/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet.internal.mcp.protocol;

import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpDirectPolicyQueuePermitTests {

	@Test
	void queuedDeadlineReturnsAllowanceForNextWaiter() throws Exception {
		assertQueuedExitReleasesPermit(true);
	}

	@Test
	void queuedCancellationReturnsAllowanceForNextWaiter() throws Exception {
		assertQueuedExitReleasesPermit(false);
	}

	private static void assertQueuedExitReleasesPermit(boolean deadline) throws Exception {
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(1, 1,
						Duration.ofSeconds(30), Duration.ofDays(1)),
				() -> 0L, McpApplicationHandlerExecutorFactory.production(), null,
				McpApplicationExecutionObserver.disabledInstance(), 0L, 0L, 4);
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		AtomicInteger firstInvocations = new AtomicInteger();
		AtomicInteger firstPhysicalExits = new AtomicInteger();
		AtomicInteger replacementInvocations = new AtomicInteger();
		AtomicInteger replacementPhysicalExits = new AtomicInteger();
		McpApplicationExecution.BoundedPolicyCancellation firstCancellation =
				execution.newBoundedPolicyCancellation();
		McpApplicationExecution.BoundedPolicyCancellation replacementCancellation =
				execution.newBoundedPolicyCancellation();

		// Occupy the handler through an ordinary policy call, which does not use
		// the sole direct-caller allowance provided by four protocol workers.
		Caller active = new Caller("mcp-direct-policy-active", () ->
				execution.invokeBoundedPolicy(() -> {
					activeEntered.countDown();
					releaseActive.await();
					return "active";
				}, TimeUnit.SECONDS.toNanos(30)));
		Caller first = new Caller("mcp-direct-policy-first", () ->
				execution.invokeBoundedPolicyIfAvailable(() -> {
					firstInvocations.incrementAndGet();
					return "must-not-enter";
				}, TimeUnit.SECONDS.toNanos(deadline ? 5 : 30),
						firstCancellation, firstPhysicalExits::incrementAndGet));
		Caller replacement = new Caller("mcp-direct-policy-replacement", () ->
				execution.invokeBoundedPolicyIfAvailable(() -> {
					replacementInvocations.incrementAndGet();
					return "replacement";
				}, TimeUnit.SECONDS.toNanos(30), replacementCancellation,
						replacementPhysicalExits::incrementAndGet));

		try {
			execution.start();
			active.thread.start();
			assertTrue(activeEntered.await(2, TimeUnit.SECONDS));
			first.thread.start();
			awaitQueued(execution, first);
			if (!deadline)
				firstCancellation.cancel(StreamTerminationReason.CLIENT_CANCELED);
			first.thread.join(6_000L);
			assertFalse(first.thread.isAlive(), "The queued caller must terminate while the handler is still held.");
			if (deadline) {
				McpApplicationPolicyDeadlineException failure = assertInstanceOf(
						McpApplicationPolicyDeadlineException.class, first.failure.get());
				assertTrue(failure.queued(), "Expiration must remove a queued ticket, without entering it.");
			} else {
				assertInstanceOf(McpApplicationPolicyCanceledException.class, first.failure.get());
				assertEquals(StreamTerminationReason.CLIENT_CANCELED,
						firstCancellation.reason().orElseThrow());
			}
			assertEquals(0, firstInvocations.get());
			assertEquals(1, firstPhysicalExits.get());
			assertEquals(0, execution.snapshot().queuedRequests());

			// The active callback remains held. A leaked allowance would reject this
			// replacement immediately, even though the handler queue has room again.
			replacement.thread.start();
			awaitQueued(execution, replacement);
			assertEquals(0, replacementInvocations.get());
			releaseActive.countDown();
			replacement.thread.join(2_000L);
			active.thread.join(2_000L);
			assertFalse(replacement.thread.isAlive());
			assertFalse(active.thread.isAlive());
			assertNull(replacement.failure.get());
			assertNull(active.failure.get());
			assertEquals("replacement", replacement.result.get());
			assertEquals("active", active.result.get());
		} finally {
			releaseActive.countDown();
			firstCancellation.cancel(StreamTerminationReason.APPLICATION_CANCELED);
			replacementCancellation.cancel(StreamTerminationReason.APPLICATION_CANCELED);
			execution.stop();
			first.thread.join(2_000L);
			replacement.thread.join(2_000L);
			active.thread.join(2_000L);
			boolean terminated = execution.awaitTermination(Duration.ofSeconds(5));
			assertAll(
					() -> assertFalse(first.thread.isAlive()),
					() -> assertFalse(replacement.thread.isAlive()),
					() -> assertFalse(active.thread.isAlive()),
					() -> assertTrue(terminated));
		}
		assertEquals(1, replacementInvocations.get());
		assertEquals(1, replacementPhysicalExits.get());
		assertEquals(0, execution.snapshot().queuedRequests());
		assertEquals(0, execution.snapshot().activeHandlerSlots());
	}

	private static void awaitQueued(McpApplicationExecution execution, Caller caller)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (execution.snapshot().queuedRequests() == 0 && caller.thread.isAlive()
				&& System.nanoTime() - deadline < 0L)
			Thread.sleep(1L);
		assertEquals(1, execution.snapshot().queuedRequests(),
				() -> "A direct policy caller must be admitted to the handler queue; failure="
						+ caller.failure.get());
	}

	private static final class Caller {
		private final AtomicReference<String> result = new AtomicReference<>();
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private final Thread thread;

		private Caller(String name, Callable<String> operation) {
			this.thread = new Thread(() -> {
				try {
					result.set(operation.call());
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}, name);
		}
	}
}
