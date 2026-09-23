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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static java.util.concurrent.TimeUnit.SECONDS;

/** Qualifies the selected lifecycle defaults through the built-in SSE transport. */
@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(value = 60, unit = SECONDS)
public class SseLifecycleAdmissionTests {

	@Test
	@Timeout(value = 90, unit = SECONDS)
	public void defaultConnectionAdmissionSaturatesAt256AndRecoversAfterDisconnect() throws Exception {
		int capacity = 256;
		AtomicInteger initializerCalls = new AtomicInteger();
		List<Socket> sockets = new ArrayList<>();
		Fixture fixture = new Fixture(Duration.ofSeconds(2));
		try {
			fixture.initializer("saturation", ignored -> initializerCalls.incrementAndGet());
			fixture.start();
			fixture.assertDefaults();
			StreamLifecycleCoordinator coordinator = fixture.coordinator();
			for (int index = 0; index < capacity; index++) {
				Socket socket = fixture.request("saturation");
				sockets.add(socket);
				assertStatus(socket, 200);
			}
			eventually(() -> fixture.server.getGlobalConnections().size() == capacity);
			Assertions.assertEquals(capacity, initializerCalls.get());
			Assertions.assertEquals(capacity, coordinator.snapshot().reservations());
			try (Socket rejected = fixture.request("saturation")) {
				String headers = assertStatus(rejected, 503);
				Assertions.assertFalse(headers.contains("text/event-stream"));
			}
			Assertions.assertEquals(capacity, initializerCalls.get());
			for (Socket socket : sockets) {
				socket.setSoLinger(true, 0);
				socket.close();
			}
			fixture.server.acquireBroadcaster(ResourcePath.fromPath("/saturation/saturation")).orElseThrow()
					.broadcastEvent(SseEvent.withData("detect reset").build());
			eventually(() -> coordinator.snapshot().reservations() == 0, Duration.ofSeconds(10));
			Assertions.assertEquals(0, coordinator.snapshot().callbacks());
			try (Socket recovered = fixture.request("saturation")) {
				assertStatus(recovered, 200);
				eventually(() -> initializerCalls.get() == capacity + 1);
			}
		} finally {
			for (Socket socket : sockets)
				socket.close();
			fixture.close();
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final int port = findFreePort();
		private final DefaultSseServer server;
		private final Soklet soklet;
		private final Map<String, SseClientInitializer> initializers = new ConcurrentHashMap<>();
		private Fixture(Duration shutdownTimeout) throws Exception {
			// Lifecycle capacity is deliberately omitted: this test qualifies its default.
			this.server = (DefaultSseServer) SseServer.withPort(this.port).host("127.0.0.1")
					.connectionQueueCapacity(8).verifyConnectionOnceEstablished(false)
					.heartbeatInterval(Duration.ofSeconds(30)).build();
			Resource resource = new Resource(this.initializers);
			this.soklet = Soklet.fromConfig(SokletConfig.withHttpServer(HttpServer.withPort(findFreePort()).build())
					.sseServer(this.server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					}).lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
					}).lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(shutdownTimeout)
							.forcedShutdownTimeout(shutdownTimeout).build()).build());
		}
		private void initializer(String name, SseClientInitializer initializer) {
			this.initializers.put("/saturation/" + name, initializer);
		}
		private void start() { this.soklet.start(); }
		private void assertDefaults() {
			Assertions.assertEquals(256, this.server.getStreamingLifecycleCapacity());
		}
		private StreamLifecycleCoordinator coordinator() {
			return this.server.getStreamLifecycleCoordinatorForTests().orElseThrow();
		}
		private Socket request(String name) throws Exception {
			Socket socket = connectWithRetry("127.0.0.1", this.port, 2_000);
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(("GET /saturation/" + name
					+ " HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n")
					.getBytes(StandardCharsets.ISO_8859_1));
			return socket;
		}
		@Override public void close() throws Exception {
			if (this.soklet.getShutdownResult().map(result -> !result.isComplete()).orElse(false))
				Assertions.assertThrows(SokletShutdownIncompleteException.class, this.soklet::close);
			else
				this.soklet.close();
		}
	}

	public static final class Resource {
		private final Map<String, SseClientInitializer> initializers;
		private Resource(Map<String, SseClientInitializer> initializers) { this.initializers = initializers; }
		@SseEventSource("/saturation/{name}")
		public SseHandshakeResult source(Request request) {
			return SseHandshakeResult.Accepted.builder().clientInitializer(this.initializers.get(request.getPath())).build();
		}
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(3, SECONDS));
	}
	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try { latch.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}
	private static void eventually(BooleanSupplier condition) throws Exception {
		eventually(condition, Duration.ofSeconds(3));
	}
	private static void eventually(BooleanSupplier condition, Duration timeout) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
			Thread.sleep(5);
		Assertions.assertTrue(condition.getAsBoolean());
	}
	private static String assertStatus(Socket socket, int status) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while (bytes.size() < 16_384) {
			int value = socket.getInputStream().read();
			if (value < 0)
				break;
			bytes.write(value);
			if (bytes.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n"))
				break;
		}
		String headers = bytes.toString(StandardCharsets.ISO_8859_1);
		Assertions.assertTrue(headers.startsWith("HTTP/1.1 " + status + " "), headers);
		Assertions.assertTrue(headers.endsWith("\r\n\r\n"), headers);
		return headers;
	}
}
