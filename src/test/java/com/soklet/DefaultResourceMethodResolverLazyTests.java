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

import com.soklet.annotation.GET;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DefaultResourceMethodResolverLazyTests {
	@Test
	void constructionIsLazyAndFirstPublicCallerCapturesTccl() throws Exception {
		AtomicInteger loadCount = new AtomicInteger();
		AtomicReference<ClassLoader> capturedClassLoader = new AtomicReference<>();
		DefaultResourceMethodResolver complete = completeResolver();
		ClassLoader constructionClassLoader = new ClassLoader(null) {};
		ClassLoader winningClassLoader = new ClassLoader(null) {};
		Thread thread = Thread.currentThread();
		ClassLoader originalClassLoader = thread.getContextClassLoader();
		DefaultResourceMethodResolver lazy;

		try {
			thread.setContextClassLoader(constructionClassLoader);
			lazy = DefaultResourceMethodResolver.lazyClasspathResolverForTesting(
					classLoader -> {
						loadCount.incrementAndGet();
						capturedClassLoader.set(classLoader);
						return complete;
					});
		} finally {
			thread.setContextClassLoader(originalClassLoader);
		}

		Assertions.assertEquals(0, loadCount.get());
		Assertions.assertSame(
				ResourceMethodResolver.fromClasspathIntrospection(),
				ResourceMethodResolver.fromClasspathIntrospection());

		Set<ResourceMethod> snapshot;
		try {
			thread.setContextClassLoader(winningClassLoader);
			snapshot = lazy.getResourceMethods();
		} finally {
			thread.setContextClassLoader(originalClassLoader);
		}

		Assertions.assertSame(winningClassLoader, capturedClassLoader.get());
		Assertions.assertEquals(1, loadCount.get());
		Assertions.assertSame(complete.getResourceMethods(), snapshot);
		Assertions.assertSame(snapshot, lazy.getResourceMethods());
		Assertions.assertThrows(UnsupportedOperationException.class,
				snapshot::clear);
	}

	@Test
	void concurrentPublicWaiterIsUninterruptibleAndRestoresInterrupt()
			throws Exception {
		AtomicInteger loadCount = new AtomicInteger();
		AtomicReference<ClassLoader> capturedClassLoader = new AtomicReference<>();
		CountDownLatch loaderEntered = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);
		CountDownLatch waiterStarted = new CountDownLatch(1);
		AtomicReference<Set<ResourceMethod>> ownerSnapshot = new AtomicReference<>();
		AtomicReference<Set<ResourceMethod>> waiterSnapshot = new AtomicReference<>();
		AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
		AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
		AtomicBoolean waiterInterruptRestored = new AtomicBoolean();
		DefaultResourceMethodResolver complete = completeResolver();
		ClassLoader ownerClassLoader = new ClassLoader(null) {};
		ClassLoader waiterClassLoader = new ClassLoader(null) {};
		DefaultResourceMethodResolver lazy =
				DefaultResourceMethodResolver.lazyClasspathResolverForTesting(
						classLoader -> {
							loadCount.incrementAndGet();
							capturedClassLoader.set(classLoader);
							loaderEntered.countDown();
							awaitUninterruptibly(releaseLoader);
							return complete;
						});

		Thread owner = new Thread(() -> runCapturing(ownerFailure, () -> {
			Thread.currentThread().setContextClassLoader(ownerClassLoader);
			ownerSnapshot.set(lazy.getResourceMethods());
		}), "lazy-resolver-owner");
		Thread waiter = new Thread(() -> runCapturing(waiterFailure, () -> {
			Thread.currentThread().setContextClassLoader(waiterClassLoader);
			waiterStarted.countDown();
			waiterSnapshot.set(lazy.getResourceMethods());
			waiterInterruptRestored.set(Thread.currentThread().isInterrupted());
		}), "lazy-resolver-public-waiter");

		owner.start();
		try {
			await(loaderEntered);
			waiter.start();
			await(waiterStarted);
			waiter.interrupt();
		} finally {
			releaseLoader.countDown();
		}

		join(owner);
		join(waiter);
		Assertions.assertNull(ownerFailure.get());
		Assertions.assertNull(waiterFailure.get());
		Assertions.assertEquals(1, loadCount.get());
		Assertions.assertSame(ownerClassLoader, capturedClassLoader.get());
		Assertions.assertSame(ownerSnapshot.get(), waiterSnapshot.get());
		Assertions.assertTrue(waiterInterruptRestored.get());
	}

	@Test
	void exactRuntimeFailureAndErrorAreCachedWithoutRetry() {
		AtomicInteger runtimeLoads = new AtomicInteger();
		RuntimeException runtimeFailure = new IllegalStateException("exact-runtime");
		DefaultResourceMethodResolver runtimeResolver =
				DefaultResourceMethodResolver.lazyClasspathResolverForTesting(
						classLoader -> {
							runtimeLoads.incrementAndGet();
							throw runtimeFailure;
						});

		Assertions.assertSame(runtimeFailure,
				Assertions.assertThrows(RuntimeException.class,
						runtimeResolver::getResourceMethods));
		Assertions.assertSame(runtimeFailure,
				Assertions.assertThrows(RuntimeException.class, () ->
						runtimeResolver.resourceMethodForRequest(
								Request.withPath(HttpMethod.GET, "/lazy").build(),
								ServerType.STANDARD_HTTP)));
		Assertions.assertEquals(1, runtimeLoads.get());

		AtomicInteger errorLoads = new AtomicInteger();
		AssertionError exactError = new AssertionError("exact-error");
		DefaultResourceMethodResolver errorResolver =
				DefaultResourceMethodResolver.lazyClasspathResolverForTesting(
						classLoader -> {
							errorLoads.incrementAndGet();
							throw exactError;
						});

		Assertions.assertSame(exactError,
				Assertions.assertThrows(AssertionError.class,
						errorResolver::getResourceMethods));
		Assertions.assertSame(exactError,
				Assertions.assertThrows(AssertionError.class,
						errorResolver::getMethods));
		Assertions.assertEquals(1, errorLoads.get());
	}

	@Test
	void lifecycleWaiterCancellationDoesNotPoisonTheSharedAttempt()
			throws Exception {
		AtomicInteger loadCount = new AtomicInteger();
		CountDownLatch loaderEntered = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);
		CountDownLatch lifecycleWaitEntered = new CountDownLatch(1);
		AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
		AtomicReference<Throwable> lifecycleOutcome = new AtomicReference<>();
		AtomicBoolean cancellationRequested = new AtomicBoolean();
		DefaultResourceMethodResolver complete = completeResolver();
		DefaultResourceMethodResolver lazy = blockingResolver(complete, loadCount,
				loaderEntered, releaseLoader);
		DeadlineWaiter deadlineWaiter = new DeadlineWaiter(NanoClock.system(),
				(monitor, remainingNanos) -> {
					lifecycleWaitEntered.countDown();
					monitor.wait();
				});
		StartupContext startupContext = new StartupContext(
				NanoClock.system(), Optional.empty(), Long.MAX_VALUE,
				cancellationRequested::get);
		Thread owner = publicOwner(lazy, ownerFailure);
		Thread lifecycleWaiter = new Thread(() -> {
			try {
				lazy.getResourceMethodsForLifecycle(startupContext,
						deadlineWaiter);
				lifecycleOutcome.set(new AssertionError(
						"Lifecycle wait unexpectedly completed"));
			} catch (Throwable throwable) {
				lifecycleOutcome.set(throwable);
			}
		}, "lazy-resolver-cancelled-lifecycle-waiter");

		owner.start();
		try {
			await(loaderEntered);
			lifecycleWaiter.start();
			await(lifecycleWaitEntered);
			cancellationRequested.set(true);
			deadlineWaiter.signal();
			join(lifecycleWaiter);
			Assertions.assertInstanceOf(
					DefaultResourceMethodResolver.StartupWaitCanceledException.class,
					lifecycleOutcome.get());
			Assertions.assertEquals(1L, releaseLoader.getCount(),
					"Cancellation must not release or replace the global owner");
		} finally {
			releaseLoader.countDown();
		}

		join(owner);
		Assertions.assertNull(ownerFailure.get());
		Assertions.assertEquals(1, loadCount.get());
		Assertions.assertSame(complete.getResourceMethods(),
				lazy.getResourceMethods());
	}

	@Test
	void lifecycleWaiterReceivesTheOnePublishedSnapshot() throws Exception {
		AtomicInteger loadCount = new AtomicInteger();
		CountDownLatch loaderEntered = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);
		CountDownLatch lifecycleWaitEntered = new CountDownLatch(1);
		AtomicReference<Set<ResourceMethod>> lifecycleSnapshot =
				new AtomicReference<>();
		AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
		AtomicReference<Throwable> lifecycleFailure = new AtomicReference<>();
		DefaultResourceMethodResolver complete = completeResolver();
		DefaultResourceMethodResolver lazy = blockingResolver(complete, loadCount,
				loaderEntered, releaseLoader);
		DeadlineWaiter deadlineWaiter = new DeadlineWaiter(NanoClock.system(),
				(monitor, remainingNanos) -> {
					lifecycleWaitEntered.countDown();
					monitor.wait();
				});
		StartupContext startupContext = new StartupContext(
				NanoClock.system(), Optional.empty(), Long.MAX_VALUE, () -> false);
		Thread owner = publicOwner(lazy, ownerFailure);
		Thread lifecycleWaiter = new Thread(() ->
				runCapturing(lifecycleFailure, () -> lifecycleSnapshot.set(
						lazy.getResourceMethodsForLifecycle(startupContext,
								deadlineWaiter))),
				"lazy-resolver-successful-lifecycle-waiter");

		owner.start();
		try {
			await(loaderEntered);
			lifecycleWaiter.start();
			await(lifecycleWaitEntered);
		} finally {
			releaseLoader.countDown();
		}

		join(owner);
		join(lifecycleWaiter);
		Assertions.assertNull(ownerFailure.get());
		Assertions.assertNull(lifecycleFailure.get());
		Assertions.assertEquals(1, loadCount.get());
		Assertions.assertSame(complete.getResourceMethods(),
				lifecycleSnapshot.get());
	}

	@Test
	void lifecycleWaiterInterruptionRestoresFlagAndUsesOnlyTheSentinel()
			throws Exception {
		AtomicInteger loadCount = new AtomicInteger();
		CountDownLatch loaderEntered = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);
		CountDownLatch lifecycleWaitEntered = new CountDownLatch(1);
		AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
		AtomicReference<Throwable> lifecycleOutcome = new AtomicReference<>();
		AtomicBoolean interruptRestored = new AtomicBoolean();
		DefaultResourceMethodResolver complete = completeResolver();
		DefaultResourceMethodResolver lazy = blockingResolver(complete, loadCount,
				loaderEntered, releaseLoader);
		DeadlineWaiter deadlineWaiter = new DeadlineWaiter(NanoClock.system(),
				(monitor, remainingNanos) -> {
					lifecycleWaitEntered.countDown();
					monitor.wait();
				});
		StartupContext startupContext = new StartupContext(
				NanoClock.system(), Optional.empty(), Long.MAX_VALUE, () -> false);
		Thread owner = publicOwner(lazy, ownerFailure);
		Thread lifecycleWaiter = new Thread(() -> {
			try {
				lazy.getResourceMethodsForLifecycle(startupContext,
						deadlineWaiter);
				lifecycleOutcome.set(new AssertionError(
						"Lifecycle wait unexpectedly completed"));
			} catch (Throwable throwable) {
				lifecycleOutcome.set(throwable);
				interruptRestored.set(Thread.currentThread().isInterrupted());
			}
		}, "lazy-resolver-interrupted-lifecycle-waiter");

		owner.start();
		try {
			await(loaderEntered);
			lifecycleWaiter.start();
			await(lifecycleWaitEntered);
			lifecycleWaiter.interrupt();
			join(lifecycleWaiter);
			Assertions.assertInstanceOf(
					DefaultResourceMethodResolver.StartupWaitCanceledException.class,
					lifecycleOutcome.get());
			Assertions.assertTrue(interruptRestored.get());
		} finally {
			releaseLoader.countDown();
		}

		join(owner);
		Assertions.assertNull(ownerFailure.get());
		Assertions.assertEquals(1, loadCount.get());
	}

	@Test
	void lifecycleWaiterConsumesTheExistingAbsoluteDeadline() throws Exception {
		AtomicInteger loadCount = new AtomicInteger();
		CountDownLatch loaderEntered = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);
		AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
		AtomicLong now = new AtomicLong(5L);
		AtomicLong observedRemaining = new AtomicLong(-1L);
		DefaultResourceMethodResolver complete = completeResolver();
		DefaultResourceMethodResolver lazy = blockingResolver(complete, loadCount,
				loaderEntered, releaseLoader);
		DeadlineWaiter deadlineWaiter = new DeadlineWaiter(now::get,
				(monitor, remainingNanos) -> {
					observedRemaining.set(remainingNanos);
					now.addAndGet(remainingNanos);
				});
		StartupContext startupContext = new StartupContext(now::get,
				Optional.of(37L), 90L, () -> false);
		Thread owner = publicOwner(lazy, ownerFailure);

		owner.start();
		try {
			await(loaderEntered);
			Assertions.assertThrows(
					DefaultResourceMethodResolver.StartupWaitCanceledException.class,
					() -> lazy.getResourceMethodsForLifecycle(startupContext,
							deadlineWaiter));
			Assertions.assertEquals(32L, observedRemaining.get());
			Assertions.assertEquals(37L, now.get());
		} finally {
			releaseLoader.countDown();
		}

		join(owner);
		Assertions.assertNull(ownerFailure.get());
		Assertions.assertEquals(1, loadCount.get());
	}

	@NonNull
	private static DefaultResourceMethodResolver blockingResolver(
			@NonNull DefaultResourceMethodResolver complete,
			@NonNull AtomicInteger loadCount,
			@NonNull CountDownLatch loaderEntered,
			@NonNull CountDownLatch releaseLoader) {
		return DefaultResourceMethodResolver.lazyClasspathResolverForTesting(
				classLoader -> {
					loadCount.incrementAndGet();
					loaderEntered.countDown();
					awaitUninterruptibly(releaseLoader);
					return complete;
				});
	}

	@NonNull
	private static DefaultResourceMethodResolver completeResolver()
			throws NoSuchMethodException {
		return DefaultResourceMethodResolver.fromMethods(Set.of(
				LazyResource.class.getMethod("get")));
	}

	@NonNull
	private static Thread publicOwner(
			@NonNull DefaultResourceMethodResolver resolver,
			@NonNull AtomicReference<Throwable> failure) {
		return new Thread(() -> runCapturing(failure,
				resolver::getResourceMethods), "lazy-resolver-public-owner");
	}

	private static void runCapturing(@NonNull AtomicReference<Throwable> failure,
			@NonNull Runnable operation) {
		try {
			operation.run();
		} catch (Throwable throwable) {
			failure.set(throwable);
		}
	}

	private static void await(@NonNull CountDownLatch latch)
			throws InterruptedException {
		Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS),
				"Timed out awaiting test coordination latch");
	}

	private static void awaitUninterruptibly(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static void join(@NonNull Thread thread) throws InterruptedException {
		thread.join(TimeUnit.SECONDS.toMillis(10));
		Assertions.assertFalse(thread.isAlive(),
				() -> "Timed out joining " + thread.getName());
	}

	private static final class LazyResource {
		@GET("/lazy")
		public String get() {
			return "lazy";
		}
	}
}
