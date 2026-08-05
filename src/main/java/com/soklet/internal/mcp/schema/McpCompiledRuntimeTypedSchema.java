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

import com.soklet.internal.mcp.protocol.McpJsonValue;
import com.soklet.internal.mcp.protocol.McpMirroredHeaderPlan;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * One registration-ready runtime schema and its matching intrinsic binding.
 *
 * @param <T> the bound Java type
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpCompiledRuntimeTypedSchema<T> {
	@NonNull
	private final McpCompiledTypedSchema schema;
	@NonNull
	private final McpTypedJsonBinding<T> binding;
	@NonNull
	private final McpTypedJsonBinder binder;

	McpCompiledRuntimeTypedSchema(@NonNull McpCompiledTypedSchema schema,
			@NonNull McpTypedJsonBinding<T> binding,
			@NonNull McpTypedJsonBinder binder) {
		this.schema = requireNonNull(schema);
		this.binding = requireNonNull(binding);
		this.binder = requireNonNull(binder);
		if (!schema.shape().equals(binding.shape()))
			throw new IllegalArgumentException(
					"A runtime binding must use the compiled schema shape.");
	}

	@NonNull
	McpCompiledTypedSchema schema() {
		return schema;
	}

	@NonNull
	McpMirroredHeaderPlan mirroredHeaderPlan() {
		return schema.mirroredHeaderPlan();
	}

	@NonNull
	T fromJson(@Nullable McpJsonValue value) {
		return binder.fromJson(value, binding);
	}

	@NonNull
	McpJsonValue toJson(@Nullable T value) {
		return binder.toJson(value, binding);
	}
}
