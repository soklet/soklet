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

import javax.annotation.concurrent.ThreadSafe;

/**
 * Immutable handler result identifying a durably created MCP task.
 *
 * <p>The result carries only a task ID. Before returning a task handle to the
 * client, Soklet asks the configured {@link McpTaskManager} for that ID and
 * requires a readable, authorized task whose origin matches the current
 * invocation. Applications must therefore commit both their work description
 * and the framework-supplied {@link McpTaskOrigin} before returning this value.
 *
 * <p>The type parameter preserves the original typed tool output contract for
 * annotation processing and programmatic registration. It is not an in-memory
 * future, callback, or worker result.
 *
 * @param <R> eventual structured tool output type
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTaskCreatedResult<R> implements McpOperationResult {
	@NonNull
	private final String taskId;

	/**
	 * Creates a result for a task that is already durably readable.
	 *
	 * @param taskId nonblank task identifier without carriage-return or newline
	 *               characters
	 * @param <R> eventual structured tool output type
	 * @return immutable task-created result
	 * @throws NullPointerException if {@code taskId} is null
	 * @throws IllegalArgumentException if {@code taskId} is blank or contains a
	 * carriage-return or newline character
	 */
	@NonNull
	public static <R> McpTaskCreatedResult<@NonNull R> fromTaskId(
			@NonNull String taskId) {
		return new McpTaskCreatedResult<>(taskId);
	}

	private McpTaskCreatedResult(@NonNull String taskId) {
		this.taskId = McpTask.requireTaskId(taskId);
	}

	/** @return nonblank durable task identifier */
	@NonNull
	public String getTaskId() {
		return this.taskId;
	}

	/** @return whether the other result carries the same task ID */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpTaskCreatedResult<?> result))
			return false;
		return this.taskId.equals(result.taskId);
	}

	/** @return task-ID hash code */
	@Override
	public int hashCode() {
		return this.taskId.hashCode();
	}

	/** @return a diagnostic rendering that does not expose the task ID */
	@Override
	@NonNull
	public String toString() {
		return "McpTaskCreatedResult{taskId=<redacted>}";
	}
}
