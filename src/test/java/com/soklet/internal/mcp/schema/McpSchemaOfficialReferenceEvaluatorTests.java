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
 * Selected official instance-validity evidence for static references and
 * location-independent anchors. Groups requiring deferred keywords or full
 * meta-schema evaluation remain outside this explicit allowlist.
 */
public class McpSchemaOfficialReferenceEvaluatorTests {
	private static final String SUITE_ROOT =
			"com/soklet/internal/mcp/schema/json-schema-test-suite/";
	private static final List<Integer> REF_GROUPS = List.of(
			1, 3, 4, 7, 8, 9, 10, 12, 14, 15, 16, 20,
			22, 23, 24, 25, 26, 27, 28, 32, 33, 34, 35);
	private static final List<Integer> ANCHOR_GROUPS = List.of(0, 1, 2, 3);
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(2_000_000, 256, 1_000_000, 1_000_000,
					10_000, 100_000, 250_000, 2_000_000));
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			new McpSchemaCompilationLimits(8, 10_000, 256, 100_000,
					1_000, 2_000, 2_000, 10_000, 20_000, 1_024, 1_024);
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			new McpSchemaEvaluationLimits(500_000, 10_000, 100_000, 256,
					1_000, 500_000);

	@Test
	public void passesSelectedPinnedStaticReferenceAndAnchorCases()
			throws IOException {
		int refCaseCount = evaluateSelected("ref.json", 36, REF_GROUPS);
		int anchorCaseCount = evaluateSelected("anchor.json", 4,
				ANCHOR_GROUPS);

		Assertions.assertEquals(23, REF_GROUPS.size());
		Assertions.assertEquals(51, refCaseCount);
		Assertions.assertEquals(4, ANCHOR_GROUPS.size());
		Assertions.assertEquals(8, anchorCaseCount);
		Assertions.assertEquals(27, REF_GROUPS.size() + 4);
		Assertions.assertEquals(59, refCaseCount + anchorCaseCount);
	}

	private static int evaluateSelected(String fileName, int expectedGroupCount,
			List<Integer> groupIndexes) throws IOException {
		McpJsonArray groups = (McpJsonArray) readJson(
				"tests/draft2020-12/" + fileName);
		Assertions.assertEquals(expectedGroupCount, groups.values().size(), fileName);
		int instanceCaseCount = 0;

		for (int groupIndex : groupIndexes) {
			McpJsonObject group = (McpJsonObject) groups.values().get(groupIndex);
			URI retrievalUri = URI.create(
					"https://soklet.invalid/official/reference/" + fileName
							+ "/" + groupIndex);
			McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(
					COMPILATION_LIMITS).compile(List.of(new McpSchemaDocument(
							retrievalUri, group.members().get("schema"))));
			McpSchemaValidationProgram program =
					new McpSchemaValidationProgramCompiler().compile(graph);
			McpJsonArray cases = (McpJsonArray) group.members().get("tests");
			for (McpJsonValue caseValue : cases.values()) {
				McpJsonObject testCase = (McpJsonObject) caseValue;
				boolean expected = testCase.members().get("valid")
						== McpJsonBoolean.TRUE;
				McpSchemaValidationOutcome outcome = new McpSchemaEvaluator()
						.evaluate(program, graph.documentRoots().get(retrievalUri),
								testCase.members().get("data"), EVALUATION_LIMITS);
				Assertions.assertEquals(expected,
						outcome instanceof McpSchemaValidationOutcome.Valid,
						() -> fileName + " group " + groupIndex + ": "
								+ testCase.members().get("description"));
				Assertions.assertFalse(
						outcome instanceof McpSchemaValidationOutcome.LimitExceeded);
				instanceCaseCount++;
			}
		}

		return instanceCaseCount;
	}

	private static McpJsonValue readJson(String relativePath)
			throws IOException {
		String resourceName = SUITE_ROOT + relativePath;
		try (InputStream input = McpSchemaOfficialReferenceEvaluatorTests.class
				.getClassLoader().getResourceAsStream(resourceName)) {
			Assertions.assertNotNull(input, resourceName);
			return JSON_CODEC.parse(input.readAllBytes());
		}
	}
}
