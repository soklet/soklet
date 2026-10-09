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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

/** Simulation guarantees application output/callback behavior rather than socket deadline behavior. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SimulatorStreamingContractTests {
	@Test
	void configuredHttpTimeoutsDoNotSetSimulationDeadlinesOrCancelTheSynchronousWriter() {
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<Thread> producer = new AtomicReference<>();
		Thread caller = Thread.currentThread();
		Observation observation = new Observation();
		Resource resource = new Resource(responseStream -> {
			calls.incrementAndGet(); producer.set(Thread.currentThread());
			Assertions.assertTrue(responseStream.getDeadline().isEmpty());
			Assertions.assertTrue(responseStream.getIdleTimeout().isEmpty());
			responseStream.getCancelationToken().throwIfCanceled();
			responseStream.write("ok".getBytes(StandardCharsets.UTF_8));
		});
		SokletConfig config = config(HttpServer.withPort(0).streamingResponseTimeout(Duration.ofNanos(1))
				.streamingResponseIdleTimeout(Duration.ofNanos(1)).build(), resource, observation);
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(request());
			Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("ok", new String(result.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
			Assertions.assertFalse(result.getMarshaledResponse().isStreaming());
			Assertions.assertSame(caller, producer.get());
			Assertions.assertEquals(1, calls.get());
			Assertions.assertEquals(1, observation.calls.get(), "The admitted observer must finish before the call returns");
			Assertions.assertEquals(StreamTerminationReason.COMPLETED, observation.termination.get().getReason());
		});
	}

	@Test
	void failedSimulationThrowsInsteadOfReturningPartialBytesAndCompletesObservationFirst() {
		IOException failure = new IOException("Controlled producer failure");
		AtomicInteger closed = new AtomicInteger();
		Observation observation = new Observation();
		Resource resource = new Resource(responseStream -> {
			responseStream.own((AutoCloseable) closed::incrementAndGet);
			responseStream.write("prefix".getBytes(StandardCharsets.UTF_8));
			responseStream.flush();
			throw failure;
		});
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(config(HttpServer.withPort(0).build(), resource, observation)), simulator -> {
			IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> simulator.performHttpRequest(request()));
			Assertions.assertSame(failure, exception.getCause());
			Assertions.assertEquals(1, closed.get());
			Assertions.assertEquals(1, observation.calls.get());
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, observation.termination.get().getReason());
			Assertions.assertSame(failure, observation.termination.get().getCause().orElseThrow());
			Assertions.assertTrue(observation.handle.get().getMarshaledResponse().isStreaming(), "Termination retains the original stream descriptor");
		});
	}

	@Test
	void realHttpFailureAfterAFlushedPrefixKeepsCommittedStatusAndAbortsDelivery() throws Exception {
		IOException failure = new IOException("Controlled producer failure");
		CountDownLatch releaseFailure = new CountDownLatch(1);
		CountDownLatch closeFinished = new CountDownLatch(1);
		AtomicInteger closed = new AtomicInteger();
		Observation observation = new Observation();
		Resource resource = new Resource(responseStream -> {
			responseStream.own((AutoCloseable) () -> { closed.incrementAndGet(); closeFinished.countDown(); });
			responseStream.write("prefix".getBytes(StandardCharsets.UTF_8)); responseStream.flush();
			Assertions.assertTrue(releaseFailure.await(3, TimeUnit.SECONDS)); throw failure;
		});
		int port = findFreePort();
		try (Soklet soklet = Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1")
				.streamingResponseTimeout(Duration.ofSeconds(5)).streamingResponseIdleTimeout(Duration.ofSeconds(5)).build(), resource, observation))) {
			soklet.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
				socket.setSoTimeout(3000);
				socket.getOutputStream().write("GET /contract HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				InputStream input = socket.getInputStream(); String head = readHead(input);
				Assertions.assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"), head);
				String prefixChunk = "6\r\nprefix\r\n";
				Assertions.assertEquals(prefixChunk, new String(input.readNBytes(prefixChunk.length()), StandardCharsets.ISO_8859_1));
				releaseFailure.countDown();
				Assertions.assertEquals(0, input.readAllBytes().length, "A failed body must not send the successful final chunk");
			}
			Assertions.assertTrue(observation.finished.await(3, TimeUnit.SECONDS));
			Assertions.assertTrue(closeFinished.await(3, TimeUnit.SECONDS), "Termination observation can precede physical cleanup");
			Assertions.assertEquals(1, closed.get());
			Assertions.assertEquals(1, observation.calls.get());
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, observation.termination.get().getReason());
			Assertions.assertSame(failure, observation.termination.get().getCause().orElseThrow());
		} finally { releaseFailure.countDown(); }
	}


	@Test
	@Timeout(115)
	void interceptorOnlyStreamSupportsAbsentResourceMethodInBothRuntimes() throws Exception {
		for (boolean simulated : new boolean[]{true, false}) {
			AtomicInteger producers = new AtomicInteger();
			Observation observation = new Observation();
			int port = simulated ? 0 : findFreePort();
			SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1").build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(UnmatchedResource.class))).lifecycleObserver(observation)
					.requestInterceptor(new RequestInterceptor() {
						@Override public void interceptRequest(ServerType serverType, Request request, ResourceMethod resourceMethod,
								Function<Request, MarshaledResponse> responseGenerator, Consumer<MarshaledResponse> responseWriter) {
							responseWriter.accept(MarshaledResponse.withStatusCode(200).stream(responseStream -> {
								producers.incrementAndGet(); responseStream.write("ok".getBytes(StandardCharsets.UTF_8));
							}).build());
						}
					}).build();
			if (simulated) {
				SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator -> {
					HttpRequestResult result = simulator.performHttpRequest(request());
					Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
					Assertions.assertTrue(result.getResourceMethod().isEmpty());
					Assertions.assertEquals("ok", new String(result.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
				});
			} else {
				try (Soklet soklet = Soklet.fromConfig(config)) {
					soklet.start();
					try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
						socket.setSoTimeout(3000);
						socket.getOutputStream().write("GET /contract HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
						String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
						Assertions.assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), wire);
						Assertions.assertTrue(wire.endsWith("2\r\nok\r\n0\r\n\r\n"), wire);
					}
					Assertions.assertTrue(observation.finished.await(3, TimeUnit.SECONDS));
				}
			}
			Assertions.assertEquals(1, producers.get());
			Assertions.assertEquals(1, observation.calls.get());
			Assertions.assertEquals(StreamTerminationReason.COMPLETED, observation.termination.get().getReason());
			Assertions.assertTrue(observation.handle.get().getResourceMethod().isEmpty());
		}
	}

	private static String readHead(InputStream input) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while (bytes.size() < 16_384) {
			int next = input.read(); if (next < 0) throw new IOException("Response head ended prematurely");
			bytes.write(next);
			String text = bytes.toString(StandardCharsets.ISO_8859_1);
			if (text.endsWith("\r\n\r\n")) return text;
		}
		throw new IOException("Response head exceeded the fixture bound");
	}
	private static Request request() { return Request.withPath(HttpMethod.GET, "/contract").build(); }
	private static SokletConfig config(HttpServer server, Resource resource, LifecycleObserver observer) {
		return SokletConfig.withHttpServer(server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(10))
						.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type); }
				}).lifecycleObserver(observer).build();
	}
	private static final class Observation implements LifecycleObserver {
		private final AtomicInteger calls = new AtomicInteger();
		private final CountDownLatch finished = new CountDownLatch(1);
		private final AtomicReference<StreamTermination> termination = new AtomicReference<>();
		private final AtomicReference<StreamingResponseHandle> handle = new AtomicReference<>();
		@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
			this.calls.incrementAndGet(); this.handle.set(handle); this.termination.set(termination); this.finished.countDown();
		}
		@Override public void didReceiveLogEvent(LogEvent event) {}
	}
	public static final class Resource {
		private final StreamingResponseWriter writer;
		private Resource(StreamingResponseWriter writer) { this.writer = writer; }
		@GET("/contract") public MarshaledResponse response() { return MarshaledResponse.withStatusCode(200).stream(this.writer).build(); }
	}
	public static final class UnmatchedResource {
		@GET("/fixture-only") public String health() { return "ok"; }
	}
}
