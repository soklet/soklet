package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ConnectionEventLoopDispatchTests {
	@Test
	public void serverStopCancelsPendingDispatchOnceAndLateResponseIsDiscarded() throws Exception {
		CountDownLatch handled = new CountDownLatch(1);
		CountDownLatch canceled = new CountDownLatch(1);
		AtomicInteger cancelCount = new AtomicInteger();
		AtomicReference<MicrohttpRequest> handledRequest = new AtomicReference<>();
		AtomicReference<MicrohttpRequest> canceledRequest = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> cancelReason = new AtomicReference<>();
		AtomicReference<Consumer<MicrohttpResponse>> responseCallback = new AtomicReference<>();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				handledRequest.set(request);
				responseCallback.set(callback);
				handled.countDown();
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				canceledRequest.set(request);
				cancelReason.set(reason);
				cancelCount.incrementAndGet();
				canceled.countDown();
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);
		Socket socket = null;

		try {
			eventLoop.start();
			socket = new Socket("localhost", eventLoop.getPort());
			socket.getOutputStream().write(ascii("GET /pending HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();
			Assertions.assertTrue(handled.await(3, TimeUnit.SECONDS), "request was not dispatched");

			eventLoop.stop();
			eventLoop.join();

			Assertions.assertTrue(canceled.await(3, TimeUnit.SECONDS), "pending dispatch was not canceled");
			Assertions.assertEquals(1, cancelCount.get());
			Assertions.assertSame(handledRequest.get(), canceledRequest.get());
			Assertions.assertEquals(StreamTerminationReason.SERVER_STOPPING, cancelReason.get());

			AtomicReference<TrackingWritableSource> lateSource = new AtomicReference<>();
			responseCallback.get().accept(streamingResponse(lateSource, false));

			Assertions.assertNotNull(lateSource.get());
			Assertions.assertTrue(lateSource.get().closed.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, lateSource.get().closeCount.get());
			Assertions.assertEquals(StreamTerminationReason.SERVER_STOPPING, lateSource.get().closeReason.get());
			Assertions.assertEquals(1, cancelCount.get(), "late callback must not cancel the dispatch again");
		} finally {
			if (socket != null) {
				socket.close();
			}
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void duplicateResponseIsDiscardedWhileFirstResponseWins() throws Exception {
		AtomicReference<TrackingWritableSource> duplicateSource = new AtomicReference<>();
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("first")));
				callback.accept(streamingResponse(duplicateSource, false));
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				cancelCount.incrementAndGet();
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);

		try {
			eventLoop.start();
			String response = sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /duplicate HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.endsWith("first"), response);
			Assertions.assertNotNull(duplicateSource.get());
			Assertions.assertTrue(duplicateSource.get().closed.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, duplicateSource.get().closeCount.get());
			Assertions.assertEquals(StreamTerminationReason.INTERNAL_ERROR,
					duplicateSource.get().closeReason.get());
			Assertions.assertEquals(0, cancelCount.get());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void sourceFailureAfterCommitDoesNotCancelHandler() throws Exception {
		AtomicReference<TrackingWritableSource> failingSource = new AtomicReference<>();
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				if ("/boom".equals(request.uri())) {
					callback.accept(streamingResponse(failingSource, true));
					return;
				}

				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("healthy")));
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				cancelCount.incrementAndGet();
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);

		try {
			eventLoop.start();
			sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /boom HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

			Assertions.assertNotNull(failingSource.get());
			Assertions.assertTrue(failingSource.get().closed.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.INTERNAL_ERROR, failingSource.get().closeReason.get());
			Assertions.assertEquals(0, cancelCount.get(), "installed response source owns post-commit failure");

			String healthyResponse = sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /healthy HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
			Assertions.assertTrue(healthyResponse.endsWith("healthy"), healthyResponse);
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void initialWriteFailureAfterCommitClosesSourceWithWriteFailed() throws Exception {
		assertCommittedWriteFailure(false);
	}

	@Test
	public void callbackWriteFailureAfterCommitClosesSourceWithWriteFailed() throws Exception {
		assertCommittedWriteFailure(true);
	}

	private void assertCommittedWriteFailure(boolean deferFailureUntilCallback) throws Exception {
		FailingWriteWritableSource source = new FailingWriteWritableSource(deferFailureUntilCallback);
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				callback.accept(StreamingMicrohttpResponses.withWritableSourceBody(
						200, "OK", List.of(), () -> source));
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				cancelCount.incrementAndGet();
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);
		Socket socket = null;

		try {
			eventLoop.start();
			socket = new Socket("localhost", eventLoop.getPort());
			socket.getOutputStream().write(ascii(
					"GET /write-failure HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();

			Assertions.assertTrue(source.started.await(3, TimeUnit.SECONDS));
			Assertions.assertTrue(source.firstWriteAttempt.await(3, TimeUnit.SECONDS));
			if (deferFailureUntilCallback)
				source.triggerFailure();

			Assertions.assertTrue(source.closed.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, source.closeCount.get());
			Assertions.assertEquals(StreamTerminationReason.WRITE_FAILED,
					source.closeReason.get());
			Assertions.assertSame(source.failure, source.closeCause.get());
			Assertions.assertEquals(0, cancelCount.get(),
					"installed response source owns post-commit write failure");
		} finally {
			if (socket != null)
				socket.close();
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void sourceFactoryFailureBeforeCommitCancelsHandler() throws Exception {
		CountDownLatch canceled = new CountDownLatch(1);
		AtomicInteger sourceFactoryCalls = new AtomicInteger();
		AtomicInteger cancelCount = new AtomicInteger();
		AtomicReference<MicrohttpRequest> failedRequest = new AtomicReference<>();
		AtomicReference<MicrohttpRequest> canceledRequest = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> cancelReason = new AtomicReference<>();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				if ("/factory-failure".equals(request.uri())) {
					failedRequest.set(request);
					callback.accept(StreamingMicrohttpResponses.withWritableSourceBody(
							200, "OK", List.of(), () -> {
								sourceFactoryCalls.incrementAndGet();
								throw new IllegalStateException("factory failed");
							}));
					return;
				}

				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("healthy")));
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				canceledRequest.set(request);
				cancelReason.set(reason);
				cancelCount.incrementAndGet();
				canceled.countDown();
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);

		try {
			eventLoop.start();
			sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /factory-failure HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

			Assertions.assertTrue(canceled.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, sourceFactoryCalls.get(), "a failed source factory must not be retried for cleanup");
			Assertions.assertEquals(1, cancelCount.get());
			Assertions.assertSame(failedRequest.get(), canceledRequest.get());
			Assertions.assertEquals(StreamTerminationReason.INTERNAL_ERROR, cancelReason.get());

			String healthyResponse = sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /healthy HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
			Assertions.assertTrue(healthyResponse.endsWith("healthy"), healthyResponse);
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void protocolRejectionDoesNotRetryThrowingBodyCleanup() throws Exception {
		CountDownLatch canceled = new CountDownLatch(1);
		AtomicInteger sourceFactoryCalls = new AtomicInteger();
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				callback.accept(StreamingMicrohttpResponses.withWritableSourceBody(
						200, "OK", List.of(), () -> {
						sourceFactoryCalls.incrementAndGet();
						return new ThrowingCloseWritableSource();
					}));
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				cancelCount.incrementAndGet();
				canceled.countDown();
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);

		try {
			eventLoop.start();
			sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /unsupported-stream HTTP/1.0\r\nConnection: close\r\n\r\n");

			Assertions.assertTrue(canceled.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, sourceFactoryCalls.get(), "throwing cleanup must retain single ownership");
			Assertions.assertEquals(1, cancelCount.get());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void lateDuplicateCannotStealCancellationFromNextPipelinedDispatch() throws Exception {
		CountDownLatch secondHandled = new CountDownLatch(1);
		CountDownLatch canceled = new CountDownLatch(1);
		AtomicReference<Consumer<MicrohttpResponse>> firstCallback = new AtomicReference<>();
		AtomicReference<MicrohttpRequest> secondRequest = new AtomicReference<>();
		AtomicReference<MicrohttpRequest> canceledRequest = new AtomicReference<>();
		AtomicReference<TrackingWritableSource> duplicateSource = new AtomicReference<>();
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				if ("/one".equals(request.uri())) {
					firstCallback.set(callback);
					callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("one")));
					return;
				}

				secondRequest.set(request);
				secondHandled.countDown();
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				canceledRequest.set(request);
				cancelCount.incrementAndGet();
				canceled.countDown();
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);
		Socket socket = null;

		try {
			eventLoop.start();
			socket = new Socket("localhost", eventLoop.getPort());
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(ascii(
					"GET /one HTTP/1.1\r\nHost: localhost\r\n\r\n"
							+ "GET /two HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();

			String firstResponse = readUntil(socket.getInputStream(), "one");
			Assertions.assertTrue(firstResponse.startsWith("HTTP/1.1 200 OK"), firstResponse);
			Assertions.assertTrue(secondHandled.await(3, TimeUnit.SECONDS), "second request was not dispatched");

			firstCallback.get().accept(streamingResponse(duplicateSource, false));
			Assertions.assertNotNull(duplicateSource.get());
			Assertions.assertTrue(duplicateSource.get().closed.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.INTERNAL_ERROR,
					duplicateSource.get().closeReason.get());

			eventLoop.stop();
			eventLoop.join();

			Assertions.assertTrue(canceled.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, cancelCount.get());
			Assertions.assertSame(secondRequest.get(), canceledRequest.get(),
					"late first-response callback must not clear the second dispatch ticket");
		} finally {
			if (socket != null) {
				socket.close();
			}
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void monitoredPendingRequestDetectsClientResetBeforeResponse() throws Exception {
		CountDownLatch handled = new CountDownLatch(1);
		CountDownLatch canceled = new CountDownLatch(1);
		AtomicInteger cancelCount = new AtomicInteger();
		AtomicReference<MicrohttpRequest> handledRequest = new AtomicReference<>();
		AtomicReference<MicrohttpRequest> canceledRequest = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> cancelReason = new AtomicReference<>();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				handledRequest.set(request);
				handled.countDown();
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				canceledRequest.set(request);
				cancelReason.set(reason);
				cancelCount.incrementAndGet();
				canceled.countDown();
			}

			@Override
			public boolean monitorClientDisconnectsBeforeResponse(MicrohttpRequest request) {
				return true;
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);
		Socket socket = null;

		try {
			eventLoop.start();
			socket = new Socket("localhost", eventLoop.getPort());
			socket.getOutputStream().write(ascii("GET /reset HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();
			Assertions.assertTrue(handled.await(3, TimeUnit.SECONDS), "request was not dispatched");

			socket.setSoLinger(true, 0);
			socket.close();
			socket = null;

			Assertions.assertTrue(canceled.await(3, TimeUnit.SECONDS),
					"client reset was not detected while response remained pending");
			Assertions.assertEquals(1, cancelCount.get());
			Assertions.assertSame(handledRequest.get(), canceledRequest.get());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, cancelReason.get());
		} finally {
			if (socket != null) {
				socket.close();
			}
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void cancelHookFailureDoesNotPreventConnectionCleanup() throws Exception {
		CountDownLatch handled = new CountDownLatch(1);
		CountDownLatch canceled = new CountDownLatch(1);
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				if ("/pending".equals(request.uri())) {
					handled.countDown();
					return;
				}

				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("healthy")));
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				cancelCount.incrementAndGet();
				canceled.countDown();
				throw new AssertionError("cancel hook failed");
			}

			@Override
			public boolean monitorClientDisconnectsBeforeResponse(MicrohttpRequest request) {
				return "/pending".equals(request.uri());
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);
		Socket socket = null;

		try {
			eventLoop.start();
			socket = new Socket("localhost", eventLoop.getPort());
			socket.getOutputStream().write(ascii("GET /pending HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();
			Assertions.assertTrue(handled.await(3, TimeUnit.SECONDS));

			socket.setSoLinger(true, 0);
			socket.close();
			socket = null;
			Assertions.assertTrue(canceled.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, cancelCount.get());

			String healthyResponse = sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /healthy HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
			Assertions.assertTrue(healthyResponse.endsWith("healthy"), healthyResponse);
		} finally {
			if (socket != null) {
				socket.close();
			}
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void monitoredHalfCloseAtBufferLimitStillReceivesResponse() throws Exception {
		CountDownLatch handled = new CountDownLatch(1);
		CountDownLatch bufferLimitReached = new CountDownLatch(1);
		CountDownLatch halfCloseObserved = new CountDownLatch(1);
		AtomicReference<Consumer<MicrohttpResponse>> responseCallback = new AtomicReference<>();
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				responseCallback.set(callback);
				handled.countDown();
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				cancelCount.incrementAndGet();
			}

			@Override
			public boolean monitorClientDisconnectsBeforeResponse(MicrohttpRequest request) {
				return true;
			}
		};
		Logger logger = new Logger() {
			@Override
			public boolean enabled() {
				return true;
			}

			@Override
			public void log(LogEntry... entries) {
				String event = logValue(entries, "event");

				if ("read_bytes_while_response_pending".equals(event)
						&& "128".equals(logValue(entries, "buffered_request_bytes"))) {
					bufferLimitReached.countDown();
				} else if ("read_half_close_while_response_pending".equals(event)) {
					halfCloseObserved.countDown();
				}
			}

			@Override
			public void log(Exception exception, LogEntry... entries) {
				log(entries);
			}
		};
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofSeconds(3))
				.withRequestBodyTimeout(Duration.ofSeconds(3))
				.withMaxRequestSize(128)
				.withConcurrency(1)
				.build();
		EventLoop eventLoop = new EventLoop(options, logger, handler);
		Socket socket = null;

		try {
			eventLoop.start();
			socket = new Socket("localhost", eventLoop.getPort());
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(ascii("GET /half-close HTTP/1.1\r\nHost: localhost\r\n"
					+ "Connection: close\r\n\r\n"));
			socket.getOutputStream().flush();
			Assertions.assertTrue(handled.await(3, TimeUnit.SECONDS));

			socket.getOutputStream().write(new byte[128]);
			socket.getOutputStream().flush();
			Assertions.assertTrue(bufferLimitReached.await(3, TimeUnit.SECONDS),
					"pending-response buffer did not reach its configured bound");
			socket.shutdownOutput();
			Assertions.assertTrue(halfCloseObserved.await(3, TimeUnit.SECONDS),
					"read monitoring stopped at the buffer bound");

			responseCallback.get().accept(new MicrohttpResponse(200, "OK", List.of(), ascii("response")));
			String response = readAll(socket.getInputStream());

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.endsWith("response"), response);
			Assertions.assertEquals(0, cancelCount.get(), "input half-close is not a client disconnect");
		} finally {
			if (socket != null) {
				socket.close();
			}
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void monitoredHalfCloseDrainsAlreadyBufferedPipelinedRequests() throws Exception {
		CountDownLatch firstHandled = new CountDownLatch(1);
		CountDownLatch secondHandled = new CountDownLatch(1);
		CountDownLatch halfCloseObserved = new CountDownLatch(1);
		AtomicReference<Consumer<MicrohttpResponse>> firstCallback = new AtomicReference<>();
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				if ("/one".equals(request.uri())) {
					firstCallback.set(callback);
					firstHandled.countDown();
					return;
				}

				secondHandled.countDown();
				callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("two")));
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				cancelCount.incrementAndGet();
			}

			@Override
			public boolean monitorClientDisconnectsBeforeResponse(MicrohttpRequest request) {
				return true;
			}
		};
		Logger logger = new Logger() {
			@Override
			public boolean enabled() {
				return true;
			}

			@Override
			public void log(LogEntry... entries) {
				if ("read_half_close_while_response_pending".equals(logValue(entries, "event"))) {
					halfCloseObserved.countDown();
				}
			}

			@Override
			public void log(Exception exception, LogEntry... entries) {
				log(entries);
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), logger, handler);
		Socket socket = null;

		try {
			eventLoop.start();
			socket = new Socket("localhost", eventLoop.getPort());
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(ascii("GET /one HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();
			Assertions.assertTrue(firstHandled.await(3, TimeUnit.SECONDS));

			socket.getOutputStream().write(ascii(
					"GET /two HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
			socket.getOutputStream().flush();
			socket.shutdownOutput();
			Assertions.assertTrue(halfCloseObserved.await(3, TimeUnit.SECONDS));

			firstCallback.get().accept(new MicrohttpResponse(200, "OK", List.of(), ascii("one")));
			String response = readAll(socket.getInputStream());

			Assertions.assertTrue(secondHandled.await(3, TimeUnit.SECONDS));
			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.contains("\r\n\r\noneHTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.endsWith("two"), response);
			Assertions.assertEquals(0, cancelCount.get());
		} finally {
			if (socket != null) {
				socket.close();
			}
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void gracefulDrainAllowsMonitoredPendingResponseToFinish() throws Exception {
		CountDownLatch handled = new CountDownLatch(1);
		CountDownLatch drainedByteRead = new CountDownLatch(1);
		AtomicReference<Consumer<MicrohttpResponse>> responseCallback = new AtomicReference<>();
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				responseCallback.set(callback);
				handled.countDown();
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				cancelCount.incrementAndGet();
			}

			@Override
			public boolean monitorClientDisconnectsBeforeResponse(MicrohttpRequest request) {
				return true;
			}
		};
		Logger logger = new Logger() {
			@Override
			public boolean enabled() {
				return true;
			}

			@Override
			public void log(LogEntry... entries) {
				if ("read_bytes_while_response_pending".equals(logValue(entries, "event"))
						&& "1".equals(logValue(entries, "buffered_request_bytes"))) {
					drainedByteRead.countDown();
				}
			}

			@Override
			public void log(Exception exception, LogEntry... entries) {
				log(entries);
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), logger, handler);
		Socket socket = null;

		try {
			eventLoop.start();
			socket = new Socket("localhost", eventLoop.getPort());
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(ascii("GET /drain HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();
			Assertions.assertTrue(handled.await(3, TimeUnit.SECONDS));

			eventLoop.beginDrain();
			socket.getOutputStream().write(ascii("G"));
			socket.getOutputStream().flush();
			Assertions.assertTrue(drainedByteRead.await(3, TimeUnit.SECONDS),
					"bounded disconnect monitoring stopped before response commitment during drain");
			Assertions.assertEquals(0, cancelCount.get(), "graceful drain must preserve an in-flight dispatch");

			responseCallback.get().accept(new MicrohttpResponse(200, "OK", List.of(), ascii("drained")));
			String response = readAll(socket.getInputStream());

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
			Assertions.assertTrue(response.endsWith("drained"), response);
			Assertions.assertEquals(0, cancelCount.get());
		} finally {
			if (socket != null) {
				socket.close();
			}
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void monitoredStreamingResponseDetectsClientResetAfterCommit() throws Exception {
		TrackingWritableSource source = new TrackingWritableSource(false);
		AtomicInteger cancelCount = new AtomicInteger();
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				callback.accept(StreamingMicrohttpResponses.withWritableSourceBody(
						200, "OK", List.of(), () -> source));
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
									 @Nullable Throwable cause) {
				cancelCount.incrementAndGet();
			}

			@Override
			public boolean monitorClientDisconnectsDuringStreamingResponse(MicrohttpRequest request) {
				return true;
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);
		Socket socket = null;

		try {
			eventLoop.start();
			socket = new Socket("localhost", eventLoop.getPort());
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(ascii("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();

			Assertions.assertTrue(source.started.await(3, TimeUnit.SECONDS),
					"streaming source was not committed and started");
			String responseHead = readUntil(socket.getInputStream(), "\r\n\r\n");
			Assertions.assertTrue(responseHead.startsWith("HTTP/1.1 200 OK"), responseHead);

			socket.setSoLinger(true, 0);
			socket.close();
			socket = null;

			Assertions.assertTrue(source.closed.await(3, TimeUnit.SECONDS),
					"reset was not detected after the streaming response became idle");
			Assertions.assertEquals(1, source.closeCount.get());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, source.closeReason.get());
			Assertions.assertEquals(0, cancelCount.get(), "post-commit disconnect must not cancel the handler");
		} finally {
			if (socket != null) {
				socket.close();
			}
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void monitoredStreamingResponsePreservesClientInputHalfClose() throws Exception {
		TrackingWritableSource source = new TrackingWritableSource(false);
		CountDownLatch halfCloseObserved = new CountDownLatch(1);
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				callback.accept(StreamingMicrohttpResponses.withWritableSourceBody(
						200, "OK", List.of(), () -> source));
			}

			@Override
			public boolean monitorClientDisconnectsDuringStreamingResponse(MicrohttpRequest request) {
				return true;
			}
		};
		Logger logger = new Logger() {
			@Override
			public boolean enabled() {
				return true;
			}

			@Override
			public void log(LogEntry... entries) {
				if ("read_half_close_during_streaming_response".equals(logValue(entries, "event"))) {
					halfCloseObserved.countDown();
				}
			}

			@Override
			public void log(Exception exception, LogEntry... entries) {
				log(entries);
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), logger, handler);

		try (Socket socket = new Socket("localhost", eventLoop.getPort())) {
			eventLoop.start();
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(ascii("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();
			Assertions.assertTrue(source.started.await(3, TimeUnit.SECONDS));
			readUntil(socket.getInputStream(), "\r\n\r\n");

			socket.shutdownOutput();

			Assertions.assertTrue(halfCloseObserved.await(3, TimeUnit.SECONDS));
			Assertions.assertFalse(source.closed.await(200, TimeUnit.MILLISECONDS),
					"an input FIN must not cancel a committed response stream");
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void monitoredStreamingResponseDiscardsPipelinedBytesWithinBound() throws Exception {
		int maximumDiscardedBytes = 128;
		TrackingWritableSource source = new TrackingWritableSource(false);
		AtomicInteger handledCount = new AtomicInteger();
		CountDownLatch boundReached = new CountDownLatch(1);
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				handledCount.incrementAndGet();
				callback.accept(StreamingMicrohttpResponses.withWritableSourceBody(
						200, "OK", List.of(), () -> source));
			}

			@Override
			public boolean monitorClientDisconnectsDuringStreamingResponse(MicrohttpRequest request) {
				return true;
			}
		};
		Logger logger = new Logger() {
			@Override
			public boolean enabled() {
				return true;
			}

			@Override
			public void log(LogEntry... entries) {
				if ("read_bytes_during_streaming_response".equals(logValue(entries, "event"))
						&& Integer.toString(maximumDiscardedBytes).equals(logValue(entries, "discarded_bytes"))) {
					boundReached.countDown();
				}
			}

			@Override
			public void log(Exception exception, LogEntry... entries) {
				log(entries);
			}
		};
		Options options = OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofSeconds(3))
				.withRequestBodyTimeout(Duration.ofSeconds(3))
				.withMaxRequestSize(maximumDiscardedBytes)
				.withConcurrency(1)
				.build();
		EventLoop eventLoop = new EventLoop(options, logger, handler);

		try (Socket socket = new Socket("localhost", eventLoop.getPort())) {
			eventLoop.start();
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(ascii("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n"));
			socket.getOutputStream().flush();
			Assertions.assertTrue(source.started.await(3, TimeUnit.SECONDS));
			readUntil(socket.getInputStream(), "\r\n\r\n");

			// A complete-looking pipelined request is opaque discarded input in this state.
			byte[] pipelined = new byte[maximumDiscardedBytes];
			byte[] requestPrefix = ascii("GET /must-not-dispatch HTTP/1.1\r\nHost: localhost\r\n\r\n");
			System.arraycopy(requestPrefix, 0, pipelined, 0, requestPrefix.length);
			socket.getOutputStream().write(pipelined);
			socket.getOutputStream().flush();

			Assertions.assertTrue(boundReached.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, handledCount.get(), "discarded bytes must never be dispatched");
			Assertions.assertEquals(1L, source.closed.getCount(), "the configured bound itself is permitted");

			socket.getOutputStream().write(1);
			socket.getOutputStream().flush();
			Assertions.assertTrue(source.closed.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.BACKPRESSURE, source.closeReason.get());
			Assertions.assertEquals(1, source.closeCount.get());
			Assertions.assertEquals(1, handledCount.get());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	private static Options testOptions() {
		return OptionsBuilder.newBuilder()
				.withPort(0)
				.withResolution(Duration.ofMillis(10))
				.withRequestHeaderTimeout(Duration.ofSeconds(3))
				.withRequestBodyTimeout(Duration.ofSeconds(3))
				.withConcurrency(1)
				.build();
	}

	private static MicrohttpResponse streamingResponse(AtomicReference<TrackingWritableSource> sourceRef,
																				 boolean failOnStart) {
		return StreamingMicrohttpResponses.withWritableSourceBody(200, "OK", List.of(), () -> {
			TrackingWritableSource source = new TrackingWritableSource(failOnStart);
			Assertions.assertTrue(sourceRef.compareAndSet(null, source), "body source was created more than once");
			return source;
		});
	}

	private static String sendRequestAndReadResponse(int port, String request) throws IOException {
		try (Socket socket = new Socket("localhost", port)) {
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(ascii(request));
			socket.getOutputStream().flush();

			ByteArrayOutputStream response = new ByteArrayOutputStream();
			InputStream inputStream = socket.getInputStream();
			byte[] buffer = new byte[256];
			int read;

			while ((read = inputStream.read(buffer)) >= 0) {
				response.write(buffer, 0, read);
			}

			return new String(response.toByteArray(), StandardCharsets.US_ASCII);
		}
	}

	private static String readUntil(InputStream inputStream, String expectedSuffix) throws IOException {
		ByteArrayOutputStream response = new ByteArrayOutputStream();
		byte[] buffer = new byte[64];

		while (true) {
			int read = inputStream.read(buffer);

			if (read < 0) {
				return new String(response.toByteArray(), StandardCharsets.US_ASCII);
			}

			response.write(buffer, 0, read);
			String value = new String(response.toByteArray(), StandardCharsets.US_ASCII);

			if (value.endsWith(expectedSuffix)) {
				return value;
			}
		}
	}

	private static String readAll(InputStream inputStream) throws IOException {
		ByteArrayOutputStream response = new ByteArrayOutputStream();
		byte[] buffer = new byte[256];
		int read;

		while ((read = inputStream.read(buffer)) >= 0) {
			response.write(buffer, 0, read);
		}

		return new String(response.toByteArray(), StandardCharsets.US_ASCII);
	}

	private static @Nullable String logValue(LogEntry[] entries, String key) {
		for (LogEntry entry : entries) {
			if (key.equals(entry.key())) {
				return entry.value();
			}
		}

		return null;
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static final class TrackingWritableSource implements WritableSource {
		private final boolean failOnStart;
		private final CountDownLatch started;
		private final CountDownLatch closed;
		private final AtomicInteger closeCount;
		private final AtomicReference<StreamTerminationReason> closeReason;

		private TrackingWritableSource(boolean failOnStart) {
			this.failOnStart = failOnStart;
			this.started = new CountDownLatch(1);
			this.closed = new CountDownLatch(1);
			this.closeCount = new AtomicInteger();
			this.closeReason = new AtomicReference<>();
		}

		@Override
		public void start() throws IOException {
			started.countDown();
			if (failOnStart) {
				throw new IOException("start failed");
			}
		}

		@Override
		public long writeTo(SocketChannel socketChannel, long maxBytes) {
			return 0L;
		}

		@Override
		public boolean hasRemaining() {
			return true;
		}

		@Override
		public boolean isReadyToWrite() {
			return false;
		}

		@Override
		public void close() {
			recordClose(null);
		}

		@Override
		public void close(@Nullable StreamTerminationReason reason, @Nullable Throwable cause) {
			recordClose(reason);
		}

		private void recordClose(@Nullable StreamTerminationReason reason) {
			closeReason.compareAndSet(null, reason);
			closeCount.incrementAndGet();
			closed.countDown();
		}
	}

	private static final class FailingWriteWritableSource implements WritableSource {
		private final IOException failure;
		private final CountDownLatch started;
		private final CountDownLatch firstWriteAttempt;
		private final CountDownLatch closed;
		private final AtomicBoolean failWrites;
		private final AtomicReference<Runnable> writeReadyCallback;
		private final AtomicInteger closeCount;
		private final AtomicReference<StreamTerminationReason> closeReason;
		private final AtomicReference<Throwable> closeCause;

		private FailingWriteWritableSource(boolean deferFailureUntilCallback) {
			this.failure = new IOException("write failed");
			this.started = new CountDownLatch(1);
			this.firstWriteAttempt = new CountDownLatch(1);
			this.closed = new CountDownLatch(1);
			this.failWrites = new AtomicBoolean(!deferFailureUntilCallback);
			this.writeReadyCallback = new AtomicReference<>();
			this.closeCount = new AtomicInteger();
			this.closeReason = new AtomicReference<>();
			this.closeCause = new AtomicReference<>();
		}

		@Override
		public void start() {
			started.countDown();
		}

		@Override
		public void writeReadyCallback(Runnable callback) {
			writeReadyCallback.set(callback);
		}

		@Override
		public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
			firstWriteAttempt.countDown();
			if (failWrites.get())
				throw failure;

			return 0L;
		}

		@Override
		public boolean hasRemaining() {
			return true;
		}

		@Override
		public boolean isReadyToWrite() {
			return failWrites.get();
		}

		@Override
		public void close() {
			recordClose(null, null);
		}

		@Override
		public void close(@Nullable StreamTerminationReason reason, @Nullable Throwable cause) {
			recordClose(reason, cause);
		}

		private void triggerFailure() {
			failWrites.set(true);
			Runnable callback = writeReadyCallback.get();
			if (callback == null)
				throw new IllegalStateException("Write-ready callback was not installed.");
			callback.run();
		}

		private void recordClose(@Nullable StreamTerminationReason reason,
				@Nullable Throwable cause) {
			closeReason.compareAndSet(null, reason);
			closeCause.compareAndSet(null, cause);
			closeCount.incrementAndGet();
			closed.countDown();
		}
	}

	private static final class ThrowingCloseWritableSource implements WritableSource {
		@Override
		public long writeTo(SocketChannel socketChannel, long maxBytes) {
			return 0L;
		}

		@Override
		public boolean hasRemaining() {
			return true;
		}

		@Override
		public void close() throws IOException {
			throw new IOException("close failed");
		}

		@Override
		public void close(@Nullable StreamTerminationReason reason, @Nullable Throwable cause) throws IOException {
			throw new IOException("close failed");
		}
	}
}
