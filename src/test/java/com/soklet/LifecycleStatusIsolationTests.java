package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class LifecycleStatusIsolationTests {
	public static class HttpResource { @GET("/ok") public String ok() { return "ok"; } }
	public static class SseResource {
		@SseEventSource("/events") public SseHandshakeResult events() {
			return SseHandshakeResult.Accepted.builder().build();
		}
	}

	@Test
	void blockedStatusDoesNotHoldHttpOrSseOwnerAndRemainsPhysicalWork() {
		assertAll("protocols", () -> blocked(ServerType.HTTP), () -> blocked(ServerType.SSE));
	}

	private static void blocked(ServerType type) throws Exception {
		try (Fixture fixture = new Fixture(type, Mode.BLOCK)) {
			fixture.stop();
			assertTrue(fixture.executor.entered.await(2, TimeUnit.SECONDS));
			ShutdownResult result = fixture.result.get(2, TimeUnit.SECONDS);
			assertFalse(result.isComplete());
			assertTrue(fixture.executor.queries.stream().anyMatch(Thread::isAlive));
			assertFalse(fixture.executor.release.getCount() == 0);
			assertTrue(result.getShutdownComponentResults().stream().anyMatch(component ->
					component.getResidualActivityEvidence().orElseThrow().getResidualActivityTypes()
							.contains(ResidualActivityType.LIFECYCLE_CALL)));
			fixture.assertNoCoordinatorQuery();
			fixture.executor.release.countDown();
			fixture.joinQueries();
			assertSame(result, fixture.result.get());
			assertFalse(result.isComplete(), "Late status return cannot alter frozen evidence");
		}
	}

	@Test
	void healthyAndSelfWaitingStatusCallbacksRemainGracefulAndOwnerScoped() {
		assertAll("protocols and callbacks",
				() -> healthy(ServerType.HTTP, Mode.HEALTHY),
				() -> healthy(ServerType.SSE, Mode.HEALTHY),
				() -> healthy(ServerType.HTTP, Mode.SELF_WAIT),
				() -> healthy(ServerType.SSE, Mode.SELF_WAIT));
	}

	private static void healthy(ServerType type, Mode mode) throws Exception {
		try (Fixture fixture = new Fixture(type, mode)) {
			fixture.stop();
			ShutdownResult result = fixture.result.get(2, TimeUnit.SECONDS);
			assertTrue(result.isComplete());
			assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
			assertNull(fixture.executor.failure.get());
			assertTrue(fixture.executor.calls.get() > 0);
			if (mode == Mode.SELF_WAIT)
				assertEquals(fixture.executor.calls.get(), fixture.executor.selfWaitRejections.get());
			fixture.assertNoCoordinatorQuery();
		}
	}

	@Test
	void throwingStatusRemainsContainedAndDoesNotReachCoordinatorFallback() {
		assertAll("protocols", () -> throwing(ServerType.HTTP), () -> throwing(ServerType.SSE));
	}

	private static void throwing(ServerType type) throws Exception {
		try (Fixture fixture = new Fixture(type, Mode.THROW)) {
			fixture.stop();
			ShutdownResult result = fixture.result.get(2, TimeUnit.SECONDS);
			assertFalse(result.isComplete());
			assertTrue(result.getShutdownComponentResults().stream()
					.flatMap(component -> component.getThrowables().stream())
					.anyMatch(failure -> failure == fixture.executor.expected));
			fixture.assertNoCoordinatorQuery();
		}
	}

	@Test
	void snapshotsAreImmutableAndDelayedPositiveCannotOverwriteTermination() throws Exception {
		LifecycleResidualActivity evidence = new LifecycleResidualActivity();
		evidence.recordObservation(Set.of(InternalResidualActivityType.EVENT_LOOP));
		evidence.recordObservation(Set.of(InternalResidualActivityType.CALLBACK));
		Set<InternalResidualActivityType> old = evidence.snapshot();
		assertEquals(Set.of(InternalResidualActivityType.EVENT_LOOP,
				InternalResidualActivityType.CALLBACK), old);
		assertThrows(UnsupportedOperationException.class, old::clear);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread delayed = new Thread(() -> {
			entered.countDown();
			try {
				assertTrue(release.await(2, TimeUnit.SECONDS));
				evidence.recordObservation(Set.of(InternalResidualActivityType.EXECUTOR_TASK));
			} catch (Throwable problem) { failure.set(problem); }
		}, "delayed-lifecycle-status-test");
		delayed.start();
		try {
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			evidence.recordTermination();
		} finally {
			release.countDown();
			delayed.join(2_000);
		}
		assertFalse(delayed.isAlive());
		assertNull(failure.get());
		assertTrue(evidence.snapshot().isEmpty());
		assertTrue(evidence.snapshot(Set.of(InternalResidualActivityType.EXECUTOR_TASK)).isEmpty(),
				"A successful proof suppresses conservative framework-reference hints too");
		assertEquals(2, old.size());
	}

	@Test
	void launchFailureDoesNotQueryUntrustedResidualDiagnostics() {
		RuntimeException expected = new IllegalStateException("worker launch failure");
		AtomicInteger queries = new AtomicInteger();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> { throw expected; });
		var operations = new BuiltInTransportLifecycleAdapter.Operations() {
			@Override public void quiesce() {}
			@Override public void force() {}
			@Override public boolean awaitTermination(long deadline) { return false; }
			@Override public Set<InternalResidualActivityType> residualActivity() {
				queries.incrementAndGet();
				throw new AssertionError("No worker is available to query application state");
			}
			@Override public void releaseTerminatedEvidence() {}
		};
		var adapter = new BuiltInTransportLifecycleAdapter(InternalLifecycleComponentType.HTTP,
				operations, () -> Duration.ZERO, Duration.ZERO, () -> 0L, workers);
		var generation = adapter.beginStart();
		adapter.markReady(generation);
		adapter.stop();
		var result = adapter.result().orElseThrow().participantResult(InternalLifecycleComponentType.HTTP).orElseThrow();
		assertEquals(InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN, result.disposition());
		assertTrue(result.residualActivity().isEmpty());
		assertEquals(List.of(expected), result.failures());
		assertEquals(0, queries.get());
		assertTrue(adapter.retentionSummary().isPresent());
	}

	@Test
	void provenMcpSimulationReleaseDoesNotRequeryApplicationStatus() throws Exception {
		StatusExecutor executor = new StatusExecutor(Mode.HEALTHY);
		executor.forbiddenStatusThread = Thread.currentThread();
		var versions = Set.of(McpProtocolVersion.V2026_07_28);
		var endpoint = McpEndpoint.withPath("/status",
				McpImplementation.withNameAndVersion("status", "1").build(), versions).build();
		var server = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.requestHandlerExecutorServiceSupplier(() -> executor).build();
		try {
			ShutdownResult result = SokletSimulator.run(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
							.startupCancelationTimeout(Duration.ofMillis(100))
							.gracefulShutdownTimeout(Duration.ofMillis(500))
							.forcedShutdownTimeout(Duration.ofMillis(200)).build()).build(),
					simulator -> {
						executor.armed.set(true);
						var session = (com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.SimulationSession)
								field(simulator, "mcpSimulationSession");
						assertTrue(session.lifecycleEvidence().executorTask());
						assertEquals(0, executor.calls.get(),
								"Framework evidence must not consult application status on the caller");
					});
			assertTrue(result.isComplete());
			assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
			assertEquals(0, executor.forbiddenQueries.get());
			assertTrue(executor.calls.get() > 0, "Existing proof worker must establish physical termination");
		} finally {
			executor.release.countDown();
			executor.delegate.shutdownNow();
			assertTrue(executor.delegate.awaitTermination(2, TimeUnit.SECONDS));
		}

		StatusExecutor blocked = new StatusExecutor(Mode.BLOCK);
		blocked.blockOnlyProofWorkers = true;
		var blockedServer = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.requestHandlerExecutorServiceSupplier(() -> blocked).build();
		var blockedConfig = SokletConfig.withMcpServer(blockedServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
						.startupCancelationTimeout(Duration.ofMillis(100))
						.gracefulShutdownTimeout(Duration.ofMillis(100))
						.forcedShutdownTimeout(Duration.ofMillis(100)).build()).build();
		AtomicReference<Object> session = new AtomicReference<>();
		FutureTask<ShutdownResult> result = new FutureTask<>(() -> {
			try {
				return SokletSimulator.run(blockedConfig, simulator -> {
					blocked.forbiddenStatusThread = Thread.currentThread();
					blocked.armed.set(true);
					session.set(field(simulator, "mcpSimulationSession"));
				});
			} catch (SokletShutdownIncompleteException incomplete) {
				return incomplete.getShutdownResult();
			}
		});
		Thread owner = new Thread(result, "blocked-status-simulation-owner-test");
		owner.start();
		try {
			assertTrue(blocked.entered.await(2, TimeUnit.SECONDS));
			ShutdownResult frozen = result.get(2, TimeUnit.SECONDS);
			assertFalse(frozen.isComplete());
			assertEquals(0, blocked.forbiddenQueries.get());
			assertEquals(1, blocked.release.getCount());
			blocked.forbiddenStatusThread = Thread.currentThread();
			var retainedSession = (com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.SimulationSession)
					session.get();
			assertTrue(retainedSession.lifecycleEvidence().executorTask());
			assertEquals(0, blocked.forbiddenQueries.get(),
					"Unproven framework evidence must not run the retained executor's getter");
			assertTrue(blocked.queries.stream().anyMatch(thread ->
					thread.isAlive() && thread.getName().contains("termination-observer")));
			// Scoped failure retains the session graph. Separately exercise the
			// compatibility/startup retention helper on that exact held generation.
			blocked.forbiddenStatusThread = Thread.currentThread();
			Object rawSession = field(session.get(), "delegate");
			Object generation = ((AtomicReference<?>) field(rawSession, "generation")).get();
			Object runtime = field(rawSession, "this$0");
			var retain = runtime.getClass().getDeclaredMethod(
					"retainSimulationGenerationEvidence", generation.getClass());
			retain.setAccessible(true);
			retain.invoke(runtime, generation);
			assertEquals(0, blocked.forbiddenQueries.get());
			assertSame(field(generation, "application"), field(runtime, "residualSimulationApplicationExecution"));
			assertSame(field(generation, "processor"), field(runtime, "residualSimulationRequestProcessor"));
			blocked.release.countDown();
			assertSame(frozen, result.get());
			assertFalse(frozen.isComplete());
		} finally {
			blocked.release.countDown();
			owner.join(3_000);
			assertFalse(owner.isAlive());
			for (Thread thread : blocked.queries) {
				thread.join(2_000);
				assertFalse(thread.isAlive());
			}
			blocked.delegate.shutdownNow();
			assertTrue(blocked.delegate.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	private static Object field(Object target, String name) throws Exception {
		var field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private enum Mode { HEALTHY, BLOCK, THROW, SELF_WAIT }

	private static final class StatusExecutor extends AbstractExecutorService {
		private final ExecutorService delegate = Executors.newSingleThreadExecutor();
		private final AtomicBoolean armed = new AtomicBoolean();
		private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		private final Set<Thread> queries = ConcurrentHashMap.newKeySet();
		private final AtomicInteger calls = new AtomicInteger(), selfWaitRejections = new AtomicInteger();
		private final AtomicInteger forbiddenQueries = new AtomicInteger();
		private volatile Thread forbiddenStatusThread;
		private volatile boolean blockOnlyProofWorkers;
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private final RuntimeException expected = new IllegalStateException("status failure");
		private final AtomicReference<Soklet> owner = new AtomicReference<>();
		private final Mode mode;
		private StatusExecutor(Mode mode) { this.mode = mode; }
		@Override public void execute(Runnable task) { this.delegate.execute(task); }
		@Override public void shutdown() { this.delegate.shutdown(); }
		@Override public List<Runnable> shutdownNow() { return this.delegate.shutdownNow(); }
		@Override public boolean isShutdown() { return this.delegate.isShutdown(); }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return this.delegate.awaitTermination(timeout, unit);
		}
		@Override public boolean isTerminated() {
			if (this.armed.get()) {
				this.calls.incrementAndGet(); this.queries.add(Thread.currentThread()); this.entered.countDown();
				if (Thread.currentThread() == this.forbiddenStatusThread) {
					this.forbiddenQueries.incrementAndGet();
					throw this.expected;
				}
				if (this.mode == Mode.THROW) throw this.expected;
				if (this.mode == Mode.SELF_WAIT) {
					try { this.owner.get().awaitShutdown(); this.failure.compareAndSet(null, new AssertionError("self-wait allowed")); }
					catch (IllegalStateException expected) { this.selfWaitRejections.incrementAndGet(); }
					catch (Throwable unexpected) { this.failure.compareAndSet(null, unexpected); }
				}
				if (this.mode == Mode.BLOCK && (!this.blockOnlyProofWorkers
						|| Thread.currentThread().getName().contains("termination-observer"))) {
					boolean interrupted = false;
					try { while (this.release.getCount() != 0) {
						try { this.release.await(); } catch (InterruptedException ignored) { interrupted = true; }
					} } finally { if (interrupted) Thread.currentThread().interrupt(); }
				}
			}
			return this.delegate.isTerminated();
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final StatusExecutor executor;
		private final Soklet owner;
		private final FutureTask<ShutdownResult> result;
		private final Thread waiter;
		private Fixture(ServerType type, Mode mode) {
			this.executor = new StatusExecutor(mode);
			SokletConfig.Builder builder = type == ServerType.HTTP
					? SokletConfig.withHttpServer(HttpServer.withPort(0).host("127.0.0.1")
						.requestHandlerExecutorServiceSupplier(() -> this.executor).build())
						.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HttpResource.class)))
					: SokletConfig.withSseServer(SseServer.withPort(0).host("127.0.0.1")
						.requestHandlerExecutorServiceSupplier(() -> this.executor).build())
						.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SseResource.class)));
			this.owner = Soklet.run(builder.jvmShutdownHookEnabled(false)
					.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
							.startupCancelationTimeout(Duration.ofMillis(100))
							.gracefulShutdownTimeout(Duration.ofMillis(100))
							.forcedShutdownTimeout(Duration.ofMillis(100)).build()).build());
			this.executor.owner.set(this.owner);
			this.result = new FutureTask<>(() -> {
				try { return this.owner.awaitShutdown(); }
				catch (SokletShutdownIncompleteException incomplete) { return incomplete.getShutdownResult(); }
			});
			this.waiter = new Thread(this.result, "status-isolation-owner-await-test");
		}
		private void stop() { this.executor.armed.set(true); this.waiter.start(); this.owner.requestShutdown(); }
		private void assertNoCoordinatorQuery() {
			assertTrue(this.executor.queries.stream().noneMatch(thread ->
					thread.getName().equals("soklet-lifecycle-coordinator")));
		}
		private void joinQueries() throws InterruptedException {
			for (Thread thread : this.executor.queries) {
				thread.join(2_000);
				assertFalse(thread.isAlive());
			}
		}
		@Override public void close() throws Exception {
			this.executor.release.countDown();
			for (Thread thread : this.executor.queries) if (thread.isAlive()) thread.interrupt();
			this.owner.requestShutdown();
			if (this.waiter.getState() == Thread.State.NEW) this.waiter.start();
			this.waiter.join(3_000);
			assertFalse(this.waiter.isAlive());
			joinQueries();
			this.executor.delegate.shutdownNow();
			assertTrue(this.executor.delegate.awaitTermination(2, TimeUnit.SECONDS));
		}
	}
}
