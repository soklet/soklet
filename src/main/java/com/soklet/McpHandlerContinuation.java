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

import javax.annotation.concurrent.NotThreadSafe;

/**
 * One-shot continuation supplied to an {@link McpHandlerInterceptor}.
 * <p>
 * The continuation is synchronous and bound to the thread invoking the
 * interceptor. It must be invoked, if at all, before the interceptor returns
 * and must not be retained or passed to another thread. The first invocation
 * enters the remaining validation and handler chain. A second or later
 * invocation fails synchronously without re-entering downstream code.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@FunctionalInterface
public interface McpHandlerContinuation {
	/**
	 * Invokes the next stage exactly once.
	 *
	 * @return recognized, non-null, method-compatible handler result
	 * @throws IllegalStateException if this continuation was already invoked,
	 *                               retained beyond its interceptor call, or
	 *                               invoked from another thread
	 * @throws Exception if downstream validation or application handling fails
	 */
	@NonNull
	McpOperationResult proceed() throws Exception;
}
