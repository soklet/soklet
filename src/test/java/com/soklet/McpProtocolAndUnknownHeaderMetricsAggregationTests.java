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
import java.lang.reflect.Type;
import java.util.ArrayList;
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
 * Focused public and default-collector coverage for MCP protocol-error and
 * unknown-mirrored-header counter aggregates.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpProtocolAndUnknownHeaderMetricsAggregationTests {
	private static final String ENDPOINT_PATH =
			"/mcp/protocol-header-metrics";
	private static final String JSON_RPC_METHOD = "tools/call";
	private static final int MANUAL_DIMENSION_CAPACITY = 8_192;
	private static final List<Integer> FRAMEWORK_PROTOCOL_ERROR_CODES = List.of(
			-32700, -32600, -32601, -32602, -32603,
			-32020, -32021, -32022, -31999, -31998);
	private static final String PROTOCOL_ERRORS_METRIC_NAME =
			"soklet_mcp_protocol_errors_total";
	private static final String PROTOCOL_ERRORS_HELP =
			"Total client-visible MCP protocol errors by fixed code";
	private static final String UNKNOWN_HEADERS_METRIC_NAME =
			"soklet_mcp_unknown_mirrored_headers_total";
	private static final String UNKNOWN_HEADERS_HELP =
			"Total unknown MCP mirrored-header occurrences by endpoint and method";

	@Test
	public void snapshotContractUsesImmutableProtocolAndUnknownHeaderCounterMaps()
			throws Exception {
		assertCountMapProperty("getProtocolErrors", "protocolErrors",
				Integer.class);
		assertCountMapProperty("getUnknownMirroredHeaders",
				"unknownMirroredHeaders",
				McpMetricsSnapshot.EndpointMethodKey.class);

		McpMetricsSnapshot empty = McpMetricsSnapshot.emptyInstance();
		Assertions.assertSame(empty, McpMetricsSnapshot.emptyInstance());
		Assertions.assertTrue(empty.getProtocolErrors().isEmpty());
		Assertions.assertTrue(empty.getUnknownMirroredHeaders().isEmpty());

		McpMetricsSnapshot.EndpointMethodKey routedKey = key(ENDPOINT_PATH,
				JSON_RPC_METHOD);
		McpMetricsSnapshot.EndpointMethodKey applicationKey = key(
				"/application-defined", "vendor.example/arbitrary");
		Map<Integer, Long> protocolSource = new LinkedHashMap<>();
		protocolSource.put(123_456, 0L);
		protocolSource.put(-32600, 2L);
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> unknownSource =
				new LinkedHashMap<>();
		unknownSource.put(routedKey, 3L);
		unknownSource.put(applicationKey, 0L);
		McpMetricsSnapshot snapshot = McpMetricsSnapshot.builder()
				.protocolErrors(protocolSource)
				.unknownMirroredHeaders(unknownSource)
				.build();
		protocolSource.clear();
		unknownSource.put(routedKey, 99L);

		Assertions.assertEquals(Map.of(-32600, 2L, 123_456, 0L),
				snapshot.getProtocolErrors());
		Assertions.assertEquals(List.of(-32600, 123_456),
				List.copyOf(snapshot.getProtocolErrors().keySet()),
				"Integer-keyed public snapshots retain natural code order.");
		Assertions.assertEquals(Map.of(routedKey, 3L, applicationKey, 0L),
				snapshot.getUnknownMirroredHeaders());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getProtocolErrors().clear());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getUnknownMirroredHeaders().put(applicationKey,
						1L));
		Assertions.assertEquals(123_456,
				new McpMetricsEvent.ProtocolError(123_456).code(),
				"Public/manual protocol values are not the framework-production allowlist.");
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
				() -> McpMetricsSnapshot.builder().protocolErrors(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().unknownMirroredHeaders(null));
		assertInvalidCountMapInputs(routedKey);
	}

	@Test
	public void defaultCollectorAggregatesRendersAndFiltersProtocolAndUnknownHeaderFamilies() {
		DefaultMetricsCollector pristine = DefaultMetricsCollector.defaultInstance();
		assertSparseFamilyAbsent(prometheus(pristine),
				PROTOCOL_ERRORS_METRIC_NAME);
		assertSparseFamilyAbsent(prometheus(pristine),
				UNKNOWN_HEADERS_METRIC_NAME);

		DefaultMetricsCollector configured = configuredCollector();
		McpMetricsSnapshot configuredSnapshot = configured.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertTrue(configuredSnapshot.getProtocolErrors().isEmpty());
		Assertions.assertTrue(configuredSnapshot.getUnknownMirroredHeaders()
				.isEmpty());
		assertSparseFamilyAbsent(prometheus(configured),
				PROTOCOL_ERRORS_METRIC_NAME);
		assertSparseFamilyAbsent(prometheus(configured),
				UNKNOWN_HEADERS_METRIC_NAME);

		DefaultMetricsCollector protocolDriven =
				DefaultMetricsCollector.defaultInstance();
		Map<Integer, Long> expectedProtocolErrors = new HashMap<>();
		for (Integer code : FRAMEWORK_PROTOCOL_ERROR_CODES) {
			protocolDriven.didRecordMcpMetricsEvent(
					new McpMetricsEvent.ProtocolError(code));
			expectedProtocolErrors.put(code, 1L);
		}
		Integer repeatedCode = FRAMEWORK_PROTOCOL_ERROR_CODES.get(0);
		protocolDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProtocolError(repeatedCode));
		expectedProtocolErrors.put(repeatedCode, 2L);
		McpMetricsSnapshot protocolSnapshot = protocolDriven.snapshot()
				.orElseThrow().getMcpMetrics();
		Assertions.assertEquals(expectedProtocolErrors,
				protocolSnapshot.getProtocolErrors());
		Assertions.assertTrue(protocolSnapshot.getUnknownMirroredHeaders()
				.isEmpty());
		String protocolText = prometheus(protocolDriven);
		assertMetricType(protocolText, PROTOCOL_ERRORS_METRIC_NAME,
				PROTOCOL_ERRORS_HELP);
		for (Map.Entry<Integer, Long> entry : expectedProtocolErrors.entrySet())
			assertProtocolSample(protocolText, entry.getKey(), entry.getValue());
		assertSparseFamilyAbsent(protocolText, UNKNOWN_HEADERS_METRIC_NAME);

		McpMetricsSnapshot.EndpointMethodKey recognized = key(ENDPOINT_PATH,
				JSON_RPC_METHOD);
		McpMetricsSnapshot.EndpointMethodKey unrecognized = key(ENDPOINT_PATH,
				McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD);
		DefaultMetricsCollector headerDriven =
				DefaultMetricsCollector.defaultInstance();
		headerDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.UnknownMirroredHeader(
						recognized.endpointPath(), recognized.jsonRpcMethod()));
		headerDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.UnknownMirroredHeader(
						recognized.endpointPath(), recognized.jsonRpcMethod()));
		headerDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.UnknownMirroredHeader(
						unrecognized.endpointPath(), unrecognized.jsonRpcMethod()));
		McpMetricsSnapshot headerSnapshot = headerDriven.snapshot()
				.orElseThrow().getMcpMetrics();
		Assertions.assertTrue(headerSnapshot.getProtocolErrors().isEmpty());
		Assertions.assertEquals(Map.of(recognized, 2L, unrecognized, 1L),
				headerSnapshot.getUnknownMirroredHeaders());
		String headerText = prometheus(headerDriven);
		assertMetricType(headerText, UNKNOWN_HEADERS_METRIC_NAME,
				UNKNOWN_HEADERS_HELP);
		assertUnknownHeaderSample(headerText, recognized, 2L);
		assertUnknownHeaderSample(headerText, unrecognized, 1L);
		assertSparseFamilyAbsent(headerText, PROTOCOL_ERRORS_METRIC_NAME);

		headerDriven.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProtocolError(-32600));
		Set<SampleProjection> observed = ConcurrentHashMap.newKeySet();
		String selected = headerDriven.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> {
							if (!isProtocolOrUnknownHeaderSample(sample.getName()))
								return false;
							observed.add(new SampleProjection(sample.getName(),
									sample.getLabels()));
							return true;
						})
						.build()).orElseThrow();
		Assertions.assertEquals(Set.of(
				new SampleProjection(PROTOCOL_ERRORS_METRIC_NAME,
						Map.of("code", "-32600")),
				new SampleProjection(UNKNOWN_HEADERS_METRIC_NAME,
						labels(recognized)),
				new SampleProjection(UNKNOWN_HEADERS_METRIC_NAME,
						labels(unrecognized))), observed,
				"Unknown-header metrics expose endpoint and bounded method only.");
		assertMetricType(selected, PROTOCOL_ERRORS_METRIC_NAME,
				PROTOCOL_ERRORS_HELP);
		assertMetricType(selected, UNKNOWN_HEADERS_METRIC_NAME,
				UNKNOWN_HEADERS_HELP);
		assertProtocolSample(selected, -32600, 1L);
		assertUnknownHeaderSample(selected, recognized, 2L);
		assertUnknownHeaderSample(selected, unrecognized, 1L);

		String rejected = headerDriven.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample ->
								!isProtocolOrUnknownHeaderSample(sample.getName()))
						.build()).orElseThrow();
		assertSparseFamilyAbsent(rejected, PROTOCOL_ERRORS_METRIC_NAME);
		assertSparseFamilyAbsent(rejected, UNKNOWN_HEADERS_METRIC_NAME);

		String openMetrics = headerDriven.snapshotText(
				MetricsCollector.SnapshotTextOptions.fromMetricsFormat(
						MetricsCollector.MetricsFormat.OPEN_METRICS_1_0))
				.orElseThrow();
		assertProtocolSample(openMetrics, -32600, 1L);
		assertUnknownHeaderSample(openMetrics, recognized, 2L);
		assertUnknownHeaderSample(openMetrics, unrecognized, 1L);
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"), openMetrics);
		Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));
	}

	@Test
	public void resetClearsSparseProtocolAndUnknownHeaderCountersWithoutLeavingFamilyMetadata() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		McpMetricsSnapshot.EndpointMethodKey key = key(ENDPOINT_PATH,
				JSON_RPC_METHOD);
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProtocolError(-32600));
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProtocolError(-32600));
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.UnknownMirroredHeader(key.endpointPath(),
						key.jsonRpcMethod()));
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(Map.of(-32600, 2L),
				retained.getProtocolErrors());
		Assertions.assertEquals(Map.of(key, 1L),
				retained.getUnknownMirroredHeaders());

		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.ProtocolError(-32601));
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.UnknownMirroredHeader(key.endpointPath(),
						key.jsonRpcMethod()));
		collector.reset();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
		String resetText = prometheus(collector);
		assertSparseFamilyAbsent(resetText, PROTOCOL_ERRORS_METRIC_NAME);
		assertSparseFamilyAbsent(resetText, UNKNOWN_HEADERS_METRIC_NAME);
		Assertions.assertEquals(Map.of(-32600, 2L),
				retained.getProtocolErrors());
		Assertions.assertEquals(Map.of(key, 1L),
				retained.getUnknownMirroredHeaders());
	}

	@Test
	@Timeout(30)
	public void manualDimensionRetentionIsIndependentlyBoundedPerFamily() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> publicUnknownHeaders =
				new LinkedHashMap<>();
		for (int index = 0; index <= MANUAL_DIMENSION_CAPACITY; ++index) {
			McpMetricsSnapshot.EndpointMethodKey manualKey = key(
					"/mcp/manual-" + index,
					"vendor.example/method-" + index);
			publicUnknownHeaders.put(manualKey, 1L);
			collector.didRecordMcpMetricsEvent(
					new McpMetricsEvent.UnknownMirroredHeader(
							manualKey.endpointPath(), manualKey.jsonRpcMethod()));
		}
		McpMetricsSnapshot.EndpointMethodKey newestUnknown = key(
				"/mcp/manual-" + MANUAL_DIMENSION_CAPACITY,
				"vendor.example/method-" + MANUAL_DIMENSION_CAPACITY);
		McpMetricsSnapshot unknownFilled = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(MANUAL_DIMENSION_CAPACITY,
				unknownFilled.getUnknownMirroredHeaders().size());
		Assertions.assertEquals(1L,
				unknownFilled.getUnknownMirroredHeaders().get(newestUnknown));
		Assertions.assertTrue(unknownFilled.getProtocolErrors().isEmpty());

		int protocolBase = 1_000_000;
		Map<Integer, Long> publicProtocolErrors = new LinkedHashMap<>();
		for (int index = 0; index <= MANUAL_DIMENSION_CAPACITY; ++index) {
			int code = protocolBase + index;
			publicProtocolErrors.put(code, 1L);
			collector.didRecordMcpMetricsEvent(
					new McpMetricsEvent.ProtocolError(code));
		}
		int newestProtocol = protocolBase + MANUAL_DIMENSION_CAPACITY;
		McpMetricsSnapshot bothFilled = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(MANUAL_DIMENSION_CAPACITY,
				bothFilled.getProtocolErrors().size());
		Assertions.assertEquals(1L,
				bothFilled.getProtocolErrors().get(newestProtocol));
		Assertions.assertEquals(MANUAL_DIMENSION_CAPACITY,
				bothFilled.getUnknownMirroredHeaders().size());
		Assertions.assertEquals(1L,
				bothFilled.getUnknownMirroredHeaders().get(newestUnknown),
				"Each manual-dimension family owns an independent retention bound.");

		McpMetricsSnapshot uncappedPublicValue = McpMetricsSnapshot.builder()
				.protocolErrors(publicProtocolErrors)
				.unknownMirroredHeaders(publicUnknownHeaders)
				.build();
		Assertions.assertEquals(MANUAL_DIMENSION_CAPACITY + 1,
				uncappedPublicValue.getProtocolErrors().size());
		Assertions.assertEquals(MANUAL_DIMENSION_CAPACITY + 1,
				uncappedPublicValue.getUnknownMirroredHeaders().size(),
				"The public snapshot value carrier does not impose Default's cache bound.");
	}

	@Test
	@Timeout(15)
	public void concurrentDirectProtocolAndUnknownHeaderIngestIsLosslessAndRetainedSnapshotsRemainImmutable()
			throws Exception {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		int threadCount = FRAMEWORK_PROTOCOL_ERROR_CODES.size();
		int rounds = 40;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		List<McpMetricsSnapshot.EndpointMethodKey> keys = new ArrayList<>();
		for (int thread = 0; thread < threadCount; ++thread)
			keys.add(key(ENDPOINT_PATH, "tools/call-" + thread));
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int thread = 0; thread < threadCount; ++thread) {
				int code = FRAMEWORK_PROTOCOL_ERROR_CODES.get(thread);
				McpMetricsSnapshot.EndpointMethodKey key = keys.get(thread);
				futures.add(executor.submit(() -> {
					start.await();
					for (int round = 0; round < rounds; ++round) {
						collector.didRecordMcpMetricsEvent(
								new McpMetricsEvent.ProtocolError(code));
						collector.didRecordMcpMetricsEvent(
								new McpMetricsEvent.UnknownMirroredHeader(
										key.endpointPath(), key.jsonRpcMethod()));
						collector.didRecordMcpMetricsEvent(
								new McpMetricsEvent.UnknownMirroredHeader(
										key.endpointPath(), key.jsonRpcMethod()));
					}
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

		Map<Integer, Long> expectedProtocolErrors = new HashMap<>();
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> expectedUnknownHeaders =
				new HashMap<>();
		for (int thread = 0; thread < threadCount; ++thread) {
			expectedProtocolErrors.put(FRAMEWORK_PROTOCOL_ERROR_CODES.get(thread),
					(long) rounds);
			expectedUnknownHeaders.put(keys.get(thread), rounds * 2L);
		}
		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(expectedProtocolErrors,
				retained.getProtocolErrors());
		Assertions.assertEquals(expectedUnknownHeaders,
				retained.getUnknownMirroredHeaders());

		collector.didRecordMcpMetricsEvent(new McpMetricsEvent.ProtocolError(
				FRAMEWORK_PROTOCOL_ERROR_CODES.get(0)));
		McpMetricsSnapshot.EndpointMethodKey first = keys.get(0);
		collector.didRecordMcpMetricsEvent(
				new McpMetricsEvent.UnknownMirroredHeader(first.endpointPath(),
						first.jsonRpcMethod()));
		collector.reset();
		Assertions.assertEquals(expectedProtocolErrors,
				retained.getProtocolErrors());
		Assertions.assertEquals(expectedUnknownHeaders,
				retained.getUnknownMirroredHeaders());
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				collector.snapshot().orElseThrow().getMcpMetrics());
	}

	private static void assertCountMapProperty(@NonNull String getterName,
			@NonNull String builderName, @NonNull Class<?> keyType)
			throws Exception {
		Method getter = McpMetricsSnapshot.class.getMethod(
				requireNonNull(getterName));
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertEquals(Map.class, getter.getReturnType());
		assertCountMapType(getter.getGenericReturnType(),
				getter.getAnnotatedReturnType(), requireNonNull(keyType));

		Method builder = McpMetricsSnapshot.Builder.class.getMethod(
				requireNonNull(builderName), Map.class);
		Assertions.assertTrue(Modifier.isPublic(builder.getModifiers()));
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				builder.getReturnType());
		Assertions.assertTrue(builder.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		assertCountMapType(builder.getGenericParameterTypes()[0],
				builder.getAnnotatedParameterTypes()[0], keyType);
	}

	private static void assertCountMapType(@NonNull Type type,
			@NonNull AnnotatedType annotatedType, @NonNull Class<?> keyType) {
		Assertions.assertInstanceOf(ParameterizedType.class, requireNonNull(type));
		ParameterizedType parameterizedType = (ParameterizedType) type;
		Assertions.assertEquals(Map.class, parameterizedType.getRawType());
		Assertions.assertArrayEquals(new Type[]{requireNonNull(keyType), Long.class},
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
		Map<Integer, Long> nullProtocolKey = new HashMap<>();
		nullProtocolKey.put(null, 1L);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().protocolErrors(nullProtocolKey));
		Map<Integer, Long> nullProtocolValue = new HashMap<>();
		nullProtocolValue.put(-32600, null);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().protocolErrors(
						nullProtocolValue));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().protocolErrors(
						Map.of(-32600, -1L)));
		Assertions.assertEquals(0L, McpMetricsSnapshot.builder()
				.protocolErrors(Map.of(123_456, 0L)).build()
				.getProtocolErrors().get(123_456));

		Map<McpMetricsSnapshot.EndpointMethodKey, Long> nullHeaderKey =
				new HashMap<>();
		nullHeaderKey.put(null, 1L);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().unknownMirroredHeaders(
						nullHeaderKey));
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> nullHeaderValue =
				new HashMap<>();
		nullHeaderValue.put(requireNonNull(key), null);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().unknownMirroredHeaders(
						nullHeaderValue));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().unknownMirroredHeaders(
						Map.of(key, -1L)));
		Assertions.assertEquals(0L, McpMetricsSnapshot.builder()
				.unknownMirroredHeaders(Map.of(key, 0L)).build()
				.getUnknownMirroredHeaders().get(key));
	}

	private static boolean isProtocolOrUnknownHeaderSample(
			@NonNull String metricName) {
		return Set.of(PROTOCOL_ERRORS_METRIC_NAME, UNKNOWN_HEADERS_METRIC_NAME)
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

	private static void assertProtocolSample(@NonNull String text, int code,
			long value) {
		Assertions.assertTrue(requireNonNull(text).contains(
				PROTOCOL_ERRORS_METRIC_NAME + "{code=\"" + code + "\"} "
						+ value + "\n"), text);
	}

	private static void assertUnknownHeaderSample(@NonNull String text,
			McpMetricsSnapshot.@NonNull EndpointMethodKey key, long value) {
		Assertions.assertTrue(requireNonNull(text).contains(
				UNKNOWN_HEADERS_METRIC_NAME + encodedLabels(requireNonNull(key))
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
						"protocol-header-metrics-test", "3.6.0-SNAPSHOT")
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
