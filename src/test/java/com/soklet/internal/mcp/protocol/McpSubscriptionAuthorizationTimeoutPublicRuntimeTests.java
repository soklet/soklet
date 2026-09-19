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
import com.soklet.LifecyclePolicy;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpMetricsEvent;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionAuthorizer;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpTextResourceContents;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Queue-inclusive timeout and bounded-capacity coverage for subscription
 * authorization.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpSubscriptionAuthorizationTimeoutPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final URI RESOURCE_URI =
			URI.create("test://subscription/authorization-timeout/resource");

	@Test
	public void queuedInitialAuthorizationTimesOutWithoutEntryRetryOrQuotaLeak()
			throws Exception {
		Duration authorizationTimeout = Duration.ofSeconds(2);
		AtomicInteger authorizations = new AtomicInteger();
		AtomicInteger activeAuthorizers = new AtomicInteger();
		AtomicInteger maximumActiveAuthorizers = new AtomicInteger();
		AtomicReference<com.soklet.CancelationToken> activeToken =
				new AtomicReference<>();
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		CountDownLatch activeExited = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int active = activeAuthorizers.incrementAndGet();
			maximumActiveAuthorizers.accumulateAndGet(active, Math::max);
			int invocation = authorizations.incrementAndGet();
			try {
				if (invocation == 1) {
					activeToken.set(features.getCancelationToken());
					activeEntered.countDown();
					awaitIgnoringInterrupts(releaseActive);
				}
				return allowed();
			} finally {
				activeAuthorizers.decrementAndGet();
				if (invocation == 1)
					activeExited.countDown();
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpServer server = server(authorizer, authorizationTimeout,
				Duration.ofSeconds(5), Duration.ofSeconds(10), 2, 1, 1);
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient activeClient = null;
		McpChunkedHttpClient queuedClient = null;
		McpChunkedHttpClient firstProbeClient = null;
		McpChunkedHttpClient secondProbeClient = null;

		try {
			owner.start();
			int port = boundPort(server);
			activeClient = listen(port, "\"initial-active-timeout\"");
			Assertions.assertTrue(activeEntered.await(5, TimeUnit.SECONDS),
					"The active initial authorization did not enter.");

			queuedClient = listen(port, "\"initial-queued-timeout\"");
			awaitCondition(() -> server.getDiagnostics()
					.getRequestHandlerQueueDepth() == 1,
					"The second initial authorization was not queued.");

			assertFixedFailure(activeClient, 504);
			assertFixedFailure(queuedClient, 503);
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT, 2);
			Assertions.assertTrue(activeToken.get().isCanceled(),
					"The active timed-out authorizer did not observe cancellation.");
			Assertions.assertEquals(1, authorizations.get(),
					"A queue-inclusive timeout invoked the queued authorizer.");
			Assertions.assertEquals(1, maximumActiveAuthorizers.get(),
					"Initial authorization callbacks overlapped.");
			Assertions.assertEquals(0,
					server.getDiagnostics().getRequestHandlerQueueDepth(),
					"A timed-out initial authorization remained queued.");
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveSubscriptions());
			Assertions.assertEquals(1,
					server.getDiagnostics().getActiveHandlerExecutions(),
					"The cancellation-resistant initial callback released its worker early.");
			Assertions.assertEquals(0L, metrics.subscriptionClosedCount(),
					"A pre-ack timeout opened a subscription lifecycle.");
			Assertions.assertEquals(2L, metrics.maintenanceCount(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT));

			releaseActive.countDown();
			Assertions.assertTrue(activeExited.await(5, TimeUnit.SECONDS),
					"The cancellation-resistant initial callback did not exit.");
			Thread.sleep(100L);
			Assertions.assertEquals(1, authorizations.get(),
					"A timed-out initial authorization retried after its callback exited.");

			firstProbeClient = listen(port, "\"initial-timeout-quota-probe-1\"");
			assertSseHead(firstProbeClient.readHead());
			Assertions.assertEquals(acknowledgment(
					"\"initial-timeout-quota-probe-1\""),
					firstProbeClient.readChunkText());
			secondProbeClient = listen(port, "\"initial-timeout-quota-probe-2\"");
			assertSseHead(secondProbeClient.readHead());
			Assertions.assertEquals(acknowledgment(
					"\"initial-timeout-quota-probe-2\""),
					secondProbeClient.readChunkText());
			Assertions.assertEquals(3, authorizations.get(),
					"Both quota probes require independent authorization checks.");
		} finally {
			releaseActive.countDown();
			if (secondProbeClient != null)
				secondProbeClient.closeWithReset();
			if (firstProbeClient != null)
				firstProbeClient.closeWithReset();
			if (queuedClient != null)
				queuedClient.close();
			if (activeClient != null)
				activeClient.close();
			owner.close();
		}
	}

	@Test
	public void activeRenewalTimeoutClosesExactlyOnceWithoutRetryOverlapOrQuotaLeak()
			throws Exception {
		Duration authorizationTimeout = Duration.ofMillis(500);
		AtomicInteger authorizations = new AtomicInteger();
		AtomicInteger activeAuthorizers = new AtomicInteger();
		AtomicInteger maximumActiveAuthorizers = new AtomicInteger();
		AtomicReference<com.soklet.CancelationToken> renewalToken =
				new AtomicReference<>();
		CountDownLatch renewalEntered = new CountDownLatch(1);
		CountDownLatch releaseRenewal = new CountDownLatch(1);
		CountDownLatch renewalExited = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int active = activeAuthorizers.incrementAndGet();
			maximumActiveAuthorizers.accumulateAndGet(active, Math::max);
			int invocation = authorizations.incrementAndGet();
			try {
				if (invocation == 1)
					return allowed(Instant.now().plusSeconds(4));
				if (invocation == 2) {
					renewalToken.set(features.getCancelationToken());
					renewalEntered.countDown();
					awaitIgnoringInterrupts(releaseRenewal);
				}
				return allowed();
			} finally {
				activeAuthorizers.decrementAndGet();
				if (invocation == 2)
					renewalExited.countDown();
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpServer server = server(authorizer, authorizationTimeout,
				Duration.ofSeconds(5), Duration.ofSeconds(10), 1, 1, 1);
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient client = null;
		McpChunkedHttpClient probeClient = null;

		try {
			owner.start();
			int port = boundPort(server);
			client = listen(port, "\"renewal-timeout\"");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"renewal-timeout\""),
					client.readChunkText());
			Assertions.assertTrue(renewalEntered.await(5, TimeUnit.SECONDS),
					"The renewal authorization did not enter.");

			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT, 1);
			metrics.awaitSubscriptionClosed(
					McpStreamTerminationReason
							.SUBSCRIPTION_AUTHORIZATION_CHECK_FAILED);
			Assertions.assertTrue(renewalToken.get().isCanceled(),
					"The timed-out renewal did not observe cancellation.");
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveSubscriptions(),
					"The timed-out renewal retained subscription quota.");
			Assertions.assertEquals(1,
					server.getDiagnostics().getActiveHandlerExecutions(),
					"The resistant renewal did not retain physical worker accounting.");
			Assertions.assertEquals(2, authorizations.get());
			Assertions.assertEquals(1, maximumActiveAuthorizers.get(),
					"One subscription overlapped authorization callbacks.");

			server.getSubscriptionReconciler().reconcileSubscriptions();
			Thread.sleep(350L);
			Assertions.assertEquals(0,
					server.getDiagnostics().getRequestHandlerQueueDepth(),
					"A replacement check queued behind the resistant callback.");
			Assertions.assertEquals(2, authorizations.get(),
					"A timed-out renewal scheduled an automatic retry.");
			Assertions.assertEquals(1L, metrics.maintenanceCount(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT));
			Assertions.assertEquals(1L, metrics.subscriptionClosedCount(
					McpStreamTerminationReason
							.SUBSCRIPTION_AUTHORIZATION_CHECK_FAILED));

			releaseRenewal.countDown();
			Assertions.assertTrue(renewalExited.await(5, TimeUnit.SECONDS),
					"The cancellation-resistant renewal did not exit.");
			Thread.sleep(100L);
			Assertions.assertEquals(2, authorizations.get(),
					"A replacement ran after the timed-out callback exited.");

			probeClient = listen(port, "\"renewal-timeout-quota-probe\"");
			assertSseHead(probeClient.readHead());
			Assertions.assertEquals(acknowledgment(
					"\"renewal-timeout-quota-probe\""),
					probeClient.readChunkText());
			Assertions.assertEquals(3, authorizations.get());
		} finally {
			releaseRenewal.countDown();
			if (probeClient != null)
				probeClient.closeWithReset();
			if (client != null)
				client.close();
			owner.close();
		}
	}

	@Test
	public void boundedAuthorizationCapacityRejectionDoesNotInvokeCallbackOrLeakQuota()
			throws Exception {
		AtomicInteger authorizations = new AtomicInteger();
		AtomicInteger activeAuthorizers = new AtomicInteger();
		AtomicInteger maximumActiveAuthorizers = new AtomicInteger();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		McpSubscriptionAuthorizer authorizer = (context, features) -> {
			int active = activeAuthorizers.incrementAndGet();
			maximumActiveAuthorizers.accumulateAndGet(active, Math::max);
			int invocation = authorizations.incrementAndGet();
			try {
				if (invocation == 1) {
					firstEntered.countDown();
					Assertions.assertTrue(releaseFirst.await(5, TimeUnit.SECONDS),
							"The first capacity authorizer was not released.");
				}
				return allowed();
			} finally {
				activeAuthorizers.decrementAndGet();
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		McpServer server = server(authorizer, Duration.ofSeconds(5),
				Duration.ofSeconds(10), Duration.ofSeconds(30), 3, 1, 1);
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient firstClient = null;
		McpChunkedHttpClient secondClient = null;
		McpChunkedHttpClient probeClient = null;

		try {
			owner.start();
			int port = boundPort(server);
			firstClient = listen(port, "\"capacity-first\"");
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS),
					"The first capacity authorizer did not enter.");
			secondClient = listen(port, "\"capacity-queued\"");
			awaitCondition(() -> server.getDiagnostics()
					.getRequestHandlerQueueDepth() == 1,
					"The second capacity authorizer was not queued.");

			try (McpChunkedHttpClient rejected = listen(port,
					"\"capacity-rejected\"")) {
				assertFixedFailure(rejected, 503);
			}
			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.CAPACITY_REJECTED,
					1);
			Assertions.assertEquals(1, authorizations.get(),
					"A capacity-rejected authorization invoked application code.");

			releaseFirst.countDown();
			assertSseHead(firstClient.readHead());
			Assertions.assertEquals(acknowledgment("\"capacity-first\""),
					firstClient.readChunkText());
			assertSseHead(secondClient.readHead());
			Assertions.assertEquals(acknowledgment("\"capacity-queued\""),
					secondClient.readChunkText());
			Assertions.assertEquals(2, authorizations.get());

			// The rejected request temporarily held the third partition slot. Keeping
			// both successful streams open makes this probe a direct quota-cleanup
			// assertion: a leaked reservation would reject it before authorization.
			probeClient = listen(port, "\"capacity-quota-probe\"");
			assertSseHead(probeClient.readHead());
			Assertions.assertEquals(acknowledgment("\"capacity-quota-probe\""),
					probeClient.readChunkText());
			Assertions.assertEquals(3, authorizations.get());
			Assertions.assertEquals(1, maximumActiveAuthorizers.get(),
					"The bounded dispatcher overlapped authorization callbacks.");
			Assertions.assertEquals(1L, metrics.maintenanceCount(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.CAPACITY_REJECTED));
		} finally {
			releaseFirst.countDown();
			if (probeClient != null)
				probeClient.closeWithReset();
			if (secondClient != null)
				secondClient.closeWithReset();
			if (firstClient != null)
				firstClient.closeWithReset();
			owner.close();
		}
	}

	private static void awaitIgnoringInterrupts(@NonNull CountDownLatch release) {
		boolean interrupted = false;
		while (release.getCount() != 0L)
			try {
				release.await(25, TimeUnit.MILLISECONDS);
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition,
			@NonNull String failureMessage) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L)
			Thread.sleep(1L);
		Assertions.assertTrue(condition.getAsBoolean(), failureMessage);
	}

	private static void assertFixedFailure(@NonNull McpChunkedHttpClient client,
			int expectedStatus) throws Exception {
		McpChunkedHttpClient.HttpResponseHead head = client.readHead();
		Assertions.assertEquals(expectedStatus, head.status(), head.raw());
		String body = client.readFixedBody(head);
		Assertions.assertFalse(body.contains(
				"notifications/subscriptions/acknowledged"), body);
	}

	@NonNull
	private static McpSubscriptionAuthorization allowed() {
		return allowed(Instant.now().plusSeconds(30));
	}

	@NonNull
	private static McpSubscriptionAuthorization allowed(
			@NonNull Instant validUntil) {
		return McpSubscriptionAuthorization.Allowed
				.withValidUntil(validUntil).build();
	}

	@NonNull
	private static McpServer server(
			@NonNull McpSubscriptionAuthorizer authorizer,
			@NonNull Duration authorizationTimeout,
			@NonNull Duration maximumAuthorizationDuration,
			@NonNull Duration maximumSubscriptionDuration,
			int maximumSubscriptionsPerPartition,
			int requestHandlerConcurrency,
			int requestHandlerQueueCapacity) {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(
						McpSubscriptionEventPublisher.fromInMemoryDefaults(),
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED))
				.build();
		McpEndpoint.Builder endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"subscription-authorization-timeout-test", "4.0.0")
						.build())
				.subscriptionConfig(subscriptions);
		endpoint.addResource(McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "Authorization timeout resource")
				.handler((request, read, features) ->
						McpCompleteResult.fromResourceOutput(
								McpResourceOutput.withContent(
										McpTextResourceContents.withUriAndText(
												read.getUri(), "test").build())
										.build()))
				.build());
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(endpoint.build())))
				.admissionController(context -> McpAdmissionDecision.accepted(
						McpAdmissionIdentity
								.withRateLimitPartitionKey("authorization-timeout-rate")
								.authorizationPartitionKey(
										"authorization-timeout-auth")
								.principal("authorization-timeout-principal")
								.build()))
				.subscriptionAuthorizer(authorizer)
				.maximumSubscriptionsPerPartition(maximumSubscriptionsPerPartition)
				.subscriptionAuthorizationTimeout(authorizationTimeout)
				.maximumSubscriptionAuthorizationDuration(
						maximumAuthorizationDuration)
				.maximumSubscriptionDuration(maximumSubscriptionDuration)
				.requestHandlerConcurrency(requestHandlerConcurrency)
				.requestHandlerQueueCapacity(requestHandlerQueueCapacity)
				.host(LOOPBACK)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector metricsCollector) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownTimeout(Duration.ofSeconds(2))
						.forcedShutdownTimeout(Duration.ofSeconds(1))
						.build())
				.build());
	}

	@NonNull
	private static McpChunkedHttpClient listen(int port,
			@NonNull String idJson) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
	}

	private static String acknowledgment(@NonNull String subscriptionIdJson) {
		return "data: {\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/subscriptions/acknowledged\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":"
				+ subscriptionIdJson + "},\"notifications\":{"
				+ "\"resourcesListChanged\":true}}}\n\n";
	}

	private static void assertSseHead(
			McpChunkedHttpClient.@NonNull HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
		Assertions.assertFalse(head.hasHeader("Content-Length"));
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	@ThreadSafe
	private static final class RecordingMetrics implements MetricsCollector {
		@NonNull
		private final List<@NonNull McpMetricsEvent> events =
				new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(event);
		}

		private void awaitMaintenance(
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Work work,
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome,
				int expectedCount) throws InterruptedException {
			awaitCondition(() -> maintenanceCount(work, outcome) >= expectedCount,
					"Missing subscription maintenance event " + work + '/' + outcome
							+ "; events=" + this.events);
		}

		private long maintenanceCount(
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Work work,
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome) {
			return this.events.stream()
					.filter(McpMetricsEvent.SubscriptionMaintenance.class::isInstance)
					.map(McpMetricsEvent.SubscriptionMaintenance.class::cast)
					.filter(event -> event.getWork() == work
							&& event.getOutcome() == outcome)
					.count();
		}

		private void awaitSubscriptionClosed(
				@NonNull McpStreamTerminationReason expectedReason)
				throws InterruptedException {
			awaitCondition(() -> subscriptionClosedCount(expectedReason) >= 1L,
					"Missing subscription close reason " + expectedReason
							+ "; events=" + this.events);
		}

		private long subscriptionClosedCount(
				@NonNull McpStreamTerminationReason expectedReason) {
			return this.events.stream()
					.filter(McpMetricsEvent.SubscriptionClosed.class::isInstance)
					.map(McpMetricsEvent.SubscriptionClosed.class::cast)
					.filter(event -> event.getReason() == expectedReason)
					.count();
		}

		private long subscriptionClosedCount() {
			return this.events.stream()
					.filter(McpMetricsEvent.SubscriptionClosed.class::isInstance)
					.count();
		}
	}
}
