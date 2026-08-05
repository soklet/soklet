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

package com.soklet.internal.mcp.transport;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Thread-safe bounded admission and dispatch for MCP application handlers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpHandlerDispatcher {
	enum Admission {
		DISPATCHED,
		QUEUED,
		REJECTED
	}

	enum TicketState {
		NEW,
		QUEUED,
		DISPATCHED,
		CANCELED,
		EXITED,
		REJECTED
	}

	@FunctionalInterface
	interface Work {
		void run() throws Exception;
	}

	record Snapshot(int concurrency, int queueCapacity, int activeSlots, int queueDepth,
			int maximumObservedActiveSlots, int maximumObservedQueueDepth, boolean accepting) {
	}

	@ThreadSafe
	final class Ticket {
		@NonNull
		private final Work work;
		@NonNull
		private final Consumer<Throwable> submissionFailureConsumer;
		@NonNull
		private final AtomicReference<@Nullable Thread> handlerThread;
		@NonNull
		private final AtomicBoolean interruptRequested;
		@NonNull
		private volatile TicketState state;

		private Ticket(@NonNull Work work,
				@NonNull Consumer<@NonNull Throwable> submissionFailureConsumer) {
			this.work = requireNonNull(work);
			this.submissionFailureConsumer = requireNonNull(submissionFailureConsumer);
			this.handlerThread = new AtomicReference<>();
			this.interruptRequested = new AtomicBoolean();
			this.state = TicketState.NEW;
		}

		@NonNull
		TicketState state() {
			return state;
		}

		@Nullable
		Thread handlerThread() {
			return handlerThread.get();
		}

		void requestInterrupt() {
			interruptRequested.set(true);
			Thread thread = handlerThread.get();

			if (thread != null)
				thread.interrupt();
		}
	}

	@NonNull
	private final Object lock;
	private final int concurrency;
	private final int queueCapacity;
	@NonNull
	private final ExecutorService executorService;
	@NonNull
	private final Queue<@NonNull Ticket> queue;
	private int activeSlots;
	private int maximumObservedActiveSlots;
	private int maximumObservedQueueDepth;
	private boolean accepting;

	McpHandlerDispatcher(int concurrency, int queueCapacity,
			@NonNull ExecutorService executorService) {
		if (concurrency < 1)
			throw new IllegalArgumentException("Handler concurrency must be > 0.");

		if (queueCapacity < 1)
			throw new IllegalArgumentException("Handler queue capacity must be > 0.");

		this.lock = new Object();
		this.concurrency = concurrency;
		this.queueCapacity = queueCapacity;
		this.executorService = requireNonNull(executorService);
		this.queue = new ArrayDeque<>(queueCapacity);
		this.accepting = true;
	}

	@NonNull
	Ticket newTicket(@NonNull Work work,
			@NonNull Consumer<@NonNull Throwable> submissionFailureConsumer) {
		return new Ticket(work, submissionFailureConsumer);
	}

	@NonNull
	Admission admit(@NonNull Ticket ticket) {
		requireNonNull(ticket);
		boolean dispatch = false;
		Admission admission;

		synchronized (lock) {
			if (ticket.state != TicketState.NEW)
				throw new IllegalStateException("Ticket has already been admitted.");

			if (!accepting) {
				ticket.state = TicketState.REJECTED;
				admission = Admission.REJECTED;
			} else if (activeSlots < concurrency) {
				ticket.state = TicketState.DISPATCHED;
				activeSlots++;
				maximumObservedActiveSlots = Math.max(maximumObservedActiveSlots, activeSlots);
				dispatch = true;
				admission = Admission.DISPATCHED;
			} else if (queue.size() < queueCapacity) {
				ticket.state = TicketState.QUEUED;
				queue.add(ticket);
				maximumObservedQueueDepth = Math.max(maximumObservedQueueDepth, queue.size());
				admission = Admission.QUEUED;
			} else {
				ticket.state = TicketState.REJECTED;
				admission = Admission.REJECTED;
			}
		}

		if (dispatch)
			dispatch(ticket);

		return admission;
	}

	boolean cancelQueued(@NonNull Ticket ticket) {
		requireNonNull(ticket);

		synchronized (lock) {
			if (ticket.state != TicketState.QUEUED || !queue.remove(ticket))
				return false;

			ticket.state = TicketState.CANCELED;
			return true;
		}
	}

	@NonNull
	List<@NonNull Ticket> stopAccepting() {
		List<@NonNull Ticket> canceledTickets;

		synchronized (lock) {
			if (!accepting)
				return List.of();

			accepting = false;
			canceledTickets = new ArrayList<>(queue);
			queue.clear();

			for (Ticket ticket : canceledTickets)
				ticket.state = TicketState.CANCELED;
		}

		return List.copyOf(canceledTickets);
	}

	@NonNull
	Snapshot snapshot() {
		synchronized (lock) {
			return new Snapshot(
					concurrency,
					queueCapacity,
					activeSlots,
					queue.size(),
					maximumObservedActiveSlots,
					maximumObservedQueueDepth,
					accepting);
		}
	}

	private void dispatch(@NonNull Ticket ticket) {
		try {
			executorService.execute(() -> run(ticket));
		} catch (RuntimeException exception) {
			onSubmissionFailure(ticket, exception);
		}
	}

	private void run(@NonNull Ticket ticket) {
		Thread.interrupted();
		ticket.handlerThread.set(Thread.currentThread());

		if (ticket.interruptRequested.get())
			Thread.currentThread().interrupt();

		try {
			ticket.work.run();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			notifyFailure(ticket, exception);
		} catch (Throwable throwable) {
			notifyFailure(ticket, throwable);
		} finally {
			ticket.handlerThread.set(null);
			Thread.interrupted();
			onHandlerExited(ticket);
		}
	}

	private void onHandlerExited(@NonNull Ticket ticket) {
		@Nullable Ticket next = null;

		synchronized (lock) {
			if (ticket.state != TicketState.DISPATCHED)
				throw new IllegalStateException("A handler exited without owning a dispatcher slot.");

			ticket.state = TicketState.EXITED;
			activeSlots--;

			if (accepting && !queue.isEmpty()) {
				next = queue.remove();
				next.state = TicketState.DISPATCHED;
				activeSlots++;
				maximumObservedActiveSlots = Math.max(maximumObservedActiveSlots, activeSlots);
			}
		}

		if (next != null)
			dispatch(next);
	}

	private void onSubmissionFailure(@NonNull Ticket ticket,
			@NonNull RuntimeException exception) {
		@Nullable Ticket next = null;

		synchronized (lock) {
			if (ticket.state == TicketState.DISPATCHED) {
				ticket.state = TicketState.REJECTED;
				activeSlots--;
			}

			if (accepting && !queue.isEmpty()) {
				next = queue.remove();
				next.state = TicketState.DISPATCHED;
				activeSlots++;
				maximumObservedActiveSlots = Math.max(maximumObservedActiveSlots, activeSlots);
			}
		}

		notifyFailure(ticket, exception);

		if (next != null)
			dispatch(next);
	}

	private void notifyFailure(@NonNull Ticket ticket,
			@NonNull Throwable throwable) {
		try {
			ticket.submissionFailureConsumer.accept(throwable);
		} catch (Throwable ignored) {
			// The dispatcher must remain usable when a failure observer fails.
		}
	}

}
