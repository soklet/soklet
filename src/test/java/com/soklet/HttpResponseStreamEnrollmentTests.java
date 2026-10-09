/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HttpResponseStreamEnrollmentTests {
	@Test
	void customTransportWithoutEnrollmentFinishesEachStreamingDispatchAtHandoff() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		CustomTransport transport = new CustomTransport();
		AtomicInteger producers = new AtomicInteger();
		SokletConfig config = config(transport, collector, silent(), new Resource(stream -> producers.incrementAndGet()));
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			for (int i = 0; i < 4; i++) {
				HttpRequestResult result = transport.dispatch(request(), false);
				assertTrue(result.getMarshaledResponse().isStreaming());
				assertEquals(0L, collector.snapshot().orElseThrow().getActiveRequests());
			}
			MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();
			assertEquals(4L, requestCount(snapshot));
			assertEquals(0D, responseBytes(snapshot));
			assertTrue(snapshot.getHttpResponseStreamTerminations().isEmpty());
			assertEquals(0, producers.get());
		}
	}

	@Test
	void publicCustomTransportReportsEarlyTerminationWithoutWaitingForHandlingFinish() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		CustomTransport transport = new CustomTransport();
		AtomicInteger prepared = new AtomicInteger();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { fail(event.getMessage()); }
			@Override public void willWriteResponseStream(StreamingResponseHandle handle) { prepared.incrementAndGet(); }
		};
		try (Soklet soklet = Soklet.fromConfig(config(transport, collector, observer, new Resource(stream -> {})))) {
			soklet.start();
			Request original = request();
			transport.dispatch(original, true);
			assertSame(original, transport.handle.getRequest());
			assertEquals(1, prepared.get());
			MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();
			assertEquals(0L, snapshot.getActiveRequests());
			assertEquals(1L, requestCount(snapshot));
			assertEquals(7D, responseBytes(snapshot));
			assertEquals(1L, terminationCount(snapshot));
			assertEquals((double) transport.terminalDuration.toNanos(), snapshot.getHttpRequestDurations().values()
					.stream().mapToDouble(histogram -> histogram.getSum()).sum());
			transport.reportTerminal();
			assertEquals(1L, terminationCount(collector.snapshot().orElseThrow()));
		}
	}

	@Test
	void preparationAndTerminationRequireExactDispatchIdentityAndFiniteReplacementDiscardsPendingTerminal() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		Request original = request();
		Request equalCopy = original.copy().finish();
		MarshaledResponse response = MarshaledResponse.withStatusCode(200).stream(stream -> {}).build();
		collector.didStartRequestHandling(ServerType.HTTP, original, null);
		collector.willWriteResponseStream(handle(equalCopy, response, null));
		collector.didFinishRequestHandling(ServerType.HTTP, original, null, response, Duration.ofNanos(3), List.of());
		assertEquals(0L, collector.snapshot().orElseThrow().getActiveRequests());
		assertTrue(collector.snapshot().orElseThrow().getHttpResponseStreamTerminations().isEmpty());

		Request next = request();
		StreamingResponseHandle handle = handle(next, response, null);
		collector.didStartRequestHandling(ServerType.HTTP, next, null);
		collector.willWriteResponseStream(handle);
		collector.didTerminateResponseStream(handle(next, response, null), completed(), Duration.ofNanos(99), 99L);
		assertTrue(collector.snapshot().orElseThrow().getHttpResponseStreamTerminations().isEmpty());
		collector.didTerminateResponseStream(handle, completed(), Duration.ofNanos(11), 9L);
		assertEquals(1L, collector.snapshot().orElseThrow().getActiveRequests());
		MarshaledResponse replacement = MarshaledResponse.withStatusCode(503).body("no".getBytes(StandardCharsets.US_ASCII)).build();
		collector.didFinishRequestHandling(ServerType.HTTP, next, null, replacement, Duration.ofNanos(5), List.of());
		collector.didTerminateResponseStream(handle, completed(), Duration.ofNanos(12), 10L);
		MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();
		assertEquals(0L, snapshot.getActiveRequests());
		assertEquals(2L, requestCount(snapshot));
		assertEquals(2D, responseBytes(snapshot));
		assertTrue(snapshot.getHttpResponseStreamTerminations().isEmpty());
	}

	@Test
	void rebuildingDecoratorUsesHandoffFallbackWhileDefaultTransportStillCompletesStream() throws Exception {
		int port;
		try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
		HttpServer delegate = HttpServer.withPort(port).host("127.0.0.1").build();
		HttpServer decorator = new HttpServer() {
			@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
				RequestHandler upstream = context.getAdmissionFencedRequestHandler();
				return context.attachTransparentDelegate(delegate, (request, consumer) -> upstream.handleRequest(request,
						result -> consumer.accept(HttpRequestResult.withMarshaledResponse(result.getMarshaledResponse())
								.resourceMethod(result.getResourceMethod().orElse(null)).build())));
			}
		};
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		AtomicInteger prepared = new AtomicInteger();
		CountDownLatch finished = new CountDownLatch(1);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { fail(event.getMessage()); }
			@Override public void willWriteResponseStream(StreamingResponseHandle handle) { prepared.incrementAndGet(); }
			@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
				assertEquals(StreamTerminationReason.COMPLETED, termination.getReason()); finished.countDown();
			}
		};
		try (Soklet soklet = Soklet.fromConfig(config(decorator, collector, observer,
				new Resource(stream -> stream.write("payload".getBytes(StandardCharsets.US_ASCII)))))) {
			soklet.start();
			String wire = exchange(port, "GET", "HTTP/1.1");
			assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
			assertTrue(wire.contains("payload"), wire);
			assertTrue(finished.await(5, TimeUnit.SECONDS));
			assertEquals(0, prepared.get());
			MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();
			assertEquals(0L, snapshot.getActiveRequests());
			assertEquals(1L, requestCount(snapshot));
			assertEquals(0D, responseBytes(snapshot));
			assertTrue(snapshot.getHttpResponseStreamTerminations().isEmpty());
		}
	}

	@Test
	void copyingRequestDecoratorPreservesDispatchAndHandleIdentityThroughStreamLifecycle() throws Exception {
		int port;
		try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
		HttpServer delegate = HttpServer.withPort(port).host("127.0.0.1").build();
		AtomicReference<Request> transportRequest = new AtomicReference<>();
		AtomicReference<Request> dispatchRequest = new AtomicReference<>();
		HttpServer decorator = new HttpServer() {
			@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
				RequestHandler upstream = context.getAdmissionFencedRequestHandler();
				return context.attachTransparentDelegate(delegate, (request, consumer) -> {
					transportRequest.set(request);
					Request copied = request.copy().finish();
					dispatchRequest.set(copied);
					upstream.handleRequest(copied, consumer);
				});
			}
		};
		DefaultMetricsCollector defaults = DefaultMetricsCollector.defaultInstance();
		List<Request> handlingRequests = new CopyOnWriteArrayList<>();
		List<StreamingResponseHandle> preparationHandles = new CopyOnWriteArrayList<>();
		List<StreamingResponseHandle> terminalHandles = new CopyOnWriteArrayList<>();
		List<String> order = new CopyOnWriteArrayList<>();
		List<LogEvent> logs = new CopyOnWriteArrayList<>();
		CountDownLatch handlingFinished = new CountDownLatch(2);
		CountDownLatch terminated = new CountDownLatch(1);
		MetricsCollector collector = new MetricsCollector() {
			@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) {
				handlingRequests.add(request); defaults.didStartRequestHandling(type, request, method);
			}
			@Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) {
				defaults.willWriteResponse(type, request, method, response);
			}
			@Override public void willWriteResponseStream(StreamingResponseHandle handle) {
				preparationHandles.add(handle); order.add("metrics preparation"); defaults.willWriteResponseStream(handle);
			}
			@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
					MarshaledResponse response, Duration duration, List<Throwable> throwables) {
				handlingRequests.add(request); order.add("metrics finish");
				defaults.didFinishRequestHandling(type, request, method, response, duration, throwables);
				handlingFinished.countDown();
			}
			@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination,
					Duration duration, Long bytes) {
				terminalHandles.add(handle); defaults.didTerminateResponseStream(handle, termination, duration, bytes);
			}
		};
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
			@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) {
				handlingRequests.add(request);
			}
			@Override public void willWriteResponseStream(StreamingResponseHandle handle) {
				preparationHandles.add(handle); order.add("lifecycle preparation");
			}
			@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
					MarshaledResponse response, Duration duration, List<Throwable> throwables) {
				handlingRequests.add(request); order.add("lifecycle finish"); handlingFinished.countDown();
			}
			@Override public void willTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
				terminalHandles.add(handle);
			}
			@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
				terminalHandles.add(handle); terminated.countDown();
			}
		};
		Resource resource = new Resource(stream -> {
			order.add("producer"); stream.write("payload".getBytes(StandardCharsets.US_ASCII));
		});
		try (Soklet soklet = Soklet.fromConfig(config(decorator, collector, observer, resource))) {
			soklet.start();
			String wire = exchange(port, "GET", "HTTP/1.1");
			assertTrue(wire.startsWith("HTTP/1.1 200"), wire); assertTrue(wire.contains("payload"), wire);
			assertTrue(handlingFinished.await(5, TimeUnit.SECONDS)); assertTrue(terminated.await(5, TimeUnit.SECONDS));
			assertNotSame(transportRequest.get(), dispatchRequest.get()); assertNotNull(dispatchRequest.get());
			assertEquals(4, handlingRequests.size());
			handlingRequests.forEach(request -> assertSame(dispatchRequest.get(), request));
			assertEquals(2, preparationHandles.size()); assertEquals(3, terminalHandles.size());
			StreamingResponseHandle prepared = preparationHandles.get(0);
			preparationHandles.forEach(handle -> assertSame(prepared, handle));
			terminalHandles.forEach(handle -> assertSame(prepared, handle));
			assertSame(dispatchRequest.get(), prepared.getRequest());
			for (String preparation : List.of("metrics preparation", "lifecycle preparation")) {
				assertTrue(order.indexOf(preparation) < order.indexOf("producer"), order.toString());
				assertTrue(order.indexOf(preparation) < order.indexOf("metrics finish"), order.toString());
				assertTrue(order.indexOf(preparation) < order.indexOf("lifecycle finish"), order.toString());
			}
			assertTrue(logs.isEmpty(), logs.toString());
			MetricsCollector.Snapshot snapshot = defaults.snapshot().orElseThrow();
			assertEquals(0L, snapshot.getActiveRequests()); assertEquals(1L, requestCount(snapshot));
			assertEquals(7D, responseBytes(snapshot)); assertEquals(1L, terminationCount(snapshot));
		}
	}

	@Test
	void copyingRequestDecoratorRetainsDispatchIdentityWhenHttp10RejectsStreamWithoutPreparation() throws Exception {
		int port;
		try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
		HttpServer delegate = HttpServer.withPort(port).host("127.0.0.1").build();
		AtomicReference<Request> transportRequest = new AtomicReference<>();
		AtomicReference<Request> dispatchRequest = new AtomicReference<>();
		HttpServer decorator = new HttpServer() {
			@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
				RequestHandler upstream = context.getAdmissionFencedRequestHandler();
				return context.attachTransparentDelegate(delegate, (request, consumer) -> {
					transportRequest.set(request); Request copied = request.copy().finish(); dispatchRequest.set(copied);
					upstream.handleRequest(copied, consumer);
				});
			}
		};
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		List<Request> handlingRequests = new CopyOnWriteArrayList<>();
		List<StreamingResponseHandle> terminalHandles = new CopyOnWriteArrayList<>();
		List<StreamTerminationReason> reasons = new CopyOnWriteArrayList<>();
		AtomicInteger preparations = new AtomicInteger();
		AtomicInteger producers = new AtomicInteger();
		AtomicReference<MarshaledResponse> finishedResponse = new AtomicReference<>();
		CountDownLatch handlingFinished = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) {}
			@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) {
				handlingRequests.add(request);
			}
			@Override public void willWriteResponseStream(StreamingResponseHandle handle) { preparations.incrementAndGet(); }
			@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
					MarshaledResponse response, Duration duration, List<Throwable> throwables) {
				handlingRequests.add(request); finishedResponse.set(response); handlingFinished.countDown();
			}
			@Override public void willTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
				terminalHandles.add(handle); reasons.add(termination.getReason());
			}
			@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
				terminalHandles.add(handle); reasons.add(termination.getReason()); terminated.countDown();
			}
		};
		try (Soklet soklet = Soklet.fromConfig(config(decorator, collector, observer,
				new Resource(stream -> producers.incrementAndGet())))) {
			soklet.start();
			String wire = exchange(port, "GET", "HTTP/1.0"); assertTrue(wire.startsWith("HTTP/1.0 505"), wire);
			assertTrue(handlingFinished.await(5, TimeUnit.SECONDS)); assertTrue(terminated.await(5, TimeUnit.SECONDS));
			assertNotSame(transportRequest.get(), dispatchRequest.get()); assertNotNull(dispatchRequest.get());
			assertEquals(2, handlingRequests.size()); handlingRequests.forEach(request -> assertSame(dispatchRequest.get(), request));
			assertEquals(2, terminalHandles.size()); assertSame(terminalHandles.get(0), terminalHandles.get(1));
			terminalHandles.forEach(handle -> assertSame(dispatchRequest.get(), handle.getRequest()));
			assertEquals(List.of(StreamTerminationReason.PROTOCOL_UNSUPPORTED, StreamTerminationReason.PROTOCOL_UNSUPPORTED), reasons);
			assertEquals(505, finishedResponse.get().getStatusCode()); assertFalse(finishedResponse.get().isStreaming());
			assertEquals(0, preparations.get()); assertEquals(0, producers.get());
			MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();
			assertEquals(0L, snapshot.getActiveRequests()); assertEquals(1L, requestCount(snapshot));
			assertTrue(snapshot.getHttpResponseStreamTerminations().isEmpty());
		}
	}

	@Test
	void simulatorPreparesBothCallbacksBeforeProducerAndContainsTheirFailures() {
		DefaultMetricsCollector defaults = DefaultMetricsCollector.defaultInstance();
		List<String> order = new CopyOnWriteArrayList<>();
		List<LogEvent> logs = new CopyOnWriteArrayList<>();
		AtomicReference<Request> original = new AtomicReference<>();
		AtomicReference<StreamingResponseHandle> preparedHandle = new AtomicReference<>();
		MetricsCollector collector = new MetricsCollector() {
			@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) {
				original.set(request); defaults.didStartRequestHandling(type, request, method);
			}
			@Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) {
				defaults.willWriteResponse(type, request, method, response);
			}
			@Override public void willWriteResponseStream(StreamingResponseHandle handle) {
				assertSame(original.get(), handle.getRequest()); preparedHandle.set(handle); order.add("metrics"); defaults.willWriteResponseStream(handle);
				throw new IllegalStateException("metrics preparation failure");
			}
			@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
					MarshaledResponse response, Duration duration, List<Throwable> throwables) {
				defaults.didFinishRequestHandling(type, request, method, response, duration, throwables);
			}
			@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination,
					Duration duration, Long bytes) {
				assertSame(preparedHandle.get(), handle); defaults.didTerminateResponseStream(handle, termination, duration, bytes);
			}
		};
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
			@Override public void willWriteResponseStream(StreamingResponseHandle handle) {
				assertSame(preparedHandle.get(), handle); assertSame(original.get(), handle.getRequest()); order.add("lifecycle");
				throw new IllegalStateException("observer preparation failure");
			}
			@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
				assertSame(preparedHandle.get(), handle);
			}
		};
		Resource resource = new Resource(stream -> {
			assertEquals(List.of("metrics", "lifecycle"), order); order.add("producer");
			stream.write("payload".getBytes(StandardCharsets.US_ASCII));
		});
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(config(HttpServer.withPort(0).build(), collector, observer, resource)), simulator -> {
			assertEquals(7L, simulator.performHttpRequest(request()).getMarshaledResponse().getBodyLength());
			assertEquals(0L, defaults.snapshot().orElseThrow().getActiveRequests());
			assertEquals(7D, responseBytes(defaults.snapshot().orElseThrow()));
			assertEquals(1L, terminationCount(defaults.snapshot().orElseThrow()));
		});
		assertEquals(List.of("metrics", "lifecycle", "producer"), order);
		assertEquals(2, logs.size());
		assertTrue(logs.stream().allMatch(log -> log.getMessage().contains("willWriteResponseStream")));
	}

	@Test
	void headAndHttp10ReplacementNeverPrepareOrRunProducer() throws Exception {
		int port;
		try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		AtomicInteger prepared = new AtomicInteger();
		AtomicInteger producers = new AtomicInteger();
		CountDownLatch finished = new CountDownLatch(2);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) {}
			@Override public void willWriteResponseStream(StreamingResponseHandle handle) { prepared.incrementAndGet(); }
			@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
					MarshaledResponse response, Duration duration, List<Throwable> throwables) { finished.countDown(); }
		};
		try (Soklet soklet = Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1").build(), collector,
				observer, new Resource(stream -> producers.incrementAndGet())))) {
			soklet.start();
			assertTrue(exchange(port, "HEAD", "HTTP/1.1").startsWith("HTTP/1.1 200"));
			assertTrue(exchange(port, "GET", "HTTP/1.0").startsWith("HTTP/1.0 505"));
			assertTrue(finished.await(5, TimeUnit.SECONDS));
			assertEquals(0, prepared.get()); assertEquals(0, producers.get());
			assertEquals(0L, collector.snapshot().orElseThrow().getActiveRequests());
			assertEquals(2L, requestCount(collector.snapshot().orElseThrow()));
			assertTrue(collector.snapshot().orElseThrow().getHttpResponseStreamTerminations().isEmpty());
		}
	}

	private static long requestCount(MetricsCollector.Snapshot snapshot) {
		return snapshot.getHttpRequestDurations().values().stream().mapToLong(histogram -> histogram.getCount()).sum();
	}
	private static double responseBytes(MetricsCollector.Snapshot snapshot) {
		return snapshot.getHttpResponseBodyBytes().values().stream().mapToDouble(histogram -> histogram.getSum()).sum();
	}
	private static long terminationCount(MetricsCollector.Snapshot snapshot) {
		return snapshot.getHttpResponseStreamTerminations().values().stream().mapToLong(Long::longValue).sum();
	}
	private static Request request() { return Request.fromPath(HttpMethod.GET, "/enrollment"); }
	private static StreamTermination completed() { return StreamTermination.with(StreamTerminationReason.COMPLETED, Duration.ZERO).build(); }
	private static LifecycleObserver silent() { return new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} }; }
	private static String exchange(int port, String method, String version) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5000);
			socket.getOutputStream().write((method + " /enrollment " + version + "\r\nHost: localhost\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
		}
	}
	private static SokletConfig config(HttpServer server, MetricsCollector metrics, LifecycleObserver observer, Resource resource) {
		return SokletConfig.withHttpServer(server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.metricsCollector(metrics).lifecycleObserver(observer).instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				}).build();
	}
	private static StreamingResponseHandle handle(Request request, MarshaledResponse response, ResourceMethod method) {
		Instant established = Instant.now();
		return new StreamingResponseHandle() {
			@Override public ServerType getServerType() { return ServerType.HTTP; }
			@Override public Request getRequest() { return request; }
			@Override public Optional<ResourceMethod> getResourceMethod() { return Optional.ofNullable(method); }
			@Override public MarshaledResponse getMarshaledResponse() { return response; }
			@Override public Instant getEstablishedAt() { return established; }
		};
	}
	public static final class Resource {
		private final StreamingResponseWriter writer;
		private Resource(StreamingResponseWriter writer) { this.writer = writer; }
		@GET("/enrollment") public MarshaledResponse stream() { return MarshaledResponse.withStatusCode(200).stream(this.writer).build(); }
	}

	/** This fixture implements only supported public transport and observation APIs. */
	private static final class CustomTransport implements HttpServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private RequestHandler handler;
		private SokletConfig config;
		private StreamingResponseHandle handle;
		private Duration terminalDuration;
		@Override public TransportIdentity getTransportIdentity() { return this.identity; }
		@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
			this.handler = context.getAdmissionFencedRequestHandler(); this.config = context.getSokletConfig();
			TransportTerminationSignal signal = context.getTransportTerminationSignal();
			return new TransportRuntime() {
				@Override public void start(StartupContext startup) {}
				@Override public void shutdownGracefully(ShutdownContext shutdown) { signal.signalTerminated(); }
				@Override public void shutdownForcibly(ShutdownContext shutdown) { signal.signalTerminated(); }
			};
		}
		private HttpRequestResult dispatch(Request request, boolean enroll) {
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			long started = System.nanoTime();
			this.handler.handleRequest(request, response -> {
				result.set(response);
				if (enroll) {
					this.handle = handle(request, response.getMarshaledResponse(), response.getResourceMethod().orElse(null));
					this.config.getMetricsCollector().willWriteResponseStream(this.handle);
					this.config.getLifecycleObservers().forEach(observer -> observer.willWriteResponseStream(this.handle));
					this.terminalDuration = Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
					reportTerminal(); reportTerminal();
					assertEquals(1L, this.config.getMetricsCollector().snapshot().orElseThrow().getActiveRequests(),
							"Early terminal reporting must return before handling finish");
				}
			});
			return result.get();
		}
		private void reportTerminal() {
			this.config.getMetricsCollector().didTerminateResponseStream(this.handle, completed(), this.terminalDuration, 7L);
		}
	}
}
