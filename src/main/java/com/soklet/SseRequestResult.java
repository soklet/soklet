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

import com.soklet.Soklet.DefaultSimulator;
import com.soklet.Soklet.MockSseUnicaster;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
public sealed interface SseRequestResult permits SseRequestResult.HandshakeAccepted, SseRequestResult.HandshakeRejected, SseRequestResult.RequestFailed {
	/**
	 * Represents the result of an SSE accepted handshake (connection stays open) when simulated by {@link Simulator#performSseRequest(Request)}.
	 * <p>
	 * The {@link #registerEventConsumer(Consumer)} and {@link #registerCommentConsumer(Consumer)} methods can be used to "listen" for Server-Sent Events and Comments, respectively.
	 * <p>
	 * The data provided when the handshake was accepted is available via {@link #getSseHandshakeResult()}, and the final data sent to the client is available via {@link #getRequestResult()}.
	 */
	@ThreadSafe
	final class HandshakeAccepted implements SseRequestResult {
		private final SseHandshakeResult.@NonNull Accepted sseHandshakeResult;
		@NonNull
		private final ResourcePath resourcePath;
		@NonNull
		private final RequestResult requestResult;
		@NonNull
		private final DefaultSimulator simulator;
		@NonNull
		private final AtomicReference<@Nullable Consumer<Throwable>> unicastErrorHandler;
		@NonNull
		private List<@NonNull SseEvent> clientInitializerEvents;
		@NonNull
		private List<@NonNull SseComment> clientInitializerComments;
		@NonNull
		private final ReentrantLock lock;
		@Nullable
		private Consumer<SseEvent> eventConsumer;
		@Nullable
		private Consumer<SseComment> commentConsumer;

		HandshakeAccepted(SseHandshakeResult.@NonNull Accepted sseHandshakeResult,
											@NonNull ResourcePath resourcePath,
											@NonNull RequestResult requestResult,
											@NonNull DefaultSimulator simulator,
											@Nullable Consumer<SseUnicaster> clientInitializer) {
			requireNonNull(sseHandshakeResult);
			requireNonNull(resourcePath);
			requireNonNull(requestResult);
			requireNonNull(simulator);

			this.sseHandshakeResult = sseHandshakeResult;
			this.resourcePath = resourcePath;
			this.requestResult = requestResult;
			this.simulator = simulator;
			this.unicastErrorHandler = simulator.getSseServer()
					.map(sseServer -> sseServer.getUnicastErrorHandler())
					.orElseGet(AtomicReference::new);
			this.eventConsumer = null;
			this.commentConsumer = null;
			this.lock = new ReentrantLock();

			this.clientInitializerEvents = new CopyOnWriteArrayList<>();
			this.clientInitializerComments = new CopyOnWriteArrayList<>();

			if (clientInitializer != null) {
				clientInitializer.accept(new MockSseUnicaster(
						getResourcePath(),
						(sseEvent) -> {
							requireNonNull(sseEvent);

							// If we don't have an event consumer registered, collect the events in a list to be fired off once the consumer is registered.
							// If we do have the event consumer registered, send immediately
							Consumer<SseEvent> eventConsumer = getEventConsumer().orElse(null);

							if (eventConsumer == null)
								clientInitializerEvents.add(sseEvent);
							else {
								try {
									eventConsumer.accept(sseEvent);
								} catch (Throwable throwable) {
									handleUnicastError(throwable);
								}
							}
						},
						(sseComment) -> {
							requireNonNull(sseComment);

							// If we don't have an event consumer registered, collect the events in a list to be fired off once the consumer is registered.
							// If we do have the event consumer registered, send immediately
							Consumer<SseComment> commentConsumer = getCommentConsumer().orElse(null);

							if (commentConsumer == null)
								clientInitializerComments.add(sseComment);
							else {
								try {
									commentConsumer.accept(sseComment);
								} catch (Throwable throwable) {
									handleUnicastError(throwable);
								}
							}
						},
						getUnicastErrorHandler())
				);
			}
		}

		/**
		 * Registers a {@link SseEvent} "consumer" for this connection - similar to how a real client would listen for Server-Sent Events.
		 * <p>
		 * Each connection may have at most 1 event consumer.
		 * <p>
		 * See documentation at <a href="https://www.soklet.com/docs/testing#server-sent-events">https://www.soklet.com/docs/testing#server-sent-events</a>.
		 *
		 * @param eventConsumer function to be invoked when a Server-Sent Event has been unicast/broadcast on the Resource Path
		 * @throws IllegalStateException if you attempt to register more than 1 event consumer
		 */
		public void registerEventConsumer(@NonNull Consumer<SseEvent> eventConsumer) {
			requireNonNull(eventConsumer);

			getLock().lock();

			try {
				if (getEventConsumer().isPresent())
					throw new IllegalStateException(format("You cannot specify more than one event consumer for the same %s", HandshakeAccepted.class.getSimpleName()));

				this.eventConsumer = eventConsumer;

				// Send client initializer unicast events immediately, before any broadcasts can make it through
				for (SseEvent event : getClientInitializerEvents()) {
					try {
						eventConsumer.accept(event);
					} catch (Throwable throwable) {
						handleUnicastError(throwable);
					}
				}

				// Register with the mock SSE server broadcaster, preserving client context
				Object clientContext = getSseHandshakeResult().getClientContext().orElse(null);
				getSimulator().getSseServer().get().registerEventConsumer(getResourcePath(), eventConsumer, clientContext);
			} finally {
				getLock().unlock();
			}
		}

		/**
		 * Registers a Server-Sent comment "consumer" for this connection - similar to how a real client would listen for Server-Sent comment payloads.
		 * <p>
		 * Each connection may have at most 1 comment consumer.
		 * <p>
		 * See documentation at <a href="https://www.soklet.com/docs/testing#server-sent-events">https://www.soklet.com/docs/testing#server-sent-events</a>.
		 *
		 * @param commentConsumer function to be invoked when a Server-Sent comment has been unicast/broadcast on the Resource Path
		 * @throws IllegalStateException if you attempt to register more than 1 comment consumer
		 */
		public void registerCommentConsumer(@NonNull Consumer<SseComment> commentConsumer) {
			requireNonNull(commentConsumer);

			getLock().lock();

			try {
				if (getCommentConsumer().isPresent())
					throw new IllegalStateException(format("You cannot specify more than one comment consumer for the same %s", HandshakeAccepted.class.getSimpleName()));

				this.commentConsumer = commentConsumer;

				// Send client initializer unicast comments immediately, before any broadcasts can make it through
				for (SseComment comment : getClientInitializerComments()) {
					try {
						commentConsumer.accept(comment);
					} catch (Throwable throwable) {
						handleUnicastError(throwable);
					}
				}

				// Register with the mock SSE server broadcaster, preserving client context
				Object clientContext = getSseHandshakeResult().getClientContext().orElse(null);
				getSimulator().getSseServer().get().registerCommentConsumer(getResourcePath(), commentConsumer, clientContext);
			} finally {
				getLock().unlock();
			}
		}

		void unregisterConsumers() {
			getLock().lock();

			try {
				getEventConsumer().ifPresent((eventConsumer ->
						getSimulator().getSseServer().get().unregisterEventConsumer(getResourcePath(), eventConsumer)));

				getCommentConsumer().ifPresent((commentConsumer ->
						getSimulator().getSseServer().get().unregisterCommentConsumer(getResourcePath(), commentConsumer)));
			} finally {
				getLock().unlock();
			}
		}

		/**
		 * Gets the data provided when the handshake was accepted by the {@link com.soklet.annotation.SseEventSource}-annotated <em>Resource Method</em>.
		 *
		 * @return the data provided when the handshake was accepted
		 */
		public SseHandshakeResult.@NonNull Accepted getSseHandshakeResult() {
			return this.sseHandshakeResult;
		}

		@Override
		public String toString() {
			return format("%s{sseHandshakeResult=%s}", HandshakeAccepted.class.getSimpleName(), getSseHandshakeResult());
		}

		/**
		 * The initial result of the handshake, as written back to the client (note that the connection remains open).
		 * <p>
		 * Useful for examining headers/cookies written via {@link RequestResult#getMarshaledResponse()}.
		 *
		 * @return the result of this request
		 */
		@NonNull
		public RequestResult getRequestResult() {
			return this.requestResult;
		}

		@NonNull
		private ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@NonNull
		private DefaultSimulator getSimulator() {
			return this.simulator;
		}

		@NonNull
		private AtomicReference<@Nullable Consumer<Throwable>> getUnicastErrorHandler() {
			return this.unicastErrorHandler;
		}

		private void handleUnicastError(@NonNull Throwable throwable) {
			requireNonNull(throwable);
			Consumer<Throwable> handler = getUnicastErrorHandler().get();

			if (handler != null) {
				try {
					handler.accept(throwable);
					return;
				} catch (Throwable ignored) {
					// Fall through to default behavior
				}
			}

			throwable.printStackTrace();
		}

		@NonNull
		private List<@NonNull SseEvent> getClientInitializerEvents() {
			return this.clientInitializerEvents;
		}

		@NonNull
		private List<@NonNull SseComment> getClientInitializerComments() {
			return this.clientInitializerComments;
		}

		@NonNull
		private Optional<Consumer<SseEvent>> getEventConsumer() {
			return Optional.ofNullable(this.eventConsumer);
		}

		@NonNull
		private Optional<Consumer<SseComment>> getCommentConsumer() {
			return Optional.ofNullable(this.commentConsumer);
		}

		@NonNull
		private ReentrantLock getLock() {
			return this.lock;
		}
	}

	/**
	 * Represents the result of an SSE rejected handshake (explicit rejection; connection closed) when simulated by {@link Simulator#performSseRequest(Request)}.
	 * <p>
	 * The data provided when the handshake was rejected is available via {@link #getSseHandshakeResult()}, and the final data sent to the client is available via {@link #getRequestResult()}.
	 */
	@ThreadSafe
	final class HandshakeRejected implements SseRequestResult {
		private final SseHandshakeResult.@NonNull Rejected sseHandshakeResult;
		@NonNull
		private final RequestResult requestResult;

		HandshakeRejected(SseHandshakeResult.@NonNull Rejected sseHandshakeResult,
											@NonNull RequestResult requestResult) {
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
		public RequestResult getRequestResult() {
			return this.requestResult;
		}

		@Override
		public String toString() {
			return format("%s{sseHandshakeResult=%s, requestResult=%s}", HandshakeRejected.class.getSimpleName(), getSseHandshakeResult(), getRequestResult());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof HandshakeRejected handshakeRejected))
				return false;

			return Objects.equals(getSseHandshakeResult(), handshakeRejected.getSseHandshakeResult())
					&& Objects.equals(getRequestResult(), handshakeRejected.getRequestResult());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getSseHandshakeResult(), getRequestResult());
		}
	}

	/**
	 * Represents the result of an SSE request failure (implicit rejection, e.g. an exception occurred; connection closed) when simulated by {@link Simulator#performSseRequest(Request)}.
	 * <p>
	 * The final data sent to the client is available via {@link #getRequestResult()}.
	 */
	@ThreadSafe
	final class RequestFailed implements SseRequestResult {
		@NonNull
		private final RequestResult requestResult;

		RequestFailed(@NonNull RequestResult requestResult) {
			requireNonNull(requestResult);
			this.requestResult = requestResult;
		}

		/**
		 * The result of the handshake, as written back to the client (the connection is then closed).
		 *
		 * @return the result of this request
		 */
		@NonNull
		public RequestResult getRequestResult() {
			return this.requestResult;
		}

		@Override
		public String toString() {
			return format("%s{requestResult=%s}", RequestFailed.class.getSimpleName(), getRequestResult());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof RequestFailed requestFailed))
				return false;

			return Objects.equals(getRequestResult(), requestFailed.getRequestResult());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getRequestResult());
		}
	}
}
