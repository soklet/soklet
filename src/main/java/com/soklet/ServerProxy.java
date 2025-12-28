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

import com.soklet.Soklet.MockServer;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Package-private internal proxy for {@link Server} which enables transparent mock usage in {@link Simulator}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class ServerProxy implements Server {
	@NonNull
	private final Server realImplementation;
	@NonNull
	private final AtomicReference<Server> activeImplementation;

	ServerProxy(@NonNull Server realImplementation) {
		requireNonNull(realImplementation);

		this.realImplementation = realImplementation;
		this.activeImplementation = new AtomicReference<>(realImplementation);
	}

	void enableSimulatorMode(@NonNull MockServer mockServer) {
		requireNonNull(mockServer);
		this.activeImplementation.set(mockServer);
	}

	void disableSimulatorMode() {
		this.activeImplementation.set(getRealImplementation());
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
	Server getRealImplementation() {
		return this.realImplementation;
	}

	@NonNull
	Server getActiveImplementation() {
		return this.activeImplementation.get();
	}
}