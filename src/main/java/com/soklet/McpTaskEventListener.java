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

/**
 * Thread-safe listener for application-owned MCP task-change events.
 * <p>
 * Events are deliberately coarse and carry only a task ID. Soklet performs a
 * fresh, authorized lookup through {@link McpTaskManager} before it projects a
 * task notification; event delivery is never authoritative task state.
 * Publishers may invoke one listener concurrently for independent events.
 * Listener implementations should return promptly and must not assume which
 * thread performs delivery.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpTaskEventListener {
	/**
	 * Reports that the durable state of a task may have changed.
	 *
	 * @param taskId changed task identifier
	 */
	void onTaskChanged(@NonNull String taskId);
}
