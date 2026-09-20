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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Current-request Apps audience projection, authorization, and localization. */
@ThreadSafe
@Timeout(120)
class McpAppProjectionPublicRuntimeTests {
	private static final String PATH = "/apps-projection";
	private static final String PROTOCOL = "2026-07-28";
	private static final URI UI_URI = URI.create("ui://example/view");
	private static final String APPS = "{\"extensions\":{\"io.modelcontextprotocol/ui\":{"
			+ "\"mimeTypes\":[\"TEXT/HTML; profile=\\\"mcp-app\\\"\"]}}}";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(5))
			.startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());

	@Test
	void typedAndRawAudiencesUseEachRequestsCapabilitiesWithoutCatalogLeaks() {
		AtomicInteger calls = new AtomicInteger();
		McpEndpoint endpoint = endpoint()
				.addTool(tool("ordinary", calls).metadata(unknownMetadata()).build())
				.addTool(tool("typed-both", calls).appToolMetadata(McpAppToolMetadata.builder()
						.resourceUri(UI_URI).build()).metadata(unknownMetadata()).build())
				.addTool(tool("raw-model", calls).metadata(rawVisibility("model")).build())
				.addTool(tool("raw-app", calls).metadata(rawVisibility("app")).build())
				.addTool(tool("empty", calls).appToolMetadata(McpAppToolMetadata.builder()
						.visibility(Set.of()).build()).build())
				.addResource(resource()).build();
		run(endpoint, builder -> {}, simulator -> {
			List<String> capabilityCases = List.of("{}", APPS,
					"{\"extensions\":{\"io.modelcontextprotocol/ui\":{}}}",
					APPS, "{}");
			assertEquals(5, capabilityCases.size());
			for (int index = 0; index < 5; ++index) {
				String capabilities = capabilityCases.get(index);
				boolean apps = capabilities.equals(APPS);
				Capture capture = execute(simulator, request("tools/list", null,
						capabilities, "allowed", "fr"));
				assertEquals(200, capture.status(), capture.body());
				assertEquals(apps ? List.of("ordinary", "typed-both", "raw-model", "raw-app")
						: List.of("ordinary", "typed-both", "raw-model"), names(capture));
				List<McpJsonObject> tools = tools(capture);
				assertEquals(unknownMetadata(), tools.get(0).getMembers().get("_meta"));
				McpJsonObject typedMetadata = object(tools.get(1).getMembers().get("_meta"));
				assertEquals("retained", string(typedMetadata.getMembers().get("owner")));
				McpJsonObject typedUi = object(typedMetadata.getMembers().get("ui"));
				assertEquals("retained", string(typedUi.getMembers().get("vendor/example")));
				assertEquals(apps, typedUi.getMembers().containsKey("resourceUri"));
				assertEquals(apps, typedUi.getMembers().containsKey("visibility"));
				assertEquals(apps, tools.get(2).getMembers().containsKey("_meta"));
			}
			assertEquals(0, calls.get());
		});
	}

	@Test
	void callerAuthorizationRunsBeforeAudienceProjectionAndCapabilityDiagnostics() {
		AtomicInteger calls = new AtomicInteger();
		List<String> evaluated = new ArrayList<>();
		McpEndpoint endpoint = endpoint()
				.addTool(tool("app", calls).metadata(rawVisibility("app")).build())
				.addTool(tool("empty", calls).metadata(rawVisibility()).build())
				.addTool(tool("model", calls).metadata(rawVisibility("model")).build())
				.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					evaluated.add(registration.getName());
					return context.getRequest().getHeader("Authorization").orElseThrow()
							.equals("Bearer allowed");
				}, (context, registration, features) -> true);
		run(endpoint, builder -> builder.catalogAccessPolicy(policy), simulator -> {
			Capture fallback = execute(simulator, request("tools/list", null, "{}", "allowed", "fr"));
			assertEquals(List.of("model"), names(fallback));
			assertEquals(List.of("app", "empty", "model"), evaluated);
			evaluated.clear();
			Capture hidden = execute(simulator, request("tools/call", "app", "{}", "hidden", "fr"));
			assertEquals(List.of("app"), evaluated);
			Capture unknown = execute(simulator, request("tools/call", "unknown", "{}", "hidden", "fr"));
			Capture empty = execute(simulator, request("tools/call", "empty", APPS, "allowed", "fr"));
			assertEquals(error(unknown), error(hidden));
			assertEquals(error(unknown), error(empty));
			assertFalse(hidden.body().contains("requiredCapabilities"));
			assertEquals(0, calls.get());
			assertMissingApps(execute(simulator,
					request("tools/call", "app", "{}", "allowed", "fr")));
			assertEquals(0, calls.get());
			assertEquals(200, execute(simulator,
					request("tools/call", "app", APPS, "allowed", "fr")).status());
			assertEquals(1, calls.get());
		});
	}

	@Test
	void directAppOnlyCallsRequireExactMimeEvenWithoutExplicitCatalogPolicy() {
		AtomicInteger calls = new AtomicInteger();
		McpEndpoint endpoint = endpoint()
				.addTool(tool("app", calls).appToolMetadata(McpAppToolMetadata.builder()
						.visibility(Set.of(McpAppToolMetadata.Visibility.APP)).build()).build())
				.addTool(tool("empty", calls).metadata(rawVisibility()).build())
				.addTool(tool("model", calls).metadata(rawVisibility("model")).build()).build();
		run(endpoint, builder -> {}, simulator -> {
			List<String> capabilityCases = List.of("{}",
					"{\"extensions\":{\"io.modelcontextprotocol/ui\":{}}}",
					"{\"extensions\":{\"io.modelcontextprotocol/ui\":{\"mimeTypes\":[\"text/html\"]}}}",
					"{\"extensions\":{\"io.modelcontextprotocol/ui\":{\"mimeTypes\":[\"text/html;profile=mcp-app\",5]}}}");
			assertEquals(4, capabilityCases.size());
			for (int index = 0; index < 4; ++index)
				assertMissingApps(execute(simulator,
						request("tools/call", "app", capabilityCases.get(index), "allowed", "fr")));
			assertEquals(0, calls.get());
			Capture unknown = execute(simulator, request("tools/call", "unknown", "{}", "allowed", "fr"));
			assertEquals(error(unknown), error(execute(simulator,
					request("tools/call", "empty", "{}", "allowed", "fr"))));
			assertEquals(200, execute(simulator,
					request("tools/call", "app", APPS, "allowed", "fr")).status());
			Capture fallback = execute(simulator, request("tools/call", "model", "{}", "allowed", "fr"));
			assertEquals(200, fallback.status());
			assertTrue(fallback.body().contains("ordinary text fallback"), fallback.body());
			assertFalse(fallback.body().contains("ui://"), fallback.body());
			assertEquals(2, calls.get());
		});
	}

	@Test
	void localizedCatalogBoundsAndStableOwnersFollowExactCapabilityProjection() {
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger lookups = new AtomicInteger();
		McpEndpoint endpoint = endpoint()
				.addTool(tool("app", calls).title("App title").metadata(rawVisibility("app")).build())
				.addTool(tool("model", calls).title("Model title").metadata(rawVisibility("model")).build())
				.build();
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
			Locale locale = Locale.forLanguageTag(request.getLanguageRanges().get(0).getRange());
			return McpLocalizationContext.withLocale(locale, text -> {
				lookups.incrementAndGet();
				return McpLocalizationResult.localized(locale.getLanguage() + ":" + text.getDefaultText());
			}).build();
		}).maximumLocalizableTextCountPerResponse(1)
				.failurePolicy(McpLocalizationFailurePolicy.FAIL_REQUEST).build();
		run(endpoint, builder -> builder.localizer(localizer), simulator -> {
			Capture french = execute(simulator, request("tools/list", null, "{}", "allowed", "fr"));
			assertEquals(200, french.status(), french.body());
			assertEquals(List.of("model"), names(french));
			assertEquals("fr:Model title", string(tools(french).get(0).getMembers().get("title")));
			Capture apps = execute(simulator, request("tools/list", null, APPS, "allowed", "fr"));
			assertEquals(500, apps.status(), apps.body());
			Capture german = execute(simulator, request("tools/list", null, "{}", "allowed", "de"));
			assertEquals(200, german.status(), german.body());
			assertEquals("de:Model title", string(tools(german).get(0).getMembers().get("title")));
			assertEquals(2, lookups.get());
			assertEquals(0, calls.get());
		});
	}

	@Test
	void standaloneExactResourceAdvertisesAppsWithoutInvokingCustomListing() {
		McpEndpoint endpoint = endpoint().addResource(resource())
				.resourceListHandler((request, list, features) -> {
					throw new AssertionError("Discovery must not invoke custom listing.");
				}).build();
		run(endpoint, builder -> {}, simulator -> {
			Capture discovery = execute(simulator, request("server/discover", null, "{}", "allowed", "fr"));
			assertEquals(200, discovery.status(), discovery.body());
			McpJsonObject capabilities = object(result(discovery).getMembers().get("capabilities"));
			McpJsonObject extensions = object(capabilities.getMembers().get("extensions"));
			assertEquals(parse("{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}"),
					extensions.getMembers().get("io.modelcontextprotocol/ui"));
		});
	}

	@Test
	void hiddenAppCatalogDoesNotFailStartupButVisibleOversizedProjectionIsBounded() {
		AtomicInteger calls = new AtomicInteger();
		McpEndpoint.Builder endpoint = endpoint();
		String largeValue = "x".repeat(900_000);
		for (int index = 0; index < 5; ++index)
			endpoint.addTool(tool("large-app-" + index, calls)
					.appToolMetadata(McpAppToolMetadata.builder()
							.visibility(Set.of(McpAppToolMetadata.Visibility.APP)).build())
					.metadata(McpJsonObject.builder().put("retained", largeValue).build()).build());
		run(endpoint.build(), builder -> {}, simulator -> {
			Capture fallback = execute(simulator, request("tools/list", null, "{}", "allowed", "fr"));
			assertEquals(200, fallback.status(), fallback.body());
			assertEquals(List.of(), names(fallback));
			Capture apps = execute(simulator, request("tools/list", null, APPS, "allowed", "fr"));
			assertEquals(500, apps.status(), apps.body());
			assertFalse(apps.body().contains("large-app-"));
			assertEquals(200, execute(simulator,
					request("tools/list", null, "{}", "allowed", "fr")).status());
			assertEquals(0, calls.get());
		});
	}

	@Test
	void listenerAndSimulatorReturnIdenticalCatalogBytesAcrossCapabilityChanges() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		McpEndpoint endpoint = endpoint()
				.addTool(tool("app", calls).metadata(rawVisibility("app")).build())
				.addTool(tool("model", calls).metadata(rawVisibility("model")).build()).build();
		List<Capture> simulated = new ArrayList<>();
		List<String> capabilities = List.of("{}", APPS, "{}", APPS);
		assertEquals(4, capabilities.size());
		run(endpoint, builder -> {}, simulator -> {
			for (int index = 0; index < 4; ++index)
				simulated.add(execute(simulator,
						request("tools/list", null, capabilities.get(index), "allowed", "fr")));
		});
		McpServer server = McpServer.withPort(0).host("127.0.0.1")
				.allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> McpAdmissionDecision.accepted())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.subscriptionAuthorizer(McpSubscriptionAuthorizer.denyAllInstance()).build();
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY).build());
		HttpClient client = HttpClient.newBuilder().connectTimeout(WAIT)
				.version(HttpClient.Version.HTTP_1_1).build();
		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (int index = 0; index < 4; ++index) {
				Request request = request("tools/list", null, capabilities.get(index), "allowed", "fr");
				HttpRequest.Builder http = HttpRequest.newBuilder()
						.uri(URI.create("http://127.0.0.1:" + port + PATH)).timeout(WAIT)
						.POST(HttpRequest.BodyPublishers.ofByteArray(request.getBody().orElseThrow()));
				request.getHeaders().forEach((name, values) -> {
					if (!name.equalsIgnoreCase("Host"))
						values.forEach(value -> http.header(name, value));
				});
				HttpResponse<String> response = client.send(http.build(), HttpResponse.BodyHandlers.ofString());
				assertEquals(simulated.get(index), new Capture(response.statusCode(), response.body()));
			}
			assertEquals(0, calls.get());
		} finally {
			owner.close();
		}
	}

	private static void assertMissingApps(Capture capture) {
		assertEquals(400, capture.status(), capture.body());
		McpJsonObject error = error(capture);
		assertEquals(-32021, ((McpJsonNumber) error.getMembers().get("code"))
				.getValue().intValueExact());
		assertEquals(parse("{\"requiredCapabilities\":{\"extensions\":{\"io.modelcontextprotocol/ui\":"
				+ "{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}}}}"), error.getMembers().get("data"));
	}

	private static McpEndpoint.Builder endpoint() {
		return McpEndpoint.withPath(PATH, McpImplementation.withNameAndVersion("apps", "test").build())
				.serverInfoIncluded(false);
	}

	private static McpResourceRegistration resource() {
		return McpResourceRegistration.withUriAndName(UI_URI, "view")
				.handler((request, resource, features) -> {
					throw new AssertionError("Must not invoke resource handler.");
				}).mimeType("TEXT/HTML; profile=\"mcp-app\"").build();
	}

	private static McpToolRegistration.OperationBuilder<McpJsonObject> tool(String name, AtomicInteger calls) {
		return McpToolRegistration.withName(name).jsonObjectArguments()
				.handler((request, arguments, features) -> {
					calls.incrementAndGet();
					return McpCompleteResult.fromToolOutput(McpToolOutput.fromText("ordinary text fallback"));
				});
	}

	private static McpJsonObject rawVisibility(String... visibility) {
		McpJsonArray.Builder audiences = McpJsonArray.builder();
		for (String audience : visibility)
			audiences.add(audience);
		return McpJsonObject.builder().put("ui", McpJsonObject.builder()
				.put("visibility", audiences.build()).build()).build();
	}

	private static McpJsonObject unknownMetadata() {
		return McpJsonObject.builder().put("owner", "retained").put("ui",
				McpJsonObject.builder().put("vendor/example", "retained").build()).build();
	}

	private static void run(McpEndpoint endpoint, Consumer<McpServer.Builder> configure,
			Consumer<Simulator> action) {
		SokletSimulator.run(SimulatorConfig.builder()
				.configureMcpServer(builder -> {
					builder.port(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
							.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
							.admissionController(context -> McpAdmissionDecision.accepted())
							.requestRateLimiter(context -> McpRateLimitDecision.allowed())
							.toolRateLimiter(context -> McpRateLimitDecision.allowed())
							.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
							.subscriptionAuthorizer(McpSubscriptionAuthorizer.denyAllInstance());
					configure.accept(builder);
				}).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY).build(), action::accept);
	}

	private static Request request(String method, String toolName, String capabilities,
			String caller, String language) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"apps-request\",\"method\":\"" + method
				+ "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":" + capabilities + "}"
				+ (toolName == null ? "" : ",\"name\":\"" + toolName + "\",\"arguments\":{}") + "}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>(Map.of(
				"Host", Set.of("127.0.0.1:0"), "Authorization", Set.of("Bearer " + caller),
				"Content-Type", Set.of("application/json"), "Accept", Set.of("application/json, text/event-stream"),
				"MCP-Protocol-Version", Set.of(PROTOCOL), "Mcp-Method", Set.of(method),
				"Accept-Language", Set.of(language)));
		if (toolName != null)
			headers.put("Mcp-Name", Set.of(toolName));
		return Request.withPath(HttpMethod.POST, PATH).headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static Capture execute(Simulator simulator, Request request) {
		McpSimulation simulation = simulator.startMcpRequest(request);
		try {
			McpSimulationResponse response = simulation.awaitResponse(WAIT).orElseThrow();
			String body = new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
			simulation.awaitCompletion(WAIT).orElseThrow();
			return new Capture(response.getStatusCode(), body);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static McpJsonObject parse(String json) {
		return (McpJsonObject) McpPublicJsonValueConverter.toPublic(JSON.parse(json));
	}

	private static McpJsonObject object(McpJsonValue value) {
		return (McpJsonObject) value;
	}

	private static String string(McpJsonValue value) {
		return ((McpJsonString) value).getValue();
	}

	private static McpJsonObject result(Capture capture) {
		return object(parse(capture.body()).getMembers().get("result"));
	}

	private static McpJsonObject error(Capture capture) {
		return object(parse(capture.body()).getMembers().get("error"));
	}

	private static List<McpJsonObject> tools(Capture capture) {
		return ((McpJsonArray) result(capture).getMembers().get("tools")).getElements().stream()
				.map(McpAppProjectionPublicRuntimeTests::object).toList();
	}

	private static List<String> names(Capture capture) {
		return tools(capture).stream().map(tool -> string(tool.getMembers().get("name"))).toList();
	}

	private record Capture(int status, String body) {}
}
