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
 * Contract for MCP server implementations that are designed to be managed by a {@link Soklet} instance.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface McpServer extends AutoCloseable {
	/**
	 * Starts the MCP server.
	 */
	void start();

	/**
	 * Stops the MCP server.
	 */
	void stop();

	/**
	 * Indicates whether the server has been started.
	 *
	 * @return {@code true} if the server is started
	 */
	@NonNull
	Boolean isStarted();

	/**
	 * Initializes the server with Soklet-owned infrastructure.
	 *
	 * @param sokletConfig the owning Soklet configuration
	 * @param requestHandler the request handler callback Soklet will invoke for MCP requests
	 */
	void initialize(@NonNull SokletConfig sokletConfig,
									@NonNull RequestHandler requestHandler);

	/**
	 * Provides the MCP handler resolver.
	 *
	 * @return the handler resolver
	 */
	@NonNull
	McpHandlerResolver getHandlerResolver();

	/**
	 * Provides the request admission policy.
	 *
	 * @return the request admission policy
	 */
	@NonNull
	McpRequestAdmissionPolicy getRequestAdmissionPolicy();

	/**
	 * Provides the request interceptor.
	 *
	 * @return the request interceptor
	 */
	@NonNull
	McpRequestInterceptor getRequestInterceptor();

	/**
	 * Provides the response marshaler used for tool structured content.
	 *
	 * @return the response marshaler
	 */
	@NonNull
	McpResponseMarshaler getResponseMarshaler();

	/**
	 * Provides the MCP CORS authorizer.
	 *
	 * @return the CORS authorizer
	 */
	@NonNull
	McpCorsAuthorizer getCorsAuthorizer();

	/**
	 * Provides the session store.
	 *
	 * @return the session store
	 */
	@NonNull
	McpSessionStore getSessionStore();

	/**
	 * Provides the generator used for MCP session IDs.
	 *
	 * @return the session ID generator
	 */
	@NonNull
	IdGenerator<String> getIdGenerator();

	@Override
	default void close() throws Exception {
		stop();
	}

	/**
	 * Request callback used by {@link Soklet} to hand transport requests to an initialized MCP server.
	 */
	@FunctionalInterface
	interface RequestHandler {
		/**
		 * Handles an MCP transport request.
		 *
		 * @param request the transport request
		 * @param requestResultConsumer the consumer that should receive the completed request result
		 */
		void handleRequest(@NonNull Request request,
											 @NonNull Consumer<HttpRequestResult> requestResultConsumer);
	}

	/**
	 * Acquires a builder configured with the given port.
	 *
	 * @param port the port to bind
	 * @return a new MCP server builder
	 */
	@NonNull
	static Builder withPort(@NonNull Integer port) {
		requireNonNull(port);
		return new Builder(port);
	}

	/**
	 * Creates a default MCP server bound to the given port.
	 *
	 * @param port the port to bind
	 * @return the built MCP server
	 */
	@NonNull
	static McpServer fromPort(@NonNull Integer port) {
		requireNonNull(port);
		return withPort(port).build();
	}

	/**
	 * Builder for {@link McpServer} instances.
	 */
	@NotThreadSafe
	final class Builder {
		@NonNull
		Integer port;
		@Nullable
		String host;
		@Nullable
		McpHandlerResolver handlerResolver;
		@Nullable
		McpRequestAdmissionPolicy requestAdmissionPolicy;
		@Nullable
		McpRequestInterceptor requestInterceptor;
		@Nullable
		McpResponseMarshaler responseMarshaler;
		@Nullable
		McpCorsAuthorizer corsAuthorizer;
		@Nullable
		McpSessionStore sessionStore;
		@Nullable
		Duration requestTimeout;
		@Nullable
		Duration requestHandlerTimeout;
		@Nullable
		Integer requestHandlerConcurrency;
		@Nullable
		Integer requestHandlerQueueCapacity;
		@Nullable
		Supplier<ExecutorService> requestHandlerExecutorServiceSupplier;
		@Nullable
		Integer maximumRequestSizeInBytes;
		@Nullable
		Integer requestReadBufferSizeInBytes;
		@Nullable
		Integer concurrentConnectionLimit;
		@Nullable
		Integer connectionQueueCapacity;
		@Nullable
		Duration shutdownTimeout;
		@Nullable
		Duration writeTimeout;
		@Nullable
		Duration heartbeatInterval;
		@Nullable
		IdGenerator<String> idGenerator;

		private Builder(@NonNull Integer port) {
			requireNonNull(port);
			this.port = port;
		}

		/**
		 * Sets the port to bind.
		 *
		 * @param port the port
		 * @return this builder
		 */
		@NonNull
		public Builder port(@NonNull Integer port) {
			requireNonNull(port);
			this.port = port;
			return this;
		}

		/**
		 * Sets the host to bind, or {@code null} to use the server default.
		 *
		 * @param host the host to bind
		 * @return this builder
		 */
		@NonNull
		public Builder host(@Nullable String host) {
			this.host = host;
			return this;
		}

		/**
		 * Sets the handler resolver.
		 *
		 * @param handlerResolver the handler resolver
		 * @return this builder
		 */
		@NonNull
		public Builder handlerResolver(@Nullable McpHandlerResolver handlerResolver) {
			this.handlerResolver = handlerResolver;
			return this;
		}

		/**
		 * Sets the request admission policy.
		 *
		 * @param requestAdmissionPolicy the admission policy
		 * @return this builder
		 */
		@NonNull
		public Builder requestAdmissionPolicy(@Nullable McpRequestAdmissionPolicy requestAdmissionPolicy) {
			this.requestAdmissionPolicy = requestAdmissionPolicy;
			return this;
		}

		/**
		 * Sets the request interceptor.
		 *
		 * @param requestInterceptor the request interceptor
		 * @return this builder
		 */
		@NonNull
		public Builder requestInterceptor(@Nullable McpRequestInterceptor requestInterceptor) {
			this.requestInterceptor = requestInterceptor;
			return this;
		}

		/**
		 * Sets the response marshaler used for tool structured content.
		 *
		 * @param responseMarshaler the response marshaler
		 * @return this builder
		 */
		@NonNull
		public Builder responseMarshaler(@Nullable McpResponseMarshaler responseMarshaler) {
			this.responseMarshaler = responseMarshaler;
			return this;
		}

		/**
		 * Sets the MCP CORS authorizer.
		 *
		 * @param corsAuthorizer the CORS authorizer
		 * @return this builder
		 */
		@NonNull
		public Builder corsAuthorizer(@Nullable McpCorsAuthorizer corsAuthorizer) {
			this.corsAuthorizer = corsAuthorizer;
			return this;
		}

		/**
		 * Sets the session store.
		 *
		 * @param sessionStore the session store
		 * @return this builder
		 */
		@NonNull
		public Builder sessionStore(@Nullable McpSessionStore sessionStore) {
			this.sessionStore = sessionStore;
			return this;
		}

		/**
		 * Sets the end-to-end request timeout.
		 *
		 * @param requestTimeout the request timeout
		 * @return this builder
		 */
		@NonNull
		public Builder requestTimeout(@Nullable Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the timeout for MCP handler execution.
		 *
		 * @param requestHandlerTimeout the handler timeout
		 * @return this builder
		 */
		@NonNull
		public Builder requestHandlerTimeout(@Nullable Duration requestHandlerTimeout) {
			this.requestHandlerTimeout = requestHandlerTimeout;
			return this;
		}

		/**
		 * Sets the handler concurrency level.
		 *
		 * @param requestHandlerConcurrency the handler concurrency
		 * @return this builder
		 */
		@NonNull
		public Builder requestHandlerConcurrency(@Nullable Integer requestHandlerConcurrency) {
			this.requestHandlerConcurrency = requestHandlerConcurrency;
			return this;
		}

		/**
		 * Sets the handler queue capacity.
		 *
		 * @param requestHandlerQueueCapacity the handler queue capacity
		 * @return this builder
		 */
		@NonNull
		public Builder requestHandlerQueueCapacity(@Nullable Integer requestHandlerQueueCapacity) {
			this.requestHandlerQueueCapacity = requestHandlerQueueCapacity;
			return this;
		}

		/**
		 * Sets the supplier used to create the handler executor service.
		 *
		 * @param requestHandlerExecutorServiceSupplier the executor service supplier
		 * @return this builder
		 */
		@NonNull
		public Builder requestHandlerExecutorServiceSupplier(@Nullable Supplier<ExecutorService> requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier = requestHandlerExecutorServiceSupplier;
			return this;
		}

		/**
		 * Sets the maximum accepted MCP request size in bytes.
		 * <p>
		 * The MCP transport rejects a header section larger than this limit and rejects
		 * requests whose declared body size is larger than this limit.
		 *
		 * @param maximumRequestSizeInBytes the maximum request size, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumRequestSizeInBytes(@Nullable Integer maximumRequestSizeInBytes) {
			this.maximumRequestSizeInBytes = maximumRequestSizeInBytes;
			return this;
		}

		/**
		 * Sets the read buffer size used while reading MCP requests.
		 *
		 * @param requestReadBufferSizeInBytes the read buffer size
		 * @return this builder
		 */
		@NonNull
		public Builder requestReadBufferSizeInBytes(@Nullable Integer requestReadBufferSizeInBytes) {
			this.requestReadBufferSizeInBytes = requestReadBufferSizeInBytes;
			return this;
		}

		/**
		 * Sets the concurrent connection limit for the MCP server.
		 *
		 * @param concurrentConnectionLimit the concurrent connection limit
		 * @return this builder
		 */
		@NonNull
		public Builder concurrentConnectionLimit(@Nullable Integer concurrentConnectionLimit) {
			this.concurrentConnectionLimit = concurrentConnectionLimit;
			return this;
		}

		/**
		 * Sets the outbound queue capacity for live MCP streams.
		 *
		 * @param connectionQueueCapacity the outbound queue capacity
		 * @return this builder
		 */
		@NonNull
		public Builder connectionQueueCapacity(@Nullable Integer connectionQueueCapacity) {
			this.connectionQueueCapacity = connectionQueueCapacity;
			return this;
		}

		/**
		 * Sets the shutdown timeout.
		 *
		 * @param shutdownTimeout the shutdown timeout
		 * @return this builder
		 */
		@NonNull
		public Builder shutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		/**
		 * Sets the transport write timeout.
		 *
		 * @param writeTimeout the write timeout
		 * @return this builder
		 */
		@NonNull
		public Builder writeTimeout(@Nullable Duration writeTimeout) {
			this.writeTimeout = writeTimeout;
			return this;
		}

		/**
		 * Sets the heartbeat interval for long-lived MCP event streams.
		 *
		 * @param heartbeatInterval the heartbeat interval
		 * @return this builder
		 */
		@NonNull
		public Builder heartbeatInterval(@Nullable Duration heartbeatInterval) {
			this.heartbeatInterval = heartbeatInterval;
			return this;
		}

		/**
		 * Sets the generator used for MCP session IDs.
		 *
		 * @param idGenerator the session ID generator
		 * @return this builder
		 */
		@NonNull
		public Builder idGenerator(@Nullable IdGenerator<String> idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		/**
		 * Builds the MCP server.
		 *
		 * @return the built MCP server
		 */
		@NonNull
		public McpServer build() {
			return new DefaultMcpServer(this);
		}
	}
}
