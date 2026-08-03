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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

/**
 * First instance-validity evidence over a selected primitive-only subset of
 * pinned required groups. The enum group that composes properties and required
 * is explicitly deferred to the applicator slice.
 */
public class McpSchemaOfficialPrimitiveEvaluatorTests {
	private static final int ENUM_APPLICATOR_GROUP = 3;
	private static final String SUITE_ROOT =
			"com/soklet/internal/mcp/schema/json-schema-test-suite/";
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(2_000_000, 256, 1_000_000, 1_000_000,
					10_000, 100_000, 250_000, 2_000_000));
	private static final McpSchemaResourceGraphCompiler GRAPH_COMPILER =
			new McpSchemaResourceGraphCompiler(new McpSchemaCompilationLimits(
					4, 2_000, 256, 20_000, 100, 200,
					200, 2_000, 8_192, 1_024, 1_024));
	private static final McpSchemaValidationProgramCompiler PROGRAM_COMPILER =
			new McpSchemaValidationProgramCompiler();
	private static final McpSchemaEvaluator EVALUATOR = new McpSchemaEvaluator();
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			new McpSchemaEvaluationLimits(100_000, 1_000, 100_000, 256,
					100, 100_000);

	@Test
	public void passesSelectedPinnedPrimitiveSubsetWithApplicatorGroupDeferred()
			throws IOException {
		List<FileExpectation> expectations = List.of(
				new FileExpectation("boolean_schema.json", 2, 18, -1, 2, 18),
				new FileExpectation("type.json", 11, 80, -1, 11, 80),
				new FileExpectation("const.json", 17, 54, -1, 17, 54),
				new FileExpectation("enum.json", 15, 51,
						ENUM_APPLICATOR_GROUP, 14, 45));
		int groupCount = 0;
		int caseCount = 0;

		for (FileExpectation expectation : expectations) {
			McpJsonArray groups = requiredGroups(expectation.fileName());
			Assertions.assertEquals(expectation.groupCount(), groups.values().size(),
					expectation.fileName());
			int fileCaseCount = 0;

			for (int groupIndex = 0; groupIndex < groups.values().size(); ++groupIndex) {
				McpJsonObject group = (McpJsonObject) groups.values().get(groupIndex);
				McpJsonArray cases = (McpJsonArray) group.members().get("tests");
				if (groupIndex == expectation.excludedApplicatorGroup()) {
					Assertions.assertEquals("enums in properties",
							string(group, "description"));
					Assertions.assertEquals(6, cases.values().size());
					continue;
				}
				String groupDescription = string(group, "description");
				URI retrievalUri = URI.create("https://soklet.invalid/official/primitive/"
						+ expectation.fileName() + "/" + groupIndex);
				McpSchemaResourceGraph graph = GRAPH_COMPILER.compile(List.of(
						new McpSchemaDocument(retrievalUri,
								group.members().get("schema"))));
				McpSchemaValidationProgram program = PROGRAM_COMPILER.compile(graph);
				McpSchemaNodeId rootNodeId = graph.documentRoots().get(retrievalUri);
				for (McpJsonValue value : cases.values()) {
					McpJsonObject testCase = (McpJsonObject) value;
					String caseDescription = string(testCase, "description");
					String evidence = expectation.fileName() + " :: "
							+ groupDescription + " :: " + caseDescription;
					McpSchemaValidationOutcome outcome = EVALUATOR.evaluate(program,
							rootNodeId, testCase.members().get("data"), EVALUATION_LIMITS);
					boolean expectedValid = testCase.members().get("valid")
							== McpJsonBoolean.TRUE;
					if (expectedValid)
						Assertions.assertInstanceOf(
								McpSchemaValidationOutcome.Valid.class, outcome, evidence);
					else
						Assertions.assertInstanceOf(
								McpSchemaValidationOutcome.Invalid.class, outcome, evidence);
					fileCaseCount++;
				}
				groupCount++;
			}

			Assertions.assertEquals(expectation.selectedCaseCount(), fileCaseCount,
					expectation.fileName());
			Assertions.assertEquals(expectation.sourceCaseCount(),
					groups.values().stream()
							.map(McpJsonObject.class::cast)
							.mapToInt(group -> ((McpJsonArray) group.members()
									.get("tests")).values().size()).sum(),
					expectation.fileName());
			caseCount += fileCaseCount;
		}

		Assertions.assertEquals(44, groupCount);
		Assertions.assertEquals(197, caseCount);
	}

	private static McpJsonArray requiredGroups(String fileName) throws IOException {
		String resourceName = SUITE_ROOT + "tests/draft2020-12/" + fileName;
		try (InputStream input = McpSchemaOfficialPrimitiveEvaluatorTests.class
				.getClassLoader().getResourceAsStream(resourceName)) {
			Assertions.assertNotNull(input, resourceName);
			return (McpJsonArray) JSON_CODEC.parse(input.readAllBytes());
		}
	}

	private static String string(McpJsonObject object, String member) {
		return ((McpJsonString) object.members().get(member)).value();
	}

	private record FileExpectation(String fileName, int groupCount,
			int sourceCaseCount, int excludedApplicatorGroup,
			int selectedGroupCount, int selectedCaseCount) {
		private FileExpectation {
			if (selectedGroupCount != groupCount
					- (excludedApplicatorGroup < 0 ? 0 : 1))
				throw new IllegalArgumentException("Inconsistent group selection.");
		}
	}
}
