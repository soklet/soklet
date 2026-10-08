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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
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
	private static final Set<McpProtocolVersion> ALL_VERSIONS = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);

	@Test
	public void legacyPromptsUseExactCatalogsAndThePublicHandler() throws Exception {
		AtomicReference<McpRequestContext> observedRequest = new AtomicReference<>();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpPromptRegistration shared = McpPromptRegistration.withName(PROMPT_NAME, ALL_VERSIONS)
				.handler((requestContext, promptGetContext, invocationFeatures) -> {
					observedRequest.set(requestContext);
					handlerInvocations.incrementAndGet();
					Assertions.assertTrue(invocationFeatures.getProgressReporter().isEmpty());
					return McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
							.description("Rendered")
							.messages(List.of(McpPromptMessage.fromUserText("subject="
									+ promptGetContext.findArgument("subject").orElseThrow()
									+ ";tone=" + promptGetContext.findArgument("tone").orElse("<absent>")),
									McpPromptMessage.fromAssistantContent(McpImageContent
											.withDataAndMimeType(new byte[] { 1, 2, 3 }, "image/png").build()),
									McpPromptMessage.fromAssistantContent(McpAudioContent
											.withDataAndMimeType(new byte[] { 4, 5 }, "audio/wav").build()),
									McpPromptMessage.fromUserContent(McpEmbeddedResource.withResource(
											McpTextResourceContents.withUriAndText(
													URI.create("test://catalog/embedded"), "embedded").build()).build())))
							.build()).toBuilder()
							.metadata(McpJsonObject.builder().put("renderedBy", "test").build()).build();
				})
				.title("Compose")
				.description("Compose a prompt")
				.icons(List.of(McpIcon.withSource(URI.create("https://example.com/icon.png")).build()))
				.metadata(McpJsonObject.builder().put("owner", "catalog").build())
				.arguments(List.of(McpPromptArgumentDeclaration.withName("subject")
						.title("Subject").description("Subject to discuss").required(true).build(),
						McpPromptArgumentDeclaration.withName("tone").build())).build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion("legacy-prompts", "4.0.0").build(), ALL_VERSIONS)
				.promptRegistrations(List.of(shared,
						simplePrompt("june-only", Set.of(McpProtocolVersion.V2025_06_18)),
						simplePrompt("modern-only", Set.of(McpProtocolVersion.V2026_07_28))))
				.build();
		McpServer server = legacyServerBuilder(endpoint).build();
		try (Soklet owner = managedSoklet(server)) {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (McpProtocolVersion version : List.of(McpProtocolVersion.V2025_06_18,
					McpProtocolVersion.V2025_11_25)) {
				String revision = version.getWireValue();
				HttpResponse<String> initialize = sendLegacy(port, revision, "initialize", "init",
						"\"protocolVersion\":\"" + revision + "\",\"capabilities\":{\"sampling\":{}},"
								+ "\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1\"}", Map.of());
				assertSuccess(initialize, "init");
				assertContains(initialize.body(), "\"protocolVersion\":\"" + revision + "\"");
				assertContains(initialize.body(), "\"capabilities\":{\"prompts\":{}}");
				Assertions.assertFalse(initialize.body().contains("listChanged"), initialize.body());
				Assertions.assertTrue(initialize.headers().firstValue("Mcp-Session-Id").isEmpty());
				HttpResponse<String> list = sendLegacy(port, revision, "prompts/list", "list", "", Map.of());
				assertSuccess(list, "list");
				assertContains(list.body(), "\"title\":\"Compose\"");
				assertContains(list.body(), "\"name\":\"subject\"");
				assertContains(list.body(), "\"required\":true");
				assertContains(list.body(), "\"owner\":\"catalog\"");
				Assertions.assertEquals(version == McpProtocolVersion.V2025_11_25,
						list.body().contains("\"icons\""), list.body());
				Assertions.assertEquals(version == McpProtocolVersion.V2025_06_18,
						list.body().contains("june-only"), list.body());
				Assertions.assertFalse(list.body().contains("modern-only"), list.body());
				assertLegacyResult(list);
				HttpResponse<String> get = sendLegacy(port, revision, "prompts/get", "get",
						"\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{\"subject\":\" exact \"}",
						Map.of("Mcp-Method", "prompts/get", "Mcp-Name", PROMPT_NAME));
				assertSuccess(get, "get");
				assertContains(get.body(), "\"text\":\"subject= exact ;tone=<absent>\"");
				assertContains(get.body(), "\"type\":\"image\"");
				assertContains(get.body(), "\"data\":\"AQID\"");
				assertContains(get.body(), "\"type\":\"audio\"");
				assertContains(get.body(), "\"type\":\"resource\"");
				assertContains(get.body(), "\"renderedBy\":\"test\"");
				assertLegacyResult(get);
				Assertions.assertSame(endpoint, observedRequest.get().getEndpoint());
				Assertions.assertEquals(version, observedRequest.get().getProtocolVersion());
				Assertions.assertTrue(observedRequest.get().getClientInfo().isEmpty());
				Assertions.assertEquals(McpJsonObject.emptyInstance(), observedRequest.get().getClientCapabilities().toJson());
				for (String parameters : List.of(
						"\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{}",
						"\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{\"subject\":42}",
						"\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{\"subject\":\"x\",\"typo\":\"y\"}",
						"\"name\":\"modern-only\"", "\"name\":\"absent\""))
					assertError(sendLegacy(port, revision, "prompts/get", "invalid", parameters, Map.of()),
							200, -32602, "invalid");
				assertError(sendLegacy(port, revision, "prompts/list", "cursor", "\"cursor\":\"x\"", Map.of()),
						200, -32602, "cursor");
				assertError(sendLegacy(port, revision, "completion/complete", "completion", "", Map.of()),
						200, -32601, "completion");
			}
			Assertions.assertEquals(2, handlerInvocations.get());
			HttpResponse<String> modern = send(port, request("modern", "prompts/get",
					",\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{\"subject\":\"modern\"}"),
					"prompts/get", PROMPT_NAME);
			assertSuccess(modern, "modern");
			assertContains(modern.body(), "\"resultType\":\"complete\"");
			Assertions.assertEquals(McpProtocolVersion.V2026_07_28, observedRequest.get().getProtocolVersion());
		}
	}

	@Test
	public void legacyPromptPolicyIsRecheckedBeforeEveryGetAndUsesTheSharedPipeline() throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		AtomicBoolean allowed = new AtomicBoolean(true);
		AtomicBoolean admitted = new AtomicBoolean(true);
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpPromptRegistration prompt = McpPromptRegistration.withName(PROMPT_NAME, ALL_VERSIONS)
				.handler((requestContext, promptGetContext, invocationFeatures) -> {
					stages.add("handler");
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages(
							McpPromptMessage.fromUserText("private prompt")));
				}).title("Canonical title").build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion("prompt-policy", "4.0.0").build(), ALL_VERSIONS)
				.promptRegistrations(List.of(prompt)).build();
		McpServer server = legacyServerBuilder(endpoint)
				.admissionController(admissionContext -> {
					stages.add("admission");
					return admitted.get() ? McpAdmissionDecision.accepted() : McpAdmissionDecision.rejected(
							McpAdmissionRejection.withStatusCodeAndError(403,
									McpJsonRpcError.fromApplication(-31903, "Denied")).build());
				})
				.requestRateLimiter(rateLimitContext -> {
					stages.add("request-limiter");
					return McpRateLimitDecision.allowed();
				})
				.catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators(
						(requestContext, registration, invocationFeatures) -> true,
						(requestContext, registration, invocationFeatures) -> {
							Assertions.assertSame(prompt, registration);
							stages.add("policy");
							return allowed.get();
						}))
				.localizer(McpLocalizer.withFallbackLocale(Locale.ENGLISH, localizationRequest ->
						McpLocalizationContext.withLocale(Locale.FRENCH, text ->
								McpLocalizationResult.localized("FR:" + text.getDefaultText())).build()).build())
				.handlerInterceptor((requestContext, invocationFeatures, continuation) -> {
					stages.add("interceptor-before");
					McpOperationResult result = continuation.proceed();
					stages.add("interceptor-after");
					return result;
				}).build();
		try (Soklet owner = managedSoklet(server)) {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String revision : List.of("2025-06-18", "2025-11-25")) {
				allowed.set(true);
				admitted.set(true);
				stages.clear();
				HttpResponse<String> list = sendLegacy(port, revision, "prompts/list", "list", "", Map.of());
				assertSuccess(list, "list");
				assertContains(list.body(), "\"title\":\"FR:Canonical title\"");
				Assertions.assertEquals(List.of("admission", "request-limiter", "policy"), stages);
				String parameters = "\"name\":\"" + PROMPT_NAME + "\"";
				stages.clear();
				assertSuccess(sendLegacy(port, revision, "prompts/get", "get", parameters, Map.of()), "get");
				Assertions.assertEquals(List.of("admission", "request-limiter", "policy",
						"interceptor-before", "handler", "interceptor-after"), stages);
				allowed.set(false);
				stages.clear();
				HttpResponse<String> hidden = sendLegacy(port, revision, "prompts/get", "hidden", parameters, Map.of());
				assertError(hidden, 200, -32602, "hidden");
				Assertions.assertEquals(List.of("admission", "request-limiter", "policy"), stages);
				HttpResponse<String> absent = sendLegacy(port, revision, "prompts/get", "hidden",
						"\"name\":\"absent\"", Map.of());
				Assertions.assertEquals(absent.body(), hidden.body());
				HttpResponse<String> emptyList = sendLegacy(port, revision, "prompts/list", "empty", "", Map.of());
				assertSuccess(emptyList, "empty");
				assertContains(emptyList.body(), "\"prompts\":[]");
				admitted.set(false);
				stages.clear();
				HttpResponse<String> denied = sendLegacy(port, revision, "prompts/get", "denied", parameters, Map.of());
				assertError(denied, 403, -31903, "denied");
				Assertions.assertEquals(List.of("admission"), stages);
				Assertions.assertFalse(denied.body().contains("private prompt"));
			}
			Assertions.assertEquals(2, handlerInvocations.get());
		}
	}

	@Test
	public void modernOnlyPromptsDoNotAdvertiseALegacyCapability() throws Exception {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion("modern-prompts", "4.0.0").build(), ALL_VERSIONS)
				.promptRegistrations(List.of(simplePrompt("modern-only", Set.of(McpProtocolVersion.V2026_07_28))))
				.build();
		McpServer server = legacyServerBuilder(endpoint).build();
		try (Soklet owner = managedSoklet(server)) {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String revision : List.of("2025-06-18", "2025-11-25")) {
				HttpResponse<String> initialize = sendLegacy(port, revision, "initialize", "init",
						"\"protocolVersion\":\"" + revision + "\",\"capabilities\":{},"
								+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}", Map.of());
				assertSuccess(initialize, "init");
				assertContains(initialize.body(), "\"capabilities\":{}");
				assertError(sendLegacy(port, revision, "prompts/list", "list", "", Map.of()),
						200, -32601, "list");
			}
		}
	}

	private static McpPromptRegistration simplePrompt(String name, Set<McpProtocolVersion> protocolVersions) {
		return McpPromptRegistration.withName(name, protocolVersions)
				.handler((requestContext, promptGetContext, invocationFeatures) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages()))
				.build();
	}

	private static McpServer.Builder legacyServerBuilder(McpEndpoint endpoint) {
		return McpServer.withPort(0).host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance()).allowedHosts(Set.of(LOOPBACK));
	}

	private static HttpResponse<String> sendLegacy(int port, String revision, String method,
			String id, String parameters, Map<String, String> headers) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE)
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream");
		if (!"initialize".equals(method))
			request.header("MCP-Protocol-Version", revision);
		headers.forEach(request::header);
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method
				+ "\",\"params\":{" + parameters + "}}";
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
				request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertLegacyResult(HttpResponse<String> response) {
		for (String modernField : List.of("resultType", "ttlMs", "cacheScope", "nextCursor"))
			Assertions.assertFalse(response.body().contains("\"" + modernField + "\""), response.body());
	}

	@Test
	public void promptCatalogAndGetUseThePublicPipeline() throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicReference<McpPromptGetContext> observedPrompt =
				new AtomicReference<>();
		AtomicReference<McpRequestContext> observedRequest =
				new AtomicReference<>();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName(PROMPT_NAME, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
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
							.messages(java.util.List.of(McpPromptMessage.fromUserContent(
									McpTextContent.fromText("subject="
											+ promptGet.findArgument("subject")
													.orElseThrow()
											+ ";tone="
											+ promptGet.findArgument("tone")
													.orElse("<absent>"))), McpPromptMessage.fromAssistantContent(
									McpImageContent.withDataAndMimeType(
											new byte[] { 1, 2, 3 }, "image/png")
											.build()), McpPromptMessage.fromAssistantContent(
									McpEmbeddedResource.withResource(resource).build())))
							.build()).toBuilder().metadata(McpJsonObject.builder()
							.put("renderedBy", "test").build()).build();
				})
				.title("Compose catalog prompt")
				.description("Builds a deterministic catalog prompt")
				.arguments(java.util.List.of(McpPromptArgumentDeclaration.withName("subject")
						.title("Subject")
						.description("Subject to discuss")
						.required(true)
						.build(), McpPromptArgumentDeclaration.withName("tone")
						.description("Optional tone")
						.build()))
				.metadata(McpJsonObject.builder().put("owner", "catalog").build())
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"prompt-public-runtime-test", "4.0.0").build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.promptRegistrations(java.util.List.of(prompt))
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).admissionController(context -> {
					stages.add("admission:"
							+ context.getOperationName().orElse("-"));
					return McpAdmissionDecision.accepted();
				})
				.host(LOOPBACK)
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
				Assertions.assertEquals(invalidParameters.contains("42") ? List.of()
						: List.of("admission:" + name), stages);
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
