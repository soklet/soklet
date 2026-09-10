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

package com.soklet.converter;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Convenience superclass which provides default implementations of {@link ValueConverter} methods.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public abstract class AbstractValueConverter<F, T> implements ValueConverter<F, T> {
	@NonNull
	private final Type fromType;
	@NonNull
	private final Type toType;

	/**
	 * Supports subclasses that have both 'from' and 'to' generic types.
	 */
	public AbstractValueConverter() {
		List<Type> genericTypes = genericTypesForClass(getClass());

		Type fromType = null;
		Type toType = null;

		if (genericTypes.size() == 2) {
			fromType = genericTypes.get(0);
			toType = genericTypes.get(1);
		}

		if (fromType == null || toType == null)
			throw unableToExtractGenericTypes();

		this.fromType = fromType;
		this.toType = toType;
	}

	/**
	 * Supports subclasses that have only a 'to' generic type, like {@link FromStringValueConverter}.
	 *
	 * @param fromType an explicitly-provided 'from' type
	 */
	public AbstractValueConverter(@NonNull Type fromType) {
		requireNonNull(fromType);

		List<Type> genericTypes = genericTypesForClass(getClass());

		Type toType = null;

		if (genericTypes.size() == 2)
			toType = genericTypes.get(1);

		if (toType == null)
			throw unableToExtractGenericTypes();

		this.fromType = fromType;
		this.toType = toType;
	}

	/*
	 * Internal escape hatch for framework converters whose intentionally erased
	 * types cannot be recovered from a concrete subclass. Keeping this
	 * package-private prevents application converters from bypassing the
	 * hierarchy validation performed by the public constructors.
	 */
	AbstractValueConverter(@NonNull Type fromType, @NonNull Type toType) {
		this.fromType = requireNonNull(fromType);
		this.toType = requireNonNull(toType);
	}

	@NonNull
	@Override
	@SuppressWarnings("unchecked")
	public final Optional<@NonNull T> convert(@Nullable F from) throws ValueConversionException {
		// Special handling for String types
		if (from instanceof String && shouldTrimFromValues())
			from = (F) trimAggressivelyToNull((String) from);

		try {
			return performConversion(from);
		} catch (ValueConversionException e) {
			throw e;
		} catch (Exception e) {
			throw new ValueConversionException(format(
					"Unable to convert a value of type %s to an instance of %s",
					getFromType(), getToType()), e, getFromType(), from, getToType());
		}
	}

	@NonNull
	protected Boolean shouldTrimFromValues() {
		return true;
	}

	/**
	 * Subclasses must implement this method to convert a 'from' instance to a 'to' instance.
	 *
	 * @param from the instance we are converting from
	 * @return an instance that was converted to
	 * @throws Exception if an error occured during conversion
	 */
	@NonNull
	public abstract Optional<@NonNull T> performConversion(@Nullable F from) throws Exception;

	@Override
	@NonNull
	public Type getFromType() {
		return this.fromType;
	}

	@Override
	@NonNull
	public Type getToType() {
		return this.toType;
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), getFromType(), getToType());
	}

	@NonNull
	static List<Type> genericTypesForClass(@Nullable Class<?> valueConverterClass) {
		if (valueConverterClass == null)
			return List.of();
		return genericTypesFor(valueConverterClass, Map.of(), new HashSet<>());
	}

	@NonNull
	private IllegalStateException unableToExtractGenericTypes() {
		return new IllegalStateException(format(
				"Unable to extract generic %s type information from %s",
				ValueConverter.class.getSimpleName(), getClass().getName()));
	}

	@NonNull
	private static List<Type> genericTypesFor(@Nullable Type candidate,
			@NonNull Map<@NonNull TypeVariable<?>, @NonNull Type> inherited,
			@NonNull Set<@NonNull Class<?>> activeClasses) {
		if (candidate == null)
			return List.of();

		Class<?> rawClass;
		Map<TypeVariable<?>, Type> substitutions =
				new LinkedHashMap<>(inherited);
		if (candidate instanceof ParameterizedType parameterized) {
			if (!(parameterized.getRawType() instanceof Class<?> candidateClass))
				return List.of();
			rawClass = candidateClass;
			TypeVariable<?>[] variables = rawClass.getTypeParameters();
			Type[] arguments = parameterized.getActualTypeArguments();
			if (variables.length != arguments.length)
				return List.of();
			for (int index = 0; index < variables.length; ++index)
				substitutions.put(variables[index],
						resolve(arguments[index], inherited));
		} else if (candidate instanceof Class<?> candidateClass) {
			rawClass = candidateClass;
		} else {
			return List.of();
		}

		if (rawClass == ValueConverter.class) {
			List<Type> resolved = new ArrayList<>(2);
			for (TypeVariable<?> variable : rawClass.getTypeParameters()) {
				Type type = resolve(variable, substitutions);
				if (containsUnresolvedTypeVariable(type))
					return List.of();
				resolved.add(type);
			}
			return List.copyOf(resolved);
		}
		if (!ValueConverter.class.isAssignableFrom(rawClass)
				|| !activeClasses.add(rawClass))
			return List.of();

		try {
			for (Type genericInterface : rawClass.getGenericInterfaces()) {
				List<Type> resolved = genericTypesFor(genericInterface,
						substitutions, activeClasses);
				if (!resolved.isEmpty())
					return resolved;
			}
			return genericTypesFor(rawClass.getGenericSuperclass(),
					substitutions, activeClasses);
		} finally {
			activeClasses.remove(rawClass);
		}
	}

	@NonNull
	private static Type resolve(@NonNull Type type,
			@NonNull Map<@NonNull TypeVariable<?>, @NonNull Type> substitutions) {
		if (type instanceof TypeVariable<?> variable) {
			Type replacement = substitutions.get(variable);
			return replacement == null || replacement == variable
					? variable : resolve(replacement, substitutions);
		}
		if (type instanceof GenericArrayType array) {
			Type componentType = resolve(array.getGenericComponentType(),
					substitutions);
			if (componentType instanceof Class<?> componentClass)
				return Array.newInstance(componentClass, 0).getClass();
			return new ResolvedGenericArrayType(componentType);
		}
		if (type instanceof WildcardType wildcard)
			return new ResolvedWildcardType(
					resolve(wildcard.getUpperBounds(), substitutions),
					resolve(wildcard.getLowerBounds(), substitutions));
		if (!(type instanceof ParameterizedType parameterized))
			return type;

		Type[] sourceArguments = parameterized.getActualTypeArguments();
		Type[] resolvedArguments = new Type[sourceArguments.length];
		for (int index = 0; index < sourceArguments.length; ++index)
			resolvedArguments[index] = resolve(sourceArguments[index], substitutions);
		Type owner = parameterized.getOwnerType();
		return new ResolvedParameterizedType(parameterized.getRawType(),
				owner == null ? null : resolve(owner, substitutions),
				resolvedArguments);
	}

	private static Type @NonNull [] resolve(Type @NonNull [] types,
			@NonNull Map<@NonNull TypeVariable<?>, @NonNull Type> substitutions) {
		Type[] resolved = new Type[types.length];
		for (int index = 0; index < types.length; ++index)
			resolved[index] = resolve(types[index], substitutions);
		return resolved;
	}

	private static boolean containsUnresolvedTypeVariable(@NonNull Type type) {
		if (type instanceof TypeVariable<?>)
			return true;
		if (type instanceof GenericArrayType array)
			return containsUnresolvedTypeVariable(array.getGenericComponentType());
		if (type instanceof WildcardType wildcard) {
			for (Type bound : wildcard.getUpperBounds())
				if (containsUnresolvedTypeVariable(bound))
					return true;
			for (Type bound : wildcard.getLowerBounds())
				if (containsUnresolvedTypeVariable(bound))
					return true;
		}
		if (type instanceof ParameterizedType parameterized) {
			for (Type argument : parameterized.getActualTypeArguments())
				if (containsUnresolvedTypeVariable(argument))
					return true;
		}
		return false;
	}

	private static final class ResolvedGenericArrayType
			implements GenericArrayType {
		@NonNull
		private final Type componentType;

		private ResolvedGenericArrayType(@NonNull Type componentType) {
			this.componentType = requireNonNull(componentType);
		}

		@Override
		@NonNull
		public Type getGenericComponentType() {
			return componentType;
		}

		@Override
		public boolean equals(@Nullable Object object) {
			return object instanceof GenericArrayType array
					&& componentType.equals(array.getGenericComponentType());
		}

		@Override
		public int hashCode() {
			return componentType.hashCode();
		}

		@Override
		@NonNull
		public String toString() {
			return componentType.getTypeName() + "[]";
		}
	}

	private static final class ResolvedWildcardType implements WildcardType {
		private final Type @NonNull [] upperBounds;
		private final Type @NonNull [] lowerBounds;

		private ResolvedWildcardType(Type @NonNull [] upperBounds,
				Type @NonNull [] lowerBounds) {
			this.upperBounds = requireNonNull(upperBounds).clone();
			this.lowerBounds = requireNonNull(lowerBounds).clone();
			for (Type bound : this.upperBounds)
				requireNonNull(bound);
			for (Type bound : this.lowerBounds)
				requireNonNull(bound);
		}

		@Override
		public Type @NonNull [] getUpperBounds() {
			return upperBounds.clone();
		}

		@Override
		public Type @NonNull [] getLowerBounds() {
			return lowerBounds.clone();
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (!(object instanceof WildcardType wildcard))
				return false;
			return java.util.Arrays.equals(upperBounds,
					wildcard.getUpperBounds())
					&& java.util.Arrays.equals(lowerBounds,
					wildcard.getLowerBounds());
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(upperBounds)
					^ java.util.Arrays.hashCode(lowerBounds);
		}

		@Override
		@NonNull
		public String toString() {
			if (lowerBounds.length > 0)
				return "? super " + joinedTypeNames(lowerBounds);
			if (upperBounds.length == 0
					|| upperBounds.length == 1
					&& upperBounds[0] == Object.class)
				return "?";
			return "? extends " + joinedTypeNames(upperBounds);
		}

		@NonNull
		private static String joinedTypeNames(Type @NonNull [] types) {
			StringBuilder names = new StringBuilder();
			for (int index = 0; index < types.length; ++index) {
				if (index > 0)
					names.append(" & ");
				names.append(types[index].getTypeName());
			}
			return names.toString();
		}
	}

	private static final class ResolvedParameterizedType
			implements ParameterizedType {
		@NonNull
		private final Type rawType;
		@Nullable
		private final Type ownerType;
		private final Type @NonNull [] arguments;

		private ResolvedParameterizedType(@NonNull Type rawType,
				@Nullable Type ownerType, Type @NonNull [] arguments) {
			this.rawType = requireNonNull(rawType);
			this.ownerType = ownerType;
			this.arguments = requireNonNull(arguments).clone();
			for (Type argument : this.arguments)
				requireNonNull(argument);
		}

		@Override
		public Type @NonNull [] getActualTypeArguments() {
			return arguments.clone();
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

		@Override
		public boolean equals(@Nullable Object object) {
			if (!(object instanceof ParameterizedType parameterized))
				return false;
			return Objects.equals(ownerType, parameterized.getOwnerType())
					&& rawType.equals(parameterized.getRawType())
					&& java.util.Arrays.equals(arguments,
							parameterized.getActualTypeArguments());
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(arguments)
					^ Objects.hashCode(ownerType) ^ rawType.hashCode();
		}

		@Override
		@NonNull
		public String toString() {
			StringBuilder typeName = new StringBuilder(rawType.getTypeName());
			if (arguments.length == 0)
				return typeName.toString();
			typeName.append('<');
			for (int index = 0; index < arguments.length; ++index) {
				if (index > 0)
					typeName.append(", ");
				typeName.append(arguments[index].getTypeName());
			}
			return typeName.append('>').toString();
		}
	}
}
