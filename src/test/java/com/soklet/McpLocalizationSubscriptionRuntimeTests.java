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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-network evidence for subscription terminal-metadata pre-rendering: the
 * two-phase reservation creates one context before response commitment, failed
 * pre-render under {@code FAIL_REQUEST} rolls the reservation back exactly
 * once, and {@code USE_DEFAULT_TEXT} publishes the canonical terminal.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@Timeout(30)
class McpLocalizationSubscriptionRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/localization/subscription";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	void theTerminalFrameIsPreRenderedLocalizedAtSubscriptionOpen() {
		AtomicInteger contexts = new AtomicInteger();
		AtomicInteger contextsAtResponse = new AtomicInteger(-1);
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					contexts.incrementAndGet();
					return context(text -> McpLocalizationResult
							.localized("FR:" + text.getDefaultText()));
				})
				.build();

		List<String> frames = subscribeAndDrain(localizer, 2, response -> {
			contextsAtResponse.set(contexts.get());
			assertEquals(Set.of("fr"),
					response.getHeaders().get("Content-Language"));
			assertEquals(Set.of("Accept-Language"),
					response.getHeaders().get("Vary"));
		});

		assertEquals(1, contexts.get(),
				"Exactly one context per subscription open.");
		assertEquals(1, contextsAtResponse.get(),
				"The context must exist before response commitment.");
		String terminal = frames.get(frames.size() - 1);
		assertTrue(terminal.contains("\"title\":\"FR:Canonical title\""),
				terminal);
		assertTrue(terminal.contains(
				"\"description\":\"FR:Canonical description\""), terminal);
		assertTrue(terminal.contains(
				"\"io.modelcontextprotocol/subscriptionId\""), terminal);
		assertFalse(terminal.contains("\"title\":\"Canonical title\""), terminal);
		// The acknowledgment has no localizable text and stays canonical.
		assertFalse(frames.get(0).contains("FR:"), frames.get(0));
	}

	@Test
	void useDefaultTextPreRenderFailurePublishesTheCanonicalTerminal() {
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> context(text ->
						McpLocalizationResult.failure()))
				.build();

		List<String> frames = subscribeAndDrain(localizer, 2, response ->
				assertEquals(Set.of("en"),
						response.getHeaders().get("Content-Language")));

		String terminal = frames.get(frames.size() - 1);
		assertTrue(terminal.contains("\"title\":\"Canonical title\""), terminal);
		assertFalse(terminal.contains("FR:"), terminal);
	}

	@Test
	void failRequestPreRenderRollsTheReservationBackExactlyOnce() {
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					throw new IllegalStateException("secret-subscription-detail");
				})
				.failurePolicy(McpLocalizationFailurePolicy.FAIL_REQUEST)
				.build();
		SokletConfig config = config(localizer, 2);
		List<Integer> statusCodes = new ArrayList<>();
		List<String> bodies = new ArrayList<>();

		Soklet.runSimulator(config, simulator -> {
			// With a per-authorization-partition cap of 2, any reservation leak
			// would turn the third and later attempts into capacity rejections
			// instead of the sanitized localization failure.
			for (int attempt = 0; attempt < 5; ++attempt) {
				McpSimulation simulation = simulator.startMcpRequest(
						subscriptionRequest("rollback-" + attempt));

				try {
					McpSimulationResponse response = simulation.awaitResponse(WAIT)
							.orElseThrow(() -> new AssertionError("Timed out."));
					statusCodes.add(response.getStatusCode());
					bodies.add(new String(response.getBody().orElseThrow(),
							StandardCharsets.UTF_8));
					simulation.awaitCompletion(WAIT);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError(e);
				}
			}
		});

		assertEquals(List.of(500, 500, 500, 500, 500), statusCodes,
				"Every attempt must be the sanitized failure, never a "
						+ "capacity rejection from a leaked reservation.");

		for (String body : bodies) {
			assertTrue(body.contains("\"code\":-32603"), body);
			assertTrue(body.contains("\"message\":\"Internal error\""), body);
			assertFalse(body.contains("secret-subscription-detail"), body);
		}
	}

	private interface ResponseProbe {
		void observe(McpSimulationResponse response);
	}

	private static List<String> subscribeAndDrain(McpLocalizer localizer,
			int maximumSubscriptionsPerPrincipal, ResponseProbe probe) {
		SokletConfig config = config(localizer, maximumSubscriptionsPerPrincipal);
		AtomicReference<McpSimulation> escaped = new AtomicReference<>();

		Soklet.runSimulator(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("terminal-render"));
			escaped.set(simulation);

			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT)
						.orElseThrow(() -> new AssertionError("Timed out."));
				assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
						response.getBodyMode());
				probe.observe(response);
				// Consume the acknowledgment so shutdown ordering stays exact.
				simulation.nextStreamItem(WAIT);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
			// The 300ms maximum subscription duration completes the stream
			// gracefully while the simulated client is still attached, which is
			// what delivers the (pre-rendered) terminal frame.
			try {
				assertEquals(McpStreamTerminationReason.COMPLETED,
						simulation.awaitCompletion(WAIT).orElseThrow(() ->
								new AssertionError("Timed out.")).getReason());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		McpSimulation simulation = escaped.get();
		List<String> frames = new ArrayList<>();
		frames.add("consumed acknowledgment");

		try {
			Optional<McpSimulationStreamItem> item;
			while ((item = simulation.nextStreamItem(Duration.ZERO)).isPresent())
				frames.add(new String(item.orElseThrow().getEncodedBytes(),
						StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}

		assertTrue(frames.size() >= 2, frames.toString());
		return frames;
	}

	private static SokletConfig config(McpLocalizer localizer,
			int maximumSubscriptionsPerPrincipal) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("localization-subscription", "1.0")
						.title("Canonical title")
						.description("Canonical description")
						.build())
				.resource(McpResourceRegistration.withUriAndName(
						java.net.URI.create("subscription://text"), "text")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(java.net.URI
																.create("subscription://text"),
																"unused")
														.build())
												.build()))
						.build())
				.subscriptions(McpSubscriptionConfig
						.withEventPublisher(
								McpLocalSubscriptionEventPublisher.fromDefaults())
						.notificationTypes(EnumSet.of(
								McpSubscriptionNotificationType
										.RESOURCES_LIST_CHANGED))
						.build())
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.maximumSubscriptionsPerPrincipal(maximumSubscriptionsPerPrincipal)
				.maximumSubscriptionDuration(Duration.ofMillis(300))
				.localizer(localizer)
				.build();
		return SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
	}

	private static McpLocalizationContext context(
			java.util.function.Function<McpLocalizableText,
					McpLocalizationResult> provider) {
		return McpLocalizationContext.withLocale(Locale.FRENCH)
				.localizer(provider)
				.build();
	}

	private static Request subscriptionRequest(String id) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of("subscriptions/listen"));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}
}
