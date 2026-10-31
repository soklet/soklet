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
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Reserves paired HTTP observations before start and delivers finish away from transport threads.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLegacyHttpObservationDispatcher {
	private final Object lock = new Object();
	private final long transientCapacity;
	private final long getCapacity;
	private final ExecutorService executor;
	private final McpApplicationHandlerDispatcher dispatcher;
	private final Runnable exitObserver;
	private long reservations;
	private long getReservations;
	private boolean accepting = true;
	private boolean stopping;

	McpLegacyHttpObservationDispatcher(int concurrency, int queueCapacity, @NonNull Runnable exitObserver) {
		this(concurrency, queueCapacity, 0L, exitObserver);
	}

	McpLegacyHttpObservationDispatcher(int concurrency, int queueCapacity,
			long maximumGetReservations, @NonNull Runnable exitObserver) {
		this(concurrency, queueCapacity, maximumGetReservations, exitObserver, "soklet-mcp-handler-");
	}

	McpLegacyHttpObservationDispatcher(int concurrency, int queueCapacity,
			long maximumGetReservations, @NonNull Runnable exitObserver, @NonNull String threadPrefix) {
		if (concurrency < 1 || queueCapacity < 1)
			throw new IllegalArgumentException("Observation concurrency and queue capacity must be positive.");
		if (maximumGetReservations < 0L)
			throw new IllegalArgumentException("The GET observation limit must be nonnegative.");
		this.transientCapacity = (long) concurrency + queueCapacity;
		// A finish queue can contain at most Integer.MAX_VALUE elements. The
		// queue is lazy; configured limits never allocate storage before use.
		this.getCapacity = Math.min(maximumGetReservations,
				Math.max(0L, Integer.MAX_VALUE - transientCapacity));
		this.exitObserver = requireNonNull(exitObserver);
		// Java 21 virtual threads can pin their carriers when an observer blocks
		// while holding a monitor. Keep application observation on daemon platform
		// threads so a slow logger cannot stall unrelated virtual-thread handlers.
		// The dispatcher owns the waiting callbacks. This small second queue only
		// covers a worker that has released its dispatcher slot and is returning
		// from the previous executor envelope; a direct handoff can reject there.
		ThreadPoolExecutor observationExecutor = new ThreadPoolExecutor(concurrency,
				concurrency, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(concurrency),
				Thread.ofPlatform().daemon().name(requireNonNull(threadPrefix), 1L).factory(),
				new ThreadPoolExecutor.AbortPolicy());
		observationExecutor.allowCoreThreadTimeOut(true);
		this.executor = observationExecutor;
		this.dispatcher = new McpApplicationHandlerDispatcher(concurrency,
				(int) (queueCapacity + getCapacity), executor);
	}

	@Nullable Reservation reserve() {
		return reserve(false);
	}

	@Nullable Reservation reserve(boolean get) {
		synchronized (lock) {
			if (!accepting) return null;
			boolean lifetime = get && getReservations < getCapacity;
			if (!lifetime && reservations - getReservations == transientCapacity) return null;
			reservations++;
			if (lifetime) getReservations++;
			return new Reservation(lifetime);
		}
	}

	final class Reservation {
		private final AtomicBoolean submitted = new AtomicBoolean();
		private final boolean get;
		private Reservation(boolean get) { this.get = get; }
		void finish(@NonNull Runnable callback, @NonNull Runnable physicalExit) {
			requireNonNull(callback); requireNonNull(physicalExit);
			if (!submitted.compareAndSet(false, true)) return;
			Runnable exited = () -> {
				try { physicalExit.run(); }
				finally {
					synchronized (lock) {
						reservations--;
						if (get) getReservations--;
						if (stopping && reservations == 0) executor.shutdown();
					}
					exitObserver.run();
				}
			};
			McpApplicationHandlerDispatcher.Ticket ticket = dispatcher.newTicket(callback::run,
					ignored -> {}, ignored -> {}, exited);
			McpApplicationHandlerDispatcher.Admission admission = dispatcher.admit(ticket);
			// Reservations cover active slots plus queue depth. The private executor
			// stays open until every reserved finish physically exits; transport end
			// during start and shutdown therefore cannot discard a paired finish.
			if (admission == McpApplicationHandlerDispatcher.Admission.REJECTED
					|| admission == McpApplicationHandlerDispatcher.Admission.CLOSED) {
				exited.run();
				throw new IllegalStateException("A reserved MCP HTTP observation could not be dispatched.");
			}
		}
	}

	void quiesce() { synchronized (lock) { accepting = false; } }
	boolean isAccepting() { synchronized (lock) { return accepting; } }
	void stop(boolean interrupt) {
		synchronized (lock) {
			accepting = false; stopping = true;
			if (reservations == 0) executor.shutdown();
		}
		if (interrupt) dispatcher.interruptActiveWork();
	}
	long outstanding() { synchronized (lock) { return reservations; } }
	boolean isTerminated() { return outstanding() == 0 && executor.isTerminated(); }
	void awaitTermination(@NonNull Duration timeout) throws InterruptedException {
		executor.awaitTermination(requireNonNull(timeout).toNanos(), TimeUnit.NANOSECONDS);
	}
}
