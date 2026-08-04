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
 */
final class McpTypedSchemaCompiler<T> {
	private final McpTypedSchemaResolver<T> resolver;
	private final McpTypedSchemaRenderer renderer;
	private final McpToolSchemaProfileCompiler profileCompiler;
	private final McpSchemaUseValidator useValidator;
	private final McpJsonCodec jsonCodec;

	McpTypedSchemaCompiler(McpTypedTypeModel<T> typeModel,
			McpSchemaCompilationLimits compilationLimits,
			McpJsonCodec jsonCodec) {
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

	McpCompiledTypedSchema compileSchema(T type) {
		return finish(resolver.resolveSchema(requireNonNull(type)), Use.SCHEMA);
	}

	McpCompiledTypedSchema compileToolInput(T type) {
		return finish(resolver.resolveToolInput(requireNonNull(type)),
				Use.TOOL_INPUT);
	}

	McpCompiledTypedSchema compileToolOutput(T type) {
		return finish(resolver.resolveToolOutput(requireNonNull(type)),
				Use.TOOL_OUTPUT);
	}

	McpCompiledTypedSchema compileToolInputProperties(
			List<McpTypedTypeDescriptor.RecordComponent<T>> components) {
		return finish(resolver.resolveToolInputProperties(
				requireNonNull(components)), Use.TOOL_INPUT);
	}

	private McpCompiledTypedSchema finish(McpTypedSchemaShape shape, Use use) {
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
