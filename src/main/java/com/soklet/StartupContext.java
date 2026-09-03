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

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static java.util.Objects.requireNonNull;

/**
 * Framework-created advisory timing and cancelation information for transport
 * startup.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class StartupContext {
	@NonNull
	private final NanoClock clock;
	private final long normalDeadlineNanos;
	@NonNull
	private final LongSupplier cancelationDeadlineNanos;
	@NonNull
	private final BooleanSupplier cancelationRequested;

	StartupContext(@NonNull NanoClock clock,
			long normalDeadlineNanos,
			long cancelationDeadlineNanos,
			@NonNull BooleanSupplier cancelationRequested) {
		this(clock, normalDeadlineNanos, () -> cancelationDeadlineNanos,
				cancelationRequested);
	}

	StartupContext(@NonNull NanoClock clock,
			long normalDeadlineNanos,
			@NonNull LongSupplier cancelationDeadlineNanos,
			@NonNull BooleanSupplier cancelationRequested) {
		this.clock = requireNonNull(clock);
		this.normalDeadlineNanos = normalDeadlineNanos;
		this.cancelationDeadlineNanos = requireNonNull(cancelationDeadlineNanos);
		this.cancelationRequested = requireNonNull(cancelationRequested);
	}

	/**
	 * Acquires the time remaining before the active startup boundary.
	 *
	 * @return the remaining time, clamped to zero once the boundary is reached
	 */
	@NonNull
	public Duration getRemainingTime() {
		if (isCancelationRequested())
			return LifecycleDeadlines.remaining(
					this.cancelationDeadlineNanos.getAsLong(), this.clock.nanoTime());
		return LifecycleDeadlines.remaining(
				this.normalDeadlineNanos, this.clock.nanoTime());
	}

	/**
	 * Is startup cancelation currently requested?
	 *
	 * @return {@code true} if cancelation is requested, {@code false} otherwise
	 */
	@NonNull
	public Boolean isCancelationRequested() {
		return this.cancelationRequested.getAsBoolean();
	}

	long activeDeadlineNanos() {
		if (isCancelationRequested())
			return this.cancelationDeadlineNanos.getAsLong();
		return this.normalDeadlineNanos;
	}
}
