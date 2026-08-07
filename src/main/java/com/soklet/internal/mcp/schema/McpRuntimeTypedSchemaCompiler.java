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
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Type;

import static java.util.Objects.requireNonNull;

/**
 * Atomic registration-time compiler for programmatic typed tools.
 *
 * <p>No artifact escapes until the declared type has produced a bounded,
 * encoded, Profile 1-compiled schema and a matching bounded runtime binding
 * plan.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRuntimeTypedSchemaCompiler {
	@NonNull
	private final McpTypedSchemaCompiler<Type> schemaCompiler;
	@NonNull
	private final McpRuntimeTypedJsonBindingCompiler bindingCompiler;
	@NonNull
	private final McpTypedJsonBinder binder;

	McpRuntimeTypedSchemaCompiler(
			@NonNull McpSchemaCompilationLimits compilationLimits,
			@NonNull McpTypedJsonBindingLimits bindingLimits,
			@NonNull McpJsonCodec jsonCodec) {
		requireNonNull(compilationLimits);
		this.schemaCompiler = new McpTypedSchemaCompiler<>(
				new McpRuntimeTypedTypeModel(compilationLimits),
				compilationLimits, requireNonNull(jsonCodec));
		this.bindingCompiler = new McpRuntimeTypedJsonBindingCompiler(
				compilationLimits);
		this.binder = new McpTypedJsonBinder(requireNonNull(bindingLimits));
	}

	@NonNull
	<T> McpCompiledRuntimeTypedSchema<T> compileSchema(
			@NonNull Type declaredType) {
		McpCompiledTypedSchema schema = schemaCompiler.compileSchema(
				requireNonNull(declaredType));
		return finish(declaredType, schema);
	}

	@NonNull
	<T> McpCompiledRuntimeTypedSchema<T> compileToolInput(
			@NonNull Type declaredType) {
		McpCompiledTypedSchema schema = schemaCompiler.compileToolInput(
				requireNonNull(declaredType));
		return finish(declaredType, schema);
	}

	@NonNull
	<T> McpCompiledRuntimeTypedSchema<T> compileToolOutput(
			@NonNull Type declaredType) {
		McpCompiledTypedSchema schema = schemaCompiler.compileToolOutput(
				requireNonNull(declaredType));
		return finish(declaredType, schema);
	}

	@NonNull
	private <T> McpCompiledRuntimeTypedSchema<T> finish(
			@NonNull Type declaredType,
			@NonNull McpCompiledTypedSchema schema) {
		McpTypedJsonBinding<T> binding = bindingCompiler.compile(declaredType,
				schema.shape());
		return new McpCompiledRuntimeTypedSchema<>(schema, binding, binder);
	}
}
