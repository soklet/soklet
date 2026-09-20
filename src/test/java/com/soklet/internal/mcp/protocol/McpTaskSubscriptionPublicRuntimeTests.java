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

package com.soklet.internal.mcp.protocol;

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
import com.soklet.McpSubscriptionAuthorizationContext;
import com.soklet.McpSubscriptionAuthorizer;
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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * Black-box real-listener coverage for MCP task-status subscriptions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpTaskSubscriptionPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TASKS_EXTENSION_ID =
			"io.modelcontextprotocol/tasks";
	private static final String TOOL_NAME = "tasks.notifications.seed";
	private static final String ALPHA = "alpha";
	private static final String BETA = "beta";
	private static final McpInputRequestDeclaration ELICITATION_URL_DECLARATION =
			McpInputRequestDeclaration.fromElicitationUrl(McpInputRequirement.CONDITIONAL);
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

	@Test
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

	@Test
	public void taskAuthorizationUsesCredentialFreeReplacementContextAndCannotExpandOnReconciliation()
			throws Exception {
		String acceptedTaskId = "task-derived-context-accepted";
		String rejectedTaskId = "task-derived-context-rejected";
		String rateLimitPartition = "derived-context-rate";
		String authorizationPartition = "derived-context-authorization";
		Object principal = new Object();
		Object admissionApplicationContext = new Object();
		Object replacementApplicationContext = new Object();
		List<McpSubscriptionAuthorizationContext> authorizationContexts =
				new CopyOnWriteArrayList<>();
		CountDownLatch reconciliationAuthorization = new CountDownLatch(1);
		AtomicInteger authorizationInvocations = new AtomicInteger();
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			authorizationContexts.add(context);
			int invocation = authorizationInvocations.incrementAndGet();
			if (invocation == 1) {
				Assertions.assertSame(admissionApplicationContext,
						context.getApplicationContext().orElseThrow());
				Assertions.assertEquals(Set.of(acceptedTaskId, rejectedTaskId),
						context.getTaskIds());
			} else if (invocation == 2) {
				Assertions.assertSame(replacementApplicationContext,
						context.getApplicationContext().orElseThrow());
				Assertions.assertEquals(Set.of(acceptedTaskId),
						context.getTaskIds(),
						"Reconciliation must not reconsider a task ID omitted from the ACK.");
				reconciliationAuthorization.countDown();
			} else {
				throw new AssertionError(
						"Unexpected subscription authorization invocation " + invocation);
			}
			return McpSubscriptionAuthorization.Allowed
					.withValidUntil(Instant.now().plus(Duration.ofMinutes(5)))
					.applicationContext(replacementApplicationContext)
					.build();
		};
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.admissionController(context -> McpAdmissionDecision.accepted(
						McpAdmissionIdentity
								.withRateLimitPartitionKey(rateLimitPartition)
								.authorizationPartitionKey(authorizationPartition)
								.principal(principal)
								.applicationContext(admissionApplicationContext)
								.build()))
				.subscriptionAuthorizer(authorizer)
				.build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, acceptedTaskId, ALPHA);
			seedTask(port, rejectedTaskId, ALPHA);
			taskManager.putTask(taskManager.requireTask(rejectedTaskId),
					"another-authorization-partition");
			taskManager.resetFindInvocations();

			client = listenWithCredentialCanaries(port,
					"\"derived-task-context\"", acceptedTaskId, rejectedTaskId);
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"derived-task-context\"",
					List.of(acceptedTaskId)), client.readChunkText(),
					"The ACK must contain only task IDs accepted with the replacement context.");

			Assertions.assertEquals(1, authorizationInvocations.get());
			Assertions.assertEquals(1, authorizationContexts.size());
			McpRequestContext initial = authorizationContexts.get(0)
					.getInitialRequestContext();
			Assertions.assertEquals("Bearer original-credential",
					initial.getRequest().getHeader("Authorization").orElseThrow());
			Assertions.assertTrue(initial.getRequest().getBody().isPresent());
			Assertions.assertTrue(initial.getRequestMetadata()
					.find("authorizationTestMetadata").isPresent());
			Assertions.assertTrue(initial.getTraceContext().isPresent());
			Assertions.assertEquals(Map.of("credential", "metadata-secret"),
					initial.getBaggage());

			List<McpTaskRequestContext> initialTaskContexts =
					taskManager.findContexts();
			Assertions.assertEquals(2, initialTaskContexts.size());
			Assertions.assertEquals(Set.of(acceptedTaskId, rejectedTaskId),
					Set.of(initialTaskContexts.get(0).getTaskId(),
							initialTaskContexts.get(1).getTaskId()));
			McpRequestContext derived = initialTaskContexts.get(0)
					.getRequestContext();
			Assertions.assertSame(derived,
					initialTaskContexts.get(1).getRequestContext());
			Assertions.assertNotSame(initial, derived);
			Assertions.assertTrue(derived.getRequest().getHeaders().isEmpty());
			Assertions.assertTrue(derived.getRequest().getBody().isEmpty());
			Assertions.assertEquals(McpJsonObject.emptyInstance(),
					derived.getRequestMetadata());
			Assertions.assertTrue(derived.getFrameworkRequestState().isEmpty());
			Assertions.assertTrue(derived.getApplicationRequestState().isEmpty());
			Assertions.assertTrue(derived.getTraceContext().isEmpty());
			Assertions.assertTrue(derived.getRequest().getTraceContext().isEmpty());
			Assertions.assertTrue(derived.getBaggage().isEmpty());
			Assertions.assertTrue(derived.getRequestId().isEmpty());
			McpAdmissionIdentity derivedIdentity = derived.getAdmissionIdentity();
			Assertions.assertSame(principal,
					derivedIdentity.getPrincipal().orElseThrow());
			Assertions.assertSame(replacementApplicationContext,
					derivedIdentity.getApplicationContext().orElseThrow());
			Assertions.assertEquals(rateLimitPartition,
					derivedIdentity.getRateLimitPartitionKey());
			Assertions.assertEquals(Optional.of(authorizationPartition),
					derivedIdentity.getAuthorizationPartitionKey());

			taskManager.resetFindInvocations();
			server.getSubscriptionReconciler().reconcileSubscriptions();
			Assertions.assertTrue(reconciliationAuthorization.await(5,
					TimeUnit.SECONDS),
					"Reconciliation did not perform a fresh authorization check.");
			taskManager.awaitFindCompletions(acceptedTaskId, 2);
			Assertions.assertEquals(2,
					taskManager.findInvocations(acceptedTaskId));
			Assertions.assertEquals(0,
					taskManager.findInvocations(rejectedTaskId),
					"A task omitted from the ACK must not be added on renewal.");
			Assertions.assertEquals(2, taskManager.findContexts().size());
			for (McpTaskRequestContext context : taskManager.findContexts())
				Assertions.assertSame(replacementApplicationContext,
						context.getRequestContext().getAdmissionIdentity()
								.getApplicationContext().orElseThrow());
		} finally {
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	@Test
	public void reconciliationFencesOldTaskContextAndDropsOnlyRevokedQueuedTask()
			throws Exception {
		String revokedTaskId = "task-revision-revoked";
		String retainedTaskId = "task-revision-retained";
		Object firstContext = new Object();
		Object replacementContext = new Object();
		AtomicInteger authorizations = new AtomicInteger();
		CountDownLatch reconciliationAuthorization = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int invocation = authorizations.incrementAndGet();
			Object applicationContext;
			if (invocation == 1) {
				applicationContext = firstContext;
			} else if (invocation == 2) {
				applicationContext = replacementContext;
				reconciliationAuthorization.countDown();
			} else {
				throw new AssertionError(
						"Unexpected authorization invocation " + invocation);
			}
			return McpSubscriptionAuthorization.Allowed
					.withValidUntil(Instant.now().plus(Duration.ofMinutes(5)))
					.applicationContext(applicationContext)
					.build();
		};
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.subscriptionAuthorizer(authorizer)
				.build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, revokedTaskId, ALPHA);
			seedTask(port, retainedTaskId, ALPHA);
			client = listen(port, "\"task-revision-fence\"", ALPHA, true,
					"{\"taskIds\":[\"" + revokedTaskId + "\",\""
							+ retainedTaskId + "\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"task-revision-fence\"",
					List.of(revokedTaskId, retainedTaskId)), client.readChunkText());

			taskManager.resetFindInvocations();
			taskManager.blockTaskFindsAfterSnapshot(Set.of(revokedTaskId));
			taskManager.publishTaskChanged(revokedTaskId);
			taskManager.awaitBlockedTaskFinds();
			taskManager.publishTaskChanged(retainedTaskId);
			taskManager.revokeForApplicationContext(revokedTaskId,
					replacementContext);

			server.getSubscriptionReconciler().reconcileSubscriptions();
			Assertions.assertTrue(reconciliationAuthorization.await(5,
					TimeUnit.SECONDS));
			taskManager.awaitFindCompletions(retainedTaskId, 1);
			taskManager.releaseBlockedTaskFinds();

			Assertions.assertEquals(workingNotification("\"task-revision-fence\"",
					retainedTaskId), client.readChunkText(),
					"Only the retained task may be projected under fresh authorization.");
			taskManager.awaitFindCompletions(revokedTaskId, 2);
			taskManager.awaitFindCompletions(retainedTaskId, 2);
			Assertions.assertEquals(2,
					taskManager.findInvocations(revokedTaskId),
					"The revoked queued task ran after fresh authorization.");
			Assertions.assertEquals(2,
					taskManager.findInvocations(retainedTaskId));

			List<McpTaskRequestContext> revokedContexts = taskManager.findContexts()
					.stream().filter(context -> revokedTaskId.equals(context.getTaskId()))
					.toList();
			Assertions.assertEquals(2, revokedContexts.size());
			Assertions.assertSame(firstContext, revokedContexts.get(0)
					.getRequestContext().getAdmissionIdentity()
					.getApplicationContext().orElseThrow());
			Assertions.assertSame(replacementContext, revokedContexts.get(1)
					.getRequestContext().getAdmissionIdentity()
					.getApplicationContext().orElseThrow());
			List<McpTaskRequestContext> retainedContexts = taskManager.findContexts()
					.stream().filter(context -> retainedTaskId.equals(context.getTaskId()))
					.toList();
			Assertions.assertEquals(2, retainedContexts.size());
			for (McpTaskRequestContext context : retainedContexts)
				Assertions.assertSame(replacementContext, context.getRequestContext()
						.getAdmissionIdentity().getApplicationContext().orElseThrow());
		} finally {
			taskManager.releaseBlockedTaskFinds();
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	@Test
	public void oneFailedTaskAuthorizationDoesNotRejectHealthyTaskIds()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = server(taskManager, new AtomicInteger());
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-authorization-healthy", ALPHA);
			taskManager.failNextFind("task-authorization-poisoned");

			client = listen(port, "\"partial-task-authorization\"", ALPHA,
					true, "{\"taskIds\":[\"task-authorization-poisoned\","
							+ "\"task-authorization-healthy\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment(
					"\"partial-task-authorization\"",
					List.of("task-authorization-healthy")),
					client.readChunkText());
		} finally {
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	@Test
	public void taskAuthorizationDoesNotRenderDiscardedCompletedOutput()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		AtomicInteger sanitizerInvocations = new AtomicInteger();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.toolResultSanitizer((request, toolName, arguments, output) -> {
					sanitizerInvocations.incrementAndGet();
					return output;
				})
				.build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-completed-authorization", ALPHA);
			taskManager.replaceTask(completedTask(
					taskManager.requireTask("task-completed-authorization")));
			sanitizerInvocations.set(0);

			client = listen(port, "\"lightweight-task-authorization\"", ALPHA,
					true, "{\"taskIds\":[\"task-completed-authorization\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment(
					"\"lightweight-task-authorization\"",
					List.of("task-completed-authorization")),
					client.readChunkText());
			Assertions.assertEquals(0, sanitizerInvocations.get(),
					"Subscription authorization must not sanitize a completed result "
							+ "that it immediately discards.");
		} finally {
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	@Test
	public void transientProjectionLookupFailureKeepsSubscriptionOpen()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = server(taskManager, new AtomicInteger());
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-projection-transient", ALPHA);
			seedTask(port, "task-projection-control", ALPHA);
			client = listen(port, "\"projection-transient\"", ALPHA, true,
					"{\"taskIds\":[\"task-projection-transient\","
							+ "\"task-projection-control\"]}");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"projection-transient\"",
					List.of("task-projection-transient", "task-projection-control")),
					client.readChunkText());

			taskManager.resetFindInvocations();
			taskManager.failNextFind("task-projection-transient");
			taskManager.publishTaskChanged("task-projection-transient");
			taskManager.awaitFindCompletions("task-projection-transient", 1);
			taskManager.publishTaskChanged("task-projection-control");
			Assertions.assertEquals(workingNotification("\"projection-transient\"",
					"task-projection-control"), client.readChunkText(),
					"An advisory lookup failure must skip one notification without "
							+ "terminating the subscription.");
		} finally {
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	@Test
	public void fullPartitionRejectsBeforeAnyAdditionalTaskAuthorizationLookup()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.maximumSubscriptionsPerPartition(1)
				.build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient admitted = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-cap-admitted", ALPHA);
			seedTask(port, "task-cap-rejected", ALPHA);
			admitted = listen(port, "\"task-cap-admitted\"", ALPHA, true,
					"{\"taskIds\":[\"task-cap-admitted\"]}");
			assertSseHead(admitted.readHead());
			Assertions.assertEquals(acknowledgment("\"task-cap-admitted\"",
					List.of("task-cap-admitted")), admitted.readChunkText());
			taskManager.resetFindInvocations();

			assertTaskSubscriptionCapacityRejected(port, "task-cap-rejected",
					"task-cap-rejected");
			Assertions.assertEquals(0, taskManager.findInvocations(),
					"A full authorization partition must reject before task IDs "
							+ "amplify manager lookups.");
		} finally {
			if (admitted != null)
				admitted.closeWithReset();
			soklet.close();
		}
	}

	@Test
	public void clientDisconnectReleasesPendingTaskSubscriptionCapacityBeforeLookupReturns()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.maximumSubscriptionsPerPartition(1)
				.build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient blocked = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-pending-disconnect", ALPHA);
			seedTask(port, "task-after-pending-disconnect", ALPHA);
			taskManager.resetFindInvocations();
			taskManager.blockNextFindBeforeSnapshotIgnoringInterrupts(
					"task-pending-disconnect");

			blocked = listen(port, "\"pending-disconnect\"", ALPHA, true,
					"{\"taskIds\":[\"task-pending-disconnect\"]}");
			taskManager.awaitBlockedFind();
			awaitReservedSubscriptionCapacity(server, 1);
			blocked.closeWithReset();
			blocked = null;

			awaitRecoveredTaskSubscription(port,
					"task-after-pending-disconnect");
			Assertions.assertEquals(0,
					taskManager.findCompletions("task-pending-disconnect"),
					"Disconnect must release capacity without waiting for the "
							+ "application-owned lookup to return.");
		} finally {
			taskManager.releaseBlockedFind();
			if (blocked != null)
				blocked.closeWithReset();
			soklet.close();
		}
	}

	@Test
	public void shutdownReleasesPendingTaskSubscriptionCapacityBeforeLookupReturns()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.maximumSubscriptionsPerPartition(1)
				.build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient blocked = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-pending-shutdown", ALPHA);
			taskManager.resetFindInvocations();
			taskManager.blockNextFindBeforeSnapshotIgnoringInterrupts(
					"task-pending-shutdown");

			blocked = listen(port, "\"pending-shutdown\"", ALPHA, true,
					"{\"taskIds\":[\"task-pending-shutdown\"]}");
			taskManager.awaitBlockedFind();
			awaitReservedSubscriptionCapacity(server, 1);

			soklet.shutdown();
			awaitReservedSubscriptionCapacity(server, 0);
			Assertions.assertEquals(0,
					taskManager.findCompletions("task-pending-shutdown"),
					"Graceful shutdown must release capacity without waiting for "
							+ "the application-owned lookup to return.");
		} finally {
			taskManager.releaseBlockedFind();
			if (blocked != null)
				blocked.closeWithReset();
			soklet.close();
		}
	}

	@Test
	public void deadlineReleasesPendingTaskSubscriptionCapacityBeforeLookupReturns()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.maximumSubscriptionsPerPartition(1)
				.requestTimeout(Duration.ofSeconds(1))
				.build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient blocked = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-pending-deadline", ALPHA);
			seedTask(port, "task-after-pending-deadline", ALPHA);
			taskManager.resetFindInvocations();
			taskManager.blockNextFindBeforeSnapshotIgnoringInterrupts(
					"task-pending-deadline");

			blocked = listen(port, "\"pending-deadline\"", ALPHA, true,
					"{\"taskIds\":[\"task-pending-deadline\"]}");
			taskManager.awaitBlockedFind();
			awaitReservedSubscriptionCapacity(server, 1);
			McpChunkedHttpClient.HttpResponseHead deadlineHead =
					blocked.readHead();
			Assertions.assertEquals(504, deadlineHead.status(),
					deadlineHead.raw());
			Assertions.assertEquals("no-store",
					deadlineHead.singleHeader("Cache-Control"));
			Assertions.assertFalse(deadlineHead.hasHeader("Content-Type"));

			awaitRecoveredTaskSubscription(port,
					"task-after-pending-deadline");
			Assertions.assertEquals(0,
					taskManager.findCompletions("task-pending-deadline"),
					"Deadline expiry must release capacity without waiting for the "
							+ "application-owned lookup to return.");
		} finally {
			taskManager.releaseBlockedFind();
			if (blocked != null)
				blocked.closeWithReset();
			soklet.close();
		}
	}

	@Test
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

	@Test
	public void inputRequiredTasksAreFilteredByEachSubscribersCapabilities()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = server(taskManager, new AtomicInteger());
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient withoutRoots = null;
		McpChunkedHttpClient withRoots = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-input-initial", ALPHA);
			seedTask(port, "task-input-transition", ALPHA);
			seedTask(port, "task-input-control", ALPHA);
			taskManager.replaceTask(inputRequiredTask(
					taskManager.requireTask("task-input-initial")));

			withoutRoots = listen(port, "\"without-roots\"", ALPHA, true,
					false, "{\"taskIds\":[\"task-input-initial\","
							+ "\"task-input-transition\",\"task-input-control\"]}");
			assertSseHead(withoutRoots.readHead());
			Assertions.assertEquals(acknowledgment("\"without-roots\"", List.of(
					"task-input-transition", "task-input-control")),
					withoutRoots.readChunkText(),
					"Initial authorization must omit an input-required task when "
							+ "the subscriber lacks Roots support.");

			withRoots = listen(port, "\"with-roots\"", ALPHA, true, true,
					"{\"taskIds\":[\"task-input-initial\","
							+ "\"task-input-transition\"]}");
			assertSseHead(withRoots.readHead());
			Assertions.assertEquals(acknowledgment("\"with-roots\"", List.of(
					"task-input-initial", "task-input-transition")),
					withRoots.readChunkText(),
					"A Roots-capable subscriber must retain the same task.");

			taskManager.replaceTask(inputRequiredTask(
					taskManager.requireTask("task-input-transition")));
			taskManager.publishTaskChanged("task-input-transition");
			Assertions.assertEquals(inputRequiredNotification("\"with-roots\"",
					"task-input-transition"), withRoots.readChunkText());

			taskManager.publishTaskChanged("task-input-control");
			Assertions.assertEquals(workingNotification("\"without-roots\"",
					"task-input-control"), withoutRoots.readChunkText(),
					"Event projection must suppress input-required state only for "
							+ "the subscriber that lacks Roots support.");
		} finally {
			if (withoutRoots != null)
				withoutRoots.closeWithReset();
			if (withRoots != null)
				withRoots.closeWithReset();
			soklet.close();
		}
	}

	@Test
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

	@Test
	public void malformedTaskIdsAndMissingCapabilityFailBeforeAdmission()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		AtomicInteger admissions = new AtomicInteger();
		McpServer server = server(taskManager, admissions);
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			int port = boundPort(server);
			List<InvalidTaskIds> invalidCases = List.of(
					new InvalidTaskIds("null", "null"),
					new InvalidTaskIds("object", "{}"),
					new InvalidTaskIds("string", "\"task\""),
					new InvalidTaskIds("nonstring-member", "[37]"),
					new InvalidTaskIds("empty", "[\"\"]"),
					new InvalidTaskIds("blank", "[\"   \"]"),
					new InvalidTaskIds("carriage-return", "[\"bad\\rvalue\"]"),
					new InvalidTaskIds("newline", "[\"bad\\nvalue\"]"));
			for (InvalidTaskIds invalidCase : invalidCases)
				assertInvalidTaskIds(port, invalidCase);
			Assertions.assertEquals(0, admissions.get(),
					"Malformed task filters must fail before admission.");
			Assertions.assertEquals(0, taskManager.findInvocations());

			try (McpChunkedHttpClient client = listen(port,
					"\"missing-capability\"", ALPHA, false,
					"{\"taskIds\":[\"task-unknown\"]}")) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				assertJsonErrorHead(head, 400);
				Assertions.assertEquals(missingTasksCapabilityError(
						"\"missing-capability\""),
						client.readFixedBody(head));
			}
			Assertions.assertEquals(0, admissions.get(),
					"Missing Tasks support must fail before admission.");
			Assertions.assertEquals(0, taskManager.findInvocations());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void taskIdsWithoutTasksCapabilityFailBeforeAdmissionWhenOnlyResourcesCanNotify()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager(false);
		AtomicInteger admissions = new AtomicInteger();
		McpServer server = resourceSubscriptionServer(taskManager, admissions);
		Soklet soklet = managedSoklet(server);

		try {
			soklet.start();
			try (McpChunkedHttpClient client = listen(boundPort(server),
					"\"resource-only\"", ALPHA, false,
					"{\"taskIds\":[\"task-unknown\"]}")) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				assertJsonErrorHead(head, 400);
				Assertions.assertEquals(missingTasksCapabilityError(
						"\"resource-only\""), client.readFixedBody(head));
			}
			Assertions.assertEquals(0, admissions.get(),
					"The capability check must precede admission even when only "
							+ "resource notifications are configured.");
			Assertions.assertEquals(0, taskManager.findInvocations(),
					"A resource-only notification source must not trigger a task lookup.");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void moreThanMaximumUniqueTaskIdsFailsBeforeAdmissionOrLookup()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		AtomicInteger admissions = new AtomicInteger();
		McpServer server = server(taskManager, admissions);
		Soklet soklet = managedSoklet(server);
		List<String> excessiveTaskIds = new ArrayList<>();
		for (int index = 0; index < 257; index++)
			excessiveTaskIds.add("task-limit-" + index);

		try {
			soklet.start();
			try (McpChunkedHttpClient client = listen(boundPort(server),
					"\"too-many-task-ids\"", ALPHA, true,
					taskIdsFilter(excessiveTaskIds))) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				assertJsonErrorHead(head, 400);
				Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":"
						+ "\"too-many-task-ids\",\"error\":{\"code\":-32602,"
						+ "\"message\":\"Invalid params\"}}",
						client.readFixedBody(head));
			}
			Assertions.assertEquals(0, admissions.get(),
					"The finite task-ID bound must be enforced before admission.");
			Assertions.assertEquals(0, taskManager.findInvocations(),
					"An oversized task filter must not amplify manager lookups.");
		} finally {
			soklet.close();
		}
	}

	@Test
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

	@Test
	public void taskNotificationBackpressureClosesStreamAndReleasesCapacity()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.streamQueueCapacity(1)
				.maximumSubscriptionsPerPartition(1)
				.build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient backpressured = null;

		try {
			soklet.start();
			int port = boundPort(server);
			List<String> largeTaskIds = List.of("task-large-first",
					"task-large-second", "task-large-third",
					"task-large-fourth");
			for (String taskId : largeTaskIds)
				seedTask(port, taskId, ALPHA);
			seedTask(port, "task-after-backpressure", ALPHA);

			String notificationsJson = "{\"taskIds\":[\""
					+ String.join("\",\"", largeTaskIds) + "\"]}";
			backpressured = listen(port, "\"task-backpressured\"", ALPHA,
					true, notificationsJson, 1_024);
			assertSseHead(backpressured.readHead());
			Assertions.assertEquals(acknowledgment("\"task-backpressured\"",
					largeTaskIds), backpressured.readChunkText());

			for (int index = 0; index < largeTaskIds.size(); index++) {
				String taskId = largeTaskIds.get(index);
				taskManager.replaceTask(workingTaskWithMessage(
						taskManager.requireTask(taskId),
						String.valueOf((char) ('a' + index)).repeat(900_000)));
				taskManager.publishTaskChanged(taskId);
			}
			awaitRecoveredTaskSubscription(port, "task-after-backpressure");
		} finally {
			if (backpressured != null)
				backpressured.closeWithReset();
			soklet.close();
		}
	}

	@Test
	public void oversizedSingleTaskNotificationIsBackpressureAndReleasesCapacity()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		TaskStreamCloseMetrics metrics = new TaskStreamCloseMetrics();
		McpServer server = serverBuilder(taskManager, new AtomicInteger())
				.maximumSubscriptionsPerPartition(1)
				.build();
		Soklet soklet = managedSoklet(server, metrics);
		McpChunkedHttpClient oversized = null;

		try {
			soklet.start();
			int port = boundPort(server);
			seedTask(port, "task-oversized", ALPHA);
			seedTask(port, "task-after-oversized", ALPHA);
			oversized = listen(port, "\"oversized-task-notification\"",
					ALPHA, true, "{\"taskIds\":[\"task-oversized\"]}");
			assertSseHead(oversized.readHead());
			Assertions.assertEquals(acknowledgment(
					"\"oversized-task-notification\"",
					List.of("task-oversized")), oversized.readChunkText());

			taskManager.replaceTask(workingTaskWithMessage(
					taskManager.requireTask("task-oversized"),
					"x".repeat(1_100_000)));
			taskManager.publishTaskChanged("task-oversized");
			metrics.awaitBackpressureClose();
			awaitRecoveredTaskSubscription(port, "task-after-oversized");
		} finally {
			if (oversized != null)
				oversized.closeWithReset();
			soklet.close();
		}
	}

	@Test
	@Timeout(90)
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
			Object control = subscriptionControl(server);
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
			awaitProjectionDrained(control);
			assertNoPrivateProjectionRetention(control, taskIds.size());

			for (String taskId : taskIds) {
				String output = "PRIVATE-RESULT-" + taskId + "y".repeat(64 * 1024);
				taskManager.replaceTask(completedTask(
						taskManager.requireTask(taskId), output));
				taskManager.publishTaskChanged(taskId);
				Assertions.assertTrue(client.readChunkText().contains(output),
						"The completed output must be delivered before it is released.");
			}
			awaitProjectionDrained(control);
			assertNoPrivateProjectionRetention(control, taskIds.size());

			client.closeWithReset();
			client = null;
			awaitActiveSubscriptions(server, 0);
			assertNoPrivateProjectionRetention(control, 0);
		} finally {
			taskManager.releaseBlockedTaskFinds();
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	@Test
	@Timeout(90)
	public void deliveryComparisonPreservesSameTimestampChangesAndStaleSuppression()
			throws Exception {
		ScriptedTaskManager taskManager = new ScriptedTaskManager();
		McpServer server = serverBuilder(taskManager, new AtomicInteger()).build();
		Soklet soklet = managedSoklet(server);
		McpChunkedHttpClient client = null;
		try {
			soklet.start();
			int port = boundPort(server);
			String taskId = "task-comparison";
			String controlTaskId = "task-comparison-control";
			seedTask(port, taskId, ALPHA);
			seedTask(port, controlTaskId, ALPHA);
			client = listen(port, "\"comparison\"", ALPHA, true,
					taskIdsFilter(List.of(taskId, controlTaskId)));
			assertSseHead(client.readHead());
			client.readChunkText();
			Object control = subscriptionControl(server);

			taskManager.publishTaskChanged(taskId);
			Assertions.assertEquals(workingNotification("\"comparison\"", taskId),
					client.readChunkText());
			awaitProjectionDrained(control);

			McpTask original = taskManager.requireTask(taskId);
			// A separately constructed equal value must not cause another delivery.
			taskManager.replaceTask(workingTask(taskId, original.getTaskOrigin()));
			taskManager.publishTaskChanged(taskId);
			taskManager.publishTaskChanged(controlTaskId);
			Assertions.assertEquals(workingNotification("\"comparison\"",
					controlTaskId), client.readChunkText());
			awaitProjectionDrained(control);

			taskManager.replaceTask(workingTaskWithMessage(original,
					"same-time-message"));
			taskManager.publishTaskChanged(taskId);
			Assertions.assertTrue(client.readChunkText().contains(
					"\"statusMessage\":\"same-time-message\""),
					"A timestamp alone is not a delivery-comparison key.");
			awaitProjectionDrained(control);

			McpTask metadataChange = McpTask.withTaskId(taskId,
					original.getTaskOrigin(), McpTaskStatus.WORKING,
					CREATED_AT, WORKING_UPDATED_AT)
					.taskStatusMessage("same-time-message")
					.timeToLive(TASK_TIME_TO_LIVE).pollInterval(POLL_INTERVAL)
					.metadata(McpJsonObject.builder()
							.put("com.example/change", "same-time-metadata").build())
					.build();
			taskManager.replaceTask(metadataChange);
			taskManager.publishTaskChanged(taskId);
			Assertions.assertTrue(client.readChunkText().contains(
					"\"com.example/change\":\"same-time-metadata\""));
			awaitProjectionDrained(control);

			taskManager.replaceTask(McpTask.withTaskId(taskId,
					original.getTaskOrigin(), McpTaskStatus.WORKING,
					CREATED_AT, WORKING_UPDATED_AT.minusMillis(1))
					.taskStatusMessage("stale-message").build());
			taskManager.publishTaskChanged(taskId);
			taskManager.replaceTask(workingTaskWithMessage(
					taskManager.requireTask(controlTaskId), "control-after-stale"));
			taskManager.publishTaskChanged(controlTaskId);
			Assertions.assertTrue(client.readChunkText().contains(
					"\"statusMessage\":\"control-after-stale\""),
					"Changed content must not override the stale-timestamp fence.");
			awaitProjectionDrained(control);
			assertNoPrivateProjectionRetention(control, 2);
			soklet.close();
			assertNoPrivateProjectionRetention(control, 0);
		} finally {
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	@Test
	@Timeout(90)
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
			Object control = subscriptionControl(server);
			taskManager.publishTaskChanged("task-disconnect-active");
			Assertions.assertEquals(workingNotification("\"disconnect-active\"",
					"task-disconnect-active"), client.readChunkText());
			awaitProjectionDrained(control);
			taskManager.resetFindInvocations();

			taskManager.blockTaskFindsAfterSnapshot(
					Set.of("task-disconnect-active"));
			taskManager.publishTaskChanged("task-disconnect-active");
			taskManager.awaitBlockedTaskFinds();
			client.closeWithReset();
			client = null;
			awaitActiveSubscriptions(server, 0);
			assertNoPrivateProjectionRetention(control, 0);

			taskManager.releaseBlockedTaskFinds();
			taskManager.awaitFindCompletions("task-disconnect-active", 1);
			assertDiscoverCompletes(port);
			assertNoPrivateProjectionRetention(control, 0);
			awaitRecoveredTaskSubscription(port, "task-disconnect-recovered");
		} finally {
			taskManager.releaseBlockedTaskFinds();
			if (client != null)
				client.closeWithReset();
			soklet.close();
		}
	}

	@Test
	@Timeout(120)
	public void sharedTaskPublisherRegistersOncePerServerGeneration()
			throws Exception {
		RecordingTaskEventPublisher publisher =
				new RecordingTaskEventPublisher();
		ScriptedTaskManager taskManager = new ScriptedTaskManager(publisher);
		Soklet firstOwner = null;
		Soklet secondOwner = null;

		try {
			McpServer firstServer = server(List.of(
					taskEndpoint("/mcp-first", taskManager),
					taskEndpoint("/mcp-second", taskManager)),
					taskManager, new AtomicInteger());
			firstOwner = managedSoklet(firstServer);
			firstOwner.start();
			Assertions.assertEquals(1, publisher.subscriptionCount(),
					"Endpoints sharing one task publisher must share one registration.");
			Assertions.assertEquals(1, publisher.activeRegistrationCount());

			firstOwner.close();
			firstOwner = null;
			Assertions.assertEquals(1, publisher.closedRegistrationCount(),
					"Stopping a generation must close its shared registration once.");
			Assertions.assertEquals(0, publisher.activeRegistrationCount());

			McpServer secondServer = server(List.of(
					taskEndpoint("/mcp-first", taskManager),
					taskEndpoint("/mcp-second", taskManager)),
					taskManager, new AtomicInteger());
			secondOwner = managedSoklet(secondServer);
			secondOwner.start();
			Assertions.assertEquals(2, publisher.subscriptionCount(),
					"A fresh server generation must establish one fresh registration.");
			Assertions.assertEquals(1, publisher.activeRegistrationCount());
		} finally {
			if (firstOwner != null)
				firstOwner.close();
			if (secondOwner != null)
				secondOwner.close();
		}
		Assertions.assertEquals(2, publisher.closedRegistrationCount());
		Assertions.assertEquals(0, publisher.activeRegistrationCount());
		Assertions.assertEquals(0, publisher.publisherCloseCount(),
				"Soklet must not assume lifecycle ownership of the publisher.");
	}

	private static McpServer server(@NonNull ScriptedTaskManager taskManager,
			@NonNull AtomicInteger admissions) {
		return serverBuilder(taskManager, admissions).build();
	}

	private static McpServer server(
			@NonNull List<@NonNull McpEndpoint> endpoints,
			@NonNull ScriptedTaskManager taskManager,
			@NonNull AtomicInteger admissions) {
		return serverBuilder(endpoints, taskManager, admissions).build();
	}

	private static McpServer.@NonNull Builder serverBuilder(
			@NonNull ScriptedTaskManager taskManager,
			@NonNull AtomicInteger admissions) {
		return serverBuilder(List.of(taskEndpoint(MCP_PATH, taskManager)),
				taskManager, admissions);
	}

	private static McpServer.@NonNull Builder serverBuilder(
			@NonNull List<@NonNull McpEndpoint> endpoints,
			@NonNull ScriptedTaskManager taskManager,
			@NonNull AtomicInteger admissions) {
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.subscriptionAuthorizer((context, features) ->
						McpSubscriptionAuthorization.Allowed.fromValidUntil(
								Instant.now().plus(Duration.ofMinutes(5))))
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

	private static McpEndpoint taskEndpoint(@NonNull String path,
			@NonNull ScriptedTaskManager taskManager) {
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
				.addInputRequestDeclaration(ELICITATION_URL_DECLARATION)
				.structuredContentMirroredAsText(false)
				.build();
		return McpEndpoint.withPath(path,
				McpImplementation.withNameAndVersion(
						"task-subscription-public-runtime-test", "4.0.0")
						.build())
				.addTool(tool)
				.build();
	}

	private static McpServer resourceSubscriptionServer(
			@NonNull ScriptedTaskManager taskManager,
			@NonNull AtomicInteger admissions) {
		McpSubscriptionConfig subscriptionConfig = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(
						McpSubscriptionEventPublisher.fromInMemoryDefaults(),
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"task-subscription-resource-only-test", "4.0.0")
						.build())
				.resourceListHandler((request, resourceList, features) ->
						McpResourcePage.builder().build())
				.subscriptionConfig(subscriptionConfig)
				.build();
		return server(List.of(endpoint), taskManager, admissions);
	}

	private static Soklet managedSoklet(@NonNull McpServer server) {
		return managedSoklet(server, MetricsCollector.disabledInstance());
	}

	private static Soklet managedSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector metricsCollector) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector)
				.build());
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static void seedTask(int port, @NonNull String taskId,
			@NonNull String tenant) throws Exception {
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
			@NonNull String tenant, boolean tasksCapable,
			@NonNull String notificationsJson) throws Exception {
		return listen(port, idJson, tenant, tasksCapable, false,
				notificationsJson, 0);
	}

	private static McpChunkedHttpClient listen(int port, String idJson,
			@NonNull String tenant, boolean tasksCapable, boolean rootsCapable,
			@NonNull String notificationsJson) throws Exception {
		return listen(port, idJson, tenant, tasksCapable, rootsCapable,
				notificationsJson, 0);
	}

	private static McpChunkedHttpClient listen(int port, String idJson,
			@NonNull String tenant, boolean tasksCapable,
			@NonNull String notificationsJson, int receiveBufferBytes)
			throws Exception {
		return listen(port, idJson, tenant, tasksCapable, false,
				notificationsJson, receiveBufferBytes);
	}

	private static McpChunkedHttpClient listen(int port, String idJson,
			@NonNull String tenant, boolean tasksCapable, boolean rootsCapable,
			@NonNull String notificationsJson, int receiveBufferBytes)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"subscriptions/listen\",\"params\":{"
				+ taskMetadata(tasksCapable, rootsCapable)
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

	private static McpChunkedHttpClient listenWithCredentialCanaries(int port,
			@NonNull String idJson, @NonNull String acceptedTaskId,
			@NonNull String rejectedTaskId) throws Exception {
		String traceparent =
				"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{"
				+ "\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}},"
				+ "\"authorizationTestMetadata\":\"metadata-secret\","
				+ "\"traceparent\":\"" + traceparent + "\","
				+ "\"baggage\":\"credential=metadata-secret\"},"
				+ "\"notifications\":{\"taskIds\":[\"" + acceptedTaskId
				+ "\",\"" + rejectedTaskId + "\"]}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen"),
				new McpChunkedHttpClient.RequestHeader(
						"Authorization", "Bearer original-credential"),
				new McpChunkedHttpClient.RequestHeader(
						"traceparent", traceparent)), 0);
	}

	private static String taskMetadata(boolean tasksCapable) {
		return taskMetadata(tasksCapable, false);
	}

	private static String taskMetadata(boolean tasksCapable,
			boolean rootsCapable) {
		String capabilities = tasksCapable
				? "{\"extensions\":{\"" + TASKS_EXTENSION_ID + "\":{}}"
						+ (rootsCapable ? ",\"elicitation\":{\"url\":{}}" : "") + "}"
				: (rootsCapable ? "{\"elicitation\":{\"url\":{}}}" : "{}");
		return "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ capabilities + "}";
	}

	private static void assertInvalidTaskIds(int port,
			@NonNull InvalidTaskIds invalidCase) throws Exception {
		try (McpChunkedHttpClient client = listen(port,
				"\"invalid-" + invalidCase.name() + "\"", ALPHA, true,
				"{\"taskIds\":" + invalidCase.valueJson() + "}")) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			assertJsonErrorHead(head, 400);
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":"
					+ "\"invalid-" + invalidCase.name() + "\",\"error\":{"
					+ "\"code\":-32602,\"message\":\"Invalid params\"}}",
					client.readFixedBody(head));
		}
	}

	private static void assertTaskSubscriptionCapacityRejected(int port,
			@NonNull String requestId, @NonNull String taskId) throws Exception {
		try (McpChunkedHttpClient client = listen(port,
				"\"" + requestId + "\"", ALPHA, true,
				"{\"taskIds\":[\"" + taskId + "\"]}")) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(503, head.status(), head.raw());
			Assertions.assertEquals("application/json",
					head.singleHeader("Content-Type"));
			Assertions.assertEquals("no-store",
					head.singleHeader("Cache-Control"));
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\""
					+ requestId + "\",\"error\":{\"code\":-32603,"
					+ "\"message\":\"Internal error\"}}",
					client.readFixedBody(head));
		}
	}

	private static void assertJsonErrorHead(
			McpChunkedHttpClient.@NonNull HttpResponseHead head,
			int expectedStatus) {
		Assertions.assertEquals(expectedStatus, head.status(), head.raw());
		Assertions.assertEquals("application/json",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store",
				head.singleHeader("Cache-Control"));
	}

	private static void assertSseHead(
			McpChunkedHttpClient.@NonNull HttpResponseHead head) {
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
			@NonNull List<@NonNull String> taskIds) {
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

	private static String missingTasksCapabilityError(
			@NonNull String requestIdJson) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + requestIdJson
				+ ",\"error\":{\"code\":-32021,\"message\":"
				+ "\"Missing required client capability\",\"data\":{"
				+ "\"requiredCapabilities\":{\"extensions\":{\""
				+ TASKS_EXTENSION_ID + "\":{}}}}}}";
	}

	private static String taskIdsFilter(
			@NonNull List<@NonNull String> taskIds) {
		List<String> encodedTaskIds = new ArrayList<>();
		for (String taskId : taskIds)
			encodedTaskIds.add("\"" + taskId + "\"");
		return "{\"taskIds\":[" + String.join(",", encodedTaskIds) + "]}";
	}

	private static String workingNotification(String subscriptionIdJson,
			@NonNull String taskId) {
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
			@NonNull String taskId) {
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
			@NonNull String taskId) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/tasks\",\"params\":{"
				+ "\"taskId\":\"" + taskId + "\","
				+ "\"status\":\"input_required\",\"statusMessage\":"
				+ "\"input-required-message\",\"createdAt\":\"" + CREATED_AT
				+ "\",\"lastUpdatedAt\":\"" + INPUT_REQUIRED_UPDATED_AT
				+ "\",\"ttlMs\":60000,\"pollIntervalMs\":250,"
				+ "\"inputRequests\":{\"approval\":{\"method\":"
				+ "\"elicitation/create\",\"params\":{\"mode\":\"url\",\"message\":\"Authorize access\",\"url\":\"https://example.com/authorize\"}}},"
				+ "\"_meta\":{\"com.example/task-metadata\":"
				+ "\"input-required\","
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "}}}");
	}

	private static String sse(@NonNull String json) {
		return "data: " + json + "\n\n";
	}

	private static McpTask workingTask(@NonNull String taskId,
			@NonNull McpTaskOrigin taskOrigin) {
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

	private static McpTask completedTask(@NonNull McpTask previous) {
		return completedTask(previous, "completed-output");
	}

	private static McpTask completedTask(@NonNull McpTask previous,
			@NonNull String output) {
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

	private static McpTask canceledTask(@NonNull McpTask previous) {
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

	private static McpTask inputRequiredTask(@NonNull McpTask previous) {
		return McpTask.withTaskId(previous.getTaskId(), previous.getTaskOrigin(),
					McpTaskStatus.INPUT_REQUIRED, CREATED_AT,
					INPUT_REQUIRED_UPDATED_AT)
				.taskStatusMessage("input-required-message")
				.timeToLive(TASK_TIME_TO_LIVE)
				.pollInterval(POLL_INTERVAL)
				.addInputRequest("approval", McpInputRequest.fromDeclaration(
						ELICITATION_URL_DECLARATION, McpJsonObject.builder().put("mode", "url").put("message", "Authorize access").put("url", "https://example.com/authorize").build()))
				.metadata(McpJsonObject.builder()
						.put("com.example/task-metadata", "input-required")
						.build())
				.build();
	}

	private static McpTask regressedWorkingTask(@NonNull McpTask previous) {
		return McpTask.withTaskId(previous.getTaskId(), previous.getTaskOrigin(),
					McpTaskStatus.WORKING, CREATED_AT, REGRESSED_UPDATED_AT)
				.taskStatusMessage("regressed-after-terminal")
				.timeToLive(TASK_TIME_TO_LIVE)
				.pollInterval(POLL_INTERVAL)
				.build();
	}

	private static McpTask workingTaskWithMessage(@NonNull McpTask previous,
			@NonNull String taskStatusMessage) {
		return McpTask.withTaskId(previous.getTaskId(), previous.getTaskOrigin(),
					McpTaskStatus.WORKING, CREATED_AT, WORKING_UPDATED_AT)
				.taskStatusMessage(taskStatusMessage)
				.timeToLive(TASK_TIME_TO_LIVE)
				.pollInterval(POLL_INTERVAL)
				.build();
	}

	private static void awaitRecoveredTaskSubscription(int port,
			@NonNull String taskId) throws Exception {
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

	private static void awaitActiveSubscriptions(@NonNull McpServer server,
			int expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (server.getDiagnostics().getActiveSubscriptions() != expected) {
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError("Timed out waiting for " + expected
						+ " active MCP subscriptions.");
			Thread.sleep(10L);
		}
	}

	private static void awaitReservedSubscriptionCapacity(
			@NonNull McpServer server, int expected) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (reservedSubscriptionCapacity(server) != expected) {
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError("Timed out waiting for " + expected
						+ " reserved MCP subscription slots.");
			Thread.sleep(10L);
		}
	}

	private static int reservedSubscriptionCapacity(@NonNull McpServer server)
			throws Exception {
		Object runtime = field(field(server, "runtimeBridge"), "runtime");
		Object subscriptionLock = field(runtime, "subscriptionLock");
		synchronized (subscriptionLock) {
			Object value = field(runtime,
					"activeSubscriptionCountsByPartition");
			Assertions.assertInstanceOf(Map.class, value);
			int reserved = 0;
			for (Object count : ((Map<?, ?>) value).values()) {
				Assertions.assertInstanceOf(Integer.class, count);
				reserved = Math.addExact(reserved, (Integer) count);
			}
			return reserved;
		}
	}

	private static Object subscriptionControl(@NonNull McpServer server)
			throws Exception {
		Object runtime = field(field(server, "runtimeBridge"), "runtime");
		Map<?, ?> controls = (Map<?, ?>) field(runtime, "requestControls");
		List<?> snapshot;
		synchronized (controls) {
			snapshot = List.copyOf(controls.values());
		}
		for (Object control : snapshot) {
			synchronized (field(control, "lock")) {
				if (field(control, "subscriptionRegistration") != null)
					return control;
			}
		}
		throw new AssertionError("No active subscription control exists.");
	}

	private static void awaitProjectionDrained(@NonNull Object control)
			throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			synchronized (field(control, "lock")) {
				McpHttpServerRuntime.TaskNotificationProjectionQueue queue =
						(McpHttpServerRuntime.TaskNotificationProjectionQueue)
								field(control, "taskNotificationProjectionQueue");
				McpRequestSseStream stream = (McpRequestSseStream)
						field(control, "responseStream");
				if (!queue.jobOutstanding()
						&& stream.snapshot().orElseThrow().bufferedFrames() == 0)
					return;
			}
			if (System.nanoTime() - deadline >= 0L)
				throw new AssertionError("Task projection or outbound frames did not drain.");
			Thread.sleep(10L);
		}
	}

	private static void assertNoPrivateProjectionRetention(
			@NonNull Object control, int expectedTaskIds) throws Exception {
		synchronized (field(control, "lock")) {
			Object queue = field(control, "taskNotificationProjectionQueue");
			Assertions.assertEquals(expectedTaskIds,
					((Map<?, ?>) field(queue, "states")).size(),
					"Terminal subscription cleanup must release all delivery state.");
			assertNoPrivateTaskObjects(queue,
					Collections.newSetFromMap(new IdentityHashMap<>()));
		}
	}

	private static void assertNoPrivateTaskObjects(@Nullable Object value,
			Set<Object> visited) throws Exception {
		if (value == null || !visited.add(value))
			return;
		Assertions.assertFalse(value instanceof McpTaskOrigin
				|| value instanceof McpTask
				|| value instanceof McpCompleteResult
				|| value instanceof McpServerRuntimeBridge.TaskSnapshot,
				"Delivery comparison retained a private origin/full task/result: "
						+ value.getClass().getName());
		if (value instanceof String string) {
			Assertions.assertFalse(string.contains("PRIVATE-ORIGIN-")
					|| string.contains("PRIVATE-RESULT-"),
					"Delivery comparison must not retain serialized private payloads.");
		} else if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				assertNoPrivateTaskObjects(entry.getKey(), visited);
				assertNoPrivateTaskObjects(entry.getValue(), visited);
			}
		} else if (value instanceof Iterable<?> iterable) {
			for (Object element : iterable)
				assertNoPrivateTaskObjects(element, visited);
		} else if (value instanceof Optional<?> optional) {
			assertNoPrivateTaskObjects(optional.orElse(null), visited);
		} else if (!value.getClass().isEnum()
				&& value.getClass().getName().startsWith("com.soklet.")) {
			for (Field field : value.getClass().getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()))
					continue;
				field.setAccessible(true);
				assertNoPrivateTaskObjects(field.get(value), visited);
			}
		}
	}

	@NonNull
	private static Object field(@NonNull Object target, @NonNull String name)
			throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static final class InvalidTaskIds {
		@NonNull
		private final String name;
		@NonNull
		private final String valueJson;

		private InvalidTaskIds(@NonNull String name,
				@NonNull String valueJson) {
			this.name = name;
			this.valueJson = valueJson;
		}

		@NonNull
		private String name() {
			return this.name;
		}

		@NonNull
		private String valueJson() {
			return this.valueJson;
		}
	}

	@ThreadSafe
	private static final class TaskStreamCloseMetrics
			implements MetricsCollector {
		@NonNull
		private final AtomicReference<@Nullable McpStreamTerminationReason>
				requestStreamReason = new AtomicReference<>();
		@NonNull
		private final AtomicReference<@Nullable McpStreamTerminationReason>
				subscriptionReason = new AtomicReference<>();
		@NonNull
		private final CountDownLatch subscriptionClosed = new CountDownLatch(1);

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
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

	@ThreadSafe
	private static final class RecordingTaskEventPublisher
			implements McpTaskEventPublisher, AutoCloseable {
		@NonNull
		private final CopyOnWriteArrayList<@NonNull Registration> registrations =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final AtomicInteger subscriptionCount = new AtomicInteger();
		@NonNull
		private final AtomicInteger closedRegistrationCount = new AtomicInteger();
		@NonNull
		private final AtomicInteger publisherCloseCount = new AtomicInteger();

		@Override
		@NonNull
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpTaskEventListener listener) {
			Registration registration = new Registration(listener);
			this.registrations.add(registration);
			this.subscriptionCount.incrementAndGet();
			return registration;
		}

		@Override
		public void publishTaskChanged(@NonNull String taskId) {
			for (Registration registration : this.registrations)
				registration.deliver(taskId);
		}

		@Override
		public void close() {
			this.publisherCloseCount.incrementAndGet();
		}

		private int subscriptionCount() {
			return this.subscriptionCount.get();
		}

		private int closedRegistrationCount() {
			return this.closedRegistrationCount.get();
		}

		private int activeRegistrationCount() {
			return this.registrations.size();
		}

		private int publisherCloseCount() {
			return this.publisherCloseCount.get();
		}

		@ThreadSafe
		private final class Registration
				implements McpSubscriptionEventRegistration {
			@NonNull
			private final McpTaskEventListener listener;
			@NonNull
			private final AtomicBoolean open = new AtomicBoolean(true);

			private Registration(@NonNull McpTaskEventListener listener) {
				this.listener = listener;
			}

			private void deliver(@NonNull String taskId) {
				if (this.open.get())
					this.listener.onTaskChanged(taskId);
			}

			@Override
			public void close() {
				if (this.open.compareAndSet(true, false)) {
					registrations.remove(this);
					closedRegistrationCount.incrementAndGet();
				}
			}
		}
	}

	@ThreadSafe
	private static final class ScriptedTaskManager implements McpTaskManager {
		@NonNull
		private final Map<@NonNull String, @NonNull Entry> entries =
				new ConcurrentHashMap<>();
		@NonNull
		private final Set<@NonNull String> revokedTaskIds =
				ConcurrentHashMap.newKeySet();
		@NonNull
		private final Map<@NonNull String, @NonNull Object>
				revokedApplicationContexts = new ConcurrentHashMap<>();
		@NonNull
		private final Set<@NonNull String> failNextFindTaskIds =
				ConcurrentHashMap.newKeySet();
		private final @Nullable McpTaskEventPublisher taskEventPublisher;
		@NonNull
		private final AtomicInteger findInvocations = new AtomicInteger();
		@NonNull
		private final Map<@NonNull String, @NonNull AtomicInteger>
				findInvocationsByTask = new ConcurrentHashMap<>();
		@NonNull
		private final Map<@NonNull String, @NonNull AtomicInteger>
				findCompletionsByTask = new ConcurrentHashMap<>();
		@NonNull
		private final List<@NonNull McpTaskRequestContext> findContexts =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final CountDownLatch revokedLookup = new CountDownLatch(1);
		@NonNull
		private final Object findBlockLock = new Object();
		private @Nullable FindBlock findBlock;
		private @Nullable TaskFindBlock taskFindBlock;

		private ScriptedTaskManager() {
			this(McpTaskEventPublisher.fromInMemoryDefaults());
		}

		private ScriptedTaskManager(boolean taskNotificationsEnabled) {
			this.taskEventPublisher = taskNotificationsEnabled
					? McpTaskEventPublisher.fromInMemoryDefaults() : null;
		}

		private ScriptedTaskManager(
				@NonNull McpTaskEventPublisher taskEventPublisher) {
			this.taskEventPublisher = taskEventPublisher;
		}

		@Override
		@NonNull
		public Optional<@NonNull McpTaskEventPublisher> getTaskEventPublisher() {
			return Optional.ofNullable(this.taskEventPublisher);
		}

		@Override
		@NonNull
		public Optional<@NonNull McpTask> findTask(
				@NonNull McpTaskRequestContext context) {
			this.findInvocations.incrementAndGet();
			this.findContexts.add(context);
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
				Object revokedApplicationContext =
						this.revokedApplicationContexts.get(taskId);
				if (revokedApplicationContext != null
						&& requestContext.getAdmissionIdentity()
								.getApplicationContext().orElse(null)
								== revokedApplicationContext)
					return Optional.empty();
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
		public void updateTask(@NonNull McpTaskUpdateContext context)
				throws McpTaskNotFoundException {
			throw new McpTaskNotFoundException();
		}

		@Override
		public void requestTaskCancelation(
				@NonNull McpTaskRequestContext context)
				throws McpTaskNotFoundException {
			throw new McpTaskNotFoundException();
		}

		private void putTask(@NonNull McpTask task,
				@NonNull String authorizationPartition) {
			putTask(task, MCP_PATH, authorizationPartition);
		}

		private void putTask(@NonNull McpTask task,
				@NonNull String endpointPath,
				@NonNull String authorizationPartition) {
			this.entries.put(task.getTaskId(), new Entry(task,
					endpointPath, authorizationPartition));
		}

		private void replaceTask(@NonNull McpTask task) {
			this.entries.compute(task.getTaskId(), (taskId, existing) -> {
				if (existing == null)
					throw new IllegalStateException("Unknown test task: " + taskId);
				return new Entry(task, existing.endpointPath(),
						existing.authorizationPartition());
			});
		}

		@NonNull
		private McpTask requireTask(@NonNull String taskId) {
			Entry entry = this.entries.get(taskId);
			if (entry == null)
				throw new IllegalStateException("Unknown test task: " + taskId);
			return entry.task();
		}

		private void revoke(@NonNull String taskId) {
			this.revokedTaskIds.add(taskId);
		}

		private void revokeForApplicationContext(@NonNull String taskId,
				@NonNull Object applicationContext) {
			this.revokedApplicationContexts.put(taskId, applicationContext);
		}

		private void failNextFind(@NonNull String taskId) {
			if (!this.failNextFindTaskIds.add(taskId))
				throw new IllegalStateException(
						"A test lookup failure is already armed for " + taskId + '.');
		}

		private void publishTaskChanged(@NonNull String taskId) {
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

		private void blockNextFindBeforeSnapshot(@NonNull String taskId) {
			blockNextFindBeforeSnapshot(taskId, true);
		}

		private void blockNextFindBeforeSnapshotIgnoringInterrupts(
				@NonNull String taskId) {
			blockNextFindBeforeSnapshot(taskId, false);
		}

		private void blockNextFindBeforeSnapshot(@NonNull String taskId,
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
				@NonNull Set<@NonNull String> taskIds) {
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

		private void awaitFindBlockBeforeSnapshot(@NonNull String taskId) {
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

		private void awaitTaskFindBlockAfterSnapshot(@NonNull String taskId) {
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

		private void awaitFindCompletions(@NonNull String taskId, int expected)
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

		private int findInvocations(@NonNull String taskId) {
			AtomicInteger invocations = this.findInvocationsByTask.get(taskId);
			return invocations == null ? 0 : invocations.get();
		}

		private int findCompletions(@NonNull String taskId) {
			AtomicInteger completions = this.findCompletionsByTask.get(taskId);
			return completions == null ? 0 : completions.get();
		}

		@NonNull
		private List<@NonNull McpTaskRequestContext> findContexts() {
			return List.copyOf(this.findContexts);
		}

		private void resetFindInvocations() {
			this.findInvocations.set(0);
			this.findInvocationsByTask.clear();
			this.findCompletionsByTask.clear();
			this.findContexts.clear();
		}
	}

	private static final class FindBlock {
		@NonNull
		private final String taskId;
		@NonNull
		private final CountDownLatch started;
		@NonNull
		private final CountDownLatch release;
		private final boolean interruptionResponsive;
		private boolean claimed;

		private FindBlock(@NonNull String taskId,
				boolean interruptionResponsive) {
			this.taskId = taskId;
			this.started = new CountDownLatch(1);
			this.release = new CountDownLatch(1);
			this.interruptionResponsive = interruptionResponsive;
		}

		@NonNull
		private String taskId() {
			return this.taskId;
		}

		@NonNull
		private CountDownLatch started() {
			return this.started;
		}

		@NonNull
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
		@NonNull
		private final Set<@NonNull String> unclaimedTaskIds;
		@NonNull
		private final CountDownLatch started;
		@NonNull
		private final CountDownLatch release;

		private TaskFindBlock(@NonNull Set<@NonNull String> taskIds) {
			if (taskIds.isEmpty())
				throw new IllegalArgumentException(
						"At least one task lookup must be blocked.");
			this.unclaimedTaskIds = new HashSet<>(taskIds);
			this.started = new CountDownLatch(this.unclaimedTaskIds.size());
			this.release = new CountDownLatch(1);
		}

		private boolean claim(@NonNull String taskId) {
			return this.unclaimedTaskIds.remove(taskId);
		}

		@NonNull
		private CountDownLatch started() {
			return this.started;
		}

		@NonNull
		private CountDownLatch release() {
			return this.release;
		}
	}

	private static final class Entry {
		@NonNull
		private final McpTask task;
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String authorizationPartition;

		private Entry(@NonNull McpTask task, @NonNull String endpointPath,
				@NonNull String authorizationPartition) {
			this.task = task;
			this.endpointPath = endpointPath;
			this.authorizationPartition = authorizationPartition;
		}

		@NonNull
		private McpTask task() {
			return this.task;
		}

		@NonNull
		private String endpointPath() {
			return this.endpointPath;
		}

		@NonNull
		private String authorizationPartition() {
			return this.authorizationPartition;
		}
	}
}
