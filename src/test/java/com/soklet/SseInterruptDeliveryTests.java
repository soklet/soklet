/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
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
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class SseInterruptDeliveryTests {
	@Test
	void blockingInterruptibleCloseReturnsIncompleteWithoutRetiringPhysicalWork() throws Exception {
		verify(true);
	}

	@Test
	void ordinaryInterruptibleCloseCompletesShutdown() throws Exception {
		verify(false);
	}

	@Test
	void interruptTriggeredResourceCloseCarriesTheOwnerSelfWaitGuard() throws Exception {
		Soklet owner = Soklet.fromConfig(SokletConfig.withHttpServer(new Soklet.MockHttpServer()).build());
		Object token = owner.getDirectLifecycle().executionOwnerToken();
		AtomicBoolean marked = new AtomicBoolean();
		AtomicReference<Throwable> selfWait = new AtomicReference<>();
		CountDownLatch callbackReturned = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = StreamLifecycleCoordinator.withCallbackExecution(1, 1,
				Duration.ofSeconds(5), ignored -> {}, action -> {
					try (LifecycleExecutionContext.Scope ignored = LifecycleExecutionContext.enter(token)) { action.run(); }
				});
		try (BlockingChannel channel = new BlockingChannel(false, () -> {
			marked.set(LifecycleExecutionContext.isMarked(token));
			try { owner.awaitShutdown(); }
			catch (Throwable failure) { selfWait.set(failure); }
			finally { callbackReturned.countDown(); }
		})) {
			var reservation = coordinator.tryReserve(); assertNotNull(reservation);
			reservation.bindTermination((reason, cause) -> reservation.complete());
			Thread producer = new Thread(() -> reservation.executeInline(() -> {
				try { channel.read(); } catch (IOException expected) { }
			}), "sse-owner-context-initializer");
			producer.setDaemon(true);
			Thread cancel = new Thread(() -> reservation.cancel(StreamTerminationReason.SERVER_STOPPING, null), "sse-owner-context-cancel");
			cancel.setDaemon(true);
			try {
				producer.start(); assertTrue(channel.readEntered.await(5, TimeUnit.SECONDS));
				cancel.start();
				assertTrue(callbackReturned.await(5, TimeUnit.SECONDS));
				assertTrue(marked.get());
				assertInstanceOf(IllegalStateException.class, selfWait.get());
				assertTrue(selfWait.get().getMessage().contains("tracked lifecycle execution"));
			} finally {
				channel.allowClose.countDown(); owner.close(); producer.join(5_000); cancel.join(5_000);
				assertFalse(producer.isAlive()); assertFalse(cancel.isAlive());
				coordinator.force(); assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
			}
		}
	}

	private static void verify(boolean blockClose) throws Exception {
		try (BlockingChannel channel = new BlockingChannel(blockClose)) {
			AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
			AtomicReference<ShutdownResult> result = new AtomicReference<>();
			AtomicReference<Thread> producer = new AtomicReference<>();
			AtomicReference<Throwable> requestFailure = new AtomicReference<>();
			AtomicReference<StreamLifecycleCoordinator> coordinator = new AtomicReference<>();
			CountDownLatch ownerReturned = new CountDownLatch(1);
			Resource resource = new Resource(channel);
			SimulatorConfig config = SimulatorConfig.builder().sseServer()
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
					})
					.lifecycleObserver(new LifecycleObserver() {})
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.gracefulShutdownTimeout(Duration.ofMillis(200))
							.forcedShutdownTimeout(Duration.ofMillis(200)).build()).build();
			Thread owner = new Thread(() -> {
				try {
					result.set(SokletSimulator.run(config, simulator -> {
						Thread request = new Thread(() -> {
							try { simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/interrupt-close")); }
							catch (Throwable failure) { requestFailure.set(failure); }
						}, "sse-interruptible-initializer");
						request.setDaemon(true);
						producer.set(request);
						request.start();
						assertTrue(channel.readEntered.await(5, TimeUnit.SECONDS));
						coordinator.set(((Soklet.DefaultSimulator) simulator).getSseLifecycleCoordinatorForTests().orElseThrow());
					}));
				} catch (Throwable failure) { ownerFailure.set(failure); }
				finally { ownerReturned.countDown(); }
			}, "sse-interruptible-owner");
			owner.setDaemon(true);
			try {
				owner.start();
				assertTrue(channel.closeEntered.await(5, TimeUnit.SECONDS));
				assertTrue(ownerReturned.await(3, TimeUnit.SECONDS), "A channel-close callback must not hold the shutdown owner");
				if (blockClose) {
					SokletShutdownIncompleteException failure = assertInstanceOf(SokletShutdownIncompleteException.class, ownerFailure.get());
					assertFalse(failure.getShutdownResult().isComplete());
					assertEquals(ShutdownDisposition.INCOMPLETE, failure.getShutdownResult().getShutdownDisposition());
					assertTrue(coordinator.get().snapshot().retainedWork() > 0, "Interrupt delivery remains physically retained");
					assertTrue(coordinator.get().snapshot().runningProducers() > 0, "The producer cannot retire through an in-flight interrupt");
					assertFalse(coordinator.get().isTerminated());
					assertTrue(producer.get().isAlive());
				} else {
					assertNull(ownerFailure.get());
					assertTrue(result.get().isComplete());
				}
			} finally {
				channel.allowClose.countDown();
				owner.join(5_000);
				if (producer.get() != null) producer.get().join(5_000);
				assertFalse(owner.isAlive());
				assertTrue(producer.get() == null || !producer.get().isAlive());
				if (requestFailure.get() != null) assertInstanceOf(IllegalStateException.class, requestFailure.get());
				if (coordinator.get() != null)
					assertTrue(coordinator.get().awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
			}
		}
	}

	public static final class Resource {
		private final BlockingChannel channel;
		Resource(BlockingChannel channel) { this.channel = channel; }
		@SseEventSource("/interrupt-close") public SseHandshakeResult events() {
			return SseHandshakeResult.Accepted.builder().clientInitializer(client -> channel.read()).build();
		}
	}

	private static final class BlockingChannel extends AbstractInterruptibleChannel {
		final CountDownLatch readEntered = new CountDownLatch(1);
		final CountDownLatch closeEntered = new CountDownLatch(1);
		final CountDownLatch allowClose;
		final Runnable onClose;
		BlockingChannel(boolean blocked) { this(blocked, () -> {}); }
		BlockingChannel(boolean blocked, Runnable onClose) { this.allowClose = new CountDownLatch(blocked ? 1 : 0); this.onClose = onClose; }
		void read() throws IOException {
			begin();
			readEntered.countDown();
			try {
				while (isOpen()) {
					try { Thread.sleep(60_000); }
					catch (InterruptedException expected) { break; }
				}
			} finally { end(false); }
		}
		@Override protected void implCloseChannel() {
			closeEntered.countDown();
			onClose.run();
			boolean interrupted = false;
			for (;;) {
				try { allowClose.await(); break; }
				catch (InterruptedException ignored) { interrupted = true; }
			}
			if (interrupted) Thread.currentThread().interrupt();
		}
	}
}
