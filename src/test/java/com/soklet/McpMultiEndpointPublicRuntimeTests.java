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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public multi-endpoint MCP registration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpMultiEndpointPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String FIRST_PATH = "/mcp/first";
	private static final String SECOND_PATH = "/mcp/second";
	private static final String UNKNOWN_PATH = "/mcp/unknown";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "shared.echo";
	private static final String PROMPT_NAME = "shared.prompt";
	@NonNull
	private static final URI RESOURCE_URI = URI.create("test://shared-resource");

	@Test
	public void fixedPathsIsolateDiscoveryAndSameNamedTools() throws Exception {
		AtomicInteger firstHandlerInvocations = new AtomicInteger();
		AtomicInteger secondHandlerInvocations = new AtomicInteger();
		AtomicReference<McpEndpoint> firstObservedEndpoint = new AtomicReference<>();
		AtomicReference<McpEndpoint> secondObservedEndpoint = new AtomicReference<>();

		McpToolRegistration<McpJsonObject> firstTool = tool(
				"First shared tool", "Tool registered only on the first endpoint",
				"first-result", firstHandlerInvocations, firstObservedEndpoint);
		McpToolRegistration<McpJsonObject> secondTool = tool(
				"Second shared tool", "Tool registered only on the second endpoint",
				"second-result", secondHandlerInvocations, secondObservedEndpoint);
		McpEndpoint firstEndpoint = McpEndpoint.withPath(FIRST_PATH, McpImplementation.withNameAndVersion(
						"multi-endpoint-first", "1.0").build())
				.instructions("Instructions for the first endpoint.")
				.addTool(firstTool)
				.build();
		McpEndpoint secondEndpoint = McpEndpoint.withPath(SECOND_PATH, McpImplementation.withNameAndVersion(
						"multi-endpoint-second", "2.0").build())
				.instructions("Instructions for the second endpoint.")
				.addTool(secondTool)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(firstEndpoint, secondEndpoint)))
				.host(LOOPBACK)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();

			HttpResponse<String> firstDiscovery = send(port, FIRST_PATH,
					request("first-discovery", "server/discover", ""),
					"server/discover");
			assertSuccess(firstDiscovery, "first-discovery");
			assertContains(firstDiscovery.body(),
					"\"name\":\"multi-endpoint-first\"");
			assertContains(firstDiscovery.body(), "\"version\":\"1.0\"");
			assertContains(firstDiscovery.body(),
					"\"instructions\":\"Instructions for the first endpoint.\"");
			assertNotContains(firstDiscovery.body(), "multi-endpoint-second");

			HttpResponse<String> secondDiscovery = send(port, SECOND_PATH,
					request("second-discovery", "server/discover", ""),
					"server/discover");
			assertSuccess(secondDiscovery, "second-discovery");
			assertContains(secondDiscovery.body(),
					"\"name\":\"multi-endpoint-second\"");
			assertContains(secondDiscovery.body(), "\"version\":\"2.0\"");
			assertContains(secondDiscovery.body(),
					"\"instructions\":\"Instructions for the second endpoint.\"");
			assertNotContains(secondDiscovery.body(), "multi-endpoint-first");

			HttpResponse<String> firstTools = send(port, FIRST_PATH,
					request("first-tools", "tools/list", ""), "tools/list");
			assertSuccess(firstTools, "first-tools");
			assertContains(firstTools.body(), "\"name\":\"" + TOOL_NAME + "\"");
			assertContains(firstTools.body(), "\"title\":\"First shared tool\"");
			assertNotContains(firstTools.body(), "Second shared tool");

			HttpResponse<String> secondTools = send(port, SECOND_PATH,
					request("second-tools", "tools/list", ""), "tools/list");
			assertSuccess(secondTools, "second-tools");
			assertContains(secondTools.body(), "\"name\":\"" + TOOL_NAME + "\"");
			assertContains(secondTools.body(), "\"title\":\"Second shared tool\"");
			assertNotContains(secondTools.body(), "First shared tool");

			HttpResponse<String> firstCall = send(port, FIRST_PATH,
					request("first-call", "tools/call", ",\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{}"),
					"tools/call", TOOL_NAME);
			assertSuccess(firstCall, "first-call");
			assertContains(firstCall.body(), "\"text\":\"first-result\"");
			assertNotContains(firstCall.body(), "second-result");

			HttpResponse<String> secondCall = send(port, SECOND_PATH,
					request("second-call", "tools/call", ",\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{}"),
					"tools/call", TOOL_NAME);
			assertSuccess(secondCall, "second-call");
			assertContains(secondCall.body(), "\"text\":\"second-result\"");
			assertNotContains(secondCall.body(), "first-result");

			HttpResponse<String> unknown = send(port, UNKNOWN_PATH,
					request("unknown-call", "tools/call", ",\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{}"),
					"tools/call", TOOL_NAME);
			Assertions.assertEquals(404, unknown.statusCode(), unknown.body());
			Assertions.assertEquals("", unknown.body());

			Assertions.assertEquals(1, firstHandlerInvocations.get());
			Assertions.assertEquals(1, secondHandlerInvocations.get());
			Assertions.assertSame(firstEndpoint, firstObservedEndpoint.get());
			Assertions.assertSame(secondEndpoint, secondObservedEndpoint.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void selectedEndpointReachesAdmissionAndRateLimitHooks() throws Exception {
		List<McpEndpoint> admittedEndpoints = new CopyOnWriteArrayList<>();
		List<McpEndpoint> requestLimitedEndpoints = new CopyOnWriteArrayList<>();
		List<McpEndpoint> toolLimitedEndpoints = new CopyOnWriteArrayList<>();
		McpEndpoint firstEndpoint = McpEndpoint.withPath(FIRST_PATH, McpImplementation.withNameAndVersion(
						"multi-endpoint-policy-first", "1.0").build())
				.addTool(tool("First policy tool", "First policy tool",
						"first-policy-result", new AtomicInteger(),
						new AtomicReference<>()))
				.build();
		McpEndpoint secondEndpoint = McpEndpoint.withPath(SECOND_PATH, McpImplementation.withNameAndVersion(
						"multi-endpoint-policy-second", "1.0").build())
				.addTool(tool("Second policy tool", "Second policy tool",
						"second-policy-result", new AtomicInteger(),
						new AtomicReference<>()))
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(firstEndpoint, secondEndpoint))).admissionController(context -> {
					admittedEndpoints.add(context.getEndpoint());
					return McpAdmissionDecision.accepted();
				})
				.host(LOOPBACK)
				.requestRateLimiter(context -> {
					requestLimitedEndpoints.add(context.getEndpoint());
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					toolLimitedEndpoints.add(context.getEndpoint());
					return McpRateLimitDecision.allowed();
				})
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			assertSuccess(send(port, FIRST_PATH,
					request("policy-first", "tools/call", ",\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{}"),
					"tools/call", TOOL_NAME), "policy-first");
			assertSuccess(send(port, SECOND_PATH,
					request("policy-second", "tools/call", ",\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{}"),
					"tools/call", TOOL_NAME), "policy-second");
			HttpResponse<String> unknown = send(port, UNKNOWN_PATH,
					request("policy-unknown", "tools/call", ",\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{}"),
					"tools/call", TOOL_NAME);
			Assertions.assertEquals(404, unknown.statusCode(), unknown.body());
			Assertions.assertEquals("", unknown.body());

			assertEndpointSequence(admittedEndpoints, firstEndpoint, secondEndpoint);
			assertEndpointSequence(requestLimitedEndpoints, firstEndpoint,
					secondEndpoint);
			assertEndpointSequence(toolLimitedEndpoints, firstEndpoint,
					secondEndpoint);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void sameNamedPromptsAndResourcesRemainEndpointLocal() throws Exception {
		AtomicReference<McpEndpoint> firstPromptEndpoint = new AtomicReference<>();
		AtomicReference<McpEndpoint> secondPromptEndpoint = new AtomicReference<>();
		AtomicReference<McpEndpoint> firstResourceEndpoint = new AtomicReference<>();
		AtomicReference<McpEndpoint> secondResourceEndpoint = new AtomicReference<>();
		McpPromptRegistration firstPrompt = prompt("First shared prompt",
				"first-prompt-result", firstPromptEndpoint);
		McpPromptRegistration secondPrompt = prompt("Second shared prompt",
				"second-prompt-result", secondPromptEndpoint);
		McpResourceRegistration firstResource = resource("First shared resource",
				"first-resource-result", firstResourceEndpoint);
		McpResourceRegistration secondResource = resource("Second shared resource",
				"second-resource-result", secondResourceEndpoint);
		McpEndpoint firstEndpoint = McpEndpoint.withPath(FIRST_PATH, McpImplementation.withNameAndVersion(
						"multi-capability-first", "1.0").build())
				.addPrompt(firstPrompt)
				.addResource(firstResource)
				.build();
		McpEndpoint secondEndpoint = McpEndpoint.withPath(SECOND_PATH, McpImplementation.withNameAndVersion(
						"multi-capability-second", "1.0").build())
				.addPrompt(secondPrompt)
				.addResource(secondResource)
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(firstEndpoint, secondEndpoint)))
				.host(LOOPBACK)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();

			HttpResponse<String> firstPrompts = send(port, FIRST_PATH,
					request("first-prompts", "prompts/list", ""), "prompts/list");
			assertSuccess(firstPrompts, "first-prompts");
			assertContains(firstPrompts.body(), "First shared prompt");
			assertNotContains(firstPrompts.body(), "Second shared prompt");
			HttpResponse<String> secondPrompts = send(port, SECOND_PATH,
					request("second-prompts", "prompts/list", ""), "prompts/list");
			assertSuccess(secondPrompts, "second-prompts");
			assertContains(secondPrompts.body(), "Second shared prompt");
			assertNotContains(secondPrompts.body(), "First shared prompt");

			HttpResponse<String> firstPromptGet = send(port, FIRST_PATH,
					request("first-prompt", "prompts/get", ",\"name\":\""
							+ PROMPT_NAME + "\""), "prompts/get", PROMPT_NAME);
			assertSuccess(firstPromptGet, "first-prompt");
			assertContains(firstPromptGet.body(), "first-prompt-result");
			assertNotContains(firstPromptGet.body(), "second-prompt-result");
			HttpResponse<String> secondPromptGet = send(port, SECOND_PATH,
					request("second-prompt", "prompts/get", ",\"name\":\""
							+ PROMPT_NAME + "\""), "prompts/get", PROMPT_NAME);
			assertSuccess(secondPromptGet, "second-prompt");
			assertContains(secondPromptGet.body(), "second-prompt-result");
			assertNotContains(secondPromptGet.body(), "first-prompt-result");

			HttpResponse<String> firstResources = send(port, FIRST_PATH,
					request("first-resources", "resources/list", ""),
					"resources/list");
			assertSuccess(firstResources, "first-resources");
			assertContains(firstResources.body(), "First shared resource");
			assertNotContains(firstResources.body(), "Second shared resource");
			HttpResponse<String> secondResources = send(port, SECOND_PATH,
					request("second-resources", "resources/list", ""),
					"resources/list");
			assertSuccess(secondResources, "second-resources");
			assertContains(secondResources.body(), "Second shared resource");
			assertNotContains(secondResources.body(), "First shared resource");

			HttpResponse<String> firstRead = send(port, FIRST_PATH,
					request("first-read", "resources/read", ",\"uri\":\""
							+ RESOURCE_URI + "\""), "resources/read",
					RESOURCE_URI.toString());
			assertSuccess(firstRead, "first-read");
			assertContains(firstRead.body(), "first-resource-result");
			assertNotContains(firstRead.body(), "second-resource-result");
			HttpResponse<String> secondRead = send(port, SECOND_PATH,
					request("second-read", "resources/read", ",\"uri\":\""
							+ RESOURCE_URI + "\""), "resources/read",
					RESOURCE_URI.toString());
			assertSuccess(secondRead, "second-read");
			assertContains(secondRead.body(), "second-resource-result");
			assertNotContains(secondRead.body(), "first-resource-result");

			Assertions.assertSame(firstEndpoint, firstPromptEndpoint.get());
			Assertions.assertSame(secondEndpoint, secondPromptEndpoint.get());
			Assertions.assertSame(firstEndpoint, firstResourceEndpoint.get());
			Assertions.assertSame(secondEndpoint, secondResourceEndpoint.get());
		} finally {
			soklet.close();
		}
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	@NonNull
	private static McpToolRegistration<McpJsonObject> tool(@NonNull String title,
			@NonNull String description, @NonNull String result,
			@NonNull AtomicInteger invocations,
			@NonNull AtomicReference<McpEndpoint> observedEndpoint) {
		return McpToolRegistration.withName(TOOL_NAME)
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					invocations.incrementAndGet();
					observedEndpoint.set(request.getEndpoint());
					return McpCompleteResult.fromToolText(result);
				})
				.title(title)
				.description(description)
				.build();
	}

	@NonNull
	private static McpPromptRegistration prompt(@NonNull String title,
			@NonNull String result,
			@NonNull AtomicReference<McpEndpoint> observedEndpoint) {
		return McpPromptRegistration.withName(PROMPT_NAME)
				.handler((request, prompt, features) -> {
					observedEndpoint.set(request.getEndpoint());
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages(
									McpPromptMessage.fromUserContent(
											McpTextContent.fromText(result))));
				})
				.title(title)
				.build();
	}

	@NonNull
	private static McpResourceRegistration resource(@NonNull String name,
			@NonNull String result,
			@NonNull AtomicReference<McpEndpoint> observedEndpoint) {
		return McpResourceRegistration.withUriAndName(RESOURCE_URI, name)
				.handler((request, resource, features) -> {
					observedEndpoint.set(request.getEndpoint());
					return McpCompleteResult.fromResourceOutput(
							McpResourceOutput.withContent(McpTextResourceContents
											.withUriAndText(resource.getUri(), result)
											.mimeType("text/plain")
											.build())
									.build());
				})
				.mimeType("text/plain")
				.build();
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String path,
			@NonNull String body, @NonNull String method) throws Exception {
		return send(port, path, body, method, Optional.empty());
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String path,
			@NonNull String body, @NonNull String method,
			@NonNull String operationName) throws Exception {
		return send(port, path, body, method, Optional.of(operationName));
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String path,
			@NonNull String body, @NonNull String method,
			@NonNull Optional<String> operationName) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + path))
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

	@NonNull
	private static String request(@NonNull String id, @NonNull String method,
			@NonNull String additionalParameters) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
	}

	private static void assertSuccess(@NonNull HttpResponse<String> response,
			@NonNull String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertContains(@NonNull String actual,
			@NonNull String expected) {
		Assertions.assertTrue(actual.contains(expected), () ->
				"Expected <" + actual + "> to contain <" + expected + ">.");
	}

	private static void assertNotContains(@NonNull String actual,
			@NonNull String unexpected) {
		Assertions.assertFalse(actual.contains(unexpected), () ->
				"Expected <" + actual + "> not to contain <" + unexpected + ">.");
	}

	private static void assertEndpointSequence(
			@NonNull List<@NonNull McpEndpoint> actual,
			@NonNull McpEndpoint first, @NonNull McpEndpoint second) {
		Assertions.assertEquals(2, actual.size());
		Assertions.assertSame(first, actual.get(0));
		Assertions.assertSame(second, actual.get(1));
	}

	@NonNull
	private static HttpClient httpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build();
	}
}
