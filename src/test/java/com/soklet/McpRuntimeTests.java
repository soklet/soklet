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
import com.soklet.annotation.McpEndpointPathParameter;
import com.soklet.annotation.McpPrompt;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import com.soklet.annotation.McpUriParameter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
			McpObject capabilities = (McpObject) result.get("capabilities").orElseThrow();
			Assertions.assertTrue(capabilities.get("tools").isPresent());
			Assertions.assertTrue(capabilities.get("prompts").isPresent());
			Assertions.assertTrue(capabilities.get("resources").isPresent());

			McpObject serverInfo = (McpObject) result.get("serverInfo").orElseThrow();
			Assertions.assertEquals("catalog", ((McpString) serverInfo.get("name").orElseThrow()).value());
			Assertions.assertEquals("1.0.0", ((McpString) serverInfo.get("version").orElseThrow()).value());
			Assertions.assertEquals("Catalog MCP", ((McpString) serverInfo.get("title").orElseThrow()).value());
			Assertions.assertEquals("Use read-only mode.", ((McpString) result.get("instructions").orElseThrow()).value());
		});
	}

	@Test
	public void initializeNegotiatesSupportedProtocolVersionWhenClientRequestsUnknownVersion() {
		Soklet.runSimulator(configuration(), simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", initializeJson("req-1", "9999-01-01"), Map.of()));

			String sessionId = headerValue(initializeResult, "MCP-Session-Id");
			McpObject body = jsonBody(initializeResult);
			McpObject result = (McpObject) body.get("result").orElseThrow();
			Assertions.assertEquals("2025-11-25", ((McpString) result.get("protocolVersion").orElseThrow()).value());

			McpRequestResult.ResponseCompleted initializedNotification = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "method":"notifications/initialized",
							  "params":{}
							}
							""", Map.of(
							"MCP-Session-Id", Set.of(sessionId),
							"MCP-Protocol-Version", Set.of("2025-11-25")
					)));

			Assertions.assertEquals(Integer.valueOf(202), initializedNotification.getRequestResult().getMarshaledResponse().getStatusCode());
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
	public void initializedNotificationRejectsSessionsThatHaveNotCompletedInitialization() {
		McpSessionStore sessionStore = McpSessionStore.fromInMemory();
		Instant now = Instant.now();
		String sessionId = "session-not-initialized";
		sessionStore.create(new McpStoredSession(
				sessionId,
				CatalogEndpoint.class,
				now,
				now,
				false,
				false,
				"2025-11-25",
				new McpClientCapabilities(new McpObject(Map.of())),
				new McpNegotiatedCapabilities(new McpObject(Map.of())),
				McpSessionContext.fromBlankSlate(),
				null,
				0L
		));

		Soklet.runSimulator(configuration(LifecycleObserver.defaultInstance(), MetricsCollector.disabledInstance(), sessionStore), simulator -> {
			McpRequestResult.ResponseCompleted initializedNotification = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "method":"notifications/initialized",
							  "params":{}
							}
							""", Map.of(
							"MCP-Session-Id", Set.of(sessionId),
							"MCP-Protocol-Version", Set.of("2025-11-25")
					)));

			McpObject body = jsonBody(initializedNotification);
			McpObject error = (McpObject) body.get("error").orElseThrow();
			Assertions.assertEquals("-32603", ((McpNumber) error.get("code").orElseThrow()).value().toPlainString());
			Assertions.assertEquals("Session not initialized", ((McpString) error.get("message").orElseThrow()).value());
		});
	}

	@Test
	public void mcpCorsPreflightIsAuthorizedWhenOriginIsWhitelisted() {
		Soklet.runSimulator(configuration(
				LifecycleObserver.defaultInstance(),
				MetricsCollector.disabledInstance(),
				null,
				McpCorsAuthorizer.fromWhitelistedOrigins(Set.of("https://chat.openai.com"), origin -> true)), simulator -> {
			McpRequestResult.ResponseCompleted preflightResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					Request.withPath(HttpMethod.OPTIONS, "/tenants/acme/mcp")
							.headers(Map.of(
									"Origin", Set.of("https://chat.openai.com"),
									"Access-Control-Request-Method", Set.of("POST"),
									"Access-Control-Request-Headers", Set.of("Authorization, MCP-Session-Id, MCP-Protocol-Version, Content-Type")
							))
							.build());

			MarshaledResponse marshaledResponse = preflightResult.getRequestResult().getMarshaledResponse();
			Assertions.assertEquals(Integer.valueOf(204), marshaledResponse.getStatusCode());
			Assertions.assertEquals(Set.of("https://chat.openai.com"), marshaledResponse.getHeaders().get("Access-Control-Allow-Origin"));
			Assertions.assertEquals(Set.of("true"), marshaledResponse.getHeaders().get("Access-Control-Allow-Credentials"));
			Assertions.assertTrue(marshaledResponse.getHeaders().get("Access-Control-Allow-Methods").contains("POST"));
			Assertions.assertTrue(marshaledResponse.getHeaders().get("Access-Control-Allow-Headers").contains("Authorization"));
			Assertions.assertTrue(marshaledResponse.getHeaders().get("Access-Control-Allow-Headers").contains("MCP-Session-Id"));
		});
	}

	@Test
	public void mcpCorsHeadersAreAppliedToInitializeAndGetStreamResponses() {
		Soklet.runSimulator(configuration(
				LifecycleObserver.defaultInstance(),
				MetricsCollector.disabledInstance(),
				null,
				McpCorsAuthorizer.fromWhitelistedOrigins(Set.of("https://chat.openai.com"), origin -> true)), simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", initializeJson("req-1"), Map.of(
							"Origin", Set.of("https://chat.openai.com")
					)));
			String sessionId = headerValue(initializeResult, "MCP-Session-Id");
			MarshaledResponse initializeMarshaledResponse = initializeResult.getRequestResult().getMarshaledResponse();

			Assertions.assertEquals(Set.of("https://chat.openai.com"), initializeMarshaledResponse.getHeaders().get("Access-Control-Allow-Origin"));
			Assertions.assertEquals(Set.of("true"), initializeMarshaledResponse.getHeaders().get("Access-Control-Allow-Credentials"));
			Assertions.assertTrue(initializeMarshaledResponse.getHeaders().get("Access-Control-Expose-Headers").contains("MCP-Session-Id"));

			Map<String, Set<String>> sessionHeaders = Map.of(
					"MCP-Session-Id", Set.of(sessionId),
					"MCP-Protocol-Version", Set.of("2025-11-25"),
					"Origin", Set.of("https://chat.openai.com")
			);

			simulator.performMcpRequest(post("/tenants/acme/mcp", """
					{
					  "jsonrpc":"2.0",
					  "method":"notifications/initialized",
					  "params":{}
					}
					""", sessionHeaders));

			McpRequestResult.StreamOpened streamOpened = (McpRequestResult.StreamOpened) simulator.performMcpRequest(
					Request.withPath(HttpMethod.GET, "/tenants/acme/mcp")
							.headers(Map.of(
									"MCP-Session-Id", Set.of(sessionId),
									"MCP-Protocol-Version", Set.of("2025-11-25"),
									"Accept", Set.of("text/event-stream"),
									"Origin", Set.of("https://chat.openai.com")
							))
							.build());

			MarshaledResponse streamMarshaledResponse = streamOpened.getRequestResult().getMarshaledResponse();
			Assertions.assertEquals(Set.of("https://chat.openai.com"), streamMarshaledResponse.getHeaders().get("Access-Control-Allow-Origin"));
			Assertions.assertEquals(Set.of("true"), streamMarshaledResponse.getHeaders().get("Access-Control-Allow-Credentials"));
			Assertions.assertTrue(streamMarshaledResponse.getHeaders().get("Access-Control-Expose-Headers").contains("MCP-Session-Id"));
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

	@Test
	public void resourcesTemplatesListExposesTemplateResources() {
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

			McpRequestResult.ResponseCompleted templatesList = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-2",
							  "method":"resources/templates/list",
							  "params":{}
							}
							""", sessionHeaders));

			McpObject templatesListBody = jsonBody(templatesList);
			McpArray resourceTemplates = (McpArray) ((McpObject) templatesListBody.get("result").orElseThrow()).get("resourceTemplates").orElseThrow();
			Set<String> uriTemplates = new java.util.LinkedHashSet<>();

			for (McpValue resourceTemplateValue : resourceTemplates.values()) {
				McpObject resourceTemplate = (McpObject) resourceTemplateValue;
				uriTemplates.add(((McpString) resourceTemplate.get("uriTemplate").orElseThrow()).value());
			}

			Assertions.assertEquals(Set.of(
					"catalog://tenants/{tenantId}/recipes/{recipeId}",
					"catalog://notes/{noteId}"
			), uriTemplates);
		});
	}

	@Test
	public void resourcesListFallsBackToLiteralStaticResourcesWhenNoHandlerExists() {
		Soklet.runSimulator(literalResourceConfiguration(), simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/literal/mcp", initializeJson("req-1"), Map.of()));
			String sessionId = headerValue(initializeResult, "MCP-Session-Id");
			Map<String, Set<String>> sessionHeaders = Map.of(
					"MCP-Session-Id", Set.of(sessionId),
					"MCP-Protocol-Version", Set.of("2025-11-25")
			);

			simulator.performMcpRequest(post("/literal/mcp", """
					{
					  "jsonrpc":"2.0",
					  "method":"notifications/initialized",
					  "params":{}
					}
					""", sessionHeaders));

			McpRequestResult.ResponseCompleted resourcesList = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/literal/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-2",
							  "method":"resources/list",
							  "params":{}
							}
							""", sessionHeaders));

			McpObject resourcesListBody = jsonBody(resourcesList);
			McpArray resources = (McpArray) ((McpObject) resourcesListBody.get("result").orElseThrow()).get("resources").orElseThrow();
			Assertions.assertEquals(1, resources.values().size());

			McpObject listedResource = (McpObject) resources.values().get(0);
			Assertions.assertEquals("catalog://status", ((McpString) listedResource.get("uri").orElseThrow()).value());
			Assertions.assertEquals("status", ((McpString) listedResource.get("name").orElseThrow()).value());
			Assertions.assertEquals("text/plain", ((McpString) listedResource.get("mimeType").orElseThrow()).value());
			Assertions.assertEquals("Service status.", ((McpString) listedResource.get("description").orElseThrow()).value());
		});
	}

	@Test
	public void annotatedHandlersSupportToolCallPromptGetAndResourceRead() {
		Soklet.runSimulator(configuration(), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult.ResponseCompleted toolCall = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-10",
							  "method":"tools/call",
							  "params":{
							    "name":"sum",
							    "arguments":{
							      "count":5,
							      "mode":"FORMAL"
							    }
							  }
							}
							""", sessionHeaders));

			McpObject toolCallBody = jsonBody(toolCall);
			McpObject toolResult = (McpObject) toolCallBody.get("result").orElseThrow();
			McpArray toolContent = (McpArray) toolResult.get("content").orElseThrow();
			McpObject firstToolContent = (McpObject) toolContent.values().get(0);
			Assertions.assertEquals("5:FORMAL:acme", ((McpString) firstToolContent.get("text").orElseThrow()).value());
			McpObject structuredContent = (McpObject) toolResult.get("structuredContent").orElseThrow();
			Assertions.assertEquals("acme", ((McpString) structuredContent.get("tenantId").orElseThrow()).value());
			Assertions.assertEquals("5", ((McpNumber) structuredContent.get("count").orElseThrow()).value().toPlainString());

			McpRequestResult.ResponseCompleted promptGet = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-11",
							  "method":"prompts/get",
							  "params":{
							    "name":"greet",
							    "arguments":{
							      "name":"Nina",
							      "style":"FORMAL"
							    }
							  }
							}
							""", sessionHeaders));

			McpObject promptBody = jsonBody(promptGet);
			McpObject promptResult = (McpObject) promptBody.get("result").orElseThrow();
			Assertions.assertEquals("Creates a greeting.", ((McpString) promptResult.get("description").orElseThrow()).value());
			McpArray messages = (McpArray) promptResult.get("messages").orElseThrow();
			McpObject firstMessage = (McpObject) messages.values().get(0);
			Assertions.assertEquals("assistant", ((McpString) firstMessage.get("role").orElseThrow()).value());
			McpObject messageContent = (McpObject) firstMessage.get("content").orElseThrow();
			Assertions.assertEquals("Hello Nina from acme in FORMAL", ((McpString) messageContent.get("text").orElseThrow()).value());

			McpRequestResult.ResponseCompleted resourceRead = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-12",
							  "method":"resources/read",
							  "params":{
							    "uri":"catalog://tenants/acme/recipes/latte"
							  }
							}
							""", sessionHeaders));

			McpObject resourceBody = jsonBody(resourceRead);
			McpArray contents = (McpArray) ((McpObject) resourceBody.get("result").orElseThrow()).get("contents").orElseThrow();
			McpObject firstContent = (McpObject) contents.values().get(0);
			Assertions.assertEquals("catalog://tenants/acme/recipes/latte", ((McpString) firstContent.get("uri").orElseThrow()).value());
			Assertions.assertEquals("tenant=acme recipe=latte session=acme", ((McpString) firstContent.get("text").orElseThrow()).value());
		});
	}

	@Test
	public void programmaticHandlersAreInvokedForToolPromptAndResourceCalls() {
		Soklet.runSimulator(configuration(), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult.ResponseCompleted toolCall = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-20",
							  "method":"tools/call",
							  "params":{
							    "name":"zz_programmatic_tool",
							    "arguments":{
							      "message":"ping"
							    }
							  }
							}
							""", sessionHeaders));

			McpObject toolBody = jsonBody(toolCall);
			McpObject toolResult = (McpObject) toolBody.get("result").orElseThrow();
			McpArray toolContent = (McpArray) toolResult.get("content").orElseThrow();
			Assertions.assertEquals("acme:ping", ((McpString) ((McpObject) toolContent.values().get(0)).get("text").orElseThrow()).value());

			McpRequestResult.ResponseCompleted promptGet = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-21",
							  "method":"prompts/get",
							  "params":{
							    "name":"zz_programmatic_prompt",
							    "arguments":{
							      "subject":"latte"
							    }
							  }
							}
							""", sessionHeaders));

			McpObject promptBody = jsonBody(promptGet);
			McpObject promptResult = (McpObject) promptBody.get("result").orElseThrow();
			Assertions.assertEquals("Programmatic prompt response", ((McpString) promptResult.get("description").orElseThrow()).value());

			McpRequestResult.ResponseCompleted resourceRead = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-22",
							  "method":"resources/read",
							  "params":{
							    "uri":"catalog://notes/programmatic-1"
							  }
							}
							""", sessionHeaders));

			McpObject resourceBody = jsonBody(resourceRead);
			McpArray contents = (McpArray) ((McpObject) resourceBody.get("result").orElseThrow()).get("contents").orElseThrow();
			McpObject firstContent = (McpObject) contents.values().get(0);
			Assertions.assertEquals("note=programmatic-1 tenant=acme", ((McpString) firstContent.get("text").orElseThrow()).value());
		});
	}

	@Test
	public void programmaticHandlersRejectMissingRequiredArgumentsFromSchema() {
		Soklet.runSimulator(configuration(), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult.ResponseCompleted toolCall = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-23",
							  "method":"tools/call",
							  "params":{
							    "name":"zz_programmatic_tool",
							    "arguments":{}
							  }
							}
							""", sessionHeaders));

			McpObject toolBody = jsonBody(toolCall);
			McpObject toolError = (McpObject) toolBody.get("error").orElseThrow();
			Assertions.assertEquals("-32602", ((McpNumber) toolError.get("code").orElseThrow()).value().toPlainString());
			Assertions.assertEquals("Missing required argument 'message'", ((McpString) toolError.get("message").orElseThrow()).value());

			McpRequestResult.ResponseCompleted promptGet = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-24",
							  "method":"prompts/get",
							  "params":{
							    "name":"zz_programmatic_prompt",
							    "arguments":{}
							  }
							}
							""", sessionHeaders));

			McpObject promptBody = jsonBody(promptGet);
			McpObject promptError = (McpObject) promptBody.get("error").orElseThrow();
			Assertions.assertEquals("-32602", ((McpNumber) promptError.get("code").orElseThrow()).value().toPlainString());
			Assertions.assertEquals("Missing required argument 'subject'", ((McpString) promptError.get("message").orElseThrow()).value());
		});
	}

	@Test
	public void toolErrorsAreMappedThroughHandleToolError() {
		Soklet.runSimulator(configuration(), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult.ResponseCompleted toolCall = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-30",
							  "method":"tools/call",
							  "params":{
							    "name":"zz_fail",
							    "arguments":{}
							  }
							}
							""", sessionHeaders));

			McpObject toolBody = jsonBody(toolCall);
			McpObject toolResult = (McpObject) toolBody.get("result").orElseThrow();
			Assertions.assertEquals(Boolean.TRUE, ((McpBoolean) toolResult.get("isError").orElseThrow()).value());
			McpArray toolContent = (McpArray) toolResult.get("content").orElseThrow();
			Assertions.assertEquals("tool:boom", ((McpString) ((McpObject) toolContent.values().get(0)).get("text").orElseThrow()).value());
		});
	}

	@Test
	public void toolCallWithoutProgressTokenUsesNormalJsonResponse() {
		CatalogEndpoint.capturedProgressReporter.set(null);

		Soklet.runSimulator(configuration(), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult requestResult = simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-35",
							  "method":"tools/call",
							  "params":{
							    "name":"zz_progress",
							    "arguments":{}
							  }
							}
							""", sessionHeaders));

			Assertions.assertInstanceOf(McpRequestResult.ResponseCompleted.class, requestResult);
			McpObject body = jsonBody((McpRequestResult.ResponseCompleted) requestResult);
			McpObject result = (McpObject) body.get("result").orElseThrow();
			McpArray content = (McpArray) result.get("content").orElseThrow();
			Assertions.assertEquals("progress:false:acme",
					((McpString) ((McpObject) content.values().get(0)).get("text").orElseThrow()).value());
			Assertions.assertNull(CatalogEndpoint.capturedProgressReporter.get());
		});
	}

	@Test
	public void toolCallWithProgressTokenUpgradesToEventStreamAndClosesAfterReplay() {
		CatalogEndpoint.capturedProgressReporter.set(null);

		Soklet.runSimulator(configuration(), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult requestResult = simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-36",
							  "method":"tools/call",
							  "params":{
							    "name":"zz_progress",
							    "arguments":{},
							    "_meta":{
							      "progressToken":"progress-1"
							    }
							  }
							}
							""", sessionHeaders));

			Assertions.assertInstanceOf(McpRequestResult.StreamOpened.class, requestResult);
			McpRequestResult.StreamOpened streamOpened = (McpRequestResult.StreamOpened) requestResult;
			List<McpObject> messages = new ArrayList<>();
			streamOpened.registerMessageConsumer(messages::add);

			Assertions.assertEquals(Integer.valueOf(200), streamOpened.getRequestResult().getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("text/event-stream; charset=UTF-8",
					streamOpened.getRequestResult().getMarshaledResponse().getHeaders().get("Content-Type").iterator().next());
			Assertions.assertEquals(3, messages.size());

			McpObject firstMessage = messages.get(0);
			Assertions.assertEquals("notifications/progress", ((McpString) firstMessage.get("method").orElseThrow()).value());
			McpObject firstParams = (McpObject) firstMessage.get("params").orElseThrow();
			Assertions.assertEquals("progress-1", ((McpString) firstParams.get("progressToken").orElseThrow()).value());
			Assertions.assertEquals("1", ((McpNumber) firstParams.get("progress").orElseThrow()).value().toPlainString());
			Assertions.assertEquals("2", ((McpNumber) firstParams.get("total").orElseThrow()).value().toPlainString());
			Assertions.assertEquals("warming", ((McpString) firstParams.get("message").orElseThrow()).value());

			McpObject terminalMessage = messages.get(2);
			McpObject terminalResult = (McpObject) terminalMessage.get("result").orElseThrow();
			McpArray terminalContent = (McpArray) terminalResult.get("content").orElseThrow();
			Assertions.assertEquals("progress:true:acme",
					((McpString) ((McpObject) terminalContent.values().get(0)).get("text").orElseThrow()).value());
			Assertions.assertTrue(streamOpened.isClosed());
		});
	}

	@Test
	public void progressReporterFailsFastAfterRequestCompletion() {
		CatalogEndpoint.capturedProgressReporter.set(null);

		Soklet.runSimulator(configuration(), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult.StreamOpened streamOpened = (McpRequestResult.StreamOpened) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-37",
							  "method":"tools/call",
							  "params":{
							    "name":"zz_progress",
							    "arguments":{},
							    "_meta":{
							      "progressToken":"progress-2"
							    }
							  }
							}
							""", sessionHeaders));

			streamOpened.registerMessageConsumer(ignored -> {
			});

			McpProgressReporter progressReporter = CatalogEndpoint.capturedProgressReporter.get();
			Assertions.assertNotNull(progressReporter);
			Assertions.assertThrows(IllegalStateException.class,
					() -> progressReporter.reportProgress(new java.math.BigDecimal("3"), null, "late"));
		});
	}

	@Test
	public void postAndGetRejectIncompatibleAcceptHeaders() {
		Soklet.runSimulator(configuration(), simulator -> {
			McpRequestResult.ResponseCompleted rejectedPost = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", initializeJson("req-60"), Map.of(
							"Accept", Set.of("application/json")
					)));

			Assertions.assertEquals(Integer.valueOf(406), rejectedPost.getRequestResult().getMarshaledResponse().getStatusCode());

			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);
			McpRequestResult.ResponseCompleted rejectedGet = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					Request.withPath(HttpMethod.GET, "/tenants/acme/mcp")
							.headers(Map.of(
									"MCP-Session-Id", sessionHeaders.get("MCP-Session-Id"),
									"MCP-Protocol-Version", sessionHeaders.get("MCP-Protocol-Version"),
									"Accept", Set.of("application/json")
							))
							.build());

			Assertions.assertEquals(Integer.valueOf(406), rejectedGet.getRequestResult().getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void oversizedMcpRequestReturns413() {
		Soklet.runSimulator(configuration(), simulator -> {
			Request oversizedRequest = Request.withPath(HttpMethod.POST, "/tenants/acme/mcp")
					.headers(Map.of("Content-Type", Set.of("application/json")))
					.contentTooLarge(true)
					.build();

			McpRequestResult.ResponseCompleted responseCompleted = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(oversizedRequest);
			Assertions.assertEquals(Integer.valueOf(413), responseCompleted.getRequestResult().getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void getMcpOpensStreamAndDeleteTerminatesIt() {
		Soklet.runSimulator(configuration(), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult requestResult = simulator.performMcpRequest(Request.withPath(HttpMethod.GET, "/tenants/acme/mcp")
					.headers(Map.of(
							"MCP-Session-Id", sessionHeaders.get("MCP-Session-Id"),
							"MCP-Protocol-Version", sessionHeaders.get("MCP-Protocol-Version"),
							"Accept", Set.of("text/event-stream")
					))
					.build());

			Assertions.assertInstanceOf(McpRequestResult.StreamOpened.class, requestResult);
			McpRequestResult.StreamOpened streamOpened = (McpRequestResult.StreamOpened) requestResult;
			Assertions.assertEquals(Integer.valueOf(200), streamOpened.getRequestResult().getMarshaledResponse().getStatusCode());
			Assertions.assertFalse(streamOpened.isClosed());

			McpRequestResult.ResponseCompleted deleteResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					Request.withPath(HttpMethod.DELETE, "/tenants/acme/mcp")
							.headers(sessionHeaders)
							.build());

			Assertions.assertEquals(Integer.valueOf(204), deleteResult.getRequestResult().getMarshaledResponse().getStatusCode());
			Assertions.assertTrue(streamOpened.isClosed());
		});
	}

	@Test
	public void simulatorRoutesInternalSessionMessagesToMostRecentLiveGetStream() {
		Soklet.runSimulator(configuration(), simulator -> {
			Soklet.DefaultSimulator defaultSimulator = (Soklet.DefaultSimulator) simulator;
			Soklet.MockMcpServer mockMcpServer = defaultSimulator.getMcpServer().orElseThrow();
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);
			String sessionId = sessionHeaders.get("MCP-Session-Id").iterator().next();
			McpObject message = internalSessionNotification("latest");

			Assertions.assertFalse(mockMcpServer.publishSessionMessage(sessionId, message));

			McpRequestResult.StreamOpened firstStream = (McpRequestResult.StreamOpened) simulator.performMcpRequest(
					Request.withPath(HttpMethod.GET, "/tenants/acme/mcp")
							.headers(Map.of(
									"MCP-Session-Id", sessionHeaders.get("MCP-Session-Id"),
									"MCP-Protocol-Version", sessionHeaders.get("MCP-Protocol-Version"),
									"Accept", Set.of("text/event-stream")
							))
							.build());
			McpRequestResult.StreamOpened secondStream = (McpRequestResult.StreamOpened) simulator.performMcpRequest(
					Request.withPath(HttpMethod.GET, "/tenants/acme/mcp")
							.headers(Map.of(
									"MCP-Session-Id", sessionHeaders.get("MCP-Session-Id"),
									"MCP-Protocol-Version", sessionHeaders.get("MCP-Protocol-Version"),
									"Accept", Set.of("text/event-stream")
							))
							.build());
			List<McpObject> firstMessages = new ArrayList<>();
			List<McpObject> secondMessages = new ArrayList<>();
			firstStream.registerMessageConsumer(firstMessages::add);
			secondStream.registerMessageConsumer(secondMessages::add);

			Assertions.assertTrue(mockMcpServer.publishSessionMessage(sessionId, message));
			Assertions.assertTrue(firstMessages.isEmpty());
			Assertions.assertEquals(List.of(message), secondMessages);

			secondStream.close();

			McpObject fallbackMessage = internalSessionNotification("fallback");
			Assertions.assertTrue(mockMcpServer.publishSessionMessage(sessionId, fallbackMessage));
			Assertions.assertEquals(List.of(fallbackMessage), firstMessages);
		});
	}

	@Test
	public void closingGetStreamSimulatesClientDisconnectWithoutEndingSession() {
		RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
		DefaultMetricsCollector metricsCollector = DefaultMetricsCollector.defaultInstance();

		Soklet.runSimulator(configuration(lifecycleObserver, metricsCollector), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult.StreamOpened streamOpened = (McpRequestResult.StreamOpened) simulator.performMcpRequest(
					Request.withPath(HttpMethod.GET, "/tenants/acme/mcp")
							.headers(Map.of(
									"MCP-Session-Id", sessionHeaders.get("MCP-Session-Id"),
									"MCP-Protocol-Version", sessionHeaders.get("MCP-Protocol-Version"),
									"Accept", Set.of("text/event-stream")
							))
							.build());

			Assertions.assertEquals(1L, metricsCollector.snapshot().orElseThrow().getActiveMcpStreams());
			streamOpened.close();
			streamOpened.close();

			Assertions.assertTrue(streamOpened.isClosed());
			Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED, lifecycleObserver.streamTerminationReason);
			Assertions.assertNull(lifecycleObserver.sessionTerminationReason);
			Assertions.assertEquals(0L, metricsCollector.snapshot().orElseThrow().getActiveMcpStreams());

			McpRequestResult.ResponseCompleted deleteResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					Request.withPath(HttpMethod.DELETE, "/tenants/acme/mcp")
							.headers(sessionHeaders)
							.build());

			Assertions.assertEquals(Integer.valueOf(204), deleteResult.getRequestResult().getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED, lifecycleObserver.streamTerminationReason);
		});
	}

	@Test
	public void activeGetStreamPreventsIdleExpiryUntilClosed() throws Exception {
		RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
		DefaultMetricsCollector metricsCollector = DefaultMetricsCollector.defaultInstance();

		Soklet.runSimulator(configuration(lifecycleObserver,
				metricsCollector,
				McpSessionStore.fromInMemory(Duration.ofMillis(50))), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			McpRequestResult.StreamOpened streamOpened = (McpRequestResult.StreamOpened) simulator.performMcpRequest(
					Request.withPath(HttpMethod.GET, "/tenants/acme/mcp")
							.headers(Map.of(
									"MCP-Session-Id", sessionHeaders.get("MCP-Session-Id"),
									"MCP-Protocol-Version", sessionHeaders.get("MCP-Protocol-Version"),
									"Accept", Set.of("text/event-stream")
							))
							.build());

			sleepUnchecked(120L);

			McpRequestResult.ResponseCompleted stillAlive = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-70",
							  "method":"ping",
							  "params":{}
							}
							""", sessionHeaders));

			Assertions.assertEquals(Integer.valueOf(200), stillAlive.getRequestResult().getMarshaledResponse().getStatusCode());

			streamOpened.close();
			sleepUnchecked(120L);

			McpRequestResult.ResponseCompleted expired = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/tenants/acme/mcp", """
							{
							  "jsonrpc":"2.0",
							  "id":"req-71",
							  "method":"ping",
							  "params":{}
							}
							""", sessionHeaders));

			Assertions.assertEquals(Integer.valueOf(404), expired.getRequestResult().getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(McpSessionTerminationReason.IDLE_TIMEOUT, lifecycleObserver.sessionTerminationReason);
			Assertions.assertEquals(0L, metricsCollector.snapshot().orElseThrow().getActiveMcpSessions());
		});
	}

	@Test
	public void lifecycleObserverReceivesMcpSessionRequestAndStreamCallbacks() {
		RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();

		Soklet.runSimulator(configuration(lifecycleObserver, MetricsCollector.disabledInstance()), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			simulator.performMcpRequest(post("/tenants/acme/mcp", """
					{
					  "jsonrpc":"2.0",
					  "id":"req-40",
					  "method":"tools/call",
					  "params":{
					    "name":"sum",
					    "arguments":{
					      "count":2
					    }
					  }
					}
					""", sessionHeaders));

			simulator.performMcpRequest(Request.withPath(HttpMethod.GET, "/tenants/acme/mcp")
					.headers(Map.of(
							"MCP-Session-Id", sessionHeaders.get("MCP-Session-Id"),
							"MCP-Protocol-Version", sessionHeaders.get("MCP-Protocol-Version"),
							"Accept", Set.of("text/event-stream")
					))
					.build());

			simulator.performMcpRequest(Request.withPath(HttpMethod.DELETE, "/tenants/acme/mcp")
					.headers(sessionHeaders)
					.build());
		});

		Assertions.assertEquals(1, lifecycleObserver.createdSessionIds.size());
		Assertions.assertTrue(lifecycleObserver.startedMethods.contains("initialize"));
		Assertions.assertTrue(lifecycleObserver.startedMethods.contains("notifications/initialized"));
		Assertions.assertTrue(lifecycleObserver.startedMethods.contains("tools/call"));
		Assertions.assertEquals(McpRequestOutcome.SUCCESS_RESPONSE, lifecycleObserver.outcomesByMethod.get("initialize"));
		Assertions.assertEquals(McpRequestOutcome.SUCCESS_NOTIFICATION, lifecycleObserver.outcomesByMethod.get("notifications/initialized"));
		Assertions.assertEquals(McpRequestOutcome.SUCCESS_RESPONSE, lifecycleObserver.outcomesByMethod.get("tools/call"));
		Assertions.assertEquals(1, lifecycleObserver.establishedStreamSessionIds.size());
		Assertions.assertEquals(McpSessionTerminationReason.CLIENT_REQUESTED, lifecycleObserver.sessionTerminationReason);
		Assertions.assertEquals(McpStreamTerminationReason.SESSION_TERMINATED, lifecycleObserver.streamTerminationReason);
	}

	@Test
	public void initializeFailureCreatesThenTerminatesSession() {
		RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();

		Soklet.runSimulator(failingInitializeConfiguration(lifecycleObserver), simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/failing/mcp", initializeJson("req-1"), Map.of()));

			McpObject responseBody = jsonBody(initializeResult);
			Assertions.assertTrue(responseBody.get("error").isPresent());
		});

		Assertions.assertEquals(1, lifecycleObserver.createdSessionIds.size());
		Assertions.assertEquals(McpSessionTerminationReason.INITIALIZATION_FAILED, lifecycleObserver.sessionTerminationReason);
	}

	@Test
	public void initializeFailureDeletesTerminatedSessionFromDefaultInMemoryStore() {
		RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
		McpSessionStore sessionStore = McpSessionStore.fromInMemory();

		Soklet.runSimulator(failingInitializeConfiguration(lifecycleObserver, sessionStore), simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/failing/mcp", initializeJson("req-1"), Map.of()));

			McpObject responseBody = jsonBody(initializeResult);
			Assertions.assertTrue(responseBody.get("error").isPresent());
		});

		String sessionId = lifecycleObserver.createdSessionIds.get(0);
		Assertions.assertTrue(sessionStore.findBySessionId(sessionId).isEmpty());
	}

	@Test
	public void initializeFailureStillCleansUpWhenHandleErrorThrows() {
		RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
		McpSessionStore sessionStore = McpSessionStore.fromInMemory();

		Soklet.runSimulator(failingHandleErrorConfiguration(lifecycleObserver, sessionStore), simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					post("/failing-handle-error/mcp", initializeJson("req-1"), Map.of()));

			McpObject responseBody = jsonBody(initializeResult);
			McpObject error = (McpObject) responseBody.get("error").orElseThrow();
			Assertions.assertEquals("-32603", ((McpNumber) error.get("code").orElseThrow()).value().toPlainString());
			Assertions.assertEquals("Internal error", ((McpString) error.get("message").orElseThrow()).value());
			Assertions.assertFalse(initializeResult.getRequestResult().getMarshaledResponse().getHeaders().containsKey("MCP-Session-Id"));
		});

		Assertions.assertEquals(1, lifecycleObserver.createdSessionIds.size());
		Assertions.assertEquals(McpSessionTerminationReason.INITIALIZATION_FAILED, lifecycleObserver.sessionTerminationReason);
		String sessionId = lifecycleObserver.createdSessionIds.get(0);
		Assertions.assertTrue(sessionStore.findBySessionId(sessionId).isEmpty());
	}

	@Test
	public void mcpMetricsAreRecordedSeparatelyFromHttpMetrics() {
		DefaultMetricsCollector metricsCollector = DefaultMetricsCollector.defaultInstance();

		Soklet.runSimulator(configuration(LifecycleObserver.defaultInstance(), metricsCollector), simulator -> {
			Map<String, Set<String>> sessionHeaders = initializedSessionHeaders(simulator);

			simulator.performMcpRequest(post("/tenants/acme/mcp", """
					{
					  "jsonrpc":"2.0",
					  "id":"req-50",
					  "method":"tools/call",
					  "params":{
					    "name":"zz_fail",
					    "arguments":{}
					  }
					}
					""", sessionHeaders));

			simulator.performMcpRequest(Request.withPath(HttpMethod.GET, "/tenants/acme/mcp")
					.headers(Map.of(
							"MCP-Session-Id", sessionHeaders.get("MCP-Session-Id"),
							"MCP-Protocol-Version", sessionHeaders.get("MCP-Protocol-Version"),
							"Accept", Set.of("text/event-stream")
					))
					.build());

			simulator.performMcpRequest(Request.withPath(HttpMethod.DELETE, "/tenants/acme/mcp")
					.headers(sessionHeaders)
					.build());
		});

		MetricsCollector.Snapshot snapshot = metricsCollector.snapshot().orElseThrow();
		Assertions.assertTrue(snapshot.getHttpRequestDurations().isEmpty());
		Assertions.assertEquals(0L, snapshot.getActiveRequests());
		Assertions.assertEquals(0L, snapshot.getActiveMcpSessions());
		Assertions.assertEquals(0L, snapshot.getActiveMcpStreams());
		Assertions.assertEquals(1L, snapshot.getMcpRequests().get(new MetricsCollector.McpEndpointRequestOutcomeKey(
				CatalogEndpoint.class, "initialize", McpRequestOutcome.SUCCESS_RESPONSE)));
		Assertions.assertEquals(1L, snapshot.getMcpRequests().get(new MetricsCollector.McpEndpointRequestOutcomeKey(
				CatalogEndpoint.class, "notifications/initialized", McpRequestOutcome.SUCCESS_NOTIFICATION)));
		Assertions.assertEquals(1L, snapshot.getMcpRequests().get(new MetricsCollector.McpEndpointRequestOutcomeKey(
				CatalogEndpoint.class, "tools/call", McpRequestOutcome.TOOL_ERROR_RESULT)));
		Assertions.assertEquals(1L, snapshot.getMcpSessionDurations().get(new MetricsCollector.McpEndpointSessionTerminationKey(
				CatalogEndpoint.class, McpSessionTerminationReason.CLIENT_REQUESTED)).getCount());
		Assertions.assertEquals(1L, snapshot.getMcpStreamDurations().get(new MetricsCollector.McpEndpointStreamTerminationKey(
				CatalogEndpoint.class, McpStreamTerminationReason.SESSION_TERMINATED)).getCount());
	}

	private static SokletConfig configuration() {
		return configuration(LifecycleObserver.defaultInstance(), MetricsCollector.defaultInstance());
	}

	private static SokletConfig configuration(LifecycleObserver lifecycleObserver,
																							 MetricsCollector metricsCollector) {
		return configuration(lifecycleObserver, metricsCollector, null);
	}

	private static SokletConfig configuration(LifecycleObserver lifecycleObserver,
																							 MetricsCollector metricsCollector,
																							 McpSessionStore sessionStore) {
		return configuration(lifecycleObserver, metricsCollector, sessionStore, null);
	}

	private static SokletConfig configuration(LifecycleObserver lifecycleObserver,
																							 MetricsCollector metricsCollector,
																							 McpSessionStore sessionStore,
																							 McpCorsAuthorizer corsAuthorizer) {
		McpHandlerResolver handlerResolver = McpHandlerResolver.fromClasses(Set.of(CatalogEndpoint.class))
				.withTool(new ProgrammaticEchoToolHandler(), CatalogEndpoint.class)
				.withPrompt(new ProgrammaticCatalogPromptHandler(), CatalogEndpoint.class)
				.withResource(new ProgrammaticNoteResourceHandler(), CatalogEndpoint.class);

		McpServer.Builder mcpServerBuilder = McpServer.withPort(0)
				.handlerResolver(handlerResolver);

		if (sessionStore != null)
			mcpServerBuilder.sessionStore(sessionStore);

		if (corsAuthorizer != null)
			mcpServerBuilder.corsAuthorizer(corsAuthorizer);

		return SokletConfig.withServer(Server.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.metricsCollector(metricsCollector)
				.mcpServer(mcpServerBuilder.build())
				.build();
	}

	private static SokletConfig failingInitializeConfiguration(LifecycleObserver lifecycleObserver) {
		return failingInitializeConfiguration(lifecycleObserver, McpSessionStore.fromInMemory());
	}

	private static SokletConfig failingInitializeConfiguration(LifecycleObserver lifecycleObserver,
																										 McpSessionStore sessionStore) {
		return SokletConfig.withServer(Server.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.metricsCollector(MetricsCollector.disabledInstance())
				.mcpServer(McpServer.withPort(0)
						.sessionStore(sessionStore)
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(FailingInitializeEndpoint.class)))
						.build())
				.build();
	}

	private static SokletConfig failingHandleErrorConfiguration(LifecycleObserver lifecycleObserver,
																										 McpSessionStore sessionStore) {
		return SokletConfig.withServer(Server.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.metricsCollector(MetricsCollector.disabledInstance())
				.mcpServer(McpServer.withPort(0)
						.sessionStore(sessionStore)
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(FailingHandleErrorEndpoint.class)))
						.build())
				.build();
	}

	private static SokletConfig literalResourceConfiguration() {
		return SokletConfig.withServer(Server.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(LifecycleObserver.defaultInstance())
				.metricsCollector(MetricsCollector.defaultInstance())
				.mcpServer(McpServer.withPort(0)
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(LiteralResourceEndpoint.class)))
						.build())
				.build();
	}

	private static Map<String, Set<String>> initializedSessionHeaders(Simulator simulator) {
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

		return sessionHeaders;
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
		return initializeJson(requestId, "2025-11-25");
	}

	private static String initializeJson(String requestId,
																			 String protocolVersion) {
		return """
				{
				  "jsonrpc":"2.0",
				  "id":"%s",
				  "method":"initialize",
				  "params":{
				    "protocolVersion":"%s",
				    "capabilities":{},
				    "clientInfo":{"name":"test-client","version":"1.0.0"}
				  }
				}
				""".formatted(requestId, protocolVersion);
	}

	private static void sleepUnchecked(long durationInMillis) {
		try {
			Thread.sleep(durationInMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	private static String headerValue(McpRequestResult.ResponseCompleted responseCompleted,
																		String headerName) {
		return responseCompleted.getRequestResult().getMarshaledResponse().getHeaders().get(headerName).stream().findFirst().orElseThrow();
	}

	private static McpObject jsonBody(McpRequestResult.ResponseCompleted responseCompleted) {
		return (McpObject) McpJsonCodec.parse(responseCompleted.getRequestResult().getMarshaledResponse().getBody().orElseThrow());
	}

	private static McpObject internalSessionNotification(String value) {
		return new McpObject(Map.of(
				"jsonrpc", new McpString("2.0"),
				"method", new McpString("notifications/test"),
				"params", new McpObject(Map.of("value", new McpString(value)))
		));
	}

	@McpServerEndpoint(
			path = "/tenants/{tenantId}/mcp",
			name = "catalog",
			version = "1.0.0",
			title = "Catalog MCP",
			instructions = "Use read-only mode."
	)
	public static class CatalogEndpoint implements McpEndpoint {
		private static final AtomicReference<McpProgressReporter> capturedProgressReporter = new AtomicReference<>();

		@Override
		public McpSessionContext initialize(McpInitializationContext context,
																				McpSessionContext session) {
			return session.with("tenantId", context.getEndpointPathParameter("tenantId").orElseThrow());
		}

		@Override
		public McpToolResult handleToolError(Throwable throwable,
																				 McpToolCallContext context) {
			return McpToolResult.fromErrorMessage("tool:%s".formatted(throwable.getMessage()));
		}

		@McpTool(name = "zz_fail", description = "Fails for testing.")
		public McpToolResult fail() {
			throw new IllegalStateException("boom");
		}

		@McpTool(name = "sum", description = "Adds numbers.")
		public McpToolResult sum(@McpArgument("count") Integer count,
													 @McpArgument(value = "mode", optional = true) Optional<Mode> mode,
													 McpSessionContext sessionContext) {
			String tenantId = sessionContext.get("tenantId", String.class).orElseThrow();
			return McpToolResult.builder()
					.content(McpTextContent.fromText("%s:%s:%s".formatted(count, mode.orElse(Mode.SIMPLE), tenantId)))
					.structuredContent(new McpObject(Map.of(
							"tenantId", new McpString(tenantId),
							"count", new McpNumber(new java.math.BigDecimal(count))
					)))
					.build();
		}

		@McpTool(name = "zz_progress", description = "Reports progress when requested.")
		public McpToolResult progress(McpToolCallContext toolCallContext,
																	McpSessionContext sessionContext) {
			Optional<McpProgressReporter> progressReporter = toolCallContext.getProgressReporter();
			capturedProgressReporter.set(progressReporter.orElse(null));
			progressReporter.ifPresent(reporter -> {
				reporter.reportProgress(new java.math.BigDecimal("1"), new java.math.BigDecimal("2"), "warming");
				reporter.reportProgress(new java.math.BigDecimal("2"), new java.math.BigDecimal("2"), "done");
			});
			return McpToolResult.builder()
					.content(McpTextContent.fromText("progress:%s:%s".formatted(progressReporter.isPresent(),
							sessionContext.get("tenantId", String.class).orElseThrow())))
					.build();
		}

		@McpPrompt(name = "greet", description = "Creates a greeting.", title = "Greeting")
		public McpPromptResult greet(@McpArgument("name") String name,
																 @McpArgument(value = "style", optional = true) Optional<Mode> style,
																 McpSessionContext sessionContext) {
			return McpPromptResult.fromMessages(McpPromptMessage.fromAssistantText("Hello %s from %s in %s".formatted(
					name,
					sessionContext.get("tenantId", String.class).orElseThrow(),
					style.orElse(Mode.SIMPLE))));
		}

		@McpResource(uri = "catalog://tenants/{tenantId}/recipes/{recipeId}", name = "recipe", mimeType = "text/plain")
		public McpResourceContents recipe(@McpEndpointPathParameter("tenantId") String tenantId,
																			@McpUriParameter("recipeId") String recipeId,
																			McpSessionContext sessionContext) {
			return McpResourceContents.fromText(
					"catalog://tenants/%s/recipes/%s".formatted(tenantId, recipeId),
					"tenant=%s recipe=%s session=%s".formatted(
							tenantId,
							recipeId,
							sessionContext.get("tenantId", String.class).orElseThrow()),
					"text/plain");
		}
	}

	private static final class ProgrammaticEchoToolHandler implements McpToolHandler {
		@Override
		public String getName() {
			return "zz_programmatic_tool";
		}

		@Override
		public String getDescription() {
			return "Programmatic echo tool.";
		}

		@Override
		public McpSchema getInputSchema() {
			return McpSchema.object()
					.required("message", McpType.STRING)
					.build();
		}

		@Override
		public McpToolResult handle(McpToolHandlerContext context) {
			String message = ((McpString) context.getArguments().get("message").orElseThrow()).value();
			String tenantId = context.getEndpointPathParameter("tenantId").orElseThrow();
			return McpToolResult.builder()
					.content(McpTextContent.fromText("%s:%s".formatted(tenantId, message)))
					.build();
		}
	}

	private static final class ProgrammaticCatalogPromptHandler implements McpPromptHandler {
		@Override
		public String getName() {
			return "zz_programmatic_prompt";
		}

		@Override
		public String getDescription() {
			return "Programmatic prompt.";
		}

		@Override
		public McpSchema getArgumentsSchema() {
			return McpSchema.object()
					.required("subject", McpType.STRING)
					.build();
		}

		@Override
		public McpPromptResult handle(McpPromptHandlerContext context) {
			String tenantId = context.getSessionContext().get("tenantId", String.class).orElseThrow();
			String subject = ((McpString) context.getArguments().get("subject").orElseThrow()).value();
			return new McpPromptResult("Programmatic prompt response",
					java.util.List.of(McpPromptMessage.fromAssistantText("%s:%s".formatted(tenantId, subject))));
		}
	}

	private static final class ProgrammaticNoteResourceHandler implements McpResourceHandler {
		@Override
		public String getUri() {
			return "catalog://notes/{noteId}";
		}

		@Override
		public String getName() {
			return "programmatic-note";
		}

		@Override
		public String getMimeType() {
			return "text/plain";
		}

		@Override
		public McpResourceContents handle(McpResourceHandlerContext context) {
			String noteId = context.getUriParameter("noteId").orElseThrow();
			String tenantId = context.getSessionContext().get("tenantId", String.class).orElseThrow();
			return McpResourceContents.fromText(context.getRequestedUri(), "note=%s tenant=%s".formatted(noteId, tenantId), "text/plain");
		}
	}

	public enum Mode {
		SIMPLE,
		FORMAL
	}

	@McpServerEndpoint(path = "/failing/mcp", name = "failing", version = "1.0.0")
	public static class FailingInitializeEndpoint implements McpEndpoint {
		@Override
		public McpSessionContext initialize(McpInitializationContext context,
																				McpSessionContext session) {
			throw new IllegalStateException("boom");
		}
	}

	@McpServerEndpoint(path = "/failing-handle-error/mcp", name = "failing-handle-error", version = "1.0.0")
	public static class FailingHandleErrorEndpoint implements McpEndpoint {
		@Override
		public McpSessionContext initialize(McpInitializationContext context,
																				McpSessionContext session) {
			throw new IllegalStateException("boom");
		}

		@Override
		public McpJsonRpcError handleError(Throwable throwable,
																			 McpRequestContext requestContext) {
			throw new IllegalStateException("handleError boom");
		}
	}

	@McpServerEndpoint(path = "/literal/mcp", name = "literal", version = "1.0.0")
	public static class LiteralResourceEndpoint implements McpEndpoint {
		@McpResource(uri = "catalog://status", name = "status", mimeType = "text/plain", description = "Service status.")
		public McpResourceContents status() {
			return McpResourceContents.fromText("catalog://status", "ok", "text/plain");
		}
	}

	private static final class RecordingLifecycleObserver implements LifecycleObserver {
		private final List<String> createdSessionIds;
		private final List<String> startedMethods;
		private final Map<String, McpRequestOutcome> outcomesByMethod;
		private final List<String> establishedStreamSessionIds;
		private McpSessionTerminationReason sessionTerminationReason;
		private McpStreamTerminationReason streamTerminationReason;

		private RecordingLifecycleObserver() {
			this.createdSessionIds = new ArrayList<>();
			this.startedMethods = new ArrayList<>();
			this.outcomesByMethod = new java.util.LinkedHashMap<>();
			this.establishedStreamSessionIds = new ArrayList<>();
		}

		@Override
		public void didCreateMcpSession(Request request,
																	 Class<? extends McpEndpoint> endpointClass,
																	 String sessionId) {
			this.createdSessionIds.add(sessionId);
		}

		@Override
		public void didStartMcpRequestHandling(Request request,
																				 Class<? extends McpEndpoint> endpointClass,
																				 String sessionId,
																				 String jsonRpcMethod,
																				 McpJsonRpcRequestId jsonRpcRequestId) {
			this.startedMethods.add(jsonRpcMethod);
		}

		@Override
		public void didFinishMcpRequestHandling(Request request,
																						Class<? extends McpEndpoint> endpointClass,
																						String sessionId,
																						String jsonRpcMethod,
																						McpJsonRpcRequestId jsonRpcRequestId,
																						McpRequestOutcome requestOutcome,
																						McpJsonRpcError jsonRpcError,
																						Duration duration,
																						List<Throwable> throwables) {
			this.outcomesByMethod.put(jsonRpcMethod, requestOutcome);
		}

		@Override
		public void didEstablishMcpServerSentEventStream(Request request,
																									 Class<? extends McpEndpoint> endpointClass,
																									 String sessionId) {
			this.establishedStreamSessionIds.add(sessionId);
		}

		@Override
		public void didTerminateMcpSession(Class<? extends McpEndpoint> endpointClass,
																		 String sessionId,
																		 Duration sessionDuration,
																		 McpSessionTerminationReason terminationReason,
																		 Throwable throwable) {
			this.sessionTerminationReason = terminationReason;
		}

		@Override
		public void didTerminateMcpServerSentEventStream(Request request,
																										 Class<? extends McpEndpoint> endpointClass,
																										 String sessionId,
																										 Duration connectionDuration,
																										 McpStreamTerminationReason terminationReason,
																										 Throwable throwable) {
			this.streamTerminationReason = terminationReason;
		}
	}
}
