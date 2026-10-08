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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;
import static org.junit.jupiter.api.Assertions.*;

/** Wire responses, quiet client disconnects and independent SSE deadline phases. */
@Timeout(30)
@EnabledForJreRange(min = JRE.JAVA_21)
class SseReadDeadlineRuntimeTests {

	@TestFactory
	Stream<DynamicTest> headerDeadlineWinsRegardlessOfHandlerTimeoutOrdering() {
		return Stream.of(80, 250, 700).map(handlerMillis -> DynamicTest.dynamicTest(
				"partial headers / handler " + handlerMillis + "ms", () -> {
			try (Fixture fixture = new Fixture(250, handlerMillis); Socket socket = fixture.open()) {
				fixture.send(socket, "GET /events HTTP/1.1\r\nHost: localhost\r\n");
				assertStatus(readResponse(socket), 408);
				assertEquals(List.of(RequestReadFailureReason.REQUEST_READ_TIMEOUT), fixture.readFailures);
				assertEquals(List.of(ConnectionRejectionReason.REQUEST_READ_TIMEOUT), fixture.connectionFailures);
				assertEquals(1L, fixture.failureCount(MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT));
				assertEquals(0, fixture.resource.calls.get());
				fixture.assertNoInternalNoise();
			}
		}));
	}

	@TestFactory
	Stream<DynamicTest> idleReadDeadlineClosesWithoutFailureEvents() {
		return Stream.of(80, 250).map(handlerMillis -> DynamicTest.dynamicTest(
				"idle headers / handler " + handlerMillis + "ms", () -> {
			try (Fixture fixture = new Fixture(250, handlerMillis); Socket socket = fixture.open()) {
				assertEquals("", readResponse(socket));
				fixture.assertQuietReadClose();
			}
		}));
	}

	@TestFactory
	Stream<DynamicTest> eofBeforeCompleteHeadersIsAQuietClientDisconnect() {
		return Stream.of("", "GET /events HTTP/1.1\r\nX-Secret: eof-input-canary").map(prefix ->
				DynamicTest.dynamicTest(prefix.isEmpty() ? "zero-byte EOF" : "partial-header EOF", () -> {
			try (Fixture fixture = new Fixture(1000, 1000); Socket socket = fixture.open()) {
				fixture.send(socket, prefix);
				socket.shutdownOutput();
				assertEquals("", readResponse(socket), "A disconnected client must not receive a synthetic 500");
				fixture.assertQuietReadClose();
			}
		}));
	}

	@Test
	void completingHeadersAfterHandlerBudgetDoesNotLoseTheIndependentReadBudget() throws Exception {
		try (Fixture fixture = new Fixture(1000, 150); Socket socket = fixture.open()) {
			fixture.send(socket, "GET /events HTTP/1.1\r\nHost: localhost\r\n");
			assertTrue(fixture.readStarted.await(2, TimeUnit.SECONDS));
			Thread.sleep(350);
			fixture.send(socket, "Accept: text/event-stream\r\n\r\n");
			assertStatus(readResponse(socket), 200);
			assertEquals(1, fixture.resource.calls.get());
			fixture.assertNoInternalNoise();
		}
	}

	@Test
	void applicationHandlingStillTimesOutAndInterruptsItsOwnWorker() throws Exception {
		try (Fixture fixture = new Fixture(1000, 150); Socket socket = fixture.open()) {
			fixture.resource.block = true;
			fixture.sendComplete(socket);
			assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
			assertStatus(readResponse(socket), 503);
			assertTrue(fixture.resource.interrupted.await(2, TimeUnit.SECONDS));
			assertEquals(List.of(SseConnection.HandshakeFailureReason.HANDSHAKE_TIMEOUT), fixture.handshakeFailures);
			assertTrue(fixture.readFailures.isEmpty());
			fixture.assertNoInternalNoise();
		}
	}

	@Test
	void queuedHandshakeRetainsItsBoundAndNeverEntersAfterQueueExpiry() throws Exception {
		try (Fixture fixture = new Fixture(1000, 150)) {
			fixture.occupyWorker();
			try (Socket socket = fixture.open()) {
				await(() -> fixture.executor.getQueue().size() == 1, "The handshake must be queued");
				assertStatus(readResponse(socket), 503);
				assertEquals(0, fixture.resource.calls.get());
				assertEquals(List.of(RequestRejectionReason.REQUEST_HANDLER_QUEUE_FULL), fixture.requestRejections);
				fixture.releaseWorker.countDown();
				await(() -> fixture.executor.getActiveCount() == 0 && fixture.executor.getQueue().isEmpty(),
						"Expired queued work must retire without resource entry");
				assertEquals(0, fixture.resource.calls.get());
				fixture.assertNoInternalNoise();
			}
		}
	}

	@Test
	void queueWaitRemainsDeductedWhenApplicationHandlingResumes() throws Exception {
		try (Fixture fixture = new Fixture(1500, 1800)) {
			fixture.occupyWorker();
			fixture.resource.block = true;
			try (Socket socket = fixture.open()) {
				await(() -> fixture.executor.getQueue().size() == 1, "The handshake must be queued");
				Thread.sleep(1000);
				fixture.releaseWorker.countDown();
				assertTrue(fixture.readStarted.await(2, TimeUnit.SECONDS));
				Thread.sleep(400);
				fixture.sendComplete(socket);
				assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
				// A fresh 1800ms application budget would exceed this socket deadline;
				// the original budget has at most 800ms left after queue wait.
				socket.setSoTimeout(1200);
				assertStatus(readResponse(socket), 503);
				assertTrue(fixture.resource.interrupted.await(2, TimeUnit.SECONDS));
				assertEquals(List.of(SseConnection.HandshakeFailureReason.HANDSHAKE_TIMEOUT), fixture.handshakeFailures);
				assertTrue(fixture.requestRejections.isEmpty());
				fixture.assertNoInternalNoise();
			}
		}
	}

	@Test
	void staleQueueDeadlineCannotClaimHeadersOrResumedApplicationWork() throws Exception {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0)
				.requestHandlerTimeout(Duration.ofSeconds(5)).build();
		TimeoutScheduler scheduler = new TimeoutScheduler(runnable -> {
			Thread thread = new Thread(runnable, "sse-stale-deadline-test");
			thread.setDaemon(true);
			return thread;
		});
		Field schedulerField = DefaultSseServer.class.getDeclaredField("requestHandlerTimeoutScheduler");
		schedulerField.setAccessible(true);
		schedulerField.set(server, scheduler);
		Class<?> contextClass = Class.forName("com.soklet.DefaultSseServer$HandshakeContext");
		Constructor<?> constructor = contextClass.getDeclaredConstructor(InetSocketAddress.class);
		constructor.setAccessible(true);
		Object context = constructor.newInstance((InetSocketAddress) null);
		Field generation = contextClass.getDeclaredField("deadlineGeneration");
		generation.setAccessible(true);
		Field ownerField = contextClass.getDeclaredField("handshakeResponseOwner");
		ownerField.setAccessible(true);
		AtomicReference<?> owner = (AtomicReference<?>) ownerField.get(context);
		Field handlerThreadField = contextClass.getDeclaredField("handlerThreadRef");
		handlerThreadField.setAccessible(true);
		@SuppressWarnings("unchecked")
		AtomicReference<Thread> handlerThread = (AtomicReference<Thread>) handlerThreadField.get(context);
		handlerThread.set(Thread.currentThread());
		Method schedule = DefaultSseServer.class.getDeclaredMethod("scheduleHandshakeTimeout",
				SocketChannel.class, contextClass, boolean.class);
		schedule.setAccessible(true);
		Method pause = DefaultSseServer.class.getDeclaredMethod("pauseHandshakeTimeoutForHeaderRead",
				SocketChannel.class, contextClass);
		pause.setAccessible(true);
		Method expire = DefaultSseServer.class.getDeclaredMethod("handleHandshakeTimeout",
				SocketChannel.class, contextClass, long.class);
		expire.setAccessible(true);
		Method claim = DefaultSseServer.class.getDeclaredMethod("claimHandshakeResponseForHandler", contextClass);
		claim.setAccessible(true);
		try (SocketChannel channel = SocketChannel.open()) {
			schedule.invoke(server, channel, context, true);
			long queuedGeneration = generation.getLong(context);
			assertEquals(Boolean.TRUE, pause.invoke(server, channel, context));
			long readingGeneration = generation.getLong(context);
			expire.invoke(server, channel, context, queuedGeneration);
			assertEquals("UNCLAIMED", owner.get().toString());
			assertFalse(Thread.currentThread().isInterrupted());
			assertTrue(channel.isOpen());
			schedule.invoke(server, channel, context, false);
			expire.invoke(server, channel, context, queuedGeneration);
			expire.invoke(server, channel, context, readingGeneration);
			assertEquals("UNCLAIMED", owner.get().toString());
			assertSame(Thread.currentThread(), handlerThread.get());
			assertFalse(Thread.currentThread().isInterrupted());
			assertTrue(channel.isOpen());
			assertEquals(Boolean.TRUE, claim.invoke(server, context));
		} finally {
			scheduler.shutdownNow();
			Thread.interrupted();
			assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	@Test
	void completeMalformedHeadersStillUseTheParseFailurePath() throws Exception {
		try (Fixture fixture = new Fixture(1000, 150); Socket socket = fixture.open()) {
			fixture.send(socket, "GET /events HTTP/1.1\r\nHost: localhost\r\nBroken-Header\r\n\r\n");
			assertStatus(readResponse(socket), 400);
			assertEquals(List.of(RequestReadFailureReason.UNPARSEABLE_REQUEST), fixture.readFailures);
			assertEquals(1L, fixture.failureCount(MetricsCollector.TransportFailureReason.MALFORMED_REQUEST));
			assertEquals(0, fixture.resource.calls.get());
		}
	}

	private static final class Fixture implements AutoCloseable {
		final int port = findFreePort();
		final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(4), runnable -> {
			Thread thread = new Thread(runnable, "sse-read-deadline-test-handler");
			thread.setDaemon(true);
			return thread;
		});
		final CountDownLatch releaseWorker = new CountDownLatch(1);
		final CountDownLatch readStarted = new CountDownLatch(1);
		final Resource resource = new Resource();
		final List<LogEvent> logs = new CopyOnWriteArrayList<>();
		final List<RequestReadFailureReason> readFailures = new CopyOnWriteArrayList<>();
		final List<ConnectionRejectionReason> connectionFailures = new CopyOnWriteArrayList<>();
		final List<RequestRejectionReason> requestRejections = new CopyOnWriteArrayList<>();
		final List<SseConnection.HandshakeFailureReason> handshakeFailures = new CopyOnWriteArrayList<>();
		final DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
		final Soklet soklet;

		Fixture(int headerMillis, int handlerMillis) throws Exception {
			SseServer server = SseServer.withPort(this.port).host("127.0.0.1")
					.requestHeaderTimeout(Duration.ofMillis(headerMillis))
					.requestHandlerTimeout(Duration.ofMillis(handlerMillis))
					.requestHandlerExecutorServiceSupplier(() -> this.executor).build();
			this.soklet = Soklet.fromConfig(SokletConfig.withSseServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> instanceClass) {
							return instanceClass == Resource.class ? instanceClass.cast(resource)
									: InstanceProvider.defaultInstance().provide(instanceClass);
						}
					})
					.metricsCollector(this.metrics)
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
							.startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
						@Override public void willReadRequest(ServerType serverType, InetSocketAddress remoteAddress,
								String target) { readStarted.countDown(); }
						@Override public void didFailToReadRequest(ServerType serverType, InetSocketAddress remoteAddress,
								String target, RequestReadFailureReason reason, Throwable throwable) { readFailures.add(reason); }
						@Override public void didFailToAcceptConnection(ServerType serverType, InetSocketAddress remoteAddress,
								ConnectionRejectionReason reason, Throwable throwable) { connectionFailures.add(reason); }
						@Override public void didFailToAcceptRequest(ServerType serverType, InetSocketAddress remoteAddress,
								String target, RequestRejectionReason reason, Throwable throwable) { requestRejections.add(reason); }
						@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod resourceMethod,
								SseConnection.HandshakeFailureReason reason, Throwable throwable) { handshakeFailures.add(reason); }
					}).build());
			this.soklet.start();
		}

		Socket open() throws Exception {
			Socket socket = connectWithRetry("127.0.0.1", this.port, 2000);
			socket.setSoTimeout(2500);
			return socket;
		}
		void send(Socket socket, String bytes) throws Exception {
			socket.getOutputStream().write(bytes.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
		}
		void sendComplete(Socket socket) throws Exception {
			send(socket, "GET /events HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n");
		}
		void occupyWorker() throws Exception {
			CountDownLatch entered = new CountDownLatch(1);
			this.executor.submit(() -> {
				entered.countDown();
				try { this.releaseWorker.await(); }
				catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			});
			assertTrue(entered.await(2, TimeUnit.SECONDS));
		}
		long failureCount(MetricsCollector.TransportFailureReason reason) {
			return this.metrics.snapshot().orElseThrow().getTransportFailures()
					.getOrDefault(new MetricsCollector.TransportFailureKey(ServerType.SSE, reason), 0L);
		}
		void assertNoInternalNoise() {
			assertEquals(0L, failureCount(MetricsCollector.TransportFailureReason.READ_ERROR));
			assertEquals(0L, failureCount(MetricsCollector.TransportFailureReason.WRITE_ERROR));
			assertEquals(0L, failureCount(MetricsCollector.TransportFailureReason.TASK_ERROR));
			assertTrue(this.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.SSE_SERVER_INTERNAL_ERROR
					|| event.getLogEventType() == LogEventType.SSE_SERVER_UNPARSEABLE_REQUEST), this.logs.toString());
		}
		void assertQuietReadClose() {
			assertTrue(this.readFailures.isEmpty(), this.readFailures.toString());
			assertTrue(this.connectionFailures.isEmpty(), this.connectionFailures.toString());
			assertEquals(0L, failureCount(MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT));
			assertEquals(0, this.resource.calls.get());
			assertNoInternalNoise();
		}
		@Override public void close() throws Exception {
			this.releaseWorker.countDown();
			this.resource.release.countDown();
			try { this.soklet.close(); assertTrue(this.soklet.getShutdownResult().orElseThrow().isComplete()); }
			finally { this.executor.shutdownNow(); assertTrue(this.executor.awaitTermination(2, TimeUnit.SECONDS)); }
		}
	}

	public static final class Resource {
		final AtomicInteger calls = new AtomicInteger();
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch interrupted = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		volatile boolean block;
		@SseEventSource("/events") public SseHandshakeResult source() {
			this.calls.incrementAndGet();
			this.entered.countDown();
			if (this.block) {
				try { this.release.await(); }
				catch (InterruptedException exception) { this.interrupted.countDown(); }
			}
			return SseHandshakeResult.rejectWithResponse(Response.withStatusCode(200).body("ok").build());
		}
	}

	private static String readResponse(Socket socket) throws Exception {
		return new String(readAll(socket.getInputStream()), StandardCharsets.ISO_8859_1);
	}
	private static void assertStatus(String response, int status) {
		assertTrue(response.startsWith("HTTP/1.1 " + status), response);
	}
	private static void await(BooleanSupplier condition, String message) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(condition.getAsBoolean(), message);
	}
}
