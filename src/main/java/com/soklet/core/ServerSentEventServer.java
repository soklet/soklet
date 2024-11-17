/*
 * Copyright 2022-2024 Revetware LLC.
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

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface ServerSentEventServer extends AutoCloseable {
	/**
	 * Starts the SSE server, which makes it able to accept requests from clients.
	 * <p>
	 * If the server is already started, no action is taken.
	 */
	void start();

	/**
	 * Stops the SSE server, which makes it unable to accept requests from clients.
	 * <p>
	 * If the server is already stopped, no action is taken.
	 */
	void stop();

	/**
	 * Is this SSE server started (that is, able to handle requests from clients)?
	 *
	 * @return {@code true} if the server is started, {@code false} otherwise
	 */
	@Nonnull
	Boolean isStarted();

	/**
	 * {@link AutoCloseable}-enabled synonym for {@link #stop()}.
	 *
	 * @throws Exception if an exception occurs while stopping the server
	 */
	@Override
	default void close() throws Exception {
		stop();
	}

	@Nonnull
	Optional<? extends ServerSentEventSource> acquireEventSource(@Nullable ResourcePathInstance resourcePathInstance);
}
