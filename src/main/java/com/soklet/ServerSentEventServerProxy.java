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

import com.soklet.Soklet.MockServerSentEventServer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Package-private internal proxy for {@link ServerSentEventServer} which enables transparent mock usage in {@link Simulator}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class ServerSentEventServerProxy implements ServerSentEventServer {
	@NonNull
	private final ServerSentEventServer realImplementation;
	@NonNull
	private final AtomicReference<ServerSentEventServer> activeImplementation;

	ServerSentEventServerProxy(@NonNull ServerSentEventServer realImplementation) {
		requireNonNull(realImplementation);

		this.realImplementation = realImplementation;
		this.activeImplementation = new AtomicReference<>(realImplementation);
	}

	void enableSimulatorMode(@NonNull MockServerSentEventServer mockServerSentEventServer) {
		requireNonNull(mockServerSentEventServer);
		this.activeImplementation.set(mockServerSentEventServer);
	}

	void disableSimulatorMode() {
		this.activeImplementation.set(getRealImplementation());
	}

	@NonNull
	@Override
	public Optional<? extends ServerSentEventBroadcaster> acquireBroadcaster(@Nullable ResourcePath resourcePath) {
		return getActiveImplementation().acquireBroadcaster(resourcePath);
	}

	@Override
	public void initialize(@NonNull SokletConfig sokletConfig,
												 @NonNull RequestHandler requestHandler) {
		getRealImplementation().initialize(sokletConfig, requestHandler);
	}

	@Override
	public void close() throws Exception {
		getActiveImplementation().close();
	}

	@NonNull
	@Override
	public Boolean isStarted() {
		return getActiveImplementation().isStarted();
	}

	@Override
	public void stop() {
		getActiveImplementation().stop();
	}

	@Override
	public void start() {
		getActiveImplementation().start();
	}

	@NonNull
	ServerSentEventServer getRealImplementation() {
		return this.realImplementation;
	}

	@NonNull
	ServerSentEventServer getActiveImplementation() {
		return this.activeImplementation.get();
	}
}