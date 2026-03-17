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
 * Programmatic MCP tool handler contract.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpToolHandler {
	/**
	 * Provides the MCP tool name.
	 *
	 * @return the tool name
	 */
	@NonNull
	String getName();

	/**
	 * Provides the MCP tool description.
	 *
	 * @return the tool description
	 */
	@NonNull
	String getDescription();

	/**
	 * Provides the input schema used for framework validation and client advertisement.
	 *
	 * @return the tool input schema
	 */
	@NonNull
	McpSchema getInputSchema();

	/**
	 * Handles an MCP {@code tools/call} request.
	 *
	 * @param context the tool handler context
	 * @return the tool result
	 * @throws Exception if the tool call fails
	 */
	@NonNull
	McpToolResult handle(@NonNull McpToolHandlerContext context) throws Exception;
}
