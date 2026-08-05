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
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpMirroredHeaderPlan;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Registration-time pipeline for closed-profile typed Java schemas.
 *
 * <p>A successful result has already been normalized, deterministically
 * rendered, serialized through the bounded production JSON implementation,
 * compiled as Profile 1, and checked for its intended tool use. Discovery can
 * therefore reuse the retained document bytes without becoming the first
 * place a generated schema is encoded or validated.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
final class McpTypedSchemaCompiler<T> {
	@NonNull
	private final McpTypedSchemaResolver<T> resolver;
	@NonNull
	private final McpTypedSchemaRenderer renderer;
	@NonNull
	private final McpToolSchemaProfileCompiler profileCompiler;
	@NonNull
	private final McpSchemaUseValidator useValidator;
	@NonNull
	private final McpJsonCodec jsonCodec;

	McpTypedSchemaCompiler(@NonNull McpTypedTypeModel<T> typeModel,
			@NonNull McpSchemaCompilationLimits compilationLimits,
			@NonNull McpJsonCodec jsonCodec) {
		requireNonNull(typeModel);
		requireNonNull(compilationLimits);
		this.jsonCodec = requireNonNull(jsonCodec);
		this.resolver = new McpTypedSchemaResolver<>(typeModel,
				compilationLimits);
		this.renderer = new McpTypedSchemaRenderer(compilationLimits);
		this.profileCompiler = new McpToolSchemaProfileCompiler(
				compilationLimits);
		this.useValidator = new McpSchemaUseValidator();
	}

	@NonNull
	McpCompiledTypedSchema compileSchema(@NonNull T type) {
		return finish(resolver.resolveSchema(requireNonNull(type)), Use.SCHEMA);
	}

	@NonNull
	McpCompiledTypedSchema compileToolInput(@NonNull T type) {
		return finish(resolver.resolveToolInput(requireNonNull(type)),
				Use.TOOL_INPUT);
	}

	@NonNull
	McpCompiledTypedSchema compileToolOutput(@NonNull T type) {
		return finish(resolver.resolveToolOutput(requireNonNull(type)),
				Use.TOOL_OUTPUT);
	}

	@NonNull
	McpCompiledTypedSchema compileToolInputProperties(
			@NonNull List<McpTypedTypeDescriptor.@NonNull RecordComponent<@NonNull T>> components) {
		return finish(resolver.resolveToolInputProperties(
				requireNonNull(components)), Use.TOOL_INPUT);
	}

	@NonNull
	private McpCompiledTypedSchema finish(@NonNull McpTypedSchemaShape shape,
			@NonNull Use use) {
		McpJsonObject document = renderer.render(shape);
		byte[] serializedDocument;
		try {
			serializedDocument = jsonCodec.toUtf8Bytes(document);
		} catch (IllegalArgumentException exception) {
			throw new McpTypedSchemaException(
					McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
					"The generated typed schema cannot be encoded within the configured JSON limits.",
					McpTypedSchemaPath.root());
		}

		McpToolSchemaProfileProgram program = profileCompiler.compile(document);
		McpMirroredHeaderPlan mirroredHeaderPlan = McpMirroredHeaderPlan.empty();
		if (use == Use.TOOL_INPUT)
			mirroredHeaderPlan = useValidator.validateToolInput(program);
		else if (use == Use.TOOL_OUTPUT)
			useValidator.validateToolOutput(program);
		else
			useValidator.validateSchema(program);
		return new McpCompiledTypedSchema(shape, document, program,
				mirroredHeaderPlan, serializedDocument);
	}

	private enum Use {
		SCHEMA,
		TOOL_INPUT,
		TOOL_OUTPUT
	}
}
