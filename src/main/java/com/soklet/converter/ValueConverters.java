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

import static java.lang.String.format;
import static java.util.Collections.unmodifiableSet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public final class ValueConverters {
  private static final Set<ValueConverter<?, ?>> DEFAULT_VALUE_CONVERTERS;

  static {
    DEFAULT_VALUE_CONVERTERS = unmodifiableSet(createDefaultValueConverters());
  }

  private ValueConverters() {}

  public static Set<ValueConverter<?, ?>> defaultValueConverters() {
    return DEFAULT_VALUE_CONVERTERS;
  }

  private static Set<ValueConverter<?, ?>> createDefaultValueConverters() {
    Set<ValueConverter<?, ?>> defaultValueConverters = new HashSet<>();

    // Primitives
    defaultValueConverters.add(new StringToIntegerValueConverter());
    defaultValueConverters.add(new StringToLongValueConverter());
    defaultValueConverters.add(new StringToDoubleValueConverter());
    defaultValueConverters.add(new StringToFloatValueConverter());
    defaultValueConverters.add(new StringToByteValueConverter());
    defaultValueConverters.add(new StringToShortValueConverter());
    defaultValueConverters.add(new StringToCharacterValueConverter());
    defaultValueConverters.add(new StringToBooleanValueConverter());
    defaultValueConverters.add(new StringToBigIntegerValueConverter());
    defaultValueConverters.add(new StringToBigDecimalValueConverter());
    defaultValueConverters.add(new StringToNumberValueConverter());
    defaultValueConverters.add(new StringToUuidValueConverter());
    defaultValueConverters.add(new StringToInstantValueConverter());
    defaultValueConverters.add(new StringToDateValueConverter());
    defaultValueConverters.add(new StringToLocalDateValueConverter());
    defaultValueConverters.add(new StringToLocalTimeValueConverter());
    defaultValueConverters.add(new StringToLocalDateTimeValueConverter());
    defaultValueConverters.add(new StringToZoneIdValueConverter());
    defaultValueConverters.add(new StringToTimeZoneValueConverter());

    return defaultValueConverters;
  }

  private static abstract class BasicValueConverter<T, F> extends AbstractValueConverter<T, F> {
    @Override
    public F convert(T from) throws ValueConversionException {
      if (from == null) return null;

      try {
        return performConversion(from);
      } catch (ValueConversionException e) {
        throw e;
      } catch (Exception e) {
        throw new ValueConversionException(format("Unable to convert value '%s' of type %s to an instance of %s", from,
          fromType(), toType()), e, fromType(), toType());
      }
    }

    protected abstract F performConversion(T from) throws Exception;

    @Override
    public String toString() {
      return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), fromType(), toType());
    }
  }

  // Primitives

  private static final class StringToIntegerValueConverter extends BasicValueConverter<String, Integer> {
    @Override
    protected Integer performConversion(String from) throws Exception {
      return Integer.parseInt(from);
    }
  };

  private static final class StringToLongValueConverter extends BasicValueConverter<String, Long> {
    @Override
    protected Long performConversion(String from) throws Exception {
      return Long.parseLong(from);
    }
  };

  private static final class StringToDoubleValueConverter extends BasicValueConverter<String, Double> {
    @Override
    protected Double performConversion(String from) throws Exception {
      return Double.parseDouble(from);
    }
  };

  private static final class StringToFloatValueConverter extends BasicValueConverter<String, Float> {
    @Override
    protected Float performConversion(String from) throws Exception {
      return Float.parseFloat(from);
    }
  };

  private static final class StringToByteValueConverter extends BasicValueConverter<String, Byte> {
    @Override
    protected Byte performConversion(String from) throws Exception {
      return Byte.parseByte(from);
    }
  };

  private static final class StringToShortValueConverter extends BasicValueConverter<String, Short> {
    @Override
    protected Short performConversion(String from) throws Exception {
      return Short.parseShort(from);
    }
  };

  private static final class StringToCharacterValueConverter extends BasicValueConverter<String, Character> {
    @Override
    protected Character performConversion(String from) throws Exception {
      from = from.trim();

      if (from.length() != 1)
        throw new ValueConversionException(format(
          "Unable to convert %s value '%s' to %s. Reason: '%s' is not a single-character String.", fromType(), from,
          toType(), from), fromType(), toType());

      return from.charAt(0);
    }
  };

  private static final class StringToBooleanValueConverter extends BasicValueConverter<String, Boolean> {
    @Override
    protected Boolean performConversion(String from) throws Exception {
      return Boolean.parseBoolean(from);
    }
  };

  // Nonprimitives

  private static final class StringToBigIntegerValueConverter extends BasicValueConverter<String, BigInteger> {
    @Override
    protected BigInteger performConversion(String from) throws Exception {
      return new BigInteger(from);
    }
  };

  private static final class StringToBigDecimalValueConverter extends BasicValueConverter<String, BigDecimal> {
    @Override
    protected BigDecimal performConversion(String from) throws Exception {
      return new BigDecimal(from);
    }
  };

  private static final class StringToNumberValueConverter extends BasicValueConverter<String, Number> {
    @Override
    protected BigDecimal performConversion(String from) throws Exception {
      return new BigDecimal(from);
    }
  };

  private static final class StringToUuidValueConverter extends BasicValueConverter<String, UUID> {
    @Override
    protected UUID performConversion(String from) throws Exception {
      return UUID.fromString(from);
    }
  };

  private static final class StringToDateValueConverter extends BasicValueConverter<String, Date> {
    @Override
    protected Date performConversion(String from) throws Exception {
      return new Date(Long.parseLong(from));
    }
  };

  private static final class StringToInstantValueConverter extends BasicValueConverter<String, Instant> {
    @Override
    protected Instant performConversion(String from) throws Exception {
      return Instant.ofEpochMilli(Long.parseLong(from));
    }
  };

  private static final class StringToLocalDateValueConverter extends BasicValueConverter<String, LocalDate> {
    @Override
    protected LocalDate performConversion(String from) throws Exception {
      return LocalDate.parse(from);
    }
  };

  private static final class StringToLocalTimeValueConverter extends BasicValueConverter<String, LocalTime> {
    @Override
    protected LocalTime performConversion(String from) throws Exception {
      return LocalTime.parse(from);
    }
  };

  private static final class StringToLocalDateTimeValueConverter extends BasicValueConverter<String, LocalDateTime> {
    @Override
    protected LocalDateTime performConversion(String from) throws Exception {
      return LocalDateTime.parse(from);
    }
  };

  private static final class StringToZoneIdValueConverter extends BasicValueConverter<String, ZoneId> {
    @Override
    protected ZoneId performConversion(String from) throws Exception {
      return ZoneId.of(from);
    }
  };

  private static final class StringToTimeZoneValueConverter extends BasicValueConverter<String, TimeZone> {
    @Override
    protected TimeZone performConversion(String from) throws Exception {
      // Use ZoneId.of since it will throw an exception if the format is invalid.
      // TimeZone.getTimeZone() returns GMT for invalid formats, which is not the behavior we want
      return TimeZone.getTimeZone(ZoneId.of(from));
    }
  };
}