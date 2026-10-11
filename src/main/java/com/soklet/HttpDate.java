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

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Formatter and parser for HTTP-date-valued headers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class HttpDate {
	@NonNull
	private static final ZoneId GMT;
	private static final int MINIMUM_IMF_FIXDATE_YEAR;
	private static final int MAXIMUM_IMF_FIXDATE_YEAR;
	@NonNull
	private static final DateTimeFormatter IMF_FIXDATE_FORMATTER;
	@NonNull
	private static final DateTimeFormatter RFC_1123_PARSER;
	@NonNull
	private static final DateTimeFormatter RFC_1036_PARSER;
	@NonNull
	private static final DateTimeFormatter TWO_DIGIT_YEAR_LEGACY_PARSER;
	@NonNull
	private static final DateTimeFormatter ASCTIME_PARSER;
	@NonNull
	private static final List<@NonNull DateTimeFormatter> PARSERS;
	@NonNull
	private static final List<@NonNull DateTimeFormatter> TWO_DIGIT_YEAR_PARSERS;
	@NonNull
	private static final AtomicReference<@NonNull CachedValue> CURRENT_SECOND_HEADER_VALUE;

	static {
		GMT = ZoneId.of("GMT");
		MINIMUM_IMF_FIXDATE_YEAR = 1;
		MAXIMUM_IMF_FIXDATE_YEAR = 9_999;
		IMF_FIXDATE_FORMATTER = new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendPattern("EEE, dd MMM ")
				.appendValue(ChronoField.YEAR, 4)
				.appendPattern(" HH:mm:ss 'GMT'")
				.toFormatter(Locale.US)
				.withZone(GMT);
		RFC_1123_PARSER = DateTimeFormatter.RFC_1123_DATE_TIME
				.withResolverStyle(ResolverStyle.STRICT).withZone(GMT);
		RFC_1036_PARSER = new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendPattern("EEEE, dd-MMM-")
				.appendValue(ChronoField.YEAR, 2)
				.appendPattern(" HH:mm:ss zzz")
				.toFormatter(Locale.US)
				.withZone(ZoneOffset.UTC);
		TWO_DIGIT_YEAR_LEGACY_PARSER = new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendPattern("EEE, dd MMM ")
				.appendValue(ChronoField.YEAR, 2)
				.appendPattern(" HH:mm:ss zzz")
				.toFormatter(Locale.US)
				.withZone(ZoneOffset.UTC);
		ASCTIME_PARSER = new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendPattern("EEE MMM")
				.appendLiteral(' ')
				.optionalStart().appendLiteral(' ').optionalEnd()
				.appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
				.appendPattern(" HH:mm:ss uuuu")
				.toFormatter(Locale.US)
				.withResolverStyle(ResolverStyle.STRICT)
				.withZone(ZoneOffset.UTC);
		PARSERS = List.of(RFC_1123_PARSER, ASCTIME_PARSER);
		TWO_DIGIT_YEAR_PARSERS = List.of(RFC_1036_PARSER, TWO_DIGIT_YEAR_LEGACY_PARSER);
		CURRENT_SECOND_HEADER_VALUE = new AtomicReference<>(CachedValue.fromInstant(Instant.now()));
	}

	private HttpDate() {
		// Non-instantiable
	}

	/**
	 * Formats the provided instant as an IMF-fixdate HTTP header value.
	 *
	 * @param instant the instant to format
	 * @return the HTTP-date header value
	 * @throws IllegalArgumentException if the instant cannot be represented with a four-digit IMF-fixdate year
	 */
	@NonNull
	public static String toHeaderValue(@NonNull Instant instant) {
		requireNonNull(instant);
		validateImfFixdateYear(instant);
		return IMF_FIXDATE_FORMATTER.format(instant);
	}

	@NonNull
	public static Optional<@NonNull Instant> fromHeaderValue(@Nullable String headerValue) {
		return fromHeaderValue(headerValue, Instant.now());
	}

	@NonNull
	static Optional<@NonNull Instant> fromHeaderValue(@Nullable String headerValue,
			@NonNull Instant now) {
		requireNonNull(now);
		String trimmed = Utilities.trimAggressivelyToNull(headerValue);

		if (trimmed == null)
			return Optional.empty();

		for (DateTimeFormatter parser : TWO_DIGIT_YEAR_PARSERS) {
			ParsePosition position = new ParsePosition(0);
			TemporalAccessor fields = parser.parseUnresolved(trimmed, position);
			if (fields == null || position.getErrorIndex() >= 0 || position.getIndex() != trimmed.length())
				continue;
			try {
				return Optional.of(resolveTwoDigitYearDate(fields, now));
			} catch (DateTimeException ignored) {
				// A recognized two-digit-year date must not fall back to RFC 1123
				// parsing that can interpret its year as, for example, 0026.
				return Optional.empty();
			}
		}
		for (DateTimeFormatter parser : PARSERS) {
			try {
				return Optional.of(Instant.from(parser.parse(trimmed)));
			} catch (DateTimeException ignored) {
				// Try the next HTTP-date format.
			}
		}
		return Optional.empty();
	}

	@NonNull
	private static Instant resolveTwoDigitYearDate(@NonNull TemporalAccessor fields,
			@NonNull Instant now) {
		// A leap-year baseline validates the fields without resolving the century
		// or weekday prematurely. For example, 2000-02-29 can be valid even when
		// the candidate future century is the non-leap year 2100.
		LocalDateTime parsed = LocalDateTime.of(2000 + fields.get(ChronoField.YEAR),
				fields.get(ChronoField.MONTH_OF_YEAR), fields.get(ChronoField.DAY_OF_MONTH),
				fields.get(ChronoField.HOUR_OF_DAY), fields.get(ChronoField.MINUTE_OF_HOUR),
				fields.get(ChronoField.SECOND_OF_MINUTE));
		ZoneId zone = ZoneId.from(fields);
		LocalDateTime latest = LocalDateTime.ofInstant(now, zone).plusYears(50);
		int year = Math.floorDiv(latest.getYear(), 100) * 100 + fields.get(ChronoField.YEAR);
		// Exactly fifty years ahead is allowed; a later timestamp denotes the
		// most recent past year with these final two digits (RFC 9110 section 5.6.7).
		if (year > latest.getYear() || (year == latest.getYear()
				&& parsed.withYear(2000).isAfter(latest.withYear(2000))))
			year -= 100;

		LocalDateTime resolved = LocalDateTime.of(year, parsed.getMonth(), parsed.getDayOfMonth(),
				parsed.getHour(), parsed.getMinute(), parsed.getSecond());
		if (resolved.getDayOfWeek().getValue() != fields.get(ChronoField.DAY_OF_WEEK))
			throw new DateTimeException("HTTP date weekday does not match its date");
		return resolved.atZone(zone).toInstant();
	}

	@NonNull
	public static String currentSecondHeaderValue() {
		Long currentEpochSecond = Instant.now().getEpochSecond();
		CachedValue cachedValue = requireNonNull(CURRENT_SECOND_HEADER_VALUE.get());

		if (cachedValue.epochSecond().equals(currentEpochSecond))
			return cachedValue.headerValue();

		CachedValue newValue = CachedValue.fromEpochSecond(currentEpochSecond);

		if (CURRENT_SECOND_HEADER_VALUE.compareAndSet(cachedValue, newValue))
			return newValue.headerValue();

		return requireNonNull(CURRENT_SECOND_HEADER_VALUE.get()).headerValue();
	}

	private static void validateImfFixdateYear(@NonNull Instant instant) {
		int year;

		try {
			year = instant.atZone(GMT).get(ChronoField.YEAR);
		} catch (DateTimeException e) {
			throw new IllegalArgumentException("Instant is outside the supported IMF-fixdate year range.", e);
		}

		if (year < MINIMUM_IMF_FIXDATE_YEAR || year > MAXIMUM_IMF_FIXDATE_YEAR)
			throw new IllegalArgumentException("IMF-fixdate year must be between 0001 and 9999.");
	}

	private record CachedValue(@NonNull Long epochSecond,
														 @NonNull String headerValue) {
		@NonNull
		static CachedValue fromInstant(@NonNull Instant instant) {
			requireNonNull(instant);
			return fromEpochSecond(instant.getEpochSecond());
		}

		@NonNull
		static CachedValue fromEpochSecond(@NonNull Long epochSecond) {
			requireNonNull(epochSecond);
			Instant instant = Instant.ofEpochSecond(epochSecond);
			return new CachedValue(epochSecond, toHeaderValue(instant));
		}
	}
}
