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
import java.util.Set;

/**
 * Tests final-schema and normative parameter validation for embedded MCP
 * input requests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
class McpEmbeddedInputRequestValidatorTests {
	private static final McpJsonCodec JSON_CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final McpInputRequestDeclaration ELICITATION_FORM =
			McpInputRequestDeclaration.elicitationForm(
					McpInputRequirement.CONDITIONAL);
	private static final McpInputRequestDeclaration ELICITATION_URL =
			McpInputRequestDeclaration.elicitationUrl(
					McpInputRequirement.CONDITIONAL);
	private static final McpInputRequestDeclaration SAMPLING =
			McpInputRequestDeclaration.sampling(Set.of(),
					McpInputRequirement.CONDITIONAL);
	private static final McpInputRequestDeclaration SAMPLING_CONTEXT =
			McpInputRequestDeclaration.sampling(
					Set.of(McpCoreClientCapability.SAMPLING_CONTEXT),
					McpInputRequirement.CONDITIONAL);
	private static final McpInputRequestDeclaration SAMPLING_TOOLS =
			McpInputRequestDeclaration.sampling(
					Set.of(McpCoreClientCapability.SAMPLING_TOOLS),
					McpInputRequirement.CONDITIONAL);
	private static final McpInputRequestDeclaration ROOTS =
			McpInputRequestDeclaration.roots(McpInputRequirement.CONDITIONAL);

	@Test
	void elicitationFormAcceptsOmittedModeOpenObjectsAndExactPrimitiveShapes() {
		assertValid(ELICITATION_FORM, """
				{"message":"","requestedSchema":{"type":"object",\
				"properties":{}}}
				""");
		assertValid(ELICITATION_FORM, """
				{
				  "message":"Collect profile",
				  "mode":"form",
				  "requestedSchema":{
				    "$schema":"com.example/future-schema",
				    "type":"object",
				    "properties":{
				      "freeString":{
				        "type":"string","title":"","description":"",
				        "format":"email","minLength":-10,"maxLength":-20,
				        "default":"","com.example/future":{"nested":true}
				      },
				      "freeNumber":{
				        "type":"number","minimum":10,"maximum":-10,
				        "default":200
				      },
				      "freeInteger":{"type":"integer","default":2.5},
				      "freeBoolean":{"type":"boolean","default":true},
				      "single":{
				        "type":"string","enum":[],"default":"not-an-option"
				      },
				      "titledSingle":{
				        "type":"string","oneOf":[],"default":"not-an-option"
				      },
				      "multi":{
				        "type":"array",
				        "items":{"type":"string","enum":["x","x"]},
				        "minItems":-1,"maxItems":-2,
				        "default":["not-an-option"]
				      },
				      "titledMulti":{
				        "type":"array",
				        "items":{"anyOf":[
				          {"const":"x","title":"X"},
				          {"const":"x","title":"X again"}
				        ]},
				        "minItems":-1,"maxItems":-2,"default":[]
				      },
				      "legacy":{
				        "type":"string","enum":["x","x"],"enumNames":[]
				      }
				    },
				    "required":["not-a-property","not-a-property"],
				    "com.example/schemaExtension":false
				  },
				  "com.example/parameterExtension":[1]
				}
					""");
	}

	@Test
	void openAnyOfBranchesIgnorePropertiesOwnedByOtherAlternatives() {
		assertValid(ELICITATION_FORM, """
				{"message":"Open string branch","requestedSchema":{
				  "type":"object","properties":{
				    "value":{"type":"string","enum":1}
				  }
				}}
				""");
		assertValid(ELICITATION_FORM, """
				{"message":"Open select branch","requestedSchema":{
				  "type":"object","properties":{
				    "value":{"type":"string","enum":["x"],"format":1}
				  }
				}}
				""");
		assertValid(ELICITATION_FORM, """
				{"message":"Open titled multi-select branch","requestedSchema":{
				  "type":"object","properties":{
				    "value":{"type":"array","items":{
				      "anyOf":[{"const":"x","title":"X"}],"type":1
				    }}
				  }
				}}
				""");
		assertValid(SAMPLING_TOOLS, toolResultFlow("""
				{"type":"resource","resource":{
				  "uri":"urn:example:text-resource","text":"ok","blob":1
				}}
				"""));
		assertValid(SAMPLING_TOOLS, toolResultFlow("""
				{"type":"resource","resource":{
				  "uri":"urn:example:blob-resource","blob":"AA==","text":false
				}}
				"""));
	}

	@Test
	void elicitationFormRejectsMalformedAndUrlParameterShapes() {
		for (String params : List.of(
				"{}",
				"{\"message\":\"missing schema\"}",
				"{\"message\":1,\"requestedSchema\":{\"type\":\"object\",\"properties\":{}}}",
				"{\"message\":\"wrong mode\",\"mode\":\"url\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{}}}",
				"{\"message\":\"wrong root\",\"requestedSchema\":{\"type\":\"array\",\"properties\":{}}}",
				"{\"message\":\"wrong properties\",\"requestedSchema\":{\"type\":\"object\",\"properties\":[]}}",
				"{\"message\":\"wrong required\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{},\"required\":[1]}}",
				"{\"message\":\"nested object\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"nested\":{\"type\":\"object\"}}}}",
				"{\"message\":\"wrong format\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"format\":\"ipv4\"}}}}",
				"{\"message\":\"wrong bound\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"minLength\":\"one\"}}}}",
				"{\"message\":\"wrong array items\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"array\",\"items\":{}}}}}",
				"{\"message\":\"URL shape\",\"mode\":\"url\",\"url\":\"https://example.test/\"}"))
			assertInvalid(ELICITATION_FORM, params);
	}

	@Test
	void elicitationUrlAcceptsAllUriSchemesAndRejectsMalformedOrFormShapes() {
		assertValid(ELICITATION_URL, """
				{"message":"Authorize","mode":"url",\
				"url":"https://example.test/authorize?state=opaque",\
				"com.example/future":{"enabled":true}}
				""");
		assertValid(ELICITATION_URL, """
				{"message":"","mode":"url","url":"urn:example:workflow"}
				""");

		for (String params : List.of(
				"{}",
				"{\"message\":\"missing mode\",\"url\":\"https://example.test/\"}",
				"{\"message\":\"missing URL\",\"mode\":\"url\"}",
				"{\"message\":1,\"mode\":\"url\",\"url\":\"https://example.test/\"}",
				"{\"message\":\"wrong mode\",\"mode\":\"form\",\"url\":\"https://example.test/\"}",
				"{\"message\":\"relative\",\"mode\":\"url\",\"url\":\"relative/path\"}",
				"{\"message\":\"bad escape\",\"mode\":\"url\",\"url\":\"https://example.test/%zz\"}",
				"{\"message\":\"wrong type\",\"mode\":\"url\",\"url\":1}",
				"{\"message\":\"form shape\",\"mode\":\"form\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{}}}"))
			assertInvalid(ELICITATION_URL, params);
	}

	@Test
	void samplingBaseAcceptsOpenNestedContentAndUnconstrainedSchemaValues() {
		assertValid(SAMPLING, "{\"maxTokens\":1.0,\"messages\":[]}");
		assertValid(SAMPLING, """
				{
				  "messages":[
				    {
				      "role":"user",
				      "content":{"type":"text","text":"",\
				        "com.example/future":1},
				      "com.example/messageExtension":true
				    },
				    {
				      "role":"assistant",
				      "content":[
				        {"type":"image","data":"","mimeType":"",\
				          "com.example/future":{}},
				        {"type":"audio","data":"","mimeType":""}
				      ]
				    }
				  ],
				  "maxTokens":-3,
				  "modelPreferences":{
				    "hints":[{}, {"name":""}],
				    "costPriority":0,
				    "intelligencePriority":1,
				    "speedPriority":0.5,
				    "com.example/future":"preserved"
				  },
				  "stopSequences":["",""],
				  "systemPrompt":"",
				  "includeContext":"none",
				  "temperature":-100,
				  "metadata":{"providerExtension":[true]},
				  "com.example/parameterExtension":null
				}
				""");
	}

	@Test
	void samplingBaseRejectsMalformedRequiredAndNestedValues() {
		for (String params : List.of(
				"{}",
				"{\"maxTokens\":1}",
				"{\"messages\":[]}",
				"{\"maxTokens\":\"1\",\"messages\":[]}",
				"{\"maxTokens\":1.5,\"messages\":[]}",
				"{\"maxTokens\":1,\"messages\":{}}",
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"system\",\"content\":{\"type\":\"text\",\"text\":\"x\"}}]}",
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"text\"}]}",
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"user\",\"content\":{}}]}",
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"image\",\"data\":\"\"}}]}",
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"audio\",\"data\":1,\"mimeType\":\"audio/wav\"}}]}",
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"user\",\"content\":[1]}]}",
				"{\"maxTokens\":1,\"messages\":[],\"modelPreferences\":{\"costPriority\":-0.1}}",
				"{\"maxTokens\":1,\"messages\":[],\"modelPreferences\":{\"speedPriority\":1.1}}",
				"{\"maxTokens\":1,\"messages\":[],\"stopSequences\":[1]}",
				"{\"maxTokens\":1,\"messages\":[],\"metadata\":[]}",
				"{\"maxTokens\":1,\"messages\":[],\"metadata\":{\"nested\":null}}",
				"{\"maxTokens\":1,\"messages\":[],\"metadata\":{\"nested\":1.5}}"))
			assertInvalid(SAMPLING, params);
	}

	@Test
	void samplingContextValuesMatchTheDeclarationCapability() {
		assertValid(SAMPLING, samplingWith(""));
		assertValid(SAMPLING, samplingWith(",\"includeContext\":\"none\""));
		for (String context : List.of("none", "thisServer", "allServers"))
			assertValid(SAMPLING_CONTEXT,
					samplingWith(",\"includeContext\":\"" + context + "\""));

		assertInvalid(SAMPLING,
				samplingWith(",\"includeContext\":\"thisServer\""));
		assertInvalid(SAMPLING,
				samplingWith(",\"includeContext\":\"allServers\""));
		assertInvalid(SAMPLING_CONTEXT,
				samplingWith(",\"includeContext\":\"future\""));
		assertInvalid(SAMPLING_CONTEXT,
				samplingWith(",\"includeContext\":true"));
	}

	@Test
	void samplingToolsAndToolChoiceMatchTheDeclarationAndFinalSchema() {
		assertValid(SAMPLING_TOOLS, samplingWith(",\"tools\":[]"));
		assertValid(SAMPLING_TOOLS, samplingWith(",\"toolChoice\":{}"));
		assertValid(SAMPLING_TOOLS, """
				{
				  "maxTokens":0,
				  "messages":[],
				  "tools":[{
				    "name":"",
				    "description":"",
				    "inputSchema":{
				      "type":"object",
				      "properties":{"future":true},
				      "com.example/schemaExtension":null
				    },
				    "com.example/toolExtension":[1]
				  }],
				  "toolChoice":{"mode":"required",\
				    "com.example/choiceExtension":true}
				}
				""");
		for (String mode : List.of("auto", "none", "required"))
			assertValid(SAMPLING_TOOLS,
					samplingWith(",\"toolChoice\":{\"mode\":\""
							+ mode + "\"}"));

		assertInvalid(SAMPLING, samplingWith(",\"tools\":[]"));
		assertInvalid(SAMPLING, samplingWith(",\"toolChoice\":{}"));
		assertInvalid(SAMPLING_CONTEXT, samplingWith(",\"tools\":[]"));
		for (String params : List.of(
				samplingWith(",\"tools\":{}"),
				samplingWith(",\"tools\":[{\"inputSchema\":{\"type\":\"object\"}}]"),
				samplingWith(",\"tools\":[{\"name\":\"missing schema\"}]"),
				samplingWith(",\"tools\":[{\"name\":1,\"inputSchema\":{\"type\":\"object\"}}]"),
				samplingWith(",\"tools\":[{\"name\":\"bad root\",\"inputSchema\":{\"type\":\"string\"}}]"),
				samplingWith(",\"toolChoice\":\"auto\""),
				samplingWith(",\"toolChoice\":{\"mode\":\"future\"}")))
			assertInvalid(SAMPLING_TOOLS, params);
	}

	@Test
	void samplingToolsCoverRecognizedNestedDescriptorsAndContentBranches() {
		assertValid(SAMPLING_TOOLS, """
				{
				  "maxTokens":1,
				  "tools":[{
				    "name":"resources",
				    "title":"",
				    "description":"",
				    "_meta":{"com.example/tool":true},
				    "icons":[{
				      "src":"https://example.test/icon.png",
				      "mimeType":"image/png",
				      "sizes":["",""],
				      "theme":"dark",
				      "com.example/iconExtension":false
				    }],
				    "inputSchema":{
				      "$schema":"",
				      "type":"object",
				      "com.example/inputExtension":true
				    },
				    "outputSchema":{
				      "$schema":"",
				      "com.example/outputExtension":null
				    },
				    "annotations":{
				      "title":"",
				      "readOnlyHint":true,
				      "destructiveHint":false,
				      "idempotentHint":true,
				      "openWorldHint":false,
				      "com.example/annotationExtension":1
				    }
				  }],
				  "messages":[
				    {"role":"assistant","content":{
				      "type":"tool_use","id":"resource-call",\
				      "name":"resources","input":{},
				      "_meta":{"com.example/use":true}
				    }},
				    {"role":"user","content":{
				      "type":"tool_result","toolUseId":"resource-call",
				      "isError":false,
				      "structuredContent":{"future":true},
				      "content":[
				        {
				          "type":"resource_link","name":"","title":"",
				          "description":"","mimeType":"","size":-1,
				          "uri":"urn:example:linked-resource",
				          "icons":[{
				            "src":"https://example.test/link.svg",
				            "sizes":[],"theme":"light"
				          }],
				          "annotations":{
				            "audience":["user","assistant","user"],
				            "lastModified":"","priority":0
				          },
				          "_meta":{"com.example/link":true}
				        },
				        {
				          "type":"resource",
				          "resource":{
				            "uri":"urn:example:text-resource",
				            "mimeType":"text/plain","text":"",
				            "_meta":{"com.example/text":true}
				          },
				          "annotations":{"priority":1}
				        },
				        {
				          "type":"resource",
				          "resource":{
				            "uri":"urn:example:blob-resource","blob":"not-checked",
				            "com.example/resourceExtension":true
				          }
				        }
				      ]
				    }}
				  ]
				}
				""");

		for (String params : List.of(
				samplingWith(",\"tools\":[{\"name\":\"bad icon URI\",\"inputSchema\":{\"type\":\"object\"},\"icons\":[{\"src\":\"relative/icon.png\"}]}]"),
				samplingWith(",\"tools\":[{\"name\":\"bad icon theme\",\"inputSchema\":{\"type\":\"object\"},\"icons\":[{\"src\":\"https://example.test/icon\",\"theme\":\"future\"}]}]"),
				samplingWith(",\"tools\":[{\"name\":\"bad metadata\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"bad key\":true}}]"),
				samplingWith(",\"tools\":[{\"name\":\"bad output schema\",\"inputSchema\":{\"type\":\"object\"},\"outputSchema\":{\"$schema\":1}}]"),
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"x\",\"annotations\":{\"priority\":1.1}}}]}",
				toolResultFlow("{\"type\":\"resource_link\",\"name\":\"bad URI\",\"uri\":\"relative/resource\"}"),
				toolResultFlow("{\"type\":\"resource\",\"resource\":{\"uri\":\"relative/resource\",\"text\":\"x\"}}"),
				toolResultFlow("{\"type\":\"resource\",\"resource\":{\"uri\":\"urn:example:missing-content\"}}"),
				toolResultFlow("{\"type\":\"text\",\"text\":\"x\",\"annotations\":{\"audience\":[\"future\"]}}")))
			assertInvalid(SAMPLING_TOOLS, params);
	}

	@Test
	void samplingToolUseAndResultFlowEnforcesTheNormativeSequence() {
		assertValid(SAMPLING_TOOLS, """
				{
				  "maxTokens":10,
				  "tools":[],
				  "messages":[
				    {"role":"user","content":{"type":"text","text":"go"}},
				    {"role":"assistant","content":[
				      {"type":"text","text":"checking"},
				      {"type":"tool_use","id":"a","name":"lookup",\
				        "input":{"value":1}},
				      {"type":"tool_use","id":"b","name":"lookup",\
				        "input":{"value":2}}
				    ]},
				    {"role":"user","content":[
				      {"type":"tool_result","toolUseId":"b","content":[]},
				      {"type":"tool_result","toolUseId":"a","content":[
				        {"type":"text","text":"done"}
				      ]}
				    ]},
				    {"role":"assistant","content":{"type":"text","text":"done"}}
				  ]
				}
				""");

		for (String params : List.of(
				toolFlow("user", toolUse("a"), "user", toolResult("a")),
				toolFlow("assistant", toolResult("a"), "user", toolResult("a")),
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"user\",\"content\":["
						+ toolResult("a") + ",{\"type\":\"text\",\"text\":\"mixed\"}]}]}",
				toolFlow("assistant", toolUse("a"), "assistant",
						"{\"type\":\"text\",\"text\":\"too soon\"}"),
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"assistant\",\"content\":["
						+ toolUse("a") + "," + toolUse("b")
						+ "]},{\"role\":\"user\",\"content\":["
						+ toolResult("a") + "]}]}",
				toolFlow("assistant", toolUse("a"), "user", toolResult("b")),
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"assistant\",\"content\":"
						+ toolUse("a") + "},{\"role\":\"user\",\"content\":["
						+ toolResult("a") + "," + toolResult("b") + "]}]}",
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"assistant\",\"content\":"
						+ toolUse("a") + "}]}",
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"user\",\"content\":"
						+ toolResult("a") + "}]}",
				"{\"maxTokens\":1,\"messages\":[{\"role\":\"assistant\",\"content\":["
						+ toolUse("same") + "," + toolUse("same")
						+ "]},{\"role\":\"user\",\"content\":["
						+ toolResult("same") + "," + toolResult("same") + "]}]}",
				"{\"maxTokens\":1,\"messages\":["
						+ "{\"role\":\"assistant\",\"content\":" + toolUse("same") + "},"
						+ "{\"role\":\"user\",\"content\":" + toolResult("same") + "},"
						+ "{\"role\":\"assistant\",\"content\":" + toolUse("same") + "},"
						+ "{\"role\":\"user\",\"content\":" + toolResult("same") + "}]}"))
			assertInvalid(SAMPLING_TOOLS, params);
	}

	@Test
	void rootsAcceptEmptyMetadataAndOpenExtensionsButRejectMalformedMetadata() {
		assertValid(ROOTS, "{}");
		assertValid(ROOTS, "{\"_meta\":{}}");
		assertValid(ROOTS, """
				{"_meta":{"com.example/future":[true]},\
				"com.example/parameterExtension":{"nested":1},\
				"futureScalar":false}
				""");

		for (String params : List.of(
				"{\"_meta\":null}",
				"{\"_meta\":\"bad\"}",
				"{\"_meta\":[]}"))
			assertInvalid(ROOTS, params);
	}

	@Test
	void invalidParameterDiagnosticsNeverIncludeApplicationValues() {
		List<DiagnosticCase> cases = List.of(
				new DiagnosticCase(ELICITATION_FORM,
						"{\"message\":\"FORM-MESSAGE-SECRET\",\"requestedSchema\":{\"type\":\"FORM-TYPE-SECRET\",\"properties\":{}}}",
						List.of("FORM-MESSAGE-SECRET", "FORM-TYPE-SECRET")),
				new DiagnosticCase(ELICITATION_URL,
						"{\"message\":\"URL-MESSAGE-SECRET\",\"mode\":\"url\",\"url\":\"URL-VALUE-SECRET\"}",
						List.of("URL-MESSAGE-SECRET", "URL-VALUE-SECRET")),
				new DiagnosticCase(SAMPLING,
						"{\"maxTokens\":\"TOKEN-SECRET\",\"messages\":[],\"systemPrompt\":\"PROMPT-SECRET\"}",
						List.of("TOKEN-SECRET", "PROMPT-SECRET")),
				new DiagnosticCase(ROOTS,
						"{\"_meta\":\"METADATA-SECRET\"}",
						List.of("METADATA-SECRET")));

		for (DiagnosticCase testCase : cases) {
			IllegalArgumentException exception = assertInvalid(
					testCase.declaration(), testCase.params());
			String diagnostic = String.valueOf(exception.getMessage());
			Assertions.assertEquals(
					"Embedded MCP input-request parameters are invalid.", diagnostic);
			for (String secret : testCase.secretValues())
				Assertions.assertFalse(diagnostic.contains(secret), diagnostic);
		}
	}

	private static void assertValid(McpInputRequestDeclaration declaration,
			String params) {
		McpJsonObject parsed = object(params);
		Assertions.assertDoesNotThrow(() ->
				McpEmbeddedInputRequestValidator.validate(declaration, parsed), params);
		McpEmbeddedInputRequest request = Assertions.assertDoesNotThrow(() ->
				McpEmbeddedInputRequest.fromDeclaration(declaration, parsed), params);
		Assertions.assertSame(parsed, request.params());
	}

	private static IllegalArgumentException assertInvalid(
			McpInputRequestDeclaration declaration, String params) {
		McpJsonObject parsed = object(params);
		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class, () ->
						McpEmbeddedInputRequestValidator.validate(declaration, parsed),
				params);
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				McpEmbeddedInputRequest.fromDeclaration(declaration, parsed), params);
		return exception;
	}

	private static McpJsonObject object(String json) {
		return Assertions.assertInstanceOf(McpJsonObject.class,
				JSON_CODEC.parse(json));
	}

	private static String samplingWith(String additionalFields) {
		return "{\"maxTokens\":1,\"messages\":[]" + additionalFields + "}";
	}

	private static String toolUse(String id) {
		return "{\"type\":\"tool_use\",\"id\":\"" + id
				+ "\",\"name\":\"tool\",\"input\":{}}";
	}

	private static String toolResult(String id) {
		return "{\"type\":\"tool_result\",\"toolUseId\":\"" + id
				+ "\",\"content\":[]}";
	}

	private static String toolFlow(String firstRole, String firstContent,
			String secondRole, String secondContent) {
		return "{\"maxTokens\":1,\"messages\":[{\"role\":\""
				+ firstRole + "\",\"content\":" + firstContent
				+ "},{\"role\":\"" + secondRole + "\",\"content\":"
				+ secondContent + "}]}";
	}

	private static String toolResultFlow(String resultContent) {
		return "{\"maxTokens\":1,\"messages\":["
				+ "{\"role\":\"assistant\",\"content\":" + toolUse("nested") + "},"
				+ "{\"role\":\"user\",\"content\":{\"type\":\"tool_result\","
				+ "\"toolUseId\":\"nested\",\"content\":[" + resultContent
				+ "]}}]}";
	}

	private record DiagnosticCase(McpInputRequestDeclaration declaration,
			String params, List<String> secretValues) {
	}
}
