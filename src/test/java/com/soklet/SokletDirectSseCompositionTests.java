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

import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** Direct-owner acceptance for package-private SSE transport composition. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectSseCompositionTests {
	@NonNull
	private static final Duration SHORT_PHASE = Duration.ofMillis(100);

	@Test
	void transparentDecoratorSharesTheRootMemberAndRoutesTheSseSurface()
			throws Exception {
		GraphProbe probe = new GraphProbe(0);
		AlternativeSseEngine engine = new AlternativeSseEngine("engine", probe,
				ProofPhase.QUIESCE);
		TransparentSseDecorator outer = new TransparentSseDecorator("transparent",
				probe, engine);
		SokletConfig config = configuration(outer);
		try (Soklet soklet = Soklet.fromConfig(config)) {
			exerciseReadyGraph(soklet, config, outer, engine,
					List.of("transparent", "engine"), List.of("transparent"));
			soklet.stop();

			InternalShutdownResult result = terminalResult(soklet,
					InternalShutdownDisposition.GRACEFUL);
			Assertions.assertEquals(List.of("transparent", "engine"),
					probe.attachments);
			Assertions.assertSame(probe.signal("transparent"),
					probe.signal("engine"),
					"Transparent delegation must preserve the exact root signal");
			Assertions.assertSame(engine.attachedRuntime(), outer.attachedRuntime(),
					"Transparent delegation must return the delegate's exact runtime");
			Assertions.assertEquals(List.of("engine"), probe.starts);
			Assertions.assertEquals(List.of("engine"), probe.quiesces);
			Assertions.assertTrue(probe.forces.isEmpty());
			assertSingleSseParticipant(result,
					InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION);
		}
	}

	@Test
	void lifecycleOwningDecoratorProofCannotBeBypassedByItsDelegate()
			throws Exception {
		GraphProbe probe = new GraphProbe(1);
		AlternativeSseEngine engine = new AlternativeSseEngine("engine", probe,
				ProofPhase.QUIESCE);
		ComposedSseEndpoint outer = new OwningSseDecorator("owner", probe,
				engine);
		SokletConfig config = configuration(outer);
		try (Soklet soklet = Soklet.fromConfig(config)) {
			exerciseReadyGraph(soklet, config, outer, engine,
					List.of("owner", "engine"), List.of("owner"));
			soklet.stop();
			assertTruthfulOwnedCleanup(probe, "owner");

			InternalShutdownResult result = terminalResult(soklet,
					InternalShutdownDisposition.FORCED);
			Assertions.assertEquals(List.of("owner", "engine"), probe.attachments);
			Assertions.assertNotSame(probe.signal("owner"), probe.signal("engine"),
					"Owning delegation must receive a distinct child signal");
			Assertions.assertEquals(List.of("owner", "engine"), probe.starts);
			Assertions.assertEquals(List.of("owner", "engine"), probe.quiesces);
			Assertions.assertEquals(List.of("owner", "engine"), probe.forces,
					"Missing outer proof must drive force through the configured runtime");
			Assertions.assertTrue(probe.proofs.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(Set.of("owner"),
					Set.copyOf(probe.proofCallbacks));
			assertSingleSseParticipant(result,
					InternalParticipantShutdownDisposition.FORCED_TERMINATION);
		}
	}

	@Test
	void twoLevelOwningStackRequiresEveryNestedMemberButRemainsOneParticipant()
			throws Exception {
		GraphProbe probe = new GraphProbe(2);
		AlternativeSseEngine engine = new AlternativeSseEngine("engine", probe,
				ProofPhase.FORCE, true);
		ComposedSseEndpoint inner = new OwningSseDecorator("inner", probe,
				engine);
		ComposedSseEndpoint outer = new OwningSseDecorator("outer", probe,
				inner);
		SokletConfig config = configuration(outer, Duration.ofSeconds(2));
		Soklet soklet = Soklet.fromConfig(config);
		try {
			exerciseReadyGraph(soklet, config, outer, engine,
					List.of("outer", "inner", "engine"),
					List.of("inner", "outer"));
			ExecutorService stopper = Executors.newSingleThreadExecutor();
			java.util.concurrent.Future<Throwable> stop = stopper.submit(() -> {
				try {
					soklet.stop();
					return null;
				} catch (Throwable throwable) {
					return throwable;
				}
			});
			Throwable stopFailure;
			try {
				Assertions.assertTrue(engine.beforeProof.await(2, TimeUnit.SECONDS));
				Assertions.assertFalse(engine.proofPublished.get());
				Assertions.assertTrue(probe.proofCallbacks.isEmpty(),
						"The outer subtree handle must wait for deepest proof");
				Assertions.assertTrue(probe.cleanupSubmissions.isEmpty(),
						"Owning cleanup begins only after subtree proof");
				engine.releaseProof.countDown();
				stopFailure = stop.get(3, TimeUnit.SECONDS);
			} finally {
				engine.releaseProof.countDown();
				stopper.shutdownNow();
			}
			Assertions.assertNull(stopFailure);
			assertTruthfulOwnedCleanup(probe, "outer");
			assertTruthfulOwnedCleanup(probe, "inner");

			InternalShutdownResult result = terminalResult(soklet,
					InternalShutdownDisposition.FORCED);
			Assertions.assertEquals(List.of("outer", "inner", "engine"),
					probe.attachments);
			Assertions.assertNotSame(probe.signal("outer"), probe.signal("inner"));
			Assertions.assertNotSame(probe.signal("inner"), probe.signal("engine"));
			Assertions.assertNotSame(probe.signal("outer"), probe.signal("engine"));
			Assertions.assertEquals(List.of("outer", "inner", "engine"),
					probe.starts);
			Assertions.assertEquals(List.of("outer", "inner", "engine"),
					probe.quiesces);
			Assertions.assertEquals(List.of("outer", "inner", "engine"),
					probe.forces,
					"Deepest missing proof must keep the whole unary graph unresolved");
			Assertions.assertTrue(probe.proofs.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(Set.of("outer", "inner"),
					Set.copyOf(probe.proofCallbacks));
			assertSingleSseParticipant(result,
					InternalParticipantShutdownDisposition.FORCED_TERMINATION);
		} finally {
			engine.releaseProof.countDown();
			soklet.close();
		}
	}

	private static void exerciseReadyGraph(@NonNull Soklet soklet,
			@NonNull SokletConfig config, @NonNull ComposedSseEndpoint outer,
			@NonNull AlternativeSseEngine engine,
			@NonNull List<String> expectedBroadcasterRoute,
			@NonNull List<String> expectedHandlerOrder) {
		EventResource.INJECTED_SERVER.set(null);
		Assertions.assertSame(engine.identity(), outer.identity(),
				"Every decorator must preserve the leaf's exact stable identity");
		ResourcePath beforeEvents = ResourcePath.fromPath("/events");
		int beforeReadiness = engine.probe.broadcasterCalls.size();
		Assertions.assertSame(engine.broadcaster,
				outer.acquireBroadcaster(beforeEvents).orElseThrow(),
				"Every decorator must forward broadcaster acquisition");
		assertBroadcasterRoute(engine.probe, beforeReadiness,
				expectedBroadcasterRoute, beforeEvents);

		soklet.start();

		Assertions.assertTrue(soklet.isStarted());
		ResourcePath afterEvents = ResourcePath.fromPath("/events");
		Assertions.assertNotSame(beforeEvents, afterEvents,
				"The two forwarding traversals must use distinct path objects");
		int afterReadiness = engine.probe.broadcasterCalls.size();
		Assertions.assertSame(engine.broadcaster,
				config.getSseServer().orElseThrow()
						.acquireBroadcaster(afterEvents).orElseThrow());
		assertBroadcasterRoute(engine.probe, afterReadiness,
				expectedBroadcasterRoute, afterEvents);

		ResourcePath wrong = ResourcePath.fromPath("/wrong");
		int wrongPath = engine.probe.broadcasterCalls.size();
		Assertions.assertTrue(outer.acquireBroadcaster(wrong).isEmpty());
		assertBroadcasterRoute(engine.probe, wrongPath,
				expectedBroadcasterRoute, wrong);
		int nullPath = engine.probe.broadcasterCalls.size();
		Assertions.assertTrue(outer.acquireBroadcaster(null).isEmpty());
		assertBroadcasterRoute(engine.probe, nullPath,
				expectedBroadcasterRoute, null);
		Assertions.assertEquals(expectedBroadcasterRoute.size() * 4,
				engine.probe.broadcasterCalls.size(),
				"Each wrapper and leaf must observe every exact acquisition call");

		HttpRequestResult response = engine.invoke("/events");
		Assertions.assertEquals(200,
				response.getMarshaledResponse().getStatusCode());
		Assertions.assertEquals(expectedHandlerOrder, engine.probe.handlerWrappers);
		Assertions.assertSame(outer, SokletConfig.unwrapSseServer(
				requireNonNull(EventResource.INJECTED_SERVER.get())),
				"SSE injection must resolve to the configured outer graph");
		for (Object observed : engine.probe.configurations.values())
			Assertions.assertSame(config, observed,
					"Every successor context must preserve the exact configuration");
		InternalStartupContext engineStartup = requireNonNull(
				engine.probe.startupContexts.get("engine"));
		for (InternalStartupContext observed
				: engine.probe.startupContexts.values())
			Assertions.assertSame(engineStartup, observed,
					"Every successor must receive the exact startup context");
		Assertions.assertEquals(engine.probe.attachments.size(),
				engine.probe.configurations.size());
		Assertions.assertEquals(engine.probe.attachments.size(),
				engine.probe.startupContexts.size());
	}

	private static void assertBroadcasterRoute(@NonNull GraphProbe probe,
			int offset, @NonNull List<String> expectedRoute,
			@Nullable ResourcePath exactPath) {
		List<BroadcasterCall> calls = List.copyOf(probe.broadcasterCalls
				.subList(offset, probe.broadcasterCalls.size()));
		Assertions.assertEquals(expectedRoute.size(), calls.size());
		for (int index = 0; index < expectedRoute.size(); index++) {
			BroadcasterCall call = calls.get(index);
			Assertions.assertEquals(expectedRoute.get(index), call.endpoint());
			Assertions.assertSame(exactPath, call.resourcePath(),
					"Decorator forwarding must preserve exact ResourcePath identity");
		}
	}

	private static void assertTruthfulOwnedCleanup(@NonNull GraphProbe probe,
			@NonNull String name) throws InterruptedException {
		Assertions.assertEquals(1, java.util.Collections.frequency(
				probe.executorStarts, name));
		Assertions.assertEquals(1, java.util.Collections.frequency(
				probe.cleanupSubmissions, name));
		Assertions.assertTrue(probe.cleanupStarted(name)
				.await(2, TimeUnit.SECONDS));
		Assertions.assertEquals(1, java.util.Collections.frequency(
				probe.cleanupStarts, name));
		Assertions.assertEquals(1, java.util.Collections.frequency(
				probe.cleanupFinishes, name));
		Assertions.assertEquals(1, java.util.Collections.frequency(
				probe.rootProofs, name));
		Assertions.assertNotSame(
				requireNonNull(probe.proofCallbackThreads.get(name)),
				requireNonNull(probe.cleanupThreads.get(name)),
				"Subtree handoff callbacks must only submit to owned execution");
		int subtree = probe.ownedEvents.indexOf("subtree-proof:" + name);
		int submitted = probe.ownedEvents.indexOf("cleanup-submit:" + name);
		int started = probe.ownedEvents.indexOf("cleanup-start:" + name);
		int finished = probe.ownedEvents.indexOf("cleanup-finish:" + name);
		int rootProof = probe.ownedEvents.indexOf("root-proof:" + name);
		Assertions.assertTrue(subtree >= 0 && subtree < submitted,
				"The proof callback must only submit owned cleanup");
		Assertions.assertTrue(submitted < started,
				"Cleanup must enter owned execution after submission");
		Assertions.assertTrue(started < finished,
				"Cleanup must finish after its prompt submission");
		Assertions.assertTrue(finished < rootProof,
				"Root proof must follow owned cleanup and executor termination");
	}

	@NonNull
	private static InternalShutdownResult terminalResult(@NonNull Soklet soklet,
			@NonNull InternalShutdownDisposition disposition) {
		InternalShutdownResult result = soklet.getDirectLifecycle().result()
				.orElseThrow();
		Assertions.assertEquals(InternalStartupDisposition.READY,
				result.startupDisposition());
		Assertions.assertEquals(disposition, result.disposition());
		return result;
	}

	private static void assertSingleSseParticipant(
			@NonNull InternalShutdownResult result,
			@NonNull InternalParticipantShutdownDisposition disposition) {
		Assertions.assertEquals(1, result.participantResults().size(),
				"The coordinator must see one configured outer graph");
		InternalParticipantShutdownResult participant = result.participantResults()
				.get(0);
		Assertions.assertEquals(InternalParticipantKind.SSE, participant.kind());
		Assertions.assertEquals(disposition, participant.disposition());
		Assertions.assertTrue(participant.failures().isEmpty());
		Assertions.assertTrue(participant.residualActivity().isEmpty());
	}

	@NonNull
	private static SokletConfig configuration(@NonNull SseServer outer) {
		return configuration(outer, SHORT_PHASE);
	}

	@NonNull
	private static SokletConfig configuration(@NonNull SseServer outer,
			@NonNull Duration forcePhase) {
		return SokletConfig.withSseServer(outer)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(EventResource.class)))
				.internalLifecyclePolicy(new InternalLifecyclePolicy(
						Optional.of(Duration.ofSeconds(2)), SHORT_PHASE,
						SHORT_PHASE, requireNonNull(forcePhase)))
				.build();
	}

	private static void awaitUninterruptibly(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				requireNonNull(latch).await();
				break;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private enum ProofPhase {
		QUIESCE,
		FORCE
	}

	private enum CleanupState {
		NOT_SUBMITTED,
		SUBMITTED,
		RUNNING,
		FINISHED
	}

	private record BroadcasterCall(@NonNull String endpoint,
			@Nullable ResourcePath resourcePath) {
		private BroadcasterCall {
			requireNonNull(endpoint);
		}
	}

	private interface ComposedSseEndpoint
			extends SseServer, InternalSseTransportEndpoint {
	}

	private static final class GraphProbe {
		@NonNull private final List<String> attachments =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> starts =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> quiesces =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> forces =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> handlerWrappers =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> proofCallbacks =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> executorStarts =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> cleanupSubmissions =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> cleanupStarts =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> cleanupFinishes =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> rootProofs =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<String> ownedEvents =
				new CopyOnWriteArrayList<>();
		@NonNull private final List<BroadcasterCall> broadcasterCalls =
				new CopyOnWriteArrayList<>();
		@NonNull private final Map<String, Object> configurations =
				new ConcurrentHashMap<>();
		@NonNull private final Map<String, InternalStartupContext> startupContexts =
				new ConcurrentHashMap<>();
		@NonNull private final Map<String, InternalTransportTerminationSignal> signals =
				new ConcurrentHashMap<>();
		@NonNull private final Map<String, CountDownLatch> cleanupStarted =
				new ConcurrentHashMap<>();
		@NonNull private final Map<String, Thread> proofCallbackThreads =
				new ConcurrentHashMap<>();
		@NonNull private final Map<String, Thread> cleanupThreads =
				new ConcurrentHashMap<>();
		@NonNull private final CountDownLatch proofs;

		private GraphProbe(int expectedProofCallbacks) {
			this.proofs = new CountDownLatch(expectedProofCallbacks);
		}

		private void attached(@NonNull String name,
				@NonNull InternalTransportAttachmentContext<?> context,
				@NonNull InternalStartupContext startupContext) {
			this.attachments.add(name);
			this.configurations.put(name, context.configuration());
			this.startupContexts.put(name, startupContext);
			this.signals.put(name, context.terminationSignal());
		}

		private void proofObserved(@NonNull String name) {
			this.proofCallbacks.add(name);
			this.proofCallbackThreads.put(name, Thread.currentThread());
			this.ownedEvents.add("subtree-proof:" + name);
			this.proofs.countDown();
		}

		private void broadcasterCalled(@NonNull String name,
				@Nullable ResourcePath resourcePath) {
			this.broadcasterCalls.add(new BroadcasterCall(name, resourcePath));
		}

		private void executorStarted(@NonNull String name) {
			this.executorStarts.add(name);
			this.ownedEvents.add("executor-start:" + name);
		}

		private void cleanupSubmitted(@NonNull String name) {
			cleanupStarted(name);
			this.cleanupSubmissions.add(name);
			this.ownedEvents.add("cleanup-submit:" + name);
		}

		private void cleanupStarted(@NonNull String name,
				@NonNull Thread thread) {
			this.cleanupStarts.add(name);
			this.cleanupThreads.put(name, requireNonNull(thread));
			this.ownedEvents.add("cleanup-start:" + name);
			cleanupStarted(name).countDown();
		}

		@NonNull
		private CountDownLatch cleanupStarted(@NonNull String name) {
			return this.cleanupStarted.computeIfAbsent(requireNonNull(name),
					ignored -> new CountDownLatch(1));
		}

		private void cleanupFinished(@NonNull String name) {
			this.cleanupFinishes.add(name);
			this.ownedEvents.add("cleanup-finish:" + name);
		}

		private void rootProof(@NonNull String name) {
			this.rootProofs.add(name);
			this.ownedEvents.add("root-proof:" + name);
		}

		@NonNull
		private InternalTransportTerminationSignal signal(@NonNull String name) {
			return requireNonNull(this.signals.get(name));
		}
	}

	private abstract static class AbstractSseEndpoint
			implements ComposedSseEndpoint {
		@NonNull final String name;
		@NonNull final GraphProbe probe;
		@NonNull final InternalTransportIdentity identity;

		private AbstractSseEndpoint(@NonNull String name,
				@NonNull GraphProbe probe,
				@NonNull InternalTransportIdentity identity) {
			this.name = requireNonNull(name);
			this.probe = requireNonNull(probe);
			this.identity = requireNonNull(identity);
		}

		@Override
		@NonNull
		public final InternalTransportIdentity identity() {
			return this.identity;
		}

		@Override
		public final void start() {
			throw new AssertionError("Direct composition must start its outer runtime");
		}

		@Override
		public final void stop() {
			throw new AssertionError("Direct composition must stop its outer runtime");
		}

		@Override
		public final void initialize(@NonNull SokletConfig sokletConfig,
				SseServer.@NonNull RequestHandler requestHandler) {
			throw new AssertionError("Direct composition must use attach(...)");
		}
	}

	private static final class AlternativeSseEngine extends AbstractSseEndpoint {
		@NonNull private final ProofPhase proofPhase;
		@NonNull private final AtomicReference<SseServer.RequestHandler> requestHandler;
		@NonNull private final AtomicReference<InternalTransportTerminationSignal> signal;
		@NonNull private final AtomicReference<InternalTransportRuntime> runtime;
		@NonNull private final AtomicBoolean proofPublished;
		@NonNull private final AtomicBoolean started;
		@NonNull private final SseBroadcaster broadcaster;
		@NonNull private final AtomicBoolean proofBarrierUsed;
		@NonNull private final CountDownLatch beforeProof;
		@NonNull private final CountDownLatch releaseProof;
		private final boolean blockBeforeProof;

		private AlternativeSseEngine(@NonNull String name,
				@NonNull GraphProbe probe, @NonNull ProofPhase proofPhase) {
			this(name, probe, proofPhase, false);
		}

		private AlternativeSseEngine(@NonNull String name,
				@NonNull GraphProbe probe, @NonNull ProofPhase proofPhase,
				boolean blockBeforeProof) {
			super(name, probe, InternalTransportIdentity.create());
			this.proofPhase = requireNonNull(proofPhase);
			this.requestHandler = new AtomicReference<>();
			this.signal = new AtomicReference<>();
			this.runtime = new AtomicReference<>();
			this.proofPublished = new AtomicBoolean();
			this.started = new AtomicBoolean();
			this.broadcaster = new SentinelBroadcaster();
			this.proofBarrierUsed = new AtomicBoolean();
			this.beforeProof = new CountDownLatch(1);
			this.releaseProof = new CountDownLatch(blockBeforeProof ? 1 : 0);
			this.blockBeforeProof = blockBeforeProof;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<SseServer.RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			this.probe.attached(this.name, context, startupContext);
			this.requestHandler.set(context.requestHandler());
			this.signal.set(context.terminationSignal());
			InternalTransportRuntime attachedRuntime = new InternalTransportRuntime() {
				@Override
				public void start(@NonNull InternalStartupContext context) {
					probe.starts.add(name);
					started.set(true);
				}

				@Override
				public void quiesce(@NonNull InternalShutdownContext context) {
					probe.quiesces.add(name);
					started.set(false);
					if (proofPhase == ProofPhase.QUIESCE)
						publishProof();
				}

				@Override
				public void force(@NonNull InternalShutdownContext context) {
					probe.forces.add(name);
					started.set(false);
					publishProof();
				}
			};
			this.runtime.set(attachedRuntime);
			return attachedRuntime;
		}

		@NonNull
		private InternalTransportRuntime attachedRuntime() {
			return requireNonNull(this.runtime.get());
		}

		@NonNull
		private HttpRequestResult invoke(@NonNull String path) {
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			requireNonNull(this.requestHandler.get()).handleRequest(
					Request.withPath(HttpMethod.GET, path).build(), result::set);
			return requireNonNull(result.get());
		}

		private void publishProof() {
			if (this.blockBeforeProof
					&& this.proofBarrierUsed.compareAndSet(false, true)) {
				this.beforeProof.countDown();
				awaitUninterruptibly(this.releaseProof);
			}
			if (this.proofPublished.compareAndSet(false, true))
				requireNonNull(this.signal.get()).signalTerminated();
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
			this.probe.broadcasterCalled(this.name, resourcePath);
			return resourcePath != null
					&& ResourcePath.fromPath("/events").equals(resourcePath)
					? Optional.of(this.broadcaster) : Optional.empty();
		}
	}

	private abstract static class AbstractSseDecorator
			extends AbstractSseEndpoint {
		@NonNull final ComposedSseEndpoint delegate;

		private AbstractSseDecorator(@NonNull String name,
				@NonNull GraphProbe probe, @NonNull ComposedSseEndpoint delegate) {
			super(name, probe, requireNonNull(delegate).identity());
			this.delegate = delegate;
		}

		final SseServer.@NonNull RequestHandler wrappedHandler(
				SseServer.@NonNull RequestHandler parent) {
			return (request, consumer) -> {
				this.probe.handlerWrappers.add(this.name);
				parent.handleRequest(request, consumer);
			};
		}

		@Override
		@NonNull
		public final Boolean isStarted() {
			return this.delegate.isStarted();
		}

		@Override
		@NonNull
		public final Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			this.probe.broadcasterCalled(this.name, resourcePath);
			return this.delegate.acquireBroadcaster(resourcePath);
		}
	}

	private static final class TransparentSseDecorator
			extends AbstractSseDecorator {
		@NonNull private final AtomicReference<InternalTransportRuntime> runtime;

		private TransparentSseDecorator(@NonNull String name,
				@NonNull GraphProbe probe, @NonNull ComposedSseEndpoint delegate) {
			super(name, probe, delegate);
			this.runtime = new AtomicReference<>();
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<SseServer.RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			this.probe.attached(this.name, context, startupContext);
			InternalTransportRuntime attachedRuntime =
					context.attachTransparentDelegate(this.delegate,
							wrappedHandler(context.requestHandler()));
			this.runtime.set(attachedRuntime);
			return attachedRuntime;
		}

		@NonNull
		private InternalTransportRuntime attachedRuntime() {
			return requireNonNull(this.runtime.get());
		}
	}

	private static final class OwningSseDecorator extends AbstractSseDecorator {
		@NonNull private final AtomicReference<InternalTransportTerminationSignal> signal;
		@NonNull private final AtomicBoolean proofPublished;
		@NonNull private final AtomicBoolean forceRequested;
		@NonNull private final AtomicReference<CleanupState> cleanupState;
		@NonNull private final AtomicReference<OwnedCleanupExecutor> executor;
		@NonNull private final AtomicReference<Thread> cleanupThread;
		@NonNull private final CountDownLatch cleanupBlocker;

		private OwningSseDecorator(@NonNull String name,
				@NonNull GraphProbe probe, @NonNull ComposedSseEndpoint delegate) {
			super(name, probe, delegate);
			this.signal = new AtomicReference<>();
			this.proofPublished = new AtomicBoolean();
			this.forceRequested = new AtomicBoolean();
			this.cleanupState = new AtomicReference<>(CleanupState.NOT_SUBMITTED);
			this.executor = new AtomicReference<>();
			this.cleanupThread = new AtomicReference<>();
			this.cleanupBlocker = new CountDownLatch(1);
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<SseServer.RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			this.probe.attached(this.name, context, startupContext);
			this.signal.set(context.terminationSignal());
			InternalTransportDelegateAttachment attachment =
					context.attachLifecycleOwningDelegate(this.delegate,
							wrappedHandler(context.requestHandler()));
			attachment.whenTerminated().thenRun(this::submitOwnedCleanup);
			InternalTransportRuntime child = attachment.runtime();
			return new InternalTransportRuntime() {
				@Override
				public void start(@NonNull InternalStartupContext phaseContext) {
					probe.starts.add(name);
					startOwnedExecutor();
					try {
						child.start(phaseContext);
					} catch (RuntimeException | Error failure) {
						requireNonNull(executor.get()).shutdownNow();
						throw failure;
					}
				}

				@Override
				public void quiesce(@NonNull InternalShutdownContext phaseContext) {
					probe.quiesces.add(name);
					child.quiesce(phaseContext);
				}

				@Override
				public void force(@NonNull InternalShutdownContext phaseContext) {
					probe.forces.add(name);
					requestCleanupForce();
					child.force(phaseContext);
				}
			};
		}

		private void startOwnedExecutor() {
			OwnedCleanupExecutor created = new OwnedCleanupExecutor(this.name,
					this::publishProof);
			if (!this.executor.compareAndSet(null, created)) {
				created.shutdownNow();
				throw new IllegalStateException(
						"Decorator-owned executor was already started");
			}
			if (!created.prestartCoreThread()) {
				created.shutdownNow();
				throw new IllegalStateException(
						"Decorator-owned executor did not prestart");
			}
			this.probe.executorStarted(this.name);
		}

		private void submitOwnedCleanup() {
			this.probe.proofObserved(this.name);
			if (!this.cleanupState.compareAndSet(CleanupState.NOT_SUBMITTED,
					CleanupState.SUBMITTED))
				return;
			this.probe.cleanupSubmitted(this.name);
			OwnedCleanupExecutor exactExecutor = requireNonNull(this.executor.get());
			try {
				exactExecutor.execute(this::runOwnedCleanup);
			} catch (RuntimeException | Error failure) {
				if (this.cleanupState.compareAndSet(CleanupState.SUBMITTED,
						CleanupState.FINISHED))
					this.probe.cleanupFinished(this.name);
				exactExecutor.shutdownNow();
				requireNonNull(this.signal.get()).signalTerminationFailure(failure);
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
			this.probe.cleanupStarted(this.name, current);
			try {
				if (!this.forceRequested.get())
					this.cleanupBlocker.await();
			} catch (InterruptedException ignored) {
				current.interrupt();
			} finally {
				finishRunningCleanup();
				this.cleanupThread.compareAndSet(current, null);
			}
		}

		private void requestCleanupForce() {
			this.forceRequested.set(true);
			for (;;) {
				switch (this.cleanupState.get()) {
					case NOT_SUBMITTED, FINISHED -> {
						return;
					}
					case SUBMITTED -> {
						// The prestarted executor must observe every submitted cleanup.
						// Its task sees forceRequested and finishes without blocking.
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
			this.probe.cleanupFinished(this.name);
			requireNonNull(this.executor.get()).shutdown();
		}

		private void publishProof() {
			this.probe.rootProof(this.name);
			if (this.proofPublished.compareAndSet(false, true))
				requireNonNull(this.signal.get()).signalTerminated();
		}

		private final class OwnedCleanupExecutor extends ThreadPoolExecutor {
			private OwnedCleanupExecutor(@NonNull String ownerName,
					@NonNull Runnable terminationCallback) {
				super(1, 1, 0L, TimeUnit.MILLISECONDS,
						new LinkedBlockingQueue<>(), runnable -> {
							Thread thread = new Thread(runnable,
									"sse-owned-cleanup-" + ownerName);
							thread.setDaemon(true);
							return thread;
						});
				this.terminationCallback = requireNonNull(terminationCallback);
			}

			@NonNull private final Runnable terminationCallback;

			@Override
			protected void terminated() {
				super.terminated();
				this.terminationCallback.run();
			}
		}
	}

	private static final class SentinelBroadcaster implements SseBroadcaster {
		@Override
		@NonNull
		public ResourcePath getResourcePath() {
			return ResourcePath.fromPath("/events");
		}

		@Override
		@NonNull
		public Long getClientCount() {
			return 0L;
		}

		@Override
		public void broadcastEvent(@NonNull SseEvent sseEvent) {
			// Identity-only conformance sentinel.
		}

		@Override
		public <T> void broadcastEvent(@NonNull Function<Object, T> keySelector,
				@NonNull Function<T, SseEvent> eventProvider) {
			// Identity-only conformance sentinel.
		}

		@Override
		public void broadcastComment(@NonNull SseComment sseComment) {
			// Identity-only conformance sentinel.
		}

		@Override
		public <T> void broadcastComment(@NonNull Function<Object, T> keySelector,
				@NonNull Function<T, SseComment> commentProvider) {
			// Identity-only conformance sentinel.
		}
	}

	public static final class EventResource {
		@NonNull private static final AtomicReference<SseServer> INJECTED_SERVER =
				new AtomicReference<>();

		@SseEventSource("/events")
		@NonNull
		public SseHandshakeResult events(@NonNull SseServer sseServer) {
			INJECTED_SERVER.set(sseServer);
			return SseHandshakeResult.accept();
		}
	}
}
