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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Owns the application-handler concurrency slots independently of the HTTP
 * request-processing pool. A dispatched ticket retains its slot until its
 * work actually exits, including when interruption has been requested.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationHandlerDispatcher {
	enum Admission {
		DISPATCHED,
		QUEUED,
		REJECTED,
		CLOSED,
		CANCELED
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

	record Snapshot(int concurrency, int queueCapacity, int activeSlots,
			int queueDepth, int maximumObservedActiveSlots,
			int maximumObservedQueueDepth, boolean accepting) {
	}

	@ThreadSafe
	final class Ticket {
		@NonNull
		private final Work work;
		@NonNull
		private final Consumer<@NonNull Throwable> failureObserver;
		@NonNull
		private final Object interruptLock;
		private volatile @Nullable Thread handlerThread;
		private boolean interruptRequested;
		@NonNull
		private volatile TicketState state;

		private Ticket(@NonNull Work work,
				@NonNull Consumer<@NonNull Throwable> failureObserver) {
			this.work = requireNonNull(work);
			this.failureObserver = requireNonNull(failureObserver);
			this.interruptLock = new Object();
			this.state = TicketState.NEW;
		}

		@NonNull
		TicketState state() {
			return state;
		}

		@Nullable
		Thread handlerThread() {
			return handlerThread;
		}

		void requestInterrupt() {
			synchronized (interruptLock) {
				interruptRequested = true;

				if (handlerThread != null)
					handlerThread.interrupt();
			}
		}

		@NonNull
		private McpApplicationHandlerDispatcher owner() {
			return McpApplicationHandlerDispatcher.this;
		}
	}

	private record SubmissionFailure(@NonNull Ticket ticket,
			@NonNull RuntimeException exception) {
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

	McpApplicationHandlerDispatcher(int concurrency, int queueCapacity,
			@NonNull ExecutorService executorService) {
		if (concurrency < 1)
			throw new IllegalArgumentException("Handler concurrency must be positive.");

		if (queueCapacity < 1)
			throw new IllegalArgumentException("Handler queue capacity must be positive.");

		this.lock = new Object();
		this.concurrency = concurrency;
		this.queueCapacity = queueCapacity;
		this.executorService = requireNonNull(executorService);
		this.queue = new ArrayDeque<>(queueCapacity);
		this.accepting = true;
	}

	@NonNull
	Ticket newTicket(@NonNull Work work,
			@NonNull Consumer<@NonNull Throwable> failureObserver) {
		return new Ticket(requireNonNull(work), requireNonNull(failureObserver));
	}

	@NonNull
	Admission admit(@NonNull Ticket ticket) {
		requireOwnedTicket(ticket);
		boolean dispatch = false;
		Admission admission;

		synchronized (lock) {
			if (ticket.state == TicketState.CANCELED)
				return Admission.CANCELED;

			if (ticket.state != TicketState.NEW)
				throw new IllegalStateException("Ticket has already been admitted.");

			if (!accepting) {
				ticket.state = TicketState.REJECTED;
				admission = Admission.CLOSED;
			} else if (activeSlots < concurrency) {
				ticket.state = TicketState.DISPATCHED;
				activeSlots++;
				maximumObservedActiveSlots = Math.max(maximumObservedActiveSlots,
						activeSlots);
				dispatch = true;
				admission = Admission.DISPATCHED;
			} else if (queue.size() < queueCapacity) {
				ticket.state = TicketState.QUEUED;
				queue.add(ticket);
				maximumObservedQueueDepth = Math.max(maximumObservedQueueDepth,
						queue.size());
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

	boolean cancelBeforeDispatch(@NonNull Ticket ticket) {
		requireOwnedTicket(ticket);

		synchronized (lock) {
			if (ticket.state == TicketState.NEW) {
				ticket.state = TicketState.CANCELED;
				return true;
			}

			if (ticket.state != TicketState.QUEUED)
				return false;

			if (!queue.remove(ticket))
				throw new IllegalStateException("Queued ticket is absent from the queue.");

			ticket.state = TicketState.CANCELED;
			return true;
		}
	}

	@NonNull
	List<@NonNull Ticket> stopAccepting() {
		List<Ticket> canceledTickets;

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
		List<SubmissionFailure> submissionFailures = new ArrayList<>();
		Ticket ticketToSubmit = ticket;

		while (ticketToSubmit != null) {
			Ticket submittedTicket = ticketToSubmit;

			try {
				executorService.execute(() -> run(submittedTicket));
				ticketToSubmit = null;
			} catch (RuntimeException exception) {
				submissionFailures.add(new SubmissionFailure(submittedTicket, exception));
				ticketToSubmit = onSubmissionFailure(submittedTicket);
			}
		}

		for (SubmissionFailure submissionFailure : submissionFailures)
			notifyFailure(submissionFailure.ticket(), submissionFailure.exception());
	}

	private void run(@NonNull Ticket ticket) {
		Thread.interrupted();

		synchronized (ticket.interruptLock) {
			ticket.handlerThread = Thread.currentThread();

			if (ticket.interruptRequested)
				Thread.currentThread().interrupt();
		}

		try {
			ticket.work.run();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			notifyFailure(ticket, exception);
		} catch (Throwable throwable) {
			notifyFailure(ticket, throwable);
		} finally {
			synchronized (ticket.interruptLock) {
				ticket.handlerThread = null;
			}

			Thread.interrupted();
			onHandlerExited(ticket);
		}
	}

	private void onHandlerExited(@NonNull Ticket ticket) {
		Ticket next;

		synchronized (lock) {
			if (ticket.state != TicketState.DISPATCHED)
				throw new IllegalStateException(
						"A handler exited without owning a dispatcher slot.");

			ticket.state = TicketState.EXITED;
			activeSlots--;
			next = promoteNextLocked();
		}

		if (next != null)
			dispatch(next);
	}

	private @Nullable Ticket onSubmissionFailure(@NonNull Ticket ticket) {
		synchronized (lock) {
			if (ticket.state != TicketState.DISPATCHED)
				throw new IllegalStateException(
						"A submission failed without owning a dispatcher slot.");

			ticket.state = TicketState.REJECTED;
			activeSlots--;
			return promoteNextLocked();
		}
	}

	private @Nullable Ticket promoteNextLocked() {
		if (!accepting || queue.isEmpty())
			return null;

		Ticket next = queue.remove();
		next.state = TicketState.DISPATCHED;
		activeSlots++;
		maximumObservedActiveSlots = Math.max(maximumObservedActiveSlots, activeSlots);
		return next;
	}

	private void notifyFailure(@NonNull Ticket ticket, @NonNull Throwable throwable) {
		try {
			ticket.failureObserver.accept(throwable);
		} catch (Throwable ignored) {
			// Failure reporting must not corrupt dispatcher accounting or promotion.
		}
	}

	@NonNull
	private Ticket requireOwnedTicket(@NonNull Ticket ticket) {
		requireNonNull(ticket);

		if (ticket.owner() != this)
			throw new IllegalArgumentException("Ticket belongs to another dispatcher.");

		return ticket;
	}
}
