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
import java.time.Duration;
import java.util.Optional;

/**
 * Thread-safe handle for one asynchronous off-network MCP request.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSimulation extends AutoCloseable {
	/**
	 * Waits up to {@code timeout} for the immutable response head.
	 * <p>
	 * A zero timeout performs a nonblocking check. Very large positive durations
	 * are saturated to the longest supported wait. Response reads are repeatable.
	 *
	 * @param timeout maximum time to wait; must not be negative
	 * @return the response when available, otherwise empty
	 * @throws InterruptedException if the waiting thread is interrupted
	 * @throws IllegalArgumentException if {@code timeout} is negative
	 * @throws NullPointerException if {@code timeout} is null
	 */
	@NonNull
	Optional<@NonNull McpSimulationResponse> awaitResponse(
			@NonNull Duration timeout) throws InterruptedException;

	/**
	 * Waits up to {@code timeout} for and removes the next captured SSE item.
	 * Removing an item releases a pending-item slot but does not refund captured
	 * bytes. A zero timeout performs a nonblocking check, and very large positive
	 * durations are saturated to the longest supported wait. Unlike response and
	 * completion reads, successful item reads are destructive.
	 *
	 * @param timeout maximum time to wait; must not be negative
	 * @return the next item when available, otherwise empty
	 * @throws InterruptedException if the waiting thread is interrupted
	 * @throws IllegalArgumentException if {@code timeout} is negative
	 * @throws NullPointerException if {@code timeout} is null
	 */
	@NonNull
	Optional<@NonNull McpSimulationStreamItem> nextStreamItem(
			@NonNull Duration timeout) throws InterruptedException;

	/**
	 * Waits up to {@code timeout} for immutable terminal completion.
	 * <p>
	 * A zero timeout performs a nonblocking check. Very large positive durations
	 * are saturated to the longest supported wait. Completion reads are
	 * repeatable.
	 *
	 * @param timeout maximum time to wait; must not be negative
	 * @return completion when available, otherwise empty
	 * @throws InterruptedException if the waiting thread is interrupted
	 * @throws IllegalArgumentException if {@code timeout} is negative
	 * @throws NullPointerException if {@code timeout} is null
	 */
	@NonNull
	Optional<@NonNull McpSimulationCompletion> awaitCompletion(
			@NonNull Duration timeout) throws InterruptedException;

	/**
	 * Performs a nonblocking terminal-state check.
	 *
	 * @return whether terminal completion is available
	 */
	@NonNull
	Boolean isComplete();

	/**
	 * Simulates a client disconnect if the request remains active. This method is
	 * idempotent and cannot replace an earlier terminal winner.
	 */
	void cancel();

	/**
	 * Simulates a client disconnect if the request remains active. Closing is
	 * idempotent, does not discard already captured values, and cannot replace an
	 * earlier terminal winner.
	 */
	@Override
	void close();
}
