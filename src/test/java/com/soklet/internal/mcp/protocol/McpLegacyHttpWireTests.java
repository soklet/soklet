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
import com.soklet.McpLocalizationContext;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class McpLegacyHttpWireTests {
	private static final McpJsonLimits LIMITS = new McpJsonLimits(
			65_536, 256, 16_384, 16_384, 512, 10_000, 16_384, 65_536);
	private static final McpJsonCodec JSON = new McpJsonCodec(LIMITS);
	private static final McpJsonRpcEnvelopeCodec ENVELOPES =
			new McpJsonRpcEnvelopeCodec(JSON);

	@Test
	public void modernFramingCannotBeReinterpretedByALegacyVersionHeader() {
		McpJsonRpcEnvelope initialize = ENVELOPES.decode("""
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
				"protocolVersion":"2025-11-25","capabilities":{},
				"clientInfo":{"name":"client","version":"1"}}}
				""");
		McpJsonRpcEnvelope modern = ENVELOPES.decode("""
				{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{
				"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
				"io.modelcontextprotocol/clientCapabilities":{}}}}
				""");

		Assertions.assertEquals(McpLegacyHttpWire.Era.LEGACY,
				McpLegacyHttpWire.classify(initialize, List.of(), List.of(),
						List.of(), false));
		Assertions.assertEquals(McpLegacyHttpWire.Era.MODERN,
				McpLegacyHttpWire.classify(initialize, List.of("2025-11-25"),
						List.of("initialize"), List.of(), false));
		Assertions.assertEquals(McpLegacyHttpWire.Era.MODERN,
				McpLegacyHttpWire.classify(initialize, List.of("2025-11-25"),
						List.of(), List.of(), true));
		Assertions.assertEquals(McpLegacyHttpWire.Era.MODERN,
				McpLegacyHttpWire.classify(modern, List.of("2025-11-25"),
						List.of(), List.of(), false));
		Assertions.assertEquals(McpLegacyHttpWire.Era.MODERN,
				McpLegacyHttpWire.classify(modern, List.of("2026-07-28"),
						List.of("tools/list"), List.of(), false));
	}

	@Test
	public void legacyLaterRequestDoesNotInheritInitializationMetadata() {
		McpJsonRpcEnvelope.Request initialize =
				(McpJsonRpcEnvelope.Request) ENVELOPES.decode("""
						{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
						"protocolVersion":"2025-11-25",
						"capabilities":{"roots":{"listChanged":true},"tasks":{"list":{}}},
						"clientInfo":{"name":"client","version":"1","title":"Client App"}}}
						""");
		McpJsonRpcEnvelope.Request later =
				(McpJsonRpcEnvelope.Request) ENVELOPES.decode("""
						{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
						"name":"search","arguments":{"q":"soklet"}}}
						""");
		McpRequestWireMapper mapper = new McpRequestWireMapper(LIMITS);
		McpJsonRpcMessage.Request mappedInitialize =
				McpLegacyRequestWireMapper.map(mapper, "2025-11-25", initialize);
		McpJsonRpcMessage.Request mappedLater =
				McpLegacyRequestWireMapper.map(mapper, "2025-11-25", later);

		Assertions.assertEquals("client", mappedInitialize.params().metadata()
				.clientInformation().orElseThrow().name());
		Assertions.assertEquals("Client App", mappedInitialize.params().metadata()
				.clientInformation().orElseThrow().title().orElseThrow());
		Assertions.assertTrue(mappedInitialize.params().metadata()
				.clientCapabilities().roots().isPresent());
		Assertions.assertTrue(mappedInitialize.params().metadata()
				.clientCapabilities().unknownCapabilities().containsKey("tasks"));
		Assertions.assertTrue(mappedLater.params().metadata()
				.clientInformation().isEmpty());
		Assertions.assertTrue(mappedLater.params().metadata()
				.clientCapabilities().toJsonObject().members().isEmpty());
		Assertions.assertEquals("2025-11-25", mappedLater.params().metadata()
				.protocolVersion());
	}

	@Test
	public void malformedLegacyInitializationCapabilitiesFailClosed() {
		McpRequestWireMapper mapper = new McpRequestWireMapper(LIMITS);
		for (String capabilities : List.of(
				"{\"roots\":{\"listChanged\":\"yes\"}}",
				"{\"sampling\":{\"tools\":true}}",
				"{\"experimental\":{\"example\":true}}",
				"{\"tasks\":{\"list\":true}}")) {
			McpJsonRpcEnvelope.Request request =
					(McpJsonRpcEnvelope.Request) ENVELOPES.decode("""
							{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
							"protocolVersion":"2025-11-25","capabilities":%s,
							"clientInfo":{"name":"client","version":"1"}}}
							""".formatted(capabilities));
			Assertions.assertThrows(McpWireDecodingException.class,
					() -> McpLegacyRequestWireMapper.map(mapper, "2025-11-25", request));
		}
	}

	@Test
	public void ordinaryToolMetadataIsPreservedWithoutAdmittingAppsOrTasks() {
		McpJsonObject ordinaryTool = new McpJsonObject(Map.of(
				"name", new McpJsonString("shared"),
				"inputSchema", new McpJsonObject(Map.of(
						"type", new McpJsonString("object"))),
				"icons", new McpJsonArray(List.of(new McpJsonObject(Map.of(
						"src", new McpJsonString("https://example.com/icon.png"))))),
				"_meta", new McpJsonObject(Map.of(
						"com.example/note", new McpJsonString("modern-only"))),
				"execution", new McpJsonObject(Map.of(
						"taskSupport", new McpJsonString("none")))));
		McpWireResult projected = Mcp2025ProtocolProfile.NOVEMBER_25
				.renderFrameworkResult(McpProfileFrameworkResultKind.TOOLS_LIST,
						McpWireResult.complete(new McpJsonObject(Map.of(
								"tools", new McpJsonArray(List.of(ordinaryTool))))));
		McpJsonObject descriptor = (McpJsonObject) ((McpJsonArray) projected.fields()
				.members().get("tools")).values().get(0);
		Assertions.assertEquals(ordinaryTool.members().get("_meta"),
				descriptor.members().get("_meta"));
		Assertions.assertFalse(descriptor.members().containsKey("execution"));
		Assertions.assertTrue(descriptor.members().containsKey("icons"));
		McpWireResult juneProjection = Mcp2025ProtocolProfile.JUNE_18
				.renderFrameworkResult(McpProfileFrameworkResultKind.TOOLS_LIST,
						McpWireResult.complete(new McpJsonObject(Map.of(
								"tools", new McpJsonArray(List.of(ordinaryTool))))));
		McpJsonObject juneDescriptor = (McpJsonObject) ((McpJsonArray)
				juneProjection.fields().members().get("tools")).values().get(0);
		Assertions.assertFalse(juneDescriptor.members().containsKey("icons"));
		Assertions.assertEquals(ordinaryTool.members().get("_meta"),
				juneDescriptor.members().get("_meta"));

		McpJsonObject appTool = new McpJsonObject(Map.of(
				"name", new McpJsonString("app"),
				"_meta", new McpJsonObject(Map.of("ui", new McpJsonObject(Map.of(
						"resourceUri", new McpJsonString("ui://example/app")))))));
		McpJsonObject taskTool = new McpJsonObject(Map.of(
				"name", new McpJsonString("task"),
				"execution", new McpJsonObject(Map.of(
						"taskSupport", new McpJsonString("required")))));
		for (McpJsonObject incompatible : List.of(appTool, taskTool))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> Mcp2025ProtocolProfile.NOVEMBER_25.renderFrameworkResult(
							McpProfileFrameworkResultKind.TOOLS_LIST,
							McpWireResult.complete(new McpJsonObject(Map.of(
									"tools", new McpJsonArray(List.of(incompatible)))))));
	}

	@Test
	public void legacyToolOutputSchemaMustHaveObjectTypeEvenWithCallerAwareCatalog() {
		McpJsonObject inputSchema = new McpJsonObject(Map.of(
				"type", new McpJsonString("object")));
		McpJsonObject arrayOutputSchema = new McpJsonObject(Map.of(
				"type", new McpJsonString("array")));
		McpNormalizedToolDescriptor descriptor = new McpNormalizedToolDescriptor(
				"array-result", inputSchema, Optional.of(arrayOutputSchema),
				McpJsonObject.empty(), McpJsonObject.empty());
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata
						.withNameAndVersion("server", "4.0.0"))
				.tool(McpNormalizedOperation.tool(descriptor,
						McpMirroredHeaderPlan.empty()))
				.catalogAccessAdapter(ignored ->
						new McpServerRuntimeBridge.CatalogAccessSession() {
							@Override
							public boolean isToolAccessible(@NonNull String toolName) {
								return true;
							}

							@Override
							public boolean isPromptAccessible(@NonNull String promptName) {
								return true;
							}

							@Override
							public Optional<@NonNull McpLocalizationContext>
							localizationContext() {
								return Optional.empty();
							}
						})
				.build();
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
						request -> McpAdmissionDecision.acceptedAnonymous()),
				endpoint, McpApplicationRequestRouter.empty(),
				McpRuntimeObservationSink.disabledInstance(), List.of(),
				Optional.empty(), Map.of("2025-11-25", endpoint));
		IllegalArgumentException failure = Assertions.assertThrows(
				IllegalArgumentException.class, () -> new McpHttpServerRuntime(
						McpHttpTransportConfiguration.productionDefaults(0),
						List.of(binding)));
		Assertions.assertTrue(failure.getMessage().contains(
				"2025 tool output schema must have object type"),
				failure.getMessage());
	}

	@Test
	public void juneToolResultRejectsNovemberResourceLinkIcons() {
		McpWireResult result = McpWireResult.complete(new McpJsonObject(Map.of(
				"content", new McpJsonArray(List.of(new McpJsonObject(Map.of(
						"type", new McpJsonString("resource_link"),
						"name", new McpJsonString("report"),
						"uri", new McpJsonString("file:///report"),
						"icons", new McpJsonArray(List.of()))))))));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> Mcp2025ProtocolProfile.JUNE_18.renderApplicationResult(
						McpProfileApplicationResultKind.TOOL, result));
		Assertions.assertDoesNotThrow(
				() -> Mcp2025ProtocolProfile.NOVEMBER_25.renderApplicationResult(
						McpProfileApplicationResultKind.TOOL, result));
	}

	@Test
	public void legacyToolResultsRequireStructuredContentObjectAndBooleanError() {
		McpJsonArray content = new McpJsonArray(List.of());
		for (Mcp2025ProtocolProfile profile : List.of(
				Mcp2025ProtocolProfile.JUNE_18,
				Mcp2025ProtocolProfile.NOVEMBER_25)) {
			McpWireResult invalidStructured = McpWireResult.complete(
					new McpJsonObject(Map.of("content", content,
							"structuredContent", new McpJsonString("not an object"))));
			McpWireResult invalidError = McpWireResult.complete(
					new McpJsonObject(Map.of("content", content,
							"isError", new McpJsonString("true"))));
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> profile.renderApplicationResult(
							McpProfileApplicationResultKind.TOOL, invalidStructured));
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> profile.renderApplicationResult(
							McpProfileApplicationResultKind.TOOL, invalidError));
		}
	}

	@Test
	public void legacyResultKeepsApplicationMetadataButOmitsModernServerMetadata() {
		McpWireResult result = McpWireResult.complete(new McpJsonObject(Map.of(
				"content", new McpJsonArray(List.of()))),
				Optional.of(new McpResultMetadata(Optional.empty(),
						new McpJsonObject(Map.of("com.example/note",
								new McpJsonString("retained"))))));
		for (Mcp2025ProtocolProfile profile : List.of(
				Mcp2025ProtocolProfile.JUNE_18,
				Mcp2025ProtocolProfile.NOVEMBER_25)) {
			McpWireResult rendered = profile.renderApplicationResult(
					McpProfileApplicationResultKind.TOOL, result);
			McpWireResult withServerInformation = McpWireResult.withServerInformation(
					rendered, Optional.of(McpImplementationMetadata.withNameAndVersion(
							"server", "4.0.0")));
			McpJsonObject projected = McpLegacyResponseWire.projectResult(
					withServerInformation);
			McpJsonObject metadata = (McpJsonObject) projected.members().get("_meta");
			Assertions.assertEquals(new McpJsonString("retained"),
					metadata.members().get("com.example/note"));
			Assertions.assertFalse(metadata.members().containsKey(
					McpResultMetadata.SERVER_INFORMATION_KEY));
			Assertions.assertFalse(projected.members().containsKey("resultType"));
		}
	}

	@Test
	@Timeout(30)
	public void oneUrlServesLegacyLifecycleAndVersionedTools() throws Exception {
		McpImplementationMetadata serverInformation = new McpImplementationMetadata(
				"server", "4.0.0", Optional.of("Soklet Server"),
				Optional.of("Modern description"),
				Optional.of(URI.create("https://example.com/server")),
				List.of(new McpImplementationMetadata.Icon(
						URI.create("https://example.com/icon.png"), Optional.empty(),
						List.of(), Optional.empty(), McpJsonObject.empty())),
				McpJsonObject.empty());
		McpNormalizedEndpoint modern = McpNormalizedEndpoint.withServerInformation(
				serverInformation)
				.tool(McpNormalizedOperation.named("shared"))
				.tool(McpNormalizedOperation.named("modern-only"))
				.build();
		McpNormalizedEndpoint legacy = McpNormalizedEndpoint.withServerInformation(
				serverInformation)
				.tool(McpNormalizedOperation.named("shared"))
				.build();
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger rateChecks = new AtomicInteger();
		AtomicInteger progressAttempts = new AtomicInteger();
		AtomicInteger progressEmissions = new AtomicInteger();
		CopyOnWriteArrayList<McpAdmissionContext> admissionContexts =
				new CopyOnWriteArrayList<>();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), request -> {
					admissions.incrementAndGet();
					admissionContexts.add(request);
					return McpAdmissionDecision.acceptedAnonymous();
				}).withRequestRateLimiter(request -> {
					rateChecks.incrementAndGet();
					return McpRateLimitDecision.allowed();
				});
		McpApplicationRequestRouter router = McpApplicationRequestRouter
				.fromHandlers(Map.of("tools/call", invocation -> {
					if (invocation.request().params().metadata().progressToken().isPresent()) {
						progressAttempts.incrementAndGet();
						Optional<McpServerRuntimeBridge.ProgressEmitter> emitter =
								McpServerRuntimeBridge.progressEmitterFor(invocation,
										McpInputRequestPlan.empty());
						if (emitter.isPresent()) {
							progressEmissions.incrementAndGet();
							emitter.orElseThrow().emit(1.0d, Optional.empty(),
									Optional.of("working"));
						}
					}
					return McpWireResult.complete(new McpJsonObject(Map.of(
							"content", new McpJsonArray(List.of(
								new McpJsonObject(Map.of(
										"type", new McpJsonString("text"),
										"text", new McpJsonString("ok"))))))));
				}));
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy,
				modern, router, McpRuntimeObservationSink.disabledInstance(),
				List.of(), Optional.empty(), Map.of(
						"2026-07-28", modern, "2025-11-25", legacy,
						"2025-06-18", legacy));
		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1).build();
		try (McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), List.of(binding))) {
			int port = runtime.start().getPort();
			URI uri = URI.create("http://127.0.0.1:" + port + "/mcp");
			HttpResponse<String> initialized = post(client, uri, Optional.empty(),
					Optional.empty(), """
					{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
					"protocolVersion":"2025-11-25","capabilities":{"roots":{}},
					"clientInfo":{"name":"client","version":"1"}}}
					""");
			Assertions.assertEquals(200, initialized.statusCode(), initialized.body());
			Assertions.assertTrue(initialized.body().contains(
					"\"protocolVersion\":\"2025-11-25\""), initialized.body());
			Assertions.assertFalse(initialized.body().contains("resultType"),
					initialized.body());
			McpJsonObject novemberResult = (McpJsonObject) ((McpJsonObject)
					JSON.parse(initialized.body())).members().get("result");
			McpJsonObject novemberInformation = (McpJsonObject)
					novemberResult.members().get("serverInfo");
			Assertions.assertTrue(novemberInformation.members().containsKey("description"));
			Assertions.assertTrue(novemberInformation.members().containsKey("websiteUrl"));
			Assertions.assertTrue(novemberInformation.members().containsKey("icons"));
			McpAdmissionContext initializeContext = admissionContexts.stream()
					.filter(value -> "initialize".equals(value.jsonRpcMethod()))
					.findFirst().orElseThrow();
			Assertions.assertTrue(initializeContext.clientCapabilities()
					.orElseThrow().roots().isPresent());
			HttpResponse<String> juneInitialization = post(client, uri,
					Optional.empty(), Optional.empty(), """
					{"jsonrpc":"2.0","id":11,"method":"initialize","params":{
					"protocolVersion":"2025-06-18","capabilities":{},
					"clientInfo":{"name":"client","version":"1"}}}
					""");
			Assertions.assertEquals(200, juneInitialization.statusCode(),
					juneInitialization.body());
			Assertions.assertTrue(juneInitialization.body().contains(
					"\"protocolVersion\":\"2025-06-18\""),
					juneInitialization.body());
			McpJsonObject juneResult = (McpJsonObject) ((McpJsonObject)
					JSON.parse(juneInitialization.body())).members().get("result");
			McpJsonObject juneInformation = (McpJsonObject)
					juneResult.members().get("serverInfo");
			Assertions.assertEquals(Set.of("name", "version", "title"),
					juneInformation.members().keySet());
			HttpResponse<String> counteroffer = post(client, uri,
					Optional.empty(), Optional.empty(), """
					{"jsonrpc":"2.0","id":12,"method":"initialize","params":{
					"protocolVersion":"2025-03-26","capabilities":{},
					"clientInfo":{"name":"client","version":"1"}}}
					""");
			Assertions.assertEquals(200, counteroffer.statusCode(), counteroffer.body());
			Assertions.assertTrue(counteroffer.body().contains(
					"\"protocolVersion\":\"2025-11-25\""),
					counteroffer.body());

			HttpResponse<String> ready = post(client, uri,
					Optional.of("2025-11-25"), Optional.empty(),
					"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
			Assertions.assertEquals(202, ready.statusCode(), ready.body());
			Assertions.assertTrue(ready.body().isEmpty());

			HttpResponse<String> listing = post(client, uri,
					Optional.of("2025-11-25"), Optional.empty(),
					"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
			Assertions.assertEquals(200, listing.statusCode(), listing.body());
			Assertions.assertTrue(listing.body().contains("\"shared\""), listing.body());
			Assertions.assertFalse(listing.body().contains("modern-only"), listing.body());
			Assertions.assertFalse(listing.body().contains("resultType"), listing.body());
			Assertions.assertTrue(admissionContexts.stream()
					.filter(value -> "tools/list".equals(value.jsonRpcMethod()))
					.findFirst().orElseThrow().clientCapabilities().isEmpty());
			HttpResponse<String> juneListing = post(client, uri,
					Optional.of("2025-06-18"), Optional.empty(),
					"{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/list\"}");
			Assertions.assertEquals(200, juneListing.statusCode(), juneListing.body());
			Assertions.assertTrue(juneListing.body().contains("\"shared\""),
					juneListing.body());

			HttpResponse<String> call = post(client, uri,
					Optional.of("2025-11-25"), Optional.empty(), """
					{"jsonrpc":"2.0","id":3,"method":"tools/call",
					"params":{"name":"shared","arguments":{}}}
					""");
			Assertions.assertEquals(200, call.statusCode(), call.body());
			Assertions.assertTrue(call.body().contains("\"text\":\"ok\""), call.body());
			Assertions.assertFalse(call.body().contains("resultType"), call.body());
			HttpResponse<String> callWithProgress = post(client, uri,
					Optional.of("2025-11-25"), Optional.empty(), """
					{"jsonrpc":"2.0","id":32,"method":"tools/call",
					"params":{"name":"shared","arguments":{},
					"_meta":{"progressToken":"legacy-progress"}}}
					""");
			Assertions.assertEquals(200, callWithProgress.statusCode(),
					callWithProgress.body());
			Assertions.assertTrue(callWithProgress.headers()
					.firstValue("Content-Type").orElse("")
					.startsWith("application/json"));
			Assertions.assertTrue(callWithProgress.body().contains("\"text\":\"ok\""),
					callWithProgress.body());
			Assertions.assertFalse(callWithProgress.body().contains("data:"),
					callWithProgress.body());
			Assertions.assertEquals(1, progressAttempts.get());
			Assertions.assertEquals(0, progressEmissions.get());
			HttpResponse<String> hiddenCall = post(client, uri,
					Optional.of("2025-11-25"), Optional.empty(), """
					{"jsonrpc":"2.0","id":31,"method":"tools/call",
					"params":{"name":"modern-only","arguments":{}}}
					""");
			Assertions.assertEquals(400, hiddenCall.statusCode(), hiddenCall.body());
			Assertions.assertFalse(hiddenCall.body().contains("\"text\":\"ok\""),
					hiddenCall.body());

			HttpResponse<String> modernDiscovery = post(client, uri,
					Optional.of("2026-07-28"), Optional.of("server/discover"), """
					{"jsonrpc":"2.0","id":4,"method":"server/discover","params":{
					"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
					"io.modelcontextprotocol/clientCapabilities":{}}}}
					""");
			Assertions.assertEquals(200, modernDiscovery.statusCode(),
					modernDiscovery.body());
			Assertions.assertTrue(modernDiscovery.body().contains("2025-11-25"),
					modernDiscovery.body());
			HttpResponse<String> modernListing = post(client, uri,
					Optional.of("2026-07-28"), Optional.of("tools/list"), """
					{"jsonrpc":"2.0","id":5,"method":"tools/list","params":{
					"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
					"io.modelcontextprotocol/clientCapabilities":{}}}}
					""");
			Assertions.assertEquals(200, modernListing.statusCode(),
					modernListing.body());
			Assertions.assertTrue(modernListing.body().contains("modern-only"),
					modernListing.body());
			HttpResponse<String> mixedFraming = post(client, uri,
					Optional.of("2025-11-25"), Optional.of("tools/list"), """
					{"jsonrpc":"2.0","id":6,"method":"tools/list","params":{
					"_meta":{"io.modelcontextprotocol/protocolVersion":"2025-11-25",
					"io.modelcontextprotocol/clientCapabilities":{}}}}
					""");
			Assertions.assertEquals(400, mixedFraming.statusCode(),
					mixedFraming.body());
			Assertions.assertTrue(mixedFraming.body().contains("\"code\":-32022"),
					mixedFraming.body());
			HttpResponse<String> mixedArgumentMirrors = client.send(
					HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
							.header("Content-Type", "application/json")
							.header("Accept", "application/json, text/event-stream")
							.header("MCP-Protocol-Version", "2025-11-25")
							.header("Mcp-Param-query", "ignored")
							.POST(HttpRequest.BodyPublishers.ofString("""
								{"jsonrpc":"2.0","id":7,"method":"tools/call",
								"params":{"name":"shared","arguments":{}}}
								"""))
							.build(), HttpResponse.BodyHandlers.ofString());
			Assertions.assertEquals(400, mixedArgumentMirrors.statusCode(),
					mixedArgumentMirrors.body());
			Assertions.assertTrue(mixedArgumentMirrors.body()
					.contains("\"code\":-32020"), mixedArgumentMirrors.body());
			HttpResponse<String> get = client.send(HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(10))
					.header("Accept", "text/event-stream")
					.header("MCP-Protocol-Version", "2025-11-25")
					.GET().build(), HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> delete = client.send(HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(10))
					.header("MCP-Protocol-Version", "2025-11-25")
					.DELETE().build(), HttpResponse.BodyHandlers.ofString());
			Assertions.assertEquals(405, get.statusCode());
			Assertions.assertEquals(405, delete.statusCode());
			Assertions.assertEquals(admissions.get(), rateChecks.get());
			Assertions.assertTrue(admissions.get() >= 9);
		}
	}

	@Test
	@Timeout(30)
	public void anEmptyLegacyViewCannotCallModernOnlyProductionTool()
			throws Exception {
		McpImplementationMetadata serverInformation =
				McpImplementationMetadata.withNameAndVersion("server", "4.0.0");
		McpNormalizedEndpoint modern = McpNormalizedEndpoint
				.withServerInformation(serverInformation)
				.tool(McpNormalizedOperation.named("modern-only"))
				.build();
		McpNormalizedEndpoint legacy = McpNormalizedEndpoint
				.withServerInformation(serverInformation).build();
		AtomicInteger handlerCalls = new AtomicInteger();
		McpApplicationRequestRouter router = McpApplicationRequestRouter
				.fromToolRoutes(Map.of("modern-only", new McpApplicationToolRoute(
						invocation -> {
							handlerCalls.incrementAndGet();
							return McpWireResult.complete(new McpJsonObject(Map.of(
									"content", new McpJsonArray(List.of()))));
						}, request -> McpRateLimitDecision.allowed())));
		McpHttpEndpointPolicy sharedPolicy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				request -> McpAdmissionDecision.acceptedAnonymous());
		McpHttpEndpointBinding shared = new McpHttpEndpointBinding(sharedPolicy,
				modern, router, McpRuntimeObservationSink.disabledInstance(),
				List.of(), Optional.empty(), Map.of(
						"2026-07-28", modern, "2025-11-25", legacy));
		McpHttpEndpointPolicy modernPolicy = new McpHttpEndpointPolicy(
				"/modern", Set.of(), McpAbsentOriginPolicy.ALLOW,
				CorsAuthorizer.rejectAllInstance(),
				request -> McpAdmissionDecision.acceptedAnonymous());
		McpHttpEndpointBinding modernOnly = new McpHttpEndpointBinding(
				modernPolicy, modern, router);
		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1).build();
		try (McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				List.of(shared, modernOnly))) {
			int port = runtime.start().getPort();
			URI sharedUri = URI.create("http://127.0.0.1:" + port + "/mcp");
			URI modernUri = URI.create("http://127.0.0.1:" + port + "/modern");
			HttpResponse<String> hiddenListing = post(client, sharedUri,
					Optional.of("2025-11-25"), Optional.empty(),
					"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
			Assertions.assertEquals(404, hiddenListing.statusCode(),
					hiddenListing.body());
			HttpResponse<String> hiddenCall = post(client, sharedUri,
					Optional.of("2025-11-25"), Optional.empty(), """
					{"jsonrpc":"2.0","id":2,"method":"tools/call",
					"params":{"name":"modern-only","arguments":{}}}
					""");
			Assertions.assertEquals(400, hiddenCall.statusCode(), hiddenCall.body());
			Assertions.assertEquals(0, handlerCalls.get());

			String modernFramedLegacySelector = """
					{"jsonrpc":"2.0","id":3,"method":"initialize",
					"params":{"protocolVersion":"2025-11-25",
					"capabilities":{},"clientInfo":{"name":"client","version":"1"}}}
					""";
			HttpResponse<String> sharedDiagnostic = post(client, sharedUri,
					Optional.of("2025-11-25"), Optional.of("initialize"),
					modernFramedLegacySelector);
			HttpResponse<String> modernDiagnostic = post(client, modernUri,
					Optional.of("2025-11-25"), Optional.of("initialize"),
					modernFramedLegacySelector);
			Assertions.assertEquals(400, sharedDiagnostic.statusCode(),
					sharedDiagnostic.body());
			Assertions.assertEquals(400, modernDiagnostic.statusCode(),
					modernDiagnostic.body());
			Assertions.assertTrue(sharedDiagnostic.body().contains(
					"\"2025-11-25\""), sharedDiagnostic.body());
			Assertions.assertTrue(modernDiagnostic.body().contains(
					"\"supported\":[\"2026-07-28\"]"),
					modernDiagnostic.body());
		}
	}

	private static HttpResponse<String> post(HttpClient client, URI uri,
			Optional<String> revision, Optional<String> method, String body)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		revision.ifPresent(value -> request.header("MCP-Protocol-Version", value));
		method.ifPresent(value -> request.header("Mcp-Method", value));
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
