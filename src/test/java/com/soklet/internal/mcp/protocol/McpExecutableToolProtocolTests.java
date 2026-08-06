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
import com.soklet.McpAdmissionDecision;
import com.soklet.McpEndpoint;
import com.soklet.McpImplementation;
import com.soklet.McpRequestObservationTestSupport;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RateLimitResult;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ToolInvocationResult;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ToolPlan;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@NotThreadSafe
@Timeout(30)
public class McpExecutableToolProtocolTests {
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String LOOPBACK = "127.0.0.1";

	@Test
	public void static_tools_list_is_framework_owned_complete_and_unpaginated()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger handlers = new AtomicInteger();
		McpNormalizedToolDescriptor descriptor = new McpNormalizedToolDescriptor(
				"lookup", objectSchema(), Optional.of(new McpJsonObject(
						Map.of("type", new McpJsonString("string")))),
				new McpJsonObject(Map.of("title", new McpJsonString("Lookup"))),
				new McpJsonObject(Map.of(
						"com.example/revision", new McpJsonString("7"))));
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"tool-list-test", "3.6.0-SNAPSHOT"))
				.tool(McpNormalizedOperation.tool(
						descriptor, McpMirroredHeaderPlan.empty()))
				.build();
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromToolRoutes(
				Map.of("lookup", new McpApplicationToolRoute(invocation -> {
					handlers.incrementAndGet();
					return McpWireResult.complete(McpJsonObject.empty());
				}, ignored -> McpRateLimitDecision.allowed())));
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				McpHttpEndpointPolicy.forDiscovery(
						CorsAuthorizer.rejectAllInstance(), ignored -> {
							admissions.incrementAndGet();
							return com.soklet.internal.mcp.protocol.McpAdmissionDecision
									.acceptedAnonymous();
						}),
				endpoint, router,
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM);

		try {
			int port = runtime.start().getPort();
			FixedResponse response = send(port,
					request("list-1", "tools/list", ""),
					headers("tools/list"));
			Assertions.assertEquals(200, response.head().status(), response.body());
			Assertions.assertTrue(response.body().contains("\"resultType\":\"complete\""),
					response.body());
			Assertions.assertTrue(response.body().contains("\"ttlMs\":0"),
					response.body());
			Assertions.assertTrue(response.body().contains("\"cacheScope\":\"private\""),
					response.body());
			Assertions.assertTrue(response.body().contains("\"name\":\"lookup\""),
					response.body());
			Assertions.assertTrue(response.body().contains("\"inputSchema\":{\"type\":\"object\"}"),
					response.body());
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(0, handlers.get());
			Assertions.assertEquals(0L, runtime.applicationExecutionSnapshot()
					.orElseThrow().admittedRequests());

			admissions.set(0);
			FixedResponse cursor = send(port,
					request("list-2", "tools/list", ",\"cursor\":\"\""),
					headers("tools/list"));
			assertError(cursor, 400, -32602, "list-2");
			Assertions.assertEquals(0, admissions.get());
			Assertions.assertEquals(0, handlers.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void bridge_routes_exact_tools_and_preserves_policy_order_and_context()
			throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger typedHandlerInvocations = new AtomicInteger();
		McpEndpoint endpoint = publicEndpoint();
		ToolPlan echo = toolPlan("echo", false, stages, invocation -> {
			stages.add("invoke:echo");
			Assertions.assertEquals("tools/call", invocation.jsonRpcMethod());
			Assertions.assertEquals("echo", invocation.operationName());
			Assertions.assertSame(endpoint, invocation.endpoint());
			Assertions.assertEquals("call-1", invocation.requestId().asString().orElseThrow());
			Assertions.assertTrue(invocation.clientCapabilitiesJson().getMembers().isEmpty());
			Assertions.assertTrue(invocation.requestMetadata().find(
					"io.modelcontextprotocol/protocolVersion").isPresent());
			Assertions.assertTrue(invocation.rawArguments().find("value").isPresent());
			typedHandlerInvocations.incrementAndGet();
			return ToolInvocationResult.structured(
					com.soklet.McpJsonObject.builder().put("answer", 42).build(),
					com.soklet.McpJsonObject.emptyInstance());
		});
		McpServerRuntimeBridge bridge = bridge(endpoint, stages,
				List.of(echo), false);

		try {
			int port = bridge.start().getPort();
			FixedResponse response = send(port,
					request("call-1", "tools/call",
							",\"name\":\"echo\",\"arguments\":{\"value\":\"x\"}"),
					headers("tools/call", "echo"));
			Assertions.assertEquals(200, response.head().status(), response.body());
			Assertions.assertTrue(response.body().contains(
					"\"structuredContent\":{\"answer\":42}"), response.body());
			Assertions.assertTrue(response.body().contains(
					"\"text\":\"{\\\"answer\\\":42}\""), response.body());
			Assertions.assertEquals(List.of(
					"admission:echo", "request:echo", "tool:echo", "invoke:echo"),
					stages);
			Assertions.assertEquals(1, typedHandlerInvocations.get());

			stages.clear();
			FixedResponse unknown = send(port,
					request("unknown-1", "tools/call",
							",\"name\":\"absent\",\"arguments\":{}"),
					headers("tools/call", "absent"));
			assertError(unknown, 400, -32602, "unknown-1");
			Assertions.assertTrue(stages.isEmpty());
			Assertions.assertEquals(1, typedHandlerInvocations.get());

			FixedResponse malformedArguments = send(port,
					request("arguments-1", "tools/call",
							",\"name\":\"echo\",\"arguments\":42"),
					headers("tools/call", "echo"));
			assertError(malformedArguments, 400, -32602, "arguments-1");
			Assertions.assertTrue(stages.isEmpty());
			Assertions.assertEquals(1, typedHandlerInvocations.get());
		} finally {
			bridge.stop();
		}
	}

	@Test
	public void invalid_input_and_rate_limit_denials_stop_the_remaining_pipeline()
			throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger typedHandlers = new AtomicInteger();
		McpEndpoint endpoint = publicEndpoint();
		ToolPlan invalid = toolPlan("invalid", false, stages, invocation -> {
			stages.add("validate:invalid");
			if (invocation.rawArguments().find("required").isEmpty())
				return ToolInvocationResult.invalidInput();
			typedHandlers.incrementAndGet();
			return completeOk();
		});
		ToolPlan toolDenied = toolPlan("tool-denied", true, stages,
				invocation -> {
					typedHandlers.incrementAndGet();
					return completeOk();
				});
		ToolPlan requestDenied = toolPlan("request-denied", false, stages,
				invocation -> {
					typedHandlers.incrementAndGet();
					return completeOk();
				});
		McpServerRuntimeBridge bridge = bridge(endpoint, stages,
				List.of(invalid, toolDenied, requestDenied), true);

		try {
			int port = bridge.start().getPort();
			FixedResponse invalidResponse = call(port, "invalid-1", "invalid");
			assertError(invalidResponse, 400, -32602, "invalid-1");
			Assertions.assertEquals(List.of("admission:invalid", "request:invalid",
					"tool:invalid", "validate:invalid"), stages);
			Assertions.assertEquals(0, typedHandlers.get());

			stages.clear();
			FixedResponse toolDeniedResponse = call(port, "limited-1", "tool-denied");
			assertError(toolDeniedResponse, 429, -31999, "limited-1");
			Assertions.assertEquals("2",
					toolDeniedResponse.head().singleHeader("Retry-After"));
			Assertions.assertEquals(List.of("admission:tool-denied",
					"request:tool-denied", "tool:tool-denied"), stages);
			Assertions.assertEquals(0, typedHandlers.get());

			stages.clear();
			FixedResponse requestDeniedResponse =
					call(port, "limited-2", "request-denied");
			assertError(requestDeniedResponse, 429, -31999, "limited-2");
			Assertions.assertEquals("3",
					requestDeniedResponse.head().singleHeader("Retry-After"));
			Assertions.assertEquals(List.of("admission:request-denied",
					"request:request-denied"), stages);
			Assertions.assertEquals(0, typedHandlers.get());
		} finally {
			bridge.stop();
		}
	}

	private static McpServerRuntimeBridge bridge(McpEndpoint endpoint,
			List<String> stages, List<ToolPlan> toolPlans,
			boolean denyRequestNamedRequestDenied) {
		return new McpServerRuntimeBridge(LOOPBACK, 0, endpoint, Set.of(LOOPBACK),
				false, CorsAuthorizer.rejectAllInstance(), true, input -> {
					String operationName = input.operationName().orElse("-");
					stages.add("admission:" + operationName);
					return McpAdmissionDecision.fromAnonymousIdentity();
				}, Optional.of(input -> {
					String operationName = input.operationName().orElse("-");
					stages.add("request:" + operationName);
					if (denyRequestNamedRequestDenied
							&& "request-denied".equals(operationName))
						return RateLimitResult.denied(Duration.ofSeconds(3));
					return RateLimitResult.allowed();
				}), toolPlans, ignored -> {}, ignored -> {},
				McpRequestObservationTestSupport.noOpAdapter());
	}

	private static ToolPlan toolPlan(String name, boolean denyTool,
			List<String> stages,
			McpServerRuntimeBridge.ToolInvoker invoker) {
		return new ToolPlan(name, publicObjectSchema(),
				McpMirroredHeaderPlan.empty(), Optional.empty(),
				com.soklet.McpJsonObject.builder().put("title", "Tool " + name).build(),
				com.soklet.McpJsonObject.emptyInstance(), true, input -> {
					Assertions.assertEquals(
							McpServerRuntimeBridge.RateLimitTarget.TOOL, input.target());
					Assertions.assertEquals(name,
							input.operationName().orElseThrow());
					stages.add("tool:" + name);
					return denyTool
							? RateLimitResult.denied(Duration.ofSeconds(2))
							: RateLimitResult.allowed();
				}, invoker);
	}

	private static ToolInvocationResult completeOk() {
		return ToolInvocationResult.complete(
				com.soklet.McpJsonObject.builder()
						.put("content", com.soklet.McpJsonArray.fromElements(List.of()))
						.build(),
				com.soklet.McpJsonObject.emptyInstance());
	}

	private static McpEndpoint publicEndpoint() {
		return McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"executable-tool-test", "3.6.0-SNAPSHOT").build())
				.build();
	}

	private static McpJsonObject objectSchema() {
		return new McpJsonObject(Map.of("type", new McpJsonString("object")));
	}

	private static com.soklet.McpJsonObject publicObjectSchema() {
		return com.soklet.McpJsonObject.builder().put("type", "object").build();
	}

	private static FixedResponse call(int port, String id, String name)
			throws Exception {
		return send(port, request(id, "tools/call",
				",\"name\":\"" + name + "\",\"arguments\":{}"),
				headers("tools/call", name));
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
			String method) {
		return headers(method, null);
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
