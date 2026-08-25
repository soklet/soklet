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
 * Focused public and default-collector coverage for the MCP keep-alive
 * aggregate family.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpKeepAliveMetricsAggregationTests {
	private static final String KEEP_ALIVES_METRIC_NAME =
			"soklet_mcp_keep_alives_emitted_total";
	private static final String KEEP_ALIVES_HELP =
			"Total MCP keep-alive comments accepted for delivery";

	@Test
	public void snapshotContractUsesBoxedNonnegativeKeepAliveCount()
			throws Exception {
		Method getter = McpMetricsSnapshot.class.getMethod(
				"getKeepAlivesEmitted");
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertEquals(Long.class, getter.getReturnType());
		Assertions.assertTrue(getter.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));

		Method builder = McpMetricsSnapshot.Builder.class.getMethod(
				"keepAlivesEmitted", Long.class);
		Assertions.assertTrue(Modifier.isPublic(builder.getModifiers()));
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				builder.getReturnType());
		Assertions.assertTrue(builder.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(builder.getAnnotatedParameterTypes()[0]
				.isAnnotationPresent(NonNull.class));

		McpMetricsSnapshot empty = McpMetricsSnapshot.emptyInstance();
		Assertions.assertSame(empty, McpMetricsSnapshot.emptyInstance());
		Assertions.assertEquals(0L, empty.getKeepAlivesEmitted());
		Assertions.assertEquals(3L, McpMetricsSnapshot.builder()
				.keepAlivesEmitted(3L).build().getKeepAlivesEmitted());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().keepAlivesEmitted(null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().keepAlivesEmitted(-1L));
		Assertions.assertDoesNotThrow(() -> McpMetricsSnapshot.builder()
				.keepAlivesEmitted(0L).build());
	}

	@Test
	public void defaultCollectorAggregatesConfiguredAndDirectKeepAlivesAcrossRenderFilterAndReset() {
		DefaultMetricsCollector eventDriven =
				DefaultMetricsCollector.defaultInstance();
		Assertions.assertFalse(prometheus(eventDriven).contains(
				KEEP_ALIVES_METRIC_NAME));
		eventDriven.didRecordMcpMetricsEvent(
				McpMetricsEvent.keepAliveEmitted());
		Assertions.assertEquals(1L, eventDriven.snapshot().orElseThrow()
				.getMcpMetrics().getKeepAlivesEmitted());
		assertMetricType(prometheus(eventDriven));
		assertSample(prometheus(eventDriven), 1L);
		eventDriven.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				eventDriven.snapshot().orElseThrow().getMcpMetrics());
		assertMetricType(prometheus(eventDriven));
		assertSample(prometheus(eventDriven), 0L);

		DefaultMetricsCollector collector = configuredCollector();
		Assertions.assertEquals(0L, collector.snapshot().orElseThrow()
				.getMcpMetrics().getKeepAlivesEmitted());
		assertMetricType(prometheus(collector));
		assertSample(prometheus(collector), 0L);
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.keepAliveEmitted());
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.keepAliveEmitted());

		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(2L, retained.getKeepAlivesEmitted());
		Set<Map<String, String>> labels =
				java.util.concurrent.ConcurrentHashMap.newKeySet();
		String selected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> {
							if (!sample.getName().equals(
									KEEP_ALIVES_METRIC_NAME))
								return false;
							labels.add(sample.getLabels());
							return true;
						})
						.build()).orElseThrow();
		assertMetricType(selected);
		assertSample(selected, 2L);
		Assertions.assertEquals(Set.of(Map.of()), labels,
				"Accepted keep-alive comments must remain a label-free count.");
		Assertions.assertFalse(selected.contains(
				KEEP_ALIVES_METRIC_NAME + "{"), selected);
		Assertions.assertEquals(1, occurrences(selected,
				"# HELP " + KEEP_ALIVES_METRIC_NAME + " "));
		Assertions.assertEquals(1, occurrences(selected,
				"# TYPE " + KEEP_ALIVES_METRIC_NAME + " counter\n"));

		String rejected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> !sample.getName().equals(
								KEEP_ALIVES_METRIC_NAME))
						.build()).orElseThrow();
		Assertions.assertFalse(rejected.contains(
				"# HELP " + KEEP_ALIVES_METRIC_NAME + " "), rejected);
		Assertions.assertFalse(rejected.contains(
				"# TYPE " + KEEP_ALIVES_METRIC_NAME + " "), rejected);
		Assertions.assertFalse(rejected.contains(KEEP_ALIVES_METRIC_NAME),
				rejected);

		String openMetrics = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
						MetricsCollector.MetricsFormat.OPEN_METRICS_1_0))
				.orElseThrow();
		assertSample(openMetrics, 2L);
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"), openMetrics);
		Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));

		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.keepAliveEmitted());
		Assertions.assertEquals(2L, retained.getKeepAlivesEmitted());
		Assertions.assertEquals(3L, collector.snapshot().orElseThrow()
				.getMcpMetrics().getKeepAlivesEmitted());
		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		assertMetricType(prometheus(collector));
		assertSample(prometheus(collector), 0L);
		Assertions.assertEquals(2L, retained.getKeepAlivesEmitted());
	}

	@Test
	@Timeout(15)
	public void concurrentDirectKeepAliveIngestIsLosslessAndRetainedSnapshotsRemainImmutable()
			throws Exception {
		DefaultMetricsCollector collector =
				DefaultMetricsCollector.defaultInstance();
		int threadCount = 6;
		int rounds = 80;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			List<Future<?>> futures = new java.util.ArrayList<>();
			for (int thread = 0; thread < threadCount; ++thread)
				futures.add(executor.submit(() -> {
					start.await();
					for (int round = 0; round < rounds; ++round)
						collector.didRecordMcpMetricsEvent(
								McpMetricsEvent.keepAliveEmitted());
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

		long expected = (long) threadCount * rounds;
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(expected, retained.getKeepAlivesEmitted());
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.keepAliveEmitted());
		collector.reset();
		Assertions.assertEquals(expected, retained.getKeepAlivesEmitted());
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		assertSample(prometheus(collector), 0L);
	}

	private static void assertMetricType(@NonNull String text) {
		Assertions.assertTrue(requireNonNull(text).contains("# HELP "
				+ KEEP_ALIVES_METRIC_NAME + " " + KEEP_ALIVES_HELP + "\n"),
				text);
		Assertions.assertTrue(text.contains("# TYPE "
				+ KEEP_ALIVES_METRIC_NAME + " counter\n"), text);
	}

	private static void assertSample(@NonNull String text, long value) {
		Assertions.assertTrue(requireNonNull(text).contains(
				KEEP_ALIVES_METRIC_NAME + " " + value + "\n"), text);
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
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp/keep-alive-metrics")
				.serverInformation(McpImplementation.withNameAndVersion(
						"keep-alive-metrics-test", "4.0.0-SNAPSHOT")
						.build())
				.build();
		McpServer server = McpServer.withPort(0)
				.host("127.0.0.1")
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
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
