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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Public scoped runs own finalization without lending lifecycle control to the body. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
final class SokletScopedRunTests {

	@Test
	void callbackRunsOnceOnCallerAfterReadinessAndReturnsTheFullCleanupResult() {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		Thread caller = Thread.currentThread();
		AtomicInteger callbacks = new AtomicInteger();
		AtomicReference<ShutdownResult> cleanupResult = new AtomicReference<>();
		ShutdownCleanup cleanup = cleanup(result -> {
			Assertions.assertEquals(1, transport.gracefulCalls.get());
			transport.events.add("cleanup");
			cleanupResult.set(result);
		});

		ShutdownResult result = Soklet.run(config(transport), cleanup, () -> {
			Assertions.assertSame(caller, Thread.currentThread());
			Assertions.assertTrue(transport.started.get());
			Assertions.assertEquals(0, transport.gracefulCalls.get());
			callbacks.incrementAndGet();
			transport.events.add("body");
		});

		Assertions.assertEquals(1, callbacks.get());
		Assertions.assertSame(cleanupResult.get(), result);
		Assertions.assertEquals(StartupDisposition.READY, result.getStartupDisposition());
		Assertions.assertTrue(result.isComplete());
		Assertions.assertEquals(List.of("start", "body", "graceful", "cleanup"), transport.events);
	}

	@Test
	void callbackWithoutCheckedFailureRequiresNoCheckedThrowsDeclaration() {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		ShutdownResult result = Soklet.run(config(transport), () -> {
			Assertions.assertTrue(transport.started.get());
		});
		Assertions.assertTrue(result.isComplete());
		Assertions.assertEquals(1, transport.gracefulCalls.get());
	}

	@Test
	void checkedCallbackFailureRetainsItsExactTypeAndIdentityAfterCleanup() {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		IOException original = new IOException("callback failed");
		AtomicBoolean cleaned = new AtomicBoolean();
		IOException observed = Assertions.assertThrows(IOException.class,
				() -> runChecked(config(transport), cleanup(result -> cleaned.set(true)), original));
		Assertions.assertSame(original, observed);
		Assertions.assertTrue(cleaned.get());
		Assertions.assertEquals(1, transport.gracefulCalls.get());
		Assertions.assertEquals(0, original.getSuppressed().length);
	}

	@Test
	void callbackAssertionErrorRemainsPrimaryWhenApplicationCleanupAlsoFails() {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		AssertionError original = new AssertionError("test body failed");
		IOException cleanupCause = new IOException("cleanup failed");
		AssertionError observed = Assertions.assertThrows(AssertionError.class,
				() -> Soklet.run(config(transport), cleanup(result -> { throw cleanupCause; }),
						() -> { throw original; }));
		Assertions.assertSame(original, observed);
		Assertions.assertEquals(1, observed.getSuppressed().length);
		SokletShutdownCleanupException teardown = Assertions.assertInstanceOf(
				SokletShutdownCleanupException.class, observed.getSuppressed()[0]);
		Assertions.assertSame(cleanupCause, teardown.getCause());
		Assertions.assertTrue(teardown.getShutdownResult().isComplete());
		Assertions.assertEquals(1, transport.gracefulCalls.get());
	}

	@Test
	void successfulBodyExposesTheExistingCleanupFailure() {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		IOException original = new IOException("cleanup failed");
		AtomicInteger callbacks = new AtomicInteger();
		SokletShutdownCleanupException failure = Assertions.assertThrows(
				SokletShutdownCleanupException.class,
				() -> Soklet.run(config(transport), cleanup(result -> { throw original; }),
						callbacks::incrementAndGet));
		Assertions.assertEquals(1, callbacks.get());
		Assertions.assertSame(original, failure.getCause());
		Assertions.assertTrue(failure.getShutdownResult().isComplete());
	}

	@Test
	void incompleteShutdownThrowsAndSkipsApplicationCleanup() {
		TestTransport transport = new TestTransport(Proof.NEVER);
		AtomicBoolean cleaned = new AtomicBoolean();
		try {
			SokletShutdownIncompleteException failure = Assertions.assertThrows(
					SokletShutdownIncompleteException.class,
					() -> Soklet.run(config(transport), cleanup(result -> cleaned.set(true)), () -> { }));
			Assertions.assertEquals(StartupDisposition.READY,
					failure.getShutdownResult().getStartupDisposition());
			Assertions.assertEquals(ShutdownDisposition.INCOMPLETE,
					failure.getShutdownResult().getShutdownDisposition());
			Assertions.assertFalse(cleaned.get());
		} finally {
			transport.proveTermination();
		}
	}

	@Test
	void callbackFailureRemainsPrimaryWhenShutdownIsIncomplete() {
		TestTransport transport = new TestTransport(Proof.NEVER);
		IOException original = new IOException("callback failed");
		try {
			IOException observed = Assertions.assertThrows(IOException.class,
					() -> Soklet.run(config(transport), () -> { throw original; }));
			Assertions.assertSame(original, observed);
			Assertions.assertEquals(1, observed.getSuppressed().length);
			Assertions.assertInstanceOf(SokletShutdownIncompleteException.class,
					observed.getSuppressed()[0]);
		} finally {
			transport.proveTermination();
		}
	}

	@Test
	void completeForcedShutdownCanReturnNormally() {
		TestTransport transport = new TestTransport(Proof.FORCED);
		ShutdownResult result = Soklet.run(config(transport), () -> { });
		Assertions.assertTrue(result.isComplete());
		Assertions.assertEquals(ShutdownDisposition.FORCED, result.getShutdownDisposition());
		Assertions.assertEquals(1, transport.forcedCalls.get());
	}

	@Test
	void startupFailureSkipsTheCallbackAndFinishesEligibleCleanup() {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		RuntimeException original = new IllegalStateException("startup failed");
		transport.startFailure = original;
		AtomicBoolean entered = new AtomicBoolean();
		AtomicBoolean cleaned = new AtomicBoolean();
		SokletStartupException failure = Assertions.assertThrows(SokletStartupException.class,
				() -> Soklet.run(config(transport), cleanup(result -> cleaned.set(true)),
						() -> entered.set(true)));
		Assertions.assertSame(original, failure.getCause());
		Assertions.assertEquals(StartupDisposition.FAILED, failure.getStartupDisposition());
		Assertions.assertFalse(entered.get());
		Assertions.assertTrue(cleaned.get());
	}

	@Test
	void invalidScopedArgumentsDoNotClaimTheTransport() {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		SokletConfig config = config(transport);
		Soklet.RunAction<RuntimeException> action = () -> { };
		ShutdownCleanup cleanup = cleanup(result -> { });
		Assertions.assertThrows(NullPointerException.class,
				() -> Soklet.run(null, action));
		Assertions.assertThrows(NullPointerException.class,
				() -> Soklet.run(config, (Soklet.RunAction<RuntimeException>) null));
		Assertions.assertThrows(NullPointerException.class,
				() -> Soklet.run(config, (ShutdownCleanup) null, action));
		Assertions.assertThrows(NullPointerException.class,
				() -> Soklet.run(config, cleanup, (Soklet.RunAction<RuntimeException>) null));
		Assertions.assertEquals(0, transport.startCalls.get());
		Assertions.assertTrue(Soklet.run(config, action).isComplete(),
				"Rejected arguments must leave the original transport available");
		Assertions.assertEquals(1, transport.startCalls.get());
	}

	@Test
	void callbackInterruptFlagIsRestoredAfterEligibleCleanup() {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		AtomicBoolean cleaned = new AtomicBoolean();
		try {
			ShutdownResult result = Soklet.run(config(transport), cleanup(terminal -> cleaned.set(true)),
					() -> Thread.currentThread().interrupt());
			Assertions.assertTrue(result.isComplete());
			Assertions.assertTrue(cleaned.get());
			Assertions.assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void interruptionWhileJoiningCleanupCannotEndTheScopeEarly() throws Exception {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		CountDownLatch cleanupEntered = new CountDownLatch(1);
		CountDownLatch releaseCleanup = new CountDownLatch(1);
		CountDownLatch returned = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicBoolean restored = new AtomicBoolean();
		Thread caller = new Thread(() -> {
			try {
				Soklet.run(config(transport), cleanup(result -> {
					cleanupEntered.countDown();
					awaitGate(releaseCleanup);
				}), () -> { });
			} catch (Throwable thrown) {
				failure.set(thrown);
			} finally {
				restored.set(Thread.currentThread().isInterrupted());
				returned.countDown();
			}
		}, "scoped-run-interrupted-finalization");
		caller.setDaemon(true);
		try {
			caller.start();
			Assertions.assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
			caller.interrupt();
			Assertions.assertEquals(1L, returned.getCount(),
					"The scope cannot return before the held cleanup completes");
			releaseCleanup.countDown();
			Assertions.assertTrue(returned.await(5, TimeUnit.SECONDS));
			Assertions.assertNull(failure.get());
			Assertions.assertTrue(restored.get());
		} finally {
			releaseCleanup.countDown();
			caller.join(5_000);
			Assertions.assertFalse(caller.isAlive());
		}
	}

	@Test
	void preInterruptedStartupSkipsTheCallbackAndRestoresInterruption() throws Exception {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		AtomicBoolean entered = new AtomicBoolean();
		AtomicBoolean restored = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread caller = new Thread(() -> {
			Thread.currentThread().interrupt();
			try {
				Soklet.run(config(transport), () -> entered.set(true));
			} catch (Throwable thrown) {
				failure.set(thrown);
			} finally {
				restored.set(Thread.currentThread().isInterrupted());
			}
		}, "scoped-run-pre-interrupted-startup");
		caller.setDaemon(true);
		caller.start();
		caller.join(5_000);
		Assertions.assertFalse(caller.isAlive());
		SokletStartupException canceled = Assertions.assertInstanceOf(
				SokletStartupException.class, failure.get());
		Assertions.assertEquals(StartupDisposition.CANCELED, canceled.getStartupDisposition());
		Assertions.assertFalse(entered.get());
		Assertions.assertTrue(restored.get());
	}

	@Test
	void externalShutdownDoesNotInterruptOrEndTheCallback() throws Exception {
		TestTransport transport = new TestTransport(Proof.GRACEFUL);
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch releaseCallback = new CountDownLatch(1);
		CountDownLatch cleaned = new CountDownLatch(1);
		CountDownLatch returned = new CountDownLatch(1);
		AtomicBoolean callbackInterrupted = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread caller = new Thread(() -> {
			try {
				Soklet.run(config(transport), cleanup(result -> cleaned.countDown()), () -> {
					callbackEntered.countDown();
					Assertions.assertTrue(releaseCallback.await(5, TimeUnit.SECONDS));
					callbackInterrupted.set(Thread.currentThread().isInterrupted());
				});
			} catch (Throwable thrown) {
				failure.set(thrown);
			} finally {
				returned.countDown();
			}
		}, "scoped-run-external-termination");
		caller.setDaemon(true);
		try {
			caller.start();
			Assertions.assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
			transport.proveTermination();
			Assertions.assertTrue(cleaned.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(1L, returned.getCount(),
					"External termination cannot make scoped run skip the live callback");
			releaseCallback.countDown();
			Assertions.assertTrue(returned.await(5, TimeUnit.SECONDS));
			Assertions.assertFalse(callbackInterrupted.get());
			SokletUnexpectedTerminationException unexpected = Assertions.assertInstanceOf(
					SokletUnexpectedTerminationException.class, failure.get());
			Assertions.assertTrue(unexpected.getShutdownResult().isComplete());
		} finally {
			releaseCallback.countDown();
			caller.join(5_000);
			Assertions.assertFalse(caller.isAlive());
		}
	}

	private static void runChecked(SokletConfig config, ShutdownCleanup cleanup,
			IOException failure) throws IOException {
		Soklet.run(config, cleanup, () -> { throw failure; });
	}

	private static ShutdownCleanup cleanup(ShutdownCleanup.Action action) {
		return ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(5), action);
	}

	private static SokletConfig config(HttpServer transport) {
		return SokletConfig.withHttpServer(transport)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecycleObservers(Set.of())
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofMillis(100))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.build();
	}

	private static void awaitGate(CountDownLatch gate) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					if (!gate.await(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
						throw new AssertionError("The scoped run gate was not released");
					return;
				} catch (InterruptedException ignored) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private enum Proof { GRACEFUL, FORCED, NEVER }

	private static final class TestTransport implements HttpServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private final Proof proof;
		private final AtomicBoolean started = new AtomicBoolean();
		private final AtomicInteger startCalls = new AtomicInteger();
		private final AtomicInteger gracefulCalls = new AtomicInteger();
		private final AtomicInteger forcedCalls = new AtomicInteger();
		private final List<String> events = new CopyOnWriteArrayList<>();
		private volatile TransportTerminationSignal signal;
		private RuntimeException startFailure;

		private TestTransport(Proof proof) {
			this.proof = proof;
		}

		@Override
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		public TransportRuntime attach(HttpTransportAttachmentContext attachment,
				StartupContext startup) {
			this.signal = attachment.getTransportTerminationSignal();
			return new TransportRuntime() {
				@Override
				public void start(StartupContext context) {
					startCalls.incrementAndGet();
					events.add("start");
					if (startFailure != null)
						throw startFailure;
					started.set(true);
				}

				@Override
				public void shutdownGracefully(ShutdownContext context) {
					gracefulCalls.incrementAndGet();
					events.add("graceful");
					if (proof == Proof.GRACEFUL)
						proveTermination();
				}

				@Override
				public void shutdownForcibly(ShutdownContext context) {
					forcedCalls.incrementAndGet();
					events.add("forced");
					if (proof != Proof.NEVER)
						proveTermination();
				}
			};
		}

		private void proveTermination() {
			TransportTerminationSignal observed = this.signal;
			if (observed != null)
				observed.signalTerminated();
		}
	}

	public static final class Resource {
		@GET("/scoped")
		public String get() {
			return "ready";
		}
	}
}
