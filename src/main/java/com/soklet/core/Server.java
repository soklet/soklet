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
import java.util.function.Consumer;

/**
 * Contract for HTTP server implementations that are designed to be managed by a {@link com.soklet.Soklet} instance.
 * <p>
 * <strong>Most Soklet applications will use {@link com.soklet.core.impl.DefaultServer} and therefore do not need to implement this interface directly.</strong>
 * <p>
 * For example:
 * <pre>{@code  SokletConfiguration config = SokletConfiguration.withServer(
 *   DefaultServer.withPort(8080).build()
 * ).build();
 *
 * try (Soklet soklet = new Soklet(config)) {
 *   soklet.start();
 *   System.out.println("Soklet started, press [enter] to exit");
 *   System.in.read(); // or Thread.currentThread().join() in containers
 * }}</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface Server extends AutoCloseable {
	/**
	 * Starts the server, which makes it able to accept requests from clients.
	 * <p>
	 * If the server is already started, no action is taken.
	 */
	void start();

	/**
	 * Stops the server, which makes it unable to accept requests from clients.
	 * <p>
	 * If the server is already stopped, no action is taken.
	 */
	void stop();

	/**
	 * Is this server started (that is, able to handle requests from clients)?
	 *
	 * @return {@code true} if the server is started, {@code false} otherwise
	 */
	@Nonnull
	Boolean isStarted();

	/**
	 * The {@link com.soklet.Soklet} instance which manages this {@link Server} will invoke this method exactly once at initialization time - this allows {@link com.soklet.Soklet} to "talk" to your {@link Server}.
	 * <p>
	 * <strong>This method is designed for internal use by {@link com.soklet.Soklet} only and should not be invoked elsewhere.</strong>
	 *
	 * @param sokletConfiguration configuration for the Soklet instance that controls this server
	 * @param requestHandler      a {@link com.soklet.Soklet}-internal request handler which takes a {@link Server}-provided request as input and supplies a {@link MarshaledResponse} as output for the {@link Server} to write back to the client
	 */
	void initialize(@Nonnull SokletConfiguration sokletConfiguration,
									@Nonnull RequestHandler requestHandler);

	/**
	 * {@link AutoCloseable}-enabled synonym for {@link #stop()}.
	 *
	 * @throws Exception if an exception occurs while stopping the server
	 */
	@Override
	default void close() throws Exception {
		stop();
	}

	/**
	 * Request/response processing contract for {@link Server} implementations.
	 * <p>
	 * This is used internally by {@link com.soklet.Soklet} instances to "talk" to a {@link Server} via {@link Server#initialize(SokletConfiguration, RequestHandler)}.  It's the responsibility of the {@link Server} to implement HTTP mechanics: read bytes from the request, write bytes to the response, and so forth.
	 * <p>
	 * <strong>Most Soklet applications will use {@link com.soklet.core.impl.DefaultServer} and therefore do not need to implement this interface directly.</strong>
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	interface RequestHandler {
		/**
		 * Callback to be invoked by a {@link Server} implementation after it has received an HTTP request but prior to writing an HTTP response.
		 * <p>
		 * The {@link Server} is responsible for converting its internal request representation into a {@link Request}, which a {@link com.soklet.Soklet} instance consumes and performs Soklet application request processing logic.
		 * <p>
		 * The {@link com.soklet.Soklet} instance will generate a {@link MarshaledResponse} for the request, which it "hands back" to the {@link Server} to be sent over the wire to the client.
		 *
		 * @param request                   a Soklet {@link Request} representation of the {@link Server}'s internal HTTP request data
		 * @param marshaledResponseConsumer invoked by {@link com.soklet.Soklet} when it's time for the {@link Server} to write HTTP response data to the client
		 */
		void handleRequest(@Nonnull Request request,
											 @Nonnull Consumer<MarshaledResponse> marshaledResponseConsumer);
	}
}
