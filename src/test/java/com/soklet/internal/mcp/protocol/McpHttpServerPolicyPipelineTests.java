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
import com.soklet.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

@NotThreadSafe
@Timeout(30)
public class McpHttpServerPolicyPipelineTests {
	private static final String APPLICATION_METHOD = "test/execute";

	@Test
	public void accepted_request_traverses_the_exact_policy_and_application_order()
			throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		McpAdmissionIdentity admittedIdentity = McpAdmissionIdentity
				.withRateLimitPartitionKey("rate-partition")
				.authorizationPartitionKey("authorization-partition")
				.principal("principal")
				.applicationContext("application-context")
				.build();
		AtomicReference<Request> admittedRequest = new AtomicReference<>();
		AtomicReference<McpEffectiveAdmissionIdentity> effectiveIdentity =
				new AtomicReference<>();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), context -> {
					stages.add("admission");
					Assertions.assertEquals(APPLICATION_METHOD, context.jsonRpcMethod());
					Assertions.assertFalse(context.notification());
					Assertions.assertEquals("ordered",
							((McpJsonRpcId.StringId) context.requestId().orElseThrow()).value());
					Assertions.assertEquals("2026-07-28", context.protocolVersion());
					Assertions.assertTrue(context.operationName().isEmpty());
					Assertions.assertTrue(context.endpointPathParameters().isEmpty());
					Assertions.assertTrue(context.clientInformation().isEmpty());
					Assertions.assertTrue(context.clientCapabilities().isPresent());
					Assertions.assertFalse(context.toString().contains(APPLICATION_METHOD));
					admittedRequest.set(context.request());
					return McpAdmissionDecision.accepted(admittedIdentity);
				})
				.withRequestRateLimiter(context -> {
					stages.add("request-limiter");
					Assertions.assertSame(admittedRequest.get(), context.request());
					Assertions.assertSame(admittedIdentity,
							context.admissionIdentity().admittedIdentity());
					Assertions.assertEquals(McpRateLimitTarget.REQUEST, context.target());
					Assertions.assertEquals(APPLICATION_METHOD, context.jsonRpcMethod());
					Assertions.assertTrue(context.operationName().isEmpty());
					Assertions.assertFalse(context.toString().contains(APPLICATION_METHOD));
					effectiveIdentity.set(context.admissionIdentity());
					return McpRateLimitDecision.allowed();
				})
				.withRequestInterceptor((invocation, continuation) -> {
					stages.add("interceptor");
					Assertions.assertSame(effectiveIdentity.get(),
							invocation.admissionIdentity());
					return continuation.invoke();
				});
		McpHttpServerRuntime runtime = runtime(policy, invocation -> {
			stages.add("handler");
			Assertions.assertSame(effectiveIdentity.get(), invocation.admissionIdentity());
			Assertions.assertEquals("principal",
					invocation.admissionIdentity().admittedIdentity()
							.principal().orElseThrow());
			return result("ordered");
		});

		try {
			int port = runtime.start().getPort();
			FixedResponse response = sendFixed(port, "\"ordered\"", APPLICATION_METHOD);
			Assertions.assertEquals(200, response.head().status(), response.head().raw());
			Assertions.assertEquals("no-store",
					response.head().singleHeader("Cache-Control"));
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"ordered\","
							+ "\"result\":{\"value\":\"ordered\","
							+ "\"resultType\":\"complete\"}}",
					response.body());
			Assertions.assertEquals(List.of(
					"admission", "request-limiter", "interceptor", "handler"), stages);
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void admission_rejection_preserves_the_id_and_stops_every_later_stage()
			throws Exception {
		AtomicInteger limiterInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> McpAdmissionDecision.rejected(
						new McpRequestRejection(401,
								new McpJsonRpcError(1_001,
										"Authentication required", Optional.empty()),
								Map.of("WWW-Authenticate",
										List.of("Bearer realm=soklet-mcp")))))
				.withRequestRateLimiter(ignored -> {
					limiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.withRequestInterceptor((invocation, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.invoke();
				});
		McpHttpServerRuntime runtime = runtime(policy, invocation -> {
			handlerInvocations.incrementAndGet();
			return result("unreachable");
		});

		try {
			int port = runtime.start().getPort();
			FixedResponse response = sendFixed(port, "\"blocked\"", APPLICATION_METHOD);
			Assertions.assertEquals(401, response.head().status(), response.head().raw());
			Assertions.assertEquals("Bearer realm=soklet-mcp",
					response.head().singleHeader("WWW-Authenticate"));
			Assertions.assertEquals("no-store",
					response.head().singleHeader("Cache-Control"));
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":\"blocked\","
							+ "\"error\":{\"code\":1001,"
							+ "\"message\":\"Authentication required\"}}",
					response.body());
			Assertions.assertEquals(0, limiterInvocations.get());
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void request_limiter_denial_has_the_provisional_exact_wire_shape()
			throws Exception {
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = acceptingPolicy()
				.withRequestRateLimiter(ignored ->
						McpRateLimitDecision.denied(Duration.ofMillis(1)))
				.withRequestInterceptor((invocation, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.invoke();
				});
		McpHttpServerRuntime runtime = runtime(policy, invocation -> {
			handlerInvocations.incrementAndGet();
			return result("unreachable");
		});

		try {
			int port = runtime.start().getPort();
			FixedResponse response = sendFixed(port, "73", APPLICATION_METHOD);
			Assertions.assertEquals(429, response.head().status(), response.head().raw());
			Assertions.assertEquals("1", response.head().singleHeader("Retry-After"));
			Assertions.assertEquals("no-store",
					response.head().singleHeader("Cache-Control"));
			Assertions.assertEquals(
					"{\"jsonrpc\":\"2.0\",\"id\":73,"
							+ "\"error\":{\"code\":-31999,"
							+ "\"message\":\"Rate limited\"}}",
					response.body());
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void policy_null_exception_reserved_code_and_unsafe_header_fail_closed()
			throws Exception {
		List<PolicyFailureCase> cases = List.of(
				new PolicyFailureCase("admission-null",
						McpHttpEndpointPolicy.forDiscovery(
								CorsAuthorizer.rejectAllInstance(), ignored -> null)),
				new PolicyFailureCase("admission-throw",
						McpHttpEndpointPolicy.forDiscovery(
								CorsAuthorizer.rejectAllInstance(), ignored -> {
									throw new IllegalStateException("secret admission failure");
								})),
				new PolicyFailureCase("limiter-null",
						acceptingPolicy().withRequestRateLimiter(ignored -> null)),
				new PolicyFailureCase("limiter-throw",
						acceptingPolicy().withRequestRateLimiter(ignored -> {
							throw new IllegalStateException("secret limiter failure");
						})),
				new PolicyFailureCase("reserved-code",
						McpHttpEndpointPolicy.forDiscovery(
								CorsAuthorizer.rejectAllInstance(),
								ignored -> McpAdmissionDecision.rejected(
										new McpRequestRejection(403,
												new McpJsonRpcError(
														McpJsonRpcError.INTERNAL_ERROR,
														"reserved", Optional.empty()),
												Map.of("X-Policy", List.of("must-not-escape")))))),
				new PolicyFailureCase("soklet-rate-code",
						rejectionPolicy(-31_999, Map.of())),
				new PolicyFailureCase("soklet-strict-header-code",
						rejectionPolicy(-31_998, Map.of())),
				new PolicyFailureCase("unsafe-header",
						McpHttpEndpointPolicy.forDiscovery(
								CorsAuthorizer.rejectAllInstance(),
								ignored -> McpAdmissionDecision.rejected(
										new McpRequestRejection(401,
												new McpJsonRpcError(1_001,
														"blocked", Optional.empty()),
												Map.of("X-Unsafe",
														List.of("safe\r\nInjected: true")))))),
				new PolicyFailureCase("content-encoding",
						rejectionPolicy(Map.of("Content-Encoding", List.of("gzip")))),
				new PolicyFailureCase("duplicate-header-name",
						rejectionPolicy(Map.of(
								"X-Duplicate", List.of("first"),
								"x-duplicate", List.of("second")))));

		for (PolicyFailureCase failureCase : cases) {
			AtomicInteger interceptorInvocations = new AtomicInteger();
			AtomicInteger handlerInvocations = new AtomicInteger();
			McpHttpEndpointPolicy policy = failureCase.policy()
					.withRequestInterceptor((invocation, continuation) -> {
						interceptorInvocations.incrementAndGet();
						return continuation.invoke();
					});
			McpHttpServerRuntime runtime = runtime(policy, invocation -> {
				handlerInvocations.incrementAndGet();
				return result("unreachable");
			});
			try {
				int port = runtime.start().getPort();
				FixedResponse response = sendFixed(port,
						"\"" + failureCase.name() + "\"", APPLICATION_METHOD);
				Assertions.assertEquals(500, response.head().status(),
						failureCase.name() + ": " + response.head().raw());
				Assertions.assertEquals(
						"{\"jsonrpc\":\"2.0\",\"id\":\"" + failureCase.name()
								+ "\",\"error\":{\"code\":-32603,"
								+ "\"message\":\"Internal error\"}}",
						response.body(), failureCase.name());
				Assertions.assertFalse(response.body().contains("secret"));
				Assertions.assertFalse(response.head().hasHeader("X-Unsafe"));
				Assertions.assertFalse(response.head().hasHeader("X-Policy"));
				Assertions.assertFalse(response.head().hasHeader("Content-Encoding"));
				Assertions.assertFalse(response.head().hasHeader("X-Duplicate"));
				Assertions.assertEquals(0, interceptorInvocations.get());
				Assertions.assertEquals(0, handlerInvocations.get());
				awaitClean(runtime);
			} finally {
				runtime.close();
			}
		}
	}

	@Test
	public void discovery_is_admitted_and_request_limited_but_never_intercepted()
			throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), context -> {
					stages.add("admission:" + context.jsonRpcMethod());
					return McpAdmissionDecision.acceptedAnonymous();
				})
				.withRequestRateLimiter(context -> {
					stages.add("limiter:" + context.jsonRpcMethod());
					Assertions.assertEquals(McpRateLimitTarget.REQUEST, context.target());
					return McpRateLimitDecision.allowed();
				})
				.withRequestInterceptor((invocation, continuation) -> {
					stages.add("interceptor");
					return continuation.invoke();
				});
		McpHttpServerRuntime runtime = runtime(policy, invocation -> {
			stages.add("handler");
			return result("unreachable");
		});

		try {
			int port = runtime.start().getPort();
			FixedResponse response = sendFixed(port, "\"discover\"", "server/discover");
			Assertions.assertEquals(200, response.head().status(), response.head().raw());
			Assertions.assertTrue(response.body().contains(
					"\"supportedVersions\":[\"2026-07-28\"]"), response.body());
			Assertions.assertEquals(List.of(
					"admission:server/discover", "limiter:server/discover"), stages);
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	@Test
	public void interceptor_occupies_a_slot_and_queue_rejection_precedes_interception()
			throws Exception {
		CountDownLatch firstInterceptorEntered = new CountDownLatch(1);
		CountDownLatch releaseFirstInterceptor = new CountDownLatch(1);
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = acceptingPolicy()
				.withRequestInterceptor((invocation, continuation) -> {
					int invocationNumber = interceptorInvocations.incrementAndGet();
					if (invocationNumber == 1) {
						firstInterceptorEntered.countDown();
						releaseFirstInterceptor.await();
					}
					return continuation.invoke();
				});
		McpHttpServerRuntime runtime = runtime(policy, invocation -> {
			handlerInvocations.incrementAndGet();
			String id = ((McpJsonRpcId.StringId) invocation.request().id()).value();
			return result(id);
		}, new McpApplicationExecutionConfiguration(
				1, 1, Duration.ofSeconds(15), Duration.ofMillis(10)));

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient first = McpChunkedHttpClient.postMcp(
					port, "\"first\"", APPLICATION_METHOD);
					McpChunkedHttpClient second = McpChunkedHttpClient.postMcp(
							port, "\"second\"", APPLICATION_METHOD)) {
				Assertions.assertTrue(firstInterceptorEntered.await(5, TimeUnit.SECONDS));
				awaitApplicationSnapshot(runtime,
						snapshot -> snapshot.activeHandlerSlots() == 1
								&& snapshot.queuedRequests() == 1);

				FixedResponse rejected = sendFixed(
						port, "\"rejected\"", APPLICATION_METHOD);
				Assertions.assertEquals(503, rejected.head().status(), rejected.head().raw());
				Assertions.assertTrue(rejected.body().contains("\"code\":-32603"));
				Assertions.assertFalse(rejected.head().hasHeader("Retry-After"));
				Assertions.assertEquals(1, interceptorInvocations.get());
				Assertions.assertEquals(0, handlerInvocations.get());

				releaseFirstInterceptor.countDown();
				assertResult(first, "first", "first");
				assertResult(second, "second", "second");
			}
			Assertions.assertEquals(2, interceptorInvocations.get());
			Assertions.assertEquals(2, handlerInvocations.get());
			awaitClean(runtime);
		} finally {
			releaseFirstInterceptor.countDown();
			runtime.close();
		}
	}

	@Test
	public void interceptor_failure_after_notification_is_a_terminal_sse_error()
			throws Exception {
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = acceptingPolicy()
				.withRequestInterceptor((invocation, continuation) -> {
					Assertions.assertTrue(invocation.sendNotification(progress("intercept", 1)));
					throw new IllegalStateException("secret interceptor failure");
				});
		McpHttpServerRuntime runtime = runtime(policy, invocation -> {
			handlerInvocations.incrementAndGet();
			return result("unreachable");
		});

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"postcommit-interceptor\"", APPLICATION_METHOD)) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				Assertions.assertEquals(200, head.status(), head.raw());
				Assertions.assertEquals("text/event-stream",
						head.singleHeader("Content-Type"));
				Assertions.assertEquals(
						"data: {\"jsonrpc\":\"2.0\","
								+ "\"method\":\"notifications/progress\","
								+ "\"params\":{\"progressToken\":\"intercept\","
								+ "\"progress\":1}}\n\n",
						client.readChunkText());
				Assertions.assertEquals(
						"data: {\"jsonrpc\":\"2.0\","
								+ "\"id\":\"postcommit-interceptor\","
								+ "\"error\":{\"code\":-32603,"
								+ "\"message\":\"Internal error\"}}\n\n",
						client.readChunkText());
				Assertions.assertNull(client.readChunk());
			}
			Assertions.assertEquals(0, handlerInvocations.get());
			awaitClean(runtime);
		} finally {
			runtime.close();
		}
	}

	private static McpHttpEndpointPolicy acceptingPolicy() {
		return McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());
	}

	private static McpHttpEndpointPolicy rejectionPolicy(
			Map<String, List<String>> headers) {
		return rejectionPolicy(1_001, headers);
	}

	private static McpHttpEndpointPolicy rejectionPolicy(int errorCode,
			Map<String, List<String>> headers) {
		return McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.rejected(
						new McpRequestRejection(401,
								new McpJsonRpcError(errorCode,
										"blocked", Optional.empty()), headers)));
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			McpApplicationRequestHandler handler) {
		return runtime(policy, handler,
				McpApplicationExecutionConfiguration.productionDefaults());
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy,
			McpApplicationRequestHandler handler,
			McpApplicationExecutionConfiguration executionConfiguration) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"policy-pipeline-test", "3.6.0-SNAPSHOT"))
				.build();
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of(APPLICATION_METHOD, handler));
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				policy, endpoint, router, executionConfiguration,
				McpApplicationClock.SYSTEM);
	}

	private static FixedResponse sendFixed(int port, String idJson, String method)
			throws Exception {
		try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
				port, idJson, method)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			return new FixedResponse(head, client.readFixedBody(head));
		}
	}

	private static void assertResult(McpChunkedHttpClient client,
			String id, String value) throws Exception {
		McpChunkedHttpClient.HttpResponseHead head = client.readHead();
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals(
				"{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\","
						+ "\"result\":{\"value\":\"" + value + "\","
						+ "\"resultType\":\"complete\"}}",
				client.readFixedBody(head));
	}

	private static McpWireResult result(String value) {
		return McpWireResult.complete(new McpJsonObject(
				Map.of("value", new McpJsonString(value))));
	}

	private static McpJsonRpcMessage.Notification progress(String token, long value) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("progressToken", new McpJsonString(token));
		fields.put("progress", new McpJsonNumber(BigDecimal.valueOf(value)));
		return new McpJsonRpcMessage.Notification("notifications/progress",
				Optional.of(new McpJsonObject(fields)), McpJsonObject.empty());
	}

	private static void awaitClean(McpHttpServerRuntime runtime) throws Exception {
		awaitRequestSnapshot(runtime, snapshot -> snapshot.retainedRequestControls() == 0
				&& snapshot.activeRequestIds() == 0);
		awaitApplicationSnapshot(runtime, snapshot -> snapshot.activeHandlerSlots() == 0
				&& snapshot.queuedRequests() == 0
				&& snapshot.activeRequestIds() == 0
				&& snapshot.retainedExchanges() == 0
				&& snapshot.retainedTransportLeases() == 0);
	}

	private static McpRequestExecutionSnapshot awaitRequestSnapshot(
			McpHttpServerRuntime runtime,
			Predicate<McpRequestExecutionSnapshot> condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpRequestExecutionSnapshot latest;
		do {
			latest = runtime.requestExecutionSnapshot();
			if (condition.test(latest))
				return latest;
			Thread.sleep(5L);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for request cleanup: " + latest);
	}

	private static McpApplicationExecutionSnapshot awaitApplicationSnapshot(
			McpHttpServerRuntime runtime,
			Predicate<McpApplicationExecutionSnapshot> condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpApplicationExecutionSnapshot latest;
		do {
			latest = runtime.applicationExecutionSnapshot().orElseThrow();
			if (condition.test(latest))
				return latest;
			Thread.sleep(5L);
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError("Timed out waiting for application cleanup: " + latest);
	}

	private record FixedResponse(McpChunkedHttpClient.HttpResponseHead head,
			String body) {
	}

	private record PolicyFailureCase(String name, McpHttpEndpointPolicy policy) {
	}
}
