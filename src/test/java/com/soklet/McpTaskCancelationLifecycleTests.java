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
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-listener coverage for the boundary between durable MCP task state and
 * request or server lifecycle.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(60)
public class McpTaskCancelationLifecycleTests {
	private static final String HOST = "127.0.0.1";
	private static final String PATH = "/mcp/tasks-lifecycle";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String TOOL_NAME = "tasks.lifecycle";

	@Test
	public void taskCancelationIsCooperativeAndDoesNotCancelOriginatingRequest()
			throws Exception {
		McpInMemoryTaskManager taskManager =
				McpTaskManager.fromInMemoryDefaults();
		CountDownLatch taskCreated = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		AtomicReference<McpTask> createdTask = new AtomicReference<>();
		AtomicReference<CancelationToken> requestCancelation =
				new AtomicReference<>();
		AtomicInteger requestCancelationCallbacks = new AtomicInteger();
		McpToolHandler<McpJsonObject> handler = (request, arguments, features) -> {
			CancelationToken cancelationToken = features.getCancelationToken();
			requestCancelation.set(cancelationToken);
			cancelationToken.onCancel(requestCancelationCallbacks::incrementAndGet);
			McpTask task = taskManager.createTask(
					features.getTaskControl().orElseThrow());
			createdTask.set(task);
			taskCreated.countDown();
			if (!releaseHandler.await(10, TimeUnit.SECONDS))
				throw new IllegalStateException(
						"Timed out waiting to release the task handler.");
			return McpTaskCreatedResult.<McpJsonObject>fromTaskId(task.getTaskId());
		};
		McpServer server = server(taskManager, handler, Duration.ofSeconds(15));
		Soklet soklet = managedSoklet(server);
		CompletableFuture<HttpResponse<String>> originatingRequest = null;

		try {
			soklet.start();
			int port = boundPort(server);
			originatingRequest = callToolAsync(port, "originating-request");
			Assertions.assertTrue(taskCreated.await(5, TimeUnit.SECONDS),
					"The task-capable handler did not create its task.");
			McpTask task = createdTask.get();
			Assertions.assertNotNull(task);

			HttpResponse<String> cancel = callTask(port, "tasks/cancel",
					"cancel-task", task.getTaskId());
			assertCompleteAcknowledgement(cancel, "cancel-task");
			Assertions.assertTrue(taskManager.isTaskCancelationRequested(
					task.getTaskId()));
			Assertions.assertEquals(McpTaskStatus.WORKING,
					taskManager.findTask(task.getTaskId()).orElseThrow()
							.getTaskStatus());
			Assertions.assertFalse(requestCancelation.get().isCanceled());
			Assertions.assertEquals(0, requestCancelationCallbacks.get());
			Assertions.assertFalse(originatingRequest.isDone(),
					"Task cancelation must not terminate the originating request.");

			taskManager.completeTask(task.getTaskId(),
					McpCompleteResult.fromToolText("completion won"), null);
			releaseHandler.countDown();
			HttpResponse<String> creation = originatingRequest.get(5,
					TimeUnit.SECONDS);
			Assertions.assertEquals(200, creation.statusCode(), creation.body());
			Assertions.assertTrue(creation.body().contains(
					"\"status\":\"completed\""), creation.body());
			Assertions.assertTrue(creation.body().contains(
					"\"resultType\":\"task\""), creation.body());
			Assertions.assertFalse(requestCancelation.get().isCanceled());
			Assertions.assertEquals(0, requestCancelationCallbacks.get());

			HttpResponse<String> completed = callTask(port, "tasks/get",
					"get-completed", task.getTaskId());
			Assertions.assertEquals(200, completed.statusCode(), completed.body());
			Assertions.assertTrue(completed.body().contains("completion won"),
					completed.body());
		} finally {
			releaseHandler.countDown();
			if (originatingRequest != null)
				originatingRequest.cancel(true);
			soklet.close();
		}
	}

	@Test
	public void requestCancelationNotificationIsAcceptedAndIgnored()
			throws Exception {
		McpInMemoryTaskManager taskManager =
				McpTaskManager.fromInMemoryDefaults();
		CountDownLatch taskCreated = new CountDownLatch(1);
		CountDownLatch requestCanceled = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		AtomicReference<McpTask> createdTask = new AtomicReference<>();
		AtomicReference<CancelationToken> requestCancelation =
				new AtomicReference<>();
		McpToolHandler<McpJsonObject> handler = (request, arguments, features) -> {
			CancelationToken cancelationToken = features.getCancelationToken();
			requestCancelation.set(cancelationToken);
			cancelationToken.onCancel(requestCanceled::countDown);
			McpTask task = taskManager.createTask(
					features.getTaskControl().orElseThrow());
			createdTask.set(task);
			taskCreated.countDown();
			try {
				awaitReleaseUninterruptibly(releaseHandler);
				return McpTaskCreatedResult.<McpJsonObject>fromTaskId(
						task.getTaskId());
			} finally {
				handlerExited.countDown();
			}
		};
		McpServer server = server(taskManager, handler, Duration.ofSeconds(15));
		Soklet soklet = managedSoklet(server);
		CompletableFuture<HttpResponse<String>> originatingRequest = null;

		try {
			soklet.start();
			int port = boundPort(server);
			String requestId = "ignored-request-cancelation";
			originatingRequest = callToolAsync(port, requestId);
			Assertions.assertTrue(taskCreated.await(5, TimeUnit.SECONDS),
					"The task-aware request did not create its durable task.");
			McpTask task = createdTask.get();
			Assertions.assertNotNull(task);

			HttpResponse<String> notification = notifyRequestCancelation(port,
					requestId);
			Assertions.assertEquals(202, notification.statusCode(),
					notification.body());
			Assertions.assertTrue(notification.body().isEmpty(),
					notification.body());
			Assertions.assertEquals(1L, requestCanceled.getCount(),
					"The ignored notification must not invoke cancelation callbacks.");
			Assertions.assertFalse(requestCancelation.get().isCanceled());
			Assertions.assertFalse(originatingRequest.isDone(),
					"The ignored notification must not finish the active request.");
			Assertions.assertFalse(taskManager.isTaskCancelationRequested(
					task.getTaskId()));
			Assertions.assertEquals(McpTaskStatus.WORKING,
					taskManager.findTask(task.getTaskId()).orElseThrow()
							.getTaskStatus());

			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(5, TimeUnit.SECONDS),
					"The task-aware request handler did not exit.");
			HttpResponse<String> creation = originatingRequest.get(5,
					TimeUnit.SECONDS);
			Assertions.assertEquals(200, creation.statusCode(), creation.body());
			Assertions.assertTrue(creation.body().contains(
					"\"resultType\":\"task\""), creation.body());
			Assertions.assertTrue(creation.body().contains(
					"\"status\":\"working\""), creation.body());
			Assertions.assertFalse(requestCancelation.get().isCanceled());
			Assertions.assertEquals(1L, requestCanceled.getCount());
		} finally {
			releaseHandler.countDown();
			if (originatingRequest != null && !originatingRequest.isDone())
				originatingRequest.cancel(true);
			soklet.close();
		}
	}

	@Test
	public void taskCancelationAndCompletionRacePreservesTerminalCompletion()
			throws Exception {
		McpInMemoryTaskManager taskManager =
				McpTaskManager.fromInMemoryDefaults();
		AtomicReference<McpTask> createdTask = new AtomicReference<>();
		McpToolHandler<McpJsonObject> handler = (request, arguments, features) -> {
			McpTask task = taskManager.createTask(
					features.getTaskControl().orElseThrow());
			createdTask.set(task);
			return McpTaskCreatedResult.<McpJsonObject>fromTaskId(task.getTaskId());
		};
		McpServer server = server(taskManager, handler, Duration.ofSeconds(5));
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			HttpResponse<String> creation = callTool(port, "create-raced-task");
			Assertions.assertEquals(200, creation.statusCode(), creation.body());
			McpTask racedTask = createdTask.get();
			Assertions.assertNotNull(racedTask);
			CyclicBarrier raceStart = new CyclicBarrier(2);
			CompletableFuture<HttpResponse<String>> cancelation =
					CompletableFuture.supplyAsync(() -> {
						awaitRaceStart(raceStart);
						try {
							return callTask(port, "tasks/cancel",
									"race-cancel", racedTask.getTaskId());
						} catch (Exception exception) {
							throw new CompletionException(exception);
						}
					});
			CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> {
				awaitRaceStart(raceStart);
				try {
					taskManager.completeTask(racedTask.getTaskId(),
							McpCompleteResult.fromToolText("race completion"), null);
				} catch (McpTaskNotFoundException exception) {
					throw new CompletionException(exception);
				}
			});

			assertCompleteAcknowledgement(cancelation.get(5, TimeUnit.SECONDS),
					"race-cancel");
			completion.get(5, TimeUnit.SECONDS);
			McpTask terminal = taskManager.findTask(racedTask.getTaskId())
					.orElseThrow();
			Assertions.assertEquals(McpTaskStatus.COMPLETED,
					terminal.getTaskStatus());
			HttpResponse<String> racedCompletion = callTask(port, "tasks/get",
					"get-raced-completion", racedTask.getTaskId());
			Assertions.assertEquals(200, racedCompletion.statusCode(),
					racedCompletion.body());
			Assertions.assertTrue(racedCompletion.body().contains("race completion"),
					racedCompletion.body());
			assertTerminalCompletionIsImmutable(taskManager, terminal);

			HttpResponse<String> secondCreation = callTool(port,
					"create-completion-first-task");
			Assertions.assertEquals(200, secondCreation.statusCode(),
					secondCreation.body());
			McpTask completionFirstTask = createdTask.get();
			Assertions.assertNotNull(completionFirstTask);
			taskManager.completeTask(completionFirstTask.getTaskId(),
					McpCompleteResult.fromToolText("completion first"), null);
			assertCompleteAcknowledgement(callTask(port, "tasks/cancel",
					"completion-first-cancel", completionFirstTask.getTaskId()),
					"completion-first-cancel");
			Assertions.assertFalse(taskManager.isTaskCancelationRequested(
					completionFirstTask.getTaskId()));
			McpTask completionFirstTerminal = taskManager.findTask(
					completionFirstTask.getTaskId()).orElseThrow();
			Assertions.assertEquals(McpTaskStatus.COMPLETED,
					completionFirstTerminal.getTaskStatus());
			assertTerminalCompletionIsImmutable(taskManager,
					completionFirstTerminal);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void requestTimeoutDoesNotRecordDurableTaskCancelation()
			throws Exception {
		McpInMemoryTaskManager taskManager =
				McpTaskManager.fromInMemoryDefaults();
		CountDownLatch taskCreated = new CountDownLatch(1);
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		CountDownLatch requestCanceled = new CountDownLatch(1);
		AtomicReference<McpTask> createdTask = new AtomicReference<>();
		AtomicReference<CancelationToken> requestCancelation =
				new AtomicReference<>();
		McpToolHandler<McpJsonObject> handler = (request, arguments, features) -> {
			CancelationToken cancelationToken = features.getCancelationToken();
			requestCancelation.set(cancelationToken);
			cancelationToken.onCancel(requestCanceled::countDown);
			McpTask task = taskManager.createTask(
					features.getTaskControl().orElseThrow());
			createdTask.set(task);
			taskCreated.countDown();
			try {
				Thread.sleep(TimeUnit.SECONDS.toMillis(10));
			} catch (InterruptedException exception) {
				handlerInterrupted.countDown();
			} finally {
				handlerExited.countDown();
			}
			return McpTaskCreatedResult.<McpJsonObject>fromTaskId(task.getTaskId());
		};
		McpServer server = server(taskManager, handler, Duration.ofSeconds(1));
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			CompletableFuture<HttpResponse<String>> request = callToolAsync(
					boundPort(server), "timed-out-request");
			Assertions.assertTrue(taskCreated.await(5, TimeUnit.SECONDS),
					"The timed request did not create its durable task.");
			HttpResponse<String> response = request.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(504, response.statusCode(), response.body());
			Assertions.assertTrue(requestCanceled.await(5, TimeUnit.SECONDS),
					"The request cancelation token was not signaled.");
			Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS),
					"The timed-out handler was not interrupted.");
			Assertions.assertTrue(handlerExited.await(5, TimeUnit.SECONDS),
					"The timed-out handler did not exit.");
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,
					requestCancelation.get().getCancelationReason().orElseThrow());

			McpTask task = createdTask.get();
			Assertions.assertNotNull(task);
			Assertions.assertFalse(taskManager.isTaskCancelationRequested(
					task.getTaskId()));
			Assertions.assertEquals(McpTaskStatus.WORKING,
					taskManager.findTask(task.getTaskId()).orElseThrow()
							.getTaskStatus());
			taskManager.completeTask(task.getTaskId(),
					McpCompleteResult.fromToolText("survived request timeout"), null);
			HttpResponse<String> completed = callTask(boundPort(server),
					"tasks/get", "get-after-timeout", task.getTaskId());
			Assertions.assertEquals(200, completed.statusCode(), completed.body());
			Assertions.assertTrue(completed.body().contains(
					"survived request timeout"), completed.body());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void shutdownLeavesDurableTaskWorkApplicationOwnedAcrossRestart()
			throws Exception {
		McpInMemoryTaskManager taskManager =
				McpTaskManager.fromInMemoryDefaults();
		AtomicReference<McpTask> createdTask = new AtomicReference<>();
		McpToolHandler<McpJsonObject> handler = (request, arguments, features) -> {
			McpTask task = taskManager.createTask(
					features.getTaskControl().orElseThrow());
			createdTask.set(task);
			return McpTaskCreatedResult.<McpJsonObject>fromTaskId(task.getTaskId());
		};
		McpServer firstServer = server(taskManager, handler,
				Duration.ofSeconds(5));
		Soklet firstSoklet = managedSoklet(firstServer);
		McpServer secondServer = null;
		Soklet secondSoklet = null;

		try {
			firstSoklet.start();
			HttpResponse<String> creation = callTool(boundPort(firstServer),
					"create-before-shutdown");
			Assertions.assertEquals(200, creation.statusCode(), creation.body());
			McpTask task = createdTask.get();
			Assertions.assertNotNull(task);

			firstSoklet.close();
			Assertions.assertEquals(McpTaskStatus.WORKING,
					taskManager.findTask(task.getTaskId()).orElseThrow()
							.getTaskStatus());
			Assertions.assertFalse(taskManager.isTaskCancelationRequested(
					task.getTaskId()));
			taskManager.completeTask(task.getTaskId(),
					McpCompleteResult.fromToolText("completed after shutdown"), null);

			secondServer = server(taskManager, handler, Duration.ofSeconds(5));
			secondSoklet = managedSoklet(secondServer);
			secondSoklet.start();
			HttpResponse<String> recovered = callTask(boundPort(secondServer),
					"tasks/get", "get-after-restart", task.getTaskId());
			Assertions.assertEquals(200, recovered.statusCode(), recovered.body());
			Assertions.assertTrue(recovered.body().contains(
					"completed after shutdown"), recovered.body());
			Assertions.assertTrue(recovered.body().contains(
					"\"status\":\"completed\""), recovered.body());
		} finally {
			if (secondSoklet != null)
				secondSoklet.close();
			firstSoklet.close();
		}
	}

	@NonNull
	private static McpServer server(@NonNull McpInMemoryTaskManager taskManager,
			@NonNull McpToolHandler<McpJsonObject> handler,
			@NonNull Duration requestTimeout) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonObjectArguments()
				.handler(handler)
				.structuredContentMirroredAsText(false)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion(
						"task-cancelation-lifecycle-test", "4.0.0").build())
				.addTool(tool)
				.build();
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.taskManager(taskManager)
				.host(HOST)
				.requestTimeout(requestTimeout)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST))
				.build();
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.build());
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	@NonNull
	private static HttpResponse<String> callTool(int port,
			@NonNull String requestId) throws Exception {
		return callToolAsync(port, requestId).get(5, TimeUnit.SECONDS);
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> callToolAsync(int port,
			@NonNull String requestId) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"tools/call\",\"params\":{" + taskMetadata()
				+ ",\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}}}";
		return sendAsync(port, "tools/call", TOOL_NAME, body);
	}

	@NonNull
	private static HttpResponse<String> callTask(int port,
			@NonNull String method, @NonNull String requestId,
			@NonNull String taskId) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{"
				+ taskMetadata() + ",\"taskId\":\"" + taskId + "\"}}";
		return sendAsync(port, method, taskId, body).get(5, TimeUnit.SECONDS);
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> sendAsync(int port,
			@NonNull String method, @NonNull String operationName,
			@NonNull String body) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + HOST + ':' + port + PATH))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method)
				.header("Mcp-Name", operationName)
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.sendAsync(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	@NonNull
	private static HttpResponse<String> notifyRequestCancelation(int port,
			@NonNull String requestId) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/cancelled\","
				+ "\"params\":{\"requestId\":\"" + requestId + "\"}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + HOST + ':' + port + PATH))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	private static void awaitReleaseUninterruptibly(
			@NonNull CountDownLatch releaseHandler) {
		boolean interrupted = false;
		while (true) {
			try {
				if (releaseHandler.await(10, TimeUnit.SECONDS))
					break;
				throw new IllegalStateException(
						"Timed out waiting to release the task handler.");
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static void awaitRaceStart(@NonNull CyclicBarrier raceStart) {
		try {
			raceStart.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new CompletionException(exception);
		} catch (BrokenBarrierException | TimeoutException exception) {
			throw new CompletionException(exception);
		}
	}

	private static void assertTerminalCompletionIsImmutable(
			@NonNull McpInMemoryTaskManager taskManager,
			@NonNull McpTask terminal) {
		Assertions.assertThrows(IllegalStateException.class, () ->
				taskManager.completeTask(terminal.getTaskId(),
						McpCompleteResult.fromToolText("replacement"), null));
		Assertions.assertThrows(IllegalStateException.class, () ->
				taskManager.cancelTask(terminal.getTaskId(), "replacement"));
		Assertions.assertThrows(IllegalStateException.class, () ->
				taskManager.failTask(terminal.getTaskId(),
						McpJsonRpcError.fromApplication(1_001, "replacement"),
						null));
		Assertions.assertEquals(terminal,
				taskManager.findTask(terminal.getTaskId()).orElseThrow());
	}

	@NonNull
	private static String taskMetadata() {
		return "\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{"
				+ "\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}}}";
	}

	private static void assertCompleteAcknowledgement(
			@NonNull HttpResponse<String> response, @NonNull String requestId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
				+ requestId + "\",\"result\":{\"resultType\":\"complete\"}}",
				response.body());
	}
}
