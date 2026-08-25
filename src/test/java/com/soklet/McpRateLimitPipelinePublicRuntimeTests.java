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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Black-box real-listener coverage for compound MCP rate-limit ordering and
 * no-refund behavior.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpRateLimitPipelinePublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String TOOL_NAME = "rate-limit.pipeline";
	private static final Duration REQUEST_RETRY_AFTER = Duration.ofSeconds(11);
	private static final Duration TOOL_RETRY_AFTER = Duration.ofSeconds(7);
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	@Test
	public void successfulToolCallTraversesTheExactPublicStageOrder()
			throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		McpAdmissionIdentity identity = authenticatedIdentity("success");
		OnePermitLimiter requestLimiter = new OnePermitLimiter(
				REQUEST_RETRY_AFTER, ignored -> stages.add("request-limiter"));
		OnePermitLimiter toolLimiter = new OnePermitLimiter(
				TOOL_RETRY_AFTER, ignored -> stages.add("tool-limiter"));
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpEndpoint endpoint = endpoint("rate-limit-order-runtime-test",
				(request, arguments, features) -> {
					stages.add("handler");
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("ordered");
				});
		McpServer server = serverBuilder(endpoint, context -> {
			stages.add("auth-authz-admission+accepted-identity");
			admissions.add(context);
			return McpAdmissionDecision.accepted(identity);
		}, toolLimiter)
				.requestRateLimiter(requestLimiter)
				.handlerInterceptor((context, continuation) -> {
					stages.add("interceptor-before");
					McpOperationResult result = continuation.proceed();
					stages.add("interceptor-after");
					return result;
				})
				.toolOutputSanitizer((request, toolName, arguments, output) -> {
					stages.add("result-sanitizer");
					sanitizerInvocations.incrementAndGet();
					return output;
				})
				.build();

		try {
			server.start();
			HttpResponse<String> response = callTool(server, "ordered");

			assertSuccess(response, "ordered");
			Assertions.assertTrue(response.body().contains("\"text\":\"ordered\""),
					response.body());
			Assertions.assertEquals(List.of(
					"auth-authz-admission+accepted-identity",
					"request-limiter",
					"tool-limiter",
					"interceptor-before",
					"handler",
					"interceptor-after",
					"result-sanitizer"), stages);
			Assertions.assertEquals(1, handlerInvocations.get());
			Assertions.assertEquals(1, sanitizerInvocations.get());
			assertLimiterSnapshot(requestLimiter, 1, 1);
			assertLimiterSnapshot(toolLimiter, 1, 1);
			assertAcceptedContext(admissions.get(0), requestLimiter.contexts().get(0),
					endpoint, identity, McpRateLimitTarget.REQUEST);
			assertAcceptedContext(admissions.get(0), toolLimiter.contexts().get(0),
					endpoint, identity, McpRateLimitTarget.TOOL);
			Assertions.assertSame(requestLimiter.contexts().get(0).getRequest(),
					toolLimiter.contexts().get(0).getRequest());
			assertIdleStartedDiagnostics(server);
		} finally {
			stopAndAssertClean(server);
		}
	}

	@Test
	public void toolDenialWinsAfterRequestChargeAndRetryProvesNoRefund()
			throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		List<McpAdmissionContext> admissions = new CopyOnWriteArrayList<>();
		McpAdmissionIdentity identity = authenticatedIdentity("compound-denial");
		OnePermitLimiter requestLimiter = new OnePermitLimiter(
				REQUEST_RETRY_AFTER, ignored -> stages.add("request-limiter"));
		OnePermitLimiter toolLimiter = new OnePermitLimiter(
				TOOL_RETRY_AFTER, ignored -> stages.add("tool-limiter"));
		toolLimiter.consumePermitForSetup();
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpEndpoint endpoint = endpoint("rate-limit-denial-runtime-test",
				(request, arguments, features) -> {
					handlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("must-not-run");
				});
		McpServer server = serverBuilder(endpoint, context -> {
			stages.add("auth-authz-admission+accepted-identity");
			admissions.add(context);
			return McpAdmissionDecision.accepted(identity);
		}, toolLimiter)
				.requestRateLimiter(requestLimiter)
				.handlerInterceptor((context, continuation) -> {
					interceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.toolOutputSanitizer((request, toolName, arguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				})
				.build();

		try {
			server.start();
			HttpResponse<String> toolDenied = callTool(server, "tool-denied");
			HttpResponse<String> requestDenied = callTool(server, "request-denied");

			assertRateLimited(toolDenied, "tool-denied", TOOL_RETRY_AFTER);
			assertRateLimited(requestDenied, "request-denied",
					REQUEST_RETRY_AFTER);
			Assertions.assertEquals(List.of(
					"auth-authz-admission+accepted-identity",
					"request-limiter",
					"tool-limiter",
					"auth-authz-admission+accepted-identity",
					"request-limiter"), stages,
					"A tool denial must stop application dispatch, and the retry must "
							+ "stop at the retained request charge.");
			assertLimiterSnapshot(requestLimiter, 2, 1);
			assertLimiterSnapshot(toolLimiter, 1, 0);
			Assertions.assertEquals(2, admissions.size());
			assertAcceptedContext(admissions.get(0), requestLimiter.contexts().get(0),
					endpoint, identity, McpRateLimitTarget.REQUEST);
			assertAcceptedContext(admissions.get(0), toolLimiter.contexts().get(0),
					endpoint, identity, McpRateLimitTarget.TOOL);
			assertAcceptedContext(admissions.get(1), requestLimiter.contexts().get(1),
					endpoint, identity, McpRateLimitTarget.REQUEST);
			Assertions.assertEquals(0, interceptorInvocations.get());
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());
			assertIdleStartedDiagnostics(server);
		} finally {
			stopAndAssertClean(server);
		}
	}

	@Test
	public void successfulChargesAreRetainedAfterEveryDownstreamFailure() {
		for (DownstreamFailure failure : DownstreamFailure.values()) {
			try {
				assertSuccessfulChargesAreRetainedAfterFailure(failure);
			} catch (Exception | AssertionError throwable) {
				throw new AssertionError("Downstream failure case failed: "
						+ failure, throwable);
			}
		}
	}

	private static void assertSuccessfulChargesAreRetainedAfterFailure(
			DownstreamFailure failure) throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		McpAdmissionIdentity identity = authenticatedIdentity(
				failure.name().toLowerCase(Locale.ROOT));
		OnePermitLimiter requestLimiter = new OnePermitLimiter(
				REQUEST_RETRY_AFTER, ignored -> stages.add("request-limiter"));
		OnePermitLimiter toolLimiter = new OnePermitLimiter(
				TOOL_RETRY_AFTER, ignored -> stages.add("tool-limiter"));
		AtomicInteger interceptorInvocations = new AtomicInteger();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpEndpoint endpoint = endpoint("rate-limit-failure-runtime-test",
				(request, arguments, features) -> {
					stages.add("handler");
					handlerInvocations.incrementAndGet();
					if (failure == DownstreamFailure.HANDLER)
						throw new IllegalStateException(
								"handler-secret-must-not-leak");
					if (failure == DownstreamFailure.RESULT)
						return McpResourcePage.builder().build();
					return McpCompleteResult.fromToolText("must-not-complete");
				});
		McpServer server = serverBuilder(endpoint, context -> {
			stages.add("auth-authz-admission+accepted-identity");
			return McpAdmissionDecision.accepted(identity);
		}, toolLimiter)
				.requestRateLimiter(requestLimiter)
				.handlerInterceptor((context, continuation) -> {
					stages.add("interceptor-before");
					interceptorInvocations.incrementAndGet();
					if (failure == DownstreamFailure.INTERCEPTOR)
						throw new IllegalStateException(
								"interceptor-secret-must-not-leak");
					McpOperationResult result = continuation.proceed();
					stages.add("interceptor-after");
					return result;
				})
				.toolOutputSanitizer((request, toolName, arguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				})
				.build();

		try {
			server.start();
			String failedId = failure.name().toLowerCase(Locale.ROOT) + "-failed";
			HttpResponse<String> failed = callTool(server, failedId);
			assertInternalError(failed, failedId);
			Assertions.assertFalse(failed.body().contains("secret"), failed.body());
			Assertions.assertEquals(expectedFailureStages(failure), stages);
			assertLimiterSnapshot(requestLimiter, 1, 1);
			assertLimiterSnapshot(toolLimiter, 1, 1);

			String requestRetryId = failure.name().toLowerCase(Locale.ROOT)
					+ "-request-retry";
			HttpResponse<String> requestRetry = callTool(server, requestRetryId);
			assertRateLimited(requestRetry, requestRetryId, REQUEST_RETRY_AFTER);
			List<String> expectedWithRequestRetry = new ArrayList<>(
					expectedFailureStages(failure));
			expectedWithRequestRetry.add(
					"auth-authz-admission+accepted-identity");
			expectedWithRequestRetry.add("request-limiter");
			Assertions.assertEquals(expectedWithRequestRetry, stages);
			assertLimiterSnapshot(requestLimiter, 2, 1);
			assertLimiterSnapshot(toolLimiter, 1, 1);
			Assertions.assertEquals(1, interceptorInvocations.get());
			Assertions.assertEquals(
					failure == DownstreamFailure.INTERCEPTOR ? 0 : 1,
					handlerInvocations.get());
			Assertions.assertEquals(0, sanitizerInvocations.get());
			assertIdleStartedDiagnostics(server);
		} finally {
			stopAndAssertClean(server);
		}

		List<String> probeStages = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger probeInterceptorInvocations = new AtomicInteger();
		AtomicInteger probeHandlerInvocations = new AtomicInteger();
		AtomicInteger probeSanitizerInvocations = new AtomicInteger();
		McpEndpoint probeEndpoint = endpoint("rate-limit-tool-retry-runtime-test",
				(request, arguments, features) -> {
					probeHandlerInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText("must-not-run");
				});
		McpServer probeServer = serverBuilder(probeEndpoint, context -> {
			probeStages.add("auth-authz-admission+accepted-identity");
			return McpAdmissionDecision.accepted(identity);
		}, toolLimiter.withObserver(ignored -> probeStages.add("tool-limiter")))
				.handlerInterceptor((context, continuation) -> {
					probeInterceptorInvocations.incrementAndGet();
					return continuation.proceed();
				})
				.toolOutputSanitizer((request, toolName, arguments, output) -> {
					probeSanitizerInvocations.incrementAndGet();
					return output;
				})
				.build();

		try {
			probeServer.start();
			String toolRetryId = failure.name().toLowerCase(Locale.ROOT)
					+ "-tool-retry";
			HttpResponse<String> toolRetry = callTool(probeServer, toolRetryId);

			assertRateLimited(toolRetry, toolRetryId, TOOL_RETRY_AFTER);
			Assertions.assertEquals(List.of(
					"auth-authz-admission+accepted-identity",
					"tool-limiter"), probeStages);
			assertLimiterSnapshot(toolLimiter, 2, 1);
			Assertions.assertEquals(0, probeInterceptorInvocations.get());
			Assertions.assertEquals(0, probeHandlerInvocations.get());
			Assertions.assertEquals(0, probeSanitizerInvocations.get());
			assertIdleStartedDiagnostics(probeServer);
		} finally {
			stopAndAssertClean(probeServer);
		}
	}

	private static List<String> expectedFailureStages(
			DownstreamFailure failure) {
		List<String> stages = new ArrayList<>(List.of(
				"auth-authz-admission+accepted-identity",
				"request-limiter",
				"tool-limiter",
				"interceptor-before"));
		if (failure != DownstreamFailure.INTERCEPTOR)
			stages.add("handler");
		if (failure == DownstreamFailure.RESULT)
			stages.add("interceptor-after");
		return stages;
	}

	private static McpAdmissionIdentity authenticatedIdentity(String suffix) {
		return McpAdmissionIdentity
				.withRateLimitPartitionKey("rate-limit-" + suffix)
				.authorizationPartitionKey("authorization-" + suffix)
				.principal("principal-" + suffix)
				.build();
	}

	private static McpEndpoint endpoint(String serverName,
			McpToolHandler<McpJsonObject> handler) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler(handler)
				.build();
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						serverName, "4.0.0-SNAPSHOT").build())
				.tool(tool)
				.build();
	}

	private static McpServer.Builder serverBuilder(McpEndpoint endpoint,
			McpAdmissionController admissionController,
			McpRateLimiter toolRateLimiter) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(admissionController)
				.toolRateLimiter(toolRateLimiter)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static HttpResponse<String> callTool(McpServer server, String id)
			throws Exception {
		int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", TOOL_NAME)
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(
				StandardCharsets.UTF_8));
	}

	private static void assertSuccess(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
	}

	private static void assertInternalError(HttpResponse<String> response,
			String expectedId) {
		Assertions.assertEquals(500, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ expectedId + "\",\"error\":{\"code\":-32603,"
				+ "\"message\":\"Internal error\"}}", response.body());
	}

	private static void assertRateLimited(HttpResponse<String> response,
			String expectedId, Duration expectedRetryAfter) {
		Assertions.assertEquals(429, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertEquals(Long.toString(expectedRetryAfter.toSeconds()),
				response.headers().firstValue("Retry-After").orElseThrow());
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ expectedId + "\",\"error\":{\"code\":-31999,"
				+ "\"message\":\"Rate limited\"}}", response.body());
	}

	private static void assertLimiterSnapshot(OnePermitLimiter limiter,
			int expectedAttempts, int expectedSuccessfulAcquisitions) {
		Assertions.assertEquals(expectedAttempts, limiter.attempts());
		Assertions.assertEquals(expectedSuccessfulAcquisitions,
				limiter.successfulAcquisitions());
		Assertions.assertEquals(expectedAttempts, limiter.contexts().size());
	}

	private static void assertAcceptedContext(McpAdmissionContext admission,
			McpRateLimitContext rateLimit, McpEndpoint endpoint,
			McpAdmissionIdentity identity, McpRateLimitTarget target) {
		Assertions.assertSame(admission.getRequest(), rateLimit.getRequest());
		Assertions.assertSame(endpoint, admission.getEndpoint());
		Assertions.assertSame(endpoint, rateLimit.getEndpoint());
		Assertions.assertEquals(target, rateLimit.getTarget());
		Assertions.assertEquals("tools/call", rateLimit.getJsonRpcMethod());
		Assertions.assertEquals(TOOL_NAME,
				rateLimit.getOperationName().orElseThrow());
		Assertions.assertEquals(identity.getRateLimitPartitionKey(),
				rateLimit.getAdmissionIdentity().getRateLimitPartitionKey());
		Assertions.assertEquals(identity.getAuthorizationPartitionKey(),
				rateLimit.getAdmissionIdentity().getAuthorizationPartitionKey());
		Assertions.assertEquals(identity.getPrincipal(),
				rateLimit.getAdmissionIdentity().getPrincipal());
	}

	private static void assertIdleStartedDiagnostics(McpServer server) {
		McpServerDiagnostics diagnostics = awaitIdleDiagnostics(server);
		Assertions.assertEquals(McpServerStatus.STARTED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isPresent());
		assertZeroLoad(diagnostics);
	}

	private static McpServerDiagnostics awaitIdleDiagnostics(McpServer server) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpServerDiagnostics latest = server.getDiagnostics();
		while (System.nanoTime() - deadline < 0L) {
			latest = server.getDiagnostics();
			if (isZeroLoad(latest))
				return latest;
			Thread.onSpinWait();
		}
		Assertions.fail("Timed out waiting for idle MCP diagnostics; latest="
				+ latest);
		throw new AssertionError();
	}

	private static void stopAndAssertClean(McpServer server) {
		server.stop();
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.STOPPED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isEmpty());
		assertZeroLoad(diagnostics);
	}

	private static void assertZeroLoad(McpServerDiagnostics diagnostics) {
		Assertions.assertEquals(0, diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(0, diagnostics.getQueuedRequests());
		Assertions.assertEquals(0, diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(0, diagnostics.getActiveSubscriptions());
	}

	private static boolean isZeroLoad(McpServerDiagnostics diagnostics) {
		return diagnostics.getActiveHandlerExecutions() == 0
				&& diagnostics.getQueuedRequests() == 0
				&& diagnostics.getActiveRequestStreams() == 0
				&& diagnostics.getActiveSubscriptions() == 0;
	}

	private enum DownstreamFailure {
		INTERCEPTOR,
		HANDLER,
		RESULT
	}

	private static final class OnePermitLimiter implements McpRateLimiter {
		private final Duration retryAfter;
		private final AtomicBoolean permitAvailable;
		private final AtomicInteger attempts;
		private final AtomicInteger successfulAcquisitions;
		private final List<McpRateLimitContext> contexts;
		private volatile Consumer<McpRateLimitContext> observer;

		private OnePermitLimiter(Duration retryAfter,
				Consumer<McpRateLimitContext> observer) {
			this.retryAfter = retryAfter;
			this.permitAvailable = new AtomicBoolean(true);
			this.attempts = new AtomicInteger();
			this.successfulAcquisitions = new AtomicInteger();
			this.contexts = new CopyOnWriteArrayList<>();
			this.observer = observer;
		}

		@Override
		public McpRateLimitDecision acquire(McpRateLimitContext context) {
			this.attempts.incrementAndGet();
			this.contexts.add(context);
			this.observer.accept(context);
			if (this.permitAvailable.compareAndSet(true, false)) {
				this.successfulAcquisitions.incrementAndGet();
				return McpRateLimitDecision.allowed();
			}
			return McpRateLimitDecision.denied(this.retryAfter);
		}

		private void consumePermitForSetup() {
			Assertions.assertTrue(this.permitAvailable.compareAndSet(true, false));
		}

		private OnePermitLimiter withObserver(
				Consumer<McpRateLimitContext> observer) {
			this.observer = observer;
			return this;
		}

		private int attempts() {
			return this.attempts.get();
		}

		private int successfulAcquisitions() {
			return this.successfulAcquisitions.get();
		}

		private List<McpRateLimitContext> contexts() {
			return List.copyOf(this.contexts);
		}
	}
}
