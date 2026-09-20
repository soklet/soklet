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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCatalogAccessPolicyTests {
	private final McpRequestContext requestContext = (McpRequestContext)
			Proxy.newProxyInstance(McpRequestContext.class.getClassLoader(),
					new Class<?>[] { McpRequestContext.class },
					(proxy, method, arguments) -> {
						throw new AssertionError("Unexpected context access: " + method.getName());
					});
	private final McpInvocationFeatures invocationFeatures =
			McpInvocationFeatures.fromFeatures(Map.of());
	private final McpToolRegistration<McpJsonObject> toolRegistration =
			McpToolRegistration.withName("tool").jsonObjectArguments()
					.handler((requestContext, toolArguments, invocationFeatures) ->
							McpCompleteResult.fromToolText("result")).build();
	private final McpPromptRegistration promptRegistration =
			McpPromptRegistration.withName("prompt")
					.handler((requestContext, promptGetContext, invocationFeatures) ->
							McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages()))
					.build();

	@Test
	void defaultIsSharedAndPermitsBothKinds() throws Exception {
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.allowAllInstance();
		assertSame(policy, McpCatalogAccessPolicy.allowAllInstance());
		assertTrue(policy.isToolAccessible(requestContext, toolRegistration, invocationFeatures));
		assertTrue(policy.isPromptAccessible(requestContext, promptRegistration, invocationFeatures));
	}

	@Test
	void factoriesRequireBothEvaluators() {
		assertThrows(NullPointerException.class, () ->
				McpCatalogAccessPolicy.fromEvaluators(null, (context, registration, features) -> true));
		assertThrows(NullPointerException.class, () ->
				McpCatalogAccessPolicy.fromEvaluators((context, registration, features) -> true, null));
	}

	@Test
	void dispatchRejectsNullInputsBeforeCallingEvaluators() {
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					throw new AssertionError("Invalid tool input reached evaluator");
				}, (context, registration, features) -> {
					throw new AssertionError("Invalid prompt input reached evaluator");
				});
		assertThrows(NullPointerException.class, () ->
				policy.isToolAccessible(null, toolRegistration, invocationFeatures));
		assertThrows(NullPointerException.class, () ->
				policy.isToolAccessible(requestContext, null, invocationFeatures));
		assertThrows(NullPointerException.class, () ->
				policy.isToolAccessible(requestContext, toolRegistration, null));
		assertThrows(NullPointerException.class, () ->
				policy.isPromptAccessible(null, promptRegistration, invocationFeatures));
		assertThrows(NullPointerException.class, () ->
				policy.isPromptAccessible(requestContext, null, invocationFeatures));
		assertThrows(NullPointerException.class, () ->
				policy.isPromptAccessible(requestContext, promptRegistration, null));
	}

	@Test
	void evaluatorsReceiveExactInputsOnceAndMayDeny() throws Exception {
		AtomicInteger toolCalls = new AtomicInteger();
		AtomicInteger promptCalls = new AtomicInteger();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					assertSame(requestContext, context);
					assertSame(toolRegistration, registration);
					assertSame(invocationFeatures, features);
					toolCalls.incrementAndGet();
					return false;
				}, (context, registration, features) -> {
					assertSame(requestContext, context);
					assertSame(promptRegistration, registration);
					assertSame(invocationFeatures, features);
					promptCalls.incrementAndGet();
					return true;
				});
		assertFalse(policy.isToolAccessible(requestContext, toolRegistration, invocationFeatures));
		assertTrue(policy.isPromptAccessible(requestContext, promptRegistration, invocationFeatures));
		assertEquals(1, toolCalls.get());
		assertEquals(1, promptCalls.get());
	}

	@Test
	void nullResultsAndCheckedExceptionsNeverBecomePermission() {
		McpCatalogAccessPolicy nullPolicy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> null,
				(context, registration, features) -> null);
		assertThrows(NullPointerException.class, () -> nullPolicy.isToolAccessible(
				requestContext, toolRegistration, invocationFeatures));
		assertThrows(NullPointerException.class, () -> nullPolicy.isPromptAccessible(
				requestContext, promptRegistration, invocationFeatures));
		Exception failure = new Exception("private evaluator failure");
		McpCatalogAccessPolicy throwingPolicy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> { throw failure; },
				(context, registration, features) -> { throw failure; });
		assertSame(failure, assertThrows(Exception.class, () -> throwingPolicy.isToolAccessible(
				requestContext, toolRegistration, invocationFeatures)));
		assertSame(failure, assertThrows(Exception.class, () -> throwingPolicy.isPromptAccessible(
				requestContext, promptRegistration, invocationFeatures)));
	}

	@Test
	void identityAndRenderingDoNotInspectEvaluatorCapabilities() {
		McpCatalogAccessPolicy.ToolAccessEvaluator evaluator =
				(context, registration, features) -> true;
		McpCatalogAccessPolicy.PromptAccessEvaluator promptEvaluator =
				(context, registration, features) -> true;
		McpCatalogAccessPolicy first = McpCatalogAccessPolicy.fromEvaluators(evaluator, promptEvaluator);
		McpCatalogAccessPolicy second = McpCatalogAccessPolicy.fromEvaluators(evaluator, promptEvaluator);
		assertNotEquals(first, second);
		assertEquals("McpCatalogAccessPolicy{evaluators=<redacted>}", first.toString());
	}

	@Test
	void serverConfigurationUsesSharedDefaultAndNullRestoresIt() {
		McpCatalogAccessPolicy configured = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> false,
				(context, registration, features) -> false);
		McpServer defaultServer = serverBuilder().build();
		McpServer configuredServer = serverBuilder()
				.catalogAccessPolicy(configured)
				.build();
		McpServer resetServer = serverBuilder()
				.catalogAccessPolicy(configured)
				.catalogAccessPolicy(null)
				.build();

		assertSame(McpCatalogAccessPolicy.allowAllInstance(),
				defaultServer.getCatalogAccessPolicy());
		assertSame(configured, configuredServer.getCatalogAccessPolicy());
		assertSame(McpCatalogAccessPolicy.allowAllInstance(),
				resetServer.getCatalogAccessPolicy());
	}

	@Test
	void explicitPolicyDefersAggregateToolCatalogNodeBudget() {
		McpJsonObject.Builder metadataBuilder = McpJsonObject.builder();
		for (int index = 0; index < 1_000; ++index)
			metadataBuilder.put("field-" + index, "value");
		McpJsonObject metadata = metadataBuilder.build();
		McpEndpoint.Builder endpointBuilder = McpEndpoint.withPath(
				"/caller-filtered-node-budget", McpImplementation
						.withNameAndVersion("caller-filtered-node-budget", "4.0.0")
						.build())
				.serverInfoIncluded(false);
		List<McpToolRegistration<?>> toolRegistrations = new ArrayList<>();
		for (int index = 0; index < 100; ++index)
			toolRegistrations.add(McpToolRegistration
					.withName("hidden." + index)
					.jsonObjectArguments()
					.handler((request, arguments, features) ->
							McpCompleteResult.fromToolText("unused"))
					.metadata(metadata)
					.build());
		endpointBuilder.toolRegistrations(toolRegistrations);
		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(
				List.of(endpointBuilder.build()));

		IllegalArgumentException staticFailure = assertThrows(
				IllegalArgumentException.class,
				() -> McpServer.withPort(0)
						.endpointRegistry(registry)
						.toolRateLimiter(
								context -> McpRateLimitDecision.allowed())
						.build());
		assertTrue(staticFailure.getMessage().contains("node limit"),
				staticFailure.getMessage());

		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> false,
				(context, registration, features) -> false);
		McpServer callerFiltered = McpServer.withPort(0)
				.endpointRegistry(registry)
				.catalogAccessPolicy(policy)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.build();
		assertSame(policy, callerFiltered.getCatalogAccessPolicy());
	}

	private static McpServer.Builder serverBuilder() {
		McpEndpoint endpoint = McpEndpoint.withPath("/catalog-access-policy",
				McpImplementation.withNameAndVersion(
						"catalog-access-policy-tests", "4.0.0").build())
				.build();
		return McpServer.withPort(0).endpointRegistry(
				McpEndpointRegistry.fromEndpoints(List.of(endpoint)));
	}
}
