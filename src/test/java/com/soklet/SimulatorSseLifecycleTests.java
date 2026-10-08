/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import com.soklet.annotation.SseEventSource;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SimulatorSseLifecycleTests {

	@Test
	void closeReportsOneOrderedLifetimeWithExactMetadataToBothSinks() {
		Fixture fixture = new Fixture();
		Probe probe = new Probe();
		Request original = request();
		SokletSimulator.run(config(fixture, probe, 2, 2), simulator -> {
			var accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class, simulator.performSseRequest(original));
			assertEquals(List.of("observer:willEstablish", "metrics:willEstablish", "observer:didEstablish", "metrics:didEstablish"), probe.calls);
			SseConnection snapshot = probe.connections.get(0);
			assertNotSame(original, snapshot.getRequest());
			assertSame(fixture.request, snapshot.getRequest());
			assertSame(accepted.getHttpRequestResult().getResourceMethod().orElseThrow(), snapshot.getResourceMethod());
			assertSame(fixture.context, snapshot.getClientContext().orElseThrow());
			assertNotNull(snapshot.getEstablishedAt());
			accepted.registerEventConsumer(ignored -> {});
			broadcaster(simulator).broadcastEvent(SseEvent.withData("payload").build());
			accepted.close(); accepted.close();
			await(() -> probe.terminations.size() == 4);
			assertEquals(expectedLifetime(), probe.calls);
			assertTrue(probe.connections.stream().allMatch(connection -> connection == snapshot));
			StreamTermination outcome = probe.terminations.get(0);
			assertTrue(probe.terminations.stream().allMatch(termination -> termination == outcome));
			assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, outcome.getReason());
			assertTrue(outcome.getCause().isEmpty());
			assertFalse(outcome.getDuration().isNegative());
			assertEquals(0, broadcaster(simulator).getClientCount());
			assertEquals(0, probe.writeCalls);
		});
		assertEquals(expectedLifetime(), probe.calls);
	}

	@Test
	void checkedInitializerFailureStillPairsAcceptanceAndPreservesTheElectedCause() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		IOException cause = new IOException("initializer failure");
		fixture.initializer = ignored -> { throw cause; };
		SokletSimulator.run(config(fixture, probe, 2, 2), simulator -> {
			IllegalStateException failure = assertThrows(IllegalStateException.class, () -> simulator.performSseRequest(request()));
			assertSame(cause, failure.getCause());
			await(() -> probe.terminations.size() == 4);
			assertEquals(expectedLifetime(), probe.calls);
			assertOutcome(probe, StreamTerminationReason.PRODUCER_FAILED, cause);
			assertEquals(0, broadcaster(simulator).getClientCount());
			assertTrue(probe.handshakeFailures.isEmpty());
		});
	}

	@Test
	void initializerOverflowReportsBackpressureWithTheOriginalFailure() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		fixture.initializer = client -> {
			client.unicastEvent(SseEvent.withData("first").build());
			client.unicastComment(SseComment.fromComment("overflow"));
		};
		SokletSimulator.run(config(fixture, probe, 2, 1), simulator -> {
			IllegalStateException failure = assertThrows(IllegalStateException.class, () -> simulator.performSseRequest(request()));
			await(() -> probe.terminations.size() == 4);
			assertEquals(expectedLifetime(), probe.calls);
			assertOutcome(probe, StreamTerminationReason.BACKPRESSURE, failure);
			assertEquals(0, broadcaster(simulator).getClientCount());
			assertTrue(probe.handshakeFailures.isEmpty());
		});
	}

	@Test
	void unreadBroadcastOverflowReportsBackpressureOnlyOnce() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		SokletSimulator.run(config(fixture, probe, 2, 1), simulator -> {
			var accepted = accepted(simulator); var broadcaster = broadcaster(simulator);
			broadcaster.broadcastEvent(SseEvent.withData("first").build());
			broadcaster.broadcastComment(SseComment.fromComment("overflow"));
			accepted.close();
			await(() -> probe.terminations.size() == 4);
			assertEquals(expectedLifetime(), probe.calls);
			assertEquals(StreamTerminationReason.BACKPRESSURE, probe.terminations.get(0).getReason());
			assertInstanceOf(IllegalStateException.class, probe.terminations.get(0).getCause().orElseThrow());
			assertTrue(probe.terminations.stream().allMatch(outcome -> outcome == probe.terminations.get(0)));
			assertEquals(0, broadcaster.getClientCount());
		});
	}

	@Test
	void teardownReportsServerStoppingBeforeTheScopeReturns() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		SokletSimulator.run(config(fixture, probe, 2, 2), SimulatorSseLifecycleTests::accepted);
		assertEquals(expectedLifetime(), probe.calls);
		assertOutcome(probe, StreamTerminationReason.SERVER_STOPPING, null);
	}

	@Test
	void callbackFailuresAreLoggedIndependentlyAndDoNotReplaceTheOutcome() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		probe.fail = true;
		SokletSimulator.run(config(fixture, probe, 2, 2), simulator -> {
			var accepted = accepted(simulator); accepted.close();
			await(() -> probe.logs.size() == 8);
			assertEquals(expectedLifetime(), probe.calls);
			assertOutcome(probe, StreamTerminationReason.CLIENT_DISCONNECTED, null);
			assertEquals(4, probe.logs.stream().filter(log -> log.getLogEventType() == LogEventType.METRICS_COLLECTOR_FAILED).count());
			assertEquals(Set.of(LogEventType.LIFECYCLE_OBSERVER_WILL_ESTABLISH_SSE_CONNECTION_FAILED,
					LogEventType.LIFECYCLE_OBSERVER_DID_ESTABLISH_SSE_CONNECTION_FAILED,
					LogEventType.LIFECYCLE_OBSERVER_WILL_TERMINATE_SSE_CONNECTION_FAILED,
					LogEventType.LIFECYCLE_OBSERVER_DID_TERMINATE_SSE_CONNECTION_FAILED,
					LogEventType.METRICS_COLLECTOR_FAILED),
					Set.copyOf(probe.logs.stream().map(LogEvent::getLogEventType).toList()));
			assertTrue(probe.logs.stream().allMatch(log -> log.getThrowable().orElseThrow() == probe.callbackFailure));
		});
	}

	@Test
	void blockedTerminalObserverDoesNotDelayReleaseOrAnotherLifetimeAndRetainsCapacity() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		probe.terminalEntered = entered; probe.releaseTerminal = release;
		try {
			SokletSimulator.run(config(fixture, probe, 2, 2), simulator -> {
				var first = accepted(simulator); var second = accepted(simulator);
				SseConnection firstSnapshot = probe.connections.get(0); probe.blockConnection = firstSnapshot;
				var broadcaster = broadcaster(simulator);
				first.close(); await(entered);
				assertEquals(1, broadcaster.getClientCount());
				second.close();
				await(() -> probe.terminations.size() == 5);
				assertEquals(0, broadcaster.getClientCount());
				StreamLifecycleCoordinator owner = coordinator(simulator);
				await(() -> owner.snapshot().reservations() == 1);
				assertTrue(owner.snapshot().callbacks() > 0);
				var third = accepted(simulator);
				assertInstanceOf(SseRequestResult.RequestFailed.class, simulator.performSseRequest(request()));
				await(() -> probe.handshakeFailures.size() == 2);
				assertEquals(List.of(SseConnection.HandshakeFailureReason.CAPACITY_EXCEEDED,
						SseConnection.HandshakeFailureReason.CAPACITY_EXCEEDED), probe.handshakeFailures);
				release.countDown();
				await(() -> owner.snapshot().reservations() == 1);
				third.close(); await(() -> owner.snapshot().reservations() == 0);
			});
		} finally { release.countDown(); }
	}

	@Test
	void shutdownDuringEstablishmentPreservesOrderAndCountsTheBlockedCallback() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		probe.establishmentEntered = entered; probe.releaseEstablishment = release;
		AtomicReference<Thread> worker = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			SokletSimulator.run(config(fixture, probe, 2, 2), simulator -> {
				Thread thread = new Thread(() -> {
					try { simulator.performSseRequest(request()); } catch (Throwable thrown) { failure.set(thrown); }
				}, "sse-establishment-observer");
				worker.set(thread); thread.start(); await(entered);
				StreamLifecycleCoordinator owner = coordinator(simulator); owner.force();
				assertEquals(1, owner.snapshot().runningProducers());
				assertFalse(owner.isTerminated());
				assertEquals(List.of("observer:willEstablish", "metrics:willEstablish", "observer:didEstablish"), probe.calls);
				release.countDown(); join(thread);
				await(() -> probe.terminations.size() == 4);
				assertInstanceOf(IllegalStateException.class, failure.get());
				assertEquals(expectedLifetime(), probe.calls);
				assertOutcome(probe, StreamTerminationReason.SERVER_STOPPING, null);
				assertTrue(probe.handshakeFailures.isEmpty());
				await(owner::isTerminated);
			});
		} finally { release.countDown(); join(worker.get()); }
	}

	@Test
	void initializerFailureWinsBeforeABlockedEstablishmentObserverAndConcurrentShutdown() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		IOException cause = new IOException("first failure"); fixture.initializer = ignored -> { throw cause; };
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		probe.establishmentEntered = entered; probe.releaseEstablishment = release;
		AtomicReference<Thread> worker = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			SokletSimulator.run(config(fixture, probe, 2, 2), simulator -> {
				Thread thread = new Thread(() -> {
					try { simulator.performSseRequest(request()); } catch (Throwable thrown) { failure.set(thrown); }
				}, "sse-failed-initializer-observer");
				worker.set(thread); thread.start(); await(entered);
				StreamLifecycleCoordinator owner = coordinator(simulator); owner.force();
				assertFalse(owner.isTerminated()); assertEquals(1, owner.snapshot().reservations());
				release.countDown(); join(thread); await(owner::isTerminated);
				assertSame(cause, failure.get().getCause());
				assertEquals(expectedLifetime(), probe.calls);
				assertOutcome(probe, StreamTerminationReason.PRODUCER_FAILED, cause);
			});
		} finally { release.countDown(); join(worker.get()); }
	}

	@Test
	void rejectedAndFailedRequestsReportHandshakeFailureWithoutInventingAnAcceptedLifetime() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		fixture.reject = true;
		SokletSimulator.run(config(fixture, probe, 2, 2), simulator -> {
			assertInstanceOf(SseRequestResult.HandshakeRejected.class, simulator.performSseRequest(request()));
			await(() -> probe.handshakeFailures.size() == 2);
			assertEquals(List.of(SseConnection.HandshakeFailureReason.HANDSHAKE_REJECTED,
					SseConnection.HandshakeFailureReason.HANDSHAKE_REJECTED), probe.handshakeFailures);
			fixture.reject = false; fixture.handlerFailure = new IllegalArgumentException("handler failure");
			assertInstanceOf(SseRequestResult.RequestFailed.class, simulator.performSseRequest(request()));
			await(() -> probe.handshakeFailures.size() == 4);
			assertEquals(SseConnection.HandshakeFailureReason.INTERNAL_ERROR, probe.handshakeFailures.get(2));
			assertTrue(probe.handshakeCauses.stream().allMatch(cause -> cause == fixture.handlerFailure));
			assertTrue(probe.calls.isEmpty()); assertTrue(probe.connections.isEmpty());
		});
	}

	@Test
	void cancellationBeforeConstructionStillPairsAcceptanceAndSuppressesTheInitializer() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		SokletSimulator.run(config(fixture, probe, 2, 2), simulator -> {
			var first = accepted(simulator); first.close();
			StreamLifecycleCoordinator owner = coordinator(simulator);
			await(() -> owner.snapshot().reservations() == 0);
			var reservation = owner.tryReserve(); assertNotNull(reservation);
			owner.force();
			var canceled = new SseRequestResult.HandshakeAccepted(first.getSseHandshakeResult(),
					fixture.request, first.getHttpRequestResult(), (Soklet.MockSseServer) simulator.getSseServer().orElseThrow(), reservation);
			try { assertFalse(canceled.initialize(ignored -> fail("Canceled initializer entered"))); }
			catch (Exception failure) { throw new AssertionError(failure); }
			await(owner::isTerminated);
			assertEquals(16, probe.calls.size());
			assertEquals(expectedLifetime(), probe.calls.subList(8, 16));
			assertEquals(StreamTerminationReason.SERVER_STOPPING, probe.terminations.get(4).getReason());
			assertTrue(probe.terminations.get(4).getCause().isEmpty());
			assertEquals(0, broadcaster(simulator).getClientCount());
		});
	}

	@Test
	void blockedHandshakeFailureObservationHasABoundedAllowanceAndDoesNotDelayTheResponse() {
		Fixture fixture = new Fixture(); Probe probe = new Probe(); fixture.reject = true;
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		probe.handshakeEntered = entered; probe.releaseHandshake = release;
		try {
			SokletSimulator.run(config(fixture, probe, 1, 2), simulator -> {
				assertInstanceOf(SseRequestResult.HandshakeRejected.class, simulator.performSseRequest(request()));
				await(entered);
				for (int i = 0; i < 20; i++)
					assertInstanceOf(SseRequestResult.HandshakeRejected.class, simulator.performSseRequest(request()));
				assertEquals(1, coordinator(simulator).snapshot().callbacks());
				assertEquals(1, probe.handshakeFailures.size());
				assertEquals(20, probe.logs.size());
				assertTrue(probe.logs.stream().allMatch(log -> log.getLogEventType()
						== LogEventType.LIFECYCLE_OBSERVER_DID_ESTABLISH_SSE_CONNECTION_FAILED));
				release.countDown(); await(() -> coordinator(simulator).snapshot().callbacks() == 0);
				assertEquals(2, probe.handshakeFailures.size());
			});
		} finally { release.countDown(); }
	}

	@Test
	void anObserverThatOutlivesTeardownRemainsAccountedUntilItsActualReturn() {
		Fixture fixture = new Fixture(); Probe probe = new Probe();
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		probe.terminalEntered = entered; probe.releaseTerminal = release;
		AtomicReference<StreamLifecycleCoordinator> owner = new AtomicReference<>();
		try {
			assertThrows(SokletShutdownIncompleteException.class, () ->
					SokletSimulator.run(config(fixture, probe, 1, 2, Duration.ofMillis(20)), simulator -> {
						var accepted = accepted(simulator); owner.set(coordinator(simulator));
						probe.blockConnection = probe.connections.get(0);
						accepted.close(); await(entered);
					}));
			assertFalse(owner.get().isTerminated());
			assertEquals(1, owner.get().snapshot().reservations());
			assertEquals(1, owner.get().snapshot().callbacks());
			release.countDown(); await(() -> owner.get().isTerminated());
			assertEquals(expectedLifetime(), probe.calls);
			assertOutcome(probe, StreamTerminationReason.CLIENT_DISCONNECTED, null);
		} finally { release.countDown(); }
	}

	private static void assertOutcome(Probe probe, StreamTerminationReason reason, Throwable cause) {
		assertEquals(4, probe.terminations.size());
		for (StreamTermination outcome : probe.terminations) {
			assertEquals(reason, outcome.getReason()); assertSame(cause, outcome.getCause().orElse(null));
		}
	}
	private static List<String> expectedLifetime() {
		return List.of("observer:willEstablish", "metrics:willEstablish", "observer:didEstablish", "metrics:didEstablish",
				"observer:willTerminate", "metrics:willTerminate", "observer:didTerminate", "metrics:didTerminate");
	}
	private static final ResourcePath PATH = ResourcePath.fromPath("/lifecycle-events");
	private static Request request() { return Request.withPath(HttpMethod.GET, "/lifecycle-events").build(); }
	private static SseRequestResult.HandshakeAccepted accepted(Simulator simulator) {
		return assertInstanceOf(SseRequestResult.HandshakeAccepted.class, simulator.performSseRequest(request()));
	}
	private static SseBroadcaster broadcaster(Simulator simulator) { return simulator.getSseServer().orElseThrow().acquireBroadcaster(PATH).orElseThrow(); }
	private static StreamLifecycleCoordinator coordinator(Simulator simulator) { return ((Soklet.DefaultSimulator) simulator).getSseLifecycleCoordinatorForTests().orElseThrow(); }
	private static SimulatorConfig config(Fixture fixture, Probe probe, int capacity, int queueCapacity) {
		return config(fixture, probe, capacity, queueCapacity, Duration.ofSeconds(2));
	}
	private static SimulatorConfig config(Fixture fixture, Probe probe, int capacity, int queueCapacity, Duration budget) {
		return SimulatorConfig.fromSokletConfig(SokletConfig.withSseServer(SseServer.withPort(0)
				.streamingLifecycleCapacity(capacity).connectionQueueCapacity(queueCapacity).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Fixture.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type == Fixture.class ? type.cast(fixture) : InstanceProvider.defaultInstance().provide(type); }
				}).lifecycleObserver(probe.observer()).metricsCollector(probe.metrics())
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(budget).forcedShutdownTimeout(budget).build()).build());
	}
	private static void await(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		assertTrue(condition.getAsBoolean(), "Controlled lifecycle transition did not finish");
	}
	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
	}
	private static void hold(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) { try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
		if (interrupted) Thread.currentThread().interrupt();
	}
	private static void join(Thread thread) {
		if (thread == null) return;
		try { thread.join(5000); assertFalse(thread.isAlive()); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
	}
	public static final class Fixture {
		final Object context = new Object();
		volatile SseClientInitializer initializer = ignored -> {};
		volatile Request request;
		volatile boolean reject;
		volatile RuntimeException handlerFailure;
		@SseEventSource("/lifecycle-events")
		public SseHandshakeResult events(Request request) {
			this.request = request;
			if (handlerFailure != null) throw handlerFailure;
			if (reject) return SseHandshakeResult.rejectWithResponse(Response.fromStatusCode(403));
			return SseHandshakeResult.Accepted.builder().clientContext(context).clientInitializer(initializer).build();
		}
	}
	private static final class Probe {
		final List<String> calls = new CopyOnWriteArrayList<>();
		final List<SseConnection> connections = new CopyOnWriteArrayList<>();
		final List<StreamTermination> terminations = new CopyOnWriteArrayList<>();
		final List<SseConnection.HandshakeFailureReason> handshakeFailures = new CopyOnWriteArrayList<>();
		final List<Throwable> handshakeCauses = new CopyOnWriteArrayList<>();
		final List<LogEvent> logs = new CopyOnWriteArrayList<>();
		final RuntimeException callbackFailure = new IllegalStateException("callback failure");
		volatile boolean fail;
		volatile int writeCalls;
		volatile SseConnection blockConnection;
		volatile CountDownLatch terminalEntered, releaseTerminal, establishmentEntered, releaseEstablishment, handshakeEntered, releaseHandshake;
		void callback(String name, SseConnection connection, StreamTermination termination) {
			calls.add(name);
			if (connection != null) connections.add(connection);
			if (termination != null) terminations.add(termination);
			if (name.equals("observer:willTerminate") && connection == blockConnection && terminalEntered != null) {
				terminalEntered.countDown(); hold(releaseTerminal);
			}
			if (name.equals("observer:didEstablish") && establishmentEntered != null) {
				establishmentEntered.countDown(); hold(releaseEstablishment);
			}
			if (fail) throw callbackFailure;
		}
		void failed(SseConnection.HandshakeFailureReason reason, Throwable cause) {
			if (cause != null) handshakeCauses.add(cause);
			handshakeFailures.add(reason);
		}
		LifecycleObserver observer() { return new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
			@Override public void willEstablishSseConnection(Request request, ResourceMethod method) { callback("observer:willEstablish", null, null); }
			@Override public void didEstablishSseConnection(SseConnection connection) { callback("observer:didEstablish", connection, null); }
			@Override public void willTerminateSseConnection(SseConnection connection, StreamTermination outcome) { callback("observer:willTerminate", connection, outcome); }
			@Override public void didTerminateSseConnection(SseConnection connection, StreamTermination outcome) { callback("observer:didTerminate", connection, outcome); }
			@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod method, SseConnection.HandshakeFailureReason reason, Throwable cause) {
				failed(reason, cause);
				if (handshakeEntered != null) { handshakeEntered.countDown(); hold(releaseHandshake); }
				if (fail) throw callbackFailure;
			}
			@Override public void willWriteSseEvent(SseConnection connection, SseEvent event) { writeCalls++; }
		}; }
		MetricsCollector metrics() { return new MetricsCollector() {
			@Override public void willEstablishSseConnection(Request request, ResourceMethod method) { callback("metrics:willEstablish", null, null); }
			@Override public void didEstablishSseConnection(SseConnection connection) { callback("metrics:didEstablish", connection, null); }
			@Override public void willTerminateSseConnection(SseConnection connection, StreamTermination outcome) { callback("metrics:willTerminate", connection, outcome); }
			@Override public void didTerminateSseConnection(SseConnection connection, StreamTermination outcome) { callback("metrics:didTerminate", connection, outcome); }
			@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod method, SseConnection.HandshakeFailureReason reason, Throwable cause) { failed(reason, cause); if (fail) throw callbackFailure; }
			@Override public void willWriteSseEvent(SseConnection connection, SseEvent event) { writeCalls++; }
		}; }
	}
}
