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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@NotThreadSafe
@Timeout(30)
public class McpQueuedExecutionWinnerElectionTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String APPLICATION_METHOD = "test/execute";
	private static final long QUEUED_DEADLINE_NANOS = 100L;
	private static final long ACTIVE_DEADLINE_NANOS = 1_000L;

	private enum RaceEvent {
		PROMOTION,
		DEADLINE,
		CLIENT_DISCONNECT
	}

	private static final List<List<RaceEvent>> ALL_LINEARIZATIONS = List.of(
			List.of(RaceEvent.PROMOTION, RaceEvent.DEADLINE,
					RaceEvent.CLIENT_DISCONNECT),
			List.of(RaceEvent.PROMOTION, RaceEvent.CLIENT_DISCONNECT,
					RaceEvent.DEADLINE),
			List.of(RaceEvent.DEADLINE, RaceEvent.PROMOTION,
					RaceEvent.CLIENT_DISCONNECT),
			List.of(RaceEvent.DEADLINE, RaceEvent.CLIENT_DISCONNECT,
					RaceEvent.PROMOTION),
			List.of(RaceEvent.CLIENT_DISCONNECT, RaceEvent.PROMOTION,
					RaceEvent.DEADLINE),
			List.of(RaceEvent.CLIENT_DISCONNECT, RaceEvent.DEADLINE,
					RaceEvent.PROMOTION));

	@Test
	public void all_queue_promotion_deadline_disconnect_linearizations_elect_exactly_one_outcome()
			throws Exception {
		for (List<RaceEvent> order : ALL_LINEARIZATIONS)
			assertLinearization(order);
	}

	@Test
	public void reserved_queued_deadline_yields_to_outer_disconnect_before_response_write()
			throws Exception {
		MonotonicManualClock clock = new MonotonicManualClock();
		ManualExecutorService executor = new ManualExecutorService();
		BlockingDeadlineObservation observation = new BlockingDeadlineObservation();
		AtomicInteger activeInvocations = new AtomicInteger();
		AtomicInteger activeWrites = new AtomicInteger();
		AtomicInteger activeCleanups = new AtomicInteger();
		AtomicInteger queuedInterceptors = new AtomicInteger();
		AtomicInteger queuedInvocations = new AtomicInteger();
		AtomicInteger queuedCallbacks = new AtomicInteger();
		AtomicReference<Throwable> timerFailure = new AtomicReference<>();
		Thread timer = null;
		McpHttpServerRuntime runtime = runtime(clock, executor, observation,
				queuedInterceptors,
				invocation -> {
					queuedInvocations.incrementAndGet();
					return McpWireResult.complete(McpJsonObject.empty());
				});

		try {
			clock.blockNextBackgroundCycle();
			InetSocketAddress address = runtime.start();
			clock.awaitBackgroundCycle();
			McpApplicationExecution application = application(runtime);
			application.dispatch(transportRequest(), request("active-owner"),
					admissionIdentity(), invocation -> {
						activeInvocations.incrementAndGet();
						return McpWireResult.complete(McpJsonObject.empty());
					}, ACTIVE_DEADLINE_NANOS, response -> {
						activeWrites.incrementAndGet();
						return true;
					}, activeCleanups::incrementAndGet);
			Assertions.assertEquals(1, executor.submissionCount());

			MicrohttpRequest queuedRequest = request(address, "queued-owner");
			submit(runtime, address, queuedRequest,
					response -> queuedCallbacks.incrementAndGet());
			observation.awaitQueued();
			assertApplicationState(runtime, 1, 1, 2, 2);

			clock.advanceAndRun(QUEUED_DEADLINE_NANOS - 1,
					runtime::runApplicationTimerCycle);
			assertApplicationState(runtime, 1, 1, 2, 2);
			Assertions.assertEquals(0, queuedCallbacks.get());
			Assertions.assertEquals(0, observation.finishCount());

			observation.blockNextDequeuedDeferral();
			timer = new Thread(() -> {
				try {
					clock.advanceAndRun(QUEUED_DEADLINE_NANOS,
							runtime::runApplicationTimerCycle);
				} catch (Throwable throwable) {
					timerFailure.set(throwable);
				}
			}, "mcp-queued-deadline-owner");
			timer.start();
			observation.awaitDeadlineReservation();

			IOException disconnect = new IOException("client disconnected");
			cancel(runtime, queuedRequest,
					StreamTerminationReason.CLIENT_DISCONNECTED, disconnect);
			observation.awaitFinish();
			Assertions.assertTrue(timer.isAlive(),
					"The deadline handoff must remain held until explicitly released.");
			Assertions.assertEquals(0, queuedCallbacks.get(),
					"Disconnect must not hand a queued deadline response to transport.");
			observation.assertExactlyOneClientDisconnect(disconnect);

			observation.releaseDeadlineReservation();
			timer.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(timer.isAlive());
			Assertions.assertNull(timerFailure.get());
			observation.assertExactlyOneClientDisconnect(disconnect);

			McpApplicationExecutionSnapshot canceled =
					runtime.applicationExecutionSnapshot().orElseThrow();
			Assertions.assertEquals(1, canceled.activeHandlerSlots());
			Assertions.assertEquals(0, canceled.queuedRequests());
			Assertions.assertEquals(1, canceled.retainedExchanges());
			Assertions.assertEquals(1, canceled.retainedTransportLeases());
			Assertions.assertEquals(1, canceled.deadlineExpirations());
			Assertions.assertEquals(0, canceled.protocolDeadlineExpirations());
			Assertions.assertEquals(0, canceled.capacityRejections());
			Assertions.assertEquals(0, canceled.terminalResponses());
			Assertions.assertEquals(1, canceled.abandonedResponses());
			Assertions.assertEquals(1, canceled.responseCleanups(),
					"Only the queued RequestControl cleanup has run so far.");
			Assertions.assertEquals(0, queuedInterceptors.get());
			Assertions.assertEquals(0, queuedInvocations.get());
			Assertions.assertEquals(0, queuedCallbacks.get());
			Assertions.assertEquals(1, observation.queuedCount());
			Assertions.assertEquals(1, observation.dequeuedCount(),
					"Deadline and disconnect must share one queue-gauge removal.");
			Assertions.assertEquals(0, observation.capacityRejectedCount());
			Assertions.assertEquals(1, executor.submissionCount(),
					"The removed queued request must not be promoted.");

			Assertions.assertTrue(executor.runNext(),
					"The synthetic active owner was not submitted.");
			Assertions.assertFalse(executor.runNext(),
					"The terminal queued request was dispatched late.");
			Assertions.assertEquals(1, activeInvocations.get());
			Assertions.assertEquals(1, activeWrites.get());
			Assertions.assertEquals(1, activeCleanups.get());
			Assertions.assertEquals(0, queuedInterceptors.get());
			Assertions.assertEquals(0, queuedInvocations.get());
			Assertions.assertEquals(0, queuedCallbacks.get());
			assertApplicationState(runtime, 0, 0, 0, 0);
			McpApplicationExecutionSnapshot finished =
					runtime.applicationExecutionSnapshot().orElseThrow();
			Assertions.assertEquals(2, finished.responseCleanups());
			Assertions.assertEquals(1, finished.terminalResponses());
			Assertions.assertEquals(1, finished.abandonedResponses());
			McpRequestExecutionSnapshot requests = runtime.requestExecutionSnapshot();
			Assertions.assertEquals(0, requests.retainedRequestControls());
			Assertions.assertEquals(0, requests.queuedProtocolRequests());
			Assertions.assertEquals(0, requests.activeIdentifiedRequestExchanges());
			observation.assertExactlyOneClientDisconnect(disconnect);
		} finally {
			observation.releaseDeadlineReservation();
			clock.releaseBackgroundCycle();
			if (timer != null)
				timer.join(TimeUnit.SECONDS.toMillis(5));
			runtime.close();
			executor.runAll();
		}
	}

	private static void assertLinearization(List<RaceEvent> order)
			throws Exception {
		LinearizationFixture fixture = new LinearizationFixture(order);
		try {
			fixture.run();
			fixture.assertOutcome();
		} finally {
			fixture.close();
		}
	}

	private static void assertApplicationState(McpHttpServerRuntime runtime,
			int activeSlots, int queuedRequests, int retainedExchanges,
			int retainedTransportLeases) {
		McpApplicationExecutionSnapshot snapshot =
				runtime.applicationExecutionSnapshot().orElseThrow();
		Assertions.assertEquals(activeSlots, snapshot.activeHandlerSlots());
		Assertions.assertEquals(queuedRequests, snapshot.queuedRequests());
		Assertions.assertEquals(retainedExchanges, snapshot.retainedExchanges());
		Assertions.assertEquals(retainedTransportLeases,
				snapshot.retainedTransportLeases());
	}

	private static McpHttpServerRuntime runtime(MonotonicManualClock clock,
			ManualExecutorService executor, BlockingDeadlineObservation observation,
			AtomicInteger interceptorInvocations,
			McpApplicationRequestHandler handler) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"queued-winner-election-test", "3.6.0-SNAPSHOT"))
				.build();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpRequestAdmissionDecision.ACCEPT)
				.withRequestInterceptor((invocation, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.invoke();
				});
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy,
				endpoint, McpApplicationRequestRouter.fromHandlers(
						Map.of(APPLICATION_METHOD, handler)), observation);
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), List.of(binding),
				McpJsonLimits.productionDefaults(),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofNanos(QUEUED_DEADLINE_NANOS),
						Duration.ofDays(1)),
				clock, ignored -> executor,
				ignored -> {}, ignored -> {}, Optional.empty(),
				McpFrameworkRequestStateRuntime.disabledInstance(),
				McpSubscriptionRuntimeConfiguration.productionDefaults(), observation);
	}

	private static MicrohttpRequest transportRequest() {
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(),
				new byte[0], false, new InetSocketAddress(LOOPBACK, 12_345));
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

	private static McpJsonRpcMessage.Request request(String id) {
		String json = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + APPLICATION_METHOD
				+ "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		McpJsonLimits limits = McpJsonLimits.productionDefaults();
		McpJsonRpcEnvelope envelope = new McpJsonRpcEnvelopeCodec(
				new McpJsonCodec(limits)).decode(
				json.getBytes(StandardCharsets.UTF_8));
		return new McpRequestWireMapper(limits).map(
				(McpJsonRpcEnvelope.Request) envelope);
	}

	private static McpEffectiveAdmissionIdentity admissionIdentity() {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"queued-winner-election-test", "3.6.0-SNAPSHOT"))
				.build();
		return McpEffectiveAdmissionIdentity.resolve(endpoint, "/mcp",
				McpAdmissionIdentity.anonymousInstance());
	}

	private static void submit(McpHttpServerRuntime runtime,
			InetSocketAddress address, MicrohttpRequest request,
			Consumer<MicrohttpResponse> callback) throws Exception {
		Method submitRequest = McpHttpServerRuntime.class.getDeclaredMethod(
				"submitRequest", ThreadPoolExecutor.class,
				McpApplicationExecution.class, InetSocketAddress.class,
				MicrohttpRequest.class, Consumer.class);
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

	private static ThreadPoolExecutor processor(McpHttpServerRuntime runtime)
			throws Exception {
		Field field = McpHttpServerRuntime.class.getDeclaredField("requestProcessor");
		field.setAccessible(true);
		return (ThreadPoolExecutor) field.get(runtime);
	}

	private static McpApplicationExecution application(McpHttpServerRuntime runtime)
			throws Exception {
		Field field = McpHttpServerRuntime.class.getDeclaredField(
				"applicationExecution");
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

	private static void await(CountDownLatch latch, String failure)
			throws InterruptedException {
		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), failure);
	}

	private static final class LinearizationFixture implements AutoCloseable {
		private final List<RaceEvent> order;
		private final MonotonicManualClock clock;
		private final ManualExecutorService executor;
		private final CountingExecutionObserver observer;
		private final McpApplicationExecution execution;
		private final MicrohttpRequest queuedTransportRequest;
		private final McpJsonRpcMessage.Request queuedRequest;
		private final AtomicInteger activeInvocations;
		private final AtomicInteger queuedInvocations;
		private final AtomicInteger activeWrites;
		private final AtomicInteger queuedWrites;
		private final AtomicInteger activeCleanups;
		private final AtomicInteger queuedCleanups;
		private final AtomicReference<McpApplicationResponse> activeResponse;
		private final AtomicReference<McpApplicationResponse> queuedResponse;

		private LinearizationFixture(List<RaceEvent> order) throws Exception {
			this.order = List.copyOf(order);
			this.clock = new MonotonicManualClock();
			this.executor = new ManualExecutorService();
			this.observer = new CountingExecutionObserver();
			this.execution = new McpApplicationExecution(
					new McpApplicationExecutionConfiguration(
							1, 1, Duration.ofNanos(QUEUED_DEADLINE_NANOS),
							Duration.ofDays(1)),
					this.clock, ignored -> this.executor, null, this.observer);
			this.queuedTransportRequest = transportRequest();
			this.queuedRequest = request("queued-race");
			this.activeInvocations = new AtomicInteger();
			this.queuedInvocations = new AtomicInteger();
			this.activeWrites = new AtomicInteger();
			this.queuedWrites = new AtomicInteger();
			this.activeCleanups = new AtomicInteger();
			this.queuedCleanups = new AtomicInteger();
			this.activeResponse = new AtomicReference<>();
			this.queuedResponse = new AtomicReference<>();

			this.clock.blockNextBackgroundCycle();
			this.execution.start();
			this.clock.awaitBackgroundCycle();
			this.execution.dispatch(transportRequest(), request("active-race"),
					admissionIdentity(), invocation -> {
						this.activeInvocations.incrementAndGet();
						return McpWireResult.complete(McpJsonObject.empty());
					}, ACTIVE_DEADLINE_NANOS, response -> {
						this.activeResponse.compareAndSet(null, response);
						this.activeWrites.incrementAndGet();
						return true;
					}, this.activeCleanups::incrementAndGet);
			this.execution.dispatch(this.queuedTransportRequest,
					this.queuedRequest, admissionIdentity(), invocation -> {
						this.queuedInvocations.incrementAndGet();
						return McpWireResult.complete(McpJsonObject.empty());
					}, QUEUED_DEADLINE_NANOS, response -> {
						this.queuedResponse.compareAndSet(null, response);
						this.queuedWrites.incrementAndGet();
						return true;
					}, this.queuedCleanups::incrementAndGet);

			this.clock.advanceAndRun(QUEUED_DEADLINE_NANOS - 1,
					this.execution::runTimerCycle);
			McpApplicationExecutionSnapshot before = this.execution.snapshot();
			Assertions.assertEquals(1, before.activeHandlerSlots(), this.orderString());
			Assertions.assertEquals(1, before.queuedRequests(), this.orderString());
			Assertions.assertEquals(2, before.retainedExchanges(), this.orderString());
			Assertions.assertEquals(2, before.retainedTransportLeases(),
					this.orderString());
			Assertions.assertEquals(0, before.deadlineExpirations(),
					this.orderString());
		}

		private void run() throws Exception {
			CountDownLatch ready = new CountDownLatch(RaceEvent.values().length);
			Map<RaceEvent, CountDownLatch> gates = latches();
			Map<RaceEvent, CountDownLatch> completed = latches();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			List<Thread> contenders = List.of(
					contender(RaceEvent.PROMOTION, ready, gates, completed, failure),
					contender(RaceEvent.DEADLINE, ready, gates, completed, failure),
					contender(RaceEvent.CLIENT_DISCONNECT, ready, gates, completed,
							failure));
			contenders.forEach(Thread::start);
			try {
				await(ready, "Race contenders were not staged: " + orderString());
				for (RaceEvent event : this.order) {
					gates.get(event).countDown();
					await(completed.get(event),
							"Race event did not finish: " + event + ' ' + orderString());
					if (failure.get() != null)
						throw new AssertionError(orderString(), failure.get());
				}
			} finally {
				gates.values().forEach(CountDownLatch::countDown);
				for (Thread contender : contenders)
					contender.join(TimeUnit.SECONDS.toMillis(5));
			}
			for (Thread contender : contenders)
				Assertions.assertFalse(contender.isAlive(), orderString());
			if (failure.get() != null)
				throw new AssertionError(orderString(), failure.get());
		}

		private Thread contender(RaceEvent event, CountDownLatch ready,
				Map<RaceEvent, CountDownLatch> gates,
				Map<RaceEvent, CountDownLatch> completed,
				AtomicReference<Throwable> failure) {
			return new Thread(() -> {
				ready.countDown();
				try {
					if (!gates.get(event).await(5, TimeUnit.SECONDS))
						throw new AssertionError("Race gate was not released.");
					run(event);
				} catch (Throwable throwable) {
					failure.compareAndSet(null, throwable);
				} finally {
					completed.get(event).countDown();
				}
			}, "mcp-queued-race-" + event.name().toLowerCase());
		}

		private void run(RaceEvent event) {
			switch (event) {
				case PROMOTION -> Assertions.assertTrue(this.executor.runNext(),
						"The active owner was not submitted: " + orderString());
				case DEADLINE -> this.clock.advanceAndRun(QUEUED_DEADLINE_NANOS,
						this.execution::runTimerCycle);
				case CLIENT_DISCONNECT -> this.execution.cancel(
						this.queuedTransportRequest,
						StreamTerminationReason.CLIENT_DISCONNECTED, null);
			}
		}

		private void assertOutcome() {
			boolean promotionFirst = this.order.get(0) == RaceEvent.PROMOTION;
			boolean deadlineWins = this.order.indexOf(RaceEvent.DEADLINE)
					< this.order.indexOf(RaceEvent.CLIENT_DISCONNECT);
			McpApplicationExecutionSnapshot beforePromotedRun =
					this.execution.snapshot();
			Assertions.assertEquals(promotionFirst ? 1 : 0,
					beforePromotedRun.activeHandlerSlots(), orderString());
			Assertions.assertEquals(0, beforePromotedRun.queuedRequests(),
					orderString());
			Assertions.assertEquals(promotionFirst ? 1 : 0,
					beforePromotedRun.retainedExchanges(), orderString());
			Assertions.assertEquals(0, beforePromotedRun.retainedTransportLeases(),
					orderString());
			Assertions.assertEquals(promotionFirst ? 1 : 0,
					this.executor.pendingCommands(), orderString());
			Assertions.assertEquals(promotionFirst ? 2 : 1,
					this.executor.submissionCount(), orderString());
			Assertions.assertEquals(0, this.queuedInvocations.get(), orderString());

			this.executor.runAll();
			McpApplicationExecutionSnapshot finished = this.execution.snapshot();
			Assertions.assertEquals(0, finished.activeHandlerSlots(), orderString());
			Assertions.assertEquals(0, finished.queuedRequests(), orderString());
			Assertions.assertEquals(0, finished.retainedExchanges(), orderString());
			Assertions.assertEquals(0, finished.retainedTransportLeases(),
					orderString());
			Assertions.assertEquals(2, finished.admittedRequests(), orderString());
			Assertions.assertEquals(0, finished.capacityRejections(), orderString());
			Assertions.assertEquals(deadlineWins ? 1 : 0,
					finished.deadlineExpirations(), orderString());
			Assertions.assertEquals(0, finished.protocolDeadlineExpirations(),
					orderString());
			Assertions.assertEquals(deadlineWins ? 2 : 1,
					finished.terminalResponses(), orderString());
			Assertions.assertEquals(deadlineWins ? 0 : 1,
					finished.abandonedResponses(), orderString());
			Assertions.assertEquals(2, finished.responseCleanups(), orderString());
			Assertions.assertEquals(1, finished.maximumObservedActiveHandlerSlots(),
					orderString());
			Assertions.assertEquals(1, finished.maximumObservedQueuedRequests(),
					orderString());

			Assertions.assertEquals(1, this.activeInvocations.get(), orderString());
			Assertions.assertEquals(0, this.queuedInvocations.get(), orderString());
			Assertions.assertEquals(1, this.activeWrites.get(), orderString());
			Assertions.assertEquals(1, this.activeCleanups.get(), orderString());
			Assertions.assertEquals(1, this.queuedCleanups.get(), orderString());
			Assertions.assertEquals(McpRequestOutcome.COMPLETE,
					this.activeResponse.get().outcome(), orderString());

			if (deadlineWins) {
				Assertions.assertEquals(1, this.queuedWrites.get(), orderString());
				McpApplicationResponse expected = promotionFirst
						? McpApplicationResponse.activeDeadline()
						: McpApplicationResponse.queuedDeadline(
								this.queuedRequest.id());
				Assertions.assertEquals(expected, this.queuedResponse.get(),
						orderString());
			} else {
				Assertions.assertEquals(0, this.queuedWrites.get(), orderString());
				Assertions.assertNull(this.queuedResponse.get(), orderString());
			}

			Assertions.assertEquals(1, this.observer.queuedCount(), orderString());
			Assertions.assertEquals(1, this.observer.dequeuedCount(), orderString());
			Assertions.assertEquals(promotionFirst ? 2 : 1,
					this.observer.executionStartedCount(), orderString());
			Assertions.assertEquals(promotionFirst ? 2 : 1,
					this.observer.executionFinishedCount(), orderString());
			Assertions.assertEquals(0, this.observer.capacityRejectedCount(),
					orderString());
			Assertions.assertEquals(0, this.observer.deferralDepth(), orderString());
		}

		private String orderString() {
			return "linearization=" + this.order;
		}

		@Override
		public void close() throws Exception {
			this.execution.stop();
			this.clock.releaseBackgroundCycle();
			this.executor.runAll();
			Assertions.assertTrue(
					this.execution.awaitTermination(Duration.ofSeconds(5)),
					orderString());
		}
	}

	private static Map<RaceEvent, CountDownLatch> latches() {
		Map<RaceEvent, CountDownLatch> latches = new EnumMap<>(RaceEvent.class);
		for (RaceEvent event : RaceEvent.values())
			latches.put(event, new CountDownLatch(1));
		return latches;
	}

	private static class CountingExecutionObserver
			implements McpApplicationExecutionObserver {
		private final AtomicInteger deferralDepth;
		private final AtomicInteger executionStarted;
		private final AtomicInteger executionFinished;
		private final AtomicInteger queued;
		private final AtomicInteger dequeued;
		private final AtomicInteger capacityRejected;

		private CountingExecutionObserver() {
			this.deferralDepth = new AtomicInteger();
			this.executionStarted = new AtomicInteger();
			this.executionFinished = new AtomicInteger();
			this.queued = new AtomicInteger();
			this.dequeued = new AtomicInteger();
			this.capacityRejected = new AtomicInteger();
		}

		@Override
		public void beginDeferral() {
			deferralDepth.incrementAndGet();
		}

		@Override
		public void recordHandlerExecutionStarted() {
			executionStarted.incrementAndGet();
		}

		@Override
		public void recordHandlerExecutionFinished() {
			executionFinished.incrementAndGet();
		}

		@Override
		public void recordHandlerQueued() {
			queued.incrementAndGet();
		}

		@Override
		public void recordHandlerDequeued() {
			dequeued.incrementAndGet();
		}

		@Override
		public void recordHandlerCapacityRejected() {
			capacityRejected.incrementAndGet();
		}

		@Override
		public void drain() {
		}

		@Override
		public void endDeferral() {
			int remaining = deferralDepth.decrementAndGet();
			if (remaining < 0)
				throw new IllegalStateException("Observer deferral is not active.");
		}

		private int deferralDepth() {
			return deferralDepth.get();
		}

		private int executionStartedCount() {
			return executionStarted.get();
		}

		private int executionFinishedCount() {
			return executionFinished.get();
		}

		final int queuedCount() {
			return queued.get();
		}

		final int dequeuedCount() {
			return dequeued.get();
		}

		final int capacityRejectedCount() {
			return capacityRejected.get();
		}
	}

	private static final class BlockingDeadlineObservation
			extends CountingExecutionObserver
			implements McpRuntimeObservationSink, McpRuntimeRequestObservation {
		private final AtomicInteger starts;
		private final AtomicInteger finishes;
		private final AtomicReference<McpRequestOutcome> outcome;
		private final AtomicReference<McpJsonRpcError> error;
		private final AtomicReference<List<Throwable>> throwables;
		private final CountDownLatch queued;
		private final CountDownLatch deadlineReserved;
		private final CountDownLatch releaseDeadline;
		private final CountDownLatch finished;
		private final AtomicBoolean blockDequeuedDeferral;
		private final AtomicBoolean blockConsumed;

		private BlockingDeadlineObservation() {
			this.starts = new AtomicInteger();
			this.finishes = new AtomicInteger();
			this.outcome = new AtomicReference<>();
			this.error = new AtomicReference<>();
			this.throwables = new AtomicReference<>(List.of());
			this.queued = new CountDownLatch(1);
			this.deadlineReserved = new CountDownLatch(1);
			this.releaseDeadline = new CountDownLatch(1);
			this.finished = new CountDownLatch(1);
			this.blockDequeuedDeferral = new AtomicBoolean();
			this.blockConsumed = new AtomicBoolean();
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
			this.finishes.incrementAndGet();
			this.finished.countDown();
		}

		@Override
		public void recordHandlerQueued() {
			super.recordHandlerQueued();
			queued.countDown();
		}

		@Override
		public void endDeferral() {
			super.endDeferral();
			if (blockDequeuedDeferral.get()
					&& dequeuedCount() == 1
					&& blockConsumed.compareAndSet(false, true)) {
				deadlineReserved.countDown();
				awaitUninterruptibly(releaseDeadline);
			}
		}

		private void blockNextDequeuedDeferral() {
			blockDequeuedDeferral.set(true);
		}

		private void awaitQueued() throws InterruptedException {
			await(queued, "The live request did not enter the application queue.");
		}

		private void awaitDeadlineReservation() throws InterruptedException {
			await(deadlineReserved,
					"The queued deadline did not reserve terminal ownership.");
		}

		private void releaseDeadlineReservation() {
			releaseDeadline.countDown();
		}

		private void awaitFinish() throws InterruptedException {
			await(finished, "The client disconnect observation was not delivered.");
		}

		private int finishCount() {
			return finishes.get();
		}

		private void assertExactlyOneClientDisconnect(Throwable cause) {
			Assertions.assertEquals(1, starts.get());
			Assertions.assertEquals(1, finishes.get());
			Assertions.assertEquals(McpRequestOutcome.CLIENT_DISCONNECTED,
					outcome.get());
			Assertions.assertNull(error.get());
			Assertions.assertEquals(List.of(cause), throwables.get());
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
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

	private static final class MonotonicManualClock
			implements McpApplicationClock {
		private final AtomicLong currentTime;
		private final AtomicBoolean blockBackground;
		private final CountDownLatch backgroundBlocked;
		private final CountDownLatch releaseBackground;

		private MonotonicManualClock() {
			this.currentTime = new AtomicLong();
			this.blockBackground = new AtomicBoolean();
			this.backgroundBlocked = new CountDownLatch(1);
			this.releaseBackground = new CountDownLatch(1);
		}

		@Override
		public long nanoTime() {
			long value = currentTime.get();
			if ("soklet-mcp-deadline".equals(Thread.currentThread().getName())
					&& blockBackground.compareAndSet(true, false)) {
				backgroundBlocked.countDown();
				awaitUninterruptibly(releaseBackground);
			}
			return value;
		}

		private void blockNextBackgroundCycle() {
			if (!blockBackground.compareAndSet(false, true))
				throw new IllegalStateException("A background cycle is already blocked.");
		}

		private void awaitBackgroundCycle() throws InterruptedException {
			await(backgroundBlocked,
					"The background deadline cycle did not reach its clock boundary.");
		}

		private void releaseBackgroundCycle() {
			releaseBackground.countDown();
		}

		private void advanceAndRun(long nowNanos, Runnable action) {
			long previous = currentTime.getAndUpdate(
					current -> Math.max(current, nowNanos));
			if (nowNanos < previous)
				throw new IllegalArgumentException("Manual time cannot move backward.");
			action.run();
		}
	}

	private static final class ManualExecutorService
			extends AbstractExecutorService {
		private final Queue<Runnable> commands = new ArrayDeque<>();
		private boolean shutdown;
		private int running;
		private int submissions;

		@Override
		public synchronized void shutdown() {
			shutdown = true;
		}

		@Override
		public synchronized List<Runnable> shutdownNow() {
			shutdown = true;
			List<Runnable> pending = List.copyOf(commands);
			commands.clear();
			return pending;
		}

		@Override
		public synchronized boolean isShutdown() {
			return shutdown;
		}

		@Override
		public synchronized boolean isTerminated() {
			return shutdown && commands.isEmpty() && running == 0;
		}

		@Override
		public synchronized boolean awaitTermination(long timeout,
				TimeUnit unit) {
			return isTerminated();
		}

		@Override
		public synchronized void execute(Runnable command) {
			if (shutdown)
				throw new RejectedExecutionException("Executor is shut down.");
			commands.add(command);
			submissions++;
		}

		private boolean runNext() {
			Runnable command;
			synchronized (this) {
				command = commands.poll();
				if (command == null)
					return false;
				running++;
			}
			try {
				command.run();
			} finally {
				synchronized (this) {
					running--;
				}
			}
			return true;
		}

		private void runAll() {
			while (runNext()) {
				// A completed owner may synchronously submit its promoted successor.
			}
		}

		private synchronized int pendingCommands() {
			return commands.size();
		}

		private synchronized int submissionCount() {
			return submissions;
		}
	}
}
