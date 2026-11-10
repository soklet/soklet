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

package com.soklet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(60)
@ResourceLock("java.lang.System.err")
class LifecycleLogDispatcherTests {

	@Test
	void idleWorkerRetiresAndNextRecordStartsANewWorker() throws Exception {
		AtomicReference<Thread> worker = new AtomicReference<>();
		AtomicInteger workerStarts = new AtomicInteger();
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher(task -> {
			Thread thread = Thread.ofPlatform().daemon(true)
					.inheritInheritableThreadLocals(false).unstarted(task);
			worker.set(thread);
			workerStarts.incrementAndGet();
			return thread;
		});
		try {
			CountDownLatch firstDelivered = new CountDownLatch(1);
			dispatcher.dispatch(firstDelivered::countDown);
			Assertions.assertTrue(firstDelivered.await(5, TimeUnit.SECONDS));
			Thread initialWorker = worker.get();
			Assertions.assertNotNull(initialWorker);
			initialWorker.join(5_000);
			Assertions.assertFalse(initialWorker.isAlive(), "Idle logging worker did not retire");
			Assertions.assertEquals(1, workerStarts.get());

			CountDownLatch secondDelivered = new CountDownLatch(1);
			dispatcher.dispatch(secondDelivered::countDown);
			Assertions.assertTrue(secondDelivered.await(5, TimeUnit.SECONDS));
			Thread replacementWorker = worker.get();
			Assertions.assertNotNull(replacementWorker);
			Assertions.assertNotSame(initialWorker, replacementWorker);
			Assertions.assertEquals(2, workerStarts.get());
			replacementWorker.join(5_000);
			Assertions.assertFalse(replacementWorker.isAlive(), "Replacement logging worker did not retire");
		} finally {
			dispatcher.shutdown();
			Thread remainingWorker = worker.get();
			if (remainingWorker != null)
				remainingWorker.join(5_000);
		}
	}

	@Test
	void activeRecordAndQueuedRecordsSurviveTheIdleInterval() throws Exception {
		AtomicReference<Thread> worker = new AtomicReference<>();
		AtomicInteger workerStarts = new AtomicInteger();
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher(task -> {
			Thread thread = Thread.ofPlatform().daemon(true)
					.inheritInheritableThreadLocals(false).unstarted(task);
			worker.set(thread);
			workerStarts.incrementAndGet();
			return thread;
		});
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch activeFinished = new CountDownLatch(1);
		CountDownLatch queuedFinished = new CountDownLatch(3);
		AtomicInteger delivered = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			dispatcher.dispatch(() -> {
				entered.countDown();
				try {
					if (!release.await(5, TimeUnit.SECONDS))
						failure.set(new AssertionError("Active record release timed out"));
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					failure.set(interrupted);
				} finally {
					activeFinished.countDown();
				}
			});
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
			for (int index = 1; index <= 3; ++index) {
				int expectedSequence = index;
				dispatcher.dispatch(() -> {
					if (delivered.incrementAndGet() != expectedSequence)
						failure.set(new AssertionError("Queued records changed order"));
					queuedFinished.countDown();
				});
			}

			Assertions.assertFalse(activeFinished.await(1_500, TimeUnit.MILLISECONDS),
					"Idle timeout interrupted an active logging record");
			Assertions.assertTrue(worker.get().isAlive());
			Assertions.assertEquals(1, workerStarts.get());
			Assertions.assertEquals(0, delivered.get());

			release.countDown();
			Assertions.assertTrue(activeFinished.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(queuedFinished.await(5, TimeUnit.SECONDS));
			Assertions.assertNull(failure.get());
			Assertions.assertEquals(3, delivered.get());
			Assertions.assertEquals(1, workerStarts.get());
			worker.get().join(5_000);
			Assertions.assertFalse(worker.get().isAlive(), "Drained logging worker did not retire");
		} finally {
			release.countDown();
			dispatcher.shutdown();
			Thread remainingWorker = worker.get();
			if (remainingWorker != null)
				remainingWorker.join(5_000);
		}
	}

	@Test
	void defaultObserverReturnsWhileItsStderrSinkIsBlocked() throws Exception {
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher();
		try {
			DefaultLifecycleObserver observer = DefaultLifecycleObserver.withDispatcher(dispatcher);
			verifyBlockedSink(dispatcher, () -> observer.didReceiveLogEvent(event("blocked default", null)));
		} finally {
			dispatcher.shutdown();
		}
	}

	@Test
	void failedObserverFallbackReturnsWhileItsStderrSinkIsBlocked() throws Exception {
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher();
		try {
			verifyBlockedSink(dispatcher, () -> LifecycleObserverLogFallback.report(
					new IllegalStateException("observer failure"), System.err, dispatcher));
		} finally {
			dispatcher.shutdown();
		}
	}

	@Test
	void throwableInspectionRunsOffTheEmittingThread() throws Exception {
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher();
		CountDownLatch inspected = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Throwable failure = new IllegalStateException("unexpected") {
			@Override
			public synchronized Throwable getCause() {
				inspected.countDown();
				awaitGate(release);
				return null;
			}
		};
		PrintStream original = System.err;
		try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
			System.setErr(sink);
			DefaultLifecycleObserver observer = DefaultLifecycleObserver.withDispatcher(dispatcher);
			CountDownLatch returned = new CountDownLatch(1);
			Thread emitter = Thread.ofPlatform().daemon(true).start(() -> {
				observer.didReceiveLogEvent(event("blocked formatter", failure));
				returned.countDown();
			});
			try {
				Assertions.assertTrue(inspected.await(5, TimeUnit.SECONDS));
				Assertions.assertTrue(returned.await(1, TimeUnit.SECONDS), "Throwable inspection blocked the emitter");
			} finally {
				release.countDown();
				emitter.join(5_000);
				Assertions.assertFalse(emitter.isAlive());
				Assertions.assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(5)));
			}
		} finally {
			System.setErr(original);
			dispatcher.shutdown();
		}
	}

	@Test
	void stalledWriterHasABoundedQueueAndNeverRunsOverflowOnTheCaller() throws Exception {
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher();
		CountDownLatch active = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch accepted = new CountDownLatch(LifecycleLogDispatcher.QUEUE_CAPACITY);
		AtomicInteger delivered = new AtomicInteger();
		AtomicInteger overflow = new AtomicInteger();
		try {
			dispatcher.dispatch(() -> {
				active.countDown();
				awaitGate(release);
			});
			Assertions.assertTrue(active.await(5, TimeUnit.SECONDS));
			for (int index = 0; index < LifecycleLogDispatcher.QUEUE_CAPACITY; ++index)
				dispatcher.dispatch(() -> { delivered.incrementAndGet(); accepted.countDown(); });
			for (int index = 0; index < 32; ++index)
				dispatcher.dispatch(overflow::incrementAndGet);
			Assertions.assertEquals(0, delivered.get());
			Assertions.assertEquals(0, overflow.get());
			release.countDown();
			Assertions.assertTrue(accepted.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(5)));
			Assertions.assertEquals(LifecycleLogDispatcher.QUEUE_CAPACITY, delivered.get());
			Assertions.assertEquals(0, overflow.get(), "Rejected work must neither run inline nor grow the queue");
		} finally {
			release.countDown();
			dispatcher.shutdown();
		}
	}

	@Test
	void workerDoesNotInheritApplicationThreadLocalsOrContextClassLoader() throws Exception {
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher();
		AtomicInteger inherited = new AtomicInteger();
		InheritableThreadLocal<String> local = new InheritableThreadLocal<>() {
			@Override
			protected String childValue(String value) {
				inherited.incrementAndGet();
				throw new IllegalStateException("must not invoke application childValue");
			}
		};
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		ClassLoader applicationLoader = new ClassLoader(original) {};
		AtomicReference<String> workerValue = new AtomicReference<>();
		AtomicReference<Thread> worker = new AtomicReference<>();
		CountDownLatch delivered = new CountDownLatch(1);
		try {
			Thread.currentThread().setContextClassLoader(applicationLoader);
			local.set("application context");
			dispatcher.dispatch(() -> {
				workerValue.set(local.get());
				worker.set(Thread.currentThread());
				delivered.countDown();
			});
			Assertions.assertTrue(delivered.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(0, inherited.get());
			Assertions.assertNull(workerValue.get());
			Assertions.assertTrue(worker.get().isDaemon());
			Assertions.assertSame(LifecycleLogDispatcher.class.getClassLoader(), worker.get().getContextClassLoader());
			Assertions.assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(5)));
		} finally {
			local.remove();
			Thread.currentThread().setContextClassLoader(original);
			dispatcher.shutdown();
		}
	}

	@Test
	void workerStartFailureDoesNotExecuteTheRecordInline() {
		AtomicInteger delivered = new AtomicInteger();
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher(task -> {
			throw new IllegalStateException("worker cannot start");
		});
		try {
			Assertions.assertDoesNotThrow(() -> dispatcher.dispatch(delivered::incrementAndGet));
			Assertions.assertEquals(0, delivered.get());
		} finally {
			dispatcher.shutdown();
		}
	}

	@Test
	void formatterFailureDoesNotPreventTheFollowingRecord() throws Exception {
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher();
		AtomicInteger failed = new AtomicInteger();
		AtomicInteger delivered = new AtomicInteger();
		try {
			dispatcher.dispatch(() -> { failed.incrementAndGet(); throw new AssertionError("formatter failed"); });
			dispatcher.dispatch(delivered::incrementAndGet);
			Assertions.assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(5)));
			Assertions.assertEquals(1, failed.get());
			Assertions.assertEquals(1, delivered.get());
		} finally {
			dispatcher.shutdown();
		}
	}

	@Test
	void defaultObserverCapsMessagesWithoutSplittingSupplementaryCharacters() throws Exception {
		LifecycleLogDispatcher dispatcher = new LifecycleLogDispatcher();
		PrintStream original = System.err;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream sink = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
			System.setErr(sink);
			String prefix = "x".repeat(LifecycleLogDispatcher.MAXIMUM_MESSAGE_CHARACTERS - 1);
			DefaultLifecycleObserver.withDispatcher(dispatcher).didReceiveLogEvent(event(prefix + "😀discarded", null));
			Assertions.assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(5)));
			Assertions.assertEquals("LifecycleObserver::didReceiveLogEvent [SERVER_INTERNAL_ERROR]: "
					+ prefix + System.lineSeparator(), bytes.toString(StandardCharsets.UTF_8));
		} finally {
			System.setErr(original);
			dispatcher.shutdown();
		}
	}

	private static LogEvent event(String message, Throwable failure) {
		return LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR, message).throwable(failure).build();
	}

	private static void verifyBlockedSink(LifecycleLogDispatcher dispatcher, Runnable emission) throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch returned = new CountDownLatch(1);
		AtomicReference<Throwable> unexpected = new AtomicReference<>();
		PrintStream original = System.err;
		try (PrintStream sink = new PrintStream(new OutputStream() {
			@Override
			public void write(int value) throws IOException {
				entered.countDown();
				try {
					if (!release.await(5, TimeUnit.SECONDS))
						throw new IOException("test sink release timed out");
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					throw new IOException(failure);
				}
			}
		}, true, StandardCharsets.UTF_8)) {
			System.setErr(sink);
			Thread emitter = Thread.ofPlatform().daemon(true).start(() -> {
				try { emission.run(); }
				catch (Throwable failure) { unexpected.set(failure); }
				finally { returned.countDown(); }
			});
			try {
				Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS), "Record did not reach the sink");
				Assertions.assertTrue(returned.await(1, TimeUnit.SECONDS), "stderr blocked the emitting thread");
				Assertions.assertNull(unexpected.get());
			} finally {
				release.countDown();
				emitter.join(5_000);
				Assertions.assertFalse(emitter.isAlive());
				Assertions.assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(5)));
			}
		} finally {
			System.setErr(original);
		}
	}

	private static void awaitGate(CountDownLatch release) {
		try {
			Assertions.assertTrue(release.await(5, TimeUnit.SECONDS));
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError(failure);
		}
	}
}
