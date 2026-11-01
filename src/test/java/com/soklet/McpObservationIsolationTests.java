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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real socket coverage for bounded observation isolation and protocol admission. */
@Timeout(60)
class McpObservationIsolationTests {
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(McpProtocolVersion.V2026_07_28);

	@Test
	void blockedFinishAndTraceLoggingDoNotBlockAnotherConnection() throws Exception {
		for (boolean blockTraceLog : List.of(false, true)) {
			CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(2);
			AtomicReference<String> callbackThread = new AtomicReference<>();
			AtomicBoolean virtualCallback = new AtomicBoolean();
			AtomicInteger logs = new AtomicInteger();
			LifecycleObserver observer = new LifecycleObserver() {
				@Override public void didReceiveLogEvent(LogEvent event) {
					if (blockTraceLog && event.getLogEventType() == LogEventType.MCP_TRACE_CORRELATION && logs.incrementAndGet() == 1) {
						callbackThread.set(Thread.currentThread().getName()); virtualCallback.set(Thread.currentThread().isVirtual()); entered.countDown(); awaitIgnoringInterrupts(release);
					}
				}
				@Override public void didFinishMcpRequestHandling(McpRequestContext request, McpRequestOutcome outcome,
						McpJsonRpcError error, Duration duration, List<Throwable> failures) {
					if (!blockTraceLog && request.getRequestId().orElseThrow().asString().orElseThrow().equals("first")) {
						callbackThread.set(Thread.currentThread().getName()); virtualCallback.set(Thread.currentThread().isVirtual()); entered.countDown(); awaitIgnoringInterrupts(release);
					}
					finished.countDown();
				}
			};
			Fixture fixture = new Fixture(builder -> builder.requestHandlerConcurrency(2).requestHandlerQueueCapacity(2)
					.logRawValidatedTraceIds(blockTraceLog), observer);
			try (HttpClient client = fixture.client) {
				Soklet.run(fixture.config, () -> {
					try {
						assertEquals(200, fixture.call("first", "tools/call", ",\"name\":\"ok\",\"arguments\":{}").statusCode());
						assertTrue(entered.await(5, TimeUnit.SECONDS));
						assertTrue(callbackThread.get().startsWith("soklet-mcp-observer-"), callbackThread.get());
						assertFalse(virtualCallback.get(), "Java 21 observation callbacks must not pin virtual-thread carriers.");
						assertEquals(200, fixture.call("second", "tools/call", ",\"name\":\"ok\",\"arguments\":{}").statusCode());
					} finally { release.countDown(); }
					assertTrue(finished.await(5, TimeUnit.SECONDS));
				});
			}
		}
	}

	@Test
	void saturatedObservationBudgetOmitsPairsWithoutRejectingRequestsAndRetainsAdmittedFinishes() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger starts = new AtomicInteger(), finishes = new AtomicInteger(), omissions = new AtomicInteger();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) {
				if (event.getLogEventType() == LogEventType.MCP_SERVER_CONFIGURATION
						&& event.getMessage().contains("observations were omitted")) omissions.incrementAndGet();
			}
			@Override public void didStartMcpRequestHandling(McpRequestContext request) { starts.incrementAndGet(); }
			@Override public void didFinishMcpRequestHandling(McpRequestContext request, McpRequestOutcome outcome,
					McpJsonRpcError error, Duration duration, List<Throwable> failures) {
				if (request.getRequestId().orElseThrow().asString().orElseThrow().equals("first")) {
					entered.countDown(); awaitIgnoringInterrupts(release);
				}
				finishes.incrementAndGet();
			}
		};
		Fixture fixture = new Fixture(builder -> builder.requestHandlerConcurrency(1).requestHandlerQueueCapacity(1), observer);
		// Incomplete shutdown must be asserted while an admitted observation remains blocked.
		SokletTestScope scope = SokletTestScope.of(Soklet.run(fixture.config));
		Soklet soklet = scope.soklet();
		try {
			assertEquals(200, fixture.call("first", "tools/call", ",\"name\":\"ok\",\"arguments\":{}").statusCode());
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			long requestLoopDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			for (int index = 2; index <= 128 && System.nanoTime() < requestLoopDeadline; index++) {
				HttpResponse<String> response = fixture.call("queued-" + index, "tools/call", ",\"name\":\"ok\",\"arguments\":{}");
				assertTrue(Set.of(200, 503).contains(response.statusCode()), response.statusCode() + " " + response.body());
				assertFalse(response.body().isEmpty(), "Observation pressure must never produce a bodyless rejection.");
			}
			assertTrue(starts.get() > 2 && starts.get() < 128, "Only a bounded set of paired observations may be retained.");
			assertEquals(1, omissions.get(), "Capacity diagnostics must be rate limited.");
			assertEquals(0, finishes.get());
			SokletShutdownIncompleteException failure = assertThrows(SokletShutdownIncompleteException.class, soklet::close);
			assertTrue(failure.getInternalShutdownResult().participantResult(InternalLifecycleComponentType.MCP)
					.orElseThrow().residualActivity().contains(InternalResidualActivityType.CALLBACK));
		} finally {
			release.countDown();
			long until = System.nanoTime() + WAIT.toNanos();
			while (finishes.get() != starts.get() && System.nanoTime() < until) Thread.sleep(5);
			assertEquals(starts.get(), finishes.get(), "Every admitted start needs exactly one retained finish.");
			try { scope.close(); } catch (SokletShutdownIncompleteException ignored) {}
			fixture.client.close();
		}
	}

	@Test
	void protocolConcurrencyAndQueueOptionsBoundAdmissionIndependentlyOfHandlerPool() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger admissions = new AtomicInteger();
		Fixture fixture = new Fixture(builder -> builder.protocolExecutorConcurrency(1).protocolExecutorQueueCapacity(1)
				.requestHandlerConcurrency(8).admissionController(context -> {
					admissions.incrementAndGet(); entered.countDown(); awaitIgnoringInterrupts(release);
					return McpAdmissionDecision.accepted();
				}), new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} });
		try (HttpClient client = fixture.client) {
			Soklet.run(fixture.config, () -> {
				try {
					var first = fixture.client.sendAsync(fixture.request("first", "tools/call", ",\"name\":\"ok\",\"arguments\":{}"), HttpResponse.BodyHandlers.ofString());
					assertTrue(entered.await(5, TimeUnit.SECONDS));
					var second = fixture.client.sendAsync(fixture.request("second", "tools/call", ",\"name\":\"ok\",\"arguments\":{}"), HttpResponse.BodyHandlers.ofString());
					long until = System.nanoTime() + WAIT.toNanos();
					while (fixture.accepted.get() < 2 && System.nanoTime() < until) Thread.sleep(5);
					assertEquals(503, fixture.call("third", "tools/call", ",\"name\":\"ok\",\"arguments\":{}").statusCode());
					assertEquals(1, admissions.get());
					release.countDown();
					assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
					assertEquals(200, second.get(5, TimeUnit.SECONDS).statusCode());
				} finally { release.countDown(); }
			});
		}
		assertThrows(IllegalArgumentException.class, () -> McpServer.withPort(0).protocolExecutorConcurrency(0));
		assertThrows(IllegalArgumentException.class, () -> McpServer.withPort(0).protocolExecutorQueueCapacity(-1));
	}

	@Test
	void maximumProtocolQueueCapacityDoesNotEagerlyAllocateAnArray() throws Exception {
		Fixture fixture = new Fixture(builder -> builder.protocolExecutorQueueCapacity(Integer.MAX_VALUE),
				new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} });
		try (HttpClient client = fixture.client) {
			Soklet.run(fixture.config, () -> {
				assertEquals(200, fixture.call("queue", "tools/call", ",\"name\":\"ok\",\"arguments\":{}").statusCode());
			});
		}
	}

	@Test
	void singletonProtocolPoolRejectsCatalogAndTaskProjectionConfigurationAtBuild() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("projection", "1").build(), VERSIONS)
				.toolRegistrations(List.of(McpToolRegistration.withName("ok", VERSIONS).jsonObjectArguments()
						.handler((request, arguments, features) -> McpCompleteResult.fromToolText("ok")).build()))
				.subscriptionProtocolVersions(VERSIONS)
				.subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
						McpSubscriptionEventPublisher.fromInMemoryDefaults(), Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED)).build())
				.build();
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).protocolExecutorConcurrency(1)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.subscriptionAuthorizer((context, features) -> McpSubscriptionAuthorization.Allowed.fromValidUntil(java.time.Instant.now().plusSeconds(60)))
				.build());
		assertTrue(failure.getMessage().contains("protocolExecutorConcurrency"), failure.getMessage());
		McpEndpoint tasks = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("projection", "1").build(), VERSIONS)
				.taskProtocolVersions(VERSIONS).subscriptionProtocolVersions(VERSIONS).build();
		IllegalStateException taskFailure = assertThrows(IllegalStateException.class, () -> McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(tasks))).protocolExecutorConcurrency(1)
				.taskManager(McpTaskManager.fromInMemoryDefaults())
				.subscriptionAuthorizer((context, features) -> McpSubscriptionAuthorization.Allowed.fromValidUntil(java.time.Instant.now().plusSeconds(60)))
				.build());
		assertTrue(taskFailure.getMessage().contains("protocolExecutorConcurrency"), taskFailure.getMessage());
	}

	@Test
	void configuredCorsExposedHeadersAreMergedWithProtocolHeaders() throws Exception {
		String origin = "https://client.example.test";
		CorsAuthorizer cors = new CorsAuthorizer() {
			@Override public Optional<CorsResponse> authorize(Request request, Cors context) {
				return Optional.of(CorsResponse.withAccessControlAllowOrigin(origin)
						.accessControlExposeHeaders(Set.of("X-Application", "MCP-Protocol-Version")).build());
			}
			@Override public Optional<CorsPreflightResponse> authorizePreflight(Request request, CorsPreflight context,
					Map<HttpMethod, ResourceMethod> methods) { return Optional.empty(); }
		};
		Fixture fixture = new Fixture(builder -> builder.corsAuthorizer(cors),
				new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} });
		try (HttpClient client = fixture.client) {
			Soklet.run(fixture.config, () -> {
				HttpRequest request = HttpRequest.newBuilder(fixture.request("cors", "tools/call", ",\"name\":\"ok\",\"arguments\":{}"),
						(name, value) -> true).header("Origin", origin).build();
				HttpResponse<String> response = fixture.client.send(request, HttpResponse.BodyHandlers.ofString());
				assertEquals(200, response.statusCode());
				String exposed = response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow();
				assertTrue(exposed.contains("X-Application"), exposed);
				assertTrue(exposed.contains("MCP-Protocol-Version"), exposed);
				assertEquals(1, response.headers().allValues("Access-Control-Expose-Headers").size());
			});
		}
	}

	@Test
	void omittedObservationPreservesCallerAwareCatalogAndHandlerContext() throws Exception {
		CountDownLatch finishEntered = new CountDownLatch(1), release = new CountDownLatch(1), pairedFinishes = new CountDownLatch(3);
		Object principal = new Object(), applicationContext = new Object();
		Map<String, Request> admittedRequests = new java.util.concurrent.ConcurrentHashMap<>();
		Map<String, McpRequestContext> policyContexts = new java.util.concurrent.ConcurrentHashMap<>();
		List<String> observedRequests = new CopyOnWriteArrayList<>();
		McpAdmissionIdentity identity = McpAdmissionIdentity.withRateLimitPartitionKey("rate")
				.authorizationPartitionKey("authorization").principal(principal).applicationContext(applicationContext).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("context", "1").build(), VERSIONS)
				.toolRegistrations(List.of(McpToolRegistration.withName("ok", VERSIONS).jsonObjectArguments()
						.handler((context, arguments, features) -> {
							String id = context.getRequestId().orElseThrow().asString().orElseThrow();
							assertSame(admittedRequests.get(id), context.getRequest());
							assertSame(policyContexts.get(id), context);
							assertSame(principal, context.getAdmissionIdentity().getPrincipal().orElseThrow());
							assertSame(applicationContext, context.getAdmissionIdentity().getApplicationContext().orElseThrow());
							assertEquals("authorization", context.getAdmissionIdentity().getAuthorizationPartitionKey().orElseThrow());
							assertTrue(context.getStartedAt().isPresent());
							assertTrue(context.getTraceContext().isPresent());
							assertSame(features.getCancelationToken(), features.require(CancelationToken.class));
							return McpCompleteResult.fromToolText("ok");
						}).build())).build();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) {}
			@Override public void didStartMcpRequestHandling(McpRequestContext context) {
				observedRequests.add(context.getRequestId().orElseThrow().asString().orElseThrow());
			}
			@Override public void didFinishMcpRequestHandling(McpRequestContext context, McpRequestOutcome outcome,
					McpJsonRpcError error, Duration duration, List<Throwable> failures) {
				if (context.getRequestId().orElseThrow().asString().orElseThrow().equals("first")) {
					finishEntered.countDown(); awaitIgnoringInterrupts(release);
				}
				pairedFinishes.countDown();
			}
		};
		Fixture fixture = new Fixture(builder -> builder.requestHandlerConcurrency(1).requestHandlerQueueCapacity(1)
				.protocolExecutorConcurrency(1).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> {
					admittedRequests.put(context.getRequestId().orElseThrow().asString().orElseThrow(), context.getRequest());
					return McpAdmissionDecision.accepted(identity);
				}).catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators((context, registration, features) -> {
					policyContexts.put(context.getRequestId().orElseThrow().asString().orElseThrow(), context);
					return true;
				}, (context, registration, features) -> true)), observer);
		try (HttpClient client = fixture.client) {
			Soklet.run(fixture.config, () -> {
				try {
					for (String id : List.of("first", "second", "third", "omitted")) {
						HttpResponse<String> response = fixture.call(id, "tools/call", ",\"name\":\"ok\",\"arguments\":{}");
						assertEquals(200, response.statusCode(), response.body());
						assertTrue(response.body().contains("\"text\":\"ok\""), response.body());
						if (id.equals("first")) assertTrue(finishEntered.await(5, TimeUnit.SECONDS));
					}
					HttpResponse<String> catalog = fixture.call("omitted-list", "tools/list", "");
					assertEquals(200, catalog.statusCode(), catalog.body());
					assertTrue(catalog.body().contains("\"name\":\"ok\""), catalog.body());
					assertSame(admittedRequests.get("omitted-list"), policyContexts.get("omitted-list").getRequest());
					assertEquals(List.of("first", "second", "third"), observedRequests,
							"Omitted execution contexts must not emit an unpaired observation start.");
				} finally { release.countDown(); }
				assertTrue(pairedFinishes.await(5, TimeUnit.SECONDS));
			});
		}
	}

	@Test
	void handlerCrashLogsAreSanitizedAndIntentionalErrorsDoNotLog() throws Exception {
		List<LogEvent> logs = new CopyOnWriteArrayList<>();
		CountDownLatch finished = new CountDownLatch(4);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
			@Override public void didFinishMcpRequestHandling(McpRequestContext request, McpRequestOutcome outcome,
					McpJsonRpcError error, Duration duration, List<Throwable> failures) { finished.countDown(); }
		};
		Fixture fixture = new Fixture(builder -> {}, observer);
		try (HttpClient client = fixture.client) {
			Soklet.run(fixture.config, () -> {
				HttpResponse<String> crashedTool = fixture.call("tool", "tools/call", ",\"name\":\"crash\",\"arguments\":{}");
				assertTrue(Set.of(200, 500).contains(crashedTool.statusCode()), crashedTool.statusCode() + " " + crashedTool.body());
				HttpResponse<String> crashedPrompt = fixture.call("prompt", "prompts/get", ",\"name\":\"crash\",\"arguments\":{}");
				assertTrue(Set.of(200, 500).contains(crashedPrompt.statusCode()), crashedPrompt.statusCode() + " " + crashedPrompt.body());
				HttpResponse<String> crashedResource = fixture.call("resource", "resources/read", ",\"uri\":\"test:///crash\"");
				assertTrue(Set.of(200, 500).contains(crashedResource.statusCode()), crashedResource.statusCode() + " " + crashedResource.body());
				HttpResponse<String> expected = fixture.call("expected", "tools/call", ",\"name\":\"expected\",\"arguments\":{}");
				assertEquals(400, expected.statusCode(), expected.body());
				assertTrue(finished.await(5, TimeUnit.SECONDS));
				List<LogEvent> failures = logs.stream().filter(event -> event.getLogEventType() == LogEventType.MCP_REQUEST_PROCESSING_FAILED).toList();
				assertEquals(3, failures.size());
				for (LogEvent failure : failures) {
					assertFalse(failure.toString().contains("secret-canary"));
					assertTrue(failure.getThrowable().isEmpty()); assertTrue(failure.getRequest().isEmpty());
					assertTrue(failure.getResourceMethod().isEmpty()); assertTrue(failure.getMarshaledResponse().isEmpty());
				}
			});
		}
	}

	private static void awaitIgnoringInterrupts(CountDownLatch latch) {
		while (latch.getCount() != 0)
			try { latch.await(20, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
	}

	private static final class Fixture {
		final HttpClient client = HttpClient.newBuilder().connectTimeout(WAIT).build();
		final AtomicInteger accepted = new AtomicInteger();
		final McpServer server;
		final SokletConfig config;
		Fixture(Consumer<McpServer.Builder> configure, LifecycleObserver observer) {
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("observation-tests", "1").build(), VERSIONS)
					.toolRegistrations(List.of(
							McpToolRegistration.withName("ok", VERSIONS).jsonObjectArguments().handler((request, arguments, features) -> McpCompleteResult.fromToolText("ok")).build(),
							McpToolRegistration.withName("crash", VERSIONS).jsonObjectArguments().handler((request, arguments, features) -> { throw new IllegalStateException("secret-canary"); }).build(),
							McpToolRegistration.withName("expected", VERSIONS).jsonObjectArguments().handler((request, arguments, features) -> { throw new McpJsonRpcException(McpJsonRpcError.fromApplication(-31903, "expected")); }).build()))
					.promptRegistrations(List.of(McpPromptRegistration.withName("crash", VERSIONS).handler((request, arguments, features) -> { throw new IllegalStateException("secret-canary"); }).build()))
					.resourceRegistrations(List.of(McpResourceRegistration.withUriAndName(URI.create("test:///crash"), "crash", VERSIONS).handler((request, resource, features) -> { throw new IllegalStateException("secret-canary"); }).build())).build();
			McpServer.Builder builder = McpServer.withPort(0).allowedHosts(Set.of("127.0.0.1"))
					.corsAuthorizer(CorsAuthorizer.rejectAllInstance()).admissionController(McpAdmissionController.acceptAllInstance())
					.toolRateLimiter(context -> McpRateLimitDecision.allowed()).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)));
			configure.accept(builder); server = builder.build();
			config = SokletConfig.withMcpServer(server).lifecycleObserver(observer)
					.metricsCollector(new MetricsCollector() {
						@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
							if (event instanceof McpMetricsEvent.RequestAccepted) accepted.incrementAndGet();
						}
					})
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(WAIT).startupCancelationTimeout(Duration.ofSeconds(2))
							.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
		}
		HttpRequest request(String id, String method, String params) {
			String json = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
					+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{},"
					+ "\"traceparent\":\"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\"}" + params + "}}";
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getDiagnostics().getBoundAddress().orElseThrow().getPort() + "/mcp")).timeout(WAIT).header("Content-Type", "application/json")
					.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", method);
			if (method.equals("tools/call") || method.equals("prompts/get")) builder.header("Mcp-Name", id.equals("expected") ? "expected" : id.equals("tool") || method.equals("prompts/get") ? "crash" : "ok");
			if (method.equals("resources/read")) builder.header("Mcp-Name", "test:///crash");
			return builder.POST(HttpRequest.BodyPublishers.ofString(json)).build();
		}
		HttpResponse<String> call(String id, String method, String params) throws Exception {
			return client.send(request(id, method, params), HttpResponse.BodyHandlers.ofString());
		}
	}
}
