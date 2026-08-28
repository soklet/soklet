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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(60)
public class McpTransportMetricDrainSchedulerTests {
	@Test
	public void rejectedSubmissionWithoutRacingSignalRetriesOnNextSignal() {
		BarrierObserver observer = new BarrierObserver();
		RejectThenCaptureExecutor executor = new RejectThenCaptureExecutor();
		McpHttpServerRuntime.TransportMetricDrainScheduler scheduler =
				new McpHttpServerRuntime.TransportMetricDrainScheduler(
						observer, executor);
		observer.markAsynchronousDrainRequired();

		scheduler.schedule();
		Assertions.assertEquals(1, executor.submissions());
		Assertions.assertTrue(observer.asynchronousDrainRequired(),
				"A rejected submission must preserve the pending barrier.");
		Assertions.assertNull(executor.captured());

		scheduler.schedule();
		Assertions.assertEquals(2, executor.submissions(),
				"A later signal must be allowed to submit a replacement worker.");
		executor.runCaptured();
		Assertions.assertEquals(1, observer.drainCount());
		Assertions.assertFalse(observer.asynchronousDrainRequired());
	}

	@Test
	public void signalDuringRejectedFirstSubmissionRetriesWithoutThirdSignal()
			throws Exception {
		BarrierObserver observer = new BarrierObserver();
		BlockingRejectThenCaptureExecutor executor =
				new BlockingRejectThenCaptureExecutor();
		McpHttpServerRuntime.TransportMetricDrainScheduler scheduler =
				new McpHttpServerRuntime.TransportMetricDrainScheduler(
						observer, executor);
		observer.markAsynchronousDrainRequired();
		AtomicReference<Throwable> schedulingFailure = new AtomicReference<>();
		Thread firstSignal = new Thread(() -> {
			try {
				scheduler.schedule();
			} catch (Throwable throwable) {
				schedulingFailure.set(throwable);
			}
		}, "blocked-transport-metric-submit");

		firstSignal.start();
		executor.awaitFirstSubmission();
		Assertions.assertEquals(1, executor.submissions());
		Assertions.assertTrue(observer.asynchronousDrainRequired(),
				"An in-flight executor submission must not clear the pending barrier.");
		Assertions.assertEquals(0, observer.drainCount());

		// This signal arrives while workerScheduled is true and the first
		// executor submission has not yet reported its rejection.
		scheduler.schedule();
		executor.releaseFirstRejection();
		firstSignal.join(TimeUnit.SECONDS.toMillis(5));
		Assertions.assertFalse(firstSignal.isAlive(),
				"The rejected scheduler submission did not finish.");
		Assertions.assertNull(schedulingFailure.get());
		Assertions.assertEquals(2, executor.submissions(),
				"The signal racing the rejection must cause an immediate retry without a third signal.");
		Assertions.assertNotNull(executor.captured());
		executor.runCaptured();

		Assertions.assertEquals(1, observer.drainCount());
		Assertions.assertFalse(observer.asynchronousDrainRequired());
		Assertions.assertNull(executor.captured());
	}

	@Test
	public void signalArrivingInsideActiveWorkerIsDrainedWithoutAnotherSubmission() {
		BarrierObserver observer = new BarrierObserver();
		CapturingExecutor executor = new CapturingExecutor();
		AtomicReference<McpHttpServerRuntime.TransportMetricDrainScheduler>
				schedulerReference = new AtomicReference<>();
		observer.afterFirstDrain(() -> {
			observer.markAsynchronousDrainRequired();
			schedulerReference.get().schedule();
		});
		McpHttpServerRuntime.TransportMetricDrainScheduler scheduler =
				new McpHttpServerRuntime.TransportMetricDrainScheduler(
						observer, executor);
		schedulerReference.set(scheduler);
		observer.markAsynchronousDrainRequired();

		scheduler.schedule();
		Assertions.assertEquals(1, executor.submissions());
		executor.runCaptured();

		Assertions.assertEquals(2, observer.drainCount(),
				"The active worker must observe and drain its reentrant signal.");
		Assertions.assertEquals(1, executor.submissions());
		Assertions.assertFalse(observer.asynchronousDrainRequired());
	}

	private static final class BarrierObserver
			implements McpApplicationExecutionObserver {
		private final AtomicBoolean asynchronousDrainRequired = new AtomicBoolean();
		private final AtomicInteger drainCount = new AtomicInteger();
		private final AtomicReference<Runnable> afterFirstDrain =
				new AtomicReference<>();

		private void markAsynchronousDrainRequired() {
			this.asynchronousDrainRequired.set(true);
		}

		private boolean asynchronousDrainRequired() {
			return this.asynchronousDrainRequired.get();
		}

		private int drainCount() {
			return this.drainCount.get();
		}

		private void afterFirstDrain(Runnable callback) {
			this.afterFirstDrain.set(callback);
		}

		@Override
		public void drainAsynchronously() {
			this.asynchronousDrainRequired.set(false);
			int count = this.drainCount.incrementAndGet();
			Runnable callback = count == 1
					? this.afterFirstDrain.getAndSet(null) : null;
			if (callback != null)
				callback.run();
		}

		@Override
		public void beginDeferral() {
		}

		@Override
		public void recordHandlerExecutionStarted() {
		}

		@Override
		public void recordHandlerExecutionFinished() {
		}

		@Override
		public void recordHandlerQueued() {
		}

		@Override
		public void recordHandlerDequeued() {
		}

		@Override
		public void recordHandlerCapacityRejected() {
		}

		@Override
		public void drain() {
		}

		@Override
		public void endDeferral() {
		}
	}

	private static class CapturingExecutor implements Executor {
		protected final AtomicInteger submissions = new AtomicInteger();
		private final AtomicReference<Runnable> captured = new AtomicReference<>();

		@Override
		public void execute(Runnable command) {
			this.submissions.incrementAndGet();
			if (!this.captured.compareAndSet(null, command))
				throw new AssertionError("A scheduler worker was already captured.");
		}

		protected final int submissions() {
			return this.submissions.get();
		}

		protected final Runnable captured() {
			return this.captured.get();
		}

		protected final void runCaptured() {
			Runnable command = this.captured.getAndSet(null);
			if (command == null)
				throw new AssertionError("No scheduler worker was captured.");
			command.run();
		}
	}

	private static final class BlockingRejectThenCaptureExecutor
			extends CapturingExecutor {
		private final AtomicBoolean reject = new AtomicBoolean(true);
		private final CountDownLatch firstSubmissionEntered = new CountDownLatch(1);
		private final CountDownLatch releaseFirstRejection = new CountDownLatch(1);

		@Override
		public void execute(Runnable command) {
			if (this.reject.compareAndSet(true, false)) {
				this.submissions.incrementAndGet();
				this.firstSubmissionEntered.countDown();
				try {
					if (!this.releaseFirstRejection.await(5, TimeUnit.SECONDS))
						throw new AssertionError(
								"Timed out awaiting the first executor rejection release.");
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
				throw new RejectedExecutionException("expected first rejection");
			}
			super.execute(command);
		}

		private void awaitFirstSubmission() throws InterruptedException {
			Assertions.assertTrue(this.firstSubmissionEntered.await(
					5, TimeUnit.SECONDS),
					"The scheduler did not enter its first executor submission.");
		}

		private void releaseFirstRejection() {
			this.releaseFirstRejection.countDown();
		}
	}

	private static final class RejectThenCaptureExecutor
			extends CapturingExecutor {
		private final AtomicBoolean reject = new AtomicBoolean(true);

		@Override
		public void execute(Runnable command) {
			if (this.reject.compareAndSet(true, false)) {
				this.submissions.incrementAndGet();
				throw new RejectedExecutionException("expected first rejection");
			}
			super.execute(command);
		}
	}
}
