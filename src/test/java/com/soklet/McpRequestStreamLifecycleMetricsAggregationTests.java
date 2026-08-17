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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Focused public and default-collector coverage for the MCP request-stream
 * lifecycle aggregate family.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpRequestStreamLifecycleMetricsAggregationTests {
	private static final String ENDPOINT_PATH =
			"/mcp/request-stream-lifecycle-metrics";
	private static final String JSON_RPC_METHOD = "tools/call";
	private static final String ACTIVE_STREAMS_METRIC_NAME =
			"soklet_mcp_request_streams_active";
	private static final String STREAM_DURATIONS_METRIC_NAME =
			"soklet_mcp_request_stream_duration_nanos";
	private static final String ACTIVE_STREAMS_HELP =
			"Currently active MCP request streams";
	private static final String STREAM_DURATIONS_HELP =
			"MCP request-stream duration in nanoseconds";
	private static final long[] STREAM_DURATION_BUCKETS_NANOS = {
			1_000_000_000L, 5_000_000_000L, 10_000_000_000L,
			30_000_000_000L, 60_000_000_000L, 120_000_000_000L,
			300_000_000_000L, 600_000_000_000L, 1_800_000_000_000L,
			3_600_000_000_000L, 7_200_000_000_000L,
			14_400_000_000_000L, Long.MAX_VALUE
	};

	@Test
	public void snapshotContractUsesReferenceTypedImmutableRequestStreamLifecycleState()
			throws Exception {
		assertBoxedCountProperty("getActiveRequestStreams",
				"activeRequestStreams");
		assertStreamDurationMapProperty();

		Class<McpMetricsSnapshot.RequestStreamTerminationKey> keyType =
				McpMetricsSnapshot.RequestStreamTerminationKey.class;
		Assertions.assertTrue(keyType.isRecord());
		Assertions.assertTrue(Modifier.isPublic(keyType.getModifiers()));
		Assertions.assertTrue(Modifier.isStatic(keyType.getModifiers()));
		Assertions.assertTrue(Modifier.isFinal(keyType.getModifiers()));
		RecordComponent[] components = keyType.getRecordComponents();
		Assertions.assertEquals(List.of("endpointPath", "jsonRpcMethod",
				"reason"), Arrays.stream(components)
				.map(RecordComponent::getName).toList());
		Assertions.assertEquals(List.of(String.class, String.class,
				McpStreamTerminationReason.class), Arrays.stream(components)
				.map(RecordComponent::getType).toList());
		for (RecordComponent component : components) {
			Assertions.assertTrue(component.getAnnotatedType()
					.isAnnotationPresent(NonNull.class), component.toString());
			Assertions.assertTrue(component.getAccessor().getAnnotatedReturnType()
					.isAnnotationPresent(NonNull.class), component.toString());
		}
		Constructor<McpMetricsSnapshot.RequestStreamTerminationKey> constructor =
				keyType.getConstructor(String.class, String.class,
						McpStreamTerminationReason.class);
		Assertions.assertTrue(Modifier.isPublic(constructor.getModifiers()));
		for (AnnotatedType parameter : constructor.getAnnotatedParameterTypes())
			Assertions.assertTrue(parameter.isAnnotationPresent(NonNull.class),
					parameter.toString());

		McpMetricsSnapshot empty = McpMetricsSnapshot.emptyInstance();
		Assertions.assertSame(empty, McpMetricsSnapshot.emptyInstance());
		Assertions.assertEquals(0L, empty.getActiveRequestStreams());
		Assertions.assertTrue(empty.getRequestStreamDurations().isEmpty());

		McpMetricsSnapshot.RequestStreamTerminationKey completedKey =
				key(McpStreamTerminationReason.COMPLETED);
		McpMetricsSnapshot.RequestStreamTerminationKey applicationKey =
				new McpMetricsSnapshot.RequestStreamTerminationKey(
						"/application-defined", "vendor.example/arbitrary",
						McpStreamTerminationReason.INTERNAL_ERROR);
		MetricsCollector.HistogramSnapshot completedHistogram =
				new MetricsCollector.HistogramSnapshot(
						new long[]{1L, Long.MAX_VALUE}, new long[]{0L, 1L},
						1L, 2L, 2L, 2L);
		MetricsCollector.HistogramSnapshot applicationHistogram =
				new MetricsCollector.HistogramSnapshot(
						new long[]{Long.MAX_VALUE}, new long[]{0L},
						0L, 0L, 0L, 0L);
		Map<McpMetricsSnapshot.RequestStreamTerminationKey,
				MetricsCollector.HistogramSnapshot> source =
				new LinkedHashMap<>();
		source.put(completedKey, completedHistogram);
		source.put(applicationKey, applicationHistogram);
		McpMetricsSnapshot snapshot = McpMetricsSnapshot.builder()
				.activeRequestStreams(2L)
				.requestStreamDurations(source)
				.build();
		source.clear();
		Assertions.assertEquals(2L, snapshot.getActiveRequestStreams());
		Assertions.assertEquals(Map.of(completedKey, completedHistogram,
				applicationKey, applicationHistogram),
				snapshot.getRequestStreamDurations());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getRequestStreamDurations().clear());

		Assertions.assertThrows(NullPointerException.class,
				() -> new McpMetricsSnapshot.RequestStreamTerminationKey(null,
						JSON_RPC_METHOD, McpStreamTerminationReason.COMPLETED));
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpMetricsSnapshot.RequestStreamTerminationKey(
						ENDPOINT_PATH, null,
						McpStreamTerminationReason.COMPLETED));
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpMetricsSnapshot.RequestStreamTerminationKey(
						ENDPOINT_PATH, JSON_RPC_METHOD, null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpMetricsSnapshot.RequestStreamTerminationKey("",
						JSON_RPC_METHOD, McpStreamTerminationReason.COMPLETED));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpMetricsSnapshot.RequestStreamTerminationKey(
						ENDPOINT_PATH, "",
						McpStreamTerminationReason.COMPLETED));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().activeRequestStreams(null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().activeRequestStreams(-1L));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().requestStreamDurations(null));
		Map<McpMetricsSnapshot.RequestStreamTerminationKey,
				MetricsCollector.HistogramSnapshot> nullKey = new HashMap<>();
		nullKey.put(null, completedHistogram);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().requestStreamDurations(nullKey));
		Map<McpMetricsSnapshot.RequestStreamTerminationKey,
				MetricsCollector.HistogramSnapshot> nullValue = new HashMap<>();
		nullValue.put(completedKey, null);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().requestStreamDurations(nullValue));
	}

	@Test
	public void defaultCollectorAggregatesRendersAndFiltersRequestStreamLifecycleFamilies() {
		DefaultMetricsCollector eventDriven =
				DefaultMetricsCollector.defaultInstance();
		Assertions.assertFalse(prometheus(eventDriven).contains(
				ACTIVE_STREAMS_METRIC_NAME));
		eventDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.RequestStreamOpened(ENDPOINT_PATH,
						JSON_RPC_METHOD));
		McpMetricsSnapshot eventDrivenSnapshot = eventDriven.snapshot()
				.orElseThrow().getMcpMetrics();
		Assertions.assertEquals(1L,
				eventDrivenSnapshot.getActiveRequestStreams());
		Assertions.assertTrue(eventDrivenSnapshot.getRequestStreamDurations()
				.isEmpty());
		assertSample(prometheus(eventDriven), ACTIVE_STREAMS_METRIC_NAME,
				"", 1L);

		DefaultMetricsCollector collector = configuredCollector();
		McpMetricsSnapshot configured = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(0L, configured.getActiveRequestStreams());
		Assertions.assertTrue(configured.getRequestStreamDurations().isEmpty());
		String configuredText = prometheus(collector);
		assertSample(configuredText, ACTIVE_STREAMS_METRIC_NAME, "", 0L);
		assertSparseDurationFamilyAbsent(configuredText);

		for (McpStreamTerminationReason reason
				: McpStreamTerminationReason.values())
			recordLifecycle(collector, reason,
					Duration.ofSeconds(reason.ordinal() + 1L));
		recordLifecycle(collector, McpStreamTerminationReason.COMPLETED,
				Duration.ofMillis(500L));
		recordLifecycle(collector, McpStreamTerminationReason.COMPLETED,
				Duration.ofHours(5L));

		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(0L, retained.getActiveRequestStreams());
		Assertions.assertEquals(Set.copyOf(Arrays.asList(
				McpStreamTerminationReason.values())),
				retained.getRequestStreamDurations().keySet().stream()
						.map(McpMetricsSnapshot.RequestStreamTerminationKey::reason)
						.collect(java.util.stream.Collectors.toUnmodifiableSet()));
		MetricsCollector.HistogramSnapshot completedHistogram = retained
				.getRequestStreamDurations().get(
						key(McpStreamTerminationReason.COMPLETED));
		assertHistogramBoundaries(completedHistogram);
		Assertions.assertEquals(3L, completedHistogram.getCount());
		Assertions.assertEquals(18_001_500_000_000L,
				completedHistogram.getSum());
		Assertions.assertEquals(500_000_000L, completedHistogram.getMin());
		Assertions.assertEquals(18_000_000_000_000L,
				completedHistogram.getMax());
		for (int index = 0; index < completedHistogram.getBucketCount(); ++index)
			Assertions.assertEquals(index == STREAM_DURATION_BUCKETS_NANOS.length - 1
					? 3L : 2L,
					completedHistogram.getBucketCumulativeCount(index));

		Set<SampleProjection> observedSamples = ConcurrentHashMap.newKeySet();
		String selected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.histogramFormat(MetricsCollector.SnapshotTextOptions
								.HistogramFormat.COUNT_SUM_ONLY)
						.metricFilter(sample -> {
							if (!isStreamLifecycleSample(sample.getName()))
								return false;
							observedSamples.add(new SampleProjection(
									sample.getName(), sample.getLabels()));
							return true;
						})
						.build()).orElseThrow();
		Set<SampleProjection> expectedSamples = new java.util.HashSet<>();
		expectedSamples.add(new SampleProjection(ACTIVE_STREAMS_METRIC_NAME,
				Map.of()));
		for (McpStreamTerminationReason reason
				: McpStreamTerminationReason.values()) {
			Map<String, String> labels = labels(reason);
			expectedSamples.add(new SampleProjection(
					STREAM_DURATIONS_METRIC_NAME + "_count", labels));
			expectedSamples.add(new SampleProjection(
					STREAM_DURATIONS_METRIC_NAME + "_sum", labels));
		}
		Assertions.assertEquals(21, expectedSamples.size());
		Assertions.assertEquals(expectedSamples, observedSamples);
		assertMetricType(selected, ACTIVE_STREAMS_METRIC_NAME,
				ACTIVE_STREAMS_HELP, "gauge");
		assertMetricType(selected, STREAM_DURATIONS_METRIC_NAME,
				STREAM_DURATIONS_HELP, "histogram");
		assertSample(selected, ACTIVE_STREAMS_METRIC_NAME, "", 0L);
		String completedLabels = encodedLabels(
				McpStreamTerminationReason.COMPLETED);
		assertSample(selected, STREAM_DURATIONS_METRIC_NAME + "_count",
				completedLabels, 3L);
		assertSample(selected, STREAM_DURATIONS_METRIC_NAME + "_sum",
				completedLabels, 18_001_500_000_000L);
		for (String metricName : List.of(ACTIVE_STREAMS_METRIC_NAME,
				STREAM_DURATIONS_METRIC_NAME)) {
			Assertions.assertEquals(1, occurrences(selected,
					"# HELP " + metricName + " "));
			Assertions.assertEquals(1, occurrences(selected,
					"# TYPE " + metricName + " "));
		}

		String rejected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> !isStreamLifecycleSample(
								sample.getName()))
						.build()).orElseThrow();
		for (String metricName : List.of(ACTIVE_STREAMS_METRIC_NAME,
				STREAM_DURATIONS_METRIC_NAME)) {
			Assertions.assertFalse(rejected.contains("# HELP " + metricName + " "),
					rejected);
			Assertions.assertFalse(rejected.contains("# TYPE " + metricName + " "),
					rejected);
			Assertions.assertFalse(rejected.contains(metricName), rejected);
		}

		String openMetrics = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.OPEN_METRICS_1_0)
						.histogramFormat(MetricsCollector.SnapshotTextOptions
								.HistogramFormat.COUNT_SUM_ONLY)
						.build()).orElseThrow();
		assertSample(openMetrics, STREAM_DURATIONS_METRIC_NAME + "_count",
				completedLabels, 3L);
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"), openMetrics);
		Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));

		recordLifecycle(collector, McpStreamTerminationReason.COMPLETED,
				Duration.ofNanos(42L));
		Assertions.assertEquals(3L, completedHistogram.getCount());
		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		String resetText = prometheus(collector);
		assertSample(resetText, ACTIVE_STREAMS_METRIC_NAME, "", 0L);
		assertSparseDurationFamilyAbsent(resetText);
		Assertions.assertEquals(3L, completedHistogram.getCount());
	}

	@Test
	public void resetPreservesActiveRequestStreamsAndLateCloseRecordsFullOriginalDuration() {
		DefaultMetricsCollector collector =
				DefaultMetricsCollector.defaultInstance();
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.RequestStreamOpened(ENDPOINT_PATH,
						JSON_RPC_METHOD));
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(1L, retained.getActiveRequestStreams());
		Assertions.assertTrue(retained.getRequestStreamDurations().isEmpty());

		collector.reset();
		McpMetricsSnapshot afterReset = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(1L, afterReset.getActiveRequestStreams());
		Assertions.assertTrue(afterReset.getRequestStreamDurations().isEmpty());
		String activeText = prometheus(collector);
		assertSample(activeText, ACTIVE_STREAMS_METRIC_NAME, "", 1L);
		assertSparseDurationFamilyAbsent(activeText);

		Duration fullDuration = Duration.ofSeconds(1_801L);
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.RequestStreamClosed(ENDPOINT_PATH,
						JSON_RPC_METHOD, McpStreamTerminationReason.COMPLETED,
						fullDuration));
		McpMetricsSnapshot completed = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(0L, completed.getActiveRequestStreams());
		MetricsCollector.HistogramSnapshot histogram = completed
				.getRequestStreamDurations().get(
						key(McpStreamTerminationReason.COMPLETED));
		Assertions.assertEquals(1L, histogram.getCount());
		Assertions.assertEquals(fullDuration.toNanos(), histogram.getSum());
		Assertions.assertEquals(fullDuration.toNanos(), histogram.getMin());
		Assertions.assertEquals(fullDuration.toNanos(), histogram.getMax());

		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		assertSample(prometheus(collector), ACTIVE_STREAMS_METRIC_NAME, "", 0L);
		Assertions.assertEquals(1L, retained.getActiveRequestStreams());
		Assertions.assertTrue(retained.getRequestStreamDurations().isEmpty());
	}

	@Test
	@Timeout(15)
	public void concurrentBalancedRequestStreamLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable()
			throws Exception {
		DefaultMetricsCollector collector =
				DefaultMetricsCollector.defaultInstance();
		int threadCount = 6;
		int rounds = 50;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int thread = 0; thread < threadCount; ++thread) {
				long durationNanos = (thread + 1L) * 1_000L;
				futures.add(executor.submit(() -> {
					start.await();
					for (int round = 0; round < rounds; ++round)
						recordLifecycle(collector,
								McpStreamTerminationReason.COMPLETED,
								Duration.ofNanos(durationNanos));
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures)
				future.get(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5,
					TimeUnit.SECONDS));
		}

		long expectedCount = (long) threadCount * rounds;
		long expectedSum = (long) rounds * 1_000L
				* threadCount * (threadCount + 1L) / 2L;
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(0L, retained.getActiveRequestStreams());
		MetricsCollector.HistogramSnapshot histogram = retained
				.getRequestStreamDurations().get(
						key(McpStreamTerminationReason.COMPLETED));
		Assertions.assertEquals(expectedCount, histogram.getCount());
		Assertions.assertEquals(expectedSum, histogram.getSum());
		Assertions.assertEquals(1_000L, histogram.getMin());
		Assertions.assertEquals(threadCount * 1_000L, histogram.getMax());

		recordLifecycle(collector, McpStreamTerminationReason.COMPLETED,
				Duration.ofNanos(42L));
		collector.reset();
		Assertions.assertEquals(expectedCount, histogram.getCount());
		Assertions.assertEquals(expectedSum, histogram.getSum());
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

	private static void assertStreamDurationMapProperty() throws Exception {
		Method getter = McpMetricsSnapshot.class.getMethod(
				"getRequestStreamDurations");
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertEquals(Map.class, getter.getReturnType());
		assertStreamDurationMapType(getter.getGenericReturnType(),
				getter.getAnnotatedReturnType());
		Method builder = McpMetricsSnapshot.Builder.class.getMethod(
				"requestStreamDurations", Map.class);
		Assertions.assertTrue(Modifier.isPublic(builder.getModifiers()));
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				builder.getReturnType());
		Assertions.assertTrue(builder.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		assertStreamDurationMapType(builder.getGenericParameterTypes()[0],
				builder.getAnnotatedParameterTypes()[0]);
	}

	private static void assertStreamDurationMapType(
			@NonNull Object genericType, @NonNull AnnotatedType annotatedType) {
		ParameterizedType parameterized = Assertions.assertInstanceOf(
				ParameterizedType.class, requireNonNull(genericType));
		Assertions.assertEquals(Map.class, parameterized.getRawType());
		Assertions.assertArrayEquals(new Object[]{
				McpMetricsSnapshot.RequestStreamTerminationKey.class,
				MetricsCollector.HistogramSnapshot.class
		}, parameterized.getActualTypeArguments());
		Assertions.assertTrue(requireNonNull(annotatedType)
				.isAnnotationPresent(NonNull.class));
		AnnotatedParameterizedType annotated = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, annotatedType);
		for (AnnotatedType argument : annotated.getAnnotatedActualTypeArguments())
			Assertions.assertTrue(argument.isAnnotationPresent(NonNull.class),
					argument.toString());
	}

	private static void recordLifecycle(
			@NonNull DefaultMetricsCollector collector,
			@NonNull McpStreamTerminationReason reason,
			@NonNull Duration duration) {
		requireNonNull(collector).didRecordMcpMetricsEvent(
				new McpMetricsEvent.RequestStreamOpened(ENDPOINT_PATH,
						JSON_RPC_METHOD));
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.RequestStreamClosed(ENDPOINT_PATH,
						JSON_RPC_METHOD, requireNonNull(reason),
						requireNonNull(duration)));
	}

	private static void assertHistogramBoundaries(
			MetricsCollector.@NonNull HistogramSnapshot histogram) {
		Assertions.assertEquals(STREAM_DURATION_BUCKETS_NANOS.length,
				requireNonNull(histogram).getBucketCount());
		for (int index = 0; index < STREAM_DURATION_BUCKETS_NANOS.length;
				++index)
			Assertions.assertEquals(STREAM_DURATION_BUCKETS_NANOS[index],
					histogram.getBucketBoundary(index));
	}

	private static void assertMetricType(@NonNull String text,
			@NonNull String metricName, @NonNull String help,
			@NonNull String type) {
		Assertions.assertTrue(requireNonNull(text).contains("# HELP "
				+ requireNonNull(metricName) + " " + requireNonNull(help) + "\n"),
				text);
		Assertions.assertTrue(text.contains("# TYPE " + metricName + " "
				+ requireNonNull(type) + "\n"), text);
	}

	private static void assertSparseDurationFamilyAbsent(@NonNull String text) {
		Assertions.assertFalse(requireNonNull(text).contains(
				STREAM_DURATIONS_METRIC_NAME), text);
	}

	private static void assertSample(@NonNull String text,
			@NonNull String metricName, @NonNull String encodedLabels,
			long value) {
		Assertions.assertTrue(requireNonNull(text).contains(
				requireNonNull(metricName) + requireNonNull(encodedLabels)
						+ " " + value + "\n"), text);
	}

	private static boolean isStreamLifecycleSample(@NonNull String name) {
		return requireNonNull(name).equals(ACTIVE_STREAMS_METRIC_NAME)
				|| name.startsWith(STREAM_DURATIONS_METRIC_NAME + "_");
	}

	@NonNull
	private static Map<@NonNull String, @NonNull String> labels(
			@NonNull McpStreamTerminationReason reason) {
		return Map.of("endpoint", ENDPOINT_PATH, "method", JSON_RPC_METHOD,
				"reason", requireNonNull(reason).name()
						.toLowerCase(Locale.ROOT));
	}

	@NonNull
	private static String encodedLabels(
			@NonNull McpStreamTerminationReason reason) {
		return "{endpoint=\"" + ENDPOINT_PATH + "\",method=\""
				+ JSON_RPC_METHOD + "\",reason=\""
				+ requireNonNull(reason).name().toLowerCase(Locale.ROOT)
				+ "\"}";
	}

	private static McpMetricsSnapshot.@NonNull RequestStreamTerminationKey key(
			@NonNull McpStreamTerminationReason reason) {
		return new McpMetricsSnapshot.RequestStreamTerminationKey(
				ENDPOINT_PATH, JSON_RPC_METHOD, requireNonNull(reason));
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
		McpEndpoint endpoint = McpEndpoint.withPath(ENDPOINT_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"request-stream-lifecycle-metrics-test",
						"3.6.0-SNAPSHOT").build())
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

	private record SampleProjection(@NonNull String name,
			@NonNull Map<@NonNull String, @NonNull String> labels) {
		private SampleProjection {
			requireNonNull(name);
			labels = Map.copyOf(requireNonNull(labels));
		}
	}
}
