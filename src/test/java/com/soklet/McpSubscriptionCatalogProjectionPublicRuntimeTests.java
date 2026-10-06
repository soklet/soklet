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
import java.util.Locale;
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
import java.util.function.Consumer;

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
	public void providerFailureUsesCanonicalCatalogBaselineAndRecovers() {
		assertCatalogLocalizationFallback(true);
	}

	@Test
	public void lookupFailureUsesCanonicalCatalogBaselineAndRecovers() {
		assertCatalogLocalizationFallback(false);
	}

	private static void assertCatalogLocalizationFallback(boolean providerFailure) {
		for (boolean prompts : List.of(false, true)) {
			McpSubscriptionEventPublisher publisher = McpSubscriptionEventPublisher.fromInMemoryDefaults();
			CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
			AtomicBoolean failing = new AtomicBoolean(true);
			AtomicInteger authorizations = new AtomicInteger();
			McpLocalizer localizer = failingLocalizer(failing, providerFailure, McpLocalizationFailurePolicy.USE_DEFAULT_TEXT);
			SimulatorConfig config = simulatorConfig(publisher, McpCatalogAccessPolicy.allowAllInstance(),
					(context, features) -> { authorizations.incrementAndGet(); return allowed("owner"); },
					metrics, WAIT, prompts, builder -> builder.localizer(localizer).catalogAccessPolicy(null));
			SokletSimulator.run(config, simulator -> {
				String id = "catalog-fallback";
				try (McpSimulation subscription = simulator.startMcpRequest(subscriptionRequest(id, prompts))) {
					assertSseResponse(awaitResponse(subscription));
					assertAcknowledgment(nextItem(subscription), id, prompts);
					metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
					assertLocalizedCatalog(simulator, prompts, false);
					publishCatalog(publisher, prompts);
					metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
					Assertions.assertTrue(pollItem(subscription, NO_ITEM_WAIT).isEmpty(), "An unchanged fallback catalog emitted a hint.");
					failing.set(false); publishCatalog(publisher, prompts);
					metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
					assertNotification(nextItem(subscription), catalogNotification(prompts), id);
					assertLocalizedCatalog(simulator, prompts, true);
					failing.set(true); publishCatalog(publisher, prompts);
					metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
					assertNotification(nextItem(subscription), catalogNotification(prompts), id);
					assertLocalizedCatalog(simulator, prompts, false);
					Assertions.assertEquals(1, authorizations.get(), "Localization fallback or recovery must not repeat authorization.");
					publisher.publishResourcesListChanged();
					assertNotification(nextItem(subscription), "notifications/resources/list_changed", id);
				}
			});
		}
	}

	@Test
	public void failRequestCatalogBaselineIsSanitizedAndReleasesCapacity() {
		for (boolean prompts : List.of(false, true)) for (boolean providerFailure : List.of(false, true)) {
			AtomicBoolean failing = new AtomicBoolean(true);
			McpLocalizer localizer = failingLocalizer(failing, providerFailure, McpLocalizationFailurePolicy.FAIL_REQUEST);
			SimulatorConfig config = simulatorConfig(McpSubscriptionEventPublisher.fromInMemoryDefaults(),
					McpCatalogAccessPolicy.allowAllInstance(), (context, features) -> allowed("owner"),
					new CatalogProjectionMetrics(), WAIT, prompts,
					builder -> builder.localizer(localizer).catalogAccessPolicy(null).maximumSubscriptionsPerPartition(1));
			SokletSimulator.run(config, simulator -> {
				for (int attempt = 0; attempt < 3; attempt++) {
					try (McpSimulation subscription = simulator.startMcpRequest(subscriptionRequest("failed-baseline-" + attempt, prompts))) {
						McpSimulationResponse response = awaitResponse(subscription);
						Assertions.assertEquals(500, response.getStatusCode());
						String body = new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
						Assertions.assertTrue(body.contains("\"code\":-32603"), body);
						Assertions.assertFalse(body.contains("private-catalog-provider"), body);
						awaitCompletion(subscription);
						Assertions.assertEquals(0, simulator.getMcpServer().orElseThrow().getDiagnostics().getActiveSubscriptions());
					}
				}
				failing.set(false);
				try (McpSimulation subscription = simulator.startMcpRequest(subscriptionRequest("recovered-baseline", prompts))) {
					assertSseResponse(awaitResponse(subscription));
					assertAcknowledgment(nextItem(subscription), "recovered-baseline", prompts);
				}
			});
		}
	}

	private static McpLocalizer failingLocalizer(AtomicBoolean failing, boolean providerFailure,
			McpLocalizationFailurePolicy policy) {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
			if (failing.get() && providerFailure) throw new IllegalStateException("private-catalog-provider");
			return McpLocalizationContext.withLocale(Locale.FRENCH, text -> failing.get()
					? McpLocalizationResult.failure() : McpLocalizationResult.localized("FR:" + text.getDefaultText())).build();
		}).failurePolicy(policy).build();
	}

	private static void assertLocalizedCatalog(Simulator simulator, boolean prompts, boolean localized) {
		try (McpSimulation request = simulator.startMcpRequest(catalogRequest(prompts))) {
			McpSimulationResponse response = awaitResponse(request);
			Assertions.assertEquals(200, response.getStatusCode());
			String body = new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
			Assertions.assertTrue(body.contains("\"title\":\"" + (localized ? "FR:" : "") + "Canonical " + STABLE_TOOL + "\""), body);
			Assertions.assertFalse(body.contains("private-catalog-provider"), body);
			awaitCompletion(request);
		}
	}

	@Test
	public void explicitCatalogPolicyContextFailureStillFailsBeforeEvaluation() {
		for (boolean prompts : List.of(false, true)) {
			AtomicBoolean failing = new AtomicBoolean(true); AtomicInteger evaluations = new AtomicInteger();
			McpLocalizer localizer = failingLocalizer(failing, true, McpLocalizationFailurePolicy.USE_DEFAULT_TEXT);
			McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
					(requestContext, registration, features) -> { evaluations.incrementAndGet(); return STABLE_TOOL.equals(registration.getName()); },
					(requestContext, registration, features) -> { evaluations.incrementAndGet(); return STABLE_TOOL.equals(registration.getName()); });
			SimulatorConfig config = simulatorConfig(McpSubscriptionEventPublisher.fromInMemoryDefaults(), policy,
					(context, features) -> allowed("owner"), new CatalogProjectionMetrics(), WAIT, prompts,
					builder -> builder.localizer(localizer).maximumSubscriptionsPerPartition(1));
			SokletSimulator.run(config, simulator -> {
				try (McpSimulation subscription = simulator.startMcpRequest(subscriptionRequest("context-unavailable", prompts))) {
					McpSimulationResponse response = awaitResponse(subscription);
					Assertions.assertEquals(500, response.getStatusCode());
					String body = new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
					Assertions.assertTrue(body.contains("\"code\":-32603"), body);
					Assertions.assertFalse(body.contains("private-catalog-provider"), body);
					awaitCompletion(subscription);
					Assertions.assertEquals(0, evaluations.get(), "Do not run policy with an invented localization context.");
				}
				failing.set(false);
				try (McpSimulation subscription = simulator.startMcpRequest(subscriptionRequest("context-recovered", prompts))) {
					assertSseResponse(awaitResponse(subscription));
					assertAcknowledgment(nextItem(subscription), "context-recovered", prompts);
					Assertions.assertTrue(evaluations.get() > 0);
					assertCurrentCatalog(simulator, prompts, false);
				}
			});
		}
	}

	@Test
	public void localizedInvalidationDuringBaselineCatchesUpAndDropsStaleTerminal() {
		for (boolean prompts : List.of(false, true)) {
			AtomicReference<McpServer> server = new AtomicReference<>();
			AtomicReference<String> prefix = new AtomicReference<>("OLD:");
			AtomicBoolean changed = new AtomicBoolean(); AtomicInteger authorizations = new AtomicInteger();
			McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
				String snapshot = prefix.get();
				if (request.getRequestContext().getOperationType() == (prompts ? McpOperationType.PROMPTS_LIST : McpOperationType.TOOLS_LIST)
						&& changed.compareAndSet(false, true)) {
					prefix.set("NEW:"); server.get().getLocalizationCatalogInvalidator().invalidateCatalogs();
				}
				return McpLocalizationContext.withLocale(Locale.FRENCH,
						text -> McpLocalizationResult.localized(snapshot + text.getDefaultText())).build();
			}).build();
			SimulatorConfig config = simulatorConfig(McpSubscriptionEventPublisher.fromInMemoryDefaults(),
					McpCatalogAccessPolicy.allowAllInstance(),
					(context, features) -> { authorizations.incrementAndGet(); return allowed("owner"); },
					new CatalogProjectionMetrics(), WAIT, prompts, builder -> builder.localizer(localizer)
							.catalogAccessPolicy(null).maximumSubscriptionDuration(Duration.ofMillis(500)), true);
			SokletSimulator.run(config, simulator -> {
				server.set(simulator.getMcpServer().orElseThrow());
				try (McpSimulation subscription = simulator.startMcpRequest(subscriptionRequest("localized-opening-change", prompts))) {
					McpSimulationResponse response = awaitResponse(subscription); assertSseResponse(response);
					Assertions.assertEquals(List.of("fr"), response.getHeaders().get("Content-Language"));
					assertAcknowledgment(nextItem(subscription), "localized-opening-change", prompts);
					Assertions.assertEquals(1, authorizations.get());
					assertNotification(nextItem(subscription), catalogNotification(prompts), "localized-opening-change");
					Assertions.assertEquals(McpStreamTerminationReason.COMPLETED, awaitCompletion(subscription).getReason());
					String terminal = new String(nextItem(subscription).getEncodedBytes(), StandardCharsets.UTF_8);
					Assertions.assertTrue(terminal.contains("\"title\":\"Canonical server title\""), terminal);
					Assertions.assertFalse(terminal.contains("OLD:"), "Do not retain terminal text from the invalidated snapshot.");
				}
			});
		}
	}

	@Test
	public void catalogChurnDuringOpeningDoesNotRepeatAuthorization() {
		for (boolean prompts : List.of(false, true)) {
			McpSubscriptionEventPublisher publisher = McpSubscriptionEventPublisher.fromInMemoryDefaults();
			AtomicInteger projections = new AtomicInteger(); AtomicInteger authorizations = new AtomicInteger();
			CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
			java.util.function.Predicate<String> visible = name -> {
				if (CONDITIONAL_TOOL.equals(name) && projections.incrementAndGet() <= 3) publishCatalog(publisher, prompts);
				return STABLE_TOOL.equals(name);
			};
			McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
					(requestContext, registration, features) -> visible.test(registration.getName()),
					(requestContext, registration, features) -> visible.test(registration.getName()));
			SimulatorConfig config = simulatorConfig(publisher, policy,
					(context, features) -> { authorizations.incrementAndGet(); return allowed("owner"); }, metrics, WAIT, prompts);
			SokletSimulator.run(config, simulator -> {
				try (McpSimulation subscription = simulator.startMcpRequest(subscriptionRequest("catalog-churn", prompts))) {
					assertSseResponse(awaitResponse(subscription));
					assertAcknowledgment(nextItem(subscription), "catalog-churn", prompts);
					metrics.awaitSuccessfulCatalogProjections(4);
					Assertions.assertEquals(1, authorizations.get(), "Catalog invalidation must not restart the application authorizer.");
					Assertions.assertEquals(4, projections.get(), "Catalog churn must use the bounded post-ack projection queue.");
					Assertions.assertTrue(pollItem(subscription, NO_ITEM_WAIT).isEmpty(), "An unchanged view must not emit a hint.");
				}
			});
		}
	}

	@Test
	public void catalogChangeDuringBaselineCatchesUpAfterAcknowledgment() {
		for (boolean prompts : List.of(false, true)) {
			McpSubscriptionEventPublisher publisher = McpSubscriptionEventPublisher.fromInMemoryDefaults();
			AtomicBoolean changed = new AtomicBoolean(); AtomicInteger authorizations = new AtomicInteger();
			CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
			java.util.function.Predicate<String> visible = name -> {
				if (!CONDITIONAL_TOOL.equals(name)) return true;
				if (changed.compareAndSet(false, true)) { publishCatalog(publisher, prompts); return false; }
				return true;
			};
			McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
					(requestContext, registration, features) -> visible.test(registration.getName()),
					(requestContext, registration, features) -> visible.test(registration.getName()));
			SimulatorConfig config = simulatorConfig(publisher, policy,
					(context, features) -> { authorizations.incrementAndGet(); return allowed("owner"); }, metrics, WAIT, prompts);
			SokletSimulator.run(config, simulator -> {
				try (McpSimulation subscription = simulator.startMcpRequest(subscriptionRequest("catalog-opening-change", prompts))) {
					assertSseResponse(awaitResponse(subscription));
					assertAcknowledgment(nextItem(subscription), "catalog-opening-change", prompts);
					Assertions.assertEquals(1, authorizations.get(), "A catalog change must not repeat authorization.");
					assertNotification(nextItem(subscription), catalogNotification(prompts), "catalog-opening-change");
					assertCurrentCatalog(simulator, prompts, true);
					metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
					metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
					publishCatalog(publisher, prompts);
					metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
					Assertions.assertTrue(pollItem(subscription, NO_ITEM_WAIT).isEmpty());
				}
			});
		}
	}

	private static void publishCatalog(McpSubscriptionEventPublisher publisher, boolean prompts) {
		if (prompts) publisher.publishPromptsListChanged(); else publisher.publishToolsListChanged();
	}

	private static String catalogNotification(boolean prompts) {
		return prompts ? "notifications/prompts/list_changed" : "notifications/tools/list_changed";
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
	public void coalescedToolOfferAdvancesBaselineWithoutLosingOscillatingChanges() {
		assertCoalescedCatalogOscillation(false);
	}

	@Test
	public void coalescedPromptOfferAdvancesBaselineWithoutLosingOscillatingChanges() {
		assertCoalescedCatalogOscillation(true);
	}

	private static void assertCoalescedCatalogOscillation(boolean prompts) {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		CatalogState catalog = new CatalogState(false);
		CatalogProjectionMetrics metrics = new CatalogProjectionMetrics();
		McpCatalogAccessPolicy accessPolicy = McpCatalogAccessPolicy.fromEvaluators(
				(requestContext, toolRegistration, invocationFeatures) -> catalog.isToolVisible(
						toolRegistration.getName(), invocationFeatures.getCancelationToken()),
				(requestContext, promptRegistration, invocationFeatures) -> catalog.isToolVisible(
						promptRegistration.getName(), invocationFeatures.getCancelationToken()));
		McpSubscriptionAuthorizer authorizer = (authorizationContext, invocationFeatures) ->
				McpSubscriptionAuthorization.Allowed.fromValidUntil(
						Instant.now().plus(Duration.ofMinutes(5)));
		SimulatorConfig config = simulatorConfig(publisher, accessPolicy, authorizer,
				metrics, Duration.ofSeconds(5), prompts);
		Runnable publish = prompts ? publisher::publishPromptsListChanged
				: publisher::publishToolsListChanged;
		String method = prompts ? "notifications/prompts/list_changed"
				: "notifications/tools/list_changed";
		String subscriptionId = "catalog-coalescing";

		SokletSimulator.run(config, simulator -> {
			try (McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest(subscriptionId, prompts))) {
				assertSseResponse(awaitResponse(simulation));
				assertAcknowledgment(nextItem(simulation), subscriptionId, prompts);
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);

				// A -> B -> A while the first hint stays unread. Its coalescing key
				// remains pending, just as a not-yet-written transport frame's does.
				catalog.setConditionalVisible(true);
				publish.run();
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				catalog.setConditionalVisible(false);
				publish.run();
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED);
				assertNotification(nextItem(simulation), method, subscriptionId);
				assertCurrentCatalog(simulator, prompts, false);
				Assertions.assertTrue(pollItem(simulation, Duration.ZERO).isEmpty(),
						"A coalesced offer retained a duplicate wire notification.");

				// The client now sees A. Returning to B must notify it again; leaving
				// the baseline at the first accepted B loses this update indefinitely.
				catalog.setConditionalVisible(true);
				publish.run();
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				assertNotification(nextItem(simulation), method, subscriptionId);
				assertCurrentCatalog(simulator, prompts, true);

				// Repeat the reverse oscillation with another pending hint.
				catalog.setConditionalVisible(false);
				publish.run();
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				catalog.setConditionalVisible(true);
				publish.run();
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED);
				assertNotification(nextItem(simulation), method, subscriptionId);
				assertCurrentCatalog(simulator, prompts, true);
				catalog.setConditionalVisible(false);
				publish.run();
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				assertNotification(nextItem(simulation), method, subscriptionId);
				assertCurrentCatalog(simulator, prompts, false);

				publish.run();
				metrics.awaitOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				Assertions.assertTrue(pollItem(simulation, NO_ITEM_WAIT).isEmpty(),
						"An unchanged catalog emitted a redundant hint after coalescing.");
			}
		});
	}

	private static void assertCurrentCatalog(@NonNull Simulator simulator,
			boolean prompts, boolean conditionalVisible) {
		Request request = catalogRequest(prompts);
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = awaitResponse(simulation);
			Assertions.assertEquals(200, response.getStatusCode());
			String result = new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
			Assertions.assertTrue(result.contains("\"name\":\"" + STABLE_TOOL + "\""), result);
			Assertions.assertEquals(conditionalVisible,
					result.contains("\"name\":\"" + CONDITIONAL_TOOL + "\""), result);
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitCompletion(simulation).getReason());
		}
	}

	private static Request catalogRequest(boolean prompts) {
		String method = prompts ? "prompts/list" : "tools/list";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"list\",\"method\":\"" + method
				+ "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of("Host", List.of(LOOPBACK + ":0"),
						"Content-Type", List.of("application/json"),
						"Accept", List.of("application/json, text/event-stream"),
						"MCP-Protocol-Version", List.of(PROTOCOL_VERSION),
						"Mcp-Method", List.of(method)))
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
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
		return simulatorConfig(publisher, accessPolicy, authorizer, metrics, projectionTimeout, false);
	}

	@NonNull
	private static SimulatorConfig simulatorConfig(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull McpCatalogAccessPolicy accessPolicy,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@NonNull MetricsCollector metrics,
			@NonNull Duration projectionTimeout, boolean prompts) {
		return simulatorConfig(publisher, accessPolicy, authorizer, metrics, projectionTimeout, prompts, ignored -> {});
	}

	@NonNull
	private static SimulatorConfig simulatorConfig(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull McpCatalogAccessPolicy accessPolicy,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@NonNull MetricsCollector metrics,
			@NonNull Duration projectionTimeout, boolean prompts,
			@NonNull Consumer<McpServer.Builder> options) {
		return simulatorConfig(publisher, accessPolicy, authorizer, metrics, projectionTimeout, prompts, options, false);
	}

	@NonNull
	private static SimulatorConfig simulatorConfig(
			@NonNull McpSubscriptionEventPublisher publisher,
			@NonNull McpCatalogAccessPolicy accessPolicy,
			@NonNull McpSubscriptionAuthorizer authorizer,
			@NonNull MetricsCollector metrics,
			@NonNull Duration projectionTimeout, boolean prompts,
			@NonNull Consumer<McpServer.Builder> options, boolean serverInfoIncluded) {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(publisher, EnumSet.of(
						McpSubscriptionNotificationType.TOOLS_LIST_CHANGED,
						McpSubscriptionNotificationType.PROMPTS_LIST_CHANGED,
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
					McpImplementation.withNameAndVersion(
							"catalog-projection-runtime-test", "4.0.0")
							.title("Canonical server title")
							.build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.serverInfoIncluded(serverInfoIncluded)
				.toolRegistrations(java.util.List.of(tool(STABLE_TOOL), tool(CONDITIONAL_TOOL)))
				.promptRegistrations(prompts ? List.of(prompt(STABLE_TOOL), prompt(CONDITIONAL_TOOL))
						: List.of())
				.resourceRegistrations(java.util.List.of(McpResourceRegistration.withUriAndName(
						RESOURCE_URI, "Projection resource", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.withContent(
												McpTextResourceContents.withUriAndText(
														resource.getUri(), "unused")
														.build())
												.build()))
						.build()))
				.subscriptionProtocolVersions(java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28)).subscriptionConfig(subscriptions)
				.build();
		return SimulatorConfig.builder()
				.configureMcpServer(builder -> {
					builder
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
						.allowedHosts(Set.of(LOOPBACK));
					options.accept(builder);
				})
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
		return McpToolRegistration.withName(name, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("unused"))
				.title("Canonical " + name)
				.build();
	}

	@NonNull
	private static McpPromptRegistration prompt(@NonNull String name) {
		return McpPromptRegistration.withName(name, Set.of(McpProtocolVersion.V2026_07_28))
				.handler((requestContext, promptGetContext, invocationFeatures) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages()))
				.title("Canonical " + name)
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
		return subscriptionRequest(id, false);
	}

	@NonNull
	private static Request subscriptionRequest(@NonNull String id, boolean prompts) {
		String catalogFilter = prompts ? "promptsListChanged" : "toolsListChanged";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"" + catalogFilter + "\":true,"
				+ "\"resourcesListChanged\":true}}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of(
						"Host", List.of(LOOPBACK + ":0"),
						"Content-Type", List.of(
								"application/json; charset=UTF-8"),
						"Accept", List.of(
								"application/json, text/event-stream"),
						"MCP-Protocol-Version", List.of(PROTOCOL_VERSION),
						"Mcp-Method", List.of("subscriptions/listen")))
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
		assertAcknowledgment(item, subscriptionId, false);
	}

	private static void assertAcknowledgment(
			@NonNull McpSimulationStreamItem item,
			@NonNull String subscriptionId, boolean prompts) {
		McpJsonObject message = message(item);
		Assertions.assertEquals("notifications/subscriptions/acknowledged",
				stringMember(message, "method"));
		McpJsonObject params = objectMember(message, "params");
		assertSubscriptionId(params, subscriptionId);
		McpJsonObject notifications = objectMember(params, "notifications");
		Assertions.assertEquals(Set.of(prompts ? "promptsListChanged" : "toolsListChanged",
				"resourcesListChanged"), notifications.getMembers().keySet());
		Assertions.assertTrue(booleanMember(notifications,
				prompts ? "promptsListChanged" : "toolsListChanged"));
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

		private void awaitSuccessfulCatalogProjections(int expected) {
			int succeeded = 0;
			for (int seen = 0; succeeded < expected && seen < 2 * expected; seen++) {
				McpMetricsEvent.SubscriptionMaintenance event = pollCatalog(WAIT).orElseThrow(() ->
						new AssertionError("Timed out waiting for a catalog projection."));
				if (event.getOutcome() == McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED) succeeded++;
				else Assertions.assertEquals(McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED, event.getOutcome());
			}
			Assertions.assertEquals(expected, succeeded);
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
