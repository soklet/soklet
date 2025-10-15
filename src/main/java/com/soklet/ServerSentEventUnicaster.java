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
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Unicasts a <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events">Server-Sent Event</a> payload to a specific client listening on a {@link ResourcePath}.
 * <p>
 * For example:
 * <pre>{@code TODO}</pre>
 * <p>
 * See <a href="https://www.soklet.com/docs/server-sent-events">https://www.soklet.com/docs/server-sent-events</a> for detailed documentation.
 * <p>
 * Formal specification is available at <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events">https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface ServerSentEventUnicaster {
	/**
	 * Unicasts a single Server-Sent Event payload to a specific client listening to this unicaster's {@link ResourcePath}.
	 * <p>
	 * In practice, implementations will generally return "immediately" and unicast operation[s] will occur on separate threads of execution.
	 * <p>
	 * However, mock implementations may wish to block until the unicast has completed - for example, to simplify automated testing.
	 *
	 * @param serverSentEvent the Server-Sent Event payload to unicast
	 */
	default void unicast(@Nonnull ServerSentEvent serverSentEvent) {
		requireNonNull(serverSentEvent);
		unicast(List.of(serverSentEvent));
	}

	/**
	 * Unicasts a list of Server-Sent Event payload to a specific client listening to this unicaster's {@link ResourcePath}, e.g. to "catch up" in a {@code Last-Event-ID} handshake scenario.
	 * <p>
	 * In practice, implementations will generally return "immediately" and unicast operation[s] will occur on separate threads of execution.
	 * <p>
	 * However, mock implementations may wish to block until the unicasts have completed - for example, to simplify automated testing.
	 *
	 * @param serverSentEvents the Server-Sent Event payloads to unicast
	 */
	void unicast(@Nonnull List<ServerSentEvent> serverSentEvents);

	/**
	 * The runtime Resource Path with which this unicaster is associated.
	 * <p>
	 * For example, a client may successfully complete a Server-Sent Event handshake for <em>Resource Method</em> {@code @ServerSentEventSource("/examples/{exampleId}")} by making a request to {@code GET /examples/123}. The server, immediately after accepting the handshake, might then acquire a unicaster to "catch up" the client according to the {@code Last-Event-ID} header value (for example).
	 * <p>
	 * A unicaster specific to {@code /examples/123} is then created (if necessary) and managed by Soklet, and can be used to send SSE payloads to that specific client via {@link #unicast(ServerSentEvent)} or {@link #unicast(List)}.
	 *
	 * @return the runtime Resource Path instance with which this unicaster is associated
	 */
	@Nonnull
	ResourcePath getResourcePath();
}