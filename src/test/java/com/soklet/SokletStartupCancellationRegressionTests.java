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
import java.security.Permission;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic cancellation coverage for denied owner and worker interruption. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletStartupCancellationRegressionTests {
	@TempDir
	Path directory;

	@Test
	void startupCancellationDoesNotDependOnInterruptingTheProcessOwner() throws Exception {
		assertCancellation(Mode.DENIED_OWNER_INTERRUPT);
	}

	@Test
	void securityManagerDenialCannotAbandonAStartingLifecycle() throws Exception {
		// Keep VM-global permissions isolated, and retain the portable companion
		// above on runtimes that no longer permit installing a SecurityManager.
		Assumptions.assumeTrue(Runtime.version().feature() < 24);
		assertChild("security-manager");
	}

	@Test
	void deniedStartupWorkerInterruptionStillPublishesBoundedIncompleteCancellation() throws Exception {
		Assumptions.assumeTrue(Runtime.version().feature() < 24);
		assertChild("denied-worker");
	}

	private void assertChild(String mode) throws Exception {
		Path output = this.directory.resolve(mode + ".log");
		ProcessBuilder builder = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				Child.class.getName(), mode);
		if (mode.equals("security-manager") || mode.equals("denied-worker"))
			builder.command().add(1, "-Djava.security.manager=allow");
		Process child = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try {
			Assertions.assertTrue(child.waitFor(25, TimeUnit.SECONDS),
					() -> "Startup cancellation child did not terminate: " + childOutput(output));
			Assertions.assertEquals(0, child.exitValue(), () -> childOutput(output));
		} finally {
			if (child.isAlive()) {
				child.destroyForcibly();
				Assertions.assertTrue(child.waitFor(5, TimeUnit.SECONDS));
			}
		}
	}

	@SuppressWarnings("removal")
	private static void assertCancellation(Mode mode) throws Exception {
		CountDownLatch callerReturned = new CountDownLatch(1);
		CountDownLatch stopped = new CountDownLatch(1);
		AtomicReference<Thread> ownerThread = new AtomicReference<>();
		AtomicReference<Thread> handoffThread = new AtomicReference<>();
		AtomicReference<Thread> startupWorker = new AtomicReference<>();
		AtomicReference<Soklet> unexpectedHandle = new AtomicReference<>();
		AtomicReference<Throwable> startupFailure = new AtomicReference<>();
		AtomicReference<ShutdownResult> cleanupResult = new AtomicReference<>();
		AtomicBoolean callerInterruptRestored = new AtomicBoolean();
		AtomicBoolean denyOwnerAccess = new AtomicBoolean();
		GateTransport transport = new GateTransport();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didStopSoklet(ShutdownResult result) {
				stopped.countDown();
			}
		};
		SokletConfig config = SokletConfig.withHttpServer(transport)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecycleObservers(Set.of(observer))
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(30))
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.build();
		ShutdownCleanup cleanup = ShutdownCleanup.fromTimeoutAndAction(
				Duration.ofSeconds(1), cleanupResult::set);
		if (mode == Mode.SECURITY_MANAGER || mode == Mode.DENIED_WORKER_INTERRUPT)
			System.setSecurityManager(new SecurityManager() {
				@Override
				public void checkPermission(Permission permission) {
				}

				@Override
				public void checkAccess(Thread thread) {
					// Activate only after start has entered, so construction, daemon
					// policy and launch all succeed before thread access is denied.
					if (denyOwnerAccess.get()
							&& ((mode == Mode.SECURITY_MANAGER
									&& thread.getName().equals("soklet-process-lifecycle"))
									|| (mode == Mode.DENIED_WORKER_INTERRUPT
											&& thread.getName().equals("soklet-start-http"))))
						throw new SecurityException("test denies interrupting a lifecycle thread");
				}
			});
		Thread caller = new Thread(() -> {
			try {
				Soklet soklet;
				if (mode == Mode.DENIED_OWNER_INTERRUPT) {
					soklet = SokletProcessLifecycle.launch(config, cleanup, task -> {
						Thread owner = new Thread(task, "denied-interrupt-test-process-owner") {
							@Override
							public void interrupt() {
								throw new SecurityException("test denies interrupting the process owner");
							}
						};
						ownerThread.set(owner);
						return owner;
					}, SokletRunCompletion::launchDaemonHandoff);
				} else {
					soklet = Soklet.run(config, cleanup);
				}
				unexpectedHandle.set(soklet);
			} catch (Throwable failure) {
				startupFailure.set(failure);
			} finally {
				callerInterruptRestored.set(Thread.currentThread().isInterrupted());
				callerReturned.countDown();
			}
		}, "startup-cancellation-regression-caller");
		caller.setDaemon(true);
		caller.start();
		try {
			Assertions.assertTrue(transport.startEntered.await(5, TimeUnit.SECONDS),
					"Transport startup did not enter");
			if (mode == Mode.DENIED_WORKER_INTERRUPT) {
				for (Thread thread : Thread.getAllStackTraces().keySet()) {
					if (thread.getName().equals("soklet-process-lifecycle"))
						ownerThread.set(thread);
					if (thread.getName().equals("soklet-finalization-result-handoff"))
						handoffThread.set(thread);
					if (thread.getName().equals("soklet-start-http"))
						startupWorker.set(thread);
				}
				Assertions.assertNotNull(ownerThread.get());
				Assertions.assertNotNull(handoffThread.get());
				Assertions.assertNotNull(startupWorker.get());
			}
			denyOwnerAccess.set(true);
			caller.interrupt();
			Assertions.assertTrue(callerReturned.await(5, TimeUnit.SECONDS),
					"Interrupted startup must settle before either application gate is released");
			Assertions.assertNull(unexpectedHandle.get(), "Cancellation returned a ready handle");
			SokletStartupException canceled = Assertions.assertInstanceOf(
					SokletStartupException.class, startupFailure.get());
			Assertions.assertEquals(StartupDisposition.CANCELED, canceled.getStartupDisposition());
			if (mode == Mode.DENIED_WORKER_INTERRUPT) {
				Assertions.assertFalse(canceled.getShutdownResult().isComplete(),
						"A worker that could not be interrupted must remain residual activity");
				Assertions.assertNull(cleanupResult.get(),
						"Incomplete core shutdown must skip application cleanup");
				Assertions.assertTrue(startupWorker.get().isAlive());
				Assertions.assertTrue(startupWorker.get().isDaemon());
				ownerThread.get().join(5_000);
				Assertions.assertFalse(ownerThread.get().isAlive(),
						"Full finalization must retire the owner before the worker gate is released");
				handoffThread.get().join(5_000);
				Assertions.assertFalse(handoffThread.get().isAlive(),
						"Exceptional completion must retire its reserved handoff");
			} else {
				Assertions.assertTrue(canceled.getShutdownResult().isComplete());
				Assertions.assertSame(canceled.getShutdownResult(), cleanupResult.get(),
						"run must wait for cleanup before reporting startup cancellation");
			}
			Assertions.assertTrue(callerInterruptRestored.get());
			Assertions.assertEquals(1L, transport.releaseStart.getCount());

		} finally {
			denyOwnerAccess.set(false);
			if (mode == Mode.SECURITY_MANAGER || mode == Mode.DENIED_WORKER_INTERRUPT)
				System.setSecurityManager(null);
			// These releases also retire the old implementation's orphaned owner
			// when a regression assertion fails, keeping the test process usable.
			transport.abort();
			caller.interrupt();
			Assertions.assertTrue(callerReturned.await(5, TimeUnit.SECONDS));
			Soklet handle = unexpectedHandle.get();
			if (handle != null) {
				handle.requestShutdown();
				try {
					handle.awaitShutdown();
				} catch (SokletLifecycleException ignored) {
					// A failure from emergency retirement must not mask the regression.
				}
			}
			Assertions.assertTrue(stopped.await(5, TimeUnit.SECONDS),
					"The transport owner did not finish after emergency retirement");
			caller.join(5_000);
			Assertions.assertFalse(caller.isAlive());
			Thread owner = ownerThread.get();
			if (owner != null) {
				owner.join(5_000);
				Assertions.assertFalse(owner.isAlive());
			}
			Thread worker = startupWorker.get();
			if (worker != null) {
				worker.join(5_000);
				Assertions.assertFalse(worker.isAlive());
			}
		}
	}

	private static String childOutput(Path output) {
		try {
			return Files.readString(output);
		} catch (Exception failure) {
			return failure.toString();
		}
	}

	private static void awaitUninterruptibly(CountDownLatch gate) {
		boolean interrupted = false;
		for (;;) {
			try {
				gate.await();
				break;
			} catch (InterruptedException interruption) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private enum Mode {
		DENIED_OWNER_INTERRUPT,
		SECURITY_MANAGER,
		DENIED_WORKER_INTERRUPT
	}

	private static final class GateTransport implements HttpServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private final CountDownLatch startEntered = new CountDownLatch(1);
		private final CountDownLatch releaseStart = new CountDownLatch(1);
		private final AtomicReference<TransportTerminationSignal> terminated = new AtomicReference<>();
		private final AtomicBoolean abortRequested = new AtomicBoolean();

		@Override
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		public TransportRuntime attach(HttpTransportAttachmentContext attachment,
				StartupContext startup) {
			TransportTerminationSignal signal = attachment.getTransportTerminationSignal();
			this.terminated.set(signal);
			if (this.abortRequested.get())
				signal.signalTerminated();
			return new TransportRuntime() {
				@Override
				public void start(StartupContext context) {
					startEntered.countDown();
					try {
						releaseStart.await();
					} catch (InterruptedException interruption) {
						Thread.currentThread().interrupt();
					}
				}

				@Override
				public void shutdownGracefully(ShutdownContext context) {
					signal.signalTerminated();
				}

				@Override
				public void shutdownForcibly(ShutdownContext context) {
					signal.signalTerminated();
				}
			};
		}

		void abort() {
			this.abortRequested.set(true);
			this.releaseStart.countDown();
			TransportTerminationSignal signal = this.terminated.get();
			if (signal != null)
				signal.signalTerminated();
		}
	}

	public static final class Resource {
		@GET("/ready")
		public String ready() {
			return "ready";
		}
	}

	public static final class Child {
		public static void main(String[] args) throws Exception {
			if (args[0].equals("denied-worker")) {
				assertCancellation(Mode.DENIED_WORKER_INTERRUPT);
			} else {
				assertCancellation(Mode.SECURITY_MANAGER);
			}
		}
	}
}
