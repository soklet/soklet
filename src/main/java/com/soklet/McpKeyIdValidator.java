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
 * Shared validation for identifiers attached to secret MCP keys.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpKeyIdValidator {
	private static final int MAXIMUM_ID_BYTES = 64;

	private McpKeyIdValidator() {
	}

	@NonNull
	static String validate(@NonNull String id, @NonNull String description) {
		requireNonNull(id);
		requireNonNull(description);

		if (id.isEmpty() || id.length() > MAXIMUM_ID_BYTES)
			throw new IllegalArgumentException(
					"%s must contain 1-64 ASCII bytes.".formatted(description));

		for (int index = 0; index < id.length(); ++index) {
			char value = id.charAt(index);
			if (value > 0x7F || !isHttpTokenCharacter(value))
				throw new IllegalArgumentException(
						"%s must be an ASCII HTTP token.".formatted(description));
		}

		return id;
	}

	private static boolean isHttpTokenCharacter(char value) {
		return (value >= '0' && value <= '9')
				|| (value >= 'A' && value <= 'Z')
				|| (value >= 'a' && value <= 'z')
				|| "!#$%&'*+-.^_`|~".indexOf(value) >= 0;
	}
}
