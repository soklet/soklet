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
import java.lang.reflect.Type;
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
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import static com.soklet.core.Utilities.trimAggressivelyToNull;
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
		@Nonnull
		public Optional<Integer> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(Integer.parseInt(from));
		}
	}

	@ThreadSafe
	private static final class StringToLongValueConverter extends FromStringValueConverter<Long> {
		@Override
		@Nonnull
		public Optional<Long> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(Long.parseLong(from));
		}
	}

	@ThreadSafe
	private static final class StringToDoubleValueConverter extends FromStringValueConverter<Double> {
		@Override
		@Nonnull
		public Optional<Double> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(Double.parseDouble(from));
		}
	}

	@ThreadSafe
	private static final class StringToFloatValueConverter extends FromStringValueConverter<Float> {
		@Override
		@Nonnull
		public Optional<Float> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(Float.parseFloat(from));
		}
	}

	@ThreadSafe
	private static final class StringToByteValueConverter extends FromStringValueConverter<Byte> {
		@Override
		@Nonnull
		public Optional<Byte> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(Byte.parseByte(from));
		}
	}

	@ThreadSafe
	private static final class StringToShortValueConverter extends FromStringValueConverter<Short> {
		@Override
		@Nonnull
		public Optional<Short> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(Short.parseShort(from));
		}
	}

	@ThreadSafe
	private static final class StringToCharacterValueConverter extends FromStringValueConverter<Character> {
		@Nonnull
		@Override
		public Optional<Character> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			String trimmedFrom = trimAggressivelyToNull(from);

			// Special handling for all-whitespace.
			// If there is at least one space, return ' '
			if (from.length() > 0 && trimmedFrom == null)
				return Optional.of(' ');

			if (trimmedFrom.length() != 1)
				throw new ValueConversionException(format(
						"Unable to convert %s value '%s' to %s. Reason: '%s' is not a single-character String.", getFromType(), trimmedFrom,
						getToType(), from), getFromType(), from, getToType());

			return Optional.of(trimmedFrom.charAt(0));
		}

		@Nonnull
		@Override
		protected Boolean shouldTrimFromValues() {
			// Special handling: we want to handle trimming ourselves
			return false;
		}

		@Nonnull
		@Override
		public Type getFromType() {
			return String.class;
		}

		@Nonnull
		@Override
		public Type getToType() {
			return Character.class;
		}
	}

	@ThreadSafe
	private static final class StringToBooleanValueConverter extends FromStringValueConverter<Boolean> {
		@Override
		@Nonnull
		public Optional<Boolean> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(Boolean.parseBoolean(from));
		}
	}

	// Nonprimitives

	@ThreadSafe
	private static final class StringToBigIntegerValueConverter extends FromStringValueConverter<BigInteger> {
		@Override
		@Nonnull
		public Optional<BigInteger> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(new BigInteger(from));
		}
	}

	@ThreadSafe
	private static final class StringToBigDecimalValueConverter extends FromStringValueConverter<BigDecimal> {
		@Override
		@Nonnull
		public Optional<BigDecimal> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(new BigDecimal(from));
		}
	}

	@ThreadSafe
	private static final class StringToNumberValueConverter extends FromStringValueConverter<Number> {
		@Override
		@Nonnull
		public Optional<Number> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(new BigDecimal(from));
		}
	}

	@ThreadSafe
	private static final class StringToUuidValueConverter extends FromStringValueConverter<UUID> {
		@Override
		@Nonnull
		public Optional<UUID> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(UUID.fromString(from));
		}
	}

	@ThreadSafe
	private static final class StringToDateValueConverter extends FromStringValueConverter<Date> {
		@Override
		@Nonnull
		public Optional<Date> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(new Date(Long.parseLong(from)));
		}
	}

	@ThreadSafe
	private static final class StringToInstantValueConverter extends FromStringValueConverter<Instant> {
		@Override
		@Nonnull
		public Optional<Instant> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(Instant.ofEpochMilli(Long.parseLong(from)));
		}
	}

	@ThreadSafe
	private static final class StringToLocalDateValueConverter extends FromStringValueConverter<LocalDate> {
		@Override
		@Nonnull
		public Optional<LocalDate> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(LocalDate.parse(from));
		}
	}

	@ThreadSafe
	private static final class StringToLocalTimeValueConverter extends FromStringValueConverter<LocalTime> {
		@Override
		@Nonnull
		public Optional<LocalTime> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(LocalTime.parse(from));
		}
	}

	@ThreadSafe
	private static final class StringToLocalDateTimeValueConverter extends FromStringValueConverter<LocalDateTime> {
		@Override
		@Nonnull
		public Optional<LocalDateTime> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(LocalDateTime.parse(from));
		}
	}

	@ThreadSafe
	private static final class StringToZoneIdValueConverter extends FromStringValueConverter<ZoneId> {
		@Override
		@Nonnull
		public Optional<ZoneId> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(ZoneId.of(from));
		}
	}

	@ThreadSafe
	private static final class StringToTimeZoneValueConverter extends FromStringValueConverter<TimeZone> {
		@Override
		@Nonnull
		public Optional<TimeZone> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			// Use ZoneId.of since it will throw an exception if the format is invalid.
			// TimeZone.getTimeZone() returns GMT for invalid formats, which is not the behavior we want
			return Optional.of(TimeZone.getTimeZone(ZoneId.of(from)));
		}
	}

	@ThreadSafe
	private static final class StringToLocaleValueConverter extends FromStringValueConverter<Locale> {
		@Override
		@Nonnull
		public Optional<Locale> performConversion(@Nullable String from) throws Exception {
			if (from == null)
				return Optional.empty();

			return Optional.of(new Locale.Builder().setLanguageTag(from).build());
		}
	}
}