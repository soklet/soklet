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
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * One validated custom mirrored-header instruction.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public record McpMirroredHeaderDeclaration(@NonNull String headerSuffix,
		@NonNull List<@NonNull String> argumentPropertyPath,
		@NonNull McpMirroredHeaderValueType valueType) {
	@NonNull
	private static final String HEADER_PREFIX = "Mcp-Param-";

	public McpMirroredHeaderDeclaration {
		requireNonNull(headerSuffix);
		if (headerSuffix.isEmpty() || !httpToken(headerSuffix))
			throw new IllegalArgumentException(
					"Mirrored header suffix must be a non-empty HTTP token.");
		argumentPropertyPath = List.copyOf(requireNonNull(argumentPropertyPath));
		if (argumentPropertyPath.isEmpty())
			throw new IllegalArgumentException(
					"Mirrored argument property path must not be empty.");
		for (String property : argumentPropertyPath)
			requireNonNull(property);
		requireNonNull(valueType);
	}

	@NonNull
	public String headerName() {
		return HEADER_PREFIX + headerSuffix;
	}

	private static boolean httpToken(@NonNull String value) {
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (!(character >= '0' && character <= '9')
					&& !(character >= 'A' && character <= 'Z')
					&& !(character >= 'a' && character <= 'z')
					&& "!#$%&'*+-.^_`|~".indexOf(character) < 0)
				return false;
		}
		return true;
	}

	@Override
	@NonNull
	public String toString() {
		return "McpMirroredHeaderDeclaration[pathDepth="
				+ argumentPropertyPath.size() + ", valueType=" + valueType + "]";
	}
}
