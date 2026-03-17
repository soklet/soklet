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

import javax.annotation.concurrent.Immutable;

import static java.util.Objects.requireNonNull;

/**
 * Immutable MCP prompt message.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public record McpPromptMessage(
		@NonNull McpPromptMessageRole role,
		@NonNull McpTextContent content
) {
	public McpPromptMessage {
		requireNonNull(role);
		requireNonNull(content);
	}

	/**
	 * Creates a user-role prompt message from plain text.
	 *
	 * @param text the text content
	 * @return a user-role prompt message
	 */
	@NonNull
	public static McpPromptMessage fromUserText(@NonNull String text) {
		requireNonNull(text);
		return new McpPromptMessage(McpPromptMessageRole.USER, McpTextContent.fromText(text));
	}

	/**
	 * Creates an assistant-role prompt message from plain text.
	 *
	 * @param text the text content
	 * @return an assistant-role prompt message
	 */
	@NonNull
	public static McpPromptMessage fromAssistantText(@NonNull String text) {
		requireNonNull(text);
		return new McpPromptMessage(McpPromptMessageRole.ASSISTANT, McpTextContent.fromText(text));
	}
}
