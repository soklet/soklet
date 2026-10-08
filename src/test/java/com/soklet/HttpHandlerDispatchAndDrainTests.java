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

import com.soklet.annotation.GET;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP dispatch, deadline delivery and physical shutdown evidence. */
@Timeout(30)
class HttpHandlerDispatchAndDrainTests {

	@Test
	void directExecutorIsRejectedBeforeApplicationEntryAndCanRecover() throws Exception {
		SwitchableExecutor executor = new SwitchableExecutor();
		try (Fixture fixture = new Fixture(executor, Duration.ofSeconds(2), false);
				 Socket rejected = fixture.open("/health?secret=http-dispatch-canary")) {
			assertUnavailable(readResponse(rejected));
			assertEquals(0, fixture.resource.healthCalls.get());
			assertEquals(List.of(RequestRejectionReason.REQUEST_HANDLER_QUEUE_FULL), fixture.rejections);
			assertEquals(List.of("Request handler executor must dispatch asynchronously"), fixture.rejectionMessages,
					"The rejection exception must not render request-controlled input");
			executor.inline.set(false);
			try (Socket healthy = fixture.open("/health")) {
				assertTrue(readResponse(healthy).startsWith("HTTP/1.1 200 OK"));
			}
			assertEquals(1, fixture.resource.healthCalls.get());
			assertEquals(1, fixture.accepted.get());
		}
	}

	@Test
	void saturatedCallerRunsDoesNotEnterAnotherHandlerOnTheSelector() throws Exception {
		ThreadPoolExecutor executor = executor(new SynchronousQueue<>(), true);
		try (Fixture fixture = new Fixture(executor, Duration.ofSeconds(5), false);
				 Socket active = fixture.open("/block")) {
			assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
			try (Socket rejected = fixture.open("/queued")) {
				assertUnavailable(readResponse(rejected));
			}
			assertEquals(0, fixture.resource.queuedCalls.get());
			assertEquals(List.of(RequestRejectionReason.REQUEST_HANDLER_QUEUE_FULL), fixture.rejections);
			fixture.resource.release.countDown();
			assertTrue(readResponse(active).startsWith("HTTP/1.1 200 OK"));
			await(() -> executor.getActiveCount() == 0, "The first worker must be available again");
			try (Socket healthy = fixture.open("/health")) {
				assertTrue(readResponse(healthy).startsWith("HTTP/1.1 200 OK"));
			}
			assertFalse(fixture.resource.handlerWasSelector.get());
		}
	}

	@Test
	void ordinaryExecutorRejectionStillReturnsUnavailableAndRecovers() throws Exception {
		ThreadPoolExecutor executor = executor(new SynchronousQueue<>(), false);
		try (Fixture fixture = new Fixture(executor, Duration.ofSeconds(5), false);
				 Socket active = fixture.open("/block")) {
			assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
			try (Socket rejected = fixture.open("/queued")) {
				assertUnavailable(readResponse(rejected));
			}
			assertEquals(0, fixture.resource.queuedCalls.get());
			fixture.resource.release.countDown();
			assertTrue(readResponse(active).startsWith("HTTP/1.1 200 OK"));
			await(() -> executor.getActiveCount() == 0, "Rejected dispatch must not occupy a worker");
			try (Socket healthy = fixture.open("/health")) {
				assertTrue(readResponse(healthy).startsWith("HTTP/1.1 200 OK"));
			}
		}
	}

	@Test
	void selectorInterruptDoesNotLeakIntoFollowingRequests() throws Exception {
		try (Fixture fixture = new Fixture(executor(new ArrayBlockingQueue<>(2), false),
				Duration.ofSeconds(2), false)) {
			fixture.interruptFirstSelectorTurn.set(true);
			try (Socket first = fixture.open("/health")) {
				assertTrue(readResponse(first).startsWith("HTTP/1.1 200 OK"));
			}
			try (Socket second = fixture.open("/health")) {
				assertTrue(readResponse(second).startsWith("HTTP/1.1 200 OK"));
			}
			assertEquals(List.of(false, false), fixture.selectorInterrupts,
					"An interrupted selector must recover before the next connection dispatch");
		}
	}

	@Test
	void activeHandlerDeadlineRemainsEffectiveDuringGracefulDrain() throws Exception {
		try (Fixture fixture = new Fixture(executor(new ArrayBlockingQueue<>(2), false),
				Duration.ofMillis(700), false);
				 Socket active = fixture.open("/block")) {
			assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
			CompletableFuture<ShutdownResult> shutdown = fixture.beginDrain();
			assertTrue(fixture.resource.interrupted.await(2, TimeUnit.SECONDS),
					"The handler's own deadline must interrupt it before forced shutdown");
			assertUnavailable(readResponse(active));
			assertGraceful(shutdown.get(2, TimeUnit.SECONDS));
			assertTrue(fixture.scheduler.isTerminated());
		}
	}

	@Test
	void queuedDeadlineExpiresWithoutApplicationEntryWhileResidualHandlerDrains() throws Exception {
		ThreadPoolExecutor executor = executor(new ArrayBlockingQueue<>(2), false);
		try (Fixture fixture = new Fixture(executor, Duration.ofMillis(700), true);
				 Socket active = fixture.open("/block")) {
			assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
			try (Socket queued = fixture.open("/queued")) {
				await(() -> executor.getQueue().size() == 1, "The second request must be admitted and queued");
				CompletableFuture<ShutdownResult> shutdown = fixture.beginDrain();
				assertUnavailable(readResponse(queued));
				assertUnavailable(readResponse(active));
				assertTrue(fixture.resource.interrupted.await(2, TimeUnit.SECONDS));
				assertEquals(0, fixture.resource.queuedCalls.get());
				assertFalse(shutdown.isDone(), "A timeout response does not prove the blocked handler terminated");
				fixture.resource.release.countDown();
				assertGraceful(shutdown.get(2, TimeUnit.SECONDS));
				assertEquals(0, fixture.resource.queuedCalls.get(), "Expired queued work must remain inert when dequeued");
				assertTrue(fixture.scheduler.isTerminated());
			}
		}
	}

	@Test
	void successfulDrainCancelsItsDeadlineAndRetiresTheScheduler() throws Exception {
		try (Fixture fixture = new Fixture(executor(new ArrayBlockingQueue<>(2), false),
				Duration.ofSeconds(10), false);
				 Socket active = fixture.open("/block")) {
			assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
			CompletableFuture<ShutdownResult> shutdown = fixture.beginDrain();
			assertFalse(fixture.scheduler.isShutdown(), "Admitted work still owns its request deadline");
			fixture.resource.release.countDown();
			assertTrue(readResponse(active).startsWith("HTTP/1.1 200 OK"));
			assertGraceful(shutdown.get(2, TimeUnit.SECONDS));
			assertTrue(fixture.scheduler.isTerminated());
			assertEquals(1L, fixture.resource.interrupted.getCount());
		}
	}

	@Test
	void forcedShutdownStopsTheSchedulerButRetainsRealHandlerResidualEvidence() throws Exception {
		try (Fixture fixture = new Fixture(executor(new ArrayBlockingQueue<>(2), false),
				Duration.ofSeconds(10), true, Duration.ofMillis(100));
				 Socket active = fixture.open("/block")) {
			assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
			fixture.residualExpected = true;
			ShutdownResult result = fixture.beginDrain().get(2, TimeUnit.SECONDS);
			assertFalse(result.isComplete());
			assertEquals(ShutdownDisposition.INCOMPLETE, result.getShutdownDisposition());
			assertFalse(result.getShutdownComponentResult(ShutdownComponentType.HTTP)
					.orElseThrow().getResidualActivityEvidence().isEmpty());
			await(fixture.scheduler::isTerminated, "Forced shutdown must stop the request scheduler");
			assertTrue(fixture.scheduler.isShutdown());
			assertTrue(fixture.resource.interrupted.await(2, TimeUnit.SECONDS));
		}
	}

	private static ThreadPoolExecutor executor(BlockingQueue<Runnable> queue, boolean callerRuns) {
		return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue, runnable -> {
			Thread thread = new Thread(runnable, "http-dispatch-test-handler");
			thread.setDaemon(true);
			return thread;
		}, callerRuns ? new ThreadPoolExecutor.CallerRunsPolicy() : new ThreadPoolExecutor.AbortPolicy());
	}

	private static final class SwitchableExecutor extends ThreadPoolExecutor {
		final AtomicBoolean inline = new AtomicBoolean(true);
		SwitchableExecutor() {
			super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), runnable -> {
				Thread thread = new Thread(runnable, "http-dispatch-test-handler");
				thread.setDaemon(true);
				return thread;
			});
		}
		@Override public void execute(Runnable runnable) {
			if (this.inline.get()) runnable.run();
			else super.execute(runnable);
		}
	}

	private static final class Fixture implements AutoCloseable {
		final BlockingResource resource;
		final DefaultHttpServer server;
		final Soklet soklet;
		final TimeoutScheduler scheduler;
		final ThreadPoolExecutor executor;
		final int port;
		final List<RequestRejectionReason> rejections = new CopyOnWriteArrayList<>();
		final List<String> rejectionMessages = new CopyOnWriteArrayList<>();
		boolean residualExpected;
		final AtomicInteger accepted = new AtomicInteger();
		final AtomicBoolean interruptFirstSelectorTurn = new AtomicBoolean();
		final List<Boolean> selectorInterrupts = new CopyOnWriteArrayList<>();

		Fixture(ThreadPoolExecutor executor, Duration timeout, boolean ignoreInterrupts) throws Exception {
			this(executor, timeout, ignoreInterrupts, Duration.ofSeconds(4));
		}

		Fixture(ThreadPoolExecutor executor, Duration timeout, boolean ignoreInterrupts,
				Duration gracefulTimeout) throws Exception {
			this.executor = executor;
			this.port = findFreePort();
			this.resource = new BlockingResource(ignoreInterrupts);
			this.server = (DefaultHttpServer) HttpServer.withPort(this.port).host("127.0.0.1")
					.concurrency(1).requestHandlerTimeout(timeout)
					.requestHandlerExecutorServiceSupplier(() -> executor).build();
			this.soklet = Soklet.fromConfig(SokletConfig.withHttpServer(this.server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(BlockingResource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(@NonNull Class<T> instanceClass) {
							return instanceClass == BlockingResource.class ? instanceClass.cast(resource)
									: InstanceProvider.defaultInstance().provide(instanceClass);
						}
					})
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
							.startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(gracefulTimeout)
							.forcedShutdownTimeout(Duration.ofMillis(200)).build())
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent logEvent) {}
						@Override public void willAcceptRequest(ServerType serverType,
								InetSocketAddress remoteAddress, String requestTarget) {
							selectorInterrupts.add(Thread.currentThread().isInterrupted());
							if (interruptFirstSelectorTurn.compareAndSet(true, false))
								Thread.currentThread().interrupt();
						}
						@Override public void didAcceptRequest(ServerType serverType,
								InetSocketAddress remoteAddress, String requestTarget) { accepted.incrementAndGet(); }
						@Override public void didFailToAcceptRequest(ServerType serverType,
								InetSocketAddress remoteAddress, String requestTarget,
								RequestRejectionReason reason, Throwable throwable) {
							rejections.add(reason);
							rejectionMessages.add(throwable.getMessage());
						}
					}).build());
			this.soklet.start();
			this.scheduler = this.server.getRequestHandlerTimeoutScheduler().orElseThrow();
		}

		Socket open(String path) throws Exception {
			Socket socket = connectWithRetry("127.0.0.1", this.port, 2000);
			socket.setSoTimeout(2000);
			socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			return socket;
		}

		CompletableFuture<ShutdownResult> beginDrain() throws Exception {
			CompletableFuture<ShutdownResult> shutdown = this.soklet.shutdown().toCompletableFuture();
			await(() -> this.executor.isShutdown() && !this.server.getEventLoop().orElseThrow().isAccepting(),
					"Graceful drain must close listener and executor admission");
			return shutdown;
		}

		@Override public void close() throws Exception {
			this.resource.release.countDown();
			try { this.soklet.close(); }
			catch (SokletShutdownIncompleteException expected) {
				if (!this.residualExpected) throw expected;
				// The force test deliberately freezes real residual evidence before release.
				assertFalse(expected.getShutdownResult().isComplete());
			} finally {
				this.executor.shutdownNow();
				assertTrue(this.executor.awaitTermination(2, TimeUnit.SECONDS));
			}
		}
	}

	public static final class BlockingResource {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch interrupted = new CountDownLatch(1);
		final AtomicInteger healthCalls = new AtomicInteger();
		final AtomicInteger queuedCalls = new AtomicInteger();
		final AtomicBoolean handlerWasSelector = new AtomicBoolean();
		final boolean ignoreInterrupts;
		BlockingResource(boolean ignoreInterrupts) { this.ignoreInterrupts = ignoreInterrupts; }

		@GET("/block") public String block() {
			this.handlerWasSelector.set(Thread.currentThread().getName().contains("connection-event-loop"));
			this.entered.countDown();
			while (this.release.getCount() > 0) {
				try { this.release.await(); }
				catch (InterruptedException exception) {
					this.interrupted.countDown();
					if (!this.ignoreInterrupts) break;
				}
			}
			return "finished";
		}
		@GET("/queued") public String queued() { this.queuedCalls.incrementAndGet(); return "queued"; }
		@GET("/health") public String health() { this.healthCalls.incrementAndGet(); return "ok"; }
	}

	private static String readResponse(Socket socket) throws Exception {
		return new String(readAll(socket.getInputStream()), StandardCharsets.ISO_8859_1);
	}

	private static void assertUnavailable(String response) {
		assertTrue(response.startsWith("HTTP/1.1 503"), response);
		assertTrue(response.toLowerCase(java.util.Locale.ROOT).contains("connection: close"), response);
	}

	private static void assertGraceful(ShutdownResult result) {
		assertTrue(result.isComplete());
		assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
		ShutdownComponentResult http = result.getShutdownComponentResult(ShutdownComponentType.HTTP).orElseThrow();
		assertEquals(ShutdownComponentDisposition.GRACEFUL_TERMINATION, http.getShutdownComponentDisposition());
		assertTrue(http.getResidualActivityEvidence().isEmpty());
	}

	private static void await(BooleanSupplier condition, String message) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(condition.getAsBoolean(), message);
	}
}
