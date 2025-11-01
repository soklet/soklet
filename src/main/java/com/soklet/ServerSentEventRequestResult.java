/*
 * Copyright 2022-2025 Revetware LLC.
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * TODO: document
 * <p>
 * See <a href="https://www.soklet.com/docs/testing#integration-testing">https://www.soklet.com/docs/testing#integration-testing</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public sealed interface ServerSentEventRequestResult permits ServerSentEventRequestResult.HandshakeAccepted, ServerSentEventRequestResult.HandshakeRejected, ServerSentEventRequestResult.RequestFailed {
	@ThreadSafe
	final class HandshakeAccepted implements ServerSentEventRequestResult {
		@Nonnull
		private final HandshakeResult.Accepted handshakeResult;
		@Nonnull
		private final ResourcePath resourcePath;
		@Nonnull
		private final RequestResult requestResult;
		@Nonnull
		private final Soklet.DefaultSimulator simulator;
		@Nonnull
		private List<ServerSentEvent> clientInitializerEvents;
		@Nonnull
		private List<String> clientInitializerComments;
		@Nonnull
		private final ReentrantLock lock;
		@Nullable
		private Consumer<ServerSentEvent> eventConsumer;
		@Nullable
		private Consumer<String> commentConsumer;

		HandshakeAccepted(@Nonnull HandshakeResult.Accepted handshakeResult,
											@Nonnull ResourcePath resourcePath,
											@Nonnull RequestResult requestResult,
											@Nonnull Soklet.DefaultSimulator simulator,
											@Nullable Consumer<ServerSentEventUnicaster> clientInitializer) {
			requireNonNull(handshakeResult);
			requireNonNull(resourcePath);
			requireNonNull(requestResult);
			requireNonNull(simulator);

			this.handshakeResult = handshakeResult;
			this.resourcePath = resourcePath;
			this.requestResult = requestResult;
			this.simulator = simulator;
			this.eventConsumer = null;
			this.commentConsumer = null;
			this.lock = new ReentrantLock();

			this.clientInitializerEvents = new CopyOnWriteArrayList<>();
			this.clientInitializerComments = new CopyOnWriteArrayList<>();

			if (clientInitializer != null) {
				clientInitializer.accept(new Soklet.MockServerSentEventUnicaster(
						getResourcePath(),
						(serverSentEvent) -> {
							requireNonNull(serverSentEvent);

							// If we don't have an event consumer registered, collect the events in a list to be fired off once the consumer is registered.
							// If we do have the event consumer registered, send immediately
							Consumer<ServerSentEvent> eventConsumer = getEventConsumer().orElse(null);

							if (eventConsumer == null)
								clientInitializerEvents.add(serverSentEvent);
							else
								eventConsumer.accept(serverSentEvent);
						},
						(comment) -> {
							requireNonNull(comment);

							// If we don't have an event consumer registered, collect the events in a list to be fired off once the consumer is registered.
							// If we do have the event consumer registered, send immediately
							Consumer<String> commentConsumer = getCommentConsumer().orElse(null);

							if (commentConsumer == null)
								clientInitializerComments.add(comment);
							else
								commentConsumer.accept(comment);
						})
				);
			}
		}

		/**
		 * Registers a {@link ServerSentEvent} "consumer" for this connection - similar to how a real client would listen for Server-Sent Events.
		 * <p>
		 * Each connection may have at most 1 event consumer.
		 * <p>
		 * See documentation at <a href="https://www.soklet.com/docs/server-sent-events#testing">https://www.soklet.com/docs/server-sent-events#testing</a>.
		 *
		 * @param eventConsumer function to be invoked when a Server-Sent Event has been unicast/broadcast on the Resource Path
		 * @throws IllegalStateException if you attempt to register more than 1 event consumer
		 */
		public void registerEventConsumer(@Nonnull Consumer<ServerSentEvent> eventConsumer) {
			requireNonNull(eventConsumer);

			getLock().lock();

			try {
				if (getEventConsumer().isPresent())
					throw new IllegalStateException(format("You cannot specify more than one event consumer for the same %s", HandshakeAccepted.class.getSimpleName()));

				this.eventConsumer = eventConsumer;

				// Send client initializer unicast events immediately, before any broadcasts can make it through
				for (ServerSentEvent event : getClientInitializerEvents())
					eventConsumer.accept(event);

				// Register with the mock SSE server broadcaster
				getSimulator().getServerSentEventServer().registerEventConsumer(getResourcePath(), eventConsumer);
			} finally {
				getLock().unlock();
			}
		}

		/**
		 * Registers a Server-Sent comment "consumer" for this connection - similar to how a real client would listen for Server-Sent comment payloads.
		 * <p>
		 * Each connection may have at most 1 comment consumer.
		 * <p>
		 * See documentation at <a href="https://www.soklet.com/docs/server-sent-events#testing">https://www.soklet.com/docs/server-sent-events#testing</a>.
		 *
		 * @param commentConsumer function to be invoked when a Server-Sent comment has been unicast/broadcast on the Resource Path
		 * @throws IllegalStateException if you attempt to register more than 1 comment consumer
		 */
		public void registerCommentConsumer(@Nonnull Consumer<String> commentConsumer) {
			requireNonNull(commentConsumer);

			getLock().lock();

			try {
				if (getCommentConsumer().isPresent())
					throw new IllegalStateException(format("You cannot specify more than one comment consumer for the same %s", HandshakeAccepted.class.getSimpleName()));

				this.commentConsumer = commentConsumer;

				// Send client initializer unicast comments immediately, before any broadcasts can make it through
				for (String comment : getClientInitializerComments())
					commentConsumer.accept(comment);

				// Register with the mock SSE server broadcaster
				getSimulator().getServerSentEventServer().registerCommentConsumer(getResourcePath(), commentConsumer);
			} finally {
				getLock().unlock();
			}
		}

		void unregisterConsumers() {
			getLock().lock();

			try {
				getEventConsumer().ifPresent((eventConsumer ->
						getSimulator().getServerSentEventServer().unregisterEventConsumer(getResourcePath(), eventConsumer)));

				getCommentConsumer().ifPresent((commentConsumer ->
						getSimulator().getServerSentEventServer().unregisterCommentConsumer(getResourcePath(), commentConsumer)));
			} finally {
				getLock().unlock();
			}
		}

		@Nonnull
		public HandshakeResult.Accepted getHandshakeResult() {
			return this.handshakeResult;
		}

		@Override
		public String toString() {
			return format("%s{handshakeResult=%s}", HandshakeAccepted.class.getSimpleName(), getHandshakeResult());
		}

		@Nonnull
		private ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@Nonnull
		private RequestResult getRequestResult() {
			return this.requestResult;
		}

		@Nonnull
		private Soklet.DefaultSimulator getSimulator() {
			return this.simulator;
		}

		@Nonnull
		private List<ServerSentEvent> getClientInitializerEvents() {
			return this.clientInitializerEvents;
		}

		@Nonnull
		private List<String> getClientInitializerComments() {
			return this.clientInitializerComments;
		}

		@Nonnull
		private Optional<Consumer<ServerSentEvent>> getEventConsumer() {
			return Optional.ofNullable(this.eventConsumer);
		}

		@Nonnull
		private Optional<Consumer<String>> getCommentConsumer() {
			return Optional.ofNullable(this.commentConsumer);
		}

		@Nonnull
		private ReentrantLock getLock() {
			return this.lock;
		}
	}

	@ThreadSafe
	final class HandshakeRejected implements ServerSentEventRequestResult {
		@Nonnull
		private final HandshakeResult.Rejected handshakeResult;
		@Nonnull
		private final RequestResult requestResult;

		HandshakeRejected(@Nonnull HandshakeResult.Rejected handshakeResult,
											@Nonnull RequestResult requestResult) {
			requireNonNull(handshakeResult);
			requireNonNull(requestResult);

			this.handshakeResult = handshakeResult;
			this.requestResult = requestResult;
		}

		@Nonnull
		public HandshakeResult.Rejected getHandshakeResult() {
			return this.handshakeResult;
		}

		@Nonnull
		public RequestResult getRequestResult() {
			return this.requestResult;
		}

		@Override
		public String toString() {
			return format("%s{handshakeResult=%s, requestResult=%s}", HandshakeRejected.class.getSimpleName(), getHandshakeResult(), getRequestResult());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof HandshakeRejected handshakeRejected))
				return false;

			return Objects.equals(getHandshakeResult(), handshakeRejected.getHandshakeResult())
					&& Objects.equals(getRequestResult(), handshakeRejected.getRequestResult());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getHandshakeResult(), getRequestResult());
		}
	}

	@ThreadSafe
	final class RequestFailed implements ServerSentEventRequestResult {
		@Nonnull
		private final RequestResult requestResult;

		RequestFailed(@Nonnull RequestResult requestResult) {
			requireNonNull(requestResult);
			this.requestResult = requestResult;
		}

		@Nonnull
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
