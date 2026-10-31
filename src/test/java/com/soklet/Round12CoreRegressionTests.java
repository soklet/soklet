package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class Round12CoreRegressionTests {
	@Test
	void headerNameCaseDoesNotChangeValueObjectHashes() {
		Request first = Request.withPath(HttpMethod.GET, "/").headers(Map.of("X-Example", List.of("value"))).build();
		Request second = first.copy().headers(Map.of("x-example", List.of("value"))).finish();
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		Response response = Response.withStatusCode(200).headers(Map.of("X-Example", List.of("value"))).build();
		Response other = response.copy().headers(Map.of("x-example", List.of("value"))).finish();
		assertEquals(response, other);
		assertEquals(response.hashCode(), other.hashCode());
		SseHandshakeResult.Accepted accepted = SseHandshakeResult.Accepted.builder().headers(Map.of("X-Example", List.of("value"))).build();
		SseHandshakeResult.Accepted otherAccepted = SseHandshakeResult.Accepted.builder().headers(Map.of("x-example", List.of("value"))).build();
		assertEquals(accepted, otherAccepted);
		assertEquals(accepted.hashCode(), otherAccepted.hashCode());
	}

	@Test
	void wrappingCannotDispatchTheResourceTwice() {
		Resource resource = new Resource();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build()).sseServer(SseServer.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				}).lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.requestInterceptor(new RequestInterceptor() {
					@Override public void wrapRequest(ServerType type, Request request, Consumer<Request> processor) {
						processor.accept(request);
						assertThrows(IllegalStateException.class, () -> processor.accept(request));
					}
				}).build();
		SokletSimulator.run(config, simulator -> assertEquals(200,
				simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/count")).getMarshaledResponse().getStatusCode()));
		assertEquals(1, resource.calls.get());
	}

	@Test
	void simulatedFiniteFramingUsesTransportLengths() {
		SokletSimulator.run(SokletConfig.withHttpServer(HttpServer.withPort(0).build()).sseServer(SseServer.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class))).build(), simulator -> {
			MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/framing")).getMarshaledResponse();
			assertEquals(List.of("2"), response.getHeaders().get("Content-Length"));
			assertFalse(response.getHeaders().containsKey("Transfer-Encoding"));
			assertFalse(response.getHeaders().containsKey("X-Private"));
			MarshaledResponse cached = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/cached")).getMarshaledResponse();
			assertFalse(cached.getHeaders().containsKey("Content-Length"));
		});
	}

	@Test
	void rejectedSseSimulationReportsTheEffectiveRequestToBothObservers() throws Exception {
		Resource resource = new Resource();
		CountDownLatch observed = new CountDownLatch(2);
		AtomicReference<Request> lifecycleRequest = new AtomicReference<>();
		AtomicReference<Request> metricsRequest = new AtomicReference<>();
		SokletConfig config = SokletConfig.withSseServer(SseServer.withPort(0).build()).httpServer(HttpServer.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				})
				.requestInterceptor(new RequestInterceptor() {
					@Override public void wrapRequest(ServerType type, Request request, Consumer<Request> processor) {
						processor.accept(request.copy().path("/rewritten-events").finish());
					}
				})
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) {}
					@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod method,
							SseConnection.HandshakeFailureReason reason, Throwable cause) {
						lifecycleRequest.set(request);
						observed.countDown();
					}
				})
				.metricsCollector(new MetricsCollector() {
					@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod method,
							SseConnection.HandshakeFailureReason reason, Throwable cause) {
						metricsRequest.set(request);
						observed.countDown();
					}
				}).build();
		SokletSimulator.run(config, simulator -> {
			var result = assertInstanceOf(SseRequestResult.HandshakeRejected.class,
					simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/original-events")));
			assertEquals(403, result.getHttpRequestResult().getMarshaledResponse().getStatusCode());
			assertSame(resource.rejectedRequest, result.getHttpRequestResult().getEffectiveSseRequest().orElseThrow());
			try { assertTrue(observed.await(5, TimeUnit.SECONDS)); }
			catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
			assertSame(resource.rejectedRequest, lifecycleRequest.get());
			assertSame(resource.rejectedRequest, metricsRequest.get());
		});
		assertTrue(java.lang.reflect.Modifier.isPublic(
				HttpRequestResult.class.getMethod("getEffectiveSseRequest").getModifiers()));
	}

	@Test
	void finiteAncestorSymlinkTraversalsBeyondTheOsBudgetAreMissing(@TempDir Path root) throws Exception {
		Files.writeString(root.resolve("asset.txt"), "asset");
		Files.createSymbolicLink(root.resolve("self"), Path.of("."));
		StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).followSymlinks(true).build();
		for (int count : List.of(41, 48, 64)) {
			String path = "self/".repeat(count) + "asset.txt";
			assertTrue(files.marshaledResponseFor(path, Request.fromPath(HttpMethod.GET, "/" + path)).isEmpty(), path);
		}
	}

	@Test
	void httpDatesUseFixedEnglishWeekdaysAndMonths() {
		Instant instant = Instant.parse("2026-10-29T12:34:56Z");
		assertEquals("Thu, 29 Oct 2026 12:34:56 GMT", HttpDate.toHeaderValue(instant));
		assertEquals(instant, HttpDate.fromHeaderValue("Thu Oct 29 12:34:56 2026").orElseThrow());
		assertEquals(instant, HttpDate.fromHeaderValue("Thursday, 29-Oct-26 12:34:56 GMT", instant).orElseThrow());
	}

	public static class Resource {
		final AtomicInteger calls = new AtomicInteger();
		volatile Request rejectedRequest;
		@SseEventSource("/rewritten-events") public SseHandshakeResult rejectedEvents(Request request) {
			this.rejectedRequest = request;
			return SseHandshakeResult.rejectWithResponse(Response.fromStatusCode(403));
		}
		@GET("/framing") public MarshaledResponse framing() {
			return MarshaledResponse.withStatusCode(200).body("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8))
					.headers(Map.of("Content-Length", List.of("999"), "Transfer-Encoding", List.of("chunked"),
							"Connection", List.of("X-Private"), "X-Private", List.of("hidden"))).build();
		}
		@GET("/cached") public MarshaledResponse cached() {
			return MarshaledResponse.withStatusCode(304).headers(Map.of("Content-Length", List.of("999"))).build();
		}
		@GET("/count") public String count() { calls.incrementAndGet(); return "ok"; }
	}
}
