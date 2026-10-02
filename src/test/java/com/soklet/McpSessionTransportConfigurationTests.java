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

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP admission value snapshots and exact-revision configuration dependencies. */
class McpSessionTransportConfigurationTests {
	private static final Set<McpProtocolVersion> LEGACY = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> ALL = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);

	@Test
	void acceptanceRetainsIdentityAndExpiryAndSnapshotsFamilies() {
		McpAdmissionIdentity identity = McpAdmissionIdentity.withRateLimitPartitionKey("private-owner").build();
		Instant expiry = Instant.parse("2030-01-01T00:00:00Z");
		Set<McpSubscriptionNotificationType> selection = EnumSet.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED);
		McpSessionTransportAdmissionDecision.Accepted accepted = McpSessionTransportAdmissionDecision.accepted(identity, expiry, selection);
		selection.add(McpSubscriptionNotificationType.RESOURCE_UPDATED);
		assertSame(identity, accepted.getIdentity());
		assertEquals(expiry, accepted.getValidUntil());
		assertEquals(Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED), accepted.getNotificationTypes());
		assertThrows(UnsupportedOperationException.class, () -> accepted.getNotificationTypes().clear());
		assertEquals(accepted, McpSessionTransportAdmissionDecision.accepted(identity, expiry, Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED)));
		assertEquals(accepted.hashCode(), McpSessionTransportAdmissionDecision.accepted(identity, expiry, accepted.getNotificationTypes()).hashCode());
		assertNotEquals(accepted, McpSessionTransportAdmissionDecision.accepted(identity, expiry.plusSeconds(1), accepted.getNotificationTypes()));
		assertTrue(McpSessionTransportAdmissionDecision.accepted(identity, expiry, Set.of()).getNotificationTypes().isEmpty());
		assertFalse(accepted.toString().contains("private-owner"));
		assertFalse(accepted.toString().contains("2030"));
		assertThrows(NullPointerException.class, () -> McpSessionTransportAdmissionDecision.accepted(null, expiry, Set.of()));
		assertThrows(NullPointerException.class, () -> McpSessionTransportAdmissionDecision.accepted(identity, null, Set.of()));
		assertThrows(NullPointerException.class, () -> McpSessionTransportAdmissionDecision.accepted(identity, expiry, null));
		Set<McpSubscriptionNotificationType> invalid = new HashSet<>();
		invalid.add(null);
		assertThrows(NullPointerException.class, () -> McpSessionTransportAdmissionDecision.accepted(identity, expiry, invalid));
	}

	@Test
	void rejectionRetainsExistingSafeCarrierAndRedactsDiagnostics() {
		McpAdmissionRejection rejection = McpAdmissionRejection.withStatusCodeAndError(403,
				McpJsonRpcError.fromApplication(-31903, "private-message"))
				.addHeader("WWW-Authenticate", "Bearer scope=\"private-scope\"").build();
		McpSessionTransportAdmissionDecision.Rejected decision = McpSessionTransportAdmissionDecision.rejected(rejection);
		assertSame(rejection, decision.getRejection());
		assertEquals(decision, McpSessionTransportAdmissionDecision.rejected(rejection));
		assertFalse(decision.toString().contains("private-message"));
		assertFalse(decision.toString().contains("private-scope"));
		assertThrows(NullPointerException.class, () -> McpSessionTransportAdmissionDecision.rejected(null));
	}

	@Test
	void nullableControllerConfigurationSnapshotsAndNeverInvokesCallbacks() {
		AtomicInteger calls = new AtomicInteger();
		McpSessionTransportAdmissionController controller = (context, features) -> {
			calls.incrementAndGet();
			throw new AssertionError("Construction must not invoke HTTP admission.");
		};
		McpSessionConfig.Builder builder = McpSessionConfig.withOwnerKeyResolver(identity -> "owner");
		assertTrue(builder.build().getTransportAdmissionController().isEmpty());
		McpSessionConfig configured = builder.transportAdmissionController(controller).build();
		McpSessionConfig cleared = builder.transportAdmissionController(null).build();
		assertSame(controller, configured.getTransportAdmissionController().orElseThrow());
		assertTrue(cleared.getTransportAdmissionController().isEmpty());
		assertEquals(0, calls.get());
		// A controller permits session cleanup without enabling unsolicited delivery.
		assertDoesNotThrow(() -> server(endpointBuilder(LEGACY).sessionProtocolVersions(LEGACY).build())
				.sessionConfig(configured).build());
		assertEquals(0, calls.get());
	}

	@Test
	void legacySubscriptionSelectionRequiresTheSameSessionRevisions() {
		assertThrows(IllegalStateException.class, () -> endpointBuilder(ALL).subscriptionProtocolVersions(LEGACY).build());
		assertThrows(IllegalStateException.class, () -> endpointBuilder(ALL)
				.sessionProtocolVersions(Set.of(McpProtocolVersion.V2025_06_18)).subscriptionProtocolVersions(LEGACY).build());
		McpEndpoint endpoint = endpointBuilder(ALL).sessionProtocolVersions(LEGACY).subscriptionProtocolVersions(ALL).build();
		assertEquals(ALL, endpoint.getSubscriptionProtocolVersions());
		assertEquals(LEGACY, endpoint.getSessionProtocolVersions());
		assertDoesNotThrow(() -> endpointBuilder(Set.of(McpProtocolVersion.V2026_07_28))
				.subscriptionProtocolVersions(Set.of(McpProtocolVersion.V2026_07_28)).build());
	}

	@Test
	void legacyDeliveryRequiresControllerAndFamiliesAndUriUpdatesRequireAuthorizer() {
		McpSubscriptionConfig catalogs = publisher(Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED));
		McpEndpoint catalogEndpoint = endpointBuilder(LEGACY).sessionProtocolVersions(LEGACY)
				.subscriptionProtocolVersions(LEGACY).subscriptionConfig(catalogs).build();
		assertThrows(IllegalStateException.class, () -> server(catalogEndpoint).sessionConfig(config(false)).build());
		assertDoesNotThrow(() -> server(catalogEndpoint).sessionConfig(config(true)).build());
		McpEndpoint noFamilies = endpointBuilder(LEGACY).sessionProtocolVersions(LEGACY)
				.subscriptionProtocolVersions(LEGACY).build();
		assertThrows(IllegalStateException.class, () -> server(noFamilies).sessionConfig(config(true)).build());
			McpEndpoint uriUpdates = endpointBuilder(LEGACY).sessionProtocolVersions(LEGACY)
					.subscriptionProtocolVersions(LEGACY).subscriptionConfig(publisher(Set.of(McpSubscriptionNotificationType.RESOURCE_UPDATED)))
					.resourceRegistrations(List.of(McpResourceRegistration.withUriAndName(java.net.URI.create("resource://catalog/item"), "item", LEGACY)
							.handler((request, read, features) -> { throw new AssertionError("Construction must not read resources."); }).build())).build();
		assertThrows(IllegalStateException.class, () -> server(uriUpdates).sessionConfig(config(true)).build());
		assertDoesNotThrow(() -> server(uriUpdates).sessionConfig(config(true))
				.subscriptionAuthorizer(McpSubscriptionAuthorizer.denyAllInstance()).build());
		McpEndpoint modern = endpointBuilder(Set.of(McpProtocolVersion.V2026_07_28))
				.subscriptionProtocolVersions(Set.of(McpProtocolVersion.V2026_07_28)).subscriptionConfig(catalogs).build();
		assertThrows(IllegalStateException.class, () -> server(modern).build());
		assertDoesNotThrow(() -> server(modern).subscriptionAuthorizer(McpSubscriptionAuthorizer.denyAllInstance()).build());
	}

	@Test
	void localizationFamiliesAreComputedFromOwnersVisibleAtEachSelectedRevision() {
		McpToolRegistration<?> modernText = McpToolRegistration.withName("modern-text", Set.of(McpProtocolVersion.V2026_07_28))
				.jsonObjectArguments().handler((request, args, features) -> McpCompleteResult.fromToolText("ok")).title("Modern only").build();
		McpToolRegistration<?> juneText = McpToolRegistration.withName("june-text", Set.of(McpProtocolVersion.V2025_06_18))
				.jsonObjectArguments().handler((request, args, features) -> McpCompleteResult.fromToolText("ok")).title("June only").build();
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> McpLocalizationContext.withLocale(Locale.ENGLISH, text -> McpLocalizationResult.useDefaultText()).build()).build();
		McpEndpoint invisible = endpointBuilder(ALL).sessionProtocolVersions(LEGACY).subscriptionProtocolVersions(LEGACY)
				.toolRegistrations(List.of(modernText)).build();
		assertThrows(IllegalStateException.class, () -> server(invisible).sessionConfig(config(true)).localizer(localizer)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed()).build());
		McpEndpoint juneOnly = endpointBuilder(ALL).sessionProtocolVersions(LEGACY)
				.subscriptionProtocolVersions(Set.of(McpProtocolVersion.V2025_06_18))
				.toolRegistrations(List.of(modernText, juneText)).build();
		assertDoesNotThrow(() -> server(juneOnly).sessionConfig(config(true)).localizer(localizer)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed()).build());
		McpEndpoint both = endpointBuilder(ALL).sessionProtocolVersions(LEGACY).subscriptionProtocolVersions(LEGACY)
				.toolRegistrations(List.of(modernText, juneText)).build();
		assertThrows(IllegalStateException.class, () -> server(both).sessionConfig(config(true)).localizer(localizer)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed()).build());
	}

	@Test
	void resourceNotificationSourcesMustHaveASurfaceInEverySelectedLegacyRevision() {
		McpResourceRegistration modern = McpResourceRegistration.withUriAndName(java.net.URI.create("resource://catalog/modern"), "modern",
				Set.of(McpProtocolVersion.V2026_07_28)).handler((request, read, features) -> {
					throw new AssertionError("Construction must not read resources.");
				}).build();
		McpResourceRegistration june = McpResourceRegistration.withUriAndName(java.net.URI.create("resource://catalog/june"), "june",
				Set.of(McpProtocolVersion.V2025_06_18)).handler((request, read, features) -> {
					throw new AssertionError("Construction must not read resources.");
				}).build();
		for (McpSubscriptionNotificationType family : List.of(McpSubscriptionNotificationType.RESOURCE_UPDATED,
				McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
			for (List<McpResourceRegistration> resources : List.of(List.of(modern), List.of(modern, june))) {
				McpEndpoint endpoint = endpointBuilder(ALL).sessionProtocolVersions(LEGACY).subscriptionProtocolVersions(LEGACY)
						.subscriptionConfig(publisher(Set.of(family))).resourceRegistrations(resources).build();
				IllegalStateException failure = assertThrows(IllegalStateException.class, () -> server(endpoint).sessionConfig(config(true))
						.subscriptionAuthorizer(McpSubscriptionAuthorizer.denyAllInstance()).build());
				assertTrue(failure.getMessage().contains("at each selected revision"), failure.getMessage());
			}
	}

	private static McpSessionConfig config(boolean controller) {
		McpSessionConfig.Builder builder = McpSessionConfig.withOwnerKeyResolver(identity -> "owner");
		if (controller) builder.transportAdmissionController((context, features) ->
				McpSessionTransportAdmissionDecision.accepted(McpAdmissionIdentity.anonymousInstance(), Instant.now().plusSeconds(30), context.getNotificationTypes()));
		return builder.build();
	}
	private static McpSubscriptionConfig publisher(Set<McpSubscriptionNotificationType> families) {
		return McpSubscriptionConfig.withEventPublisherAndNotificationTypes(McpSubscriptionEventPublisher.fromInMemoryDefaults(), families).build();
	}
	private static McpEndpoint.Builder endpointBuilder(Set<McpProtocolVersion> versions) {
		return McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("catalog", "1").build(), versions);
	}
	private static McpServer.Builder server(McpEndpoint endpoint) {
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)));
	}
}
