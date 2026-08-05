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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public typed MCP tool registrations.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpTypedToolPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "catalog.search";

	@Test
	public void typedToolCatalogAndCallsUseThePublicPipeline() throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicReference<SearchArguments> observedArguments = new AtomicReference<>();
		AtomicReference<McpJsonObject> observedRawArguments = new AtomicReference<>();
		AtomicReference<McpRequestContext> observedRequest = new AtomicReference<>();

		McpToolRegistration<SearchArguments> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.types(SearchArguments.class, SearchResult.class)
				.handler((request, call, features) -> {
					stages.add("handler:" + TOOL_NAME);
					handlerInvocations.incrementAndGet();
					observedRequest.set(request);
					observedArguments.set(call.getArguments());
					observedRawArguments.set(call.getRawArguments());
					return new SearchResult(List.of(new SearchItem("a", 7)));
				})
				.title("Catalog search")
				.description("Searches the catalog")
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"typed-tool-public-runtime-test", "3.6.0-SNAPSHOT").build())
				.tool(tool)
				.build();
		McpRateLimiter requestRateLimiter = context -> {
			Assertions.assertEquals(McpRateLimitTarget.REQUEST,
					context.getTarget());
			stages.add("request:" + context.getOperationName().orElse("-"));
			return McpRateLimitDecision.fromAllowed();
		};
		McpRateLimiter toolRateLimiter = context -> {
			Assertions.assertEquals(McpRateLimitTarget.TOOL, context.getTarget());
			Assertions.assertEquals(TOOL_NAME,
					context.getOperationName().orElseThrow());
			stages.add("tool:" + context.getOperationName().orElseThrow());
			return McpRateLimitDecision.fromAllowed();
		};
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(context -> {
					stages.add("admission:"
							+ context.getOperationName().orElse("-"));
					return McpAdmissionDecision.fromAnonymousIdentity();
				})
				.requestRateLimiter(requestRateLimiter)
				.toolRateLimiter(toolRateLimiter)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();

			HttpResponse<String> listResponse = send(port,
					request("list-1", "tools/list", ""), "tools/list");
			assertSuccess(listResponse, "list-1");
			String listBody = listResponse.body();
			assertContains(listBody, "\"resultType\":\"complete\"");
			assertContains(listBody, "\"ttlMs\":0");
			assertContains(listBody, "\"cacheScope\":\"private\"");
			assertContains(listBody, "\"name\":\"" + TOOL_NAME + "\"");
			assertContains(listBody, "\"title\":\"Catalog search\"");
			assertContains(listBody,
					"\"description\":\"Searches the catalog\"");
			assertContains(listBody, "\"inputSchema\":{");
			assertContains(listBody, "\"outputSchema\":{");
			assertContains(listBody, "\"query\"");
			assertContains(listBody, "\"pageSizes\"");
			assertContains(listBody, "\"identifier\"");
			assertContains(listBody, "\"score\"");
			assertContains(listBody, "\"type\":\"array\"");
			assertContains(listBody, "\"type\":\"integer\"");
			assertContains(listBody, "\"additionalProperties\":false");
			Assertions.assertFalse(listBody.contains("\"nextCursor\""), listBody);
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(List.of("admission:-", "request:-"), stages);

			stages.clear();
			HttpResponse<String> cursorResponse = send(port,
					request("list-cursor", "tools/list", ",\"cursor\":\"\""),
					"tools/list");
			assertError(cursorResponse, 400, -32602, "list-cursor");
			Assertions.assertTrue(stages.isEmpty(), stages.toString());
			Assertions.assertEquals(0, handlerInvocations.get());

			stages.clear();
			HttpResponse<String> callResponse = send(port,
					request("call-1", "tools/call", ",\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{\"query\":\" exact \""
							+ ",\"pageSizes\":[2,5]}"),
					"tools/call", TOOL_NAME);
			assertSuccess(callResponse, "call-1");
			String callBody = callResponse.body();
			String structuredContent = extractObject(callBody,
					"\"structuredContent\":");
			assertContains(callBody,
					"\"text\":" + jsonString(structuredContent));
			assertContains(structuredContent, "\"items\":[{");
			assertContains(structuredContent, "\"identifier\":\"a\"");
			assertContains(structuredContent, "\"score\":7");
			Assertions.assertEquals(List.of("admission:" + TOOL_NAME,
					"request:" + TOOL_NAME, "tool:" + TOOL_NAME,
					"handler:" + TOOL_NAME), stages);
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(new SearchArguments(" exact ", List.of(2, 5)),
					observedArguments.get());
			Assertions.assertEquals(new McpJsonString(" exact "),
					observedRawArguments.get().find("query").orElseThrow());
			McpJsonArray rawPageSizes = Assertions.assertInstanceOf(
					McpJsonArray.class,
					observedRawArguments.get().find("pageSizes").orElseThrow());
			Assertions.assertEquals(2, rawPageSizes.getElements().size());
			Assertions.assertEquals("tools/call",
					observedRequest.get().getJsonRpcMethod());
			Assertions.assertEquals(McpRequestId.fromString("call-1"),
					observedRequest.get().getRequestId().orElseThrow());
			Assertions.assertSame(endpoint, observedRequest.get().getEndpoint());

			stages.clear();
			HttpResponse<String> invalidResponse = send(port,
					request("invalid-1", "tools/call", ",\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{\"query\":\"missing list\"}"),
					"tools/call", TOOL_NAME);
			assertError(invalidResponse, 400, -32602, "invalid-1");
			Assertions.assertEquals(List.of("admission:" + TOOL_NAME,
					"request:" + TOOL_NAME, "tool:" + TOOL_NAME), stages);
			Assertions.assertEquals(1, handlerInvocations.get());

			stages.clear();
			HttpResponse<String> unknownResponse = send(port,
					request("unknown-1", "tools/call",
							",\"name\":\"catalog.absent\",\"arguments\":{}"),
					"tools/call", "catalog.absent");
			assertError(unknownResponse, 400, -32602, "unknown-1");
			Assertions.assertTrue(stages.isEmpty(), stages.toString());
			Assertions.assertEquals(1, handlerInvocations.get());
		} finally {
			server.stop();
		}
	}

	@Test
	public void mcpPropagationMetadataOverridesPhysicalHttpTraceHeaders()
			throws Exception {
		String mcpTraceparent =
				"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
		String httpTraceparent =
				"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";
		AtomicReference<TraceContext> admissionTraceContext = new AtomicReference<>();
		AtomicReference<TraceContext> handlerTraceContext = new AtomicReference<>();
		AtomicReference<TraceContext> handlerHttpTraceContext = new AtomicReference<>();
		AtomicReference<Map<String, String>> handlerBaggage = new AtomicReference<>();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("propagation")
				.jsonArguments()
				.handler((request, call, features) -> {
					handlerTraceContext.set(request.getTraceContext().orElseThrow());
					handlerHttpTraceContext.set(
							request.getRequest().getTraceContext().orElseThrow());
					handlerBaggage.set(request.getBaggage());
					return McpCompleteResult.fromToolText("done");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"propagation-public-runtime-test", "3.6.0-SNAPSHOT").build())
				.tool(tool)
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(context -> {
					admissionTraceContext.set(
							context.getTraceContext().orElseThrow());
					return McpAdmissionDecision.fromAnonymousIdentity();
				})
				.requestRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"propagation-1\","
					+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
					+ "\"io.modelcontextprotocol/protocolVersion\":\""
					+ PROTOCOL_VERSION + "\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{},"
					+ "\"traceparent\":\"" + mcpTraceparent + "\","
					+ "\"tracestate\":\"rojo=00f067aa0ba902b7\","
					+ "\"baggage\":\"userId=Am%C3%A9lie,serverNode=DF%2028\"},"
					+ "\"name\":\"propagation\",\"arguments\":{}}}";
			HttpResponse<String> response = sendWithHttpTraceparent(port, body,
					"tools/call", "propagation", httpTraceparent);
			assertSuccess(response, "propagation-1");

			Assertions.assertEquals("0af7651916cd43dd8448eb211c80319c",
					admissionTraceContext.get().getTraceId());
			Assertions.assertEquals(admissionTraceContext.get(), handlerTraceContext.get());
			Assertions.assertEquals("rojo=00f067aa0ba902b7",
					handlerTraceContext.get().toTracestateHeaderValue().orElseThrow());
			Assertions.assertEquals("4bf92f3577b34da6a3ce929d0e0e4736",
					handlerHttpTraceContext.get().getTraceId());
			Assertions.assertEquals(Map.of("userId", "Amélie", "serverNode", "DF 28"),
					handlerBaggage.get());
		} finally {
			server.stop();
		}
	}

	private static HttpResponse<String> send(int port, String body,
			String method) throws Exception {
		return send(port, body, method, Optional.empty());
	}

	private static HttpResponse<String> send(int port, String body,
			String method, String operationName) throws Exception {
		return send(port, body, method, Optional.of(operationName));
	}

	private static HttpResponse<String> send(int port, String body,
			String method, Optional<String> operationName) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		operationName.ifPresent(value -> request.header("Mcp-Name", value));
		return httpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(
				body, StandardCharsets.UTF_8)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static HttpResponse<String> sendWithHttpTraceparent(int port,
			String body, String method, String operationName,
			String traceparent) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method)
				.header("Mcp-Name", operationName)
				.header("traceparent", traceparent)
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return httpClient().send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

	private static void assertSuccess(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertError(HttpResponse<String> response, int status,
			int code, String expectedId) {
		Assertions.assertEquals(status, response.statusCode(), response.body());
		assertContains(response.body(), "\"code\":" + code);
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertContains(String text, String expected) {
		Assertions.assertTrue(text.contains(expected), text);
	}

	private static String extractObject(String json, String memberPrefix) {
		int memberIndex = json.indexOf(memberPrefix);
		Assertions.assertTrue(memberIndex >= 0, json);
		int start = memberIndex + memberPrefix.length();
		Assertions.assertTrue(start < json.length() && json.charAt(start) == '{',
				json);
		int depth = 0;
		boolean quoted = false;
		boolean escaped = false;
		for (int index = start; index < json.length(); ++index) {
			char character = json.charAt(index);
			if (quoted) {
				if (escaped)
					escaped = false;
				else if (character == '\\')
					escaped = true;
				else if (character == '"')
					quoted = false;
				continue;
			}
			if (character == '"')
				quoted = true;
			else if (character == '{')
				depth++;
			else if (character == '}' && --depth == 0)
				return json.substring(start, index + 1);
		}
		throw new AssertionError("Unterminated JSON object: " + json);
	}

	private static String jsonString(String value) {
		return "\"" + value.replace("\\", "\\\\")
				.replace("\"", "\\\"") + "\"";
	}

	private static HttpClient httpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build();
	}

	private record SearchArguments(String query, List<Integer> pageSizes) {
	}

	private record SearchResult(List<SearchItem> items) {
	}

	private record SearchItem(String identifier, int score) {
	}
}
