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

import com.soklet.annotation.McpArgument;
import com.soklet.annotation.McpPrompt;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ThreadSafe
public class McpRuntimeTests {
	@Test
	public void initializeReturnsSessionIdAndServerMetadata() {
		Soklet.runSimulator(configuration(), simulator -> {
			McpRequestResult.ResponseCompleted requestResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-1",
							  "method":"initialize",
							  "params":{
							    "protocolVersion":"2025-11-25",
							    "capabilities":{},
							    "clientInfo":{"name":"test-client","version":"1.0.0"}
							  }
							}
							""", Map.of()));

			Assertions.assertEquals(Integer.valueOf(200), requestResult.getRequestResult().getMarshaledResponse().getStatusCode());
			String sessionId = headerValue(requestResult, "MCP-Session-Id");
			Assertions.assertFalse(sessionId.isBlank());

			McpObject body = jsonBody(requestResult);
			McpObject result = (McpObject) body.get("result").orElseThrow();
			Assertions.assertEquals("2025-11-25", ((McpString) result.get("protocolVersion").orElseThrow()).value());

			McpObject serverInfo = (McpObject) result.get("serverInfo").orElseThrow();
			Assertions.assertEquals("catalog", ((McpString) serverInfo.get("name").orElseThrow()).value());
			Assertions.assertEquals("1.0.0", ((McpString) serverInfo.get("version").orElseThrow()).value());
			Assertions.assertEquals("Catalog MCP", ((McpString) serverInfo.get("title").orElseThrow()).value());
			Assertions.assertEquals("Use read-only mode.", ((McpString) result.get("instructions").orElseThrow()).value());
		});
	}

	@Test
	public void pingSucceedsAfterInitializeBeforeInitializedNotification() {
		Soklet.runSimulator(configuration(), simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", initializeJson("req-1"), Map.of()));
			String sessionId = headerValue(initializeResult, "MCP-Session-Id");

			McpRequestResult.ResponseCompleted pingResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-2",
							  "method":"ping",
							  "params":{}
							}
							""", Map.of(
							"MCP-Session-Id", Set.of(sessionId),
							"MCP-Protocol-Version", Set.of("2025-11-25")
					)));

			Assertions.assertEquals(Integer.valueOf(200), pingResult.getRequestResult().getMarshaledResponse().getStatusCode());
			McpObject body = jsonBody(pingResult);
			McpObject result = (McpObject) body.get("result").orElseThrow();
			Assertions.assertTrue(result.values().isEmpty());
		});
	}

	@Test
	public void generatedListsRequireInitializedNotificationAndExposeSchemas() {
		Soklet.runSimulator(configuration(), simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", initializeJson("req-1"), Map.of()));
			String sessionId = headerValue(initializeResult, "MCP-Session-Id");
			Map<String, Set<String>> sessionHeaders = Map.of(
					"MCP-Session-Id", Set.of(sessionId),
					"MCP-Protocol-Version", Set.of("2025-11-25")
			);

			McpRequestResult.ResponseCompleted rejectedToolsList = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-2",
							  "method":"tools/list",
							  "params":{}
							}
							""", sessionHeaders));

			McpObject rejectedBody = jsonBody(rejectedToolsList);
			McpObject rejectedError = (McpObject) rejectedBody.get("error").orElseThrow();
			Assertions.assertEquals("-32600", ((McpNumber) rejectedError.get("code").orElseThrow()).value().toPlainString());

			McpRequestResult.ResponseCompleted initializedNotification = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "method":"notifications/initialized",
							  "params":{}
							}
							""", sessionHeaders));
			Assertions.assertEquals(Integer.valueOf(202), initializedNotification.getRequestResult().getMarshaledResponse().getStatusCode());

			McpRequestResult.ResponseCompleted toolsList = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-3",
							  "method":"tools/list",
							  "params":{}
							}
							""", sessionHeaders));

			McpObject toolsListBody = jsonBody(toolsList);
			McpArray tools = (McpArray) ((McpObject) toolsListBody.get("result").orElseThrow()).get("tools").orElseThrow();
			McpObject tool = (McpObject) tools.values().get(0);
			Assertions.assertEquals("sum", ((McpString) tool.get("name").orElseThrow()).value());
			McpObject inputSchema = (McpObject) tool.get("inputSchema").orElseThrow();
			McpObject properties = (McpObject) inputSchema.get("properties").orElseThrow();
			Assertions.assertTrue(properties.get("count").isPresent());
			Assertions.assertTrue(properties.get("mode").isPresent());

			McpRequestResult.ResponseCompleted promptsList = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-4",
							  "method":"prompts/list",
							  "params":{}
							}
							""", sessionHeaders));

			McpObject promptsListBody = jsonBody(promptsList);
			McpArray prompts = (McpArray) ((McpObject) promptsListBody.get("result").orElseThrow()).get("prompts").orElseThrow();
			McpObject prompt = (McpObject) prompts.values().get(0);
			Assertions.assertEquals("greet", ((McpString) prompt.get("name").orElseThrow()).value());
			Assertions.assertEquals("Greeting", ((McpString) prompt.get("title").orElseThrow()).value());

			McpArray arguments = (McpArray) prompt.get("arguments").orElseThrow();
			McpObject firstArgument = (McpObject) arguments.values().get(0);
			McpObject secondArgument = (McpObject) arguments.values().get(1);
			Assertions.assertEquals("name", ((McpString) firstArgument.get("name").orElseThrow()).value());
			Assertions.assertEquals(Boolean.TRUE, ((McpBoolean) firstArgument.get("required").orElseThrow()).value());
			Assertions.assertEquals("style", ((McpString) secondArgument.get("name").orElseThrow()).value());
			Assertions.assertEquals(Boolean.FALSE, ((McpBoolean) secondArgument.get("required").orElseThrow()).value());
		});
	}

	@Test
	public void resourcesListFallsBackToEmptyWhenNoHandlerExists() {
		Soklet.runSimulator(configuration(), simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", initializeJson("req-1"), Map.of()));
			String sessionId = headerValue(initializeResult, "MCP-Session-Id");
			Map<String, Set<String>> sessionHeaders = Map.of(
					"MCP-Session-Id", Set.of(sessionId),
					"MCP-Protocol-Version", Set.of("2025-11-25")
			);

			simulator.performMcpRequest(post("/tenants/acme/mcp", """
					{
					  "jsonrpc":"2.0",
					  "method":"notifications/initialized",
					  "params":{}
					}
					""", sessionHeaders));

			McpRequestResult.ResponseCompleted resourcesList = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-2",
							  "method":"resources/list",
							  "params":{}
							}
							""", sessionHeaders));

			McpObject resourcesListBody = jsonBody(resourcesList);
			McpArray resources = (McpArray) ((McpObject) resourcesListBody.get("result").orElseThrow()).get("resources").orElseThrow();
			Assertions.assertTrue(resources.values().isEmpty());
		});
	}

	private static SokletConfig configuration() {
		return SokletConfig.withServer(Server.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.mcpServer(McpServer.withPort(0)
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(CatalogEndpoint.class)))
						.build())
				.build();
	}

	private static Request post(String path,
															String body,
															Map<String, Set<String>> headers) {
		Map<String, Set<String>> allHeaders = new java.util.LinkedHashMap<>(headers);
		allHeaders.put("Content-Type", Set.of("application/json"));

		return Request.withPath(HttpMethod.POST, path)
				.headers(allHeaders)
				.body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
				.build();
	}

	private static String initializeJson(String requestId) {
		return """
				{
				  "jsonrpc":"2.0",
				  "id":"%s",
				  "method":"initialize",
				  "params":{
				    "protocolVersion":"2025-11-25",
				    "capabilities":{},
				    "clientInfo":{"name":"test-client","version":"1.0.0"}
				  }
				}
				""".formatted(requestId);
	}

	private static String headerValue(McpRequestResult.ResponseCompleted responseCompleted,
																		String headerName) {
		return responseCompleted.getRequestResult().getMarshaledResponse().getHeaders().get(headerName).stream().findFirst().orElseThrow();
	}

	private static McpObject jsonBody(McpRequestResult.ResponseCompleted responseCompleted) {
		return (McpObject) McpJsonCodec.parse(responseCompleted.getRequestResult().getMarshaledResponse().getBody().orElseThrow());
	}

	@McpServerEndpoint(
			path = "/tenants/{tenantId}/mcp",
			name = "catalog",
			version = "1.0.0",
			title = "Catalog MCP",
			instructions = "Use read-only mode."
	)
	public static class CatalogEndpoint implements McpEndpoint {
		@Override
		public McpSessionContext initialize(McpInitializationContext context,
																				McpSessionContext session) {
			return session.with("tenantId", context.getEndpointPathParameter("tenantId").orElseThrow());
		}

		@McpTool(name = "sum", description = "Adds numbers.")
		public McpToolResult sum(@McpArgument("count") Integer count,
													 @McpArgument(value = "mode", optional = true) Optional<Mode> mode) {
			return McpToolResult.builder()
					.content(McpTextContent.fromText("%s:%s".formatted(count, mode.orElse(Mode.SIMPLE))))
					.build();
		}

		@McpPrompt(name = "greet", description = "Creates a greeting.", title = "Greeting")
		public McpPromptResult greet(@McpArgument("name") String name,
																 @McpArgument(value = "style", optional = true) Optional<Mode> style) {
			return McpPromptResult.fromMessages(McpPromptMessage.fromAssistantText("Hello %s".formatted(name)));
		}
	}

	public enum Mode {
		SIMPLE,
		FORMAL
	}
}
