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

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** RFC 6265 section 5.1.1 cookie dates have a fixed year cutoff and ignore weekdays. */
final class CookieDate {
	private static final Pattern DELIMITERS = Pattern.compile("[\\x09\\x20-\\x2f\\x3b-\\x40\\x5b-\\x60\\x7b-\\x7e]+");
	private static final Pattern TIME = Pattern.compile("([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,2})(?:[^0-9].*)?", Pattern.DOTALL);
	private static final Pattern DAY = Pattern.compile("([0-9]{1,2})(?:[^0-9].*)?", Pattern.DOTALL);
	private static final Pattern YEAR = Pattern.compile("([0-9]{2,4})(?:[^0-9].*)?", Pattern.DOTALL);
	private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

	private CookieDate() {}

	@NonNull
	static Optional<Instant> parse(@NonNull String value) {
		int hour = -1, minute = -1, second = -1, day = -1, month = -1, year = -1;
		for (String token : DELIMITERS.split(value, -1)) {
			Matcher timeMatch = TIME.matcher(token);
			if (hour == -1 && timeMatch.matches()) {
				hour = Integer.parseInt(timeMatch.group(1));
				minute = Integer.parseInt(timeMatch.group(2));
				second = Integer.parseInt(timeMatch.group(3));
				continue;
			}
			Matcher dayMatch = DAY.matcher(token);
			if (day == -1 && dayMatch.matches()) {
				day = Integer.parseInt(dayMatch.group(1));
				continue;
			}
			int monthIndex = token.length() >= 3 ? MONTHS.indexOf(token.substring(0, 3).toLowerCase(Locale.ROOT)) : -1;
			if (month == -1 && monthIndex >= 0) {
				month = monthIndex + 1;
				continue;
			}
			Matcher yearMatch = YEAR.matcher(token);
			if (year == -1 && yearMatch.matches())
				year = Integer.parseInt(yearMatch.group(1));
		}
		if (year >= 0 && year <= 69) year += 2000;
		else if (year >= 70 && year <= 99) year += 1900;
		if (year < 1601 || hour < 0 || minute < 0 || second < 0 || day < 1 || month < 1)
			return Optional.empty();
		try {
			return Optional.of(LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC));
		} catch (DateTimeException ignored) {
			return Optional.empty();
		}
	}
}
