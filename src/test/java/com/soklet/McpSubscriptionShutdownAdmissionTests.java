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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Shutdown must retain a finite response for subscriptions still being admitted. */
@Timeout(60)
class McpSubscriptionShutdownAdmissionTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);
	private static final Set<McpProtocolVersion> LEGACY = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final String ORIGIN = "https://shutdown.example";

	enum ModernGate { CORS, ADMISSION, AUTHORIZATION }
	enum LegacyGate { CORS, TRANSPORT_ADMISSION, OWNER_RESOLUTION }

	@Test void modernListenPausedInCorsDrainsGracefully() throws Exception { assertPendingModernListen(ModernGate.CORS); }
	@Test void modernListenPausedInAdmissionDrainsGracefully() throws Exception { assertPendingModernListen(ModernGate.ADMISSION); }
	@Test void modernListenPausedInAuthorizationDrainsGracefully() throws Exception { assertPendingModernListen(ModernGate.AUTHORIZATION); }

	@Test
	void pendingListenResponseAndShutdownDoNotWaitForProtocolErrorMetrics() throws Exception {
		Gate collector = new Gate();
		collector.arm();
		try (Fixture fixture = new Fixture(ModernGate.AUTHORIZATION, null, new MetricsCollector() {
			@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				if (event instanceof McpMetricsEvent.ProtocolError) collector.block();
			}
		})) {
			fixture.gate.arm();
			try (RawClient client = fixture.modern("subscriptions/listen", "\"notifications\":{\"resourcesListChanged\":true}")) {
				fixture.gate.awaitEntry();
				CompletionStage<ShutdownResult> shutdown = fixture.quiesceAndShutdown();
				collector.awaitEntry();
				assertEquals(1L, collector.exited.getCount(), "Quiesce waited for the metrics collector.");
				fixture.gate.release();
				Head head = client.readHead();
				assertEquals(503, head.status());
				assertTrue(client.readBody(head).contains("\"error\""));
				assertGraceful(shutdown);
			}
		} finally { collector.release(); }
	}

	private void assertPendingModernListen(ModernGate gate) throws Exception {
		try (Fixture fixture = new Fixture(gate, null)) {
			fixture.gate.arm();
			try (RawClient client = fixture.modern("subscriptions/listen", "\"notifications\":{\"resourcesListChanged\":true}")) {
				fixture.gate.awaitEntry();
				CompletionStage<ShutdownResult> shutdown = fixture.quiesceAndShutdown();
				fixture.gate.release();
				Head head = client.readHead();
				assertEquals(503, head.status());
				assertEquals("close", head.header("Connection"));
				assertTrue(head.header("Content-Type").startsWith("application/json"));
				String body = client.readBody(head);
				assertTrue(body.contains("\"id\":7"), body);
				assertTrue(body.contains("\"error\""), body);
				assertFalse(body.contains("data:"), body);
				assertGraceful(shutdown);
			}
		}
	}

	@Test @Timeout(150)
	void legacyGetPausedInCorsCannotOpenAfterQuiesce() throws Exception { assertPendingLegacyGet(LegacyGate.CORS); }
	@Test @Timeout(150)
	void legacyGetPausedInAdmissionCannotOpenAfterQuiesce() throws Exception { assertPendingLegacyGet(LegacyGate.TRANSPORT_ADMISSION); }
	@Test @Timeout(150)
	void legacyGetPausedInOwnerResolutionCannotOpenAfterQuiesce() throws Exception { assertPendingLegacyGet(LegacyGate.OWNER_RESOLUTION); }

	private void assertPendingLegacyGet(LegacyGate gate) throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			try (Fixture fixture = new Fixture(null, gate)) {
				String sessionId = fixture.initialize(version);
				fixture.gate.arm();
				try (RawClient client = fixture.get(version, sessionId)) {
					fixture.gate.awaitEntry();
					CompletionStage<ShutdownResult> shutdown = fixture.quiesceAndShutdown();
					fixture.gate.release();
					assertStoppingGet(client);
					assertGraceful(shutdown);
				}
			}
		}
	}

	@Test
	@Timeout(120)
	void queuedLegacyGetIsRejectedWhileAdmittedFiniteRequestStillCompletes() throws Exception {
		try (Fixture fixture = new Fixture(ModernGate.CORS, null)) {
			String sessionId = fixture.initialize(McpProtocolVersion.V2025_11_25);
			ThreadPoolExecutor processor = fixture.protocolProcessor();
			processor.setCorePoolSize(1);
			processor.setMaximumPoolSize(1);
			fixture.gate.arm();
			try (RawClient finite = fixture.modern("resources/list", "");
					RawClient queuedGet = fixture.get(McpProtocolVersion.V2025_11_25, sessionId)) {
				fixture.gate.awaitEntry();
				awaitQueued(processor);
				CompletionStage<ShutdownResult> shutdown = fixture.quiesceAndShutdown();
				fixture.gate.release();
				Head finiteHead = finite.readHead();
				assertEquals(200, finiteHead.status());
				assertTrue(finite.readBody(finiteHead).contains("\"resources\""));
				assertStoppingGet(queuedGet);
				assertGraceful(shutdown);
			}
		}
	}

	@Test
	@Timeout(90)
	void establishedModernAndLegacyStreamsCompleteGracefully() throws Exception {
		try (Fixture fixture = new Fixture(null, null)) {
			String sessionId = fixture.initialize(McpProtocolVersion.V2025_11_25);
			try (RawClient modern = fixture.modern("subscriptions/listen", "\"notifications\":{\"resourcesListChanged\":true}");
					RawClient legacy = fixture.get(McpProtocolVersion.V2025_11_25, sessionId)) {
				Head modernHead = modern.readHead();
				Head legacyHead = legacy.readHead();
				assertEquals(200, modernHead.status());
				assertEquals(200, legacyHead.status());
				assertTrue(new String(modern.readChunk(), java.nio.charset.StandardCharsets.UTF_8).contains("data:"));
				CompletionStage<ShutdownResult> shutdown = fixture.quiesceAndShutdown();
				String terminal = modern.readBody(modernHead);
				assertTrue(terminal.contains("\"id\":7"), terminal);
				assertFalse(legacy.readBody(legacyHead).contains("data:"));
				assertTrue(modern.terminalRead);
				assertTrue(legacy.terminalRead);
				assertGraceful(shutdown);
			}
		}
	}

	private static void assertStoppingGet(RawClient client) throws Exception {
		Head head = client.readHead();
		assertEquals(503, head.status());
		assertEquals("close", head.header("Connection"));
		assertEquals("", client.readBody(head));
	}

	private static void assertGraceful(CompletionStage<ShutdownResult> shutdown) throws Exception {
		ShutdownResult result = shutdown.toCompletableFuture().get(5, TimeUnit.SECONDS);
		assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
		assertEquals(ShutdownComponentDisposition.GRACEFUL_TERMINATION,
				result.getShutdownComponentResult(ShutdownComponentType.MCP).orElseThrow().getShutdownComponentDisposition());
	}

	private static void awaitQueued(ThreadPoolExecutor processor) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (processor.getQueue().size() != 1) {
			assertTrue(System.nanoTime() - deadline < 0L, "GET was not queued behind the finite request.");
			Thread.sleep(5);
		}
	}

	private static final class Gate {
		private final AtomicBoolean armed = new AtomicBoolean();
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);
		private final CountDownLatch exited = new CountDownLatch(1);
		void arm() { armed.set(true); }
		void block() {
			if (!armed.compareAndSet(true, false)) return;
			entered.countDown();
			try { assertTrue(released.await(5, TimeUnit.SECONDS), "Callback was not released."); }
			catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			finally { exited.countDown(); }
		}
		void awaitEntry() throws InterruptedException { assertTrue(entered.await(5, TimeUnit.SECONDS), "Callback was not entered."); }
		void release() { released.countDown(); }
	}

	private static final class Fixture implements AutoCloseable {
		final Gate gate = new Gate();
		final McpServer server;
		final Soklet soklet;
		final int port;
		Fixture(ModernGate modernGate, LegacyGate legacyGate) {
			this(modernGate, legacyGate, MetricsCollector.disabledInstance());
		}
		Fixture(ModernGate modernGate, LegacyGate legacyGate, MetricsCollector metricsCollector) {
			McpSubscriptionConfig subscriptions = McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
					McpSubscriptionEventPublisher.fromInMemoryDefaults(), Set.of(McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)).build();
			McpResourceRegistration resource = McpResourceRegistration.withUriAndName(URI.create("catalog://shutdown/item"), "Item", VERSIONS)
					.handler((requestContext, resourceReadContext, invocationFeatures) -> McpCompleteResult.fromResourceOutput(
							McpResourceOutput.withContent(McpTextResourceContents.withUriAndText(resourceReadContext.getUri(), "item").build()).build())).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("shutdown-admission", "1").build(), VERSIONS)
					.resourceRegistrations(List.of(resource)).sessionProtocolVersions(LEGACY)
					.subscriptionProtocolVersions(VERSIONS).subscriptionConfig(subscriptions).build();
			CorsAuthorizer delegate = CorsAuthorizer.fromWhitelistedOrigins(Set.of(ORIGIN));
			McpSessionConfig session = McpSessionConfig.withOwnerKeyResolver(identity -> {
				if (legacyGate == LegacyGate.OWNER_RESOLUTION) gate.block();
				return "owner";
			}).transportAdmissionController((sessionTransportAdmissionContext, invocationFeatures) -> {
				if (legacyGate == LegacyGate.TRANSPORT_ADMISSION) gate.block();
				return McpSessionTransportAdmissionDecision.accepted(identity(), Instant.now().plusSeconds(30), sessionTransportAdmissionContext.getNotificationTypes());
			}).build();
			server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).sessionConfig(session)
					.requestHandlerConcurrency(1).requestHandlerQueueCapacity(2).requestTimeout(Duration.ofSeconds(10))
					.subscriptionAuthorizationTimeout(Duration.ofSeconds(10)).keepAliveInterval(Duration.ofMillis(100))
					.admissionController(admissionContext -> {
						if (modernGate == ModernGate.ADMISSION) gate.block();
						return McpAdmissionDecision.accepted(identity());
					}).subscriptionAuthorizer((subscriptionAuthorizationContext, invocationFeatures) -> {
						if (modernGate == ModernGate.AUTHORIZATION) gate.block();
						return McpSubscriptionAuthorization.Allowed.fromValidUntil(Instant.now().plusSeconds(30));
					}).corsAuthorizer(new CorsAuthorizer() {
						@Override public java.util.Optional<CorsResponse> authorize(Request request, Cors cors) {
							if (modernGate == ModernGate.CORS || legacyGate == LegacyGate.CORS) gate.block();
							return delegate.authorize(request, cors);
						}
						@Override public java.util.Optional<CorsPreflightResponse> authorizePreflight(Request request, CorsPreflight corsPreflight,
								java.util.Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod) {
							return java.util.Optional.empty();
						}
					}).build();
			soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.metricsCollector(metricsCollector)
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(Duration.ofSeconds(3)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
			soklet.start();
			port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
		}
		private static McpAdmissionIdentity identity() {
			return McpAdmissionIdentity.withRateLimitPartitionKey("shutdown").authorizationPartitionKey("shutdown").principal("owner").build();
		}
		CompletionStage<ShutdownResult> quiesceAndShutdown() throws Exception {
			// Synchronous runtime wind-up gives a deterministic boundary before the
			// paused callback returns; the public owner then verifies full drain.
			((McpServerRuntimeBridge) readField(server, "runtimeBridge")).quiesceLifecycle();
			return soklet.shutdown();
		}
		ThreadPoolExecutor protocolProcessor() throws Exception {
			Object runtime = readField(readField(server, "runtimeBridge"), "runtime");
			return (ThreadPoolExecutor) readField(runtime, "requestProcessor");
		}
		private static Object readField(Object target, String name) throws Exception {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(target);
		}
		RawClient modern(String method, String fields) throws Exception {
			String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
					+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}"
					+ (fields.isEmpty() ? "" : "," + fields) + "}}";
			return new RawClient(port, "POST", "/mcp", body, List.of(new HeaderValue("Origin", ORIGIN),
					new HeaderValue("MCP-Protocol-Version", "2026-07-28"), new HeaderValue("Mcp-Method", method)));
		}
		String initialize(McpProtocolVersion version) throws Exception {
			String body = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
					+ version.getWireValue() + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
			String id;
			try (RawClient client = new RawClient(port, "POST", "/mcp", body, List.of())) {
				Head head = client.readHead(); assertEquals(200, head.status(), client.readBody(head)); id = head.header("Mcp-Session-Id");
			}
			assertNotNull(id);
			try (RawClient client = new RawClient(port, "POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
					List.of(new HeaderValue("MCP-Protocol-Version", version.getWireValue()), new HeaderValue("Mcp-Session-Id", id)))) {
				Head head = client.readHead(); assertEquals(202, head.status()); client.readBody(head);
			}
			return id;
		}
		RawClient get(McpProtocolVersion version, String id) throws Exception {
			List<HeaderValue> headers = new ArrayList<>(List.of(new HeaderValue("Origin", ORIGIN),
					new HeaderValue("MCP-Protocol-Version", version.getWireValue()), new HeaderValue("Mcp-Session-Id", id)));
			return new RawClient(port, "GET", "/mcp", "", headers);
		}
		@Override public void close() { gate.release(); soklet.close(); }
	}
}
