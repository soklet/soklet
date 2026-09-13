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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Explicit resource bounds for the internal JSON codec.
 *
 * <p>The production and maximum-supported profiles are fixed from
 * pinned-corpus, adversarial-boundary, and cross-JDK evidence. The
 * maximum-supported profile is the 16 MiB public transport ceiling. The public
 * constructor enforces that ceiling for every field. Dedicated package-private
 * factories create the two larger durable-task profiles needed to retain an
 * accepted request with framework schema and wrapper state; those profiles do
 * not widen transport acceptance.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpJsonLimits {
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

	private static final int DURABLE_TASK_ORIGIN_MAXIMUM_BYTES =
			32 * 1_024 * 1_024;
	private static final int DURABLE_TASK_ORIGIN_MAXIMUM_NODE_COUNT = 2_000_000;

	private final int maximumInputBytes;
	private final int maximumNestingDepth;
	private final int maximumTokenLengthInCharacters;
	private final int maximumStringLengthInCharacters;
	private final int maximumNumberLengthInCharacters;
	private final int maximumExponentMagnitude;
	private final int maximumNodeCount;
	private final int maximumOutputBytes;

	/**
	 * Creates a transport-safe JSON limit profile.
	 *
	 * @throws IllegalArgumentException if a value is invalid or exceeds the
	 * reviewed public transport ceiling
	 */
	public McpJsonLimits(int maximumInputBytes, int maximumNestingDepth,
			int maximumTokenLengthInCharacters,
			int maximumStringLengthInCharacters,
			int maximumNumberLengthInCharacters,
			int maximumExponentMagnitude, int maximumNodeCount,
			int maximumOutputBytes) {
		this(maximumInputBytes, maximumNestingDepth,
				maximumTokenLengthInCharacters,
				maximumStringLengthInCharacters,
				maximumNumberLengthInCharacters, maximumExponentMagnitude,
				maximumNodeCount, maximumOutputBytes, false);
	}

	private McpJsonLimits(int maximumInputBytes, int maximumNestingDepth,
			int maximumTokenLengthInCharacters,
			int maximumStringLengthInCharacters,
			int maximumNumberLengthInCharacters,
			int maximumExponentMagnitude, int maximumNodeCount,
			int maximumOutputBytes, boolean allowDurableTaskHeadroom) {
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

		int maximumBytes = allowDurableTaskHeadroom
				? DURABLE_TASK_ORIGIN_MAXIMUM_BYTES
				: MAXIMUM_SUPPORTED_INPUT_BYTES;
		int maximumNodes = allowDurableTaskHeadroom
				? DURABLE_TASK_ORIGIN_MAXIMUM_NODE_COUNT
				: MAXIMUM_SUPPORTED_NODE_COUNT;
		requireAtMost(maximumInputBytes, maximumBytes,
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
		requireAtMost(maximumNodeCount, maximumNodes,
				"maximumNodeCount");
		requireAtMost(maximumOutputBytes, maximumBytes,
				"maximumOutputBytes");

		this.maximumInputBytes = maximumInputBytes;
		this.maximumNestingDepth = maximumNestingDepth;
		this.maximumTokenLengthInCharacters =
				maximumTokenLengthInCharacters;
		this.maximumStringLengthInCharacters =
				maximumStringLengthInCharacters;
		this.maximumNumberLengthInCharacters =
				maximumNumberLengthInCharacters;
		this.maximumExponentMagnitude = maximumExponentMagnitude;
		this.maximumNodeCount = maximumNodeCount;
		this.maximumOutputBytes = maximumOutputBytes;
	}

	public int maximumInputBytes() {
		return this.maximumInputBytes;
	}

	public int maximumNestingDepth() {
		return this.maximumNestingDepth;
	}

	public int maximumTokenLengthInCharacters() {
		return this.maximumTokenLengthInCharacters;
	}

	public int maximumStringLengthInCharacters() {
		return this.maximumStringLengthInCharacters;
	}

	public int maximumNumberLengthInCharacters() {
		return this.maximumNumberLengthInCharacters;
	}

	public int maximumExponentMagnitude() {
		return this.maximumExponentMagnitude;
	}

	public int maximumNodeCount() {
		return this.maximumNodeCount;
	}

	public int maximumOutputBytes() {
		return this.maximumOutputBytes;
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return this == other
				|| other instanceof McpJsonLimits limits
				&& this.maximumInputBytes == limits.maximumInputBytes
				&& this.maximumNestingDepth == limits.maximumNestingDepth
				&& this.maximumTokenLengthInCharacters
						== limits.maximumTokenLengthInCharacters
				&& this.maximumStringLengthInCharacters
						== limits.maximumStringLengthInCharacters
				&& this.maximumNumberLengthInCharacters
						== limits.maximumNumberLengthInCharacters
				&& this.maximumExponentMagnitude
						== limits.maximumExponentMagnitude
				&& this.maximumNodeCount == limits.maximumNodeCount
				&& this.maximumOutputBytes == limits.maximumOutputBytes;
	}

	@Override
	public int hashCode() {
		int result = Integer.hashCode(this.maximumInputBytes);
		result = 31 * result + Integer.hashCode(this.maximumNestingDepth);
		result = 31 * result
				+ Integer.hashCode(this.maximumTokenLengthInCharacters);
		result = 31 * result
				+ Integer.hashCode(this.maximumStringLengthInCharacters);
		result = 31 * result
				+ Integer.hashCode(this.maximumNumberLengthInCharacters);
		result = 31 * result
				+ Integer.hashCode(this.maximumExponentMagnitude);
		result = 31 * result + Integer.hashCode(this.maximumNodeCount);
		result = 31 * result + Integer.hashCode(this.maximumOutputBytes);
		return result;
	}

	@Override
	public String toString() {
		return "McpJsonLimits[maximumInputBytes=" + this.maximumInputBytes
				+ ", maximumNestingDepth=" + this.maximumNestingDepth
				+ ", maximumTokenLengthInCharacters="
				+ this.maximumTokenLengthInCharacters
				+ ", maximumStringLengthInCharacters="
				+ this.maximumStringLengthInCharacters
				+ ", maximumNumberLengthInCharacters="
				+ this.maximumNumberLengthInCharacters
				+ ", maximumExponentMagnitude="
				+ this.maximumExponentMagnitude
				+ ", maximumNodeCount=" + this.maximumNodeCount
				+ ", maximumOutputBytes=" + this.maximumOutputBytes + "]";
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

	/**
	 * Returns the reviewed ceiling for public transport JSON. Internal wrapper
	 * state must use its dedicated profile rather than widening this contract.
	 *
	 * @return transport-supported limit profile
	 */
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

	/**
	 * Returns the internal profile used to validate arguments recovered from a
	 * durable task origin. A transport may raise only its aggregate input-byte
	 * allowance; all structural and scalar limits remain the production
	 * defaults. The durable output allowance accommodates canonical rendering,
	 * whose byte length need not equal the admitted wire representation.
	 *
	 * @return restored task-argument limit profile
	 */
	@NonNull
	static McpJsonLimits durableTaskArguments() {
		return new McpJsonLimits(MAXIMUM_SUPPORTED_INPUT_BYTES,
				DEFAULT_MAXIMUM_NESTING_DEPTH,
				DEFAULT_MAXIMUM_TOKEN_LENGTH_IN_CHARACTERS,
				DEFAULT_MAXIMUM_STRING_LENGTH_IN_CHARACTERS,
				DEFAULT_MAXIMUM_NUMBER_LENGTH_IN_CHARACTERS,
				DEFAULT_MAXIMUM_EXPONENT_MAGNITUDE,
				DEFAULT_MAXIMUM_NODE_COUNT,
				DURABLE_TASK_ORIGIN_MAXIMUM_BYTES, true);
	}

	/**
	 * Returns the internal durable-origin profile. This is not a transport
	 * acceptance profile: it exists only to hold an accepted public request plus
	 * framework-retained task state without introducing a narrower hidden bound.
	 *
	 * @return durable-task-origin limit profile
	 */
	@NonNull
	static McpJsonLimits durableTaskOrigin() {
		return new McpJsonLimits(DURABLE_TASK_ORIGIN_MAXIMUM_BYTES,
				MAXIMUM_SUPPORTED_NESTING_DEPTH,
				MAXIMUM_SUPPORTED_TOKEN_LENGTH_IN_CHARACTERS,
				MAXIMUM_SUPPORTED_STRING_LENGTH_IN_CHARACTERS,
				MAXIMUM_SUPPORTED_NUMBER_LENGTH_IN_CHARACTERS,
				MAXIMUM_SUPPORTED_EXPONENT_MAGNITUDE,
				DURABLE_TASK_ORIGIN_MAXIMUM_NODE_COUNT,
				DURABLE_TASK_ORIGIN_MAXIMUM_BYTES, true);
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
