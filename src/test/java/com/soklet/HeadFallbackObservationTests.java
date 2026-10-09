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
import com.soklet.annotation.HEAD;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

/** HEAD routing metadata must identify the method actually selected before request observation. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class HeadFallbackObservationTests {
	@Test
	void simulatedHeadReportsGetFallbackAtEveryHookWithoutChangingTheRequestMethod() {
		Fixture fixture = new Fixture(false, false);
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config(0)), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(request(HttpMethod.HEAD, "/fallback"));
			Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(List.of("2"), result.getMarshaledResponse().getHeaders().get("Content-Length"));
			Assertions.assertEquals(0, result.getMarshaledResponse().bodyBytesOrEmpty().length);
			Assertions.assertEquals("fallback", result.getResourceMethod().orElseThrow().getMethod().getName());
			fixture.assertHooks("fallback", HttpMethod.HEAD);
			Assertions.assertEquals(List.of(HttpMethod.HEAD, HttpMethod.GET), fixture.resolutions);
			Assertions.assertEquals(1, fixture.resource.getCalls.get());
			Assertions.assertEquals(HttpMethod.HEAD, fixture.resource.handlerMethod);
		});
	}

	@Test
	void realHeadReportsGetFallbackAndSendsOnlyTheResponseHead() throws Exception {
		Fixture fixture = new Fixture(false, false);
		int port = findFreePort();
		try (Soklet soklet = Soklet.fromConfig(fixture.config(port))) {
			soklet.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
				socket.setSoTimeout(3000);
				socket.getOutputStream().write("HEAD /fallback HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
						.getBytes(StandardCharsets.ISO_8859_1));
				String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
				Assertions.assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), wire);
				Assertions.assertTrue(wire.toLowerCase(java.util.Locale.ROOT).contains("content-length: 2\r\n"), wire);
				Assertions.assertTrue(wire.endsWith("\r\n\r\n"), "HEAD unexpectedly delivered body bytes: " + wire);
			}
			Assertions.assertTrue(fixture.finished.await(3, TimeUnit.SECONDS));
			fixture.assertHooks("fallback", HttpMethod.HEAD);
			Assertions.assertEquals(1, fixture.resource.getCalls.get());
			Assertions.assertEquals(HttpMethod.HEAD, fixture.resource.handlerMethod);
		}
	}

	@Test
	void explicitHeadWinsWithoutResolvingOrInvokingItsGetMethod() {
		Fixture fixture = new Fixture(false, false);
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config(0)), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(request(HttpMethod.HEAD, "/explicit"));
			Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("explicitHead", result.getResourceMethod().orElseThrow().getMethod().getName());
			fixture.assertHooks("explicitHead", HttpMethod.HEAD);
			Assertions.assertEquals(List.of(HttpMethod.HEAD), fixture.resolutions);
			Assertions.assertEquals(1, fixture.resource.headCalls.get());
			Assertions.assertEquals(0, fixture.resource.getCalls.get());
		});
	}

	@Test
	void missingHeadAndNonHeadVerbRemainUnmatched() {
		for (Request request : List.of(request(HttpMethod.HEAD, "/missing"), request(HttpMethod.POST, "/fallback"))) {
			Fixture fixture = new Fixture(false, false);
			SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config(0)), simulator -> {
				HttpRequestResult result = simulator.performHttpRequest(request);
				Assertions.assertEquals(request.getHttpMethod() == HttpMethod.HEAD ? 404 : 405, result.getMarshaledResponse().getStatusCode());
				Assertions.assertTrue(result.getResourceMethod().isEmpty());
				fixture.assertHooks(null, request.getHttpMethod());
				Assertions.assertEquals(0, fixture.resource.getCalls.get());
			});
		}
	}

	@Test
	void wrapRequestRewriteIsAppliedBeforeSelectingTheHeadFallback() {
		Fixture fixture = new Fixture(true, false);
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config(0)), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(request(HttpMethod.HEAD, "/rewrite"));
			Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
			fixture.assertHooks("fallback", HttpMethod.HEAD);
			Assertions.assertEquals("/fallback", fixture.resource.handlerPath);
			Assertions.assertEquals(1, fixture.resource.getCalls.get());
		});
	}

	@Test
	void headToAStreamingGetReportsTheRouteWithoutAcquiringTheProducer() {
		Fixture fixture = new Fixture(false, false);
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config(0)), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(request(HttpMethod.HEAD, "/stream"));
			Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
			Assertions.assertFalse(result.getMarshaledResponse().isStreaming());
			fixture.assertHooks("stream", HttpMethod.HEAD);
			Assertions.assertEquals(0, fixture.resource.producerCalls.get());
		});
	}

	@Test
	void headFallbackResolverFailureStaysUnmatchedAndDoesNotInvokeTheResource() {
		Fixture fixture = new Fixture(false, true);
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config(0)), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(request(HttpMethod.HEAD, "/fallback"));
			Assertions.assertEquals(500, result.getMarshaledResponse().getStatusCode());
			Assertions.assertTrue(result.getResourceMethod().isEmpty());
			fixture.assertHooks(null, HttpMethod.HEAD);
			Assertions.assertEquals(0, fixture.resource.getCalls.get());
		});
	}

	@Test
	void contentTooLargeHeadRetainsTheFallbackRouteWithoutInvokingIt() {
		Fixture fixture = new Fixture(false, false);
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config(0)), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(request(HttpMethod.HEAD, "/fallback").copy().contentTooLarge(true).finish());
			Assertions.assertEquals(413, result.getMarshaledResponse().getStatusCode());
			fixture.assertHooks("fallback", HttpMethod.HEAD);
			Assertions.assertEquals("fallback", fixture.contentTooLargeResourceMethod.getMethod().getName(),
					"The content-too-large marshaler must receive the same fallback route");
			Assertions.assertEquals(0, fixture.resource.getCalls.get());
		});
	}

	private static Request request(HttpMethod method, String path) { return Request.withPath(method, path).build(); }
	private record Event(String phase, ResourceMethod resourceMethod, HttpMethod requestMethod) {}
	private static final class Fixture implements LifecycleObserver {
		private final Resource resource = new Resource();
		private final List<Event> events = new CopyOnWriteArrayList<>();
		private final List<HttpMethod> resolutions = new CopyOnWriteArrayList<>();
		private final CountDownLatch finished = new CountDownLatch(1);
		private final boolean rewrite;
		private final boolean failGetResolution;
		private ResourceMethod contentTooLargeResourceMethod;
		private Fixture(boolean rewrite, boolean failGetResolution) { this.rewrite = rewrite; this.failGetResolution = failGetResolution; }
		private void record(String phase, Request request, ResourceMethod method) { this.events.add(new Event(phase, method, request.getHttpMethod())); }
		private void assertHooks(String expectedMethod, HttpMethod requestMethod) {
			Assertions.assertEquals(9, this.events.size(), this.events.toString());
			for (Event event : this.events) {
				Assertions.assertEquals(expectedMethod, event.resourceMethod() == null ? null : event.resourceMethod().getMethod().getName(), event.phase());
				Assertions.assertSame(this.events.get(0).resourceMethod(), event.resourceMethod(), event.phase());
				Assertions.assertEquals(requestMethod, event.requestMethod(), event.phase());
			}
		}
		private SokletConfig config(int port) {
			ResourceMethodResolver delegate = ResourceMethodResolver.fromClasses(Set.of(Resource.class));
			ResourceMethodResolver resolver = new ResourceMethodResolver() {
				@Override public Optional<ResourceMethod> resourceMethodForRequest(Request request, ServerType type) {
					resolutions.add(request.getHttpMethod());
					if (failGetResolution && request.getHttpMethod() == HttpMethod.GET) throw new IllegalStateException("Controlled GET resolution failure");
					return delegate.resourceMethodForRequest(request, type);
				}
				@Override public Set<ResourceMethod> getResourceMethods() { return delegate.getResourceMethods(); }
			};
			return SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1").build())
					.resourceMethodResolver(resolver).instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) { return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type); }
					}).responseMarshaler(ResponseMarshaler.builder().contentTooLargeHandler((request, resourceMethod) -> {
						this.contentTooLargeResourceMethod = resourceMethod;
						return ResponseMarshaler.defaultInstance().forContentTooLarge(request, resourceMethod);
					}).build()).lifecycleObserver(this).requestInterceptor(new RequestInterceptor() {
						@Override public void wrapRequest(ServerType type, Request request, Consumer<Request> consumer) {
							consumer.accept(rewrite ? request.copy().path("/fallback").finish() : request);
						}
						@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
								Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
							record("interceptor", request, method); writer.accept(generator.apply(request));
						}
					}).metricsCollector(new MetricsCollector() {
						@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) { record("metrics:start", request, method); }
						@Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) { record("metrics:willWrite", request, method); }
						@Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration) { record("metrics:didWrite", request, method); }
						@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration, List<Throwable> throwables) { record("metrics:finish", request, method); }
					}).build();
		}
		@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) { record("observer:start", request, method); }
		@Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) { record("observer:willWrite", request, method); }
		@Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration) { record("observer:didWrite", request, method); }
		@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration, List<Throwable> throwables) { record("observer:finish", request, method); this.finished.countDown(); }
		@Override public void didReceiveLogEvent(LogEvent event) {}
	}
	public static final class Resource {
		private final AtomicInteger getCalls = new AtomicInteger();
		private final AtomicInteger headCalls = new AtomicInteger();
		private final AtomicInteger producerCalls = new AtomicInteger();
		private volatile HttpMethod handlerMethod;
		private volatile String handlerPath;
		@GET("/fallback") public String fallback(Request request) {
			this.getCalls.incrementAndGet(); this.handlerMethod = request.getHttpMethod(); this.handlerPath = request.getPath(); return "ok";
		}
		@GET("/explicit") public String explicitGet() { this.getCalls.incrementAndGet(); return "GET"; }
		@HEAD("/explicit") public String explicitHead() { this.headCalls.incrementAndGet(); return "HEAD"; }
		@GET("/stream") public MarshaledResponse stream() { return MarshaledResponse.withStatusCode(200).stream(responseStream -> this.producerCalls.incrementAndGet()).build(); }
	}
}
