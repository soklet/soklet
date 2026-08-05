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

import com.soklet.McpJsonArray;
import com.soklet.McpJsonBoolean;
import com.soklet.McpJsonNull;
import com.soklet.McpJsonNumber;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Immutable production bridge from a Java type to Soklet's internal typed
 * schema, validation, and JSON-binding implementation.
 *
 * <p>This type is public only so production code in another internal package
 * can consume the schema implementation. Soklet excludes internal MCP
 * packages from its public Javadocs and compatibility surface. Compilation is
 * synchronous and uses the reviewed production limits for schema compilation,
 * schema evaluation, typed binding, and JSON encoding.</p>
 *
 * @param <T> the Java value type bound by this bridge
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpRuntimeTypedSchemaBridge<T> {
	@NonNull
	private static final McpSchemaEvaluationLimits EVALUATION_LIMITS =
			McpSchemaEvaluationLimits.productionDefaults();
	@NonNull
	private static final McpToolSchemaProfileEvaluator EVALUATOR =
			new McpToolSchemaProfileEvaluator();
	@NonNull
	private final McpCompiledRuntimeTypedSchema<T> compiledSchema;
	@NonNull
	private final McpJsonObject schemaDocument;

	/**
	 * Compiles a Java type as an MCP tool input schema and intrinsic binding.
	 *
	 * @param declaredType the tool's declared Java argument type
	 * @param <T>          the Java argument type
	 * @return an immutable compiled input bridge
	 * @throws NullPointerException     if {@code declaredType} is {@code null}
	 * @throws IllegalArgumentException if the type is unsupported, cannot be
	 *                                  bound, does not produce an object-rooted
	 *                                  tool input schema, or exceeds a production
	 *                                  compilation limit
	 */
	@NonNull
	public static <T> McpRuntimeTypedSchemaBridge<T> compileToolInput(
			@NonNull Type declaredType) {
		McpCompiledRuntimeTypedSchema<T> compiledSchema = compiler()
				.compileToolInput(requireNonNull(declaredType));
		return new McpRuntimeTypedSchemaBridge<>(compiledSchema);
	}

	/**
	 * Compiles a Java type as an MCP tool output schema and intrinsic binding.
	 *
	 * @param declaredType the tool's declared Java result type
	 * @param <T>          the Java result type
	 * @return an immutable compiled output bridge
	 * @throws NullPointerException     if {@code declaredType} is {@code null}
	 * @throws IllegalArgumentException if the type is unsupported, cannot be
	 *                                  bound, is an ambiguous bare string result,
	 *                                  or exceeds a production compilation limit
	 */
	@NonNull
	public static <T> McpRuntimeTypedSchemaBridge<T> compileToolOutput(
			@NonNull Type declaredType) {
		McpCompiledRuntimeTypedSchema<T> compiledSchema = compiler()
				.compileToolOutput(requireNonNull(declaredType));
		return new McpRuntimeTypedSchemaBridge<>(compiledSchema);
	}

	private McpRuntimeTypedSchemaBridge(
			@NonNull McpCompiledRuntimeTypedSchema<T> compiledSchema) {
		this.compiledSchema = requireNonNull(compiledSchema);
		this.schemaDocument = (McpJsonObject) toPublic(
				compiledSchema.schema().document());
	}

	/**
	 * Returns the immutable public-JSON projection of the compiled schema.
	 *
	 * @return the compiled schema document
	 */
	@NonNull
	public McpJsonObject getSchemaDocument() {
		return this.schemaDocument;
	}

	/**
	 * Evaluates a public JSON value against the compiled schema using production
	 * limits.
	 *
	 * <p>An invalid value and an evaluation that exhausts a production limit both
	 * return {@code false}. No diagnostic retains or reflects the supplied
	 * value.</p>
	 *
	 * @param value the JSON value to validate
	 * @return {@code true} exactly when the value satisfies the compiled schema
	 * @throws NullPointerException if {@code value} is {@code null}
	 */
	public boolean isValid(@NonNull McpJsonValue value) {
		return validationOutcome(toInternal(requireNonNull(value)))
				instanceof McpSchemaValidationOutcome.Valid;
	}

	/**
	 * Validates and decodes a public JSON value with the compiled intrinsic
	 * binding.
	 *
	 * @param value the JSON value to validate and decode
	 * @return the decoded Java value
	 * @throws NullPointerException     if {@code value} is {@code null}
	 * @throws IllegalArgumentException if schema validation fails or exhausts a
	 *                                  production limit, or if intrinsic binding
	 *                                  cannot construct the declared Java value
	 */
	@NonNull
	public T decode(@NonNull McpJsonValue value) {
		com.soklet.internal.mcp.protocol.McpJsonValue internalValue =
				toInternal(requireNonNull(value));
		requireValid(internalValue,
				"The JSON value does not satisfy the compiled tool schema.");
		return this.compiledSchema.fromJson(internalValue);
	}

	/**
	 * Encodes a Java value with the compiled intrinsic binding, validates the
	 * encoded value, and returns its immutable public-JSON projection.
	 *
	 * @param value the Java value to encode
	 * @return the encoded JSON value
	 * @throws IllegalArgumentException if {@code value} is {@code null}, cannot
	 *                                  be encoded by the compiled binding, or the
	 *                                  encoded value fails schema validation or
	 *                                  exhausts a production evaluation limit
	 */
	@NonNull
	public McpJsonValue encode(@NonNull T value) {
		com.soklet.internal.mcp.protocol.McpJsonValue internalValue =
				this.compiledSchema.toJson(value);
		requireValid(internalValue,
				"The encoded Java value does not satisfy the compiled tool schema.");
		return toPublic(internalValue);
	}

	@NonNull
	private McpSchemaValidationOutcome validationOutcome(
			com.soklet.internal.mcp.protocol.@NonNull McpJsonValue value) {
		return EVALUATOR.evaluate(this.compiledSchema.schema().program(), value,
				EVALUATION_LIMITS);
	}

	private void requireValid(
			com.soklet.internal.mcp.protocol.@NonNull McpJsonValue value,
			@NonNull String failureMessage) {
		if (!(validationOutcome(value) instanceof McpSchemaValidationOutcome.Valid))
			throw new IllegalArgumentException(requireNonNull(failureMessage));
	}

	@NonNull
	private static McpRuntimeTypedSchemaCompiler compiler() {
		return new McpRuntimeTypedSchemaCompiler(
				McpSchemaCompilationLimits.productionDefaults(),
				McpTypedJsonBindingLimits.productionDefaults(),
				new McpJsonCodec(McpJsonLimits.productionDefaults()));
	}

	@NonNull
	private static McpJsonValue toPublic(
			com.soklet.internal.mcp.protocol.@NonNull McpJsonValue value) {
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonString string)
			return new McpJsonString(string.value());
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonNumber number)
			return new McpJsonNumber(number.value());
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonBoolean bool)
			return new McpJsonBoolean(
					bool == com.soklet.internal.mcp.protocol.McpJsonBoolean.TRUE);
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonNull)
			return McpJsonNull.INSTANCE;
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonArray array) {
			List<McpJsonValue> elements = new ArrayList<>(array.values().size());
			array.values().forEach(element -> elements.add(toPublic(element)));
			return McpJsonArray.fromElements(elements);
		}
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonObject object) {
			Map<String, McpJsonValue> members =
					new LinkedHashMap<>(object.members().size());
			object.members().forEach((name, member) ->
					members.put(name, toPublic(member)));
			return McpJsonObject.fromMembers(members);
		}
		throw new IllegalArgumentException("Unsupported internal MCP JSON value.");
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpJsonValue toInternal(
			@NonNull McpJsonValue value) {
		if (value instanceof McpJsonString string)
			return new com.soklet.internal.mcp.protocol.McpJsonString(string.value());
		if (value instanceof McpJsonNumber number)
			return new com.soklet.internal.mcp.protocol.McpJsonNumber(number.value());
		if (value instanceof McpJsonBoolean bool)
			return com.soklet.internal.mcp.protocol.McpJsonBoolean
					.fromBoolean(bool.value());
		if (value instanceof McpJsonNull)
			return com.soklet.internal.mcp.protocol.McpJsonNull.INSTANCE;
		if (value instanceof McpJsonArray array) {
			List<com.soklet.internal.mcp.protocol.McpJsonValue> elements =
					new ArrayList<>(array.getElements().size());
			array.getElements().forEach(element -> elements.add(toInternal(element)));
			return new com.soklet.internal.mcp.protocol.McpJsonArray(elements);
		}
		if (value instanceof McpJsonObject object) {
			Map<String, com.soklet.internal.mcp.protocol.McpJsonValue> members =
					new LinkedHashMap<>(object.getMembers().size());
			object.getMembers().forEach((name, member) ->
					members.put(name, toInternal(member)));
			return new com.soklet.internal.mcp.protocol.McpJsonObject(members);
		}
		throw new IllegalArgumentException("Unsupported public MCP JSON value.");
	}
}
