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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public-boundary evidence for caller filtering followed by stable-identity
 * localization remapping.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpCatalogAccessLocalizationPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String WIRE_PATH = "/catalog-access/localization";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancelationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownTimeout(Duration.ofSeconds(2))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build();
	private static final Map<String, Set<String>> ALLOWED_TOOLS = Map.of(
			"alpha", Set.of("tool.alpha", "tool.shared"),
			"beta", Set.of("tool.neutral", "tool.shared", "tool.beta"),
			"zero", Set.of("tool.neutral"));
	private static final Map<String, Set<String>> ALLOWED_PROMPTS = Map.of(
			"alpha", Set.of("prompt.alpha", "prompt.shared"),
			"beta", Set.of("prompt.neutral", "prompt.shared", "prompt.beta"),
			"zero", Set.of("prompt.neutral"));

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void filteredTenantCatalogsLocalizeOnlySurvivingStableOwners() {
		Observations observations = new Observations();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(request, registration, features) -> {
					observations.observePolicyContext(request, features);
					return ALLOWED_TOOLS.get(tenant(request))
							.contains(registration.getName());
				},
				(request, registration, features) -> {
					observations.observePolicyContext(request, features);
					return ALLOWED_PROMPTS.get(tenant(request))
							.contains(registration.getName());
				});
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				observations::localizationContext).build();
		Map<String, Capture> captures = new LinkedHashMap<>();

		SokletSimulator.run(SimulatorConfig.builder()
				.configureMcpServer(builder -> builder
						.port(0)
						.endpointRegistry(McpEndpointRegistry.fromEndpoints(
								List.of(endpoint())))
						.admissionController(context -> {
							String authorization = context.getRequest()
									.getHeader("Authorization").orElseThrow();
							String tenant = authorization.substring("Bearer ".length());
							return McpAdmissionDecision.accepted(McpAdmissionIdentity
									.withRateLimitPartitionKey("rate-" + tenant)
									.authorizationPartitionKey("auth-" + tenant)
									.principal(tenant)
									.build());
						})
						.catalogAccessPolicy(policy)
						.localizer(localizer)
						.subscriptionAuthorizer(
								McpSubscriptionAuthorizer.denyAllInstance())
						.host(LOOPBACK)
						.requestRateLimiter(context -> McpRateLimitDecision.allowed())
						.toolRateLimiter(context -> McpRateLimitDecision.allowed())
						.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
						.allowedHosts(Set.of(LOOPBACK)))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build(), simulator -> {
			for (String tenant : List.of("alpha", "beta", "zero")) {
				for (String method : List.of("tools/list", "prompts/list")) {
					String key = requestKey(tenant, method);
					captures.put(key, execute(simulator,
							request(tenant, method)));
				}
			}
		});

		assertToolProjections(captures);
		assertPromptProjections(captures);
		assertLookupIsolation(observations);
		assertContextReuse(observations);
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void explicitPolicyAppliesLocalizationBudgetToExactProjection() {
		McpEndpoint endpoint = McpEndpoint.withPath(WIRE_PATH, McpImplementation
						.withNameAndVersion("catalog-localization-budget", "1")
						.build())
				.serverInfoIncluded(false)
				.addTool(tool("budget.visible", "Visible title"))
				.addTool(tool("budget.hidden", "Hidden title"))
				.build();
		AtomicInteger providerCalls = new AtomicInteger();
		AtomicInteger lookupCalls = new AtomicInteger();
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> {
					providerCalls.incrementAndGet();
					return McpLocalizationContext.withLocale(Locale.FRENCH,
							text -> {
								lookupCalls.incrementAndGet();
								return McpLocalizationResult.localized(
										"L[" + text.getDefaultText() + "]");
							}).build();
				})
				.failurePolicy(McpLocalizationFailurePolicy.FAIL_REQUEST)
				.maximumLocalizableTextCountPerResponse(1)
				.build();
		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(
				List.of(endpoint));

		assertThrows(IllegalStateException.class, () -> McpServer.withPort(0)
				.endpointRegistry(registry)
				.localizer(localizer)
				.subscriptionAuthorizer(
						McpSubscriptionAuthorizer.denyAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.build());

		McpCatalogAccessPolicy filteredPolicy =
				McpCatalogAccessPolicy.fromEvaluators(
						(request, registration, features) -> registration
								.getName().equals("budget.visible"),
						(request, registration, features) -> true);
		Capture filtered = executeToolList(endpoint, filteredPolicy, localizer,
				"filtered-budget");
		assertEquals(200, filtered.statusCode(), filtered.body());
		assertTrue(filtered.body().contains(
				"\"name\":\"budget.visible\",\"title\":\"L[Visible title]\""),
				filtered.body());
		assertFalse(filtered.body().contains("budget.hidden"), filtered.body());
		assertEquals(1, providerCalls.get());
		assertEquals(1, lookupCalls.get());

		providerCalls.set(0);
		lookupCalls.set(0);
		Capture overBudget = executeToolList(endpoint,
				McpCatalogAccessPolicy.allowAllInstance(), localizer,
				"over-budget");
		assertEquals(500, overBudget.statusCode(), overBudget.body());
		assertFalse(overBudget.body().contains("budget.visible"),
				overBudget.body());
		assertFalse(overBudget.body().contains("budget.hidden"),
				overBudget.body());
		assertEquals(1, providerCalls.get(),
				"The policy and projection-bound check share one request context.");
		assertEquals(0, lookupCalls.get(),
				"An over-budget projection must not begin localization lookups.");
	}

	private static void assertToolProjections(Map<String, Capture> captures) {
		String alpha = successBody(captures, "alpha", "tools/list");
		assertRelativeOrder(alpha, "tool.alpha", "tool.shared");
		assertFalse(alpha.contains("\"name\":\"tool.neutral\""), alpha);
		assertFalse(alpha.contains("\"name\":\"tool.beta\""), alpha);
		assertTrue(alpha.contains("\"name\":\"tool.alpha\",\"title\":"
				+ "\"L[alpha|tool.alpha|/title]\""), alpha);
		assertTrue(alpha.contains("\"name\":\"tool.shared\",\"title\":"
				+ "\"L[alpha|tool.shared|/title]\""), alpha);

		String beta = successBody(captures, "beta", "tools/list");
		assertRelativeOrder(beta, "tool.neutral", "tool.shared", "tool.beta");
		assertFalse(beta.contains("\"name\":\"tool.alpha\""), beta);
		assertTrue(beta.contains("\"name\":\"tool.shared\",\"title\":"
				+ "\"L[beta|tool.shared|/title]\""), beta);
		assertTrue(beta.contains("\"name\":\"tool.beta\",\"title\":"
				+ "\"L[beta|tool.beta|/title]\""), beta);

		String zero = successBody(captures, "zero", "tools/list");
		assertTrue(zero.contains("\"name\":\"tool.neutral\""), zero);
		assertFalse(zero.contains("\"name\":\"tool.alpha\""), zero);
		assertFalse(zero.contains("\"name\":\"tool.shared\""), zero);
		assertFalse(zero.contains("\"name\":\"tool.beta\""), zero);
		assertFalse(zero.contains("L["), zero);
	}

	private static void assertPromptProjections(Map<String, Capture> captures) {
		String alpha = successBody(captures, "alpha", "prompts/list");
		assertRelativeOrder(alpha, "prompt.alpha", "prompt.shared");
		assertFalse(alpha.contains("\"name\":\"prompt.neutral\""), alpha);
		assertFalse(alpha.contains("\"name\":\"prompt.beta\""), alpha);
		assertTrue(alpha.contains("\"name\":\"prompt.alpha\",\"title\":"
				+ "\"L[alpha|prompt.alpha|/title]\""), alpha);
		assertTrue(alpha.contains("\"name\":\"first\",\"title\":"
				+ "\"L[alpha|prompt.shared|/arguments/first/title]\""), alpha);
		assertTrue(alpha.contains("\"name\":\"second\",\"title\":"
				+ "\"L[alpha|prompt.shared|/arguments/second/title]\""), alpha);

		String beta = successBody(captures, "beta", "prompts/list");
		assertRelativeOrder(beta, "prompt.neutral", "prompt.shared", "prompt.beta");
		assertFalse(beta.contains("\"name\":\"prompt.alpha\""), beta);
		assertTrue(beta.contains("\"name\":\"prompt.shared\",\"title\":"
				+ "\"L[beta|prompt.shared|/title]\""), beta);
		assertTrue(beta.contains("\"name\":\"first\",\"title\":"
				+ "\"L[beta|prompt.shared|/arguments/first/title]\""), beta);
		assertTrue(beta.contains("\"name\":\"prompt.beta\",\"title\":"
				+ "\"L[beta|prompt.beta|/title]\""), beta);

		String zero = successBody(captures, "zero", "prompts/list");
		assertTrue(zero.contains("\"name\":\"prompt.neutral\""), zero);
		assertFalse(zero.contains("\"name\":\"prompt.alpha\""), zero);
		assertFalse(zero.contains("\"name\":\"prompt.shared\""), zero);
		assertFalse(zero.contains("\"name\":\"prompt.beta\""), zero);
		assertFalse(zero.contains("L["), zero);
	}

	private static void assertLookupIsolation(Observations observations) {
		assertEquals(Set.of("tool.alpha", "tool.shared"),
				observations.localizedOwners("alpha", "tools/list"));
		assertEquals(Set.of("tool.shared", "tool.beta"),
				observations.localizedOwners("beta", "tools/list"));
		assertEquals(Set.of(),
				observations.localizedOwners("zero", "tools/list"));
		assertEquals(Set.of("prompt.alpha", "prompt.shared"),
				observations.localizedOwners("alpha", "prompts/list"));
		assertEquals(Set.of("prompt.shared", "prompt.beta"),
				observations.localizedOwners("beta", "prompts/list"));
		assertEquals(Set.of(),
				observations.localizedOwners("zero", "prompts/list"));
	}

	private static void assertContextReuse(Observations observations) {
		for (String tenant : List.of("alpha", "beta", "zero")) {
			for (String method : List.of("tools/list", "prompts/list")) {
				String key = requestKey(tenant, method);
				assertEquals(1, observations.providerCalls(key),
						"One localization context must serve policy and rendering for "
								+ key);
				McpLocalizationContext providerContext =
						observations.providerContext(key);
				assertFalse(observations.policyContexts(key).isEmpty(), key);
				for (McpLocalizationContext policyContext
						: observations.policyContexts(key))
					assertSame(providerContext, policyContext, key);
				for (McpLocalizationContext lookupContext
						: observations.lookupContexts(key))
					assertSame(providerContext, lookupContext, key);
			}
		}
	}

	private static String successBody(Map<String, Capture> captures,
			String tenant, String method) {
		Capture capture = captures.get(requestKey(tenant, method));
		assertEquals(200, capture.statusCode(), capture.body());
		return capture.body();
	}

	private static void assertRelativeOrder(String body, String... names) {
		int previous = -1;
		for (String name : names) {
			int position = body.indexOf("\"name\":\"" + name + "\"");
			assertTrue(position > previous,
					"Expected canonical relative order for " + List.of(names)
							+ " in " + body);
			previous = position;
		}
	}

	private static McpEndpoint endpoint() {
		return McpEndpoint.withPath(WIRE_PATH, McpImplementation
						.withNameAndVersion("catalog-access-localization", "1")
						.build())
				.addTool(tool("tool.alpha", "Alpha tool"))
				.addTool(tool("tool.neutral", null))
				.addTool(tool("tool.shared", "Shared tool"))
				.addTool(tool("tool.beta", "Beta tool"))
				.addPrompt(prompt("prompt.alpha", "Alpha prompt", List.of(
						argument("alpha", "Alpha argument"))))
				.addPrompt(prompt("prompt.neutral", null, List.of()))
				.addPrompt(prompt("prompt.shared", "Shared prompt", List.of(
						argument("first", "First argument"),
						argument("second", "Second argument"))))
				.addPrompt(prompt("prompt.beta", "Beta prompt", List.of(
						argument("beta", "Beta argument"))))
				.build();
	}

	private static McpToolRegistration<McpJsonObject> tool(String name,
			String title) {
		McpToolRegistration.OperationBuilder<McpJsonObject> builder =
				McpToolRegistration.withName(name)
						.jsonObjectArguments()
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("unused"));
		if (title != null)
			builder.title(title);
		return builder.build();
	}

	private static McpPromptRegistration prompt(String name, String title,
			List<McpPromptArgumentDeclaration> arguments) {
		McpPromptRegistration.Builder builder = McpPromptRegistration.withName(name)
				.handler((request, prompt, features) ->
						McpCompleteResult.fromPromptOutput(
								McpPromptOutput.fromMessages()));
		if (title != null)
			builder.title(title);
		for (McpPromptArgumentDeclaration argument : arguments)
			builder.addArgument(argument);
		return builder.build();
	}

	private static McpPromptArgumentDeclaration argument(String name,
			String title) {
		return McpPromptArgumentDeclaration.withName(name)
				.title(title)
				.build();
	}

	private static Request request(String tenant, String method) {
		String id = tenant + "-" + method.replace('/', '-');
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\","
				+ "\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		return Request.withPath(HttpMethod.POST, WIRE_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Authorization", Set.of("Bearer " + tenant),
						"Content-Type", Set.of(
								"application/json; charset=UTF-8"),
						"Accept", Set.of("application/json, text/event-stream"),
						"Accept-Language", Set.of("fr"),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of(method)))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static Capture execute(Simulator simulator, Request request) {
		McpSimulation simulation = simulator.startMcpRequest(request);
		try {
			McpSimulationResponse response = simulation.awaitResponse(WAIT)
					.orElseThrow(() -> new AssertionError("Timed out awaiting response."));
			String body = new String(response.getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			simulation.awaitCompletion(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting completion."));
			return new Capture(response.getStatusCode(), body);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static Capture executeToolList(@NonNull McpEndpoint endpoint,
			@NonNull McpCatalogAccessPolicy policy,
			@NonNull McpLocalizer localizer, @NonNull String tenant) {
		AtomicReference<Capture> capture = new AtomicReference<>();
		SokletSimulator.run(SimulatorConfig.builder()
				.configureMcpServer(builder -> builder
						.port(0)
						.endpointRegistry(McpEndpointRegistry.fromEndpoints(
								List.of(endpoint)))
						.admissionController(context -> McpAdmissionDecision.accepted(
								McpAdmissionIdentity
										.withRateLimitPartitionKey("rate-" + tenant)
										.authorizationPartitionKey("auth-" + tenant)
										.principal(tenant)
										.build()))
						.catalogAccessPolicy(policy)
						.localizer(localizer)
						.subscriptionAuthorizer(
								McpSubscriptionAuthorizer.denyAllInstance())
						.host(LOOPBACK)
						.requestRateLimiter(
								context -> McpRateLimitDecision.allowed())
						.toolRateLimiter(
								context -> McpRateLimitDecision.allowed())
						.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
						.allowedHosts(Set.of(LOOPBACK)))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build(), simulator -> capture.set(execute(simulator,
						request(tenant, "tools/list"))));
		return capture.get();
	}

	private static String tenant(McpRequestContext context) {
		return (String) context.getAdmissionIdentity().getPrincipal().orElseThrow();
	}

	private static String requestKey(McpRequestContext context) {
		return requestKey(tenant(context), context.getJsonRpcMethod());
	}

	private static String requestKey(String tenant, String method) {
		return tenant + "|" + method;
	}

	private record Capture(int statusCode, String body) {
	}

	private record LookupObservation(String requestKey, String ownerId,
			McpLocalizationContext context) {
	}

	private static final class Observations {
		private final Map<String, AtomicInteger> providerCalls =
				new ConcurrentHashMap<>();
		private final Map<String, McpLocalizationContext> providerContexts =
				new ConcurrentHashMap<>();
		private final Map<String, List<McpLocalizationContext>> policyContexts =
				new ConcurrentHashMap<>();
		private final List<LookupObservation> lookups =
				new CopyOnWriteArrayList<>();

		private McpLocalizationContext localizationContext(
				McpLocalizationRequest request) {
			String key = requestKey(request.getRequestContext());
			this.providerCalls.computeIfAbsent(key,
					ignored -> new AtomicInteger()).incrementAndGet();
			AtomicReference<McpLocalizationContext> contextReference =
					new AtomicReference<>();
			String tenant = tenant(request.getRequestContext());
			McpLocalizationContext context = McpLocalizationContext.withLocale(
					Locale.FRENCH, text -> {
						McpLocalizationContext current = contextReference.get();
						this.lookups.add(new LookupObservation(key,
								text.getCoordinate().getSubjectId(), current));
						return McpLocalizationResult.localized("L[" + tenant + "|"
								+ text.getCoordinate().getSubjectId() + "|"
								+ text.getCoordinate().getMemberPath() + "]");
					}).build();
			contextReference.set(context);
			this.providerContexts.putIfAbsent(key, context);
			return context;
		}

		private void observePolicyContext(McpRequestContext request,
				McpInvocationFeatures features) {
			this.policyContexts.computeIfAbsent(requestKey(request),
					ignored -> new CopyOnWriteArrayList<>())
					.add(features.find(McpLocalizationContext.class).orElseThrow());
		}

		private int providerCalls(String key) {
			AtomicInteger calls = this.providerCalls.get(key);
			return calls == null ? 0 : calls.get();
		}

		private McpLocalizationContext providerContext(String key) {
			return this.providerContexts.get(key);
		}

		private List<McpLocalizationContext> policyContexts(String key) {
			return List.copyOf(this.policyContexts.getOrDefault(key, List.of()));
		}

		private List<McpLocalizationContext> lookupContexts(String key) {
			return this.lookups.stream()
					.filter(observation -> observation.requestKey().equals(key))
					.map(LookupObservation::context)
					.toList();
		}

		private Set<String> localizedOwners(String tenant, String method) {
			String key = requestKey(tenant, method);
			return Set.copyOf(this.lookups.stream()
					.filter(observation -> observation.requestKey().equals(key))
					.map(LookupObservation::ownerId)
					.toList());
		}
	}
}
