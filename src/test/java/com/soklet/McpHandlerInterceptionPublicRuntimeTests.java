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
@Timeout(60)
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
				.withName(TOOL_NAME, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					Assertions.assertSame(interceptorContexts.get("tools/call"),
							request);
					Assertions.assertSame(interceptorFeatures.get("tools/call"),
							features);
					stages.add("handler:tools/call");
					return McpCompleteResult.fromToolText("tool-original");
				})
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName(PROMPT_NAME, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
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
				.withUriAndName(RESOURCE_URI, "Intercepted resource", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, read, features) -> {
					Assertions.assertSame(interceptorContexts.get("resources/read"),
							request);
					Assertions.assertSame(interceptorFeatures.get("resources/read"),
							features);
					stages.add("handler:resources/read");
					return completeText(read.getUri(), "resource-original");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"handler-interception-runtime-test", "4.0.0")
						.build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.toolRegistrations(java.util.List.of(tool))
				.promptRegistrations(java.util.List.of(prompt))
				.resourceRegistrations(java.util.List.of(resource))
				.resourceListHandler((request, list, features) -> {
					Assertions.assertSame(interceptorContexts.get("resources/list"),
							request);
					Assertions.assertSame(interceptorFeatures.get("resources/list"),
							features);
					stages.add("handler:resources/list");
					return McpResourcePage.builder()
							.resourceDescriptors(list.getRegisteredResourceDescriptors())
							.build();
				}, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.build();
		expectedEndpoint.set(endpoint);
		McpHandlerInterceptor innerInterceptor =
				(context, features, continuation) -> {
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
					interceptorFeatures.put(method, features);
					stages.add("before:" + method);
					McpOperationResult result = continuation.proceed();
					stages.add("after:" + method);
					if (method.equals("tools/call"))
						return McpCompleteResult.fromToolText("tool-transformed");
					return result;
				};
		McpHandlerInterceptor middleInterceptor =
				(context, features, continuation) -> {
					McpOperationResult result = innerInterceptor.interceptHandler(
							context, features, continuation);
					Assertions.assertSame(features,
							interceptorFeatures.get(context.getJsonRpcMethod()));
					return result;
				};
		McpHandlerInterceptor outerInterceptor =
				(context, features, continuation) -> {
					McpOperationResult result = middleInterceptor.interceptHandler(
							context, features, continuation);
					Assertions.assertSame(features,
							interceptorFeatures.get(context.getJsonRpcMethod()));
					return result;
				};
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor(outerInterceptor)
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
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
			owner.close();
		}
	}

	@Test
	public void interceptorMayShortCircuitBeforeBindingAndFailuresFailClosed()
			throws Exception {
		AtomicInteger shortCircuitHandlerInvocations = new AtomicInteger();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"handler-interception-failure-test", "4.0.0")
						.build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.toolRegistrations(java.util.List.of(McpToolRegistration.withName("short-circuit", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.argumentType(RequiredArguments.class)
						.handler((request, arguments, features) -> {
							shortCircuitHandlerInvocations.incrementAndGet();
							return McpCompleteResult.fromToolText("must-not-run");
						})
						.build(), rawTool("wrong-result"), rawTool("null-result"), rawTool("throwing")))
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, features, continuation) -> switch (
						context.getOperationName().orElseThrow()) {
					case "short-circuit" -> {
						yield McpCompleteResult.fromToolText("short-circuited");
					}
					case "wrong-result" -> McpResourcePage.builder().build();
					case "null-result" -> null;
					case "throwing" -> throw new IllegalStateException(
							"interceptor-secret-must-not-leak");
					default -> continuation.proceed();
				})
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
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
			owner.close();
		}
	}

	@Test
	public void staticResourceListHasNoApplicationHandlerToIntercept()
			throws Exception {
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"static-resource-list-interception-test",
						"4.0.0").build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.resourceRegistrations(java.util.List.of(McpResourceRegistration
						.withUriAndName(RESOURCE_URI, "Static resource", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler((request, read, features) ->
								completeText(read.getUri(), "not-read"))
						.build()))
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, features, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
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
			owner.close();
		}
	}

	@Test
	public void interceptorJsonRpcExceptionsFailClosedForResourceOperations()
			throws Exception {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"resource-interceptor-error-test",
						"4.0.0").build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.resourceRegistrations(java.util.List.of(McpResourceRegistration
						.withUriAndName(RESOURCE_URI, "Resource", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.handler((request, read, features) ->
								completeText(read.getUri(), "must-not-run"))
						.build()))
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, features, continuation) -> {
					throw new McpJsonRpcException(McpJsonRpcError.fromApplication(
							1_001, "interceptor-secret-must-not-leak"));
				})
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
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
			owner.close();
		}
	}

	@Test
	public void handlerJsonRpcExceptionsArePreservedForToolsAndPrompts()
			throws Exception {
		McpJsonObject toolData = McpJsonObject.builder()
				.put("kind", "tool")
				.build();
		McpJsonObject promptData = McpJsonObject.builder()
				.put("kind", "prompt")
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("intentional-tool-error", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					throw new McpJsonRpcException(McpJsonRpcError.fromApplication(
							1_001, "Tool precondition failed", toolData));
				})
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("intentional-prompt-error", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.handler((request, promptGet, features) -> {
					throw new McpJsonRpcException(McpJsonRpcError.fromApplication(
							1_002, "Prompt precondition failed", promptData));
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"handler-json-rpc-error-test", "4.0.0").build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.toolRegistrations(java.util.List.of(tool))
				.promptRegistrations(java.util.List.of(prompt))
				.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> toolResponse = callTool(port, "tool-error",
					"intentional-tool-error", "{}");
			HttpResponse<String> promptResponse = send(port,
					request("prompt-error", "prompts/get",
							",\"name\":\"intentional-prompt-error\","
									+ "\"arguments\":{}"),
					"prompts/get", "intentional-prompt-error");

			assertApplicationError(toolResponse, "tool-error", 1_001,
					"Tool precondition failed", "tool");
			assertApplicationError(promptResponse, "prompt-error", 1_002,
					"Prompt precondition failed", "prompt");
		} finally {
			owner.close();
		}
	}

	@Test
	@Timeout(180)
	public void interceptorsObserveAndRethrowExactHandlerErrorsOnEveryRevision() throws Exception {
		for (McpProtocolVersion protocolVersion : List.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28)) {
			AtomicReference<McpJsonRpcException> handlerFailure = new AtomicReference<>(intentionalFailure());
			AtomicInteger observed = new AtomicInteger();
			McpHandlerInterceptor inner = (requestContext, invocationFeatures, continuation) -> {
				try {
					return continuation.proceed();
				} catch (McpJsonRpcException exception) {
					Assertions.assertSame(handlerFailure.get(), exception);
					observed.incrementAndGet();
					throw exception;
				}
			};
			McpServer server = errorServer(protocolVersion, handlerFailure)
					.handlerInterceptor((requestContext, invocationFeatures, continuation) -> {
						try {
							return inner.interceptHandler(requestContext, invocationFeatures, continuation);
						} catch (McpJsonRpcException exception) {
							Assertions.assertSame(handlerFailure.get(), exception);
							observed.incrementAndGet();
							throw exception;
						}
					}).build();
			try (Soklet owner = managedSoklet(server)) {
				owner.start();
				int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
				List<ErrorOperation> operations = errorOperations(protocolVersion);
				Assertions.assertAll(operations.stream().map(operation -> () -> {
					HttpResponse<String> response = sendErrorOperation(port, protocolVersion, operation);
					Assertions.assertEquals(protocolVersion == McpProtocolVersion.V2026_07_28 ? 400 : 200,
							response.statusCode(), response.body());
					assertContains(response.body(), "\"code\":3001");
					assertContains(response.body(), "\"message\":\"Visible handler error\"");
					assertContains(response.body(), "\"data\":{\"kind\":\"intentional\"}");
				}));
				handlerFailure.set(new McpJsonRpcException(McpJsonRpcError.fromResourceNotFound(RESOURCE_URI)));
				HttpResponse<String> missingResource = sendErrorOperation(port, protocolVersion, operations.get(2));
				Assertions.assertEquals(protocolVersion == McpProtocolVersion.V2026_07_28 ? 400 : 200,
						missingResource.statusCode(), missingResource.body());
				assertContains(missingResource.body(), "\"code\":"
						+ (protocolVersion == McpProtocolVersion.V2026_07_28 ? -32602 : -32002));
				assertContains(missingResource.body(), "\"uri\":\"" + RESOURCE_URI + "\"");
				Assertions.assertEquals((operations.size() + 1) * 2, observed.get());
			}
		}
	}

	@Test
	@Timeout(180)
	public void interceptorCanRecoverHandlerErrorsThroughNormalResultValidation() throws Exception {
		for (McpProtocolVersion protocolVersion : List.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28)) {
			AtomicReference<McpJsonRpcException> handlerFailure = new AtomicReference<>(intentionalFailure());
			AtomicReference<McpOperationResult> recovery = new AtomicReference<>();
			AtomicInteger sanitized = new AtomicInteger();
			McpServer server = errorServer(protocolVersion, handlerFailure)
					.handlerInterceptor((requestContext, invocationFeatures, continuation) -> {
						try {
							return continuation.proceed();
						} catch (McpJsonRpcException exception) {
							Assertions.assertSame(handlerFailure.get(), exception);
							return recovery.get();
						}
					})
					.toolResultSanitizer((requestContext, toolName, rawArguments, completeResult) -> {
						sanitized.incrementAndGet();
						return McpCompleteResult.fromToolText("sanitized-recovery");
					}).build();
			try (Soklet owner = managedSoklet(server)) {
				owner.start();
				int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
				Assertions.assertAll(errorOperations(protocolVersion).stream().map(operation -> () -> {
					recovery.set(operation.recovery());
					HttpResponse<String> response = sendErrorOperation(port, protocolVersion, operation);
					Assertions.assertEquals(200, response.statusCode(), response.body());
					assertContains(response.body(), "recovery");
					Assertions.assertFalse(response.body().contains("Visible handler error"), response.body());
					if (operation.method().equals("tools/call"))
						assertContains(response.body(), "sanitized-recovery");
				}));
				Assertions.assertEquals(1, sanitized.get());
				// Catching a public handler error does not bypass method-specific result checks.
				recovery.set(McpCompleteResult.fromToolText("wrong-operation-recovery"));
				ErrorOperation completion = errorOperations(protocolVersion).get(4);
				assertPrivateError(sendErrorOperation(port, protocolVersion, completion), protocolVersion);
			}
		}
	}

	@Test
	@Timeout(540)
	public void copiedWrappedStaleAndInterceptorAuthoredErrorsStayPrivate() throws Exception {
		for (McpProtocolVersion protocolVersion : List.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28)) {
			McpJsonRpcException previousHandlerFailure = intentionalFailure();
			AtomicReference<McpJsonRpcException> handlerFailure = new AtomicReference<>(previousHandlerFailure);
			AtomicReference<String> mode = new AtomicReference<>("rethrow");
			AtomicInteger interceptedFailures = new AtomicInteger();
			McpServer server = errorServer(protocolVersion, handlerFailure)
					.handlerInterceptor((requestContext, invocationFeatures, continuation) -> {
						if (mode.get().equals("before"))
							throw new McpJsonRpcException(McpJsonRpcError.fromApplication(3002, "Private interceptor error"));
						McpOperationResult result;
						try {
							result = continuation.proceed();
						} catch (McpJsonRpcException exception) {
							Assertions.assertSame(handlerFailure.get(), exception);
							interceptedFailures.incrementAndGet();
							throw switch (mode.get()) {
								case "copy" -> new McpJsonRpcException(exception.getError());
								case "wrapped" -> new IllegalStateException("Private wrapper", exception);
								case "stale" -> previousHandlerFailure;
								default -> exception;
							};
						}
						if (mode.get().equals("after-success"))
							throw new McpJsonRpcException(McpJsonRpcError.fromApplication(3002, "Private interceptor error"));
						return result;
					}).build();
			try (Soklet owner = managedSoklet(server)) {
				owner.start();
				int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
				List<ErrorOperation> operations = errorOperations(protocolVersion);
				// Establish the old object's real handler origin in an earlier invocation.
				HttpResponse<String> prime = sendErrorOperation(port, protocolVersion, operations.get(0));
				assertContains(prime.body(), "Visible handler error");
				for (String errorMode : List.of("before", "after-success", "copy", "wrapped", "stale")) {
					mode.set(errorMode);
					handlerFailure.set(errorMode.equals("after-success") ? null : intentionalFailure());
					Assertions.assertAll(operations.stream().map(operation -> () ->
							assertPrivateError(sendErrorOperation(port, protocolVersion, operation), protocolVersion)));
				}
				Assertions.assertEquals(1 + operations.size() * 3, interceptedFailures.get());
			}
		}
	}

	private static McpJsonRpcException intentionalFailure() {
		return new McpJsonRpcException(McpJsonRpcError.fromApplication(3001, "Visible handler error",
				McpJsonObject.builder().put("kind", "intentional").build()));
	}

	private static McpServer.Builder errorServer(McpProtocolVersion protocolVersion,
			AtomicReference<McpJsonRpcException> handlerFailure) {
		Set<McpProtocolVersion> versions = Set.of(protocolVersion);
		McpCompletionHandler completionHandler = (requestContext, completionContext, invocationFeatures) -> {
			throwHandlerFailure(handlerFailure);
			return McpArgumentCompletionResult.fromValues(List.of("recovery"));
		};
		McpEndpoint.Builder endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion("handler-error-interception", "1").build(), versions)
				.toolRegistrations(List.of(McpToolRegistration.withName(TOOL_NAME, versions).jsonObjectArguments()
						.handler((requestContext, arguments, invocationFeatures) -> {
							throwHandlerFailure(handlerFailure);
							return McpCompleteResult.fromToolText("recovery");
						}).build()))
				.promptRegistrations(List.of(McpPromptRegistration.withName(PROMPT_NAME, versions)
						.handler((requestContext, promptGetContext, invocationFeatures) -> {
							throwHandlerFailure(handlerFailure);
							return promptRecovery();
						}).arguments(List.of(McpPromptArgumentDeclaration.withName("subject").build()))
						.completionHandler(completionHandler, versions).build()))
				.resourceRegistrations(List.of(McpResourceRegistration.withUriTemplateAndName(
						"test://interception/{subject}", "Resource", versions)
						.handler((requestContext, resourceReadContext, invocationFeatures) -> {
							throwHandlerFailure(handlerFailure);
							return completeText(resourceReadContext.getUri(), "recovery");
						}).completionHandler(completionHandler, versions).build()))
				.resourceListHandler((requestContext, resourceListContext, invocationFeatures) -> {
					throwHandlerFailure(handlerFailure);
					return McpResourcePage.builder().metadata(recoveryMetadata()).build();
				}, versions);
		if (protocolVersion == McpProtocolVersion.V2026_07_28)
			endpoint.skillListHandler((requestContext, skillListContext, invocationFeatures) -> {
				throwHandlerFailure(handlerFailure);
				return McpSkillPage.builder().metadata(recoveryMetadata()).build();
			}, versions);
		return serverBuilder(endpoint.build()).requestRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed());
	}

	private static void throwHandlerFailure(AtomicReference<McpJsonRpcException> handlerFailure) {
		McpJsonRpcException exception = handlerFailure.get();
		if (exception != null)
			throw exception;
	}

	private static McpJsonObject recoveryMetadata() {
		return McpJsonObject.builder().put("recovery", true).build();
	}

	private static McpCompleteResult promptRecovery() {
		return McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages(
				McpPromptMessage.fromUserContent(McpTextContent.fromText("recovery"))));
	}

	private static List<ErrorOperation> errorOperations(McpProtocolVersion protocolVersion) {
		List<ErrorOperation> operations = new ArrayList<>(List.of(
				new ErrorOperation("tools/call", ",\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}",
						Optional.of(TOOL_NAME), McpCompleteResult.fromToolText("recovery")),
				new ErrorOperation("prompts/get", ",\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{}",
						Optional.of(PROMPT_NAME), promptRecovery()),
				new ErrorOperation("resources/read", ",\"uri\":\"" + RESOURCE_URI + "\"",
						Optional.of(RESOURCE_URI.toString()), completeText(RESOURCE_URI, "recovery")),
				new ErrorOperation("resources/list", "", Optional.empty(),
						McpResourcePage.builder().metadata(recoveryMetadata()).build()),
				new ErrorOperation("completion/complete", ",\"ref\":{\"type\":\"ref/prompt\",\"name\":\""
						+ PROMPT_NAME + "\"},\"argument\":{\"name\":\"subject\",\"value\":\"r\"}", Optional.empty(),
						McpArgumentCompletionResult.fromValues(List.of("recovery"))),
				new ErrorOperation("completion/complete", ",\"ref\":{\"type\":\"ref/resource\",\"uri\":\""
						+ "test://interception/{subject}\"},\"argument\":{\"name\":\"subject\",\"value\":\"r\"}", Optional.empty(),
						McpArgumentCompletionResult.fromValues(List.of("recovery")))));
		if (protocolVersion == McpProtocolVersion.V2026_07_28)
			operations.add(new ErrorOperation("skills/list", "", Optional.empty(),
					McpSkillPage.builder().metadata(recoveryMetadata()).build()));
		return operations;
	}

	private record ErrorOperation(String method, String parameters, Optional<String> name,
			McpOperationResult recovery) { }

	private static final HttpClient ERROR_HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

	private static HttpResponse<String> sendErrorOperation(int port, McpProtocolVersion protocolVersion,
			ErrorOperation operation) throws Exception {
		boolean modern = protocolVersion == McpProtocolVersion.V2026_07_28;
		String body = modern ? request("error", operation.method(), operation.parameters())
				: "{\"jsonrpc\":\"2.0\",\"id\":\"error\",\"method\":\"" + operation.method() + "\",\"params\":{"
						+ (operation.parameters().isEmpty() ? "" : operation.parameters().substring(1)) + "}}";
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5)).header("Content-Type", JSON_MEDIA_TYPE)
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", protocolVersion.getWireValue());
		if (modern) {
			request.header("Mcp-Method", operation.method());
			operation.name().ifPresent(name -> request.header("Mcp-Name", name));
		}
		var response = ERROR_HTTP.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		try {
			return response.get(5, TimeUnit.SECONDS);
		} finally {
			response.cancel(true);
		}
	}

	private static void assertPrivateError(HttpResponse<String> response, McpProtocolVersion protocolVersion) {
		Assertions.assertEquals(protocolVersion == McpProtocolVersion.V2026_07_28 ? 500 : 200,
				response.statusCode(), response.body());
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"error\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}",
				response.body());
	}

	@Test
	public void continuationIsOneShotThreadBoundAndCallScoped() throws Exception {
		Map<String, AtomicInteger> handlerInvocations = new ConcurrentHashMap<>();
		for (String toolName : List.of("one-shot", "wrong-thread", "retained"))
			handlerInvocations.put(toolName, new AtomicInteger());
		AtomicReference<McpHandlerContinuation> retainedContinuation =
				new AtomicReference<>();
		AtomicReference<Throwable> wrongThreadInvocationFailure =
				new AtomicReference<>();

		McpEndpoint.Builder endpointBuilder = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"handler-continuation-runtime-test", "4.0.0")
						.build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28));
		List<McpToolRegistration<?>> toolRegistrations = new ArrayList<>();
		for (String toolName : handlerInvocations.keySet()) {
			toolRegistrations.add(McpToolRegistration.withName(toolName, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
					.jsonObjectArguments()
					.handler((request, arguments, features) -> {
						handlerInvocations.get(toolName).incrementAndGet();
						return McpCompleteResult.fromToolText(toolName + "-handled");
					})
					.build());
		}
		McpEndpoint endpoint = endpointBuilder.toolRegistrations(toolRegistrations).build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, features, continuation) -> switch (
						context.getOperationName().orElseThrow()) {
					case "one-shot" -> {
						McpOperationResult result = continuation.proceed();
						Assertions.assertThrows(IllegalStateException.class,
								continuation::proceed);
						yield result;
					}
					case "wrong-thread" -> {
						Thread thread = new Thread(() -> {
							try {
								continuation.proceed();
							} catch (Throwable throwable) {
								wrongThreadInvocationFailure.set(throwable);
							}
						}, "mcp-handler-interceptor-wrong-thread-test");
						thread.start();
						thread.join(TimeUnit.SECONDS.toMillis(5));
						Assertions.assertFalse(thread.isAlive());
						Assertions.assertInstanceOf(IllegalStateException.class,
								wrongThreadInvocationFailure.get());
						Assertions.assertEquals(0,
								handlerInvocations.get("wrong-thread").get());
						yield continuation.proceed();
					}
					case "retained" -> {
						retainedContinuation.set(continuation);
						yield continuation.proceed();
					}
					default -> throw new AssertionError("Unexpected tool");
				})
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
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
					() -> retainedContinuation.get().proceed());
			Assertions.assertEquals(1,
					handlerInvocations.get("retained").get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void deadlinePreventsLatePublicHandlerEntry() throws Exception {
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicReference<Exception> lateContinuationFailure =
				new AtomicReference<>();
		CountDownLatch lateContinuationCompleted = new CountDownLatch(1);
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation.withNameAndVersion(
						"handler-interception-deadline-test", "4.0.0")
						.build(), java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.toolRegistrations(java.util.List.of(McpToolRegistration.withName("late", java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
						.jsonObjectArguments()
						.handler((request, arguments, features) -> {
							handlerInvocations.incrementAndGet();
							return McpCompleteResult.fromToolText("too-late");
						})
						.build()))
				.build();
		McpServer server = serverBuilder(endpoint)
				.requestTimeout(Duration.ofMillis(50))
				.handlerInterceptor((context, features, continuation) -> {
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
						return continuation.proceed();
					} catch (Exception exception) {
						lateContinuationFailure.set(exception);
						throw exception;
					} finally {
						lateContinuationCompleted.countDown();
					}
				})
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = callTool(port, "late", "late", "{}");

			Assertions.assertEquals(504, response.statusCode(), response.body());
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"late\","
							+ "\"error\":{\"code\":-32603,"
							+ "\"message\":\"Internal error\"}}",
					response.body());
			Assertions.assertEquals("application/json",
					response.headers().firstValue("Content-Type").orElseThrow());
			Assertions.assertEquals(1, interceptorInvocations.get());
			Assertions.assertTrue(lateContinuationCompleted.await(5,
					TimeUnit.SECONDS),
					"The noncooperative interceptor did not attempt late continuation.");
			Assertions.assertInstanceOf(InterruptedException.class,
					lateContinuationFailure.get());
			Assertions.assertEquals(0, handlerInvocations.get(),
					"An expired request must not enter the public handler.");
		} finally {
			try {
				lateContinuationCompleted.await(5, TimeUnit.SECONDS);
			} finally {
				owner.close();
			}
		}
	}

	private static McpToolRegistration<McpJsonObject> rawTool(String name) {
		return McpToolRegistration.withName(name, java.util.Set.of(com.soklet.McpProtocolVersion.V2026_07_28))
				.jsonObjectArguments()
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText(name + "-handled"))
				.build();
	}

	private static McpCompleteResult completeText(URI uri, String text) {
		return McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(McpTextResourceContents.withUriAndText(uri, text)
						.mimeType("text/plain")
						.build())
				.build());
	}

	private static McpServer.Builder serverBuilder(McpEndpoint endpoint) {
		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static Soklet managedSoklet(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(10))
					.startupCancelationTimeout(Duration.ofSeconds(1))
					.gracefulShutdownTimeout(Duration.ofSeconds(1))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build())
				.build());
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
		var response = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		try {
			return response.get(5, TimeUnit.SECONDS);
		} finally {
			response.cancel(true);
		}
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

	private static void assertApplicationError(HttpResponse<String> response,
			String expectedId, int expectedCode, String expectedMessage,
			String expectedKind) {
		Assertions.assertEquals(400, response.statusCode(), response.body());
		assertContains(response.body(), "\"id\":\"" + expectedId + "\"");
		assertContains(response.body(), "\"code\":" + expectedCode);
		assertContains(response.body(), "\"message\":\""
				+ expectedMessage + "\"");
		assertContains(response.body(), "\"data\":{\"kind\":\""
				+ expectedKind + "\"}");
	}

	private static void assertContains(String text, String expected) {
		Assertions.assertTrue(text.contains(expected), text);
	}

	private record RequiredArguments(String required) {
	}
}
