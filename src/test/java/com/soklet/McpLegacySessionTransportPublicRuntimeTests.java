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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Public API real-socket HTTP admission, GET lease, and DELETE cleanup contracts. */
@Timeout(120)
class McpLegacySessionTransportPublicRuntimeTests {
	private static final List<McpProtocolVersion> LEGACY = List.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> ALL = Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);
	private static final Set<McpSubscriptionNotificationType> FAMILIES = Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED);
	private static final LifecyclePolicy LIFECYCLE = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@BeforeEach
	void beginRequestBudget() { RawClient.beginRequestBudget(); }

	@AfterEach
	void endRequestBudget() { RawClient.endRequestBudget(); }

	@Test
	void chunkReadResumesAfterTimeoutAtEveryHeaderPayloadAndDelimiterByte() throws Exception {
		String frame = "5;part=1\r\nhello\r\n";
		for (int offset = 0; offset < frame.length(); offset++) {
			try (RawClient client = new RawClient(timeoutOnce(frame + "3\r\nbye\r\n0\r\n\r\n", offset))) {
				assertThrows(java.net.SocketTimeoutException.class, client::readChunk);
				assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), client.readChunk());
				assertArrayEquals("bye".getBytes(StandardCharsets.UTF_8), client.readChunk());
				assertNull(client.readChunk());
			}
		}
	}

	@Test
	void terminalChunkReadResumesAfterTimeoutWithoutDiscardingItsFraming() throws Exception {
		String wire = "0\r\n\r\n";
		for (int offset = 0; offset < wire.length(); offset++) {
			try (RawClient client = new RawClient(timeoutOnce(wire, offset))) {
				assertThrows(java.net.SocketTimeoutException.class, client::readChunk);
				assertNull(client.readChunk());
				assertNull(client.readChunk());
			}
		}
	}

	private static InputStream timeoutOnce(String wire, int timeoutOffset) {
		byte[] bytes = wire.getBytes(StandardCharsets.ISO_8859_1);
		return new InputStream() {
			private int offset;
			private boolean timedOut;
			@Override public int read() throws IOException {
				if (!timedOut && offset == timeoutOffset) {
					timedOut = true;
					throw new java.net.SocketTimeoutException("Interrupted test frame.");
				}
				return offset == bytes.length ? -1 : bytes[offset++] & 0xff;
			}
			@Override public int read(byte[] target, int offset, int length) throws IOException {
				if (length == 0) return 0;
				int value = read();
				if (value < 0) return -1;
				target[offset] = (byte) value;
				return 1;
			}
		};
	}

	@Test
	void methodMatrixRespectsExactRevisionConfigurationAndDoesNotFabricateRpcAdmission() throws Exception {
		try (Fixture fixture = new Fixture(true, builder -> {})) {
			for (McpProtocolVersion version : LEGACY) {
				String id = fixture.initialize("/mcp", version);
				String cleanupId = fixture.initialize("/cleanup", version);
				int httpBefore = fixture.contexts.size();
				assertEquals(400, fixture.control("GET", "/mcp", null, id, "alice", "", List.of()).status());
				Response cleanupGet = fixture.control("GET", "/cleanup", version, cleanupId, "alice", "", List.of());
				assertEquals(405, cleanupGet.status());
				assertTrue(cleanupGet.header("Allow").contains("DELETE"));
				assertFalse(cleanupGet.header("Allow").contains("GET"));
				assertEquals(405, fixture.control("GET", "/stateless", version, id, "alice", "", List.of()).status());
				assertEquals(405, fixture.control("DELETE", "/mcp", McpProtocolVersion.V2026_07_28, id, "alice", "", List.of()).status());
				assertEquals(httpBefore, fixture.contexts.size(), "Unsupported facilities never invoke the controller.");
				assertEquals(400, fixture.control("GET", "/mcp", version, id, "alice", "x", List.of()).status());
				assertEquals(400, fixture.control("DELETE", "/mcp", version, id, "alice", "x", List.of()).status());
				assertEquals(400, fixture.control("GET", "/mcp", version, id, "alice", "",
						List.of(new HeaderValue("Mcp-Session-Id", id))).status());
				assertEquals(400, fixture.control("GET", "/mcp", version, null, "alice", "", List.of()).status());
				int rpcBefore = fixture.rpcAdmissions.get();
				assertEquals(404, fixture.control("GET", "/mcp", version, id, "bob", "", List.of()).status());
				assertEquals(404, fixture.control("GET", "/other", version, id, "alice", "", List.of()).status());
				McpProtocolVersion otherRevision = version == McpProtocolVersion.V2025_06_18
						? McpProtocolVersion.V2025_11_25 : McpProtocolVersion.V2025_06_18;
					assertEquals(400, fixture.control("GET", "/mcp", otherRevision, id, "alice", "", List.of()).status());
				assertEquals(404, fixture.control("DELETE", "/mcp", version, "unknown.valid", "alice", "", List.of()).status());
				assertEquals(rpcBefore, fixture.rpcAdmissions.get(), "HTTP admission must not invent ping/listen RPC contexts.");
				Response deleted = fixture.control("DELETE", "/cleanup", version, cleanupId, "alice", "", List.of());
				assertEquals(204, deleted.status(), deleted.body());
				assertEquals("", deleted.body());
				McpSessionTransportAdmissionContext context = fixture.contexts.get(fixture.contexts.size() - 1);
				assertEquals(HttpMethod.DELETE, context.getRequest().getHttpMethod());
				assertEquals("/cleanup", context.getEndpoint().getPath());
				assertEquals(version, context.getProtocolVersion());
				assertTrue(context.getNotificationTypes().isEmpty());
				assertFalse(context.isReauthorization());
			}
		}
	}

	@Test
	void absenceOfControllerPreservesPostOnlySessions() throws Exception {
		try (Fixture fixture = new Fixture(false, builder -> {})) {
			for (McpProtocolVersion version : LEGACY) {
				String id = fixture.initialize("/mcp", version);
				for (String method : List.of("GET", "DELETE")) {
					Response response = fixture.control(method, "/mcp", version, id, "alice", "", List.of());
					assertEquals(405, response.status());
					assertEquals(405, fixture.control(method, "/mcp", null, id, "alice", "", List.of()).status());
					assertFalse(response.header("Allow").contains("DELETE"));
					assertFalse(response.header("Allow").contains("GET"));
				}
				assertEquals(200, fixture.ping(version, id).status());
			}
			assertTrue(fixture.contexts.isEmpty());
		}
	}

	@Test
	void oauthChallengesAreApplicationSelectedAndDoNotRenderJsonRpcErrors() throws Exception {
		try (Fixture fixture = new Fixture(true, builder -> {})) {
			for (McpProtocolVersion version : LEGACY) {
				String id = fixture.initialize("/mcp", version);
				fixture.policy.set((context, features) -> McpSessionTransportAdmissionDecision.rejected(
						McpAdmissionRejection.withStatusCodeAndError(403, McpJsonRpcError.fromApplication(-31903, "private-rpc-message"))
								.addHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"catalog:listen\"")
								.addHeader("X-Policy", "safe").build()));
				for (String method : List.of("GET", "DELETE")) {
					Response response = fixture.control(method, "/mcp", version, id, "alice", "", List.of());
					assertEquals(403, response.status());
					assertEquals("Bearer error=\"insufficient_scope\", scope=\"catalog:listen\"", response.header("WWW-Authenticate"));
					assertEquals("safe", response.header("X-Policy"));
					assertEquals("no-store", response.header("Cache-Control"));
					assertEquals("", response.body());
				}
				fixture.policy.set((context, features) -> McpSessionTransportAdmissionDecision.rejected(
						McpAdmissionRejection.withStatusCodeAndError(403, McpJsonRpcError.fromApplication(-31903, "Denied")).build()));
				Response ordinary = fixture.control("GET", "/mcp", version, id, "alice", "", List.of());
				assertNull(ordinary.header("WWW-Authenticate"));
				for (int status : List.of(400, 404, 405)) {
					fixture.policy.set((context, features) -> McpSessionTransportAdmissionDecision.rejected(
							McpAdmissionRejection.withStatusCodeAndError(status, McpJsonRpcError.fromApplication(-31903, "Denied")).build()));
					assertEquals(403, fixture.control("DELETE", "/mcp", version, id, "alice", "", List.of()).status());
				}
				assertEquals(200, fixture.ping(version, id).status(), "Denial does not retire the session.");
				fixture.policy.set(Fixture::allow);
			}
		}
	}

	@Test
	void expiredAndInvalidSelectionsFailWithoutCreatingGetOrDeletingSession() throws Exception {
		try (Fixture fixture = new Fixture(true, builder -> {})) {
			for (McpProtocolVersion version : LEGACY) {
				String id = fixture.initialize("/mcp", version);
				fixture.policy.set((context, features) -> McpSessionTransportAdmissionDecision.accepted(identity("alice"),
						Instant.now().minusSeconds(1), context.getNotificationTypes()));
				for (String method : List.of("GET", "DELETE"))
					assertEquals(403, fixture.control(method, "/mcp", version, id, "alice", "", List.of()).status());
				fixture.policy.set((context, features) -> McpSessionTransportAdmissionDecision.accepted(identity("alice"),
						Instant.now().plusSeconds(30), Set.of(McpSubscriptionNotificationType.RESOURCE_UPDATED)));
				for (String method : List.of("GET", "DELETE"))
					assertEquals(500, fixture.control(method, "/mcp", version, id, "alice", "", List.of()).status());
				for (String header : List.of("Content-Type", "Access-Control-Allow-Origin", "Mcp-Session-Id")) {
					fixture.policy.set((context, features) -> McpSessionTransportAdmissionDecision.rejected(
							McpAdmissionRejection.withStatusCodeAndError(403, McpJsonRpcError.fromApplication(-31903, "Denied"))
									.addHeader(header, "untrusted-value").build()));
					Response rejected = fixture.control("GET", "/mcp", version, id, "alice", "", List.of());
					assertEquals(500, rejected.status());
					assertFalse(rejected.body().contains("untrusted-value"));
				}
				fixture.policy.set((context, features) -> null);
				assertEquals(500, fixture.control("GET", "/mcp", version, id, "alice", "", List.of()).status());
				assertEquals(0, fixture.server.getDiagnostics().getActiveSubscriptions());
				assertEquals(200, fixture.ping(version, id).status());
				fixture.policy.set(Fixture::allow);
				assertEquals(204, fixture.control("DELETE", "/mcp", version, id, "alice", "", List.of()).status());
				assertEquals(404, fixture.ping(version, id).status());
			}
		}
	}

	@Test
	void getUsesBoundedFreshRenewalAndPhysicalHttpObservationWithoutRpcStreamMetrics() throws Exception {
		for (McpProtocolVersion version : LEGACY)
			try (Fixture fixture = new Fixture(true, builder -> builder
					.maximumSubscriptionAuthorizationDuration(Duration.ofMillis(250))
					.maximumSubscriptionDuration(Duration.ofMillis(950)))) {
				String id = fixture.initialize("/mcp", version);
				int rpcBefore = fixture.rpcAdmissions.get();
				fixture.policy.set((context, features) -> McpSessionTransportAdmissionDecision.accepted(
						identity("alice"), Instant.MAX, context.getNotificationTypes()));
				try (RawClient get = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
					Head head = get.readHead();
					assertSse(head);
					awaitCondition(() -> fixture.server.getDiagnostics().getActiveSubscriptions() == 1);
					assertTrue(fixture.server.getDiagnostics().getActiveRequestStreams() >= 1);
					String body = get.readBody(head);
					assertFalse(body.contains("data:"), body);
					assertTrue(get.terminalRead, "GET lifetime must cleanly complete HTTP framing.");
				}
				assertEquals(rpcBefore, fixture.rpcAdmissions.get());
				assertTrue(fixture.contexts.size() >= 2, "Configured lease must perform fresh reauthorization.");
				McpSessionTransportAdmissionContext original = fixture.contexts.get(0);
				assertFalse(original.isReauthorization());
				assertEquals(FAMILIES, original.getNotificationTypes());
				assertFalse(original.toString().contains(id));
				assertFalse(original.toString().contains("alice"));
				assertThrows(UnsupportedOperationException.class, () -> original.getNotificationTypes().clear());
				for (McpSessionTransportAdmissionContext renewal : fixture.contexts.subList(1, fixture.contexts.size())) {
					assertTrue(renewal.isReauthorization());
					assertSame(original.getRequest(), renewal.getRequest(), "Renewal retains original immutable HTTP credential input.");
					assertEquals(version, renewal.getProtocolVersion());
				}
				awaitCondition(() -> fixture.events.stream().filter(McpMetricsEvent.SubscriptionClosed.class::isInstance).count() == 1);
				assertEquals(1, fixture.events.stream().filter(McpMetricsEvent.SubscriptionOpened.class::isInstance).count());
				assertEquals(0, fixture.events.stream().filter(McpMetricsEvent.RequestStreamOpened.class::isInstance).count());
				assertEquals(0, fixture.events.stream().filter(McpMetricsEvent.RequestStreamClosed.class::isInstance).count());
				awaitCondition(() -> fixture.httpFinishes.get() == 1);
				assertEquals(1, fixture.httpStarts.get());
				assertEquals(0, fixture.server.getDiagnostics().getActiveSubscriptions());
				assertEquals(0, fixture.server.getDiagnostics().getActiveRequestStreams());
			}
	}

	@Test
	@Timeout(150)
	void reconciliationDenialAndOwnerChangeCloseOnlyTheGetAndCannotResurrectIt() throws Exception {
		for (McpProtocolVersion version : LEGACY)
			for (boolean ownerChange : List.of(false, true))
				try (Fixture fixture = new Fixture(true, builder -> {})) {
					String id = fixture.initialize("/mcp", version);
					try (RawClient get = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
						Head head = get.readHead();
						assertSse(head);
						awaitCondition(() -> fixture.server.getDiagnostics().getActiveSubscriptions() == 1);
						fixture.policy.set((context, features) -> ownerChange
								? McpSessionTransportAdmissionDecision.accepted(identity("bob"), Instant.now().plusSeconds(30), context.getNotificationTypes())
								: McpSessionTransportAdmissionDecision.rejected(McpAdmissionRejection.withStatusCodeAndError(403,
										McpJsonRpcError.fromApplication(-31903, "No longer allowed")).build()));
						fixture.server.getSubscriptionReconciler().reconcileSubscriptions();
						assertFalse(get.readBody(head).contains("data:"));
						assertTrue(get.terminalRead);
					}
					awaitCondition(() -> fixture.server.getDiagnostics().getActiveSubscriptions() == 0);
					assertEquals(200, fixture.ping(version, id).status(), "Revoking GET does not retire unrelated session work.");
					fixture.policy.set(Fixture::allow);
					try (RawClient reconnect = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
						assertSse(reconnect.readHead());
					}
				}
	}

	@Test
	void deleteEndsAnExistingGetAndShutdownBalancesMetricsAndDiagnostics() throws Exception {
		for (McpProtocolVersion version : LEGACY)
			try (Fixture fixture = new Fixture(true, builder -> {})) {
				String id = fixture.initialize("/mcp", version);
				try (RawClient get = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
					Head head = get.readHead();
					assertSse(head);
					awaitCondition(() -> fixture.server.getDiagnostics().getActiveSubscriptions() == 1);
					assertEquals(204, fixture.control("DELETE", "/mcp", version, id, "alice", "", List.of()).status());
					assertFalse(get.readBody(head).contains("data:"));
					assertTrue(get.terminalRead);
				}
				assertEquals(404, fixture.ping(version, id).status());
				awaitCondition(() -> fixture.events.stream().filter(McpMetricsEvent.SubscriptionClosed.class::isInstance)
						.map(McpMetricsEvent.SubscriptionClosed.class::cast).anyMatch(event -> event.getReason() == McpStreamTerminationReason.SESSION_CLOSED));
				String fresh = fixture.initialize("/mcp", version);
				try (RawClient get = fixture.openControl("GET", "/mcp", version, fresh, "alice", "", List.of())) {
					Head head = get.readHead();
					assertSse(head);
					fixture.soklet.close();
					assertFalse(get.readBody(head).contains("data:"));
					assertTrue(get.terminalRead);
				}
				assertEquals(0, fixture.server.getDiagnostics().getActiveSubscriptions());
				assertEquals(0, fixture.server.getDiagnostics().getActiveRequestStreams());
				awaitCondition(() -> fixture.events.stream().filter(McpMetricsEvent.SubscriptionClosed.class::isInstance).count() == 2);
				assertEquals(2, fixture.events.stream().filter(McpMetricsEvent.SubscriptionOpened.class::isInstance).count());
				assertEquals(2, fixture.events.stream().filter(McpMetricsEvent.SubscriptionClosed.class::isInstance).count());
			}
	}

	@Test
	void slowReauthorizationCannotDeliverPastLeaseAndRetainsPhysicalReservationUntilExit() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicReference<CancelationToken> token = new AtomicReference<>();
			try (Fixture fixture = new Fixture(true, builder -> builder
					.maximumSubscriptionAuthorizationDuration(Duration.ofMillis(300))
					.subscriptionAuthorizationTimeout(Duration.ofSeconds(2)))) {
				String id = fixture.initialize("/mcp", version);
				fixture.policy.set((context, features) -> {
					if (context.isReauthorization()) {
						token.set(features.getCancelationToken());
						entered.countDown();
						boolean interrupted = false;
						long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
						try {
							while (release.getCount() != 0 && System.nanoTime() - until < 0L) {
								try { release.await(20, TimeUnit.MILLISECONDS); }
								catch (InterruptedException ignored) { interrupted = true; }
							}
							assertEquals(0, release.getCount(), "The test must release its deliberately uncooperative callback.");
						} finally { if (interrupted) Thread.currentThread().interrupt(); }
					}
					return Fixture.allow(context, features);
				});
				try (RawClient get = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
					Head head = get.readHead();
					assertSse(head);
					assertTrue(entered.await(5, TimeUnit.SECONDS));
					assertFalse(get.readBody(head).contains("data:"));
					assertTrue(get.terminalRead);
					awaitCondition(() -> token.get() != null && token.get().isCanceled());
					assertTrue(fixture.server.getDiagnostics().getActiveHandlerExecutions() >= 1);
					assertEquals(0, fixture.server.getDiagnostics().getActiveSubscriptions());
					release.countDown();
					awaitCondition(() -> fixture.server.getDiagnostics().getActiveHandlerExecutions() == 0);
					assertEquals(200, fixture.ping(version, id).status());
				} finally { release.countDown(); }
			} finally { release.countDown(); }
		}
	}

	@Test
	void sameSessionReplacementTransfersQuotaAndCapacityDenialPreservesTheExistingGet() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			try (Fixture fixture = new Fixture(true, builder -> builder.maximumSubscriptionsPerPartition(2))) {
				String id = fixture.initialize("/mcp", version);
				try (RawClient oldest = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
					Head oldHead = oldest.readHead();
					assertSse(oldHead);
					awaitCondition(() -> fixture.server.getDiagnostics().getActiveSubscriptions() == 1);
					try (RawClient newer = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
						assertSse(newer.readHead());
						awaitCondition(() -> fixture.server.getDiagnostics().getActiveSubscriptions() == 2);
						try (RawClient replacement = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
							assertSse(replacement.readHead());
							assertFalse(oldest.readBody(oldHead).contains("data:"));
							assertTrue(oldest.terminalRead);
							assertEquals(2, fixture.server.getDiagnostics().getActiveSubscriptions());
						}
					}
				}
			}
			try (Fixture fixture = new Fixture(true, builder -> builder.maximumSubscriptionsPerPartition(1))) {
				String firstId = fixture.initialize("/mcp", version);
				String otherId = fixture.initialize("/mcp", version);
				try (RawClient existing = fixture.openControl("GET", "/mcp", version, firstId, "alice", "", List.of())) {
					assertSse(existing.readHead());
					Response denied = fixture.control("GET", "/mcp", version, otherId, "alice", "", List.of());
					assertEquals(503, denied.status());
					assertNull(denied.header("Retry-After"));
					assertNotNull(existing.readChunk(), "Capacity failure cannot retire the existing eligible GET.");
					assertEquals(1, fixture.server.getDiagnostics().getActiveSubscriptions());
					assertEquals(200, fixture.ping(version, firstId).status());
				}
			}
		}
	}

	@Test
	void corsPreflightOffersOnlyConfiguredHttpFacilitiesAndDoesNotAdmitSessions() throws Exception {
		String origin = "https://client.example.test";
		try (Fixture fixture = new Fixture(true, builder -> builder.corsAuthorizer(CorsAuthorizer.fromWhitelistedOrigins(Set.of(origin))))) {
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
			for (String path : List.of("/mcp", "/cleanup", "/stateless"))
				for (String method : List.of("GET", "DELETE")) {
					java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
							java.net.URI.create("http://127.0.0.1:" + fixture.port + path)).timeout(Duration.ofSeconds(5))
							.method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody())
							.header("Origin", origin).header("Access-Control-Request-Method", method)
							.header("Access-Control-Request-Headers", "Mcp-Session-Id, MCP-Protocol-Version").build();
					int expected = path.equals("/mcp") || path.equals("/cleanup") && method.equals("DELETE") ? 204 : 403;
					assertEquals(expected, client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode(), path + " " + method);
				}
			assertEquals(0, fixture.rpcAdmissions.get());
			assertTrue(fixture.contexts.isEmpty());
		}
	}

	@Test
	void authorizationExpiryIsRecheckedAfterOwnerResolutionBeforeGetOrDeleteMutation() throws Exception {
		try (Fixture fixture = new Fixture(true, builder -> {})) {
			for (McpProtocolVersion version : LEGACY) {
				String id = fixture.initialize("/mcp", version);
				fixture.policy.set((context, features) -> McpSessionTransportAdmissionDecision.accepted(identity("alice"),
						Instant.now().plusMillis(30), context.getNotificationTypes()));
				fixture.ownerResolver.set(identity -> {
					Thread.sleep(100);
					return identity.getPrincipal().orElseThrow().toString();
				});
				for (String method : List.of("GET", "DELETE"))
					assertEquals(403, fixture.control(method, "/mcp", version, id, "alice", "", List.of()).status());
				assertEquals(0, fixture.server.getDiagnostics().getActiveSubscriptions());
				fixture.ownerResolver.set(identity -> identity.getPrincipal().orElseThrow().toString());
				fixture.policy.set(Fixture::allow);
				assertEquals(200, fixture.ping(version, id).status());
			}
		}
	}

	private static void assertSse(Head head) {
		assertEquals(200, head.status());
		assertEquals("text/event-stream", head.header("Content-Type"));
		assertEquals("chunked", head.header("Transfer-Encoding"));
		assertEquals("no-store", head.header("Cache-Control"));
	}

	private static McpAdmissionIdentity identity(String subject) {
		return McpAdmissionIdentity.withRateLimitPartitionKey("shared-quota")
				.authorizationPartitionKey("shared-authorization").principal(subject).build();
	}

	private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
		assertTrue(condition.getAsBoolean(), "Expected bounded runtime transition did not complete.");
	}

	static final class Fixture implements AutoCloseable {
		final AtomicInteger rpcAdmissions = new AtomicInteger();
		final AtomicInteger httpStarts = new AtomicInteger();
		final AtomicInteger httpFinishes = new AtomicInteger();
		final List<McpSessionTransportAdmissionContext> contexts = new CopyOnWriteArrayList<>();
		final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		final AtomicReference<McpSessionTransportAdmissionController> policy = new AtomicReference<>(Fixture::allow);
		final AtomicReference<McpSessionOwnerKeyResolver> ownerResolver = new AtomicReference<>(identity -> identity.getPrincipal().orElseThrow().toString());
		final McpServer server;
		final Soklet soklet;
		final int port;
		final boolean toolCatalogIncluded;

		Fixture(boolean controller, Consumer<McpServer.Builder> configure) throws Exception {
			this(controller, configure, null);
		}

		Fixture(boolean controller, Consumer<McpServer.Builder> configure, McpToolHandler<McpJsonObject> handler) throws Exception {
			this.toolCatalogIncluded = handler != null;
			McpImplementation info = McpImplementation.withNameAndVersion("transport-test", "1").build();
			McpSubscriptionConfig sources = McpSubscriptionConfig.withEventPublisherAndNotificationTypes(
					McpSubscriptionEventPublisher.fromInMemoryDefaults(), FAMILIES).build();
			List<McpEndpoint> endpoints = new ArrayList<>();
			for (String path : List.of("/mcp", "/other", "/cleanup", "/stateless")) {
				McpEndpoint.Builder endpoint = McpEndpoint.withPath(path, info, ALL);
				if (handler != null) endpoint.toolRegistrations(List.of(McpToolRegistration.withName("busy", ALL)
						.jsonObjectArguments().handler(handler).build()));
				if (!path.equals("/stateless")) endpoint.sessionProtocolVersions(Set.copyOf(LEGACY));
				if (controller && (path.equals("/mcp") || path.equals("/other")))
					endpoint.subscriptionProtocolVersions(Set.copyOf(LEGACY)).subscriptionConfig(sources);
				endpoints.add(endpoint.build());
			}
			McpSessionConfig.Builder session = McpSessionConfig.withOwnerKeyResolver(identity -> ownerResolver.get().resolve(identity));
			if (controller) session.transportAdmissionController((context, features) -> {
				contexts.add(context);
				assertTrue(features.getProgressReporter().isEmpty());
				assertNotNull(features.getCancelationToken());
				return policy.get().admit(context, features);
			});
			McpServer.Builder builder = McpServer.withPort(0).host("127.0.0.1")
					.allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints)).sessionConfig(session.build())
					.requestTimeout(Duration.ofSeconds(5)).keepAliveInterval(Duration.ofMillis(100))
					.maximumSubscriptionDuration(Duration.ofSeconds(3))
					.admissionController(context -> {
						rpcAdmissions.incrementAndGet();
						return McpAdmissionDecision.accepted(identity(context.getRequest().getHeader("X-Subject").orElse("alice")));
					});
			if (handler != null) builder.toolRateLimiter(context -> McpRateLimitDecision.allowed());
			configure.accept(builder);
			this.server = builder.build();
			this.soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).lifecyclePolicy(LIFECYCLE)
					.metricsCollector(new MetricsCollector() {
						@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) { events.add(event); }
					}).lifecycleObserver(new LifecycleObserver() {
						@Override public void didStartRequestHandling(ServerType serverType, Request request, ResourceMethod method) {
							assertEquals(ServerType.HTTP, serverType); assertNull(method); httpStarts.incrementAndGet();
						}
						@Override public void didFinishRequestHandling(ServerType serverType, Request request, ResourceMethod method,
								MarshaledResponse response, Duration duration, List<Throwable> failures) {
							assertEquals(ServerType.HTTP, serverType); assertNull(method); assertTrue(failures.isEmpty());
							assertTrue(Set.of(HttpMethod.GET, HttpMethod.DELETE).contains(request.getHttpMethod()));
							assertTrue(response.getStatusCode() >= 200); httpFinishes.incrementAndGet();
						}
					}).build());
			try {
				soklet.start();
				this.port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			} catch (Throwable failure) { soklet.close(); throw failure; }
		}

		static McpSessionTransportAdmissionDecision allow(McpSessionTransportAdmissionContext context, McpInvocationFeatures features) {
			return McpSessionTransportAdmissionDecision.accepted(identity(context.getRequest().getHeader("X-Subject").orElse("alice")),
					Instant.now().plusSeconds(30), context.getNotificationTypes());
		}

		String initialize(String path, McpProtocolVersion version) throws Exception {
			String body = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
					+ version.getWireValue() + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
			Response response = exchange("POST", path, body, List.of(new HeaderValue("X-Subject", "alice")));
			assertEquals(200, response.status(), response.body());
			String id = response.header("Mcp-Session-Id");
			assertNotNull(id);
			assertEquals(toolCatalogIncluded && (path.equals("/mcp") || path.equals("/other")), response.body().contains("listChanged"),
					"A notification source needs a corresponding served catalog to advertise list changes.");
			assertFalse(response.body().contains("\"subscribe\""));
			Response ack = exchange("POST", path, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
					List.of(new HeaderValue("X-Subject", "alice"), new HeaderValue("MCP-Protocol-Version", version.getWireValue()), new HeaderValue("Mcp-Session-Id", id)));
			assertEquals(202, ack.status(), ack.body());
			return id;
		}

		Response ping(McpProtocolVersion version, String id) throws Exception {
			return exchange("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}",
					List.of(new HeaderValue("X-Subject", "alice"), new HeaderValue("MCP-Protocol-Version", version.getWireValue()), new HeaderValue("Mcp-Session-Id", id)));
		}

		RawClient openControl(String method, String path, McpProtocolVersion version, String id, String subject,
				String body, List<HeaderValue> extra) throws Exception {
			List<HeaderValue> headers = new ArrayList<>();
			if (version != null) headers.add(new HeaderValue("MCP-Protocol-Version", version.getWireValue()));
			if (id != null) headers.add(new HeaderValue("Mcp-Session-Id", id));
			headers.add(new HeaderValue("X-Subject", subject)); headers.addAll(extra);
			return new RawClient(port, method, path, body, headers);
		}

		Response control(String method, String path, McpProtocolVersion version, String id, String subject,
				String body, List<HeaderValue> extra) throws Exception {
			try (RawClient client = openControl(method, path, version, id, subject, body, extra)) {
				Head head = client.readHead();
				return new Response(head.status(), head.headers(), client.readBody(head));
			}
		}

		Response exchange(String method, String path, String body, List<HeaderValue> headers) throws Exception {
			try (RawClient client = new RawClient(port, method, path, body, headers)) {
				Head head = client.readHead();
				return new Response(head.status(), head.headers(), client.readBody(head));
			}
		}
		@Override public void close() { soklet.close(); }
	}

	record HeaderValue(String name, String value) {}
	record Response(int status, Map<String, List<String>> headers, String body) {
		String header(String name) {
			List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
			return values == null ? null : values.get(0);
		}
	}
	record Head(int status, Map<String, List<String>> headers) {
		String header(String name) {
			List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
			return values == null ? null : values.get(0);
		}
	}

	/** Bounded test-only HTTP/1.1 reader; preserves duplicate request fields and empty chunked completion. */
	static final class RawClient implements AutoCloseable {
		private static final Duration WAIT = Duration.ofSeconds(5);
		private static final ThreadLocal<Long> REQUEST_DEADLINE = new ThreadLocal<>();
		static void beginRequestBudget() { REQUEST_DEADLINE.set(System.nanoTime() + Duration.ofSeconds(60).toNanos()); }
		static void endRequestBudget() { REQUEST_DEADLINE.remove(); }
		static Duration remainingRequestWait() {
			Long deadline = REQUEST_DEADLINE.get();
			long remaining = deadline == null ? WAIT.toNanos() : deadline - System.nanoTime();
			assertTrue(remaining > 0, "Legacy requests exceeded their shared 60-second deadline.");
			return Duration.ofNanos(Math.min(WAIT.toNanos(), remaining));
		}
		final Socket socket = new Socket();
		final InputStream input;
		boolean terminalRead;
		long readDeadlineNanos;

		RawClient(InputStream input) {
			this.input = new BufferedInputStream(input);
		}

		RawClient(int port, String method, String path, String body, List<HeaderValue> headers) throws IOException {
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(5000);
			socket.connect(new InetSocketAddress("127.0.0.1", port), (int) Math.max(1, remainingRequestWait().toMillis()));
			this.input = new BufferedInputStream(socket.getInputStream());
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			StringBuilder head = new StringBuilder(method).append(" ").append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1:")
					.append(port).append("\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n");
			for (HeaderValue header : headers) head.append(header.name()).append(": ").append(header.value()).append("\r\n");
			head.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
			socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().write(bytes);
			socket.getOutputStream().flush();
		}

		Head readHead() throws IOException {
			readDeadlineNanos = System.nanoTime() + remainingRequestWait().toNanos();
			String status = line();
			Map<String, List<String>> headers = new LinkedHashMap<>();
			for (int count = 0; count < 128; count++) {
				String line = line();
				if (line.isEmpty()) {
					return new Head(Integer.parseInt(status.split(" ")[1]), Map.copyOf(headers));
				}
				int colon = line.indexOf(':');
				if (colon < 1) throw new IOException("Invalid response field.");
				headers.computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
						.add(line.substring(colon + 1).trim());
			}
			throw new IOException("Response head exceeded test bound.");
		}

		String readBody(Head head) throws IOException {
			readDeadlineNanos = System.nanoTime() + remainingRequestWait().toNanos();
			if (head.status() == 204 && head.header("Content-Length") == null) return "";
			if ("chunked".equalsIgnoreCase(head.header("Transfer-Encoding"))) {
				ByteArrayOutputStream body = new ByteArrayOutputStream();
				for (int count = 0; count < 256; count++) {
					byte[] chunk = readChunk(readDeadlineNanos);
					if (chunk == null) return body.toString(StandardCharsets.UTF_8);
					if (body.size() + chunk.length > 2 * 1024 * 1024) throw new IOException("Response exceeded test bound.");
					body.write(chunk);
				}
				throw new IOException("Chunk count exceeded test bound.");
			}
			return new String(exact(Integer.parseInt(head.header("Content-Length"))), StandardCharsets.UTF_8);
		}

		byte[] readChunk() throws IOException {
			return readChunk(System.nanoTime() + remainingRequestWait().toNanos());
		}

		byte[] readChunk(long deadlineNanos) throws IOException {
			readDeadlineNanos = deadlineNanos;
			if (terminalRead) return null;
			// A short negative-observation deadline can expire anywhere in a chunk.
			// Retain the bounded frame so the next observation resumes at its start.
			input.mark(2 * 1024 * 1024 + 2 * 65536);
			try {
				String size = line().split(";", 2)[0];
				int length = Integer.parseInt(size, 16);
				if (length == 0) {
					if (!line().isEmpty()) throw new IOException("Unexpected terminal trailer.");
					terminalRead = true;
					return null;
				}
				byte[] bytes = exact(length);
				if (!line().isEmpty()) throw new IOException("Missing chunk delimiter.");
				return bytes;
			} catch (java.net.SocketTimeoutException exception) {
				input.reset();
				throw exception;
			}
		}

		byte[] exact(int count) throws IOException {
			if (count < 0 || count > 2 * 1024 * 1024) throw new IOException("Body exceeded test bound.");
			byte[] bytes = new byte[count];
			for (int offset = 0; offset < count;) {
				applyReadDeadline();
				int read = input.read(bytes, offset, count - offset);
				if (read < 0) throw new EOFException("Response ended before its complete body.");
				offset += read;
			}
			return bytes;
		}

		String line() throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			for (int count = 0; count < 65536; count++) {
				applyReadDeadline();
				int value = input.read();
				if (value < 0) throw new EOFException("Response ended before its framing.");
				if (value == '\r') {
					applyReadDeadline();
					if (input.read() != '\n') throw new IOException("Invalid response line delimiter.");
					return bytes.toString(StandardCharsets.ISO_8859_1);
				}
				bytes.write(value);
			}
			throw new IOException("Response line exceeded test bound.");
		}

		private void applyReadDeadline() throws IOException {
			long remaining = readDeadlineNanos - System.nanoTime();
			if (remaining <= 0) throw new java.net.SocketTimeoutException("Response exceeded its shared read deadline.");
			socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
		}

		@Override public void close() throws IOException { socket.close(); }
	}
}
