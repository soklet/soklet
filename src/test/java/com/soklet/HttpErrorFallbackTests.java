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
import org.junit.jupiter.api.io.TempDir;

import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class HttpErrorFallbackTests {
	@Test
	void nullThrowableResponseAlwaysSelectsAFiniteFallback() {
		AtomicInteger postProcesses = new AtomicInteger();
		RecordingObserver observer = new RecordingObserver();
		DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
		ShutdownResult shutdown = SokletSimulator.run(config().requestInterceptor(new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generate, Consumer<MarshaledResponse> write) {
				if (request.getPath().equals("/throws")) throw new IllegalStateException("interceptor");
				if (request.getPath().equals("/resource")) write.accept(generate.apply(request));
				// /missing deliberately omits the required writer callback.
			}
		}).responseMarshaler(ResponseMarshaler.builder().throwableHandler((request, failure, method) -> null)
				.postProcessor(response -> { postProcesses.incrementAndGet(); return response; }).build())
				.lifecycleObserver(observer).metricsCollector(metrics).build(), simulator -> {
			for (String path : List.of("/throws", "/missing", "/resource")) {
				MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, path)).getMarshaledResponse();
				assertFallback(response);
				assertEquals(0L, metrics.snapshot().orElseThrow().getActiveRequests());
			}
			MarshaledResponse head = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/throws")).getMarshaledResponse();
			assertEquals(500, head.getStatusCode());
			assertTrue(head.getBody().isEmpty());
			assertEquals(List.of(String.valueOf("HTTP 500: Internal Server Error".getBytes(StandardCharsets.UTF_8).length)), head.getHeaders().get("Content-Length"));
			assertEquals(4, postProcesses.get());
			observer.assertCompleted(4);
		});
		assertTrue(shutdown.isComplete());
		assertEquals(ShutdownDisposition.GRACEFUL, shutdown.getShutdownDisposition());
	}

	@Test
	void recoveryLoggingDoesNotRenderApplicationThrowables() {
		PoisonedFailure failure = new PoisonedFailure();
		RecordingObserver observer = new RecordingObserver();
		ShutdownResult shutdown = SokletSimulator.run(config().requestInterceptor(new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generate, Consumer<MarshaledResponse> write) {
				if (request.getPath().equals("/throws")) throw failure;
				write.accept(generate.apply(request));
			}
		}).responseMarshaler(ResponseMarshaler.builder().resourceMethodHandler((request, response, method) -> { throw failure; }).throwableHandler((request, error, method) -> {
			assertSame(failure, error);
			error.getMessage();
			throw new AssertionError("Unreachable");
		}).build()).lifecycleObserver(observer).build(), simulator -> {
			assertFallback(simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/throws")).getMarshaledResponse());
			assertFallback(simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/healthy")).getMarshaledResponse());
			assertEquals(0, failure.renderCalls.get());
			assertEquals(2, failure.messageCalls.get());
			assertTrue(observer.failures.contains(failure));
			observer.assertCompleted(2);
		});
		assertTrue(shutdown.isComplete());
	}

	@Test
	void failingFallbackPostProcessorRunsOnceAndStillProducesAFiniteResponse() {
		AtomicInteger postProcesses = new AtomicInteger();
		AtomicInteger throwableCalls = new AtomicInteger();
		IllegalStateException postFailure = new IllegalStateException("postprocessor");
		RecordingObserver observer = new RecordingObserver();
		ShutdownResult shutdown = SokletSimulator.run(config().requestInterceptor(throwingInterceptor())
				.responseMarshaler(ResponseMarshaler.builder().throwableHandler((request, failure, method) -> {
					throwableCalls.incrementAndGet(); return null;
				}).postProcessor(response -> { postProcesses.incrementAndGet(); throw postFailure; }).build())
				.lifecycleObserver(observer).build(), simulator -> {
			assertFallback(simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/throws")).getMarshaledResponse());
			assertEquals(1, postProcesses.get());
			assertEquals(2, throwableCalls.get());
			assertTrue(observer.failures.contains(postFailure));
			observer.assertCompleted(1);
		});
		assertTrue(shutdown.isComplete());
	}

	@Test
	void nullErrorRecoveryReleasesAbandonedOwnedBodiesButPreservesBorrowedBodies(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("response"), "body");
		RecordingObserver observer = new RecordingObserver();
		try (FileChannel owned = FileChannel.open(path); FileChannel borrowed = FileChannel.open(path)) {
			ShutdownResult shutdown = SokletSimulator.run(config().requestInterceptor(new RequestInterceptor() {
				@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
						Function<Request, MarshaledResponse> generate, Consumer<MarshaledResponse> write) {
					boolean takeOwnership = request.getPath().equals("/owned");
					write.accept(MarshaledResponse.withStatusCode(200)
							.body(takeOwnership ? owned : borrowed, 0L, 4L, takeOwnership).build());
					throw new IllegalStateException("after response selection");
				}
			}).responseMarshaler(ResponseMarshaler.builder().throwableHandler((request, failure, method) -> null).build())
					.lifecycleObserver(observer).build(), simulator -> {
				assertFallback(simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/owned")).getMarshaledResponse());
				assertFalse(owned.isOpen());
				assertFallback(simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/borrowed")).getMarshaledResponse());
				assertTrue(borrowed.isOpen());
				observer.assertCompleted(2);
			});
			assertTrue(shutdown.isComplete());
		}
	}

	@Test
	void successfulCustomErrorResponseRemainsSelectedAndFinalizedOnce() {
		AtomicInteger calls = new AtomicInteger();
		RecordingObserver observer = new RecordingObserver();
		ShutdownResult shutdown = SokletSimulator.run(config().requestInterceptor(throwingInterceptor())
				.responseMarshaler(ResponseMarshaler.builder().throwableHandler((request, failure, method) ->
						MarshaledResponse.withStatusCode(422).body("custom".getBytes(StandardCharsets.UTF_8)).build())
						.postProcessor(response -> { calls.incrementAndGet(); return response; }).build())
				.lifecycleObserver(observer).build(), simulator -> {
			MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/throws")).getMarshaledResponse();
			assertEquals(422, response.getStatusCode());
			assertEquals("custom", new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
			assertEquals(1, calls.get());
			observer.assertCompleted(1);
		});
		assertTrue(shutdown.isComplete());
	}

	@Test
	void liveFallbackIsWrittenBeforeHandlerTimeoutAndShutdownRemainsGraceful() throws Exception {
		int port;
		try (java.net.ServerSocket reservation = new java.net.ServerSocket(0)) { port = reservation.getLocalPort(); }
		DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
		RecordingObserver observer = new RecordingObserver();
		HttpServer server = HttpServer.withPort(port).host("127.0.0.1").requestHandlerTimeout(Duration.ofSeconds(5)).build();
		ShutdownResult shutdown = Soklet.run(SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class))).lifecyclePolicy(policy())
				.requestInterceptor(throwingInterceptor()).metricsCollector(metrics).lifecycleObserver(observer)
				.responseMarshaler(ResponseMarshaler.builder().throwableHandler((request, failure, method) -> null).build()).build(), () -> {
			try (Socket socket = new Socket()) {
				socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
				socket.setSoTimeout(2_000);
				socket.getOutputStream().write("GET /throws HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				assertTrue(wire.startsWith("HTTP/1.1 500 "), wire);
				assertTrue(wire.endsWith("HTTP 500: Internal Server Error"), wire);
			}
		});
		assertTrue(shutdown.isComplete());
		assertEquals(ShutdownDisposition.GRACEFUL, shutdown.getShutdownDisposition());
		assertEquals(0L, metrics.snapshot().orElseThrow().getActiveRequests());
		observer.assertCompleted(1);
	}

	private static SimulatorConfig.Builder config() {
		return SimulatorConfig.builder().httpServer().resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecyclePolicy(policy());
	}
	private static LifecyclePolicy policy() {
		return LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
				.gracefulShutdownTimeout(Duration.ofSeconds(2)).forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	}
	private static RequestInterceptor throwingInterceptor() {
		return new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generate, Consumer<MarshaledResponse> write) {
				throw new IllegalStateException("interceptor");
			}
		};
	}
	private static void assertFallback(MarshaledResponse response) {
		assertEquals(500, response.getStatusCode());
		assertEquals("HTTP 500: Internal Server Error", new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
		assertFalse(response.isStreaming());
	}
	public static class Resource {
		@GET("/resource") public String fail() { throw new IllegalStateException("resource"); }
		@GET("/healthy") public String healthy() { return "healthy"; }
	}
	private static final class PoisonedFailure extends RuntimeException {
		private final AtomicInteger renderCalls = new AtomicInteger();
		private final AtomicInteger messageCalls = new AtomicInteger();
		@Override public String getMessage() { messageCalls.incrementAndGet(); throw new IllegalStateException("message failed"); }
		@Override public String toString() { renderCalls.incrementAndGet(); throw new IllegalStateException("render failed"); }
	}
	private static final class RecordingObserver implements LifecycleObserver {
		private final AtomicInteger starts = new AtomicInteger();
		private final AtomicInteger writes = new AtomicInteger();
		private final AtomicInteger finishes = new AtomicInteger();
		private final AtomicInteger failedWrites = new AtomicInteger();
		private final List<Throwable> failures = new CopyOnWriteArrayList<>();
		@Override public void didReceiveLogEvent(LogEvent event) {}
		@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) { starts.incrementAndGet(); }
		@Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) { assertNotNull(response); writes.incrementAndGet(); }
		@Override public void didFailToWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration, Throwable failure) { failedWrites.incrementAndGet(); }
		@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration, List<Throwable> throwables) {
			assertNotNull(response); failures.addAll(throwables); finishes.incrementAndGet();
		}
		private void assertCompleted(int expected) {
			assertEquals(expected, starts.get()); assertEquals(expected, writes.get()); assertEquals(expected, finishes.get()); assertEquals(0, failedWrites.get());
		}
	}
}
