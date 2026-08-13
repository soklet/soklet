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

import javax.annotation.concurrent.ThreadSafe;
import java.util.IllformedLocaleException;
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

	private McpLocaleSupport() {
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
