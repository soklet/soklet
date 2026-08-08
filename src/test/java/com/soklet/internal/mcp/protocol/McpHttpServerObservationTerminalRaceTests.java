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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@NotThreadSafe
@Timeout(30)
class McpHttpServerObservationTerminalRaceTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String APPLICATION_METHOD = "test/execute";

	@Test
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
	void application_stop_after_admission_cannot_strand_observation()
			throws Exception {
		RecordingObservation observation = new RecordingObservation();
		CountDownLatch limiterEntered = new CountDownLatch(1);
		CountDownLatch releaseLimiter = new CountDownLatch(1);
		AtomicInteger callbacks = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = acceptingPolicy().withRequestRateLimiter(ignored -> {
			limiterEntered.countDown();
			releaseLimiter.await();
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

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			McpApplicationRequestHandler handler, RecordingObservation observation,
			McpApplicationClock clock) {
		return runtime(policy, handler, observation, clock,
				McpJsonLimits.productionDefaults());
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			McpApplicationRequestHandler handler, RecordingObservation observation,
			McpApplicationClock clock, McpJsonLimits jsonLimits) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"observation-terminal-race-test", "3.6.0-SNAPSHOT"))
				.build();
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy, endpoint,
				McpApplicationRequestRouter.fromHandlers(
						Map.of(APPLICATION_METHOD, handler)), observation);
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), List.of(binding),
				jsonLimits,
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(20), Duration.ofDays(1)),
				clock, McpApplicationHandlerExecutorFactory.production(),
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
		Method submitRequest = McpHttpServerRuntime.class.getDeclaredMethod(
				"submitRequest", ThreadPoolExecutor.class, McpApplicationExecution.class,
				InetSocketAddress.class, MicrohttpRequest.class, Consumer.class);
		submitRequest.setAccessible(true);
		invoke(submitRequest, runtime, processor(runtime), application(runtime),
				address, request, callback);
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

	private static final class RecordingObservation
			implements McpRuntimeObservationSink, McpRuntimeRequestObservation {
		private final AtomicInteger starts;
		private final AtomicInteger finishes;
		private final AtomicReference<McpRequestOutcome> outcome;
		private final AtomicReference<McpJsonRpcError> error;
		private final AtomicReference<List<Throwable>> throwables;
		private final CountDownLatch finished;

		private RecordingObservation() {
			this.starts = new AtomicInteger();
			this.finishes = new AtomicInteger();
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
		public void didFinish(McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error, Duration duration,
				List<Throwable> throwables) {
			this.outcome.compareAndSet(null, outcome);
			this.error.compareAndSet(null, error);
			this.throwables.set(List.copyOf(throwables));
			finishes.incrementAndGet();
			finished.countDown();
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
