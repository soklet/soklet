/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class DeepDiveResponsePipelineTests {
	@Test
	void explicitFinalizationContractRequiresDecoratorsToImplementThePhase() throws Exception {
		assertTrue(Modifier.isAbstract(ResponseMarshaler.class.getMethod("postProcess", MarshaledResponse.class).getModifiers()));
		assertFalse(AutoCloseable.class.isAssignableFrom(MarshaledResponse.class));
	}

	@Test
	void rebuildingGeneratedPartsPostProcessesOnlyTheSelectedResponse() {
		AtomicInteger calls = new AtomicInteger();
		ResponseMarshaler marshaler = prefixing(calls);
		SokletSimulator.run(config(new Resource(), 0).responseMarshaler(marshaler).requestInterceptor(new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generate, Consumer<MarshaledResponse> write) {
				MarshaledResponse raw = generate.apply(request);
				assertEquals(0, calls.get());
				write.accept(MarshaledResponse.withStatusCode(raw.getStatusCode()).headers(raw.getHeaders())
						.cookies(raw.getCookies()).body(raw.getBody().orElse(null)).build());
			}
		}).build(), simulator -> {
			MarshaledResponse result = simulator.performHttpRequest(request(HttpMethod.GET)).getMarshaledResponse();
			assertEquals("post:body", new String(result.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
			assertEquals(1, result.getCookies().size());
			assertEquals(1, calls.get());
		});
	}

	@Test
	void forwardingMarshalerProjectsThePostprocessedGetRepresentationForHead() {
		AtomicInteger calls = new AtomicInteger();
		SokletSimulator.run(config(new Resource(), 0).responseMarshaler(forwarding(prefixing(calls))).build(), simulator -> {
			MarshaledResponse get = simulator.performHttpRequest(request(HttpMethod.GET)).getMarshaledResponse();
			MarshaledResponse head = simulator.performHttpRequest(request(HttpMethod.HEAD)).getMarshaledResponse();
			assertEquals(List.of("9"), get.getHeaders().get("Content-Length"));
			assertEquals(get.getHeaders().get("Content-Length"), head.getHeaders().get("Content-Length"));
			assertTrue(head.getBody().isEmpty());
			assertEquals(get.getCookies(), head.getCookies());
			assertEquals(2, calls.get());
		});
	}

	@Test
	void decoratorAndConfiguredHeadProjectionUseTheSameCompressionInputAsGet() throws Exception {
		for (boolean decorated : List.of(false, true)) {
			AtomicInteger calls = new AtomicInteger();
			List<String> planned = new CopyOnWriteArrayList<>();
			ResponseMarshaler delegate = ResponseMarshaler.builder().postProcessor(response -> {
				calls.incrementAndGet();
				return response.copy().body(("post:" + new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8))
						.getBytes(StandardCharsets.UTF_8)).finish();
			}).headHandler((request, response) -> {
				assertEquals("post:body", new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
				return ResponseMarshaler.defaultInstance().forHead(request, response);
			}).build();
			int port = TestSupport.findFreePort();
			HttpServer server = HttpServer.withPort(port).host("127.0.0.1").responseCompressor((request, response) -> {
				planned.add(new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
				return ResponseCompressionPlan.compress(ResponseCompressionCodec.gzipInstance());
			}).build();
			try (Soklet owner = Soklet.fromConfig(config(new Resource(), server)
					.responseMarshaler(decorated ? forwarding(delegate) : delegate).build())) {
				owner.start();
				String get = exchange(port, "GET /body HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\nConnection: close\r\n\r\n");
				String head = exchange(port, "HEAD /body HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\nConnection: close\r\n\r\n");
				assertTrue(get.contains("Content-Encoding: gzip\r\n"), get);
				assertTrue(head.contains("Content-Encoding: gzip\r\n"), head);
				assertEquals(List.of("post:body", "post:body"), planned);
				assertEquals(2, calls.get());
			}
		}
	}

	@Test
	void finalizationFailureUsesCustomThrowableRecoveryWithoutRepeatingThePostprocessor() {
		for (boolean failHead : List.of(false, true)) {
			AtomicInteger calls = new AtomicInteger();
			ResponseMarshaler marshaler = ResponseMarshaler.builder()
					.postProcessor(response -> {
						calls.incrementAndGet();
						if (!failHead) throw new IllegalStateException("postprocessor failed");
						return response;
					}).headHandler((request, response) -> { throw new IllegalStateException("HEAD failed"); })
					.throwableHandler((request, failure, method) -> MarshaledResponse.withStatusCode(422)
							.headers(Map.of("X-Recovery", List.of("custom"))).body("recovered".getBytes(StandardCharsets.UTF_8)).build())
					.build();
			SokletSimulator.run(config(new Resource(), 0).responseMarshaler(marshaler)
					.corsAuthorizer(CorsAuthorizer.acceptAllInstance()).build(), simulator -> {
				MarshaledResponse response = simulator.performHttpRequest(Request.withPath(failHead ? HttpMethod.HEAD : HttpMethod.GET, "/body")
						.headers(Map.of("Origin", List.of("https://example.test"))).build()).getMarshaledResponse();
				assertEquals(422, response.getStatusCode());
				assertEquals(List.of("custom"), response.getHeaders().get("X-Recovery"));
				assertEquals(List.of("https://example.test"), response.getHeaders().get("Access-Control-Allow-Origin"));
				assertEquals(1, calls.get());
				assertArrayEquals(failHead ? new byte[0] : "recovered".getBytes(StandardCharsets.UTF_8), response.bodyBytesOrEmpty());
			});
		}
	}

	@Test
	void unusedBodyReplacingCopiesLeaveTheOriginalChannelOpen(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("body"), "body");
		try (FileChannel channel = FileChannel.open(file)) {
			MarshaledResponse original = MarshaledResponse.withStatusCode(200).body(channel, 0L, 4L, true).build();
			original.copy().body(new byte[]{1}).finish();
			original.copy().body(ByteBuffer.wrap(new byte[]{2})).finish();
			original.copy().body(file).finish();
			original.copy().withoutBody().finish();
			assertTrue(channel.isOpen());
			Resource resource = new Resource(); resource.response = original;
			SokletSimulator.run(config(resource, 0).build(), simulator -> {
				assertArrayEquals("body".getBytes(StandardCharsets.UTF_8),
						simulator.performHttpRequest(request(HttpMethod.GET)).getMarshaledResponse().bodyBytesOrEmpty());
				assertFalse(channel.isOpen());
			});
		}
	}

	@Test
	void finiteSimulationConsumesOwnedChannelsAndPreservesBorrowedChannels(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("body"), "body");
		for (boolean owned : List.of(false, true)) for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
			try (FileChannel channel = FileChannel.open(file)) {
				Resource resource = new Resource();
				resource.response = MarshaledResponse.withStatusCode(200).body(channel, 0L, 4L, owned).build();
				SokletSimulator.run(config(resource, 0).build(), simulator -> {
					MarshaledResponse result = simulator.performHttpRequest(request(method)).getMarshaledResponse();
					assertEquals(!owned, channel.isOpen());
					assertArrayEquals(method == HttpMethod.GET ? "body".getBytes(StandardCharsets.UTF_8) : new byte[0], result.bodyBytesOrEmpty());
				});
			}
		}
	}

	@Test
	void lateAcceptedSseFramingFailureProducesAResponseInSimulationAndOnTheWire() throws Exception {
		ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> response.copy()
				.headers(headers -> headers.put("Content-Length", List.of("0"))).finish()).build();
		SokletSimulator.run(sseConfig(new Events(), 0).responseMarshaler(marshaler).build(), simulator -> {
			SseRequestResult result = simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events"));
			assertInstanceOf(SseRequestResult.RequestFailed.class, result);
			assertEquals(500, ((SseRequestResult.RequestFailed) result).getHttpRequestResult().getMarshaledResponse().getStatusCode());
		});
		int port = TestSupport.findFreePort();
		try (Soklet owner = Soklet.fromConfig(sseConfig(new Events(), port).responseMarshaler(marshaler).build())) {
			owner.start();
			assertTrue(exchange(port, "GET /events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 500 "));
		}
	}

	@Test
	void simulatedSseRejectionCleansAnUnsupportedReplacementAndObservesLogicalHeaders(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("body"), "body");
		for (boolean stream : List.of(false, true)) try (FileChannel channel = FileChannel.open(file)) {
			AtomicReference<MarshaledResponse> observed = new AtomicReference<>();
			ResponseMarshaler marshaler = ResponseMarshaler.builder().resourceMethodHandler((request, logical, method) -> {
				MarshaledResponse response = MarshaledResponse.withStatusCode(403).headers(Map.of("Connection", List.of("keep-alive")))
						.body(channel, 0L, 4L, true).build();
				return stream ? response.copy().withoutBody().stream(output -> {}).finish() : response;
			}).build();
			Events events = new Events(); events.accept = false;
			SokletSimulator.run(sseConfig(events, 0).responseMarshaler(marshaler).lifecycleObserver(new LifecycleObserver() {
				@Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method,
						MarshaledResponse response, Duration duration) { observed.set(response); }
				@Override public void didReceiveLogEvent(LogEvent event) {}
			}).build(), simulator -> {
				SseRequestResult result = simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events"));
				assertNotNull(result);
				assertFalse(channel.isOpen());
				assertEquals(stream ? 500 : 403, observed.get().getStatusCode());
				if (!stream) assertEquals(List.of("keep-alive"), observed.get().getHeaders().get("Connection"));
			});
		}
	}

	@Test
	void simulatedSseCapacityReplacementRetainsAnOwnedPredecessorThroughCapture(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("body"), "body");
		AtomicInteger selected = new AtomicInteger();
		try (FileChannel original = FileChannel.open(file);
				ResponseFinalizationReleaseTests.DelegatingChannel wrapper = new ResponseFinalizationReleaseTests.DelegatingChannel(original)) {
			SokletConfig config = sseConfig(new Events(), 0)
					.sseServer(SseServer.withPort(0).streamingLifecycleCapacity(1).build())
					.responseMarshaler(ResponseMarshaler.builder().serviceUnavailableHandler((request, method) ->
							MarshaledResponse.withStatusCode(503).body(wrapper, 0L, 4L, false).build()).build())
					.requestInterceptor(new RequestInterceptor() {
						@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
								Function<Request, MarshaledResponse> generate, Consumer<MarshaledResponse> write) {
							MarshaledResponse response = generate.apply(request);
							if (selected.incrementAndGet() == 2)
								response = response.copy().body(original, 0L, 4L, true).withoutBody().finish();
							write.accept(response);
						}
					}).build();
			SokletSimulator.run(config, simulator -> {
				try (var accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
						simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")))) {
					var rejected = assertInstanceOf(SseRequestResult.RequestFailed.class,
							simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")));
					assertEquals(503, rejected.getHttpRequestResult().getMarshaledResponse().getStatusCode());
					assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), rejected.getHttpRequestResult().getMarshaledResponse().bodyBytesOrEmpty());
					assertFalse(original.isOpen());
					assertTrue(wrapper.isOpen(), "The borrowed wrapper remains caller-owned");
				}
			});
		}
	}

	@Test
	void emergencyHandlerDeadlineBypassesApplicationFinalization() throws Exception {
		Resource resource = new Resource(); resource.block = new CountDownLatch(1);
		AtomicInteger postprocessed = new AtomicInteger();
		AtomicInteger unavailable = new AtomicInteger();
		int port = TestSupport.findFreePort();
		HttpServer server = HttpServer.withPort(port).host("127.0.0.1")
				.requestHandlerTimeout(Duration.ofMillis(200)).build();
		try (Soklet owner = Soklet.fromConfig(config(resource, server).responseMarshaler(ResponseMarshaler.builder()
				.postProcessor(response -> { postprocessed.incrementAndGet(); return response; })
				.serviceUnavailableHandler((request, method) -> { unavailable.incrementAndGet(); return MarshaledResponse.fromStatusCode(503); })
				.build()).build())) {
			owner.start();
			try {
				String wire = exchange(port, "GET /body HTTP/1.1\r\nHost: localhost\r\nOrigin: https://example.test\r\n\r\n");
				assertTrue(wire.startsWith("HTTP/1.1 503 "), wire);
				assertEquals(0, postprocessed.get());
				assertEquals(0, unavailable.get());
			} finally { resource.block.countDown(); }
		}
	}

	@Test
	void emergencyQueueRejectionBypassesApplicationFinalization() throws Exception {
		Resource resource = new Resource(); resource.block = new CountDownLatch(1);
		AtomicInteger postprocessed = new AtomicInteger();
		int port = TestSupport.findFreePort();
		try (ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1))) {
			HttpServer server = HttpServer.withPort(port).host("127.0.0.1").requestHandlerTimeout(Duration.ofSeconds(10))
					.requestHandlerExecutorServiceSupplier(() -> workers).build();
			try (Soklet owner = Soklet.fromConfig(config(resource, server).responseMarshaler(prefixing(postprocessed)).build())) {
				owner.start();
				try (Socket first = new Socket("127.0.0.1", port); Socket second = new Socket("127.0.0.1", port)) {
					String request = "GET /body HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
					first.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
					assertTrue(resource.entered.await(3, TimeUnit.SECONDS));
					second.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
					long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
					while (workers.getQueue().isEmpty() && System.nanoTime() < until) Thread.sleep(5);
					assertEquals(1, workers.getQueue().size());
					String wire = exchange(port, request);
					assertTrue(wire.startsWith("HTTP/1.1 503 "), wire);
					assertEquals(0, postprocessed.get());
				} finally { resource.block.countDown(); }
			}
		}
	}

	@Test
	void lateAndAsynchronousFirstWrapConsumerCannotDispatchAfterFailure() throws Exception {
		for (boolean beforeReturn : List.of(false, true)) {
			AtomicReference<Consumer<Request>> saved = new AtomicReference<>();
			AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
			Resource resource = new Resource();
			SokletSimulator.run(config(resource, 0).requestInterceptor(new RequestInterceptor() {
				@Override public void wrapRequest(ServerType type, Request request, Consumer<Request> process) {
					saved.set(process);
					if (beforeReturn) {
						Thread other = Thread.ofPlatform().start(() -> { try { process.accept(request); } catch (Throwable failure) { asyncFailure.set(failure); } });
						try { other.join(3000); assertFalse(other.isAlive()); } catch (InterruptedException failure) { throw new AssertionError(failure); }
					}
				}
			}).build(), simulator -> {
				assertEquals(500, simulator.performHttpRequest(request(HttpMethod.GET)).getMarshaledResponse().getStatusCode());
				assertEquals(0, resource.calls.get());
				assertThrows(IllegalStateException.class, () -> saved.get().accept(request(HttpMethod.GET)));
				assertEquals(0, resource.calls.get());
				if (beforeReturn) assertInstanceOf(IllegalStateException.class, asyncFailure.get());
			});
		}
	}

	private static ResponseMarshaler prefixing(AtomicInteger calls) {
		return ResponseMarshaler.builder().postProcessor(response -> {
			calls.incrementAndGet();
			return response.copy().body(("post:" + new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8))
					.cookies(cookies -> cookies.add(ResponseCookie.withName("post").value("once").build())).finish();
		}).build();
	}
	private static ResponseMarshaler forwarding(ResponseMarshaler delegate) {
		return (ResponseMarshaler) Proxy.newProxyInstance(ResponseMarshaler.class.getClassLoader(), new Class<?>[]{ResponseMarshaler.class},
				(proxy, method, arguments) -> { try { return method.invoke(delegate, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); } });
	}
	private static Request request(HttpMethod method) { return Request.fromPath(method, "/body"); }
	private static SokletConfig.Builder config(Resource resource, int port) { return config(resource, HttpServer.withPort(port).host("127.0.0.1").build()); }
	private static SokletConfig.Builder config(Resource resource, HttpServer server) {
		return SokletConfig.withHttpServer(server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) { return type.cast(resource); } })
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(2)).build());
	}
	private static SokletConfig.Builder sseConfig(Events events, int port) {
		return SokletConfig.withSseServer(SseServer.withPort(port).host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Events.class)))
				.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) { return type.cast(events); } })
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(2)).build());
	}
	private static String exchange(int port, String request) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5000); socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
			return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
	public static final class Resource {
		final AtomicInteger calls = new AtomicInteger();
		final CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch block;
		MarshaledResponse response = MarshaledResponse.withStatusCode(200).headers(Map.of("Content-Type", List.of("text/plain")))
				.body("body".getBytes(StandardCharsets.UTF_8)).build();
		@GET("/body") public MarshaledResponse body() {
			calls.incrementAndGet(); entered.countDown();
			if (block != null) {
				boolean interrupted = false;
				while (true) { try { block.await(); break; } catch (InterruptedException failure) { interrupted = true; } }
				if (interrupted) Thread.currentThread().interrupt();
			}
			return response;
		}
	}
	public static final class Events {
		boolean accept = true;
		@SseEventSource("/events") public SseHandshakeResult events() { return accept ? SseHandshakeResult.accept() : SseHandshakeResult.rejectWithResponse(Response.fromStatusCode(403)); }
	}
}
