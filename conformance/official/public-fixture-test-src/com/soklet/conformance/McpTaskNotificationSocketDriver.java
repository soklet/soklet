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

package com.soklet.conformance;

import com.soklet.CorsAuthorizer;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequirement;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import com.soklet.McpMetricsEvent;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestContext;
import com.soklet.McpResourcePage;
import com.soklet.McpServer;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionEventRegistration;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTask;
import com.soklet.McpTaskCreatedResult;
import com.soklet.McpTaskEventPublisher;
import com.soklet.McpTaskEventListener;
import com.soklet.McpTaskManager;
import com.soklet.McpTaskNotFoundException;
import com.soklet.McpTaskOrigin;
import com.soklet.McpTaskRequestContext;
import com.soklet.McpTaskStatus;
import com.soklet.McpTaskUpdateContext;
import com.soklet.McpToolRegistration;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Candidate-JAR-only real-listener Tasks notification supplement. This main is
 * supervised with a 120-second command timeout and bounded process-tree cleanup.
 * Public socket assertions mirror the source-suite contract; private retention
 * inspections remain source-suite tests and are not claimed by this driver.
 */
public final class McpTaskNotificationSocketDriver {
	public static void main(String[] args) throws Exception {
		if (args.length != 0)
			throw new IllegalArgumentException("No arguments are accepted");
		McpTaskNotificationSocketDriver driver = new McpTaskNotificationSocketDriver();
		driver.cancelAcknowledgesAndPublishesTerminalSnapshot();
		System.out.println("PASS\tcancelAcknowledgesAndPublishesTerminalSnapshot");
		driver.acknowledgmentFiltersTaskIdsAndEventCarriesExactCurrentSnapshot();
		System.out.println("PASS\tacknowledgmentFiltersTaskIdsAndEventCarriesExactCurrentSnapshot");
		driver.staleEventReadsTerminalStateAndRevokedAuthorizationIsSuppressed();
		System.out.println("PASS\tstaleEventReadsTerminalStateAndRevokedAuthorizationIsSuppressed");
		driver.inputRequiredTasksAreFilteredByEachSubscribersCapabilities();
		System.out.println("PASS\tinputRequiredTasksAreFilteredByEachSubscribersCapabilities");
		driver.duplicateEventsReadCurrentTerminalAndSuppressLaterStates();
		System.out.println("PASS\tduplicateEventsReadCurrentTerminalAndSuppressLaterStates");
		driver.maximumTaskIdStormCoalescesWithoutClosingItsSubscription();
		System.out.println("PASS\tmaximumTaskIdStormCoalescesWithoutClosingItsSubscription");
		driver.disconnectDuringActiveProjectionReleasesSubscriptionAndRecovers();
		System.out.println("PASS\tdisconnectDuringActiveProjectionReleasesSubscriptionAndRecovers");
		driver.reconnectUsesNewIdAndDoesNotReplayDisconnectedEvents();
		System.out.println("PASS\treconnectUsesNewIdAndDoesNotReplayDisconnectedEvents");
	}

	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String TOOL_NAME = "tasks.notifications.seed";
	private static final String ALPHA = "alpha";
	private static final String BETA = "beta";
	private static final McpInputRequestDeclaration FORM_DECLARATION =
			McpInputRequestDeclaration.fromElicitationForm(McpInputRequirement.CONDITIONAL);
	private static final McpJsonObject FORM_PARAMETERS = McpJsonObject.builder()
			.put("message", "Approve task?")
			.put("mode", "form")
			.put("requestedSchema", McpJsonObject.builder()
					.put("type", "object")
					.put("properties", McpJsonObject.emptyInstance())
					.build())
			.build();
	private static final Instant CREATED_AT =
			Instant.parse("2026-09-01T12:00:00Z");
	private static final Instant WORKING_UPDATED_AT =
			Instant.parse("2026-09-01T12:01:00Z");
	private static final Instant COMPLETED_UPDATED_AT =
			Instant.parse("2026-09-01T12:02:00Z");
	private static final Instant CANCELED_UPDATED_AT =
			Instant.parse("2026-09-01T12:03:00Z");
	private static final Instant REGRESSED_UPDATED_AT =
			Instant.parse("2026-09-01T12:04:00Z");
	private static final Instant INPUT_REQUIRED_UPDATED_AT =
			Instant.parse("2026-09-01T12:05:00Z");
	private static final Duration TASK_TIME_TO_LIVE = Duration.ofMinutes(1);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

	public void acknowledgmentFiltersTaskIdsAndEventCarriesExactCurrentSnapshot()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		AtomicInteger admissions = new AtomicInteger();
		McpServer server = server(taskManager, admissions);
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-first", ALPHA);
			seedTask(port, "task-second", ALPHA);
			seedTask(port, "task-private", BETA);
			admissions.set(0);
			taskManager.resetFindInvocations();

			client = listen(port, "\"task-filter\"", ALPHA, true,
					"{\"taskIds\":[\"task-second\",\"task-missing\","
							+ "\"task-private\",\"task-first\","
							+ "\"task-second\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"task-filter\"",
					List.of("task-second", "task-first")),
					client.readChunkText());
			Assertions.assertEquals(1, admissions.get());
			Assertions.assertEquals(4, taskManager.findInvocations(),
					"Task IDs must be deduplicated before authorization lookup.");

			taskManager.publishTaskChanged("task-missing");
			taskManager.publishTaskChanged("task-private");
			taskManager.publishTaskChanged("task-first");
			Assertions.assertEquals(workingNotification("\"task-filter\"",
					"task-first"), client.readChunkText(),
					"Only an accepted task ID may produce a notification.");
		} finally {
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	public void staleEventReadsTerminalStateAndRevokedAuthorizationIsSuppressed()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = server(taskManager, new AtomicInteger());
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-stale", ALPHA);
			seedTask(port, "task-revoked", ALPHA);
			seedTask(port, "task-control", ALPHA);

			client = listen(port, "\"fresh-snapshot\"", ALPHA, true,
					"{\"taskIds\":[\"task-stale\",\"task-revoked\","
							+ "\"task-control\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"fresh-snapshot\"",
					List.of("task-stale", "task-revoked", "task-control")),
					client.readChunkText());

			taskManager.replaceTask(completedTask(
					taskManager.requireTask("task-stale")));
			taskManager.publishTaskChanged("task-stale");
			Assertions.assertEquals(completedNotification("\"fresh-snapshot\"",
					"task-stale"), client.readChunkText(),
					"A coarse stale event must render a fresh authoritative snapshot.");

			taskManager.revoke("task-revoked");
			taskManager.publishTaskChanged("task-revoked");
			taskManager.awaitRevokedLookup();
			taskManager.publishTaskChanged("task-control");
			Assertions.assertEquals(workingNotification("\"fresh-snapshot\"",
					"task-control"), client.readChunkText(),
					"Revoked authorization must suppress the earlier task event.");
		} finally {
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	public void inputRequiredTasksAreFilteredByEachSubscribersCapabilities()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = server(taskManager, new AtomicInteger());
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient withoutForm = null;
		McpChunkedHttpClient withForm = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-input-initial", ALPHA);
			seedTask(port, "task-input-transition", ALPHA);
			seedTask(port, "task-input-control", ALPHA);
			taskManager.replaceTask(inputRequiredTask(
					taskManager.requireTask("task-input-initial")));

			withoutForm = listen(port, "\"without-form\"", ALPHA, true,
					false, "{\"taskIds\":[\"task-input-initial\","
							+ "\"task-input-transition\",\"task-input-control\"]}");
			assertSseHead(withoutForm.readHead());
			Assertions.assertEquals(acknowledgment("\"without-form\"", List.of(
					"task-input-transition", "task-input-control")),
					withoutForm.readChunkText(),
					"Initial authorization must omit an input-required task when "
							+ "the subscriber lacks form elicitation support.");

			withForm = listen(port, "\"with-form\"", ALPHA, true, true,
					"{\"taskIds\":[\"task-input-initial\","
							+ "\"task-input-transition\"]}");
			assertSseHead(withForm.readHead());
			Assertions.assertEquals(acknowledgment("\"with-form\"", List.of(
					"task-input-initial", "task-input-transition")),
					withForm.readChunkText(),
					"A form-elicitation-capable subscriber must retain the same task.");

			taskManager.replaceTask(inputRequiredTask(
					taskManager.requireTask("task-input-transition")));
			taskManager.publishTaskChanged("task-input-transition");
			Assertions.assertEquals(inputRequiredNotification("\"with-form\"",
					"task-input-transition"), withForm.readChunkText());

			taskManager.publishTaskChanged("task-input-control");
			Assertions.assertEquals(workingNotification("\"without-form\"",
					"task-input-control"), withoutForm.readChunkText(),
					"Event projection must suppress input-required state only for "
							+ "the subscriber that lacks form elicitation support.");
		} finally {
			if (withoutForm != null)
				withoutForm.closeWithReset();
			if (withForm != null)
				withForm.closeWithReset();
			soklet.close();
		}
	}

	public void reconnectUsesNewIdAndDoesNotReplayDisconnectedEvents()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = server(taskManager, new AtomicInteger());
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient first = null;
		McpChunkedHttpClient second = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-missed", ALPHA);
			seedTask(port, "task-after-reconnect", ALPHA);

			first = listen(port, "\"before-disconnect\"", ALPHA, true,
					"{\"taskIds\":[\"task-missed\"]}");
			assertSseHead(first.readHead());
			Assertions.assertEquals(acknowledgment("\"before-disconnect\"",
					List.of("task-missed")), first.readChunkText());
			first.closeWithReset();
			first = null;
			awaitActiveSubscriptions(server, 0);

			taskManager.replaceTask(completedTask(
					taskManager.requireTask("task-missed")));
			taskManager.publishTaskChanged("task-missed");

			second = listen(port, "\"after-disconnect\"", ALPHA, true,
					"{\"taskIds\":[\"task-missed\","
							+ "\"task-after-reconnect\"]}");
			assertSseHead(second.readHead());
			Assertions.assertEquals(acknowledgment("\"after-disconnect\"",
					List.of("task-missed", "task-after-reconnect")),
					second.readChunkText());

			taskManager.publishTaskChanged("task-after-reconnect");
			Assertions.assertEquals(workingNotification("\"after-disconnect\"",
					"task-after-reconnect"), second.readChunkText(),
					"A disconnected event must not replay onto a later subscription.");
			taskManager.publishTaskChanged("task-missed");
			Assertions.assertEquals(completedNotification("\"after-disconnect\"",
					"task-missed"), second.readChunkText(),
					"A new event must use the replacement subscription ID.");
		} finally {
			if (first != null)
				first.closeWithReset();
			if (second != null)
				second.closeWithReset();
			soklet.close();
		}
	}

	public void duplicateEventsReadCurrentTerminalAndSuppressLaterStates()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = server(taskManager, new AtomicInteger());
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-race", ALPHA);
			seedTask(port, "task-race-control", ALPHA);

			client = listen(port, "\"terminal-race\"", ALPHA, true,
					"{\"taskIds\":[\"task-race\",\"task-race-control\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"terminal-race\"",
					List.of("task-race", "task-race-control")),
					client.readChunkText());
			taskManager.resetFindInvocations();

			taskManager.blockNextFindBeforeSnapshot("task-race");
			taskManager.publishTaskChanged("task-race");
			taskManager.awaitBlockedFind();
			taskManager.replaceTask(completedTask(
					taskManager.requireTask("task-race")));
			for (int duplicate = 0; duplicate < 5; duplicate++)
					taskManager.publishTaskChanged("task-race");
			taskManager.releaseBlockedFind();

			Assertions.assertEquals(completedNotification("\"terminal-race\"",
					"task-race"), client.readChunkText(),
					"Projection must read the authoritative state after the event, "
							+ "not a stale pre-update snapshot.");
			taskManager.awaitFindCompletions("task-race", 2);
			Assertions.assertEquals(2,
					taskManager.findInvocations("task-race"),
					"A duplicate burst must converge to one follow-up projection.");

			taskManager.replaceTask(canceledTask(
					taskManager.requireTask("task-race")));
			taskManager.publishTaskChanged("task-race");
			taskManager.awaitFindCompletions("task-race", 3);
			taskManager.replaceTask(regressedWorkingTask(
					taskManager.requireTask("task-race")));
			taskManager.publishTaskChanged("task-race");
			taskManager.awaitFindCompletions("task-race", 4);
			taskManager.publishTaskChanged("task-race-control");
			Assertions.assertEquals(workingNotification("\"terminal-race\"",
					"task-race-control"), client.readChunkText(),
					"Neither a different terminal status nor a nonterminal regression "
							+ "may follow the first delivered terminal snapshot.");
		} finally {
			taskManager.releaseBlockedFind();
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	public void maximumTaskIdStormCoalescesWithoutClosingItsSubscription()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		TaskStreamCloseMetrics metrics = new TaskStreamCloseMetrics();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.maximumSubscriptionsPerPartition(1)
				.build();
		Soklet soklet = managedSoklet(server, metrics);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-storm-origin", ALPHA);
			McpTaskOrigin origin = taskManager
					.requireTask("task-storm-origin").getTaskOrigin();
			List<String> taskIds = new ArrayList<>();
			for (int index = 0; index < 256; index++) {
				String taskId = "task-storm-" + index;
				taskIds.add(taskId);
				Map<String, McpJsonValue> persisted = new LinkedHashMap<>(
						origin.getPersistedState().getMembers());
				persisted.put("rawArguments", McpJsonObject.builder()
						.put("private", "PRIVATE-ORIGIN-" + index
								+ "x".repeat(128 * 1024)).build());
				taskManager.putTask(workingTask(taskId,
						McpTaskOrigin.fromPersistedState(
								McpJsonObject.fromMembers(persisted))),
						"authorization-" + ALPHA);
			}

			client = listen(port, "\"bounded-storm\"", ALPHA, true,
					taskIdsFilter(taskIds));
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"bounded-storm\"", taskIds),
					client.readChunkText());
			taskManager.resetFindInvocations();

			taskManager.blockTaskFindsAfterSnapshot(Set.of(taskIds.get(0)));
			taskManager.publishTaskChanged(taskIds.get(0));
			taskManager.awaitBlockedTaskFinds();
			for (String taskId : taskIds.subList(1, taskIds.size()))
				taskManager.publishTaskChanged(taskId);

			assertDiscoverCompletes(port);
			Assertions.assertEquals(1, taskManager.findInvocations(),
					"One subscription must occupy only one projection worker and "
							+ "one shared scheduler slot.");
			Assertions.assertEquals(1,
					server.getDiagnostics().getActiveSubscriptions());
			metrics.assertOpen();

			taskManager.releaseBlockedTaskFinds();
			for (String taskId : taskIds)
				Assertions.assertEquals(workingNotification("\"bounded-storm\"",
						taskId), client.readChunkText(),
						"Unique task IDs must retain first-event order.");
			Assertions.assertEquals(taskIds.size(), taskManager.findInvocations());
			Assertions.assertEquals(1,
					server.getDiagnostics().getActiveSubscriptions());
			metrics.assertOpen();

			for (String taskId : taskIds) {
				String output = "PRIVATE-RESULT-" + taskId + "y".repeat(64 * 1024);
				taskManager.replaceTask(completedTask(
						taskManager.requireTask(taskId), output));
				taskManager.publishTaskChanged(taskId);
				Assertions.assertTrue(client.readChunkText().contains(output),
						"The completed output must be delivered before it is released.");
			}

			client.closeWithReset();
			client = null;
			awaitActiveSubscriptions(server, 0);
		} finally {
			taskManager.releaseBlockedTaskFinds();
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	public void disconnectDuringActiveProjectionReleasesSubscriptionAndRecovers()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.maximumSubscriptionsPerPartition(1)
				.build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-disconnect-active", ALPHA);
			seedTask(port, "task-disconnect-recovered", ALPHA);
			client = listen(port, "\"disconnect-active\"", ALPHA, true,
					"{\"taskIds\":[\"task-disconnect-active\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"disconnect-active\"",
					List.of("task-disconnect-active")), client.readChunkText());
			taskManager.publishTaskChanged("task-disconnect-active");
			Assertions.assertEquals(workingNotification("\"disconnect-active\"",
					"task-disconnect-active"), client.readChunkText());
			taskManager.resetFindInvocations();

			taskManager.blockTaskFindsAfterSnapshot(
					Set.of("task-disconnect-active"));
			taskManager.publishTaskChanged("task-disconnect-active");
			taskManager.awaitBlockedTaskFinds();
			client.closeWithReset();
			client = null;
			awaitActiveSubscriptions(server, 0);

			taskManager.releaseBlockedTaskFinds();
			taskManager.awaitFindCompletions("task-disconnect-active", 1);
			assertDiscoverCompletes(port);
			awaitRecoveredTaskSubscription(port, "task-disconnect-recovered");
		} finally {
			taskManager.releaseBlockedTaskFinds();
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	private void cancelAcknowledgesAndPublishesTerminalSnapshot() throws Exception {
		ScriptedTaskManager manager = new ScriptedTaskManager();
		McpServer server = server(manager, new AtomicInteger());
		try (Soklet owner = managedSoklet(server)) {
			owner.start();
			int port = boundPort(server);
			seedTask(port, "task-cancel", ALPHA);
			try (McpChunkedHttpClient subscription = listen(port, "\"cancel-stream\"",
					ALPHA, true, "{\"taskIds\":[\"task-cancel\"]}")) {
				assertSseHead(subscription.readHead());
				Assertions.assertEquals(acknowledgment("\"cancel-stream\"",
						List.of("task-cancel")), subscription.readChunkText());
				String body = "{\"jsonrpc\":\"2.0\",\"id\":\"cancel\","
						+ "\"method\":\"tasks/cancel\",\"params\":{"
						+ taskMetadata(true) + ",\"taskId\":\"task-cancel\"}}";
				try (McpChunkedHttpClient cancellation = McpChunkedHttpClient.postMcpMessage(
						port, body, List.of(
								new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", PROTOCOL_VERSION),
								new McpChunkedHttpClient.RequestHeader("Mcp-Method", "tasks/cancel"),
								new McpChunkedHttpClient.RequestHeader("Mcp-Name", "task-cancel"),
								new McpChunkedHttpClient.RequestHeader("X-Test-Tenant", ALPHA)))) {
					McpChunkedHttpClient.HttpResponseHead head = cancellation.readHead();
					Assertions.assertEquals(200, head.status());
					Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"cancel\",\"result\":{\"resultType\":\"complete\","
							+ "\"_meta\":{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"task-subscription-public-runtime-test\",\"version\":\"4.0.0\"}}}}",
							cancellation.readFixedBody(head));
				}
				String event = subscription.readChunkText();
				Assertions.assertTrue(event.contains("\"method\":\"notifications/tasks\""));
				Assertions.assertTrue(event.contains("\"taskId\":\"task-cancel\""));
				Assertions.assertTrue(event.contains("\"status\":\"cancelled\""), event);
				Assertions.assertTrue(event.contains("\"io.modelcontextprotocol/subscriptionId\":\"cancel-stream\""));
				subscription.closeWithReset();
			}
			awaitActiveSubscriptions(server, 0);
		}
		Assertions.assertEquals(0, server.getDiagnostics().getActiveSubscriptions());
	}

	private static McpServer server(ScriptedTaskManager taskManager,
			AtomicInteger admissions) {
		return serverBuilder(taskManager, admissions).build();
	}

	private static McpServer server(
			List<McpEndpoint> endpoints,
			ScriptedTaskManager taskManager,
			AtomicInteger admissions) {
		return serverBuilder(endpoints, taskManager, admissions).build();
	}

	private static McpServer.Builder serverBuilder(
			ScriptedTaskManager taskManager,
			AtomicInteger admissions) {
		return serverBuilder(List.of(taskEndpoint(MCP_PATH, taskManager)),
				taskManager, admissions);
	}

	private static McpServer.Builder serverBuilder(
			List<McpEndpoint> endpoints,
			ScriptedTaskManager taskManager,
			AtomicInteger admissions) {
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.subscriptionAuthorizer((context, features) ->
						McpSubscriptionAuthorization.Allowed.fromValidUntil(
								Instant.now().plusSeconds(30)))
				.admissionController(context -> {
					admissions.incrementAndGet();
					String tenant = context.getRequest()
							.getHeader("X-Test-Tenant").orElse(ALPHA);
					return McpAdmissionDecision.accepted(McpAdmissionIdentity
							.withRateLimitPartitionKey("rate-" + tenant)
							.authorizationPartitionKey(
									"authorization-" + tenant)
							.principal(tenant)
							.build());
				})
				.host(LOOPBACK)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.taskManager(taskManager)
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	private static McpEndpoint taskEndpoint(String path,
			ScriptedTaskManager taskManager) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonObjectArguments()
				.handler((request, arguments, features) -> {
					McpJsonValue taskIdValue = arguments.getRawArguments()
							.getMembers().get("taskId");
					if (!(taskIdValue instanceof McpJsonString taskId))
						throw new IllegalArgumentException("taskId must be a string");
					McpTaskOrigin taskOrigin = features.getTaskControl()
							.orElseThrow().getTaskOrigin();
					String authorizationPartition = request.getAdmissionIdentity()
							.getAuthorizationPartitionKey().orElseThrow();
					taskManager.putTask(workingTask(taskId.getValue(), taskOrigin),
							request.getEndpoint().getPath(), authorizationPartition);
					return McpTaskCreatedResult
							.<McpJsonObject>fromTaskId(taskId.getValue());
				})
				.addInputRequestDeclaration(FORM_DECLARATION)
				.structuredContentMirroredAsText(false)
				.build();
		return McpEndpoint.withPath(path,
				McpImplementation.withNameAndVersion(
						"task-subscription-public-runtime-test", "4.0.0")
						.build())
				.addTool(tool)
				.build();
	}

	private static Soklet managedSoklet(McpServer server) {
		return managedSoklet(server, MetricsCollector.disabledInstance());
	}

	private static Soklet managedSoklet(McpServer server,
			MetricsCollector metricsCollector) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector)
				.build());
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static void seedTask(int port, String taskId,
			String tenant) throws Exception {
		String requestId = "seed-" + taskId;
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"tools/call\",\"params\":{"
				+ taskMetadata(true) + ",\"name\":\"" + TOOL_NAME
				+ "\",\"arguments\":{\"taskId\":\"" + taskId + "\"}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", TOOL_NAME)
				.header("X-Test-Tenant", tenant)
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertTrue(response.body().contains("\"taskId\":\""
				+ taskId + "\""), response.body());
	}

	private static void assertDiscoverCompletes(int port) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"storm-control\","
				+ "\"method\":\"server/discover\",\"params\":{"
				+ taskMetadata(false) + "}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "server/discover")
				.POST(HttpRequest.BodyPublishers.ofString(body,
						StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"storm-control\""), response.body());
	}

	private static McpChunkedHttpClient listen(int port, String idJson,
			String tenant, boolean tasksCapable,
			String notificationsJson) throws Exception {
		return listen(port, idJson, tenant, tasksCapable, false,
				notificationsJson, 0);
	}

	private static McpChunkedHttpClient listen(int port, String idJson,
			String tenant, boolean tasksCapable, boolean formCapable,
			String notificationsJson) throws Exception {
		return listen(port, idJson, tenant, tasksCapable, formCapable,
				notificationsJson, 0);
	}

	private static McpChunkedHttpClient listen(int port, String idJson,
			String tenant, boolean tasksCapable,
			String notificationsJson, int receiveBufferBytes)
			throws Exception {
		return listen(port, idJson, tenant, tasksCapable, false,
				notificationsJson, receiveBufferBytes);
	}

	private static McpChunkedHttpClient listen(int port, String idJson,
			String tenant, boolean tasksCapable, boolean formCapable,
			String notificationsJson, int receiveBufferBytes)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"subscriptions/listen\",\"params\":{"
				+ taskMetadata(tasksCapable, formCapable)
				+ ",\"notifications\":"
				+ notificationsJson + "}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen"),
				new McpChunkedHttpClient.RequestHeader(
						"X-Test-Tenant", tenant)), receiveBufferBytes);
	}

	private static String taskMetadata(boolean tasksCapable) {
		return taskMetadata(tasksCapable, false);
	}

	private static String taskMetadata(boolean tasksCapable,
			boolean formCapable) {
		String capabilities = tasksCapable
				? "{\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}"
						+ (formCapable ? ",\"elicitation\":{\"form\":{}}" : "") + "}"
				: (formCapable ? "{\"elicitation\":{\"form\":{}}}" : "{}");
		return "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ capabilities + "}";
	}

	private static void assertSseHead(
			McpChunkedHttpClient.HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store",
				head.singleHeader("Cache-Control"));
		Assertions.assertEquals("no",
				head.singleHeader("X-Accel-Buffering"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
		Assertions.assertFalse(head.hasHeader("Content-Length"));
		Assertions.assertFalse(head.hasHeader("Last-Event-ID"));
	}

	private static String acknowledgment(String subscriptionIdJson,
			List<String> taskIds) {
		List<String> encodedTaskIds = new ArrayList<>();
		for (String taskId : taskIds)
			encodedTaskIds.add("\"" + taskId + "\"");
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/subscriptions/acknowledged\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "},\"notifications\":{"
				+ "\"taskIds\":[" + String.join(",", encodedTaskIds)
				+ "]}}}");
	}

	private static String taskIdsFilter(
			List<String> taskIds) {
		List<String> encodedTaskIds = new ArrayList<>();
		for (String taskId : taskIds)
			encodedTaskIds.add("\"" + taskId + "\"");
		return "{\"taskIds\":[" + String.join(",", encodedTaskIds) + "]}";
	}

	private static String workingNotification(String subscriptionIdJson,
			String taskId) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/tasks\",\"params\":{"
				+ "\"taskId\":\"" + taskId + "\","
				+ "\"status\":\"working\",\"statusMessage\":"
				+ "\"working-message\",\"createdAt\":\"" + CREATED_AT
				+ "\",\"lastUpdatedAt\":\"" + WORKING_UPDATED_AT
				+ "\",\"ttlMs\":60000,\"pollIntervalMs\":250,"
				+ "\"_meta\":{\"com.example/task-metadata\":\"working\","
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "}}}");
	}

	private static String completedNotification(String subscriptionIdJson,
			String taskId) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/tasks\",\"params\":{"
				+ "\"taskId\":\"" + taskId + "\","
				+ "\"status\":\"completed\",\"statusMessage\":"
				+ "\"completed-message\",\"createdAt\":\"" + CREATED_AT
				+ "\",\"lastUpdatedAt\":\"" + COMPLETED_UPDATED_AT
				+ "\",\"ttlMs\":60000,\"pollIntervalMs\":250,"
				+ "\"result\":{\"content\":[{\"type\":\"text\","
				+ "\"text\":\"completed-output\"}],"
				+ "\"resultType\":\"complete\",\"_meta\":{"
				+ "\"com.example/completed\":\"nested\"}},"
				+ "\"_meta\":{\"com.example/task-metadata\":\"completed\","
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "}}}");
	}

	private static String inputRequiredNotification(String subscriptionIdJson,
			String taskId) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/tasks\",\"params\":{"
				+ "\"taskId\":\"" + taskId + "\","
				+ "\"status\":\"input_required\",\"statusMessage\":"
				+ "\"input-required-message\",\"createdAt\":\"" + CREATED_AT
				+ "\",\"lastUpdatedAt\":\"" + INPUT_REQUIRED_UPDATED_AT
				+ "\",\"ttlMs\":60000,\"pollIntervalMs\":250,"
				+ "\"inputRequests\":{\"approval\":{\"method\":"
				+ "\"elicitation/create\",\"params\":{\"message\":\"Approve task?\","
				+ "\"mode\":\"form\",\"requestedSchema\":{\"type\":\"object\","
				+ "\"properties\":{}}}}},"
				+ "\"_meta\":{\"com.example/task-metadata\":"
				+ "\"input-required\","
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "}}}");
	}

	private static String sse(String json) {
		return "data: " + json + "\n\n";
	}

	private static McpTask workingTask(String taskId,
			McpTaskOrigin taskOrigin) {
		return McpTask.withTaskId(taskId, taskOrigin, McpTaskStatus.WORKING,
					CREATED_AT, WORKING_UPDATED_AT)
				.taskStatusMessage("working-message")
				.timeToLive(TASK_TIME_TO_LIVE)
				.pollInterval(POLL_INTERVAL)
				.metadata(McpJsonObject.builder()
						.put("com.example/task-metadata", "working")
						.build())
				.build();
	}

	private static McpTask completedTask(McpTask previous) {
		return completedTask(previous, "completed-output");
	}

	private static McpTask completedTask(McpTask previous,
			String output) {
		return McpTask.withTaskId(previous.getTaskId(), previous.getTaskOrigin(),
					McpTaskStatus.COMPLETED, CREATED_AT, COMPLETED_UPDATED_AT)
				.taskStatusMessage("completed-message")
				.timeToLive(TASK_TIME_TO_LIVE)
				.pollInterval(POLL_INTERVAL)
				.completedResult(McpCompleteResult
						.fromToolText(output)
						.toBuilder().metadata(McpJsonObject.builder()
								.put("com.example/completed", "nested")
								.build()).build())
				.metadata(McpJsonObject.builder()
						.put("com.example/task-metadata", "completed")
						.build())
				.build();
	}

	private static McpTask canceledTask(McpTask previous) {
		return McpTask.withTaskId(previous.getTaskId(), previous.getTaskOrigin(),
					McpTaskStatus.CANCELED, CREATED_AT, CANCELED_UPDATED_AT)
				.taskStatusMessage("canceled-message")
				.timeToLive(TASK_TIME_TO_LIVE)
				.pollInterval(POLL_INTERVAL)
				.metadata(McpJsonObject.builder()
						.put("com.example/task-metadata", "canceled")
						.build())
				.build();
	}

	private static McpTask inputRequiredTask(McpTask previous) {
		return McpTask.withTaskId(previous.getTaskId(), previous.getTaskOrigin(),
					McpTaskStatus.INPUT_REQUIRED, CREATED_AT,
					INPUT_REQUIRED_UPDATED_AT)
				.taskStatusMessage("input-required-message")
				.timeToLive(TASK_TIME_TO_LIVE)
				.pollInterval(POLL_INTERVAL)
				.addInputRequest("approval", McpInputRequest.fromDeclaration(
						FORM_DECLARATION, FORM_PARAMETERS))
				.metadata(McpJsonObject.builder()
						.put("com.example/task-metadata", "input-required")
						.build())
				.build();
	}

	private static McpTask regressedWorkingTask(McpTask previous) {
		return McpTask.withTaskId(previous.getTaskId(), previous.getTaskOrigin(),
					McpTaskStatus.WORKING, CREATED_AT, REGRESSED_UPDATED_AT)
				.taskStatusMessage("regressed-after-terminal")
				.timeToLive(TASK_TIME_TO_LIVE)
				.pollInterval(POLL_INTERVAL)
				.build();
	}

	private static void awaitRecoveredTaskSubscription(int port,
			String taskId) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			McpChunkedHttpClient client = listen(port,
					"\"recovered-after-backpressure\"", ALPHA, true,
					"{\"taskIds\":[\"" + taskId + "\"]}");
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			if (head.status() == 200) {
				try (client) {
					assertSseHead(head);
					Assertions.assertEquals(acknowledgment(
							"\"recovered-after-backpressure\"",
							List.of(taskId)), client.readChunkText());
				}
				return;
			}

			try (client) {
				Assertions.assertEquals(503, head.status(), head.raw());
				client.readFixedBody(head);
			}
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError("The task-subscription capacity did not "
						+ "recover after backpressure closed the stream.");
			Thread.sleep(10L);
		}
	}

	private static void awaitActiveSubscriptions(McpServer server,
			int expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (server.getDiagnostics().getActiveSubscriptions() != expected) {
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError("Timed out waiting for " + expected
						+ " active MCP subscriptions.");
			Thread.sleep(10L);
		}
	}

	private static final class TaskStreamCloseMetrics
			implements MetricsCollector {
		private final AtomicReference<McpStreamTerminationReason>
				requestStreamReason = new AtomicReference<>();
		private final AtomicReference<McpStreamTerminationReason>
				subscriptionReason = new AtomicReference<>();
		private final CountDownLatch subscriptionClosed = new CountDownLatch(1);

		@Override
		public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
			if (event instanceof McpMetricsEvent.RequestStreamClosed closed)
				this.requestStreamReason.compareAndSet(null, closed.getReason());
			else if (event instanceof McpMetricsEvent.SubscriptionClosed closed) {
				this.subscriptionReason.compareAndSet(null, closed.getReason());
				this.subscriptionClosed.countDown();
			}
		}

		private void awaitBackpressureClose() throws InterruptedException {
			Assertions.assertTrue(this.subscriptionClosed.await(5,
					TimeUnit.SECONDS),
					"The oversized task notification did not close its subscription.");
			Assertions.assertSame(McpStreamTerminationReason.BACKPRESSURE,
					this.requestStreamReason.get());
			Assertions.assertSame(McpStreamTerminationReason.BACKPRESSURE,
					this.subscriptionReason.get());
		}

		private void assertOpen() {
			Assertions.assertEquals(1L, this.subscriptionClosed.getCount());
			Assertions.assertNull(this.requestStreamReason.get());
			Assertions.assertNull(this.subscriptionReason.get());
		}
	}

	private static final class ScriptedTaskManager implements McpTaskManager {
		private final Map<String, Entry> entries =
				new ConcurrentHashMap<>();
		private final Set<String> revokedTaskIds =
				ConcurrentHashMap.newKeySet();
		private final Set<String> failNextFindTaskIds =
				ConcurrentHashMap.newKeySet();
		private final McpTaskEventPublisher taskEventPublisher;
		private final AtomicInteger findInvocations = new AtomicInteger();
		private final Map<String, AtomicInteger>
				findInvocationsByTask = new ConcurrentHashMap<>();
		private final Map<String, AtomicInteger>
				findCompletionsByTask = new ConcurrentHashMap<>();
		private final CountDownLatch revokedLookup = new CountDownLatch(1);
		private final Object findBlockLock = new Object();
		private FindBlock findBlock;
		private TaskFindBlock taskFindBlock;

		private ScriptedTaskManager() {
			this(McpTaskEventPublisher.fromInMemoryDefaults());
		}

		private ScriptedTaskManager(boolean taskNotificationsEnabled) {
			this.taskEventPublisher = taskNotificationsEnabled
					? McpTaskEventPublisher.fromInMemoryDefaults() : null;
		}

		private ScriptedTaskManager(
				McpTaskEventPublisher taskEventPublisher) {
			this.taskEventPublisher = taskEventPublisher;
		}

		@Override
		public Optional<McpTaskEventPublisher> getTaskEventPublisher() {
			return Optional.ofNullable(this.taskEventPublisher);
		}

		@Override
		public Optional<McpTask> findTask(
				McpTaskRequestContext context) {
			this.findInvocations.incrementAndGet();
			String taskId = context.getTaskId();
			this.findInvocationsByTask.computeIfAbsent(taskId,
					ignored -> new AtomicInteger()).incrementAndGet();
			try {
				if (this.failNextFindTaskIds.remove(taskId))
					throw new IllegalStateException(
							"Synthetic transient task lookup failure.");
				awaitFindBlockBeforeSnapshot(taskId);
				Entry entry = this.entries.get(taskId);
				awaitTaskFindBlockAfterSnapshot(taskId);
				if (entry == null)
					return Optional.empty();
				if (this.revokedTaskIds.contains(taskId)) {
					this.revokedLookup.countDown();
					return Optional.empty();
				}
				McpRequestContext requestContext = context.getRequestContext();
				String authorizationPartition = requestContext
						.getAdmissionIdentity()
						.getAuthorizationPartitionKey().orElseThrow();
				if (!entry.authorizationPartition().equals(authorizationPartition)
						|| !entry.endpointPath().equals(
								requestContext.getEndpoint().getPath()))
					return Optional.empty();
				return Optional.of(entry.task());
			} finally {
				this.findCompletionsByTask.computeIfAbsent(taskId,
						ignored -> new AtomicInteger()).incrementAndGet();
			}
		}

		@Override
		public void updateTask(McpTaskUpdateContext context)
				throws McpTaskNotFoundException {
			throw new McpTaskNotFoundException();
		}

		@Override
		public void requestTaskCancelation(
				McpTaskRequestContext context)
				throws McpTaskNotFoundException {
			McpTask task = findTask(context).orElseThrow(McpTaskNotFoundException::new);
			replaceTask(canceledTask(task));
			publishTaskChanged(context.getTaskId());
		}

		private void putTask(McpTask task,
				String authorizationPartition) {
			putTask(task, MCP_PATH, authorizationPartition);
		}

		private void putTask(McpTask task,
				String endpointPath,
				String authorizationPartition) {
			this.entries.put(task.getTaskId(), new Entry(task,
					endpointPath, authorizationPartition));
		}

		private void replaceTask(McpTask task) {
			this.entries.compute(task.getTaskId(), (taskId, existing) -> {
				if (existing == null)
					throw new IllegalStateException("Unknown test task: " + taskId);
				return new Entry(task, existing.endpointPath(),
						existing.authorizationPartition());
			});
		}

		private McpTask requireTask(String taskId) {
			Entry entry = this.entries.get(taskId);
			if (entry == null)
				throw new IllegalStateException("Unknown test task: " + taskId);
			return entry.task();
		}

		private void revoke(String taskId) {
			this.revokedTaskIds.add(taskId);
		}

		private void failNextFind(String taskId) {
			if (!this.failNextFindTaskIds.add(taskId))
				throw new IllegalStateException(
						"A test lookup failure is already armed for " + taskId + '.');
		}

		private void publishTaskChanged(String taskId) {
			McpTaskEventPublisher publisher = this.taskEventPublisher;
			if (publisher == null)
				throw new IllegalStateException(
						"Task notifications are disabled for this test manager.");
			publisher.publishTaskChanged(taskId);
		}

		private void awaitRevokedLookup() throws InterruptedException {
			Assertions.assertTrue(this.revokedLookup.await(5, TimeUnit.SECONDS),
					"Task notification did not perform a fresh authorization lookup.");
		}

		private void blockNextFindBeforeSnapshot(String taskId) {
			blockNextFindBeforeSnapshot(taskId, true);
		}

		private void blockNextFindBeforeSnapshotIgnoringInterrupts(
				String taskId) {
			blockNextFindBeforeSnapshot(taskId, false);
		}

		private void blockNextFindBeforeSnapshot(String taskId,
				boolean interruptionResponsive) {
			synchronized (this.findBlockLock) {
				if (this.findBlock != null)
					throw new IllegalStateException(
							"A test task lookup is already blocked.");
				this.findBlock = new FindBlock(taskId,
						interruptionResponsive);
			}
		}

		private void awaitBlockedFind() throws InterruptedException {
			FindBlock block;
			synchronized (this.findBlockLock) {
				block = this.findBlock;
			}
			if (block == null)
				throw new AssertionError("No test task lookup was armed to block.");
			Assertions.assertTrue(block.started().await(5, TimeUnit.SECONDS),
					"The task-notification lookup did not reach the race boundary.");
		}

		private void releaseBlockedFind() {
			FindBlock block;
			synchronized (this.findBlockLock) {
				block = this.findBlock;
				this.findBlock = null;
			}
			if (block != null)
				block.release().countDown();
		}

		private void blockTaskFindsAfterSnapshot(
				Set<String> taskIds) {
			synchronized (this.findBlockLock) {
				if (this.findBlock != null || this.taskFindBlock != null)
					throw new IllegalStateException(
							"A test task lookup is already blocked.");
				this.taskFindBlock = new TaskFindBlock(taskIds);
			}
		}

		private void awaitBlockedTaskFinds() throws InterruptedException {
			TaskFindBlock block;
			synchronized (this.findBlockLock) {
				block = this.taskFindBlock;
			}
			if (block == null)
				throw new AssertionError(
						"No group of test task lookups was armed to block.");
			Assertions.assertTrue(block.started().await(5, TimeUnit.SECONDS),
					"The bounded task-notification workers did not all reach the "
							+ "test boundary.");
		}

		private void releaseBlockedTaskFinds() {
			TaskFindBlock block;
			synchronized (this.findBlockLock) {
				block = this.taskFindBlock;
				this.taskFindBlock = null;
			}
			if (block != null)
				block.release().countDown();
		}

		private void awaitFindBlockBeforeSnapshot(String taskId) {
			FindBlock block = null;
			synchronized (this.findBlockLock) {
				if (this.findBlock != null && !this.findBlock.claimed()
						&& this.findBlock.taskId().equals(taskId)) {
					this.findBlock.claim();
					block = this.findBlock;
				}
			}
			if (block == null)
				return;
			block.started().countDown();
			boolean released = false;
			while (!released)
				try {
					released = block.release().await(25,
							TimeUnit.MILLISECONDS);
				} catch (InterruptedException exception) {
					if (block.interruptionResponsive()) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(
								"Interrupted at the task-notification race boundary.",
								exception);
					}
					// Deliberately model application storage that ignores cancelation.
				}
		}

		private void awaitTaskFindBlockAfterSnapshot(String taskId) {
			TaskFindBlock block = null;
			synchronized (this.findBlockLock) {
				if (this.taskFindBlock != null
						&& this.taskFindBlock.claim(taskId))
					block = this.taskFindBlock;
			}
			if (block == null)
				return;
			block.started().countDown();
			try {
				block.release().await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
						"Interrupted at the task-notification storm boundary.",
						exception);
			}
		}

		private void awaitFindCompletions(String taskId, int expected)
				throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (findCompletions(taskId) < expected) {
				if (System.nanoTime() - deadline >= 0L)
					throw new AssertionError("Timed out waiting for " + expected
							+ " completed lookups for " + taskId + '.');
				Thread.sleep(10L);
			}
		}

		private int findInvocations() {
			return this.findInvocations.get();
		}

		private int findInvocations(String taskId) {
			AtomicInteger invocations = this.findInvocationsByTask.get(taskId);
			return invocations == null ? 0 : invocations.get();
		}

		private int findCompletions(String taskId) {
			AtomicInteger completions = this.findCompletionsByTask.get(taskId);
			return completions == null ? 0 : completions.get();
		}

		private void resetFindInvocations() {
			this.findInvocations.set(0);
			this.findInvocationsByTask.clear();
			this.findCompletionsByTask.clear();
		}
	}

	private static final class FindBlock {
		private final String taskId;
		private final CountDownLatch started;
		private final CountDownLatch release;
		private final boolean interruptionResponsive;
		private boolean claimed;

		private FindBlock(String taskId,
				boolean interruptionResponsive) {
			this.taskId = taskId;
			this.started = new CountDownLatch(1);
			this.release = new CountDownLatch(1);
			this.interruptionResponsive = interruptionResponsive;
		}

		private String taskId() {
			return this.taskId;
		}

		private CountDownLatch started() {
			return this.started;
		}

		private CountDownLatch release() {
			return this.release;
		}

		private boolean interruptionResponsive() {
			return this.interruptionResponsive;
		}

		private boolean claimed() {
			return this.claimed;
		}

		private void claim() {
			this.claimed = true;
		}
	}

	private static final class TaskFindBlock {
		private final Set<String> unclaimedTaskIds;
		private final CountDownLatch started;
		private final CountDownLatch release;

		private TaskFindBlock(Set<String> taskIds) {
			if (taskIds.isEmpty())
				throw new IllegalArgumentException(
						"At least one task lookup must be blocked.");
			this.unclaimedTaskIds = new HashSet<>(taskIds);
			this.started = new CountDownLatch(this.unclaimedTaskIds.size());
			this.release = new CountDownLatch(1);
		}

		private boolean claim(String taskId) {
			return this.unclaimedTaskIds.remove(taskId);
		}

		private CountDownLatch started() {
			return this.started;
		}

		private CountDownLatch release() {
			return this.release;
		}
	}

	private static final class Entry {
		private final McpTask task;
		private final String endpointPath;
		private final String authorizationPartition;

		private Entry(McpTask task, String endpointPath,
				String authorizationPartition) {
			this.task = task;
			this.endpointPath = endpointPath;
			this.authorizationPartition = authorizationPartition;
		}

		private McpTask task() {
			return this.task;
		}

		private String endpointPath() {
			return this.endpointPath;
		}

		private String authorizationPartition() {
			return this.authorizationPartition;
		}
	}
	private static final class Assertions {
		private static void assertTrue(boolean value, String... message) {
			if (!value) throw new AssertionError(String.join(" ", message));
		}
		private static void assertFalse(boolean value, String... message) {
			assertTrue(!value, message);
		}
		private static void assertEquals(Object expected, Object actual, String... message) {
			boolean equal = expected instanceof Number left && actual instanceof Number right
					? left.doubleValue() == right.doubleValue() : java.util.Objects.equals(expected, actual);
			if (!equal) throw new AssertionError(String.join(" ", message)
					+ " Expected " + expected + " but found " + actual);
		}
		private static void assertNull(Object value) {
			assertEquals(null, value);
		}
		private static void assertSame(Object expected, Object actual) {
			assertTrue(expected == actual, "Object identity differs");
		}
	}
}
