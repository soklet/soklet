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
import java.net.URI;

import static java.util.Objects.requireNonNull;

/**
 * Shared scalar validation for public MCP resource values.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpResourceValueSupport {
	private McpResourceValueSupport() {
	}

	@NonNull
	static URI requireAbsoluteNormalizedUri(@NonNull URI uri) {
		requireNonNull(uri);
		if (!uri.isAbsolute())
			throw new IllegalArgumentException(
					"MCP resource URIs must be absolute.");
		if (!uri.normalize().equals(uri))
			throw new IllegalArgumentException(
					"MCP resource URIs must be normalized.");
		String wireValue = uri.toString();
		for (int index = 0; index < wireValue.length(); ++index) {
			if (wireValue.charAt(index) > 0x7F)
				throw new IllegalArgumentException(
						"MCP resource URIs must use an ASCII URI wire form.");
		}
		return uri;
	}

	@NonNull
	static String requireNonBlank(@NonNull String value,
			@NonNull String description) {
		requireNonNull(value);
		requireNonNull(description);
		if (value.isBlank())
			throw new IllegalArgumentException(description + " must not be blank.");
		return value;
	}
}
