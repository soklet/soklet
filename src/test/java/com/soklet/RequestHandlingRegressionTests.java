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
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.PUT;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class RequestHandlingRegressionTests {
	private static final String ORIGIN = "https://example.com";

	@Test
	public void wrappedRequestIsUsedForInterceptAndResponseLogic() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(WrappedRequestResource.class)))
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.requestInterceptor(new RequestInterceptor() {
					@Override
					public void wrapRequest(@NonNull ServerType serverType,
																	@NonNull Request request,
																	@NonNull Consumer<Request> requestProcessor) {
						Request wrappedRequest = request.copy()
								.httpMethod(HttpMethod.HEAD)
								.headers(headers -> {
									headers.put("Origin", Set.of(ORIGIN));
									headers.put("X-Wrapped", Set.of("true"));
								})
								.finish();

						requestProcessor.accept(wrappedRequest);
					}

					@Override
					public void interceptRequest(@NonNull ServerType serverType,
																			 @NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod,
																			 @NonNull Function<Request, MarshaledResponse> responseGenerator,
																			 @NonNull Consumer<MarshaledResponse> responseWriter) {
						responseWriter.accept(responseGenerator.apply(request));
					}
				})
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/greet").build());

			MarshaledResponse response = result.getMarshaledResponse();
			assertEquals(Integer.valueOf(200), response.getStatusCode());
			Assertions.assertTrue(response.getBody().isEmpty(), "HEAD response should not include a body");
			assertEquals(Set.of("5"), response.getHeaders().get("Content-Length"));
			assertEquals(Set.of(ORIGIN), response.getHeaders().get("Access-Control-Allow-Origin"));
		});
	}

	@Test
	public void responseMarshalerRespectsCaseInsensitiveContentType() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ContentTypeResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/content-type").build());

			assertEquals(Integer.valueOf(200), result.getMarshaledResponse().getStatusCode());
			assertEquals(Set.of("application/custom"),
					result.getMarshaledResponse().getHeaders().get("Content-Type"));
		});
	}

	@Test
	public void wrapRequestMustAdvanceRequestFlow() {
		List<LogEvent> logEvents = new ArrayList<>();

		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(WrappedRequestResource.class)))
				.requestInterceptor(new RequestInterceptor() {
					@Override
					public void wrapRequest(@NonNull ServerType serverType,
																	@NonNull Request request,
																	@NonNull Consumer<Request> requestProcessor) {
						// Intentionally do not advance the request.
					}
				})
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						logEvents.add(logEvent);
					}
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/greet").build());

			assertEquals(Integer.valueOf(500), result.getMarshaledResponse().getStatusCode());
			Assertions.assertTrue(logEvents.stream()
							.anyMatch(event -> event.getLogEventType() == LogEventType.REQUEST_INTERCEPTOR_WRAP_REQUEST_FAILED),
					"Expected wrapRequest failure log event");
		});
	}

	@Test
	public void interceptRequestMustWriteResponse() {
		List<LogEvent> logEvents = new ArrayList<>();

		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(WrappedRequestResource.class)))
				.requestInterceptor(new RequestInterceptor() {
					@Override
					public void interceptRequest(@NonNull ServerType serverType,
																			 @NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod,
																			 @NonNull Function<Request, MarshaledResponse> responseGenerator,
																			 @NonNull Consumer<MarshaledResponse> responseWriter) {
						responseGenerator.apply(request);
					}
				})
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						logEvents.add(logEvent);
					}
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/greet").build());

			assertEquals(Integer.valueOf(500), result.getMarshaledResponse().getStatusCode());
			Assertions.assertTrue(logEvents.stream()
							.anyMatch(event -> event.getLogEventType() == LogEventType.REQUEST_INTERCEPTOR_INTERCEPT_REQUEST_FAILED),
					"Expected interceptRequest failure log event");
		});
	}

	@Test
	public void wrappedRequestRewritesPathAndMethod() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(WrappedRequestRewriteResource.class)))
				.requestInterceptor(new RequestInterceptor() {
					@Override
					public void wrapRequest(@NonNull ServerType serverType,
																	@NonNull Request request,
																	@NonNull Consumer<Request> requestProcessor) {
						Request wrappedRequest = request.copy()
								.httpMethod(HttpMethod.POST)
								.path("/rewrite-target")
								.finish();

						requestProcessor.accept(wrappedRequest);
					}
				})
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/rewrite-source").build());

			assertEquals(Integer.valueOf(200), result.getMarshaledResponse().getStatusCode());
			assertEquals("rewritten", result.getResponse().get().getBody().get());
		});
	}

	@Test
	public void explicitRouteBeatsPlaceholder() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(RouteSpecificityResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			HttpRequestResult specialResult = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/items/special").build());

			assertEquals(Integer.valueOf(200), specialResult.getMarshaledResponse().getStatusCode());
			assertEquals("special", specialResult.getResponse().get().getBody().get());

			HttpRequestResult itemResult = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/items/123").build());

			assertEquals(Integer.valueOf(200), itemResult.getMarshaledResponse().getStatusCode());
			assertEquals("item:123", itemResult.getResponse().get().getBody().get());
		});
	}

	@Test
	public void conditionalRequestsWorkFromResourceMethods() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ConditionalResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			HttpRequestResult cacheHitResult = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/conditional")
							.headers(Map.of("If-None-Match", Set.of("W/\"account-7\"")))
							.build());
			assertEquals(Integer.valueOf(304), cacheHitResult.getMarshaledResponse().getStatusCode());
			Assertions.assertTrue(cacheHitResult.getMarshaledResponse().getBody().isEmpty());
			assertEquals(Set.of("\"account-7\""), cacheHitResult.getMarshaledResponse().getHeaders().get("ETag"));
			assertEquals(Set.of("private, max-age=60"), cacheHitResult.getMarshaledResponse().getHeaders().get("Cache-Control"));

			HttpRequestResult normalGetResult = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET, "/conditional").build());
			assertEquals(Integer.valueOf(200), normalGetResult.getMarshaledResponse().getStatusCode());
			assertEquals(Set.of("\"account-7\""), normalGetResult.getMarshaledResponse().getHeaders().get("ETag"));
			assertEquals(Set.of("private, max-age=60"), normalGetResult.getMarshaledResponse().getHeaders().get("Cache-Control"));
			assertEquals("account", normalGetResult.getResponse().orElseThrow().getBody().orElseThrow());

			HttpRequestResult failedUpdateResult = simulator.performHttpRequest(
					Request.withPath(HttpMethod.PUT, "/conditional")
							.headers(Map.of("If-Match", Set.of("\"account-6\"")))
							.build());
			assertEquals(Integer.valueOf(412), failedUpdateResult.getMarshaledResponse().getStatusCode());
			Assertions.assertTrue(failedUpdateResult.getMarshaledResponse().getBody().isEmpty());

			HttpRequestResult successfulUpdateResult = simulator.performHttpRequest(
					Request.withPath(HttpMethod.PUT, "/conditional")
							.headers(Map.of("If-Match", Set.of("\"account-7\"")))
							.build());
			assertEquals(Integer.valueOf(204), successfulUpdateResult.getMarshaledResponse().getStatusCode());
		});
	}

	@ThreadSafe
	public static class WrappedRequestResource {
		@GET("/greet")
		public String greet() {
			return "hello";
		}
	}

	@ThreadSafe
	public static class ContentTypeResource {
		@GET("/content-type")
		public Response contentType() {
			return Response.withStatusCode(200)
					.headers(Map.of("content-type", Set.of("application/custom")))
					.body("ok")
					.build();
		}
	}

	@ThreadSafe
	public static class WrappedRequestRewriteResource {
		@GET("/rewrite-source")
		public String original() {
			return "original";
		}

		@POST("/rewrite-target")
		public String rewritten() {
			return "rewritten";
		}
	}

	@ThreadSafe
	public static class RouteSpecificityResource {
		@GET("/items/{id}")
		public String getItem(@PathParameter String id) {
			return "item:" + id;
		}

		@GET("/items/special")
		public String getSpecialItem() {
			return "special";
		}
	}

	@ThreadSafe
	public static class ConditionalResource {
		private static final EntityTag ENTITY_TAG = EntityTag.fromStrongValue("account-7");
		private static final Instant LAST_MODIFIED = Instant.parse("2026-05-04T01:02:03Z");
		private static final Map<String, Set<String>> CACHE_HEADERS = Map.of(
				"Cache-Control", Set.of("private, max-age=60")
		);

		@GET("/conditional")
		public Response get(@NonNull Request request) {
			return ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED, CACHE_HEADERS)
					.orElseGet(() -> Response.withStatusCode(200)
							.headers(ConditionalRequests.validatorHeaders(ENTITY_TAG, LAST_MODIFIED, CACHE_HEADERS))
							.body("account")
							.build());
		}

		@PUT("/conditional")
		public Response update(@NonNull Request request) {
			return ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED)
					.orElseGet(() -> Response.fromStatusCode(204));
		}
	}
}
