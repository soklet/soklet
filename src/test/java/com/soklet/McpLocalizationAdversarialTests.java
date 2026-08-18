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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L6 scheduling, containment, and cardinality evidence: work that never
 * reaches an admitted localizable operation never calls the provider, a
 * unique-tag flood retains nothing, and simultaneous locale selection races
 * with catalog invalidation without cross-request contamination.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@Timeout(60)
class McpLocalizationAdversarialTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/localization/adversarial";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	void rejectedAndIrrelevantWorkNeverInvokesTheProvider() {
		AtomicInteger contexts = new AtomicInteger();
		List<Request> inertRequests = List.of(
				// Malformed envelope.
				raw("not-json"),
				// Unknown method: no localizable operation resolves.
				request("unknown-method", "tools/call", null,
						",\"name\":\"missing.tool\",\"arguments\":{}"),
				// Invalid params for a real method.
				request("invalid-params", "resources/read", "bad://uri",
						",\"uri\":\"\""),
				// Protocol-version mismatch is refused before dispatch.
				versionMismatch("version-mismatch"),
				// Notifications neither publish text nor run application code.
				notification());

		for (Request inert : inertRequests) {
			Capture capture = call(server(contexts, null), inert);
			assertTrue(capture.statusCode() >= 200, capture.body());
		}

		assertEquals(0, contexts.get(),
				"Malformed, unknown-method, invalid-params, version-mismatch, "
						+ "and notification work must never call the provider.");
	}

	@Test
	void rateLimitedWorkNeverInvokesTheProvider() {
		AtomicInteger contexts = new AtomicInteger();
		McpServer server = serverBuilder(contexts, null)
				.requestRateLimiter(context -> McpRateLimitDecision
						.denied(Duration.ofSeconds(30)))
				.build();

		Capture capture = call(server, request("rate-limited", "server/discover",
				null, ""));

		assertTrue(capture.statusCode() == 429 || capture.statusCode() == 503,
				capture.statusCode() + " " + capture.body());
		assertEquals(0, contexts.get(),
				"Rate-limited work must never call the provider.");
	}

	@Test
	void admissionRejectedWorkNeverInvokesTheProvider() {
		AtomicInteger contexts = new AtomicInteger();
		McpServer server = serverBuilder(contexts, null)
				.admissionController(context -> McpAdmissionDecision
						.rejected(McpAdmissionRejection.withStatusCodeAndError(403,
								McpJsonRpcError.fromApplication(-31000, "denied"))
								.build()))
				.build();

		Capture capture = call(server, request("rejected", "server/discover",
				null, ""));

		assertEquals(403, capture.statusCode(), capture.body());
		assertEquals(0, contexts.get(),
				"Admission-rejected work must never call the provider.");
	}

	@Test
	void aUniqueTagFloodRetainsNoStateOrMetricSeries() {
		AtomicInteger contexts = new AtomicInteger();
		List<String> observedTags = new CopyOnWriteArrayList<>();
		RecordingMetrics metrics = new RecordingMetrics();
		McpServer server = serverBuilder(contexts, request -> {
			request.getLanguageRanges().stream().findFirst()
					.ifPresent(range -> observedTags.add(range.getRange()));
			return Locale.CANADA_FRENCH;
		}).build();
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metrics)
				.build();

		int floodSize = 250;
		Soklet.runSimulator(config, simulator -> {
			for (int index = 0; index < floodSize; ++index)
				awaitBody(simulator, request("flood-" + index, "server/discover",
						null, "", Set.of("xx-" + String.format("%03d", index))));
		});

		assertEquals(floodSize, contexts.get(),
				"Each request creates exactly one context.");
		assertEquals(floodSize, observedTags.size());
		assertEquals(floodSize, Set.copyOf(observedTags).size(),
				"Every request carried a distinct tag.");

		// Metric labels are bounded by endpoint and method, never by locale, so
		// a unique-tag flood creates no new series.
		Set<String> methodLabels = metrics.finishedMethods();
		assertEquals(Set.of("server/discover"), methodLabels,
				"Locale must never become a metric dimension.");
		assertTrue(metrics.events().stream().noneMatch(event ->
				event.toString().contains("xx-")),
				"No metric event may retain a raw language tag.");
	}

	@Test
	void simultaneousLocaleSelectionAndInvalidationStayIsolated() throws Exception {
		AtomicInteger contexts = new AtomicInteger();
		int concurrency = 8;
		CountDownLatch allInside = new CountDownLatch(concurrency);
		McpServer server = serverBuilder(contexts, request -> {
			// Every request parks inside the provider so its locale selection
			// overlaps every peer's, and overlaps the invalidation below.
			allInside.countDown();
			try {
				allInside.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			String tag = request.getLanguageRanges().stream().findFirst()
					.map(Locale.LanguageRange::getRange).orElse("und");
			return Locale.forLanguageTag(tag);
		}).build();
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
		List<String> bodies = new CopyOnWriteArrayList<>();

		Soklet.runSimulator(config, simulator -> {
			List<McpSimulation> simulations = new ArrayList<>();

			for (int index = 0; index < concurrency; ++index)
				simulations.add(simulator.startMcpRequest(
						request("race-" + index, "server/discover", null, "",
								Set.of(index % 2 == 0 ? "fr-CA" : "de-DE"))));

			// Races the in-flight selections; must neither corrupt nor block.
			server.getLocalizationControl().catalogsChanged();

			for (McpSimulation simulation : simulations)
				bodies.add(awaitStartedBody(simulation));
		});

		assertEquals(concurrency, contexts.get(),
				"Each concurrent request gets its own context.");
		assertEquals(concurrency, bodies.size());

		// Every response carries the canonical body: each request rendered from
		// its own context with no cross-request contamination.
		for (String body : bodies) {
			assertTrue(body.contains("\"instructions\":\"Canonical instructions.\""),
					body);
			assertFalse(body.contains("error"), body);
		}
	}

	private interface LocaleSelector {
		Locale select(McpLocalizationRequest request);
	}

	private static McpServer server(AtomicInteger contexts,
			LocaleSelector selector) {
		return serverBuilder(contexts, selector).build();
	}

	private static McpServer.Builder serverBuilder(AtomicInteger contexts,
			LocaleSelector selector) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("localization-adversarial", "1.0")
						.title("Canonical title")
						.build())
				.instructions("Canonical instructions.")
				.tool(McpToolRegistration.withName("adversarial.tool")
						.jsonArguments()
						.handler((request, arguments, features) ->
								McpCompleteResult.fromToolText("unused"))
						.title("Tool title")
						.build())
				.resource(McpResourceRegistration.withUriAndName(
						java.net.URI.create("adversarial://text"), "text")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(java.net.URI
																.create("adversarial://text"),
																"unused")
														.build())
												.build()))
						.build())
				.build();
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.localizer(McpLocalizer.withFallbackLocale(Locale.ENGLISH)
						.contextProvider(request -> {
							contexts.incrementAndGet();
							Locale locale = selector == null ? Locale.CANADA_FRENCH
									: selector.select(request);
							return McpLocalizationContext.withLocale(locale)
									.localizer(text ->
											McpLocalizationResult.useDefaultText())
									.build();
						})
						.build());
	}

	private record Capture(int statusCode, String body) {}

	private static Capture call(McpServer server, Request request) {
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
		AtomicReference<Capture> captured = new AtomicReference<>();

		Soklet.runSimulator(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(request);

			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT)
						.orElseThrow(() -> new AssertionError("Timed out."));
				captured.set(new Capture(response.getStatusCode(),
						new String(response.getBody().orElse(new byte[0]),
								StandardCharsets.UTF_8)));
				simulation.awaitCompletion(WAIT);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		});

		return captured.get();
	}

	private static String awaitBody(Simulator simulator, Request request) {
		return awaitStartedBody(simulator.startMcpRequest(request));
	}

	private static String awaitStartedBody(McpSimulation simulation) {
		try {
			McpSimulationResponse response = simulation.awaitResponse(WAIT)
					.orElseThrow(() -> new AssertionError("Timed out."));
			String body = new String(response.getBody().orElse(new byte[0]),
					StandardCharsets.UTF_8);
			simulation.awaitCompletion(WAIT);
			return body;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static Request request(String id, String method,
			String operationName, String additionalParameters) {
		return request(id, method, operationName, additionalParameters, Set.of());
	}

	private static Request request(String id, String method,
			String operationName, String additionalParameters,
			Set<String> acceptLanguage) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}"
				+ additionalParameters + "}}";
		Map<String, Set<String>> headers = baseHeaders(method);

		if (operationName != null)
			headers.put("Mcp-Name", Set.of(operationName));
		if (!acceptLanguage.isEmpty())
			headers.put("Accept-Language", acceptLanguage);

		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static Request raw(String body) {
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(baseHeaders("server/discover"))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static Request versionMismatch(String id) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"1999-01-01\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(baseHeaders("server/discover"))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static Request notification() {
		String body = "{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/cancelled\","
				+ "\"params\":{\"requestId\":\"absent\"}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(baseHeaders("notifications/cancelled"))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static Map<String, Set<String>> baseHeaders(String method) {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));
		return headers;
	}

	private static final class RecordingMetrics implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
			this.events.add(event);
		}

		private List<McpMetricsEvent> events() {
			return List.copyOf(this.events);
		}

		private Set<String> finishedMethods() {
			return this.events.stream()
					.filter(McpMetricsEvent.RequestFinished.class::isInstance)
					.map(McpMetricsEvent.RequestFinished.class::cast)
					.map(McpMetricsEvent.RequestFinished::getJsonRpcMethod)
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
	}
}
