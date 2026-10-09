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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real-listener initialization projection, fallback, and session rollback. */
@Timeout(60)
class McpLocalizationInitializationPublicRuntimeTests {

	private static final List<McpProtocolVersion> LEGACY = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	@TestFactory
	Stream<DynamicTest> initializationLocalizesOnlyRevisionSupportedText() {
		return LEGACY.stream().map(version -> DynamicTest.dynamicTest(version.getWireValue(), () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			List<McpLocalizableText> lookedUp = new ArrayList<>();
			McpLocalizer localizer = localizer(request -> context(text -> {
				lookedUp.add(text);
				return McpLocalizationResult.localized("FR:" + text.getDefaultText());
			}), McpLocalizationFailurePolicy.FAIL_REQUEST);
			try (Fixture fixture = new Fixture(version, localizer, false, false)) {
				Captured response = fixture.initialize();
				assertLocalized(response, version);
				assertEquals(version == McpProtocolVersion.V2025_06_18 ? 2 : 3, lookedUp.size());
				assertEquals(Set.of(McpTextOwnerType.SERVER_INFORMATION, McpTextOwnerType.ENDPOINT),
						lookedUp.stream().map(text -> text.getCoordinate().getOwnerType()).collect(java.util.stream.Collectors.toSet()));
				assertEquals(Set.of("/title", "/instructions"), lookedUp.stream()
						.filter(text -> !text.getCoordinate().getMemberPath().equals("/description"))
						.map(text -> text.getCoordinate().getMemberPath()).collect(java.util.stream.Collectors.toSet()));
				assertEquals("Canonical title", fixture.endpoint.getServerInfo().getTitle().orElseThrow());
				assertEquals("Canonical description", fixture.endpoint.getServerInfo().getDescription().orElseThrow());
			}
		})));
	}

	@TestFactory
	Stream<DynamicTest> failedInitializationLocalizationsReleaseSessionsAndCanRecover() {
		return LEGACY.stream().map(version -> DynamicTest.dynamicTest(version.getWireValue(), () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			AtomicReference<String> mode = new AtomicReference<>("provider");
			McpLocalizer localizer = localizer(request -> {
				if (mode.get().equals("provider")) throw new IllegalStateException("private-initialize-canary");
				return context(text -> mode.get().equals("lookup") ? McpLocalizationResult.failure()
						: McpLocalizationResult.localized("FR:" + text.getDefaultText()));
			}, McpLocalizationFailurePolicy.FAIL_REQUEST);
			try (Fixture fixture = new Fixture(version, localizer, true, true)) {
				for (String failure : List.of("provider", "lookup", "provider", "lookup")) {
					mode.set(failure);
					Captured response = fixture.initialize();
					assertEquals(200, response.status(), response.body());
					assertTrue(response.body().contains("\"code\":-32603"), response.body());
					assertFalse(response.body().contains("private-initialize-canary"), response.body());
					assertFalse(response.body().contains("\"result\""), response.body());
					assertNull(response.header("Mcp-Session-Id"));
					assertNull(response.header("Content-Language"));
				}
				mode.set("success");
				Captured response = fixture.initialize();
				assertLocalized(response, version);
				String sessionId = response.header("Mcp-Session-Id");
				assertNotNull(sessionId, "A failed localization must release the single session slot.");
				Captured ping = fixture.exchange("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}",
						List.of(new HeaderValue("Mcp-Session-Id", sessionId)));
				assertEquals(200, ping.status(), ping.body());
				assertTrue(ping.body().contains("\"result\":{}"), ping.body());
			}
		})));
	}

	@TestFactory
	Stream<DynamicTest> wholeResponseFallbackAndNoLocalizerPreserveCanonicalBytes() {
		return LEGACY.stream().map(version -> DynamicTest.dynamicTest(version.getWireValue(), () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			Captured canonical;
			try (Fixture fixture = new Fixture(version, null, false, true)) { canonical = fixture.initialize(); }
			for (boolean providerFailure : List.of(true, false)) {
				McpLocalizer localizer = localizer(request -> {
					if (providerFailure) throw new IllegalStateException("private-initialize-canary");
					return context(text -> text.getDefaultText().equals("Canonical title")
							? McpLocalizationResult.localized("partial") : McpLocalizationResult.failure());
				}, McpLocalizationFailurePolicy.USE_DEFAULT_TEXT);
				try (Fixture fixture = new Fixture(version, localizer, false, true)) {
					Captured fallback = fixture.initialize();
					assertEquals(canonical.body(), fallback.body());
					assertEquals("en", fallback.header("Content-Language"));
					assertEquals("Accept-Language", fallback.header("Vary"));
				}
			}
		})));
	}

	@TestFactory
	Stream<DynamicTest> simulatorUsesTheSameInitializationLocalization() {
		return LEGACY.stream().map(version -> DynamicTest.dynamicTest(version.getWireValue(), () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			McpLocalizer localizer = localizer(request -> context(text ->
					McpLocalizationResult.localized("FR:" + text.getDefaultText())), McpLocalizationFailurePolicy.FAIL_REQUEST);
			SokletSimulator.run(SimulatorConfig.builder().configureMcpServer(builder -> configure(builder,
					endpoint(version, false, true), localizer, false))
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build(), simulator -> {
				Request request = Request.withPath(HttpMethod.POST, "/mcp")
						.headers(Map.of("Host", List.of("127.0.0.1:0"), "Content-Type", List.of("application/json"),
								"Accept", List.of("application/json, text/event-stream"),
								"MCP-Protocol-Version", List.of(version.getWireValue()), "Accept-Language", List.of("fr")))
						.body(initializeBody(version).getBytes(StandardCharsets.UTF_8)).build();
				try (McpSimulation simulation = simulator.startMcpRequest(request)) {
					McpSimulationResponse response = simulation.awaitResponse(Duration.ofSeconds(5)).orElseThrow();
					simulation.awaitCompletion(Duration.ofSeconds(5)).orElseThrow();
					assertLocalized(new Captured(response.getStatusCode(), response.getHeaders(),
							new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8)), version);
				}
			});
		})));
	}

	@Test
	void juneInitializationCallbackBoundCountsOnlyPublishedText() {
		McpEndpoint endpoint = endpoint(McpProtocolVersion.V2025_06_18, false, true);
		McpCanonicalLocalizationPlan plan = DefaultMcpLocalizationCatalogExtractor.plan(
				McpEndpointRegistry.fromEndpoints(List.of(endpoint)), 2);
		assertEquals(Set.of("Canonical title", "Canonical instructions"), plan.texts().stream()
				.map(McpLocalizableText::getDefaultText).collect(java.util.stream.Collectors.toSet()));
		assertThrows(IllegalStateException.class, () -> DefaultMcpLocalizationCatalogExtractor.plan(
				McpEndpointRegistry.fromEndpoints(List.of(endpoint)), 1));
		assertThrows(IllegalStateException.class, () -> DefaultMcpLocalizationCatalogExtractor.plan(
				McpEndpointRegistry.fromEndpoints(List.of(endpoint(McpProtocolVersion.V2025_11_25, false, true))), 2));
	}

	@Test
	void mixedRevisionPlansReuseCanonicalCoordinates() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("canonical-server", "1")
						.title("Canonical title").description("Canonical description").build(),
				Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28))
				.instructions("Canonical instructions").build();
		McpCanonicalLocalizationPlan plan = DefaultMcpLocalizationCatalogExtractor.plan(
				McpEndpointRegistry.fromEndpoints(List.of(endpoint)), 3);
		assertEquals(3, plan.texts().size(), "Sharing a canonical text across wire projections must not duplicate its coordinate.");
		McpCanonicalLocalizationPlan.EndpointPlan endpointPlan = plan.endpoints().get(0);
		List<McpCanonicalLocalizationPlan.Slot> june = endpointPlan
				.response(McpCanonicalLocalizationPlan.ResponseKind.INITIALIZE_2025_06_18).orElseThrow().slots();
		List<McpCanonicalLocalizationPlan.Slot> november = endpointPlan
				.response(McpCanonicalLocalizationPlan.ResponseKind.INITIALIZE_2025_11_25).orElseThrow().slots();
		assertEquals(List.of("/serverInfo/title", "/instructions"), june.stream()
				.map(McpCanonicalLocalizationPlan.Slot::targetPointer).toList());
		assertEquals(List.of("/serverInfo/title", "/serverInfo/description", "/instructions"), november.stream()
				.map(McpCanonicalLocalizationPlan.Slot::targetPointer).toList());
		assertSame(june.get(0).text(), november.get(0).text());
		assertSame(june.get(1).text(), november.get(2).text());
		assertSame(june.get(0).text(), endpointPlan.response(McpCanonicalLocalizationPlan.ResponseKind.DISCOVERY)
				.orElseThrow().slots().get(0).text());
	}

	private static void assertLocalized(Captured response, McpProtocolVersion version) {
		assertEquals(200, response.status(), response.body());
		assertEquals("fr", response.header("Content-Language"));
		assertEquals("Accept-Language", response.header("Vary"));
		assertTrue(response.body().contains("\"title\":\"FR:Canonical title\""), response.body());
		assertTrue(response.body().contains("\"instructions\":\"FR:Canonical instructions\""), response.body());
		assertTrue(response.body().contains("\"protocolVersion\":\"" + version.getWireValue() + "\""), response.body());
		assertTrue(response.body().contains("\"name\":\"canonical-server\",\"version\":\"1\""), response.body());
		assertFalse(response.body().contains("resultType"), response.body());
		assertFalse(response.body().contains("io.modelcontextprotocol/serverInfo"), response.body());
		if (version == McpProtocolVersion.V2025_06_18) assertFalse(response.body().contains("description"), response.body());
		else assertTrue(response.body().contains("\"description\":\"FR:Canonical description\""), response.body());
	}

	private static McpLocalizer localizer(McpLocalizationContextProvider provider, McpLocalizationFailurePolicy policy) {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH, provider).failurePolicy(policy).build();
	}

	private static McpLocalizationContext context(McpLocalizationLookup lookup) {
		return McpLocalizationContext.withLocale(Locale.FRENCH, lookup).build();
	}

	private static McpEndpoint endpoint(McpProtocolVersion version, boolean sessions, boolean serverInfoIncluded) {
		return McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("canonical-server", "1")
				.title("Canonical title").description("Canonical description").build(), Set.of(version))
				.instructions("Canonical instructions").serverInfoIncluded(serverInfoIncluded)
				.sessionProtocolVersions(sessions ? Set.of(version) : Set.of()).build();
	}

	private static void configure(McpServer.Builder builder, McpEndpoint endpoint, McpLocalizer localizer, boolean sessions) {
		builder.port(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(admissionContext -> McpAdmissionDecision.accepted(
						McpAdmissionIdentity.withRateLimitPartitionKey("owner")
								.authorizationPartitionKey("owner").principal("owner").build()));
		if (localizer != null) builder.localizer(localizer);
		if (sessions) builder.sessionConfig(McpSessionConfig.withOwnerKeyResolver(identity -> "owner")
				.maximumSessions(1).maximumSessionsPerOwner(1).build());
	}

	private static String initializeBody(McpProtocolVersion version) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
				+ version.getWireValue() + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
	}

	private record Captured(int status, Map<String, List<String>> headers, String body) {
		String header(String name) {
			return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
					.map(entry -> String.join(", ", entry.getValue())).findFirst().orElse(null);
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final McpProtocolVersion version;
		private final McpEndpoint endpoint;
		private final Soklet soklet;
		private final int port;

		Fixture(McpProtocolVersion version, McpLocalizer localizer, boolean sessions, boolean serverInfoIncluded) {
			this.version = version;
			this.endpoint = endpoint(version, sessions, serverInfoIncluded);
			McpServer.Builder builder = McpServer.withPort(0);
			configure(builder, endpoint, localizer, sessions);
			McpServer server = builder.build();
			this.soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build());
			soklet.start();
			this.port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
		}

		Captured initialize() throws Exception { return exchange(initializeBody(version), List.of()); }

		Captured exchange(String body, List<HeaderValue> additional) throws Exception {
			List<HeaderValue> headers = new ArrayList<>(additional);
			headers.add(new HeaderValue("MCP-Protocol-Version", version.getWireValue()));
			headers.add(new HeaderValue("Accept-Language", "fr"));
			try (RawClient client = new RawClient(port, "POST", "/mcp", body, headers)) {
				Head head = client.readHead();
				return new Captured(head.status(), head.headers(), client.readBody(head));
			}
		}

		@Override public void close() { soklet.close(); }
	}
}
