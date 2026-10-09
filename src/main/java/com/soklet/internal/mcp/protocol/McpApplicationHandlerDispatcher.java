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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.soklet.internal.ObjectIdentity.sameInstance;
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
		private final Consumer<@NonNull Throwable> cancellationObserver;
		@NonNull
		private final Runnable physicalExitObserver;
		@NonNull
		private final AtomicBoolean physicalExitDelivered;
		@NonNull
		private final Object interruptLock;
		private volatile @Nullable Thread handlerThread;
		private boolean interruptRequested;
		@NonNull
		private volatile TicketState state;

		private Ticket(@NonNull Work work,
				@NonNull Consumer<@NonNull Throwable> failureObserver,
				@NonNull Consumer<@NonNull Throwable> cancellationObserver,
				@NonNull Runnable physicalExitObserver) {
			this.work = requireNonNull(work);
			this.failureObserver = requireNonNull(failureObserver);
			this.cancellationObserver = requireNonNull(cancellationObserver);
			this.physicalExitObserver = requireNonNull(physicalExitObserver);
			this.physicalExitDelivered = new AtomicBoolean();
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
				if (interruptRequested)
					return;
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

	@NonNull
	private final Object lock;
	private final int concurrency;
	private final int queueCapacity;
	@NonNull
	private final ExecutorService executorService;
	@NonNull
	private final McpApplicationExecutionObserver observer;
	@NonNull
	private final Runnable slotReleaseObserver;
	@NonNull
	private final Queue<@NonNull Ticket> queue;
	@NonNull
	private final Set<@NonNull Ticket> activeTickets;
	private int activeSlots;
	private int maximumObservedActiveSlots;
	private int maximumObservedQueueDepth;
	private boolean accepting;
	private boolean draining;

	McpApplicationHandlerDispatcher(int concurrency, int queueCapacity,
			@NonNull ExecutorService executorService) {
		this(concurrency, queueCapacity, executorService,
				McpApplicationExecutionObserver.disabledInstance());
	}

	McpApplicationHandlerDispatcher(int concurrency, int queueCapacity,
			@NonNull ExecutorService executorService,
			@NonNull McpApplicationExecutionObserver observer) {
		this(concurrency, queueCapacity, executorService, observer, () -> {});
	}

	McpApplicationHandlerDispatcher(int concurrency, int queueCapacity,
			@NonNull ExecutorService executorService,
			@NonNull McpApplicationExecutionObserver observer,
			@NonNull Runnable slotReleaseObserver) {
		if (concurrency < 1)
			throw new IllegalArgumentException("Handler concurrency must be positive.");

		if (queueCapacity < 1)
			throw new IllegalArgumentException("Handler queue capacity must be positive.");

		this.lock = new Object();
		this.concurrency = concurrency;
		this.queueCapacity = queueCapacity;
		this.executorService = requireNonNull(executorService);
		this.observer = requireNonNull(observer);
		this.slotReleaseObserver = requireNonNull(slotReleaseObserver);
		this.queue = new ArrayDeque<>(queueCapacity);
		this.activeTickets = Collections.newSetFromMap(new IdentityHashMap<>());
		this.accepting = true;
		this.draining = false;
	}

	@NonNull
	Ticket newTicket(@NonNull Work work,
			@NonNull Consumer<@NonNull Throwable> failureObserver) {
		return newTicket(work, failureObserver, ignored -> {}, () -> {});
	}

	@NonNull
	Ticket newTicket(@NonNull Work work,
			@NonNull Consumer<@NonNull Throwable> failureObserver,
			@NonNull Consumer<@NonNull Throwable> cancellationObserver) {
		return newTicket(work, failureObserver, cancellationObserver, () -> {});
	}

	@NonNull
	Ticket newTicket(@NonNull Work work,
			@NonNull Consumer<@NonNull Throwable> failureObserver,
			@NonNull Consumer<@NonNull Throwable> cancellationObserver,
			@NonNull Runnable physicalExitObserver) {
		return new Ticket(requireNonNull(work), requireNonNull(failureObserver),
				requireNonNull(cancellationObserver),
				requireNonNull(physicalExitObserver));
	}

	McpApplicationExecutionObserver.@NonNull MetricDeferral beginObserverDeferral() {
		return this.observer.beginRequestTransitionDeferral();
	}

	@NonNull
	Admission admit(@NonNull Ticket ticket) {
		requireOwnedTicket(ticket);
		Ticket ticketToDispatch = null;
		Admission admission;

		synchronized (lock) {
			if (ticket.state == TicketState.CANCELED)
				return Admission.CANCELED;

			if (ticket.state != TicketState.NEW)
				throw new IllegalStateException("Ticket has already been admitted.");

			if (!accepting) {
				ticket.state = TicketState.REJECTED;
				admission = Admission.CLOSED;
			} else if (activeSlots < concurrency && queue.isEmpty()) {
				ticket.state = TicketState.DISPATCHED;
				activeTickets.add(ticket);
				activeSlots++;
				recordHandlerExecutionStarted();
				maximumObservedActiveSlots = Math.max(maximumObservedActiveSlots,
						activeSlots);
				ticketToDispatch = ticket;
				admission = Admission.DISPATCHED;
			} else if (queue.size() < queueCapacity || activeSlots < concurrency) {
				// An earlier failed handoff can leave accepted queued work waiting
				// for a physical worker. A fresh admission may start that work, while
				// preserving its FIFO position and the configured queue bound.
				if (activeSlots < concurrency) ticketToDispatch = promoteNextLocked();
				ticket.state = TicketState.QUEUED;
				queue.add(ticket);
				recordHandlerQueued();
				maximumObservedQueueDepth = Math.max(maximumObservedQueueDepth,
						queue.size());
				admission = Admission.QUEUED;
			} else {
				ticket.state = TicketState.REJECTED;
				recordHandlerCapacityRejected();
				admission = Admission.REJECTED;
			}
		}

		drainObserver();
		if (ticketToDispatch != null)
			dispatch(ticketToDispatch);

		return admission;
	}

	boolean cancelBeforeDispatch(@NonNull Ticket ticket) {
		requireOwnedTicket(ticket);
		boolean dequeued = false;
		boolean canceled;

		synchronized (lock) {
			if (ticket.state == TicketState.NEW) {
				ticket.state = TicketState.CANCELED;
				canceled = true;
			} else if (ticket.state == TicketState.QUEUED) {
				if (!queue.remove(ticket))
					throw new IllegalStateException(
							"Queued ticket is absent from the queue.");

				ticket.state = TicketState.CANCELED;
				recordHandlerDequeued();
				dequeued = true;
				canceled = true;
			} else {
				canceled = false;
			}
		}

		if (dequeued)
			drainObserver();
		if (canceled)
			notifyPhysicalExit(ticket);
		return canceled;
	}

	@NonNull
	List<@NonNull Ticket> stopAccepting() {
		List<Ticket> canceledTickets;

		synchronized (lock) {
			if (!accepting)
				if (!draining)
					return List.of();

			accepting = false;
			draining = false;
			canceledTickets = new ArrayList<>(queue);
			queue.clear();

			for (Ticket ticket : canceledTickets) {
				ticket.state = TicketState.CANCELED;
				recordHandlerDequeued();
			}
		}

		if (!canceledTickets.isEmpty())
			drainObserver();
		for (Ticket ticket : canceledTickets)
			notifyPhysicalExit(ticket);
		return List.copyOf(canceledTickets);
	}

	/**
	 * Closes admission and reserves cancellation delivery for every accepted
	 * ticket. The returned action invokes observers and interrupts active work
	 * outside the dispatcher lock. Callers may therefore establish their own
	 * cancellation state first, which prevents dispatcher shutdown from racing
	 * an application exchange into an ordinary failure response.
	 */
	@NonNull
	Runnable stopAcceptingAndReserveCancellation(
			@NonNull Throwable cancellationCause) {
		requireNonNull(cancellationCause);
		List<Ticket> canceledTickets;
		List<Ticket> dispatchedTickets;

		synchronized (lock) {
			accepting = false;
			draining = false;
			canceledTickets = new ArrayList<>(queue);
			queue.clear();

			for (Ticket ticket : canceledTickets) {
				ticket.state = TicketState.CANCELED;
				recordHandlerDequeued();
			}
			dispatchedTickets = new ArrayList<>(activeTickets);
		}

		if (!canceledTickets.isEmpty())
			drainObserver();
		List<Ticket> immutableCanceled = List.copyOf(canceledTickets);
		List<Ticket> immutableDispatched = List.copyOf(dispatchedTickets);
		AtomicBoolean delivered = new AtomicBoolean();
		return () -> {
			if (!delivered.compareAndSet(false, true))
				return;
			for (Ticket ticket : immutableCanceled) {
				notifyCancellation(ticket, cancellationCause);
				notifyPhysicalExit(ticket);
			}
			for (Ticket ticket : immutableDispatched) {
				notifyCancellation(ticket, cancellationCause);
				ticket.requestInterrupt();
			}
		};
	}

	/**
	 * Closes admission while preserving every ticket that was already accepted.
	 * Queued work continues to promote as active slots return during grace.
	 */
	void beginGracefulDrain() {
		synchronized (lock) {
			if (!accepting)
				return;
			accepting = false;
			draining = true;
		}
	}

	/** Interrupts physical work while retaining all accepted queued tickets. */
	void interruptActiveWork() {
		List<Ticket> tickets;
		synchronized (lock) { tickets = List.copyOf(activeTickets); }
		for (Ticket ticket : tickets) ticket.requestInterrupt();
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
		Thread submittingThread = Thread.currentThread();
		AtomicBoolean submissionActive = new AtomicBoolean(true);
		try {
			executorService.execute(() -> {
				if (sameInstance(Thread.currentThread(), submittingThread) && submissionActive.get())
					throw new RejectedExecutionException("MCP application work cannot run on the submitting thread.");
				run(ticket);
			});
		} catch (RuntimeException | Error failure) {
			onSubmissionFailure(ticket, failure);
			notifyFailure(ticket, failure);
			if (failure instanceof Error error) throw error;
		} finally { submissionActive.set(false); }
	}

	private void run(@NonNull Ticket firstTicket) {
		Ticket ticketToRun = firstTicket;
		while (ticketToRun != null) {
			Ticket ticket = ticketToRun;
			Thread.interrupted();

			synchronized (ticket.interruptLock) {
				ticket.handlerThread = Thread.currentThread();

				if (ticket.interruptRequested)
					Thread.currentThread().interrupt();
			}

			Ticket next;
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
				next = onHandlerExited(ticket);
			}
			// This physical worker already satisfies the application's executor
			// contract. Drain accepted queued work before returning it to a
			// direct-handoff executor; do not resubmit from an exiting worker.
			ticketToRun = next;
		}
	}

	private @Nullable Ticket onHandlerExited(@NonNull Ticket ticket) {
		Ticket next;

		synchronized (lock) {
			if (ticket.state != TicketState.DISPATCHED)
				throw new IllegalStateException(
						"A handler exited without owning a dispatcher slot.");

			ticket.state = TicketState.EXITED;
			if (!activeTickets.remove(ticket))
				throw new IllegalStateException(
						"An exiting handler is absent from the active ticket set.");
			activeSlots--;
			recordHandlerExecutionFinished();
			next = promoteNextLocked();
		}

		notifySlotReleased();
		notifyPhysicalExit(ticket);
		drainObserver();
		return next;
	}

	private void onSubmissionFailure(@NonNull Ticket ticket, @NonNull Throwable failure) {
		synchronized (lock) {
			if (ticket.state != TicketState.DISPATCHED)
				throw new IllegalStateException(
						"A submission failed without owning a dispatcher slot.");

			ticket.state = TicketState.REJECTED;
			if (!activeTickets.remove(ticket))
				throw new IllegalStateException(
						"A rejected handler is absent from the active ticket set.");
			activeSlots--;
			recordHandlerExecutionFinished();
			if (failure instanceof RejectedExecutionException)
				recordHandlerCapacityRejected();
			// Reject only this new handoff. Accepted queued tickets remain bounded
			// and are drained by existing workers or a later successful admission.
		}
		notifySlotReleased();
		notifyPhysicalExit(ticket);
		drainObserver();
	}

	private @Nullable Ticket promoteNextLocked() {
		if ((!accepting && !draining) || queue.isEmpty())
			return null;

		Ticket next = queue.remove();
		next.state = TicketState.DISPATCHED;
		activeTickets.add(next);
		recordHandlerDequeued();
		activeSlots++;
		recordHandlerExecutionStarted();
		maximumObservedActiveSlots = Math.max(maximumObservedActiveSlots, activeSlots);
		return next;
	}

	private void recordHandlerExecutionStarted() {
		try {
			this.observer.recordHandlerExecutionStarted();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting.
		}
	}

	private void recordHandlerExecutionFinished() {
		try {
			this.observer.recordHandlerExecutionFinished();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting.
		}
	}

	private void recordHandlerQueued() {
		try {
			this.observer.recordHandlerQueued();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting.
		}
	}

	private void recordHandlerDequeued() {
		try {
			this.observer.recordHandlerDequeued();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting.
		}
	}

	private void recordHandlerCapacityRejected() {
		try {
			this.observer.recordHandlerCapacityRejected();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting.
		}
	}

	private void drainObserver() {
		try {
			this.observer.drain();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting or dispatch.
		}
	}

	private void notifySlotReleased() {
		try {
			this.slotReleaseObserver.run();
		} catch (Throwable ignored) {
			// Slot-release signaling must not corrupt accounting or promotion.
		}
	}

	private void notifyFailure(@NonNull Ticket ticket, @NonNull Throwable throwable) {
		try {
			ticket.failureObserver.accept(throwable);
		} catch (Throwable ignored) {
			// Failure reporting must not corrupt dispatcher accounting or promotion.
		}
	}

	private void notifyCancellation(@NonNull Ticket ticket,
			@NonNull Throwable throwable) {
		try {
			ticket.cancellationObserver.accept(throwable);
		} catch (Throwable ignored) {
			// Cancellation reporting must not corrupt dispatcher shutdown.
		}
	}

	private void notifyPhysicalExit(@NonNull Ticket ticket) {
		if (!ticket.physicalExitDelivered.compareAndSet(false, true))
			return;
		try {
			ticket.physicalExitObserver.run();
		} catch (Throwable ignored) {
			// Exit reporting must not corrupt accounting or promotion.
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
