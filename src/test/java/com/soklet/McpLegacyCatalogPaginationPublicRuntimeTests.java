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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Public runtime coverage for framework-owned pagination on both exact 2025 views. */
@Timeout(120)
public class McpLegacyCatalogPaginationPublicRuntimeTests {
	private static final String HOST = "127.0.0.1";
	private static final String PATH = "/mcp";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final List<McpProtocolVersion> LEGACY = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> LEGACY_SET = Set.copyOf(LEGACY);
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final LifecyclePolicy LIFECYCLE = LifecyclePolicy.builder()
			.startupTimeout(WAIT).startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@Test
	public void catalogsThatFitRemainOneCompletePageInCanonicalOrder() {
		McpEndpoint endpoint = catalog(PATH, LEGACY_SET, 129, 3, 3, 3, false, null);
		run(List.of(endpoint), UnaryOperator.identity(), simulator -> {
			for (McpProtocolVersion version : LEGACY)
				for (Kind kind : Kind.values()) {
					Page page = page(execute(simulator, request(PATH, version, kind, null, "en")), kind);
					assertEquals(expected(kind, kind == Kind.TOOLS ? 129 : 3), page.identities());
					assertNull(page.cursor(), "A fitting catalog must not acquire an arbitrary page split.");
					assertFalse(page.capture().body().contains("resultType"), page.capture().body());
					assertError(execute(simulator, requestWithParams(PATH, version, kind,
							"\"cursor\":\"\"", "en")), -32602);
					assertError(execute(simulator, requestWithParams(PATH, version, kind,
							"\"cursor\":3", "en")), -32602);
					assertError(execute(simulator, requestWithParams(PATH, version, kind,
							"\"other\":true", "en")), -32602);
				}
		});
	}

	@Test
	public void localizationSlotBudgetPagesAllFourCatalogKinds() {
		McpEndpoint endpoint = catalog(PATH, LEGACY_SET, 7, 7, 7, 7, true, null);
		AtomicInteger contexts = new AtomicInteger();
		AtomicInteger lookups = new AtomicInteger();
		McpLocalizer localizer = localizer(2, contexts, text -> {
			lookups.incrementAndGet();
			return McpLocalizationResult.localized("L[" + text.getDefaultText() + "]");
		});
		run(List.of(endpoint), builder -> builder.localizer(localizer), simulator -> {
			int pages = 0;
			for (McpProtocolVersion version : LEGACY)
				for (Kind kind : Kind.values()) {
					Enumeration enumeration = enumerate(simulator, PATH, version, kind, "en", 2);
					assertEquals(expected(kind, 7), enumeration.identities());
					assertEquals(4, enumeration.pages());
					pages += enumeration.pages();
				}
			assertEquals(pages, contexts.get(), "Create one immutable context per admitted page.");
			assertEquals(56, lookups.get(), "Each emitted title is localized once.");
		});
	}

	@Test
	public void largeCatalogEnumerationEvaluatesOnlyTheResumeAnchorAndNeededPrefix() {
		int tools = 10_001;
		int prompts = 1_001;
		int resources = 1_001;
		McpEndpoint endpoint = catalog(PATH, LEGACY_SET, tools, prompts, resources, 0, true, null);
		AtomicInteger contexts = new AtomicInteger();
		AtomicInteger lookups = new AtomicInteger();
		AtomicInteger toolPolicyCalls = new AtomicInteger();
		AtomicInteger promptPolicyCalls = new AtomicInteger();
		McpLocalizer localizer = localizer(137, contexts, text -> {
			lookups.incrementAndGet();
			return McpLocalizationResult.useDefaultText();
		});
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> { toolPolicyCalls.incrementAndGet(); return true; },
				(context, registration, features) -> { promptPolicyCalls.incrementAndGet(); return true; });
		run(List.of(endpoint), builder -> builder.localizer(localizer).catalogAccessPolicy(policy), simulator -> {
			int allPages = 0;
			int toolPages = 0;
			int promptPages = 0;
			for (McpProtocolVersion version : LEGACY) {
				Enumeration toolEnumeration = enumerate(simulator, PATH, version, Kind.TOOLS, "en", 137);
				assertEquals(expected(Kind.TOOLS, tools), toolEnumeration.identities());
				assertEquals((tools + 136) / 137, toolEnumeration.pages());
				Enumeration promptEnumeration = enumerate(simulator, PATH, version, Kind.PROMPTS, "en", 137);
				assertEquals(expected(Kind.PROMPTS, prompts), promptEnumeration.identities());
				assertEquals((prompts + 136) / 137, promptEnumeration.pages());
				Enumeration resourceEnumeration = enumerate(simulator, PATH, version, Kind.RESOURCES, "en", 137);
				assertEquals(expected(Kind.RESOURCES, resources), resourceEnumeration.identities());
				assertEquals((resources + 136) / 137, resourceEnumeration.pages());
				toolPages += toolEnumeration.pages();
				promptPages += promptEnumeration.pages();
				allPages += toolEnumeration.pages() + promptEnumeration.pages() + resourceEnumeration.pages();
			}
			assertEquals(allPages, contexts.get());
			assertEquals(2 * (tools + prompts + resources), lookups.get());
			assertTrue(toolPolicyCalls.get() >= 2 * tools);
			assertTrue(toolPolicyCalls.get() <= 2 * tools + 2 * toolPages,
					"Enumeration must not rescan all tools on every page: " + toolPolicyCalls.get());
			assertTrue(promptPolicyCalls.get() >= 2 * prompts);
			assertTrue(promptPolicyCalls.get() <= 2 * prompts + 2 * promptPages,
					"Enumeration must not rescan all prompts on every page: " + promptPolicyCalls.get());
		});
	}

	@Test
	public void oneDescriptorThatExceedsTheSlotBudgetFailsWithoutAnEmptyContinuation() {
		McpEndpoint endpoint = catalog(PATH, LEGACY_SET, 1, 1, 1, 1, true, "Second localizable field");
		AtomicInteger lookups = new AtomicInteger();
		McpLocalizer localizer = localizer(1, new AtomicInteger(), text -> {
			lookups.incrementAndGet();
			return McpLocalizationResult.useDefaultText();
		});
		run(List.of(endpoint), builder -> builder.localizer(localizer), simulator -> {
			for (McpProtocolVersion version : LEGACY)
				for (Kind kind : Kind.values()) {
					Capture capture = execute(simulator, request(PATH, version, kind, null, "en"));
					assertError(capture, -32603);
					assertFalse(capture.body().contains("nextCursor"));
				}
			assertEquals(0, lookups.get(), "A non-fitting owner must fail before provider lookups.");
		});
	}

	@Test
	public void productionByteBudgetSplitsAnAggregateWhileEachDescriptorStillFits() {
		String description = "x".repeat(500_000);
		McpEndpoint endpoint = catalog(PATH, LEGACY_SET, 10, 0, 0, 0, false, description);
		run(List.of(endpoint), UnaryOperator.identity(), simulator -> {
			for (McpProtocolVersion version : LEGACY) {
				Enumeration enumeration = enumerate(simulator, PATH, version, Kind.TOOLS, "en", 10);
				assertEquals(expected(Kind.TOOLS, 10), enumeration.identities());
				assertTrue(enumeration.pages() > 1, "Five million descriptor bytes exceed the 4 MiB bound.");
			}
		});
	}

	@Test
	public void shrinkingLocalizedPagesReuseLookupResultsAndOneContextAcrossRetries() {
		McpEndpoint endpoint = catalog(PATH, LEGACY_SET, 30, 0, 0, 0, true, null);
		String translated = "L".repeat(600_000);
		AtomicInteger contexts = new AtomicInteger();
		List<Map<McpLocalizableText, Integer>> callsByRequest = new ArrayList<>();
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
			contexts.incrementAndGet();
			Map<McpLocalizableText, Integer> calls = new HashMap<>();
			callsByRequest.add(calls);
			return McpLocalizationContext.withLocale(Locale.ENGLISH, text -> {
				calls.merge(text, 1, Integer::sum);
				return McpLocalizationResult.localized(translated);
			}).build();
		}).maximumLocalizableTextCountPerResponse(30)
				.failurePolicy(McpLocalizationFailurePolicy.FAIL_REQUEST).build();
		run(List.of(endpoint), builder -> builder.localizer(localizer), simulator -> {
			for (McpProtocolVersion version : LEGACY) {
				int before = contexts.get();
				Page first = page(execute(simulator, request(PATH, version, Kind.TOOLS, null, "en")), Kind.TOOLS);
				assertTrue(first.identities().size() > 0 && first.identities().size() <= 7,
						"Thirty tiny canonical titles require several prefix reductions after localization.");
				assertNotNull(first.cursor(), "Localized growth must reduce the prefix and continue.");
				assertEquals(before + 1, contexts.get(), "Shrinking must not recreate the request context.");
				Map<McpLocalizableText, Integer> firstCalls = callsByRequest.get(callsByRequest.size() - 1);
				assertTrue(firstCalls.size() >= 7 && firstCalls.size() > first.identities().size(),
						"Exercise localized overflow before multiple prefix reductions.");
				assertTrue(firstCalls.size() <= 30, "The cumulative lookup budget covers all attempts.");
				assertTrue(firstCalls.values().stream().allMatch(count -> count == 1),
						"A reduced prefix must reuse already resolved lookup results.");
				List<String> identities = new ArrayList<>(first.identities());
				String cursor = first.cursor();
				while (cursor != null) {
					Page next = page(execute(simulator, request(PATH, version, Kind.TOOLS, cursor, "en")), Kind.TOOLS);
					identities.addAll(next.identities());
					cursor = next.cursor();
				}
				assertEquals(expected(Kind.TOOLS, 30), identities);
			}
			assertTrue(callsByRequest.stream().allMatch(calls ->
					calls.size() <= 30 && calls.values().stream().allMatch(count -> count == 1)));
		});
	}

	@Test
	public void cursorScopeFailuresStayNeutralAndEveryContinuationIsFreshlyAdmitted() {
		McpEndpoint endpoint = catalog(PATH, LEGACY_SET, 3, 3, 0, 0, true, null);
		McpEndpoint otherPath = catalog("/other", LEGACY_SET, 3, 3, 0, 0, true, null);
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger charges = new AtomicInteger();
		AtomicInteger contexts = new AtomicInteger();
		AtomicBoolean hideAnchor = new AtomicBoolean();
		AtomicBoolean hideNextCandidate = new AtomicBoolean();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) ->
						(!hideAnchor.get() || !registration.getName().equals(name(0)))
								&& (!hideNextCandidate.get() || !registration.getName().equals(name(1))),
				(context, registration, features) -> true);
		McpLocalizer localizer = localizer(1, contexts, text -> McpLocalizationResult.useDefaultText());
		run(List.of(endpoint, otherPath), builder -> builder.localizer(localizer).catalogAccessPolicy(policy)
				.admissionController(context -> { admissions.incrementAndGet(); return McpAdmissionDecision.accepted(); })
				.requestRateLimiter(context -> { charges.incrementAndGet(); return McpRateLimitDecision.allowed(); }), simulator -> {
			for (McpProtocolVersion version : LEGACY) {
				hideAnchor.set(false);
				hideNextCandidate.set(false);
				Page first = page(execute(simulator, request(PATH, version, Kind.TOOLS, null, "en")), Kind.TOOLS);
				assertEquals(List.of(name(0)), first.identities());
				assertNotNull(first.cursor());
				int before = admissions.get();
				Page next = page(execute(simulator, request(PATH, version, Kind.TOOLS, first.cursor(), "en")), Kind.TOOLS);
				assertEquals(List.of(name(1)), next.identities());
				assertEquals(before + 1, admissions.get());
				hideNextCandidate.set(true);
				Page freshlyFiltered = page(execute(simulator,
						request(PATH, version, Kind.TOOLS, first.cursor(), "en")), Kind.TOOLS);
				assertEquals(List.of(name(2)), freshlyFiltered.identities(),
						"A continuation must reevaluate current access to subsequent candidates.");
				hideNextCandidate.set(false);
				String unknownAnchor = changeCursorByte(first.cursor(), 34);
				Capture unknown = execute(simulator, request(PATH, version, Kind.TOOLS, unknownAnchor, "en"));
				assertError(unknown, -32602);
				List<Capture> invalid = List.of(
						execute(simulator, request(PATH, version, Kind.PROMPTS, first.cursor(), "en")),
						execute(simulator, request("/other", version, Kind.TOOLS, first.cursor(), "en")),
						execute(simulator, request(PATH, otherRevision(version), Kind.TOOLS, first.cursor(), "en")),
						execute(simulator, request(PATH, version, Kind.TOOLS, first.cursor(), "fr")),
						execute(simulator, request(PATH, version, Kind.TOOLS, changeCursorByte(first.cursor(), 2), "en")));
				for (Capture capture : invalid) {
					assertError(capture, -32602);
					assertEquals(unknown, capture, "Scope and anchor failures must share a neutral response.");
				}
				hideAnchor.set(true);
				Capture hidden = execute(simulator, request(PATH, version, Kind.TOOLS, first.cursor(), "en"));
				assertEquals(unknown, hidden, "A revoked anchor must not become an existence oracle.");
				Page restart = page(execute(simulator, request(PATH, version, Kind.TOOLS, null, "en")), Kind.TOOLS);
				assertEquals(List.of(name(1)), restart.identities(), "A new first page uses current policy.");
				assertError(execute(simulator, request(PATH, version, Kind.TOOLS, "!corrupt", "en")), -32602);
				assertError(execute(simulator, request(PATH, version, Kind.TOOLS, "x".repeat(2_049), "en")), -32602);
			}
			assertEquals(admissions.get(), charges.get(), "Every admitted page is charged exactly once.");
			assertEquals(admissions.get(), contexts.get(), "Scope checks follow one fresh localization context.");
		});
	}

	@Test
	public void changedCatalogRejectsAnOldCursorButEquivalentInstancesCanResumeIt() {
		for (McpProtocolVersion version : LEGACY) {
			AtomicReference<String> cursor = new AtomicReference<>();
			McpLocalizer localizer = localizer(1, new AtomicInteger(), text -> McpLocalizationResult.useDefaultText());
			run(List.of(catalog(PATH, LEGACY_SET, 3, 0, 0, 0, true, null)),
					builder -> builder.localizer(localizer), simulator -> cursor.set(
							page(execute(simulator, request(PATH, version, Kind.TOOLS, null, "en")), Kind.TOOLS).cursor()));
			assertNotNull(cursor.get());
			run(List.of(catalog(PATH, LEGACY_SET, 3, 0, 0, 0, true, null)),
					builder -> builder.localizer(localizer), simulator -> {
				Page resumed = page(execute(simulator, request(PATH, version, Kind.TOOLS, cursor.get(), "en")), Kind.TOOLS);
				assertEquals(List.of(name(1)), resumed.identities());
			});
			run(List.of(catalog(PATH, LEGACY_SET, 3, 0, 0, 0, true, "Changed descriptor")),
					builder -> builder.localizer(localizer), simulator ->
							assertError(execute(simulator, request(PATH, version, Kind.TOOLS, cursor.get(), "en")), -32602));
		}
	}

	@Test
	public void legacyViewWithoutLocalizableOwnersDoesNotInvokeTheModernOwnersProvider() {
		McpToolRegistration<?> legacy = McpToolRegistration.withName("legacy-plain", LEGACY_SET)
				.jsonObjectArguments().handler((request, args, features) -> {
					throw new AssertionError("Listing must not invoke tools/call.");
				}).build();
		McpToolRegistration<?> modern = McpToolRegistration.withName("modern-title",
				Set.of(McpProtocolVersion.V2026_07_28)).jsonObjectArguments()
				.handler((request, args, features) -> {
					throw new AssertionError("Listing must not invoke tools/call.");
				}).title("Modern title").build();
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion("legacy-paging", "test").build(),
				Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
						McpProtocolVersion.V2026_07_28)).toolRegistrations(List.of(modern, legacy)).build();
		AtomicInteger contexts = new AtomicInteger();
		AtomicInteger lookups = new AtomicInteger();
		McpLocalizer localizer = localizer(1, contexts, text -> {
			lookups.incrementAndGet();
			assertEquals("Modern title", text.getDefaultText());
			return McpLocalizationResult.useDefaultText();
		});
		run(List.of(endpoint), builder -> builder.localizer(localizer), simulator -> {
			for (McpProtocolVersion version : LEGACY) {
				Page page = page(execute(simulator, request(PATH, version, Kind.TOOLS, null, "en")), Kind.TOOLS);
				assertEquals(List.of("legacy-plain"), page.identities());
				assertNull(page.cursor());
				assertEquals(0, contexts.get(), "Other revisions' owners must not activate a legacy provider.");
				assertEquals(0, lookups.get());
			}
			Page page = page(execute(simulator,
					request(PATH, McpProtocolVersion.V2026_07_28, Kind.TOOLS, null, "en")), Kind.TOOLS);
			assertEquals(List.of("modern-title"), page.identities());
			assertEquals(1, contexts.get());
			assertEquals(1, lookups.get());
		});
	}

	@Test
	public void frameworkCursorLimitIsIndependentOfOneByteApplicationCursors() {
		AtomicInteger handlers = new AtomicInteger();
		McpEndpoint base = catalog(PATH, LEGACY_SET, 3, 0, 1, 0, true, null);
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion("legacy-paging", "test").build(), LEGACY_SET)
				.toolRegistrations(base.getToolRegistrations()).resourceRegistrations(base.getResourceRegistrations())
				.resourceListHandler((request, context, features) -> {
					handlers.incrementAndGet();
					if (context.getCursor().isPresent())
						assertTrue(context.getCursor().get().equals("x") || context.getCursor().get().isEmpty());
					McpResourcePage.Builder page = McpResourcePage.builder()
							.resourceDescriptors(context.getRegisteredResourceDescriptors());
					if (context.getCursor().isEmpty()) page.nextCursor("x");
					return page.build();
				}, LEGACY_SET).build();
		McpLocalizer localizer = localizer(1, new AtomicInteger(), text -> McpLocalizationResult.useDefaultText());
		run(List.of(endpoint), builder -> builder.maximumCursorSizeInBytes(1).localizer(localizer), simulator -> {
			for (McpProtocolVersion version : LEGACY) {
				Enumeration tools = enumerate(simulator, PATH, version, Kind.TOOLS, "en", 1);
				assertEquals(expected(Kind.TOOLS, 3), tools.identities());
				Page first = page(execute(simulator, request(PATH, version, Kind.RESOURCES, null, "en")), Kind.RESOURCES);
				assertEquals("x", first.cursor());
				assertNull(page(execute(simulator, request(PATH, version, Kind.RESOURCES, "x", "en")), Kind.RESOURCES).cursor());
				assertNull(page(execute(simulator, request(PATH, version, Kind.RESOURCES, "", "en")), Kind.RESOURCES).cursor());
				int before = handlers.get();
				assertError(execute(simulator, request(PATH, version, Kind.RESOURCES, "xx", "en")), -32602);
				assertEquals(before, handlers.get());
			}
			assertEquals(6, handlers.get());
		});
	}

	@Test
	public void modernAndMixedViewsRetainWholeCatalogBytePreflight() {
		String description = "x".repeat(500_000);
		for (Set<McpProtocolVersion> versions : List.of(
				Set.of(McpProtocolVersion.V2026_07_28),
				Set.of(McpProtocolVersion.V2026_07_28, McpProtocolVersion.V2025_06_18,
						McpProtocolVersion.V2025_11_25))) {
			McpEndpoint endpoint = catalog(PATH, versions, 10, 0, 0, 0, false, description);
			assertThrows(IllegalArgumentException.class, () -> configure(McpServer.withPort(0), List.of(endpoint)).build());
		}
	}

	private static McpEndpoint catalog(String path, Set<McpProtocolVersion> versions,
			int toolCount, int promptCount, int resourceCount, int templateCount,
			boolean titles, String description) {
		List<McpToolRegistration<?>> tools = new ArrayList<>();
		List<McpPromptRegistration> prompts = new ArrayList<>();
		List<McpResourceRegistration> resources = new ArrayList<>();
		// Reverse registration order proves that continuation order is canonical.
		for (int index = toolCount - 1; index >= 0; --index) {
			McpToolRegistration.OperationBuilder<McpJsonObject> builder = McpToolRegistration.withName(name(index), versions)
					.jsonObjectArguments().handler((request, args, features) -> {
					throw new AssertionError("Listing must not invoke tools/call.");
				});
			if (titles) builder.title("Title " + name(index));
			if (description != null) builder.description(description);
			tools.add(builder.build());
		}
		for (int index = promptCount - 1; index >= 0; --index) {
			McpPromptRegistration.Builder builder = McpPromptRegistration.withName(name(index), versions)
					.handler((request, args, features) -> { throw new AssertionError("Listing must not invoke prompts/get."); });
			if (titles) builder.title("Title " + name(index));
			if (description != null) builder.description(description);
			prompts.add(builder.build());
		}
		for (int index = resourceCount - 1; index >= 0; --index) {
			McpResourceRegistration.ExactBuilder builder = McpResourceRegistration
					.withUriAndName(URI.create(identity(Kind.RESOURCES, index)), name(index), versions)
					.handler((request, args, features) -> { throw new AssertionError("Listing must not invoke resources/read."); });
			if (titles) builder.title("Title " + name(index));
			if (description != null) builder.description(description);
			resources.add(builder.build());
		}
		for (int index = templateCount - 1; index >= 0; --index) {
			McpResourceRegistration.TemplateBuilder builder = McpResourceRegistration
					.withUriTemplateAndName(identity(Kind.TEMPLATES, index), name(index), versions)
					.handler((request, args, features) -> { throw new AssertionError("Listing must not invoke resources/read."); });
			if (titles) builder.title("Title " + name(index));
			if (description != null) builder.description(description);
			resources.add(builder.build());
		}
		return McpEndpoint.withPath(path, McpImplementation.withNameAndVersion("legacy-paging", "test").build(), versions)
				.toolRegistrations(tools).promptRegistrations(prompts).resourceRegistrations(resources).build();
	}

	private static McpLocalizer localizer(int slots, AtomicInteger contexts, McpLocalizationLookup lookup) {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
			contexts.incrementAndGet();
			Locale locale = request.getRequestContext().getRequest().getHeader("Accept-Language")
					.filter("fr"::equals).isPresent() ? Locale.FRENCH : Locale.ENGLISH;
			return McpLocalizationContext.withLocale(locale, lookup).build();
		}).maximumLocalizableTextCountPerResponse(slots)
				.failurePolicy(McpLocalizationFailurePolicy.FAIL_REQUEST).build();
	}

	private static void run(List<McpEndpoint> endpoints, UnaryOperator<McpServer.Builder> customize,
			Consumer<Simulator> assertions) {
		SokletSimulator.run(SimulatorConfig.builder()
				.configureMcpServer(builder -> customize.apply(configure(builder.port(0), endpoints)))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LIFECYCLE).build(), simulator -> assertions.accept(simulator));
	}

	private static McpServer.Builder configure(McpServer.Builder builder, List<McpEndpoint> endpoints) {
		return builder.host(HOST).endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance()).allowedHosts(Set.of(HOST));
	}

	private static Request request(String path, McpProtocolVersion version, Kind kind, String cursor, String locale) {
		return requestWithParams(path, version, kind, cursor == null ? "" : "\"cursor\":" + quote(cursor), locale);
	}

	private static Request requestWithParams(String path, McpProtocolVersion version, Kind kind, String params, String locale) {
		if (version == McpProtocolVersion.V2026_07_28)
			params = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" + (params.isEmpty() ? "" : "," + params);
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"list\",\"method\":" + quote(kind.method)
				+ (params.isEmpty() ? "" : ",\"params\":{" + params + "}") + "}";
		Map<String, Set<String>> headers = new HashMap<>(Map.of(
				"Host", Set.of(HOST + ":0"), "Content-Type", Set.of("application/json"),
				"Accept", Set.of("application/json, text/event-stream"),
				"MCP-Protocol-Version", Set.of(version.getWireValue()), "Accept-Language", Set.of(locale)));
		if (version == McpProtocolVersion.V2026_07_28) headers.put("Mcp-Method", Set.of(kind.method));
		return Request.withPath(HttpMethod.POST, path).headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static Capture execute(Simulator simulator, Request request) {
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(WAIT).orElseThrow();
			assertEquals(McpSimulationBodyType.JSON, response.getBodyType());
			String body = new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
			assertTrue(body.getBytes(StandardCharsets.UTF_8).length <= McpJsonLimits.productionDefaults().maximumOutputBytes());
			assertTrue(simulation.awaitCompletion(WAIT).isPresent());
			return new Capture(response.getStatusCode(), body);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static Page page(Capture capture, Kind kind) {
		assertEquals(200, capture.status(), capture.body());
		com.soklet.internal.mcp.protocol.McpJsonObject envelope =
				(com.soklet.internal.mcp.protocol.McpJsonObject) JSON.parse(capture.body());
		assertFalse(envelope.members().containsKey("error"), capture.body());
		com.soklet.internal.mcp.protocol.McpJsonObject result =
				(com.soklet.internal.mcp.protocol.McpJsonObject) envelope.members().get("result");
		McpJsonArray descriptors = (McpJsonArray) result.members().get(kind.member);
		List<String> identities = descriptors.values().stream()
				.map(value -> (com.soklet.internal.mcp.protocol.McpJsonObject) value)
				.map(value -> ((McpJsonString) value.members().get(kind.key)).value()).toList();
		for (var value : descriptors.values()) {
			var descriptor = (com.soklet.internal.mcp.protocol.McpJsonObject) value;
			if (descriptor.members().get("title") instanceof McpJsonString title
					&& title.value().startsWith("L[Title "))
				assertEquals("L[Title " + ((McpJsonString) descriptor.members().get("name")).value() + "]",
						title.value(), "Page-local localization must follow owners across canonical sorting.");
		}
		String cursor = null;
		if (result.members().containsKey("nextCursor")) {
			assertInstanceOf(McpJsonString.class, result.members().get("nextCursor"));
			cursor = ((McpJsonString) result.members().get("nextCursor")).value();
			assertFalse(cursor.isEmpty());
			assertTrue(cursor.getBytes(StandardCharsets.UTF_8).length <= 2_048);
		}
		return new Page(identities, cursor, capture);
	}

	private static Enumeration enumerate(Simulator simulator, String path, McpProtocolVersion version,
			Kind kind, String locale, int maximumPageEntries) {
		List<String> identities = new ArrayList<>();
		Set<String> cursors = new HashSet<>();
		String cursor = null;
		int pages = 0;
		do {
			Page page = page(execute(simulator, request(path, version, kind, cursor, locale)), kind);
			assertTrue(page.identities().size() <= maximumPageEntries);
			if (page.cursor() != null) assertFalse(page.identities().isEmpty());
			identities.addAll(page.identities());
			cursor = page.cursor();
			if (cursor != null) assertTrue(cursors.add(cursor), "Continuation must advance.");
			assertTrue(++pages < 20_000, "Enumeration must terminate.");
		} while (cursor != null);
		assertEquals(identities.size(), new HashSet<>(identities).size(), "Do not repeat descriptors across pages.");
		return new Enumeration(identities, pages);
	}

	private static void assertError(Capture capture, int code) {
		com.soklet.internal.mcp.protocol.McpJsonObject envelope =
				(com.soklet.internal.mcp.protocol.McpJsonObject) JSON.parse(capture.body());
		assertFalse(envelope.members().containsKey("result"), capture.body());
		com.soklet.internal.mcp.protocol.McpJsonObject error =
				(com.soklet.internal.mcp.protocol.McpJsonObject) envelope.members().get("error");
		assertEquals(code, ((McpJsonNumber) error.members().get("code")).value().intValueExact());
	}

	private static String changeCursorByte(String token, int offset) {
		// Deliberately forge syntactically valid navigation data, without treating it as authority.
		byte[] bytes = Base64.getUrlDecoder().decode(token);
		bytes[offset] ^= 1;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String quote(String value) { return JSON.toJson(new McpJsonString(value)); }
	private static String name(int index) { return "item-" + String.format(Locale.ROOT, "%05d", index); }
	private static String identity(Kind kind, int index) {
		return switch (kind) {
			case TOOLS, PROMPTS -> name(index);
			case RESOURCES -> "catalog://" + name(index);
			case TEMPLATES -> "catalog://" + name(index) + "/{value}";
		};
	}
	private static List<String> expected(Kind kind, int count) {
		List<String> identities = new ArrayList<>(count);
		for (int index = 0; index < count; ++index) identities.add(identity(kind, index));
		return identities;
	}
	private static McpProtocolVersion otherRevision(McpProtocolVersion version) {
		return version == McpProtocolVersion.V2025_06_18 ? McpProtocolVersion.V2025_11_25 : McpProtocolVersion.V2025_06_18;
	}
	private enum Kind {
		TOOLS("tools/list", "tools", "name"), PROMPTS("prompts/list", "prompts", "name"),
		RESOURCES("resources/list", "resources", "uri"),
		TEMPLATES("resources/templates/list", "resourceTemplates", "uriTemplate");
		final String method;
		final String member;
		final String key;
		Kind(String method, String member, String key) { this.method = method; this.member = member; this.key = key; }
	}
	private record Capture(int status, String body) { }
	private record Page(List<String> identities, String cursor, Capture capture) { }
	private record Enumeration(List<String> identities, int pages) { }
}
