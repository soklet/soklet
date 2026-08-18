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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public attachment-contract tests for provisional MCP lifecycle and metrics
 * values.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpObservabilityPublicApiTests {
	@Test
	public void sharedHostsExposeTheFinalAttachmentDescriptors() throws Exception {
		Method didStart = LifecycleObserver.class.getMethod(
				"didStartMcpRequestHandling", McpRequestContext.class);
		Method didFinish = LifecycleObserver.class.getMethod(
				"didFinishMcpRequestHandling", McpRequestContext.class,
				McpRequestOutcome.class, McpJsonRpcError.class, Duration.class,
				List.class);
		Method didStop = LifecycleObserver.class.getMethod(
				"didStopMcpServer", McpServer.class, McpShutdownOutcome.class);
		Method getDiagnostics = McpServer.class.getMethod("getDiagnostics");
		Method getOperationName = McpRequestContext.class.getMethod(
				"getOperationName");
		Method didRecord = MetricsCollector.class.getMethod(
				"didRecordMcpMetricsEvent", McpMetricsEvent.class);
		Method getMetrics = MetricsCollector.Snapshot.class.getMethod(
				"getMcpMetrics");
		Method setMetrics = MetricsCollector.Snapshot.Builder.class.getMethod(
				"mcpMetrics", McpMetricsSnapshot.class);
		Method setUnknownMirroredHeaderNameDiagnostics =
				McpServer.Builder.class.getMethod(
						"unknownMirroredHeaderNameDiagnostics", Boolean.class);

		Assertions.assertTrue(didStart.isDefault());
		Assertions.assertTrue(didFinish.isDefault());
		Assertions.assertTrue(didStop.isDefault());
		Assertions.assertEquals(void.class, didStop.getReturnType());
		for (AnnotatedType parameter : didStop.getAnnotatedParameterTypes())
			Assertions.assertTrue(parameter.isAnnotationPresent(NonNull.class));
		Assertions.assertEquals(McpServerDiagnostics.class,
				getDiagnostics.getReturnType());
		Assertions.assertTrue(getDiagnostics.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		Assertions.assertEquals(Optional.class, getOperationName.getReturnType());
		Assertions.assertInstanceOf(ParameterizedType.class,
				getOperationName.getGenericReturnType());
		ParameterizedType operationNameType = (ParameterizedType)
				getOperationName.getGenericReturnType();
		Assertions.assertArrayEquals(new Object[]{String.class},
				operationNameType.getActualTypeArguments());
		AnnotatedType annotatedOperationName =
				getOperationName.getAnnotatedReturnType();
		Assertions.assertTrue(annotatedOperationName
				.isAnnotationPresent(NonNull.class));
		Assertions.assertInstanceOf(AnnotatedParameterizedType.class,
				annotatedOperationName);
		AnnotatedType operationNameValue = ((AnnotatedParameterizedType)
				annotatedOperationName).getAnnotatedActualTypeArguments()[0];
		Assertions.assertEquals(String.class, operationNameValue.getType());
		Assertions.assertTrue(operationNameValue
				.isAnnotationPresent(NonNull.class));

		AnnotatedType[] finishParameters = didFinish.getAnnotatedParameterTypes();
		Assertions.assertTrue(finishParameters[0]
				.isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(finishParameters[2]
				.isAnnotationPresent(Nullable.class));
		Assertions.assertTrue(finishParameters[4]
				.isAnnotationPresent(NonNull.class));
		Assertions.assertInstanceOf(AnnotatedParameterizedType.class,
				finishParameters[4]);
		AnnotatedType throwableType = ((AnnotatedParameterizedType)
				finishParameters[4]).getAnnotatedActualTypeArguments()[0];
		Assertions.assertEquals(Throwable.class, throwableType.getType());
		Assertions.assertTrue(throwableType.isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(didRecord.isDefault());
		Assertions.assertEquals(McpMetricsSnapshot.class,
				getMetrics.getReturnType());
		Assertions.assertEquals(MetricsCollector.Snapshot.Builder.class,
				setMetrics.getReturnType());
		Assertions.assertEquals(McpServer.Builder.class,
				setUnknownMirroredHeaderNameDiagnostics.getReturnType());
		Assertions.assertEquals(Boolean.class,
				setUnknownMirroredHeaderNameDiagnostics.getParameterTypes()[0]);
		Assertions.assertTrue(setUnknownMirroredHeaderNameDiagnostics
				.getAnnotatedParameterTypes()[0].isAnnotationPresent(NonNull.class));
	}

	@Test
	public void snapshotAlwaysCarriesAnImmutableMcpAggregate() {
		MetricsCollector.Snapshot empty = MetricsCollector.Snapshot.builder().build();
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				empty.getMcpMetrics());
		Assertions.assertEquals(0L,
				empty.getMcpMetrics().getServerStarts());
		Assertions.assertTrue(empty.getMcpMetrics().getShutdowns().isEmpty());
		Assertions.assertEquals(0L,
				empty.getMcpMetrics().getConnectionsAccepted());
		Assertions.assertEquals(0L,
				empty.getMcpMetrics().getConnectionsRejected());
		Assertions.assertTrue(empty.getMcpMetrics().getTransportFailures()
				.isEmpty());
		Assertions.assertEquals(0L,
				empty.getMcpMetrics().getRequestsAccepted());
		Assertions.assertEquals(0L,
				empty.getMcpMetrics().getRequestsRejected());

		McpMetricsSnapshot aggregate = McpMetricsSnapshot.builder()
				.serverStarts(3L)
				.requestsAccepted(5L)
				.requestsRejected(1L)
				.shutdowns(Map.of(McpShutdownOutcome.CLEAN, 2L))
				.build();
		MetricsCollector.Snapshot snapshot = MetricsCollector.Snapshot.builder()
				.mcpMetrics(aggregate)
				.build();

		Assertions.assertSame(aggregate, snapshot.getMcpMetrics());
		Assertions.assertEquals(3L, aggregate.getServerStarts());
		Assertions.assertEquals(5L, aggregate.getRequestsAccepted());
		Assertions.assertEquals(1L, aggregate.getRequestsRejected());
		Assertions.assertEquals(2L, aggregate.getShutdowns()
				.get(McpShutdownOutcome.CLEAN));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> aggregate.getShutdowns().put(
						McpShutdownOutcome.RESIDUAL_HANDLERS, 1L));
		Assertions.assertThrows(NullPointerException.class,
				() -> MetricsCollector.Snapshot.builder().mcpMetrics(null));
	}

	@Test
	public void provisionalVocabulariesHaveReviewedCurrentBounds() {
		Assertions.assertEquals("<unrecognized>",
				McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD);
		Assertions.assertArrayEquals(new McpRequestOutcome[]{
				McpRequestOutcome.COMPLETE,
				McpRequestOutcome.INPUT_REQUIRED,
				McpRequestOutcome.REJECTED,
				McpRequestOutcome.APPLICATION_ERROR,
				McpRequestOutcome.PROTOCOL_ERROR,
				McpRequestOutcome.INTERNAL_ERROR,
				McpRequestOutcome.CANCELED,
				McpRequestOutcome.DEADLINE_EXCEEDED,
				McpRequestOutcome.CLIENT_DISCONNECTED,
				McpRequestOutcome.WRITE_FAILED
		}, McpRequestOutcome.values());
		Assertions.assertArrayEquals(new McpStreamTerminationReason[]{
				McpStreamTerminationReason.COMPLETED,
				McpStreamTerminationReason.CLIENT_DISCONNECTED,
				McpStreamTerminationReason.REQUEST_CANCELED,
				McpStreamTerminationReason.DEADLINE_EXCEEDED,
				McpStreamTerminationReason.WRITE_FAILED,
				McpStreamTerminationReason.BACKPRESSURE,
				McpStreamTerminationReason.SERVER_STOPPED,
				McpStreamTerminationReason.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED,
				McpStreamTerminationReason.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
				McpStreamTerminationReason.INTERNAL_ERROR
		}, McpStreamTerminationReason.values());
		Assertions.assertSame(
				LogEventType.LIFECYCLE_OBSERVER_DID_START_MCP_REQUEST_HANDLING_FAILED,
				LogEventType.valueOf(
						"LIFECYCLE_OBSERVER_DID_START_MCP_REQUEST_HANDLING_FAILED"));
		Assertions.assertSame(
				LogEventType.LIFECYCLE_OBSERVER_DID_FINISH_MCP_REQUEST_HANDLING_FAILED,
				LogEventType.valueOf(
						"LIFECYCLE_OBSERVER_DID_FINISH_MCP_REQUEST_HANDLING_FAILED"));
		Assertions.assertSame(LogEventType.MCP_UNKNOWN_MIRRORED_HEADER,
				LogEventType.valueOf("MCP_UNKNOWN_MIRRORED_HEADER"));
		Assertions.assertSame(LogEventType.MCP_TRACE_CORRELATION,
				LogEventType.valueOf("MCP_TRACE_CORRELATION"));
	}

	@Test
	public void metricSchemaHasExactFiniteNonTraceDimensions() throws Exception {
		Map<Class<?>, List<Map.Entry<String, Class<?>>>> expectedComponents =
				Map.ofEntries(
						Map.entry(McpMetricsEvent.ServerStarted.class, List.of()),
						Map.entry(McpMetricsEvent.ConnectionAccepted.class,
								List.of()),
						Map.entry(McpMetricsEvent.ConnectionRejected.class,
								List.of()),
						Map.entry(McpMetricsEvent.RequestAccepted.class, List.of()),
						Map.entry(McpMetricsEvent.RequestRejected.class, List.of()),
						Map.entry(McpMetricsEvent.RequestStarted.class, List.of(
								component("endpointPath", String.class),
								component("jsonRpcMethod", String.class))),
						Map.entry(McpMetricsEvent.RequestFinished.class, List.of(
								component("endpointPath", String.class),
								component("jsonRpcMethod", String.class),
								component("outcome", McpRequestOutcome.class),
								component("duration", Duration.class))),
						Map.entry(McpMetricsEvent.RequestStreamOpened.class, List.of(
								component("endpointPath", String.class),
								component("jsonRpcMethod", String.class))),
						Map.entry(McpMetricsEvent.RequestStreamClosed.class, List.of(
								component("endpointPath", String.class),
								component("jsonRpcMethod", String.class),
								component("reason",
										McpStreamTerminationReason.class),
								component("duration", Duration.class))),
						Map.entry(McpMetricsEvent.SubscriptionOpened.class, List.of(
								component("endpointPath", String.class))),
						Map.entry(McpMetricsEvent.SubscriptionClosed.class, List.of(
								component("endpointPath", String.class),
								component("reason",
										McpStreamTerminationReason.class),
								component("duration", Duration.class))),
						Map.entry(McpMetricsEvent.CancelationSignaled.class, List.of(
								component("endpointPath", String.class),
								component("jsonRpcMethod", String.class))),
						Map.entry(McpMetricsEvent.ProgressEmitted.class, List.of(
								component("endpointPath", String.class),
								component("jsonRpcMethod", String.class))),
						Map.entry(McpMetricsEvent.KeepAliveEmitted.class, List.of()),
						Map.entry(McpMetricsEvent.ProtocolError.class, List.of(
								component("code", Integer.class))),
						Map.entry(McpMetricsEvent.UnknownMirroredHeader.class, List.of(
								component("endpointPath", String.class),
								component("jsonRpcMethod", String.class))),
						Map.entry(McpMetricsEvent.HandlerExecutionStarted.class,
								List.of()),
						Map.entry(McpMetricsEvent.HandlerExecutionFinished.class,
								List.of()),
						Map.entry(McpMetricsEvent.HandlerQueued.class, List.of()),
						Map.entry(McpMetricsEvent.HandlerDequeued.class, List.of()),
						Map.entry(McpMetricsEvent.HandlerCapacityRejected.class,
								List.of()),
						Map.entry(McpMetricsEvent.TransportFailure.class, List.of(
								component("reason",
										MetricsCollector.TransportFailureReason.class))),
						Map.entry(McpMetricsEvent.ServerStopped.class, List.of(
								component("outcome", McpShutdownOutcome.class))));

		Set<Class<?>> permittedTypes = Set.copyOf(Arrays.asList(
				McpMetricsEvent.class.getPermittedSubclasses()));
		Assertions.assertEquals(23, permittedTypes.size());
		Assertions.assertEquals(expectedComponents.keySet(), permittedTypes);
		for (Map.Entry<Class<?>, List<Map.Entry<String, Class<?>>>> entry
				: expectedComponents.entrySet()) {
			Class<?> eventType = entry.getKey();
			Assertions.assertFalse(eventType.isRecord(), eventType.getName());
			List<Map.Entry<String, Class<?>>> actualComponents = Arrays.stream(
					eventType.getDeclaredMethods())
					.filter(method -> Modifier.isPublic(method.getModifiers()))
					.filter(method -> method.getParameterCount() == 0)
					.filter(method -> method.getName().startsWith("get"))
					.map(method -> component(
							Character.toLowerCase(method.getName().charAt(3))
									+ method.getName().substring(4),
							method.getReturnType()))
					.sorted(Map.Entry.comparingByKey())
					.toList();
			List<Map.Entry<String, Class<?>>> expected = entry.getValue().stream()
					.sorted(Map.Entry.comparingByKey())
					.toList();
			Assertions.assertEquals(expected, actualComponents,
					eventType.getName());
			Arrays.stream(eventType.getDeclaredMethods())
					.filter(method -> Modifier.isPublic(method.getModifiers()))
					.filter(method -> method.getParameterCount() == 0)
					.filter(method -> method.getName().startsWith("get"))
					.forEach(method -> {
						Assertions.assertTrue(method.getAnnotatedReturnType()
								.isAnnotationPresent(NonNull.class),
								method.toString());
						Assertions.assertFalse(Map.class.isAssignableFrom(
								method.getReturnType()), method.toString());
						assertNonTraceDimensionName(
								Character.toLowerCase(method.getName().charAt(3))
										+ method.getName().substring(4));
					});
		}

		Map<String, Class<?>> expectedGetters = Map.ofEntries(
				Map.entry("getServerStarts", Long.class),
				Map.entry("getActiveHandlerExecutions", Long.class),
				Map.entry("getHandlerQueueDepth", Long.class),
				Map.entry("getHandlerCapacityRejections", Long.class),
				Map.entry("getShutdowns", Map.class),
				Map.entry("getConnectionsAccepted", Long.class),
				Map.entry("getConnectionsRejected", Long.class),
				Map.entry("getTransportFailures", Map.class),
				Map.entry("getRequestsAccepted", Long.class),
				Map.entry("getRequestsRejected", Long.class),
				Map.entry("getActiveRequests", Long.class),
				Map.entry("getRequests", Map.class),
				Map.entry("getRequestDurations", Map.class),
				Map.entry("getActiveRequestStreams", Long.class),
				Map.entry("getRequestStreamDurations", Map.class),
				Map.entry("getActiveSubscriptions", Long.class),
				Map.entry("getSubscriptionDurations", Map.class),
				Map.entry("getCancelationsSignaled", Map.class),
				Map.entry("getProgressEmitted", Map.class),
				Map.entry("getKeepAlivesEmitted", Long.class),
				Map.entry("getProtocolErrors", Map.class),
				Map.entry("getUnknownMirroredHeaders", Map.class));
		Map<String, Class<?>> actualGetters = Arrays.stream(
				McpMetricsSnapshot.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
					.filter(method -> !Modifier.isStatic(method.getModifiers()))
					.collect(Collectors.toUnmodifiableMap(Method::getName,
							Method::getReturnType));
		Assertions.assertEquals(22, actualGetters.size());
		Assertions.assertEquals(expectedGetters, actualGetters);
		for (String getterName : expectedGetters.keySet()) {
			Method getter = McpMetricsSnapshot.class.getMethod(getterName);
			Assertions.assertEquals(0, getter.getParameterCount());
			Assertions.assertTrue(getter.getAnnotatedReturnType()
					.isAnnotationPresent(NonNull.class));
			if (!getterName.equals("getUnknownMirroredHeaders"))
				assertNonTraceDimensionName(getterName);
		}
		Assertions.assertEquals(Map.of(
				"emptyInstance", McpMetricsSnapshot.class,
				"builder", McpMetricsSnapshot.Builder.class),
				Arrays.stream(McpMetricsSnapshot.class.getDeclaredMethods())
						.filter(method -> Modifier.isPublic(method.getModifiers()))
						.filter(method -> Modifier.isStatic(method.getModifiers()))
						.collect(Collectors.toUnmodifiableMap(Method::getName,
								Method::getReturnType)));
		Assertions.assertEquals(0, McpMetricsSnapshot.class.getConstructors().length);

		Map<String, Map.Entry<Class<?>, List<Class<?>>>> expectedBuilderMethods =
				Map.ofEntries(
						Map.entry("serverStarts", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("activeHandlerExecutions", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("handlerQueueDepth", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("handlerCapacityRejections", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("shutdowns", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("connectionsAccepted", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("connectionsRejected", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("transportFailures", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("requestsAccepted", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("requestsRejected", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("activeRequests", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("requests", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("requestDurations", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("activeRequestStreams", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("requestStreamDurations", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("activeSubscriptions", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("subscriptionDurations", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("cancelationsSignaled", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("progressEmitted", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("keepAlivesEmitted", methodSignature(
								McpMetricsSnapshot.Builder.class, Long.class)),
						Map.entry("protocolErrors", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("unknownMirroredHeaders", methodSignature(
								McpMetricsSnapshot.Builder.class, Map.class)),
						Map.entry("build", methodSignature(McpMetricsSnapshot.class)));
		Map<String, Map.Entry<Class<?>, List<Class<?>>>> actualBuilderMethods =
				Arrays.stream(McpMetricsSnapshot.Builder.class.getDeclaredMethods())
						.filter(method -> Modifier.isPublic(method.getModifiers()))
						.collect(Collectors.toUnmodifiableMap(Method::getName,
								method -> methodSignature(method.getReturnType(),
										method.getParameterTypes())));
		Assertions.assertEquals(23, actualBuilderMethods.size());
		Assertions.assertEquals(expectedBuilderMethods, actualBuilderMethods);
		for (Method method : McpMetricsSnapshot.Builder.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(method.getModifiers()))
				continue;
			Assertions.assertTrue(method.getAnnotatedReturnType()
					.isAnnotationPresent(NonNull.class));
			Arrays.stream(method.getAnnotatedParameterTypes()).forEach(
					parameter -> Assertions.assertTrue(
							parameter.isAnnotationPresent(NonNull.class)));
			if (!method.getName().equals("unknownMirroredHeaders"))
				assertNonTraceDimensionName(method.getName());
		}

		assertShutdownMapSignature(McpMetricsSnapshot.class.getMethod(
				"getShutdowns").getGenericReturnType());
		assertShutdownMapSignature(McpMetricsSnapshot.Builder.class.getMethod(
				"shutdowns", Map.class).getGenericParameterTypes()[0]);
		assertTransportFailureMapSignature(McpMetricsSnapshot.class.getMethod(
				"getTransportFailures").getGenericReturnType());
		assertTransportFailureMapSignature(McpMetricsSnapshot.Builder.class
				.getMethod("transportFailures", Map.class)
				.getGenericParameterTypes()[0]);
		assertCounterMapSignature(McpMetricsSnapshot.class.getMethod(
				"getProtocolErrors").getGenericReturnType(), Integer.class);
		assertCounterMapSignature(McpMetricsSnapshot.Builder.class.getMethod(
				"protocolErrors", Map.class).getGenericParameterTypes()[0],
				Integer.class);
		assertCounterMapSignature(McpMetricsSnapshot.class.getMethod(
				"getUnknownMirroredHeaders").getGenericReturnType(),
				McpMetricsSnapshot.EndpointMethodKey.class);
		assertCounterMapSignature(McpMetricsSnapshot.Builder.class.getMethod(
				"unknownMirroredHeaders", Map.class).getGenericParameterTypes()[0],
				McpMetricsSnapshot.EndpointMethodKey.class);

		DefaultMetricsCollector defaultCollector =
				DefaultMetricsCollector.defaultInstance();
		List<McpMetricsEvent> nonAggregatedEvents = List.of();
		Assertions.assertEquals(0, nonAggregatedEvents.size());
		nonAggregatedEvents.forEach(defaultCollector::didRecordMcpMetricsEvent);
		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				defaultCollector.snapshot().orElseThrow().getMcpMetrics());

		// Public event factories remain application-owned value carriers. This
		// gate freezes Soklet's built-in schema, not arbitrary nonempty values an
		// application may deliberately place in a factory-created event.
		McpMetricsEvent.RequestStarted applicationEvent =
				McpMetricsEvent.requestStarted(
						"/application-defined", "vendor.example/arbitrary");
		Assertions.assertEquals("vendor.example/arbitrary",
				applicationEvent.getJsonRpcMethod());
		Assertions.assertEquals(123_456,
				McpMetricsEvent.protocolError(123_456).getCode());
	}

	@Test
	public void everyMetricsEventVariantIsConstructibleAndSemanticallyTyped()
			throws NoSuchMethodException {
		String endpointPath = "/mcp";
		String method = "tools/call";
		Duration duration = Duration.ofMillis(25);
		List<McpMetricsEvent> events = List.of(
				McpMetricsEvent.serverStarted(),
				McpMetricsEvent.connectionAccepted(),
				McpMetricsEvent.connectionRejected(),
				McpMetricsEvent.requestAccepted(),
				McpMetricsEvent.requestRejected(),
				McpMetricsEvent.requestStarted(endpointPath, method),
				McpMetricsEvent.requestFinished(endpointPath, method,
						McpRequestOutcome.COMPLETE, duration),
				McpMetricsEvent.requestStreamOpened(endpointPath, method),
				McpMetricsEvent.requestStreamClosed(endpointPath, method,
						McpStreamTerminationReason.COMPLETED, duration),
				McpMetricsEvent.subscriptionOpened(endpointPath),
				McpMetricsEvent.subscriptionClosed(endpointPath,
						McpStreamTerminationReason.CLIENT_DISCONNECTED, duration),
				McpMetricsEvent.cancelationSignaled(endpointPath, method),
				McpMetricsEvent.progressEmitted(endpointPath, method),
				McpMetricsEvent.keepAliveEmitted(),
				McpMetricsEvent.protocolError(-32600),
				McpMetricsEvent.unknownMirroredHeader(endpointPath, method),
				McpMetricsEvent.handlerExecutionStarted(),
				McpMetricsEvent.handlerExecutionFinished(),
				McpMetricsEvent.handlerQueued(),
				McpMetricsEvent.handlerDequeued(),
				McpMetricsEvent.handlerCapacityRejected(),
				McpMetricsEvent.transportFailure(
						MetricsCollector.TransportFailureReason.WRITE_ERROR),
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN));

		Set<Class<?>> constructedTypes = events.stream()
				.map(Object::getClass)
				.collect(Collectors.toUnmodifiableSet());
		Set<Class<?>> permittedTypes = Set.copyOf(Arrays.asList(
				McpMetricsEvent.class.getPermittedSubclasses()));
		Assertions.assertEquals(permittedTypes, constructedTypes);
		Assertions.assertEquals(Integer.class, McpMetricsEvent.ProtocolError.class
				.getMethod("getCode").getReturnType());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsEvent.protocolError(null));

		McpMetricsEvent.RequestFinished finished =
				(McpMetricsEvent.RequestFinished) events.get(6);
		Assertions.assertEquals(endpointPath, finished.getEndpointPath());
		Assertions.assertEquals(method, finished.getJsonRpcMethod());
		Assertions.assertEquals(McpRequestOutcome.COMPLETE,
				finished.getOutcome());
		Assertions.assertEquals(duration, finished.getDuration());
	}

	@Test
	public void metricsEventDimensionsFailClosedWhenInvalid() {
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsEvent.requestStarted(null, "tools/call"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsEvent.requestStarted("", "tools/call"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsEvent.requestStarted("/mcp", ""));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsEvent.requestFinished("/mcp", "tools/call",
						McpRequestOutcome.COMPLETE, Duration.ofNanos(-1L)));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsEvent.transportFailure(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsEvent.serverStopped(null));
	}

	private static Map.Entry<String, Class<?>> component(
			@NonNull String name,
			@NonNull Class<?> type) {
		return Map.entry(name, type);
	}

	private static Map.Entry<Class<?>, List<Class<?>>> methodSignature(
			@NonNull Class<?> returnType, @NonNull Class<?>... parameterTypes) {
		return Map.entry(returnType, List.of(parameterTypes));
	}

	private static void assertShutdownMapSignature(@NonNull Object genericType) {
		ParameterizedType parameterizedType = Assertions.assertInstanceOf(
				ParameterizedType.class, genericType);
		Assertions.assertEquals(Map.class, parameterizedType.getRawType());
		Assertions.assertArrayEquals(new Object[]{McpShutdownOutcome.class,
				Long.class}, parameterizedType.getActualTypeArguments());
	}

	private static void assertTransportFailureMapSignature(
			@NonNull Object genericType) {
		ParameterizedType parameterizedType = Assertions.assertInstanceOf(
				ParameterizedType.class, genericType);
		Assertions.assertEquals(Map.class, parameterizedType.getRawType());
		Assertions.assertArrayEquals(new Object[]{
				MetricsCollector.TransportFailureReason.class, Long.class
		}, parameterizedType.getActualTypeArguments());
	}

	private static void assertCounterMapSignature(@NonNull Object genericType,
			@NonNull Class<?> keyType) {
		ParameterizedType parameterizedType = Assertions.assertInstanceOf(
				ParameterizedType.class, genericType);
		Assertions.assertEquals(Map.class, parameterizedType.getRawType());
		Assertions.assertArrayEquals(new Object[]{keyType, Long.class},
				parameterizedType.getActualTypeArguments());
	}

	private static void assertNonTraceDimensionName(@NonNull String name) {
		String normalized = name.toLowerCase(java.util.Locale.ROOT);
		for (String forbidden : List.of("trace", "token", "key", "tracestate",
				"baggage", "header", "label", "tag", "attribute",
				"dimension"))
			Assertions.assertFalse(normalized.contains(forbidden), name);
	}
}
