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

package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class McpRequestPolicyTests {
	@Test
	public void canonical_anonymous_identity_has_a_stable_rate_partition() {
		McpAdmissionIdentity anonymous = McpAdmissionIdentity.anonymousInstance();

		Assertions.assertSame(anonymous, McpAdmissionIdentity.anonymousInstance());
		Assertions.assertFalse(anonymous.authenticated());
		Assertions.assertTrue(anonymous.principal().isEmpty());
		Assertions.assertTrue(anonymous.applicationContext().isEmpty());
		Assertions.assertEquals("anonymous",
				anonymous.rateLimitPartitionKey().orElseThrow());
		Assertions.assertTrue(anonymous.authorizationPartitionKey().isEmpty());
	}

	@Test
	public void identity_builder_requires_bounded_valid_partition_keys() {
		String exactlyMaximumUtf8Bytes = "\u00E9".repeat(128);
		McpAdmissionIdentity identity = McpAdmissionIdentity
				.withRateLimitPartitionKey(exactlyMaximumUtf8Bytes)
				.authorizationPartitionKey("tenant-7")
				.principal("principal-7")
				.applicationContext("context-7")
				.build();

		Assertions.assertTrue(identity.authenticated());
		Assertions.assertEquals("principal-7", identity.principal().orElseThrow());
		Assertions.assertEquals("context-7",
				identity.applicationContext().orElseThrow());
		Assertions.assertEquals(exactlyMaximumUtf8Bytes,
				identity.rateLimitPartitionKey().orElseThrow());
		Assertions.assertEquals("tenant-7",
				identity.authorizationPartitionKey().orElseThrow());

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpAdmissionIdentity.withRateLimitPartitionKey(" ").build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpAdmissionIdentity
						.withRateLimitPartitionKey(exactlyMaximumUtf8Bytes + "a")
						.build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpAdmissionIdentity
						.withRateLimitPartitionKey("\uD800")
						.build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpAdmissionIdentity.withRateLimitPartitionKey("rate")
						.authorizationPartitionKey("\n")
						.build());
	}

	@Test
	public void authenticated_identity_requires_both_partition_keys() {
		Assertions.assertThrows(IllegalStateException.class,
				() -> McpAdmissionIdentity.withRateLimitPartitionKey("rate")
						.principal("principal")
						.build());

		McpAdmissionIdentity partitionedAnonymous = McpAdmissionIdentity
				.withRateLimitPartitionKey("rate")
				.authorizationPartitionKey("authorization")
				.build();
		Assertions.assertFalse(partitionedAnonymous.authenticated());
	}

	@Test
	public void effective_partitions_use_a_deployment_stable_endpoint_scope_and_are_safe_to_log() {
		McpNormalizedEndpoint firstEndpoint = endpoint("first");
		McpNormalizedEndpoint secondEndpoint = endpoint("first");
		McpAdmissionIdentity admitted = McpAdmissionIdentity
				.withRateLimitPartitionKey("secret-rate-key")
				.authorizationPartitionKey("secret-authorization-key")
				.principal("secret-principal")
				.build();

		McpEffectiveAdmissionIdentity first = McpEffectiveAdmissionIdentity.resolve(
				firstEndpoint, "/mcp", admitted);
		McpEffectiveAdmissionIdentity sameEndpoint = McpEffectiveAdmissionIdentity.resolve(
				firstEndpoint, "/mcp", admitted);
		McpEffectiveAdmissionIdentity equivalentEndpoint =
				McpEffectiveAdmissionIdentity.resolve(
				secondEndpoint, "/mcp", admitted);
		McpEffectiveAdmissionIdentity otherPath = McpEffectiveAdmissionIdentity.resolve(
				secondEndpoint, "/other-mcp", admitted);

		Assertions.assertEquals(first.rateLimitPartition(),
				sameEndpoint.rateLimitPartition());
		Assertions.assertEquals(first.rateLimitPartition(),
				equivalentEndpoint.rateLimitPartition());
		Assertions.assertNotEquals(first.rateLimitPartition(),
				otherPath.rateLimitPartition());
		Assertions.assertEquals("secret-rate-key",
				first.rateLimitPartition().applicationKey().orElseThrow());
		Assertions.assertEquals("secret-authorization-key",
				first.authorizationPartition().applicationKey().orElseThrow());
		Assertions.assertFalse(first.toString().contains("secret"));
		Assertions.assertFalse(first.rateLimitPartition().toString().contains("secret"));
		Assertions.assertFalse(first.authorizationPartition().toString().contains("secret"));
		Assertions.assertFalse(first.rateLimitPartition().endpointIdentity()
				.toString().contains("secret"));
	}

	@Test
	public void endpoint_policy_diagnostics_never_stringify_application_hooks() {
		McpProtocolAdmissionController protocolAdmissionController = new McpProtocolAdmissionController() {
			@Override
			public McpAdmissionDecision admit(McpAdmissionContext context) {
				return McpAdmissionDecision.acceptedAnonymous();
			}

			@Override
			public String toString() {
				return "secret-admission-credentials";
			}
		};
		McpRateLimiter limiter = new McpRateLimiter() {
			@Override
			public McpRateLimitDecision acquire(McpRateLimitContext context) {
				return McpRateLimitDecision.allowed();
			}

			@Override
			public String toString() {
				return "secret-limiter-credentials";
			}
		};
		McpApplicationRequestInterceptor interceptor =
				new McpApplicationRequestInterceptor() {
					@Override
					public McpWireResult intercept(McpApplicationInvocation invocation,
							McpApplicationHandlerInvocation continuation)
							throws Exception {
						return continuation.invoke();
					}

					@Override
					public String toString() {
						return "secret-interceptor-credentials";
					}
				};
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), protocolAdmissionController)
				.withRequestRateLimiter(limiter)
				.withRequestInterceptor(interceptor);

		Assertions.assertEquals(
				"McpHttpEndpointPolicy[path=/mcp, allowedHostCount=0, "
						+ "absentOriginPolicy=ALLOW, requestRateLimiterPresent=true, "
						+ "unknownMirroredHeaderPolicy=IGNORE, "
						+ "catalogLocalizerPresent=false]",
				policy.toString());
		Assertions.assertFalse(policy.toString().contains("secret"));
	}

	@Test
	public void endpoint_policy_copies_preserve_cors_authorizer_provenance() {
		CorsAuthorizer rejectAll = CorsAuthorizer.rejectAllInstance();
		McpProtocolAdmissionController protocolAdmissionController =
				ignored -> McpAdmissionDecision.acceptedAnonymous();
		McpHttpEndpointPolicy omitted =
				McpHttpEndpointPolicy.forDiscoveryWithDefaultCorsAuthorizer(
						protocolAdmissionController)
						.withRequestRateLimiter(ignored -> McpRateLimitDecision.allowed())
						.withRequestInterceptor(
								McpApplicationRequestInterceptor.passThroughInstance())
						.withUnknownMirroredHeaderPolicy(
								McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS);

		Assertions.assertSame(rejectAll, omitted.corsAuthorizer());
		Assertions.assertFalse(omitted.corsAuthorizerExplicitlyConfigured());

		McpHttpEndpointPolicy explicit = McpHttpEndpointPolicy.forDiscovery(
				rejectAll, protocolAdmissionController)
				.withRequestRateLimiter(ignored -> McpRateLimitDecision.allowed())
				.withRequestInterceptor(
						McpApplicationRequestInterceptor.passThroughInstance())
				.withUnknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS);

		Assertions.assertSame(rejectAll, explicit.corsAuthorizer());
		Assertions.assertTrue(explicit.corsAuthorizerExplicitlyConfigured());

		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> new McpHttpEndpointPolicy("/mcp", Set.of(),
						McpAbsentOriginPolicy.ALLOW, CorsAuthorizer.acceptAllInstance(),
						protocolAdmissionController, Optional.empty(),
						McpApplicationRequestInterceptor.passThroughInstance(),
						McpUnknownMirroredHeaderPolicy.IGNORE, false));
		Assertions.assertEquals(
				"An omitted CORS authorizer must use the reject-all default.",
				exception.getMessage());
	}

	@Test
	public void rejection_copies_headers_and_diagnostic_text_omits_values() {
		List<String> values = new ArrayList<>(List.of("Bearer realm=secret-realm"));
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("WWW-Authenticate", values);
		McpAdmissionRejection rejection = new McpAdmissionRejection(401,
				new McpJsonRpcError(1_001, "Authentication required",
						java.util.Optional.empty()), headers);

		values.add("mutated");
		headers.put("X-Late", List.of("late"));
		Assertions.assertEquals(List.of("Bearer realm=secret-realm"),
				rejection.headers().get("WWW-Authenticate"));
		Assertions.assertFalse(rejection.headers().containsKey("X-Late"));
		Assertions.assertFalse(rejection.toString().contains("secret-realm"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpAdmissionRejection(399, rejection.jsonRpcError(), Map.of()));
	}

	@Test
	public void rate_limit_denial_requires_a_nonnegative_retry_delay() {
		Assertions.assertEquals(Duration.ZERO,
				McpRateLimitDecision.denied(Duration.ZERO).retryAfter());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpRateLimitDecision.denied(Duration.ofNanos(-1)));
	}

	private static McpNormalizedEndpoint endpoint(String name) {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(name, "3.6.0-SNAPSHOT"))
				.build();
	}
}
