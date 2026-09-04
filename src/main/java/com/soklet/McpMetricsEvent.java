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

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * A Soklet-owned immutable semantic event delivered to a
 * {@link MetricsCollector}.
 * <p>
 * The hierarchy deliberately has no generic value, label map, or bag of
 * optional fields. Each event exposes only the dimensions meaningful for its
 * transition. Framework-produced endpoint paths are finite registered
 * declarations, and framework-produced method values are recognized names or
 * {@link #UNRECOGNIZED_JSON_RPC_METHOD}. Public event factories enforce only
 * the documented value shape; applications construct events through these
 * factories and own the confidentiality and cardinality of supplied values. No raw
 * unrecognized method, operation name, resource URI, principal, header, trace
 * data, argument, or result is a framework-produced built-in metric dimension.
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

	/** @return event indicating that a listener generation started */
	@NonNull
	static ServerStarted serverStarted() {
		return ServerStarted.INSTANCE;
	}

	/** @return event indicating that a TCP connection was accepted */
	@NonNull
	static ConnectionAccepted connectionAccepted() {
		return ConnectionAccepted.INSTANCE;
	}

	/** @return event indicating that a TCP connection was rejected */
	@NonNull
	static ConnectionRejected connectionRejected() {
		return ConnectionRejected.INSTANCE;
	}

	/** @return event indicating that an HTTP request was accepted */
	@NonNull
	static RequestAccepted requestAccepted() {
		return RequestAccepted.INSTANCE;
	}

	/** @return event indicating that a request was rejected */
	@NonNull
	static RequestRejected requestRejected() {
		return RequestRejected.INSTANCE;
	}

	/**
	 * Creates an event indicating that an admitted semantic request started.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @return request-started event
	 */
	@NonNull
	static RequestStarted requestStarted(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod) {
		return new RequestStarted(endpointPath, jsonRpcMethod);
	}

	/**
	 * Creates an event indicating that an admitted semantic request finished.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @param outcome fixed terminal outcome
	 * @param duration nonnegative request duration
	 * @return request-finished event
	 */
	@NonNull
	static RequestFinished requestFinished(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod, @NonNull McpRequestOutcome outcome,
			@NonNull Duration duration) {
		return new RequestFinished(endpointPath, jsonRpcMethod, outcome,
				duration);
	}

	/**
	 * Creates an event indicating that a request response stream opened.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @return request-stream-opened event
	 */
	@NonNull
	static RequestStreamOpened requestStreamOpened(
			@NonNull String endpointPath, @NonNull String jsonRpcMethod) {
		return new RequestStreamOpened(endpointPath, jsonRpcMethod);
	}

	/**
	 * Creates an event indicating that a request response stream closed.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @param reason fixed stream termination reason
	 * @param duration nonnegative stream duration
	 * @return request-stream-closed event
	 */
	@NonNull
	static RequestStreamClosed requestStreamClosed(
			@NonNull String endpointPath, @NonNull String jsonRpcMethod,
			@NonNull McpStreamTerminationReason reason,
			@NonNull Duration duration) {
		return new RequestStreamClosed(endpointPath, jsonRpcMethod, reason,
				duration);
	}

	/**
	 * Creates an event indicating that a resource subscription became active.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @return subscription-opened event
	 */
	@NonNull
	static SubscriptionOpened subscriptionOpened(@NonNull String endpointPath) {
		return new SubscriptionOpened(endpointPath);
	}

	/**
	 * Creates an event indicating that a resource subscription terminated.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param reason fixed stream termination reason
	 * @param duration nonnegative subscription duration
	 * @return subscription-closed event
	 */
	@NonNull
	static SubscriptionClosed subscriptionClosed(@NonNull String endpointPath,
			@NonNull McpStreamTerminationReason reason,
			@NonNull Duration duration) {
		return new SubscriptionClosed(endpointPath, reason, duration);
	}

	/**
	 * Creates an event indicating that cooperative cancelation was signaled.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @return cancelation-signaled event
	 */
	@NonNull
	static CancelationSignaled cancelationSignaled(
			@NonNull String endpointPath, @NonNull String jsonRpcMethod) {
		return new CancelationSignaled(endpointPath, jsonRpcMethod);
	}

	/**
	 * Creates an event indicating that a progress notification was emitted.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @return progress-emitted event
	 */
	@NonNull
	static ProgressEmitted progressEmitted(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod) {
		return new ProgressEmitted(endpointPath, jsonRpcMethod);
	}

	/** @return event indicating that a keep-alive was emitted */
	@NonNull
	static KeepAliveEmitted keepAliveEmitted() {
		return KeepAliveEmitted.INSTANCE;
	}

	/**
	 * Creates an event indicating that a fixed protocol error was produced.
	 *
	 * @param code fixed JSON-RPC error code
	 * @return protocol-error event
	 */
	@NonNull
	static ProtocolError protocolError(@NonNull Integer code) {
		return new ProtocolError(code);
	}

	/**
	 * Creates an event indicating that an unknown mirrored header was counted.
	 *
	 * @param endpointPath finite registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @return unknown-mirrored-header event
	 */
	@NonNull
	static UnknownMirroredHeader unknownMirroredHeader(
			@NonNull String endpointPath, @NonNull String jsonRpcMethod) {
		return new UnknownMirroredHeader(endpointPath, jsonRpcMethod);
	}

	/** @return event indicating that handler execution started */
	@NonNull
	static HandlerExecutionStarted handlerExecutionStarted() {
		return HandlerExecutionStarted.INSTANCE;
	}

	/** @return event indicating that handler execution finished */
	@NonNull
	static HandlerExecutionFinished handlerExecutionFinished() {
		return HandlerExecutionFinished.INSTANCE;
	}

	/** @return event indicating that a handler request entered the queue */
	@NonNull
	static HandlerQueued handlerQueued() {
		return HandlerQueued.INSTANCE;
	}

	/** @return event indicating that a handler request left the queue */
	@NonNull
	static HandlerDequeued handlerDequeued() {
		return HandlerDequeued.INSTANCE;
	}

	/** @return event indicating that handler dispatch was rejected */
	@NonNull
	static HandlerCapacityRejected handlerCapacityRejected() {
		return HandlerCapacityRejected.INSTANCE;
	}

	/**
	 * Creates an event indicating that the transport recorded a failure.
	 *
	 * @param reason fixed low-level transport failure reason
	 * @return transport-failure event
	 */
	@NonNull
	static TransportFailure transportFailure(
			MetricsCollector.@NonNull TransportFailureReason reason) {
		return new TransportFailure(reason);
	}

	/**
	 * Creates an event indicating that a real listener stop completed.
	 *
	 * @param shutdownComponentDisposition fixed listener shutdown-component
	 *                                     disposition
	 * @return server-stopped event
	 */
	@NonNull
	static ServerStopped serverStopped(
			@NonNull ShutdownComponentDisposition shutdownComponentDisposition) {
		return new ServerStopped(shutdownComponentDisposition);
	}

	/**
	 * A listener generation started successfully.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class ServerStarted implements McpMetricsEvent {
		@NonNull
		private static final ServerStarted INSTANCE = new ServerStarted();

		private ServerStarted() {
		}

		/** @return whether this object is another server-started event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "ServerStarted{}";
		}
	}

	/**
	 * A successfully accepted TCP connection was admitted within the configured connection-capacity bound.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class ConnectionAccepted implements McpMetricsEvent {
		@NonNull
		private static final ConnectionAccepted INSTANCE =
				new ConnectionAccepted();

		private ConnectionAccepted() {
		}

		/** @return whether this object is another connection-accepted event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "ConnectionAccepted{}";
		}
	}

	/**
	 * A successfully accepted TCP connection was rejected because the configured maximum-connection capacity was full.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class ConnectionRejected implements McpMetricsEvent {
		@NonNull
		private static final ConnectionRejected INSTANCE =
				new ConnectionRejected();

		private ConnectionRejected() {
		}

		/** @return whether this object is another connection-rejected event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "ConnectionRejected{}";
		}
	}

	/**
	 * An HTTP request was accepted into the bounded protocol processor.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class RequestAccepted implements McpMetricsEvent {
		@NonNull
		private static final RequestAccepted INSTANCE = new RequestAccepted();

		private RequestAccepted() {
		}

		/** @return whether this object is another request-accepted event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "RequestAccepted{}";
		}
	}

	/**
	 * A request was rejected before admitted semantic handling began.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class RequestRejected implements McpMetricsEvent {
		@NonNull
		private static final RequestRejected INSTANCE = new RequestRejected();

		private RequestRejected() {
		}

		/** @return whether this object is another request-rejected event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "RequestRejected{}";
		}
	}

	/**
	 * An admitted semantic request started.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class RequestStarted implements McpMetricsEvent {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;

		private RequestStarted(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod) {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
		}

		/** @return finite registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return whether this object contains the same dimensions */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			RequestStarted that = (RequestStarted) other;
			return this.endpointPath.equals(that.endpointPath)
					&& this.jsonRpcMethod.equals(that.jsonRpcMethod);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 31 * this.endpointPath.hashCode()
					+ this.jsonRpcMethod.hashCode();
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "RequestStarted{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>}";
		}
	}

	/**
	 * An admitted semantic request reached its client-visible terminal outcome.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class RequestFinished implements McpMetricsEvent {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;
		@NonNull
		private final McpRequestOutcome outcome;
		@NonNull
		private final Duration duration;

		private RequestFinished(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod,
				@NonNull McpRequestOutcome outcome,
				@NonNull Duration duration) {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
			this.outcome = requireNonNull(outcome);
			requireNonNegative(duration);
			this.duration = duration;
		}

		/** @return finite registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return fixed terminal outcome */
		@NonNull
		public McpRequestOutcome getOutcome() {
			return this.outcome;
		}

		/** @return nonnegative request duration */
		@NonNull
		public Duration getDuration() {
			return this.duration;
		}

		/** @return whether this object contains the same event values */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			RequestFinished that = (RequestFinished) other;
			return this.endpointPath.equals(that.endpointPath)
					&& this.jsonRpcMethod.equals(that.jsonRpcMethod)
					&& this.outcome.equals(that.outcome)
					&& this.duration.equals(that.duration);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			int result = this.endpointPath.hashCode();
			result = 31 * result + this.jsonRpcMethod.hashCode();
			result = 31 * result + this.outcome.hashCode();
			return 31 * result + this.duration.hashCode();
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "RequestFinished{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>, outcome=" + this.outcome
					+ ", duration=" + this.duration + "}";
		}
	}

	/**
	 * A request response stream opened.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class RequestStreamOpened implements McpMetricsEvent {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;

		private RequestStreamOpened(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod) {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
		}

		/** @return finite registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return whether this object contains the same dimensions */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			RequestStreamOpened that = (RequestStreamOpened) other;
			return this.endpointPath.equals(that.endpointPath)
					&& this.jsonRpcMethod.equals(that.jsonRpcMethod);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 31 * this.endpointPath.hashCode()
					+ this.jsonRpcMethod.hashCode();
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "RequestStreamOpened{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>}";
		}
	}

	/**
	 * A request response stream closed.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class RequestStreamClosed implements McpMetricsEvent {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;
		@NonNull
		private final McpStreamTerminationReason reason;
		@NonNull
		private final Duration duration;

		private RequestStreamClosed(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod,
				@NonNull McpStreamTerminationReason reason,
				@NonNull Duration duration) {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
			this.reason = requireNonNull(reason);
			requireNonNegative(duration);
			this.duration = duration;
		}

		/** @return finite registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return fixed stream termination reason */
		@NonNull
		public McpStreamTerminationReason getReason() {
			return this.reason;
		}

		/** @return nonnegative stream duration */
		@NonNull
		public Duration getDuration() {
			return this.duration;
		}

		/** @return whether this object contains the same event values */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			RequestStreamClosed that = (RequestStreamClosed) other;
			return this.endpointPath.equals(that.endpointPath)
					&& this.jsonRpcMethod.equals(that.jsonRpcMethod)
					&& this.reason.equals(that.reason)
					&& this.duration.equals(that.duration);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			int result = this.endpointPath.hashCode();
			result = 31 * result + this.jsonRpcMethod.hashCode();
			result = 31 * result + this.reason.hashCode();
			return 31 * result + this.duration.hashCode();
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "RequestStreamClosed{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>, reason=" + this.reason
					+ ", duration=" + this.duration + "}";
		}
	}

	/**
	 * A resource subscription became active.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class SubscriptionOpened implements McpMetricsEvent {
		@NonNull
		private final String endpointPath;

		private SubscriptionOpened(@NonNull String endpointPath) {
			requireEndpointPath(endpointPath);
			this.endpointPath = endpointPath;
		}

		/** @return finite registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return whether this object contains the same endpoint path */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			SubscriptionOpened that = (SubscriptionOpened) other;
			return this.endpointPath.equals(that.endpointPath);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return this.endpointPath.hashCode();
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "SubscriptionOpened{endpointPath=<redacted>}";
		}
	}

	/**
	 * A resource subscription terminated.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class SubscriptionClosed implements McpMetricsEvent {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final McpStreamTerminationReason reason;
		@NonNull
		private final Duration duration;

		private SubscriptionClosed(@NonNull String endpointPath,
				@NonNull McpStreamTerminationReason reason,
				@NonNull Duration duration) {
			requireEndpointPath(endpointPath);
			this.endpointPath = endpointPath;
			this.reason = requireNonNull(reason);
			requireNonNegative(duration);
			this.duration = duration;
		}

		/** @return finite registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return fixed stream termination reason */
		@NonNull
		public McpStreamTerminationReason getReason() {
			return this.reason;
		}

		/** @return nonnegative subscription duration */
		@NonNull
		public Duration getDuration() {
			return this.duration;
		}

		/** @return whether this object contains the same event values */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			SubscriptionClosed that = (SubscriptionClosed) other;
			return this.endpointPath.equals(that.endpointPath)
					&& this.reason.equals(that.reason)
					&& this.duration.equals(that.duration);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			int result = this.endpointPath.hashCode();
			result = 31 * result + this.reason.hashCode();
			return 31 * result + this.duration.hashCode();
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "SubscriptionClosed{endpointPath=<redacted>, reason="
					+ this.reason + ", duration=" + this.duration + "}";
		}
	}

	/**
	 * Cooperative request cancelation was signaled.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class CancelationSignaled implements McpMetricsEvent {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;

		private CancelationSignaled(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod) {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
		}

		/** @return finite registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return whether this object contains the same dimensions */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			CancelationSignaled that = (CancelationSignaled) other;
			return this.endpointPath.equals(that.endpointPath)
					&& this.jsonRpcMethod.equals(that.jsonRpcMethod);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 31 * this.endpointPath.hashCode()
					+ this.jsonRpcMethod.hashCode();
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "CancelationSignaled{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>}";
		}
	}

	/**
	 * A progress notification was accepted for delivery.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class ProgressEmitted implements McpMetricsEvent {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;

		private ProgressEmitted(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod) {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
		}

		/** @return finite registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return whether this object contains the same dimensions */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			ProgressEmitted that = (ProgressEmitted) other;
			return this.endpointPath.equals(that.endpointPath)
					&& this.jsonRpcMethod.equals(that.jsonRpcMethod);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 31 * this.endpointPath.hashCode()
					+ this.jsonRpcMethod.hashCode();
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "ProgressEmitted{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>}";
		}
	}

	/**
	 * A keep-alive comment was accepted for delivery.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class KeepAliveEmitted implements McpMetricsEvent {
		@NonNull
		private static final KeepAliveEmitted INSTANCE =
				new KeepAliveEmitted();

		private KeepAliveEmitted() {
		}

		/** @return whether this object is another keep-alive-emitted event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "KeepAliveEmitted{}";
		}
	}

	/**
	 * A fixed JSON-RPC or MCP protocol error was produced.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class ProtocolError implements McpMetricsEvent {
		@NonNull
		private final Integer code;

		private ProtocolError(@NonNull Integer code) {
			this.code = requireNonNull(code);
		}

		/** @return fixed JSON-RPC error code */
		@NonNull
		public Integer getCode() {
			return this.code;
		}

		/** @return whether this object contains the same protocol error code */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			ProtocolError that = (ProtocolError) other;
			return this.code.equals(that.code);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return this.code.hashCode();
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "ProtocolError{code=" + this.code + "}";
		}
	}

	/**
	 * An unknown mirrored-header occurrence was counted without its name.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class UnknownMirroredHeader implements McpMetricsEvent {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;

		private UnknownMirroredHeader(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod) {
			requireRoutedDimensions(endpointPath, jsonRpcMethod);
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
		}

		/** @return finite registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return whether this object contains the same dimensions */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			UnknownMirroredHeader that = (UnknownMirroredHeader) other;
			return this.endpointPath.equals(that.endpointPath)
					&& this.jsonRpcMethod.equals(that.jsonRpcMethod);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 31 * this.endpointPath.hashCode()
					+ this.jsonRpcMethod.hashCode();
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "UnknownMirroredHeader{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>}";
		}
	}

	/**
	 * An application handler acquired an execution slot.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class HandlerExecutionStarted implements McpMetricsEvent {
		@NonNull
		private static final HandlerExecutionStarted INSTANCE =
				new HandlerExecutionStarted();

		private HandlerExecutionStarted() {
		}

		/** @return whether this object is another handler-started event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "HandlerExecutionStarted{}";
		}
	}

	/**
	 * A reserved application-handler execution slot was released after actual
	 * handler exit or failed executor submission.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class HandlerExecutionFinished implements McpMetricsEvent {
		@NonNull
		private static final HandlerExecutionFinished INSTANCE =
				new HandlerExecutionFinished();

		private HandlerExecutionFinished() {
		}

		/** @return whether this object is another handler-finished event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "HandlerExecutionFinished{}";
		}
	}

	/**
	 * An application handler request entered the bounded queue.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class HandlerQueued implements McpMetricsEvent {
		@NonNull
		private static final HandlerQueued INSTANCE = new HandlerQueued();

		private HandlerQueued() {
		}

		/** @return whether this object is another handler-queued event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "HandlerQueued{}";
		}
	}

	/**
	 * An application handler request left the bounded queue.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class HandlerDequeued implements McpMetricsEvent {
		@NonNull
		private static final HandlerDequeued INSTANCE = new HandlerDequeued();

		private HandlerDequeued() {
		}

		/** @return whether this object is another handler-dequeued event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "HandlerDequeued{}";
		}
	}

	/**
	 * Handler dispatch was rejected because the bounded queue was full.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class HandlerCapacityRejected implements McpMetricsEvent {
		@NonNull
		private static final HandlerCapacityRejected INSTANCE =
				new HandlerCapacityRejected();

		private HandlerCapacityRejected() {
		}

		/** @return whether this object is another capacity-rejected event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other != null && getClass() == other.getClass();
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "HandlerCapacityRejected{}";
		}
	}

	/**
	 * The dedicated MCP transport recorded a bounded failure reason.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class TransportFailure implements McpMetricsEvent {
		private final MetricsCollector.@NonNull TransportFailureReason reason;

		private TransportFailure(
				MetricsCollector.@NonNull TransportFailureReason reason) {
			this.reason = requireNonNull(reason);
		}

		/** @return fixed low-level transport failure reason */
		public MetricsCollector.@NonNull TransportFailureReason getReason() {
			return this.reason;
		}

		/** @return whether this object contains the same failure reason */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			TransportFailure that = (TransportFailure) other;
			return this.reason.equals(that.reason);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return this.reason.hashCode();
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "TransportFailure{reason=" + this.reason + "}";
		}
	}

	/**
	 * A real listener stop completed with one fixed outcome.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class ServerStopped implements McpMetricsEvent {
		@NonNull
		private final ShutdownComponentDisposition shutdownComponentDisposition;

		private ServerStopped(
				@NonNull ShutdownComponentDisposition shutdownComponentDisposition) {
			this.shutdownComponentDisposition =
					requireNonNull(shutdownComponentDisposition);
		}

		/** @return fixed listener shutdown-component disposition */
		@NonNull
		public ShutdownComponentDisposition getShutdownComponentDisposition() {
			return this.shutdownComponentDisposition;
		}

		/** @return whether this object contains the same shutdown disposition */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (other == null || getClass() != other.getClass())
				return false;
			ServerStopped that = (ServerStopped) other;
			return this.shutdownComponentDisposition.equals(
					that.shutdownComponentDisposition);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return this.shutdownComponentDisposition.hashCode();
		}

		/** @return diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "ServerStopped{shutdownComponentDisposition="
					+ this.shutdownComponentDisposition + "}";
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
