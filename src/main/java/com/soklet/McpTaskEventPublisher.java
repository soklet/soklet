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
 * Thread-safe broadcast publisher for application-owned MCP task-change
 * events.
 * <p>
 * Events use broadcast rather than competing-consumer semantics: one listener
 * receiving an event must not consume it on behalf of the others.
 * Implementations may perform process-local delivery or coordinate through a
 * distributed service, and may provide best-effort rather than durable
 * delivery.
 * In a fleet, the application normally supplies a publisher that reaches every
 * Soklet node capable of serving the corresponding task. Soklet closes only
 * registrations returned by {@link #subscribe(McpTaskEventListener)} and never
 * closes the publisher itself.
 * <p>
 * An event is a non-authoritative hint that carries only a task ID. It must be
 * published after the corresponding state change is durable and readable
 * through the associated {@link McpTaskManager}. Duplicate, delayed, or lost
 * events are permitted because polling remains authoritative. Soklet performs
 * a fresh manager lookup using each independently admitted subscription's
 * endpoint and identity before rendering a task snapshot. A publisher must
 * protect task IDs as potentially sensitive application data and must not
 * attach task state, principals, or authorization decisions to an event.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpTaskEventPublisher {
	/**
	 * Creates an independent in-memory publisher with default behavior.
	 * <p>
	 * The publisher reaches only listeners registered with that instance in the
	 * current process and invokes them synchronously on the publishing thread.
	 * Closing a registration does not wait for a delivery already selected or
	 * in flight. If a listener throws a runtime exception, all other current
	 * listeners are still attempted before the first exception is rethrown with
	 * later exceptions suppressed.
	 *
	 * @return in-memory task-event publisher
	 */
	@NonNull
	static McpTaskEventPublisher fromInMemoryDefaults() {
		return new DefaultMcpTaskEventPublisher();
	}

	/**
	 * Registers a listener for broadcast task-change events.
	 *
	 * @param listener thread-safe listener
	 * @return an idempotently closable listener registration
	 */
	@NonNull
	McpSubscriptionEventRegistration subscribe(
			@NonNull McpTaskEventListener listener);

	/**
	 * Broadcasts a coarse change event after the task's new durable state is
	 * readable through the associated task manager.
	 *
	 * @param taskId changed task identifier
	 * @throws NullPointerException if {@code taskId} is null
	 * @throws IllegalArgumentException if {@code taskId} is blank or contains a
	 * carriage-return or newline character
	 */
	void publishTaskChanged(@NonNull String taskId);
}
