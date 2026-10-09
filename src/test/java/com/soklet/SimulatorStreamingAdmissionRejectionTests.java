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

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Admission rejection must describe the finite response before write/finish observation. */
@Timeout(60)
public class SimulatorStreamingAdmissionRejectionTests {
	@Test
	@Timeout(100)
	void exhaustedAdmissionReportsFiniteResponseWithoutAcquiringAnyProducerKind() {
		for (String kind : List.of("writer", "input-stream", "reader", "publisher")) {
			AtomicInteger acquisitions = new AtomicInteger();
			StreamingResponseBody body = switch (kind) {
				case "writer" -> StreamingResponseBody.fromWriter(stream -> acquisitions.incrementAndGet());
				case "input-stream" -> StreamingResponseBody.fromInputStream(() -> {
					acquisitions.incrementAndGet(); return new ByteArrayInputStream(new byte[0]);
				});
				case "reader" -> StreamingResponseBody.fromReader(() -> {
					acquisitions.incrementAndGet(); return new StringReader("");
				}, StandardCharsets.UTF_8);
				case "publisher" -> StreamingResponseBody.fromPublisher(subscriber -> acquisitions.incrementAndGet());
				default -> throw new AssertionError(kind);
			};
			Fixture fixture = new Fixture(body, false);
			fixture.run(simulator -> {
				HttpRequestResult rejected = simulator.performHttpRequest(request(HttpMethod.GET, "/rejected"));
				assertFiniteRejection(rejected.getMarshaledResponse());
				Assertions.assertTrue(rejected.getResponse().isEmpty(), "The rejected stream did not produce the finite response");
				Assertions.assertTrue(rejected.getResourceMethod().isPresent());
				assertFiniteRejection(fixture.written.get());
				assertFiniteRejection(fixture.finished.get());
				assertFiniteRejection(fixture.metricsFinished.get());
				Assertions.assertEquals(1, fixture.writeCalls.get());
				Assertions.assertEquals(1, fixture.finishCalls.get());
				Assertions.assertEquals(1, fixture.metricsFinishCalls.get());
				Assertions.assertEquals(0, fixture.failedWrites.get());
				await(fixture.terminationEntered);
				Assertions.assertEquals(StreamTerminationReason.BACKPRESSURE, fixture.termination.get().getReason());
				Throwable rejection = fixture.termination.get().getCause().orElseThrow();
				Assertions.assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, rejection);
				Assertions.assertEquals(1, fixture.metricsThrowables.get().size());
				Assertions.assertSame(rejection, fixture.metricsThrowables.get().get(0));
				Assertions.assertEquals(Duration.ZERO, fixture.termination.get().getDuration());
				Assertions.assertSame(body, fixture.handle.get().getMarshaledResponse().getStreamingResponseBody().orElseThrow());
				Assertions.assertEquals(0, acquisitions.get(), kind);
				HttpRequestResult finite = simulator.performHttpRequest(request(HttpMethod.GET, "/finite"));
				Assertions.assertEquals(200, finite.getMarshaledResponse().getStatusCode());
				HttpRequestResult head = simulator.performHttpRequest(request(HttpMethod.HEAD, "/rejected"));
				Assertions.assertEquals(200, head.getMarshaledResponse().getStatusCode());
				Assertions.assertFalse(head.getMarshaledResponse().isStreaming());
				Assertions.assertEquals(0, acquisitions.get(), "HEAD must not require admission or acquire a producer");
			});
		}
	}

	@Test
	void saturatedRejectionObservationStillReturnsFinite503AndReportsTheSkippedNotification() {
		AtomicInteger acquisitions = new AtomicInteger();
		Fixture fixture = new Fixture(StreamingResponseBody.fromWriter(stream -> acquisitions.incrementAndGet()), true);
		fixture.run(simulator -> {
			assertFiniteRejection(simulator.performHttpRequest(request(HttpMethod.GET, "/rejected")).getMarshaledResponse());
			await(fixture.terminationEntered);
			HttpRequestResult second = simulator.performHttpRequest(request(HttpMethod.GET, "/rejected"));
			assertFiniteRejection(second.getMarshaledResponse());
			LogEvent event = fixture.skippedRejection.get();
			Assertions.assertNotNull(event);
			Assertions.assertEquals(LogEventType.RESPONSE_STREAM_CANCELED, event.getLogEventType());
			Assertions.assertEquals("/rejected", event.getRequest().orElseThrow().getPath());
			Assertions.assertTrue(event.getThrowable().isEmpty());
			Assertions.assertEquals(0, acquisitions.get());
			Assertions.assertEquals(1, fixture.terminationCalls.get(), "The separate one-slot rejection allowance must stay bounded");
			Assertions.assertEquals(2, fixture.finishCalls.get());
			Assertions.assertEquals(2, fixture.metricsFinishCalls.get());
		});
	}

	@Test
	void rejectedRequestFinishesWhileRejectionObserverIsBlockedAndAdmissionRecovers() {
		AtomicInteger acquisitions = new AtomicInteger();
		Fixture fixture = new Fixture(StreamingResponseBody.fromWriter(stream -> {
			acquisitions.incrementAndGet(); stream.write("ok".getBytes(StandardCharsets.UTF_8));
		}), true);
		fixture.run(simulator -> {
			assertFiniteRejection(simulator.performHttpRequest(request(HttpMethod.GET, "/rejected")).getMarshaledResponse());
			await(fixture.terminationEntered);
			Assertions.assertEquals(1, fixture.terminationFinished.getCount());
			assertFiniteRejection(fixture.finished.get());
			Assertions.assertEquals(0, acquisitions.get());
			fixture.releaseTermination.countDown();
			await(fixture.terminationFinished);
			fixture.releaseOccupied.countDown();
			join(fixture.occupiedCaller);
			HttpRequestResult recovered = simulator.performHttpRequest(request(HttpMethod.GET, "/rejected"));
			Assertions.assertEquals(200, recovered.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("ok", new String(recovered.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
			Assertions.assertEquals(1, acquisitions.get());
		});
	}

	private static void assertFiniteRejection(MarshaledResponse response) {
		Assertions.assertNotNull(response);
		Assertions.assertEquals(503, response.getStatusCode());
		Assertions.assertFalse(response.isStreaming());
		Assertions.assertEquals(List.of("text/plain; charset=UTF-8"), response.getHeaders().get("Content-Type"));
		Assertions.assertEquals(List.of("close"), response.getHeaders().get("Connection"));
		Assertions.assertFalse(response.getHeaders().containsKey("Transfer-Encoding"));
		Assertions.assertEquals("HTTP 503: Service Unavailable", new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
	}

	private static Request request(HttpMethod method, String path) { return Request.withPath(method, path).build(); }
	private static void await(CountDownLatch latch) {
		try { Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Controlled lifecycle event did not arrive"); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
	}
	private static void join(Thread thread) {
		try { thread.join(3000); Assertions.assertFalse(thread.isAlive(), "Controlled producer did not return"); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
	}

	private static final class Fixture implements LifecycleObserver {
		private final Resource resource;
		private final boolean blockTermination;
		private final CountDownLatch occupiedEntered = new CountDownLatch(1);
		private final CountDownLatch releaseOccupied = new CountDownLatch(1);
		private final CountDownLatch terminationEntered = new CountDownLatch(1);
		private final CountDownLatch terminationFinished = new CountDownLatch(1);
		private final CountDownLatch releaseTermination = new CountDownLatch(1);
		private final AtomicReference<MarshaledResponse> written = new AtomicReference<>();
		private final AtomicReference<MarshaledResponse> finished = new AtomicReference<>();
		private final AtomicReference<MarshaledResponse> metricsFinished = new AtomicReference<>();
		private final AtomicReference<List<Throwable>> metricsThrowables = new AtomicReference<>();
		private final AtomicReference<StreamTermination> termination = new AtomicReference<>();
		private final AtomicReference<StreamingResponseHandle> handle = new AtomicReference<>();
		private final AtomicReference<Throwable> occupiedFailure = new AtomicReference<>();
		private final AtomicReference<LogEvent> skippedRejection = new AtomicReference<>();
		private final AtomicInteger terminationCalls = new AtomicInteger();
		private final AtomicInteger writeCalls = new AtomicInteger();
		private final AtomicInteger finishCalls = new AtomicInteger();
		private final AtomicInteger metricsFinishCalls = new AtomicInteger();
		private final AtomicInteger failedWrites = new AtomicInteger();
		private Thread occupiedCaller;

		private Fixture(StreamingResponseBody body, boolean blockTermination) {
			this.resource = new Resource(body, this.occupiedEntered, this.releaseOccupied);
			this.blockTermination = blockTermination;
		}

		private void run(java.util.function.Consumer<Simulator> action) {
			SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0)
					.streamingLifecycleCapacity(1).streamingCallbackConcurrency(1).build())
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(10))
					.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
					.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					}).responseMarshaler(ResponseMarshaler.builder().resourceMethodHandler((request, response, resourceMethod) -> {
						if (response.getBody().orElse(null) instanceof StreamingResponseBody body)
							return MarshaledResponse.withResponse(response).streamingResponseBody(body).build();
						return ResponseMarshaler.defaultInstance().forResourceMethod(request, response, resourceMethod);
					}).build()).lifecycleObserver(this).metricsCollector(new MetricsCollector() {
						@Override public void didFinishRequestHandling(ServerType serverType, Request request, ResourceMethod resourceMethod,
								MarshaledResponse response, Duration duration, List<Throwable> throwables) {
							if (request.getPath().equals("/rejected")) {
								metricsFinished.set(response); metricsThrowables.set(throwables); metricsFinishCalls.incrementAndGet();
							}
						}
					}).build();
			SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator -> {
				this.occupiedCaller = new Thread(() -> {
					try { simulator.performHttpRequest(request(HttpMethod.GET, "/occupied")); }
					catch (Throwable throwable) { this.occupiedFailure.set(throwable); }
				}, "admission-occupied");
				this.occupiedCaller.start();
				try { await(this.occupiedEntered); action.accept(simulator); }
				finally {
					this.releaseOccupied.countDown(); this.releaseTermination.countDown(); join(this.occupiedCaller);
				}
				Assertions.assertNull(this.occupiedFailure.get());
			});
		}

		@Override public void didWriteResponse(ServerType serverType, Request request, ResourceMethod resourceMethod,
				MarshaledResponse response, Duration duration) {
			if (request.getPath().equals("/rejected")) { this.written.set(response); this.writeCalls.incrementAndGet(); }
		}
		@Override public void didFailToWriteResponse(ServerType serverType, Request request, ResourceMethod resourceMethod,
				MarshaledResponse response, Duration duration, Throwable throwable) { this.failedWrites.incrementAndGet(); }
		@Override public void didFinishRequestHandling(ServerType serverType, Request request, ResourceMethod resourceMethod,
				MarshaledResponse response, Duration duration, List<Throwable> throwables) {
			if (request.getPath().equals("/rejected")) { this.finished.set(response); this.finishCalls.incrementAndGet(); }
		}
		@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
			if (!handle.getRequest().getPath().equals("/rejected")) return;
			this.terminationCalls.incrementAndGet();
			this.handle.set(handle); this.termination.set(termination); this.terminationEntered.countDown();
			try { if (this.blockTermination) this.releaseTermination.await(); }
			catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			finally { this.terminationFinished.countDown(); }
		}
		@Override public void didReceiveLogEvent(LogEvent event) {
			if (event.getLogEventType() == LogEventType.RESPONSE_STREAM_CANCELED)
				this.skippedRejection.set(event);
		}
	}

	public static final class Resource {
		private final StreamingResponseBody body;
		private final CountDownLatch entered;
		private final CountDownLatch release;
		private Resource(StreamingResponseBody body, CountDownLatch entered, CountDownLatch release) {
			this.body = body; this.entered = entered; this.release = release;
		}
		@GET("/occupied") public Response occupied() {
			return Response.withStatusCode(200).body(StreamingResponseBody.fromWriter(stream -> {
				this.entered.countDown(); this.release.await();
			})).build();
		}
		@GET("/rejected") public Response rejected() { return Response.withStatusCode(200).body(this.body).build(); }
		@GET("/finite") public Response finite() { return Response.withStatusCode(200).body("ok").build(); }
	}
}
