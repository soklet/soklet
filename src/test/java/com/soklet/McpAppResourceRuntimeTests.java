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

import com.soklet.internal.mcp.protocol.McpJsonLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Public resource-read wiring, bounds, and listener/simulator Apps parity. */
@Timeout(60)
public class McpAppResourceRuntimeTests {
	private static final URI URI_VALUE = URI.create("ui://example/view");
	private static final String MIME = "text/html;profile=mcp-app";
	private static final String HOST = "127.0.0.1";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final LifecyclePolicy POLICY = LifecyclePolicy.builder()
			.startupTimeout(WAIT).startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@Test
	void typedSecurityMetadataHasListenerSimulatorParity() throws Exception {
		McpAppResourceMetadata metadata = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.connectDomains(Set.of("https://z.example", "https://a.example"))
						.build())
				.permissions(Set.of(McpAppResourceMetadata.Permission.CLIPBOARD_WRITE,
						McpAppResourceMetadata.Permission.CAMERA))
				.domain("sandbox.example").prefersBorder(false).build();
		McpJsonObject raw = McpJsonObject.builder().put("example.test/marker", "preserved").build();
		McpResourceOutput output = McpResourceOutput.fromContents(List.of(
				McpTextResourceContents.withUriAndText(URI_VALUE, "<html>hello</html>")
						.mimeType("TEXT/HTML; profile=\"mcp-app\"")
						.appResourceMetadata(metadata).metadata(raw).build(),
				McpBlobResourceContents.withUriAndData(URI_VALUE,
						"<html>blob</html>".getBytes(StandardCharsets.UTF_8))
						.mimeType(MIME).appResourceMetadata(metadata).build()));
		AtomicInteger calls = new AtomicInteger();
		McpEndpoint endpoint = endpoint(() -> {
			calls.incrementAndGet();
			return output;
		});
		McpServer server = configure(McpServer.withPort(0), endpoint).build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(POLICY).build());
		String wire;
		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://" + HOST + ":" + port + "/mcp"))
					.timeout(WAIT).header("Content-Type", "application/json")
					.header("Accept", "application/json, text/event-stream")
					.header("MCP-Protocol-Version", "2026-07-28")
					.header("Mcp-Method", "resources/read")
					.header("Mcp-Name", URI_VALUE.toString())
					.POST(HttpRequest.BodyPublishers.ofString(body(), StandardCharsets.UTF_8)).build();
			HttpResponse<String> response = HttpClient.newHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertEquals(200, response.statusCode(), response.body());
			wire = response.body();
		} finally {
			soklet.close();
		}
		assertTrue(wire.contains("\"connectDomains\":[\"https://a.example\",\"https://z.example\"]"), wire);
		assertTrue(wire.contains("\"permissions\":{\"camera\":{},\"clipboardWrite\":{}}"), wire);
		assertTrue(wire.contains("\"domain\":\"sandbox.example\""), wire);
		assertTrue(wire.contains("\"prefersBorder\":false"), wire);
		assertTrue(wire.contains("\"example.test/marker\":\"preserved\""), wire);
		assertTrue(wire.contains("\"blob\":\"PGh0bWw+YmxvYjwvaHRtbD4=\""), wire);
		run(endpoint, simulator -> assertEquals(wire, read(simulator, 200)));
		assertEquals(2, calls.get(), "Every read independently invokes the application handler.");
	}

	@Test
	@Timeout(120)
	void eligibleReadsFailClosedOnAnyReturnedUriOrMimeMismatchAndRecover() {
		McpResourceContents healthy = McpTextResourceContents.withUriAndText(URI_VALUE, "healthy")
				.mimeType(MIME).build();
		AtomicReference<McpResourceOutput> result = new AtomicReference<>(McpResourceOutput.fromContent(healthy));
		AtomicInteger calls = new AtomicInteger();
		McpEndpoint endpoint = endpoint(() -> {
			calls.incrementAndGet();
			return result.get();
		});
		run(endpoint, simulator -> {
			List<McpResourceContents> invalid = List.of(
					McpTextResourceContents.withUriAndText(URI_VALUE, "SECRET").build(),
					McpTextResourceContents.withUriAndText(URI_VALUE, "SECRET").mimeType("text/plain").build(),
					McpTextResourceContents.withUriAndText(URI_VALUE, "SECRET").mimeType(MIME + ";charset=utf-8").build(),
					McpTextResourceContents.withUriAndText(URI_VALUE, "SECRET").mimeType("text/html;profile=\"broken").build(),
					McpBlobResourceContents.withUriAndData(URI.create("ui://example/SECRET"), new byte[]{1})
							.mimeType(MIME).build());
			assertEquals(5, invalid.size());
			for (int index = 0; index < 5; index++) {
				McpResourceContents wrong = invalid.get(index);
				result.set(McpResourceOutput.fromContents(List.of(healthy, wrong)));
				String response = read(simulator, 500);
				assertTrue(response.contains("\"code\":-32603"), response);
				assertFalse(response.contains("SECRET"), response);
				assertFalse(response.contains("healthy"), response);
				assertFalse(response.contains("must match"), response);
			}
			result.set(McpResourceOutput.fromContent(healthy));
			assertTrue(read(simulator, 200).contains("healthy"));
		});
		assertEquals(6, calls.get());
	}

	@Test
	void composedTypedMetadataCountsTowardAggregateNodeLimit() throws Exception {
		McpResourceContents plain = McpTextResourceContents.withUriAndText(URI_VALUE, "small")
				.mimeType(MIME).build();
		McpResourceContents typed = McpTextResourceContents.withUriAndText(URI_VALUE, "small")
				.mimeType(MIME).appResourceMetadata(McpAppResourceMetadata.builder()
						.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder().build())
						.permissions(Set.of(McpAppResourceMetadata.Permission.CAMERA)).build()).build();
		int count = (McpJsonLimits.productionDefaults().maximumNodeCount() - 8) / 4;
		Method budget = DefaultMcpServer.class.getDeclaredMethod("requireResourceResultFitsJsonNodeBudget",
				McpResourceOutput.class, McpJsonObject.class);
		budget.setAccessible(true);
		budget.invoke(null, McpResourceOutput.fromContents(Collections.nCopies(count, plain)), McpJsonObject.emptyInstance());
		InvocationTargetException failure = assertThrows(InvocationTargetException.class,
				() -> budget.invoke(null, McpResourceOutput.fromContents(Collections.nCopies(count, typed)),
						McpJsonObject.emptyInstance()));
		assertInstanceOf(IllegalArgumentException.class, failure.getCause());
	}

	@Test
	void oversizedTypedResourceMetadataFailsClosedOnWire() {
		Set<String> domains = new java.util.LinkedHashSet<>();
		int count = McpJsonLimits.productionDefaults().maximumNodeCount();
		for (int index = 0; index < count; index++)
			domains.add("https://secret-" + index + ".example");
		McpResourceContents contents = McpTextResourceContents.withUriAndText(URI_VALUE, "SECRET-HTML")
				.mimeType(MIME).appResourceMetadata(McpAppResourceMetadata.builder()
						.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder()
								.resourceDomains(domains).build()).build()).build();
		run(endpoint(() -> McpResourceOutput.fromContent(contents)), simulator -> {
			String response = read(simulator, 500);
			assertTrue(response.contains("\"code\":-32603"), response);
			assertFalse(response.contains("secret-"), response);
			assertFalse(response.contains("SECRET-HTML"), response);
		});
	}

	private static McpEndpoint endpoint(java.util.function.Supplier<McpResourceOutput> output) {
		return McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("apps-test", "test").build())
				.addResource(McpResourceRegistration.withUriAndName(URI_VALUE, "view")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(output.get())).mimeType(MIME).build()).build();
	}

	private static McpServer.Builder configure(McpServer.Builder builder, McpEndpoint endpoint) {
		return builder.host(HOST).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance()).allowedHosts(Set.of(HOST));
	}

	private static void run(McpEndpoint endpoint, java.util.function.Consumer<Simulator> test) {
		SokletSimulator.run(SimulatorConfig.builder()
				.configureMcpServer(builder -> configure(builder.port(0), endpoint))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(POLICY).build(), test::accept);
	}

	private static String body() {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"read\",\"method\":\"resources/read\",\"params\":{"
				+ "\"uri\":\"" + URI_VALUE + "\",\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
	}

	private static String read(Simulator simulator, int expectedStatus) {
		McpSimulation simulation = simulator.startMcpRequest(Request.withPath(HttpMethod.POST, "/mcp")
				.headers(Map.of("Host", Set.of(HOST + ":0"), "Content-Type", Set.of("application/json"),
						"Accept", Set.of("application/json, text/event-stream"),
						"MCP-Protocol-Version", Set.of("2026-07-28"), "Mcp-Method", Set.of("resources/read"),
						"Mcp-Name", Set.of(URI_VALUE.toString())))
				.body(body().getBytes(StandardCharsets.UTF_8)).build());
		try {
			McpSimulationResponse response = simulation.awaitResponse(WAIT).orElseThrow();
			assertEquals(McpSimulationBodyType.JSON, response.getBodyType());
			String wire = new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8);
			assertEquals(expectedStatus, response.getStatusCode(), wire);
			assertTrue(simulation.awaitCompletion(WAIT).isPresent());
			return wire;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		} finally {
			simulation.close();
		}
	}
}
