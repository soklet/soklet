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

import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import com.soklet.internal.streaming.ManagedSseLifecycle;
import com.soklet.Soklet.MockSseUnicaster;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Objects;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Sealed interface used by {@link Simulator#performSseRequest(Request)} during integration tests, which encapsulates the 3 logical outcomes for SSE connections: accepted handshake, rejected handshake, and general request failure.
 * <p>
 * See <a href="https://www.soklet.com/docs/testing#integration-testing">https://www.soklet.com/docs/testing#integration-testing</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface SseRequestResult permits SseRequestResult.HandshakeAccepted, SseRequestResult.HandshakeRejected, SseRequestResult.RequestFailed {
	/**
	 * Represents the result of an SSE accepted handshake (connection stays open) when simulated by {@link Simulator#performSseRequest(Request)}.
	 * <p>
	 * The {@link #registerEventConsumer(Consumer)} and {@link #registerCommentConsumer(Consumer)} methods can be used to "listen" for Server-Sent Events and Comments, respectively.
	 * <p>
	 * The data provided when the handshake was accepted is available via {@link #getSseHandshakeResult()}, and the final data sent to the client is available via {@link #getHttpRequestResult()}.
	 */
	@ThreadSafe
	final class HandshakeAccepted implements SseRequestResult, AutoCloseable {
		private final SseHandshakeResult.Accepted sseHandshakeResult;
		private final Request request;
		private final HttpRequestResult requestResult;
		private final Soklet.MockSseServer server;
		private final Object lock = new Object();
		private final Channel<SseEvent> events = new Channel<>();
		private final Channel<SseComment> comments = new Channel<>();
		private final Consumer<SseEvent> broadcastEvents = event -> broadcast(this.events, event);
		private final Consumer<SseComment> broadcastComments = comment -> broadcast(this.comments, comment);
		private final ManagedSseLifecycle lifecycle;
		private final MockSseUnicaster unicaster;
		private boolean active;

		HandshakeAccepted(SseHandshakeResult.@NonNull Accepted sseHandshakeResult,
				@NonNull Request request, @NonNull HttpRequestResult requestResult,
				Soklet.@NonNull MockSseServer server,
				StreamLifecycleCoordinator.@NonNull Reservation reservation) {
			this.sseHandshakeResult = requireNonNull(sseHandshakeResult);
			this.request = requireNonNull(request);
			this.requestResult = requireNonNull(requestResult);
			this.server = requireNonNull(server);
			this.lifecycle = new ManagedSseLifecycle(requireNonNull(reservation), this::releaseConnection);
			this.unicaster = new MockSseUnicaster(request,
					event -> enqueue(this.events, event, false), comment -> enqueue(this.comments, comment, false));
		}

		boolean initialize(@Nullable SseClientInitializer initializer) throws Exception {
			if (!this.lifecycle.executeInitializer(() -> {
				this.unicaster.beginInitializer();
				try {
					if (initializer != null)
						initializer.initialize(this.unicaster);
				} finally {
					this.unicaster.finishInitializer();
				}
			}))
				return false;
			try {
				return this.lifecycle.whileOpen(() -> {
					synchronized (this.lock) {
						this.server.registerConnection(this, this.request.getResourcePath(),
								this.broadcastEvents, this.broadcastComments,
								this.sseHandshakeResult.getClientContext().orElse(null));
						this.active = true;
						return true;
					}
				});
			} catch (IllegalStateException closedBeforeActivation) {
				if (this.lifecycle.isOpen())
					throw closedBeforeActivation;
				return false;
			}
		}

		/**
		 * Disconnects this simulated client. Repeated calls are harmless.
		 * Handshake metadata remains available.
		 */
		@Override
		public void close() {
			this.lifecycle.terminate(StreamTerminationReason.CLIENT_DISCONNECTED, null);
		}

		/**
		 * Registers the sole event consumer and delivers buffered events in order.
		 * @throws IllegalStateException if a consumer is already registered or this connection has terminated
		 */
		public void registerEventConsumer(@NonNull Consumer<@NonNull SseEvent> eventConsumer) {
			register(this.events, requireNonNull(eventConsumer));
		}

		/**
		 * Registers the sole comment consumer and delivers buffered comments in order.
		 * @throws IllegalStateException if a consumer is already registered or this connection has terminated
		 */
		public void registerCommentConsumer(@NonNull Consumer<@NonNull SseComment> commentConsumer) {
			register(this.comments, requireNonNull(commentConsumer));
		}

		private <T> void register(Channel<T> channel, Consumer<T> consumer) {
			boolean drain = this.lifecycle.whileOpen(() -> {
				synchronized (this.lock) {
					if (channel.consumer != null)
						throw new IllegalStateException("This simulated SSE connection already has a consumer of this type");
					channel.consumer = consumer;
					return claimDrain(channel);
				}
			});
			if (drain)
				drain(channel);
		}

		private <T> void broadcast(Channel<T> channel, T payload) {
			try {
				enqueue(channel, payload, true);
			} catch (IllegalStateException failure) {
				// A broadcaster may have snapshotted this connection just before it
				// disconnected. The terminal hook has already released its state.
				if (this.lifecycle.isOpen())
					throw failure;
			}
		}

		private <T> void enqueue(Channel<T> channel, T payload, boolean broadcast) {
			requireNonNull(payload);
			boolean drain;
			try {
				drain = this.lifecycle.whileOpen(() -> {
					synchronized (this.lock) {
						if (this.events.pending.size() + this.comments.pending.size() >= this.server.connectionQueueCapacity)
							throw new QueueCapacityExceededException();
						channel.pending.addLast(new Pending<>(payload, broadcast));
						return claimDrain(channel);
					}
				});
			} catch (QueueCapacityExceededException overflow) {
				this.lifecycle.terminate(StreamTerminationReason.BACKPRESSURE, overflow);
				throw overflow;
			}
			if (drain)
				drain(channel);
		}

		private <T> boolean claimDrain(Channel<T> channel) {
			if (channel.draining || channel.consumer == null || channel.pending.isEmpty())
				return false;
			channel.draining = true;
			return true;
		}

		private <T> void drain(Channel<T> channel) {
			StreamLifecycleCoordinator.Reservation.Work work = this.lifecycle.retainWork();
			if (work == null)
				return;
			try (work) {
				for (;;) {
					Delivery<T> delivery;
					try {
						delivery = this.lifecycle.whileOpen(() -> {
							synchronized (this.lock) {
								if (channel.consumer == null || channel.pending.isEmpty()) {
									channel.draining = false;
									return null;
								}
								Pending<T> pending = channel.pending.removeFirst();
								return new Delivery<>(channel.consumer, pending.payload, pending.broadcast);
							}
						});
					} catch (IllegalStateException terminated) {
						return;
					}
					if (delivery == null)
						return;
					try {
						delivery.consumer.accept(delivery.payload);
					} catch (Throwable failure) {
						handleConsumerError(failure, delivery.broadcast);
					}
				}
			}
		}

		private void releaseConnection() {
			synchronized (this.lock) {
				this.events.clear();
				this.comments.clear();
				if (this.active) {
					this.server.unregisterConnection(this, this.request.getResourcePath(),
							this.broadcastEvents, this.broadcastComments);
					this.active = false;
				}
			}
		}

		private void handleConsumerError(Throwable throwable, boolean broadcast) {
			Consumer<Throwable> handler = (broadcast ? this.server.getBroadcastErrorHandler()
					: this.server.getUnicastErrorHandler()).get();
			if (handler != null) {
				try {
					handler.accept(throwable);
					return;
				} catch (Throwable ignored) {
					// Fall through to the lifecycle log sink.
				}
			}
			this.server.safelyLog(LogEvent.with(LogEventType.SSE_SERVER_INTERNAL_ERROR,
					"SSE simulator consumer failed").throwable(throwable).build());
		}

		/** Returns the original accepted handshake, including its headers and cookies. */
		public SseHandshakeResult.@NonNull Accepted getSseHandshakeResult() { return this.sseHandshakeResult; }

		/** Returns the initial handshake response; remains available after {@link #close()}. */
		public @NonNull HttpRequestResult getHttpRequestResult() { return this.requestResult; }

		@Override public @NonNull String toString() {
			return format("%s{sseHandshakeResult=%s}", HandshakeAccepted.class.getSimpleName(), this.sseHandshakeResult);
		}

		private static final class Channel<T> {
			private final java.util.ArrayDeque<Pending<T>> pending = new java.util.ArrayDeque<>();
			private @Nullable Consumer<T> consumer;
			private boolean draining;
			private void clear() { this.pending.clear(); this.consumer = null; this.draining = false; }
		}

		private record Pending<T>(T payload, boolean broadcast) {}
		private record Delivery<T>(Consumer<T> consumer, T payload, boolean broadcast) {}
		private static final class QueueCapacityExceededException extends IllegalStateException {
			private QueueCapacityExceededException() { super("The simulated SSE connection queue is full"); }
		}
	}
	/**
	 * Represents the result of an SSE rejected handshake (explicit rejection; connection closed) when simulated by {@link Simulator#performSseRequest(Request)}.
	 * <p>
	 * The data provided when the handshake was rejected is available via {@link #getSseHandshakeResult()}, and the final data sent to the client is available via {@link #getHttpRequestResult()}.
	 */
	@ThreadSafe
	final class HandshakeRejected implements SseRequestResult {
		private final SseHandshakeResult.@NonNull Rejected sseHandshakeResult;
		@NonNull
		private final HttpRequestResult requestResult;

		HandshakeRejected(SseHandshakeResult.@NonNull Rejected sseHandshakeResult,
											@NonNull HttpRequestResult requestResult) {
			requireNonNull(sseHandshakeResult);
			requireNonNull(requestResult);

			this.sseHandshakeResult = sseHandshakeResult;
			this.requestResult = requestResult;
		}

		/**
		 * Gets the data provided when the handshake was explicitly rejected by the {@link com.soklet.annotation.SseEventSource}-annotated <em>Resource Method</em>.
		 *
		 * @return the data provided when the handshake was rejected
		 */
		public SseHandshakeResult.@NonNull Rejected getSseHandshakeResult() {
			return this.sseHandshakeResult;
		}

		/**
		 * The result of the handshake, as written back to the client (the connection is then closed).
		 *
		 * @return the result of this request
		 */
		@NonNull
		public HttpRequestResult getHttpRequestResult() {
			return this.requestResult;
		}

		@Override
		@NonNull
		public String toString() {
			return format("%s{sseHandshakeResult=%s, requestResult=%s}", HandshakeRejected.class.getSimpleName(), getSseHandshakeResult(), getHttpRequestResult());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof HandshakeRejected handshakeRejected))
				return false;

			return Objects.equals(getSseHandshakeResult(), handshakeRejected.getSseHandshakeResult())
					&& Objects.equals(getHttpRequestResult(), handshakeRejected.getHttpRequestResult());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getSseHandshakeResult(), getHttpRequestResult());
		}
	}

	/**
	 * Represents the result of an SSE request failure (implicit rejection, e.g. an exception occurred; connection closed) when simulated by {@link Simulator#performSseRequest(Request)}.
	 * <p>
	 * The final data sent to the client is available via {@link #getHttpRequestResult()}.
	 */
	@ThreadSafe
	final class RequestFailed implements SseRequestResult {
		@NonNull
		private final HttpRequestResult requestResult;

		RequestFailed(@NonNull HttpRequestResult requestResult) {
			requireNonNull(requestResult);
			this.requestResult = requestResult;
		}

		/**
		 * The result of the handshake, as written back to the client (the connection is then closed).
		 *
		 * @return the result of this request
		 */
		@NonNull
		public HttpRequestResult getHttpRequestResult() {
			return this.requestResult;
		}

		@Override
		@NonNull
		public String toString() {
			return format("%s{requestResult=%s}", RequestFailed.class.getSimpleName(), getHttpRequestResult());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof RequestFailed requestFailed))
				return false;

			return Objects.equals(getHttpRequestResult(), requestFailed.getHttpRequestResult());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getHttpRequestResult());
		}
	}
}
