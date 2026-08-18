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
import java.math.BigDecimal;

import static java.util.Objects.requireNonNull;

/**
 * An immutable, exactly represented JSON number.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpJsonNumber implements McpJsonValue {
	@NonNull
	private final BigDecimal value;

	/**
	 * Creates an immutable, exactly represented JSON number from its value.
	 *
	 * @param value the non-null numeric value
	 * @return immutable JSON number
	 */
	@NonNull
	public static McpJsonNumber fromValue(@NonNull BigDecimal value) {
		return new McpJsonNumber(value);
	}

	private McpJsonNumber(@NonNull BigDecimal value) {
		this.value = requireNonNull(value);
	}

	/** @return exactly represented numeric value */
	@NonNull
	public BigDecimal getValue() {
		return this.value;
	}

	/** @return whether this object contains the same exact numeric value */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpJsonNumber number))
			return false;
		return this.value.equals(number.value);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		return this.value.hashCode();
	}

	/** @return redacted diagnostic rendering */
	@Override
	@NonNull
	public String toString() {
		return "McpJsonNumber{value=<redacted>}";
	}
}
