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

package com.soklet.internal.mcp.transport;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.soklet.internal.microhttp.EventLoop;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class McpTransportRuntimeSmokeTests {
	@Test
	@Timeout(60)
	void timerAndTransportLoopsDoNotInvokeInheritanceAndKeepTheContextLoader(@TempDir Path resources) throws Exception {
		Files.writeString(resources.resolve("transport-helper-context.txt"), "transport-resource", StandardCharsets.UTF_8);
		AtomicInteger inheritanceCalls = new AtomicInteger();
		InheritableThreadLocal<String> local = new InheritableThreadLocal<>() {
			@Override protected String childValue(String value) {
				inheritanceCalls.incrementAndGet();
				throw new IllegalStateException("Transport helpers must not invoke inheritance");
			}
		};
		Thread caller = Thread.currentThread();
		ClassLoader priorLoader = caller.getContextClassLoader();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch timerRan = new CountDownLatch(1);
		try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},
				ClassLoader.getPlatformClassLoader())) {
			caller.setContextClassLoader(loader);
			local.set("transport-parent-value");
			McpMonotonicClock clock = () -> {
				if (Thread.currentThread().getName().equals("soklet-mcp-timer")) {
					try {
						Assertions.assertNull(local.get());
						Assertions.assertSame(loader, Thread.currentThread().getContextClassLoader());
						try (InputStream resource = Thread.currentThread().getContextClassLoader()
								.getResourceAsStream("transport-helper-context.txt")) {
							Assertions.assertNotNull(resource);
							Assertions.assertEquals("transport-resource", new String(resource.readAllBytes(), StandardCharsets.UTF_8));
						}
					} catch (Throwable unexpected) { failure.compareAndSet(null, unexpected); }
					finally { timerRan.countDown(); }
				}
				return System.nanoTime();
			};
			McpTransportConfiguration configuration = new McpTransportConfiguration("127.0.0.1", 0,
					1, 4, 1, 1, 2, 1_024, 1_024,
					Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(250), McpThreadStrategy.PLATFORM);
			McpTransportRuntime runtime = Assertions.assertDoesNotThrow(() ->
					new McpTransportRuntime(configuration, invocation -> {}, clock));
			try {
				runtime.start();
				Assertions.assertTrue(timerRan.await(5, TimeUnit.SECONDS));
				Assertions.assertNull(failure.get());
				Assertions.assertEquals(0, inheritanceCalls.get());
			} finally {
				runtime.stop();
				Assertions.assertTrue(runtime.awaitHandlerTermination(Duration.ofSeconds(5)));
				Thread timer = reflectedField(runtime, "timerThread", Thread.class);
				timer.join(5_000);
				Assertions.assertFalse(timer.isAlive());
				EventLoop loop = reflectedField(runtime, "eventLoop", EventLoop.class);
				Thread acceptor = reflectedField(loop, "thread", Thread.class);
				acceptor.join(5_000);
				Assertions.assertFalse(acceptor.isAlive());
				for (Object connectionLoop : reflectedField(loop, "connectionEventLoops", List.class)) {
					Thread writer = reflectedField(connectionLoop, "thread", Thread.class);
					writer.join(5_000);
					Assertions.assertFalse(writer.isAlive());
				}
			}
		} finally {
			local.remove();
			caller.setContextClassLoader(priorLoader);
		}
		Assertions.assertEquals(0, inheritanceCalls.get());
	}

	private static <T> T reflectedField(Object target, String name, Class<T> type) throws Exception {
		Field field = target.getClass().getDeclaredField(name); field.setAccessible(true);
		return type.cast(field.get(target));
	}

	@Test
	public void platform_live_post_uses_independent_listener_and_event_driven_sse_body() throws Exception {
		runLivePost(McpThreadStrategy.PLATFORM);
	}

	@Test
	public void virtual_live_post_uses_independent_listener_and_event_driven_sse_body() throws Exception {
		runLivePost(McpThreadStrategy.VIRTUAL);
	}

	private static void runLivePost(McpThreadStrategy threadStrategy) throws Exception {
		McpTransportConfiguration configuration = new McpTransportConfiguration(
				"127.0.0.1",
				0,
				1,
				4,
				1,
				1,
				2,
				1_024,
				1_024,
				Duration.ofSeconds(5),
				Duration.ofSeconds(2),
				Duration.ofMillis(250),
				threadStrategy);
		McpTransportRuntime runtime = new McpTransportRuntime(configuration, invocation -> {
			invocation.progress("working:" + invocation.requestId());
			invocation.complete("done:" + invocation.requestId());
		});

		try {
			runtime.start();
			String response = postAndReadAll(runtime.port(), "/request", "smoke");

			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
			Assertions.assertTrue(response.contains("Transfer-Encoding: chunked\r\n"), response);
			Assertions.assertTrue(response.contains("Content-Type: text/event-stream\r\n"), response);
			Assertions.assertTrue(response.contains("event: progress\ndata: working:smoke\n\n"), response);
			Assertions.assertTrue(response.contains("event: result\ndata: done:smoke\n\n"), response);
			Assertions.assertTrue(response.endsWith("0\r\n\r\n"), response);
			awaitCondition(() -> {
				McpTransportRuntime.Snapshot snapshot = runtime.snapshot();
				return snapshot.liveExchanges() == 0
						&& snapshot.activeStreams() == 0
						&& snapshot.dispatcher().activeSlots() == 0
						&& snapshot.dispatcher().queueDepth() == 0
						&& snapshot.residualHandlerSlots() == 0;
			});
			Assertions.assertEquals(0, runtime.snapshot().dispatcher().activeSlots());
			Assertions.assertEquals(1, runtime.snapshot().terminalReservations());
		} finally {
			runtime.close();
			Assertions.assertTrue(runtime.awaitHandlerTermination(Duration.ofSeconds(3)));
		}
	}

	private static String postAndReadAll(int port, String path, String body) throws Exception {
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(("POST " + path + " HTTP/1.1\r\n"
					+ "Host: 127.0.0.1:" + port + "\r\n"
					+ "Content-Type: application/json\r\n"
					+ "Accept: text/event-stream\r\n"
					+ "Content-Length: " + bodyBytes.length + "\r\n"
					+ "Connection: close\r\n"
					+ "\r\n").getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().write(bodyBytes);
			socket.getOutputStream().flush();

			ByteArrayOutputStream response = new ByteArrayOutputStream();
			InputStream inputStream = socket.getInputStream();
			byte[] buffer = new byte[256];
			int read;

			while ((read = inputStream.read(buffer)) >= 0)
				response.write(buffer, 0, read);

			return response.toString(StandardCharsets.UTF_8);
		}
	}

	private static void awaitCondition(Condition condition) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();

		while (!condition.evaluate()) {
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError("Timed out waiting for condition");

			Thread.onSpinWait();
		}
	}

	@FunctionalInterface
	private interface Condition {
		boolean evaluate() throws Exception;
	}
}
