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
import java.util.List;
import java.util.Optional;

/**
 * Immutable terminal state for one MCP simulation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSimulationCompletion {
	/**
	 * @return the exact public request-stream termination reason
	 */
	@NonNull
	McpStreamTerminationReason getReason();

	/**
	 * Returns the terminal SSE JSON message when its frame was successfully
	 * captured. The same message also remains available as one ordinary stream
	 * item; nonstreaming JSON responses never appear here.
	 *
	 * @return the duplicated terminal stream message, otherwise empty
	 */
	@NonNull
	Optional<@NonNull McpJsonValue> getTerminalMessage();

	/**
	 * Returns an immutable ordered view of terminal failures. Throwable instances
	 * retain their exact identities and may contain application-sensitive data;
	 * callers own any logging or disclosure decision.
	 *
	 * @return terminal failures in observation order
	 */
	@NonNull
	List<@NonNull Throwable> getThrowables();
}
