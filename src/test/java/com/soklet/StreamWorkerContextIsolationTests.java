package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class StreamWorkerContextIsolationTests {
	@Test
	void ordinaryPublicSimulationStillCompletesAndNotifiesTermination() {
		ContextState state = new ContextState(Inheritance.NONE);
		assertHealthy(state, runSimulation(state));
	}

	@Test
	void publicWriterContextCannotThrowDuringFrameworkWorkerConstruction() {
		ContextState state = new ContextState(Inheritance.THROW);

		assertHealthy(state, runSimulation(state));
		assertTrue(state.writerSawContext.get());
	}

	@Test
	void publicWriterContextCannotBlockFrameworkWorkerConstruction() throws Exception {
		ContextState state = new ContextState(Inheritance.BLOCK);
		ExecutorService executor = Executors.newSingleThreadExecutor(
				Thread.ofPlatform().inheritInheritableThreadLocals(false).factory());
		Future<ShutdownResult> simulation = executor.submit(() -> runSimulation(state));
		try {
			ShutdownResult result = assertDoesNotThrow(() -> simulation.get(5, TimeUnit.SECONDS),
					"Application inheritance must not delay framework worker construction");
			assertHealthy(state, result);
			assertTrue(state.writerSawContext.get());
		} finally {
			state.releaseInheritance.countDown();
			try {
				simulation.get(5, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void everyCoordinatorWorkerFamilySuppressesApplicationInheritance() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1,
				Duration.ofSeconds(1), failure -> { });
		AtomicInteger inheritanceCalls = new AtomicInteger();
		InheritableThreadLocal<String> local = new InheritableThreadLocal<>() {
			@Override protected String childValue(String value) {
				inheritanceCalls.incrementAndGet();
				throw new IllegalStateException("Application inheritance must not run for a framework helper");
			}
		};
		try {
			for (WorkerFamily family : List.of(
					new WorkerFamily("callbackExecutor", "stream-callback"),
					new WorkerFamily("terminationExecutor", "stream-termination"),
					new WorkerFamily("diagnosticExecutor", "stream-diagnostic"),
					new WorkerFamily("interruptExecutor", "stream-interrupt"),
					new WorkerFamily("supervisor", "stream-supervisor"))) {
				Field field = StreamLifecycleCoordinator.class.getDeclaredField(family.field());
				field.setAccessible(true);
				ThreadFactory factory = ((ThreadPoolExecutor) field.get(coordinator)).getThreadFactory();
				AtomicInteger calls = new AtomicInteger();
				AtomicReference<String> inheritedValue = new AtomicReference<>("not-entered");
				Thread worker = null;
				local.set("application-context");
				try {
					worker = assertDoesNotThrow(() -> factory.newThread(() -> {
						inheritedValue.set(local.get());
						calls.incrementAndGet();
					}), family.field());
					assertTrue(worker.isDaemon());
					assertTrue(worker.getName().startsWith(family.prefix() + "-"));
					assertEquals(Thread.State.NEW, worker.getState());
					worker.start();
					worker.join(2_000);
					assertFalse(worker.isAlive());
					assertEquals(1, calls.get());
					assertNull(inheritedValue.get());
					assertEquals("application-context", local.get());
				} finally {
					local.remove();
					if (worker != null && worker.isAlive()) {
						worker.interrupt();
						worker.join(2_000);
						assertFalse(worker.isAlive());
					}
				}
			}
			assertEquals(0, inheritanceCalls.get());
		} finally {
			local.remove();
			coordinator.stopAdmission();
			assertTrue(coordinator.awaitTermination(System.nanoTime() + Duration.ofSeconds(2).toNanos()));
		}
	}

	@Test
	void explicitCallbackOwnerContextStillEntersAndRestoresBothMarkers() throws Exception {
		Object component = new Object();
		Object graph = new Object();
		Object unrelated = new Object();
		AtomicBoolean componentMarked = new AtomicBoolean();
		AtomicBoolean graphMarked = new AtomicBoolean();
		AtomicBoolean unrelatedMarked = new AtomicBoolean();
		AtomicBoolean markersRestored = new AtomicBoolean();
		CountDownLatch deliveryExited = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = StreamLifecycleCoordinator.withCallbackExecution(1, 1,
				Duration.ofSeconds(1), failure -> { }, action -> {
					try (var ignored = LifecycleExecutionContext.enter(component, graph)) {
						action.run();
					} finally {
						markersRestored.set(!LifecycleExecutionContext.isMarked(component)
								&& !LifecycleExecutionContext.isMarked(graph));
						deliveryExited.countDown();
					}
				});
		var reservation = coordinator.tryReserve();
		assertNotNull(reservation);
		try {
			reservation.dispatchTermination(() -> {
				componentMarked.set(LifecycleExecutionContext.isMarked(component));
				graphMarked.set(LifecycleExecutionContext.isMarked(graph));
				unrelatedMarked.set(LifecycleExecutionContext.isMarked(unrelated));
			});
			reservation.complete();
			assertTrue(deliveryExited.await(2, TimeUnit.SECONDS));
			assertTrue(componentMarked.get());
			assertTrue(graphMarked.get());
			assertFalse(unrelatedMarked.get());
			assertTrue(markersRestored.get());
			assertFalse(LifecycleExecutionContext.isMarked(component));
			assertFalse(LifecycleExecutionContext.isMarked(graph));
		} finally {
			reservation.complete();
			coordinator.stopAdmission();
			assertTrue(coordinator.awaitTermination(System.nanoTime() + Duration.ofSeconds(2).toNanos()));
		}
	}

	private static ShutdownResult runSimulation(ContextState state) {
		Routes routes = new Routes(state);
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0)
				.streamingLifecycleCapacity(1).streamingCallbackConcurrency(1).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Routes.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == Routes.class ? type.cast(routes) : InstanceProvider.defaultInstance().provide(type);
					}
				})
				.responseMarshaler(ResponseMarshaler.builder().resourceMethodHandler((request, response, method) -> {
					if (response.getBody().orElse(null) instanceof StreamingResponseBody body)
						return MarshaledResponse.withResponse(response).streamingResponseBody(body).build();
					return ResponseMarshaler.defaultInstance().forResourceMethod(request, response, method);
				}).build())
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
						if (state.mode != Inheritance.NONE)
							state.terminalContext.set(state.local.get());
						state.terminalCalls.incrementAndGet();
					}
					@Override public void didReceiveLogEvent(LogEvent event) { }
				}).build();
		try {
			return SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator -> {
				try {
					state.status.set(simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/stream"))
							.getMarshaledResponse().getStatusCode());
				} catch (Throwable failure) {
					state.requestFailure.set(failure);
				} finally {
					state.local.remove();
				}
			});
		} catch (SokletShutdownIncompleteException incomplete) {
			return incomplete.getShutdownResult();
		}
	}

	private static void assertHealthy(ContextState state, ShutdownResult result) {
		assertEquals(0, state.inheritanceCalls.get(), "Framework worker construction must not invoke application childValue");
		assertNull(state.requestFailure.get());
		assertEquals(200, state.status.get());
		assertEquals(1, state.writerCalls.get());
		assertEquals(1, state.terminalCalls.get());
		assertNull(state.terminalContext.get());
		assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
		assertTrue(result.isComplete());
	}

	private enum Inheritance { NONE, THROW, BLOCK }
	private record WorkerFamily(String field, String prefix) { }

	private static final class ContextState {
		private final Inheritance mode;
		private final AtomicInteger inheritanceCalls = new AtomicInteger();
		private final AtomicInteger writerCalls = new AtomicInteger();
		private final AtomicInteger terminalCalls = new AtomicInteger();
		private final AtomicInteger status = new AtomicInteger();
		private final AtomicBoolean writerSawContext = new AtomicBoolean();
		private final AtomicReference<String> terminalContext = new AtomicReference<>();
		private final AtomicReference<Throwable> requestFailure = new AtomicReference<>();
		private final CountDownLatch releaseInheritance = new CountDownLatch(1);
		private final InheritableThreadLocal<String> local = new InheritableThreadLocal<>() {
			@Override protected String childValue(String value) {
				inheritanceCalls.incrementAndGet();
				if (mode == Inheritance.THROW)
					throw new IllegalStateException("Application childValue must not run during stream cleanup");
				if (mode == Inheritance.BLOCK) {
					try {
						if (!releaseInheritance.await(10, TimeUnit.SECONDS))
							throw new IllegalStateException("Controlled inheritance gate exceeded its safety bound");
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
					}
				}
				return value;
			}
		};

		private ContextState(Inheritance mode) { this.mode = mode; }
	}

	public static final class Routes {
		private final ContextState state;
		private Routes(ContextState state) { this.state = state; }

		@GET("/stream")
		public Response stream() {
			return Response.withStatusCode(200).body(StreamingResponseBody.fromWriter(stream -> {
				state.writerCalls.incrementAndGet();
				if (state.mode != Inheritance.NONE) {
					state.local.set("application-context");
					state.writerSawContext.set("application-context".equals(state.local.get()));
				}
			})).build();
		}
	}
}
