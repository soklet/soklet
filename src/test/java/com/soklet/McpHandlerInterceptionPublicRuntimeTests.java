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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public MCP handler interception.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpHandlerInterceptionPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "interception.tool";
	private static final String PROMPT_NAME = "interception.prompt";
	private static final URI RESOURCE_URI = URI.create("test://interception/resource");

	@Test
	public void everyApplicationHandlerUsesOneInterceptorWhileCatalogsBypassIt()
			throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		Map<String, McpRequestContext> interceptorContexts =
				new ConcurrentHashMap<>();
		Map<String, McpInvocationFeatures> interceptorFeatures =
				new ConcurrentHashMap<>();
		AtomicReference<McpEndpoint> expectedEndpoint = new AtomicReference<>();
		AtomicInteger interceptorInvocations = new AtomicInteger();

		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((request, call, features) -> {
					Assertions.assertSame(interceptorContexts.get("tools/call"),
							request);
					Assertions.assertSame(interceptorFeatures.get("tools/call"),
							features);
					stages.add("handler:tools/call");
					return McpCompleteResult.fromToolText("tool-original");
				})
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName(PROMPT_NAME)
				.handler((request, promptGet, features) -> {
					Assertions.assertSame(interceptorContexts.get("prompts/get"),
							request);
					Assertions.assertSame(interceptorFeatures.get("prompts/get"),
							features);
					stages.add("handler:prompts/get");
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages(
									McpPromptMessage.fromUserContent(
											McpTextContent.fromText("prompt-original"))));
				})
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "Intercepted resource")
				.handler((request, read, features) -> {
					Assertions.assertSame(interceptorContexts.get("resources/read"),
							request);
					Assertions.assertSame(interceptorFeatures.get("resources/read"),
							features);
					stages.add("handler:resources/read");
					return completeText(read.getUri(), "resource-original");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"handler-interception-runtime-test", "3.6.0-SNAPSHOT")
						.build())
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.resourceListHandler((request, list, features) -> {
					Assertions.assertSame(interceptorContexts.get("resources/list"),
							request);
					Assertions.assertSame(interceptorFeatures.get("resources/list"),
							features);
					stages.add("handler:resources/list");
					return McpResourcePage.builder()
							.resources(list.getRegisteredResourceDescriptors())
							.build();
				})
				.build();
		expectedEndpoint.set(endpoint);
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, invocation) -> {
					interceptorInvocations.incrementAndGet();
					Assertions.assertSame(expectedEndpoint.get(), context.getEndpoint());
					Assertions.assertTrue(
							context.getEndpointPathParameters().isEmpty());
					String method = context.getJsonRpcMethod();
					Optional<String> expectedOperation = switch (method) {
						case "tools/call" -> Optional.of(TOOL_NAME);
						case "prompts/get" -> Optional.of(PROMPT_NAME);
						case "resources/read" -> Optional.of(RESOURCE_URI.toString());
						case "resources/list" -> Optional.empty();
						default -> throw new AssertionError(
								"Unexpected intercepted method: " + method);
					};
					Assertions.assertEquals(expectedOperation,
							context.getOperationName());
					interceptorContexts.put(method, context);
					McpInvocationFeatures features = invocation.getFeatures();
					Assertions.assertSame(features, invocation.getFeatures());
					interceptorFeatures.put(method, features);
					stages.add("before:" + method);
					McpOperationResult result = invocation.invoke();
					stages.add("after:" + method);
					if (method.equals("tools/call"))
						return McpCompleteResult.fromToolText("tool-transformed");
					return result;
				})
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();

			for (String method : List.of("server/discover", "tools/list",
					"prompts/list", "resources/templates/list")) {
				HttpResponse<String> catalog = send(port,
						request("catalog-" + method, method, ""), method);
				assertSuccess(catalog, "catalog-" + method);
			}
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertTrue(stages.isEmpty(), stages.toString());

			HttpResponse<String> toolCall = send(port,
					request("tool", "tools/call", ",\"name\":\""
							+ TOOL_NAME + "\",\"arguments\":{}"),
					"tools/call", TOOL_NAME);
			assertSuccess(toolCall, "tool");
			assertContains(toolCall.body(), "\"text\":\"tool-transformed\"");
			Assertions.assertFalse(toolCall.body().contains("tool-original"),
					toolCall.body());

			HttpResponse<String> promptGet = send(port,
					request("prompt", "prompts/get", ",\"name\":\""
							+ PROMPT_NAME + "\",\"arguments\":{}"),
					"prompts/get", PROMPT_NAME);
			assertSuccess(promptGet, "prompt");
			assertContains(promptGet.body(), "prompt-original");

			HttpResponse<String> resourceRead = send(port,
					request("resource", "resources/read", ",\"uri\":\""
							+ RESOURCE_URI + "\""),
					"resources/read", RESOURCE_URI.toString());
			assertSuccess(resourceRead, "resource");
			assertContains(resourceRead.body(), "resource-original");

			HttpResponse<String> resourceList = send(port,
					request("resource-list", "resources/list", ""),
					"resources/list");
			assertSuccess(resourceList, "resource-list");
			assertContains(resourceList.body(),
					"\"uri\":\"" + RESOURCE_URI + "\"");

			Assertions.assertEquals(4, interceptorInvocations.get());
			Assertions.assertEquals(List.of(
					"before:tools/call", "handler:tools/call", "after:tools/call",
					"before:prompts/get", "handler:prompts/get", "after:prompts/get",
					"before:resources/read", "handler:resources/read",
					"after:resources/read", "before:resources/list",
					"handler:resources/list", "after:resources/list"), stages);
		} finally {
			server.stop();
		}
	}

	@Test
	public void interceptorMayShortCircuitBeforeBindingAndFailuresFailClosed()
			throws Exception {
		AtomicInteger shortCircuitHandlerInvocations = new AtomicInteger();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"handler-interception-failure-test", "3.6.0-SNAPSHOT")
						.build())
				.tool(McpToolRegistration.withName("short-circuit")
						.argumentType(RequiredArguments.class)
						.handler((request, call, features) -> {
							shortCircuitHandlerInvocations.incrementAndGet();
							return McpCompleteResult.fromToolText("must-not-run");
						})
						.build())
				.tool(rawTool("wrong-result"))
				.tool(rawTool("null-result"))
				.tool(rawTool("throwing"))
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, invocation) -> switch (
						context.getOperationName().orElseThrow()) {
					case "short-circuit" -> {
						McpInvocationFeatures features = invocation.getFeatures();
						Assertions.assertSame(features, invocation.getFeatures());
						yield McpCompleteResult.fromToolText("short-circuited");
					}
					case "wrong-result" -> McpResourcePage.builder().build();
					case "null-result" -> null;
					case "throwing" -> throw new IllegalStateException(
							"interceptor-secret-must-not-leak");
					default -> invocation.invoke();
				})
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();

			HttpResponse<String> shortCircuit = callTool(port, "short",
					"short-circuit", "{}");
			assertSuccess(shortCircuit, "short");
			assertContains(shortCircuit.body(), "short-circuited");
			Assertions.assertEquals(0, shortCircuitHandlerInvocations.get(),
					"Short-circuiting must bypass typed input binding and the handler.");

			for (String toolName : List.of("wrong-result", "null-result",
					"throwing")) {
				HttpResponse<String> failure = callTool(port, toolName, toolName,
						"{}");
				assertInternalError(failure, toolName);
				Assertions.assertFalse(failure.body().contains("secret"),
						failure.body());
			}
		} finally {
			server.stop();
		}
	}

	@Test
	public void staticResourceListHasNoApplicationHandlerToIntercept()
			throws Exception {
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"static-resource-list-interception-test",
						"3.6.0-SNAPSHOT").build())
				.resource(McpResourceRegistration
						.withUriAndName(RESOURCE_URI, "Static resource")
						.handler((request, read, features) ->
								completeText(read.getUri(), "not-read"))
						.build())
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, invocation) -> {
					interceptorInvocations.incrementAndGet();
					return invocation.invoke();
				})
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					request("static-list", "resources/list", ""),
					"resources/list");

			assertSuccess(response, "static-list");
			assertContains(response.body(),
					"\"uri\":\"" + RESOURCE_URI + "\"");
			Assertions.assertEquals(0, interceptorInvocations.get());
		} finally {
			server.stop();
		}
	}

	@Test
	public void interceptorJsonRpcExceptionsFailClosedForResourceOperations()
			throws Exception {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"resource-interceptor-error-test",
						"3.6.0-SNAPSHOT").build())
				.resource(McpResourceRegistration
						.withUriAndName(RESOURCE_URI, "Resource")
						.handler((request, read, features) ->
								completeText(read.getUri(), "must-not-run"))
						.build())
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, invocation) -> {
					throw new McpJsonRpcException(McpJsonRpcError.fromApplication(
							1_001, "interceptor-secret-must-not-leak"));
				})
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> read = send(port,
					request("resource-error", "resources/read", ",\"uri\":\""
							+ RESOURCE_URI + "\""),
					"resources/read", RESOURCE_URI.toString());
			HttpResponse<String> list = send(port,
					request("list-error", "resources/list", ""),
					"resources/list");

			assertInternalError(read, "resource-error");
			assertInternalError(list, "list-error");
			Assertions.assertFalse(read.body().contains("interceptor-secret"),
					read.body());
			Assertions.assertFalse(list.body().contains("interceptor-secret"),
					list.body());
		} finally {
			server.stop();
		}
	}

	@Test
	public void continuationIsOneShotThreadBoundAndCallScoped() throws Exception {
		Map<String, AtomicInteger> handlerInvocations = new ConcurrentHashMap<>();
		for (String toolName : List.of("one-shot", "wrong-thread", "retained"))
			handlerInvocations.put(toolName, new AtomicInteger());
		AtomicReference<McpHandlerInvocation> retainedInvocation =
				new AtomicReference<>();
		AtomicReference<Throwable> wrongThreadFeatureFailure =
				new AtomicReference<>();
		AtomicReference<Throwable> wrongThreadInvocationFailure =
				new AtomicReference<>();

		McpEndpoint.Builder endpointBuilder = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"handler-continuation-runtime-test", "3.6.0-SNAPSHOT")
						.build());
		for (String toolName : handlerInvocations.keySet()) {
			endpointBuilder.tool(McpToolRegistration.withName(toolName)
					.jsonArguments()
					.handler((request, call, features) -> {
						handlerInvocations.get(toolName).incrementAndGet();
						return McpCompleteResult.fromToolText(toolName + "-handled");
					})
					.build());
		}
		McpEndpoint endpoint = endpointBuilder.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, invocation) -> switch (
						context.getOperationName().orElseThrow()) {
					case "one-shot" -> {
						McpInvocationFeatures features = invocation.getFeatures();
						Assertions.assertSame(features, invocation.getFeatures());
						McpOperationResult result = invocation.invoke();
						Assertions.assertThrows(IllegalStateException.class,
								invocation::invoke);
						yield result;
					}
					case "wrong-thread" -> {
						Thread thread = new Thread(() -> {
							try {
								invocation.getFeatures();
							} catch (Throwable throwable) {
								wrongThreadFeatureFailure.set(throwable);
							}
							try {
								invocation.invoke();
							} catch (Throwable throwable) {
								wrongThreadInvocationFailure.set(throwable);
							}
						}, "mcp-handler-interceptor-wrong-thread-test");
						thread.start();
						thread.join(TimeUnit.SECONDS.toMillis(5));
						Assertions.assertFalse(thread.isAlive());
						Assertions.assertInstanceOf(IllegalStateException.class,
								wrongThreadFeatureFailure.get());
						Assertions.assertInstanceOf(IllegalStateException.class,
								wrongThreadInvocationFailure.get());
						Assertions.assertEquals(0,
								handlerInvocations.get("wrong-thread").get());
						yield invocation.invoke();
					}
					case "retained" -> {
						retainedInvocation.set(invocation);
						yield invocation.invoke();
					}
					default -> throw new AssertionError("Unexpected tool");
				})
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			for (String toolName : List.of("one-shot", "wrong-thread", "retained")) {
				HttpResponse<String> response = callTool(port, toolName, toolName,
						"{}");
				assertSuccess(response, toolName);
				assertContains(response.body(), toolName + "-handled");
			}

			for (AtomicInteger count : handlerInvocations.values())
				Assertions.assertEquals(1, count.get());
			Assertions.assertThrows(IllegalStateException.class,
					() -> retainedInvocation.get().invoke());
			Assertions.assertThrows(IllegalStateException.class,
					() -> retainedInvocation.get().getFeatures());
			Assertions.assertEquals(1,
					handlerInvocations.get("retained").get());
		} finally {
			server.stop();
		}
	}

	@Test
	public void deadlinePreventsLatePublicHandlerEntry() throws Exception {
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicReference<Exception> lateContinuationFailure =
				new AtomicReference<>();
		CountDownLatch lateContinuationCompleted = new CountDownLatch(1);
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"handler-interception-deadline-test", "3.6.0-SNAPSHOT")
						.build())
				.tool(McpToolRegistration.withName("late")
						.jsonArguments()
						.handler((request, call, features) -> {
							handlerInvocations.incrementAndGet();
							return McpCompleteResult.fromToolText("too-late");
						})
						.build())
				.build();
		McpServer server = serverBuilder(endpoint)
				.requestTimeout(Duration.ofMillis(50))
				.handlerInterceptor((context, invocation) -> {
					interceptorInvocations.incrementAndGet();
					long finish = System.nanoTime()
							+ TimeUnit.MILLISECONDS.toNanos(250);
					while (System.nanoTime() - finish < 0L) {
						try {
							Thread.sleep(10);
						} catch (InterruptedException ignored) {
							// Deliberately test a noncooperative interceptor.
						}
					}
					try {
						return invocation.invoke();
					} catch (Exception exception) {
						lateContinuationFailure.set(exception);
						throw exception;
					} finally {
						lateContinuationCompleted.countDown();
					}
				})
				.build();

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = callTool(port, "late", "late", "{}");

			Assertions.assertEquals(504, response.statusCode(), response.body());
			Assertions.assertTrue(response.body().isEmpty(), response.body());
			Assertions.assertTrue(
					response.headers().firstValue("Content-Type").isEmpty());
			Assertions.assertEquals(1, interceptorInvocations.get());
			Assertions.assertTrue(lateContinuationCompleted.await(5,
					TimeUnit.SECONDS),
					"The noncooperative interceptor did not attempt late continuation.");
			Assertions.assertInstanceOf(InterruptedException.class,
					lateContinuationFailure.get());
			Assertions.assertEquals(0, handlerInvocations.get(),
					"An expired request must not enter the public handler.");
		} finally {
			server.stop();
		}
	}

	private static McpToolRegistration<McpJsonObject> rawTool(String name) {
		return McpToolRegistration.withName(name)
				.jsonArguments()
				.handler((request, call, features) ->
						McpCompleteResult.fromToolText(name + "-handled"))
				.build();
	}

	private static McpCompleteResult completeText(URI uri, String text) {
		return McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
				.content(McpTextResourceContents.withUriAndText(uri, text)
						.mimeType("text/plain")
						.build())
				.build());
	}

	private static McpServer.Builder serverBuilder(McpEndpoint endpoint) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static HttpResponse<String> callTool(int port, String id,
			String toolName, String arguments) throws Exception {
		return send(port, request(id, "tools/call", ",\"name\":\""
				+ toolName + "\",\"arguments\":" + arguments),
				"tools/call", toolName);
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
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request.POST(HttpRequest.BodyPublishers.ofString(
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
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
	}

	private static void assertInternalError(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(500, response.statusCode(), response.body());
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ expectedId
				+ "\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}",
				response.body());
		Assertions.assertFalse(response.body().contains("\"data\""),
				response.body());
	}

	private static void assertContains(String text, String expected) {
		Assertions.assertTrue(text.contains(expected), text);
	}

	private record RequiredArguments(String required) {
	}
}
