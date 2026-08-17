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
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Focused public and default-collector coverage for MCP progress and
 * cooperative-cancelation counter aggregates.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpProgressAndCancelationMetricsAggregationTests {
	private static final String ENDPOINT_PATH =
			"/mcp/progress-cancelation-metrics";
	private static final String JSON_RPC_METHOD = "tools/call";
	private static final String CANCELATIONS_METRIC_NAME =
			"soklet_mcp_cancelations_signaled_total";
	private static final String CANCELATIONS_HELP =
			"Total cooperative MCP request cancelations signaled by endpoint and method";
	private static final String PROGRESS_METRIC_NAME =
			"soklet_mcp_progress_emitted_total";
	private static final String PROGRESS_HELP =
			"Total MCP progress notifications accepted for delivery by endpoint and method";

	@Test
	public void snapshotContractUsesSharedImmutableEndpointMethodCounterMaps()
			throws Exception {
		assertEndpointMethodCountMapProperty("getCancelationsSignaled",
				"cancelationsSignaled");
		assertEndpointMethodCountMapProperty("getProgressEmitted",
				"progressEmitted");

		Class<McpMetricsSnapshot.EndpointMethodKey> keyType =
				McpMetricsSnapshot.EndpointMethodKey.class;
		Assertions.assertTrue(keyType.isRecord());
		Assertions.assertTrue(Modifier.isPublic(keyType.getModifiers()));
		Assertions.assertTrue(Modifier.isStatic(keyType.getModifiers()));
		Assertions.assertTrue(Modifier.isFinal(keyType.getModifiers()));
		RecordComponent[] components = keyType.getRecordComponents();
		Assertions.assertEquals(List.of("endpointPath", "jsonRpcMethod"),
				Arrays.stream(components).map(RecordComponent::getName).toList());
		Assertions.assertEquals(List.of(String.class, String.class),
				Arrays.stream(components).map(RecordComponent::getType).toList());
		for (RecordComponent component : components) {
			Assertions.assertTrue(component.getAnnotatedType()
					.isAnnotationPresent(NonNull.class), component.toString());
			Assertions.assertTrue(component.getAccessor().getAnnotatedReturnType()
					.isAnnotationPresent(NonNull.class), component.toString());
		}
		Constructor<McpMetricsSnapshot.EndpointMethodKey> constructor =
				keyType.getConstructor(String.class, String.class);
		Assertions.assertTrue(Modifier.isPublic(constructor.getModifiers()));
		for (AnnotatedType parameter : constructor.getAnnotatedParameterTypes())
			Assertions.assertTrue(parameter.isAnnotationPresent(NonNull.class),
					parameter.toString());

		McpMetricsSnapshot empty = McpMetricsSnapshot.emptyInstance();
		Assertions.assertSame(empty, McpMetricsSnapshot.emptyInstance());
		Assertions.assertTrue(empty.getCancelationsSignaled().isEmpty());
		Assertions.assertTrue(empty.getProgressEmitted().isEmpty());

		McpMetricsSnapshot.EndpointMethodKey routedKey = key(ENDPOINT_PATH,
				JSON_RPC_METHOD);
		McpMetricsSnapshot.EndpointMethodKey applicationKey = key(
				"/application-defined", "vendor.example/arbitrary");
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> cancelationSource =
				new LinkedHashMap<>();
		cancelationSource.put(routedKey, 2L);
		cancelationSource.put(applicationKey, 0L);
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> progressSource =
				new LinkedHashMap<>();
		progressSource.put(routedKey, 5L);
		McpMetricsSnapshot snapshot = McpMetricsSnapshot.builder()
				.cancelationsSignaled(cancelationSource)
				.progressEmitted(progressSource)
				.build();
		cancelationSource.clear();
		progressSource.put(routedKey, 99L);

		Assertions.assertEquals(Map.of(routedKey, 2L, applicationKey, 0L),
				snapshot.getCancelationsSignaled());
		Assertions.assertEquals(Map.of(routedKey, 5L),
				snapshot.getProgressEmitted());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getCancelationsSignaled().clear());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getProgressEmitted().put(applicationKey, 1L));
		Assertions.assertEquals("vendor.example/arbitrary",
				applicationKey.jsonRpcMethod(),
				"Public keys validate shape, not the runtime-only method vocabulary.");

		Assertions.assertThrows(NullPointerException.class,
				() -> new McpMetricsSnapshot.EndpointMethodKey(null,
						JSON_RPC_METHOD));
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpMetricsSnapshot.EndpointMethodKey(ENDPOINT_PATH,
						null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpMetricsSnapshot.EndpointMethodKey("",
						JSON_RPC_METHOD));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpMetricsSnapshot.EndpointMethodKey(ENDPOINT_PATH,
						""));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().cancelationsSignaled(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().progressEmitted(null));
		assertInvalidCountMapInputs(routedKey);
	}

	@Test
	public void defaultCollectorAggregatesRendersAndFiltersProgressAndCancelationFamilies() {
		DefaultMetricsCollector pristine = DefaultMetricsCollector.defaultInstance();
		assertSparseFamilyAbsent(prometheus(pristine), CANCELATIONS_METRIC_NAME);
		assertSparseFamilyAbsent(prometheus(pristine), PROGRESS_METRIC_NAME);

		DefaultMetricsCollector configured = configuredCollector();
		Assertions.assertTrue(configured.snapshot().orElseThrow().getMcpMetrics()
				.getCancelationsSignaled().isEmpty());
		Assertions.assertTrue(configured.snapshot().orElseThrow().getMcpMetrics()
				.getProgressEmitted().isEmpty());
		assertSparseFamilyAbsent(prometheus(configured),
				CANCELATIONS_METRIC_NAME);
		assertSparseFamilyAbsent(prometheus(configured), PROGRESS_METRIC_NAME);

		McpMetricsSnapshot.EndpointMethodKey primary = key(ENDPOINT_PATH,
				JSON_RPC_METHOD);
		McpMetricsSnapshot.EndpointMethodKey secondary = key(ENDPOINT_PATH,
				"resources/read");
		DefaultMetricsCollector progressDriven =
				DefaultMetricsCollector.defaultInstance();
		progressDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProgressEmitted(primary.endpointPath(),
						primary.jsonRpcMethod()));
		progressDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProgressEmitted(primary.endpointPath(),
						primary.jsonRpcMethod()));
		progressDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProgressEmitted(secondary.endpointPath(),
						secondary.jsonRpcMethod()));
		McpMetricsSnapshot progressSnapshot = progressDriven.snapshot()
				.orElseThrow().getMcpMetrics();
		Assertions.assertTrue(progressSnapshot.getCancelationsSignaled().isEmpty());
		Assertions.assertEquals(Map.of(primary, 2L, secondary, 1L),
				progressSnapshot.getProgressEmitted());
		String progressText = prometheus(progressDriven);
		assertSparseFamilyAbsent(progressText, CANCELATIONS_METRIC_NAME);
		assertMetricType(progressText, PROGRESS_METRIC_NAME, PROGRESS_HELP);
		assertSample(progressText, PROGRESS_METRIC_NAME, primary, 2L);
		assertSample(progressText, PROGRESS_METRIC_NAME, secondary, 1L);

		DefaultMetricsCollector cancelationDriven =
				DefaultMetricsCollector.defaultInstance();
		cancelationDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.CancelationSignaled(primary.endpointPath(),
						primary.jsonRpcMethod()));
		McpMetricsSnapshot cancelationSnapshot = cancelationDriven.snapshot()
				.orElseThrow().getMcpMetrics();
		Assertions.assertEquals(Map.of(primary, 1L),
				cancelationSnapshot.getCancelationsSignaled());
		Assertions.assertTrue(cancelationSnapshot.getProgressEmitted().isEmpty());
		String cancelationText = prometheus(cancelationDriven);
		assertMetricType(cancelationText, CANCELATIONS_METRIC_NAME,
				CANCELATIONS_HELP);
		assertSample(cancelationText, CANCELATIONS_METRIC_NAME, primary, 1L);
		assertSparseFamilyAbsent(cancelationText, PROGRESS_METRIC_NAME);

		cancelationDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProgressEmitted(primary.endpointPath(),
						primary.jsonRpcMethod()));
		cancelationDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProgressEmitted(primary.endpointPath(),
						primary.jsonRpcMethod()));
		Set<SampleProjection> observed = ConcurrentHashMap.newKeySet();
		String selected = cancelationDriven.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> {
							if (!isProgressOrCancelationSample(sample.getName()))
								return false;
							observed.add(new SampleProjection(sample.getName(),
									sample.getLabels()));
							return true;
						})
						.build()).orElseThrow();
		Assertions.assertEquals(Set.of(
				new SampleProjection(CANCELATIONS_METRIC_NAME, labels(primary)),
				new SampleProjection(PROGRESS_METRIC_NAME, labels(primary))),
				observed);
		assertMetricType(selected, CANCELATIONS_METRIC_NAME, CANCELATIONS_HELP);
		assertMetricType(selected, PROGRESS_METRIC_NAME, PROGRESS_HELP);
		assertSample(selected, CANCELATIONS_METRIC_NAME, primary, 1L);
		assertSample(selected, PROGRESS_METRIC_NAME, primary, 2L);
		Assertions.assertEquals(1, occurrences(selected,
				"# HELP " + CANCELATIONS_METRIC_NAME + " "));
		Assertions.assertEquals(1, occurrences(selected,
				"# TYPE " + CANCELATIONS_METRIC_NAME + " counter\n"));
		Assertions.assertEquals(1, occurrences(selected,
				"# HELP " + PROGRESS_METRIC_NAME + " "));
		Assertions.assertEquals(1, occurrences(selected,
				"# TYPE " + PROGRESS_METRIC_NAME + " counter\n"));

		String rejected = cancelationDriven.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample ->
								!isProgressOrCancelationSample(sample.getName()))
						.build()).orElseThrow();
		assertSparseFamilyAbsent(rejected, CANCELATIONS_METRIC_NAME);
		assertSparseFamilyAbsent(rejected, PROGRESS_METRIC_NAME);

		String openMetrics = cancelationDriven.snapshotText(
				MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
						MetricsCollector.MetricsFormat.OPEN_METRICS_1_0))
				.orElseThrow();
		assertSample(openMetrics, CANCELATIONS_METRIC_NAME, primary, 1L);
		assertSample(openMetrics, PROGRESS_METRIC_NAME, primary, 2L);
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"), openMetrics);
		Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));
	}

	@Test
	public void resetClearsSparseProgressAndCancelationCountersWithoutLeavingFamilyMetadata() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		McpMetricsSnapshot.EndpointMethodKey key = key(ENDPOINT_PATH,
				JSON_RPC_METHOD);
		collector.didRecordMcpMetricsEvent(new McpMetricsEvent.ProgressEmitted(
				key.endpointPath(), key.jsonRpcMethod()));
		collector.didRecordMcpMetricsEvent(new McpMetricsEvent.ProgressEmitted(
				key.endpointPath(), key.jsonRpcMethod()));
		collector.didRecordMcpMetricsEvent(new McpMetricsEvent.CancelationSignaled(
				key.endpointPath(), key.jsonRpcMethod()));
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(Map.of(key, 1L),
				retained.getCancelationsSignaled());
		Assertions.assertEquals(Map.of(key, 2L), retained.getProgressEmitted(),
				"Progress and cancelation are independent counters, not complements.");

		collector.didRecordMcpMetricsEvent(new McpMetricsEvent.ProgressEmitted(
				key.endpointPath(), key.jsonRpcMethod()));
		collector.didRecordMcpMetricsEvent(new McpMetricsEvent.CancelationSignaled(
				key.endpointPath(), key.jsonRpcMethod()));
		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		String resetText = prometheus(collector);
		assertSparseFamilyAbsent(resetText, CANCELATIONS_METRIC_NAME);
		assertSparseFamilyAbsent(resetText, PROGRESS_METRIC_NAME);
		Assertions.assertEquals(Map.of(key, 1L),
				retained.getCancelationsSignaled());
		Assertions.assertEquals(Map.of(key, 2L), retained.getProgressEmitted());
	}

	@Test
	@Timeout(15)
	public void concurrentDirectProgressAndCancelationIngestIsLosslessAndRetainedSnapshotsRemainImmutable()
			throws Exception {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		int threadCount = 6;
		int rounds = 60;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		List<McpMetricsSnapshot.EndpointMethodKey> keys = new ArrayList<>();
		for (int thread = 0; thread < threadCount; ++thread)
			keys.add(key(ENDPOINT_PATH, "tools/call-" + thread));
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (McpMetricsSnapshot.EndpointMethodKey key : keys)
				futures.add(executor.submit(() -> {
					start.await();
					for (int round = 0; round < rounds; ++round) {
						collector.didRecordMcpMetricsEvent(
								new McpMetricsEvent.ProgressEmitted(
										key.endpointPath(), key.jsonRpcMethod()));
						collector.didRecordMcpMetricsEvent(
								new McpMetricsEvent.ProgressEmitted(
										key.endpointPath(), key.jsonRpcMethod()));
						collector.didRecordMcpMetricsEvent(
								new McpMetricsEvent.CancelationSignaled(
										key.endpointPath(), key.jsonRpcMethod()));
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

		Map<McpMetricsSnapshot.EndpointMethodKey, Long> expectedCancelations =
				new HashMap<>();
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> expectedProgress =
				new HashMap<>();
		for (McpMetricsSnapshot.EndpointMethodKey key : keys) {
			expectedCancelations.put(key, (long) rounds);
			expectedProgress.put(key, rounds * 2L);
		}
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(expectedCancelations,
				retained.getCancelationsSignaled());
		Assertions.assertEquals(expectedProgress, retained.getProgressEmitted());

		McpMetricsSnapshot.EndpointMethodKey first = keys.get(0);
		collector.didRecordMcpMetricsEvent(new McpMetricsEvent.ProgressEmitted(
				first.endpointPath(), first.jsonRpcMethod()));
		collector.didRecordMcpMetricsEvent(new McpMetricsEvent.CancelationSignaled(
				first.endpointPath(), first.jsonRpcMethod()));
		collector.reset();
		Assertions.assertEquals(expectedCancelations,
				retained.getCancelationsSignaled());
		Assertions.assertEquals(expectedProgress, retained.getProgressEmitted());
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
	}

	private static void assertEndpointMethodCountMapProperty(
			@NonNull String getterName, @NonNull String builderName)
			throws Exception {
		Method getter = McpMetricsSnapshot.class.getMethod(
				requireNonNull(getterName));
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertEquals(Map.class, getter.getReturnType());
		assertEndpointMethodCountMapType(getter.getGenericReturnType(),
				getter.getAnnotatedReturnType());

		Method builder = McpMetricsSnapshot.Builder.class.getMethod(
				requireNonNull(builderName), Map.class);
		Assertions.assertTrue(Modifier.isPublic(builder.getModifiers()));
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				builder.getReturnType());
		Assertions.assertTrue(builder.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		assertEndpointMethodCountMapType(builder.getGenericParameterTypes()[0],
				builder.getAnnotatedParameterTypes()[0]);
	}

	private static void assertEndpointMethodCountMapType(
			@NonNull Type type,
			@NonNull AnnotatedType annotatedType) {
		Assertions.assertInstanceOf(ParameterizedType.class, requireNonNull(type));
		ParameterizedType parameterizedType = (ParameterizedType) type;
		Assertions.assertEquals(Map.class, parameterizedType.getRawType());
		Assertions.assertArrayEquals(new java.lang.reflect.Type[]{
				McpMetricsSnapshot.EndpointMethodKey.class, Long.class},
				parameterizedType.getActualTypeArguments());
		Assertions.assertTrue(requireNonNull(annotatedType)
				.isAnnotationPresent(NonNull.class));
		Assertions.assertInstanceOf(AnnotatedParameterizedType.class,
				annotatedType);
		for (AnnotatedType argument : ((AnnotatedParameterizedType) annotatedType)
				.getAnnotatedActualTypeArguments())
			Assertions.assertTrue(argument.isAnnotationPresent(NonNull.class),
					argument.toString());
	}

	private static void assertInvalidCountMapInputs(
			McpMetricsSnapshot.@NonNull EndpointMethodKey key) {
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> nullKey = new HashMap<>();
		nullKey.put(null, 1L);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().cancelationsSignaled(nullKey));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().progressEmitted(nullKey));
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> nullValue =
				new HashMap<>();
		nullValue.put(requireNonNull(key), null);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().cancelationsSignaled(nullValue));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().progressEmitted(nullValue));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().cancelationsSignaled(
						Map.of(key, -1L)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().progressEmitted(
						Map.of(key, -1L)));
	}

	private static boolean isProgressOrCancelationSample(
			@NonNull String metricName) {
		return Set.of(CANCELATIONS_METRIC_NAME, PROGRESS_METRIC_NAME)
				.contains(requireNonNull(metricName));
	}

	private static void assertMetricType(@NonNull String text,
			@NonNull String metricName, @NonNull String help) {
		Assertions.assertTrue(requireNonNull(text).contains("# HELP "
				+ requireNonNull(metricName) + " " + requireNonNull(help) + "\n"),
				text);
		Assertions.assertTrue(text.contains("# TYPE " + metricName
				+ " counter\n"), text);
	}

	private static void assertSparseFamilyAbsent(@NonNull String text,
			@NonNull String metricName) {
		Assertions.assertFalse(requireNonNull(text).contains(
				requireNonNull(metricName)), text);
	}

	private static void assertSample(@NonNull String text,
			@NonNull String metricName,
			McpMetricsSnapshot.@NonNull EndpointMethodKey key, long value) {
		Assertions.assertTrue(requireNonNull(text).contains(
				requireNonNull(metricName) + encodedLabels(requireNonNull(key))
						+ " " + value + "\n"), text);
	}

	@NonNull
	private static Map<@NonNull String, @NonNull String> labels(
			McpMetricsSnapshot.@NonNull EndpointMethodKey key) {
		return Map.of("endpoint", requireNonNull(key).endpointPath(), "method",
				key.jsonRpcMethod());
	}

	@NonNull
	private static String encodedLabels(
			McpMetricsSnapshot.@NonNull EndpointMethodKey key) {
		return "{endpoint=\"" + requireNonNull(key).endpointPath()
				+ "\",method=\"" + key.jsonRpcMethod() + "\"}";
	}

	private static McpMetricsSnapshot.@NonNull EndpointMethodKey key(
			@NonNull String endpointPath, @NonNull String jsonRpcMethod) {
		return new McpMetricsSnapshot.EndpointMethodKey(
				requireNonNull(endpointPath), requireNonNull(jsonRpcMethod));
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
						"progress-cancelation-metrics-test", "3.6.0-SNAPSHOT")
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
			@NonNull Map<@NonNull String, @NonNull String> labels) {}
}
