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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/** Direct-owner acceptance coverage for composed HTTP transport graphs. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectHttpCompositionTests {
	@Test
	void transparentDecoratorSharesRootSignalRuntimeAndRequestPath() {
		AlternativeHttpEngine engine = new AlternativeHttpEngine();
		TransparentHttpDecorator outer = new TransparentHttpDecorator("outer", engine);
		SokletConfig config = config(outer);

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();

			Assertions.assertSame(engine.getTransportIdentity(),
					outer.getTransportIdentity());
			Assertions.assertSame(config, outer.attachedConfiguration());
			Assertions.assertSame(config, engine.attachedConfiguration());
			Assertions.assertSame(outer.attachStartupContext(),
					engine.attachStartupContext());
			Assertions.assertSame(outer.terminationSignal(),
					engine.terminationSignal(),
					"Transparent delegation must preserve the exact root signal");
			Assertions.assertSame(engine.attachedRuntime(), outer.attachedRuntime(),
					"The transparent decorator must return the delegate runtime");
			Assertions.assertEquals(1, outer.attachCalls());
			Assertions.assertEquals(1, engine.attachCalls());
			Assertions.assertEquals(1, engine.startCalls());

			HttpRequestResult response = engine.invoke("/ok").orElseThrow();
			Assertions.assertEquals(200,
					response.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(List.of("outer"), outer.handlerObservations());

			soklet.shutdown().toCompletableFuture().join();

			Assertions.assertEquals(1, engine.quiesceCalls());
			Assertions.assertEquals(0, engine.forceCalls());
			assertOneGracefulHttpParticipant(soklet);
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void lifecycleOwningDecoratorRequiresDelegateAndOuterProof()
			throws Exception {
		AlternativeHttpEngine engine = new AlternativeHttpEngine();
		OwningHttpDecorator outer = new OwningHttpDecorator("outer", engine,
				false);
		SokletConfig config = config(outer);
		Soklet soklet = Soklet.fromConfig(config);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			soklet.start();

			Assertions.assertSame(engine.getTransportIdentity(),
					outer.getTransportIdentity());
			Assertions.assertSame(config, outer.attachedConfiguration());
			Assertions.assertSame(config, engine.attachedConfiguration());
			Assertions.assertSame(outer.attachStartupContext(),
					engine.attachStartupContext());
			Assertions.assertNotSame(outer.terminationSignal(),
					engine.terminationSignal(),
					"Lifecycle-owning delegation must create a child signal");
			Assertions.assertSame(engine.attachedRuntime(), outer.delegateRuntime());

			HttpRequestResult response = engine.invoke("/ok").orElseThrow();
			Assertions.assertEquals(200,
					response.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(List.of("outer"), outer.handlerObservations());

			Future<?> stopping = executor.submit(() ->
					soklet.shutdown().toCompletableFuture().join());
			Assertions.assertTrue(outer.awaitDelegateProof(2, TimeUnit.SECONDS),
					"The child proof stage should complete without root proof");
			Assertions.assertNull(outer.delegateProofFailure());
			Assertions.assertTrue(outer.awaitCleanupStarted(2, TimeUnit.SECONDS),
					"The child-proof callback must submit owned cleanup promptly");
			Assertions.assertNotSame(outer.delegateProofCallbackThread(),
					outer.cleanupThread(),
					"Proof observation must hand cleanup to decorator-owned execution");
			Assertions.assertFalse(stopping.isDone(),
					"Delegate proof alone must not complete the configured graph");
			Assertions.assertFalse(outer.cleanupFinished(),
					"The owned cleanup must remain active behind its release barrier");
			Assertions.assertFalse(outer.rootProofPublished(),
					"Root proof must not precede completion of owned cleanup");
			Assertions.assertEquals(1, outer.quiesceCalls());
			Assertions.assertEquals(1, engine.quiesceCalls());

			outer.releaseCleanup();
			Assertions.assertTrue(outer.awaitCleanupFinished(2, TimeUnit.SECONDS));
			Assertions.assertTrue(outer.awaitRootProof(2, TimeUnit.SECONDS));
			Assertions.assertTrue(outer.executorTerminationHookRan());
			stopping.get(2, TimeUnit.SECONDS);

			Assertions.assertEquals(0, outer.forceCalls());
			Assertions.assertEquals(0, engine.forceCalls());
			assertOneGracefulHttpParticipant(soklet);
		} finally {
			outer.releaseCleanup();
			soklet.close();
			executor.shutdownNow();
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void twoLevelOwningDecoratorsRemainOneConfiguredParticipant()
			throws Exception {
		AlternativeHttpEngine engine = new AlternativeHttpEngine(false);
		OwningHttpDecorator inner = new OwningHttpDecorator("inner", engine,
				true);
		OwningHttpDecorator outer = new OwningHttpDecorator("outer", inner,
				true);
		SokletConfig config = config(outer);
		Soklet soklet = Soklet.fromConfig(config);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			TransportOwnershipException conflict = Assertions.assertThrows(
					TransportOwnershipException.class,
					() -> Soklet.fromConfig(config(engine)));
			Assertions.assertEquals(ParticipantKind.HTTP,
					conflict.getParticipantKind());
			Assertions.assertSame(AlternativeHttpEngine.class,
					conflict.getTransportClass());

			soklet.start();

			Assertions.assertSame(engine.getTransportIdentity(),
					inner.getTransportIdentity());
			Assertions.assertSame(engine.getTransportIdentity(),
					outer.getTransportIdentity());
			Assertions.assertSame(config, outer.attachedConfiguration());
			Assertions.assertSame(config, inner.attachedConfiguration());
			Assertions.assertSame(config, engine.attachedConfiguration());
			Assertions.assertSame(outer.attachStartupContext(),
					inner.attachStartupContext());
			Assertions.assertSame(outer.attachStartupContext(),
					engine.attachStartupContext());
			Assertions.assertNotSame(outer.terminationSignal(),
					inner.terminationSignal());
			Assertions.assertNotSame(inner.terminationSignal(),
					engine.terminationSignal());
			Assertions.assertNotSame(outer.terminationSignal(),
					engine.terminationSignal());
			Assertions.assertEquals(1, outer.attachCalls());
			Assertions.assertEquals(1, inner.attachCalls());
			Assertions.assertEquals(1, engine.attachCalls());
			Assertions.assertEquals(1, outer.startCalls());
			Assertions.assertEquals(1, inner.startCalls());
			Assertions.assertEquals(1, engine.startCalls());

			HttpRequestResult response = engine.invoke("/ok").orElseThrow();
			Assertions.assertEquals(200,
					response.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(List.of("inner"), inner.handlerObservations());
			Assertions.assertEquals(List.of("outer"), outer.handlerObservations());

			Future<?> stopping = executor.submit(() ->
					soklet.shutdown().toCompletableFuture().join());
			Assertions.assertTrue(outer.awaitQuiesceReturned(2, TimeUnit.SECONDS));
			Assertions.assertTrue(engine.awaitQuiesceReturned(2, TimeUnit.SECONDS));
			Assertions.assertFalse(inner.delegateProofObserved(),
					"The inner child stage must wait for deepest descendant proof");
			Assertions.assertFalse(outer.delegateProofObserved(),
					"The outer subtree stage must include the deepest descendant");
			Assertions.assertFalse(stopping.isDone(),
					"Missing deepest proof must keep the configured graph pending");

			engine.releaseTermination();
			Assertions.assertTrue(inner.awaitDelegateProof(2, TimeUnit.SECONDS));
			Assertions.assertTrue(outer.awaitDelegateProof(2, TimeUnit.SECONDS));
			Assertions.assertTrue(inner.awaitCleanupFinished(2, TimeUnit.SECONDS));
			Assertions.assertTrue(outer.awaitCleanupFinished(2, TimeUnit.SECONDS));
			Assertions.assertTrue(inner.awaitRootProof(2, TimeUnit.SECONDS));
			Assertions.assertTrue(outer.awaitRootProof(2, TimeUnit.SECONDS));
			Assertions.assertTrue(inner.executorTerminationHookRan());
			Assertions.assertTrue(outer.executorTerminationHookRan());
			stopping.get(2, TimeUnit.SECONDS);
			Assertions.assertNull(inner.delegateProofFailure());
			Assertions.assertNull(outer.delegateProofFailure());
			Assertions.assertEquals(1, outer.quiesceCalls());
			Assertions.assertEquals(1, inner.quiesceCalls());
			Assertions.assertEquals(1, engine.quiesceCalls());
			Assertions.assertEquals(0, outer.forceCalls());
			Assertions.assertEquals(0, inner.forceCalls());
			Assertions.assertEquals(0, engine.forceCalls());
			assertOneGracefulHttpParticipant(soklet);
		} finally {
			engine.releaseTermination();
			inner.releaseCleanup();
			outer.releaseCleanup();
			soklet.close();
			executor.shutdownNow();
		}
	}

	@Test
	void forceBeforeChildProofCancelsUnsubmittedCleanupWithoutRejection()
			throws Exception {
		AlternativeHttpEngine engine = new AlternativeHttpEngine(false, true);
		OwningHttpDecorator outer = new OwningHttpDecorator("outer", engine,
				false);
		SokletConfig config = config(outer, new InternalLifecyclePolicy(
				Optional.of(Duration.ofSeconds(2)), Duration.ofMillis(100),
				Duration.ofMillis(75), Duration.ofSeconds(2)));
		Soklet soklet = Soklet.fromConfig(config);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			soklet.start();

			Future<?> stopping = executor.submit(() ->
					soklet.shutdown().toCompletableFuture().join());
			Assertions.assertTrue(engine.awaitQuiesceReturned(2, TimeUnit.SECONDS));

			Assertions.assertTrue(outer.awaitForceReturned(2, TimeUnit.SECONDS));
			Assertions.assertTrue(outer.awaitDelegateProof(2, TimeUnit.SECONDS));
			Assertions.assertTrue(outer.awaitCleanupFinished(2, TimeUnit.SECONDS));
			Assertions.assertTrue(outer.awaitRootProof(2, TimeUnit.SECONDS));
			stopping.get(2, TimeUnit.SECONDS);

			Assertions.assertFalse(outer.cleanupStarted(),
					"Force-first cancellation must not submit the deferred cleanup");
			Assertions.assertEquals(CleanupState.FINISHED, outer.cleanupState());
			Assertions.assertEquals(0, outer.cleanupSubmissions());
			Assertions.assertEquals(0, outer.rejectedCleanupSubmissions());
			Assertions.assertEquals(2, outer.cleanupForceRequests(),
					"One force phase rechecks cancellation after child propagation");
			Assertions.assertNull(outer.delegateProofFailure());
			Assertions.assertTrue(outer.executorTerminationHookRan());
			Assertions.assertEquals(1, outer.forceCalls());
			Assertions.assertEquals(1, engine.forceCalls());
			assertOneForcedHttpParticipant(soklet);
		} finally {
			outer.releaseCleanup();
			engine.releaseTermination();
			soklet.close();
			executor.shutdownNow();
		}
	}

	@NonNull
	private static SokletConfig config(@NonNull ComposedHttpEndpoint endpoint) {
		return config(endpoint, InternalLifecyclePolicy.defaults());
	}

	@NonNull
	private static SokletConfig config(@NonNull ComposedHttpEndpoint endpoint,
			@NonNull InternalLifecyclePolicy lifecyclePolicy) {
		return SokletConfig.withHttpServer(endpoint)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(OkResource.class)))
				.internalLifecyclePolicy(lifecyclePolicy)
				.build();
	}

	private static void assertOneGracefulHttpParticipant(
			@NonNull Soklet soklet) {
		InternalShutdownResult result = soklet.getDirectLifecycle().result()
				.orElseThrow();
		Assertions.assertEquals(InternalStartupDisposition.READY,
				result.startupDisposition());
		Assertions.assertEquals(InternalShutdownDisposition.GRACEFUL,
				result.disposition());
		Assertions.assertEquals(1, result.participantResults().size(),
				"A composed graph remains one configured participant");
		InternalParticipantShutdownResult http = result
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION,
				http.disposition());
		Assertions.assertTrue(http.failures().isEmpty());
		Assertions.assertTrue(http.residualActivity().isEmpty());
	}

	private static void assertOneForcedHttpParticipant(@NonNull Soklet soklet) {
		InternalShutdownResult result = soklet.getDirectLifecycle().result()
				.orElseThrow();
		Assertions.assertEquals(InternalStartupDisposition.READY,
				result.startupDisposition());
		Assertions.assertEquals(InternalShutdownDisposition.FORCED,
				result.disposition());
		Assertions.assertEquals(1, result.participantResults().size());
		InternalParticipantShutdownResult http = result
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.FORCED_TERMINATION,
				http.disposition());
		Assertions.assertTrue(http.failures().isEmpty());
		Assertions.assertTrue(http.residualActivity().isEmpty());
	}

	private enum CleanupState {
		NOT_SUBMITTED,
		SUBMITTED,
		RUNNING,
		CANCELLED,
		FINISHED
	}

	private interface ComposedHttpEndpoint extends HttpServer {
	}

	private static final class AlternativeHttpEngine
			implements ComposedHttpEndpoint {
		@NonNull
		private final TransportIdentity identity = TransportIdentity.create();
		@NonNull
		private final AtomicInteger attachCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger startCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull
		private final AtomicBoolean terminationSignalled = new AtomicBoolean();
		@NonNull
		private final AtomicBoolean terminationRequested = new AtomicBoolean();
		@NonNull
		private final AtomicBoolean terminationReleased;
		private final boolean releaseTerminationOnForce;
		@NonNull
		private final CountDownLatch quiesceReturned = new CountDownLatch(1);
		@NonNull
		private final AtomicReference<Object> attachedConfiguration =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<HttpServer.RequestHandler> requestHandler =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<StartupContext> attachStartupContext =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<TransportTerminationSignal>
				terminationSignal = new AtomicReference<>();
		@NonNull
		private final AtomicReference<TransportRuntime> attachedRuntime =
				new AtomicReference<>();

		private AlternativeHttpEngine() {
			this(true, false);
		}

		private AlternativeHttpEngine(boolean terminationInitiallyReleased) {
			this(terminationInitiallyReleased, false);
		}

		private AlternativeHttpEngine(boolean terminationInitiallyReleased,
				boolean releaseTerminationOnForce) {
			this.terminationReleased = new AtomicBoolean(
					terminationInitiallyReleased);
			this.releaseTerminationOnForce = releaseTerminationOnForce;
		}

		int attachCalls() {
			return this.attachCalls.get();
		}

		int startCalls() {
			return this.startCalls.get();
		}

		int quiesceCalls() {
			return this.quiesceCalls.get();
		}

		int forceCalls() {
			return this.forceCalls.get();
		}

		boolean awaitQuiesceReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.quiesceReturned.await(timeout, unit);
		}

		void releaseTermination() {
			this.terminationReleased.set(true);
			if (this.terminationRequested.get())
				publishTermination();
		}

		@NonNull
		Object attachedConfiguration() {
			return requireNonNull(this.attachedConfiguration.get());
		}

		@NonNull
		StartupContext attachStartupContext() {
			return requireNonNull(this.attachStartupContext.get());
		}

		@NonNull
		TransportTerminationSignal terminationSignal() {
			return requireNonNull(this.terminationSignal.get());
		}

		@NonNull
		TransportRuntime attachedRuntime() {
			return requireNonNull(this.attachedRuntime.get());
		}

		@NonNull
		Optional<HttpRequestResult> invoke(@NonNull String path) {
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			requireNonNull(this.requestHandler.get()).handleRequest(
					Request.withPath(HttpMethod.GET, path).build(), result::set);
			return Optional.ofNullable(result.get());
		}

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.attachCalls.incrementAndGet();
			this.attachedConfiguration.set(context.getSokletConfig());
			this.requestHandler.set(context.getAdmissionFencedRequestHandler());
			this.attachStartupContext.set(startupContext);
			this.terminationSignal.set(context.getTerminationSignal());
			TransportRuntime runtime = new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					startCalls.incrementAndGet();
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					quiesceCalls.incrementAndGet();
					try {
						requestTermination();
					} finally {
						quiesceReturned.countDown();
					}
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					forceCalls.incrementAndGet();
					if (releaseTerminationOnForce)
						terminationReleased.set(true);
					requestTermination();
				}
			};
			this.attachedRuntime.set(runtime);
			return runtime;
		}

		private void requestTermination() {
			this.terminationRequested.set(true);
			if (this.terminationReleased.get())
				publishTermination();
		}

		private void publishTermination() {
			TransportTerminationSignal signal = this.terminationSignal.get();
			if (signal != null && this.terminationSignalled.compareAndSet(false, true))
				signal.signalTerminated();
		}
	}

	private static final class TransparentHttpDecorator
			implements ComposedHttpEndpoint {
		@NonNull
		private final String name;
		@NonNull
		private final ComposedHttpEndpoint delegate;
		@NonNull
		private final TransportIdentity identity;
		@NonNull
		private final AtomicInteger attachCalls = new AtomicInteger();
		@NonNull
		private final List<String> handlerObservations =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final AtomicReference<Object> attachedConfiguration =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<StartupContext> attachStartupContext =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<TransportTerminationSignal>
				terminationSignal = new AtomicReference<>();
		@NonNull
		private final AtomicReference<TransportRuntime> attachedRuntime =
				new AtomicReference<>();

		private TransparentHttpDecorator(@NonNull String name,
				@NonNull ComposedHttpEndpoint delegate) {
			this.name = requireNonNull(name);
			this.delegate = requireNonNull(delegate);
			this.identity = delegate.getTransportIdentity();
		}

		int attachCalls() {
			return this.attachCalls.get();
		}

		@NonNull
		List<String> handlerObservations() {
			return List.copyOf(this.handlerObservations);
		}

		@NonNull
		Object attachedConfiguration() {
			return requireNonNull(this.attachedConfiguration.get());
		}

		@NonNull
		StartupContext attachStartupContext() {
			return requireNonNull(this.attachStartupContext.get());
		}

		@NonNull
		TransportTerminationSignal terminationSignal() {
			return requireNonNull(this.terminationSignal.get());
		}

		@NonNull
		TransportRuntime attachedRuntime() {
			return requireNonNull(this.attachedRuntime.get());
		}

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.attachCalls.incrementAndGet();
			this.attachedConfiguration.set(context.getSokletConfig());
			this.attachStartupContext.set(startupContext);
			this.terminationSignal.set(context.getTerminationSignal());
			HttpServer.RequestHandler upstreamHandler =
					context.getAdmissionFencedRequestHandler();
			HttpServer.RequestHandler wrappedHandler = (request, consumer) -> {
				this.handlerObservations.add(this.name);
				upstreamHandler.handleRequest(request, consumer);
			};
			TransportRuntime runtime = context.attachTransparentDelegate(
					this.delegate, wrappedHandler);
			this.attachedRuntime.set(runtime);
			return runtime;
		}

	}

	private static final class OwningHttpDecorator
			implements ComposedHttpEndpoint {
		@NonNull
		private final String name;
		@NonNull
		private final ComposedHttpEndpoint delegate;
		@NonNull
		private final TransportIdentity identity;
		@NonNull
		private final AtomicInteger attachCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger startCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull
		private final List<String> handlerObservations =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final CountDownLatch delegateProofObserved = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch cleanupRelease = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch cleanupStarted = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch cleanupFinished = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch quiesceReturned = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch rootProofPublished = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch executorTerminationHook = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch forceReturned = new CountDownLatch(1);
		@NonNull
		private final AtomicBoolean forceRequested = new AtomicBoolean();
		@NonNull
		private final AtomicReference<CleanupState> cleanupState =
				new AtomicReference<>(CleanupState.NOT_SUBMITTED);
		@NonNull
		private final AtomicInteger cleanupSubmissions = new AtomicInteger();
		@NonNull
		private final AtomicInteger rejectedCleanupSubmissions = new AtomicInteger();
		@NonNull
		private final AtomicInteger cleanupForceRequests = new AtomicInteger();
		@NonNull
		private final AtomicReference<Object> attachedConfiguration =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<StartupContext> attachStartupContext =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<TransportTerminationSignal>
				terminationSignal = new AtomicReference<>();
		@NonNull
		private final AtomicReference<TransportDelegateAttachment>
				delegateAttachment = new AtomicReference<>();
		@NonNull
		private final AtomicReference<@Nullable Throwable> delegateProofFailure =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<OwnedCleanupExecutor> cleanupExecutor =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<Thread> delegateProofCallbackThread =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<Thread> cleanupThread =
				new AtomicReference<>();

		private OwningHttpDecorator(@NonNull String name,
				@NonNull ComposedHttpEndpoint delegate,
				boolean cleanupInitiallyReleased) {
			this.name = requireNonNull(name);
			this.delegate = requireNonNull(delegate);
			this.identity = delegate.getTransportIdentity();
			if (cleanupInitiallyReleased)
				this.cleanupRelease.countDown();
		}

		int attachCalls() {
			return this.attachCalls.get();
		}

		int startCalls() {
			return this.startCalls.get();
		}

		int quiesceCalls() {
			return this.quiesceCalls.get();
		}

		int forceCalls() {
			return this.forceCalls.get();
		}

		@NonNull
		List<String> handlerObservations() {
			return List.copyOf(this.handlerObservations);
		}

		@NonNull
		Object attachedConfiguration() {
			return requireNonNull(this.attachedConfiguration.get());
		}

		@NonNull
		StartupContext attachStartupContext() {
			return requireNonNull(this.attachStartupContext.get());
		}

		@NonNull
		TransportTerminationSignal terminationSignal() {
			return requireNonNull(this.terminationSignal.get());
		}

		@NonNull
		TransportRuntime delegateRuntime() {
			return requireNonNull(this.delegateAttachment.get()).getRuntime();
		}

		boolean awaitDelegateProof(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.delegateProofObserved.await(timeout, unit);
		}

		boolean delegateProofObserved() {
			return this.delegateProofObserved.getCount() == 0;
		}

		boolean awaitCleanupStarted(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.cleanupStarted.await(timeout, unit);
		}

		boolean awaitCleanupFinished(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.cleanupFinished.await(timeout, unit);
		}

		boolean cleanupFinished() {
			return this.cleanupFinished.getCount() == 0;
		}

		boolean cleanupStarted() {
			return this.cleanupStarted.getCount() == 0;
		}

		@NonNull
		CleanupState cleanupState() {
			return this.cleanupState.get();
		}

		int cleanupSubmissions() {
			return this.cleanupSubmissions.get();
		}

		int rejectedCleanupSubmissions() {
			return this.rejectedCleanupSubmissions.get();
		}

		int cleanupForceRequests() {
			return this.cleanupForceRequests.get();
		}

		boolean awaitQuiesceReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.quiesceReturned.await(timeout, unit);
		}

		boolean awaitForceReturned(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.forceReturned.await(timeout, unit);
		}

		boolean awaitRootProof(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.rootProofPublished.await(timeout, unit);
		}

		boolean rootProofPublished() {
			return this.rootProofPublished.getCount() == 0;
		}

		boolean executorTerminationHookRan() {
			return this.executorTerminationHook.getCount() == 0;
		}

		@NonNull
		Thread delegateProofCallbackThread() {
			return requireNonNull(this.delegateProofCallbackThread.get());
		}

		@NonNull
		Thread cleanupThread() {
			return requireNonNull(this.cleanupThread.get());
		}

		@Nullable
		Throwable delegateProofFailure() {
			return this.delegateProofFailure.get();
		}

		void releaseCleanup() {
			this.cleanupRelease.countDown();
		}

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.attachCalls.incrementAndGet();
			this.attachedConfiguration.set(context.getSokletConfig());
			this.attachStartupContext.set(startupContext);
			this.terminationSignal.set(context.getTerminationSignal());
			TransportTerminationSignal rootSignal = context.getTerminationSignal();
			HttpServer.RequestHandler upstreamHandler =
					context.getAdmissionFencedRequestHandler();
			HttpServer.RequestHandler wrappedHandler = (request, consumer) -> {
				this.handlerObservations.add(this.name);
				upstreamHandler.handleRequest(request, consumer);
			};
			TransportDelegateAttachment attachment =
					context.attachLifecycleOwningDelegate(this.delegate,
							wrappedHandler);
			this.delegateAttachment.set(attachment);
			attachment.whenTerminated().whenComplete((ignored, failure) -> {
				this.delegateProofCallbackThread.set(Thread.currentThread());
				if (failure != null) {
					this.delegateProofFailure.compareAndSet(null, failure);
				} else {
					try {
						submitOwnedCleanup();
					} catch (RuntimeException | Error cleanupFailure) {
						this.delegateProofFailure.compareAndSet(null,
								cleanupFailure);
						rootSignal.signalTerminationFailure(cleanupFailure);
					}
				}
				this.delegateProofObserved.countDown();
			});
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					startCalls.incrementAndGet();
					OwnedCleanupExecutor executor = new OwnedCleanupExecutor(
							name, OwningHttpDecorator.this::publishRootProof);
					if (!cleanupExecutor.compareAndSet(null, executor)) {
						executor.shutdownNow();
						throw new IllegalStateException(
								"Decorator cleanup executor was already acquired");
					}
					if (!executor.prestartCoreThread()) {
						executor.shutdownNow();
						throw new IllegalStateException(
								"Decorator cleanup executor did not prestart");
					}
					try {
						attachment.getRuntime().start(context);
					} catch (RuntimeException | Error failure) {
						executor.shutdownNow();
						throw failure;
					}
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					quiesceCalls.incrementAndGet();
					try {
						attachment.getRuntime().quiesce(context);
					} finally {
						quiesceReturned.countDown();
					}
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					forceCalls.incrementAndGet();
					requestCleanupForce();
					try {
						attachment.getRuntime().force(context);
					} finally {
						requestCleanupForce();
						forceReturned.countDown();
					}
				}
			};
		}

		private void submitOwnedCleanup() {
			OwnedCleanupExecutor executor = requireNonNull(this.cleanupExecutor.get(),
					"Decorator cleanup executor was not started");
			for (;;) {
				switch (this.cleanupState.get()) {
					case NOT_SUBMITTED -> {
						if (this.cleanupState.compareAndSet(
								CleanupState.NOT_SUBMITTED,
								CleanupState.SUBMITTED))
							break;
						continue;
					}
					case CANCELLED -> {
						if (this.cleanupState.compareAndSet(CleanupState.CANCELLED,
								CleanupState.FINISHED)) {
							this.cleanupFinished.countDown();
							executor.shutdown();
						}
						return;
					}
					case SUBMITTED, RUNNING, FINISHED -> {
						return;
					}
				}
				break;
			}

			this.cleanupSubmissions.incrementAndGet();
			try {
				executor.execute(this::runOwnedCleanup);
			} catch (RuntimeException | Error failure) {
				this.rejectedCleanupSubmissions.incrementAndGet();
				if (this.cleanupState.compareAndSet(CleanupState.SUBMITTED,
						CleanupState.FINISHED))
					this.cleanupFinished.countDown();
				executor.shutdownNow();
				throw failure;
			}
			if (this.forceRequested.get())
				requestCleanupForce();
		}

		private void runOwnedCleanup() {
			Thread current = Thread.currentThread();
			this.cleanupThread.set(current);
			if (!this.cleanupState.compareAndSet(CleanupState.SUBMITTED,
					CleanupState.RUNNING)) {
				this.cleanupThread.compareAndSet(current, null);
				return;
			}
			this.cleanupStarted.countDown();
			try {
				if (!this.forceRequested.get())
					this.cleanupRelease.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} finally {
				finishRunningCleanup();
				this.cleanupThread.compareAndSet(current, null);
			}
		}

		private void requestCleanupForce() {
			this.cleanupForceRequests.incrementAndGet();
			this.forceRequested.set(true);
			for (;;) {
				switch (this.cleanupState.get()) {
					case NOT_SUBMITTED -> {
						if (this.cleanupState.compareAndSet(
								CleanupState.NOT_SUBMITTED,
								CleanupState.CANCELLED))
							return;
					}
					case SUBMITTED, CANCELLED, FINISHED -> {
						return;
					}
					case RUNNING -> {
						Thread worker = this.cleanupThread.get();
						if (worker != null) {
							worker.interrupt();
							return;
						}
						Thread.onSpinWait();
					}
				}
			}
		}

		private void finishRunningCleanup() {
			if (!this.cleanupState.compareAndSet(CleanupState.RUNNING,
					CleanupState.FINISHED))
				return;
			this.cleanupFinished.countDown();
			requireNonNull(this.cleanupExecutor.get()).shutdown();
		}

		private void publishRootProof() {
			this.executorTerminationHook.countDown();
			requireNonNull(this.terminationSignal.get()).signalTerminated();
			this.rootProofPublished.countDown();
		}

		private static final class OwnedCleanupExecutor
				extends ThreadPoolExecutor {
			@NonNull
			private final Runnable terminationCallback;

			private OwnedCleanupExecutor(@NonNull String ownerName,
					@NonNull Runnable terminationCallback) {
				super(1, 1, 0L, TimeUnit.MILLISECONDS,
						new LinkedBlockingQueue<>(), runnable -> {
							Thread thread = new Thread(runnable,
									"soklet-http-" + ownerName + "-cleanup");
							thread.setDaemon(true);
							return thread;
						});
				this.terminationCallback = requireNonNull(terminationCallback);
			}

			@Override
			protected void terminated() {
				super.terminated();
				this.terminationCallback.run();
			}
		}
	}

	public static final class OkResource {
		@GET("/ok")
		@NonNull
		public String ok() {
			return "ok";
		}
	}
}
