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
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import static com.soklet.core.Utilities.trimAggressively;
import static java.lang.String.format;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public final class ValueConverters {
	@Nonnull
	private static final Set<ValueConverter<?, ?>> DEFAULT_VALUE_CONVERTERS;

	static {
		DEFAULT_VALUE_CONVERTERS = Collections.unmodifiableSet(createDefaultValueConverters());
	}

	private ValueConverters() {
		// Cannot instantiate
	}

	@Nonnull
	public static Set<ValueConverter<?, ?>> defaultValueConverters() {
		return DEFAULT_VALUE_CONVERTERS;
	}

	@Nonnull
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
		defaultValueConverters.add(new StringToLocaleValueConverter());

		return defaultValueConverters;
	}

	@ThreadSafe
	private static abstract class BasicValueConverter<T, F> extends AbstractValueConverter<T, F> {
		@Override
		@Nullable
		public F convert(@Nullable T from) throws ValueConversionException {
			if (from == null)
				return null;

			try {
				return performConversion(from);
			} catch (ValueConversionException e) {
				throw e;
			} catch (Exception e) {
				throw new ValueConversionException(format("Unable to convert value '%s' of type %s to an instance of %s", from,
						getFromType(), getToType()), e, getFromType(), getToType());
			}
		}

		@Nullable
		protected abstract F performConversion(@Nullable T from) throws Exception;

		@Override
		@Nonnull
		public String toString() {
			return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), getFromType(), getToType());
		}
	}

	@ThreadSafe
	private static abstract class BasicStringValueConverter<T, F> extends BasicValueConverter<String, F> {
		@Override
		@Nullable
		public F convert(@Nullable String from) throws ValueConversionException {
			from = trimAggressively(from); // Aggressively trim off everything, including nonprintable whitespace
			return super.convert(from);
		}
	}

	// Primitives

	@ThreadSafe
	private static final class StringToIntegerValueConverter extends BasicStringValueConverter<String, Integer> {
		@Override
		@Nullable
		protected Integer performConversion(@Nullable String from) throws Exception {
			return Integer.parseInt(from);
		}
	}

	@ThreadSafe
	private static final class StringToLongValueConverter extends BasicStringValueConverter<String, Long> {
		@Override
		@Nullable
		protected Long performConversion(@Nullable String from) throws Exception {
			return Long.parseLong(from);
		}
	}

	@ThreadSafe
	private static final class StringToDoubleValueConverter extends BasicStringValueConverter<String, Double> {
		@Override
		@Nullable
		protected Double performConversion(@Nullable String from) throws Exception {
			return Double.parseDouble(from);
		}
	}

	@ThreadSafe
	private static final class StringToFloatValueConverter extends BasicStringValueConverter<String, Float> {
		@Override
		@Nullable
		protected Float performConversion(@Nullable String from) throws Exception {
			return Float.parseFloat(from);
		}
	}

	@ThreadSafe
	private static final class StringToByteValueConverter extends BasicStringValueConverter<String, Byte> {
		@Override
		@Nullable
		protected Byte performConversion(@Nullable String from) throws Exception {
			return Byte.parseByte(from);
		}
	}

	@ThreadSafe
	private static final class StringToShortValueConverter extends BasicStringValueConverter<String, Short> {
		@Override
		@Nullable
		protected Short performConversion(@Nullable String from) throws Exception {
			return Short.parseShort(from);
		}
	}

	@ThreadSafe
	private static final class StringToCharacterValueConverter extends BasicStringValueConverter<String, Character> {
		@Override
		@Nullable
		protected Character performConversion(@Nullable String from) throws Exception {
			if (from.length() != 1)
				throw new ValueConversionException(format(
						"Unable to convert %s value '%s' to %s. Reason: '%s' is not a single-character String.", getFromType(), from,
						getToType(), from), getFromType(), getToType());

			return from.charAt(0);
		}
	}

	@ThreadSafe
	private static final class StringToBooleanValueConverter extends BasicStringValueConverter<String, Boolean> {
		@Override
		@Nullable
		protected Boolean performConversion(@Nullable String from) throws Exception {
			return Boolean.parseBoolean(from);
		}
	}

	// Nonprimitives

	@ThreadSafe
	private static final class StringToBigIntegerValueConverter extends BasicStringValueConverter<String, BigInteger> {
		@Override
		@Nullable
		protected BigInteger performConversion(@Nullable String from) throws Exception {
			return new BigInteger(from);
		}
	}

	@ThreadSafe
	private static final class StringToBigDecimalValueConverter extends BasicStringValueConverter<String, BigDecimal> {
		@Override
		@Nullable
		protected BigDecimal performConversion(@Nullable String from) throws Exception {
			return new BigDecimal(from);
		}
	}

	@ThreadSafe
	private static final class StringToNumberValueConverter extends BasicStringValueConverter<String, Number> {
		@Override
		@Nullable
		protected BigDecimal performConversion(@Nullable String from) throws Exception {
			return new BigDecimal(from);
		}
	}

	@ThreadSafe
	private static final class StringToUuidValueConverter extends BasicStringValueConverter<String, UUID> {
		@Override
		@Nullable
		protected UUID performConversion(@Nullable String from) throws Exception {
			return UUID.fromString(from);
		}
	}

	@ThreadSafe
	private static final class StringToDateValueConverter extends BasicStringValueConverter<String, Date> {
		@Override
		@Nullable
		protected Date performConversion(@Nullable String from) throws Exception {
			return new Date(Long.parseLong(from));
		}
	}

	@ThreadSafe
	private static final class StringToInstantValueConverter extends BasicStringValueConverter<String, Instant> {
		@Override
		@Nullable
		protected Instant performConversion(@Nullable String from) throws Exception {
			return Instant.ofEpochMilli(Long.parseLong(from));
		}
	}

	@ThreadSafe
	@Nullable
	private static final class StringToLocalDateValueConverter extends BasicStringValueConverter<String, LocalDate> {
		@Override
		protected LocalDate performConversion(@Nullable String from) throws Exception {
			return LocalDate.parse(from);
		}
	}

	@ThreadSafe
	@Nullable
	private static final class StringToLocalTimeValueConverter extends BasicStringValueConverter<String, LocalTime> {
		@Override
		protected LocalTime performConversion(@Nullable String from) throws Exception {
			return LocalTime.parse(from);
		}
	}

	@ThreadSafe
	@Nullable
	private static final class StringToLocalDateTimeValueConverter extends BasicStringValueConverter<String, LocalDateTime> {
		@Override
		protected LocalDateTime performConversion(@Nullable String from) throws Exception {
			return LocalDateTime.parse(from);
		}
	}

	@ThreadSafe
	private static final class StringToZoneIdValueConverter extends BasicStringValueConverter<String, ZoneId> {
		@Override
		@Nullable
		protected ZoneId performConversion(@Nullable String from) throws Exception {
			return ZoneId.of(from);
		}
	}

	@ThreadSafe
	private static final class StringToTimeZoneValueConverter extends BasicStringValueConverter<String, TimeZone> {
		@Override
		@Nullable
		protected TimeZone performConversion(@Nullable String from) throws Exception {
			// Use ZoneId.of since it will throw an exception if the format is invalid.
			// TimeZone.getTimeZone() returns GMT for invalid formats, which is not the behavior we want
			return TimeZone.getTimeZone(ZoneId.of(from));
		}
	}

	@ThreadSafe
	private static final class StringToLocaleValueConverter extends BasicStringValueConverter<String, Locale> {
		@Override
		@Nullable
		protected Locale performConversion(@Nullable String from) throws Exception {
			return new Locale.Builder().setLanguageTag(from).build();
		}
	}
}