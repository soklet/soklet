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

package com.soklet;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Public programmatic MCP schema DSL.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public final class McpSchema {
	@NonNull
	private final McpObject value;

	private McpSchema(@NonNull McpObject value) {
		requireNonNull(value);
		this.value = value;
	}

	@NonNull
	public static ObjectBuilder object() {
		return new ObjectBuilder();
	}

	@NonNull
	public McpObject toValue() {
		return this.value;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other)
			return true;

		if (!(other instanceof McpSchema mcpSchema))
			return false;

		return this.value.equals(mcpSchema.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.value);
	}

	@Override
	public String toString() {
		return "McpSchema{value=%s}".formatted(this.value);
	}

	/**
	 * Builder for root-level object schemas.
	 */
	@NotThreadSafe
	public static final class ObjectBuilder {
		@NonNull
		private final Map<String, McpValue> properties;
		@NonNull
		private final Set<String> required;

		private ObjectBuilder() {
			this.properties = new LinkedHashMap<>();
			this.required = new LinkedHashSet<>();
		}

		@NonNull
		public ObjectBuilder required(@NonNull String name,
																	@NonNull McpType type) {
			requireNonNull(name);
			requireNonNull(type);
			this.properties.put(name, type.toSchemaValue());
			this.required.add(name);
			return this;
		}

		@NonNull
		public ObjectBuilder optional(@NonNull String name,
																	@NonNull McpType type) {
			requireNonNull(name);
			requireNonNull(type);
			this.properties.put(name, type.toSchemaValue());
			this.required.remove(name);
			return this;
		}

		@NonNull
		public ObjectBuilder requiredEnum(@NonNull String name,
																			@NonNull String... values) {
			requireNonNull(name);
			requireNonNull(values);
			this.properties.put(name, enumSchema(values));
			this.required.add(name);
			return this;
		}

		@NonNull
		public ObjectBuilder optionalEnum(@NonNull String name,
																			@NonNull String... values) {
			requireNonNull(name);
			requireNonNull(values);
			this.properties.put(name, enumSchema(values));
			this.required.remove(name);
			return this;
		}

		@NonNull
		public McpSchema build() {
			Map<String, McpValue> value = new LinkedHashMap<>();
			value.put("type", new McpString("object"));
			value.put("properties", new McpObject(this.properties));
			value.put("additionalProperties", new McpBoolean(false));

			if (!this.required.isEmpty()) {
				List<McpValue> requiredValues = new ArrayList<>();

				for (String requiredName : this.required)
					requiredValues.add(new McpString(requiredName));

				value.put("required", new McpArray(requiredValues));
			}

			return new McpSchema(new McpObject(value));
		}

		@NonNull
		private static McpObject enumSchema(@NonNull String... values) {
			requireNonNull(values);

			List<McpValue> enumValues = new ArrayList<>(values.length);

			for (String value : values) {
				requireNonNull(value);
				enumValues.add(new McpString(value));
			}

			return new McpObject(Map.of(
					"type", new McpString("string"),
					"enum", new McpArray(enumValues)
			));
		}
	}
}
