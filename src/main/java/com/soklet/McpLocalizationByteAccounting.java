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
 * Exact serialized-size accounting for localized JSON string replacements.
 * <p>
 * Fail-atomic rendering must decide whether a candidate replacement still fits
 * the production response ceiling <em>before</em> it is retained, which means
 * predicting the encoder's output without running it. This mirrors the
 * production JSON writer's string rules exactly - two-character escapes for
 * quote, backslash, and the five named control characters, six-character
 * {@code \\uXXXX} escapes for the remaining C0 controls, and plain UTF-8 for
 * everything else including surrogate pairs.
 * <p>
 * The prediction is advisory in the sense that the production encoder still runs
 * as the authoritative aggregate check before commitment; this only lets the
 * renderer abandon a doomed candidate early, without calling further application
 * code.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLocalizationByteAccounting {
	private McpLocalizationByteAccounting() {
	}

	/**
	 * Returns the exact number of UTF-8 bytes the production JSON writer emits
	 * for {@code value} as a JSON string, including both enclosing quotes.
	 *
	 * @param value string to measure
	 * @return exact encoded byte count including the enclosing quotes
	 * @throws IllegalArgumentException if the string contains an unpaired surrogate,
	 *         matching the production writer's own rejection
	 */
	static long encodedStringBytes(@NonNull String value) {
		requireNonNull(value, "value");

		// Both enclosing quotes.
		long bytes = 2;

		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);

			switch (character) {
				case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> bytes += 2;
				default -> {
					if (character < 0x20) {
						bytes += 6;
					} else if (Character.isHighSurrogate(character)) {
						if (index + 1 >= value.length()
								|| !Character.isLowSurrogate(value.charAt(index + 1)))
							throw new IllegalArgumentException(
									"JSON string contains an unpaired high surrogate.");

						++index;
						bytes += 4;
					} else if (Character.isLowSurrogate(character)) {
						throw new IllegalArgumentException(
								"JSON string contains an unpaired low surrogate.");
					} else if (character <= 0x7F) {
						bytes += 1;
					} else if (character <= 0x7FF) {
						bytes += 2;
					} else {
						bytes += 3;
					}
				}
			}
		}

		return bytes;
	}

	/**
	 * Returns the signed change in encoded response bytes from replacing
	 * {@code defaultText} with {@code replacementText} at one localization slot.
	 *
	 * @param defaultText canonical source text being replaced
	 * @param replacementText candidate localized text
	 * @return signed byte delta, negative when the replacement is shorter
	 */
	static long replacementByteDelta(@NonNull String defaultText,
			@NonNull String replacementText) {
		return encodedStringBytes(replacementText) - encodedStringBytes(defaultText);
	}
}
