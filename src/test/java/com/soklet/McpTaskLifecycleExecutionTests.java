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
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Direct lifecycle-wait guards for task authority and post-handler application callbacks. */
@Timeout(60)
class McpTaskLifecycleExecutionTests {
	@TestFactory
	Stream<DynamicTest> taskManagerAndSanitizerCallbacksCannotJoinTheirOwnShutdown() {
		return Stream.of("find", "update", "cancel", "created-lookup", "sanitizer")
				.map(callback -> DynamicTest.dynamicTest(callback, () -> assertTimeoutPreemptively(Duration.ofSeconds(60),
						() -> assertCloseIsGuarded(callback))));
	}

	private static void assertCloseIsGuarded(String callback) throws Exception {
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicReference<Throwable> closeFailure = new AtomicReference<>();
		AtomicReference<McpTask> createdTask = new AtomicReference<>();
		AtomicBoolean marked = new AtomicBoolean();
		CountDownLatch callbackReturned = new CountDownLatch(1);
		Runnable attemptClose = () -> {
			Soklet owner = ownerReference.get();
			marked.set(LifecycleExecutionContext.isMarked(owner.getDirectLifecycle().executionOwnerToken()));
			try {
				// close() publishes shutdown intent, then must reject the self-wait.
				// Returning from this callback lets the independently owned shutdown finish.
				owner.close();
			} catch (Throwable failure) {
				closeFailure.set(failure);
			} finally {
				callbackReturned.countDown();
			}
		};
		McpTaskManager manager = new McpTaskManager() {
			@Override public Optional<McpTask> findTask(McpTaskRequestContext context) {
				if (callback.equals("find") || callback.equals("created-lookup")) attemptClose.run();
				return Optional.ofNullable(createdTask.get());
			}
			@Override public void updateTask(McpTaskUpdateContext context) {
				if (callback.equals("update")) attemptClose.run();
			}
			@Override public void requestTaskCancelation(McpTaskRequestContext context) {
				if (callback.equals("cancel")) attemptClose.run();
			}
		};
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("work", Set.of(McpProtocolVersion.V2026_07_28)).jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (!callback.equals("created-lookup")) return McpCompleteResult.fromToolText("done");
					Instant now = Instant.now();
					createdTask.set(McpTask.withTaskId("task", features.getTaskCreationContext().orElseThrow()
							.getTaskOrigin(), McpTaskStatus.WORKING, now, now).build());
					return McpTaskCreatedResult.<McpJsonObject>fromTaskId("task");
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("callback-lifecycle", "1").build(),
				Set.of(McpProtocolVersion.V2026_07_28))
				.taskProtocolVersions(Set.of(McpProtocolVersion.V2026_07_28)).toolRegistrations(List.of(tool)).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).taskManager(manager)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolResultSanitizer((request, name, arguments, result) -> {
					if (callback.equals("sanitizer")) attemptClose.run();
					return result;
				}).build();
		try (Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
				HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
			ownerReference.set(owner);
			owner.start();
			String method = switch (callback) {
				case "find" -> "tasks/get";
				case "update" -> "tasks/update";
				case "cancel" -> "tasks/cancel";
				default -> "tools/call";
			};
			String fields = method.equals("tools/call") ? "\"name\":\"work\",\"arguments\":{}"
					: "\"taskId\":\"task\"" + (method.equals("tasks/update") ? ",\"inputResponses\":{}" : "");
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"request\",\"method\":\"" + method
					+ "\",\"params\":{" + fields + ",\"_meta\":{"
					+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{\"extensions\":{"
					+ "\"io.modelcontextprotocol/tasks\":{}}}}}}";
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
					.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
					.header("Accept", "application/json, text/event-stream")
					.header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", method)
					.header("Mcp-Name", method.equals("tools/call") ? "work" : "task")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build();
			// Shutdown can close the socket before an operation response is offered.
			// Its wire outcome is independent of the callback's guarded self-wait.
			client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
			assertTrue(callbackReturned.await(5, TimeUnit.SECONDS), "The selected callback was not invoked");
			assertTrue(marked.get(), "Framework-owned application callbacks must carry the exact lifecycle owner");
			IllegalStateException failure = assertInstanceOf(IllegalStateException.class, closeFailure.get());
			assertTrue(failure.getMessage().contains("tracked lifecycle execution"), failure.toString());
			ShutdownResult result = owner.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
			assertTrue(result.isComplete(), result.toString());
			assertTrue(result.getShutdownComponentResults().stream().allMatch(component -> component
					.getResidualActivityEvidence().map(evidence -> evidence.getResidualActivityTypes().isEmpty()).orElse(true)),
					"A rejected self-wait must not freeze callback or executor residuals");
		}
	}
}
