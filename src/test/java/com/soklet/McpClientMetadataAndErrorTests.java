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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for the approved error and client-metadata contracts. */
@Timeout(60)
class McpClientMetadataAndErrorTests {
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final Set<McpProtocolVersion> ALL = Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);

	@Test
	void resourceNotFoundHasCanonicalDataAndDistinctRevisionIntent() {
		URI uri = URI.create("test://catalog/item%20one");
		McpJsonRpcError error = McpJsonRpcError.fromResourceNotFound(uri);
		assertEquals(-32602, error.getCode());
		assertEquals("Resource not found", error.getMessage());
		assertEquals(McpJsonObject.builder().put("uri", uri.toString()).build(), error.getData().orElseThrow());
		McpJsonRpcError equal = McpJsonRpcError.fromResourceNotFound(uri);
		assertEquals(error, equal);
		assertEquals(error.hashCode(), equal.hashCode());
		assertNotEquals(error, McpJsonRpcError.fromInvalidParameters(error.getMessage(), error.getData().orElseThrow()));
		assertThrows(IllegalArgumentException.class, () -> McpJsonRpcError.fromApplication(-32002, "Missing"));
		for (String invalid : List.of("relative", "test://catalog/a/../b", "test://catalog/café"))
			assertThrows(IllegalArgumentException.class, () -> McpJsonRpcError.fromResourceNotFound(URI.create(invalid)));
		assertThrows(NullPointerException.class, () -> McpJsonRpcError.fromResourceNotFound(null));
	}

	@Test
	void blankImplementationStringsArePreservedButConfiguredServerIdentityMustBeNonblank() {
		for (String name : List.of("", " ", "\t"))
			for (String version : List.of("", " ", "\t")) {
				McpImplementation implementation = McpImplementation.withNameAndVersion(name, version).build();
				assertEquals(name, implementation.getName());
				assertEquals(version, implementation.getVersion());
				assertEquals(implementation, McpImplementation.withNameAndVersion(name, version).build());
			}
		for (McpImplementation implementation : List.of(
				McpImplementation.withNameAndVersion("", "1").build(),
				McpImplementation.withNameAndVersion("server", " ").build())) {
			assertThrows(IllegalArgumentException.class, () -> McpEndpoint.withPath("/mcp", implementation, ALL));
			assertThrows(IllegalArgumentException.class, () -> McpEndpoint.withPath("/mcp",
					McpImplementation.withNameAndVersion("server", "1").build(), ALL)
					.serverInfoIncluded(false).serverInfo(implementation));
		}
		assertThrows(NullPointerException.class, () -> McpImplementation.withNameAndVersion(null, "1"));
		assertThrows(NullPointerException.class, () -> McpImplementation.withNameAndVersion("name", null));
	}

	@Test
	void blankClientInformationSurvivesAdmissionAndRememberedSessionMetadata() throws Exception {
		for (McpProtocolVersion version : List.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28))
			for (boolean sessions : version == McpProtocolVersion.V2026_07_28 ? List.of(false) : List.of(false, true)) {
				List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
				List<McpRequestContext> handlers = new CopyOnWriteArrayList<>();
				SokletSimulator.run(configuration(sessions, admissions, handlers), simulator -> {
					boolean modern = version == McpProtocolVersion.V2026_07_28;
					String sessionId = null;
					if (!modern) {
						McpSimulationResponse initialized = exchange(simulator, request(version, null,
								initializeBody(version, "{\"name\":\"\",\"version\":\" \",\"title\":\"Client title\"}"), "initialize"));
						assertEquals(200, initialized.getStatusCode(), version + " sessions=" + sessions + " " + text(initialized));
						sessionId = initialized.getHeaders().entrySet().stream()
								.filter(entry -> entry.getKey().equalsIgnoreCase("Mcp-Session-Id"))
								.map(entry -> entry.getValue().get(0)).findFirst().orElse(null);
						assertEquals(sessions, sessionId != null);
						assertBlankClient(admissions.get(0).getClientInfo().orElseThrow());
					}
					String metadata = modern ? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
							+ "\"io.modelcontextprotocol/clientCapabilities\":{},\"io.modelcontextprotocol/clientInfo\":{"
							+ "\"name\":\"\",\"version\":\" \",\"title\":\"Client title\"}}," : "";
					McpSimulationResponse called = exchange(simulator, request(version, sessionId,
							"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{" + metadata
									+ "\"name\":\"metadata\",\"arguments\":{}}}", "tools/call"));
					assertEquals(200, called.getStatusCode(), text(called));
					assertTrue(text(called).contains("\"text\":\"called\""), text(called));
					assertEquals(1, handlers.size());
					if (modern) assertBlankClient(admissions.get(admissions.size() - 1).getClientInfo().orElseThrow());
					else assertTrue(admissions.get(admissions.size() - 1).getClientInfo().isEmpty(),
							"Legacy POST admission precedes owner-verified session lookup.");
					if (modern || sessions) assertBlankClient(handlers.get(0).getClientInfo().orElseThrow());
					else assertTrue(handlers.get(0).getClientInfo().isEmpty());
				});
			}
	}

	@Test
	void sessionCallsAcceptBodiesLargerThanThePersistentEvidenceBudget() throws Exception {
		String payload = "x".repeat(650_000);
		for (McpProtocolVersion version : List.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25))
			for (boolean sessions : List.of(false, true)) {
				List<McpRequestContext> handlers = new CopyOnWriteArrayList<>();
				SokletSimulator.run(configuration(sessions, new CopyOnWriteArrayList<>(), handlers), simulator -> {
					McpSimulationResponse initialized = exchange(simulator, request(version, null,
							initializeBody(version, "{\"name\":\"client\",\"version\":\"1\"}"), "initialize"));
					assertEquals(200, initialized.getStatusCode(), text(initialized));
					String sessionId = initialized.getHeaders().entrySet().stream()
							.filter(entry -> entry.getKey().equalsIgnoreCase("Mcp-Session-Id"))
							.map(entry -> entry.getValue().get(0)).findFirst().orElse(null);
					String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
							+ "\"name\":\"metadata\",\"arguments\":{\"a\":\"" + payload + "\",\"b\":\"" + payload + "\"}}}";
					assertTrue(body.length() > 1_048_576);
					McpSimulationResponse response = exchange(simulator, request(version, sessionId, body, "tools/call"));
					assertEquals(200, response.getStatusCode(), text(response));
					assertTrue(text(response).contains("\"text\":\"called\""), text(response));
					assertEquals(1, handlers.size());
				});
			}
	}

	@Test
	void missingOrNonstringClientInformationRemainsAProtocolError() throws Exception {
		for (McpProtocolVersion version : List.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28))
			SokletSimulator.run(configuration(false, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>()), simulator -> {
				for (String clientInfo : List.of("{\"version\":\"1\"}", "{\"name\":\"client\"}",
						"{\"name\":1,\"version\":\"1\"}", "{\"name\":\"client\",\"version\":null}")) {
					boolean modern = version == McpProtocolVersion.V2026_07_28;
					String body = modern ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
							+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
							+ "\"io.modelcontextprotocol/clientCapabilities\":{},\"io.modelcontextprotocol/clientInfo\":"
							+ clientInfo + "},\"name\":\"metadata\",\"arguments\":{}}}" : initializeBody(version, clientInfo);
					McpSimulationResponse response = exchange(simulator, request(version, null, body, modern ? "tools/call" : "initialize"));
					assertEquals(400, response.getStatusCode(), text(response));
					assertTrue(text(response).contains("\"code\":-32602"), text(response));
				}
			});
	}

	private static void assertBlankClient(McpImplementation implementation) {
		assertEquals("", implementation.getName());
		assertEquals(" ", implementation.getVersion());
		assertEquals("Client title", implementation.getTitle().orElseThrow());
	}

	private static SimulatorConfig configuration(boolean sessions, List<McpAdmissionContext> admissions,
			List<McpRequestContext> handlers) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("metadata", ALL).jsonObjectArguments()
				.handler((requestContext, arguments, invocationFeatures) -> {
					handlers.add(requestContext);
					return McpCompleteResult.fromToolText("called");
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("server", "1").build(), ALL)
				.toolRegistrations(List.of(tool)).sessionProtocolVersions(sessions
						? Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25) : Set.of()).build();
		return SimulatorConfig.builder().configureMcpServer(builder -> {
			builder.port(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.requestRateLimiter(context -> McpRateLimitDecision.allowed())
					.toolRateLimiter(context -> McpRateLimitDecision.allowed())
					.admissionController(requestContext -> {
						admissions.add(requestContext);
						return McpAdmissionDecision.accepted(McpAdmissionIdentity.withRateLimitPartitionKey("owner").authorizationPartitionKey("owner").principal("owner").build());
					});
			if (sessions) builder.sessionConfig(McpSessionConfig.withOwnerKeyResolver(identity -> "owner").build());
		}).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build();
	}

	private static String initializeBody(McpProtocolVersion version, String clientInfo) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
				+ version.getWireValue() + "\",\"capabilities\":{},\"clientInfo\":" + clientInfo + "}}";
	}

	private static Request request(McpProtocolVersion version, String sessionId, String body, String method) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Host", List.of("127.0.0.1:0"));
		headers.put("Accept", List.of("application/json, text/event-stream"));
		headers.put("Content-Type", List.of("application/json"));
		headers.put("MCP-Protocol-Version", List.of(version.getWireValue()));
		if (version == McpProtocolVersion.V2026_07_28) {
			headers.put("Mcp-Method", List.of(method));
			headers.put("Mcp-Name", List.of("metadata"));
		}
		if (sessionId != null) headers.put("Mcp-Session-Id", List.of(sessionId));
		return Request.withPath(HttpMethod.POST, "/mcp").headers(headers).body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static McpSimulationResponse exchange(Simulator simulator, Request request) throws InterruptedException {
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(WAIT).orElseThrow();
			assertEquals(McpStreamTerminationReason.COMPLETED, simulation.awaitCompletion(WAIT).orElseThrow().getReason());
			return response;
		}
	}

	private static String text(McpSimulationResponse response) {
		return new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
	}
}
