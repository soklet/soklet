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

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpInterruptDeliveryTests {
	@Test
	void defaultDeliveryConstructionDoesNotInvokeApplicationThreadLocalInheritance() throws Exception {
		AtomicInteger inheritanceCalls = new AtomicInteger();
		InheritableThreadLocal<String> applicationLocal = new InheritableThreadLocal<>() {
			@Override protected String childValue(String value) {
				inheritanceCalls.incrementAndGet();
				throw new AssertionError("Application inheritance callback ran during interrupt delivery construction.");
			}
		};
		AtomicReference<String> deliveryValue = new AtomicReference<>("not-entered");
		McpApplicationExecutionObserver observer = new NoopObserver() {
			@Override public LifecycleScope enterLifecycleExecution() {
				deliveryValue.set(applicationLocal.get());
				return () -> {};
			}
		};
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(1, 1, executor, observer);
		var ticket = dispatcher.newTicket(() -> {
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException expected) { interrupted.countDown(); }
			awaitUninterruptibly(release);
		}, failure::set);
		try {
			dispatcher.admit(ticket);
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			applicationLocal.set("application-state");
			ticket.requestInterrupt();
			assertTrue(interrupted.await(3, TimeUnit.SECONDS));
			assertEquals(0, inheritanceCalls.get());
			assertNull(deliveryValue.get(), "The delivery helper must not inherit application thread-local state.");
			assertNull(failure.get());
		} finally {
			applicationLocal.remove();
			release.countDown();
			stop(dispatcher, executor);
		}
	}

	@Test
	void blockedDeliveryRetainsItsSlotAndCannotInterruptTheSuccessor() throws Exception {
		CountDownLatch interruptEntered = new CountDownLatch(1);
		CountDownLatch releaseInterrupt = new CountDownLatch(1);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseWork = new CountDownLatch(1);
		CountDownLatch firstWorkReturned = new CountDownLatch(1);
		CountDownLatch secondRan = new CountDownLatch(1);
		CountDownLatch firstPhysicalExit = new CountDownLatch(1);
		AtomicInteger deliveries = new AtomicInteger();
		AtomicInteger helpers = new AtomicInteger();
		AtomicReference<Thread> firstWorker = new AtomicReference<>();
		AtomicReference<Thread> secondWorker = new AtomicReference<>();
		AtomicBoolean successorInterrupted = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable) {
			@Override public void interrupt() {
				deliveries.incrementAndGet();
				interruptEntered.countDown();
				awaitUninterruptibly(releaseInterrupt);
				super.interrupt();
			}
		});
		ExecutorService callers = Executors.newFixedThreadPool(2);
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(
				1, 1, executor, McpApplicationExecutionObserver.disabledInstance(), () -> {}, runnable -> {
					helpers.incrementAndGet();
					return new Thread(runnable);
				});
		var first = dispatcher.newTicket(() -> {
			firstWorker.set(Thread.currentThread());
			firstEntered.countDown();
			awaitUninterruptibly(releaseWork);
			firstWorkReturned.countDown();
		}, failure::set, ignored -> {}, firstPhysicalExit::countDown);
		var second = dispatcher.newTicket(() -> {
			secondWorker.set(Thread.currentThread());
			successorInterrupted.set(Thread.currentThread().isInterrupted());
			secondRan.countDown();
		}, failure::set);
		try {
			dispatcher.admit(first);
			assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
			assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED, dispatcher.admit(second));
			callers.submit(first::requestInterrupt).get(1, TimeUnit.SECONDS);
			assertTrue(interruptEntered.await(3, TimeUnit.SECONDS));
			for (int index = 0; index < 8; index++)
				callers.submit(first::requestInterrupt).get(1, TimeUnit.SECONDS);
			releaseWork.countDown();
			assertTrue(firstWorkReturned.await(3, TimeUnit.SECONDS));
			assertEquals(1, dispatcher.snapshot().activeSlots());
			assertEquals(1, dispatcher.snapshot().queueDepth());
			assertEquals(1L, firstPhysicalExit.getCount());
			assertEquals(1L, secondRan.getCount());
			assertEquals(McpApplicationHandlerDispatcher.TicketState.DISPATCHED, first.state());
			assertEquals(1, helpers.get(), "Duplicate requests cannot create replacement delivery workers.");
			releaseInterrupt.countDown();
			assertTrue(secondRan.await(3, TimeUnit.SECONDS));
			assertTrue(firstPhysicalExit.await(3, TimeUnit.SECONDS));
			assertSame(firstWorker.get(), secondWorker.get());
			assertFalse(successorInterrupted.get(), "Late delivery must not reach the reused worker.");
			assertEquals(1, deliveries.get());
			assertNull(failure.get());
		} finally {
			releaseWork.countDown();
			releaseInterrupt.countDown();
			stop(dispatcher, executor);
			callers.shutdownNow();
			assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test
	void throwingInterruptStillRetiresDeliveryAndReportsItsFailure() throws Exception {
		RuntimeException expected = new IllegalStateException("Expected interrupt failure.");
		AtomicBoolean firstInterrupt = new AtomicBoolean(true);
		assertDeliveryFailureRetires(runnable -> new Thread(runnable), new ThreadFactory() {
			@Override public Thread newThread(Runnable runnable) {
				return new Thread(runnable) {
					@Override public void interrupt() {
						if (firstInterrupt.compareAndSet(true, false)) throw expected;
						super.interrupt();
					}
				};
			}
		}, McpApplicationExecutionObserver.disabledInstance(), expected);
	}

	@Test
	void failedDeliveryLaunchNeverEscapesOrStrandsRetirement() throws Exception {
		RuntimeException expected = new RejectedExecutionException("Expected delivery launch failure.");
		List<ThreadFactory> factories = List.of(
				runnable -> { throw expected; },
				runnable -> null,
				runnable -> new Thread(runnable) {
					@Override public synchronized void start() { throw expected; }
				});
		for (ThreadFactory factory : factories)
			assertDeliveryFailureRetires(factory, Thread::new,
					McpApplicationExecutionObserver.disabledInstance(), factory == factories.get(1) ? null : expected);
	}

	@Test
	void lifecycleScopeFailuresDoNotLeakDeliveryOwnership() throws Exception {
		for (boolean failOnEntry : List.of(true, false)) {
			RuntimeException expected = new IllegalStateException("Expected lifecycle scope failure.");
			McpApplicationExecutionObserver observer = new NoopObserver() {
				@Override public LifecycleScope enterLifecycleExecution() {
					if (failOnEntry) throw expected;
					return () -> { throw expected; };
				}
			};
			assertDeliveryFailureRetires(Thread::new, Thread::new, observer, expected);
		}
	}

	@Test
	void preEntryInterruptCarriesScopeWithoutHoldingTheTicketMonitor() throws Exception {
		ManualExecutor executor = new ManualExecutor();
		ExecutorService probe = Executors.newSingleThreadExecutor();
		ThreadLocal<Boolean> marked = ThreadLocal.withInitial(() -> false);
		AtomicInteger scopedInterrupts = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicBoolean interruptedAtEntry = new AtomicBoolean();
		McpApplicationExecutionObserver observer = new NoopObserver() {
			@Override public LifecycleScope enterLifecycleExecution() {
				marked.set(true);
				return marked::remove;
			}
		};
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(1, 1, executor, observer);
		var ticket = dispatcher.newTicket(() -> interruptedAtEntry.set(Thread.currentThread().isInterrupted()), failure::set);
		Thread worker = new Thread(() -> executor.command.run()) {
			@Override public void interrupt() {
				assertTrue(marked.get(), "The custom interrupt callback must run in the owner scope.");
				try {
					probe.submit(ticket::requestInterrupt).get(1, TimeUnit.SECONDS);
				} catch (Exception exception) { throw new AssertionError("Ticket monitor held during interruption", exception); }
				scopedInterrupts.incrementAndGet();
				super.interrupt();
			}
		};
		try {
			dispatcher.admit(ticket);
			ticket.requestInterrupt();
			worker.start();
			worker.join(3_000L);
			assertFalse(worker.isAlive());
			assertEquals(1, scopedInterrupts.get());
			assertTrue(interruptedAtEntry.get());
			assertNull(failure.get());
			assertEquals(0, dispatcher.snapshot().activeSlots());
		} finally {
			worker.join(3_000L);
			stop(dispatcher, executor);
			probe.shutdownNow();
			assertTrue(probe.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test
	void completedTicketCannotCreateALateDelivery() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		AtomicInteger helpers = new AtomicInteger();
		CountDownLatch exited = new CountDownLatch(1);
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(1, 1, executor,
				McpApplicationExecutionObserver.disabledInstance(), () -> {}, runnable -> {
					helpers.incrementAndGet();
					return new Thread(runnable);
				});
		var ticket = dispatcher.newTicket(() -> {}, failure -> fail(failure), ignored -> {}, exited::countDown);
		try {
			dispatcher.admit(ticket);
			assertTrue(exited.await(3, TimeUnit.SECONDS));
			ticket.requestInterrupt();
			assertEquals(0, helpers.get());
			assertNull(ticket.handlerThread());
		} finally { stop(dispatcher, executor); }
	}

	private static void assertDeliveryFailureRetires(ThreadFactory deliveryFactory, ThreadFactory handlerFactory,
			McpApplicationExecutionObserver observer, Throwable expected) throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor(handlerFactory);
		ExecutorService callers = Executors.newSingleThreadExecutor();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch exited = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicInteger exits = new AtomicInteger();
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(1, 1, executor, observer,
				() -> {}, deliveryFactory);
		var ticket = dispatcher.newTicket(() -> {
			entered.countDown();
			awaitUninterruptibly(release);
		}, failure::set, ignored -> {}, () -> { exits.incrementAndGet(); exited.countDown(); });
		try {
			dispatcher.admit(ticket);
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			callers.submit(ticket::requestInterrupt).get(1, TimeUnit.SECONDS);
			release.countDown();
			assertTrue(exited.await(3, TimeUnit.SECONDS));
			assertEquals(1, exits.get());
			assertEquals(0, dispatcher.snapshot().activeSlots());
			if (expected == null) assertInstanceOf(NullPointerException.class, failure.get());
			else assertSame(expected, failure.get());
		} finally {
			release.countDown();
			// Avoid invoking a deliberately broken interrupt implementation in
			// executor cleanup: its already-finished worker can exit gracefully.
			dispatcher.stopAcceptingAndReserveCancellation(new IllegalStateException("Test finished")).run();
			executor.shutdown();
			assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
			callers.shutdownNow();
			assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try { assertTrue(latch.await(5, TimeUnit.SECONDS)); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		// These fixtures deliberately let the dispatcher clear status before reuse.
		if (interrupted) Thread.interrupted();
	}

	private static void stop(McpApplicationHandlerDispatcher dispatcher, ExecutorService executor) throws Exception {
		dispatcher.stopAcceptingAndReserveCancellation(new IllegalStateException("Test finished")).run();
		executor.shutdown();
		assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
	}

	private static final class ManualExecutor extends AbstractExecutorService {
		private Runnable command;
		private boolean shutdown;
		@Override public void execute(Runnable command) { this.command = command; }
		@Override public void shutdown() { shutdown = true; }
		@Override public List<Runnable> shutdownNow() { shutdown(); return List.of(); }
		@Override public boolean isShutdown() { return shutdown; }
		@Override public boolean isTerminated() { return shutdown; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
	}

	private static class NoopObserver implements McpApplicationExecutionObserver {
		@Override public void beginDeferral() {}
		@Override public void endDeferral() {}
		@Override public void recordHandlerExecutionStarted() {}
		@Override public void recordHandlerExecutionFinished() {}
		@Override public void recordHandlerQueued() {}
		@Override public void recordHandlerDequeued() {}
		@Override public void recordHandlerCapacityRejected() {}
		@Override public void drain() {}
	}
}
