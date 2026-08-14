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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Version-2 request-state plaintext behavior: the exact {@code selectedLocale}
 * field, its 20 + N byte overhead, strict dual-version decoding, canonical-tag
 * tamper rejection, boundary fit, and carry-forward.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpRequestStateSelectedLocaleCodecTests {
	private static final int MAXIMUM_BYTES = 4_096;
	private static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(15);
	private static final int MAXIMUM_ROUNDS = 3;
	private static final Instant NOW = Instant.ofEpochSecond(1_700_000_100L);
	private static final McpJsonRpcId NEXT_ID =
			new McpJsonRpcId.StringId("request-next");

	@Test
	public void versionTwoMatchesTheDeterministicCanonicalVector() {
		byte[] plaintext = encode(continuation("fr-CA"));

		Assertions.assertEquals(
				"{\"bindingDigest\":\"zlJZXwvYkSZXNQDFOCrFiEzSF-zGERmYQWYl01Eimko\","
						+ "\"expiresAtEpochSecond\":1700000901,"
						+ "\"expiresAtNanoAdjustment\":123456789,"
						+ "\"issuedAtEpochSecond\":1700000001,"
						+ "\"issuedAtNanoAdjustment\":123456789,"
						+ "\"originatingRequestId\":\"request-1\",\"round\":1,"
						+ "\"selectedLocale\":\"fr-CA\","
						+ "\"state\":{\"phase\":\"waiting\"},\"version\":2}",
				new String(plaintext, StandardCharsets.UTF_8));

		McpFrameworkRequestStateContinuation decoded = decode(plaintext);
		Assertions.assertEquals("fr-CA", decoded.selectedLocale());
		Assertions.assertArrayEquals(plaintext, encode(decoded),
				"Version-2 decode must round-trip byte-exactly.");
	}

	@Test
	public void versionTwoCostsExactlyTwentyPlusTagBytesOverVersionOne() {
		byte[] versionOne = encode(continuation(null));

		for (String tag : new String[]{"en", "fr-CA", maximumLengthTag()}) {
			byte[] versionTwo = encode(continuation(tag));
			Assertions.assertEquals(20 + tag.length(),
					versionTwo.length - versionOne.length,
					"Tag '" + tag.substring(0, Math.min(12, tag.length()))
							+ "...' must cost exactly 20 + N bytes.");
		}
	}

	@Test
	public void decodedLimitBoundariesAreExactForMinimumAndMaximumTags() {
		for (String tag : new String[]{"en", maximumLengthTag()}) {
			byte[] versionTwo = McpRequestStatePlaintextCodec.encode(
					continuation(tag), binding(), MAXIMUM_BYTES,
					MAXIMUM_LIFETIME, MAXIMUM_ROUNDS);

			// Exactly at the limit: encodes.
			Assertions.assertEquals(versionTwo.length,
					McpRequestStatePlaintextCodec.encode(continuation(tag),
							binding(), versionTwo.length, MAXIMUM_LIFETIME,
							MAXIMUM_ROUNDS).length);

			// One byte under: sealing fails rather than dropping the locale.
			Assertions.assertThrows(IllegalArgumentException.class, () ->
					McpRequestStatePlaintextCodec.encode(continuation(tag),
							binding(), versionTwo.length - 1, MAXIMUM_LIFETIME,
							MAXIMUM_ROUNDS));
		}
	}

	@Test
	public void theMaximumTagIsExactlyTwoHundredFiftyFiveCanonicalBytes() {
		String tag = maximumLengthTag();
		Assertions.assertEquals(255, tag.length());
		Assertions.assertEquals(tag, Locale.forLanguageTag(tag).toLanguageTag(),
				"The maximum-length fixture must itself be canonical.");
		Assertions.assertEquals(tag, decode(encode(continuation(tag)))
				.selectedLocale());
	}

	@Test
	public void versionAndFieldSetMustAgreeExactly() {
		// A version-1 object smuggling selectedLocale is tampering.
		assertRejected(canonicalJson(1, "\"selectedLocale\":\"fr-CA\","));
		// A version-2 object missing selectedLocale is tampering.
		assertRejected(canonicalJson(2, ""));
		// Version values other than 1 and 2 are unknown.
		assertRejected(canonicalJson(3, "\"selectedLocale\":\"fr-CA\","));
	}

	@Test
	public void nonCanonicalRootOversizedAndNonAsciiTagsAreRejected() {
		for (String tag : new String[]{"FR-ca", "fr_CA", "und", "und-CA", "",
				"fr-CA-", "café"}) {
			String escaped = tag.replace("\\", "\\\\").replace("\"", "\\\"");
			assertRejected(canonicalJson(2,
					"\"selectedLocale\":\"" + escaped + "\","));
		}

		// One byte over the 255-byte bound must be rejected even when the tag
		// would otherwise be canonical.
		String oversized = maximumLengthTag() + "x";
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> encode(continuation(oversized)));
	}

	@Test
	public void carryForwardPreservesTheOriginalTagExactly() {
		McpFrameworkRequestStateContinuation first = continuation("fr-CA");
		McpFrameworkRequestStateContinuation second = first.next(
				new McpJsonObject(Map.of("phase",
						new McpJsonString("round-two"))),
				new McpJsonRpcId.StringId("request-2"), MAXIMUM_ROUNDS);

		Assertions.assertEquals("fr-CA", second.selectedLocale());
		Assertions.assertEquals(2, second.round());

		McpFrameworkRequestStateContinuation versionOne = continuation(null);
		Assertions.assertNull(versionOne.next(
				new McpJsonObject(Map.of("phase",
						new McpJsonString("round-two"))),
				new McpJsonRpcId.StringId("request-2"), MAXIMUM_ROUNDS)
				.selectedLocale(),
				"A version-1 flow never upgrades mid-continuation.");
	}

	private static void assertRejected(String canonicalJson) {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> decode(canonicalJson.getBytes(StandardCharsets.UTF_8)),
				canonicalJson);
	}

	/**
	 * Builds canonical (byte-sorted, whitespace-free) plaintext with the given
	 * version and an optional pre-sorted {@code selectedLocale} member, which
	 * sorts between {@code round} and {@code state}.
	 */
	private static String canonicalJson(int version, String selectedLocaleMember) {
		return "{\"bindingDigest\":\"zlJZXwvYkSZXNQDFOCrFiEzSF-zGERmYQWYl01Eimko\","
				+ "\"expiresAtEpochSecond\":1700000901,"
				+ "\"expiresAtNanoAdjustment\":123456789,"
				+ "\"issuedAtEpochSecond\":1700000001,"
				+ "\"issuedAtNanoAdjustment\":123456789,"
				+ "\"originatingRequestId\":\"request-1\",\"round\":1,"
				+ selectedLocaleMember
				+ "\"state\":{\"phase\":\"waiting\"},\"version\":" + version + "}";
	}

	/** 255 ASCII bytes: {@code en-x} plus 27 eight-char and one seven-char subtag. */
	private static String maximumLengthTag() {
		StringBuilder tag = new StringBuilder("en-x");

		for (int index = 0; index < 27; ++index)
			tag.append(String.format("-sub%05d", index));

		tag.append("-last678");
		String value = tag.toString();
		return value;
	}

	private static McpFrameworkRequestStateContinuation continuation(
			String selectedLocale) {
		return new McpFrameworkRequestStateContinuation(
				new McpRequestStateTimestamp(1_700_000_001L, 123_456_789),
				new McpRequestStateTimestamp(1_700_000_901L, 123_456_789), 1,
				new McpJsonRpcId.StringId("request-1"),
				new McpJsonObject(Map.of("phase", new McpJsonString("waiting"))),
				selectedLocale);
	}

	private static byte[] encode(
			McpFrameworkRequestStateContinuation continuation) {
		return McpRequestStatePlaintextCodec.encode(continuation, binding(),
				MAXIMUM_BYTES, MAXIMUM_LIFETIME, MAXIMUM_ROUNDS);
	}

	private static McpFrameworkRequestStateContinuation decode(byte[] plaintext) {
		return McpRequestStatePlaintextCodec.decode(plaintext, binding(),
				MAXIMUM_BYTES, MAXIMUM_LIFETIME, MAXIMUM_ROUNDS, NOW, NEXT_ID);
	}

	private static McpRequestStateBinding binding() {
		return McpRequestStateBinding.create("/mcp", "2026-07-28", "tools/call",
				Optional.of("tenant-α"), new McpJsonObject(Map.of(
						"name", new McpJsonString("stateful.tool"))));
	}
}
