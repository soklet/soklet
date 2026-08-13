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
 * Immutable framework-owned MCP presentation field supplied to a localization
 * provider.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpLocalizableText {
	@NonNull
	private final McpTextCoordinate coordinate;
	@NonNull
	private final String defaultText;

	McpLocalizableText(@NonNull McpTextCoordinate coordinate,
			@NonNull String defaultText) {
		this.coordinate = requireNonNull(coordinate);
		this.defaultText = requireNonNull(defaultText);
		if (defaultText.isBlank())
			throw new IllegalArgumentException(
					"Localizable MCP default text must not be blank.");
	}

	/** @return stable structured coordinate */
	@NonNull
	public McpTextCoordinate getCoordinate() {
		return this.coordinate;
	}

	/** @return canonical configured default text and safe fallback */
	@NonNull
	public String getDefaultText() {
		return this.defaultText;
	}

	/** @return whether coordinate and canonical default text are equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpLocalizableText text))
			return false;
		return this.coordinate.equals(text.coordinate)
				&& this.defaultText.equals(text.defaultText);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return 31 * this.coordinate.hashCode() + this.defaultText.hashCode();
	}

	/** @return rendering that never exposes default text */
	@Override
	@NonNull
	public String toString() {
		return "McpLocalizableText{coordinate=<redacted>, "
				+ "defaultText=<redacted>}";
	}
}
