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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public listener and simulator contracts for session-owned legacy resource delivery. */
@Timeout(60)
class McpLegacySubscriptionPublicRuntimeTests {
	private static final List<McpProtocolVersion> LEGACY = List.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> ALL = Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
	private static final Set<McpSubscriptionNotificationType> FAMILIES = Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED,
			McpSubscriptionNotificationType.PROMPTS_LIST_CHANGED, McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
			McpSubscriptionNotificationType.RESOURCE_UPDATED);
	private static final URI EXACT = URI.create("test:///exact");
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final LifecyclePolicy LIFECYCLE = LifecyclePolicy.builder().startupTimeout(WAIT)
			.startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(1))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@Test
	void simulatorAdmissionExposesValidatedSubscribeSelectionAndUnsubscribeOnlyItsOperationName() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness();
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				assertEmptyResult(rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"test:///exact\"}"), 10);
				McpAdmissionContext subscribe = harness.admissions.stream().filter(context -> context.getOperationType() == McpOperationType.RESOURCES_SUBSCRIBE).findFirst().orElseThrow();
				assertEquals("resources/subscribe", subscribe.getJsonRpcMethod());
				assertEquals(version, subscribe.getProtocolVersion());
				assertEquals("test:///exact", subscribe.getOperationName().orElseThrow());
				assertTrue(subscribe.isResourceSubscriptionsIncluded());
				assertEquals(List.of(EXACT), subscribe.getRequestedResourceSubscriptionUris());
				assertThrows(UnsupportedOperationException.class, () -> subscribe.getRequestedResourceSubscriptionUris().add(EXACT));
				assertFalse(subscribe.isToolsListChangedIncluded()); assertFalse(subscribe.isPromptsListChangedIncluded());
				assertFalse(subscribe.isResourcesListChangedIncluded()); assertFalse(subscribe.isTaskIdsRequested()); assertTrue(subscribe.getRequestedTaskIds().isEmpty());
				McpSubscriptionAuthorizationContext authorization = harness.authorizations.get(0);
				assertEquals("resources/subscribe", authorization.getInitialRequestContext().getJsonRpcMethod());
				assertEquals(version, authorization.getInitialRequestContext().getProtocolVersion());
				assertSame(subscribe.getRequest(), authorization.getInitialRequestContext().getRequest());
				assertEquals(Set.of(EXACT), authorization.getResourceSubscriptionUris());
				assertEquals("admission-context", authorization.getApplicationContext().orElseThrow());
				assertTrue(authorization.getPreviousValidUntil().isEmpty());
				assertFalse(authorization.isToolsListChangedIncluded()); assertFalse(authorization.isPromptsListChangedIncluded());
				assertFalse(authorization.isResourcesListChangedIncluded()); assertTrue(authorization.getTaskIds().isEmpty());
				assertTrue(authorization.getInitialRequestContext().getClientInfo().isPresent());
				assertEquals(0, harness.readCalls.get());
				int authorizations = harness.authorizations.size();
				assertEmptyResult(rpc(simulator, version, id, "resources/unsubscribe", "{\"uri\":\"test:///exact\"}"), 10);
				McpAdmissionContext unsubscribe = harness.admissions.stream().filter(context -> context.getOperationType() == McpOperationType.RESOURCES_UNSUBSCRIBE).findFirst().orElseThrow();
				assertFalse(unsubscribe.isResourceSubscriptionsIncluded()); assertTrue(unsubscribe.getRequestedResourceSubscriptionUris().isEmpty());
				assertEquals("test:///exact", unsubscribe.getOperationName().orElseThrow());
				assertEquals(authorizations, harness.authorizations.size(), "Unsubscribe cannot establish or renew permission.");
				assertEmptyResult(rpc(simulator, version, id, "resources/unsubscribe", "{\"uri\":\"test:///missing\"}"), 10);
			});
		}
	}

	@Test
	void invalidUriFailsBeforeAdmissionAndMissingOrDeniedReadableRoutesAreNeutral() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness();
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				int admitted = harness.admissions.size();
				for (String params : List.of("{}", "{\"uri\":42}", "{\"uri\":\"relative\"}", "{\"uri\":\"test:///exact\",\"extra\":true}"))
					assertError(rpc(simulator, version, id, "resources/subscribe", params), -32602);
				assertEquals(admitted, harness.admissions.size()); assertTrue(harness.authorizations.isEmpty());
				McpJsonObject missing = rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"test:///missing\"}");
				assertError(missing, -32002); assertTrue(harness.authorizations.isEmpty());
				assertError(rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"test:///modern\"}"), -32002);
				assertTrue(harness.authorizations.isEmpty(), "A route on another revision must not authorize a legacy URI.");
				harness.denied.set(true);
				McpJsonObject denied = rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"test:///exact\"}");
				assertError(denied, -32002);
				assertEquals(((McpJsonObject) missing.getMembers().get("error")).getMembers(),
						((McpJsonObject) denied.getMembers().get("error")).getMembers(), "Permission and route absence must not reveal different details.");
				assertEquals(0, harness.readCalls.get());
			});
		}
	}

	@Test
	void liveAdmissionCanChallengeBeforeResourceAuthorizationAndInitializationAdvertisesExactFamilies() throws Exception {
		try (SocketFixture fixture = new SocketFixture(new Harness())) {
			for (McpProtocolVersion version : LEGACY) {
				RawResponse initialization = fixture.initialize(version);
				assertEquals(200, initialization.status());
				assertTrue(initialization.body().contains("\"tools\":{\"listChanged\":true}"), initialization.body());
				assertTrue(initialization.body().contains("\"prompts\":{\"listChanged\":true}"), initialization.body());
				assertTrue(initialization.body().contains("\"resources\":{\"listChanged\":true,\"subscribe\":true}"), initialization.body());
				String id = initialization.header("Mcp-Session-Id"); assertNotNull(id);
				fixture.harness.challenge.set(true);
				RawResponse denied = fixture.rpc(version, id, "resources/subscribe", "{\"uri\":\"test:///exact\"}");
				assertEquals(403, denied.status());
				assertEquals("Bearer error=\"insufficient_scope\", scope=\"resource:listen\"", denied.header("WWW-Authenticate"));
				assertTrue(fixture.harness.authorizations.isEmpty());
				fixture.harness.challenge.set(false);
			}
		}
	}

	@Test
	void liveDuplicateTemplateUnsubscribeAndDisconnectedGapPreserveOnlyCurrentUriGrant() throws Exception {
		try (SocketFixture fixture = new SocketFixture(new Harness())) {
			for (McpProtocolVersion version : LEGACY) {
				String id = fixture.initialize(version).header("Mcp-Session-Id");
				URI templateUri = URI.create("test:///template/one");
				assertEmptyWireResult(fixture.rpc(version, id, "resources/subscribe", "{\"uri\":\"" + templateUri + "\"}"));
				assertEmptyWireResult(fixture.rpc(version, id, "resources/subscribe", "{\"uri\":\"" + templateUri + "\"}"));
				assertEquals(0, fixture.harness.readCalls.get());
				try (RawClient get = fixture.openGet(version, id)) {
					assertEquals(200, get.readHead().status());
					fixture.harness.publisher.publishResourceUpdated(templateUri);
					String update = nextWireNotification(get);
					assertTrue(update.contains("\"method\":\"notifications/resources/updated\""), update);
					assertTrue(update.contains("\"params\":{\"uri\":\"test:///template/one\"}"), update);
					assertFalse(update.contains("subscription")); assertFalse(update.contains("\"id\"")); assertFalse(update.contains("_meta"));
					assertNoWireMessages(get, Duration.ofMillis(150));
				}
				long disconnectedBy = System.nanoTime() + WAIT.toNanos();
				while (fixture.server.getDiagnostics().getActiveSubscriptions() != 0 && System.nanoTime() < disconnectedBy) Thread.sleep(5);
				assertEquals(0, fixture.server.getDiagnostics().getActiveSubscriptions());
				fixture.harness.publisher.publishResourceUpdated(templateUri);
				try (RawClient reconnected = fixture.openGet(version, id)) {
					assertEquals(200, reconnected.readHead().status());
					assertTrue(nextWireNotification(reconnected).contains("test:///template/one"));
					assertEmptyWireResult(fixture.rpc(version, id, "resources/unsubscribe", "{\"uri\":\"test:///template/one\"}"));
					fixture.harness.publisher.publishResourceUpdated(templateUri);
					assertNoWireMessages(reconnected, Duration.ofMillis(180));
					assertEmptyWireResult(fixture.rpc(version, id, "resources/unsubscribe", "{\"uri\":\"test:///template/one\"}"));
					fixture.harness.publisher.publishToolsListChanged();
					assertTrue(nextWireNotification(reconnected).contains("notifications/tools/list_changed"), "Unrelated catalog delivery remains active.");
				}
			}
		}
	}

	@Test
	void simulatorGetCallerSubsetCannotDeliverOtherFamiliesOrUnsubscribedUrisAndModernIsIndependent() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness();
				harness.getTypes.set(Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED));
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, "", null))) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					harness.publisher.publishResourceUpdated(EXACT); harness.publisher.publishResourcesListChanged(); harness.publisher.publishPromptsListChanged();
					assertNoSimulatorMessages(get, Duration.ofMillis(180));
					harness.publisher.publishToolsListChanged();
					assertNotification(nextSimulatorNotification(get), "notifications/tools/list_changed", null);
					try (McpSimulation modern = simulator.startMcpRequest(request(HttpMethod.POST, McpProtocolVersion.V2026_07_28, id,
							"{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"test:///exact\",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}", "resources/subscribe"))) {
						McpSimulationResponse response = modern.awaitResponse(WAIT).orElseThrow();
						modern.awaitCompletion(WAIT).orElseThrow();
						assertError(responseObject(response), -32601);
					}
					assertTrue(harness.authorizations.isEmpty());
				}
			});
		}
	}

	@Test
	void callerPolicyAndCustomResourceCatalogHintsAreCoarseAndRearmOnlyAfterAnAdmittedList() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness(); AtomicInteger policyCalls = new AtomicInteger(); AtomicInteger lists = new AtomicInteger();
			harness.endpointOptions = endpoint -> endpoint.resourceListHandler((requestContext, resourceListContext, invocationFeatures) -> {
				lists.incrementAndGet(); return McpResourcePage.builder().resourceDescriptors(resourceListContext.getRegisteredResourceDescriptors()).build();
			}, Set.copyOf(LEGACY));
			harness.additional = builder -> builder.catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators(
					(requestContext, registration, invocationFeatures) -> { policyCalls.incrementAndGet(); return false; },
					(requestContext, registration, invocationFeatures) -> true));
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, "", null))) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					harness.publisher.publishToolsListChanged();
					assertNotification(nextSimulatorNotification(get), "notifications/tools/list_changed", null);
					harness.publisher.publishToolsListChanged(); harness.publisher.publishToolsListChanged();
					assertNoSimulatorMessages(get, Duration.ofMillis(180)); assertEquals(0, policyCalls.get(), "GET must not fabricate a catalog RPC context.");
					McpJsonObject listed = rpc(simulator, version, id, "tools/list", "{}");
					assertEquals(McpJsonArray.fromElements(List.of()), ((McpJsonObject) listed.getMembers().get("result")).getMembers().get("tools"));
					assertTrue(policyCalls.get() > 0);
					harness.publisher.publishToolsListChanged();
					assertNotification(nextSimulatorNotification(get), "notifications/tools/list_changed", null);
					harness.publisher.publishResourcesListChanged();
					assertNotification(nextSimulatorNotification(get), "notifications/resources/list_changed", null);
					assertEquals(0, lists.get(), "Dynamic catalogs invalidate without a fake GET-based list invocation.");
					rpc(simulator, version, id, "resources/list", "{}"); assertEquals(1, lists.get());
					harness.publisher.publishResourcesListChanged();
					assertNotification(nextSimulatorNotification(get), "notifications/resources/list_changed", null);
				}
			});
		}
	}

	@Test
	void immutableCallerIndependentCatalogHintsAreSuppressedButUriUpdatesRemainAvailable() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness(); harness.additional = builder -> builder.catalogAccessPolicy(null);
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				assertEmptyResult(rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"test:///exact\"}"), 10);
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, "", null))) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					harness.publisher.publishToolsListChanged(); harness.publisher.publishPromptsListChanged(); harness.publisher.publishResourcesListChanged();
					assertNoSimulatorMessages(get, Duration.ofMillis(200));
					harness.publisher.publishResourceUpdated(EXACT);
					assertNotification(nextSimulatorNotification(get), "notifications/resources/updated", EXACT);
				}
			});
		}
	}

	@Test
	void localizationInvalidationWorksWithoutCallerPolicyAndIgnoresModernOnlyLocalizedOwners() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness(); AtomicInteger providers = new AtomicInteger();
			harness.endpointOptions = endpoint -> endpoint.toolRegistrations(List.of(McpToolRegistration.withName("localized", ALL).jsonObjectArguments()
					.handler((requestContext, arguments, invocationFeatures) -> McpCompleteResult.fromToolText("ok")).title("Localized title").build()));
			harness.additional = builder -> builder.catalogAccessPolicy(null).localizer(McpLocalizer.withFallbackLocale(Locale.ENGLISH,
					request -> { providers.incrementAndGet(); return McpLocalizationContext.withLocale(Locale.ENGLISH, text -> McpLocalizationResult.useDefaultText()).build(); }).build());
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, "", null))) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					simulator.getMcpServer().orElseThrow().getLocalizationCatalogInvalidator().invalidateCatalogs();
					assertNotification(nextSimulatorNotification(get), "notifications/tools/list_changed", null);
					assertEquals(0, providers.get(), "Localized invalidation needs no invented GET-based provider context.");
				}
			});
		}
		Harness isolated = new Harness();
		isolated.endpointOptions = endpoint -> endpoint.subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(isolated.publisher,
				Set.of(McpSubscriptionNotificationType.RESOURCE_UPDATED)).build()).toolRegistrations(List.of(
				McpToolRegistration.withName("legacy-plain", Set.copyOf(LEGACY)).jsonObjectArguments()
						.handler((requestContext, arguments, invocationFeatures) -> McpCompleteResult.fromToolText("ok")).build(),
				McpToolRegistration.withName("modern-localized", Set.of(McpProtocolVersion.V2026_07_28)).jsonObjectArguments()
						.handler((requestContext, arguments, invocationFeatures) -> McpCompleteResult.fromToolText("ok")).title("Modern title").build()));
		isolated.additional = builder -> builder.catalogAccessPolicy(null).localizer(McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> McpLocalizationContext.withLocale(Locale.ENGLISH, text -> McpLocalizationResult.useDefaultText()).build()).build());
		try (SocketFixture fixture = new SocketFixture(isolated)) {
			for (McpProtocolVersion version : LEGACY) {
				RawResponse initialization = fixture.initialize(version); assertEquals(200, initialization.status());
				assertTrue(initialization.body().contains("\"tools\":{}"), initialization.body());
				assertFalse(initialization.body().contains("\"tools\":{\"listChanged\""), "Modern-only localization must not advertise a legacy tool family.");
			}
		}
	}

	@Test
	void modernOnlyLocalizationCannotMakeAnImmutableLegacyCatalogEmitHints() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness(); AtomicInteger providers = new AtomicInteger();
			harness.endpointOptions = endpoint -> endpoint.toolRegistrations(List.of(
					McpToolRegistration.withName("legacy-plain", Set.copyOf(LEGACY)).jsonObjectArguments()
							.handler((requestContext, arguments, invocationFeatures) -> McpCompleteResult.fromToolText("ok")).build(),
					McpToolRegistration.withName("modern-localized", Set.of(McpProtocolVersion.V2026_07_28)).jsonObjectArguments()
							.handler((requestContext, arguments, invocationFeatures) -> McpCompleteResult.fromToolText("ok")).title("Modern title").build()));
			harness.additional = builder -> builder.catalogAccessPolicy(null).localizer(McpLocalizer.withFallbackLocale(Locale.ENGLISH,
					request -> { providers.incrementAndGet(); return McpLocalizationContext.withLocale(Locale.ENGLISH, text -> McpLocalizationResult.useDefaultText()).build(); }).build());
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				assertEmptyResult(rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"test:///exact\"}"), 10);
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, "", null))) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					simulator.getMcpServer().orElseThrow().getLocalizationCatalogInvalidator().invalidateCatalogs();
					harness.publisher.publishToolsListChanged();
					assertNoSimulatorMessages(get, Duration.ofMillis(200));
					assertEquals(0, providers.get());
					harness.publisher.publishResourceUpdated(EXACT);
					assertNotification(nextSimulatorNotification(get), "notifications/resources/updated", EXACT);
				}
			});
		}
	}

	@Test
	void reconciliationDuringEstablishmentDiscardsStaleDenialAndUsesFreshCancellationLease() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			AtomicInteger checks = new AtomicInteger(); List<CancelationToken> tokens = new CopyOnWriteArrayList<>();
			List<McpSubscriptionAuthorizationContext> contexts = new CopyOnWriteArrayList<>();
			harness.additional = builder -> builder.subscriptionAuthorizer((context, invocationFeatures) -> {
				contexts.add(context); tokens.add(invocationFeatures.getCancelationToken());
				if (checks.incrementAndGet() == 1) {
					entered.countDown();
					long stop = System.nanoTime() + WAIT.toNanos();
					while (release.getCount() != 0 && System.nanoTime() < stop) {
						try { release.await(30, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
					}
					return McpSubscriptionAuthorization.deniedInstance();
				}
				return McpSubscriptionAuthorization.Allowed.withValidUntil(Instant.now().plusSeconds(30)).build();
			});
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				try (McpSimulation subscribe = simulator.startMcpRequest(request(HttpMethod.POST, version, id,
						"{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"test:///exact\"}}", null))) {
					try {
						assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
						simulator.getMcpServer().orElseThrow().getSubscriptionReconciler().reconcileSubscriptions();
						assertTrue(tokens.get(0).isCanceled()); release.countDown();
						McpSimulationResponse response = subscribe.awaitResponse(WAIT).orElseThrow();
						assertEquals(200, response.getStatusCode());
						subscribe.awaitCompletion(WAIT).orElseThrow();
						assertEmptyResult(responseObject(response), 10);
						assertEquals(2, checks.get()); assertNotSame(tokens.get(0), tokens.get(1));
						assertSame(contexts.get(0).getInitialRequestContext(), contexts.get(1).getInitialRequestContext());
						assertEquals("admission-context", contexts.get(1).getApplicationContext().orElseThrow());
					} finally { release.countDown(); }
				}
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, "", null))) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					harness.publisher.publishResourceUpdated(EXACT);
					assertNotification(nextSimulatorNotification(get), "notifications/resources/updated", EXACT);
				}
			});
		}
	}

	@Test
	void subscribeAndUnsubscribeUseTheCurrentOrdinaryRequestLimiterBeforeAnyGrantMutation() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness(); AtomicBoolean limited = new AtomicBoolean(); List<McpRateLimitContext> limits = new CopyOnWriteArrayList<>();
			harness.additional = builder -> builder.requestRateLimiter(context -> {
				limits.add(context); return limited.get() ? McpRateLimitDecision.denied(Duration.ofSeconds(1)) : McpRateLimitDecision.allowed();
			});
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version); limited.set(true);
				for (String method : List.of("resources/subscribe", "resources/unsubscribe")) {
					try (McpSimulation call = simulator.startMcpRequest(request(HttpMethod.POST, version, id,
							"{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"" + method + "\",\"params\":{\"uri\":\"test:///exact\"}}", null))) {
						assertEquals(429, call.awaitResponse(WAIT).orElseThrow().getStatusCode()); call.awaitCompletion(WAIT).orElseThrow();
					}
					McpRateLimitContext context = limits.get(limits.size() - 1);
					assertEquals(method, context.getJsonRpcMethod()); assertEquals("test:///exact", context.getOperationName().orElseThrow());
					assertEquals(version, context.getProtocolVersion());
				}
				assertTrue(harness.authorizations.isEmpty()); limited.set(false);
				assertEmptyResult(rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"test:///exact\"}"), 10);
				assertEquals(1, harness.authorizations.size());
			});
		}
	}

	@Test
	void equivalentUriSpellingsSharePublisherMatchingAndUnsubscribeIdentity() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness();
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				assertEmptyResult(rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"TEST:///exact\"}"), 10);
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, "", null))) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					harness.publisher.publishResourceUpdated(EXACT);
					McpJsonObject notification = (McpJsonObject) nextSimulatorNotification(get);
					assertEquals(McpJsonString.fromValue("notifications/resources/updated"), notification.getMembers().get("method"));
					McpJsonObject params = (McpJsonObject) notification.getMembers().get("params");
					assertEquals(EXACT, URI.create(((McpJsonString) params.getMembers().get("uri")).getValue()));
					assertEmptyResult(rpc(simulator, version, id, "resources/unsubscribe", "{\"uri\":\"test:///exact\"}"), 10);
					harness.publisher.publishResourceUpdated(EXACT); assertNoSimulatorMessages(get, Duration.ofMillis(180));
				}
			});
		}
	}

	@Test
	void detachedGrantReconciliationRenewsRealSubscribeEvidenceAndDeniedUriDoesNotCloseGet() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Harness harness = new Harness();
			AtomicInteger checks = new AtomicInteger();
			CountDownLatch renewed = new CountDownLatch(1);
			AtomicReference<McpRequestContext> original = new AtomicReference<>();
			harness.additional = builder -> builder.subscriptionAuthorizer((context, features) -> {
				int check = checks.incrementAndGet();
				if (check == 1) {
					original.set(context.getInitialRequestContext());
					return McpSubscriptionAuthorization.Allowed.withValidUntil(Instant.now().plusSeconds(30))
							.applicationContext("first-context").build();
				}
				if (check == 2) {
					assertSame(original.get(), context.getInitialRequestContext());
					assertEquals("resources/subscribe", context.getInitialRequestContext().getJsonRpcMethod());
					assertEquals(version, context.getInitialRequestContext().getProtocolVersion());
					assertEquals(Set.of(EXACT), context.getResourceSubscriptionUris());
					assertEquals("first-context", context.getApplicationContext().orElseThrow());
					assertTrue(context.getPreviousValidUntil().orElseThrow().isBefore(Instant.now().plusSeconds(11)));
					renewed.countDown();
					return McpSubscriptionAuthorization.deniedInstance();
				}
				assertTrue(context.getPreviousValidUntil().isEmpty());
				return McpSubscriptionAuthorization.Allowed.withValidUntil(Instant.now().plusSeconds(30)).build();
			});
			SokletSimulator.run(harness.simulatorConfig(), simulator -> {
				String id = initialize(simulator, version);
				assertEmptyResult(rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"test:///exact\"}"), 10);
				// No GET exists: a detached grant must still reconcile against its real POST evidence.
				simulator.getMcpServer().orElseThrow().getSubscriptionReconciler().reconcileSubscriptions();
				assertTrue(renewed.await(3, TimeUnit.SECONDS));
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, "", null))) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					harness.publisher.publishResourceUpdated(EXACT);
					assertNoSimulatorMessages(get, Duration.ofMillis(180));
					harness.publisher.publishToolsListChanged();
					assertNotification(nextSimulatorNotification(get), "notifications/tools/list_changed", null);
					assertEmptyResult(rpc(simulator, version, id, "resources/subscribe", "{\"uri\":\"test:///exact\"}"), 10);
					harness.publisher.publishResourceUpdated(EXACT);
					assertNotification(nextSimulatorNotification(get), "notifications/resources/updated", EXACT);
				}
			});
		}
	}

	private static McpJsonObject rpc(Simulator simulator, McpProtocolVersion version, String id, String method, String params) throws InterruptedException {
		try (McpSimulation call = simulator.startMcpRequest(request(HttpMethod.POST, version, id,
				"{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"" + method + "\",\"params\":" + params + "}", null))) {
			McpSimulationResponse response = call.awaitResponse(WAIT).orElseThrow();
			assertEquals(200, response.getStatusCode());
			call.awaitCompletion(WAIT).orElseThrow();
			return responseObject(response);
		}
	}

	private static McpJsonObject responseObject(McpSimulationResponse response) {
		// Decode only for assertions; application fixtures use the public API.
		var codec = new com.soklet.internal.mcp.protocol.McpJsonCodec(com.soklet.internal.mcp.protocol.McpJsonLimits.productionDefaults());
		return (McpJsonObject) com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter.toPublic(codec.parse(response.getBody().orElseThrow()));
	}

	private static String initialize(Simulator simulator, McpProtocolVersion version) throws InterruptedException {
		try (McpSimulation call = simulator.startMcpRequest(request(HttpMethod.POST, version, null, initializeBody(version), null))) {
			McpSimulationResponse response = call.awaitResponse(WAIT).orElseThrow(); assertEquals(200, response.getStatusCode());
			call.awaitCompletion(WAIT).orElseThrow();
			return response.getHeaders().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("Mcp-Session-Id"))
					.findFirst().orElseThrow().getValue().iterator().next();
		}
	}

	private static String initializeBody(McpProtocolVersion version) {
		return "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + version.getWireValue()
				+ "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"legacy-test\",\"version\":\"1\"}}}";
	}

	private static Request request(HttpMethod method, McpProtocolVersion version, String id, String body, String mirroredMethod) {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
			headers.put("Host", Set.of("127.0.0.1:0")); headers.put("Accept", Set.of("application/json, text/event-stream"));
			headers.put("Content-Type", Set.of("application/json")); headers.put("MCP-Protocol-Version", Set.of(version.getWireValue()));
			if (id != null) headers.put("Mcp-Session-Id", Set.of(id));
			if (mirroredMethod != null) headers.put("Mcp-Method", Set.of(mirroredMethod));
		return Request.withPath(method, "/mcp").headers(headers).body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static void assertEmptyResult(McpJsonObject response, int id) {
		assertEquals(McpJsonNumber.fromValue(java.math.BigDecimal.valueOf(id)), response.getMembers().get("id"));
		assertEquals(McpJsonObject.emptyInstance(), response.getMembers().get("result")); assertFalse(response.getMembers().containsKey("error"));
	}
	private static void assertError(McpJsonObject response, int code) {
		McpJsonObject error = (McpJsonObject) response.getMembers().get("error"); assertNotNull(error);
		assertEquals(McpJsonNumber.fromValue(java.math.BigDecimal.valueOf(code)), error.getMembers().get("code")); assertFalse(response.getMembers().containsKey("result"));
	}
	private static void assertEmptyWireResult(RawResponse response) {
		assertEquals(200, response.status(), response.body()); assertTrue(response.body().contains("\"result\":{}"), response.body());
		assertFalse(response.body().contains("\"error\""));
	}
	private static McpJsonValue nextSimulatorNotification(McpSimulation get) throws InterruptedException {
		for (int count = 0; count < 64; count++) {
			McpSimulationStreamItem item = get.awaitStreamItem(WAIT).orElseThrow();
			if (item.getMessage().isPresent()) return item.getMessage().orElseThrow();
		}
		throw new AssertionError("Expected bounded notification.");
	}
	private static void assertNoSimulatorMessages(McpSimulation get, Duration duration) throws InterruptedException {
		long deadline = System.nanoTime() + duration.toNanos();
		while (System.nanoTime() < deadline) {
			var item = get.awaitStreamItem(Duration.ofMillis(30));
			if (item.isPresent()) assertTrue(item.orElseThrow().getMessage().isEmpty(), "Unexpected family or URI disclosure.");
		}
	}
	private static void assertNotification(McpJsonValue message, String method, URI uri) {
		McpJsonObject object = (McpJsonObject) message;
		assertEquals(McpJsonString.fromValue(method), object.getMembers().get("method"));
		assertFalse(object.getMembers().containsKey("id")); assertFalse(object.getMembers().containsKey("_meta"));
		McpJsonValue params = object.getMembers().get("params");
		if (uri == null) assertTrue(params == null || params.equals(McpJsonObject.emptyInstance()));
		else assertEquals(McpJsonObject.fromMembers(Map.of("uri", McpJsonString.fromValue(uri.toString()))), params);
	}
	private static String nextWireNotification(RawClient get) throws Exception {
		for (int count = 0; count < 64; count++) {
			byte[] chunk = get.readChunk(); assertNotNull(chunk, "GET ended before notification.");
			String frame = new String(chunk, StandardCharsets.UTF_8);
			if (frame.contains("data:")) return frame;
		}
		throw new AssertionError("Expected bounded wire notification.");
	}
	private static void assertNoWireMessages(RawClient get, Duration duration) throws Exception {
		long deadline = System.nanoTime() + duration.toNanos();
		while (System.nanoTime() < deadline) {
			byte[] chunk = get.readChunk(); assertNotNull(chunk, "GET must remain open.");
			assertFalse(new String(chunk, StandardCharsets.UTF_8).contains("data:"), "Unexpected URI disclosure or duplicate delivery.");
		}
	}

	private static McpAdmissionIdentity identity() {
		return McpAdmissionIdentity.withRateLimitPartitionKey("owner").authorizationPartitionKey("owner")
				.principal("owner").applicationContext("admission-context").build();
	}

	private record RawResponse(int status, Map<String, List<String>> headers, String body) {
		String header(String name) {
			return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
					.findFirst().orElseThrow().getValue().get(0);
		}
	}

	static final class Harness {
		final McpSubscriptionEventPublisher publisher = McpSubscriptionEventPublisher.fromInMemoryDefaults();
		final List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		final List<McpSubscriptionAuthorizationContext> authorizations = new CopyOnWriteArrayList<>();
		final AtomicBoolean denied = new AtomicBoolean(); final AtomicBoolean challenge = new AtomicBoolean();
		final AtomicInteger readCalls = new AtomicInteger();
		final AtomicReference<Set<McpSubscriptionNotificationType>> getTypes = new AtomicReference<>(FAMILIES);
		Consumer<McpServer.Builder> additional = ignored -> {};
		Consumer<McpEndpoint.Builder> endpointOptions = ignored -> {};
		McpEndpoint endpoint() {
			McpResourceReadHandler read = (requestContext, resourceReadContext, invocationFeatures) -> {
				readCalls.incrementAndGet(); throw new AssertionError("Subscribe never invokes a read handler.");
			};
			McpEndpoint.Builder endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("legacy-delivery", "1").build(), ALL)
					.sessionProtocolVersions(Set.copyOf(LEGACY)).subscriptionProtocolVersions(Set.copyOf(LEGACY))
					.subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(publisher, FAMILIES).build())
					.resourceRegistrations(List.of(McpResourceRegistration.withUriAndName(EXACT, "exact", ALL).handler(read).build(),
							McpResourceRegistration.withUriTemplateAndName("test:///template/{key}", "template", ALL).handler(read).build(),
							McpResourceRegistration.withUriAndName(URI.create("test:///modern"), "modern", Set.of(McpProtocolVersion.V2026_07_28)).handler(read).build()))
					.toolRegistrations(List.of(McpToolRegistration.withName("tool", ALL).jsonObjectArguments()
							.handler((requestContext, arguments, invocationFeatures) -> McpCompleteResult.fromToolText("ok")).build()))
					.promptRegistrations(List.of(McpPromptRegistration.withName("prompt", ALL)
							.handler((requestContext, promptContext, invocationFeatures) -> { throw new AssertionError("No prompt invocation."); }).build()));
			endpointOptions.accept(endpoint);
			return endpoint.build();
		}
		void configure(McpServer.Builder builder) {
			builder.port(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1")).corsAuthorizer(CorsAuthorizer.rejectAllInstance())
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint())))
					.requestTimeout(Duration.ofSeconds(4)).maximumSubscriptionDuration(Duration.ofSeconds(20))
					.maximumSubscriptionAuthorizationDuration(Duration.ofSeconds(10)).keepAliveInterval(Duration.ofMillis(30))
					.toolRateLimiter(context -> McpRateLimitDecision.allowed())
					.catalogAccessPolicy(McpCatalogAccessPolicy.allowAllInstance())
					.admissionController(context -> {
						admissions.add(context);
						if (challenge.get() && context.getOperationType() == McpOperationType.RESOURCES_SUBSCRIBE)
							return McpAdmissionDecision.rejected(McpAdmissionRejection.withStatusCodeAndError(403,
									McpJsonRpcError.fromApplication(-31903, "Scope required"))
									.addHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"resource:listen\"").build());
						return McpAdmissionDecision.accepted(identity());
					}).sessionConfig(McpSessionConfig.withOwnerKeyResolver(admissionIdentity -> "owner")
							.transportAdmissionController((context, features) -> McpSessionTransportAdmissionDecision.accepted(identity(),
										Instant.now().plusSeconds(30), context.getRequest().getHttpMethod() == HttpMethod.DELETE ? Set.of() : getTypes.get())).build())
					.subscriptionAuthorizer((context, features) -> {
						authorizations.add(context); assertTrue(features.getProgressReporter().isEmpty()); assertNotNull(features.getCancelationToken());
						return denied.get() ? McpSubscriptionAuthorization.deniedInstance()
								: McpSubscriptionAuthorization.Allowed.withValidUntil(Instant.now().plusSeconds(30)).applicationContext("grant-context").build();
					});
			additional.accept(builder);
		}
		SimulatorConfig simulatorConfig() {
			return SimulatorConfig.builder().configureMcpServer(this::configure)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).lifecyclePolicy(LIFECYCLE).build();
		}
	}

	static final class SocketFixture implements AutoCloseable {
		final Harness harness; final McpServer server; final Soklet soklet; final int port;
		SocketFixture(Harness harness) throws Exception {
			this.harness = harness; McpServer.Builder builder = McpServer.withPort(0); harness.configure(builder); this.server = builder.build();
			this.soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).lifecyclePolicy(LIFECYCLE).build());
			try { soklet.start(); this.port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort(); }
			catch (Throwable failure) { soklet.close(); throw failure; }
		}
		RawResponse initialize(McpProtocolVersion version) throws Exception { return exchange(initializeBody(version), List.of()); }
		RawResponse rpc(McpProtocolVersion version, String id, String method, String params) throws Exception {
			return exchange("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"" + method + "\",\"params\":" + params + "}",
					List.of(new HeaderValue("MCP-Protocol-Version", version.getWireValue()), new HeaderValue("Mcp-Session-Id", id)));
		}
		RawResponse exchange(String body, List<HeaderValue> headers) throws Exception {
			try (RawClient client = new RawClient(port, "POST", "/mcp", body, headers)) {
				Head head = client.readHead(); return new RawResponse(head.status(), head.headers(), client.readBody(head));
			}
		}
		RawClient openGet(McpProtocolVersion version, String id) throws Exception {
			return new RawClient(port, "GET", "/mcp", "", List.of(new HeaderValue("MCP-Protocol-Version", version.getWireValue()), new HeaderValue("Mcp-Session-Id", id)));
		}
		@Override public void close() { soklet.close(); }
	}
}
