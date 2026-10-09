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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Simulates server behavior of accepting a request and returning a response without touching the network, useful for writing integration tests.
 * <p>
 * <a href="https://www.soklet.com/docs/server-sent-events">Server-Sent Event</a> simulation is also supported.
 * <p>
 * Instances of {@link Simulator} are made available through
 * {@link SokletSimulator#run(SokletConfig, SokletSimulator.Simulation)} when
 * testing an existing application configuration, or through
 * {@link SokletSimulator#run(SimulatorConfig, SokletSimulator.Simulation)} for
 * a standalone simulator configuration.
 * <p>
 * Usage example:
 * <pre>{@code @Test
 * public void basicIntegrationTest () {
 *   // With the Simulator, you can issue requests
 *   // and receive responses just like you would with real servers.
 *   SimulatorConfig simulatorConfig = SimulatorConfig.builder()
 *     .httpServer()
 *     .sseServer()
 *     .build();
 *   SokletSimulator.run(simulatorConfig,
 *     simulator -> {
 *     // Construct a request
 *     Request request = Request.withPath(HttpMethod.GET, "/hello")
 *       .queryParameters(Map.of("name", List.of("Mark")))
 *       .build();
 *
 *     // Perform the request and get a handle to the result
 *     HttpRequestResult result = simulator.performHttpRequest(request);
 *
 *     // Verify status code
 *     Integer expectedCode = 200;
 *     Integer actualCode = result.getMarshaledResponse().getStatusCode();
 *     assertEquals(expectedCode, actualCode, "Bad status code");
 *
 *     // Now, create a request for an SSE Event Source...
 *     Request eventSourceRequest = Request.withPath(HttpMethod.GET, "/sse-test")
 *         .queryParameters(Map.of("signingToken", List.of("xxx")))
 *         .build();
 *
 *     // ...and perform it and get a handle to the result.
 *     SseRequestResult eventSourceResult =
 *       simulator.performSseRequest(eventSourceRequest);
 *
 *     // Single-shot latch; we'll wait until a Server-Sent Event comes through
 *     CountDownLatch eventReceivedLatch = new CountDownLatch(1);
 *
 *     // The Simulator provides 3 logical outcomes for SSE connections:
 *     // * Accepted Handshake (connection stays open)
 *     // * Rejected Handshake (explicit rejection, connection closed)
 *     // * Request Failed (implicit rejection, e.g. uncaught exception, connection closed)
 *     switch (eventSourceResult) {
 *       // Explicit handshake acceptance
 *       case HandshakeAccepted handshakeAccepted -> {
 *         handshakeAccepted.registerEventConsumer((event) -> {
 *           // Server-Sent Event received: open the latch to end the test
 *           eventReceivedLatch.countDown();
 *         });
 *
 *         // On a separate thread, broadcast a Server-Sent Event
 *         new Thread(() -> {
 *           // ... not shown
 *         }).start();
 *       }
 *
 *       // Explicit handshake rejection
 *       case HandshakeRejected handshakeRejected ->
 *         Assertions.fail("SSE Handshake Rejected: " + handshakeRejected);
 *
 *       // Uncaught exception
 *       case RequestFailed requestFailed ->
 *         Assertions.fail("SSE Request Failed: " + requestFailed);
 *     }
 *
 *     // Finally, wait a bit for the latch to open
 *     try {
 *       Assertions.assertTrue(eventReceivedLatch.await(5, SECONDS),
 *         "Didn't receive a Server-Sent Event in time");
 *     } catch (InterruptedException e) {
 *       Thread.currentThread().interrupt();
 *       Assertions.fail("Interrupted while waiting for a Server-Sent Event", e);
 *     }
 *     });
 * }}</pre>
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/testing">https://www.soklet.com/docs/testing</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface Simulator {
	/**
	 * Returns the simulated HTTP server selected by this run's configuration.
	 *
	 * @return HTTP server, or the empty optional when HTTP was not configured
	 * @throws IllegalStateException if the simulation scope is closed
	 */
	@NonNull
	Optional<@NonNull HttpServer> getHttpServer();

	/**
	 * Returns the simulated Server-Sent Events server selected by this run's
	 * configuration.
	 *
	 * @return SSE server, or the empty optional when SSE was not configured
	 * @throws IllegalStateException if the simulation scope is closed
	 */
	@NonNull
	Optional<@NonNull SseServer> getSseServer();

	/**
	 * Returns the simulated MCP server selected by this run's configuration.
	 *
	 * @return MCP server, or the empty optional when MCP was not configured
	 * @throws IllegalStateException if the simulation scope is closed
	 */
	@NonNull
	Optional<@NonNull McpServer> getMcpServer();

	/**
	 * Starts an asynchronous, off-network MCP HTTP simulation using default
	 * bounded capture options. Supports POST and, on session-enabled 2025
	 * endpoints with {@link McpSessionConfig.Builder#transportAdmissionController}
	 * configured, GET and DELETE, as well as OPTIONS preflight requests. GET also
	 * requires enabled notification families and their effective sources.
	 *
	 * @param request request to simulate
	 * @return simulation handle
	 * @throws NullPointerException if {@code request} is null
	 * @throws IllegalStateException if no MCP server is configured or the simulation scope is closed
	 */
	@NonNull
	McpSimulation startMcpRequest(@NonNull Request request);

	/**
	 * Starts an asynchronous, off-network MCP HTTP simulation. Supports POST
	 * and, on session-enabled 2025 endpoints with
	 * {@link McpSessionConfig.Builder#transportAdmissionController} configured,
	 * GET and DELETE, as well as OPTIONS preflight requests. GET also requires
	 * enabled notification families and their effective sources.
	 *
	 * @param request request to simulate
	 * @param options bounded response-capture options
	 * @return simulation handle
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalStateException if no MCP server is configured or the simulation scope is closed
	 */
	@NonNull
	McpSimulation startMcpRequest(@NonNull Request request,
			@NonNull McpSimulationOptions options);

	/**
	 * Given a request that would normally be handled by your standard {@link HttpServer}, process it and return response data (both logical {@link Response}, if present, and the {@link MarshaledResponse} bytes to be sent over the wire) as well as the matching <em>Resource Method</em>, if available.
	 * <p>
	 * To make requests that would normally be handled by your {@link SseServer}, use {@link #performSseRequest(Request)}.
	 * <p>
	 * Each invocation processes a fresh copy of {@code request}, preserving its ID and other values.
	 * Paired lifecycle and metrics callbacks, including HTTP stream handles, share that dispatch copy,
	 * so the same caller-supplied request may be reused concurrently without sharing observation identity.
	 * <p>
	 * Streaming producers run synchronously on the calling thread and successful
	 * output is materialized into bytes. HTTP streaming total/idle timeout settings
	 * are not applied: the stream's deadline and idle timeout are empty. Cleanup
	 * supervision, scope shutdown and output-capture limits still apply. Use the
	 * real HTTP server to test response deadlines and committed partial delivery.
	 * <p>
	 * An admitted call waits for its termination observer to finish. A producer
	 * failure that wins termination surfaces as an {@link IllegalStateException}
	 * with the original cause instead of returning a partial result; an application
	 * {@link Error} is rethrown when it wins that outcome. An earlier elected
	 * cancelation still wins.
	 * A blocked producer or observer can therefore block this synchronous call.
	 * <p>
	 * Exhausted HTTP streaming admission returns the built-in finite {@code 503}
	 * response with its plain-text body and {@code Connection: close} header. Its
	 * logical response is absent and its resource method remains available.
	 * {@code didWriteResponse} and {@code didFinishRequestHandling} observers and
	 * metrics receive that finite response. {@code willWriteResponse} sees the
	 * original stream before admission, as it does in HTTP. The rejected producer
	 * is not acquired. Stream rejection observation retains the original
	 * stream descriptor and uses bounded asynchronous delivery without delaying this call.
	 *
	 * @param request the standard HTTP request to process
	 * @return the result (logical response, marshaled response, etc.) that corresponds to the request
	 */
	@NonNull
	HttpRequestResult performHttpRequest(@NonNull Request request);

	/**
	 * Given a request that would normally be handled by your {@link SseServer} (that is, for a <em>Resource Method</em> decorated with the {@link com.soklet.annotation.SseEventSource} annotation), process it and return response data ({@link com.soklet.SseRequestResult.HandshakeAccepted}, {@link com.soklet.SseRequestResult.HandshakeRejected}, or {@link com.soklet.SseRequestResult.RequestFailed});
	 * <p>
	 * To make requests that would normally be handled by your {@link HttpServer}, use {@link #performHttpRequest(Request)}.
	 * <p>
	 * Each invocation processes a fresh copy of {@code request}, preserving its ID and other values.
	 * Paired lifecycle and metrics callbacks share that dispatch copy, even when the caller reuses the
	 * same request concurrently.
	 * <p>
	 * On an accepted connection, the first event or comment consumer starts simulated reading. Later payloads
	 * of unregistered types are discarded; the bounded capture from before reading remains available to later
	 * consumers. Pending deliveries to registered consumers have a separate combined queue-capacity bound.
	 * See {@link SseRequestResult.HandshakeAccepted} for capture limits and registration semantics.
	 * <p>
	 * SSE establishment and termination notifications use the configured {@link LifecycleObserver} and
	 * {@link MetricsCollector}. Terminal notifications run asynchronously; simulator teardown waits within
	 * its lifecycle budgets. Failed handshakes use a separate bounded observation allowance; notifications
	 * beyond that allowance are omitted with a log event. No socket-write callbacks or wire metrics are simulated.
	 *
	 * @param request the Server-Sent Event HTTP request to process
	 * @return the result (handshake outcode, etc.) that corresponds to the request
	 * @throws IllegalStateException if an accepted initializer throws a checked
	 *         exception, overflows its bounded queue, or terminates before activation;
	 *         initializer RuntimeExceptions and Errors propagate unchanged. Accepted
	 *         headers alone do not guarantee a returned active simulated connection
	 */
	@NonNull
	SseRequestResult performSseRequest(@NonNull Request request);

	/**
	 * Registers a handler for exceptions from simulated Server-Sent Event broadcasts.
	 * <p>
	 * This only applies to simulator-mode SSE broadcasts. A memoized provider failure, including a forbidden null
	 * payload, is reported once per key per broadcast. Key-selector and consumer failures remain per client.
	 * A consumer failure does not invalidate a payload shared with other clients. A later broadcast can retry a
	 * failed provider key. Without a handler, or if it throws, Soklet logs the original failure.
	 *
	 * @param onBroadcastError handler for broadcast errors, or {@code null} to clear
	 * @return this simulator
	 */
	@NonNull
	default Simulator onBroadcastError(
			@Nullable Consumer<@NonNull Throwable> onBroadcastError) {
		return this;
	}

	/**
	 * Registers a handler for exceptions thrown by simulated Server-Sent Event unicast consumers.
	 * <p>
	 * This only applies to simulator-mode SSE unicast deliveries (including client initializers).
	 *
	 * @param onUnicastError handler for unicast errors, or {@code null} to clear
	 * @return this simulator
	 */
	@NonNull
	default Simulator onUnicastError(
			@Nullable Consumer<@NonNull Throwable> onUnicastError) {
		return this;
	}

}
