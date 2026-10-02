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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class McpLegacySessionOwnerPolicyTests {
	@Test
	void owner_policy_does_not_reinvoke_the_user_executor_supplier_or_change_handler_counts() throws Exception {
		AtomicInteger factories = new AtomicInteger();
		McpApplicationExecution execution = new McpApplicationExecution(configuration(1, 1), McpApplicationClock.SYSTEM,
				concurrency -> {
					factories.incrementAndGet();
					return McpApplicationHandlerExecutorFactory.production().create(concurrency);
				});
		try {
			execution.start();
			assertEquals("fresh-owner", execution.invokeBoundedSessionOwnerPolicy(() -> "fresh-owner", deadline()));
			await(() -> execution.sessionOwnerPolicySnapshot().activeSlots() == 0);
			assertEquals(1, factories.get());
			assertEquals(0, execution.snapshot().activeHandlerSlots());
			assertEquals(0, execution.snapshot().maximumObservedActiveHandlerSlots());
			assertEquals(1, execution.sessionOwnerPolicySnapshot().maximumObservedActiveSlots());
		} finally {
			execution.stop(); assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	void owner_worker_and_queue_bounds_survive_stop_and_keep_ignoring_callbacks_physically_owned() throws Exception {
		McpApplicationExecution execution = new McpApplicationExecution(configuration(32, 2), McpApplicationClock.SYSTEM);
		CountDownLatch entered = new CountDownLatch(4);
		CountDownLatch release = new CountDownLatch(1);
		List<Thread> callers = new ArrayList<>();
		List<AtomicReference<Throwable>> failures = new ArrayList<>();
		AtomicInteger callbacks = new AtomicInteger();
		try {
			execution.start();
			for (int index = 0; index < 6; index++) {
				AtomicReference<Throwable> failure = new AtomicReference<>(); failures.add(failure);
				Thread caller = new Thread(() -> {
					try {
						execution.invokeBoundedSessionOwnerPolicy(() -> {
							callbacks.incrementAndGet(); entered.countDown();
							awaitUninterruptibly(release);
							return "ignored-interruption";
						}, deadline());
					} catch (Throwable throwable) { failure.set(throwable); }
				}, "mcp-session-owner-saturation-caller-" + index);
				callers.add(caller); caller.start();
			}
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			await(() -> execution.sessionOwnerPolicySnapshot().queueDepth() == 2);
			assertEquals(4, execution.sessionOwnerPolicySnapshot().concurrency());
			assertEquals(4, execution.sessionOwnerPolicySnapshot().activeSlots());
			assertEquals(0, execution.snapshot().activeHandlerSlots());
			assertEquals(0, execution.snapshot().queuedRequests());
			assertThrows(McpApplicationPolicyCapacityException.class,
					() -> execution.invokeBoundedSessionOwnerPolicy(() -> "must-not-enter", deadline()));
			execution.stop();
			for (Thread caller : callers) { caller.join(TimeUnit.SECONDS.toMillis(5)); assertFalse(caller.isAlive()); }
			for (AtomicReference<Throwable> failure : failures) assertInstanceOf(McpApplicationExecutionStoppedException.class, failure.get());
			assertEquals(4, callbacks.get(), "Queued authorization callbacks must never enter after stop.");
			assertEquals(4, execution.sessionOwnerPolicySnapshot().activeSlots());
			assertEquals(0, execution.sessionOwnerPolicySnapshot().queueDepth());
			assertFalse(execution.isTerminated());
			assertFalse(execution.awaitTermination(Duration.ofMillis(10)));
			release.countDown();
			assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
			assertEquals(0, execution.sessionOwnerPolicySnapshot().activeSlots());
		} finally {
			release.countDown(); execution.stop();
			for (Thread caller : callers) { caller.interrupt(); caller.join(TimeUnit.SECONDS.toMillis(5)); }
			assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	void graceful_drain_preserves_accepted_owner_work_and_waits_for_its_physical_exit() throws Exception {
		McpApplicationExecution execution = new McpApplicationExecution(configuration(1, 1), McpApplicationClock.SYSTEM);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<String> first = new AtomicReference<>();
		AtomicReference<String> second = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread firstCaller = new Thread(() -> {
			try {
				first.set(execution.invokeBoundedSessionOwnerPolicy(() -> {
					entered.countDown(); awaitUninterruptibly(release); return "first";
				}, deadline()));
			} catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
		}, "mcp-session-owner-graceful-active");
		Thread secondCaller = new Thread(() -> {
			try { second.set(execution.invokeBoundedSessionOwnerPolicy(() -> "second", deadline())); }
			catch (Throwable throwable) { failure.compareAndSet(null, throwable); }
		}, "mcp-session-owner-graceful-queued");
		try {
			execution.start(); firstCaller.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); secondCaller.start();
			await(() -> execution.sessionOwnerPolicySnapshot().queueDepth() == 1);
			execution.beginGracefulDrain();
			assertFalse(execution.sessionOwnerPolicySnapshot().accepting());
			assertFalse(execution.isTerminated());
			assertThrows(McpApplicationPolicyCapacityException.class,
					() -> execution.invokeBoundedSessionOwnerPolicy(() -> "not-accepted", deadline()));
			release.countDown();
			firstCaller.join(TimeUnit.SECONDS.toMillis(5)); secondCaller.join(TimeUnit.SECONDS.toMillis(5));
			assertFalse(firstCaller.isAlive()); assertFalse(secondCaller.isAlive());
			assertNull(failure.get()); assertEquals("first", first.get()); assertEquals("second", second.get());
			assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		} finally {
			release.countDown(); execution.stop(); firstCaller.interrupt(); secondCaller.interrupt();
			firstCaller.join(TimeUnit.SECONDS.toMillis(5)); secondCaller.join(TimeUnit.SECONDS.toMillis(5));
			assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	private static McpApplicationExecutionConfiguration configuration(int workers, int queue) {
		return new McpApplicationExecutionConfiguration(workers, queue, Duration.ofSeconds(20), Duration.ofDays(1));
	}
	private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(20); }
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L) Thread.sleep(5);
		assertTrue(condition.getAsBoolean(), "The bounded owner-policy state did not settle.");
	}
	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Missing test release.");
					return;
				} catch (InterruptedException ignored) { interrupted = true; }
			}
		} finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
}
