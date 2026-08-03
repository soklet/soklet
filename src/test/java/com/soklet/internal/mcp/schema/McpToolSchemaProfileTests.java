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
import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class McpToolSchemaProfileTests {
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(1_000_000, 256, 200_000, 200_000, 4_096,
					100_000, 100_000, 1_000_000));
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			McpSchemaCompilationLimits.productionDefaults();
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			McpSchemaEvaluationLimits.productionDefaults();

	@Test
	public void exactOfficialMcpFixtureCompilesPreservesAndEvaluates()
			throws IOException {
		McpToolSchemaProfileTestManifest.OfficialScenario scenario =
				McpToolSchemaProfileTestManifest.load().officialScenario();
		McpJsonObject tool = object(McpToolSchemaProfileTestManifest.readJson(
				McpToolSchemaProfileTestManifest.PROFILE_ROOT + scenario.fixture()));
		McpJsonObject document = object(tool.members().get("inputSchema"));
		McpToolSchemaProfileProgram program = compile(document);

		Assertions.assertSame(document, program.document());
		Assertions.assertEquals(document, JSON_CODEC.parse(
				JSON_CODEC.toJson(program.document())));

		McpJsonObject cases = object(McpToolSchemaProfileTestManifest.readJson(
				McpToolSchemaProfileTestManifest.PROFILE_ROOT + scenario.cases()));
		assertCases(program, array(cases.members().get("valid")), true);
		assertCases(program, array(cases.members().get("invalid")), false);
	}

	@Test
	public void exactAndAbsentDialectAreAcceptedButEveryOtherDialectIsRejected() {
		compile("{}");
		compile("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}");

		for (String schema : List.of(
				"{\"$schema\":\"http://json-schema.org/draft-07/schema#\"}",
				"{\"$schema\":1}",
				"{\"properties\":{\"x\":{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}}}"))
			Assertions.assertThrows(McpSchemaCompilationException.class,
					() -> compile(schema), schema);
	}

	@Test
	public void everyNamedExclusionAndUnknownExtensionFailsClosed() {
		for (String keyword
				: McpToolSchemaProfileCompiler.explicitlyRejectedKeywords()) {
			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compile("{\"" + keyword + "\":null}"), keyword);
			Assertions.assertEquals(
					McpSchemaCompilationException.Kind.UNSUPPORTED_KEYWORD,
					exception.kind(), keyword);
			Assertions.assertEquals(keyword, exception.keyword().orElseThrow());
		}

		for (String keyword : List.of("x-example", "unknown", "$future")) {
			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compile("{\"" + keyword + "\":true}"));
			Assertions.assertEquals(
					McpSchemaCompilationException.Kind.UNSUPPORTED_KEYWORD,
					exception.kind());
		}
	}

	@Test
	public void instanceDataObjectsAreOpaqueToTheKeywordAllowlist() {
		McpToolSchemaProfileProgram program = compile("""
				{
				  "const":{"pattern":"literal","$id":"also-literal"},
				  "default":{"oneOf":"literal"},
				  "examples":[{"unevaluatedProperties":"literal"}]
				}
				""");
		assertValid(program,
				"{\"pattern\":\"literal\",\"$id\":\"also-literal\"}");
		assertInvalid(program, "{}");
	}

	@Test
	public void rootMustBeObjectButBooleanSubschemasAreSupported() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> compileValue("true"));
		McpToolSchemaProfileProgram program = compile("""
				{"type":"object","properties":{"allowed":true,"blocked":false}}
				""");
		assertValid(program, "{\"allowed\":1}");
		assertInvalid(program, "{\"blocked\":1}");
	}

	@Test
	public void localPointersAndAnchorsResolveWhileOtherReferencesFailClosed() {
		McpToolSchemaProfileProgram anchored = compile("""
				{"$defs":{"value":{"$anchor":"valueDef","type":"integer"}},
				 "properties":{"byPointer":{"$ref":"#/$defs/value"},
				               "byAnchor":{"$ref":"#valueDef"}}}
				""");
		assertValid(anchored, "{\"byPointer\":1,\"byAnchor\":2}");
		assertInvalid(anchored, "{\"byPointer\":\"1\"}");

		for (String reference : List.of("other.json", "other.json#value",
				"https://example.test/schema", "#/missing", "#missing", "#/%ZZ"))
			Assertions.assertThrows(McpSchemaCompilationException.class,
					() -> compile("{\"$ref\":" + jsonString(reference) + "}"),
					reference);

		Assertions.assertThrows(McpSchemaCompilationException.class,
				() -> compile("{\"$anchor\":\"same\",\"$defs\":{\"x\":{\"$anchor\":\"same\"}}}"));
	}

	@Test
	public void localReferenceFragmentsRequireRfc3986Encoding() {
		for (Map.Entry<String, String> example : Map.of(
				"a b", "a%20b",
				"accent-é", "accent-%C3%A9",
				"emoji-😀", "emoji-%F0%9F%98%80",
				"hash#name", "hash%23name",
				"snowman-☃", "snowman-%E2%98%83").entrySet()) {
			String schema = "{\"$defs\":{" + jsonString(example.getKey())
					+ ":{\"type\":\"integer\"}},\"$ref\":\"#/$defs/"
					+ example.getValue() + "\"}";
			McpToolSchemaProfileProgram program = compile(schema);
			assertValid(program, "1");
			assertInvalid(program, "\"1\"");
		}

		for (String schema : List.of(
				"{\"$defs\":{\"value\":{\"$anchor\":\"encodedName\","
						+ "\"type\":\"integer\"}},\"$ref\":\"#encoded%4Eame\"}",
				"{\"$defs\":{\"value\":{\"type\":\"integer\"}},"
						+ "\"$ref\":\"#%2F$defs%2Fvalue\"}",
				"{\"$defs\":{\"~\":{\"type\":\"integer\"}},"
						+ "\"$ref\":\"#/$defs/~0\"}",
				"{\"$defs\":{\"/\":{\"type\":\"integer\"}},"
						+ "\"$ref\":\"#/$defs/~1\"}")) {
			McpToolSchemaProfileProgram program = compile(schema);
			assertValid(program, "1");
			assertInvalid(program, "\"1\"");
		}

		for (String reference : List.of("#/$defs/a b", "#/$defs/hash#name",
				"#/$defs/quote\"name", "#/$defs/back\\slash",
				"#/$defs/snowman-☃", "#/%E2%82", "#/%C0%AF",
				"#/%ED%A0%80", "#/%F4%90%80%80")) {
			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compile("{\"$ref\":" + jsonString(reference) + "}"),
					reference);
			Assertions.assertEquals(
					McpSchemaCompilationException.Kind.INVALID_REFERENCE,
					exception.kind(), reference);
		}
	}

	@Test
	public void profileApplicatorsAndNumericBoundsAreEnforced() {
		McpToolSchemaProfileProgram arrays = compile("""
				{"type":"array","items":{"type":"integer","minimum":0,"maximum":2}}
				""");
		assertValid(arrays, "[0,1,2]");
		assertInvalid(arrays, "[-1]");
		assertInvalid(arrays, "[3]");
		assertInvalid(arrays, "[1.5]");

		McpToolSchemaProfileProgram additional = compile("""
				{"type":"object","properties":{"known":{"type":"string"}},
				 "additionalProperties":{"type":"integer"}}
				""");
		assertValid(additional, "{\"known\":\"x\",\"other\":1}");
		assertInvalid(additional, "{\"other\":\"x\"}");

		McpToolSchemaProfileProgram inertBranches =
				compile("{\"then\":false,\"else\":false}");
		assertValid(inertBranches, "null");
	}

	@Test
	public void headerDeclarationsArePreservedForSchemaUseValidation() {
		McpToolSchemaProfileProgram program = compile("""
				{"type":"object","properties":{
				  "tenant":{"type":"string","x-mcp-header":"Tenant"},
				  "nested":{"type":"object","properties":{
				    "shard":{"type":"integer","x-mcp-header":"Shard"}}}
				}}
				""");
		Assertions.assertEquals(Map.of(
				"/properties/nested/properties/shard", "Shard",
				"/properties/tenant", "Tenant"),
				program.declaredHeadersBySchemaPointer());

		for (String schema : List.of(
				"{\"x-mcp-header\":\"Root\",\"type\":\"string\"}",
				"{\"$defs\":{\"x\":{\"type\":\"string\",\"x-mcp-header\":\"Hidden\"}}}",
				"{\"properties\":{\"x\":{\"type\":\"number\",\"x-mcp-header\":\"X\"}}}",
				"{\"properties\":{\"x\":{\"type\":\"string\",\"x-mcp-header\":\"bad name\"}}}",
				"{\"properties\":{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Tenant\"},\"b\":{\"type\":\"boolean\",\"x-mcp-header\":\"tenant\"}}}"))
			compile(schema);

		Assertions.assertThrows(McpSchemaCompilationException.class,
				() -> compile("{\"x-mcp-header\":true}"));
	}

	@Test
	public void malformedKeywordShapesAndHardLimitsFailDuringCompilation() {
		for (String schema : List.of(
				"{\"$defs\":[]}",
				"{\"properties\":[]}",
				"{\"additionalProperties\":0}",
				"{\"items\":0}",
				"{\"allOf\":[]}",
				"{\"anyOf\":[0]}",
				"{\"type\":[]}",
				"{\"type\":\"unknown\"}",
				"{\"required\":[\"x\",\"x\"]}",
				"{\"minimum\":\"0\"}",
				"{\"examples\":{}}",
				"{\"deprecated\":\"true\"}"))
			Assertions.assertThrows(McpSchemaCompilationException.class,
					() -> compile(schema), schema);

		McpSchemaCompilationLimits oneNode = new McpSchemaCompilationLimits(
				1, 1, 10, 1, 1, 100, 100, 10, 10, 100, 100);
		new McpToolSchemaProfileCompiler(oneNode).compile(object("{}"));
		McpSchemaCompilationException exception = Assertions.assertThrows(
				McpSchemaCompilationException.class,
				() -> new McpToolSchemaProfileCompiler(oneNode).compile(
						object("{\"properties\":{\"x\":true}}")));
		Assertions.assertEquals(McpSchemaCompilationException.Kind.LIMIT_EXCEEDED,
				exception.kind());
	}

	@Test
	public void localReferenceCyclesTerminateWithTypedLimitOutcome() {
		McpToolSchemaProfileProgram program = compile("{\"$ref\":\"#\"}");
		McpSchemaValidationOutcome outcome = evaluate(program, "null");
		McpSchemaValidationOutcome.LimitExceeded limit =
				Assertions.assertInstanceOf(
						McpSchemaValidationOutcome.LimitExceeded.class, outcome);
		Assertions.assertEquals(McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS,
				limit.limit());
	}

	private static McpToolSchemaProfileProgram compile(String schema) {
		return compile(object(schema));
	}

	private static McpToolSchemaProfileProgram compile(McpJsonObject schema) {
		return new McpToolSchemaProfileCompiler(COMPILATION_LIMITS).compile(schema);
	}

	private static McpToolSchemaProfileProgram compileValue(String schema) {
		McpJsonValue value = JSON_CODEC.parse(schema);
		if (!(value instanceof McpJsonObject object))
			throw new IllegalArgumentException("A profile root must be an object.");
		return compile(object);
	}

	private static McpSchemaValidationOutcome evaluate(
			McpToolSchemaProfileProgram program, String instance) {
		return evaluate(program, JSON_CODEC.parse(instance));
	}

	private static McpSchemaValidationOutcome evaluate(
			McpToolSchemaProfileProgram program, McpJsonValue instance) {
		return new McpToolSchemaProfileEvaluator().evaluate(program, instance,
				EVALUATION_LIMITS);
	}

	private static void assertValid(McpToolSchemaProfileProgram program,
			String instance) {
		Assertions.assertInstanceOf(McpSchemaValidationOutcome.Valid.class,
				evaluate(program, instance), instance);
	}

	private static void assertInvalid(McpToolSchemaProfileProgram program,
			String instance) {
		Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class,
				evaluate(program, instance), instance);
	}

	private static McpJsonObject object(String json) {
		return Assertions.assertInstanceOf(McpJsonObject.class,
				JSON_CODEC.parse(json));
	}

	private static McpJsonObject object(McpJsonValue value) {
		return Assertions.assertInstanceOf(McpJsonObject.class, value);
	}

	private static McpJsonArray array(McpJsonValue value) {
		return Assertions.assertInstanceOf(McpJsonArray.class, value);
	}

	private static void assertCases(McpToolSchemaProfileProgram program,
			McpJsonArray cases, boolean expectedValid) {
		for (McpJsonValue value : cases.values()) {
			McpJsonObject testCase = object(value);
			String description = Assertions.assertInstanceOf(McpJsonString.class,
					testCase.members().get("description")).value();
			McpSchemaValidationOutcome outcome = evaluate(program,
					testCase.members().get("instance"));
			Assertions.assertEquals(expectedValid,
					outcome instanceof McpSchemaValidationOutcome.Valid,
					description + " :: " + outcome);
			Assertions.assertFalse(
					outcome instanceof McpSchemaValidationOutcome.LimitExceeded,
					description + " :: " + outcome);
		}
	}

	private static String jsonString(String value) {
		return JSON_CODEC.toJson(
				new com.soklet.internal.mcp.protocol.McpJsonString(value));
	}

}
