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

import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Represents the result of a {@link com.soklet.annotation.ServerSentEventSource} "handshake".
 * <p>
 * Once a handshake has been accepted, you may acquire a broadcaster via {@link ServerSentEventServer#acquireBroadcaster(ResourcePath)} - the client whose handshake was accepted will then receive Server-Sent Events broadcast via {@link ServerSentEventBroadcaster#broadcast(ServerSentEvent)}.
 * <p>
 * You might have a JavaScript Server-Sent Event client that looks like this:
 * <pre>{@code // Register an event source
 * let eventSourceUrl =
 *   'https://sse.example.com/chats/123/event-source?signingToken=xxx';
 *
 * let eventSource = new EventSource(eventSourceUrl, {
 *   withCredentials: true
 * });
 *
 * // Listen for Server-Sent Events
 * eventSource.addEventListener('chat-message', (e) => {
 *   console.log(`Chat message: ${e.data}`);
 * });}</pre>
 * <p>
 * And then a Soklet Server-Sent Event Source that looks like this, which performs the handshake:
 * <pre>{@code // Resource Method that acts as a Server-Sent Event Source
 * @ServerSentEventSource("/chats/{chatId}/event-source")
 * public HandshakeResult chatEventSource(
 *   @PathParameter Long chatId,
 *   @QueryParameter String signingToken
 * ) {
 *   Chat chat = myChatService.find(chatId);
 *
 *   // Exceptions that bubble out will reject the handshake and go through the
 *   // ResponseMarshaler.forThrowable(...) path, same as non-SSE Resource Methods
 *   if (chat == null)
 *     throw new NotFoundException();
 *
 *   // You'll normally want to use a transient signing token for SSE authorization
 *   myAuthorizationService.verify(signingToken);
 *
 *   // Accept the handshake with no additional data
 *   // (or use a variant to send headers/cookies).
 *   // Can also reject via HandshakeResult.rejectWithResponse(...)
 *   return HandshakeResult.accept();
 * }}</pre>
 * <p>
 * Finally, broadcast to all clients who had their handshakes accepted:
 * <pre>{@code // Sometime later, acquire a broadcaster...
 * ResourcePath resourcePath = ResourcePath.withPath("/chats/123/event-source");
 * ServerSentEventBroadcaster broadcaster = sseServer.acquireBroadcaster(resourcePath).get();
 *
 * // ...construct the payload...
 * ServerSentEvent sse = ServerSentEvent.withEvent("chat-message")
 *   .data("Hello, world") // often JSON
 *   .retry(Duration.ofSeconds(5))
 *   .build();
 *
 * // ...and send it to all connected clients.
 * broadcaster.broadcast(sse);}</pre>
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/server-sent-events">https://www.soklet.com/docs/server-sent-events</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public sealed interface HandshakeResult permits HandshakeResult.Accepted, HandshakeResult.Rejected {
	/**
	 * Vends an instance that indicates a successful handshake, with no additional information provided.
	 *
	 * @return an instance that indicates a successful handshake
	 */
	@Nonnull
	static Accepted accept() {
		return Accepted.DEFAULT_INSTANCE;
	}

	/**
	 * Vends an instance that indicates a successful handshake, including custom response headers to send to the server-sent event client.
	 *
	 * @param headers the response headers to include in the successful handshake response
	 * @return an instance that indicates a successful handshake
	 */
	@Nonnull
	static Accepted acceptWithHeaders(@Nonnull Map<String, Set<String>> headers) {
		requireNonNull(headers);
		return new Accepted(headers, Set.of());
	}

	/**
	 * Vends an instance that indicates a successful handshake, including custom response cookies to send to the server-sent event client.
	 *
	 * @param cookies the response cookies to include in the successful handshake response
	 * @return an instance that indicates a successful handshake
	 */
	@Nonnull
	static Accepted acceptWithCookies(@Nonnull Set<ResponseCookie> cookies) {
		requireNonNull(cookies);
		return new Accepted(Map.of(), cookies);
	}

	/**
	 * Vends an instance that indicates a successful handshake, including custom response headers and cookies to send to the server-sent event client.
	 *
	 * @param headers the response headers to include in the successful handshake response
	 * @param cookies the response cookies to include in the successful handshake response
	 * @return an instance that indicates a successful handshake
	 */
	@Nonnull
	static Accepted acceptWith(@Nonnull Map<String, Set<String>> headers,
														 @Nonnull Set<ResponseCookie> cookies) {
		requireNonNull(headers);
		requireNonNull(cookies);
		return new Accepted(headers, cookies);
	}

	/**
	 * Vends an instance that indicates a rejected handshake along with a logical response to send to the client.
	 *
	 * @param response the response to send to the client
	 * @return an instance that indicates a rejected handshake
	 */
	@Nonnull
	static Rejected rejectWithResponse(@Nonnull Response response) {
		requireNonNull(response);
		return new Rejected(response);
	}

	/**
	 * Type which indicates a successful server-sent event handshake.
	 * <p>
	 * Instances can be acquired via these factory methods:
	 * <ul>
	 *   <li>{@link HandshakeResult#accept()}</li>
	 *   <li>{@link HandshakeResult#acceptWithHeaders(Map)}</li>
	 *   <li>{@link HandshakeResult#acceptWithCookies(Set)}</li>
	 *   <li>{@link HandshakeResult#acceptWith(Map, Set)}</li>
	 * </ul>
	 */
	@ThreadSafe
	final class Accepted implements HandshakeResult {
		@Nonnull
		static final Accepted DEFAULT_INSTANCE;
		@Nonnull
		static final Map<String, Set<String>> DEFAULT_HEADERS;

		static {
			// Generally speaking, we always want these headers for SSE streaming responses.
			// Users can override if they think necessary
			LinkedCaseInsensitiveMap<Set<String>> defaultHeaders = new LinkedCaseInsensitiveMap<>(4);
			defaultHeaders.put("Content-Type", Set.of("text/event-stream; charset=UTF-8"));
			defaultHeaders.put("Cache-Control", Set.of("no-cache"));
			defaultHeaders.put("Connection", Set.of("keep-alive"));
			defaultHeaders.put("X-Accel-Buffering", Set.of("no"));

			DEFAULT_HEADERS = Collections.unmodifiableMap(defaultHeaders);
			DEFAULT_INSTANCE = new Accepted(Map.of(), Set.of());
		}

		@Nonnull
		private final MarshaledResponse marshaledResponse;

		private Accepted(@Nonnull Map<String, Set<String>> headers,
										 @Nonnull Set<ResponseCookie> cookies) {
			requireNonNull(headers);
			requireNonNull(cookies);

			LinkedCaseInsensitiveMap<Set<String>> finalHeaders = new LinkedCaseInsensitiveMap<>(DEFAULT_HEADERS.size() + headers.size());

			// Start with defaults
			for (Map.Entry<String, Set<String>> e : DEFAULT_HEADERS.entrySet())
				finalHeaders.put(e.getKey(), e.getValue()); // values already unmodifiable

			// Overlay user-supplied headers (prefer user values on key collision)
			for (Map.Entry<String, Set<String>> e : headers.entrySet()) {
				// Defensively copy so callers can't mutate after construction
				Set<String> values = e.getValue() == null ? Set.of() : Set.copyOf(e.getValue());
				finalHeaders.put(e.getKey(), values);
			}

			this.marshaledResponse = MarshaledResponse.withStatusCode(200)
					.headers(finalHeaders)
					.cookies(cookies)
					.build();
		}

		/**
		 * The response to be sent over the wire for this accepted server-sent event handshake.
		 *
		 * @return the response to be sent over the wire
		 */
		@Nonnull
		public MarshaledResponse getMarshaledResponse() {
			return this.marshaledResponse;
		}

		@Override
		public String toString() {
			return format("%s{marshaledResponse=%s}", Accepted.class.getSimpleName(), getMarshaledResponse());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof Accepted accepted))
				return false;

			return Objects.equals(getMarshaledResponse(), accepted.getMarshaledResponse());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getMarshaledResponse());
		}
	}

	/**
	 * Type which indicates a rejected server-sent event handshake.
	 * <p>
	 * Instances can be acquired via the {@link HandshakeResult#rejectWithResponse(Response)} factory method.
	 */
	@ThreadSafe
	final class Rejected implements HandshakeResult {
		@Nonnull
		private final Response response;

		private Rejected(@Nonnull Response response) {
			requireNonNull(response);
			this.response = response;
		}

		/**
		 * The logical response to send to the client for this handshake rejection.
		 *
		 * @return the logical response for this handshake rejection
		 */
		@Nonnull
		public Response getResponse() {
			return this.response;
		}

		@Override
		public String toString() {
			return format("%s{response=%s}", Rejected.class.getSimpleName(), getResponse());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof Rejected rejected))
				return false;

			return Objects.equals(getResponse(), rejected.getResponse());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getResponse());
		}
	}
}
