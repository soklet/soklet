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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.math.BigInteger;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpJsonIntegerSupport {
	private McpJsonIntegerSupport() {
	}

	@NonNull
	static BigInteger toSerializableInteger(@NonNull BigDecimal decimal,
			@NonNull McpJsonLimits limits) {
		requireNonNull(decimal);
		requireNonNull(limits);
		BigDecimal normalized = decimal.stripTrailingZeros();

		if (normalized.scale() > 0)
			throw new IllegalArgumentException("The JSON number is not an integer.");

		long integerDigits = normalized.signum() == 0
				? 1
				: (long) normalized.precision() - normalized.scale();
		long tokenLength = integerDigits + (normalized.signum() < 0 ? 1 : 0);

		if (tokenLength > limits.maximumNumberLengthInCharacters()
				|| tokenLength > limits.maximumTokenLengthInCharacters()
				|| integerDigits - 1 > limits.maximumExponentMagnitude())
			throw new IllegalArgumentException(
					"The expanded JSON integer exceeds the configured number limit.");

		return normalized.toBigIntegerExact();
	}
}
