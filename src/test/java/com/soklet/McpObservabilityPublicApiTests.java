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
		Assertions.assertTrue(empty.getMcpMetrics().getShutdowns().isEmpty());

		McpMetricsSnapshot aggregate = McpMetricsSnapshot.builder()
				.shutdowns(Map.of(McpShutdownOutcome.CLEAN, 2L))
				.build();
		MetricsCollector.Snapshot snapshot = MetricsCollector.Snapshot.builder()
				.mcpMetrics(aggregate)
				.build();

		Assertions.assertSame(aggregate, snapshot.getMcpMetrics());
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
	}

	@Test
	public void everyMetricsEventVariantIsConstructibleAndSemanticallyTyped() {
		String endpointPath = "/mcp";
		String method = "tools/call";
		Duration duration = Duration.ofMillis(25);
		List<McpMetricsEvent> events = List.of(
				new McpMetricsEvent.ServerStarted(),
				new McpMetricsEvent.ConnectionAccepted(),
				new McpMetricsEvent.ConnectionRejected(),
				new McpMetricsEvent.RequestAccepted(),
				new McpMetricsEvent.RequestRejected(),
				new McpMetricsEvent.RequestStarted(endpointPath, method),
				new McpMetricsEvent.RequestFinished(endpointPath, method,
						McpRequestOutcome.COMPLETE, duration),
				new McpMetricsEvent.RequestStreamOpened(endpointPath, method),
				new McpMetricsEvent.RequestStreamClosed(endpointPath, method,
						McpStreamTerminationReason.COMPLETED, duration),
				new McpMetricsEvent.SubscriptionOpened(endpointPath),
				new McpMetricsEvent.SubscriptionClosed(endpointPath,
						McpStreamTerminationReason.CLIENT_DISCONNECTED, duration),
				new McpMetricsEvent.CancelationSignaled(endpointPath, method),
				new McpMetricsEvent.ProgressEmitted(endpointPath, method),
				new McpMetricsEvent.KeepAliveEmitted(),
				new McpMetricsEvent.ProtocolError(-32600),
				new McpMetricsEvent.UnknownMirroredHeader(endpointPath, method),
				new McpMetricsEvent.HandlerExecutionStarted(),
				new McpMetricsEvent.HandlerExecutionFinished(),
				new McpMetricsEvent.HandlerQueued(),
				new McpMetricsEvent.HandlerDequeued(),
				new McpMetricsEvent.HandlerCapacityRejected(),
				new McpMetricsEvent.TransportFailure(
						MetricsCollector.TransportFailureReason.WRITE_ERROR),
				new McpMetricsEvent.ServerStopped(McpShutdownOutcome.CLEAN));

		Set<Class<?>> constructedTypes = events.stream()
				.map(Object::getClass)
				.collect(Collectors.toUnmodifiableSet());
		Set<Class<?>> permittedTypes = Set.copyOf(Arrays.asList(
				McpMetricsEvent.class.getPermittedSubclasses()));
		Assertions.assertEquals(permittedTypes, constructedTypes);
		Assertions.assertEquals(Integer.class,
				McpMetricsEvent.ProtocolError.class.getRecordComponents()[0]
						.getType());
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpMetricsEvent.ProtocolError(null));

		McpMetricsEvent.RequestFinished finished =
				(McpMetricsEvent.RequestFinished) events.get(6);
		Assertions.assertEquals(endpointPath, finished.endpointPath());
		Assertions.assertEquals(method, finished.jsonRpcMethod());
		Assertions.assertEquals(McpRequestOutcome.COMPLETE, finished.outcome());
		Assertions.assertEquals(duration, finished.duration());
	}

	@Test
	public void metricsEventDimensionsFailClosedWhenInvalid() {
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpMetricsEvent.RequestStarted(null, "tools/call"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpMetricsEvent.RequestStarted("", "tools/call"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpMetricsEvent.RequestStarted("/mcp", ""));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpMetricsEvent.RequestFinished("/mcp", "tools/call",
						McpRequestOutcome.COMPLETE, Duration.ofNanos(-1L)));
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpMetricsEvent.TransportFailure(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpMetricsEvent.ServerStopped(null));
	}
}
