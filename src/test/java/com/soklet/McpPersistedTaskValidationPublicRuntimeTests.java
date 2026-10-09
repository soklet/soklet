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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.McpTaskCapabilityBoundaryPublicRuntimeTests.post;
import static org.junit.jupiter.api.Assertions.*;

/** Read-time validation leaves the manager's authoritative state unchanged. */
@Timeout(60)
class McpPersistedTaskValidationPublicRuntimeTests {
	private static final Set<McpProtocolVersion> MODERN = Set.of(McpProtocolVersion.V2026_07_28);
	private static final String CANARY = "private-persisted-task-canary";

	@Test
	void invalidCompletedReadsPreserveTerminalStateAndCanRecoverThroughSanitization() throws Exception {
		McpInMemoryTaskManager manager = McpTaskManager.fromInMemoryDefaults();
		AtomicReference<String> taskId = new AtomicReference<>();
		AtomicBoolean repair = new AtomicBoolean();
		AtomicInteger sanitizations = new AtomicInteger();
		McpServer server = server(manager, taskId)
				.toolResultSanitizer((requestContext, toolName, rawArguments, completeResult) -> {
					sanitizations.incrementAndGet();
					return repair.get() ? McpCompleteResult.fromToolOutput(McpToolOutput.fromStructuredContent(
							McpJsonObject.builder().put("answer", "safe").build())) : completeResult;
				}).build();
		try (Soklet soklet = managed(server)) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			create(port);
			AtomicInteger changes = new AtomicInteger();
			try (McpSubscriptionEventRegistration registration = manager.getTaskEventPublisher().orElseThrow()
					.subscribe(changedTaskId -> changes.incrementAndGet())) {
				// Worker completion checks state shape; delivery validates the typed output.
				McpTask completed = manager.completeTask(taskId.get(), McpCompleteResult.fromToolText(CANARY), null);
				assertEquals(McpTaskStatus.COMPLETED, completed.getTaskStatus());
				assertEquals(1, changes.get());
				for (int attempt = 0; attempt < 2; ++attempt) {
					assertFixedInternalError(get(port, taskId.get()), taskId.get());
					assertSame(completed, manager.findTask(taskId.get()).orElseThrow());
					assertEquals(1, changes.get());
				}
				assertEquals(2, sanitizations.get());
				assertThrows(IllegalStateException.class, () -> manager.failTask(taskId.get(),
						McpJsonRpcError.fromApplication(-31901, "Worker failure"), null));
				repair.set(true);
				var recovered = get(port, taskId.get());
				assertEquals(200, recovered.status(), recovered.body());
				assertTrue(recovered.body().contains("\"status\":\"completed\""), recovered.body());
				assertTrue(recovered.body().contains("\"structuredContent\":{\"answer\":\"safe\"}"), recovered.body());
				assertFalse(recovered.body().contains(CANARY), recovered.body());
				assertSame(completed, manager.findTask(taskId.get()).orElseThrow());
				assertEquals(1, changes.get());
				assertEquals(3, sanitizations.get());
			}
		}
	}

	@Test
	void undeclaredStoredInputFailsReadsUntilWorkerSupersedesIt() throws Exception {
		McpInMemoryTaskManager manager = McpTaskManager.fromInMemoryDefaults();
		AtomicReference<String> taskId = new AtomicReference<>();
		McpServer server = server(manager, taskId).build();
		try (Soklet soklet = managed(server)) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			create(port);
			McpInputRequest request = McpInputRequest.fromDeclaration(
					McpInputRequestDeclaration.fromElicitationUrl(McpInputRequirement.CONDITIONAL),
					McpJsonObject.builder().put("mode", "url").put("message", CANARY)
							.put("url", "https://example.com/authorize").build());
			McpTask waiting = manager.requestTaskInput(taskId.get(), Map.of("undeclared", request), null);
			for (int attempt = 0; attempt < 2; ++attempt) {
				assertFixedInternalError(get(port, taskId.get()), taskId.get());
				assertSame(waiting, manager.findTask(taskId.get()).orElseThrow());
			}
			McpTask resumed = manager.markTaskWorking(taskId.get(), null);
			var recovered = get(port, taskId.get());
			assertEquals(200, recovered.status(), recovered.body());
			assertTrue(recovered.body().contains("\"status\":\"working\""), recovered.body());
			assertTrue(resumed.getInputRequests().isEmpty());
			assertSame(resumed, manager.findTask(taskId.get()).orElseThrow());
		}
	}

	private static McpServer.Builder server(McpInMemoryTaskManager manager, AtomicReference<String> taskId) {
		McpToolRegistration<Arguments> tool = McpToolRegistration.withName("create", MODERN)
				.argumentAndOutputTypes(Arguments.class, Answer.class)
				.operationHandler((requestContext, arguments, invocationFeatures) -> {
					McpTask task = manager.createTask(invocationFeatures.getTaskCreationContext().orElseThrow());
					taskId.set(task.getTaskId());
					return McpTaskCreatedResult.<Answer>fromTaskId(task.getTaskId());
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("persisted-task-test", "1").build(), MODERN)
				.taskProtocolVersions(MODERN).toolRegistrations(List.of(tool)).build();
		return McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).taskManager(manager)
				.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed());
	}

	private static Soklet managed(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build());
	}

	private static void create(int port) throws Exception {
		var response = post(port, "/mcp", McpProtocolVersion.V2026_07_28,
				"tools/call", "create", "\"name\":\"create\",\"arguments\":{}", true);
		assertEquals(200, response.status(), response.body());
		assertTrue(response.body().contains("\"resultType\":\"task\""), response.body());
	}

	private static McpTaskCapabilityBoundaryPublicRuntimeTests.Response get(int port, String taskId) throws Exception {
		return post(port, "/mcp", McpProtocolVersion.V2026_07_28,
				"tasks/get", taskId, "\"taskId\":\"" + taskId + "\"", true);
	}

	private static void assertFixedInternalError(McpTaskCapabilityBoundaryPublicRuntimeTests.Response response,
			String taskId) {
		assertEquals(500, response.status(), response.body());
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"task-boundary\","
				+ "\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}", response.body());
		assertFalse(response.body().contains(CANARY), response.body());
		assertFalse(response.body().contains(taskId), response.body());
	}

	private record Arguments() {}
	private record Answer(String answer) {}
}
