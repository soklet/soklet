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
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@NotThreadSafe
@Timeout(30)
public class McpHttpServerNotificationTests {
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String CANCELLED = "notifications/cancelled";

	@Test
	public void cancellation_is_admitted_limited_accepted_and_ignores_its_payload()
			throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), context -> {
					stages.add("admission");
					Assertions.assertTrue(context.notification());
					Assertions.assertTrue(context.requestId().isEmpty());
					Assertions.assertEquals(CANCELLED, context.jsonRpcMethod());
					Assertions.assertEquals(PROTOCOL_VERSION, context.protocolVersion());
					Assertions.assertTrue(context.operationName().isEmpty());
					Assertions.assertTrue(context.clientInformation().isEmpty());
					Assertions.assertTrue(context.clientCapabilities().isEmpty());
					return McpAdmissionDecision.acceptedAnonymous();
				})
				.withRequestRateLimiter(context -> {
					stages.add("request-limiter");
					Assertions.assertEquals(McpRateLimitTarget.REQUEST, context.target());
					Assertions.assertEquals(CANCELLED, context.jsonRpcMethod());
					Assertions.assertTrue(context.operationName().isEmpty());
					return McpRateLimitDecision.allowed();
				})
				.withRequestInterceptor((invocation, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.invoke();
				})
				.withUnknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS);
		McpHttpServerRuntime runtime = runtime(policy);

		try {
			int port = runtime.start().getPort();
			FixedResponse response = send(port,
					"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
							+ "\"params\":{\"requestId\":{\"malformed\":true},"
							+ "\"_meta\":\"also malformed\"}}",
					List.of(versionHeader(),
							new McpChunkedHttpClient.RequestHeader("Mcp-Method", "wrong"),
							new McpChunkedHttpClient.RequestHeader("mcp-method", "duplicate"),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Param-Untrusted", "ignored")));

			Assertions.assertEquals(202, response.head().status(), response.head().raw());
			Assertions.assertEquals("", response.body());
			Assertions.assertFalse(response.head().hasHeader("Content-Type"));
			Assertions.assertEquals("no-store",
					response.head().singleHeader("Cache-Control"));
			Assertions.assertEquals(List.of("admission", "request-limiter"), stages);
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0,
					runtime.requestExecutionSnapshot().unknownMirroredHeaderOccurrences());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void http_cancellation_with_an_active_request_id_never_cancels_work()
			throws Exception {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		AtomicBoolean handlerInterrupted = new AtomicBoolean();
		AtomicReference<McpApplicationInvocation> invocation = new AtomicReference<>();
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"notification-cancellation-test", "3.6.0-SNAPSHOT"))
				.tool(new McpNormalizedOperation("slow-tool",
						McpInputRequestPlan.empty(), McpMirroredHeaderPlan.empty()))
				.build();
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromHandlers(
				Map.of("tools/call", applicationInvocation -> {
					invocation.set(applicationInvocation);
					handlerEntered.countDown();
					try {
						releaseHandler.await();
					} catch (InterruptedException exception) {
						handlerInterrupted.set(true);
						throw exception;
					}
					return McpWireResult.complete(new McpJsonObject(
							Map.of("completed", McpJsonBoolean.TRUE)));
				}));
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), policy, endpoint,
				router, McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM);
		ExecutorService client = Executors.newSingleThreadExecutor();

		try {
			int port = runtime.start().getPort();
			Future<FixedResponse> applicationResponse = client.submit(() -> send(port,
					toolCallRequest("active-id", "slow-tool"),
					toolCallHeaders("slow-tool")));
			Assertions.assertTrue(handlerEntered.await(5, TimeUnit.SECONDS),
					"The active request handler did not enter.");

			FixedResponse cancellationResponse = send(port,
					notification(CANCELLED,
							"{\"requestId\":\"active-id\",\"reason\":\"ignored\"}"),
					List.of(versionHeader()));
			Assertions.assertEquals(202, cancellationResponse.head().status(),
					cancellationResponse.head().raw());
			Assertions.assertEquals("", cancellationResponse.body());
			Assertions.assertFalse(cancellationResponse.head().hasHeader("Content-Type"));
			Assertions.assertFalse(handlerInterrupted.get(),
					"An HTTP cancellation notification interrupted application work.");
			Assertions.assertFalse(invocation.get().isCancellationRequested(),
					"An HTTP cancellation notification signalled application cancellation.");
			Assertions.assertFalse(applicationResponse.isDone(),
					"The active request completed before its handler was released.");

			releaseHandler.countDown();
			FixedResponse completed = applicationResponse.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(200, completed.head().status(), completed.head().raw());
			Assertions.assertTrue(completed.body().contains("\"id\":\"active-id\""),
					completed.body());
			Assertions.assertTrue(completed.body().contains("\"completed\":true"),
					completed.body());
			Assertions.assertFalse(handlerInterrupted.get());
		} finally {
			releaseHandler.countDown();
			runtime.close();
			client.shutdownNow();
			client.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	public void compound_notification_failures_follow_the_frozen_classification_order()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger limiterInvocations = new AtomicInteger();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicBoolean rejectAdmission = new AtomicBoolean();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> {
					admissions.incrementAndGet();
					if (rejectAdmission.get())
						return McpAdmissionDecision.rejected(new McpAdmissionRejection(
								401, new McpJsonRpcError(1_001,
								"Authentication required", Optional.empty()), Map.of()));
					return McpAdmissionDecision.acceptedAnonymous();
				})
				.withRequestRateLimiter(ignored -> {
					limiterInvocations.incrementAndGet();
					return McpRateLimitDecision.denied(Duration.ofSeconds(1));
				})
				.withRequestInterceptor((invocation, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.invoke();
				});
		McpHttpServerRuntime runtime = runtime(policy);

		try {
			int port = runtime.start().getPort();
			List<NotificationPrecedenceCase> cases = List.of(
					new NotificationPrecedenceCase(
							"strict JSON failure remains pre-classification",
							"{", List.of(), false, 400, 0, 0, true),
					new NotificationPrecedenceCase(
							"unsupported metadata precedes missing protocol and policy",
							notification("future/event",
									"{\"_meta\":\"malformed\"}"),
							List.of(), false, 400, 0, 0, false),
					new NotificationPrecedenceCase(
							"cancellation skips metadata but not missing protocol",
							notification(CANCELLED,
									"{\"requestId\":{},\"_meta\":\"malformed\"}"),
							List.of(), false, 400, 0, 0, false),
					new NotificationPrecedenceCase(
							"admission rejection precedes request limiting",
							notification("future/event", null),
							List.of(versionHeader()), true, 401, 1, 0, false),
					new NotificationPrecedenceCase(
							"cancellation reaches request limiting before acceptance",
							notification(CANCELLED,
									"{\"requestId\":{},\"_meta\":\"malformed\"}"),
							List.of(versionHeader()), false, 429, 2, 1, false),
					new NotificationPrecedenceCase(
							"unsupported handling follows request limiting",
							notification("future/event", null),
							List.of(versionHeader()), false, 429, 3, 2, false));

			for (NotificationPrecedenceCase testCase : cases) {
				rejectAdmission.set(testCase.rejectAdmission());
				FixedResponse response = send(port, testCase.body(), testCase.headers());
				Assertions.assertEquals(testCase.expectedStatus(), response.head().status(),
						testCase.description() + ": " + response.head().raw());
				Assertions.assertEquals("no-store",
						response.head().singleHeader("Cache-Control"),
						testCase.description());
				if (testCase.expectJsonRpcBody()) {
					Assertions.assertTrue(response.head().hasHeader("Content-Type"),
							testCase.description());
					Assertions.assertTrue(response.body().contains("\"code\":-32700"),
							response.body());
					Assertions.assertFalse(response.body().contains("\"id\""),
							response.body());
				} else {
					Assertions.assertEquals("", response.body(), testCase.description());
					Assertions.assertFalse(response.head().hasHeader("Content-Type"),
							testCase.description());
				}
				Assertions.assertEquals(testCase.expectedAdmissions(), admissions.get(),
						testCase.description());
				Assertions.assertEquals(testCase.expectedLimiterInvocations(),
						limiterInvocations.get(), testCase.description());
				Assertions.assertEquals(0, interceptorInvocations.get(),
						"Notifications must never enter request interception.");
			}
		} finally {
			runtime.close();
		}
	}

	@Test
	public void unsupported_notification_validates_present_metadata_before_policy()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				}));

		try {
			int port = runtime.start().getPort();
			for (String params : List.of(
					"{\"_meta\":\"not-an-object\"}",
					"{\"_meta\":{\"bad/key/with-two-slashes\":true}}")) {
				FixedResponse response = send(port, notification("future/event", params),
						List.of(versionHeader()));
				Assertions.assertEquals(400, response.head().status(), response.head().raw());
				Assertions.assertEquals("", response.body());
				Assertions.assertFalse(response.head().hasHeader("Content-Type"));
			}
			Assertions.assertEquals(0, admissions.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void unsupported_notification_runs_policy_then_returns_empty_400()
			throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger interceptorInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), context -> {
					stages.add("admission");
					Assertions.assertTrue(context.notification());
					Assertions.assertEquals("future/event", context.jsonRpcMethod());
					return McpAdmissionDecision.acceptedAnonymous();
				})
				.withRequestRateLimiter(context -> {
					stages.add("request-limiter");
					return McpRateLimitDecision.allowed();
				})
				.withRequestInterceptor((invocation, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.invoke();
				})
				.withUnknownMirroredHeaderPolicy(
						McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS);
		McpHttpServerRuntime runtime = runtime(policy);

		try {
			int port = runtime.start().getPort();
			FixedResponse response = send(port,
					notification("future/event", "{\"_meta\":{"
							+ "\"vendor.example/tag\":1,"
							+ "\"io.modelcontextprotocol/future\":true}}"),
					List.of(versionHeader(),
							new McpChunkedHttpClient.RequestHeader("Mcp-Method", "wrong"),
							new McpChunkedHttpClient.RequestHeader("mcp-method", "duplicate"),
							new McpChunkedHttpClient.RequestHeader("Mcp-Name", "ignored"),
							new McpChunkedHttpClient.RequestHeader(
									"Mcp-Param-Unknown", "=?base64?***invalid***?=")));
			Assertions.assertEquals(400, response.head().status(), response.head().raw());
			Assertions.assertEquals("", response.body());
			Assertions.assertFalse(response.head().hasHeader("Content-Type"));
			Assertions.assertEquals(List.of("admission", "request-limiter"), stages);
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0,
					runtime.requestExecutionSnapshot().unknownMirroredHeaderOccurrences());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void blank_notification_methods_remain_classified_and_reach_policy()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), context -> {
					admissions.incrementAndGet();
					Assertions.assertTrue(context.notification());
					return McpAdmissionDecision.acceptedAnonymous();
				}));

		try {
			int port = runtime.start().getPort();
			for (String method : List.of("", "   ")) {
				FixedResponse response = send(port, notification(method, null),
						List.of(versionHeader()));
				Assertions.assertEquals(400, response.head().status(), response.head().raw());
				Assertions.assertEquals("", response.body());
			}
			Assertions.assertEquals(2, admissions.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void notification_protocol_header_failures_precede_policy_and_have_no_body()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> {
					admissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				}));

		try {
			int port = runtime.start().getPort();
			List<List<McpChunkedHttpClient.RequestHeader>> cases = List.of(
					List.of(),
					List.of(versionHeader(), versionHeader()),
					List.of(new McpChunkedHttpClient.RequestHeader(
							"MCP-Protocol-Version", "2099-01-01")));
			for (List<McpChunkedHttpClient.RequestHeader> headers : cases) {
				FixedResponse response = send(port, notification("future/event", null), headers);
				Assertions.assertEquals(400, response.head().status(), response.head().raw());
				Assertions.assertEquals("", response.body());
				Assertions.assertFalse(response.head().hasHeader("Content-Type"));
			}
			Assertions.assertEquals(0, admissions.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void notification_admission_rejection_preserves_status_safe_headers_and_no_body()
			throws Exception {
		AtomicInteger limiterInvocations = new AtomicInteger();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), ignored -> McpAdmissionDecision.rejected(
						new McpAdmissionRejection(401,
								new McpJsonRpcError(1_001, "Authentication required",
										Optional.empty()),
								Map.of("WWW-Authenticate",
										List.of("Bearer realm=soklet-mcp")))))
				.withRequestRateLimiter(ignored -> {
					limiterInvocations.incrementAndGet();
					return McpRateLimitDecision.allowed();
				});
		McpHttpServerRuntime runtime = runtime(policy);

		try {
			int port = runtime.start().getPort();
			FixedResponse response = send(port, notification("future/event", null),
					List.of(versionHeader()));
			Assertions.assertEquals(401, response.head().status(), response.head().raw());
			Assertions.assertEquals("Bearer realm=soklet-mcp",
					response.head().singleHeader("WWW-Authenticate"));
			Assertions.assertEquals("", response.body());
			Assertions.assertFalse(response.head().hasHeader("Content-Type"));
			Assertions.assertEquals(0, limiterInvocations.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void notification_request_limiter_denial_has_retry_after_and_no_body()
			throws Exception {
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous())
				.withRequestRateLimiter(ignored ->
						McpRateLimitDecision.denied(Duration.ofMillis(1)));
		McpHttpServerRuntime runtime = runtime(policy);

		try {
			int port = runtime.start().getPort();
			FixedResponse response = send(port, notification("future/event", null),
					List.of(versionHeader()));
			Assertions.assertEquals(429, response.head().status(), response.head().raw());
			Assertions.assertEquals("1", response.head().singleHeader("Retry-After"));
			Assertions.assertEquals("", response.body());
			Assertions.assertFalse(response.head().hasHeader("Content-Type"));
		} finally {
			runtime.close();
		}
	}

	@Test
	public void notification_policy_failures_fail_closed_without_a_json_rpc_body()
			throws Exception {
		List<McpHttpEndpointPolicy> cases = List.of(
				McpHttpEndpointPolicy.forDiscovery(
						CorsAuthorizer.rejectAllInstance(), ignored -> null),
				McpHttpEndpointPolicy.forDiscovery(
						CorsAuthorizer.rejectAllInstance(), ignored -> {
							throw new IllegalStateException("secret admission failure");
						}),
				McpHttpEndpointPolicy.forDiscovery(
						CorsAuthorizer.rejectAllInstance(),
						ignored -> McpAdmissionDecision.acceptedAnonymous())
						.withRequestRateLimiter(ignored -> null),
				McpHttpEndpointPolicy.forDiscovery(
						CorsAuthorizer.rejectAllInstance(),
						ignored -> McpAdmissionDecision.acceptedAnonymous())
						.withRequestRateLimiter(ignored -> {
							throw new IllegalStateException("secret limiter failure");
						}));

		for (McpHttpEndpointPolicy policy : cases) {
			McpHttpServerRuntime runtime = runtime(policy);
			try {
				int port = runtime.start().getPort();
				FixedResponse response = send(port, notification("future/event", null),
						List.of(versionHeader()));
				Assertions.assertEquals(500, response.head().status(), response.head().raw());
				Assertions.assertEquals("", response.body());
				Assertions.assertFalse(response.head().hasHeader("Content-Type"));
			} finally {
				runtime.close();
			}
		}
	}

	@Test
	public void classified_notification_cors_matrix_preserves_headers_and_rejects_origins_early()
			throws Exception {
		String allowedOrigin = "https://allowed.example";
		CorsAuthorizer corsAuthorizer = CorsAuthorizer.fromWhitelistedOrigins(
				Set.of(allowedOrigin));
		List<CorsNotificationCase> cases = List.of(
				new CorsNotificationCase("accepted cancellation",
						notification(CANCELLED, "{\"requestId\":\"unknown\"}"),
						McpHttpEndpointPolicy.forDiscovery(corsAuthorizer,
								ignored -> McpAdmissionDecision.acceptedAnonymous()),
						202, Map.of()),
				new CorsNotificationCase("unsupported notification",
						notification("future/event", null),
						McpHttpEndpointPolicy.forDiscovery(corsAuthorizer,
								ignored -> McpAdmissionDecision.acceptedAnonymous()),
						400, Map.of()),
				new CorsNotificationCase("admission rejection",
						notification("future/event", null),
						McpHttpEndpointPolicy.forDiscovery(corsAuthorizer,
								ignored -> McpAdmissionDecision.rejected(
										new McpAdmissionRejection(401,
												new McpJsonRpcError(1_001,
														"Authentication required",
														Optional.empty()),
												Map.of("WWW-Authenticate", List.of(
														"Bearer realm=soklet-mcp"))))),
						401, Map.of("WWW-Authenticate", "Bearer realm=soklet-mcp")),
				new CorsNotificationCase("request limiter rejection",
						notification("future/event", null),
						McpHttpEndpointPolicy.forDiscovery(corsAuthorizer,
								ignored -> McpAdmissionDecision.acceptedAnonymous())
								.withRequestRateLimiter(ignored ->
										McpRateLimitDecision.denied(Duration.ofMillis(1))),
						429, Map.of("Retry-After", "1")),
				new CorsNotificationCase("policy failure",
						notification("future/event", null),
						McpHttpEndpointPolicy.forDiscovery(corsAuthorizer, ignored -> {
							throw new IllegalStateException("secret policy failure");
						}), 500, Map.of()));

		for (CorsNotificationCase testCase : cases) {
			McpHttpServerRuntime runtime = runtime(testCase.policy());
			try {
				int port = runtime.start().getPort();
				FixedResponse response = send(port, testCase.body(),
						List.of(versionHeader(), originHeader(allowedOrigin)));
				Assertions.assertEquals(testCase.expectedStatus(), response.head().status(),
						testCase.description() + ": " + response.head().raw());
				Assertions.assertEquals(allowedOrigin,
						response.head().singleHeader("Access-Control-Allow-Origin"),
						testCase.description());
				Assertions.assertEquals("Origin", response.head().singleHeader("Vary"),
						testCase.description());
				Assertions.assertEquals("WWW-Authenticate",
						response.head().singleHeader("Access-Control-Expose-Headers"),
						testCase.description());
				Assertions.assertEquals("no-store",
						response.head().singleHeader("Cache-Control"),
						testCase.description());
				Assertions.assertEquals("", response.body(), testCase.description());
				Assertions.assertFalse(response.head().hasHeader("Content-Type"),
						testCase.description());
				for (Map.Entry<String, String> expectedHeader
						: testCase.expectedHeaders().entrySet())
					Assertions.assertEquals(expectedHeader.getValue(),
							response.head().singleHeader(expectedHeader.getKey()),
							testCase.description());
			} finally {
				runtime.close();
			}
		}

		AtomicInteger rejectedOriginAdmissions = new AtomicInteger();
		McpHttpEndpointPolicy rejectedOriginPolicy = McpHttpEndpointPolicy.forDiscovery(
				corsAuthorizer, ignored -> {
					rejectedOriginAdmissions.incrementAndGet();
					return McpAdmissionDecision.acceptedAnonymous();
				});
		McpHttpServerRuntime rejectedOriginRuntime = runtime(rejectedOriginPolicy);
		try {
			int port = rejectedOriginRuntime.start().getPort();
			FixedResponse response = send(port, notification("future/event", null),
					List.of(versionHeader(), originHeader("https://rejected.example")));
			Assertions.assertEquals(403, response.head().status(), response.head().raw());
			Assertions.assertEquals("no-store",
					response.head().singleHeader("Cache-Control"));
			Assertions.assertEquals("", response.body());
			Assertions.assertFalse(response.head().hasHeader("Content-Type"));
			Assertions.assertFalse(
					response.head().hasHeader("Access-Control-Allow-Origin"));
			Assertions.assertEquals(0, rejectedOriginAdmissions.get(),
					"Origin rejection must precede notification admission.");
		} finally {
			rejectedOriginRuntime.close();
		}
	}

	@Test
	public void notification_admission_outputs_fail_closed_on_reserved_codes_and_unsafe_headers()
			throws Exception {
		List<NotificationAdmissionHardeningCase> cases = List.of(
				new NotificationAdmissionHardeningCase("reserved JSON-RPC error code",
						new McpJsonRpcError(McpJsonRpcError.INVALID_PARAMS,
								"Must not escape", Optional.empty()), Map.of()),
				new NotificationAdmissionHardeningCase("framework-owned response header",
						new McpJsonRpcError(1_001, "Rejected", Optional.empty()),
						Map.of("Content-Type", List.of("text/plain; secret=true"))),
				new NotificationAdmissionHardeningCase("legacy session response header",
						new McpJsonRpcError(1_001, "Rejected", Optional.empty()),
						Map.of("mCp-SeSsIoN-Id",
								List.of("legacy-session-secret"))),
				new NotificationAdmissionHardeningCase("legacy replay response header",
						new McpJsonRpcError(1_001, "Rejected", Optional.empty()),
						Map.of("lAsT-EvEnT-iD",
								List.of("legacy-replay-secret"))));

		for (NotificationAdmissionHardeningCase testCase : cases) {
			AtomicInteger limiterInvocations = new AtomicInteger();
			AtomicInteger interceptorInvocations = new AtomicInteger();
			McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
					CorsAuthorizer.rejectAllInstance(),
					ignored -> McpAdmissionDecision.rejected(new McpAdmissionRejection(
							401, testCase.error(), testCase.headers())))
					.withRequestRateLimiter(ignored -> {
						limiterInvocations.incrementAndGet();
						return McpRateLimitDecision.allowed();
					})
					.withRequestInterceptor((invocation, continuation) -> {
						interceptorInvocations.incrementAndGet();
						return continuation.invoke();
					});
			McpHttpServerRuntime runtime = runtime(policy);
			try {
				int port = runtime.start().getPort();
				FixedResponse response = send(port, notification("future/event", null),
						List.of(versionHeader()));
				Assertions.assertEquals(500, response.head().status(),
						testCase.description() + ": " + response.head().raw());
				Assertions.assertEquals("no-store",
						response.head().singleHeader("Cache-Control"),
						testCase.description());
				Assertions.assertEquals("", response.body(), testCase.description());
				Assertions.assertFalse(response.head().hasHeader("Content-Type"),
						testCase.description());
				Assertions.assertFalse(response.head().hasHeader("MCP-Session-Id"),
						testCase.description());
				Assertions.assertFalse(response.head().hasHeader("Last-Event-ID"),
						testCase.description());
				Assertions.assertFalse(response.head().raw().contains("secret"),
						testCase.description());
				Assertions.assertFalse(response.body().contains("secret"),
						testCase.description());
				Assertions.assertEquals(0, limiterInvocations.get(),
						"Admission rejection must precede the notification limiter: "
								+ testCase.description());
				Assertions.assertEquals(0, interceptorInvocations.get(),
						"Admission rejection must precede notification interception: "
								+ testCase.description());
			} finally {
				runtime.close();
			}
		}
	}

	private static McpHttpServerRuntime runtime(McpHttpEndpointPolicy policy) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"notification-test", "3.6.0-SNAPSHOT"))
				.build();
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), policy, endpoint);
	}

	private static FixedResponse send(int port, String body,
			List<McpChunkedHttpClient.RequestHeader> headers) throws Exception {
		try (McpChunkedHttpClient client =
					McpChunkedHttpClient.postMcpMessage(port, body, headers)) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			return new FixedResponse(head, client.readFixedBody(head));
		}
	}

	private static String notification(String method, String paramsJson) {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\""
				+ (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
	}

	private static String toolCallRequest(String id, String toolName) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"name\":\""
				+ toolName + "\",\"arguments\":{},\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
	}

	private static List<McpChunkedHttpClient.RequestHeader> toolCallHeaders(
			String toolName) {
		return List.of(versionHeader(),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", "tools/call"),
				new McpChunkedHttpClient.RequestHeader("Mcp-Name", toolName));
	}

	private static McpChunkedHttpClient.RequestHeader versionHeader() {
		return new McpChunkedHttpClient.RequestHeader(
				"MCP-Protocol-Version", PROTOCOL_VERSION);
	}

	private static McpChunkedHttpClient.RequestHeader originHeader(String origin) {
		return new McpChunkedHttpClient.RequestHeader("Origin", origin);
	}

	private record FixedResponse(McpChunkedHttpClient.HttpResponseHead head,
			String body) {
	}

	private record NotificationPrecedenceCase(String description, String body,
			List<McpChunkedHttpClient.RequestHeader> headers,
			boolean rejectAdmission, int expectedStatus, int expectedAdmissions,
			int expectedLimiterInvocations, boolean expectJsonRpcBody) {
	}

	private record CorsNotificationCase(String description, String body,
			McpHttpEndpointPolicy policy, int expectedStatus,
			Map<String, String> expectedHeaders) {
	}

	private record NotificationAdmissionHardeningCase(String description,
			McpJsonRpcError error, Map<String, List<String>> headers) {
	}
}
