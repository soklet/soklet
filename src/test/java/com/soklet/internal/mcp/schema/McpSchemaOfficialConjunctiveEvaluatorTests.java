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
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

/**
 * Selected official instance-validity evidence for the first structured,
 * conjunctive evaluator slice: properties, required, and allOf.
 */
public class McpSchemaOfficialConjunctiveEvaluatorTests {
	private static final String SUITE_ROOT =
			"com/soklet/internal/mcp/schema/json-schema-test-suite/";
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(2_000_000, 256, 1_000_000, 1_000_000,
					10_000, 100_000, 250_000, 2_000_000));
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			new McpSchemaCompilationLimits(4, 4_000, 256, 40_000,
					400, 800, 800, 4_000, 8_192, 1_024, 1_024);
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			new McpSchemaEvaluationLimits(500_000, 1_000, 100_000, 256,
					1_000, 500_000);
	private static final List<FileSelection> SELECTIONS = List.of(
			new FileSelection("properties.json", 6,
					List.of(0, 2, 3, 4, 5), 20),
			new FileSelection("required.json", 5,
					List.of(0, 1, 2, 3, 4), 18),
			new FileSelection("allOf.json", 12,
					List.of(0, 1, 3, 4, 5, 6, 7, 8, 9, 10), 20),
			new FileSelection("enum.json", 15, List.of(3), 6));

	@Test
	public void passesSelectedPinnedStructuredConjunctiveSubset()
			throws IOException {
		int groupCount = 0;
		int instanceCaseCount = 0;

		for (FileSelection selection : SELECTIONS) {
			McpJsonArray groups = (McpJsonArray) readJson(
					"tests/draft2020-12/" + selection.fileName());
			Assertions.assertEquals(selection.expectedFileGroupCount(),
					groups.values().size(), selection.fileName());
			int fileCaseCount = 0;

			for (int groupIndex : selection.groupIndexes()) {
				McpJsonObject group = (McpJsonObject) groups.values().get(groupIndex);
				McpJsonArray cases = (McpJsonArray) group.members().get("tests");
				URI retrievalUri = URI.create(
						"https://soklet.invalid/official/conjunctive/"
								+ selection.fileName() + "/" + groupIndex);
				McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(
						COMPILATION_LIMITS).compile(List.of(new McpSchemaDocument(
								retrievalUri, group.members().get("schema"))));
				McpSchemaValidationProgram program =
						new McpSchemaValidationProgramCompiler().compile(graph);
				for (McpJsonValue caseValue : cases.values()) {
					McpJsonObject testCase = (McpJsonObject) caseValue;
					boolean expected = testCase.members().get("valid")
							== McpJsonBoolean.TRUE;
					McpSchemaValidationOutcome outcome = new McpSchemaEvaluator()
							.evaluate(program, graph.documentRoots().get(retrievalUri),
									testCase.members().get("data"), EVALUATION_LIMITS);
					Assertions.assertEquals(expected,
							outcome instanceof McpSchemaValidationOutcome.Valid,
							() -> selection.fileName() + " group " + groupIndex
									+ ": " + testCase.members().get("description"));
					Assertions.assertFalse(
							outcome instanceof McpSchemaValidationOutcome.LimitExceeded);
					fileCaseCount++;
				}
				groupCount++;
			}

			Assertions.assertEquals(selection.expectedSelectedCaseCount(),
					fileCaseCount, selection.fileName());
			instanceCaseCount += fileCaseCount;
		}

		Assertions.assertEquals(21, groupCount);
		Assertions.assertEquals(64, instanceCaseCount);
	}

	private static McpJsonValue readJson(String relativePath)
			throws IOException {
		String resourceName = SUITE_ROOT + relativePath;
		try (InputStream input = McpSchemaOfficialConjunctiveEvaluatorTests.class
				.getClassLoader().getResourceAsStream(resourceName)) {
			Assertions.assertNotNull(input, resourceName);
			return JSON_CODEC.parse(input.readAllBytes());
		}
	}

	private record FileSelection(String fileName,
			int expectedFileGroupCount, List<Integer> groupIndexes,
			int expectedSelectedCaseCount) {
		private FileSelection {
			groupIndexes = List.copyOf(groupIndexes);
		}
	}
}
