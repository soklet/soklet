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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OpenMetrics 1.0 canonical bucket labels through the default collector. */
class OpenMetricsBucketLabelTests {
	@Test
	void httpLatencyLabelsUseCanonicalScientificNotation() {
		String text = render(collectorWithSamples(), MetricsCollector.MetricsFormat.OPEN_METRICS_1_0);
		assertBucketLabels(text, "soklet_http_request_duration_nanos",
				"1e+06", "2e+06", "5e+06", "1e+07", "2.5e+07", "5e+07",
				"1e+08", "2e+08", "4e+08", "8e+08", "1.5e+09", "3e+09",
				"7e+09", "1.5e+10", "+Inf");
		Assertions.assertTrue(text.endsWith("# EOF\n"));
	}

	@Test
	void byteLabelsCoverZeroFixedPointAndNonPowerOfTenScientificNotation() {
		String text = render(collectorWithSamples(), MetricsCollector.MetricsFormat.OPEN_METRICS_1_0);
		assertBucketLabels(text, "soklet_http_response_body_bytes",
				"0.0", "128.0", "256.0", "512.0", "1024.0", "2048.0", "4096.0", "8192.0",
				"16384.0", "32768.0", "65536.0", "131072.0", "262144.0", "524288.0",
				"1.048576e+06", "2.097152e+06", "4.194304e+06", "8.388608e+06", "+Inf");
	}

	@Test
	void sseQueueAndWriteHistogramsUseTheSameCanonicalFormatting() {
		String text = render(collectorWithSamples(), MetricsCollector.MetricsFormat.OPEN_METRICS_1_0);
		assertBucketLabels(text, "soklet_sse_queue_depth",
				"0.0", "1.0", "2.0", "4.0", "8.0", "16.0", "32.0", "64.0",
				"128.0", "256.0", "512.0", "1024.0", "+Inf");
		Assertions.assertTrue(bucketLabels(text, "soklet_sse_event_write_duration_nanos")
				.containsAll(List.of("0.0", "1e+06", "1.5e+10", "3e+10", "+Inf")));
	}

	@Test
	void mcpRequestStreamAndSubscriptionLabelsCoverLargeDurations() {
		String text = render(collectorWithSamples(), MetricsCollector.MetricsFormat.OPEN_METRICS_1_0);
		List<String> expected = List.of("1e+09", "5e+09", "1e+10", "3e+10", "6e+10", "1.2e+11",
				"3e+11", "6e+11", "1.8e+12", "3.6e+12", "7.2e+12", "1.44e+13", "+Inf");
		Assertions.assertEquals(expected, bucketLabels(text, "soklet_mcp_request_stream_duration_nanos"));
		Assertions.assertEquals(expected, bucketLabels(text, "soklet_mcp_subscription_duration_nanos"));
		Assertions.assertTrue(bucketLabels(text, "soklet_mcp_request_duration_nanos").contains("1e+06"));
	}

	@Test
	void prometheusLabelsAndNumericBucketMeaningRemainUnchanged() {
		DefaultMetricsCollector collector = collectorWithSamples();
		String prometheus = render(collector, MetricsCollector.MetricsFormat.PROMETHEUS);
		String openMetrics = render(collector, MetricsCollector.MetricsFormat.OPEN_METRICS_1_0);
		Assertions.assertTrue(bucketLabels(prometheus, "soklet_http_request_duration_nanos")
				.containsAll(List.of("1000000", "1500000000", "15000000000", "+Inf")));
		Assertions.assertTrue(bucketLabels(prometheus, "soklet_sse_queue_depth")
				.containsAll(List.of("0", "1", "1024", "+Inf")));
		Assertions.assertEquals(normalizedBucketSamples(prometheus), normalizedBucketSamples(openMetrics));
		Assertions.assertEquals(countAndSumSamples(prometheus), countAndSumSamples(openMetrics));
	}

	@Test
	void sampleFilterReceivesTheExactLabelUsedInTheSelectedFormat() {
		DefaultMetricsCollector collector = collectorWithSamples();
		for (MetricsCollector.MetricsFormat format : MetricsCollector.MetricsFormat.values()) {
			String selectedBoundary = format == MetricsCollector.MetricsFormat.OPEN_METRICS_1_0
					? "1e+06" : "1000000";
			MetricsCollector.SnapshotTextOptions options = MetricsCollector.SnapshotTextOptions
					.withMetricsFormat(format)
					.includeZeroBuckets(true)
					.metricFilter(sample -> !sample.getName().endsWith("_bucket")
							|| List.of(selectedBoundary, "+Inf").contains(sample.getLabels().get("le")))
					.build();
			String text = collector.snapshotText(options).orElseThrow();
			assertBucketLabels(text, "soklet_http_request_duration_nanos", selectedBoundary, "+Inf");
		}
	}

	@Test
	void openMetricsLabelsAreIndependentOfTheFormattingLocale() {
		Locale previous = Locale.getDefault(Locale.Category.FORMAT);
		try {
			Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE);
			String text = render(collectorWithSamples(), MetricsCollector.MetricsFormat.OPEN_METRICS_1_0);
			Assertions.assertTrue(bucketLabels(text, "soklet_http_response_body_bytes")
					.containsAll(List.of("128.0", "1.048576e+06", "+Inf")));
		} finally {
			Locale.setDefault(Locale.Category.FORMAT, previous);
		}
	}

	@Test
	void reducedAndResetHistogramsKeepTheRequiredInfinityBucket() {
		DefaultMetricsCollector collector = collectorWithSamples();
		collector.reset();
		String reduced = collector.snapshotText(MetricsCollector.SnapshotTextOptions
				.withMetricsFormat(MetricsCollector.MetricsFormat.OPEN_METRICS_1_0)
				.histogramFormat(MetricsCollector.SnapshotTextOptions.HistogramFormat.COUNT_SUM_ONLY)
				.includeZeroBuckets(false).build()).orElseThrow();
		assertBucketLabels(reduced, "soklet_http_request_duration_nanos", "+Inf");
		Assertions.assertTrue(reduced.contains("le=\"+Inf\"} 0"));
		String omitted = collector.snapshotText(MetricsCollector.SnapshotTextOptions
				.withMetricsFormat(MetricsCollector.MetricsFormat.OPEN_METRICS_1_0)
				.histogramFormat(MetricsCollector.SnapshotTextOptions.HistogramFormat.NONE)
				.build()).orElseThrow();
		Assertions.assertFalse(omitted.contains("soklet_http_request_duration_nanos"));
	}

	private static DefaultMetricsCollector collectorWithSamples() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		collector.initialize(SokletConfig.withHttpServer(HttpServer.fromPort(0))
				.sseServer(SseServer.fromPort(0)).metricsCollector(collector).build());
		Request request = Request.fromPath(HttpMethod.GET, "/bucket-labels");
		MarshaledResponse response = MarshaledResponse.withStatusCode(200).body(new byte[256]).build();
		collector.didStartRequestHandling(ServerType.HTTP, request, null);
		collector.didFinishRequestHandling(ServerType.HTTP, request, null, response, Duration.ofMillis(7), List.of());
		ResourceMethod resourceMethod;
		try {
			resourceMethod = ResourceMethod.fromComponents(HttpMethod.GET,
					ResourcePathDeclaration.fromPath("/bucket-labels"),
					OpenMetricsBucketLabelTests.class.getDeclaredMethod("sseResource"), true);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
		SseConnection connection = new SseConnection() {
			@Override public Request getRequest() { return request; }
			@Override public ResourceMethod getResourceMethod() { return resourceMethod; }
			@Override public Instant getEstablishedAt() { return Instant.EPOCH; }
			@Override public Optional<Object> getClientContext() { return Optional.empty(); }
		};
		collector.didWriteSseEvent(connection, SseEvent.withData("test").build(), Duration.ofMillis(1),
				Duration.ofMillis(1), 256, 1);
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStarted("/mcp", "tools/call"));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestFinished("/mcp", "tools/call",
				McpRequestOutcome.COMPLETE, Duration.ofMillis(7)));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStreamOpened("/mcp", "tools/call"));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStreamClosed("/mcp", "tools/call",
				McpStreamTerminationReason.COMPLETED, Duration.ofSeconds(2)));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionOpened("/mcp"));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.subscriptionClosed("/mcp",
				McpStreamTerminationReason.COMPLETED, Duration.ofSeconds(3)));
		return collector;
	}

	private static void sseResource() {}

	private static String render(DefaultMetricsCollector collector, MetricsCollector.MetricsFormat format) {
		return collector.snapshotText(MetricsCollector.SnapshotTextOptions.withMetricsFormat(format).build())
				.orElseThrow();
	}

	private static void assertBucketLabels(String text, String family, String... expected) {
		Assertions.assertEquals(List.of(expected), bucketLabels(text, family), family);
	}

	private static List<String> bucketLabels(String text, String family) {
		Pattern pattern = Pattern.compile("^" + family + "_bucket\\{.*le=\"([^\"]+)\"\\} \\d+$");
		return text.lines().map(pattern::matcher).filter(Matcher::matches).map(match -> match.group(1)).toList();
	}

	private static Map<String, String> normalizedBucketSamples(String text) {
		Pattern pattern = Pattern.compile("^(.*_bucket\\{.*le=\")([^\"]+)(\"\\}) (\\d+)$");
		Map<String, String> samples = new LinkedHashMap<>();
		text.lines().map(pattern::matcher).filter(Matcher::matches).forEach(match -> {
			String boundary = match.group(2).equals("+Inf") ? "+Inf"
					: new BigDecimal(match.group(2)).stripTrailingZeros().toPlainString();
			String key = match.group(1) + boundary + match.group(3);
			Assertions.assertNull(samples.put(key, match.group(4)), "Duplicate bucket " + key);
		});
		Assertions.assertFalse(samples.isEmpty());
		return samples;
	}

	private static List<String> countAndSumSamples(String text) {
		return text.lines().filter(line -> line.matches(".*_(count|sum)\\{.*")).sorted().toList();
	}
}
