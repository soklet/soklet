package com.soklet.internal.mcp.protocol;

import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpPolicyCancellationShutdownRaceTests {

	@Test
	void shutdownPreservesAnElectedPolicyCancellationUntilItsSidecarIsSubmitted() throws Exception {
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(1, 1, Duration.ofSeconds(4), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM);
		McpApplicationExecution.BoundedPolicyCancellation cancellation = execution.newBoundedPolicyCancellation();
		CountDownLatch policyEntered = new CountDownLatch(1);
		CountDownLatch releasePolicy = new CountDownLatch(1);
		CountDownLatch cancellationElected = new CountDownLatch(1);
		CountDownLatch releaseCancellation = new CountDownLatch(1);
		CountDownLatch callbackDelivered = new CountDownLatch(1);
		AtomicInteger callbacks = new AtomicInteger();
		AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();
		AtomicReference<Throwable> gateFailure = new AtomicReference<>();
		cancellation.onCancel(() -> {
			callbacks.incrementAndGet();
			callbackDelivered.countDown();
		});
		Thread policyCaller = new Thread(() -> {
			try {
				execution.invokeBoundedPolicy(() -> {
					policyEntered.countDown();
					assertTrue(releasePolicy.await(4, TimeUnit.SECONDS));
					return Boolean.TRUE;
				}, System.nanoTime() + TimeUnit.SECONDS.toNanos(4), cancellation);
			} catch (Exception expectedCancellation) {
				// Shutdown may interrupt or cancel the bounded policy invocation.
			}
		}, "policy-cancellation-race-caller");
		Thread canceler = new Thread(() -> {
			try {
				cancellation.cancel(StreamTerminationReason.CLIENT_DISCONNECTED);
			} catch (Throwable failure) {
				cancellationFailure.set(failure);
			}
		}, "policy-cancellation-race-canceler");
		try {
			execution.start();
			policyCaller.start();
			assertTrue(policyEntered.await(2, TimeUnit.SECONDS), "Policy did not enter");
			// Pause after the delivery election but before interruption/submission.
			// This exposes shutdown ordering without relying on scheduler timing.
			Field targetField = cancellation.getClass().getDeclaredField("cancellationTarget");
			targetField.setAccessible(true);
			BooleanSupplier originalTarget = (BooleanSupplier) targetField.get(cancellation);
			targetField.set(cancellation, (BooleanSupplier) () -> {
				cancellationElected.countDown();
				try {
					assertTrue(releaseCancellation.await(2, TimeUnit.SECONDS), "Cancellation target was not released");
				} catch (Throwable failure) {
					gateFailure.set(failure);
					throw new IllegalStateException(failure);
				}
				return originalTarget.getAsBoolean();
			});
			canceler.start();
			assertTrue(cancellationElected.await(2, TimeUnit.SECONDS), "Cancellation did not win its election");
			// Neither a duplicate caller nor stop may retire the first caller's reservation.
			cancellation.cancel(StreamTerminationReason.SERVER_STOPPING);
			execution.stop(StreamTerminationReason.SERVER_STOPPING);
			assertEquals(0, callbacks.get());
			releaseCancellation.countDown();
			assertTrue(callbackDelivered.await(2, TimeUnit.SECONDS),
					() -> "Elected cancellation was lost: " + cancellationFailure.get());
			canceler.join(2_000);
			policyCaller.join(2_000);
			assertFalse(canceler.isAlive());
			assertFalse(policyCaller.isAlive());
			assertNull(gateFailure.get());
			assertNull(cancellationFailure.get());
			assertEquals(1, callbacks.get());
			assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, cancellation.reason().orElseThrow());
			assertTrue(execution.awaitTermination(Duration.ofSeconds(2)), "Canceled policy did not physically drain");
		} finally {
			releaseCancellation.countDown();
			releasePolicy.countDown();
			execution.stop();
			canceler.join(2_000);
			policyCaller.join(2_000);
		}
	}
}
