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

import static java.util.Objects.requireNonNull;

/**
 * TODO: document
 * <p>
 * See <a href="https://www.soklet.com/docs/testing#integration-testing">https://www.soklet.com/docs/testing#integration-testing</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public sealed interface ServerSentEventRequestResult permits ServerSentEventRequestResult.Accepted, ServerSentEventRequestResult.Rejected {
	interface ServerSentEventSourceConnection extends AutoCloseable {
		@Nonnull
		Boolean isConnected();

		// Narrow AutoCloseable's "closed" to not throw a checked Exception to reduce unnecessary boilerplate
		@Override
		void close();
	}

	final class Accepted implements ServerSentEventRequestResult {
		@Nonnull
		private final HandshakeResult.Accepted handshakeResult;
		@Nonnull
		private final ServerSentEventSourceConnection connection;

		Accepted(@Nonnull HandshakeResult.Accepted handshakeResult,
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
	}

	final class Rejected implements ServerSentEventRequestResult {
		@Nonnull
		private final HandshakeResult.Rejected handshakeResult;
		@Nonnull
		private final RequestResult requestResult;

		Rejected(@Nonnull HandshakeResult.Rejected handshakeResult,
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
	}
}
