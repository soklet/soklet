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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class McpJsonCodecTests {
	private static final Path CORPUS_ROOT = Path.of(
			"fuzz", "src", "test", "resources", "com", "soklet", "json-corpus");
	private static final McpJsonLimits LIMITS =
			new McpJsonLimits(4_096, 16, 512, 512, 512, 10_000, 512, 4_096);
	private static final McpJsonCodec CODEC = new McpJsonCodec(LIMITS);

	@Test
	public void strictJsonParserAcceptsObjectRoot() {
		McpJsonValue value = CODEC.parse(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{}}".getBytes(StandardCharsets.UTF_8));

		Assertions.assertInstanceOf(McpJsonObject.class, value);
		Assertions.assertEquals(new McpJsonString("2.0"),
				((McpJsonObject) value).members().get("jsonrpc"));
	}

	@Test
	public void strictJsonParserDecodesUtf8AndEscapedUnicode() {
		McpJsonValue value = CODEC.parse("[\"café\",\"\\uD83D\\uDE80\"]"
				.getBytes(StandardCharsets.UTF_8));

		Assertions.assertEquals(new McpJsonArray(List.of(
				new McpJsonString("café"), new McpJsonString("🚀"))), value);
	}

	@Test
	public void strictJsonWriterRejectsUnpairedSurrogateBeforeOutput() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.toUtf8Bytes(new McpJsonString("bad\uD800")));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.toUtf8Bytes(new McpJsonObject(
						Map.of("bad\uDC00", McpJsonNull.INSTANCE))));
	}

	@Test
	public void strictJsonWriterPreservesValidSurrogatePairs() {
		McpJsonString value = new McpJsonString("launch 🚀");
		byte[] serialized = CODEC.toUtf8Bytes(value);

		Assertions.assertEquals("\"launch 🚀\"",
				new String(serialized, StandardCharsets.UTF_8));
		Assertions.assertEquals(value, CODEC.parse(serialized));
	}

	@Test
	public void strictJsonWriterEmitsJsonLineSeparatorsWithoutTokenExpansion() {
		McpJsonString value = new McpJsonString("a\u2028b\u2029c");

		Assertions.assertEquals("\"a\u2028b\u2029c\"", CODEC.toJson(value));
		Assertions.assertEquals(value, CODEC.parse(CODEC.toUtf8Bytes(value)));
	}

	@Test
	public void strictJsonWriterEmitsExponentNumbersThatReparse() {
		for (String number : List.of("1e1", "1e600", "1e-600", "12.50E+20")) {
			McpJsonValue parsed = CODEC.parse(number);
			String serialized = CODEC.toJson(parsed);
			Assertions.assertEquals(parsed, CODEC.parse(serialized), number);
		}
	}

	@Test
	public void strictJsonWriterEscapesAndRoundTripsValues() {
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("quote\"slash\\", new McpJsonString("\b\f\n\r\t\u0001"));
		members.put("number", new McpJsonNumber(new BigDecimal("123.4500")));
		members.put("boolean", McpJsonBoolean.TRUE);
		members.put("null", McpJsonNull.INSTANCE);
		members.put("array", new McpJsonArray(List.of(new McpJsonString("é"))));
		McpJsonObject value = new McpJsonObject(members);

		Assertions.assertEquals(value, CODEC.parse(CODEC.toUtf8Bytes(value)));
		Assertions.assertEquals(
				"{\"quote\\\"slash\\\\\":\"\\b\\f\\n\\r\\t\\u0001\","
						+ "\"number\":123.4500,\"boolean\":true,\"null\":null,"
						+ "\"array\":[\"é\"]}", CODEC.toJson(value));
	}

	@Test
	public void strictJsonParserRejectsTrailingComma() {
		assertMalformed("[1,]");
		assertMalformed("{\"a\":1,}");
	}

	@Test
	public void strictJsonParserRejectsLeadingZeroNumber() {
		for (String invalid : List.of("01", "-01", "00.1"))
			assertMalformed(invalid);
	}

	@Test
	public void strictJsonParserRejectsTrailingGarbage() {
		assertMalformed("{} trailing");
		assertMalformed("true false");
	}

	@Test
	public void strictJsonParserRejectsLeadingBom() {
		assertMalformed("\uFEFF{}");
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.parse(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}));
	}

	@Test
	public void strictJsonParserRejectsDuplicateObjectKey() {
		assertMalformed("{\"a\":1,\"a\":2}");
		assertMalformed("{\"a\":1,\"\\u0061\":2}");
	}

	@Test
	public void strictJsonParserRejectsLoneLowSurrogate() {
		assertMalformed("\"\\uDC00\"");
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.parse("\"\uDC00\""));
	}

	@Test
	public void strictJsonParserRejectsIncompleteObjectPropertyName() {
		for (String invalid : List.of("{", "{\"", "{\"name", "{\"name\"", "{\"name\":"))
			assertMalformed(invalid);
	}

	@Test
	public void strictJsonParserRejectsNestingBeyondLimit() {
		McpJsonCodec depthThreeCodec = new McpJsonCodec(limitsWithDepth(3));
		Assertions.assertDoesNotThrow(() -> depthThreeCodec.parse("[[0]]"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> depthThreeCodec.parse("[[[0]]]"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> depthThreeCodec.toJson(new McpJsonArray(List.of(
						new McpJsonArray(List.of(new McpJsonArray(List.of(
								new McpJsonNumber(0L)))))))));
	}

	@Test
	public void strictJsonParserRejectsNumberBeyondLengthLimit() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.parse("1".repeat(513)));
		Assertions.assertDoesNotThrow(() -> CODEC.parse("1".repeat(512)));
	}

	@Test
	public void strictJsonParserAcceptsMaximumExponentMagnitude() {
		Assertions.assertEquals(new McpJsonNumber(new BigDecimal("1e10000")),
				CODEC.parse("1e10000"));
		Assertions.assertEquals(new McpJsonNumber(new BigDecimal("1e-10000")),
				CODEC.parse("1e-10000"));
	}

	@Test
	public void strictJsonParserRejectsExponentBeyondMagnitudeLimit() {
		for (String invalid : List.of("1e10001", "1e-10001", "1e999999999999999999"))
			assertMalformed(invalid);
	}

	@Test
	public void strictJsonParserRejectsCanonicalNumberBeyondLengthLimit() throws IOException {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.parse(corpus("parse/canonical-length-overflow.json")));
	}

	@Test
	public void strictJsonParserRejectsCanonicalExponentBeyondMagnitudeLimit() {
		assertMalformed("12e10000");
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.toJson(new McpJsonNumber(new BigDecimal("12e10000"))));
	}

	@Test
	public void strictJsonParserRejectsMalformedUtf8ByteSequences() throws IOException {
		for (String fixture : List.of(
				"parse/invalid-utf8-large-truncated-envelope.bin",
				"parse/invalid-utf8-nested-object.bin",
				"parse/invalid-utf8-property-name-truncated-id.bin"))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> CODEC.parse(corpus(fixture)), fixture);

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.parse(new byte[]{'"', (byte) 0xC0, (byte) 0xAF, '"'}));

		for (byte[] invalid : List.of(
				new byte[]{(byte) 0xC2},
				new byte[]{(byte) 0xE2, (byte) 0x82},
				new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x92},
				new byte[]{(byte) 0x80},
				new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80},
				new byte[]{(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> CODEC.parse(invalid));
	}

	@Test
	public void strictJsonParserAndWriterAcceptDepth256AndRejectDepth257() {
		McpJsonCodec maximumDepthCodec = new McpJsonCodec(limitsWithDepth(256));
		McpJsonValue depth256Value = new McpJsonNumber(0L);
		String depth256Json = "0";

		for (int depth = 1; depth < 256; ++depth) {
			depth256Value = new McpJsonArray(List.of(depth256Value));
			depth256Json = '[' + depth256Json + ']';
		}

		Assertions.assertEquals(depth256Value, maximumDepthCodec.parse(depth256Json));
		Assertions.assertEquals(depth256Json, maximumDepthCodec.toJson(depth256Value));

		McpJsonValue depth257Value = new McpJsonArray(List.of(depth256Value));
		String depth257Json = '[' + depth256Json + ']';
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> maximumDepthCodec.parse(depth257Json));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> maximumDepthCodec.toJson(depth257Value));
	}

	@Test
	public void strictJsonParserEnforcesRawTokenAndDecodedStringBounds() {
		McpJsonCodec exactCodec = new McpJsonCodec(
				new McpJsonLimits(64, 4, 6, 4, 16, 16, 16, 64));
		Assertions.assertEquals(new McpJsonString("a"), exactCodec.parse("\"\\u0061\""));
		Assertions.assertEquals(new McpJsonString("abcd"), exactCodec.parse("\"abcd\""));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonCodec(new McpJsonLimits(
						64, 4, 5, 4, 16, 16, 16, 64)).parse("\"\\u0061\""));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonCodec(new McpJsonLimits(
						64, 4, 8, 3, 16, 16, 16, 64)).parse("\"abcd\""));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonCodec(new McpJsonLimits(
						64, 4, 1, 1, 16, 16, 16, 64))
						.toJson(new McpJsonString("\"")));
	}

	@Test
	public void strictJsonParserAndWriterEnforceExactNodeCounts() {
		McpJsonArray threeNodes = new McpJsonArray(List.of(
				McpJsonNull.INSTANCE, McpJsonBoolean.TRUE));
		McpJsonCodec threeNodeCodec = new McpJsonCodec(limitsWithNodes(3));
		McpJsonCodec twoNodeCodec = new McpJsonCodec(limitsWithNodes(2));

		Assertions.assertEquals(threeNodes, threeNodeCodec.parse("[null,true]"));
		Assertions.assertEquals("[null,true]", threeNodeCodec.toJson(threeNodes));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> twoNodeCodec.parse("[null,true]"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> twoNodeCodec.toJson(threeNodes));
	}

	@Test
	public void strictJsonWriterEnforcesExactUtf8OutputBytes() {
		McpJsonString value = new McpJsonString("é");
		McpJsonCodec fourByteCodec = new McpJsonCodec(limitsWithOutputBytes(4));
		McpJsonCodec threeByteCodec = new McpJsonCodec(limitsWithOutputBytes(3));

		Assertions.assertArrayEquals("\"é\"".getBytes(StandardCharsets.UTF_8),
				fourByteCodec.toUtf8Bytes(value));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> threeByteCodec.toUtf8Bytes(value));
	}

	@Test
	public void strictJsonParserRejectsEveryUnpairedSurrogateForm() {
		for (String invalid : List.of(
				"\"\\uD800\"", "\"\\uD800x\"", "\"\\uD800\\u0041\"", "\"\\uDC00\""))
			assertMalformed(invalid);

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.parse("\"\uD800\""));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CODEC.toJson(new McpJsonString("\uDC00")));
	}

	@Test
	public void strictJsonParserImplementsTheCompleteJsonNumberGrammar() {
		for (String valid : List.of(
				"0", "-0", "1", "-1", "0.0", "-0.1", "1e0", "1E+2", "1e-2", "1.23e4"))
			Assertions.assertInstanceOf(McpJsonNumber.class, CODEC.parse(valid), valid);

		for (String invalid : List.of(
				"-", "+1", "01", "-01", ".1", "1.", "1e", "1e+", "--1",
				"NaN", "Infinity", "00", "1..0"))
			assertMalformed(invalid);
	}

	@Test
	public void strictJsonCodecReplaysTheRetainedCorpus() throws IOException {
		for (String fixture : List.of(
				"parse/array.json", "parse/object.json", "parse/string-escapes.json",
				"parse/surrogate-pair.json", "parse/deep-array.json", "parse/exponent-limit.json"))
			Assertions.assertDoesNotThrow(() -> CODEC.parse(corpus(fixture)), fixture);

		for (String fixture : List.of(
				"parse/canonical-exponent-overflow.json",
				"parse/canonical-length-overflow.json",
				"parse/duplicate-keys.json", "parse/incomplete-object.json",
				"parse/invalid-number.json", "parse/leading-bom.json",
				"parse/lone-low-surrogate.json",
				"parse/truncated-array-object-with-whitespace.json",
				"parse/truncated-deep-array-object.json",
				"parse/truncated-nested-array-object.json",
				"parse/truncated-object-minimal.json"))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> CODEC.parse(corpus(fixture)), fixture);

		for (String fixture : List.of(
				"round-trip/exponent-scale.json", "round-trip/large-exponent.json",
				"round-trip/line-separators.json", "round-trip/nested.json",
				"round-trip/surrogate-pair.json")) {
			McpJsonValue value = CODEC.parse(corpus(fixture));
			Assertions.assertEquals(value, CODEC.parse(CODEC.toUtf8Bytes(value)), fixture);
		}
	}

	@Test
	public void retainedJsonCorpusMatchesItsSha256Manifest()
			throws IOException, NoSuchAlgorithmException {
		List<String> manifestLines = Files.readAllLines(
				CORPUS_ROOT.resolve("manifest.sha256"), StandardCharsets.US_ASCII);
		Set<String> manifestPaths = new LinkedHashSet<>();
		MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

		for (String line : manifestLines) {
			int separator = line.indexOf("  ");
			Assertions.assertEquals(64, separator, line);
			String expectedDigest = line.substring(0, separator);
			String relativePath = line.substring(separator + 2);
			Assertions.assertTrue(manifestPaths.add(relativePath), relativePath);
			String actualDigest = HexFormat.of().formatHex(
					sha256.digest(corpus(relativePath)));
			Assertions.assertEquals(expectedDigest, actualDigest, relativePath);
		}

		Set<String> actualPaths;

		try (var paths = Files.walk(CORPUS_ROOT)) {
			actualPaths = paths
					.filter(Files::isRegularFile)
					.map(CORPUS_ROOT::relativize)
					.map(Path::toString)
					.filter(path -> !path.equals("manifest.sha256"))
					.collect(Collectors.toCollection(LinkedHashSet::new));
		}

		Assertions.assertEquals(manifestPaths, actualPaths);
		Assertions.assertEquals(25, manifestPaths.size());
	}

	@Test
	public void strictJsonCodecPreservesEncounterOrderAndRejectsNonJsonWhitespace() {
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("z", McpJsonNull.INSTANCE);
		members.put("a", McpJsonBoolean.TRUE);

		Assertions.assertEquals("{\"z\":null,\"a\":true}",
				CODEC.toJson(new McpJsonObject(members)));
		assertMalformed("\u00A0null");
	}

	@Test
	public void jsonLimitsRejectUnsafeOrNonPositiveConfigurations() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonLimits(0, 1, 1, 1, 1, 1, 1, 1));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonLimits(1, 257, 1, 1, 1, 1, 1, 1));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpJsonLimits(1, 1, 1, 1, 1, -1, 1, 1));
	}

	private static void assertMalformed(String json) {
		Assertions.assertThrows(IllegalArgumentException.class, () -> CODEC.parse(json), json);
	}

	private static byte[] corpus(String relativePath) throws IOException {
		return Files.readAllBytes(CORPUS_ROOT.resolve(relativePath));
	}

	private static McpJsonLimits limitsWithDepth(int maximumDepth) {
		return new McpJsonLimits(LIMITS.maximumInputBytes(), maximumDepth,
				LIMITS.maximumTokenLengthInCharacters(), LIMITS.maximumStringLengthInCharacters(),
				LIMITS.maximumNumberLengthInCharacters(), LIMITS.maximumExponentMagnitude(),
				LIMITS.maximumNodeCount(), LIMITS.maximumOutputBytes());
	}

	private static McpJsonLimits limitsWithNodes(int maximumNodes) {
		return new McpJsonLimits(LIMITS.maximumInputBytes(), LIMITS.maximumNestingDepth(),
				LIMITS.maximumTokenLengthInCharacters(), LIMITS.maximumStringLengthInCharacters(),
				LIMITS.maximumNumberLengthInCharacters(), LIMITS.maximumExponentMagnitude(),
				maximumNodes, LIMITS.maximumOutputBytes());
	}

	private static McpJsonLimits limitsWithOutputBytes(int maximumOutputBytes) {
		return new McpJsonLimits(LIMITS.maximumInputBytes(), LIMITS.maximumNestingDepth(),
				LIMITS.maximumTokenLengthInCharacters(), LIMITS.maximumStringLengthInCharacters(),
				LIMITS.maximumNumberLengthInCharacters(), LIMITS.maximumExponentMagnitude(),
				LIMITS.maximumNodeCount(), maximumOutputBytes);
	}
}
