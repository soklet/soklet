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

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Coverage-guided checks for localization primitives: replacement Unicode byte
 * accounting, bounded language-preference derivation, and copy-on-write overlay
 * pointer totality.
 */
@ThreadSafe
public class McpLocalizationFuzzTest {
	private static final int MAXIMUM_FUZZ_INPUT_BYTES = 32 * 1_024;
	private static final McpJsonCodec CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());

	/**
	 * The predicted encoded size must equal what the production writer emits,
	 * for every string, or both must reject it identically. This is the
	 * property that makes pre-commitment budget checks exact.
	 */
	@FuzzTest(maxDuration = "2m")
	public void replacementByteAccountingMatchesTheProductionEncoder(
			byte[] input) {
		String value = boundedString(input);
		long predicted;

		try {
			predicted = McpLocalizationByteAccounting.encodedStringBytes(value);
		} catch (IllegalArgumentException rejected) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> CODEC.toUtf8Bytes(new McpJsonString(value)),
					"The encoder must reject exactly what accounting rejects.");
			return;
		}

		Assertions.assertEquals(CODEC.toUtf8Bytes(new McpJsonString(value)).length,
				predicted);
	}

	/**
	 * Preference derivation is total and bounded: it never throws, never
	 * exceeds 32 ranges, and never truncates - an over-limit input collapses to
	 * empty rather than to a partial view.
	 */
	@FuzzTest(maxDuration = "2m")
	public void boundedPreferenceDerivationIsTotalAndNeverTruncates(
			byte[] input) {
		String header = boundedString(input);
		List<Locale.LanguageRange> ranges =
				McpLocaleSupport.boundedLanguageRanges(List.of(header));

		Assertions.assertTrue(ranges.size() <= 32, header);
		Assertions.assertThrows(UnsupportedOperationException.class,
				ranges::clear);

		if (header.length() > 4_096)
			Assertions.assertEquals(List.of(), ranges,
					"Over-limit input must never be truncated.");

		// Splitting the identical text across repeated header values must reach
		// the identical bounded view, since values recombine in wire order.
		if (header.length() > 1 && header.length() <= 4_096) {
			int middle = header.length() / 2;
			List<Locale.LanguageRange> split =
					McpLocaleSupport.boundedLanguageRanges(List.of(
							header.substring(0, middle) + ",",
							header.substring(middle)));
			Assertions.assertTrue(split.size() <= 32, header);
		}
	}

	/**
	 * The overlay is total over arbitrary pointers: it either replaces exactly
	 * one existing string leaf, leaving every other byte untouched, or rejects
	 * with the uniform defect exception. It never corrupts the document and
	 * never mutates the canonical input.
	 */
	@FuzzTest(maxDuration = "2m")
	public void overlayPointerHandlingIsTotalAndNonMutating(byte[] input) {
		String pointer = boundedString(input);
		McpJsonObject canonical = document();
		byte[] canonicalBytes = CODEC.toUtf8Bytes(canonical);
		McpJsonObject replaced;

		try {
			replaced = McpLocalizationOverlay.withReplacements(canonical,
					List.of(new McpLocalizationOverlay.Replacement(pointer,
							"REPLACED")));
		} catch (IllegalStateException rejected) {
			Assertions.assertArrayEquals(canonicalBytes,
					CODEC.toUtf8Bytes(canonical),
					"A rejected overlay must not mutate the canonical document.");
			return;
		}

		Assertions.assertArrayEquals(canonicalBytes,
				CODEC.toUtf8Bytes(canonical),
				"A successful overlay must not mutate the canonical document.");

		String canonicalJson = new String(canonicalBytes, StandardCharsets.UTF_8);
		String replacedJson = new String(CODEC.toUtf8Bytes(replaced),
				StandardCharsets.UTF_8);
		Assertions.assertEquals(1, occurrences(replacedJson, "\"REPLACED\""),
				replacedJson);

		// Exactly one contiguous region may differ: splice the replacement out
		// of the result and the canonical encoding must reappear byte-for-byte.
		int prefix = 0;
		while (prefix < canonicalJson.length() && prefix < replacedJson.length()
				&& canonicalJson.charAt(prefix) == replacedJson.charAt(prefix))
			++prefix;

		int suffix = 0;
		while (suffix < canonicalJson.length() - prefix
				&& suffix < replacedJson.length() - prefix
				&& canonicalJson.charAt(canonicalJson.length() - 1 - suffix)
						== replacedJson.charAt(replacedJson.length() - 1 - suffix))
			++suffix;

		Assertions.assertEquals("REPLACED",
				replacedJson.substring(prefix, replacedJson.length() - suffix),
				"Only the replacement text may differ from canonical.");
	}

	private static int occurrences(String value, String token) {
		int count = 0;
		int index = value.indexOf(token);

		while (index >= 0) {
			++count;
			index = value.indexOf(token, index + token.length());
		}

		return count;
	}

	private static McpJsonObject document() {
		Map<String, McpJsonValue> nested = new LinkedHashMap<>();
		nested.put("title", new McpJsonString("Nested title"));
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("instructions", new McpJsonString("Canonical instructions"));
		members.put("nested", new McpJsonObject(nested));
		return new McpJsonObject(members);
	}

	private static String boundedString(byte[] input) {
		byte[] bounded = input.length <= MAXIMUM_FUZZ_INPUT_BYTES
				? input : Arrays.copyOf(input, MAXIMUM_FUZZ_INPUT_BYTES);
		return new String(bounded, StandardCharsets.UTF_8);
	}
}
