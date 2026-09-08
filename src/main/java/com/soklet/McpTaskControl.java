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
 * Invocation-scoped information for creating a durable MCP task.
 *
 * <p>This feature is available only for a task-augmentable operation whose
 * request declared the MCP Tasks extension. Applications may use the admitted
 * request to derive their own authorization binding and must persist
 * {@link #getTaskOrigin()} in the same transaction or outbox operation as the
 * durable work description. Neither this control, the request context, nor any
 * request cancelation token is durable work and none may be retained by a
 * worker.
 *
 * <p>The control does not schedule work, allocate a task ID, or imply that a
 * client requested asynchronous execution. The server remains the sole
 * per-invocation decision maker.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpTaskControl {
	/**
	 * Returns the admitted request that is being considered for task creation.
	 *
	 * <p>Use this context synchronously to derive an application-owned task
	 * authorization binding. Do not retain it as the durable execution context.
	 *
	 * @return admitted task-augmentable request
	 */
	@NonNull
	McpRequestContext getRequestContext();

	/**
	 * Returns the framework-derived origin to persist with the durable task.
	 *
	 * <p>When called by an interceptor for a typed tool, first access completes
	 * typed argument validation. Invalid arguments fail before an origin can be
	 * used or a tool handler can be entered.
	 *
	 * @return immutable non-wire task origin
	 * @throws IllegalArgumentException if the typed tool arguments are invalid
	 */
	@NonNull
	McpTaskOrigin getTaskOrigin();
}
