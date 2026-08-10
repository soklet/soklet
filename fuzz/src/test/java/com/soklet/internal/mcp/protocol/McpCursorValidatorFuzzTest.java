/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Assertions;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Coverage-guided checks for total, exact cursor UTF-8 bound validation.
 */
@ThreadSafe
public class McpCursorValidatorFuzzTest {
	private static final int MAXIMUM_FUZZ_INPUT_BYTES = 64 * 1_024;

	@FuzzTest(maxDuration = "2m")
	public void cursorValidationIsUtf8ExactAndTotal(byte[] input) {
		byte[] bounded = input.length <= MAXIMUM_FUZZ_INPUT_BYTES
				? input : Arrays.copyOf(input, MAXIMUM_FUZZ_INPUT_BYTES);
		int maximumBytes = bounded.length == 0
				? 1 : Byte.toUnsignedInt(bounded[0]) + 1;

		assertExact(new String(bounded, StandardCharsets.UTF_8), maximumBytes);
		assertExact(rawUtf16(bounded), maximumBytes);
	}

	private static void assertExact(String value, int maximumBytes) {
		Assertions.assertEquals(expected(value, maximumBytes),
				McpCursorValidator.fitsWithinUtf8ByteLimit(value, maximumBytes));
	}

	private static boolean expected(String value, int maximumBytes) {
		try {
			ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.encode(CharBuffer.wrap(value));
			return encoded.remaining() <= maximumBytes;
		} catch (CharacterCodingException expected) {
			return false;
		}
	}

	private static String rawUtf16(byte[] input) {
		char[] characters = new char[(input.length + 1) / 2];
		for (int index = 0; index < characters.length; index++) {
			int high = Byte.toUnsignedInt(input[index * 2]);
			int lowIndex = index * 2 + 1;
			int low = lowIndex < input.length
					? Byte.toUnsignedInt(input[lowIndex]) : 0;
			characters[index] = (char) ((high << 8) | low);
		}
		return new String(characters);
	}
}
