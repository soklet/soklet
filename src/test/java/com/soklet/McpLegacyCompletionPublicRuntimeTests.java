/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real-listener and simulator coverage for the exact 2025 Completion profiles. */
@Timeout(60)
public class McpLegacyCompletionPublicRuntimeTests {
	private static final String HOST = "127.0.0.1";
	private static final String PATH = "/mcp";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final List<McpProtocolVersion> LEGACY = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> ALL = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);
	private static final McpJsonCodec JSON = new McpJsonCodec(
			McpJsonLimits.productionDefaults());
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1).build();
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY = LifecyclePolicy.builder()
			.startupTimeout(WAIT).startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@Test
	public void mixedEndpointRunsOnlyTheSelectedRevisionsCompleter() throws Exception {
		AtomicInteger entries = new AtomicInteger();
		List<McpPromptRegistration> prompts = new ArrayList<>();
		List<McpResourceRegistration> resources = new ArrayList<>();
		for (McpProtocolVersion completionVersion : ALL) {
			String name = "only-" + completionVersion.getWireValue();
			McpCompletionHandler handler = (requestContext, completionContext,
					invocationFeatures) -> {
				assertEquals(completionVersion, requestContext.getProtocolVersion());
				assertEquals(McpOperationType.COMPLETION_COMPLETE,
						requestContext.getOperationType());
				assertFalse(invocationFeatures.getCancelationToken().isCanceled());
				assertTrue(invocationFeatures.find(McpTaskCreationContext.class).isEmpty());
				entries.incrementAndGet();
				return McpArgumentCompletionResult.fromValues(List.of(name));
			};
			prompts.add(prompt(name, ALL, handler, Set.of(completionVersion)));
			resources.add(template(name, ALL, handler, Set.of(completionVersion)));
		}
		McpPromptRegistration juneReference = prompt("june-reference",
				Set.of(McpProtocolVersion.V2025_06_18),
				(requestContext, completionContext, invocationFeatures) -> {
					entries.incrementAndGet();
					return McpArgumentCompletionResult.fromValues(List.of("june"));
				}, Set.of(McpProtocolVersion.V2025_06_18));
		prompts.add(juneReference);
		McpEndpoint endpoint = endpoint(prompts, resources);
		McpServer server = configure(McpServer.withPort(0), endpoint).build();
		try (Soklet soklet = managed(server, new MetricsCollector() { })) {
			soklet.start();
			int port = port(server);
			for (McpProtocolVersion requestedVersion : ALL) {
				if (LEGACY.contains(requestedVersion)) {
					Capture initialize = send(port, requestedVersion, "initialize",
							"\"protocolVersion\":" + quote(requestedVersion.getWireValue())
									+ ",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}",
							"initialize", null);
					assertEquals(200, initialize.status(), initialize.body());
					assertTrue(initialize.body().contains("\"completions\":{}"), initialize.body());
				}
				for (McpProtocolVersion completionVersion : ALL) {
					String name = "only-" + completionVersion.getWireValue();
					for (boolean prompt : List.of(true, false)) {
						Capture capture = send(port, requestedVersion, "completion/complete",
								params(prompt, prompt ? name : uriTemplate(name), "value", "a", null),
								"mixed", null);
						if (requestedVersion == completionVersion)
							assertValues(capture, List.of(name));
						else if (requestedVersion != McpProtocolVersion.V2026_07_28 || prompt)
							assertValues(capture, List.of());
						else
							assertError(capture, -32602);
					}
				}
				Capture reference = send(port, requestedVersion, "completion/complete",
						params(true, "june-reference", "value", "a", null), "reference", null);
				if (requestedVersion == McpProtocolVersion.V2025_06_18)
					assertValues(reference, List.of("june"));
				else
					assertError(reference, -32602);
			}
			assertEquals(7, entries.get(), "Wrong-revision completers must never execute.");
		}
	}

	@Test
	public void initializationAdvertisesOnlyConfiguredRevisionCompletion() throws Exception {
		McpEndpoint endpoint = endpoint(List.of(prompt("modern-only", ALL,
				(requestContext, completionContext, invocationFeatures) ->
						McpArgumentCompletionResult.fromValues(List.of("modern")),
				Set.of(McpProtocolVersion.V2026_07_28))), List.of());
		McpServer server = configure(McpServer.withPort(0), endpoint).build();
		try (Soklet soklet = managed(server, new MetricsCollector() { })) {
			soklet.start();
			for (McpProtocolVersion version : LEGACY) {
				Capture initialize = send(port(server), version, "initialize",
						"\"protocolVersion\":" + quote(version.getWireValue())
								+ ",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}",
						"initialize", null);
				assertEquals(200, initialize.status(), initialize.body());
				assertFalse(initialize.body().contains("\"completions\""), initialize.body());
				Capture completion = send(port(server), version, "completion/complete",
						params(true, "modern-only", "value", "a", null), "disabled", null);
				assertEquals(404, completion.status(), completion.body());
				assertError(completion, -32601);
			}
			Capture discover = send(port(server), McpProtocolVersion.V2026_07_28,
					"server/discover", "", "discover", null);
			assertEquals(200, discover.status(), discover.body());
			assertTrue(discover.body().contains("\"completions\":{}"), discover.body());
		}
	}

	@Test
	public void visibleTargetsWithoutCompletersAreEmptyAndHiddenTargetsStayNeutral()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger charges = new AtomicInteger();
		AtomicInteger interceptorEntries = new AtomicInteger();
		AtomicInteger handlerEntries = new AtomicInteger();
		List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		CountDownLatch finished = new CountDownLatch(16);
		McpCompletionHandler handler = (requestContext, completionContext,
				invocationFeatures) -> {
			handlerEntries.incrementAndGet();
			assertEquals(" α", completionContext.getArgumentValue());
			assertEquals(Map.of("tone", "soft"), completionContext.getContextArguments());
			return McpArgumentCompletionResult.fromValues(List.of("α"));
		};
		McpEndpoint endpoint = endpoint(List.of(prompt("private", ALL, handler, ALL),
				prompt("plain", ALL, null, Set.of())),
				List.of(template("plain", ALL, null, Set.of())));
		McpServer server = configure(McpServer.withPort(0), endpoint)
				.admissionController(admissionContext -> {
					assertEquals(McpOperationType.COMPLETION_COMPLETE,
							admissionContext.getOperationType());
					admissions.incrementAndGet();
					return McpAdmissionDecision.accepted();
				})
				.requestRateLimiter(rateLimitContext -> {
					assertEquals(McpOperationType.COMPLETION_COMPLETE,
							rateLimitContext.getOperationType());
					charges.incrementAndGet();
					return McpRateLimitDecision.allowed();
				})
				.catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators(
						(requestContext, toolRegistration, invocationFeatures) -> true,
						(requestContext, promptRegistration, invocationFeatures) ->
								!"private".equals(promptRegistration.getName())
										|| requestContext.getRequest().getHeader("X-Caller")
												.map("allowed"::equals).orElse(false)))
				.handlerInterceptor((requestContext, invocationFeatures, continuation) -> {
					interceptorEntries.incrementAndGet();
					return continuation.proceed();
				}).build();
		try (Soklet soklet = managed(server, new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				events.add(event);
				if (event instanceof McpMetricsEvent.RequestFinished)
					finished.countDown();
			}
		})) {
			soklet.start();
			for (McpProtocolVersion version : LEGACY) {
				assertValues(send(port(server), version, "completion/complete",
						params(true, "private", "value", " α",
								"\"context\":{\"arguments\":{\"tone\":\"soft\"}}"), "allowed", "allowed"),
						List.of("α"));
				assertValues(send(port(server), version, "completion/complete",
						params(true, "plain", "value", "a", null), "plain", null), List.of());
				assertValues(send(port(server), version, "completion/complete",
						params(false, uriTemplate("plain"), "value", "a", null), "template", null), List.of());
				Capture hidden = send(port(server), version, "completion/complete",
						params(true, "private", "value", "secret", null), "neutral", null);
				Capture unknown = send(port(server), version, "completion/complete",
						params(true, "unknown", "value", "secret", null), "neutral", null);
				assertError(hidden, -32602);
				assertEquals(hidden, unknown, "Hidden references must not become an existence oracle.");
				assertError(send(port(server), version, "completion/complete",
						params(true, "plain", "undeclared", "a", null), "argument", null), -32602);
				assertError(send(port(server), version, "completion/complete",
						params(false, uriTemplate("plain"), "undeclared", "a", null), "resource-argument", null), -32602);
				assertError(send(port(server), version, "completion/complete",
						params(true, "private", "value", "a",
								"\"context\":{\"arguments\":{\"undeclared\":\"x\"}}"), "context", "allowed"), -32602);
			}
			assertTrue(finished.await(5, TimeUnit.SECONDS));
			assertEquals(16, admissions.get());
			assertEquals(16, charges.get(), "Charge every admitted Completion request once.");
			assertEquals(2, interceptorEntries.get());
			assertEquals(2, handlerEntries.get());
			assertEquals(16, events.stream().filter(McpMetricsEvent.RequestFinished.class::isInstance).count());
		}
	}

	@Test
	public void legacyCompletionEnvelopesAgreeOnTheListenerAndSimulator() throws Exception {
		AtomicInteger handlerEntries = new AtomicInteger();
		McpCompletionHandler handler = (requestContext, completionContext,
				invocationFeatures) -> {
			handlerEntries.incrementAndGet();
			assertTrue(LEGACY.contains(requestContext.getProtocolVersion()));
			assertEquals(" λ", completionContext.getArgumentValue());
			return McpArgumentCompletionResult.withValues(List.of("", "λ", "🙂"))
					.total(5L).hasMore(true)
					.metadata(McpJsonObject.builder().put("example.test/receipt", "opaque").build())
					.build();
		};
		McpEndpoint endpoint = endpoint(List.of(prompt("wire", ALL, handler, ALL)),
				List.of(template("wire", ALL, handler, ALL)));
		McpServer server = configure(McpServer.withPort(0), endpoint).build();
		List<Capture> listener = new ArrayList<>();
		try (Soklet soklet = managed(server, new MetricsCollector() { })) {
			soklet.start();
			for (McpProtocolVersion version : LEGACY)
				for (boolean prompt : List.of(true, false)) {
					Capture capture = send(port(server), version, "completion/complete",
							params(prompt, prompt ? "wire" : uriTemplate("wire"), "value", " λ", null),
							"wire", null);
					assertEquals(200, capture.status(), capture.body());
					assertEquals(JSON.parse("{\"jsonrpc\":\"2.0\",\"id\":\"wire\",\"result\":{"
							+ "\"completion\":{\"values\":[\"\",\"λ\",\"🙂\"],\"total\":5,\"hasMore\":true},"
							+ "\"_meta\":{\"example.test/receipt\":\"opaque\"}}}"), JSON.parse(capture.body()));
					assertFalse(capture.body().contains("resultType"), capture.body());
					listener.add(capture);
				}
		}
		SokletSimulator.run(SimulatorConfig.builder()
				.configureMcpServer(builder -> configure(builder.port(0), endpoint))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY).build(), simulator -> {
			int index = 0;
			for (McpProtocolVersion version : LEGACY)
				for (boolean prompt : List.of(true, false)) {
					Request request = Request.withPath(HttpMethod.POST, PATH)
							.headers(Map.of("Host", Set.of(HOST + ":0"),
									"Content-Type", Set.of("application/json"),
									"Accept", Set.of("application/json, text/event-stream"),
									"MCP-Protocol-Version", Set.of(version.getWireValue())))
							.body(body(version, "completion/complete", params(prompt,
									prompt ? "wire" : uriTemplate("wire"), "value", " λ", null), "wire")
									.getBytes(StandardCharsets.UTF_8)).build();
					try (McpSimulation simulation = simulator.startMcpRequest(request)) {
						McpSimulationResponse response = simulation.awaitResponse(WAIT).orElseThrow();
						assertEquals(McpSimulationBodyType.JSON, response.getBodyType());
						assertEquals(listener.get(index++), new Capture(response.getStatusCode(),
								new String(response.getBody().orElseThrow(), StandardCharsets.UTF_8)));
						assertTrue(simulation.awaitCompletion(WAIT).isPresent());
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new AssertionError(exception);
					}
				}
		});
		assertEquals(8, handlerEntries.get());
	}

	@Test
	public void legacyCompletionUsesExistingDeadlineAndCancelationLifecycle() throws Exception {
		CountDownLatch canceled = new CountDownLatch(4);
		CountDownLatch exited = new CountDownLatch(4);
		CountDownLatch release = new CountDownLatch(1);
		McpCompletionHandler handler = (requestContext, completionContext,
				invocationFeatures) -> {
			invocationFeatures.getCancelationToken().onCancel(canceled::countDown);
			try {
				release.await(5, TimeUnit.SECONDS);
				return McpArgumentCompletionResult.fromValues(List.of("late"));
			} finally {
				exited.countDown();
			}
		};
		McpEndpoint endpoint = endpoint(List.of(prompt("deadline", ALL, handler, ALL)),
				List.of(template("deadline", ALL, handler, ALL)));
		McpServer server = configure(McpServer.withPort(0), endpoint)
				.requestTimeout(Duration.ofMillis(250)).build();
		try (Soklet soklet = managed(server, new MetricsCollector() { })) {
			soklet.start();
			try {
				for (McpProtocolVersion version : LEGACY)
					for (boolean prompt : List.of(true, false)) {
						Capture capture = send(port(server), version, "completion/complete",
								params(prompt, prompt ? "deadline" : uriTemplate("deadline"), "value", "a", null),
								"deadline", null);
						assertEquals(504, capture.status(), capture.body());
						assertError(capture, -32603);
						assertFalse(capture.body().contains("late"), capture.body());
					}
				assertTrue(canceled.await(5, TimeUnit.SECONDS));
			} finally {
				release.countDown();
			}
			assertTrue(exited.await(5, TimeUnit.SECONDS));
		}
	}

	private static McpPromptRegistration prompt(String name,
			Set<McpProtocolVersion> versions, McpCompletionHandler completionHandler,
			Set<McpProtocolVersion> completionVersions) {
		McpPromptRegistration.Builder builder = McpPromptRegistration.withName(name, versions)
				.handler((requestContext, promptGetContext, invocationFeatures) -> {
					throw new AssertionError("Completion must not invoke prompts/get.");
				})
				.arguments(List.of(McpPromptArgumentDeclaration.withName("value").required(true).build(),
						McpPromptArgumentDeclaration.withName("tone").required(true).build()));
		if (completionHandler != null)
			builder.completionHandler(completionHandler, completionVersions);
		return builder.build();
	}

	private static McpResourceRegistration template(String name,
			Set<McpProtocolVersion> versions, McpCompletionHandler completionHandler,
			Set<McpProtocolVersion> completionVersions) {
		McpResourceRegistration.TemplateBuilder builder = McpResourceRegistration
				.withUriTemplateAndName(uriTemplate(name), name, versions)
				.handler((requestContext, resourceReadContext, invocationFeatures) -> {
					throw new AssertionError("Completion must not invoke resources/read.");
				});
		if (completionHandler != null)
			builder.completionHandler(completionHandler, completionVersions);
		return builder.build();
	}

	private static String uriTemplate(String name) {
		return "catalog://" + name + "/{value}";
	}

	private static McpEndpoint endpoint(List<McpPromptRegistration> prompts,
			List<McpResourceRegistration> resources) {
		return McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion("legacy-completion", "test").build(), ALL)
				.promptRegistrations(prompts).resourceRegistrations(resources).build();
	}

	private static McpServer.Builder configure(McpServer.Builder builder, McpEndpoint endpoint) {
		return builder.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.requestRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance()).allowedHosts(Set.of(HOST));
	}

	private static Soklet managed(McpServer server, MetricsCollector metricsCollector) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector).lifecyclePolicy(TEST_LIFECYCLE_POLICY).build());
	}

	private static int port(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static Capture send(int port, McpProtocolVersion version, String method,
			String params, String id, String caller) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(
				URI.create("http://" + HOST + ":" + port + PATH)).timeout(WAIT)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream");
		if (version == McpProtocolVersion.V2026_07_28 || !"initialize".equals(method))
			builder.header("MCP-Protocol-Version", version.getWireValue());
		if (version == McpProtocolVersion.V2026_07_28)
			builder.header("Mcp-Method", method);
		if (caller != null)
			builder.header("X-Caller", caller);
		HttpResponse<String> response = HTTP.send(builder
				.POST(HttpRequest.BodyPublishers.ofString(body(version, method, params, id),
						StandardCharsets.UTF_8)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new Capture(response.statusCode(), response.body());
	}

	private static String body(McpProtocolVersion version, String method, String params, String id) {
		String metadata = version == McpProtocolVersion.V2026_07_28
				? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
						+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" : "";
		String fields = metadata + (metadata.isEmpty() || params.isEmpty() ? "" : ",") + params;
		return "{\"jsonrpc\":\"2.0\",\"id\":" + quote(id) + ",\"method\":" + quote(method)
				+ (fields.isEmpty() ? "" : ",\"params\":{" + fields + "}") + "}";
	}

	private static String params(boolean prompt, String reference, String argument,
			String value, String extra) {
		return "\"ref\":{\"type\":\"ref/" + (prompt ? "prompt\",\"name\":" : "resource\",\"uri\":")
				+ quote(reference) + "},\"argument\":{\"name\":" + quote(argument)
				+ ",\"value\":" + quote(value) + "}" + (extra == null ? "" : "," + extra);
	}

	private static String quote(String value) {
		return JSON.toJson(new McpJsonString(value));
	}

	private static void assertValues(Capture capture, List<String> values) {
		assertEquals(200, capture.status(), capture.body());
		String expected = "\"values\":[" + String.join(",", values.stream()
				.map(McpLegacyCompletionPublicRuntimeTests::quote).toList()) + "]";
		assertTrue(capture.body().contains(expected), capture.body());
		assertFalse(capture.body().contains("\"error\""), capture.body());
	}

	private static void assertError(Capture capture, int code) {
		assertTrue(capture.body().contains("\"code\":" + code), capture.body());
		assertFalse(capture.body().contains("\"result\""), capture.body());
	}

	private record Capture(int status, String body) { }
}
