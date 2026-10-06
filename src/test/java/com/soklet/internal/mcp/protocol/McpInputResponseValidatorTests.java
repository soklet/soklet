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
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Coverage for the MCP elicitation input-response union and numeric limits.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpInputResponseValidatorTests {
	private static final McpJsonCodec JSON_CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());

	@Test
	public void acceptsDecimalAndExponentNumbersWithoutChangingTheirValues() {
		for (String token : List.of("3.50", "-0.125", "0.0", "1.5E+2", "1E-10000", "1E+10000")) {
			McpJsonObject response = (McpJsonObject) JSON_CODEC.parse(
					"{\"action\":\"accept\",\"content\":{\"amount\":" + token + "}}");
			McpJsonNumber number = (McpJsonNumber) ((McpJsonObject) response.members().get("content")).members().get("amount");
			Assertions.assertDoesNotThrow(() -> McpInputResponseValidator.validate(response), token);
			Assertions.assertTrue(McpInputResponseValidator.matches(
					McpInputRequestDeclaration.elicitationForm(McpInputRequirement.CONDITIONAL), response), token);
			Assertions.assertEquals(new BigDecimal(token), number.value(), token);
		}
	}

	@Test
	public void programmaticNumbersRemainWithinTheCodecLimits() {
		for (BigDecimal value : List.of(
				new BigDecimal("1E+10001"), new BigDecimal("1E-10001"),
				new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE),
				new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE),
				new BigDecimal("1" + "2".repeat(1024)), new BigDecimal("0." + "1".repeat(1024)))) {
			McpJsonObject response = new McpJsonObject(Map.of("action", new McpJsonString("accept"),
					"content", new McpJsonObject(Map.of("amount", new McpJsonNumber(value)))));
			Assertions.assertThrows(IllegalArgumentException.class, () -> JSON_CODEC.toJson(response));
			IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpInputResponseValidator.validate(response));
			Assertions.assertEquals("MCP input response is invalid.", failure.getMessage());
			Assertions.assertFalse(McpInputResponseValidator.matches(
					McpInputRequestDeclaration.elicitationForm(McpInputRequirement.CONDITIONAL), response));
		}
	}

	@Test
	public void acceptsOpenElicitationResultsWithoutMutatingThem() {
		for (String json : List.of(
				"{\"action\":\"decline\"}",
				"{\"action\":\"cancel\",\"future\":null}",
				"""
				{"action":"accept","content":{
				  "string":"value","integer":7,"decimal":1.5,"boolean":true,
				  "strings":["first","second"]
				 },"com.example/extension":{"nested":[null]}}
				""")) {
			McpJsonValue response = JSON_CODEC.parse(json);
			String before = JSON_CODEC.toJson(response);

			Assertions.assertDoesNotThrow(
					() -> McpInputResponseValidator.validate(response));
			Assertions.assertEquals(before, JSON_CODEC.toJson(response));
		}
	}
	@Test
	public void rejectsValuesThatMatchNoInputResponseBranch() {
		for (String json : List.of(
				"null", "true", "7", "\"response\"", "[]", "{}",
				"{\"action\":\"unknown\"}",
				"{\"action\":\"accept\",\"content\":[]}",
				"{\"action\":\"accept\",\"content\":{\"value\":null}}",
				"{\"action\":\"accept\",\"content\":{\"value\":[\"ok\",1]}}",
				"{\"action\":\"accept\",\"content\":{\"value\":{}}}",
				"{\"roots\":null}",
				"{\"roots\":[1]}",
				"{\"roots\":[{}]}",
				"{\"roots\":[{\"uri\":\"relative/path\"}]}",
				"{\"roots\":[{\"uri\":\"https://example.com/project\"}]}",
				"{\"roots\":[{\"uri\":\"file:///tmp/project\",\"_meta\":[]}]}",
				"{\"roots\":[{\"uri\":\"file:///tmp/project\",\"_meta\":{\" bad\":true}}]}",
				"{\"role\":\"assistant\",\"model\":\"fixture-model\"}",
				"{\"role\":\"assistant\",\"model\":\"fixture-model\",\"content\":{\"type\":\"text\",\"text\":\"x\",\"_meta\":{\"bad/key/again\":true}}}",
				"{\"role\":\"system\",\"model\":\"fixture-model\",\"content\":{\"type\":\"text\",\"text\":\"x\"}}",
				"{\"role\":\"assistant\",\"model\":7,\"content\":{\"type\":\"text\",\"text\":\"x\"}}",
				"{\"role\":\"assistant\",\"model\":\"fixture-model\",\"content\":{\"type\":\"text\"}}",
				"{\"role\":\"assistant\",\"model\":\"fixture-model\",\"content\":{\"type\":\"future\"}}",
				"{\"role\":\"assistant\",\"model\":\"fixture-model\",\"content\":{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"tool\",\"input\":[]}}",
				"{\"role\":\"assistant\",\"model\":\"fixture-model\",\"content\":{\"type\":\"tool_result\",\"toolUseId\":\"call-1\",\"content\":[{\"type\":\"resource_link\",\"name\":\"fixture\",\"uri\":\"relative\"}]}}")) {
			IllegalArgumentException exception = Assertions.assertThrows(
					IllegalArgumentException.class, () ->
							McpInputResponseValidator.validate(JSON_CODEC.parse(json)),
					json);
			Assertions.assertEquals("MCP input response is invalid.",
					exception.getMessage(), json);
			Assertions.assertFalse(exception.getMessage().contains(json), json);
		}
	}
}
