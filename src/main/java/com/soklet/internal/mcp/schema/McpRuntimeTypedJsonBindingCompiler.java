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

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Compiles the reflection mechanics for an already-approved typed shape.
 *
 * <p>This class does not select supported schema types. It walks a supplied
 * {@link McpTypedSchemaShape} and its declared Java {@link Type} in lockstep;
 * disagreement is an internal invariant failure.</p>
 */
final class McpRuntimeTypedJsonBindingCompiler {
	private final McpSchemaCompilationLimits limits;
	private final ClassValue<Set<String>> enumConstantNamesByClass;

	McpRuntimeTypedJsonBindingCompiler(McpSchemaCompilationLimits limits) {
		this.limits = requireNonNull(limits);
		this.enumConstantNamesByClass = new ClassValue<>() {
			@Override
			protected Set<String> computeValue(Class<?> type) {
				Set<String> names = new LinkedHashSet<>();
				for (java.lang.reflect.Field field : type.getDeclaredFields()) {
					if (field.isEnumConstant())
						names.add(field.getName());
				}
				return Set.copyOf(names);
			}
		};
	}

	<T> McpTypedJsonBinding<T> compile(Type declaredType,
			McpTypedSchemaShape shape) {
		requireNonNull(declaredType);
		requireNonNull(shape);
		McpTypedJsonBindingNode rootNode = compileNode(declaredType, shape,
				McpTypedSchemaPath.root());
		return new McpTypedJsonBinding<>(declaredType, shape, rootNode);
	}

	private McpTypedJsonBindingNode compileNode(Type type,
			McpTypedSchemaShape shape, McpTypedSchemaPath path) {
		try {
			if (shape instanceof McpTypedSchemaShape.Scalar scalar)
				return compileScalar(type, scalar, path);
			if (shape instanceof McpTypedSchemaShape.Enumeration enumeration)
				return compileEnumeration(type, enumeration, path);
			if (shape instanceof McpTypedSchemaShape.ArrayValue array)
				return compileArrayOrList(type, array, path);
			if (shape instanceof McpTypedSchemaShape.MapValue map)
				return compileMap(type, map, path);
			if (shape instanceof McpTypedSchemaShape.RecordValue record)
				return compileRecord(type, record, path);
		} catch (McpTypedJsonBindingException exception) {
			throw exception;
		} catch (RuntimeException | LinkageError exception) {
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		}
		throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
				path);
	}

	private McpTypedJsonBindingNode compileScalar(Type type,
			McpTypedSchemaShape.Scalar shape, McpTypedSchemaPath path) {
		Class<?> javaType = classType(type, path);
		if (scalar(javaType) != shape.scalar())
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		return new McpTypedJsonBindingNode.Scalar(shape.scalar(),
				boxed(javaType));
	}

	@SuppressWarnings("unchecked")
	private McpTypedJsonBindingNode compileEnumeration(Type type,
			McpTypedSchemaShape.Enumeration shape, McpTypedSchemaPath path) {
		Class<?> enumType = classType(type, path);
		if (!enumType.isEnum())
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		Set<String> names = enumConstantNamesByClass.get(enumType);
		if (!names.equals(new LinkedHashSet<>(shape.constants())))
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		return new McpTypedJsonBindingNode.Enumeration(
				(Class<? extends Enum<?>>) enumType,
				new LinkedHashSet<>(shape.constants()));
	}

	private McpTypedJsonBindingNode compileArrayOrList(Type type,
			McpTypedSchemaShape.ArrayValue shape, McpTypedSchemaPath path) {
		if (type instanceof Class<?> typeClass && typeClass.isArray()) {
			Type elementType = requireNonNull(typeClass.getComponentType());
			return new McpTypedJsonBindingNode.ArrayValue(typeClass,
					typeClass.getComponentType(), compileNode(elementType,
							shape.elementShape(), path.arrayElement()));
		}
		if (type instanceof GenericArrayType genericArray) {
			Type elementType = requireNonNull(
					genericArray.getGenericComponentType());
			Class<?> componentType = erasedClass(elementType, path);
			Class<?> arrayType = Array.newInstance(componentType, 0).getClass();
			return new McpTypedJsonBindingNode.ArrayValue(arrayType,
					componentType, compileNode(elementType,
							shape.elementShape(), path.arrayElement()));
		}
		if (type instanceof ParameterizedType parameterized
				&& parameterized.getRawType() == List.class) {
			Type[] arguments = typeArguments(parameterized, path);
			if (arguments.length != 1)
				throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
						path);
			return new McpTypedJsonBindingNode.ListValue(compileNode(
					requireNonNull(arguments[0]), shape.elementShape(),
					path.arrayElement()));
		}
		throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
				path);
	}

	private McpTypedJsonBindingNode compileMap(Type type,
			McpTypedSchemaShape.MapValue shape, McpTypedSchemaPath path) {
		if (!(type instanceof ParameterizedType parameterized)
				|| parameterized.getRawType() != Map.class)
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		Type[] arguments = typeArguments(parameterized, path);
		if (arguments.length != 2 || arguments[0] != String.class)
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		return new McpTypedJsonBindingNode.MapValue(compileNode(
				requireNonNull(arguments[1]), shape.valueShape(), path.mapValue()));
	}

	private McpTypedJsonBindingNode compileRecord(Type type,
			McpTypedSchemaShape.RecordValue shape, McpTypedSchemaPath path) {
		Class<?> recordType = rawClass(type, path);
		if (!recordType.isRecord())
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);

		RecordComponent[] components = requireNonNull(
				recordType.getRecordComponents());
		if (components.length != shape.properties().size())
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		Map<TypeVariable<?>, Type> substitutions = substitutions(type,
				recordType, path);
		List<McpTypedJsonBindingNode.Property> properties =
				new ArrayList<>(components.length);
		Set<String> propertyNames = new LinkedHashSet<>();
		TypeSubstitution typeSubstitution = new TypeSubstitution();

		for (int index = 0; index < components.length; ++index) {
			RecordComponent component = requireNonNull(components[index]);
			McpTypedSchemaShape.Property property =
					shape.properties().get(index);
			McpTypedSchemaPath propertyPath = path.property(property.name());
			if (!component.getName().equals(property.name())
					|| !propertyNames.add(property.name()))
				throw failure(
						McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
						propertyPath);

			Type componentType = typeSubstitution.substitute(
					component.getGenericType(), substitutions, propertyPath);
			Optional<Type> optionalValueType = optionalValueType(componentType,
					propertyPath);
			boolean optional = optionalValueType.isPresent();
			if (property.required() == optional)
				throw failure(
						McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
						propertyPath);
			Type valueType = optionalValueType.orElse(componentType);
			McpTypedSchemaPath valuePath = optional
					? propertyPath.optionalValue() : propertyPath;
			Method accessor = component.getAccessor();
			makeAccessible(accessor, propertyPath);
			properties.add(new McpTypedJsonBindingNode.Property(property.name(),
					optional, accessor, compileNode(valueType, property.shape(),
							valuePath)));
		}

		Constructor<?> constructor;
		try {
			Class<?>[] parameterTypes = new Class<?>[components.length];
			for (int index = 0; index < components.length; ++index)
				parameterTypes[index] = components[index].getType();
			constructor = recordType.getDeclaredConstructor(parameterTypes);
		} catch (ReflectiveOperationException | RuntimeException
				| LinkageError exception) {
			throw failure(McpTypedJsonBindingException.Reason.REFLECTION_ACCESS,
					path);
		}
		makeAccessible(constructor, path);
		return new McpTypedJsonBindingNode.RecordValue(recordType, constructor,
				properties, propertyNames);
	}

	private Map<TypeVariable<?>, Type> substitutions(Type type,
			Class<?> rawType, McpTypedSchemaPath path) {
		TypeVariable<?>[] parameters = rawType.getTypeParameters();
		if (parameters.length == 0)
			return Map.of();
		if (!(type instanceof ParameterizedType parameterized))
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		Type[] arguments = typeArguments(parameterized, path);
		if (parameters.length != arguments.length)
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		Map<TypeVariable<?>, Type> substitutions = new LinkedHashMap<>();
		for (int index = 0; index < parameters.length; ++index)
			substitutions.put(parameters[index], requireNonNull(arguments[index]));
		return substitutions;
	}

	private Optional<Type> optionalValueType(Type type,
			McpTypedSchemaPath path) {
		if (!(type instanceof ParameterizedType parameterized)
				|| parameterized.getRawType() != Optional.class)
			return Optional.empty();
		Type[] arguments = typeArguments(parameterized, path);
		if (arguments.length != 1)
			return Optional.empty();
		return Optional.of(requireNonNull(arguments[0]));
	}

	private Type[] typeArguments(ParameterizedType type,
			McpTypedSchemaPath path) {
		Type[] arguments;
		try {
			arguments = requireNonNull(type.getActualTypeArguments()).clone();
		} catch (RuntimeException | LinkageError exception) {
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		}
		if (arguments.length > limits.maximumCollectionEntryCount())
			throw failure(
					McpTypedJsonBindingException.Limit.CONTAINER_ENTRY_COUNT,
					path);
		for (Type argument : arguments)
			requireNonNull(argument);
		return arguments;
	}

	private Class<?> rawClass(Type type, McpTypedSchemaPath path) {
		if (type instanceof Class<?> typeClass)
			return typeClass;
		if (type instanceof ParameterizedType parameterized
				&& parameterized.getRawType() instanceof Class<?> rawClass)
			return rawClass;
		throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
				path);
	}

	private Class<?> classType(Type type, McpTypedSchemaPath path) {
		if (type instanceof Class<?> typeClass)
			return typeClass;
		throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
				path);
	}

	private Class<?> erasedClass(Type type, McpTypedSchemaPath path) {
		if (type instanceof Class<?> typeClass)
			return typeClass;
		if (type instanceof ParameterizedType parameterized
				&& parameterized.getRawType() instanceof Class<?> rawClass)
			return rawClass;
		if (type instanceof GenericArrayType array) {
			Class<?> component = erasedClass(array.getGenericComponentType(), path);
			return Array.newInstance(component, 0).getClass();
		}
		throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
				path);
	}

	private void makeAccessible(Constructor<?> constructor,
			McpTypedSchemaPath path) {
		try {
			if (!constructor.trySetAccessible())
				throw failure(
						McpTypedJsonBindingException.Reason.REFLECTION_ACCESS,
						path);
		} catch (McpTypedJsonBindingException exception) {
			throw exception;
		} catch (RuntimeException | LinkageError exception) {
			throw failure(McpTypedJsonBindingException.Reason.REFLECTION_ACCESS,
					path);
		}
	}

	private void makeAccessible(Method method, McpTypedSchemaPath path) {
		try {
			if (!method.trySetAccessible())
				throw failure(
						McpTypedJsonBindingException.Reason.REFLECTION_ACCESS,
						path);
		} catch (McpTypedJsonBindingException exception) {
			throw exception;
		} catch (RuntimeException | LinkageError exception) {
			throw failure(McpTypedJsonBindingException.Reason.REFLECTION_ACCESS,
					path);
		}
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

	private Class<?> boxed(Class<?> type) {
		if (type == boolean.class)
			return Boolean.class;
		if (type == byte.class)
			return Byte.class;
		if (type == short.class)
			return Short.class;
		if (type == int.class)
			return Integer.class;
		if (type == long.class)
			return Long.class;
		if (type == float.class)
			return Float.class;
		if (type == double.class)
			return Double.class;
		return type;
	}

	private McpTypedJsonBindingException failure(
			McpTypedJsonBindingException.Reason reason,
			McpTypedSchemaPath path) {
		return new McpTypedJsonBindingException(
				McpTypedJsonBindingException.Operation.COMPILE, reason, path);
	}

	private McpTypedJsonBindingException failure(
			McpTypedJsonBindingException.Limit limit,
			McpTypedSchemaPath path) {
		return new McpTypedJsonBindingException(
				McpTypedJsonBindingException.Operation.COMPILE, limit, path);
	}

	private final class TypeSubstitution {
		private final Set<Type> activeTypes = Collections.newSetFromMap(
				new IdentityHashMap<>());
		private int visitedNodeCount;

		private Type substitute(Type type,
				Map<TypeVariable<?>, Type> substitutions,
				McpTypedSchemaPath path) {
			return substitute(type, substitutions, path, 1);
		}

		private Type substitute(Type type,
				Map<TypeVariable<?>, Type> substitutions,
				McpTypedSchemaPath path, int depth) {
			enter(type, path, depth);
			try {
				if (type instanceof TypeVariable<?> variable)
					return substitutions.getOrDefault(variable, variable);
				if (type instanceof GenericArrayType array)
					return new ResolvedGenericArrayType(substitute(
							array.getGenericComponentType(), substitutions, path,
							depth + 1));
				if (!(type instanceof ParameterizedType parameterized))
					return type;

				Type[] sourceArguments = typeArguments(parameterized, path);
				List<Type> arguments = new ArrayList<>(sourceArguments.length);
				for (Type argument : sourceArguments)
					arguments.add(substitute(argument, substitutions, path,
							depth + 1));
				Type owner = parameterized.getOwnerType();
				return new ResolvedParameterizedType(
						requireNonNull(parameterized.getRawType()),
						owner == null ? null : substitute(owner, substitutions,
								path, depth + 1), arguments);
			} finally {
				activeTypes.remove(type);
			}
		}

		private void enter(Type type, McpTypedSchemaPath path, int depth) {
			requireNonNull(type);
			if (depth > limits.maximumSchemaDepth())
				throw failure(McpTypedJsonBindingException.Limit.NESTING_DEPTH,
						path);
			if (visitedNodeCount >= limits.maximumSchemaNodeCount())
				throw failure(McpTypedJsonBindingException.Limit.NODE_COUNT, path);
			visitedNodeCount++;
			if (!activeTypes.add(type))
				throw failure(
						McpTypedJsonBindingException.Reason.TYPE_METADATA_CYCLE,
						path);
		}
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
