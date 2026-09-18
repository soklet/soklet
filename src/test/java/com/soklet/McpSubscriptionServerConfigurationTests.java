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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-level configuration contracts for subscription maintenance.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpSubscriptionServerConfigurationTests {
	@Test
	public void maintenanceDefaultsAndNullResetsAreRetained() {
		DefaultMcpServer defaults = buildPlainServer(McpServer.withPort(0));
		DefaultMcpServer reset = buildPlainServer(McpServer.withPort(0)
				.subscriptionCatalogProjectionTimeout(Duration.ofSeconds(2))
				.subscriptionCatalogProjectionTimeout(null)
				.subscriptionAuthorizationTimeout(Duration.ofSeconds(3))
				.subscriptionAuthorizationTimeout(null)
				.maximumSubscriptionAuthorizationDuration(Duration.ofMinutes(2))
				.maximumSubscriptionAuthorizationDuration(null));

		Assertions.assertEquals(Duration.ofSeconds(5),
				defaults.subscriptionCatalogProjectionTimeout());
		Assertions.assertEquals(Duration.ofSeconds(5),
				defaults.subscriptionAuthorizationTimeout());
		Assertions.assertEquals(Duration.ofMinutes(1),
				defaults.maximumSubscriptionAuthorizationDuration());
		Assertions.assertEquals(defaults.subscriptionCatalogProjectionTimeout(),
				reset.subscriptionCatalogProjectionTimeout());
		Assertions.assertEquals(defaults.subscriptionAuthorizationTimeout(),
				reset.subscriptionAuthorizationTimeout());
		Assertions.assertEquals(
				defaults.maximumSubscriptionAuthorizationDuration(),
				reset.maximumSubscriptionAuthorizationDuration());
	}

	@Test
	public void maintenanceNondefaultDurationsAreStored() {
		DefaultMcpServer server = buildPlainServer(McpServer.withPort(0)
				.subscriptionCatalogProjectionTimeout(Duration.ofSeconds(2))
				.subscriptionAuthorizationTimeout(Duration.ofSeconds(3))
				.maximumSubscriptionAuthorizationDuration(Duration.ofMinutes(2)));

		Assertions.assertEquals(Duration.ofSeconds(2),
				server.subscriptionCatalogProjectionTimeout());
		Assertions.assertEquals(Duration.ofSeconds(3),
				server.subscriptionAuthorizationTimeout());
		Assertions.assertEquals(Duration.ofMinutes(2),
				server.maximumSubscriptionAuthorizationDuration());
	}

	@Test
	public void maintenanceDurationsRejectNonpositiveAndUnrepresentableValues() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpServer.withPort(0)
						.subscriptionCatalogProjectionTimeout(Duration.ZERO));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpServer.withPort(0)
						.subscriptionAuthorizationTimeout(Duration.ofNanos(-1)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpServer.withPort(0)
						.maximumSubscriptionAuthorizationDuration(
								Duration.ofSeconds(Long.MAX_VALUE)));
	}

	@Test
	public void defaultAuthorizerAndServerOwnedReconcilerAreStable() {
		DefaultMcpServer first = buildPlainServer(McpServer.withPort(0));
		DefaultMcpServer second = buildPlainServer(McpServer.withPort(0));

		Assertions.assertSame(McpSubscriptionAuthorizer.denyAllInstance(),
				first.getSubscriptionAuthorizer());
		Assertions.assertSame(first.getSubscriptionReconciler(),
				first.getSubscriptionReconciler());
		Assertions.assertNotSame(first.getSubscriptionReconciler(),
				second.getSubscriptionReconciler());
		first.getSubscriptionReconciler().reconcileSubscriptions();
	}

	@Test
	public void endpointSubscriptionsRequireExplicitAuthorizerSelection() {
		McpEndpointRegistry registry = registry(subscriptionEndpoint());

		Assertions.assertThrows(IllegalStateException.class,
				() -> McpServer.withPort(0).endpointRegistry(registry).build());
		McpServer explicitDeny = McpServer.withPort(0)
				.endpointRegistry(registry)
				.subscriptionAuthorizer(
						McpSubscriptionAuthorizer.denyAllInstance())
				.build();
		Assertions.assertSame(McpSubscriptionAuthorizer.denyAllInstance(),
				explicitDeny.getSubscriptionAuthorizer());
		Assertions.assertThrows(IllegalStateException.class,
				() -> McpServer.withPort(0)
						.endpointRegistry(registry)
						.subscriptionAuthorizer(
								McpSubscriptionAuthorizer.denyAllInstance())
						.subscriptionAuthorizer(null)
						.build());
	}

	@Test
	public void taskAndFrameworkLocalizationSubscriptionsRequireSelection() {
		McpEndpointRegistry plainRegistry = registry(plainEndpoint());
		Assertions.assertThrows(IllegalStateException.class,
				() -> McpServer.withPort(0)
						.endpointRegistry(plainRegistry)
						.taskManager(McpTaskManager.fromInMemoryDefaults())
						.build());
		McpServer.withPort(0)
				.endpointRegistry(plainRegistry)
				.taskManager(McpTaskManager.fromInMemoryDefaults())
				.subscriptionAuthorizer(
						McpSubscriptionAuthorizer.denyAllInstance())
				.build();

		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> {
					throw new AssertionError("No localization request is expected");
				}).build();
		McpEndpoint localizedEndpoint = McpEndpoint
				.withPath("/localized", implementation())
				.instructions("Localizable instructions")
				.build();
		Assertions.assertThrows(IllegalStateException.class,
				() -> McpServer.withPort(0)
						.endpointRegistry(registry(localizedEndpoint))
						.localizer(localizer)
						.build());
		McpServer.withPort(0)
				.endpointRegistry(registry(localizedEndpoint))
				.localizer(localizer)
				.subscriptionAuthorizer(
						McpSubscriptionAuthorizer.denyAllInstance())
				.build();
	}

	@Test
	public void localizerWithoutAPlannedCatalogDoesNotEnableSubscriptions() {
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> {
					throw new AssertionError("No localization request is expected");
				}).build();

		McpServer server = McpServer.withPort(0)
				.endpointRegistry(registry(plainEndpoint()))
				.localizer(localizer)
				.build();

		Assertions.assertTrue(
				server.getLocalizationCatalogInvalidator().isEnabled());
		Assertions.assertNotNull(server.getSubscriptionReconciler());
	}

	@Test
	public void simulatorCopyPreservesExplicitAuthorizerSelection() {
		McpSubscriptionAuthorizer authorizer = (context, features) ->
				McpSubscriptionAuthorization.deniedInstance();
		DefaultMcpServer source = (DefaultMcpServer) McpServer.withPort(0)
				.endpointRegistry(registry(subscriptionEndpoint()))
				.subscriptionAuthorizer(authorizer)
				.build();

		DefaultMcpServer derived = SimulatorConfig.fromSokletConfig(
				SokletConfig.withMcpServer(source).build()).simulatedMcpServer();

		Assertions.assertNotNull(derived);
		Assertions.assertSame(authorizer, derived.getSubscriptionAuthorizer());
	}

	@Test
	public void simulatorCopyPreservesImplicitAuthorizerSelection() {
		DefaultMcpServer source = buildPlainServer(McpServer.withPort(0));

		Assertions.assertSame(McpSubscriptionAuthorizer.denyAllInstance(),
				source.getSubscriptionAuthorizer());
		IllegalStateException failure = Assertions.assertThrows(
				IllegalStateException.class,
				() -> SimulatorConfig.withSokletConfig(
						SokletConfig.withMcpServer(source).build())
						.configureMcpServer(builder -> builder.endpointRegistry(
								registry(subscriptionEndpoint()))));
		Assertions.assertEquals(
				"An MCP subscription authorizer must be explicitly configured when "
						+ "subscription support is enabled.", failure.getMessage());
	}

	private static DefaultMcpServer buildPlainServer(McpServer.Builder builder) {
		return (DefaultMcpServer) builder.endpointRegistry(registry(plainEndpoint()))
				.build();
	}

	private static McpEndpoint subscriptionEndpoint() {
		McpSubscriptionConfig config = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(
						McpSubscriptionEventPublisher.fromInMemoryDefaults(),
						Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED))
				.build();
		return McpEndpoint.withPath("/subscriptions", implementation())
				.subscriptionConfig(config)
				.build();
	}

	private static McpEndpoint plainEndpoint() {
		return McpEndpoint.withPath("/plain", implementation()).build();
	}

	private static McpEndpointRegistry registry(McpEndpoint endpoint) {
		return McpEndpointRegistry.fromEndpoints(List.of(endpoint));
	}

	private static McpImplementation implementation() {
		return McpImplementation.withNameAndVersion(
				"subscription-server-configuration-tests", "4.0.0").build();
	}
}
