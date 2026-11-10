/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Head;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.HeaderValue;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.RawClient;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Initial stream policies and baselines must share the catalog wait allowance. */
@Timeout(60)
class McpSubscriptionProtocolSaturationTests {
	@BeforeEach
	void beginRequestBudget() { RawClient.beginRequestBudget(); }

	@AfterEach
	void endRequestBudget() { RawClient.endRequestBudget(); }

	@Test
	void juneSessionGetAdmissionSharesCatalogAllowanceAndKeepsCancellationAvailable() throws Exception {
		assertDirectStage(Stage.SESSION_GET, McpProtocolVersion.V2025_06_18);
	}

	@Test
	void novemberSessionGetAdmissionLeavesDedicatedDeleteCleanupAvailable() throws Exception {
		assertDirectStage(Stage.SESSION_GET, McpProtocolVersion.V2025_11_25);
	}

	@Test
	void juneResourceAuthorizationSharesCatalogAllowanceAndKeepsCancellationAvailable() throws Exception {
		assertDirectStage(Stage.LEGACY_RESOURCE, McpProtocolVersion.V2025_06_18);
	}

	@Test
	void novemberResourceAuthorizationSharesCatalogAllowanceAndKeepsCancellationAvailable() throws Exception {
		assertDirectStage(Stage.LEGACY_RESOURCE, McpProtocolVersion.V2025_11_25);
	}

	@Test
	void modernInitialAuthorizationSharesCatalogAllowanceAndReleasesRejectedReservations() throws Exception {
		assertDirectStage(Stage.MODERN_AUTHORIZATION, McpProtocolVersion.V2025_06_18);
	}

	@Test
	void initialBaselinesRetainOneSharedAllowanceAfterAuthorizersReleaseTheirSlots() throws Exception {
		Harness harness = new Harness(McpProtocolVersion.V2025_06_18, true);
		List<RawClient> clients = new ArrayList<>();
		try {
			ShutdownResult result = Soklet.run(harness.config, () -> {
				try {
					List<String> sessions = harness.initializeSessions();
					List<RawClient> streams = new ArrayList<>();
					for (int index = 0; index < 3; index++) {
						RawClient stream = harness.open(Stage.MODERN_AUTHORIZATION, "initial-" + index, sessions.get(index + 1));
						streams.add(stream);
						clients.add(stream);
					}
					await(() -> harness.authorizations.get() == 3);
					// Three authorizers hold slots and protocol workers. The remaining
					// protocol worker admits one active tool and three queued tools.
					harness.openTools(sessions, clients);
					await(() -> harness.toolsEntered.get() == 1 && harness.queueDepth() == 3);
					harness.releaseAuthorizations.countDown();
					await(() -> harness.toolsEntered.get() == 4);
					await(() -> harness.settled(1, 2));
					assertEquals(1, harness.queueDepth(), "Only one initial baseline may wait behind the older tools.");
					assertEquals(2, harness.capacityRejections.get());

					// A baseline already owns the sole allowance. Neither another
					// initial authorization nor catalog work may acquire a second one.
					try (RawClient extra = harness.open(Stage.MODERN_AUTHORIZATION, "extra", sessions.get(4))) {
						assertJsonCapacity(read(extra));
					}
					assertEquals(3, harness.authorizations.get(), "Rejected initial authorization must not enter application code.");
					harness.assertCatalogRejected(sessions.get(4));
					harness.assertPing(sessions.get(4));
					harness.cancelFirstTool(sessions.get(0));
					assertTrue(harness.toolInterrupted.await(5, TimeUnit.SECONDS));
					await(() -> harness.queueDepth() == 0);
					int accepted = 0;
					for (RawClient stream : streams) {
						Head head = stream.readHead();
						if (head.status() == 200) {
							accepted++;
							assertTrue(head.header("Content-Type").startsWith("text/event-stream"));
						} else {
							assertJsonCapacity(new Response(head.status(), head.headers(), stream.readBody(head)));
						}
						stream.close();
					}
					assertEquals(1, accepted, "The waiting initial baseline must recover when a handler slot is released.");
					harness.releaseTools.countDown();
					await(() -> harness.server.getDiagnostics().getActiveSubscriptions() == 0
							&& harness.server.getDiagnostics().getActiveHandlerExecutions() == 0);
					harness.assertRecovery(Stage.MODERN_AUTHORIZATION, sessions.get(4), clients);
				} finally {
					harness.releaseAuthorizations.countDown();
					harness.releaseTools.countDown();
					close(clients);
				}
			});
			assertDrained(harness, result);
		} finally {
			harness.releaseAuthorizations.countDown();
			harness.releaseTools.countDown();
			close(clients);
		}
	}

	private static void assertDirectStage(Stage stage, McpProtocolVersion revision) throws Exception {
		Harness harness = new Harness(revision, false);
		List<RawClient> clients = new ArrayList<>();
		try {
			ShutdownResult result = Soklet.run(harness.config, () -> {
				try {
					List<String> sessions = harness.initializeSessions();
					harness.openTools(sessions, clients);
					await(() -> harness.toolsEntered.get() == 4);
					List<RawClient> opens = new ArrayList<>();
					for (int index = 0; index < 4; index++) {
						RawClient client = harness.open(stage, "open-" + index, sessions.get(index + 1));
						opens.add(client);
						clients.add(client);
					}
					await(() -> harness.settled(1, 3));
					assertEquals(1, harness.queueDepth(), "Four protocol workers allow one direct waiter across all stages.");
					assertEquals(3, harness.capacityRejections.get());
					assertEquals(0, harness.initialEntries(stage), "Queued and rejected policies must not enter application code.");
					harness.assertCatalogRejected(sessions.get(4));
					harness.assertPing(sessions.get(4));
					assertEquals(4, harness.server.getDiagnostics().getActiveHandlerExecutions());
					if (stage == Stage.SESSION_GET && revision == McpProtocolVersion.V2025_11_25) {
						try (RawClient delete = harness.httpControl("DELETE", sessions.get(0))) {
							assertEquals(204, read(delete).status(), "DELETE cleanup uses the dedicated owner-policy dispatcher.");
						}
						assertEquals(1, harness.deletions.get());
					} else {
						harness.cancelFirstTool(sessions.get(0));
					}
					assertTrue(harness.toolInterrupted.await(5, TimeUnit.SECONDS));
					await(() -> harness.initialEntries(stage) == 1 && harness.queueDepth() == 0);
					int accepted = 0;
					for (RawClient client : opens) {
						Head head = client.readHead();
						if (head.status() == 200) {
							accepted++;
							if (stage == Stage.LEGACY_RESOURCE)
								assertTrue(client.readBody(head).contains("\"result\":{}"));
							else
								assertTrue(head.header("Content-Type").startsWith("text/event-stream"));
						} else {
							Response response = new Response(head.status(), head.headers(), client.readBody(head));
							if (stage == Stage.MODERN_AUTHORIZATION)
								assertJsonCapacity(response);
							else {
								assertEquals(503, response.status());
								assertEquals("", response.body(), "Legacy session-capacity responses remain HTTP-only.");
								assertNull(response.header("Retry-After"));
							}
						}
						client.close();
					}
					assertEquals(1, accepted);
					harness.releaseTools.countDown();
					await(() -> harness.server.getDiagnostics().getActiveSubscriptions() == 0
							&& harness.server.getDiagnostics().getActiveHandlerExecutions() == 0);
					harness.assertRecovery(stage, sessions.get(4), clients);
				} finally {
					harness.releaseTools.countDown();
					close(clients);
				}
			});
			assertDrained(harness, result);
		} finally {
			harness.releaseTools.countDown();
			close(clients);
		}
	}

	private static void assertDrained(Harness harness, ShutdownResult result) {
		assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
		assertEquals(0, harness.server.getDiagnostics().getActiveHandlerExecutions());
		assertEquals(0, harness.queueDepth());
		assertEquals(0, harness.server.getDiagnostics().getActiveSubscriptions());
	}

	private static void assertJsonCapacity(Response response) {
		assertEquals(503, response.status(), response.body());
		assertTrue(response.body().contains("\"code\":-32603"), response.body());
		assertNull(response.header("Retry-After"));
	}

	private static Response read(RawClient client) throws Exception {
		Head head = client.readHead();
		return new Response(head.status(), head.headers(), client.readBody(head));
	}

	private static void close(List<RawClient> clients) throws Exception {
		for (RawClient client : clients)
			client.close();
	}

	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() - deadline < 0L, "Expected bounded subscription admission or physical drain was not reached.");
			Thread.sleep(2L);
		}
	}

	private enum Stage { SESSION_GET, LEGACY_RESOURCE, MODERN_AUTHORIZATION }

	private static final class Harness {
		private static final Set<McpProtocolVersion> ALL = Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
		private static final Set<McpProtocolVersion> LEGACY = Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25);
		private final McpProtocolVersion revision;
		private final AtomicInteger toolsEntered = new AtomicInteger();
		private final AtomicInteger gets = new AtomicInteger();
		private final AtomicInteger deletions = new AtomicInteger();
		private final AtomicInteger authorizations = new AtomicInteger();
		private final AtomicInteger capacityRejections = new AtomicInteger();
		private final CountDownLatch releaseTools = new CountDownLatch(1);
		private final CountDownLatch releaseAuthorizations = new CountDownLatch(1);
		private final CountDownLatch toolInterrupted = new CountDownLatch(1);
		private final McpServer server;
		private final SokletConfig config;

		private Harness(McpProtocolVersion revision, boolean gateAuthorizations) {
			this.revision = revision;
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("busy", ALL)
					.jsonObjectArguments().handler((context, arguments, features) -> {
						toolsEntered.incrementAndGet();
						try {
							releaseTools.await();
						} catch (InterruptedException exception) {
							toolInterrupted.countDown();
							throw exception;
						}
						return McpCompleteResult.fromToolText("done");
					}).build();
			McpResourceRegistration resource = McpResourceRegistration.withUriAndName(URI.create("test:///exact"), "Exact", ALL)
					.handler((context, read, features) -> McpCompleteResult.fromResourceOutput(McpResourceOutput.withContent(
							McpTextResourceContents.withUriAndText(read.getUri(), "item").build()).build())).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("subscription-saturation", "1").build(), ALL)
					.toolRegistrations(List.of(tool)).resourceRegistrations(List.of(resource)).sessionProtocolVersions(LEGACY)
					.subscriptionProtocolVersions(ALL).subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
							McpSubscriptionEventPublisher.fromInMemoryDefaults(), Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED,
									McpSubscriptionNotificationType.RESOURCE_UPDATED)).build()).build();
			server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.protocolExecutorConcurrency(4).requestHandlerConcurrency(4).requestHandlerQueueCapacity(16)
					.maximumSubscriptionsPerPartition(4).requestTimeout(Duration.ofSeconds(30))
					.subscriptionAuthorizationTimeout(Duration.ofSeconds(30)).subscriptionCatalogProjectionTimeout(Duration.ofSeconds(30))
					.sessionConfig(McpSessionConfig.withOwnerKeyResolver(identity -> "owner").anonymousSessionsAllowed(true)
							.transportAdmissionController((context, features) -> {
								if (context.getRequest().getHttpMethod() == HttpMethod.DELETE)
									deletions.incrementAndGet();
								else
									gets.incrementAndGet();
								return McpSessionTransportAdmissionDecision.accepted(McpAdmissionIdentity.anonymousInstance(),
										Instant.now().plusSeconds(30), context.getNotificationTypes());
							}).build())
					.subscriptionAuthorizer((context, features) -> {
						int entered = authorizations.incrementAndGet();
						if (gateAuthorizations && entered <= 3)
							releaseAuthorizations.await();
						return McpSubscriptionAuthorization.Allowed.fromValidUntil(Instant.now().plusSeconds(30));
					}).build();
			config = SokletConfig.withMcpServer(server).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.jvmShutdownHookEnabled(false).lifecycleObservers(List.of()).metricsCollector(new MetricsCollector() {
						@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
							if (event instanceof McpMetricsEvent.HandlerCapacityRejected)
								capacityRejections.incrementAndGet();
						}
					}).lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
		}

		private int queueDepth() { return server.getDiagnostics().getRequestHandlerQueueDepth(); }

		private boolean settled(int queued, int rejected) {
			return queueDepth() == queued && capacityRejections.get() == rejected
					|| queueDepth() > queued || capacityRejections.get() > rejected;
		}

		private int initialEntries(Stage stage) { return stage == Stage.SESSION_GET ? gets.get() : authorizations.get(); }

		private int port() { return server.getDiagnostics().getBoundAddress().orElseThrow().getPort(); }

		private List<String> initializeSessions() throws Exception {
			List<String> sessions = new ArrayList<>();
			for (int index = 0; index < 5; index++) {
				String body = "{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
						+ revision.getWireValue() + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
				try (RawClient client = new RawClient(port(), "POST", "/mcp", body, List.of())) {
					Response response = read(client);
					assertEquals(200, response.status(), response.body());
					assertNotNull(response.header("Mcp-Session-Id"));
					sessions.add(response.header("Mcp-Session-Id"));
				}
			}
			return sessions;
		}

		private void openTools(List<String> sessions, List<RawClient> clients) throws Exception {
			for (int index = 0; index < 4; index++)
				clients.add(rpc(revision, "tools/call", "tool-" + index, "\"name\":\"busy\",\"arguments\":{}", sessions.get(index)));
		}

		private RawClient open(Stage stage, String id, String session) throws Exception {
			return switch (stage) {
				case SESSION_GET -> httpControl("GET", session);
				case LEGACY_RESOURCE -> rpc(revision, "resources/subscribe", id, "\"uri\":\"test:///exact\"", session);
				case MODERN_AUTHORIZATION -> rpc(McpProtocolVersion.V2026_07_28, "subscriptions/listen", id,
						"\"notifications\":{\"toolsListChanged\":true}", null);
			};
		}

		private RawClient httpControl(String method, String session) throws Exception {
			return new RawClient(port(), method, "/mcp", "", List.of(new HeaderValue("MCP-Protocol-Version", revision.getWireValue()),
					new HeaderValue("Mcp-Session-Id", session)));
		}

		private RawClient rpc(McpProtocolVersion version, String method, String id, String fields, String session) throws Exception {
			boolean modern = version == McpProtocolVersion.V2026_07_28;
			String metadata = modern ? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" : "";
			String params = fields + (modern && !fields.isEmpty() ? "," : "") + metadata;
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method + "\",\"params\":{" + params + "}}";
			List<HeaderValue> headers = new ArrayList<>();
			headers.add(new HeaderValue("MCP-Protocol-Version", version.getWireValue()));
			if (session != null) headers.add(new HeaderValue("Mcp-Session-Id", session));
			if (modern) {
				headers.add(new HeaderValue("Mcp-Method", method));
				if (method.equals("tools/call")) headers.add(new HeaderValue("Mcp-Name", "busy"));
			}
			return new RawClient(port(), "POST", "/mcp", body, headers);
		}

		private void assertCatalogRejected(String session) throws Exception {
			try (RawClient catalog = rpc(revision, "tools/list", "catalog", "", session)) {
				assertJsonCapacity(read(catalog));
			}
		}

		private void assertPing(String session) throws Exception {
			try (RawClient ping = rpc(revision, "ping", "ping", "", session)) {
				assertEquals(200, read(ping).status());
			}
		}

		private void cancelFirstTool(String session) throws Exception {
			String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":\"tool-0\"}}";
			try (RawClient cancel = new RawClient(port(), "POST", "/mcp", body, List.of(
					new HeaderValue("MCP-Protocol-Version", revision.getWireValue()), new HeaderValue("Mcp-Session-Id", session)))) {
				assertEquals(202, read(cancel).status());
			}
		}

		private void assertRecovery(Stage stage, String session, List<RawClient> clients) throws Exception {
			for (int index = 0; index < (stage == Stage.MODERN_AUTHORIZATION ? 4 : 1); index++) {
				RawClient recovered = open(stage, "recovered-" + index, session);
				clients.add(recovered);
				Head head = recovered.readHead();
				assertEquals(200, head.status(), "Rejected and completed requests must return their admission reservations.");
				if (stage == Stage.LEGACY_RESOURCE)
					assertTrue(recovered.readBody(head).contains("\"result\":{}"));
			}
			if (stage == Stage.MODERN_AUTHORIZATION)
				assertEquals(4, server.getDiagnostics().getActiveSubscriptions(), "All four partition reservations must be reusable.");
		}
	}
}
