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
 * Text content block used by v1 MCP tool and prompt results.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public record McpTextContent(
		@NonNull String text
) {
	public McpTextContent {
		requireNonNull(text);
	}

	/**
	 * Creates a text content block.
	 *
	 * @param text the text content
	 * @return a text content block
	 */
	@NonNull
	public static McpTextContent fromText(@NonNull String text) {
		requireNonNull(text);
		return new McpTextContent(text);
	}
}
