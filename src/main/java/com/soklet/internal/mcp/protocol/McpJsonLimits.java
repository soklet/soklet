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

/**
 * Explicit resource bounds for the internal JSON codec.
 *
 * <p>Phase 2 intentionally has no default instance yet. The final production
 * values are selected from corpus, adversarial, and cross-JDK evidence rather
 * than frozen by this first implementation slice.</p>
 */
public record McpJsonLimits(int maximumInputBytes, int maximumNestingDepth,
		int maximumTokenLengthInCharacters, int maximumStringLengthInCharacters,
		int maximumNumberLengthInCharacters, int maximumExponentMagnitude,
		int maximumNodeCount, int maximumOutputBytes) {
	private static final int MAXIMUM_SAFE_RECURSIVE_DEPTH = 256;

	public McpJsonLimits {
		requirePositive(maximumInputBytes, "maximumInputBytes");
		requirePositive(maximumNestingDepth, "maximumNestingDepth");
		requirePositive(maximumTokenLengthInCharacters,
				"maximumTokenLengthInCharacters");
		requirePositive(maximumStringLengthInCharacters,
				"maximumStringLengthInCharacters");
		requirePositive(maximumNumberLengthInCharacters,
				"maximumNumberLengthInCharacters");
		requireNonNegative(maximumExponentMagnitude, "maximumExponentMagnitude");
		requirePositive(maximumNodeCount, "maximumNodeCount");
		requirePositive(maximumOutputBytes, "maximumOutputBytes");

		if (maximumNestingDepth > MAXIMUM_SAFE_RECURSIVE_DEPTH)
			throw new IllegalArgumentException("maximumNestingDepth must not exceed "
					+ MAXIMUM_SAFE_RECURSIVE_DEPTH + ".");
	}

	private static void requirePositive(int value, String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive.");
	}

	private static void requireNonNegative(int value, String name) {
		if (value < 0)
			throw new IllegalArgumentException(name + " must not be negative.");
	}
}
