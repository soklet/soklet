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

import com.soklet.McpJsonObject;
import com.soklet.McpJsonValue;
import com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Immutable production bridge for one persisted tool-output schema.
 *
 * <p>This bridge lets Soklet validate deferred tool output against the exact
 * Profile 1 schema retained with its task origin, without depending on the
 * originating tool registration still being present on the serving node.
 * It is public only so production code in another Soklet package can consume
 * the internal schema implementation; internal MCP packages are excluded from
 * Soklet's public API and compatibility surface. Compilation and evaluation
 * use the reviewed production JSON and Profile 1 limits.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpRuntimeToolOutputSchemaBridge {
	@NonNull
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			McpSchemaEvaluationLimits.productionDefaults();
	@NonNull
	private static final McpToolSchemaProfileEvaluator EVALUATOR =
			new McpToolSchemaProfileEvaluator();

	@NonNull
	private final McpJsonObject schemaDocument;
	@NonNull
	private final McpToolSchemaProfileProgram program;

	/**
	 * Compiles and validates a persisted Profile 1 tool-output schema.
	 *
	 * @param schemaDocument immutable public-JSON schema document
	 * @return compiled production bridge
	 * @throws NullPointerException if {@code schemaDocument} is {@code null}
	 * @throws IllegalArgumentException if the document exceeds a production
	 *                                  limit, is outside Profile 1, or contains
	 *                                  a tool-input-only mirrored-header
	 *                                  declaration
	 */
	@NonNull
	public static McpRuntimeToolOutputSchemaBridge compileToolOutput(
			@NonNull McpJsonObject schemaDocument) {
		McpJsonObject retainedDocument = requireNonNull(schemaDocument);
		com.soklet.internal.mcp.protocol.McpJsonObject internalDocument =
				McpPublicJsonValueConverter.toInternalObject(retainedDocument);
		McpToolSchemaProfileProgram program =
				new McpToolSchemaProfileCompiler(
						McpSchemaCompilationLimits.productionDefaults())
						.compile(internalDocument);
		new McpSchemaUseValidator().validateToolOutput(program);
		return new McpRuntimeToolOutputSchemaBridge(retainedDocument, program);
	}

	private McpRuntimeToolOutputSchemaBridge(
			@NonNull McpJsonObject schemaDocument,
			@NonNull McpToolSchemaProfileProgram program) {
		this.schemaDocument = requireNonNull(schemaDocument);
		this.program = requireNonNull(program);
	}

	/**
	 * Returns the exact immutable document supplied at compilation time.
	 *
	 * @return retained public-JSON schema document
	 */
	@NonNull
	public McpJsonObject getSchemaDocument() {
		return this.schemaDocument;
	}

	/**
	 * Evaluates a public JSON value against the compiled output schema.
	 *
	 * <p>An invalid value, a value that exceeds a production JSON limit, and an
	 * evaluation that exhausts a production limit all return {@code false}. No
	 * diagnostic retains or reflects the supplied value.</p>
	 *
	 * @param value output JSON value to validate
	 * @return {@code true} exactly when the value satisfies the schema
	 * @throws NullPointerException if {@code value} is {@code null}
	 */
	public boolean isValid(@NonNull McpJsonValue value) {
		com.soklet.internal.mcp.protocol.McpJsonValue internalValue;
		try {
			internalValue = McpPublicJsonValueConverter.toInternal(
					requireNonNull(value));
		} catch (IllegalArgumentException exception) {
			return false;
		}
		return EVALUATOR.evaluate(this.program, internalValue, EVALUATION_LIMITS)
				instanceof McpSchemaValidationOutcome.Valid;
	}
}
