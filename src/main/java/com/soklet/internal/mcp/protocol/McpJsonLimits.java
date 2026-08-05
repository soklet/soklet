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

/**
 * Explicit resource bounds for the internal JSON codec.
 *
 * <p>The package-private production and maximum-supported profiles are fixed
 * from pinned-corpus, adversarial-boundary, and cross-JDK evidence. Public
 * callers may construct a stricter profile, but cannot raise a field beyond
 * the implementation's reviewed hard ceiling.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public record McpJsonLimits(int maximumInputBytes, int maximumNestingDepth,
		int maximumTokenLengthInCharacters, int maximumStringLengthInCharacters,
		int maximumNumberLengthInCharacters, int maximumExponentMagnitude,
		int maximumNodeCount, int maximumOutputBytes) {
	private static final int DEFAULT_MAXIMUM_INPUT_BYTES = 4 * 1_024 * 1_024;
	private static final int DEFAULT_MAXIMUM_NESTING_DEPTH = 128;
	private static final int DEFAULT_MAXIMUM_TOKEN_LENGTH_IN_CHARACTERS =
			1_024 * 1_024;
	private static final int DEFAULT_MAXIMUM_STRING_LENGTH_IN_CHARACTERS =
			1_024 * 1_024;
	private static final int DEFAULT_MAXIMUM_NUMBER_LENGTH_IN_CHARACTERS = 1_024;
	private static final int DEFAULT_MAXIMUM_EXPONENT_MAGNITUDE = 10_000;
	private static final int DEFAULT_MAXIMUM_NODE_COUNT = 100_000;
	private static final int DEFAULT_MAXIMUM_OUTPUT_BYTES = 4 * 1_024 * 1_024;

	private static final int MAXIMUM_SUPPORTED_INPUT_BYTES = 16 * 1_024 * 1_024;
	private static final int MAXIMUM_SUPPORTED_NESTING_DEPTH = 256;
	private static final int MAXIMUM_SUPPORTED_TOKEN_LENGTH_IN_CHARACTERS =
			4 * 1_024 * 1_024;
	private static final int MAXIMUM_SUPPORTED_STRING_LENGTH_IN_CHARACTERS =
			4 * 1_024 * 1_024;
	private static final int MAXIMUM_SUPPORTED_NUMBER_LENGTH_IN_CHARACTERS = 4_096;
	private static final int MAXIMUM_SUPPORTED_EXPONENT_MAGNITUDE = 100_000;
	private static final int MAXIMUM_SUPPORTED_NODE_COUNT = 1_000_000;
	private static final int MAXIMUM_SUPPORTED_OUTPUT_BYTES = 16 * 1_024 * 1_024;

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

		requireAtMost(maximumInputBytes, MAXIMUM_SUPPORTED_INPUT_BYTES,
				"maximumInputBytes");
		requireAtMost(maximumNestingDepth, MAXIMUM_SUPPORTED_NESTING_DEPTH,
				"maximumNestingDepth");
		requireAtMost(maximumTokenLengthInCharacters,
				MAXIMUM_SUPPORTED_TOKEN_LENGTH_IN_CHARACTERS,
				"maximumTokenLengthInCharacters");
		requireAtMost(maximumStringLengthInCharacters,
				MAXIMUM_SUPPORTED_STRING_LENGTH_IN_CHARACTERS,
				"maximumStringLengthInCharacters");
		requireAtMost(maximumNumberLengthInCharacters,
				MAXIMUM_SUPPORTED_NUMBER_LENGTH_IN_CHARACTERS,
				"maximumNumberLengthInCharacters");
		requireAtMost(maximumExponentMagnitude,
				MAXIMUM_SUPPORTED_EXPONENT_MAGNITUDE,
				"maximumExponentMagnitude");
		requireAtMost(maximumNodeCount, MAXIMUM_SUPPORTED_NODE_COUNT,
				"maximumNodeCount");
		requireAtMost(maximumOutputBytes, MAXIMUM_SUPPORTED_OUTPUT_BYTES,
				"maximumOutputBytes");
	}

	/**
	 * Returns the reviewed production JSON limits.
	 *
	 * @return the production limit profile
	 */
	@NonNull
	public static McpJsonLimits productionDefaults() {
		return new McpJsonLimits(DEFAULT_MAXIMUM_INPUT_BYTES,
				DEFAULT_MAXIMUM_NESTING_DEPTH,
				DEFAULT_MAXIMUM_TOKEN_LENGTH_IN_CHARACTERS,
				DEFAULT_MAXIMUM_STRING_LENGTH_IN_CHARACTERS,
				DEFAULT_MAXIMUM_NUMBER_LENGTH_IN_CHARACTERS,
				DEFAULT_MAXIMUM_EXPONENT_MAGNITUDE,
				DEFAULT_MAXIMUM_NODE_COUNT, DEFAULT_MAXIMUM_OUTPUT_BYTES);
	}

	@NonNull
	static McpJsonLimits maximumSupported() {
		return new McpJsonLimits(MAXIMUM_SUPPORTED_INPUT_BYTES,
				MAXIMUM_SUPPORTED_NESTING_DEPTH,
				MAXIMUM_SUPPORTED_TOKEN_LENGTH_IN_CHARACTERS,
				MAXIMUM_SUPPORTED_STRING_LENGTH_IN_CHARACTERS,
				MAXIMUM_SUPPORTED_NUMBER_LENGTH_IN_CHARACTERS,
				MAXIMUM_SUPPORTED_EXPONENT_MAGNITUDE,
				MAXIMUM_SUPPORTED_NODE_COUNT, MAXIMUM_SUPPORTED_OUTPUT_BYTES);
	}

	private static void requirePositive(int value, @NonNull String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive.");
	}

	private static void requireNonNegative(int value, @NonNull String name) {
		if (value < 0)
			throw new IllegalArgumentException(name + " must not be negative.");
	}

	private static void requireAtMost(int value, int maximum, @NonNull String name) {
		if (value > maximum)
			throw new IllegalArgumentException(name + " must not exceed "
					+ maximum + ".");
	}
}
