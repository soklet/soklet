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
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

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
	private final Optional<Duration> startupTimeout;
	@NonNull
	private final Duration startupCancellationTimeout;
	@NonNull
	private final Duration gracefulShutdownTimeout;
	@NonNull
	private final Duration forcedShutdownTimeout;

	InternalLifecyclePolicy(@NonNull Optional<Duration> startupTimeout,
														@NonNull Duration startupCancellationTimeout,
														@NonNull Duration gracefulShutdownTimeout,
														@NonNull Duration forcedShutdownTimeout) {
		this.startupTimeout = validateOptional(startupTimeout, "startupTimeout");
		this.startupCancellationTimeout = validate(startupCancellationTimeout,
				"startupCancellationTimeout");
		this.gracefulShutdownTimeout = validate(gracefulShutdownTimeout,
				"gracefulShutdownTimeout");
		this.forcedShutdownTimeout = validate(forcedShutdownTimeout,
				"forcedShutdownTimeout");
	}

	@NonNull
	static InternalLifecyclePolicy defaults() {
		return new InternalLifecyclePolicy(Optional.of(Duration.ofSeconds(30)),
				Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(3));
	}

	@NonNull
	Optional<Duration> startupTimeout() {
		return this.startupTimeout;
	}

	@NonNull
	Duration startupCancellationTimeout() {
		return this.startupCancellationTimeout;
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
	private static Optional<Duration> validateOptional(
			@NonNull Optional<Duration> duration, @NonNull String name) {
		requireNonNull(duration);
		duration.ifPresent(value -> validate(value, name));
		return duration;
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

enum InternalShutdownPhase {
	GRACEFUL,
	FORCED
}

@Immutable
final class InternalStartupContext implements StartupContext {
	@NonNull
	private final NanoClock clock;
	private final long normalDeadlineNanos;
	private final boolean normalDeadlinePresent;
	private final LongSupplier cancellationDeadlineNanos;
	private final BooleanSupplier cancellationRequested;

	InternalStartupContext(@NonNull NanoClock clock,
											 @NonNull Optional<Long> normalDeadlineNanos,
											 long cancellationDeadlineNanos,
											 @NonNull BooleanSupplier cancellationRequested) {
		this(clock, normalDeadlineNanos, () -> cancellationDeadlineNanos,
				cancellationRequested);
	}

	InternalStartupContext(@NonNull NanoClock clock,
			@NonNull Optional<Long> normalDeadlineNanos,
			@NonNull LongSupplier cancellationDeadlineNanos,
			@NonNull BooleanSupplier cancellationRequested) {
		this.clock = requireNonNull(clock);
		requireNonNull(normalDeadlineNanos);
		this.normalDeadlinePresent = normalDeadlineNanos.isPresent();
		this.normalDeadlineNanos = normalDeadlineNanos.orElse(0L);
		this.cancellationDeadlineNanos = requireNonNull(cancellationDeadlineNanos);
		this.cancellationRequested = requireNonNull(cancellationRequested);
	}

	@NonNull
	Optional<Duration> remainingTime() {
		return getRemainingTime();
	}

	@Override
	@NonNull
	public Optional<Duration> getRemainingTime() {
		if (isCancellationRequested())
			return Optional.of(LifecycleDeadlines.remaining(
					this.cancellationDeadlineNanos.getAsLong(), this.clock.nanoTime()));
		if (!this.normalDeadlinePresent)
			return Optional.empty();
		return Optional.of(LifecycleDeadlines.remaining(
				this.normalDeadlineNanos, this.clock.nanoTime()));
	}

	@Override
	public boolean isCancellationRequested() {
		return this.cancellationRequested.getAsBoolean();
	}

	@NonNull
	Optional<Long> activeDeadlineNanos() {
		if (isCancellationRequested())
			return Optional.of(this.cancellationDeadlineNanos.getAsLong());
		if (!this.normalDeadlinePresent)
			return Optional.empty();
		return Optional.of(this.normalDeadlineNanos);
	}
}

@Immutable
final class InternalShutdownContext implements ShutdownContext {
	@NonNull
	private final InternalShutdownPhase phase;
	@NonNull
	private final NanoClock clock;
	private final long absoluteDeadlineNanos;

	InternalShutdownContext(@NonNull InternalShutdownPhase phase,
													@NonNull NanoClock clock,
													long absoluteDeadlineNanos) {
		this.phase = requireNonNull(phase);
		this.clock = requireNonNull(clock);
		this.absoluteDeadlineNanos = absoluteDeadlineNanos;
	}

	@NonNull
	InternalShutdownPhase phase() {
		return this.phase;
	}

	@Override
	@NonNull
	public ShutdownPhase getPhase() {
		return this.phase == InternalShutdownPhase.GRACEFUL
				? ShutdownPhase.GRACEFUL : ShutdownPhase.FORCED;
	}

	@NonNull
	Duration remainingTime() {
		return getRemainingTime();
	}

	@Override
	@NonNull
	public Duration getRemainingTime() {
		return LifecycleDeadlines.remaining(this.absoluteDeadlineNanos,
				this.clock.nanoTime());
	}

	long absoluteDeadlineNanos() {
		return this.absoluteDeadlineNanos;
	}
}
