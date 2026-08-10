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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Total UTF-8 wire-bound validation for application-owned cursor strings.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpCursorValidator {
	private McpCursorValidator() {
	}

	static boolean fitsWithinUtf8ByteLimit(@NonNull String value,
			int maximumBytes) {
		requireNonNull(value);
		if (maximumBytes < 1)
			throw new IllegalArgumentException(
					"Maximum cursor size must be positive.");

		long encodedBytes = 0L;
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
			if (character <= 0x7F) {
				encodedBytes++;
			} else if (character <= 0x7FF) {
				encodedBytes += 2L;
			} else if (Character.isHighSurrogate(character)) {
				if (index + 1 >= value.length()
						|| !Character.isLowSurrogate(value.charAt(index + 1)))
					return false;
				encodedBytes += 4L;
				index++;
			} else if (Character.isLowSurrogate(character)) {
				return false;
			} else {
				encodedBytes += 3L;
			}

			if (encodedBytes > maximumBytes)
				return false;
		}
		return true;
	}
}
