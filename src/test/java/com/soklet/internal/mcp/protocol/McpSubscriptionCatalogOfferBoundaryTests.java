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
import com.soklet.HttpMethod;
import com.soklet.LifecycleObserver;
import com.soklet.McpAdmissionController;
import com.soklet.McpCatalogAccessPolicy;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import com.soklet.McpMetricsEvent;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyType;
import com.soklet.McpSimulationCompletion;
import com.soklet.McpSimulationOptions;
import com.soklet.McpSimulationResponse;
import com.soklet.McpSimulationStreamItem;
import com.soklet.McpSimulationStreamItemType;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpToolRegistration;
import com.soklet.MetricsCollector;
import com.soklet.Request;
import com.soklet.SimulatorConfig;
import com.soklet.SokletSimulator;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Deterministic transport-boundary coverage for caller-visible catalog
 * projection notifications.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(60)
public class McpSubscriptionCatalogOfferBoundaryTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String STABLE_TOOL = "catalog.stable";
	private static final String CONDITIONAL_TOOL = "catalog.conditional";
	private static final String SUBSCRIPTION_ID_KEY =
			"io.modelcontextprotocol/subscriptionId";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final Duration NO_ITEM_WAIT = Duration.ofMillis(100);

	@AfterEach
	public void resetTestHooks() {
		McpRequestSseStream.setTestHooks(null);
	}

	@Test
	public void synchronousCaptureLimitClosureDuringCatalogOfferTerminatesCleanly() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		AtomicBoolean conditionalVisible = new AtomicBoolean();
		CatalogMetrics metrics = new CatalogMetrics();
		SimulatorConfig config = simulatorConfig(publisher, conditionalVisible,
				metrics, Duration.ofSeconds(5));
		int byteLimit = 300;

		SokletSimulator.run(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("catalog-capture-limit"),
					McpSimulationOptions.builder()
							.streamItemQueueCapacity(4)
							.maximumCapturedSizeInBytes(byteLimit)
							.build());
			try {
				assertSseResponse(awaitResponse(simulation));
				McpSimulationStreamItem acknowledgment = nextItem(simulation);
				assertMethod(acknowledgment,
						"notifications/subscriptions/acknowledged",
						"catalog-capture-limit");
				Assertions.assertTrue(
						acknowledgment.getEncodedBytes().length < byteLimit,
						"The acknowledgment must fit before the catalog offer exceeds the byte cap.");
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				conditionalVisible.set(true);
				publisher.publishToolsListChanged();
				McpSimulationCompletion completion = awaitCompletion(simulation);
				Assertions.assertEquals(
						McpStreamTerminationReason
								.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
						completion.getReason());
				Assertions.assertTrue(pollItem(simulation, Duration.ZERO).isEmpty(),
						"The capture-limit offender must not be retained.");
				Assertions.assertTrue(metrics.pollCatalog(NO_ITEM_WAIT).isEmpty(),
						"A synchronously terminated catalog offer must not record a late result.");
			} finally {
				simulation.close();
			}
		});
	}

	@Test
	@Timeout(70)
	public void offerCrossingProjectionDeadlineIsSuppressedUntilFreshProjection() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		AtomicBoolean conditionalVisible = new AtomicBoolean();
		CatalogMetrics metrics = new CatalogMetrics();
		Duration projectionTimeout = Duration.ofSeconds(1);
		SimulatorConfig config = simulatorConfig(publisher, conditionalVisible,
				metrics, projectionTimeout);
		AtomicBoolean delayNextCatalogOffer = new AtomicBoolean();
		AtomicInteger delayedOffers = new AtomicInteger();
		McpRequestSseStream.setTestHooks(new McpRequestSseStream.TestHooks() {
			@Override
			public void beforeTerminalReservation() {
				// This proof terminates by explicit simulation close.
			}

			@Override
			public void beforeCoalescingMessageOffer() {
				if (!delayNextCatalogOffer.compareAndSet(true, false))
					return;
				delayedOffers.incrementAndGet();
				long resumeAt = System.nanoTime()
						+ projectionTimeout.plusMillis(100).toNanos();
				long remaining;
				while ((remaining = resumeAt - System.nanoTime()) > 0L)
					LockSupport.parkNanos(remaining);
			}
		});

		SokletSimulator.run(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("catalog-deadline-commit"));
			try {
				assertSseResponse(awaitResponse(simulation));
				assertMethod(nextItem(simulation),
						"notifications/subscriptions/acknowledged",
						"catalog-deadline-commit");
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				conditionalVisible.set(true);
				delayNextCatalogOffer.set(true);
				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT);
				Assertions.assertTrue(pollItem(simulation, NO_ITEM_WAIT).isEmpty(),
						"A catalog offer crossed its absolute projection deadline.");
				Assertions.assertEquals(1, delayedOffers.get(),
						"The catalog offer did not cross its deadline seam.");

				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				assertMethod(nextItem(simulation),
						"notifications/tools/list_changed",
						"catalog-deadline-commit");

				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				Assertions.assertTrue(pollItem(simulation, NO_ITEM_WAIT).isEmpty(),
						"The fresh accepted offer failed to commit its catalog digest.");

				simulation.close();
				Assertions.assertEquals(
						McpStreamTerminationReason.CLIENT_DISCONNECTED,
						awaitCompletion(simulation).getReason());
			} finally {
				simulation.close();
			}
		});
	}

	@Test
	@Timeout(90)
	public void reconciliationCannotFenceBetweenOfferReservationAndHandoff() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		AtomicBoolean conditionalVisible = new AtomicBoolean();
		CatalogMetrics metrics = new CatalogMetrics();
		SimulatorConfig config = simulatorConfig(publisher, conditionalVisible,
				metrics, Duration.ofSeconds(5));
		AtomicBoolean blockNextOffer = new AtomicBoolean();
		CountDownLatch offerEntered = new CountDownLatch(1);
		CountDownLatch offerRelease = new CountDownLatch(1);
		McpRequestSseStream.setTestHooks(new McpRequestSseStream.TestHooks() {
			@Override
			public void beforeTerminalReservation() {
				// This proof terminates by explicit simulation close.
			}

			@Override
			public void beforeCoalescingMessageOffer() {
				if (!blockNextOffer.compareAndSet(true, false))
					return;
				offerEntered.countDown();
				await(offerRelease,
						"The catalog offer was not released by the test.");
			}
		});
		ExecutorService reconciliationExecutor =
				Executors.newSingleThreadExecutor(runnable -> {
					Thread thread = new Thread(runnable,
							"mcp-catalog-offer-reconciliation-test");
					thread.setDaemon(true);
					return thread;
				});

		try {
			SokletSimulator.run(config, simulator -> {
				McpSimulation simulation = simulator.startMcpRequest(
						subscriptionRequest("catalog-offer-reconciliation"));
				try {
					assertSseResponse(awaitResponse(simulation));
					assertMethod(nextItem(simulation),
							"notifications/subscriptions/acknowledged",
							"catalog-offer-reconciliation");
					metrics.awaitOutcome(
							McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

					conditionalVisible.set(true);
					blockNextOffer.set(true);
					publisher.publishToolsListChanged();
					await(offerEntered,
							"The catalog projection did not reach its offer boundary.");
					CountDownLatch reconciliationStarted = new CountDownLatch(1);
					Future<?> reconciliation = reconciliationExecutor.submit(() -> {
						reconciliationStarted.countDown();
						simulator.getMcpServer().orElseThrow()
								.getSubscriptionReconciler().reconcileSubscriptions();
					});
					await(reconciliationStarted,
							"The reconciliation call did not start.");
					Assertions.assertThrows(TimeoutException.class,
							() -> reconciliation.get(100, TimeUnit.MILLISECONDS),
							"Reconciliation returned before the reserved offer reached transport.");

					offerRelease.countDown();
					await(reconciliation,
							"Reconciliation did not return after the offer completed.");
					metrics.awaitOutcome(
							McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
					assertMethod(nextItem(simulation),
							"notifications/tools/list_changed",
							"catalog-offer-reconciliation");
					metrics.awaitOutcome(
							McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
					Assertions.assertTrue(pollItem(simulation, NO_ITEM_WAIT).isEmpty(),
							"Fresh reconciliation projection duplicated the committed offer.");

					simulation.close();
					Assertions.assertEquals(
							McpStreamTerminationReason.CLIENT_DISCONNECTED,
							awaitCompletion(simulation).getReason());
				} finally {
					offerRelease.countDown();
					simulation.close();
				}
			});
		} finally {
			offerRelease.countDown();
			reconciliationExecutor.shutdownNow();
			try {
				Assertions.assertTrue(reconciliationExecutor.awaitTermination(
						5, TimeUnit.SECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		}
	}

	@NonNull
	private static SimulatorConfig simulatorConfig(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull AtomicBoolean conditionalVisible,
			@NonNull MetricsCollector metrics,
			@NonNull Duration projectionTimeout) {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(publisher, EnumSet.of(
						McpSubscriptionNotificationType.TOOLS_LIST_CHANGED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
					McpImplementation.withNameAndVersion(
							"catalog-offer-boundary-test", "4.0.0")
							.build())
				.serverInfoIncluded(false)
				.addTool(tool(STABLE_TOOL))
				.addTool(tool(CONDITIONAL_TOOL))
				.subscriptionConfig(subscriptions)
				.build();
		McpCatalogAccessPolicy accessPolicy =
				McpCatalogAccessPolicy.fromEvaluators(
						(request, registration, features) -> STABLE_TOOL.equals(
								registration.getName())
								|| conditionalVisible.get(),
						(request, registration, features) -> true);
		return SimulatorConfig.builder()
				.configureMcpServer(builder -> builder
						.port(0)
						.host(LOOPBACK)
						.endpointRegistry(McpEndpointRegistry.fromEndpoints(
								List.of(endpoint)))
						.admissionController(
								McpAdmissionController.acceptAllInstance())
						.requestRateLimiter(context ->
								McpRateLimitDecision.allowed())
						.toolRateLimiter(context ->
								McpRateLimitDecision.allowed())
						.subscriptionAuthorizer((context, features) ->
								McpSubscriptionAuthorization.Allowed.fromValidUntil(
										Instant.now().plus(Duration.ofMinutes(5))))
						.subscriptionCatalogProjectionTimeout(projectionTimeout)
						.catalogAccessPolicy(accessPolicy)
						.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
						.allowedHosts(Set.of(LOOPBACK)))
				.resourceMethodResolver(
						com.soklet.ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metrics)
				.lifecycleObservers(List.of(
						LifecycleObserver.defaultInstance()))
				.build();
	}

	@NonNull
	private static McpToolRegistration<McpJsonObject> tool(
			@NonNull String name) {
		return McpToolRegistration.withName(name)
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("unused"))
				.build();
	}

	@NonNull
	private static Request subscriptionRequest(@NonNull String id) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"toolsListChanged\":true}}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type", Set.of(
								"application/json; charset=UTF-8"),
						"Accept", Set.of(
								"application/json, text/event-stream"),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of("subscriptions/listen")))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static void assertSseResponse(
			@NonNull McpSimulationResponse response) {
		Assertions.assertEquals(200, response.getStatusCode());
		Assertions.assertEquals(McpSimulationBodyType.SSE,
				response.getBodyType());
	}

	private static void assertMethod(@NonNull McpSimulationStreamItem item,
			@NonNull String method, @NonNull String subscriptionId) {
		Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE,
				item.getType());
		McpJsonObject message = Assertions.assertInstanceOf(McpJsonObject.class,
				item.getMessage().orElseThrow());
		Assertions.assertEquals(method, stringMember(message, "method"));
		McpJsonObject params = objectMember(message, "params");
		McpJsonObject metadata = objectMember(params, "_meta");
		Assertions.assertEquals(subscriptionId,
				stringMember(metadata, SUBSCRIPTION_ID_KEY));
	}

	@NonNull
	private static McpJsonObject objectMember(@NonNull McpJsonObject object,
			@NonNull String name) {
		return Assertions.assertInstanceOf(McpJsonObject.class,
				object.find(name).orElseThrow());
	}

	@NonNull
	private static String stringMember(@NonNull McpJsonObject object,
			@NonNull String name) {
		McpJsonValue value = object.find(name).orElseThrow();
		return Assertions.assertInstanceOf(McpJsonString.class, value).getValue();
	}

	@NonNull
	private static McpSimulationResponse awaitResponse(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.awaitResponse(WAIT).orElseThrow(() ->
					new AssertionError("Timed out waiting for an MCP response."));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static McpSimulationStreamItem nextItem(
			@NonNull McpSimulation simulation) {
		return pollItem(simulation, WAIT).orElseThrow(() ->
				new AssertionError("Timed out waiting for an MCP stream item."));
	}

	@NonNull
	private static Optional<@NonNull McpSimulationStreamItem> pollItem(
			@NonNull McpSimulation simulation, @NonNull Duration timeout) {
		try {
			return simulation.awaitStreamItem(timeout);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static McpSimulationCompletion awaitCompletion(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.awaitCompletion(WAIT).orElseThrow(() ->
					new AssertionError(
							"Timed out waiting for MCP simulation completion."));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static void await(@NonNull CountDownLatch latch,
			@NonNull String timeoutMessage) {
		try {
			Assertions.assertTrue(latch.await(WAIT.toNanos(),
					TimeUnit.NANOSECONDS), timeoutMessage);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static void await(@NonNull Future<?> future,
			@NonNull String timeoutMessage) {
		try {
			future.get(WAIT.toNanos(), TimeUnit.NANOSECONDS);
		} catch (TimeoutException exception) {
			throw new AssertionError(timeoutMessage, exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		} catch (java.util.concurrent.ExecutionException exception) {
			throw new AssertionError(exception.getCause());
		}
	}

	private static final class CatalogMetrics implements MetricsCollector {
		@NonNull
		private final BlockingQueue<McpMetricsEvent.@NonNull SubscriptionMaintenance>
				catalogEvents = new LinkedBlockingQueue<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			if (event instanceof McpMetricsEvent.SubscriptionMaintenance maintenance
					&& maintenance.getWork()
							== McpMetricsEvent.SubscriptionMaintenance.Work
									.CATALOG_PROJECTION)
				this.catalogEvents.add(maintenance);
		}

		private void awaitOutcome(
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome) {
			McpMetricsEvent.SubscriptionMaintenance event;
			try {
				event = this.catalogEvents.poll(WAIT.toNanos(),
						TimeUnit.NANOSECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
			Assertions.assertNotNull(event,
					"Timed out waiting for a catalog-projection metric.");
			Assertions.assertEquals(MCP_PATH, event.getEndpointPath());
			Assertions.assertEquals(outcome, event.getOutcome());
		}

		@NonNull
		private Optional<McpMetricsEvent.@NonNull SubscriptionMaintenance>
		pollCatalog(@NonNull Duration timeout) {
			try {
				return Optional.ofNullable(this.catalogEvents.poll(
						timeout.toNanos(), TimeUnit.NANOSECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		}
	}
}
