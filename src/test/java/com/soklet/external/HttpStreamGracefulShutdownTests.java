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
package com.soklet.external;

import com.soklet.*;
import com.soklet.annotation.GET;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Public API exercised from outside Soklet's package, with real HTTP and off-network simulation. */
@Timeout(15)
class HttpStreamGracefulShutdownTests {

	@Test
	void exactPublicContractRequiresOneBoxedNonNullStateMethod() throws Exception {
		var method = ResponseStream.class.getMethod("isGracefulShutdownRequested");
		assertEquals(Boolean.class, method.getReturnType());
		assertTrue(method.getAnnotatedReturnType().isAnnotationPresent(NonNull.class));
		assertTrue(Modifier.isAbstract(method.getModifiers()));
	}

	@Test
	void liveFeedFinishesItsOutputAndFinalizerWhileAFiniteDownloadKeepsDraining() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.soklet.start();
			try (Socket feed = connect(fixture.port, "/feed"); Socket finite = connect(fixture.port, "/finite")) {
				assertTrue(readHead(feed).toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked"));
				assertTrue(readHead(finite).toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked"));
				assertTrue(fixture.resource.feedEntered.await(3, TimeUnit.SECONDS));
				assertTrue(fixture.resource.finiteEntered.await(3, TimeUnit.SECONDS));
				var shutdown = fixture.soklet.shutdown().toCompletableFuture();
				assertEquals("begin\nend\ntail\n", readChunkedBody(feed));
				assertTrue(fixture.resource.finiteStreams.get(0).isGracefulShutdownRequested());
				assertFalse(shutdown.isDone(), "The finite writer is still retained and may ignore the advisory request");
				assertFalse(fixture.resource.finiteStreams.get(0).getCancelationToken().isCanceled());
				fixture.resource.releaseFinite.countDown();
				assertEquals("first\nsecond\n", readChunkedBody(finite));
				ShutdownResult result = shutdown.get(4, TimeUnit.SECONDS);
				assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
				assertTrue(result.isComplete());
				assertEquals(StreamTerminationReason.COMPLETED, fixture.terminations.get("/feed").getReason());
				assertEquals(StreamTerminationReason.COMPLETED, fixture.terminations.get("/finite").getReason());
				assertTrue(fixture.resource.feedStream.get().isGracefulShutdownRequested());
				assertFalse(fixture.resource.feedStream.get().getCancelationToken().isCanceled());
				assertEquals(1, fixture.resource.finalizerCalls.get());
			}
		}
	}

	@Test
	void simulatorRequestsGracefulCompletionAndCapturesFinalOutputNormally() throws Exception {
		Resource resource = new Resource();
		ExecutorService producer = Executors.newSingleThreadExecutor();
		AtomicReference<Future<HttpRequestResult>> pending = new AtomicReference<>();
		try {
			ShutdownResult shutdown = SokletSimulator.run(simulatorConfig(resource), simulator -> {
				pending.set(producer.submit(() -> simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/feed").build())));
				assertTrue(resource.feedEntered.await(3, TimeUnit.SECONDS));
			});
			assertEquals(ShutdownDisposition.GRACEFUL, shutdown.getShutdownDisposition());
			assertTrue(shutdown.isComplete());
			HttpRequestResult result = pending.get().get(1, TimeUnit.SECONDS);
			assertEquals("begin\nend\ntail\n", bodyText(result));
			assertTrue(resource.feedStream.get().isGracefulShutdownRequested());
			assertTrue(resource.feedStream.get().getDeadline().isEmpty());
			assertTrue(resource.feedStream.get().getIdleTimeout().isEmpty());
			assertFalse(resource.feedStream.get().getCancelationToken().isCanceled());
			assertEquals(1, resource.finalizerCalls.get());
		} finally {
			resource.cleanupRequested.set(true); producer.shutdownNow();
			assertTrue(producer.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test
	void completedAndReusedFiniteBodyExecutionsStayUnrequested() throws Exception {
		Resource resource = new Resource(); resource.releaseFinite.countDown();
		for (int index = 0; index < 2; index++) {
			ShutdownResult shutdown = SokletSimulator.run(simulatorConfig(resource), simulator -> {
				HttpRequestResult result = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/finite").build());
				assertEquals("first\nsecond\n", bodyText(result));
			});
			assertEquals(ShutdownDisposition.GRACEFUL, shutdown.getShutdownDisposition());
		}
		assertEquals(2, resource.finiteStreams.size());
		assertNotSame(resource.finiteStreams.get(0), resource.finiteStreams.get(1));
		for (ResponseStream stream : resource.finiteStreams) assertFalse(stream.isGracefulShutdownRequested());
	}

	private static String bodyText(HttpRequestResult result) {
		MarshaledResponseBody.Bytes body = assertInstanceOf(MarshaledResponseBody.Bytes.class, result.getMarshaledResponse().getBody().orElseThrow());
		return new String(body.getBytes(), StandardCharsets.UTF_8);
	}

	private static SimulatorConfig simulatorConfig(Resource resource) {
		return SimulatorConfig.builder().httpServer().resourceMethodResolver(resolver()).instanceProvider(provider(resource))
				.lifecycleObserver(new LifecycleObserver() {}).lifecyclePolicy(policy()).build();
	}
	private static ResourceMethodResolver resolver() { return ResourceMethodResolver.fromClasses(Set.of(Resource.class)); }
	private static InstanceProvider provider(Resource resource) {
		return new InstanceProvider() {
			@Override public <T> T provide(Class<T> type) { return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type); }
		};
	}
	private static LifecyclePolicy policy() {
		return LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(3)).forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	}

	public static final class Resource {
		final CountDownLatch feedEntered = new CountDownLatch(1);
		final CountDownLatch finiteEntered = new CountDownLatch(1);
		final CountDownLatch releaseFinite = new CountDownLatch(1);
		final AtomicBoolean cleanupRequested = new AtomicBoolean();
		final AtomicReference<ResponseStream> feedStream = new AtomicReference<>();
		final java.util.concurrent.atomic.AtomicInteger finalizerCalls = new java.util.concurrent.atomic.AtomicInteger();
		final List<ResponseStream> finiteStreams = new CopyOnWriteArrayList<>();
		final MarshaledResponse finiteResponse = MarshaledResponse.withStatusCode(200).stream(responseStream -> {
			assertFalse(responseStream.isGracefulShutdownRequested());
			this.finiteStreams.add(responseStream);
			var originalDeadline = responseStream.getDeadline(); var originalIdleTimeout = responseStream.getIdleTimeout();
			responseStream.write("first\n".getBytes(StandardCharsets.UTF_8)); responseStream.flush(); this.finiteEntered.countDown();
			assertTrue(this.releaseFinite.await(5, TimeUnit.SECONDS));
			assertFalse(Thread.currentThread().isInterrupted()); assertFalse(responseStream.getCancelationToken().isCanceled());
			assertEquals(originalDeadline, responseStream.getDeadline()); assertEquals(originalIdleTimeout, responseStream.getIdleTimeout());
			responseStream.write("second\n".getBytes(StandardCharsets.UTF_8));
		}).build();
		@GET("/finite") public MarshaledResponse finite() { return this.finiteResponse; }
		@GET("/feed") public MarshaledResponse feed() {
			return MarshaledResponse.withStatusCode(200).stream(responseStream -> {
				assertFalse(responseStream.isGracefulShutdownRequested()); this.feedStream.set(responseStream);
				var originalDeadline = responseStream.getDeadline(); var originalIdleTimeout = responseStream.getIdleTimeout();
				responseStream.own(() -> { this.finalizerCalls.incrementAndGet(); responseStream.write("tail\n".getBytes(StandardCharsets.UTF_8)); });
				responseStream.write("begin\n".getBytes(StandardCharsets.UTF_8)); responseStream.flush(); this.feedEntered.countDown();
				CountDownLatch boundedWait = new CountDownLatch(1);
				while (!responseStream.isGracefulShutdownRequested() && !this.cleanupRequested.get()) {
					responseStream.getCancelationToken().throwIfCanceled(); boundedWait.await(10, TimeUnit.MILLISECONDS);
				}
				responseStream.getCancelationToken().throwIfCanceled();
				assertFalse(Thread.currentThread().isInterrupted()); assertTrue(responseStream.isOpen());
				assertEquals(originalDeadline, responseStream.getDeadline()); assertEquals(originalIdleTimeout, responseStream.getIdleTimeout());
				responseStream.write("end\n".getBytes(StandardCharsets.UTF_8));
			}).build();
		}
	}

	private static final class Fixture implements AutoCloseable {
		final Resource resource = new Resource();
		final Map<String, StreamTermination> terminations = new ConcurrentHashMap<>();
		final int port;
		final Soklet soklet;
		Fixture() throws Exception {
			try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { this.port = socket.getLocalPort(); }
			this.soklet = Soklet.fromConfig(SokletConfig.withHttpServer(HttpServer.withPort(this.port).host("127.0.0.1")
					.streamingResponseTimeout(Duration.ofSeconds(10)).build()).resourceMethodResolver(resolver()).instanceProvider(provider(this.resource))
					.lifecyclePolicy(policy()).lifecycleObserver(new LifecycleObserver() {
						@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
							terminations.put(handle.getRequest().getResourcePath().getPath(), termination);
						}
					}).build());
		}
		@Override public void close() { this.resource.cleanupRequested.set(true); this.resource.releaseFinite.countDown(); this.soklet.close(); }
	}

	private static Socket connect(int port, String path) throws Exception {
		Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(4000);
		socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		socket.getOutputStream().flush(); return socket;
	}
	private static String readHead(Socket socket) throws Exception {
		StringBuilder head = new StringBuilder(); String line;
		do { line = readLine(socket.getInputStream()); head.append(line).append("\r\n"); } while (!line.isEmpty());
		assertTrue(head.toString().startsWith("HTTP/1.1 200"), head.toString()); return head.toString();
	}
	private static String readChunkedBody(Socket socket) throws Exception {
		InputStream input = socket.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream();
		while (true) {
			int size = Integer.parseInt(readLine(input), 16); assertTrue(size >= 0 && size <= 1024);
			if (size == 0) { assertEquals("", readLine(input)); assertEquals(-1, input.read()); break; }
			byte[] bytes = input.readNBytes(size); assertEquals(size, bytes.length); body.write(bytes); assertEquals("", readLine(input));
		}
		return body.toString(StandardCharsets.UTF_8);
	}
	private static String readLine(InputStream input) throws Exception {
		StringBuilder line = new StringBuilder();
		while (line.length() < 8192) {
			int next = input.read(); assertNotEquals(-1, next, "Unexpected EOF before a complete HTTP line");
			if (next == '\r') { assertEquals('\n', input.read()); return line.toString(); }
			line.append((char) next);
		}
		throw new AssertionError("HTTP line exceeded test capture bound");
	}
}
