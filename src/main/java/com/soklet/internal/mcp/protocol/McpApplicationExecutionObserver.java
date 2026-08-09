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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Internal two-phase observation boundary for application-dispatch accounting.
 * Record methods are invoked while the dispatcher accounting lock is held and
 * must only enqueue immutable transition state. {@link #drain()} is invoked
 * after that lock is released and may deliver the queued transitions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpApplicationExecutionObserver {
	void beginDeferral();

	void recordHandlerExecutionStarted();

	void recordHandlerExecutionFinished();

	void recordHandlerQueued();

	void recordHandlerDequeued();

	void recordHandlerCapacityRejected();

	void drain();

	void endDeferral();

	@NonNull
	static McpApplicationExecutionObserver disabledInstance() {
		return DisabledMcpApplicationExecutionObserver.INSTANCE;
	}
}

/**
 * Shared no-op application-execution observer.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum DisabledMcpApplicationExecutionObserver
		implements McpApplicationExecutionObserver {
	INSTANCE;

	@Override
	public void beginDeferral() {
	}

	@Override
	public void recordHandlerExecutionStarted() {
	}

	@Override
	public void recordHandlerExecutionFinished() {
	}

	@Override
	public void recordHandlerQueued() {
	}

	@Override
	public void recordHandlerDequeued() {
	}

	@Override
	public void recordHandlerCapacityRejected() {
	}

	@Override
	public void drain() {
	}

	@Override
	public void endDeferral() {
	}
}
