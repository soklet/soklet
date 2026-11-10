/*
* Copyright 2022-2026 Revetware LLC.
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
*/
package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.OPTIONS;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class OptionsMissingRouteTests {
	@Test
	void unknownPathsAndRoutesOnAnotherListenerUseNotFound() {
		SokletSimulator.run(simulatorConfig(ResponseMarshaler.defaultInstance(), CorsAuthorizer.rejectAllInstance()), simulator -> {
			for (String path : List.of("/missing", "/events")) {
				HttpRequestResult result = simulator.performHttpRequest(Request.fromPath(HttpMethod.OPTIONS, path));
				assertEquals(404, result.getMarshaledResponse().getStatusCode());
				assertFalse(result.getMarshaledResponse().getHeaders().containsKey("Allow"));
				assertTrue(result.getResourceMethod().isEmpty());
			}
			for (String path : List.of("/missing", "/known")) {
				SseRequestResult.RequestFailed result = assertInstanceOf(SseRequestResult.RequestFailed.class,
						simulator.performSseRequest(Request.fromPath(HttpMethod.OPTIONS, path)));
				assertEquals(404, result.getHttpRequestResult().getMarshaledResponse().getStatusCode());
			}
			SseRequestResult.RequestFailed known = assertInstanceOf(SseRequestResult.RequestFailed.class,
					simulator.performSseRequest(Request.fromPath(HttpMethod.OPTIONS, "/events")));
			assertEquals(204, known.getHttpRequestResult().getMarshaledResponse().getStatusCode());
			assertEquals(Set.of("GET", "OPTIONS"), Set.copyOf(known.getHttpRequestResult().getMarshaledResponse().getHeaders().get("Allow")));
		});
	}

	@Test
	void automaticExplicitAndServerWideOptionsKeepTheirOwnHandlers() {
		AtomicInteger notFound = new AtomicInteger();
		AtomicInteger automatic = new AtomicInteger();
		ResponseMarshaler marshaler = ResponseMarshaler.builder()
				.notFoundHandler(request -> {
					notFound.incrementAndGet();
					return MarshaledResponse.withStatusCode(404).headers(Map.of("X-Handler", List.of("not-found"))).build();
				}).optionsHandler((request, methods) -> {
					automatic.incrementAndGet();
					assertEquals(Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS), methods);
					return MarshaledResponse.withStatusCode(204).headers(Map.of("X-Handler", List.of("options"))).build();
				}).build();
		SokletSimulator.run(simulatorConfig(marshaler, CorsAuthorizer.rejectAllInstance()), simulator -> {
			assertEquals(List.of("not-found"), simulator.performHttpRequest(Request.fromPath(HttpMethod.OPTIONS, "/missing"))
					.getMarshaledResponse().getHeaders().get("X-Handler"));
			assertEquals(1, notFound.get());
			assertEquals(0, automatic.get());
			assertEquals(List.of("options"), simulator.performHttpRequest(Request.fromPath(HttpMethod.OPTIONS, "/known"))
					.getMarshaledResponse().getHeaders().get("X-Handler"));
			assertEquals(202, simulator.performHttpRequest(Request.fromPath(HttpMethod.OPTIONS, "/explicit"))
					.getMarshaledResponse().getStatusCode());
			assertEquals(200, simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.OPTIONS, "*"))
					.getMarshaledResponse().getStatusCode());
			assertEquals(1, notFound.get());
			assertEquals(1, automatic.get());
		});
	}

	@Test
	void corsPreflightStillUsesItsAuthorizerEvenForAnUnknownPath() {
		for (boolean allow : List.of(false, true)) {
			SokletSimulator.run(simulatorConfig(ResponseMarshaler.defaultInstance(),
					allow ? CorsAuthorizer.acceptAllInstance() : CorsAuthorizer.rejectAllInstance()), simulator -> {
				for (String path : List.of("/missing", "/known", "/explicit")) {
					Request preflight = Request.withPath(HttpMethod.OPTIONS, path).headers(Map.of(
							"Origin", List.of("https://example.com"), "Access-Control-Request-Method", List.of("GET"))).build();
					HttpRequestResult result = simulator.performHttpRequest(preflight);
					assertEquals(allow ? 204 : 403, result.getMarshaledResponse().getStatusCode());
				}
				assertEquals(404, simulator.performHttpRequest(Request.fromPath(HttpMethod.OPTIONS, "/missing"))
						.getMarshaledResponse().getStatusCode());
			});
		}
	}

	@Test
	void realHttpRequestsDistinguishMissingRoutesFromOptionsCapabilities() throws Exception {
		int port;
		int ssePort;
		try (ServerSocket httpSocket = new ServerSocket(0); ServerSocket sseSocket = new ServerSocket(0)) {
			port = httpSocket.getLocalPort();
			ssePort = sseSocket.getLocalPort();
		}
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1").build())
				.sseServer(SseServer.withPort(ssePort).host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Routes.class)))
				.lifecycleObservers(List.of()).jvmShutdownHookEnabled(false)
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1))
						.build()).build();
		ShutdownResult shutdown = Soklet.run(config, () -> {
			for (String path : List.of("/missing", "/events"))
				assertTrue(wire(port, path).startsWith("HTTP/1.1 404 "));
			String known = wire(port, "/known");
			assertTrue(known.startsWith("HTTP/1.1 204 "), known);
			assertTrue(known.contains("Allow:"), known);
			assertTrue(wire(port, "/explicit").startsWith("HTTP/1.1 202 "));
			assertTrue(wire(port, "*").startsWith("HTTP/1.1 200 "));
			assertTrue(wire(ssePort, "/missing").startsWith("HTTP/1.1 404 "));
			assertTrue(wire(ssePort, "/known").startsWith("HTTP/1.1 404 "));
			assertTrue(wire(ssePort, "/events").startsWith("HTTP/1.1 204 "));
		});
		assertTrue(shutdown.isComplete());
	}

	private static String wire(int port, String target) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
			socket.getOutputStream().write(("OPTIONS " + target
					+ " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			ByteArrayOutputStream response = new ByteArrayOutputStream();
			byte[] buffer = new byte[1_024];
			while (true) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0L) throw new SocketTimeoutException("OPTIONS response deadline exceeded");
				socket.setSoTimeout((int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
				int count = socket.getInputStream().read(buffer);
				if (count < 0) break;
				response.write(buffer, 0, count);
			}
			return response.toString(StandardCharsets.ISO_8859_1);
		} catch (Exception exception) { throw new AssertionError(exception); }
	}

	private static SimulatorConfig simulatorConfig(ResponseMarshaler marshaler, CorsAuthorizer cors) {
		return SimulatorConfig.builder().httpServer().sseServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Routes.class)))
				.responseMarshaler(marshaler).corsAuthorizer(cors).lifecycleObservers(List.of())
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
	}

	public static class Routes {
		@GET("/known") public String known() { return "ok"; }
		@OPTIONS("/explicit") public Response explicit() { return Response.fromStatusCode(202); }
		@SseEventSource("/events") public SseHandshakeResult events() { return SseHandshakeResult.accept(); }
	}
}
