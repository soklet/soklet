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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static java.util.Objects.requireNonNull;

/**
 * Decodes string-valued MCP mirrors without ever reflecting their untrusted
 * values into an exception. The sentinel markers deliberately remain
 * case-sensitive.
 */
final class McpMirroredHeaderCodec {
	static final int DEFAULT_MAXIMUM_DECODED_BYTES = 16 * 1_024;
	private static final String BASE64_PREFIX = "=?base64?";
	private static final String BASE64_SUFFIX = "?=";
	private static final String INVALID_VALUE = "Invalid mirrored header value.";

	private final int maximumDecodedBytes;

	McpMirroredHeaderCodec(int maximumDecodedBytes) {
		if (maximumDecodedBytes < 1)
			throw new IllegalArgumentException(
					"Maximum decoded mirrored-header bytes must be positive.");
		this.maximumDecodedBytes = maximumDecodedBytes;
	}

	String decodeString(String encodedValue) {
		requireNonNull(encodedValue);

		if (encodedValue.startsWith(BASE64_PREFIX)
				&& encodedValue.endsWith(BASE64_SUFFIX))
			return decodeBase64(encodedValue.substring(BASE64_PREFIX.length(),
					encodedValue.length() - BASE64_SUFFIX.length()));

		validatePlainValue(encodedValue);
		return encodedValue;
	}

	String requirePlainString(String value) {
		requireNonNull(value);
		validatePlainValue(value);
		return value;
	}

	private String decodeBase64(String payload) {
		long maximumEncodedBytes = ((long) maximumDecodedBytes + 2L) / 3L * 4L;
		if (payload.length() > maximumEncodedBytes || !canonicalBase64Shape(payload))
			throw invalidValue();

		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(payload);
		} catch (IllegalArgumentException exception) {
			throw invalidValue();
		}
		if (decoded.length > maximumDecodedBytes
				|| !Base64.getEncoder().encodeToString(decoded).equals(payload))
			throw invalidValue();

		try {
			CharBuffer characters = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(decoded));
			return characters.toString();
		} catch (CharacterCodingException exception) {
			throw invalidValue();
		}
	}

	private boolean canonicalBase64Shape(String payload) {
		if (payload.length() % 4 != 0)
			return false;

		int padding = 0;
		if (!payload.isEmpty() && payload.charAt(payload.length() - 1) == '=') {
			padding = 1;
			if (payload.length() > 1 && payload.charAt(payload.length() - 2) == '=')
				padding = 2;
		}
		int dataLength = payload.length() - padding;
		for (int index = 0; index < dataLength; index++) {
			char character = payload.charAt(index);
			if (!(character >= 'A' && character <= 'Z')
					&& !(character >= 'a' && character <= 'z')
					&& !(character >= '0' && character <= '9')
					&& character != '+' && character != '/')
				return false;
		}
		for (int index = dataLength; index < payload.length(); index++) {
			if (payload.charAt(index) != '=')
				return false;
		}
		return true;
	}

	private void validatePlainValue(String value) {
		if (value.length() > maximumDecodedBytes)
			throw invalidValue();
		if (!value.isEmpty() && (optionalWhitespace(value.charAt(0))
				|| optionalWhitespace(value.charAt(value.length() - 1))))
			throw invalidValue();

		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character != '\t' && (character < 0x20 || character > 0x7E))
				throw invalidValue();
		}
	}

	private boolean optionalWhitespace(char character) {
		return character == ' ' || character == '\t';
	}

	private IllegalArgumentException invalidValue() {
		return new IllegalArgumentException(INVALID_VALUE);
	}
}
