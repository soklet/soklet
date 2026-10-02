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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** An accepted ticket suppressed before entry has no later worker to release its evidence. */
@Timeout(20)
class McpApplicationHandlerDispatcherSuppressedExitTests {
	@Test
	void direct_cancel_and_plain_stop_release_suppressed_tickets_once_outside_the_dispatcher_lock() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(1, 1, executor);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger canceledExits = new AtomicInteger();
		AtomicInteger stoppedExits = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket active = dispatcher.newTicket(() -> {
			entered.countDown(); release.await();
		}, failure::set);
		McpApplicationHandlerDispatcher.Ticket canceled = dispatcher.newTicket(
				() -> fail("Canceled queued work entered."), failure::set, ignored -> {},
				() -> verifyUnlockedPhysicalExit(dispatcher, canceledExits, failure));
		McpApplicationHandlerDispatcher.Ticket stopped = dispatcher.newTicket(
				() -> fail("Stopped queued work entered."), failure::set, ignored -> {},
				() -> verifyUnlockedPhysicalExit(dispatcher, stoppedExits, failure));
		try {
			assertEquals(McpApplicationHandlerDispatcher.Admission.DISPATCHED, dispatcher.admit(active));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED, dispatcher.admit(canceled));
			assertTrue(dispatcher.cancelBeforeDispatch(canceled));
			assertFalse(dispatcher.cancelBeforeDispatch(canceled));
			assertEquals(1, canceledExits.get());
			assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED, dispatcher.admit(stopped));
			assertEquals(List.of(stopped), dispatcher.stopAccepting());
			assertEquals(List.of(), dispatcher.stopAccepting());
			assertFalse(dispatcher.cancelBeforeDispatch(stopped));
			assertEquals(1, stoppedExits.get());
			assertEquals(1, dispatcher.snapshot().activeSlots());
			assertEquals(0, dispatcher.snapshot().queueDepth());
			assertNull(failure.get());
		} finally {
			release.countDown(); dispatcher.stopAccepting(); executor.shutdown();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		assertEquals(1, canceledExits.get()); assertEquals(1, stoppedExits.get());
	}

	@Test
	void reserved_stop_delivers_suppressed_physical_exit_after_cancellation_and_only_when_action_runs() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		McpApplicationHandlerDispatcher dispatcher = new McpApplicationHandlerDispatcher(1, 1, executor);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger cancellations = new AtomicInteger();
		AtomicInteger exits = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		McpApplicationHandlerDispatcher.Ticket active = dispatcher.newTicket(() -> {
			entered.countDown(); release.await();
		}, ignored -> {});
		McpApplicationHandlerDispatcher.Ticket queued = dispatcher.newTicket(
				() -> fail("Stopped queued work entered."), failure::set,
				ignored -> cancellations.incrementAndGet(), () -> {
					if (cancellations.get() != 1) failure.set(new AssertionError("Physical exit overtook cancellation delivery."));
					verifyUnlockedPhysicalExit(dispatcher, exits, failure);
				});
		try {
			assertEquals(McpApplicationHandlerDispatcher.Admission.DISPATCHED, dispatcher.admit(active));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			assertEquals(McpApplicationHandlerDispatcher.Admission.QUEUED, dispatcher.admit(queued));
			Runnable stop = dispatcher.stopAcceptingAndReserveCancellation(new IllegalStateException("test stop"));
			assertEquals(0, cancellations.get()); assertEquals(0, exits.get());
			assertEquals(0, dispatcher.snapshot().queueDepth());
			stop.run(); stop.run();
			assertEquals(1, cancellations.get()); assertEquals(1, exits.get());
			assertFalse(dispatcher.cancelBeforeDispatch(queued));
			assertNull(failure.get());
		} finally {
			release.countDown(); dispatcher.stopAccepting(); executor.shutdown();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		assertEquals(1, exits.get());
	}

	private static void verifyUnlockedPhysicalExit(McpApplicationHandlerDispatcher dispatcher,
			AtomicInteger exits, AtomicReference<Throwable> failure) {
		exits.incrementAndGet();
		Thread probe = new Thread(() -> dispatcher.snapshot(), "mcp-suppressed-exit-lock-probe");
		probe.start();
		try {
			probe.join(1000);
			if (probe.isAlive()) failure.set(new AssertionError("Physical-exit observer ran under dispatcher lock."));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt(); failure.set(exception);
		}
	}
}
