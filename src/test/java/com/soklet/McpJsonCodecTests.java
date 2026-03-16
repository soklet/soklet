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
}
