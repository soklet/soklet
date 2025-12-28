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

import com.soklet.HandshakeResult.Accepted.Builder;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

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
 * let eventSource = new EventSource(eventSourceUrl);
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
 * ServerSentEvent event = ServerSentEvent.withEvent("chat-message")
 *   .data("Hello, world") // often JSON
 *   .retry(Duration.ofSeconds(5))
 *   .build();
 *
 * // ...and send it to all connected clients.
 * broadcaster.broadcastEvent(event);}</pre>
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
	@NonNull
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
	@NonNull
	static Builder acceptWithDefaults() {
		return new Builder();
	}

	/**
	 * Vends an instance that indicates a rejected handshake along with a logical response to send to the client.
	 *
	 * @param response the response to send to the client
	 * @return an instance that indicates a rejected handshake
	 */
	@NonNull
	static Rejected rejectWithResponse(@NonNull Response response) {
		requireNonNull(response);
		return new Rejected(response);
	}

	/**
	 * Type which indicates a successful Server-Sent Event handshake.
	 * <p>
	 * A default, no-customization-permitted instance can be acquired via {@link #accept()} and a builder which enables customization can be acquired via {@link #acceptWithDefaults()}.
	 * <p>
	 * Full documentation is available at <a href="https://www.soklet.com/docs/server-sent-events#accepting-handshakes">https://www.soklet.com/docs/server-sent-events#accepting-handshakes</a>.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	final class Accepted implements HandshakeResult {
		@NonNull
		static final Accepted DEFAULT_INSTANCE;

		static {
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
			private Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
			@Nullable
			private Set<@NonNull ResponseCookie> cookies;
			@Nullable
			private Object clientContext;
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
			@NonNull
			public Builder headers(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> headers) {
				this.headers = headers;
				return this;
			}

			/**
			 * Specifies custom response cookies to be sent with the handshake.
			 *
			 * @param cookies custom response cookies to send
			 * @return this builder, for chaining
			 */
			@NonNull
			public Builder cookies(@Nullable Set<@NonNull ResponseCookie> cookies) {
				this.cookies = cookies;
				return this;
			}

			/**
			 * Specifies an application-specific custom context to be preserved over the lifetime of the SSE connection.
			 * <p>
			 * For example, an application might want to broadcast differently-formatted payloads based on the client's locale - a {@link java.util.Locale} object could be specified as client context.
			 * <p>
			 * Server-Sent Events can then be broadcast per-locale via {@link ServerSentEventBroadcaster#broadcastEvent(Function, Function)}.
			 *
			 * @param clientContext custom context
			 * @return this builder, for chaining
			 */
			@NonNull
			public Builder clientContext(@Nullable Object clientContext) {
				this.clientContext = clientContext;
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
			@NonNull
			public Builder clientInitializer(@Nullable Consumer<ServerSentEventUnicaster> clientInitializer) {
				this.clientInitializer = clientInitializer;
				return this;
			}

			@NonNull
			public Accepted build() {
				return new Accepted(this);
			}
		}

		@Nullable
		private final Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
		@Nullable
		private final Set<@NonNull ResponseCookie> cookies;
		@Nullable
		private final Object clientContext;
		@Nullable
		private final Consumer<ServerSentEventUnicaster> clientInitializer;

		private Accepted(@NonNull Builder builder) {
			requireNonNull(builder);

			// Defensive copies
			Map<String, Set<String>> headers = builder.headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedCaseInsensitiveMap<>(builder.headers));
			Set<ResponseCookie> cookies = builder.cookies == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(builder.cookies));

			this.headers = headers;
			this.cookies = cookies;
			this.clientContext = builder.clientContext;
			this.clientInitializer = builder.clientInitializer;
		}

		/**
		 * Returns the headers explicitly specified when this handshake was accepted (which may be different from the finalized map of headers sent to the client).
		 *
		 * @return the headers explicitly specified when this handshake was accepted
		 */
		@Nullable
		public Map<@NonNull String, @NonNull Set<@NonNull String>> getHeaders() {
			return this.headers;
		}

		/**
		 * Returns the cookies explicitly specified when this handshake was accepted (which may be different from the finalized map of headers sent to the client).
		 *
		 * @return the cookies explicitly specified when this handshake was accepted
		 */
		@Nullable
		public Set<@NonNull ResponseCookie> getCookies() {
			return this.cookies;
		}

		/**
		 * Returns the client context, if specified, for this accepted Server-Sent Event handshake.
		 * <p>
		 * Useful for "targeted" broadcasts via {@link ServerSentEventBroadcaster#broadcastEvent(Function, Function)}.
		 *
		 * @return the client context, or {@link Optional#empty()} if none was specified
		 */
		@NonNull
		public Optional<@NonNull Object> getClientContext() {
			return Optional.ofNullable(this.clientContext);
		}

		/**
		 * Returns the client initialization function, if specified, for this accepted Server-Sent Event handshake.
		 *
		 * @return the client initialization function, or {@link Optional#empty()} if none was specified
		 */
		@NonNull
		public Optional<@NonNull Consumer<ServerSentEventUnicaster>> getClientInitializer() {
			return Optional.ofNullable(this.clientInitializer);
		}

		@Override
		public String toString() {
			return format("%s{headers=%s, cookies=%s, clientContext=%s clientInitializer=%s}",
					Accepted.class.getSimpleName(), getHeaders(), getCookies(),
					(getClientContext().isPresent() ? getClientContext().get() : "[not specified]"),
					(getClientInitializer().isPresent() ? "[specified]" : "[not specified]")
			);
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof Accepted accepted))
				return false;

			return Objects.equals(getHeaders(), accepted.getHeaders())
					&& Objects.equals(getCookies(), accepted.getCookies())
					&& Objects.equals(getClientContext(), accepted.getClientContext())
					&& Objects.equals(getClientInitializer(), accepted.getClientInitializer());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getHeaders(), getCookies(), getClientContext(), getClientInitializer());
		}
	}

	/**
	 * Type which indicates a rejected Server-Sent Event handshake.
	 * <p>
	 * Instances can be acquired via the {@link HandshakeResult#rejectWithResponse(Response)} factory method.
	 * <p>
	 * Full documentation is available at <a href="https://www.soklet.com/docs/server-sent-events#rejecting-handshakes">https://www.soklet.com/docs/server-sent-events#rejecting-handshakes</a>.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	final class Rejected implements HandshakeResult {
		@NonNull
		private final Response response;

		private Rejected(@NonNull Response response) {
			requireNonNull(response);
			this.response = response;
		}

		/**
		 * The logical response to send to the client for this handshake rejection.
		 *
		 * @return the logical response for this handshake rejection
		 */
		@NonNull
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
