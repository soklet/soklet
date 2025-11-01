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
import java.util.Objects;
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
	/**
	 * TODO: document
	 */
	interface ServerSentEventSourceConnection extends AutoCloseable {
		// TODO: do we need the concept of "connected?"
		// TODO: should we have a mechanism to register event and comment listeners here, or one level up?

		/**
		 * Registers a {@link ServerSentEvent} "consumer" for this connection - similar to how a real client would listen for Server-Sent Events and comments.
		 * <p>
		 * See documentation at <a href="https://www.soklet.com/docs/server-sent-events#testing">https://www.soklet.com/docs/server-sent-events#testing</a>.
		 *
		 * @param eventConsumer function to be invoked when a Server-Sent Event has been unicast/broadcast on the Resource Path
		 */
		void registerEventConsumer(@Nonnull Consumer<ServerSentEvent> eventConsumer);

		/**
		 * Registers a Server-Sent comment "consumer" for this connection - similar to how a real client would listen for Server-Sent Events and comments.
		 * <p>
		 * See documentation at <a href="https://www.soklet.com/docs/server-sent-events#testing">https://www.soklet.com/docs/server-sent-events#testing</a>.
		 *
		 * @param commentConsumer function to be invoked when a Server-Sent comment has been unicast/broadcast on the Resource Path
		 */
		void registerCommentConsumer(@Nonnull Consumer<String> commentConsumer);

		@Nonnull
		Boolean isConnected();

		// Narrow AutoCloseable's "closed" to not throw a checked Exception to reduce unnecessary boilerplate
		@Override
		void close();
	}

	@ThreadSafe
	final class HandshakeAccepted implements ServerSentEventRequestResult {
		@Nonnull
		private final HandshakeResult.Accepted handshakeResult;
		@Nonnull
		private final ServerSentEventSourceConnection connection;

		HandshakeAccepted(@Nonnull HandshakeResult.Accepted handshakeResult,
											@Nonnull ServerSentEventSourceConnection connection) {
			requireNonNull(handshakeResult);
			requireNonNull(connection);

			this.handshakeResult = handshakeResult;
			this.connection = connection;
		}

		@Nonnull
		public HandshakeResult.Accepted getHandshakeResult() {
			return this.handshakeResult;
		}

		@Nonnull
		public ServerSentEventSourceConnection getConnection() {
			return this.connection;
		}

		@Override
		public String toString() {
			return format("%s{handshakeResult=%s, connection=%s}", HandshakeAccepted.class.getSimpleName(), getHandshakeResult(), getConnection());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof HandshakeAccepted handshakeAccepted))
				return false;

			return Objects.equals(getHandshakeResult(), handshakeAccepted.getHandshakeResult())
					&& Objects.equals(getConnection(), handshakeAccepted.getConnection());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getHandshakeResult(), getConnection());
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
