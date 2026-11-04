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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(360)
class McpHttpNegotiationSyntaxTests {
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final List<McpProtocolVersion> VERSIONS = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
	private static final List<String> ACCEPT_BOTH = List.of("application/json, text/event-stream");

	@Test
	void quotedCharsetPairsAndOpaqueParameterTextAreAcceptedForEveryRevision() throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		SokletSimulator.run(configuration(false, admissions), simulator -> {
			for (McpProtocolVersion version : VERSIONS)
				for (String contentType : List.of("application/json; charset=\"UTF-8\"",
						"application/json; charset=\"utf\\-8\"", "application/json; charset=\"\\u\\t\\f-8\"",
						"application/json; note=\"a\\\"b\\\\c\\;d\"; charset=\"utf\\-8\"",
						"application/json; note=\"opaque\ttext\"; charset=UTF-8")) {
					McpSimulationResponse response = exchange(simulator,
							request(version, List.of(contentType), ACCEPT_BOTH));
					assertEquals(200, response.getStatusCode(), version + " " + contentType);
					assertTrue(text(response).contains("\"result\":"), text(response));
				}
			assertEquals(VERSIONS.size() * 5, admissions.get());
		});
	}

	@Test
	void emptyMediaTypeAndMediaRangeParameterElementsAreIgnored() throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		SokletSimulator.run(configuration(false, admissions), simulator -> {
			for (McpProtocolVersion version : VERSIONS)
				for (String contentType : List.of("application/json;", "application/json;; charset=\"utf\\-8\"; ;")) {
					assertEquals(200, exchange(simulator, request(version, List.of(contentType),
							List.of("application/json;;q=1;, text/event-stream; ;q=0.5;"))).getStatusCode());
				}
			assertEquals(VERSIONS.size() * 2, admissions.get());
		});
	}

	@Test
	void emptyAcceptMembersAcrossPhysicalFieldsDoNotEraseAdvertisedSupport() throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		SokletSimulator.run(configuration(false, admissions), simulator -> {
			for (McpProtocolVersion version : VERSIONS)
				for (List<String> accept : List.of(List.of(", application/json,, text/event-stream,"),
						List.of("", "application/json", " \t", "text/event-stream", ""),
						List.of("\t,application/*, ,text/*,\t"))) {
					assertEquals(200, exchange(simulator, request(version, List.of("application/json"), accept)).getStatusCode(),
							version + " " + accept);
				}
			assertEquals(VERSIONS.size() * 3, admissions.get());
		});
	}

	@Test
	void malformedOrDuplicateContentTypeParametersRemainRejectedBeforeAdmission() throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		SokletSimulator.run(configuration(false, admissions), simulator -> {
			for (McpProtocolVersion version : VERSIONS) {
				for (String contentType : List.of("text/plain", "application/json; charset=ISO-8859-1",
						"application/json; profile=\"unterminated", "application/json; profile=\"trailing\\\"",
						"application/json; profile=\"ok\"junk", "application/json; profile",
						"application/json; profile=", "application/json; profile=a; PROFILE=b")) {
					assertEquals(415, exchange(simulator, request(version, List.of(contentType), ACCEPT_BOTH)).getStatusCode(),
							version + " " + contentType);
				}
				assertEquals(400, exchange(simulator, request(version,
						List.of("application/json", "application/json"), ACCEPT_BOTH)).getStatusCode());
			}
			assertEquals(0, admissions.get());
		});
	}

	@Test
	void emptyMembersNeverOverrideExplicitRejectionOrMakeMalformedRangesValid() throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		SokletSimulator.run(configuration(false, admissions), simulator -> {
			for (McpProtocolVersion version : VERSIONS)
				for (List<String> accept : List.of(List.<String>of(), List.of(",,,"),
						List.of("application/json,,"), List.of(",text/event-stream,"),
						List.of("*/*;q=1, application/json;q=0,"),
						List.of("application/json, text/event-stream;;q=0;,"),
						List.of("application/json, garbage, text/event-stream,"),
						List.of("application/json;profile=\"unterminated, text/event-stream"),
						List.of("application/json;q=1;q=0, text/event-stream"),
						List.of("application/json, text/event-stream;q=.5"),
						List.of("application/json;profile, text/event-stream"))) {
					assertEquals(406, exchange(simulator, request(version, List.of("application/json"), accept)).getStatusCode(),
							version + " " + accept);
				}
			assertEquals(0, admissions.get());
		});
	}

	@Test
	void legacySessionGetUsesTheSameEmptyAcceptMemberRules() throws Exception {
		SokletSimulator.run(configuration(true, new AtomicInteger()), simulator -> {
			for (McpProtocolVersion version : VERSIONS.subList(0, 2)) {
				String sessionId = initialize(simulator, version);
				for (List<String> accept : List.of(List.of(",text/event-stream,,"), List.of("", "text/event-stream; ;q=1;", ""))) {
					try (McpSimulation get = simulator.startMcpRequest(sessionRequest(HttpMethod.GET, version, sessionId, accept))) {
						McpSimulationResponse response = get.awaitResponse(WAIT).orElseThrow();
						assertEquals(200, response.getStatusCode());
						assertEquals(McpSimulationBodyType.SSE, response.getBodyType());
						get.close();
						assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
								get.awaitCompletion(WAIT).orElseThrow().getReason());
					}
				}
				for (List<String> accept : List.of(List.of(",,"), List.of("application/json,"),
						List.of("*/*;q=1,text/event-stream;q=0,"), List.of("text/event-stream;q=\"1\","))) {
					assertEquals(406, exchange(simulator, sessionRequest(HttpMethod.GET, version, sessionId, accept)).getStatusCode());
				}
				assertEquals(204, exchange(simulator, sessionRequest(HttpMethod.DELETE, version, sessionId, List.of())).getStatusCode());
			}
		});
	}

	private static SimulatorConfig configuration(boolean sessions, AtomicInteger admissions) {
		Set<McpProtocolVersion> versions = Set.copyOf(VERSIONS);
		Set<McpProtocolVersion> legacy = sessions ? Set.copyOf(VERSIONS.subList(0, 2)) : Set.of();
		McpEndpoint.Builder endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("negotiation-syntax", "1").build(), versions).sessionProtocolVersions(legacy);
		if (sessions)
			endpoint.subscriptionProtocolVersions(legacy).subscriptionConfig(McpSubscriptionConfig
					.withEventPublisherAndNotificationTypes(McpSubscriptionEventPublisher.fromInMemoryDefaults(),
							Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED)).build());
		McpAdmissionIdentity identity = McpAdmissionIdentity.withRateLimitPartitionKey("owner")
				.authorizationPartitionKey("owner").principal("owner").build();
		return SimulatorConfig.builder().configureMcpServer(builder -> {
			builder.port(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint.build())))
					.admissionController(context -> { admissions.incrementAndGet(); return McpAdmissionDecision.accepted(identity); });
			if (sessions)
				builder.sessionConfig(McpSessionConfig.withOwnerKeyResolver(admitted -> "owner")
						.transportAdmissionController((context, features) -> McpSessionTransportAdmissionDecision.accepted(
								identity, Instant.now().plusSeconds(30), context.getNotificationTypes())).build());
		}).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(WAIT).startupCancelationTimeout(WAIT)
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
	}

	private static Request request(McpProtocolVersion version, List<String> contentTypes, List<String> accept) {
		boolean modern = version == McpProtocolVersion.V2026_07_28;
		String method = modern ? "server/discover" : "ping";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
				+ (modern ? ",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}" : "") + "}";
		Map<String, List<String>> headers = baseHeaders(version, accept);
		headers.put("Content-Type", contentTypes);
		if (modern) headers.put("Mcp-Method", List.of(method));
		return Request.withPath(HttpMethod.POST, "/mcp").headers(headers).body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static String initialize(Simulator simulator, McpProtocolVersion version) throws InterruptedException {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
				+ version.getWireValue() + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"negotiation-syntax\",\"version\":\"1\"}}}";
		Map<String, List<String>> headers = baseHeaders(version, ACCEPT_BOTH);
		headers.put("Content-Type", List.of("application/json"));
		McpSimulationResponse response = exchange(simulator, Request.withPath(HttpMethod.POST, "/mcp")
				.headers(headers).body(body.getBytes(StandardCharsets.UTF_8)).build());
		assertEquals(200, response.getStatusCode());
		return response.getHeaders().get("Mcp-Session-Id").getFirst();
	}

	private static Request sessionRequest(HttpMethod method, McpProtocolVersion version, String sessionId, List<String> accept) {
		Map<String, List<String>> headers = baseHeaders(version, accept);
		headers.put("Mcp-Session-Id", List.of(sessionId));
		return Request.withPath(method, "/mcp").headers(headers).build();
	}

	private static Map<String, List<String>> baseHeaders(McpProtocolVersion version, List<String> accept) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Host", List.of("127.0.0.1:0"));
		headers.put("MCP-Protocol-Version", List.of(version.getWireValue()));
		headers.put("Accept", accept);
		return headers;
	}

	private static McpSimulationResponse exchange(Simulator simulator, Request request) throws InterruptedException {
		try (McpSimulation exchange = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = exchange.awaitResponse(WAIT).orElseThrow();
			assertEquals(McpStreamTerminationReason.COMPLETED, exchange.awaitCompletion(WAIT).orElseThrow().getReason());
			return response;
		}
	}

	private static String text(McpSimulationResponse response) {
		return new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
	}
}
