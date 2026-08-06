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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
		Method didRecord = MetricsCollector.class.getMethod(
				"didRecordMcpMetricsEvent", McpMetricsEvent.class);
		Method getMetrics = MetricsCollector.Snapshot.class.getMethod(
				"getMcpMetrics");
		Method setMetrics = MetricsCollector.Snapshot.Builder.class.getMethod(
				"mcpMetrics", McpMetricsSnapshot.class);
		Method setUnknownMirroredHeaderNameDiagnostics =
				McpServer.Builder.class.getMethod(
						"unknownMirroredHeaderNameDiagnostics", boolean.class);

		Assertions.assertTrue(didStart.isDefault());
		Assertions.assertTrue(didFinish.isDefault());
		Assertions.assertTrue(didRecord.isDefault());
		Assertions.assertEquals(McpMetricsSnapshot.class,
				getMetrics.getReturnType());
		Assertions.assertEquals(MetricsCollector.Snapshot.Builder.class,
				setMetrics.getReturnType());
		Assertions.assertEquals(McpServer.Builder.class,
				setUnknownMirroredHeaderNameDiagnostics.getReturnType());
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
	public void provisionalVocabulariesRemainFixedAndBounded() {
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
