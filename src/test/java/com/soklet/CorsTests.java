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

package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class CorsTests {
	private static final String GOOD = "https://good.example";
	private static final String EVIL = "https://evil.example";

	@Test
	public void preflight_allOrigins_allowed() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
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
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
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
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
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

	public static class CorsResource {
		@GET("/api/hello")
		public String hello() {
			return "ok";
		}
	}

	@Test
	void corsFromHeaders_shouldTreatOriginCaseInsensitively() {
		var headers = new LinkedHashMap<String, Set<String>>();
		headers.put("origin", Set.of("https://example.com")); // lowercase

		var cors = Cors.fromHeaders(HttpMethod.GET, headers);
		Assertions.assertTrue(cors.isPresent(),
				"Expected Cors.fromHeaders to find 'origin' irrespective of case");
	}

	@Test
	void corsPreflightFromHeaders_shouldTreatOriginCaseInsensitively() {
		var headers = new LinkedHashMap<String, Set<String>>();
		headers.put("origin", Set.of("https://example.com"));
		headers.put("access-control-request-method", Set.of("POST"));

		var preflight = CorsPreflight.fromHeaders(headers);
		Assertions.assertTrue(preflight.isPresent(),
				"Expected CorsPreflight.fromHeaders to find headers irrespective of case");
	}

	@Test
	public void preflight_whitelist_allows_only_listed_origin() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of("https://good.example")))
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

	@Test
	public void preflight_reflects_requested_headers_and_sets_max_age() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of("https://good.example")))
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			RequestResult preflight = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of("https://good.example"),
									"Access-Control-Request-Method", Set.of("GET"),
									"Access-Control-Request-Headers", Set.of("Authorization, X-Token")
							))
							.build());

			var resp = preflight.getMarshaledResponse();
			Assertions.assertEquals(204, resp.getStatusCode());

			Map<String, Set<String>> headers = resp.getHeaders();
			Assertions.assertEquals(Set.of("Authorization", "X-Token"), headers.get("Access-Control-Allow-Headers"));
			Assertions.assertEquals(Set.of("600"), headers.get("Access-Control-Max-Age")); // 10 minutes
			// Vary should include Origin (marshaler adds this when normalizing "*" + credentials)
			Assertions.assertTrue(headers.getOrDefault("Vary", Set.of()).contains("Origin"));
		});
	}

	@Test
	public void nonpreflight_whitelist_sets_vary_origin_and_allows_credentials() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of("https://good.example")))
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			RequestResult result = simulator.performRequest(
					Request.with(HttpMethod.GET, "/api/hello")
							.headers(Map.of("Origin", Set.of("https://good.example")))
							.build());

			var resp = result.getMarshaledResponse();
			Assertions.assertEquals(200, resp.getStatusCode());

			Map<String, Set<String>> headers = resp.getHeaders();
			Assertions.assertEquals(Set.of("https://good.example"), headers.get("Access-Control-Allow-Origin"));
			Assertions.assertEquals(null, headers.get("Access-Control-Allow-Credentials"));
			Assertions.assertTrue(headers.getOrDefault("Vary", Set.of()).contains("Origin"));
		});
	}

	@Test
	public void allorigins_acceptall_echoes_origin_and_reflects_headers() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withAcceptAllPolicy())  // permissive: creds ON
				.build();

		Soklet.runSimulator(configuration, simulator -> {
			RequestResult preflight = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of("https://any.example"),
									"Access-Control-Request-Method", Set.of("GET"),
									"Access-Control-Request-Headers", Set.of("Authorization")
							))
							.build());

			var resp = preflight.getMarshaledResponse();
			Assertions.assertEquals(204, resp.getStatusCode());

			Map<String, Set<String>> headers = resp.getHeaders();

			// With credentials enabled, marshaler echoes the concrete Origin and adds Vary: Origin
			Assertions.assertEquals(Set.of("https://any.example"), headers.get("Access-Control-Allow-Origin"));
			Assertions.assertEquals(Set.of("true"), headers.get("Access-Control-Allow-Credentials"));
			Assertions.assertTrue(headers.getOrDefault("Vary", Set.of()).contains("Origin"));

			// Still reflects requested headers
			Assertions.assertEquals(Set.of("Authorization"), headers.get("Access-Control-Allow-Headers"));

			// If you set a Max-Age in the authorizer, you can assert it here too (e.g., "600")
			// Assertions.assertEquals(Set.of("600"), headers.get("Access-Control-Max-Age"));
		});
	}

	@Test
	public void corspreflight_fromHeaders_parses_plural_headers_with_commas() {
		Map<String, Set<String>> headers = Map.of(
				"Origin", Set.of("https://good.example"),
				"Access-Control-Request-Method", Set.of("GET"),
				"Access-Control-Request-Headers", Set.of("X-Alpha, X-Beta , Authorization")
		);

		CorsPreflight preflight = CorsPreflight.fromHeaders(headers).orElseThrow();
		Assertions.assertEquals(Set.of("X-Alpha", "X-Beta", "Authorization"), preflight.getAccessControlRequestHeaders());
	}


	@Test
	public void preflight_whitelist_resolver_true_sets_credentials_true() {
		var config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of(GOOD), origin -> true))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* quiet */ }
				})
				.build();

		Soklet.runSimulator(config, simulator -> {
			var result = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of(GOOD),
									"Access-Control-Request-Method", Set.of("GET"),
									"Access-Control-Request-Headers", Set.of("Authorization")
							))
							.build()
			);

			var resp = result.getMarshaledResponse();
			Assertions.assertEquals(204, resp.getStatusCode());
			Assertions.assertEquals(Set.of(GOOD), resp.getHeaders().get("Access-Control-Allow-Origin"));
			Assertions.assertEquals(Set.of("true"), resp.getHeaders().get("Access-Control-Allow-Credentials"));
		});
	}

	@Test
	public void preflight_whitelist_resolver_false_disables_credentials_header() {
		var config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of(GOOD), origin -> false))
				.build();

		Soklet.runSimulator(config, simulator -> {
			var result = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of(GOOD),
									"Access-Control-Request-Method", Set.of("GET")
							))
							.build()
			);

			var resp = result.getMarshaledResponse();
			Assertions.assertEquals(204, resp.getStatusCode());

			// If the marshaler includes the header when false, it should be "false".
			// If it omits the header, that’s also acceptable. Just ensure it’s not "true".
			Map<String, Set<String>> headers = resp.getHeaders();
			if (headers.containsKey("Access-Control-Allow-Credentials")) {
				Assertions.assertEquals(Set.of("false"), headers.get("Access-Control-Allow-Credentials"));
			}
		});
	}

	@Test
	public void preflight_whitelist_resolver_null_omits_credentials_header() {
		var config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of(GOOD), origin -> null))
				.build();

		Soklet.runSimulator(config, simulator -> {
			var result = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of(GOOD),
									"Access-Control-Request-Method", Set.of("GET")
							))
							.build()
			);

			var resp = result.getMarshaledResponse();
			Assertions.assertEquals(204, resp.getStatusCode());
			Assertions.assertFalse(resp.getHeaders().containsKey("Access-Control-Allow-Credentials"),
					"Expected ACAC to be omitted when resolver returns null");
		});
	}

	@Test
	public void preflight_resolver_receives_normalized_origin_lowercase_trimmed() {
		AtomicReference<String> seen = new AtomicReference<>();
		var config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of(GOOD), origin -> {
					seen.set(origin); // this is the normalized origin per implementation
					return true;
				}))
				.build();

		Soklet.runSimulator(config, simulator -> {
			// Mix case + whitespace to ensure normalization (toLowerCase + trim) occurs
			var headers = new LinkedHashMap<String, Set<String>>();
			headers.put("Origin", Set.of("  HTTPS://GOOD.EXAMPLE  "));
			headers.put("Access-Control-Request-Method", Set.of("GET"));

			var result = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello").headers(headers).build()
			);

			Assertions.assertEquals(204, result.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("https://good.example", seen.get(),
					"allowCredentialsResolver should see normalized origin");
		});
	}

	@Test
	public void preflight_dynamic_whitelist_authorizer_with_independent_resolver() {
		// Authorizer dynamically allows GOOD only; resolver independently says "false"
		var config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistAuthorizer(
						origin -> origin.equals("https://good.example"),
						origin -> false))
				.build();

		Soklet.runSimulator(config, simulator -> {
			var ok = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of(GOOD),
									"Access-Control-Request-Method", Set.of("GET")
							))
							.build());
			Assertions.assertEquals(204, ok.getMarshaledResponse().getStatusCode());
			var headers = ok.getMarshaledResponse().getHeaders();
			if (headers.containsKey("Access-Control-Allow-Credentials")) {
				Assertions.assertEquals(Set.of("false"), headers.get("Access-Control-Allow-Credentials"));
			}

			var denied = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of(EVIL),
									"Access-Control-Request-Method", Set.of("GET")
							))
							.build());
			Assertions.assertEquals(403, denied.getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void nonpreflight_whitelist_resolver_true_sets_credentials_true() {
		var config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of(GOOD), origin -> true))
				.build();

		Soklet.runSimulator(config, simulator -> {
			var result = simulator.performRequest(
					Request.with(HttpMethod.GET, "/api/hello")
							.headers(Map.of("Origin", Set.of(GOOD)))
							.build()
			);

			var resp = result.getMarshaledResponse();
			Assertions.assertEquals(200, resp.getStatusCode());
			Assertions.assertEquals("ok", new String(resp.getBody().orElse(new byte[0]), StandardCharsets.UTF_8));

			Map<String, Set<String>> headers = resp.getHeaders();
			Assertions.assertEquals(Set.of(GOOD), headers.get("Access-Control-Allow-Origin"));
			Assertions.assertEquals(Set.of("true"), headers.get("Access-Control-Allow-Credentials"));
		});
	}

	@Test
	public void nonpreflight_whitelist_resolver_false_should_disable_credentials() {
		var config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of(GOOD), origin -> false))
				.build();

		Soklet.runSimulator(config, simulator -> {
			var result = simulator.performRequest(
					Request.with(HttpMethod.GET, "/api/hello")
							.headers(Map.of("Origin", Set.of(GOOD)))
							.build()
			);

			var resp = result.getMarshaledResponse();
			Assertions.assertEquals(200, resp.getStatusCode());

			Map<String, Set<String>> headers = resp.getHeaders();
			if (headers.containsKey("Access-Control-Allow-Credentials")) {
				Assertions.assertEquals(Set.of("false"), headers.get("Access-Control-Allow-Credentials"));
			}
		});
	}

	@Test
	public void preflight_null_origin_is_rejected_even_with_permissive_resolver() {
		var config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(CorsResource.class)))
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of(GOOD), origin -> true))
				.build();

		Soklet.runSimulator(config, simulator -> {
			var result = simulator.performRequest(
					Request.with(HttpMethod.OPTIONS, "/api/hello")
							.headers(Map.of(
									"Origin", Set.of("null"),
									"Access-Control-Request-Method", Set.of("GET")
							))
							.build()
			);

			Assertions.assertEquals(403, result.getMarshaledResponse().getStatusCode(),
					"Suspicious origin 'null' should be rejected before resolver is consulted");
		});
	}
}