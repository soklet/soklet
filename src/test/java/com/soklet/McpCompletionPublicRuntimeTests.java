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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.CompletionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real-listener coverage for Completion's public registration path. */
@Timeout(60)
public class McpCompletionPublicRuntimeTests {
	private static final String HOST = "127.0.0.1";
	private static final String TEMPLATE = "catalog://items/{sku}";

	@Test
	public void completionUsesExactRoutesAndNeutralErrors() throws Exception {
		AtomicInteger requestCharges = new AtomicInteger();
		AtomicInteger interceptorEntries = new AtomicInteger();
		AtomicInteger promptEntries = new AtomicInteger();
		AtomicInteger resourceEntries = new AtomicInteger();
		AtomicBoolean rejectNext = new AtomicBoolean();
		AtomicReference<McpCompletionContext.Prompt> promptContext =
				new AtomicReference<>();
		AtomicReference<McpCompletionContext.Resource> resourceContext =
				new AtomicReference<>();
		McpPromptRegistration prompt = prompt("visible", true,
				(request, context, features) -> {
					promptEntries.incrementAndGet();
					assertTrue(features.find(McpTaskControl.class).isEmpty());
					assertFalse(features.getCancelationToken().isCanceled());
					promptContext.set((McpCompletionContext.Prompt) context);
					return McpArgumentCompletionResult.withValues(List.of("α", "a"))
							.total(2L).build();
				});
		McpPromptRegistration noCompleter = prompt("plain", false, null);
		McpPromptRegistration hidden = prompt("hidden", true,
				(request, context, features) -> {
					fail("Hidden completer must not run");
					return McpArgumentCompletionResult.fromValues(List.of());
				});
		McpResourceRegistration template = McpResourceRegistration
				.withUriTemplateAndName(TEMPLATE, "Items")
				.handler((request, resource, features) -> text(resource.getUri()))
				.completionHandler((request, context, features) -> {
					resourceEntries.incrementAndGet();
					assertTrue(features.find(McpTaskControl.class).isEmpty());
					resourceContext.set((McpCompletionContext.Resource) context);
					return McpArgumentCompletionResult.withValues(List.of("sku-1"))
							.hasMore(false).build();
				})
				.build();
		McpResourceRegistration noCompleterTemplate = McpResourceRegistration
				.withUriTemplateAndName("catalog://other/{sku}", "Other")
				.handler((request, resource, features) -> text(resource.getUri()))
				.build();
		McpResourceRegistration exact = McpResourceRegistration
				.withUriAndName(URI.create("catalog://items/sku-1"), "Exact")
				.handler((request, resource, features) -> text(resource.getUri()))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("completion-test", "4.0.0")
						.build())
				.addPrompt(prompt).addPrompt(noCompleter).addPrompt(hidden)
				.addResource(template).addResource(noCompleterTemplate)
				.addResource(exact).build();
		McpServer server = McpServer.withPort(0).host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators(
						(request, tool, features) -> true,
						(request, registration, features) ->
								!"hidden".equals(registration.getName())))
				.requestRateLimiter(context -> {
					if (context.getOperationType()
							== McpOperationType.COMPLETION_COMPLETE) {
						requestCharges.incrementAndGet();
						if (rejectNext.compareAndSet(true, false))
							return McpRateLimitDecision.denied(Duration.ofSeconds(1));
					}
					return McpRateLimitDecision.allowed();
				})
				.handlerInterceptor((request, features, continuation) -> {
					interceptorEntries.incrementAndGet();
					return continuation.proceed();
				})
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST)).build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			String discover = send(port, "server/discover", "", "discover").body();
			assertTrue(discover.contains("\"completions\":{}"), discover);

			String promptResult = send(port, "completion/complete",
					params(true, "visible", "subject", " α",
							"\"context\":{\"arguments\":{\"tone\":\"soft\"}}"),
					"prompt").body();
			assertTrue(promptResult.contains("\"values\":[\"α\",\"a\"]"),
					promptResult);
			assertTrue(promptResult.contains("\"total\":2"), promptResult);
			assertFalse(promptResult.contains("\"hasMore\""), promptResult);
			assertSame(prompt, promptContext.get().getPromptRegistration());
			assertEquals(" α", promptContext.get().getArgumentValue());
			assertEquals(Map.of("tone", "soft"),
					promptContext.get().getContextArguments());
			assertThrows(UnsupportedOperationException.class,
					() -> promptContext.get().getContextArguments().put("x", "y"));
			assertFalse(promptContext.get().toString().contains(" α"));
			assertFalse(promptContext.get().toString().contains("soft"));
			assertFalse(promptContext.get().toString().contains("visible"));

			String resourceResult = send(port, "completion/complete",
					params(false, TEMPLATE, "sku", "SENSITIVE-PARTIAL", null),
					"resource").body();
			assertTrue(resourceResult.contains("\"values\":[\"sku-1\"]"),
					resourceResult);
			assertTrue(resourceResult.contains("\"hasMore\":false"),
					resourceResult);
			assertFalse(resourceResult.contains("\"total\""), resourceResult);
			assertSame(template, resourceContext.get().getResourceRegistration());
			assertEquals(Map.of(), resourceContext.get().getContextArguments());
			assertFalse(resourceContext.get().toString()
					.contains("SENSITIVE-PARTIAL"));
			assertFalse(resourceContext.get().toString().contains(TEMPLATE));

			String empty = send(port, "completion/complete",
					params(true, "plain", "subject", "x", null), "empty")
					.body();
			assertTrue(empty.contains("\"values\":[]"), empty);
			assertEquals(2, interceptorEntries.get(),
					"An unconfigured prompt must not invoke an unrelated handler.");

			String hiddenError = send(port, "completion/complete",
					params(true, "hidden", "undeclared", "secret", null), "err")
					.body();
			String unknownPromptError = send(port, "completion/complete",
					params(true, "unknown", "undeclared", "secret", null), "err")
					.body();
			assertEquals(hiddenError, unknownPromptError);
			assertTrue(hiddenError.contains("\"code\":-32602"), hiddenError);

			String exactError = send(port, "completion/complete",
					params(false, "catalog://items/sku-1", "sku", "s", null), "err")
					.body();
			String noCompleterError = send(port, "completion/complete",
					params(false, "catalog://other/{sku}", "sku", "s", null), "err")
					.body();
			String unknownError = send(port, "completion/complete",
					params(false, "catalog://unknown/{sku}", "sku", "s", null), "err")
					.body();
			String expandedError = send(port, "completion/complete",
					params(false, "catalog://items/sku-2", "sku", "s", null), "err")
					.body();
			assertEquals(exactError, noCompleterError);
			assertEquals(exactError, unknownError);
			assertEquals(exactError, expandedError);
			String invalidPromptArgument = send(port, "completion/complete",
					params(true, "visible", "undeclared", "x", null), "bad-prompt")
					.body();
			assertTrue(invalidPromptArgument.contains("\"code\":-32602"),
					invalidPromptArgument);
			String invalidContext = send(port, "completion/complete",
					params(false, TEMPLATE, "sku", "s",
							"\"context\":{\"arguments\":{\"unknown\":\"private\"}}"),
					"bad-context").body();
			assertTrue(invalidContext.contains("\"code\":-32602"),
					invalidContext);
			String malformedContext = send(port, "completion/complete",
					params(true, "visible", "subject", "s",
							"\"context\":{\"arguments\":{\"tone\":7}}"),
					"malformed-context").body();
			assertTrue(malformedContext.contains("\"code\":-32602"),
					malformedContext);
			rejectNext.set(true);
			HttpResponse<String> denied = send(port, "completion/complete",
					params(true, "visible", "subject", "s", null), "denied");
			assertEquals(429, denied.statusCode(), denied.body());
			assertTrue(denied.body().contains("\"code\":-31999"),
					denied.body());
			assertEquals(12, requestCharges.get(),
					"Each admitted Completion request must charge exactly once.");
			assertEquals(1, promptEntries.get());
			assertEquals(1, resourceEntries.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void configuredCompleterRequiresRequestLimiter() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("completion-test", "4.0.0")
						.build())
				.addPrompt(prompt("enabled", true,
						(request, context, features) ->
								McpArgumentCompletionResult.fromValues(List.of())))
				.build();
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> McpServer.withPort(0).host(HOST)
						.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
						.build());
		assertTrue(error.getMessage().contains("request rate limiter"));
	}

	@Test
	public void noCompleterDoesNotAdvertiseOrRouteCompletion() throws Exception {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("completion-test", "4.0.0")
						.build())
				.addPrompt(prompt("plain", false, null)).build();
		McpServer server = McpServer.withPort(0).host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST)).build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			String discover = send(port, "server/discover", "", "discover").body();
			assertFalse(discover.contains("\"completions\""), discover);
			HttpResponse<String> completion = send(port, "completion/complete",
					params(true, "plain", "subject", "x", null), "no-support");
			assertEquals(404, completion.statusCode(), completion.body());
			assertTrue(completion.body().contains("\"code\":-32601"),
					completion.body());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void erasedCompletionPlanDoesNotRenderReferenceOrArguments() {
		CompletionPlan plan = new CompletionPlan(
				CompletionPlan.ReferenceType.RESOURCE,
				"catalog://private/{secret}", List.of("secret"),
				invocation -> McpArgumentCompletionResult.fromValues(List.of()));
		assertFalse(plan.toString().contains("catalog://private"));
		assertFalse(plan.toString().contains("secret"));
	}

	@Test
	public void wrongOperationInterceptorResultFailsClosed() throws Exception {
		AtomicInteger handlerEntries = new AtomicInteger();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("completion-test", "4.0.0")
						.build())
				.addPrompt(prompt("visible", true,
						(request, context, features) -> {
							handlerEntries.incrementAndGet();
							return McpArgumentCompletionResult.fromValues(List.of("x"));
						}))
				.build();
		McpServer server = McpServer.withPort(0).host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.handlerInterceptor((request, features, continuation) ->
					McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
							.addMessage(McpPromptMessage.fromUserContent(
									McpTextContent.fromText("wrong"))).build()))
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST)).build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			HttpResponse<String> response = send(port, "completion/complete",
					params(true, "visible", "subject", "s", null), "wrong");
			assertEquals(500, response.statusCode(), response.body());
			assertFalse(response.body().contains(
					"An MCP Completion interceptor"), response.body());
			assertEquals(0, handlerEntries.get());
		} finally {
			soklet.close();
		}
	}

	private static McpPromptRegistration prompt(String name, boolean completer,
			McpCompletionHandler handler) {
		McpPromptRegistration.Builder builder = McpPromptRegistration
				.withName(name)
				.handler((request, prompt, features) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
								.addMessage(McpPromptMessage.fromUserContent(
										McpTextContent.fromText("unused"))).build()))
				.addArgument(McpPromptArgumentDeclaration.withName("subject").build())
				.addArgument(McpPromptArgumentDeclaration.withName("tone").build());
		if (completer)
			builder.completionHandler(handler);
		return builder.build();
	}

	private static McpCompleteResult text(URI uri) {
		return McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(
				McpTextResourceContents.withUriAndText(uri, "unused").build())
				.build());
	}

	private static String params(boolean prompt, String reference,
			String argumentName, String value, String context) {
		return ",\"ref\":{\"type\":\"ref/"
				+ (prompt ? "prompt\",\"name\"" : "resource\",\"uri\"")
				+ ":\"" + reference + "\"},\"argument\":{\"name\":\""
				+ argumentName + "\",\"value\":\"" + value + "\"}"
				+ (context == null ? "" : "," + context);
	}

	private static HttpResponse<String> send(int port, String method,
			String parameters, String id) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ parameters + "}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + HOST + ":" + port + "/mcp"))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", "2026-07-28")
				.header("Mcp-Method", method)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newHttpClient().send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}
}
