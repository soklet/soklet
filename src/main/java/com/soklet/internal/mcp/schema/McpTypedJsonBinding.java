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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable runtime mechanics paired with one already-resolved typed schema
 * shape.
 *
 * @param <T> the bound Java type
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@SuppressWarnings("UnusedTypeParameter")
record McpTypedJsonBinding<T>(@NonNull Type declaredType,
		@NonNull McpTypedSchemaShape shape,
		@NonNull McpTypedJsonBindingNode rootNode) {
	McpTypedJsonBinding {
		requireNonNull(declaredType);
		requireNonNull(shape);
		requireNonNull(rootNode);
	}
}

/**
 * Runtime-only mechanics. Support policy remains in
 * {@link McpTypedSchemaShape}; these nodes retain only the Java information
 * required to perform a conversion.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
sealed interface McpTypedJsonBindingNode permits
		McpTypedJsonBindingNode.Scalar,
		McpTypedJsonBindingNode.Enumeration,
		McpTypedJsonBindingNode.ArrayValue,
		McpTypedJsonBindingNode.ListValue,
		McpTypedJsonBindingNode.MapValue,
		McpTypedJsonBindingNode.RecordValue {
	record Scalar(@NonNull McpTypedSchemaScalar scalar,
			@NonNull Class<?> javaType)
			implements McpTypedJsonBindingNode {
		public Scalar {
			requireNonNull(scalar);
			requireNonNull(javaType);
		}
	}

	record Enumeration(@NonNull Class<? extends @NonNull Enum<?>> enumType,
			@NonNull Set<@NonNull String> constantNames)
			implements McpTypedJsonBindingNode {
		public Enumeration {
			requireNonNull(enumType);
			constantNames = Set.copyOf(requireNonNull(constantNames));
			for (String constantName : constantNames)
				requireNonNull(constantName);
		}
	}

	record ArrayValue(@NonNull Class<?> arrayType,
			@NonNull Class<?> componentType,
			@NonNull McpTypedJsonBindingNode elementNode)
			implements McpTypedJsonBindingNode {
		public ArrayValue {
			requireNonNull(arrayType);
			requireNonNull(componentType);
			requireNonNull(elementNode);
			if (!arrayType.isArray())
				throw new IllegalArgumentException("arrayType must be an array.");
		}
	}

	record ListValue(@NonNull McpTypedJsonBindingNode elementNode)
			implements McpTypedJsonBindingNode {
		public ListValue {
			requireNonNull(elementNode);
		}
	}

	record MapValue(@NonNull McpTypedJsonBindingNode valueNode)
			implements McpTypedJsonBindingNode {
		public MapValue {
			requireNonNull(valueNode);
		}
	}

	record RecordValue(@NonNull Class<?> recordType,
			@NonNull Constructor<?> constructor,
			@NonNull List<@NonNull Property> properties,
			@NonNull Set<@NonNull String> propertyNames)
			implements McpTypedJsonBindingNode {
		public RecordValue {
			requireNonNull(recordType);
			requireNonNull(constructor);
			properties = List.copyOf(requireNonNull(properties));
			propertyNames = Set.copyOf(requireNonNull(propertyNames));
			for (Property property : properties)
				requireNonNull(property);
			if (!recordType.isRecord())
				throw new IllegalArgumentException("recordType must be a record.");
			if (propertyNames.size() != properties.size())
				throw new IllegalArgumentException(
						"Record property names must be unique.");
		}
	}

	record Property(@NonNull String name, boolean optional,
			@NonNull Method accessor,
			@NonNull McpTypedJsonBindingNode valueNode) {
		public Property {
			requireNonNull(name);
			requireNonNull(accessor);
			requireNonNull(valueNode);
		}
	}
}
