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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@NotThreadSafe
@Timeout(30)
public class McpHttpServerConnectionContractTests {
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String DISCOVER = "server/discover";
	private static final String CALL_TOOL = "tools/call";
	private static final String TOOL = "connection_tool";

	@Test
	public void persistent_connection_never_carries_protocol_metadata_between_posts()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"connection-test", "3.6.0-SNAPSHOT"))
				.build();
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				McpHttpEndpointPolicy.forDiscovery(
						CorsAuthorizer.fromWhitelistedOrigins(
								Set.of("https://allowed.example")),
						ignored -> {
							admissions.incrementAndGet();
							return McpAdmissionDecision.acceptedAnonymous();
						}),
				endpoint);

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(
					port, discoverRequest("first", PROTOCOL_VERSION, true),
					headers(PROTOCOL_VERSION, true,
							new McpChunkedHttpClient.RequestHeader(
									"Origin", "https://allowed.example")))) {
				assertResponse(client, 200, "\"id\":\"first\"");

				client.writeMcpMessage(
						discoverRequest("bad-origin", PROTOCOL_VERSION, true),
						headers(PROTOCOL_VERSION, true,
								new McpChunkedHttpClient.RequestHeader(
										"Origin", "https://rejected.example")));
				assertResponse(client, 403);

				client.writeMcpMessage(
						discoverRequest("missing-header", PROTOCOL_VERSION, true),
						headers(PROTOCOL_VERSION, false));
				assertResponse(client, 400,
						"\"id\":\"missing-header\"", "\"code\":-32020");

				client.writeMcpMessage(
						discoverRequest("unsupported", "2099-01-01", true),
						headers("2099-01-01", true));
				assertResponse(client, 400,
						"\"id\":\"unsupported\"", "\"code\":-32022",
						"\"requested\":\"2099-01-01\"");

				client.writeMcpMessage(
						discoverRequest("missing-meta", PROTOCOL_VERSION, false),
						headers(PROTOCOL_VERSION, true));
				assertResponse(client, 400,
						"\"id\":\"missing-meta\"", "\"code\":-32602");

				client.writeMcpMessage(
						discoverRequest("last", PROTOCOL_VERSION, true),
						headers(PROTOCOL_VERSION, true));
				assertResponse(client, 200, "\"id\":\"last\"");
				Assertions.assertEquals(2, admissions.get());
			}
		} finally {
			runtime.close();
		}
	}

	@Test
	public void persistent_connection_isolates_tool_headers_and_releases_request_ids()
			throws Exception {
		AtomicInteger handlers = new AtomicInteger();
		List<Optional<String>> authorizations = new CopyOnWriteArrayList<>();
		List<String> hosts = new CopyOnWriteArrayList<>();
		McpNormalizedOperation tool = new McpNormalizedOperation(TOOL,
				McpInputRequestPlan.empty(), new McpMirroredHeaderPlan(List.of(
						new McpMirroredHeaderDeclaration("Tenant", List.of("tenant"),
								McpMirroredHeaderValueType.STRING))));
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"connection-tool-test", "3.6.0-SNAPSHOT"))
				.tool(tool)
				.build();
		McpProtocolAdmissionController protocolAdmissionController = context -> {
			authorizations.add(context.request().getHeader("Authorization"));
			hosts.add(context.request().getHeader("Host").orElseThrow());
			return McpAdmissionDecision.acceptedAnonymous();
		};
		McpHttpEndpointPolicy endpointPolicy = new McpHttpEndpointPolicy(
				"/mcp", Set.of("localhost"), McpAbsentOriginPolicy.ALLOW,
				CorsAuthorizer.rejectAllInstance(), protocolAdmissionController);
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of(CALL_TOOL, ignored -> {
					handlers.incrementAndGet();
					return McpWireResult.complete(new McpJsonObject(
							Map.of("ok", McpJsonBoolean.TRUE)));
				}));
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), endpointPolicy,
				endpoint, router,
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM);

		try {
			int port = runtime.start().getPort();
			List<McpChunkedHttpClient.RequestHeader> firstHeaders = toolHeaders(
					"alpha", new McpChunkedHttpClient.RequestHeader(
							"Authorization", "Bearer first"),
					new McpChunkedHttpClient.RequestHeader(
							"MCP-Session-Id", "client-session-must-be-ignored"),
					new McpChunkedHttpClient.RequestHeader(
							"Last-Event-ID", "client-event-must-be-ignored"));
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(
					port, toolRequest("reused", "alpha"), firstHeaders)) {
				assertResponse(client, 200, "\"id\":\"reused\"");

				client.writeMcpMessage(toolRequest("reused", "beta"),
						"localhost:" + port, toolHeaders("beta",
								new McpChunkedHttpClient.RequestHeader(
										"Authorization", "Bearer second")));
				assertResponse(client, 200, "\"id\":\"reused\"");

				client.writeMcpMessage(toolRequest("missing-custom", "gamma"),
						withoutHeader(toolHeaders("gamma"), "Mcp-Param-Tenant"));
				assertResponse(client, 400, "\"id\":\"missing-custom\"",
						"\"code\":-32020");

				client.writeMcpMessage(toolRequest("missing-name", "gamma"),
						withoutHeader(toolHeaders("gamma"), "Mcp-Name"));
				assertResponse(client, 400, "\"id\":\"missing-name\"",
						"\"code\":-32020");

				client.writeMcpMessage(toolRequest("wrong-name", "gamma"),
						replaceHeader(toolHeaders("gamma"), "Mcp-Name", "other_tool"));
				assertResponse(client, 400, "\"id\":\"wrong-name\"",
						"\"code\":-32020");

				client.writeMcpMessage(toolRequest("wrong-custom", "gamma"),
						replaceHeader(toolHeaders("gamma"), "Mcp-Param-Tenant",
								"other-tenant"));
				assertResponse(client, 400, "\"id\":\"wrong-custom\"",
						"\"code\":-32020");

				client.writeRequest("GET", "/mcp", "localhost:" + port, "",
						List.of(
								new McpChunkedHttpClient.RequestHeader(
										"MCP-Session-Id", "legacy-get-session"),
								new McpChunkedHttpClient.RequestHeader(
										"Last-Event-ID", "legacy-get-event")));
				assertResponse(client, 405);

				client.writeMcpMessage(toolRequest("final", "delta"),
						toolHeaders("delta"));
				assertResponse(client, 200, "\"id\":\"final\"");

				client.writeRequest("DELETE", "/mcp", "localhost:" + port, "",
						List.of(
								new McpChunkedHttpClient.RequestHeader(
										"MCP-Session-Id", "legacy-delete-session"),
								new McpChunkedHttpClient.RequestHeader(
										"Last-Event-ID", "legacy-delete-event")));
				assertResponse(client, 405);

				client.writeMcpMessage(toolRequest("after-delete", "epsilon"),
						toolHeaders("epsilon"));
				assertResponse(client, 200, "\"id\":\"after-delete\"");
			}

			Assertions.assertEquals(4, handlers.get());
			Assertions.assertEquals(List.of(
					Optional.of("Bearer first"), Optional.of("Bearer second"),
					Optional.empty(), Optional.empty()), authorizations);
			Assertions.assertEquals(List.of(
					"127.0.0.1:" + port, "localhost:" + port,
					"127.0.0.1:" + port, "127.0.0.1:" + port), hosts);
			Assertions.assertEquals(0,
					runtime.requestExecutionSnapshot()
							.activeIdentifiedRequestExchanges());
		} finally {
			runtime.close();
		}
	}

	private static void assertResponse(McpChunkedHttpClient client,
			int expectedStatus, String... expectedFragments) throws Exception {
		McpChunkedHttpClient.HttpResponseHead head = client.readHead();
		Assertions.assertEquals(expectedStatus, head.status(), head.raw());
		Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
		Assertions.assertFalse(head.hasHeader("MCP-Session-Id"), head.raw());
		Assertions.assertFalse(head.hasHeader("Last-Event-ID"), head.raw());
		String body = client.readFixedBody(head);
		for (String expectedFragment : expectedFragments)
			Assertions.assertTrue(body.contains(expectedFragment), body);
	}

	private static List<McpChunkedHttpClient.RequestHeader> headers(
			String protocolVersion, boolean includeProtocolVersion,
			McpChunkedHttpClient.RequestHeader... additional) {
		McpChunkedHttpClient.RequestHeader method =
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", DISCOVER);
		List<McpChunkedHttpClient.RequestHeader> headers = new java.util.ArrayList<>();
		if (includeProtocolVersion)
			headers.add(new McpChunkedHttpClient.RequestHeader(
					"MCP-Protocol-Version", protocolVersion));
		headers.add(method);
		headers.addAll(List.of(additional));
		return List.copyOf(headers);
	}

	private static List<McpChunkedHttpClient.RequestHeader> toolHeaders(String tenant,
			McpChunkedHttpClient.RequestHeader... additional) {
		List<McpChunkedHttpClient.RequestHeader> headers = new ArrayList<>();
		headers.add(new McpChunkedHttpClient.RequestHeader(
				"MCP-Protocol-Version", PROTOCOL_VERSION));
		headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Method", CALL_TOOL));
		headers.add(new McpChunkedHttpClient.RequestHeader("Mcp-Name", TOOL));
		headers.add(new McpChunkedHttpClient.RequestHeader(
				"Mcp-Param-Tenant", tenant));
		headers.addAll(List.of(additional));
		return List.copyOf(headers);
	}

	private static List<McpChunkedHttpClient.RequestHeader> withoutHeader(
			List<McpChunkedHttpClient.RequestHeader> headers, String name) {
		List<McpChunkedHttpClient.RequestHeader> copy = new ArrayList<>(headers);
		copy.removeIf(header -> header.name().equalsIgnoreCase(name));
		return List.copyOf(copy);
	}

	private static List<McpChunkedHttpClient.RequestHeader> replaceHeader(
			List<McpChunkedHttpClient.RequestHeader> headers, String name,
			String replacementValue) {
		List<McpChunkedHttpClient.RequestHeader> copy = new ArrayList<>(headers.size());
		for (McpChunkedHttpClient.RequestHeader header : headers)
			copy.add(header.name().equalsIgnoreCase(name)
					? new McpChunkedHttpClient.RequestHeader(
							header.name(), replacementValue)
					: header);
		return List.copyOf(copy);
	}

	private static String discoverRequest(String id, String protocolVersion,
			boolean includeMetadata) {
		String params = includeMetadata
				? "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
						+ protocolVersion + "\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}"
				: "{}";
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + DISCOVER + "\",\"params\":" + params + "}";
	}

	private static String toolRequest(String id, String tenant) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + CALL_TOOL + "\",\"params\":{"
				+ "\"name\":\"" + TOOL + "\",\"arguments\":{\"tenant\":\""
				+ tenant + "\"},\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
	}
}
