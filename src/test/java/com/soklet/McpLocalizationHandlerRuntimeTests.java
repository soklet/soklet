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
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-network evidence for handler-family localization context creation: the
 * exact-class feature carrier, interceptor identity, cursor exposure, and the
 * sanitized empty-throwable context-creation failure path.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpLocalizationHandlerRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/localization/handler";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	void everyHandlerFamilyReceivesTheExactContextAndTheInterceptorSeesIt() {
		AtomicInteger contexts = new AtomicInteger();
		AtomicReference<McpLocalizationContext> created = new AtomicReference<>();
		AtomicReference<Object> handlerObserved = new AtomicReference<>();
		AtomicReference<Object> interceptorObserved = new AtomicReference<>();

		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					contexts.incrementAndGet();
					McpLocalizationContext context = context(Locale.FRENCH);
					created.set(context);
					return context;
				})
				.build();
		McpHandlerInterceptor interceptor = (context, continuation) -> {
			interceptorObserved.set(continuation.getFeatures()
					.find(McpLocalizationContext.class).orElse(null));
			return continuation.proceed();
		};
		McpEndpoint endpoint = endpoint(features -> handlerObserved.set(
				features.find(McpLocalizationContext.class).orElse(null)));

		Capture capture = call(endpoint, localizer, interceptor,
				toolCallRequest("handler-carrier", Set.of()));

		assertEquals(200, capture.statusCode(), capture.body());
		assertTrue(capture.body().contains("tool complete"), capture.body());
		assertEquals(1, contexts.get(),
				"Exactly one context per handler invocation.");
		assertSame(created.get(), handlerObserved.get(),
				"The handler must see the exact provider context.");
		assertSame(created.get(), interceptorObserved.get(),
				"The interceptor must see the identical carrier instance.");
	}

	@Test
	void withoutALocalizerTheFeatureCarrierHasNoLocalizationContext() {
		AtomicReference<Optional<McpLocalizationContext>> observed =
				new AtomicReference<>();
		McpEndpoint endpoint = endpoint(features ->
				observed.set(features.find(McpLocalizationContext.class)));

		Capture capture = call(endpoint, null, null,
				toolCallRequest("no-localizer", Set.of()));

		assertEquals(200, capture.statusCode(), capture.body());
		assertEquals(Optional.empty(), observed.get());
	}

	@Test
	void contextCreationFailureFailsBeforeEntryWithTheSanitizedEmptyError() {
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		List<Throwable> observedThrowables = new CopyOnWriteArrayList<>();

		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					throw new AssertionError("secret-provider-detail");
				})
				.build();
		McpHandlerInterceptor interceptor = (context, continuation) -> {
			interceptorInvocations.incrementAndGet();
			return continuation.proceed();
		};
		McpEndpoint endpoint = endpoint(features ->
				handlerInvocations.incrementAndGet());

		Capture capture = call(endpoint, localizer, interceptor,
				toolCallRequest("creation-failure", Set.of()), observedThrowables);

		assertEquals(500, capture.statusCode(), capture.body());
		assertTrue(capture.body().contains("\"code\":-32603"), capture.body());
		assertTrue(capture.body().contains("\"message\":\"Internal error\""),
				capture.body());
		assertFalse(capture.body().contains("secret-provider-detail"),
				capture.body());
		assertEquals(0, handlerInvocations.get(),
				"Context-creation failure must precede handler entry.");
		assertEquals(0, interceptorInvocations.get(),
				"Context-creation failure must precede interceptor entry.");
		assertEquals(List.of(), observedThrowables,
				"The fixed internal error must carry an empty throwable list.");
	}

	@Test
	void promptAndResourceHandlersAlsoReceiveTheContext() {
		AtomicInteger contexts = new AtomicInteger();
		AtomicReference<Object> promptObserved = new AtomicReference<>();
		AtomicReference<Object> resourceObserved = new AtomicReference<>();

		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					contexts.incrementAndGet();
					return context(Locale.FRENCH);
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("handler-context", "1.0").build())
				.prompt(McpPromptRegistration.withName("context.prompt")
						.handler((request, promptContext, features) -> {
							promptObserved.set(features
									.find(McpLocalizationContext.class).orElse(null));
							return McpCompleteResult.fromPromptOutput(
									McpPromptOutput.fromMessages());
						})
						.build())
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("handler://text"), "text")
						.handler((request, resource, features) -> {
							resourceObserved.set(features
									.find(McpLocalizationContext.class).orElse(null));
							return McpCompleteResult.fromResourceOutput(
									McpResourceOutput.builder()
											.content(McpTextResourceContents
													.withUriAndText(URI.create(
															"handler://text"),
															"resource complete")
													.build())
											.build());
						})
						.build())
				.build();

		Capture prompt = call(endpoint, localizer, null, request("prompts/get",
				"prompt-context", "context.prompt",
				",\"name\":\"context.prompt\",\"arguments\":{}", Set.of()));
		assertEquals(200, prompt.statusCode(), prompt.body());
		assertTrue(promptObserved.get() instanceof McpLocalizationContext);

		Capture resource = call(endpoint, localizer, null,
				request("resources/read", "resource-context", "handler://text",
						",\"uri\":\"handler://text\"", Set.of()));
		assertEquals(200, resource.statusCode(), resource.body());
		assertTrue(resourceObserved.get() instanceof McpLocalizationContext);
		assertEquals(2, contexts.get());
	}

	@Test
	void customResourceListExposesThePresentEmptyCursorDistinctly() {
		List<Optional<String>> observedCursors = new CopyOnWriteArrayList<>();
		List<Locale.LanguageRange> observedRanges = new CopyOnWriteArrayList<>();

		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					observedCursors.add(request.getResourceListCursor());
					observedRanges.addAll(request.getLanguageRanges());
					return context(Locale.FRENCH);
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("handler-cursor", "1.0").build())
				.resourceListHandler((request, list, features) -> {
					assertTrue(features.find(McpLocalizationContext.class)
							.isPresent());
					return McpResourcePage.builder().build();
				})
				.build();

		Capture absent = call(endpoint, localizer, null, request("resources/list",
				"cursor-absent", null, "", Set.of("fr-CA;q=0.8, en-US")));
		assertEquals(200, absent.statusCode(), absent.body());

		Capture presentEmpty = call(endpoint, localizer, null,
				request("resources/list", "cursor-empty", null,
						",\"cursor\":\"\"", Set.of()));
		assertEquals(200, presentEmpty.statusCode(), presentEmpty.body());

		assertEquals(List.of(Optional.<String>empty(), Optional.of("")),
				observedCursors,
				"Absence and a present empty cursor must stay distinct.");
		assertEquals(List.of("en-us", "fr-ca"), observedRanges.stream()
				.map(Locale.LanguageRange::getRange).toList(),
				"Handler-family providers see the bounded ordered view.");
	}

	private static McpLocalizationContext context(Locale locale) {
		return McpLocalizationContext.withLocale(locale)
				.localizer(text -> McpLocalizationResult.useDefaultText())
				.build();
	}

	@FunctionalInterface
	private interface FeaturesProbe {
		void observe(McpInvocationFeatures features);
	}

	private static McpEndpoint endpoint(FeaturesProbe probe) {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("handler-context", "1.0").build())
				.tool(McpToolRegistration.withName("context.tool")
						.jsonArguments()
						.handler((request, arguments, features) -> {
							probe.observe(features);
							return McpCompleteResult.fromToolText("tool complete");
						})
						.build())
				.build();
	}

	private record Capture(int statusCode, String body) {}

	private static Capture call(McpEndpoint endpoint, McpLocalizer localizer,
			McpHandlerInterceptor interceptor, Request request) {
		return call(endpoint, localizer, interceptor, request,
				new CopyOnWriteArrayList<>());
	}

	private static Capture call(McpEndpoint endpoint, McpLocalizer localizer,
			McpHandlerInterceptor interceptor, Request request,
			List<Throwable> observedThrowables) {
		AtomicReference<Capture> captured = new AtomicReference<>();

		SokletSimulator.run(transports -> {
			McpServer.Builder builder = transports.newMcpServerBuilder(0)
					.host(LOOPBACK)
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(
							List.of(endpoint)))
					.admissionController(McpAdmissionController.acceptAllInstance())
					.requestRateLimiter(context -> McpRateLimitDecision.allowed())
					.toolRateLimiter(context -> McpRateLimitDecision.allowed())
					.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
					.allowedHosts(Set.of(LOOPBACK));

			if (localizer != null)
				builder.localizer(localizer);
			if (interceptor != null)
				builder.handlerInterceptor(interceptor);

			return SokletConfig.withMcpServer(builder.build())
					.resourceMethodResolver(
							ResourceMethodResolver.fromMethods(Set.of()))
					.lifecycleObservers(List.of(new LifecycleObserver() {
						@Override
						public void didFinishMcpRequestHandling(
								@NonNull McpRequestContext context,
								@NonNull McpRequestOutcome requestOutcome,
								@Nullable McpJsonRpcError error,
								@NonNull Duration duration,
								@NonNull List<@NonNull Throwable> throwables) {
							observedThrowables.addAll(throwables);
						}
					}))
					.build();
		}, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(request);

			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT)
						.orElseThrow(() -> new AssertionError("Timed out."));
				captured.set(new Capture(response.getStatusCode(),
						new String(response.getBody().orElseThrow(),
								StandardCharsets.UTF_8)));
				simulation.awaitCompletion(WAIT);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		return captured.get();
	}

	private static Request toolCallRequest(String id,
			Set<String> acceptLanguage) {
		return request("tools/call", id, "context.tool",
				",\"name\":\"context.tool\",\"arguments\":{}", acceptLanguage);
	}

	private static Request request(String method, String id, String operationName,
			String paramsSuffix, Set<String> acceptLanguage) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ paramsSuffix + "}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));

		if (operationName != null)
			headers.put("Mcp-Name", Set.of(operationName));
		if (!acceptLanguage.isEmpty())
			headers.put("Accept-Language", acceptLanguage);

		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}
}
