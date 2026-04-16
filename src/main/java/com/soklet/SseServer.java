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

import javax.annotation.concurrent.NotThreadSafe;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * A special HTTP server whose only purpose is to provide <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events">Server-Sent Event</a> functionality.
 * <p>
 * A Soklet application which supports Server-Sent Events will be configured with a {@link SseServer}.
 * A regular {@link HttpServer} is only required if the same application also serves ordinary HTTP <em>Resource Methods</em>.
 * <p>
 * For example:
 * <pre>{@code // Set up our SSE server
 * SseServer sseServer = SseServer.fromPort(8081);
 *
 * // Wire the SSE server into our config
 * SokletConfig config = SokletConfig.withSseServer(sseServer)
 *   .build();
 *
 * // Add .httpServer(HttpServer.fromPort(8080)) if you also serve ordinary HTTP resources
 *
 * // Run the app
 * try (Soklet soklet = Soklet.fromConfig(config)) {
 *   soklet.start();
 *   System.out.println("Soklet started, press [enter] to exit");
 *   soklet.awaitShutdown(ShutdownTrigger.ENTER_KEY);
 * }}</pre>
 * <p>
 * See <a href="https://www.soklet.com/docs/server-sent-events">https://www.soklet.com/docs/server-sent-events</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface SseServer extends AutoCloseable {
	/**
	 * Starts the SSE server, which makes it able to accept requests from clients.
	 * <p>
	 * If the server is already started, no action is taken.
	 * <p>
	 * <strong>This method is designed for internal use by {@link com.soklet.Soklet} only and should not be invoked elsewhere.</strong>
	 */
	void start();

	/**
	 * Stops the SSE server, which makes it unable to accept requests from clients.
	 * <p>
	 * If the server is already stopped, no action is taken.
	 * <p>
	 * <strong>This method is designed for internal use by {@link com.soklet.Soklet} only and should not be invoked elsewhere.</strong>
	 */
	void stop();

	/**
	 * Is this SSE server started (that is, able to handle requests from clients)?
	 *
	 * @return {@code true} if the server is started, {@code false} otherwise
	 */
	@NonNull
	Boolean isStarted();

	/**
	 * {@link AutoCloseable}-enabled synonym for {@link #stop()}.
	 * <p>
	 * <strong>This method is designed for internal use by {@link com.soklet.Soklet} only and should not be invoked elsewhere.</strong>
	 *
	 * @throws Exception if an exception occurs while stopping the server
	 */
	@Override
	default void close() throws Exception {
		stop();
	}

	/**
	 * Given a {@link ResourcePath} that corresponds to a <em>Resource Method</em> annotated with {@link com.soklet.annotation.SseEventSource}, acquire a {@link SseBroadcaster} which is capable of "pushing" messages to all connected Server-Sent Event clients.
	 * <p>
	 * When using the default {@link SseServer}, Soklet guarantees exactly one {@link SseBroadcaster} instance exists per {@link ResourcePath} (within the same JVM process).  Soklet is responsible for the creation and management of {@link SseBroadcaster} instances.
	 * <p>
	 * Your code should not hold long-lived references to {@link SseBroadcaster} instances (e.g. in a cache or instance variables) - the recommended usage pattern is to invoke {@link #acquireBroadcaster(ResourcePath)} every time you need a broadcaster reference.
	 * <p>
	 * See <a href="https://www.soklet.com/docs/server-sent-events">https://www.soklet.com/docs/server-sent-events</a> for detailed documentation.
	 *
	 * @param resourcePath the {@link com.soklet.annotation.SseEventSource}-annotated <em>Resource Method</em> for which to acquire a broadcaster
	 * @return a broadcaster for the given {@link ResourcePath}, or {@link Optional#empty()} if there is no broadcaster available
	 */
	@NonNull
	Optional<? extends SseBroadcaster> acquireBroadcaster(@Nullable ResourcePath resourcePath);

	/**
	 * The {@link com.soklet.Soklet} instance which manages this {@link SseServer} will invoke this method exactly once at initialization time - this allows {@link com.soklet.Soklet} to "talk" to your {@link SseServer}.
	 * <p>
	 * <strong>This method is designed for internal use by {@link com.soklet.Soklet} only and should not be invoked elsewhere.</strong>
	 *
	 * @param sokletConfig   configuration for the Soklet instance that controls this server
	 * @param requestHandler a {@link com.soklet.Soklet}-internal request handler which takes a {@link SseServer}-provided request as input and supplies a {@link MarshaledResponse} as output for the {@link SseServer} to write back to the client
	 */
	void initialize(@NonNull SokletConfig sokletConfig,
									@NonNull RequestHandler requestHandler);

	/**
	 * Request/response processing contract for {@link SseServer} implementations.
	 * <p>
	 * This is used internally by {@link com.soklet.Soklet} instances to "talk" to a {@link SseServer} via {@link SseServer#initialize(SokletConfig, RequestHandler)}.
	 * It's the responsibility of the {@link SseServer} to implement HTTP mechanics: read bytes from the request, write bytes to the response, and so forth.
	 * <p>
	 * <strong>Most Soklet applications will use Soklet's default {@link SseServer} implementation and therefore do not need to implement this interface directly.</strong>
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@FunctionalInterface
	interface RequestHandler {
		/**
		 * Callback to be invoked by a {@link SseServer} implementation after it has received a Server-Sent Event Source HTTP request but prior to writing initial data to the HTTP response.
		 * <p>
		 * <strong>Note: this method is only invoked during the initial request "handshake" - it is not called for subsequent Server-Sent Event stream writes performed via {@link SseBroadcaster#broadcastEvent(SseEvent)} invocations.</strong>
		 * <p>
		 * For example, when a Server-Sent Event Source HTTP request is received, you might immediately write an HTTP 200 OK response if all looks good, or reject with a 401 due to invalid credentials.
		 * That is the extent of the request-handling logic performed here.  The Server-Sent Event stream then remains open and can be written to via {@link SseBroadcaster#broadcastEvent(SseEvent)}.
		 * <p>
		 * The {@link SseServer} is responsible for converting its internal request representation into a {@link Request}, which a {@link com.soklet.Soklet} instance consumes and performs Soklet application request processing logic.
		 * <p>
		 * The {@link com.soklet.Soklet} instance will generate a {@link MarshaledResponse} for the request, which it "hands back" to the {@link SseServer} to be sent over the wire to the client.
		 *
		 * @param request               a Soklet {@link Request} representation of the {@link SseServer}'s internal HTTP request data
		 * @param requestResultConsumer invoked by {@link com.soklet.Soklet} when it's time for the {@link SseServer} to write HTTP response data to the client
		 */
		void handleRequest(@NonNull Request request,
											 @NonNull Consumer<HttpRequestResult> requestResultConsumer);
	}

	/**
	 * Acquires a builder for {@link SseServer} instances.
	 *
	 * @param port the port number on which the server should listen
	 * @return the builder
	 */
	@NonNull
	static Builder withPort(@NonNull Integer port) {
		requireNonNull(port);
		return new Builder(port);
	}

	/**
	 * Creates a {@link SseServer} configured with the given port and default settings.
	 *
	 * @param port the port number on which the server should listen
	 * @return a {@link SseServer} instance
	 */
	@NonNull
	static SseServer fromPort(@NonNull Integer port) {
		return withPort(port).build();
	}

	/**
	 * Builder used to construct a standard implementation of {@link SseServer}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	final class Builder {
		@NonNull
		Integer port;
		@Nullable
		String host;
		@Nullable
		Duration requestTimeout;
		@Nullable
		Duration requestHandlerTimeout;
		@Nullable
		Integer requestHandlerConcurrency;
		@Nullable
		Integer requestHandlerQueueCapacity;
		@Nullable
		Duration writeTimeout;
		@Nullable
		Duration shutdownTimeout;
		@Nullable
		Duration heartbeatInterval;
		@Nullable
		Integer maximumRequestSizeInBytes;
		@Nullable
		Integer requestReadBufferSizeInBytes;
		@Nullable
		Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
		@Nullable
		Integer concurrentConnectionLimit;
		@Nullable
		Integer broadcasterCacheCapacity;
		@Nullable
		Integer resourcePathCacheCapacity;
		@Nullable
		Integer connectionQueueCapacity;
		@Nullable
		Boolean verifyConnectionOnceEstablished;
		@Nullable
		IdGenerator<?> idGenerator;

		@NonNull
		protected Builder(@NonNull Integer port) {
			requireNonNull(port);
			this.port = port;
		}

		@NonNull
		public Builder port(@NonNull Integer port) {
			requireNonNull(port);
			this.port = port;
			return this;
		}

		@NonNull
		public Builder host(@Nullable String host) {
			this.host = host;
			return this;
		}

		@NonNull
		public Builder requestTimeout(@Nullable Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		@NonNull
		public Builder requestHandlerTimeout(@Nullable Duration requestHandlerTimeout) {
			this.requestHandlerTimeout = requestHandlerTimeout;
			return this;
		}

		@NonNull
		public Builder requestHandlerConcurrency(@Nullable Integer requestHandlerConcurrency) {
			this.requestHandlerConcurrency = requestHandlerConcurrency;
			return this;
		}

		@NonNull
		public Builder requestHandlerQueueCapacity(@Nullable Integer requestHandlerQueueCapacity) {
			this.requestHandlerQueueCapacity = requestHandlerQueueCapacity;
			return this;
		}

		@NonNull
		public Builder writeTimeout(@Nullable Duration writeTimeout) {
			this.writeTimeout = writeTimeout;
			return this;
		}

		@NonNull
		public Builder shutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		@NonNull
		public Builder heartbeatInterval(@Nullable Duration heartbeatInterval) {
			this.heartbeatInterval = heartbeatInterval;
			return this;
		}

		/**
		 * Sets the maximum accepted SSE handshake request size in bytes.
		 * <p>
		 * This limit applies to the whole received handshake request, including request line
		 * and headers. Established SSE stream writes are governed by the write timeout and
		 * connection queue capacity settings instead.
		 *
		 * @param maximumRequestSizeInBytes the maximum handshake request size, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumRequestSizeInBytes(@Nullable Integer maximumRequestSizeInBytes) {
			this.maximumRequestSizeInBytes = maximumRequestSizeInBytes;
			return this;
		}

		@NonNull
		public Builder requestReadBufferSizeInBytes(@Nullable Integer requestReadBufferSizeInBytes) {
			this.requestReadBufferSizeInBytes = requestReadBufferSizeInBytes;
			return this;
		}

		@NonNull
		public Builder requestHandlerExecutorServiceSupplier(@Nullable Supplier<ExecutorService> requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier = requestHandlerExecutorServiceSupplier;
			return this;
		}

		@NonNull
		public Builder concurrentConnectionLimit(@Nullable Integer concurrentConnectionLimit) {
			this.concurrentConnectionLimit = concurrentConnectionLimit;
			return this;
		}

		@NonNull
		public Builder broadcasterCacheCapacity(@Nullable Integer broadcasterCacheCapacity) {
			this.broadcasterCacheCapacity = broadcasterCacheCapacity;
			return this;
		}

		@NonNull
		public Builder resourcePathCacheCapacity(@Nullable Integer resourcePathCacheCapacity) {
			this.resourcePathCacheCapacity = resourcePathCacheCapacity;
			return this;
		}

		@NonNull
		public Builder connectionQueueCapacity(@Nullable Integer connectionQueueCapacity) {
			this.connectionQueueCapacity = connectionQueueCapacity;
			return this;
		}

		@NonNull
		public Builder verifyConnectionOnceEstablished(@Nullable Boolean verifyConnectionOnceEstablished) {
			this.verifyConnectionOnceEstablished = verifyConnectionOnceEstablished;
			return this;
		}

		@NonNull
		public Builder idGenerator(@Nullable IdGenerator<?> idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		@NonNull
		public SseServer build() {
			return new DefaultSseServer(this);
		}
	}
}
