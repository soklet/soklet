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

import java.util.List;

/**
 * Coverage for final-schema MCP input-response validation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpInputResponseValidatorTests {
	private static final McpJsonCodec JSON_CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());

	@Test
	public void acceptsOpenElicitationResultsWithoutMutatingThem() {
		for (String json : List.of(
				"{\"action\":\"decline\"}",
				"{\"action\":\"cancel\",\"future\":null}",
				"""
				{"action":"accept","content":{
				  "string":"value","integer":7,"boolean":true,
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
				"{\"action\":\"accept\",\"content\":{\"value\":1.5}}",
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
