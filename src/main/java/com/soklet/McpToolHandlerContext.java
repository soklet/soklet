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
 * Context supplied to programmatic MCP tool handlers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpToolHandlerContext {
	@NonNull
	McpToolCallContext getToolCallContext();

	@NonNull
	McpSessionContext getSessionContext();

	@NonNull
	McpClientCapabilities getClientCapabilities();

	@NonNull
	McpObject getArguments();

	@NonNull
	Optional<String> getEndpointPathParameter(@NonNull String name);

	@NonNull
	<T> Optional<T> getEndpointPathParameter(@NonNull String name,
																					 @NonNull Class<T> type);
}
