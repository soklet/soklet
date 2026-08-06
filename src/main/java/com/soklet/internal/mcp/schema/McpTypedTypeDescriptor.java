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

import javax.annotation.concurrent.NotThreadSafe;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Type-system-neutral description consumed by the one policy resolver.
 *
 * @param <T> the Java type representation
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
sealed interface McpTypedTypeDescriptor<T>
		permits McpTypedTypeDescriptor.Scalar,
		McpTypedTypeDescriptor.Enumeration,
		McpTypedTypeDescriptor.ArrayValue,
		McpTypedTypeDescriptor.ListValue,
		McpTypedTypeDescriptor.MapValue,
		McpTypedTypeDescriptor.OptionalValue,
		McpTypedTypeDescriptor.RecordValue,
		McpTypedTypeDescriptor.Unsupported {
	record Scalar<T>(@NonNull McpTypedSchemaScalar scalar)
			implements McpTypedTypeDescriptor<T> {
		public Scalar {
			requireNonNull(scalar);
		}
	}

	record Enumeration<T>(@NonNull String declarationIdentity,
			@NonNull List<@NonNull String> constants)
			implements McpTypedTypeDescriptor<T> {
		public Enumeration {
			requireNonNull(declarationIdentity);
			constants = List.copyOf(requireNonNull(constants));
			for (String constant : constants)
				requireNonNull(constant);
		}
	}

	record ArrayValue<T>(@NonNull T elementType)
			implements McpTypedTypeDescriptor<T> {
		public ArrayValue {
			requireNonNull(elementType);
		}
	}

	record ListValue<T>(@NonNull T elementType)
			implements McpTypedTypeDescriptor<T> {
		public ListValue {
			requireNonNull(elementType);
		}
	}

	record MapValue<T>(@NonNull T keyType, @NonNull T valueType)
			implements McpTypedTypeDescriptor<T> {
		public MapValue {
			requireNonNull(keyType);
			requireNonNull(valueType);
		}
	}

	record OptionalValue<T>(@NonNull T valueType)
			implements McpTypedTypeDescriptor<T> {
		public OptionalValue {
			requireNonNull(valueType);
		}
	}

	record RecordValue<T>(@NonNull String declarationIdentity,
			@NonNull List<@NonNull RecordComponent<@NonNull T>> components,
			int genericArgumentStructuralComplexity,
			@NonNull List<@NonNull T> screeningOnlyGenericArguments)
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

		RecordValue(@NonNull String declarationIdentity,
				@NonNull List<@NonNull RecordComponent<@NonNull T>> components) {
			this(declarationIdentity, components, 0, List.of());
		}

		RecordValue(@NonNull String declarationIdentity,
				@NonNull List<@NonNull RecordComponent<@NonNull T>> components,
				int genericArgumentStructuralComplexity) {
			this(declarationIdentity, components,
					genericArgumentStructuralComplexity, List.of());
		}
	}

	record Unsupported<T>(McpTypedSchemaException.@NonNull Reason reason)
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

	record RecordComponent<T>(@NonNull String name, @NonNull T type,
			@NonNull Optional<@NonNull String> title,
			@NonNull Optional<@NonNull String> description,
			@NonNull Optional<@NonNull String> headerName) {
		public RecordComponent {
			requireNonNull(name);
			requireNonNull(type);
			title = nonBlankMetadata(title);
			description = nonBlankMetadata(description);
			requireNonNull(headerName);
		}

		@NonNull
		static <T> RecordComponent<@NonNull T> fromNameAndType(
				@NonNull String name, @NonNull T type) {
			return new RecordComponent<>(name, type, Optional.empty(),
					Optional.empty(), Optional.empty());
		}

		@NonNull
		static <T> RecordComponent<@NonNull T> fromNameAndType(
				@NonNull String name, @NonNull T type,
				@NonNull String title, @NonNull String description) {
			return fromNameAndType(name, type, title, description,
					Optional.empty());
		}

		@NonNull
		static <T> RecordComponent<@NonNull T> fromNameAndType(
				@NonNull String name, @NonNull T type,
				@NonNull String title, @NonNull String description,
				@NonNull Optional<@NonNull String> headerName) {
			return new RecordComponent<>(name, type,
					optionalMetadata(title), optionalMetadata(description),
					requireNonNull(headerName));
		}

		@NonNull
		private static Optional<@NonNull String> optionalMetadata(
				@NonNull String value) {
			requireNonNull(value);
			return value.isBlank() ? Optional.empty() : Optional.of(value);
		}

		@NonNull
		private static Optional<@NonNull String> nonBlankMetadata(
				@NonNull Optional<@NonNull String> value) {
			requireNonNull(value);
			return value.filter(metadata -> !metadata.isBlank());
		}
	}
}
