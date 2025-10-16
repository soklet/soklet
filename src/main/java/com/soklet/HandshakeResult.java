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

import com.soklet.HandshakeResult.Accepted.Builder;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Represents the result of a {@link com.soklet.annotation.ServerSentEventSource} "handshake".
 * <p>
 * Once a handshake has been accepted, you may acquire a broadcaster via {@link ServerSentEventServer#acquireBroadcaster(ResourcePath)} - the client whose handshake was accepted will then receive Server-Sent Events broadcast via {@link ServerSentEventBroadcaster#broadcastEvent(ServerSentEvent)}.
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
	 * Vends a builder for an instance that indicates a successful handshake.
	 * <p>
	 * The builder supports specifying optional response headers, cookies, and a post-handshake client initialization hook, which is useful to "catch up" in a {@code Last-Event-ID} handshake scenario.
	 *
	 * @return a builder for an instance that indicates a successful handshake
	 */
	@Nonnull
	static Builder acceptWithDefaults() {
		return new Builder();
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
	 * A default, no-customization-permitted instance can be acquired via {@link #accept()} and a builder which enables customization can be acquired via {@link #acceptWithDefaults()}.
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
			DEFAULT_INSTANCE = new Builder().build();
		}

		/**
		 * Builder used to construct instances of {@link Accepted}.
		 * <p>
		 * This class is intended for use by a single thread.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@NotThreadSafe
		public static final class Builder {
			@Nullable
			private Map<String, Set<String>> headers;
			@Nullable
			private Set<ResponseCookie> cookies;
			@Nullable
			private Consumer<ServerSentEventUnicaster> clientInitializer;

			private Builder() {
				// Only permit construction through Handshake builder methods
			}

			/**
			 * Specifies custom response headers to be sent with the handshake.
			 *
			 * @param headers custom response headers to send
			 * @return this builder, for chaining
			 */
			@Nonnull
			public Builder headers(@Nullable Map<String, Set<String>> headers) {
				this.headers = headers;
				return this;
			}

			/**
			 * Specifies custom response cookies to be sent with the handshake.
			 *
			 * @param cookies custom response cookies to send
			 * @return this builder, for chaining
			 */
			@Nonnull
			public Builder cookies(@Nullable Set<ResponseCookie> cookies) {
				this.cookies = cookies;
				return this;
			}

			/**
			 * Specifies custom "client initializer" function to run immediately after the handshake succeeds - useful for performing "catch-up" logic if the client had provided a {@code Last-Event-ID} request header.
			 * <p>
			 * The function is provided with a {@link ServerSentEventUnicaster}, which permits sending Server-Sent Events and comments directly to the client that accepted the handshake (as opposed to a {@link ServerSentEventBroadcaster}, which would send to all clients listening on the same {@link ResourcePath}).
			 * <p>
			 * Full documentation is available at <a href="https://www.soklet.com/docs/server-sent-events">https://www.soklet.com/docs/server-sent-events</a>.
			 *
			 * @param clientInitializer custom function to run to initialize the client
			 * @return this builder, for chaining
			 */
			@Nonnull
			public Builder clientInitializer(@Nullable Consumer<ServerSentEventUnicaster> clientInitializer) {
				this.clientInitializer = clientInitializer;
				return this;
			}

			@Nonnull
			public Accepted build() {
				return new Accepted(this);
			}
		}

		@Nullable
		private final Consumer<ServerSentEventUnicaster> clientInitializer;
		@Nonnull
		private final MarshaledResponse marshaledResponse;

		private Accepted(@Nonnull Builder builder) {
			requireNonNull(builder);

			// Don't need defensive copies b/c those happen downstream
			Map<String, Set<String>> headers = builder.headers == null ? Map.of() : builder.headers;
			Set<ResponseCookie> cookies = builder.cookies == null ? Set.of() : builder.cookies;

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

			this.clientInitializer = builder.clientInitializer;
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

		/**
		 * The client initialization function, if specified, for this accepted server-sent event handshake.
		 *
		 * @return the client initialization function, or {@link Optional#empty()} if none was specified
		 */
		@Nonnull
		public Optional<Consumer<ServerSentEventUnicaster>> getClientInitializer() {
			return Optional.ofNullable(this.clientInitializer);
		}

		@Override
		public String toString() {
			return format("%s{marshaledResponse=%s, clientInitializer=%s}",
					Accepted.class.getSimpleName(), getMarshaledResponse(), getClientInitializer().isPresent() ? "[specified]" : "[not specified]");
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof Accepted accepted))
				return false;

			return Objects.equals(getMarshaledResponse(), accepted.getMarshaledResponse())
					&& Objects.equals(getClientInitializer(), accepted.getClientInitializer());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getMarshaledResponse(), getClientInitializer());
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
