package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.OPTIONS;
import com.soklet.annotation.POST;
import com.soklet.annotation.PUT;
import com.soklet.annotation.PathParameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class DeepDiveRoutingSimulatorRegressionTests {
	public static class Routes {
		static final AtomicInteger optionsCalls = new AtomicInteger();
		static final AtomicInteger streamCalls = new AtomicInteger();
		@OPTIONS("/preflight") public String options() { optionsCalls.incrementAndGet(); return "options"; }
		@PUT("/preflight") public String put() { return "updated"; }
		@POST("/only-post") public String post() { return "posted"; }
		@GET("/items/{id}") public String item(@PathParameter String id) { return id; }
		@GET("/stream") public MarshaledResponse stream() {
			return MarshaledResponse.withStatusCode(200).stream(output -> {
				streamCalls.incrementAndGet(); output.write("streamed".getBytes(StandardCharsets.UTF_8));
			}).build();
		}
	}

	@Test
	void explicitOptionsHandlersDoNotBypassCorsPreflightAuthorization() {
		Routes.optionsCalls.set(0);
		SokletSimulator.run(config().corsAuthorizer(CorsAuthorizer.fromWhitelistedOrigins(Set.of("https://allowed.example"))).build(), simulator -> {
			for (String origin : List.of("https://allowed.example", "https://denied.example")) {
				HttpRequestResult result = simulator.performHttpRequest(Request.withPath(HttpMethod.OPTIONS, "/preflight")
						.headers(Map.of("Origin", List.of(origin), "Access-Control-Request-Method", List.of("PUT"))).build());
				if (origin.contains("allowed")) {
					assertEquals(204, result.getMarshaledResponse().getStatusCode());
					assertEquals(List.of(origin), result.getMarshaledResponse().getHeaders().get("Access-Control-Allow-Origin"));
				} else {
					assertEquals(403, result.getMarshaledResponse().getStatusCode());
					assertNull(result.getMarshaledResponse().getHeaders().get("Access-Control-Allow-Origin"));
				}
			}
			assertEquals(0, Routes.optionsCalls.get());
			assertEquals(200, simulator.performHttpRequest(Request.fromPath(HttpMethod.OPTIONS, "/preflight")).getMarshaledResponse().getStatusCode());
			assertEquals(1, Routes.optionsCalls.get());
		});
	}

	@Test
	void headWithoutGetUsesMethodNotAllowedWhenTheResourceExists() {
		SokletSimulator.run(config().build(), simulator -> {
			MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/only-post")).getMarshaledResponse();
			assertEquals(405, response.getStatusCode());
			assertTrue(response.getHeaders().get("Allow").stream().anyMatch(value -> value.contains("POST")));
			assertTrue(response.getBody().isEmpty());
			assertEquals(404, simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/absent")).getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	void methodDiscoveryPreservesQuestionMarksInDecodedPaths() {
		SokletSimulator.run(config().build(), simulator -> {
			assertEquals(200, simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.GET, "/items/a%3Fb")).getMarshaledResponse().getStatusCode());
			MarshaledResponse rejected = simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.POST, "/items/a%3Fb")).getMarshaledResponse();
			assertEquals(405, rejected.getStatusCode());
			assertTrue(rejected.getHeaders().get("Allow").stream().anyMatch(value -> value.contains("GET")));
			assertEquals(204, simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.OPTIONS, "/items/a%3Fb")).getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	void simulatedHeadNeverInvokesStreamingProducersWhenCustomHeadHandlerKeepsTheBody() {
		Routes.streamCalls.set(0);
		SokletSimulator.run(config().responseMarshaler(ResponseMarshaler.builder().headHandler((request, response) -> response).build()).build(), simulator -> {
			MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/stream")).getMarshaledResponse();
			assertEquals(200, response.getStatusCode());
			assertFalse(response.isStreaming());
			assertTrue(response.getBody().isEmpty());
			assertFalse(response.getHeaders().containsKey("Content-Length"));
			assertEquals(0, Routes.streamCalls.get());
		});
	}

	@Test
	void simulatedInterceptorResponsesHonorHeadAndBodylessStatusRules() {
		AtomicInteger producers = new AtomicInteger();
		for (int status : List.of(200, 204, 205, 304)) {
			for (boolean streaming : List.of(false, true)) {
				if (streaming && status != 200) continue; // Builders reject streams for bodyless statuses.
				SokletSimulator.run(config().requestInterceptor(new RequestInterceptor() {
					@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
							Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
						MarshaledResponse.Builder builder = MarshaledResponse.withStatusCode(status);
						if (streaming) builder.stream(output -> { producers.incrementAndGet(); output.write(new byte[]{1, 2, 3}); });
						else builder.body(new byte[]{1, 2, 3});
						writer.accept(builder.build());
					}
				}).build(), simulator -> {
					MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/items/a")).getMarshaledResponse();
					assertEquals(status, response.getStatusCode());
					assertTrue(response.getBody().isEmpty());
					assertFalse(response.isStreaming());
					if (status == 205) assertEquals(List.of("0"), response.getHeaders().get("Content-Length"));
					else if (status != 200 || streaming) assertFalse(response.getHeaders().containsKey("Content-Length"));
					else assertEquals(List.of("3"), response.getHeaders().get("Content-Length"));
					if (status != 200) {
						MarshaledResponse get = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/items/a")).getMarshaledResponse();
						assertTrue(get.getBody().isEmpty()); assertFalse(get.isStreaming());
					}
				});
			}
		}
		assertEquals(0, producers.get());
	}

	private static SimulatorConfig.Builder config() {
		return SimulatorConfig.builder().httpServer().resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Routes.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(java.time.Duration.ofSeconds(1))
						.startupCancelationTimeout(java.time.Duration.ofSeconds(1))
						.gracefulShutdownTimeout(java.time.Duration.ofSeconds(1)).forcedShutdownTimeout(java.time.Duration.ofSeconds(1)).build())
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) { } });
	}
}
