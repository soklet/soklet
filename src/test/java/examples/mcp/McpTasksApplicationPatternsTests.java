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

package examples.mcp;

import com.soklet.CorsAuthorizer;
import com.soklet.HttpMethod;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpJsonObject;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestContext;
import com.soklet.McpServer;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyType;
import com.soklet.McpSimulationResponse;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpTask;
import com.soklet.McpTaskControl;
import com.soklet.McpTaskCreatedResult;
import com.soklet.McpTaskManager;
import com.soklet.McpTaskNotFoundException;
import com.soklet.McpTaskRequestContext;
import com.soklet.McpTaskStatus;
import com.soklet.McpTaskUpdateContext;
import com.soklet.McpToolRegistration;
import com.soklet.Request;
import com.soklet.ResourceMethodResolver;
import com.soklet.Simulator;
import com.soklet.SokletConfig;
import com.soklet.SokletSimulator;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Executable application pattern for durable MCP Tasks.
 *
 * <p>The manager is application code and delegates every durable decision to a
 * repository boundary. This test supplies a synchronized memory repository so
 * the example stays self-contained; a production application substitutes a
 * shared database, transactional outbox, and worker infrastructure without
 * changing Soklet's task-manager contract.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@Timeout(60)
public class McpTasksApplicationPatternsTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/reports/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String TOOL_NAME = "reports.generate";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	@Timeout(120)
	void applicationOwnedManagerSurvivesTheOriginalRequestBoundary()
			throws Exception {
		TestReportTaskRepository repository = new TestReportTaskRepository();
		ReportTaskManager taskManager = new ReportTaskManager(repository);
		SokletConfig config = config(taskManager);

		SokletSimulator.run(config, simulator -> {
			String creation = performJson(simulator, request("tools/call",
					TOOL_NAME, "create", "\"name\":\"" + TOOL_NAME
							+ "\",\"arguments\":{\"reportId\":\"weekly\"}",
					"tenant-alpha"));
			String taskId = repository.onlyTaskId();
			Assertions.assertTrue(creation.contains("\"resultType\":\"task\""),
					creation);
			Assertions.assertTrue(creation.contains("\"taskId\":\"" + taskId
					+ "\""), creation);
			Assertions.assertEquals(McpTaskStatus.WORKING,
					repository.requireTask(taskId).getTaskStatus());

			// An application worker, not Soklet, claims and completes durable work.
			taskManager.runOneReportJob();

			// A fresh stateless request recovers the result without the original
			// connection, invocation, or cancelation token.
			String recovered = performJson(simulator, request("tasks/get", taskId,
					"recover", "\"taskId\":\"" + taskId + "\"",
					"tenant-alpha"));
			Assertions.assertTrue(recovered.contains("\"status\":\"completed\""),
					recovered);
			Assertions.assertTrue(recovered.contains(
					"\"structuredContent\":{\"downloadUrl\":"), recovered);

			String unauthorized = performJson(simulator, request("tasks/get",
					taskId, "isolated", "\"taskId\":\"" + taskId + "\"",
					"tenant-beta"), 400);
			String unknown = performJson(simulator, request("tasks/get",
					"missing-task", "isolated", "\"taskId\":\"missing-task\"",
					"tenant-alpha"), 400);
			Assertions.assertTrue(unauthorized.contains("\"code\":-32602"),
					unauthorized);
			Assertions.assertFalse(unauthorized.contains(taskId), unauthorized);
			Assertions.assertEquals(unknown, unauthorized);
		});
	}

	@NonNull
	private static SokletConfig config(@NonNull ReportTaskManager taskManager) {
		McpToolRegistration<ReportArguments> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.argumentAndOutputTypes(ReportArguments.class,
						GeneratedReport.class)
				.operationHandler((request, arguments, features) -> {
					String taskId = taskManager.createAndEnqueue(
							arguments.getConvertedArguments(),
							features.getTaskControl().orElseThrow());
					return McpTaskCreatedResult
							.<GeneratedReport>fromTaskId(taskId);
				})
				.structuredContentMirroredAsText(false)
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"tasks-application-pattern", "4.0.0").build())
				.addTool(tool)
				.build();
		McpServer server = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(endpoint)))
				.admissionController(context -> {
					// Test-only identity seam; production authenticates a credential.
					String tenant = context.getRequest().getHeader("X-Test-Tenant")
							.orElseThrow();
					return McpAdmissionDecision.accepted(McpAdmissionIdentity
							.withRateLimitPartitionKey("rate:" + tenant)
							.authorizationPartitionKey(tenant)
							.principal(tenant)
							.build());
				})
				.host(LOOPBACK)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.taskManager(taskManager)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
		return SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
	}

	@NonNull
	private static String performJson(@NonNull Simulator simulator,
			@NonNull Request request) throws InterruptedException {
		return performJson(simulator, request, 200);
	}

	@NonNull
	private static String performJson(@NonNull Simulator simulator,
			@NonNull Request request, int expectedStatusCode)
			throws InterruptedException {
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(WAIT)
					.orElseThrow(() -> new AssertionError(
							"Timed out waiting for an MCP response."));
			Assertions.assertEquals(expectedStatusCode, response.getStatusCode(),
					response.getHeaders().toString());
			Assertions.assertEquals(McpSimulationBodyType.JSON,
					response.getBodyType());
			String json = new String(response.getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					simulation.awaitCompletion(WAIT).orElseThrow().getReason());
			return json;
		}
	}

	@NonNull
	private static Request request(@NonNull String method,
			@NonNull String operationName, @NonNull String requestId,
			@NonNull String fields, @NonNull String tenant) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + method + "\",\"params\":{" + fields
				+ ",\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{"
				+ "\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}}}}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));
		headers.put("Mcp-Name", Set.of(operationName));
		headers.put("X-Test-Tenant", Set.of(tenant));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private record ReportArguments(@NonNull String reportId) {
	}

	private record GeneratedReport(@NonNull String downloadUrl) {
	}

	/**
	 * Application adapter from Soklet's protocol SPI to durable repositories and
	 * workers. It deliberately contains no executor or storage implementation.
	 */
	@ThreadSafe
	private static final class ReportTaskManager implements McpTaskManager {
		@NonNull
		private final ReportTaskRepository repository;

		private ReportTaskManager(@NonNull ReportTaskRepository repository) {
			this.repository = repository;
		}

		@NonNull
		private String createAndEnqueue(@NonNull ReportArguments arguments,
				@NonNull McpTaskControl taskControl) {
			McpRequestContext request = taskControl.getRequestContext();
			String taskId = UUID.randomUUID().toString();
			Instant now = Instant.now();
			McpTask task = McpTask.withTaskId(taskId,
					taskControl.getTaskOrigin(), McpTaskStatus.WORKING, now, now)
					.pollInterval(Duration.ofSeconds(2))
					.timeToLive(Duration.ofDays(7))
					.build();
			TaskBinding binding = new TaskBinding(request.getEndpoint().getPath(),
					request.getAdmissionIdentity().getAuthorizationPartitionKey());
			this.repository.insertTaskAndEnqueueWork(task, binding, arguments);
			return taskId;
		}

		private void runOneReportJob() {
			ReportWork work = this.repository.takeWork().orElseThrow();
			GeneratedReport report = new GeneratedReport(
					"https://reports.example/" + work.arguments().reportId());
			this.repository.completeTask(work.taskId(), report);
		}

		@Override
		@NonNull
		public Optional<@NonNull McpTask> findTask(
				@NonNull McpTaskRequestContext context) {
			return this.repository.findAuthorizedTask(context);
		}

		@Override
		public void updateTask(@NonNull McpTaskUpdateContext context)
				throws McpTaskNotFoundException {
			// This tool declares no input requests, so every supplied key is
			// unknown and is ignored after the atomic authorization check.
			this.repository.requireAuthorizedTask(context.getTaskId(),
					context.getRequestContext());
		}

		@Override
		public void requestTaskCancelation(
				@NonNull McpTaskRequestContext context)
				throws McpTaskNotFoundException {
			this.repository.recordCancelationIntent(context.getTaskId(),
					context.getRequestContext());
		}
	}

	private interface ReportTaskRepository {
		void insertTaskAndEnqueueWork(@NonNull McpTask task,
				@NonNull TaskBinding binding,
				@NonNull ReportArguments arguments);

		@NonNull
		Optional<@NonNull McpTask> findAuthorizedTask(
				@NonNull McpTaskRequestContext context);

		void requireAuthorizedTask(@NonNull String taskId,
				@NonNull McpRequestContext requestContext)
				throws McpTaskNotFoundException;

		void recordCancelationIntent(@NonNull String taskId,
				@NonNull McpRequestContext requestContext)
				throws McpTaskNotFoundException;

		@NonNull
		Optional<@NonNull ReportWork> takeWork();

		void completeTask(@NonNull String taskId,
				@NonNull GeneratedReport report);
	}

	/** Self-contained repository test double; not a production task backend. */
	private static final class TestReportTaskRepository
			implements ReportTaskRepository {
		@NonNull
		private final Map<@NonNull String, @NonNull TaskRow> rows =
				new LinkedHashMap<>();
		@NonNull
		private final ArrayDeque<@NonNull ReportWork> work = new ArrayDeque<>();

		@Override
		public synchronized void insertTaskAndEnqueueWork(@NonNull McpTask task,
				@NonNull TaskBinding binding,
				@NonNull ReportArguments arguments) {
			TaskRow row = new TaskRow(task, binding, false);
			if (this.rows.putIfAbsent(task.getTaskId(), row) != null)
				throw new IllegalStateException("Duplicate task ID.");
			this.work.addLast(new ReportWork(task.getTaskId(), arguments));
		}

		@Override
		@NonNull
		public synchronized Optional<@NonNull McpTask> findAuthorizedTask(
				@NonNull McpTaskRequestContext context) {
			TaskRow row = this.rows.get(context.getTaskId());
			if (row == null || !row.binding().authorizes(
					context.getRequestContext()))
				return Optional.empty();
			return Optional.of(row.task());
		}

		@Override
		public synchronized void requireAuthorizedTask(@NonNull String taskId,
				@NonNull McpRequestContext requestContext)
				throws McpTaskNotFoundException {
			requireAuthorizedRow(taskId, requestContext);
		}

		@Override
		public synchronized void recordCancelationIntent(@NonNull String taskId,
				@NonNull McpRequestContext requestContext)
				throws McpTaskNotFoundException {
			TaskRow row = requireAuthorizedRow(taskId, requestContext);
			this.rows.put(taskId, new TaskRow(row.task(), row.binding(), true));
		}

		@Override
		@NonNull
		public synchronized Optional<@NonNull ReportWork> takeWork() {
			return Optional.ofNullable(this.work.pollFirst());
		}

		@Override
		public synchronized void completeTask(@NonNull String taskId,
				@NonNull GeneratedReport report) {
			TaskRow row = Optional.ofNullable(this.rows.get(taskId)).orElseThrow();
			McpTask current = row.task();
			McpTask completed = McpTask.withTaskId(taskId,
					current.getTaskOrigin(), McpTaskStatus.COMPLETED,
					current.getCreatedAt(), Instant.now())
					.pollInterval(current.getPollInterval().orElse(null))
					.timeToLive(current.getTimeToLive().orElse(null))
					.completedResult(McpCompleteResult.fromToolStructuredContent(
							McpJsonObject.builder()
									.put("downloadUrl", report.downloadUrl())
									.build()))
					.build();
			this.rows.put(taskId,
					new TaskRow(completed, row.binding(), row.cancelationRequested()));
		}

		@NonNull
		private synchronized TaskRow requireAuthorizedRow(@NonNull String taskId,
				@NonNull McpRequestContext requestContext)
				throws McpTaskNotFoundException {
			TaskRow row = this.rows.get(taskId);
			if (row == null || !row.binding().authorizes(requestContext))
				throw new McpTaskNotFoundException();
			return row;
		}

		@NonNull
		private synchronized String onlyTaskId() {
			Assertions.assertEquals(1, this.rows.size());
			return this.rows.keySet().iterator().next();
		}

		@NonNull
		private synchronized McpTask requireTask(@NonNull String taskId) {
			return Optional.ofNullable(this.rows.get(taskId))
					.map(TaskRow::task)
					.orElseThrow();
		}
	}

	private record TaskBinding(
			@NonNull String endpointPath,
			@NonNull Optional<@NonNull String> authorizationPartitionKey) {
		private boolean authorizes(@NonNull McpRequestContext request) {
			return this.endpointPath.equals(request.getEndpoint().getPath())
					&& this.authorizationPartitionKey.equals(request
							.getAdmissionIdentity().getAuthorizationPartitionKey());
		}
	}

	private record ReportWork(
			@NonNull String taskId,
			@NonNull ReportArguments arguments) {
	}

	private record TaskRow(
			@NonNull McpTask task,
			@NonNull TaskBinding binding,
			boolean cancelationRequested) {
	}
}
