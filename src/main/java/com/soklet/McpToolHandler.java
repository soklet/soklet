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
 * Advanced programmatic MCP tool handler.
 *
 * <p>This handler returns the open {@link McpOperationResult} spine directly
 * and is the path for explicit tool content and future multi-round-trip
 * results. The open interface is a compatibility seam, not an application
 * result-extension registry: Soklet accepts only result implementations it
 * recognizes. Prefer {@link McpCompleteToolHandler} when a tool always
 * returns a supported structured Java value.
 *
 * <p>Implementations must be safe for concurrent invocation.
 *
 * @param <A> bound argument type
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpToolHandler<A> {
	/**
	 * Handles one tool invocation.
	 *
	 * @param request request metadata
	 * @param arguments converted and raw tool arguments
	 * @param features invocation-scoped optional features
	 * @return recognized non-null operation result
	 * @throws Exception if application handling fails
	 */
	@NonNull
	McpOperationResult handle(@NonNull McpRequestContext request,
			@NonNull McpToolArguments<A> arguments,
			@NonNull McpInvocationFeatures features) throws Exception;
}
