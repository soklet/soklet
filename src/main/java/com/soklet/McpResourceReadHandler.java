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
 * Programmatic MCP resource-read handler.
 *
 * <p>The open {@link McpOperationResult} return preserves the result spine for
 * later multi-round-trip support. In the Phase 4 resource surface, handlers
 * complete with {@link McpCompleteResult#fromResourceOutput(McpResourceOutput)}.
 * Implementations must be safe for concurrent invocation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpResourceReadHandler {
	/**
	 * Reads one resource.
	 *
	 * @param request request metadata
	 * @param resource resolved resource URI and template variables
	 * @param features invocation-scoped optional features
	 * @return recognized non-null operation result
	 * @throws Exception if application handling fails
	 */
	@NonNull
	McpOperationResult handle(@NonNull McpRequestContext request,
			@NonNull McpResourceReadContext resource,
			@NonNull McpInvocationFeatures features) throws Exception;
}
