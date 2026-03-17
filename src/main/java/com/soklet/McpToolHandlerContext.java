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
	/**
	 * Provides the tool-call-specific request context.
	 *
	 * @return the tool-call context
	 */
	@NonNull
	McpToolCallContext getToolCallContext();

	/**
	 * Provides the current session context.
	 *
	 * @return the session context
	 */
	@NonNull
	McpSessionContext getSessionContext();

	/**
	 * Provides the negotiated client capabilities for the session.
	 *
	 * @return the client capabilities
	 */
	@NonNull
	McpClientCapabilities getClientCapabilities();

	/**
	 * Provides the tool arguments object for the current request.
	 *
	 * @return the arguments object
	 */
	@NonNull
	McpObject getArguments();

	/**
	 * Retrieves an endpoint path parameter as a string.
	 *
	 * @param name the endpoint path parameter name
	 * @return the parameter value, if present
	 */
	@NonNull
	Optional<String> getEndpointPathParameter(@NonNull String name);

	/**
	 * Retrieves an endpoint path parameter using a Soklet value conversion.
	 *
	 * @param name the endpoint path parameter name
	 * @param type the desired converted type
	 * @param <T> the converted type
	 * @return the converted parameter value, if present
	 */
	@NonNull
	<T> Optional<T> getEndpointPathParameter(@NonNull String name,
																					 @NonNull Class<T> type);
}
