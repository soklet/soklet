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
		return UseDefaultText.INSTANCE;
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
		return Failure.INSTANCE;
	}

	/**
	 * Text successfully resolved for the localization context. Resolution may
	 * use the selected locale, a parent locale, or a localization-library
	 * fallback; Soklet does not require or retain per-field resolution
	 * provenance. The result carries localized nonblank text.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class Localized implements McpLocalizationResult {
		@NonNull
		private final String text;

		/**
		 * Validates the localized text.
		 *
		 * @param text localized nonblank text
		 */
		private Localized(@NonNull String text) {
			this.text = requireNonNull(text);
			if (text.isBlank())
				throw new IllegalArgumentException(
						"Localized MCP text must not be blank.");
		}

		/** @return localized nonblank text */
		@NonNull
		public String getText() {
			return this.text;
		}

		/** @return whether this result contains the same localized text */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof Localized localized))
				return false;
			return this.text.equals(localized.text);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return this.text.hashCode();
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
	public final class UseDefaultText implements McpLocalizationResult {
		@NonNull
		private static final UseDefaultText INSTANCE = new UseDefaultText();

		private UseDefaultText() {
		}

		/** @return whether the other value also requests default text */
		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof UseDefaultText;
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return safe diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "UseDefaultText{}";
		}
	}

	/**
	 * Reports an unexpected lookup failure without carrying a throwable,
	 * message, key, coordinate, locale, revision, or other diagnostic data.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class Failure implements McpLocalizationResult {
		@NonNull
		private static final Failure INSTANCE = new Failure();

		private Failure() {
		}

		/** @return whether the other value is also a localization failure */
		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof Failure;
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return safe diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "Failure{}";
		}
	}
}
