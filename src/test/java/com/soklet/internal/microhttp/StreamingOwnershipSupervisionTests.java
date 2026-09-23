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
import com.soklet.StreamingResponseCanceledException;
import com.soklet.StreamingResponseWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the real producer adapter and reservation with controlled physical work and clock. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingOwnershipSupervisionTests {
	@Test
	public void blockedOwnCloseExpiresWithoutMovingDuplicatingOrRetiringIt() throws Exception {
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch releaseClose = new CountDownLatch(1);
		AtomicReference<Thread> owner = new AtomicReference<>();
		AtomicReference<Thread> closeThread = new AtomicReference<>();
		AtomicInteger closes = new AtomicInteger();
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.start(responseStream -> {
					owner.set(Thread.currentThread());
					responseStream.own((AutoCloseable) () -> {
						closeThread.set(Thread.currentThread());
						closes.incrementAndGet();
						closeEntered.countDown();
						awaitUninterruptibly(releaseClose);
					});
				});
				await(closeEntered);
				long deadline = fixture.reservation.cleanupDeadlineNanos();
				Assertions.assertNotEquals(0L, deadline, "Root cleanup must start its supervision budget");
				fixture.clock.set(deadline - 1L);
				fixture.reservation.checkCleanupDeadline(fixture.clock.get());
				Assertions.assertFalse(fixture.reservation.isCanceled());
				fixture.clock.set(deadline);
				fixture.reservation.checkCleanupDeadline(fixture.clock.get());
				await(fixture.terminated);
				Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT, fixture.reason.get());
				Assertions.assertNull(fixture.cause.get());
				Assertions.assertSame(owner.get(), closeThread.get());
				Assertions.assertEquals(1, closes.get());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().runningProducers());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().overdue());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().reservations());
				Assertions.assertNull(fixture.coordinator.tryReserve(), "A timed-out close still consumes admission");
				fixture.coordinator.stopAdmission();
				Assertions.assertFalse(fixture.coordinator.awaitTermination(System.nanoTime()));
				releaseClose.countDown();
				fixture.awaitRetirement();
				Assertions.assertEquals(1, closes.get());
				Assertions.assertSame(owner.get(), closeThread.get());
				Assertions.assertEquals(1, fixture.terminationCalls.get());
			} finally {
				releaseClose.countDown();
			}
		}
	}

	@Test
	public void canceledAcquisitionDisposesLateResultBeforeReturningItToWriter() throws Exception {
		CountDownLatch acquiring = new CountDownLatch(1);
		CountDownLatch releaseAcquisition = new CountDownLatch(1);
		AtomicInteger closes = new AtomicInteger();
		AtomicInteger uses = new AtomicInteger();
		AtomicReference<Throwable> acquisitionFailure = new AtomicReference<>();
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.start(responseStream -> {
					try {
						responseStream.open(() -> {
							acquiring.countDown();
							awaitUninterruptibly(releaseAcquisition);
							return closes::incrementAndGet;
						});
						uses.incrementAndGet();
					} catch (Exception failure) {
						acquisitionFailure.set(failure);
					}
				});
				await(acquiring);
				fixture.source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
				await(fixture.terminated);
				Assertions.assertEquals(0, closes.get());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().runningProducers());
				Assertions.assertNull(fixture.coordinator.tryReserve());
				releaseAcquisition.countDown();
				fixture.awaitRetirement();
				Assertions.assertEquals(1, closes.get());
				Assertions.assertEquals(0, uses.get());
				StreamingResponseCanceledException failure = Assertions.assertInstanceOf(
						StreamingResponseCanceledException.class, acquisitionFailure.get());
				Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, failure.getCancelationReason());
				Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.reason.get());
			} finally {
				releaseAcquisition.countDown();
			}
		}
	}

	@Test
	public void coordinatedCloseAsAbortRunsOnceWhileProducerCleanupWaits() throws Exception {
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch releaseClose = new CountDownLatch(1);
		AtomicInteger closes = new AtomicInteger();
		AtomicReference<Thread> signalThread = new AtomicReference<>();
		AtomicReference<Thread> closeThread = new AtomicReference<>();
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.start(responseStream -> {
					responseStream.open(() -> (AutoCloseable) () -> {
						closeThread.set(Thread.currentThread());
						closes.incrementAndGet();
						closeEntered.countDown();
						awaitUninterruptibly(releaseClose);
					});
					acquired.countDown();
					awaitUninterruptibly(closeEntered);
				});
				await(acquired);
				signalThread.set(Thread.currentThread());
				fixture.source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
				await(closeEntered);
				Assertions.assertNotSame(signalThread.get(), closeThread.get(), "Signal delivery cannot run resource close");
				Assertions.assertEquals(1, closes.get());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().runningProducers());
				Assertions.assertNull(fixture.coordinator.tryReserve());
				releaseClose.countDown();
				fixture.awaitRetirement();
				Assertions.assertEquals(1, closes.get(), "Owner cleanup must join the claimed close, never retry it");
				Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.reason.get());
			} finally {
				releaseClose.countDown();
				closeEntered.countDown();
			}
		}
	}

	@Test
	public void separateAbortCompletesBeforeFinalCloseOnProducer() throws Exception {
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch abortEntered = new CountDownLatch(1);
		CountDownLatch releaseAbort = new CountDownLatch(1);
		AtomicInteger aborts = new AtomicInteger();
		AtomicInteger closes = new AtomicInteger();
		AtomicReference<Thread> owner = new AtomicReference<>();
		AtomicReference<Thread> closeThread = new AtomicReference<>();
		AtomicReference<Thread> abortThread = new AtomicReference<>();
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.start(responseStream -> {
					owner.set(Thread.currentThread());
					responseStream.open(() -> (AutoCloseable) () -> {
						closeThread.set(Thread.currentThread());
						closes.incrementAndGet();
					}, resource -> {
						abortThread.set(Thread.currentThread());
						aborts.incrementAndGet();
						abortEntered.countDown();
						awaitUninterruptibly(releaseAbort);
					});
					acquired.countDown();
					awaitUninterruptibly(abortEntered);
				});
				await(acquired);
				fixture.source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
				await(abortEntered);
				Assertions.assertEquals(0, closes.get(), "Final close must wait for an already-running abort");
				Assertions.assertNotSame(Thread.currentThread(), abortThread.get());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().runningProducers());
				releaseAbort.countDown();
				fixture.awaitRetirement();
				Assertions.assertEquals(1, aborts.get());
				Assertions.assertEquals(1, closes.get());
				Assertions.assertSame(owner.get(), closeThread.get());
				Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.reason.get());
			} finally {
				releaseAbort.countDown();
				abortEntered.countDown();
			}
		}
	}

	@Test
	public void lexicalFailureAfterCancelationRemainsAvailableAsDiagnosticEvidence() throws Exception {
		CountDownLatch consumerEntered = new CountDownLatch(1);
		CountDownLatch releaseConsumer = new CountDownLatch(1);
		IOException lexicalFailure = new IOException("lexical body failed after disconnect");
		AtomicInteger closes = new AtomicInteger();
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.start(responseStream -> responseStream.using(
						() -> (AutoCloseable) closes::incrementAndGet, resource -> {
							consumerEntered.countDown();
							awaitUninterruptibly(releaseConsumer);
							throw lexicalFailure;
						}));
				await(consumerEntered);
				fixture.source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
				await(fixture.terminated);
				Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.reason.get());
				Assertions.assertNull(fixture.cause.get());
				releaseConsumer.countDown();
				fixture.awaitRetirement();
				Assertions.assertEquals(1, closes.get());
				Assertions.assertTrue(fixture.diagnostics.stream().anyMatch(failure ->
						failure == lexicalFailure || List.of(failure.getSuppressed()).contains(lexicalFailure)),
						"A later producer failure must remain observable without replacing the winning cancelation");
				Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.reason.get());
				Assertions.assertNull(fixture.cause.get());
				Assertions.assertEquals(1, fixture.terminationCalls.get());
			} finally {
				releaseConsumer.countDown();
			}
		}
	}

	@Test
	public void caughtInterruptedQueueWriteRemainsAnApplicationCanceledResponse() throws Exception {
		CountDownLatch writing = new CountDownLatch(1);
		AtomicReference<Thread> owner = new AtomicReference<>();
		AtomicReference<Throwable> caught = new AtomicReference<>();
		AtomicInteger closes = new AtomicInteger();
		try (Fixture fixture = new Fixture()) {
			fixture.start(responseStream -> {
				owner.set(Thread.currentThread());
				responseStream.own((AutoCloseable) closes::incrementAndGet);
				writing.countDown();
				try {
					// No transport drains this 1 KiB queue, so the larger write cannot complete.
					responseStream.write(new byte[2_048]);
				} catch (IOException | InterruptedException failure) {
					caught.set(failure);
				} finally {
					Thread.interrupted();
				}
			});
			await(writing);
			owner.get().interrupt();
			await(fixture.terminated);
			fixture.awaitRetirement();
			Assertions.assertNotNull(caught.get());
			Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, fixture.reason.get());
			Assertions.assertEquals(1, closes.get());
		}
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Controlled lifecycle step did not occur");
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try { latch.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private static final class Fixture implements AutoCloseable {
		private final ExecutorService producers = Executors.newSingleThreadExecutor();
		private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
		private final AtomicLong clock = new AtomicLong(100L);
		private final List<Throwable> diagnostics = new CopyOnWriteArrayList<>();
		private final StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(
				1, 1, Duration.ofSeconds(30), this.diagnostics::add, this.clock::get);
		private final CountDownLatch terminated = new CountDownLatch(1);
		private final AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		private final AtomicReference<Throwable> cause = new AtomicReference<>();
		private final AtomicInteger terminationCalls = new AtomicInteger();
		private StreamLifecycleCoordinator.Reservation reservation;
		private WritableSource source;

		private void start(StreamingResponseWriter writer) throws IOException {
			this.reservation = this.coordinator.tryReserve();
			Assertions.assertNotNull(this.reservation);
			MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(200, "OK",
					List.of(new Header("Transfer-Encoding", "chunked")),
					Request.withPath(HttpMethod.GET, "/owned").build(), StreamingResponseBody.fromWriter(writer),
					this.producers, this.timeouts, 1_024, 1_024, null, null, () -> false,
					(establishedAt, duration, reason, cause) -> {
						this.reason.set(reason);
						this.cause.set(cause);
						this.terminationCalls.incrementAndGet();
						this.terminated.countDown();
					}, this.diagnostics::add, this.reservation);
			this.source = response.writableSource(response.serializeHead("HTTP/1.1", List.of()));
			this.source.start();
		}

		private void awaitRetirement() throws InterruptedException {
			this.coordinator.stopAdmission();
			Assertions.assertTrue(this.coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			Assertions.assertEquals(0, this.coordinator.snapshot().reservations());
		}

		@Override public void close() throws Exception {
			try {
				if (this.source != null) this.source.close(StreamTerminationReason.SERVER_STOPPING, null);
				this.coordinator.force();
				this.producers.shutdownNow();
				this.timeouts.shutdownNow();
				Assertions.assertTrue(this.producers.awaitTermination(3, TimeUnit.SECONDS));
				Assertions.assertTrue(this.coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			} finally {
				this.producers.shutdownNow();
				this.timeouts.shutdownNow();
			}
		}
	}
}
