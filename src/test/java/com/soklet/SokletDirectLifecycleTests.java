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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Focused package-private acceptance coverage for D1a's direct one-shot owner. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class SokletDirectLifecycleTests {
	@NonNull
	private static final Duration SHORT_PHASE = Duration.ofMillis(75);
	@NonNull
	private final Set<ExecutorService> executors =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	@AfterEach
	void tearDown() {
		for (ExecutorService executor : this.executors)
			executor.shutdownNow();
		SelfStoppingResource.SOKLET.set(null);
		SelfStoppingResource.STOP_FAILURE.set(null);
	}

	@Test
	void configBuildAndFromConfigAreLightweightAndCloseBeforeStartDoesNoSetup() {
		ReferenceHttpEndpoint http = new ReferenceHttpEndpoint();
		CountingResolver resolver = CountingResolver.forClasses(OkResource.class);

		SokletConfig config = directConfig(http, resolver).build();
		Assertions.assertEquals(0, resolver.snapshotCalls());
		Assertions.assertEquals(0, http.attachCalls());

		Soklet soklet = Soklet.fromConfig(config);
		Assertions.assertEquals(InternalLifecycleStateMachine.State.NEW,
				soklet.getDirectLifecycle().state());
		Assertions.assertTrue(soklet.getDirectLifecycle().result().isEmpty());
		Assertions.assertEquals(0, resolver.snapshotCalls());
		Assertions.assertEquals(0, http.attachCalls());
		Assertions.assertEquals(0, http.initializeCalls());
		Assertions.assertEquals(0, http.startCalls());

		soklet.close();

		InternalShutdownResult result = soklet.getDirectLifecycle().result()
				.orElseThrow();
		Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
				soklet.getDirectLifecycle().state());
		Assertions.assertEquals(InternalShutdownDisposition.NOT_STARTED,
				result.disposition());
		Assertions.assertEquals(InternalStartupDisposition.NOT_ATTEMPTED,
				result.startupDisposition());
		Assertions.assertEquals(InternalParticipantShutdownDisposition.NOT_STARTED,
				result.participantResult(InternalParticipantKind.HTTP).orElseThrow()
						.disposition());
		Assertions.assertEquals(0, resolver.snapshotCalls());
		Assertions.assertEquals(0, http.attachCalls());
	}

	@Test
	void closeBeforeStartIsOneShotAndSubsequentStopsJoinTheSameResult() {
		ReferenceHttpEndpoint http = new ReferenceHttpEndpoint();
		Soklet soklet = Soklet.fromConfig(directConfig(http,
				CountingResolver.forClasses(OkResource.class)).build());

		soklet.close();
		InternalShutdownResult first = soklet.getDirectLifecycle().result()
				.orElseThrow();
		soklet.stop();

		Assertions.assertSame(first,
				soklet.getDirectLifecycle().result().orElseThrow());
		Assertions.assertThrows(IllegalStateException.class, soklet::start);
		Assertions.assertFalse(soklet.isStarted());
	}

	@Test
	void closeBeforeStartPublishesCompleteOrderedStopTransitionPairs()
			throws Exception {
		ReferenceHttpEndpoint http = new ReferenceHttpEndpoint();
		StopTransitionObserver observer = new StopTransitionObserver();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withHttpServer(http)
				.resourceMethodResolver(
						CountingResolver.forClasses(OkResource.class))
				.lifecycleObserver(observer)
				.build());

		soklet.close();

		Assertions.assertTrue(observer.terminal.await(2, TimeUnit.SECONDS));
		Assertions.assertEquals(List.of("will-stop-soklet", "will-stop-http",
				"did-stop-http", "did-stop-soklet"), observer.transitions);
	}

	@Test
	void blockingFrameworkSetupIsBoundedByStartupAndShutdownBudgets()
			throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch exited = new CountDownLatch(1);
		CountingResolver resolver = CountingResolver.forClasses(OkResource.class,
				() -> {
					entered.countDown();
					awaitIgnoringInterrupts(release);
					exited.countDown();
				});
		ReferenceHttpEndpoint http = new ReferenceHttpEndpoint();
		Soklet soklet = Soklet.fromConfig(directConfig(http, resolver)
				.internalLifecyclePolicy(shortPolicy()).build());
		ExecutorService executor = newExecutor();
		long began = System.nanoTime();
		Future<Throwable> start = executor.submit(() -> captureFailure(soklet::start));

		Assertions.assertTrue(entered.await(1, TimeUnit.SECONDS));
		Throwable failure;
		try {
			failure = start.get(3, TimeUnit.SECONDS);
		} finally {
			release.countDown();
		}

		Assertions.assertInstanceOf(SokletStartupException.class, failure);
		SokletStartupException startupFailure = (SokletStartupException) failure;
		Assertions.assertEquals(InternalStartupDisposition.TIMED_OUT,
				startupFailure.getInternalStartupDisposition());
		Assertions.assertInstanceOf(TimeoutException.class,
				startupFailure.getCause());
		Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - began)
				.compareTo(Duration.ofSeconds(3)) < 0);
		Assertions.assertEquals(0, http.attachCalls(),
				"A timed-out setup must not enter transport attachment");
		Assertions.assertTrue(exited.await(1, TimeUnit.SECONDS));
		Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
				soklet.getDirectLifecycle().state());
	}

	@Test
	void blockingTransportStartIsBoundedAndCannotPublishLateReadiness()
			throws Exception {
		CountDownLatch startEntered = new CountDownLatch(1);
		CountDownLatch releaseStart = new CountDownLatch(1);
		CountDownLatch startExited = new CountDownLatch(1);
		ReferenceHttpEndpoint http = new ReferenceHttpEndpoint();
		http.onStart(() -> {
			startEntered.countDown();
			awaitIgnoringInterrupts(releaseStart);
			startExited.countDown();
		});
		Soklet soklet = Soklet.fromConfig(directConfig(http,
				CountingResolver.forClasses(OkResource.class))
				.internalLifecyclePolicy(shortPolicy()).build());
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(soklet::start));

		Assertions.assertTrue(startEntered.await(1, TimeUnit.SECONDS));
		Throwable failure;
		try {
			failure = start.get(3, TimeUnit.SECONDS);
		} finally {
			releaseStart.countDown();
		}

		Assertions.assertInstanceOf(SokletStartupException.class, failure);
		Assertions.assertEquals(InternalStartupDisposition.TIMED_OUT,
				((SokletStartupException) failure).getInternalStartupDisposition());
		Assertions.assertFalse(soklet.isStarted());
		Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
				soklet.getDirectLifecycle().state());
		Assertions.assertTrue(startExited.await(1, TimeUnit.SECONDS));
		Assertions.assertFalse(soklet.isStarted(),
				"A late-returning start call must not reopen global readiness");
	}

	@Test
	void admissionRemainsClosedUntilEveryConfiguredTransportHasStarted()
			throws Exception {
		ReferenceHttpEndpoint http = new ReferenceHttpEndpoint();
		ReferenceSseEndpoint sse = new ReferenceSseEndpoint();
		CountDownLatch sseStartEntered = new CountDownLatch(1);
		CountDownLatch releaseSseStart = new CountDownLatch(1);
		sse.onStart(() -> {
			sseStartEntered.countDown();
			awaitIgnoringInterrupts(releaseSseStart);
		});
		SokletConfig config = directConfig(http,
				CountingResolver.forClasses(OkResource.class))
				.sseServer(sse).build();
		Soklet soklet = Soklet.fromConfig(config);
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() -> captureFailure(soklet::start));

		Assertions.assertTrue(sseStartEntered.await(1, TimeUnit.SECONDS));
		Assertions.assertEquals(1, http.startCalls());
		Assertions.assertTrue(http.invoke("/ok").isEmpty(),
				"HTTP admission must remain closed while SSE startup is incomplete");
		releaseSseStart.countDown();
		Assertions.assertNull(start.get(3, TimeUnit.SECONDS));

		Assertions.assertTrue(soklet.isStarted());
		HttpRequestResult response = http.invoke("/ok").orElseThrow();
		Assertions.assertEquals(200,
				response.getMarshaledResponse().getStatusCode());
		soklet.stop();
		Assertions.assertEquals(InternalStartupDisposition.READY,
				soklet.getDirectLifecycle().result().orElseThrow()
						.startupDisposition());
	}

	@Test
	void stopFromAnAdmittedHttpHandlerPublishesIntentThenFailsFast()
			throws Exception {
		ReferenceHttpEndpoint http = new ReferenceHttpEndpoint();
		Soklet soklet = Soklet.fromConfig(directConfig(http,
				CountingResolver.forClasses(SelfStoppingResource.class)).build());
		SelfStoppingResource.SOKLET.set(soklet);
		soklet.start();

		HttpRequestResult response = http.invoke("/self-stop").orElseThrow();

		Assertions.assertEquals(200,
				response.getMarshaledResponse().getStatusCode());
		Throwable handlerFailure = SelfStoppingResource.STOP_FAILURE.get();
		Assertions.assertInstanceOf(IllegalStateException.class, handlerFailure);
		Assertions.assertTrue(handlerFailure.getMessage()
				.contains("tracked lifecycle execution"));
		Assertions.assertFalse(soklet.isStarted(),
				"The fail-fast self-stop must still publish shutdown intent");

		// The same owner joined from outside admitted execution must observe the
		// shared terminal result rather than initiating a second stop transition.
		soklet.stop();
		InternalShutdownResult result = soklet.getDirectLifecycle().result()
				.orElseThrow();
		Assertions.assertEquals(InternalShutdownDisposition.GRACEFUL,
				result.disposition());
		Assertions.assertEquals(InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION,
				result.participantResult(InternalParticipantKind.HTTP).orElseThrow()
						.disposition());
	}

	@Test
	void startupValidationRunsAtStartAndRetainsItsExactCause() {
		ReferenceHttpEndpoint http = new ReferenceHttpEndpoint();
		CountingResolver resolver = CountingResolver.empty();
		Soklet soklet = Soklet.fromConfig(directConfig(http, resolver).build());
		Assertions.assertEquals(0, resolver.snapshotCalls());

		SokletStartupException exception = Assertions.assertThrows(
				SokletStartupException.class, soklet::start);

		Assertions.assertEquals(1, resolver.snapshotCalls());
		Assertions.assertInstanceOf(IllegalStateException.class,
				exception.getCause());
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				exception.getInternalStartupDisposition());
		Assertions.assertSame(exception.getInternalShutdownResult(),
				soklet.getDirectLifecycle().result().orElseThrow());
		InternalParticipantShutdownResult httpResult = exception
				.getInternalShutdownResult()
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertEquals(InternalParticipantShutdownDisposition.NOT_STARTED,
				httpResult.disposition());
		Assertions.assertSame(exception.getCause(),
				httpResult.failures().get(0),
				"The validation cause must survive startup and result handoff exactly");
		Assertions.assertEquals(0, http.attachCalls());
	}

	@Test
	void synchronousParticipantStartThrowRemainsStartupFailureNotUnexpected() {
		RuntimeException exactFailure = new IllegalStateException(
				"SSE start failed synchronously");
		ReferenceHttpEndpoint http = new ReferenceHttpEndpoint();
		ReferenceSseEndpoint sse = new ReferenceSseEndpoint();
		sse.onStart(() -> { throw exactFailure; });
		Soklet soklet = Soklet.fromConfig(directConfig(http,
				CountingResolver.forClasses(OkResource.class))
				.sseServer(sse)
				.build());

		SokletStartupException startupFailure = Assertions.assertThrows(
				SokletStartupException.class, soklet::start);

		Assertions.assertSame(exactFailure, startupFailure.getCause());
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				startupFailure.getInternalStartupDisposition());
		InternalParticipantShutdownResult sseResult = startupFailure
				.getInternalShutdownResult()
				.participantResult(InternalParticipantKind.SSE).orElseThrow();
		Assertions.assertNotEquals(
				InternalParticipantShutdownDisposition.UNEXPECTED_TERMINATION,
				sseResult.disposition());
		Assertions.assertTrue(sseResult.failures().stream()
				.anyMatch(failure -> failure == exactFailure));
		Assertions.assertDoesNotThrow(soklet::stop);
	}

	@Test
	void stableTransportIdentityCannotBeClaimedByTwoLiveOwners() {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		ReferenceHttpEndpoint firstEndpoint = new ReferenceHttpEndpoint(identity);
		ReferenceHttpEndpoint secondEndpoint = new ReferenceHttpEndpoint(identity);
		Soklet first = Soklet.fromConfig(directConfig(firstEndpoint,
				CountingResolver.forClasses(OkResource.class)).build());

		TransportOwnershipException conflict = Assertions.assertThrows(
				TransportOwnershipException.class, () -> Soklet.fromConfig(
				directConfig(secondEndpoint,
							CountingResolver.forClasses(OkResource.class)).build()));

		Assertions.assertTrue(conflict.getMessage().contains("already owned"));
		Assertions.assertEquals(InternalParticipantKind.HTTP,
				conflict.getInternalParticipantKind());
		Assertions.assertSame(ReferenceHttpEndpoint.class,
				conflict.getTransportClass());
		Assertions.assertSame(identity, firstEndpoint.identity());
		Assertions.assertSame(identity, secondEndpoint.identity());
		first.close();
	}

	private static SokletConfig.@NonNull Builder directConfig(
			@NonNull ReferenceHttpEndpoint http,
			@NonNull ResourceMethodResolver resolver) {
		return SokletConfig.withHttpServer(http)
				.resourceMethodResolver(resolver);
	}

	@NonNull
	private static InternalLifecyclePolicy shortPolicy() {
		return new InternalLifecyclePolicy(Optional.of(SHORT_PHASE), SHORT_PHASE,
				SHORT_PHASE, SHORT_PHASE);
	}

	@NonNull
	private ExecutorService newExecutor() {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		this.executors.add(executor);
		return executor;
	}

	@Nullable
	private static Throwable captureFailure(@NonNull Runnable runnable) {
		try {
			runnable.run();
			return null;
		} catch (Throwable throwable) {
			return throwable;
		}
	}

	private static void awaitIgnoringInterrupts(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static final class CountingResolver implements ResourceMethodResolver {
		@NonNull
		private final ResourceMethodResolver delegate;
		@NonNull
		private final Runnable beforeSnapshot;
		@NonNull
		private final AtomicInteger snapshotCalls;

		private CountingResolver(@NonNull ResourceMethodResolver delegate,
				@NonNull Runnable beforeSnapshot) {
			this.delegate = delegate;
			this.beforeSnapshot = beforeSnapshot;
			this.snapshotCalls = new AtomicInteger();
		}

		@NonNull
		static CountingResolver forClasses(@NonNull Class<?>... classes) {
			return forClasses(classes, () -> { });
		}

		@NonNull
		static CountingResolver forClasses(@NonNull Class<?> resourceClass,
				@NonNull Runnable beforeSnapshot) {
			return forClasses(new Class<?>[]{resourceClass}, beforeSnapshot);
		}

		@NonNull
		private static CountingResolver forClasses(@NonNull Class<?>[] classes,
				@NonNull Runnable beforeSnapshot) {
			return new CountingResolver(ResourceMethodResolver.fromClasses(
					Set.of(classes)), beforeSnapshot);
		}

		@NonNull
		static CountingResolver empty() {
			return new CountingResolver(ResourceMethodResolver.fromMethods(Set.of()),
					() -> { });
		}

		int snapshotCalls() {
			return this.snapshotCalls.get();
		}

		@Override
		@NonNull
		public Optional<ResourceMethod> resourceMethodForRequest(
				@NonNull Request request, @NonNull ServerType serverType) {
			return this.delegate.resourceMethodForRequest(request, serverType);
		}

		@Override
		@NonNull
		public Set<@NonNull ResourceMethod> getResourceMethods() {
			this.snapshotCalls.incrementAndGet();
			this.beforeSnapshot.run();
			return this.delegate.getResourceMethods();
		}
	}

	private static final class StopTransitionObserver
			implements LifecycleObserver {
		@NonNull private final List<String> transitions =
				new CopyOnWriteArrayList<>();
		@NonNull private final CountDownLatch terminal = new CountDownLatch(1);

		@Override public void willStopSoklet(@NonNull Soklet soklet) {
			this.transitions.add("will-stop-soklet");
		}

		@Override public void willStopHttpServer(@NonNull HttpServer httpServer) {
			this.transitions.add("will-stop-http");
		}

		@Override public void didStopHttpServer(@NonNull HttpServer httpServer) {
			this.transitions.add("did-stop-http");
		}

		@Override public void didStopSoklet(@NonNull Soklet soklet) {
			this.transitions.add("did-stop-soklet");
			this.terminal.countDown();
		}
	}

	private static final class ReferenceHttpEndpoint
			implements HttpServer, InternalHttpTransportEndpoint {
		@NonNull
		private final InternalTransportIdentity identity;
		@NonNull
		private final AtomicInteger initializeCalls;
		@NonNull
		private final AtomicInteger attachCalls;
		@NonNull
		private final AtomicInteger startCalls;
		@NonNull
		private final AtomicBoolean started;
		@NonNull
		private final AtomicBoolean terminationSignalled;
		@NonNull
		private final AtomicReference<Runnable> onStart;
		@NonNull
		private final AtomicReference<HttpServer.RequestHandler> requestHandler;
		@NonNull
		private final AtomicReference<InternalTransportTerminationSignal>
				terminationSignal;

		private ReferenceHttpEndpoint() {
			this(InternalTransportIdentity.create());
		}

		private ReferenceHttpEndpoint(
				@NonNull InternalTransportIdentity identity) {
			this.identity = identity;
			this.initializeCalls = new AtomicInteger();
			this.attachCalls = new AtomicInteger();
			this.startCalls = new AtomicInteger();
			this.started = new AtomicBoolean();
			this.terminationSignalled = new AtomicBoolean();
			this.onStart = new AtomicReference<>(() -> { });
			this.requestHandler = new AtomicReference<>();
			this.terminationSignal = new AtomicReference<>();
		}

		void onStart(@NonNull Runnable callback) {
			this.onStart.set(callback);
		}

		int initializeCalls() {
			return this.initializeCalls.get();
		}

		int attachCalls() {
			return this.attachCalls.get();
		}

		int startCalls() {
			return this.startCalls.get();
		}

		@NonNull
		Optional<HttpRequestResult> invoke(@NonNull String path) {
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			this.requestHandler.get().handleRequest(
					Request.withPath(HttpMethod.GET, path).build(), result::set);
			return Optional.ofNullable(result.get());
		}

		@Override
		@NonNull
		public InternalTransportIdentity identity() {
			return this.identity;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<HttpServer.RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			this.attachCalls.incrementAndGet();
			this.requestHandler.set(context.requestHandler());
			this.terminationSignal.set(context.terminationSignal());
			return new InternalTransportRuntime() {
				@Override
				public void start(@NonNull InternalStartupContext context) {
					startCalls.incrementAndGet();
					started.set(true);
					onStart.get().run();
				}

				@Override
				public void quiesce(@NonNull InternalShutdownContext context) {
					terminate();
				}

				@Override
				public void force(@NonNull InternalShutdownContext context) {
					terminate();
				}
			};
		}

		@Override
		public void start() {
			throw new AssertionError("Direct endpoint startup must use its runtime");
		}

		@Override
		public void stop() {
			terminate();
		}

		@Override
		@NonNull
		public Boolean isStarted() {
			return this.started.get();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
				HttpServer.@NonNull RequestHandler requestHandler) {
			this.initializeCalls.incrementAndGet();
		}

		private void terminate() {
			this.started.set(false);
			InternalTransportTerminationSignal signal = this.terminationSignal.get();
			if (signal != null && this.terminationSignalled.compareAndSet(false, true))
				signal.signalTerminated();
		}
	}

	private static final class ReferenceSseEndpoint
			implements SseServer, InternalSseTransportEndpoint {
		@NonNull
		private final InternalTransportIdentity identity;
		@NonNull
		private final AtomicInteger startCalls;
		@NonNull
		private final AtomicBoolean started;
		@NonNull
		private final AtomicBoolean terminationSignalled;
		@NonNull
		private final AtomicReference<Runnable> onStart;
		@NonNull
		private final AtomicReference<InternalTransportTerminationSignal>
				terminationSignal;

		private ReferenceSseEndpoint() {
			this.identity = InternalTransportIdentity.create();
			this.startCalls = new AtomicInteger();
			this.started = new AtomicBoolean();
			this.terminationSignalled = new AtomicBoolean();
			this.onStart = new AtomicReference<>(() -> { });
			this.terminationSignal = new AtomicReference<>();
		}

		void onStart(@NonNull Runnable callback) {
			this.onStart.set(callback);
		}

		@Override
		@NonNull
		public InternalTransportIdentity identity() {
			return this.identity;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<SseServer.RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			this.terminationSignal.set(context.terminationSignal());
			return new InternalTransportRuntime() {
				@Override
				public void start(@NonNull InternalStartupContext context) {
					startCalls.incrementAndGet();
					started.set(true);
					onStart.get().run();
				}

				@Override
				public void quiesce(@NonNull InternalShutdownContext context) {
					terminate();
				}

				@Override
				public void force(@NonNull InternalShutdownContext context) {
					terminate();
				}
			};
		}

		@Override
		public void start() {
			throw new AssertionError("Direct endpoint startup must use its runtime");
		}

		@Override
		public void stop() {
			terminate();
		}

		@Override
		@NonNull
		public Boolean isStarted() {
			return this.started.get();
		}

		@Override
		@NonNull
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			return Optional.empty();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
				SseServer.@NonNull RequestHandler requestHandler) {
			throw new AssertionError("Direct endpoint attachment must use attach(...)");
		}

		private void terminate() {
			this.started.set(false);
			InternalTransportTerminationSignal signal = this.terminationSignal.get();
			if (signal != null && this.terminationSignalled.compareAndSet(false, true))
				signal.signalTerminated();
		}
	}

	public static final class OkResource {
		@GET("/ok")
		@NonNull
		public String ok() {
			return "ok";
		}
	}

	public static final class SelfStoppingResource {
		@NonNull
		private static final AtomicReference<Soklet> SOKLET = new AtomicReference<>();
		@NonNull
		private static final AtomicReference<Throwable> STOP_FAILURE =
				new AtomicReference<>();

		@GET("/self-stop")
		@NonNull
		public String stop() {
			try {
				SOKLET.get().stop();
			} catch (Throwable throwable) {
				STOP_FAILURE.set(throwable);
			}
			return "stopped";
		}
	}
}
