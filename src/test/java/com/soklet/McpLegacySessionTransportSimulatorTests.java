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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Simulator parity for actual HTTP GET/DELETE without fabricated RPC messages. */
@Timeout(60)
class McpLegacySessionTransportSimulatorTests {
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final List<McpProtocolVersion> LEGACY = List.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> ALL = Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);

	@Test
	@Timeout(120)
	void publishedBeforeAckSessionGetThenDeleteCompletesZeroMessageSseWithExactReason() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			List<McpSessionTransportAdmissionContext> contexts = new CopyOnWriteArrayList<>();
			SokletSimulator.run(configuration(contexts), simulator -> {
				String id = initialize(simulator, version);
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, ""))) {
					McpSimulationResponse response = get.awaitResponse(WAIT).orElseThrow();
					assertEquals(200, response.getStatusCode());
					assertEquals(McpSimulationBodyType.SSE, response.getBodyType());
					assertTrue(response.getBody().isEmpty());
					assertTrue(get.awaitCompletion(Duration.ZERO).isEmpty());
					assertEquals(HttpMethod.GET, contexts.get(0).getRequest().getHttpMethod());
					assertEquals(Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED), contexts.get(0).getNotificationTypes());
					try (McpSimulation delete = simulator.startMcpRequest(request(HttpMethod.DELETE, version, id, ""))) {
						McpSimulationResponse deletion = delete.awaitResponse(WAIT).orElseThrow();
						assertEquals(204, deletion.getStatusCode());
						assertEquals(McpSimulationBodyType.EMPTY, deletion.getBodyType());
						assertArrayEquals(new byte[0], deletion.getBody().orElseThrow());
						assertEquals(McpStreamTerminationReason.COMPLETED, delete.awaitCompletion(WAIT).orElseThrow().getReason());
					}
					McpSimulationCompletion completion = get.awaitCompletion(WAIT).orElseThrow();
					assertEquals(McpStreamTerminationReason.SESSION_CLOSED, completion.getReason());
					assertTrue(completion.getTerminalMessage().isEmpty());
					assertTrue(completion.getThrowables().isEmpty());
					for (McpSimulationStreamItem item : drain(get)) {
						assertEquals(McpSimulationStreamItemType.KEEP_ALIVE_COMMENT, item.getType());
						assertTrue(item.getMessage().isEmpty());
					}
					McpSessionTransportAdmissionContext deletion = contexts.stream()
							.filter(context -> context.getRequest().getHttpMethod() == HttpMethod.DELETE).findFirst().orElseThrow();
					assertTrue(deletion.getNotificationTypes().isEmpty());
					assertFalse(deletion.isReauthorization());
				}
				assertEquals(404, ping(simulator, version, id));
			});
		}
	}

	@Test
	@Timeout(120)
	void simulatorDisconnectEndsOnlyTheGetAndModernSelectionCannotMutateTheSession() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			List<McpSessionTransportAdmissionContext> contexts = new CopyOnWriteArrayList<>();
			SokletSimulator.run(configuration(contexts), simulator -> {
				String id = initialize(simulator, version);
				try (McpSimulation modern = simulator.startMcpRequest(request(HttpMethod.DELETE, McpProtocolVersion.V2026_07_28, id, ""))) {
					assertEquals(405, modern.awaitResponse(WAIT).orElseThrow().getStatusCode());
					assertTrue(modern.awaitCompletion(WAIT).orElseThrow().getTerminalMessage().isEmpty());
				}
				assertTrue(contexts.isEmpty());
				try (McpSimulation get = simulator.startMcpRequest(request(HttpMethod.GET, version, id, ""))) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					get.close();
					McpSimulationCompletion completion = get.awaitCompletion(WAIT).orElseThrow();
					assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED, completion.getReason());
					assertTrue(completion.getTerminalMessage().isEmpty());
					for (McpSimulationStreamItem item : drain(get)) assertTrue(item.getMessage().isEmpty());
				}
				assertEquals(200, ping(simulator, version, id));
			});
		}
	}

	private static List<McpSimulationStreamItem> drain(McpSimulation simulation) throws InterruptedException {
		List<McpSimulationStreamItem> items = new ArrayList<>();
		for (int count = 0; count < 16; count++) {
			var item = simulation.awaitStreamItem(Duration.ZERO);
			if (item.isEmpty()) return List.copyOf(items);
			items.add(item.orElseThrow());
		}
		throw new AssertionError("Unexpected simulator stream-item volume.");
	}

	private static String initialize(Simulator simulator, McpProtocolVersion version) throws InterruptedException {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
				+ version.getWireValue() + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"simulator\",\"version\":\"1\"}}}";
		try (McpSimulation initialization = simulator.startMcpRequest(request(HttpMethod.POST, version, null, body))) {
			McpSimulationResponse response = initialization.awaitResponse(WAIT).orElseThrow();
			assertEquals(200, response.getStatusCode());
			assertEquals(McpStreamTerminationReason.COMPLETED, initialization.awaitCompletion(WAIT).orElseThrow().getReason());
			return response.getHeaders().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("Mcp-Session-Id"))
					.findFirst().orElseThrow().getValue().iterator().next();
		}
	}

	private static int ping(Simulator simulator, McpProtocolVersion version, String id) throws InterruptedException {
		try (McpSimulation ping = simulator.startMcpRequest(request(HttpMethod.POST, version, id,
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}"))) {
			int status = ping.awaitResponse(WAIT).orElseThrow().getStatusCode();
			ping.awaitCompletion(WAIT).orElseThrow();
			return status;
		}
	}

	private static Request request(HttpMethod method, McpProtocolVersion version, String id, String body) {
		Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
		headers.put("Host", List.of("127.0.0.1:0"));
		headers.put("Accept", List.of("application/json, text/event-stream"));
		headers.put("Content-Type", List.of("application/json"));
		headers.put("MCP-Protocol-Version", List.of(version.getWireValue()));
		if (id != null) headers.put("Mcp-Session-Id", List.of(id));
		return Request.withPath(method, "/mcp").headers(headers).body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static SimulatorConfig configuration(List<McpSessionTransportAdmissionContext> contexts) {
		McpAdmissionIdentity identity = McpAdmissionIdentity.withRateLimitPartitionKey("owner")
				.authorizationPartitionKey("owner").principal("owner").build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("simulation", "1").build(), ALL)
				.sessionProtocolVersions(Set.copyOf(LEGACY)).subscriptionProtocolVersions(Set.copyOf(LEGACY))
				.subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(McpSubscriptionEventPublisher.fromInMemoryDefaults(),
						Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED)).build()).build();
		return SimulatorConfig.builder().configureMcpServer(builder -> builder.port(0).host("127.0.0.1")
				.allowedHosts(Set.of("127.0.0.1")).corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> McpAdmissionDecision.accepted(identity))
				.sessionConfig(McpSessionConfig.withOwnerKeyResolver(admitted -> "owner")
						.transportAdmissionController((context, features) -> {
							contexts.add(context);
							assertTrue(features.getProgressReporter().isEmpty());
							return McpSessionTransportAdmissionDecision.accepted(identity, Instant.now().plusSeconds(30), context.getNotificationTypes());
						}).build())
				.maximumSubscriptionDuration(Duration.ofSeconds(3)).keepAliveInterval(Duration.ofSeconds(1)))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(WAIT).startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
	}
}
