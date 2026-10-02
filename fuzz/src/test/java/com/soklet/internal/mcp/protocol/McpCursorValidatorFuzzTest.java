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
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Coverage-guided checks for total cursor framing, exact UTF-8 validation,
 * and bounded framework-owned legacy navigation under current authority.
 */
@ThreadSafe
public class McpCursorValidatorFuzzTest {
	private static final int MAXIMUM_FUZZ_INPUT_BYTES = 64 * 1_024;
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final McpJsonRpcId REQUEST_ID = new McpJsonRpcId.IntegerId(BigInteger.ONE);
	private static final List<String> OWNERS = List.of("a", "b", "c");
	private static final List<McpLegacyCatalogPager> LEGACY_PAGERS = List.of(
			legacyPager(Mcp2025ProtocolProfile.JUNE_18),
			legacyPager(Mcp2025ProtocolProfile.NOVEMBER_25));

	@FuzzTest(maxDuration = "2m")
	public void cursorValidationIsUtf8ExactAndTotal(byte[] input) throws Exception {
		byte[] bounded = input.length <= MAXIMUM_FUZZ_INPUT_BYTES
				? input : Arrays.copyOf(input, MAXIMUM_FUZZ_INPUT_BYTES);
		int maximumBytes = bounded.length == 0
				? 1 : Byte.toUnsignedInt(bounded[0]) + 1;

		assertExact(new String(bounded, StandardCharsets.UTF_8), maximumBytes);
		assertExact(rawUtf16(bounded), maximumBytes);
		assertLegacyDecode(new String(bounded, StandardCharsets.UTF_8));
		assertLegacyDecode(rawUtf16(bounded));
		assertLegacyDecode(Base64.getUrlEncoder().withoutPadding().encodeToString(
				Arrays.copyOf(bounded, Math.min(bounded.length, 512))));
		for (McpLegacyCatalogPager pager : LEGACY_PAGERS)
			assertLegacyPaging(pager, bounded);
	}

	private static void assertLegacyDecode(String token) {
		Optional<McpLegacyCatalogPager.Cursor> decoded = McpLegacyCatalogPager.decode(token);
		if (decoded.isEmpty())
			return;
		McpLegacyCatalogPager.Cursor cursor = decoded.orElseThrow();
		Assertions.assertTrue(token.length() <= McpLegacyCatalogPager.MAXIMUM_CURSOR_BYTES);
		Assertions.assertTrue(token.matches("[A-Za-z0-9_-]+"));
		byte[] locale = cursor.locale().getBytes(StandardCharsets.US_ASCII);
		ByteBuffer canonical = ByteBuffer.allocate(68 + locale.length);
		canonical.put((byte) 1).put((byte) cursor.kind())
				.put(HexFormat.of().parseHex(cursor.fingerprint()))
				.put(Base64.getUrlDecoder().decode(cursor.key()))
				.putShort((short) locale.length).put(locale);
		Assertions.assertEquals(token,
				Base64.getUrlEncoder().withoutPadding().encodeToString(canonical.array()));
		Assertions.assertFalse(cursor.toString().contains(cursor.key()));
		Assertions.assertFalse(cursor.toString().contains(cursor.fingerprint()));
	}

	private static McpLegacyCatalogPager legacyPager(McpProtocolProfile profile) {
		List<McpJsonValue> descriptors = List.of("c", "b", "a").stream()
				.map(owner -> (McpJsonValue) new McpJsonObject(Map.of(
						"name", new McpJsonString(owner), "title", new McpJsonString("Canonical title"))))
				.toList();
		return new McpLegacyCatalogPager("/mcp-fuzz", profile,
				McpLegacyCatalogPager.Kind.PROMPTS,
				McpWireResult.complete(new McpJsonObject(Map.of("prompts", new McpJsonArray(descriptors)))), JSON);
	}

	private static void assertLegacyPaging(McpLegacyCatalogPager pager, byte[] input) throws Exception {
		String locale = unsigned(input, 0) % 2 == 0 ? "pt-BR" : "en-US";
		McpJsonObject empty = McpLegacyCatalogPager.envelope(REQUEST_ID,
				pager.document(List.of(), false, locale));
		long baseBytes = JSON.toUtf8Bytes(empty).length;
		long baseNodes = McpLegacyCatalogPager.nodes(empty);
		McpLegacyCatalogPager.Page first = pager.select(null, locale, baseBytes, baseNodes,
				4_096, 1_024, 1, ignored -> 1, ignored -> true);
		Assertions.assertEquals("a", first.entries().get(0).ownerId());
		Assertions.assertTrue(first.more());
		String token = ((McpJsonString) pager.document(first.entries(), true, locale)
				.members().get("nextCursor")).value();
		assertLegacyDecode(token);
		McpLegacyCatalogPager.Cursor cursor = McpLegacyCatalogPager.decode(token).orElseThrow();
		Assertions.assertTrue(pager.select(cursor, locale, baseBytes, baseNodes,
				4_096, 1_024, 1, ignored -> 1, owner -> !owner.equals("a")).invalidCursor(),
				"A previously emitted anchor must be authorized again on every page.");

		// Mutate a byte covered by cursor validation, scope, or the anchor lookup.
		byte[] mutated = Base64.getUrlDecoder().decode(token);
		int mutationIndex = unsigned(input, 1) % mutated.length;
		mutated[mutationIndex] ^= (byte) (1 + unsigned(input, 2) % 255);
		String mutatedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(mutated);
		assertLegacyDecode(mutatedToken);
		Optional<McpLegacyCatalogPager.Cursor> changed = McpLegacyCatalogPager.decode(mutatedToken);
		if (changed.isPresent())
			Assertions.assertTrue(pager.select(changed.orElseThrow(), locale, baseBytes, baseNodes,
						4_096, 1_024, 1, ignored -> 1, ignored -> true).invalidCursor());

		int hiddenMask = unsigned(input, 3) % 8;
		long maximumBytes = baseBytes + unsigned(input, 4) * 4L;
		long maximumNodes = baseNodes + unsigned(input, 5) % 12;
		int maximumSlots = unsigned(input, 6) % 4;
		McpLegacyCatalogPager.Cursor resume = unsigned(input, 7) % 2 == 0 ? null : cursor;
		McpLegacyCatalogPager.Page page = pager.select(resume, locale, baseBytes, baseNodes,
				maximumBytes, maximumNodes, maximumSlots, ignored -> 1,
				owner -> (hiddenMask & (1 << OWNERS.indexOf(owner))) == 0);
		boolean revokedAnchor = resume != null && (hiddenMask & 1) != 0;
		Assertions.assertEquals(revokedAnchor, page.invalidCursor());
		if (page.invalidCursor()) {
			Assertions.assertTrue(page.entries().isEmpty());
			return;
		}
		String previous = resume == null ? "" : "a";
		for (McpLegacyCatalogPager.Entry entry : page.entries()) {
			Assertions.assertTrue(entry.ownerId().compareTo(previous) > 0);
			Assertions.assertEquals(0, hiddenMask & (1 << OWNERS.indexOf(entry.ownerId())));
			previous = entry.ownerId();
		}
		Assertions.assertTrue(page.entries().size() <= maximumSlots);
		if (page.oversizedEntry()) {
			Assertions.assertTrue(page.entries().isEmpty());
			Assertions.assertTrue(page.more());
			return;
		}
		McpJsonObject publication = McpLegacyCatalogPager.envelope(REQUEST_ID,
				pager.document(page.entries(), page.more(), locale));
		Assertions.assertTrue(JSON.toUtf8Bytes(publication).length <= maximumBytes);
		Assertions.assertTrue(McpLegacyCatalogPager.nodes(publication) <= maximumNodes);
	}

	private static int unsigned(byte[] input, int index) {
		return index < input.length ? Byte.toUnsignedInt(input[index]) : 0;
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
