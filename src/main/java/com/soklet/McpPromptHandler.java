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
 * Programmatic MCP prompt handler contract.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpPromptHandler {
	/**
	 * Provides the MCP prompt name.
	 *
	 * @return the prompt name
	 */
	@NonNull
	String getName();

	/**
	 * Provides the MCP prompt description.
	 *
	 * @return the prompt description
	 */
	@NonNull
	String getDescription();

	/**
	 * Provides optional prompt title metadata.
	 *
	 * @return the prompt title, if available
	 */
	@NonNull
	default Optional<String> getTitle() {
		return Optional.empty();
	}

	/**
	 * Provides the argument schema used for validation and prompt argument advertisement.
	 *
	 * @return the prompt argument schema
	 */
	@NonNull
	McpSchema getArgumentsSchema();

	/**
	 * Handles an MCP {@code prompts/get} request.
	 *
	 * @param context the prompt handler context
	 * @return the prompt result
	 * @throws Exception if prompt generation fails
	 */
	@NonNull
	McpPromptResult handle(@NonNull McpPromptHandlerContext context) throws Exception;
}
