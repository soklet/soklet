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

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Failure evidence must never acquire an application-owned exception monitor. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
final class SokletFailureEvidenceMonitorTests {

	@Test
	void recordingCompetingFailureAndFreezingEvidenceDoesNotAcquireThrowableMonitor()
			throws Exception {
		RuntimeException primary = new RuntimeException("primary");
		RuntimeException secondary = new RuntimeException("secondary");
		InternalTerminationGroup group = new InternalTerminationGroup(
				new AdmissionFence(), () -> { }, new LifecycleWorkers());
		group.signalFailure(group.root(), primary);
		group.commit();
		CountDownLatch recordingEntered = new CountDownLatch(1);
		CountDownLatch recordingFinished = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread recorder = new Thread(() -> {
			recordingEntered.countDown();
			try {
				Assertions.assertTrue(group.tryRetainFailureBeforeFreeze(
						group.root(), primary, secondary));
				Assertions.assertFalse(group.tryRetainFailureBeforeFreeze(
						group.root(), primary, new RuntimeException("capped")));
				group.signalTerminated(group.root());
				InternalTerminationGroup.EvidenceSnapshot snapshot = group.freezeEvidence();
				Assertions.assertTrue(snapshot.barrierComplete());
				Assertions.assertEquals(2, snapshot.failures().size());
				Assertions.assertSame(primary, snapshot.failures().get(0));
				Assertions.assertSame(secondary, snapshot.failures().get(1));
				Assertions.assertFalse(group.tryRetainFailureBeforeFreeze(
						group.root(), primary, new RuntimeException("after freeze")));
				Assertions.assertSame(snapshot, group.freezeEvidence());
			} catch (Throwable thrown) {
				failure.set(thrown);
			} finally {
				recordingFinished.countDown();
			}
		}, "failure-evidence-monitor-recorder");
		recorder.setDaemon(true);
		try {
			synchronized (primary) {
				recorder.start();
				Assertions.assertTrue(recordingEntered.await(5, TimeUnit.SECONDS));
				Assertions.assertTrue(recordingFinished.await(5, TimeUnit.SECONDS),
						"Failure recording and evidence freeze must finish while the exception monitor is held");
				Assertions.assertNull(failure.get());
				Assertions.assertEquals(0, primary.getSuppressed().length,
						"Secondary evidence must not mutate an application-owned exception");
			}
		} finally {
			recorder.join(5_000);
			Assertions.assertFalse(recorder.isAlive(), "The evidence recorder did not finish");
		}
	}

	@Test
	void canceledStartupAndCatchUpFailureFinishWhilePrimaryExceptionMonitorIsHeld()
			throws Exception {
		RuntimeException primary = new RuntimeException("late HTTP startup failure");
		RuntimeException secondary = new RuntimeException("graceful catch-up failure");
		CountDownLatch startEntered = new CountDownLatch(1);
		CountDownLatch releaseStart = new CountDownLatch(1);
		CountDownLatch quiesceReturned = new CountDownLatch(1);
		CountDownLatch catchUpEntered = new CountDownLatch(1);
		CountDownLatch callerReturned = new CountDownLatch(1);
		AtomicReference<Throwable> startupFailure = new AtomicReference<>();
		List<Thread> launchedWorkers = new CopyOnWriteArrayList<>();
		LifecycleWorkers.Launcher launcher = (name, task) -> {
			Thread worker = new Thread(() -> {
				try {
					task.run();
				} finally {
					if (name.equals("lifecycle-quiesce-http"))
						quiesceReturned.countDown();
				}
			}, name);
			worker.setDaemon(true);
			launchedWorkers.add(worker);
			worker.start();
		};
		LifecycleWorkers workers = new LifecycleWorkers(launcher, launcher);
		HttpServer transport = new HttpServer() {
			private final TransportIdentity identity = TransportIdentity.create();

			@Override
			public TransportIdentity getTransportIdentity() {
				return this.identity;
			}

			@Override
			public TransportRuntime attach(HttpTransportAttachmentContext attachment,
					StartupContext startup) {
				TransportTerminationSignal terminated = attachment.getTransportTerminationSignal();
				return new TransportRuntime() {
					@Override
					public void start(StartupContext context) {
						startEntered.countDown();
						awaitGate(releaseStart);
						throw primary;
					}

					@Override
					public void shutdownGracefully(ShutdownContext context) {
						catchUpEntered.countDown();
						throw secondary;
					}

					@Override
					public void shutdownForcibly(ShutdownContext context) {
						terminated.signalTerminated();
					}
				};
			}
		};
		SokletConfig config = config(transport);
		// A passive owner supplies callbacks without claiming the tested transport.
		Soklet callbackOwner = Soklet.fromConfig(config(passiveTransport()));
		SokletDirectLifecycle core = new SokletDirectLifecycle(callbackOwner, config,
				new SokletFrameworkSetup(config), NanoClock.system(), workers);
		Thread caller = new Thread(() -> {
			try {
				core.start();
			} catch (Throwable failure) {
				startupFailure.set(failure);
			} finally {
				callerReturned.countDown();
			}
		}, "failure-evidence-monitor-startup");
		caller.setDaemon(true);
		try {
			synchronized (primary) {
				caller.start();
				Assertions.assertTrue(startEntered.await(5, TimeUnit.SECONDS));
				core.requestStartupCancellation();
				Assertions.assertTrue(quiesceReturned.await(5, TimeUnit.SECONDS),
						"The graceful phase must be retained behind the blocked startup call");
				releaseStart.countDown();
				Assertions.assertTrue(catchUpEntered.await(5, TimeUnit.SECONDS));
				Assertions.assertTrue(callerReturned.await(5, TimeUnit.SECONDS),
						"Canceled startup must finish while the application holds its exception monitor");
				SokletStartupException canceled = Assertions.assertInstanceOf(
						SokletStartupException.class, startupFailure.get());
				ShutdownResult result = core.awaitPublicCompletion();
				Assertions.assertEquals(StartupDisposition.CANCELED, result.getStartupDisposition());
				Assertions.assertSame(result, canceled.getShutdownResult());
				Assertions.assertTrue(result.isComplete());
				List<Throwable> failures = result.internalResult()
						.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow().failures();
				Assertions.assertEquals(2, failures.size());
				Assertions.assertSame(primary, failures.get(0));
				Assertions.assertSame(secondary, failures.get(1));
				Assertions.assertEquals(0, primary.getSuppressed().length,
						"Terminal evidence must retain both exact failures without mutating either exception");
			}
		} finally {
			releaseStart.countDown();
			core.requestShutdownIntent();
			core.awaitCompletion();
			callbackOwner.requestShutdown();
			callbackOwner.awaitCoreShutdown();
			caller.join(5_000);
			Assertions.assertFalse(caller.isAlive(), "The canceled startup caller did not finish");
			long reapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			for (Thread worker : launchedWorkers) {
				long remainingNanos = reapDeadline - System.nanoTime();
				if (remainingNanos > 0L)
					TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos);
				Assertions.assertFalse(worker.isAlive(), () -> worker.getName() + " did not finish");
			}
		}
	}

	private static SokletConfig config(HttpServer transport) {
		return SokletConfig.withHttpServer(transport)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecycleObservers(Set.of())
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofMillis(100))
						.gracefulShutdownTimeout(Duration.ofMillis(300))
						.forcedShutdownTimeout(Duration.ofMillis(300)).build())
				.build();
	}

	private static HttpServer passiveTransport() {
		return new HttpServer() {
			private final TransportIdentity identity = TransportIdentity.create();

			@Override
			public TransportIdentity getTransportIdentity() {
				return this.identity;
			}

			@Override
			public TransportRuntime attach(HttpTransportAttachmentContext attachment,
					StartupContext startup) {
				TransportTerminationSignal terminated = attachment.getTransportTerminationSignal();
				return new TransportRuntime() {
					@Override public void start(StartupContext context) { }
					@Override public void shutdownGracefully(ShutdownContext context) { terminated.signalTerminated(); }
					@Override public void shutdownForcibly(ShutdownContext context) { terminated.signalTerminated(); }
				};
			}
		};
	}

	private static void awaitGate(CountDownLatch gate) {
		boolean interrupted = false;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		try {
			for (;;) {
				try {
					long remainingNanos = deadline - System.nanoTime();
					if (!gate.await(Math.max(0L, remainingNanos), TimeUnit.NANOSECONDS))
						throw new AssertionError("The startup return gate was not released");
					return;
				} catch (InterruptedException interruption) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	public static final class Resource {
		@GET("/failure-monitor")
		public String get() {
			return "ready";
		}
	}
}
