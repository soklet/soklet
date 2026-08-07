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
import java.time.Duration;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Full-width timestamp used by the protected request-state wire format.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpRequestStateTimestamp(long epochSecond, int nanoAdjustment)
		implements Comparable<McpRequestStateTimestamp> {
	private static final int NANOS_PER_SECOND = 1_000_000_000;

	McpRequestStateTimestamp {
		if (nanoAdjustment < 0 || nanoAdjustment >= NANOS_PER_SECOND)
			throw new IllegalArgumentException(
					"Request-state timestamp nano adjustment is out of range.");
	}

	@NonNull
	static McpRequestStateTimestamp fromInstant(@NonNull Instant instant) {
		requireNonNull(instant);
		return new McpRequestStateTimestamp(
				instant.getEpochSecond(), instant.getNano());
	}

	@NonNull
	McpRequestStateTimestamp plus(@NonNull Duration duration) {
		requirePositiveDuration(duration);
		long seconds = Math.addExact(epochSecond, duration.getSeconds());
		int nanos = nanoAdjustment + duration.getNano();

		if (nanos >= NANOS_PER_SECOND) {
			seconds = Math.addExact(seconds, 1L);
			nanos -= NANOS_PER_SECOND;
		}

		return new McpRequestStateTimestamp(seconds, nanos);
	}

	@Override
	public int compareTo(@NonNull McpRequestStateTimestamp other) {
		requireNonNull(other);
		int secondsComparison = Long.compare(epochSecond, other.epochSecond);
		return secondsComparison != 0
				? secondsComparison
				: Integer.compare(nanoAdjustment, other.nanoAdjustment);
	}

	static void requirePositiveDuration(@NonNull Duration duration) {
		requireNonNull(duration);
		if (duration.isZero() || duration.isNegative())
			throw new IllegalArgumentException(
					"Maximum request-state lifetime must be positive.");

		try {
			if (duration.toNanos() < 1L)
				throw new IllegalArgumentException(
						"Maximum request-state lifetime must be positive.");
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					"Maximum request-state lifetime must fit in signed nanoseconds.",
					exception);
		}
	}
}
