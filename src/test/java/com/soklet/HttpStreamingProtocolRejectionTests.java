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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

/** Protocol replacements must describe the finite wire response without starting the rejected producer. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class HttpStreamingProtocolRejectionTests {
	@Test
	void httpOneDotZeroRejectionReportsActualResponseAndRetainsOriginalStreamHandle() throws Exception {
		for (String kind : List.of("writer", "input-stream", "reader", "publisher")) {
			AtomicInteger producerCalls = new AtomicInteger();
			StreamingResponseBody body = switch (kind) {
				case "writer" -> StreamingResponseBody.fromWriter(stream -> producerCalls.incrementAndGet());
				case "input-stream" -> StreamingResponseBody.fromInputStream(() -> {
					producerCalls.incrementAndGet(); return new ByteArrayInputStream(new byte[0]);
				});
				case "reader" -> StreamingResponseBody.fromReader(() -> {
					producerCalls.incrementAndGet(); return new StringReader("");
				}, StandardCharsets.UTF_8);
				case "publisher" -> StreamingResponseBody.fromPublisher(subscriber -> producerCalls.incrementAndGet());
				default -> throw new AssertionError(kind);
			};
			Fixture fixture = new Fixture(body, false);
			try (fixture) {
				fixture.start();
				String wire = fixture.request("GET", "/stream", "HTTP/1.0");
				assertRejectionWire(wire);
				Assertions.assertTrue(fixture.metricsFinished.await(3, TimeUnit.SECONDS), kind);
				Assertions.assertTrue(fixture.terminationFinished.await(3, TimeUnit.SECONDS), kind);
				assertRejectionObservation(fixture);
				Assertions.assertEquals(1, fixture.resource.handlerCalls.get(), kind);
				Assertions.assertEquals(0, producerCalls.get(), kind);
			}
			Assertions.assertEquals(1, fixture.terminationCalls.get(), kind);
		}
	}

	@Test
	void replacementIsReportedEvenWhenTheLogicalStreamAlreadyHasStatus505() throws Exception {
		try (Fixture fixture = new Fixture(StreamingResponseBody.fromWriter(stream -> Assertions.fail("Rejected writer entered")), false, 505)) {
			fixture.start();
			assertRejectionWire(fixture.request("GET", "/stream", "HTTP/1.0"));
			Assertions.assertTrue(fixture.metricsFinished.await(3, TimeUnit.SECONDS));
			Assertions.assertTrue(fixture.terminationFinished.await(3, TimeUnit.SECONDS));
			assertRejectionObservation(fixture);
		}
	}

	@Test
	void rejectedFiniteResponseAndRequestFinishDoNotWaitForTerminationObserver() throws Exception {
		Fixture fixture = new Fixture(StreamingResponseBody.fromWriter(stream -> Assertions.fail("Rejected writer entered")), true);
		try (fixture) {
			fixture.start();
			assertRejectionWire(fixture.request("GET", "/stream", "HTTP/1.0"));
			Assertions.assertTrue(fixture.terminationEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(1, fixture.terminationFinished.getCount(), "The observer should still be blocked");
			Assertions.assertTrue(fixture.metricsFinished.await(3, TimeUnit.SECONDS), "Finite request finish waited for the observer");
			assertRejectionObservation(fixture);
			Assertions.assertTrue(fixture.request("GET", "/buffered", "HTTP/1.1").endsWith("ok"),
					"The event loop or request worker was blocked by termination observation");
		} finally { fixture.releaseTermination.countDown(); }
		Assertions.assertEquals(1, fixture.terminationCalls.get());
	}

	@Test
	void bufferedHttpOneDotZeroResponseStillWorks() throws Exception {
		try (Fixture fixture = new Fixture(StreamingResponseBody.fromWriter(stream -> {}), false)) {
			fixture.start();
			String wire = fixture.request("GET", "/buffered", "HTTP/1.0");
			Assertions.assertTrue(wire.startsWith("HTTP/1.0 200 OK\r\n"), wire);
			Assertions.assertTrue(wire.endsWith("ok"), wire);
			Assertions.assertFalse(wire.contains("Transfer-Encoding:"), wire);
			Assertions.assertTrue(fixture.metricsFinished.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(200, fixture.metricsFinish.get().getStatusCode());
			Assertions.assertEquals(0, fixture.terminationCalls.get());
		}
	}

	@Test
	void httpOneDotZeroHeadUsesNormalBodyOmissionWithoutStartingProducer() throws Exception {
		AtomicInteger producerCalls = new AtomicInteger();
		try (Fixture fixture = new Fixture(StreamingResponseBody.fromWriter(stream -> producerCalls.incrementAndGet()), false)) {
			fixture.start();
			String wire = fixture.request("HEAD", "/stream", "HTTP/1.0");
			Assertions.assertTrue(wire.startsWith("HTTP/1.0 200 OK\r\n"), wire);
			Assertions.assertTrue(wire.endsWith("\r\n\r\n"), wire);
			Assertions.assertFalse(wire.contains("Transfer-Encoding:"), wire);
			Assertions.assertFalse(wire.contains("Content-Length:"), wire);
			Assertions.assertTrue(fixture.metricsFinished.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(200, fixture.metricsFinish.get().getStatusCode());
			Assertions.assertEquals(1, fixture.resource.handlerCalls.get());
			Assertions.assertEquals(0, producerCalls.get());
			Assertions.assertEquals(0, fixture.terminationCalls.get());
		}
	}

	@Test
	void httpOneDotOneStillStartsAndCompletesTheStreamingProducer() throws Exception {
		AtomicInteger producerCalls = new AtomicInteger();
		try (Fixture fixture = new Fixture(StreamingResponseBody.fromWriter(stream -> {
			producerCalls.incrementAndGet(); stream.write("ok".getBytes(StandardCharsets.UTF_8));
		}), false)) {
			fixture.start();
			String wire = fixture.request("GET", "/stream", "HTTP/1.1");
			Assertions.assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), wire);
			Assertions.assertTrue(wire.contains("Transfer-Encoding: chunked\r\n"), wire);
			Assertions.assertTrue(wire.endsWith("2\r\nok\r\n0\r\n\r\n"), wire);
			Assertions.assertTrue(fixture.metricsFinished.await(3, TimeUnit.SECONDS));
			Assertions.assertTrue(fixture.terminationFinished.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(200, fixture.metricsFinish.get().getStatusCode());
			Assertions.assertTrue(fixture.metricsFinish.get().isStreaming());
			Assertions.assertEquals(StreamTerminationReason.COMPLETED, fixture.termination.get().getReason());
			Assertions.assertEquals(1, producerCalls.get());
		}
	}

	private static void assertRejectionWire(String wire) {
		Assertions.assertTrue(wire.startsWith("HTTP/1.0 505 HTTP Version Not Supported\r\n"), wire);
		Assertions.assertTrue(wire.contains("Connection: close\r\n"), wire);
		Assertions.assertTrue(wire.contains("Content-Length: 0\r\n"), wire);
		Assertions.assertFalse(wire.contains("Transfer-Encoding:"), wire);
		Assertions.assertFalse(wire.contains("X-Original:"), wire);
		Assertions.assertTrue(wire.endsWith("\r\n\r\n"), wire);
	}

	private static void assertRejectionObservation(Fixture fixture) throws InterruptedException {
		Assertions.assertTrue(fixture.handlingFinished.await(3, TimeUnit.SECONDS),
				"Observer request finish did not arrive after metrics finish");
		// willWriteResponse precedes transport preparation and describes the logical candidate.
		Assertions.assertTrue(fixture.intended.get().isStreaming());
		Assertions.assertEquals(fixture.resource.statusCode, fixture.intended.get().getStatusCode());
		for (MarshaledResponse response : List.of(fixture.written.get(), fixture.finished.get(),
				fixture.metricsWrite.get(), fixture.metricsFinish.get())) {
			Assertions.assertEquals(505, response.getStatusCode());
			Assertions.assertFalse(response.isStreaming());
			Assertions.assertEquals(List.of("close"), response.getHeaders().get("Connection"));
			Assertions.assertArrayEquals(new byte[0], ((MarshaledResponseBody.Bytes) response.getBody().orElseThrow()).getBytes());
		}
		Assertions.assertSame(fixture.intended.get(), fixture.handle.get().getMarshaledResponse());
		Assertions.assertEquals(StreamTerminationReason.PROTOCOL_UNSUPPORTED, fixture.termination.get().getReason());
		Assertions.assertTrue(fixture.termination.get().getCause().isEmpty());
		Assertions.assertEquals(1, fixture.terminationCalls.get());
		Assertions.assertEquals(1, fixture.writeCalls.get());
		Assertions.assertEquals(1, fixture.finishCalls.get());
		Assertions.assertEquals(0, fixture.writeFailures.get());
	}

	private static final class Fixture implements AutoCloseable {
		private final int port = findFreePort();
		private final TestResource resource;
		private final Soklet soklet;
		private final AtomicReference<MarshaledResponse> intended = new AtomicReference<>();
		private final AtomicReference<MarshaledResponse> written = new AtomicReference<>();
		private final AtomicReference<MarshaledResponse> finished = new AtomicReference<>();
		private final AtomicReference<MarshaledResponse> metricsWrite = new AtomicReference<>();
		private final AtomicReference<MarshaledResponse> metricsFinish = new AtomicReference<>();
		private final AtomicReference<StreamingResponseHandle> handle = new AtomicReference<>();
		private final AtomicReference<StreamTermination> termination = new AtomicReference<>();
		private final AtomicInteger terminationCalls = new AtomicInteger();
		private final AtomicInteger writeCalls = new AtomicInteger();
		private final AtomicInteger finishCalls = new AtomicInteger();
		private final AtomicInteger writeFailures = new AtomicInteger();
		private final CountDownLatch metricsFinished = new CountDownLatch(1);
		private final CountDownLatch handlingFinished = new CountDownLatch(1);
		private final CountDownLatch terminationEntered = new CountDownLatch(1);
		private final CountDownLatch terminationFinished = new CountDownLatch(1);
		private final CountDownLatch releaseTermination;

		private Fixture(StreamingResponseBody body, boolean blockTermination) throws Exception {
			this(body, blockTermination, 200);
		}

		private Fixture(StreamingResponseBody body, boolean blockTermination, int statusCode) throws Exception {
			this.resource = new TestResource(body, statusCode);
			this.releaseTermination = new CountDownLatch(blockTermination ? 1 : 0);
			this.soklet = Soklet.fromConfig(SokletConfig.withHttpServer(HttpServer.withPort(this.port).host("127.0.0.1")
					.concurrency(1).streamingResponseTimeout(Duration.ofSeconds(5)).build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TestResource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override
						public <T> T provide(@NonNull Class<@NonNull T> instanceClass) {
							return instanceClass == TestResource.class ? instanceClass.cast(resource)
									: InstanceProvider.defaultInstance().provide(instanceClass);
						}
					}).lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.metricsCollector(new MetricsCollector() {
						@Override
						public void didWriteResponse(ServerType serverType, Request request, ResourceMethod resourceMethod,
								MarshaledResponse response, Duration duration) { metricsWrite.set(response); }
						@Override
						public void didFinishRequestHandling(ServerType serverType, Request request, ResourceMethod resourceMethod,
								MarshaledResponse response, Duration duration, List<Throwable> throwables) {
							metricsFinish.set(response); metricsFinished.countDown();
						}
					}).lifecycleObserver(new LifecycleObserver() {
						@Override
						public void willWriteResponse(ServerType serverType, Request request, ResourceMethod resourceMethod,
								MarshaledResponse response) { intended.set(response); }
						@Override
						public void didWriteResponse(ServerType serverType, Request request, ResourceMethod resourceMethod,
								MarshaledResponse response, Duration duration) { written.set(response); writeCalls.incrementAndGet(); }
						@Override
						public void didFinishRequestHandling(ServerType serverType, Request request, ResourceMethod resourceMethod,
								MarshaledResponse response, Duration duration, List<Throwable> throwables) {
							finished.set(response); finishCalls.incrementAndGet(); handlingFinished.countDown();
						}
						@Override
						public void didFailToWriteResponse(ServerType serverType, Request request, ResourceMethod resourceMethod,
								MarshaledResponse response, Duration duration, Throwable throwable) { writeFailures.incrementAndGet(); }
						@Override
						public void didTerminateResponseStream(StreamingResponseHandle streamingResponseHandle, StreamTermination streamTermination) {
							handle.set(streamingResponseHandle); termination.set(streamTermination); terminationCalls.incrementAndGet();
							terminationEntered.countDown();
							try { releaseTermination.await(); }
							catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
							finally { terminationFinished.countDown(); }
						}
						@Override
						public void didReceiveLogEvent(LogEvent logEvent) { }
					}).build());
		}

		private void start() { this.soklet.start(); }

		private String request(String method, String path, String version) throws Exception {
			try (Socket socket = connectWithRetry("127.0.0.1", this.port, 3_000)) {
				socket.setSoTimeout(3_000);
				socket.getOutputStream().write((method + " " + path + " " + version
						+ "\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
				return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
			}
		}

		@Override
		public void close() throws Exception {
			this.releaseTermination.countDown();
			this.soklet.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
		}
	}

	public static final class TestResource {
		private final StreamingResponseBody body;
		private final int statusCode;
		private final AtomicInteger handlerCalls = new AtomicInteger();
		private TestResource(StreamingResponseBody body, int statusCode) { this.body = body; this.statusCode = statusCode; }

		@GET("/stream")
		public MarshaledResponse stream() {
			this.handlerCalls.incrementAndGet();
			return MarshaledResponse.withStatusCode(this.statusCode).headers(Map.of("X-Original", List.of("candidate")))
					.streamingResponseBody(this.body).build();
		}

		@GET("/buffered")
		public String buffered() { return "ok"; }
	}
}
