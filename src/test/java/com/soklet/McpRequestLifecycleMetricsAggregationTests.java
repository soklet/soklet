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
import java.time.Duration;
import java.util.ArrayList;
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
 * Focused public and default-collector coverage for the admitted-request
 * lifecycle aggregate family.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpRequestLifecycleMetricsAggregationTests {
	private static final String ENDPOINT_PATH =
			"/mcp/request-lifecycle-metrics";
	private static final String JSON_RPC_METHOD = "tools/call";
	private static final String ACTIVE_REQUESTS_METRIC_NAME =
			"soklet_mcp_requests_active";
	private static final String REQUESTS_METRIC_NAME =
			"soklet_mcp_requests_total";
	private static final String REQUEST_DURATIONS_METRIC_NAME =
			"soklet_mcp_request_duration_nanos";
	private static final String ACTIVE_REQUESTS_HELP =
			"Currently active admitted MCP requests";
	private static final String REQUESTS_HELP =
			"Total completed MCP requests";
	private static final String REQUEST_DURATIONS_HELP =
			"MCP request duration in nanoseconds";
	private static final long[] REQUEST_DURATION_BUCKETS_NANOS = {
			1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L,
			25_000_000L, 50_000_000L, 100_000_000L, 200_000_000L,
			400_000_000L, 800_000_000L, 1_500_000_000L,
			3_000_000_000L, 7_000_000_000L, 15_000_000_000L,
			Long.MAX_VALUE
	};

	@Test
	public void snapshotContractUsesReferenceTypedImmutableRequestLifecycleState()
			throws Exception {
		assertBoxedCountProperty("getActiveRequests", "activeRequests");
		assertRequestMapProperty("getRequests", "requests", Long.class);
		assertRequestMapProperty("getRequestDurations", "requestDurations",
				MetricsCollector.HistogramSnapshot.class);

		Class<McpMetricsSnapshot.RequestOutcomeKey> keyType =
				McpMetricsSnapshot.RequestOutcomeKey.class;
		Assertions.assertFalse(keyType.isRecord());
		Assertions.assertTrue(Modifier.isPublic(keyType.getModifiers()));
		Assertions.assertTrue(Modifier.isStatic(keyType.getModifiers()));
		Assertions.assertTrue(Modifier.isFinal(keyType.getModifiers()));
		Assertions.assertEquals(0, keyType.getConstructors().length,
				"Metrics keys must not expose public constructors.");
		Method factory = keyType.getMethod("fromDimensions", String.class,
				String.class, McpRequestOutcome.class);
		Assertions.assertTrue(Modifier.isPublic(factory.getModifiers()));
		Assertions.assertTrue(Modifier.isStatic(factory.getModifiers()));
		Assertions.assertEquals(keyType, factory.getReturnType());
		Assertions.assertTrue(factory.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		for (AnnotatedType parameter : factory.getAnnotatedParameterTypes())
			Assertions.assertTrue(parameter.isAnnotationPresent(NonNull.class),
					parameter.toString());
		assertNonNullGetter(keyType, "getEndpointPath", String.class);
		assertNonNullGetter(keyType, "getJsonRpcMethod", String.class);
		assertNonNullGetter(keyType, "getOutcome", McpRequestOutcome.class);

		McpMetricsSnapshot empty = McpMetricsSnapshot.emptyInstance();
		Assertions.assertSame(empty, McpMetricsSnapshot.emptyInstance());
		Assertions.assertEquals(0L, empty.getActiveRequests());
		Assertions.assertTrue(empty.getRequests().isEmpty());
		Assertions.assertTrue(empty.getRequestDurations().isEmpty());

		McpMetricsSnapshot.RequestOutcomeKey completedKey =
				key(McpRequestOutcome.COMPLETE);
		McpMetricsSnapshot.RequestOutcomeKey durationOnlyKey =
				McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(
						"/application-defined", "vendor.example/arbitrary",
						McpRequestOutcome.INTERNAL_ERROR);
		McpMetricsSnapshot.RequestOutcomeKey equalDurationOnlyKey =
				McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(
						"/application-defined", "vendor.example/arbitrary",
						McpRequestOutcome.INTERNAL_ERROR);
		Assertions.assertEquals("/application-defined",
				durationOnlyKey.getEndpointPath());
		Assertions.assertEquals("vendor.example/arbitrary",
				durationOnlyKey.getJsonRpcMethod());
		Assertions.assertEquals(McpRequestOutcome.INTERNAL_ERROR,
				durationOnlyKey.getOutcome());
		Assertions.assertEquals(durationOnlyKey, equalDurationOnlyKey);
		Assertions.assertEquals(durationOnlyKey.hashCode(),
				equalDurationOnlyKey.hashCode());
		Assertions.assertNotEquals(durationOnlyKey, completedKey);
		Assertions.assertEquals("RequestOutcomeKey{endpointPath=<redacted>, "
				+ "jsonRpcMethod=<redacted>, outcome=INTERNAL_ERROR}",
				durationOnlyKey.toString());
		MetricsCollector.HistogramSnapshot histogram =
				new MetricsCollector.HistogramSnapshot(
						new long[]{1L, Long.MAX_VALUE}, new long[]{0L, 1L},
						1L, 2L, 2L, 2L);
		Map<McpMetricsSnapshot.RequestOutcomeKey, Long> counts =
				new LinkedHashMap<>();
		counts.put(completedKey, 0L);
		Map<McpMetricsSnapshot.RequestOutcomeKey,
				MetricsCollector.HistogramSnapshot> durations =
				new LinkedHashMap<>();
		durations.put(durationOnlyKey, histogram);
		McpMetricsSnapshot snapshot = McpMetricsSnapshot.builder()
				.activeRequests(2L)
				.requests(counts)
				.requestDurations(durations)
				.build();
		counts.put(completedKey, 99L);
		durations.clear();
		Assertions.assertEquals(2L, snapshot.getActiveRequests());
		Assertions.assertEquals(Map.of(completedKey, 0L),
				snapshot.getRequests());
		Assertions.assertEquals(Map.of(durationOnlyKey, histogram),
				snapshot.getRequestDurations(),
				"Completed counts and duration histograms are independent sparse maps.");
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getRequests().put(durationOnlyKey, 1L));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getRequestDurations().clear());

		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(null,
						JSON_RPC_METHOD, McpRequestOutcome.COMPLETE));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(ENDPOINT_PATH,
						null, McpRequestOutcome.COMPLETE));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(ENDPOINT_PATH,
						JSON_RPC_METHOD, null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.RequestOutcomeKey.fromDimensions("",
						JSON_RPC_METHOD, McpRequestOutcome.COMPLETE));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(ENDPOINT_PATH,
						"", McpRequestOutcome.COMPLETE));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().activeRequests(null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().activeRequests(-1L));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().requests(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().requestDurations(null));

		Map<McpMetricsSnapshot.RequestOutcomeKey, Long> nullCountKey =
				new HashMap<>();
		nullCountKey.put(null, 1L);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().requests(nullCountKey));
		Map<McpMetricsSnapshot.RequestOutcomeKey, Long> nullCount =
				new HashMap<>();
		nullCount.put(completedKey, null);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().requests(nullCount));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().requests(
						Map.of(completedKey, -1L)));
		Map<McpMetricsSnapshot.RequestOutcomeKey,
				MetricsCollector.HistogramSnapshot> nullHistogramKey =
				new HashMap<>();
		nullHistogramKey.put(null, histogram);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder()
						.requestDurations(nullHistogramKey));
		Map<McpMetricsSnapshot.RequestOutcomeKey,
				MetricsCollector.HistogramSnapshot> nullHistogram =
				new HashMap<>();
		nullHistogram.put(completedKey, null);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder()
						.requestDurations(nullHistogram));
	}

	@Test
	public void defaultCollectorAggregatesRendersAndFiltersRequestLifecycleFamilies() {
		DefaultMetricsCollector eventDriven =
				DefaultMetricsCollector.defaultInstance();
		Assertions.assertFalse(prometheus(eventDriven).contains(
				ACTIVE_REQUESTS_METRIC_NAME));
		eventDriven.didRecordMcpMetricsEvent(McpMetricsEvent.requestStarted(
				ENDPOINT_PATH, JSON_RPC_METHOD));
		McpMetricsSnapshot eventDrivenSnapshot = eventDriven.snapshot()
				.orElseThrow().getMcpMetrics();
		Assertions.assertEquals(1L, eventDrivenSnapshot.getActiveRequests());
		Assertions.assertTrue(eventDrivenSnapshot.getRequests().isEmpty());
		Assertions.assertTrue(eventDrivenSnapshot.getRequestDurations().isEmpty());
		assertSample(prometheus(eventDriven), ACTIVE_REQUESTS_METRIC_NAME,
				"", 1L);

		DefaultMetricsCollector collector = configuredCollector();
		McpMetricsSnapshot configured = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(0L, configured.getActiveRequests());
		Assertions.assertTrue(configured.getRequests().isEmpty());
		Assertions.assertTrue(configured.getRequestDurations().isEmpty());
		String configuredText = prometheus(collector);
		assertSample(configuredText, ACTIVE_REQUESTS_METRIC_NAME, "", 0L);
		assertSparseCompletedFamiliesAbsent(configuredText);

		Map<McpMetricsSnapshot.RequestOutcomeKey, Long> expectedCounts =
				new LinkedHashMap<>();
		for (McpRequestOutcome outcome : McpRequestOutcome.values()) {
			Duration duration = Duration.ofMillis(outcome.ordinal() + 1L);
			recordLifecycle(collector, outcome, duration);
			expectedCounts.put(key(outcome), 1L);
		}
		recordLifecycle(collector, McpRequestOutcome.COMPLETE,
				Duration.ofNanos(500_000L));
		recordLifecycle(collector, McpRequestOutcome.COMPLETE,
				Duration.ofSeconds(20L));
		expectedCounts.put(key(McpRequestOutcome.COMPLETE), 3L);

		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(0L, retained.getActiveRequests());
		Assertions.assertEquals(expectedCounts, retained.getRequests());
		Assertions.assertEquals(expectedCounts.keySet(),
				retained.getRequestDurations().keySet());
		MetricsCollector.HistogramSnapshot completedHistogram =
				retained.getRequestDurations().get(
						key(McpRequestOutcome.COMPLETE));
		assertHistogramBoundaries(completedHistogram);
		Assertions.assertEquals(3L, completedHistogram.getCount());
		Assertions.assertEquals(20_001_500_000L,
				completedHistogram.getSum());
		Assertions.assertEquals(500_000L, completedHistogram.getMin());
		Assertions.assertEquals(20_000_000_000L, completedHistogram.getMax());
		for (int index = 0; index < completedHistogram.getBucketCount(); ++index)
			Assertions.assertEquals(index == REQUEST_DURATION_BUCKETS_NANOS.length - 1
					? 3L : 2L,
					completedHistogram.getBucketCumulativeCount(index));

		Set<SampleProjection> observedSamples = ConcurrentHashMap.newKeySet();
		MetricsCollector.SnapshotTextOptions selectedOptions =
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.histogramFormat(MetricsCollector.SnapshotTextOptions
								.HistogramFormat.COUNT_SUM_ONLY)
						.metricFilter(sample -> {
							if (!isLifecycleSample(sample.getName()))
								return false;
							observedSamples.add(new SampleProjection(
									sample.getName(), sample.getLabels()));
							return true;
						})
						.build();
		String selected = collector.snapshotText(selectedOptions).orElseThrow();
		Set<SampleProjection> expectedSamples = new java.util.HashSet<>();
		expectedSamples.add(new SampleProjection(ACTIVE_REQUESTS_METRIC_NAME,
				Map.of()));
		for (McpRequestOutcome outcome : McpRequestOutcome.values()) {
			Map<String, String> labels = labels(outcome);
			expectedSamples.add(new SampleProjection(REQUESTS_METRIC_NAME, labels));
			expectedSamples.add(new SampleProjection(
					REQUEST_DURATIONS_METRIC_NAME + "_count", labels));
			expectedSamples.add(new SampleProjection(
					REQUEST_DURATIONS_METRIC_NAME + "_sum", labels));
		}
		Assertions.assertEquals(expectedSamples.size(), observedSamples.size(),
				observedSamples.toString());
		Assertions.assertEquals(expectedSamples, observedSamples);
		assertMetricType(selected, ACTIVE_REQUESTS_METRIC_NAME,
				ACTIVE_REQUESTS_HELP, "gauge");
		assertMetricType(selected, REQUESTS_METRIC_NAME, REQUESTS_HELP,
				"counter");
		assertMetricType(selected, REQUEST_DURATIONS_METRIC_NAME,
				REQUEST_DURATIONS_HELP, "histogram");
		assertSample(selected, ACTIVE_REQUESTS_METRIC_NAME, "", 0L);
		String completedLabels = encodedLabels(McpRequestOutcome.COMPLETE);
		assertSample(selected, REQUESTS_METRIC_NAME, completedLabels, 3L);
		assertSample(selected, REQUEST_DURATIONS_METRIC_NAME + "_count",
				completedLabels, 3L);
		assertSample(selected, REQUEST_DURATIONS_METRIC_NAME + "_sum",
				completedLabels, 20_001_500_000L);
		for (String metricName : List.of(ACTIVE_REQUESTS_METRIC_NAME,
				REQUESTS_METRIC_NAME, REQUEST_DURATIONS_METRIC_NAME)) {
			Assertions.assertEquals(1, occurrences(selected,
					"# HELP " + metricName + " "));
			Assertions.assertEquals(1, occurrences(selected,
					"# TYPE " + metricName + " "));
		}

		String rejected = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> !isLifecycleSample(
								sample.getName()))
						.build()).orElseThrow();
		for (String metricName : List.of(ACTIVE_REQUESTS_METRIC_NAME,
				REQUESTS_METRIC_NAME, REQUEST_DURATIONS_METRIC_NAME)) {
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
		assertSample(openMetrics, REQUESTS_METRIC_NAME, completedLabels, 3L);
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"), openMetrics);
		Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));

		recordLifecycle(collector, McpRequestOutcome.COMPLETE,
				Duration.ofNanos(42L));
		Assertions.assertEquals(3L, retained.getRequests().get(
				key(McpRequestOutcome.COMPLETE)));
		Assertions.assertEquals(3L, completedHistogram.getCount());
		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		String resetText = prometheus(collector);
		assertSample(resetText, ACTIVE_REQUESTS_METRIC_NAME, "", 0L);
		assertSparseCompletedFamiliesAbsent(resetText);
		Assertions.assertEquals(expectedCounts, retained.getRequests());
		Assertions.assertEquals(3L, completedHistogram.getCount());
	}

	@Test
	public void resetPreservesActiveRequestsAndLateFinishRecordsFullOriginalDuration() {
		DefaultMetricsCollector collector =
				DefaultMetricsCollector.defaultInstance();
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestStarted(
				ENDPOINT_PATH, JSON_RPC_METHOD));
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(1L, retained.getActiveRequests());
		Assertions.assertTrue(retained.getRequests().isEmpty());
		Assertions.assertTrue(retained.getRequestDurations().isEmpty());

		collector.reset();
		McpMetricsSnapshot afterReset = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(1L, afterReset.getActiveRequests());
		Assertions.assertTrue(afterReset.getRequests().isEmpty());
		Assertions.assertTrue(afterReset.getRequestDurations().isEmpty());
		String activeText = prometheus(collector);
		assertSample(activeText, ACTIVE_REQUESTS_METRIC_NAME, "", 1L);
		Assertions.assertFalse(activeText.contains(REQUESTS_METRIC_NAME + "{"),
				activeText);

		Duration fullDuration = Duration.ofMillis(7_001L);
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestFinished(
				ENDPOINT_PATH, JSON_RPC_METHOD, McpRequestOutcome.COMPLETE,
				fullDuration));
		McpMetricsSnapshot completed = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(0L, completed.getActiveRequests());
		Assertions.assertEquals(Map.of(key(McpRequestOutcome.COMPLETE), 1L),
				completed.getRequests());
		MetricsCollector.HistogramSnapshot histogram = completed
				.getRequestDurations().get(key(McpRequestOutcome.COMPLETE));
		Assertions.assertEquals(1L, histogram.getCount());
		Assertions.assertEquals(fullDuration.toNanos(), histogram.getSum());
		Assertions.assertEquals(fullDuration.toNanos(), histogram.getMin());
		Assertions.assertEquals(fullDuration.toNanos(), histogram.getMax());

		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		assertSample(prometheus(collector), ACTIVE_REQUESTS_METRIC_NAME, "", 0L);
		Assertions.assertEquals(1L, retained.getActiveRequests());
		Assertions.assertTrue(retained.getRequests().isEmpty());
	}

	@Test
	@Timeout(15)
	public void concurrentBalancedRequestLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable()
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
						recordLifecycle(collector, McpRequestOutcome.COMPLETE,
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
		Assertions.assertEquals(0L, retained.getActiveRequests());
		Assertions.assertEquals(Map.of(key(McpRequestOutcome.COMPLETE),
				expectedCount), retained.getRequests());
		MetricsCollector.HistogramSnapshot histogram = retained
				.getRequestDurations().get(key(McpRequestOutcome.COMPLETE));
		Assertions.assertEquals(expectedCount, histogram.getCount());
		Assertions.assertEquals(expectedSum, histogram.getSum());
		Assertions.assertEquals(1_000L, histogram.getMin());
		Assertions.assertEquals(threadCount * 1_000L, histogram.getMax());

		recordLifecycle(collector, McpRequestOutcome.COMPLETE,
				Duration.ofNanos(42L));
		collector.reset();
		Assertions.assertEquals(expectedCount, retained.getRequests().get(
				key(McpRequestOutcome.COMPLETE)));
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

	private static void assertRequestMapProperty(@NonNull String getterName,
			@NonNull String builderName, @NonNull Class<?> valueType)
			throws Exception {
		Method getter = McpMetricsSnapshot.class.getMethod(
				requireNonNull(getterName));
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertEquals(Map.class, getter.getReturnType());
		assertRequestMapType(getter.getGenericReturnType(),
				getter.getAnnotatedReturnType(), valueType);
		Method builder = McpMetricsSnapshot.Builder.class.getMethod(
				requireNonNull(builderName), Map.class);
		Assertions.assertTrue(Modifier.isPublic(builder.getModifiers()));
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				builder.getReturnType());
		Assertions.assertTrue(builder.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		assertRequestMapType(builder.getGenericParameterTypes()[0],
				builder.getAnnotatedParameterTypes()[0], valueType);
	}

	private static void assertNonNullGetter(@NonNull Class<?> declaringType,
			@NonNull String name, @NonNull Class<?> returnType)
			throws Exception {
		Method getter = requireNonNull(declaringType).getMethod(
				requireNonNull(name));
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertEquals(requireNonNull(returnType), getter.getReturnType());
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertTrue(getter.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
	}

	private static void assertRequestMapType(@NonNull Object genericType,
			@NonNull AnnotatedType annotatedType, @NonNull Class<?> valueType) {
		ParameterizedType parameterized = Assertions.assertInstanceOf(
				ParameterizedType.class, requireNonNull(genericType));
		Assertions.assertEquals(Map.class, parameterized.getRawType());
		Assertions.assertArrayEquals(new Object[]{
				McpMetricsSnapshot.RequestOutcomeKey.class,
				requireNonNull(valueType)
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
			@NonNull McpRequestOutcome outcome, @NonNull Duration duration) {
		requireNonNull(collector).didRecordMcpMetricsEvent(
				McpMetricsEvent.requestStarted(ENDPOINT_PATH,
						JSON_RPC_METHOD));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.requestFinished(
				ENDPOINT_PATH, JSON_RPC_METHOD, requireNonNull(outcome),
				requireNonNull(duration)));
	}

	private static void assertHistogramBoundaries(
			MetricsCollector.@NonNull HistogramSnapshot histogram) {
		Assertions.assertEquals(REQUEST_DURATION_BUCKETS_NANOS.length,
			requireNonNull(histogram).getBucketCount());
		for (int index = 0; index < REQUEST_DURATION_BUCKETS_NANOS.length;
				++index)
			Assertions.assertEquals(REQUEST_DURATION_BUCKETS_NANOS[index],
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

	private static void assertSparseCompletedFamiliesAbsent(
			@NonNull String text) {
		Assertions.assertFalse(requireNonNull(text).contains(
				REQUESTS_METRIC_NAME), text);
		Assertions.assertFalse(text.contains(REQUEST_DURATIONS_METRIC_NAME),
				text);
	}

	private static void assertSample(@NonNull String text,
			@NonNull String metricName, @NonNull String encodedLabels,
			long value) {
		Assertions.assertTrue(requireNonNull(text).contains(
				requireNonNull(metricName) + requireNonNull(encodedLabels)
						+ " " + value + "\n"), text);
	}

	private static boolean isLifecycleSample(@NonNull String name) {
		return requireNonNull(name).equals(ACTIVE_REQUESTS_METRIC_NAME)
				|| name.equals(REQUESTS_METRIC_NAME)
				|| name.startsWith(REQUEST_DURATIONS_METRIC_NAME + "_");
	}

	@NonNull
	private static Map<@NonNull String, @NonNull String> labels(
			@NonNull McpRequestOutcome outcome) {
		return Map.of("endpoint", ENDPOINT_PATH, "method", JSON_RPC_METHOD,
				"outcome", requireNonNull(outcome).name()
						.toLowerCase(Locale.ROOT));
	}

	@NonNull
	private static String encodedLabels(@NonNull McpRequestOutcome outcome) {
		return "{endpoint=\"" + ENDPOINT_PATH + "\",method=\""
				+ JSON_RPC_METHOD + "\",outcome=\""
				+ requireNonNull(outcome).name().toLowerCase(Locale.ROOT)
				+ "\"}";
	}

	private static McpMetricsSnapshot.@NonNull RequestOutcomeKey key(
			@NonNull McpRequestOutcome outcome) {
		return McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(ENDPOINT_PATH,
				JSON_RPC_METHOD, requireNonNull(outcome));
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
						"request-lifecycle-metrics-test", "4.0.0-SNAPSHOT")
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

	private record SampleProjection(@NonNull String name,
			@NonNull Map<@NonNull String, @NonNull String> labels) {
		private SampleProjection {
			requireNonNull(name);
			labels = Map.copyOf(requireNonNull(labels));
		}
	}
}
