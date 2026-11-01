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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.Permission;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Regressions for bounded process shutdown and completion callback isolation. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletManagedShutdownRegressionTests {
	@TempDir
	Path directory;

	@Test
	void blockingAsyncCallbackCannotRetainJvmWhenShutdownCannotCreateThreads()
			throws Exception {
		// Thread-creation denial is confined to a fork and available only on the
		// supported runtimes that still permit installing a SecurityManager.
		Assumptions.assumeTrue(Runtime.version().feature() < 24);
		Path callback = this.directory.resolve("callback-entered");
		Path stopped = this.directory.resolve("callback-stopped");
		Path output = this.directory.resolve("callback.log");
		Process child = child("handoff", callback, stopped, output);
		try {
			awaitMarker(child, callback, output);
			Assertions.assertEquals("daemon=true", Files.readString(callback),
					"Completion consumers must never execute on the process owner");
			Assertions.assertTrue(child.waitFor(10, TimeUnit.SECONDS),
					() -> "A blocked async callback retained JVM liveness: " + childOutput(output));
			Assertions.assertEquals(0, child.exitValue(), () -> childOutput(output));
			Assertions.assertEquals("complete", Files.readString(stopped));
		} finally {
			terminate(child);
		}
	}

	@Test
	void completionReservesItsDaemonDispatcherBeforeShutdown() throws Exception {
		AtomicInteger launches = new AtomicInteger();
		AtomicReference<Thread> dispatcher = new AtomicReference<>();
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch releaseCallback = new CountDownLatch(1);
		RuntimeException terminalFailure = new RuntimeException("expected terminal failure");
		SokletRunCompletion completion = new SokletRunCompletion((name, task) -> {
			Assertions.assertEquals(1, launches.incrementAndGet(),
					"Shutdown must not need to launch another handoff thread");
			Thread thread = new Thread(task, name);
			thread.setDaemon(true);
			dispatcher.set(thread);
			thread.start();
		});
		Assertions.assertEquals(1, launches.get());
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		completion.async().whenComplete((result, failure) -> {
			callbackThread.set(Thread.currentThread());
			callbackEntered.countDown();
			awaitUninterruptibly(releaseCallback);
		});
		try {
			completion.finish(null, terminalFailure);
			Assertions.assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertSame(terminalFailure,
					Assertions.assertThrows(RuntimeException.class, completion::await));
			Assertions.assertSame(dispatcher.get(), callbackThread.get());
			Assertions.assertTrue(callbackThread.get().isDaemon());
			Assertions.assertEquals(1, launches.get());
		} finally {
			releaseCallback.countDown();
			dispatcher.get().join(5_000);
		}
		Assertions.assertFalse(dispatcher.get().isAlive());
	}

	@Test
	void dispatcherReservationFailureDoesNotClaimTransportsOrLaunchOwner()
			throws Exception {
		AtomicInteger identityReads = new AtomicInteger();
		AtomicInteger ownerLaunches = new AtomicInteger();
		SokletConfig configuration = config(transport(identityReads), Set.of());
		identityReads.set(0);
		OutOfMemoryError launchFailure = new OutOfMemoryError("test dispatcher launch failure");
		Assertions.assertSame(launchFailure, Assertions.assertThrows(OutOfMemoryError.class,
				() -> SokletProcessLifecycle.launch(configuration, null,
						task -> {
							ownerLaunches.incrementAndGet();
							return new Thread(task);
						}, (name, task) -> { throw launchFailure; })));
		Assertions.assertEquals(0, ownerLaunches.get());
		Assertions.assertEquals(0, identityReads.get());
		Soklet soklet = Soklet.run(configuration);
		try {
			Assertions.assertTrue(identityReads.get() > 0);
		} finally {
			soklet.requestShutdown();
			Assertions.assertTrue(soklet.awaitShutdown().isComplete());
		}
	}

	@Test
	void ownerFactoryFailureReleasesReservedDispatcherWithoutClaimingTransports()
			throws Exception {
		assertOwnerLaunchFailure(false);
	}

	@Test
	void ownerThreadStartFailureReleasesReservedDispatcherWithoutClaimingTransports()
			throws Exception {
		assertOwnerLaunchFailure(true);
	}

	@Test
	void cancelledStartupReleasesReservedDispatcher() throws Exception {
		SokletConfig configuration = config(transport(new AtomicInteger()), Set.of());
		AtomicReference<Thread> dispatcher = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread caller = new Thread(() -> {
			Thread.currentThread().interrupt();
			try {
				Soklet unexpected = SokletProcessLifecycle.launch(configuration, null,
						task -> new Thread(task, "cancelled-test-process-owner"), (name, task) -> {
							Thread thread = new Thread(task, name);
							thread.setDaemon(true);
							dispatcher.set(thread);
							thread.start();
						});
				SokletTestScope.close(unexpected);
				failure.set(new AssertionError("Interrupted startup returned a ready handle"));
			} catch (Throwable thrown) {
				failure.set(thrown);
			}
		}, "cancelled-test-startup-caller");
		caller.setDaemon(true);
		caller.start();
		caller.join(10_000);
		Assertions.assertFalse(caller.isAlive());
		SokletStartupException startup = Assertions.assertInstanceOf(
				SokletStartupException.class, failure.get());
		Assertions.assertEquals(StartupDisposition.CANCELED, startup.getStartupDisposition());
		Assertions.assertTrue(startup.getShutdownResult().isComplete());
		dispatcher.get().join(5_000);
		Assertions.assertFalse(dispatcher.get().isAlive());
	}

	private static void assertOwnerLaunchFailure(boolean failAtStart) throws Exception {
		AtomicInteger identityReads = new AtomicInteger();
		SokletConfig configuration = config(transport(identityReads), Set.of());
		identityReads.set(0);
		AtomicReference<Thread> dispatcher = new AtomicReference<>();
		RuntimeException launchFailure = new RuntimeException("test owner launch failure");
		Assertions.assertSame(launchFailure, Assertions.assertThrows(RuntimeException.class,
				() -> SokletProcessLifecycle.launch(configuration, null,
						task -> {
							if (!failAtStart)
								throw launchFailure;
							return new Thread(task) {
								@Override
								public synchronized void start() {
									throw launchFailure;
								}
							};
						}, (name, task) -> {
							Thread thread = new Thread(task, name);
							thread.setDaemon(true);
							dispatcher.set(thread);
							thread.start();
						})));
		dispatcher.get().join(5_000);
		Assertions.assertFalse(dispatcher.get().isAlive(),
				"Failed ownership launch must not strand the reserved dispatcher");
		Assertions.assertEquals(0, identityReads.get());
		Soklet soklet = Soklet.run(configuration);
		try {
			Assertions.assertTrue(identityReads.get() > 0);
		} finally {
			soklet.requestShutdown();
			Assertions.assertTrue(soklet.awaitShutdown().isComplete());
		}
	}

	private static Process child(String mode, Path entered, Path completed, Path output)
			throws Exception {
		ProcessBuilder builder = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				Child.class.getName(), mode, entered.toString(), completed.toString());
		if (mode.equals("handoff"))
			builder.command().add(1, "-Djava.security.manager=allow");
		return builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
	}

	private static void awaitMarker(Process child, Path marker, Path output) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!Files.exists(marker) && child.isAlive() && System.nanoTime() < deadline)
			Thread.sleep(10);
		Assertions.assertTrue(Files.exists(marker), () -> childOutput(output));
	}

	private static void terminate(Process child) throws Exception {
		if (child.isAlive()) {
			child.destroyForcibly();
			Assertions.assertTrue(child.waitFor(5, TimeUnit.SECONDS));
		}
	}

	private static String childOutput(Path output) {
		try {
			return Files.readString(output);
		} catch (Exception failure) {
			return failure.toString();
		}
	}

	private static SokletConfig config(HttpServer transport, Set<LifecycleObserver> observers) {
		return SokletConfig.withHttpServer(transport)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecycleObservers(observers)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(1))
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.build();
	}

	private static HttpServer transport(AtomicInteger identityReads) {
		return new HttpServer() {
			private final TransportIdentity identity = TransportIdentity.create();

			@Override
			public TransportIdentity getTransportIdentity() {
				identityReads.incrementAndGet();
				return this.identity;
			}

			@Override
			public TransportRuntime attach(HttpTransportAttachmentContext attachment,
					StartupContext startup) {
				TransportTerminationSignal terminated = attachment.getTransportTerminationSignal();
				return new TransportRuntime() {
					@Override
					public void start(StartupContext context) {
					}

					@Override
					public void shutdownGracefully(ShutdownContext context) {
						terminated.signalTerminated();
					}

					@Override
					public void shutdownForcibly(ShutdownContext context) {
						terminated.signalTerminated();
					}
				};
			}
		};
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException interruption) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	public static final class Resource {
		@GET("/ready")
		public String ready() {
			return "ready";
		}
	}

	public static final class Child {
		@SuppressWarnings("removal")
		public static void main(String[] args) throws Exception {
			Path entered = Path.of(args[1]);
			Path completed = Path.of(args[2]);


			Soklet soklet = Soklet.run(config(transport(new AtomicInteger()), Set.of()));
			soklet.awaitShutdownAsync().whenComplete((result, failure) -> {
				try {
					writeMarker(entered, "daemon=" + Thread.currentThread().isDaemon());
				} catch (Exception markerFailure) {
					throw new IllegalStateException(markerFailure);
				}
				awaitUninterruptibly(new CountDownLatch(1));
			});
			System.setSecurityManager(new SecurityManager() {
				@Override
				public void checkPermission(Permission permission) {
				}

				private boolean publishingCompletion() {
					return Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
							frame.getClassName().equals(SokletRunCompletion.class.getName())
									&& frame.getMethodName().equals("finish"));
				}

				@Override
				public void checkAccess(ThreadGroup group) {
					if (publishingCompletion())
						throw new SecurityException("test denies completion-time thread creation");
				}

				@Override
				public void checkAccess(Thread thread) {
					if (publishingCompletion())
						throw new SecurityException("test denies completion-time thread creation");
				}
			});
			try {
				soklet.requestShutdown();
				ShutdownResult result = soklet.awaitShutdown();
				writeMarker(completed, result.isComplete() ? "complete" : "incomplete");
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (!Files.exists(entered) && System.nanoTime() < deadline)
					Thread.sleep(10);
				if (!Files.exists(entered))
					throw new AssertionError("Completion callback was not published");
			} finally {
				System.setSecurityManager(null);
			}
			// The blocked completion callback is deliberately left alive. It must
			// be daemon-owned so returning from main ends the process naturally.
		}

		private static void writeMarker(Path target, String text) throws Exception {
			Path pending = target.resolveSibling(target.getFileName() + ".pending");
			Files.writeString(pending, text);
			Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
		}
	}
}
