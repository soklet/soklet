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
import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

@NotThreadSafe
@Timeout(20)
public class McpHttpServerApplicationExecutionTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String APPLICATION_METHOD = "test/execute";
	private static final String JSON_MEDIA_TYPE = "application/json";

	@Test
	public void application_handler_returns_json_and_preserves_string_and_integer_ids()
			throws Exception {
		AtomicInteger invocations = new AtomicInteger();
		McpApplicationRequestRouter router = router(invocation -> {
			invocations.incrementAndGet();
			return completeResult("handled");
		});
		McpHttpServerRuntime runtime = runtime(router, executionConfiguration(2, 2),
				McpApplicationClock.SYSTEM);

		try {
			int port = runtime.start().getPort();
			RawResponse stringResponse = send(port, "\"application-string\"");
			RawResponse integerResponse = send(port, "73");

			assertSuccessfulResult(stringResponse, "\"application-string\"", "handled");
			assertSuccessfulResult(integerResponse, "73", "handled");
			Assertions.assertFalse(integerResponse.bodyText().contains("\"id\":\"73\""),
					integerResponse.bodyText());
			Assertions.assertEquals(2, invocations.get());
			awaitSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 0
					&& snapshot.queuedRequests() == 0
					&& snapshot.activeRequestIds() == 0
					&& snapshot.retainedExchanges() == 0);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void active_request_ids_are_shared_by_application_and_framework_methods()
			throws Exception {
		CountDownLatch applicationEntered = new CountDownLatch(1);
		CountDownLatch releaseApplication = new CountDownLatch(1);
		McpApplicationRequestRouter router = router(invocation -> {
			applicationEntered.countDown();
			releaseApplication.await();
			return completeResult("application-complete");
		});
		McpHttpServerRuntime runtime = runtime(router, executionConfiguration(1, 1),
				McpApplicationClock.SYSTEM);
		ExecutorService client = Executors.newSingleThreadExecutor();

		try {
			int port = runtime.start().getPort();
			Future<RawResponse> application = client.submit(
					() -> send(port, "\"shared-id\"", APPLICATION_METHOD));
			Assertions.assertTrue(applicationEntered.await(5, TimeUnit.SECONDS),
					"The application request did not enter.");
			awaitSnapshot(runtime, snapshot -> snapshot.activeRequestIds() == 1);

			RawResponse duplicateDiscovery = send(
					port, "\"shared-id\"", "server/discover");
			Assertions.assertEquals(400, duplicateDiscovery.status(),
					duplicateDiscovery.bodyText());
			Assertions.assertTrue(duplicateDiscovery.bodyText().contains("\"code\":-32600"),
					duplicateDiscovery.bodyText());
			Assertions.assertTrue(duplicateDiscovery.bodyText().contains(
					"\"id\":\"shared-id\""), duplicateDiscovery.bodyText());
			McpApplicationExecutionSnapshot collision =
					runtime.applicationExecutionSnapshot().orElseThrow();
			Assertions.assertEquals(1, collision.activeRequestIds());
			Assertions.assertEquals(1, collision.duplicateIdRejections());

			releaseApplication.countDown();
			assertSuccessfulResult(application.get(5, TimeUnit.SECONDS),
					"\"shared-id\"", "application-complete");
			awaitSnapshot(runtime, snapshot -> snapshot.activeRequestIds() == 0
					&& snapshot.retainedExchanges() == 0);

			RawResponse discoveryAfterRelease = send(
					port, "\"shared-id\"", "server/discover");
			Assertions.assertEquals(200, discoveryAfterRelease.status(),
					discoveryAfterRelease.bodyText());
		} finally {
			releaseApplication.countDown();
			runtime.close();
			shutdown(client);
		}
	}

	@Test
	public void application_executor_factory_failure_restores_a_restartable_runtime()
			throws Exception {
		AtomicInteger factoryInvocations = new AtomicInteger();
		McpApplicationHandlerExecutorFactory executorFactory = concurrency -> {
			if (factoryInvocations.incrementAndGet() == 1)
				throw new IllegalStateException("simulated executor construction failure");
			return Executors.newSingleThreadExecutor();
		};
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"restart-after-factory-failure", "3.6.0-SNAPSHOT"))
				.build();
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
						request -> McpRequestAdmissionDecision.ACCEPT),
				endpoint, McpJsonLimits.productionDefaults(),
				McpApplicationRequestRouter.empty(), executionConfiguration(1, 1),
				McpApplicationClock.SYSTEM, executorFactory);

		try {
			IllegalStateException failure = Assertions.assertThrows(
					IllegalStateException.class, runtime::start);
			Assertions.assertEquals("simulated executor construction failure",
					failure.getMessage());
			Assertions.assertFalse(runtime.isStarted());
			Assertions.assertTrue(runtime.boundAddress().isEmpty());

			int port = runtime.start().getPort();
			Assertions.assertEquals(200,
					send(port, "\"after-factory-failure\"", "server/discover").status());
			Assertions.assertEquals(2, factoryInvocations.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void bounded_handler_capacity_queues_one_and_rejects_the_third_exactly()
			throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpApplicationRequestRouter router = router(invocation -> {
			int invocationNumber = invocations.incrementAndGet();
			if (invocationNumber == 1) {
				firstEntered.countDown();
				releaseFirst.await();
			} else if (invocationNumber == 2) {
				secondEntered.countDown();
			}
			return completeResult("invocation-" + invocationNumber);
		});
		McpHttpServerRuntime runtime = runtime(router, executionConfiguration(1, 1),
				McpApplicationClock.SYSTEM);
		ExecutorService clients = Executors.newFixedThreadPool(2);

		try {
			int port = runtime.start().getPort();
			Future<RawResponse> first = clients.submit(() -> send(port, "\"first\""));
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS),
					"The first handler did not enter.");

			Future<RawResponse> second = clients.submit(() -> send(port, "\"second\""));
			awaitSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 1
					&& snapshot.queuedRequests() == 1);

			RawResponse third = send(port, "\"third\"");
			assertExactCapacityResponse(third, "\"third\"");
			Assertions.assertEquals(1, invocations.get(),
					"A capacity-rejected request must never reach application code.");

			releaseFirst.countDown();
			Assertions.assertTrue(secondEntered.await(5, TimeUnit.SECONDS),
					"The queued request did not dispatch after the first handler exited.");
			assertSuccessfulResult(first.get(5, TimeUnit.SECONDS), "\"first\"",
					"invocation-1");
			assertSuccessfulResult(second.get(5, TimeUnit.SECONDS), "\"second\"",
					"invocation-2");
			Assertions.assertEquals(2, invocations.get());

			McpApplicationExecutionSnapshot snapshot = awaitSnapshot(runtime,
					value -> value.activeHandlerSlots() == 0
							&& value.queuedRequests() == 0
							&& value.activeRequestIds() == 0
							&& value.retainedExchanges() == 0);
			Assertions.assertEquals(1, snapshot.maximumObservedActiveHandlerSlots());
			Assertions.assertEquals(1, snapshot.maximumObservedQueuedRequests());
			Assertions.assertEquals(1, snapshot.capacityRejections());
		} finally {
			releaseFirst.countDown();
			runtime.close();
			shutdown(clients);
		}
	}

	@Test
	public void queued_absolute_deadline_gets_the_exact_capacity_response_without_dispatch()
			throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch firstInterrupted = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpApplicationRequestRouter router = router(invocation -> {
			int invocationNumber = invocations.incrementAndGet();
			if (invocationNumber == 1) {
				firstEntered.countDown();
				boolean released = false;
				while (!released) {
					try {
						released = releaseFirst.await(25, TimeUnit.MILLISECONDS);
					} catch (InterruptedException exception) {
						firstInterrupted.countDown();
						// Deliberately keep running so the active slot cannot be recycled.
					}
				}
			}
			return completeResult("invocation-" + invocationNumber);
		});
		ControllableClock clock = new ControllableClock();
		McpApplicationExecutionConfiguration configuration =
				new McpApplicationExecutionConfiguration(1, 1,
						Duration.ofSeconds(5), Duration.ofDays(1));
		McpHttpServerRuntime runtime = runtime(router, configuration, clock);
		ExecutorService clients = Executors.newFixedThreadPool(2);

		try {
			int port = runtime.start().getPort();
			Future<RawResponse> first = clients.submit(() -> send(port, "\"active\""));
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS),
					"The active handler did not enter.");
			Future<RawResponse> queued = clients.submit(
					() -> send(port, "\"queued-deadline\""));
			awaitSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 1
					&& snapshot.queuedRequests() == 1);

			clock.advance(Duration.ofSeconds(6));
			runtime.runApplicationTimerCycle();

			RawResponse queuedResponse = queued.get(5, TimeUnit.SECONDS);
			assertExactCapacityResponse(queuedResponse, "\"queued-deadline\"");
			Assertions.assertTrue(firstInterrupted.await(5, TimeUnit.SECONDS),
					"The simultaneously expired active handler was not interrupted.");
			Assertions.assertEquals(1, invocations.get(),
					"An expired queued request must never reach application code.");
			Assertions.assertEquals(1,
					runtime.applicationExecutionSnapshot().orElseThrow().activeHandlerSlots(),
					"The noncooperative active handler must retain its slot.");

			// The active-deadline wire mapping is intentionally provisional in 3B.1.
			first.get(5, TimeUnit.SECONDS);
			releaseFirst.countDown();
			McpApplicationExecutionSnapshot snapshot = awaitSnapshot(runtime,
					value -> value.activeHandlerSlots() == 0
							&& value.queuedRequests() == 0
							&& value.activeRequestIds() == 0
							&& value.retainedExchanges() == 0);
			Assertions.assertEquals(2, snapshot.deadlineExpirations());
		} finally {
			releaseFirst.countDown();
			runtime.close();
			shutdown(clients);
		}
	}

	@Test
	public void request_deadline_is_captured_before_protocol_admission_work()
			throws Exception {
		CountDownLatch admissionEntered = new CountDownLatch(1);
		CountDownLatch releaseAdmission = new CountDownLatch(1);
		AtomicInteger handlerInvocations = new AtomicInteger();
		ControllableClock clock = new ControllableClock();
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"acceptance-deadline-test", "3.6.0-SNAPSHOT"))
				.build();
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(), request -> {
					admissionEntered.countDown();
					releaseAdmission.await();
					return McpRequestAdmissionDecision.ACCEPT;
				}), endpoint, router(invocation -> {
					handlerInvocations.incrementAndGet();
					return completeResult("must-not-run");
				}), new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(5), Duration.ofDays(1)), clock);
		ExecutorService client = Executors.newSingleThreadExecutor();

		try {
			int port = runtime.start().getPort();
			Future<RawResponse> response = client.submit(
					() -> send(port, "\"protocol-delay\""));
			Assertions.assertTrue(admissionEntered.await(5, TimeUnit.SECONDS),
					"The admission gate did not enter.");
			clock.advance(Duration.ofSeconds(6));
			releaseAdmission.countDown();

			assertExactCapacityResponse(response.get(5, TimeUnit.SECONDS),
					"\"protocol-delay\"");
			Assertions.assertEquals(0, handlerInvocations.get(),
					"Protocol-stage work must not reset the absolute deadline.");
			McpApplicationExecutionSnapshot snapshot = awaitSnapshot(runtime,
					value -> value.retainedExchanges() == 0
							&& value.activeRequestIds() == 0);
			Assertions.assertEquals(1, snapshot.deadlineExpirations());
			Assertions.assertEquals(0, snapshot.capacityRejections());
		} finally {
			releaseAdmission.countDown();
			runtime.close();
			shutdown(client);
		}
	}

	@Test
	public void queued_client_disconnect_removes_the_request_without_dispatch()
			throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpApplicationRequestRouter router = router(invocation -> {
			int invocationNumber = invocations.incrementAndGet();
			if (invocationNumber == 1) {
				firstEntered.countDown();
				releaseFirst.await();
			}
			return completeResult("invocation-" + invocationNumber);
		});
		McpHttpServerRuntime runtime = runtime(router, executionConfiguration(1, 1),
				McpApplicationClock.SYSTEM);
		ExecutorService clients = Executors.newSingleThreadExecutor();
		Socket queuedClient = null;

		try {
			int port = runtime.start().getPort();
			Future<RawResponse> active = clients.submit(() -> send(port, "\"active\""));
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS),
					"The active handler did not enter.");

			queuedClient = openRequest(port, "\"queued-disconnect\"");
			awaitSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 1
					&& snapshot.queuedRequests() == 1);
			queuedClient.setSoLinger(true, 0);
			queuedClient.close();

			McpApplicationExecutionSnapshot afterDisconnect = awaitSnapshot(runtime,
					snapshot -> snapshot.activeHandlerSlots() == 1
							&& snapshot.queuedRequests() == 0
							&& snapshot.retainedExchanges() == 1);
			Assertions.assertEquals(1, invocations.get(),
					"A disconnected queued request must never reach application code.");
			Assertions.assertEquals(1, afterDisconnect.abandonedResponses());

			releaseFirst.countDown();
			assertSuccessfulResult(active.get(5, TimeUnit.SECONDS), "\"active\"",
					"invocation-1");
			awaitSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 0
					&& snapshot.queuedRequests() == 0
					&& snapshot.activeRequestIds() == 0
					&& snapshot.retainedExchanges() == 0);
			Assertions.assertEquals(1, invocations.get(),
					"The disconnected request dispatched after the active slot was released.");
		} finally {
			releaseFirst.countDown();
			if (queuedClient != null && !queuedClient.isClosed())
				queuedClient.close();
			runtime.close();
			shutdown(clients);
		}
	}

	@Test
	public void active_client_disconnect_interrupts_but_retains_the_slot_until_handler_exit()
			throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch firstInterrupted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch firstExited = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		AtomicBoolean cancellationObserved = new AtomicBoolean();
		AtomicReference<Optional<StreamTerminationReason>> cancellationReason =
				new AtomicReference<>(Optional.empty());
		McpApplicationRequestRouter router = router(invocation -> {
			int invocationNumber = invocations.incrementAndGet();
			if (invocationNumber == 1) {
				firstEntered.countDown();
				boolean released = false;
				try {
					while (!released) {
						try {
							released = releaseFirst.await(25, TimeUnit.MILLISECONDS);
						} catch (InterruptedException exception) {
							cancellationObserved.set(invocation.isCancellationRequested());
							cancellationReason.set(invocation.cancellationReason());
							firstInterrupted.countDown();
							// Deliberately continue until the test releases the handler.
						}
					}
				} finally {
					firstExited.countDown();
				}
			}
			return completeResult("invocation-" + invocationNumber);
		});
		McpHttpServerRuntime runtime = runtime(router, executionConfiguration(1, 1),
				McpApplicationClock.SYSTEM);
		ExecutorService clients = Executors.newSingleThreadExecutor();
		Socket disconnectedClient = null;

		try {
			int port = runtime.start().getPort();
			disconnectedClient = openRequest(port, "\"disconnect\"");
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS),
					"The disconnect test handler did not enter.");
			disconnectedClient.setSoLinger(true, 0);
			disconnectedClient.close();

			Assertions.assertTrue(firstInterrupted.await(5, TimeUnit.SECONDS),
					"Disconnect did not interrupt the active handler.");
			Assertions.assertTrue(cancellationObserved.get(),
					"The handler did not observe the cancellation signal.");
			Assertions.assertEquals(Optional.of(StreamTerminationReason.CLIENT_DISCONNECTED),
					cancellationReason.get());
			McpApplicationExecutionSnapshot retained = awaitSnapshot(runtime,
					snapshot -> snapshot.activeHandlerSlots() == 1
							&& snapshot.retainedExchanges() == 1);
			Assertions.assertEquals(0, retained.queuedRequests());
			Assertions.assertEquals(0, retained.retainedTransportLeases(),
					"A noncooperative handler must not retain its closed transport lease.");

			Future<RawResponse> second = clients.submit(() -> send(port, "\"after-disconnect\""));
			awaitSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 1
					&& snapshot.queuedRequests() == 1);
			Assertions.assertEquals(1, invocations.get(),
					"The interrupted handler's slot was recycled before it exited.");

			releaseFirst.countDown();
			Assertions.assertTrue(firstExited.await(5, TimeUnit.SECONDS),
					"The noncooperative handler did not exit after release.");
			assertSuccessfulResult(second.get(5, TimeUnit.SECONDS),
					"\"after-disconnect\"", "invocation-2");
			Assertions.assertEquals(2, invocations.get());
			awaitSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 0
					&& snapshot.queuedRequests() == 0
					&& snapshot.activeRequestIds() == 0
					&& snapshot.retainedExchanges() == 0);
		} finally {
			releaseFirst.countDown();
			if (disconnectedClient != null && !disconnectedClient.isClosed())
				disconnectedClient.close();
			runtime.close();
			shutdown(clients);
		}
	}

	@Test
	public void shutdown_interrupts_active_handler_without_promoting_queued_work()
			throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch firstInterrupted = new CountDownLatch(1);
		CountDownLatch firstExited = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpApplicationRequestRouter router = router(invocation -> {
			int invocationNumber = invocations.incrementAndGet();
			if (invocationNumber == 1) {
				firstEntered.countDown();
				try {
					emergencyRelease.await();
				} catch (InterruptedException exception) {
					firstInterrupted.countDown();
					throw exception;
				} finally {
					firstExited.countDown();
				}
			}
			return completeResult("invocation-" + invocationNumber);
		});
		McpHttpServerRuntime runtime = runtime(router, executionConfiguration(1, 1),
				McpApplicationClock.SYSTEM);
		Socket activeClient = null;
		Socket queuedClient = null;

		try {
			int port = runtime.start().getPort();
			activeClient = openRequest(port, "\"active-at-stop\"");
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS),
					"The active shutdown handler did not enter.");
			queuedClient = openRequest(port, "\"queued-at-stop\"");
			McpApplicationExecutionSnapshot beforeStop = awaitSnapshot(runtime,
					snapshot -> snapshot.activeHandlerSlots() == 1
							&& snapshot.queuedRequests() == 1
							&& snapshot.retainedExchanges() == 2);
			Assertions.assertEquals(2, beforeStop.retainedTransportLeases());

			runtime.stop();

			Assertions.assertTrue(firstInterrupted.await(5, TimeUnit.SECONDS),
					"Shutdown did not interrupt the active handler.");
			Assertions.assertTrue(firstExited.await(5, TimeUnit.SECONDS),
					"The interrupted active handler did not exit promptly.");
			Assertions.assertEquals(1, invocations.get(),
					"Shutdown must cancel queued work without promoting it.");
			Assertions.assertFalse(runtime.isStarted());
			Assertions.assertTrue(runtime.boundAddress().isEmpty());
			Assertions.assertTrue(runtime.applicationExecutionSnapshot().isEmpty(),
					"Clean shutdown must not retain exchanges, transport leases, or handler work.");

			activeClient.close();
			activeClient = null;
			queuedClient.close();
			queuedClient = null;

			int restartedPort = runtime.start().getPort();
			assertSuccessfulResult(send(restartedPort, "\"after-clean-shutdown\""),
					"\"after-clean-shutdown\"", "invocation-2");
			Assertions.assertEquals(2, invocations.get());
		} finally {
			emergencyRelease.countDown();
			if (activeClient != null)
				activeClient.close();
			if (queuedClient != null)
				queuedClient.close();
			runtime.close();
		}
	}

	@Test
	public void shutdown_reports_residual_application_work_and_blocks_restart_until_exit()
			throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch firstInterrupted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpApplicationRequestRouter router = router(invocation -> {
			int invocationNumber = invocations.incrementAndGet();
			if (invocationNumber == 1) {
				firstEntered.countDown();
				boolean released = false;
				while (!released) {
					try {
						released = releaseFirst.await(25, TimeUnit.MILLISECONDS);
					} catch (InterruptedException exception) {
						firstInterrupted.countDown();
						// Model application code that ignores cooperative shutdown.
					}
				}
			}
			return completeResult("invocation-" + invocationNumber);
		});
		McpHttpServerRuntime runtime = runtime(
				transportConfiguration(Duration.ofMillis(75)), router,
				executionConfiguration(1, 1), McpApplicationClock.SYSTEM);
		Socket firstClient = null;

		try {
			int firstPort = runtime.start().getPort();
			firstClient = openRequest(firstPort, "\"residual\"");
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS),
					"The residual handler did not enter.");

			runtime.stop();
			Assertions.assertTrue(firstInterrupted.await(5, TimeUnit.SECONDS),
					"Shutdown did not interrupt the residual handler.");
			Assertions.assertFalse(runtime.isStarted());
			McpApplicationExecutionSnapshot residual =
					runtime.applicationExecutionSnapshot().orElseThrow();
			Assertions.assertEquals(1, residual.activeHandlerSlots());
			Assertions.assertEquals(1, residual.retainedExchanges());
			Assertions.assertEquals(0, residual.retainedTransportLeases());
			Assertions.assertFalse(residual.accepting());
			Assertions.assertFalse(residual.terminated());

			IllegalStateException restartFailure = Assertions.assertThrows(
					IllegalStateException.class, runtime::start);
			Assertions.assertEquals(
					"Cannot start MCP server while residual handler executions remain",
					restartFailure.getMessage());

			releaseFirst.countDown();
			awaitSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 0
					&& snapshot.retainedExchanges() == 0 && snapshot.terminated());

			int restartedPort = runtime.start().getPort();
			assertSuccessfulResult(send(restartedPort, "\"after-residual\""),
					"\"after-residual\"", "invocation-2");
			Assertions.assertEquals(2, invocations.get());
		} finally {
			releaseFirst.countDown();
			if (firstClient != null)
				firstClient.close();
			runtime.close();
		}
	}

	private static McpApplicationRequestRouter router(
			McpApplicationRequestHandler handler) {
		return McpApplicationRequestRouter.fromHandlers(Map.of(APPLICATION_METHOD, handler));
	}

	private static McpHttpServerRuntime runtime(McpApplicationRequestRouter router,
			McpApplicationExecutionConfiguration executionConfiguration,
			McpApplicationClock clock) {
		return runtime(McpHttpTransportConfiguration.productionDefaults(0), router,
				executionConfiguration, clock);
	}

	private static McpHttpServerRuntime runtime(
			McpHttpTransportConfiguration transportConfiguration,
			McpApplicationRequestRouter router,
			McpApplicationExecutionConfiguration executionConfiguration,
			McpApplicationClock clock) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"application-test-server", "3.6.0-SNAPSHOT"))
				.build();
		return new McpHttpServerRuntime(
				transportConfiguration,
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
						request -> McpRequestAdmissionDecision.ACCEPT),
				endpoint, router, executionConfiguration, clock);
	}

	private static McpHttpTransportConfiguration transportConfiguration(
			Duration shutdownTimeout) {
		McpHttpTransportConfiguration defaults =
				McpHttpTransportConfiguration.productionDefaults(0);
		return new McpHttpTransportConfiguration(
				defaults.host(), defaults.port(), defaults.selectorResolution(),
				defaults.requestHeaderTimeout(), defaults.requestBodyTimeout(),
				defaults.responseWriteIdleTimeout(), shutdownTimeout,
				defaults.readBufferSize(), defaults.acceptBacklog(),
				defaults.maximumAggregateRequestBytes(),
				defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				defaults.maximumConnections(), defaults.connectionWriterConcurrency(),
				defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity());
	}

	private static McpApplicationExecutionConfiguration executionConfiguration(
			int concurrency, int queueCapacity) {
		return new McpApplicationExecutionConfiguration(concurrency, queueCapacity,
				Duration.ofSeconds(15), Duration.ofMillis(10));
	}

	private static McpWireResult completeResult(String value) {
		return McpWireResult.complete(new McpJsonObject(
				Map.of("value", new McpJsonString(value))));
	}

	private static RawResponse send(int port, String idJson) throws Exception {
		return send(port, idJson, APPLICATION_METHOD);
	}

	private static RawResponse send(int port, String idJson, String method) throws Exception {
		try (Socket socket = openRequest(port, idJson, method)) {
			socket.setSoTimeout(5_000);
			ByteArrayOutputStream response = new ByteArrayOutputStream();
			InputStream input = socket.getInputStream();
			byte[] buffer = new byte[4_096];
			int read;
			while ((read = input.read(buffer)) >= 0)
				response.write(buffer, 0, read);
			return RawResponse.parse(response.toByteArray());
		}
	}

	private static Socket openRequest(int port, String idJson) throws Exception {
		return openRequest(port, idJson, APPLICATION_METHOD);
	}

	private static Socket openRequest(int port, String idJson, String method)
			throws Exception {
		byte[] body = requestBody(idJson, method);
		Socket socket = new Socket();
		try {
			socket.connect(new InetSocketAddress(LOOPBACK, port), 3_000);
			String requestHead = "POST /mcp HTTP/1.1\r\n"
					+ "Host: " + LOOPBACK + ":" + port + "\r\n"
					+ "Content-Type: " + JSON_MEDIA_TYPE + "; charset=UTF-8\r\n"
					+ "Accept: " + JSON_MEDIA_TYPE + ", text/event-stream\r\n"
					+ "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
					+ "Mcp-Method: " + method + "\r\n"
					+ "Content-Length: " + body.length + "\r\n"
					+ "Connection: close\r\n\r\n";
			socket.getOutputStream().write(
					requestHead.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().write(body);
			socket.getOutputStream().flush();
			return socket;
		} catch (Throwable throwable) {
			try {
				socket.close();
			} catch (Throwable suppressed) {
				throwable.addSuppressed(suppressed);
			}
			throw throwable;
		}
	}

	private static byte[] requestBody(String idJson) {
		return requestBody(idJson, APPLICATION_METHOD);
	}

	private static byte[] requestBody(String idJson, String method) {
		return ("{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}")
				.getBytes(StandardCharsets.UTF_8);
	}

	private static void assertSuccessfulResult(RawResponse response, String idJson,
			String expectedValue) {
		Assertions.assertEquals(200, response.status(), response.bodyText());
		Assertions.assertEquals(JSON_MEDIA_TYPE, response.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", response.singleHeader("Cache-Control"));
		Assertions.assertTrue(response.bodyText().contains("\"id\":" + idJson),
				response.bodyText());
		Assertions.assertTrue(response.bodyText().contains(
				"\"value\":\"" + expectedValue + "\""), response.bodyText());
		Assertions.assertTrue(response.bodyText().contains("\"resultType\":\"complete\""),
				response.bodyText());
	}

	private static void assertExactCapacityResponse(RawResponse response, String idJson) {
		Assertions.assertEquals(503, response.status(), response.bodyText());
		Assertions.assertEquals(JSON_MEDIA_TYPE, response.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", response.singleHeader("Cache-Control"));
		Assertions.assertFalse(response.hasHeader("Retry-After"),
				"Retry-After is not part of the fixed capacity response.");
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}",
				response.bodyText());
		Assertions.assertFalse(response.bodyText().contains("\"data\""), response.bodyText());
	}

	private static McpApplicationExecutionSnapshot awaitSnapshot(
			McpHttpServerRuntime runtime,
			Predicate<McpApplicationExecutionSnapshot> condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpApplicationExecutionSnapshot latest = null;
		do {
			latest = runtime.applicationExecutionSnapshot().orElseThrow();
			if (condition.test(latest))
				return latest;
			Thread.sleep(5);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for application execution state; latest="
				+ latest);
	}

	private static void shutdown(ExecutorService executor) throws InterruptedException {
		executor.shutdownNow();
		Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
				"Client executor did not terminate.");
	}

	private static final class ControllableClock implements McpApplicationClock {
		private final AtomicLong nanoseconds = new AtomicLong();

		@Override
		public long nanoTime() {
			return nanoseconds.get();
		}

		private void advance(Duration duration) {
			nanoseconds.addAndGet(duration.toNanos());
		}
	}

	private record RawResponse(int status, Map<String, List<String>> headers, byte[] body) {
		private static RawResponse parse(byte[] bytes) {
			byte[] delimiter = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
			int boundary = indexOf(bytes, delimiter);
			if (boundary < 0)
				throw new AssertionError("Response did not contain a complete HTTP head.");

			String head = new String(bytes, 0, boundary, StandardCharsets.ISO_8859_1);
			String[] lines = head.split("\r\n");
			String[] statusParts = lines[0].split(" ", 3);
			Map<String, List<String>> headers = new LinkedHashMap<>();
			for (int index = 1; index < lines.length; index++) {
				int colon = lines[index].indexOf(':');
				String name = lines[index].substring(0, colon).toLowerCase(Locale.ROOT);
				String value = lines[index].substring(colon + 1).trim();
				headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
			}
			return new RawResponse(Integer.parseInt(statusParts[1]), Map.copyOf(headers),
					Arrays.copyOfRange(bytes, boundary + delimiter.length, bytes.length));
		}

		private String bodyText() {
			return new String(body, StandardCharsets.UTF_8);
		}

		private String singleHeader(String name) {
			List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
			if (values == null || values.size() != 1)
				throw new AssertionError("Expected exactly one " + name
						+ " header, found " + values);
			return values.get(0);
		}

		private boolean hasHeader(String name) {
			return headers.containsKey(name.toLowerCase(Locale.ROOT));
		}

		private static int indexOf(byte[] bytes, byte[] target) {
			outer:
			for (int offset = 0; offset <= bytes.length - target.length; offset++) {
				for (int index = 0; index < target.length; index++) {
					if (bytes[offset + index] != target[index])
						continue outer;
				}
				return offset;
			}
			return -1;
		}
	}
}
