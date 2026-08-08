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
 * Invocation-scoped reporter for MCP progress notifications.
 *
 * <p>A reporter is available through {@link McpInvocationFeatures} only when
 * the initiating request supplied a valid progress token and Soklet can safely
 * commit request-scoped progress to the response stream. Implementations are
 * thread-safe. Reports from concurrent callers are serialized in accepted
 * progress order.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpProgressReporter {
	/**
	 * Reports increasing progress for the active invocation.
	 *
	 * <p>An update equal to the last accepted progress value is coalesced. A
	 * lower value is rejected while the invocation remains active. Once the
	 * invocation is canceled or terminal, reports have no effect.
	 *
	 * <p>Reporting is synchronous and may block while the bounded
	 * request-scoped outbound queue applies backpressure. If that wait is
	 * interrupted, Soklet preserves the calling thread's interrupt status and
	 * does not accept the update.
	 *
	 * @param update immutable progress update
	 * @throws NullPointerException if {@code update} is null
	 * @throws IllegalArgumentException if progress decreases while the
	 * invocation is active
	 */
	void report(@NonNull McpProgressUpdate update);
}
