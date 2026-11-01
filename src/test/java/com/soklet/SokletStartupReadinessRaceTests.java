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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/** Deterministic coverage of readiness and terminal publication between startup reads. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletStartupReadinessRaceTests {
	@Test
	void gracefulShutdownBetweenStartupReadsStillReturnsFromStart() throws Exception {
		assertTerminalPublicationBetweenStartupReads(false);
	}

	@Test
	void unexpectedShutdownBetweenStartupReadsPreservesTheTerminalFailure() throws Exception {
		assertTerminalPublicationBetweenStartupReads(true);
	}

	private static void assertTerminalPublicationBetweenStartupReads(boolean unexpected)
			throws Exception {
		CountDownLatch resultReadPaused = new CountDownLatch(1);
		CountDownLatch resumeResultRead = new CountDownLatch(1);
		CountDownLatch reachedReadiness = new CountDownLatch(1);
		CountDownLatch startReturned = new CountDownLatch(1);
		AtomicInteger resultReadCalls = new AtomicInteger();
		AtomicReference<Throwable> startFailure = new AtomicReference<>();
		GateTransport transport = new GateTransport();
		SokletConfig config = SokletConfig.withHttpServer(transport)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecycleObservers(Set.of(new LifecycleObserver() {
					@Override
					public void didStartSoklet() {
						reachedReadiness.countDown();
					}
				}))
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.build();
		Soklet soklet = Soklet.fromConfig(config);
		SokletDirectLifecycle owner = soklet.getDirectLifecycle();
		Thread caller = new Thread(() -> {
			try {
				owner.start(() -> {
					if (resultReadCalls.incrementAndGet() == 1) {
						resultReadPaused.countDown();
						awaitGate(resumeResultRead, "Startup result read was never resumed");
					}
				});
			} catch (Throwable failure) {
				startFailure.set(failure);
			} finally {
				startReturned.countDown();
			}
		}, "startup-readiness-result-race");
		caller.setDaemon(true);
		RuntimeException prematureFailure = new RuntimeException("transport ended after readiness");
		caller.start();
		try {
			Assertions.assertTrue(resultReadPaused.await(5, TimeUnit.SECONDS),
					"Startup did not pause after reading readiness as false");
			Assertions.assertTrue(transport.startEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(InternalLifecycleStateMachine.State.STARTING, owner.state());

			// Startup cannot reach readiness until its caller has already taken
			// the false-ready branch, making this ordering independent of scheduling.
			transport.releaseStart.countDown();
			Assertions.assertTrue(reachedReadiness.await(5, TimeUnit.SECONDS));
			if (unexpected)
				transport.failAndTerminate(prematureFailure);
			else
				soklet.requestShutdown();
			InternalShutdownResult terminal = owner.awaitCompletion();
			Assertions.assertEquals(InternalStartupDisposition.READY, terminal.startupDisposition());
			Assertions.assertTrue(terminal.isComplete());
			Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED, owner.state());
			Assertions.assertEquals(1L, startReturned.getCount(),
					"The startup caller must remain paused until READY and terminal are both published");
			Assertions.assertEquals(1, resultReadCalls.get());

			resumeResultRead.countDown();
			Assertions.assertTrue(startReturned.await(5, TimeUnit.SECONDS));
			Assertions.assertNull(startFailure.get(),
					"A terminal READY result is successful startup, even when shutdown already finished");
			Assertions.assertSame(terminal, owner.result().orElseThrow());
			if (unexpected) {
				SokletUnexpectedTerminationException failure = Assertions.assertThrows(
						SokletUnexpectedTerminationException.class, soklet::awaitShutdown);
				Assertions.assertSame(prematureFailure, failure.getCause());
				Assertions.assertSame(terminal, failure.getInternalShutdownResult());
				Assertions.assertEquals(StartupDisposition.READY,
						failure.getShutdownResult().getStartupDisposition());
			} else {
				ShutdownResult result = soklet.awaitShutdown();
				Assertions.assertEquals(StartupDisposition.READY, result.getStartupDisposition());
				Assertions.assertSame(terminal, result.internalResult());
			}
		} finally {
			transport.releaseStart.countDown();
			resumeResultRead.countDown();
			soklet.requestShutdown();
			owner.awaitCompletion();
			caller.join(5_000);
			Assertions.assertFalse(caller.isAlive(), "Startup caller did not finish");
		}
	}

	private static void awaitGate(CountDownLatch gate, String message) {
		try {
			if (!gate.await(5, TimeUnit.SECONDS))
				throw new AssertionError(message);
		} catch (InterruptedException interruption) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(message, interruption);
		}
	}

	private static final class GateTransport implements HttpServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private final CountDownLatch startEntered = new CountDownLatch(1);
		private final CountDownLatch releaseStart = new CountDownLatch(1);
		private final AtomicReference<TransportTerminationSignal> terminated = new AtomicReference<>();

		@Override
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		public TransportRuntime attach(HttpTransportAttachmentContext attachment,
				StartupContext context) {
			TransportTerminationSignal signal = attachment.getTransportTerminationSignal();
			this.terminated.set(signal);
			return new TransportRuntime() {
				@Override
				public void start(StartupContext startup) {
					startEntered.countDown();
					awaitGate(releaseStart, "Transport startup was never released");
				}

				@Override
				public void shutdownGracefully(ShutdownContext shutdown) {
					signal.signalTerminated();
				}

				@Override
				public void shutdownForcibly(ShutdownContext shutdown) {
					signal.signalTerminated();
				}
			};
		}

		void failAndTerminate(Throwable failure) {
			TransportTerminationSignal signal = requireNonNull(this.terminated.get());
			signal.signalTerminationFailure(failure);
			signal.signalTerminated();
		}
	}

	public static final class Resource {
		@GET("/ready")
		public String ready() {
			return "ready";
		}
	}
}
