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

import static java.lang.String.format;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
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

	// Primitives

	@ThreadSafe
	private static final class StringToIntegerValueConverter extends FromStringValueConverter<Integer> {
		@Override
		@Nullable
		public Integer performConversion(@Nonnull String from) throws Exception {
			return Integer.parseInt(from);
		}
	}

	@ThreadSafe
	private static final class StringToLongValueConverter extends FromStringValueConverter<Long> {
		@Override
		@Nullable
		public Long performConversion(@Nonnull String from) throws Exception {
			return Long.parseLong(from);
		}
	}

	@ThreadSafe
	private static final class StringToDoubleValueConverter extends FromStringValueConverter<Double> {
		@Override
		@Nullable
		public Double performConversion(@Nonnull String from) throws Exception {
			return Double.parseDouble(from);
		}
	}

	@ThreadSafe
	private static final class StringToFloatValueConverter extends FromStringValueConverter<Float> {
		@Override
		@Nullable
		public Float performConversion(@Nonnull String from) throws Exception {
			return Float.parseFloat(from);
		}
	}

	@ThreadSafe
	private static final class StringToByteValueConverter extends FromStringValueConverter<Byte> {
		@Override
		@Nullable
		public Byte performConversion(@Nonnull String from) throws Exception {
			return Byte.parseByte(from);
		}
	}

	@ThreadSafe
	private static final class StringToShortValueConverter extends FromStringValueConverter<Short> {
		@Override
		@Nullable
		public Short performConversion(@Nonnull String from) throws Exception {
			return Short.parseShort(from);
		}
	}

	@ThreadSafe
	private static final class StringToCharacterValueConverter extends FromStringValueConverter<Character> {
		@Override
		@Nullable
		public Character performConversion(@Nonnull String from) throws Exception {
			if (from.length() != 1)
				throw new ValueConversionException(format(
						"Unable to convert %s value '%s' to %s. Reason: '%s' is not a single-character String.", getFromType(), from,
						getToType(), from), getFromType(), getToType());

			return from.charAt(0);
		}
	}

	@ThreadSafe
	private static final class StringToBooleanValueConverter extends FromStringValueConverter<Boolean> {
		@Override
		@Nullable
		public Boolean performConversion(@Nonnull String from) throws Exception {
			return Boolean.parseBoolean(from);
		}
	}

	// Nonprimitives

	@ThreadSafe
	private static final class StringToBigIntegerValueConverter extends FromStringValueConverter<BigInteger> {
		@Override
		@Nullable
		public BigInteger performConversion(@Nonnull String from) throws Exception {
			return new BigInteger(from);
		}
	}

	@ThreadSafe
	private static final class StringToBigDecimalValueConverter extends FromStringValueConverter<BigDecimal> {
		@Override
		@Nullable
		public BigDecimal performConversion(@Nonnull String from) throws Exception {
			return new BigDecimal(from);
		}
	}

	@ThreadSafe
	private static final class StringToNumberValueConverter extends FromStringValueConverter<Number> {
		@Override
		@Nullable
		public BigDecimal performConversion(@Nonnull String from) throws Exception {
			return new BigDecimal(from);
		}
	}

	@ThreadSafe
	private static final class StringToUuidValueConverter extends FromStringValueConverter<UUID> {
		@Override
		@Nullable
		public UUID performConversion(@Nonnull String from) throws Exception {
			return UUID.fromString(from);
		}
	}

	@ThreadSafe
	private static final class StringToDateValueConverter extends FromStringValueConverter<Date> {
		@Override
		@Nullable
		public Date performConversion(@Nonnull String from) throws Exception {
			return new Date(Long.parseLong(from));
		}
	}

	@ThreadSafe
	private static final class StringToInstantValueConverter extends FromStringValueConverter<Instant> {
		@Override
		@Nullable
		public Instant performConversion(@Nonnull String from) throws Exception {
			return Instant.ofEpochMilli(Long.parseLong(from));
		}
	}

	@ThreadSafe
	@Nullable
	private static final class StringToLocalDateValueConverter extends FromStringValueConverter<LocalDate> {
		@Override
		@Nullable
		public LocalDate performConversion(@Nonnull String from) throws Exception {
			return LocalDate.parse(from);
		}
	}

	@ThreadSafe
	@Nullable
	private static final class StringToLocalTimeValueConverter extends FromStringValueConverter<LocalTime> {
		@Override
		@Nullable
		public LocalTime performConversion(@Nonnull String from) throws Exception {
			return LocalTime.parse(from);
		}
	}

	@ThreadSafe
	@Nullable
	private static final class StringToLocalDateTimeValueConverter extends FromStringValueConverter<LocalDateTime> {
		@Override
		@Nullable
		public LocalDateTime performConversion(@Nonnull String from) throws Exception {
			return LocalDateTime.parse(from);
		}
	}

	@ThreadSafe
	private static final class StringToZoneIdValueConverter extends FromStringValueConverter<ZoneId> {
		@Override
		@Nullable
		public ZoneId performConversion(@Nonnull String from) throws Exception {
			return ZoneId.of(from);
		}
	}

	@ThreadSafe
	private static final class StringToTimeZoneValueConverter extends FromStringValueConverter<TimeZone> {
		@Override
		@Nullable
		public TimeZone performConversion(@Nonnull String from) throws Exception {
			// Use ZoneId.of since it will throw an exception if the format is invalid.
			// TimeZone.getTimeZone() returns GMT for invalid formats, which is not the behavior we want
			return TimeZone.getTimeZone(ZoneId.of(from));
		}
	}

	@ThreadSafe
	private static final class StringToLocaleValueConverter extends FromStringValueConverter<Locale> {
		@Override
		@Nullable
		public Locale performConversion(@Nonnull String from) throws Exception {
			return new Locale.Builder().setLanguageTag(from).build();
		}
	}
}