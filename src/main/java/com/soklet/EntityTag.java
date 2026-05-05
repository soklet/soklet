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
import java.util.Objects;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Immutable representation of one HTTP entity tag.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class EntityTag {
	@NonNull
	private final String value;
	@NonNull
	private final Boolean weak;

	@NonNull
	public static EntityTag fromStrongValue(@NonNull String value) {
		requireNonNull(value);
		return new EntityTag(value, false);
	}

	@NonNull
	public static EntityTag fromWeakValue(@NonNull String value) {
		requireNonNull(value);
		return new EntityTag(value, true);
	}

	@NonNull
	public static Optional<EntityTag> fromHeaderValue(@Nullable String headerValue) {
		String trimmed = Utilities.trimAggressivelyToNull(headerValue);

		if (trimmed == null || "*".equals(trimmed))
			return Optional.empty();

		Boolean weak = false;
		String remaining = trimmed;

		if (remaining.regionMatches(true, 0, "W/", 0, 2)) {
			weak = true;
			remaining = remaining.substring(2);
		}

		if (remaining.length() < 2 || remaining.charAt(0) != '"' || remaining.charAt(remaining.length() - 1) != '"')
			return Optional.empty();

		String value = remaining.substring(1, remaining.length() - 1);

		if (!isValidOpaqueTag(value))
			return Optional.empty();

		return Optional.of(new EntityTag(value, weak));
	}

	private EntityTag(@NonNull String value,
										@NonNull Boolean weak) {
		requireNonNull(value);
		requireNonNull(weak);

		if (!isValidOpaqueTag(value))
			throw new IllegalArgumentException(format("Invalid entity-tag value '%s'.", value));

		this.value = value;
		this.weak = weak;
	}

	@NonNull
	public Boolean isWeak() {
		return this.weak;
	}

	@NonNull
	public String getValue() {
		return this.value;
	}

	@NonNull
	public String toHeaderValue() {
		return format("%s\"%s\"", isWeak() ? "W/" : "", getValue());
	}

	@NonNull
	public Boolean stronglyMatches(@NonNull EntityTag other) {
		requireNonNull(other);
		return !isWeak() && !other.isWeak() && getValue().equals(other.getValue());
	}

	@NonNull
	public Boolean weaklyMatches(@NonNull EntityTag other) {
		requireNonNull(other);
		return getValue().equals(other.getValue());
	}

	@Override
	public String toString() {
		return format("%s{value=%s, weak=%s}", getClass().getSimpleName(), getValue(), isWeak());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof EntityTag entityTag))
			return false;

		return Objects.equals(getValue(), entityTag.getValue())
				&& Objects.equals(isWeak(), entityTag.isWeak());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getValue(), isWeak());
	}

	private static boolean isValidOpaqueTag(@NonNull String value) {
		requireNonNull(value);

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (c == '"' || c < 0x21 || c == 0x7F)
				return false;
		}

		return true;
	}
}
