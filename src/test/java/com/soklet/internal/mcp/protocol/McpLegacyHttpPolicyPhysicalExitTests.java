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

import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Physical HTTP policy ownership also ends exactly once when invocation is suppressed. */
@Timeout(30)
class McpLegacyHttpPolicyPhysicalExitTests {

	@Test
	void expired_and_closed_admission_report_one_physical_exit_without_entering_application_code() throws Exception {
		for (boolean ownerPolicy : new boolean[]{false, true}) {
			McpApplicationExecution execution = execution();
			AtomicInteger invocations = new AtomicInteger();
			AtomicInteger exits = new AtomicInteger();
			try {
				execution.start();
				assertThrows(McpApplicationPolicyDeadlineException.class,
						() -> invoke(execution, ownerPolicy, () -> { invocations.incrementAndGet(); return "expired"; },
								System.nanoTime() - 1L, exits::incrementAndGet));
				assertEquals(1, exits.get());
				assertEquals(0, invocations.get());
				execution.stop();
				assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
				assertThrows(McpApplicationPolicyCapacityException.class,
						() -> invoke(execution, ownerPolicy, () -> { invocations.incrementAndGet(); return "closed"; },
								deadline(), exits::incrementAndGet));
				assertEquals(2, exits.get());
				assertEquals(0, invocations.get());
			} finally {
				execution.stop();
				assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
			}
		}
	}

	@Test
	void rejected_and_queued_canceled_work_release_once_but_running_work_keeps_its_control_budget() throws Exception {
		for (boolean ownerPolicy : new boolean[]{false, true}) {
			McpApplicationExecution execution = execution();
			McpLegacyHttpControlBudget budget = new McpLegacyHttpControlBudget(McpApplicationClock.SYSTEM);
			McpEffectivePartition partition = new McpEffectivePartition(new McpEndpointPartitionIdentity("/mcp"),
					McpPartitionPurpose.AUTHORIZATION, Optional.of("shared"));
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger activeExits = new AtomicInteger();
			AtomicInteger queuedExits = new AtomicInteger();
			AtomicInteger rejectedExits = new AtomicInteger();
			AtomicInteger queuedInvocations = new AtomicInteger();
			AtomicReference<Throwable> activeFailure = new AtomicReference<>();
			AtomicReference<Throwable> queuedFailure = new AtomicReference<>();
			Thread active = new Thread(() -> {
				try {
					invoke(execution, ownerPolicy, () -> {
						entered.countDown(); awaitRelease(release); return "physically-released";
					}, deadline(), () -> { activeExits.incrementAndGet(); budget.release(partition); });
				} catch (Throwable failure) { activeFailure.set(failure); }
			}, "mcp-legacy-http-physical-active");
			Thread queued = new Thread(() -> {
				try {
					invoke(execution, ownerPolicy, () -> {
						queuedInvocations.incrementAndGet(); return "must-not-enter";
					}, deadline(), queuedExits::incrementAndGet);
				} catch (Throwable failure) { queuedFailure.set(failure); }
			}, "mcp-legacy-http-physical-queued");
			try {
				execution.start();
				assertTrue(budget.reserve(partition));
				active.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
				queued.start();
				await(() -> queueDepth(execution, ownerPolicy) == 1);
				assertThrows(McpApplicationPolicyCapacityException.class,
						() -> invoke(execution, ownerPolicy, () -> "rejected", deadline(), rejectedExits::incrementAndGet));
				assertEquals(1, rejectedExits.get());
				execution.stop();
				active.join(5000); queued.join(5000);
				assertFalse(active.isAlive()); assertFalse(queued.isAlive());
				assertInstanceOf(McpApplicationExecutionStoppedException.class, activeFailure.get());
				assertInstanceOf(McpApplicationExecutionStoppedException.class, queuedFailure.get());
				assertEquals(0, queuedInvocations.get());
				await(() -> queuedExits.get() == 1);
				assertEquals(1, queuedExits.get());
				assertEquals(0, activeExits.get());
				assertEquals(1, budget.snapshot().activeReservations(),
						"Logical cancellation cannot release an ignoring callback's physical control reservation.");
				assertFalse(execution.awaitTermination(Duration.ofMillis(10)));
				release.countDown();
				assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
				assertEquals(1, activeExits.get());
				assertEquals(0, budget.snapshot().activeReservations());
				assertEquals(1, queuedExits.get()); assertEquals(1, rejectedExits.get());
			} finally {
				release.countDown(); execution.stop(); active.interrupt(); queued.interrupt();
				active.join(5000); queued.join(5000);
				assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
			}
		}
	}

	@Test
	void cancellation_before_admission_reports_one_physical_exit_and_never_enters_callback() throws Exception {
		McpApplicationExecution execution = execution();
		AtomicInteger exits = new AtomicInteger();
		AtomicInteger invocations = new AtomicInteger();
		try {
			execution.start();
			McpApplicationExecution.BoundedPolicyCancellation cancellation = execution.newBoundedPolicyCancellation();
			cancellation.cancel(StreamTerminationReason.APPLICATION_CANCELED);
			assertThrows(McpApplicationPolicyCanceledException.class,
					() -> execution.invokeBoundedPolicy(() -> {
						invocations.incrementAndGet(); return "must-not-enter";
					}, deadline(), cancellation, exits::incrementAndGet));
			assertEquals(0, invocations.get());
			assertEquals(1, exits.get());
			assertEquals(0, execution.snapshot().activeHandlerSlots());
			assertEquals(0, execution.snapshot().queuedRequests());
		} finally {
			execution.stop(); assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	private static McpApplicationExecution execution() {
		return new McpApplicationExecution(new McpApplicationExecutionConfiguration(
				1, 1, Duration.ofSeconds(20), Duration.ofDays(1)), McpApplicationClock.SYSTEM);
	}
	private static String invoke(McpApplicationExecution execution, boolean ownerPolicy, Callable<String> callback,
			long deadlineNanos, Runnable observer) throws Exception {
		return ownerPolicy ? execution.invokeBoundedSessionOwnerPolicy(callback, deadlineNanos, observer)
				: execution.invokeBoundedPolicy(callback, deadlineNanos, execution.newBoundedPolicyCancellation(), observer);
	}
	private static int queueDepth(McpApplicationExecution execution, boolean ownerPolicy) {
		return ownerPolicy ? execution.sessionOwnerPolicySnapshot().queueDepth() : execution.snapshot().queuedRequests();
	}
	private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(20); }
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L) Thread.sleep(5);
		assertTrue(condition.getAsBoolean(), "Bounded policy state did not settle.");
	}
	private static void awaitRelease(CountDownLatch latch) {
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
