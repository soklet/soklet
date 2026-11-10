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
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.Options;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import com.soklet.internal.streaming.ManagedSseLifecycle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Internal transport helpers isolate inherited locals while retaining their caller's loader. */
@Timeout(60)
class TransportHelperThreadIsolationTests {
	@Test
	void applicationExecutorFactoryStillInheritsItsCallerContext() throws Exception {
		InheritableThreadLocal<String> local = new InheritableThreadLocal<>();
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		ClassLoader expected = new ClassLoader(original) {};
		AtomicReference<String> observed = new AtomicReference<>();
		AtomicReference<ClassLoader> loader = new AtomicReference<>();
		CountDownLatch delivered = new CountDownLatch(1);
		Thread worker = null;
		try {
			local.set("application-context");
			Thread.currentThread().setContextClassLoader(expected);
			worker = Utilities.createVirtualThreadFactory("application-handler-", (thread, failure) -> {}).newThread(() -> {
				observed.set(local.get()); loader.set(Thread.currentThread().getContextClassLoader()); delivered.countDown();
			});
			Assertions.assertTrue(worker.isVirtual());
			worker.start();
			Assertions.assertTrue(delivered.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals("application-context", observed.get());
			Assertions.assertSame(expected, loader.get());
		} finally {
			local.remove();
			Thread.currentThread().setContextClassLoader(original);
			if (worker != null) { worker.join(2_000); Assertions.assertFalse(worker.isAlive()); }
		}
	}

	@Test
	void httpTimeoutFactoryAvoidsApplicationCopyHooksAndRetainsContextLoader() throws Exception {
		AtomicInteger copies = new AtomicInteger();
		InheritableThreadLocal<String> local = throwingLocal(copies);
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		ClassLoader expected = new ClassLoader(original) {};
		AtomicReference<String> observed = new AtomicReference<>();
		AtomicReference<ClassLoader> loader = new AtomicReference<>();
		CountDownLatch delivered = new CountDownLatch(1);
		Thread worker = null;
		try {
			local.set("application-context");
			Thread.currentThread().setContextClassLoader(expected);
			worker = new DefaultHttpServer.NonvirtualThreadFactory("request-handler-timeout")
					.newThread(() -> { observed.set(local.get()); loader.set(Thread.currentThread().getContextClassLoader()); delivered.countDown(); });
			Assertions.assertEquals("request-handler-timeout-1", worker.getName());
			Assertions.assertTrue(worker.isDaemon());
			Assertions.assertFalse(worker.isVirtual());
			Assertions.assertEquals(Thread.State.NEW, worker.getState());
			Assertions.assertSame(expected, worker.getContextClassLoader());
			worker.start();
			Assertions.assertTrue(delivered.await(2, TimeUnit.SECONDS));
			Assertions.assertNull(observed.get());
			Assertions.assertSame(expected, loader.get());
			Assertions.assertEquals(0, copies.get());
		} finally {
			local.remove();
			Thread.currentThread().setContextClassLoader(original);
			if (worker != null) { worker.join(2_000); Assertions.assertFalse(worker.isAlive()); }
		}
	}

	@Test
	void httpListenerHelpersKeepServingWithoutInvokingApplicationCopyHooks() throws Exception {
		AtomicInteger copies = new AtomicInteger();
		InheritableThreadLocal<String> local = throwingLocal(copies);
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		ClassLoader expected = new ClassLoader(original) {};
		AtomicReference<String> handlerContext = new AtomicReference<>();
		AtomicReference<ClassLoader> handlerLoader = new AtomicReference<>();
		EventLoop loop = null;
		int port = 0;
		try {
			local.set("application-context");
			Thread.currentThread().setContextClassLoader(expected);
			loop = new EventLoop(Options.builder().withHost("127.0.0.1").withPort(0).withConcurrency(1).build(),
					(request, response) -> {
						handlerContext.set(local.get());
						handlerLoader.set(Thread.currentThread().getContextClassLoader());
						response.accept(new MicrohttpResponse(200, "OK", List.of(), "isolated".getBytes(StandardCharsets.US_ASCII)));
					});
			port = loop.getPort();
			Thread accept = field(EventLoop.class, "thread").get(loop) instanceof Thread thread ? thread : null;
			Assertions.assertNotNull(accept);
			Object connectionLoop = ((List<?>) field(EventLoop.class, "connectionEventLoops").get(loop)).get(0);
			Thread connection = (Thread) field(connectionLoop.getClass(), "thread").get(connectionLoop);
			assertListener(accept, "event-loop", expected);
			assertListener(connection, "connection-event-loop", expected);
			loop.start();
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
				socket.setSoTimeout(2_000);
				socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				String wire = readResponse(socket);
				Assertions.assertTrue(wire.startsWith("HTTP/1.1 200 "), wire);
				Assertions.assertTrue(wire.endsWith("isolated"), wire);
			}
			Assertions.assertEquals(0, copies.get());
			Assertions.assertNull(handlerContext.get());
			Assertions.assertSame(expected, handlerLoader.get());
		} finally {
			local.remove();
			Thread.currentThread().setContextClassLoader(original);
			if (loop != null) { loop.stop(); Assertions.assertTrue(loop.join(Duration.ofSeconds(2))); }
		}
		assertPortReleased(port);
	}

	@Test
	void sseListenerAndTimeoutHelpersAvoidCopyHooksAndRetainContextLoader() throws Exception {
		AtomicInteger copies = new AtomicInteger();
		InheritableThreadLocal<String> local = throwingLocal(copies);
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		ClassLoader expected = new ClassLoader(original) {};
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).host("127.0.0.1").build();
		SokletConfig config = SokletConfig.withSseServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecycleObservers(List.of())
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
						.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
		server.initialize(config, (request, response) -> { throw new AssertionError("No SSE handshake is sent in this worker test"); });
		AtomicReference<String> timeoutContext = new AtomicReference<>();
		AtomicReference<ClassLoader> timeoutLoader = new AtomicReference<>();
		CountDownLatch fired = new CountDownLatch(1);
		int port = 0;
		try {
			local.set("application-context");
			Thread.currentThread().setContextClassLoader(expected);
			server.start();
			port = ((InetSocketAddress) ((ServerSocketChannel) field(DefaultSseServer.class, "serverSocketChannel").get(server)).getLocalAddress()).getPort();
			assertListener(server.getEventLoopThread().orElseThrow(), "sse-event-loop", expected);
			TimeoutScheduler scheduler = server.getRequestHandlerTimeoutScheduler().orElseThrow();
			Thread timer = (Thread) field(TimeoutScheduler.class, "workerThread").get(scheduler);
			Assertions.assertEquals("sse-request-handler-timeout", timer.getName());
			Assertions.assertTrue(timer.isDaemon());
			Assertions.assertFalse(timer.isVirtual());
			Assertions.assertSame(expected, timer.getContextClassLoader());
			scheduler.schedule(() -> { timeoutContext.set(local.get()); timeoutLoader.set(Thread.currentThread().getContextClassLoader()); fired.countDown(); }, Duration.ofMillis(20));
			Assertions.assertTrue(fired.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(0, copies.get());
			Assertions.assertNull(timeoutContext.get());
			Assertions.assertSame(expected, timeoutLoader.get());
		} finally {
			local.remove();
			Thread.currentThread().setContextClassLoader(original);
			server.stop();
		}
		assertPortReleased(port);
	}

	@Test
	void ssePeerMonitorSurvivesAThrowingApplicationCopyHook() throws Exception {
		verifyPeerMonitor(CopyMode.THROW);
	}

	@Test
	void ssePeerMonitorDoesNotWaitForAnApplicationCopyHook() throws Exception {
		verifyPeerMonitor(CopyMode.BLOCK);
	}

	@Test
	void ssePeerMonitorRetainsContextLoaderWithoutInheritingApplicationValues() throws Exception {
		verifyPeerMonitor(CopyMode.VALUE);
	}

	private static void verifyPeerMonitor(CopyMode mode) throws Exception {
		AtomicInteger copies = new AtomicInteger();
		AtomicInteger carrierCopies = new AtomicInteger();
		CountDownLatch releaseCopy = new CountDownLatch(1);
		InheritableThreadLocal<String> local = new InheritableThreadLocal<>() {
			@Override protected String childValue(String value) {
				// Java 21 can copy, then erase, inherited locals while expanding its
				// carrier pool. Scope this fault to Soklet's target-thread construction.
				if (StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
						frame.getClassName().equals("jdk.internal.misc.CarrierThread")
								&& frame.getMethodName().equals("<init>")))) {
					carrierCopies.incrementAndGet();
					return value;
				}
				copies.incrementAndGet();
				if (mode == CopyMode.THROW) throw new IllegalStateException("Application context cannot be copied");
				if (mode == CopyMode.BLOCK) {
					try { Assertions.assertTrue(releaseCopy.await(5, TimeUnit.SECONDS), "Copy safety gate expired"); }
					catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
				}
				return "copied-application-context";
			}
		};
		ClassLoader expected = new ClassLoader(Thread.currentThread().getContextClassLoader()) {};
		try (PeerFixture fixture = new PeerFixture(local)) {
			CountDownLatch returned = new CountDownLatch(1);
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread caller = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false).start(() -> {
				Thread.currentThread().setContextClassLoader(expected);
				local.set("application-context");
				try { fixture.start(); }
				catch (Throwable throwable) { failure.set(throwable); }
				finally { local.remove(); returned.countDown(); }
			});
			try {
				Assertions.assertTrue(returned.await(1, TimeUnit.SECONDS), () ->
						"SSE monitor construction did not return; copies=" + copies.get()
								+ ", caller=" + java.util.Arrays.toString(caller.getStackTrace()));
				Assertions.assertNull(failure.get());
				Assertions.assertTrue(fixture.channel.entered.await(2, TimeUnit.SECONDS), "Peer monitor did not enter its read");
				Assertions.assertTrue(fixture.lifecycle.isOpen(), "Construction failure terminated an otherwise healthy SSE connection");
				Assertions.assertEquals(0, copies.get());
				Assertions.assertNull(fixture.channel.inheritedValue.get());
				Assertions.assertSame(expected, fixture.channel.loader.get());
				Assertions.assertTrue(fixture.channel.worker.get().isVirtual());
				Assertions.assertTrue(fixture.channel.worker.get().isDaemon());
				Assertions.assertEquals("soklet-sse-peer-close", fixture.channel.worker.get().getName());
				System.out.printf("SSE_HELPER_COPY_PROBE mode=%s targetCopies=%d jdkCarrierCopies=%d%n",
						mode, copies.get(), carrierCopies.get());
			} finally {
				releaseCopy.countDown();
				caller.join(2_000);
				Assertions.assertFalse(caller.isAlive());
			}
		}
	}

	private static InheritableThreadLocal<String> throwingLocal(AtomicInteger copies) {
		return new InheritableThreadLocal<>() {
			@Override protected String childValue(String value) {
				copies.incrementAndGet();
				throw new IllegalStateException("Application context-copy hook must not run");
			}
		};
	}

	private static void assertListener(Thread thread, String name, ClassLoader loader) {
		Assertions.assertEquals(name, thread.getName());
		Assertions.assertFalse(thread.isDaemon());
		Assertions.assertFalse(thread.isVirtual());
		Assertions.assertSame(loader, thread.getContextClassLoader());
	}

	private static Field field(Class<?> type, String name) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static void assertPortReleased(int port) throws IOException {
		Assertions.assertTrue(port > 0);
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress("127.0.0.1", port));
		}
	}

	private static String readResponse(Socket socket) throws IOException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[1_024];
		for (;;) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0L)
				throw new java.net.SocketTimeoutException("HTTP helper response deadline expired");
			socket.setSoTimeout((int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
			int count = socket.getInputStream().read(buffer);
			if (count < 0)
				return bytes.toString(StandardCharsets.US_ASCII);
			bytes.write(buffer, 0, count);
			if (bytes.size() > 4_096)
				throw new IOException("Unexpectedly large HTTP helper response");
		}
	}

	private enum CopyMode { THROW, BLOCK, VALUE }

	private static final class PeerFixture implements AutoCloseable {
		final StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(1), ignored -> {});
		final ProbeChannel channel;
		final DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
		final Object connection;
		final ManagedSseLifecycle lifecycle;
		final Method start;

		PeerFixture(InheritableThreadLocal<String> local) throws Exception {
			channel = new ProbeChannel(local);
			ResourceMethod method = ResourceMethod.fromComponents(HttpMethod.GET, ResourcePathDeclaration.fromPath("/helper-events"), Resource.class.getMethod("events"), true);
			Class<?> type = Class.forName("com.soklet.DefaultSseServer$DefaultSseConnection");
			Constructor<?> constructor = type.getDeclaredConstructor(Request.class, ResourceMethod.class, Object.class, Integer.class, SocketChannel.class, StreamLifecycleCoordinator.Reservation.class);
			constructor.setAccessible(true);
			connection = constructor.newInstance(Request.fromPath(HttpMethod.GET, "/helper-events"), method, null, 1, channel, coordinator.tryReserve());
			lifecycle = (ManagedSseLifecycle) field(type, "lifecycle").get(connection);
			start = DefaultSseServer.class.getDeclaredMethod("startClientDisconnectMonitor", type);
			start.setAccessible(true);
		}

		void start() throws Exception { start.invoke(server, connection); }

		@Override public void close() throws Exception {
			channel.release.countDown();
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			Thread monitor = channel.worker.get();
			if (monitor != null) { monitor.join(2_000); Assertions.assertFalse(monitor.isAlive()); }
			channel.close();
		}
	}

	public static final class Resource {
		@SseEventSource("/helper-events")
		public SseHandshakeResult events() { return SseHandshakeResult.Accepted.builder().build(); }
	}

	private static final class ProbeChannel extends SocketChannel {
		final InheritableThreadLocal<String> local;
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicReference<String> inheritedValue = new AtomicReference<>();
		final AtomicReference<ClassLoader> loader = new AtomicReference<>();
		final AtomicReference<Thread> worker = new AtomicReference<>();

		ProbeChannel(InheritableThreadLocal<String> local) { super(SelectorProvider.provider()); this.local = local; }
		@Override public int read(ByteBuffer target) throws IOException {
			worker.set(Thread.currentThread()); inheritedValue.set(local.get()); loader.set(Thread.currentThread().getContextClassLoader()); entered.countDown();
			try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Peer read release timed out"); }
			catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
			return -1;
		}
		@Override public long read(ByteBuffer[] targets, int offset, int length) throws IOException { return length == 0 ? 0 : read(targets[offset]); }
		@Override public int write(ByteBuffer source) { int length = source.remaining(); source.position(source.limit()); return length; }
		@Override public long write(ByteBuffer[] sources, int offset, int length) { long written = 0; for (int i = offset; i < offset + length; ++i) written += write(sources[i]); return written; }
		@Override public SocketChannel bind(SocketAddress address) { return this; }
		@Override public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
		@Override public <T> T getOption(SocketOption<T> option) { return null; }
		@Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
		@Override public SocketChannel shutdownInput() { return this; }
		@Override public SocketChannel shutdownOutput() { return this; }
		@Override public Socket socket() { throw new UnsupportedOperationException(); }
		@Override public boolean isConnected() { return true; }
		@Override public boolean isConnectionPending() { return false; }
		@Override public boolean connect(SocketAddress address) { return true; }
		@Override public boolean finishConnect() { return true; }
		@Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 0); }
		@Override public SocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
		@Override protected void implCloseSelectableChannel() { release.countDown(); }
		@Override protected void implConfigureBlocking(boolean block) {}
	}
}
