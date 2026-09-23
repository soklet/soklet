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

import javax.annotation.concurrent.ThreadSafe;

/**
 * Unicasts a <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events">Server-Sent Event</a> or comment payload to a specific client listening on a {@link ResourcePath}.
 * <p>
 * For example:
 * <pre>{@code @SseEventSource("/chats/{chatId}/event-source")
 * public SseHandshakeResult chatEventSource(
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
 *   // If a Last-Event-ID header was sent, pull one bounded replay page.
 *   // This example assumes connectionQueueCapacity is at least 128.
 *   List<ChatMessage> catchupMessages = new ArrayList<>();
 *
 *   if(lastEventId != null)
 *     catchupMessages.addAll(myChatService.findCatchups(chatId, lastEventId, 64));
 *
 *   // Customize "accept" handshake with a client initializer
 *   return SseHandshakeResult.Accepted.builder()
 *     .clientInitializer(sseUnicaster -> {
 *       // Unicast "catchup" initialization events to this specific client.
 *       // If delivered, these events precede broadcaster events to this client.
 *       // Continue additional pages through your application replay protocol;
 *       // application-level coordination is needed for gap-free replay.
 *       catchupMessages.stream()
 *         .map(catchupMessage -> SseEvent.withEvent("chat-message")
 *           .id(catchupMessage.id())
 *           .data(catchupMessage.toJson())
 *           .retry(Duration.ofSeconds(5))
 *           .build())
 *         .forEach(event -> sseUnicaster.unicastEvent(event));
 *     })
 *     .build();
 * }}</pre>
 * <p>
 * Client-initializer writes are buffered before the connection becomes active
 * and are therefore limited to the configured
 * {@link SseServer.Builder#connectionQueueCapacity(Integer)}. Configure that
 * capacity for the largest expected catch-up page plus headroom for live broadcasts
 * arriving before that page drains; paginate or otherwise limit larger replays
 * before returning the accepted result. Overflow terminates the already-accepted connection with
 * {@link StreamTerminationReason#BACKPRESSURE}, even if the initializer catches
 * the exception. Buffered writes may be discarded. Soklet's optional one-time connection-verification heartbeat does
 * not consume an application queue slot.
 * <p>
 * The initializer is one-time, bounded setup or catch-up work. Its queued events are not delivered until it
 * returns successfully. Do not retain this unicaster or use the initializer as an indefinite upstream producer.
 * Broadcasts published before the client joins its broadcaster are not buffered for it. Initializer ordering
 * alone does not guarantee a gap-free handoff from replay to live events.
 * <p>
 * See <a href="https://www.soklet.com/docs/server-sent-events#client-initialization">https://www.soklet.com/docs/server-sent-events#client-initialization</a> for detailed documentation.
 * <p>
 * Formal specification is available at <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events">https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface SseUnicaster {
	/**
	 * Unicasts a single Server-Sent Event payload to a specific client listening to this unicaster's {@link ResourcePath}.
	 * <p>
	 * During client initialization this method queues the event. Soklet delivers queued events after the
	 * initializer returns successfully; returning from this method does not acknowledge socket delivery.
	 * <p>
	 * However, mock implementations may wish to block until the unicast has completed - for example, to simplify automated testing.
	 *
	 * @param sseEvent the Server-Sent Event payload to unicast
	 * @throws IllegalStateException if the initializer queue is full, the connection has terminated,
	 * or the initializer has returned
	 */
	void unicastEvent(@NonNull SseEvent sseEvent);

	/**
	 * Unicasts a single Server-Sent Event comment to a specific client listening to this unicaster's {@link ResourcePath}.
	 * <p>
	 * Use {@link SseComment#heartbeatInstance()} to emit a heartbeat comment.
	 * <p>
	 * During client initialization this method queues the comment. Soklet delivers queued comments after the
	 * initializer returns successfully; returning from this method does not acknowledge socket delivery.
	 * <p>
	 * However, mock implementations may wish to block until the unicast has completed - for example, to simplify automated testing.
	 *
	 * @param sseComment the comment payload to unicast
	 * @throws IllegalStateException if the initializer queue is full, the connection has terminated,
	 * or the initializer has returned
	 */
	void unicastComment(@NonNull SseComment sseComment);

	/**
	 * The runtime Resource Path with which this unicaster is associated.
	 * <p>
	 * For example, a client may successfully complete a Server-Sent Event handshake for <em>Resource Method</em> {@code @SseEventSource("/examples/{exampleId}")} by making a request to {@code GET /examples/123}. The server, immediately after accepting the handshake, might then acquire a unicaster to "catch up" the client according to the {@code Last-Event-ID} header value (for example).
	 * <p>
	 * During that client's initializer, Soklet provides a unicaster for {@code /examples/123}. It can queue catch-up payloads for that client via {@link #unicastEvent(SseEvent)} and becomes unusable when the initializer returns.
	 *
	 * @return the runtime Resource Path instance with which this unicaster is associated
	 */
	@NonNull
	ResourcePath getResourcePath();

}
