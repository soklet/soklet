/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.core;

import com.soklet.SokletConfiguration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface ServerSentEventServer extends AutoCloseable {
	/**
	 * Starts the SSE server, which makes it able to accept requests from clients.
	 * <p>
	 * If the server is already started, no action is taken.
	 */
	void start();

	/**
	 * Stops the SSE server, which makes it unable to accept requests from clients.
	 * <p>
	 * If the server is already stopped, no action is taken.
	 */
	void stop();

	/**
	 * Is this SSE server started (that is, able to handle requests from clients)?
	 *
	 * @return {@code true} if the server is started, {@code false} otherwise
	 */
	@Nonnull
	Boolean isStarted();

	/**
	 * {@link AutoCloseable}-enabled synonym for {@link #stop()}.
	 *
	 * @throws Exception if an exception occurs while stopping the server
	 */
	@Override
	default void close() throws Exception {
		stop();
	}

	@Nonnull
	Optional<? extends ServerSentEventBroadcaster> acquireBroadcaster(@Nullable ResourcePathInstance resourcePathInstance);

	/**
	 * The {@link com.soklet.Soklet} instance which manages this {@link ServerSentEventServer} will invoke this method exactly once at initialization time - this allows {@link com.soklet.Soklet} to "talk" to your {@link ServerSentEventServer}.
	 * <p>
	 * <strong>This method is designed for internal use by {@link com.soklet.Soklet} only and should not be invoked elsewhere.</strong>
	 *
	 * @param requestHandler a {@link com.soklet.Soklet}-internal request handler which takes a {@link ServerSentEventServer}-provided request as input and supplies a {@link MarshaledResponse} as output for the {@link ServerSentEventServer} to write back to the client
	 */
	void initialize(@Nonnull SokletConfiguration sokletConfiguration,
									@Nonnull RequestHandler requestHandler);

	/**
	 * Request/response processing contract for custom {@link ServerSentEventServer} implementations.
	 * <p>
	 * This is used internally by {@link com.soklet.Soklet} instances to "talk" to a {@link ServerSentEventServer} via {@link ServerSentEventServer#initialize(SokletConfiguration, RequestHandler)}.
	 * It's the responsibility of the {@link ServerSentEventServer} to implement HTTP mechanics: read bytes from the request, write bytes to the response, and so forth.
	 * <p>
	 * <strong>Most Soklet applications will use {@link com.soklet.core.impl.DefaultServerSentEventServer} and therefore do not need to implement this interface directly.</strong>
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	interface RequestHandler {
		/**
		 * Callback to be invoked by a {@link ServerSentEventServer} implementation after it has received a Server-Sent Event Source HTTP request but prior to writing initial data to the HTTP response.
		 * <p>
		 * <strong>Note: this method is only invoked during the initial request "handshake" - it is not called for subsequent Server-Sent Event stream writes performed via {@link ServerSentEventBroadcaster#broadcast(ServerSentEvent)} invocations.</strong>
		 * <p>
		 * For example, when a Server-Sent Event Source HTTP request is received, you might immediately write an HTTP 200 OK response if all looks good, or reject with a 401 due to invalid credentials.
		 * That is the extent of the request-handling logic performed here.  The Server-Sent Event stream then remains open and can be written to via {@link ServerSentEventBroadcaster#broadcast(ServerSentEvent)}.
		 * <p>
		 * The {@link ServerSentEventServer} is responsible for converting its internal request representation into a {@link Request}, which a {@link com.soklet.Soklet} instance consumes and performs Soklet application request processing logic.
		 * <p>
		 * The {@link com.soklet.Soklet} instance will generate a {@link MarshaledResponse} for the request, which it "hands back" to the {@link ServerSentEventServer} to be sent over the wire to the client.
		 *
		 * @param request                   a Soklet {@link Request} representation of the {@link ServerSentEventServer}'s internal HTTP request data
		 * @param marshaledResponseConsumer invoked by {@link com.soklet.Soklet} when it's time for the {@link ServerSentEventServer} to write HTTP response data to the client
		 */
		void handleRequest(@Nonnull Request request,
											 @Nonnull Consumer<MarshaledResponse> marshaledResponseConsumer);
	}
}
