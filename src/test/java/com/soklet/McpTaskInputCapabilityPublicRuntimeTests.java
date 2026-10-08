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

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task input delivery uses the current authorized polling request's capabilities. */
@Timeout(30)
class McpTaskInputCapabilityPublicRuntimeTests {
	private static final String TASKS = "io.modelcontextprotocol/tasks";
	private static final String CANARY = "private-task-input-canary";
	private static final String NONE = "";
	private static final String EMPTY = ",\"elicitation\":{}";
	private static final String FORM = ",\"elicitation\":{\"form\":{}}";
	private static final String URL = ",\"elicitation\":{\"url\":{}}";
	private static final String BOTH = ",\"elicitation\":{\"form\":{},\"url\":{}}";
	private static final McpInputRequestDeclaration FORM_DECLARATION =
			McpInputRequestDeclaration.fromElicitationForm(McpInputRequirement.CONDITIONAL);
	private static final McpInputRequestDeclaration URL_DECLARATION =
			McpInputRequestDeclaration.fromElicitationUrl(McpInputRequirement.CONDITIONAL);

	@TestFactory
	Stream<DynamicTest> everyOutstandingModeRequiresCurrentRequestSupport() {
		return Stream.of(
				new Case("url-absent", false, true, NONE, "\"url\":{}"),
				new Case("url-empty-form-default", false, true, EMPTY, "\"url\":{}"),
				new Case("url-form-only", false, true, FORM, "\"url\":{}"),
				new Case("url-supported", false, true, URL, ""),
				new Case("url-both-supported", false, true, BOTH, ""),
				new Case("form-absent", true, false, NONE, "\"form\":{}"),
				new Case("form-url-only", true, false, URL, "\"form\":{}"),
				new Case("form-empty-default", true, false, EMPTY, ""),
				new Case("form-supported", true, false, FORM, ""),
				new Case("both-absent", true, true, NONE, "\"form\":{},\"url\":{}"),
				new Case("both-form-only", true, true, FORM, "\"url\":{}"),
				new Case("both-url-only", true, true, URL, "\"form\":{}"),
				new Case("both-supported", true, true, BOTH, ""))
				.map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> verifyModes(testCase)));
	}

	private void verifyModes(Case testCase) throws Exception {
		Fixture fixture = new Fixture();
		try (Soklet soklet = Soklet.fromConfig(fixture.config)) {
			soklet.start();
			int port = fixture.server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			assertEquals(200, post(port, "tools/call", "create", "\"name\":\"create\",\"arguments\":{}",
					NONE, "owner").status());
			String taskId = fixture.taskId.get();
			McpTask waiting = fixture.requestInput(testCase.form(), testCase.url());
			AtomicInteger readTransitions = new AtomicInteger();
			try (McpSubscriptionEventRegistration registration = fixture.manager.getTaskEventPublisher().orElseThrow()
					.subscribe(changedTaskId -> readTransitions.incrementAndGet())) {
				Response initial = get(port, taskId, testCase.capabilities(), "owner");
				if (testCase.missingModes().isEmpty()) {
					assertDetailedInput(initial, testCase.form(), testCase.url());
				} else {
					assertMissingModes(initial, testCase.missingModes(), taskId);
					assertMissingModes(get(port, taskId, testCase.capabilities(), "owner"),
							testCase.missingModes(), taskId);
				}
				assertSame(waiting, fixture.manager.findTask(taskId).orElseThrow());
				// No remembered creation or prior-poll capability substitutes for this request.
				assertDetailedInput(get(port, taskId, BOTH, "owner"), testCase.form(), testCase.url());
				assertSame(waiting, fixture.manager.findTask(taskId).orElseThrow());
				assertEquals(0, readTransitions.get());
			}
			fixture.manager.markTaskWorking(taskId, null);
			assertEquals(200, get(port, taskId, NONE, "owner").status());
			fixture.manager.completeTask(taskId, McpCompleteResult.fromToolText("done"), null);
			Response completed = get(port, taskId, NONE, "owner");
			assertEquals(200, completed.status(), completed.body());
			assertTrue(completed.body().contains("\"status\":\"completed\""), completed.body());
			assertEquals(1, fixture.handlerCalls.get());
		}
	}

	@Test
	void unknownAndUnauthorizedTasksDoNotDiscloseInputCapabilityRequirements() throws Exception {
		Fixture fixture = new Fixture();
		try (Soklet soklet = Soklet.fromConfig(fixture.config)) {
			soklet.start();
			int port = fixture.server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			assertEquals(200, post(port, "tools/call", "create", "\"name\":\"create\",\"arguments\":{}",
					NONE, "owner").status());
			String taskId = fixture.taskId.get();
			McpTask waiting = fixture.requestInput(true, true);
			Response unauthorized = get(port, taskId, NONE, "other");
			Response unknown = get(port, "unknown-private-task-id", NONE, "other");
			assertEquals(400, unauthorized.status(), unauthorized.body());
			assertEquals(unauthorized, unknown);
			assertTrue(unauthorized.body().contains("\"code\":-32602"), unauthorized.body());
			assertFalse(unauthorized.body().contains("requiredCapabilities"), unauthorized.body());
			assertFalse(unauthorized.body().contains(CANARY), unauthorized.body());
			assertFalse(unauthorized.body().contains(taskId), unauthorized.body());
			assertSame(waiting, fixture.manager.findTask(taskId).orElseThrow());
			assertDetailedInput(get(port, taskId, BOTH, "owner"), true, true);
		}
	}

	@Test
	void simulatorRejectsUnsupportedInputAndRecoversOnACapablePoll() throws Exception {
		Fixture fixture = new Fixture();
		SokletSimulator.run(fixture.config, simulator -> {
			assertEquals(200, simulate(simulator, "tools/call", "create",
					"\"name\":\"create\",\"arguments\":{}", NONE).status());
			String taskId = fixture.taskId.get();
			McpTask waiting = fixture.requestInput(true, true);
			assertMissingModes(simulate(simulator, "tasks/get", taskId,
					"\"taskId\":\"" + taskId + "\"", NONE), "\"form\":{},\"url\":{}", taskId);
			assertSame(waiting, fixture.manager.findTask(taskId).orElseThrow());
			assertDetailedInput(simulate(simulator, "tasks/get", taskId,
					"\"taskId\":\"" + taskId + "\"", BOTH), true, true);
			assertMissingModes(simulate(simulator, "tasks/get", taskId,
					"\"taskId\":\"" + taskId + "\"", NONE), "\"form\":{},\"url\":{}", taskId);
			assertSame(waiting, fixture.manager.findTask(taskId).orElseThrow());
		});
	}

	private static void assertMissingModes(Response response, String missingModes, String taskId) {
		assertEquals(400, response.status(), response.body());
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"task-input-capability\",\"error\":{"
				+ "\"code\":-32021,\"message\":\"Missing required client capability\","
				+ "\"data\":{\"requiredCapabilities\":{\"elicitation\":{" + missingModes + "}}}}}", response.body());
		assertFalse(response.body().contains("inputRequests"), response.body());
		assertFalse(response.body().contains(CANARY), response.body());
		assertFalse(response.body().contains(taskId), response.body());
	}

	private static void assertDetailedInput(Response response, boolean form, boolean url) {
		assertEquals(200, response.status(), response.body());
		assertTrue(response.body().contains("\"status\":\"input_required\""), response.body());
		assertEquals(form, response.body().contains("\"form-" + CANARY + "\":"), response.body());
		assertEquals(url, response.body().contains("\"url-" + CANARY + "\":"), response.body());
		assertEquals(url, response.body().contains("\"url-again-" + CANARY + "\":"), response.body());
	}

	private static Response get(int port, String taskId, String capabilities, String owner) throws Exception {
		return post(port, "tasks/get", taskId, "\"taskId\":\"" + taskId + "\"", capabilities, owner);
	}

	private static String body(String method, String fields, String capabilities) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"task-input-capability\",\"method\":\"" + method
				+ "\",\"params\":{" + fields + ",\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{\"extensions\":{\"" + TASKS
				+ "\":{}}" + capabilities + "}}}}";
	}

	private static Response post(int port, String method, String name, String fields,
			String capabilities, String owner) throws Exception {
		try (RawClient client = new RawClient(port, "POST", "/mcp", body(method, fields, capabilities), List.of(
				new HeaderValue("MCP-Protocol-Version", "2026-07-28"),
				new HeaderValue("Mcp-Method", method), new HeaderValue("Mcp-Name", name),
				new HeaderValue("Authorization", "Bearer " + owner)))) {
			Head head = client.readHead();
			return new Response(head.status(), client.readBody(head));
		}
	}

	private static Response simulate(Simulator simulator, String method, String name,
			String fields, String capabilities) throws InterruptedException {
		Request request = Request.withPath(HttpMethod.POST, "/mcp")
				.headers(Map.of("Host", List.of("127.0.0.1:0"), "Content-Type", List.of("application/json"),
						"Accept", List.of("application/json, text/event-stream"), "MCP-Protocol-Version", List.of("2026-07-28"),
						"Mcp-Method", List.of(method), "Mcp-Name", List.of(name), "Authorization", List.of("Bearer owner")))
				.body(body(method, fields, capabilities).getBytes(StandardCharsets.UTF_8)).build();
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(Duration.ofSeconds(5)).orElseThrow();
			assertEquals(McpSimulationBodyType.JSON, response.getBodyType());
			McpSimulationCompletion completion = simulation.awaitCompletion(Duration.ofSeconds(5)).orElseThrow();
			assertTrue(completion.getThrowables().isEmpty());
			return new Response(response.getStatusCode(), new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8));
		}
	}

	private record Case(String name, boolean form, boolean url, String capabilities, String missingModes) {}
	private record Response(int status, String body) {}

	private static final class Fixture {
		private final McpInMemoryTaskManager manager = McpTaskManager.fromInMemoryDefaults();
		private final AtomicReference<String> taskId = new AtomicReference<>();
		private final AtomicInteger handlerCalls = new AtomicInteger();
		private final McpServer server;
		private final SokletConfig config;

		private Fixture() {
			Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2026_07_28);
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("create", versions)
					.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
						handlerCalls.incrementAndGet();
						McpTask task = manager.createTask(invocationFeatures.getTaskCreationContext().orElseThrow());
						taskId.set(task.getTaskId());
						return McpTaskCreatedResult.fromTaskId(task.getTaskId());
					}).inputRequestDeclarations(List.of(FORM_DECLARATION, URL_DECLARATION)).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
					McpImplementation.withNameAndVersion("task-input-capability-test", "1").build(), versions)
					.taskProtocolVersions(versions).serverInfoIncluded(false).toolRegistrations(List.of(tool)).build();
			server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).taskManager(manager)
					.admissionController(admissionContext -> McpAdmissionDecision.accepted(McpAdmissionIdentity
							.withRateLimitPartitionKey("task-rate")
							.authorizationPartitionKey(admissionContext.getRequest().getHeaders()
									.getOrDefault("Authorization", List.of()).equals(List.of("Bearer owner")) ? "owner" : "other")
							.build()))
					.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
			config = SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build();
		}

		private McpTask requestInput(boolean form, boolean url) throws McpTaskNotFoundException {
			Map<String, McpInputRequest> requests = new LinkedHashMap<>();
			if (form)
				requests.put("form-" + CANARY, McpInputRequest.fromDeclaration(FORM_DECLARATION,
						McpJsonObject.builder().put("mode", "form").put("message", CANARY)
								.put("requestedSchema", McpJsonObject.builder().put("type", "object")
										.put("properties", McpJsonObject.emptyInstance()).build()).build()));
			if (url) {
				McpInputRequest request = McpInputRequest.fromDeclaration(URL_DECLARATION,
						McpJsonObject.builder().put("mode", "url").put("message", CANARY)
								.put("url", "https://example.com/" + CANARY).build());
				requests.put("url-" + CANARY, request);
				requests.put("url-again-" + CANARY, request);
			}
			return manager.requestTaskInput(taskId.get(), requests, null);
		}
	}
}
