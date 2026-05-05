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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
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
	private static final AtomicReference<CachedValue> CURRENT_SECOND_HEADER_VALUE;

	static {
		GMT = ZoneId.of("GMT");
		IMF_FIXDATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
				.withZone(GMT);
		RFC_1123_PARSER = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(GMT);
		RFC_1036_PARSER = new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendPattern("EEEE, dd-MMM-")
				.appendValueReduced(ChronoField.YEAR, 2, 2, 1900)
				.appendPattern(" HH:mm:ss zzz")
				.toFormatter(Locale.US)
				.withZone(ZoneOffset.UTC);
		TWO_DIGIT_YEAR_LEGACY_PARSER = new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendPattern("EEE, dd MMM ")
				.appendValueReduced(ChronoField.YEAR, 2, 2, 1900)
				.appendPattern(" HH:mm:ss zzz")
				.toFormatter(Locale.US)
				.withZone(ZoneOffset.UTC);
		ASCTIME_PARSER = new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendPattern("EEE MMM")
				.appendLiteral(' ')
				.optionalStart().appendLiteral(' ').optionalEnd()
				.appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
				.appendPattern(" HH:mm:ss yyyy")
				.toFormatter(Locale.US)
				.withZone(ZoneOffset.UTC);
		PARSERS = List.of(RFC_1123_PARSER, RFC_1036_PARSER, TWO_DIGIT_YEAR_LEGACY_PARSER, ASCTIME_PARSER);
		CURRENT_SECOND_HEADER_VALUE = new AtomicReference<>(CachedValue.fromInstant(Instant.now()));
	}

	private HttpDate() {
		// Non-instantiable
	}

	@NonNull
	public static String toHeaderValue(@NonNull Instant instant) {
		requireNonNull(instant);
		return IMF_FIXDATE_FORMATTER.format(instant);
	}

	@NonNull
	public static Optional<Instant> fromHeaderValue(@Nullable String headerValue) {
		String trimmed = Utilities.trimAggressivelyToNull(headerValue);

		if (trimmed == null)
			return Optional.empty();

		for (DateTimeFormatter parser : PARSERS) {
			try {
				return Optional.of(Instant.from(parser.parse(trimmed)));
			} catch (Exception ignored) {
				// Try the next HTTP-date format.
			}
		}

		return Optional.empty();
	}

	@NonNull
	public static String currentSecondHeaderValue() {
		Long currentEpochSecond = Instant.now().getEpochSecond();
		CachedValue cachedValue = CURRENT_SECOND_HEADER_VALUE.get();

		if (cachedValue.epochSecond().equals(currentEpochSecond))
			return cachedValue.headerValue();

		CachedValue newValue = CachedValue.fromEpochSecond(currentEpochSecond);

		if (CURRENT_SECOND_HEADER_VALUE.compareAndSet(cachedValue, newValue))
			return newValue.headerValue();

		return CURRENT_SECOND_HEADER_VALUE.get().headerValue();
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
