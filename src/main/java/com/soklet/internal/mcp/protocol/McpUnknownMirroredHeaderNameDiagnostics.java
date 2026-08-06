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
import java.util.Optional;
import java.util.function.BiConsumer;

import static java.util.Objects.requireNonNull;

/**
 * Bounded, name-only diagnostic delivery for unknown MCP mirrored headers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpUnknownMirroredHeaderNameDiagnostics {
	static final int MAXIMUM_EVENTS_PER_WINDOW = 10;
	static final int MAXIMUM_DISPLAYED_NAME_BYTES = 128;
	static final long WINDOW_NANOSECONDS = 60_000_000_000L;

	@NonNull
	private final McpApplicationClock clock;
	@NonNull
	private final Optional<@NonNull BiConsumer<@NonNull String, @NonNull String>>
			consumer;
	@NonNull
	private final Object lock;
	@NonNull
	private final long[] emissionTimestamps;
	private int oldestTimestampIndex;
	private int timestampCount;

	McpUnknownMirroredHeaderNameDiagnostics(@NonNull McpApplicationClock clock,
			@NonNull Optional<@NonNull BiConsumer<@NonNull String, @NonNull String>>
					consumer) {
		this.clock = requireNonNull(clock);
		this.consumer = requireNonNull(consumer);
		this.lock = new Object();
		this.emissionTimestamps = new long[MAXIMUM_EVENTS_PER_WINDOW];
	}

	boolean enabled() {
		return this.consumer.isPresent();
	}

	void observe(@NonNull String endpointPath, @NonNull String headerName) {
		requireNonNull(endpointPath);
		requireNonNull(headerName);
		if (this.consumer.isEmpty())
			return;

		try {
			if (!reserve())
				return;
			String sanitizedName = sanitizeHeaderName(headerName);
			this.consumer.orElseThrow().accept(endpointPath, sanitizedName);
		} catch (Throwable ignored) {
			// Diagnostic delivery must not affect request processing.
		}
	}

	private boolean reserve() {
		synchronized (this.lock) {
			long nowNanos = this.clock.nanoTime();
			if (this.timestampCount > 0) {
				int newestTimestampIndex = (this.oldestTimestampIndex
						+ this.timestampCount - 1)
						% MAXIMUM_EVENTS_PER_WINDOW;
				long newestTimestamp =
						this.emissionTimestamps[newestTimestampIndex];
				if (nowNanos - newestTimestamp < 0)
					return false;
			}

			while (this.timestampCount > 0) {
				long oldestTimestamp =
						this.emissionTimestamps[this.oldestTimestampIndex];
				if (nowNanos - oldestTimestamp < WINDOW_NANOSECONDS)
					break;
				this.oldestTimestampIndex = (this.oldestTimestampIndex + 1)
						% MAXIMUM_EVENTS_PER_WINDOW;
				this.timestampCount--;
			}

			if (this.timestampCount == MAXIMUM_EVENTS_PER_WINDOW)
				return false;

			int insertionIndex = (this.oldestTimestampIndex + this.timestampCount)
					% MAXIMUM_EVENTS_PER_WINDOW;
			this.emissionTimestamps[insertionIndex] = nowNanos;
			this.timestampCount++;
			return true;
		}
	}

	@NonNull
	static String sanitizeHeaderName(@NonNull String headerName) {
		requireNonNull(headerName);
		int length = Math.min(headerName.length(), MAXIMUM_DISPLAYED_NAME_BYTES);
		StringBuilder sanitized = new StringBuilder(length);
		for (int index = 0; index < length; index++) {
			char character = headerName.charAt(index);
			sanitized.append(isAsciiTokenCharacter(character) ? character : '_');
		}
		return sanitized.toString();
	}

	private static boolean isAsciiTokenCharacter(char character) {
		if (character >= '0' && character <= '9'
				|| character >= 'A' && character <= 'Z'
				|| character >= 'a' && character <= 'z')
			return true;
		return switch (character) {
			case '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`',
					'|', '~' -> true;
			default -> false;
		};
	}
}
