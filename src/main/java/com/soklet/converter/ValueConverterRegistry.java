/*
 * Copyright 2015 Transmogrify LLC.
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

import static com.soklet.converter.ValueConverters.defaultValueConverters;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.FINEST;
import static java.util.stream.Collectors.toList;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import com.soklet.util.TypeReference;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class ValueConverterRegistry {
  private final ConcurrentHashMap<CacheKey, ValueConverter<?, ?>> valueConverterCache = new ConcurrentHashMap<>();
  private final Logger logger = Logger.getLogger(ValueConverterRegistry.class.getName());

  public ValueConverterRegistry() {
    initializeDefaultValueConverters();
  }

  public boolean add(ValueConverter<?, ?> valueConverter) {
    requireNonNull(valueConverter);

    if (logger.isLoggable(FINEST))
      logger.finest(format("Registering ValueConverter for %s -> %s", valueConverter.fromType(),
        valueConverter.toType()));

    return valueConverterCache.put(extractCacheKeyFromValueConverter(valueConverter), valueConverter) != null;
  }

  public List<Boolean> addAll(Iterable<ValueConverter<?, ?>> valueConverters) {
    requireNonNull(valueConverters);
    return StreamSupport.stream(valueConverters.spliterator(), false).map(valueConverter -> add(valueConverter))
      .collect(toList());
  }

  public boolean remove(ValueConverter<?, ?> valueConverter) {
    requireNonNull(valueConverter);
    return valueConverterCache.remove(extractCacheKeyFromValueConverter(valueConverter)) != null;
  }

  public boolean remove(TypeReference<?> fromTypeReference, TypeReference<?> toTypeReference) {
    requireNonNull(fromTypeReference);
    requireNonNull(toTypeReference);
    return remove(fromTypeReference.type(), toTypeReference.type());
  }

  public boolean remove(Type fromType, Type toType) {
    requireNonNull(fromType);
    requireNonNull(toType);
    return valueConverterCache.remove(new CacheKey(fromType, toType)) != null;
  }

  public <F, T> Optional<ValueConverter<F, T>> get(TypeReference<F> fromTypeReference, TypeReference<T> toTypeReference) {
    requireNonNull(fromTypeReference);
    requireNonNull(toTypeReference);

    return get(fromTypeReference.type(), toTypeReference.type());
  }

  @SuppressWarnings("unchecked")
  public <F, T> Optional<ValueConverter<F, T>> get(Type fromType, Type toType) {
    requireNonNull(fromType);
    requireNonNull(toType);

    if (fromType.equals(toType))
      return Optional.of((ValueConverter<F, T>) REFLEXIVE_VALUE_CONVERTER);

    ValueConverter<F, T> valueConverter =
        (ValueConverter<F, T>) valueConverterCache.get(new CacheKey(fromType, toType));

    // Special case for enums
    if (valueConverter == null && String.class.equals(fromType) && toType instanceof Class) {
      @SuppressWarnings("rawtypes")
      Class toClass = (Class) toType;

      if (toClass.isEnum()) {
        valueConverter = new AbstractValueConverter<F, T>() {
          @Override
          public T convert(Object from) throws ValueConversionException {
            if (from == null)
              return null;

            try {
              return (T) Enum.valueOf(toClass, from.toString());
            } catch (Exception e) {
              throw new ValueConversionException(format("Unable to convert value '%s' of type %s to an instance of %s",
                from, fromType(), toType()), e, fromType(), toType());
            }
          }

          @Override
          public String toString() {
            return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), fromType(), toType());
          }
        };
      }
    }

    return Optional.ofNullable(valueConverter);
  }

  protected CacheKey extractCacheKeyFromValueConverter(ValueConverter<?, ?> valueConverter) {
    requireNonNull(valueConverter);
    return new CacheKey(valueConverter.fromType(), valueConverter.toType());
  }

  /**
   * Hook for subclasses to provide different default converters.
   */
  protected void initializeDefaultValueConverters() {
    addAll(defaultValueConverters());
  }

  private static final ValueConverter<?, ?> REFLEXIVE_VALUE_CONVERTER = new ReflexiveValueConverter<Object, Object>();

  private static final class ReflexiveValueConverter<T, T2> extends AbstractValueConverter<T, T> {
    @Override
    public T convert(T from) throws ValueConversionException {
      return from;
    }
  }

  private static final class CacheKey {
    private final Type fromType;
    private final Type toType;

    private CacheKey(Type fromType, Type toType) {
      this.fromType = requireNonNull(fromType);
      this.toType = requireNonNull(toType);
    }

    @Override
    public String toString() {
      return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), getFromType(), getToType());
    }

    @Override
    public boolean equals(Object object) {
      if (this == object)
        return true;
      if (!(object instanceof CacheKey))
        return false;

      CacheKey cacheKey = (CacheKey) object;

      return Objects.equals(getFromType(), cacheKey.getFromType()) && Objects.equals(getToType(), cacheKey.getToType());
    }

    @Override
    public int hashCode() {
      return Objects.hash(getFromType(), getToType());
    }

    private Type getFromType() {
      return fromType;
    }

    private Type getToType() {
      return toType;
    }
  }
}