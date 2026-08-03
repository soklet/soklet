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

import static java.util.Objects.requireNonNull;

/**
 * One registration-ready runtime schema and its matching intrinsic binding.
 */
final class McpCompiledRuntimeTypedSchema<T> {
	private final McpCompiledTypedSchema schema;
	private final McpTypedJsonBinding<T> binding;
	private final McpTypedJsonBinder binder;

	McpCompiledRuntimeTypedSchema(McpCompiledTypedSchema schema,
			McpTypedJsonBinding<T> binding, McpTypedJsonBinder binder) {
		this.schema = requireNonNull(schema);
		this.binding = requireNonNull(binding);
		this.binder = requireNonNull(binder);
		if (!schema.shape().equals(binding.shape()))
			throw new IllegalArgumentException(
					"A runtime binding must use the compiled schema shape.");
	}

	McpCompiledTypedSchema schema() {
		return schema;
	}

	T fromJson(McpJsonValue value) {
		return binder.fromJson(value, binding);
	}

	McpJsonValue toJson(T value) {
		return binder.toJson(value, binding);
	}
}
