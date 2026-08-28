/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.WritableSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@NotThreadSafe
@Timeout(60)
class McpHttpServerObservationTerminalRaceTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String APPLICATION_METHOD = "test/execute";

	@Test
	@Timeout(120)
	void lifecycleLeaseOutlivesBodyCompletionUntilApplicationExchangeUnwinds()
			throws Exception {
		RecordingObservation observation = new RecordingObservation();
		AtomicInteger releases = new AtomicInteger();
		AtomicInteger releaseCountDuringCallback = new AtomicInteger(-1);
		AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
		McpHttpServerRuntime runtime = runtime(acceptingPolicy(),
				invocation -> completeResult("body-first"), observation,
				McpApplicationClock.SYSTEM);

		try {
			InetSocketAddress address = runtime.start();
			MicrohttpRequest request = request(address, "body-first-lease");
			submit(runtime, address, request, releases::incrementAndGet, response -> {
				try {
					completeBody(response);
					releaseCountDuringCallback.set(releases.get());
				} catch (Throwable throwable) {
					callbackFailure.set(throwable);
				}
			});

			awaitCondition(() -> releaseCountDuringCallback.get() >= 0,
					"The response body did not complete in the application callback.");
			Assertions.assertNull(callbackFailure.get());
			Assertions.assertEquals(0, releaseCountDuringCallback.get(),
					"Body completion cannot release the generation lease while the "
							+ "application exchange remains on the handler stack.");
			awaitCondition(() -> releases.get() == 1,
					"Application unwind did not release the completed-body lease.");
			awaitClean(runtime);
			Assertions.assertEquals(1, releases.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	void lifecycleLeaseOutlivesApplicationExchangeUntilBodyCompletion()
			throws Exception {
		RecordingObservation observation = new RecordingObservation();
		AtomicInteger releases = new AtomicInteger();
		AtomicReference<MicrohttpResponse> response = new AtomicReference<>();
		McpHttpServerRuntime runtime = runtime(acceptingPolicy(),
				invocation -> completeResult("handler-first"), observation,
				McpApplicationClock.SYSTEM);

		try {
			InetSocketAddress address = runtime.start();
			MicrohttpRequest request = request(address, "handler-first-lease");
			submit(runtime, address, request, releases::incrementAndGet,
					offered -> Assertions.assertTrue(
							response.compareAndSet(null, offered)));

			awaitValue(response, "The nonstreaming response was not offered.");
			awaitCondition(() -> {
				McpApplicationExecutionSnapshot snapshot = runtime
						.applicationExecutionSnapshot().orElseThrow();
				return snapshot.activeHandlerSlots() == 0
						&& snapshot.retainedExchanges() == 0;
			}, "The application exchange did not unwind.");
			Assertions.assertEquals(0, releases.get(),
					"Application unwind cannot release the generation lease before "
							+ "the response body terminates.");
			Assertions.assertEquals(1,
					runtime.requestExecutionSnapshot().retainedRequestControls());

			completeBody(response.get());
			awaitClean(runtime);
			Assertions.assertEquals(1, releases.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	void protocol_completion_cannot_preempt_inline_stream_terminal_owner()
			throws Exception {
		InlineExecutorService executor = new InlineExecutorService();
		RecordingObservation observation = new RecordingObservation();
		AtomicReference<MicrohttpResponse> streamingResponse =
				new AtomicReference<>();
		WritableSource source = null;
		RecordingSocketChannel socket = null;
		McpHttpServerRuntime runtime = runtime(acceptingPolicy(), invocation -> {
			Assertions.assertTrue(invocation.sendNotification(
					progress("inline-terminal-owner")));
			return completeResult("inline-terminal-owner");
		}, observation, McpApplicationClock.SYSTEM,
				McpJsonLimits.productionDefaults(), ignored -> executor);

		try {
			InetSocketAddress address = runtime.start();
			submit(runtime, address, request(address, "inline-terminal-owner"),
					response -> Assertions.assertTrue(
							streamingResponse.compareAndSet(null, response),
							"The inline SSE response callback must be offered once."));
			MicrohttpResponse response = awaitValue(streamingResponse,
					"The inline SSE response was not offered.");
			Assertions.assertTrue(response.streaming());

			// Do not give the transport a body source until the protocol task has
			// returned. This deterministically exercises the handoff in which an
			// inline handler already owns a reserved stream terminal.
			ThreadPoolExecutor processor = processor(runtime);
			awaitCondition(() -> processor.getActiveCount() == 0,
					"The inline protocol task did not complete.");

			source = newBodySource(response);
			socket = new RecordingSocketChannel();
			source.writeReadyCallback(() -> {
				// The test drains every queued frame directly.
			});
			source.start();
			for (int write = 0; write < 8 && source.isReadyToWrite(); write++)
				source.writeTo(socket, Long.MAX_VALUE);
			Assertions.assertFalse(source.hasRemaining(),
					"The inline terminal response did not finish writing.");

			String body = socket.writtenText();
			Assertions.assertTrue(body.contains(
					"\"value\":\"inline-terminal-owner\""), body);
			Assertions.assertTrue(body.endsWith("0\r\n\r\n"), body);
			observation.awaitFinished();
			awaitClean(runtime);
			Assertions.assertEquals(0,
					runtime.diagnosticsSnapshot().activeRequestStreams());
			observation.assertExactlyOne(McpRequestOutcome.COMPLETE);
			observation.assertExactlyOneCompletedStream();
		} finally {
			if (source != null)
				source.close(StreamTerminationReason.SERVER_STOPPING, null);
			if (socket != null)
				socket.close();
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	void written_sse_terminal_beats_concurrent_client_cancel_exactly_once()
			throws Exception {
		TerminalWriteBlockingClock clock = new TerminalWriteBlockingClock();
		RecordingObservation observation = new RecordingObservation();
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerSentProgress = new CountDownLatch(1);
		AtomicReference<MicrohttpResponse> streamingResponse = new AtomicReference<>();
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
		AtomicReference<WritableSource> sourceReference = new AtomicReference<>();
		AtomicReference<RecordingSocketChannel> socketReference = new AtomicReference<>();
		Thread writer = null;
		Thread canceler = null;
		McpHttpServerRuntime runtime = runtime(
				acceptingPolicy(), invocation -> {
					Assertions.assertTrue(invocation.sendNotification(
							progress("terminal-cancel-race")));
					handlerSentProgress.countDown();
					releaseHandler.await();
					return completeResult("terminal-wins");
				}, observation, clock);

		try {
			InetSocketAddress address = runtime.start();
			MicrohttpRequest request = request(address, "terminal-cancel-race");
			submit(runtime, address, request, response -> {
				Assertions.assertTrue(streamingResponse.compareAndSet(null, response),
						"The SSE response callback must be offered once.");
			});

			Assertions.assertTrue(handlerSentProgress.await(5, TimeUnit.SECONDS));
			MicrohttpResponse response = awaitValue(streamingResponse,
					"The first SSE response was not offered.");
			Assertions.assertTrue(response.streaming());
			WritableSource source = newBodySource(response);
			sourceReference.set(source);
			RecordingSocketChannel socket = new RecordingSocketChannel();
			socketReference.set(socket);
			source.writeReadyCallback(() -> {
				// The test drives every write directly.
			});
			source.start();
			Assertions.assertTrue(source.writeTo(socket, Long.MAX_VALUE) > 0L,
					"The progress event was not written.");
			Assertions.assertFalse(source.isReadyToWrite(),
					"Only the progress event should be ready before handler completion.");

			releaseHandler.countDown();
			awaitCondition(source::isReadyToWrite,
					"The terminal SSE message was not reserved.");

			writer = new Thread(() -> {
				try {
					clock.blockNextCallOnCurrentThread();
					source.writeTo(socket, Long.MAX_VALUE);
				} catch (Throwable throwable) {
					writerFailure.set(throwable);
				}
			}, "mcp-terminal-byte-writer");
			writer.start();
			Assertions.assertTrue(clock.writeTimestampEntered.await(5, TimeUnit.SECONDS),
					"The terminal write did not reach its post-write timestamp boundary.");

			CountDownLatch cancelInvoked = new CountDownLatch(1);
			canceler = new Thread(() -> {
				cancelInvoked.countDown();
				try {
					cancel(runtime, request, StreamTerminationReason.CLIENT_DISCONNECTED,
							new IOException("client disconnected after terminal write"));
				} catch (Throwable throwable) {
					cancelFailure.set(throwable);
				}
			}, "mcp-terminal-byte-canceler");
			canceler.start();
			Assertions.assertTrue(cancelInvoked.await(5, TimeUnit.SECONDS));
			Thread cancelThread = canceler;
			awaitCondition(() -> cancelThread.getState() == Thread.State.BLOCKED,
					"Cancel did not contend after the terminal byte was written.");

			clock.releaseWriteTimestamp.countDown();
			writer.join(TimeUnit.SECONDS.toMillis(5));
			canceler.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(writer.isAlive());
			Assertions.assertFalse(canceler.isAlive());
			Assertions.assertNull(writerFailure.get());
			Assertions.assertNull(cancelFailure.get());
			observation.awaitFinished();
			awaitClean(runtime);

			String body = socket.writtenText();
			Assertions.assertTrue(body.contains("\"value\":\"terminal-wins\""), body);
			Assertions.assertTrue(body.endsWith("0\r\n\r\n"), body);
			observation.assertExactlyOne(McpRequestOutcome.COMPLETE);
			Assertions.assertNull(observation.error.get());
			Assertions.assertEquals(List.of(), observation.throwables.get());
		} finally {
			releaseHandler.countDown();
			clock.releaseWriteTimestamp.countDown();
			WritableSource source = sourceReference.get();
			if (source != null)
				source.close(StreamTerminationReason.SERVER_STOPPING, null);
			if (writer != null)
				writer.join(TimeUnit.SECONDS.toMillis(5));
			if (canceler != null)
				canceler.join(TimeUnit.SECONDS.toMillis(5));
			RecordingSocketChannel socket = socketReference.get();
			if (socket != null)
				socket.close();
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	void precommit_mapped_error_beats_late_client_cancel_exactly_once()
			throws Exception {
		RecordingObservation observation = new RecordingObservation();
		IllegalStateException handlerFailure = new IllegalStateException(
				"handler-secret-must-not-reach-the-wire");
		IOException cancellationCause = new IOException(
				"cancel-secret-must-not-reach-the-wire");
		AtomicReference<MicrohttpResponse> renderedResponse = new AtomicReference<>();
		AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();
		McpHttpServerRuntime runtime = errorRaceRuntime(invocation -> {
			throw handlerFailure;
		}, observation, McpApplicationClock.SYSTEM);

		try {
			InetSocketAddress address = runtime.start();
			MicrohttpRequest request = request(address, "precommit-error-owner");
			submit(runtime, address, request, response -> {
				Assertions.assertTrue(renderedResponse.compareAndSet(null, response),
						"The mapped response callback must be offered once.");
				try {
					cancel(runtime, request,
							StreamTerminationReason.CLIENT_DISCONNECTED,
							cancellationCause);
				} catch (Throwable throwable) {
					cancellationFailure.set(throwable);
				}
			});

			MicrohttpResponse response = awaitValue(renderedResponse,
					"The mapped error response was not offered.");
			Assertions.assertNull(cancellationFailure.get());
			Assertions.assertEquals(500, response.status());
			String body = new String(response.body(), StandardCharsets.UTF_8);
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"precommit-error-owner\","
							+ "\"error\":{\"code\":-32603,"
							+ "\"message\":\"Internal error\"}}",
					body);
			assertRedacted(body, handlerFailure, cancellationCause);

			completeBody(response);
			observation.awaitFinished();
			awaitClean(runtime);
			observation.assertExactlyOneInternalError(handlerFailure);
			observation.assertProtocolErrors(
					List.of(McpJsonRpcError.INTERNAL_ERROR));
			observation.assertNoStream();
			Assertions.assertEquals(0,
					runtime.diagnosticsSnapshot().activeRequestStreams());
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	void written_streamed_error_terminal_beats_concurrent_client_cancel_exactly_once()
			throws Exception {
		TerminalWriteBlockingClock clock = new TerminalWriteBlockingClock();
		RecordingObservation observation = new RecordingObservation();
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerSentProgress = new CountDownLatch(1);
		IllegalStateException handlerFailure = new IllegalStateException(
				"stream-handler-secret-must-not-reach-the-wire");
		IOException cancellationCause = new IOException(
				"stream-cancel-secret-must-not-reach-the-wire");
		AtomicReference<MicrohttpResponse> streamingResponse = new AtomicReference<>();
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();
		AtomicReference<WritableSource> sourceReference = new AtomicReference<>();
		AtomicReference<RecordingSocketChannel> socketReference = new AtomicReference<>();
		Thread writer = null;
		Thread canceler = null;
		McpHttpServerRuntime runtime = errorRaceRuntime(invocation -> {
			Assertions.assertTrue(invocation.sendNotification(
					progress("streamed-error-owner")));
			handlerSentProgress.countDown();
			releaseHandler.await();
			throw handlerFailure;
		}, observation, clock);

		try {
			InetSocketAddress address = runtime.start();
			MicrohttpRequest request = request(address, "streamed-error-owner");
			submit(runtime, address, request, response ->
					Assertions.assertTrue(
							streamingResponse.compareAndSet(null, response),
							"The SSE response callback must be offered once."));

			Assertions.assertTrue(handlerSentProgress.await(5, TimeUnit.SECONDS));
			MicrohttpResponse response = awaitValue(streamingResponse,
					"The first SSE response was not offered.");
			Assertions.assertTrue(response.streaming());
			WritableSource source = newBodySource(response);
			sourceReference.set(source);
			RecordingSocketChannel socket = new RecordingSocketChannel();
			socketReference.set(socket);
			source.writeReadyCallback(() -> {
				// The test drives every write directly.
			});
			source.start();
			Assertions.assertTrue(source.writeTo(socket, Long.MAX_VALUE) > 0L,
					"The progress event was not written.");
			Assertions.assertFalse(source.isReadyToWrite(),
					"Only progress should be ready before handler failure.");

			releaseHandler.countDown();
			awaitCondition(source::isReadyToWrite,
					"The mapped terminal error was not reserved.");

			writer = new Thread(() -> {
				try {
					clock.blockNextCallOnCurrentThread();
					source.writeTo(socket, Long.MAX_VALUE);
				} catch (Throwable throwable) {
					writerFailure.set(throwable);
				}
			}, "mcp-error-terminal-byte-writer");
			writer.start();
			Assertions.assertTrue(clock.writeTimestampEntered.await(5, TimeUnit.SECONDS),
					"The terminal error write did not reach its timestamp boundary.");

			CountDownLatch cancelInvoked = new CountDownLatch(1);
			canceler = new Thread(() -> {
				cancelInvoked.countDown();
				try {
					cancel(runtime, request,
							StreamTerminationReason.CLIENT_DISCONNECTED,
							cancellationCause);
				} catch (Throwable throwable) {
					cancellationFailure.set(throwable);
				}
			}, "mcp-error-terminal-byte-canceler");
			canceler.start();
			Assertions.assertTrue(cancelInvoked.await(5, TimeUnit.SECONDS));
			Thread cancelThread = canceler;
			awaitCondition(() -> cancelThread.getState() == Thread.State.BLOCKED,
					"Cancel did not contend after the terminal error byte was written.");

			clock.releaseWriteTimestamp.countDown();
			writer.join(TimeUnit.SECONDS.toMillis(5));
			canceler.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(writer.isAlive());
			Assertions.assertFalse(canceler.isAlive());
			Assertions.assertNull(writerFailure.get());
			Assertions.assertNull(cancellationFailure.get());
			observation.awaitFinished();
			awaitClean(runtime);

			String body = socket.writtenText();
			String terminal = "data: {\"jsonrpc\":\"2.0\","
					+ "\"id\":\"streamed-error-owner\","
					+ "\"error\":{\"code\":-32603,"
					+ "\"message\":\"Internal error\"}}\n\n";
			Assertions.assertEquals(1, occurrences(body, terminal), body);
			Assertions.assertTrue(body.contains(
					"\"progressToken\":\"streamed-error-owner\""), body);
			Assertions.assertTrue(body.endsWith("0\r\n\r\n"), body);
			assertRedacted(body, handlerFailure, cancellationCause);
			observation.assertExactlyOneInternalError(handlerFailure);
			observation.assertProtocolErrors(
					List.of(McpJsonRpcError.INTERNAL_ERROR));
			observation.assertExactlyOneCompletedStream();
			Assertions.assertEquals(0,
					runtime.diagnosticsSnapshot().activeRequestStreams());
		} finally {
			releaseHandler.countDown();
			clock.releaseWriteTimestamp.countDown();
			WritableSource source = sourceReference.get();
			if (source != null)
				source.close(StreamTerminationReason.SERVER_STOPPING, null);
			if (writer != null)
				writer.join(TimeUnit.SECONDS.toMillis(5));
			if (canceler != null)
				canceler.join(TimeUnit.SECONDS.toMillis(5));
			RecordingSocketChannel socket = socketReference.get();
			if (socket != null)
				socket.close();
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	void client_cancel_beats_unreserved_streamed_error_and_discards_its_metric()
			throws Exception {
		RecordingObservation observation = new RecordingObservation();
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerSentProgress = new CountDownLatch(1);
		CountDownLatch terminalAttemptEntered = new CountDownLatch(1);
		CountDownLatch releaseTerminalAttempt = new CountDownLatch(1);
		IllegalStateException handlerFailure = new IllegalStateException(
				"losing-handler-secret-must-not-reach-the-wire");
		IOException cancellationCause = new IOException(
				"winning-cancel-secret-must-not-reach-the-wire");
		AtomicReference<MicrohttpResponse> streamingResponse = new AtomicReference<>();
		AtomicReference<WritableSource> sourceReference = new AtomicReference<>();
		AtomicReference<RecordingSocketChannel> socketReference = new AtomicReference<>();
		McpHttpServerRuntime runtime = errorRaceRuntime(invocation -> {
			Assertions.assertTrue(invocation.sendNotification(
					progress("cancel-wins")));
			handlerSentProgress.countDown();
			releaseHandler.await();
			throw handlerFailure;
		}, observation, McpApplicationClock.SYSTEM);

		try {
			McpRequestSseStream.setTestHooks(() -> {
				terminalAttemptEntered.countDown();
				awaitLatchUninterruptibly(releaseTerminalAttempt);
			});
			InetSocketAddress address = runtime.start();
			MicrohttpRequest request = request(address, "cancel-wins");
			submit(runtime, address, request, response ->
					Assertions.assertTrue(
							streamingResponse.compareAndSet(null, response),
							"The SSE response callback must be offered once."));

			Assertions.assertTrue(handlerSentProgress.await(5, TimeUnit.SECONDS));
			MicrohttpResponse response = awaitValue(streamingResponse,
					"The first SSE response was not offered.");
			WritableSource source = newBodySource(response);
			sourceReference.set(source);
			RecordingSocketChannel socket = new RecordingSocketChannel();
			socketReference.set(socket);
			source.writeReadyCallback(() -> {
				// The test drives the accepted progress write directly.
			});
			source.start();
			Assertions.assertTrue(source.writeTo(socket, Long.MAX_VALUE) > 0L,
					"The accepted progress event was not written.");

			releaseHandler.countDown();
			Assertions.assertTrue(terminalAttemptEntered.await(5, TimeUnit.SECONDS),
					"The mapped error did not reach terminal reservation.");
			cancel(runtime, request, StreamTerminationReason.CLIENT_DISCONNECTED,
					cancellationCause);
			releaseTerminalAttempt.countDown();

			observation.awaitFinished();
			awaitClean(runtime);
			String body = socket.writtenText();
			Assertions.assertTrue(body.contains(
					"\"progressToken\":\"cancel-wins\""), body);
			Assertions.assertFalse(body.contains("\"code\":-32603"), body);
			assertRedacted(body, handlerFailure, cancellationCause);
			observation.assertExactlyOneClientDisconnect(cancellationCause);
			observation.assertProtocolErrors(List.of());
			observation.assertExactlyOneClientDisconnectedStream();
			Assertions.assertEquals(0,
					runtime.diagnosticsSnapshot().activeRequestStreams());
		} finally {
			releaseHandler.countDown();
			releaseTerminalAttempt.countDown();
			McpRequestSseStream.setTestHooks(null);
			WritableSource source = sourceReference.get();
			if (source != null)
				source.close(StreamTerminationReason.SERVER_STOPPING, null);
			RecordingSocketChannel socket = socketReference.get();
			if (socket != null)
				socket.close();
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	void application_stop_after_admission_cannot_strand_observation()
			throws Exception {
		RecordingObservation observation = new RecordingObservation();
		CountDownLatch limiterEntered = new CountDownLatch(1);
		CountDownLatch releaseLimiter = new CountDownLatch(1);
		AtomicInteger callbacks = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = acceptingPolicy().withRequestRateLimiter(ignored -> {
			limiterEntered.countDown();
			Assertions.assertTrue(releaseLimiter.await(10,
					TimeUnit.SECONDS),
					"Timed out waiting to release the observation limiter");
			return McpRateLimitDecision.allowed();
		});
		McpHttpServerRuntime runtime = runtime(policy, invocation -> {
			handlerInvocations.incrementAndGet();
			return completeResult("must-not-run");
		}, observation, McpApplicationClock.SYSTEM);

		try {
			InetSocketAddress address = runtime.start();
			submit(runtime, address, request(address, "stop-after-admission"),
					ignored -> callbacks.incrementAndGet());
			Assertions.assertTrue(limiterEntered.await(5, TimeUnit.SECONDS),
					"The admitted request did not reach its rate limiter.");

			application(runtime).stop();
			releaseLimiter.countDown();
			observation.awaitFinished();
			awaitClean(runtime);

			observation.assertExactlyOne(McpRequestOutcome.CANCELED);
			Assertions.assertEquals(0, callbacks.get());
			Assertions.assertEquals(0, handlerInvocations.get());
		} finally {
			releaseLimiter.countDown();
			runtime.close();
		}
	}

	@Test
	void first_sse_response_handoff_failure_is_write_failed_with_cause()
			throws Exception {
		RecordingObservation observation = new RecordingObservation();
		IllegalStateException handoffFailure =
				new IllegalStateException("simulated first SSE response handoff failure");
		AtomicInteger callbacks = new AtomicInteger();
		AtomicBoolean notificationAccepted = new AtomicBoolean(true);
		McpHttpServerRuntime runtime = runtime(acceptingPolicy(), invocation -> {
			notificationAccepted.set(invocation.sendNotification(
					progress("handoff-failure")));
			return completeResult("must-not-be-delivered");
		}, observation, McpApplicationClock.SYSTEM);

		try {
			InetSocketAddress address = runtime.start();
			submit(runtime, address, request(address, "handoff-failure"), response -> {
				callbacks.incrementAndGet();
				throw handoffFailure;
			});

			observation.awaitFinished();
			awaitClean(runtime);
			observation.assertExactlyOne(McpRequestOutcome.WRITE_FAILED);
			Assertions.assertEquals(1, callbacks.get());
			Assertions.assertFalse(notificationAccepted.get());
			Assertions.assertNull(observation.error.get());
			Assertions.assertEquals(1, observation.throwables.get().size());
			Assertions.assertSame(handoffFailure, observation.throwables.get().get(0));
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	void application_encoding_fallback_reports_actual_internal_error()
			throws Exception {
		RecordingObservation observation = new RecordingObservation();
		AtomicReference<MicrohttpResponse> renderedResponse = new AtomicReference<>();
		McpHttpServerRuntime runtime = runtime(acceptingPolicy(),
				invocation -> completeResult("x".repeat(2_048)), observation,
				McpApplicationClock.SYSTEM, limitsWithOutputBytes(1_024));

		try {
			InetSocketAddress address = runtime.start();
			submit(runtime, address, request(address, "encoding-fallback"),
					response -> Assertions.assertTrue(
							renderedResponse.compareAndSet(null, response),
							"The fallback response callback must be offered once."));

			MicrohttpResponse response = awaitValue(renderedResponse,
					"The encoding fallback response was not offered.");
			Assertions.assertEquals(500, response.status());
			String body = new String(response.body(), StandardCharsets.UTF_8);
			Assertions.assertTrue(body.contains("\"code\":-32603"), body);
			Assertions.assertTrue(body.contains("\"message\":\"Internal error\""), body);
			Assertions.assertFalse(body.contains("x".repeat(2_048)), body);

			completeBody(response);
			observation.awaitFinished();
			awaitClean(runtime);

			observation.assertExactlyOne(McpRequestOutcome.INTERNAL_ERROR);
			Assertions.assertNotNull(observation.error.get());
			Assertions.assertEquals(McpJsonRpcError.INTERNAL_ERROR,
					observation.error.get().code());
			Assertions.assertEquals("Internal error", observation.error.get().message());
			Assertions.assertEquals(1, observation.throwables.get().size());
			Assertions.assertInstanceOf(IllegalArgumentException.class,
					observation.throwables.get().get(0));
		} finally {
			runtime.close();
		}
	}

	private static McpHttpEndpointPolicy acceptingPolicy() {
		return McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
				ignored -> McpRequestAdmissionDecision.ACCEPT);
	}

	private static McpHttpServerRuntime errorRaceRuntime(
			McpApplicationRequestHandler handler,
			RecordingObservation observation, McpApplicationClock clock) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"error-terminal-race-test", "4.0.0-SNAPSHOT"))
				.build();
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
				acceptingPolicy(), endpoint, McpApplicationRequestRouter.fromHandlers(
						Map.of(APPLICATION_METHOD, handler)), observation);
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), List.of(binding),
				McpJsonLimits.productionDefaults(),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(20), Duration.ofDays(1)),
				clock, McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {}, Optional.empty(),
				McpFrameworkRequestStateRuntime.disabledInstance(),
				McpSubscriptionRuntimeConfiguration.productionDefaults(), observation);
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			McpApplicationRequestHandler handler, RecordingObservation observation,
			McpApplicationClock clock) {
		return runtime(policy, handler, observation, clock,
				McpJsonLimits.productionDefaults());
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			McpApplicationRequestHandler handler, RecordingObservation observation,
			McpApplicationClock clock, McpJsonLimits jsonLimits) {
		return runtime(policy, handler, observation, clock, jsonLimits,
				McpApplicationHandlerExecutorFactory.production());
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			McpApplicationRequestHandler handler, RecordingObservation observation,
			McpApplicationClock clock, McpJsonLimits jsonLimits,
			McpApplicationHandlerExecutorFactory executorFactory) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"observation-terminal-race-test", "4.0.0-SNAPSHOT"))
				.build();
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy, endpoint,
				McpApplicationRequestRouter.fromHandlers(
						Map.of(APPLICATION_METHOD, handler)), observation);
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), List.of(binding),
				jsonLimits,
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(20), Duration.ofDays(1)),
				clock, executorFactory,
				ignored -> {}, ignored -> {});
	}

	private static McpJsonLimits limitsWithOutputBytes(int maximumOutputBytes) {
		McpJsonLimits production = McpJsonLimits.productionDefaults();
		return new McpJsonLimits(production.maximumInputBytes(),
				production.maximumNestingDepth(),
				production.maximumTokenLengthInCharacters(),
				production.maximumStringLengthInCharacters(),
				production.maximumNumberLengthInCharacters(),
				production.maximumExponentMagnitude(), production.maximumNodeCount(),
				maximumOutputBytes);
	}

	private static McpWireResult completeResult(String value) {
		return McpWireResult.complete(new McpJsonObject(
				Map.of("value", new McpJsonString(value))));
	}

	private static McpJsonRpcMessage.Notification progress(String token) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("progressToken", new McpJsonString(token));
		fields.put("progress", new McpJsonNumber(BigDecimal.ONE));
		return new McpJsonRpcMessage.Notification("notifications/progress",
				Optional.of(new McpJsonObject(fields)), McpJsonObject.empty());
	}

	private static MicrohttpRequest request(InetSocketAddress address, String id) {
		byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + APPLICATION_METHOD
				+ "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}")
				.getBytes(StandardCharsets.UTF_8);
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(
				new Header("Host", LOOPBACK + ':' + address.getPort()),
				new Header("Content-Type", "application/json; charset=UTF-8"),
				new Header("Accept", "application/json, text/event-stream"),
				new Header("MCP-Protocol-Version", PROTOCOL_VERSION),
				new Header("Mcp-Method", APPLICATION_METHOD)), body, false,
				new InetSocketAddress(LOOPBACK, 12_345));
	}

	private static void submit(McpHttpServerRuntime runtime,
			InetSocketAddress address, MicrohttpRequest request,
			Consumer<MicrohttpResponse> callback) throws Exception {
		submit(runtime, address, request, null, callback);
	}

	private static void submit(McpHttpServerRuntime runtime,
			InetSocketAddress address, MicrohttpRequest request,
			@Nullable Runnable lifecycleAdmission,
			Consumer<MicrohttpResponse> callback) throws Exception {
		Method submitRequest = McpHttpServerRuntime.class.getDeclaredMethod(
				"submitRequest", ThreadPoolExecutor.class, McpApplicationExecution.class,
				InetSocketAddress.class, MicrohttpRequest.class,
				com.soklet.Request.class, McpSimulationRuntime.class, Runnable.class,
				Consumer.class);
		submitRequest.setAccessible(true);
		invoke(submitRequest, runtime, processor(runtime), application(runtime),
				address, request, null, null, lifecycleAdmission, callback);
	}

	private static void cancel(McpHttpServerRuntime runtime,
			MicrohttpRequest request, StreamTerminationReason reason,
			@Nullable Throwable cause) throws Exception {
		Method cancelRequest = McpHttpServerRuntime.class.getDeclaredMethod(
				"cancelRequest", MicrohttpRequest.class,
				StreamTerminationReason.class, Throwable.class);
		cancelRequest.setAccessible(true);
		invoke(cancelRequest, runtime, request, reason, cause);
	}

	private static WritableSource newBodySource(MicrohttpResponse response)
			throws Exception {
		Method newBodySource = MicrohttpResponse.class.getDeclaredMethod("newBodySource");
		newBodySource.setAccessible(true);
		return (WritableSource) invoke(newBodySource, response);
	}

	private static void completeBody(MicrohttpResponse response) throws Exception {
		Method reserveBodyTermination = MicrohttpResponse.class.getDeclaredMethod(
				"reserveBodyTermination", StreamTerminationReason.class, Throwable.class);
		reserveBodyTermination.setAccessible(true);
		invoke(reserveBodyTermination, response, StreamTerminationReason.COMPLETED, null);

		Method deliverBodyTermination = MicrohttpResponse.class.getDeclaredMethod(
				"deliverBodyTermination");
		deliverBodyTermination.setAccessible(true);
		invoke(deliverBodyTermination, response);
	}

	private static ThreadPoolExecutor processor(McpHttpServerRuntime runtime)
			throws Exception {
		Field field = McpHttpServerRuntime.class.getDeclaredField("requestProcessor");
		field.setAccessible(true);
		return (ThreadPoolExecutor) field.get(runtime);
	}

	private static McpApplicationExecution application(McpHttpServerRuntime runtime)
			throws Exception {
		Field field = McpHttpServerRuntime.class.getDeclaredField("applicationExecution");
		field.setAccessible(true);
		return (McpApplicationExecution) field.get(runtime);
	}

	private static Object invoke(Method method, Object target, Object... arguments)
			throws Exception {
		try {
			return method.invoke(target, arguments);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof Exception checked)
				throw checked;
			if (cause instanceof Error error)
				throw error;
			throw new AssertionError(cause);
		}
	}

	private static <T> T awaitValue(AtomicReference<T> reference, String failure)
			throws InterruptedException {
		awaitCondition(() -> reference.get() != null, failure);
		return reference.get();
	}

	private static void awaitClean(McpHttpServerRuntime runtime)
			throws InterruptedException {
		awaitCondition(() -> {
			McpRequestExecutionSnapshot requests = runtime.requestExecutionSnapshot();
			McpApplicationExecutionSnapshot application =
					runtime.applicationExecutionSnapshot().orElseThrow();
			return requests.retainedRequestControls() == 0
					&& requests.activeIdentifiedRequestExchanges() == 0
					&& application.activeHandlerSlots() == 0
					&& application.queuedRequests() == 0
					&& application.retainedExchanges() == 0
					&& application.retainedTransportLeases() == 0;
		}, "The request did not release all terminal state.");
	}

	private static void awaitCondition(BooleanSupplier condition, String failure)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L)
			Thread.sleep(5L);
		Assertions.assertTrue(condition.getAsBoolean(), failure);
	}

	private static void assertRedacted(String wire, Throwable... secrets) {
		for (Throwable secret : secrets)
			Assertions.assertFalse(wire.contains(secret.getMessage()), wire);
	}

	private static int occurrences(String value, String target) {
		int count = 0;
		int offset = 0;
		while ((offset = value.indexOf(target, offset)) >= 0) {
			count++;
			offset += target.length();
		}
		return count;
	}

	private static void awaitLatchUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try {
				latch.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private record PendingProtocolError(int code)
			implements McpApplicationExecutionObserver.PendingMetricRecord {
	}

	private static final class RecordingObservation
			implements McpRuntimeObservationSink, McpRuntimeRequestObservation,
			McpApplicationExecutionObserver {
		private final Object metricLock;
		private final List<PendingProtocolError> pendingProtocolErrors;
		private final List<Integer> deliveredProtocolErrors;
		private final AtomicInteger starts;
		private final AtomicInteger finishes;
		private final AtomicInteger streamOpens;
		private final AtomicInteger streamCloses;
		private final AtomicReference<StreamTerminationReason> streamCloseReason;
		private final AtomicReference<McpRequestOutcome> outcome;
		private final AtomicReference<McpJsonRpcError> error;
		private final AtomicReference<List<Throwable>> throwables;
		private final CountDownLatch finished;
		private int metricDeferralDepth;

		private RecordingObservation() {
			this.metricLock = new Object();
			this.pendingProtocolErrors = new java.util.ArrayList<>();
			this.deliveredProtocolErrors = new java.util.ArrayList<>();
			this.starts = new AtomicInteger();
			this.finishes = new AtomicInteger();
			this.streamOpens = new AtomicInteger();
			this.streamCloses = new AtomicInteger();
			this.streamCloseReason = new AtomicReference<>();
			this.outcome = new AtomicReference<>();
			this.error = new AtomicReference<>();
			this.throwables = new AtomicReference<>(List.of());
			this.finished = new CountDownLatch(1);
		}

		@Override
		public McpRuntimeRequestObservation didStartRequest(
				McpRuntimeRequestInput input) {
			starts.incrementAndGet();
			return this;
		}

		@Override
		public Optional<McpRequestContext> publicContext() {
			return Optional.empty();
		}

		@Override
		public void didOpenRequestStream() {
			this.streamOpens.incrementAndGet();
		}

		@Override
		public void didCloseRequestStream(StreamTerminationReason reason,
				Duration duration) {
			this.streamCloseReason.compareAndSet(null, reason);
			this.streamCloses.incrementAndGet();
		}

		@Override
		public void didFinish(McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error, Duration duration,
				List<Throwable> throwables) {
			this.outcome.compareAndSet(null, outcome);
			this.error.compareAndSet(null, error);
			this.throwables.set(List.copyOf(throwables));
			finishes.incrementAndGet();
			finished.countDown();
		}

		@Override
		public void beginDeferral() {
			synchronized (metricLock) {
				metricDeferralDepth++;
			}
		}

		@Override
		public PendingMetricRecord recordProtocolError(int code,
				@Nullable McpRequestContext requestContext) {
			PendingProtocolError pending = new PendingProtocolError(code);
			synchronized (metricLock) {
				pendingProtocolErrors.add(pending);
			}
			return pending;
		}

		@Override
		public void discardPendingMetric(PendingMetricRecord pendingMetricRecord) {
			if (!(pendingMetricRecord instanceof PendingProtocolError pending))
				return;
			synchronized (metricLock) {
				if (!pendingProtocolErrors.remove(pending))
					throw new IllegalStateException(
							"The provisional protocol error is no longer pending.");
			}
		}

		@Override
		public void recordHandlerExecutionStarted() {
		}

		@Override
		public void recordHandlerExecutionFinished() {
		}

		@Override
		public void recordHandlerQueued() {
		}

		@Override
		public void recordHandlerDequeued() {
		}

		@Override
		public void recordHandlerCapacityRejected() {
		}

		@Override
		public void drain() {
			synchronized (metricLock) {
				if (metricDeferralDepth != 0 || pendingProtocolErrors.isEmpty())
					return;
				for (PendingProtocolError pending : pendingProtocolErrors)
					deliveredProtocolErrors.add(pending.code());
				pendingProtocolErrors.clear();
			}
		}

		@Override
		public void endDeferral() {
			boolean shouldDrain;
			synchronized (metricLock) {
				if (metricDeferralDepth == 0)
					throw new IllegalStateException(
							"Metric deferral is not active.");
				metricDeferralDepth--;
				shouldDrain = metricDeferralDepth == 0;
			}
			if (shouldDrain)
				drain();
		}

		private void awaitFinished() throws InterruptedException {
			Assertions.assertTrue(finished.await(5, TimeUnit.SECONDS),
					"The admitted request observation was stranded.");
		}

		private void assertExactlyOne(McpRequestOutcome expectedOutcome) {
			Assertions.assertEquals(1, starts.get());
			Assertions.assertEquals(1, finishes.get());
			Assertions.assertEquals(expectedOutcome, outcome.get());
		}

		private void assertExactlyOneInternalError(Throwable expectedFailure) {
			assertExactlyOne(McpRequestOutcome.INTERNAL_ERROR);
			McpJsonRpcError observedError = error.get();
			Assertions.assertNotNull(observedError);
			Assertions.assertEquals(McpJsonRpcError.INTERNAL_ERROR,
					observedError.code());
			Assertions.assertEquals("Internal error", observedError.message());
			Assertions.assertEquals(List.of(expectedFailure), throwables.get());
		}

		private void assertExactlyOneClientDisconnect(Throwable expectedCause) {
			assertExactlyOne(McpRequestOutcome.CLIENT_DISCONNECTED);
			Assertions.assertNull(error.get());
			Assertions.assertEquals(List.of(expectedCause), throwables.get());
		}

		private void assertProtocolErrors(List<Integer> expectedCodes) {
			synchronized (metricLock) {
				Assertions.assertEquals(expectedCodes, deliveredProtocolErrors);
				Assertions.assertEquals(List.of(), pendingProtocolErrors);
			}
		}

		private void assertNoStream() {
			Assertions.assertEquals(0, streamOpens.get());
			Assertions.assertEquals(0, streamCloses.get());
			Assertions.assertNull(streamCloseReason.get());
		}

		private void assertExactlyOneCompletedStream() {
			Assertions.assertEquals(1, this.streamOpens.get());
			Assertions.assertEquals(1, this.streamCloses.get());
			Assertions.assertEquals(StreamTerminationReason.COMPLETED,
					this.streamCloseReason.get());
		}

		private void assertExactlyOneClientDisconnectedStream() {
			Assertions.assertEquals(1, streamOpens.get());
			Assertions.assertEquals(1, streamCloses.get());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
					streamCloseReason.get());
		}
	}

	private static final class InlineExecutorService
			extends AbstractExecutorService {
		private final AtomicBoolean shutdown = new AtomicBoolean();

		@Override
		public void shutdown() {
			this.shutdown.set(true);
		}

		@Override
		public List<Runnable> shutdownNow() {
			this.shutdown.set(true);
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return this.shutdown.get();
		}

		@Override
		public boolean isTerminated() {
			return this.shutdown.get();
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return this.shutdown.get();
		}

		@Override
		public void execute(Runnable command) {
			if (this.shutdown.get())
				throw new RejectedExecutionException("Executor is shut down.");
			command.run();
		}
	}

	private static final class TerminalWriteBlockingClock
			implements McpApplicationClock {
		private final ThreadLocal<Boolean> blockNextCall;
		private final CountDownLatch writeTimestampEntered;
		private final CountDownLatch releaseWriteTimestamp;

		private TerminalWriteBlockingClock() {
			this.blockNextCall = ThreadLocal.withInitial(() -> false);
			this.writeTimestampEntered = new CountDownLatch(1);
			this.releaseWriteTimestamp = new CountDownLatch(1);
		}

		private void blockNextCallOnCurrentThread() {
			blockNextCall.set(true);
		}

		@Override
		public long nanoTime() {
			if (blockNextCall.get()) {
				blockNextCall.remove();
				writeTimestampEntered.countDown();
				boolean interrupted = false;
				while (true) {
					try {
						releaseWriteTimestamp.await();
						break;
					} catch (InterruptedException exception) {
						interrupted = true;
					}
				}
				if (interrupted)
					Thread.currentThread().interrupt();
			}
			return System.nanoTime();
		}
	}

	private static final class RecordingSocketChannel extends SocketChannel {
		private final ByteArrayOutputStream output;

		private RecordingSocketChannel() {
			super(SelectorProvider.provider());
			this.output = new ByteArrayOutputStream();
		}

		private String writtenText() {
			return output.toString(StandardCharsets.UTF_8);
		}

		@Override
		public int write(ByteBuffer source) {
			int byteCount = source.remaining();
			byte[] bytes = new byte[byteCount];
			source.get(bytes);
			output.writeBytes(bytes);
			return byteCount;
		}

		@Override
		public long write(ByteBuffer[] sources, int offset, int length) {
			long written = 0L;
			for (int index = offset; index < offset + length; index++)
				written += write(sources[index]);
			return written;
		}

		@Override
		public int read(ByteBuffer destination) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long read(ByteBuffer[] destinations, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SocketChannel bind(SocketAddress localAddress) {
			return this;
		}

		@Override
		public <T> SocketChannel setOption(SocketOption<T> option, T value) {
			return this;
		}

		@Override
		public <T> T getOption(SocketOption<T> option) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Set<SocketOption<?>> supportedOptions() {
			return Set.of();
		}

		@Override
		public SocketChannel shutdownInput() {
			return this;
		}

		@Override
		public SocketChannel shutdownOutput() {
			return this;
		}

		@Override
		public Socket socket() {
			return new Socket();
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public boolean isConnectionPending() {
			return false;
		}

		@Override
		public boolean connect(SocketAddress remoteAddress) {
			return true;
		}

		@Override
		public boolean finishConnect() {
			return true;
		}

		@Override
		public @Nullable SocketAddress getRemoteAddress() {
			return null;
		}

		@Override
		public @Nullable SocketAddress getLocalAddress() {
			return null;
		}

		@Override
		protected void implCloseSelectableChannel() {
			// No-op
		}

		@Override
		protected void implConfigureBlocking(boolean blocking) {
			// No-op
		}
	}
}
