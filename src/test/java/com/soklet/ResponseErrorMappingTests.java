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
import com.soklet.exception.IllegalRequestBodyException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Set;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResponseErrorMappingTests {
	@Test
	public void runtime_exception_maps_to_500() {
		SokletSimulator.run(SimulatorConfig.builder().httpServer().sseServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ExplodeResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build(), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/explode").build());
			Assertions.assertEquals(500, result.getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void bad_request_exception_maps_to_400() {
		SokletSimulator.run(SimulatorConfig.builder().httpServer().sseServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ExplodeResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build(), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/bad-request").build());
			Assertions.assertEquals(400, result.getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void bodyless_response_with_body_maps_to_500() {
		SokletSimulator.run(SimulatorConfig.builder().httpServer().sseServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ExplodeResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build(), simulator -> {
			for (String path : Set.of("/bodyless", "/reset-with-body", "/reset-marshaled-with-body")) {
				HttpRequestResult result = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, path).build());
				Assertions.assertEquals(500, result.getMarshaledResponse().getStatusCode(), path);
			}
		});
	}

	@Test
	public void emptyResetContentResourceBodiesAreNormalized() {
		SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ExplodeResource.class))).build(), simulator -> {
			for (String path : Set.of("/reset-empty-string", "/reset-empty-bytes", "/reset-empty-marshaled")) {
				for (HttpMethod method : Set.of(HttpMethod.GET, HttpMethod.HEAD)) {
					MarshaledResponse result = simulator.performHttpRequest(Request.fromPath(method, path)).getMarshaledResponse();
					Assertions.assertEquals(205, result.getStatusCode(), path);
					Assertions.assertTrue(result.getBody().isEmpty(), path);
					Assertions.assertEquals(java.util.List.of("0"), result.getHeaders().get("Content-Length"), path);
				}
			}
		});
	}

	@Test
	public void bodylessMarshalerHooksRetainTheirStatus() {
		java.util.function.Function<Request, MarshaledResponse> result = request -> MarshaledResponse
				.withStatusCode(Integer.parseInt(request.getHeader("X-Status").orElseThrow()))
				.body(new byte[Integer.parseInt(request.getHeader("X-Length").orElseThrow())]).build();
		ResponseMarshaler marshaler = ResponseMarshaler.builder()
				.throwableHandler((request, failure, method) -> result.apply(request))
				.optionsHandler((request, methods) -> result.apply(request))
				.notFoundHandler(result::apply)
				.methodNotAllowedHandler((request, methods) -> result.apply(request))
				.corsPreflightAllowedHandler((request, preflight, allowed) -> result.apply(request)).build();
		SokletSimulator.run(SimulatorConfig.builder().httpServer().responseMarshaler(marshaler)
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ExplodeResource.class))).build(), simulator -> {
			for (int status : new int[]{204, 205, 304}) {
				for (int length : new int[]{0, 1}) {
					var headers = java.util.Map.of("X-Status", java.util.List.of(Integer.toString(status)),
							"X-Length", java.util.List.of(Integer.toString(length)));
					var corsHeaders = new java.util.HashMap<>(headers);
					corsHeaders.put("Origin", java.util.List.of("https://example.com"));
					corsHeaders.put("Access-Control-Request-Method", java.util.List.of("GET"));
					for (Request request : java.util.List.of(
							Request.withPath(HttpMethod.GET, "/explode").headers(headers).build(),
							Request.withPath(HttpMethod.OPTIONS, "/explode").headers(headers).build(),
							Request.withPath(HttpMethod.GET, "/missing").headers(headers).build(),
							Request.withPath(HttpMethod.POST, "/explode").headers(headers).build(),
							Request.withPath(HttpMethod.OPTIONS, "/explode").headers(corsHeaders).build())) {
						Assertions.assertEquals(status, simulator.performHttpRequest(request).getMarshaledResponse().getStatusCode(),
								request.getHttpMethod() + " " + request.getResourcePath());
					}
				}
			}
		});
	}

	public static class ExplodeResource {
		@GET("/reset-empty-string")
		public Response resetEmptyString() { return Response.withStatusCode(205).body("").build(); }
		@GET("/reset-empty-bytes")
		public Response resetEmptyBytes() { return Response.withStatusCode(205).body(new byte[0]).build(); }
		@GET("/reset-empty-marshaled")
		public MarshaledResponse resetEmptyMarshaled() { return MarshaledResponse.withStatusCode(205).body(new byte[0]).build(); }

		@GET("/reset-with-body")
		public Response resetWithBody() {
			return Response.withStatusCode(205).body("nope").build();
		}

		@GET("/reset-marshaled-with-body")
		public MarshaledResponse resetMarshaledWithBody() {
			return MarshaledResponse.withStatusCode(205).body(new byte[]{1}).build();
		}
		@GET("/explode")
		public String explode() {
			throw new RuntimeException("boom");
		}

		@GET("/bad-request")
		public String badRequest() {
			throw new IllegalRequestBodyException("nope");
		}

		@GET("/bodyless")
		public Response bodyless() {
			return Response.withStatusCode(204)
					.body("nope")
					.build();
		}
	}
}
