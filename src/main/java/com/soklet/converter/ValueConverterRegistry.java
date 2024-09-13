/*
 * Copyright 2022-2024 Revetware LLC.
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

import com.soklet.core.TypeReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ValueConverterRegistry {
	@Nonnull
	private static final ValueConverterRegistry SHARED_INSTANCE;
	@Nonnull
	private static final ValueConverter<?, ?> REFLEXIVE_VALUE_CONVERTER;
	@Nonnull
	private static final Map<Type, Type> PRIMITIVE_TYPES_TO_NONPRIMITIVE_EQUIVALENTS;

	static {
		REFLEXIVE_VALUE_CONVERTER = new ReflexiveValueConverter<>();
		SHARED_INSTANCE = new ValueConverterRegistry();

		// See https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
		PRIMITIVE_TYPES_TO_NONPRIMITIVE_EQUIVALENTS = Map.of(
				int.class, Integer.class,
				long.class, Long.class,
				double.class, Double.class,
				float.class, Float.class,
				boolean.class, Boolean.class,
				char.class, Character.class,
				byte.class, Byte.class,
				short.class, Short.class
		);
	}

	// This is explicitly typed as a ConcurrentHashMap because we may silently accumulate additional converters over time
	// and this serves as a reminder that the Map instance must be threadsafe to accommodate.
	//
	// Use case: as new enum types are encountered, ValueConverter instances are generated and cached off.
	// From a user's perspective, it would be burdensome to register converters for these ahead of time -
	// it's preferable to have enum conversion "just work" for string names, which is almost always what's desired.
	@Nonnull
	private final ConcurrentHashMap<CacheKey, ValueConverter<?, ?>> valueConvertersByCacheKey;

	@Nonnull
	public static ValueConverterRegistry sharedInstance() {
		return SHARED_INSTANCE;
	}

	public ValueConverterRegistry() {
		this(Set.of());
	}

	public ValueConverterRegistry(@Nonnull Set<ValueConverter<?, ?>> customValueConverters) {
		requireNonNull(customValueConverters);

		Set<ValueConverter<?, ?>> defaultValueConverters = ValueConverters.defaultValueConverters();
		ConcurrentHashMap<CacheKey, ValueConverter<?, ?>> valueConvertersByCacheKey = new ConcurrentHashMap<>(
				defaultValueConverters.size()
						+ customValueConverters.size()
						+ 1 // reflexive converter
						+ 100 // leave a little headroom for enum types that might accumulate over time
		);

		// By default, we include out-of-the-box converters
		for (ValueConverter<?, ?> defaultValueConverter : defaultValueConverters)
			valueConvertersByCacheKey.put(extractCacheKeyFromValueConverter(defaultValueConverter), defaultValueConverter);

		// We also include a "reflexive" converter which knows how to convert a type to itself
		valueConvertersByCacheKey.put(extractCacheKeyFromValueConverter(REFLEXIVE_VALUE_CONVERTER), REFLEXIVE_VALUE_CONVERTER);

		// Finally, register any additional converters that were provided
		for (ValueConverter<?, ?> valueConverter : customValueConverters)
			valueConvertersByCacheKey.put(extractCacheKeyFromValueConverter(valueConverter), valueConverter);

		this.valueConvertersByCacheKey = valueConvertersByCacheKey;
	}

	@Nonnull
	public <F, T> Optional<ValueConverter<F, T>> get(@Nonnull TypeReference<F> fromTypeReference,
																									 @Nonnull TypeReference<T> toTypeReference) {
		requireNonNull(fromTypeReference);
		requireNonNull(toTypeReference);

		return get(fromTypeReference.getType(), toTypeReference.getType());
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public <F, T> Optional<ValueConverter<F, T>> get(@Nonnull Type fromType,
																									 @Nonnull Type toType) {
		requireNonNull(fromType);
		requireNonNull(toType);

		Type normalizedFromType = normalizePrimitiveTypeIfNecessary(fromType);
		Type normalizedToType = normalizePrimitiveTypeIfNecessary(toType);

		// Reflexive case: from == to
		if (normalizedFromType.equals(normalizedToType))
			return Optional.of((ValueConverter<F, T>) REFLEXIVE_VALUE_CONVERTER);

		CacheKey cacheKey = new CacheKey(normalizedFromType, normalizedToType);
		ValueConverter<F, T> valueConverter = (ValueConverter<F, T>) getValueConvertersByCacheKey().get(cacheKey);

		// Special case for enums.
		// If no converter was registered for converting a String to an Enum<?>, create a simple converter and cache it off
		if (valueConverter == null && String.class.equals(normalizedFromType) && toType instanceof @SuppressWarnings("rawtypes")Class toClass) {
			if (toClass.isEnum()) {
				valueConverter = new ValueConverter<>() {
					@Override
					@Nonnull
					public Optional<T> convert(@Nullable Object from) throws ValueConversionException {
						if (from == null)
							return null;

						try {
							return Optional.ofNullable((T) Enum.valueOf(toClass, from.toString()));
						} catch (Exception e) {
							throw new ValueConversionException(format("Unable to convert value '%s' of type %s to an instance of %s",
									from, getFromType(), getToType()), e, getFromType(), from, getToType());
						}
					}

					@Override
					@Nonnull
					public Type getFromType() {
						return normalizedFromType;
					}

					@Override
					@Nonnull
					public Type getToType() {
						return normalizedToType;
					}

					@Override
					@Nonnull
					public String toString() {
						return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), getFromType(), getToType());
					}
				};

				getValueConvertersByCacheKey().putIfAbsent(new CacheKey(normalizedFromType, normalizedToType), valueConverter);
			}
		}

		return Optional.ofNullable(valueConverter);
	}

	@Nonnull
	protected Type normalizePrimitiveTypeIfNecessary(@Nonnull Type type) {
		requireNonNull(type);

		Type nonprimitiveEquivalent = PRIMITIVE_TYPES_TO_NONPRIMITIVE_EQUIVALENTS.get(type);
		return nonprimitiveEquivalent == null ? type : nonprimitiveEquivalent;
	}

	@Nonnull
	protected CacheKey extractCacheKeyFromValueConverter(@Nonnull ValueConverter<?, ?> valueConverter) {
		requireNonNull(valueConverter);
		return new CacheKey(valueConverter.getFromType(), valueConverter.getToType());
	}

	@Nonnull
	protected Map<CacheKey, ValueConverter<?, ?>> getValueConvertersByCacheKey() {
		return this.valueConvertersByCacheKey;
	}

	@Nonnull
	@Immutable
	private static final class ReflexiveValueConverter<T> extends AbstractValueConverter<T, T> {
		@Nonnull
		@Override
		public Optional<T> performConversion(@Nullable T from) throws Exception {
			return Optional.ofNullable(from);
		}
	}

	@ThreadSafe
	protected static final class CacheKey {
		@Nonnull
		private final Type fromType;
		@Nonnull
		private final Type toType;

		public CacheKey(@Nonnull Type fromType,
										@Nonnull Type toType) {
			requireNonNull(fromType);
			requireNonNull(toType);

			this.fromType = fromType;
			this.toType = toType;
		}

		@Override
		public String toString() {
			return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), getFromType(), getToType());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;
			if (!(object instanceof CacheKey cacheKey))
				return false;

			return Objects.equals(getFromType(), cacheKey.getFromType()) && Objects.equals(getToType(), cacheKey.getToType());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getFromType(), getToType());
		}

		@Nonnull
		public Type getFromType() {
			return this.fromType;
		}

		@Nonnull
		public Type getToType() {
			return this.toType;
		}
	}
}