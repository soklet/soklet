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

import javax.annotation.concurrent.NotThreadSafe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Black-box real-listener coverage for bounded pre-admission MCP metrics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(30)
public class McpPreAdmissionMetricsEventPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";

	@Test
	public void acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector(1);
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver(0);
		McpServer server = serverBuilder(endpoint("malformed-metrics-test"))
				.build();
		Soklet soklet = managedSoklet(server, collector, observer);

		try {
			soklet.start();
			HttpResponse<String> response = send(boundPort(server), "{",
					"server/discover", Map.of());
			collector.awaitRequestRejections();

			Assertions.assertEquals(400, response.statusCode(), response.body());
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"error\":{"
					+ "\"code\":-32700,\"message\":\"Parse error\"}}",
					response.body());
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.RequestAccepted(),
					new McpMetricsEvent.ProtocolError(-32_700),
					new McpMetricsEvent.RequestRejected()),
					collector.quartetEvents());
			Assertions.assertEquals(0, observer.starts());
			Assertions.assertEquals(0, observer.finishes());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void applicationCodesAreExcludedWhileAdmittedFixedErrorsRetainExactRequestContext()
			throws Exception {
		RuntimeException preAdmissionFailure = new RuntimeException(
				"expected pre-admission metric failure");
		RuntimeException admittedFailure = new RuntimeException(
				"expected admitted protocol-error metric failure");
		FailingContextMetricsCollector collector =
				new FailingContextMetricsCollector(preAdmissionFailure,
						admittedFailure);
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver(2);
		AtomicInteger admissionCalls = new AtomicInteger();
		McpAdmissionRejection applicationRejection = McpAdmissionRejection
				.withStatusCodeAndError(401,
						McpJsonRpcError.fromApplication(1_001,
								"Authentication required"))
				.header("WWW-Authenticate", "Bearer realm=soklet-mcp")
				.build();
		McpServer server = serverBuilder(endpoint("fixed-error-context-test"))
				.admissionController(context ->
						admissionCalls.getAndIncrement() == 0
								? McpAdmissionDecision.rejected(
										applicationRejection)
								: McpAdmissionDecision.accepted())
				.requestRateLimiter(context ->
						McpRateLimitDecision.denied(Duration.ofMillis(1)))
				.build();
		Soklet soklet = managedSoklet(server, collector, observer);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> malformed = send(port, "{",
					"server/discover", Map.of());
			HttpResponse<String> applicationRejected = send(port,
					discoveryRequest("application-rejected"),
					"server/discover", Map.of());
			collector.awaitRequestRejections();
			HttpResponse<String> fixedRejected = send(port,
					discoveryRequest("fixed-rejected"),
					"server/discover", Map.of());
			observer.awaitFinished();
			observer.awaitMetricFailures();
			collector.awaitRequestFinishedMetric();

			Assertions.assertEquals(400, malformed.statusCode(),
					malformed.body());
			Assertions.assertTrue(malformed.body().contains("\"code\":-32700"),
					malformed.body());
			Assertions.assertEquals(401, applicationRejected.statusCode(),
					applicationRejected.body());
			Assertions.assertEquals("Bearer realm=soklet-mcp",
					applicationRejected.headers()
							.firstValue("WWW-Authenticate").orElseThrow());
			Assertions.assertTrue(applicationRejected.body().contains(
					"\"code\":1001"), applicationRejected.body());
			Assertions.assertEquals(429, fixedRejected.statusCode(),
					fixedRejected.body());
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":"
					+ "\"fixed-rejected\",\"error\":{\"code\":-31999,"
					+ "\"message\":\"Rate limited\"}}", fixedRejected.body());

			List<McpMetricsEvent> requestEvents = collector.requestEvents();
			Assertions.assertEquals(9, requestEvents.size(),
					requestEvents.toString());
			Assertions.assertEquals(new McpMetricsEvent.RequestAccepted(),
					requestEvents.get(0));
			Assertions.assertEquals(new McpMetricsEvent.ProtocolError(-32_700),
					requestEvents.get(1));
			Assertions.assertEquals(new McpMetricsEvent.RequestRejected(),
					requestEvents.get(2));
			Assertions.assertEquals(new McpMetricsEvent.RequestAccepted(),
					requestEvents.get(3));
			Assertions.assertEquals(new McpMetricsEvent.RequestRejected(),
					requestEvents.get(4));
			Assertions.assertEquals(new McpMetricsEvent.RequestAccepted(),
					requestEvents.get(5));
			Assertions.assertEquals(new McpMetricsEvent.RequestStarted(
					MCP_PATH, "server/discover"), requestEvents.get(6));
			Assertions.assertEquals(new McpMetricsEvent.ProtocolError(-31_999),
					requestEvents.get(7));
			Assertions.assertInstanceOf(McpMetricsEvent.RequestFinished.class,
					requestEvents.get(8));
			McpMetricsEvent.RequestFinished finished =
					(McpMetricsEvent.RequestFinished) requestEvents.get(8);
			Assertions.assertEquals(MCP_PATH, finished.endpointPath());
			Assertions.assertEquals("server/discover", finished.jsonRpcMethod());
			Assertions.assertEquals(McpRequestOutcome.REJECTED,
					finished.outcome());
			Assertions.assertFalse(finished.duration().isNegative());
			Assertions.assertEquals(List.of(-32_700, -31_999), requestEvents.stream()
					.filter(McpMetricsEvent.ProtocolError.class::isInstance)
					.map(McpMetricsEvent.ProtocolError.class::cast)
					.map(McpMetricsEvent.ProtocolError::code)
					.toList(),
					"Application-owned error code 1001 must not become a metric dimension.");

			List<LogEvent> failures = observer.metricFailures();
			Assertions.assertEquals(2, failures.size(), failures.toString());
			LogEvent preAdmissionLog = failureFor(failures,
					preAdmissionFailure);
			LogEvent admittedLog = failureFor(failures, admittedFailure);
			Assertions.assertTrue(preAdmissionLog.getRequest().isEmpty(),
					"A pre-admission ProtocolError must remain request-free.");
			Assertions.assertSame(observer.requestContext().getRequest(),
					admittedLog.getRequest().orElseThrow(),
					"An admitted fixed-code event must retain its exact request context.");
			Assertions.assertEquals(1, collector.maximumConcurrentCallbacks(),
					"Collector failure containment must preserve serialized delivery.");
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void unknownHeaderOccurrencesAreExactRedactedAndMethodBoundedAcrossPolicies()
			throws Exception {
		String rawMethod = "attacker.example/unbounded-method-canary";
		Map<String, String> ignoredHeaders = new LinkedHashMap<>();
		ignoredHeaders.put("Mcp-Param-Ignored-Alpha-Canary",
				"ignored-alpha-value-canary");
		ignoredHeaders.put("Mcp-Param-Ignored-Beta-Canary",
				"ignored-beta-value-canary");
		UnknownCase ignored = runUnknownCase(
				McpUnknownMirroredHeaderPolicy.IGNORE, rawMethod,
				ignoredHeaders, "ignored-unknown-method");

		Assertions.assertEquals(404, ignored.response().statusCode(),
				ignored.response().body());
		Assertions.assertEquals(List.of(
				new McpMetricsEvent.RequestAccepted(),
				new McpMetricsEvent.UnknownMirroredHeader(MCP_PATH,
						McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD),
				new McpMetricsEvent.UnknownMirroredHeader(MCP_PATH,
						McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD),
				new McpMetricsEvent.ProtocolError(-32_601),
				new McpMetricsEvent.RequestRejected()),
				ignored.events());
		Assertions.assertEquals(0, ignored.admissions());
		assertAbsentFromObservability(ignored, rawMethod,
				"Ignored-Alpha-Canary", "ignored-alpha-value-canary",
				"Ignored-Beta-Canary", "ignored-beta-value-canary");

		Map<String, String> strictHeaders = new LinkedHashMap<>();
		strictHeaders.put("Mcp-Param-Strict-Alpha-Canary",
				"strict-alpha-value-canary");
		strictHeaders.put("Mcp-Param-Strict-Beta-Canary",
				"strict-beta-value-canary");
		UnknownCase strict = runUnknownCase(
				McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS,
				"server/discover", strictHeaders, "strict-unknown");

		Assertions.assertEquals(400, strict.response().statusCode(),
				strict.response().body());
		Assertions.assertEquals(List.of(
				new McpMetricsEvent.RequestAccepted(),
				new McpMetricsEvent.UnknownMirroredHeader(MCP_PATH,
						"server/discover"),
				new McpMetricsEvent.UnknownMirroredHeader(MCP_PATH,
						"server/discover"),
				new McpMetricsEvent.ProtocolError(-31_998),
				new McpMetricsEvent.RequestRejected()),
				strict.events());
		Assertions.assertEquals(0, strict.admissions());
		assertAbsentFromObservability(strict, "Strict-Alpha-Canary",
				"strict-alpha-value-canary", "Strict-Beta-Canary",
				"strict-beta-value-canary");
	}

	@Test
	public void preAdmissionQuartetDeliveryIsReentrantAndSerializedWithoutCrossRequestOrderClaim()
			throws Exception {
		AtomicInteger port = new AtomicInteger();
		ReentrantMalformedMetricsCollector collector =
				new ReentrantMalformedMetricsCollector(port);
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver(0);
		McpServer server = serverBuilder(endpoint("reentrant-quartet-test"))
				.build();
		Soklet soklet = managedSoklet(server, collector, observer);

		try {
			soklet.start();
			port.set(boundPort(server));
			HttpResponse<String> outerResponse = send(port.get(), "{",
					"server/discover", Map.of());
			collector.awaitRequestRejections();

			Assertions.assertEquals(400, outerResponse.statusCode(),
					outerResponse.body());
			Assertions.assertNull(collector.failure());
			Assertions.assertNotNull(collector.nestedResponse());
			Assertions.assertEquals(400,
					collector.nestedResponse().statusCode(),
					collector.nestedResponse().body());
			List<McpMetricsEvent> events = collector.quartetEvents();
			Assertions.assertEquals(2, count(events,
					McpMetricsEvent.RequestAccepted.class));
			Assertions.assertEquals(2, count(events,
					McpMetricsEvent.ProtocolError.class));
			Assertions.assertEquals(2, count(events,
					McpMetricsEvent.RequestRejected.class));
			Assertions.assertEquals(0, count(events,
					McpMetricsEvent.UnknownMirroredHeader.class));
			Assertions.assertEquals(List.of(-32_700, -32_700), events.stream()
					.filter(McpMetricsEvent.ProtocolError.class::isInstance)
					.map(McpMetricsEvent.ProtocolError.class::cast)
					.map(McpMetricsEvent.ProtocolError::code)
					.toList());
			Assertions.assertEquals(1, collector.maximumConcurrentCallbacks(),
					"A nested request must enqueue without recursively entering the collector.");
			Assertions.assertEquals(0, observer.starts());
			Assertions.assertEquals(0, observer.finishes());
		} finally {
			soklet.stop();
		}
	}

	@NonNull
	private static UnknownCase runUnknownCase(
			@NonNull McpUnknownMirroredHeaderPolicy policy,
			@NonNull String method, @NonNull Map<@NonNull String, @NonNull String> headers,
			@NonNull String id) throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		RecordingMetricsCollector collector = new RecordingMetricsCollector(1);
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver(0);
		McpServer server = serverBuilder(endpoint("unknown-header-metrics-test"))
				.admissionController(context -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.accepted();
				})
				.unknownMirroredHeaderPolicy(policy)
				.build();
		Soklet soklet = managedSoklet(server, collector, observer);

		try {
			soklet.start();
			HttpResponse<String> response = send(boundPort(server),
					request(id, method), method, headers);
			collector.awaitRequestRejections();
			Assertions.assertEquals(0, observer.starts());
			Assertions.assertEquals(0, observer.finishes());
			return new UnknownCase(response, collector.quartetEvents(),
					observer.logEvents(), admissions.get());
		} finally {
			soklet.stop();
		}
	}

	private static void assertAbsentFromObservability(
			@NonNull UnknownCase unknownCase,
			@NonNull String... canaries) {
		String rendered = unknownCase.events().toString()
				+ unknownCase.logEvents()
				+ unknownCase.response().body();
		for (String canary : canaries)
			Assertions.assertFalse(rendered.contains(canary), rendered);
	}

	private static long count(@NonNull List<@NonNull McpMetricsEvent> events,
			@NonNull Class<? extends McpMetricsEvent> type) {
		return events.stream().filter(type::isInstance).count();
	}

	@NonNull
	private static LogEvent failureFor(@NonNull List<@NonNull LogEvent> failures,
			@NonNull Throwable expected) {
		return failures.stream()
				.filter(event -> event.getThrowable().orElseThrow() == expected)
				.findFirst().orElseThrow();
	}

	@NonNull
	private static McpEndpoint endpoint(@NonNull String implementationName) {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						implementationName, "3.6.0-SNAPSHOT").build())
				.build();
	}

	private static McpServer.@NonNull Builder serverBuilder(
			@NonNull McpEndpoint endpoint) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context ->
						McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector collector,
			@NonNull LifecycleObserver observer) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector)
				.lifecycleObserver(observer)
				.build());
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	@NonNull
	private static HttpResponse<String> send(int port, @NonNull String body,
			@NonNull String method,
			@NonNull Map<@NonNull String, @NonNull String> additionalHeaders)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method);
		additionalHeaders.forEach(request::header);
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@NonNull
	private static String discoveryRequest(@NonNull String id) {
		return request(id, "server/discover");
	}

	@NonNull
	private static String request(@NonNull String id, @NonNull String method) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method
				+ "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
	}

	private static class RecordingMetricsCollector implements MetricsCollector {
		@NonNull
		private final List<@NonNull McpMetricsEvent> events =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final CountDownLatch requestRejections;

		private RecordingMetricsCollector(int expectedRequestRejections) {
			this.requestRejections = new CountDownLatch(
					expectedRequestRejections);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			record(event);
		}

		protected final void record(@NonNull McpMetricsEvent event) {
			this.events.add(requireNonNull(event));
			if (event instanceof McpMetricsEvent.RequestRejected)
				this.requestRejections.countDown();
		}

		protected final void awaitRequestRejections()
				throws InterruptedException {
			Assertions.assertTrue(this.requestRejections.await(5, TimeUnit.SECONDS),
					"The expected request-rejection metrics did not arrive.");
		}

		@NonNull
		protected final List<@NonNull McpMetricsEvent> quartetEvents() {
			return this.events.stream()
					.filter(event -> event instanceof McpMetricsEvent.RequestAccepted
							|| event instanceof McpMetricsEvent.RequestRejected
							|| event instanceof McpMetricsEvent.ProtocolError
							|| event instanceof McpMetricsEvent.UnknownMirroredHeader)
					.toList();
		}

		@NonNull
		protected final List<@NonNull McpMetricsEvent> requestEvents() {
			return this.events.stream()
					.filter(event -> event instanceof McpMetricsEvent.RequestAccepted
							|| event instanceof McpMetricsEvent.RequestRejected
							|| event instanceof McpMetricsEvent.RequestStarted
							|| event instanceof McpMetricsEvent.RequestFinished
							|| event instanceof McpMetricsEvent.ProtocolError)
					.toList();
		}
	}

	private static final class FailingContextMetricsCollector
			extends RecordingMetricsCollector {
		@NonNull
		private final RuntimeException preAdmissionFailure;
		@NonNull
		private final RuntimeException admittedFailure;
		@NonNull
		private final AtomicInteger activeCallbacks = new AtomicInteger();
		@NonNull
		private final AtomicInteger maximumConcurrentCallbacks =
				new AtomicInteger();
		@NonNull
		private final CountDownLatch requestFinishedMetric =
				new CountDownLatch(1);

		private FailingContextMetricsCollector(
				@NonNull RuntimeException preAdmissionFailure,
				@NonNull RuntimeException admittedFailure) {
			super(2);
			this.preAdmissionFailure = requireNonNull(preAdmissionFailure);
			this.admittedFailure = requireNonNull(admittedFailure);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			int active = this.activeCallbacks.incrementAndGet();
			this.maximumConcurrentCallbacks.accumulateAndGet(active, Math::max);
			try {
				record(event);
				if (event instanceof McpMetricsEvent.RequestFinished)
					this.requestFinishedMetric.countDown();
				if (event instanceof McpMetricsEvent.ProtocolError protocolError
						&& protocolError.code() == -32_700)
					throw this.preAdmissionFailure;
				if (event instanceof McpMetricsEvent.ProtocolError protocolError
						&& protocolError.code() == -31_999)
					throw this.admittedFailure;
			} finally {
				this.activeCallbacks.decrementAndGet();
			}
		}

		private int maximumConcurrentCallbacks() {
			return this.maximumConcurrentCallbacks.get();
		}

		private void awaitRequestFinishedMetric() throws InterruptedException {
			Assertions.assertTrue(this.requestFinishedMetric.await(
					5, TimeUnit.SECONDS),
					"The admitted request-finished metric did not arrive.");
		}
	}

	private static final class ReentrantMalformedMetricsCollector
			extends RecordingMetricsCollector {
		@NonNull
		private final AtomicInteger port;
		@NonNull
		private final AtomicBoolean reentered = new AtomicBoolean();
		@NonNull
		private final AtomicInteger activeCallbacks = new AtomicInteger();
		@NonNull
		private final AtomicInteger maximumConcurrentCallbacks =
				new AtomicInteger();
		@NonNull
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		@NonNull
		private final AtomicReference<HttpResponse<String>> nestedResponse =
				new AtomicReference<>();

		private ReentrantMalformedMetricsCollector(@NonNull AtomicInteger port) {
			super(2);
			this.port = requireNonNull(port);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			int active = this.activeCallbacks.incrementAndGet();
			this.maximumConcurrentCallbacks.accumulateAndGet(active, Math::max);
			try {
				record(event);
				if (event instanceof McpMetricsEvent.ProtocolError
						&& this.reentered.compareAndSet(false, true)) {
					try {
						this.nestedResponse.set(send(this.port.get(), "{",
								"server/discover", Map.of()));
					} catch (Throwable throwable) {
						this.failure.compareAndSet(null, throwable);
					}
				}
			} finally {
				this.activeCallbacks.decrementAndGet();
			}
		}

		private int maximumConcurrentCallbacks() {
			return this.maximumConcurrentCallbacks.get();
		}

		private Throwable failure() {
			return this.failure.get();
		}

		private HttpResponse<String> nestedResponse() {
			return this.nestedResponse.get();
		}
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		@NonNull
		private final AtomicInteger starts = new AtomicInteger();
		@NonNull
		private final AtomicInteger finishes = new AtomicInteger();
		@NonNull
		private final AtomicReference<McpRequestContext> requestContext =
				new AtomicReference<>();
		@NonNull
		private final CountDownLatch finished = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch metricFailures;
		@NonNull
		private final List<@NonNull LogEvent> logEvents =
				new CopyOnWriteArrayList<>();

		private RecordingLifecycleObserver(int expectedMetricFailures) {
			this.metricFailures = new CountDownLatch(expectedMetricFailures);
		}

		@Override
		public void didStartMcpRequestHandling(
				@NonNull McpRequestContext context) {
			this.requestContext.set(requireNonNull(context));
			this.starts.incrementAndGet();
		}

		@Override
		public void didFinishMcpRequestHandling(
				@NonNull McpRequestContext context,
				@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error,
				@NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			Assertions.assertSame(this.requestContext.get(), context);
			this.finishes.incrementAndGet();
			this.finished.countDown();
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			this.logEvents.add(requireNonNull(logEvent));
			if (logEvent.getLogEventType()
					== LogEventType.METRICS_COLLECTOR_FAILED)
				this.metricFailures.countDown();
		}

		private void awaitFinished() throws InterruptedException {
			Assertions.assertTrue(this.finished.await(5, TimeUnit.SECONDS),
					"The admitted request lifecycle did not finish.");
		}

		private void awaitMetricFailures() throws InterruptedException {
			Assertions.assertTrue(this.metricFailures.await(5, TimeUnit.SECONDS),
					"The expected metric-failure logs did not arrive.");
		}

		private int starts() {
			return this.starts.get();
		}

		private int finishes() {
			return this.finishes.get();
		}

		@NonNull
		private McpRequestContext requestContext() {
			return requireNonNull(this.requestContext.get());
		}

		@NonNull
		private List<@NonNull LogEvent> logEvents() {
			return List.copyOf(this.logEvents);
		}

		@NonNull
		private List<@NonNull LogEvent> metricFailures() {
			return this.logEvents.stream()
					.filter(event -> event.getLogEventType()
							== LogEventType.METRICS_COLLECTOR_FAILED)
					.toList();
		}
	}

	private record UnknownCase(@NonNull HttpResponse<@NonNull String> response,
			@NonNull List<@NonNull McpMetricsEvent> events,
			@NonNull List<@NonNull LogEvent> logEvents, int admissions) {
		private UnknownCase {
			requireNonNull(response);
			events = List.copyOf(requireNonNull(events));
			logEvents = List.copyOf(requireNonNull(logEvents));
			if (admissions < 0)
				throw new IllegalArgumentException("Admissions must not be negative.");
		}
	}
}
