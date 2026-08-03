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

package com.soklet.internal.mcp.schema;

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class McpSchemaUseValidatorTests {
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(1_000_000, 256, 200_000, 200_000, 4_096,
					100_000, 100_000, 1_000_000));
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			McpSchemaCompilationLimits.productionDefaults();
	private static final McpSchemaUseValidator VALIDATOR =
			new McpSchemaUseValidator();

	@Test
	public void toolInputRootMustDirectlyDeclareObjectType() {
		validateInput("{\"type\":\"object\"}");

		for (String schema : List.of(
				"{}",
				"{\"type\":\"array\"}",
				"{\"type\":[\"object\"]}",
				"{\"allOf\":[{\"type\":\"object\"}]}",
				"{\"$defs\":{\"object\":{\"type\":\"object\"}},"
						+ "\"$ref\":\"#/$defs/object\"}")) {
			McpSchemaCompilationException exception =
					assertInputRejected(schema);
			Assertions.assertEquals(McpSchemaCompilationException.Kind.INVALID_SCHEMA,
					exception.kind(), schema);
			Assertions.assertEquals("type", exception.keyword().orElseThrow());
			Assertions.assertEquals("",
					exception.location().orElseThrow().jsonPointer());
		}
	}

	@Test
	public void toolOutputAcceptsAnyProfileRootButRejectsEveryHeaderDeclaration() {
		validateOutput("{\"type\":\"string\"}");
		validateOutput("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}");
		validateOutput("{\"type\":\"object\"}");

		for (String schema : List.of(
				"{\"type\":\"string\",\"x-mcp-header\":\"Root\"}",
				"{\"type\":\"object\",\"properties\":{\"x\":{"
						+ "\"type\":\"string\",\"x-mcp-header\":\"X\"}}}",
				"{\"type\":\"array\",\"items\":{\"type\":\"string\","
						+ "\"x-mcp-header\":\"X\"}}")) {
			McpSchemaCompilationException exception =
					assertOutputRejected(schema);
			Assertions.assertEquals("x-mcp-header",
					exception.keyword().orElseThrow(), schema);
		}
	}

	@Test
	public void inputHeadersMayUseOnlyPropertiesChainsFromTheRoot() {
		validateInput("""
				{"type":"object","properties":{
				  "tenant":{"type":"string","x-mcp-header":"Tenant"},
				  "nested":{"type":"object","properties":{
				    "region":{"type":"string","x-mcp-header":"Region"}}}
				}}
				""");

		for (String schema : List.of(
				"{\"type\":\"object\",\"x-mcp-header\":\"Root\"}",
				"{\"type\":\"object\",\"$defs\":{\"x\":{\"type\":\"string\","
						+ "\"x-mcp-header\":\"X\"}}}",
				"{\"type\":\"object\",\"properties\":{\"values\":{"
						+ "\"type\":\"array\",\"items\":{\"type\":\"string\","
						+ "\"x-mcp-header\":\"X\"}}}}",
				"{\"type\":\"object\",\"allOf\":[{\"properties\":{\"x\":{"
						+ "\"type\":\"string\",\"x-mcp-header\":\"X\"}}}]}",
				"{\"type\":\"object\",\"anyOf\":[{\"properties\":{\"x\":{"
						+ "\"type\":\"string\",\"x-mcp-header\":\"X\"}}}]}",
				"{\"type\":\"object\",\"if\":{\"properties\":{\"x\":{"
						+ "\"type\":\"string\",\"x-mcp-header\":\"X\"}}}}",
				"{\"type\":\"object\",\"$defs\":{\"x\":{\"type\":\"string\","
						+ "\"x-mcp-header\":\"X\"}},\"properties\":{\"x\":{"
						+ "\"$ref\":\"#/$defs/x\"}}}"))
			assertInputRejected(schema);
	}

	@Test
	public void inputHeadersRequireOneDirectPermittedPrimitiveType() {
		validateInput(schemaWithHeader("{\"type\":\"string\"}"));
		validateInput(schemaWithHeader("{\"type\":\"boolean\"}"));
		validateInput(schemaWithHeader("{\"type\":\"integer\"}"));

		for (String propertySchema : List.of(
				"{}",
				"{\"type\":\"number\"}",
				"{\"type\":\"object\"}",
				"{\"type\":[\"string\"]}",
				"{\"type\":[\"string\",\"null\"]}"))
			assertInputRejected(schemaWithHeader(propertySchema));
	}

	@Test
	public void headerNamesMustBeNonemptyTokensAndUniqueIgnoringCase() {
		validateInput(schemaWithNamedStringHeader("!#$%&'*+-.^_`|~AZaz09"));

		for (String name : List.of("", "bad name", "bad:name", "ténant",
				"bad\rname"))
			assertInputRejected(schemaWithNamedStringHeader(name));

		assertInputRejected("""
				{"type":"object","properties":{
				  "first":{"type":"string","x-mcp-header":"Tenant"},
				  "second":{"type":"boolean","x-mcp-header":"tenant"}
				}}
				""");
	}

	private static String schemaWithHeader(String propertySchema) {
		String withoutClosingBrace = propertySchema.substring(0,
				propertySchema.length() - 1);
		String separator = withoutClosingBrace.equals("{") ? "" : ",";
		return "{\"type\":\"object\",\"properties\":{\"value\":"
				+ withoutClosingBrace + separator
				+ "\"x-mcp-header\":\"Value\"}}}";
	}

	private static String schemaWithNamedStringHeader(String name) {
		return "{\"type\":\"object\",\"properties\":{\"value\":{"
				+ "\"type\":\"string\",\"x-mcp-header\":"
				+ JSON_CODEC.toJson(
						new com.soklet.internal.mcp.protocol.McpJsonString(name))
				+ "}}}";
	}

	private static void validateInput(String schema) {
		VALIDATOR.validateToolInput(compile(schema));
	}

	private static void validateOutput(String schema) {
		VALIDATOR.validateToolOutput(compile(schema));
	}

	private static McpSchemaCompilationException assertInputRejected(
			String schema) {
		return Assertions.assertThrows(McpSchemaCompilationException.class,
				() -> validateInput(schema), schema);
	}

	private static McpSchemaCompilationException assertOutputRejected(
			String schema) {
		return Assertions.assertThrows(McpSchemaCompilationException.class,
				() -> validateOutput(schema), schema);
	}

	private static McpToolSchemaProfileProgram compile(String schema) {
		McpJsonObject document = Assertions.assertInstanceOf(McpJsonObject.class,
				JSON_CODEC.parse(schema));
		return new McpToolSchemaProfileCompiler(COMPILATION_LIMITS)
				.compile(document);
	}
}
