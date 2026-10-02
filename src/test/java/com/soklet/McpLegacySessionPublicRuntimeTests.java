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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP session integration using only Soklet's supported application API. */
@Timeout(60)
class McpLegacySessionPublicRuntimeTests {
	private static final List<McpProtocolVersion> LEGACY = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> ALL = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final LifecyclePolicy LIFECYCLE = LifecyclePolicy.builder()
			.startupTimeout(WAIT).startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(1))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	private static final McpToolHandler<McpJsonObject> QUIET =
			(requestContext, toolArguments, invocationFeatures) -> McpCompleteResult.fromToolText("ok");

	@Test
	void defaultStatelessServersNeitherIssueNorRequireSessionIds() throws Exception {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("plain", "1").build(), ALL)
				.toolRegistrations(List.of(McpToolRegistration.withName("work", ALL)
						.jsonObjectArguments().handler(QUIET).build())).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1")
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.toolRateLimiter(context -> McpRateLimitDecision.allowed()).build();
		try (Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LIFECYCLE).build())) {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			for (McpProtocolVersion version : LEGACY) {
				try (RawClient initialize = new RawClient(port, "/mcp",
						body(version, "initialize", initParams(version, "{}", "plain"), "\"initialize\""), List.of(), 0)) {
					Head head = initialize.readHead();
					assertEquals(200, head.status());
					assertNull(head.header("Mcp-Session-Id"));
					assertTrue(initialize.readBody(head).contains("\"result\""));
				}
				try (RawClient call = new RawClient(port, "/mcp", body(version, "tools/call", toolParams(""), "\"plain\""),
						List.of(new HeaderValue("MCP-Protocol-Version", version.getWireValue())), 0)) {
					Head head = call.readHead();
					assertEquals(200, head.status());
					assertTrue(call.readBody(head).contains("\"text\":\"ok\""));
				}
			}
		}
	}

	@Test
	void ownerResolverFailureAndInvalidKeysFailClosedWithoutPublishingIdentifiers() throws Exception {
		List<McpSessionOwnerKeyResolver> resolvers = List.of(
				identity -> null,
				identity -> " ",
				identity -> "x".repeat(257),
				identity -> "bad\ud800key",
				identity -> { throw new Exception("owner-resolution-secret"); });
		for (McpSessionOwnerKeyResolver resolver : resolvers)
			try (Fixture fixture = new Fixture(QUIET, builder -> builder,
					builder -> builder.sessionConfig(McpSessionConfig.withOwnerKeyResolver(resolver).build()))) {
				for (McpProtocolVersion version : LEGACY) {
					Response failure = fixture.initialize(version, "alice", null, "{}", "test");
					assertEquals(200, failure.status(), failure.body());
					assertTrue(failure.body().contains("\"code\":-32603"), failure.body());
					assertNull(failure.header("Mcp-Session-Id"));
					assertFalse(failure.body().contains("owner-resolution-secret"));
				}
			}
	}

	@Test
	void ownerResolutionUsesTheRequestDeadlineAndCannotPublishAfterTimeout() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		McpSessionOwnerKeyResolver resolver = identity -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return "alice";
		};
		try (Fixture fixture = new Fixture(QUIET, builder -> builder,
				builder -> builder.requestTimeout(Duration.ofMillis(250))
						.sessionConfig(McpSessionConfig.withOwnerKeyResolver(resolver).build()))) {
			try {
				Response response = fixture.initialize(McpProtocolVersion.V2025_11_25, "alice", null, "{}", "timeout");
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				assertTrue(Set.of(503, 504).contains(response.status()), response.body());
				assertNull(response.header("Mcp-Session-Id"));
			} finally {
				release.countDown();
			}
			awaitCondition(() -> fixture.server.getDiagnostics().getActiveHandlerExecutions() == 0);
		} finally {
			release.countDown();
		}
	}

	@Test
	void explicitCancellationCompletesFiniteAndCommittedSseBodiesWithoutAResultAndRetainsPhysicalWork() throws Exception {
		for (McpProtocolVersion version : LEGACY)
			for (boolean reports : List.of(false, true)) {
				Held held = new Held(reports);
				try (Fixture fixture = new Fixture(held::handle)) {
					String id = sessionId(fixture.initialize(version, "alice", null, "{}", "test"));
					try (RawClient target = fixture.open("/mcp", version, "tools/call",
							toolParams(reports ? "\"_meta\":{\"progressToken\":\"progress\"}" : ""),
							"\"pending\"", id, "alice", List.of(), 0)) {
						assertTrue(held.entered.await(5, TimeUnit.SECONDS));
						Head head = null;
						if (reports) {
							head = target.readHead();
							assertSse(head);
							String progress = new String(target.readChunk(), StandardCharsets.UTF_8);
							assertTrue(progress.contains("\"method\":\"notifications/progress\""), progress);
						}
						Response cancellation = fixture.cancel(version, id, "alice", "\"pending\"");
						assertEquals(202, cancellation.status(), cancellation.body());
						assertEquals("", cancellation.body());
						assertTrue(held.canceled.await(5, TimeUnit.SECONDS));
						assertEquals(StreamTerminationReason.CLIENT_CANCELED,
								held.token.get().getCancelationReason().orElseThrow());
						if (head == null) head = target.readHead();
						assertSse(head);
						assertEquals("", target.readBody(head), "A canceled RPC emits no result or empty JSON document.");
						assertTrue(target.terminalRead, "The body must end at a real HTTP terminal chunk.");
						assertEquals(1, fixture.server.getDiagnostics().getActiveHandlerExecutions(),
								"Logical cancellation cannot release the blocked physical worker.");
						assertEquals(202, fixture.cancel(version, id, "alice", "\"pending\"").status(),
								"Completed/unknown targets have the same empty cancellation acknowledgement.");
					} finally {
						held.release.countDown();
					}
					assertTrue(held.exited.await(5, TimeUnit.SECONDS));
					awaitCondition(() -> fixture.server.getDiagnostics().getActiveHandlerExecutions() == 0);
				} finally {
					held.release.countDown();
				}
			}
	}

	@Test
	void blockedCancellationCallbacksDoNotHoldHttpCompletionButRetainTheirPhysicalExecutionSlot() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch handlerReturned = new CountDownLatch(1);
			CountDownLatch callbackEntered = new CountDownLatch(1);
			CountDownLatch callbackRelease = new CountDownLatch(1);
			CountDownLatch callbackExited = new CountDownLatch(1);
			AtomicInteger handlerEntries = new AtomicInteger();
			AtomicReference<CancelationToken> token = new AtomicReference<>();
			try (Fixture fixture = new Fixture((requestContext, toolArguments, invocationFeatures) -> {
				handlerEntries.incrementAndGet();
				if (requestContext.getRequest().getHeader("X-Call-Tag").filter("probe"::equals).isPresent())
					return McpCompleteResult.fromToolText("probe-result");
				CancelationToken cancelationToken = invocationFeatures.getCancelationToken();
				token.set(cancelationToken);
				cancelationToken.onCancel(() -> {
					callbackEntered.countDown();
					try { awaitTestRelease(callbackRelease); }
					finally { callbackExited.countDown(); }
				});
				entered.countDown();
				boolean interrupted = false;
				try {
					long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
					while (!cancelationToken.isCanceled() && System.nanoTime() - deadline < 0L) {
						try { Thread.sleep(5); }
						catch (InterruptedException ignored) { interrupted = true; }
					}
					assertTrue(cancelationToken.isCanceled());
					return McpCompleteResult.fromToolText("suppressed-result");
				} finally {
					if (interrupted) Thread.currentThread().interrupt();
					handlerReturned.countDown();
				}
			}, builder -> builder, builder -> builder.requestHandlerConcurrency(1).requestHandlerQueueCapacity(1))) {
				String id = sessionId(fixture.initialize(version, "alice", null, "{}", "callbacks"));
				try (RawClient target = fixture.open("/mcp", version, "tools/call", toolParams(""),
						"\"callback-target\"", id, "alice", List.of(), 0)) {
					assertTrue(entered.await(5, TimeUnit.SECONDS));
					Response canceled = fixture.cancel(version, id, "alice", "\"callback-target\"");
					assertEquals(202, canceled.status(), canceled.body());
					assertEquals("", canceled.body());
					assertSseEmpty(target);
					assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
					assertTrue(handlerReturned.await(5, TimeUnit.SECONDS));
					assertEquals(StreamTerminationReason.CLIENT_CANCELED,
							token.get().getCancelationReason().orElseThrow());
					assertEquals(1, fixture.server.getDiagnostics().getActiveHandlerExecutions(),
							"The blocked callback retains its request's physical reservation after HTTP completion.");
					try (RawClient probe = fixture.open("/mcp", version, "tools/call", toolParams(""),
							"\"queued-probe\"", id, "alice", List.of(new HeaderValue("X-Call-Tag", "probe")), 0)) {
						awaitCondition(() -> fixture.server.getDiagnostics().getRequestHandlerQueueDepth() == 1);
						assertEquals(1, handlerEntries.get(), "The occupied physical slot cannot be reused while its callback runs.");
						callbackRelease.countDown();
						assertTrue(callbackExited.await(5, TimeUnit.SECONDS));
						Head head = probe.readHead();
						assertEquals(200, head.status());
						assertTrue(probe.readBody(head).contains("probe-result"));
					}
				} finally {
					callbackRelease.countDown();
				}
				awaitCondition(() -> fixture.server.getDiagnostics().getActiveHandlerExecutions() == 0);
			} finally {
				callbackRelease.countDown();
			}
		}
	}

	@Test
	void activeIdCollisionsAreTypedAndCancellationIsBoundToOneOwnerAndSession() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Map<String, Held> calls = new ConcurrentHashMap<>();
			Held integer = new Held(false);
			Held string = new Held(false);
			Held otherSession = new Held(false);
			calls.put("integer", integer);
			calls.put("string", string);
			calls.put("other", otherSession);
			AtomicInteger entries = new AtomicInteger();
			try (Fixture fixture = new Fixture((requestContext, toolArguments, invocationFeatures) -> {
				entries.incrementAndGet();
				Held call = calls.get(requestContext.getRequest().getHeader("X-Call-Tag").orElseThrow());
				return call.handle(requestContext, toolArguments, invocationFeatures);
			})) {
				String first = sessionId(fixture.initialize(version, "alice", null, "{}", "first"));
				String second = sessionId(fixture.initialize(version, "alice", null, "{}", "second"));
				try (RawClient integerTarget = fixture.open("/mcp", version, "tools/call", toolParams(""), "1",
						first, "alice", List.of(new HeaderValue("X-Call-Tag", "integer")), 0)) {
					assertTrue(integer.entered.await(5, TimeUnit.SECONDS));
					Response collision = fixture.post("/mcp", version, "tools/call", toolParams(""), "1",
							first, "alice", List.of(new HeaderValue("X-Call-Tag", "integer")));
					assertTrue(collision.body().contains("\"code\":-32600"), collision.body());
					assertEquals(1, entries.get());
					try (RawClient stringTarget = fixture.open("/mcp", version, "tools/call", toolParams(""), "\"1\"",
							first, "alice", List.of(new HeaderValue("X-Call-Tag", "string")), 0);
							RawClient otherTarget = fixture.open("/mcp", version, "tools/call", toolParams(""), "1",
									second, "alice", List.of(new HeaderValue("X-Call-Tag", "other")), 0)) {
						assertTrue(string.entered.await(5, TimeUnit.SECONDS));
						assertTrue(otherSession.entered.await(5, TimeUnit.SECONDS));
						assertEquals(3, entries.get());
						assertEquals(404, fixture.cancel(version, first, "bob", "1").status());
						assertFalse(integer.token.get().isCanceled());
						assertEquals(202, fixture.cancel(version, first, "alice", "1").status());
						assertTrue(integer.canceled.await(5, TimeUnit.SECONDS));
						assertFalse(string.token.get().isCanceled());
						assertFalse(otherSession.token.get().isCanceled());
						assertSseEmpty(integerTarget);
						assertEquals(202, fixture.cancel(version, first, "alice", "\"1\"").status());
						assertEquals(202, fixture.cancel(version, second, "alice", "1").status());
						assertTrue(string.canceled.await(5, TimeUnit.SECONDS));
						assertTrue(otherSession.canceled.await(5, TimeUnit.SECONDS));
						assertSseEmpty(stringTarget);
						assertSseEmpty(otherTarget);
					}
				} finally {
					integer.release.countDown();
					string.release.countDown();
					otherSession.release.countDown();
				}
			} finally {
				integer.release.countDown();
				string.release.countDown();
				otherSession.release.countDown();
			}
		}
	}

	@Test
	void resultReservationPreservesQueuedTerminalBytesAgainstALateCancellation() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicReference<CancelationToken> token = new AtomicReference<>();
			String payload = "x".repeat(512 * 1024) + "END-OF-RESULT";
			try (Fixture fixture = new Fixture((requestContext, toolArguments, invocationFeatures) -> {
				token.set(invocationFeatures.getCancelationToken());
				invocationFeatures.getProgressReporter().orElseThrow().report(McpProgressUpdate.withProgress(1.0).build());
				entered.countDown();
				assertTrue(release.await(5, TimeUnit.SECONDS));
				return McpCompleteResult.fromToolText(payload);
			})) {
				String id = sessionId(fixture.initialize(version, "alice", null, "{}", "test"));
				try (RawClient target = fixture.open("/mcp", version, "tools/call",
						toolParams("\"_meta\":{\"progressToken\":\"progress\"}"), "\"result\"",
						id, "alice", List.of(), 1024)) {
					assertTrue(entered.await(5, TimeUnit.SECONDS));
					Head head = target.readHead();
					assertSse(head);
					assertTrue(new String(target.readChunk(), StandardCharsets.UTF_8).contains("notifications/progress"));
					release.countDown();
					awaitCondition(() -> fixture.server.getDiagnostics().getActiveHandlerExecutions() == 0);
					assertEquals(202, fixture.cancel(version, id, "alice", "\"result\"").status());
					assertFalse(token.get().isCanceled(), "A reserved result wins against later client cancellation.");
					String terminal = target.readBody(head);
					assertTrue(terminal.contains("\"id\":\"result\""), terminal.substring(0, Math.min(128, terminal.length())));
					assertTrue(terminal.contains(payload), "Queued bytes must retain the entire final result.");
					assertTrue(terminal.endsWith("}\n\n"));
					assertTrue(target.terminalRead);
				}
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	void hardSessionExpiryCancelsPhysicalWorkAndCompletesItsUsablePostWithACorrelatedError() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Held held = new Held(false);
			try (Fixture fixture = new Fixture(held::handle, builder -> builder
					.maximumSessionIdleDuration(Duration.ofMillis(250))
					.maximumSessionDuration(Duration.ofMillis(500)), builder -> {})) {
				String id = sessionId(fixture.initialize(version, "alice", null, "{}", "test"));
				try (RawClient target = fixture.open("/mcp", version, "tools/call", toolParams(""),
						"\"expire\"", id, "alice", List.of(), 0)) {
					assertTrue(held.entered.await(5, TimeUnit.SECONDS));
					assertTrue(held.canceled.await(5, TimeUnit.SECONDS));
					assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, held.token.get().getCancelationReason().orElseThrow());
					Head head = target.readHead();
					String error = target.readBody(head);
					assertTrue(error.contains("\"id\":\"expire\""), error);
					assertTrue(error.contains("\"code\":-32603"), error);
					assertFalse(error.contains("late-result"));
					assertEquals(1, fixture.server.getDiagnostics().getActiveHandlerExecutions());
					assertEquals(404, fixture.call(version, id, "alice", "\"expired\"", "").status());
				} finally {
					held.release.countDown();
				}
				assertTrue(held.exited.await(5, TimeUnit.SECONDS));
			} finally {
				held.release.countDown();
			}
		}
	}

	private static void assertSse(Head head) {
		assertEquals(200, head.status());
		assertEquals("text/event-stream", head.header("Content-Type"));
		assertEquals("chunked", head.header("Transfer-Encoding"));
		assertNull(head.header("Content-Length"));
	}

	private static void assertSseEmpty(RawClient target) throws IOException {
		Head head = target.readHead();
		assertSse(head);
		assertEquals("", target.readBody(head));
		assertTrue(target.terminalRead);
	}

	private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L) Thread.sleep(5);
		assertTrue(condition.getAsBoolean(), "Timed out waiting for bounded runtime cleanup.");
	}

	private static void awaitTestRelease(CountDownLatch release) {
		boolean interrupted = false;
		long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
		try {
			while (release.getCount() != 0 && System.nanoTime() - deadline < 0L) {
				try { release.await(50, TimeUnit.MILLISECONDS); }
				catch (InterruptedException ignored) { interrupted = true; }
			}
			assertEquals(0, release.getCount(), "The fixture must release its deliberately blocked callback.");
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	private static final class Held {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch canceled = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch exited = new CountDownLatch(1);
		final AtomicReference<CancelationToken> token = new AtomicReference<>();
		final boolean reports;
		Held(boolean reports) { this.reports = reports; }
		McpOperationResult handle(McpRequestContext requestContext, McpToolArguments<McpJsonObject> arguments,
				McpInvocationFeatures features) {
			token.set(features.getCancelationToken());
			features.getCancelationToken().onCancel(canceled::countDown);
			if (reports) features.getProgressReporter().orElseThrow().report(McpProgressUpdate.withProgress(1.0).build());
			entered.countDown();
			boolean interrupted = false;
			long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
			try {
				while (release.getCount() != 0 && System.nanoTime() - deadline < 0L) {
					try { release.await(50, TimeUnit.MILLISECONDS); }
					catch (InterruptedException ignored) { interrupted = true; }
				}
				assertEquals(0, release.getCount(), "The fixture must release its deliberately uncooperative handler.");
				return McpCompleteResult.fromToolText("late-result");
			} finally {
				if (interrupted) Thread.currentThread().interrupt();
				exited.countDown();
			}
		}
	}

	@Test
	void initializationPublishesAUsableIdBeforeAcknowledgementAndKeepsMetadataSeparate() throws Exception {
		List<McpRequestContext> contexts = new CopyOnWriteArrayList<>();
		try (Fixture fixture = new Fixture((requestContext, toolArguments, invocationFeatures) -> {
			contexts.add(requestContext);
			return McpCompleteResult.fromToolText("ok");
		})) {
			for (McpProtocolVersion version : LEGACY) {
				Response initialized = fixture.initialize(version, "alice", null,
						"{\"experimental\":{\"example.test/capability\":{\"enabled\":true}}}", "client-" + version.getWireValue());
				String sessionId = sessionId(initialized);
				assertEquals(200, fixture.call(version, sessionId, "alice", "\"early\"",
						"\"_meta\":{\"example.test/message\":\"early\"}").status());
				Response ack = fixture.post("/mcp", version, "notifications/initialized",
						"\"_meta\":{\"example.test/ack\":true}", null, sessionId, "alice", List.of());
				assertEquals(202, ack.status());
				assertEquals("", ack.body());
				assertEquals(202, fixture.post("/mcp", version, "notifications/initialized", "", null,
						sessionId, "alice", List.of()).status());
				assertEquals(200, fixture.call(version, sessionId, "alice", "\"later\"",
						"\"_meta\":{\"example.test/message\":\"later\"}").status());
				McpRequestContext early = contexts.get(contexts.size() - 2);
				McpRequestContext later = contexts.get(contexts.size() - 1);
				for (McpRequestContext context : List.of(early, later)) {
					assertEquals("client-" + version.getWireValue(), context.getClientInfo().orElseThrow().getName());
					assertEquals(McpJsonObject.builder().put("experimental",
							McpJsonObject.builder().put("example.test/capability",
									McpJsonObject.builder().put("enabled", true).build()).build()).build(),
							context.getClientCapabilities().toJson());
					assertTrue(context.getRequestMetadata().find("example.test/initial").isEmpty());
					assertTrue(context.getRequestMetadata().find("io.modelcontextprotocol/clientCapabilities").isEmpty());
					assertEquals("alice", context.getAdmissionIdentity().getPrincipal().orElseThrow());
				}
				assertEquals(McpJsonObject.builder().put("example.test/message", "early").build(), early.getRequestMetadata());
				assertEquals(McpJsonObject.builder().put("example.test/message", "later").build(), later.getRequestMetadata());
				assertNotSame(early.getAdmissionIdentity(), later.getAdmissionIdentity(), "Every message has fresh admitted identity.");
			}
			assertEquals(10, fixture.admissions.get());
			assertEquals(10, fixture.limits.get());
		}
	}

	@Test
	void ownerPathAndExistenceFailuresAreNeutralAfterFreshAdmissionWhileRevisionMismatchPreservesTheSession() throws Exception {
		try (Fixture fixture = new Fixture(QUIET)) {
			for (McpProtocolVersion version : LEGACY) {
				String id = sessionId(fixture.initialize(version, "alice", null, "{}", "test"));
				Response foreign = fixture.call(version, id, "bob", "\"same\"", "");
				Response unknown = fixture.call(version, "another-server.v1", "alice", "\"same\"", "");
				Response wrongPath = fixture.post("/other", version, "tools/call", toolParams(""),
						"\"same\"", id, "alice", List.of());
				assertEquals(404, foreign.status(), foreign.body());
				assertEquals(404, unknown.status(), unknown.body());
				assertEquals(404, wrongPath.status(), wrongPath.body());
				assertEquals(foreign.body(), unknown.body());
				assertEquals(foreign.body(), wrongPath.body());
				McpProtocolVersion other = version == McpProtocolVersion.V2025_06_18
						? McpProtocolVersion.V2025_11_25 : McpProtocolVersion.V2025_06_18;
				assertEquals(400, fixture.call(other, id, "alice", "\"revision\"", "").status());
				assertEquals(200, fixture.call(version, id, "alice", "\"still-valid\"", "").status());
			}
			assertEquals(12, fixture.admissions.get());
			assertEquals(12, fixture.limits.get());
		}
	}

	@Test
	void sessionFramingCannotDowngradeAndDuplicateOrMalformedHeadersDoNotUseTheStore() throws Exception {
		try (Fixture fixture = new Fixture(QUIET)) {
			for (McpProtocolVersion version : LEGACY) {
				String id = sessionId(fixture.initialize(version, "alice", null, "{}", "test"));
				assertEquals(400, fixture.call(version, null, "alice", "\"missing\"", "").status());
				for (String malformed : List.of("", "space value", "x".repeat(257), "bad\u007fvalue"))
					assertEquals(400, fixture.call(version, malformed, "alice", "\"framing\"", "").status());
				assertEquals(400, fixture.post("/mcp", version, "tools/call", toolParams(""), "\"duplicate\"",
						id, "alice", List.of(new HeaderValue("Mcp-Session-Id", id))).status());
				assertEquals(200, fixture.call(version, id, "alice", "\"valid\"", "").status());
			}
		}
	}

	@Test
	void modernAndStatelessPathsStayIndependentOfConfiguredSessions() throws Exception {
		try (Fixture fixture = new Fixture(QUIET)) {
			for (McpProtocolVersion version : LEGACY) {
				Response initialize = fixture.post("/stateless", version, "initialize", initParams(version, "{}", "plain"),
						"\"init\"", null, "alice", List.of());
				assertEquals(200, initialize.status(), initialize.body());
				assertNull(initialize.header("Mcp-Session-Id"));
				assertEquals(200, fixture.post("/stateless", version, "tools/call", toolParams(""),
						"\"plain\"", null, "alice", List.of()).status());
			}
			Response modern = fixture.post("/mcp", McpProtocolVersion.V2026_07_28, "server/discover", "",
					"\"modern\"", null, "alice", List.of());
			assertEquals(200, modern.status(), modern.body());
			assertNull(modern.header("Mcp-Session-Id"));
			assertEquals(200, fixture.post("/mcp", McpProtocolVersion.V2026_07_28, "tools/call", toolParams(""),
					"\"modern-tool\"", null, "alice", List.of()).status());
		}
	}

	@Test
	void initializationWithAStaleOrExistingWellFormedIdAllocatesANewRecord() throws Exception {
		try (Fixture fixture = new Fixture(QUIET)) {
			for (McpProtocolVersion version : LEGACY) {
				String first = sessionId(fixture.initialize(version, "alice", "old-node.token", "{}", "first"));
				String second = sessionId(fixture.initialize(version, "alice", first, "{}", "second"));
				assertNotEquals(first, second);
				assertEquals(200, fixture.call(version, first, "alice", "\"first\"", "").status());
				assertEquals(200, fixture.call(version, second, "alice", "\"second\"", "").status());
			}
		}
	}

	@Test
	void corsAllowsAndExposesSessionHeadersOnlyOnExplicitSessionEndpoints() throws Exception {
		String origin = "https://client.example.test";
		try (Fixture fixture = new Fixture(QUIET, builder -> builder,
				builder -> builder.corsAuthorizer(CorsAuthorizer.fromWhitelistedOrigins(Set.of(origin))))) {
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(2)).build();
			for (String path : List.of("/mcp", "/stateless")) {
				java.net.http.HttpRequest preflight = java.net.http.HttpRequest.newBuilder(
						java.net.URI.create("http://127.0.0.1:" + fixture.port + path))
						.timeout(Duration.ofSeconds(5)).method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody())
						.header("Origin", origin).header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "Mcp-Session-Id, MCP-Protocol-Version").build();
				assertEquals(path.equals("/mcp") ? 204 : 403,
						client.send(preflight, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
			}
			assertEquals(0, fixture.admissions.get(), "Preflight cannot admit or create sessions.");
			for (String path : List.of("/mcp", "/stateless")) {
				Response response = fixture.post(path, McpProtocolVersion.V2025_11_25, "initialize",
						initParams(McpProtocolVersion.V2025_11_25, "{}", "cors"), "\"init\"", null, "alice",
						List.of(new HeaderValue("Origin", origin)));
				assertEquals(200, response.status(), response.body());
				assertEquals(path.equals("/mcp"), response.header("Access-Control-Expose-Headers")
						.toLowerCase(Locale.ROOT).contains("mcp-session-id"));
			}
		}
	}

	@Test
	void anonymousAllocationRequiresExplicitOptInAndUsesFreshAdmission() throws Exception {
		try (Fixture fixture = new Fixture(QUIET)) {
			for (McpProtocolVersion version : LEGACY) {
				Response denied = fixture.initialize(version, "anonymous", null, "{}", "public");
				assertEquals(403, denied.status(), denied.body());
				assertNull(denied.header("Mcp-Session-Id"));
			}
			assertEquals(0, fixture.owners.get());
		}
		try (Fixture fixture = new Fixture(QUIET, builder -> builder.anonymousSessionsAllowed(true), builder -> {})) {
			for (McpProtocolVersion version : LEGACY) {
				String id = sessionId(fixture.initialize(version, "anonymous", null, "{}", "public"));
				assertEquals(200, fixture.call(version, id, "anonymous", "\"public\"", "").status());
				assertEquals(404, fixture.call(version, id, "alice", "\"public\"", "").status());
			}
		}
	}

	@Test
	void ownerAndGlobalCapacityFailuresHaveDistinctBoundedRetryStatuses() throws Exception {
		try (Fixture fixture = new Fixture(QUIET,
				builder -> builder.maximumSessions(2).maximumSessionsPerOwner(1), builder -> {})) {
			String alice = sessionId(fixture.initialize(McpProtocolVersion.V2025_06_18, "alice", null, "{}", "a"));
			Response ownerFull = fixture.initialize(McpProtocolVersion.V2025_11_25, "alice", null, "{}", "a2");
			assertEquals(429, ownerFull.status(), ownerFull.body());
			assertNotNull(ownerFull.header("Retry-After"));
			sessionId(fixture.initialize(McpProtocolVersion.V2025_11_25, "bob", null, "{}", "b"));
			Response globalFull = fixture.initialize(McpProtocolVersion.V2025_06_18, "charlie", null, "{}", "c");
			assertEquals(503, globalFull.status(), globalFull.body());
			assertNotNull(globalFull.header("Retry-After"));
			assertEquals(200, fixture.call(McpProtocolVersion.V2025_06_18, alice, "alice", "\"alive\"", "").status());
		}
	}

	@Test
	void oversizedClientSnapshotDoesNotPublishASessionId() throws Exception {
		try (Fixture fixture = new Fixture(QUIET, builder -> builder.maximumClientMetadataSizeInBytes(128), builder -> {})) {
			for (McpProtocolVersion version : LEGACY) {
				Response oversized = fixture.initialize(version, "alice", null,
						"{\"experimental\":{\"example.test/padding\":{\"value\":\"" + "x".repeat(256) + "\"}}}", "large");
				assertNull(oversized.header("Mcp-Session-Id"));
				assertTrue(oversized.body().contains("\"error\""), oversized.body());
				sessionId(fixture.initialize(version, "alice", null, "{}", "small"));
			}
		}
	}

	@Test
	void currentLimiterAndAdmissionRunBeforeSessionLookupAndReservedPolicyStatusesAreRemapped() throws Exception {
		try (Fixture fixture = new Fixture(QUIET)) {
			for (McpProtocolVersion version : LEGACY) {
				String id = sessionId(fixture.initialize(version, "alice", null, "{}", "test"));
				for (int status : List.of(400, 404, 405)) {
					Response denied = fixture.post("/mcp", version, "tools/call", toolParams(""), "\"policy\"",
							id, "alice", List.of(new HeaderValue("X-Admission-Status", Integer.toString(status))));
					assertEquals(403, denied.status(), denied.body());
				}
				assertEquals(401, fixture.post("/mcp", version, "tools/call", toolParams(""), "\"challenge\"",
						id, "alice", List.of(new HeaderValue("X-Admission-Status", "401"))).status());
				assertEquals(429, fixture.post("/mcp", version, "tools/call", toolParams(""), "\"limited\"",
						"unknown.valid", "alice", List.of(new HeaderValue("X-Limit", "deny"))).status());
				assertEquals(200, fixture.call(version, id, "alice", "\"allowed\"", "").status());
			}
			assertEquals(14, fixture.admissions.get());
			assertEquals(6, fixture.limits.get(), "Rejected admission does not charge the current request limiter.");
		}
	}

	@Test
	void quiescentExpiryReturnsTheSameNeutralUnknownResponseAndAllowsReinitialization() throws Exception {
		try (Fixture fixture = new Fixture(QUIET, builder -> builder
				.maximumSessionIdleDuration(Duration.ofMillis(250))
				.maximumSessionDuration(Duration.ofSeconds(2)), builder -> {})) {
			for (McpProtocolVersion version : LEGACY) {
				String id = sessionId(fixture.initialize(version, "alice", null, "{}", "test"));
				assertEquals(200, fixture.call(version, id, "alice", "\"evidence\"", "").status());
				Thread.sleep(400);
				Response expired = fixture.call(version, id, "alice", "\"same\"", "");
				Response unknown = fixture.call(version, "unknown.expired", "alice", "\"same\"", "");
				assertEquals(404, expired.status(), expired.body());
				assertEquals(unknown.body(), expired.body());
				sessionId(fixture.initialize(version, "alice", null, "{}", "reconnected"));
			}
		}
	}

	private static String sessionId(Response response) {
		assertEquals(200, response.status(), response.body());
		String id = response.header("Mcp-Session-Id");
		assertNotNull(id, "Initialization must publish its allocated ID in the response head.");
		assertTrue(id.matches("[A-Za-z0-9_-]{43}"), "Session IDs use 256 random bits encoded as unpadded base64url.");
		return id;
	}

	private static String initParams(McpProtocolVersion version, String capabilities, String clientName) {
		return "\"protocolVersion\":\"" + version.getWireValue() + "\",\"capabilities\":" + capabilities
				+ ",\"clientInfo\":{\"name\":\"" + clientName + "\",\"version\":\"1\"},"
				+ "\"_meta\":{\"example.test/initial\":\"initial-only\"}";
	}

	private static String toolParams(String extra) {
		return "\"name\":\"work\",\"arguments\":{}" + (extra.isEmpty() ? "" : "," + extra);
	}

	private static String body(McpProtocolVersion version, String method, String params, String idJson) {
		String metadata = version == McpProtocolVersion.V2026_07_28
				? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}"
				: "";
		String fields = metadata + (metadata.isEmpty() || params.isEmpty() ? "" : ",") + params;
		return "{\"jsonrpc\":\"2.0\"," + (idJson == null ? "" : "\"id\":" + idJson + ",")
				+ "\"method\":\"" + method + "\",\"params\":{" + fields + "}}";
	}

	private static final class Fixture implements AutoCloseable {
		final AtomicInteger admissions = new AtomicInteger();
		final AtomicInteger limits = new AtomicInteger();
		final AtomicInteger owners = new AtomicInteger();
		final McpServer server;
		final Soklet soklet;
		final int port;

		Fixture(McpToolHandler<McpJsonObject> handler) throws Exception {
			this(handler, builder -> builder, builder -> {});
		}

		Fixture(McpToolHandler<McpJsonObject> handler, UnaryOperator<McpSessionConfig.Builder> configureSessions,
				Consumer<McpServer.Builder> configureServer) throws Exception {
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("work", ALL)
					.jsonObjectArguments().handler(handler).build();
			McpImplementation implementation = McpImplementation.withNameAndVersion("session-test", "1").build();
			McpEndpoint main = McpEndpoint.withPath("/mcp", implementation, ALL)
					.sessionProtocolVersions(Set.copyOf(LEGACY)).toolRegistrations(List.of(tool)).build();
			McpEndpoint other = McpEndpoint.withPath("/other", implementation, ALL)
					.sessionProtocolVersions(Set.copyOf(LEGACY)).toolRegistrations(List.of(tool)).build();
			McpEndpoint stateless = McpEndpoint.withPath("/stateless", implementation, ALL)
					.toolRegistrations(List.of(tool)).build();
			McpSessionConfig config = configureSessions.apply(McpSessionConfig.withOwnerKeyResolver(identity -> {
				owners.incrementAndGet();
				return identity.getPrincipal().map(Object::toString).orElse("public-owner");
			})).build();
			McpServer.Builder builder = McpServer.withPort(0).host("127.0.0.1")
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(main, other, stateless)))
					.sessionConfig(config).allowedHosts(Set.of("127.0.0.1"))
					.requestTimeout(Duration.ofSeconds(10))
					.admissionController(context -> {
						admissions.incrementAndGet();
						String denied = context.getRequest().getHeader("X-Admission-Status").orElse(null);
						if (denied != null)
							return McpAdmissionDecision.rejected(McpAdmissionRejection.withStatusCodeAndError(
									Integer.parseInt(denied), McpJsonRpcError.fromApplication(-31903, "Denied")).build());
						String subject = context.getRequest().getHeader("X-Subject").orElse("alice");
						if (subject.equals("anonymous"))
							return McpAdmissionDecision.accepted();
						return McpAdmissionDecision.accepted(McpAdmissionIdentity.withRateLimitPartitionKey("shared-quota")
								.authorizationPartitionKey("shared-authorization").principal(subject)
								.applicationContext(new Object()).build());
					})
					.requestRateLimiter(context -> {
						limits.incrementAndGet();
						return context.getRequest().getHeader("X-Limit").filter("deny"::equals).isPresent()
								? McpRateLimitDecision.denied(Duration.ofSeconds(1)) : McpRateLimitDecision.allowed();
					})
					.toolRateLimiter(context -> McpRateLimitDecision.allowed());
			configureServer.accept(builder);
			this.server = builder.build();
			this.soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.lifecyclePolicy(LIFECYCLE).build());
			try {
				this.soklet.start();
				this.port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			} catch (Throwable failure) {
				this.soklet.close();
				throw failure;
			}
		}

		Response initialize(McpProtocolVersion version, String subject, String sessionId,
				String capabilities, String clientName) throws Exception {
			return post("/mcp", version, "initialize", initParams(version, capabilities, clientName),
					"\"initialize\"", sessionId, subject, List.of());
		}

		Response call(McpProtocolVersion version, String sessionId, String subject, String idJson, String extra) throws Exception {
			return post("/mcp", version, "tools/call", toolParams(extra), idJson, sessionId, subject, List.of());
		}

		Response cancel(McpProtocolVersion version, String sessionId, String subject, String requestIdJson) throws Exception {
			return post("/mcp", version, "notifications/cancelled", "\"requestId\":" + requestIdJson
					+ ",\"reason\":\"manual cancellation\"", null, sessionId, subject, List.of());
		}

		Response post(String path, McpProtocolVersion version, String method, String params,
				String idJson, String sessionId, String subject, List<HeaderValue> extra) throws Exception {
			try (RawClient client = open(path, version, method, params, idJson, sessionId, subject, extra, 0)) {
				Head head = client.readHead();
				return new Response(head.status(), head.headers(), client.readBody(head));
			}
		}

		RawClient open(String path, McpProtocolVersion version, String method, String params,
				String idJson, String sessionId, String subject, List<HeaderValue> extra, int receiveBuffer) throws Exception {
			List<HeaderValue> headers = new ArrayList<>();
			if (!method.equals("initialize") || version == McpProtocolVersion.V2026_07_28)
				headers.add(new HeaderValue("MCP-Protocol-Version", version.getWireValue()));
			if (version == McpProtocolVersion.V2026_07_28) {
				headers.add(new HeaderValue("Mcp-Method", method));
				if (method.equals("tools/call")) headers.add(new HeaderValue("Mcp-Name", "work"));
			}
			if (sessionId != null) headers.add(new HeaderValue("Mcp-Session-Id", sessionId));
			headers.add(new HeaderValue("X-Subject", subject));
			headers.addAll(extra);
			return new RawClient(port, path, body(version, method, params, idJson), headers, receiveBuffer);
		}

		@Override public void close() { this.soklet.close(); }
	}

	private record HeaderValue(String name, String value) {}
	private record Response(int status, Map<String, List<String>> headers, String body) {
		String header(String name) {
			List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
			return values == null ? null : values.get(0);
		}
	}
	private record Head(int status, Map<String, List<String>> headers) {
		String header(String name) {
			List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
			return values == null ? null : values.get(0);
		}
	}

	/** Bounded test-only HTTP/1.1 reader; preserves duplicate request fields and empty chunked completion. */
	private static final class RawClient implements AutoCloseable {
		final Socket socket = new Socket();
		final InputStream input;
		boolean terminalRead;

		RawClient(int port, String path, String body, List<HeaderValue> headers, int receiveBuffer) throws IOException {
			if (receiveBuffer > 0) socket.setReceiveBufferSize(receiveBuffer);
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(5000);
			socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
			this.input = socket.getInputStream();
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			StringBuilder head = new StringBuilder("POST ").append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1:")
					.append(port).append("\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n");
			for (HeaderValue header : headers) head.append(header.name()).append(": ").append(header.value()).append("\r\n");
			head.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
			socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().write(bytes);
			socket.getOutputStream().flush();
		}

		Head readHead() throws IOException {
			String status = line();
			Map<String, List<String>> headers = new LinkedHashMap<>();
			for (int count = 0; count < 128; count++) {
				String line = line();
				if (line.isEmpty()) {
					assertEquals(List.of("no-store"), headers.get("cache-control"),
							"Session framing, admission remapping, JSON-RPC normalization, and streaming preserve no-store.");
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
			if ("chunked".equalsIgnoreCase(head.header("Transfer-Encoding"))) {
				ByteArrayOutputStream body = new ByteArrayOutputStream();
				for (int count = 0; count < 256; count++) {
					byte[] chunk = readChunk();
					if (chunk == null) return body.toString(StandardCharsets.UTF_8);
					if (body.size() + chunk.length > 2 * 1024 * 1024) throw new IOException("Response exceeded test bound.");
					body.write(chunk);
				}
				throw new IOException("Chunk count exceeded test bound.");
			}
			return new String(exact(Integer.parseInt(head.header("Content-Length"))), StandardCharsets.UTF_8);
		}

		byte[] readChunk() throws IOException {
			if (terminalRead) return null;
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
		}

		byte[] exact(int count) throws IOException {
			if (count < 0 || count > 2 * 1024 * 1024) throw new IOException("Body exceeded test bound.");
			byte[] bytes = input.readNBytes(count);
			if (bytes.length != count) throw new EOFException("Response ended before its complete body.");
			return bytes;
		}

		String line() throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			for (int count = 0; count < 65536; count++) {
				int value = input.read();
				if (value < 0) throw new EOFException("Response ended before its framing.");
				if (value == '\r') {
					if (input.read() != '\n') throw new IOException("Invalid response line delimiter.");
					return bytes.toString(StandardCharsets.ISO_8859_1);
				}
				bytes.write(value);
			}
			throw new IOException("Response line exceeded test bound.");
		}

		@Override public void close() throws IOException { socket.close(); }
	}
}
