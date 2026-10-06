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
import com.soklet.McpProtocolVersion;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionAuthorizer;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTextResourceContents;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@NotThreadSafe
@Timeout(30)
public class McpSubscriptionResourceCatchUpPublicRuntimeTests {
	private static final URI RESOURCE = URI.create("test://catch-up/resource");
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(McpProtocolVersion.V2026_07_28);

	@AfterEach
	public void resetHooks() { McpRequestSseStream.setTestHooks(null); }

	@Test
	public void resourceInvalidationsDuringReconciliationCatchUpUnderNewGrant() throws Exception {
		catchUpAfterReconciliation(true);
	}

	@Test
	public void resourceInvalidationsDuringDeniedReconciliationNeverEscapeFence() throws Exception {
		catchUpAfterReconciliation(false);
	}

	private void catchUpAfterReconciliation(boolean allow) throws Exception {
		CountDownLatch reauthorizationEntered = new CountDownLatch(1);
		CountDownLatch releaseReauthorization = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		McpSubscriptionEventPublisher publisher = McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, (authorizationContext, invocationFeatures) -> {
			if (calls.incrementAndGet() > 1) {
				reauthorizationEntered.countDown();
				await(releaseReauthorization);
				if (!allow) return McpSubscriptionAuthorization.deniedInstance();
			}
			return McpSubscriptionAuthorization.Allowed.withValidUntil(Instant.now().plusSeconds(300))
					.applicationContext(new Object()).build();
		}, Duration.ofMinutes(5));
		Soklet owner = managed(server, new MetricsCollector() {});
		try {
			owner.start();
			try (McpChunkedHttpClient client = listen(server)) {
				assertAcknowledged(client);
				server.getSubscriptionReconciler().reconcileSubscriptions();
				await(reauthorizationEntered);
				for (int index = 0; index < 20; index++) {
					publisher.publishResourcesListChanged();
					publisher.publishResourceUpdated(RESOURCE);
					publisher.publishResourceUpdated(URI.create("test://catch-up/unselected"));
				}
				releaseReauthorization.countDown();
				if (allow) {
					Assertions.assertEquals(listChanged(), client.readChunkText());
					Assertions.assertEquals(resourceUpdated(), client.readChunkText());
					publisher.publishResourcesListChanged();
					Assertions.assertEquals(listChanged(), client.readChunkText(),
							"Repeated fenced invalidations must coalesce; an unselected URI must never be sent.");
				} else {
					String terminal = client.readChunkText();
					Assertions.assertTrue(terminal.contains("\"resultType\":\"complete\""), terminal);
					Assertions.assertFalse(terminal.contains("notifications/resources"), terminal);
					Assertions.assertNull(client.readChunk(),
							"Denied reconciliation must discard fenced hints and end cleanly.");
				}
			}
		} finally {
			releaseReauthorization.countDown();
			owner.close();
		}
	}

	@Test
	public void renewalReplacingRegistrationDoesNotLoseResourceOffer() throws Exception {
		CountDownLatch offerEntered = new CountDownLatch(1);
		CountDownLatch releaseOffer = new CountDownLatch(1);
		CountDownLatch renewed = new CountDownLatch(1);
		AtomicInteger offers = new AtomicInteger();
		AtomicInteger authorizations = new AtomicInteger();
		McpRequestSseStream.setTestHooks(new McpRequestSseStream.TestHooks() {
			@Override public void beforeTerminalReservation() {}
			@Override public void beforeCoalescingMessageOffer() {
				if (offers.incrementAndGet() == 1) { offerEntered.countDown(); await(releaseOffer); }
			}
		});
		McpSubscriptionEventPublisher publisher = McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpServer server = server(publisher, (authorizationContext, invocationFeatures) -> {
			authorizations.incrementAndGet();
			return McpSubscriptionAuthorization.Allowed.fromValidUntil(Instant.now().plusSeconds(300));
		}, Duration.ofSeconds(4));
		MetricsCollector metrics = new MetricsCollector() {
			@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				if (event instanceof McpMetricsEvent.SubscriptionMaintenance maintenance
						&& maintenance.getWork() == McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION
						&& maintenance.getOutcome() == McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED
						&& authorizations.get() >= 2) renewed.countDown();
			}
		};
		Soklet owner = managed(server, metrics);
		try {
			owner.start();
			try (McpChunkedHttpClient client = listen(server)) {
				assertAcknowledged(client);
				publisher.publishResourceUpdated(RESOURCE);
				await(offerEntered);
				await(renewed);
				releaseOffer.countDown();
				Assertions.assertEquals(resourceUpdated(), client.readChunkText());
				Assertions.assertEquals(1, offers.get(), "A renewed grant must preserve the still-authorized resource invalidation.");
			}
		} finally { releaseOffer.countDown(); owner.close(); }
	}

	private static McpServer server(McpSubscriptionEventPublisher publisher,
			McpSubscriptionAuthorizer authorizer, Duration authorizationDuration) {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(publisher, Set.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
						McpSubscriptionNotificationType.RESOURCE_UPDATED)).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("resource-catch-up", "4.0.0").build(), VERSIONS)
				.resourceRegistrations(List.of(McpResourceRegistration.withUriAndName(RESOURCE, "Resource", VERSIONS)
						.handler((requestContext, resourceReadContext, invocationFeatures) ->
								McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(
										McpTextResourceContents.withUriAndText(RESOURCE, "unused").build()).build())).build()))
				.subscriptionProtocolVersions(VERSIONS).subscriptionConfig(subscriptions).build();
		return McpServer.withPort(0).host("127.0.0.1")
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(requestContext -> McpRateLimitDecision.allowed())
				.subscriptionAuthorizer(authorizer).subscriptionAuthorizationTimeout(Duration.ofSeconds(5))
				.maximumSubscriptionAuthorizationDuration(authorizationDuration)
				.maximumSubscriptionDuration(Duration.ofMinutes(5))
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance()).allowedHosts(Set.of("127.0.0.1")).build();
	}

	private static Soklet managed(McpServer server, MetricsCollector metrics) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).metricsCollector(metrics)
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(2))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
	}

	private static McpChunkedHttpClient listen(McpServer server) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"catch-up\",\"method\":\"subscriptions/listen\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},\"notifications\":{\"resourcesListChanged\":true,"
				+ "\"resourceSubscriptions\":[\"" + RESOURCE + "\"]}}}";
		return McpChunkedHttpClient.postMcpMessage(server.getDiagnostics().getBoundAddress().orElseThrow().getPort(),
				body, List.of(new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", "2026-07-28"),
						new McpChunkedHttpClient.RequestHeader("Mcp-Method", "subscriptions/listen")));
	}

	private static void assertAcknowledged(McpChunkedHttpClient client) throws Exception {
		Assertions.assertEquals(200, client.readHead().status());
		Assertions.assertTrue(client.readChunkText().contains("notifications/subscriptions/acknowledged"));
	}

	private static String listChanged() {
		return "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/resources/list_changed\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/subscriptionId\":\"catch-up\"}}}\n\n";
	}

	private static String resourceUpdated() {
		return "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/resources/updated\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/subscriptionId\":\"catch-up\"},\"uri\":\"" + RESOURCE + "\"}}\n\n";
	}

	private static void await(CountDownLatch latch) {
		try { Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS), "The test barrier timed out."); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
	}
}
