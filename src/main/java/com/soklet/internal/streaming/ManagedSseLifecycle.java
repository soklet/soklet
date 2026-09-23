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

package com.soklet.internal.streaming;

import com.soklet.StreamTermination;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Admission and termination guard shared by live and simulated SSE connections.
 * A reservation is acquired before handshake acceptance. Initializer return does
 * not end the connection lifetime; transport termination seals the reservation.
 * {@link #whileOpen(Supplier)} is only for short framework state changes, never
 * application callbacks, I/O, or termination.
 */
@ThreadSafe
public final class ManagedSseLifecycle {
	private final Object lock = new Object();
	private final StreamLifecycleCoordinator.Reservation reservation;
	private final Runnable transportTermination;
	private final long startedAt = System.nanoTime();
	private boolean terminalPublished;
	@Nullable
	private volatile StreamTermination termination;

	public ManagedSseLifecycle(@NonNull StreamLifecycleCoordinator.Reservation reservation,
			@NonNull Runnable transportTermination) {
		this.reservation = requireNonNull(reservation);
		this.transportTermination = requireNonNull(transportTermination);
		this.reservation.bindTermination(this::publishTermination);
	}

	@FunctionalInterface
	public interface CheckedRunnable {
		void run() throws Exception;
	}

	/** Tracks and interrupts the current initializer thread without completing the connection lifetime. */
	public boolean executeInitializer(@NonNull CheckedRunnable initializer) throws Exception {
		requireNonNull(initializer);
		Throwable[] failure = new Throwable[1];
		boolean entered = this.reservation.executeInline(() -> {
			try {
				initializer.run();
			} catch (Throwable throwable) {
				failure[0] = throwable;
				terminate(StreamTerminationReason.PRODUCER_FAILED, throwable);
			}
		});
		if (failure[0] != null)
			rethrow(failure[0]);
		return entered && isOpen();
	}

	/** A short framework-only state action; prior termination wins over activation or enqueue. */
	public <T> T whileOpen(@NonNull Supplier<T> frameworkAction) {
		requireNonNull(frameworkAction);
		synchronized (this.lock) {
			if (!isOpen())
				throw new IllegalStateException("The SSE connection has terminated");
			return frameworkAction.get();
		}
	}

	public boolean isOpen() {
		return this.termination == null && !this.reservation.isCanceled();
	}

	/** The caller closes this proof after actual execution or proven pre-entry retirement. */
	@Nullable
	public StreamLifecycleCoordinator.Reservation.Work retainWork() {
		return this.reservation.retainWork();
	}

	public boolean terminate(@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
		return this.reservation.cancel(requireNonNull(reason), cause);
	}

	@NonNull
	public Optional<StreamTermination> termination() {
		synchronized (this.lock) {
			// The coordinator elects an outcome before invoking its framework hook.
			if (this.termination == null) {
				StreamTerminationReason reason = this.reservation.reason().orElse(null);
				if (reason != null)
					this.termination = outcome(reason, this.reservation.cause().orElse(null));
			}
			return Optional.ofNullable(this.termination);
		}
	}

	private void publishTermination(StreamTerminationReason reason, @Nullable Throwable cause) {
		synchronized (this.lock) {
			if (this.terminalPublished)
				return;
			this.terminalPublished = true;
			if (this.termination == null)
				this.termination = outcome(reason, cause);
		}
		try {
			this.transportTermination.run();
		} catch (Throwable failure) {
			this.reservation.reportCleanupFailure(failure);
		} finally {
			this.reservation.complete();
		}
	}

	private StreamTermination outcome(StreamTerminationReason reason, @Nullable Throwable cause) {
		return StreamTermination.with(reason, Duration.ofNanos(Math.max(0L, System.nanoTime() - this.startedAt)))
				.cause(cause).build();
	}

	private static void rethrow(Throwable failure) throws Exception {
		if (failure instanceof Exception exception)
			throw exception;
		if (failure instanceof Error error)
			throw error;
		throw new IllegalStateException(failure);
	}
}
