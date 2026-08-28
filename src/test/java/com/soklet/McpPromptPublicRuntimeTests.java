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
 * Black-box real-listener coverage for public MCP prompt registrations.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpPromptPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String PROMPT_NAME = "catalog.compose";

	@Test
	public void promptCatalogAndGetUseThePublicPipeline() throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicReference<McpPromptGetContext> observedPrompt =
				new AtomicReference<>();
		AtomicReference<McpRequestContext> observedRequest =
				new AtomicReference<>();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName(PROMPT_NAME)
				.handler((request, promptGet, features) -> {
					stages.add("handler:" + PROMPT_NAME);
					handlerInvocations.incrementAndGet();
					observedRequest.set(request);
					observedPrompt.set(promptGet);
					McpTextResourceContents resource = McpTextResourceContents
							.withUriAndText(URI.create("test://example-resource"),
									"embedded text")
							.mimeType("text/plain")
							.build();
					return McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
							.description("Rendered prompt")
							.message(McpPromptMessage.fromUserContent(
									McpTextContent.fromText("subject="
											+ promptGet.findArgument("subject")
													.orElseThrow()
											+ ";tone="
											+ promptGet.findArgument("tone")
													.orElse("<absent>"))))
							.message(McpPromptMessage.fromAssistantContent(
									McpImageContent.withDataAndMimeType(
											new byte[] { 1, 2, 3 }, "image/png")
											.build()))
							.message(McpPromptMessage.fromAssistantContent(
									McpEmbeddedResource.withResource(resource).build()))
							.build()).withMetadata(McpJsonObject.builder()
							.put("renderedBy", "test").build());
				})
				.title("Compose catalog prompt")
				.description("Builds a deterministic catalog prompt")
				.argument(McpPromptArgumentDefinition.withName("subject")
						.title("Subject")
						.description("Subject to discuss")
						.required(true)
						.build())
				.argument(McpPromptArgumentDefinition.withName("tone")
						.description("Optional tone")
						.build())
				.metadata(McpJsonObject.builder().put("owner", "catalog").build())
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"prompt-public-runtime-test", "4.0.0-SNAPSHOT").build())
				.prompt(prompt)
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> {
					stages.add("admission:"
							+ context.getOperationName().orElse("-"));
					return McpAdmissionDecision.accepted();
				})
				.requestRateLimiter(context -> {
					Assertions.assertEquals(McpRateLimitTarget.REQUEST,
							context.getTarget());
					stages.add("request:"
							+ context.getOperationName().orElse("-"));
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

			HttpResponse<String> discover = send(port,
					request("discover-1", "server/discover", ""),
					"server/discover");
			assertSuccess(discover, "discover-1");
			assertContains(discover.body(), "\"capabilities\":{\"prompts\":{}}");
			Assertions.assertFalse(discover.body().contains("listChanged"),
					discover.body());
			Assertions.assertEquals(List.of("admission:-", "request:-"), stages);

			stages.clear();
			HttpResponse<String> list = send(port,
					request("list-1", "prompts/list", ""), "prompts/list");
			assertSuccess(list, "list-1");
			String listBody = list.body();
			assertContains(listBody, "\"resultType\":\"complete\"");
			assertContains(listBody, "\"ttlMs\":0");
			assertContains(listBody, "\"cacheScope\":\"private\"");
			assertContains(listBody, "\"name\":\"" + PROMPT_NAME + "\"");
			assertContains(listBody, "\"title\":\"Compose catalog prompt\"");
			assertContains(listBody,
					"\"description\":\"Builds a deterministic catalog prompt\"");
			assertContains(listBody, "\"name\":\"subject\"");
			assertContains(listBody, "\"required\":true");
			assertContains(listBody, "\"name\":\"tone\"");
			assertContains(listBody, "\"owner\":\"catalog\"");
			Assertions.assertFalse(listBody.contains("\"nextCursor\""), listBody);
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(List.of("admission:-", "request:-"), stages);

			stages.clear();
			HttpResponse<String> cursor = send(port,
					request("list-cursor", "prompts/list", ",\"cursor\":\"\""),
					"prompts/list");
			assertError(cursor, 400, -32602, "list-cursor");
			Assertions.assertTrue(stages.isEmpty(), stages.toString());

			HttpResponse<String> get = send(port,
					request("get-1", "prompts/get", ",\"name\":\""
							+ PROMPT_NAME
							+ "\",\"arguments\":{\"subject\":\" exact \"}"),
					"prompts/get", PROMPT_NAME);
			assertSuccess(get, "get-1");
			String getBody = get.body();
			assertContains(getBody, "\"description\":\"Rendered prompt\"");
			assertContains(getBody, "\"role\":\"user\"");
			assertContains(getBody, "\"text\":\"subject= exact ;tone=<absent>\"");
			assertContains(getBody, "\"role\":\"assistant\"");
			assertContains(getBody, "\"type\":\"image\"");
			assertContains(getBody, "\"data\":\"AQID\"");
			assertContains(getBody, "\"mimeType\":\"image/png\"");
			assertContains(getBody, "\"type\":\"resource\"");
			assertContains(getBody, "\"uri\":\"test://example-resource\"");
			assertContains(getBody, "\"text\":\"embedded text\"");
			assertContains(getBody, "\"renderedBy\":\"test\"");
			Assertions.assertEquals(List.of("admission:" + PROMPT_NAME,
					"request:" + PROMPT_NAME, "handler:" + PROMPT_NAME), stages);
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(" exact ", observedPrompt.get()
					.findArgument("subject").orElseThrow());
			Assertions.assertTrue(observedPrompt.get().findArgument("tone").isEmpty());
			Assertions.assertEquals("prompts/get",
					observedRequest.get().getJsonRpcMethod());
			Assertions.assertSame(endpoint, observedRequest.get().getEndpoint());

			for (String invalidParameters : List.of(
					",\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{}",
					",\"name\":\"" + PROMPT_NAME
							+ "\",\"arguments\":{\"subject\":42}",
					",\"name\":\"" + PROMPT_NAME
							+ "\",\"arguments\":{\"subject\":\"ok\",\"typo\":\"x\"}",
					",\"name\":\"catalog.absent\",\"arguments\":{}")) {
				stages.clear();
				String name = invalidParameters.contains("catalog.absent")
						? "catalog.absent" : PROMPT_NAME;
				HttpResponse<String> invalid = send(port,
						request("invalid-" + name, "prompts/get",
								invalidParameters), "prompts/get", name);
				assertError(invalid, 400, -32602, "invalid-" + name);
				Assertions.assertTrue(stages.isEmpty(), stages.toString());
			}
			Assertions.assertEquals(1, handlerInvocations.get());
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
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.build().send(request.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8)).build(),
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
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertError(HttpResponse<String> response, int status,
			int code, String expectedId) {
		Assertions.assertEquals(status, response.statusCode(), response.body());
		assertContains(response.body(), "\"code\":" + code);
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertContains(String actual, String expected) {
		Assertions.assertTrue(actual.contains(expected), () ->
				"Expected <" + actual + "> to contain <" + expected + ">.");
	}
}
