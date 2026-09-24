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

package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static com.soklet.internal.ObjectIdentity.sameInstance;
import static java.util.Objects.requireNonNull;

/**
 * Server-owned admission, cancelation dispatch, and physical-work accounting for
 * HTTP streaming and SSE connections. This is an internal lifecycle owner, not an application API.
 * <p>
 * A reservation is acquired before response commitment. Its publication gate
 * stays open until {@link Reservation#complete()}, and it remains counted until
 * accepted producer work and every published terminal task have physically exited.
 * Expiring a cleanup deadline never retires physical work. Cancelation hooks are
 * framework-only: hooks must publish transport state without invoking application
 * code or waiting for user work. Arbitrary callbacks and diagnostics use separate,
 * bounded executors; they never run on the signal or supervision thread.
 */
@ThreadSafe
public final class StreamLifecycleCoordinator {

	private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
	private final Object lock = new Object();
	private final int capacity;
	private final long cleanupGraceNanos;
	private final Consumer<Throwable> diagnostics;
	private final LongSupplier nanoClock;
	private final Set<Reservation> reservations = new LinkedHashSet<>();
	private final ThreadPoolExecutor callbackExecutor;
	private final ThreadPoolExecutor diagnosticExecutor;
	private final ScheduledThreadPoolExecutor supervisor;
	private boolean accepting = true;
	private boolean infrastructureStopping;
	private long nextId;

	public StreamLifecycleCoordinator(int capacity,
																		int callbackConcurrency,
																		@NonNull Duration cleanupGrace,
																			@NonNull Consumer<Throwable> diagnostics) {
		this(capacity, callbackConcurrency, cleanupGrace, diagnostics, System::nanoTime);
	}

	StreamLifecycleCoordinator(int capacity, int callbackConcurrency, Duration cleanupGrace,
														 Consumer<Throwable> diagnostics, LongSupplier nanoClock) {
		if (capacity <= 0 || capacity > Integer.MAX_VALUE / 2)
			throw new IllegalArgumentException("Stream lifecycle capacity must be positive and fit twice in an int");
		if (callbackConcurrency <= 0 || callbackConcurrency > capacity)
			throw new IllegalArgumentException("Callback concurrency must be positive and no greater than capacity");
		requireNonNull(cleanupGrace);
		if (cleanupGrace.isNegative() || cleanupGrace.isZero())
			throw new IllegalArgumentException("Cleanup grace must be positive");
		try {
			this.cleanupGraceNanos = cleanupGrace.toNanos();
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("Cleanup grace must be representable in nanoseconds", overflow);
		}
		this.capacity = capacity;
		this.diagnostics = requireNonNull(diagnostics);
		this.nanoClock = requireNonNull(nanoClock);
		this.callbackExecutor = new ThreadPoolExecutor(callbackConcurrency, callbackConcurrency,
				0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity * 2),
				threadFactory("stream-callback"), new ThreadPoolExecutor.AbortPolicy());
		this.diagnosticExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(capacity), threadFactory("stream-diagnostic"),
				new ThreadPoolExecutor.AbortPolicy());
		this.supervisor = new ScheduledThreadPoolExecutor(1, threadFactory("stream-supervisor"));
		this.supervisor.setRemoveOnCancelPolicy(true);
		this.supervisor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
	}

	/** Returns null when stopped or full; no producer work has been accepted yet. */
	@Nullable
	public Reservation tryReserve() {
		synchronized (this.lock) {
			if (!this.accepting || this.reservations.size() >= this.capacity)
				return null;
			Reservation reservation = new Reservation(++this.nextId);
			this.reservations.add(reservation);
			return reservation;
		}
	}

	/** Stops admission while allowing already admitted production and callbacks to drain. */
	public void stopAdmission() {
		synchronized (this.lock) {
			this.accepting = false;
			stopInfrastructureIfDrained();
			this.lock.notifyAll();
		}
	}

	/** Stops admission and signals all admitted lifetimes without waiting for cleanup. */
	public void force() {
		List<Reservation> retained;
		synchronized (this.lock) {
			this.accepting = false;
			retained = new ArrayList<>(this.reservations);
			stopInfrastructureIfDrained();
		}
		for (Reservation reservation : retained)
			reservation.cancel(StreamTerminationReason.SERVER_STOPPING, null);
	}

	/**
	 * Records removal proof from an executor's shutdownNow return value. Only this
	 * coordinator's exact execution envelopes are recognized. A canceled Future is
	 * deliberately not treated as proof that a queued wrapper has left an executor.
	 */
	public void retireQueuedTasks(@NonNull List<? extends @NonNull Runnable> removedTasks) {
		requireNonNull(removedTasks);
		for (Runnable removed : removedTasks) {
			if (removed instanceof StreamLifecycleCoordinator.ProducerTask task
					&& task.coordinator() == this) {
				synchronized (this.lock) {
					Reservation reservation = task.reservation;
					if (reservation.producerTask == task
							&& (reservation.producerState == ProducerState.QUEUED
							|| reservation.producerState == ProducerState.SUBMITTING)) {
						reservation.producerState = ProducerState.RETIRED;
						reservation.retireIfFinished();
					}
				}
			}
		}
	}

	/**
	 * Waits only until the caller's absolute System.nanoTime deadline. False means
	 * retained physical work or owned executor termination is still outstanding.
	 * It neither cancels work nor releases reservations on timeout.
	 */
	public boolean awaitTermination(long absoluteNanoDeadline) throws InterruptedException {
		synchronized (this.lock) {
			while (this.accepting || !this.reservations.isEmpty()) {
				long remaining = absoluteNanoDeadline - System.nanoTime();
				if (remaining <= 0L)
					return false;
				TimeUnit.NANOSECONDS.timedWait(this.lock, remaining);
			}
		}
		return awaitExecutor(this.callbackExecutor, absoluteNanoDeadline)
				&& awaitExecutor(this.diagnosticExecutor, absoluteNanoDeadline)
				&& awaitExecutor(this.supervisor, absoluteNanoDeadline);
	}

	public boolean isTerminated() {
		synchronized (this.lock) {
			return !this.accepting && this.reservations.isEmpty()
					&& this.callbackExecutor.isTerminated()
					&& this.diagnosticExecutor.isTerminated()
					&& this.supervisor.isTerminated();
		}
	}

	/** Bounded evidence: counts only, without retaining request/application payloads. */
	@NonNull
	public Snapshot snapshot() {
		synchronized (this.lock) {
			int queued = 0;
			int running = 0;
			int callbacks = 0;
			int queuedCallbacks = 0;
			int overdue = 0;
			int diagnostics = 0;
			int publisherLifetimes = 0;
			int pendingPublisherAcquisitions = 0;
			long retainedWork = 0L;
			for (Reservation reservation : this.reservations) {
				if (reservation.producerState == ProducerState.QUEUED
						|| reservation.producerState == ProducerState.SUBMITTING)
					queued++;
				if (reservation.producerState == ProducerState.RUNNING)
					running++;
				for (JobState state : reservation.jobs) {
					if (state == JobState.QUEUED || state == JobState.RUNNING)
						callbacks++;
					if (state == JobState.QUEUED)
						queuedCallbacks++;
				}
				if (reservation.overdue)
					overdue++;
				if (reservation.diagnosticPending)
					diagnostics++;
				if (reservation.hasPublisherWork())
					publisherLifetimes++;
				if (reservation.publisherState == PublisherState.PENDING)
					pendingPublisherAcquisitions++;
				retainedWork += reservation.retainedWork;
			}
			return new Snapshot(this.accepting, this.reservations.size(), queued, running,
					callbacks, queuedCallbacks, overdue, diagnostics, publisherLifetimes, pendingPublisherAcquisitions, retainedWork);
		}
	}

	public record Snapshot(boolean accepting, int reservations, int queuedProducers,
											 int runningProducers, int callbacks, int queuedCallbacks,
											 int overdue, int diagnostics, int publisherLifetimes,
											 int pendingPublisherAcquisitions, long retainedWork) {
	}

	/** The bounded diagnostic distinguishes overdue work from work that finished exceptionally. */
	public static final class CleanupDeadlineExceededException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final long reservationId;
		private final String phase;
		private final String producerState;
		private final String cancelationState;
		private final String terminationState;
		private final String publisherState;
		private final int publisherLifetimes;
		private final int pendingPublisherAcquisitions;
		private final long retainedWork;
		private final int dispatcherRunning;
		private final int dispatcherQueued;

		private CleanupDeadlineExceededException(long reservationId, String phase, String producerState,
				String cancelationState, String terminationState, String publisherState,
				int publisherLifetimes, int pendingPublisherAcquisitions, long retainedWork, int dispatcherRunning, int dispatcherQueued) {
			super("Streaming cleanup deadline exceeded for reservation " + reservationId
					+ ": phase=" + phase + ", producer=" + producerState + ", cancelation=" + cancelationState
					+ ", termination=" + terminationState + ", publisher=" + publisherState
					+ ", publisherLifetimes=" + publisherLifetimes + ", pendingPublisherAcquisitions=" + pendingPublisherAcquisitions
					+ ", retainedWork=" + retainedWork
					+ ", dispatcherRunning=" + dispatcherRunning
					+ ", dispatcherQueued=" + dispatcherQueued);
			this.reservationId = reservationId;
			this.phase = phase;
			this.producerState = producerState;
			this.cancelationState = cancelationState;
			this.terminationState = terminationState;
			this.publisherState = publisherState;
			this.publisherLifetimes = publisherLifetimes;
			this.pendingPublisherAcquisitions = pendingPublisherAcquisitions;
			this.retainedWork = retainedWork;
			this.dispatcherRunning = dispatcherRunning;
			this.dispatcherQueued = dispatcherQueued;
		}

		public long getReservationId() {
			return this.reservationId;
		}

		@NonNull public String getPhase() { return this.phase; }
		@NonNull public String getProducerState() { return this.producerState; }
		@NonNull public String getCancelationState() { return this.cancelationState; }
		@NonNull public String getTerminationState() { return this.terminationState; }
		@NonNull public String getPublisherState() { return this.publisherState; }
		public int getPublisherLifetimes() { return this.publisherLifetimes; }
		public int getPendingPublisherAcquisitions() { return this.pendingPublisherAcquisitions; }
		public long getRetainedWork() { return this.retainedWork; }
		public int getDispatcherRunning() { return this.dispatcherRunning; }
		public int getDispatcherQueued() { return this.dispatcherQueued; }
	}

	private enum ProducerState { RESERVED, SUBMITTING, QUEUED, RUNNING, EXITED, RETIRED }
	private enum JobState { ABSENT, QUEUED, RUNNING, DONE }
	private enum PublisherState { ABSENT, PENDING, ACTIVE, DONE }

	/**
	 * One admitted publisher's acquisition and physical provider work. The adapter
	 * closes this obligation only when no subscription or entered provider call can
	 * still require cleanup. A deadline never closes it on the adapter's behalf.
	 */
	@ThreadSafe
	public final class PublisherWork implements AutoCloseable {
		private final Reservation reservation;

		private PublisherWork(Reservation reservation) {
			this.reservation = reservation;
		}

		/** Records acquisition once; a late notification cannot revive a released obligation. */
		public void subscriptionReceived() {
			synchronized (lock) {
				if (this.reservation.publisherState == PublisherState.PENDING)
					this.reservation.publisherState = PublisherState.ACTIVE;
			}
		}

		/** Releases this obligation idempotently after physical publisher work has ended. */
		@Override
		public void close() {
			synchronized (lock) {
				if (!this.reservation.hasPublisherWork())
					return;
				this.reservation.publisherState = PublisherState.DONE;
				this.reservation.retireIfFinished();
			}
		}
	}

	@ThreadSafe
	public final class Reservation {
		private final long id;
		private final JobState[] jobs = {JobState.ABSENT, JobState.ABSENT};
		private ProducerState producerState = ProducerState.RESERVED;
		private PublisherState publisherState = PublisherState.ABSENT;
		private long retainedWork;
		@Nullable
		private ProducerTask producerTask;
		@Nullable
		private Thread producerThread;
		@Nullable
		private volatile StreamTerminationReason reason;
		@Nullable
		private Throwable cause;
		@Nullable
		private BiConsumer<StreamTerminationReason, Throwable> terminationHandler;
		private boolean signalClaimed;
		private int signalsRunning;
		private boolean submissionPending;
		private boolean publicationComplete;
		private boolean completionRequested;
		private volatile boolean productionComplete;
		private boolean transportComplete;
		private boolean retired;
		private boolean cleanupStarted;
		private long cleanupDeadline;
		private boolean observerCleanupStarted;
		private long observerCleanupDeadline;
		@Nullable
		private ScheduledFuture<?> cleanupTimer;
		private boolean overdue;
		private boolean diagnosticClaimed;
		private boolean diagnosticPending;

		private Reservation(long id) {
			this.id = id;
		}

		/**
		 * Retains an independently executing acquisition, resource, or transport envelope.
		 * The caller must close the returned proof only after physical work exits or a
		 * never-entered envelope is conclusively retired. A canceled/sealed lifetime
		 * cannot admit new work; work admitted before cancelation remains counted.
		 */
		@Nullable
		public Work retainWork() {
			synchronized (lock) {
				if (this.retired || this.publicationComplete || this.reason != null || this.productionComplete || this.transportComplete)
					return null;
				if (this.retainedWork == Long.MAX_VALUE)
					throw new IllegalStateException("Too many retained streaming work obligations");
				this.retainedWork++;
				return new Work();
			}
		}

		/** Physical-exit proof for one admitted unit of work; closing is idempotent and non-waiting. */
		@ThreadSafe
		public final class Work implements AutoCloseable {
			private boolean closed;

			private Work() {}

			@Override
			public void close() {
				synchronized (lock) {
					if (this.closed)
						return;
					this.closed = true;
					retainedWork--;
					retireIfFinished();
				}
			}
		}

		/**
		 * Retains the sole publisher lifetime before application subscribe is invoked.
		 * Cancelation may already have won after producer entry; its late acquisition
		 * still needs an obligation until the adapter can prove disposal completed.
		 */
		@NonNull
		public PublisherWork retainPublisher() {
			synchronized (lock) {
				if (this.retired || this.publicationComplete || this.productionComplete
						|| this.producerState != ProducerState.RUNNING)
					throw new IllegalStateException("Publisher acquisition is outside its producer lifetime");
				if (this.publisherState != PublisherState.ABSENT)
					throw new IllegalStateException("A publisher lifetime was already retained");
				this.publisherState = PublisherState.PENDING;
				return new PublisherWork(this);
			}
		}

		private boolean hasPublisherWork() {
			return this.publisherState == PublisherState.PENDING || this.publisherState == PublisherState.ACTIVE;
		}

		/**
		 * Binds a framework-only transport signal. Forced cancelation may win before
		 * source construction; late binding delivers that retained outcome exactly once.
		 */
		public void bindTermination(@NonNull BiConsumer<@NonNull StreamTerminationReason, @Nullable Throwable> handler) {
			requireNonNull(handler);
			boolean invoke;
			synchronized (lock) {
				if (this.terminationHandler != null)
					throw new IllegalStateException("Termination handler is already bound");
				if (this.retired)
					throw new IllegalStateException("Reservation is retired");
				this.terminationHandler = handler;
				invoke = claimSignal();
			}
			if (invoke)
				invokeSignal(handler);
		}

		/**
		 * Submits an owned wrapper using execute, never a cancellable FutureTask.
		 * Returns false if cancelation/retirement already prevented submission.
		 * Rejection retires the producer obligation but propagates to the adapter,
		 * which must publish its terminal outcome and call complete().
		 */
		public boolean execute(@NonNull ExecutorService executor, @NonNull Runnable producer) {
			requireNonNull(executor);
			requireNonNull(producer);
			ProducerTask task;
			synchronized (lock) {
				if (this.producerState == ProducerState.RETIRED)
					return false;
				if (this.producerState != ProducerState.RESERVED)
					throw new IllegalStateException("Producer execution was already submitted");
				if (this.reason != null || this.publicationComplete) {
					this.producerState = ProducerState.RETIRED;
					retireIfFinished();
					return false;
				}
				this.producerState = ProducerState.SUBMITTING;
				this.submissionPending = true;
				task = new ProducerTask(this, producer, Thread.currentThread());
				this.producerTask = task;
			}
			try {
				executor.execute(task);
				return true;
			} catch (RejectedExecutionException rejected) {
				synchronized (lock) {
					if (this.producerState == ProducerState.SUBMITTING)
						this.producerState = ProducerState.RETIRED;
				}
				throw rejected;
			} finally {
				synchronized (lock) {
					this.submissionPending = false;
					if (this.producerState == ProducerState.SUBMITTING)
						this.producerState = ProducerState.QUEUED;
					retireIfFinished();
				}
			}
		}

		/**
		 * Runs a simulator producer or an SSE initializer on its already-admitted calling
		 * thread with physical-work and interruption accounting. HTTP network producers
		 * must use {@link #execute(ExecutorService, Runnable)}. SSE adapters separately
		 * retain their enclosing handshake and asynchronous connection envelopes.
		 * Returns false when cancelation or scope shutdown suppressed entry.
		 */
		public boolean executeInline(@NonNull Runnable producer) {
			requireNonNull(producer);
			synchronized (lock) {
				if (this.producerState == ProducerState.RETIRED)
					return false;
				if (this.producerState != ProducerState.RESERVED)
					throw new IllegalStateException("Producer execution was already submitted");
				if (this.reason != null || this.publicationComplete) {
					this.producerState = ProducerState.RETIRED;
					retireIfFinished();
					return false;
				}
				this.producerState = ProducerState.RUNNING;
				this.producerThread = Thread.currentThread();
			}
			try {
				producer.run();
			} catch (Throwable failure) {
				cancel(StreamTerminationReason.PRODUCER_FAILED, failure);
				report(failure);
				complete();
			} finally {
				synchronized (lock) {
					this.producerThread = null;
					this.producerState = ProducerState.EXITED;
					retireIfFinished();
				}
			}
			return true;
		}

		/**
		 * Elects cancelation once. Records the outcome and interrupts an entered
		 * producer before the framework hook can publish blocking application work.
		 * Neither the hook nor this operation enumerates application registrations.
		 */
		public boolean cancel(@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
			requireNonNull(reason);
			if (reason == StreamTerminationReason.COMPLETED)
				throw new IllegalArgumentException("Cancelation cannot have the COMPLETED reason");
			BiConsumer<StreamTerminationReason, Throwable> handler;
			boolean invoke;
			synchronized (lock) {
				if (!reserveCancelation(reason, cause))
					return false;
				handler = this.terminationHandler;
				invoke = claimSignal();
			}
			if (invoke)
				invokeSignal(requireNonNull(handler));
			return true;
		}

		private boolean reserveCancelation(StreamTerminationReason reason, @Nullable Throwable cause) {
			if (this.retired || this.publicationComplete || this.transportComplete || this.reason != null)
				return false;
			this.reason = reason;
			this.cause = cause;
			beginCleanupLocked();
			if (!this.productionComplete && this.producerThread != null
					&& !sameInstance(this.producerThread, Thread.currentThread()))
				this.producerThread.interrupt();
			return true;
		}

		public boolean isCanceled() {
			// Status polling is frequent during scalar output. Elections still hold
			// the coordinator lock, as do the paired reason/cause accessors.
			return this.reason != null;
		}

		/** Elects successful managed production without pretending its execution envelope exited. */
		public boolean completeProduction() {
			synchronized (lock) {
				if (this.productionComplete)
					return true;
				if (this.reason != null)
					return false;
				if (hasPublisherWork())
					throw new IllegalStateException("Publisher work must finish before successful production completes");
				if (this.retainedWork != 0L)
					throw new IllegalStateException("Retained work must finish before successful production completes");
				this.productionComplete = true;
				if (this.cleanupTimer != null)
					this.cleanupTimer.cancel(false);
				return true;
			}
		}

		public boolean isProductionComplete() {
			return this.productionComplete;
		}

		/** Elects successful wire completion against cancelation without sealing observer publication. */
		public boolean completeTransport() {
			synchronized (lock) {
				if (this.transportComplete)
					return true;
				if (this.reason != null)
					return false;
				this.transportComplete = true;
				return true;
			}
		}

		@NonNull
		public Optional<StreamTerminationReason> reason() {
			synchronized (lock) { return Optional.ofNullable(this.reason); }
		}

		@NonNull
		public Optional<Throwable> cause() {
			synchronized (lock) { return Optional.ofNullable(this.cause); }
		}

		/** Reports a failed cleanup attempt through the bounded diagnostic owner without changing the outcome. */
		public void reportCleanupFailure(@NonNull Throwable failure) {
			report(requireNonNull(failure));
		}

		/** Starts one non-resetting terminal-cleanup grace, including normal finalization. */
		public void beginCleanup() {
			synchronized (lock) {
				if (!this.retired)
					beginCleanupLocked();
			}
		}

		/** One application abort/cancelation batch, independently accounted from observers. */
		public void dispatchCallbacks(@NonNull Runnable callbacks) {
			dispatch(0, callbacks);
		}

		/**
		 * One application transport-termination notification, with no cleanup-completion
		 * barrier. After successful production it receives a separate cleanup grace:
		 * healthy wire drain between production and notification spends neither budget.
		 */
		public void dispatchTermination(@NonNull Runnable observer) {
			dispatch(1, observer);
		}

		/**
		 * Seals terminal task publication. Call only after all required callback and
		 * observer work has been published. It does not claim physical work has exited.
		 */
		public void complete() {
			synchronized (lock) {
				this.completionRequested = true;
				completePublicationIfReady();
			}
		}

		private void completePublicationIfReady() {
			// Cancelation state is visible before its framework hook publishes the
			// callback batch. A fast producer exit must not close that publication gap.
			if (this.completionRequested && this.signalsRunning == 0) {
				this.publicationComplete = true;
				if (this.producerState == ProducerState.RESERVED)
					this.producerState = ProducerState.RETIRED;
			}
			retireIfFinished();
		}

		/** Retires a suppressed/unpublished body that never submitted a producer. */
		public void abandon() {
			synchronized (lock) {
				if (this.retired || this.publicationComplete)
					return;
				if (this.producerState != ProducerState.RESERVED && this.producerState != ProducerState.RETIRED)
					throw new IllegalStateException("Cannot abandon accepted producer work");
				this.producerState = ProducerState.RETIRED;
				this.completionRequested = true;
				completePublicationIfReady();
			}
		}

		private boolean claimSignal() {
			if (this.reason == null || this.terminationHandler == null || this.signalClaimed)
				return false;
			this.signalClaimed = true;
			this.signalsRunning++;
			return true;
		}

		private void invokeSignal(BiConsumer<StreamTerminationReason, Throwable> handler) {
			try {
				handler.accept(requireNonNull(this.reason), this.cause);
			} catch (Throwable failure) {
				report(failure);
			} finally {
				synchronized (lock) {
					this.signalsRunning--;
					completePublicationIfReady();
				}
			}
		}

		private void dispatch(int index, Runnable action) {
			requireNonNull(action);
			synchronized (lock) {
				if (this.publicationComplete || this.retired)
					throw new IllegalStateException("Terminal task publication has completed");
				if (index == 0 && this.productionComplete)
					throw new IllegalStateException("Cancelation callbacks cannot be published after successful production");
				if (this.jobs[index] != JobState.ABSENT)
					throw new IllegalStateException("Terminal task was already published");
				this.jobs[index] = JobState.QUEUED;
				if (this.productionComplete && index == 1) {
					this.observerCleanupStarted = true;
					this.observerCleanupDeadline = nanoClock.getAsLong() + cleanupGraceNanos;
					scheduleCleanupCheck(this.observerCleanupDeadline);
				} else if (!this.productionComplete && this.cleanupStarted) {
					// Cancelation jobs share the original budget, even if publication
					// happens after an earlier check found no outstanding physical work.
					scheduleCleanupCheck(this.cleanupDeadline);
				}
			}
			try {
				callbackExecutor.execute(() -> {
					synchronized (lock) { this.jobs[index] = JobState.RUNNING; }
					try {
						action.run();
					} catch (Throwable failure) {
						report(failure);
					} finally {
						synchronized (lock) {
							this.jobs[index] = JobState.DONE;
							retireIfFinished();
						}
					}
				});
			} catch (RejectedExecutionException rejected) {
				// Outstanding reservations prevent orderly executor shutdown. This is
				// an invariant failure, not permission to run user cleanup inline.
				report(new IllegalStateException("Reserved streaming callback capacity was unavailable", rejected));
				synchronized (lock) {
					this.jobs[index] = JobState.DONE;
					retireIfFinished();
				}
				throw rejected;
			}
		}

		private void beginCleanupLocked() {
			if (this.cleanupStarted || this.productionComplete)
				return;
			this.cleanupStarted = true;
			this.cleanupDeadline = nanoClock.getAsLong() + cleanupGraceNanos;
			scheduleCleanupCheck(this.cleanupDeadline);
		}

		private void scheduleCleanupCheck(long deadline) {
			if (this.cleanupTimer != null)
				this.cleanupTimer.cancel(false);
			long delay = Math.max(0L, deadline - nanoClock.getAsLong());
			this.cleanupTimer = supervisor.schedule(() -> checkCleanupDeadline(nanoClock.getAsLong()),
					delay, TimeUnit.NANOSECONDS);
		}

		// Package-private clock entry points exercise the actual deadline transition
		// deterministically without sleeping or changing application-facing clocks.
		long cleanupDeadlineNanos() {
			synchronized (lock) { return this.cleanupDeadline; }
		}

		long observerCleanupDeadlineNanos() {
			synchronized (lock) { return this.observerCleanupDeadline; }
		}

		void checkCleanupDeadline(long nowNanos) {
			CleanupDeadlineExceededException evidence;
			boolean observerPhase;
			BiConsumer<StreamTerminationReason, Throwable> handler = null;
			boolean invoke = false;
			boolean publishDiagnostic;
			synchronized (lock) {
				observerPhase = this.productionComplete;
				boolean started = observerPhase ? this.observerCleanupStarted : this.cleanupStarted;
				long deadline = observerPhase ? this.observerCleanupDeadline : this.cleanupDeadline;
				if (this.retired || !started || this.overdue || nowNanos - deadline < 0L)
					return;
				boolean physicalWorkOutstanding = this.diagnosticPending || hasPublisherWork() || this.retainedWork != 0L;
				if (!observerPhase)
					physicalWorkOutstanding |= this.submissionPending || this.signalsRunning != 0
						|| this.producerState == ProducerState.RUNNING
						|| this.producerState == ProducerState.SUBMITTING || this.producerState == ProducerState.QUEUED;
				for (JobState job : this.jobs)
					physicalWorkOutstanding |= job == JobState.QUEUED || job == JobState.RUNNING;
				// Stage-one integration can retain the slot through wire completion.
				// Merely draining already-produced bytes is not outstanding cleanup.
				if (!physicalWorkOutstanding)
					return;
				this.overdue = true;
				evidence = new CleanupDeadlineExceededException(this.id,
						observerPhase ? "termination-observer" : "producer-cleanup", this.producerState.name(),
						this.jobs[0].name(), this.jobs[1].name(), this.publisherState.name(),
						hasPublisherWork() ? 1 : 0, this.publisherState == PublisherState.PENDING ? 1 : 0,
						this.retainedWork,
						callbackExecutor.getActiveCount(),
						callbackExecutor.getQueue().size());
				publishDiagnostic = claimDiagnostic();
				if (!observerPhase && reserveCancelation(StreamTerminationReason.CLEANUP_TIMEOUT, null)) {
					handler = this.terminationHandler;
					invoke = claimSignal();
				}
			}
			// Transport state publication is framework-only and does not wait for a
			// callback worker. A previously winning cancelation/normal outcome stays.
			if (invoke)
				invokeSignal(requireNonNull(handler));
			if (publishDiagnostic)
				publishDiagnostic(evidence);
		}

		private void report(Throwable failure) {
			synchronized (lock) {
				if (!claimDiagnostic())
					return;
			}
			publishDiagnostic(failure);
		}

		private boolean claimDiagnostic() {
			// One retained diagnostic per reservation bounds queue/storage even if
			// an application observer itself stops returning. Deadline checks claim
			// before signaling so prompt physical cleanup cannot erase their evidence.
			if (this.diagnosticClaimed || this.retired)
				return false;
			this.diagnosticClaimed = true;
			this.diagnosticPending = true;
			return true;
		}

		private void publishDiagnostic(Throwable failure) {
			try {
				diagnosticExecutor.execute(() -> {
					try {
						diagnostics.accept(failure);
					} catch (Throwable ignored) {
						// Diagnostics must not compromise state publication or accounting.
					} finally {
						synchronized (lock) {
							this.diagnosticPending = false;
							retireIfFinished();
						}
					}
				});
			} catch (RejectedExecutionException rejected) {
				synchronized (lock) {
					this.diagnosticPending = false;
					retireIfFinished();
				}
			}
		}

		private void retireIfFinished() {
			if (this.retired || !this.publicationComplete || this.submissionPending
					|| this.signalsRunning != 0 || this.diagnosticPending || hasPublisherWork() || this.retainedWork != 0L
					|| (this.producerState != ProducerState.EXITED && this.producerState != ProducerState.RETIRED))
				return;
			for (JobState job : this.jobs)
				if (job == JobState.QUEUED || job == JobState.RUNNING)
					return;
			this.retired = true;
			if (this.cleanupTimer != null)
				this.cleanupTimer.cancel(false);
			this.terminationHandler = null;
			this.producerTask = null;
			reservations.remove(this);
			stopInfrastructureIfDrained();
			lock.notifyAll();
		}
	}

	private final class ProducerTask implements Runnable {
		private final Reservation reservation;
		private final Runnable producer;
		private final Thread submittingThread;

		private ProducerTask(Reservation reservation, Runnable producer, Thread submittingThread) {
			this.reservation = reservation;
			this.producer = producer;
			this.submittingThread = submittingThread;
		}

		private StreamLifecycleCoordinator coordinator() {
			return StreamLifecycleCoordinator.this;
		}

		@Override
		public void run() {
			synchronized (lock) {
				if (this.reservation.submissionPending && sameInstance(Thread.currentThread(), this.submittingThread))
					throw new RejectedExecutionException("Streaming producer executor must not run submitted work inline");
				if (this.reservation.producerState != ProducerState.SUBMITTING
						&& this.reservation.producerState != ProducerState.QUEUED)
					return;
				if (this.reservation.reason != null || this.reservation.publicationComplete) {
					this.reservation.producerState = ProducerState.RETIRED;
					this.reservation.retireIfFinished();
					return;
				}
				this.reservation.producerState = ProducerState.RUNNING;
				this.reservation.producerThread = Thread.currentThread();
			}
			try {
				this.producer.run();
			} catch (Throwable failure) {
				this.reservation.cancel(StreamTerminationReason.PRODUCER_FAILED, failure);
				this.reservation.report(failure);
				this.reservation.complete();
			} finally {
				synchronized (lock) {
					this.reservation.producerThread = null;
					this.reservation.producerState = ProducerState.EXITED;
					this.reservation.retireIfFinished();
				}
			}
		}
	}

	private void stopInfrastructureIfDrained() {
		if (this.accepting || !this.reservations.isEmpty() || this.infrastructureStopping)
			return;
		this.infrastructureStopping = true;
		this.callbackExecutor.shutdown();
		this.diagnosticExecutor.shutdown();
		this.supervisor.shutdown();
	}

	private static boolean awaitExecutor(ExecutorService executor, long deadline) throws InterruptedException {
		if (executor.isTerminated())
			return true;
		long remaining = deadline - System.nanoTime();
		return remaining > 0L && executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
	}

	private static ThreadFactory threadFactory(String prefix) {
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + "-" + THREAD_SEQUENCE.incrementAndGet());
			thread.setDaemon(false);
			return thread;
		};
	}
}
