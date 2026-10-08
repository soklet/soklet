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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SimulatorSseDeliveryContractTests {
	private static final ResourcePath PATH = ResourcePath.fromPath("/delivery-contract");

	@TestFactory
	Stream<DynamicTest> oneListenerCanReadMixedBroadcastsBeyondTheQueueLimit() {
		return listenerCases().map(comments -> DynamicTest.dynamicTest(label(comments), () -> {
			SokletSimulator.run(config(2, ignored -> {}), simulator -> {
				var accepted = accepted(simulator);
				List<String> deliveries = new ArrayList<>();
				register(accepted, comments, deliveries::add);
				var broadcaster = broadcaster(simulator);
				for (int i = 0; i < 200; i++) {
					send(broadcaster, !comments, "unobserved-" + i);
					send(broadcaster, comments, "observed-" + i);
				}
				assertEquals(200, deliveries.size());
				for (int i = 0; i < 200; i++) assertEquals("observed-" + i, deliveries.get(i));
				assertEquals(1, broadcaster.getClientCount());
				accepted.close();
				await(() -> broadcaster.getClientCount() == 0);
			});
		}));
	}

	@TestFactory
	Stream<DynamicTest> initialMixedCaptureSurvivesReadingAndLaterListenerRegistration() {
		return listenerCases().map(comments -> DynamicTest.dynamicTest(label(comments), () -> {
			SokletSimulator.run(config(4, client -> {
				unicast(client, comments, "initial-observed");
				unicast(client, !comments, "initial-unobserved");
			}), simulator -> {
				var accepted = accepted(simulator);
				var broadcaster = broadcaster(simulator);
				send(broadcaster, comments, "before-observed");
				send(broadcaster, !comments, "before-unobserved");
				List<String> observed = new ArrayList<>();
				List<String> other = new ArrayList<>();
				register(accepted, comments, observed::add);
				for (int i = 0; i < 100; i++) {
					send(broadcaster, !comments, "ignored-" + i);
					send(broadcaster, comments, "live-" + i);
				}
				register(accepted, !comments, other::add);
				assertEquals(List.of("initial-unobserved", "before-unobserved"), other);
				assertEquals(List.of("initial-observed", "before-observed"), observed.subList(0, 2));
				assertEquals(102, observed.size());
				send(broadcaster, !comments, "now-observed");
				assertEquals(List.of("initial-unobserved", "before-unobserved", "now-observed"), other);
				assertEquals(1, broadcaster.getClientCount());
				assertThrows(IllegalStateException.class, () -> register(accepted, comments, ignored -> {}));
			});
		}));
	}

	@TestFactory
	Stream<DynamicTest> readingStartsBeforeReentrantConsumerDelivery() {
		return listenerCases().map(comments -> DynamicTest.dynamicTest(label(comments), () -> {
			SokletSimulator.run(config(3, client -> {
				unicast(client, comments, "first");
				unicast(client, comments, "second");
				unicast(client, !comments, "initial-other");
			}), simulator -> {
				var accepted = accepted(simulator);
				var broadcaster = broadcaster(simulator);
				List<String> deliveries = new ArrayList<>();
				register(accepted, comments, value -> {
					deliveries.add(value);
					if (value.equals("first")) {
						for (int i = 0; i < 100; i++) send(broadcaster, !comments, "ignored-" + i);
						send(broadcaster, comments, "tail");
					}
				});
				assertEquals(List.of("first", "second", "tail"), deliveries);
				List<String> other = new ArrayList<>();
				register(accepted, !comments, other::add);
				assertEquals(List.of("initial-other"), other);
				assertEquals(1, broadcaster.getClientCount());
			});
		}));
	}

	@TestFactory
	Stream<DynamicTest> capturedPayloadsDoNotConsumeTheLiveDeliveryBudget() {
		return listenerCases().map(comments -> DynamicTest.dynamicTest(label(comments), () -> {
			CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			AtomicReference<Thread> worker = new AtomicReference<>();
			try {
				SokletSimulator.run(config(2, client -> {
					unicast(client, !comments, "captured-1");
					unicast(client, !comments, "captured-2");
				}), simulator -> {
					var accepted = accepted(simulator);
					var broadcaster = broadcaster(simulator);
					List<String> observed = new CopyOnWriteArrayList<>(), other = new ArrayList<>();
					register(accepted, comments, value -> {
						observed.add(value);
						if (value.equals("held")) { entered.countDown(); awaitUninterruptibly(release); }
					});
					Thread thread = new Thread(() -> send(broadcaster, comments, "held"), "sse-held-consumer");
					worker.set(thread); thread.start(); await(entered);
					send(broadcaster, comments, "queued-1");
					send(broadcaster, comments, "queued-2");
					for (int i = 0; i < 100; i++) send(broadcaster, !comments, "ignored-" + i);
					assertEquals(1, broadcaster.getClientCount());
					register(accepted, !comments, other::add);
					assertEquals(List.of("captured-1", "captured-2"), other);
					assertEquals(1, broadcaster.getClientCount());
					release.countDown(); join(thread);
					assertEquals(List.of("held", "queued-1", "queued-2"), observed);
					send(broadcaster, !comments, "live-other");
					assertEquals(List.of("captured-1", "captured-2", "live-other"), other);
				});
			} finally { release.countDown(); join(worker.get()); }
		}));
	}

	@TestFactory
	Stream<DynamicTest> aBlockedListenerStillHasFiniteLiveBackpressure() {
		return listenerCases().map(comments -> DynamicTest.dynamicTest(label(comments), () -> {
			CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			AtomicReference<Thread> worker = new AtomicReference<>();
			try {
				SokletSimulator.run(config(2, ignored -> {}), simulator -> {
					var accepted = accepted(simulator);
					var broadcaster = broadcaster(simulator);
					List<String> deliveries = new CopyOnWriteArrayList<>();
					register(accepted, comments, value -> {
						deliveries.add(value); entered.countDown(); awaitUninterruptibly(release);
					});
					Thread thread = new Thread(() -> send(broadcaster, comments, "held"), "sse-blocked-consumer");
					worker.set(thread); thread.start(); await(entered);
					send(broadcaster, comments, "queued-1");
					send(broadcaster, comments, "queued-2");
					for (int i = 0; i < 100; i++) send(broadcaster, !comments, "ignored-" + i);
					assertEquals(1, broadcaster.getClientCount());
					send(broadcaster, comments, "overflow");
					await(() -> broadcaster.getClientCount() == 0);
					assertThrows(IllegalStateException.class, () -> register(accepted, !comments, ignored -> {}));
					var coordinator = ((Soklet.DefaultSimulator) simulator).getSseLifecycleCoordinatorForTests().orElseThrow();
					assertEquals(1, coordinator.snapshot().reservations());
					assertTrue(coordinator.snapshot().retainedWork() > 0);
					release.countDown(); join(thread);
					await(() -> coordinator.snapshot().reservations() == 0);
					assertEquals(List.of("held"), deliveries);
				});
			} finally { release.countDown(); join(worker.get()); }
		}));
	}

	@Test
	void twoRegisteredTypesShareOneLiveDeliveryLimit() throws Exception {
		CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
		List<Thread> workers = new ArrayList<>();
		try {
			SokletSimulator.run(config(2, ignored -> {}), simulator -> {
				var accepted = accepted(simulator);
				var broadcaster = broadcaster(simulator);
				for (boolean comments : List.of(false, true)) {
					register(accepted, comments, value -> { entered.countDown(); awaitUninterruptibly(release); });
					Thread thread = new Thread(() -> send(broadcaster, comments, "held"), "sse-mixed-held-consumer");
					workers.add(thread); thread.start();
				}
				await(entered);
				send(broadcaster, false, "event-queued");
				send(broadcaster, true, "comment-queued");
				assertEquals(1, broadcaster.getClientCount());
				send(broadcaster, false, "overflow");
				await(() -> broadcaster.getClientCount() == 0);
				release.countDown();
				for (Thread worker : workers) join(worker);
			});
		} finally { release.countDown(); for (Thread worker : workers) join(worker); }
	}

	@Test
	void aClientWithoutListenersRetainsAFiniteCombinedCaptureLimit() throws Exception {
		SokletSimulator.run(config(2, ignored -> {}), simulator -> {
			var accepted = accepted(simulator);
			var broadcaster = broadcaster(simulator);
			assertEquals(1, broadcaster.getClientCount());
			send(broadcaster, false, "event"); send(broadcaster, true, "comment");
			assertEquals(1, broadcaster.getClientCount());
			send(broadcaster, false, "overflow");
			await(() -> broadcaster.getClientCount() == 0);
			assertThrows(IllegalStateException.class, () -> accepted.registerEventConsumer(ignored -> {}));
		});
	}

	private static Stream<Boolean> listenerCases() { return Stream.of(false, true); }
	private static String label(boolean comments) { return comments ? "comments" : "events"; }
	private static SseRequestResult.HandshakeAccepted accepted(Simulator simulator) {
		return assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
				simulator.performSseRequest(Request.fromPath(HttpMethod.GET, PATH.getPath())));
	}
	private static SseBroadcaster broadcaster(Simulator simulator) {
		return simulator.getSseServer().orElseThrow().acquireBroadcaster(PATH).orElseThrow();
	}
	private static void register(SseRequestResult.HandshakeAccepted accepted, boolean comments, Consumer<String> consumer) {
		if (comments) accepted.registerCommentConsumer(comment -> consumer.accept(comment.getComment().orElseThrow()));
		else accepted.registerEventConsumer(event -> consumer.accept(event.getData().orElseThrow()));
	}
	private static void send(SseBroadcaster broadcaster, boolean comments, String value) {
		if (comments) broadcaster.broadcastComment(SseComment.fromComment(value));
		else broadcaster.broadcastEvent(SseEvent.withData(value).build());
	}
	private static void unicast(SseUnicaster unicaster, boolean comments, String value) {
		if (comments) unicaster.unicastComment(SseComment.fromComment(value));
		else unicaster.unicastEvent(SseEvent.withData(value).build());
	}
	private static SimulatorConfig config(int capacity, SseClientInitializer initializer) {
		SokletConfig config = SokletConfig.withSseServer(SseServer.withPort(0).connectionQueueCapacity(capacity).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == Resource.class ? type.cast(new Resource(initializer)) : InstanceProvider.defaultInstance().provide(type);
					}
				})
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(300))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
		return SimulatorConfig.fromSokletConfig(config);
	}
	private static void await(CountDownLatch latch) throws InterruptedException {
		assertTrue(latch.await(3, TimeUnit.SECONDS), "Consumer did not enter");
	}
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
		assertTrue(condition.getAsBoolean());
	}
	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			for (;;) {
				try { latch.await(); return; } catch (InterruptedException ignored) { interrupted = true; }
			}
		} finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
	private static void join(Thread thread) throws InterruptedException {
		if (thread == null) return;
		thread.join(3000);
		assertFalse(thread.isAlive(), "Consumer work did not exit");
	}
	public static final class Resource {
		private final SseClientInitializer initializer;
		Resource(SseClientInitializer initializer) { this.initializer = initializer; }
		@SseEventSource("/delivery-contract")
		public SseHandshakeResult events() {
			return SseHandshakeResult.Accepted.builder().clientInitializer(initializer).build();
		}
	}
}
