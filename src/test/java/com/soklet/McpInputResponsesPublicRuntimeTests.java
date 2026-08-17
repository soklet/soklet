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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Black-box real-listener coverage for inbound MCP input responses.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpInputResponsesPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "retry.tool";
	private static final String PROMPT_NAME = "retry.prompt";
	private static final URI RESOURCE_URI = URI.create("test://retry/resource");
	private static final String ALL_INPUT_CAPABILITIES =
			"{\"elicitation\":{\"form\":{}},\"sampling\":{},\"roots\":{}}";
	private static final String VALID_INPUT_RESPONSES = """
			{"approval":{"action":"accept","content":{"name":"Alice"},
			 "com.example/responseExtension":"preserved"},
			 "sample":{"role":"assistant","content":{"type":"text",
			 "text":"Paris"},"model":"fixture-model","stopReason":"endTurn",
			 "com.example/sampleExtension":true},
			 "roots":{"roots":[{"uri":"file:///tmp/project","name":"Project",
			 "com.example/rootExtension":1}],"com.example/rootsExtension":false},
			 "extra":{"action":"decline","com.example/extraExtension":[true]}}
			""".replaceAll("\\s+", "");

	@Test
	public void validRetriesReachEveryHandlerThroughTheExactObservedContext()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver(3);
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		Map<String, McpRequestContext> handlerContexts =
				new ConcurrentHashMap<>();
		Map<String, McpRequestContext> interceptorContexts =
				new ConcurrentHashMap<>();
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration sampling = McpInputRequestDeclaration
				.fromSampling(Set.of(), McpInputRequirement.CONDITIONAL);
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					assertExactInputResponses(request);
					Assertions.assertSame(interceptorContexts.get(TOOL_NAME),
							request);
					handlerContexts.put(TOOL_NAME, request);
					return McpCompleteResult.fromToolText("tool retry complete");
				})
				.mayRequestInput(form, sampling, roots)
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName(PROMPT_NAME)
				.handler((request, get, features) -> {
					assertExactInputResponses(request);
					Assertions.assertSame(interceptorContexts.get(PROMPT_NAME),
							request);
					handlerContexts.put(PROMPT_NAME, request);
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages(
									McpPromptMessage.fromUserContent(
											McpTextContent.fromText(
													"prompt retry complete"))));
				})
				.mayRequestInput(form, sampling, roots)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "retry resource")
				.handler((request, read, features) -> {
					assertExactInputResponses(request);
					Assertions.assertSame(interceptorContexts.get(
							RESOURCE_URI.toString()), request);
					handlerContexts.put(RESOURCE_URI.toString(), request);
					return McpCompleteResult.fromResourceOutput(
							McpResourceOutput.builder()
									.content(McpTextResourceContents.withUriAndText(
											RESOURCE_URI, "resource retry complete")
											.build())
									.build());
				})
				.mayRequestInput(form, sampling, roots)
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofHours(1)))
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, continuation) -> {
					String operation = context.getOperationName().orElseThrow();
					assertExactInputResponses(context);
					Assertions.assertSame(observer.startedContexts.get(operation),
							context);
					interceptorContexts.put(operation, context);
					return continuation.proceed();
				})
				.build();
		Soklet soklet = managedSoklet(server, observer, collector);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> toolResponse = send(port, "tool-retry",
					"tools/call", TOOL_NAME,
					",\"name\":\"" + TOOL_NAME + "\",\"arguments\":{},"
							+ "\"inputResponses\":" + VALID_INPUT_RESPONSES,
					ALL_INPUT_CAPABILITIES);
			HttpResponse<String> promptResponse = send(port, "prompt-retry",
					"prompts/get", PROMPT_NAME,
					",\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{},"
							+ "\"inputResponses\":" + VALID_INPUT_RESPONSES,
					ALL_INPUT_CAPABILITIES);
			HttpResponse<String> resourceResponse = send(port, "resource-retry",
					"resources/read", RESOURCE_URI.toString(),
					",\"uri\":\"" + RESOURCE_URI + "\",\"inputResponses\":"
							+ VALID_INPUT_RESPONSES
							+ ",\"com.example/futureParameter\":{\"preserved\":true}",
					ALL_INPUT_CAPABILITIES);
			observer.awaitFinished();

			assertComplete(toolResponse, "tool-retry");
			assertComplete(promptResponse, "prompt-retry");
			assertComplete(resourceResponse, "resource-retry");
			Assertions.assertTrue(toolResponse.body().contains(
					"\"text\":\"tool retry complete\""), toolResponse.body());
			Assertions.assertTrue(promptResponse.body().contains(
					"\"text\":\"prompt retry complete\""),
					promptResponse.body());
			Assertions.assertTrue(resourceResponse.body().contains(
					"\"text\":\"resource retry complete\""),
					resourceResponse.body());
			Assertions.assertTrue(resourceResponse.body().contains(
					"\"ttlMs\":0"), resourceResponse.body());
			Assertions.assertTrue(resourceResponse.body().contains(
					"\"cacheScope\":\"private\""), resourceResponse.body());
			Assertions.assertFalse(resourceResponse.body().contains(
					"\"ttlMs\":3600000"), resourceResponse.body());
			Assertions.assertEquals(Set.of(TOOL_NAME, PROMPT_NAME,
					RESOURCE_URI.toString()), handlerContexts.keySet());
			for (Map.Entry<String, McpRequestContext> entry
					: handlerContexts.entrySet()) {
				Assertions.assertSame(observer.startedContexts.get(entry.getKey()),
						entry.getValue());
				Assertions.assertSame(interceptorContexts.get(entry.getKey()),
						entry.getValue());
			}
			Assertions.assertEquals(3, observer.starts.get());
			Assertions.assertEquals(3, observer.finishes.get());
			Assertions.assertEquals(3, collector.started.get());
			Assertions.assertEquals(3, collector.finished.get());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void inputResponsesAreValidUnderNoneAndEmptyStillMarksResourceRetry()
			throws Exception {
		AtomicInteger toolInvocations = new AtomicInteger();
		AtomicInteger resourceInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("none.tool")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					toolInvocations.incrementAndGet();
					Assertions.assertTrue(request.getRequestState().isEmpty());
					McpJsonObject response = Assertions.assertInstanceOf(
							McpJsonObject.class,
							request.getInputResponses().find("response")
									.orElseThrow());
					Assertions.assertEquals("decline", string(response, "action"));
					return McpCompleteResult.fromToolText("none complete");
				})
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "none resource")
				.handler((request, read, features) -> {
					resourceInvocations.incrementAndGet();
					Assertions.assertSame(McpInputResponses.emptyInstance(),
							request.getInputResponses());
					Assertions.assertTrue(request.getRequestState().isEmpty());
					return McpCompleteResult.fromResourceOutput(
							McpResourceOutput.builder()
									.content(McpTextResourceContents.withUriAndText(
											RESOURCE_URI, "empty retry complete")
											.build())
									.build());
				})
				.cachePolicy(McpCachePolicy.fromPublicTimeToLive(
						Duration.ofHours(1)))
				.build();
		Assertions.assertEquals(McpRequestStateMode.NONE,
				tool.getRequestStateMode());
		Assertions.assertEquals(McpRequestStateMode.NONE,
				resource.getRequestStateMode());
		McpEndpoint endpoint = endpointBuilder()
				.tool(tool)
				.resource(resource)
				.build();
		McpServer server = serverBuilder(endpoint).build();

		try {
			server.start();
			int port = boundPort(server);
			HttpResponse<String> toolResponse = send(port, "none-tool",
					"tools/call", "none.tool",
					",\"name\":\"none.tool\",\"arguments\":{},"
							+ "\"inputResponses\":{\"response\":{"
							+ "\"action\":\"decline\"}}", "{}");
			HttpResponse<String> resourceResponse = send(port, "empty-resource",
					"resources/read", RESOURCE_URI.toString(),
					",\"uri\":\"" + RESOURCE_URI
							+ "\",\"inputResponses\":{}", "{}");

			assertComplete(toolResponse, "none-tool");
			assertComplete(resourceResponse, "empty-resource");
			Assertions.assertTrue(toolResponse.body().contains(
					"\"text\":\"none complete\""), toolResponse.body());
			Assertions.assertTrue(resourceResponse.body().contains(
					"\"ttlMs\":0"), resourceResponse.body());
			Assertions.assertTrue(resourceResponse.body().contains(
					"\"cacheScope\":\"private\""), resourceResponse.body());
			Assertions.assertEquals(1, toolInvocations.get());
			Assertions.assertEquals(1, resourceInvocations.get());
		} finally {
			server.stop();
		}
	}

	@Test
	public void missingExpectedKeyIsReRequestedAndExtraResponsesRemainAvailable()
			throws Exception {
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpJsonObject params = McpJsonObject.builder()
				.put("message", "What is your name?")
				.put("requestedSchema", McpJsonObject.builder()
						.put("type", "object")
						.put("properties", McpJsonObject.builder()
								.put("name", McpJsonObject.builder()
										.put("type", "string")
										.build())
								.build())
						.build())
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("retry.missing")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					if (request.getInputResponses().find("approval").isEmpty()) {
						Assertions.assertTrue(request.getInputResponses()
								.find("wrong-key").isPresent());
						return McpInputRequiredResult.builder()
								.inputRequest("approval", McpInputRequest
										.fromDeclaration(form, params))
								.build();
					}
					Assertions.assertTrue(request.getInputResponses()
							.find("ignored-extra").isPresent());
					return McpCompleteResult.fromToolText("accepted");
				})
				.mayRequestInput(form)
				.build();
		McpEndpoint endpoint = endpointBuilder().tool(tool).build();
		McpServer server = serverBuilder(endpoint).build();

		try {
			server.start();
			int port = boundPort(server);
			HttpResponse<String> missing = send(port, "missing", "tools/call",
					"retry.missing", ",\"name\":\"retry.missing\","
							+ "\"arguments\":{},\"inputResponses\":{"
							+ "\"wrong-key\":{\"action\":\"decline\"}}",
					ALL_INPUT_CAPABILITIES);
			assertInputRequired(missing, "missing");
			Assertions.assertTrue(missing.body().contains(
					"\"inputRequests\":{\"approval\":"), missing.body());
			Assertions.assertFalse(missing.body().contains("wrong-key"),
					missing.body());

			HttpResponse<String> complete = send(port, "complete", "tools/call",
					"retry.missing", ",\"name\":\"retry.missing\","
							+ "\"arguments\":{},\"inputResponses\":{"
							+ "\"approval\":{\"action\":\"accept\","
							+ "\"content\":{\"name\":\"Alice\"}},"
							+ "\"ignored-extra\":{\"roots\":[]}}",
					ALL_INPUT_CAPABILITIES);
			assertComplete(complete, "complete");
			Assertions.assertTrue(complete.body().contains(
					"\"text\":\"accepted\""), complete.body());
			Assertions.assertEquals(2, handlerInvocations.get());
		} finally {
			server.stop();
		}
	}

	@Test
	public void malformedResponsesAndRequestStateFailBeforeApplicationPolicy()
			throws Exception {
		AtomicInteger admissionInvocations = new AtomicInteger();
		AtomicInteger requestLimiterInvocations = new AtomicInteger();
		AtomicInteger toolLimiterInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver(0);
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("must not run");
				})
				.mayRequestInput(McpInputRequestDeclaration.fromRoots(
						McpInputRequirement.REQUIRED))
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName(PROMPT_NAME)
				.handler((request, get, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages());
				})
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "retry resource")
				.handler((request, read, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromResourceOutput(
							McpResourceOutput.builder()
									.content(McpTextResourceContents.withUriAndText(
											RESOURCE_URI, "must not run").build())
									.build());
				})
				.build();
		McpEndpoint endpoint = endpointBuilder()
				.tool(tool)
				.prompt(prompt)
				.resource(resource)
				.build();
		McpServer server = serverBuilder(endpoint)
				.admissionController(context -> {
					admissionInvocations.incrementAndGet();
					return McpAdmissionDecision.accepted();
				})
				.requestRateLimiter(context -> {
					requestLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.toolRateLimiter(context -> {
					toolLimiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.toolOutputSanitizer((request, toolName, rawArguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				})
				.build();
		Soklet soklet = managedSoklet(server, observer, collector);

		try {
			soklet.start();
			int port = boundPort(server);
			List<HttpResponse<String>> responses = List.of(
					send(port, "null-responses", "tools/call", TOOL_NAME,
							",\"name\":\"" + TOOL_NAME + "\",\"arguments\":{},"
									+ "\"inputResponses\":null", "{}"),
					send(port, "array-responses", "prompts/get", PROMPT_NAME,
							",\"name\":\"" + PROMPT_NAME + "\",\"arguments\":{},"
									+ "\"inputResponses\":[]", "{}"),
					send(port, "invalid-member", "resources/read",
							RESOURCE_URI.toString(), ",\"uri\":\"" + RESOURCE_URI
									+ "\",\"inputResponses\":{\"secret-key\":{"
									+ "\"secret\":\"INPUT-RESPONSE-SECRET\"}}", "{}"),
					send(port, "state-under-none", "tools/call", TOOL_NAME,
							",\"name\":\"" + TOOL_NAME + "\",\"arguments\":{},"
									+ "\"requestState\":\"REQUEST-STATE-SECRET\"", "{}"));

			List<String> ids = List.of("null-responses", "array-responses",
					"invalid-member", "state-under-none");
			for (int index = 0; index < responses.size(); index++)
				assertInvalidParams(responses.get(index), ids.get(index));
			for (HttpResponse<String> response : responses) {
				Assertions.assertFalse(response.body().contains(
						"INPUT-RESPONSE-SECRET"), response.body());
				Assertions.assertFalse(response.body().contains(
						"REQUEST-STATE-SECRET"), response.body());
			}
			assertAllZero(admissionInvocations, requestLimiterInvocations,
					toolLimiterInvocations, handlerInvocations,
					sanitizerInvocations, observer.starts, observer.finishes,
					collector.started, collector.finished);
		} finally {
			soklet.stop();
		}
	}

	private static void assertExactInputResponses(
			@NonNull McpRequestContext request) {
		McpInputResponses responses = request.getInputResponses();
		Assertions.assertEquals(List.of("approval", "sample", "roots", "extra"),
				new ArrayList<>(responses.asMap().keySet()));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> responses.asMap().clear());
		Assertions.assertTrue(request.getRequestState().isEmpty());

		McpJsonObject approval = Assertions.assertInstanceOf(McpJsonObject.class,
				responses.find("approval").orElseThrow());
		Assertions.assertEquals("accept", string(approval, "action"));
		Assertions.assertEquals("preserved",
				string(approval, "com.example/responseExtension"));
		McpJsonObject approvalContent = object(approval, "content");
		Assertions.assertEquals("Alice", string(approvalContent, "name"));

		McpJsonObject sample = Assertions.assertInstanceOf(McpJsonObject.class,
				responses.find("sample").orElseThrow());
		Assertions.assertEquals("assistant", string(sample, "role"));
		Assertions.assertEquals("fixture-model", string(sample, "model"));
		Assertions.assertEquals(new McpJsonBoolean(true),
				sample.find("com.example/sampleExtension").orElseThrow());
		McpJsonObject sampleContent = object(sample, "content");
		Assertions.assertEquals("text", string(sampleContent, "type"));
		Assertions.assertEquals("Paris", string(sampleContent, "text"));

		McpJsonObject roots = Assertions.assertInstanceOf(McpJsonObject.class,
				responses.find("roots").orElseThrow());
		McpJsonArray rootValues = Assertions.assertInstanceOf(McpJsonArray.class,
				roots.find("roots").orElseThrow());
		Assertions.assertEquals(1, rootValues.getElements().size());
		McpJsonObject root = Assertions.assertInstanceOf(McpJsonObject.class,
				rootValues.getElements().get(0));
		Assertions.assertEquals("file:///tmp/project", string(root, "uri"));
		Assertions.assertEquals("Project", string(root, "name"));

		McpJsonObject extra = Assertions.assertInstanceOf(McpJsonObject.class,
				responses.find("extra").orElseThrow());
		Assertions.assertEquals("decline", string(extra, "action"));
		Assertions.assertSame(responses.asMap().get("extra"),
				responses.find("extra").orElseThrow());
	}

	@NonNull
	private static McpJsonObject object(@NonNull McpJsonObject parent,
			@NonNull String name) {
		return Assertions.assertInstanceOf(McpJsonObject.class,
				parent.find(name).orElseThrow());
	}

	@NonNull
	private static String string(@NonNull McpJsonObject parent,
			@NonNull String name) {
		return Assertions.assertInstanceOf(McpJsonString.class,
				parent.find(name).orElseThrow()).value();
	}

	private static McpEndpoint.@NonNull Builder endpointBuilder() {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"input-responses-public-runtime-test",
						"3.6.0-SNAPSHOT").build());
	}

	private static McpServer.@NonNull Builder serverBuilder(
			@NonNull McpEndpoint endpoint) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server,
			@NonNull LifecycleObserver observer,
			@NonNull MetricsCollector collector) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.metricsCollector(collector)
				.build());
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String requestId,
			@NonNull String method, @NonNull String operationName,
			@NonNull String additionalParameters,
			@NonNull String clientCapabilities) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ clientCapabilities + "}" + additionalParameters + "}}";
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(HttpRequest.newBuilder()
						.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
						.timeout(Duration.ofSeconds(5))
						.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
						.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
						.header("MCP-Protocol-Version", PROTOCOL_VERSION)
						.header("Mcp-Method", method)
						.header("Mcp-Name", operationName)
						.POST(HttpRequest.BodyPublishers.ofString(
								body, StandardCharsets.UTF_8))
						.build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertComplete(@NonNull HttpResponse<String> response,
			@NonNull String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"resultType\":\"complete\""), response.body());
	}

	private static void assertInputRequired(
			@NonNull HttpResponse<String> response, @NonNull String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"resultType\":\"input_required\""), response.body());
	}

	private static void assertInvalidParams(
			@NonNull HttpResponse<String> response, @NonNull String expectedId) {
		Assertions.assertEquals(400, response.statusCode(), response.body());
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ expectedId + "\",\"error\":{\"code\":-32602,"
				+ "\"message\":\"Invalid params\"}}", response.body());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
	}

	private static void assertAllZero(@NonNull AtomicInteger... counters) {
		for (AtomicInteger counter : counters)
			Assertions.assertEquals(0, counter.get());
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		private final AtomicInteger starts = new AtomicInteger();
		private final AtomicInteger finishes = new AtomicInteger();
		private final Map<String, McpRequestContext> startedContexts =
				new ConcurrentHashMap<>();
		private final CountDownLatch finished;

		private RecordingLifecycleObserver(int expectedFinishes) {
			this.finished = new CountDownLatch(expectedFinishes);
		}

		@Override
		public void didStartMcpRequestHandling(
				@NonNull McpRequestContext context) {
			context.getOperationName().ifPresent(operation ->
					this.startedContexts.put(operation, context));
			this.starts.incrementAndGet();
		}

		@Override
		public void didFinishMcpRequestHandling(
				@NonNull McpRequestContext context,
				@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error,
				@NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			Assertions.assertSame(this.startedContexts.get(
					context.getOperationName().orElseThrow()), context);
			Assertions.assertEquals(McpRequestOutcome.COMPLETE, outcome);
			Assertions.assertNull(error);
			Assertions.assertTrue(throwables.isEmpty());
			this.finishes.incrementAndGet();
			this.finished.countDown();
		}

		private void awaitFinished() throws InterruptedException {
			Assertions.assertTrue(this.finished.await(5, TimeUnit.SECONDS),
					"The MCP request finish callbacks did not arrive.");
		}
	}

	private static final class RecordingMetricsCollector
			implements MetricsCollector {
		private final AtomicInteger started = new AtomicInteger();
		private final AtomicInteger finished = new AtomicInteger();
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(event);
			if (event instanceof McpMetricsEvent.RequestStarted)
				this.started.incrementAndGet();
			if (event instanceof McpMetricsEvent.RequestFinished)
				this.finished.incrementAndGet();
		}
	}
}
