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
import java.util.function.Consumer;

/**
 * Simulates server behavior of accepting a request and returning a response, useful for writing integration tests.
 * <p>
 * <a href="https://www.soklet.com/docs/server-sent-events">Server-Sent Event</a> simulation is also supported.
 * <p>
 * Instances of {@link Simulator} are made available via {@link com.soklet.Soklet#runSimulator(SokletConfig, Consumer)}.
 * <p>
 * Usage example:
 * <pre>{@code @Test
 * public void basicIntegrationTest() {
 *   // Build your configuration however you like
 *   SokletConfig config = obtainMySokletConfig();
 *
 *   // Instead of running on a real HTTP server that listens on a port,
 *   // a simulator is provided against which you can issue requests
 *   // and receive responses.
 *   Soklet.runSimulator(config, (simulator -> {
 *     // Construct a request.
 *     // You may alternatively specify query parameters directly in the URI
 *     // as a query string, e.g. "/hello?name=Mark"
 *     Request request = Request.with(HttpMethod.GET, "/hello")
 *       .queryParameters(Map.of("name", Set.of("Mark")))
 *       .build();
 *
 *     // Perform the request and get a handle to the result
 *     RequestResult result = simulator.performRequest(request);
 *
 *     // Verify status code
 *     Integer expectedCode = 200;
 *     Integer actualCode = result.getMarshaledResponse().getStatusCode();
 *     Assert.assertEquals("Bad status code", expectedCode, actualCode);
 *   }));
 * }}</pre>
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/automated-testing">https://www.soklet.com/docs/automated-testing</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface Simulator {
	/**
	 * Given a request, process it and return response data (both logical {@link Response}, if present, and the {@link MarshaledResponse} bytes to be sent over the wire) as well as the matching <em>Resource Method</em>, if available.
	 *
	 * @param request the request to process
	 * @return the result (logical response, marshaled response, etc.) that corresponds to the request
	 */
	@Nonnull
	RequestResult performRequest(@Nonnull Request request);

	/**
	 * Registers {@link ServerSentEvent} data "consumers" for the given {@link ResourcePath} - similar to how a real client would listen for Server-Sent Events and comments.
	 * <p>
	 * See documentation at <a href="https://www.soklet.com/docs/server-sent-events#testing">https://www.soklet.com/docs/server-sent-events#testing</a>.
	 *
	 * @param resourcePath    the Resource Path on which to listen for Server-Sent Events
	 * @param eventConsumer   function to be invoked when a Server-Sent Event has been unicast/broadcast on the Resource Path
	 * @param commentConsumer function to be invoked when a Server-Sent Event comment has been unicast/broadcast on the Resource Path
	 */
	void registerServerSentEventConsumers(@Nonnull ResourcePath resourcePath,
																				@Nonnull Consumer<ServerSentEvent> eventConsumer,
																				@Nonnull Consumer<String> commentConsumer);

	/**
	 * Acquires a Server-Sent Event broadcaster for the given {@link ResourcePath}.
	 * <p>
	 * See documentation at <a href="https://www.soklet.com/docs/server-sent-events#testing">https://www.soklet.com/docs/server-sent-events#testing</a>.
	 *
	 * @param resourcePath the Resource Path on which to broadcast Server-Sent Events
	 * @return a Server-Sent Event broadcaster
	 */
	@Nonnull
	ServerSentEventBroadcaster acquireServerSentEventBroadcaster(@Nonnull ResourcePath resourcePath);
}
