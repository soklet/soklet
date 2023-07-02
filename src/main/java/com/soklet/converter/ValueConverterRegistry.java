/*
 * Copyright 2022 Revetware LLC.
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

import static com.soklet.converter.ValueConverters.defaultValueConverters;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ValueConverterRegistry {
	@Nonnull
	private static final ValueConverter<?, ?> REFLEXIVE_VALUE_CONVERTER;

	@Nonnull
	private final ConcurrentHashMap<CacheKey, ValueConverter<?, ?>> valueConverterCache;

	static {
		REFLEXIVE_VALUE_CONVERTER = new ReflexiveValueConverter<>();
	}

	public ValueConverterRegistry() {
		this.valueConverterCache = new ConcurrentHashMap<>();
		initializeDefaultValueConverters();
	}

	@Nonnull
	public Boolean add(@Nonnull ValueConverter<?, ?> valueConverter) {
		requireNonNull(valueConverter);
		return getValueConverterCache().put(extractCacheKeyFromValueConverter(valueConverter), valueConverter) != null;
	}

	@Nonnull
	public List<Boolean> addAll(@Nonnull Iterable<ValueConverter<?, ?>> valueConverters) {
		requireNonNull(valueConverters);
		return StreamSupport.stream(valueConverters.spliterator(), false)
				.map(valueConverter -> add(valueConverter))
				.collect(toList());
	}

	@Nonnull
	public Boolean remove(@Nonnull ValueConverter<?, ?> valueConverter) {
		requireNonNull(valueConverter);
		return getValueConverterCache().remove(extractCacheKeyFromValueConverter(valueConverter)) != null;
	}

	@Nonnull
	public Boolean remove(@Nonnull TypeReference<?> fromTypeReference,
												@Nonnull TypeReference<?> toTypeReference) {
		requireNonNull(fromTypeReference);
		requireNonNull(toTypeReference);

		return remove(fromTypeReference.getType(), toTypeReference.getType());
	}

	@Nonnull
	public Boolean remove(@Nonnull Type fromType,
												@Nonnull Type toType) {
		requireNonNull(fromType);
		requireNonNull(toType);

		return getValueConverterCache().remove(new CacheKey(fromType, toType)) != null;
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

		if (fromType.equals(toType))
			return Optional.of((ValueConverter<F, T>) REFLEXIVE_VALUE_CONVERTER);

		ValueConverter<F, T> valueConverter = (ValueConverter<F, T>) getValueConverterCache().get(new CacheKey(fromType, toType));

		// Special case for enums.
		// If no converter was registered for converting a String to an Enum<?>, create a simple converter and cache it off
		if (valueConverter == null && String.class.equals(fromType) && toType instanceof @SuppressWarnings("rawtypes")Class toClass) {

			if (toClass.isEnum()) {
				valueConverter = new ValueConverter<>() {
					@Override
					@Nullable
					public T convert(@Nullable Object from) throws ValueConversionException {
						if (from == null)
							return null;

						try {
							return (T) Enum.valueOf(toClass, from.toString());
						} catch (Exception e) {
							throw new ValueConversionException(format("Unable to convert value '%s' of type %s to an instance of %s",
									from, getFromType(), getToType()), e, getFromType(), getToType());
						}
					}

					@Override
					@Nonnull
					public Type getFromType() {
						return fromType;
					}

					@Override
					@Nonnull
					public Type getToType() {
						return toType;
					}

					@Override
					@Nonnull
					public String toString() {
						return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), getFromType(), getToType());
					}
				};

				getValueConverterCache().putIfAbsent(new CacheKey(fromType, toType), valueConverter);
			}
		}

		return Optional.ofNullable(valueConverter);
	}

	@Nonnull
	protected CacheKey extractCacheKeyFromValueConverter(@Nonnull ValueConverter<?, ?> valueConverter) {
		requireNonNull(valueConverter);
		return new CacheKey(valueConverter.getFromType(), valueConverter.getToType());
	}

	/**
	 * Hook for subclasses to provide different default converters.
	 */
	protected void initializeDefaultValueConverters() {
		addAll(defaultValueConverters());
	}

	@Nonnull
	protected ConcurrentHashMap<CacheKey, ValueConverter<?, ?>> getValueConverterCache() {
		return valueConverterCache;
	}

	@Nonnull
	@Immutable
	private static final class ReflexiveValueConverter<T> extends AbstractValueConverter<T, T> {
		@Override
		@Nullable
		public T convert(@Nullable T from) throws ValueConversionException {
			return from;
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