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

import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Generic invocation callback used by {@link McpRequestInterceptor}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpHandlerInvocation<T> {
	/**
	 * Invokes the downstream MCP handling logic.
	 *
	 * @return the invocation result
	 * @throws Exception if downstream handling fails
	 */
	@Nullable
	T invoke() throws Exception;
}
