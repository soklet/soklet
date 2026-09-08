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
import java.util.Optional;

/**
 * Thread-safe application authority for MCP task state and client actions.
 *
 * <p>A production implementation normally delegates to application-owned
 * durable storage and job infrastructure shared by every eligible Soklet
 * node. It owns task-ID generation, persistence, retention, task-to-principal
 * and tenant binding, legal atomic state transitions, client-input
 * deduplication, cancelation intent, and publication of already-durable status
 * changes. Soklet does not close the manager or start, stop, schedule, retry,
 * lease, or fence application workers.
 *
 * <p>Every operation is independently admitted before this interface is
 * called, but admission alone does not authorize a task. Implementations must
 * atomically compare the current request's endpoint and admitted application
 * identity with their durable task binding. Unknown and unauthorized task IDs
 * must be indistinguishable: {@link #findTask(McpTaskRequestContext)} returns
 * an empty optional for either case, while mutation methods throw
 * {@link McpTaskNotFoundException} for either case. Other failures are treated
 * as internal errors and must not expose exception-derived data to the client.
 *
 * <p>A task must be durably readable through {@link #findTask} before its
 * handler returns {@link McpTaskCreatedResult}. Correct production behavior
 * cannot depend on the original HTTP connection, sticky routing, or the
 * originating process remaining alive. Implementations should assume
 * at-least-once work execution and use leases, fencing, idempotency, and an
 * outbox or equivalent recovery mechanism where their workload requires them.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpTaskManager {
	/**
	 * Creates Soklet's explicit process-local task manager with documented
	 * bounded defaults.
	 *
	 * <p>The returned manager is intended for development, tests, simulation,
	 * and deliberately ephemeral single-process applications. Its state is lost
	 * on restart and is invisible to other nodes. It provides no worker,
	 * executor, queue, outbox, leases, fencing, failover, or crash recovery and
	 * is never selected implicitly by an {@link McpServer}.
	 *
	 * @return a new independent in-memory task manager
	 */
	@NonNull
	static McpInMemoryTaskManager fromInMemoryDefaults() {
		return new McpInMemoryTaskManager();
	}

	/**
	 * Finds the current authoritative task snapshot after atomically
	 * authorizing the request.
	 *
	 * @param context independently admitted lookup context
	 * @return current snapshot, or empty when the task is unknown or unauthorized
	 * @throws Exception if task storage or authorization fails unexpectedly
	 */
	@NonNull
	Optional<@NonNull McpTask> findTask(
			@NonNull McpTaskRequestContext context) throws Exception;

	/**
	 * Atomically authorizes and accepts client responses to outstanding task
	 * input requests.
	 *
	 * <p>Unknown, already-consumed, and superseded response keys are ignored.
	 * The update acknowledgement may precede a worker's subsequent observable
	 * state change.
	 *
	 * @param context independently admitted task update
	 * @throws McpTaskNotFoundException if the task is unknown or unauthorized
	 * @throws Exception if task storage, authorization, or update processing
	 * fails unexpectedly
	 */
	void updateTask(@NonNull McpTaskUpdateContext context) throws Exception;

	/**
	 * Atomically authorizes and records a client's durable task-cancelation
	 * intent.
	 *
	 * <p>A successful return acknowledges intent only. A worker may later honor
	 * it, or completion or failure may win the race. This method is unrelated to
	 * request-scoped {@link CancelationToken} and
	 * {@code notifications/cancelled} behavior.
	 *
	 * @param context independently admitted task-cancelation request
	 * @throws McpTaskNotFoundException if the task is unknown or unauthorized
	 * @throws Exception if task storage, authorization, or intent recording
	 * fails unexpectedly
	 */
	void requestTaskCancelation(
			@NonNull McpTaskRequestContext context) throws Exception;
}
