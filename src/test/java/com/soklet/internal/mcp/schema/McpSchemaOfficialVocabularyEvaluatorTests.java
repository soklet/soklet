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
 * Instance-validity evidence for both pinned required custom-vocabulary
 * groups. Meta-schema declaration extraction is structural at this stage;
 * full schema-against-meta-schema validation remains a separate Phase 2 gate.
 */
public class McpSchemaOfficialVocabularyEvaluatorTests {
	private static final String SUITE_ROOT =
			"com/soklet/internal/mcp/schema/json-schema-test-suite/";
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(2_000_000, 256, 1_000_000, 1_000_000,
					10_000, 100_000, 250_000, 2_000_000));
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			new McpSchemaCompilationLimits(32, 20_000, 256, 100_000,
					2_000, 4_000, 4_000, 20_000, 8_192, 1_024, 1_024);
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			new McpSchemaEvaluationLimits(100_000, 1_000, 100_000, 256,
					100, 100_000);

	@Test
	public void passesEveryPinnedRequiredCustomVocabularyCase()
			throws IOException {
		McpSchemaDialectRegistry registry =
				new McpSchemaDialectRegistryCompiler(COMPILATION_LIMITS).compile(
						List.of(
								remoteMetaSchema("metaschema-no-validation.json"),
								remoteMetaSchema(
										"metaschema-optional-vocabulary.json")));
		McpJsonArray groups = (McpJsonArray) readJson(
				"tests/draft2020-12/vocabulary.json");
		int instanceCaseCount = 0;

		for (int groupIndex = 0; groupIndex < groups.values().size(); ++groupIndex) {
			int currentGroupIndex = groupIndex;
			McpJsonObject group = (McpJsonObject) groups.values().get(groupIndex);
			URI retrievalUri = URI.create(
					"https://soklet.invalid/official/vocabulary/" + groupIndex);
			McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(
					COMPILATION_LIMITS, registry).compile(List.of(
							new McpSchemaDocument(retrievalUri,
									group.members().get("schema"))));
			McpSchemaValidationProgram program =
					new McpSchemaValidationProgramCompiler(registry).compile(graph);
			McpJsonArray cases = (McpJsonArray) group.members().get("tests");
			for (McpJsonValue caseValue : cases.values()) {
				McpJsonObject testCase = (McpJsonObject) caseValue;
				boolean expected = testCase.members().get("valid")
						== com.soklet.internal.mcp.protocol.McpJsonBoolean.TRUE;
				McpSchemaValidationOutcome outcome = new McpSchemaEvaluator()
						.evaluate(program, graph.documentRoots().get(retrievalUri),
								testCase.members().get("data"), EVALUATION_LIMITS);
				Assertions.assertEquals(expected,
						outcome instanceof McpSchemaValidationOutcome.Valid,
						() -> "vocabulary.json group " + currentGroupIndex + ": "
								+ testCase.members().get("description"));
				Assertions.assertFalse(
						outcome instanceof McpSchemaValidationOutcome.LimitExceeded);
				instanceCaseCount++;
			}
		}

		Assertions.assertEquals(2, groups.values().size());
		Assertions.assertEquals(5, instanceCaseCount);
	}

	private static McpSchemaDocument remoteMetaSchema(String fileName)
			throws IOException {
		return new McpSchemaDocument(URI.create(
				"http://localhost:1234/draft2020-12/" + fileName),
				readJson("remotes/draft2020-12/" + fileName));
	}

	private static McpJsonValue readJson(String relativePath)
			throws IOException {
		String resourceName = SUITE_ROOT + relativePath;
		try (InputStream input = McpSchemaOfficialVocabularyEvaluatorTests.class
				.getClassLoader().getResourceAsStream(resourceName)) {
			Assertions.assertNotNull(input, resourceName);
			return JSON_CODEC.parse(input.readAllBytes());
		}
	}
}
