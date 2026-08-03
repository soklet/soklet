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
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Manifest-driven semantic evidence from complete, pinned upstream groups that
 * fit Soklet MCP Tool Schema Profile 1 exactly.
 */
public class McpToolSchemaProfileOfficialSuiteTests {
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			McpSchemaCompilationLimits.productionDefaults();
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			McpSchemaEvaluationLimits.productionDefaults();

	@Test
	public void passesEveryManifestSelectedPinnedGroupAndCase()
			throws IOException {
		McpToolSchemaProfileTestManifest.Suite suite =
				McpToolSchemaProfileTestManifest.load().suite();
		int groupCount = 0;
		int caseCount = 0;
		long maximumEvaluationOperations = 0;

		for (McpToolSchemaProfileTestManifest.Selection selection
				: suite.selections()) {
			McpJsonArray groups = array(
					McpToolSchemaProfileTestManifest.readJson(
							suite.testsRoot() + selection.file()));
			int fileCaseCount = 0;
			for (int groupIndex : selection.selectedGroupIndexes()) {
				McpJsonObject group = object(groups.values().get(groupIndex));
				String groupDescription = string(group, "description");
				String groupEvidence = selection.file() + " group " + groupIndex
						+ " :: " + groupDescription;
				McpJsonObject schema = Assertions.assertInstanceOf(
						McpJsonObject.class, group.members().get("schema"),
						groupEvidence);
				McpToolSchemaProfileProgram program = Assertions.assertDoesNotThrow(
						() -> new McpToolSchemaProfileCompiler(COMPILATION_LIMITS)
								.compile(schema), groupEvidence);
				McpJsonArray cases = array(group.members().get("tests"));

				for (McpJsonValue value : cases.values()) {
					McpJsonObject testCase = object(value);
					String evidence = groupEvidence + " :: "
							+ string(testCase, "description");
					boolean expected = Assertions.assertInstanceOf(
							McpJsonBoolean.class,
							testCase.members().get("valid"), evidence)
							== McpJsonBoolean.TRUE;
					McpSchemaValidationOutcome outcome =
							new McpToolSchemaProfileEvaluator().evaluate(program,
									testCase.members().get("data"), EVALUATION_LIMITS);
					Assertions.assertFalse(
							outcome instanceof McpSchemaValidationOutcome.LimitExceeded,
							evidence + " :: " + outcome);
					Assertions.assertEquals(expected,
							outcome instanceof McpSchemaValidationOutcome.Valid,
							evidence + " :: " + outcome);
					maximumEvaluationOperations = Math.max(
							maximumEvaluationOperations,
							outcome.evaluationOperations());
					fileCaseCount++;
					caseCount++;
				}
				groupCount++;
			}
			Assertions.assertEquals(selection.selectedCaseCount(), fileCaseCount,
					selection.file());
		}

		Assertions.assertEquals(133, groupCount);
		Assertions.assertEquals(500, caseCount);
		Assertions.assertEquals(suite.expectedSelectedGroupCount(), groupCount);
		Assertions.assertEquals(suite.expectedSelectedCaseCount(), caseCount);
		Assertions.assertEquals(86, maximumEvaluationOperations);
	}

	@Test
	public void rejectsEveryManifestClassifiedRejectedGroup()
			throws IOException {
		McpToolSchemaProfileTestManifest.Suite suite =
				McpToolSchemaProfileTestManifest.load().suite();
		int rejectedGroupCount = 0;

		for (McpToolSchemaProfileTestManifest.Selection selection
				: suite.selections()) {
			McpJsonArray groups = array(
					McpToolSchemaProfileTestManifest.readJson(
							suite.testsRoot() + selection.file()));
			for (int groupIndex : selection.rejectedGroupIndexes()) {
				McpJsonObject group = object(groups.values().get(groupIndex));
				String evidence = selection.file() + " group " + groupIndex
						+ " :: " + string(group, "description");
				McpJsonValue schema = group.members().get("schema");
				if (schema instanceof McpJsonObject object) {
					McpSchemaCompilationException exception = Assertions.assertThrows(
							McpSchemaCompilationException.class,
							() -> new McpToolSchemaProfileCompiler(
									COMPILATION_LIMITS).compile(object), evidence);
					Assertions.assertNotEquals(
							McpSchemaCompilationException.Kind.LIMIT_EXCEEDED,
							exception.kind(), evidence);
				} else {
					Assertions.assertInstanceOf(
							com.soklet.internal.mcp.protocol.McpJsonBoolean.class,
							schema, evidence);
					Assertions.assertThrows(IllegalArgumentException.class,
							() -> compileProfileRoot(schema), evidence);
				}
				rejectedGroupCount++;
			}
		}

		Assertions.assertEquals(56, rejectedGroupCount);
		Assertions.assertEquals(suite.expectedRejectedGroupCount(),
				rejectedGroupCount);
	}

	private static McpToolSchemaProfileProgram compileProfileRoot(
			McpJsonValue schema) {
		if (!(schema instanceof McpJsonObject object))
			throw new IllegalArgumentException(
					"A Profile 1 document root must be a JSON object.");
		return new McpToolSchemaProfileCompiler(COMPILATION_LIMITS)
				.compile(object);
	}

	private static McpJsonObject object(McpJsonValue value) {
		return Assertions.assertInstanceOf(McpJsonObject.class, value);
	}

	private static McpJsonArray array(McpJsonValue value) {
		return Assertions.assertInstanceOf(McpJsonArray.class, value);
	}

	private static String string(McpJsonObject object, String name) {
		return Assertions.assertInstanceOf(McpJsonString.class,
				object.members().get(name)).value();
	}
}
