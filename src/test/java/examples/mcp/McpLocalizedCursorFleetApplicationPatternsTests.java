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

package examples.mcp;

import com.soklet.CorsAuthorizer;
import com.soklet.HttpMethod;
import com.soklet.LifecycleObserver;
import com.soklet.LifecyclePolicy;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpCompleteResult;
import com.soklet.McpInvocationFeatures;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpLocalizer;
import com.soklet.McpLocalizationContext;
import com.soklet.McpLocalizationRequest;
import com.soklet.McpLocalizationResult;
import com.soklet.McpLocalizationRevision;
import com.soklet.McpProtectionMode;
import com.soklet.McpResourceDescriptor;
import com.soklet.McpResourceListContext;
import com.soklet.McpResourcePage;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpServerDiagnostics;
import com.soklet.McpServerStatus;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyMode;
import com.soklet.McpSimulationCompletion;
import com.soklet.McpSimulationResponse;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpTextResourceContents;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.Request;
import com.soklet.ResourceMethodResolver;
import com.soklet.Simulator;
import com.soklet.SimulatorConfig;
import com.soklet.SokletSimulator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public-API-only application pattern for portable, localized dynamic-list
 * cursors in a fleet.
 *
 * <p>The two nodes below have independent server objects, application cursor
 * codecs, key-ring copies, localization snapshots, and retained catalog
 * repositories. The cursor is the only value transferred between them. The
 * separately populated repositories model application replication; they are
 * not a claim that Soklet supplies a distributed store, key-management
 * system, or routing affinity.
 *
 * <p>The fixed application HMAC keys and clock are deterministic test
 * material. They are deliberately separate from Soklet's purpose-specific
 * framework request-state protection configuration.
 */
@Timeout(60)
public class McpLocalizedCursorFleetApplicationPatternsTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/examples/localized-cursor-fleet";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String CURSOR_ERROR_MESSAGE =
			"The resource-list cursor is invalid.";
	private static final String PRINCIPAL = "tenant-7:user-42";
	private static final Instant NOW =
			Instant.parse("2026-08-21T12:00:00Z");
	private static final Duration CURSOR_LIFETIME = Duration.ofMinutes(5);
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final Pattern NEXT_CURSOR = Pattern.compile(
			"\\\"nextCursor\\\":\\\"([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\\"");
	private static final Pattern RESOURCE_URI = Pattern.compile(
			"\\\"uri\\\":\\\"app-resource://catalog/([a-z]+)\\\"");

	@Test
	@Timeout(120)
	void localizedCursorCrossesNodesWithStableSnapshotLocaleRevisionAndPageBounds() {
		CatalogSnapshot originalA = catalog("snapshot-7", "catalog-r7",
				"alpha", "bravo", "charlie", "delta", "echo");
		CatalogSnapshot originalB = catalog("snapshot-7", "catalog-r7",
				"alpha", "bravo", "charlie", "delta", "echo");
		CatalogSnapshot replacementB = catalog("snapshot-8", "catalog-r8",
				"foxtrot", "golf");
		TranslationSnapshot translationsA = translations("translations-r7");
		TranslationSnapshot translationsB = translations("translations-r7");
		ApplicationCursorKeyRing keyRingA = cursorKeyRing(1);
		ApplicationCursorKeyRing keyRingB = cursorKeyRing(1);
		ApplicationNode nodeA = new ApplicationNode("node-a",
				new ReplicatedCatalogRepository(originalA, List.of(originalA)),
				translationsA, keyRingA);
		ApplicationNode nodeB = new ApplicationNode("node-b",
				new ReplicatedCatalogRepository(replacementB,
						List.of(originalB, replacementB)),
				translationsB, keyRingB);

		assertNotSame(nodeA.repository(), nodeB.repository());
		assertNotSame(originalA, originalB);
		assertEquals(originalA, originalB);
		assertNotSame(originalA.records(), originalB.records());
		assertNotSame(originalA.records().get(0), originalB.records().get(0));
		assertNotSame(translationsA, translationsB);
		assertEquals(translationsA, translationsB);
		assertNotSame(keyRingA, keyRingB);
		assertTrue(keyRingA.hasSameConfiguration(keyRingB));
		assertNotSame(nodeA.codec(), nodeB.codec());

		SokletSimulator.run(nodeA.configFactory(), simulatorA ->
				SokletSimulator.run(nodeB.configFactory(), simulatorB -> {
					assertNotSame(nodeA.server(), nodeB.server());
					assertFrameworkProtectionDisabled(nodeA);
					assertFrameworkProtectionDisabled(nodeB);
					Capture first = capture(simulatorA, resourceListRequest(
							"page-a-1", Optional.empty(), PRINCIPAL, "fr-CA"));
					assertEquals(List.of(), nodeA.applicationFailures(),
							nodeA.applicationFailures().toString());
					assertEquals(List.of(), nodeA.observedThrowables(),
							nodeA.observedThrowables().toString());
					assertEquals(1, nodeA.providerObservations().size());
					assertEquals(1, nodeA.handlerInvocations());
					assertEquals(1, nodeA.successfulPages());
					assertEquals(1, nodeA.issuedCursors().size());
					assertSuccessfulPage(first, "fr-CA", List.of("alpha", "bravo"));
					String cursorA = nextCursor(first.body()).orElseThrow();
					assertEquals(List.of(cursorA), nodeA.issuedCursors());
					String cursorPayload = new String(
							nodeA.codec().payloadBytes(cursorA),
							StandardCharsets.ISO_8859_1);
					assertFalse(cursorPayload.contains(PRINCIPAL),
							"The principal belongs in HMAC AAD, not cursor payload.");
					assertFalse(cursorPayload.contains("auth:" + PRINCIPAL),
							"The authorization partition belongs in HMAC AAD, not payload.");

					Capture sameNodeReplay = capture(simulatorA, resourceListRequest(
							"page-a-2-replay", Optional.of(cursorA), PRINCIPAL, "fr"));
					assertSuccessfulPage(sameNodeReplay, "fr-CA",
							List.of("charlie", "delta"));
					String sameNodeNextCursor = nextCursor(sameNodeReplay.body())
							.orElseThrow();

					Capture second = capture(simulatorB, resourceListRequest(
							"page-b-2", Optional.of(cursorA), PRINCIPAL, "en"));
					assertSuccessfulPage(second, "fr-CA",
							List.of("charlie", "delta"));
					String cursorB = nextCursor(second.body()).orElseThrow();
					assertEquals(List.of(cursorB), nodeB.issuedCursors());
					assertEquals(resourceIds(sameNodeReplay.body()),
							resourceIds(second.body()));
					assertEquals(sameNodeNextCursor, cursorB,
							"Equal claims must replay to one stable cursor across nodes.");
					assertEquals(List.of(cursorA, cursorB), nodeA.issuedCursors());

					Capture third = capture(simulatorA, resourceListRequest(
							"page-a-3", Optional.of(cursorB), PRINCIPAL, "de"));
					assertSuccessfulPage(third, "fr-CA", List.of("echo"));
					assertTrue(nextCursor(third.body()).isEmpty(), third.body());

					Capture fresh = capture(simulatorB, resourceListRequest(
							"page-b-fresh", Optional.empty(), PRINCIPAL, "en"));
					assertSuccessfulPage(fresh, "en", List.of("foxtrot", "golf"));
					assertTrue(nextCursor(fresh.body()).isEmpty(), fresh.body());

					List<String> continued = new ArrayList<>();
					continued.addAll(resourceIds(first.body()));
					continued.addAll(resourceIds(second.body()));
					continued.addAll(resourceIds(third.body()));
					assertEquals(List.of("alpha", "bravo", "charlie", "delta", "echo"),
							continued);
					assertEquals(continued.size(), new LinkedHashSet<>(continued).size(),
							"Cross-page resource identities must remain unique.");

					assertObservation(nodeA, "page-a-1", Optional.empty(), false, false,
							Locale.CANADA_FRENCH, "translations-r7");
					assertObservation(nodeA, "page-a-2-replay", Optional.of(cursorA),
							true, true, Locale.CANADA_FRENCH, "translations-r7");
					assertObservation(nodeB, "page-b-2", Optional.of(cursorA), true, true,
							Locale.CANADA_FRENCH, "translations-r7");
					assertObservation(nodeA, "page-a-3", Optional.of(cursorB), true, true,
							Locale.CANADA_FRENCH, "translations-r7");
					assertObservation(nodeB, "page-b-fresh", Optional.empty(), false, false,
							Locale.ENGLISH, "translations-r7");
				}));

		assertStopped(nodeA);
		assertStopped(nodeB);
	}

	@Test
	@Timeout(240)
	void cursorFailuresPreserveOpaqueBytesAndCollapseToOneNeutralError() {
		CatalogSnapshot original = catalog("snapshot-7", "catalog-r7",
				"alpha", "bravo", "charlie");
		ApplicationNode issuer = new ApplicationNode("issuer",
				new ReplicatedCatalogRepository(original, List.of(original)),
				translations("translations-r7"), cursorKeyRing(1));
		Capture issued = run(issuer, resourceListRequest("issue-cursor",
				Optional.empty(), PRINCIPAL, "fr-CA"));
		assertEquals(List.of(), issuer.applicationFailures(),
				issuer.applicationFailures().toString());
		assertEquals(List.of(), issuer.observedThrowables(),
				issuer.observedThrowables().toString());
		String validCursor = nextCursor(issued.body()).orElseThrow();
		assertEquals(List.of(validCursor), issuer.issuedCursors());
		CursorClaims validClaims = issuer.codec().verify(validCursor,
				new IdentityBinding(PRINCIPAL, "auth:" + PRINCIPAL), NOW);
		assertEquals("snapshot-7", validClaims.snapshot());
		assertEquals("catalog-r7", validClaims.catalogRevision());
		assertEquals("fr-CA", validClaims.locale());
		assertEquals("translations-r7", validClaims.localizationRevision());
		assertEquals(NOW.plus(CURSOR_LIFETIME).getEpochSecond(),
				validClaims.expiresAtEpochSecond());
		assertEquals(2, validClaims.offset());
		assertStopped(issuer);

		List<FailureCase> cases = List.of(
				new FailureCase("present-empty",
						compatibleNode("empty", original), "", PRINCIPAL,
						false, false),
				new FailureCase("tampered",
						compatibleNode("tampered", original), tamper(validCursor),
						PRINCIPAL, false, false),
				new FailureCase("wrong-principal",
						compatibleNode("principal", original), validCursor,
						"tenant-7:user-99", false, false),
				new FailureCase("wrong-application-key",
						new ApplicationNode("wrong-key",
								new ReplicatedCatalogRepository(copy(original),
										List.of(copy(original))),
								translations("translations-r7"), cursorKeyRing(99)),
						validCursor, PRINCIPAL, false, false),
				new FailureCase("localization-revision-drift",
						new ApplicationNode("revision-drift",
								new ReplicatedCatalogRepository(copy(original),
										List.of(copy(original))),
								translations("translations-r8"), cursorKeyRing(1)),
						validCursor, PRINCIPAL, true, true),
				new FailureCase("catalog-revision-drift",
						new ApplicationNode("catalog-drift",
								new ReplicatedCatalogRepository(
										catalog("snapshot-7", "catalog-r8", "alpha",
												"bravo", "charlie"),
										List.of(catalog("snapshot-7", "catalog-r8",
												"alpha", "bravo", "charlie"))),
								translations("translations-r7"), cursorKeyRing(1)),
						validCursor, PRINCIPAL, true, true),
				new FailureCase("locale-pin-unavailable",
						new ApplicationNode("locale-mismatch",
								new ReplicatedCatalogRepository(copy(original),
										List.of(copy(original))),
								translations("translations-r7",
										List.of(Locale.FRENCH, Locale.ENGLISH)),
								cursorKeyRing(1)),
						validCursor, PRINCIPAL, true, false),
				new FailureCase("exact-expiry",
						new ApplicationNode("expired",
								new ReplicatedCatalogRepository(copy(original),
										List.of(copy(original))),
								translations("translations-r7"), cursorKeyRing(1),
								NOW.plus(CURSOR_LIFETIME)),
						validCursor, PRINCIPAL, false, false),
				new FailureCase("missing-retained-snapshot",
						new ApplicationNode("missing-snapshot",
								new ReplicatedCatalogRepository(
										catalog("snapshot-8", "catalog-r8", "foxtrot"),
										List.of(catalog("snapshot-8", "catalog-r8",
												"foxtrot"))),
								translations("translations-r7"), cursorKeyRing(1)),
						validCursor, PRINCIPAL, true, true));

		List<String> failureBodies = new ArrayList<>();
		for (FailureCase failureCase : cases) {
			ApplicationNode node = failureCase.node();
			assertNotSame(issuer.repository(), node.repository());
			assertNotSame(issuer.codec(), node.codec());
			assertNotSame(issuer.keyRing(), node.keyRing());

			Capture failure = run(node, resourceListRequest("invalid-cursor",
					Optional.of(failureCase.cursor()), failureCase.principal(),
					"fr-CA"));
			assertNeutralCursorFailure(failure);
			failureBodies.add(failure.body());
			assertEquals(1, node.providerObservations().size(), failureCase.name());
			assertEquals(1, node.handlerObservations().size(), failureCase.name());
			ProviderObservation provider = node.providerObservations().get(0);
			HandlerObservation handler = node.handlerObservations().get(0);
			assertEquals(Optional.of(failureCase.cursor()), provider.cursor(),
					failureCase.name());
			assertEquals(Optional.of(failureCase.cursor()), handler.cursor(),
					failureCase.name());
			assertEquals(failureCase.providerAuthenticated(),
					provider.authenticatedCursor(), failureCase.name());
			assertEquals(failureCase.providerHonoredPin(),
					provider.honoredPin(), failureCase.name());
			if (failureCase.name().equals("locale-pin-unavailable")) {
				assertEquals(Locale.FRENCH, provider.locale());
				assertEquals(Locale.FRENCH, handler.locale());
			}
			if (failureCase.name().equals("exact-expiry"))
				assertEquals(NOW.plus(CURSOR_LIFETIME), node.now());
			assertEquals(1, node.handlerInvocations(), failureCase.name());
			assertEquals(0, node.successfulPages(), failureCase.name());
			assertEquals(1, node.applicationFailures().size(), failureCase.name());
			McpJsonRpcException applicationFailure = assertInstanceOf(
					McpJsonRpcException.class, node.applicationFailures().get(0),
					failureCase.name());
			assertEquals(-32602, applicationFailure.getError().getCode(),
					failureCase.name());
			assertEquals(CURSOR_ERROR_MESSAGE,
					applicationFailure.getError().getMessage(), failureCase.name());
			assertTrue(applicationFailure.getError().getData().isEmpty(),
					failureCase.name());
			assertNull(applicationFailure.getCause(), failureCase.name());
			assertEquals(List.of(), node.observedThrowables(), failureCase.name());
			assertStopped(node);
		}

		assertEquals(1, new LinkedHashSet<>(failureBodies).size(),
				"Every application cursor invalidity must have one wire error.");
	}

	private static ApplicationNode compatibleNode(String name,
			CatalogSnapshot snapshot) {
		CatalogSnapshot active = copy(snapshot);
		CatalogSnapshot retained = copy(snapshot);
		return new ApplicationNode(name,
				new ReplicatedCatalogRepository(active, List.of(retained)),
				translations("translations-r7"), cursorKeyRing(1));
	}

	private static Capture run(ApplicationNode node, Request request) {
		List<Capture> captures = new ArrayList<>(1);
		SokletSimulator.run(node.configFactory(), simulator -> {
			assertFrameworkProtectionDisabled(node);
			captures.add(capture(simulator, request));
		});
		return captures.get(0);
	}

	private static Capture capture(Simulator simulator, Request request) {
		McpSimulation simulation = simulator.startMcpRequest(request);
		try {
			McpSimulationResponse response = simulation.awaitResponse(WAIT)
					.orElseThrow(() -> new AssertionError(
							"Timed out awaiting simulator response."));
			McpSimulationCompletion completion = simulation.awaitCompletion(WAIT)
					.orElseThrow(() -> new AssertionError(
							"Timed out awaiting simulator completion."));
			assertEquals(McpSimulationBodyMode.JSON, response.getBodyMode());
			assertEquals(McpStreamTerminationReason.COMPLETED,
					completion.getReason());
			return new Capture(response.getStatusCode(), response.getHeaders(),
					new String(response.getBody().orElseThrow(),
							StandardCharsets.UTF_8));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static Request resourceListRequest(String requestId,
			Optional<String> cursor, String principal, String acceptLanguage) {
		String cursorField = cursor.map(value -> ",\"cursor\":\"" + value + '"')
				.orElse("");
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"resources/list\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ cursorField + "}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type", Set.of(
								"application/json; charset=UTF-8"),
						"Accept", Set.of(
								"application/json, text/event-stream"),
						"Accept-Language", Set.of(acceptLanguage),
						"Authorization", Set.of("Bearer " + principal),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of("resources/list")))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static void assertSuccessfulPage(Capture capture,
			String contentLanguage, List<String> resourceIds) {
		assertEquals(200, capture.statusCode(), capture.body());
		assertTrue(headerValues(capture.headers(), "Content-Language").isEmpty(),
				"Soklet must not invent Content-Language for an application-owned page: "
						+ capture.headers());
		assertEquals(resourceIds, resourceIds(capture.body()), capture.body());
		assertTrue(capture.body().contains("\"ttlMs\":0"), capture.body());
		assertTrue(capture.body().contains("\"cacheScope\":\"private\""),
				capture.body());
		for (String resourceId : resourceIds)
			assertTrue(capture.body().contains("fr-CA".equals(contentLanguage)
					? "\"name\":\"fr-CA translations-r7 " + resourceId + "\""
					: "\"name\":\"en translations-r7 " + resourceId + "\""),
					capture.body());
	}

	private static Set<String> headerValues(Map<String, Set<String>> headers,
			String name) {
		return headers.entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(name))
				.map(Map.Entry::getValue)
				.findFirst().orElse(Set.of());
	}

	private static void assertNeutralCursorFailure(Capture capture) {
		assertEquals(400, capture.statusCode(), capture.body());
		assertTrue(capture.body().contains("\"id\":\"invalid-cursor\""),
				capture.body());
		assertTrue(capture.body().contains("\"code\":-32602"), capture.body());
		assertTrue(capture.body().contains("\"message\":\""
				+ CURSOR_ERROR_MESSAGE + "\""), capture.body());
		assertFalse(capture.body().contains("\"data\":"), capture.body());
		for (String classifiedDetail : List.of("present-empty", "tampered",
				"wrong-principal", "wrong-application-key",
				"localization-revision", "catalog-revision-drift",
				"locale-pin-unavailable",
				"exact-expiry", "missing-retained-snapshot", PRINCIPAL))
			assertFalse(capture.body().contains(classifiedDetail), capture.body());
	}

	private static void assertObservation(ApplicationNode node, String requestId,
			Optional<String> cursor, boolean authenticatedCursor,
			boolean honoredPin, Locale locale,
			String revision) {
		ProviderObservation provider = node.providerObservation(requestId);
		HandlerObservation handler = node.handlerObservation(requestId);
		assertEquals(node.name(), provider.node());
		assertEquals(node.name(), handler.node());
		assertEquals(cursor, provider.cursor());
		assertEquals(cursor, handler.cursor());
		assertEquals(authenticatedCursor, provider.authenticatedCursor());
		assertEquals(honoredPin, provider.honoredPin());
		assertEquals(locale, provider.locale());
		assertEquals(locale, handler.locale());
		assertEquals(revision, provider.localizationRevision());
		assertEquals(revision, handler.localizationRevision());
	}

	private static void assertFrameworkProtectionDisabled(ApplicationNode node) {
		assertEquals(McpProtectionMode.NO_FRAMEWORK_KEYS,
				node.server().getProtectionControl().getProtectionMode());
		assertTrue(node.server().getProtectionControl().getKeyRingSnapshot().isEmpty());
		assertTrue(node.server().getDiagnostics()
				.getProtectionKeyRingFingerprint().isEmpty());
	}

	private static void assertStopped(ApplicationNode node) {
		McpServerDiagnostics diagnostics = node.server().getDiagnostics();
		assertEquals(McpServerStatus.TERMINATED, diagnostics.getStatus());
		assertTrue(diagnostics.getBoundAddress().isEmpty());
		assertEquals(0, diagnostics.getActiveHandlerExecutions());
		assertEquals(0, diagnostics.getQueuedRequests());
		assertEquals(0, diagnostics.getActiveRequestStreams());
		assertEquals(0, diagnostics.getActiveSubscriptions());
		assertFrameworkProtectionDisabled(node);
	}

	private static Optional<String> nextCursor(String body) {
		Matcher matcher = NEXT_CURSOR.matcher(body);
		if (!matcher.find())
			return Optional.empty();
		String cursor = matcher.group(1);
		assertFalse(matcher.find(), body);
		return Optional.of(cursor);
	}

	private static List<String> resourceIds(String body) {
		List<String> resourceIds = new ArrayList<>();
		Matcher matcher = RESOURCE_URI.matcher(body);
		while (matcher.find())
			resourceIds.add(matcher.group(1));
		return List.copyOf(resourceIds);
	}

	private static String tamper(String cursor) {
		int signature = cursor.lastIndexOf('.') + 1;
		char original = cursor.charAt(signature);
		return cursor.substring(0, signature)
				+ (original == 'A' ? 'B' : 'A')
				+ cursor.substring(signature + 1);
	}

	private static CatalogSnapshot catalog(String id, String revision,
			String... resourceIds) {
		List<ResourceRecord> records = Arrays.stream(resourceIds)
				.map(resourceId -> new ResourceRecord(resourceId,
						URI.create("app-resource://catalog/" + resourceId)))
				.toList();
		return new CatalogSnapshot(id, revision, records);
	}

	private static CatalogSnapshot copy(CatalogSnapshot snapshot) {
		return new CatalogSnapshot(snapshot.id(), snapshot.revision(),
				snapshot.records().stream()
						.map(record -> new ResourceRecord(record.id(), record.uri()))
						.toList());
	}

	private static TranslationSnapshot translations(String revision) {
		return translations(revision, List.of(Locale.CANADA_FRENCH,
				Locale.FRENCH, Locale.ENGLISH));
	}

	private static TranslationSnapshot translations(String revision,
			List<Locale> locales) {
		List<String> resourceIds = List.of("alpha", "bravo", "charlie",
				"delta", "echo", "foxtrot", "golf");
		LinkedHashMap<Locale, Map<String, String>> localizedNames =
				new LinkedHashMap<>();
		for (Locale locale : locales) {
			LinkedHashMap<String, String> names = new LinkedHashMap<>();
			for (String resourceId : resourceIds)
				names.put(resourceId, locale.toLanguageTag() + ' ' + revision
						+ ' ' + resourceId);
			localizedNames.put(locale, names);
		}
		return new TranslationSnapshot(
				McpLocalizationRevision.fromValue(revision), localizedNames);
	}

	private static ApplicationCursorKeyRing cursorKeyRing(int seed) {
		byte[] active = new byte[32];
		byte[] verification = new byte[32];
		for (int index = 0; index < active.length; ++index) {
			active[index] = (byte) (seed + index);
			verification[index] = (byte) (seed + 64 + index);
		}
		return new ApplicationCursorKeyRing("cursor-k2", Map.of(
				"cursor-k1", verification,
				"cursor-k2", active));
	}

	private record Capture(int statusCode, Map<String, Set<String>> headers,
			String body) {
		private Capture {
			headers = Map.copyOf(headers);
		}
	}

	private record FailureCase(String name, ApplicationNode node, String cursor,
			String principal, boolean providerAuthenticated,
			boolean providerHonoredPin) {}

	private record ProviderObservation(String node, String requestId,
			Optional<String> cursor, boolean authenticatedCursor,
			boolean honoredPin, Locale locale,
			String localizationRevision) {}

	private record HandlerObservation(String node, String requestId,
			Optional<String> cursor, Locale locale,
			String localizationRevision) {}

	private static final class ApplicationNode {
		private static final int PAGE_SIZE = 2;
		private final String name;
		private final ReplicatedCatalogRepository repository;
		private final TranslationSnapshot translations;
		private final ApplicationCursorKeyRing keyRing;
		private final SignedLocalizedCursorCodec codec;
		private final Instant now;
		private final CopyOnWriteArrayList<ProviderObservation>
				providerObservations = new CopyOnWriteArrayList<>();
		private final CopyOnWriteArrayList<HandlerObservation>
				handlerObservations = new CopyOnWriteArrayList<>();
		private final CopyOnWriteArrayList<String> issuedCursors =
				new CopyOnWriteArrayList<>();
		private final CopyOnWriteArrayList<Throwable> observedThrowables =
				new CopyOnWriteArrayList<>();
		private final CopyOnWriteArrayList<Throwable> applicationFailures =
				new CopyOnWriteArrayList<>();
		private final AtomicInteger handlerInvocations = new AtomicInteger();
		private final AtomicInteger successfulPages = new AtomicInteger();
		private final AtomicReference<McpServer> server = new AtomicReference<>();

		private ApplicationNode(String name,
				ReplicatedCatalogRepository repository,
				TranslationSnapshot translations,
				ApplicationCursorKeyRing keyRing) {
			this(name, repository, translations, keyRing, NOW);
		}

		private ApplicationNode(String name,
				ReplicatedCatalogRepository repository,
				TranslationSnapshot translations,
				ApplicationCursorKeyRing keyRing, Instant now) {
			this.name = requireClaimText(name);
			this.repository = requireNonNull(repository);
			this.translations = requireNonNull(translations);
			this.keyRing = requireNonNull(keyRing);
			this.codec = new SignedLocalizedCursorCodec(keyRing);
			this.now = requireNonNull(now);
		}

		private Function<SimulatorConfig.Builder, SimulatorConfig> configFactory() {
			return config -> {
				McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
						.serverInformation(McpImplementation.withNameAndVersion(
								"localized-cursor-fixture", "1.0").build())
						.resource(McpResourceRegistration.withUriTemplateAndName(
								"app-resource://catalog/{id}", "catalog-resource")
								.handler((request, resource, features) ->
										McpCompleteResult.fromResourceOutput(
												McpResourceOutput.builder()
														.content(McpTextResourceContents
																.withUriAndText(
																		resource.getUri(), "unused")
																.build())
														.build()))
								.build())
						.resourceListHandler(this::page)
						.build();
				McpLocalizer localizer = McpLocalizer
						.withFallbackLocale(Locale.ENGLISH)
						.contextProvider(this::localizationContext)
						.build();
				return config.mcpServer(0, mcpServerBuilder -> {
					McpServer server = mcpServerBuilder
							.host(LOOPBACK)
							.endpointRegistry(McpEndpointRegistry.fromEndpoints(
									List.of(endpoint)))
							.admissionController(context -> {
								String authorization = context.getRequest()
										.getHeader("Authorization").orElseThrow();
								if (!authorization.startsWith("Bearer "))
									throw new IllegalArgumentException(
											"A bearer principal is required.");
								String principal = requireClaimText(
										authorization.substring("Bearer ".length()));
								return McpAdmissionDecision.accepted(McpAdmissionIdentity
										.withRateLimitPartitionKey("rate:" + principal)
										.authorizationPartitionKey("auth:" + principal)
										.principal(principal)
										.build());
							})
							.requestRateLimiter(context ->
									McpRateLimitDecision.allowed())
							.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
							.allowedHosts(Set.of(LOOPBACK))
							.localizer(localizer)
							.build();
					if (!this.server.compareAndSet(null, server))
						throw new IllegalStateException(
								"An application node may create only one simulator scope.");
					return server;
				})
						.resourceMethodResolver(
								ResourceMethodResolver.fromMethods(Set.of()))
						.lifecyclePolicy(LifecyclePolicy.builder()
								.startupTimeout(Duration.ofSeconds(5))
								.startupCancelationTimeout(Duration.ofSeconds(2))
								.gracefulShutdownDuration(Duration.ofSeconds(2))
								.forcedShutdownDuration(Duration.ofSeconds(1))
								.build())
						.lifecycleObservers(List.of(new LifecycleObserver() {
							@Override
							public void didFinishMcpRequestHandling(
									@NonNull McpRequestContext context,
									@NonNull McpRequestOutcome requestOutcome,
									@Nullable McpJsonRpcError error,
									@NonNull Duration duration,
									@NonNull List<@NonNull Throwable> throwables) {
								observedThrowables.addAll(throwables);
							}
						}))
						.build();
			};
		}

		private McpLocalizationContext localizationContext(
				McpLocalizationRequest request) {
			try {
				return createLocalizationContext(request);
			} catch (RuntimeException | Error failure) {
				this.applicationFailures.add(failure);
				throw failure;
			}
		}

		private McpLocalizationContext createLocalizationContext(
				McpLocalizationRequest request) {
			IdentityBinding binding = identity(request.getRequestContext());
			Optional<String> cursor = request.getResourceListCursor();
			Optional<CursorClaims> recognized = cursor.flatMap(value ->
					this.codec.recognize(value, binding, this.now));
			Optional<Locale> pinnedLocale = recognized.flatMap(claims ->
					this.translations.locale(claims.locale()));
			Locale locale = pinnedLocale.orElseGet(() -> {
				Locale selected = Locale.lookup(request.getLanguageRanges(),
						this.translations.locales());
				return selected == null ? Locale.ENGLISH : selected;
			});
			String requestId = requestId(request.getRequestContext());
			this.providerObservations.add(new ProviderObservation(this.name, requestId,
					cursor, recognized.isPresent(), pinnedLocale.isPresent(), locale,
					this.translations.revision().getValue()));
			return McpLocalizationContext.withLocale(locale)
					.revision(this.translations.revision())
					.localizer(text -> McpLocalizationResult.useDefaultText())
					.build();
		}

		private McpResourcePage page(McpRequestContext request,
				McpResourceListContext list, McpInvocationFeatures features) {
			try {
				return createPage(request, list, features);
			} catch (RuntimeException | Error failure) {
				this.applicationFailures.add(failure);
				throw failure;
			}
		}

		private McpResourcePage createPage(McpRequestContext request,
				McpResourceListContext list, McpInvocationFeatures features) {
			this.handlerInvocations.incrementAndGet();
			IdentityBinding binding = identity(request);
			McpLocalizationContext localization =
					features.require(McpLocalizationContext.class);
			String localizationRevision = localization.getRevision()
					.orElseThrow(McpLocalizedCursorFleetApplicationPatternsTests
							::invalidCursor)
					.getValue();
			Optional<String> cursor = list.getCursor();
			this.handlerObservations.add(new HandlerObservation(this.name,
					requestId(request), cursor, localization.getLocale(),
					localizationRevision));

			CatalogSnapshot snapshot;
			CursorClaims claims;
			if (cursor.isEmpty()) {
				snapshot = this.repository.active();
				claims = new CursorClaims(snapshot.id(), snapshot.revision(),
						localization.getLocale().toLanguageTag(), localizationRevision,
						this.now.plus(CURSOR_LIFETIME).getEpochSecond(), 0);
			} else {
				claims = this.codec.verify(cursor.orElseThrow(), binding, this.now);
				if (!claims.locale().equals(
						localization.getLocale().toLanguageTag())
						|| !claims.localizationRevision().equals(
						localizationRevision))
					throw invalidCursor();
				snapshot = this.repository.find(claims.snapshot())
						.orElseThrow(
								McpLocalizedCursorFleetApplicationPatternsTests
										::invalidCursor);
				if (!snapshot.revision().equals(claims.catalogRevision()))
					throw invalidCursor();
			}
			if (claims.offset() > snapshot.records().size())
				throw invalidCursor();

			int end;
			try {
				end = Math.min(snapshot.records().size(),
						Math.addExact(claims.offset(), PAGE_SIZE));
			} catch (ArithmeticException exception) {
				throw invalidCursor();
			}
			McpResourcePage.Builder page = McpResourcePage.builder();
			for (ResourceRecord record
					: snapshot.records().subList(claims.offset(), end))
				page.resource(McpResourceDescriptor.withUriAndName(record.uri(),
						this.translations.localizedName(
								localization.getLocale(), record.id())).build());
			if (end < snapshot.records().size()) {
				String nextCursor = this.codec.issue(claims.withOffset(end), binding);
				this.issuedCursors.add(nextCursor);
				page.nextCursor(nextCursor);
			}
			this.successfulPages.incrementAndGet();
			return page.build();
		}

		private String name() {
			return this.name;
		}

		private ReplicatedCatalogRepository repository() {
			return this.repository;
		}

		private ApplicationCursorKeyRing keyRing() {
			return this.keyRing;
		}

		private SignedLocalizedCursorCodec codec() {
			return this.codec;
		}

		private McpServer server() {
			return Optional.ofNullable(this.server.get()).orElseThrow(() ->
					new IllegalStateException(
							"The application node has not created its simulator scope."));
		}

		private Instant now() {
			return this.now;
		}

		private List<String> issuedCursors() {
			return List.copyOf(this.issuedCursors);
		}

		private List<ProviderObservation> providerObservations() {
			return List.copyOf(this.providerObservations);
		}

		private List<HandlerObservation> handlerObservations() {
			return List.copyOf(this.handlerObservations);
		}

		private ProviderObservation providerObservation(String requestId) {
			return this.providerObservations.stream()
					.filter(observation -> observation.requestId().equals(requestId))
					.findFirst().orElseThrow();
		}

		private HandlerObservation handlerObservation(String requestId) {
			return this.handlerObservations.stream()
					.filter(observation -> observation.requestId().equals(requestId))
					.findFirst().orElseThrow();
		}

		private int handlerInvocations() {
			return this.handlerInvocations.get();
		}

		private int successfulPages() {
			return this.successfulPages.get();
		}

		private List<Throwable> observedThrowables() {
			return List.copyOf(this.observedThrowables);
		}

		private List<Throwable> applicationFailures() {
			return List.copyOf(this.applicationFailures);
		}
	}

	private static final class ReplicatedCatalogRepository {
		private final Map<String, CatalogSnapshot> retained;
		private final String activeSnapshot;

		private ReplicatedCatalogRepository(CatalogSnapshot active,
				List<CatalogSnapshot> retained) {
			CatalogSnapshot requiredActive = requireNonNull(active);
			LinkedHashMap<String, CatalogSnapshot> snapshots = new LinkedHashMap<>();
			for (CatalogSnapshot snapshot : retained) {
				CatalogSnapshot previous = snapshots.put(
						requireNonNull(snapshot).id(), snapshot);
				if (previous != null)
					throw new IllegalArgumentException(
							"Snapshot IDs must be unique.");
			}
			if (!snapshots.containsKey(requiredActive.id()))
				snapshots.put(requiredActive.id(), requiredActive);
			this.retained = Map.copyOf(snapshots);
			this.activeSnapshot = requiredActive.id();
		}

		private CatalogSnapshot active() {
			return this.retained.get(this.activeSnapshot);
		}

		private Optional<CatalogSnapshot> find(String id) {
			return Optional.ofNullable(this.retained.get(requireNonNull(id)));
		}
	}

	private record CatalogSnapshot(String id, String revision,
			List<ResourceRecord> records) {
		private CatalogSnapshot {
			id = requireClaimText(id);
			revision = requireClaimText(revision);
			records = List.copyOf(requireNonNull(records));
		}
	}

	private record ResourceRecord(String id, URI uri) {
		private ResourceRecord {
			id = requireClaimText(id);
			requireNonNull(uri);
			if (!uri.isAbsolute() || !"app-resource".equals(uri.getScheme()))
				throw new IllegalArgumentException("Unsupported resource URI.");
		}
	}

	private record TranslationSnapshot(McpLocalizationRevision revision,
			List<Locale> locales, Map<Locale, Map<String, String>> localizedNames) {
		private TranslationSnapshot(McpLocalizationRevision revision,
				LinkedHashMap<Locale, Map<String, String>> localizedNames) {
			this(requireNonNull(revision), List.copyOf(localizedNames.keySet()),
					copyLocalizedNames(localizedNames));
		}

		private TranslationSnapshot {
			requireNonNull(revision);
			locales = List.copyOf(requireNonNull(locales));
			localizedNames = Map.copyOf(requireNonNull(localizedNames));
			if (!locales.contains(Locale.ENGLISH))
				throw new IllegalArgumentException(
						"The fallback locale must be present.");
		}

		private Optional<Locale> locale(String languageTag) {
			return this.locales.stream().filter(candidate ->
					candidate.toLanguageTag().equals(languageTag)).findFirst();
		}

		private String localizedName(Locale locale, String resourceId) {
			Map<String, String> names = this.localizedNames.get(requireNonNull(locale));
			if (names == null || !names.containsKey(resourceId))
				throw new IllegalStateException(
						"The immutable translation snapshot is incomplete.");
			return names.get(resourceId);
		}

		private static Map<Locale, Map<String, String>> copyLocalizedNames(
				Map<Locale, Map<String, String>> source) {
			LinkedHashMap<Locale, Map<String, String>> copied = new LinkedHashMap<>();
			source.forEach((locale, names) -> copied.put(requireNonNull(locale),
					Map.copyOf(requireNonNull(names))));
			return Map.copyOf(copied);
		}
	}

	private static final class ApplicationCursorKeyRing {
		private final String activeKeyId;
		private final Map<String, byte[]> keys;

		private ApplicationCursorKeyRing(String activeKeyId,
				Map<String, byte[]> keys) {
			this.activeKeyId = requireKeyId(activeKeyId);
			LinkedHashMap<String, byte[]> copied = new LinkedHashMap<>();
			for (Map.Entry<String, byte[]> entry : requireNonNull(keys).entrySet()) {
				String keyId = requireKeyId(entry.getKey());
				byte[] material = requireNonNull(entry.getValue()).clone();
				if (material.length < 32)
					throw new IllegalArgumentException(
							"Application cursor keys require at least 256 bits.");
				copied.put(keyId, material);
			}
			if (!copied.containsKey(this.activeKeyId))
				throw new IllegalArgumentException("The active key is absent.");
			this.keys = Map.copyOf(copied);
		}

		private String activeKeyId() {
			return this.activeKeyId;
		}

		private Optional<byte[]> key(String keyId) {
			byte[] material = this.keys.get(requireNonNull(keyId));
			return material == null ? Optional.empty()
					: Optional.of(material.clone());
		}

		private boolean hasSameConfiguration(ApplicationCursorKeyRing other) {
			if (!this.activeKeyId.equals(other.activeKeyId)
					|| !this.keys.keySet().equals(other.keys.keySet()))
				return false;
			return this.keys.entrySet().stream().allMatch(entry ->
					MessageDigest.isEqual(entry.getValue(),
							other.keys.get(entry.getKey())));
		}
	}

	private static final class SignedLocalizedCursorCodec {
		private static final int FORMAT_VERSION = 1;
		private static final int SIGNATURE_BYTES = 32;
		private static final int MAXIMUM_CURSOR_CHARACTERS = 4_096;
		private static final int MAXIMUM_PAYLOAD_BYTES = 2_048;
		private static final int MAXIMUM_TEXT_BYTES = 256;
		private static final byte[] HMAC_DOMAIN =
				"soklet-example-localized-resource-cursor-v1"
						.getBytes(StandardCharsets.US_ASCII);
		private final ApplicationCursorKeyRing keyRing;

		private SignedLocalizedCursorCodec(ApplicationCursorKeyRing keyRing) {
			this.keyRing = requireNonNull(keyRing);
		}

		private String issue(CursorClaims claims, IdentityBinding binding) {
			byte[] payload = encode(requireNonNull(claims));
			String keyId = this.keyRing.activeKeyId();
			byte[] key = this.keyRing.key(keyId).orElseThrow();
			try {
				Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
				return keyId + '.' + encoder.encodeToString(payload) + '.'
						+ encoder.encodeToString(
								hmac(key, keyId, payload, requireNonNull(binding)));
			} finally {
				Arrays.fill(key, (byte) 0);
			}
		}

		private Optional<CursorClaims> recognize(String cursor,
				IdentityBinding binding, Instant now) {
			try {
				return Optional.of(verify(cursor, binding, now));
			} catch (McpJsonRpcException ignored) {
				return Optional.empty();
			}
		}

		private CursorClaims verify(String cursor, IdentityBinding binding,
				Instant now) {
			requireNonNull(binding);
			requireNonNull(now);
			try {
				if (cursor == null || cursor.length() < 5
						|| cursor.length() > MAXIMUM_CURSOR_CHARACTERS)
					throw invalidCursor();
				int firstSeparator = cursor.indexOf('.');
				int lastSeparator = cursor.lastIndexOf('.');
				if (firstSeparator < 1 || lastSeparator <= firstSeparator + 1
						|| lastSeparator == cursor.length() - 1)
					throw invalidCursor();
				String keyId = requireKeyId(cursor.substring(0, firstSeparator));
				byte[] key = this.keyRing.key(keyId).orElseThrow(
						McpLocalizedCursorFleetApplicationPatternsTests::invalidCursor);
				try {
					Base64.Decoder decoder = Base64.getUrlDecoder();
					byte[] payload = decoder.decode(cursor.substring(
							firstSeparator + 1, lastSeparator));
					byte[] signature = decoder.decode(
							cursor.substring(lastSeparator + 1));
					if (payload.length < 1 || payload.length > MAXIMUM_PAYLOAD_BYTES
							|| signature.length != SIGNATURE_BYTES
							|| !MessageDigest.isEqual(signature,
									hmac(key, keyId, payload, binding)))
						throw invalidCursor();
					CursorClaims claims = decode(payload);
					if (claims.expiresAtEpochSecond() <= now.getEpochSecond())
						throw invalidCursor();
					return claims;
				} finally {
					Arrays.fill(key, (byte) 0);
				}
			} catch (IOException | IllegalArgumentException ignored) {
				throw invalidCursor();
			}
		}

		private byte[] payloadBytes(String cursor) {
			int first = cursor.indexOf('.');
			int last = cursor.lastIndexOf('.');
			if (first < 1 || last <= first)
				throw new IllegalArgumentException("Malformed cursor.");
			return Base64.getUrlDecoder().decode(
					cursor.substring(first + 1, last));
		}

		private byte[] encode(CursorClaims claims) {
			try {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				try (DataOutputStream output = new DataOutputStream(bytes)) {
					output.writeByte(FORMAT_VERSION);
					writeText(output, claims.snapshot());
					writeText(output, claims.catalogRevision());
					writeText(output, claims.locale());
					writeText(output, claims.localizationRevision());
					output.writeLong(claims.expiresAtEpochSecond());
					output.writeInt(claims.offset());
				}
				byte[] payload = bytes.toByteArray();
				if (payload.length > MAXIMUM_PAYLOAD_BYTES)
					throw new IllegalArgumentException("Cursor payload is too large.");
				return payload;
			} catch (IOException exception) {
				throw new IllegalStateException("Unable to encode cursor.", exception);
			}
		}

		private CursorClaims decode(byte[] payload) throws IOException {
			try (DataInputStream input = new DataInputStream(
					new ByteArrayInputStream(payload))) {
				if (input.readUnsignedByte() != FORMAT_VERSION)
					throw invalidCursor();
				CursorClaims claims = new CursorClaims(readText(input),
						readText(input), readText(input), readText(input),
						input.readLong(), input.readInt());
				if (input.read() != -1)
					throw invalidCursor();
				return claims;
			}
		}

		private static void writeText(DataOutputStream output, String value)
				throws IOException {
			byte[] encoded = claimBytes(value);
			output.writeInt(encoded.length);
			output.write(encoded);
		}

		private static String readText(DataInputStream input) throws IOException {
			int length = input.readInt();
			if (length < 1 || length > MAXIMUM_TEXT_BYTES)
				throw invalidCursor();
			byte[] encoded = input.readNBytes(length);
			if (encoded.length != length)
				throw invalidCursor();
			String decoded = new String(encoded, StandardCharsets.UTF_8);
			if (!Arrays.equals(encoded, decoded.getBytes(StandardCharsets.UTF_8)))
				throw invalidCursor();
			return requireClaimText(decoded);
		}

		private static byte[] hmac(byte[] key, String keyId, byte[] payload,
				IdentityBinding binding) {
			try {
				Mac mac = Mac.getInstance("HmacSHA256");
				mac.init(new SecretKeySpec(key, "HmacSHA256"));
				updateField(mac, HMAC_DOMAIN);
				updateField(mac, claimBytes(keyId));
				updateField(mac, claimBytes(binding.principal()));
				updateField(mac, claimBytes(binding.authorizationPartition()));
				updateField(mac, payload);
				return mac.doFinal();
			} catch (GeneralSecurityException exception) {
				throw new IllegalStateException("HmacSHA256 is unavailable.", exception);
			}
		}

		private static void updateField(Mac mac, byte[] value) {
			mac.update((byte) (value.length >>> 24));
			mac.update((byte) (value.length >>> 16));
			mac.update((byte) (value.length >>> 8));
			mac.update((byte) value.length);
			mac.update(value);
		}

		private static byte[] claimBytes(String value) {
			byte[] encoded = requireClaimText(value)
					.getBytes(StandardCharsets.UTF_8);
			if (encoded.length > MAXIMUM_TEXT_BYTES)
				throw new IllegalArgumentException("Cursor claim is too large.");
			return encoded;
		}
	}

	private record CursorClaims(String snapshot, String catalogRevision,
			String locale, String localizationRevision,
			long expiresAtEpochSecond, int offset) {
		private CursorClaims {
			snapshot = requireClaimText(snapshot);
			catalogRevision = requireClaimText(catalogRevision);
			locale = requireClaimText(locale);
			localizationRevision = requireClaimText(localizationRevision);
			if (offset < 0)
				throw new IllegalArgumentException(
						"Cursor offset must not be negative.");
		}

		private CursorClaims withOffset(int nextOffset) {
			return new CursorClaims(this.snapshot, this.catalogRevision, this.locale,
					this.localizationRevision, this.expiresAtEpochSecond, nextOffset);
		}
	}

	private record IdentityBinding(String principal,
			String authorizationPartition) {
		private IdentityBinding {
			principal = requireClaimText(principal);
			authorizationPartition = requireClaimText(authorizationPartition);
		}
	}

	private static IdentityBinding identity(McpRequestContext context) {
		Object principal = context.getAdmissionIdentity().getPrincipal()
				.orElseThrow();
		if (!(principal instanceof String stringPrincipal))
			throw new IllegalStateException("A string principal is required.");
		return new IdentityBinding(stringPrincipal,
				context.getAdmissionIdentity().getAuthorizationPartitionKey()
						.orElseThrow());
	}

	private static String requestId(McpRequestContext context) {
		return context.getRequestId().orElseThrow().asString().orElseThrow();
	}

	private static McpJsonRpcException invalidCursor() {
		return new McpJsonRpcException(
				McpJsonRpcError.fromInvalidParameters(CURSOR_ERROR_MESSAGE));
	}

	private static String requireKeyId(String value) {
		requireNonNull(value);
		if (!value.matches("[A-Za-z0-9_-]{1,64}"))
			throw new IllegalArgumentException("Invalid application cursor key ID.");
		return value;
	}

	private static String requireClaimText(String value) {
		requireNonNull(value);
		if (value.isBlank())
			throw new IllegalArgumentException("Cursor claims must not be blank.");
		return value;
	}
}
