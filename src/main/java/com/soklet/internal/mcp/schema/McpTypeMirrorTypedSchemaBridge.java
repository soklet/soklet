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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Compile-time bridge from one annotated tool signature to Soklet's shared
 * typed-schema implementation.
 *
 * <p>This type is public only so {@code SokletProcessor}, which lives in a
 * different Soklet package, can consume the internal schema implementation.
 * It is not an application API. Annotation-processing type utilities do not
 * promise concurrent access, so callers must confine one invocation to the
 * processor thread.</p>
 *
 * <p>Rejected schemas expose only a stable reason, schema direction, and
 * logical schema path. They never retain or render a {@link TypeMirror}, an
 * application value, or annotation title and description values. A bounded,
 * diagnostic-safe rendering of a published property name can appear as one
 * component of the logical path.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public final class McpTypeMirrorTypedSchemaBridge {
	private McpTypeMirrorTypedSchemaBridge() {
	}

	/**
	 * Compiles the synthetic object input and declared output schemas for one
	 * tool using the production schema and JSON limits.
	 *
	 * @param types       the annotation-processing type utilities
	 * @param elements    the annotation-processing element utilities
	 * @param arguments   published tool arguments in their wire order
	 * @param outputType  the tool's declared output type
	 * @return compiled schemas or a safe rejection diagnostic
	 * @throws NullPointerException if an argument is {@code null}
	 */
	@NonNull
	public static Result compileToolSchemas(@NonNull Types types,
			@NonNull Elements elements,
			@NonNull List<@NonNull ToolArgument> arguments,
			@NonNull TypeMirror outputType) {
		requireNonNull(types);
		requireNonNull(elements);
		requireNonNull(arguments);
		requireNonNull(outputType);

		McpSchemaCompilationLimits limits =
				McpSchemaCompilationLimits.productionDefaults();
		McpTypedSchemaCompiler<TypeMirror> compiler =
				new McpTypedSchemaCompiler<>(
						new McpTypeMirrorTypedTypeModel(types, elements, limits),
						limits, new McpJsonCodec(McpJsonLimits.productionDefaults()));
		List<McpTypedTypeDescriptor.RecordComponent<TypeMirror>> components =
				new ArrayList<>(arguments.size());
		for (ToolArgument argument : arguments) {
			requireNonNull(argument);
			components.add(McpTypedTypeDescriptor.RecordComponent
					.fromNameAndType(argument.publishedName(), argument.type(),
							argument.title(), argument.description()));
		}

		McpCompiledTypedSchema inputSchema;
		try {
			inputSchema = compiler.compileToolInputProperties(components);
		} catch (McpTypedSchemaException exception) {
			return new RejectedSchemas(diagnostic(Direction.TOOL_INPUT,
					exception));
		}

		McpCompiledTypedSchema outputSchema;
		try {
			outputSchema = compiler.compileToolOutput(outputType);
		} catch (McpTypedSchemaException exception) {
			return new RejectedSchemas(diagnostic(Direction.TOOL_OUTPUT,
					exception));
		}

		return new CompiledSchemas(inputSchema.document(),
				inputSchema.serializedDocument(), outputSchema.document(),
				outputSchema.serializedDocument());
	}

	@NonNull
	private static Diagnostic diagnostic(@NonNull Direction direction,
			@NonNull McpTypedSchemaException exception) {
		return new Diagnostic(direction, reason(exception.reason()),
				exception.path().toString());
	}

	@NonNull
	private static Reason reason(
			McpTypedSchemaException.@NonNull Reason reason) {
		return switch (reason) {
			case INVALID_DESCRIPTOR -> Reason.INVALID_DESCRIPTOR;
			case UNSUPPORTED_TYPE -> Reason.UNSUPPORTED_TYPE;
			case RAW_GENERIC -> Reason.RAW_GENERIC;
			case WILDCARD -> Reason.WILDCARD;
			case UNRESOLVED_TYPE_VARIABLE -> Reason.UNRESOLVED_TYPE_VARIABLE;
			case UNRESOLVED_GENERIC_ARRAY_COMPONENT ->
					Reason.UNRESOLVED_GENERIC_ARRAY_COMPONENT;
			case OBJECT_TYPE -> Reason.OBJECT_TYPE;
			case CHAR_SEQUENCE_TYPE -> Reason.CHAR_SEQUENCE_TYPE;
			case FRAMEWORK_TYPE -> Reason.FRAMEWORK_TYPE;
			case OPTIONAL_OUTSIDE_PROPERTY -> Reason.OPTIONAL_OUTSIDE_PROPERTY;
			case INPUT_ROOT_NOT_OBJECT -> Reason.INPUT_ROOT_NOT_OBJECT;
			case AMBIGUOUS_OUTPUT_STRING -> Reason.AMBIGUOUS_OUTPUT_STRING;
			case MAP_KEY_NOT_STRING -> Reason.MAP_KEY_NOT_STRING;
			case RECURSIVE_TYPE -> Reason.RECURSIVE_TYPE;
			case DUPLICATE_PROPERTY -> Reason.DUPLICATE_PROPERTY;
			case LIMIT_EXCEEDED -> Reason.LIMIT_EXCEEDED;
		};
	}

	/**
	 * Published name and compile-time type of one tool argument.
	 *
	 * @param publishedName the argument name published on the MCP wire
	 * @param type          the declared Java argument type
	 * @param title         the optional human-readable title; blank is absent
	 * @param description   the optional human-readable description; blank is
	 *                      absent
	 */
	@NotThreadSafe
	public record ToolArgument(@NonNull String publishedName,
			@NonNull TypeMirror type, @NonNull String title,
			@NonNull String description) {
		public ToolArgument {
			requireNonNull(publishedName);
			requireNonNull(type);
			requireNonNull(title);
			requireNonNull(description);
		}

		/**
		 * Creates an argument without title or description metadata.
		 *
		 * @param publishedName the argument name published on the MCP wire
		 * @param type          the declared Java argument type
		 */
		public ToolArgument(@NonNull String publishedName,
				@NonNull TypeMirror type) {
			this(publishedName, type, "", "");
		}
	}

	/**
	 * Result of compiling one tool's input and output schemas.
	 */
	@ThreadSafe
	public sealed interface Result permits CompiledSchemas, RejectedSchemas {
	}

	/**
	 * Successfully compiled, immutable tool schemas.
	 */
	@ThreadSafe
	public static final class CompiledSchemas implements Result {
		@NonNull
		private final McpJsonObject inputSchemaDocument;
		private final byte @NonNull [] inputSchemaBytes;
		@NonNull
		private final McpJsonObject outputSchemaDocument;
		private final byte @NonNull [] outputSchemaBytes;

		private CompiledSchemas(@NonNull McpJsonObject inputSchemaDocument,
				byte @NonNull [] inputSchemaBytes,
				@NonNull McpJsonObject outputSchemaDocument,
				byte @NonNull [] outputSchemaBytes) {
			this.inputSchemaDocument = requireNonNull(inputSchemaDocument);
			this.inputSchemaBytes = requireNonNull(inputSchemaBytes).clone();
			this.outputSchemaDocument = requireNonNull(outputSchemaDocument);
			this.outputSchemaBytes = requireNonNull(outputSchemaBytes).clone();
		}

		/**
		 * @return the validated canonical input schema document
		 */
		@NonNull
		public McpJsonObject getInputSchemaDocument() {
			return inputSchemaDocument;
		}

		/**
		 * @return a copy of the canonical UTF-8 input schema
		 */
		public byte @NonNull [] getInputSchemaBytes() {
			return inputSchemaBytes.clone();
		}

		/**
		 * @return the validated canonical output schema document
		 */
		@NonNull
		public McpJsonObject getOutputSchemaDocument() {
			return outputSchemaDocument;
		}

		/**
		 * @return a copy of the canonical UTF-8 output schema
		 */
		public byte @NonNull [] getOutputSchemaBytes() {
			return outputSchemaBytes.clone();
		}
	}

	/**
	 * Rejected tool schemas with a safe processor diagnostic.
	 *
	 * @param diagnostic the stable rejection diagnostic
	 */
	@ThreadSafe
	public record RejectedSchemas(@NonNull Diagnostic diagnostic)
			implements Result {
		public RejectedSchemas {
			requireNonNull(diagnostic);
		}
	}

	/**
	 * Safe schema rejection information for annotation-processor diagnostics.
	 *
	 * @param direction the rejected schema direction
	 * @param reason    the stable rejection category
	 * @param path      the diagnostic-safe logical schema path, rooted at
	 *                  {@code $}
	 */
	@ThreadSafe
	public record Diagnostic(@NonNull Direction direction,
			@NonNull Reason reason, @NonNull String path) {
		public Diagnostic {
			requireNonNull(direction);
			requireNonNull(reason);
			requireNonNull(path);
			if (!path.startsWith("$"))
				throw new IllegalArgumentException(
						"A typed-schema diagnostic path must be rooted at '$'.");
		}
	}

	/**
	 * Tool schema direction associated with a rejection.
	 */
	public enum Direction {
		TOOL_INPUT,
		TOOL_OUTPUT
	}

	/**
	 * Stable processor-facing typed-schema rejection categories.
	 */
	public enum Reason {
		INVALID_DESCRIPTOR,
		UNSUPPORTED_TYPE,
		RAW_GENERIC,
		WILDCARD,
		UNRESOLVED_TYPE_VARIABLE,
		UNRESOLVED_GENERIC_ARRAY_COMPONENT,
		OBJECT_TYPE,
		CHAR_SEQUENCE_TYPE,
		FRAMEWORK_TYPE,
		OPTIONAL_OUTSIDE_PROPERTY,
		INPUT_ROOT_NOT_OBJECT,
		AMBIGUOUS_OUTPUT_STRING,
		MAP_KEY_NOT_STRING,
		RECURSIVE_TYPE,
		DUPLICATE_PROPERTY,
		LIMIT_EXCEEDED
	}
}
