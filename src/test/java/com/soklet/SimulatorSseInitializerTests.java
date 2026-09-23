/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import com.soklet.annotation.SseEventSource;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SimulatorSseInitializerTests {
	@Test
	public void helper_thread_may_queue_during_initializer_but_handle_closes_at_return() {
		Fixture fixture = new Fixture();
		AtomicReference<SseUnicaster> initializerHandle = new AtomicReference<>();
		AtomicReference<Throwable> helperFailure = new AtomicReference<>();
		fixture.initializer = client -> {
			initializerHandle.set(client);
			Thread helper = new Thread(() -> {
				try { client.unicastEvent(event("helper")); }
				catch (Throwable failure) { helperFailure.set(failure); }
			}, "sse-catchup-helper");
			helper.start();
			helper.join();
		};
		SokletSimulator.run(config(fixture, Duration.ofSeconds(2)), simulator -> {
			SseRequestResult.HandshakeAccepted accepted = accepted(simulator);
			List<SseEvent> events = new ArrayList<>();
			accepted.registerEventConsumer(events::add);
			Assertions.assertNull(helperFailure.get());
			Assertions.assertEquals(List.of("helper"),
					events.stream().map(event -> event.getData().orElseThrow()).toList());
			Assertions.assertThrows(IllegalStateException.class,
					() -> initializerHandle.get().unicastEvent(event("late")));
			accepted.close();
		});
	}

	@Test
	public void close_without_consumers_releases_connection_and_preserves_handshake_metadata() {
		Fixture fixture = new Fixture();
		SokletSimulator.run(config(fixture, Duration.ofSeconds(2)), simulator -> {
			SseRequestResult.HandshakeAccepted accepted = accepted(simulator);
			SseBroadcaster broadcaster = simulator.getSseServer().orElseThrow().acquireBroadcaster(PATH).orElseThrow();
			Assertions.assertSame(fixture.request.get().getResourcePath(), fixture.client.get().getResourcePath());
			Assertions.assertEquals(1L, broadcaster.getClientCount());
			accepted.close();
			accepted.close();
			Assertions.assertEquals(0L, broadcaster.getClientCount());
			Assertions.assertEquals(200, accepted.getHttpRequestResult().getResponse().orElseThrow().getStatusCode());
			Assertions.assertThrows(IllegalStateException.class, () -> accepted.registerEventConsumer(ignored -> {}));
			Assertions.assertThrows(IllegalStateException.class, () -> accepted.registerCommentConsumer(ignored -> {}));
			Assertions.assertThrows(IllegalStateException.class, () -> fixture.client.get().unicastEvent(event("late")));
		});
	}

	@Test
	public void initializer_and_broadcast_payloads_are_ordered_and_released_after_close() {
		Fixture fixture = new Fixture();
		fixture.initializer = client -> {
			client.unicastEvent(event("initial"));
			client.unicastComment(SseComment.fromComment("initial-comment"));
		};
		SokletSimulator.run(config(fixture, Duration.ofSeconds(2)), simulator -> {
			SseRequestResult.HandshakeAccepted accepted = accepted(simulator);
			SseBroadcaster broadcaster = simulator.getSseServer().orElseThrow().acquireBroadcaster(PATH).orElseThrow();
			broadcaster.broadcastEvent(event("before-registration"));
			List<SseEvent> events = new ArrayList<>();
			List<SseComment> comments = new ArrayList<>();
			accepted.registerEventConsumer(events::add);
			accepted.registerCommentConsumer(comments::add);
			Assertions.assertEquals(List.of("initial", "before-registration"),
					events.stream().map(event -> event.getData().orElseThrow()).toList());
			Assertions.assertEquals(List.of("initial-comment"),
					comments.stream().map(comment -> comment.getComment().orElseThrow()).toList());
			Assertions.assertEquals(1L, broadcaster.getClientCount(), "An event and comment listener are one client");
			accepted.close();
			broadcaster.broadcastEvent(event("closed"));
			broadcaster.broadcastComment(SseComment.fromComment("closed"));
			Assertions.assertEquals(2, events.size());
			Assertions.assertEquals(1, comments.size());
		});
	}

	@Test
	public void buffered_delivery_preserves_unicast_and_broadcast_error_handlers() {
		Fixture fixture = new Fixture();
		fixture.initializer = client -> client.unicastEvent(event("unicast"));
		SokletSimulator.run(config(fixture, Duration.ofSeconds(2)), simulator -> {
			AtomicInteger unicastErrors = new AtomicInteger();
			AtomicInteger broadcastErrors = new AtomicInteger();
			simulator.onUnicastError(ignored -> unicastErrors.incrementAndGet());
			simulator.onBroadcastError(ignored -> broadcastErrors.incrementAndGet());
			SseRequestResult.HandshakeAccepted accepted = accepted(simulator);
			simulator.getSseServer().orElseThrow().acquireBroadcaster(PATH).orElseThrow()
					.broadcastEvent(event("broadcast"));
			accepted.registerEventConsumer(ignored -> { throw new IllegalArgumentException("consumer failed"); });
			Assertions.assertEquals(1, unicastErrors.get());
			Assertions.assertEquals(1, broadcastErrors.get());
		});
	}

	@Test
	public void checked_initializer_failure_exposes_no_connection() {
		Fixture fixture = new Fixture();
		IOException failure = new IOException("initializer failed");
		fixture.initializer = ignored -> { throw failure; };
		SokletSimulator.run(config(fixture, Duration.ofSeconds(2)), simulator -> {
			IllegalStateException wrapped = Assertions.assertThrows(IllegalStateException.class,
					() -> simulator.performSseRequest(request()));
			Assertions.assertSame(failure, wrapped.getCause());
			Assertions.assertEquals(0L, simulator.getSseServer().orElseThrow().acquireBroadcaster(PATH).orElseThrow().getClientCount());
		});
	}

	@Test
	public void source_settings_are_snapshotted_and_full_capacity_suppresses_initializer() {
		Fixture fixture = new Fixture();
		DefaultSseServer source = (DefaultSseServer) SseServer.withPort(0)
				.streamingLifecycleCapacity(1).connectionQueueCapacity(3).build();
		SokletConfig sourceConfig = SokletConfig.withSseServer(source)
				.responseMarshaler(ResponseMarshaler.builder().serviceUnavailableHandler((request, resourceMethod) ->
						MarshaledResponse.withStatusCode(503).headers(Map.of("X-Capacity", Set.of("full"))).build()).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Fixture.class)))
				.instanceProvider(provider(fixture)).build();
		SimulatorConfig imported = SimulatorConfig.fromSokletConfig(sourceConfig);
		Soklet.MockSseServer copied = imported.simulatedSseServer();
		Assertions.assertEquals(1, copied.streamingLifecycleCapacity);
		Assertions.assertEquals(3, copied.connectionQueueCapacity);
		SokletSimulator.run(imported, simulator -> {
			SseRequestResult.HandshakeAccepted first = accepted(simulator);
			SseRequestResult.RequestFailed rejected = Assertions.assertInstanceOf(SseRequestResult.RequestFailed.class,
					simulator.performSseRequest(request()));
			Assertions.assertEquals(503, rejected.getHttpRequestResult().getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(Set.of("full"), rejected.getHttpRequestResult().getMarshaledResponse().getHeaders().get("X-Capacity"));
			Assertions.assertTrue(rejected.getHttpRequestResult().getResourceMethod().isPresent());
			Assertions.assertTrue(rejected.getHttpRequestResult().getSseHandshakeResult().isEmpty());
			Assertions.assertEquals(1, fixture.entries.get());
			Assertions.assertFalse(source.isStarted());
			first.close();
		});
	}

	@Test
	public void caught_mixed_initializer_queue_overflow_still_terminates() {
		Fixture fixture = new Fixture();
		AtomicReference<IllegalStateException> overflow = new AtomicReference<>();
		fixture.initializer = client -> {
			client.unicastEvent(event("first"));
			client.unicastComment(SseComment.fromComment("second"));
			try { client.unicastEvent(event("overflow")); }
			catch (IllegalStateException failure) { overflow.set(failure); }
		};
		SseServer source = SseServer.withPort(0).connectionQueueCapacity(2).build();
		SokletConfig sourceConfig = SokletConfig.withSseServer(source)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Fixture.class)))
				.instanceProvider(provider(fixture)).build();
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(sourceConfig), simulator -> {
			Assertions.assertThrows(IllegalStateException.class, () -> simulator.performSseRequest(request()),
					"Catching overflow cannot reactivate or expose a terminal connection");
			Assertions.assertNotNull(overflow.get());
			Assertions.assertEquals(0L, simulator.getSseServer().orElseThrow().acquireBroadcaster(PATH).orElseThrow().getClientCount());
		});
	}

	@Test
	public void blocked_broadcast_consumer_does_not_block_close_but_remains_accounted() throws Exception {
		Fixture fixture = new Fixture();
		CountDownLatch consumerEntered = new CountDownLatch(1);
		CountDownLatch releaseConsumer = new CountDownLatch(1);
		AtomicReference<Thread> delivery = new AtomicReference<>();
		try {
			SokletSimulator.run(config(fixture, Duration.ofSeconds(2)), simulator -> {
				SseRequestResult.HandshakeAccepted accepted = accepted(simulator);
				accepted.registerEventConsumer(ignored -> { consumerEntered.countDown(); awaitUninterruptibly(releaseConsumer); });
				SseBroadcaster broadcaster = simulator.getSseServer().orElseThrow().acquireBroadcaster(PATH).orElseThrow();
				Thread thread = new Thread(() -> broadcaster.broadcastEvent(event("blocked")), "simulated-sse-consumer");
				delivery.set(thread); thread.start();
				await(consumerEntered);
				accepted.close();
				StreamLifecycleCoordinator coordinator = ((Soklet.DefaultSimulator) simulator).getSseLifecycleCoordinatorForTests().orElseThrow();
				Assertions.assertEquals(1, coordinator.snapshot().reservations());
				Assertions.assertTrue(coordinator.snapshot().retainedWork() > 0);
				releaseConsumer.countDown();
				join(thread);
				awaitCondition(() -> coordinator.snapshot().reservations() == 0);
			});
		} finally { releaseConsumer.countDown(); join(delivery.get()); }
	}

	private static final ResourcePath PATH = ResourcePath.fromPath("/owned-events");
	private static Request request() { return Request.withPath(HttpMethod.GET, "/owned-events").build(); }
	private static SseEvent event(String data) { return SseEvent.withData(data).build(); }
	private static SseRequestResult.HandshakeAccepted accepted(Simulator simulator) {
		return Assertions.assertInstanceOf(SseRequestResult.HandshakeAccepted.class, simulator.performSseRequest(request()));
	}
	private static SimulatorConfig config(Fixture fixture, Duration shutdownBudget) {
		return SimulatorConfig.builder().sseServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Fixture.class)))
				.instanceProvider(provider(fixture))
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(shutdownBudget)
						.forcedShutdownTimeout(shutdownBudget).build())
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.build();
	}
	private static InstanceProvider provider(Fixture fixture) {
		return new InstanceProvider() {
			@Override public <T> T provide(Class<T> type) {
				return type == Fixture.class ? type.cast(fixture) : InstanceProvider.defaultInstance().provide(type);
			}
		};
	}
	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled SSE step did not complete");
	}
	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try { latch.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}
	private static void awaitCondition(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		Assertions.assertTrue(condition.getAsBoolean(), "SSE physical retirement did not complete");
	}
	private static void join(Thread thread) throws InterruptedException {
		if (thread == null) return;
		thread.join(5000);
		Assertions.assertFalse(thread.isAlive(), "SSE application frame did not exit");
	}
	private static void forceAndAwait(StreamLifecycleCoordinator coordinator) throws InterruptedException {
		if (coordinator != null) {
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
		}
	}

	public static final class Fixture {
		private SseClientInitializer initializer = ignored -> {};
		private final AtomicReference<Request> request = new AtomicReference<>();
		private final AtomicReference<SseUnicaster> client = new AtomicReference<>();
		private final AtomicInteger entries = new AtomicInteger();
		@SseEventSource("/owned-events") public SseHandshakeResult events(Request request) {
			this.request.set(request);
			return SseHandshakeResult.Accepted.builder().clientInitializer(client -> {
				this.entries.incrementAndGet(); this.client.set(client); this.initializer.initialize(client);
			}).build();
		}
	}
}
