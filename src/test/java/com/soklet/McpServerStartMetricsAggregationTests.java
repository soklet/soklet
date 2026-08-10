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
 * Focused public and default-collector coverage for the MCP server-start
 * aggregate family.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpServerStartMetricsAggregationTests {
	private static final String SERVER_STARTS_METRIC_NAME =
			"soklet_mcp_server_starts_total";
	private static final String SERVER_STARTS_HELP =
			"Total successful MCP server starts";
	private static final String SHUTDOWNS_METRIC_NAME =
			"soklet_mcp_shutdowns_total";

	@Test
	public void snapshotContractUsesBoxedNonnegativeServerStarts()
			throws Exception {
		Method getter = McpMetricsSnapshot.class.getMethod("getServerStarts");
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertEquals(Long.class, getter.getReturnType());
		Assertions.assertTrue(getter.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));

		Method builder = McpMetricsSnapshot.Builder.class.getMethod(
				"serverStarts", Long.class);
		Assertions.assertTrue(Modifier.isPublic(builder.getModifiers()));
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				builder.getReturnType());
		Assertions.assertTrue(builder.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(builder.getAnnotatedParameterTypes()[0]
				.isAnnotationPresent(NonNull.class));

		McpMetricsSnapshot empty = McpMetricsSnapshot.emptyInstance();
		Assertions.assertSame(empty, McpMetricsSnapshot.emptyInstance());
		Assertions.assertEquals(0L, empty.getServerStarts());
		Assertions.assertEquals(3L, McpMetricsSnapshot.builder()
				.serverStarts(3L).build().getServerStarts());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().serverStarts(null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().serverStarts(-1L));
		Assertions.assertDoesNotThrow(() -> McpMetricsSnapshot.builder()
				.serverStarts(0L).build());
	}

	@Test
	public void defaultCollectorAggregatesConfiguredAndDirectServerStartsAcrossRenderFilterAndReset() {
		DefaultMetricsCollector stopDriven =
				DefaultMetricsCollector.defaultInstance();
		stopDriven.didRecordMcpMetricsEvent(new McpMetricsEvent.ServerStopped(
				McpShutdownOutcome.CLEAN));
		String stopDrivenText = prometheus(stopDriven);
		assertMetricType(stopDrivenText);
		assertSample(stopDrivenText, 0L);
		Assertions.assertTrue(stopDrivenText.contains(SHUTDOWNS_METRIC_NAME
				+ "{outcome=\"clean\"} 1\n"), stopDrivenText);
		stopDriven.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				stopDriven.snapshot().orElseThrow().getMcpMetrics());
		String resetStopDrivenText = prometheus(stopDriven);
		assertSample(resetStopDrivenText, 0L);
		Assertions.assertFalse(resetStopDrivenText.contains(
				SHUTDOWNS_METRIC_NAME + "{"), resetStopDrivenText);

		DefaultMetricsCollector eventDriven =
				DefaultMetricsCollector.defaultInstance();
		Assertions.assertFalse(prometheus(eventDriven).contains(
				SERVER_STARTS_METRIC_NAME));
		eventDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ServerStarted());
		assertSample(prometheus(eventDriven), 1L);
		eventDriven.reset();
		assertSample(prometheus(eventDriven), 0L);

		DefaultMetricsCollector collector = configuredCollector();
		Assertions.assertEquals(0L, collector.snapshot().orElseThrow()
				.getMcpMetrics().getServerStarts());
		assertSample(prometheus(collector), 0L);
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ServerStarted());
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ServerStarted());

		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(2L, retained.getServerStarts());
		Set<Map<String, String>> labels =
				java.util.concurrent.ConcurrentHashMap.newKeySet();
		String selected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> {
							if (!sample.getName().equals(
									SERVER_STARTS_METRIC_NAME))
								return false;
							labels.add(sample.getLabels());
							return true;
						})
						.build()).orElseThrow();
		assertMetricType(selected);
		assertSample(selected, 2L);
		Assertions.assertEquals(Set.of(Map.of()), labels,
				"The MCP server-start counter must remain label-free.");
		Assertions.assertFalse(selected.contains(
				SERVER_STARTS_METRIC_NAME + "{"), selected);
		Assertions.assertEquals(1, occurrences(selected,
				"# HELP " + SERVER_STARTS_METRIC_NAME + " "));
		Assertions.assertEquals(1, occurrences(selected,
				"# TYPE " + SERVER_STARTS_METRIC_NAME + " counter\n"));

		String rejected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> !sample.getName().equals(
								SERVER_STARTS_METRIC_NAME))
						.build()).orElseThrow();
		Assertions.assertFalse(rejected.contains(
				"# HELP " + SERVER_STARTS_METRIC_NAME + " "), rejected);
		Assertions.assertFalse(rejected.contains(
				"# TYPE " + SERVER_STARTS_METRIC_NAME + " "), rejected);
		Assertions.assertFalse(rejected.contains(SERVER_STARTS_METRIC_NAME),
				rejected);

		String openMetrics = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
						MetricsCollector.MetricsFormat.OPEN_METRICS_1_0))
				.orElseThrow();
		assertSample(openMetrics, 2L);
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"), openMetrics);
		Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));

		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ServerStarted());
		Assertions.assertEquals(2L, retained.getServerStarts());
		Assertions.assertEquals(3L, collector.snapshot().orElseThrow()
				.getMcpMetrics().getServerStarts());
		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		assertSample(prometheus(collector), 0L);
		Assertions.assertEquals(2L, retained.getServerStarts());
	}

	@Test
	@Timeout(15)
	public void concurrentDirectServerStartIngestIsLosslessAndRetainedSnapshotsRemainImmutable()
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
								new McpMetricsEvent.ServerStarted());
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
		Assertions.assertEquals(expected, retained.getServerStarts());
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ServerStarted());
		collector.reset();
		Assertions.assertEquals(expected, retained.getServerStarts());
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
	}

	private static void assertMetricType(@NonNull String text) {
		Assertions.assertTrue(requireNonNull(text).contains("# HELP "
				+ SERVER_STARTS_METRIC_NAME + " " + SERVER_STARTS_HELP + "\n"),
				text);
		Assertions.assertTrue(text.contains("# TYPE "
				+ SERVER_STARTS_METRIC_NAME + " counter\n"), text);
	}

	private static void assertSample(@NonNull String text, long value) {
		Assertions.assertTrue(requireNonNull(text).contains(
				SERVER_STARTS_METRIC_NAME + " " + value + "\n"), text);
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
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp/server-start-metrics")
				.serverInformation(McpImplementation.withNameAndVersion(
						"server-start-metrics-test", "3.6.0-SNAPSHOT").build())
				.build();
		McpServer server = McpServer.withPort(0)
				.host("127.0.0.1")
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
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
