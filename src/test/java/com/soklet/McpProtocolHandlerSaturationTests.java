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

import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Head;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.HeaderValue;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.RawClient;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

/**
 * Real sockets keep handler work blocked while testing independent protocol progress.
 * <p>
 * Catalog and catalog-policy work holds its protocol worker while it waits for a
 * handler slot. A quarter of the protocol pool may wait that way; further requests
 * need an immediately free slot and otherwise receive the capacity response.
 */
@Timeout(60)
class McpProtocolHandlerSaturationTests {
	@BeforeEach
	void beginRequestBudget() { RawClient.beginRequestBudget(); }

	@AfterEach
	void endRequestBudget() { RawClient.endRequestBudget(); }

	@Test
	void june2025CatalogOverloadKeepsDefaultControlTrafficAndCancellationAvailable() throws Exception {
		legacyCatalogOverload(McpProtocolVersion.V2025_06_18);
	}

	@Test
	void november2025CatalogOverloadKeepsDefaultControlTrafficAndCancellationAvailable() throws Exception {
		legacyCatalogOverload(McpProtocolVersion.V2025_11_25);
	}

	@Test
	void modernCatalogPolicyOverloadBoundsWaitersAndKeepsDiscoveryAvailable() throws Exception {
		// Four protocol workers allow one catalog-policy waiter.
		Harness harness = new Harness(4, 4, true, false);
		List<RawClient> clients = new ArrayList<>();
		try {
			ShutdownResult result = Soklet.run(harness.config, () -> {
				try {
					for (int index = 0; index < harness.concurrency; index++) {
						clients.add(harness.open(McpProtocolVersion.V2026_07_28, "tools/call", "active-" + index, null));
						int expected = index + 1;
						await(() -> harness.entered.get() == expected);
					}
					List<RawClient> excess = new ArrayList<>();
					for (int index = 0; index < 8; index++) {
						RawClient client = harness.open(McpProtocolVersion.V2026_07_28, "tools/call", "excess-" + index, null);
						excess.add(client);
						clients.add(client);
					}
					// The unbounded implementation queues all eight and then stalls discovery.
					await(() -> harness.settled(1, 7));
					try (RawClient discovery = harness.open(McpProtocolVersion.V2026_07_28, "server/discover", "control", null)) {
						assertEquals(200, read(discovery).status());
					}
					assertEquals(harness.concurrency, harness.server.getDiagnostics().getActiveHandlerExecutions());
					assertEquals(1, harness.queueDepth(), "Exactly one catalog-policy caller may wait.");
					assertEquals(7, harness.capacityRejections.get());
					assertEquals(harness.concurrency, harness.entered.get(), "Waiting or rejected policy work must not enter a tool.");
					clients.get(0).close();
					assertTrue(harness.interrupted.await(5, TimeUnit.SECONDS), "Closing the active modern RPC must interrupt its handler.");
					// The freed slot serves the waiting policy step, and its tool then runs.
					await(() -> harness.entered.get() == harness.concurrency + 1 && harness.queueDepth() == 0);
					harness.release.countDown();
					int served = 0;
					for (RawClient client : excess) {
						Response response = read(client);
						if (response.status() == 200)
							served++;
						else
							assertCapacity(response);
					}
					assertEquals(1, served, "The waiting call must complete once capacity returns.");
					try (RawClient recovered = harness.open(McpProtocolVersion.V2026_07_28, "tools/list", "recovered", null)) {
						assertEquals(200, read(recovered).status());
					}
				} finally {
					harness.release.countDown();
					close(clients);
				}
			});
			assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
		} finally {
			harness.release.countDown();
			close(clients);
		}
	}

	@Test
	void protocolPoolBelowFourRejectsCatalogPolicyOverloadWithoutWaiting() throws Exception {
		Harness harness = new Harness(4, 3, true, false);
		List<RawClient> clients = new ArrayList<>();
		try {
			ShutdownResult result = Soklet.run(harness.config, () -> {
				try {
					for (int index = 0; index < harness.concurrency; index++) {
						clients.add(harness.open(McpProtocolVersion.V2026_07_28, "tools/call", "active-" + index, null));
						int expected = index + 1;
						await(() -> harness.entered.get() == expected);
					}
					List<RawClient> rejected = new ArrayList<>();
					for (int index = 0; index < 8; index++) {
						RawClient client = harness.open(McpProtocolVersion.V2026_07_28, "tools/call", "rejected-" + index, null);
						rejected.add(client);
						clients.add(client);
					}
					await(() -> harness.settled(0, 8));
					for (RawClient client : rejected)
						assertCapacity(read(client));
					assertEquals(0, harness.queueDepth(), "A pool this small has no worker to spare for waiting.");
					assertEquals(harness.concurrency, harness.entered.get(), "Rejected policy work must not enter a tool.");
					try (RawClient discovery = harness.open(McpProtocolVersion.V2026_07_28, "server/discover", "control", null)) {
						assertEquals(200, read(discovery).status());
					}
				} finally {
					harness.release.countDown();
					close(clients);
				}
			});
			assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
		} finally {
			harness.release.countDown();
			close(clients);
		}
	}

	/**
	 * Subscription maintenance may hold half the protocol pool while its catalog
	 * projections wait for handler slots. Catalog-policy waiters must leave workers
	 * free on top of that, which a waiter bound of half the pool would not.
	 */
	@Test
	void subscriptionMaintenanceAndPolicyWaitersTogetherLeaveProtocolWorkersFree() throws Exception {
		// Four protocol workers: two maintenance projections and one policy waiter.
		Harness harness = new Harness(4, 4, true, true);
		List<RawClient> clients = new ArrayList<>();
		try {
			ShutdownResult result = Soklet.run(harness.config, () -> {
				try {
					for (int index = 0; index < 3; index++) {
						RawClient subscription = harness.open(McpProtocolVersion.V2026_07_28, "subscriptions/listen", "listen-" + index, null);
						clients.add(subscription);
						assertEquals(200, subscription.readHead().status());
					}
					// Initial baselines use handler slots too; let them finish first.
					await(() -> harness.succeededMaintenance.get() >= 3
							&& harness.server.getDiagnostics().getActiveHandlerExecutions() == 0);
					for (int index = 0; index < harness.concurrency; index++) {
						clients.add(harness.open(McpProtocolVersion.V2026_07_28, "tools/call", "active-" + index, null));
						int expected = index + 1;
						await(() -> harness.entered.get() == expected);
					}
					harness.publisher.publishToolsListChanged();
					await(() -> harness.queueDepth() == 2);
					List<RawClient> excess = new ArrayList<>();
					for (int index = 0; index < 3; index++) {
						RawClient client = harness.open(McpProtocolVersion.V2026_07_28, "tools/list", "excess-" + index, null);
						excess.add(client);
						clients.add(client);
					}
					await(() -> harness.settled(3, 2));
					try (RawClient discovery = harness.open(McpProtocolVersion.V2026_07_28, "server/discover", "control", null)) {
						assertEquals(200, read(discovery).status(), "One protocol worker must remain for control traffic.");
					}
					assertEquals(3, harness.queueDepth(), "Two projections and one catalog read may wait.");
					assertEquals(2, harness.capacityRejections.get());
					harness.release.countDown();
					int served = 0;
					for (RawClient client : excess) {
						Response response = read(client);
						if (response.status() == 200)
							served++;
						else
							assertCapacity(response);
					}
					assertEquals(1, served, "The waiting catalog read must complete once capacity returns.");
				} finally {
					harness.release.countDown();
					close(clients);
				}
			});
			assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
		} finally {
			harness.release.countDown();
			close(clients);
		}
	}

	private static void legacyCatalogOverload(McpProtocolVersion revision) throws Exception {
		// Default pools: 32 handler slots and 32 protocol workers, so eight catalog reads may wait.
		Harness harness = new Harness();
		List<RawClient> clients = new ArrayList<>();
		try {
			ShutdownResult result = Soklet.run(harness.config, () -> {
				try {
					List<String> sessions = new ArrayList<>();
					for (int index = 0; index < 8; index++)
						sessions.add(harness.initialize(revision));
					for (int index = 0; index < harness.concurrency; index++)
						clients.add(harness.open(revision, "tools/call", "active-" + index, sessions.get(index % sessions.size())));
					await(() -> harness.entered.get() == harness.concurrency);
					List<RawClient> catalogs = new ArrayList<>();
					for (int index = 0; index < 128; index++) {
						RawClient client = harness.open(revision, "tools/list", "catalog-" + index, sessions.get(index % sessions.size()));
						catalogs.add(client);
						clients.add(client);
					}
					// The unbounded implementation queues every catalog read and then stalls control traffic.
					await(() -> harness.settled(8, 120));
					try (RawClient ping = harness.open(revision, "ping", "control", sessions.get(0))) {
						assertEquals(200, read(ping).status());
					}
					assertNotNull(harness.initialize(revision), "Initialization must not wait for a tool slot.");
					assertEquals(harness.concurrency, harness.server.getDiagnostics().getActiveHandlerExecutions());
					assertEquals(8, harness.queueDepth(), "A quarter of the protocol pool may wait for a handler slot.");
					assertEquals(120, harness.capacityRejections.get());
					try (RawClient cancel = harness.cancel(revision, sessions.get(0), "active-0")) {
						assertEquals(202, read(cancel).status());
					}
					assertTrue(harness.interrupted.await(5, TimeUnit.SECONDS), "Cancellation must interrupt a tool while the other tools remain blocked.");
					// The one freed slot serves the eight waiting catalog reads in turn.
					await(() -> harness.server.getDiagnostics().getActiveHandlerExecutions() == harness.concurrency - 1
							&& harness.queueDepth() == 0);
					int served = 0;
					for (RawClient client : catalogs) {
						Response response = read(client);
						if (response.status() == 200)
							served++;
						else
							assertCapacity(response);
					}
					assertEquals(8, served, "Waiting catalog reads must complete once capacity returns.");
					try (RawClient recovered = harness.open(revision, "tools/list", "recovered", sessions.get(0))) {
						assertEquals(200, read(recovered).status());
					}
				} finally {
					harness.release.countDown();
					close(clients);
				}
			});
			assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
		} finally {
			harness.release.countDown();
			close(clients);
		}
	}

	private static void assertCapacity(Response response) {
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
			assertTrue(System.nanoTime() - deadline < 0, "The expected handler saturation/rejection was not reached.");
			Thread.sleep(2);
		}
	}

	private static final class Harness {
		final int concurrency;
		final AtomicInteger entered = new AtomicInteger();
		final AtomicInteger capacityRejections = new AtomicInteger();
		final AtomicInteger succeededMaintenance = new AtomicInteger();
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch interrupted = new CountDownLatch(1);
		final McpSubscriptionEventPublisher publisher = McpSubscriptionEventPublisher.fromInMemoryDefaults();
		final McpServer server;
		final SokletConfig config;

		/** Default pool sizes and no catalog policy. */
		Harness() {
			this(32, 0, false, false);
		}

		Harness(int handlerConcurrency, int protocolWorkers, boolean catalogPolicy, boolean subscriptions) {
			concurrency = handlerConcurrency;
			Set<McpProtocolVersion> revisions = Set.of(McpProtocolVersion.V2025_06_18,
					McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("busy", revisions)
					.jsonObjectArguments().handler((context, arguments, features) -> {
						entered.incrementAndGet();
						try {
							release.await();
						} catch (InterruptedException exception) {
							interrupted.countDown();
							throw exception;
						}
						return McpCompleteResult.fromToolText("done");
					}).build();
			McpEndpoint.Builder endpoint = McpEndpoint.withPath("/mcp",
					McpImplementation.withNameAndVersion("saturation-test", "1").build(), revisions)
					.toolRegistrations(List.of(tool))
					.sessionProtocolVersions(Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25));
			if (subscriptions)
				endpoint.subscriptionProtocolVersions(Set.of(McpProtocolVersion.V2026_07_28))
						.subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(publisher,
								Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED)).build());
			McpServer.Builder builder = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint.build())))
					.sessionConfig(McpSessionConfig.withOwnerKeyResolver(identity -> "owner").anonymousSessionsAllowed(true).build())
					.requestTimeout(Duration.ofSeconds(30));
			if (protocolWorkers > 0)
				builder.requestHandlerConcurrency(handlerConcurrency).requestHandlerQueueCapacity(16)
						.protocolExecutorConcurrency(protocolWorkers);
			if (catalogPolicy)
				builder.catalogAccessPolicy(McpCatalogAccessPolicy.allowAllInstance());
			if (subscriptions)
				builder.subscriptionCatalogProjectionTimeout(Duration.ofSeconds(30))
						.subscriptionAuthorizer((context, features) -> McpSubscriptionAuthorization.Allowed
								.fromValidUntil(Instant.now().plus(Duration.ofMinutes(5))));
			server = builder.build();
			config = SokletConfig.withMcpServer(server).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.jvmShutdownHookEnabled(false).lifecycleObservers(List.of())
					.metricsCollector(new MetricsCollector() {
						@Override
						public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
							if (event instanceof McpMetricsEvent.HandlerCapacityRejected)
								capacityRejections.incrementAndGet();
							if (event instanceof McpMetricsEvent.SubscriptionMaintenance maintenance
									&& maintenance.getOutcome() == McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED)
								succeededMaintenance.incrementAndGet();
						}
					}).lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
		}

		int queueDepth() {
			return server.getDiagnostics().getRequestHandlerQueueDepth();
		}

		/** True at the expected state, and also past it so a wrong bound fails on the assertions that follow. */
		boolean settled(int queued, int rejected) {
			int depth = queueDepth();
			int rejections = capacityRejections.get();
			return depth == queued && rejections == rejected || depth > queued || rejections > rejected;
		}

		int port() {
			return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
		}

		String initialize(McpProtocolVersion revision) throws Exception {
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"initialize\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
					+ revision.getWireValue() + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
			try (RawClient client = new RawClient(port(), "POST", "/mcp", body, List.of())) {
				Response response = read(client);
				assertEquals(200, response.status(), response.body());
				String session = response.header("Mcp-Session-Id");
				assertNotNull(session);
				return session;
			}
		}

		RawClient open(McpProtocolVersion revision, String method, String id, String session) throws Exception {
			boolean modern = revision == McpProtocolVersion.V2026_07_28;
			String metadata = modern ? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" : "";
			String params = method.equals("tools/call")
					? "\"name\":\"busy\",\"arguments\":{}" + (modern ? "," + metadata : "")
					: method.equals("subscriptions/listen")
							? metadata + ",\"notifications\":{\"toolsListChanged\":true}" : metadata;
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method + "\",\"params\":{" + params + "}}";
			List<HeaderValue> headers = new ArrayList<>();
			headers.add(new HeaderValue("MCP-Protocol-Version", revision.getWireValue()));
			if (session != null)
				headers.add(new HeaderValue("Mcp-Session-Id", session));
			if (modern) {
				headers.add(new HeaderValue("Mcp-Method", method));
				if (method.equals("tools/call"))
					headers.add(new HeaderValue("Mcp-Name", "busy"));
			}
			return new RawClient(port(), "POST", "/mcp", body, headers);
		}

		RawClient cancel(McpProtocolVersion revision, String session, String target) throws Exception {
			String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":\"" + target + "\"}}";
			return new RawClient(port(), "POST", "/mcp", body, List.of(
					new HeaderValue("MCP-Protocol-Version", revision.getWireValue()),
					new HeaderValue("Mcp-Session-Id", session)));
		}
	}
}
