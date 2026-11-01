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
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SseMarshalerLifecycleExecutionTests {
	@TestFactory
	Stream<DynamicTest> noncentralHandshakeCallbacksCannotWaitOnTheirOwnShutdown() {
		return Stream.of("overload-handler", "overload-post", "timeout-handler", "timeout-post",
				"invalid-body-handler", "invalid-body-post", "overload-body-close", "timeout-body-close")
				.map(mode -> DynamicTest.dynamicTest(mode, () -> assertTimeoutPreemptively(Duration.ofSeconds(60),
						() -> assertCloseIsGuarded(mode))));
	}

	private static void assertCloseIsGuarded(String mode) throws Exception {
		boolean timeout = mode.startsWith("timeout");
		boolean invalidBody = mode.startsWith("invalid-body");
		boolean bodyClose = mode.endsWith("body-close");
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicReference<Throwable> closeFailure = new AtomicReference<>();
		AtomicBoolean marked = new AtomicBoolean();
		AtomicInteger attempts = new AtomicInteger();
		CountDownLatch callbackReturned = new CountDownLatch(1);
		CountDownLatch established = new CountDownLatch(1);
		Resource resource = new Resource(timeout);
		Runnable attemptClose = () -> {
			attempts.incrementAndGet();
			Soklet owner = ownerReference.get();
			marked.set(LifecycleExecutionContext.isMarked(owner.getDirectLifecycle().executionOwnerToken()));
			try { owner.close(); }
			catch (Throwable failure) { closeFailure.set(failure); }
			finally { callbackReturned.countDown(); }
		};
		Path path = Files.createTempFile("sse-response-lifecycle-", ".txt");
		Files.writeString(path, "x");
		try (ClosingChannel channel = new ClosingChannel(FileChannel.open(path), bodyClose ? attemptClose : () -> {})) {
			ResponseMarshaler marshaler = ResponseMarshaler.builder()
					.serviceUnavailableHandler((request, method) -> {
						if (mode.endsWith("handler")) attemptClose.run();
						return bodyClose ? MarshaledResponse.withStatusCode(503).body(channel, 0L, 1L, true).build()
								: MarshaledResponse.fromStatusCode(503);
					})
					.throwableHandler((request, failure, method) -> {
						if (invalidBody && mode.endsWith("handler")) attemptClose.run();
						return MarshaledResponse.fromStatusCode(400);
					})
					.postProcessor(response -> {
						if (mode.endsWith("post") && response.getStatusCode() == (invalidBody ? 400 : 503))
							attemptClose.run();
						return response;
					}).build();
			int port = TestSupport.findFreePort();
			DefaultSseServer server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
					.concurrentConnectionLimit(1).requestHandlerTimeout(Duration.ofSeconds(30))
					.verifyConnectionOnceEstablished(false).build();
			// Callbacks must attempt self-waits against this exact owner to exercise the lifecycle guard.
			try (SokletTestScope ownerScope = SokletTestScope.of(Soklet.fromConfig(SokletConfig.withSseServer(server)
						.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
						.instanceProvider(new InstanceProvider() {
							@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
						})
						.responseMarshaler(marshaler)
						.lifecycleObserver(new LifecycleObserver() {
							@Override public void didReceiveLogEvent(LogEvent event) {}
							@Override public void didEstablishSseConnection(SseConnection connection) { established.countDown(); }
						})
						.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
								.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build()))) {
				Soklet owner = ownerScope.soklet();
				ownerReference.set(owner);
				owner.start();
				try (Socket first = request(port, invalidBody)) {
					if (timeout) {
						assertTrue(resource.entered.await(5, TimeUnit.SECONDS));
						timeoutHandshake(server);
					} else if (!invalidBody) {
						assertTrue(readHead(first).startsWith("HTTP/1.1 200"));
						assertTrue(established.await(5, TimeUnit.SECONDS));
						try (Socket second = request(port, false)) { assertGuarded(owner, callbackReturned, marked, closeFailure, attempts); }
					}
					if (timeout || invalidBody) assertGuarded(owner, callbackReturned, marked, closeFailure, attempts);
					if (bodyClose) {
						assertFalse(channel.isOpen());
						assertEquals(1, channel.closes.get());
					}
				}
			}
		} finally {
			Files.deleteIfExists(path);
		}
	}

	private static void assertGuarded(Soklet owner, CountDownLatch returned, AtomicBoolean marked,
			AtomicReference<Throwable> failure, AtomicInteger attempts) throws Exception {
		assertTrue(returned.await(5, TimeUnit.SECONDS), "The intended callback must run");
		assertTrue(marked.get(), "The callback must carry the exact lifecycle owner");
		IllegalStateException rejected = assertInstanceOf(IllegalStateException.class, failure.get());
		assertTrue(rejected.getMessage().contains("tracked lifecycle execution"), rejected.toString());
		ShutdownResult result = owner.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertTrue(result.isComplete(), result.toString());
		assertTrue(result.getShutdownComponentResults().stream().allMatch(component -> component
				.getResidualActivityEvidence().map(evidence -> evidence.getResidualActivityTypes().isEmpty()).orElse(true)));
		assertEquals(1, attempts.get());
	}

	private static Socket request(int port, boolean invalidBody) throws IOException {
		Socket socket = new Socket();
		socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
		socket.setSoTimeout(5000);
		socket.getOutputStream().write(("GET /events HTTP/1.1\r\nHost: localhost\r\n"
				+ (invalidBody ? "Content-Length: 1\r\n" : "") + "\r\n").getBytes(StandardCharsets.US_ASCII));
		return socket;
	}

	private static String readHead(Socket socket) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
		String first = reader.readLine();
		for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {}
		return first == null ? "" : first;
	}

	private static void timeoutHandshake(DefaultSseServer server) throws Exception {
		Field activeField = DefaultSseServer.class.getDeclaredField("activeHandshakes");
		activeField.setAccessible(true);
		Map<?, ?> active = (Map<?, ?>) activeField.get(server);
		assertEquals(1, active.size());
		var admission = active.entrySet().iterator().next();
		Method contextMethod = admission.getValue().getClass().getDeclaredMethod("context");
		contextMethod.setAccessible(true);
		Object context = contextMethod.invoke(admission.getValue());
		Method timeout = DefaultSseServer.class.getDeclaredMethod("handleHandshakeTimeout", SocketChannel.class, context.getClass());
		timeout.setAccessible(true);
		timeout.invoke(server, admission.getKey(), context);
	}

	public static class Resource {
		final boolean block;
		final CountDownLatch entered = new CountDownLatch(1);
		Resource(boolean block) { this.block = block; }
		@SseEventSource("/events") public SseHandshakeResult events() throws InterruptedException {
			entered.countDown();
			if (block) new CountDownLatch(1).await(10, TimeUnit.SECONDS);
			return SseHandshakeResult.accept();
		}
	}

	private static final class ClosingChannel extends FileChannel {
		final FileChannel delegate;
		final Runnable onClose;
		final AtomicInteger closes = new AtomicInteger();
		ClosingChannel(FileChannel delegate, Runnable onClose) { this.delegate = delegate; this.onClose = onClose; }
		@Override protected void implCloseChannel() throws IOException { closes.incrementAndGet(); try { onClose.run(); } finally { delegate.close(); } }
		@Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
		@Override public int read(ByteBuffer dst, long position) throws IOException { return delegate.read(dst, position); }
		@Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException { return delegate.read(dsts, offset, length); }
		@Override public int write(ByteBuffer src) throws IOException { return delegate.write(src); }
		@Override public int write(ByteBuffer src, long position) throws IOException { return delegate.write(src, position); }
		@Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException { return delegate.write(srcs, offset, length); }
		@Override public long position() throws IOException { return delegate.position(); }
		@Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
		@Override public long size() throws IOException { return delegate.size(); }
		@Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
		@Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
		@Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException { return delegate.transferTo(position, count, target); }
		@Override public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException { return delegate.transferFrom(src, position, count); }
		@Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
		@Override public FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
		@Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
	}
}
