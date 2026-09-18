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
import org.jspecify.annotations.Nullable;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box coverage for caller-aware tool and prompt catalogs.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpCatalogAccessPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String CALLER_HEADER = "X-Test-Catalog-Caller";
	private static final String ANONYMOUS = "anonymous";
	private static final String ADMIN = "admin";
	private static final String TENANT_A = "tenant-a";
	private static final String TENANT_B = "tenant-b";
	private static final List<String> ALL_TOOL_NAMES = List.of(
			"catalog.shared", "catalog.tenant-a", "catalog.admin",
			"catalog.tenant-b");
	private static final List<String> ALL_PROMPT_NAMES = List.of(
			"prompt.shared", "prompt.tenant-a", "prompt.admin",
			"prompt.tenant-b");
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	@Test
	public void listFilteringPreservesCanonicalOrderForEveryCallerClass()
			throws Exception {
		AtomicInteger handlerInvocations = new AtomicInteger();
		List<McpToolRegistration<McpJsonObject>> tools = ALL_TOOL_NAMES.stream()
				.map(name -> tool(name, handlerInvocations))
				.toList();
		List<McpPromptRegistration> prompts = ALL_PROMPT_NAMES.stream()
				.map(name -> prompt(name, handlerInvocations))
				.toList();
		McpEndpoint.Builder endpointBuilder = endpointBuilder(
				"catalog-access-list-runtime-test");
		tools.forEach(endpointBuilder::addTool);
		prompts.forEach(endpointBuilder::addPrompt);
		McpEndpoint endpoint = endpointBuilder.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> visibleToCaller(
						caller(context), registration.getName()),
				(context, registration, features) -> visibleToCaller(
						caller(context), registration.getName()));
		McpServer server = serverBuilder(endpoint, policy)
				.admissionController(
						McpCatalogAccessPublicRuntimeTests::admitCaller)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			for (CallerCase callerCase : List.of(
					new CallerCase(ANONYMOUS,
							List.of("catalog.shared"),
							List.of("prompt.shared")),
					new CallerCase(ADMIN, ALL_TOOL_NAMES, ALL_PROMPT_NAMES),
					new CallerCase(TENANT_A,
							List.of("catalog.shared", "catalog.tenant-a"),
							List.of("prompt.shared", "prompt.tenant-a")),
					new CallerCase(TENANT_B,
							List.of("catalog.shared", "catalog.tenant-b"),
							List.of("prompt.shared", "prompt.tenant-b")))) {
				HttpResponse<String> toolList = send(server,
						callerCase.caller() + "-tools", "tools/list", null,
						callerCase.caller(), "");
				assertSuccess(toolList, callerCase.caller() + "-tools");
				assertVisibleNames(toolList.body(), callerCase.tools(),
						ALL_TOOL_NAMES);

				HttpResponse<String> promptList = send(server,
						callerCase.caller() + "-prompts", "prompts/list", null,
						callerCase.caller(), "");
				assertSuccess(promptList, callerCase.caller() + "-prompts");
				assertVisibleNames(promptList.body(), callerCase.prompts(),
						ALL_PROMPT_NAMES);
			}
			Assertions.assertEquals(0, handlerInvocations.get(),
					"Catalog listing must not dispatch registrations.");
		} finally {
			owner.close();
		}
	}

	@Test
	public void fullyFilteredNonemptyCatalogsReturnSuccessfulEmptyArrays()
			throws Exception {
		AtomicInteger toolPolicyInvocations = new AtomicInteger();
		AtomicInteger promptPolicyInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpEndpoint endpoint = endpointBuilder(
				"catalog-access-empty-runtime-test")
				.addTool(tool("empty.hidden-tool", handlerInvocations))
				.addPrompt(prompt("empty.hidden-prompt", handlerInvocations))
				.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					toolPolicyInvocations.incrementAndGet();
					return false;
				}, (context, registration, features) -> {
					promptPolicyInvocations.incrementAndGet();
					return false;
				});
		McpServer server = serverBuilder(endpoint, policy)
				.admissionController(context -> McpAdmissionDecision.accepted())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			HttpResponse<String> tools = send(server, "empty-tools",
					"tools/list", null, ANONYMOUS, "");
			assertSuccess(tools, "empty-tools");
			Assertions.assertTrue(tools.body().contains("\"tools\":[]"),
					tools.body());
			Assertions.assertFalse(tools.body().contains("empty.hidden-tool"),
					tools.body());

			HttpResponse<String> prompts = send(server, "empty-prompts",
					"prompts/list", null, ANONYMOUS, "");
			assertSuccess(prompts, "empty-prompts");
			Assertions.assertTrue(prompts.body().contains("\"prompts\":[]"),
					prompts.body());
			Assertions.assertFalse(prompts.body().contains("empty.hidden-prompt"),
					prompts.body());

			Assertions.assertEquals(1, toolPolicyInvocations.get());
			Assertions.assertEquals(1, promptPolicyInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void catalogListStopsBeforeNextEvaluatorAfterDeadline()
			throws Exception {
		AtomicInteger firstInvocations = new AtomicInteger();
		AtomicInteger secondInvocations = new AtomicInteger();
		AtomicReference<Boolean> canceledBeforeFirstReturned =
				new AtomicReference<>(false);
		CountDownLatch firstReturned = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpEndpoint endpoint = endpointBuilder(
				"catalog-access-deadline-runtime-test")
				.addTool(tool("deadline.first", handlerInvocations))
				.addTool(tool("deadline.second", handlerInvocations))
				.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					if (registration.getName().endsWith("first")) {
						firstInvocations.incrementAndGet();
						long finish = System.nanoTime()
								+ TimeUnit.MILLISECONDS.toNanos(250);
						while (System.nanoTime() - finish < 0L) {
							try {
								Thread.sleep(10);
							} catch (InterruptedException ignored) {
								// Deliberately exercise a noncooperative evaluator.
							}
						}
						canceledBeforeFirstReturned.set(features
								.getCancelationToken().isCanceled());
						firstReturned.countDown();
						return true;
					}
					secondInvocations.incrementAndGet();
					secondEntered.countDown();
					return true;
				}, (context, registration, features) -> true);
		McpServer server = serverBuilder(endpoint, policy)
				.admissionController(context -> McpAdmissionDecision.accepted())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.requestTimeout(Duration.ofMillis(50))
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			HttpResponse<String> response = send(server, "deadline-list",
					"tools/list", null, ANONYMOUS, "");

			Assertions.assertEquals(504, response.statusCode(), response.body());
			Assertions.assertTrue(firstReturned.await(5, TimeUnit.SECONDS),
					"The first evaluator did not return after the deadline.");
			Assertions.assertTrue(canceledBeforeFirstReturned.get(),
					"The shared catalog cancellation token was not canceled.");
			Assertions.assertFalse(secondEntered.await(500,
					TimeUnit.MILLISECONDS),
					"A later evaluator entered after cancellation.");
			Assertions.assertEquals(1, firstInvocations.get());
			Assertions.assertEquals(0, secondInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
		} finally {
			try {
				firstReturned.await(5, TimeUnit.SECONDS);
			} finally {
				owner.close();
			}
		}
	}

	@Test
	public void explicitlyConfiguredAllowAllKeepsCatalogsRequestScoped()
			throws Exception {
		AtomicInteger providerInvocations = new AtomicInteger();
		AtomicInteger lookupInvocations = new AtomicInteger();
		McpEndpoint endpoint = endpointBuilder(
				"catalog-access-explicit-allow-all-runtime-test")
				.addTool(McpToolRegistration.withName("allow-all.tool")
						.jsonObjectArguments()
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("unused"))
						.title("Canonical tool title")
						.build())
				.addPrompt(McpPromptRegistration.withName("allow-all.prompt")
						.handler((request, promptGet, features) ->
								McpCompleteResult.fromPromptOutput(
										McpPromptOutput.fromMessages()))
						.title("Canonical prompt title")
						.build())
				.build();
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> {
					providerInvocations.incrementAndGet();
					String requestCaller = caller(request.getRequestContext());
					return McpLocalizationContext.withLocale(Locale.FRENCH, text -> {
						lookupInvocations.incrementAndGet();
						return McpLocalizationResult.localized(requestCaller + ":"
								+ text.getDefaultText());
					}).build();
				}).build();
		McpServer server = serverBuilder(endpoint,
				McpCatalogAccessPolicy.allowAllInstance())
				.admissionController(
						McpCatalogAccessPublicRuntimeTests::admitCaller)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.localizer(localizer)
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			HttpResponse<String> tenantATools = send(server, "allow-a-tools",
					"tools/list", null, TENANT_A, "");
			HttpResponse<String> tenantBTools = send(server, "allow-b-tools",
					"tools/list", null, TENANT_B, "");
			HttpResponse<String> tenantAPrompts = send(server, "allow-a-prompts",
					"prompts/list", null, TENANT_A, "");
			HttpResponse<String> tenantBPrompts = send(server, "allow-b-prompts",
					"prompts/list", null, TENANT_B, "");

			assertSuccess(tenantATools, "allow-a-tools");
			assertSuccess(tenantBTools, "allow-b-tools");
			assertSuccess(tenantAPrompts, "allow-a-prompts");
			assertSuccess(tenantBPrompts, "allow-b-prompts");
			Assertions.assertTrue(tenantATools.body().contains(
					"\"title\":\"tenant-a:Canonical tool title\""),
					tenantATools.body());
			Assertions.assertFalse(tenantATools.body().contains(
					"tenant-b:Canonical tool title"), tenantATools.body());
			Assertions.assertTrue(tenantBTools.body().contains(
					"\"title\":\"tenant-b:Canonical tool title\""),
					tenantBTools.body());
			Assertions.assertTrue(tenantAPrompts.body().contains(
					"\"title\":\"tenant-a:Canonical prompt title\""),
					tenantAPrompts.body());
			Assertions.assertTrue(tenantBPrompts.body().contains(
					"\"title\":\"tenant-b:Canonical prompt title\""),
					tenantBPrompts.body());
			Assertions.assertEquals(4, providerInvocations.get(),
					"Explicit allow-all must still use one request-local context per list.");
			Assertions.assertEquals(4, lookupInvocations.get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void directToolAndPromptDispatchUseThePolicyPipelineOrder()
			throws Exception {
		List<String> stages = new CopyOnWriteArrayList<>();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("pipeline.tool")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					stages.add("handler:tools/call");
					return McpCompleteResult.fromToolText("tool-ok");
				})
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("pipeline.prompt")
				.handler((request, promptGet, features) -> {
					stages.add("handler:prompts/get");
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages(
									McpPromptMessage.fromUserText("prompt-ok")));
				})
				.build();
		McpEndpoint endpoint = endpointBuilder(
				"catalog-access-order-runtime-test")
				.addTool(tool)
				.addPrompt(prompt)
				.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					Assertions.assertSame(tool, registration);
					stages.add("policy:tools/call");
					return true;
				}, (context, registration, features) -> {
					Assertions.assertSame(prompt, registration);
					stages.add("policy:prompts/get");
					return true;
				});
		McpServer server = serverBuilder(endpoint, policy)
				.admissionController(context -> {
					stages.add("admission:" + context.getJsonRpcMethod());
					return McpAdmissionDecision.accepted();
				})
				.requestRateLimiter(context -> {
					stages.add("request-limiter:" + context.getJsonRpcMethod());
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					stages.add("tool-limiter");
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((context, features, continuation) -> {
					stages.add("interceptor-before:" + context.getJsonRpcMethod());
					McpOperationResult result = continuation.proceed();
					stages.add("interceptor-after:" + context.getJsonRpcMethod());
					return result;
				})
				.toolOutputSanitizer((request, toolName, arguments, output) -> {
					stages.add("sanitizer");
					return output;
				})
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			HttpResponse<String> toolResponse = send(server, "tool-order",
					"tools/call", tool.getName(), ANONYMOUS,
					",\"name\":\"" + tool.getName() + "\",\"arguments\":{}");
			assertSuccess(toolResponse, "tool-order");
			Assertions.assertEquals(List.of(
					"admission:tools/call",
					"request-limiter:tools/call",
					"policy:tools/call",
					"tool-limiter",
					"interceptor-before:tools/call",
					"handler:tools/call",
					"interceptor-after:tools/call",
					"sanitizer"), stages);

			stages.clear();
			HttpResponse<String> promptResponse = send(server, "prompt-order",
					"prompts/get", prompt.getName(), ANONYMOUS,
					",\"name\":\"" + prompt.getName()
							+ "\",\"arguments\":{}");
			assertSuccess(promptResponse, "prompt-order");
			Assertions.assertEquals(List.of(
					"admission:prompts/get",
					"request-limiter:prompts/get",
					"policy:prompts/get",
					"interceptor-before:prompts/get",
					"handler:prompts/get",
					"interceptor-after:prompts/get"), stages);
		} finally {
			owner.close();
		}
	}

	@Test
	public void directPolicyAndHandlerShareOneLocalizationContext()
			throws Exception {
		AtomicInteger providerInvocations = new AtomicInteger();
		AtomicReference<McpLocalizationContext> toolPolicyContext =
				new AtomicReference<>();
		AtomicReference<McpLocalizationContext> toolHandlerContext =
				new AtomicReference<>();
		AtomicReference<McpLocalizationContext> promptPolicyContext =
				new AtomicReference<>();
		AtomicReference<McpLocalizationContext> promptHandlerContext =
				new AtomicReference<>();
		AtomicReference<McpRequestContext> toolPolicyRequest =
				new AtomicReference<>();
		AtomicReference<McpRequestContext> toolHandlerRequest =
				new AtomicReference<>();
		AtomicReference<McpRequestContext> promptPolicyRequest =
				new AtomicReference<>();
		AtomicReference<McpRequestContext> promptHandlerRequest =
				new AtomicReference<>();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("context.tool")
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					toolHandlerRequest.set(request);
					toolHandlerContext.set(features.find(
							McpLocalizationContext.class).orElseThrow());
					return McpCompleteResult.fromToolText("tool-ok");
				})
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("context.prompt")
				.handler((request, promptGet, features) -> {
					promptHandlerRequest.set(request);
					promptHandlerContext.set(features.find(
							McpLocalizationContext.class).orElseThrow());
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages());
				})
				.build();
		McpEndpoint endpoint = endpointBuilder(
				"catalog-access-context-runtime-test")
				.addTool(tool)
				.addPrompt(prompt)
				.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					Assertions.assertSame(tool, registration);
					toolPolicyRequest.set(context);
					toolPolicyContext.set(features.find(
							McpLocalizationContext.class).orElseThrow());
					return true;
				}, (context, registration, features) -> {
					Assertions.assertSame(prompt, registration);
					promptPolicyRequest.set(context);
					promptPolicyContext.set(features.find(
							McpLocalizationContext.class).orElseThrow());
					return true;
				});
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH,
				request -> {
					providerInvocations.incrementAndGet();
					return McpLocalizationContext.withLocale(Locale.FRENCH,
							text -> McpLocalizationResult.useDefaultText()).build();
				}).build();
		McpServer server = serverBuilder(endpoint, policy)
				.admissionController(context -> McpAdmissionDecision.accepted())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.localizer(localizer)
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			HttpResponse<String> toolResponse = send(server, "context-tool",
					"tools/call", tool.getName(), ANONYMOUS,
					",\"name\":\"" + tool.getName() + "\",\"arguments\":{}");
			assertSuccess(toolResponse, "context-tool");
			HttpResponse<String> promptResponse = send(server, "context-prompt",
					"prompts/get", prompt.getName(), ANONYMOUS,
					",\"name\":\"" + prompt.getName()
							+ "\",\"arguments\":{}");
			assertSuccess(promptResponse, "context-prompt");

			Assertions.assertSame(toolPolicyContext.get(), toolHandlerContext.get());
			Assertions.assertSame(promptPolicyContext.get(),
					promptHandlerContext.get());
			Assertions.assertSame(toolPolicyRequest.get(), toolHandlerRequest.get());
			Assertions.assertSame(promptPolicyRequest.get(),
					promptHandlerRequest.get());
			Assertions.assertNotSame(toolPolicyContext.get(),
					promptPolicyContext.get());
			Assertions.assertEquals(2, providerInvocations.get(),
					"Each direct request must create exactly one context.");
		} finally {
			owner.close();
		}
	}

	@Test
	public void hiddenAndUnknownToolsAreByteAndLimiterEquivalent()
			throws Exception {
		String hiddenName = "neutral.hidden";
		String unknownName = "neutral.unknown";
		List<String> stages = new CopyOnWriteArrayList<>();
		AtomicInteger requestLimiterInvocations = new AtomicInteger();
		AtomicInteger toolLimiterInvocations = new AtomicInteger();
		AtomicInteger policyInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpToolRegistration<RequiredArguments> hidden = McpToolRegistration
				.withName(hiddenName)
				.argumentType(RequiredArguments.class)
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("must-not-run");
				})
				.build();
		McpEndpoint endpoint = endpointBuilder(
				"catalog-access-neutral-runtime-test")
				.addTool(hidden)
				.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					Assertions.assertSame(hidden, registration);
					policyInvocations.incrementAndGet();
					stages.add("policy:" + registration.getName());
					return false;
				}, (context, registration, features) -> true);
		McpServer server = serverBuilder(endpoint, policy)
				.admissionController(context -> {
					stages.add("admission:"
							+ context.getOperationName().orElse("-"));
					return McpAdmissionDecision.accepted();
				})
				.requestRateLimiter(context -> {
					requestLimiterInvocations.incrementAndGet();
					stages.add("request:"
							+ context.getOperationName().orElse("-"));
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					toolLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((context, features, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			HttpResponse<String> hiddenResponse = send(server, "neutral-id",
					"tools/call", hiddenName, ANONYMOUS,
					",\"name\":\"" + hiddenName + "\",\"arguments\":{}");
			assertNeutralFailure(hiddenResponse, "neutral-id");
			Assertions.assertEquals(1, requestLimiterInvocations.get());
			Assertions.assertEquals(0, toolLimiterInvocations.get());

			HttpResponse<String> unknownResponse = send(server, "neutral-id",
					"tools/call", unknownName, ANONYMOUS,
					",\"name\":\"" + unknownName + "\",\"arguments\":{}");
			assertNeutralFailure(unknownResponse, "neutral-id");
			Assertions.assertEquals(hiddenResponse.statusCode(),
					unknownResponse.statusCode());
			Assertions.assertEquals(hiddenResponse.body(), unknownResponse.body());
			Assertions.assertEquals(2, requestLimiterInvocations.get(),
					"Both names must consume the same request-limiter charge.");
			Assertions.assertEquals(0, toolLimiterInvocations.get(),
					"Neither name may reach the registration-specific tool limiter.");
			Assertions.assertEquals(1, policyInvocations.get(),
					"Only the registered hidden name has a canonical policy input.");
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(List.of(
					"admission:" + hiddenName,
					"request:" + hiddenName,
					"policy:" + hiddenName,
					"admission:" + unknownName,
					"request:" + unknownName), stages);
		} finally {
			owner.close();
		}
	}

	@Test
	public void hiddenAndUnknownPromptsAreByteAndLimiterEquivalent()
			throws Exception {
		String hiddenName = "neutral.hidden-prompt";
		String unknownName = "neutral.unknown-prompt";
		List<String> stages = new CopyOnWriteArrayList<>();
		AtomicInteger requestLimiterInvocations = new AtomicInteger();
		AtomicInteger policyInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpPromptRegistration hidden = McpPromptRegistration
				.withName(hiddenName)
				.handler((request, promptGet, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages());
				})
				.build();
		McpEndpoint endpoint = endpointBuilder(
				"catalog-access-prompt-neutral-runtime-test")
				.addPrompt(hidden)
				.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> true,
				(context, registration, features) -> {
					Assertions.assertSame(hidden, registration);
					policyInvocations.incrementAndGet();
					stages.add("policy:" + registration.getName());
					return false;
				});
		McpServer server = serverBuilder(endpoint, policy)
				.admissionController(context -> {
					stages.add("admission:"
							+ context.getOperationName().orElse("-"));
					return McpAdmissionDecision.accepted();
				})
				.requestRateLimiter(context -> {
					requestLimiterInvocations.incrementAndGet();
					stages.add("request:"
							+ context.getOperationName().orElse("-"));
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((context, features, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			HttpResponse<String> hiddenResponse = send(server, "neutral-prompt-id",
					"prompts/get", hiddenName, ANONYMOUS,
					",\"name\":\"" + hiddenName + "\",\"arguments\":{}");
			assertNeutralFailure(hiddenResponse, "neutral-prompt-id");

			HttpResponse<String> unknownResponse = send(server, "neutral-prompt-id",
					"prompts/get", unknownName, ANONYMOUS,
					",\"name\":\"" + unknownName + "\",\"arguments\":{}");
			assertNeutralFailure(unknownResponse, "neutral-prompt-id");
			Assertions.assertEquals(hiddenResponse.statusCode(),
					unknownResponse.statusCode());
			Assertions.assertEquals(hiddenResponse.body(), unknownResponse.body());
			Assertions.assertEquals(2, requestLimiterInvocations.get(),
					"Both names must consume the same request-limiter charge.");
			Assertions.assertEquals(1, policyInvocations.get(),
					"Only the registered hidden prompt has a canonical policy input.");
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(List.of(
					"admission:" + hiddenName,
					"request:" + hiddenName,
					"policy:" + hiddenName,
					"admission:" + unknownName,
					"request:" + unknownName), stages);
		} finally {
			owner.close();
		}
	}

	@Test
	public void exhaustedRequestLimiterPreventsPolicyAndToolLimiterWithParity()
			throws Exception {
		Duration retryAfter = Duration.ofSeconds(2);
		AtomicInteger requestLimiterInvocations = new AtomicInteger();
		AtomicInteger toolPolicyInvocations = new AtomicInteger();
		AtomicInteger promptPolicyInvocations = new AtomicInteger();
		AtomicInteger toolLimiterInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpEndpoint endpoint = endpointBuilder(
				"catalog-access-request-limit-runtime-test")
				.addTool(tool("limited.registered-tool", handlerInvocations))
				.addPrompt(prompt("limited.registered-prompt", handlerInvocations))
				.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					toolPolicyInvocations.incrementAndGet();
					return true;
				}, (context, registration, features) -> {
					promptPolicyInvocations.incrementAndGet();
					return true;
				});
		McpServer server = serverBuilder(endpoint, policy)
				.admissionController(context -> McpAdmissionDecision.accepted())
				.requestRateLimiter(context ->
						requestLimiterInvocations.incrementAndGet() == 1
								? McpRateLimitDecision.allowed()
								: McpRateLimitDecision.denied(retryAfter))
				.toolRateLimiter(context -> {
					toolLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			HttpResponse<String> setup = send(server, "limit-setup",
					"server/discover", null, ANONYMOUS, "");
			assertSuccess(setup, "limit-setup");

			List<HttpResponse<String>> denied = List.of(
					send(server, "limited-id", "tools/call",
							"limited.registered-tool", ANONYMOUS,
							",\"name\":\"limited.registered-tool\",\"arguments\":{}"),
					send(server, "limited-id", "tools/call",
							"limited.unknown-tool", ANONYMOUS,
							",\"name\":\"limited.unknown-tool\",\"arguments\":{}"),
					send(server, "limited-id", "prompts/get",
							"limited.registered-prompt", ANONYMOUS,
							",\"name\":\"limited.registered-prompt\",\"arguments\":{}"),
					send(server, "limited-id", "prompts/get",
							"limited.unknown-prompt", ANONYMOUS,
							",\"name\":\"limited.unknown-prompt\",\"arguments\":{}"));
			for (HttpResponse<String> response : denied)
				assertRateLimited(response, "limited-id", retryAfter);
			for (HttpResponse<String> response : denied)
				Assertions.assertEquals(denied.get(0).body(), response.body(),
						"Registered and unknown direct names must be byte-equivalent "
								+ "when the request limiter denies them.");

			Assertions.assertEquals(5, requestLimiterInvocations.get());
			Assertions.assertEquals(0, toolPolicyInvocations.get());
			Assertions.assertEquals(0, promptPolicyInvocations.get());
			Assertions.assertEquals(0, toolLimiterInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
		} finally {
			owner.close();
		}
	}

	@Test
	public void catalogPolicyNullAndExceptionAbortWithoutPartialLists()
			throws Exception {
		AtomicReference<FailureMode> failureMode =
				new AtomicReference<>(FailureMode.RETURN_NULL);
		AtomicInteger handlerInvocations = new AtomicInteger();
		List<String> toolEvaluations = new CopyOnWriteArrayList<>();
		List<String> promptEvaluations = new CopyOnWriteArrayList<>();
		McpEndpoint endpoint = endpointBuilder(
				"catalog-access-failure-runtime-test")
				.addTool(tool("failure.tool.first", handlerInvocations))
				.addTool(tool("failure.tool.second", handlerInvocations))
				.addPrompt(prompt("failure.prompt.first", handlerInvocations))
				.addPrompt(prompt("failure.prompt.second", handlerInvocations))
				.build();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(context, registration, features) -> {
					toolEvaluations.add(registration.getName());
					if (registration.getName().endsWith("first"))
						return true;
					return policyFailure(failureMode.get());
				}, (context, registration, features) -> {
					promptEvaluations.add(registration.getName());
					if (registration.getName().endsWith("first"))
						return true;
					return policyFailure(failureMode.get());
				});
		McpServer server = serverBuilder(endpoint, policy)
				.admissionController(context -> McpAdmissionDecision.accepted())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.build();
		Soklet owner = managedSoklet(server);

		try {
			owner.start();
			HttpResponse<String> nullResponse = send(server, "null-list",
					"tools/list", null, ANONYMOUS, "");
			assertInternalError(nullResponse, "null-list");
			Assertions.assertFalse(nullResponse.body().contains("failure.tool.first"),
					nullResponse.body());
			Assertions.assertEquals(List.of("failure.tool.first",
					"failure.tool.second"), toolEvaluations);

			failureMode.set(FailureMode.THROW);
			HttpResponse<String> throwingResponse = send(server, "throw-list",
					"prompts/list", null, ANONYMOUS, "");
			assertInternalError(throwingResponse, "throw-list");
			Assertions.assertFalse(throwingResponse.body()
					.contains("failure.prompt.first"), throwingResponse.body());
			Assertions.assertFalse(throwingResponse.body()
					.contains("PRIVATE-POLICY-FAILURE"), throwingResponse.body());
			Assertions.assertEquals(List.of("failure.prompt.first",
					"failure.prompt.second"), promptEvaluations);
			Assertions.assertEquals(0, handlerInvocations.get());
		} finally {
			owner.close();
		}
	}

	private static McpToolRegistration<McpJsonObject> tool(
			@NonNull String name, @NonNull AtomicInteger handlerInvocations) {
		return McpToolRegistration.withName(name)
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText(name);
				})
				.build();
	}

	private static McpPromptRegistration prompt(@NonNull String name,
			@NonNull AtomicInteger handlerInvocations) {
		return McpPromptRegistration.withName(name)
				.handler((request, promptGet, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages(
									McpPromptMessage.fromUserText(name)));
				})
				.build();
	}

	private static McpEndpoint.Builder endpointBuilder(
			@NonNull String implementationName) {
		return McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						implementationName, "4.0.0").build())
				.serverInfoIncluded(false);
	}

	private static McpServer.Builder serverBuilder(@NonNull McpEndpoint endpoint,
			@NonNull McpCatalogAccessPolicy policy) {
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK)
				.catalogAccessPolicy(policy)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static Soklet managedSoklet(@NonNull McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static McpAdmissionDecision admitCaller(
			@NonNull McpAdmissionContext context) {
		String caller = context.getRequest().getHeader(CALLER_HEADER)
				.orElse(ANONYMOUS);
		if (ANONYMOUS.equals(caller))
			return McpAdmissionDecision.accepted();
		McpAdmissionIdentity identity = McpAdmissionIdentity
				.withRateLimitPartitionKey("rate-" + caller)
				.authorizationPartitionKey(caller)
				.principal(caller)
				.build();
		return McpAdmissionDecision.accepted(identity);
	}

	private static String caller(@NonNull McpRequestContext context) {
		return context.getAdmissionIdentity().getAuthorizationPartitionKey()
				.orElse(ANONYMOUS);
	}

	private static boolean visibleToCaller(@NonNull String caller,
			@NonNull String registrationName) {
		if (ADMIN.equals(caller))
			return true;
		if (registrationName.endsWith(".shared"))
			return true;
		return (TENANT_A.equals(caller)
				&& registrationName.endsWith(".tenant-a"))
				|| (TENANT_B.equals(caller)
				&& registrationName.endsWith(".tenant-b"));
	}

	private static Boolean policyFailure(@NonNull FailureMode failureMode) {
		return switch (failureMode) {
			case RETURN_NULL -> null;
			case THROW -> throw new IllegalStateException(
					"PRIVATE-POLICY-FAILURE");
		};
	}

	private static HttpResponse<String> send(@NonNull McpServer server,
			@NonNull String requestId, @NonNull String method,
			@Nullable String operationName, @NonNull String caller,
			@NonNull String additionalParameters) throws Exception {
		int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		if (operationName != null)
			request.header("Mcp-Name", operationName);
		if (!ANONYMOUS.equals(caller))
			request.header(CALLER_HEADER, caller);
		return HTTP_CLIENT.send(request.POST(HttpRequest.BodyPublishers.ofString(
				body, StandardCharsets.UTF_8)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertVisibleNames(@NonNull String body,
			@NonNull List<@NonNull String> expected,
			@NonNull List<@NonNull String> completeCatalog) {
		int previous = -1;
		for (String name : expected) {
			int index = body.indexOf("\"name\":\"" + name + "\"");
			Assertions.assertTrue(index > previous,
					() -> "Expected canonical ordered name " + name + " in " + body);
			previous = index;
		}
		for (String name : completeCatalog)
			if (!expected.contains(name))
				Assertions.assertFalse(body.contains("\"name\":\"" + name + "\""),
						body);
	}

	private static void assertSuccess(@NonNull HttpResponse<String> response,
			@NonNull String expectedId) {
		assertResponseHeaders(response, 200);
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"resultType\":\"complete\""), response.body());
	}

	private static void assertNeutralFailure(
			@NonNull HttpResponse<String> response, @NonNull String expectedId) {
		assertResponseHeaders(response, 400);
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ expectedId + "\",\"error\":{\"code\":-32602,"
				+ "\"message\":\"Invalid params\"}}", response.body());
	}

	private static void assertInternalError(
			@NonNull HttpResponse<String> response, @NonNull String expectedId) {
		assertResponseHeaders(response, 500);
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ expectedId + "\",\"error\":{\"code\":-32603,"
				+ "\"message\":\"Internal error\"}}", response.body());
	}

	private static void assertRateLimited(
			@NonNull HttpResponse<String> response, @NonNull String expectedId,
			@NonNull Duration expectedRetryAfter) {
		assertResponseHeaders(response, 429);
		Assertions.assertEquals(Long.toString(expectedRetryAfter.toSeconds()),
				response.headers().firstValue("Retry-After").orElseThrow());
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ expectedId + "\",\"error\":{\"code\":-31999,"
				+ "\"message\":\"Rate limited\"}}", response.body());
	}

	private static void assertResponseHeaders(
			@NonNull HttpResponse<String> response, int expectedStatus) {
		Assertions.assertEquals(expectedStatus, response.statusCode(),
				response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
	}

	private enum FailureMode {
		RETURN_NULL,
		THROW
	}

	private record CallerCase(@NonNull String caller,
			@NonNull List<@NonNull String> tools,
			@NonNull List<@NonNull String> prompts) {
	}

	private record RequiredArguments(@NonNull String required) {
	}
}
