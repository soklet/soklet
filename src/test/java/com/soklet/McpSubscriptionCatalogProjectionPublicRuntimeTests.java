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

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Public off-network coverage for caller-visible subscription catalog
 * projections.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpSubscriptionCatalogProjectionPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String SUBSCRIPTION_ID_KEY =
			"io.modelcontextprotocol/subscriptionId";
	private static final String STABLE_TOOL = "catalog.stable";
	private static final String CONDITIONAL_TOOL = "catalog.conditional";
	private static final URI RESOURCE_URI =
			URI.create("test://catalog-projection/resource");
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final Duration NO_ITEM_WAIT = Duration.ofMillis(100);

	@Test
	@Timeout(75)
	public void initialBaselinePrecedesAcknowledgmentAndProjectionFailureRetainsStream() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		CatalogState catalog = new CatalogState(true);
		CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
		SimulatorConfig config = simulatorConfig(publisher, catalog, metrics);

		SokletSimulator.run(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("catalog-baseline"));
			try {
				catalog.awaitInitialProjection();
				Assertions.assertTrue(pollResponse(simulation, NO_ITEM_WAIT).isEmpty(),
						"The subscription acknowledged before its initial catalog baseline completed.");
				catalog.releaseInitialProjection();

				assertSseResponse(awaitResponse(simulation));
				assertAcknowledgment(nextItem(simulation), "catalog-baseline");
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				Assertions.assertTrue(pollItem(simulation, NO_ITEM_WAIT).isEmpty(),
						"An unchanged caller-visible catalog emitted a notification.");

				catalog.setConditionalVisible(true);
				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				assertNotification(nextItem(simulation),
						"notifications/tools/list_changed", "catalog-baseline");

				catalog.setConditionalVisible(false);
				catalog.failNextProjection();
				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED);
				Assertions.assertFalse(simulation.isComplete(),
						"A catalog projection failure closed an authorized subscription.");
				Assertions.assertTrue(pollItem(simulation, NO_ITEM_WAIT).isEmpty(),
						"A failed projection emitted a catalog notification.");

				publisher.publishResourcesListChanged();
				assertNotification(nextItem(simulation),
						"notifications/resources/list_changed", "catalog-baseline");

				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				assertNotification(nextItem(simulation),
						"notifications/tools/list_changed", "catalog-baseline");

				simulation.close();
				Assertions.assertEquals(
						McpStreamTerminationReason.CLIENT_DISCONNECTED,
						awaitCompletion(simulation).getReason());
			} finally {
				catalog.releaseInitialProjection();
				simulation.close();
			}
		});
	}

	@Test
	public void reconciliationDuringInitialProjectionRetriesAfterInterruptedCallback() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		PreAcknowledgmentReconciliationRace race =
				new PreAcknowledgmentReconciliationRace();
		AtomicInteger authorizations = new AtomicInteger();
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			authorizations.incrementAndGet();
			return McpSubscriptionAuthorization.Allowed.fromValidUntil(
					Instant.now().plus(Duration.ofMinutes(5)));
		};
		McpCatalogAccessPolicy accessPolicy = McpCatalogAccessPolicy.fromEvaluators(
				(request, registration, features) -> race.isToolVisible(
						registration.getName(), features.getCancelationToken()),
				(request, registration, features) -> true);
		CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
		SimulatorConfig config = simulatorConfig(publisher, accessPolicy,
				authorizer, metrics);

		SokletSimulator.run(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("catalog-pre-ack-reconciliation"));
			try {
				race.awaitInitialProjection();
				metrics.awaitMaintenance(
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				Assertions.assertTrue(pollResponse(simulation, NO_ITEM_WAIT).isEmpty(),
						"The subscription acknowledged before its initial catalog projection completed.");

				simulator.getMcpServer().orElseThrow()
						.getSubscriptionReconciler().reconcileSubscriptions();
				race.awaitCancellationHook();
				race.awaitCanceledProjectionReturn();
				race.assertInitialProjectionCanceledAndInterrupted();
				race.assertNoFreshProjectionWhileCancellationHookBlocked();
				race.releaseCancellationHook();
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome
						.STALE_RESULT_DISCARDED);

				// The establishment loop remains on its original protocol worker. A
				// leaked interrupt from the canceled callback would make its next
				// bounded authorization wait fail instead of reaching this retry.
				metrics.awaitMaintenance(
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				race.awaitFreshProjection();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				assertSseResponse(awaitResponse(simulation));
				assertAcknowledgment(nextItem(simulation),
						"catalog-pre-ack-reconciliation");
				Assertions.assertEquals(2, authorizations.get(),
						"Initial establishment did not perform fresh authorization after reconciliation.");
				Assertions.assertEquals(2, race.getProjectionAttempts(),
						"Initial establishment did not perform one fresh catalog projection.");
				Assertions.assertFalse(simulation.isComplete(),
						"The retried subscription ended instead of acknowledging.");

				simulation.close();
				Assertions.assertEquals(
						McpStreamTerminationReason.CLIENT_DISCONNECTED,
						awaitCompletion(simulation).getReason());
			} finally {
				race.forceReleaseCancellationHook();
				race.releaseInitialProjection();
				simulation.close();
			}
		});
	}

	@Test
	public void successfulReconciliationReprojectsWithReplacementApplicationContext() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		Object hiddenContext = new Object();
		Object visibleContext = new Object();
		AtomicInteger authorizations = new AtomicInteger();
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int invocation = authorizations.incrementAndGet();
			if (invocation == 1)
				return allowed(hiddenContext);
			if (invocation == 2)
				return allowed(visibleContext);
			throw new AssertionError(
					"Unexpected subscription authorization invocation " + invocation);
		};
		McpCatalogAccessPolicy accessPolicy = McpCatalogAccessPolicy.fromEvaluators(
				(request, registration, features) -> STABLE_TOOL.equals(
						registration.getName())
						|| (CONDITIONAL_TOOL.equals(registration.getName())
								&& request.getAdmissionIdentity()
										.getApplicationContext()
										.filter(context -> context == visibleContext)
										.isPresent()),
				(request, registration, features) -> true);
		CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
		SimulatorConfig config = simulatorConfig(publisher, accessPolicy,
				authorizer, metrics);

		SokletSimulator.run(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("catalog-reconciliation"));
			try {
				assertSseResponse(awaitResponse(simulation));
				assertAcknowledgment(nextItem(simulation),
						"catalog-reconciliation");
				metrics.awaitMaintenance(
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				simulator.getMcpServer().orElseThrow()
						.getSubscriptionReconciler().reconcileSubscriptions();
				metrics.awaitMaintenance(
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				metrics.awaitMaintenance(
						McpMetricsEvent.SubscriptionMaintenance.Work.RECONCILIATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				assertNotification(nextItem(simulation),
						"notifications/tools/list_changed",
						"catalog-reconciliation");
				Assertions.assertEquals(2, authorizations.get());

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
	public void reconciliationCancelsAndFencesAnInFlightCatalogProjection() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		CatalogState catalog = new CatalogState(false);
		CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
		SimulatorConfig config = simulatorConfig(publisher, catalog, metrics);

		SokletSimulator.run(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("catalog-reconciliation-fence"));
			try {
				assertSseResponse(awaitResponse(simulation));
				assertAcknowledgment(nextItem(simulation),
						"catalog-reconciliation-fence");
				metrics.awaitMaintenance(
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				catalog.setConditionalVisible(true);
				catalog.blockCancellationHook();
				catalog.blockNextConditionalProjection();
				publisher.publishToolsListChanged();
				catalog.awaitBlockedProjection();

				simulator.getMcpServer().orElseThrow()
						.getSubscriptionReconciler().reconcileSubscriptions();
				catalog.assertBlockedProjectionCanceled();
				catalog.awaitCancellationHook();
				metrics.awaitMaintenance(
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				metrics.awaitMaintenance(
						McpMetricsEvent.SubscriptionMaintenance.Work.RECONCILIATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				catalog.setConditionalVisible(false);
				catalog.awaitBlockedProjectionReturn();
				catalog.assertNoFreshProjectionWhileCancellationHookBlocked();
				catalog.releaseCancellationHook();
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome
						.STALE_RESULT_DISCARDED);
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				catalog.awaitFreshProjection();
				Assertions.assertTrue(pollItem(simulation, NO_ITEM_WAIT).isEmpty(),
						"A projection fenced by reconciliation emitted stale catalog work.");
				Assertions.assertFalse(simulation.isComplete());

				simulation.close();
				Assertions.assertEquals(
						McpStreamTerminationReason.CLIENT_DISCONNECTED,
						awaitCompletion(simulation).getReason());
			} finally {
				catalog.forceReleaseCancellationHook();
				catalog.releaseBlockedProjection();
				simulation.close();
			}
		});
	}

	@Test
	@Timeout(70)
	public void timedOutCallbackRetainsProjectionOwnershipUntilPhysicalExit() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		TimeoutResistantCatalogState catalog = new TimeoutResistantCatalogState();
		McpCatalogAccessPolicy accessPolicy = McpCatalogAccessPolicy.fromEvaluators(
				(request, registration, features) -> catalog.isToolVisible(
						registration.getName(), features.getCancelationToken()),
				(request, registration, features) -> true);
		McpSubscriptionAuthorizer authorizer = (context, features) ->
				McpSubscriptionAuthorization.Allowed.fromValidUntil(
						Instant.now().plus(Duration.ofMinutes(5)));
		CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
		SimulatorConfig config = simulatorConfig(publisher, accessPolicy,
				authorizer, metrics, Duration.ofSeconds(1));

		SokletSimulator.run(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("catalog-timeout-physical-exit"));
			try {
				assertSseResponse(awaitResponse(simulation));
				assertAcknowledgment(nextItem(simulation),
						"catalog-timeout-physical-exit");
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				int initialInvocations = catalog.invocations();
				catalog.setConditionalVisible(true);
				catalog.blockNextProjection();
				publisher.publishToolsListChanged();
				catalog.awaitBlockedProjection();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT);
				catalog.awaitCancellationHook();
				catalog.awaitInterrupt();

				publisher.publishToolsListChanged();
				publisher.publishToolsListChanged();
				publisher.publishToolsListChanged();
				for (int index = 0; index < 3; index++)
					metrics.awaitOutcome(
							McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED);
				try {
					Thread.sleep(100L);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
				Assertions.assertEquals(initialInvocations + 1,
						catalog.invocations(),
						"A timed-out callback admitted overlapping catalog work.");
				Assertions.assertEquals(1, catalog.maximumConcurrentInvocations());

				catalog.releaseBlockedProjection();
				catalog.awaitBlockedProjectionReturn();
				try {
					Thread.sleep(100L);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
				Assertions.assertEquals(initialInvocations + 1,
						catalog.invocations(),
						"A blocking cancellation hook released projection ownership.");
				catalog.releaseCancellationHook();
				catalog.awaitFreshProjection();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				assertNotification(nextItem(simulation),
						"notifications/tools/list_changed",
						"catalog-timeout-physical-exit");
				Assertions.assertEquals(initialInvocations + 2,
						catalog.invocations(),
						"Coalesced invalidations must produce one fresh projection.");
				Assertions.assertEquals(1, catalog.maximumConcurrentInvocations());

				simulation.close();
				Assertions.assertEquals(
						McpStreamTerminationReason.CLIENT_DISCONNECTED,
						awaitCompletion(simulation).getReason());
			} finally {
				catalog.forceReleaseCancellationHook();
				catalog.releaseBlockedProjection();
				simulation.close();
			}
		});
	}

	@Test
	public void streamCloseCancelsInFlightProjectionWithoutLateDelivery() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		CatalogState catalog = new CatalogState(false);
		CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
		SimulatorConfig config = simulatorConfig(publisher, catalog, metrics);

		SokletSimulator.run(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("catalog-close-fence"));
			try {
				assertSseResponse(awaitResponse(simulation));
				assertAcknowledgment(nextItem(simulation), "catalog-close-fence");
				metrics.awaitMaintenance(
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				catalog.setConditionalVisible(true);
				catalog.blockNextConditionalProjection();
				publisher.publishToolsListChanged();
				catalog.awaitBlockedProjection();
				simulation.close();
				Assertions.assertEquals(
						McpStreamTerminationReason.CLIENT_DISCONNECTED,
						awaitCompletion(simulation).getReason());
				catalog.assertBlockedProjectionCanceled();
				catalog.releaseBlockedProjection();
				catalog.awaitBlockedProjectionReturn();

				Assertions.assertTrue(pollItem(simulation, Duration.ZERO).isEmpty(),
						"A catalog projection delivered after stream closure.");
				Assertions.assertTrue(metrics.pollCatalog(NO_ITEM_WAIT).isEmpty(),
						"A terminally fenced catalog projection recorded a late result.");
			} finally {
				catalog.releaseBlockedProjection();
				simulation.close();
			}
		});
	}

	@Test
	@Timeout(70)
	public void coalescedTransportOfferDoesNotAdvanceCatalogBaseline() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		CatalogState catalog = new CatalogState(false);
		CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
		SimulatorConfig config = simulatorConfig(publisher, catalog, metrics);

		SokletSimulator.run(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("catalog-coalescing"));
			try {
				assertSseResponse(awaitResponse(simulation));
				assertAcknowledgment(nextItem(simulation), "catalog-coalescing");
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				catalog.setConditionalVisible(true);
				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				// Leave the accepted notification in the simulator capture queue so its
				// coalescing key remains pending while the next projection is offered.
				catalog.setConditionalVisible(false);
				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED);
				assertNotification(nextItem(simulation),
						"notifications/tools/list_changed", "catalog-coalescing");
				Assertions.assertTrue(pollItem(simulation, Duration.ZERO).isEmpty(),
						"A coalesced offer retained a duplicate wire notification.");

				// The second projection saw the original catalog. Because COALESCED may
				// not commit that digest, the same invalidation must detect it again now
				// that the prior wire notification has left the capture queue.
				publisher.publishToolsListChanged();
				metrics.awaitOutcome(
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				assertNotification(nextItem(simulation),
						"notifications/tools/list_changed", "catalog-coalescing");

				simulation.close();
				Assertions.assertEquals(
						McpStreamTerminationReason.CLIENT_DISCONNECTED,
						awaitCompletion(simulation).getReason());
			} finally {
				simulation.close();
			}
		});
	}

	@NonNull
	private static SimulatorConfig simulatorConfig(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull CatalogState catalog,
			@NonNull MetricsCollector metrics) {
		McpCatalogAccessPolicy accessPolicy = McpCatalogAccessPolicy.fromEvaluators(
				(request, registration, features) -> catalog.isToolVisible(
						registration.getName(), features.getCancelationToken()),
				(request, registration, features) -> true);
		McpSubscriptionAuthorizer authorizer = (context, features) ->
				McpSubscriptionAuthorization.Allowed.fromValidUntil(
						Instant.now().plus(Duration.ofMinutes(5)));
		return simulatorConfig(publisher, accessPolicy, authorizer, metrics);
	}

	@NonNull
	private static SimulatorConfig simulatorConfig(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull McpCatalogAccessPolicy accessPolicy,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@NonNull MetricsCollector metrics) {
		return simulatorConfig(publisher, accessPolicy, authorizer, metrics,
				Duration.ofSeconds(5));
	}

	@NonNull
	private static SimulatorConfig simulatorConfig(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull McpCatalogAccessPolicy accessPolicy,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@NonNull MetricsCollector metrics,
			@NonNull Duration projectionTimeout) {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(publisher, EnumSet.of(
						McpSubscriptionNotificationType.TOOLS_LIST_CHANGED,
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
					McpImplementation.withNameAndVersion(
							"catalog-projection-runtime-test", "4.0.0")
							.build())
				.serverInfoIncluded(false)
				.addTool(tool(STABLE_TOOL))
				.addTool(tool(CONDITIONAL_TOOL))
				.addResource(McpResourceRegistration.withUriAndName(
						RESOURCE_URI, "Projection resource")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.withContent(
												McpTextResourceContents.withUriAndText(
														resource.getUri(), "unused")
														.build())
												.build()))
						.build())
				.subscriptionConfig(subscriptions)
				.build();
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
						.subscriptionAuthorizer(authorizer)
						.subscriptionCatalogProjectionTimeout(
								projectionTimeout)
						.catalogAccessPolicy(accessPolicy)
						.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
						.allowedHosts(Set.of(LOOPBACK)))
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
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
	private static McpSubscriptionAuthorization allowed(
			@NonNull Object applicationContext) {
		return McpSubscriptionAuthorization.Allowed
				.withValidUntil(Instant.now().plus(Duration.ofMinutes(5)))
				.applicationContext(applicationContext)
				.build();
	}

	@NonNull
	private static Request subscriptionRequest(@NonNull String id) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"toolsListChanged\":true,"
				+ "\"resourcesListChanged\":true}}}";
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
		Assertions.assertTrue(response.getBody().isEmpty());
	}

	private static void assertAcknowledgment(
			@NonNull McpSimulationStreamItem item,
			@NonNull String subscriptionId) {
		McpJsonObject message = message(item);
		Assertions.assertEquals("notifications/subscriptions/acknowledged",
				stringMember(message, "method"));
		McpJsonObject params = objectMember(message, "params");
		assertSubscriptionId(params, subscriptionId);
		McpJsonObject notifications = objectMember(params, "notifications");
		Assertions.assertEquals(Set.of("toolsListChanged",
				"resourcesListChanged"), notifications.getMembers().keySet());
		Assertions.assertTrue(booleanMember(notifications,
				"toolsListChanged"));
		Assertions.assertTrue(booleanMember(notifications,
				"resourcesListChanged"));
	}

	private static void assertNotification(
			@NonNull McpSimulationStreamItem item, @NonNull String method,
			@NonNull String subscriptionId) {
		McpJsonObject message = message(item);
		Assertions.assertEquals(method, stringMember(message, "method"));
		assertSubscriptionId(objectMember(message, "params"), subscriptionId);
	}

	private static void assertSubscriptionId(@NonNull McpJsonObject params,
			@NonNull String subscriptionId) {
		Assertions.assertEquals(subscriptionId,
				stringMember(objectMember(params, "_meta"),
						SUBSCRIPTION_ID_KEY));
	}

	@NonNull
	private static McpJsonObject message(
			@NonNull McpSimulationStreamItem item) {
		Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE,
				item.getType());
		return Assertions.assertInstanceOf(McpJsonObject.class,
				item.getMessage().orElseThrow());
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
		return Assertions.assertInstanceOf(McpJsonString.class,
				object.find(name).orElseThrow()).getValue();
	}

	private static boolean booleanMember(@NonNull McpJsonObject object,
			@NonNull String name) {
		return Assertions.assertInstanceOf(McpJsonBoolean.class,
				object.find(name).orElseThrow()).getValue();
	}

	@NonNull
	private static McpSimulationResponse awaitResponse(
			@NonNull McpSimulation simulation) {
		return pollResponse(simulation, WAIT).orElseThrow(() ->
				new AssertionError("Timed out waiting for an MCP response."));
	}

	@NonNull
	private static Optional<@NonNull McpSimulationResponse> pollResponse(
			@NonNull McpSimulation simulation, @NonNull Duration timeout) {
		try {
			return simulation.awaitResponse(timeout);
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

	private static final class PreAcknowledgmentReconciliationRace {
		@NonNull
		private final AtomicInteger projectionAttempts;
		@NonNull
		private final CountDownLatch initialProjectionEntered;
		@NonNull
		private final CountDownLatch initialProjectionCanceled;
		@NonNull
		private final CountDownLatch initialProjectionRelease;
		@NonNull
		private final CountDownLatch initialProjectionReturned;
		@NonNull
		private final CountDownLatch freshProjectionEntered;
		@NonNull
		private final CountDownLatch cancellationHookEntered;
		@NonNull
		private final CountDownLatch cancellationHookRelease;
		@NonNull
		private final CountDownLatch cancellationHookExited;
		@NonNull
		private final AtomicReference<CancelationToken> initialProjectionToken;
		@NonNull
		private final AtomicBoolean interruptedExceptionThrown;

		private PreAcknowledgmentReconciliationRace() {
			this.projectionAttempts = new AtomicInteger();
			this.initialProjectionEntered = new CountDownLatch(1);
			this.initialProjectionCanceled = new CountDownLatch(1);
			this.initialProjectionRelease = new CountDownLatch(1);
			this.initialProjectionReturned = new CountDownLatch(1);
			this.freshProjectionEntered = new CountDownLatch(1);
			this.cancellationHookEntered = new CountDownLatch(1);
			this.cancellationHookRelease = new CountDownLatch(1);
			this.cancellationHookExited = new CountDownLatch(1);
			this.initialProjectionToken = new AtomicReference<>();
			this.interruptedExceptionThrown = new AtomicBoolean();
		}

		private boolean isToolVisible(@NonNull String toolName,
				@NonNull CancelationToken cancelationToken)
				throws InterruptedException {
			if (!STABLE_TOOL.equals(toolName))
				return true;

			int attempt = this.projectionAttempts.incrementAndGet();
			if (attempt == 1) {
				CancelationToken token = requireNonNull(cancelationToken);
				this.initialProjectionToken.set(token);
				token.onCancel(() -> {
					this.initialProjectionCanceled.countDown();
					this.cancellationHookEntered.countDown();
					try {
						await(this.cancellationHookRelease,
								"Timed out releasing the initial cancellation hook.");
					} finally {
						this.cancellationHookExited.countDown();
					}
				});
				this.initialProjectionEntered.countDown();
				try {
					this.initialProjectionRelease.await();
					this.interruptedExceptionThrown.set(true);
					throw new InterruptedException(
							"initial catalog projection reconciled");
				} catch (InterruptedException exception) {
					this.interruptedExceptionThrown.set(true);
					throw exception;
				} finally {
					this.initialProjectionReturned.countDown();
				}
			}
			if (attempt == 2)
				this.freshProjectionEntered.countDown();
			return true;
		}

		private void awaitInitialProjection() {
			await(this.initialProjectionEntered,
					"The initial catalog projection did not start.");
		}

		private void awaitCanceledProjectionReturn() {
			await(this.initialProjectionCanceled,
					"Reconciliation did not cancel the initial catalog projection.");
			this.initialProjectionRelease.countDown();
			await(this.initialProjectionReturned,
					"The canceled initial catalog projection did not return.");
		}

		private void assertInitialProjectionCanceledAndInterrupted() {
			CancelationToken token = this.initialProjectionToken.get();
			Assertions.assertNotNull(token,
					"The initial catalog projection did not expose its token.");
			Assertions.assertTrue(token.isCanceled(),
					"The initial catalog projection token was not canceled.");
			Assertions.assertTrue(this.interruptedExceptionThrown.get(),
					"The canceled catalog evaluator did not throw InterruptedException.");
		}

		private void awaitCancellationHook() {
			await(this.cancellationHookEntered,
					"The initial catalog cancellation hook did not enter.");
		}

		private void assertNoFreshProjectionWhileCancellationHookBlocked() {
			try {
				Assertions.assertFalse(this.freshProjectionEntered.await(
						NO_ITEM_WAIT.toNanos(), TimeUnit.NANOSECONDS),
						"Initial catalog projection retried before its canceled callback chain physically exited.");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
			Assertions.assertEquals(1, this.projectionAttempts.get());
		}

		private void releaseCancellationHook() {
			this.cancellationHookRelease.countDown();
			await(this.cancellationHookExited,
					"The initial catalog cancellation hook did not exit.");
		}

		private void forceReleaseCancellationHook() {
			this.cancellationHookRelease.countDown();
		}

		private void awaitFreshProjection() {
			await(this.freshProjectionEntered,
					"A fresh initial catalog projection did not run.");
		}

		private int getProjectionAttempts() {
			return this.projectionAttempts.get();
		}

		private void releaseInitialProjection() {
			this.initialProjectionRelease.countDown();
		}
	}

	private static final class CatalogState {
		@NonNull
		private final AtomicBoolean initialBlockArmed;
		@NonNull
		private final CountDownLatch initialProjectionEntered;
		@NonNull
		private final CountDownLatch initialProjectionRelease;
		@NonNull
		private final AtomicBoolean conditionalVisible;
		@NonNull
		private final AtomicBoolean failNextProjection;
		@NonNull
		private final AtomicBoolean blockNextConditionalProjection;
		@NonNull
		private final CountDownLatch blockedProjectionEntered;
		@NonNull
		private final CountDownLatch blockedProjectionRelease;
		@NonNull
		private final CountDownLatch blockedProjectionReturned;
		@NonNull
		private final CountDownLatch blockedProjectionCanceled;
		@NonNull
		private final AtomicBoolean blockCancellationHook;
		@NonNull
		private final CountDownLatch cancellationHookEntered;
		@NonNull
		private final CountDownLatch cancellationHookRelease;
		@NonNull
		private final CountDownLatch cancellationHookExited;
		@NonNull
		private final AtomicInteger conditionalProjectionInvocations;
		@NonNull
		private final CountDownLatch freshProjectionEntered;
		@NonNull
		private final AtomicReference<CancelationToken>
				blockedProjectionToken;

		private CatalogState(boolean blockInitialProjection) {
			this.initialBlockArmed = new AtomicBoolean(blockInitialProjection);
			this.initialProjectionEntered = new CountDownLatch(
					blockInitialProjection ? 1 : 0);
			this.initialProjectionRelease = new CountDownLatch(
					blockInitialProjection ? 1 : 0);
			this.conditionalVisible = new AtomicBoolean();
			this.failNextProjection = new AtomicBoolean();
			this.blockNextConditionalProjection = new AtomicBoolean();
			this.blockedProjectionEntered = new CountDownLatch(1);
			this.blockedProjectionRelease = new CountDownLatch(1);
			this.blockedProjectionReturned = new CountDownLatch(1);
			this.blockedProjectionCanceled = new CountDownLatch(1);
			this.blockCancellationHook = new AtomicBoolean();
			this.cancellationHookEntered = new CountDownLatch(1);
			this.cancellationHookRelease = new CountDownLatch(1);
			this.cancellationHookExited = new CountDownLatch(1);
			this.conditionalProjectionInvocations = new AtomicInteger();
			this.freshProjectionEntered = new CountDownLatch(1);
			this.blockedProjectionToken = new AtomicReference<>();
		}

		private boolean isToolVisible(@NonNull String toolName,
				@NonNull CancelationToken cancelationToken) {
			if (this.initialBlockArmed.compareAndSet(true, false)) {
				this.initialProjectionEntered.countDown();
				await(this.initialProjectionRelease,
						"Timed out releasing the initial catalog projection.");
			}
			if (this.failNextProjection.compareAndSet(true, false))
				throw new IllegalStateException(
						"private catalog projection failure");
			if (CONDITIONAL_TOOL.equals(toolName)
					&& this.conditionalProjectionInvocations.incrementAndGet() >= 3)
				this.freshProjectionEntered.countDown();
			boolean visible = STABLE_TOOL.equals(toolName)
					|| (CONDITIONAL_TOOL.equals(toolName)
							&& this.conditionalVisible.get());
			if (CONDITIONAL_TOOL.equals(toolName)
					&& this.blockNextConditionalProjection.compareAndSet(
							true, false)) {
				CancelationToken token = requireNonNull(cancelationToken);
				this.blockedProjectionToken.set(token);
				token.onCancel(() -> {
					this.blockedProjectionCanceled.countDown();
					if (!this.blockCancellationHook.compareAndSet(true, false))
						return;
					this.cancellationHookEntered.countDown();
					try {
						await(this.cancellationHookRelease,
								"Timed out releasing the catalog cancellation hook.");
					} finally {
						this.cancellationHookExited.countDown();
					}
				});
				this.blockedProjectionEntered.countDown();
				try {
					await(this.blockedProjectionRelease,
							"Timed out releasing the blocked catalog projection.");
				} finally {
					this.blockedProjectionReturned.countDown();
				}
			}
			return visible;
		}

		private void awaitInitialProjection() {
			await(this.initialProjectionEntered,
					"The initial catalog projection did not start.");
		}

		private void releaseInitialProjection() {
			this.initialProjectionRelease.countDown();
		}

		private void setConditionalVisible(boolean visible) {
			this.conditionalVisible.set(visible);
		}

		private void failNextProjection() {
			this.failNextProjection.set(true);
		}

		private void blockNextConditionalProjection() {
			Assertions.assertTrue(this.blockNextConditionalProjection.compareAndSet(
					false, true), "A catalog projection block is already armed.");
		}

		private void blockCancellationHook() {
			Assertions.assertTrue(this.blockCancellationHook.compareAndSet(false, true),
					"A catalog cancellation-hook block is already armed.");
		}

		private void awaitBlockedProjection() {
			await(this.blockedProjectionEntered,
					"The catalog projection did not reach its blocking evaluator.");
		}

		private void assertBlockedProjectionCanceled() {
			Assertions.assertNotNull(this.blockedProjectionToken.get(),
					"The blocked catalog projection did not expose its token.");
			await(this.blockedProjectionCanceled,
					"The blocked catalog projection was not canceled.");
			Assertions.assertTrue(this.blockedProjectionToken.get().isCanceled());
		}

		private void awaitCancellationHook() {
			await(this.cancellationHookEntered,
					"The catalog cancellation hook did not enter.");
		}

		private void assertNoFreshProjectionWhileCancellationHookBlocked() {
			try {
				Assertions.assertFalse(this.freshProjectionEntered.await(
						NO_ITEM_WAIT.toNanos(), TimeUnit.NANOSECONDS),
						"Catalog projection replacement overlapped its canceled callback chain.");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
			Assertions.assertEquals(2,
					this.conditionalProjectionInvocations.get());
		}

		private void releaseCancellationHook() {
			this.cancellationHookRelease.countDown();
			await(this.cancellationHookExited,
					"The catalog cancellation hook did not exit.");
		}

		private void forceReleaseCancellationHook() {
			this.cancellationHookRelease.countDown();
		}

		private void awaitFreshProjection() {
			await(this.freshProjectionEntered,
					"Fresh catalog projection did not follow physical cancellation exit.");
		}

		private void awaitBlockedProjectionReturn() {
			await(this.blockedProjectionReturned,
					"The blocked catalog projection did not return.");
		}

		private void releaseBlockedProjection() {
			this.blockedProjectionRelease.countDown();
		}
	}

	private static final class TimeoutResistantCatalogState {
		@NonNull
		private final AtomicBoolean conditionalVisible = new AtomicBoolean();
		@NonNull
		private final AtomicBoolean blockNext = new AtomicBoolean();
		@NonNull
		private final AtomicBoolean blockedReturned = new AtomicBoolean();
		@NonNull
		private final CountDownLatch blockedEntered = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch blockedRelease = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch blockedReturnObserved = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch interruptObserved = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch cancellationHookEntered = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch cancellationHookRelease = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch cancellationHookExited = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch freshEntered = new CountDownLatch(1);
		@NonNull
		private final AtomicInteger invocationCount = new AtomicInteger();
		@NonNull
		private final AtomicInteger activeInvocations = new AtomicInteger();
		@NonNull
		private final AtomicInteger maximumConcurrentInvocations =
				new AtomicInteger();

		private boolean isToolVisible(@NonNull String toolName,
				@NonNull CancelationToken cancelationToken) {
			if (STABLE_TOOL.equals(toolName))
				return true;
			int active = this.activeInvocations.incrementAndGet();
			this.maximumConcurrentInvocations.accumulateAndGet(active, Math::max);
			this.invocationCount.incrementAndGet();
			try {
				if (this.blockNext.compareAndSet(true, false)) {
					cancelationToken.onCancel(() -> {
						this.cancellationHookEntered.countDown();
						try {
							await(this.cancellationHookRelease,
									"Timed out releasing the cancellation hook.");
						} finally {
							this.cancellationHookExited.countDown();
						}
					});
					this.blockedEntered.countDown();
					while (true) {
						try {
							this.blockedRelease.await();
							break;
						} catch (InterruptedException ignored) {
							// The regression deliberately models resistant application code.
							this.interruptObserved.countDown();
						}
					}
					this.blockedReturned.set(true);
					this.blockedReturnObserved.countDown();
				} else if (this.blockedReturned.get()) {
					this.freshEntered.countDown();
				}
				return this.conditionalVisible.get();
			} finally {
				this.activeInvocations.decrementAndGet();
			}
		}

		private void setConditionalVisible(boolean visible) {
			this.conditionalVisible.set(visible);
		}

		private void blockNextProjection() {
			Assertions.assertTrue(this.blockNext.compareAndSet(false, true));
		}

		private void awaitBlockedProjection() {
			await(this.blockedEntered,
					"The timeout-resistant catalog projection did not start.");
		}

		private void releaseBlockedProjection() {
			this.blockedRelease.countDown();
		}

		private void awaitBlockedProjectionReturn() {
			await(this.blockedReturnObserved,
					"The timeout-resistant catalog projection did not return.");
		}

		private void awaitInterrupt() {
			await(this.interruptObserved,
					"The catalog deadline did not interrupt the evaluator.");
		}

		private void awaitCancellationHook() {
			await(this.cancellationHookEntered,
					"The catalog deadline did not deliver cancellation callbacks.");
		}

		private void releaseCancellationHook() {
			this.cancellationHookRelease.countDown();
			await(this.cancellationHookExited,
					"The catalog cancellation callback did not exit.");
		}

		private void forceReleaseCancellationHook() {
			this.cancellationHookRelease.countDown();
		}

		private void awaitFreshProjection() {
			await(this.freshEntered,
					"Fresh catalog work did not wait for physical callback exit.");
		}

		private int invocations() {
			return this.invocationCount.get();
		}

		private int maximumConcurrentInvocations() {
			return this.maximumConcurrentInvocations.get();
		}
	}

	private static final class CatalogProjectionMetrics
			implements MetricsCollector {
		@NonNull
		private final EnumMap<McpMetricsEvent.SubscriptionMaintenance.Work,
				BlockingQueue<McpMetricsEvent.@NonNull SubscriptionMaintenance>>
				events;

		private CatalogProjectionMetrics() {
			this.events = new EnumMap<>(
					McpMetricsEvent.SubscriptionMaintenance.Work.class);
			for (McpMetricsEvent.SubscriptionMaintenance.Work work
					: McpMetricsEvent.SubscriptionMaintenance.Work.values())
				this.events.put(work, new LinkedBlockingQueue<>());
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			if (event instanceof McpMetricsEvent.SubscriptionMaintenance maintenance)
				requireNonNull(this.events.get(maintenance.getWork()))
						.add(maintenance);
		}

		private void awaitOutcome(
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome) {
			awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.CATALOG_PROJECTION,
					outcome);
		}

		private void awaitMaintenance(
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Work work,
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome) {
			McpMetricsEvent.SubscriptionMaintenance event;
			try {
				event = requireNonNull(this.events.get(work)).poll(
						WAIT.toNanos(), TimeUnit.NANOSECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
			Assertions.assertNotNull(event,
					"Timed out waiting for a subscription-maintenance metric.");
			Assertions.assertEquals(MCP_PATH, event.getEndpointPath());
			Assertions.assertEquals(work, event.getWork());
			Assertions.assertEquals(outcome, event.getOutcome());
		}

		@NonNull
		private Optional<McpMetricsEvent.@NonNull SubscriptionMaintenance>
		pollCatalog(@NonNull Duration timeout) {
			try {
				return Optional.ofNullable(requireNonNull(this.events.get(
						McpMetricsEvent.SubscriptionMaintenance.Work
								.CATALOG_PROJECTION)).poll(
						timeout.toNanos(), TimeUnit.NANOSECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
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
}
