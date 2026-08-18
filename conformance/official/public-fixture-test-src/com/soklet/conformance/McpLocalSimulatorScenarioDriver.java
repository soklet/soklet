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

package com.soklet.conformance;

import com.soklet.HttpMethod;
import com.soklet.LifecycleObserver;
import com.soklet.McpMetricsEvent;
import com.soklet.McpServer;
import com.soklet.McpServerDiagnostics;
import com.soklet.McpServerStatus;
import com.soklet.McpShutdownOutcome;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyMode;
import com.soklet.McpSimulationCompletion;
import com.soklet.McpSimulationResponse;
import com.soklet.McpSimulationStreamItem;
import com.soklet.McpSimulationStreamItemType;
import com.soklet.McpStreamTerminationReason;
import com.soklet.MetricsCollector;
import com.soklet.Request;
import com.soklet.Simulator;
import com.soklet.Soklet;
import com.soklet.SokletConfig;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Candidate-artifact, public-API-only replay of the pinned 39 RUN scenarios
 * through Soklet's off-network MCP simulator.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class McpLocalSimulatorScenarioDriver {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	// GitHub-hosted runners can briefly deschedule the simulator's worker and
	// metrics threads. Keep every wait bounded by the Node driver's 120-second
	// process timeout while allowing enough headroom for that contention.
	private static final Duration WAIT = Duration.ofSeconds(15);
	private static final String EMPTY_CAPABILITIES = "{}";
	private static final String FORM_CAPABILITY =
			"{\"elicitation\":{\"form\":{}}}";
	private static final String SAMPLING_CAPABILITY = "{\"sampling\":{}}";
	private static final String ROOTS_CAPABILITY = "{\"roots\":{}}";
	private static final String ALL_INPUT_CAPABILITIES =
			"{\"elicitation\":{\"form\":{}},\"sampling\":{},\"roots\":{}}";
	private static final String FORM_NAME_RESPONSE =
			"{\"action\":\"accept\",\"content\":{\"name\":\"Alice\"}}";
	private static final String FORM_CONFIRM_RESPONSE =
			"{\"action\":\"accept\",\"content\":{\"ok\":true}}";
	private static final String FORM_CONTEXT_RESPONSE =
			"{\"action\":\"accept\",\"content\":{\"context\":\"test context\"}}";
	private static final String FORM_COLOR_RESPONSE =
			"{\"action\":\"accept\",\"content\":{\"color\":\"blue\"}}";
	private static final String SAMPLING_RESPONSE =
			"{\"role\":\"assistant\",\"model\":\"local-simulator\","
					+ "\"content\":{\"type\":\"text\",\"text\":\"Paris\"}}";
	private static final String ROOTS_RESPONSE =
			"{\"roots\":[{\"uri\":\"file:///test/root\"}]}";
	private static final List<ScenarioRow> EXPECTED_ROWS = List.of(
			new ScenarioRow(1, "server-stateless"),
			new ScenarioRow(3, "tools-list"),
			new ScenarioRow(4, "tools-call-simple-text"),
			new ScenarioRow(5, "tools-call-image"),
			new ScenarioRow(6, "tools-call-audio"),
			new ScenarioRow(7, "tools-call-embedded-resource"),
			new ScenarioRow(8, "tools-call-mixed-content"),
			new ScenarioRow(9, "tools-call-error"),
			new ScenarioRow(10, "tools-call-with-progress"),
			new ScenarioRow(11, "json-schema-2020-12"),
			new ScenarioRow(12, "server-sse-multiple-streams"),
			new ScenarioRow(13, "resources-list"),
			new ScenarioRow(14, "resources-read-text"),
			new ScenarioRow(15, "resources-read-binary"),
			new ScenarioRow(16, "resources-templates-read"),
			new ScenarioRow(17, "sep-2164-resource-not-found"),
			new ScenarioRow(18, "prompts-list"),
			new ScenarioRow(19, "prompts-get-simple"),
			new ScenarioRow(20, "prompts-get-with-args"),
			new ScenarioRow(21, "prompts-get-embedded-resource"),
			new ScenarioRow(22, "prompts-get-with-image"),
			new ScenarioRow(23, "dns-rebinding-protection"),
			new ScenarioRow(24, "caching"),
			new ScenarioRow(25, "http-header-validation"),
			new ScenarioRow(26, "http-custom-header-server-validation"),
			new ScenarioRow(27, "input-required-result-basic-elicitation"),
			new ScenarioRow(28, "input-required-result-basic-sampling"),
			new ScenarioRow(29, "input-required-result-basic-list-roots"),
			new ScenarioRow(30, "input-required-result-request-state"),
			new ScenarioRow(31, "input-required-result-multiple-input-requests"),
			new ScenarioRow(32, "input-required-result-multi-round"),
			new ScenarioRow(33, "input-required-result-missing-input-response"),
			new ScenarioRow(34, "input-required-result-non-tool-request"),
			new ScenarioRow(35, "input-required-result-result-type"),
			new ScenarioRow(36, "input-required-result-unsupported-methods"),
			new ScenarioRow(37, "input-required-result-tampered-state"),
			new ScenarioRow(38, "input-required-result-capability-check"),
			new ScenarioRow(39, "input-required-result-ignore-extra-params"),
			new ScenarioRow(40, "input-required-result-validate-input"));

	private McpLocalSimulatorScenarioDriver() {
	}

	/**
	 * Replays the exact manifest-ordinal projection supplied by the Node wrapper.
	 *
	 * @param arguments exact {@code ordinal:name} RUN rows
	 */
	public static void main(String[] arguments) throws Exception {
		runManifestRowsOffNetwork(arguments);
	}

	/**
	 * Replays the exact rows without requiring a subprocess.
	 *
	 * @param rows exact {@code ordinal:name} RUN rows
	 * @throws Exception if a bounded replay operation cannot complete
	 */
	public static void runManifestRowsOffNetwork(String... rows) throws Exception {
		List<ScenarioRow> parsedRows = parseRows(rows);
		assertEquals(EXPECTED_ROWS, parsedRows,
				"Driver arguments differ from the pinned manifest ordinal projection");
		for (ScenarioRow row : parsedRows) {
			try {
				runScenario(row);
			} catch (RuntimeException | Error failure) {
				throw new AssertionError("Local simulator scenario failed: "
						+ row.ordinal() + ":" + row.name(), failure);
			}
			byte[] bytes = passLine(row).getBytes(StandardCharsets.UTF_8);
			System.out.write(bytes, 0, bytes.length);
			System.out.flush();
		}
	}

	private static List<ScenarioRow> parseRows(String[] arguments) {
		if (arguments.length != EXPECTED_ROWS.size())
			throw new IllegalArgumentException(
					"The local simulator driver requires exactly 39 rows.");
		List<ScenarioRow> rows = new ArrayList<>(arguments.length);
		for (String argument : arguments) {
			int separator = argument.indexOf(':');
			if (separator <= 0 || separator == argument.length() - 1
					|| argument.indexOf(':', separator + 1) >= 0)
				throw new IllegalArgumentException(
						"Invalid local simulator row argument.");
			int ordinal;
			try {
				ordinal = Integer.parseInt(argument.substring(0, separator));
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException(
						"Invalid local simulator row ordinal.", exception);
			}
			String name = argument.substring(separator + 1);
			if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
				throw new IllegalArgumentException(
						"Invalid local simulator scenario name.");
			rows.add(new ScenarioRow(ordinal, name));
		}
		return List.copyOf(rows);
	}

	private static void runScenario(ScenarioRow row) {
		RecordingMetrics metrics = new RecordingMetrics(
				expectedSemanticTerminals(row.name()));
		RecordingLifecycle lifecycle = new RecordingLifecycle();
		SokletConfig base = McpConformanceFixture
				.simulationConfigForScenario(row.name());
		SokletConfig config = base.copy()
				.metricsCollector(metrics)
				.lifecycleObserver(lifecycle)
				.finish();
		McpServer server = config.getMcpServer().orElseThrow();

		assertStopped(server);
		Soklet.runSimulator(config, simulator -> {
			assertStopped(server);
			executeScenario(row, simulator, server);
			assertStopped(server);
		});
		metrics.awaitSemanticTerminal();
		metrics.assertNoListenerOrTransportEvents();
		lifecycle.assertNoServerLifecycle();
		assertStopped(server);
	}

	private static int expectedSemanticTerminals(String scenario) {
		return switch (scenario) {
			case "server-stateless" -> 5;
			case "json-schema-2020-12",
					"server-sse-multiple-streams",
					"http-header-validation",
					"http-custom-header-server-validation",
					"input-required-result-basic-elicitation",
					"input-required-result-basic-sampling",
					"input-required-result-basic-list-roots",
					"input-required-result-request-state",
					"input-required-result-multiple-input-requests",
					"input-required-result-non-tool-request",
					"input-required-result-unsupported-methods",
					"input-required-result-tampered-state" -> 2;
			case "input-required-result-multi-round",
					"input-required-result-validate-input" -> 3;
			case "dns-rebinding-protection", "caching" -> 4;
			default -> 1;
		};
	}

	private static void executeScenario(ScenarioRow row, Simulator simulator,
			McpServer server) {
		String prefix = "local-" + row.ordinal();
		switch (row.name()) {
			case "server-stateless" -> serverStateless(simulator, server, prefix);
			case "tools-list" -> toolsList(simulator, prefix);
			case "tools-call-simple-text" -> toolCall(simulator, prefix,
					"test_simple_text", "{}", "This is a simple text response");
			case "tools-call-image" -> toolCall(simulator, prefix,
					"test_image_content", "{}", "\"type\":\"image\"",
					"\"mimeType\":\"image/png\"", "iVBORw0KGgo");
			case "tools-call-audio" -> toolCall(simulator, prefix,
					"test_audio_content", "{}", "\"type\":\"audio\"",
					"\"mimeType\":\"audio/wav\"", "UklGRiQAAABXQVZF");
			case "tools-call-embedded-resource" -> toolCall(simulator, prefix,
					"test_embedded_resource", "{}", "\"type\":\"resource\"",
					"test://embedded-resource", "This is an embedded resource content.");
			case "tools-call-mixed-content" -> mixedToolCall(simulator, prefix);
			case "tools-call-error" -> toolCall(simulator, prefix,
					"test_error_handling", "{}", "\"isError\":true",
					"This tool intentionally returns an error for testing");
			case "tools-call-with-progress" -> progressCall(simulator, prefix,
					prefix + "-progress");
			case "json-schema-2020-12" -> schemaScenario(simulator, prefix);
			case "server-sse-multiple-streams" ->
					multipleProgressStreams(simulator, prefix);
			case "resources-list" -> resourcesList(simulator, prefix);
			case "resources-read-text" -> resourceRead(simulator, prefix,
					"test://static-text", "This is the content of the static text resource.",
					"\"mimeType\":\"text/plain\"");
			case "resources-read-binary" -> resourceRead(simulator, prefix,
					"test://static-binary", "\"mimeType\":\"image/png\"",
					"\"blob\":\"iVBORw0KGgo");
			case "resources-templates-read" -> resourceRead(simulator, prefix,
					"test://template/record-42/data", "templateTest", "Data for ID: record-42",
					"\"mimeType\":\"application/json\"");
			case "sep-2164-resource-not-found" ->
					resourceNotFound(simulator, prefix);
			case "prompts-list" -> promptsList(simulator, prefix);
			case "prompts-get-simple" -> promptGet(simulator, prefix,
					"test_simple_prompt", "{}", "This is a simple prompt for testing.");
			case "prompts-get-with-args" -> promptGet(simulator, prefix,
					"test_prompt_with_arguments",
					"{\"arg1\":\"first\",\"arg2\":\"second\"}",
					"Prompt with arguments: arg1='first', arg2='second'");
			case "prompts-get-embedded-resource" -> promptGet(simulator, prefix,
					"test_prompt_with_embedded_resource",
					"{\"resourceUri\":\"test://prompt/resource\"}",
					"test://prompt/resource", "Embedded resource content for testing.",
					"Please process the embedded resource above.");
			case "prompts-get-with-image" -> promptGet(simulator, prefix,
					"test_prompt_with_image", "{}", "\"type\":\"image\"",
					"\"mimeType\":\"image/png\"", "Please analyze the image above.");
			case "dns-rebinding-protection" -> dnsProtection(simulator, prefix);
			case "caching" -> caching(simulator, prefix);
			case "http-header-validation" -> headerValidation(simulator, prefix);
			case "http-custom-header-server-validation" ->
					customHeaderValidation(simulator, prefix);
			case "input-required-result-basic-elicitation" ->
					basicElicitation(simulator, prefix);
			case "input-required-result-basic-sampling" ->
					basicSampling(simulator, prefix);
			case "input-required-result-basic-list-roots" ->
					basicRoots(simulator, prefix);
			case "input-required-result-request-state" ->
					requestState(simulator, prefix);
			case "input-required-result-multiple-input-requests" ->
					multipleInputs(simulator, prefix);
			case "input-required-result-multi-round" ->
					multiRound(simulator, prefix);
			case "input-required-result-missing-input-response" ->
					missingInput(simulator, prefix);
			case "input-required-result-non-tool-request" ->
					nonToolInput(simulator, prefix);
			case "input-required-result-result-type" ->
					resultType(simulator, prefix);
			case "input-required-result-unsupported-methods" ->
					unsupportedInputMethods(simulator, prefix);
			case "input-required-result-tampered-state" ->
					tamperedState(simulator, prefix);
			case "input-required-result-capability-check" ->
					capabilityCheck(simulator, prefix);
			case "input-required-result-ignore-extra-params" ->
					ignoreExtraResponses(simulator, prefix);
			case "input-required-result-validate-input" ->
					validateInput(simulator, prefix);
			default -> throw new AssertionError("Unmapped local scenario: " + row.name());
		}
	}

	private static void serverStateless(Simulator simulator, McpServer server,
			String id) {
		JsonExchange discover = json(simulator, request(id + "-discover",
				"server/discover", null, "", EMPTY_CAPABILITIES, "", LOOPBACK + ":0",
				null, Map.of()));
		assertSuccess(discover, id + "-discover", "\"supportedVersions\":[\""
				+ PROTOCOL_VERSION + "\"]", "\"capabilities\":{", "\"tools\":",
				"\"prompts\":", "\"resources\":", "soklet-public-conformance");
		JsonExchange missingCapability = json(simulator, toolRequest(
				id + "-missing-capability", "test_missing_capability", "{}",
				EMPTY_CAPABILITIES, "", Map.of()));
		assertError(missingCapability, 400, -32021,
				id + "-missing-capability");
		JsonExchange elicitation = json(simulator, toolRequest(
				id + "-streaming-elicitation", "test_streaming_elicitation", "{}",
				FORM_CAPABILITY, "", Map.of()));
		assertInputRequired(elicitation, id + "-streaming-elicitation",
				"\"conformance-value\":{",
				"\"method\":\"elicitation/create\"",
				"Provide a conformance value");
		JsonExchange logging = json(simulator, toolRequest(id + "-logging",
				"test_logging_tool", "{}", EMPTY_CAPABILITIES, "", Map.of()));
		assertComplete(logging, id + "-logging",
				"No log notification was emitted.");

		String subscriptionId = id + "-subscription";
		McpSimulation subscription = simulator.startMcpRequest(request(subscriptionId,
				"subscriptions/listen", null,
				",\"notifications\":{\"resourcesListChanged\":true}",
				EMPTY_CAPABILITIES, "", LOOPBACK + ":0", null, Map.of()));
		try {
			McpSimulationResponse response = awaitResponse(subscription);
			assertEquals(200, response.getStatusCode(), "Subscription status");
			assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					response.getBodyMode(), "Subscription body mode");
			assertTrue(response.getBody().isEmpty(), "SSE body must be absent");
			McpSimulationStreamItem acknowledgment = nextItem(subscription);
			String frame = frame(acknowledgment);
			assertContains(frame, "notifications/subscriptions/acknowledged",
					"\"io.modelcontextprotocol/subscriptionId\":\""
							+ subscriptionId + "\"", "resourcesListChanged");
			assertStopped(server);
			subscription.cancel();
			McpSimulationCompletion completion = awaitCompletion(subscription);
			assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
					completion.getReason(), "Subscription cancel reason");
			assertTrue(completion.getTerminalMessage().isEmpty(),
					"Canceled subscription must not fabricate a terminal message");
			assertTrue(completion.getThrowables().isEmpty(),
					"Canceled subscription must not fabricate failures");
			assertTrue(pollItem(subscription).isEmpty(),
					"Subscription queue must drain exactly");
		} finally {
			subscription.close();
		}
	}

	private static void toolsList(Simulator simulator, String id) {
		JsonExchange exchange = json(simulator, request(id, "tools/list", null, "",
				EMPTY_CAPABILITIES, "", LOOPBACK + ":0", null, Map.of()));
		assertSuccess(exchange, id, "\"tools\":[", "test_simple_text",
				"test_image_content", "test_audio_content", "test_embedded_resource",
				"test_multiple_content_types", "test_error_handling",
				"test_tool_with_progress", "json_schema_2020_12_tool",
				"test_custom_header", "\"resultType\":\"complete\"");
	}

	private static void toolCall(Simulator simulator, String id, String tool,
			String arguments, String... fragments) {
		JsonExchange exchange = json(simulator, toolRequest(id, tool, arguments,
				EMPTY_CAPABILITIES, "", Map.of()));
		assertSuccess(exchange, id, fragments);
		assertContains(exchange.body(), "\"resultType\":\"complete\"");
	}

	private static void mixedToolCall(Simulator simulator, String id) {
		JsonExchange exchange = json(simulator, toolRequest(id,
				"test_multiple_content_types", "{}", EMPTY_CAPABILITIES, "", Map.of()));
		assertSuccess(exchange, id, "Multiple content types test:",
				"\"type\":\"image\"", "test://mixed-content-resource",
				"{\\\"test\\\":\\\"data\\\",\\\"value\\\":123}");
		int text = exchange.body().indexOf("Multiple content types test:");
		int image = exchange.body().indexOf("\"type\":\"image\"");
		int resource = exchange.body().indexOf("test://mixed-content-resource");
		assertTrue(text >= 0 && text < image && image < resource,
				"Mixed content order changed");
	}

	private static void progressCall(Simulator simulator, String id, String token) {
		McpSimulation simulation = startProgress(simulator, id, token);
		try {
			drainProgress(simulation, id, token, null);
		} finally {
			simulation.close();
		}
	}

	private static McpSimulation startProgress(Simulator simulator, String id,
			String token) {
		return simulator.startMcpRequest(request(id, "tools/call",
				"test_tool_with_progress",
				",\"name\":\"test_tool_with_progress\",\"arguments\":{}",
				EMPTY_CAPABILITIES, ",\"progressToken\":\"" + token + "\"",
				LOOPBACK + ":0", null, Map.of()));
	}

	private static String drainProgress(McpSimulation simulation, String id,
			String token, String forbiddenToken) {
		McpSimulationResponse response = awaitResponse(simulation);
		assertEquals(200, response.getStatusCode(), "Progress status");
		assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
				response.getBodyMode(), "Progress body mode");
		assertTrue(response.getBody().isEmpty(), "Progress SSE body must be absent");
		StringBuilder transcript = new StringBuilder();
		for (String progress : List.of("0", "50", "100")) {
			String encoded = frame(nextItem(simulation));
			assertContains(encoded, "\"method\":\"notifications/progress\"",
					"\"progressToken\":\"" + token + "\"",
					"\"progress\":" + progress, "\"total\":100");
			if (forbiddenToken != null)
				assertNotContains(encoded, forbiddenToken);
			transcript.append(encoded);
		}
		String terminal = frame(nextItem(simulation));
		assertContains(terminal, "\"id\":\"" + id + "\"",
				"Progress test completed.", "\"resultType\":\"complete\"");
		if (forbiddenToken != null)
			assertNotContains(terminal, forbiddenToken);
		transcript.append(terminal);
		McpSimulationCompletion completion = awaitCompletion(simulation);
		assertEquals(McpStreamTerminationReason.COMPLETED, completion.getReason(),
				"Progress completion reason");
		assertTrue(completion.getTerminalMessage().isPresent(),
				"Progress terminal message must be repeated in completion");
		assertTrue(completion.getThrowables().isEmpty(),
				"Progress completion must not expose failures");
		assertTrue(pollItem(simulation).isEmpty(), "Progress stream must drain");
		return transcript.toString();
	}

	private static void schemaScenario(Simulator simulator, String id) {
		String validArguments = "{\"name\":\"Alice\",\"address\":{"
				+ "\"street\":\"Main\",\"city\":\"New York\"},"
				+ "\"contactMethod\":\"email\","
				+ "\"email\":\"alice@example.test\"}";
		JsonExchange valid = json(simulator, toolRequest(id + "-valid",
				"json_schema_2020_12_tool", validArguments, EMPTY_CAPABILITIES, "",
				Map.of()));
		assertSuccess(valid, id + "-valid", "Schema input accepted.",
				"\"resultType\":\"complete\"");
		JsonExchange invalid = json(simulator, toolRequest(id + "-invalid",
				"json_schema_2020_12_tool", "{}", EMPTY_CAPABILITIES, "", Map.of()));
		assertError(invalid, 400, -32602, id + "-invalid");
	}

	private static void multipleProgressStreams(Simulator simulator, String id) {
		String firstToken = id + "-first-token";
		String secondToken = id + "-second-token";
		McpSimulation first = startProgress(simulator, id + "-first", firstToken);
		McpSimulation second = startProgress(simulator, id + "-second", secondToken);
		try {
			String firstTranscript = drainProgress(first, id + "-first", firstToken,
					secondToken);
			String secondTranscript = drainProgress(second, id + "-second", secondToken,
					firstToken);
			assertNotContains(firstTranscript, id + "-second");
			assertNotContains(secondTranscript, id + "-first");
		} finally {
			first.close();
			second.close();
		}
	}

	private static void resourcesList(Simulator simulator, String id) {
		JsonExchange exchange = json(simulator, request(id, "resources/list", null,
				"", EMPTY_CAPABILITIES, "", LOOPBACK + ":0", null, Map.of()));
		assertSuccess(exchange, id, "\"resources\":[", "test://static-text",
				"Static text resource", "test://static-binary",
				"Static binary resource", "\"ttlMs\":300000",
				"\"cacheScope\":\"public\"");
	}

	private static void resourceRead(Simulator simulator, String id, String uri,
			String... fragments) {
		JsonExchange exchange = json(simulator, request(id, "resources/read", uri,
				",\"uri\":\"" + uri + "\"", EMPTY_CAPABILITIES, "",
				LOOPBACK + ":0", null, Map.of()));
		assertSuccess(exchange, id, fragments);
		assertContains(exchange.body(), "\"uri\":\"" + uri + "\"",
				"\"ttlMs\":300000", "\"cacheScope\":\"public\"",
				"\"resultType\":\"complete\"");
	}

	private static void resourceNotFound(Simulator simulator, String id) {
		String uri = "test://missing/resource";
		JsonExchange exchange = json(simulator, request(id, "resources/read", uri,
				",\"uri\":\"" + uri + "\"", EMPTY_CAPABILITIES, "",
				LOOPBACK + ":0", null, Map.of()));
		assertError(exchange, 400, -32602, id);
	}

	private static void promptsList(Simulator simulator, String id) {
		JsonExchange exchange = json(simulator, request(id, "prompts/list", null, "",
				EMPTY_CAPABILITIES, "", LOOPBACK + ":0", null, Map.of()));
		assertSuccess(exchange, id, "\"prompts\":[", "test_simple_prompt",
				"test_prompt_with_arguments", "test_prompt_with_embedded_resource",
				"test_prompt_with_image", "\"resultType\":\"complete\"");
	}

	private static void promptGet(Simulator simulator, String id, String prompt,
			String arguments, String... fragments) {
		JsonExchange exchange = json(simulator, promptRequest(id, prompt, arguments,
				EMPTY_CAPABILITIES, ""));
		assertSuccess(exchange, id, fragments);
		assertContains(exchange.body(), "\"messages\":[",
				"\"resultType\":\"complete\"");
	}

	private static void dnsProtection(Simulator simulator, String id) {
		FixedExchange missingHost = fixed(simulator, request(id + "-missing-host",
				"server/discover", null, "", EMPTY_CAPABILITIES, "", null, null,
				Map.of()));
		assertEquals(421, missingHost.status(), "Missing Host status");
		FixedExchange wrongHost = fixed(simulator, request(id + "-wrong-host",
				"server/discover", null, "", EMPTY_CAPABILITIES, "",
				"attacker.example:0", null, Map.of()));
		assertEquals(421, wrongHost.status(), "Wrong Host status");
		FixedExchange wrongOrigin = fixed(simulator, request(id + "-wrong-origin",
				"server/discover", null, "", EMPTY_CAPABILITIES, "", LOOPBACK + ":0",
				"https://attacker.example", Map.of()));
		assertEquals(403, wrongOrigin.status(), "Wrong Origin status");
		JsonExchange accepted = json(simulator, request(id + "-accepted",
				"server/discover", null, "", EMPTY_CAPABILITIES, "", LOOPBACK + ":0",
				"http://" + LOOPBACK + ":0", Map.of()));
		assertSuccess(accepted, id + "-accepted", "\"supportedVersions\":[");
		assertEquals(Set.of("http://" + LOOPBACK + ":0"),
				header(accepted.headers(), "Access-Control-Allow-Origin"),
				"Accepted Origin response header");
	}

	private static void caching(Simulator simulator, String id) {
		JsonExchange discover = json(simulator, request(id + "-discover",
				"server/discover", null, "", EMPTY_CAPABILITIES, "", LOOPBACK + ":0",
				null, Map.of()));
		assertContains(discover.body(), "\"ttlMs\":0",
				"\"cacheScope\":\"private\"");
		JsonExchange resources = json(simulator, request(id + "-resources",
				"resources/list", null, "", EMPTY_CAPABILITIES, "", LOOPBACK + ":0",
				null, Map.of()));
		JsonExchange templates = json(simulator, request(id + "-templates",
				"resources/templates/list", null, "", EMPTY_CAPABILITIES, "",
				LOOPBACK + ":0", null, Map.of()));
		JsonExchange read = json(simulator, request(id + "-read", "resources/read",
				"test://static-text", ",\"uri\":\"test://static-text\"",
				EMPTY_CAPABILITIES, "", LOOPBACK + ":0", null, Map.of()));
		for (JsonExchange exchange : List.of(resources, templates, read))
			assertContains(exchange.body(), "\"ttlMs\":300000",
					"\"cacheScope\":\"public\"");
	}

	private static void headerValidation(Simulator simulator, String id) {
		JsonExchange valid = json(simulator, request(id + "-valid", "tools/list",
				null, "", EMPTY_CAPABILITIES, "", LOOPBACK + ":0", null, Map.of()));
		assertSuccess(valid, id + "-valid", "\"tools\":[");
		JsonExchange mismatch = json(simulator, request(id + "-mismatch",
				"tools/list", null, "", EMPTY_CAPABILITIES, "", LOOPBACK + ":0", null,
				Map.of("Mcp-Method", Set.of("prompts/list"))));
		assertError(mismatch, 400, -32020, id + "-mismatch");
	}

	private static void customHeaderValidation(Simulator simulator, String id) {
		JsonExchange valid = json(simulator, toolRequest(id + "-valid",
				"test_custom_header", "{\"value\":\"header-value\"}",
				EMPTY_CAPABILITIES, "",
				Map.of("Mcp-Param-Value", Set.of("header-value"))));
		assertSuccess(valid, id + "-valid", "Custom header accepted.");
		JsonExchange mismatch = json(simulator, toolRequest(id + "-mismatch",
				"test_custom_header", "{\"value\":\"body-value\"}",
				EMPTY_CAPABILITIES, "",
				Map.of("Mcp-Param-Value", Set.of("header-value"))));
		assertError(mismatch, 400, -32020, id + "-mismatch");
	}

	private static void basicElicitation(Simulator simulator, String id) {
		String tool = "test_input_required_result_elicitation";
		JsonExchange initial = json(simulator, toolRequest(id + "-initial", tool,
				"{}", FORM_CAPABILITY, "", Map.of()));
		assertInputRequired(initial, id + "-initial", "\"user_name\":{",
				"\"method\":\"elicitation/create\"", "What is your name?");
		JsonExchange complete = json(simulator, toolRequest(id + "-complete", tool,
				"{}", FORM_CAPABILITY,
				",\"inputResponses\":{\"user_name\":" + FORM_NAME_RESPONSE + "}",
				Map.of()));
		assertComplete(complete, id + "-complete", "Hello, Alice!");
	}

	private static void basicSampling(Simulator simulator, String id) {
		String tool = "test_input_required_result_sampling";
		JsonExchange initial = json(simulator, toolRequest(id + "-initial", tool,
				"{}", SAMPLING_CAPABILITY, "", Map.of()));
		assertInputRequired(initial, id + "-initial", "\"capital_question\":{",
				"\"method\":\"sampling/createMessage\"", "capital of France",
				"\"maxTokens\":100");
		JsonExchange complete = json(simulator, toolRequest(id + "-complete", tool,
				"{}", SAMPLING_CAPABILITY,
				",\"inputResponses\":{\"capital_question\":"
						+ SAMPLING_RESPONSE + "}", Map.of()));
		assertComplete(complete, id + "-complete",
				"The capital of France is Paris.");
	}

	private static void basicRoots(Simulator simulator, String id) {
		String tool = "test_input_required_result_list_roots";
		JsonExchange initial = json(simulator, toolRequest(id + "-initial", tool,
				"{}", ROOTS_CAPABILITY, "", Map.of()));
		assertInputRequired(initial, id + "-initial", "\"client_roots\":{",
				"\"method\":\"roots/list\"");
		JsonExchange complete = json(simulator, toolRequest(id + "-complete", tool,
				"{}", ROOTS_CAPABILITY,
				",\"inputResponses\":{\"client_roots\":" + ROOTS_RESPONSE + "}",
				Map.of()));
		assertComplete(complete, id + "-complete",
				"Client root file:///test/root accepted.");
	}

	private static void requestState(Simulator simulator, String id) {
		String tool = "test_input_required_result_request_state";
		JsonExchange initial = json(simulator, toolRequest(id + "-initial", tool,
				"{}", FORM_CAPABILITY, "", Map.of()));
		assertInputRequired(initial, id + "-initial", "\"confirm\":{",
				"\"method\":\"elicitation/create\"");
		String state = extractState(initial.body());
		JsonExchange complete = json(simulator, toolRequest(id + "-complete", tool,
				"{}", FORM_CAPABILITY,
				",\"inputResponses\":{\"confirm\":" + FORM_CONFIRM_RESPONSE + "}"
						+ ",\"requestState\":\"" + state + "\"", Map.of()));
		assertComplete(complete, id + "-complete", "state-ok");
	}

	private static void multipleInputs(Simulator simulator, String id) {
		String tool = "test_input_required_result_multiple_inputs";
		JsonExchange initial = json(simulator, toolRequest(id + "-initial", tool,
				"{}", ALL_INPUT_CAPABILITIES, "", Map.of()));
		assertInputRequired(initial, id + "-initial", "\"user_name\":{",
				"\"greeting\":{", "\"client_roots\":{",
				"\"method\":\"elicitation/create\"",
				"\"method\":\"sampling/createMessage\"",
				"\"method\":\"roots/list\"");
		String state = extractState(initial.body());
		String responses = "{\"user_name\":" + FORM_NAME_RESPONSE
				+ ",\"greeting\":" + SAMPLING_RESPONSE
				+ ",\"client_roots\":" + ROOTS_RESPONSE + "}";
		JsonExchange complete = json(simulator, toolRequest(id + "-complete", tool,
				"{}", ALL_INPUT_CAPABILITIES,
				",\"inputResponses\":" + responses + ",\"requestState\":\""
						+ state + "\"", Map.of()));
		assertComplete(complete, id + "-complete", "All input responses accepted.");
	}

	private static void multiRound(Simulator simulator, String id) {
		String tool = "test_input_required_result_multi_round";
		JsonExchange first = json(simulator, toolRequest(id + "-round-1", tool,
				"{}", FORM_CAPABILITY, "", Map.of()));
		assertInputRequired(first, id + "-round-1", "\"step1\":{", "Step 1:");
		String firstState = extractState(first.body());
		JsonExchange second = json(simulator, toolRequest(id + "-round-2", tool,
				"{}", FORM_CAPABILITY,
				",\"inputResponses\":{\"step1\":" + FORM_NAME_RESPONSE + "}"
						+ ",\"requestState\":\"" + firstState + "\"", Map.of()));
		assertInputRequired(second, id + "-round-2", "\"step2\":{", "Step 2:");
		assertNotContains(second.body(), "\"step1\":{");
		String secondState = extractState(second.body());
		assertTrue(!firstState.equals(secondState),
				"Protected state must advance between rounds");
		JsonExchange complete = json(simulator, toolRequest(id + "-complete", tool,
				"{}", FORM_CAPABILITY,
				",\"inputResponses\":{\"step2\":" + FORM_COLOR_RESPONSE + "}"
						+ ",\"requestState\":\"" + secondState + "\"", Map.of()));
		assertComplete(complete, id + "-complete", "Multi-round input complete.");
	}

	private static void missingInput(Simulator simulator, String id) {
		JsonExchange exchange = json(simulator, toolRequest(id,
				"test_input_required_result_elicitation", "{}", FORM_CAPABILITY,
				",\"inputResponses\":{\"wrong_key\":{\"action\":\"decline\"}}",
				Map.of()));
		assertInputRequired(exchange, id, "\"user_name\":{",
				"\"method\":\"elicitation/create\"");
		assertNotContains(exchange.body(), "wrong_key");
	}

	private static void nonToolInput(Simulator simulator, String id) {
		String prompt = "test_input_required_result_prompt";
		JsonExchange initial = json(simulator, promptRequest(id + "-initial", prompt,
				"{}", FORM_CAPABILITY, ""));
		assertInputRequired(initial, id + "-initial", "\"user_context\":{",
				"\"method\":\"elicitation/create\"");
		JsonExchange complete = json(simulator, promptRequest(id + "-complete", prompt,
				"{}", FORM_CAPABILITY,
				",\"inputResponses\":{\"user_context\":"
						+ FORM_CONTEXT_RESPONSE + "}"));
		assertComplete(complete, id + "-complete", "Prompt using test context.");
	}

	private static void resultType(Simulator simulator, String id) {
		JsonExchange exchange = json(simulator, toolRequest(id,
				"test_input_required_result_elicitation", "{}", FORM_CAPABILITY, "",
				Map.of()));
		assertInputRequired(exchange, id, "\"inputRequests\":{\"user_name\":{",
				"\"resultType\":\"input_required\"");
	}

	private static void unsupportedInputMethods(Simulator simulator, String id) {
		JsonExchange tools = json(simulator, request(id + "-tools", "tools/list",
				null, "", EMPTY_CAPABILITIES, "", LOOPBACK + ":0", null, Map.of()));
		JsonExchange prompts = json(simulator, request(id + "-prompts",
				"prompts/list", null, "", EMPTY_CAPABILITIES, "", LOOPBACK + ":0",
				null, Map.of()));
		for (JsonExchange exchange : List.of(tools, prompts)) {
			assertContains(exchange.body(), "\"resultType\":\"complete\"");
			assertNotContains(exchange.body(), "input_required");
			assertNotContains(exchange.body(), "inputRequests");
		}
	}

	private static void tamperedState(Simulator simulator, String id) {
		String tool = "test_input_required_result_tampered_state";
		JsonExchange initial = json(simulator, toolRequest(id + "-initial", tool,
				"{}", FORM_CAPABILITY, "", Map.of()));
		assertInputRequired(initial, id + "-initial", "\"confirm\":{");
		String state = extractState(initial.body());
		JsonExchange tampered = json(simulator, toolRequest(id + "-tampered", tool,
				"{}", FORM_CAPABILITY,
				",\"inputResponses\":{\"confirm\":" + FORM_CONFIRM_RESPONSE + "}"
						+ ",\"requestState\":\"" + state + "-TAMPERED\"", Map.of()));
		assertError(tampered, 400, -32602, id + "-tampered");
		assertNotContains(tampered.body(), "Protected state accepted.");
	}

	private static void capabilityCheck(Simulator simulator, String id) {
		JsonExchange exchange = json(simulator, toolRequest(id,
				"test_input_required_result_capabilities", "{}", SAMPLING_CAPABILITY,
				"", Map.of()));
		assertInputRequired(exchange, id, "\"sampling\":{",
				"\"method\":\"sampling/createMessage\"");
		assertNotContains(exchange.body(), "elicitation/create");
	}

	private static void ignoreExtraResponses(Simulator simulator, String id) {
		String responses = "{\"user_name\":" + FORM_NAME_RESPONSE
				+ ",\"unknown_extra_key\":{\"roots\":[]}}";
		JsonExchange exchange = json(simulator, toolRequest(id,
				"test_input_required_result_elicitation", "{}", ALL_INPUT_CAPABILITIES,
				",\"inputResponses\":" + responses, Map.of()));
		assertComplete(exchange, id, "Hello, Alice!");
		assertNotContains(exchange.body(), "input_required");
	}

	private static void validateInput(Simulator simulator, String id) {
		String tool = "test_input_required_result_elicitation";
		JsonExchange initial = json(simulator, toolRequest(id + "-initial", tool,
				"{}", FORM_CAPABILITY, "", Map.of()));
		assertInputRequired(initial, id + "-initial", "\"user_name\":{");
		for (String invalid : List.of("42", "null")) {
			String suffix = invalid.equals("42") ? "-number" : "-null";
			JsonExchange exchange = json(simulator, toolRequest(id + suffix, tool, "{}",
					FORM_CAPABILITY,
					",\"inputResponses\":{\"user_name\":" + invalid + "}", Map.of()));
			assertError(exchange, 400, -32602, id + suffix);
			assertNotContains(exchange.body(), "Hello, Alice!");
		}
	}

	private static Request toolRequest(String id, String tool, String arguments,
			String capabilities, String trailingParameters,
			Map<String, Set<String>> extraHeaders) {
		return request(id, "tools/call", tool,
				",\"name\":\"" + tool + "\",\"arguments\":" + arguments
						+ trailingParameters,
				capabilities, "", LOOPBACK + ":0", null, extraHeaders);
	}

	private static Request promptRequest(String id, String prompt,
			String arguments, String capabilities, String trailingParameters) {
		return request(id, "prompts/get", prompt,
				",\"name\":\"" + prompt + "\",\"arguments\":" + arguments
						+ trailingParameters,
				capabilities, "", LOOPBACK + ":0", null, Map.of());
	}

	private static Request request(String id, String method, String operationName,
			String parameterSuffix, String capabilities, String metaSuffix,
			String host, String origin, Map<String, Set<String>> extraHeaders) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":" + capabilities
				+ metaSuffix + "}" + parameterSuffix + "}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		if (host != null)
			headers.put("Host", Set.of(host));
		headers.put("Content-Type", Set.of(JSON_MEDIA_TYPE + "; charset=UTF-8"));
		headers.put("Accept", Set.of(JSON_MEDIA_TYPE + ", text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));
		if (operationName != null)
			headers.put("Mcp-Name", Set.of(operationName));
		if (origin != null)
			headers.put("Origin", Set.of(origin));
		headers.putAll(extraHeaders);
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static FixedExchange fixed(Simulator simulator, Request request) {
		McpSimulation simulation = simulator.startMcpRequest(request);
		try {
			McpSimulationResponse response = awaitResponse(simulation);
			Optional<byte[]> bodyBytes = response.getBody();
			String body = bodyBytes
					.map(bytes -> new String(bytes, StandardCharsets.UTF_8))
					.orElse("");
			McpSimulationCompletion completion = awaitCompletion(simulation);
			assertEquals(McpStreamTerminationReason.COMPLETED, completion.getReason(),
					"Fixed response completion reason");
			assertTrue(completion.getTerminalMessage().isEmpty(),
					"Fixed responses must not expose stream terminal messages");
			assertTrue(completion.getThrowables().isEmpty(),
					"Fixed responses must not expose failures");
			assertTrue(pollItem(simulation).isEmpty(),
					"Fixed responses must not expose stream items");
			return new FixedExchange(response.getStatusCode(), response.getBodyMode(),
					bodyBytes.isPresent(), body, response.getHeaders());
		} finally {
			simulation.close();
		}
	}

	private static JsonExchange json(Simulator simulator, Request request) {
		FixedExchange exchange = fixed(simulator, request);
		assertEquals(McpSimulationBodyMode.JSON, exchange.bodyMode(),
				"Expected JSON response body mode");
		assertTrue(exchange.bodyPresent(), "Expected captured JSON response body");
		assertEquals(Set.of("no-store"), header(exchange.headers(), "Cache-Control"),
				"JSON Cache-Control header");
		assertEquals(Set.of(JSON_MEDIA_TYPE),
				header(exchange.headers(), "Content-Type"),
				"JSON Content-Type header");
		return new JsonExchange(exchange.status(), exchange.body(),
				exchange.headers());
	}

	private static void assertSuccess(JsonExchange exchange, String id,
			String... fragments) {
		assertEquals(200, exchange.status(), "Successful response status");
		assertContains(exchange.body(), "\"jsonrpc\":\"2.0\"",
				"\"id\":\"" + id + "\"", "\"result\":{");
		assertContains(exchange.body(), fragments);
	}

	private static void assertComplete(JsonExchange exchange, String id,
			String... fragments) {
		assertSuccess(exchange, id, fragments);
		assertContains(exchange.body(), "\"resultType\":\"complete\"");
		assertNotContains(exchange.body(), "\"resultType\":\"input_required\"");
	}

	private static void assertInputRequired(JsonExchange exchange, String id,
			String... fragments) {
		assertSuccess(exchange, id, fragments);
		assertContains(exchange.body(), "\"resultType\":\"input_required\"",
				"\"inputRequests\":{");
	}

	private static void assertError(JsonExchange exchange, int status, int code,
			String id) {
		assertEquals(status, exchange.status(), "JSON-RPC error HTTP status");
		assertContains(exchange.body(), "\"jsonrpc\":\"2.0\"",
				"\"id\":\"" + id + "\"", "\"error\":{",
				"\"code\":" + code);
		assertNotContains(exchange.body(), "\"result\":{");
	}

	private static String extractState(String body) {
		String marker = "\"requestState\":\"";
		int start = body.indexOf(marker);
		assertTrue(start >= 0, "Expected protected request state");
		start += marker.length();
		int end = body.indexOf('"', start);
		assertTrue(end > start, "Expected nonempty protected request state");
		String state = body.substring(start, end);
		assertTrue(state.startsWith("soklet-mcp-request-state-v1."),
				"Unexpected protected request-state envelope");
		return state;
	}

	private static McpSimulationResponse awaitResponse(McpSimulation simulation) {
		try {
			return simulation.awaitResponse(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator response."));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted awaiting simulator response.",
					exception);
		}
	}

	private static McpSimulationStreamItem nextItem(McpSimulation simulation) {
		try {
			return simulation.nextStreamItem(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator stream item."));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted awaiting simulator stream item.",
					exception);
		}
	}

	private static Optional<McpSimulationStreamItem> pollItem(
			McpSimulation simulation) {
		try {
			return simulation.nextStreamItem(Duration.ZERO);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted polling simulator stream item.",
					exception);
		}
	}

	private static McpSimulationCompletion awaitCompletion(
			McpSimulation simulation) {
		try {
			return simulation.awaitCompletion(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator completion."));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted awaiting simulator completion.",
					exception);
		}
	}

	private static String frame(McpSimulationStreamItem item) {
		assertEquals(McpSimulationStreamItemType.JSON_MESSAGE, item.getType(),
				"Expected JSON SSE item");
		assertTrue(item.getMessage().isPresent(), "JSON SSE item lacks message");
		assertTrue(item.getComment().isEmpty(), "JSON SSE item exposed comment");
		String frame = new String(item.getEncodedBytes(), StandardCharsets.UTF_8);
		assertTrue(frame.startsWith("data: ") && frame.endsWith("\n\n"),
				"Unexpected unchunked SSE framing");
		return frame;
	}

	private static Set<String> header(Map<String, Set<String>> headers,
			String name) {
		for (Map.Entry<String, Set<String>> entry : headers.entrySet())
			if (entry.getKey().equalsIgnoreCase(name))
				return entry.getValue();
		return Set.of();
	}

	private static void assertStopped(McpServer server) {
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		assertEquals(McpServerStatus.STOPPED, diagnostics.getStatus(),
				"Simulator must not start the listener");
		assertTrue(diagnostics.getBoundAddress().isEmpty(),
				"Simulator must not expose a bound address");
		assertEquals(0, diagnostics.getActiveHandlerExecutions(),
				"Simulator handler diagnostics must remain hidden");
		assertEquals(0, diagnostics.getQueuedRequests(),
				"Simulator queue diagnostics must remain hidden");
		assertEquals(0, diagnostics.getActiveRequestStreams(),
				"Simulator stream diagnostics must remain hidden");
		assertEquals(0, diagnostics.getActiveSubscriptions(),
				"Simulator subscription diagnostics must remain hidden");
		assertTrue(!server.isStarted(), "Simulator must not mark server started");
	}

	private static void assertContains(String value, String... fragments) {
		for (String fragment : fragments)
			assertTrue(value.contains(fragment),
					"Expected fragment <" + fragment + "> in response");
	}

	private static void assertNotContains(String value, String fragment) {
		assertTrue(!value.contains(fragment),
				"Unexpected fragment <" + fragment + "> in response");
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition)
			throw new AssertionError(message);
	}

	private static void assertEquals(Object expected, Object actual,
			String message) {
		if (!expected.equals(actual))
			throw new AssertionError(message + ": expected <" + expected
					+ "> but was <" + actual + ">");
	}

	private static String passLine(ScenarioRow row) {
		return "PASS\t" + row.ordinal() + '\t' + row.name() + '\n';
	}

	private record ScenarioRow(int ordinal, String name) {
	}

	private record FixedExchange(int status, McpSimulationBodyMode bodyMode,
			boolean bodyPresent, String body, Map<String, Set<String>> headers) {
	}

	private record JsonExchange(int status, String body,
			Map<String, Set<String>> headers) {
	}

	private static final class RecordingLifecycle implements LifecycleObserver {
		private final AtomicInteger serverCallbacks = new AtomicInteger();

		@Override
		public void willStartMcpServer(McpServer server) {
			this.serverCallbacks.incrementAndGet();
		}

		@Override
		public void didStartMcpServer(McpServer server) {
			this.serverCallbacks.incrementAndGet();
		}

		@Override
		public void willStopMcpServer(McpServer server) {
			this.serverCallbacks.incrementAndGet();
		}

		@Override
		public void didStopMcpServer(McpServer server, McpShutdownOutcome outcome) {
			this.serverCallbacks.incrementAndGet();
		}

		private void assertNoServerLifecycle() {
			assertEquals(0, this.serverCallbacks.get(),
					"Simulator emitted server lifecycle callbacks");
		}
	}

	private static final class RecordingMetrics implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		private final CountDownLatch semanticTerminal;
		private final AtomicInteger terminalEvents = new AtomicInteger();
		private final int expectedTerminalEvents;

		private RecordingMetrics(int expectedTerminalEvents) {
			this.expectedTerminalEvents = expectedTerminalEvents;
			this.semanticTerminal = new CountDownLatch(expectedTerminalEvents);
		}

		@Override
		public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
			this.events.add(event);
			if (event instanceof McpMetricsEvent.RequestFinished
					|| event instanceof McpMetricsEvent.RequestRejected) {
				this.terminalEvents.incrementAndGet();
				this.semanticTerminal.countDown();
			}
		}

		private void awaitSemanticTerminal() {
			try {
				assertTrue(this.semanticTerminal.await(WAIT.toMillis(),
						TimeUnit.MILLISECONDS),
						"Simulator semantic metrics did not reach a terminal event");
				assertEquals(this.expectedTerminalEvents, this.terminalEvents.get(),
						"Unexpected simulator semantic-terminal count");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(
						"Interrupted awaiting simulator semantic metrics.", exception);
			}
		}

		private void assertNoListenerOrTransportEvents() {
			for (McpMetricsEvent event : this.events)
				assertTrue(!(event instanceof McpMetricsEvent.ServerStarted)
						&& !(event instanceof McpMetricsEvent.ServerStopped)
						&& !(event instanceof McpMetricsEvent.ConnectionAccepted)
						&& !(event instanceof McpMetricsEvent.ConnectionRejected)
						&& !(event instanceof McpMetricsEvent.TransportFailure),
						"Simulator emitted listener/connection/transport event "
								+ event.getClass().getSimpleName());
		}
	}
}
