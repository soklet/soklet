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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live callback coverage for localization and deferred task catalog authority.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
class McpLocalizationLifecycleExecutionTests {
	@TestFactory
	Stream<DynamicTest> localizationAndCompletedTaskPolicyCallbacksCannotJoinTheirOwnShutdown() {
		return Stream.of("application-provider", "catalog-provider", "catalog-lookup", "completed-task-policy")
				.map(callback -> DynamicTest.dynamicTest(callback, () -> assertTimeoutPreemptively(Duration.ofSeconds(60),
						() -> assertCloseIsGuarded(callback))));
	}

	private static void assertCloseIsGuarded(String callback) throws Exception {
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicReference<Throwable> closeFailure = new AtomicReference<>();
		AtomicReference<McpTask> createdTask = new AtomicReference<>();
		AtomicBoolean attempted = new AtomicBoolean();
		AtomicBoolean marked = new AtomicBoolean();
		CountDownLatch callbackReturned = new CountDownLatch(1);
		Runnable attemptClose = () -> {
			if (!attempted.compareAndSet(false, true)) return;
			Soklet owner = ownerReference.get();
			marked.set(LifecycleExecutionContext.isMarked(owner.getDirectLifecycle().executionOwnerToken()));
			try {
				owner.close();
			} catch (Throwable failure) {
				closeFailure.set(failure);
			} finally {
				callbackReturned.countDown();
			}
		};
		McpTaskManager manager = new McpTaskManager() {
			@Override public Optional<McpTask> findTask(McpTaskRequestContext context) {
				return Optional.ofNullable(createdTask.get());
			}
			@Override public void updateTask(McpTaskUpdateContext context) {}
			@Override public void requestTaskCancelation(McpTaskRequestContext context) {}
		};
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("work", Set.of(McpProtocolVersion.V2026_07_28)).jsonObjectArguments()
				.handler((request, arguments, features) -> {
					if (!callback.equals("completed-task-policy")) return McpCompleteResult.fromToolText("done");
					Instant now = Instant.now();
					createdTask.set(McpTask.withTaskId("task", features.getTaskCreationContext().orElseThrow()
							.getTaskOrigin(), McpTaskStatus.COMPLETED, now, now)
							.completedResult(McpCompleteResult.fromToolText("done")).build());
					return McpTaskCreatedResult.<McpJsonObject>fromTaskId("task");
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("localization-lifecycle", "1").build(),
				Set.of(McpProtocolVersion.V2026_07_28)).instructions("Instructions")
				.taskProtocolVersions(Set.of(McpProtocolVersion.V2026_07_28)).toolRegistrations(List.of(tool)).build();
		var builder = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).taskManager(manager)
				.toolRateLimiter(context -> McpRateLimitDecision.allowed());
		if (callback.equals("completed-task-policy")) {
			builder.catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators((request, registration, features) -> {
				if (request.getJsonRpcMethod().equals("tasks/get")) attemptClose.run();
				return true;
			}, (request, registration, features) -> true));
		} else {
			builder.localizer(McpLocalizer.withFallbackLocale(Locale.ENGLISH, request -> {
				if (!callback.equals("catalog-lookup")) attemptClose.run();
				return McpLocalizationContext.withLocale(Locale.ENGLISH, text -> {
					if (callback.equals("catalog-lookup")) attemptClose.run();
					return McpLocalizationResult.useDefaultText();
				}).build();
			}).build());
		}
		McpServer server = builder.build();
		// The callbacks must inspect the exact owner token and verify owner-scoped self-wait guards.
		// The callbacks must inspect the exact owner token and verify owner-scoped self-wait guards.
		try (SokletTestScope ownerScope = SokletTestScope.of(Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build()));
				HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
			Soklet owner = ownerScope.soklet();
			ownerReference.set(owner);
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			if (callback.equals("completed-task-policy")) {
				HttpResponse<String> created = client.send(request(port, "tools/call", "work",
						"\"name\":\"work\",\"arguments\":{}"), HttpResponse.BodyHandlers.ofString());
				assertEquals(200, created.statusCode(), created.body());
				assertTrue(created.body().contains("\"resultType\":\"task\""), created.body());
			}
			boolean catalog = callback.startsWith("catalog-");
			String method = catalog ? "server/discover" : callback.equals("application-provider") ? "tools/call" : "tasks/get";
			String fields = catalog ? "" : method.equals("tools/call") ? "\"name\":\"work\",\"arguments\":{}" : "\"taskId\":\"task\"";
			client.sendAsync(request(port, method, catalog ? null : method.equals("tools/call") ? "work" : "task", fields),
					HttpResponse.BodyHandlers.discarding());
			assertTrue(callbackReturned.await(5, TimeUnit.SECONDS), "The selected callback was not invoked");
			assertTrue(marked.get(), "Framework-owned application callbacks must carry the exact lifecycle owner");
			IllegalStateException failure = assertInstanceOf(IllegalStateException.class, closeFailure.get());
			assertTrue(failure.getMessage().contains("tracked lifecycle execution"), failure.toString());
			ShutdownResult result = owner.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
			assertTrue(result.isComplete(), result.toString());
			assertTrue(result.getShutdownComponentResults().stream().allMatch(component -> component
					.getResidualActivityEvidence().map(evidence -> evidence.getResidualActivityTypes().isEmpty()).orElse(true)),
					"Guarded self-wait must not freeze callback or executor residuals");
		}
	}

	private static HttpRequest request(int port, String method, String name, String fields) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"request\",\"method\":\"" + method + "\",\"params\":{" + fields
				+ (fields.isEmpty() ? "" : ",") + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{\"extensions\":{\"io.modelcontextprotocol/tasks\":{}}}}}}";
		var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
				.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", method);
		if (name != null) builder.header("Mcp-Name", name);
		return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
	}
}
