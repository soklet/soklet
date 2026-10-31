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
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class HandlerTimeoutWorkerReuseTests {

	@Test
	void httpWorkerCannotRetireBeforeItsTimeoutInterruptIsDelivered() throws Exception {
		exerciseWorkerReuse(false);
	}

	@Test
	void sseWorkerCannotRetireBeforeItsTimeoutInterruptIsDelivered() throws Exception {
		exerciseWorkerReuse(true);
	}

	private void exerciseWorkerReuse(boolean sse) throws Exception {
		try (Fixture fixture = new Fixture(sse)) {
			fixture.owner.start();
			try (Socket first = fixture.request("a")) {
				assertTrue(fixture.resource.enteredA.await(3, TimeUnit.SECONDS));
				fixture.armInterrupt.set(true);
				if (sse)
					fixture.startSseTimeout();
				assertTrue(fixture.interruptClaimed.await(5, TimeUnit.SECONDS), "A's timeout must claim its worker");

				fixture.resource.releaseA.countDown();
				assertTrue(fixture.finishedA.await(3, TimeUnit.SECONDS), "A must finish application handling");
				try (Socket second = fixture.request("b")) {
					// Pause interrupt delivery exactly after the timeout has chosen A's
					// worker. A must wait at retirement, rather than letting B reuse it.
					awaitRetirement(fixture);
					assertEquals(1L, fixture.resource.enteredB.getCount(), "B cannot start while A's interrupt is in flight");
					fixture.releaseInterrupt.countDown();
					assertTrue(fixture.resource.enteredB.await(3, TimeUnit.SECONDS));
					fixture.resource.releaseB.countDown();
					assertTrue(fixture.finishedB.await(3, TimeUnit.SECONDS));
					assertSame(fixture.resource.workerA.get(), fixture.resource.workerB.get(), "Exercise actual pooled worker reuse");
					assertFalse(fixture.resource.interruptedB.get(), "A's timeout must never interrupt B");
					assertNull(fixture.timerFailure.get());
				}
			}
		}
	}

	private static void awaitRetirement(Fixture fixture) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadline) {
			assertEquals(1L, fixture.resource.enteredB.getCount(), "The worker retired before A's interrupt was delivered");
			if (fixture.resource.workerA.get().getState() == Thread.State.BLOCKED)
				return;
			Thread.sleep(5);
		}
		fail("A's worker did not wait for its claimed interrupt before retirement");
	}

	private static final class Fixture implements AutoCloseable {
		final int port = TestSupport.findFreePort();
		final Resource resource;
		final AtomicBoolean armInterrupt = new AtomicBoolean();
		final CountDownLatch interruptClaimed = new CountDownLatch(1);
		final CountDownLatch releaseInterrupt = new CountDownLatch(1);
		final CountDownLatch finishedA = new CountDownLatch(1);
		final CountDownLatch finishedB = new CountDownLatch(1);
		final AtomicReference<Throwable> timerFailure = new AtomicReference<>();
		final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(4), runnable -> new Thread(runnable, "timeout-reuse-worker") {
			@Override public void interrupt() {
				if (armInterrupt.compareAndSet(true, false)) {
					interruptClaimed.countDown();
					awaitIgnoringInterrupts(releaseInterrupt);
				}
				super.interrupt();
			}
		});
		final DefaultSseServer sseServer;
		final Soklet owner;
		Thread timeoutThread;

		Fixture(boolean sse) throws Exception {
			resource = sse ? new SseResource() : new HttpResource();
			SokletConfig.Builder builder;
			if (sse) {
				sseServer = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
						.requestHandlerExecutorServiceSupplier(() -> executor)
						.requestHandlerTimeout(Duration.ofSeconds(30)).verifyConnectionOnceEstablished(false).build();
				builder = SokletConfig.withSseServer(sseServer);
			} else {
				sseServer = null;
				builder = SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1")
						.requestHandlerExecutorServiceSupplier(() -> executor)
						.requestHandlerTimeout(Duration.ofSeconds(2)).build());
			}
			owner = Soklet.fromConfig(builder.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(resource.getClass())))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == resource.getClass() ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					})
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
							.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) {}
						@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
								MarshaledResponse response, Duration duration, List<Throwable> failures) {
							if (request.getRawPathAndQuery().endsWith("/a")) finishedA.countDown();
							if (request.getRawPathAndQuery().endsWith("/b")) finishedB.countDown();
						}
					}).build());
		}

		Socket request(String path) throws Exception {
			Socket socket = new Socket();
			socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
			socket.setSoTimeout(3_000);
			String prefix = sseServer == null ? "/http/" : "/sse/";
			socket.getOutputStream().write(("GET " + prefix + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			return socket;
		}

		void startSseTimeout() throws Exception {
			Field activeField = DefaultSseServer.class.getDeclaredField("activeHandshakes");
			activeField.setAccessible(true);
			Map<?, ?> active = (Map<?, ?>) activeField.get(sseServer);
			assertEquals(1, active.size());
			var admission = active.entrySet().iterator().next();
			Method contextMethod = admission.getValue().getClass().getDeclaredMethod("context");
			contextMethod.setAccessible(true);
			Object context = contextMethod.invoke(admission.getValue());
			Method timeoutMethod = DefaultSseServer.class.getDeclaredMethod("handleHandshakeTimeout", SocketChannel.class,
					context.getClass());
			timeoutMethod.setAccessible(true);
			timeoutThread = new Thread(() -> {
				try {
					timeoutMethod.invoke(sseServer, admission.getKey(), context);
				} catch (Throwable failure) {
					timerFailure.set(failure);
				}
			}, "controlled-sse-timeout");
			timeoutThread.start();
		}

		@Override public void close() throws Exception {
			armInterrupt.set(false);
			resource.releaseA.countDown();
			resource.releaseB.countDown();
			releaseInterrupt.countDown();
			if (timeoutThread != null) {
				timeoutThread.join(3_000);
				assertFalse(timeoutThread.isAlive());
			}
			owner.close();
			assertTrue(owner.getShutdownResult().orElseThrow().isComplete());
		}
	}

	public static class Resource {
		final CountDownLatch enteredA = new CountDownLatch(1);
		final CountDownLatch releaseA = new CountDownLatch(1);
		final CountDownLatch enteredB = new CountDownLatch(1);
		final CountDownLatch releaseB = new CountDownLatch(1);
		final AtomicReference<Thread> workerA = new AtomicReference<>();
		final AtomicReference<Thread> workerB = new AtomicReference<>();
		final AtomicBoolean interruptedB = new AtomicBoolean();

		final void first() throws Exception {
			workerA.set(Thread.currentThread());
			enteredA.countDown();
			assertTrue(releaseA.await(10, TimeUnit.SECONDS));
		}

		final void second() throws Exception {
			workerB.set(Thread.currentThread());
			interruptedB.set(Thread.currentThread().isInterrupted());
			enteredB.countDown();
			try {
				assertTrue(releaseB.await(5, TimeUnit.SECONDS));
			} catch (InterruptedException interrupted) {
				interruptedB.set(true);
			}
		}
	}

	public static final class HttpResource extends Resource {
		@GET("/http/a") public String httpA() throws Exception { first(); return "A"; }
		@GET("/http/b") public String httpB() throws Exception { second(); return "B"; }
	}

	public static final class SseResource extends Resource {
		@SseEventSource("/sse/a") public SseHandshakeResult sseA() throws Exception { first(); return SseHandshakeResult.Accepted.builder().build(); }
		@SseEventSource("/sse/b") public SseHandshakeResult sseB() throws Exception { second(); return SseHandshakeResult.Accepted.builder().build(); }
	}

	private static void awaitIgnoringInterrupts(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			while (true) {
				try {
					if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Interrupt delivery was not released");
					return;
				} catch (InterruptedException ignored) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}
}
