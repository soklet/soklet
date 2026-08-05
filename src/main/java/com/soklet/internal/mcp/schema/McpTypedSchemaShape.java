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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Language-neutral, immutable shape used by derivation and conversion.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
sealed interface McpTypedSchemaShape permits McpTypedSchemaShape.Scalar,
		McpTypedSchemaShape.Enumeration, McpTypedSchemaShape.ArrayValue,
		McpTypedSchemaShape.MapValue, McpTypedSchemaShape.RecordValue {
	record Scalar(@NonNull McpTypedSchemaScalar scalar)
			implements McpTypedSchemaShape {
		public Scalar {
			requireNonNull(scalar);
		}
	}

	record Enumeration(@NonNull List<@NonNull String> constants)
			implements McpTypedSchemaShape {
		public Enumeration {
			constants = List.copyOf(requireNonNull(constants));
			for (String constant : constants)
				requireNonNull(constant);
			if (new LinkedHashSet<>(constants).size() != constants.size())
				throw new IllegalArgumentException(
						"Enum constant names must be unique.");
		}
	}

	record ArrayValue(@NonNull McpTypedSchemaShape elementShape)
			implements McpTypedSchemaShape {
		public ArrayValue {
			requireNonNull(elementShape);
		}
	}

	record MapValue(@NonNull McpTypedSchemaShape valueShape)
			implements McpTypedSchemaShape {
		public MapValue {
			requireNonNull(valueShape);
		}
	}

	record RecordValue(@NonNull List<@NonNull Property> properties)
			implements McpTypedSchemaShape {
		public RecordValue {
			properties = List.copyOf(requireNonNull(properties));
			Set<@NonNull String> names = new LinkedHashSet<>();
			for (Property property : properties) {
				requireNonNull(property);
				if (!names.add(property.name()))
					throw new IllegalArgumentException(
							"Record property names must be unique.");
			}
		}
	}

	record Property(@NonNull String name,
			@NonNull McpTypedSchemaShape shape, boolean required,
			@NonNull Optional<@NonNull String> title,
			@NonNull Optional<@NonNull String> description,
			@NonNull Optional<@NonNull String> headerName) {
		public Property {
			requireNonNull(name);
			requireNonNull(shape);
			requireNonNull(title);
			requireNonNull(description);
			requireNonNull(headerName);
		}

		@NonNull
		static Property fromNameAndShape(@NonNull String name,
				@NonNull McpTypedSchemaShape shape, boolean required) {
			return new Property(name, shape, required, Optional.empty(),
					Optional.empty(), Optional.empty());
		}
	}
}
