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
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Immutable outcome of localizing one MCP presentation field.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpLocalizationResult
		permits McpLocalizationResult.Localized,
		McpLocalizationResult.Fallback,
		McpLocalizationResult.UseDefaultText,
		McpLocalizationResult.Failure {
	/**
	 * Creates a translation resolved in the context locale.
	 *
	 * @param text localized nonblank text
	 * @return localized result
	 * @throws NullPointerException if {@code text} is null
	 * @throws IllegalArgumentException if {@code text} is blank
	 */
	@NonNull
	static Localized fromLocalizedText(@NonNull String text) {
		return new Localized(text);
	}

	/**
	 * Creates a translation resolved through another locale.
	 *
	 * @param text localized nonblank text
	 * @param resolvedLocale actual canonical non-root locale
	 * @return fallback result
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if {@code text} is blank or
	 * {@code resolvedLocale} is not a canonical, non-root BCP 47 locale of at
	 * most 255 ASCII bytes
	 */
	@NonNull
	static Fallback fromFallbackText(@NonNull String text,
			@NonNull Locale resolvedLocale) {
		return new Fallback(text, resolvedLocale);
	}

	/** @return result requesting the canonical default field */
	@NonNull
	static UseDefaultText fromDefaultText() {
		return new UseDefaultText();
	}

	/**
	 * Creates the data-free outcome for an unexpected localization lookup
	 * failure. This outcome applies the localizer's whole-response failure
	 * policy; it is not an ordinary missing-translation result.
	 *
	 * @return failure result
	 */
	@NonNull
	static Failure fromFailure() {
		return new Failure();
	}

	/**
	 * Text resolved in the context locale.
	 *
	 * @param text localized nonblank text
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record Localized(@NonNull String text) implements McpLocalizationResult {
		/**
		 * Validates the localized text.
		 *
		 * @param text localized nonblank text
		 */
		public Localized {
			requireNonNull(text);
			if (text.isBlank())
				throw new IllegalArgumentException(
						"Localized MCP text must not be blank.");
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public final String toString() {
			return "Localized{text=<redacted>}";
		}
	}

	/**
	 * Text resolved from a locale other than the context locale. This may be a
	 * parent locale or a provider/library terminal fallback locale. It is
	 * independent of the localizer's configured default-text fallback locale.
	 * Soklet rejects this result if {@code resolvedLocale} equals the selected
	 * context locale.
	 *
	 * @param text localized nonblank text
	 * @param resolvedLocale actual canonical non-root locale
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record Fallback(@NonNull String text, @NonNull Locale resolvedLocale)
			implements McpLocalizationResult {
		/**
		 * Validates the fallback text and locale.
		 *
		 * @param text localized nonblank text
		 * @param resolvedLocale actual canonical non-root locale
		 */
		public Fallback {
			requireNonNull(text);
			resolvedLocale = McpLocaleSupport.requireCanonicalCatalogLocale(
					resolvedLocale, "resolvedLocale");
			if (text.isBlank())
				throw new IllegalArgumentException(
						"Localized MCP text must not be blank.");
		}

		/** @return redacted diagnostic rendering */
		@Override
		@NonNull
		public final String toString() {
			return "Fallback{text=<redacted>, resolvedLocale=<redacted>}";
		}
	}

	/**
	 * Requests publication of the canonical default text for this field.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record UseDefaultText() implements McpLocalizationResult {
	}

	/**
	 * Reports an unexpected lookup failure without carrying a throwable,
	 * message, key, coordinate, locale, revision, or other diagnostic data.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record Failure() implements McpLocalizationResult {
	}
}
