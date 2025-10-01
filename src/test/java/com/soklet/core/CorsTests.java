/*
 * Copyright 2022-2025 Revetware LLC.
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

package com.soklet.core;

import com.soklet.CorsAuthorizer;
import com.soklet.HttpMethod;
import com.soklet.LifecycleInterceptor;
import com.soklet.LogEvent;
import com.soklet.Request;
import com.soklet.RequestResult;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.annotation.GET;
import com.soklet.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class CorsTests {
	@Test
	public void preflight_allOrigins_allowed() {
		SokletConfig configuration = SokletConfig.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withAcceptAllPolicy())
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			RequestResult requestResult = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of("https://example.com"),
									"Access-Control-Request-Method", Set.of("GET"),
									"Access-Control-Request-Headers", Set.of("X-Foo, X-Bar")
							))
							.build()
			);

			Assertions.assertEquals(204, requestResult.getMarshaledResponse().getStatusCode());
			Map<String, Set<String>> headers = requestResult.getMarshaledResponse().getHeaders();
			Assertions.assertTrue(headers.containsKey("Access-Control-Allow-Origin"), "missing ACAO");
			// With AllOrigins authorizer most implementations return "*"
			Assertions.assertTrue(headers.get("Access-Control-Allow-Origin").iterator().next().length() > 0);
			Assertions.assertTrue(headers.containsKey("Access-Control-Allow-Methods"));
			Assertions.assertTrue(headers.get("Access-Control-Allow-Methods").contains("GET"));
		});
	}

	@Test
	public void preflight_rejected_without_authorizer() {
		SokletConfig configuration = SokletConfig.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withRejectAllPolicy())
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			RequestResult requestResult = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of("https://malicious.net"),
									"Access-Control-Request-Method", Set.of("POST")
							))
							.build()
			);

			Assertions.assertEquals(403, requestResult.getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void actual_request_includes_cors_headers_when_allowed() {
		SokletConfig configuration = SokletConfig.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withAcceptAllPolicy())
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			RequestResult result = simulator.performRequest(
					Request.with(HttpMethod.GET, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of("https://app.example")
							))
							.build()
			);

			Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
			String body = new String(result.getMarshaledResponse().getBody().orElse(new byte[0]), StandardCharsets.UTF_8);
			Assertions.assertEquals("ok", body);
			Map<String, Set<String>> headers = result.getMarshaledResponse().getHeaders();
			Assertions.assertTrue(headers.containsKey("Access-Control-Allow-Origin"), "CORS header not present");
		});
	}

	@Test
	public void preflight_whitelist_allows_only_listed_origin() {
		SokletConfig configuration = SokletConfig.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of("https://good.example")))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			RequestResult allowed = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of("https://good.example"),
									"Access-Control-Request-Method", Set.of("GET")
							))
							.build());
			Assertions.assertEquals(204, allowed.getMarshaledResponse().getStatusCode());

			RequestResult denied = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of("https://evil.example"),
									"Access-Control-Request-Method", Set.of("GET")
							))
							.build());
			Assertions.assertEquals(403, denied.getMarshaledResponse().getStatusCode());
		});
	}

	@Resource
	public static class CorsResource {
		@GET("/api/hello")
		public String hello() {
			return "ok";
		}
	}
}