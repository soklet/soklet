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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public MCP request observation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpRequestObservationPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "observation.echo";

	@Test
	public void admittedDiscoveryPublishesLifecycleAndMetricsWithoutInterception()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpEndpoint endpoint = endpointBuilder("discovery-observation-test").build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, invocation) -> {
					interceptorInvocations.incrementAndGet();
					return invocation.invoke();
				})
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port, discoverRequest("discover"),
					"server/discover", Optional.empty());
			observer.awaitFinished();

			assertSuccess(response, "discover");
			Assertions.assertEquals(0, interceptorInvocations.get());
			assertSingleCompleteLifecycle(observer, endpoint, "server/discover",
					Optional.empty(), "discover");
			assertSingleCompleteMetrics(collector, "server/discover");
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void successfulToolSharesOneContextAndFinishesExactlyOnce()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		AtomicReference<McpRequestContext> interceptorContext =
				new AtomicReference<>();
		AtomicReference<McpRequestContext> handlerContext = new AtomicReference<>();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((context, call, features) -> {
					handlerContext.set(context);
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("observed");
				})
				.build();
		McpEndpoint endpoint = endpointBuilder("tool-observation-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint)
				.handlerInterceptor((context, invocation) -> {
					interceptorContext.set(context);
					return invocation.invoke();
				})
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					toolRequest("tool", TOOL_NAME), "tools/call",
					Optional.of(TOOL_NAME));
			observer.awaitFinished();

			assertSuccess(response, "tool");
			Assertions.assertTrue(response.body().contains("\"text\":\"observed\""),
					response.body());
			Assertions.assertEquals(1, handlerInvocations.get());
			assertSingleCompleteLifecycle(observer, endpoint, "tools/call",
					Optional.of(TOOL_NAME), "tool");
			Assertions.assertSame(observer.startedContext.get(),
					interceptorContext.get());
			Assertions.assertSame(observer.startedContext.get(), handlerContext.get());
			assertSingleCompleteMetrics(collector, "tools/call");
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void handlerFailurePublishesExactInternalErrorAndImmutableThrowable()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		IllegalStateException handlerFailure = new IllegalStateException(
				"sentinel-handler-failure");
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler((context, call, features) -> {
					throw handlerFailure;
				})
				.build();
		McpEndpoint endpoint = endpointBuilder("handler-failure-observation-test")
				.tool(tool)
				.build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					toolRequest("handler-failure", TOOL_NAME), "tools/call",
					Optional.of(TOOL_NAME));
			observer.awaitFinished();

			Assertions.assertEquals(500, response.statusCode(), response.body());
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":"
					+ "\"handler-failure\",\"error\":{\"code\":-32603,"
					+ "\"message\":\"Internal error\"}}", response.body());
			assertSingleLifecycle(observer, endpoint, "tools/call",
					Optional.of(TOOL_NAME), "handler-failure",
					McpRequestOutcome.INTERNAL_ERROR);
			McpJsonRpcError error = Optional.ofNullable(observer.error.get())
					.orElseThrow();
			Assertions.assertEquals(-32603, error.getCode());
			Assertions.assertEquals("Internal error", error.getMessage());
			Assertions.assertEquals(Optional.empty(), error.getData());
			List<Throwable> finishThrowables = observer.finishThrowables.get();
			Assertions.assertEquals(List.of(handlerFailure), finishThrowables);
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> finishThrowables.add(
							new RuntimeException("must-not-add")));
			assertSingleMetrics(collector, "tools/call",
					McpRequestOutcome.INTERNAL_ERROR);
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void admissionRejectionDoesNotPublishAdmittedRequestObservation()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpEndpoint endpoint = endpointBuilder("rejected-observation-test").build();
		McpRequestRejection rejection = McpRequestRejection
				.withStatusCodeAndError(401,
						McpJsonRpcError.fromApplication(1_001,
								"Authentication required"))
				.header("WWW-Authenticate", "Bearer realm=soklet-mcp")
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(context ->
						McpAdmissionDecision.fromRejection(rejection))
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port, discoverRequest("rejected"),
					"server/discover", Optional.empty());

			Assertions.assertEquals(401, response.statusCode(), response.body());
			Assertions.assertEquals("Bearer realm=soklet-mcp",
					response.headers().firstValue("WWW-Authenticate").orElseThrow());
			Assertions.assertTrue(response.body().contains("\"id\":\"rejected\""),
					response.body());
			Assertions.assertEquals(0, observer.starts.get());
			Assertions.assertEquals(0, observer.finishes.get());
			Assertions.assertTrue(collector.requestStartedEvents().isEmpty());
			Assertions.assertTrue(collector.requestFinishedEvents().isEmpty());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void postAdmissionRequestRateRejectionPublishesExactError()
			throws Exception {
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpEndpoint endpoint = endpointBuilder("rate-rejection-observation-test")
				.build();
		McpServer server = serverBuilder(endpoint)
				.requestRateLimiter(context -> {
					Assertions.assertEquals(McpRateLimitTarget.REQUEST,
							context.getTarget());
					return McpRateLimitDecision.fromDenied(Duration.ofMillis(1));
				})
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					discoverRequest("rate-rejected"), "server/discover",
					Optional.empty());
			observer.awaitFinished();

			Assertions.assertEquals(429, response.statusCode(), response.body());
			Assertions.assertEquals("1",
					response.headers().firstValue("Retry-After").orElseThrow());
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":"
					+ "\"rate-rejected\",\"error\":{\"code\":-31999,"
					+ "\"message\":\"Rate limited\"}}", response.body());
			assertSingleLifecycle(observer, endpoint, "server/discover",
					Optional.empty(), "rate-rejected", McpRequestOutcome.REJECTED);
			McpJsonRpcError error = Optional.ofNullable(observer.error.get())
					.orElseThrow();
			Assertions.assertEquals(McpJsonRpcError.SOKLET_RATE_LIMIT_ERROR_CODE,
					error.getCode());
			Assertions.assertEquals("Rate limited", error.getMessage());
			Assertions.assertEquals(Optional.empty(), error.getData());
			Assertions.assertEquals(List.of(), observer.finishThrowables.get());
			assertSingleMetrics(collector, "server/discover",
					McpRequestOutcome.REJECTED);
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void unsupportedNotificationRetainsRawLifecycleMethodAndBoundsMetrics()
			throws Exception {
		String unsupportedMethod = "vendor.example/future-notification";
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpEndpoint endpoint = endpointBuilder(
				"unsupported-notification-observation-test").build();
		McpServer server = serverBuilder(endpoint)
				.requestRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.build();
		Soklet soklet = managedSoklet(server, List.of(observer), collector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port,
					notification(unsupportedMethod), unsupportedMethod,
					Optional.empty());
			observer.awaitFinished();

			Assertions.assertEquals(400, response.statusCode(), response.body());
			Assertions.assertTrue(response.body().isEmpty(), response.body());
			Assertions.assertEquals(1, observer.starts.get());
			Assertions.assertEquals(1, observer.finishes.get());
			Assertions.assertSame(observer.startedContext.get(),
					observer.finishedContext.get());
			McpRequestContext context = observer.startedContext.get();
			Assertions.assertNotNull(context);
			Assertions.assertSame(endpoint, context.getEndpoint());
			Assertions.assertEquals(unsupportedMethod,
					context.getJsonRpcMethod());
			Assertions.assertEquals(Optional.empty(), context.getRequestId());
			Assertions.assertEquals(Optional.empty(), context.getOperationName());
			Assertions.assertEquals(McpRequestOutcome.PROTOCOL_ERROR,
					observer.outcome.get());
			Assertions.assertNull(observer.error.get());
			Assertions.assertEquals(List.of(), observer.finishThrowables.get());
			assertSingleMetrics(collector,
					McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD,
					McpRequestOutcome.PROTOCOL_ERROR);
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void throwingObservationCallbacksAreContainedLoggedAndPartitioned()
			throws Exception {
		RuntimeException startFailure = new RuntimeException(
				"lifecycle-start-secret");
		RuntimeException finishFailure = new RuntimeException(
				"lifecycle-finish-secret");
		RuntimeException metricsFailure = new RuntimeException("metrics-secret");
		LifecycleObserver throwingObserver = new LifecycleObserver() {
			@Override
			public void didStartMcpRequestHandling(
					@NonNull McpRequestContext context) {
				throw startFailure;
			}

			@Override
			public void didFinishMcpRequestHandling(
					@NonNull McpRequestContext context,
					@NonNull McpRequestOutcome outcome,
					@Nullable McpJsonRpcError error,
					@NonNull Duration duration,
					@NonNull List<@NonNull Throwable> throwables) {
				throw finishFailure;
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				// Keep the deliberately throwing observer quiet while another records.
			}
		};
		RecordingLifecycleObserver recordingObserver =
				new RecordingLifecycleObserver();
		MetricsCollector throwingCollector = new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
				if (event instanceof McpMetricsEvent.RequestStarted
						|| event instanceof McpMetricsEvent.RequestFinished)
					throw metricsFailure;
			}
		};
		McpEndpoint endpoint = endpointBuilder("throwing-observation-test").build();
		McpServer server = serverBuilder(endpoint).build();
		Soklet soklet = managedSoklet(server,
				List.of(throwingObserver, recordingObserver), throwingCollector);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			HttpResponse<String> response = send(port, discoverRequest("contained"),
					"server/discover", Optional.empty());
			recordingObserver.awaitFinished();

			assertSuccess(response, "contained");
			Assertions.assertFalse(response.body().contains("secret"), response.body());
			Assertions.assertEquals(1, recordingObserver.starts.get());
			Assertions.assertEquals(1, recordingObserver.finishes.get());
			Assertions.assertSame(recordingObserver.startedContext.get(),
					recordingObserver.finishedContext.get());
			List<Throwable> finishThrowables =
					recordingObserver.finishThrowables.get();
			Assertions.assertEquals(List.of(startFailure), finishThrowables);
			Assertions.assertFalse(finishThrowables.contains(metricsFailure));
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> finishThrowables.add(new RuntimeException("must-not-add")));

			assertLogCount(recordingObserver.logEvents,
					LogEventType.LIFECYCLE_OBSERVER_DID_START_MCP_REQUEST_HANDLING_FAILED,
					1, startFailure);
			assertLogCount(recordingObserver.logEvents,
					LogEventType.LIFECYCLE_OBSERVER_DID_FINISH_MCP_REQUEST_HANDLING_FAILED,
					1, finishFailure);
			assertLogCount(recordingObserver.logEvents,
					LogEventType.METRICS_COLLECTOR_FAILED, 2, metricsFailure);
			Assertions.assertEquals(4, recordingObserver.logEvents.size(),
					recordingObserver.logEvents.toString());
			for (LogEvent event : recordingObserver.logEvents)
				Assertions.assertTrue(event.getRequest().isPresent(), event.toString());
		} finally {
			soklet.stop();
		}
	}

	private static McpEndpoint.@NonNull Builder endpointBuilder(
			@NonNull String implementationName) {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						implementationName, "3.6.0-SNAPSHOT").build());
	}

	private static McpServer.@NonNull Builder serverBuilder(
			@NonNull McpEndpoint endpoint) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server,
			@NonNull List<@NonNull LifecycleObserver> observers,
			@NonNull MetricsCollector collector) {
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObservers(observers)
				.metricsCollector(collector)
				.build();
		return Soklet.fromConfig(config);
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String body,
			@NonNull String method,
			@NonNull Optional<@NonNull String> operationName) throws Exception {
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

	@NonNull
	private static String discoverRequest(@NonNull String id) {
		return request(id, "server/discover", "");
	}

	@NonNull
	private static String toolRequest(@NonNull String id,
			@NonNull String toolName) {
		return request(id, "tools/call", ",\"name\":\"" + toolName
				+ "\",\"arguments\":{}");
	}

	@NonNull
	private static String notification(@NonNull String method) {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"}";
	}

	@NonNull
	private static String request(@NonNull String id, @NonNull String method,
			@NonNull String additionalParameters) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method
				+ "\",\"params\":{\"_meta\":"
				+ "{"
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
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
	}

	private static void assertSingleCompleteLifecycle(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull McpEndpoint expectedEndpoint, @NonNull String expectedMethod,
			@NonNull Optional<@NonNull String> expectedOperation,
			@NonNull String expectedRequestId) {
		assertSingleLifecycle(observer, expectedEndpoint, expectedMethod,
				expectedOperation, expectedRequestId, McpRequestOutcome.COMPLETE);
		Assertions.assertNull(observer.error.get());
		Assertions.assertEquals(List.of(), observer.finishThrowables.get());
	}

	private static void assertSingleLifecycle(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull McpEndpoint expectedEndpoint, @NonNull String expectedMethod,
			@NonNull Optional<@NonNull String> expectedOperation,
			@NonNull String expectedRequestId,
			@NonNull McpRequestOutcome expectedOutcome) {
		Assertions.assertEquals(1, observer.starts.get());
		Assertions.assertEquals(1, observer.finishes.get());
		Assertions.assertSame(observer.startedContext.get(),
				observer.finishedContext.get());
		McpRequestContext context = observer.startedContext.get();
		Assertions.assertNotNull(context);
		Assertions.assertSame(expectedEndpoint, context.getEndpoint());
		Assertions.assertEquals(expectedMethod, context.getJsonRpcMethod());
		Assertions.assertEquals(expectedOperation, context.getOperationName());
		Assertions.assertEquals(McpRequestId.fromString(expectedRequestId),
				context.getRequestId().orElseThrow());
		Assertions.assertEquals(expectedOutcome, observer.outcome.get());
		Assertions.assertFalse(observer.duration.get().isNegative());
	}

	private static void assertSingleCompleteMetrics(
			@NonNull RecordingMetricsCollector collector,
			@NonNull String expectedMethod) {
		assertSingleMetrics(collector, expectedMethod,
				McpRequestOutcome.COMPLETE);
	}

	private static void assertSingleMetrics(
			@NonNull RecordingMetricsCollector collector,
			@NonNull String expectedMethod,
			@NonNull McpRequestOutcome expectedOutcome) {
		List<McpMetricsEvent.RequestStarted> started =
				collector.requestStartedEvents();
		List<McpMetricsEvent.RequestFinished> finished =
				collector.requestFinishedEvents();
		Assertions.assertEquals(1, started.size(), started.toString());
		Assertions.assertEquals(MCP_PATH, started.get(0).endpointPath());
		Assertions.assertEquals(expectedMethod, started.get(0).jsonRpcMethod());
		Assertions.assertEquals(1, finished.size(), finished.toString());
		Assertions.assertEquals(MCP_PATH, finished.get(0).endpointPath());
		Assertions.assertEquals(expectedMethod, finished.get(0).jsonRpcMethod());
		Assertions.assertEquals(expectedOutcome, finished.get(0).outcome());
		Assertions.assertFalse(finished.get(0).duration().isNegative());
	}

	private static void assertLogCount(@NonNull List<@NonNull LogEvent> events,
			@NonNull LogEventType eventType, int expectedCount,
			@NonNull Throwable expectedThrowable) {
		List<LogEvent> matching = events.stream()
				.filter(event -> event.getLogEventType() == eventType)
				.toList();
		Assertions.assertEquals(expectedCount, matching.size(), events.toString());
		for (LogEvent event : matching)
			Assertions.assertSame(expectedThrowable,
					event.getThrowable().orElseThrow());
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		private final AtomicInteger starts = new AtomicInteger();
		private final AtomicInteger finishes = new AtomicInteger();
		private final CountDownLatch finished = new CountDownLatch(1);
		private final AtomicReference<McpRequestContext> startedContext =
				new AtomicReference<>();
		private final AtomicReference<McpRequestContext> finishedContext =
				new AtomicReference<>();
		private final AtomicReference<McpRequestOutcome> outcome =
				new AtomicReference<>();
		private final AtomicReference<McpJsonRpcError> error =
				new AtomicReference<>();
		private final AtomicReference<Duration> duration = new AtomicReference<>();
		private final AtomicReference<List<Throwable>> finishThrowables =
				new AtomicReference<>();
		private final List<LogEvent> logEvents = new CopyOnWriteArrayList<>();

		@Override
		public void didStartMcpRequestHandling(
				@NonNull McpRequestContext context) {
			this.startedContext.set(context);
			this.starts.incrementAndGet();
		}

		@Override
		public void didFinishMcpRequestHandling(
				@NonNull McpRequestContext context,
				@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error,
				@NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			this.finishedContext.set(context);
			this.outcome.set(outcome);
			this.error.set(error);
			this.duration.set(duration);
			this.finishThrowables.set(throwables);
			this.finishes.incrementAndGet();
			this.finished.countDown();
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			this.logEvents.add(logEvent);
		}

		private void awaitFinished() throws InterruptedException {
			Assertions.assertTrue(this.finished.await(5, TimeUnit.SECONDS),
					"The MCP request finish callback did not arrive.");
		}
	}

	private static final class RecordingMetricsCollector
			implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(event);
		}

		@NonNull
		private List<McpMetricsEvent.RequestStarted> requestStartedEvents() {
			return this.events.stream()
					.filter(McpMetricsEvent.RequestStarted.class::isInstance)
					.map(McpMetricsEvent.RequestStarted.class::cast)
					.toList();
		}

		@NonNull
		private List<McpMetricsEvent.RequestFinished> requestFinishedEvents() {
			return this.events.stream()
					.filter(McpMetricsEvent.RequestFinished.class::isInstance)
					.map(McpMetricsEvent.RequestFinished.class::cast)
					.toList();
		}
	}
}
