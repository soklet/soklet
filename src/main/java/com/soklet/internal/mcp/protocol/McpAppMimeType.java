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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Shared structural MIME validation for MCP Apps resource and capability values.
 *
 * <p>This type is public only so public value builders and internal runtime
 * boundaries can share one parser. It is not part of Soklet's supported public
 * API or published Javadocs.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpAppMimeType {

	private McpAppMimeType() {
	}

	/**
	 * Validates a media type and determines whether it is the exact MCP Apps profile.
	 *
	 * <p>Type, subtype, and parameter names are ASCII case-insensitive. ASCII spaces
	 * around separators are ignored, and quoted parameter values are unescaped
	 * before comparison. Parameter values remain case-sensitive. All input is
	 * validated, including parameters on media types that do not match the Apps
	 * profile.</p>
	 *
	 * @param mimeType media type to validate
	 * @return whether the media type is {@code text/html} with exactly one
	 *         {@code profile=mcp-app} parameter
	 * @throws IllegalArgumentException if the media type contains malformed syntax,
	 *                                  duplicate parameters, controls, or non-ASCII
	 *                                  characters
	 */
	public static boolean isAppsProfile(@NonNull String mimeType) {
		return "text/html;profile=mcp-app".equals(canonicalize(mimeType));
	}

	/**
	 * Validates a media type and returns its structural canonical representation.
	 * Parameter names are sorted; quoted values use token form when possible.
	 *
	 * @param mimeType media type to validate
	 * @return canonical media type, preserving parameter-value case
	 * @throws IllegalArgumentException if the media type is malformed
	 */
	@NonNull
	public static String canonicalize(@NonNull String mimeType) {
		return new Parser(requireNonNull(mimeType)).canonicalize();
	}

	/**
	 * Matches a requested media type against an entire advertised MIME array.
	 * A null array represents a missing or non-array setting; null entries represent
	 * non-string members. Every entry must be syntactically valid before any entry
	 * can establish support.
	 *
	 * @param mimeType requested media type to validate
	 * @param advertisedMimeTypes decoded peer array, or null for an invalid setting
	 * @return whether the valid peer array contains a structurally equivalent entry
	 * @throws IllegalArgumentException if the requested media type is malformed
	 */
	public static boolean supportsMimeType(@NonNull String mimeType,
			@Nullable List<@Nullable String> advertisedMimeTypes) {
		String requested = canonicalize(mimeType);
		if (advertisedMimeTypes == null)
			return false;
		boolean matched = false;
		for (String advertisedMimeType : advertisedMimeTypes) {
			if (advertisedMimeType == null)
				return false;
			try {
				matched |= requested.equals(canonicalize(advertisedMimeType));
			} catch (IllegalArgumentException exception) {
				return false;
			}
		}
		return matched;
	}

	@NonNull
	private static IllegalArgumentException invalidMimeType() {
		return new IllegalArgumentException("Invalid MCP Apps MIME type.");
	}

	private static boolean isTokenCharacter(char character) {
		return (character >= 'a' && character <= 'z')
				|| (character >= 'A' && character <= 'Z')
				|| (character >= '0' && character <= '9')
				|| "!#$%&'*+-.^_`|~".indexOf(character) >= 0;
	}

	private static final class Parser {
		@NonNull
		private final String value;
		private int index;

		private Parser(@NonNull String value) {
			this.value = value;
			for (int characterIndex = 0; characterIndex < value.length(); characterIndex++) {
				char character = value.charAt(characterIndex);
				if (character < 0x20 || character > 0x7E)
					throw invalidMimeType();
			}
		}

		@NonNull
		private String canonicalize() {
			skipSpaces();
			String type = readToken();
			requireSeparator('/');
			String subtype = readToken();
			Map<String, String> parameters = new TreeMap<>();
			skipSpaces();
			while (this.index < this.value.length()) {
				requireSeparator(';');
				String parameterName = readToken().toLowerCase(Locale.ROOT);
				requireSeparator('=');
				String parameterValue = this.index < this.value.length()
						&& this.value.charAt(this.index) == '"'
						? readQuotedValue() : readToken();
				if (parameters.putIfAbsent(parameterName, parameterValue) != null)
					throw invalidMimeType();
				skipSpaces();
			}
			StringBuilder canonical = new StringBuilder(type.toLowerCase(Locale.ROOT))
					.append('/').append(subtype.toLowerCase(Locale.ROOT));
			parameters.forEach((name, value) -> {
				canonical.append(';').append(name).append('=');
				if (!value.isEmpty() && value.chars().allMatch(character -> isTokenCharacter((char) character)))
					canonical.append(value);
				else {
					canonical.append('"');
					for (int valueIndex = 0; valueIndex < value.length(); valueIndex++) {
						char character = value.charAt(valueIndex);
						if (character == '"' || character == '\\')
							canonical.append('\\');
						canonical.append(character);
					}
					canonical.append('"');
				}
			});
			return canonical.toString();
		}

		private void skipSpaces() {
			while (this.index < this.value.length() && this.value.charAt(this.index) == ' ')
				this.index++;
		}

		private void requireSeparator(char separator) {
			skipSpaces();
			if (this.index >= this.value.length() || this.value.charAt(this.index) != separator)
				throw invalidMimeType();
			this.index++;
			skipSpaces();
		}

		@NonNull
		private String readToken() {
			int start = this.index;
			while (this.index < this.value.length()
					&& isTokenCharacter(this.value.charAt(this.index)))
				this.index++;
			if (start == this.index)
				throw invalidMimeType();
			return this.value.substring(start, this.index);
		}

		@NonNull
		private String readQuotedValue() {
			this.index++;
			StringBuilder decoded = new StringBuilder();
			while (this.index < this.value.length()) {
				char character = this.value.charAt(this.index++);
				if (character == '"')
					return decoded.toString();
				if (character == '\\') {
					if (this.index == this.value.length())
						throw invalidMimeType();
					character = this.value.charAt(this.index++);
				}
				decoded.append(character);
			}
			throw invalidMimeType();
		}
	}
}
