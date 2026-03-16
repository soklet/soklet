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

import javax.annotation.concurrent.Immutable;
import java.util.List;

import static java.util.List.of;
import static java.util.Objects.requireNonNull;

/**
 * Immutable MCP prompt result.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public record McpPromptResult(
		@Nullable String description,
		@NonNull List<@NonNull McpPromptMessage> messages
) {
	public McpPromptResult {
		requireNonNull(messages);
		messages = List.copyOf(messages);
	}

	@NonNull
	public static McpPromptResult fromMessages(@NonNull McpPromptMessage... messages) {
		requireNonNull(messages);
		return new McpPromptResult(null, of(messages));
	}

	@NonNull
	public static McpPromptResult fromDescriptionAndMessages(@NonNull String description,
																													 @NonNull McpPromptMessage... messages) {
		requireNonNull(description);
		requireNonNull(messages);
		return new McpPromptResult(description, of(messages));
	}
}
