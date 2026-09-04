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

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-listener evidence for application-orchestrated localization reloads in
 * a two-node fleet. Each node owns its translation snapshot and local
 * invalidation control; Soklet owns request-local snapshot consistency and
 * listener cleanup, but no distributed session or fleet activation protocol.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@Timeout(60)
class McpLocalizationFleetPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/localization/fleet";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final Duration NO_EVENT_WAIT = Duration.ofMillis(500);
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancelationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownTimeout(Duration.ofSeconds(2))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build();

	@Test
	void failedFleetReloadPreservesBothOldSnapshotsAndPublishesNoInvalidation()
			throws Exception {
		TwoNodeFleet fleet = new TwoNodeFleet();
		LiveSubscription firstSubscription = null;
		LiveSubscription secondSubscription = null;

		try {
			fleet.start();
			LiveSubscription first =
					fleet.first().subscribe("failed-reload", "fr-CA");
			LiveSubscription second =
					fleet.second().subscribe("failed-reload", "fr-CA");
			firstSubscription = first;
			secondSubscription = second;
			assertAcknowledgment(first.nextFrame(WAIT));
			assertAcknowledgment(second.nextFrame(WAIT));
			awaitRuntime(first.node(), 1, 1);
			awaitRuntime(second.node(), 1, 1);
			CatalogSnapshot oldFirst = fleet.first().activeSnapshot();
			CatalogSnapshot oldSecond = fleet.second().activeSnapshot();
			assertNotSame(oldFirst, oldSecond);
			assertEquals(oldFirst, oldSecond);

			CatalogSnapshot validForFirst = CatalogSnapshot.valid("R42");
			CatalogSnapshot invalidForSecond = CatalogSnapshot.invalid("R42");
			assertThrows(CatalogValidationException.class,
					() -> fleet.stage(validForFirst, invalidForSecond));

			assertSame(oldFirst, fleet.first().activeSnapshot());
			assertSame(oldSecond, fleet.second().activeSnapshot());
			assertEquals(1, fleet.first().validationCount());
			assertEquals(1, fleet.second().validationCount());
			assertEquals(0, fleet.first().invalidationCount());
			assertEquals(0, fleet.second().invalidationCount());
			assertThrows(SocketTimeoutException.class,
					() -> first.nextFrame(NO_EVENT_WAIT));
			assertThrows(SocketTimeoutException.class,
					() -> second.nextFrame(NO_EVENT_WAIT));

			assertCoherentRevision(fleet.first().toolsList("failed-first"),
					oldFirst, validForFirst);
			assertCoherentRevision(fleet.second().toolsList("failed-second"),
					oldSecond, invalidForSecond);
			assertLatestContextRevision(fleet.first(), oldFirst);
			assertLatestContextRevision(fleet.second(), oldSecond);
		} finally {
			close(firstSubscription);
			close(secondSubscription);
			fleet.close();
			assertStoppedWithRetainedAddress(fleet.first());
			assertStoppedWithRetainedAddress(fleet.second());
		}
	}

	@Test
	void rollingActivationAllowsRevisionDriftBetweenNodesButNeverWithinAResponse()
			throws Exception {
		TwoNodeFleet fleet = new TwoNodeFleet();
		LiveSubscription firstSubscription = null;
		LiveSubscription secondSubscription = null;

		try {
			fleet.start();
			LiveSubscription first =
					fleet.first().subscribe("rolling-first", "fr-CA");
			LiveSubscription second =
					fleet.second().subscribe("rolling-second", "fr-CA");
			firstSubscription = first;
			secondSubscription = second;
			assertAcknowledgment(first.nextFrame(WAIT));
			assertAcknowledgment(second.nextFrame(WAIT));
			awaitRuntime(fleet.first(), 1, 1);
			awaitRuntime(fleet.second(), 1, 1);
			CatalogSnapshot firstR41 = fleet.first().activeSnapshot();
			CatalogSnapshot secondR41 = fleet.second().activeSnapshot();
			CatalogSnapshot firstR42 = CatalogSnapshot.valid("R42");
			CatalogSnapshot secondR42 = CatalogSnapshot.valid("R42");
			assertNotSame(firstR41, secondR41);
			assertNotSame(firstR42, secondR42);

			assertCoherentRevision(fleet.first().toolsList("baseline-first"),
					firstR41, firstR42);
			assertCoherentRevision(fleet.second().toolsList("baseline-second"),
					secondR41, secondR42);
			fleet.stage(firstR42, secondR42);

			RenderPause pause = fleet.first().pauseAfterFirstLocalizationLookup();
			CompletableFuture<String> parkedResponse = CompletableFuture.supplyAsync(
					() -> toolsListUnchecked(fleet.first(), "parked-old-response"));
			pause.awaitFirstLookup();
			try {
				fleet.activateFirst();
			} finally {
				pause.release();
			}
			assertCoherentRevision(await(parkedResponse), firstR41, firstR42);
			assertLatestContextRevision(fleet.first(), firstR41);
			assertListChanged(first.nextFrame(WAIT));
			assertThrows(SocketTimeoutException.class,
					() -> second.nextFrame(NO_EVENT_WAIT));

			assertCoherentRevision(fleet.first().toolsList("drift-first"),
					firstR42, firstR41);
			assertCoherentRevision(fleet.second().toolsList("drift-second"),
					secondR41, secondR42);
			assertLatestContextRevision(fleet.first(), firstR42);
			assertLatestContextRevision(fleet.second(), secondR41);

			fleet.activateSecond();
			assertListChanged(second.nextFrame(WAIT));
			assertThrows(SocketTimeoutException.class,
					() -> first.nextFrame(NO_EVENT_WAIT));
			assertCoherentRevision(fleet.first().toolsList("converged-first"),
					firstR42, firstR41);
			assertCoherentRevision(fleet.second().toolsList("converged-second"),
					secondR42, secondR41);
			assertLatestContextRevision(fleet.first(), firstR42);
			assertLatestContextRevision(fleet.second(), secondR42);
		} finally {
			close(firstSubscription);
			close(secondSubscription);
			fleet.close();
			assertStoppedWithRetainedAddress(fleet.first());
			assertStoppedWithRetainedAddress(fleet.second());
		}
	}

	@Test
	void nodeLossAndSubscriptionReconnectNeedNoSessionRecoveryAndReleaseFleetResources()
			throws Exception {
		TwoNodeFleet fleet = new TwoNodeFleet();
		LiveSubscription firstSubscription = null;
		LiveSubscription reconnectedSubscription = null;

		try {
			fleet.start();
			firstSubscription = fleet.first().subscribe("portable-subscription", "fr-CA");
			assertAcknowledgment(firstSubscription.nextFrame(WAIT));
			assertEquals("fr-CA", firstSubscription.contentLanguage());
			awaitRuntime(fleet.first(), 1, 1);
			assertEquals(1, fleet.first().contextCount());
			assertEquals(0, fleet.second().contextCount());
			assertEquals(0, fleet.second().server().getDiagnostics()
					.getActiveSubscriptions());

			fleet.first().stop();
			firstSubscription.awaitTransportClosed(WAIT);
			assertStoppedWithRetainedAddress(fleet.first());
			assertEquals(0, fleet.second().server().getDiagnostics()
					.getActiveSubscriptions());

			reconnectedSubscription = fleet.second().subscribe(
					"portable-subscription", "fr-CA");
			assertAcknowledgment(reconnectedSubscription.nextFrame(WAIT));
			assertEquals("fr-CA", reconnectedSubscription.contentLanguage());
			awaitRuntime(fleet.second(), 1, 1);
			assertEquals(1, fleet.second().contextCount());

			ContextObservation firstContext = fleet.first().context(0);
			ContextObservation secondContext = fleet.second().context(0);
			assertEquals("node-a", firstContext.node());
			assertEquals("node-b", secondContext.node());
			assertNotSame(firstContext.context(), secondContext.context());
			assertEquals(firstContext.languageRanges(), secondContext.languageRanges());
			assertEquals("fr-ca", firstContext.languageRanges().get(0).getRange());
			assertEquals(Locale.CANADA_FRENCH, firstContext.context().getLocale());
			assertEquals(Locale.CANADA_FRENCH, secondContext.context().getLocale());
			assertEquals(fleet.first().activeSnapshot().revision(),
					firstContext.context().getRevision().orElseThrow());
			assertEquals(fleet.second().activeSnapshot().revision(),
					secondContext.context().getRevision().orElseThrow());
			assertTrue(firstContext.continuationLocale().isEmpty());
			assertTrue(secondContext.continuationLocale().isEmpty());
			assertTrue(firstContext.resourceListCursor().isEmpty());
			assertTrue(secondContext.resourceListCursor().isEmpty());

			fleet.second().invalidateCatalogs();
			assertListChanged(reconnectedSubscription.nextFrame(WAIT));
			reconnectedSubscription.close();
			awaitRuntime(fleet.second(), 0, 0);
			fleet.second().stop();
			assertStoppedWithRetainedAddress(fleet.second());
		} finally {
			close(firstSubscription);
			close(reconnectedSubscription);
			fleet.close();
			assertStoppedWithRetainedAddress(fleet.first());
			assertStoppedWithRetainedAddress(fleet.second());
		}
	}

	private static String toolsListUnchecked(FleetNode node, String id) {
		try {
			return node.toolsList(id);
		} catch (Exception exception) {
			throw new CompletionException(exception);
		}
	}

	private static String await(CompletableFuture<String> future) {
		try {
			return future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (Exception exception) {
			throw new AssertionError("Timed out waiting for the parked response.",
					exception);
		}
	}

	private static void assertAcknowledgment(String frame) {
		assertTrue(frame.contains("\"method\":"
				+ "\"notifications/subscriptions/acknowledged\""), frame);
		assertTrue(frame.contains("\"toolsListChanged\":true"), frame);
		assertFalse(frame.contains("\"id\":"), frame);
	}

	private static void assertListChanged(String frame) {
		assertTrue(frame.contains("notifications/tools/list_changed"), frame);
		assertFalse(frame.contains("R41"), frame);
		assertFalse(frame.contains("R42"), frame);
	}

	private static void assertCoherentRevision(String body,
			CatalogSnapshot expected, CatalogSnapshot unexpected) {
		int expectedOccurrences = occurrences(body, expected.marker());
		assertTrue(expectedOccurrences >= 2,
				() -> "Expected at least two " + expected.marker()
						+ " localized slots, but found " + expectedOccurrences
						+ " in " + body);
		assertFalse(body.contains(unexpected.marker()), body);
	}

	private static void assertLatestContextRevision(FleetNode node,
			CatalogSnapshot expected) {
		assertEquals(expected.revision(), node.latestContext().context()
				.getRevision().orElseThrow());
	}

	private static int occurrences(String value, String needle) {
		int count = 0;
		int index = 0;
		while ((index = value.indexOf(needle, index)) >= 0) {
			++count;
			index += needle.length();
		}
		return count;
	}

	private static void awaitRuntime(FleetNode node, int streams,
			int subscriptions) {
		long deadline = System.nanoTime() + WAIT.toNanos();
		McpServerDiagnostics diagnostics;
		do {
			diagnostics = node.server().getDiagnostics();
			if (diagnostics.getActiveRequestStreams() == streams
					&& diagnostics.getActiveSubscriptions() == subscriptions)
				return;
			try {
				Thread.sleep(10L);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Runtime did not reach streams=" + streams
				+ ", subscriptions=" + subscriptions + "; diagnostics="
				+ diagnostics);
	}

	private static void assertStoppedWithRetainedAddress(FleetNode node) {
		McpServerDiagnostics diagnostics = node.server().getDiagnostics();
		assertEquals(McpServerStatus.TERMINATED, diagnostics.getStatus());
		assertTrue(diagnostics.getBoundAddress().isPresent());
		assertEquals(0, diagnostics.getActiveHandlerExecutions());
		assertEquals(0, diagnostics.getRequestHandlerQueueDepth());
		assertEquals(0, diagnostics.getActiveRequestStreams());
		assertEquals(0, diagnostics.getActiveSubscriptions());
	}

	private static void close(LiveSubscription subscription) {
		if (subscription != null)
			subscription.close();
	}

	private record CatalogSnapshot(String revisionValue, String marker,
			boolean valid) {
		private static CatalogSnapshot valid(String revision) {
			return new CatalogSnapshot(revision, '[' + revision + "]:", true);
		}

		private static CatalogSnapshot invalid(String revision) {
			return new CatalogSnapshot(revision, '[' + revision + "]:", false);
		}

		private McpLocalizationRevision revision() {
			return McpLocalizationRevision.fromValue(this.revisionValue);
		}
	}

	private static final class CatalogValidationException extends Exception {
		private CatalogValidationException(String message) {
			super(message);
		}
	}

	private static final class TwoNodeFleet implements AutoCloseable {
		private final FleetNode first = new FleetNode("node-a",
				CatalogSnapshot.valid("R41"));
		private final FleetNode second = new FleetNode("node-b",
				CatalogSnapshot.valid("R41"));
		private CatalogSnapshot stagedFirst;
		private CatalogSnapshot stagedSecond;

		private FleetNode first() {
			return this.first;
		}

		private FleetNode second() {
			return this.second;
		}

		private void start() {
			try {
				this.first.start();
				this.second.start();
			} catch (RuntimeException exception) {
				close();
				throw exception;
			}
		}

		private void stage(CatalogSnapshot firstCandidate,
				CatalogSnapshot secondCandidate) throws CatalogValidationException {
			validate(this.first, firstCandidate);
			validate(this.second, secondCandidate);
			this.stagedFirst = firstCandidate;
			this.stagedSecond = secondCandidate;
		}

		private void activateFirst() {
			if (this.stagedFirst == null)
				throw new IllegalStateException("No first-node catalog is staged.");
			this.first.activate(this.stagedFirst);
		}

		private void activateSecond() {
			if (this.stagedSecond == null)
				throw new IllegalStateException("No second-node catalog is staged.");
			this.second.activate(this.stagedSecond);
		}

		private static void validate(FleetNode node,
				CatalogSnapshot candidate) throws CatalogValidationException {
			node.recordValidation();
			if (!candidate.valid())
				throw new CatalogValidationException(
						"Catalog validation failed for " + node.name());
		}

		@Override
		public void close() {
			this.first.close();
			this.second.close();
		}
	}

	private static final class FleetNode implements AutoCloseable {
		private final String name;
		private final AtomicReference<CatalogSnapshot> activeSnapshot;
		private final AtomicReference<RenderPause> renderPause =
				new AtomicReference<>();
		private final AtomicInteger invalidations = new AtomicInteger();
		private final AtomicInteger validations = new AtomicInteger();
		private final CopyOnWriteArrayList<ContextObservation> contexts =
				new CopyOnWriteArrayList<>();
		private final CopyOnWriteArrayList<LiveSubscription> subscriptions =
				new CopyOnWriteArrayList<>();
		private final McpServer server;
		private final Soklet soklet;

		private FleetNode(String name, CatalogSnapshot initialSnapshot) {
			this.name = name;
			this.activeSnapshot = new AtomicReference<>(initialSnapshot);
			this.server = buildServer();
			this.soklet = Soklet.fromConfig(SokletConfig.withMcpServer(this.server)
					.resourceMethodResolver(
							ResourceMethodResolver.fromMethods(Set.of()))
					.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
					.build());
		}

		private String name() {
			return this.name;
		}

		private McpServer server() {
			return this.server;
		}

		private CatalogSnapshot activeSnapshot() {
			return this.activeSnapshot.get();
		}

		private int invalidationCount() {
			return this.invalidations.get();
		}

		private int validationCount() {
			return this.validations.get();
		}

		private int contextCount() {
			return this.contexts.size();
		}

		private ContextObservation context(int index) {
			return this.contexts.get(index);
		}

		private ContextObservation latestContext() {
			return this.contexts.get(this.contexts.size() - 1);
		}

		private void recordValidation() {
			this.validations.incrementAndGet();
		}

		private void start() {
			this.soklet.start();
		}

		private void stop() {
			this.soklet.close();
		}

		private void activate(CatalogSnapshot snapshot) {
			this.activeSnapshot.set(snapshot);
			invalidateCatalogs();
		}

		private void invalidateCatalogs() {
			this.server.getLocalizationControl().invalidateCatalogs();
			this.invalidations.incrementAndGet();
		}

		private RenderPause pauseAfterFirstLocalizationLookup() {
			RenderPause pause = new RenderPause();
			if (!this.renderPause.compareAndSet(null, pause))
				throw new IllegalStateException("A render pause is already armed.");
			return pause;
		}

		private LiveSubscription subscribe(String id, String language)
				throws IOException {
			LiveSubscription subscription = LiveSubscription.open(this, id,
					language);
			this.subscriptions.add(subscription);
			return subscription;
		}

		private String toolsList(String id) throws Exception {
			String body = requestBody(id, "tools/list",
					"", "");
			HttpRequest request = HttpRequest.newBuilder(uri())
					.timeout(WAIT)
					.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
					.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
					.header("Accept-Language", "fr-CA")
					.header("MCP-Protocol-Version", PROTOCOL_VERSION)
					.header("Mcp-Method", "tools/list")
					.POST(HttpRequest.BodyPublishers.ofString(body,
							StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = HTTP_CLIENT.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertEquals(200, response.statusCode(), response.body());
			return response.body();
		}

		private URI uri() {
			return URI.create("http://" + LOOPBACK + ':' + port() + MCP_PATH);
		}

		private int port() {
			return this.server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
		}

		private McpServer buildServer() {
			McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH, McpImplementation
							.withNameAndVersion(this.name, "1.0")
							.title("Canonical server title")
							.description("Canonical server description")
							.build())
					.addTool(McpToolRegistration.withName("fleet.tool")
							.jsonObjectArguments()
							.handler((request, arguments, features) ->
									McpCompleteResult.fromToolText("unused"))
							.title("Canonical tool title")
							.description("Canonical tool description")
							.build())
					.addResource(McpResourceRegistration.withUriAndName(
							URI.create("fleet://resource"), "fleet-resource")
							.handler((request, resource, features) ->
									McpCompleteResult.fromResourceOutput(
											McpResourceOutput.withContent(McpTextResourceContents
															.withUriAndText(
																	resource.getUri(), "unused")
															.build())
													.build()))
							.build())
					.subscriptionConfig(McpSubscriptionConfig
							.withEventPublisher(
									McpSubscriptionEventPublisher.fromInMemoryDefaults(),
									EnumSet.of(
											McpSubscriptionNotificationType
													.RESOURCES_LIST_CHANGED))
							.build())
					.build();
			McpLocalizer localizer = McpLocalizer
					.withFallbackLocale(Locale.ENGLISH, request -> {
						CatalogSnapshot captured = this.activeSnapshot.get();
						Locale selectedLocale = Locale.lookup(
								request.getLanguageRanges(),
								List.of(Locale.CANADA_FRENCH, Locale.FRENCH,
										Locale.ENGLISH));
						if (selectedLocale == null)
							selectedLocale = Locale.ENGLISH;
						AtomicInteger lookup = new AtomicInteger();
						McpLocalizationContext context = McpLocalizationContext
								.withLocale(selectedLocale, text -> {
									if (lookup.incrementAndGet() == 1) {
										RenderPause pause =
												this.renderPause.getAndSet(null);
										if (pause != null)
											pause.reachedFirstLookupAndAwaitRelease();
									}
									return McpLocalizationResult.localized(
											captured.marker()
													+ text.getDefaultText());
								})
								.revision(captured.revision())
								.build();
						this.contexts.add(new ContextObservation(this.name, context,
								request.getLanguageRanges(),
								request.getContinuationLocale(),
								request.getResourceListCursor()));
						return context;
					})
					.build();
			return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.host(LOOPBACK)
					.requestRateLimiter(context -> McpRateLimitDecision.allowed())
					.toolRateLimiter(context -> McpRateLimitDecision.allowed())
					.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
					.allowedHosts(Set.of(LOOPBACK))
					.maximumSubscriptionDuration(Duration.ofSeconds(30))
					.localizer(localizer)
					.build();
		}

		@Override
		public void close() {
			for (LiveSubscription subscription : this.subscriptions)
				subscription.close();
			this.soklet.close();
			this.contexts.clear();
		}
	}

	private record ContextObservation(String node,
			McpLocalizationContext context,
			List<Locale.LanguageRange> languageRanges,
			Optional<Locale> continuationLocale,
			Optional<String> resourceListCursor) {}

	private static final class RenderPause {
		private final CountDownLatch firstLookup = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);

		private void reachedFirstLookupAndAwaitRelease() {
			this.firstLookup.countDown();
			await(this.released, "release the parked localization render");
		}

		private void awaitFirstLookup() {
			await(this.firstLookup, "reach the first localization lookup");
		}

		private void release() {
			this.released.countDown();
		}

		private static void await(CountDownLatch latch, String action) {
			try {
				if (!latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
					throw new AssertionError("Timed out waiting to " + action + '.');
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		}
	}

	private static final class LiveSubscription implements AutoCloseable {
		private final FleetNode node;
		private final Socket socket;
		private final InputStream body;
		private final String contentLanguage;

		private LiveSubscription(FleetNode node, Socket socket, InputStream body,
				String contentLanguage) {
			this.node = node;
			this.socket = socket;
			this.body = body;
			this.contentLanguage = contentLanguage;
		}

		private static LiveSubscription open(FleetNode node, String id,
				String language) throws IOException {
			Socket socket = new Socket();
			try {
				socket.connect(new InetSocketAddress(LOOPBACK, node.port()),
						(int) WAIT.toMillis());
				socket.setSoTimeout((int) WAIT.toMillis());
				String body = requestBody(id, "subscriptions/listen",
						",\"notifications\":{\"toolsListChanged\":true}", "");
				byte[] encodedBody = body.getBytes(StandardCharsets.UTF_8);
				String head = "POST " + MCP_PATH + " HTTP/1.1\r\n"
						+ "Host: " + LOOPBACK + ':' + node.port() + "\r\n"
						+ "Content-Type: " + JSON_MEDIA_TYPE
						+ "; charset=UTF-8\r\n"
						+ "Accept: " + JSON_MEDIA_TYPE
						+ ", text/event-stream\r\n"
						+ "Accept-Language: " + language + "\r\n"
						+ "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
						+ "Mcp-Method: subscriptions/listen\r\n"
						+ "Content-Length: " + encodedBody.length + "\r\n"
						+ "Connection: close\r\n\r\n";
				OutputStream output = socket.getOutputStream();
				output.write(head.getBytes(StandardCharsets.US_ASCII));
				output.write(encodedBody);
				output.flush();

				InputStream input = socket.getInputStream();
				String statusLine = readAsciiLine(input);
				if (statusLine == null || !statusLine.contains(" 200 "))
					throw new AssertionError("Unexpected subscription status: "
							+ statusLine);
				Map<String, String> headers = readHeaders(input);
				String contentType = headers.getOrDefault("Content-Type", "");
				assertTrue(contentType.startsWith("text/event-stream"), headers::toString);
				InputStream responseBody = headers.getOrDefault(
						"Transfer-Encoding", "").toLowerCase(Locale.ROOT)
						.contains("chunked") ? new ChunkedInputStream(input) : input;
				return new LiveSubscription(node, socket, responseBody,
						headers.getOrDefault("Content-Language", ""));
			} catch (IOException | RuntimeException | Error exception) {
				try {
					socket.close();
				} catch (IOException ignored) {
					// Preserve the original failure.
				}
				throw exception;
			}
		}

		private FleetNode node() {
			return this.node;
		}

		private String contentLanguage() {
			return this.contentLanguage;
		}

		private String nextFrame(Duration timeout) throws IOException {
			this.socket.setSoTimeout((int) Math.max(1L, timeout.toMillis()));
			StringBuilder frame = new StringBuilder();
			for (;;) {
				String line = readUtf8Line(this.body);
				if (line == null)
					throw new IOException("The subscription transport reached EOF.");
				if (line.isEmpty()) {
					if (!frame.isEmpty())
						return frame.toString();
					continue;
				}
				if (!frame.isEmpty())
					frame.append('\n');
				frame.append(line);
			}
		}

		private void awaitTransportClosed(Duration timeout) throws IOException {
			this.socket.setSoTimeout((int) timeout.toMillis());
			try {
				while (this.body.read() >= 0) {
					// Drain any terminal frame before the listener closes the socket.
				}
			} catch (SocketTimeoutException exception) {
				throw exception;
			} catch (IOException exception) {
				// EOF, a reset, or a truncated final chunk all prove closure.
			}
		}

		@Override
		public void close() {
			try {
				this.socket.setSoLinger(true, 0);
			} catch (SocketException ignored) {
				// The peer may already have closed the transport.
			}
			try {
				this.socket.close();
			} catch (IOException ignored) {
				// Best-effort client cleanup; server diagnostics prove release.
			}
		}
	}

	private static final class ChunkedInputStream extends InputStream {
		private final InputStream input;
		private int remaining;
		private boolean finished;

		private ChunkedInputStream(InputStream input) {
			this.input = input;
		}

		@Override
		public int read() throws IOException {
			if (this.finished)
				return -1;
			if (this.remaining == 0 && !readChunkHead())
				return -1;
			int value = this.input.read();
			if (value < 0)
				throw new IOException("EOF inside an HTTP chunk.");
			if (--this.remaining == 0)
				consumeCrLf();
			return value;
		}

		private boolean readChunkHead() throws IOException {
			String line = readAsciiLine(this.input);
			if (line == null)
				return false;
			int extension = line.indexOf(';');
			String size = (extension < 0 ? line : line.substring(0, extension)).trim();
			try {
				this.remaining = Integer.parseInt(size, 16);
			} catch (NumberFormatException exception) {
				throw new IOException("Invalid HTTP chunk size: " + line, exception);
			}
			if (this.remaining != 0)
				return true;
			while ((line = readAsciiLine(this.input)) != null && !line.isEmpty()) {
				// Consume trailers.
			}
			this.finished = true;
			return false;
		}

		private void consumeCrLf() throws IOException {
			if (this.input.read() != '\r' || this.input.read() != '\n')
				throw new IOException("Invalid HTTP chunk terminator.");
		}
	}

	private static Map<String, String> readHeaders(InputStream input)
			throws IOException {
		Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		String line;
		while ((line = readAsciiLine(input)) != null && !line.isEmpty()) {
			int separator = line.indexOf(':');
			if (separator <= 0)
				throw new IOException("Invalid HTTP response header: " + line);
			headers.merge(line.substring(0, separator).trim(),
					line.substring(separator + 1).trim(), (first, second) ->
							first + ", " + second);
		}
		return headers;
	}

	private static String readAsciiLine(InputStream input) throws IOException {
		return readLine(input, StandardCharsets.US_ASCII);
	}

	private static String readUtf8Line(InputStream input) throws IOException {
		return readLine(input, StandardCharsets.UTF_8);
	}

	private static String readLine(InputStream input,
			java.nio.charset.Charset charset) throws IOException {
		ArrayList<Byte> bytes = new ArrayList<>();
		for (;;) {
			int value = input.read();
			if (value < 0)
				return bytes.isEmpty() ? null : decode(bytes, charset);
			if (value == '\n') {
				if (!bytes.isEmpty() && bytes.get(bytes.size() - 1) == (byte) '\r')
					bytes.remove(bytes.size() - 1);
				return decode(bytes, charset);
			}
			bytes.add((byte) value);
		}
	}

	private static String decode(List<Byte> bytes,
			java.nio.charset.Charset charset) {
		byte[] array = new byte[bytes.size()];
		for (int index = 0; index < bytes.size(); ++index)
			array[index] = bytes.get(index);
		return new String(array, charset);
	}

	private static String requestBody(String id, String method,
			String additionalParameters, String additionalMeta) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}"
				+ additionalMeta + '}' + additionalParameters + "}}";
	}

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(WAIT)
			.version(HttpClient.Version.HTTP_1_1)
			.build();
}
