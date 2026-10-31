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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class InterceptorDefaultFinalizationTests {
	@Test
	void freshResponsesReceiveCorsDateAndHeadHandlingWithoutAPostprocessor() {
		for (boolean generate : List.of(false, true)) {
			AtomicInteger headCalls = new AtomicInteger();
			ResponseMarshaler marshaler = ResponseMarshaler.builder().headHandler((request, response) -> {
				headCalls.incrementAndGet();
				return response.copy().withoutBody()
						.headers(headers -> headers.put("X-Head-Handler", List.of("ran"))).finish();
			}).build();
			SokletSimulator.run(SimulatorConfig.builder().httpServer().lifecyclePolicy(testPolicy()).responseMarshaler(marshaler)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HttpResource.class)))
					.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
					.requestInterceptor(replacing(generate, false)).build(), simulator -> {
				for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
					MarshaledResponse response = simulator.performHttpRequest(Request.withPath(method, "/body")
							.headers(Map.of("Origin", List.of("https://example.test"))).build()).getMarshaledResponse();
					assertEquals(200, response.getStatusCode());
					assertEquals(List.of("https://example.test"), response.getHeaders().get("Access-Control-Allow-Origin"));
					assertTrue(response.getHeaders().get("Vary").contains("Origin"));
					assertTrue(HttpDate.fromHeaderValue(response.getHeaders().get("Date").getFirst()).isPresent());
					assertEquals(List.of("11"), response.getHeaders().get("Content-Length"));
					if (method == HttpMethod.HEAD) {
						assertTrue(response.getBody().isEmpty());
						assertEquals(List.of("ran"), response.getHeaders().get("X-Head-Handler"));
						assertEquals(1, headCalls.get());
					} else {
						assertArrayEquals("replacement".getBytes(StandardCharsets.UTF_8), response.bodyBytesOrEmpty());
						assertEquals(0, headCalls.get());
					}
				}
			});
		}
	}

	@Test
	void freshAcceptedSseResponseReceivesCorsWithoutFiniteFraming() {
		SokletSimulator.run(SimulatorConfig.builder().sseServer().lifecyclePolicy(testPolicy())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SseResource.class)))
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.requestInterceptor(replacing(true, true)).build(), simulator -> {
			try (var result = assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
					simulator.performSseRequest(Request.withPath(HttpMethod.GET, "/events")
						.headers(Map.of("Origin", List.of("https://example.test"))).build()))) {
				MarshaledResponse response = result.getHttpRequestResult().getMarshaledResponse();
				assertEquals(List.of("https://example.test"), response.getHeaders().get("Access-Control-Allow-Origin"));
				assertTrue(response.getHeaders().get("Vary").contains("Origin"));
				assertTrue(response.getHeaders().containsKey("Date"));
				assertFalse(response.getHeaders().containsKey("Content-Length"));
				assertFalse(response.getHeaders().containsKey("Transfer-Encoding"));
			}
		});
	}

	private static LifecyclePolicy testPolicy() {
		return LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
				.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(2)).build();
	}

	private static RequestInterceptor replacing(boolean generate, boolean sse) {
		return new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
				if (generate) generator.apply(request);
				writer.accept(sse ? MarshaledResponse.withStatusCode(200)
						.headers(Map.of("Content-Type", List.of("text/event-stream"))).build()
						: MarshaledResponse.withStatusCode(200).body("replacement".getBytes(StandardCharsets.UTF_8)).build());
			}
		};
	}

	public static class HttpResource {
		@GET("/body") public String body() { return "original"; }
	}

	public static class SseResource {
		@SseEventSource("/events") public SseHandshakeResult events() { return SseHandshakeResult.accept(); }
	}
}
