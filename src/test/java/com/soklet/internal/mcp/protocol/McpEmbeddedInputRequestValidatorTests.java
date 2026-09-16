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
	void invalidParameterDiagnosticsNeverIncludeApplicationValues() {
		List<DiagnosticCase> cases = List.of(
				new DiagnosticCase(ELICITATION_FORM,
						"{\"message\":\"FORM-MESSAGE-SECRET\",\"requestedSchema\":{\"type\":\"FORM-TYPE-SECRET\",\"properties\":{}}}",
						List.of("FORM-MESSAGE-SECRET", "FORM-TYPE-SECRET")),
				new DiagnosticCase(ELICITATION_URL,
						"{\"message\":\"URL-MESSAGE-SECRET\",\"mode\":\"url\",\"url\":\"URL-VALUE-SECRET\"}",
						List.of("URL-MESSAGE-SECRET", "URL-VALUE-SECRET")));

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

	private record DiagnosticCase(McpInputRequestDeclaration declaration,
			String params, List<String> secretValues) {
	}
}
