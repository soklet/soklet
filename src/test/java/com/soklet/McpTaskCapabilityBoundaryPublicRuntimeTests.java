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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Endpoint, server and client task gates through public real listeners. */
@Timeout(30)
class McpTaskCapabilityBoundaryPublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);
	private static final Set<McpProtocolVersion> MODERN = Set.of(McpProtocolVersion.V2026_07_28);
	private static final String TASKS = "io.modelcontextprotocol/tasks";
	private static final String HANDLE = "private-task-handle-canary";

	@TestFactory
	Stream<DynamicTest> advancedTaskReturnsWithoutCreationContextAreInternalErrors() {
		return Stream.of(
				new Case("endpoint-disabled", McpProtocolVersion.V2026_07_28, false, true, true, false),
				new Case("client-undeclared", McpProtocolVersion.V2026_07_28, true, true, false, false),
				new Case("manager-absent", McpProtocolVersion.V2026_07_28, false, false, true, false),
				new Case("june", McpProtocolVersion.V2025_06_18, true, true, false, false),
				new Case("november", McpProtocolVersion.V2025_11_25, true, true, false, false),
				new Case("interceptor", McpProtocolVersion.V2026_07_28, false, true, true, true))
				.map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> verifyInvalidReturn(testCase)));
	}

	private void verifyInvalidReturn(Case testCase) throws Exception {
		AtomicBoolean invalidReturn = new AtomicBoolean(true);
		AtomicInteger handlerCalls = new AtomicInteger();
		AtomicInteger managerReads = new AtomicInteger();
		McpTaskManager manager = new McpTaskManager() {
			@Override
			public Optional<McpTask> findTask(McpTaskRequestContext taskRequestContext) {
				managerReads.incrementAndGet();
				throw new AssertionError("An ineligible task return must not look up a handle.");
			}
			@Override
			public void updateTask(McpTaskUpdateContext taskUpdateContext) { fail("Unexpected mutation."); }
			@Override
			public void requestTaskCancelation(McpTaskRequestContext taskRequestContext) { fail("Unexpected cancelation."); }
		};
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("optional", VERSIONS)
				.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
					handlerCalls.incrementAndGet();
					assertTrue(invocationFeatures.getTaskCreationContext().isEmpty());
					return invalidReturn.get() ? McpTaskCreatedResult.fromTaskId(HANDLE)
							: McpCompleteResult.fromToolText("inline");
				}).build();
		McpEndpoint endpoint = endpoint("/mcp", testCase.endpointEnabled(), tool);
		McpServer server = server(List.of(endpoint), testCase.managerConfigured() ? manager : null)
				.handlerInterceptor((requestContext, invocationFeatures, continuation) -> {
					if (testCase.interceptor() && invalidReturn.get()) {
						assertTrue(invocationFeatures.getTaskCreationContext().isEmpty());
						return McpTaskCreatedResult.fromTaskId(HANDLE);
					}
					return continuation.proceed();
				}).build();
		try (Soklet soklet = managed(server)) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			Response failure = post(port, "/mcp", testCase.version(), "tools/call", "optional",
					"\"name\":\"optional\",\"arguments\":{}", testCase.clientCapable());
			assertEquals(testCase.version() == McpProtocolVersion.V2026_07_28 ? 500 : 200,
					failure.status(), failure.body());
			assertTrue(failure.body().contains("\"code\":-32603"), failure.body());
			assertFalse(failure.body().contains("-32021"), failure.body());
			assertFalse(failure.body().contains(HANDLE), failure.body());
			assertEquals(0, managerReads.get());
			assertEquals(testCase.interceptor() ? 0 : 1, handlerCalls.get());
			invalidReturn.set(false);
			Response recovered = post(port, "/mcp", testCase.version(), "tools/call", "optional",
					"\"name\":\"optional\",\"arguments\":{}", testCase.clientCapable());
			assertEquals(200, recovered.status(), recovered.body());
			assertTrue(recovered.body().contains("\"text\":\"inline\""), recovered.body());
			assertEquals(0, managerReads.get());
		}
	}

	@Test
	void declaredTaskEndpointRequiresManagerBeforeRuntime() {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("optional", VERSIONS)
				.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) ->
						McpCompleteResult.fromToolText("inline")).build();
		assertThrows(IllegalStateException.class,
				() -> server(List.of(endpoint("/mcp", true, tool)), null).build());
	}

	@Test
	void sharedManagerDoesNotEnableTasksOnSiblingEndpoints() throws Exception {
		McpInMemoryTaskManager manager = McpTaskManager.fromInMemoryDefaults();
		List<Optional<McpTaskCreationContext>> contexts = new java.util.concurrent.CopyOnWriteArrayList<>();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("optional", VERSIONS)
				.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
					contexts.add(invocationFeatures.getTaskCreationContext());
					return McpCompleteResult.fromToolText("inline");
				}).build();
		McpServer server = server(List.of(endpoint("/enabled", true, tool),
				endpoint("/disabled", false, tool)), manager).build();
		try (Soklet soklet = managed(server)) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (String path : List.of("/enabled", "/disabled")) {
				Response discovery = post(port, path, McpProtocolVersion.V2026_07_28,
						"server/discover", null, "", false);
				assertEquals(200, discovery.status(), discovery.body());
				assertEquals(path.equals("/enabled"), discovery.body().contains("\"" + TASKS + "\""), discovery.body());
				Response inline = post(port, path, McpProtocolVersion.V2026_07_28,
						"tools/call", "optional", "\"name\":\"optional\",\"arguments\":{}", true);
				assertEquals(200, inline.status(), inline.body());
			}
			assertEquals(2, contexts.size());
			assertTrue(contexts.get(0).isPresent());
			assertTrue(contexts.get(1).isEmpty());
		}
	}

	private static McpEndpoint endpoint(String path, boolean enabled, McpToolRegistration<?> tool) {
		return McpEndpoint.withPath(path, McpImplementation.withNameAndVersion("tasks-gate-test", "1").build(), VERSIONS)
				.taskProtocolVersions(enabled ? MODERN : Set.of()).toolRegistrations(List.of(tool)).build();
	}

	private static McpServer.Builder server(List<McpEndpoint> endpoints, McpTaskManager manager) {
		return McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints)).taskManager(manager)
				.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed());
	}

	private static Soklet managed(McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build());
	}

	static Response post(int port, String path, McpProtocolVersion version, String method,
			String name, String fields, boolean clientCapable) throws Exception {
		String metadata = version == McpProtocolVersion.V2026_07_28
				? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":"
						+ (clientCapable ? "{\"extensions\":{\"" + TASKS + "\":{}}}" : "{}") + "}" : "";
		String params = fields + (!fields.isEmpty() && !metadata.isEmpty() ? "," : "") + metadata;
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"task-boundary\",\"method\":\"" + method
				+ "\",\"params\":{" + params + "}}";
		List<HeaderValue> headers = new ArrayList<>();
		headers.add(new HeaderValue("MCP-Protocol-Version", version.getWireValue()));
		if (version == McpProtocolVersion.V2026_07_28) {
			headers.add(new HeaderValue("Mcp-Method", method));
			if (name != null)
				headers.add(new HeaderValue("Mcp-Name", name));
		}
		try (RawClient client = new RawClient(port, "POST", path, body, headers)) {
			Head head = client.readHead();
			return new Response(head.status(), client.readBody(head));
		}
	}

	private record Case(String name, McpProtocolVersion version, boolean endpointEnabled,
			boolean managerConfigured, boolean clientCapable, boolean interceptor) {}
	static record Response(int status, String body) {}
}
