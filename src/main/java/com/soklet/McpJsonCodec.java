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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Strict internal JSON codec for MCP transport values.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpJsonCodec {
	private McpJsonCodec() {}

	@NonNull
	static McpValue parse(@NonNull byte[] jsonBytes) {
		requireNonNull(jsonBytes);
		return parse(new String(jsonBytes, StandardCharsets.UTF_8));
	}

	@NonNull
	static McpValue parse(@NonNull String json) {
		requireNonNull(json);
		return new Parser(json).parse();
	}

	@NonNull
	static byte[] toUtf8Bytes(@NonNull McpValue value) {
		requireNonNull(value);
		return toJson(value).getBytes(StandardCharsets.UTF_8);
	}

	@NonNull
	static String toJson(@NonNull McpValue value) {
		requireNonNull(value);

		StringBuilder json = new StringBuilder(256);
		appendJson(value, json);
		return json.toString();
	}

	private static void appendJson(@NonNull McpValue value,
																 @NonNull StringBuilder json) {
		requireNonNull(value);
		requireNonNull(json);

		if (value instanceof McpObject mcpObject) {
			json.append('{');

			boolean first = true;

			for (Map.Entry<String, McpValue> entry : mcpObject.values().entrySet()) {
				if (!first)
					json.append(',');

				appendEscapedString(entry.getKey(), json);
				json.append(':');
				appendJson(entry.getValue(), json);
				first = false;
			}

			json.append('}');
			return;
		}

		if (value instanceof McpArray mcpArray) {
			json.append('[');

			boolean first = true;

			for (McpValue childValue : mcpArray.values()) {
				if (!first)
					json.append(',');

				appendJson(childValue, json);
				first = false;
			}

			json.append(']');
			return;
		}

		if (value instanceof McpString mcpString) {
			appendEscapedString(mcpString.value(), json);
			return;
		}

		if (value instanceof McpNumber mcpNumber) {
			json.append(mcpNumber.value().toPlainString());
			return;
		}

		if (value instanceof McpBoolean mcpBoolean) {
			json.append(mcpBoolean.value().booleanValue() ? "true" : "false");
			return;
		}

		if (value instanceof McpNull) {
			json.append("null");
			return;
		}

		throw new IllegalStateException(format("Unsupported %s type: %s", McpValue.class.getSimpleName(), value.getClass().getName()));
	}

	private static void appendEscapedString(@NonNull String value,
																					@NonNull StringBuilder json) {
		requireNonNull(value);
		requireNonNull(json);

		json.append('"');

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			switch (c) {
				case '"' -> json.append("\\\"");
				case '\\' -> json.append("\\\\");
				case '\b' -> json.append("\\b");
				case '\f' -> json.append("\\f");
				case '\n' -> json.append("\\n");
				case '\r' -> json.append("\\r");
				case '\t' -> json.append("\\t");
				default -> {
					if (c < 0x20)
						json.append(format("\\u%04x", (int) c));
					else
						json.append(c);
				}
			}
		}

		json.append('"');
	}

	@ThreadSafe
	private static final class Parser {
		@NonNull
		private final String json;
		private int index;

		private Parser(@NonNull String json) {
			requireNonNull(json);
			this.json = json;
			this.index = 0;
		}

		@NonNull
		private McpValue parse() {
			skipWhitespace();
			McpValue value = parseValue();
			skipWhitespace();

			if (!isAtEnd())
				throw parseException("Unexpected trailing content");

			return value;
		}

		@NonNull
		private McpValue parseValue() {
			skipWhitespace();

			if (isAtEnd())
				throw parseException("Expected JSON value");

			char c = current();

			if (c == '{')
				return parseObject();

			if (c == '[')
				return parseArray();

			if (c == '"')
				return new McpString(parseString());

			if (c == 't') {
				consumeLiteral("true");
				return new McpBoolean(Boolean.TRUE);
			}

			if (c == 'f') {
				consumeLiteral("false");
				return new McpBoolean(Boolean.FALSE);
			}

			if (c == 'n') {
				consumeLiteral("null");
				return McpNull.INSTANCE;
			}

			if (c == '-' || isDigit(c))
				return new McpNumber(parseNumber());

			throw parseException(format("Unexpected character '%s'", c));
		}

		@NonNull
		private McpObject parseObject() {
			expect('{');
			skipWhitespace();

			Map<String, McpValue> values = new LinkedHashMap<>();

			if (peek('}')) {
				index++;
				return new McpObject(values);
			}

			while (true) {
				skipWhitespace();

				if (current() != '"')
					throw parseException("Expected object property name");

				String name = parseString();
				skipWhitespace();
				expect(':');
				skipWhitespace();
				values.put(name, parseValue());
				skipWhitespace();

				if (peek('}')) {
					index++;
					return new McpObject(values);
				}

				expect(',');
			}
		}

		@NonNull
		private McpArray parseArray() {
			expect('[');
			skipWhitespace();

			List<McpValue> values = new ArrayList<>();

			if (peek(']')) {
				index++;
				return new McpArray(values);
			}

			while (true) {
				values.add(parseValue());
				skipWhitespace();

				if (peek(']')) {
					index++;
					return new McpArray(values);
				}

				expect(',');
			}
		}

		@NonNull
		private String parseString() {
			expect('"');
			StringBuilder value = new StringBuilder();

			while (!isAtEnd()) {
				char c = current();
				index++;

				if (c == '"')
					return value.toString();

				if (c == '\\') {
					value.append(parseEscapedCharacter());
					continue;
				}

				if (c < 0x20)
					throw parseException("Control characters must be escaped in JSON strings");

				value.append(c);
			}

			throw parseException("Unterminated string");
		}

		@NonNull
		private String parseEscapedCharacter() {
			if (isAtEnd())
				throw parseException("Unexpected end of input after escape character");

			char escaped = current();
			index++;

			return switch (escaped) {
				case '"', '\\', '/' -> String.valueOf(escaped);
				case 'b' -> "\b";
				case 'f' -> "\f";
				case 'n' -> "\n";
				case 'r' -> "\r";
				case 't' -> "\t";
				case 'u' -> parseUnicodeEscape();
				default -> throw parseException(format("Invalid JSON escape sequence '\\%s'", escaped));
			};
		}

		@NonNull
		private String parseUnicodeEscape() {
			char first = parseHexCharacter();

			if (!Character.isHighSurrogate(first))
				return String.valueOf(first);

			if (isAtEnd() || current() != '\\')
				throw parseException("Expected low surrogate after high surrogate");

			index++;

			if (isAtEnd() || current() != 'u')
				throw parseException("Expected unicode escape for low surrogate");

			index++;
			char second = parseHexCharacter();

			if (!Character.isLowSurrogate(second))
				throw parseException("Expected low surrogate escape sequence");

			return new String(Character.toChars(Character.toCodePoint(first, second)));
		}

		private char parseHexCharacter() {
			if (index + 4 > this.json.length())
				throw parseException("Incomplete unicode escape sequence");

			int codePoint = 0;

			for (int i = 0; i < 4; i++) {
				char c = this.json.charAt(index++);
				int digit = Character.digit(c, 16);

				if (digit < 0)
					throw parseException(format("Invalid unicode escape hex digit '%s'", c));

				codePoint = (codePoint << 4) | digit;
			}

			return (char) codePoint;
		}

		@NonNull
		private BigDecimal parseNumber() {
			int numberStart = this.index;

			if (peek('-'))
				index++;

			if (isAtEnd())
				throw parseException("Incomplete number");

			if (peek('0')) {
				index++;

				if (!isAtEnd() && isDigit(current()))
					throw parseException("Leading zeroes are not permitted in JSON numbers");
			} else {
				consumeDigits("Expected digit in number");
			}

			if (peek('.')) {
				index++;
				consumeDigits("Fractional JSON number part must contain at least one digit");
			}

			if (peek('e') || peek('E')) {
				index++;

				if (peek('+') || peek('-'))
					index++;

				consumeDigits("Exponent JSON number part must contain at least one digit");
			}

			return new BigDecimal(this.json.substring(numberStart, this.index));
		}

		private void consumeDigits(@NonNull String ifMissingDigitsMessage) {
			requireNonNull(ifMissingDigitsMessage);

			if (isAtEnd() || !isDigit(current()))
				throw parseException(ifMissingDigitsMessage);

			while (!isAtEnd() && isDigit(current()))
				index++;
		}

		private void consumeLiteral(@NonNull String literal) {
			requireNonNull(literal);

			if (this.json.regionMatches(this.index, literal, 0, literal.length())) {
				this.index += literal.length();
				return;
			}

			throw parseException(format("Expected literal '%s'", literal));
		}

		private void expect(char expected) {
			if (isAtEnd() || current() != expected)
				throw parseException(format("Expected '%s'", expected));

			index++;
		}

		private void skipWhitespace() {
			while (!isAtEnd()) {
				char c = current();

				if (c != ' ' && c != '\n' && c != '\r' && c != '\t')
					return;

				index++;
			}
		}

		private boolean peek(char c) {
			return !isAtEnd() && current() == c;
		}

		private boolean isAtEnd() {
			return this.index >= this.json.length();
		}

		private char current() {
			return this.json.charAt(this.index);
		}

		@NonNull
		private IllegalArgumentException parseException(@NonNull String message) {
			requireNonNull(message);
			return new IllegalArgumentException(format("%s at character %d", message, this.index));
		}

		private static boolean isDigit(char c) {
			return c >= '0' && c <= '9';
		}
	}
}
