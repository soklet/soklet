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

import static java.util.Objects.requireNonNull;

/**
 * One immutable role/content pair in an MCP prompt result.
 *
 * @param role message author role
 * @param content message content
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public record McpPromptMessage(@NonNull McpRole role,
		@NonNull McpContentBlock content) {
	/** Validates the role and content. */
	public McpPromptMessage {
		requireNonNull(role);
		requireNonNull(content);
	}

	/**
	 * Creates a user-authored message.
	 *
	 * @param content message content
	 * @return user message
	 */
	@NonNull
	public static McpPromptMessage fromUserContent(
			@NonNull McpContentBlock content) {
		return new McpPromptMessage(McpRole.USER, content);
	}

	/**
	 * Creates an assistant-authored message.
	 *
	 * @param content message content
	 * @return assistant message
	 */
	@NonNull
	public static McpPromptMessage fromAssistantContent(
			@NonNull McpContentBlock content) {
		return new McpPromptMessage(McpRole.ASSISTANT, content);
	}
}
