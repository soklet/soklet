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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for framework-generated responses outside ordinary dispatch. */
@Timeout(60)
class InternalResponsePostProcessingTests {
	@Test
	void lifecycleAdmissionFenceFinalizesEachUnavailableResponseOnce() {
		ReferenceHttpEndpoint endpoint = new ReferenceHttpEndpoint();
		AtomicInteger calls = new AtomicInteger();
		ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> {
			calls.incrementAndGet();
			return response.copy().headers(headers -> headers.put("X-Final", List.of("once")))
					.body("final503".getBytes(StandardCharsets.US_ASCII)).finish();
		}).build();
		// Stop the owner before probing its sealed admission handler.
		try (SokletTestScope ownerScope = SokletTestScope.of(Soklet.fromConfig(SokletConfig.withHttpServer(endpoint)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HttpResource.class)))
				.responseMarshaler(marshaler).build()))) {
			Soklet owner = ownerScope.soklet();
			owner.start();
			owner.shutdown().toCompletableFuture().join();
			for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
				AtomicReference<HttpRequestResult> result = new AtomicReference<>();
				endpoint.handler.handleRequest(Request.fromPath(method, "/closed"), result::set);
				MarshaledResponse response = result.get().getMarshaledResponse();
				try {
					assertEquals(503, response.getStatusCode());
					assertEquals(List.of("once"), response.getHeaders().get("X-Final"));
					assertEquals("final503", new String(response.bodyBytesOrNull(), StandardCharsets.US_ASCII));
				} finally { response.close(); }
			}
			assertEquals(2, calls.get());
		}
	}

	@Test
	void failingFencePostProcessorReleasesItsOwnedInputAndUsesFallback(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("unavailable.txt"), "owned");
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			ReferenceHttpEndpoint endpoint = new ReferenceHttpEndpoint();
			AtomicInteger calls = new AtomicInteger();
			ResponseMarshaler marshaler = ResponseMarshaler.builder()
					.serviceUnavailableHandler((request, method) -> MarshaledResponse.withStatusCode(503)
							.body(channel, 0L, 5L, true).build())
					.postProcessor(response -> {
						calls.incrementAndGet();
						throw new IllegalStateException("postprocessor failed");
					}).build();
			// Stop the owner before testing cleanup of a rejected post-shutdown response.
			try (SokletTestScope ownerScope = SokletTestScope.of(Soklet.fromConfig(SokletConfig.withHttpServer(endpoint)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HttpResource.class)))
					.responseMarshaler(marshaler).build()))) {
				Soklet owner = ownerScope.soklet();
				owner.start();
				owner.shutdown().toCompletableFuture().join();
				AtomicReference<HttpRequestResult> result = new AtomicReference<>();
				endpoint.handler.handleRequest(Request.fromPath(HttpMethod.GET, "/closed"), result::set);
				MarshaledResponse response = result.get().getMarshaledResponse();
				try {
					assertEquals(503, response.getStatusCode());
					assertFalse(response.getHeaders().containsKey("X-Final"));
				} finally { response.close(); }
				assertEquals(1, calls.get());
				assertFalse(channel.isOpen(), "A failed postprocessor must release the generated owned body");
			}
		}
	}

	@Test
	void liveSseRejectionsFinalizeInvalidBodyAndBothCapacityPathsOnce() throws Exception {
		for (boolean fastCapacity : List.of(true, false)) {
			SseFixture fixture = new SseFixture(fastCapacity);
			Soklet.run(fixture.config, () -> {
				try (Socket accepted = fixture.connect(false)) {
					assertTrue(readHeaders(accepted).startsWith("HTTP/1.1 200 "));
					long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
					while (fixture.server.getGlobalConnections().size() != 1 && System.nanoTime() - deadline < 0)
						Thread.sleep(5);
					assertEquals(1, fixture.server.getGlobalConnections().size());
					try (Socket rejected = fixture.connect(false)) {
						String wire = new String(rejected.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
						assertTrue(wire.startsWith("HTTP/1.1 503 "), wire);
						assertTrue(wire.contains("X-Final: once\r\n"), wire);
						assertTrue(wire.contains("Content-Length: 8\r\n"), wire);
						assertTrue(wire.endsWith("final503"), wire);
					}
					assertEquals(1, fixture.rejectionPostProcesses.get());
					assertEquals(fastCapacity ? 1 : 2, fixture.resource.invocations.get(),
							"Exercise the pre-handler connection check and the post-handler lifecycle check separately");
					accepted.setSoLinger(true, 0);
				}
			});
		}
		SseFixture fixture = new SseFixture(false);
		Soklet.run(fixture.config, () -> {
			try (Socket rejected = fixture.connect(true)) {
				String wire = new String(rejected.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
				assertTrue(wire.startsWith("HTTP/1.1 400 "), wire);
				assertTrue(wire.contains("X-Final: once\r\n"), wire);
				assertTrue(wire.endsWith("final400"), wire);
				assertEquals(1, fixture.rejectionPostProcesses.get());
				assertEquals(1, fixture.throwableCalls.get(), "Exercise the transport's forThrowable path after parsing");
				assertEquals(0, fixture.resource.invocations.get());
			}
		});
	}

	@Test
	void simulatorCapacityReplacementFinalizesItsNewResponseOnce() {
		AtomicInteger rejectedCalls = new AtomicInteger();
		SokletConfig config = SokletConfig.withSseServer(SseServer.withPort(0)
				.streamingLifecycleCapacity(1).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Events.class)))
				.responseMarshaler(ResponseMarshaler.builder().postProcessor(response -> {
					if (response.getStatusCode() != 503) return response;
					rejectedCalls.incrementAndGet();
					return response.copy().headers(headers -> headers.put("X-Final", List.of("once")))
							.body("final503".getBytes(StandardCharsets.US_ASCII)).finish();
				}).build()).build();
		SokletSimulator.run(config, simulator -> {
			try (SseRequestResult.HandshakeAccepted accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
					simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")))) {
				SseRequestResult.RequestFailed rejected = assertInstanceOf(SseRequestResult.RequestFailed.class,
						simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")));
				MarshaledResponse response = rejected.getHttpRequestResult().getMarshaledResponse();
				assertEquals(503, response.getStatusCode());
				assertEquals(List.of("once"), response.getHeaders().get("X-Final"));
				assertEquals("final503", new String(response.bodyBytesOrNull(), StandardCharsets.US_ASCII));
				assertEquals(1, rejectedCalls.get());
			}
		});
	}

	private static String readHeaders(Socket socket) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while (bytes.size() < 8192) {
			int next = socket.getInputStream().read();
			assertTrue(next >= 0, "Connection closed before its handshake headers");
			bytes.write(next);
			String headers = bytes.toString(StandardCharsets.US_ASCII);
			if (headers.endsWith("\r\n\r\n")) return headers;
		}
		throw new AssertionError("Handshake headers exceeded test bound");
	}

	private static final class SseFixture {
		private final int port = TestSupport.findFreePort();
		private final Events resource = new Events();
		private final AtomicInteger rejectionPostProcesses = new AtomicInteger();
		private final AtomicInteger throwableCalls = new AtomicInteger();
		private final DefaultSseServer server;
		private final SokletConfig config;
		private SseFixture(boolean fastCapacity) throws Exception {
			server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
					.concurrentConnectionLimit(fastCapacity ? 1 : 0).streamingLifecycleCapacity(1)
					.verifyConnectionOnceEstablished(false).build();
			config = SokletConfig.withSseServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Events.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
					})
					.responseMarshaler(ResponseMarshaler.builder()
							.throwableHandler((request, throwable, method) -> {
								throwableCalls.incrementAndGet();
								return MarshaledResponse.fromStatusCode(400);
							})
							.postProcessor(response -> {
								if (response.getStatusCode() < 400) return response;
								rejectionPostProcesses.incrementAndGet();
								return response.copy().headers(headers -> headers.put("X-Final", List.of("once")))
										.body(("final" + response.getStatusCode()).getBytes(StandardCharsets.US_ASCII)).finish();
							}).build())
					.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(Duration.ofMillis(100))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
		}
		private Socket connect(boolean invalidBody) throws Exception {
			Socket socket = TestSupport.connectWithRetry("127.0.0.1", port, 2000);
			socket.setSoTimeout(3000);
			socket.getOutputStream().write(("GET /events HTTP/1.1\r\nHost: localhost\r\n"
					+ (invalidBody ? "Content-Length: 1\r\n" : "") + "\r\n"
					+ (invalidBody ? "x" : "")).getBytes(StandardCharsets.US_ASCII));
			return socket;
		}
	}

	public static final class HttpResource {
		@GET("/ok") public String ok() { return "ok"; }
	}

	public static final class Events {
		private final AtomicInteger invocations = new AtomicInteger();
		@SseEventSource("/events")
		public SseHandshakeResult events() { invocations.incrementAndGet(); return SseHandshakeResult.accept(); }
	}

	private static final class ReferenceHttpEndpoint implements HttpServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private HttpServer.RequestHandler handler;
		@Override public TransportIdentity getTransportIdentity() { return identity; }
		@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
			handler = context.getAdmissionFencedRequestHandler();
			AtomicBoolean signaled = new AtomicBoolean();
			return new TransportRuntime() {
				@Override public void start(StartupContext startup) {}
				private void terminate() {
					if (signaled.compareAndSet(false, true)) context.getTransportTerminationSignal().signalTerminated();
				}
				@Override public void shutdownGracefully(ShutdownContext shutdown) { terminate(); }
				@Override public void shutdownForcibly(ShutdownContext shutdown) { terminate(); }
			};
		}
	}
}
