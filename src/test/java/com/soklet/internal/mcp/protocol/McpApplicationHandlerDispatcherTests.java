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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@NotThreadSafe
public class McpApplicationHandlerDispatcherTests {
	@Test
	public void observer_transitions_are_globally_ordered_and_drained_unlocked()
			throws Exception {
		ExecutorService executor = singleThreadExecutor(
				"mcp-application-observer-order-test");
		ExecutorService probeExecutor = Executors.newFixedThreadPool(2);
		AtomicReference<McpApplicationHandlerDispatcher> dispatcherReference =
				new AtomicReference<>();
		LockProbingExecutionObserver observer = new LockProbingExecutionObserver(
				probeExecutor, dispatcherReference);
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor, observer);
		dispatcherReference.set(dispatcher);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondExited = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket first = dispatcher.newTicket(() -> {
			firstEntered.countDown();
			releaseFirst.await();
		}, failure::set);
		McpApplicationHandlerDispatcher.Ticket second = dispatcher.newTicket(
				secondExited::countDown, failure::set);
		McpApplicationHandlerDispatcher.Ticket rejected = dispatcher.newTicket(() -> {
			throw new AssertionError("Capacity-rejected work ran.");
		}, failure::set);

		try {
			Assertions.assertEquals(
					McpApplicationHandlerDispatcher.Admission.DISPATCHED,
					dispatcher.admit(first));
			Assertions.assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED,
					dispatcher.admit(second));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.REJECTED,
					dispatcher.admit(rejected));

			releaseFirst.countDown();
			Assertions.assertTrue(secondExited.await(3, TimeUnit.SECONDS));
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			observer.awaitCompletedDrains(5);
			Assertions.assertNull(observer.probeFailure(),
					"Observer drain ran while the dispatcher lock was held.");
			Assertions.assertEquals(List.of(
					"HandlerExecutionStarted",
					"HandlerQueued",
					"HandlerCapacityRejected",
					"HandlerExecutionFinished",
					"HandlerDequeued",
					"HandlerExecutionStarted",
					"HandlerExecutionFinished"),
					observer.transitions());
			Assertions.assertNull(failure.get());
		} finally {
			releaseFirst.countDown();
			stop(dispatcher, executor);
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					3, TimeUnit.SECONDS));
		}
	}

	@Test
	public void queue_removal_is_balanced_and_only_a_full_queue_is_rejected()
			throws Exception {
		ExecutorService executor = singleThreadExecutor(
				"mcp-application-observer-removal-test");
		RecordingExecutionObserver observer = new RecordingExecutionObserver();
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 2, executor, observer);
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		AtomicInteger queuedRuns = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket active = dispatcher.newTicket(() -> {
			activeEntered.countDown();
			releaseActive.await();
		}, failure::set);
		McpApplicationHandlerDispatcher.Ticket canceled = dispatcher.newTicket(
				queuedRuns::incrementAndGet, failure::set);
		McpApplicationHandlerDispatcher.Ticket stopped = dispatcher.newTicket(
				queuedRuns::incrementAndGet, failure::set);

		try {
			Assertions.assertEquals(
					McpApplicationHandlerDispatcher.Admission.DISPATCHED,
					dispatcher.admit(active));
			Assertions.assertTrue(activeEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED,
					dispatcher.admit(canceled));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED,
					dispatcher.admit(stopped));
			Assertions.assertTrue(dispatcher.cancelBeforeDispatch(canceled));
			Assertions.assertEquals(List.of(stopped), dispatcher.stopAccepting());

			McpApplicationHandlerDispatcher.Ticket afterStop = dispatcher.newTicket(
					queuedRuns::incrementAndGet, failure::set);
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.CLOSED,
					dispatcher.admit(afterStop));
			McpApplicationHandlerDispatcher.Ticket canceledBeforeAdmission =
					dispatcher.newTicket(queuedRuns::incrementAndGet, failure::set);
			Assertions.assertTrue(dispatcher.cancelBeforeDispatch(
					canceledBeforeAdmission));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.CANCELED,
					dispatcher.admit(canceledBeforeAdmission));

			releaseActive.countDown();
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			Assertions.assertEquals(List.of(
					"HandlerExecutionStarted",
					"HandlerQueued",
					"HandlerQueued",
					"HandlerDequeued",
					"HandlerDequeued",
					"HandlerExecutionFinished"),
					observer.transitions());
			Assertions.assertEquals(0, queuedRuns.get());
			Assertions.assertNull(failure.get());
		} finally {
			releaseActive.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	public void observer_failures_and_drain_reentrancy_are_contained()
			throws Exception {
		ExecutorService executor = singleThreadExecutor(
				"mcp-application-observer-failure-test");
		AtomicReference<McpApplicationHandlerDispatcher> dispatcherReference =
				new AtomicReference<>();
		FailingReentrantExecutionObserver observer =
				new FailingReentrantExecutionObserver(dispatcherReference);
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor, observer);
		dispatcherReference.set(dispatcher);
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		AtomicInteger queuedRuns = new AtomicInteger();
		AtomicReference<Throwable> workFailure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket active = dispatcher.newTicket(() -> {
			activeEntered.countDown();
			releaseActive.await();
		}, workFailure::set);
		McpApplicationHandlerDispatcher.Ticket canceled = dispatcher.newTicket(
				queuedRuns::incrementAndGet, workFailure::set);
		McpApplicationHandlerDispatcher.Ticket rejected = dispatcher.newTicket(
				queuedRuns::incrementAndGet, workFailure::set);

		try {
			Assertions.assertEquals(
					McpApplicationHandlerDispatcher.Admission.DISPATCHED,
					dispatcher.admit(active));
			Assertions.assertTrue(activeEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED,
					dispatcher.admit(canceled));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.REJECTED,
					dispatcher.admit(rejected));
			Assertions.assertTrue(dispatcher.cancelBeforeDispatch(canceled));
			releaseActive.countDown();
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			awaitCondition(() -> observer.successfulDrainReentries() == 5);

			Assertions.assertEquals(5, observer.recordAttempts());
			Assertions.assertEquals(5, observer.successfulDrainReentries());
			Assertions.assertEquals(0, queuedRuns.get());
			Assertions.assertNull(workFailure.get());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.EXITED,
					active.state());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.CANCELED,
					canceled.state());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.REJECTED,
					rejected.state());
		} finally {
			releaseActive.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	public void slot_and_queue_bounds_are_exact_and_recover_after_work_exits()
			throws Exception {
		ExecutorService executor = singleThreadExecutor("mcp-application-bounds-test");
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondExited = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket first = dispatcher.newTicket(() -> {
			firstEntered.countDown();
			releaseFirst.await();
		}, failure::set);
		McpApplicationHandlerDispatcher.Ticket second = dispatcher.newTicket(
				secondExited::countDown, failure::set);
		McpApplicationHandlerDispatcher.Ticket third = dispatcher.newTicket(() -> {
			throw new AssertionError("Rejected work ran.");
		}, failure::set);

		try {
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.DISPATCHED,
					dispatcher.admit(first));
			Assertions.assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED,
					dispatcher.admit(second));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.REJECTED,
					dispatcher.admit(third));

			McpApplicationHandlerDispatcher.Snapshot saturated = dispatcher.snapshot();
			Assertions.assertEquals(1, saturated.concurrency());
			Assertions.assertEquals(1, saturated.queueCapacity());
			Assertions.assertEquals(1, saturated.activeSlots());
			Assertions.assertEquals(1, saturated.queueDepth());
			Assertions.assertEquals(1, saturated.maximumObservedActiveSlots());
			Assertions.assertEquals(1, saturated.maximumObservedQueueDepth());
			Assertions.assertTrue(saturated.accepting());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.REJECTED,
					third.state());

			releaseFirst.countDown();
			Assertions.assertTrue(secondExited.await(3, TimeUnit.SECONDS));
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.EXITED,
					first.state());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.EXITED,
					second.state());
			Assertions.assertNull(failure.get());
		} finally {
			releaseFirst.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	public void cancel_before_admission_is_terminal_and_work_is_never_submitted()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor);
		AtomicInteger runs = new AtomicInteger();
		McpApplicationHandlerDispatcher.Ticket ticket = dispatcher.newTicket(
				runs::incrementAndGet, failure -> Assertions.fail(failure));

		try {
			Assertions.assertTrue(dispatcher.cancelBeforeDispatch(ticket));
			Assertions.assertFalse(dispatcher.cancelBeforeDispatch(ticket));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.CANCELED,
					ticket.state());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.CANCELED,
					dispatcher.admit(ticket));
			Assertions.assertNull(executor.command());
			Assertions.assertEquals(0, runs.get());
			Assertions.assertEquals(0, dispatcher.snapshot().activeSlots());
			Assertions.assertEquals(0, dispatcher.snapshot().queueDepth());
		} finally {
			stop(dispatcher, executor);
		}
	}

	@Test
	public void cancel_before_dispatch_atomically_removes_a_queued_ticket()
			throws Exception {
		ExecutorService executor = singleThreadExecutor("mcp-application-cancel-test");
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger queuedRuns = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket first = dispatcher.newTicket(() -> {
			firstEntered.countDown();
			releaseFirst.await();
		}, failure::set);
		McpApplicationHandlerDispatcher.Ticket queued = dispatcher.newTicket(
				queuedRuns::incrementAndGet, failure::set);

		try {
			dispatcher.admit(first);
			Assertions.assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED,
					dispatcher.admit(queued));
			Assertions.assertTrue(dispatcher.cancelBeforeDispatch(queued));
			Assertions.assertFalse(dispatcher.cancelBeforeDispatch(queued));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.CANCELED,
					queued.state());
			Assertions.assertEquals(0, dispatcher.snapshot().queueDepth());

			releaseFirst.countDown();
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			Assertions.assertEquals(0, queuedRuns.get());
			Assertions.assertNull(failure.get());
		} finally {
			releaseFirst.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	public void stop_accepting_cancels_every_queued_ticket_but_not_active_work()
			throws Exception {
		ExecutorService executor = singleThreadExecutor("mcp-application-stop-test");
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 2, executor);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger queuedRuns = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket first = dispatcher.newTicket(() -> {
			firstEntered.countDown();
			releaseFirst.await();
		}, failure::set);
		McpApplicationHandlerDispatcher.Ticket second = dispatcher.newTicket(
				queuedRuns::incrementAndGet, failure::set);
		McpApplicationHandlerDispatcher.Ticket third = dispatcher.newTicket(
				queuedRuns::incrementAndGet, failure::set);

		try {
			dispatcher.admit(first);
			Assertions.assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			dispatcher.admit(second);
			dispatcher.admit(third);

			List<McpApplicationHandlerDispatcher.Ticket> canceled =
					dispatcher.stopAccepting();
			Assertions.assertEquals(List.of(second, third), canceled);
			Assertions.assertThrows(UnsupportedOperationException.class, canceled::clear);
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.CANCELED,
					second.state());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.CANCELED,
					third.state());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.DISPATCHED,
					first.state());
			Assertions.assertFalse(dispatcher.snapshot().accepting());
			Assertions.assertEquals(1, dispatcher.snapshot().activeSlots());
			Assertions.assertEquals(0, dispatcher.snapshot().queueDepth());
			Assertions.assertEquals(List.of(), dispatcher.stopAccepting());

			McpApplicationHandlerDispatcher.Ticket afterStop = dispatcher.newTicket(
					queuedRuns::incrementAndGet, failure::set);
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.CLOSED,
					dispatcher.admit(afterStop));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.REJECTED,
					afterStop.state());

			releaseFirst.countDown();
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			Assertions.assertEquals(0, queuedRuns.get());
			Assertions.assertNull(failure.get());
		} finally {
			releaseFirst.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	public void interrupt_requested_before_handler_publication_reaches_work()
			throws Exception {
		ManualExecutorService executor = new ManualExecutorService();
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor);
		AtomicBoolean workSawInterrupt = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket ticket = dispatcher.newTicket(
				() -> workSawInterrupt.set(Thread.currentThread().isInterrupted()),
				failure::set);

		try {
			Assertions.assertEquals(McpApplicationHandlerDispatcher.Admission.DISPATCHED,
					dispatcher.admit(ticket));
			Assertions.assertNotNull(executor.command());
			Assertions.assertNull(ticket.handlerThread());
			ticket.requestInterrupt();

			Thread handler = new Thread(executor.command(),
					"mcp-application-latched-interrupt-test");
			handler.start();
			handler.join(TimeUnit.SECONDS.toMillis(3));
			Assertions.assertFalse(handler.isAlive());
			Assertions.assertTrue(workSawInterrupt.get());
			Assertions.assertNull(failure.get());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.EXITED,
					ticket.state());
			Assertions.assertEquals(0, dispatcher.snapshot().activeSlots());
		} finally {
			stop(dispatcher, executor);
		}
	}

	@Test
	public void repeated_interrupt_requests_deliver_one_signal() throws Exception {
		ExecutorService executor = singleThreadExecutor(
				"mcp-application-idempotent-interrupt-test");
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor);
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch firstInterruptObserved = new CountDownLatch(1);
		CountDownLatch releaseAfterInterrupt = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		AtomicInteger interruptions = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket ticket = dispatcher.newTicket(() -> {
			handlerEntered.countDown();
			try {
				new CountDownLatch(1).await();
			} catch (InterruptedException expected) {
				interruptions.incrementAndGet();
				firstInterruptObserved.countDown();
			}
			try {
				releaseAfterInterrupt.await();
			} catch (InterruptedException duplicate) {
				interruptions.incrementAndGet();
			}
			handlerExited.countDown();
		}, failure::set);

		try {
			dispatcher.admit(ticket);
			Assertions.assertTrue(handlerEntered.await(3, TimeUnit.SECONDS));
			ticket.requestInterrupt();
			Assertions.assertTrue(firstInterruptObserved.await(3, TimeUnit.SECONDS));
			ticket.requestInterrupt();
			releaseAfterInterrupt.countDown();
			Assertions.assertTrue(handlerExited.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, interruptions.get());
			Assertions.assertNull(failure.get());
		} finally {
			releaseAfterInterrupt.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	public void interruption_does_not_release_a_slot_until_work_actually_exits()
			throws Exception {
		ExecutorService executor = singleThreadExecutor("mcp-application-interrupt-test");
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch interruptionObserved = new CountDownLatch(1);
		CountDownLatch releaseAfterInterruption = new CountDownLatch(1);
		CountDownLatch secondRan = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket first = dispatcher.newTicket(() -> {
			firstEntered.countDown();

			try {
				new CountDownLatch(1).await();
			} catch (InterruptedException exception) {
				interruptionObserved.countDown();
			}

			releaseAfterInterruption.await();
		}, failure::set);
		McpApplicationHandlerDispatcher.Ticket second = dispatcher.newTicket(
				secondRan::countDown, failure::set);

		try {
			dispatcher.admit(first);
			Assertions.assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			dispatcher.admit(second);
			first.requestInterrupt();
			Assertions.assertTrue(interruptionObserved.await(3, TimeUnit.SECONDS));

			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.DISPATCHED,
					first.state());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.QUEUED,
					second.state());
			Assertions.assertEquals(1, dispatcher.snapshot().activeSlots());
			Assertions.assertEquals(1, dispatcher.snapshot().queueDepth());
			Assertions.assertEquals(1L, secondRan.getCount());

			releaseAfterInterruption.countDown();
			Assertions.assertTrue(secondRan.await(3, TimeUnit.SECONDS));
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			Assertions.assertNull(failure.get());
		} finally {
			releaseAfterInterruption.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	public void submission_failure_releases_the_slot_and_promotes_following_work()
			throws Exception {
		RejectSecondSubmissionExecutor executor = new RejectSecondSubmissionExecutor();
		RecordingExecutionObserver observer = new RecordingExecutionObserver();
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 2, executor, observer);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch thirdRan = new CountDownLatch(1);
		AtomicReference<Throwable> observedSubmissionFailure = new AtomicReference<>();
		AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket first = dispatcher.newTicket(() -> {
			firstEntered.countDown();
			releaseFirst.await();
		}, unexpectedFailure::set);
		McpApplicationHandlerDispatcher.Ticket rejected = dispatcher.newTicket(() -> {
			throw new AssertionError("Rejected submission ran.");
		}, failure -> {
			observedSubmissionFailure.set(failure);
			throw new IllegalStateException("Failure observer failed.");
		});
		McpApplicationHandlerDispatcher.Ticket third = dispatcher.newTicket(
				thirdRan::countDown, unexpectedFailure::set);

		try {
			dispatcher.admit(first);
			Assertions.assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			dispatcher.admit(rejected);
			dispatcher.admit(third);
			releaseFirst.countDown();

			Assertions.assertTrue(thirdRan.await(3, TimeUnit.SECONDS));
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			Assertions.assertInstanceOf(RejectedExecutionException.class,
					observedSubmissionFailure.get());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.REJECTED,
					rejected.state());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.EXITED,
					third.state());
			Assertions.assertEquals(3, executor.submissionCount());
			Assertions.assertEquals(1, dispatcher.snapshot().maximumObservedActiveSlots());
			Assertions.assertEquals(2, dispatcher.snapshot().maximumObservedQueueDepth());
			Assertions.assertEquals(List.of(
					"HandlerExecutionStarted",
					"HandlerQueued",
					"HandlerQueued",
					"HandlerExecutionFinished",
					"HandlerDequeued",
					"HandlerExecutionStarted",
					"HandlerExecutionFinished",
					"HandlerDequeued",
					"HandlerExecutionStarted",
					"HandlerExecutionFinished"),
					observer.transitions());
			Assertions.assertNull(unexpectedFailure.get());
		} finally {
			releaseFirst.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	public void work_failure_observer_is_contained_and_queued_work_still_runs()
			throws Exception {
		ExecutorService executor = singleThreadExecutor("mcp-application-failure-test");
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondRan = new CountDownLatch(1);
		AtomicReference<Throwable> observedFailure = new AtomicReference<>();
		AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();
		IllegalArgumentException applicationFailure =
				new IllegalArgumentException("Application failure.");
		McpApplicationHandlerDispatcher.Ticket first = dispatcher.newTicket(() -> {
			firstEntered.countDown();
			releaseFirst.await();
			throw applicationFailure;
		}, failure -> {
			observedFailure.set(failure);
			throw new AssertionError("Observer failure.");
		});
		McpApplicationHandlerDispatcher.Ticket second = dispatcher.newTicket(
				secondRan::countDown, unexpectedFailure::set);

		try {
			dispatcher.admit(first);
			Assertions.assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			dispatcher.admit(second);
			releaseFirst.countDown();

			Assertions.assertTrue(secondRan.await(3, TimeUnit.SECONDS));
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0);
			Assertions.assertSame(applicationFailure, observedFailure.get());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.EXITED,
					first.state());
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.EXITED,
					second.state());
			Assertions.assertNull(unexpectedFailure.get());
		} finally {
			releaseFirst.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	public void bounds_nulls_and_foreign_tickets_are_rejected() throws Exception {
		ExecutorService executor = singleThreadExecutor("mcp-application-validation-test");
		McpApplicationHandlerDispatcher dispatcher =
				new McpApplicationHandlerDispatcher(1, 1, executor);
		McpApplicationHandlerDispatcher other =
				new McpApplicationHandlerDispatcher(1, 1, executor);

		try {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> new McpApplicationHandlerDispatcher(0, 1, executor));
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> new McpApplicationHandlerDispatcher(1, 0, executor));
			Assertions.assertThrows(NullPointerException.class,
					() -> new McpApplicationHandlerDispatcher(1, 1, null));
			Assertions.assertThrows(NullPointerException.class,
					() -> new McpApplicationHandlerDispatcher(
							1, 1, executor, null));
			Assertions.assertThrows(NullPointerException.class,
					() -> dispatcher.newTicket(null, failure -> {
					}));
			Assertions.assertThrows(NullPointerException.class,
					() -> dispatcher.newTicket(() -> {
					}, null));
			Assertions.assertThrows(NullPointerException.class,
					() -> dispatcher.admit(null));
			Assertions.assertThrows(NullPointerException.class,
					() -> dispatcher.cancelBeforeDispatch(null));

			McpApplicationHandlerDispatcher.Ticket foreign = other.newTicket(() -> {
			}, failure -> {
			});
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> dispatcher.admit(foreign));
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> dispatcher.cancelBeforeDispatch(foreign));
			Assertions.assertEquals(McpApplicationHandlerDispatcher.TicketState.NEW,
					foreign.state());
		} finally {
			dispatcher.stopAccepting();
			other.stopAccepting();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	private static ExecutorService singleThreadExecutor(String threadName) {
		return Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, threadName);
			thread.setDaemon(true);
			return thread;
		});
	}

	private static void stop(McpApplicationHandlerDispatcher dispatcher,
			ExecutorService executor) throws InterruptedException {
		dispatcher.stopAccepting();
		executor.shutdownNow();
		Assertions.assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
	}

	private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
		long startedAt = System.nanoTime();
		long timeoutNanos = TimeUnit.SECONDS.toNanos(3);

		while (!condition.getAsBoolean()
				&& System.nanoTime() - startedAt < timeoutNanos)
			Thread.sleep(10L);

		Assertions.assertTrue(condition.getAsBoolean(), "Condition did not become true in time.");
	}

	private static class RecordingExecutionObserver
			implements McpApplicationExecutionObserver {
		private final List<String> transitions;

		private RecordingExecutionObserver() {
			this.transitions = new CopyOnWriteArrayList<>();
		}

		@Override
		public void beginDeferral() {
		}

		@Override
		public void recordHandlerExecutionStarted() {
			this.transitions.add("HandlerExecutionStarted");
		}

		@Override
		public void recordHandlerExecutionFinished() {
			this.transitions.add("HandlerExecutionFinished");
		}

		@Override
		public void recordHandlerQueued() {
			this.transitions.add("HandlerQueued");
		}

		@Override
		public void recordHandlerDequeued() {
			this.transitions.add("HandlerDequeued");
		}

		@Override
		public void recordHandlerCapacityRejected() {
			this.transitions.add("HandlerCapacityRejected");
		}

		@Override
		public void drain() {
		}

		@Override
		public void endDeferral() {
		}

		List<String> transitions() {
			return List.copyOf(this.transitions);
		}
	}

	private static final class LockProbingExecutionObserver
			extends RecordingExecutionObserver {
		private final ExecutorService probeExecutor;
		private final AtomicReference<McpApplicationHandlerDispatcher>
				dispatcherReference;
		private final AtomicInteger completedDrains;
		private final AtomicReference<Throwable> probeFailure;

		private LockProbingExecutionObserver(
				ExecutorService probeExecutor,
				AtomicReference<McpApplicationHandlerDispatcher>
						dispatcherReference) {
			this.probeExecutor = probeExecutor;
			this.dispatcherReference = dispatcherReference;
			this.completedDrains = new AtomicInteger();
			this.probeFailure = new AtomicReference<>();
		}

		@Override
		public void drain() {
			try {
				Future<McpApplicationHandlerDispatcher.Snapshot> probe =
						this.probeExecutor.submit(() ->
								this.dispatcherReference.get().snapshot());
				probe.get(1, TimeUnit.SECONDS);
			} catch (Throwable throwable) {
				this.probeFailure.compareAndSet(null, throwable);
			} finally {
				this.completedDrains.incrementAndGet();
			}
		}

		private void awaitCompletedDrains(int expectedCount)
				throws InterruptedException {
			awaitCondition(() -> this.completedDrains.get() == expectedCount);
		}

		private Throwable probeFailure() {
			return this.probeFailure.get();
		}
	}

	private static final class FailingReentrantExecutionObserver
			implements McpApplicationExecutionObserver {
		private final AtomicReference<McpApplicationHandlerDispatcher>
				dispatcherReference;
		private final AtomicInteger recordAttempts;
		private final AtomicInteger successfulDrainReentries;

		private FailingReentrantExecutionObserver(
				AtomicReference<McpApplicationHandlerDispatcher>
						dispatcherReference) {
			this.dispatcherReference = dispatcherReference;
			this.recordAttempts = new AtomicInteger();
			this.successfulDrainReentries = new AtomicInteger();
		}

		@Override
		public void beginDeferral() {
		}

		@Override
		public void recordHandlerExecutionStarted() {
			failRecord();
		}

		@Override
		public void recordHandlerExecutionFinished() {
			failRecord();
		}

		@Override
		public void recordHandlerQueued() {
			failRecord();
		}

		@Override
		public void recordHandlerDequeued() {
			failRecord();
		}

		@Override
		public void recordHandlerCapacityRejected() {
			failRecord();
		}

		@Override
		public void drain() {
			this.dispatcherReference.get().snapshot();
			this.successfulDrainReentries.incrementAndGet();
			throw new IllegalStateException("Synthetic observer drain failure.");
		}

		@Override
		public void endDeferral() {
		}

		private void failRecord() {
			this.recordAttempts.incrementAndGet();
			throw new IllegalStateException("Synthetic observer record failure.");
		}

		private int recordAttempts() {
			return this.recordAttempts.get();
		}

		private int successfulDrainReentries() {
			return this.successfulDrainReentries.get();
		}
	}

	private static final class ManualExecutorService extends AbstractExecutorService {
		private final AtomicReference<Runnable> command;
		private final AtomicBoolean shutdown;

		private ManualExecutorService() {
			this.command = new AtomicReference<>();
			this.shutdown = new AtomicBoolean();
		}

		@Override
		public void shutdown() {
			shutdown.set(true);
		}

		@Override
		public List<Runnable> shutdownNow() {
			shutdown.set(true);
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return shutdown.get();
		}

		@Override
		public boolean isTerminated() {
			return shutdown.get();
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return shutdown.get();
		}

		@Override
		public void execute(Runnable command) {
			if (shutdown.get())
				throw new RejectedExecutionException("Executor is shut down.");

			if (!this.command.compareAndSet(null, command))
				throw new RejectedExecutionException("A command is already pending.");
		}

		private Runnable command() {
			return command.get();
		}
	}

	private static final class RejectSecondSubmissionExecutor
			extends AbstractExecutorService {
		private final ExecutorService delegate;
		private final AtomicInteger submissionCount;

		private RejectSecondSubmissionExecutor() {
			this.delegate = singleThreadExecutor("mcp-application-rejection-test");
			this.submissionCount = new AtomicInteger();
		}

		@Override
		public void shutdown() {
			delegate.shutdown();
		}

		@Override
		public List<Runnable> shutdownNow() {
			return delegate.shutdownNow();
		}

		@Override
		public boolean isShutdown() {
			return delegate.isShutdown();
		}

		@Override
		public boolean isTerminated() {
			return delegate.isTerminated();
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit)
				throws InterruptedException {
			return delegate.awaitTermination(timeout, unit);
		}

		@Override
		public void execute(Runnable command) {
			int submission = submissionCount.incrementAndGet();

			if (submission == 2)
				throw new RejectedExecutionException("Synthetic second-submission failure.");

			delegate.execute(command);
		}

		private int submissionCount() {
			return submissionCount.get();
		}
	}
}
