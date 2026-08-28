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

import com.soklet.annotation.McpToolProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
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
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRuntimeTypedJsonBindingCompiler {
	@NonNull
	private final McpSchemaCompilationLimits limits;
	@NonNull
	private final ClassValue<@NonNull Set<@NonNull String>> enumConstantNamesByClass;

	McpRuntimeTypedJsonBindingCompiler(
			@NonNull McpSchemaCompilationLimits limits) {
		this.limits = requireNonNull(limits);
		this.enumConstantNamesByClass = new ClassValue<>() {
			@Override
			@NonNull
			protected Set<@NonNull String> computeValue(
					@NonNull Class<?> type) {
				Set<String> names = new LinkedHashSet<>();
				for (java.lang.reflect.Field field : type.getDeclaredFields()) {
					if (field.isEnumConstant())
						names.add(field.getName());
				}
				return Set.copyOf(names);
			}
		};
	}

	@NonNull
	<T> McpTypedJsonBinding<T> compile(@NonNull Type declaredType,
			@NonNull McpTypedSchemaShape shape) {
		requireNonNull(declaredType);
		requireNonNull(shape);
		McpTypedJsonBindingNode rootNode = compileNode(declaredType, shape,
				McpTypedSchemaPath.root());
		return new McpTypedJsonBinding<>(declaredType, shape, rootNode);
	}

	@NonNull
	private McpTypedJsonBindingNode compileNode(@NonNull Type type,
			@NonNull McpTypedSchemaShape shape,
			@NonNull McpTypedSchemaPath path) {
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

	@NonNull
	private McpTypedJsonBindingNode compileScalar(@NonNull Type type,
			McpTypedSchemaShape.@NonNull Scalar shape,
			@NonNull McpTypedSchemaPath path) {
		Class<?> javaType = classType(type, path);
		if (scalar(javaType) != shape.scalar())
			throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
					path);
		return new McpTypedJsonBindingNode.Scalar(shape.scalar(),
				boxed(javaType));
	}

	@SuppressWarnings("unchecked")
	@NonNull
	private McpTypedJsonBindingNode compileEnumeration(@NonNull Type type,
			McpTypedSchemaShape.@NonNull Enumeration shape,
			@NonNull McpTypedSchemaPath path) {
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

	@NonNull
	private McpTypedJsonBindingNode compileArrayOrList(@NonNull Type type,
			McpTypedSchemaShape.@NonNull ArrayValue shape,
			@NonNull McpTypedSchemaPath path) {
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

	@NonNull
	private McpTypedJsonBindingNode compileMap(@NonNull Type type,
			McpTypedSchemaShape.@NonNull MapValue shape,
			@NonNull McpTypedSchemaPath path) {
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

	@NonNull
	private McpTypedJsonBindingNode compileRecord(@NonNull Type type,
			McpTypedSchemaShape.@NonNull RecordValue shape,
			@NonNull McpTypedSchemaPath path) {
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
			@Nullable McpToolProperty propertyMetadata = component.getAnnotation(
					McpToolProperty.class);
			String configuredName = propertyMetadata == null ? ""
					: requireNonNull(propertyMetadata.name());
			String publishedName = configuredName.isBlank()
					? component.getName() : configuredName;
			Optional<String> title = propertyMetadata == null ? Optional.empty()
					: optionalMetadata(requireNonNull(propertyMetadata.title()));
			Optional<String> description = propertyMetadata == null
					? Optional.empty()
					: optionalMetadata(requireNonNull(propertyMetadata.description()));
			if (!publishedName.equals(property.name())
					|| !title.equals(property.title())
					|| !description.equals(property.description())
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

	@NonNull
	private Optional<@NonNull String> optionalMetadata(
			@NonNull String value) {
		requireNonNull(value);
		return value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	@NonNull
	private Map<@NonNull TypeVariable<?>, @NonNull Type> substitutions(
			@NonNull Type type, @NonNull Class<?> rawType,
			@NonNull McpTypedSchemaPath path) {
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

	@NonNull
	private Optional<@NonNull Type> optionalValueType(@NonNull Type type,
			@NonNull McpTypedSchemaPath path) {
		if (!(type instanceof ParameterizedType parameterized)
				|| parameterized.getRawType() != Optional.class)
			return Optional.empty();
		Type[] arguments = typeArguments(parameterized, path);
		if (arguments.length != 1)
			return Optional.empty();
		return Optional.of(requireNonNull(arguments[0]));
	}

	private Type @NonNull [] typeArguments(
			@NonNull ParameterizedType type,
			@NonNull McpTypedSchemaPath path) {
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

	@NonNull
	private Class<?> rawClass(@NonNull Type type,
			@NonNull McpTypedSchemaPath path) {
		if (type instanceof Class<?> typeClass)
			return typeClass;
		if (type instanceof ParameterizedType parameterized
				&& parameterized.getRawType() instanceof Class<?> rawClass)
			return rawClass;
		throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
				path);
	}

	@NonNull
	private Class<?> classType(@NonNull Type type,
			@NonNull McpTypedSchemaPath path) {
		if (type instanceof Class<?> typeClass)
			return typeClass;
		throw failure(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
				path);
	}

	@NonNull
	private Class<?> erasedClass(@NonNull Type type,
			@NonNull McpTypedSchemaPath path) {
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

	private void makeAccessible(@NonNull Constructor<?> constructor,
			@NonNull McpTypedSchemaPath path) {
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

	private void makeAccessible(@NonNull Method method,
			@NonNull McpTypedSchemaPath path) {
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

	private @Nullable McpTypedSchemaScalar scalar(@NonNull Class<?> type) {
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

	@NonNull
	private Class<?> boxed(@NonNull Class<?> type) {
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

	@NonNull
	private McpTypedJsonBindingException failure(
			McpTypedJsonBindingException.@NonNull Reason reason,
			@NonNull McpTypedSchemaPath path) {
		return new McpTypedJsonBindingException(
				McpTypedJsonBindingException.Operation.COMPILE, reason, path);
	}

	@NonNull
	private McpTypedJsonBindingException failure(
			McpTypedJsonBindingException.@NonNull Limit limit,
			@NonNull McpTypedSchemaPath path) {
		return new McpTypedJsonBindingException(
				McpTypedJsonBindingException.Operation.COMPILE, limit, path);
	}

	@NotThreadSafe
	private final class TypeSubstitution {
		@NonNull
		private final Set<@NonNull Type> activeTypes = Collections.newSetFromMap(
				new IdentityHashMap<>());
		private int visitedNodeCount;

		@NonNull
		private Type substitute(@NonNull Type type,
				@NonNull Map<@NonNull TypeVariable<?>, @NonNull Type> substitutions,
				@NonNull McpTypedSchemaPath path) {
			return substitute(type, substitutions, path, 1);
		}

		@NonNull
		private Type substitute(@NonNull Type type,
				@NonNull Map<@NonNull TypeVariable<?>, @NonNull Type> substitutions,
				@NonNull McpTypedSchemaPath path, int depth) {
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

		private void enter(@NonNull Type type,
				@NonNull McpTypedSchemaPath path, int depth) {
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

	private record ResolvedGenericArrayType(@NonNull Type genericComponentType)
			implements GenericArrayType {
		private ResolvedGenericArrayType {
			requireNonNull(genericComponentType);
		}

		@Override
		@NonNull
		public Type getGenericComponentType() {
			return genericComponentType;
		}
	}

	private record ResolvedParameterizedType(@NonNull Type rawType,
			@Nullable Type ownerType,
			@NonNull List<@NonNull Type> arguments) implements ParameterizedType {
		private ResolvedParameterizedType {
			requireNonNull(rawType);
			arguments = List.copyOf(requireNonNull(arguments));
			for (Type argument : arguments)
				requireNonNull(argument);
		}

		@Override
		public Type @NonNull [] getActualTypeArguments() {
			return arguments.toArray(Type[]::new);
		}

		@Override
		@NonNull
		public Type getRawType() {
			return rawType;
		}

		@Override
		public @Nullable Type getOwnerType() {
			return ownerType;
		}
	}
}
