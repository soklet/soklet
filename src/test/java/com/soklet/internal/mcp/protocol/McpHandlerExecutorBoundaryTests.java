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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Small deterministic custom-executor checks; no load test or external host. */
@Timeout(20)
class McpHandlerExecutorBoundaryTests {
	@Test
	void directHandoffRejectionCannotDiscardTheAcceptedQueue() throws Exception {
		verifyWorkerReuse(false);
	}

	@Test
	void gracefulDrainPreservesQueuedTicketsWhenTheExecutorStopsAccepting() throws Exception {
		verifyWorkerReuse(true);
	}

	@Test
	void retainedHandoffAppliesOnlyThePromotedTicketsRequestedInterrupt() throws Exception {
		DirectHandoffExecutor executor = new DirectHandoffExecutor();
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(1, 2, executor);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<McpApplicationHandlerDispatcher.Ticket> promoted = new AtomicReference<>();
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		List<Boolean> interruptStates = new CopyOnWriteArrayList<>();
		AtomicInteger physicalExits = new AtomicInteger();
		McpApplicationHandlerDispatcher.Ticket active = dispatcher.newTicket(() -> {
			entered.countDown();
			release.await();
		}, failures::add, ignored -> {}, () -> {
			promoted.get().requestInterrupt();
			physicalExits.incrementAndGet();
		});
		try {
			dispatcher.admit(active);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			for (int index = 0; index < 2; index++) {
				McpApplicationHandlerDispatcher.Ticket ticket = dispatcher.newTicket(
						() -> interruptStates.add(Thread.currentThread().isInterrupted()),
						failures::add, ignored -> {}, physicalExits::incrementAndGet);
				if (index == 0) promoted.set(ticket);
				assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED, dispatcher.admit(ticket));
			}
			release.countDown();
			awaitCondition(() -> physicalExits.get() == 3);
			assertTrue(failures.isEmpty());
			assertEquals(List.of(true, false), interruptStates);
			assertEquals(0, dispatcher.snapshot().activeSlots());
		} finally {
			release.countDown();
			dispatcher.stopAccepting();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	private static void verifyWorkerReuse(boolean drain) throws Exception {
		DirectHandoffExecutor executor = new DirectHandoffExecutor();
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(1, 128, executor);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		List<Integer> ran = new CopyOnWriteArrayList<>();
		List<McpApplicationHandlerDispatcher.Ticket> queued = new ArrayList<>();
		AtomicInteger firstDepth = new AtomicInteger();
		AtomicInteger maximumDepth = new AtomicInteger();
		AtomicInteger physicalExits = new AtomicInteger();
		McpApplicationHandlerDispatcher.Ticket active = dispatcher.newTicket(() -> {
			firstDepth.set(Thread.currentThread().getStackTrace().length);
			entered.countDown();
			release.await();
		}, failures::add, ignored -> {}, physicalExits::incrementAndGet);
		try {
			assertEquals(McpApplicationHandlerDispatcher.Admission.DISPATCHED, dispatcher.admit(active));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			for (int index = 0; index < 128; index++) {
				int value = index;
				McpApplicationHandlerDispatcher.Ticket ticket = dispatcher.newTicket(() -> {
					assertFalse(Thread.currentThread().isInterrupted(), "The prior ticket's interrupt must not leak.");
					maximumDepth.accumulateAndGet(Thread.currentThread().getStackTrace().length, Math::max);
					ran.add(value);
					Thread.currentThread().interrupt();
				}, failures::add, ignored -> {}, physicalExits::incrementAndGet);
				queued.add(ticket);
				assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED, dispatcher.admit(ticket));
			}
			if (drain) {
				dispatcher.beginGracefulDrain();
				executor.shutdown();
				assertEquals(McpApplicationHandlerDispatcher.Admission.CLOSED,
						dispatcher.admit(dispatcher.newTicket(() -> fail("New work entered during drain."), failures::add)));
			}
			release.countDown();
			awaitCondition(() -> dispatcher.snapshot().activeSlots() == 0 && physicalExits.get() == 129);
			assertTrue(failures.isEmpty(), () -> "Accepted tickets failed during handoff: " + failures.size());
			assertEquals(java.util.stream.IntStream.range(0, 128).boxed().toList(), ran);
			for (McpApplicationHandlerDispatcher.Ticket ticket : queued)
				assertEquals(McpApplicationHandlerDispatcher.TicketState.EXITED, ticket.state());
			assertEquals(129, physicalExits.get());
			assertEquals(1, dispatcher.snapshot().maximumObservedActiveSlots());
			assertEquals(128, dispatcher.snapshot().maximumObservedQueueDepth());
			assertEquals(0, dispatcher.snapshot().queueDepth());
			assertTrue(maximumDepth.get() <= firstDepth.get() + 4, "Worker reuse must be iterative, not recursive.");
		} finally {
			release.countDown();
			dispatcher.stopAccepting();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void inlineExecutorCannotEnterApplicationCodeOnTheSubmittingThread() {
		ExecutorService executor = new InlineExecutor();
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(1, 1, executor);
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket ticket = dispatcher.newTicket(calls::incrementAndGet, failure::set);
		try {
			dispatcher.admit(ticket);
			assertEquals(0, calls.get(), "Application work must not occupy a protocol submitting thread.");
			assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, failure.get());
			assertEquals(McpApplicationHandlerDispatcher.TicketState.REJECTED, ticket.state());
			assertEquals(0, dispatcher.snapshot().activeSlots());
		} finally {
			dispatcher.stopAccepting();
			executor.shutdownNow();
		}
	}

	@Test
	void policySubmissionRejectionIsCapacityWhileCallbackRejectionRemainsAnApplicationFailure() throws Exception {
		AtomicInteger submissions = new AtomicInteger();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(1)) {
			@Override public void execute(Runnable command) {
				if (submissions.incrementAndGet() == 1)
					throw new RejectedExecutionException("private executor detail");
				super.execute(command);
			}
		};
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(1, 1, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM, concurrency -> executor);
		AtomicInteger calls = new AtomicInteger();
		try {
			execution.start();
			assertThrows(McpApplicationPolicyCapacityException.class, () -> execution.invokeBoundedPolicy(
					() -> { calls.incrementAndGet(); return "unexpected"; }, policyDeadline()));
			assertEquals(0, calls.get());
			assertEquals(0, execution.snapshot().activeHandlerSlots());
			assertEquals("recovered", execution.invokeBoundedPolicy(() -> "recovered", policyDeadline()));
			awaitCondition(() -> execution.snapshot().activeHandlerSlots() == 0);
			RejectedExecutionException callbackFailure = new RejectedExecutionException("private callback detail");
			assertSame(callbackFailure, assertThrows(RejectedExecutionException.class,
					() -> execution.invokeBoundedPolicy(() -> { throw callbackFailure; }, policyDeadline())));
			awaitCondition(() -> execution.snapshot().activeHandlerSlots() == 0);
		} finally {
			execution.stop();
			assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	private static long policyDeadline() {
		return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
	}

	private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L)
			Thread.sleep(5L);
		assertTrue(condition.getAsBoolean());
	}

	private static final class DirectHandoffExecutor extends ThreadPoolExecutor {
		private DirectHandoffExecutor() {
			super(1, 1, 0L, TimeUnit.SECONDS, new SynchronousQueue<>(),
					runnable -> new Thread(runnable, "mcp-direct-handoff-test"), new AbortPolicy());
		}
	}

	private static final class InlineExecutor extends AbstractExecutorService {
		private boolean stopped;
		@Override public void execute(Runnable command) { command.run(); }
		@Override public void shutdown() { stopped = true; }
		@Override public List<Runnable> shutdownNow() { stopped = true; return List.of(); }
		@Override public boolean isShutdown() { return stopped; }
		@Override public boolean isTerminated() { return stopped; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
	}
}
