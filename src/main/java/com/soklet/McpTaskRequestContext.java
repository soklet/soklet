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

import static java.util.Objects.requireNonNull;

/**
 * Immutable input to a task lookup or cancelation-intent request.
 *
 * <p>The request has already passed Soklet's structural validation and
 * admission pipeline. A task manager must still use the admitted identity,
 * selected endpoint, and its application-owned task binding to authorize the
 * operation atomically. Possession of the task ID is not authorization.
 *
 * <p>Instances intentionally retain reference identity. The nested request
 * context represents one independently admitted invocation and may carry
 * application principal and context objects; structurally equal-looking
 * carriers must not be treated as the same authorization decision.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTaskRequestContext {
	@NonNull
	private final McpRequestContext requestContext;
	@NonNull
	private final String taskId;

	/**
	 * Creates a task-request context from its components.
	 *
	 * <p>This factory is useful for exercising application-provided task managers
	 * in isolation; Soklet constructs the authoritative contexts used for live
	 * requests.
	 *
	 * @param requestContext request context to supply to the task manager
	 * @param taskId nonblank task identifier without carriage-return or newline
	 *               characters
	 * @return immutable task-request context
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the task ID is blank or contains a
	 * carriage-return or newline character
	 */
	@NonNull
	public static McpTaskRequestContext fromComponents(
			@NonNull McpRequestContext requestContext,
			@NonNull String taskId) {
		return new McpTaskRequestContext(requestContext, taskId);
	}

	McpTaskRequestContext(@NonNull McpRequestContext requestContext,
			@NonNull String taskId) {
		this.requestContext = requireNonNull(requestContext);
		this.taskId = McpTask.requireTaskId(taskId);
	}

	/** @return independently admitted current request */
	@NonNull
	public McpRequestContext getRequestContext() {
		return this.requestContext;
	}

	/** @return requested durable task identifier */
	@NonNull
	public String getTaskId() {
		return this.taskId;
	}
}
