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

/**
 * Broadcasts a <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events">Server-Sent Event</a> payload to all clients listening on a {@link ResourcePath}.
 * <p>
 * For example:
 * <pre>{@code // Acquire our SSE broadcaster (sends to anyone listening to "/examples/123")
 * ServerSentEventServer server = ...;
 * ServerSentEventBroadcaster broadcaster = server.acquireBroadcaster(ResourcePath.withPath("/examples/123")).get();
 *
 * // Create our SSE payload
 * ServerSentEvent event = ServerSentEvent.withEvent("test")
 *   .data("example")
 *   .build();
 *
 * // Publish SSE payload to all listening clients
 * broadcaster.broadcast(event);}</pre>
 * <p>
 * Soklet's default {@link ServerSentEventServer} implementation guarantees exactly one {@link ServerSentEventBroadcaster} instance exists per {@link ResourcePath}. That implementation is responsible for the creation and management of {@link ServerSentEventBroadcaster} instances.
 * <p>
 * You may acquire a broadcaster via {@link ServerSentEventServer#acquireBroadcaster(ResourcePath)}.
 * <p>
 * See <a href="https://www.soklet.com/docs/server-sent-events">https://www.soklet.com/docs/server-sent-events</a> for detailed documentation.
 * <p>
 * Formal specification is available at <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events">https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface ServerSentEventBroadcaster {
	/**
	 * The runtime Resource Path with which this broadcaster is associated.
	 * <p>
	 * Soklet guarantees exactly one {@link ServerSentEventBroadcaster} instance exists per {@link ResourcePath}.
	 * <p>
	 * For example, a client may register for SSE broadcasts for <em>Resource Method</em> {@code @ServerSentEventSource("/examples/{exampleId}")} by making a request to {@code GET /examples/123}.
	 * <p>
	 * A broadcaster specific to {@code /examples/123} is then created (if necessary) and managed by Soklet, and can be used to send SSE payloads to all clients via {@link #broadcastEvent(ServerSentEvent)}.
	 *
	 * @return the runtime Resource Path instance with which this broadcaster is associated
	 */
	@Nonnull
	ResourcePath getResourcePath();

	/**
	 * Approximately how many clients are listening to this broadcaster's {@link ResourcePath}?
	 * <p>
	 * For performance reasons, this number may be an estimate, or a snapshot of a recent moment-in-time.
	 * It's possible for some clients to have already disconnected, but we won't know until we attempt to broadcast to them.
	 *
	 * @return the approximate number of clients who will receive a broadcasted event
	 */
	@Nonnull
	Long getClientCount();

	/**
	 * Broadcasts a Server-Sent Event payload to all clients listening to this broadcaster's {@link ResourcePath}.
	 * <p>
	 * In practice, implementations will generally return "immediately" and broadcast operation[s] will occur on separate threads of execution.
	 * <p>
	 * However, mock implementations may wish to block until broadcasts have completed - for example, to simplify automated testing.
	 *
	 * @param serverSentEvent the Server-Sent Event payload to broadcast
	 */
	void broadcastEvent(@Nonnull ServerSentEvent serverSentEvent);

	/**
	 * Broadcasts a single Server-Sent Event comment to all clients listening to this broadcaster's {@link ResourcePath}.
	 * <p>
	 * Specify a blank string to generate a bare {@code ":"} Server-Sent Event comment line.
	 * <p>
	 * In practice, implementations will generally return "immediately" and broadcast operation[s] will occur on separate threads of execution.
	 * <p>
	 * However, mock implementations may wish to block until broadcasts have completed - for example, to simplify automated testing.
	 *
	 * @param comment the comment payload to broadcast
	 */
	void broadcastComment(@Nonnull String comment);
}