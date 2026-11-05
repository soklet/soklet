/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Interrupt delivery is separately retained physical work, never an owner call. */
@Timeout(60)
class McpRegistrationInterruptDeliveryTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(McpProtocolVersion.V2026_07_28);
	private static final LifecyclePolicy POLICY = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofMillis(200))
			.gracefulShutdownTimeout(Duration.ofMillis(200)).forcedShutdownTimeout(Duration.ofMillis(200)).build();

	@Test
	void simulatorPublishesIncompleteWithoutWaitingForInterruptibleChannelClose() throws Exception {
		try (SimulationFixture fixture = new SimulationFixture(true)) {
			fixture.start();
			assertTrue(fixture.channel.closeEntered.await(5, TimeUnit.SECONDS));
			ShutdownResult result = fixture.result.get(2, TimeUnit.SECONDS);
			assertFalse(result.isComplete());
			assertEquals(ShutdownDisposition.INCOMPLETE, result.getShutdownDisposition());
			var residual = result.getShutdownComponentResult(ShutdownComponentType.MCP)
					.orElseThrow().getResidualActivityEvidence().orElseThrow().getResidualActivityTypes();
			assertTrue(residual.contains(ResidualActivityType.EXECUTOR_TASK));
			assertEquals(1, fixture.closes.get());
			assertTrue(fixture.channel.callbackThread.get().isDaemon());
			assertNotSame(fixture.owner, fixture.channel.callbackThread.get());
			assertTrue(fixture.callbackMarked.get() != null && fixture.callbackMarked.get());
			fixture.releaseAndJoin();
			assertFalse(result.isComplete(), "Late physical completion cannot mutate frozen evidence");
		}
	}

	@Test
	void cooperativeInterruptionCompletesAndInstallsLifecycleSelfWaitContext() throws Exception {
		try (SimulationFixture fixture = new SimulationFixture(false)) {
			fixture.start();
			ShutdownResult result = fixture.result.get(5, TimeUnit.SECONDS);
			assertTrue(result.isComplete());
			assertEquals(ShutdownDisposition.FORCED, result.getShutdownDisposition());
			assertEquals(1, fixture.closes.get());
			assertTrue(fixture.callbackMarked.get() != null && fixture.callbackMarked.get());
		}
	}

	@Test
	void cancellationBeforeBindingElectsOnlyOneDeliveryAndLateCancellationIsInert() throws Exception {
		AtomicInteger interrupts = new AtomicInteger();
		CountDownLatch delivered = new CountDownLatch(1);
		AtomicInteger submissions = new AtomicInteger();
		Attempt attempt = new Attempt(Runnable::run, action -> {
			submissions.incrementAndGet();
			return new Thread(action);
		});
		Thread target = new Thread() {
			@Override public void interrupt() { interrupts.incrementAndGet(); delivered.countDown(); }
		};
		attempt.call("cancel");
		attempt.call("cancel");
		assertEquals(0, submissions.get());
		attempt.bind(target);
		assertTrue(delivered.await(5, TimeUnit.SECONDS));
		for (int i = 0; i < 10; ++i) attempt.call("cancel");
		assertEquals(1, submissions.get());
		assertEquals(1, interrupts.get());
		assertFalse(attempt.completed());
		attempt.call("retireWorker");
		attempt.call("complete");
		assertTrue(attempt.await());
		attempt.call("cancel");
		assertEquals(1, interrupts.get());
		assertThrows(IllegalStateException.class, () -> attempt.bind(new Thread()));

		Attempt neverStarted = new Attempt(Runnable::run, action -> {
			fail("Completed startup-failure evidence must not create interrupt delivery");
			return null;
		});
		neverStarted.call("complete");
		neverStarted.call("cancel");
		neverStarted.bind(new Thread());
		assertTrue(neverStarted.completed());
	}

	@Test
	void queuedDeliverySkipsRetiredTargetButRemainsPendingUntilItsEnvelopeExits() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger interrupts = new AtomicInteger();
		AtomicReference<Thread> delivery = new AtomicReference<>();
		Attempt attempt = new Attempt(Runnable::run, action -> {
			Thread thread = new Thread(() -> { awaitRelease(release); action.run(); });
			delivery.set(thread);
			return thread;
		});
		try {
			attempt.bind(new Thread() { @Override public void interrupt() { interrupts.incrementAndGet(); } });
			attempt.call("cancel");
			attempt.call("retireWorker");
			attempt.call("complete");
			assertFalse(attempt.completed(), "Queued delivery is physical work even after close exits");
		} finally {
			release.countDown();
			join(delivery.get());
		}
		assertTrue(attempt.await());
		assertEquals(0, interrupts.get(), "A retired execution must never receive a stale interrupt");
	}

	@Test
	void inFlightDeliveryRetainsCompletionAndFencesWorkerRetirement() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger interrupts = new AtomicInteger();
		AtomicReference<Thread> delivery = new AtomicReference<>();
		Attempt attempt = new Attempt(Runnable::run, action -> {
			Thread thread = new Thread(action); delivery.set(thread); return thread;
		});
		Thread target = new Thread() {
			@Override public void interrupt() { interrupts.incrementAndGet(); entered.countDown(); awaitRelease(release); }
		};
		CountDownLatch retirementEntered = new CountDownLatch(1);
		FutureTask<Void> retired = new FutureTask<>(() -> { retirementEntered.countDown(); attempt.call("retireWorker"); return null; });
		Thread retirement = new Thread(retired);
		try {
			attempt.bind(target);
			attempt.call("cancel"); // Must return without waiting for target.interrupt().
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			retirement.start();
			attempt.call("complete");
			assertFalse(attempt.completed(), "Close completion cannot erase physical interrupt delivery");
			assertTrue(retirementEntered.await(5, TimeUnit.SECONDS));
			assertThrows(TimeoutException.class, () -> retired.get(50, TimeUnit.MILLISECONDS),
					"The exact worker envelope must remain fenced during delivery");
			for (int i = 0; i < 10; ++i) attempt.call("cancel");
			assertEquals(1, interrupts.get());
		} finally {
			release.countDown();
			join(delivery.get());
			join(retirement);
		}
		assertTrue(attempt.await());
		retired.get(5, TimeUnit.SECONDS);
	}

	@Test
	void rejectedDeliveryHasNoInlineFallbackAndDoesNotStrandUnusedProof() throws Exception {
		for (int failureMode = 0; failureMode < 3; ++failureMode) {
			int mode = failureMode;
			AtomicInteger interrupts = new AtomicInteger(), submissions = new AtomicInteger();
			RejectedExecutionException rejection = new RejectedExecutionException("test launch rejection");
			Attempt attempt = new Attempt(Runnable::run, action -> {
				submissions.incrementAndGet();
				if (mode == 1) return null;
				if (mode == 2) return new Thread(action) { @Override public synchronized void start() { throw rejection; } };
				throw rejection;
			});
			attempt.bind(new Thread() { @Override public void interrupt() { interrupts.incrementAndGet(); } });
			attempt.call("cancel");
			attempt.call("cancel");
			assertEquals(1, submissions.get());
			assertEquals(0, interrupts.get());
			assertFalse(attempt.completed());
			if (mode == 1) assertInstanceOf(NullPointerException.class, attempt.call("interruptFailure"));
			else assertSame(rejection, attempt.call("interruptFailure"));
			attempt.call("retireWorker");
			attempt.call("complete");
			assertTrue(attempt.await());
		}
	}

	@Test
	void throwingInterruptRetiresDeliveryAndPreservesExactFailure() throws Exception {
		AssertionError failure = new AssertionError("interrupt failure");
		AtomicReference<Thread> delivery = new AtomicReference<>();
		AtomicInteger enters = new AtomicInteger(), exits = new AtomicInteger();
		Attempt attempt = new Attempt(action -> {
			enters.incrementAndGet(); try { action.run(); } finally { exits.incrementAndGet(); }
		}, action -> { Thread thread = new Thread(action); delivery.set(thread); return thread; });
		attempt.bind(new Thread() { @Override public void interrupt() { throw failure; } });
		attempt.call("cancel");
		join(delivery.get());
		assertSame(failure, attempt.call("interruptFailure"));
		assertEquals(1, enters.get());
		assertEquals(1, exits.get());
		assertFalse(attempt.completed());
		attempt.call("retireWorker");
		attempt.call("complete");
		assertTrue(attempt.await());
	}

	private static final class Attempt {
		private final Class<?> type;
		private final Object value;
		Attempt(Consumer<Runnable> execution, ThreadFactory factory) throws Exception {
			type = Class.forName("com.soklet.internal.mcp.protocol.McpHttpServerRuntime$SubscriptionRegistrationCloseAttempt");
			Constructor<?> constructor = type.getDeclaredConstructor(Consumer.class, ThreadFactory.class);
			constructor.setAccessible(true);
			value = constructor.newInstance(execution, factory);
		}
		Object call(String name) throws Exception { return invoke(name, new Class<?>[0]); }
		void bind(Thread worker) throws Exception { invoke("bindWorker", new Class<?>[]{Thread.class}, worker); }
		boolean completed() throws Exception { return (boolean) call("completed"); }
		boolean await() throws Exception { return (boolean) invoke("await", new Class<?>[]{long.class}, TimeUnit.SECONDS.toNanos(5)); }
		Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
			Method method = type.getDeclaredMethod(name, types); method.setAccessible(true);
			try { return method.invoke(value, args); }
			catch (InvocationTargetException exception) {
				if (exception.getCause() instanceof Exception cause) throw cause;
				throw (Error) exception.getCause();
			}
		}
	}

	private static final class SimulationFixture implements AutoCloseable {
		final AtomicInteger closes = new AtomicInteger();
		final AtomicReference<Boolean> callbackMarked = new AtomicReference<>();
		final AtomicReference<McpServerRuntimeBridge> bridge = new AtomicReference<>();
		final AtomicReference<Object> ownerToken = new AtomicReference<>();
		final BlockingChannel channel;
		final FutureTask<ShutdownResult> result;
		final Thread owner;
		SimulationFixture(boolean blockClose) throws Exception {
			channel = new BlockingChannel(blockClose, () -> callbackMarked.set(bridge.get().lifecycleWaitWouldSelfJoin()
					&& LifecycleExecutionContext.isMarked(ownerToken.get())));
			McpSubscriptionEventPublisher publisher = new McpSubscriptionEventPublisher() {
				@Override public McpSubscriptionEventRegistration subscribe(McpSubscriptionEventListener listener) {
					return () -> { closes.incrementAndGet(); try { channel.read(ByteBuffer.allocate(1)); } catch (IOException ignored) {} };
				}
				@Override public void publish(McpSubscriptionEvent event) {}
			};
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("interrupt-delivery", "1").build(), VERSIONS)
					.subscriptionProtocolVersions(VERSIONS).subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
							publisher, Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED)).build()).build();
			McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.subscriptionAuthorizer(McpSubscriptionAuthorizer.denyAllInstance()).build();
			SokletConfig config = SokletConfig.withMcpServer(server).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.lifecycleObserver(new LifecycleObserver() {}).lifecyclePolicy(POLICY).build();
			result = new FutureTask<>(() -> {
				try { return SokletSimulator.run(config, simulator -> {
					Field serverField = simulator.getClass().getDeclaredField("mcpServer"); serverField.setAccessible(true);
					Object activeServer = serverField.get(simulator);
					Field bridgeField = activeServer.getClass().getDeclaredField("runtimeBridge"); bridgeField.setAccessible(true);
					bridge.set((McpServerRuntimeBridge) bridgeField.get(activeServer));
					Field tokenField = simulator.getClass().getDeclaredField("executionOwnerToken"); tokenField.setAccessible(true);
					ownerToken.set(tokenField.get(simulator));
				}); }
				catch (SokletShutdownIncompleteException incomplete) { return incomplete.getShutdownResult(); }
			});
			owner = new Thread(result, "registration-interrupt-owner");
		}
		void start() { owner.start(); }
		void releaseAndJoin() throws Exception {
			channel.release.countDown();
			join(owner);
			join(channel.readThread.get());
			join(channel.callbackThread.get());
		}
		@Override public void close() throws Exception { releaseAndJoin(); }
	}

	private static final class BlockingChannel extends AbstractInterruptibleChannel implements ReadableByteChannel {
		final CountDownLatch closeEntered = new CountDownLatch(1), release = new CountDownLatch(1);
		final AtomicReference<Thread> readThread = new AtomicReference<>(), callbackThread = new AtomicReference<>();
		final boolean blocked;
		final Runnable callback;
		BlockingChannel(boolean blocked, Runnable callback) { this.blocked = blocked; this.callback = callback; }
		@Override public int read(ByteBuffer target) throws IOException {
			readThread.set(Thread.currentThread());
			begin();
			try { awaitRelease(release); return -1; }
			finally { end(false); }
		}
		@Override protected void implCloseChannel() {
			callbackThread.set(Thread.currentThread());
			callback.run();
			closeEntered.countDown();
			if (blocked) awaitRelease(release);
			else release.countDown();
		}
	}

	private static void awaitRelease(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			while (latch.getCount() != 0L) {
				try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture release timed out"); }
				catch (InterruptedException ignored) { interrupted = true; }
			}
		} finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
	private static void join(Thread thread) throws InterruptedException {
		if (thread == null) return;
		thread.join(5000);
		assertFalse(thread.isAlive(), "Fixture worker did not terminate: " + thread.getName());
	}
}
