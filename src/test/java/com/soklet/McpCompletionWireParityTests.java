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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exact Completion envelopes agree on the listener and off-network simulator. */
public class McpCompletionWireParityTests {
	private static final String HOST = "127.0.0.1";
	private static final String TEMPLATE = "wire://records/{value}";
	private static final String VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(5))
			.startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	private static final List<String> HUNDRED_VALUES = hundredValues();
	@Test
	@Timeout(120)
	public void empty() throws Exception {
		verifyWireParity(true, "empty");
		verifyWireParity(false, "empty");
	}

	@Test
	@Timeout(120)
	public void hundred() throws Exception {
		verifyWireParity(true, "hundred");
		verifyWireParity(false, "hundred");
	}

	@Test
	@Timeout(120)
	public void totalOnly() throws Exception {
		verifyWireParity(true, "total-only");
		verifyWireParity(false, "total-only");
	}

	@Test
	@Timeout(120)
	public void moreOnly() throws Exception {
		verifyWireParity(true, "more-only");
		verifyWireParity(false, "more-only");
	}

	@Test
	@Timeout(120)
	public void both() throws Exception {
		verifyWireParity(true, "both");
		verifyWireParity(false, "both");
	}

	@Test
	@Timeout(120)
	public void maxTotal() throws Exception {
		verifyWireParity(true, "max-total");
		verifyWireParity(false, "max-total");
	}

	@Test
	@Timeout(120)
	public void tooMany() throws Exception {
		verifyWireParity(true, "too-many");
		verifyWireParity(false, "too-many");
	}

	@Test
	@Timeout(120)
	public void negativeTotal() throws Exception {
		verifyWireParity(true, "negative-total");
		verifyWireParity(false, "negative-total");
	}

	@Test
	@Timeout(120)
	public void unsafeTotal() throws Exception {
		verifyWireParity(true, "unsafe-total");
		verifyWireParity(false, "unsafe-total");
	}

	@Test
	@Timeout(120)
	public void belowSize() throws Exception {
		verifyWireParity(true, "below-size");
		verifyWireParity(false, "below-size");
	}

	@Test
	@Timeout(120)
	public void inconsistentMore() throws Exception {
		verifyWireParity(true, "inconsistent-more");
		verifyWireParity(false, "inconsistent-more");
	}

	@Test
	@Timeout(120)
	public void inconsistentComplete() throws Exception {
		verifyWireParity(true, "inconsistent-complete");
		verifyWireParity(false, "inconsistent-complete");
	}

	private void verifyWireParity(boolean prompt, String name) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		McpCompletionHandler handler = (request, context, features) -> {
			calls.incrementAndGet();
			assertEquals(Map.of(), context.getContextArguments());
			return result(context.getArgumentValue());
		};
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("completion-wire", "test").build())
				.serverInfoIncluded(false)
				.promptRegistrations(java.util.List.of(McpPromptRegistration.withName("wire")
						.handler((request, context, features) -> {
							throw new AssertionError("Completion must not invoke prompts/get.");
						})
						.arguments(java.util.List.of(McpPromptArgumentDeclaration.withName("value").build()))
						.completionHandler(handler).build()))
				.resourceRegistrations(java.util.List.of(McpResourceRegistration.withUriTemplateAndName(TEMPLATE, "wire")
						.handler((request, context, features) -> {
							throw new AssertionError("Completion must not invoke resources/read.");
						})
						.completionHandler(handler).build())).build();
		McpServer server = configure(McpServer.withPort(0), endpoint).build();
		AtomicReference<Capture> listener = new AtomicReference<>();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY).build());
		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			HttpClient client = HttpClient.newHttpClient();
			String body = body(prompt, name);
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://" + HOST + ":" + port + "/mcp"))
					.timeout(WAIT)
					.header("Content-Type", "application/json")
					.header("Accept", "application/json, text/event-stream")
					.header("MCP-Protocol-Version", VERSION)
					.header("Mcp-Method", "completion/complete")
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = client.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			Capture capture = new Capture(response.statusCode(), response.body());
			assertEnvelope(name, capture);
			listener.set(capture);
		} finally {
			soklet.close();
		}
		SokletSimulator.run(SimulatorConfig.builder()
				.configureMcpServer(builder -> configure(builder.port(0), endpoint))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY).build(), simulator -> {
			assertTrue(simulator.getMcpServer().orElseThrow().getDiagnostics()
					.getBoundAddress().isEmpty());
			Request request = Request.withPath(HttpMethod.POST, "/mcp")
					.headers(Map.of("Host", Set.of(HOST + ":0"),
							"Content-Type", Set.of("application/json"),
							"Accept", Set.of("application/json, text/event-stream"),
							"MCP-Protocol-Version", Set.of(VERSION),
							"Mcp-Method", Set.of("completion/complete")))
					.body(body(prompt, name).getBytes(StandardCharsets.UTF_8)).build();
			McpSimulation simulation = simulator.startMcpRequest(request);
			try {
				McpSimulationResponse response = simulation.awaitResponse(WAIT).orElseThrow();
				assertEquals(McpSimulationBodyType.JSON, response.getBodyType());
				Capture capture = new Capture(response.getStatusCode(),
						new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8));
				assertEnvelope(name, capture);
				assertEquals(listener.get(), capture,
						"Listener/simulator mismatch: " + prompt + ":" + name);
				assertTrue(simulation.awaitCompletion(WAIT).isPresent());
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			} finally {
				simulation.close();
			}
		});
		assertEquals(2, calls.get(), "Every admitted call executes exactly once.");
	}

	private static McpServer.Builder configure(McpServer.Builder builder, McpEndpoint endpoint) {
		return builder.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance()).allowedHosts(Set.of(HOST));
	}

	private static McpArgumentCompletionResult result(String name) {
		return switch (name) {
			case "empty" -> McpArgumentCompletionResult.fromValues(List.of());
			case "hundred" -> McpArgumentCompletionResult.fromValues(HUNDRED_VALUES);
			case "total-only" -> McpArgumentCompletionResult.withValues(List.of("canonical-ID"))
					.total(2L).build();
			case "more-only" -> McpArgumentCompletionResult.withValues(List.of())
					.hasMore(true).build();
			case "both" -> McpArgumentCompletionResult.withValues(List.of("canonical-ID"))
					.total(1L).hasMore(false)
					.metadata(McpJsonObject.builder().put("example.test/receipt", "opaque").build())
					.build();
			case "max-total" -> McpArgumentCompletionResult.withValues(List.of())
					.total(9_007_199_254_740_991L).hasMore(true).build();
			case "too-many" -> McpArgumentCompletionResult.fromValues(
					java.util.Collections.nCopies(101, "SENSITIVE-SUGGESTION"));
			case "negative-total" -> McpArgumentCompletionResult.withValues(List.of()).total(-1L).build();
			case "unsafe-total" -> McpArgumentCompletionResult.withValues(List.of())
					.total(9_007_199_254_740_992L).build();
			case "below-size" -> McpArgumentCompletionResult.withValues(List.of("SENSITIVE-SUGGESTION"))
					.total(0L).build();
			case "inconsistent-more" -> McpArgumentCompletionResult.withValues(List.of())
					.total(0L).hasMore(true).build();
			case "inconsistent-complete" -> McpArgumentCompletionResult.withValues(List.of())
					.total(1L).hasMore(false).build();
			default -> throw new AssertionError(name);
		};
	}

	private static void assertEnvelope(String name, Capture capture) {
		String completion = switch (name) {
			case "empty" -> "{\"values\":[]}";
			case "hundred" -> "{\"values\":[" + String.join(",", HUNDRED_VALUES.stream()
					.map(value -> JSON.toJson(new McpJsonString(value))).toList()) + "]}";
			case "total-only" -> "{\"values\":[\"canonical-ID\"],\"total\":2}";
			case "more-only" -> "{\"values\":[],\"hasMore\":true}";
			case "both" -> "{\"values\":[\"canonical-ID\"],\"total\":1,\"hasMore\":false}";
			case "max-total" -> "{\"values\":[],\"total\":9007199254740991,\"hasMore\":true}";
			default -> null;
		};
		if (completion == null) {
			assertEquals(500, capture.status(), capture.body());
			assertTrue(capture.body().contains("\"error\":"), capture.body());
			assertFalse(capture.body().contains("\"result\":"), capture.body());
			assertFalse(capture.body().contains("SENSITIVE-SUGGESTION"), capture.body());
			assertFalse(capture.body().contains("IllegalArgumentException"), capture.body());
			return;
		}
		assertEquals(200, capture.status(), capture.body());
		String expected = "{\"jsonrpc\":\"2.0\",\"id\":\"wire\",\"result\":{"
				+ "\"resultType\":\"complete\",\"completion\":" + completion
				+ (name.equals("both") ? ",\"_meta\":{\"example.test/receipt\":\"opaque\"}" : "") + "}}";
		assertEquals(JSON.parse(expected), JSON.parse(capture.body()), name);
	}

	private static String body(boolean prompt, String name) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"wire\",\"method\":\"completion/complete\","
				+ "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + VERSION
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}},\"ref\":{\"type\":\"ref/"
				+ (prompt ? "prompt\",\"name\":\"wire" : "resource\",\"uri\":\"" + TEMPLATE)
				+ "\"},\"argument\":{\"name\":\"value\",\"value\":\"" + name + "\"}}}";
	}

	private static List<String> hundredValues() {
		List<String> values = new ArrayList<>(List.of("", " ", "duplicate", "duplicate",
				"é", "e\u0301", "日本語", "العربية", "🙂", "quote\"slash\\newline\n"));
		for (int index = values.size(); index < 100; index++)
			values.add("canonical-ID-" + index);
		return List.copyOf(values);
	}

	private record Capture(int status, String body) {
	}
}
