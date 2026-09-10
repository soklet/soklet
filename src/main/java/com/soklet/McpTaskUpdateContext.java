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
 * Immutable input to a task input-response update.
 *
 * <p>The request has already passed Soklet's structural validation and
 * admission pipeline. A task manager must authorize and apply the update as
 * one application-owned operation. It must ignore unknown, already-consumed,
 * or no-longer-outstanding response keys, ignore a response whose MCP union
 * variant does not match the outstanding request, and make accepted responses
 * idempotent according to the MCP Tasks contract. Implementations may perform
 * that correlation through
 * {@link McpInputRequest#matchesInputResponse(McpJsonValue)}.
 *
 * <p>Instances intentionally retain reference identity. The nested request
 * context represents one independently admitted invocation and may carry
 * application principal and context objects; structurally equal-looking
 * carriers must not be treated as the same authorization decision.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTaskUpdateContext {
	@NonNull
	private final McpRequestContext requestContext;
	@NonNull
	private final String taskId;
	@NonNull
	private final McpInputResponses inputResponses;

	/**
	 * Creates a task-update context from its components.
	 *
	 * <p>This factory is useful for exercising application-provided task managers
	 * in isolation; Soklet constructs the authoritative contexts used for live
	 * requests.
	 *
	 * @param requestContext request context to supply to the task manager
	 * @param taskId nonblank task identifier without carriage-return or newline
	 *               characters
	 * @param inputResponses immutable client responses supplied by the update
	 * @return immutable task-update context
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the task ID is blank or contains a
	 * carriage-return or newline character
	 */
	@NonNull
	public static McpTaskUpdateContext fromComponents(
			@NonNull McpRequestContext requestContext,
			@NonNull String taskId,
			@NonNull McpInputResponses inputResponses) {
		return new McpTaskUpdateContext(requestContext, taskId, inputResponses);
	}

	McpTaskUpdateContext(@NonNull McpRequestContext requestContext,
			@NonNull String taskId,
			@NonNull McpInputResponses inputResponses) {
		this.requestContext = requireNonNull(requestContext);
		this.taskId = McpTask.requireTaskId(taskId);
		this.inputResponses = requireNonNull(inputResponses);
	}

	/** @return independently admitted current request */
	@NonNull
	public McpRequestContext getRequestContext() {
		return this.requestContext;
	}

	/** @return durable task identifier */
	@NonNull
	public String getTaskId() {
		return this.taskId;
	}

	/** @return immutable client responses supplied by this update */
	@NonNull
	public McpInputResponses getInputResponses() {
		return this.inputResponses;
	}
}
