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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
	private static final AtomicLong INTERRUPT_THREAD_SEQUENCE = new AtomicLong();
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
		private final McpApplicationExecutionObserver.@Nullable MetricSequence metricSequence;
		@NonNull
		private final AtomicBoolean physicalExitDelivered;
		@NonNull
		private final Object interruptLock;
		private volatile @Nullable Thread handlerThread;
		private boolean interruptRequested;
		private boolean interruptDeliveryPending;
		private @Nullable Thread interruptDeliveryThread;
		private @Nullable Throwable interruptDeliveryFailure;
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
			this.metricSequence = captureMetricSequence();
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
			Thread target;
			synchronized (interruptLock) {
				if (interruptRequested)
					return;
				interruptRequested = true;
				target = handlerThread;
				if (target == null)
					return;
				interruptDeliveryPending = true;
			}
			startInterruptDelivery(this, target);
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
	private final ThreadFactory interruptThreadFactory;
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
		this(concurrency, queueCapacity, executorService, observer, slotReleaseObserver,
				runnable -> Thread.ofPlatform().inheritInheritableThreadLocals(false)
						.name("soklet-mcp-interrupt-" + INTERRUPT_THREAD_SEQUENCE.incrementAndGet())
						.unstarted(runnable));
	}

	// Internal launch seam permits deterministic failure coverage without a
	// configurable application executor or an additional public setting.
	McpApplicationHandlerDispatcher(int concurrency, int queueCapacity,
			@NonNull ExecutorService executorService,
			@NonNull McpApplicationExecutionObserver observer,
			@NonNull Runnable slotReleaseObserver,
			@NonNull ThreadFactory interruptThreadFactory) {
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
		this.interruptThreadFactory = requireNonNull(interruptThreadFactory);
		this.queue = new ArrayDeque<>();
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
				recordHandlerExecutionStarted(ticket);
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
				recordHandlerQueued(ticket);
				maximumObservedQueueDepth = Math.max(maximumObservedQueueDepth,
						queue.size());
				admission = Admission.QUEUED;
			} else {
				ticket.state = TicketState.REJECTED;
				recordHandlerCapacityRejected(ticket);
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
				recordHandlerDequeued(ticket);
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
				recordHandlerDequeued(ticket);
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
				recordHandlerDequeued(ticket);
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

			boolean interruptBeforeEntry;
			synchronized (ticket.interruptLock) {
				ticket.handlerThread = Thread.currentThread();
				interruptBeforeEntry = ticket.interruptRequested;
			}

			Ticket next;
			try (var scope = ticketMetricScope(ticket)) {
				// A request accepted before publication belongs to this worker.
				// Deliver outside the ticket monitor and inside the owner scope;
				// custom Thread implementations may execute application code here.
				if (interruptBeforeEntry)
					performInterrupt(ticket, Thread.currentThread());
				ticket.work.run();
			} catch (InterruptedException exception) {
				performInterrupt(ticket, Thread.currentThread());
				notifyFailure(ticket, exception);
			} catch (Throwable throwable) {
				notifyFailure(ticket, throwable);
			} finally {
				Throwable interruptFailure;
				Thread interruptDelivery;
				synchronized (ticket.interruptLock) {
					// Interrupt can synchronously close application channels. Never
					// return/promote this physical worker until that exact delivery
					// has exited, even when application work has already returned.
					while (ticket.interruptDeliveryPending) {
						try {
							ticket.interruptLock.wait();
						} catch (InterruptedException ignored) {
							// Worker interrupt status is cleared below after retirement.
						}
					}
					ticket.handlerThread = null;
					interruptFailure = ticket.interruptDeliveryFailure;
					interruptDelivery = ticket.interruptDeliveryThread;
				}

				// A completed delivery body has no more application calls, but
				// retain its slot through the helper thread's actual physical exit.
				if (interruptDelivery != null) {
					while (interruptDelivery.isAlive()) {
						try {
							interruptDelivery.join();
						} catch (InterruptedException ignored) {
							// This worker is still fenced against executor reuse.
						}
					}
				}
				Thread.interrupted();
				if (interruptFailure != null)
					notifyFailure(ticket, interruptFailure);
				next = onHandlerExited(ticket);
			}
			// This physical worker already satisfies the application's executor
			// contract. Drain accepted queued work before returning it to a
			// direct-handoff executor; do not resubmit from an exiting worker.
			ticketToRun = next;
		}
	}

	private void startInterruptDelivery(@NonNull Ticket ticket, @NonNull Thread target) {
		try {
			Thread delivery = requireNonNull(this.interruptThreadFactory.newThread(() -> {
				try {
					performInterrupt(ticket, target);
				} finally {
					finishInterruptDelivery(ticket);
				}
			}), "MCP interrupt thread factory returned null.");
			delivery.setDaemon(true);
			synchronized (ticket.interruptLock) {
				ticket.interruptDeliveryThread = delivery;
			}
			delivery.start();
		} catch (Throwable failure) {
			// A launch failure must neither strand the retirement fence nor
			// prevent the timer from publishing its elected timeout response.
			recordInterruptFailure(ticket, failure);
			finishInterruptDelivery(ticket);
		}
	}

	private void performInterrupt(@NonNull Ticket ticket, @NonNull Thread target) {
		try (var scope = this.observer.enterLifecycleExecution()) {
			target.interrupt();
		} catch (Throwable failure) {
			recordInterruptFailure(ticket, failure);
		}
	}

	private void recordInterruptFailure(@NonNull Ticket ticket, @NonNull Throwable failure) {
		synchronized (ticket.interruptLock) {
			if (ticket.interruptDeliveryFailure == null)
				ticket.interruptDeliveryFailure = failure;
		}
	}

	private void finishInterruptDelivery(@NonNull Ticket ticket) {
		synchronized (ticket.interruptLock) {
			ticket.interruptDeliveryPending = false;
			ticket.interruptLock.notifyAll();
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
			recordHandlerExecutionFinished(ticket);
			next = promoteNextLocked();
		}

		notifySlotReleased();
		notifyPhysicalExit(ticket);
		drainObserver();
		return next;
	}

	private void onSubmissionFailure(@NonNull Ticket ticket, @NonNull Throwable failure) {
		List<Ticket> stranded = new ArrayList<>();
		synchronized (lock) {
			if (ticket.state != TicketState.DISPATCHED)
				throw new IllegalStateException(
						"A submission failed without owning a dispatcher slot.");

			ticket.state = TicketState.REJECTED;
			if (!activeTickets.remove(ticket))
				throw new IllegalStateException(
						"A rejected handler is absent from the active ticket set.");
			activeSlots--;
			recordHandlerExecutionFinished(ticket);
			if (failure instanceof RejectedExecutionException)
				recordHandlerCapacityRejected(ticket);
			// Existing physical workers can drain accepted work. If no worker
			// remains, fail these tickets truthfully rather than leave them waiting
			// until their deadline or recursively retry a rejecting executor.
			if (activeSlots == 0) {
				while (!queue.isEmpty()) {
					Ticket queued = queue.remove();
					queued.state = TicketState.REJECTED;
					stranded.add(queued);
					recordHandlerDequeued(queued);
					recordHandlerCapacityRejected(queued);
				}
			}
		}
		for (Ticket queued : stranded) {
			notifyFailure(queued, failure);
			notifyPhysicalExit(queued);
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
		recordHandlerDequeued(next);
		activeSlots++;
		recordHandlerExecutionStarted(next);
		maximumObservedActiveSlots = Math.max(maximumObservedActiveSlots, activeSlots);
		return next;
	}

	private McpApplicationExecutionObserver.@Nullable MetricSequence captureMetricSequence() {
		try {
			return this.observer.currentMetricSequence();
		} catch (Throwable ignored) {
			// Broken observation cannot prevent the application ticket from existing.
			return null;
		}
	}

	private McpApplicationExecutionObserver.@NonNull MetricScope ticketMetricScope(@NonNull Ticket ticket) {
		McpApplicationExecutionObserver.MetricScope scope;
		try {
			scope = this.observer.useMetricSequence(ticket.metricSequence);
		} catch (Throwable ignored) {
			// Observation cannot prevent work, cancellation, or physical cleanup.
			return () -> {};
		}
		return () -> {
			try {
				scope.close();
			} catch (Throwable ignored) {
				// Restoring observation state must not turn successful work into failure.
			}
		};
	}

	private void recordHandlerExecutionStarted(@NonNull Ticket ticket) {
		try (var scope = ticketMetricScope(ticket)) {
			this.observer.recordHandlerExecutionStarted();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting.
		}
	}

	private void recordHandlerExecutionFinished(@NonNull Ticket ticket) {
		try (var scope = ticketMetricScope(ticket)) {
			this.observer.recordHandlerExecutionFinished();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting.
		}
	}

	private void recordHandlerQueued(@NonNull Ticket ticket) {
		try (var scope = ticketMetricScope(ticket)) {
			this.observer.recordHandlerQueued();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting.
		}
	}

	private void recordHandlerDequeued(@NonNull Ticket ticket) {
		try (var scope = ticketMetricScope(ticket)) {
			this.observer.recordHandlerDequeued();
		} catch (Throwable ignored) {
			// Observation must not corrupt dispatcher accounting.
		}
	}

	private void recordHandlerCapacityRejected(@NonNull Ticket ticket) {
		try (var scope = ticketMetricScope(ticket)) {
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
		try (var scope = ticketMetricScope(ticket)) {
			ticket.failureObserver.accept(throwable);
		} catch (Throwable ignored) {
			// Failure reporting must not corrupt dispatcher accounting or promotion.
		}
	}

	private void notifyCancellation(@NonNull Ticket ticket,
			@NonNull Throwable throwable) {
		try (var scope = ticketMetricScope(ticket)) {
			ticket.cancellationObserver.accept(throwable);
		} catch (Throwable ignored) {
			// Cancellation reporting must not corrupt dispatcher shutdown.
		}
	}

	private void notifyPhysicalExit(@NonNull Ticket ticket) {
		if (!ticket.physicalExitDelivered.compareAndSet(false, true))
			return;
		try (var scope = ticketMetricScope(ticket)) {
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
