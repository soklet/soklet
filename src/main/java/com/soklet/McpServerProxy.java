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

import com.soklet.Soklet.MockMcpServer;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Package-private internal proxy for {@link McpServer} which enables transparent mock usage in {@link Simulator}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpServerProxy implements McpServer, InternalMcpSessionMessagePublisher {
	@NonNull
	private final McpServer realImplementation;
	@NonNull
	private final AtomicReference<McpServer> activeImplementation;

	McpServerProxy(@NonNull McpServer realImplementation) {
		requireNonNull(realImplementation);

		this.realImplementation = realImplementation;
		this.activeImplementation = new AtomicReference<>(realImplementation);
	}

	void enableSimulatorMode(@NonNull MockMcpServer mockMcpServer) {
		requireNonNull(mockMcpServer);
		this.activeImplementation.set(mockMcpServer);
	}

	void disableSimulatorMode() {
		this.activeImplementation.set(getRealImplementation());
	}

	@Override
	public void start() {
		getActiveImplementation().start();
	}

	@Override
	public void stop() {
		getActiveImplementation().stop();
	}

	@NonNull
	@Override
	public Boolean isStarted() {
		return getActiveImplementation().isStarted();
	}

	@Override
	public void initialize(@NonNull SokletConfig sokletConfig,
												 @NonNull RequestHandler requestHandler) {
		getRealImplementation().initialize(sokletConfig, requestHandler);
	}

	@NonNull
	@Override
	public McpHandlerResolver getHandlerResolver() {
		return getRealImplementation().getHandlerResolver();
	}

	@NonNull
	@Override
	public McpRequestAdmissionPolicy getRequestAdmissionPolicy() {
		return getRealImplementation().getRequestAdmissionPolicy();
	}

	@NonNull
	@Override
	public McpRequestInterceptor getRequestInterceptor() {
		return getRealImplementation().getRequestInterceptor();
	}

	@NonNull
	@Override
	public McpResponseMarshaler getResponseMarshaler() {
		return getRealImplementation().getResponseMarshaler();
	}

	@NonNull
	@Override
	public McpOriginPolicy getOriginPolicy() {
		return getRealImplementation().getOriginPolicy();
	}

	@NonNull
	@Override
	public McpSessionStore getSessionStore() {
		return getRealImplementation().getSessionStore();
	}

	@NonNull
	@Override
	public IdGenerator<String> getIdGenerator() {
		return getRealImplementation().getIdGenerator();
	}

	@NonNull
	McpServer getRealImplementation() {
		return this.realImplementation;
	}

	@NonNull
	McpServer getActiveImplementation() {
		return this.activeImplementation.get();
	}

	@NonNull
	@Override
	public Boolean publishSessionMessage(@NonNull String sessionId,
																			 @NonNull McpObject message) {
		requireNonNull(sessionId);
		requireNonNull(message);

		McpServer activeImplementation = getActiveImplementation();

		if (activeImplementation instanceof InternalMcpSessionMessagePublisher internalMcpSessionMessagePublisher)
			return internalMcpSessionMessagePublisher.publishSessionMessage(sessionId, message);

		return false;
	}
}
