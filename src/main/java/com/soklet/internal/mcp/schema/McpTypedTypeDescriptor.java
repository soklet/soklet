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

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Type-system-neutral description consumed by the one policy resolver. */
sealed interface McpTypedTypeDescriptor<T>
		permits McpTypedTypeDescriptor.Scalar,
		McpTypedTypeDescriptor.Enumeration,
		McpTypedTypeDescriptor.ArrayValue,
		McpTypedTypeDescriptor.ListValue,
		McpTypedTypeDescriptor.MapValue,
		McpTypedTypeDescriptor.OptionalValue,
		McpTypedTypeDescriptor.RecordValue,
		McpTypedTypeDescriptor.Unsupported {
	record Scalar<T>(McpTypedSchemaScalar scalar)
			implements McpTypedTypeDescriptor<T> {
		public Scalar {
			requireNonNull(scalar);
		}
	}

	record Enumeration<T>(String declarationIdentity, List<String> constants)
			implements McpTypedTypeDescriptor<T> {
		public Enumeration {
			requireNonNull(declarationIdentity);
			constants = List.copyOf(requireNonNull(constants));
			for (String constant : constants)
				requireNonNull(constant);
		}
	}

	record ArrayValue<T>(T elementType)
			implements McpTypedTypeDescriptor<T> {
		public ArrayValue {
			requireNonNull(elementType);
		}
	}

	record ListValue<T>(T elementType)
			implements McpTypedTypeDescriptor<T> {
		public ListValue {
			requireNonNull(elementType);
		}
	}

	record MapValue<T>(T keyType, T valueType)
			implements McpTypedTypeDescriptor<T> {
		public MapValue {
			requireNonNull(keyType);
			requireNonNull(valueType);
		}
	}

	record OptionalValue<T>(T valueType)
			implements McpTypedTypeDescriptor<T> {
		public OptionalValue {
			requireNonNull(valueType);
		}
	}

	record RecordValue<T>(String declarationIdentity,
			List<RecordComponent<T>> components,
			int genericArgumentStructuralComplexity,
			List<T> screeningOnlyGenericArguments)
			implements McpTypedTypeDescriptor<T> {
		public RecordValue {
			requireNonNull(declarationIdentity);
			components = List.copyOf(requireNonNull(components));
			for (RecordComponent<T> component : components)
				requireNonNull(component);
			if (genericArgumentStructuralComplexity < 0)
				throw new IllegalArgumentException(
						"Generic-argument structural complexity must not be negative.");
			screeningOnlyGenericArguments = List.copyOf(
					requireNonNull(screeningOnlyGenericArguments));
			for (T genericArgument : screeningOnlyGenericArguments)
				requireNonNull(genericArgument);
			if (!screeningOnlyGenericArguments.isEmpty()
					&& genericArgumentStructuralComplexity == 0)
				throw new IllegalArgumentException(
						"Screening-only generic arguments require positive structural complexity.");
			if (screeningOnlyGenericArguments.size()
					> genericArgumentStructuralComplexity)
				throw new IllegalArgumentException(
						"Generic-argument structural complexity must cover every screening-only argument.");
		}

		RecordValue(String declarationIdentity,
				List<RecordComponent<T>> components) {
			this(declarationIdentity, components, 0, List.of());
		}

		RecordValue(String declarationIdentity,
				List<RecordComponent<T>> components,
				int genericArgumentStructuralComplexity) {
			this(declarationIdentity, components,
					genericArgumentStructuralComplexity, List.of());
		}
	}

	record Unsupported<T>(McpTypedSchemaException.Reason reason)
			implements McpTypedTypeDescriptor<T> {
		public Unsupported {
			requireNonNull(reason);
			if (reason == McpTypedSchemaException.Reason.LIMIT_EXCEEDED
					|| reason == McpTypedSchemaException.Reason.RECURSIVE_TYPE
					|| reason == McpTypedSchemaException.Reason.DUPLICATE_PROPERTY)
				throw new IllegalArgumentException(
						"The unsupported descriptor reason is not a type classification.");
		}
	}

	record RecordComponent<T>(String name, T type, Optional<String> title,
			Optional<String> description, Optional<String> headerName) {
		public RecordComponent {
			requireNonNull(name);
			requireNonNull(type);
			requireNonNull(title);
			requireNonNull(description);
			requireNonNull(headerName);
		}

		static <T> RecordComponent<T> fromNameAndType(String name, T type) {
			return new RecordComponent<>(name, type, Optional.empty(),
					Optional.empty(), Optional.empty());
		}
	}
}
