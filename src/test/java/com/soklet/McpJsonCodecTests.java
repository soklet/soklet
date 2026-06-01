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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ThreadSafe
public class McpJsonCodecTests {
	@Test
	public void parseStrictJsonObject() {
		McpValue value = McpJsonCodec.parse("""
				{
				  "jsonrpc":"2.0",
				  "id":"abc",
				  "params":{"enabled":true,"count":3,"items":["x",null]}
				}
				""");

		Assertions.assertInstanceOf(McpObject.class, value);
		McpObject object = (McpObject) value;
		Assertions.assertEquals("2.0", ((McpString) object.get("jsonrpc").orElseThrow()).value());
		Assertions.assertEquals("abc", ((McpString) object.get("id").orElseThrow()).value());
		McpObject params = (McpObject) object.get("params").orElseThrow();
		Assertions.assertEquals(Boolean.TRUE, ((McpBoolean) params.get("enabled").orElseThrow()).value());
		Assertions.assertEquals(new BigDecimal("3"), ((McpNumber) params.get("count").orElseThrow()).value());
		Assertions.assertEquals(McpNull.INSTANCE, ((McpArray) params.get("items").orElseThrow()).values().get(1));
	}

	@Test
	public void parseSupportsUtf8AndEscapedUnicode() {
		McpString value = (McpString) McpJsonCodec.parse("\"caf\\u00e9 \\uD83D\\uDE80\"".getBytes(StandardCharsets.UTF_8));
		Assertions.assertEquals("café 🚀", value.value());
	}

	@Test
	public void serializesUnpairedSurrogatesWithoutCorruption() {
		// A lone low surrogate is accepted on input; serializing it must not let UTF-8 encoding silently
		// replace it with '?'. It must be escaped and survive a parse -> serialize -> parse cycle.
		McpString parsed = (McpString) McpJsonCodec.parse("\"\\uDC00\"");
		byte[] serialized = McpJsonCodec.toUtf8Bytes(parsed);
		String serializedText = new String(serialized, StandardCharsets.UTF_8);

		Assertions.assertFalse(serializedText.contains("?"),
				"Unpaired surrogate was corrupted during serialization: " + serializedText);

		McpString reparsed = (McpString) McpJsonCodec.parse(serialized);
		Assertions.assertEquals(parsed.value(), reparsed.value());
	}

	@Test
	public void serializesValidSurrogatePairsVerbatim() {
		// A well-formed surrogate pair (an emoji) must round-trip and be emitted as UTF-8, not escaped away.
		McpString parsed = (McpString) McpJsonCodec.parse("\"\\uD83D\\uDE00\"");
		Assertions.assertEquals("\"😀\"", McpJsonCodec.toJson(parsed));
		McpString reparsed = (McpString) McpJsonCodec.parse(McpJsonCodec.toUtf8Bytes(parsed));
		Assertions.assertEquals(parsed.value(), reparsed.value());
		Assertions.assertEquals("😀", reparsed.value());
	}

	@Test
	public void serializesExponentNumbersCompactlyEnoughToReparse() {
		McpNumber parsed = (McpNumber) McpJsonCodec.parse("1e600");
		String json = McpJsonCodec.toJson(parsed);

		Assertions.assertEquals("1E+600", json);
		Assertions.assertTrue(json.length() <= 512);
		Assertions.assertEquals(0, parsed.value().compareTo(((McpNumber) McpJsonCodec.parse(json)).value()));
	}

	@Test
	public void writerEscapesAndRoundTripsValues() {
		Map<String, McpValue> values = new LinkedHashMap<>();
		values.put("message", new McpString("Hello\n\"MCP\""));
		values.put("number", new McpNumber(new BigDecimal("12.50")));
		values.put("items", new McpArray(List.of(new McpBoolean(Boolean.TRUE), McpNull.INSTANCE)));
		McpObject value = new McpObject(values);

		String json = McpJsonCodec.toJson(value);
		Assertions.assertEquals("{\"message\":\"Hello\\n\\\"MCP\\\"\",\"number\":12.50,\"items\":[true,null]}", json);
		Assertions.assertEquals(value, McpJsonCodec.parse(json));
	}

	@Test
	public void parseRejectsTrailingCommas() {
		IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse("{\"a\":1,}"));
		Assertions.assertTrue(exception.getMessage().contains("Expected object property name"));
	}

	@Test
	public void parseRejectsLeadingZeroNumbers() {
		IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse("01"));
		Assertions.assertTrue(exception.getMessage().contains("Leading zeroes"));
	}

	@Test
	public void parseRejectsTrailingGarbage() {
		IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse("true false"));
		Assertions.assertTrue(exception.getMessage().contains("Unexpected trailing content"));
	}

	@Test
	public void parseRejectsIncompleteObjectPropertyName() {
		IllegalArgumentException topLevelException = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse("{"));
		Assertions.assertTrue(topLevelException.getMessage().contains("Expected object property name"));

		IllegalArgumentException nestedException = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse("[[{"));
		Assertions.assertTrue(nestedException.getMessage().contains("Expected object property name"));
	}

	@Test
	public void parseRejectsExcessiveNestingDepth() {
		StringBuilder json = new StringBuilder();

		for (int i = 0; i < 257; i++)
			json.append('[');

		json.append('0');

		for (int i = 0; i < 257; i++)
			json.append(']');

		IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse(json.toString()));
		Assertions.assertTrue(exception.getMessage().contains("nesting depth"));
	}

	@Test
	public void parseRejectsExcessiveNumberLength() {
		String json = "1" + "0".repeat(512);

		IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse(json));
		Assertions.assertTrue(exception.getMessage().contains("number length"));
	}

	@Test
	public void parseAcceptsMaximumExponentMagnitude() {
		Assertions.assertDoesNotThrow(() -> McpJsonCodec.parse("1e10000"));
		Assertions.assertDoesNotThrow(() -> McpJsonCodec.parse("1e-10000"));
	}

	@Test
	public void parseRejectsExcessiveExponentMagnitude() {
		IllegalArgumentException positiveException = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse("1e10001"));
		Assertions.assertTrue(positiveException.getMessage().contains("exponent magnitude"));

		IllegalArgumentException negativeException = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse("1e-10001"));
		Assertions.assertTrue(negativeException.getMessage().contains("exponent magnitude"));
	}

	@Test
	public void parseRejectsNumbersWhoseCanonicalFormExceedsLengthCap() {
		// "9...9e9" (511-char token) satisfies the input-token cap, but its canonical
		// BigDecimal.toString() form is longer than 512 characters. Soklet must reject it rather than
		// emit a number it could not itself re-parse.
		String json = "9".repeat(509) + "e9";
		Assertions.assertTrue(json.length() <= 512);

		IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse(json));
		Assertions.assertTrue(exception.getMessage().contains("number length"));
	}

	@Test
	public void parseRejectsNumbersWhoseCanonicalExponentExceedsCap() {
		// "12e10000" sits at the exponent cap on input, but normalizes to "1.2E+10001" - one past the
		// cap - so Soklet must reject it for the same round-trip self-consistency reason.
		IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpJsonCodec.parse("12e10000"));
		Assertions.assertTrue(exception.getMessage().contains("exponent magnitude"));
	}
}
