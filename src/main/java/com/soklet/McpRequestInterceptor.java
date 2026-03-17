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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;

/**
 * MCP-side analogue of HTTP {@link RequestInterceptor}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpRequestInterceptor {
	/**
	 * Intercepts an MCP JSON-RPC request before Soklet dispatches it to framework or application logic.
	 *
	 * @param context the current request context
	 * @param invocation the invocation callback to continue processing
	 * @param <T> the invocation result type
	 * @return the invocation result
	 * @throws Exception if interception or downstream handling fails
	 */
	@Nullable
	default <T> T interceptRequest(@NonNull McpRequestContext context,
																 @NonNull McpHandlerInvocation<T> invocation) throws Exception {
		return invocation.invoke();
	}
}
