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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real decimal form input through protected retries and durable tasks. */
@Timeout(20)
class McpDecimalElicitationPublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(McpProtocolVersion.V2026_07_28);
	private static final String CAPABILITIES = "{\"elicitation\":{\"form\":{}},\"extensions\":{\"io.modelcontextprotocol/tasks\":{}}}";
	private static final String DECIMAL_RESPONSE = "{\"action\":\"accept\",\"content\":{\"amount\":3.50}}";
	private static final String INVALID_RESPONSE = "{\"action\":\"accept\",\"content\":{\"amount\":[3.50]}}";

	@Test
	void protectedRetryAcceptsDecimalsWithoutBypassingCapabilitiesOrTheResponseUnion() throws Exception {
		try (Fixture fixture = new Fixture()) {
			String initial = fixture.post("tools/call", "amount", "initial", "\"name\":\"amount\",\"arguments\":{}", CAPABILITIES, 200);
			assertTrue(initial.contains("\"resultType\":\"input_required\""), initial);
			assertTrue(initial.contains("\"default\":3.50"), initial);
			String stateMarker = "\"requestState\":\"";
			int stateStart = initial.indexOf(stateMarker);
			assertTrue(stateStart >= 0, initial);
			stateStart += stateMarker.length();
			String state = initial.substring(stateStart, initial.indexOf('"', stateStart));
			String retryFields = "\"name\":\"amount\",\"arguments\":{},\"requestState\":\"" + state + "\",\"inputResponses\":{\"amount\":";
			String missingCapability = fixture.post("tools/call", "amount", "missing-capability", retryFields + DECIMAL_RESPONSE + "}", "{}", 400);
			String invalid = fixture.post("tools/call", "amount", "invalid-union", retryFields + INVALID_RESPONSE + "}", CAPABILITIES, 400);
			assertTrue(invalid.contains("\"code\":-32602"), invalid);
			assertEquals(1, fixture.handlerCalls.get());
			String completed = fixture.post("tools/call", "amount", "decimal-retry", retryFields + DECIMAL_RESPONSE + "}", CAPABILITIES, 200);
			assertTrue(completed.contains("\"resultType\":\"complete\""), completed);
			assertTrue(completed.contains("\"text\":\"amount=3.50\""), completed);
			assertEquals(new BigDecimal("3.50"), fixture.receivedAmount.get());
			assertEquals(2, fixture.handlerCalls.get());
			assertTrue(missingCapability.contains("\"code\":-32021"), missingCapability);
		}
	}

	@Test
	void durableTaskConsumesDecimalInputOnceAndIgnoresAnInvalidUnionValue() throws Exception {
		try (Fixture fixture = new Fixture()) {
			String created = fixture.post("tools/call", "task.amount", "create", "\"name\":\"task.amount\",\"arguments\":{}", CAPABILITIES, 200);
			assertTrue(created.contains("\"resultType\":\"task\""), created);
			String taskId = fixture.createdTaskId.get();
			assertNotNull(taskId);
			fixture.taskManager.requestTaskInput(taskId, Map.of("amount", fixture.formRequest), "Waiting for an amount");
			String taskFields = "\"taskId\":\"" + taskId + "\"";
			String waiting = fixture.post("tasks/get", taskId, "waiting", taskFields, CAPABILITIES, 200);
			assertTrue(waiting.contains("\"status\":\"input_required\""), waiting);
			fixture.post("tasks/update", taskId, "invalid", taskFields + ",\"inputResponses\":{\"amount\":" + INVALID_RESPONSE + "}", CAPABILITIES, 200);
			assertEquals(McpTaskStatus.INPUT_REQUIRED, fixture.taskManager.findTask(taskId).orElseThrow().getTaskStatus());
			assertTrue(fixture.taskManager.takeTaskInputResponses(taskId).asMap().isEmpty());
			fixture.post("tasks/update", taskId, "decimal", taskFields + ",\"inputResponses\":{\"amount\":" + DECIMAL_RESPONSE + "}", CAPABILITIES, 200);
			assertEquals(McpTaskStatus.WORKING, fixture.taskManager.findTask(taskId).orElseThrow().getTaskStatus());
			fixture.post("tasks/update", taskId, "duplicate", taskFields
					+ ",\"inputResponses\":{\"amount\":{\"action\":\"accept\",\"content\":{\"amount\":9.75}}}", CAPABILITIES, 200);
			McpInputResponses responses = fixture.taskManager.takeTaskInputResponses(taskId);
			assertEquals(Set.of("amount"), responses.asMap().keySet());
			assertEquals(new BigDecimal("3.50"), amount(responses.find("amount").orElseThrow()));
			assertTrue(fixture.taskManager.takeTaskInputResponses(taskId).asMap().isEmpty());
			fixture.taskManager.completeTask(taskId, McpCompleteResult.fromToolText("decimal task completed"), null);
			String completed = fixture.post("tasks/get", taskId, "completed", taskFields, CAPABILITIES, 200);
			assertTrue(completed.contains("\"status\":\"completed\""), completed);
			assertTrue(completed.contains("decimal task completed"), completed);
		}
	}

	private static BigDecimal amount(McpJsonValue response) {
		McpJsonObject object = assertInstanceOf(McpJsonObject.class, response);
		McpJsonObject content = assertInstanceOf(McpJsonObject.class, object.find("content").orElseThrow());
		return assertInstanceOf(McpJsonNumber.class, content.find("amount").orElseThrow()).getValue();
	}

	private static final class Fixture implements AutoCloseable {
		private final McpInMemoryTaskManager taskManager = McpTaskManager.fromInMemoryDefaults();
		private final AtomicInteger handlerCalls = new AtomicInteger();
		private final AtomicReference<BigDecimal> receivedAmount = new AtomicReference<>();
		private final AtomicReference<String> createdTaskId = new AtomicReference<>();
		private final McpInputRequest formRequest;
		private final McpServer server;
		private final Soklet soklet;

		private Fixture() {
			McpInputRequestDeclaration form = McpInputRequestDeclaration.fromElicitationForm(McpInputRequirement.REQUIRED);
			formRequest = McpInputRequest.fromDeclaration(form, McpJsonObject.builder()
					.put("mode", "form").put("message", "Choose an amount")
					.put("requestedSchema", McpJsonObject.builder().put("type", "object")
							.put("properties", McpJsonObject.builder().put("amount", McpJsonObject.builder()
									.put("type", "number").put("default", new BigDecimal("3.50"))
									.put("minimum", new BigDecimal("0.25")).put("maximum", new BigDecimal("10.5")).build()).build()).build()).build());
			McpToolRegistration<McpJsonObject> unary = McpToolRegistration.withName("amount", VERSIONS)
					.jsonObjectArguments()
					.handler((requestContext, arguments, invocationFeatures) -> {
						handlerCalls.incrementAndGet();
						if (requestContext.getFrameworkRequestState().isEmpty())
							return McpInputRequiredResult.withInputRequest("amount", formRequest)
									.frameworkRequestState(McpJsonObject.builder().put("phase", "waiting").build()).build();
						assertEquals(McpJsonObject.builder().put("phase", "waiting").build(), requestContext.getFrameworkRequestState().orElseThrow());
						McpJsonValue response = requestContext.getInputResponses().find("amount").orElseThrow();
						assertTrue(formRequest.matchesInputResponse(response));
						BigDecimal value = amount(response);
						receivedAmount.set(value);
						return McpCompleteResult.fromToolText("amount=" + value.toPlainString());
					}).inputRequestDeclarations(List.of(form))
					.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED).build();
			McpToolRegistration<McpJsonObject> task = McpToolRegistration.withName("task.amount", VERSIONS)
					.jsonObjectArguments()
					.handler((requestContext, arguments, invocationFeatures) -> {
						McpTask created = taskManager.createTask(invocationFeatures.getTaskCreationContext().orElseThrow());
						createdTaskId.set(created.getTaskId());
						return McpTaskCreatedResult.<McpJsonObject>fromTaskId(created.getTaskId());
					}).inputRequestDeclarations(List.of(form)).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("decimal-elicitation-test", "1").build(), VERSIONS)
					.taskProtocolVersions(VERSIONS).serverInfoIncluded(false).toolRegistrations(List.of(unary, task)).build();
			McpProtectionKeyring keyring = McpProtectionKeyring.withActiveKey(McpProtectionKey.fromIdAndBytes("test",
					"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII))).build();
			McpAdmissionIdentity identity = McpAdmissionIdentity.withRateLimitPartitionKey("test-rate").authorizationPartitionKey("test-owner").build();
			server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).taskManager(taskManager)
					.protectionConfig(McpProtectionConfig.withKeyring(keyring).build())
					.admissionController(admissionContext -> McpAdmissionDecision.accepted(identity))
					.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
			soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent logEvent) {} })
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
			soklet.start();
		}

		private String post(String method, String operationName, String id, String fields, String capabilities, int expectedStatus) throws Exception {
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method + "\",\"params\":{" + fields
					+ ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":" + capabilities + "}}}";
			try (RawClient client = new RawClient(server.getDiagnostics().getBoundAddress().orElseThrow().getPort(), "POST", "/mcp", body,
					List.of(new HeaderValue("MCP-Protocol-Version", "2026-07-28"),
							new HeaderValue("Mcp-Method", method), new HeaderValue("Mcp-Name", operationName)))) {
				Head head = client.readHead();
				String response = client.readBody(head);
				assertEquals(expectedStatus, head.status(), response);
				return response;
			}
		}

		@Override public void close() { soklet.close(); }
	}
}
