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
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.List;
import java.util.Optional;

public class McpToolSchemaProfileLimitTests {
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(1_000_000, 256, 200_000, 200_000, 4_096,
					100_000, 100_000, 1_000_000));
	private static final McpSchemaCompilationLimits GENEROUS_COMPILATION_LIMITS =
			new McpSchemaCompilationLimits(10_000, 256, 100_000,
					10_000, 10_000, 4_096, 20_000, 256, 10_000,
					4_096, 4_096);
	private static final McpSchemaEvaluationLimits GENEROUS_EVALUATION_LIMITS =
			new McpSchemaEvaluationLimits(1_000_000, 100_000, 256,
					1_000, 1_000_000);

	@Test
	public void compilationLimitsAcceptTheirBoundaryAndRejectOneOver() {
		compile("{\"properties\":{\"x\":true}}",
				limits(2, 2, 10, 10, 10, 100, 100, 2));
		assertCompilationLimit("{\"properties\":{\"x\":true,\"y\":true}}",
				limits(2, 2, 10, 10, 10, 100, 100, 2),
				McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT);

		compile("{\"properties\":{\"x\":true}}",
				limits(10, 2, 10, 10, 10, 100, 100, 2));
		assertCompilationLimit(
				"{\"properties\":{\"x\":{\"properties\":{\"y\":true}}}}",
				limits(10, 2, 10, 10, 10, 100, 100, 4),
				McpSchemaCompilationException.Limit.SCHEMA_DEPTH);

		compile("{\"type\":\"object\"}",
				limits(10, 10, 1, 10, 10, 100, 100, 10));
		assertCompilationLimit("{\"type\":\"object\",\"title\":\"x\"}",
				limits(10, 10, 1, 10, 10, 100, 100, 10),
				McpSchemaCompilationException.Limit.KEYWORD_COUNT);

		compile("{\"$anchor\":\"a\"}",
				limits(10, 10, 10, 1, 10, 1, 100, 10));
		assertCompilationLimit(
				"{\"$anchor\":\"a\",\"$defs\":{\"x\":{\"$anchor\":\"b\"}}}",
				limits(10, 10, 10, 1, 10, 1, 100, 10),
				McpSchemaCompilationException.Limit.ANCHOR_COUNT);
		assertCompilationLimit("{\"$anchor\":\"aa\"}",
				limits(10, 10, 10, 10, 10, 1, 100, 10),
				McpSchemaCompilationException.Limit.ANCHOR_NAME_LENGTH);

		compile("{\"$ref\":\"#\"}",
				limits(10, 10, 10, 10, 1, 100, 1, 10));
		assertCompilationLimit("{\"$ref\":\"#a\"}",
				limits(10, 10, 10, 10, 10, 100, 1, 10),
				McpSchemaCompilationException.Limit.REFERENCE_LENGTH);
		assertCompilationLimit(
				"{\"$defs\":{\"x\":true},\"properties\":{"
						+ "\"a\":{\"$ref\":\"#/$defs/x\"},"
						+ "\"b\":{\"$ref\":\"#/$defs/x\"}}}",
				limits(10, 10, 20, 10, 1, 100, 100, 10),
				McpSchemaCompilationException.Limit.REFERENCE_COUNT);

		compile("{\"properties\":{\"x\":true}}",
				limits(10, 10, 10, 10, 10, 100, 100, 2));
		assertCompilationLimit("{\"$ref\":\"#/a/b\"}",
				limits(10, 10, 10, 10, 10, 100, 100, 1),
				McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT);
	}

	@Test
	public void collectionAndNameLimitsAcceptTheirBoundaryAndRejectOneOver() {
		McpSchemaCompilationLimits twoEntries = limits(20, 10, 100, 10, 10,
				100, 100, 20, 2, 100, 100);
		for (String schema : List.of(
				"{\"enum\":[1,2]}",
				"{\"required\":[\"a\",\"b\"]}",
				"{\"$defs\":{\"a\":true,\"b\":true}}",
				"{\"properties\":{\"a\":true,\"b\":true}}",
				"{\"allOf\":[true,true]}",
				"{\"anyOf\":[true,true]}"))
			compile(schema, twoEntries);

		for (String schema : List.of(
				"{\"enum\":[1,2,3]}",
				"{\"required\":[\"a\",\"b\",\"c\"]}",
				"{\"$defs\":{\"a\":true,\"b\":true,\"c\":true}}",
				"{\"properties\":{\"a\":true,\"b\":true,\"c\":true}}",
				"{\"allOf\":[true,true,true]}",
				"{\"anyOf\":[true,true,true]}"))
			assertCompilationLimit(schema, twoEntries,
					McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT);

		McpSchemaCompilationLimits twoCharacters = limits(20, 10, 100, 10,
				10, 100, 100, 20, 20, 2, 100);
		for (String schema : List.of(
				"{\"properties\":{\"ab\":true}}",
				"{\"required\":[\"ab\"]}",
				"{\"x-mcp-header\":\"Ab\"}"))
			compile(schema, twoCharacters);

		for (String schema : List.of(
				"{\"properties\":{\"abc\":true}}",
				"{\"required\":[\"abc\"]}",
				"{\"x-mcp-header\":\"Abc\"}"))
			assertCompilationLimit(schema, twoCharacters,
					McpSchemaCompilationException.Limit.NAME_LENGTH);
	}

	@Test
	public void pointerSegmentLengthAcceptsItsBoundaryAndRejectsOneOver() {
		McpSchemaCompilationLimits tenCharacters = limits(20, 10, 100, 10,
				10, 100, 100, 20, 20, 100, 10);
		compile("{\"$defs\":{\"abcdefghij\":true},"
				+ "\"$ref\":\"#/$defs/abcdefghij\"}", tenCharacters);
		assertCompilationLimit("{\"$defs\":{\"abcdefghijk\":true},"
				+ "\"$ref\":\"#/$defs/abcdefghijk\"}", tenCharacters,
				McpSchemaCompilationException.Limit.POINTER_SEGMENT_LENGTH);

		McpSchemaCompilationLimits oneCharacter = limits(20, 10, 100, 10,
				10, 100, 100, 20, 20, 100, 1);
		for (String reference : List.of("#/a", "#/~0", "#/~1",
				"#/%E2%98%83", "#%2Fa"))
			assertCompilationKind("{\"$ref\":\"" + reference + "\"}",
					oneCharacter,
					McpSchemaCompilationException.Kind.UNRESOLVED_REFERENCE);
		for (String reference : List.of("#/ab", "#/~0a", "#/~1a",
				"#/%E2%98%83a", "#%2Fab"))
			assertCompilationLimit("{\"$ref\":\"" + reference + "\"}",
					oneCharacter,
					McpSchemaCompilationException.Limit.POINTER_SEGMENT_LENGTH);
	}

	@Test
	public void compilationLimitConfigurationRejectsInvalidValues() {
		List<int[]> invalid = List.of(
				new int[]{0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
				new int[]{1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1},
				new int[]{1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1},
				new int[]{1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1},
				new int[]{1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1},
				new int[]{1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1},
				new int[]{1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1},
				new int[]{1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1},
				new int[]{1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1},
				new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1},
				new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0},
				new int[]{1, 257, 1, 1, 1, 1, 1, 1, 1, 1, 1});
		for (int[] values : invalid)
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> limits(values[0], values[1], values[2], values[3],
							values[4], values[5], values[6], values[7], values[8],
							values[9], values[10]));
	}

	@Test
	public void productionCompilationLimitsAndHardCeilingsAreFrozen() {
		Assertions.assertEquals(new McpSchemaCompilationLimits(
				4_096, 64, 32_768, 1_024, 4_096, 256, 4_096, 128,
				4_096, 1_024, 1_024),
				McpSchemaCompilationLimits.productionDefaults());
		int[] maximum = {65_536, 256, 524_288, 65_536, 65_536, 4_096,
				65_536, 512, 65_536, 16_384, 16_384};
		Assertions.assertEquals(limits(maximum),
				McpSchemaCompilationLimits.maximumSupported());
		for (int index = 0; index < maximum.length; ++index) {
			int[] oneOver = maximum.clone();
			oneOver[index]++;
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> limits(oneOver), "field " + index);
		}
	}

	@Test
	public void productionAndHardSchemaDepthHaveExactBoundaries() {
		McpSchemaCompilationLimits production =
				McpSchemaCompilationLimits.productionDefaults();
		compile(schemaChain(64), production);
		assertCompilationLimit(schemaChain(65), production,
				McpSchemaCompilationException.Limit.SCHEMA_DEPTH);

		McpSchemaCompilationLimits maximum =
				McpSchemaCompilationLimits.maximumSupported();
		compile(schemaChain(256), maximum);
		McpSchemaCompilationException exception = Assertions.assertThrows(
				McpSchemaCompilationException.class,
				() -> new McpToolSchemaProfileCompiler(maximum)
						.compile(schemaChain(257)));
		Assertions.assertEquals(McpSchemaCompilationException.Kind.LIMIT_EXCEEDED,
				exception.kind());
		Assertions.assertEquals(McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
				exception.limit().orElseThrow());
	}

	@Test
	public void hardEvaluationLimitsHaveExactBoundaries() {
		McpToolSchemaProfileProgram empty = compile("{}",
				GENEROUS_COMPILATION_LIMITS);
		assertValid(empty, McpJsonObject.empty(),
				new McpSchemaEvaluationLimits(1, 1, 1, 1, 1));

		McpToolSchemaProfileProgram typed = compile("{\"type\":\"string\"}",
				GENEROUS_COMPILATION_LIMITS);
		assertEvaluationLimit(typed,
				JSON_CODEC.parse("\"value\""),
				new McpSchemaEvaluationLimits(1, 10, 10, 10, 1_000),
				McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);

		McpToolSchemaProfileProgram oneReference = compile(
				"{\"$defs\":{\"x\":true},\"$ref\":\"#/$defs/x\"}",
				GENEROUS_COMPILATION_LIMITS);
		assertValid(oneReference, McpJsonObject.empty(),
				new McpSchemaEvaluationLimits(10, 1, 2, 10, 1_000));

		McpToolSchemaProfileProgram twoReferences = compile(
				"{\"$defs\":{\"x\":{\"$ref\":\"#/$defs/y\"},"
						+ "\"y\":true},\"$ref\":\"#/$defs/x\"}",
				GENEROUS_COMPILATION_LIMITS);
		assertEvaluationLimit(twoReferences, McpJsonObject.empty(),
				new McpSchemaEvaluationLimits(20, 1, 3, 10, 1_000),
				McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS);

		McpToolSchemaProfileProgram child = compile(
				"{\"properties\":{\"x\":true}}",
				GENEROUS_COMPILATION_LIMITS);
		assertEvaluationLimit(child, object("{\"x\":1}"),
				new McpSchemaEvaluationLimits(20, 10, 1, 10, 1_000),
				McpSchemaEvaluationLimit.PENDING_TASKS);
	}

	@Test
	public void objectAndPointerWorkIsChargedBeforeBoundedAllocations() {
		McpToolSchemaProfileProgram absentProperties = compile(
				"{\"properties\":{\"a\":true,\"b\":true}}",
				GENEROUS_COMPILATION_LIMITS);
		assertValid(absentProperties, McpJsonObject.empty(),
				new McpSchemaEvaluationLimits(3, 10, 10, 10, 1_000));
		assertEvaluationLimit(absentProperties, McpJsonObject.empty(),
				new McpSchemaEvaluationLimits(2, 10, 10, 10, 1_000),
				McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);

		McpToolSchemaProfileProgram additionalProperties = compile(
				"{\"additionalProperties\":true}",
				GENEROUS_COMPILATION_LIMITS);
		McpJsonObject threeMembers = object("{\"a\":1,\"b\":2,\"c\":3}");
		assertValid(additionalProperties, threeMembers,
				new McpSchemaEvaluationLimits(16, 10, 10, 10, 1_000));
		assertEvaluationLimit(additionalProperties, threeMembers,
				new McpSchemaEvaluationLimits(15, 10, 10, 10, 1_000),
				McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);

		McpToolSchemaProfileProgram arrayItems = compile(
				"{\"type\":\"array\",\"items\":true}",
				GENEROUS_COMPILATION_LIMITS);
		assertValid(arrayItems, JSON_CODEC.parse("[null]"),
				new McpSchemaEvaluationLimits(4, 10, 10, 10, 1_000));
		assertEvaluationLimit(arrayItems, JSON_CODEC.parse("[null]"),
				new McpSchemaEvaluationLimits(3, 10, 10, 10, 1_000),
				McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);

		McpToolSchemaProfileProgram manyAbsent = compile(
				propertiesSchema(1_000), GENEROUS_COMPILATION_LIMITS);
		assertEvaluationLimit(manyAbsent, McpJsonObject.empty(),
				new McpSchemaEvaluationLimits(1, 10, 10, 10, 1_000),
				McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);

		McpJsonObject manyMembers = object(objectInstance(1_000));
		assertEvaluationLimit(additionalProperties, manyMembers,
				new McpSchemaEvaluationLimits(1, 10, 10, 10, 1_000),
				McpSchemaEvaluationLimit.EVALUATION_OPERATIONS);
	}

	@Test
	public void maximumPendingDepthCompletesAndOneOverFailsTyped() {
		McpSchemaEvaluationLimits production =
				McpSchemaEvaluationLimits.productionDefaults();
		McpToolSchemaProfileProgram productionExact = compile(referenceChain(127),
				GENEROUS_COMPILATION_LIMITS);
		assertValid(productionExact, McpJsonObject.empty(), production);
		McpToolSchemaProfileProgram productionOneOver = compile(referenceChain(128),
				GENEROUS_COMPILATION_LIMITS);
		assertEvaluationLimit(productionOneOver, McpJsonObject.empty(), production,
				McpSchemaEvaluationLimit.PENDING_TASKS);

		McpToolSchemaProfileProgram exact = compile(referenceChain(255),
				GENEROUS_COMPILATION_LIMITS);
		assertValid(exact, McpJsonObject.empty(),
				new McpSchemaEvaluationLimits(1_000, 255, 256, 10, 1_000));

		McpToolSchemaProfileProgram oneOver = compile(referenceChain(256),
				GENEROUS_COMPILATION_LIMITS);
		assertEvaluationLimit(oneOver, McpJsonObject.empty(),
				new McpSchemaEvaluationLimits(1_000, 256, 256, 10, 1_000),
				McpSchemaEvaluationLimit.PENDING_TASKS);
	}

	@Test
	public void diagnosticBudgetsTruncateWithoutChangingInvalidity() {
		McpToolSchemaProfileProgram program = compile(
				"{\"type\":\"object\",\"required\":[\"a\",\"b\"]}",
				GENEROUS_COMPILATION_LIMITS);
		McpSchemaValidationOutcome.Invalid countLimited =
				Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class,
						evaluate(program, McpJsonObject.empty(),
								new McpSchemaEvaluationLimits(100, 10, 10, 1,
										10_000)));
		Assertions.assertEquals(1, countLimited.diagnostics().size());
		Assertions.assertTrue(countLimited.diagnosticsTruncated());

		McpToolSchemaProfileProgram typed = compile("{\"type\":\"string\"}",
				GENEROUS_COMPILATION_LIMITS);
		McpSchemaValidationOutcome.Invalid baseline =
				Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class,
						evaluate(typed, McpJsonObject.empty(),
								GENEROUS_EVALUATION_LIMITS));
		int exactBytes = baseline.diagnostics().get(0).utf8ByteCount();

		McpSchemaValidationOutcome.Invalid exact =
				Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class,
						evaluate(typed, McpJsonObject.empty(),
								new McpSchemaEvaluationLimits(100, 10, 10, 1,
										exactBytes)));
		Assertions.assertEquals(1, exact.diagnostics().size());
		Assertions.assertFalse(exact.diagnosticsTruncated());

		McpSchemaValidationOutcome.Invalid oneByteShort =
				Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class,
						evaluate(typed, McpJsonObject.empty(),
								new McpSchemaEvaluationLimits(100, 10, 10, 1,
										exactBytes - 1)));
		Assertions.assertTrue(oneByteShort.diagnostics().isEmpty());
		Assertions.assertTrue(oneByteShort.diagnosticsTruncated());
	}

	@Test
	public void diagnosticTruncationSkipsLaterDiagnosticConstruction() {
		List<String> poisonPointer = new AbstractList<>() {
			@Override
			public String get(int index) {
				throw new AssertionError("A truncated diagnostic was inspected.");
			}

			@Override
			public int size() {
				throw new AssertionError("A truncated diagnostic was inspected.");
			}
		};
		McpSchemaEvaluationContext countLimited = new McpSchemaEvaluationContext(
				new McpSchemaEvaluationLimits(10, 10, 10, 1, 10_000));
		addFalseSchemaDiagnostic(countLimited, List.of());
		Assertions.assertDoesNotThrow(
				() -> addFalseSchemaDiagnostic(countLimited, poisonPointer));
		Assertions.assertTrue(countLimited.diagnosticsTruncated());

		McpSchemaEvaluationContext byteLimited = new McpSchemaEvaluationContext(
				new McpSchemaEvaluationLimits(10, 10, 10, 10, 1));
		addFalseSchemaDiagnostic(byteLimited, List.of());
		Assertions.assertTrue(byteLimited.diagnostics().isEmpty());
		Assertions.assertTrue(byteLimited.diagnosticsTruncated());
		Assertions.assertDoesNotThrow(
				() -> addFalseSchemaDiagnostic(byteLimited, poisonPointer));
	}

	@Test
	public void evaluationLimitConfigurationRejectsNonpositiveValues() {
		for (long[] values : List.of(
				new long[]{0, 1, 1, 1, 1},
				new long[]{1, 0, 1, 1, 1},
				new long[]{1, 1, 0, 1, 1},
				new long[]{1, 1, 1, 0, 1},
				new long[]{1, 1, 1, 1, 0}))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> new McpSchemaEvaluationLimits(values[0], values[1],
							(int) values[2], (int) values[3], (int) values[4]));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpSchemaEvaluationLimits(1, 1, 257, 1, 1));
	}

	@Test
	public void productionEvaluationLimitsAndHardCeilingsAreFrozen() {
		Assertions.assertEquals(new McpSchemaEvaluationLimits(
				1_000_000, 100_000, 128, 100, 64 * 1_024),
				McpSchemaEvaluationLimits.productionDefaults());
		long[] maximum = {10_000_000, 1_000_000, 256, 1_000,
				1_024 * 1_024};
		Assertions.assertEquals(evaluationLimits(maximum),
				McpSchemaEvaluationLimits.maximumSupported());
		for (int index = 0; index < maximum.length; ++index) {
			long[] oneOver = maximum.clone();
			oneOver[index]++;
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> evaluationLimits(oneOver), "field " + index);
		}
	}

	private static String referenceChain(int nodeCount) {
		StringBuilder schema = new StringBuilder("{\"$defs\":{");
		for (int index = 0; index < nodeCount; ++index) {
			if (index > 0)
				schema.append(',');
			schema.append("\"n").append(index).append("\":");
			if (index + 1 == nodeCount) {
				schema.append("true");
			} else {
				schema.append("{\"$ref\":\"#/$defs/n")
						.append(index + 1).append("\"}");
			}
		}
		return schema.append("},\"$ref\":\"#/$defs/n0\"}").toString();
	}

	private static McpJsonObject schemaChain(int nodeCount) {
		McpJsonValue schema = McpJsonObject.empty();
		for (int index = 1; index < nodeCount; ++index)
			schema = new McpJsonObject(java.util.Map.of("items", schema));
		return (McpJsonObject) schema;
	}

	private static McpSchemaCompilationLimits limits(int nodes, int depth,
			int keywords, int anchors, int references, int anchorNameLength,
			int referenceLength, int pointerSegments) {
		return limits(nodes, depth, keywords, anchors, references,
				anchorNameLength, referenceLength, pointerSegments, 1_000, 1_000,
				1_000);
	}

	private static McpSchemaCompilationLimits limits(int[] values) {
		return limits(values[0], values[1], values[2], values[3], values[4],
				values[5], values[6], values[7], values[8], values[9],
				values[10]);
	}

	private static McpSchemaEvaluationLimits evaluationLimits(long[] values) {
		return new McpSchemaEvaluationLimits(values[0], values[1],
				(int) values[2], (int) values[3], (int) values[4]);
	}

	private static McpSchemaCompilationLimits limits(int nodes, int depth,
			int keywords, int anchors, int references, int anchorNameLength,
			int referenceLength, int pointerSegments, int collectionEntries,
			int nameLength, int pointerSegmentLength) {
		return new McpSchemaCompilationLimits(nodes, depth, keywords, anchors,
				references, anchorNameLength, referenceLength, pointerSegments,
				collectionEntries, nameLength, pointerSegmentLength);
	}

	private static String propertiesSchema(int count) {
		StringBuilder schema = new StringBuilder("{\"properties\":{");
		for (int index = 0; index < count; ++index) {
			if (index > 0)
				schema.append(',');
			schema.append("\"p").append(index).append("\":true");
		}
		return schema.append("}}").toString();
	}

	private static String objectInstance(int count) {
		StringBuilder instance = new StringBuilder("{");
		for (int index = 0; index < count; ++index) {
			if (index > 0)
				instance.append(',');
			instance.append("\"p").append(index).append("\":null");
		}
		return instance.append('}').toString();
	}

	private static McpToolSchemaProfileProgram compile(String schema,
			McpSchemaCompilationLimits limits) {
		return compile(object(schema), limits);
	}

	private static McpToolSchemaProfileProgram compile(McpJsonObject schema,
			McpSchemaCompilationLimits limits) {
		return new McpToolSchemaProfileCompiler(limits).compile(schema);
	}

	private static void assertCompilationLimit(String schema,
			McpSchemaCompilationLimits limits,
			McpSchemaCompilationException.Limit expected) {
		assertCompilationLimit(object(schema), limits, expected);
	}

	private static void assertCompilationLimit(McpJsonObject schema,
			McpSchemaCompilationLimits limits,
			McpSchemaCompilationException.Limit expected) {
		McpSchemaCompilationException exception = Assertions.assertThrows(
				McpSchemaCompilationException.class, () -> compile(schema, limits));
		Assertions.assertEquals(McpSchemaCompilationException.Kind.LIMIT_EXCEEDED,
				exception.kind());
		Assertions.assertEquals(expected, exception.limit().orElseThrow());
	}

	private static void assertCompilationKind(String schema,
			McpSchemaCompilationLimits limits,
			McpSchemaCompilationException.Kind expected) {
		McpSchemaCompilationException exception = Assertions.assertThrows(
				McpSchemaCompilationException.class, () -> compile(schema, limits));
		Assertions.assertEquals(expected, exception.kind());
	}

	private static void addFalseSchemaDiagnostic(
			McpSchemaEvaluationContext context, List<String> instancePointer) {
		context.addDiagnostic(McpSchemaDiagnostic.Code.FALSE_SCHEMA,
				McpSchemaLocation.root(), Optional.empty(), Optional.empty(),
				instancePointer, "The instance is rejected by a false schema.");
	}

	private static McpSchemaValidationOutcome evaluate(
			McpToolSchemaProfileProgram program, McpJsonValue instance,
			McpSchemaEvaluationLimits limits) {
		return new McpToolSchemaProfileEvaluator().evaluate(program, instance,
				limits);
	}

	private static void assertValid(McpToolSchemaProfileProgram program,
			McpJsonValue instance, McpSchemaEvaluationLimits limits) {
		Assertions.assertInstanceOf(McpSchemaValidationOutcome.Valid.class,
				evaluate(program, instance, limits));
	}

	private static void assertEvaluationLimit(
			McpToolSchemaProfileProgram program, McpJsonValue instance,
			McpSchemaEvaluationLimits limits,
			McpSchemaEvaluationLimit expected) {
		McpSchemaValidationOutcome.LimitExceeded outcome =
				Assertions.assertInstanceOf(
						McpSchemaValidationOutcome.LimitExceeded.class,
						evaluate(program, instance, limits));
		Assertions.assertEquals(expected, outcome.limit());
	}

	private static McpJsonObject object(String json) {
		return Assertions.assertInstanceOf(McpJsonObject.class,
				JSON_CODEC.parse(json));
	}
}
