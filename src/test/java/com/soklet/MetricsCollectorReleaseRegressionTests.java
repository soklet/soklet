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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Regression coverage for the metrics findings accepted from the 4.0 release
 * review.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class MetricsCollectorReleaseRegressionTests {
	private static final String ENDPOINT_PATH = "/mcp/metrics-regression";
	private static final String JSON_RPC_METHOD = "tools/call";

	@Test
	public void overlappingRequestsWithSameIdRemainIndependent() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		Object sharedId = "non-unique-request-id";
		ResourceMethod widgetsResource = resourceMethodFor(
				"/widgets/{id}", HttpMethod.GET, "widget", false);
		ResourceMethod reportsResource = resourceMethodFor(
				"/reports/{id}", HttpMethod.POST, "report", false);
		Request widgetsRequest = Request.withPath(HttpMethod.GET, "/widgets/1")
				.id(sharedId)
				.build();
		Request reportsRequest = Request.withPath(HttpMethod.POST, "/reports/2")
				.id(sharedId)
				.build();
		MarshaledResponse widgetsResponse = MarshaledResponse.withStatusCode(200)
				.body(new byte[]{1})
				.build();
		MarshaledResponse reportsResponse = MarshaledResponse.withStatusCode(503)
				.body(new byte[]{2, 3})
				.build();

		collector.didStartRequestHandling(ServerType.STANDARD_HTTP,
				widgetsRequest, widgetsResource);
		collector.didStartRequestHandling(ServerType.STANDARD_HTTP,
				reportsRequest, reportsResource);
		Assertions.assertEquals(2L, collector.getActiveRequests());
		Assertions.assertEquals(2L, collector.getRequestsInFlightByIdentityCount());
		Assertions.assertEquals(2L, collector.getRequestsInFlightByIdCount());

		collector.willWriteResponse(ServerType.STANDARD_HTTP,
				reportsRequest, reportsResource, reportsResponse);
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP,
				reportsRequest, reportsResource, reportsResponse,
				Duration.ofMillis(7), List.of());
		// Once a collision has occurred, an ID-only fallback remains ambiguous
		// until every overlapping request is gone. A duplicate terminal callback
		// for one request must not finish the remaining request.
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP,
				reportsRequest, reportsResource, reportsResponse,
				Duration.ofMillis(11), List.of());
		Assertions.assertEquals(1L, collector.getActiveRequests());
		collector.willWriteResponse(ServerType.STANDARD_HTTP,
				widgetsRequest, widgetsResource, widgetsResponse);
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP,
				widgetsRequest, widgetsResource, widgetsResponse,
				Duration.ofMillis(3), List.of());

		MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();
		MetricsCollector.HttpServerRouteStatusKey widgetsKey =
				new MetricsCollector.HttpServerRouteStatusKey(HttpMethod.GET,
						MetricsCollector.RouteType.MATCHED,
						ResourcePathDeclaration.fromPath("/widgets/{id}"), "2xx");
		MetricsCollector.HttpServerRouteStatusKey reportsKey =
				new MetricsCollector.HttpServerRouteStatusKey(HttpMethod.POST,
						MetricsCollector.RouteType.MATCHED,
						ResourcePathDeclaration.fromPath("/reports/{id}"), "5xx");
		Assertions.assertEquals(1L,
				snapshot.getHttpRequestDurations().get(widgetsKey).getCount());
		Assertions.assertEquals(1L,
				snapshot.getHttpRequestDurations().get(reportsKey).getCount());
		Assertions.assertEquals(1L,
				snapshot.getHttpResponseBodyBytes().get(widgetsKey).getSum());
		Assertions.assertEquals(2L,
				snapshot.getHttpResponseBodyBytes().get(reportsKey).getSum());
		Assertions.assertEquals(0L, collector.getActiveRequests());
		Assertions.assertEquals(0L, collector.getRequestsInFlightByIdentityCount());
		Assertions.assertEquals(0L, collector.getRequestsInFlightByIdCount());
	}

	@Test
	public void resetPreservesHttpAndSseLiveStateUntilMatchingTerminalEvents() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		ResourceMethod httpResource = resourceMethodFor(
				"/widgets/{id}", HttpMethod.GET, "widget", false);
		Request httpRequest = Request.withPath(HttpMethod.GET, "/widgets/1").build();
		MarshaledResponse response = MarshaledResponse.withStatusCode(200).build();
		collector.didStartRequestHandling(ServerType.STANDARD_HTTP,
				httpRequest, httpResource);

		ResourceMethod sseResource = resourceMethodFor(
				"/events/{id}", HttpMethod.GET, "events", true);
		Request sseRequest = Request.withPath(HttpMethod.GET, "/events/1").build();
		SseConnection connection = new TestSseConnection(sseRequest, sseResource);
		collector.didEstablishSseConnection(connection);

		collector.reset();

		MetricsCollector.Snapshot during = collector.snapshot().orElseThrow();
		Assertions.assertEquals(1L, during.getActiveRequests());
		Assertions.assertEquals(1L, during.getActiveSseStreams());
		Assertions.assertEquals(1L, collector.getRequestsInFlightByIdentityCount());
		Assertions.assertEquals(1L, collector.getRequestsInFlightByIdCount());

		// Both calls require the identity state that reset previously discarded.
		collector.willWriteResponse(ServerType.STANDARD_HTTP,
				httpRequest, httpResource, response);
		collector.willWriteSseEvent(connection,
				SseEvent.withData("first").build());

		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP,
				httpRequest, httpResource, response, Duration.ofNanos(-1L), List.of());
		StreamTermination termination = StreamTermination.with(
				StreamTerminationReason.CLIENT_DISCONNECTED, Duration.ofSeconds(1L))
				.build();
		collector.didTerminateSseConnection(connection, termination);

		MetricsCollector.Snapshot after = collector.snapshot().orElseThrow();
		Assertions.assertEquals(0L, after.getActiveRequests());
		Assertions.assertEquals(0L, after.getActiveSseStreams());
		Assertions.assertEquals(0L, collector.getRequestsInFlightByIdentityCount());
		Assertions.assertEquals(0L, collector.getRequestsInFlightByIdCount());

		MetricsCollector.HttpServerRouteStatusKey httpKey =
				new MetricsCollector.HttpServerRouteStatusKey(HttpMethod.GET,
						MetricsCollector.RouteType.MATCHED,
						ResourcePathDeclaration.fromPath("/widgets/{id}"), "2xx");
		MetricsCollector.HistogramSnapshot requestDuration = after
				.getHttpRequestDurations().get(httpKey);
		Assertions.assertNotNull(requestDuration);
		Assertions.assertEquals(1L, requestDuration.getCount(),
				"A negative custom duration must be counted at zero, not silently dropped");

		MetricsCollector.SseEventRouteKey sseKey =
				new MetricsCollector.SseEventRouteKey(
						MetricsCollector.RouteType.MATCHED,
						ResourcePathDeclaration.fromPath("/events/{id}"));
		Assertions.assertEquals(1L, after.getSseTimeToFirstEvent()
				.get(sseKey).getCount());
		Assertions.assertEquals(1L, after.getSseStreamDurations()
				.values().iterator().next().getCount());

		// Duplicate terminal callbacks neither underflow gauges nor duplicate samples.
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP,
				httpRequest, httpResource, response, Duration.ZERO, List.of());
		collector.didTerminateSseConnection(connection, termination);
		MetricsCollector.Snapshot duplicate = collector.snapshot().orElseThrow();
		Assertions.assertEquals(0L, duplicate.getActiveRequests());
		Assertions.assertEquals(0L, duplicate.getActiveSseStreams());
		Assertions.assertEquals(1L, duplicate.getHttpRequestDurations()
				.get(httpKey).getCount());
	}

	@Test
	public void unmatchedHttpFinishDoesNotCreatePhantomMetrics() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		ResourceMethod resourceMethod = resourceMethodFor(
				"/widgets/{id}", HttpMethod.GET, "widget", false);
		Request request = Request.withPath(HttpMethod.GET, "/widgets/1").build();
		MarshaledResponse response = MarshaledResponse.withStatusCode(500).build();

		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP,
				request, resourceMethod, response, Duration.ofSeconds(1L), List.of());

		MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();
		Assertions.assertEquals(0L, snapshot.getActiveRequests());
		Assertions.assertTrue(snapshot.getHttpRequestDurations().isEmpty());
		Assertions.assertTrue(snapshot.getHttpResponseBodyBytes().isEmpty());
	}

	@Test
	public void unmatchedMcpTerminalEventsAreFloorAwareAndSnapshotsRecover() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestFinished(
				ENDPOINT_PATH, JSON_RPC_METHOD, McpRequestOutcome.COMPLETE,
				Duration.ofNanos(1L)));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStreamClosed(
				ENDPOINT_PATH, JSON_RPC_METHOD, McpStreamTerminationReason.COMPLETED,
				Duration.ofNanos(1L)));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionClosed(
				ENDPOINT_PATH, McpStreamTerminationReason.COMPLETED,
				Duration.ofNanos(1L)));
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.handlerExecutionFinished());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.handlerDequeued());

		McpMetricsSnapshot snapshot = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(0L, snapshot.getActiveRequests());
		Assertions.assertEquals(0L, snapshot.getActiveRequestStreams());
		Assertions.assertEquals(0L, snapshot.getActiveSubscriptions());
		Assertions.assertEquals(0L, snapshot.getActiveHandlerExecutions());
		Assertions.assertEquals(0L, snapshot.getHandlerQueueDepth());
		Assertions.assertDoesNotThrow(() -> collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)));

		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStarted(
				ENDPOINT_PATH, JSON_RPC_METHOD));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestFinished(
				ENDPOINT_PATH, JSON_RPC_METHOD, McpRequestOutcome.COMPLETE,
				Duration.ofNanos(1L)));
		Assertions.assertEquals(0L, collector.snapshot().orElseThrow()
				.getMcpMetrics().getActiveRequests());
	}

	@Test
	public void histogramOverflowIsBoundedAndSumSaturates() {
		MetricsCollector.Histogram percentileHistogram =
				new MetricsCollector.Histogram(new long[]{10L});
		percentileHistogram.record(11L);
		MetricsCollector.HistogramSnapshot percentile =
				percentileHistogram.snapshot();
		Assertions.assertEquals(11L, percentile.getPercentile(99.0));
		Assertions.assertNotEquals(Long.MAX_VALUE,
				percentile.getPercentile(99.0));

		MetricsCollector.Histogram saturatingHistogram =
				new MetricsCollector.Histogram(new long[]{1L});
		saturatingHistogram.record(Long.MAX_VALUE);
		saturatingHistogram.record(Long.MAX_VALUE);
		MetricsCollector.HistogramSnapshot saturated =
				saturatingHistogram.snapshot();
		Assertions.assertEquals(2L, saturated.getCount());
		Assertions.assertEquals(Long.MAX_VALUE, saturated.getSum());
	}

	@Test
	public void textFormatsRetainRequiredHistogramAndCounterStructure() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		ResourceMethod resourceMethod = resourceMethodFor(
				"/widgets/{id}", HttpMethod.GET, "widget", false);
		Request request = Request.withPath(HttpMethod.GET, "/widgets/1").build();
		MarshaledResponse response = MarshaledResponse.withStatusCode(200).build();
		collector.didAcceptConnection(ServerType.STANDARD_HTTP, null);
		collector.didRecordTransportFailure(ServerType.STANDARD_HTTP,
				MetricsCollector.TransportFailureReason.WRITE_ERROR, null);
		collector.didStartRequestHandling(ServerType.STANDARD_HTTP,
				request, resourceMethod);
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP,
				request, resourceMethod, response, Duration.ZERO, List.of());
		collector.reset();

		String prometheus = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions
						.withMetricsFormat(MetricsCollector.MetricsFormat.PROMETHEUS)
						.includeZeroBuckets(false)
						.build()).orElseThrow();
		Assertions.assertTrue(prometheus.contains(
				"soklet_http_request_duration_nanos_bucket{method=\"GET\",route=\"/widgets/{id}\",status_class=\"2xx\",le=\"+Inf\"} 0"),
				prometheus);

		collector.didAcceptConnection(ServerType.STANDARD_HTTP, null);
		collector.didRecordTransportFailure(ServerType.STANDARD_HTTP,
				MetricsCollector.TransportFailureReason.WRITE_ERROR, null);
		String openMetrics = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions
						.withMetricsFormat(MetricsCollector.MetricsFormat.OPEN_METRICS_1_0)
						.histogramFormat(MetricsCollector.SnapshotTextOptions
								.HistogramFormat.COUNT_SUM_ONLY)
						.includeZeroBuckets(false)
						.build()).orElseThrow();
		Assertions.assertTrue(openMetrics.contains(
				"# HELP soklet_http_connections_accepted Total accepted HTTP connections"),
				openMetrics);
		Assertions.assertTrue(openMetrics.contains(
				"# TYPE soklet_http_connections_accepted counter"), openMetrics);
		Assertions.assertTrue(openMetrics.contains(
				"soklet_http_connections_accepted_total 1"), openMetrics);
		Assertions.assertFalse(openMetrics.contains(
				"# TYPE soklet_http_connections_accepted_total counter"), openMetrics);
		Assertions.assertTrue(openMetrics.contains(
				"soklet_http_request_duration_nanos_bucket{method=\"GET\",route=\"/widgets/{id}\",status_class=\"2xx\",le=\"+Inf\"} 0"),
				openMetrics);
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"), openMetrics);
		assertOpenMetricsFamilyStructure(openMetrics);
	}

	private static void assertOpenMetricsFamilyStructure(
			@NonNull String exposition) {
		List<String> lines = exposition.lines().toList();
		Assertions.assertFalse(lines.isEmpty());
		Assertions.assertEquals("# EOF", lines.get(lines.size() - 1));
		for (String line : lines) {
			if (!line.startsWith("# TYPE "))
				continue;

			String[] parts = line.split(" ", 4);
			Assertions.assertEquals(4, parts.length, line);
			String familyName = parts[2];
			String type = parts[3];
			Assertions.assertTrue(lines.stream().anyMatch(candidate ->
					candidate.startsWith("# HELP " + familyName + " ")), line);
			if (type.equals("counter")) {
				Assertions.assertFalse(familyName.endsWith("_total"), line);
				Assertions.assertTrue(lines.stream().anyMatch(candidate ->
						candidate.startsWith(familyName + "_total")), line);
			} else if (type.equals("histogram")) {
				Assertions.assertTrue(lines.stream().anyMatch(candidate ->
						candidate.startsWith(familyName + "_bucket")
								&& candidate.contains("le=\"+Inf\"")), line);
			}
		}
	}

	@NonNull
	private static ResourceMethod resourceMethodFor(@NonNull String path,
			@NonNull HttpMethod httpMethod, @NonNull String methodName,
			boolean sseEventSource) {
		try {
			Method method = TestResource.class.getDeclaredMethod(methodName);
			return ResourceMethod.fromComponents(httpMethod,
					ResourcePathDeclaration.fromPath(path), method, sseEventSource);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static final class TestResource {
		private void widget() {
		}

		private void report() {
		}

		private void events() {
		}
	}

	private static final class TestSseConnection implements SseConnection {
		@NonNull
		private final Request request;
		@NonNull
		private final ResourceMethod resourceMethod;

		private TestSseConnection(@NonNull Request request,
				@NonNull ResourceMethod resourceMethod) {
			this.request = request;
			this.resourceMethod = resourceMethod;
		}

		@Override
		@NonNull
		public Request getRequest() {
			return this.request;
		}

		@Override
		@NonNull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@Override
		@NonNull
		public Instant getEstablishedAt() {
			return Instant.EPOCH;
		}

		@Override
		@NonNull
		public Optional<Object> getClientContext() {
			return Optional.empty();
		}
	}
}
