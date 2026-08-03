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
import org.jspecify.annotations.Nullable;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Reflection adapter for the shared typed-schema policy resolver. */
final class McpRuntimeTypedTypeModel implements McpTypedTypeModel<Type> {
	private static final Set<String> FRAMEWORK_ROOT_TYPE_NAMES = Set.of(
			"com.soklet.McpJsonValue",
			"com.soklet.McpOperationResult",
			"com.soklet.McpCompletePayload",
			"com.soklet.McpContentBlock",
			"com.soklet.McpResourceContents");

	private final McpSchemaCompilationLimits limits;
	private final McpRuntimeEnumNameReader enumNameReader;
	private final Set<String> frameworkRootTypeNames;
	private final ClassValue<McpTypedTypeDescriptor.Enumeration<Type>>
			enumerationsByClass;

	McpRuntimeTypedTypeModel(McpSchemaCompilationLimits limits) {
		this(limits, FRAMEWORK_ROOT_TYPE_NAMES);
	}

	McpRuntimeTypedTypeModel(McpSchemaCompilationLimits limits,
			Set<String> frameworkRootTypeNames) {
		this.limits = requireNonNull(limits);
		this.frameworkRootTypeNames = Set.copyOf(
				requireNonNull(frameworkRootTypeNames));
		this.enumNameReader = new McpRuntimeEnumNameReader(
				limits.maximumCollectionEntryCount(),
				limits.maximumNameLengthInCharacters());
		this.enumerationsByClass = new ClassValue<>() {
			@Override
			protected McpTypedTypeDescriptor.Enumeration<Type> computeValue(
					Class<?> type) {
				return new McpTypedTypeDescriptor.Enumeration<>(type.getName(),
						enumNameReader.read(type));
			}
		};
	}

	@Override
	public McpTypedTypeDescriptor<Type> describe(Type type) {
		requireNonNull(type);
		if (type instanceof Class<?> typeClass)
			return describeClass(typeClass);
		if (type instanceof ParameterizedType parameterizedType)
			return describeParameterized(parameterizedType);
		if (type instanceof GenericArrayType genericArrayType)
			return describeGenericArray(genericArrayType);
		if (type instanceof WildcardType)
			return new McpTypedTypeDescriptor.Unsupported<>(
					McpTypedSchemaException.Reason.WILDCARD);
		if (type instanceof TypeVariable<?>)
			return new McpTypedTypeDescriptor.Unsupported<>(
					McpTypedSchemaException.Reason.UNRESOLVED_TYPE_VARIABLE);
		return new McpTypedTypeDescriptor.Unsupported<>(
				McpTypedSchemaException.Reason.UNSUPPORTED_TYPE);
	}

	private McpTypedTypeDescriptor<Type> describeClass(Class<?> type) {
		McpTypedSchemaScalar scalar = scalar(type);
		if (scalar != null)
			return new McpTypedTypeDescriptor.Scalar<>(scalar);
		if (type == Object.class)
			return unsupported(McpTypedSchemaException.Reason.OBJECT_TYPE);
		if (CharSequence.class.isAssignableFrom(type))
			return unsupported(McpTypedSchemaException.Reason.CHAR_SEQUENCE_TYPE);
		if (frameworkType(type))
			return unsupported(McpTypedSchemaException.Reason.FRAMEWORK_TYPE);
		if (type.isArray())
			return new McpTypedTypeDescriptor.ArrayValue<>(
					type.getComponentType());
		if (type == List.class || type == Map.class || type == Optional.class
				|| type.getTypeParameters().length > 0)
			return unsupported(McpTypedSchemaException.Reason.RAW_GENERIC);
		if (type.isEnum())
			return enumerationsByClass.get(type);
		if (type.isRecord())
			return recordDescriptor(type, Map.of(), new Type[0], 0);
		return unsupported(McpTypedSchemaException.Reason.UNSUPPORTED_TYPE);
	}

	private McpTypedTypeDescriptor<Type> describeParameterized(
			ParameterizedType type) {
		Type rawType = requireNonNull(type.getRawType());
		if (!(rawType instanceof Class<?> rawClass))
			throw new IllegalArgumentException(
					"A parameterized type must have a Class raw type.");

		Type[] arguments = typeArguments(type);
		if (rawClass == List.class)
			return arguments.length == 1
					? new McpTypedTypeDescriptor.ListValue<>(arguments[0])
					: unsupported(McpTypedSchemaException.Reason.RAW_GENERIC);
		if (rawClass == Map.class)
			return arguments.length == 2
					? new McpTypedTypeDescriptor.MapValue<>(arguments[0], arguments[1])
					: unsupported(McpTypedSchemaException.Reason.RAW_GENERIC);
		if (rawClass == Optional.class)
			return arguments.length == 1
					? new McpTypedTypeDescriptor.OptionalValue<>(arguments[0])
					: unsupported(McpTypedSchemaException.Reason.RAW_GENERIC);
		if (!rawClass.isRecord())
			return unsupported(McpTypedSchemaException.Reason.UNSUPPORTED_TYPE);

		TypeVariable<?>[] parameters = rawClass.getTypeParameters();
		if (parameters.length != arguments.length)
			return unsupported(McpTypedSchemaException.Reason.RAW_GENERIC);
		int genericArgumentStructuralComplexity =
				new GenericTraversal().structuralComplexity(arguments);
		Map<TypeVariable<?>, Type> substitutions = new LinkedHashMap<>();
		for (int index = 0; index < parameters.length; ++index)
			substitutions.put(parameters[index], arguments[index]);
		return recordDescriptor(rawClass, substitutions, arguments,
				genericArgumentStructuralComplexity);
	}

	private McpTypedTypeDescriptor<Type> describeGenericArray(
			GenericArrayType type) {
		Type component = requireNonNull(type.getGenericComponentType());
		if (new GenericTraversal().containsUnresolved(component))
			return unsupported(
					McpTypedSchemaException.Reason.UNRESOLVED_GENERIC_ARRAY_COMPONENT);
		return new McpTypedTypeDescriptor.ArrayValue<>(component);
	}

	private McpTypedTypeDescriptor.RecordValue<Type> recordDescriptor(
			Class<?> recordClass, Map<TypeVariable<?>, Type> substitutions,
			Type[] actualArguments,
			int genericArgumentStructuralComplexity) {
		RecordComponent[] components = requireNonNull(
				recordClass.getRecordComponents());
		if (components.length > limits.maximumCollectionEntryCount())
			throw new McpTypedTypeModelLimitException(
					McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
					"Record component count exceeds its configured limit.");
		for (RecordComponent component : components) {
			if (component.getName().length()
					> limits.maximumNameLengthInCharacters())
				throw new McpTypedTypeModelLimitException(
						McpSchemaCompilationException.Limit.NAME_LENGTH,
						"Record component name exceeds its configured limit.");
		}

		List<Type> sourceComponentTypes = new ArrayList<>(components.length);
		for (RecordComponent component : components)
			sourceComponentTypes.add(requireNonNull(component.getGenericType()));

		Set<TypeVariable<?>> declaredParameters = new LinkedHashSet<>();
		Collections.addAll(declaredParameters, recordClass.getTypeParameters());
		Set<TypeVariable<?>> usedParameters = new LinkedHashSet<>();
		GenericTraversal usageTraversal = new GenericTraversal();
		for (Type sourceComponentType : sourceComponentTypes)
			usageTraversal.collectReferencedParameters(sourceComponentType,
					declaredParameters, usedParameters);
		List<Type> screeningOnlyGenericArguments = new ArrayList<>();
		TypeVariable<?>[] parameters = recordClass.getTypeParameters();
		if (parameters.length != actualArguments.length)
			throw new IllegalArgumentException(
					"Record generic parameters and arguments must have equal arity.");
		for (int index = 0; index < parameters.length; ++index) {
			if (!usedParameters.contains(parameters[index]))
				screeningOnlyGenericArguments.add(actualArguments[index]);
		}

		List<McpTypedTypeDescriptor.RecordComponent<Type>> described =
				new ArrayList<>(components.length);
		GenericTraversal substitutionTraversal = new GenericTraversal();
		for (int index = 0; index < components.length; ++index) {
			RecordComponent component = components[index];
			Type componentType = substitutionTraversal.substitute(
					sourceComponentTypes.get(index), substitutions);
			described.add(McpTypedTypeDescriptor.RecordComponent
					.fromNameAndType(component.getName(), componentType));
		}
		return new McpTypedTypeDescriptor.RecordValue<>(recordClass.getName(),
				described, genericArgumentStructuralComplexity,
				screeningOnlyGenericArguments);
	}

	private Type[] typeArguments(ParameterizedType type) {
		Type[] arguments = requireNonNull(type.getActualTypeArguments());
		if (arguments.length > limits.maximumCollectionEntryCount())
			throw new McpTypedTypeModelLimitException(
					McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
					"Generic argument count exceeds its configured limit.");
		arguments = arguments.clone();
		for (Type argument : arguments)
			requireNonNull(argument);
		return arguments;
	}

	private Type[] wildcardBounds(Type[] bounds) {
		requireNonNull(bounds);
		if (bounds.length > limits.maximumCollectionEntryCount())
			throw new McpTypedTypeModelLimitException(
					McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
					"Wildcard bound count exceeds its configured limit.");
		bounds = bounds.clone();
		for (Type bound : bounds)
			requireNonNull(bound);
		return bounds;
	}

	private final class GenericTraversal {
		private final Set<Type> activeTypes =
				Collections.newSetFromMap(new IdentityHashMap<>());
		private int visitedNodeCount;

		private Type substitute(Type type,
				Map<TypeVariable<?>, Type> substitutions) {
			return substitute(type, substitutions, 1);
		}

		private Type substitute(Type type,
				Map<TypeVariable<?>, Type> substitutions, int depth) {
			enter(type, depth);
			try {
				if (type instanceof TypeVariable<?> variable)
					return substitutions.getOrDefault(variable, variable);
				if (type instanceof GenericArrayType array)
					return new ResolvedGenericArrayType(substitute(
							array.getGenericComponentType(), substitutions,
							depth + 1));
				if (!(type instanceof ParameterizedType parameterized))
					return type;

				Type[] sourceArguments = typeArguments(parameterized);
				List<Type> arguments = new ArrayList<>(sourceArguments.length);
				for (Type argument : sourceArguments)
					arguments.add(substitute(argument, substitutions, depth + 1));
				Type owner = parameterized.getOwnerType();
				return new ResolvedParameterizedType(
						requireNonNull(parameterized.getRawType()),
						owner == null ? null
								: substitute(owner, substitutions, depth + 1),
						arguments);
			} finally {
				exit(type);
			}
		}

		private boolean containsUnresolved(Type type) {
			return containsUnresolved(type, 1);
		}

		private boolean containsUnresolved(Type type, int depth) {
			enter(type, depth);
			try {
				if (type instanceof TypeVariable<?> || type instanceof WildcardType)
					return true;
				if (type instanceof GenericArrayType array)
					return containsUnresolved(array.getGenericComponentType(),
							depth + 1);
				if (type instanceof Class<?> typeClass)
					return typeClass.isArray()
							&& containsUnresolved(typeClass.getComponentType(),
									depth + 1);
				if (type instanceof ParameterizedType parameterized) {
					Type owner = parameterized.getOwnerType();
					if (owner != null && containsUnresolved(owner, depth + 1))
						return true;
					for (Type argument : typeArguments(parameterized)) {
						if (containsUnresolved(argument, depth + 1))
							return true;
					}
				}
				return false;
			} finally {
				exit(type);
			}
		}

		private int structuralComplexity(Type[] types) {
			for (Type type : types)
				visitStructure(type, 1);
			return visitedNodeCount;
		}

		private void visitStructure(Type type, int depth) {
			enter(type, depth);
			try {
				if (type instanceof GenericArrayType array) {
					visitStructure(array.getGenericComponentType(), depth + 1);
				} else if (type instanceof Class<?> typeClass
						&& typeClass.isArray()) {
					visitStructure(typeClass.getComponentType(), depth + 1);
				} else if (type instanceof ParameterizedType parameterized) {
					Type owner = parameterized.getOwnerType();
					if (owner != null)
						visitStructure(owner, depth + 1);
					for (Type argument : typeArguments(parameterized))
						visitStructure(argument, depth + 1);
				}
			} finally {
				exit(type);
			}
		}

		private void collectReferencedParameters(Type type,
				Set<TypeVariable<?>> declaredParameters,
				Set<TypeVariable<?>> destination) {
			collectReferencedParameters(type, declaredParameters, destination, 1);
		}

		private void collectReferencedParameters(Type type,
				Set<TypeVariable<?>> declaredParameters,
				Set<TypeVariable<?>> destination, int depth) {
			enter(type, depth);
			try {
				if (type instanceof TypeVariable<?> variable) {
					if (declaredParameters.contains(variable))
						destination.add(variable);
					return;
				}
				if (type instanceof GenericArrayType array) {
					collectReferencedParameters(array.getGenericComponentType(),
							declaredParameters, destination, depth + 1);
					return;
				}
				if (type instanceof Class<?> typeClass) {
					if (typeClass.isArray())
						collectReferencedParameters(typeClass.getComponentType(),
								declaredParameters, destination, depth + 1);
					return;
				}
				if (type instanceof ParameterizedType parameterized) {
					Type owner = parameterized.getOwnerType();
					if (owner != null)
						collectReferencedParameters(owner, declaredParameters,
								destination, depth + 1);
					for (Type argument : typeArguments(parameterized))
						collectReferencedParameters(argument, declaredParameters,
								destination, depth + 1);
					return;
				}
				if (type instanceof WildcardType wildcard) {
					for (Type bound : wildcardBounds(wildcard.getLowerBounds()))
						collectReferencedParameters(bound, declaredParameters,
								destination, depth + 1);
					for (Type bound : wildcardBounds(wildcard.getUpperBounds()))
						collectReferencedParameters(bound, declaredParameters,
								destination, depth + 1);
				}
			} finally {
				exit(type);
			}
		}

		private void enter(Type type, int depth) {
			requireNonNull(type);
			if (depth > limits.maximumSchemaDepth())
				throw new McpTypedTypeModelLimitException(
						McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
						"Generic type structure exceeds its configured depth limit.");
			if (visitedNodeCount >= limits.maximumSchemaNodeCount())
				throw new McpTypedTypeModelLimitException(
						McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						"Generic type structure exceeds its configured node limit.");
			visitedNodeCount++;
			if (!activeTypes.add(type))
				throw new IllegalArgumentException(
						"Generic type metadata contains an identity cycle.");
		}

		private void exit(Type type) {
			activeTypes.remove(type);
		}
	}

	private boolean frameworkType(Class<?> type) {
		if (McpJsonValue.class.isAssignableFrom(type))
			return true;

		Set<Class<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<Class<?>> pending = new ArrayDeque<>();
		pending.add(type);
		int scheduledNodeCount = 1;
		while (!pending.isEmpty()) {
			Class<?> current = pending.removeFirst();
			if (!visited.add(current))
				continue;
			if (frameworkRootTypeNames.contains(current.getName()))
				return true;

			Class<?> superclass = current.getSuperclass();
			if (superclass != null) {
				scheduledNodeCount = checkHierarchyNodeLimit(scheduledNodeCount);
				pending.addLast(superclass);
			}
			Class<?>[] interfaces = requireNonNull(current.getInterfaces());
			if (interfaces.length > limits.maximumCollectionEntryCount())
				throw new McpTypedTypeModelLimitException(
						McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
						"Java interface count exceeds its configured limit.");
			for (Class<?> implementedInterface : interfaces) {
				scheduledNodeCount = checkHierarchyNodeLimit(scheduledNodeCount);
				pending.addLast(requireNonNull(implementedInterface));
			}
		}
		return false;
	}

	private int checkHierarchyNodeLimit(int scheduledNodeCount) {
		if (scheduledNodeCount >= limits.maximumSchemaNodeCount())
			throw new McpTypedTypeModelLimitException(
					McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
					"Java type hierarchy exceeds its configured traversal limit.");
		return scheduledNodeCount + 1;
	}

	private @Nullable McpTypedSchemaScalar scalar(Class<?> type) {
		if (type == boolean.class || type == Boolean.class)
			return McpTypedSchemaScalar.BOOLEAN;
		if (type == byte.class || type == Byte.class)
			return McpTypedSchemaScalar.BYTE;
		if (type == short.class || type == Short.class)
			return McpTypedSchemaScalar.SHORT;
		if (type == int.class || type == Integer.class)
			return McpTypedSchemaScalar.INT;
		if (type == long.class || type == Long.class)
			return McpTypedSchemaScalar.LONG;
		if (type == BigInteger.class)
			return McpTypedSchemaScalar.BIG_INTEGER;
		if (type == float.class || type == Float.class)
			return McpTypedSchemaScalar.FLOAT;
		if (type == double.class || type == Double.class)
			return McpTypedSchemaScalar.DOUBLE;
		if (type == BigDecimal.class)
			return McpTypedSchemaScalar.BIG_DECIMAL;
		if (type == String.class)
			return McpTypedSchemaScalar.STRING;
		return null;
	}

	private McpTypedTypeDescriptor<Type> unsupported(
			McpTypedSchemaException.Reason reason) {
		return new McpTypedTypeDescriptor.Unsupported<>(reason);
	}

	private record ResolvedGenericArrayType(Type genericComponentType)
			implements GenericArrayType {
		private ResolvedGenericArrayType {
			requireNonNull(genericComponentType);
		}

		@Override
		public Type getGenericComponentType() {
			return genericComponentType;
		}
	}

	private record ResolvedParameterizedType(Type rawType,
			@Nullable Type ownerType,
			List<Type> arguments) implements ParameterizedType {
		private ResolvedParameterizedType {
			requireNonNull(rawType);
			arguments = List.copyOf(requireNonNull(arguments));
			for (Type argument : arguments)
				requireNonNull(argument);
		}

		@Override
		public Type[] getActualTypeArguments() {
			return arguments.toArray(Type[]::new);
		}

		@Override
		public Type getRawType() {
			return rawType;
		}

		@Override
		public @Nullable Type getOwnerType() {
			return ownerType;
		}
	}
}
