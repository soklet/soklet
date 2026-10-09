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
	private final long capacity;
	private final ExecutorService executor;
	private final McpApplicationHandlerDispatcher dispatcher;
	private final Runnable exitObserver;
	private long reservations;
	private boolean accepting = true;
	private boolean stopping;

	McpLegacyHttpObservationDispatcher(int concurrency, int queueCapacity, @NonNull Runnable exitObserver) {
		this.capacity = (long) concurrency + queueCapacity;
		this.exitObserver = requireNonNull(exitObserver);
		this.executor = McpApplicationHandlerExecutorFactory.production().create(concurrency);
		this.dispatcher = new McpApplicationHandlerDispatcher(concurrency, queueCapacity, executor);
	}

	@Nullable Reservation reserve() {
		synchronized (lock) {
			if (!accepting || reservations == capacity) return null;
			reservations++;
			return new Reservation();
		}
	}

	final class Reservation {
		private final AtomicBoolean submitted = new AtomicBoolean();
		void finish(@NonNull Runnable callback, @NonNull Runnable physicalExit) {
			requireNonNull(callback); requireNonNull(physicalExit);
			if (!submitted.compareAndSet(false, true)) return;
			Runnable exited = () -> {
				try { physicalExit.run(); }
				finally {
					synchronized (lock) {
						reservations--;
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
