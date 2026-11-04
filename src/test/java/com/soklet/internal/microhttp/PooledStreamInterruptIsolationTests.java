/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.microhttp;

import com.soklet.CallbackRegistration;
import com.soklet.CancelationToken;
import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.streaming.ManagedResponseStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class PooledStreamInterruptIsolationTests {
	@Test
	void canceledForkJoinProducerDoesNotInterruptTheNextStream() throws Exception {
		assertProducerIsolation(new ForkJoinPool(1));
	}

	@Test
	void producerBoundaryClearsStatusEvenWhenTheExecutorDoesNot() throws Exception {
		// Prove the fixture retains status between ordinary tasks on every JDK.
		FlagRetainingExecutor control = new FlagRetainingExecutor();
		CountDownLatch controlExited = new CountDownLatch(1);
		AtomicBoolean retained = new AtomicBoolean();
		try {
			control.execute(() -> Thread.currentThread().interrupt());
			control.execute(() -> {
				retained.set(Thread.currentThread().isInterrupted());
				controlExited.countDown();
			});
			await(controlExited);
			assertTrue(retained.get());
		} finally {
			stop(control);
		}
		assertProducerIsolation(new FlagRetainingExecutor());
	}

	@Test
	void inlineExecutionPreservesTheCallingThreadsInterruptStatus() throws Exception {
		StreamLifecycleCoordinator coordinator = coordinator();
		var reservation = coordinator.tryReserve();
		assertNotNull(reservation);
		AtomicBoolean preserved = new AtomicBoolean();
		Thread caller = new Thread(() -> {
			try {
				reservation.executeInline(() -> {
					Thread.currentThread().interrupt();
					reservation.complete();
				});
				preserved.set(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
		}, "inline-interrupt-owner");
		caller.setDaemon(true);
		try {
			caller.start();
			caller.join(5_000);
			assertFalse(caller.isAlive());
			assertTrue(preserved.get(), "An inline call does not own the host thread's interrupt status");
		} finally {
			caller.interrupt();
			caller.join(5_000);
			assertFalse(caller.isAlive());
			coordinator.force();
			assertTrue(coordinator.awaitTermination(deadline()));
		}
	}

	private static void assertProducerIsolation(ExecutorService executor) throws Exception {
		StreamLifecycleCoordinator coordinator = coordinator();
		var canceled = coordinator.tryReserve();
		var healthy = coordinator.tryReserve();
		assertNotNull(canceled);
		assertNotNull(healthy);
		canceled.bindTermination((reason, cause) -> canceled.complete());
		healthy.bindTermination((reason, cause) -> healthy.complete());
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		CountDownLatch healthyExited = new CountDownLatch(1);
		AtomicBoolean restored = new AtomicBoolean(), inherited = new AtomicBoolean(), writerEntered = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			assertTrue(canceled.execute(executor, () -> {
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException expected) {
					Thread.currentThread().interrupt();
					restored.set(true);
				} finally {
					canceled.complete();
				}
			}));
			await(entered);
			// Queue the next envelope before cancellation so the executor cannot park
			// and incidentally clear status between these two tasks.
			assertTrue(healthy.execute(executor, () -> {
				inherited.set(Thread.currentThread().isInterrupted());
				ManagedResponseStream.Output output = new ManagedResponseStream.Output() {
					@Override public void write(ByteBuffer input) {
						while (input.hasRemaining()) bytes.write(input.get());
					}
					@Override public void flush() {}
					@Override public boolean isOpen() { return true; }
				};
				ManagedResponseStream stream = new ManagedResponseStream(
						Request.fromPath(HttpMethod.GET, "/healthy"), healthyToken(),
						() -> false, null, null, output, () -> {}, failure::set, failure::set);
				try {
					stream.run(sink -> {
						writerEntered.set(true);
						sink.write(new byte[]{'o', 'k'});
					});
				} catch (Exception unexpected) {
					failure.set(unexpected);
				} finally {
					healthy.complete();
					healthyExited.countDown();
				}
			}));
			assertTrue(canceled.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			await(healthyExited);
			assertTrue(restored.get(), "The canceled application restored its interrupt status");
			assertFalse(inherited.get(), "The next producer must not inherit the canceled producer's status");
			assertTrue(writerEntered.get(), "A healthy writer must not be rejected as already interrupted");
			assertArrayEquals(new byte[]{'o', 'k'}, bytes.toByteArray());
			assertNull(failure.get());
		} finally {
			release.countDown();
			coordinator.retireQueuedTasks(executor.shutdownNow());
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
			coordinator.force();
			assertTrue(coordinator.awaitTermination(deadline()));
		}
	}

	private static StreamLifecycleCoordinator coordinator() {
		return new StreamLifecycleCoordinator(2, 1, Duration.ofSeconds(5), ignored -> {});
	}

	private static CancelationToken healthyToken() {
		return new CancelationToken() {
			@Override public Boolean isCanceled() { return false; }
			@Override public Optional<StreamTerminationReason> getCancelationReason() { return Optional.empty(); }
			@Override public Optional<Throwable> getCancelationCause() { return Optional.empty(); }
			@Override public CallbackRegistration onCancel(Runnable callback) { return () -> {}; }
		};
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertTrue(latch.await(5, TimeUnit.SECONDS), "Finite fixture control expired");
	}

	private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

	private static void stop(ExecutorService executor) throws InterruptedException {
		executor.shutdownNow();
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
	}

	/** A normal asynchronous executor that deliberately retains status across task boundaries. */
	private static final class FlagRetainingExecutor extends AbstractExecutorService {
		private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
		private final CountDownLatch terminated = new CountDownLatch(1);
		private final Thread worker;
		private volatile boolean shutdown;

		private FlagRetainingExecutor() {
			this.worker = new Thread(() -> {
				try {
					while (!this.shutdown || !this.tasks.isEmpty()) {
						Runnable task = this.tasks.poll();
						if (task != null) task.run();
						else LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
					}
				} finally {
					this.terminated.countDown();
				}
			}, "flag-retaining-stream-worker");
			this.worker.setDaemon(true);
			this.worker.start();
		}

		@Override public void execute(Runnable task) {
			if (this.shutdown) throw new RejectedExecutionException();
			this.tasks.add(task);
			LockSupport.unpark(this.worker);
		}
		@Override public void shutdown() { this.shutdown = true; LockSupport.unpark(this.worker); }
		@Override public List<Runnable> shutdownNow() {
			this.shutdown = true;
			List<Runnable> pending = new ArrayList<>();
			for (Runnable task; (task = this.tasks.poll()) != null;) pending.add(task);
			this.worker.interrupt();
			return pending;
		}
		@Override public boolean isShutdown() { return this.shutdown; }
		@Override public boolean isTerminated() { return this.terminated.getCount() == 0; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return this.terminated.await(timeout, unit);
		}
	}
}
