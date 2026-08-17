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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the localization byte prediction equals what the production JSON
 * writer actually emits, which is what makes pre-commitment fit checks exact.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpLocalizationByteAccountingTests {
	private static final McpJsonCodec CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final String EMOJI =
			new String(Character.toChars(0x1F600));

	@Test
	void predictionMatchesTheProductionEncoderForEveryNonSurrogateCodeUnit() {
		List<String> mismatches = new ArrayList<>();

		for (int codeUnit = 0; codeUnit <= 0xFFFF; ++codeUnit) {
			if (Character.isSurrogate((char) codeUnit))
				continue;

			String value = String.valueOf((char) codeUnit);
			long predicted = McpLocalizationByteAccounting.encodedStringBytes(value);
			long actual = encodedLength(value);

			if (predicted != actual)
				mismatches.add(String.format("U+%04X predicted %d actual %d",
						codeUnit, predicted, actual));
		}

		assertEquals(List.of(), mismatches,
				"Every BMP code unit must be predicted exactly.");
	}

	@Test
	void predictionMatchesTheProductionEncoderForSupplementaryCodePoints() {
		List<String> mismatches = new ArrayList<>();

		for (int codePoint = Character.MIN_SUPPLEMENTARY_CODE_POINT;
				codePoint <= Character.MAX_CODE_POINT; codePoint += 977) {
			String value = new String(Character.toChars(codePoint));
			long predicted = McpLocalizationByteAccounting.encodedStringBytes(value);
			long actual = encodedLength(value);

			if (predicted != actual)
				mismatches.add(String.format("U+%06X predicted %d actual %d",
						codePoint, predicted, actual));
		}

		assertEquals(List.of(), mismatches);
		// A surrogate pair is two code units but exactly four encoded bytes.
		assertEquals(6, McpLocalizationByteAccounting.encodedStringBytes(EMOJI));
	}

	@Test
	void predictionMatchesTheProductionEncoderForEscapeHeavyMixedStrings() {
		List<String> corpus = List.of(
				"", "plain", "\"", "\\", "\"\\/\b\f\n\r\t",
				" ", "quote\"inside", "back\\slash",
				"tab\tnewline\ncarriage\r",
				"café", "éèê",
				"€中文", "emoji " + EMOJI + " tail",
				"mixed \"\\\n é 中 " + EMOJI + " end",
				EMOJI + "\"\\中");

		for (String value : corpus)
			assertEquals(encodedLength(value),
					McpLocalizationByteAccounting.encodedStringBytes(value),
					() -> "Mismatch for " + describe(value));
	}

	@Test
	void predictionMatchesTheProductionEncoderForRandomizedStrings() {
		Random random = new Random(20260813L);

		for (int iteration = 0; iteration < 2_000; ++iteration) {
			String value = randomString(random);
			assertEquals(encodedLength(value),
					McpLocalizationByteAccounting.encodedStringBytes(value),
					() -> "Mismatch for " + describe(value));
		}
	}

	@Test
	void unpairedSurrogatesAreRejectedExactlyLikeTheProductionEncoder() {
		String highSurrogate = String.valueOf((char) 0xD83D);
		String lowSurrogate = String.valueOf((char) 0xDE00);

		for (String value : List.of(highSurrogate, "lead" + highSurrogate,
				lowSurrogate, "trail" + lowSurrogate,
				highSurrogate + highSurrogate)) {
			assertThrows(IllegalArgumentException.class,
					() -> McpLocalizationByteAccounting.encodedStringBytes(value));
			assertThrows(IllegalArgumentException.class,
					() -> encodedLength(value),
					"The production encoder must reject this too.");
		}
	}

	@Test
	void replacementDeltaIsTheSignedDifferenceOfExactEncodedLengths() {
		assertEquals(0, McpLocalizationByteAccounting
				.replacementByteDelta("same", "same"));

		// One ASCII character becomes a three-byte character plus an escape.
		long delta = McpLocalizationByteAccounting
				.replacementByteDelta("a", "中\"");
		assertEquals(encodedLength("中\"") - encodedLength("a"), delta);
		assertTrue(delta > 0);

		// A shorter replacement must reclaim budget rather than clamp at zero.
		assertTrue(McpLocalizationByteAccounting
				.replacementByteDelta("a longer default", "x") < 0);
	}

	@Test
	void serializedTokenCharacterPredictionMatchesProductionEscaping() {
		assertEquals(0, McpLocalizationByteAccounting
				.serializedTokenCharacters(""));
		assertEquals(3, McpLocalizationByteAccounting
				.serializedTokenCharacters("abc"));
		assertEquals(2, McpLocalizationByteAccounting
				.serializedTokenCharacters("\n"));
		assertEquals(6, McpLocalizationByteAccounting
				.serializedTokenCharacters("\0"));
		assertEquals(2, McpLocalizationByteAccounting
				.serializedTokenCharacters(EMOJI));
	}

	private static long encodedLength(String value) {
		// The internal writer is what actually produces response bytes; the
		// same-named public value type is a different hierarchy.
		return CODEC.toUtf8Bytes(
				new com.soklet.internal.mcp.protocol.McpJsonString(value)).length;
	}

	private static String randomString(Random random) {
		StringBuilder builder = new StringBuilder();
		int length = random.nextInt(12);

		for (int index = 0; index < length; ++index) {
			int choice = random.nextInt(10);

			if (choice < 3)
				builder.append((char) (0x20 + random.nextInt(0x5F)));
			else if (choice < 5)
				builder.append("\"\\\b\f\n\r\t".charAt(random.nextInt(7)));
			else if (choice < 6)
				builder.append((char) random.nextInt(0x20));
			else if (choice < 7)
				builder.append((char) (0x80 + random.nextInt(0x780)));
			else if (choice < 9)
				// Skip the surrogate block: lone surrogates are invalid input and
				// have their own dedicated rejection test.
				builder.append((char) (random.nextBoolean()
						? 0x800 + random.nextInt(0xD800 - 0x800)
						: 0xE000 + random.nextInt(0x10000 - 0xE000)));
			else
				builder.appendCodePoint(Character.MIN_SUPPLEMENTARY_CODE_POINT
						+ random.nextInt(0x1000));
		}

		return builder.toString();
	}

	private static String describe(String value) {
		StringBuilder builder = new StringBuilder();
		value.codePoints().forEach(codePoint ->
				builder.append(String.format("U+%04X ", codePoint)));
		return builder.toString();
	}
}
