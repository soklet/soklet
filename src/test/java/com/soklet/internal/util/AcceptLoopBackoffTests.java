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

package com.soklet.internal.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tests for the shared accept-loop retry policy used by the HTTP, SSE, and MCP servers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class AcceptLoopBackoffTests {
	@Test
	public void backoffScheduleDoublesToCeiling() {
		// Escalation schedule: doubles per consecutive failure, capped at 1s.
		Assertions.assertEquals(50L, AcceptLoopBackoff.backoffMillis(1));
		Assertions.assertEquals(100L, AcceptLoopBackoff.backoffMillis(2));
		Assertions.assertEquals(200L, AcceptLoopBackoff.backoffMillis(3));
		Assertions.assertEquals(400L, AcceptLoopBackoff.backoffMillis(4));
		Assertions.assertEquals(800L, AcceptLoopBackoff.backoffMillis(5));
		Assertions.assertEquals(1000L, AcceptLoopBackoff.backoffMillis(6));
		Assertions.assertEquals(1000L, AcceptLoopBackoff.backoffMillis(7));
		Assertions.assertEquals(1000L, AcceptLoopBackoff.backoffMillis(1_000));

		// Degenerate inputs stay at the base delay rather than misbehaving.
		Assertions.assertEquals(50L, AcceptLoopBackoff.backoffMillis(0));
		Assertions.assertEquals(50L, AcceptLoopBackoff.backoffMillis(-1));
	}

	@Test
	public void failureLoggingCoalescesToPowerOfTwoMilestones() {
		Assertions.assertTrue(AcceptLoopBackoff.shouldLogFailure(1));
		Assertions.assertTrue(AcceptLoopBackoff.shouldLogFailure(2));
		Assertions.assertTrue(AcceptLoopBackoff.shouldLogFailure(4));
		Assertions.assertTrue(AcceptLoopBackoff.shouldLogFailure(8));
		Assertions.assertFalse(AcceptLoopBackoff.shouldLogFailure(3));
		Assertions.assertFalse(AcceptLoopBackoff.shouldLogFailure(5));
		Assertions.assertFalse(AcceptLoopBackoff.shouldLogFailure(6));
		Assertions.assertFalse(AcceptLoopBackoff.shouldLogFailure(7));
		Assertions.assertFalse(AcceptLoopBackoff.shouldLogFailure(0));
		Assertions.assertFalse(AcceptLoopBackoff.shouldLogFailure(-1));
	}

	@Test
	public void sleepReturnsImmediatelyWhenAlreadyStopped() {
		long startNanos = System.nanoTime();
		boolean interrupted = AcceptLoopBackoff.sleepBeforeRetry(10_000L, () -> true);
		long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

		Assertions.assertFalse(interrupted);
		// A requested 10s sleep should not even begin when the stop flag is already set.
		Assertions.assertTrue(elapsedMillis < 1_000L, "Expected immediate return, took " + elapsedMillis + "ms");
	}

	@Test
	public void sleepEndsEarlyWhenStopFlagIsSetMidSleep() {
		// The stop flag flips after the first check, so the sleep should end after roughly one
		// 50ms increment instead of the requested 10s.
		AtomicLong stopChecks = new AtomicLong();
		long startNanos = System.nanoTime();
		boolean interrupted = AcceptLoopBackoff.sleepBeforeRetry(10_000L, () -> stopChecks.incrementAndGet() > 1);
		long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

		Assertions.assertFalse(interrupted);
		Assertions.assertTrue(elapsedMillis < 1_000L, "Expected early exit, took " + elapsedMillis + "ms");
	}

	@Test
	public void sleepCompletesFullDurationWhenNeverStopped() {
		long startNanos = System.nanoTime();
		boolean interrupted = AcceptLoopBackoff.sleepBeforeRetry(120L, () -> false);
		long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

		Assertions.assertFalse(interrupted);
		// Thread.sleep guarantees at-least semantics; allow a little scheduler slop below the target.
		Assertions.assertTrue(elapsedMillis >= 100L, "Expected a full sleep, took " + elapsedMillis + "ms");
	}

	@Test
	public void sleepReportsInterruptionAndRestoresInterruptStatus() throws Exception {
		AtomicBoolean sawInterrupted = new AtomicBoolean();
		AtomicBoolean interruptStatusRestored = new AtomicBoolean();

		Thread sleeper = new Thread(() -> {
			sawInterrupted.set(AcceptLoopBackoff.sleepBeforeRetry(10_000L, () -> false));
			interruptStatusRestored.set(Thread.currentThread().isInterrupted());
		});

		sleeper.start();
		// Give the sleeper a moment to enter its sleep before interrupting.
		Thread.sleep(100L);
		sleeper.interrupt();
		sleeper.join(5_000L);

		Assertions.assertFalse(sleeper.isAlive(), "Sleeper should have exited after interrupt");
		Assertions.assertTrue(sawInterrupted.get(), "Interruption should be reported to the caller");
		Assertions.assertTrue(interruptStatusRestored.get(), "Interrupt status should be restored");
	}
}
