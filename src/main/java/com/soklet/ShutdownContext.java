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

import static java.util.Objects.requireNonNull;

/**
 * Framework-created advisory phase and deadline information borrowed by a
 * transport for one shutdown invocation. Soklet owns the context and its
 * absolute phase boundary; a transport may inspect it but cannot extend the
 * deadline.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ShutdownContext {
	@NonNull
	private final ShutdownPhase shutdownPhase;
	@NonNull
	private final NanoClock clock;
	private final long absoluteDeadlineNanos;

	ShutdownContext(@NonNull ShutdownPhase shutdownPhase,
			@NonNull NanoClock clock, long absoluteDeadlineNanos) {
		this.shutdownPhase = requireNonNull(shutdownPhase);
		this.clock = requireNonNull(clock);
		this.absoluteDeadlineNanos = absoluteDeadlineNanos;
	}

	/**
	 * Acquires the current shutdown phase.
	 *
	 * @return the current shutdown phase
	 */
	@NonNull
	public ShutdownPhase getShutdownPhase() {
		return this.shutdownPhase;
	}

	/**
	 * Acquires the time remaining before the current phase boundary.
	 *
	 * @return the remaining time, clamped to zero
	 */
	@NonNull
	public Duration getRemainingTime() {
		return LifecycleDeadlines.remaining(this.absoluteDeadlineNanos,
				this.clock.nanoTime());
	}

	long absoluteDeadlineNanos() {
		return this.absoluteDeadlineNanos;
	}
}
