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

import static java.util.Objects.requireNonNull;

/**
 * Immutable outcome of localizing one MCP presentation field.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpLocalizationResult
		permits McpLocalizationResult.Localized,
		McpLocalizationResult.UseDefaultText,
		McpLocalizationResult.Failure {
	/**
	 * Creates text successfully resolved for the localization context. The
	 * provider owns locale matching and fallback, so the text may come from the
	 * selected locale, a parent locale, or a localization-library fallback.
	 *
	 * @param text localized nonblank text
	 * @return localized result
	 * @throws NullPointerException if {@code text} is null
	 * @throws IllegalArgumentException if {@code text} is blank
	 */
	@NonNull
	static Localized localized(@NonNull String text) {
		return new Localized(text);
	}

	/** @return result requesting the canonical default field */
	@NonNull
	static UseDefaultText useDefaultText() {
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
	static Failure failure() {
		return new Failure();
	}

	/**
	 * Text successfully resolved for the localization context. Resolution may
	 * use the selected locale, a parent locale, or a localization-library
	 * fallback; Soklet does not require or retain per-field resolution
	 * provenance.
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
