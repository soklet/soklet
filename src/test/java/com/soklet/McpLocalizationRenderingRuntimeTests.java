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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-network end-to-end evidence that a configured localizer actually renders
 * framework-owned catalog text, fails atomically, and stays wire-neutral.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpLocalizationRenderingRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String WIRE_PATH = "/localization/render";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancelationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownTimeout(Duration.ofSeconds(2))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build();

	@Test
	void localizedTextReplacesEveryPlannedDiscoverySlotOnTheWire() {
		AtomicInteger contexts = new AtomicInteger();
		String body = discover(localizer(contexts, Locale.FRENCH,
				text -> McpLocalizationResult.localized(
						"FR:" + text.getDefaultText())), Set.of());

		assertTrue(body.contains("\"title\":\"FR:Canonical title\""), body);
		assertTrue(body.contains("\"description\":\"FR:Canonical description\""),
				body);
		assertTrue(body.contains(
				"\"instructions\":\"FR:Use canonical instructions.\""), body);
		assertFalse(body.contains("\"Canonical title\""), body);
		assertEquals(1, contexts.get(), "Exactly one context per response.");
	}

	@Test
	void aProviderFailureDiscardsThePartialOverlayAndPublishesCanonicalText() {
		AtomicInteger localizeCalls = new AtomicInteger();
		String body = discover(localizer(new AtomicInteger(), Locale.FRENCH,
				text -> localizeCalls.incrementAndGet() == 1
						? McpLocalizationResult.localized("FR:first")
						: McpLocalizationResult.failure()), Set.of());

		// The first slot succeeded, so a partial overlay existed and was discarded.
		assertFalse(body.contains("FR:first"), body);
		assertTrue(body.contains("\"title\":\"Canonical title\""), body);
		assertTrue(body.contains(
				"\"instructions\":\"Use canonical instructions.\""), body);
	}

	@Test
	void aThrowingProviderContextIsContainedAndNeverReachesTheWire() {
		String body = discover(McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
					throw new IllegalStateException("secret-provider-detail");
				}).build(), Set.of());

		assertFalse(body.contains("secret-provider-detail"), body);
		assertTrue(body.contains("\"title\":\"Canonical title\""), body);
	}

	@Test
	void failRequestPolicyReturnsTheFixedSanitizedInternalError() {
		String body = discover(McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
					throw new IllegalStateException("secret-provider-detail");
				})
				.failurePolicy(McpLocalizationFailurePolicy.FAIL_REQUEST)
				.build(), Set.of());

		assertTrue(body.contains("\"error\""), body);
		assertTrue(body.contains("Internal error"), body);
		assertFalse(body.contains("secret-provider-detail"), body);
		assertFalse(body.contains("Canonical title"), body);
	}

	@Test
	void theProviderSeesTheBoundedPreferenceViewRatherThanTheRawHeader() {
		AtomicReference<List<Locale.LanguageRange>> observed =
				new AtomicReference<>();
		AtomicReference<Locale> fallback = new AtomicReference<>();

		discover(McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
					observed.set(request.getLanguageRanges());
					fallback.set(request.getFallbackLocale());
					assertTrue(request.getResourceListCursor().isEmpty(),
							"Discovery carries no resource-list cursor.");
					return context(Locale.FRENCH,
							text -> McpLocalizationResult.useDefaultText());
				}).build(), Set.of("fr-CA;q=0.8, en-US"));

		assertEquals(List.of("en-us", "fr-ca"), observed.get().stream()
				.map(Locale.LanguageRange::getRange).toList());
		assertEquals(Locale.ENGLISH, fallback.get());
	}

	@Test
	void anOverLimitAcceptLanguageHeaderReachesTheProviderAsAnEmptyPreference() {
		AtomicReference<List<Locale.LanguageRange>> observed =
				new AtomicReference<>();

		discover(McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
					observed.set(request.getLanguageRanges());
					return context(Locale.FRENCH,
							text -> McpLocalizationResult.useDefaultText());
				}).build(), Set.of("en-US" + " ".repeat(4_092)));

		assertEquals(List.of(), observed.get(),
				"Over-limit input must never be truncated into a partial view.");
	}

	private static McpLocalizer localizer(AtomicInteger contexts, Locale locale,
			McpLocalizationLookup localizationLookup) {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
					contexts.incrementAndGet();
					return context(locale, localizationLookup);
				}).build();
	}

	private static McpLocalizationContext context(Locale locale,
			McpLocalizationLookup localizationLookup) {
		return McpLocalizationContext.withLocale(locale, localizationLookup)
				.build();
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void everyNonDiscoveryCatalogRendersItsPlannedSlotsLocalized() {
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> context(Locale.FRENCH,
						text -> McpLocalizationResult.localized(
								"FR:" + text.getDefaultText())))
				.build();

		String tools = capture(richEndpoint(), localizer, "tools/list", Set.of())
				.body();
		assertTrue(tools.contains("\"title\":\"FR:Tool title\""), tools);
		assertTrue(tools.contains("\"description\":\"FR:Tool description\""),
				tools);
		assertTrue(tools.contains("\"title\":\"FR:Annotation title\""), tools);
		assertTrue(tools.contains("\"title\":\"FR:Input title\""), tools);
		assertTrue(tools.contains("\"description\":\"FR:Input description\""),
				tools);
		// Data members named "title" inside default values stay untouched.
		assertTrue(tools.contains("\"title\":\"Not schema text\""), tools);
		assertFalse(tools.contains("\"title\":\"Tool title\""), tools);

		String prompts = capture(richEndpoint(), localizer, "prompts/list",
				Set.of()).body();
		assertTrue(prompts.contains("\"title\":\"FR:Prompt title\""), prompts);
		assertTrue(prompts.contains("\"title\":\"FR:Topic title\""), prompts);

		String resources = capture(richEndpoint(), localizer, "resources/list",
				Set.of()).body();
		assertTrue(resources.contains("\"title\":\"FR:Resource title\""),
				resources);
		assertFalse(resources.contains("FR:Template title"), resources);

		String templates = capture(richEndpoint(), localizer,
				"resources/templates/list", Set.of()).body();
		assertTrue(templates.contains("\"title\":\"FR:Template title\""),
				templates);
		assertFalse(templates.contains("FR:Resource title"), templates);
	}

	@Test
	void aLocalizedResponseKeepsTheCanonicalStatusAndHeaders() {
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> context(Locale.FRENCH,
						text -> McpLocalizationResult.localized(
								"FR:" + text.getDefaultText())))
				.build();
		Capture capture = capture(richEndpoint(), localizer, "server/discover",
				Set.of());

		assertTrue(capture.body().contains("FR:"), capture.body());
		assertEquals(200, capture.statusCode());
		assertEquals(Map.of(
				"Cache-Control", Set.of("no-store"),
				"Content-Type", Set.of("application/json"),
				"Vary", Set.of("Accept-Language"),
				"Content-Language", Set.of("fr")), capture.headers());
	}

	@Test
	void failRequestPublishesStatus500WithTheJsonRpcInternalErrorCode() {
		Capture capture = capture(richEndpoint(),
				McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
							throw new IllegalStateException("secret-provider-detail");
						})
						.failurePolicy(McpLocalizationFailurePolicy.FAIL_REQUEST)
						.build(),
				"server/discover", Set.of());

		assertEquals(500, capture.statusCode());
		assertTrue(capture.body().contains("\"code\":-32603"), capture.body());
		assertTrue(capture.body().contains("\"message\":\"Internal error\""),
				capture.body());
		assertFalse(capture.body().contains("secret-provider-detail"),
				capture.body());
		assertEquals(McpRequestOutcome.INTERNAL_ERROR, capture.outcome(),
				"A localization failure must be observed as INTERNAL_ERROR.");
	}

	@Test
	void aProviderThrownErrorIsContainedAndNeverReachesObservationSurfaces() {
		List<Throwable> observedThrowables = new CopyOnWriteArrayList<>();
		Capture capture = capture(richEndpoint(),
				McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> context(Locale.FRENCH, text -> {
							throw new AssertionError("secret-error-detail");
						}))
						.build(),
				"server/discover", Set.of(), observedThrowables);

		assertEquals(200, capture.statusCode());
		assertTrue(capture.body().contains(
				"\"instructions\":\"Endpoint instructions\""), capture.body());
		assertFalse(capture.body().contains("secret-error-detail"), capture.body());
		assertEquals(List.of(), observedThrowables,
				"A provider Error must never reach lifecycle observation.");
	}

	@Test
	void byteBudgetExhaustionThroughTheRealTransportStopsCallbacksAndFallsBack() {
		// Two 3MB replacements against the production 4MB output ceiling: the
		// second one exhausts the budget, so the third slot's callback never runs.
		String threeMegabytes = "\u4E2D".repeat(1_000_000);
		AtomicInteger localizeCalls = new AtomicInteger();
		Capture capture = capture(richEndpoint(),
				McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> context(Locale.FRENCH, text -> {
							localizeCalls.incrementAndGet();
							return McpLocalizationResult.localized(
									threeMegabytes);
						}))
						.build(),
				"server/discover", Set.of());

		assertEquals(200, capture.statusCode());
		assertTrue(capture.body().contains(
				"\"instructions\":\"Endpoint instructions\""), capture.body());
		assertEquals(2, localizeCalls.get(),
				"Budget exhaustion at the second slot must stop the third.");
	}

	@Test
	void repeatedAcceptLanguageValuesReachTheProviderInWireOrder() {
		AtomicReference<List<Locale.LanguageRange>> observed =
				new AtomicReference<>();
		LinkedHashSet<String> values = new LinkedHashSet<>();
		values.add("en;q=0");
		values.add("en;q=0.9");

		capture(richEndpoint(), McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
					observed.set(request.getLanguageRanges());
					return context(Locale.FRENCH,
							text -> McpLocalizationResult.useDefaultText());
				}).build(), "server/discover", values);

		// First occurrence wins in Locale.LanguageRange.parse, so the q=0
		// exclusion must survive deterministically.
		assertEquals(1, observed.get().size(), observed.get().toString());
		assertEquals("en", observed.get().get(0).getRange());
		assertEquals(0.0d, observed.get().get(0).getWeight(),
				"The q=0 exclusion must be preserved in wire order.");
	}

	@Test
	void anEndpointWithNoLocalizableTextNeverCreatesAContext() {
		AtomicInteger contexts = new AtomicInteger();
		McpEndpoint bare = McpEndpoint.withPath(WIRE_PATH, McpImplementation
						.withNameAndVersion("bare", "1.0").build())
				.build();
		Capture capture = capture(bare,
				McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
							contexts.incrementAndGet();
							return context(Locale.FRENCH,
									text -> McpLocalizationResult.useDefaultText());
						})
						.build(),
				"server/discover", Set.of());

		assertEquals(200, capture.statusCode());
		assertEquals(0, contexts.get(),
				"No localizable text means no plan, no seam, and no context.");
	}

	@Test
	void localizedRenderingLeavesLaterCanonicalRequestsByteIdentical() {
		AtomicReference<String> mode = new AtomicReference<>("localize");
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> context(Locale.FRENCH,
						text -> "localize".equals(mode.get())
								? McpLocalizationResult.localized(
										"FR:" + text.getDefaultText())
								: McpLocalizationResult.useDefaultText()))
				.build();

		McpEndpoint endpoint = richEndpoint();
		List<String> bodies = new CopyOnWriteArrayList<>();

		SokletSimulator.run(SimulatorConfig.builder()
				.mcpServer(0,
						McpEndpointRegistry.fromEndpoints(List.of(endpoint)),
						McpAdmissionController.acceptAllInstance(),
						builder -> configureServer(builder, localizer))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build(), simulator -> {
			bodies.add(awaitBody(simulator, request("server/discover", Set.of())));
			mode.set("default");
			bodies.add(awaitBody(simulator, request("server/discover", Set.of())));
		});

		assertTrue(bodies.get(0).contains("FR:Endpoint instructions"),
				bodies.get(0));

		String canonical = capture(richEndpoint(), null, "server/discover",
				Set.of()).body();
		assertEquals(canonical, bodies.get(1),
				"A localized render must not contaminate the shared canonical "
						+ "document for later requests.");
	}

	@Test
	void concurrentLocalizedRequestsGetIndependentContexts() throws Exception {
		Set<McpLocalizationContext> contexts = ConcurrentHashMap.newKeySet();
		CountDownLatch bothInside = new CountDownLatch(2);
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
					McpLocalizationContext context = context(Locale.FRENCH, text -> {
						bothInside.countDown();
						try {
							bothInside.await(5, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
						return McpLocalizationResult.localized(
								"FR:" + text.getDefaultText());
					});
					contexts.add(context);
					return context;
				})
				.build();

		SokletSimulator.run(SimulatorConfig.builder()
				.mcpServer(0,
						McpEndpointRegistry.fromEndpoints(List.of(richEndpoint())),
						McpAdmissionController.acceptAllInstance(),
						builder -> configureServer(builder, localizer))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build(), simulator -> {
			McpSimulation first = simulator.startMcpRequest(
					request("server/discover", Set.of()));
			McpSimulation second = simulator.startMcpRequest(
					request("server/discover", Set.of()));
			String firstBody = awaitStartedBody(first);
			String secondBody = awaitStartedBody(second);

			assertTrue(firstBody.contains("FR:Endpoint instructions"), firstBody);
			assertEquals(firstBody, secondBody);
		});

		assertEquals(2, contexts.size(),
				"Each concurrent response must create its own context.");
	}

	private record Capture(int statusCode, Map<String, Set<String>> headers,
			String body, McpRequestOutcome outcome) {}

	private static Capture capture(McpEndpoint endpoint, McpLocalizer localizer,
			String method, Set<String> acceptLanguageValues) {
		return capture(endpoint, localizer, method, acceptLanguageValues,
				new CopyOnWriteArrayList<>());
	}

	private static Capture capture(McpEndpoint endpoint, McpLocalizer localizer,
			String method, Set<String> acceptLanguageValues,
			List<Throwable> observedThrowables) {
		AtomicReference<McpRequestOutcome> outcome = new AtomicReference<>();
		AtomicReference<Capture> captured = new AtomicReference<>();

		SokletSimulator.run(SimulatorConfig.builder()
				.mcpServer(0,
						McpEndpointRegistry.fromEndpoints(List.of(endpoint)),
						McpAdmissionController.acceptAllInstance(),
						builder -> configureServer(builder, localizer))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObservers(List.of(new LifecycleObserver() {
					@Override
					public void didFinishMcpRequestHandling(
							@NonNull McpRequestContext context,
							@NonNull McpRequestOutcome requestOutcome,
							@Nullable McpJsonRpcError error,
							@NonNull Duration duration,
							@NonNull List<@NonNull Throwable> throwables) {
						outcome.set(requestOutcome);
						observedThrowables.addAll(throwables);
					}
				}))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build(), simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					request(endpoint.getPath(), method, acceptLanguageValues));

			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT)
						.orElseThrow(() -> new AssertionError("Timed out."));
				simulation.awaitCompletion(WAIT);
				captured.set(new Capture(response.getStatusCode(),
						response.getHeaders(),
						new String(response.getBody().orElseThrow(),
								StandardCharsets.UTF_8), null));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		// The observation callback can trail stream completion; it is guaranteed
		// delivered once the simulator scope has fully drained.
		Capture capture = captured.get();
		return new Capture(capture.statusCode(), capture.headers(),
				capture.body(), outcome.get());
	}

	private static void configureServer(McpServer.Builder builder,
			McpLocalizer localizer) {
		builder.host(LOOPBACK)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));

		if (localizer != null)
			builder.localizer(localizer);
	}

	private static McpEndpoint richEndpoint() {
		McpJsonObject inputSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put("query", McpJsonObject.builder()
								.put("type", "string")
								.put("title", "Input title")
								.put("description", "Input description")
								.put("default", McpJsonObject.builder()
										.put("title", "Not schema text")
										.build())
								.build())
						.build())
				.build();
		McpResourceReadHandler resourceHandler = (request, resource, features) ->
				McpCompleteResult.fromResourceOutput(
						McpResourceOutput.withContent(
								McpTextResourceContents.withUriAndText(
										URI.create("render://unused"), "unused")
										.build())
								.build());
		return McpEndpoint.withPath(WIRE_PATH, McpImplementation
						.withNameAndVersion("localization-render", "1.0")
						.title("Canonical title")
						.description("Canonical description")
						.build())
				.instructions("Endpoint instructions")
				.addTool(McpToolRegistration.withName("render.search")
						.conformanceInputSchema(inputSchema)
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("unused"))
						.title("Tool title")
						.description("Tool description")
						.annotations(McpToolAnnotations.builder()
								.title("Annotation title").build())
						.build())
				.addPrompt(McpPromptRegistration.withName("render.summary")
						.handler((request, context, features) ->
								McpCompleteResult.fromPromptOutput(
										McpPromptOutput.fromMessages()))
						.title("Prompt title")
						.description("Prompt description")
						.addArgument(McpPromptArgumentDeclaration.withName("topic")
								.title("Topic title")
								.description("Topic description")
								.build())
						.build())
				.addResource(McpResourceRegistration.withUriAndName(
						URI.create("render://summary"), "summary")
						.handler(resourceHandler)
						.title("Resource title")
						.description("Resource description")
						.build())
				.addResource(McpResourceRegistration.withUriTemplateAndName(
						"render://item/{id}", "item")
						.handler(resourceHandler)
						.title("Template title")
						.description("Template description")
						.build())
				.build();
	}

	private static String awaitBody(Simulator simulator, Request request) {
		McpSimulation simulation = simulator.startMcpRequest(request);
		return awaitStartedBody(simulation);
	}

	private static String awaitStartedBody(McpSimulation simulation) {
		try {
			McpSimulationResponse response = simulation.awaitResponse(WAIT)
					.orElseThrow(() -> new AssertionError("Timed out."));
			String body = new String(response.getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			simulation.awaitCompletion(WAIT);
			return body;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static Request request(String method, Set<String> acceptLanguage) {
		return request(WIRE_PATH, method, acceptLanguage);
	}

	private static Request request(String path, String method,
			Set<String> acceptLanguage) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"render\","
				+ "\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));

		if (!acceptLanguage.isEmpty())
			headers.put("Accept-Language", acceptLanguage);

		return Request.withPath(HttpMethod.POST, path)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static String discover(McpLocalizer localizer,
			Set<String> acceptLanguageValues) {
		McpEndpoint endpoint = McpEndpoint.withPath(WIRE_PATH, McpImplementation
						.withNameAndVersion("localization-render", "1.0")
						.title("Canonical title")
						.description("Canonical description")
						.build())
				.instructions("Use canonical instructions.")
				.build();
		AtomicReference<String> captured = new AtomicReference<>();

		SokletSimulator.run(SimulatorConfig.builder().mcpServer(0,
				McpEndpointRegistry.fromEndpoints(List.of(endpoint)),
				McpAdmissionController.acceptAllInstance(), builder -> builder
						.host(LOOPBACK)
						.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
						.allowedHosts(Set.of(LOOPBACK))
						.localizer(localizer))
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.build(), simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					discoveryRequest(acceptLanguageValues));

			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT)
						.orElseThrow(() -> new AssertionError("Timed out."));
				captured.set(new String(response.getBody().orElseThrow(),
						StandardCharsets.UTF_8));
				simulation.awaitCompletion(WAIT);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		return captured.get();
	}

	private static Request discoveryRequest(Set<String> acceptLanguageValues) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"render\","
				+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of("server/discover"));

		if (!acceptLanguageValues.isEmpty())
			headers.put("Accept-Language", acceptLanguageValues);

		return Request.withPath(HttpMethod.POST, WIRE_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}
}
