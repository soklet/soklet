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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Black-box real-listener coverage for MCP tool-output sanitization.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpToolOutputSanitizerPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";

	@Test
	public void sanitizerRunsAfterInterceptionAndSanitizesShortCircuits()
			throws Exception {
		List<String> stages = new ArrayList<>();
		AtomicInteger shortCircuitedHandlerInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> ordinaryTool = McpToolRegistration
				.withName("ordinary")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					stages.add("handler:ordinary");
					return McpCompleteResult.fromToolText("UNSANITIZED-ORDINARY");
				})
				.build();
		McpToolRegistration<McpJsonObject> shortCircuitedTool = McpToolRegistration
				.withName("short-circuited")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					shortCircuitedHandlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("HANDLER-MUST-NOT-RUN");
				})
				.build();
		McpHandlerInterceptor interceptor = (context, continuation) -> {
			String operation = context.getOperationName().orElseThrow();
			stages.add("interceptor-before:" + operation);
			McpOperationResult result;
			if (operation.equals("short-circuited"))
				result = McpCompleteResult.fromToolText(
						"UNSANITIZED-SHORT-CIRCUIT");
			else
				result = continuation.proceed();
			stages.add("interceptor-after:" + operation);
			return result;
		};
		McpToolOutputSanitizer sanitizer = (request, toolName, rawArguments,
				output) -> {
			stages.add("sanitizer:" + toolName);
			return McpToolOutput.fromText("SANITIZED-" + toolName);
		};
		McpServer server = server(List.of(ordinaryTool, shortCircuitedTool),
				interceptor, sanitizer);

		try {
			server.start();
			int port = boundPort(server);

			HttpResponse<String> ordinary = call(port, "ordered-ordinary",
					"ordinary", "{}");
			assertSuccess(ordinary, "ordered-ordinary");
			assertContains(ordinary.body(), "SANITIZED-ordinary");
			Assertions.assertFalse(
					ordinary.body().contains("UNSANITIZED-ORDINARY"),
					ordinary.body());
			Assertions.assertEquals(List.of("interceptor-before:ordinary",
					"handler:ordinary", "interceptor-after:ordinary",
					"sanitizer:ordinary"), stages);

			stages.clear();
			HttpResponse<String> shortCircuited = call(port,
					"ordered-short-circuit", "short-circuited", "{}");
			assertSuccess(shortCircuited, "ordered-short-circuit");
			assertContains(shortCircuited.body(), "SANITIZED-short-circuited");
			Assertions.assertFalse(shortCircuited.body()
					.contains("UNSANITIZED-SHORT-CIRCUIT"),
					shortCircuited.body());
			Assertions.assertEquals(List.of(
					"interceptor-before:short-circuited",
					"interceptor-after:short-circuited",
					"sanitizer:short-circuited"), stages);
			Assertions.assertEquals(0,
					shortCircuitedHandlerInvocations.get());
		} finally {
			server.stop();
		}
	}

	@Test
	public void sanitizerNullAndExceptionsFailClosedWithoutOutputLeaks()
			throws Exception {
		assertSanitizerFailure("null-result",
				(request, toolName, rawArguments, output) -> null);
		assertSanitizerFailure("thrown-exception",
				(request, toolName, rawArguments, output) -> {
					throw new IllegalStateException(
							"SANITIZER-EXCEPTION-MUST-NOT-LEAK");
				});
	}

	@Test
	public void typedValidationUsesSanitizedOutputAndAllowsStructuredOmission()
			throws Exception {
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpToolRegistration<TypedArguments> tool = McpToolRegistration
				.withName("typed")
				.types(TypedArguments.class, TypedResult.class)
				.handler((request, arguments, features) ->
						new TypedResult("ORIGINAL-TYPED-VALUE"))
				.build();
		McpToolRegistration<TypedArguments> toolWithoutMirror = McpToolRegistration
				.withName("typed-without-mirror")
				.types(TypedArguments.class, TypedResult.class)
				.handler((request, arguments, features) ->
						new TypedResult("ORIGINAL-TYPED-VALUE"))
				.mirrorStructuredContentAsText(false)
				.build();
		McpToolOutputSanitizer sanitizer = (request, toolName, rawArguments,
				output) -> {
			sanitizerInvocations.incrementAndGet();
			McpJsonString mode = Assertions.assertInstanceOf(McpJsonString.class,
					rawArguments.find("mode").orElseThrow());
			McpJsonObject validReplacement = McpJsonObject.builder()
					.put("value", "SANITIZED-TYPED-VALUE")
					.build();
			McpJsonObject invalidReplacement = McpJsonObject.builder()
					.put("unexpected", "INVALID-SANITIZED-VALUE")
					.build();
			return switch (mode.getValue()) {
				case "valid-replacement" -> McpToolOutput
						.fromStructuredContent(validReplacement);
				case "valid-with-content" -> McpToolOutput.builder()
						.content(McpTextContent.fromText("SANITIZED-PRIMARY-CONTENT"))
						.structuredContent(validReplacement)
						.build();
				case "valid-error" -> McpToolOutput.builder()
						.structuredContent(validReplacement)
						.error(true)
						.build();
				case "omit-success" -> McpToolOutput
						.fromText("SANITIZED-OMITTED-SUCCESS");
				case "omit-error" -> McpToolOutput
						.fromErrorText("SANITIZED-OMITTED-ERROR");
				case "invalid-success" -> McpToolOutput.builder()
						.structuredContent(invalidReplacement)
						.build();
				case "invalid-error" -> McpToolOutput.builder()
						.structuredContent(invalidReplacement)
						.error(true)
						.build();
				default -> throw new IllegalArgumentException("Unknown test mode.");
			};
		};
		McpServer server = server(List.of(tool, toolWithoutMirror),
				McpHandlerInterceptor.passThroughInstance(), sanitizer);

		try {
			server.start();
			int port = boundPort(server);

			HttpResponse<String> validReplacement = call(port,
					"typed-valid-replacement", "typed",
					"{\"mode\":\"valid-replacement\"}");
			assertSuccess(validReplacement, "typed-valid-replacement");
			assertContains(validReplacement.body(),
					"\"structuredContent\":{\"value\":"
							+ "\"SANITIZED-TYPED-VALUE\"}");
			assertContains(validReplacement.body(),
					"\"text\":\"{\\\"value\\\":\\\"SANITIZED-TYPED-VALUE\\\"}\"");
			Assertions.assertFalse(validReplacement.body()
					.contains("ORIGINAL-TYPED-VALUE"), validReplacement.body());

			HttpResponse<String> validWithContent = call(port,
					"typed-valid-with-content", "typed",
					"{\"mode\":\"valid-with-content\"}");
			assertSuccess(validWithContent, "typed-valid-with-content");
			assertContains(validWithContent.body(), "SANITIZED-PRIMARY-CONTENT");
			assertContains(validWithContent.body(),
					"\"text\":\"{\\\"value\\\":\\\"SANITIZED-TYPED-VALUE\\\"}\"");

			HttpResponse<String> validError = call(port,
					"typed-valid-error", "typed",
					"{\"mode\":\"valid-error\"}");
			assertSuccess(validError, "typed-valid-error");
			assertContains(validError.body(), "\"isError\":true");
			assertContains(validError.body(),
					"\"text\":\"{\\\"value\\\":\\\"SANITIZED-TYPED-VALUE\\\"}\"");

			HttpResponse<String> disabledMirror = call(port,
					"typed-disabled-mirror", "typed-without-mirror",
					"{\"mode\":\"valid-with-content\"}");
			assertSuccess(disabledMirror, "typed-disabled-mirror");
			assertContains(disabledMirror.body(),
					"\"structuredContent\":{\"value\":"
							+ "\"SANITIZED-TYPED-VALUE\"}");
			Assertions.assertFalse(disabledMirror.body().contains(
					"\"text\":\"{\\\"value\\\":"), disabledMirror.body());

			HttpResponse<String> omittedSuccess = call(port,
					"typed-omit-success", "typed",
					"{\"mode\":\"omit-success\"}");
			assertSuccess(omittedSuccess, "typed-omit-success");
			assertContains(omittedSuccess.body(), "SANITIZED-OMITTED-SUCCESS");
			Assertions.assertFalse(
					omittedSuccess.body().contains("\"structuredContent\""),
					omittedSuccess.body());

			HttpResponse<String> omittedError = call(port,
					"typed-omit-error", "typed",
					"{\"mode\":\"omit-error\"}");
			assertSuccess(omittedError, "typed-omit-error");
			assertContains(omittedError.body(), "SANITIZED-OMITTED-ERROR");
			assertContains(omittedError.body(), "\"isError\":true");
			Assertions.assertFalse(
					omittedError.body().contains("\"structuredContent\""),
					omittedError.body());

			HttpResponse<String> invalidSuccess = call(port,
					"typed-invalid-success", "typed",
					"{\"mode\":\"invalid-success\"}");
			assertFixedInternalError(invalidSuccess,
					"typed-invalid-success");
			Assertions.assertFalse(invalidSuccess.body()
					.contains("INVALID-SANITIZED-VALUE"), invalidSuccess.body());

			HttpResponse<String> invalidError = call(port,
					"typed-invalid-error", "typed",
					"{\"mode\":\"invalid-error\"}");
			assertFixedInternalError(invalidError, "typed-invalid-error");
			Assertions.assertFalse(invalidError.body()
					.contains("INVALID-SANITIZED-VALUE"), invalidError.body());

			Assertions.assertEquals(8, sanitizerInvocations.get());
		} finally {
			server.stop();
		}
	}

	private static void assertSanitizerFailure(String suffix,
			McpToolOutputSanitizer sanitizer) throws Exception {
		String toolName = "sanitizer-failure-" + suffix;
		String handlerSecret = "HANDLER-OUTPUT-MUST-NOT-LEAK-" + suffix;
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(toolName)
				.jsonArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText(handlerSecret))
				.build();
		McpServer server = server(List.of(tool),
				McpHandlerInterceptor.passThroughInstance(), sanitizer);

		try {
			server.start();
			String requestId = "failure-" + suffix;
			HttpResponse<String> response = call(boundPort(server), requestId,
					toolName, "{}");
			assertFixedInternalError(response, requestId);
			Assertions.assertFalse(response.body().contains(handlerSecret),
					response.body());
			Assertions.assertFalse(response.body()
					.contains("SANITIZER-EXCEPTION-MUST-NOT-LEAK"),
					response.body());
		} finally {
			server.stop();
		}
	}

	private static McpServer server(List<McpToolRegistration<?>> tools,
			McpHandlerInterceptor interceptor,
			McpToolOutputSanitizer sanitizer) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"sanitizer-public-runtime-test",
						"4.0.0-SNAPSHOT").build())
				.tools(tools)
				.build();
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(
						McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(
						context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(
						context -> McpRateLimitDecision.allowed())
				.handlerInterceptor(interceptor)
				.toolOutputSanitizer(sanitizer)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static HttpResponse<String> call(int port, String requestId,
			String toolName, String arguments) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":"
				+ arguments + "}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", toolName)
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return httpClient().send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static HttpClient httpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build();
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

	private static void assertFixedInternalError(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(500, response.statusCode(), response.body());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
		assertContains(response.body(), "\"code\":-32603");
		assertContains(response.body(), "\"message\":\"Internal error\"");
		Assertions.assertFalse(response.body().contains("\"data\""),
				response.body());
	}

	private static void assertContains(String text, String expected) {
		Assertions.assertTrue(text.contains(expected), text);
	}

	private record TypedArguments(String mode) {
	}

	private record TypedResult(String value) {
	}
}
