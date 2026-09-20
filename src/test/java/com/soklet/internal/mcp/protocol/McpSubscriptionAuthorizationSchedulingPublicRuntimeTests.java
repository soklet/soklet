/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import com.soklet.McpAdmissionController;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpMetricsEvent;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpServer;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionAuthorizer;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpToolRegistration;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic scheduling-order coverage for renewal/reconciliation races.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(60)
public class McpSubscriptionAuthorizationSchedulingPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(10);

	@AfterEach
	public void resetTestHooks() {
		McpHttpServerRuntime.setSubscriptionAuthorizationSchedulingTestHooks(null);
	}

	@Test
	@Timeout(70)
	public void renewalReservedAfterReconciliationFenceUsesReconciliationSemantics()
			throws Exception {
		CountDownLatch renewalReadyToSchedule = new CountDownLatch(1);
		CountDownLatch releaseRenewalSchedule = new CountDownLatch(1);
		CountDownLatch reconciliationReadyToSchedule = new CountDownLatch(1);
		CountDownLatch releaseReconciliationSchedule = new CountDownLatch(1);
		CountDownLatch racedCheckEntered = new CountDownLatch(1);
		McpHttpServerRuntime.setSubscriptionAuthorizationSchedulingTestHooks(
				new McpHttpServerRuntime.SubscriptionAuthorizationSchedulingTestHooks() {
					@Override
					public void beforeRenewalScheduling() {
						renewalReadyToSchedule.countDown();
						await(releaseRenewalSchedule,
								"The renewal scheduler was not released.");
					}

					@Override
					public void beforeReconciliationScheduling() {
						reconciliationReadyToSchedule.countDown();
						await(releaseReconciliationSchedule,
								"The reconciliation scheduler was not released.");
					}
				});

		AtomicInteger authorizations = new AtomicInteger();
		RecordingMetrics metrics = new RecordingMetrics();
		McpServer server = server((context, features) -> {
			int invocation = authorizations.incrementAndGet();
			if (invocation == 1)
				return McpSubscriptionAuthorization.Allowed.fromValidUntil(
						Instant.now().plus(Duration.ofMinutes(5)));
			if (invocation == 2) {
				racedCheckEntered.countDown();
				return McpSubscriptionAuthorization.deniedInstance();
			}
			throw new AssertionError("Unexpected authorization invocation " + invocation);
		});
		Soklet owner = managedSoklet(server, metrics);
		ExecutorService reconciliationExecutor = Executors.newSingleThreadExecutor();
		McpChunkedHttpClient client = null;

		try {
			owner.start();
			client = listen(boundPort(server));
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment(), client.readChunkText());
			await(renewalReadyToSchedule,
					"The automatic renewal did not reach its scheduling boundary.");

			Future<?> reconciliation = reconciliationExecutor.submit(() -> server
					.getSubscriptionReconciler().reconcileSubscriptions());
			await(reconciliationReadyToSchedule,
					"Reconciliation did not establish its delivery fence.");
			releaseRenewalSchedule.countDown();
			await(racedCheckEntered,
					"The renewal-side scheduler did not reserve the raced check.");
			releaseReconciliationSchedule.countDown();
			await(reconciliation,
					"The reconciliation call did not return.");

			metrics.awaitMaintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.RECONCILIATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED);
			metrics.awaitSubscriptionClosed(McpStreamTerminationReason
					.SUBSCRIPTION_RECONCILIATION_FAILED);
			Assertions.assertEquals(2, authorizations.get());
		} finally {
			releaseRenewalSchedule.countDown();
			releaseReconciliationSchedule.countDown();
			if (client != null)
				client.closeWithReset();
			owner.close();
			reconciliationExecutor.shutdownNow();
			Assertions.assertTrue(reconciliationExecutor.awaitTermination(
					WAIT.toNanos(), TimeUnit.NANOSECONDS));
		}
	}

	@NonNull
	private static McpServer server(
			@NonNull McpSubscriptionAuthorizer authorizer) {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(publisher,
						Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"subscription-authorization-scheduling-test", "4.0.0")
						.build())
				.toolRegistrations(java.util.List.of(McpToolRegistration.withName("scheduling.probe")
						.jsonObjectArguments()
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("unused"))
						.build()))
				.subscriptionConfig(subscriptions)
				.build();
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.subscriptionAuthorizer(authorizer)
				.subscriptionAuthorizationTimeout(Duration.ofSeconds(5))
				.maximumSubscriptionAuthorizationDuration(Duration.ofSeconds(4))
				.maximumSubscriptionDuration(Duration.ofMinutes(5))
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector metrics) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metrics)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownTimeout(Duration.ofSeconds(2))
						.forcedShutdownTimeout(Duration.ofSeconds(1))
						.build())
				.build());
	}

	@NonNull
	private static McpChunkedHttpClient listen(int port) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"schedule-race\","
				+ "\"method\":\"subscriptions/listen\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"toolsListChanged\":true}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
	}

	@NonNull
	private static String acknowledgment() {
		return "data: {\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/subscriptions/acknowledged\","
				+ "\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/subscriptionId\":\"schedule-race\"},"
				+ "\"notifications\":{\"toolsListChanged\":true}}}\n\n";
	}

	private static void assertSseHead(
			McpChunkedHttpClient.@NonNull HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static void await(@NonNull CountDownLatch latch,
			@NonNull String timeoutMessage) {
		try {
			Assertions.assertTrue(latch.await(WAIT.toNanos(), TimeUnit.NANOSECONDS),
					timeoutMessage);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static void await(@NonNull Future<?> future,
			@NonNull String timeoutMessage) {
		try {
			future.get(WAIT.toNanos(), TimeUnit.NANOSECONDS);
		} catch (Exception exception) {
			throw new AssertionError(timeoutMessage, exception);
		}
	}

	private static final class RecordingMetrics implements MetricsCollector {
		@NonNull
		private final BlockingQueue<McpMetricsEvent.@NonNull SubscriptionMaintenance>
				maintenanceEvents = new LinkedBlockingQueue<>();
		@NonNull
		private final BlockingQueue<McpMetricsEvent.@NonNull SubscriptionClosed>
				closedEvents = new LinkedBlockingQueue<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			if (event instanceof McpMetricsEvent.SubscriptionMaintenance maintenance)
				this.maintenanceEvents.add(maintenance);
			else if (event instanceof McpMetricsEvent.SubscriptionClosed closed)
				this.closedEvents.add(closed);
		}

		private void awaitMaintenance(
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Work work,
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome) {
			long deadline = System.nanoTime() + WAIT.toNanos();
			while (System.nanoTime() - deadline < 0L) {
				McpMetricsEvent.SubscriptionMaintenance event;
				try {
					event = this.maintenanceEvents.poll(10L, TimeUnit.MILLISECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
				if (event != null && event.getWork() == work
						&& event.getOutcome() == outcome)
					return;
			}
			throw new AssertionError(
					"Missing subscription maintenance event " + work + '/' + outcome);
		}

		private void awaitSubscriptionClosed(
				@NonNull McpStreamTerminationReason expectedReason) {
			long deadline = System.nanoTime() + WAIT.toNanos();
			while (System.nanoTime() - deadline < 0L) {
				McpMetricsEvent.SubscriptionClosed event;
				try {
					event = this.closedEvents.poll(10L, TimeUnit.MILLISECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
				if (event != null && event.getReason() == expectedReason)
					return;
			}
			throw new AssertionError(
					"Missing subscription close reason " + expectedReason);
		}
	}
}
