/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.schema;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNull;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Coverage-guided checks for bounded Profile 1 compilation and evaluation.
 */
@ThreadSafe
public class McpToolSchemaProfileFuzzTest {
	private static final int MAXIMUM_FUZZ_INPUT_BYTES = 64 * 1_024;
	private static final byte[] INSTANCE_SEPARATOR =
			"\n---INSTANCE---\n".getBytes(StandardCharsets.UTF_8);
	private static final McpJsonCodec JSON_CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			McpSchemaCompilationLimits.productionDefaults();
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			McpSchemaEvaluationLimits.productionDefaults();

	@FuzzTest(maxDuration = "2m")
	public void compileAndEvaluateRemainTypedAndBounded(byte[] input) {
		byte[] boundedInput = input.length <= MAXIMUM_FUZZ_INPUT_BYTES
				? input : Arrays.copyOf(input, MAXIMUM_FUZZ_INPUT_BYTES);
		int separatorIndex = indexOf(boundedInput, INSTANCE_SEPARATOR);
		byte[] schemaBytes = separatorIndex < 0
				? boundedInput : Arrays.copyOfRange(boundedInput, 0, separatorIndex);
		byte[] instanceBytes = separatorIndex < 0
				? null : Arrays.copyOfRange(boundedInput,
						separatorIndex + INSTANCE_SEPARATOR.length, boundedInput.length);

		McpJsonValue parsedSchema;
		try {
			parsedSchema = JSON_CODEC.parse(schemaBytes);
		} catch (IllegalArgumentException expected) {
			return;
		}
		if (!(parsedSchema instanceof McpJsonObject schema))
			return;

		McpToolSchemaProfileProgram program;
		try {
			program = new McpToolSchemaProfileCompiler(COMPILATION_LIMITS)
					.compile(schema);
		} catch (McpSchemaCompilationException expected) {
			Assertions.assertNotNull(expected.kind());
			Assertions.assertNotNull(expected.limit());
			Assertions.assertNotNull(expected.location());
			Assertions.assertNotNull(expected.keyword());
			return;
		}

		McpJsonValue instance = McpJsonNull.INSTANCE;
		if (instanceBytes != null) {
			try {
				instance = JSON_CODEC.parse(instanceBytes);
			} catch (IllegalArgumentException expected) {
				return;
			}
		}
		McpSchemaValidationOutcome outcome =
				new McpToolSchemaProfileEvaluator().evaluate(program, instance,
						EVALUATION_LIMITS);
		assertBounded(outcome);
	}

	private static void assertBounded(McpSchemaValidationOutcome outcome) {
		Assertions.assertTrue(outcome.evaluationOperations() >= 0L);
		Assertions.assertTrue(outcome.evaluationOperations()
				<= EVALUATION_LIMITS.maximumEvaluationOperations());
		if (outcome instanceof McpSchemaValidationOutcome.Invalid invalid) {
			Assertions.assertTrue(invalid.diagnostics().size()
					<= EVALUATION_LIMITS.maximumDiagnosticCount());
			long diagnosticBytes = invalid.diagnostics().stream()
					.mapToLong(McpSchemaDiagnostic::utf8ByteCount)
					.sum();
			Assertions.assertTrue(diagnosticBytes
					<= EVALUATION_LIMITS.maximumDiagnosticUtf8Bytes());
		} else if (outcome instanceof McpSchemaValidationOutcome.LimitExceeded limit) {
			Assertions.assertNotNull(limit.limit());
		} else {
			Assertions.assertInstanceOf(McpSchemaValidationOutcome.Valid.class,
					outcome);
		}
	}

	private static int indexOf(byte[] value, byte[] sought) {
		outer:
		for (int index = 0; index <= value.length - sought.length; index++) {
			for (int offset = 0; offset < sought.length; offset++) {
				if (value[index + offset] != sought[offset])
					continue outer;
			}
			return index;
		}
		return -1;
	}
}
