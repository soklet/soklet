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
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * One immutable role/content pair in an MCP prompt result.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpPromptMessage {
	@NonNull
	private final McpRole role;
	@NonNull
	private final McpContentBlock content;

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

	private McpPromptMessage(@NonNull McpRole role,
			@NonNull McpContentBlock content) {
		this.role = requireNonNull(role);
		this.content = requireNonNull(content);
	}

	/** @return message author role */
	@NonNull
	public McpRole getRole() {
		return this.role;
	}

	/** @return message content */
	@NonNull
	public McpContentBlock getContent() {
		return this.content;
	}

	/** @return whether this value has the same role and content */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpPromptMessage message))
			return false;
		return this.role == message.role && this.content.equals(message.content);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.role, this.content);
	}

	/** @return diagnostic rendering that redacts message content */
	@Override
	@NonNull
	public String toString() {
		return "McpPromptMessage{role=%s, content=<redacted>}"
				.formatted(this.role);
	}
}
