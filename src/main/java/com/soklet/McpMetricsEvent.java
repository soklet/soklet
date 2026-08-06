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

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * A Soklet-owned immutable semantic event delivered to a
 * {@link MetricsCollector}.
 * <p>
 * The hierarchy deliberately has no generic value, label map, or bag of
 * optional fields. Each event exposes only the dimensions meaningful for its
 * transition. Endpoint paths are finite registered declarations and method
 * values are recognized names or {@link #UNRECOGNIZED_JSON_RPC_METHOD}; no raw
 * unrecognized method, operation name, resource URI, principal, header, trace
 * data, argument, or result is a built-in metric dimension.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpMetricsEvent permits
		McpMetricsEvent.ServerStarted,
		McpMetricsEvent.ConnectionAccepted,
		McpMetricsEvent.ConnectionRejected,
		McpMetricsEvent.RequestAccepted,
		McpMetricsEvent.RequestRejected,
		McpMetricsEvent.RequestStarted,
		McpMetricsEvent.RequestFinished,
		McpMetricsEvent.RequestStreamOpened,
		McpMetricsEvent.RequestStreamClosed,
		McpMetricsEvent.SubscriptionOpened,
		McpMetricsEvent.SubscriptionClosed,
		McpMetricsEvent.CancelationSignaled,
		McpMetricsEvent.ProgressEmitted,
		McpMetricsEvent.KeepAliveEmitted,
		McpMetricsEvent.ProtocolError,
		McpMetricsEvent.UnknownMirroredHeader,
		McpMetricsEvent.HandlerExecutionStarted,
		McpMetricsEvent.HandlerExecutionFinished,
		McpMetricsEvent.HandlerQueued,
		McpMetricsEvent.HandlerDequeued,
		McpMetricsEvent.HandlerCapacityRejected,
		McpMetricsEvent.TransportFailure,
		McpMetricsEvent.ServerStopped {
	/**
	 * Fixed bounded-cardinality method dimension used for every unrecognized
	 * or attacker-supplied JSON-RPC method.
	 */
	@NonNull
	String UNRECOGNIZED_JSON_RPC_METHOD = "<unrecognized>";

	/**
	 * A listener generation started successfully.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ServerStarted() implements McpMetricsEvent {
	}

	/**
	 * A TCP connection was accepted.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ConnectionAccepted() implements McpMetricsEvent {
	}

	/**
	 * A TCP connection was rejected before request processing.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ConnectionRejected() implements McpMetricsEvent {
	}

	/**
	 * An HTTP request was accepted into the bounded protocol processor.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record RequestAccepted() implements McpMetricsEvent {
	}

	/**
	 * A request was rejected before admitted semantic handling began.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record RequestRejected() implements McpMetricsEvent {
	}

	/**
	 * An admitted semantic request started.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record RequestStarted(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod) implements McpMetricsEvent {
		/**
		 * Creates a request-started event.
		 *
		 * @param endpointPath finite registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 */
		public RequestStarted {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
		}
	}

	/**
	 * An admitted semantic request reached its client-visible terminal outcome.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @param outcome fixed terminal outcome
	 * @param duration nonnegative request duration
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record RequestFinished(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod,
			@NonNull McpRequestOutcome outcome,
			@NonNull Duration duration) implements McpMetricsEvent {
		/**
		 * Creates a request-finished event.
		 *
		 * @param endpointPath finite registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 * @param outcome fixed terminal outcome
		 * @param duration nonnegative request duration
		 */
		public RequestFinished {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
			requireNonNull(outcome);
			requireNonNegative(duration);
		}
	}

	/**
	 * A request response stream opened.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record RequestStreamOpened(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod) implements McpMetricsEvent {
		/**
		 * Creates a request-stream-opened event.
		 *
		 * @param endpointPath finite registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 */
		public RequestStreamOpened {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
		}
	}

	/**
	 * A request response stream closed.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @param reason fixed stream termination reason
	 * @param duration nonnegative stream duration
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record RequestStreamClosed(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod,
			@NonNull McpStreamTerminationReason reason,
			@NonNull Duration duration) implements McpMetricsEvent {
		/**
		 * Creates a request-stream-closed event.
		 *
		 * @param endpointPath finite registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 * @param reason fixed stream termination reason
		 * @param duration nonnegative stream duration
		 */
		public RequestStreamClosed {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
			requireNonNull(reason);
			requireNonNegative(duration);
		}
	}

	/**
	 * A resource subscription became active.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record SubscriptionOpened(@NonNull String endpointPath)
			implements McpMetricsEvent {
		/**
		 * Creates a subscription-opened event.
		 *
		 * @param endpointPath finite registered endpoint-path declaration
		 */
		public SubscriptionOpened {
			requireEndpointPath(endpointPath);
		}
	}

	/**
	 * A resource subscription terminated.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param reason fixed stream termination reason
	 * @param duration nonnegative subscription duration
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record SubscriptionClosed(@NonNull String endpointPath,
			@NonNull McpStreamTerminationReason reason,
			@NonNull Duration duration) implements McpMetricsEvent {
		/**
		 * Creates a subscription-closed event.
		 *
		 * @param endpointPath finite registered endpoint-path declaration
		 * @param reason fixed stream termination reason
		 * @param duration nonnegative subscription duration
		 */
		public SubscriptionClosed {
			requireEndpointPath(endpointPath);
			requireNonNull(reason);
			requireNonNegative(duration);
		}
	}

	/**
	 * Cooperative request cancelation was signaled.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record CancelationSignaled(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod) implements McpMetricsEvent {
		/**
		 * Creates a cancelation-signaled event.
		 *
		 * @param endpointPath finite registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 */
		public CancelationSignaled {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
		}
	}

	/**
	 * A progress notification was accepted for delivery.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ProgressEmitted(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod) implements McpMetricsEvent {
		/**
		 * Creates a progress-emitted event.
		 *
		 * @param endpointPath finite registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 */
		public ProgressEmitted {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
		}
	}

	/**
	 * A keep-alive comment was accepted for delivery.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record KeepAliveEmitted() implements McpMetricsEvent {
	}

	/**
	 * A fixed JSON-RPC or MCP protocol error was produced.
	 *
	 * @param code fixed JSON-RPC error code
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ProtocolError(int code) implements McpMetricsEvent {
	}

	/**
	 * An unknown mirrored-header occurrence was counted without its name.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record UnknownMirroredHeader(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod) implements McpMetricsEvent {
		/**
		 * Creates an unknown-mirrored-header event.
		 *
		 * @param endpointPath finite registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 */
		public UnknownMirroredHeader {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
		}
	}

	/**
	 * An application handler acquired an execution slot.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record HandlerExecutionStarted() implements McpMetricsEvent {
	}

	/**
	 * An application handler released its execution slot after actual exit.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record HandlerExecutionFinished() implements McpMetricsEvent {
	}

	/**
	 * An application handler request entered the bounded queue.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record HandlerQueued() implements McpMetricsEvent {
	}

	/**
	 * An application handler request left the bounded queue.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record HandlerDequeued() implements McpMetricsEvent {
	}

	/**
	 * Handler dispatch was rejected because the bounded queue was full.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record HandlerCapacityRejected() implements McpMetricsEvent {
	}

	/**
	 * The dedicated MCP transport recorded a bounded failure reason.
	 *
	 * @param reason fixed low-level transport failure reason
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record TransportFailure(
			MetricsCollector.@NonNull TransportFailureReason reason)
			implements McpMetricsEvent {
		/**
		 * Creates a transport-failure event.
		 *
		 * @param reason fixed low-level transport failure reason
		 */
		public TransportFailure {
			requireNonNull(reason);
		}
	}

	/**
	 * A real listener stop completed with one fixed outcome.
	 *
	 * @param outcome fixed listener shutdown outcome
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ServerStopped(@NonNull McpShutdownOutcome outcome)
			implements McpMetricsEvent {
		/**
		 * Creates a server-stopped event.
		 *
		 * @param outcome fixed listener shutdown outcome
		 */
		public ServerStopped {
			requireNonNull(outcome);
		}
	}

	private static void requireRoutedDimensions(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod) {
		requireEndpointPath(endpointPath);
		if (requireNonNull(jsonRpcMethod).isEmpty())
			throw new IllegalArgumentException(
					"JSON-RPC method must not be empty.");
	}

	private static void requireEndpointPath(@NonNull String endpointPath) {
		if (requireNonNull(endpointPath).isEmpty())
			throw new IllegalArgumentException(
					"Endpoint path must not be empty.");
	}

	private static void requireNonNegative(@NonNull Duration duration) {
		if (requireNonNull(duration).isNegative())
			throw new IllegalArgumentException("Duration must not be negative.");
	}
}
