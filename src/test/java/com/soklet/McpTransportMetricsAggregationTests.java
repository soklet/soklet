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

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.EnumMap;
import java.util.HashMap;
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
 * Focused public and default-collector coverage for the MCP transport-boundary
 * aggregate family.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpTransportMetricsAggregationTests {
	private static final String CONNECTIONS_ACCEPTED_METRIC_NAME =
			"soklet_mcp_connections_accepted_total";
	private static final String CONNECTIONS_REJECTED_METRIC_NAME =
			"soklet_mcp_connections_rejected_total";
	private static final String TRANSPORT_FAILURE_METRIC_NAME =
			"soklet_transport_failures_total";

	@Test
	public void snapshotContractUsesBoxedConnectionCountsAndImmutableBoundedTransportFailures()
			throws Exception {
		assertBoxedCountProperty("getConnectionsAccepted",
				"connectionsAccepted");
		assertBoxedCountProperty("getConnectionsRejected",
				"connectionsRejected");
		assertTransportFailureMapProperty();

		McpMetricsSnapshot empty = McpMetricsSnapshot.emptyInstance();
		Assertions.assertSame(empty, McpMetricsSnapshot.emptyInstance());
		Assertions.assertEquals(0L, empty.getConnectionsAccepted());
		Assertions.assertEquals(0L, empty.getConnectionsRejected());
		Assertions.assertTrue(empty.getTransportFailures().isEmpty());

		EnumMap<MetricsCollector.TransportFailureReason, Long> source =
				new EnumMap<>(MetricsCollector.TransportFailureReason.class);
		source.put(MetricsCollector.TransportFailureReason.WRITE_ERROR, 3L);
		source.put(MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT,
				0L);
		McpMetricsSnapshot snapshot = McpMetricsSnapshot.builder()
				.connectionsAccepted(2L)
				.connectionsRejected(1L)
				.transportFailures(source)
				.build();
		source.put(MetricsCollector.TransportFailureReason.WRITE_ERROR, 99L);

		Assertions.assertEquals(2L, snapshot.getConnectionsAccepted());
		Assertions.assertEquals(1L, snapshot.getConnectionsRejected());
		Assertions.assertEquals(List.of(
				MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT,
				MetricsCollector.TransportFailureReason.WRITE_ERROR),
				List.copyOf(snapshot.getTransportFailures().keySet()));
		Assertions.assertEquals(Map.of(
				MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT, 0L,
				MetricsCollector.TransportFailureReason.WRITE_ERROR, 3L),
				snapshot.getTransportFailures());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getTransportFailures().put(
						MetricsCollector.TransportFailureReason.UNKNOWN, 1L));

		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().connectionsAccepted(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().connectionsRejected(null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().connectionsAccepted(-1L));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().connectionsRejected(-1L));
		Assertions.assertDoesNotThrow(() -> McpMetricsSnapshot.builder()
				.connectionsAccepted(0L).connectionsRejected(0L).build());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().transportFailures(null));

		Map<MetricsCollector.TransportFailureReason, Long> nullKey =
				new HashMap<>();
		nullKey.put(null, 1L);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().transportFailures(nullKey));
		Map<MetricsCollector.TransportFailureReason, Long> nullValue =
				new HashMap<>();
		nullValue.put(MetricsCollector.TransportFailureReason.UNKNOWN, null);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().transportFailures(nullValue));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().transportFailures(Map.of(
						MetricsCollector.TransportFailureReason.UNKNOWN, -1L)));
	}

	@Test
	public void defaultCollectorAggregatesRendersFiltersAndResetsTransportBoundaryFamilies() {
		DefaultMetricsCollector eventDriven =
				DefaultMetricsCollector.defaultInstance();
		eventDriven.didRecordMcpMetricsEvent(
				McpMetricsEvent.connectionAccepted());
		String eventDrivenText = prometheus(eventDriven);
		assertSample(eventDrivenText, CONNECTIONS_ACCEPTED_METRIC_NAME, 1L);
		assertSample(eventDrivenText, CONNECTIONS_REJECTED_METRIC_NAME, 0L);

		DefaultMetricsCollector collector = configuredCollector();
		McpMetricsSnapshot configured = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(0L, configured.getConnectionsAccepted());
		Assertions.assertEquals(0L, configured.getConnectionsRejected());
		Assertions.assertTrue(configured.getTransportFailures().isEmpty());
		String configuredText = prometheus(collector);
		assertSample(configuredText, CONNECTIONS_ACCEPTED_METRIC_NAME, 0L);
		assertSample(configuredText, CONNECTIONS_REJECTED_METRIC_NAME, 0L);
		Assertions.assertFalse(configuredText.contains(
				TRANSPORT_FAILURE_METRIC_NAME + "{server_type=\"MCP\""),
				configuredText);

		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.connectionAccepted());
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.connectionAccepted());
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.connectionRejected());
		EnumMap<MetricsCollector.TransportFailureReason, Long> expectedFailures =
				new EnumMap<>(MetricsCollector.TransportFailureReason.class);
		for (MetricsCollector.TransportFailureReason reason
				: MetricsCollector.TransportFailureReason.values()) {
			long expectedCount = reason.ordinal() + 1L;
			expectedFailures.put(reason, expectedCount);
			for (long index = 0L; index < expectedCount; ++index)
				collector.didRecordMcpMetricsEvent(
						McpMetricsEvent.transportFailure(reason));
		}

		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		assertTransportSnapshot(retained, 2L, 1L, expectedFailures);

		Set<Map<String, String>> failureLabels =
				java.util.concurrent.ConcurrentHashMap.newKeySet();
		Set<Map<String, String>> connectionLabels =
				java.util.concurrent.ConcurrentHashMap.newKeySet();
		MetricsCollector.SnapshotTextOptions options =
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> {
							if (sample.getName().equals(
									TRANSPORT_FAILURE_METRIC_NAME)
									&& "MCP".equals(sample.getLabels()
									.get("server_type")))
								failureLabels.add(sample.getLabels());
							if (Set.of(CONNECTIONS_ACCEPTED_METRIC_NAME,
									CONNECTIONS_REJECTED_METRIC_NAME)
									.contains(sample.getName()))
								connectionLabels.add(sample.getLabels());
							return true;
						})
						.build();
		String text = collector.snapshotText(options).orElseThrow();
		assertMetricType(text, CONNECTIONS_ACCEPTED_METRIC_NAME,
				"Total accepted MCP connections admitted within the connection-capacity bound");
		assertMetricType(text, CONNECTIONS_REJECTED_METRIC_NAME,
				"Total MCP connections rejected because the connection-capacity bound was full");
		assertMetricType(text, TRANSPORT_FAILURE_METRIC_NAME,
				"Total low-level transport failures");
		assertSample(text, CONNECTIONS_ACCEPTED_METRIC_NAME, 2L);
		assertSample(text, CONNECTIONS_REJECTED_METRIC_NAME, 1L);
		Assertions.assertEquals(Set.of(Map.of()), connectionLabels,
				"MCP connection counters must remain label-free.");
		Assertions.assertFalse(text.contains(
				CONNECTIONS_ACCEPTED_METRIC_NAME + "{"), text);
		Assertions.assertFalse(text.contains(
				CONNECTIONS_REJECTED_METRIC_NAME + "{"), text);
		Set<Map<String, String>> expectedLabels =
				java.util.Arrays.stream(
						MetricsCollector.TransportFailureReason.values())
						.map(reason -> Map.of("server_type", "MCP", "reason",
								reason.name()))
						.collect(java.util.stream.Collectors.toUnmodifiableSet());
		Assertions.assertEquals(expectedLabels, failureLabels);
		for (Map.Entry<MetricsCollector.TransportFailureReason, Long> entry
				: expectedFailures.entrySet())
			Assertions.assertTrue(text.contains(TRANSPORT_FAILURE_METRIC_NAME
					+ "{server_type=\"MCP\",reason=\""
					+ entry.getKey().name() + "\"} " + entry.getValue() + "\n"),
					text);

		MetricsCollector.TransportFailureReason selectedReason =
				MetricsCollector.TransportFailureReason.EVENT_LOOP_TERMINATED;
		String selected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> sample.getName().equals(
								TRANSPORT_FAILURE_METRIC_NAME)
								&& sample.getLabels().equals(Map.of(
										"server_type", "MCP", "reason",
										selectedReason.name())))
						.build()).orElseThrow();
		Assertions.assertTrue(selected.contains(TRANSPORT_FAILURE_METRIC_NAME
				+ "{server_type=\"MCP\",reason=\""
				+ selectedReason.name() + "\"} "
				+ expectedFailures.get(selectedReason) + "\n"), selected);
		Assertions.assertEquals(1, occurrences(selected,
				TRANSPORT_FAILURE_METRIC_NAME + "{"));
		Assertions.assertFalse(selected.contains("soklet_http_"), selected);
		Assertions.assertFalse(selected.contains("soklet_mcp_connections_"),
				selected);

		String openMetrics = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
						MetricsCollector.MetricsFormat.OPEN_METRICS_1_0))
				.orElseThrow();
		assertSample(openMetrics, CONNECTIONS_ACCEPTED_METRIC_NAME, 2L);
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"), openMetrics);
		Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));

		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.connectionAccepted());
		Assertions.assertEquals(2L, retained.getConnectionsAccepted());
		Assertions.assertEquals(expectedFailures,
				retained.getTransportFailures());

		collector.reset();
		assertTransportSnapshot(collector.snapshot().orElseThrow().getMcpMetrics(),
				0L, 0L, Map.of());
		String resetText = prometheus(collector);
		assertSample(resetText, CONNECTIONS_ACCEPTED_METRIC_NAME, 0L);
		assertSample(resetText, CONNECTIONS_REJECTED_METRIC_NAME, 0L);
		Assertions.assertFalse(resetText.contains(
				TRANSPORT_FAILURE_METRIC_NAME + "{server_type=\"MCP\""), resetText);
		assertTransportSnapshot(retained, 2L, 1L, expectedFailures);
	}

	@Test
	public void sharedTransportFamilyCombinesServerTypesWithSingleMetadataBlock() {
		DefaultMetricsCollector collector = configuredCollector();
		collector.didRecordTransportFailure(ServerType.STANDARD_HTTP,
				MetricsCollector.TransportFailureReason.WRITE_ERROR, null);
		collector.didRecordTransportFailure(ServerType.SSE,
				MetricsCollector.TransportFailureReason.CONNECTION_SETUP_ERROR, null);
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.transportFailure(
				MetricsCollector.TransportFailureReason.WRITE_TIMEOUT));

		Set<Map<String, String>> labels =
				java.util.concurrent.ConcurrentHashMap.newKeySet();
		String text = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> {
							if (sample.getName().equals(
									TRANSPORT_FAILURE_METRIC_NAME))
								labels.add(sample.getLabels());
							return true;
						})
						.build()).orElseThrow();
		Assertions.assertEquals(1, occurrences(text,
				"# HELP " + TRANSPORT_FAILURE_METRIC_NAME + " "));
		Assertions.assertEquals(1, occurrences(text,
				"# TYPE " + TRANSPORT_FAILURE_METRIC_NAME + " counter\n"));
		Assertions.assertEquals(Set.of(
				Map.of("server_type", "STANDARD_HTTP", "reason", "WRITE_ERROR"),
				Map.of("server_type", "SSE", "reason",
						"CONNECTION_SETUP_ERROR"),
				Map.of("server_type", "MCP", "reason", "WRITE_TIMEOUT")),
				labels);
		Assertions.assertTrue(text.contains(TRANSPORT_FAILURE_METRIC_NAME
				+ "{server_type=\"STANDARD_HTTP\",reason=\"WRITE_ERROR\"} 1\n"),
				text);
		Assertions.assertTrue(text.contains(TRANSPORT_FAILURE_METRIC_NAME
				+ "{server_type=\"SSE\",reason=\"CONNECTION_SETUP_ERROR\"} 1\n"),
				text);
		Assertions.assertTrue(text.contains(TRANSPORT_FAILURE_METRIC_NAME
				+ "{server_type=\"MCP\",reason=\"WRITE_TIMEOUT\"} 1\n"),
				text);

		String mcpOnly = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> sample.getName().equals(
								TRANSPORT_FAILURE_METRIC_NAME)
								&& "MCP".equals(sample.getLabels()
								.get("server_type")))
						.build()).orElseThrow();
		Assertions.assertEquals(1, occurrences(mcpOnly,
				TRANSPORT_FAILURE_METRIC_NAME + "{"));
		Assertions.assertTrue(mcpOnly.contains("server_type=\"MCP\""), mcpOnly);
		Assertions.assertFalse(mcpOnly.contains("STANDARD_HTTP"), mcpOnly);
		Assertions.assertFalse(mcpOnly.contains("server_type=\"SSE\""), mcpOnly);

		String withoutTransportFailures = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> !sample.getName().equals(
								TRANSPORT_FAILURE_METRIC_NAME))
						.build()).orElseThrow();
		Assertions.assertFalse(withoutTransportFailures.contains(
				"# HELP " + TRANSPORT_FAILURE_METRIC_NAME + " "),
				withoutTransportFailures);
		Assertions.assertFalse(withoutTransportFailures.contains(
				"# TYPE " + TRANSPORT_FAILURE_METRIC_NAME + " "),
				withoutTransportFailures);
		Assertions.assertFalse(withoutTransportFailures.contains(
				TRANSPORT_FAILURE_METRIC_NAME + "{"),
				withoutTransportFailures);
	}

	@Test
	@Timeout(15)
	public void concurrentDirectIngestIsLosslessAndRetainedSnapshotsRemainImmutable()
			throws Exception {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		int threadCount = 6;
		int rounds = 40;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			List<Future<?>> futures = new java.util.ArrayList<>();
			for (int thread = 0; thread < threadCount; ++thread)
				futures.add(executor.submit(() -> {
					start.await();
					for (int round = 0; round < rounds; ++round) {
						collector.didRecordMcpMetricsEvent(
								McpMetricsEvent.connectionAccepted());
						collector.didRecordMcpMetricsEvent(
								McpMetricsEvent.connectionRejected());
						for (MetricsCollector.TransportFailureReason reason
								: MetricsCollector.TransportFailureReason.values())
							collector.didRecordMcpMetricsEvent(
									McpMetricsEvent.transportFailure(reason));
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

		long expected = (long) threadCount * rounds;
		EnumMap<MetricsCollector.TransportFailureReason, Long> failures =
				new EnumMap<>(MetricsCollector.TransportFailureReason.class);
		for (MetricsCollector.TransportFailureReason reason
				: MetricsCollector.TransportFailureReason.values())
			failures.put(reason, expected);
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		assertTransportSnapshot(retained, expected, expected, failures);

		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.connectionAccepted());
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.transportFailure(
				MetricsCollector.TransportFailureReason.UNKNOWN));
		collector.reset();
		assertTransportSnapshot(retained, expected, expected, failures);
		assertTransportSnapshot(collector.snapshot().orElseThrow().getMcpMetrics(),
				0L, 0L, Map.of());
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

	private static void assertTransportFailureMapProperty() throws Exception {
		Method getter = McpMetricsSnapshot.class.getMethod(
				"getTransportFailures");
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertEquals(Map.class, getter.getReturnType());
		assertTransportFailureMapType(getter.getGenericReturnType(),
				getter.getAnnotatedReturnType());

		Method builder = McpMetricsSnapshot.Builder.class.getMethod(
				"transportFailures", Map.class);
		Assertions.assertTrue(Modifier.isPublic(builder.getModifiers()));
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				builder.getReturnType());
		Assertions.assertTrue(builder.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		assertTransportFailureMapType(builder.getGenericParameterTypes()[0],
				builder.getAnnotatedParameterTypes()[0]);
	}

	private static void assertTransportFailureMapType(@NonNull Object genericType,
			@NonNull AnnotatedType annotatedType) {
		ParameterizedType parameterized = Assertions.assertInstanceOf(
				ParameterizedType.class, requireNonNull(genericType));
		Assertions.assertEquals(Map.class, parameterized.getRawType());
		Assertions.assertArrayEquals(new Object[]{
				MetricsCollector.TransportFailureReason.class, Long.class
		}, parameterized.getActualTypeArguments());
		Assertions.assertTrue(requireNonNull(annotatedType)
				.isAnnotationPresent(NonNull.class));
		AnnotatedParameterizedType annotated = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, annotatedType);
		for (AnnotatedType argument : annotated.getAnnotatedActualTypeArguments())
			Assertions.assertTrue(argument.isAnnotationPresent(NonNull.class),
					argument.toString());
	}

	private static void assertTransportSnapshot(@NonNull McpMetricsSnapshot snapshot,
			long accepted, long rejected,
			@NonNull Map<MetricsCollector.TransportFailureReason, Long> failures) {
		Assertions.assertEquals(accepted,
				requireNonNull(snapshot).getConnectionsAccepted());
		Assertions.assertEquals(rejected, snapshot.getConnectionsRejected());
		Assertions.assertEquals(requireNonNull(failures),
				snapshot.getTransportFailures());
	}

	private static void assertMetricType(@NonNull String text,
			@NonNull String metricName, @NonNull String help) {
		Assertions.assertTrue(requireNonNull(text).contains("# HELP "
				+ requireNonNull(metricName) + " " + requireNonNull(help) + "\n"),
				text);
		Assertions.assertTrue(text.contains("# TYPE " + metricName
				+ " counter\n"), text);
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
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp/transport-metrics")
				.serverInformation(McpImplementation.withNameAndVersion(
						"transport-metrics-test", "3.6.0-SNAPSHOT").build())
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
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector)
				.build();
		collector.initialize(config);
		return collector;
	}
}
