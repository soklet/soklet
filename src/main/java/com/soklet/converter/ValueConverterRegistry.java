/*
 * Copyright 2022-2025 Revetware LLC.
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
 * A collection of {@link ValueConverter} instances, supplemented with quality-of-life features that most applications need.
 * <p>
 * For example, the registry will automatically generate and cache off {@link ValueConverter} instances when a requested 'from' type is {@link String}
 * and the 'to' type is an {@link Enum} if no converter was previously specified (this is almost always the behavior you want).
 * <p>
 * The registry will also perform primitive mapping when locating {@link ValueConverter} instances.
 * For example, if a requested 'from' {@link String} and 'to' {@code int} are specified and that converter does not exist, but a 'from' {@link String} and 'to' {@link Integer} does exist, it will be returned.
 * <p>
 * Finally, reflexive {@link ValueConverter} instances are automatically created and cached off when the requested 'from' and 'to' types are identical.
 * <p>
 * Value conversion is documented in detail at <a href="https://www.soklet.com/docs/value-conversions">https://www.soklet.com/docs/value-conversions</a>.
 *
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

	/**
	 * The system's default shared registry instance.
	 *
	 * @return the shared registry instance
	 */
	@Nonnull
	public static ValueConverterRegistry sharedInstance() {
		return SHARED_INSTANCE;
	}

	/**
	 * Creates a registry with a sensible default set of converters as specified by {@link ValueConverters#defaultValueConverters()}.
	 */
	public ValueConverterRegistry() {
		this(Set.of());
	}

	/**
	 * Creates a registry with a sensible default set of converters as specified by {@link ValueConverters#defaultValueConverters()}, optionally supplemented with custom converters.
	 *
	 * @param customValueConverters the custom value converters to include in the registry
	 */
	public ValueConverterRegistry(@Nullable Set<ValueConverter<?, ?>> customValueConverters) {
		if (customValueConverters == null)
			customValueConverters = Set.of();

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

	/**
	 * Obtains a {@link ValueConverter} that matches the 'from' and 'to' type references specified.
	 * <p>
	 * Because of type erasure, you cannot directly express a generic type like <code>List&lt;String&gt;.class</code>.
	 * You must encode it as a type parameter - in this case, <code>new TypeReference&lt;List&lt;String&gt;&gt;() &#123;&#125;</code>.
	 *
	 * @param fromTypeReference reference to the 'from' type of the converter
	 * @param toTypeReference   reference to the 'to' type of the converter
	 * @param <F>               the 'from' type
	 * @param <T>               the 'to' type
	 * @return a matching {@link ValueConverter}, or {@link Optional#empty()} if not found
	 */
	@Nonnull
	public <F, T> Optional<ValueConverter<F, T>> get(@Nonnull TypeReference<F> fromTypeReference,
																									 @Nonnull TypeReference<T> toTypeReference) {
		requireNonNull(fromTypeReference);
		requireNonNull(toTypeReference);

		return getInternal(fromTypeReference.getType(), toTypeReference.getType());
	}

	/**
	 * Obtain a {@link ValueConverter} that matches the 'from' and 'to' types specified.
	 *
	 * @param fromType the 'from' type
	 * @param toType   the 'to' type
	 * @return a matching {@link ValueConverter}, or {@link Optional#empty()} if not found
	 */
	@Nonnull
	public Optional<ValueConverter<Object, Object>> get(@Nonnull Type fromType,
																											@Nonnull Type toType) {
		requireNonNull(fromType);
		requireNonNull(toType);

		return getInternal(fromType, toType);
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	protected <F, T> Optional<ValueConverter<F, T>> getInternal(@Nonnull Type fromType,
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
							return Optional.empty();

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