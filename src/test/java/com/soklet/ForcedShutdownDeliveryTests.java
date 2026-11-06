package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.internal.microhttp.EventLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ForcedShutdownDeliveryTests {
	@Test
	void completedStartStillReceivesOneForcedDeliveryAfterFreeze() {
		DirectParticipantPhaseGate gate = new DirectParticipantPhaseGate();
		ShutdownContext forced = new ShutdownContext(ShutdownPhase.FORCED, () -> 0L, 0L);
		ShutdownContext graceful = new ShutdownContext(ShutdownPhase.GRACEFUL, () -> 0L, 0L);
		assertTrue(gate.claimStart());
		assertNull(gate.completeStartCall());
		gate.freezeForClassification(forced);
		assertFalse(gate.claimStart());
		assertNull(gate.requestPhase(graceful));
		assertSame(forced, gate.requestPhase(forced));
		assertNull(gate.requestPhase(forced));
		assertNull(gate.requestPhase(graceful));
		assertFalse(gate.startupCallActive());
	}

	@Test
	void zeroBudgetRetainsDeferredForceAndFrozenEvidenceUntilWorkerReturns() throws Exception {
		AtomicReference<Runnable> delayedForce = new AtomicReference<>();
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			if (name.equals("lifecycle-force-http"))
				assertTrue(delayedForce.compareAndSet(null, task));
			else task.run();
		});
		DeadlineWaiter waiter = new DeadlineWaiter(() -> 0L);
		AdmissionFence admission = new AdmissionFence(false, waiter::signal);
		InternalTerminationGroup group = new InternalTerminationGroup(admission, waiter::signal, workers);
		group.commit();
		DirectParticipantPhaseGate gate = new DirectParticipantPhaseGate();
		assertTrue(gate.claimStart());
		assertNull(gate.completeStartCall());
		AtomicInteger forces = new AtomicInteger();
		InternalLifecycleCoordinator.Participant participant = new InternalLifecycleCoordinator.Participant() {
			@Override public InternalLifecycleComponentType kind() { return InternalLifecycleComponentType.HTTP; }
			@Override public AdmissionFence admissionFence() { return admission; }
			@Override public InternalTerminationGroup terminationGroup() { return group; }
			@Override public Set<InternalResidualActivityType> residualActivity() { return Set.of(); }
			@Override public void freezeForClassification() {
				gate.freezeForClassification(new ShutdownContext(ShutdownPhase.FORCED, () -> 0L, 0L));
			}
			@Override public InternalTransportRuntime runtime() {
				return new InternalTransportRuntime() {
					@Override public void start(StartupContext context) {}
					@Override public void shutdownGracefully(ShutdownContext context) { gate.requestPhase(context); }
					@Override public void shutdownForcibly(ShutdownContext context) {
						if (gate.requestPhase(context) != null) forces.incrementAndGet();
					}
				};
			}
		};
		InternalShutdownResult frozen = new InternalLifecycleCoordinator(() -> 0L, waiter,
				new TrackedLifecycleCallRunner(workers)).shutdown(List.of(participant), 0L, 0L);
		assertFalse(frozen.isComplete());
		Set<InternalResidualActivityType> residual = frozen.participantResult(
				InternalLifecycleComponentType.HTTP).orElseThrow().residualActivity();
		assertTrue(residual.contains(InternalResidualActivityType.LIFECYCLE_CALL));
		assertEquals(0, forces.get());
		assertTrue(workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL) > 0);
		Thread worker = new Thread(delayedForce.get(), "deferred-forced-delivery-test");
		worker.start();
		worker.join(2_000);
		assertFalse(worker.isAlive());
		assertEquals(1, forces.get());
		assertEquals(0, workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL));
		assertFalse(frozen.isComplete());
		assertEquals(residual, frozen.participantResult(InternalLifecycleComponentType.HTTP)
				.orElseThrow().residualActivity(), "Late cleanup cannot rewrite frozen evidence");
	}

	@Test
	void zeroAndNearZeroForcedBudgetsStillStopRealListenerAndStreamingWriter() throws Exception {
		for (Duration forced : List.of(Duration.ZERO, Duration.ofMillis(5))) {
			ProducerState state = new ProducerState();
			StreamResource resource = new StreamResource(state);
			DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").build();
			Soklet owner = Soklet.run(SokletConfig.withHttpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StreamResource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
					})
					.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
					.jvmShutdownHookEnabled(false)
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
							.startupCancelationTimeout(Duration.ofMillis(100))
							.gracefulShutdownTimeout(Duration.ofMillis(20)).forcedShutdownTimeout(forced).build()).build());
			EventLoop loop = server.getEventLoop().orElseThrow();
			TimeoutScheduler scheduler = server.getRequestHandlerTimeoutScheduler().orElseThrow();
			var executor = server.getRequestHandlerExecutorService().orElseThrow();
			try (Socket client = new Socket()) {
				client.connect(loop.getLocalAddress(), 2_000);
				client.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				client.getOutputStream().flush();
				assertTrue(state.entered.await(2, TimeUnit.SECONDS));
				owner.requestShutdown();
				ShutdownResult frozen = shutdownResult(owner);
				assertTrue(state.exited.await(2, TimeUnit.SECONDS), "Forced cleanup was dropped at " + forced);
				assertTrue(loop.joinUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)), "Listener remained alive");
				assertSame(frozen, shutdownResult(owner));
			} finally {
				state.release.countDown();
				try {
					owner.requestShutdown();
				} finally {
					try {
						loop.stop();
						assertTrue(loop.joinUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
					} finally {
						try {
							scheduler.shutdownNow();
							assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
						} finally {
							executor.shutdownNow();
							assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
						}
					}
				}
			}
		}
	}

	private static ShutdownResult shutdownResult(Soklet owner) throws InterruptedException {
		try { return owner.awaitShutdown(); }
		catch (SokletShutdownIncompleteException incomplete) { return incomplete.getShutdownResult(); }
	}

	private static final class ProducerState {
		private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
	}

	public static final class StreamResource {
		private final ProducerState state;
		private StreamResource(ProducerState state) { this.state = state; }
		@GET("/stream") public MarshaledResponse stream() {
			return MarshaledResponse.withStatusCode(200).stream(out -> {
				this.state.entered.countDown();
				try { this.state.release.await(); }
				finally { this.state.exited.countDown(); }
			}).build();
		}
	}
}
