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
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Focused public and default-collector coverage for the MCP request-admission
 * aggregate family.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpRequestAdmissionMetricsAggregationTests {
	private static final String REQUESTS_ACCEPTED_METRIC_NAME =
			"soklet_mcp_requests_accepted_total";
	private static final String REQUESTS_ACCEPTED_HELP =
			"Total MCP requests accepted by the bounded protocol processor";
	private static final String REQUESTS_REJECTED_METRIC_NAME =
			"soklet_mcp_requests_rejected_total";
	private static final String REQUESTS_REJECTED_HELP =
			"Total MCP requests rejected before admitted semantic handling";

	@Test
	public void snapshotContractUsesBoxedNonnegativeRequestAdmissionCounts()
			throws Exception {
		assertBoxedCountProperty("getRequestsAccepted", "requestsAccepted");
		assertBoxedCountProperty("getRequestsRejected", "requestsRejected");

		McpMetricsSnapshot empty = McpMetricsSnapshot.emptyInstance();
		Assertions.assertSame(empty, McpMetricsSnapshot.emptyInstance());
		Assertions.assertEquals(0L, empty.getRequestsAccepted());
		Assertions.assertEquals(0L, empty.getRequestsRejected());

		McpMetricsSnapshot snapshot = McpMetricsSnapshot.builder()
				.requestsAccepted(3L)
				.requestsRejected(1L)
				.build();
		Assertions.assertEquals(3L, snapshot.getRequestsAccepted());
		Assertions.assertEquals(1L, snapshot.getRequestsRejected());

		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().requestsAccepted(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().requestsRejected(null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().requestsAccepted(-1L));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().requestsRejected(-1L));
		Assertions.assertDoesNotThrow(() -> McpMetricsSnapshot.builder()
				.requestsAccepted(0L).requestsRejected(0L).build());
	}

	@Test
	public void defaultCollectorAggregatesConfiguredAndDirectRequestAdmissionEventsAcrossRenderFilterAndReset() {
		DefaultMetricsCollector acceptedDriven =
				DefaultMetricsCollector.defaultInstance();
		Assertions.assertFalse(prometheus(acceptedDriven).contains(
				"soklet_mcp_requests_"));
		acceptedDriven.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestAccepted());
		assertAdmissionSnapshot(acceptedDriven, 1L, 0L);
		assertAdmissionSamples(prometheus(acceptedDriven), 1L, 0L);
		acceptedDriven.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				acceptedDriven.snapshot().orElseThrow().getMcpMetrics());
		assertAdmissionSamples(prometheus(acceptedDriven), 0L, 0L);

		DefaultMetricsCollector rejectedDriven =
				DefaultMetricsCollector.defaultInstance();
		rejectedDriven.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestRejected());
		assertAdmissionSnapshot(rejectedDriven, 0L, 1L);
		assertAdmissionSamples(prometheus(rejectedDriven), 0L, 1L);

		DefaultMetricsCollector collector = configuredCollector();
		assertAdmissionSnapshot(collector, 0L, 0L);
		assertAdmissionSamples(prometheus(collector), 0L, 0L);
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestAccepted());
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestAccepted());
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestRejected());

		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(2L, retained.getRequestsAccepted());
		Assertions.assertEquals(1L, retained.getRequestsRejected(),
				"Accepted and rejected processor events are independent counts, "
						+ "not complementary outcomes.");

		Set<Map<String, String>> labels =
				java.util.concurrent.ConcurrentHashMap.newKeySet();
		String selected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> {
							if (!sample.getName().equals(
									REQUESTS_ACCEPTED_METRIC_NAME)
									&& !sample.getName().equals(
									REQUESTS_REJECTED_METRIC_NAME))
								return false;
							labels.add(sample.getLabels());
							return true;
						})
						.build()).orElseThrow();
		assertMetricType(selected, REQUESTS_ACCEPTED_METRIC_NAME,
				REQUESTS_ACCEPTED_HELP);
		assertMetricType(selected, REQUESTS_REJECTED_METRIC_NAME,
				REQUESTS_REJECTED_HELP);
		assertAdmissionSamples(selected, 2L, 1L);
		Assertions.assertEquals(Set.of(Map.of()), labels,
				"MCP request-admission counters must remain label-free.");
		Assertions.assertFalse(selected.contains(
				REQUESTS_ACCEPTED_METRIC_NAME + "{"), selected);
		Assertions.assertFalse(selected.contains(
				REQUESTS_REJECTED_METRIC_NAME + "{"), selected);
		Assertions.assertEquals(1, occurrences(selected,
				"# HELP " + REQUESTS_ACCEPTED_METRIC_NAME + " "));
		Assertions.assertEquals(1, occurrences(selected,
				"# TYPE " + REQUESTS_ACCEPTED_METRIC_NAME + " counter\n"));
		Assertions.assertEquals(1, occurrences(selected,
				"# HELP " + REQUESTS_REJECTED_METRIC_NAME + " "));
		Assertions.assertEquals(1, occurrences(selected,
				"# TYPE " + REQUESTS_REJECTED_METRIC_NAME + " counter\n"));

		String rejected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> !sample.getName().startsWith(
								"soklet_mcp_requests_"))
						.build()).orElseThrow();
		for (String metricName : List.of(REQUESTS_ACCEPTED_METRIC_NAME,
				REQUESTS_REJECTED_METRIC_NAME)) {
			Assertions.assertFalse(rejected.contains("# HELP " + metricName + " "),
					rejected);
			Assertions.assertFalse(rejected.contains("# TYPE " + metricName + " "),
					rejected);
			Assertions.assertFalse(rejected.contains(metricName), rejected);
		}

		String openMetrics = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
						MetricsCollector.MetricsFormat.OPEN_METRICS_1_0))
				.orElseThrow();
		assertAdmissionSamples(openMetrics, 2L, 1L);
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"), openMetrics);
		Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));

		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestAccepted());
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestRejected());
		Assertions.assertEquals(2L, retained.getRequestsAccepted());
		Assertions.assertEquals(1L, retained.getRequestsRejected());
		assertAdmissionSnapshot(collector, 3L, 2L);
		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		assertAdmissionSamples(prometheus(collector), 0L, 0L);
		Assertions.assertEquals(2L, retained.getRequestsAccepted());
		Assertions.assertEquals(1L, retained.getRequestsRejected());
	}

	@Test
	@Timeout(60)
	public void concurrentDirectRequestAdmissionIngestIsLosslessAndRetainedSnapshotsRemainImmutable()
			throws Exception {
		DefaultMetricsCollector collector =
				DefaultMetricsCollector.defaultInstance();
		int threadCount = 6;
		int rounds = 60;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			List<Future<?>> futures = new java.util.ArrayList<>();
			for (int thread = 0; thread < threadCount; ++thread)
				futures.add(executor.submit(() -> {
					start.await();
					for (int round = 0; round < rounds; ++round) {
						collector.didRecordMcpMetricsEvent(
								McpMetricsEvent.requestAccepted());
						collector.didRecordMcpMetricsEvent(
								McpMetricsEvent.requestAccepted());
						collector.didRecordMcpMetricsEvent(
								McpMetricsEvent.requestRejected());
					}
					return null;
				}));
			start.countDown();
			for (Future<?> future : futures)
				future.get(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5,
					TimeUnit.SECONDS));
		}

		long expectedAccepted = (long) threadCount * rounds * 2L;
		long expectedRejected = (long) threadCount * rounds;
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(expectedAccepted,
				retained.getRequestsAccepted());
		Assertions.assertEquals(expectedRejected,
				retained.getRequestsRejected());
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestAccepted());
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.requestRejected());
		collector.reset();
		Assertions.assertEquals(expectedAccepted,
				retained.getRequestsAccepted());
		Assertions.assertEquals(expectedRejected,
				retained.getRequestsRejected());
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
	}

	private static void assertBoxedCountProperty(@NonNull String getterName,
			@NonNull String builderName) throws Exception {
		Method getter = McpMetricsSnapshot.class.getMethod(
				requireNonNull(getterName));
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertEquals(Long.class, getter.getReturnType());
		Assertions.assertTrue(getter.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));

		Method builder = McpMetricsSnapshot.Builder.class.getMethod(
				requireNonNull(builderName), Long.class);
		Assertions.assertTrue(Modifier.isPublic(builder.getModifiers()));
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				builder.getReturnType());
		Assertions.assertTrue(builder.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(builder.getAnnotatedParameterTypes()[0]
				.isAnnotationPresent(NonNull.class));
	}

	private static void assertAdmissionSnapshot(
			@NonNull DefaultMetricsCollector collector, long accepted,
			long rejected) {
		McpMetricsSnapshot snapshot = requireNonNull(collector).snapshot()
				.orElseThrow().getMcpMetrics();
		Assertions.assertEquals(accepted, snapshot.getRequestsAccepted());
		Assertions.assertEquals(rejected, snapshot.getRequestsRejected());
	}

	private static void assertMetricType(@NonNull String text,
			@NonNull String metricName, @NonNull String help) {
		Assertions.assertTrue(requireNonNull(text).contains("# HELP "
				+ requireNonNull(metricName) + " " + requireNonNull(help) + "\n"),
				text);
		Assertions.assertTrue(text.contains("# TYPE " + metricName
				+ " counter\n"), text);
	}

	private static void assertAdmissionSamples(@NonNull String text,
			long accepted, long rejected) {
		assertSample(text, REQUESTS_ACCEPTED_METRIC_NAME, accepted);
		assertSample(text, REQUESTS_REJECTED_METRIC_NAME, rejected);
	}

	private static void assertSample(@NonNull String text,
			@NonNull String metricName, long value) {
		Assertions.assertTrue(requireNonNull(text).contains(
				requireNonNull(metricName) + " " + value + "\n"), text);
	}

	@NonNull
	private static String prometheus(@NonNull MetricsCollector collector) {
		return requireNonNull(collector).snapshotText(
				MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS))
				.orElseThrow();
	}

	private static int occurrences(@NonNull String value,
			@NonNull String substring) {
		int count = 0;
		int index = 0;
		while ((index = requireNonNull(value).indexOf(
				requireNonNull(substring), index)) >= 0) {
			++count;
			index += substring.length();
		}
		return count;
	}

	@NonNull
	private static DefaultMetricsCollector configuredCollector() {
		DefaultMetricsCollector collector =
				DefaultMetricsCollector.defaultInstance();
		McpEndpoint endpoint = McpEndpoint
				.withPath("/mcp/request-admission-metrics",
						McpImplementation.withNameAndVersion(
						"request-admission-metrics-test", "4.0.0")
						.build())
				.build();
		McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host("127.0.0.1")
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector)
				.build();
		collector.initialize(config);
		return collector;
	}
}
