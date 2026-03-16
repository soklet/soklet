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
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpServer implements McpServer {
	@NonNull
	private final Server delegate;
	@NonNull
	private final McpHandlerResolver handlerResolver;
	@NonNull
	private final McpRequestAdmissionPolicy requestAdmissionPolicy;
	@NonNull
	private final McpRequestInterceptor requestInterceptor;
	@NonNull
	private final McpResponseMarshaler responseMarshaler;
	@NonNull
	private final McpOriginPolicy originPolicy;
	@NonNull
	private final McpSessionStore sessionStore;
	@NonNull
	private final IdGenerator<String> idGenerator;

	DefaultMcpServer(McpServer.Builder builder) {
		requireNonNull(builder);

		if (builder.handlerResolver == null)
			throw new IllegalStateException("You must specify an McpHandlerResolver when building an McpServer.");

		this.handlerResolver = builder.handlerResolver;
		this.requestAdmissionPolicy = builder.requestAdmissionPolicy != null ? builder.requestAdmissionPolicy : McpRequestAdmissionPolicy.defaultInstance();
		this.requestInterceptor = builder.requestInterceptor != null ? builder.requestInterceptor : new McpRequestInterceptor() {};
		this.responseMarshaler = builder.responseMarshaler != null ? builder.responseMarshaler : McpResponseMarshaler.defaultInstance();
		this.originPolicy = builder.originPolicy != null ? builder.originPolicy : McpOriginPolicy.nonBrowserClientsOnlyInstance();
		this.sessionStore = builder.sessionStore != null ? builder.sessionStore : McpSessionStore.fromInMemory();
		this.idGenerator = builder.idGenerator != null ? builder.idGenerator : IdGenerator.defaultInstance();

		Server.Builder serverBuilder = Server.withPort(builder.port)
				.host(builder.host)
				.requestTimeout(builder.requestTimeout)
				.requestHandlerTimeout(builder.requestHandlerTimeout)
				.requestHandlerConcurrency(builder.requestHandlerConcurrency)
				.requestHandlerQueueCapacity(builder.requestHandlerQueueCapacity)
				.maximumRequestSizeInBytes(builder.maximumRequestSizeInBytes)
				.requestReadBufferSizeInBytes(builder.requestReadBufferSizeInBytes)
				.maximumConnections(builder.concurrentConnectionLimit)
				.shutdownTimeout(builder.shutdownTimeout)
				.idGenerator(this.idGenerator);

		Supplier<ExecutorService> requestHandlerExecutorServiceSupplier = builder.requestHandlerExecutorServiceSupplier;

		if (requestHandlerExecutorServiceSupplier != null)
			serverBuilder.requestHandlerExecutorServiceSupplier(requestHandlerExecutorServiceSupplier);

		this.delegate = serverBuilder.build();
	}

	@Override
	public void start() {
		this.delegate.start();
	}

	@Override
	public void stop() {
		this.delegate.stop();
	}

	@NonNull
	@Override
	public Boolean isStarted() {
		return this.delegate.isStarted();
	}

	@Override
	public void initialize(@NonNull SokletConfig sokletConfig,
												 @NonNull RequestHandler requestHandler) {
		requireNonNull(sokletConfig);
		requireNonNull(requestHandler);
		this.delegate.initialize(sokletConfig, requestHandler::handleRequest);
	}

	@NonNull
	@Override
	public McpHandlerResolver getHandlerResolver() {
		return this.handlerResolver;
	}

	@NonNull
	@Override
	public McpRequestAdmissionPolicy getRequestAdmissionPolicy() {
		return this.requestAdmissionPolicy;
	}

	@NonNull
	@Override
	public McpRequestInterceptor getRequestInterceptor() {
		return this.requestInterceptor;
	}

	@NonNull
	@Override
	public McpResponseMarshaler getResponseMarshaler() {
		return this.responseMarshaler;
	}

	@NonNull
	@Override
	public McpOriginPolicy getOriginPolicy() {
		return this.originPolicy;
	}

	@NonNull
	@Override
	public McpSessionStore getSessionStore() {
		return this.sessionStore;
	}

	@NonNull
	@Override
	public IdGenerator<String> getIdGenerator() {
		return this.idGenerator;
	}
}
