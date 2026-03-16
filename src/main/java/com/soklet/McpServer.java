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
	void start();

	void stop();

	@NonNull
	Boolean isStarted();

	void initialize(@NonNull SokletConfig sokletConfig,
									@NonNull RequestHandler requestHandler);

	@NonNull
	McpHandlerResolver getHandlerResolver();

	@NonNull
	McpRequestAdmissionPolicy getRequestAdmissionPolicy();

	@NonNull
	McpRequestInterceptor getRequestInterceptor();

	@NonNull
	McpResponseMarshaler getResponseMarshaler();

	@NonNull
	McpCorsAuthorizer getCorsAuthorizer();

	@NonNull
	McpSessionStore getSessionStore();

	@NonNull
	IdGenerator<String> getIdGenerator();

	@Override
	default void close() throws Exception {
		stop();
	}

	@FunctionalInterface
	interface RequestHandler {
		void handleRequest(@NonNull Request request,
											 @NonNull Consumer<RequestResult> requestResultConsumer);
	}

	@NonNull
	static Builder withPort(@NonNull Integer port) {
		requireNonNull(port);
		return new Builder(port);
	}

	@NonNull
	static McpServer fromPort(@NonNull Integer port) {
		requireNonNull(port);
		return withPort(port).build();
	}

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
		public Builder handlerResolver(@Nullable McpHandlerResolver handlerResolver) {
			this.handlerResolver = handlerResolver;
			return this;
		}

		@NonNull
		public Builder requestAdmissionPolicy(@Nullable McpRequestAdmissionPolicy requestAdmissionPolicy) {
			this.requestAdmissionPolicy = requestAdmissionPolicy;
			return this;
		}

		@NonNull
		public Builder requestInterceptor(@Nullable McpRequestInterceptor requestInterceptor) {
			this.requestInterceptor = requestInterceptor;
			return this;
		}

		@NonNull
		public Builder responseMarshaler(@Nullable McpResponseMarshaler responseMarshaler) {
			this.responseMarshaler = responseMarshaler;
			return this;
		}

		@NonNull
		public Builder corsAuthorizer(@Nullable McpCorsAuthorizer corsAuthorizer) {
			this.corsAuthorizer = corsAuthorizer;
			return this;
		}

		@NonNull
		public Builder sessionStore(@Nullable McpSessionStore sessionStore) {
			this.sessionStore = sessionStore;
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
		public Builder requestHandlerExecutorServiceSupplier(@Nullable Supplier<ExecutorService> requestHandlerExecutorServiceSupplier) {
			this.requestHandlerExecutorServiceSupplier = requestHandlerExecutorServiceSupplier;
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
		public Builder concurrentConnectionLimit(@Nullable Integer concurrentConnectionLimit) {
			this.concurrentConnectionLimit = concurrentConnectionLimit;
			return this;
		}

		@NonNull
		public Builder connectionQueueCapacity(@Nullable Integer connectionQueueCapacity) {
			this.connectionQueueCapacity = connectionQueueCapacity;
			return this;
		}

		@NonNull
		public Builder shutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		@NonNull
		public Builder writeTimeout(@Nullable Duration writeTimeout) {
			this.writeTimeout = writeTimeout;
			return this;
		}

		@NonNull
		public Builder heartbeatInterval(@Nullable Duration heartbeatInterval) {
			this.heartbeatInterval = heartbeatInterval;
			return this;
		}

		@NonNull
		public Builder idGenerator(@Nullable IdGenerator<String> idGenerator) {
			this.idGenerator = idGenerator;
			return this;
		}

		@NonNull
		public McpServer build() {
			return new DefaultMcpServer(this);
		}
	}
}
