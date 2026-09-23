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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Interrupts the real four-byte producer queue after observable acceptance, without socket timing. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingOutputInterruptionTests {
	@Test
	public void viewBulkInterruptionReportsOnlyItsAcceptedPrefixAndRemainsTerminalWhenCaught() throws Exception {
		AtomicReference<Throwable> caught = new AtomicReference<>();
		AtomicReference<Throwable> retryFailure = new AtomicReference<>();
		AtomicBoolean interruptedAtCatch = new AtomicBoolean();
		try (Fixture fixture = new Fixture()) {
			fixture.start(responseStream -> {
				try {
					responseStream.asOutputStream().write(new byte[6]);
				} catch (IOException failure) {
					caught.set(failure);
					interruptedAtCatch.set(Thread.currentThread().isInterrupted());
					Thread.interrupted();
					try {
						responseStream.write(new byte[0]);
					} catch (IOException | InterruptedException retry) {
						retryFailure.set(retry);
					}
				} finally {
					// Successful failure detection must not depend on the flag surviving the handler.
					Thread.interrupted();
				}
			});
			fixture.interruptAfterFourAcceptedBytes();
			fixture.awaitRetirement();
			InterruptedIOException interruption = Assertions.assertInstanceOf(InterruptedIOException.class, caught.get());
			Assertions.assertEquals(4, interruption.bytesTransferred);
			Assertions.assertInstanceOf(InterruptedException.class, interruption.getCause());
			Assertions.assertSame(interruption.getCause(), fixture.cause.get());
			Assertions.assertTrue(interruptedAtCatch.get(), "The Java I/O bridge must restore the interrupt flag");
			StreamingResponseCanceledException retry = Assertions.assertInstanceOf(
					StreamingResponseCanceledException.class, retryFailure.get());
			Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, retry.getCancelationReason());
			fixture.assertTerminatedAs(StreamTerminationReason.APPLICATION_CANCELED);
		}
	}

	@Test
	public void nativeByteBufferPositionLimitAndMarkSurvivePartialInterruption() throws Exception {
		for (ByteBuffer byteBuffer : List.of(ByteBuffer.allocate(10), ByteBuffer.allocateDirect(10),
				ByteBuffer.allocateDirect(10).asReadOnlyBuffer())) {
			byteBuffer.position(2).limit(8).mark();
			AtomicReference<Throwable> caught = new AtomicReference<>();
			try (Fixture fixture = new Fixture()) {
				fixture.start(responseStream -> {
					try {
						responseStream.write(byteBuffer);
					} catch (IOException | InterruptedException failure) {
						caught.set(failure);
					} finally {
						Thread.interrupted();
					}
				});
				fixture.interruptAfterFourAcceptedBytes();
				fixture.awaitRetirement();
				Assertions.assertInstanceOf(InterruptedException.class, caught.get());
				Assertions.assertSame(caught.get(), fixture.cause.get());
				Assertions.assertEquals(2, byteBuffer.position());
				Assertions.assertEquals(8, byteBuffer.limit());
				Assertions.assertDoesNotThrow(() -> byteBuffer.reset());
				Assertions.assertEquals(2, byteBuffer.position());
				fixture.assertTerminatedAs(StreamTerminationReason.APPLICATION_CANCELED);
			}
		}
	}

	@Test
	public void anElectedTimeoutOrDisconnectWinsOverInterruptionTranslation() throws Exception {
		for (StreamTerminationReason reason : List.of(StreamTerminationReason.RESPONSE_TIMEOUT,
				StreamTerminationReason.CLIENT_DISCONNECTED)) {
			AtomicReference<Throwable> caught = new AtomicReference<>();
			Throwable originalCause = reason == StreamTerminationReason.CLIENT_DISCONNECTED
					? new IOException("disconnect evidence") : null;
			try (Fixture fixture = new Fixture()) {
				fixture.start(responseStream -> {
					try {
						responseStream.asOutputStream().write(new byte[6]);
					} catch (IOException failure) {
						caught.set(failure);
					} finally {
						Thread.interrupted();
					}
				});
				await(fixture.fourAcceptedBytes);
				fixture.source.close(reason, originalCause);
				fixture.awaitRetirement();
				StreamingResponseCanceledException canceled = Assertions.assertInstanceOf(
						StreamingResponseCanceledException.class, caught.get());
				Assertions.assertEquals(reason, canceled.getCancelationReason());
				Assertions.assertSame(originalCause, canceled.getCancelationCause().orElse(null));
				Assertions.assertSame(originalCause, fixture.cause.get());
				fixture.assertTerminatedAs(reason);
			}
		}
	}

	@Test
	public void interruptionDrainingOlderStagingReportsZeroForTheNewBulkCall() throws Exception {
		CountDownLatch newBulkCall = new CountDownLatch(1);
		AtomicReference<Throwable> caught = new AtomicReference<>();
		AtomicBoolean interruptedAtCatch = new AtomicBoolean();
		try (Fixture fixture = new Fixture()) {
			fixture.start(responseStream -> {
				responseStream.write(new byte[4]);
				OutputStream outputStream = responseStream.asOutputStream();
				outputStream.write(17);
				newBulkCall.countDown();
				try {
					outputStream.write(new byte[2]);
				} catch (IOException failure) {
					caught.set(failure);
					interruptedAtCatch.set(Thread.currentThread().isInterrupted());
				} finally {
					Thread.interrupted();
				}
			});
			await(fixture.fourAcceptedBytes);
			await(newBulkCall);
			fixture.owner.get().interrupt();
			fixture.awaitRetirement();
			InterruptedIOException interruption = Assertions.assertInstanceOf(InterruptedIOException.class, caught.get());
			Assertions.assertEquals(0, interruption.bytesTransferred,
					"The earlier scalar byte belongs to an earlier call, and the new bulk bytes were never accepted");
			Assertions.assertSame(interruption.getCause(), fixture.cause.get());
			Assertions.assertTrue(interruptedAtCatch.get());
			fixture.assertTerminatedAs(StreamTerminationReason.APPLICATION_CANCELED);
		}
	}

	@Test
	public void interruptedFlushAndCloseReportZeroAndFailedCloseIsStillIdempotent() throws Exception {
		for (boolean close : new boolean[]{false, true}) {
			CountDownLatch flushing = new CountDownLatch(1);
			AtomicReference<Throwable> caught = new AtomicReference<>();
			AtomicReference<Throwable> secondCloseFailure = new AtomicReference<>();
			AtomicBoolean secondCloseAttempted = new AtomicBoolean();
			AtomicBoolean interruptedAtCatch = new AtomicBoolean();
			try (Fixture fixture = new Fixture()) {
				fixture.start(responseStream -> {
					responseStream.write(new byte[4]);
					OutputStream outputStream = responseStream.asOutputStream();
					outputStream.write(17);
					flushing.countDown();
					try {
						if (close) outputStream.close();
						else outputStream.flush();
					} catch (IOException failure) {
						caught.set(failure);
						interruptedAtCatch.set(Thread.currentThread().isInterrupted());
					} finally {
						Thread.interrupted();
					}
					if (close) {
						secondCloseAttempted.set(true);
						try { outputStream.close(); }
						catch (IOException failure) { secondCloseFailure.set(failure); }
					}
				});
				await(fixture.fourAcceptedBytes);
				await(flushing);
				fixture.owner.get().interrupt();
				fixture.awaitRetirement();
				InterruptedIOException interruption = Assertions.assertInstanceOf(InterruptedIOException.class, caught.get());
				Assertions.assertEquals(0, interruption.bytesTransferred);
				Assertions.assertSame(interruption.getCause(), fixture.cause.get());
				Assertions.assertTrue(interruptedAtCatch.get());
				if (close) {
					Assertions.assertTrue(secondCloseAttempted.get());
					Assertions.assertNull(secondCloseFailure.get(), "The failing first close must still close its own view");
				}
				fixture.assertTerminatedAs(StreamTerminationReason.APPLICATION_CANCELED);
			}
		}
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Controlled streaming step did not occur");
	}

	private static final class Fixture implements AutoCloseable {
		private final ExecutorService producers = Executors.newSingleThreadExecutor();
		private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
		private final StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(
				1, 1, Duration.ofSeconds(30), ignored -> {});
		private final AtomicReference<Thread> owner = new AtomicReference<>();
		private final CountDownLatch fourAcceptedBytes = new CountDownLatch(2);
		private final CountDownLatch terminated = new CountDownLatch(1);
		private final AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		private final AtomicReference<Throwable> cause = new AtomicReference<>();
		private final AtomicInteger terminationCalls = new AtomicInteger();
		private WritableSource source;

		private void start(StreamingResponseWriter writer) throws IOException {
			StreamLifecycleCoordinator.Reservation reservation = this.coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(200, "OK",
					List.of(new Header("Transfer-Encoding", "chunked")),
					Request.withPath(HttpMethod.GET, "/output-interruption").build(),
					StreamingResponseBody.fromWriter(responseStream -> {
						this.owner.set(Thread.currentThread());
						writer.writeTo(responseStream);
					}), this.producers, this.timeouts, 4, 2, null, null, () -> false,
					(establishedAt, duration, reason, cause) -> {
						this.reason.set(reason);
						this.cause.set(cause);
						this.terminationCalls.incrementAndGet();
						this.terminated.countDown();
					}, ignored -> {}, reservation);
			this.source = response.writableSource(response.serializeHead("HTTP/1.1", List.of()));
			// Before any test-triggered cancelation, only the two accepted chunks wake the writer.
			// No transport drains this queue, so the third chunk cannot be accepted.
			this.source.writeReadyCallback(this.fourAcceptedBytes::countDown);
			this.source.start();
		}

		private void interruptAfterFourAcceptedBytes() throws InterruptedException {
			await(this.fourAcceptedBytes);
			this.owner.get().interrupt();
		}

		private void awaitRetirement() throws InterruptedException {
			await(this.terminated);
			this.coordinator.stopAdmission();
			Assertions.assertTrue(this.coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			Assertions.assertEquals(0, this.coordinator.snapshot().reservations());
		}

		private void assertTerminatedAs(StreamTerminationReason expected) {
			Assertions.assertEquals(expected, this.reason.get());
			Assertions.assertEquals(1, this.terminationCalls.get());
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
