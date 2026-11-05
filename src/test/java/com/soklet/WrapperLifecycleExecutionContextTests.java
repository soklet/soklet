package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class WrapperLifecycleExecutionContextTests {
	@Test
	void pairedScopesRestoreNestedAndExceptionalExecution() {
		Object component = new Object();
		Object graph = new Object();
		Object unrelated = new Object();
		try (var outer = LifecycleExecutionContext.enter(graph)) {
			for (Throwable expected : List.of(new IllegalArgumentException("callback"), new AssertionError("callback"))) {
				assertSame(expected, assertThrows(Throwable.class, () -> {
					try (var ignored = LifecycleExecutionContext.enter(component, graph)) {
						assertTrue(LifecycleExecutionContext.isMarked(component));
						assertTrue(LifecycleExecutionContext.isMarked(graph));
						assertThrows(IllegalStateException.class,
								() -> LifecycleExecutionContext.requireNonReentrantWait(graph));
						assertDoesNotThrow(() -> LifecycleExecutionContext.requireNonReentrantWait(unrelated));
						throw expected;
					}
				}));
				assertFalse(LifecycleExecutionContext.isMarked(component));
				assertTrue(LifecycleExecutionContext.isMarked(graph));
			}
		}
		assertFalse(LifecycleExecutionContext.isMarked(graph));
	}

	@Test
	void pairedScopesDeduplicateIdenticalTokensAndCloseOnce() throws Exception {
		Object owner = new Object();
		var first = LifecycleExecutionContext.enter(owner, owner);
		try {
			assertEquals(1, depth(owner));
			try (var nested = LifecycleExecutionContext.enter(owner, owner)) {
				assertEquals(2, depth(owner));
			}
			assertEquals(1, depth(owner));
		} finally {
			first.close();
			first.close();
		}
		assertEquals(0, depth(owner));

		Object graph = new Object();
		var local = depths();
		var original = local.get();
		for (Throwable expected : List.of(new IllegalArgumentException("entry"), new AssertionError("entry"))) {
			var interruptedEntry = new IdentityHashMap<Object, Integer>() {
				private boolean failNextGraphEntry;
				@Override public Integer put(Object key, Integer value) {
					Integer previous = super.put(key, value);
					if (key == graph && this.failNextGraphEntry) {
						this.failNextGraphEntry = false;
						throwUnchecked(expected);
					}
					return previous;
				}
			};
			interruptedEntry.put(owner, 2);
			interruptedEntry.put(graph, 3);
			interruptedEntry.failNextGraphEntry = true;
			local.set(interruptedEntry);
			try {
				assertSame(expected, assertThrows(Throwable.class,
						() -> LifecycleExecutionContext.enter(owner, graph)));
				assertEquals(2, depth(owner));
				assertEquals(3, depth(graph));
			} finally {
				local.set(original);
			}
		}
	}

	@Test
	void pairedMarkersAreExplicitThreadLocalAndOwnerSpecific() throws Exception {
		Object component = new Object();
		Object graph = new Object();
		Object unrelated = new Object();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try (var ignored = LifecycleExecutionContext.enter(component, graph)) {
			Thread callback = new Thread(() -> {
				try {
					assertFalse(LifecycleExecutionContext.isMarked(component));
					assertFalse(LifecycleExecutionContext.isMarked(graph));
					try (var marked = LifecycleExecutionContext.enter(component, graph)) {
						assertThrows(IllegalStateException.class,
								() -> LifecycleExecutionContext.requireNonReentrantWait(graph));
						assertDoesNotThrow(() -> LifecycleExecutionContext.requireNonReentrantWait(unrelated));
					}
					assertFalse(LifecycleExecutionContext.isMarked(component));
					assertFalse(LifecycleExecutionContext.isMarked(graph));
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}, "wrapper-execution-context-test");
			callback.start();
			callback.join(2_000);
			assertFalse(callback.isAlive());
			assertNull(failure.get());
			assertTrue(LifecycleExecutionContext.isMarked(graph));
		}
	}

	@Test
	void delegatedCallbackRetainsGraphMarkerWithoutSharingChildProof() throws Exception {
		Object graph = new Object();
		var ownerGroup = ownerGroup(graph);
		var adapter = adapter();
		TransportRuntime runtime = delegated(adapter, ownerGroup, graph, null);
		runtime.start(startup());
		var child = adapter.generation().orElseThrow();
		assertNotSame(ownerGroup, child.terminationGroup());
		assertSame(LifecycleExecutionContext.legacyOwnerToken(),
				child.terminationGroup().executionOwnerToken());
		assertFalse(ownerGroup.isBarrierComplete());
		assertFalse(LifecycleExecutionContext.isMarked(graph));
		try {
			adapter.runCallback(() -> {
				assertTrue(LifecycleExecutionContext.isMarked(graph));
				assertTrue(LifecycleExecutionContext.isMarked());
				assertThrows(IllegalStateException.class,
						() -> LifecycleExecutionContext.requireNonReentrantWait(graph));
				assertThrows(IllegalStateException.class, LifecycleExecutionContext::requireNonReentrantWait);
			});
			assertFalse(ownerGroup.isBarrierComplete(), "Callback ownership is not termination proof");
		} finally {
			adapter.stop();
		}
		assertTrue(ownerGroup.isBarrierComplete());
		assertTrue(child.terminationGroup().isBarrierComplete());
		assertFalse(LifecycleExecutionContext.isMarked(graph));
		assertFalse(LifecycleExecutionContext.isMarked());

		// A proof observer is a separate framework worker, not the owner phase
		// caller. Custom executor termination methods must carry the same guard.
		Object proofGraph = new Object();
		var proofOwner = ownerGroup(proofGraph);
		var proofEntered = new CountDownLatch(1);
		var releaseProof = new CountDownLatch(1);
		var proofThread = new AtomicReference<Thread>();
		var proofFailure = new AtomicReference<Throwable>();
		Thread caller = Thread.currentThread();
		var proofWorkers = new LifecycleWorkers((name, runnable) -> {
			if (name.endsWith("-proof")) {
				Thread worker = new Thread(() -> {
					try {
						assertFalse(LifecycleExecutionContext.isMarked(proofGraph));
						assertFalse(LifecycleExecutionContext.isMarked());
						runnable.run();
						assertFalse(LifecycleExecutionContext.isMarked(proofGraph));
						assertFalse(LifecycleExecutionContext.isMarked());
					} catch (Throwable failure) {
						proofFailure.compareAndSet(null, failure);
					}
				}, "wrapper-proof-context-test");
				proofThread.set(worker);
				worker.start();
			} else {
				runnable.run();
			}
		});
		var proofAdapter = adapter(proofWorkers, () -> {
			proofEntered.countDown();
			try {
				assertNotSame(caller, Thread.currentThread());
				assertTrue(LifecycleExecutionContext.isMarked());
				assertTrue(LifecycleExecutionContext.isMarked(proofGraph));
				assertThrows(IllegalStateException.class,
						() -> LifecycleExecutionContext.requireNonReentrantWait(proofGraph));
				assertTrue(releaseProof.await(2, TimeUnit.SECONDS));
			} catch (Throwable failure) {
				proofFailure.compareAndSet(null, failure);
			}
		});
		var proofRuntime = delegated(proofAdapter, proofOwner, proofGraph, null);
		proofRuntime.start(startup());
		try {
			try (var ownerPhase = LifecycleExecutionContext.enter(proofGraph)) {
				proofRuntime.shutdownGracefully(graceful());
			}
			assertTrue(proofEntered.await(2, TimeUnit.SECONDS));
			assertFalse(proofOwner.isBarrierComplete());
			assertFalse(proofAdapter.generation().orElseThrow().terminationGroup().isBarrierComplete());
		} finally {
			releaseProof.countDown();
			Thread worker = proofThread.get();
			if (worker != null) {
				worker.join(2_000);
				assertFalse(worker.isAlive());
			}
			proofAdapter.stop();
		}
		assertNull(proofFailure.get(), "The fresh proof worker must carry the graph scope");
		assertTrue(proofOwner.isBarrierComplete());
		assertFalse(LifecycleExecutionContext.isMarked(proofGraph));
		assertFalse(LifecycleExecutionContext.isMarked());
	}

	@Test
	void preGenerationStartFailuresClearBothInvocationValues() {
		for (Throwable expected : List.of(new IllegalArgumentException("before start"), new AssertionError("before start"))) {
			Object graph = new Object();
			var ownerGroup = ownerGroup(graph);
			var adapter = adapter();
			TransportRuntime runtime = adapter.delegatedRuntime(signal(ownerGroup), graph, () -> throwUnchecked(expected));
			assertSame(expected, assertThrows(Throwable.class, () -> runtime.start(startup())));
			assertTrue(adapter.generation().isEmpty());
			runtime.shutdownGracefully(graceful());
			assertTrue(ownerGroup.isBarrierComplete());
			int oldEvents = ownerGroup.primaryEventsInSequence().size();
			var subsequent = adapter.beginStart();
			adapter.markReady(subsequent);
			try {
				adapter.runCallback(() -> {
					assertTrue(LifecycleExecutionContext.isMarked());
					assertFalse(LifecycleExecutionContext.isMarked(graph));
				});
			} finally {
				adapter.stop();
			}
			assertEquals(oldEvents, ownerGroup.primaryEventsInSequence().size());
			assertFalse(LifecycleExecutionContext.isMarked(graph));
		}
	}

	@Test
	void restartAndPostGenerationFailureDoNotRebindAnEarlierGeneration() {
		for (Throwable expected : new Throwable[]{null, new IllegalArgumentException("after start"), new AssertionError("after start")}) {
			Object firstGraph = new Object();
			Object nextGraph = new Object();
			var firstOwner = ownerGroup(firstGraph);
			var nextOwner = ownerGroup(nextGraph);
			var adapter = adapter();
			TransportRuntime first = delegated(adapter, firstOwner, firstGraph, expected);
			if (expected == null)
				first.start(startup());
			else
				assertSame(expected, assertThrows(Throwable.class, () -> first.start(startup())));
			TransportRuntime next = delegated(adapter, nextOwner, nextGraph, null);
			try {
				adapter.runCallback(() -> {
					assertTrue(LifecycleExecutionContext.isMarked(firstGraph));
					assertFalse(LifecycleExecutionContext.isMarked(nextGraph));
				});
			} finally {
				adapter.stop();
			}
			int oldEvents = firstOwner.primaryEventsInSequence().size();
			next.start(startup());
			try {
				adapter.runCallback(() -> {
					assertFalse(LifecycleExecutionContext.isMarked(firstGraph));
					assertTrue(LifecycleExecutionContext.isMarked(nextGraph));
				});
			} finally {
				adapter.stop();
			}
			assertEquals(oldEvents, firstOwner.primaryEventsInSequence().size());
			assertTrue(firstOwner.isBarrierComplete());
			assertTrue(nextOwner.isBarrierComplete());
			assertFalse(LifecycleExecutionContext.isMarked(firstGraph));
			assertFalse(LifecycleExecutionContext.isMarked(nextGraph));
		}
	}

	@Test
	void legacyDelegateOverloadKeepsOnlyItsOriginalComponentMarker() {
		Object graph = new Object();
		var ownerGroup = ownerGroup(graph);
		var adapter = adapter();
		TransportRuntime runtime = adapter.delegatedRuntime(signal(ownerGroup), () -> {
			var generation = adapter.beginStart();
			adapter.markReady(generation);
		});
		runtime.start(startup());
		try {
			adapter.runCallback(() -> {
				assertTrue(LifecycleExecutionContext.isMarked());
				assertFalse(LifecycleExecutionContext.isMarked(graph));
			});
		} finally {
			adapter.stop();
		}
		assertTrue(ownerGroup.isBarrierComplete());
	}

	@Test
	void nestedDelegationKeepsOneGraphTokenAndIndependentOwningSignals() {
		Object graph = new Object();
		var identity = InternalTransportIdentity.create();
		var rootSignal = new AtomicReference<InternalTransportTerminationSignal>();
		var transparentSignal = new AtomicReference<InternalTransportTerminationSignal>();
		var childSignal = new AtomicReference<InternalTransportTerminationSignal>();
		InternalTransportEndpoint<String> child = endpoint(identity, (context, startup) -> {
			assertSame(graph, context.executionOwnerToken());
			childSignal.set(context.terminationSignal());
			return noOpRuntime();
		});
		InternalTransportEndpoint<String> transparent = endpoint(identity, (context, startup) -> {
			assertSame(graph, context.executionOwnerToken());
			transparentSignal.set(context.terminationSignal());
			context.attachTerminationOwningDelegate(child, "child");
			return noOpRuntime();
		});
		InternalTransportEndpoint<String> root = endpoint(identity, (context, startup) -> {
			assertSame(graph, context.executionOwnerToken());
			rootSignal.set(context.terminationSignal());
			return context.attachTransparentDelegate(transparent, "transparent");
		});
		var session = new InternalTransportAttachmentSession<>(new Object(), "root", identity,
				startup(), new AdmissionFence(), () -> {}, workers(), graph);
		session.attach(root);
		assertSame(rootSignal.get(), transparentSignal.get());
		assertNotSame(rootSignal.get(), childSignal.get());
		assertEquals(2, session.group().memberCount());
		rootSignal.get().signalTerminated();
		assertFalse(session.group().isBarrierComplete());
		childSignal.get().signalTerminated();
		assertTrue(session.group().isBarrierComplete());
	}

	private static BuiltInTransportLifecycleAdapter adapter() {
		return adapter(workers(), () -> {});
	}

	private static BuiltInTransportLifecycleAdapter adapter(LifecycleWorkers workers,
			Runnable beforeProof) {
		return new BuiltInTransportLifecycleAdapter(InternalLifecycleComponentType.HTTP,
				new BuiltInTransportLifecycleAdapter.Operations() {
					@Override public void quiesce() {}
					@Override public void force() {}
					@Override public boolean awaitTermination(long deadline) {
						beforeProof.run();
						return true;
					}
					@Override public Set<InternalResidualActivityType> residualActivity() { return Set.of(); }
					@Override public void releaseTerminatedEvidence() {}
				}, () -> Duration.ZERO, Duration.ZERO, () -> 0L, workers);
	}

	private static LifecycleWorkers workers() {
		return new LifecycleWorkers((name, runnable) -> runnable.run());
	}

	private static InternalTerminationGroup ownerGroup(Object graph) {
		var group = new InternalTerminationGroup(new AdmissionFence(), () -> {}, workers(), graph);
		group.commit();
		return group;
	}

	private static TransportTerminationSignal signal(InternalTerminationGroup group) {
		return new InternalTransportTerminationSignal(group, group.root()).publicSignal();
	}

	private static TransportRuntime delegated(BuiltInTransportLifecycleAdapter adapter,
			InternalTerminationGroup group, Object graph, Throwable failure) {
		return adapter.delegatedRuntime(signal(group), graph, () -> {
			var generation = adapter.beginStart();
			if (failure != null) {
				adapter.failedStart(generation, failure, false);
				throwUnchecked(failure);
			}
			adapter.markReady(generation);
		});
	}

	private static StartupContext startup() {
		return new StartupContext(() -> 0L, Long.MAX_VALUE, Long.MAX_VALUE, () -> false);
	}

	private static ShutdownContext graceful() {
		return new ShutdownContext(ShutdownPhase.GRACEFUL, () -> 0L, Long.MAX_VALUE);
	}

	private static void throwUnchecked(Throwable throwable) {
		if (throwable instanceof RuntimeException runtimeException)
			throw runtimeException;
		throw (Error) throwable;
	}

	private static InternalTransportEndpoint<String> endpoint(InternalTransportIdentity identity,
			BiFunction<InternalTransportAttachmentContext<String>, StartupContext, InternalTransportRuntime> attach) {
		return new InternalTransportEndpoint<>() {
			@Override public InternalTransportIdentity identity() { return identity; }
			@Override public InternalTransportRuntime attach(InternalTransportAttachmentContext<String> context,
					StartupContext startup) { return attach.apply(context, startup); }
		};
	}

	private static InternalTransportRuntime noOpRuntime() {
		return new InternalTransportRuntime() {
			@Override public void start(StartupContext context) {}
			@Override public void shutdownGracefully(ShutdownContext context) {}
			@Override public void shutdownForcibly(ShutdownContext context) {}
		};
	}

	@SuppressWarnings("unchecked")
	private static ThreadLocal<IdentityHashMap<Object, Integer>> depths() throws Exception {
		Field field = LifecycleExecutionContext.class.getDeclaredField("DEPTHS");
		field.setAccessible(true);
		return (ThreadLocal<IdentityHashMap<Object, Integer>>) field.get(null);
	}

	private static int depth(Object owner) throws Exception {
		return depths().get().getOrDefault(owner, 0);
	}
}
