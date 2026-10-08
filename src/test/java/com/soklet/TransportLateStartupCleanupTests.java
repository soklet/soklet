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
import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Public custom-transport cleanup after the owner's immutable deadline result. */
@Timeout(20)
class TransportLateStartupCleanupTests {
	@Test void lateHttpStartClosesItsListener() throws Exception {
		assertLateCleanup(false, false, null, null, null);
	}

	@Test void lateSseStartClosesItsListener() throws Exception {
		assertLateCleanup(true, false, null, null, null);
	}

	@Test void lateStartAndForceFailuresDoNotRewriteCancellation() throws Exception {
		assertLateCleanup(false, false, new IllegalStateException("late start failure"),
				new IllegalArgumentException("late force failure"), null);
	}

	@Test void lateFailuresDoNotMutatePublishedIndependentCause() throws Exception {
		assertLateCleanup(true, false, new IllegalStateException("late start failure"),
				new IllegalArgumentException("late force failure"),
				new IllegalStateException("independent failure before readiness"));
	}

	@Test void blockingLateForceRetainsItsExistingWorkerWithoutDelayingShutdown() throws Exception {
		assertLateCleanup(true, true, null, null, null);
	}

	private void assertLateCleanup(boolean sse, boolean blockForce,
			@Nullable RuntimeException startFailure, @Nullable RuntimeException forceFailure,
			@Nullable RuntimeException independentFailure) throws Exception {
		Fixture fixture = new Fixture(blockForce, startFailure, forceFailure, independentFailure);
		SokletConfig.Builder builder = sse ? SokletConfig.withSseServer(fixture.sse())
				: SokletConfig.withHttpServer(fixture.http());
		Soklet soklet = Soklet.fromConfig(builder
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(sse ? SseRoutes.class : Routes.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(@NonNull LogEvent logEvent) { }
				})
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ZERO)
						.gracefulShutdownTimeout(Duration.ofMillis(30))
						.forcedShutdownTimeout(Duration.ofMillis(70)).build()).build());
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<SokletStartupException> startup = executor.submit(() ->
				assertThrows(SokletStartupException.class, soklet::start));
		try {
			assertTrue(fixture.startEntered.await(5, TimeUnit.SECONDS), () -> startup.isDone()
					? "Startup finished before runtime entry: " + startupFailure(startup) : "Runtime start did not enter");
			CompletionStage<ShutdownResult> stage = soklet.shutdown();
			ShutdownResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
			SokletStartupException startupException = startup.get(5, TimeUnit.SECONDS);
			ShutdownComponentType type = sse ? ShutdownComponentType.SSE : ShutdownComponentType.HTTP;
			ShutdownComponentResult component = result.getShutdownComponentResult(type).orElseThrow();
			assertFalse(result.isComplete());
			assertEquals(ShutdownComponentDisposition.TERMINATION_UNKNOWN,
					component.getShutdownComponentDisposition());
			assertSame(result, startupException.getShutdownResult());
			Throwable publishedCause = startupException.getCause();
			if (independentFailure != null) assertSame(independentFailure, publishedCause);
			var failures = component.getThrowables();
			assertEquals(independentFailure == null ? java.util.List.of() : java.util.List.of(independentFailure), failures);
			assertEquals(0, publishedCause.getSuppressed().length);
			assertFalse(fixture.listener.get().isClosed());
			assertEquals(0, fixture.forceCalls.get());
			assertEquals(0, fixture.gracefulCalls.get());
			assertClosedAdmission(fixture);

			fixture.releaseStart.countDown();
			assertTrue(fixture.forceEntered.await(5, TimeUnit.SECONDS), "Late start must receive forced stop");
			assertSame(fixture.startThread.get(), fixture.forceThread.get(), "Compensation reuses the tracked start worker");
			assertTrue(fixture.forceThread.get().isDaemon());
			assertEquals(ShutdownPhase.FORCED, fixture.forceContext.get().getShutdownPhase());
			assertEquals(Duration.ZERO, fixture.forceContext.get().getRemainingTime(), "No fresh cleanup budget");
			if (blockForce) {
				assertTrue(fixture.startThread.get().isAlive());
				assertFalse(fixture.listener.get().isClosed());
				assertSame(result, soklet.awaitShutdown(), "Blocked cleanup cannot reopen the completed result");
				assertEquals(1L, fixture.forceFinished.getCount());
			}
			fixture.releaseForce.countDown();
			assertTrue(fixture.forceFinished.await(5, TimeUnit.SECONDS));
			fixture.startThread.get().join(5000);
			assertFalse(fixture.startThread.get().isAlive());
			assertTrue(fixture.listener.get().isClosed(), "The late listener must be physically closed");
			assertEquals(1, fixture.forceCalls.get());
			assertEquals(0, fixture.gracefulCalls.get());
			assertSame(stage, soklet.shutdown());
			assertSame(result, soklet.awaitShutdown());
			assertSame(component, result.getShutdownComponentResult(type).orElseThrow());
			assertEquals(ShutdownComponentDisposition.TERMINATION_UNKNOWN, component.getShutdownComponentDisposition());
			assertEquals(failures, component.getThrowables());
			assertEquals(0, publishedCause.getSuppressed().length, "Best-effort failure must not mutate a published Throwable");
			if (startFailure != null) assertEquals(0, startFailure.getSuppressed().length);
			assertClosedAdmission(fixture);
			assertThrows(SokletShutdownIncompleteException.class, soklet::close);
			assertEquals(1, fixture.forceCalls.get(), "Repeated shutdown cannot replay compensation");
		} finally {
			fixture.releaseStart.countDown();
			fixture.releaseForce.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
			Thread worker = fixture.startThread.get();
			if (worker != null) worker.join(5000);
			ServerSocket listener = fixture.listener.get();
			if (listener != null) listener.close();
			soklet.shutdown();
			soklet.awaitShutdown();
		}
	}

	public static final class Routes {
		@GET("/late") public String late() { return "late"; }
	}

	public static final class SseRoutes {
		@SseEventSource("/late") public SseHandshakeResult late() { return SseHandshakeResult.accept(); }
	}

	private static String startupFailure(Future<SokletStartupException> startup) {
		try { return String.valueOf(startup.get().getCause()); }
		catch (Exception exception) { return String.valueOf(exception); }
	}

	private static void assertClosedAdmission(Fixture fixture) {
		AtomicReference<HttpRequestResult> response = new AtomicReference<>();
		fixture.handler.get().accept(response::set);
		assertEquals(503, response.get().getMarshaledResponse().getStatusCode());
	}

	private static void awaitIgnoringInterrupts(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try { latch.await(); break; }
			catch (InterruptedException exception) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private static final class Fixture implements TransportRuntime {
		private final TransportIdentity identity = TransportIdentity.create();
		private final boolean blockForce;
		private final @Nullable RuntimeException startFailure;
		private final @Nullable RuntimeException forceFailure;
		private final @Nullable RuntimeException independentFailure;
		private final CountDownLatch startEntered = new CountDownLatch(1);
		private final CountDownLatch releaseStart = new CountDownLatch(1);
		private final CountDownLatch forceEntered = new CountDownLatch(1);
		private final CountDownLatch releaseForce = new CountDownLatch(1);
		private final CountDownLatch forceFinished = new CountDownLatch(1);
		private final AtomicInteger gracefulCalls = new AtomicInteger();
		private final AtomicInteger forceCalls = new AtomicInteger();
		private final AtomicReference<Thread> startThread = new AtomicReference<>();
		private final AtomicReference<Thread> forceThread = new AtomicReference<>();
		private final AtomicReference<ServerSocket> listener = new AtomicReference<>();
		private final AtomicReference<ShutdownContext> forceContext = new AtomicReference<>();
		private final AtomicReference<TransportTerminationSignal> signal = new AtomicReference<>();
		private final AtomicReference<Consumer<Consumer<HttpRequestResult>>> handler = new AtomicReference<>();

		private Fixture(boolean blockForce, @Nullable RuntimeException startFailure,
				@Nullable RuntimeException forceFailure, @Nullable RuntimeException independentFailure) {
			this.blockForce = blockForce; this.startFailure = startFailure;
			this.forceFailure = forceFailure; this.independentFailure = independentFailure;
		}

		HttpServer http() {
			return new HttpServer() {
				@Override public @NonNull TransportIdentity getTransportIdentity() { return identity; }
				@Override public @NonNull TransportRuntime attach(@NonNull HttpTransportAttachmentContext context,
						@NonNull StartupContext startupContext) {
					signal.set(context.getTransportTerminationSignal());
					HttpServer.RequestHandler requestHandler = context.getAdmissionFencedRequestHandler();
					handler.set(consumer -> requestHandler.handleRequest(Request.withPath(HttpMethod.GET, "/late").build(), consumer));
					return Fixture.this;
				}
			};
		}

		SseServer sse() {
			return new SseServer() {
				@Override public @NonNull TransportIdentity getTransportIdentity() { return identity; }
				@Override public @NonNull TransportRuntime attach(@NonNull SseTransportAttachmentContext context,
						@NonNull StartupContext startupContext) {
					signal.set(context.getTransportTerminationSignal());
					SseServer.RequestHandler requestHandler = context.getAdmissionFencedRequestHandler();
					handler.set(consumer -> requestHandler.handleRequest(Request.withPath(HttpMethod.GET, "/late").build(), consumer));
					return Fixture.this;
				}
				@Override public @NonNull Optional<? extends SseBroadcaster> acquireBroadcaster(@Nullable ResourcePath path) {
					return Optional.empty();
				}
			};
		}

		@Override public void start(@NonNull StartupContext startupContext) {
			this.startThread.set(Thread.currentThread());
			try {
				ServerSocket socket = new ServerSocket();
				this.listener.set(socket);
				socket.bind(new InetSocketAddress("127.0.0.1", 0));
			} catch (IOException exception) { throw new UncheckedIOException(exception); }
			if (this.independentFailure != null) this.signal.get().signalTerminationFailure(this.independentFailure);
			this.startEntered.countDown();
			awaitIgnoringInterrupts(this.releaseStart);
			if (this.startFailure != null) throw this.startFailure;
		}

		@Override public void shutdownGracefully(@NonNull ShutdownContext shutdownContext) {
			this.gracefulCalls.incrementAndGet();
			throw new AssertionError("Graceful phase must stay deferred behind start");
		}

		@Override public void shutdownForcibly(@NonNull ShutdownContext shutdownContext) {
			this.forceCalls.incrementAndGet();
			this.forceThread.set(Thread.currentThread());
			this.forceContext.set(shutdownContext);
			this.forceEntered.countDown();
			try {
				if (this.blockForce) awaitIgnoringInterrupts(this.releaseForce);
				try { this.listener.get().close(); }
				catch (IOException exception) { throw new UncheckedIOException(exception); }
				this.signal.get().signalTerminated();
				if (this.forceFailure != null) throw this.forceFailure;
			} finally { this.forceFinished.countDown(); }
		}
	}
}
