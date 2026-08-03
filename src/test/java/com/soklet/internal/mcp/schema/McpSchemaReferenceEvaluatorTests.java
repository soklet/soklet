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
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

public class McpSchemaReferenceEvaluatorTests {
	private static final URI ROOT_URI =
			URI.create("https://schemas.example.test/reference-root.json");
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(1_000_000, 256, 200_000, 200_000, 10_000,
					100_000, 100_000, 1_000_000));
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			new McpSchemaCompilationLimits(32, 10_000, 256, 100_000,
					10_000, 20_000, 10_000, 10_000, 20_000, 1_000,
					10_000);
	private static final McpSchemaEvaluationLimits GENEROUS_LIMITS =
			new McpSchemaEvaluationLimits(100_000, 1_000, 100_000, 256,
					1_000, 100_000);

	@Test
	public void definitionsAreInertUntilAStaticReferenceAppliesThem() {
		CompiledSchema compiled = compile(document(ROOT_URI, """
				{
				  "$defs":{
				    "unused":false,
				    "used":{"type":"integer"}
				  },
				  "$ref":"#/$defs/used"
				}
				"""));

		assertValid(evaluate(compiled, "1.0", GENEROUS_LIMITS));
		assertInvalid(evaluate(compiled, "\"not an integer\"",
				GENEROUS_LIMITS));
	}

	@Test
	public void referenceResultsRemainConjunctiveWithSiblingKeywords() {
		CompiledSchema compiled = compile(document(ROOT_URI, """
				{
				  "$defs":{"string":{"type":"string"}},
				  "$ref":"#/$defs/string",
				  "const":"expected"
				}
				"""));

		assertValid(evaluate(compiled, "\"expected\"", GENEROUS_LIMITS));
		assertInvalid(evaluate(compiled, "\"other\"", GENEROUS_LIMITS));
		McpSchemaValidationOutcome.Invalid both = assertInvalid(evaluate(
				compiled, "1", GENEROUS_LIMITS));
		Assertions.assertEquals(2, both.diagnostics().size());
	}

	@Test
	public void localAnchorsAndClosedExternalAliasesEvaluateWithoutFetching() {
		URI externalRetrieval = URI.create(
				"https://schemas.example.test/external-retrieval.json");
		URI externalCanonical = URI.create(
				"https://schemas.example.test/external-canonical.json");
		CompiledSchema anchor = compile(document(ROOT_URI, """
				{"$defs":{"target":{"$anchor":"kind","type":"string"}},
				 "$ref":"#kind"}
				"""));
		assertValid(evaluate(anchor, "\"value\"", GENEROUS_LIMITS));
		assertInvalid(evaluate(anchor, "1", GENEROUS_LIMITS));

		CompiledSchema external = compile(
				document(ROOT_URI, "{\"$ref\":\"external-retrieval.json\"}"),
				document(externalRetrieval, """
						{"$id":"https://schemas.example.test/external-canonical.json",
						 "type":"number"}
						"""));
		assertValid(evaluate(external, "1", GENEROUS_LIMITS));
		assertInvalid(evaluate(external, "\"value\"", GENEROUS_LIMITS));
		Assertions.assertTrue(external.graph().resource(externalCanonical).isPresent());
	}

	@Test
	public void canonicalEmbeddedResourcePointersEvaluate() {
		CompiledSchema compiled = compile(document(ROOT_URI, """
				{
				  "$defs":{
				    "embedded":{
				      "$id":"embedded.json",
				      "properties":{"value":{"type":"boolean"}}
				    }
				  },
				  "$ref":"embedded.json#/properties/value"
				}
				"""));

		assertValid(evaluate(compiled, "true", GENEROUS_LIMITS));
		assertInvalid(evaluate(compiled, "1", GENEROUS_LIMITS));
	}

	@Test
	public void referenceTraversalLimitAcceptsTheExactChainAndStopsOneOver() {
		CompiledSchema compiled = compile(document(ROOT_URI, """
				{
				  "$defs":{
				    "first":{"$ref":"#/$defs/second"},
				    "second":false
				  },
				  "$ref":"#/$defs/first"
				}
				"""));

		McpSchemaValidationOutcome.Invalid exact = assertInvalid(evaluate(
				compiled, "null", limits(100, 2)));
		Assertions.assertEquals(5, exact.evaluationOperations());

		McpSchemaValidationOutcome.LimitExceeded oneOver = assertLimitExceeded(
				evaluate(compiled, "null", limits(100, 1)));
		Assertions.assertEquals(McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS,
				oneOver.limit());
		Assertions.assertEquals(4, oneOver.evaluationOperations());
	}

	@Test
	public void selfAndTwoNodeCyclesStopOnReferenceBudgetWithoutRecursion() {
		CompiledSchema self = compile(document(ROOT_URI, "{\"$ref\":\"#\"}"));
		McpSchemaValidationOutcome.LimitExceeded selfLimit = assertLimitExceeded(
				evaluate(self, "null", limits(100, 3)));
		Assertions.assertEquals(McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS,
				selfLimit.limit());
		Assertions.assertEquals(8, selfLimit.evaluationOperations());

		CompiledSchema pair = compile(document(ROOT_URI, """
				{
				  "$defs":{
				    "a":{"$ref":"#/$defs/b"},
				    "b":{"$ref":"#/$defs/a"}
				  },
				  "$ref":"#/$defs/a"
				}
				"""));
		McpSchemaValidationOutcome.LimitExceeded pairLimit = assertLimitExceeded(
				evaluate(pair, "null", limits(1_000, 50)));
		Assertions.assertEquals(McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS,
				pairLimit.limit());
		Assertions.assertEquals(102, pairLimit.evaluationOperations());
	}

	@Test
	public void pendingTaskLimitAcceptsExactWidthAndRejectsOneOver() {
		CompiledSchema compiled = compile(document(ROOT_URI,
				"{\"allOf\":[true,true]}"));

		assertValid(evaluate(compiled, "null", limits(100, 100, 2)));
		McpSchemaValidationOutcome.LimitExceeded oneOver = assertLimitExceeded(
				evaluate(compiled, "null", limits(100, 100, 1)));
		Assertions.assertEquals(McpSchemaEvaluationLimit.PENDING_TASKS,
				oneOver.limit());
	}

	@Test
	public void recursiveReferenceRunsAfterItsConjunctiveSiblings() {
		CompiledSchema allOf = compile(document(ROOT_URI, """
				{"$ref":"#","allOf":[true,true,true]}
				"""));
		McpSchemaValidationOutcome.LimitExceeded allOfLimit =
				assertLimitExceeded(evaluate(allOf, "null", limits(1_000, 3, 4)));
		Assertions.assertEquals(McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS,
				allOfLimit.limit());

		CompiledSchema properties = compile(document(ROOT_URI, """
				{
				  "$ref":"#",
				  "properties":{"a":true,"b":true,"c":true}
				}
				"""));
		McpSchemaValidationOutcome.LimitExceeded propertiesLimit =
				assertLimitExceeded(evaluate(properties, """
						{"a":1,"b":2,"c":3}
						""", limits(1_000, 3, 4)));
		Assertions.assertEquals(McpSchemaEvaluationLimit.REFERENCE_TRAVERSALS,
				propertiesLimit.limit());
	}

	@Test
	public void branchingReferenceCycleStopsAtPendingTaskBound() {
		CompiledSchema compiled = compile(document(ROOT_URI, """
				{"allOf":[{"$ref":"#"},true,true]}
				"""));

		McpSchemaValidationOutcome.LimitExceeded outcome = assertLimitExceeded(
				evaluate(compiled, "null", limits(10_000, 1_000, 4)));
		Assertions.assertEquals(McpSchemaEvaluationLimit.PENDING_TASKS,
				outcome.limit());
	}

	@Test
	public void referenceTargetDiagnosticRetainsCurrentInstanceLocation() {
		CompiledSchema compiled = compile(document(ROOT_URI, """
				{
				  "$defs":{"reject":false},
				  "properties":{"value":{"$ref":"#/$defs/reject"}}
				}
				"""));
		McpSchemaValidationOutcome.Invalid invalid = assertInvalid(evaluate(
				compiled, "{\"value\":1}", GENEROUS_LIMITS));

		Assertions.assertEquals(1, invalid.diagnostics().size());
		Assertions.assertEquals("/$defs/reject",
				invalid.diagnostics().get(0).schemaLocation().jsonPointer());
		Assertions.assertEquals(List.of("value"),
				invalid.diagnostics().get(0).instancePointerSegments());
	}

	@Test
	public void evaluationBudgetStillDominatesBeforeReferenceTraversal() {
		CompiledSchema compiled = compile(document(ROOT_URI, "{\"$ref\":\"#\"}"));
		McpSchemaValidationOutcome.LimitExceeded outcome = assertLimitExceeded(
				evaluate(compiled, "null", limits(1, 100)));
		Assertions.assertEquals(McpSchemaEvaluationLimit.EVALUATION_OPERATIONS,
				outcome.limit());
		Assertions.assertEquals(1, outcome.evaluationOperations());
	}

	private static CompiledSchema compile(McpSchemaDocument... documents) {
		McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(
				COMPILATION_LIMITS).compile(List.of(documents));
		McpSchemaValidationProgram program =
				new McpSchemaValidationProgramCompiler().compile(graph);
		return new CompiledSchema(graph, program,
				graph.documentRoots().get(ROOT_URI));
	}

	private static McpSchemaDocument document(URI retrievalUri, String schema) {
		return new McpSchemaDocument(retrievalUri, JSON_CODEC.parse(schema));
	}

	private static McpSchemaValidationOutcome evaluate(CompiledSchema compiled,
			String instance, McpSchemaEvaluationLimits limits) {
		return new McpSchemaEvaluator().evaluate(compiled.program(),
				compiled.rootNodeId(), JSON_CODEC.parse(instance), limits);
	}

	private static McpSchemaEvaluationLimits limits(long operations,
			long references) {
		return limits(operations, references, 100_000);
	}

	private static McpSchemaEvaluationLimits limits(long operations,
			long references, int pendingTasks) {
		return new McpSchemaEvaluationLimits(operations, references, pendingTasks, 256,
				1_000, 100_000);
	}

	private static McpSchemaValidationOutcome.Valid assertValid(
			McpSchemaValidationOutcome outcome) {
		return Assertions.assertInstanceOf(McpSchemaValidationOutcome.Valid.class,
				outcome);
	}

	private static McpSchemaValidationOutcome.Invalid assertInvalid(
			McpSchemaValidationOutcome outcome) {
		return Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class,
				outcome);
	}

	private static McpSchemaValidationOutcome.LimitExceeded assertLimitExceeded(
			McpSchemaValidationOutcome outcome) {
		return Assertions.assertInstanceOf(
				McpSchemaValidationOutcome.LimitExceeded.class, outcome);
	}

	private record CompiledSchema(McpSchemaResourceGraph graph,
			McpSchemaValidationProgram program, McpSchemaNodeId rootNodeId) {
	}
}
