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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HttpChannelCleanupLifecycleTests {

	@TestFactory
	Stream<DynamicTest> finiteResponseCleanupPreservesLifecycleAndOwnership() {
		return Stream.of("active-owned", "retained-owned", "failed-owned", "preparation-owned", "active-borrowed", "retained-borrowed")
				.map(mode -> DynamicTest.dynamicTest(mode, () -> assertTimeoutPreemptively(Duration.ofSeconds(60),
						() -> assertCleanup(mode))));
	}

	private static void assertCleanup(String mode) throws Exception {
		boolean owned = mode.endsWith("owned");
		boolean preparationFailure = mode.startsWith("preparation");
		boolean retained = mode.startsWith("retained") || preparationFailure;
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicReference<Throwable> closeFailure = new AtomicReference<>();
		AtomicBoolean marked = new AtomicBoolean();
		CountDownLatch callbackReturned = new CountDownLatch(1);
		Runnable attemptClose = () -> {
			if (!owned) return;
			Soklet owner = ownerReference.get();
			marked.set(LifecycleExecutionContext.isMarked(owner.getDirectLifecycle().executionOwnerToken()));
			try { owner.close(); }
			catch (Throwable failure) { closeFailure.set(failure); }
			finally { callbackReturned.countDown(); }
		};
		Path path = Files.createTempFile("http-channel-cleanup-", ".txt");
		Files.writeString(path, "owned");
		try (ClosingChannel channel = new ClosingChannel(FileChannel.open(path), attemptClose, mode.startsWith("failed"))) {
			Resource resource = new Resource(channel, owned);
			int port = TestSupport.findFreePort();
			// Channel cleanup probes this owner's self-wait guard during explicit shutdown.
			try (SokletTestScope ownerScope = SokletTestScope.of(Soklet.fromConfig(SokletConfig
					.withHttpServer(HttpServer.withPort(port).host("127.0.0.1")
							.responseCompressor((request, response) -> {
								if (preparationFailure && response.getStatusCode() == 200)
									throw new IllegalStateException("Response preparation failed");
								return ResponseCompressionPlan.none();
							}).build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
					})
					.responseMarshaler(ResponseMarshaler.builder().postProcessor(response -> retained
							? response.copy().body("bytes".getBytes(StandardCharsets.US_ASCII)).finish() : response).build())
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) {}
					})
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
							.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.build()))) {
				Soklet owner = ownerScope.soklet();
				ownerReference.set(owner);
				owner.start();
				try (Socket socket = new Socket()) {
					socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
					socket.setSoTimeout(5000);
					socket.getOutputStream().write("GET /body HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
							.getBytes(StandardCharsets.US_ASCII));
					String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
					if (preparationFailure) {
						assertTrue(wire.startsWith("HTTP/1.1 500"), wire);
					} else if (!mode.startsWith("failed")) {
						assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
						assertTrue(wire.endsWith(retained ? "bytes" : "owned"), wire);
					}
				}
				if (owned) {
					assertTrue(callbackReturned.await(5, TimeUnit.SECONDS), "Owned channel cleanup must run");
					assertTrue(marked.get(), "Cleanup must carry the exact lifecycle owner");
					IllegalStateException rejected = assertInstanceOf(IllegalStateException.class, closeFailure.get());
					assertTrue(rejected.getMessage().contains("tracked lifecycle execution"), rejected.toString());
					assertFalse(channel.isOpen());
					assertEquals(1, channel.closes.get());
				}
				ShutdownResult result = owner.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
				assertTrue(result.isComplete(), result.toString());
				if (!owned) {
					assertTrue(channel.isOpen(), "Borrowed channels remain caller-owned through shutdown");
					assertEquals(0, channel.closes.get());
				}
			}
		} finally {
			Files.deleteIfExists(path);
		}
	}

	public static class Resource {
		final FileChannel channel;
		final boolean owned;
		Resource(FileChannel channel, boolean owned) { this.channel = channel; this.owned = owned; }
		@GET("/body") public MarshaledResponse body() {
			return MarshaledResponse.withStatusCode(200).body(channel, 0L, 5L, owned).build();
		}
	}

	private static final class ClosingChannel extends FileChannel {
		final FileChannel delegate;
		final Runnable onClose;
		final boolean failTransfer;
		final AtomicInteger closes = new AtomicInteger();
		ClosingChannel(FileChannel delegate, Runnable onClose, boolean failTransfer) {
			this.delegate = delegate; this.onClose = onClose; this.failTransfer = failTransfer;
		}
		@Override protected void implCloseChannel() throws IOException {
			closes.incrementAndGet();
			try { delegate.close(); } finally { onClose.run(); }
		}
		@Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
			if (failTransfer) throw new IOException("Response source failed");
			return delegate.transferTo(position, count, target);
		}
		@Override public int read(ByteBuffer buffer) throws IOException { return delegate.read(buffer); }
		@Override public long read(ByteBuffer[] buffers, int offset, int length) throws IOException { return delegate.read(buffers, offset, length); }
		@Override public int write(ByteBuffer buffer) throws IOException { return delegate.write(buffer); }
		@Override public long write(ByteBuffer[] buffers, int offset, int length) throws IOException { return delegate.write(buffers, offset, length); }
		@Override public long position() throws IOException { return delegate.position(); }
		@Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
		@Override public long size() throws IOException { return delegate.size(); }
		@Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
		@Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
		@Override public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException { return delegate.transferFrom(source, position, count); }
		@Override public int read(ByteBuffer buffer, long position) throws IOException { return delegate.read(buffer, position); }
		@Override public int write(ByteBuffer buffer, long position) throws IOException { return delegate.write(buffer, position); }
		@Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
		@Override public FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
		@Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
	}
}
