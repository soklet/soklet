/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import com.soklet.CancelationToken;
import com.soklet.CorsAuthorizer;
import com.soklet.LifecyclePolicy;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpMetricsEvent;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionAuthorizationContext;
import com.soklet.McpSubscriptionAuthorizer;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTextResourceContents;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.SokletStatus;
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
 * Real-socket terminal-precedence coverage for in-flight subscription renewal.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpSubscriptionAuthorizationTerminationPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final URI RESOURCE_URI =
			URI.create("test://subscription/authorization/termination");

	@Test
	public void clientDisconnectCancelsResistantRenewalWithoutAuthRevival()
			throws Exception {
		CancellationResistantRenewal renewal =
				new CancellationResistantRenewal();
		RecordingMetrics metrics = new RecordingMetrics();
		McpServer server = server(renewal.authorizer());
		Soklet owner = managedSoklet(server, metrics);
		McpChunkedHttpClient client = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"disconnect-renewal\"");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"disconnect-renewal\""),
					client.readChunkText());
			renewal.awaitEntered();
			assertRenewalIsPhysicallyActive(server, renewal);

			client.closeWithReset();
			client = null;
			renewal.awaitCanceled();
			metrics.awaitSubscriptionClosed(
					McpStreamTerminationReason.CLIENT_DISCONNECTED);
			awaitCondition(() -> server.getDiagnostics().getActiveSubscriptions() == 0,
					"The disconnected subscription remained active.");
			Assertions.assertEquals(1,
					server.getDiagnostics().getActiveHandlerExecutions(),
					"Physical worker accounting ended before the callback exited.");

			renewal.release();
			renewal.awaitExited();
			assertTerminalCleanup(server, renewal, metrics,
					McpStreamTerminationReason.CLIENT_DISCONNECTED);
		} finally {
			renewal.release();
			if (client != null)
				client.closeWithReset();
			owner.close();
		}
	}

	@Test
	public void serverShutdownCancelsResistantRenewalWithoutAuthRevival()
			throws Exception {
		CancellationResistantRenewal renewal =
				new CancellationResistantRenewal();
		RecordingMetrics metrics = new RecordingMetrics();
		McpServer server = server(renewal.authorizer());
		Soklet owner = managedSoklet(server, metrics);
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();
		McpChunkedHttpClient client = null;
		Thread stopper = null;

		try {
			owner.start();
			client = listen(boundPort(server), "\"shutdown-renewal\"");
			assertSseHead(client.readHead());
			Assertions.assertEquals(acknowledgment("\"shutdown-renewal\""),
					client.readChunkText());
			renewal.awaitEntered();
			assertRenewalIsPhysicallyActive(server, renewal);

			stopper = new Thread(() -> {
				try {
					owner.close();
				} catch (Throwable throwable) {
					stopFailure.set(throwable);
				}
			}, "mcp-subscription-authorization-stop");
			stopper.start();
			renewal.awaitCanceled();
			Assertions.assertEquals(1,
					server.getDiagnostics().getActiveHandlerExecutions(),
					"Shutdown forgot the cancellation-resistant callback worker.");

			renewal.release();
			renewal.awaitExited();
			stopper.join(TimeUnit.SECONDS.toMillis(10));
			Assertions.assertFalse(stopper.isAlive(),
					"Server shutdown did not finish after the callback exited.");
			Assertions.assertNull(stopFailure.get(),
					"Server shutdown failed unexpectedly.");
			Assertions.assertEquals(SokletStatus.CLOSED, owner.getStatus());
			metrics.awaitSubscriptionClosed(
					McpStreamTerminationReason.SERVER_STOPPING);
			assertTerminalCleanup(server, renewal, metrics,
					McpStreamTerminationReason.SERVER_STOPPING);
		} finally {
			renewal.release();
			if (client != null)
				client.closeWithReset();
			owner.close();
			if (stopper != null && stopper.isAlive())
				stopper.join(TimeUnit.SECONDS.toMillis(10));
		}
	}

	private static void assertRenewalIsPhysicallyActive(@NonNull McpServer server,
			@NonNull CancellationResistantRenewal renewal) {
		Assertions.assertTrue(renewal.context().getPreviousValidUntil().isPresent(),
				"The blocked callback was not an established-stream renewal.");
		Assertions.assertEquals(1,
				server.getDiagnostics().getActiveSubscriptions());
		Assertions.assertEquals(1,
				server.getDiagnostics().getActiveHandlerExecutions());
		Assertions.assertEquals(1, renewal.maximumActiveCallbacks(),
				"One subscription ran overlapping authorization callbacks.");
	}

	private static void assertTerminalCleanup(@NonNull McpServer server,
			@NonNull CancellationResistantRenewal renewal,
			@NonNull RecordingMetrics metrics,
			@NonNull McpStreamTerminationReason expectedReason) throws Exception {
		awaitCondition(() -> server.getDiagnostics().getActiveSubscriptions() == 0
				&& server.getDiagnostics().getActiveHandlerExecutions() == 0,
				"Subscription or callback-worker accounting did not drain.");
		Assertions.assertEquals(2, renewal.invocations(),
				"A late result scheduled or revived authorization work.");
		Assertions.assertEquals(1, renewal.maximumActiveCallbacks(),
				"One subscription ran overlapping authorization callbacks.");
		Assertions.assertEquals(1L, metrics.maintenanceCount(
				McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
				McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED),
				"The late Allowed result became a successful renewed grant.");
		Assertions.assertEquals(List.of(expectedReason),
				metrics.subscriptionCloseReasons(),
				"Authorization failure overrode the established terminal owner.");
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition,
			@NonNull String failureMessage) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L)
			Thread.sleep(1L);
		Assertions.assertTrue(condition.getAsBoolean(), failureMessage);
	}

	@NonNull
	private static McpServer server(@NonNull McpSubscriptionAuthorizer authorizer) {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(
						McpSubscriptionEventPublisher.fromInMemoryDefaults(),
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED))
				.build();
		McpEndpoint.Builder endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"subscription-authorization-terminal-test", "4.0.0")
						.build())
				.subscriptionConfig(subscriptions)
				.addResource(McpResourceRegistration
						.withUriAndName(RESOURCE_URI,
								"Subscription authorization terminal resource")
						.handler((request, read, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.withContent(
												McpTextResourceContents
														.withUriAndText(read.getUri(), "test")
														.build())
												.build()))
						.build());
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(endpoint.build())))
				.subscriptionAuthorizer(authorizer)
				.maximumSubscriptionsPerPartition(1)
				.subscriptionAuthorizationTimeout(Duration.ofSeconds(5))
				.maximumSubscriptionAuthorizationDuration(Duration.ofSeconds(10))
				.maximumSubscriptionDuration(Duration.ofSeconds(30))
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
						.gracefulShutdownTimeout(Duration.ofSeconds(5))
						.forcedShutdownTimeout(Duration.ofSeconds(2))
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

	private static final class CancellationResistantRenewal {
		private final AtomicInteger invocations = new AtomicInteger();
		private final AtomicInteger activeCallbacks = new AtomicInteger();
		private final AtomicInteger maximumActiveCallbacks = new AtomicInteger();
		private final AtomicReference<CancelationToken> token =
				new AtomicReference<>();
		private final AtomicReference<McpSubscriptionAuthorizationContext> context =
				new AtomicReference<>();
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final CountDownLatch exited = new CountDownLatch(1);

		@NonNull
		private McpSubscriptionAuthorizer authorizer() {
			return (context, features) -> {
				int active = this.activeCallbacks.incrementAndGet();
				this.maximumActiveCallbacks.accumulateAndGet(active, Math::max);
				int invocation = this.invocations.incrementAndGet();
				try {
					if (invocation == 1)
						return McpSubscriptionAuthorization.Allowed.fromValidUntil(
								Instant.now().plusSeconds(4));
					if (invocation == 2) {
						this.context.set(context);
						this.token.set(features.getCancelationToken());
						this.entered.countDown();
						while (this.release.getCount() != 0L) {
							try {
								this.release.await(25, TimeUnit.MILLISECONDS);
							} catch (InterruptedException ignored) {
								// Deliberately resist cooperative/thread cancellation.
							}
						}
						return McpSubscriptionAuthorization.Allowed
								.withValidUntil(Instant.now().plusSeconds(30))
								.applicationContext(new Object())
								.build();
					}
					throw new AssertionError(
							"Unexpected authorization invocation " + invocation);
				} finally {
					this.activeCallbacks.decrementAndGet();
					if (invocation == 2)
						this.exited.countDown();
				}
			};
		}

		private void awaitEntered() throws InterruptedException {
			Assertions.assertTrue(this.entered.await(10, TimeUnit.SECONDS),
					"The automatic renewal did not enter.");
		}

		private void awaitCanceled() throws InterruptedException {
			awaitCondition(() -> this.token.get() != null
					&& this.token.get().isCanceled(),
					"The terminal owner did not cancel the renewal token.");
		}

		private void awaitExited() throws InterruptedException {
			Assertions.assertTrue(this.exited.await(5, TimeUnit.SECONDS),
					"The renewal callback did not exit after release.");
		}

		private void release() {
			this.release.countDown();
		}

		@NonNull
		private McpSubscriptionAuthorizationContext context() {
			return this.context.get();
		}

		private int invocations() {
			return this.invocations.get();
		}

		private int maximumActiveCallbacks() {
			return this.maximumActiveCallbacks.get();
		}
	}

	@ThreadSafe
	private static final class RecordingMetrics implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(event);
		}

		private void awaitSubscriptionClosed(
				@NonNull McpStreamTerminationReason expectedReason)
				throws InterruptedException {
			awaitCondition(() -> subscriptionCloseReasons().contains(expectedReason),
					"Missing subscription close reason " + expectedReason
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

		@NonNull
		private List<@NonNull McpStreamTerminationReason>
				subscriptionCloseReasons() {
			return this.events.stream()
					.filter(McpMetricsEvent.SubscriptionClosed.class::isInstance)
					.map(McpMetricsEvent.SubscriptionClosed.class::cast)
					.map(McpMetricsEvent.SubscriptionClosed::getReason)
					.toList();
		}
	}
}
