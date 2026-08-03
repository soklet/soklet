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
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McpSchemaPrimitiveEvaluatorTests {
	private static final URI RETRIEVAL_URI =
			URI.create("https://schemas.example.test/primitive.json");
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(1_000_000, 256, 200_000, 200_000, 10_000,
					100_000, 100_000, 1_000_000));
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			new McpSchemaCompilationLimits(10, 1_000, 256, 10_000,
					1_000, 2_000, 1_000, 1_000, 20_000, 1_000, 1_000);
	private static final McpSchemaEvaluationLimits GENEROUS_EVALUATION_LIMITS =
			new McpSchemaEvaluationLimits(1_000_000, 10_000, 100_000, 1_000,
					1_000, 1_000_000);

	@Test
	public void invalidTypeSyntaxIsRejectedDuringProgramCompilation() {
		for (String invalidType : List.of(
				"null",
				"\"unknown\"",
				"[]",
				"[\"string\", 1]",
				"[\"string\", \"string\"]")) {
			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compileProgram("{\"type\":" + invalidType + "}"));
			Assertions.assertEquals(
					McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					exception.kind(), invalidType);
			Assertions.assertEquals("type", exception.keyword().orElseThrow());
			Assertions.assertEquals("",
					exception.location().orElseThrow().jsonPointer());
		}
	}

	@Test
	public void recognizedUnimplementedKeywordsFailClosedButExtensionsDoNot() {
		Map<String, String> unsupportedKeywordValues = new LinkedHashMap<>();
		unsupportedKeywordValues.put("$dynamicRef", "\"#\"");
		unsupportedKeywordValues.put("dependentRequired", "{}");
		unsupportedKeywordValues.put("maxContains", "1");
		unsupportedKeywordValues.put("minContains", "1");
		unsupportedKeywordValues.put("maxItems", "1");
		unsupportedKeywordValues.put("minItems", "1");
		unsupportedKeywordValues.put("uniqueItems", "true");
		unsupportedKeywordValues.put("maxProperties", "1");
		unsupportedKeywordValues.put("minProperties", "1");
		unsupportedKeywordValues.put("maximum", "1");
		unsupportedKeywordValues.put("exclusiveMaximum", "1");
		unsupportedKeywordValues.put("minimum", "1");
		unsupportedKeywordValues.put("exclusiveMinimum", "1");
		unsupportedKeywordValues.put("multipleOf", "1");
		unsupportedKeywordValues.put("maxLength", "1");
		unsupportedKeywordValues.put("minLength", "1");
		unsupportedKeywordValues.put("pattern", "\"x\"");
		unsupportedKeywordValues.put("additionalProperties", "true");
		unsupportedKeywordValues.put("anyOf", "[true]");
		unsupportedKeywordValues.put("contains", "true");
		unsupportedKeywordValues.put("dependentSchemas", "{\"x\":true}");
		unsupportedKeywordValues.put("else", "true");
		unsupportedKeywordValues.put("if", "true");
		unsupportedKeywordValues.put("items", "true");
		unsupportedKeywordValues.put("not", "true");
		unsupportedKeywordValues.put("oneOf", "[true]");
		unsupportedKeywordValues.put("patternProperties", "{\"x\":true}");
		unsupportedKeywordValues.put("prefixItems", "[true]");
		unsupportedKeywordValues.put("propertyNames", "true");
		unsupportedKeywordValues.put("then", "true");
		unsupportedKeywordValues.put("unevaluatedItems", "true");
		unsupportedKeywordValues.put("unevaluatedProperties", "true");

		for (Map.Entry<String, String> entry : unsupportedKeywordValues.entrySet()) {
			String schema = "{\"" + entry.getKey() + "\":"
					+ entry.getValue() + "}";
			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compileProgram(schema));
			Assertions.assertEquals(
					McpSchemaCompilationException.Kind.UNSUPPORTED_KEYWORD,
					exception.kind(), schema);
			Assertions.assertEquals(entry.getKey(),
					exception.keyword().orElseThrow(), schema);
		}

		assertValid(evaluate("{\"x-example\":{\"minimum\":0}}", "false"));
		assertValid(evaluate("""
				{"title":"annotation","description":"annotation","default":0,
				 "deprecated":true,"readOnly":true,"writeOnly":true,"examples":[],
				 "format":"custom","contentEncoding":"custom",
				 "contentMediaType":"application/example","contentSchema":true,
				 "$comment":"comment","$defs":{"unused":false}}
				""", "false"));
	}

	@Test
	public void unsupportedKeywordDiagnosticsDoNotDependOnMemberOrder() {
		for (String schema : List.of(
				"{\"properties\":{},\"minimum\":0}",
				"{\"minimum\":0,\"properties\":{}}")) {
			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compileProgram(schema));
			Assertions.assertEquals("minimum",
					exception.keyword().orElseThrow());
		}
	}

	@Test
	public void validTypeUnionAndMathematicalIntegerSemanticsAreExact() {
		assertValid(evaluate("{\"type\":[\"null\",\"string\"]}", "null"));
		assertValid(evaluate("{\"type\":[\"null\",\"string\"]}",
				"\"value\""));
		assertInvalid(evaluate("{\"type\":[\"null\",\"string\"]}", "false"));

		for (String integer : List.of("1", "1.0", "1e3", "-2.000", "0e-100"))
			assertValid(evaluate("{\"type\":\"integer\"}", integer));
		assertInvalid(evaluate("{\"type\":\"integer\"}", "1.5"));
		assertValid(evaluate("{\"type\":\"number\"}", "1.5"));
	}

	@Test
	public void mathematicalIntegerCheckHandlesMinimumBigDecimalScale() {
		CompiledPrimitiveSchema compiled = compileProgram("{\"type\":\"integer\"}");
		McpJsonNumber extremeInteger = new McpJsonNumber(
				new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE));
		assertValid(new McpSchemaEvaluator().evaluate(compiled.program(),
				compiled.rootNodeId(), extremeInteger, GENEROUS_EVALUATION_LIMITS));
	}

	@Test
	public void enumMustBeAnArrayButAnEmptyArrayIsValidAndAlwaysFalse() {
		McpSchemaCompilationException malformed = Assertions.assertThrows(
				McpSchemaCompilationException.class,
				() -> compileProgram("{\"enum\":true}"));
		Assertions.assertEquals(
				McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
				malformed.kind());
		Assertions.assertEquals("enum", malformed.keyword().orElseThrow());

		for (String instance : List.of("null", "false", "0", "\"\"", "[]", "{}")) {
			McpSchemaValidationOutcome.Invalid invalid = assertInvalid(
					evaluate("{\"enum\":[]}", instance));
			Assertions.assertEquals(1, invalid.diagnostics().size());
			Assertions.assertEquals(McpSchemaDiagnostic.Code.ENUM_MISMATCH,
					invalid.diagnostics().get(0).code());
		}
	}

	@Test
	public void booleanSchemasHaveOneDeterministicNodeEntryOperation() {
		McpSchemaValidationOutcome.Valid valid = assertValid(evaluate("true", "null"));
		Assertions.assertEquals(1, valid.evaluationOperations());

		McpSchemaValidationOutcome.Invalid invalid = assertInvalid(
				evaluate("false", "null"));
		Assertions.assertEquals(1, invalid.evaluationOperations());
		Assertions.assertEquals(McpSchemaDiagnostic.Code.FALSE_SCHEMA,
				invalid.diagnostics().get(0).code());
	}

	@Test
	public void propertiesAppliesOnlyPresentObjectMembers() {
		String schema = """
				{"properties":{"name":{"type":"string"},"forbidden":false}}
				""";
		assertValid(evaluate(schema, "{}"));
		assertValid(evaluate(schema, "[]"));
		assertValid(evaluate(schema, "{\"name\":\"value\"}"));
		assertInvalid(evaluate(schema, "{\"name\":1}"));
		McpSchemaValidationOutcome.Invalid forbidden = assertInvalid(
				evaluate(schema, "{\"forbidden\":null}"));
		Assertions.assertEquals(List.of("forbidden"),
				forbidden.diagnostics().get(0).instancePointerSegments());
		Assertions.assertEquals("/properties/forbidden",
				forbidden.diagnostics().get(0).schemaLocation().jsonPointer());
	}

	@Test
	public void propertiesUsesLexicalSchemaOrderIndependentOfInstanceOrder() {
		String schema = "{\"properties\":{\"z\":false,\"a\":false}}";
		McpSchemaValidationOutcome.Invalid invalid = assertInvalid(evaluate(
				schema, "{\"z\":0,\"a\":0}",
				limits(100, 1, 100_000)));

		Assertions.assertTrue(invalid.diagnosticsTruncated());
		Assertions.assertEquals(List.of("a"),
				invalid.diagnostics().get(0).instancePointerSegments());
	}

	@Test
	public void propertiesHandlesExactAndAdversarialMemberNames() {
		for (String name : List.of("a/b", "a~b", "", "__proto__",
				"constructor", "toString", "nul\0name")) {
			String encodedName = jsonString(name);
			McpSchemaValidationOutcome.Invalid invalid = assertInvalid(evaluate(
					"{\"properties\":{" + encodedName + ":false}}",
					"{" + encodedName + ":true}"));
			Assertions.assertEquals(List.of(name),
					invalid.diagnostics().get(0).instancePointerSegments());
		}
	}

	@Test
	public void propertiesWorkIsReservedBeforeMemberTraversal() {
		String schema = """
				{"properties":{"a":false,"b":{"type":"string"}}}
				""";
		McpSchemaValidationOutcome.Invalid exact = assertInvalid(evaluate(schema,
				"{\"a\":0,\"b\":1}", limits(7, 100, 100_000)));
		Assertions.assertEquals(7, exact.evaluationOperations());

		McpSchemaValidationOutcome.LimitExceeded oneUnder = assertLimitExceeded(
				evaluate(schema, "{\"a\":0,\"b\":1}",
						limits(6, 100, 100_000)));
		Assertions.assertEquals(6, oneUnder.evaluationOperations());
	}

	@Test
	public void requiredSyntaxAndObjectMembershipSemanticsAreExact() {
		for (String invalidRequired : List.of(
				"null", "{}", "[1]", "[\"a\",\"a\"]")) {
			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compileProgram("{\"required\":"
							+ invalidRequired + "}"));
			Assertions.assertEquals(
					McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					exception.kind());
			Assertions.assertEquals("required",
					exception.keyword().orElseThrow());
		}

		assertValid(evaluate("{\"required\":[]}", "{}"));
		assertValid(evaluate("{\"required\":[\"value\"]}",
				"{\"value\":null}"));
		assertValid(evaluate("{\"required\":[\"value\"]}", "[]"));
		McpSchemaValidationOutcome.Invalid missing = assertInvalid(evaluate(
				"{\"required\":[\"value\"]}", "{}"));
		Assertions.assertEquals(
				McpSchemaDiagnostic.Code.REQUIRED_PROPERTY_MISSING,
				missing.diagnostics().get(0).code());
		Assertions.assertEquals(List.of(),
				missing.diagnostics().get(0).instancePointerSegments());

		McpSchemaValidationOutcome.Invalid multiple = assertInvalid(evaluate(
				"{\"required\":[\"z\",\"a\"]}", "{}"));
		Assertions.assertEquals(List.of("a", "z"), multiple.diagnostics().stream()
				.map(diagnostic -> diagnostic.missingPropertyName().orElseThrow())
				.toList());
		Assertions.assertTrue(multiple.diagnostics().stream()
				.allMatch(diagnostic -> diagnostic.instancePointerSegments().isEmpty()));
	}

	@Test
	public void requiredHandlesExactAndAdversarialMemberNames() {
		for (String name : List.of("a/b", "a~b", "", "__proto__",
				"constructor", "toString", "nul\0name")) {
			String encodedName = jsonString(name);
			assertValid(evaluate("{\"required\":[" + encodedName + "]}",
					"{" + encodedName + ":null}"));
			assertInvalid(evaluate("{\"required\":[" + encodedName + "]}",
					"{}"));
		}
	}

	@Test
	public void requiredWorkIsReservedBeforeMembershipChecks() {
		String schema = "{\"required\":[\"a\",\"b\"]}";
		McpSchemaValidationOutcome.Invalid exact = assertInvalid(evaluate(schema,
				"{}", limits(4, 100, 100_000)));
		Assertions.assertEquals(4, exact.evaluationOperations());

		McpSchemaValidationOutcome.LimitExceeded oneUnder = assertLimitExceeded(
				evaluate(schema, "{}", limits(3, 100, 100_000)));
		Assertions.assertEquals(2, oneUnder.evaluationOperations());
	}

	@Test
	public void allOfCombinesEveryChildAndAdjacentAssertionConjunctively() {
		String schema = """
				{
				  "type":"object",
				  "allOf":[
				    {"properties":{"a":{"type":"integer"}},"required":["a"]},
				    {"allOf":[{"properties":{"b":{"type":"string"}}},
				              {"required":["b"]}]}
				  ]
				}
				""";
		assertValid(evaluate(schema, "{\"a\":1.0,\"b\":\"value\"}"));
		assertInvalid(evaluate(schema, "{\"a\":1}"));
		assertInvalid(evaluate(schema, "{\"a\":\"wrong\",\"b\":\"value\"}"));
		assertInvalid(evaluate(schema, "null"));
	}

	@Test
	public void allOfReservesChildWidthAndEvaluatesEveryConjunct() {
		String schema = "{\"allOf\":[true,false]}";
		McpSchemaValidationOutcome.Invalid exact = assertInvalid(evaluate(schema,
				"null", limits(6, 100, 100_000)));
		Assertions.assertEquals(6, exact.evaluationOperations());
		Assertions.assertEquals(1, exact.diagnostics().size());

		McpSchemaValidationOutcome.LimitExceeded oneUnder = assertLimitExceeded(
				evaluate(schema, "null", limits(5, 100, 100_000)));
		Assertions.assertEquals(5, oneUnder.evaluationOperations());
	}

	@Test
	public void evaluationOperationLimitSucceedsAtTheExactBoundaryAndStopsOneOver() {
		McpSchemaEvaluationLimits exactLimits = limits(2, 100, 100_000);
		McpSchemaValidationOutcome.Invalid exact = assertInvalid(evaluate(
				"{\"type\":\"string\"}", "1", exactLimits));
		Assertions.assertEquals(2, exact.evaluationOperations());

		McpSchemaEvaluationLimits oneOverLimits = limits(1, 100, 100_000);
		McpSchemaValidationOutcome.LimitExceeded oneOver = assertLimitExceeded(
				evaluate("{\"type\":\"string\"}", "1", oneOverLimits));
		Assertions.assertEquals(McpSchemaEvaluationLimit.EVALUATION_OPERATIONS,
				oneOver.limit());
		Assertions.assertEquals(1, oneOver.evaluationOperations());
	}

	@Test
	public void equalityChargesContainerChildrenBeforeQueueingThem() {
		String schema = "{\"const\":[1,2]}";
		McpSchemaValidationOutcome.Valid exact = assertValid(evaluate(schema,
				"[1.0,2.0]", limits(5, 100, 100_000)));
		Assertions.assertEquals(5, exact.evaluationOperations());

		McpSchemaValidationOutcome.LimitExceeded oneOver = assertLimitExceeded(
				evaluate(schema, "[1.0,2.0]", limits(4, 100, 100_000)));
		Assertions.assertEquals(3, oneOver.evaluationOperations());
		Assertions.assertEquals(McpSchemaEvaluationLimit.EVALUATION_OPERATIONS,
				oneOver.limit());
	}

	@Test
	public void equalityReservesWideObjectWorkBeforeAllocatingTraversalState() {
		String object = wideObject(1_000);
		String reverse = wideObjectInReverse(1_000);
		String schema = "{\"const\":" + object + "}";
		McpSchemaValidationOutcome.Valid exact = assertValid(evaluate(schema,
				reverse, limits(1_003, 100, 100_000)));
		Assertions.assertEquals(1_003, exact.evaluationOperations());

		McpSchemaValidationOutcome.LimitExceeded oneUnder = assertLimitExceeded(
				evaluate(schema, reverse, limits(1_002, 100, 100_000)));
		Assertions.assertEquals(3, oneUnder.evaluationOperations());
	}

	@Test
	public void equalityAccountingDoesNotDependOnContainerObjectIdentity() {
		CompiledPrimitiveSchema compiled = compileProgram("{\"const\":{\"a\":1}}");
		McpJsonValue constant = compiled.program().node(compiled.rootNodeId())
				.constant().orElseThrow();
		McpJsonValue copy = JSON_CODEC.parse("{\"a\":1.0}");
		McpSchemaEvaluator evaluator = new McpSchemaEvaluator();

		McpSchemaValidationOutcome.Valid alias = assertValid(evaluator.evaluate(
				compiled.program(), compiled.rootNodeId(), constant,
				limits(4, 100, 100_000)));
		McpSchemaValidationOutcome.Valid independent = assertValid(evaluator.evaluate(
				compiled.program(), compiled.rootNodeId(), copy,
				limits(4, 100, 100_000)));
		Assertions.assertEquals(4, alias.evaluationOperations());
		Assertions.assertEquals(alias.evaluationOperations(),
				independent.evaluationOperations());

		Assertions.assertInstanceOf(McpSchemaValidationOutcome.LimitExceeded.class,
				evaluator.evaluate(compiled.program(), compiled.rootNodeId(), constant,
						limits(3, 100, 100_000)));
		Assertions.assertInstanceOf(McpSchemaValidationOutcome.LimitExceeded.class,
				evaluator.evaluate(compiled.program(), compiled.rootNodeId(), copy,
						limits(3, 100, 100_000)));
	}

	@Test
	public void diagnosticCountLimitTruncatesWithoutChangingInvalidToLimitExceeded() {
		String schema = "{\"type\":\"string\",\"const\":false}";
		McpSchemaValidationOutcome.Invalid complete = assertInvalid(
				evaluate(schema, "1"));
		Assertions.assertEquals(2, complete.diagnostics().size());
		Assertions.assertFalse(complete.diagnosticsTruncated());

		McpSchemaValidationOutcome.Invalid truncated = assertInvalid(evaluate(
				schema, "1", limits(1_000, 1, 100_000)));
		Assertions.assertEquals(1, truncated.diagnostics().size());
		Assertions.assertTrue(truncated.diagnosticsTruncated());
		Assertions.assertEquals(McpSchemaDiagnostic.Code.TYPE_MISMATCH,
				truncated.diagnostics().get(0).code());
	}

	@Test
	public void diagnosticUtf8ByteLimitIsExactAndTruncatesIndependently() {
		String schema = "{\"type\":\"string\"}";
		McpSchemaValidationOutcome.Invalid baseline = assertInvalid(
				evaluate(schema, "false"));
		int exactByteCount = baseline.diagnostics().get(0).utf8ByteCount();

		McpSchemaValidationOutcome.Invalid exact = assertInvalid(evaluate(schema,
				"false", limits(1_000, 10, exactByteCount)));
		Assertions.assertEquals(1, exact.diagnostics().size());
		Assertions.assertFalse(exact.diagnosticsTruncated());

		McpSchemaValidationOutcome.Invalid oneByteShort = assertInvalid(evaluate(
				schema, "false", limits(1_000, 10, exactByteCount - 1)));
		Assertions.assertTrue(oneByteShort.diagnostics().isEmpty());
		Assertions.assertTrue(oneByteShort.diagnosticsTruncated());
	}

	@Test
	public void exhaustedDiagnosticCapsSkipAllFurtherSizingWork() {
		McpSchemaDiagnostic normal = diagnostic("normal");
		McpSchemaDiagnostic malformedIfSized = diagnostic("\uD800");

		McpSchemaEvaluationContext countLimited = new McpSchemaEvaluationContext(
				new McpSchemaEvaluationLimits(100, 100, 100, 10, 1, 10_000));
		countLimited.addDiagnostic(normal);
		Assertions.assertDoesNotThrow(
				() -> countLimited.addDiagnostic(malformedIfSized));
		Assertions.assertTrue(countLimited.diagnosticsTruncated());

		McpSchemaEvaluationContext byteLimited = new McpSchemaEvaluationContext(
				new McpSchemaEvaluationLimits(100, 100, 100, 10, 10, 1));
		byteLimited.addDiagnostic(normal);
		Assertions.assertDoesNotThrow(
				() -> byteLimited.addDiagnostic(malformedIfSized));
		Assertions.assertTrue(byteLimited.diagnosticsTruncated());
	}

	@Test
	public void nestedConstUsesSemanticJsonEquality() {
		String schema = """
				{"const":{"outer":[{"n":1,"s":"x"},[true,null]]}}
				""";
		assertValid(evaluate(schema, """
				{"outer":[{"s":"x","n":1.0},[true,null]]}
				"""));
		assertInvalid(evaluate(schema, """
				{"outer":[{"s":"x","n":1.0},[false,null]]}
				"""));
	}

	@Test
	public void nestedEnumUsesSemanticJsonEquality() {
		String schema = """
				{"enum":[{"a":[1,{"b":"x"}]},[false,null]]}
				""";
		assertValid(evaluate(schema, "{\"a\":[1.0,{\"b\":\"x\"}]}"));
		assertValid(evaluate(schema, "[false,null]"));
		assertInvalid(evaluate(schema, "{\"a\":[1,{\"b\":\"y\"}]}"));
	}

	@Test
	public void numericallyEquivalentScalesAndExponentsCompareEqual() {
		for (String equivalent : List.of("1", "1.0", "1.0000", "1e0", "100e-2"))
			assertValid(evaluate("{\"const\":1}", equivalent));

		for (String equivalent : List.of("1000", "1000.000", "1e3", "10e2"))
			assertValid(evaluate("{\"enum\":[1e3]}", equivalent));

		for (String equivalentZero : List.of("0", "-0", "0.000", "0e100"))
			assertValid(evaluate("{\"const\":0}", equivalentZero));
	}

	@Test
	public void booleansNeverCompareEqualToNumbers() {
		assertInvalid(evaluate("{\"const\":true}", "1"));
		assertInvalid(evaluate("{\"const\":1}", "true"));
		assertInvalid(evaluate("{\"enum\":[0,1]}", "false"));
	}

	@Test
	public void objectMemberOrderDoesNotAffectEquality() {
		assertValid(evaluate("{\"const\":{\"a\":1,\"b\":[2,3]}}",
				"{\"b\":[2.0,3e0],\"a\":1.00}"));
	}

	@Test
	public void arrayElementOrderDoesAffectEquality() {
		assertValid(evaluate("{\"const\":[1,2,3]}", "[1.0,2.0,3.0]"));
		assertInvalid(evaluate("{\"const\":[1,2,3]}", "[3,2,1]"));
	}

	@Test
	public void stringsContainingNulCompareExactly() {
		String withNul = jsonString("a\0b");
		assertValid(evaluate("{\"const\":" + withNul + "}", withNul));
		assertInvalid(evaluate("{\"const\":" + withNul + "}",
				jsonString("ab")));
	}

	@Test
	public void visuallyEquivalentUnicodeIsNotNormalized() {
		String composed = jsonString("é");
		String decomposed = jsonString("e\u0301");

		assertValid(evaluate("{\"const\":" + composed + "}", composed));
		assertValid(evaluate("{\"const\":" + decomposed + "}", decomposed));
		assertInvalid(evaluate("{\"const\":" + composed + "}", decomposed));
		assertInvalid(evaluate("{\"const\":" + decomposed + "}", composed));
	}

	private static CompiledPrimitiveSchema compileProgram(String schema) {
		McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(
				COMPILATION_LIMITS).compile(List.of(new McpSchemaDocument(
						RETRIEVAL_URI, JSON_CODEC.parse(schema))));
		McpSchemaValidationProgram program =
				new McpSchemaValidationProgramCompiler().compile(graph);
		return new CompiledPrimitiveSchema(program,
				graph.documentRoots().get(RETRIEVAL_URI));
	}

	private static McpSchemaValidationOutcome evaluate(String schema,
			String instance) {
		return evaluate(schema, instance, GENEROUS_EVALUATION_LIMITS);
	}

	private static McpSchemaValidationOutcome evaluate(String schema,
			String instance, McpSchemaEvaluationLimits limits) {
		CompiledPrimitiveSchema compiled = compileProgram(schema);
		return new McpSchemaEvaluator().evaluate(compiled.program(),
				compiled.rootNodeId(), JSON_CODEC.parse(instance), limits);
	}

	private static McpSchemaValidationOutcome.Valid assertValid(
			McpSchemaValidationOutcome outcome) {
		Assertions.assertInstanceOf(McpSchemaValidationOutcome.Valid.class, outcome);
		return (McpSchemaValidationOutcome.Valid) outcome;
	}

	private static McpSchemaValidationOutcome.Invalid assertInvalid(
			McpSchemaValidationOutcome outcome) {
		Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class, outcome);
		return (McpSchemaValidationOutcome.Invalid) outcome;
	}

	private static McpSchemaValidationOutcome.LimitExceeded assertLimitExceeded(
			McpSchemaValidationOutcome outcome) {
		Assertions.assertInstanceOf(
				McpSchemaValidationOutcome.LimitExceeded.class, outcome);
		return (McpSchemaValidationOutcome.LimitExceeded) outcome;
	}

	private static McpSchemaEvaluationLimits limits(long operationCount,
			int diagnosticCount, int diagnosticBytes) {
		return new McpSchemaEvaluationLimits(operationCount, 10_000, 100_000,
				1_000, diagnosticCount, diagnosticBytes);
	}

	private static String jsonString(String value) {
		return JSON_CODEC.toJson(new McpJsonString(value));
	}

	private static McpSchemaDiagnostic diagnostic(String message) {
		return new McpSchemaDiagnostic(McpSchemaDiagnostic.Code.TYPE_MISMATCH,
				McpSchemaLocation.root(RETRIEVAL_URI), Optional.of("type"),
				Optional.empty(), List.of(), message);
	}

	private static String wideObject(int propertyCount) {
		StringBuilder object = new StringBuilder(propertyCount * 12).append('{');
		for (int index = 0; index < propertyCount; ++index) {
			if (index > 0)
				object.append(',');
			object.append('"').append('p').append(index).append("\":").append(index);
		}
		return object.append('}').toString();
	}

	private static String wideObjectInReverse(int propertyCount) {
		StringBuilder object = new StringBuilder(propertyCount * 12).append('{');
		for (int index = propertyCount - 1; index >= 0; --index) {
			if (index < propertyCount - 1)
				object.append(',');
			object.append('"').append('p').append(index).append("\":").append(index);
		}
		return object.append('}').toString();
	}

	private record CompiledPrimitiveSchema(McpSchemaValidationProgram program,
			McpSchemaNodeId rootNodeId) {
	}
}
