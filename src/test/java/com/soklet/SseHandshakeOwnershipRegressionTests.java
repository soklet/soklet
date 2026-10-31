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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class SseHandshakeOwnershipRegressionTests {
	@TempDir Path directory;

	@TestFactory
	Stream<DynamicTest> lateCapacityReplacementRetiresOwnedPredecessorsOnly() {
		return Stream.of(true, false).flatMap(owned -> Stream.of(true, false)
				.map(failReplacement -> DynamicTest.dynamicTest("owned=" + owned + ", failed-replacement=" + failReplacement,
						() -> assertTimeoutPreemptively(Duration.ofSeconds(60),
								() -> assertLateCapacityCleanup(owned, failReplacement)))));
	}

	private void assertLateCapacityCleanup(boolean owned, boolean failReplacement) throws Exception {
		Resource resource = new Resource();
		CountDownLatch established = new CountDownLatch(1);
		CountDownLatch secondFinished = new CountDownLatch(1);
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicBoolean cleanupMarked = new AtomicBoolean();
		Path path = Files.writeString(Files.createTempFile(directory, "late-", ".txt"), "owned");
		try (ClosingChannel channel = new ClosingChannel(FileChannel.open(path), () -> {
			Soklet owner = ownerReference.get();
			cleanupMarked.set(LifecycleExecutionContext.isMarked(owner.getDirectLifecycle().executionOwnerToken()));
		})) {
			int port = TestSupport.findFreePort();
			SseServer server = SseServer.withPort(port).host("127.0.0.1").concurrentConnectionLimit(1)
					.requestHandlerConcurrency(2).requestHandlerTimeout(Duration.ofSeconds(20))
					.verifyConnectionOnceEstablished(false).build();
			ResponseMarshaler marshaler = ResponseMarshaler.builder()
					.serviceUnavailableHandler((request, method) -> {
						if (failReplacement) throw new IllegalStateException("replacement preparation failed");
						return MarshaledResponse.fromStatusCode(503);
					})
					.postProcessor(response -> response.getHeaders().getOrDefault("X-Resource", List.of()).contains("second")
							? response.copy().body(channel, 0L, 5L, owned).withoutBody().finish() : response)
					.build();
			Soklet owner = Soklet.fromConfig(config(server, resource).responseMarshaler(marshaler)
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) {}
						@Override public void didEstablishSseConnection(SseConnection connection) { established.countDown(); }
						@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
								MarshaledResponse response, Duration duration, List<Throwable> failures) {
							if (request.getRawPathAndQuery().equals("/second")) secondFinished.countDown();
						}
					}).build());
			ownerReference.set(owner);
			try {
				owner.start();
				try (Socket first = request(port, "/first"); Socket second = request(port, "/second")) {
					assertTrue(resource.entered.await(5, TimeUnit.SECONDS), "Both requests must pass early admission");
					resource.releaseFirst.countDown();
					assertTrue(readHead(first).startsWith("HTTP/1.1 200"));
					assertTrue(established.await(5, TimeUnit.SECONDS));
					resource.releaseSecond.countDown();
					String rejected = new String(second.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
					assertTrue(rejected.startsWith("HTTP/1.1 " + (failReplacement ? 500 : 503)), rejected);
					assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
					assertEquals(!owned, channel.isOpen(), "The final capacity decision must release owned predecessors");
					assertEquals(owned ? 1 : 0, channel.closes.get());
					if (owned) assertTrue(cleanupMarked.get(), "Cleanup must carry the exact lifecycle owner");
				}
			} finally {
				resource.releaseFirst.countDown();
				resource.releaseSecond.countDown();
				owner.close();
			}
			assertTrue(owner.getShutdownResult().orElseThrow().isComplete());
			assertEquals(!owned, channel.isOpen());
		}
	}

	@TestFactory
	Stream<DynamicTest> acceptedSimulationReleasesPredecessorsBeforeInitializer() {
		return Stream.of(true, false).flatMap(owned -> Stream.of(true, false)
				.map(failInitializer -> DynamicTest.dynamicTest("owned=" + owned + ", failed-initializer=" + failInitializer,
						() -> assertTimeoutPreemptively(Duration.ofSeconds(60),
								() -> assertAcceptedSimulationCleanup(owned, failInitializer)))));
	}

	private void assertAcceptedSimulationCleanup(boolean owned, boolean failInitializer) throws Exception {
		AtomicReference<Object> ownerToken = new AtomicReference<>();
		AtomicBoolean cleanupMarked = new AtomicBoolean();
		IllegalStateException initializerFailure = new IllegalStateException("initializer failed");
		Path path = Files.writeString(Files.createTempFile(directory, "accepted-", ".txt"), "owned");
		try (ClosingChannel channel = new ClosingChannel(FileChannel.open(path),
				() -> cleanupMarked.set(LifecycleExecutionContext.isMarked(ownerToken.get())))) {
			Resource resource = new Resource();
			AtomicInteger initialized = new AtomicInteger();
			resource.initializer = unicaster -> {
				initialized.incrementAndGet();
				assertEquals(!owned, channel.isOpen(), "Handshake resources must be retired before application initialization");
				if (failInitializer) throw initializerFailure;
			};
			SokletConfig config = config(SseServer.withPort(0).build(), resource)
					.responseMarshaler(ResponseMarshaler.builder().postProcessor(response ->
							response.copy().body(channel, 0L, 5L, owned).withoutBody().finish()).build()).build();
			ShutdownResult shutdown = SokletSimulator.run(config, simulator -> {
				ownerToken.set(simulatorOwnerToken(simulator));
				if (failInitializer) {
					assertSame(initializerFailure, assertThrows(IllegalStateException.class,
							() -> simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events"))));
				} else {
					try (var accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
							simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")))) {
						MarshaledResponse snapshot = accepted.getHttpRequestResult().getMarshaledResponse();
						assertEquals(List.of("events"), snapshot.getHeaders().get("X-Resource"));
						assertFalse(snapshot.hasOwnedChannels(), "The captured response must be detached from resource ownership");
						assertTrue(snapshot.getBody().isEmpty());
					}
				}
				assertEquals(1, initialized.get());
				assertEquals(!owned, channel.isOpen());
				assertEquals(owned ? 1 : 0, channel.closes.get());
				if (owned) assertTrue(cleanupMarked.get(), "Cleanup must carry the exact simulator lifecycle owner");
			});
			assertTrue(shutdown.isComplete());
			assertEquals(!owned, channel.isOpen());
		}
	}

	@Test
	void acceptedSimulationCleanupFailureRetiresReservation() throws Exception {
		Path path = Files.writeString(directory.resolve("failed-close.txt"), "owned");
		IllegalStateException cleanupFailure = new IllegalStateException("channel cleanup failed");
		AtomicReference<Object> ownerToken = new AtomicReference<>();
		AtomicBoolean cleanupMarked = new AtomicBoolean();
		try (ClosingChannel channel = new ClosingChannel(FileChannel.open(path), () -> {
			cleanupMarked.set(LifecycleExecutionContext.isMarked(ownerToken.get()));
			throw cleanupFailure;
		})) {
			Resource resource = new Resource();
			AtomicInteger initialized = new AtomicInteger();
			resource.initializer = ignored -> initialized.incrementAndGet();
			SokletConfig config = config(SseServer.withPort(0).build(), resource)
					.responseMarshaler(ResponseMarshaler.builder().postProcessor(response ->
							response.copy().body(channel, 0L, 5L, true).withoutBody().finish()).build()).build();
			ShutdownResult shutdown = SokletSimulator.run(config, simulator -> {
				ownerToken.set(simulatorOwnerToken(simulator));
				assertSame(cleanupFailure, assertThrows(IllegalStateException.class,
						() -> simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events"))));
				assertTrue(cleanupMarked.get());
				assertFalse(channel.isOpen());
				assertEquals(1, channel.closes.get());
				assertEquals(0, initialized.get());
			});
			assertTrue(shutdown.isComplete(), shutdown.toString());
		}
	}

	private static Object simulatorOwnerToken(Simulator simulator) throws ReflectiveOperationException {
		Field field = Soklet.DefaultSimulator.class.getDeclaredField("executionOwnerToken");
		field.setAccessible(true);
		return field.get(simulator);
	}

	private static SokletConfig.Builder config(SseServer server, Resource resource) {
		return SokletConfig.withSseServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				})
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build());
	}

	private static Socket request(int port, String path) throws IOException {
		Socket socket = new Socket();
		socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
		socket.setSoTimeout(5000);
		socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
		return socket;
	}

	private static String readHead(Socket socket) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		for (int value; (value = socket.getInputStream().read()) != -1;) {
			output.write(value);
			if (output.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) break;
		}
		return output.toString(StandardCharsets.ISO_8859_1);
	}

	public static class Resource {
		final CountDownLatch entered = new CountDownLatch(2);
		final CountDownLatch releaseFirst = new CountDownLatch(1);
		final CountDownLatch releaseSecond = new CountDownLatch(1);
		SseClientInitializer initializer;
		@SseEventSource("/first") public SseHandshakeResult first() throws InterruptedException {
			entered.countDown();
			if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("first request not released");
			return SseHandshakeResult.Accepted.builder().headers(Map.of("X-Resource", List.of("first"))).build();
		}
		@SseEventSource("/second") public SseHandshakeResult second() throws InterruptedException {
			entered.countDown();
			if (!releaseSecond.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("second request not released");
			return SseHandshakeResult.Accepted.builder().headers(Map.of("X-Resource", List.of("second"))).build();
		}
		@SseEventSource("/events") public SseHandshakeResult events() {
			return SseHandshakeResult.Accepted.builder().headers(Map.of("X-Resource", List.of("events")))
					.clientInitializer(initializer).build();
		}
	}

	private static final class ClosingChannel extends FileChannel {
		final FileChannel delegate;
		final Runnable onClose;
		final AtomicInteger closes = new AtomicInteger();
		ClosingChannel(FileChannel delegate, Runnable onClose) { this.delegate = delegate; this.onClose = onClose; }
		@Override protected void implCloseChannel() throws IOException {
			closes.incrementAndGet();
			try { onClose.run(); } finally { delegate.close(); }
		}
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
