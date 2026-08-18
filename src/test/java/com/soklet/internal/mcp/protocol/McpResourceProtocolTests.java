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

package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import com.soklet.McpRequestObservationTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTimeout;

@NotThreadSafe
@Timeout(30)
public class McpResourceProtocolTests {
	private static final String PROTOCOL_VERSION = "2026-07-28";

	@Test
	public void static_resource_catalogs_are_separate_ordered_and_cache_owned()
			throws Exception {
		McpNormalizedResourceDescriptor first = new McpNormalizedResourceDescriptor(
				"catalog://items/1", "Item one",
				new McpJsonObject(Map.of(
						"title", new McpJsonString("First item"),
						"mimeType", new McpJsonString("application/json"),
						"size", new McpJsonNumber(12L))),
				new McpJsonObject(Map.of(
						"com.example/revision", new McpJsonString("7"))),
				new McpResourceCachePolicy(11L, McpCacheScope.PRIVATE));
		McpNormalizedResourceDescriptor second =
				McpNormalizedResourceDescriptor.minimal("catalog://items/2");
		McpNormalizedResourceTemplateDescriptor template =
				new McpNormalizedResourceTemplateDescriptor(
						"catalog://items/{itemId}", "Catalog item",
						new McpJsonObject(Map.of(
								"description", new McpJsonString("One catalog item"))),
						McpJsonObject.empty(),
						new McpResourceCachePolicy(13L, McpCacheScope.PRIVATE));
		McpNormalizedEndpoint endpoint = endpointBuilder()
				.exactResource(first)
				.exactResource(second)
				.resourceTemplate(template)
				.resourceListCachePolicy(
						new McpResourceCachePolicy(1_000L, McpCacheScope.PUBLIC))
				.resourceTemplateListCachePolicy(
						new McpResourceCachePolicy(2_000L, McpCacheScope.PRIVATE))
				.build();
		McpServerCapabilityRegistry registry =
				McpServerCapabilityRegistry.fromEndpoint(endpoint);

		McpJsonObject resources = registry.resourcesListResult().toJsonObject();
		McpJsonObject templates = registry.resourceTemplatesListResult().toJsonObject();
		Assertions.assertEquals(new McpJsonNumber(1_000L),
				resources.members().get("ttlMs"));
		Assertions.assertEquals(new McpJsonString("public"),
				resources.members().get("cacheScope"));
		Assertions.assertEquals(new McpJsonNumber(2_000L),
				templates.members().get("ttlMs"));
		Assertions.assertEquals(new McpJsonString("private"),
				templates.members().get("cacheScope"));
		McpJsonArray listedResources =
				(McpJsonArray) resources.members().get("resources");
		Assertions.assertEquals(2, listedResources.values().size());
		Assertions.assertEquals(new McpJsonString("catalog://items/1"),
				((McpJsonObject) listedResources.values().get(0)).members().get("uri"));
		Assertions.assertTrue(((McpJsonObject) listedResources.values().get(0))
				.members().containsKey("_meta"));
		McpJsonArray listedTemplates =
				(McpJsonArray) templates.members().get("resourceTemplates");
		Assertions.assertEquals(1, listedTemplates.values().size());
		Assertions.assertEquals(new McpJsonString("catalog://items/{itemId}"),
				((McpJsonObject) listedTemplates.values().get(0))
						.members().get("uriTemplate"));
		Assertions.assertFalse(resources.members().containsKey("nextCursor"));
		Assertions.assertFalse(templates.members().containsKey("nextCursor"));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> registry.exactResourceDescriptors().add(first));

		// A template-only endpoint still owns a valid empty resources/list page.
		McpNormalizedEndpoint templateOnly = endpointBuilder()
				.resourceTemplate("catalog://other/{id}")
				.build();
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromResourceRoutes(
				Map.of(), List.of(new McpApplicationResourceTemplateRoute(
						"catalog://other/{id}", new McpApplicationResourceReadRoute(
								ignored -> emptyReadResult()))), Optional.empty());
		try (McpHttpServerRuntime runtime = runtime(templateOnly, router,
				ignored -> McpAdmissionDecision.acceptedAnonymous(), Optional.empty())) {
			int port = runtime.start().getPort();
			FixedResponse response = send(port,
					request("empty-list", "resources/list", ""),
					headers("resources/list", null));
			Assertions.assertEquals(200, response.head().status(), response.body());
			Assertions.assertTrue(response.body().contains("\"resources\":[]"),
					response.body());
		}
	}

	@Test
	public void static_catalogs_reject_every_present_cursor_before_admission()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpNormalizedEndpoint endpoint = endpointBuilder()
				.exactResource("catalog://items/1")
				.build();
		try (McpHttpServerRuntime runtime = runtime(endpoint,
				McpApplicationRequestRouter.fromResourceRoutes(
						Map.of("catalog://items/1", new McpApplicationResourceReadRoute(
								ignored -> emptyReadResult())),
						List.of(), Optional.empty()), ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				}, Optional.empty())) {
			int port = runtime.start().getPort();
			for (String method : List.of("resources/list", "resources/templates/list")) {
				FixedResponse response = send(port,
						request(method, method, ",\"cursor\":\"\""),
						headers(method, null));
				assertError(response, 400, -32602, method);
			}
			Assertions.assertEquals(0, admissions.get());
		}
	}

	@Test
	public void dynamic_list_preserves_opaque_cursors_and_bounds_utf8_before_admission()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger rateLimits = new AtomicInteger();
		List<Optional<String>> observedCursors =
				Collections.synchronizedList(new ArrayList<>());
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpNormalizedEndpoint endpoint = endpointBuilder()
				.exactResource("catalog://items/1")
				.customResourceListHandler()
				.maximumCursorSizeInBytes(8)
				.resourceListCachePolicy(
						new McpResourceCachePolicy(99L, McpCacheScope.PRIVATE))
				.build();
		McpApplicationResourceListRoute listRoute = new McpApplicationResourceListRoute(
				invocation -> {
					handlerInvocations.incrementAndGet();
					observedCursors.add(invocation.cursor());
					Assertions.assertEquals(List.of("catalog://items/1"),
							invocation.registeredResourceDescriptors().stream()
									.map(McpNormalizedResourceDescriptor::uri).toList());
					Assertions.assertThrows(UnsupportedOperationException.class,
							() -> invocation.registeredResourceDescriptors().clear());
					Map<String, McpJsonValue> fields = new java.util.LinkedHashMap<>();
					fields.put("resources", new McpJsonArray(List.of()));
					if (invocation.cursor().isEmpty()) {
						fields.put("nextCursor", new McpJsonString("世界"));
						fields.put("ttlMs", new McpJsonNumber(123L));
					} else if (invocation.cursor().orElseThrow().equals("big")) {
						fields.put("nextCursor", new McpJsonString("世界語"));
					}
					return McpWireResult.complete(new McpJsonObject(fields));
				});
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromResourceRoutes(
				Map.of("catalog://items/1", new McpApplicationResourceReadRoute(
						ignored -> emptyReadResult())), List.of(), Optional.of(listRoute));

		try (McpHttpServerRuntime runtime = runtime(endpoint, router, ignored -> {
			admissions.incrementAndGet();
			return McpAdmissionDecision.acceptedAnonymous();
		}, Optional.of(ignored -> {
			rateLimits.incrementAndGet();
			return McpRateLimitDecision.allowed();
		}))) {
			int port = runtime.start().getPort();
			FixedResponse omitted = send(port,
					request("omitted", "resources/list", ""),
					headers("resources/list", null));
			Assertions.assertEquals(200, omitted.head().status(), omitted.body());
			Assertions.assertTrue(omitted.body().contains("\"ttlMs\":123"),
					omitted.body());
			Assertions.assertTrue(omitted.body().contains("\"cacheScope\":\"private\""),
					omitted.body());
			Assertions.assertTrue(omitted.body().contains("\"nextCursor\":\"世界\""),
					omitted.body());

			FixedResponse empty = send(port,
					request("empty", "resources/list", ",\"cursor\":\"\""),
					headers("resources/list", null));
			Assertions.assertEquals(200, empty.head().status(), empty.body());
			FixedResponse unicode = send(port,
					request("unicode", "resources/list", ",\"cursor\":\"世界\""),
					headers("resources/list", null));
			Assertions.assertEquals(200, unicode.head().status(), unicode.body());
			Assertions.assertEquals(List.of(Optional.empty(), Optional.of(""),
					Optional.of("世界")), observedCursors);
			Assertions.assertEquals(3, admissions.get());
			Assertions.assertEquals(3, rateLimits.get());
			Assertions.assertEquals(3, handlerInvocations.get());

			FixedResponse oversizedOutput = send(port,
					request("oversized-output", "resources/list",
							",\"cursor\":\"big\""),
					headers("resources/list", null));
			assertError(oversizedOutput, 500, -32603, "oversized-output");
			Assertions.assertFalse(oversizedOutput.body().contains("世界語"),
					oversizedOutput.body());
			Assertions.assertEquals(4, admissions.get());
			Assertions.assertEquals(4, rateLimits.get());
			Assertions.assertEquals(4, handlerInvocations.get());

			FixedResponse oversized = send(port,
					request("oversized", "resources/list", ",\"cursor\":\"世界語\""),
					headers("resources/list", null));
			assertError(oversized, 400, -32602, "oversized");
			FixedResponse wrongType = send(port,
					request("wrong-type", "resources/list", ",\"cursor\":7"),
					headers("resources/list", null));
			assertError(wrongType, 400, -32602, "wrong-type");
			Assertions.assertEquals(4, admissions.get());
			Assertions.assertEquals(4, rateLimits.get());
			Assertions.assertEquals(4, handlerInvocations.get());
		}
	}

	@Test
	public void dynamic_list_rejects_semantically_equivalent_resource_uris()
			throws Exception {
		String registeredUri = "CATALOG://ITEMS/a%2Fb";
		McpNormalizedEndpoint endpoint = endpointBuilder()
				.exactResource(registeredUri)
				.customResourceListHandler()
				.build();
		McpApplicationResourceListRoute listRoute =
				new McpApplicationResourceListRoute(ignored -> McpWireResult.complete(
						new McpJsonObject(Map.of("resources", new McpJsonArray(List.of(
								new McpJsonObject(Map.of(
										"uri", new McpJsonString(registeredUri),
										"name", new McpJsonString("Uppercase"))),
								new McpJsonObject(Map.of(
										"uri", new McpJsonString(
												"catalog://items/a%2fb"),
										"name", new McpJsonString("Lowercase")))))))));
		McpApplicationRequestRouter router = McpApplicationRequestRouter
				.fromResourceRoutes(Map.of(registeredUri,
						new McpApplicationResourceReadRoute(
								ignored -> emptyReadResult())),
						List.of(), Optional.of(listRoute));

		try (McpHttpServerRuntime runtime = runtime(endpoint, router,
				ignored -> McpAdmissionDecision.acceptedAnonymous(), Optional.empty())) {
			FixedResponse response = send(runtime.start().getPort(),
					request("equivalent-uris", "resources/list", ""),
					headers("resources/list", null));
			assertError(response, 500, -32603, "equivalent-uris");
			Assertions.assertFalse(response.body().contains(registeredUri),
					response.body());
		}
	}

	@Test
	public void custom_list_only_surface_rejects_resource_reads_as_invalid_params()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpNormalizedEndpoint endpoint = endpointBuilder()
				.customResourceListHandler()
				.build();
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromResourceRoutes(
				Map.of(), List.of(), Optional.of(new McpApplicationResourceListRoute(
						ignored -> McpWireResult.complete(new McpJsonObject(Map.of(
								"resources", new McpJsonArray(List.of())))))));
		try (McpHttpServerRuntime runtime = runtime(endpoint, router, ignored -> {
			admissions.incrementAndGet();
			return McpAdmissionDecision.acceptedAnonymous();
		}, Optional.empty())) {
			FixedResponse response = read(runtime.start().getPort(), "list-only-read",
					"catalog://items/1");
			assertError(response, 400, -32602, "list-only-read");
			Assertions.assertTrue(response.body().contains(
					"\"data\":{\"uri\":\"catalog://items/1\"}"), response.body());
			Assertions.assertEquals(0, admissions.get());
		}
	}

	@Test
	public void erased_bridge_maps_intentional_resource_errors_without_internal_failure()
			throws Exception {
		com.soklet.McpEndpoint publicEndpoint = com.soklet.McpEndpoint.withPath("/mcp")
				.serverInformation(com.soklet.McpImplementation.withNameAndVersion(
						"resource-bridge-test", "3.6.0-SNAPSHOT").build())
				.build();
		McpServerRuntimeBridge.CachePlan cache =
				McpServerRuntimeBridge.CachePlan.privateNoCache();
		McpServerRuntimeBridge.ResourcePlan resourcePlan =
				new McpServerRuntimeBridge.ResourcePlan(
						McpServerRuntimeBridge.ResourceAddressKind.URI,
						"catalog://items/1", "Item one",
						com.soklet.McpJsonObject.emptyInstance(),
						com.soklet.McpJsonObject.emptyInstance(), cache,
						ignored -> McpServerRuntimeBridge.ResourceInvocationResult
								.jsonRpcError(-32602, "Application rejected resource",
										Optional.of(com.soklet.McpJsonString.fromValue(
												"read-data"))));
		McpServerRuntimeBridge.ResourceListPlan listPlan =
				new McpServerRuntimeBridge.ResourceListPlan(cache, cache, 4_096,
						Optional.of(ignored -> McpServerRuntimeBridge
								.ResourceListInvocationResult.jsonRpcError(
										700, "Application rejected cursor",
										Optional.of(com.soklet.McpJsonString.fromValue(
												"list-data")))));
		McpServerRuntimeBridge bridge = new McpServerRuntimeBridge(
				"127.0.0.1", 0, publicEndpoint, Set.of("127.0.0.1"), false,
				CorsAuthorizer.rejectAllInstance(), true,
				ignored -> com.soklet.McpAdmissionDecision.accepted(),
				Optional.empty(), List.of(), List.of(), List.of(resourcePlan),
				Optional.of(listPlan), ignored -> {}, ignored -> {},
				McpRequestObservationTestSupport.noOpAdapter());

		try {
			int port = bridge.start().getPort();
			FixedResponse read = read(port, "bridge-read", "catalog://items/1");
			assertError(read, 400, -32602, "bridge-read");
			Assertions.assertTrue(read.body().contains("Application rejected resource"),
					read.body());
			Assertions.assertTrue(read.body().contains("\"data\":\"read-data\""),
					read.body());

			FixedResponse list = send(port,
					request("bridge-list", "resources/list", ""),
					headers("resources/list", null));
			assertError(list, 400, 700, "bridge-list");
			Assertions.assertTrue(list.body().contains("Application rejected cursor"),
					list.body());
			Assertions.assertTrue(list.body().contains("\"data\":\"list-data\""),
					list.body());
		} finally {
			bridge.stop();
		}
	}

	@Test
	public void reads_prefer_exact_routes_decode_template_values_and_reject_safely()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger rateLimits = new AtomicInteger();
		AtomicInteger exactInvocations = new AtomicInteger();
		AtomicReference<Map<String, String>> templateVariables = new AtomicReference<>();
		McpResourceCachePolicy exactCache =
				new McpResourceCachePolicy(50L, McpCacheScope.PUBLIC);
		McpResourceCachePolicy templateCache =
				new McpResourceCachePolicy(75L, McpCacheScope.PRIVATE);
		McpNormalizedEndpoint endpoint = endpointBuilder()
				.exactResource(new McpNormalizedResourceDescriptor(
						"catalog://items/special", "Special", McpJsonObject.empty(),
						McpJsonObject.empty(), exactCache))
				.resourceTemplate(new McpNormalizedResourceTemplateDescriptor(
						"catalog://items/{itemId}", "Item", McpJsonObject.empty(),
						McpJsonObject.empty(), templateCache))
				.build();
		McpApplicationResourceReadRoute exactRoute =
				new McpApplicationResourceReadRoute(invocation -> {
					exactInvocations.incrementAndGet();
					Assertions.assertTrue(invocation.templateVariables().isEmpty());
					return emptyReadResult();
				}, exactCache);
		McpApplicationResourceReadRoute templateRoute =
				new McpApplicationResourceReadRoute(invocation -> {
					templateVariables.set(invocation.templateVariables());
					return emptyReadResult();
				}, templateCache);
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromResourceRoutes(
				Map.of("catalog://items/special", exactRoute),
				List.of(new McpApplicationResourceTemplateRoute(
						"catalog://items/{itemId}", templateRoute)), Optional.empty());

		try (McpHttpServerRuntime runtime = runtime(endpoint, router, ignored -> {
			admissions.incrementAndGet();
			return McpAdmissionDecision.acceptedAnonymous();
		}, Optional.of(ignored -> {
			rateLimits.incrementAndGet();
			return McpRateLimitDecision.allowed();
		}))) {
			int port = runtime.start().getPort();
			FixedResponse exact = read(port, "exact", "catalog://items/special");
			Assertions.assertEquals(200, exact.head().status(), exact.body());
			Assertions.assertEquals(1, exactInvocations.get());
			Assertions.assertTrue(exact.body().contains("\"ttlMs\":50"), exact.body());
			Assertions.assertTrue(exact.body().contains("\"cacheScope\":\"public\""),
					exact.body());

			FixedResponse decoded = read(port, "decoded", "catalog://items/caf%C3%A9");
			Assertions.assertEquals(200, decoded.head().status(), decoded.body());
			Assertions.assertEquals(Map.of("itemId", "café"), templateVariables.get());
			Assertions.assertTrue(decoded.body().contains("\"ttlMs\":75"), decoded.body());
			Assertions.assertEquals(2, admissions.get());
			Assertions.assertEquals(2, rateLimits.get());

			FixedResponse unknown = read(port, "unknown", "catalog://other/1");
			assertError(unknown, 400, -32602, "unknown");
			Assertions.assertTrue(unknown.body().contains(
					"\"data\":{\"uri\":\"catalog://other/1\"}"), unknown.body());
			FixedResponse malformed = read(port, "malformed", "catalog://items/%ZZ");
			assertError(malformed, 400, -32602, "malformed");
			FixedResponse malformedUtf8 = read(port, "malformed-utf8",
					"catalog://items/%C3%28");
			assertError(malformedUtf8, 400, -32602, "malformed-utf8");
			Assertions.assertEquals(2, admissions.get());
			Assertions.assertEquals(2, rateLimits.get());
		}
	}

	@Test
	public void level_one_templates_reject_unsupported_or_overlapping_routes() {
		McpLevelOneUriTemplate parsed =
				McpLevelOneUriTemplate.parse("catalog://items/{itemId}/details");
		Assertions.assertEquals(Optional.of(Map.of("itemId", "café")),
				parsed.match("catalog://items/caf%C3%A9/details"));
		Assertions.assertEquals(Optional.of(Map.of("value", "ab")),
				McpLevelOneUriTemplate.parse("catalog://items/{value}b")
						.match("catalog://items/abb"));
		McpLevelOneUriTemplate terminal =
				McpLevelOneUriTemplate.parse("catalog://items/{value}");
		Assertions.assertEquals(Optional.of(Map.of("value", "?")),
				terminal.match("catalog://items/%3F"));
		Assertions.assertEquals(Optional.of(Map.of("value", "/%€😀")),
				terminal.match("catalog://items/%2F%25%E2%82%AC%F0%9F%98%80"));
		Assertions.assertTrue(terminal.match("catalog://items/%41").isEmpty());
		Assertions.assertTrue(terminal.match("catalog://items/%7E").isEmpty());
		for (String invalidUtf8 : List.of("%C0%AF", "%ED%A0%80", "%F4%90%80%80"))
			Assertions.assertTrue(
					terminal.match("catalog://items/" + invalidUtf8).isEmpty(),
					invalidUtf8);
		Assertions.assertTrue(terminal.match("catalog://items/value?query").isEmpty());
		Assertions.assertTrue(terminal.match("catalog://items/value#fragment").isEmpty());
		Assertions.assertEquals(Optional.of(Map.of("value", "a")),
				McpLevelOneUriTemplate.parse("test://h/café/{value}")
						.match("test://h/caf%C3%A9/a"));
		Assertions.assertEquals(Optional.of(Map.of("value", "a")),
				McpLevelOneUriTemplate.parse("test://h/café/{value}")
						.match("test://h/caf%c3%a9/a"));
		Assertions.assertEquals(Optional.of(Map.of("value", "a")),
				McpLevelOneUriTemplate.parse("test://h/%FF/{value}")
						.match("test://h/%FF/a"));
		Assertions.assertEquals(Optional.of(Map.of("value", "a")),
				McpLevelOneUriTemplate.parse("test://h/%2F/{value}")
						.match("test://h/%2f/a"));
		Assertions.assertEquals(Optional.of(Map.of("value", "")),
				terminal.match("catalog://items/"));
		Assertions.assertEquals(Optional.of(Map.of("a", "ax", "b", "b")),
				McpLevelOneUriTemplate.parse("test:///{a}x{b}")
						.match("test:///axxb"));
		Assertions.assertTrue(
				parsed.match("catalog://items/%C3%28/details").isEmpty());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> parsed.match("catalog://items/café/details"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpLevelOneUriTemplate.parse("catalog://items/{+itemId}"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpLevelOneUriTemplate.parse("catalog://items/static"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpLevelOneUriTemplate.parse("test://h/it's/{value}"));
		StringBuilder maximumVariableTemplate = new StringBuilder("test://h");
		for (int index = 0; index < 32; ++index)
			maximumVariableTemplate.append("/{value").append(index).append('}');
		Assertions.assertDoesNotThrow(() -> McpLevelOneUriTemplate.parse(
				maximumVariableTemplate.toString()));
		String excessiveVariableTemplate = maximumVariableTemplate
				.append("/{value32}").toString();
		IllegalArgumentException excessiveVariables = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> McpLevelOneUriTemplate.parse(excessiveVariableTemplate));
		Assertions.assertTrue(excessiveVariables.getMessage().contains(
				"at most 32 variables"), excessiveVariables.getMessage());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> endpointBuilder()
						.resourceTemplate("catalog://items/{itemId}")
						.resourceTemplate("catalog://items/{slug}")
						.build());
		Assertions.assertDoesNotThrow(() -> endpointBuilder()
				.resourceTemplate("catalog://items/{itemId}")
				.resourceTemplate("catalog://users/{userId}")
				.build());
		Assertions.assertDoesNotThrow(() -> endpointBuilder()
				.resourceTemplate("catalog://items/{itemId}")
				.resourceTemplate("catalog://items/{slug}/details")
				.build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> endpointBuilder()
						.resourceTemplate("test://h/%2F/{itemId}")
						.resourceTemplate("test://h/%2f/{slug}")
						.build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpApplicationRequestRouter.fromResourceRoutes(Map.of(), List.of(
						new McpApplicationResourceTemplateRoute(
								"catalog://items/{itemId}",
								new McpApplicationResourceReadRoute(
										ignored -> emptyReadResult())),
						new McpApplicationResourceTemplateRoute(
								"catalog://items/{slug}",
								new McpApplicationResourceReadRoute(
										ignored -> emptyReadResult()))), Optional.empty()));
	}

	@Test
	public void exact_resource_routes_use_rfc3986_syntax_equivalence() {
		McpApplicationResourceReadRoute route =
				new McpApplicationResourceReadRoute(ignored -> emptyReadResult());
		McpApplicationRequestRouter router = McpApplicationRequestRouter
				.fromResourceRoutes(Map.of("CATALOG://ITEMS/a%2Fb", route),
						List.of(), Optional.empty());

		Assertions.assertSame(route, router.resolveExactResource(
				"catalog://items/a%2fb").orElseThrow());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpApplicationRequestRouter.fromResourceRoutes(Map.of(
						"CATALOG://ITEMS/a%2Fb", route,
						"catalog://items/a%2fb", route), List.of(), Optional.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> endpointBuilder()
						.exactResource("CATALOG://ITEMS/a%2Fb")
						.exactResource("catalog://items/a%2fb")
						.build());
	}

	@Test
	public void level_one_template_matching_is_linear_for_failed_long_values() {
		McpLevelOneUriTemplate template =
				McpLevelOneUriTemplate.parse("test:///{value}Z");
		String uri = "test:///" + "a".repeat(131_072) + "Y";

		assertTimeout(java.time.Duration.ofSeconds(2),
				() -> Assertions.assertTrue(template.match(uri).isEmpty()));
	}

	@Test
	public void maximum_shape_level_one_template_matching_is_bounded() {
		StringBuilder template = new StringBuilder("test:///");
		for (int index = 0; index < 32; ++index)
			template.append("{value").append(index).append("}a");
		McpLevelOneUriTemplate parsed = McpLevelOneUriTemplate.parse(
				template.append('Z').toString());
		String uri = "test:///" + "a".repeat(65_536) + "Y";

		assertTimeout(java.time.Duration.ofSeconds(5),
				() -> Assertions.assertTrue(parsed.match(uri).isEmpty()));
	}

	private static McpNormalizedEndpoint.Builder endpointBuilder() {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"resource-protocol-test", "3.6.0-SNAPSHOT"));
	}

	private static McpHttpServerRuntime runtime(McpNormalizedEndpoint endpoint,
			McpApplicationRequestRouter router,
			McpProtocolAdmissionController protocolAdmissionController,
			Optional<McpRateLimiter> requestRateLimiter) {
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), protocolAdmissionController);
		if (requestRateLimiter.isPresent())
			policy = policy.withRequestRateLimiter(requestRateLimiter.orElseThrow());
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), policy, endpoint,
				router, McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM);
	}

	private static McpWireResult emptyReadResult() {
		return McpWireResult.complete(new McpJsonObject(
				Map.of("contents", new McpJsonArray(List.of(new McpJsonObject(Map.of(
						"uri", new McpJsonString("test://result"),
						"text", new McpJsonString(""))))))));
	}

	private static FixedResponse read(int port, String id, String uri)
			throws Exception {
		return send(port, request(id, "resources/read",
				",\"uri\":\"" + uri + "\""), headers("resources/read", uri));
	}

	private static FixedResponse send(int port, String body,
			List<McpChunkedHttpClient.RequestHeader> headers) throws Exception {
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, body, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			return new FixedResponse(head, client.readFixedBody(head));
		}
	}

	private static List<McpChunkedHttpClient.RequestHeader> headers(
			String method, String name) {
		List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>();
		headers.add(new McpChunkedHttpClient.RequestHeader(
				"MCP-Protocol-Version", PROTOCOL_VERSION));
		headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Method", method));
		if (name != null)
			headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Name", name));
		return List.copyOf(headers);
	}

	private static String request(String id, String method,
			String additionalParameters) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
	}

	private static void assertError(FixedResponse response, int status,
			int code, String id) {
		Assertions.assertEquals(status, response.head().status(), response.body());
		Assertions.assertTrue(response.body().contains("\"code\":" + code),
				response.body());
		Assertions.assertTrue(response.body().contains("\"id\":\"" + id + "\""),
				response.body());
	}

	private record FixedResponse(McpChunkedHttpClient.HttpResponseHead head,
			String body) {
	}
}
