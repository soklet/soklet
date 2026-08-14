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
import java.util.Collection;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Shared validation for public MCP localization locales.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLocaleSupport {
	private static final int MAXIMUM_LANGUAGE_TAG_BYTES = 255;
	private static final int MAXIMUM_ACCEPT_LANGUAGE_CODE_UNITS = 4_096;
	private static final int MAXIMUM_LANGUAGE_RANGES = 32;

	private McpLocaleSupport() {
	}

	/**
	 * Derives the bounded effective client language preference view from raw
	 * {@code Accept-Language} header values.
	 * <p>
	 * The combined input is bounded to 4,096 UTF-16 code units before parsing and
	 * the parsed result is bounded to 32 ranges after JDK alias expansion. Neither
	 * bound truncates: missing, blank, malformed, or over-limit input becomes an
	 * empty list, which reaches the provider's own fallback behavior. Zero-weight
	 * exclusions are preserved exactly as parsed.
	 * <p>
	 * This deliberately does not delegate to the general-purpose
	 * {@code Request.getLanguageRanges()} accessor, which parses before applying
	 * any count bound and would defeat this request-path containment rule.
	 *
	 * @param rawAcceptLanguageValues raw header values, or {@code null}
	 * @return immutable bounded language ranges, possibly empty
	 */
	@NonNull
	static List<Locale.@NonNull LanguageRange> boundedLanguageRanges(
			@Nullable Collection<@NonNull String> rawAcceptLanguageValues) {
		if (rawAcceptLanguageValues == null || rawAcceptLanguageValues.isEmpty())
			return List.of();

		StringBuilder combined = new StringBuilder();

		for (String rawAcceptLanguageValue : rawAcceptLanguageValues) {
			if (rawAcceptLanguageValue == null)
				continue;

			if (combined.length() > 0)
				combined.append(',');

			combined.append(rawAcceptLanguageValue);

			// Bail before materializing more than the bound permits.
			if (combined.length() > MAXIMUM_ACCEPT_LANGUAGE_CODE_UNITS)
				return List.of();
		}

		if (combined.isEmpty() || combined.toString().isBlank())
			return List.of();

		List<Locale.LanguageRange> languageRanges;

		try {
			languageRanges = Locale.LanguageRange.parse(combined.toString());
		} catch (RuntimeException ignored) {
			// Malformed input is indistinguishable from absent input by contract.
			return List.of();
		}

		if (languageRanges.size() > MAXIMUM_LANGUAGE_RANGES)
			return List.of();

		return List.copyOf(languageRanges);
	}

	@NonNull
	static Locale requireCanonicalCatalogLocale(@NonNull Locale locale,
			@NonNull String name) {
		requireNonNull(locale, name);
		String languageTag = locale.toLanguageTag();
		if (languageTag.length() > MAXIMUM_LANGUAGE_TAG_BYTES
				|| languageTag.equalsIgnoreCase("und")
				|| languageTag.regionMatches(true, 0, "und-", 0, 4)
				|| !isAscii(languageTag)
				|| !isWellFormed(locale)
				|| !Locale.forLanguageTag(languageTag).equals(locale))
			throw new IllegalArgumentException(
					"MCP localization locales must be canonical non-root BCP 47 tags of at most 255 ASCII bytes.");
		return locale;
	}

	private static boolean isWellFormed(@NonNull Locale locale) {
		try {
			new Locale.Builder().setLocale(locale).build();
			return true;
		} catch (IllformedLocaleException exception) {
			return false;
		}
	}

	private static boolean isAscii(@NonNull String value) {
		for (int index = 0; index < value.length(); ++index) {
			if (value.charAt(index) > 0x7F)
				return false;
		}
		return true;
	}
}
