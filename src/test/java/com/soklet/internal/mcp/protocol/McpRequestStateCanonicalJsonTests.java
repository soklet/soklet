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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpRequestStateCanonicalJsonTests {
	@Test
	public void recursivelySortsByUnsignedUtf8AndPreservesArrayOrder() {
		Map<String, McpJsonValue> nested = new LinkedHashMap<>();
		nested.put("\uD800\uDC00", new McpJsonNumber(3L));
		nested.put("z", new McpJsonNumber(1L));
		nested.put("\uE000", new McpJsonNumber(2L));
		Map<String, McpJsonValue> root = new LinkedHashMap<>();
		root.put("nested", new McpJsonObject(nested));
		root.put("array", new McpJsonArray(List.of(
				new McpJsonString("second"), new McpJsonString("first"))));

		Assertions.assertEquals(
				"{\"array\":[\"second\",\"first\"],\"nested\":{\"z\":1,"
						+ "\"\uE000\":2,\"\uD800\uDC00\":3}}",
				canonical(new McpJsonObject(root)));
	}

	@Test
	public void normalizesNumbersAndUsesDeterministicStringEscaping() {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("zero", new McpJsonNumber(new BigDecimal("-0.000")));
		fields.put("thousand", new McpJsonNumber(new BigDecimal("1000.00")));
		fields.put("small", new McpJsonNumber(new BigDecimal("0.00000010")));
		fields.put("plain", new McpJsonNumber(new BigDecimal("12.3400")));
		fields.put("text", new McpJsonString(
				"\"\\\b\f\n\r\t\u0001 café 🚀"));

		Assertions.assertEquals(
				"{\"plain\":12.34,\"small\":1E-7,"
						+ "\"text\":\"\\\"\\\\\\b\\f\\n\\r\\t\\u0001 café 🚀\","
						+ "\"thousand\":1E+3,\"zero\":0}",
				canonical(new McpJsonObject(fields)));
	}

	@Test
	public void exactCanonicalReserializationRejectsEquivalentSpellings() {
		for (String noncanonical : List.of(
				" {\"a\":1}",
				"{\"b\":2,\"a\":1}",
				"{\"a\":1.0}",
				"{\"a\":1000}",
				"{\"a\":\"\\u0061\"}"))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpRequestStateCanonicalJson.parseCanonical(
							noncanonical.getBytes(StandardCharsets.UTF_8), 1_024),
					noncanonical);

		byte[] canonical = "{\"a\":1E+3,\"b\":\"a\"}"
				.getBytes(StandardCharsets.UTF_8);
		Assertions.assertEquals(
				new McpJsonObject(Map.of(
						"a", new McpJsonNumber(new BigDecimal("1E+3")),
						"b", new McpJsonString("a"))),
				McpRequestStateCanonicalJson.parseCanonical(canonical, 1_024));
	}

	@Test
	public void preservesCanonicallyDistinctUnicodeAndRejectsMalformedUtf8() {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("é", new McpJsonString("é"));
		fields.put("e\u0301", new McpJsonString("e\u0301"));
		Assertions.assertEquals("{\"e\u0301\":\"e\u0301\",\"é\":\"é\"}",
				canonical(new McpJsonObject(fields)));

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateCanonicalJson.parseCanonical(
						new byte[]{'"', (byte) 0xC0, (byte) 0xAF, '"'}, 32));
	}

	@Test
	public void rejectsInvalidUnicodeAndBoundsViolations() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateCanonicalJson.canonicalize(
						new McpJsonString("bad\uD800"), 1_024));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateCanonicalJson.canonicalize(
						new McpJsonObject(Map.of(
								"bad\uDC00", McpJsonNull.INSTANCE)), 1_024));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateCanonicalJson.canonicalize(
						new McpJsonString("oversize"), 5));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateCanonicalJson.parseCanonical(
						"null".getBytes(StandardCharsets.UTF_8), 3));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateCanonicalJson.canonicalize(
						McpJsonNull.INSTANCE, 0));
	}

	@Test
	public void rejectsConstructedTreesBeyondTheStrictDepthBeforeWriting() {
		McpJsonValue value = McpJsonNull.INSTANCE;
		for (int depth = 0; depth < 128; ++depth)
			value = new McpJsonArray(List.of(value));
		McpJsonValue tooDeep = value;

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRequestStateCanonicalJson.canonicalize(
						tooDeep, 4_096));
	}

	private static String canonical(McpJsonValue value) {
		return new String(McpRequestStateCanonicalJson.canonicalize(value, 4_096),
				StandardCharsets.UTF_8);
	}
}
