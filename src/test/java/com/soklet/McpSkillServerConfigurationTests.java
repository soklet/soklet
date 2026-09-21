/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSkillServerConfigurationTests {
	private static final String SELECTOR_REQUIRED =
			"An MCP Skills variant selector must be explicitly configured for multivariant groups.";

	@Test
	void defaultsExposeSharedAllowAllPolicyAndNoSelector() throws Exception {
		McpServer server = serverBuilder().build();
		assertSame(McpSkillAccessPolicy.allowAllInstance(), server.getSkillAccessPolicy());
		assertTrue(server.getSkillVariantSelector().isEmpty());
		assertFalse(explicitPolicy(server));
	}

	@Test
	void replacementKeepsExactCallbacksAndEarlierServerSnapshots() throws Exception {
		McpSkillAccessPolicy firstPolicy = unusedPolicy();
		McpSkillAccessPolicy secondPolicy = unusedPolicy();
		McpSkillVariantSelector firstSelector = unusedSelector();
		McpSkillVariantSelector secondSelector = (request, selection, features) -> {
			throw new AssertionError("Replacement selector must not run during construction.");
		};
		McpServer.Builder builder = serverBuilder();
		assertSame(builder, builder.skillAccessPolicy(firstPolicy));
		assertSame(builder, builder.skillVariantSelector(firstSelector));
		McpServer first = builder.build();
		McpServer second = builder.skillAccessPolicy(secondPolicy)
				.skillVariantSelector(secondSelector).build();
		assertSame(firstPolicy, first.getSkillAccessPolicy());
		assertSame(firstSelector, first.getSkillVariantSelector().orElseThrow());
		assertSame(secondPolicy, second.getSkillAccessPolicy());
		assertSame(secondSelector, second.getSkillVariantSelector().orElseThrow());
		assertTrue(explicitPolicy(first));
		assertTrue(explicitPolicy(second));
	}

	@Test
	void nullableResetsAreIndependentAndRestoreImplicitPolicyState() throws Exception {
		McpSkillAccessPolicy policy = unusedPolicy();
		McpSkillVariantSelector selector = unusedSelector();
		McpServer.Builder builder = serverBuilder().skillAccessPolicy(policy).skillVariantSelector(selector);
		assertSame(builder, builder.skillAccessPolicy(null));
		McpServer policyReset = builder.build();
		assertSame(McpSkillAccessPolicy.allowAllInstance(), policyReset.getSkillAccessPolicy());
		assertSame(selector, policyReset.getSkillVariantSelector().orElseThrow());
		assertFalse(explicitPolicy(policyReset));

		builder.skillAccessPolicy(policy);
		assertSame(builder, builder.skillVariantSelector(null));
		McpServer selectorReset = builder.build();
		assertSame(policy, selectorReset.getSkillAccessPolicy());
		assertTrue(selectorReset.getSkillVariantSelector().isEmpty());
		assertTrue(explicitPolicy(selectorReset));

		McpServer bothReset = builder.skillAccessPolicy(null).skillVariantSelector(null).build();
		assertSame(McpSkillAccessPolicy.allowAllInstance(), bothReset.getSkillAccessPolicy());
		assertTrue(bothReset.getSkillVariantSelector().isEmpty());
		assertFalse(explicitPolicy(bothReset));
	}

	@Test
	void explicitlyPassingTheDefaultPolicyRemainsDistinctFromOmissionOrReset() throws Exception {
		McpServer.Builder builder = serverBuilder();
		McpServer implicit = builder.build();
		McpServer explicit = builder.skillAccessPolicy(McpSkillAccessPolicy.allowAllInstance()).build();
		McpServer reset = builder.skillAccessPolicy(null).build();
		assertSame(implicit.getSkillAccessPolicy(), explicit.getSkillAccessPolicy());
		assertSame(explicit.getSkillAccessPolicy(), reset.getSkillAccessPolicy());
		assertFalse(explicitPolicy(implicit));
		assertTrue(explicitPolicy(explicit));
		assertFalse(explicitPolicy(reset));
	}

	@Test
	void simulatorCopiesFrozenPolicySelectorAndExplicitConfigurationState() throws Exception {
		McpSkillAccessPolicy policy = unusedPolicy();
		McpSkillVariantSelector selector = unusedSelector();
		McpServer.Builder builder = serverBuilder().skillAccessPolicy(policy).skillVariantSelector(selector);
		McpServer source = builder.build();
		builder.skillAccessPolicy(null).skillVariantSelector(null);
		DefaultMcpServer derived = SimulatorConfig.fromSokletConfig(
				SokletConfig.withMcpServer(source).build()).simulatedMcpServer();
		assertNotSame(source, derived);
		assertSame(source.getEndpointRegistry(), derived.getEndpointRegistry());
		assertSame(policy, source.getSkillAccessPolicy());
		assertSame(policy, derived.getSkillAccessPolicy());
		assertSame(selector, derived.getSkillVariantSelector().orElseThrow());
		assertTrue(explicitPolicy(source));
		assertTrue(explicitPolicy(derived));
	}

	@Test
	void simulatorRetainsBothImplicitAndExplicitSharedDefaultStates() throws Exception {
		for (boolean explicitlyConfigured : List.of(false, true)) {
			McpServer.Builder builder = serverBuilder();
			if (explicitlyConfigured) builder.skillAccessPolicy(McpSkillAccessPolicy.allowAllInstance());
			McpServer source = builder.build();
			DefaultMcpServer derived = SimulatorConfig.fromSokletConfig(
					SokletConfig.withMcpServer(source).build()).simulatedMcpServer();
			assertSame(McpSkillAccessPolicy.allowAllInstance(), derived.getSkillAccessPolicy());
			assertTrue(derived.getSkillVariantSelector().isEmpty());
			assertEquals(explicitlyConfigured, explicitPolicy(derived));
		}
	}

	@Test
	void declaredMultivariantGroupsRequireSelectorBeforeAnyFilteringOrRuntimeSetup() {
		AtomicInteger policyCalls = new AtomicInteger();
		AtomicInteger executorCalls = new AtomicInteger();
		List<McpSkillRegistration> variants = variants();
		McpSkillAccessPolicy oneCandidatePolicy = McpSkillAccessPolicy.fromEvaluators(
				(request, registration, features) -> {
					policyCalls.incrementAndGet();
					return registration == variants.get(0);
				}, (request, registration, features) -> {
					policyCalls.incrementAndGet();
					return true;
				});
		McpEndpoint grouped = endpointBuilder("/grouped").skillGroups(List.of(
				McpSkillGroup.fromKeyAndSkillRegistrations("private-group-canary", variants))).build();
		McpServer.Builder builder = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(
						endpointBuilder("/ordinary").build(), grouped)))
				.skillAccessPolicy(oneCandidatePolicy)
				.requestHandlerExecutorServiceSupplier(() -> {
					executorCalls.incrementAndGet();
					throw new AssertionError("Invalid Skills configuration must fail before executor creation.");
				});
		assertFailure(SELECTOR_REQUIRED, builder::build);
		assertEquals(0, policyCalls.get());
		assertEquals(0, executorCalls.get());
	}

	@Test
	void configuredSelectorAllowsLiveSkillsConstructionAndNullRestoresSelectorGuard() {
		McpEndpoint endpoint = endpointBuilder("/skills").skillGroups(List.of(
				McpSkillGroup.fromKeyAndSkillRegistrations("variants", variants()))).build();
		McpSkillAccessPolicy policy = unusedPolicy();
		McpSkillVariantSelector selector = unusedSelector();
		McpServer.Builder builder = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.skillAccessPolicy(policy).skillVariantSelector(selector);
		McpServer server = assertDoesNotThrow(builder::build);
		assertSame(policy, server.getSkillAccessPolicy());
		assertSame(selector, server.getSkillVariantSelector().orElseThrow());
		assertSame(endpoint, server.getEndpointRegistry().getEndpoints().iterator().next());
		builder.skillVariantSelector(null);
		assertFailure(SELECTOR_REQUIRED, builder::build);
	}

	@Test
	void standaloneAndSingletonNeedNoSelectorAndBuildWithoutCallbacks() {
		McpSkillRegistration registration = variants().get(0);
		McpEndpoint standalone = endpointBuilder("/standalone").skillRegistrations(List.of(registration)).build();
		McpEndpoint singleton = endpointBuilder("/singleton").skillGroups(List.of(
				McpSkillGroup.fromKeyAndSkillRegistrations("one", List.of(registration)))).build();
		for (McpEndpoint endpoint : List.of(standalone, singleton)) {
			McpServer server = assertDoesNotThrow(() -> McpServer.withPort(0)
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.skillAccessPolicy(unusedPolicy()).build());
			assertSame(endpoint, server.getEndpointRegistry().getEndpoints().iterator().next());
			assertDoesNotThrow(() -> SimulatorConfig.fromSokletConfig(
					SokletConfig.withMcpServer(server).build()));
		}

		McpEndpoint empty = endpointBuilder("/empty").skillGroups(List.of(
				McpSkillGroup.fromKeyAndSkillRegistrations("empty", List.of()))).build();
		assertDoesNotThrow(() -> McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(empty))).build());
	}

	@Test
	void simulatorConfigurationRetainsSelectorGuardAndBuildsConfiguredSkillsWithoutCallbacks() {
		McpServer ordinary = serverBuilder().build();
		SokletConfig source = SokletConfig.withMcpServer(ordinary).build();
		McpEndpoint grouped = endpointBuilder("/grouped").skillGroups(List.of(
				McpSkillGroup.fromKeyAndSkillRegistrations("variants", variants()))).build();
		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(List.of(grouped));
		assertFailure(SELECTOR_REQUIRED, () -> SimulatorConfig.withSokletConfig(source)
				.configureMcpServer(builder -> builder.endpointRegistry(registry)));
		McpSkillAccessPolicy policy = unusedPolicy();
		McpSkillVariantSelector selector = unusedSelector();
		SimulatorConfig configured = assertDoesNotThrow(() -> SimulatorConfig.withSokletConfig(source)
				.configureMcpServer(builder -> builder.endpointRegistry(registry)
						.skillAccessPolicy(policy).skillVariantSelector(selector))
				.build());
		DefaultMcpServer simulated = configured.simulatedMcpServer();
		assertSame(registry, simulated.getEndpointRegistry());
		assertSame(policy, simulated.getSkillAccessPolicy());
		assertSame(selector, simulated.getSkillVariantSelector().orElseThrow());
	}

	private static McpServer.Builder serverBuilder() {
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(
				List.of(endpointBuilder("/ordinary").build())));
	}

	private static McpEndpoint.Builder endpointBuilder(String path) {
		return McpEndpoint.withPath(path, McpImplementation.withNameAndVersion("test", "1").build());
	}

	private static McpSkillAccessPolicy unusedPolicy() {
		return McpSkillAccessPolicy.fromEvaluators((request, registration, features) -> {
			throw new AssertionError("Access policy must not run during configuration.");
		}, (request, registration, features) -> {
			throw new AssertionError("Discovery policy must not run during configuration.");
		});
	}

	private static McpSkillVariantSelector unusedSelector() {
		return (request, selection, features) -> {
			throw new AssertionError("Selector must not run during configuration.");
		};
	}

	private static List<McpSkillRegistration> variants() {
		McpSkillBundle bundle = McpSkillBundle.fromFiles(Map.of("SKILL.md",
				"---\nname: sample\ndescription: Sample skill\n---\nBody\n".getBytes(StandardCharsets.UTF_8)));
		return List.of(
				McpSkillRegistration.withUriAndSkillBundle(URI.create("skill://host/en/sample/SKILL.md"), bundle)
						.locale(Locale.ENGLISH).build(),
				McpSkillRegistration.withUriAndSkillBundle(URI.create("skill://host/fr/sample/SKILL.md"), bundle)
						.locale(Locale.FRENCH).build());
	}

	private static boolean explicitPolicy(McpServer server) throws Exception {
		Field field = DefaultMcpServer.class.getDeclaredField("skillAccessPolicyExplicitlyConfigured");
		field.setAccessible(true);
		return field.getBoolean(server);
	}

	private static void assertFailure(String expectedMessage, org.junit.jupiter.api.function.Executable executable) {
		IllegalStateException failure = assertThrows(IllegalStateException.class, executable);
		assertEquals(expectedMessage, failure.getMessage());
		assertNull(failure.getCause());
	}
}
