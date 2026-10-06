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
 * <p>Equality and hashing compare numeric values regardless of decimal scale.
 * For example, {@code 1}, {@code 1.0} and {@code 1E+0} are equal. The supplied
 * {@link BigDecimal}, including its scale, remains available from
 * {@link #getValue()}.
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

	/** @return exactly represented numeric value, retaining the supplied scale */
	@NonNull
	public BigDecimal getValue() {
		return this.value;
	}

	/** @return whether this object contains the same numeric value regardless of scale */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpJsonNumber number))
			return false;
		return this.value.compareTo(number.value) == 0;
	}

	/** @return numeric hash code independent of decimal scale */
	@Override
	public int hashCode() {
		if (this.value.signum() == 0)
			return 0;
		// Normalize the coefficient with a zero starting scale. Stripping the
		// original decimal can underflow an extreme supplied scale; keeping the
		// combined scale in a long handles every supported BigDecimal value
		// without rendering or expanding its exponent.
		BigDecimal coefficient = new BigDecimal(this.value.unscaledValue())
				.stripTrailingZeros();
		long normalizedScale = (long) this.value.scale() + coefficient.scale();
		return 31 * coefficient.unscaledValue().hashCode() + Long.hashCode(normalizedScale);
	}

	/** @return redacted diagnostic rendering */
	@Override
	@NonNull
	public String toString() {
		return "McpJsonNumber{value=<redacted>}";
	}
}
