/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.Handler;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Drain refusals are acceptance observations, never admitted request handling. */
@Timeout(90)
class HttpDrainRejectionObservationTests {
	private enum Branch { CLOSED_BUILT_IN, CLOSED_OUTER, LOST_AFTER_ADMISSION, OPEN }
	private enum Failure { NONE, OBSERVER, COLLECTOR, BOTH }
	private static final String TARGET = "/drain?path=%2F&value=a+b";
	private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 12345);
	private static final LifecyclePolicy POLICY = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofMillis(200))
			.gracefulShutdownTimeout(Duration.ofMillis(400)).forcedShutdownTimeout(Duration.ofMillis(200)).build();

	@Test
	void eachAdmissionRefusalReportsOnceWithoutApplicationHandling() throws Exception {
		for (Branch branch : List.of(Branch.CLOSED_BUILT_IN, Branch.CLOSED_OUTER, Branch.LOST_AFTER_ADMISSION)) {
			Recorder recorder = new Recorder(Failure.NONE);
			try (Fixture fixture = new Fixture(branch, recorder)) {
				for (int i = 0; i < 64; ++i) {
					AtomicReference<MicrohttpResponse> response = new AtomicReference<>();
					fixture.handler.handle(request(), value -> { recorder.offers.incrementAndGet(); response.set(value); });
					assertRefusal(response.get());
				}
				recorder.assertRejected(64);
				assertEquals(0, fixture.handled.get());
				fixture.assertNoAdmissions();
			}
		}
	}

	@Test
	void observerAndCollectorFailuresCannotChangeOrDuplicateTheRefusal() throws Exception {
		for (Branch branch : List.of(Branch.CLOSED_BUILT_IN, Branch.LOST_AFTER_ADMISSION)) {
			for (Failure failure : List.of(Failure.OBSERVER, Failure.COLLECTOR, Failure.BOTH)) {
				Recorder recorder = new Recorder(failure);
				try (Fixture fixture = new Fixture(branch, recorder)) {
					AtomicReference<MicrohttpResponse> response = new AtomicReference<>();
					fixture.handler.handle(request(), value -> { recorder.offers.incrementAndGet(); response.set(value); });
					assertRefusal(response.get());
					recorder.assertRejected(1);
					assertEquals(0, fixture.handled.get());
					fixture.assertNoAdmissions();
				}
			}
		}
	}

	@Test
	void responseOfferFailuresStillReportExactlyOnceAndReleaseAdmissions() throws Exception {
		for (Branch branch : List.of(Branch.CLOSED_BUILT_IN, Branch.LOST_AFTER_ADMISSION)) {
			for (Throwable failure : List.of(new IllegalStateException("offer failed"), new AssertionError("offer failed"))) {
				Recorder recorder = new Recorder(Failure.NONE);
				try (Fixture fixture = new Fixture(branch, recorder)) {
					Throwable observed = assertThrows(failure.getClass(), () -> fixture.handler.handle(request(), response -> {
						recorder.offers.incrementAndGet(); assertRefusal(response);
						if (failure instanceof RuntimeException runtime) throw runtime;
						throw (Error) failure;
					}));
					assertSame(failure, observed);
					recorder.assertRejected(1);
					assertEquals(0, fixture.handled.get());
					fixture.assertNoAdmissions();
				}
			}
		}
	}

	@Test
	void blockingCallbacksRemainTrackedAndCannotJoinTheirOwnShutdown() throws Exception {
		for (boolean blockCollector : new boolean[]{false, true}) {
			CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			AtomicReference<Soklet> ownerReference = new AtomicReference<>();
			AtomicInteger observerCalls = new AtomicInteger(), collectorCalls = new AtomicInteger(), handled = new AtomicInteger();
			AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
			DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
			Consumer<Boolean> callback = collector -> {
				if (collector) { collectorCalls.incrementAndGet(); metrics.didFailToAcceptRequest(ServerType.HTTP, REMOTE, TARGET,
						RequestRejectionReason.REQUEST_HANDLER_EXECUTOR_SHUTDOWN, null); }
				else observerCalls.incrementAndGet();
				try {
					assertTrue(LifecycleExecutionContext.isMarked(ownerReference.get().getDirectLifecycle().executionOwnerToken()));
					if (collector == blockCollector) {
						assertThrows(IllegalStateException.class, () -> ownerReference.get().awaitShutdown());
						entered.countDown(); awaitRelease(release);
					}
				} catch (Throwable failure) { callbackFailure.compareAndSet(null, failure); entered.countDown(); }
			};
			DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").concurrency(1).build();
			// Close only outer admission while the built-in listener remains open.
			server.setStartSetupHookForTests(() -> setOuterSupplier(server, Optional::empty));
			SokletConfig config = SokletConfig.withHttpServer(server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.requestInterceptor(new RequestInterceptor() {
						@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
								java.util.function.Function<Request, MarshaledResponse> generate, Consumer<MarshaledResponse> write) {
							handled.incrementAndGet(); write.accept(MarshaledResponse.fromStatusCode(200));
						}
					}).lifecyclePolicy(POLICY).lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) {}
						@Override public void didFailToAcceptRequest(ServerType type, InetSocketAddress remote, String target,
								RequestRejectionReason reason, Throwable failure) { callback.accept(false); }
					}).metricsCollector(new MetricsCollector() {
						@Override public void didFailToAcceptRequest(ServerType type, InetSocketAddress remote, String target,
								RequestRejectionReason reason, Throwable failure) { callback.accept(true); }
					}).build();
			Soklet owner = Soklet.fromConfig(config); ownerReference.set(owner);
			EventLoop loop = null;
			ShutdownResult frozen = null;
			try {
				owner.start(); loop = server.getEventLoop().orElseThrow();
				try (Socket socket = new Socket()) {
					socket.connect(new InetSocketAddress("127.0.0.1", loop.getPort()), 2000);
					socket.getOutputStream().write(("GET " + TARGET + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
					socket.getOutputStream().flush();
					assertTrue(entered.await(3, TimeUnit.SECONDS));
					assertNull(callbackFailure.get());
					frozen = owner.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS);
					assertFalse(frozen.isComplete());
					assertEquals(ShutdownDisposition.INCOMPLETE, frozen.getShutdownDisposition());
					assertTrue(frozen.getShutdownComponentResult(ShutdownComponentType.HTTP).orElseThrow()
							.getResidualActivityEvidence().orElseThrow().getResidualActivityTypes().contains(ResidualActivityType.EVENT_LOOP));
					assertFalse(loop.isTerminated(), "The blocked selector must remain physical shutdown evidence");
					assertEquals(0, handled.get());
					assertEquals(1, observerCalls.get());
					assertEquals(blockCollector ? 1 : 0, collectorCalls.get(), "Existing observer-before-metrics order remains inline");
					// The response was queued, not flushed: no wire-progress assertion while callback blocks.
				}
			} finally {
				release.countDown();
				try { owner.close(); }
				catch (SokletShutdownIncompleteException incomplete) { assertNotNull(frozen); }
				if (loop != null) assertTrue(loop.joinUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			}
			assertNull(callbackFailure.get());
			assertEquals(1, observerCalls.get()); assertEquals(1, collectorCalls.get());
			assertEquals(1L, rejected(metrics));
			assertFalse(frozen.isComplete(), "Releasing callback work must not rewrite the frozen incomplete result");
		}
	}

	@Test
	void openAdmissionPreservesNormalAcceptanceAndDoesNotRecordARejection() throws Exception {
		Recorder recorder = new Recorder(Failure.NONE);
		try (Fixture fixture = new Fixture(Branch.OPEN, recorder)) {
			CountDownLatch received = new CountDownLatch(1);
			AtomicReference<MicrohttpResponse> response = new AtomicReference<>();
			fixture.handler.handle(request(), value -> { response.set(value); received.countDown(); });
			assertTrue(received.await(3, TimeUnit.SECONDS));
			assertEquals(204, response.get().status());
			assertEquals(1, fixture.handled.get());
			assertEquals(0, recorder.rejections.get()); assertEquals(0, recorder.collectorCalls.get());
			assertEquals(0L, rejected(recorder.metrics));
			assertTrue(recorder.acceptedNotification.await(3, TimeUnit.SECONDS));
			assertEquals(1, recorder.accepted.get());
		}
	}

	public static final class Resource {
		@com.soklet.annotation.GET("/drain") public String drain() { return "unexpected application dispatch"; }
	}

	private static final class Fixture implements AutoCloseable {
		final DefaultHttpServer server;
		final Handler handler;
		final AtomicInteger handled = new AtomicInteger();
		final AdmissionFence outer = new AdmissionFence(true, () -> {});
		Fixture(Branch branch, Recorder recorder) throws Exception {
			server = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").concurrency(1).build();
			server.initialize(SokletConfig.forSimulatorTesting().resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.lifecycleObserver(recorder).metricsCollector(recorder.collector).lifecyclePolicy(POLICY).build(),
					(request, response) -> { handled.incrementAndGet(); response.accept(HttpRequestResult.withMarshaledResponse(
							MarshaledResponse.fromStatusCode(204)).build()); });
			if (branch == Branch.CLOSED_OUTER) setOuterSupplier(server, Optional::empty);
			if (branch == Branch.LOST_AFTER_ADMISSION) setOuterSupplier(server, () -> {
				server.getLifecycleAdapter().generation().orElseThrow().admissionFence().close();
				return outer.tryAdmit();
			});
			server.start();
			if (branch == Branch.CLOSED_BUILT_IN) server.getLifecycleAdapter().generation().orElseThrow().admissionFence().close();
			Field loops = EventLoop.class.getDeclaredField("connectionEventLoops"); loops.setAccessible(true);
			Object loop = ((List<?>) loops.get(server.getEventLoop().orElseThrow())).getFirst();
			Field field = loop.getClass().getDeclaredField("handler"); field.setAccessible(true);
			handler = (Handler) field.get(loop);
		}
		void assertNoAdmissions() {
			assertEquals(0, server.getLifecycleAdapter().generation().orElseThrow().admissionFence().admittedWorkCount());
			assertEquals(0, outer.admittedWorkCount());
		}
		@Override public void close() { server.stop(); }
	}

	private static final class Recorder implements LifecycleObserver {
		final AtomicInteger offers = new AtomicInteger(), rejections = new AtomicInteger(), collectorCalls = new AtomicInteger();
		final AtomicInteger accepted = new AtomicInteger(), preHandlingCalls = new AtomicInteger();
		final CountDownLatch acceptedNotification = new CountDownLatch(1);
		final List<Rejection> observations = new CopyOnWriteArrayList<>();
		final List<Throwable> metricCauses = new CopyOnWriteArrayList<>();
		final DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
		final MetricsCollector collector;
		Recorder(Failure mode) {
			collector = new MetricsCollector() {
				@Override public void didFailToAcceptRequest(ServerType type, InetSocketAddress remote, String target,
						RequestRejectionReason reason, Throwable failure) {
					collectorCalls.incrementAndGet(); metrics.didFailToAcceptRequest(type, remote, target, reason, failure);
					metricCauses.add(failure);
					if (mode == Failure.COLLECTOR || mode == Failure.BOTH) throw new AssertionError("collector failure");
				}
			};
			this.mode = mode;
		}
		final Failure mode;
		@Override public void didReceiveLogEvent(LogEvent event) {}
		@Override public void didFailToAcceptRequest(ServerType type, InetSocketAddress remote, String target,
				RequestRejectionReason reason, Throwable failure) {
			rejections.incrementAndGet();
			observations.add(new Rejection(type, remote, target, reason, failure, offers.get()));
			if (mode == Failure.OBSERVER || mode == Failure.BOTH) throw new AssertionError("observer failure");
		}
		@Override public void willAcceptRequest(ServerType type, InetSocketAddress remote, String target) { preHandlingCalls.incrementAndGet(); }
		@Override public void didAcceptRequest(ServerType type, InetSocketAddress remote, String target) { accepted.incrementAndGet(); acceptedNotification.countDown(); }
		@Override public void willReadRequest(ServerType type, InetSocketAddress remote, String target) { preHandlingCalls.incrementAndGet(); }
		@Override public void didReadRequest(ServerType type, InetSocketAddress remote, String target) { preHandlingCalls.incrementAndGet(); }
		void assertRejected(int count) {
			assertEquals(count, offers.get()); assertEquals(count, rejections.get()); assertEquals(count, collectorCalls.get());
			assertEquals((long) count, rejected(metrics)); assertEquals(0, accepted.get()); assertEquals(0, preHandlingCalls.get());
			assertEquals(count, observations.size()); assertEquals(count, metricCauses.size());
			for (int i = 0; i < count; ++i) {
				Rejection observed = observations.get(i);
				assertEquals(ServerType.HTTP, observed.type()); assertEquals(REMOTE, observed.remote()); assertEquals(TARGET, observed.target());
				assertEquals(RequestRejectionReason.REQUEST_HANDLER_EXECUTOR_SHUTDOWN, observed.reason());
				assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, observed.cause());
				assertSame(observed.cause(), metricCauses.get(i));
				assertEquals(i + 1, observed.offersSeen(), "Response must be offered before observation");
			}
		}
	}

	private record Rejection(ServerType type, InetSocketAddress remote, String target,
			RequestRejectionReason reason, Throwable cause, int offersSeen) {}

	private static void setOuterSupplier(DefaultHttpServer server, Supplier<Optional<AdmissionFence.Admission>> supplier) {
		try { Field field = DefaultHttpServer.class.getDeclaredField("outerRequestAdmissionSupplier"); field.setAccessible(true); field.set(server, supplier); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static MicrohttpRequest request() { return new MicrohttpRequest("GET", TARGET, "HTTP/1.1", List.of(new Header("Host", "localhost")), new byte[0], false, REMOTE); }
	private static void assertRefusal(MicrohttpResponse response) {
		assertNotNull(response); assertEquals(503, response.status());
		assertTrue(response.headers().stream().anyMatch(h -> h.name().equalsIgnoreCase("Connection") && h.value().equalsIgnoreCase("close")));
		assertEquals("HTTP 503: Service Unavailable", new String(response.body(), StandardCharsets.UTF_8));
	}
	private static long rejected(DefaultMetricsCollector collector) { return collector.snapshot().orElseThrow().getHttpRequestRejections()
			.getOrDefault(new MetricsCollector.RequestRejectionKey(RequestRejectionReason.REQUEST_HANDLER_EXECUTOR_SHUTDOWN), 0L); }
	private static void awaitRelease(CountDownLatch latch) {
		boolean interrupted = false;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		try {
			while (latch.getCount() != 0) {
				long remaining = deadline - System.nanoTime();
				assertTrue(remaining > 0, "Callback fixture release timed out");
				try { assertTrue(latch.await(remaining, TimeUnit.NANOSECONDS), "Callback fixture release timed out"); }
				catch (InterruptedException failure) { interrupted = true; }
			}
		} finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
}
