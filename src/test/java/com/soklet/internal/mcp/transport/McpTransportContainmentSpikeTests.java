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

package com.soklet.internal.mcp.transport;

import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.OptionsBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Executable Phase 1 containment spike. These tests intentionally exercise the package-private,
 * production-shaped transport over live sockets before a public MCP API is committed.
 */
@NotThreadSafe
public class McpTransportContainmentSpikeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final Duration TEST_TIMEOUT = Duration.ofSeconds(8);
	private static final Duration LONG_RUNTIME_TIMEOUT = Duration.ofMinutes(1);
	private static final int DEFAULT_OUTBOUND_BYTES = 64 * 1_024;
	private static final int DEFAULT_TERMINAL_BYTES = 4 * 1_024;

	@TestFactory
	public List<DynamicTest> containmentMatrix() {
		List<NamedScenario> scenarios = List.of(
				new NamedScenario("close-before-start", this::closeBeforeStartReleasesDedicatedRuntime),
				new NamedScenario("dedicated-listener", this::dedicatedListenerIsIndependent),
				new NamedScenario("bounded-admission", this::boundedAdmissionRejectsAndRecovers),
				new NamedScenario("saturated-progress", this::progressWritesWhileHandlersAreSaturated),
				new NamedScenario("subscription-isolation", this::subscriptionPublishesWhileHandlersAreSaturated),
				new NamedScenario("slow-reader-backpressure", this::slowReaderIsBoundedAndFastStreamProgresses),
				new NamedScenario("queued-reset-cleanup", this::queuedClientResetCleansUpBeforeDispatch),
				new NamedScenario("deadline-wire-outcomes", this::queuedAndCommittedDeadlinesHaveDistinctWireOutcomes),
				new NamedScenario("terminal-capacity-timer-isolation", this::terminalCapacityAndTimerFailureIsolation),
				new NamedScenario("completed-terminal-deadline-retention",
						this::completedTerminalRemainsCancelableAtDeadline),
				new NamedScenario("completed-terminal-shutdown-retention",
						this::completedTerminalRemainsCancelableAtShutdown),
				new NamedScenario("reserved-terminal-deadline", this::deadlineDiscardsUnwrittenReservedTerminal),
				new NamedScenario("shutdown-cleanup", this::shutdownCleansUpActiveAndQueuedRequests),
				new NamedScenario("noncooperative-containment", this::noncooperativeHandlerRetainsItsSlot),
				new NamedScenario("single-terminal", this::onlyOneConcurrentTerminalWins));
		List<DynamicTest> tests = new ArrayList<>();

		for (McpThreadStrategy strategy : McpThreadStrategy.values()) {
			if (!strategy.supported())
				continue;

			for (NamedScenario scenario : scenarios) {
				tests.add(DynamicTest.dynamicTest(
						strategy.name().toLowerCase(Locale.ROOT) + ": " + scenario.name(),
						() -> scenario.scenario().run(strategy)));
			}
		}

		Assertions.assertFalse(tests.isEmpty(), "The platform-thread containment matrix must always run");
		return tests;
	}

	private void closeBeforeStartReleasesDedicatedRuntime(McpThreadStrategy strategy) throws Exception {
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration(strategy, 1, 1),
				invocation -> Assertions.fail("an unstarted runtime cannot invoke a handler"));
		int port = runtime.port();

		try {
			runtime.close();
			runtime.close();
			Assertions.assertTrue(runtime.awaitHandlerTermination(TEST_TIMEOUT),
					"close-before-start did not terminate the MCP handler executor");
			Assertions.assertFalse(runtime.snapshot().running());
			Assertions.assertThrows(IllegalStateException.class, runtime::start,
					"a closed MCP runtime must not be restartable");

			try (ServerSocket rebound = new ServerSocket()) {
				rebound.bind(new InetSocketAddress(LOOPBACK, port));
				Assertions.assertEquals(port, rebound.getLocalPort(),
						"close-before-start did not release the dedicated MCP listener");
			}
		} finally {
			runtime.close();
		}
	}

	private void dedicatedListenerIsIndependent(McpThreadStrategy strategy) throws Exception {
		EventLoop normalEventLoop = new EventLoop(
				OptionsBuilder.newBuilder()
						.withHost(LOOPBACK)
						.withPort(0)
						.withConcurrency(1)
						.withResolution(Duration.ofMillis(10))
						.build(),
				(request, callback) -> callback.accept(new MicrohttpResponse(
						200,
						"OK",
						List.of(),
						"ordinary-soklet".getBytes(StandardCharsets.UTF_8))));
		McpTransportConfiguration configuration = configuration(strategy, 1, 1);
		assertPositiveFiniteBounds(configuration);
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration,
				invocation -> invocation.complete("mcp-" + invocation.requestId()));

		try {
			normalEventLoop.start();
			runtime.start();
			int normalPort = normalEventLoop.getPort();
			int mcpPort = runtime.port();
			Assertions.assertNotEquals(normalPort, mcpPort, "MCP must own a dedicated listener");

			try (RawHttpClient normal = RawHttpClient.get(normalPort, "/normal")) {
				HttpResponseHead head = normal.readHead();
				Assertions.assertEquals(200, head.status());
				Assertions.assertEquals("ordinary-soklet", normal.readFixedBody(head));
			}

			assertSuccessfulRequest(mcpPort, "before-normal-stop", "mcp-before-normal-stop");

			normalEventLoop.stop();
			normalEventLoop.join();
			assertSuccessfulRequest(mcpPort, "after-normal-stop", "mcp-after-normal-stop");

			McpTransportRuntime.Snapshot snapshot = awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0,
					"MCP exchanges did not clean up after the ordinary listener stopped");
			Assertions.assertEquals(2L, snapshot.admittedRequests());
			Assertions.assertEquals(strategy, snapshot.threadStrategy());
			Assertions.assertEquals(configuration.connectionWriterConcurrency(),
					snapshot.configuredConnectionWriterConcurrency());
			Assertions.assertEquals(configuration.maximumConnections(), snapshot.configuredMaximumConnections());
			Assertions.assertEquals(configuration.handlerConcurrency(), snapshot.dispatcher().concurrency());
			Assertions.assertEquals(configuration.handlerQueueCapacity(), snapshot.dispatcher().queueCapacity());
		} finally {
			normalEventLoop.stop();
			normalEventLoop.join();
			shutdown(runtime);
		}
	}

	private void boundedAdmissionRejectsAndRecovers(McpThreadStrategy strategy) throws Exception {
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration(strategy, 1, 1),
				invocation -> {
					if ("first".equals(invocation.requestId())) {
						firstStarted.countDown();
						releaseFirst.await();
					} else if ("second".equals(invocation.requestId())) {
						secondStarted.countDown();
					}

					invocation.complete(invocation.requestId() + "-result");
				});

		try {
			runtime.start();

			try (RawHttpClient first = RawHttpClient.post(
					runtime.port(), "/request", "first")) {
				await(firstStarted, "first request did not acquire the only handler slot");
				assertStreamingHead(first.readHead());

				try (RawHttpClient second = RawHttpClient.post(
						runtime.port(), "/request", "second")) {
					McpTransportRuntime.Snapshot saturated = awaitSnapshot(
							runtime,
							value -> value.dispatcher().activeSlots() == 1
									&& value.dispatcher().queueDepth() == 1,
							"one active plus one queued request was not observed");
					Assertions.assertEquals(1,
							saturated.dispatcher().maximumObservedActiveSlots());
					Assertions.assertEquals(1,
							saturated.dispatcher().maximumObservedQueueDepth());

					try (RawHttpClient rejected = RawHttpClient.post(
							runtime.port(), "/request", "third")) {
						assertUnavailable(rejected, "third");
					}

					Assertions.assertEquals(1L, runtime.snapshot().rejectedRequests());
					Assertions.assertEquals(1L, runtime.snapshot().cleanupCount());
					releaseFirst.countDown();
					assertTerminalResult(first, "first-result");
					await(secondStarted,
							"queued request did not acquire the released slot");
					assertStreamingHead(second.readHead());
					assertTerminalResult(second, "second-result");
				}
			}

			assertSuccessfulRequest(runtime.port(), "recovery", "recovery-result");
			McpTransportRuntime.Snapshot recovered = awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0
							&& value.dispatcher().activeSlots() == 0
							&& value.dispatcher().queueDepth() == 0
							&& value.cleanupCount() == 4,
					"handler capacity did not recover");
			Assertions.assertEquals(3L, recovered.admittedRequests());
			Assertions.assertEquals(1L, recovered.rejectedRequests());
			Assertions.assertEquals(4L, recovered.cleanupCount());
		} finally {
			releaseFirst.countDown();
			shutdown(runtime);
		}
	}

	private void progressWritesWhileHandlersAreSaturated(McpThreadStrategy strategy) throws Exception {
		CountDownLatch handlersStarted = new CountDownLatch(2);
		CountDownLatch progressEnqueued = new CountDownLatch(2);
		CountDownLatch releaseHandlers = new CountDownLatch(1);
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration(strategy, 2, 2),
				invocation -> {
					handlersStarted.countDown();
					invocation.progress("progress-" + invocation.requestId());
					progressEnqueued.countDown();
					releaseHandlers.await();
					invocation.complete("result-" + invocation.requestId());
				});

		try {
			runtime.start();

			try (RawHttpClient alpha = RawHttpClient.post(runtime.port(), "/request", "alpha");
					RawHttpClient beta = RawHttpClient.post(runtime.port(), "/request", "beta")) {
				await(handlersStarted, "both handler slots were not occupied");
				await(progressEnqueued, "progress did not enqueue from both occupied handlers");
				assertStreamingHead(alpha.readHead());
				assertStreamingHead(beta.readHead());
				Assertions.assertEquals(sse("progress", "progress-alpha"), alpha.readChunkText());
				Assertions.assertEquals(sse("progress", "progress-beta"), beta.readChunkText());

				McpTransportRuntime.Snapshot saturated = runtime.snapshot();
				Assertions.assertEquals(2, saturated.dispatcher().activeSlots());
				Assertions.assertEquals(2, saturated.dispatcher().maximumObservedActiveSlots());
				Assertions.assertEquals(0, saturated.dispatcher().queueDepth());
				releaseHandlers.countDown();
				assertTerminalResult(alpha, "result-alpha");
				assertTerminalResult(beta, "result-beta");
			}

			McpTransportRuntime.Snapshot cleaned = awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0 && value.dispatcher().activeSlots() == 0,
					"saturated progress requests did not clean up");
			Assertions.assertEquals(2L, cleaned.terminalReservations());
		} finally {
			releaseHandlers.countDown();
			shutdown(runtime);
		}
	}

	private void subscriptionPublishesWhileHandlersAreSaturated(McpThreadStrategy strategy) throws Exception {
		CountDownLatch subscriptionHandlerRan = new CountDownLatch(1);
		CountDownLatch requestHandlersStarted = new CountDownLatch(2);
		CountDownLatch releaseRequests = new CountDownLatch(1);
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration(strategy, 2, 2),
				invocation -> {
					if ("subscription".equals(invocation.requestId())) {
						invocation.becomeSubscription();
						subscriptionHandlerRan.countDown();
						return;
					}

					requestHandlersStarted.countDown();
					invocation.progress("working-" + invocation.requestId());
					releaseRequests.await();
					invocation.complete("done-" + invocation.requestId());
				});

		try {
			runtime.start();

			try (RawHttpClient subscription = RawHttpClient.post(runtime.port(), "/subscription", "subscription")) {
				await(subscriptionHandlerRan, "subscription handler did not run");
				assertStreamingHead(subscription.readHead());
				awaitSnapshot(
						runtime,
						value -> value.subscriptions() == 1 && value.dispatcher().activeSlots() == 0,
						"subscription retained a handler slot");

				try (RawHttpClient first = RawHttpClient.post(runtime.port(), "/request", "one");
						RawHttpClient second = RawHttpClient.post(runtime.port(), "/request", "two")) {
					await(requestHandlersStarted, "request handlers did not saturate both slots");
					assertStreamingHead(first.readHead());
					assertStreamingHead(second.readHead());
					Assertions.assertEquals(sse("progress", "working-one"), first.readChunkText());
					Assertions.assertEquals(sse("progress", "working-two"), second.readChunkText());
					McpTransportRuntime.Snapshot saturated = runtime.snapshot();
					Assertions.assertEquals(2, saturated.dispatcher().activeSlots());
					Assertions.assertEquals(1, saturated.subscriptions());

					Assertions.assertEquals(1, runtime.publishSubscriptionEvent("catalog-changed"));
					Assertions.assertEquals(
							sse("resources-updated", "catalog-changed"),
							subscription.readChunkText());

					releaseRequests.countDown();
					assertTerminalResult(first, "done-one");
					assertTerminalResult(second, "done-two");
				}

				subscription.closeWithReset();
				awaitSnapshot(
						runtime,
						value -> value.subscriptions() == 0,
						"committed-stream reset monitor did not clean up the subscription");
			}
		} finally {
			releaseRequests.countDown();
			shutdown(runtime);
		}
	}

	private void slowReaderIsBoundedAndFastStreamProgresses(McpThreadStrategy strategy) throws Exception {
		int largeFrameBytes = 16 * 1_024 * 1_024;
		String largePayload = "x".repeat(largeFrameBytes);
		CountDownLatch slowHandlerStarted = new CountDownLatch(1);
		CountDownLatch permitLargeFrame = new CountDownLatch(1);
		CountDownLatch slowHandlerExited = new CountDownLatch(1);
		AtomicBoolean fastHandlerRan = new AtomicBoolean();
		McpTransportConfiguration configuration = configuration(
				strategy,
				2,
				1,
				1,
				1,
				largeFrameBytes + 1_024,
				DEFAULT_TERMINAL_BYTES,
				LONG_RUNTIME_TIMEOUT);
		McpTransportRuntime runtime = new McpTransportRuntime(configuration, invocation -> {
			if ("slow".equals(invocation.requestId())) {
				slowHandlerStarted.countDown();
				try {
					permitLargeFrame.await();
					invocation.progress(largePayload);
					invocation.progress("blocked-behind-large-frame");
					invocation.complete("slow-result");
				} finally {
					slowHandlerExited.countDown();
				}
				return;
			}

			fastHandlerRan.set(true);
			invocation.complete("fast-result");
		});

		try {
			runtime.start();

			try (RawHttpClient slow = RawHttpClient.postWithReceiveBuffer(
					runtime.port(), "/request", "slow", 1_024)) {
				await(slowHandlerStarted, "slow handler did not start");
				assertStreamingHead(slow.readHead());
				permitLargeFrame.countDown();
				McpTransportRuntime.Snapshot pressured = awaitSnapshot(
						runtime,
						value -> value.appliedBackpressureCount() > 0,
						"slow reader did not apply bounded outbound backpressure");
				Assertions.assertTrue(pressured.bufferedFrames() <= 1);
				Assertions.assertTrue(pressured.bufferedBytes() <= largeFrameBytes + 1_024);
				Assertions.assertTrue(pressured.maximumObservedBufferedFramesPerStream() <= 1);
				Assertions.assertTrue(
						pressured.maximumObservedBufferedBytesPerStream() <= largeFrameBytes + 1_024);

				assertSuccessfulRequest(runtime.port(), "fast", "fast-result");
				Assertions.assertTrue(fastHandlerRan.get(), "fast handler was starved by the slow writer");
				Assertions.assertEquals(1L, slowHandlerExited.getCount(),
						"slow-reader backpressure disappeared before the client reset");
				awaitSnapshot(
						runtime,
						value -> value.liveExchanges() == 1 && value.dispatcher().activeSlots() == 1,
						"fast handler did not release its slot while the slow handler remained blocked");
				slow.closeWithReset();
				await(slowHandlerExited, "reset slow stream did not release its blocked handler");
			}

			awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0 && value.dispatcher().activeSlots() == 0,
					"slow-reader resources did not clean up");
		} finally {
			permitLargeFrame.countDown();
			shutdown(runtime);
		}
	}

	private void queuedClientResetCleansUpBeforeDispatch(McpThreadStrategy strategy) throws Exception {
		CountDownLatch heldStarted = new CountDownLatch(1);
		CountDownLatch releaseHeld = new CountDownLatch(1);
		CountDownLatch recoveryStarted = new CountDownLatch(1);
		AtomicBoolean resetRequestRan = new AtomicBoolean();
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration(strategy, 1, 1),
				invocation -> {
					if ("held".equals(invocation.requestId())) {
						heldStarted.countDown();
						releaseHeld.await();
					} else if ("reset-while-queued".equals(invocation.requestId())) {
						resetRequestRan.set(true);
					} else if ("recovery".equals(invocation.requestId())) {
						recoveryStarted.countDown();
					}

					invocation.complete(invocation.requestId() + "-result");
				});

		try {
			runtime.start();

			try (RawHttpClient held = RawHttpClient.post(runtime.port(), "/request", "held")) {
				await(heldStarted, "held handler did not start");
				assertStreamingHead(held.readHead());
				RawHttpClient reset = RawHttpClient.post(runtime.port(), "/request", "reset-while-queued");

				try {
					awaitSnapshot(
							runtime,
							value -> value.dispatcher().queueDepth() == 1,
							"reset candidate did not queue");
					reset.closeWithReset();
				} finally {
					reset.close();
				}

				McpTransportRuntime.Snapshot afterReset = awaitSnapshot(
						runtime,
						value -> value.dispatcher().queueDepth() == 0
								&& value.liveExchanges() == 1
								&& value.cleanupCount() == 1,
						"queued client reset did not remove and clean up the dispatch");
				Assertions.assertFalse(resetRequestRan.get(), "reset queued request reached application code");
				Assertions.assertEquals(1, afterReset.dispatcher().activeSlots());

				try (RawHttpClient recovery = RawHttpClient.post(runtime.port(), "/request", "recovery")) {
					awaitSnapshot(
							runtime,
							value -> value.dispatcher().queueDepth() == 1,
							"capacity was not reusable after queued reset cleanup");
					releaseHeld.countDown();
					assertTerminalResult(held, "held-result");
					await(recoveryStarted, "recovery request did not acquire the released slot");
					assertStreamingHead(recovery.readHead());
					assertTerminalResult(recovery, "recovery-result");
				}
			}

			McpTransportRuntime.Snapshot cleaned = awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0
							&& value.dispatcher().activeSlots() == 0
							&& value.cleanupCount() == 3,
					"reset containment fixture did not clean up");
			Assertions.assertEquals(3L, cleaned.cleanupCount());
		} finally {
			releaseHeld.countDown();
			shutdown(runtime);
		}
	}

	private void queuedAndCommittedDeadlinesHaveDistinctWireOutcomes(McpThreadStrategy strategy)
			throws Exception {
		ManualClock clock = new ManualClock();
		CountDownLatch committedStarted = new CountDownLatch(1);
		CountDownLatch releaseCommitted = new CountDownLatch(1);
		CountDownLatch committedInterrupted = new CountDownLatch(1);
		AtomicBoolean queuedRan = new AtomicBoolean();
		Duration requestDeadline = Duration.ofSeconds(5);
		McpTransportConfiguration configuration = configuration(
				strategy,
				1,
				1,
				1,
				4,
				DEFAULT_OUTBOUND_BYTES,
				DEFAULT_TERMINAL_BYTES,
				requestDeadline);
		McpTransportRuntime runtime = new McpTransportRuntime(configuration, invocation -> {
			if ("committed".equals(invocation.requestId())) {
				committedStarted.countDown();

				try {
					releaseCommitted.await();
				} catch (InterruptedException exception) {
					committedInterrupted.countDown();
					throw exception;
				}
			} else {
				queuedRan.set(true);
			}
		}, clock);

		try {
			runtime.start();

			try (RawHttpClient committed = RawHttpClient.post(
					runtime.port(), "/request", "committed")) {
				await(committedStarted, "committed handler did not start");
				assertStreamingHead(committed.readHead());

				try (RawHttpClient queued = RawHttpClient.post(
						runtime.port(), "/request", "queued")) {
					awaitSnapshot(
							runtime,
							value -> value.dispatcher().queueDepth() == 1,
							"deadline candidate did not remain queued");
					clock.advance(requestDeadline.plusNanos(1));
					runtime.runTimerCycle();

					assertUnavailable(queued, "queued");
					Assertions.assertEquals(
							sse("error", "Request deadline exceeded"),
							committed.readChunkText());
					Assertions.assertNull(committed.readChunk(),
							"committed deadline emitted more than one terminal");
					await(committedInterrupted,
							"committed deadline did not signal handler interruption");
					Assertions.assertFalse(queuedRan.get(),
							"expired queued request reached application code");
				}
			}

			McpTransportRuntime.Snapshot cleaned = awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0
							&& value.dispatcher().activeSlots() == 0
							&& value.dispatcher().queueDepth() == 0
							&& value.cleanupCount() == 2,
					"deadline outcomes did not clean up");
			Assertions.assertEquals(1L, cleaned.terminalReservations());
			Assertions.assertEquals(2L, cleaned.cleanupCount());
			Assertions.assertEquals(2L, cleaned.admittedRequests());
			Assertions.assertEquals(0L, cleaned.rejectedRequests(),
					"queued deadline expiry is not a capacity rejection");
		} finally {
			releaseCommitted.countDown();
			shutdown(runtime);
		}
	}

	private void terminalCapacityAndTimerFailureIsolation(McpThreadStrategy strategy) throws Exception {
		int minimumTerminalBytes = McpTransportConfiguration.MINIMUM_FRAMEWORK_TERMINAL_BYTE_CAPACITY;
		IllegalArgumentException capacityFailure = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> configuration(
						strategy,
						2,
						1,
						1,
						4,
						DEFAULT_OUTBOUND_BYTES,
						minimumTerminalBytes - 1,
						Duration.ofSeconds(5)));
		Assertions.assertTrue(capacityFailure.getMessage().contains(Integer.toString(minimumTerminalBytes)));

		ManualClock clock = new ManualClock();
		Duration requestDeadline = Duration.ofSeconds(5);
		CountDownLatch handlersStarted = new CountDownLatch(2);
		CountDownLatch handlersInterrupted = new CountDownLatch(2);
		CountDownLatch releaseHandlers = new CountDownLatch(1);
		CountDownLatch failureInjected = new CountDownLatch(1);
		AtomicBoolean probeArmed = new AtomicBoolean();
		AtomicBoolean injected = new AtomicBoolean();
		AtomicReference<String> failedRequestId = new AtomicReference<>();
		Map<String, StreamTerminationReason> cancelationReasons = new ConcurrentHashMap<>();
		McpTransportConfiguration configuration = configuration(
				strategy,
				2,
				1,
				1,
				4,
				DEFAULT_OUTBOUND_BYTES,
				minimumTerminalBytes,
				requestDeadline);
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration,
				invocation -> {
					if ("recovery".equals(invocation.requestId())) {
						invocation.complete("recovery-result");
						return;
					}

					handlersStarted.countDown();

					try {
						releaseHandlers.await();
					} catch (InterruptedException exception) {
						cancelationReasons.put(
								invocation.requestId(),
								invocation.cancellationReason().orElseThrow());
						handlersInterrupted.countDown();
						throw exception;
					}
				},
				clock,
				requestId -> {
					if (probeArmed.get() && injected.compareAndSet(false, true)) {
						failedRequestId.set(requestId);
						failureInjected.countDown();
						throw new IllegalStateException("injected timer failure");
					}
				});

		try {
			runtime.start();

			try (RawHttpClient alpha = RawHttpClient.post(runtime.port(), "/request", "alpha");
					RawHttpClient beta = RawHttpClient.post(runtime.port(), "/request", "beta")) {
				await(handlersStarted, "timer-isolation handlers did not both start");
				assertStreamingHead(alpha.readHead());
				assertStreamingHead(beta.readHead());
				clock.advance(requestDeadline.plusNanos(1));
				probeArmed.set(true);
				await(failureInjected, "timer thread did not execute the injected exchange failure");

				String failedId = failedRequestId.get();
				Assertions.assertTrue("alpha".equals(failedId) || "beta".equals(failedId), failedId);
				RawHttpClient failedClient = "alpha".equals(failedId) ? alpha : beta;
				RawHttpClient deadlineClient = "alpha".equals(failedId) ? beta : alpha;
				String deadlineId = "alpha".equals(failedId) ? "beta" : "alpha";

				Assertions.assertThrows(IOException.class, failedClient::readChunk,
						"failed exchange unexpectedly produced a normal terminal chunk");
				Assertions.assertEquals(
						sse("error", "Request deadline exceeded"),
						deadlineClient.readChunkText());
				Assertions.assertNull(deadlineClient.readChunk());
				await(handlersInterrupted, "timer outcomes did not interrupt both handlers");
				Assertions.assertEquals(StreamTerminationReason.INTERNAL_ERROR,
						cancelationReasons.get(failedId));
				Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,
						cancelationReasons.get(deadlineId));
			}

			McpTransportRuntime.Snapshot isolated = awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0
							&& value.dispatcher().activeSlots() == 0
							&& value.cleanupCount() == 2,
					"timer failure did not remain isolated to one exchange");
			Assertions.assertEquals(1L, isolated.terminalReservations());
			Assertions.assertTrue(runtime.timerThreadAlive(), "one exchange failure killed the timer thread");
			assertSuccessfulRequest(runtime.port(), "recovery", "recovery-result");
			Assertions.assertTrue(runtime.timerThreadAlive(), "timer thread died after recovery traffic");
		} finally {
			releaseHandlers.countDown();
			shutdown(runtime);
		}
	}

	private void completedTerminalRemainsCancelableAtDeadline(McpThreadStrategy strategy) throws Exception {
		ManualClock clock = new ManualClock();
		Duration requestDeadline = Duration.ofSeconds(5);
		CountDownLatch terminalReserved = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		AtomicReference<Boolean> completionWon = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> cancelationReason = new AtomicReference<>();
		McpTransportConfiguration configuration = configuration(
				strategy,
				1,
				1,
				1,
				4,
				DEFAULT_OUTBOUND_BYTES,
				DEFAULT_TERMINAL_BYTES,
				requestDeadline);
		McpTransportRuntime runtime = new McpTransportRuntime(configuration, invocation -> {
			completionWon.set(invocation.complete("already-sent"));
			terminalReserved.countDown();

			while (releaseHandler.getCount() > 0) {
				try {
					releaseHandler.await();
				} catch (InterruptedException ignored) {
					cancelationReason.set(invocation.cancellationReason().orElse(null));
					interrupted.countDown();
				}
			}
		}, clock);

		try {
			runtime.start();

			try (RawHttpClient client = RawHttpClient.post(runtime.port(), "/request", "complete-then-hang")) {
				await(terminalReserved, "handler did not reserve its application terminal");
				assertStreamingHead(client.readHead());
				assertTerminalResult(client, "already-sent");
				Assertions.assertEquals(Boolean.TRUE, completionWon.get());

				McpTransportRuntime.Snapshot retained = awaitSnapshot(
						runtime,
						value -> value.liveExchanges() == 0
								&& value.activeStreams() == 0
								&& value.cleanupCount() == 1
								&& value.dispatcher().activeSlots() == 1
								&& value.residualHandlerSlots() == 1,
						"completed response did not retain cancellation ownership for its running handler");
				Assertions.assertEquals(0, retained.bufferedFrames());
				Assertions.assertEquals(0, retained.terminalBytes());
				clock.advance(requestDeadline.plusNanos(1));
				runtime.runTimerCycle();
				await(interrupted, "deadline did not interrupt handler after response completion");
				Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, cancelationReason.get());
				Assertions.assertEquals(1, runtime.snapshot().residualHandlerSlots());
				releaseHandler.countDown();
			}

			McpTransportRuntime.Snapshot released = awaitSnapshot(
					runtime,
					value -> value.dispatcher().activeSlots() == 0 && value.residualHandlerSlots() == 0,
					"deadline-canceled completed handler did not return its slot");
			Assertions.assertEquals(1L, released.cleanupCount());
			Assertions.assertEquals(1L, released.terminalReservations());
		} finally {
			releaseHandler.countDown();
			shutdown(runtime);
		}
	}

	private void completedTerminalRemainsCancelableAtShutdown(McpThreadStrategy strategy) throws Exception {
		CountDownLatch terminalReserved = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> cancelationReason = new AtomicReference<>();
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration(strategy, 1, 1),
				invocation -> {
					invocation.complete("sent-before-stop");
					terminalReserved.countDown();

					while (releaseHandler.getCount() > 0) {
						try {
							releaseHandler.await();
						} catch (InterruptedException ignored) {
							cancelationReason.set(invocation.cancellationReason().orElse(null));
							interrupted.countDown();
						}
					}
				});

		try {
			runtime.start();

			try (RawHttpClient client = RawHttpClient.post(runtime.port(), "/request", "complete-before-stop")) {
				await(terminalReserved, "shutdown fixture did not reserve its terminal");
				assertStreamingHead(client.readHead());
				assertTerminalResult(client, "sent-before-stop");
				awaitSnapshot(
						runtime,
						value -> value.cleanupCount() == 1
								&& value.liveExchanges() == 0
								&& value.residualHandlerSlots() == 1,
						"completed response was not retained through handler exit");
				runtime.stop();
				await(interrupted, "shutdown did not interrupt handler after response completion");
				Assertions.assertEquals(StreamTerminationReason.SERVER_STOPPING, cancelationReason.get());
				McpTransportRuntime.Snapshot retained = runtime.snapshot();
				Assertions.assertFalse(retained.running());
				Assertions.assertEquals(1, retained.dispatcher().activeSlots());
				Assertions.assertEquals(1, retained.residualHandlerSlots());
				Assertions.assertEquals(1L, retained.cleanupCount());
				releaseHandler.countDown();
			}

			McpTransportRuntime.Snapshot released = awaitSnapshot(
					runtime,
					value -> value.dispatcher().activeSlots() == 0 && value.residualHandlerSlots() == 0,
					"shutdown-canceled completed handler did not return its slot");
			Assertions.assertEquals(1L, released.cleanupCount());
			runtime.join();
		} finally {
			releaseHandler.countDown();
			shutdown(runtime);
		}
	}

	private void deadlineDiscardsUnwrittenReservedTerminal(McpThreadStrategy strategy) throws Exception {
		int largeFrameBytes = 16 * 1_024 * 1_024;
		String largePayload = "x".repeat(largeFrameBytes);
		ManualClock clock = new ManualClock();
		Duration requestDeadline = Duration.ofSeconds(5);
		CountDownLatch handlerStarted = new CountDownLatch(1);
		CountDownLatch permitWrite = new CountDownLatch(1);
		CountDownLatch terminalReserved = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		AtomicReference<Boolean> completionWon = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> cancelationReason = new AtomicReference<>();
		McpTransportConfiguration configuration = configuration(
				strategy,
				1,
				1,
				1,
				1,
				largeFrameBytes + 1_024,
				DEFAULT_TERMINAL_BYTES,
				requestDeadline);
		McpTransportRuntime runtime = new McpTransportRuntime(configuration, invocation -> {
			handlerStarted.countDown();
			permitWrite.await();
			invocation.progress(largePayload);
			completionWon.set(invocation.complete("stale-terminal"));
			terminalReserved.countDown();

			while (releaseHandler.getCount() > 0) {
				try {
					releaseHandler.await();
				} catch (InterruptedException ignored) {
					cancelationReason.set(invocation.cancellationReason().orElse(null));
					interrupted.countDown();
				}
			}
		}, clock);

		try {
			runtime.start();

			try (RawHttpClient client = RawHttpClient.postWithReceiveBuffer(
					runtime.port(), "/request", "reserved-before-deadline", 1_024)) {
				await(handlerStarted, "reserved-terminal handler did not start");
				assertStreamingHead(client.readHead());
				permitWrite.countDown();
				await(terminalReserved, "application terminal was not reserved behind the large frame");
				Assertions.assertEquals(Boolean.TRUE, completionWon.get());
				McpTransportRuntime.Snapshot pending = awaitSnapshot(
						runtime,
						value -> value.bufferedFrames() == 1 && value.terminalBytes() > 0,
						"reserved terminal drained before the deadline fixture could exercise it");
				Assertions.assertEquals(1L, pending.terminalReservations());
				clock.advance(requestDeadline.plusNanos(1));
				runtime.runTimerCycle();
				await(interrupted, "deadline did not interrupt handler with an unwritten terminal");
				Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, cancelationReason.get());
				McpTransportRuntime.Snapshot failed = awaitSnapshot(
						runtime,
						value -> value.liveExchanges() == 0
								&& value.activeStreams() == 0
								&& value.bufferedFrames() == 0
								&& value.terminalBytes() == 0
								&& value.cleanupCount() == 1
								&& value.residualHandlerSlots() == 1,
						"deadline did not discard the stale reserved terminal");
				Assertions.assertEquals(1L, failed.terminalReservations(),
						"deadline must not count a second terminal reservation when the app already won");
				Assertions.assertFalse(
						client.readUntilClosedContains("stale-terminal", largeFrameBytes + 8 * 1_024),
						"stale application terminal drained after the absolute deadline");
				releaseHandler.countDown();
			}

			awaitSnapshot(
					runtime,
					value -> value.dispatcher().activeSlots() == 0 && value.residualHandlerSlots() == 0,
					"reserved-terminal handler did not return its slot");
		} finally {
			permitWrite.countDown();
			releaseHandler.countDown();
			shutdown(runtime);
		}
	}

	private void shutdownCleansUpActiveAndQueuedRequests(McpThreadStrategy strategy) throws Exception {
		CountDownLatch subscriptionReady = new CountDownLatch(1);
		CountDownLatch activeStarted = new CountDownLatch(1);
		CountDownLatch activeInterrupted = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		AtomicBoolean queuedRan = new AtomicBoolean();
		AtomicReference<StreamTerminationReason> activeCancelationReason = new AtomicReference<>();
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration(strategy, 1, 1),
				invocation -> {
					if ("subscription-at-stop".equals(invocation.requestId())) {
						invocation.becomeSubscription();
						subscriptionReady.countDown();
					} else if ("active-at-stop".equals(invocation.requestId())) {
						activeStarted.countDown();

						try {
							releaseActive.await();
						} catch (InterruptedException exception) {
							activeCancelationReason.set(invocation.cancellationReason().orElse(null));
							activeInterrupted.countDown();
							throw exception;
						}
					} else {
						queuedRan.set(true);
					}
				});

		try {
			runtime.start();

			try (RawHttpClient subscription = RawHttpClient.post(
					runtime.port(), "/subscription", "subscription-at-stop")) {
				await(subscriptionReady, "shutdown subscription handler did not run");
				assertStreamingHead(subscription.readHead());
				awaitSnapshot(
						runtime,
						value -> value.subscriptions() == 1 && value.dispatcher().activeSlots() == 0,
						"shutdown subscription retained a handler slot");

				try (RawHttpClient active = RawHttpClient.post(
						runtime.port(), "/request", "active-at-stop")) {
					await(activeStarted, "active shutdown fixture did not acquire its slot");
					assertStreamingHead(active.readHead());

					try (RawHttpClient queued = RawHttpClient.post(
							runtime.port(), "/request", "queued-at-stop")) {
						awaitSnapshot(
								runtime,
								value -> value.dispatcher().activeSlots() == 1
										&& value.dispatcher().queueDepth() == 1
										&& value.subscriptions() == 1,
								"shutdown fixture did not reach subscription-plus-active-plus-queued state");
						runtime.stop();
						runtime.join();
						await(activeInterrupted,
								"server shutdown did not signal the active handler");
						Assertions.assertEquals(
								StreamTerminationReason.SERVER_STOPPING,
								activeCancelationReason.get());
						Assertions.assertFalse(queuedRan.get(),
								"server shutdown dispatched queued application work");
					}
				}
			}

			McpTransportRuntime.Snapshot cleaned = awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0
							&& value.dispatcher().activeSlots() == 0
							&& value.dispatcher().queueDepth() == 0
							&& value.subscriptions() == 0
							&& value.cleanupCount() == 3,
					"shutdown did not clean up subscription, active, and queued framework state");
			Assertions.assertFalse(cleaned.running());
			Assertions.assertEquals(3L, cleaned.cleanupCount());
			Assertions.assertEquals(0, cleaned.residualHandlerSlots());
		} finally {
			releaseActive.countDown();
			shutdown(runtime);
		}
	}

	private void noncooperativeHandlerRetainsItsSlot(McpThreadStrategy strategy) throws Exception {
		ManualClock clock = new ManualClock();
		CountDownLatch stuckStarted = new CountDownLatch(1);
		CountDownLatch stuckInterrupted = new CountDownLatch(1);
		CountDownLatch releaseStuck = new CountDownLatch(1);
		CountDownLatch queuedStarted = new CountDownLatch(1);
		AtomicReference<Boolean> lateCompletionWon = new AtomicReference<>();
		AtomicReference<Thread> stuckThread = new AtomicReference<>();
		Duration requestDeadline = Duration.ofSeconds(5);
		McpTransportConfiguration configuration = configuration(
				strategy,
				1,
				1,
				1,
				4,
				DEFAULT_OUTBOUND_BYTES,
				DEFAULT_TERMINAL_BYTES,
				requestDeadline);
		McpTransportRuntime runtime = new McpTransportRuntime(configuration, invocation -> {
			if ("stuck".equals(invocation.requestId())) {
				stuckThread.set(Thread.currentThread());
				stuckStarted.countDown();

				while (releaseStuck.getCount() > 0) {
					try {
						releaseStuck.await();
					} catch (InterruptedException ignored) {
						stuckInterrupted.countDown();
					}
				}

				lateCompletionWon.set(invocation.complete("late-result"));
				return;
			}

			if ("queued-after-timeout".equals(invocation.requestId()))
				queuedStarted.countDown();

			invocation.complete(invocation.requestId() + "-result");
		}, clock);

		try {
			runtime.start();

			try (RawHttpClient stuck = RawHttpClient.post(runtime.port(), "/request", "stuck")) {
				await(stuckStarted, "noncooperative handler did not start");
				assertStreamingHead(stuck.readHead());
				clock.advance(requestDeadline.plusNanos(1));
				runtime.runTimerCycle();
				Assertions.assertEquals(
						sse("error", "Request deadline exceeded"),
						stuck.readChunkText());
				Assertions.assertNull(stuck.readChunk(), "timed-out handler emitted more than one terminal");
				await(stuckInterrupted, "noncooperative handler was not signaled");

				McpTransportRuntime.Snapshot retained = awaitSnapshot(
						runtime,
						value -> value.liveExchanges() == 0
								&& value.dispatcher().activeSlots() == 1
								&& value.residualHandlerSlots() == 1,
						"timed-out noncooperative handler did not remain charged to its slot");
				Assertions.assertEquals(0, retained.dispatcher().queueDepth());
				Thread retainedThread = stuckThread.get();
				Assertions.assertNotNull(retainedThread);
				Assertions.assertTrue(retainedThread.isAlive(),
						"residual slot was not backed by the still-running handler worker");
				Assertions.assertTrue(retainedThread.getName().startsWith("soklet-mcp-handler-"),
						"handler escaped the MCP-owned executor: " + retainedThread.getName());
				Assertions.assertEquals(strategy == McpThreadStrategy.VIRTUAL, isVirtual(retainedThread),
						"handler worker did not match the configured thread strategy");

				try (RawHttpClient queued = RawHttpClient.post(
						runtime.port(), "/request", "queued-after-timeout")) {
					awaitSnapshot(
							runtime,
							value -> value.dispatcher().queueDepth() == 1,
							"request did not queue behind residual handler slot");
					Assertions.assertEquals(1L, queuedStarted.getCount(),
							"queued request bypassed the retained slot");

					try (RawHttpClient rejected = RawHttpClient.post(runtime.port(), "/request", "overflow")) {
						assertUnavailable(rejected, "overflow");
					}

					releaseStuck.countDown();
					await(queuedStarted, "queued request did not start after actual handler exit");
					assertStreamingHead(queued.readHead());
					assertTerminalResult(queued, "queued-after-timeout-result");
				}
			}

			Assertions.assertEquals(Boolean.FALSE, lateCompletionWon.get(),
					"late noncooperative completion replaced the reserved deadline terminal");
			McpTransportRuntime.Snapshot recovered = awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0 && value.dispatcher().activeSlots() == 0,
					"slot capacity did not recover after actual noncooperative handler exit");
			Assertions.assertEquals(0, recovered.residualHandlerSlots());
			Assertions.assertEquals(1L, recovered.rejectedRequests());
		} finally {
			releaseStuck.countDown();
			shutdown(runtime);
		}
	}

	private void onlyOneConcurrentTerminalWins(McpThreadStrategy strategy) throws Exception {
		AtomicInteger terminalWinners = new AtomicInteger();
		AtomicReference<Throwable> competitorFailure = new AtomicReference<>();
		McpTransportRuntime runtime = new McpTransportRuntime(
				configuration(strategy, 1, 1),
				invocation -> {
					CountDownLatch startCompetitors = new CountDownLatch(1);
					Thread first = terminalCompetitor(
							invocation,
							"terminal-one",
							startCompetitors,
							terminalWinners,
							competitorFailure);
					Thread second = terminalCompetitor(
							invocation,
							"terminal-two",
							startCompetitors,
							terminalWinners,
							competitorFailure);
					first.start();
					second.start();
					startCompetitors.countDown();
					first.join();
					second.join();
				});

		try {
			runtime.start();

			try (RawHttpClient client = RawHttpClient.post(runtime.port(), "/request", "terminal-race")) {
				assertStreamingHead(client.readHead());
				String terminal = client.readChunkText();
				Assertions.assertTrue(
						terminal.equals(sse("result", "terminal-one"))
								|| terminal.equals(sse("result", "terminal-two")),
						terminal);
				Assertions.assertNull(client.readChunk(), "concurrent completion emitted a second terminal frame");
			}

			Assertions.assertNull(competitorFailure.get());
			Assertions.assertEquals(1, terminalWinners.get());
			McpTransportRuntime.Snapshot cleaned = awaitSnapshot(
					runtime,
					value -> value.liveExchanges() == 0 && value.dispatcher().activeSlots() == 0,
					"single-terminal fixture did not clean up");
			Assertions.assertEquals(1L, cleaned.terminalReservations());
		} finally {
			shutdown(runtime);
		}
	}

	private static Thread terminalCompetitor(McpTransportRuntime.Invocation invocation, String value,
			CountDownLatch start, AtomicInteger winners, AtomicReference<Throwable> failure) {
		return new Thread(() -> {
			try {
				start.await();

				if (invocation.complete(value))
					winners.incrementAndGet();
			} catch (Throwable throwable) {
				failure.compareAndSet(null, throwable);
			}
		}, "mcp-terminal-competitor");
	}

	private static void assertSuccessfulRequest(int port, String requestId, String expectedResult)
			throws Exception {
		try (RawHttpClient client = RawHttpClient.post(port, "/request", requestId)) {
			assertStreamingHead(client.readHead());
			assertTerminalResult(client, expectedResult);
		}
	}

	private static void assertStreamingHead(HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream", head.header("content-type"));
		Assertions.assertEquals("chunked", head.header("transfer-encoding"));
	}

	private static void assertTerminalResult(RawHttpClient client, String value) throws Exception {
		Assertions.assertEquals(sse("result", value), client.readChunkText());
		Assertions.assertNull(client.readChunk(), "response emitted data after its terminal result");
	}

	private static void assertUnavailable(RawHttpClient client, String requestId) throws Exception {
		HttpResponseHead head = client.readHead();
		Assertions.assertEquals(503, head.status(), head.raw());
		Assertions.assertEquals("application/json", head.header("content-type"));
		Assertions.assertNull(head.header("retry-after"));
		String body = client.readFixedBody(head);
		Assertions.assertEquals(unavailableBody(requestId), body);
		Assertions.assertFalse(body.contains("\"data\""), body);
	}

	private static String unavailableBody(String requestId) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
	}

	private static String sse(String event, String data) {
		return "event: " + event + "\ndata: " + data + "\n\n";
	}

	private static McpTransportConfiguration configuration(McpThreadStrategy strategy,
			int handlerConcurrency, int handlerQueueCapacity) {
		return configuration(
				strategy,
				handlerConcurrency,
				handlerQueueCapacity,
				1,
				8,
				DEFAULT_OUTBOUND_BYTES,
				DEFAULT_TERMINAL_BYTES,
				LONG_RUNTIME_TIMEOUT);
	}

	private static void assertPositiveFiniteBounds(McpTransportConfiguration configuration) {
		Assertions.assertTrue(configuration.connectionWriterConcurrency() > 0);
		Assertions.assertTrue(configuration.maximumConnections() > 0);
		Assertions.assertTrue(configuration.handlerConcurrency() > 0);
		Assertions.assertTrue(configuration.handlerQueueCapacity() > 0);
		Assertions.assertTrue(configuration.outboundFrameCapacity() > 0);
		Assertions.assertTrue(configuration.outboundByteCapacity() > 0);
		Assertions.assertTrue(configuration.terminalByteCapacity() > 0);
		Assertions.assertTrue(configuration.requestDeadline().toNanos() > 0L);
		Assertions.assertTrue(configuration.responseWriteIdleTimeout().toNanos() > 0L);
		Assertions.assertTrue(configuration.keepAliveInterval().toNanos() > 0L);
	}

	private static boolean isVirtual(Thread thread) {
		try {
			return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
		} catch (NoSuchMethodException exception) {
			return false;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Unable to inspect handler thread strategy", exception);
		}
	}

	private static McpTransportConfiguration configuration(McpThreadStrategy strategy,
			int handlerConcurrency, int handlerQueueCapacity, int connectionWriterConcurrency,
			int outboundFrameCapacity, int outboundByteCapacity, int terminalByteCapacity,
			Duration requestDeadline) {
		return new McpTransportConfiguration(
				LOOPBACK,
				0,
				connectionWriterConcurrency,
				64,
				handlerConcurrency,
				handlerQueueCapacity,
				outboundFrameCapacity,
				outboundByteCapacity,
				terminalByteCapacity,
				requestDeadline,
				LONG_RUNTIME_TIMEOUT,
				LONG_RUNTIME_TIMEOUT,
				strategy);
	}

	private static McpTransportRuntime.Snapshot awaitSnapshot(McpTransportRuntime runtime,
			Predicate<McpTransportRuntime.Snapshot> predicate, String failureMessage) {
		long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
		McpTransportRuntime.Snapshot snapshot;
		long spins = 0L;

		do {
			snapshot = runtime.snapshot();

			if (predicate.test(snapshot))
				return snapshot;

			if ((++spins & 1_023L) == 0L)
				Thread.yield();
			else
				Thread.onSpinWait();
		} while (System.nanoTime() - deadline < 0L);

		Assertions.fail(failureMessage + "; final snapshot=" + snapshot);
		return snapshot;
	}

	private static void await(CountDownLatch latch, String failureMessage) throws InterruptedException {
		Assertions.assertTrue(latch.await(TEST_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), failureMessage);
	}

	private static void shutdown(McpTransportRuntime runtime) throws InterruptedException {
		runtime.close();
		Assertions.assertTrue(
				runtime.awaitHandlerTermination(TEST_TIMEOUT),
				"MCP handler executor did not terminate");
	}

	@FunctionalInterface
	private interface Scenario {
		void run(McpThreadStrategy strategy) throws Exception;
	}

	private record NamedScenario(String name, Scenario scenario) {
	}

	private static final class ManualClock implements McpMonotonicClock {
		private final AtomicLong now;

		private ManualClock() {
			this.now = new AtomicLong();
		}

		@Override
		public long nanoTime() {
			return now.get();
		}

		private void advance(Duration duration) {
			now.addAndGet(duration.toNanos());
		}
	}

	private record HttpResponseHead(String raw, int status, Map<String, String> headers) {
		private String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}
	}

	private static final class RawHttpClient implements AutoCloseable {
		private static final int MAXIMUM_HEAD_BYTES = 64 * 1_024;
		private static final int MAXIMUM_CHUNK_BYTES = 20 * 1_024 * 1_024;

		private final Socket socket;
		private final InputStream inputStream;
		private boolean terminalChunkRead;

		private RawHttpClient(int port, int receiveBufferBytes) throws IOException {
			this.socket = new Socket();

			if (receiveBufferBytes > 0)
				socket.setReceiveBufferSize(receiveBufferBytes);

			socket.setTcpNoDelay(true);
			socket.setSoTimeout((int) TEST_TIMEOUT.toMillis());
			socket.connect(new InetSocketAddress(LOOPBACK, port), (int) TEST_TIMEOUT.toMillis());
			this.inputStream = socket.getInputStream();
		}

		private static RawHttpClient post(int port, String path, String body) throws IOException {
			return postWithReceiveBuffer(port, path, body, 0);
		}

		private static RawHttpClient postWithReceiveBuffer(int port, String path, String body,
				int receiveBufferBytes) throws IOException {
			RawHttpClient client = new RawHttpClient(port, receiveBufferBytes);

			try {
				byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
				client.write(("POST " + path + " HTTP/1.1\r\n"
						+ "Host: " + LOOPBACK + "\r\n"
						+ "Content-Type: application/json\r\n"
						+ "Content-Length: " + bodyBytes.length + "\r\n"
						+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				client.write(bodyBytes);
				client.socket.getOutputStream().flush();
				return client;
			} catch (Throwable throwable) {
				client.close();
				throw throwable;
			}
		}

		private static RawHttpClient get(int port, String path) throws IOException {
			RawHttpClient client = new RawHttpClient(port, 0);

			try {
				client.write(("GET " + path + " HTTP/1.1\r\n"
						+ "Host: " + LOOPBACK + "\r\n"
						+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				client.socket.getOutputStream().flush();
				return client;
			} catch (Throwable throwable) {
				client.close();
				throw throwable;
			}
		}

		private void write(byte[] bytes) throws IOException {
			socket.getOutputStream().write(bytes);
		}

		private HttpResponseHead readHead() throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			int matched = 0;

			while (bytes.size() < MAXIMUM_HEAD_BYTES) {
				int value = inputStream.read();

				if (value < 0)
					throw new EOFException("Socket closed before the HTTP response head was complete");

				bytes.write(value);
				matched = switch (matched) {
					case 0 -> value == '\r' ? 1 : 0;
					case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
					case 2 -> value == '\r' ? 3 : 0;
					case 3 -> value == '\n' ? 4 : 0;
					default -> matched;
				};

				if (matched == 4)
					break;
			}

			if (matched != 4)
				throw new IOException("HTTP response head exceeded " + MAXIMUM_HEAD_BYTES + " bytes");

			String raw = bytes.toString(StandardCharsets.ISO_8859_1);
			String[] lines = raw.substring(0, raw.length() - 4).split("\\r\\n");
			String[] statusParts = lines[0].split(" ", 3);

			if (statusParts.length < 2)
				throw new IOException("Malformed HTTP status line: " + lines[0]);

			Map<String, String> headers = new LinkedHashMap<>();

			for (int index = 1; index < lines.length; index++) {
				int colon = lines[index].indexOf(':');

				if (colon < 1)
					throw new IOException("Malformed HTTP response header: " + lines[index]);

				headers.put(
						lines[index].substring(0, colon).trim().toLowerCase(Locale.ROOT),
						lines[index].substring(colon + 1).trim());
			}

			return new HttpResponseHead(raw, Integer.parseInt(statusParts[1]), Map.copyOf(headers));
		}

		private String readFixedBody(HttpResponseHead head) throws IOException {
			String contentLength = head.header("content-length");

			if (contentLength == null)
				throw new IOException("Expected Content-Length response: " + head.raw());

			int length = Integer.parseInt(contentLength);
			return new String(readExactly(length), StandardCharsets.UTF_8);
		}

		private String readChunkText() throws IOException {
			byte[] chunk = readChunk();

			if (chunk == null)
				throw new EOFException("Expected another HTTP chunk, but reached the terminal chunk");

			return new String(chunk, StandardCharsets.UTF_8);
		}

		private byte[] readChunk() throws IOException {
			if (terminalChunkRead)
				return null;

			String sizeLine = readCrlfLine();
			int extension = sizeLine.indexOf(';');
			String hexadecimal = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim();
			long size;

			try {
				size = Long.parseLong(hexadecimal, 16);
			} catch (NumberFormatException exception) {
				throw new IOException("Malformed HTTP chunk size: " + sizeLine, exception);
			}

			if (size == 0L) {
				String trailer;

				do {
					trailer = readCrlfLine();
				} while (!trailer.isEmpty());

				terminalChunkRead = true;
				return null;
			}

			if (size < 0L || size > MAXIMUM_CHUNK_BYTES)
				throw new IOException("HTTP chunk exceeds test bound: " + size);

			byte[] payload = readExactly((int) size);
			int carriageReturn = inputStream.read();
			int lineFeed = inputStream.read();

			if (carriageReturn != '\r' || lineFeed != '\n')
				throw new IOException("HTTP chunk payload was not followed by CRLF");

			return payload;
		}

		private String readCrlfLine() throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			boolean carriageReturn = false;

			while (bytes.size() < MAXIMUM_HEAD_BYTES) {
				int value = inputStream.read();

				if (value < 0)
					throw new EOFException("Socket closed while reading an HTTP chunk line");

				if (carriageReturn && value == '\n') {
					byte[] line = bytes.toByteArray();
					return new String(line, 0, line.length - 1, StandardCharsets.US_ASCII);
				}

				bytes.write(value);
				carriageReturn = value == '\r';
			}

			throw new IOException("HTTP chunk line exceeded test bound");
		}

		private byte[] readExactly(int length) throws IOException {
			byte[] bytes = new byte[length];
			int offset = 0;

			while (offset < bytes.length) {
				int read = inputStream.read(bytes, offset, bytes.length - offset);

				if (read < 0)
					throw new EOFException("Socket closed with " + (bytes.length - offset) + " bytes remaining");

				offset += read;
			}

			return bytes;
		}

		private boolean readUntilClosedContains(String value, int maximumBytes) throws IOException {
			byte[] expected = value.getBytes(StandardCharsets.UTF_8);
			int matched = 0;

			for (int count = 0; count < maximumBytes; count++) {
				int next;

				try {
					next = inputStream.read();
				} catch (SocketTimeoutException exception) {
					throw exception;
				} catch (SocketException exception) {
					return false;
				}

				if (next < 0)
					return false;

				if ((byte) next == expected[matched]) {
					matched++;

					if (matched == expected.length)
						return true;
				} else {
					matched = (byte) next == expected[0] ? 1 : 0;
				}
			}

			throw new IOException("Response remained open beyond the " + maximumBytes + "-byte test bound");
		}

		private void closeWithReset() throws IOException {
			if (socket.isClosed())
				return;

			socket.setSoLinger(true, 0);
			socket.close();
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}
}
