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

package com.soklet.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Simulates server behavior of accepting a request and returning a response, useful for writing integration tests.
 * <p>
 * <a href="https://www.soklet.com/docs/server-sent-events">Server-Sent Event</a> simulation is also supported.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/automated-testing">https://www.soklet.com/docs/automated-testing</a>.
 * <p>
 * For example:
 * <pre>{@code  @Test
 * public void basicIntegrationTest() {
 *   // Build your app's configuration however you like
 *   SokletConfiguration config = obtainMySokletConfig();
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
 *     // Perform the request and get a handle to the response
 *     MarshaledResponse marshaledResponse = simulator.performRequest(request);
 *
 *     // Verify status code
 *     Integer expectedCode = 200;
 *     Integer actualCode = response.getStatusCode();
 *     Assert.assertEquals("Bad status code", expectedCode, actualCode);
 *   }));
 * }}</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface Simulator {
	/**
	 * Given a request, process it and return a response ready to be written back over the wire.
	 *
	 * @param request the request to process
	 * @return the response that corresponds to the request
	 */
	@Nonnull
	MarshaledResponse performRequest(@Nonnull Request request);

	void registerServerSentEventConsumer(@Nonnull ResourcePath resourcePath,
																			 @Nonnull Consumer<ServerSentEvent> serverSentEventConsumer);

	@Nonnull
	Optional<? extends ServerSentEventBroadcaster> acquireServerSentEventBroadcaster(@Nullable ResourcePath resourcePath);
}
