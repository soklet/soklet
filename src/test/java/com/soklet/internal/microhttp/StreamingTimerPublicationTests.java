/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.microhttp;

import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises timer publication against real streaming-source cancelation without socket or clock timing. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingTimerPublicationTests {
	@AfterEach
	public void resetHooks() {
		StreamingMicrohttpResponses.setTestHooks(null);
	}

	@Test
	public void closeAfterStartCheckPreventsDeadlineTimerCreation() throws Exception {
		CountDownLatch beforeSchedule = new CountDownLatch(1);
		CountDownLatch resumeStart = new CountDownLatch(1);
		StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
			@Override public void beforeResponseTimeoutScheduled() {
				beforeSchedule.countDown();
				awaitUnchecked(resumeStart);
			}
		});
		try (Fixture fixture = new Fixture(Instant.now().plusSeconds(3_600), Duration.ofSeconds(1))) {
			Future<?> starting = fixture.actions.submit(() -> {
				fixture.source.start();
				return null;
			});
			try {
				await(beforeSchedule);
				fixture.closeBody();
				fixture.awaitRetirement();
			} finally {
				resumeStart.countDown();
			}
			starting.get(3, TimeUnit.SECONDS);
			Assertions.assertTrue(fixture.timeouts.tasks.isEmpty(),
					"A retired source must not publish either timer after start resumes");
			Assertions.assertEquals(0, fixture.producerCalls.get());
		}
	}

	@Test
	public void reentrantCloseCancelsDeadlineFutureReturnedAfterStop() throws Exception {
		try (Fixture fixture = new Fixture(Instant.now().plusSeconds(3_600), Duration.ofSeconds(1))) {
			fixture.timeouts.beforeScheduleReturns = fixture::closeBody;
			fixture.source.start();
			fixture.awaitRetirement();
			Assertions.assertEquals(1, fixture.timeouts.tasks.size());
			Assertions.assertTrue(fixture.timeouts.tasks.get(0).isCancelled(),
					"A future returned after reentrant close must be canceled instead of retained");
			Assertions.assertEquals(0, fixture.producerCalls.get());
		}
	}

	@Test
	public void alreadyRunningIdleCallbackCannotRearmAfterClose() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
			@Override public long nanoTime() { return clock.get(); }
		});
		CountDownLatch callbackStarted = new CountDownLatch(1);
		CountDownLatch resumeCallback = new CountDownLatch(1);
		try (Fixture fixture = new Fixture(null, Duration.ofSeconds(1))) {
			fixture.source.start();
			await(fixture.producerStarted);
			fixture.timeouts.beforeCallback = () -> {
				callbackStarted.countDown();
				awaitUnchecked(resumeCallback);
			};
			ManualFuture idleCheck = fixture.timeouts.tasks.get(0);
			Future<?> checking = fixture.actions.submit(idleCheck);
			try {
				await(callbackStarted);
				fixture.closeBody();
				fixture.awaitRetirement();
				Assertions.assertTrue(idleCheck.isCancelled());
			} finally {
				resumeCallback.countDown();
			}
			checking.get(3, TimeUnit.SECONDS);
			Assertions.assertEquals(1, fixture.timeouts.tasks.size(),
					"The already-running callback must observe stop before scheduling a successor");
		}
	}

	@Test
	public void reentrantCloseCancelsUnpublishedIdleSuccessor() throws Exception {
		AtomicLong clock = new AtomicLong(100L);
		StreamingMicrohttpResponses.setTestHooks(new StreamingMicrohttpResponses.TestHooks() {
			@Override public long nanoTime() { return clock.get(); }
		});
		try (Fixture fixture = new Fixture(null, Duration.ofSeconds(1))) {
			fixture.source.start();
			await(fixture.producerStarted);
			clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(750));
			fixture.timeouts.beforeScheduleReturns = fixture::closeBody;
			fixture.timeouts.tasks.get(0).run();
			fixture.awaitRetirement();
			Assertions.assertEquals(2, fixture.timeouts.tasks.size());
			Assertions.assertTrue(fixture.timeouts.tasks.stream().allMatch(ManualFuture::isCancelled),
					"Both the running timer and its not-yet-published successor must be canceled");
		}
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Controlled timer step did not occur");
	}

	private static void awaitUnchecked(CountDownLatch latch) {
		try {
			await(latch);
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Controlled timer step was interrupted", interruptedException);
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final ExecutorService producers = Executors.newSingleThreadExecutor();
		private final ExecutorService actions = Executors.newSingleThreadExecutor();
		private final ControlledTimeouts timeouts = new ControlledTimeouts();
		private final StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(
				1, 1, Duration.ofSeconds(30), ignored -> {});
		private final AtomicInteger producerCalls = new AtomicInteger();
		private final CountDownLatch producerStarted = new CountDownLatch(1);
		private final CountDownLatch releaseProducer = new CountDownLatch(1);
		private final CountDownLatch terminated = new CountDownLatch(1);
		private final AtomicInteger terminationCalls = new AtomicInteger();
		private final AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		private final MicrohttpResponse response;
		private final WritableSource source;

		private Fixture(Instant deadline, Duration idleTimeout) throws IOException {
			StreamLifecycleCoordinator.Reservation reservation = this.coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			this.response = StreamingMicrohttpResponses.withStreamingBody(200, "OK", List.of(),
					Request.withPath(HttpMethod.GET, "/timer-publication").build(),
					StreamingResponseBody.fromWriter(responseStream -> {
						this.producerCalls.incrementAndGet();
						this.producerStarted.countDown();
						this.releaseProducer.await();
					}), this.producers, this.timeouts, 4, 2, deadline, idleTimeout, () -> false,
					(established, elapsed, terminationReason, cause) -> {
						this.reason.set(terminationReason);
						this.terminationCalls.incrementAndGet();
						this.terminated.countDown();
					}, ignored -> {}, reservation);
			this.source = this.response.writableSource(this.response.serializeHead("HTTP/1.1", List.of()));
		}

		private void closeBody() {
			try {
				// The reserved body is shared; avoid concurrently modifying the composite source's iterator.
				this.response.closeBody(StreamTerminationReason.CLIENT_DISCONNECTED, null);
			} catch (IOException ioException) {
				throw new UncheckedIOException(ioException);
			}
		}

		private void awaitRetirement() throws InterruptedException {
			await(this.terminated);
			this.coordinator.stopAdmission();
			Assertions.assertTrue(this.coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			Assertions.assertEquals(0, this.coordinator.snapshot().reservations());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, this.reason.get());
			Assertions.assertEquals(1, this.terminationCalls.get());
		}

		@Override public void close() throws Exception {
			try {
				closeBody();
				this.releaseProducer.countDown();
				this.coordinator.force();
			} finally {
				this.producers.shutdownNow();
				this.actions.shutdownNow();
				this.timeouts.shutdownNow();
				Assertions.assertTrue(this.producers.awaitTermination(3, TimeUnit.SECONDS));
				Assertions.assertTrue(this.actions.awaitTermination(3, TimeUnit.SECONDS));
				Assertions.assertTrue(this.coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			}
		}
	}

	private static final class ControlledTimeouts extends ScheduledThreadPoolExecutor {
		private final List<ManualFuture> tasks = new CopyOnWriteArrayList<>();
		private volatile Runnable beforeScheduleReturns = () -> {};
		private volatile Runnable beforeCallback = () -> {};

		private ControlledTimeouts() { super(1); }

		@Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			ManualFuture future = new ManualFuture(() -> {
				this.beforeCallback.run();
				command.run();
			});
			this.tasks.add(future);
			this.beforeScheduleReturns.run();
			return future;
		}
	}

	private static final class ManualFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
		private ManualFuture(Runnable command) { super(command, null); }
		@Override public long getDelay(TimeUnit unit) { return 0; }
		@Override public int compareTo(Delayed other) { return 0; }
	}
}
