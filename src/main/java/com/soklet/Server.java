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
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Contract for HTTP server implementations that are designed to be managed by a {@link com.soklet.Soklet} instance.
 * <p>
 * <strong>Most Soklet applications will use the default {@link Server} (constructed via the {@link #withPort(Integer)} builder factory method) and therefore do not need to implement this interface directly.</strong>
 * <p>
 * For example:
 * <pre>{@code  SokletConfig config = SokletConfig.withServer(
 *   Server.withPort(8080).build()
 * ).build();
 *
 * try (Soklet soklet = Soklet.withConfig(config)) {
 *   soklet.start();
 *   System.out.println("Soklet started, press [enter] to exit");
 *   soklet.awaitShutdown(ShutdownTrigger.ENTER_KEY);
 * }}</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface Server extends AutoCloseable {
	/**
	 * Starts the server, which makes it able to accept requests from clients.
	 * <p>
	 * If the server is already started, no action is taken.
	 * <p>
	 * <strong>This method is designed for internal use by {@link com.soklet.Soklet} only and should not be invoked elsewhere.</strong>
	 */
	void start();

	/**
	 * Stops the server, which makes it unable to accept requests from clients.
	 * <p>
	 * If the server is already stopped, no action is taken.
	 * <p>
	 * <strong>This method is designed for internal use by {@link com.soklet.Soklet} only and should not be invoked elsewhere.</strong>
	 */
	void stop();

	/**
	 * Is this server started (that is, able to handle requests from clients)?
	 *
	 * @return {@code true} if the server is started, {@code false} otherwise
	 */
	@NonNull
	Boolean isStarted();

	/**
	 * The {@link com.soklet.Soklet} instance which manages this {@link Server} will invoke this method exactly once at initialization time - this allows {@link com.soklet.Soklet} to "talk" to your {@link Server}.
	 * <p>
	 * <strong>This method is designed for internal use by {@link com.soklet.Soklet} only and should not be invoked elsewhere.</strong>
	 *
	 * @param sokletConfig   configuration for the Soklet instance that controls this server
	 * @param requestHandler a {@link com.soklet.Soklet}-internal request handler which takes a {@link Server}-provided request as input and supplies a {@link MarshaledResponse} as output for the {@link Server} to write back to the client
	 */
	void initialize(@NonNull SokletConfig sokletConfig,
									@NonNull RequestHandler requestHandler);

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
	 * Request/response processing contract for {@link Server} implementations.
	 * <p>
	 * This is used internally by {@link com.soklet.Soklet} instances to "talk" to a {@link Server} via {@link Server#initialize(SokletConfig, RequestHandler)}.  It's the responsibility of the {@link Server} to implement HTTP mechanics: read bytes from the request, write bytes to the response, and so forth.
	 * <p>
	 * <strong>Most Soklet applications will use Soklet's default {@link Server} implementation and therefore do not need to implement this interface directly.</strong>
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
		 * @param request               a Soklet {@link Request} representation of the {@link Server}'s internal HTTP request data
		 * @param requestResultConsumer invoked by {@link com.soklet.Soklet} when it's time for the {@link Server} to write HTTP response data to the client
		 */
		void handleRequest(@NonNull Request request,
											 @NonNull Consumer<RequestResult> requestResultConsumer);
	}

	/**
	 * Acquires a builder for {@link Server} instances.
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
	 * Builder used to construct a standard implementation of {@link Server}.
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
		Integer concurrency;
		@Nullable
		Duration requestTimeout;
		@Nullable
		Duration requestHandlerTimeout;
		@Nullable
		Integer requestHandlerConcurrency;
		@Nullable
		Integer requestHandlerQueueCapacity;
		@Nullable
		Duration socketSelectTimeout;
		@Nullable
		Duration shutdownTimeout;
		@Nullable
		Integer maximumRequestSizeInBytes;
		@Nullable
		Integer requestReadBufferSizeInBytes;
		@Nullable
		Integer socketPendingConnectionLimit;
		@Nullable
		Integer maximumConnections;
		@Nullable
		MultipartParser multipartParser;
		@Nullable
		Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
		@Nullable
		IdGenerator<?> idGenerator;

		@NonNull
		private Builder(@NonNull Integer port) {
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
		public Builder concurrency(@Nullable Integer concurrency) {
			this.concurrency = concurrency;
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
		public Builder socketSelectTimeout(@Nullable Duration socketSelectTimeout) {
			this.socketSelectTimeout = socketSelectTimeout;
			return this;
		}

		@NonNull
		public Builder socketPendingConnectionLimit(@Nullable Integer socketPendingConnectionLimit) {
			this.socketPendingConnectionLimit = socketPendingConnectionLimit;
			return this;
		}

		@NonNull
		public Builder maximumConnections(@Nullable Integer maximumConnections) {
			this.maximumConnections = maximumConnections;
			return this;
		}

		@NonNull
		public Builder shutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

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
		public Builder multipartParser(@Nullable MultipartParser multipartParser) {
			this.multipartParser = multipartParser;
			return this;
		}

		@NonNull
		public Builder requestHandlerExecutorServiceSupplier(@Nullable Supplier<ExecutorService> requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier = requestHandlerExecutorServiceSupplier;
			return this;
		}

		@NonNull
		public Builder idGenerator(@Nullable IdGenerator<?> idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		@NonNull
		public Server build() {
			return new DefaultServer(this);
		}
	}
}
