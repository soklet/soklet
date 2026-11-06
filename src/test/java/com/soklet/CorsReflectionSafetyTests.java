/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CorsReflectionSafetyTests {
	private static final String GOOD = "https://good.example";
	private static final String UNSAFE = "https://good.example%00";

	@Test
	@Timeout(60)
	void unsafeOriginDoesNotReplaceHttpOrErrorResponsesAndRemainsVisible() {
		for (CorsAuthorizer authorizer : reflectingAuthorizers()) {
			AtomicInteger marshaledErrors = new AtomicInteger();
			ResponseMarshaler marshaler = ResponseMarshaler.builder()
					.throwableHandler((request, failure, method) -> {
						marshaledErrors.incrementAndGet();
						assertEquals(UNSAFE, request.getHeaders().get("Origin").getFirst());
						return MarshaledResponse.withStatusCode(422).body("application-error".getBytes(StandardCharsets.UTF_8)).build();
					}).build();
			SokletSimulator.run(config(authorizer).httpServer().responseMarshaler(marshaler).build(), simulator -> {
				HttpRequestResult normal = simulator.performHttpRequest(request(HttpMethod.GET, "/ok", UNSAFE));
				assertEquals(200, normal.getMarshaledResponse().getStatusCode());
				assertEquals(UNSAFE + "|" + UNSAFE,
						new String(normal.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
				assertNoGrant(normal.getMarshaledResponse());
				HttpRequestResult failed = simulator.performHttpRequest(request(HttpMethod.GET, "/fail", UNSAFE));
				assertEquals(422, failed.getMarshaledResponse().getStatusCode());
				assertEquals("application-error", new String(failed.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
				assertNoGrant(failed.getMarshaledResponse());
				HttpRequestResult missing = simulator.performHttpRequest(request(HttpMethod.GET, "/missing", UNSAFE));
				assertEquals(404, missing.getMarshaledResponse().getStatusCode());
				assertNoGrant(missing.getMarshaledResponse());
			});
			assertEquals(1, marshaledErrors.get(), "Unsafe CORS must not re-enter error marshaling");
		}
	}

	@Test
	@Timeout(60)
	void unsafePreflightReflectionIsExplicitlyDeniedWithoutDroppingNames() {
		for (CorsAuthorizer authorizer : reflectingAuthorizers()) {
			SokletSimulator.run(config(authorizer).httpServer().build(), simulator -> {
				for (Request preflight : List.of(preflight(UNSAFE, "X-Safe"),
						preflight(GOOD, "X-Safe, x%0a"), preflight(GOOD, "X-Safe, bad name"))) {
					HttpRequestResult result = simulator.performHttpRequest(preflight);
					assertEquals(403, result.getMarshaledResponse().getStatusCode());
					assertNoGrant(result.getMarshaledResponse());
					assertTrue(result.getCorsPreflightResponse().isEmpty());
				}
				// Percent is legal in a header-name token; only prohibited outgoing
				// control encodings are denied, not all percent-containing names.
				HttpRequestResult allowed = simulator.performHttpRequest(preflight(GOOD, "X-Safe, x%41"));
				assertEquals(204, allowed.getMarshaledResponse().getStatusCode());
				assertEquals(Set.of("X-Safe", "x%41"),
						Set.copyOf(allowed.getMarshaledResponse().getHeaders().get("Access-Control-Allow-Headers")));
			});
		}
		assertDoesNotThrow(() -> Utilities.validateHeaderNameAndValue("x%0a", null));
	}

	@Test
	void builtInAuthorizersRejectUnsafeReflectionForBothPreflightOverloads() {
		for (CorsAuthorizer authorizer : List.of(CorsAuthorizer.acceptAllInstance(),
				CorsAuthorizer.fromWhitelistAuthorizer(origin -> true),
				CorsAuthorizer.fromWhitelistedOrigins(Set.of(GOOD, UNSAFE)))) {
			assertTrue(authorizer.authorize(request(HttpMethod.GET, "/ok", UNSAFE), Cors.fromOrigin(HttpMethod.GET, UNSAFE)).isEmpty());
			for (CorsPreflight preflight : List.of(CorsPreflight.fromOrigin(UNSAFE, HttpMethod.GET),
					CorsPreflight.fromOrigin(GOOD, HttpMethod.GET, Set.of("X-Safe", "x%0a")))) {
				assertTrue(authorizer.authorizePreflight(request(HttpMethod.OPTIONS, "/ok", preflight.getOrigin()), preflight,
						Map.<HttpMethod, ResourceMethod>of()).isEmpty());
				assertTrue(authorizer.authorizePreflight(request(HttpMethod.OPTIONS, "/ok", preflight.getOrigin()), preflight,
						Set.of(HttpMethod.GET)).isEmpty());
			}
		}
	}

	@Test
	@Timeout(60)
	void safeCredentialedAndUnlistedOriginsKeepTheirExistingBehavior() {
		for (CorsAuthorizer authorizer : reflectingAuthorizers()) {
			SokletSimulator.run(config(authorizer).httpServer().build(), simulator -> {
				MarshaledResponse actual = simulator.performHttpRequest(request(HttpMethod.GET, "/ok", GOOD)).getMarshaledResponse();
				assertEquals(200, actual.getStatusCode());
				assertEquals(List.of(GOOD), actual.getHeaders().get("Access-Control-Allow-Origin"));
				assertEquals(List.of("true"), actual.getHeaders().get("Access-Control-Allow-Credentials"));
				MarshaledResponse preflight = simulator.performHttpRequest(preflight(GOOD, "Authorization")).getMarshaledResponse();
				assertEquals(204, preflight.getStatusCode());
				assertEquals(List.of(GOOD), preflight.getHeaders().get("Access-Control-Allow-Origin"));
				assertEquals(List.of("true"), preflight.getHeaders().get("Access-Control-Allow-Credentials"));
			});
		}
		SokletSimulator.run(config(CorsAuthorizer.fromWhitelistedOrigins(Set.of(GOOD))).httpServer().build(), simulator -> {
			MarshaledResponse unlisted = simulator.performHttpRequest(request(HttpMethod.GET, "/ok", UNSAFE)).getMarshaledResponse();
			assertEquals(200, unlisted.getStatusCode());
			assertNoGrant(unlisted);
			MarshaledResponse allowed = simulator.performHttpRequest(request(HttpMethod.GET, "/ok", GOOD)).getMarshaledResponse();
			assertEquals(List.of(GOOD), allowed.getHeaders().get("Access-Control-Allow-Origin"));
			assertFalse(allowed.getHeaders().containsKey("Access-Control-Allow-Credentials"));
		});
	}

	@Test
	@Timeout(60)
	void unsafeOriginDoesNotBreakSseHandshakeAndValidCredentialsStillWork() {
		for (CorsAuthorizer authorizer : reflectingAuthorizers()) {
			SokletSimulator.run(config(authorizer).sseServer()
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SseResource.class))).build(), simulator -> {
				for (String origin : List.of(UNSAFE, GOOD)) {
					SseRequestResult result = simulator.performSseRequest(request(HttpMethod.GET, "/events", origin));
					SseRequestResult.HandshakeAccepted accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class, result);
					try (accepted) {
						MarshaledResponse response = accepted.getHttpRequestResult().getMarshaledResponse();
						assertEquals(200, response.getStatusCode());
						assertEquals(List.of(UNSAFE.equals(origin) ? "unsafe-origin-preserved" : origin),
								response.getHeaders().get("X-Observed-Origin"));
						if (UNSAFE.equals(origin)) assertNoGrant(response);
						else {
							assertEquals(List.of(GOOD), response.getHeaders().get("Access-Control-Allow-Origin"));
							assertEquals(List.of("true"), response.getHeaders().get("Access-Control-Allow-Credentials"));
						}
					}
				}
			});
		}
	}

	private static SimulatorConfig.Builder config(CorsAuthorizer authorizer) {
		return SimulatorConfig.builder().corsAuthorizer(authorizer)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
						.startupCancelationTimeout(Duration.ofMillis(200)).gracefulShutdownTimeout(Duration.ofMillis(200))
						.forcedShutdownTimeout(Duration.ofMillis(200)).build())
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(@NonNull LogEvent event) { }
				});
	}

	private static List<CorsAuthorizer> reflectingAuthorizers() {
		return List.of(CorsAuthorizer.acceptAllInstance(), CorsAuthorizer.fromWhitelistAuthorizer(origin -> true, origin -> true),
				new CorsAuthorizer() {
					@Override public @NonNull Optional<CorsResponse> authorize(@NonNull Request request, @NonNull Cors cors) {
						return Optional.of(CorsResponse.withAccessControlAllowOrigin(cors.getOrigin()).accessControlAllowCredentials(true).build());
					}
					@Override public @NonNull Optional<CorsPreflightResponse> authorizePreflight(@NonNull Request request,
							@NonNull CorsPreflight preflight, @NonNull Map<HttpMethod, ResourceMethod> methods) {
						return Optional.of(CorsPreflightResponse.withAccessControlAllowOrigin(preflight.getOrigin())
								.accessControlAllowCredentials(true).accessControlAllowMethods(methods.keySet())
								.accessControlAllowHeaders(preflight.getAccessControlRequestHeaders()).build());
					}
				});
	}

	private static Request request(HttpMethod method, String path, String origin) {
		return Request.withPath(method, path).headers(Map.of("Origin", List.of(origin))).build();
	}

	private static Request preflight(String origin, String names) {
		return Request.withPath(HttpMethod.OPTIONS, "/ok").headers(Map.of("Origin", List.of(origin),
				"Access-Control-Request-Method", List.of("GET"), "Access-Control-Request-Headers", List.of(names))).build();
	}

	private static void assertNoGrant(MarshaledResponse response) {
		assertFalse(response.getHeaders().containsKey("Access-Control-Allow-Origin"));
		assertFalse(response.getHeaders().containsKey("Access-Control-Allow-Credentials"));
		assertFalse(response.getHeaders().containsKey("Access-Control-Allow-Headers"));
	}

	public static class Resource {
		@GET("/ok") public String ok(Request request) {
			return request.getHeaders().get("Origin").getFirst() + "|" + request.getCors().orElseThrow().getOrigin();
		}
		@GET("/fail") public String fail() { throw new IllegalStateException("application-error"); }
	}

	public static class SseResource {
		@SseEventSource("/events") public SseHandshakeResult events(Request request) {
			// An observer sees the original value, without reflecting the unsafe value in a response header.
			String origin = request.getHeaders().get("Origin").getFirst();
			assertEquals(origin, request.getCors().orElseThrow().getOrigin());
			return SseHandshakeResult.Accepted.builder()
					.headers(Map.of("X-Observed-Origin", List.of(origin.equals(UNSAFE) ? "unsafe-origin-preserved" : origin))).build();
		}
	}
}
