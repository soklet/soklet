package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JvmShutdownHookConfigurationTests {
	@Test
	void hookDefaultsToTrueAndNullRestoresTheDefaultWithoutChangingBuiltConfigurations() {
		SokletConfig.Builder builder = config(new TestTransport());
		assertEquals(Boolean.TRUE, builder.build().isJvmShutdownHookEnabled());
		SokletConfig disabled = builder.jvmShutdownHookEnabled(false).build();
		assertEquals(Boolean.FALSE, disabled.isJvmShutdownHookEnabled());
		assertEquals(Boolean.TRUE, builder.jvmShutdownHookEnabled(true).build().isJvmShutdownHookEnabled());
		assertEquals(Boolean.TRUE, builder.jvmShutdownHookEnabled(false).jvmShutdownHookEnabled(null)
				.build().isJvmShutdownHookEnabled());
		assertEquals(Boolean.FALSE, disabled.isJvmShutdownHookEnabled());
	}

	@Test
	void rawApplicationSettingsRetainTheHookChoiceDuringConfigurationDerivation() {
		for (Boolean choice : Arrays.asList(null, true, false)) {
			SokletConfig source = config(new TestTransport()).jvmShutdownHookEnabled(choice).build();
			SokletConfig.Builder derived = config(new TestTransport()).jvmShutdownHookEnabled(false);
			source.applyApplicationSettingsTo(derived);
			assertEquals(choice == null || choice, derived.build().isJvmShutdownHookEnabled());
			assertEquals(Boolean.TRUE, derived.jvmShutdownHookEnabled(null).build().isJvmShutdownHookEnabled());
		}
	}

	@Test
	void simulatorRetainsTheSettingButRunsWithoutStartingTheSourceListener() {
		for (boolean enabled : List.of(true, false)) {
			HttpServer sourceServer = HttpServer.fromPort(0);
			SokletConfig source = config(sourceServer).jvmShutdownHookEnabled(enabled).build();
			SimulatorConfig simulation = SimulatorConfig.fromSokletConfig(source);
			assertEquals(enabled, simulation.getSokletConfig().isJvmShutdownHookEnabled());
			SokletSimulator.run(simulation, simulator -> {
				assertEquals(200, simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/ready"))
						.getMarshaledResponse().getStatusCode());
			});
			assertFalse(((DefaultHttpServer) sourceServer).isStarted());
		}
	}

	@Test
	void disabledHookSkipsEveryHookOperationAndStillRunsCompleteCleanup() {
		TestTransport transport = new TestTransport();
		HookOperations hooks = new HookOperations(true);
		AtomicInteger cleanupCalls = new AtomicInteger();
		AtomicInteger reports = new AtomicInteger();
		AtomicReference<ShutdownResult> cleanupResult = new AtomicReference<>();
		InternalShutdownResult result = SokletProcessLifecycle.fromConfig(config(transport)
				.jvmShutdownHookEnabled(false).build()).run(environment(hooks, snapshot -> reports.incrementAndGet()),
				cleanup(shutdown -> {
					cleanupCalls.incrementAndGet();
					cleanupResult.set(shutdown);
				}));
		assertEquals(InternalStartupDisposition.READY, result.startupDisposition());
		assertSame(result, cleanupResult.get().internalResult());
		assertTrue(cleanupResult.get().isComplete());
		assertEquals(1, cleanupCalls.get());
		assertEquals(1, transport.startCalls.get());
		assertEquals(1, transport.stopCalls.get());
		assertEquals(1, reports.get(), "Disabling the JVM hook must retain terminal reporting.");
		hooks.assertCounts(0);
	}

	@Test
	void disabledHookStillReportsCleanupFailureAfterCoreShutdown() {
		TestTransport transport = new TestTransport();
		HookOperations hooks = new HookOperations(true);
		IOException cleanupFailure = new IOException("application cleanup failed");
		AtomicReference<SokletApplicationTerminalSnapshot> report = new AtomicReference<>();
		SokletShutdownCleanupException observed = assertThrows(SokletShutdownCleanupException.class,
				() -> SokletProcessLifecycle.fromConfig(config(transport).jvmShutdownHookEnabled(false).build())
						.run(environment(hooks, report::set), cleanup(result -> { throw cleanupFailure; })));
		assertSame(cleanupFailure, observed.getCause());
		assertTrue(observed.getShutdownResult().isComplete());
		assertNotNull(report.get());
		assertTrue(report.get().cleanupOutcome().failed());
		assertEquals(1, transport.stopCalls.get());
		hooks.assertCounts(0);
	}

	@Test
	void defaultAndExplicitTrueStillCreateRegisterAndRemoveOneHook() {
		for (Boolean choice : Arrays.asList(null, true)) {
			TestTransport transport = new TestTransport();
			HookOperations hooks = new HookOperations(false);
			AtomicInteger cleanupCalls = new AtomicInteger();
			InternalShutdownResult result = SokletProcessLifecycle.fromConfig(config(transport)
					.jvmShutdownHookEnabled(choice).build()).run(environment(hooks, snapshot -> { }),
					cleanup(shutdown -> cleanupCalls.incrementAndGet()));
			assertEquals(InternalStartupDisposition.READY, result.startupDisposition());
			assertEquals(1, transport.startCalls.get());
			assertEquals(1, transport.stopCalls.get());
			assertEquals(1, cleanupCalls.get());
			hooks.assertCounts(1);
		}
	}

	@Test
	void readyHandleWithoutHookStillOwnsProgrammaticShutdownAndFinalization() throws Exception {
		TestTransport transport = new TestTransport();
		AtomicReference<ShutdownResult> cleaned = new AtomicReference<>();
		Soklet soklet = Soklet.run(config(transport).jvmShutdownHookEnabled(false).build(), cleanup(cleaned::set));
		try {
			assertTrue(transport.started.get());
			soklet.requestShutdown();
			ShutdownResult result = soklet.awaitShutdown();
			assertTrue(result.isComplete());
			assertSame(result, cleaned.get());
			assertSame(result, soklet.awaitShutdownAsync().toCompletableFuture().get(5, TimeUnit.SECONDS));
			assertEquals(1, transport.stopCalls.get());
		} finally {
			soklet.requestShutdown();
			soklet.awaitShutdown();
		}
	}

	@Test
	void scopedRunWithoutHookStillStopsAfterTheCallbackReturns() {
		TestTransport transport = new TestTransport();
		AtomicInteger callbacks = new AtomicInteger();
		ShutdownResult result = Soklet.run(config(transport).jvmShutdownHookEnabled(false).build(), () -> {
			assertTrue(transport.started.get());
			assertEquals(0, transport.stopCalls.get());
			callbacks.incrementAndGet();
		});
		assertTrue(result.isComplete());
		assertEquals(1, callbacks.get());
		assertEquals(1, transport.stopCalls.get());
	}

	@Test
	void scopedRunWithoutHookPreservesCallbackFailureAndSuppressedCleanupFailure() {
		TestTransport transport = new TestTransport();
		IOException callbackFailure = new IOException("callback failed");
		IOException cleanupFailure = new IOException("cleanup failed");
		IOException observed = assertThrows(IOException.class, () -> Soklet.run(
				config(transport).jvmShutdownHookEnabled(false).build(), cleanup(result -> { throw cleanupFailure; }),
				() -> { throw callbackFailure; }));
		assertSame(callbackFailure, observed);
		assertEquals(1, observed.getSuppressed().length);
		SokletShutdownCleanupException cleanup = assertInstanceOf(SokletShutdownCleanupException.class,
				observed.getSuppressed()[0]);
		assertSame(cleanupFailure, cleanup.getCause());
		assertTrue(cleanup.getShutdownResult().isComplete());
		assertEquals(1, transport.stopCalls.get());
	}

	@Test
	void disabledHookStillRollsBackFailedStartupAndRunsEligibleCleanup() {
		TestTransport transport = new TestTransport();
		RuntimeException startupFailure = new IllegalStateException("startup failed");
		transport.startFailure = startupFailure;
		AtomicInteger cleanupCalls = new AtomicInteger();
		SokletStartupException observed = assertThrows(SokletStartupException.class, () -> Soklet.run(
				config(transport).jvmShutdownHookEnabled(false).build(), cleanup(result -> cleanupCalls.incrementAndGet())));
		assertSame(startupFailure, observed.getCause());
		assertEquals(StartupDisposition.FAILED, observed.getStartupDisposition());
		assertEquals(1, cleanupCalls.get());
		assertEquals(1, transport.stopCalls.get());
	}

	private static ShutdownCleanup cleanup(ShutdownCleanup.Action action) {
		return ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(5), action);
	}

	private static SokletConfig.Builder config(HttpServer transport) {
		return SokletConfig.withHttpServer(transport)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecycleObservers(List.of())
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build());
	}

	private static SokletApplicationEnvironment environment(HookOperations hooks, Consumer<SokletApplicationTerminalSnapshot> reporter) {
		return new SokletApplicationEnvironment(LifecycleRuntimeServices.system(), hooks, reporter::accept,
				(config, services, publisher) -> {
					DirectSokletApplicationRuntime runtime = DirectSokletApplicationRuntime.create(config, services, publisher);
					return new SokletApplicationRuntime() {
						@Override public void start() { runtime.start(); runtime.shutdown(); }
						@Override public void shutdown() { runtime.shutdown(); }
						@Override public InternalLifecycleCoreSnapshot awaitCore() throws InterruptedException { return runtime.awaitCore(); }
						@Override public Optional<RuntimeException> terminalFailure(InternalShutdownResult result) { return runtime.terminalFailure(result); }
						@Override public SokletApplicationCoreDiagnostics diagnostics() { return runtime.diagnostics(); }
					};
				}, hooks::create);
	}

	private static final class HookOperations implements LifecycleProcessAccess {
		private final boolean forbidden;
		private final AtomicInteger created = new AtomicInteger();
		private final AtomicInteger added = new AtomicInteger();
		private final AtomicInteger removed = new AtomicInteger();
		private Thread hook;
		private HookOperations(boolean forbidden) { this.forbidden = forbidden; }
		private Thread create(String name, Runnable task) {
			this.created.incrementAndGet();
			if (this.forbidden) throw new AssertionError("Hook creation must be skipped");
			this.hook = new Thread(task, name);
			return this.hook;
		}
		@Override public void addShutdownHook(Thread hook) {
			this.added.incrementAndGet();
			if (this.forbidden) throw new AssertionError("Hook registration must be skipped");
			assertSame(this.hook, hook);
		}
		@Override public boolean removeShutdownHook(Thread hook) {
			this.removed.incrementAndGet();
			if (this.forbidden) throw new AssertionError("Hook removal must be skipped");
			assertSame(this.hook, hook);
			return true;
		}
		private void assertCounts(int expected) {
			assertEquals(expected, this.created.get());
			assertEquals(expected, this.added.get());
			assertEquals(expected, this.removed.get());
		}
	}

	private static final class TestTransport implements HttpServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private final AtomicBoolean started = new AtomicBoolean();
		private final AtomicInteger startCalls = new AtomicInteger();
		private final AtomicInteger stopCalls = new AtomicInteger();
		private RuntimeException startFailure;
		@Override public TransportIdentity getTransportIdentity() { return this.identity; }
		@Override public TransportRuntime attach(HttpTransportAttachmentContext attachment, StartupContext startup) {
			TransportTerminationSignal signal = attachment.getTransportTerminationSignal();
			return new TransportRuntime() {
				@Override public void start(StartupContext context) {
					startCalls.incrementAndGet();
					if (startFailure != null) throw startFailure;
					started.set(true);
				}
				@Override public void shutdownGracefully(ShutdownContext context) {
					stopCalls.incrementAndGet();
					started.set(false);
					signal.signalTerminated();
				}
				@Override public void shutdownForcibly(ShutdownContext context) { signal.signalTerminated(); }
			};
		}
	}

	public static final class Resource {
		@GET("/ready") public String ready() { return "ready"; }
	}
}
