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
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpRuntimeTypedSchemaCompilerTests {
	private static final McpSchemaCompilationLimits COMPILATION_LIMITS =
			McpSchemaCompilationLimits.productionDefaults();
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			McpSchemaEvaluationLimits.productionDefaults();

	@Test
	void atomicallyCompilesInputSchemaAndMatchingIntrinsicBinding() {
		McpCompiledRuntimeTypedSchema<Arguments> compiled = compiler()
				.compileToolInput(Arguments.class);
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("query", new McpJsonString(" exact "));
		members.put("limits", new McpJsonArray(List.of(
				new McpJsonNumber(2), new McpJsonNumber(5))));
		McpJsonObject input = new McpJsonObject(members);

		assertInstanceOf(McpSchemaValidationOutcome.Valid.class,
				new McpToolSchemaProfileEvaluator().evaluate(
						compiled.schema().program(), input, EVALUATION_LIMITS));
		Arguments arguments = compiled.fromJson(input);
		assertEquals(new Arguments(" exact ", Optional.empty(), List.of(2, 5)),
				arguments);
		assertEquals(input, compiled.toJson(arguments));
	}

	@Test
	void atomicallyCompilesOutputSchemaAndRejectsInvalidJavaOutput() {
		McpCompiledRuntimeTypedSchema<Result> compiled = compiler()
				.compileToolOutput(Result.class);
		Result result = new Result(List.of(new Item("b", 2), new Item("a", 1)));
		McpJsonValue output = compiled.toJson(result);

		assertInstanceOf(McpSchemaValidationOutcome.Valid.class,
				new McpToolSchemaProfileEvaluator().evaluate(
						compiled.schema().program(), output, EVALUATION_LIMITS));
		assertEquals(result, compiled.fromJson(output));
		assertThrows(McpTypedJsonBindingException.class,
				() -> compiled.toJson(null));
	}

	private McpRuntimeTypedSchemaCompiler compiler() {
		return new McpRuntimeTypedSchemaCompiler(COMPILATION_LIMITS,
				McpTypedJsonBindingLimits.productionDefaults(),
				new McpJsonCodec(productionJsonLimits()));
	}

	private McpJsonLimits productionJsonLimits() {
		return new McpJsonLimits(4 * 1_024 * 1_024, 128,
				1_024 * 1_024, 1_024 * 1_024, 1_024, 10_000,
				100_000, 4 * 1_024 * 1_024);
	}

	private record Arguments(String query, Optional<Integer> limit,
			List<Integer> limits) {
	}

	private record Result(List<Item> items) {
	}

	private record Item(String id, int score) {
	}
}
