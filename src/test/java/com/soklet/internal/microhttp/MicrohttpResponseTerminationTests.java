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

public class MicrohttpResponseTerminationTests {
	@Test
	public void terminalReservationIsDeliveredExactlyOnce() throws Exception {
		AtomicInteger sourceCount = new AtomicInteger();
		AtomicInteger closeCount = new AtomicInteger();
		AtomicInteger listenerCount = new AtomicInteger();
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		AtomicReference<Throwable> cause = new AtomicReference<>();
		IOException failure = new IOException("delivery failed");
		MicrohttpResponse response = MicrohttpResponse.withWritableSourceBody(
				200, "OK", List.of(), 1L, () -> {
				sourceCount.incrementAndGet();
				return new CloseTrackingWritableSource(closeCount);
			}).withBodyTerminationListener((terminalReason, terminalCause) -> {
			listenerCount.incrementAndGet();
			reason.set(terminalReason);
			cause.set(terminalCause);
		});

		WritableSource firstSource = response.writableSource(new byte[0]);
		firstSource.close(StreamTerminationReason.WRITE_FAILED, failure);
		firstSource.close(StreamTerminationReason.SERVER_STOPPING, null);
		WritableSource secondSource = response.writableSource(new byte[0]);
		secondSource.close();

		Assertions.assertEquals(0, listenerCount.get(), "source closure only reserves terminal delivery");
		response.deliverBodyTermination();
		response.deliverBodyTermination();

		Assertions.assertEquals(2, sourceCount.get());
		Assertions.assertEquals(2, closeCount.get());
		Assertions.assertEquals(1, listenerCount.get());
		Assertions.assertEquals(StreamTerminationReason.WRITE_FAILED, reason.get());
		Assertions.assertSame(failure, cause.get());
	}

	@Test
	public void streamingResponseRejectsOrdinaryBodyTerminationListener() {
		MicrohttpResponse response = MicrohttpResponse.withStreamingBody(
				200, "OK", List.of(), CloseTrackingWritableSource::new);

		Assertions.assertThrows(IllegalStateException.class,
				() -> response.withBodyTerminationListener((reason, cause) -> {}));
	}

	@Test
	public void successfulSocketWriteReportsCompletedAfterBodyClosure() throws Exception {
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicBoolean bodyClosed = new AtomicBoolean();
		AtomicInteger listenerCount = new AtomicInteger();
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		AtomicReference<Throwable> cause = new AtomicReference<>();
		Handler handler = (request, callback) -> callback.accept(
				MicrohttpResponse.withWritableSourceBody(
						200, "OK", List.of(), 4L,
						() -> new ByteArrayWritableSource(ascii("body"), bodyClosed))
						.withBodyTerminationListener((terminalReason, terminalCause) -> {
							Assertions.assertTrue(bodyClosed.get(), "body must be closed before terminal delivery");
							listenerCount.incrementAndGet();
							reason.set(terminalReason);
							cause.set(terminalCause);
							terminated.countDown();
						}));
		EventLoop eventLoop = new EventLoop(testOptions(), handler);

		try {
			eventLoop.start();
			String response = sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /success HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

			Assertions.assertTrue(response.endsWith("body"), response);
			Assertions.assertTrue(terminated.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, listenerCount.get());
			Assertions.assertEquals(StreamTerminationReason.COMPLETED, reason.get());
			Assertions.assertNull(cause.get());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void committedWriteFailureReportsWriteFailedExactlyOnce() throws Exception {
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicInteger listenerCount = new AtomicInteger();
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		AtomicReference<Throwable> cause = new AtomicReference<>();
		IOException failure = new IOException("write failed");
		Handler handler = (request, callback) -> callback.accept(
				MicrohttpResponse.withWritableSourceBody(
						200, "OK", List.of(), 1L, () -> new FailingWritableSource(failure))
						.withBodyTerminationListener((terminalReason, terminalCause) -> {
							listenerCount.incrementAndGet();
							reason.set(terminalReason);
							cause.set(terminalCause);
							terminated.countDown();
						}));
		EventLoop eventLoop = new EventLoop(testOptions(), handler);

		try {
			eventLoop.start();
			sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /failure HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

			Assertions.assertTrue(terminated.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, listenerCount.get());
			Assertions.assertEquals(StreamTerminationReason.WRITE_FAILED, reason.get());
			Assertions.assertSame(failure, cause.get());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void sourceFactoryFailureReportsInternalErrorExactlyOnce() throws Exception {
		CountDownLatch canceled = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicInteger listenerCount = new AtomicInteger();
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		AtomicReference<Throwable> cause = new AtomicReference<>();
		IOException failure = new IOException("factory failed");
		Handler handler = new Handler() {
			@Override
			public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
				callback.accept(MicrohttpResponse.withWritableSourceBody(
						200, "OK", List.of(), 1L, () -> {
							throw failure;
						}).withBodyTerminationListener((terminalReason, terminalCause) -> {
						listenerCount.incrementAndGet();
						reason.set(terminalReason);
						cause.set(terminalCause);
						terminated.countDown();
				}));
			}

			@Override
			public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
							 @Nullable Throwable cause) {
				canceled.countDown();
			}
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);

		try {
			eventLoop.start();
			sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /factory-failure HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

			Assertions.assertTrue(canceled.await(3, TimeUnit.SECONDS));
			Assertions.assertTrue(terminated.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, listenerCount.get());
			Assertions.assertEquals(StreamTerminationReason.INTERNAL_ERROR, reason.get());
			Assertions.assertSame(failure, cause.get());
		} finally {
			eventLoop.stop();
			eventLoop.join();
		}
	}

	@Test
	public void terminationListenerFailureDoesNotPoisonEventLoop() throws Exception {
		CountDownLatch failedListenerInvoked = new CountDownLatch(1);
		Handler handler = (request, callback) -> {
			MicrohttpResponse response = new MicrohttpResponse(
					200, "OK", List.of(), ascii(request.uri()));

			if ("/listener-failure".equals(request.uri())) {
				response = response.withBodyTerminationListener((reason, cause) -> {
					failedListenerInvoked.countDown();
					throw new AssertionError("listener failed");
				});
			}

			callback.accept(response);
		};
		EventLoop eventLoop = new EventLoop(testOptions(), handler);

		try {
			eventLoop.start();
			String firstResponse = sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /listener-failure HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
			Assertions.assertTrue(firstResponse.endsWith("/listener-failure"), firstResponse);
			Assertions.assertTrue(failedListenerInvoked.await(3, TimeUnit.SECONDS));

			String healthyResponse = sendRequestAndReadResponse(eventLoop.getPort(),
					"GET /healthy HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
			Assertions.assertTrue(healthyResponse.endsWith("/healthy"), healthyResponse);
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

	private static String sendRequestAndReadResponse(int port, String request) throws IOException {
		try (Socket socket = new Socket("localhost", port)) {
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(ascii(request));
			socket.getOutputStream().flush();

			ByteArrayOutputStream response = new ByteArrayOutputStream();
			InputStream inputStream = socket.getInputStream();
			byte[] buffer = new byte[256];
			int read;

			while ((read = inputStream.read(buffer)) >= 0)
				response.write(buffer, 0, read);

			return new String(response.toByteArray(), StandardCharsets.US_ASCII);
		}
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static final class CloseTrackingWritableSource implements WritableSource {
		private final AtomicInteger closeCount;

		private CloseTrackingWritableSource() {
			this(new AtomicInteger());
		}

		private CloseTrackingWritableSource(AtomicInteger closeCount) {
			this.closeCount = closeCount;
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
		public void close() {
			closeCount.incrementAndGet();
		}

		@Override
		public void close(@Nullable StreamTerminationReason reason, @Nullable Throwable cause) {
			closeCount.incrementAndGet();
		}
	}

	private static final class ByteArrayWritableSource implements WritableSource {
		private final byte[] bytes;
		private final AtomicBoolean closed;
		private int offset;

		private ByteArrayWritableSource(byte[] bytes, AtomicBoolean closed) {
			this.bytes = bytes;
			this.closed = closed;
		}

		@Override
		public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
			int count = (int) Math.min(bytes.length - offset, maxBytes);
			int written = socketChannel.write(java.nio.ByteBuffer.wrap(bytes, offset, count));
			offset += written;
			return written;
		}

		@Override
		public boolean hasRemaining() {
			return offset < bytes.length;
		}

		@Override
		public void close() {
			closed.set(true);
		}
	}

	private static final class FailingWritableSource implements WritableSource {
		private final IOException failure;

		private FailingWritableSource(IOException failure) {
			this.failure = failure;
		}

		@Override
		public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
			throw failure;
		}

		@Override
		public boolean hasRemaining() {
			return true;
		}

		@Override
		public void close() {
			// No-op
		}
	}
}
