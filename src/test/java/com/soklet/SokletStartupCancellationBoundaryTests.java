/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Startup cancellation must elect against core readiness, not a later latch read. */
@Timeout(60)
final class SokletStartupCancellationBoundaryTests {

	@Test
	void readinessWinningAfterTheInterruptedCallersCheckKeepsTheOwnerRunning() throws Exception {
		CountDownLatch cancellationPaused = new CountDownLatch(1);
		CountDownLatch resumeCancellation = new CountDownLatch(1);
		try (ManagedRun run = new ManagedRun(() -> {
			cancellationPaused.countDown();
			awaitGate(resumeCancellation);
		}, true, false)) {
			run.startOwner();
			Assertions.assertTrue(run.transport.startEntered.await(5, TimeUnit.SECONDS));
			run.startCaller();
			run.caller.interrupt();
			try {
				Assertions.assertTrue(cancellationPaused.await(5, TimeUnit.SECONDS));
				// The caller has read the managed readiness latch as false. Let the
				// real core and managed readiness publications win before it cancels.
				run.transport.releaseStart.countDown();
				Assertions.assertTrue(run.managedReady.await(5, TimeUnit.SECONDS));
				resumeCancellation.countDown();
				run.assertReadyCallerReturned();
				Assertions.assertTrue(run.callerInterruptRestored.get());
				run.assertStillServing();
			} finally {
				resumeCancellation.countDown();
			}
		}
	}

	@Test
	void internalReadinessWinsBeforeTheManagedReadinessPublication() throws Exception {
		try (ManagedRun run = new ManagedRun(() -> { }, true, true)) {
			run.startOwner();
			Assertions.assertTrue(run.transport.startEntered.await(5, TimeUnit.SECONDS));
			run.transport.releaseStart.countDown();
			Assertions.assertTrue(run.internalReady.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(1L, run.managedReady.getCount());
			// Core readiness is already irrevocable even though the process owner
			// has not yet called SokletRunCompletion.publishReady().
			run.completion.cancelStartup();
			run.assertStillServing();
			run.releaseManagedReady.countDown();
			run.startCaller();
			run.assertReadyCallerReturned();
			run.assertStillServing();
		}
	}

	@Test
	void interruptedCallerWinningBeforeReadinessGetsCanceledStartup() throws Exception {
		try (ManagedRun run = new ManagedRun(() -> { }, true, false)) {
			run.startOwner();
			Assertions.assertTrue(run.transport.startEntered.await(5, TimeUnit.SECONDS));
			run.startCaller();
			run.caller.interrupt();
			run.assertCanceledCallerReturned();
			Assertions.assertTrue(run.callerInterruptRestored.get());
			Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED, run.owner.state());
			Assertions.assertEquals(1L, run.managedReady.getCount(),
					"Canceled startup must not publish readiness");
		}
	}

	@Test
	void cancellationBeforeBindingSurvivesTheNewToStartingClaim() throws Exception {
		try (ManagedRun run = new ManagedRun(() -> { }, false, false)) {
			run.completion.cancelStartup();
			Assertions.assertEquals(InternalLifecycleStateMachine.State.NEW, run.owner.state());
			run.completion.bind(run.soklet);
			Assertions.assertEquals(InternalLifecycleStateMachine.State.NEW, run.owner.state(),
					"Recording pending cancellation must not close an unstarted lifecycle");
			run.startOwner();
			run.startCaller();
			run.assertCanceledCallerReturned();
			Assertions.assertEquals(0, run.transport.startCalls.get());
			Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED, run.owner.state());
		}
	}

	private static void awaitGate(CountDownLatch gate) {
		try {
			if (!gate.await(5, TimeUnit.SECONDS))
				throw new AssertionError("Startup scheduling gate was not released");
		} catch (InterruptedException interruption) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Startup scheduling gate was interrupted", interruption);
		}
	}

	private static final class ManagedRun implements AutoCloseable {
		private final GateTransport transport = new GateTransport();
		private final Soklet soklet;
		private final SokletDirectLifecycle owner;
		private final SokletRunCompletion completion;
		private final CountDownLatch internalReady = new CountDownLatch(1);
		private final CountDownLatch managedReady = new CountDownLatch(1);
		private final CountDownLatch releaseManagedReady;
		private final CountDownLatch callerReturned = new CountDownLatch(1);
		private final AtomicReference<Soklet> callerResult = new AtomicReference<>();
		private final AtomicReference<Throwable> callerFailure = new AtomicReference<>();
		private final AtomicBoolean callerInterruptRestored = new AtomicBoolean();
		private final Thread processOwner;
		private final Thread caller;
		private final AtomicReference<Thread> handoff = new AtomicReference<>();

		ManagedRun(Runnable beforeCancellation, boolean bind, boolean holdManagedReady) {
			SokletConfig config = SokletConfig.withHttpServer(this.transport)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.lifecycleObservers(List.of()).jvmShutdownHookEnabled(false)
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
			this.soklet = Soklet.fromConfig(config);
			this.owner = this.soklet.getDirectLifecycle();
			this.releaseManagedReady = new CountDownLatch(holdManagedReady ? 1 : 0);
			this.completion = new SokletRunCompletion((name, task) -> {
				Thread thread = Thread.ofPlatform().daemon(true).name(name).unstarted(task);
				this.handoff.set(thread);
				thread.start();
			}, beforeCancellation);
			if (bind)
				this.completion.bind(this.soklet);
			// Match the process owner's actual ordering, with a controlled seam
			// between internal readiness and managed publication.
			this.processOwner = Thread.ofPlatform().daemon(true).name("startup-cancellation-owner").unstarted(() -> {
				try {
					this.soklet.start();
					this.internalReady.countDown();
					awaitGate(this.releaseManagedReady);
					this.completion.publishReady();
					this.managedReady.countDown();
					ShutdownResult terminal = this.owner.shutdownStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
					this.completion.finish(terminal, null);
				} catch (Throwable failure) {
					this.completion.finish(null, failure);
				}
			});
			this.caller = Thread.ofPlatform().daemon(true).name("startup-cancellation-caller").unstarted(() -> {
				try {
					this.callerResult.set(this.completion.awaitReady());
				} catch (Throwable failure) {
					this.callerFailure.set(failure);
				} finally {
					this.callerInterruptRestored.set(Thread.currentThread().isInterrupted());
					this.callerReturned.countDown();
				}
			});
		}

		void startOwner() { this.processOwner.start(); }
		void startCaller() { this.caller.start(); }

		void assertReadyCallerReturned() throws Exception {
			Assertions.assertTrue(this.callerReturned.await(5, TimeUnit.SECONDS));
			Assertions.assertNull(this.callerFailure.get());
			Assertions.assertSame(this.soklet, this.callerResult.get());
		}

		void assertCanceledCallerReturned() throws Exception {
			Assertions.assertTrue(this.callerReturned.await(5, TimeUnit.SECONDS));
			SokletStartupException failure = Assertions.assertInstanceOf(
					SokletStartupException.class, this.callerFailure.get());
			Assertions.assertNull(this.callerResult.get());
			Assertions.assertEquals(StartupDisposition.CANCELED, failure.getStartupDisposition());
			Assertions.assertTrue(failure.getShutdownResult().isComplete());
		}

		void assertStillServing() {
			Assertions.assertEquals(InternalLifecycleStateMachine.State.READY, this.owner.state());
			Assertions.assertTrue(this.owner.result().isEmpty(), "Stale cancellation published a terminal result");
			AtomicReference<HttpRequestResult> response = new AtomicReference<>();
			this.transport.handler.get().handleRequest(Request.withPath(HttpMethod.GET, "/ready").build(), response::set);
			Assertions.assertNotNull(response.get());
			Assertions.assertEquals(200, response.get().getMarshaledResponse().getStatusCode());
		}

		@Override
		public void close() throws Exception {
			this.transport.releaseStart.countDown();
			this.releaseManagedReady.countDown();
			if (this.processOwner.getState() == Thread.State.NEW)
				this.processOwner.start();
			this.soklet.requestShutdown();
			this.processOwner.join(5_000);
			this.caller.join(5_000);
			this.handoff.get().join(5_000);
			Assertions.assertFalse(this.processOwner.isAlive(), "Process owner did not retire");
			Assertions.assertFalse(this.caller.isAlive(), "Readiness caller did not retire");
			Assertions.assertFalse(this.handoff.get().isAlive(), "Completion handoff did not retire");
			Assertions.assertTrue(this.owner.result().orElseThrow().isComplete());
		}
	}

	private static final class GateTransport implements HttpServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private final CountDownLatch startEntered = new CountDownLatch(1);
		private final CountDownLatch releaseStart = new CountDownLatch(1);
		private final AtomicInteger startCalls = new AtomicInteger();
		private final AtomicReference<HttpServer.RequestHandler> handler = new AtomicReference<>();

		@Override
		public TransportIdentity getTransportIdentity() { return this.identity; }

		@Override
		public TransportRuntime attach(HttpTransportAttachmentContext attachment, StartupContext context) {
			this.handler.set(attachment.getAdmissionFencedRequestHandler());
			TransportTerminationSignal terminated = attachment.getTransportTerminationSignal();
			return new TransportRuntime() {
				@Override
				public void start(StartupContext startup) {
					startCalls.incrementAndGet();
					startEntered.countDown();
					awaitGate(releaseStart);
				}

				@Override
				public void shutdownGracefully(ShutdownContext shutdown) { terminated.signalTerminated(); }

				@Override
				public void shutdownForcibly(ShutdownContext shutdown) { terminated.signalTerminated(); }
			};
		}
	}

	public static final class Resource {
		@GET("/ready")
		public String ready() { return "ready"; }
	}
}
