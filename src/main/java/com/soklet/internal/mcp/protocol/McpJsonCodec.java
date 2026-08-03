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

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Strict, bounded UTF-8 JSON codec for MCP protocol values.
 *
 * <p>Token and decoded-string limits count Java UTF-16 code units. Input and
 * output limits count exact UTF-8 bytes. Every JSON value, including the root
 * and containers, counts as one node; property names do not. The root has
 * depth one and each contained value increments the depth.</p>
 */
public final class McpJsonCodec {
	private final McpJsonLimits limits;

	public McpJsonCodec(McpJsonLimits limits) {
		this.limits = requireNonNull(limits);
	}

	public McpJsonLimits limits() {
		return limits;
	}

	public McpJsonValue parse(byte[] utf8) {
		requireNonNull(utf8);

		if (utf8.length > limits.maximumInputBytes())
			throw new IllegalArgumentException("JSON input exceeds the configured UTF-8 byte limit.");

		return parseDecoded(decodeUtf8(utf8));
	}

	public McpJsonValue parse(String json) {
		requireNonNull(json);
		validateUtf8Length(json, limits.maximumInputBytes(), "JSON input");
		return parseDecoded(json);
	}

	public byte[] toUtf8Bytes(McpJsonValue value) {
		requireNonNull(value);
		BoundedUtf8Writer writer = new BoundedUtf8Writer(limits.maximumOutputBytes());
		new JsonWriter(writer).write(value);
		return writer.toByteArray();
	}

	public String toJson(McpJsonValue value) {
		return new String(toUtf8Bytes(value), StandardCharsets.UTF_8);
	}

	private McpJsonValue parseDecoded(String json) {
		if (!json.isEmpty() && json.charAt(0) == '\uFEFF')
			throw new IllegalArgumentException("A leading byte-order mark is not permitted at character offset 0.");

		return new JsonParser(json).parse();
	}

	private static String decodeUtf8(byte[] utf8) {
		ByteBuffer input = ByteBuffer.wrap(utf8);
		CharBuffer output = CharBuffer.allocate(utf8.length);
		var decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		CoderResult result = decoder.decode(input, output, true);

		if (result.isError())
			throw new IllegalArgumentException(
					"Malformed UTF-8 at byte offset " + input.position() + ".");

		result = decoder.flush(output);

		if (result.isError())
			throw new IllegalArgumentException(
					"Malformed UTF-8 at byte offset " + input.position() + ".");

		output.flip();
		return output.toString();
	}

	private static void validateUtf8Length(String value, int maximumBytes, String description) {
		long encodedLength = 0;

		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);

			if (character <= 0x7F) {
				encodedLength++;
			} else if (character <= 0x7FF) {
				encodedLength += 2;
			} else if (Character.isHighSurrogate(character)) {
				if (index + 1 >= value.length()
						|| !Character.isLowSurrogate(value.charAt(index + 1)))
					throw new IllegalArgumentException(description
							+ " contains an unpaired high surrogate at character offset "
							+ index + ".");

				encodedLength += 4;
				index++;
			} else if (Character.isLowSurrogate(character)) {
				throw new IllegalArgumentException(description
						+ " contains an unpaired low surrogate at character offset "
						+ index + ".");
			} else {
				encodedLength += 3;
			}

			if (encodedLength > maximumBytes)
				throw new IllegalArgumentException(
						description + " exceeds the configured UTF-8 byte limit.");
		}
	}

	private final class JsonParser {
		private final String source;
		private int index;
		private int nodeCount;

		private JsonParser(String source) {
			this.source = source;
		}

		private McpJsonValue parse() {
			skipWhitespace();
			McpJsonValue value = parseValue(1);
			skipWhitespace();

			if (!atEnd())
				throw error("Unexpected trailing content");

			return value;
		}

		private McpJsonValue parseValue(int depth) {
			skipWhitespace();
			validateDepth(depth);

			if (atEnd())
				throw error("Expected a JSON value");

			incrementNodeCount();
			char character = current();

			return switch (character) {
				case '{' -> parseObject(depth);
				case '[' -> parseArray(depth);
				case '"' -> new McpJsonString(parseString());
				case 't' -> {
					consumeLiteral("true");
					yield McpJsonBoolean.TRUE;
				}
				case 'f' -> {
					consumeLiteral("false");
					yield McpJsonBoolean.FALSE;
				}
				case 'n' -> {
					consumeLiteral("null");
					yield McpJsonNull.INSTANCE;
				}
				default -> {
					if (character == '-' || asciiDigit(character))
						yield new McpJsonNumber(parseNumber());

					throw error("Unexpected character");
				}
			};
		}

		private McpJsonObject parseObject(int depth) {
			expect('{');
			skipWhitespace();
			Map<String, McpJsonValue> members = new LinkedHashMap<>();

			if (peek('}')) {
				index++;
				return new McpJsonObject(members);
			}

			while (true) {
				skipWhitespace();

				if (!peek('"'))
					throw error("Expected an object property name");

				String name = parseString();

				if (members.containsKey(name))
					throw error("Duplicate object property");

				skipWhitespace();
				expect(':');
				members.put(name, parseValue(depth + 1));
				skipWhitespace();

				if (peek('}')) {
					index++;
					return new McpJsonObject(members);
				}

				expect(',');
			}
		}

		private McpJsonArray parseArray(int depth) {
			expect('[');
			skipWhitespace();
			List<McpJsonValue> values = new ArrayList<>();

			if (peek(']')) {
				index++;
				return new McpJsonArray(values);
			}

			while (true) {
				values.add(parseValue(depth + 1));
				skipWhitespace();

				if (peek(']')) {
					index++;
					return new McpJsonArray(values);
				}

				expect(',');
			}
		}

		private String parseString() {
			expect('"');
			int tokenStart = index;
			StringBuilder value = new StringBuilder();

			while (true) {
				if (atEnd())
					throw error("Unterminated string");

				if (peek('"')) {
					index++;
					return value.toString();
				}

				char character = consumeStringTokenCharacter(tokenStart);

				if (character == '\\') {
					appendDecoded(value, parseEscape(tokenStart));
					continue;
				}

				if (character < 0x20)
					throw error("Control characters must be escaped in JSON strings");

				if (Character.isHighSurrogate(character)) {
					if (atEnd() || !Character.isLowSurrogate(current()))
						throw error("Expected a low surrogate after a high surrogate");

					char lowSurrogate = consumeStringTokenCharacter(tokenStart);
					appendDecoded(value, new String(new char[]{character, lowSurrogate}));
				} else if (Character.isLowSurrogate(character)) {
					throw error("Unexpected low surrogate");
				} else {
					appendDecoded(value, String.valueOf(character));
				}
			}
		}

		private String parseEscape(int tokenStart) {
			if (atEnd())
				throw error("Unexpected end of input after an escape character");

			char escaped = consumeStringTokenCharacter(tokenStart);

			return switch (escaped) {
				case '"', '\\', '/' -> String.valueOf(escaped);
				case 'b' -> "\b";
				case 'f' -> "\f";
				case 'n' -> "\n";
				case 'r' -> "\r";
				case 't' -> "\t";
				case 'u' -> parseUnicodeEscape(tokenStart);
				default -> throw error("Invalid JSON escape sequence");
			};
		}

		private String parseUnicodeEscape(int tokenStart) {
			char first = parseHexCharacter(tokenStart);

			if (Character.isLowSurrogate(first))
				throw error("Unexpected escaped low surrogate");

			if (!Character.isHighSurrogate(first))
				return String.valueOf(first);

			if (atEnd() || current() != '\\')
				throw error("Expected an escaped low surrogate after an escaped high surrogate");

			consumeStringTokenCharacter(tokenStart);

			if (atEnd() || current() != 'u')
				throw error("Expected an escaped low surrogate after an escaped high surrogate");

			consumeStringTokenCharacter(tokenStart);
			char second = parseHexCharacter(tokenStart);

			if (!Character.isLowSurrogate(second))
				throw error("Expected an escaped low surrogate after an escaped high surrogate");

			return new String(new char[]{first, second});
		}

		private char parseHexCharacter(int tokenStart) {
			int value = 0;

			for (int count = 0; count < 4; ++count) {
				if (atEnd())
					throw error("Incomplete unicode escape");

				char character = consumeStringTokenCharacter(tokenStart);
				int digit = asciiHexDigit(character);

				if (digit < 0)
					throw error("Invalid unicode escape hex digit");

				value = value * 16 + digit;
			}

			return (char) value;
		}

		private char consumeStringTokenCharacter(int tokenStart) {
			char character = current();
			index++;

			if (index - tokenStart > limits.maximumTokenLengthInCharacters())
				throw error("JSON token exceeds the configured character limit");

			return character;
		}

		private void appendDecoded(StringBuilder value, String addition) {
			if ((long) value.length() + addition.length()
					> limits.maximumStringLengthInCharacters())
				throw error("Decoded JSON string exceeds the configured character limit");

			value.append(addition);
		}

		private BigDecimal parseNumber() {
			int start = index;

			if (peek('-'))
				consumeNumberCharacter(start);

			if (atEnd())
				throw error("Incomplete number");

			if (peek('0')) {
				consumeNumberCharacter(start);

				if (!atEnd() && asciiDigit(current()))
					throw error("Leading zeroes are not permitted in JSON numbers");
			} else {
				consumeDigits(start, "Expected a digit in the integer part");
			}

			if (peek('.')) {
				consumeNumberCharacter(start);
				consumeDigits(start, "A fractional part must contain at least one digit");
			}

			if (peek('e') || peek('E')) {
				consumeNumberCharacter(start);

				if (peek('+') || peek('-'))
					consumeNumberCharacter(start);

				consumeExponentDigits(start);
			}

			String token = source.substring(start, index);
			BigDecimal value;

			try {
				value = new BigDecimal(token);
			} catch (NumberFormatException exception) {
				throw error("Invalid JSON number");
			}

			validateNumberForSerialization(value);
			return value;
		}

		private void consumeDigits(int start, String missingMessage) {
			if (atEnd() || !asciiDigit(current()))
				throw error(missingMessage);

			while (!atEnd() && asciiDigit(current()))
				consumeNumberCharacter(start);
		}

		private void consumeExponentDigits(int start) {
			if (atEnd() || !asciiDigit(current()))
				throw error("An exponent must contain at least one digit");

			int magnitude = 0;

			while (!atEnd() && asciiDigit(current())) {
				int digit = current() - '0';

				if (magnitude > limits.maximumExponentMagnitude() / 10
						|| (magnitude == limits.maximumExponentMagnitude() / 10
						&& digit > limits.maximumExponentMagnitude() % 10))
					throw error("JSON number exponent exceeds the configured magnitude limit");

				magnitude = magnitude * 10 + digit;
				consumeNumberCharacter(start);
			}
		}

		private void consumeNumberCharacter(int start) {
			index++;
			int length = index - start;

			if (length > limits.maximumNumberLengthInCharacters()
					|| length > limits.maximumTokenLengthInCharacters())
				throw error("JSON number exceeds the configured character limit");
		}

		private void consumeLiteral(String literal) {
			if (literal.length() > limits.maximumTokenLengthInCharacters())
				throw error("JSON token exceeds the configured character limit");

			if (!source.regionMatches(index, literal, 0, literal.length()))
				throw error("Invalid JSON literal");

			index += literal.length();
		}

		private void validateDepth(int depth) {
			if (depth > limits.maximumNestingDepth())
				throw error("JSON nesting exceeds the configured depth limit");
		}

		private void incrementNodeCount() {
			nodeCount++;

			if (nodeCount > limits.maximumNodeCount())
				throw error("JSON input exceeds the configured node limit");
		}

		private void expect(char expected) {
			if (atEnd() || current() != expected)
				throw error("Expected JSON syntax character");

			index++;
		}

		private void skipWhitespace() {
			while (!atEnd()) {
				char character = current();

				if (character != ' ' && character != '\t'
						&& character != '\r' && character != '\n')
					return;

				index++;
			}
		}

		private boolean peek(char expected) {
			return !atEnd() && current() == expected;
		}

		private boolean atEnd() {
			return index >= source.length();
		}

		private char current() {
			return source.charAt(index);
		}

		private IllegalArgumentException error(String message) {
			return new IllegalArgumentException(
					message + " at character offset " + index + ".");
		}
	}

	private final class JsonWriter {
		private final BoundedUtf8Writer output;
		private int nodeCount;

		private JsonWriter(BoundedUtf8Writer output) {
			this.output = output;
		}

		private void write(McpJsonValue value) {
			writeValue(value, 1);
		}

		private void writeValue(McpJsonValue value, int depth) {
			requireNonNull(value);

			if (depth > limits.maximumNestingDepth())
				throw new IllegalArgumentException(
						"JSON output exceeds the configured depth limit.");

			nodeCount++;

			if (nodeCount > limits.maximumNodeCount())
				throw new IllegalArgumentException(
						"JSON output exceeds the configured node limit.");

			if (value instanceof McpJsonObject object) {
				writeObject(object, depth);
			} else if (value instanceof McpJsonArray array) {
				writeArray(array, depth);
			} else if (value instanceof McpJsonString string) {
				writeString(string.value());
			} else if (value instanceof McpJsonNumber number) {
				writeNumber(number.value());
			} else if (value == McpJsonBoolean.TRUE) {
				writeLiteral("true");
			} else if (value == McpJsonBoolean.FALSE) {
				writeLiteral("false");
			} else if (value == McpJsonNull.INSTANCE) {
				writeLiteral("null");
			} else {
				throw new IllegalArgumentException("Unsupported JSON value implementation.");
			}
		}

		private void writeObject(McpJsonObject object, int depth) {
			output.writeAscii('{');
			boolean first = true;

			for (Map.Entry<String, McpJsonValue> member : object.members().entrySet()) {
				if (!first)
					output.writeAscii(',');

				writeString(member.getKey());
				output.writeAscii(':');
				writeValue(member.getValue(), depth + 1);
				first = false;
			}

			output.writeAscii('}');
		}

		private void writeArray(McpJsonArray array, int depth) {
			output.writeAscii('[');

			for (int index = 0; index < array.values().size(); ++index) {
				if (index > 0)
					output.writeAscii(',');

				writeValue(array.values().get(index), depth + 1);
			}

			output.writeAscii(']');
		}

		private void writeString(String value) {
			requireNonNull(value);

			if (value.length() > limits.maximumStringLengthInCharacters())
				throw new IllegalArgumentException(
						"JSON string exceeds the configured decoded-character limit.");

			output.writeAscii('"');
			int tokenCharacters = 0;

			for (int index = 0; index < value.length(); ++index) {
				char character = value.charAt(index);

				switch (character) {
					case '"' -> {
						tokenCharacters = addTokenCharacters(tokenCharacters, 2);
						output.writeAscii("\\\"");
					}
					case '\\' -> {
						tokenCharacters = addTokenCharacters(tokenCharacters, 2);
						output.writeAscii("\\\\");
					}
					case '\b' -> {
						tokenCharacters = addTokenCharacters(tokenCharacters, 2);
						output.writeAscii("\\b");
					}
					case '\f' -> {
						tokenCharacters = addTokenCharacters(tokenCharacters, 2);
						output.writeAscii("\\f");
					}
					case '\n' -> {
						tokenCharacters = addTokenCharacters(tokenCharacters, 2);
						output.writeAscii("\\n");
					}
					case '\r' -> {
						tokenCharacters = addTokenCharacters(tokenCharacters, 2);
						output.writeAscii("\\r");
					}
					case '\t' -> {
						tokenCharacters = addTokenCharacters(tokenCharacters, 2);
						output.writeAscii("\\t");
					}
					default -> {
						if (character < 0x20) {
							tokenCharacters = addTokenCharacters(tokenCharacters, 6);
							writeUnicodeEscape(character);
						} else if (Character.isHighSurrogate(character)) {
							if (index + 1 >= value.length()
									|| !Character.isLowSurrogate(value.charAt(index + 1)))
								throw new IllegalArgumentException(
										"JSON string contains an unpaired high surrogate.");

							char lowSurrogate = value.charAt(++index);
							tokenCharacters = addTokenCharacters(tokenCharacters, 2);
							output.writeCodePoint(Character.toCodePoint(character, lowSurrogate));
						} else if (Character.isLowSurrogate(character)) {
							throw new IllegalArgumentException(
									"JSON string contains an unpaired low surrogate.");
						} else {
							tokenCharacters = addTokenCharacters(tokenCharacters, 1);
							output.writeCodePoint(character);
						}
					}
				}
			}

			output.writeAscii('"');
		}

		private int addTokenCharacters(int currentCount, int addition) {
			long newCount = (long) currentCount + addition;

			if (newCount > limits.maximumTokenLengthInCharacters())
				throw new IllegalArgumentException(
						"Serialized JSON token exceeds the configured character limit.");

			return (int) newCount;
		}

		private void writeUnicodeEscape(char character) {
			char[] hexadecimal = "0123456789abcdef".toCharArray();
			output.writeAscii('\\');
			output.writeAscii('u');
			output.writeAscii(hexadecimal[(character >>> 12) & 0x0F]);
			output.writeAscii(hexadecimal[(character >>> 8) & 0x0F]);
			output.writeAscii(hexadecimal[(character >>> 4) & 0x0F]);
			output.writeAscii(hexadecimal[character & 0x0F]);
		}

		private void writeNumber(BigDecimal value) {
			requireNonNull(value);
			validateNumberForSerialization(value);
			String token = value.toString();

			if (token.length() > limits.maximumNumberLengthInCharacters()
					|| token.length() > limits.maximumTokenLengthInCharacters())
				throw new IllegalArgumentException(
						"Canonical JSON number exceeds the configured character limit.");

			output.writeAscii(token);
		}

		private void writeLiteral(String literal) {
			if (literal.length() > limits.maximumTokenLengthInCharacters())
				throw new IllegalArgumentException(
						"Serialized JSON token exceeds the configured character limit.");

			output.writeAscii(literal);
		}
	}

	private void validateNumberForSerialization(BigDecimal value) {
		int precision = value.precision();
		long adjustedExponent = (long) precision - value.scale() - 1;

		if (Math.abs(adjustedExponent) > limits.maximumExponentMagnitude())
			throw new IllegalArgumentException(
					"Canonical JSON number exceeds the configured exponent magnitude limit.");

		long canonicalLength = canonicalNumberLength(value, precision, adjustedExponent);

		if (canonicalLength > limits.maximumNumberLengthInCharacters()
				|| canonicalLength > limits.maximumTokenLengthInCharacters())
			throw new IllegalArgumentException(
					"Canonical JSON number exceeds the configured character limit.");
	}

	private static long canonicalNumberLength(
			BigDecimal value, int precision, long adjustedExponent) {
		long signLength = value.signum() < 0 ? 1 : 0;
		int scale = value.scale();

		if (scale >= 0 && adjustedExponent >= -6) {
			if (scale == 0)
				return signLength + precision;

			if (adjustedExponent >= 0)
				return signLength + precision + 1;

			return signLength + precision - adjustedExponent + 1;
		}

		long significandLength = precision == 1 ? 1 : (long) precision + 1;
		return signLength + significandLength + 2 + decimalDigitCount(Math.abs(adjustedExponent));
	}

	private static int decimalDigitCount(long value) {
		int digits = 1;

		while (value >= 10) {
			value /= 10;
			digits++;
		}

		return digits;
	}

	private static int asciiHexDigit(char character) {
		if (character >= '0' && character <= '9')
			return character - '0';

		if (character >= 'A' && character <= 'F')
			return character - 'A' + 10;

		if (character >= 'a' && character <= 'f')
			return character - 'a' + 10;

		return -1;
	}

	private static boolean asciiDigit(char character) {
		return character >= '0' && character <= '9';
	}

	private static final class BoundedUtf8Writer {
		private final int maximumBytes;
		private byte[] bytes;
		private int size;

		private BoundedUtf8Writer(int maximumBytes) {
			this.maximumBytes = maximumBytes;
			this.bytes = new byte[Math.min(maximumBytes, 256)];
		}

		private void writeAscii(char character) {
			ensureCapacity(1);
			bytes[size++] = (byte) character;
		}

		private void writeAscii(String value) {
			ensureCapacity(value.length());

			for (int index = 0; index < value.length(); ++index)
				bytes[size++] = (byte) value.charAt(index);
		}

		private void writeCodePoint(int codePoint) {
			if (codePoint <= 0x7F) {
				ensureCapacity(1);
				bytes[size++] = (byte) codePoint;
			} else if (codePoint <= 0x7FF) {
				ensureCapacity(2);
				bytes[size++] = (byte) (0xC0 | (codePoint >>> 6));
				bytes[size++] = (byte) (0x80 | (codePoint & 0x3F));
			} else if (codePoint <= 0xFFFF) {
				ensureCapacity(3);
				bytes[size++] = (byte) (0xE0 | (codePoint >>> 12));
				bytes[size++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3F));
				bytes[size++] = (byte) (0x80 | (codePoint & 0x3F));
			} else {
				ensureCapacity(4);
				bytes[size++] = (byte) (0xF0 | (codePoint >>> 18));
				bytes[size++] = (byte) (0x80 | ((codePoint >>> 12) & 0x3F));
				bytes[size++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3F));
				bytes[size++] = (byte) (0x80 | (codePoint & 0x3F));
			}
		}

		private void ensureCapacity(int additionalBytes) {
			long requiredCapacity = (long) size + additionalBytes;

			if (requiredCapacity > maximumBytes)
				throw new IllegalArgumentException(
						"JSON output exceeds the configured UTF-8 byte limit.");

			if (requiredCapacity <= bytes.length)
				return;

			long doubledCapacity = bytes.length == 0 ? 1 : (long) bytes.length * 2;
			int newCapacity = (int) Math.min(maximumBytes,
					Math.max(requiredCapacity, doubledCapacity));
			bytes = Arrays.copyOf(bytes, newCapacity);
		}

		private byte[] toByteArray() {
			return Arrays.copyOf(bytes, size);
		}
	}
}
