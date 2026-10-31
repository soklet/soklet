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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Authorization must retain its admitted context when bounded observation is omitted. */
@Timeout(120)
class McpOmittedObservationAuthorizationTests {

	@Test
	void omittedModernResourceListenRetainsItsAdmittedAuthorizationContext() throws InterruptedException {
		modernListen(false);
	}

	@Test
	void omittedModernTaskListenRetainsItsTaskAndSubscriptionAuthorizationContexts() throws InterruptedException {
		modernListen(true);
	}

	@Test
	void omittedJune2025UriGrantRetainsItsAdmittedAuthorizationContext() throws InterruptedException {
		legacyUriGrant(McpProtocolVersion.V2025_06_18);
	}

	@Test
	void omittedNovember2025UriGrantRetainsItsAdmittedAuthorizationContext() throws InterruptedException {
		legacyUriGrant(McpProtocolVersion.V2025_11_25);
	}

	private void modernListen(boolean tasks) throws InterruptedException {
		Harness harness = new Harness(McpProtocolVersion.V2026_07_28, tasks);
		SokletSimulator.run(harness.config(), simulator -> {
			try {
				if (tasks) {
					String seed = json(simulator, harness.request("seed", "tools/call", "seed", "\"name\":\"seed\",\"arguments\":{}"), 200);
					assertTrue(seed.contains("\"resultType\":\"task\""), seed);
					harness.awaitFinished("seed");
				}
				// A completed, denied listen retains the sole lifetime observation slot.
				// Its blocked finish also prevents the four transient pairs from draining.
				json(simulator, harness.request("hold", "subscriptions/listen", null,
						"\"notifications\":{\"resourcesListChanged\":true}"), 403);
				assertTrue(harness.finishEntered.await(5, TimeUnit.SECONDS));
				harness.fillTransientObservationBudget(simulator);
				String notifications = tasks ? "{\"taskIds\":[\"" + harness.taskId.get() + "\"]}"
						: "{\"resourceSubscriptions\":[\"test:///omitted\"]}";
				try (McpSimulation listen = simulator.startMcpRequest(harness.request("omitted", "subscriptions/listen", null,
						"\"notifications\":" + notifications))) {
					McpSimulationResponse response = listen.awaitResponse(WAIT).orElseThrow();
					assertEquals(200, response.getStatusCode(), () -> response.getBody().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("no body"));
					assertEquals(McpSimulationBodyType.SSE, response.getBodyType());
					McpJsonObject ack = nextMessage(listen);
					assertEquals("notifications/subscriptions/acknowledged", string(ack, "method"));
					McpJsonObject accepted = (McpJsonObject) ((McpJsonObject) ack.find("params").orElseThrow()).find("notifications").orElseThrow();
					String acceptedKey = tasks ? "taskIds" : "resourceSubscriptions";
					assertTrue(accepted.find(acceptedKey).isPresent(), () -> "Missing accepted " + acceptedKey + ": " + ack + "; task lookups=" + harness.taskAuthorizations.size());
					McpJsonArray selections = (McpJsonArray) accepted.find(acceptedKey).orElseThrow();
					assertEquals(List.of(McpJsonString.fromValue(tasks ? harness.taskId.get() : RESOURCE.toString())), selections.getElements());
					McpSubscriptionAuthorizationContext authorization = harness.authorization.get();
					assertNotNull(authorization, "The omitted listen must still run subscription authorization.");
					harness.assertOriginalContext(authorization.getInitialRequestContext(), "omitted", "subscriptions/listen");
					assertSame(harness.applicationContext, authorization.getApplicationContext().orElseThrow());
					if (tasks) {
						assertEquals(Set.of(harness.taskId.get()), authorization.getTaskIds());
						assertFalse(harness.taskAuthorizations.isEmpty(), "Task selection must independently authorize a durable lookup.");
						for (McpRequestContext context : harness.taskAuthorizations) {
							harness.assertIdentity(context);
							assertTrue(context.getRequest().getHeaders().isEmpty(), "Task projections retain identity without credential headers.");
							assertTrue(context.getRequest().getBody().isEmpty());
							assertTrue(context.getRequestId().isEmpty());
						}
					} else assertEquals(Set.of(RESOURCE), authorization.getResourceSubscriptionUris());
					listen.close();
					listen.awaitCompletion(WAIT).orElseThrow();
				}
			} finally { harness.finishRelease.countDown(); }
			harness.assertCompleteObservationPairs("subscriptions/listen", 1);
		});
	}

	private void legacyUriGrant(McpProtocolVersion version) throws InterruptedException {
		Harness harness = new Harness(version, false);
		SokletSimulator.run(harness.config(), simulator -> {
			try {
				try (McpSimulation initialization = simulator.startMcpRequest(harness.initialize())) {
					McpSimulationResponse response = initialization.awaitResponse(WAIT).orElseThrow();
					assertEquals(200, response.getStatusCode());
					harness.sessionId = response.getHeaders().entrySet().stream()
							.filter(entry -> entry.getKey().equalsIgnoreCase("Mcp-Session-Id"))
							.findFirst().orElseThrow().getValue().iterator().next();
					initialization.awaitCompletion(WAIT).orElseThrow();
				}
				harness.awaitFinished("initialize");
				json(simulator, harness.request("hold", "tools/call", "ok", "\"name\":\"ok\",\"arguments\":{}"), 200);
				assertTrue(harness.finishEntered.await(5, TimeUnit.SECONDS));
				harness.fillTransientObservationBudget(simulator);
				String subscribed = json(simulator, harness.request("omitted", "resources/subscribe", RESOURCE.toString(),
						"\"uri\":\"" + RESOURCE + "\""), 200);
				assertTrue(subscribed.contains("\"result\":{}"), subscribed);
				McpSubscriptionAuthorizationContext authorization = harness.authorization.get();
				assertNotNull(authorization, "The omitted exact URI grant must still run subscription authorization.");
				harness.assertOriginalContext(authorization.getInitialRequestContext(), "omitted", "resources/subscribe");
				assertEquals(Set.of(RESOURCE), authorization.getResourceSubscriptionUris());
				assertSame(harness.applicationContext, authorization.getApplicationContext().orElseThrow());
				try (McpSimulation get = simulator.startMcpRequest(harness.get())) {
					assertEquals(200, get.awaitResponse(WAIT).orElseThrow().getStatusCode());
					harness.publisher.publishResourceUpdated(RESOURCE);
					McpJsonObject notification = nextMessage(get);
					assertEquals("notifications/resources/updated", string(notification, "method"));
					assertEquals(RESOURCE.toString(), string((McpJsonObject) notification.find("params").orElseThrow(), "uri"));
					get.close(); get.awaitCompletion(WAIT).orElseThrow();
				}
			} finally { harness.finishRelease.countDown(); }
			harness.assertCompleteObservationPairs("resources/subscribe", 0);
		});
	}

	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final URI RESOURCE = URI.create("test:///omitted");

	private static String json(Simulator simulator, Request request, int status) throws InterruptedException {
		try (McpSimulation call = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = call.awaitResponse(WAIT).orElseThrow();
			String body = new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
			assertEquals(status, response.getStatusCode(), body);
			call.awaitCompletion(WAIT).orElseThrow();
			return body;
		}
	}

	private static McpJsonObject nextMessage(McpSimulation simulation) throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (System.nanoTime() < deadline) {
			McpSimulationStreamItem item = simulation.awaitStreamItem(Duration.ofNanos(Math.max(1, deadline - System.nanoTime()))).orElseThrow();
			if (item.getMessage().isPresent()) return (McpJsonObject) item.getMessage().orElseThrow();
		}
		throw new AssertionError("No JSON stream item within five seconds");
	}

	private static String string(McpJsonObject object, String member) {
		return ((McpJsonString) object.find(member).orElseThrow()).getValue();
	}

	private static final class Harness {
		final McpProtocolVersion version;
		final boolean tasks;
		final Object principal = new Object(), applicationContext = new Object();
		final McpAdmissionIdentity identity = McpAdmissionIdentity.withRateLimitPartitionKey("alice-rate")
				.authorizationPartitionKey("alice-authorization").principal(principal).applicationContext(applicationContext).build();
		final CountDownLatch finishEntered = new CountDownLatch(1), finishRelease = new CountDownLatch(1);
		final List<String> starts = new CopyOnWriteArrayList<>(), finishes = new CopyOnWriteArrayList<>();
		final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		final Map<String, Request> admittedRequests = new ConcurrentHashMap<>();
		final AtomicReference<McpSubscriptionAuthorizationContext> authorization = new AtomicReference<>();
		final List<McpRequestContext> taskAuthorizations = new CopyOnWriteArrayList<>();
		final AtomicReference<String> taskId = new AtomicReference<>();
		final McpInMemoryTaskManager taskDelegate = McpTaskManager.fromInMemoryDefaults();
		final McpSubscriptionEventPublisher publisher = McpSubscriptionEventPublisher.fromInMemoryDefaults();
		String sessionId;

		Harness(McpProtocolVersion version, boolean tasks) { this.version = version; this.tasks = tasks; }

		SimulatorConfig config() {
			McpToolRegistration<McpJsonObject> ok = McpToolRegistration.withName("ok", Set.of(version)).jsonObjectArguments()
					.handler((context, arguments, features) -> McpCompleteResult.fromToolText("ok")).build();
			McpToolRegistration<McpJsonObject> seed = McpToolRegistration.withName("seed", Set.of(version)).jsonObjectArguments()
					.handler((context, arguments, features) -> {
						String id = taskDelegate.createTask(features.getTaskCreationContext().orElseThrow()).getTaskId();
						taskId.set(id); return McpTaskCreatedResult.fromTaskId(id);
					}).build();
			McpEndpoint.Builder endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("omitted-auth", "1").build(), Set.of(version))
					.subscriptionProtocolVersions(Set.of(version))
					.subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(publisher,
							Set.of(McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED, McpSubscriptionNotificationType.RESOURCE_UPDATED)).build())
					.resourceRegistrations(List.of(McpResourceRegistration.withUriAndName(RESOURCE, "omitted", Set.of(version))
							.handler((context, resource, features) -> { throw new AssertionError("Subscribe must not read a resource."); }).build()))
					.toolRegistrations(tasks ? List.of(ok, seed) : List.of(ok));
			if (tasks) endpoint.taskProtocolVersions(Set.of(version));
			if (legacy()) endpoint.sessionProtocolVersions(Set.of(version));
			McpEndpoint builtEndpoint = endpoint.build();
			return SimulatorConfig.builder().configureMcpServer(builder -> {
				builder.host("127.0.0.1").allowedHosts(Set.of("127.0.0.1")).concurrentConnectionLimit(1)
						.requestHandlerConcurrency(1).requestHandlerQueueCapacity(1).protocolExecutorConcurrency(2)
						.requestTimeout(WAIT).maximumSubscriptionDuration(Duration.ofSeconds(60))
						.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(builtEndpoint)))
						.corsAuthorizer(CorsAuthorizer.rejectAllInstance()).toolRateLimiter(context -> McpRateLimitDecision.allowed())
						.admissionController(context -> {
							admittedRequests.put(context.getRequestId().orElseThrow().asString().orElseThrow(), context.getRequest());
							return McpAdmissionDecision.accepted(identity);
						}).subscriptionAuthorizer((context, features) -> {
							if (context.getInitialRequestContext().getRequestId().orElseThrow().asString().orElseThrow().equals("hold"))
								return McpSubscriptionAuthorization.deniedInstance();
							authorization.set(context);
							return McpSubscriptionAuthorization.Allowed.withValidUntil(Instant.now().plusSeconds(60)).applicationContext(applicationContext).build();
						});
				if (tasks) builder.taskManager(new McpTaskManager() {
					@Override public Optional<McpTaskEventPublisher> getTaskEventPublisher() { return taskDelegate.getTaskEventPublisher(); }
					@Override public Optional<McpTask> findTask(McpTaskRequestContext context) throws Exception {
						if (context.getRequestContext().getJsonRpcMethod().equals("subscriptions/listen"))
							taskAuthorizations.add(context.getRequestContext());
						return taskDelegate.findTask(context);
					}
					@Override public void updateTask(McpTaskUpdateContext context) throws Exception { taskDelegate.updateTask(context); }
					@Override public void requestTaskCancelation(McpTaskRequestContext context) throws Exception { taskDelegate.requestTaskCancelation(context); }
				});
				if (legacy()) builder.sessionConfig(McpSessionConfig.withOwnerKeyResolver(admitted -> "alice")
						.transportAdmissionController((context, features) -> McpSessionTransportAdmissionDecision.accepted(identity,
								Instant.now().plusSeconds(60), context.getRequest().getHttpMethod() == HttpMethod.DELETE ? Set.of()
								: Set.of(McpSubscriptionNotificationType.RESOURCE_UPDATED))).build());
			}).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(WAIT).startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(2)).build())
					.metricsCollector(new MetricsCollector() { @Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) { events.add(event); } })
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) {}
						@Override public void didStartMcpRequestHandling(McpRequestContext context) { starts.add(id(context)); }
						@Override public void didFinishMcpRequestHandling(McpRequestContext context, McpRequestOutcome outcome,
								McpJsonRpcError error, Duration duration, List<Throwable> failures) {
							if (id(context).equals("hold")) {
								finishEntered.countDown();
								boolean interrupted = false;
								try {
								long until = System.nanoTime() + Duration.ofSeconds(100).toNanos();
								while (finishRelease.getCount() != 0) {
									long remaining = until - System.nanoTime();
									if (remaining <= 0) throw new AssertionError("Foreground did not release observation pressure.");
									try { finishRelease.await(remaining, TimeUnit.NANOSECONDS); } catch (InterruptedException ignored) { interrupted = true; }
								}
							}
								finally { if (interrupted) Thread.currentThread().interrupt(); }
							}
							finishes.add(id(context));
						}
					}).build();
		}

		void fillTransientObservationBudget(Simulator simulator) throws InterruptedException {
			// concurrency1 + handlerQueue1 + protocolHeadroom2 = four pairs.
			// Legacy hold already owns a transient pair; modern hold owns its separate lifetime slot.
			int fillers = legacy() ? 3 : 4;
			for (int index = 0; index < fillers; index++) {
				String response = json(simulator, request("fill-" + index, "tools/call", "ok", "\"name\":\"ok\",\"arguments\":{}"), 200);
				assertFalse(response.contains("\"error\""), response);
				assertTrue(starts.contains("fill-" + index));
			}
		}

		void assertOriginalContext(McpRequestContext context, String id, String method) {
			assertSame(admittedRequests.get(id), context.getRequest());
			assertEquals("Bearer alice-secret", context.getRequest().getHeader("Authorization").orElseThrow());
			assertEquals(id, id(context)); assertEquals(method, context.getJsonRpcMethod());
			assertIdentity(context);
		}

		void assertIdentity(McpRequestContext context) {
			assertEquals(version, context.getProtocolVersion());
			assertEquals(identity.getRateLimitPartitionKey(), context.getAdmissionIdentity().getRateLimitPartitionKey());
			assertSame(principal, context.getAdmissionIdentity().getPrincipal().orElseThrow());
			assertEquals("alice-authorization", context.getAdmissionIdentity().getAuthorizationPartitionKey().orElseThrow());
			assertSame(applicationContext, context.getAdmissionIdentity().getApplicationContext().orElseThrow());
			assertTrue(context.getStartedAt().isPresent());
		}

		void awaitFinished(String id) throws InterruptedException {
			long deadline = System.nanoTime() + WAIT.toNanos();
			while (!finishes.contains(id) && System.nanoTime() < deadline) Thread.sleep(5);
			assertTrue(finishes.contains(id));
		}

		void assertCompleteObservationPairs(String method, long observedForMethod) throws InterruptedException {
			long deadline = System.nanoTime() + WAIT.toNanos();
			while ((finishes.size() != starts.size() || events.stream().filter(McpMetricsEvent.RequestFinished.class::isInstance).count() != starts.size())
					&& System.nanoTime() < deadline) Thread.sleep(5);
			assertEquals(Set.copyOf(starts), Set.copyOf(finishes)); assertEquals(starts.size(), finishes.size());
			assertFalse(starts.contains("omitted")); assertFalse(finishes.contains("omitted"));
			assertEquals(observedForMethod, events.stream().filter(event -> event instanceof McpMetricsEvent.RequestStarted started && started.getJsonRpcMethod().equals(method)).count());
			assertEquals(observedForMethod, events.stream().filter(event -> event instanceof McpMetricsEvent.RequestFinished finished && finished.getJsonRpcMethod().equals(method)).count());
			assertEquals(0, events.stream().filter(event -> event instanceof McpMetricsEvent.RequestStreamOpened opened && opened.getJsonRpcMethod().equals(method)
					|| event instanceof McpMetricsEvent.RequestStreamClosed closed && closed.getJsonRpcMethod().equals(method)).count());
		}

		boolean legacy() { return version != McpProtocolVersion.V2026_07_28; }
		static String id(McpRequestContext context) { return context.getRequestId().orElseThrow().asString().orElseThrow(); }
		Request initialize() {
			return wire(HttpMethod.POST, "initialize", "initialize", null, "{\"protocolVersion\":\"" + version.getWireValue()
					+ "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"omission\",\"version\":\"1\"}}");
		}
		Request request(String id, String method, String name, String fields) {
			String meta = legacy() ? "" : "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + version.getWireValue()
					+ "\",\"io.modelcontextprotocol/clientCapabilities\":" + (tasks ? "{\"extensions\":{\"io.modelcontextprotocol/tasks\":{}}}" : "{}") + "},";
			return wire(HttpMethod.POST, id, method, name, "{" + meta + fields + "}");
		}
		Request get() { return wire(HttpMethod.GET, null, null, null, null); }
		Request wire(HttpMethod httpMethod, String id, String method, String name, String params) {
			Map<String, List<String>> headers = new LinkedHashMap<>();
			headers.put("Host", List.of("127.0.0.1:0")); headers.put("Accept", List.of("application/json, text/event-stream"));
			headers.put("Content-Type", List.of("application/json")); headers.put("Authorization", List.of("Bearer alice-secret"));
			headers.put("MCP-Protocol-Version", List.of(version.getWireValue()));
			if (sessionId != null) headers.put("Mcp-Session-Id", List.of(sessionId));
			if (!legacy() && method != null) headers.put("Mcp-Method", List.of(method));
			if (!legacy() && name != null) headers.put("Mcp-Name", List.of(name));
			String body = method == null ? "" : "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method + "\",\"params\":" + params + "}";
			return Request.withPath(httpMethod, "/mcp").headers(headers).body(body.getBytes(StandardCharsets.UTF_8)).build();
		}
	}
}
