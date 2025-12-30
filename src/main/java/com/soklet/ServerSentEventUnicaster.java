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

import org.jspecify.annotations.NonNull;

/**
 * Unicasts a <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events">Server-Sent Event</a> or comment payload to a specific client listening on a {@link ResourcePath}.
 * <p>
 * For example:
 * <pre>{@code @ServerSentEventSource("/chats/{chatId}/event-source")
 * public HandshakeResult chatEventSource(
 *   @PathParameter Long chatId,
 *   // Browsers will send this header automatically on reconnects
 *   @RequestHeader(name="Last-Event-ID", optional=true) String lastEventId
 * ) {
 *   Chat chat = myChatService.find(chatId);
 *
 *   // Exceptions that bubble out will reject the handshake and go through the
 *   // ResponseMarshaler::forThrowable path, same as non-SSE Resource Methods
 *   if (chat == null)
 *     throw new NoSuchChatException();
 *
 *   // If a Last-Event-ID header was sent, pull data to "catch up" the client
 *   List<ChatMessage> catchupMessages = new ArrayList<>();
 *
 *   if(lastEventId != null)
 *     catchupMessages.addAll(myChatService.findCatchups(chatId, lastEventId));
 *
 *   // Customize "accept" handshake with a client initializer
 *   return HandshakeResult.acceptWithDefaults()
 *     .clientInitializer((unicaster) -> {
 *       // Unicast "catchup" initialization events to this specific client.
 *       // The unicaster is guaranteed to write these events before any
 *       // other broadcaster does, allowing clients to safely catch up
 *       // without the risk of event interleaving
 *       catchupMessages.stream()
 *         .map(catchupMessage -> ServerSentEvent.withEvent("chat-message")
 *           .id(catchupMessage.id())
 *           .data(catchupMessage.toJson())
 *           .retry(Duration.ofSeconds(5))
 *           .build())
 *         .forEach(event -> unicaster.unicastEvent(event));
 *     })
 *     .build();
 * }}</pre>
 * <p>
 * See <a href="https://www.soklet.com/docs/server-sent-events#client-initialization">https://www.soklet.com/docs/server-sent-events#client-initialization</a> for detailed documentation.
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
	void unicastEvent(@NonNull ServerSentEvent serverSentEvent);

	/**
	 * Unicasts a single Server-Sent Event comment to a specific client listening to this unicaster's {@link ResourcePath}.
	 * <p>
	 * Use {@link ServerSentEventComment#withHeartbeat()} to emit a heartbeat comment, or set the {@link ServerSentEventComment#getCommentType()} to {@link ServerSentEventComment.CommentType#HEARTBEAT}.
	 * <p>
	 * In practice, implementations will generally return "immediately" and unicast operation[s] will occur on separate threads of execution.
	 * <p>
	 * However, mock implementations may wish to block until the unicast has completed - for example, to simplify automated testing.
	 *
	 * @param serverSentEventComment the comment payload to unicast
	 */
	void unicastComment(@NonNull ServerSentEventComment serverSentEventComment);

	/**
	 * The runtime Resource Path with which this unicaster is associated.
	 * <p>
	 * For example, a client may successfully complete a Server-Sent Event handshake for <em>Resource Method</em> {@code @ServerSentEventSource("/examples/{exampleId}")} by making a request to {@code GET /examples/123}. The server, immediately after accepting the handshake, might then acquire a unicaster to "catch up" the client according to the {@code Last-Event-ID} header value (for example).
	 * <p>
	 * A unicaster specific to {@code /examples/123} is then created (if necessary) and managed by Soklet, and can be used to send SSE payloads to that specific client via {@link #unicastEvent(ServerSentEvent)}.
	 *
	 * @return the runtime Resource Path instance with which this unicaster is associated
	 */
	@NonNull
	ResourcePath getResourcePath();
}
