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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletApplicationTriggerTests {
	@Test
	void unavailableStdinWarnsOnceWithoutRegisteringOrPublishingIntent() {
		RecordingProcessAccess processAccess = RecordingProcessAccess.unavailable();
		QueuedLauncher launcher = new QueuedLauncher();
		SokletApplicationInputManager manager =
				new SokletApplicationInputManager(processAccess, launcher);
		AtomicInteger intentCalls = new AtomicInteger();

		SokletApplicationTriggerRegistration first =
				manager.register(intentCalls::incrementAndGet);
		SokletApplicationTriggerRegistration second =
				manager.register(intentCalls::incrementAndGet);
		first.unregister();
		second.unregister();

		Assertions.assertEquals(List.of(
				"Ignoring ENTER_KEY shutdown because stdin is unavailable"),
				processAccess.warnings());
		Assertions.assertEquals(0, intentCalls.get());
		Assertions.assertEquals(0, manager.registrationCount());
		Assertions.assertFalse(manager.isListenerStarted());
		Assertions.assertEquals(0, launcher.launchAttempts());
	}

	@Test
	void stdinEofWarnsOnceWithoutPublishingIntentAndResetsListener() {
		RecordingProcessAccess processAccess =
				RecordingProcessAccess.withInput(InputStream.nullInputStream());
		QueuedLauncher launcher = new QueuedLauncher();
		SokletApplicationInputManager manager =
				new SokletApplicationInputManager(processAccess, launcher);
		AtomicInteger intentCalls = new AtomicInteger();
		SokletApplicationTriggerRegistration first =
				manager.register(intentCalls::incrementAndGet);
		SokletApplicationTriggerRegistration second = null;

		try {
			Assertions.assertTrue(manager.isListenerStarted());
			launcher.runNext();
			Assertions.assertFalse(manager.isListenerStarted());

			second = manager.register(intentCalls::incrementAndGet);
			Assertions.assertTrue(manager.isListenerStarted());
			launcher.runNext();

			Assertions.assertEquals(List.of(
					"Ignoring ENTER_KEY shutdown because stdin reached EOF"),
					processAccess.warnings());
			Assertions.assertEquals(0, intentCalls.get());
			Assertions.assertFalse(manager.isListenerStarted());
			Assertions.assertEquals(2, launcher.launchAttempts());
		} finally {
			first.unregister();
			if (second != null)
				second.unregister();
		}

		Assertions.assertEquals(0, manager.registrationCount());
	}

	@Test
	void retiredListenerDoesNotCloseProcessOwnedStdin() {
		CloseTrackingInputStream input = new CloseTrackingInputStream("\n");
		RecordingProcessAccess processAccess =
				RecordingProcessAccess.withInput(input);
		QueuedLauncher launcher = new QueuedLauncher();
		SokletApplicationInputManager manager =
				new SokletApplicationInputManager(processAccess, launcher);
		AtomicInteger intentCalls = new AtomicInteger();
		SokletApplicationTriggerRegistration registration =
				manager.register(intentCalls::incrementAndGet);

		try {
			launcher.runNext();

			Assertions.assertEquals(1, intentCalls.get());
			Assertions.assertFalse(input.closed());
			Assertions.assertFalse(manager.isListenerStarted());
		} finally {
			registration.unregister();
		}
	}

	@Test
	void registrationDuringEofWarningLaunchesANewListenerGeneration()
			throws Exception {
		CountDownLatch warningEntered = new CountDownLatch(1);
		CountDownLatch releaseWarning = new CountDownLatch(1);
		AtomicInteger inputRequests = new AtomicInteger();
		AtomicReference<String> warning = new AtomicReference<>();
		LifecycleProcessAccess processAccess = new LifecycleProcessAccess() {
			@NonNull
			@Override
			public Optional<InputStream> standardInput() {
				return Optional.of(inputRequests.incrementAndGet() == 1
						? InputStream.nullInputStream() : newlineInput());
			}

			@Override
			public void addShutdownHook(@NonNull Thread hook) {
				throw new AssertionError("The input manager must not register JVM hooks");
			}

			@Override
			public boolean removeShutdownHook(@NonNull Thread hook) {
				throw new AssertionError("The input manager must not remove JVM hooks");
			}

			@Override
			public void reportConfigurationWarning(@NonNull String message) {
				warning.set(message);
				warningEntered.countDown();
				awaitUninterruptibly(releaseWarning);
			}
		};
		QueuedLauncher launcher = new QueuedLauncher();
		SokletApplicationInputManager manager =
				new SokletApplicationInputManager(processAccess, launcher);
		AtomicInteger firstCalls = new AtomicInteger();
		AtomicInteger secondCalls = new AtomicInteger();
		SokletApplicationTriggerRegistration first =
				manager.register(firstCalls::incrementAndGet);
		AtomicReference<SokletApplicationTriggerRegistration> second =
				new AtomicReference<>();
		Thread exitingListener = new Thread(launcher::runNext,
				"soklet-input-eof-warning-race");
		exitingListener.setDaemon(true);

		try {
			exitingListener.start();
			Assertions.assertTrue(warningEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertFalse(manager.isListenerStarted(),
					"The exiting generation must retire before warning delivery");

			second.set(manager.register(secondCalls::incrementAndGet));
			Assertions.assertEquals(2, inputRequests.get());
			Assertions.assertEquals(2, launcher.launchAttempts());
			Assertions.assertEquals(1, launcher.queuedCount());
			Assertions.assertTrue(manager.isListenerStarted());

			launcher.runNext();
			Assertions.assertEquals(1, firstCalls.get());
			Assertions.assertEquals(1, secondCalls.get());
			Assertions.assertFalse(manager.isListenerStarted());
			Assertions.assertEquals(
					"Ignoring ENTER_KEY shutdown because stdin reached EOF",
					warning.get());
		} finally {
			releaseWarning.countDown();
			first.unregister();
			if (second.get() != null)
				second.get().unregister();
		}

		exitingListener.join(5_000L);
		Assertions.assertFalse(exitingListener.isAlive());
		Assertions.assertEquals(0, manager.registrationCount());
	}

	@Test
	void newlineSnapshotsEveryRegistrationBeforeCallbacksCanMutateTheRegistry() {
		RecordingProcessAccess processAccess =
				RecordingProcessAccess.withInput(newlineInput());
		QueuedLauncher launcher = new QueuedLauncher();
		SokletApplicationInputManager manager =
				new SokletApplicationInputManager(processAccess, launcher);
		List<String> calls = new CopyOnWriteArrayList<>();
		AtomicReference<SokletApplicationTriggerRegistration> second =
				new AtomicReference<>();
		AtomicReference<SokletApplicationTriggerRegistration> late =
				new AtomicReference<>();
		SokletApplicationTriggerRegistration first = manager.register(() -> {
			calls.add("first");
			second.get().unregister();
			late.set(manager.register(() -> calls.add("late")));
		});
		second.set(manager.register(() -> calls.add("second")));
		SokletApplicationTriggerRegistration third =
				manager.register(() -> calls.add("third"));

		try {
			Assertions.assertEquals(1, launcher.launchAttempts(),
					"All registrations must share the one process-wide listener");
			launcher.runNext();

			Assertions.assertEquals(List.of("first", "second", "third"), calls,
					"The newline snapshot must be fixed before any intent callback runs");
			Assertions.assertNotNull(late.get());
			Assertions.assertFalse(manager.isListenerStarted());
		} finally {
			first.unregister();
			second.get().unregister();
			third.unregister();
			SokletApplicationTriggerRegistration lateRegistration = late.get();
			if (lateRegistration != null)
				lateRegistration.unregister();
		}

		Assertions.assertEquals(0, manager.registrationCount());
	}

	@Test
	void unregisterIsIdempotentAndExcludesIntentFromTheNewlineSnapshot() {
		RecordingProcessAccess processAccess =
				RecordingProcessAccess.withInput(newlineInput());
		QueuedLauncher launcher = new QueuedLauncher();
		SokletApplicationInputManager manager =
				new SokletApplicationInputManager(processAccess, launcher);
		AtomicInteger retainedCalls = new AtomicInteger();
		AtomicInteger removedCalls = new AtomicInteger();
		SokletApplicationTriggerRegistration retained =
				manager.register(retainedCalls::incrementAndGet);
		SokletApplicationTriggerRegistration removed =
				manager.register(removedCalls::incrementAndGet);

		try {
			removed.unregister();
			removed.unregister();
			Assertions.assertEquals(1, manager.registrationCount());

			launcher.runNext();

			Assertions.assertEquals(1, retainedCalls.get());
			Assertions.assertEquals(0, removedCalls.get());
			Assertions.assertFalse(manager.isListenerStarted());
		} finally {
			retained.unregister();
			removed.unregister();
		}

		Assertions.assertEquals(0, manager.registrationCount());
	}

	@Test
	void listenerLaunchFailureRollsBackRegistrationAndAllowsASecondAttempt() {
		RecordingProcessAccess processAccess =
				RecordingProcessAccess.withInput(newlineInput());
		QueuedLauncher launcher = new QueuedLauncher();
		SokletApplicationInputManager manager =
				new SokletApplicationInputManager(processAccess, launcher);
		RuntimeException exactFailure = new RuntimeException("listener rejected");
		AtomicInteger intentCalls = new AtomicInteger();
		launcher.rejectNext(exactFailure);

		RuntimeException actualFailure = Assertions.assertThrows(
				RuntimeException.class,
				() -> manager.register(intentCalls::incrementAndGet));
		Assertions.assertSame(exactFailure, actualFailure);
		Assertions.assertEquals(0, manager.registrationCount());
		Assertions.assertFalse(manager.isListenerStarted());
		Assertions.assertEquals(0, intentCalls.get());

		SokletApplicationTriggerRegistration recovered =
				manager.register(intentCalls::incrementAndGet);
		try {
			Assertions.assertTrue(manager.isListenerStarted());
			launcher.runNext();
			Assertions.assertEquals(1, intentCalls.get());
			Assertions.assertFalse(manager.isListenerStarted());
		} finally {
			recovered.unregister();
		}

		Assertions.assertEquals(0, manager.registrationCount());
		Assertions.assertEquals(2, launcher.launchAttempts());
	}

	@Test
	void concurrentRegistrationTakesOverAfterFirstListenerLaunchFailure()
			throws Exception {
		RecordingProcessAccess processAccess =
				RecordingProcessAccess.withInput(newlineInput());
		CountDownLatch firstLaunchEntered = new CountDownLatch(1);
		CountDownLatch releaseFirstLaunch = new CountDownLatch(1);
		AtomicInteger launchAttempts = new AtomicInteger();
		AtomicReference<Runnable> launchedTask = new AtomicReference<>();
		RuntimeException launchFailure = new RuntimeException("first launch failed");
		SokletApplicationInputManager manager = new SokletApplicationInputManager(
				processAccess, (name, task) -> {
					if (launchAttempts.incrementAndGet() == 1) {
						firstLaunchEntered.countDown();
						awaitUninterruptibly(releaseFirstLaunch);
						throw launchFailure;
					}
					launchedTask.set(task);
				});
		AtomicReference<Throwable> firstFailure = new AtomicReference<>();
		AtomicReference<SokletApplicationTriggerRegistration> second =
				new AtomicReference<>();
		AtomicInteger intentCalls = new AtomicInteger();
		Thread first = new Thread(() -> {
			try {
				manager.register(() -> { });
			} catch (Throwable failure) {
				firstFailure.set(failure);
			}
		}, "soklet-input-first-launch");
		Thread contender = new Thread(() -> second.set(
				manager.register(intentCalls::incrementAndGet)),
				"soklet-input-launch-takeover");
		first.setDaemon(true);
		contender.setDaemon(true);
		first.start();
		Assertions.assertTrue(firstLaunchEntered.await(5, TimeUnit.SECONDS));
		contender.start();
		awaitThreadState(contender, Thread.State.BLOCKED);

		releaseFirstLaunch.countDown();
		first.join(5_000L);
		contender.join(5_000L);

		Assertions.assertFalse(first.isAlive());
		Assertions.assertFalse(contender.isAlive());
		Assertions.assertSame(launchFailure, firstFailure.get());
		Assertions.assertNotNull(second.get());
		Assertions.assertEquals(2, launchAttempts.get());
		Assertions.assertEquals(1, manager.registrationCount());
		Assertions.assertTrue(manager.isListenerStarted());
		requireNonNull(launchedTask.get()).run();
		Assertions.assertEquals(1, intentCalls.get());
		second.get().unregister();
		Assertions.assertEquals(0, manager.registrationCount());
	}

	@Test
	void concurrentRegistrationsCreateOnlyOneProcessWideListener() throws Exception {
		RecordingProcessAccess processAccess =
				RecordingProcessAccess.withInput(newlineInput());
		QueuedLauncher launcher = new QueuedLauncher();
		SokletApplicationInputManager manager =
				new SokletApplicationInputManager(processAccess, launcher);
		int contenderCount = 12;
		CountDownLatch ready = new CountDownLatch(contenderCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(contenderCount);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicInteger intentCalls = new AtomicInteger();
		List<SokletApplicationTriggerRegistration> registrations =
				new CopyOnWriteArrayList<>();

		for (int index = 0; index < contenderCount; index++) {
			Thread contender = new Thread(() -> {
				ready.countDown();
				try {
					start.await();
					registrations.add(manager.register(intentCalls::incrementAndGet));
				} catch (Throwable throwable) {
					failure.compareAndSet(null, throwable);
				} finally {
					finished.countDown();
				}
			}, "soklet-input-registration-test");
			contender.setDaemon(true);
			contender.start();
		}

		try {
			Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			Assertions.assertTrue(finished.await(5, TimeUnit.SECONDS));
			Assertions.assertNull(failure.get());
			Assertions.assertEquals(contenderCount, registrations.size());
			Assertions.assertEquals(contenderCount, manager.registrationCount());
			Assertions.assertEquals(1, launcher.launchAttempts());
			Assertions.assertEquals(1, launcher.queuedCount());
			Assertions.assertTrue(manager.isListenerStarted());
		} finally {
			start.countDown();
			for (SokletApplicationTriggerRegistration registration
					: registrations)
				registration.unregister();
			if (launcher.queuedCount() > 0)
				launcher.runNext();
		}

		Assertions.assertEquals(0, intentCalls.get());
		Assertions.assertEquals(0, manager.registrationCount());
		Assertions.assertFalse(manager.isListenerStarted());
	}

	@Test
	void oneIntentFailureDoesNotSuppressLaterRegistrations() {
		RecordingProcessAccess processAccess =
				RecordingProcessAccess.withInput(newlineInput());
		QueuedLauncher launcher = new QueuedLauncher();
		SokletApplicationInputManager manager =
				new SokletApplicationInputManager(processAccess, launcher);
		AssertionError exactFailure = new AssertionError("intent failed");
		AtomicInteger laterCalls = new AtomicInteger();
		SokletApplicationTriggerRegistration first =
				manager.register(() -> { throw exactFailure; });
		SokletApplicationTriggerRegistration second =
				manager.register(laterCalls::incrementAndGet);
		SokletApplicationTriggerRegistration third =
				manager.register(laterCalls::incrementAndGet);

		try {
			launcher.runNext();

			Assertions.assertEquals(2, laterCalls.get());
			Assertions.assertFalse(manager.isListenerStarted());
			Assertions.assertEquals(List.of(
					"Ignoring ENTER_KEY shutdown because stdin reached EOF"),
					processAccess.warnings());
		} finally {
			first.unregister();
			second.unregister();
			third.unregister();
		}

		Assertions.assertEquals(0, manager.registrationCount());
	}

	@NonNull
	private static InputStream newlineInput() {
		return new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8));
	}

	private static void awaitThreadState(@NonNull Thread thread,
			Thread.State expected) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (requireNonNull(thread).getState() != requireNonNull(expected)) {
			if (System.nanoTime() >= deadline)
				Assertions.fail("Thread did not reach state " + expected
						+ "; current=" + thread.getState());
			Thread.onSpinWait();
		}
	}

	private static void awaitUninterruptibly(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				requireNonNull(latch).await();
				break;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static final class RecordingProcessAccess
			implements LifecycleProcessAccess {
		@NonNull
		private final Optional<InputStream> input;
		@NonNull
		private final List<String> warnings;

		private RecordingProcessAccess(@NonNull Optional<InputStream> input) {
			this.input = input;
			this.warnings = new CopyOnWriteArrayList<>();
		}

		@NonNull
		static RecordingProcessAccess unavailable() {
			return new RecordingProcessAccess(Optional.empty());
		}

		@NonNull
		static RecordingProcessAccess withInput(@NonNull InputStream input) {
			return new RecordingProcessAccess(Optional.of(input));
		}

		@NonNull
		@Override
		public Optional<InputStream> standardInput() {
			return this.input;
		}

		@Override
		public void addShutdownHook(@NonNull Thread hook) {
			throw new AssertionError("The input manager must not register JVM hooks");
		}

		@Override
		public boolean removeShutdownHook(@NonNull Thread hook) {
			throw new AssertionError("The input manager must not remove JVM hooks");
		}

		@Override
		public void reportConfigurationWarning(@NonNull String message) {
			this.warnings.add(message);
		}

		@NonNull
		List<String> warnings() {
			return List.copyOf(this.warnings);
		}
	}

	private static final class CloseTrackingInputStream
			extends ByteArrayInputStream {
		private boolean closed;

		private CloseTrackingInputStream(@NonNull String input) {
			super(requireNonNull(input).getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public void close() {
			this.closed = true;
		}

		boolean closed() {
			return this.closed;
		}
	}

	private static final class QueuedLauncher
			implements SokletApplicationInputManager.DaemonLauncher {
		@NonNull
		private final ArrayDeque<Runnable> tasks;
		@NonNull
		private final AtomicInteger launchAttempts;
		@NonNull
		private final AtomicReference<Throwable> nextFailure;

		private QueuedLauncher() {
			this.tasks = new ArrayDeque<>();
			this.launchAttempts = new AtomicInteger();
			this.nextFailure = new AtomicReference<>();
		}

		@Override
		public void launch(@NonNull String name, @NonNull Runnable task) {
			Assertions.assertEquals("soklet-application-enter-key", name);
			this.launchAttempts.incrementAndGet();
			Throwable failure = this.nextFailure.getAndSet(null);
			if (failure instanceof RuntimeException runtimeFailure)
				throw runtimeFailure;
			if (failure instanceof Error error)
				throw error;
			synchronized (this.tasks) {
				this.tasks.addLast(task);
			}
		}

		void rejectNext(@NonNull Throwable failure) {
			if (!(failure instanceof RuntimeException) && !(failure instanceof Error))
				throw new IllegalArgumentException(
						"Listener failures must be unchecked", failure);
			if (!this.nextFailure.compareAndSet(null, failure))
				throw new IllegalStateException("A listener failure is already pending");
		}

		int launchAttempts() {
			return this.launchAttempts.get();
		}

		int queuedCount() {
			synchronized (this.tasks) {
				return this.tasks.size();
			}
		}

		void runNext() {
			Runnable task;
			synchronized (this.tasks) {
				task = this.tasks.removeFirst();
			}
			task.run();
		}
	}
}
