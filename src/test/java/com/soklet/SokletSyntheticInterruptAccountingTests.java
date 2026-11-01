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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A synthetic startup action can finish before its separately owned interruption. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
final class SokletSyntheticInterruptAccountingTests {

	@Test
	void completedSetupRetainsBlockedInterruptDeliveryAndSkipsCleanup() throws Exception {
		assertSyntheticDeliveryAccounting(Phase.SETUP, true);
	}

	@Test
	void completedAttachmentRetainsBlockedInterruptDeliveryAndSkipsCleanup() throws Exception {
		assertSyntheticDeliveryAccounting(Phase.ATTACHMENT, true);
	}

	@Test
	void retiredSetupInterruptDeliveryAllowsCompleteShutdownAndCleanup() throws Exception {
		assertSyntheticDeliveryAccounting(Phase.SETUP, false);
	}

	@Test
	void retiredAttachmentInterruptDeliveryAllowsCompleteShutdownAndCleanup() throws Exception {
		assertSyntheticDeliveryAccounting(Phase.ATTACHMENT, false);
	}

	@Test
	void setupReservationFailureDoesNotInventResidualStartupWork() throws Exception {
		assertReservationFailureAccounting(Phase.SETUP);
	}

	@Test
	void attachmentReservationFailureDoesNotInventResidualStartupWork() throws Exception {
		assertReservationFailureAccounting(Phase.ATTACHMENT);
	}

	private static void assertSyntheticDeliveryAccounting(Phase phase, boolean holdDelivery)
			throws Exception {
		CountDownLatch targetEntered = new CountDownLatch(1);
		CountDownLatch releaseTarget = new CountDownLatch(1);
		CountDownLatch deliveryEntered = new CountDownLatch(1);
		CountDownLatch releaseDelivery = new CountDownLatch(1);
		CountDownLatch outcomePaused = new CountDownLatch(1);
		CountDownLatch releaseOutcome = new CountDownLatch(1);
		CountDownLatch startReturned = new CountDownLatch(1);
		Map<String, Thread> workersByName = new ConcurrentHashMap<>();
		AtomicReference<Thread> deliveryThread = new AtomicReference<>();
		AtomicReference<Throwable> startupFailure = new AtomicReference<>();
		AtomicInteger cleanupCalls = new AtomicInteger();
		String targetName = phase == Phase.SETUP ? "soklet-framework-setup" : "soklet-attach-http";
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			Thread worker = name.equals(targetName) ? new Thread(task, name) {
				@Override
				public void interrupt() {
					// Model a permission check or other interrupt-delivery work that
					// runs before setting the target's interrupt flag. The target can
					// return while its reserved delivery still owns a tracked ticket.
					deliveryThread.set(Thread.currentThread());
					deliveryEntered.countDown();
					awaitGate(releaseDelivery, "Interrupt delivery was not released");
					super.interrupt();
				}
			} : new Thread(task, name);
			worker.setDaemon(true);
			workersByName.put(name, worker);
			worker.start();
		});
		ResourceMethodResolver delegate = ResourceMethodResolver.fromClasses(Set.of(Resource.class));
		Set<ResourceMethod> resourceMethods = delegate.getResourceMethods();
		ResourceMethodResolver resolver = new ResourceMethodResolver() {
			@Override
			public Optional<ResourceMethod> resourceMethodForRequest(Request request, ServerType serverType) {
				return delegate.resourceMethodForRequest(request, serverType);
			}

			@Override
			public Set<ResourceMethod> getResourceMethods() {
				if (phase == Phase.SETUP && Thread.currentThread().getName().equals(targetName)) {
					targetEntered.countDown();
					awaitGate(releaseTarget, "Framework setup was not released");
				}
				return resourceMethods;
			}
		};
		HttpServer transport = transport(phase == Phase.ATTACHMENT ? targetEntered : null,
				phase == Phase.ATTACHMENT ? releaseTarget : null);
		SokletConfig configuration = config(transport, resolver);
		// This passive callback owner supplies the ordinary Soklet callbacks. Its
		// unrelated transport identity keeps the scheduling fixture one-shot.
		Soklet callbackOwner = Soklet.fromConfig(config(transport(null, null), delegate));
		SokletDirectLifecycle core = new SokletDirectLifecycle(callbackOwner, configuration,
				new SokletFrameworkSetup(configuration), NanoClock.system(), workers,
				() -> { }, () -> { }, () -> { }, () -> { }, name -> {
					if (name.equals(targetName)) {
						outcomePaused.countDown();
						awaitGate(releaseOutcome, "Startup outcome selection was not resumed");
					}
				});
		Thread caller = new Thread(() -> {
			try {
				core.start();
			} catch (Throwable failure) {
				startupFailure.set(failure);
			} finally {
				startReturned.countDown();
			}
		}, "synthetic-interrupt-accounting-startup");
		caller.setDaemon(true);
		caller.start();
		try {
			Assertions.assertTrue(targetEntered.await(5, TimeUnit.SECONDS));
			core.requestStartupCancellation();
			Assertions.assertTrue(deliveryEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(outcomePaused.await(5, TimeUnit.SECONDS));

			// Let the original action, its completion callback, and its worker all
			// finish before the coordinator can choose shutdown participants.
			releaseTarget.countDown();
			Thread target = workersByName.get(targetName);
			Assertions.assertNotNull(target);
			target.join(5_000);
			Assertions.assertFalse(target.isAlive(), "The original startup action did not finish");
			Assertions.assertTrue(deliveryThread.get().isAlive());
			Assertions.assertTrue(deliveryThread.get().isDaemon());
			Assertions.assertEquals(1L, releaseDelivery.getCount());
			if (!holdDelivery) {
				releaseDelivery.countDown();
				deliveryThread.get().join(5_000);
				Assertions.assertFalse(deliveryThread.get().isAlive());
			}
			releaseOutcome.countDown();
			Assertions.assertTrue(startReturned.await(5, TimeUnit.SECONDS));
			SokletStartupException canceled = Assertions.assertInstanceOf(
					SokletStartupException.class, startupFailure.get());
			ShutdownResult result = core.awaitPublicCompletion();
			Assertions.assertEquals(StartupDisposition.CANCELED, result.getStartupDisposition());
			Assertions.assertSame(result, canceled.getShutdownResult());
			Assertions.assertEquals(!holdDelivery, result.isComplete(),
					"Synthetic termination proof must include interrupt delivery after the action returns");
			InternalShutdownResult internal = result.internalResult();
			Assertions.assertEquals(internal.participantResults().size(),
					internal.participantResults().stream()
							.map(InternalLifecycleComponentShutdownResult::kind).distinct().count(),
					"Synthetic interrupt evidence must not duplicate a configured component kind");
			if (holdDelivery) {
				Assertions.assertEquals(1L, releaseDelivery.getCount());
				Assertions.assertTrue(deliveryThread.get().isAlive(),
						"The regression requires live delivery after the original action finished");
				InternalLifecycleComponentType kind = phase == Phase.SETUP
						? InternalLifecycleComponentType.FRAMEWORK : InternalLifecycleComponentType.HTTP;
				Assertions.assertTrue(internal.participantResult(kind).orElseThrow()
						.residualActivity().contains(InternalResidualActivityType.LIFECYCLE_CALL));
				if (phase == Phase.ATTACHMENT)
					Assertions.assertTrue(internal.participantResult(InternalLifecycleComponentType.FRAMEWORK)
							.isEmpty(), "Attachment evidence belongs to the configured transport row");
			}

			SokletApplicationFinalization finalization = new SokletApplicationFinalization(
					ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(5),
							terminal -> cleanupCalls.incrementAndGet()),
					new LifecycleRuntimeServices(NanoClock.system(), workers), terminal -> { });
			finalization.publishCoreSnapshot(core.terminalCoreSnapshot());
			InternalShutdownCleanupOutcome cleanup = finalization.awaitCompletion().cleanupOutcome();
			Assertions.assertEquals(holdDelivery
						? InternalShutdownCleanupDisposition.SKIPPED_INCOMPLETE_SHUTDOWN
						: InternalShutdownCleanupDisposition.SUCCEEDED,
					cleanup.disposition());
			Assertions.assertEquals(holdDelivery ? 0 : 1, cleanupCalls.get());
		} finally {
			releaseTarget.countDown();
			releaseOutcome.countDown();
			releaseDelivery.countDown();
			core.requestShutdownIntent();
			core.awaitCompletion();
			callbackOwner.requestShutdown();
			callbackOwner.awaitCoreShutdown();
			caller.join(5_000);
			Assertions.assertFalse(caller.isAlive());
			long reapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			for (Thread worker : workersByName.values()) {
				long remainingNanos = reapDeadline - System.nanoTime();
				if (remainingNanos > 0L)
					TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos);
				Assertions.assertFalse(worker.isAlive(), () -> worker.getName() + " did not finish");
			}
			Thread delivery = deliveryThread.get();
			if (delivery != null) {
				long remainingNanos = reapDeadline - System.nanoTime();
				if (remainingNanos > 0L)
					TimeUnit.NANOSECONDS.timedJoin(delivery, remainingNanos);
				Assertions.assertFalse(delivery.isAlive());
			}
		}
	}

	private static void assertReservationFailureAccounting(Phase phase) throws Exception {
		String targetName = phase == Phase.SETUP ? "soklet-framework-setup" : "soklet-attach-http";
		RuntimeException launchFailure = new SecurityException("test denies synthetic interrupt reservation");
		Map<String, Thread> workersByName = new ConcurrentHashMap<>();
		AtomicInteger targetActions = new AtomicInteger();
		AtomicInteger cleanupCalls = new AtomicInteger();
		LifecycleWorkers.Launcher launcher = (name, task) -> {
			Thread worker = new Thread(task, name);
			worker.setDaemon(true);
			workersByName.put(name, worker);
			worker.start();
		};
		LifecycleWorkers workers = new LifecycleWorkers(launcher, (name, task) -> {
			if (name.equals(targetName + "-interrupt"))
				throw launchFailure;
			launcher.launch(name, task);
		});
		ResourceMethodResolver delegate = ResourceMethodResolver.fromClasses(Set.of(Resource.class));
		Set<ResourceMethod> resourceMethods = delegate.getResourceMethods();
		ResourceMethodResolver resolver = new ResourceMethodResolver() {
			@Override
			public Optional<ResourceMethod> resourceMethodForRequest(Request request, ServerType serverType) {
				return delegate.resourceMethodForRequest(request, serverType);
			}

			@Override
			public Set<ResourceMethod> getResourceMethods() {
				if (phase == Phase.SETUP && Thread.currentThread().getName().equals(targetName))
					targetActions.incrementAndGet();
				return resourceMethods;
			}
		};
		HttpServer delegateTransport = transport(null, null);
		HttpServer transport = new HttpServer() {
			@Override
			public TransportIdentity getTransportIdentity() {
				return delegateTransport.getTransportIdentity();
			}

			@Override
			public TransportRuntime attach(HttpTransportAttachmentContext attachment, StartupContext startup) {
				if (phase == Phase.ATTACHMENT)
					targetActions.incrementAndGet();
				return delegateTransport.attach(attachment, startup);
			}
		};
		SokletConfig configuration = config(transport, resolver);
		Soklet callbackOwner = Soklet.fromConfig(config(transport(null, null), delegate));
		SokletDirectLifecycle core = new SokletDirectLifecycle(callbackOwner, configuration,
				new SokletFrameworkSetup(configuration), NanoClock.system(), workers);
		try {
			SokletStartupException failure = Assertions.assertThrows(SokletStartupException.class, core::start);
			ShutdownResult result = core.awaitPublicCompletion();
			Assertions.assertEquals(StartupDisposition.FAILED, result.getStartupDisposition());
			Assertions.assertSame(launchFailure, failure.getCause());
			Assertions.assertTrue(result.isComplete(), "An action that never started owns no residual work");
			Assertions.assertEquals(0, targetActions.get());
			Assertions.assertTrue(result.internalResult().participantResult(InternalLifecycleComponentType.FRAMEWORK)
					.isEmpty(), "Retired reservation failure must not invent a framework residual row");
			SokletApplicationFinalization finalization = new SokletApplicationFinalization(
					ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(5),
							terminal -> cleanupCalls.incrementAndGet()),
					new LifecycleRuntimeServices(NanoClock.system(), workers), terminal -> { });
			finalization.publishCoreSnapshot(core.terminalCoreSnapshot());
			Assertions.assertEquals(InternalShutdownCleanupDisposition.SUCCEEDED,
					finalization.awaitCompletion().cleanupOutcome().disposition());
			Assertions.assertEquals(1, cleanupCalls.get());
		} finally {
			core.requestShutdownIntent();
			core.awaitCompletion();
			callbackOwner.requestShutdown();
			callbackOwner.awaitCoreShutdown();
			long reapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			for (Thread worker : workersByName.values()) {
				long remainingNanos = reapDeadline - System.nanoTime();
				if (remainingNanos > 0L)
					TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos);
				Assertions.assertFalse(worker.isAlive(), () -> worker.getName() + " did not finish");
			}
		}
	}

	private static SokletConfig config(HttpServer transport, ResourceMethodResolver resolver) {
		return SokletConfig.withHttpServer(transport)
				.resourceMethodResolver(resolver)
				.lifecycleObservers(Set.of())
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(30))
						.startupCancelationTimeout(Duration.ofMillis(100))
						.gracefulShutdownTimeout(Duration.ofMillis(100))
						.forcedShutdownTimeout(Duration.ofMillis(100)).build())
				.build();
	}

	private static HttpServer transport(CountDownLatch entered, CountDownLatch release) {
		return new HttpServer() {
			private final TransportIdentity identity = TransportIdentity.create();

			@Override
			public TransportIdentity getTransportIdentity() {
				return this.identity;
			}

			@Override
			public TransportRuntime attach(HttpTransportAttachmentContext attachment, StartupContext startup) {
				if (entered != null) {
					entered.countDown();
					awaitGate(release, "Transport attachment was not released");
				}
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

	private static void awaitGate(CountDownLatch gate, String message) {
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					if (!gate.await(5, TimeUnit.SECONDS))
						throw new AssertionError(message);
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

	private enum Phase {
		SETUP,
		ATTACHMENT
	}

	public static final class Resource {
		@GET("/ready")
		public String ready() {
			return "ready";
		}
	}
}
