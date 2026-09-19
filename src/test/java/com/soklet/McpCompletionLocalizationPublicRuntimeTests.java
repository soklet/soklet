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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Selected-locale and admitted-tenant isolation for both Completion targets. */
@Timeout(60)
class McpCompletionLocalizationPublicRuntimeTests {
	private static final String HOST = "127.0.0.1";
	private static final String TEMPLATE = "catalog://localized/{subject}/{tone}";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancelationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownTimeout(Duration.ofSeconds(2))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build();

	@Test
	void selectedLocaleAndApplicationFallbackReachBothCompletionTargets()
			throws Exception {
		Observations observations = new Observations();
		McpServer server = server(observations);
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY).build());
		List<LanguageCase> cases = List.of(
				new LanguageCase("fr-CA, en;q=0.5", Locale.FRENCH),
				new LanguageCase("ar", Locale.forLanguageTag("ar")),
				new LanguageCase("de-DE", Locale.ENGLISH),
				new LanguageCase(null, Locale.ENGLISH),
				new LanguageCase("invalid_locale", Locale.ENGLISH),
				new LanguageCase("fr;q=0, en;q=1", Locale.ENGLISH));
		try {
			owner.start();
			HttpClient client = HttpClient.newBuilder().connectTimeout(WAIT).build();
			int index = 0;
			for (boolean prompt : List.of(true, false)) {
				for (LanguageCase language : cases) {
					String id = "locale-" + index++;
					HttpResponse<String> response = client.send(request(server, id,
							prompt, "alpha", language.header()),
							HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
					assertSuggestions(response, "alpha", language.expected());
					observations.assertInvocation(id, prompt, "alpha",
							language.expected());
				}
			}
			assertEquals(12, observations.providerCalls.get());
			assertEquals(0, observations.lookups.get(),
					"Completion strings, including canonical IDs, are application output.");
			assertEquals(0, observations.ordinaryHandlerCalls.get());
		} finally {
			owner.close();
		}
	}

	@Test
	void identicalInputsAndLocaleDoNotShareSuggestionsAcrossAdmittedTenants()
			throws Exception {
		Observations observations = new Observations();
		McpServer server = server(observations);
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY).build());
		List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>();
		try {
			owner.start();
			HttpClient client = HttpClient.newBuilder().connectTimeout(WAIT).build();
			for (boolean prompt : List.of(true, false)) {
				for (String tenant : List.of("alpha", "beta")) {
					String id = (prompt ? "prompt-" : "resource-") + tenant;
					responses.add(client.sendAsync(request(server, id, prompt,
							tenant, "fr-CA"), HttpResponse.BodyHandlers.ofString(
							StandardCharsets.UTF_8)));
				}
			}
			CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new))
					.get(5, TimeUnit.SECONDS);
			int index = 0;
			for (boolean prompt : List.of(true, false)) {
				for (String tenant : List.of("alpha", "beta")) {
					String id = (prompt ? "prompt-" : "resource-") + tenant;
					assertSuggestions(responses.get(index++).getNow(null),
							tenant, Locale.FRENCH);
					observations.assertInvocation(id, prompt, tenant, Locale.FRENCH);
					HttpResponse<String> repeated = client.send(request(server,
							id + "-repeat", prompt, tenant, "fr-CA"),
							HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
					assertSuggestions(repeated, tenant, Locale.FRENCH);
					observations.assertInvocation(id + "-repeat", prompt, tenant,
							Locale.FRENCH);
				}
			}
			assertEquals(8, observations.providerCalls.get());
			assertEquals(8, observations.contexts.values().stream().distinct().count(),
					"Every request owns a fresh localization snapshot, even on reuse.");
			assertEquals(0, observations.lookups.get());
			assertEquals(0, observations.ordinaryHandlerCalls.get());
		} finally {
			for (CompletableFuture<?> response : responses)
				response.cancel(true);
			owner.close();
		}
	}

	private static McpServer server(Observations observations) {
		McpCompletionHandler handler = (request, completion, features) -> {
			String id = id(request);
			McpLocalizationContext context = features.find(
					McpLocalizationContext.class).orElseThrow();
			observations.handlerRequests.put(id, request);
			observations.handlerContexts.put(id, context);
			assertEquals("same-sensitive-prefix", completion.getArgumentValue());
			assertEquals(Map.of("tone", "same-sensitive-context"),
					completion.getContextArguments());
			return McpArgumentCompletionResult.fromValues(List.of(
					machineValue(tenant(request)), naturalValue(context.getLocale())));
		};
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("completion-localization", "1.0")
						.build())
				.addPrompt(McpPromptRegistration.withName("localized")
						.handler((request, prompt, features) -> {
							observations.ordinaryHandlerCalls.incrementAndGet();
							return McpCompleteResult.fromPromptOutput(
									McpPromptOutput.fromMessages());
						})
						.addArgument(McpPromptArgumentDeclaration.withName("subject").build())
						.addArgument(McpPromptArgumentDeclaration.withName("tone").build())
						.completionHandler(handler).build())
				.addResource(McpResourceRegistration.withUriTemplateAndName(
						TEMPLATE, "Localized")
						.handler((request, resource, features) -> {
							observations.ordinaryHandlerCalls.incrementAndGet();
							return McpCompleteResult.fromResourceOutput(
									McpResourceOutput.withContent(McpTextResourceContents
											.withUriAndText(resource.getUri(), "unused")
											.build()).build());
						}).completionHandler(handler).build()).build();
		return McpServer.withPort(0).host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> {
					String tenant = context.getRequest().getHeader("X-Test-Tenant")
							.orElseThrow();
					return McpAdmissionDecision.accepted(McpAdmissionIdentity
							.withRateLimitPartitionKey("rate-" + tenant)
							.authorizationPartitionKey("auth-" + tenant)
							.principal(tenant).build());
				})
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.localizer(McpLocalizer.withFallbackLocale(Locale.ENGLISH,
						observations::localizationContext).build())
				.catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators(
						(request, registration, features) -> true,
						(request, registration, features) -> {
							observations.policyRequests.put(id(request), request);
							observations.policyContexts.put(id(request), features.find(
									McpLocalizationContext.class).orElseThrow());
							return true;
						}))
				.handlerInterceptor((request, features, continuation) -> {
					observations.interceptorRequests.put(id(request), request);
					observations.interceptorContexts.put(id(request), features.find(
							McpLocalizationContext.class).orElseThrow());
					return continuation.proceed();
				})
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST)).build();
	}

	private static HttpRequest request(McpServer server, String id,
			boolean prompt, String tenant, String language) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"completion/complete\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},\"ref\":"
				+ (prompt ? "{\"type\":\"ref/prompt\",\"name\":\"localized\"}"
				: "{\"type\":\"ref/resource\",\"uri\":\"" + TEMPLATE + "\"}")
				+ ",\"argument\":{\"name\":\"subject\",\"value\":\"same-sensitive-prefix\"},"
				+ "\"context\":{\"arguments\":{\"tone\":\"same-sensitive-context\"}}}}";
		int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
		HttpRequest.Builder builder = HttpRequest.newBuilder(
				URI.create("http://" + HOST + ':' + port + "/mcp"))
				.timeout(WAIT)
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("X-Test-Tenant", tenant)
				.header("MCP-Protocol-Version", "2026-07-28")
				.header("Mcp-Method", "completion/complete")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (language != null)
			builder.header("Accept-Language", language);
		return builder.build();
	}

	private static void assertSuggestions(HttpResponse<String> response,
			String tenant, Locale locale) {
		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.body().contains("\"values\":[\"" + machineValue(tenant)
				+ "\",\"" + naturalValue(locale) + "\"]"), response.body());
		assertFalse(response.body().contains(machineValue(
				"alpha".equals(tenant) ? "beta" : "alpha")), response.body());
		assertFalse(response.body().contains("same-sensitive-prefix"), response.body());
		assertFalse(response.body().contains("same-sensitive-context"), response.body());
	}

	private static String machineValue(String tenant) {
		return "CANONICAL-ID::" + tenant + "::FR-07";
	}

	private static String naturalValue(Locale locale) {
		return switch (locale.getLanguage()) {
			case "fr" -> "été";
			case "ar" -> "مرحبا";
			default -> "hello";
		};
	}

	private static String id(McpRequestContext request) {
		return request.getRequestId().orElseThrow().asString().orElseThrow();
	}

	private static String tenant(McpRequestContext request) {
		return (String) request.getAdmissionIdentity().getPrincipal().orElseThrow();
	}

	private record LanguageCase(String header, Locale expected) {}

	private static final class Observations {
		private final AtomicInteger providerCalls = new AtomicInteger();
		private final AtomicInteger lookups = new AtomicInteger();
		private final AtomicInteger ordinaryHandlerCalls = new AtomicInteger();
		private final Map<String, McpLocalizationContext> contexts = new ConcurrentHashMap<>();
		private final Map<String, McpRequestContext> providerRequests = new ConcurrentHashMap<>();
		private final Map<String, McpLocalizationContext> policyContexts = new ConcurrentHashMap<>();
		private final Map<String, McpRequestContext> policyRequests = new ConcurrentHashMap<>();
		private final Map<String, McpLocalizationContext> interceptorContexts = new ConcurrentHashMap<>();
		private final Map<String, McpRequestContext> interceptorRequests = new ConcurrentHashMap<>();
		private final Map<String, McpLocalizationContext> handlerContexts = new ConcurrentHashMap<>();
		private final Map<String, McpRequestContext> handlerRequests = new ConcurrentHashMap<>();

		private McpLocalizationContext localizationContext(McpLocalizationRequest request) {
			providerCalls.incrementAndGet();
			assertEquals(McpOperationType.COMPLETION_COMPLETE,
					request.getRequestContext().getOperationType());
			assertEquals(Locale.ENGLISH, request.getFallbackLocale());
			assertTrue(request.getContinuationLocale().isEmpty());
			assertTrue(request.getResourceListCursor().isEmpty());
			assertThrows(UnsupportedOperationException.class,
					() -> request.getLanguageRanges().add(new Locale.LanguageRange("de")));
			Locale selected = Locale.lookup(request.getLanguageRanges(),
					List.of(Locale.FRENCH, Locale.ENGLISH, Locale.forLanguageTag("ar")));
			if (selected == null)
				selected = request.getFallbackLocale();
			McpLocalizationContext context = McpLocalizationContext.withLocale(selected,
					text -> {
						lookups.incrementAndGet();
						return McpLocalizationResult.localized("DO-NOT-TRANSLATE-SUGGESTIONS");
					}).build();
			String id = id(request.getRequestContext());
			assertNull(contexts.putIfAbsent(id, context), "One context per request.");
			providerRequests.put(id, request.getRequestContext());
			return context;
		}

		private void assertInvocation(String id, boolean prompt, String tenant,
				Locale locale) {
			McpLocalizationContext context = contexts.get(id);
			assertNotNull(context);
			assertEquals(locale, context.getLocale());
			assertSame(context, interceptorContexts.get(id));
			assertSame(context, handlerContexts.get(id));
			McpRequestContext request = providerRequests.get(id);
			assertSame(request, interceptorRequests.get(id));
			assertSame(request, handlerRequests.get(id));
			assertEquals(tenant, tenant(request));
			assertEquals("auth-" + tenant, request.getAdmissionIdentity()
					.getAuthorizationPartitionKey().orElseThrow());
			if (prompt) {
				assertSame(context, policyContexts.get(id));
				assertSame(request, policyRequests.get(id));
			} else {
				assertFalse(policyContexts.containsKey(id));
			}
		}
	}
}
