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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Acceptance coverage for the ready-returning, process-owned public lifecycle. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletPublicLifecycleTests {
	@Test
	void runReturnsAfterNetworkReadinessAndAsyncObservationCannotCompleteTheLifecycle()
			throws Exception {
		DefaultHttpServer server = server();
		Soklet soklet = Soklet.run(config(server));
		try {
			Assertions.assertEquals("ready", get(server));
			CompletionStage<ShutdownResult> completion = soklet.awaitShutdownAsync();
			Assertions.assertSame(completion, soklet.awaitShutdownAsync());
			Assertions.assertFalse(completion.toCompletableFuture().isDone());

			// Callers can mutate a CompletableFuture copy without publishing a
			// terminal lifecycle result or completing other observers.
			completion.toCompletableFuture().complete(null);
			Assertions.assertFalse(soklet.awaitShutdownAsync()
					.toCompletableFuture().isDone());
			Assertions.assertEquals("ready", get(server));

			soklet.requestShutdown();
			soklet.requestShutdown();
			ShutdownResult asynchronous = completion.toCompletableFuture()
					.get(5, TimeUnit.SECONDS);
			Assertions.assertTrue(asynchronous.isComplete());
			Assertions.assertSame(asynchronous, soklet.awaitShutdown());
		} finally {
			stop(soklet);
		}
	}

	@Test
	void completionWaitsForApplicationCleanupAndPreservesThePublishedResult()
			throws Exception {
		CountDownLatch cleanupEntered = new CountDownLatch(1);
		CountDownLatch releaseCleanup = new CountDownLatch(1);
		AtomicReference<ShutdownResult> cleanupResult = new AtomicReference<>();
		ShutdownCleanup cleanup = ShutdownCleanup.fromTimeoutAndAction(
				Duration.ofSeconds(5), result -> {
					cleanupResult.set(result);
					cleanupEntered.countDown();
					Assertions.assertTrue(releaseCleanup.await(5, TimeUnit.SECONDS));
				});
		Soklet soklet = Soklet.run(config(server()), cleanup);
		try {
			CompletionStage<ShutdownResult> completion = soklet.awaitShutdownAsync();
			soklet.requestShutdown();
			Assertions.assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertFalse(completion.toCompletableFuture().isDone(),
					"Core termination must not complete the public finalization stage");
			releaseCleanup.countDown();
			ShutdownResult result = completion.toCompletableFuture()
					.get(5, TimeUnit.SECONDS);
			Assertions.assertSame(cleanupResult.get(), result);
			Assertions.assertSame(result, soklet.awaitShutdown());
		} finally {
			releaseCleanup.countDown();
			stop(soklet);
		}
	}

	@Test
	void interruptingAWaitDoesNotRequestShutdown() throws Exception {
		DefaultHttpServer server = server();
		Soklet soklet = Soklet.run(config(server));
		CountDownLatch entered = new CountDownLatch(1);
		AtomicReference<Throwable> waitFailure = new AtomicReference<>();
		Thread waiter = new Thread(() -> {
			entered.countDown();
			try {
				soklet.awaitShutdown();
			} catch (Throwable failure) {
				waitFailure.set(failure);
			}
		}, "public-soklet-interruptible-wait");
		waiter.setDaemon(true);
		try {
			waiter.start();
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
			waiter.interrupt();
			waiter.join(5_000);
			Assertions.assertFalse(waiter.isAlive());
			Assertions.assertInstanceOf(InterruptedException.class, waitFailure.get());
			Assertions.assertFalse(soklet.awaitShutdownAsync()
					.toCompletableFuture().isDone());
			Assertions.assertEquals("ready", get(server));
		} finally {
			stop(soklet);
			waiter.interrupt();
			waiter.join(5_000);
		}
	}

	@Test
	void synchronousAndAsynchronousWaitsExposeTheSameCleanupFailure()
			throws Exception {
		RuntimeException cause = new RuntimeException("cleanup failed");
		Soklet soklet = Soklet.run(config(server()),
				ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(5),
						result -> { throw cause; }));
		soklet.requestShutdown();
		ExecutionException asynchronous = Assertions.assertThrows(
				ExecutionException.class, () -> soklet.awaitShutdownAsync()
						.toCompletableFuture().get(5, TimeUnit.SECONDS));
		SokletShutdownCleanupException synchronous = Assertions.assertThrows(
				SokletShutdownCleanupException.class, soklet::awaitShutdown);
		Assertions.assertSame(synchronous, asynchronous.getCause());
		Assertions.assertSame(cause, synchronous.getCause());
		Assertions.assertTrue(synchronous.getShutdownResult().isComplete());
	}

	@Test
	void cleanupCannotBlockWaitingForItsOwnFinalization() throws Exception {
		AtomicReference<Soklet> owner = new AtomicReference<>();
		AtomicReference<IllegalStateException> reentrantFailure =
				new AtomicReference<>();
		Soklet soklet = Soklet.run(config(server()),
				ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(5),
						result -> {
							reentrantFailure.set(Assertions.assertThrows(
									IllegalStateException.class,
									owner.get()::awaitShutdown));
							Assertions.assertFalse(owner.get().awaitShutdownAsync()
									.toCompletableFuture().isDone());
						}));
		owner.set(soklet);
		stop(soklet);
		Assertions.assertNotNull(reentrantFailure.get());
	}

	@Test
	void startupFailureIsSynchronousAndFinishesEligibleCleanupBeforeThrowing()
			throws Exception {
		AtomicBoolean cleaned = new AtomicBoolean();
		SokletConfig invalidConfig = SokletConfig.withHttpServer(server())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(policy()).build();
		SokletStartupException failure = Assertions.assertThrows(
				SokletStartupException.class, () -> Soklet.run(invalidConfig,
						ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(5),
								result -> cleaned.set(true))));
		Assertions.assertEquals(StartupDisposition.FAILED,
				failure.getStartupDisposition());
		Assertions.assertTrue(failure.getShutdownResult().isComplete());
		Assertions.assertTrue(cleaned.get(),
				"A failed run must finish eligible cleanup before throwing");
	}

	@Test
	void nullArgumentsFailBeforeClaimingTransports() throws Exception {
		DefaultHttpServer server = server();
		SokletConfig config = config(server);
		Assertions.assertThrows(NullPointerException.class,
				() -> Soklet.run(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> Soklet.run(config, (ShutdownCleanup) null));
		Soklet soklet = Soklet.run(config);
		stop(soklet);
	}

	@Test
	void startupCancellationSurvivesAnIdentityGetterConsumingInterruption()
			throws Exception {
		AtomicBoolean interruptionConsumed = new AtomicBoolean();
		AtomicBoolean interruptRestored = new AtomicBoolean();
		AtomicInteger starts = new AtomicInteger();
		AtomicReference<Throwable> startupFailure = new AtomicReference<>();
		AtomicReference<Soklet> unexpectedReadyHandle = new AtomicReference<>();
		TransportIdentity identity = TransportIdentity.create();
		HttpServer transport = new HttpServer() {
			@Override
			public TransportIdentity getTransportIdentity() {
				if (Thread.interrupted())
					interruptionConsumed.set(true);
				return identity;
			}

			@Override
			public TransportRuntime attach(HttpTransportAttachmentContext attachment,
					StartupContext startup) {
				return new TransportRuntime() {
					@Override
					public void start(StartupContext context) {
						starts.incrementAndGet();
					}

					@Override
					public void shutdownGracefully(ShutdownContext context) {
						attachment.getTransportTerminationSignal().signalTerminated();
					}

					@Override
					public void shutdownForcibly(ShutdownContext context) {
						attachment.getTransportTerminationSignal().signalTerminated();
					}
				};
			}
		};
		SokletConfig configuration = config(transport);
		Thread caller = new Thread(() -> {
			Thread.currentThread().interrupt();
			try {
				Soklet soklet = Soklet.run(configuration);
				unexpectedReadyHandle.set(soklet);
				SokletTestScope.close(soklet);
			} catch (Throwable failure) {
				startupFailure.set(failure);
			} finally {
				interruptRestored.set(Thread.currentThread().isInterrupted());
			}
		}, "public-soklet-identity-interrupt-cancellation");
		caller.setDaemon(true);
		caller.start();
		caller.join(10_000);
		Assertions.assertFalse(caller.isAlive());
		Assertions.assertTrue(interruptionConsumed.get(),
				"The regression fixture must consume the coordinator's interrupt");
		Assertions.assertNull(unexpectedReadyHandle.get());
		SokletStartupException failure = Assertions.assertInstanceOf(
				SokletStartupException.class, startupFailure.get());
		Assertions.assertEquals(StartupDisposition.CANCELED,
				failure.getStartupDisposition());
		Assertions.assertEquals(0, starts.get());
		Assertions.assertTrue(interruptRestored.get());
	}

	private static DefaultHttpServer server() {
		return (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").build();
	}

	private static SokletConfig config(HttpServer server) {
		return SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(ReadyResource.class))).lifecyclePolicy(policy()).build();
	}

	private static LifecyclePolicy policy() {
		return LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(5))
				.startupCancelationTimeout(Duration.ofSeconds(1))
				.gracefulShutdownTimeout(Duration.ofSeconds(1))
				.forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	}

	private static String get(DefaultHttpServer server) throws Exception {
		int port = server.getEventLoop().orElseThrow().getPort();
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> response = client.send(HttpRequest.newBuilder(
					URI.create("http://127.0.0.1:" + port + "/ready"))
					.timeout(Duration.ofSeconds(5)).build(),
					HttpResponse.BodyHandlers.ofString());
			Assertions.assertEquals(200, response.statusCode());
			return response.body();
		}
	}

	private static void stop(Soklet soklet) throws InterruptedException {
		soklet.requestShutdown();
		soklet.awaitShutdown();
	}

	public static final class ReadyResource {
		@GET("/ready")
		public String ready() {
			return "ready";
		}
	}
}
