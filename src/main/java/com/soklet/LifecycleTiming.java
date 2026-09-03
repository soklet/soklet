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

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;

/** Package-private monotonic time source used by every lifecycle deadline. */
@FunctionalInterface
interface NanoClock {
	long nanoTime();

	@NonNull
	static NanoClock system() {
		return System::nanoTime;
	}
}

/**
 * Waits against one already-computed absolute deadline.  The waiter never
 * derives a new deadline, so a wakeup cannot accidentally reset a phase.
 */
@ThreadSafe
final class DeadlineWaiter {
	@FunctionalInterface
	interface WaitOperation {
		void await(@NonNull Object monitor, long remainingNanos) throws InterruptedException;
	}

	enum Outcome {
		SATISFIED,
		DEADLINE_REACHED
	}

	@NonNull
	private final NanoClock clock;
	@NonNull
	private final WaitOperation waitOperation;
	@NonNull
	private final Object monitor;

	DeadlineWaiter(@NonNull NanoClock clock) {
		this(clock, DeadlineWaiter::waitOnMonitor);
	}

	DeadlineWaiter(@NonNull NanoClock clock, @NonNull WaitOperation waitOperation) {
		this.clock = requireNonNull(clock);
		this.waitOperation = requireNonNull(waitOperation);
		this.monitor = new Object();
	}

	@NonNull
	Outcome await(long absoluteDeadlineNanos,
							 @NonNull BooleanSupplier completionPredicate) throws InterruptedException {
		requireNonNull(completionPredicate);

		synchronized (getMonitor()) {
			for (;;) {
				if (completionPredicate.getAsBoolean())
					return Outcome.SATISFIED;

				long remainingNanos = LifecycleDeadlines.remainingNanos(
						absoluteDeadlineNanos, getClock().nanoTime());
				if (remainingNanos == 0L)
					return Outcome.DEADLINE_REACHED;

				getWaitOperation().await(getMonitor(), remainingNanos);
			}
		}
	}

	void signal() {
		synchronized (getMonitor()) {
			getMonitor().notifyAll();
		}
	}

	@NonNull
	private NanoClock getClock() {
		return this.clock;
	}

	@NonNull
	private WaitOperation getWaitOperation() {
		return this.waitOperation;
	}

	@NonNull
	private Object getMonitor() {
		return this.monitor;
	}

	private static void waitOnMonitor(@NonNull Object monitor,
															 long remainingNanos) throws InterruptedException {
		long millis = remainingNanos / 1_000_000L;
		int nanos = (int) (remainingNanos % 1_000_000L);
		monitor.wait(millis, nanos);
	}
}

/** Overflow-safe absolute-deadline arithmetic. */
final class LifecycleDeadlines {
	private LifecycleDeadlines() {
	}

	static long after(long nowNanos, @NonNull Duration duration) {
		requireNonNull(duration);
		if (duration.isNegative())
			throw new IllegalArgumentException("Lifecycle duration must be >= 0");

		final long durationNanos;
		try {
			durationNanos = duration.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("Lifecycle duration exceeds signed nanoseconds", exception);
		}

		try {
			return Math.addExact(nowNanos, durationNanos);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	static long remainingNanos(long absoluteDeadlineNanos, long nowNanos) {
		if (nowNanos >= absoluteDeadlineNanos)
			return 0L;

		try {
			return Math.subtractExact(absoluteDeadlineNanos, nowNanos);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	@NonNull
	static Duration remaining(long absoluteDeadlineNanos, long nowNanos) {
		return Duration.ofNanos(remainingNanos(absoluteDeadlineNanos, nowNanos));
	}
}

/** Descriptor-neutral draft of the Gate-4 policy. */
@Immutable
final class InternalLifecyclePolicy {
	@NonNull
	private final Duration startupTimeout;
	@NonNull
	private final Duration startupCancelationTimeout;
	@NonNull
	private final Duration gracefulShutdownTimeout;
	@NonNull
	private final Duration forcedShutdownTimeout;

	InternalLifecyclePolicy(@NonNull Duration startupTimeout,
												@NonNull Duration startupCancelationTimeout,
														@NonNull Duration gracefulShutdownTimeout,
														@NonNull Duration forcedShutdownTimeout) {
		this.startupTimeout = validate(startupTimeout, "startupTimeout");
		this.startupCancelationTimeout = validate(startupCancelationTimeout,
				"startupCancelationTimeout");
		this.gracefulShutdownTimeout = validate(gracefulShutdownTimeout,
				"gracefulShutdownTimeout");
		this.forcedShutdownTimeout = validate(forcedShutdownTimeout,
				"forcedShutdownTimeout");
	}

	@NonNull
	static InternalLifecyclePolicy defaults() {
		return new InternalLifecyclePolicy(Duration.ofSeconds(30),
				Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(3));
	}

	@NonNull
	Duration startupTimeout() {
		return this.startupTimeout;
	}

	@NonNull
	Duration startupCancelationTimeout() {
		return this.startupCancelationTimeout;
	}

	@NonNull
	Duration gracefulShutdownTimeout() {
		return this.gracefulShutdownTimeout;
	}

	@NonNull
	Duration forcedShutdownTimeout() {
		return this.forcedShutdownTimeout;
	}

	@NonNull
	private static Duration validate(@NonNull Duration duration,
															 @NonNull String name) {
		requireNonNull(duration, name);
		if (duration.isNegative())
			throw new IllegalArgumentException(name + " must be >= 0");
		try {
			if (duration.toNanos() < 0L)
				throw new IllegalArgumentException(name + " must be >= 0");
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(name + " exceeds signed nanoseconds", exception);
		}
		return duration;
	}
}
