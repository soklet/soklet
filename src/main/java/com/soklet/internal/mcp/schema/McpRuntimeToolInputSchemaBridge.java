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
import com.soklet.internal.mcp.protocol.McpMirroredHeaderPlan;
import com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Immutable production bridge for one package-private authored tool-input
 * schema.
 *
 * <p>This bridge exists solely for Soklet's conformance fixture. It is public
 * only so production code in another Soklet package can consume the internal
 * schema implementation; internal MCP packages are excluded from Soklet's
 * public API and compatibility surface. Compilation and evaluation use the
 * reviewed production JSON and Profile 1 limits.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpRuntimeToolInputSchemaBridge {
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
	@NonNull
	private final McpMirroredHeaderPlan mirroredHeaderPlan;

	/**
	 * Compiles and validates an authored Profile 1 tool-input schema.
	 *
	 * @param schemaDocument immutable public-JSON schema document
	 * @return compiled production bridge
	 * @throws NullPointerException if {@code schemaDocument} is {@code null}
	 * @throws IllegalArgumentException if the document exceeds a production
	 *                                  limit, is outside Profile 1, is not a
	 *                                  direct object-root tool input, or has an
	 *                                  invalid mirrored-header declaration
	 */
	@NonNull
	public static McpRuntimeToolInputSchemaBridge compileToolInput(
			@NonNull McpJsonObject schemaDocument) {
		McpJsonObject retainedDocument = requireNonNull(schemaDocument);
		com.soklet.internal.mcp.protocol.McpJsonObject internalDocument =
				McpPublicJsonValueConverter.toInternalObject(retainedDocument);
		McpToolSchemaProfileProgram program =
				new McpToolSchemaProfileCompiler(
						McpSchemaCompilationLimits.productionDefaults())
						.compile(internalDocument);
		McpMirroredHeaderPlan mirroredHeaderPlan =
				new McpSchemaUseValidator().validateToolInput(program);
		return new McpRuntimeToolInputSchemaBridge(retainedDocument, program,
				mirroredHeaderPlan);
	}

	private McpRuntimeToolInputSchemaBridge(
			@NonNull McpJsonObject schemaDocument,
			@NonNull McpToolSchemaProfileProgram program,
			@NonNull McpMirroredHeaderPlan mirroredHeaderPlan) {
		this.schemaDocument = requireNonNull(schemaDocument);
		this.program = requireNonNull(program);
		this.mirroredHeaderPlan = requireNonNull(mirroredHeaderPlan);
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
	 * Returns custom mirrored-header declarations derived during schema-use
	 * validation.
	 *
	 * @return immutable mirrored-header plan
	 */
	@NonNull
	public McpMirroredHeaderPlan getMirroredHeaderPlan() {
		return this.mirroredHeaderPlan;
	}

	/**
	 * Validates raw tool arguments and returns the same immutable object.
	 *
	 * @param rawArguments raw tool arguments
	 * @return {@code rawArguments} after successful bounded evaluation
	 * @throws NullPointerException if {@code rawArguments} is {@code null}
	 * @throws IllegalArgumentException if the arguments exceed a production
	 *                                  JSON limit, do not satisfy the schema, or
	 *                                  exhaust a production evaluation limit
	 */
	@NonNull
	public McpJsonObject decode(@NonNull McpJsonObject rawArguments) {
		McpJsonObject retainedArguments = requireNonNull(rawArguments);
		com.soklet.internal.mcp.protocol.McpJsonObject internalArguments =
				McpPublicJsonValueConverter.toInternalObject(retainedArguments);
		McpSchemaValidationOutcome outcome = EVALUATOR.evaluate(this.program,
				internalArguments, EVALUATION_LIMITS);
		if (!(outcome instanceof McpSchemaValidationOutcome.Valid))
			throw new IllegalArgumentException(
					"The JSON value does not satisfy the compiled tool schema.");
		return retainedArguments;
	}

}
