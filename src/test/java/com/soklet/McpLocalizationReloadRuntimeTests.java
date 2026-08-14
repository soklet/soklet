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
import java.net.URI;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-network reload-control evidence: {@code catalogsChanged()} delivers
 * coarse per-family invalidations through the existing subscription machinery,
 * advertisement and filters stay truthful per localized surface, stale
 * pre-rendered terminal state is released, generations fence delivery, and two
 * nodes invalidate independently.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@Timeout(30)
class McpLocalizationReloadRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/localization/reload";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	void catalogsChangedDeliversOneCoarseInvalidationPerLocalizedFamily() {
		McpServer server = server(true, localizer(
				text -> McpLocalizationResult.fromDefaultText()));

		List<String> frames = new ArrayList<>();
		Soklet.runSimulator(config(server), simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("invalidation",
							"\"toolsListChanged\":true,"
									+ "\"promptsListChanged\":true,"
									+ "\"resourcesListChanged\":true"));
			String acknowledgment = nextFrame(simulation);
			assertTrue(acknowledgment.contains("\"toolsListChanged\":true"),
					acknowledgment);
			assertTrue(acknowledgment.contains("\"promptsListChanged\":true"),
					acknowledgment);
			assertTrue(acknowledgment.contains("\"resourcesListChanged\":true"),
					acknowledgment);

			server.getLocalizationControl().catalogsChanged();

			for (int index = 0; index < 3; ++index)
				frames.add(nextFrame(simulation));
		});

		String all = String.join("\n", frames);
		assertTrue(all.contains("notifications/tools/list_changed"), all);
		assertTrue(all.contains("notifications/prompts/list_changed"), all);
		assertTrue(all.contains("notifications/resources/list_changed"), all);
		// Coarse means coarse: no localized text, locale, key, or revision.
		assertFalse(all.contains("FR:"), all);
		assertFalse(all.contains("fr"), all);
		assertTrue(all.contains("\"io.modelcontextprotocol/subscriptionId\""),
				all);
	}

	@Test
	void familiesWithoutALocalizedCatalogAreNeitherAcknowledgedNorDelivered() {
		// Only the prompt carries localizable text: no tools, and the resource
		// has no title or description.
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("reload-prompts-only", "1.0").build())
				.prompt(McpPromptRegistration.withName("reload.prompt")
						.handler((request, context, features) ->
								McpCompleteResult.fromPromptOutput(
										McpPromptOutput.fromMessages()))
						.title("Prompt title")
						.build())
				.resource(bareResource())
				// RESOURCE_UPDATED-only support: the application does not offer
				// the resources list-change family, and neither does the
				// localizer for this endpoint, so the flag must not be accepted.
				.subscriptions(McpSubscriptionConfig
						.withEventPublisher(
								McpLocalSubscriptionEventPublisher.fromDefaults())
						.notificationTypes(EnumSet.of(
								McpSubscriptionNotificationType.RESOURCE_UPDATED))
						.build())
				.build();
		McpServer server = server(endpoint, localizer(
				text -> McpLocalizationResult.fromDefaultText()));

		Soklet.runSimulator(config(server), simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("prompts-only",
							"\"toolsListChanged\":true,"
									+ "\"promptsListChanged\":true,"
									+ "\"resourcesListChanged\":true"));
			String acknowledgment = nextFrame(simulation);
			assertFalse(acknowledgment.contains("toolsListChanged"),
					acknowledgment);
			assertTrue(acknowledgment.contains("\"promptsListChanged\":true"),
					acknowledgment);
			assertFalse(acknowledgment.contains("resourcesListChanged"),
					acknowledgment);

			server.getLocalizationControl().catalogsChanged();

			String frame = nextFrame(simulation);
			assertTrue(frame.contains("notifications/prompts/list_changed"),
					frame);
			assertTrue(pollFrame(simulation, Duration.ofMillis(150)).isEmpty(),
					"Only the localized prompts family may deliver.");
		});
	}

	@Test
	void discoveryAdvertisesListChangedOnlyForLocalizedCatalogsWithSubscriptions() {
		String localized = discoveryBody(server(true, localizer(
				text -> McpLocalizationResult.fromDefaultText())));
		assertTrue(localized.contains("\"tools\":{\"listChanged\":true}"),
				localized);
		assertTrue(localized.contains("\"prompts\":{\"listChanged\":true}"),
				localized);
		assertTrue(localized.contains("\"resources\":{\"listChanged\":true"),
				localized);

		String unlocalized = discoveryBody(server(true, null));
		assertTrue(unlocalized.contains("\"tools\":{}"), unlocalized);
		assertTrue(unlocalized.contains("\"prompts\":{}"), unlocalized);

		// Without subscriptions/listen there is no delivery channel, so a
		// localized catalog still advertises nothing.
		String noSubscriptions = discoveryBody(server(false, localizer(
				text -> McpLocalizationResult.fromDefaultText())));
		assertTrue(noSubscriptions.contains("\"tools\":{}"), noSubscriptions);
		assertTrue(noSubscriptions.contains("\"prompts\":{}"), noSubscriptions);
	}

	@Test
	void aStaleLocalizedTerminalIsReleasedByInvalidation() {
		McpServer server = server(true, localizer(
				text -> McpLocalizationResult.fromLocalizedText(
						"FR:" + text.getDefaultText())));
		AtomicReference<McpSimulation> escaped = new AtomicReference<>();

		Soklet.runSimulator(config(server), simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					subscriptionRequest("stale-terminal",
							"\"resourcesListChanged\":true"));
			escaped.set(simulation);
			nextFrame(simulation);

			// The invalidation clears the pre-rendered localized terminal, so
			// the close that follows publishes canonical text instead of
			// retaining the obsolete translation graph.
			server.getLocalizationControl().catalogsChanged();
			nextFrame(simulation);

			try {
				assertEquals(McpStreamTerminationReason.COMPLETED,
						simulation.awaitCompletion(WAIT).orElseThrow(() ->
								new AssertionError("Timed out.")).getReason());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		List<String> frames = drain(escaped.get());
		String terminal = frames.get(frames.size() - 1);
		assertTrue(terminal.contains("\"title\":\"Canonical title\""), terminal);
		assertFalse(terminal.contains("FR:"), terminal);
	}

	@Test
	void generationsFenceDeliveryAndDisabledControlStillThrows() {
		McpServer localized = server(true, localizer(
				text -> McpLocalizationResult.fromDefaultText()));

		// No active listener or simulator generation: accepted as a no-op.
		localized.getLocalizationControl().catalogsChanged();

		McpServer unlocalized = server(true, null);
		assertFalse(unlocalized.getLocalizationControl().isEnabled());
		assertThrows(IllegalStateException.class,
				() -> unlocalized.getLocalizationControl().catalogsChanged());
	}

	@Test
	void twoNodesInvalidateIndependently() {
		McpServer first = server(true, localizer(
				text -> McpLocalizationResult.fromDefaultText()));
		McpServer second = server(true, localizer(
				text -> McpLocalizationResult.fromDefaultText()));

		Soklet.runSimulator(config(first), firstSimulator ->
				Soklet.runSimulator(config(second), secondSimulator -> {
					McpSimulation firstSubscription = firstSimulator
							.startMcpRequest(subscriptionRequest("node-one",
									"\"toolsListChanged\":true"));
					McpSimulation secondSubscription = secondSimulator
							.startMcpRequest(subscriptionRequest("node-two",
									"\"toolsListChanged\":true"));
					nextFrame(firstSubscription);
					nextFrame(secondSubscription);

					// The control is a local-server operation: each node's call
					// reaches only its own streams.
					first.getLocalizationControl().catalogsChanged();
					assertTrue(nextFrame(firstSubscription)
							.contains("notifications/tools/list_changed"));
					assertTrue(pollFrame(secondSubscription,
							Duration.ofMillis(150)).isEmpty(),
							"Node one's invalidation must not reach node two.");

					second.getLocalizationControl().catalogsChanged();
					assertTrue(nextFrame(secondSubscription)
							.contains("notifications/tools/list_changed"));
				}));
	}

	private static McpLocalizer localizer(
			java.util.function.Function<McpLocalizableText,
					McpLocalizationResult> provider) {
		return McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> new McpLocalizationContext() {
					@Override
					public Locale getLocale() {
						return Locale.CANADA_FRENCH;
					}

					@Override
					public McpLocalizationResult localize(
							McpLocalizableText text) {
						return provider.apply(text);
					}
				})
				.build();
	}

	private static McpServer server(boolean subscriptions,
			McpLocalizer localizer) {
		McpEndpoint.Builder builder = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("localization-reload", "1.0")
						.title("Canonical title")
						.description("Canonical description")
						.build())
				.tool(McpToolRegistration.withName("reload.tool")
						.jsonArguments()
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText("unused"))
						.title("Tool title")
						.build())
				.prompt(McpPromptRegistration.withName("reload.prompt")
						.handler((request, context, features) ->
								McpCompleteResult.fromPromptOutput(
										McpPromptOutput.fromMessages()))
						.title("Prompt title")
						.build())
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("reload://text"), "text")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(URI.create(
																"reload://text"),
																"unused")
														.build())
												.build()))
						.title("Resource title")
						.build());

		if (subscriptions)
			builder.subscriptions(subscriptions());

		return server(builder.build(), localizer);
	}

	private static McpServer server(McpEndpoint endpoint,
			McpLocalizer localizer) {
		McpServer.Builder builder = McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(McpRequestAdmissionPolicy.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.maximumSubscriptionDuration(Duration.ofMillis(400));

		if (localizer != null)
			builder.localizer(localizer);

		return builder.build();
	}

	private static McpSubscriptionConfig subscriptions() {
		return McpSubscriptionConfig
				.withEventPublisher(McpLocalSubscriptionEventPublisher.fromDefaults())
				.notificationTypes(EnumSet.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
				.build();
	}

	private static McpResourceRegistration bareResource() {
		return McpResourceRegistration.withUriAndName(
				URI.create("reload://bare"), "bare")
				.handler((request, resource, features) ->
						McpCompleteResult.fromResourceOutput(
								McpResourceOutput.builder()
										.content(McpTextResourceContents
												.withUriAndText(URI.create(
														"reload://bare"),
														"unused")
												.build())
										.build()))
				.build();
	}

	private static SokletConfig config(McpServer server) {
		return SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
	}

	private static String discoveryBody(McpServer server) {
		AtomicReference<String> captured = new AtomicReference<>();

		Soklet.runSimulator(config(server), simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(
					request("discover", "server/discover", ""));

			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT)
						.orElseThrow(() -> new AssertionError("Timed out."));
				captured.set(new String(response.getBody().orElseThrow(),
						StandardCharsets.UTF_8));
				simulation.awaitCompletion(WAIT);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		return captured.get();
	}

	private static String nextFrame(McpSimulation simulation) {
		try {
			return new String(simulation.nextStreamItem(WAIT)
					.orElseThrow(() -> new AssertionError("Timed out."))
					.getEncodedBytes(), StandardCharsets.UTF_8);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static Optional<McpSimulationStreamItem> pollFrame(
			McpSimulation simulation, Duration timeout) {
		try {
			return simulation.nextStreamItem(timeout);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static List<String> drain(McpSimulation simulation) {
		List<String> frames = new ArrayList<>();

		try {
			Optional<McpSimulationStreamItem> item;
			while ((item = simulation.nextStreamItem(Duration.ZERO)).isPresent())
				frames.add(new String(item.orElseThrow().getEncodedBytes(),
						StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}

		assertTrue(!frames.isEmpty(), "Expected at least the terminal frame.");
		return frames;
	}

	private static Request subscriptionRequest(String id, String notifications) {
		return request(id, "subscriptions/listen",
				",\"notifications\":{" + notifications + "}");
	}

	private static Request request(String id, String method,
			String additionalParameters) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}
}
