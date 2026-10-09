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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpLegacyNegotiationAndNotificationTests {
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final List<McpProtocolVersion> LEGACY = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> ALL = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);

	@Test
	@Timeout(300)
	void missingVersionCanUseOnlyAnUnambiguousLegacyEndpointWithoutDowngradingModernFraming() throws Exception {
		for (McpProtocolVersion version : LEGACY)
			for (boolean sessions : List.of(false, true))
				SokletSimulator.run(configuration(Set.of(version), sessions, new CopyOnWriteArrayList<>()), simulator -> {
					McpSimulationResponse initialized = exchange(simulator, request(HttpMethod.POST,
							null, null, initializeBody(version.getWireValue()), "alice", ""));
					assertEquals(200, initialized.getStatusCode());
					String sessionId = initialized.getHeaders().getOrDefault("Mcp-Session-Id", List.of()).stream().findFirst().orElse(null);
					assertEquals(200, exchange(simulator, request(HttpMethod.POST, null, sessionId,
							"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", "alice", "")).getStatusCode());
					assertEquals(202, exchange(simulator, request(HttpMethod.POST, null, sessionId,
							"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\"}", "alice", "")).getStatusCode());
					assertEquals(400, exchange(simulator, request(HttpMethod.POST, null, sessionId,
							"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}", "alice", "")).getStatusCode());
					if (sessions) {
						assertEquals(204, exchange(simulator, request(HttpMethod.DELETE, null, sessionId, "", "alice", "")).getStatusCode());
						assertEquals(404, exchange(simulator, request(HttpMethod.POST, null, sessionId,
								"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\"}", "alice", "")).getStatusCode());
					}
				});
		for (Set<McpProtocolVersion> versions : List.of(ALL, Set.of(McpProtocolVersion.V2026_07_28)))
			SokletSimulator.run(configuration(versions, false, new CopyOnWriteArrayList<>()), simulator -> {
				assertEquals(400, exchange(simulator, request(HttpMethod.POST, null, null,
						"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", "alice", "")).getStatusCode());
			});
	}

	@Test
	@Timeout(180)
	void initializationCanCounterofferAStaticHeaderWithoutOverridingASupportedBodyVersion() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			McpProtocolVersion other = version == LEGACY.get(0) ? LEGACY.get(1) : LEGACY.get(0);
			for (boolean sessions : List.of(false, true)) {
				List<McpAdmissionContext> contexts = new CopyOnWriteArrayList<>();
				SokletSimulator.run(configuration(Set.of(version), sessions, contexts), simulator -> {
					McpSimulationResponse response = exchange(simulator, request(HttpMethod.POST,
							version.getWireValue(), null, initializeBody(other.getWireValue()), "alice", ""));
					assertEquals(200, response.getStatusCode(), text(response));
					assertTrue(text(response).contains("\"protocolVersion\":\"" + version.getWireValue() + "\""), text(response));
					assertEquals(version, contexts.get(0).getProtocolVersion());
					String sessionId = response.getHeaders().entrySet().stream()
							.filter(entry -> entry.getKey().equalsIgnoreCase("Mcp-Session-Id"))
							.map(entry -> entry.getValue().get(0)).findFirst().orElse(null);
					assertEquals(sessions, sessionId != null);
					assertEquals(200, exchange(simulator, request(HttpMethod.POST, version.getWireValue(), sessionId,
							"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", "alice", "")).getStatusCode());
				});
			}
		}
		SokletSimulator.run(configuration(ALL, false, new CopyOnWriteArrayList<>()), simulator -> {
			McpSimulationResponse response = exchange(simulator, request(HttpMethod.POST,
					"2025-06-18", null, initializeBody("2025-11-25"), "alice", ""));
			assertEquals(200, response.getStatusCode(), text(response));
			assertTrue(text(response).contains("\"protocolVersion\":\"2025-11-25\""), text(response));
			for (String invalid : List.of("2025-03-26", "unsupported", ""))
				assertEquals(400, exchange(simulator, request(HttpMethod.POST, invalid, null,
						initializeBody("2025-11-25"), "alice", "")).getStatusCode());
		});
	}

	@Test
	@Timeout(450)
	void unsupportedLegacyNotificationsAreAcceptedOnlyAfterAdmissionAndSessionValidation() throws Exception {
		for (McpProtocolVersion version : LEGACY)
			for (boolean sessions : List.of(false, true)) {
				List<McpAdmissionContext> contexts = new CopyOnWriteArrayList<>();
				SokletSimulator.run(configuration(ALL, sessions, contexts), simulator -> {
					McpSimulationResponse initialized = exchange(simulator, request(HttpMethod.POST,
							version.getWireValue(), null, initializeBody(version.getWireValue()), "alice", ""));
					assertEquals(200, initialized.getStatusCode());
					String sessionId = initialized.getHeaders().entrySet().stream()
							.filter(entry -> entry.getKey().equalsIgnoreCase("Mcp-Session-Id"))
							.map(entry -> entry.getValue().get(0)).findFirst().orElse(null);
					for (String method : List.of("notifications/roots/list_changed", "notifications/example")) {
						String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{\"com.example/trace\":\"ok\"}}}";
						McpSimulationResponse accepted = exchange(simulator, request(HttpMethod.POST,
								version.getWireValue(), sessionId, body, "alice", ""));
						assertEquals(202, accepted.getStatusCode(), text(accepted));
						assertEquals(McpSimulationBodyType.EMPTY, accepted.getBodyType());
						assertArrayEquals(new byte[0], accepted.getBody().orElseThrow());
						assertEquals(403, exchange(simulator, request(HttpMethod.POST,
								version.getWireValue(), sessionId, body, "alice", "deny")).getStatusCode());
						assertEquals(429, exchange(simulator, request(HttpMethod.POST,
								version.getWireValue(), sessionId, body, "alice", "limit")).getStatusCode());
						if (sessions)
							assertEquals(404, exchange(simulator, request(HttpMethod.POST,
									version.getWireValue(), sessionId, body, "bob", "")).getStatusCode());
					}
					String invalid = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":[]}";
					assertEquals(400, exchange(simulator, request(HttpMethod.POST,
							version.getWireValue(), sessionId, invalid, "alice", "")).getStatusCode());
					if (sessions) {
						assertEquals(204, exchange(simulator, request(HttpMethod.DELETE,
								version.getWireValue(), sessionId, "", "alice", "")).getStatusCode());
						assertEquals(404, exchange(simulator, request(HttpMethod.POST,
								version.getWireValue(), sessionId,
								"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\"}", "alice", "")).getStatusCode());
					}
				});
			}
	}

	private static SimulatorConfig configuration(Set<McpProtocolVersion> versions, boolean sessions,
			List<McpAdmissionContext> contexts) {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("negotiation", "1").build(), versions)
				.sessionProtocolVersions(sessions ? versions.stream().filter(LEGACY::contains)
						.collect(java.util.stream.Collectors.toSet()) : Set.of()).build();
		return SimulatorConfig.builder().configureMcpServer(builder -> {
			builder.port(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.admissionController(requestContext -> {
						contexts.add(requestContext);
						if (requestContext.getRequest().getHeader("X-Policy").filter("deny"::equals).isPresent())
							return McpAdmissionDecision.rejected(McpAdmissionRejection.withStatusCodeAndError(403,
									McpJsonRpcError.fromApplication(-31903, "Denied")).build());
						String owner = requestContext.getRequest().getHeader("X-Owner").orElse("alice");
						return McpAdmissionDecision.accepted(McpAdmissionIdentity.withRateLimitPartitionKey(owner)
								.authorizationPartitionKey(owner).principal(owner).build());
					}).requestRateLimiter(requestContext -> requestContext.getRequest().getHeader("X-Policy")
							.filter("limit"::equals).isPresent()
							? McpRateLimitDecision.denied(Duration.ofSeconds(1)) : McpRateLimitDecision.allowed());
			if (sessions)
				builder.sessionConfig(McpSessionConfig.withOwnerKeyResolver(identity -> (String) identity.getPrincipal().orElseThrow())
						.transportAdmissionController((requestContext, invocationFeatures) ->
								McpSessionTransportAdmissionDecision.accepted(
										McpAdmissionIdentity.withRateLimitPartitionKey("alice").authorizationPartitionKey("alice")
												.principal("alice").build(), java.time.Instant.now().plusSeconds(30), Set.of())).build());
		}).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(WAIT).startupCancelationTimeout(WAIT)
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
	}

	private static McpSimulationResponse exchange(Simulator simulator, Request request) throws InterruptedException {
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(WAIT).orElseThrow();
			assertEquals(McpStreamTerminationReason.COMPLETED, simulation.awaitCompletion(WAIT).orElseThrow().getReason());
			return response;
		}
	}

	private static String text(McpSimulationResponse response) {
		return response.getBody().map(body -> new String(body, StandardCharsets.UTF_8)).orElse("");
	}

	private static String initializeBody(String revision) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
				+ revision + "\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
	}

	private static Request request(HttpMethod method, String revision, String sessionId, String body, String owner, String policy) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Host", List.of("127.0.0.1:0"));
		headers.put("Accept", List.of("application/json, text/event-stream"));
		headers.put("Content-Type", List.of("application/json"));
		if (revision != null) headers.put("MCP-Protocol-Version", List.of(revision));
		headers.put("X-Owner", List.of(owner));
		headers.put("X-Policy", List.of(policy));
		if (sessionId != null) headers.put("Mcp-Session-Id", List.of(sessionId));
		return Request.withPath(method, "/mcp").headers(headers).body(body.getBytes(StandardCharsets.UTF_8)).build();
	}
}
