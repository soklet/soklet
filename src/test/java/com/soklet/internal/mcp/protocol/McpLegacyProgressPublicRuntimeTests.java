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

package com.soklet.internal.mcp.protocol;

import com.soklet.CancelationToken;
import com.soklet.CorsAuthorizer;
import com.soklet.HttpMethod;
import com.soklet.LifecyclePolicy;
import com.soklet.McpArgumentCompletionResult;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpInvocationFeatures;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpMetricsEvent;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpPromptArgumentDeclaration;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyType;
import com.soklet.McpSimulationStreamItem;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpTextContent;
import com.soklet.McpTextResourceContents;
import com.soklet.McpToolHandler;
import com.soklet.McpToolOutput;
import com.soklet.McpToolRegistration;
import com.soklet.MetricsCollector;
import com.soklet.Request;
import com.soklet.ResourceMethodResolver;
import com.soklet.SimulatorConfig;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.SokletSimulator;
import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Public listener and simulator contracts for both exact 2025 progress profiles. */
@Timeout(60)
public class McpLegacyProgressPublicRuntimeTests {
	private static final String HOST = "127.0.0.1";
	private static final String PATH = "/mcp";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final List<McpProtocolVersion> LEGACY = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
	private static final Set<McpProtocolVersion> VERSIONS = Set.copyOf(LEGACY);
	private static final McpJsonCodec JSON = new McpJsonCodec(McpJsonLimits.productionDefaults());
	private static final LifecyclePolicy LIFECYCLE = LifecyclePolicy.builder()
			.startupTimeout(WAIT).startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@Test
	public void firstProgressPrecedesHandlerCompletionAndPreservesExactMonotonicUpdates()
			throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			CountDownLatch release = new CountDownLatch(1);
			AtomicReference<McpProgressReporter> retainedReporter = new AtomicReference<>();
			AtomicReference<CancelationToken> retainedToken = new AtomicReference<>();
			McpEndpoint endpoint = endpoint(tool("progress", (requestContext, arguments,
					invocationFeatures) -> {
				assertEquals(version, requestContext.getProtocolVersion());
				McpProgressReporter reporter = invocationFeatures.getProgressReporter().orElseThrow();
				retainedReporter.set(reporter);
				retainedToken.set(invocationFeatures.getCancelationToken());
				reporter.report(McpProgressUpdate.withProgress(1.25).total(4.5)
						.message("Working 世界").build());
				assertTrue(release.await(5, TimeUnit.SECONDS));
				reporter.report(McpProgressUpdate.withProgress(2.5).build());
				reporter.report(McpProgressUpdate.withProgress(2.5)
						.message("coalesced").build());
				assertThrows(IllegalArgumentException.class, () ->
						reporter.report(McpProgressUpdate.withProgress(2.25).build()));
				reporter.report(McpProgressUpdate.withProgress(4.5).total(4.5).build());
				return McpCompleteResult.withToolOutput(McpToolOutput.fromErrorText("raw-secret"))
						.metadata(McpJsonObject.builder().put("example.test/receipt", "opaque").build())
						.build();
			}));
			McpServer server = configure(McpServer.withPort(0), endpoint)
					.toolResultSanitizer((requestContext, toolName, rawArguments, completeResult) ->
							completeResult.toBuilder().payload(((McpToolOutput) completeResult.getPayload())
									.toBuilder().content(List.of(McpTextContent.fromText("safe"))).build()).build())
					.build();
			try (Soklet soklet = managed(server, new MetricsCollector() { })) {
				soklet.start();
				try (McpChunkedHttpClient client = call(port(server), version,
						"tools/call", toolParams("progress"), "\"progress\"", "9007199254740991")) {
					assertSseHead(client.readHead());
					assertEquals(progress("9007199254740991", "1.25,\"total\":4.5,\"message\":\"Working 世界\""),
							client.readChunkText());
					assertEquals(1, release.getCount(), "The first event must arrive while work is blocked.");
					release.countDown();
					assertEquals(progress("9007199254740991", "2.5"), client.readChunkText());
					assertEquals(progress("9007199254740991", "4.5,\"total\":4.5"), client.readChunkText());
					String terminal = client.readChunkText();
					assertJsonEquals("{\"jsonrpc\":\"2.0\",\"id\":\"progress\",\"result\":{"
							+ "\"content\":[{\"type\":\"text\",\"text\":\"safe\"}],\"isError\":true,"
							+ "\"_meta\":{\"example.test/receipt\":\"opaque\"}}}", message(terminal));
					assertFalse(terminal.contains("raw-secret"), terminal);
					assertFalse(terminal.contains("resultType"), terminal);
					assertFalse(terminal.contains("io.modelcontextprotocol"), terminal);
					assertNull(client.readChunk(), "No equal, decreasing, or late update may follow the result.");
				} finally {
					release.countDown();
				}
				assertFalse(retainedToken.get().isCanceled());
				assertDoesNotThrow(() -> retainedReporter.get().report(McpProgressUpdate.withProgress(0.0).build()));
				awaitCleanup(server);
			}
		}
	}

	@Test
	public void simulatorPublishesProgressBeforeTerminalAndDuplicatesTheExactTerminalMessage()
			throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			CountDownLatch release = new CountDownLatch(1);
			McpEndpoint endpoint = endpoint(tool("progress", (requestContext, arguments,
					invocationFeatures) -> {
				invocationFeatures.getProgressReporter().orElseThrow().report(
						McpProgressUpdate.withProgress(0.5).message("Halfway").build());
				assertTrue(release.await(5, TimeUnit.SECONDS));
				return McpCompleteResult.fromToolText("done").toBuilder()
						.metadata(McpJsonObject.builder().put("example.test/receipt", "opaque").build())
						.build();
			}));
			SokletSimulator.run(simulatorConfig(endpoint), simulator -> {
				try (McpSimulation simulation = simulator.startMcpRequest(simulationRequest(version,
						"tools/call", toolParams("progress"), "\"simulation\"", "\"token\""))) {
					assertEquals(McpSimulationBodyType.SSE,
							simulation.awaitResponse(WAIT).orElseThrow().getBodyType());
					McpSimulationStreamItem first = simulation.awaitStreamItem(WAIT).orElseThrow();
					assertEquals(progress("\"token\"", "0.5,\"message\":\"Halfway\""), text(first));
					assertEquals(McpServerRuntimeBridge.toPublic(JSON.parse(message(text(first)))),
							first.getMessage().orElseThrow());
					assertTrue(simulation.awaitCompletion(Duration.ZERO).isEmpty());
					release.countDown();
					McpSimulationStreamItem terminal = simulation.awaitStreamItem(WAIT).orElseThrow();
					assertJsonEquals("{\"jsonrpc\":\"2.0\",\"id\":\"simulation\",\"result\":{"
							+ "\"content\":[{\"type\":\"text\",\"text\":\"done\"}],"
							+ "\"_meta\":{\"example.test/receipt\":\"opaque\"}}}", message(text(terminal)));
					com.soklet.McpJsonValue decodedTerminal =
							McpServerRuntimeBridge.toPublic(JSON.parse(message(text(terminal))));
					assertEquals(decodedTerminal, terminal.getMessage().orElseThrow());
					var completion = simulation.awaitCompletion(WAIT).orElseThrow();
					assertEquals(McpStreamTerminationReason.COMPLETED, completion.getReason());
					assertEquals(terminal.getMessage(), completion.getTerminalMessage());
					assertEquals(decodedTerminal, completion.getTerminalMessage().orElseThrow());
					assertTrue(simulation.awaitStreamItem(Duration.ZERO).isEmpty());
				} finally {
					release.countDown();
				}
			});
		}
	}

	@Test
	public void noReportsStayJsonAndMissingOrMalformedTokensDoNotStartProgress() throws Exception {
		AtomicInteger entries = new AtomicInteger();
		McpEndpoint endpoint = endpoint(tool("quiet", (requestContext, arguments, invocationFeatures) -> {
			entries.incrementAndGet();
			assertEquals(requestContext.getRequest().getHeader("X-Expect-Reporter").isPresent(),
					invocationFeatures.getProgressReporter().isPresent());
			return McpCompleteResult.fromToolText("quiet");
		}));
		McpServer server = configure(McpServer.withPort(0), endpoint).build();
		try (Soklet soklet = managed(server, new MetricsCollector() { })) {
			soklet.start();
			for (McpProtocolVersion version : LEGACY) {
				for (String token : new String[] { null, "\"valid\"" }) {
					List<McpChunkedHttpClient.RequestHeader> headers = token == null
							? headers(version) : List.of(
									new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", version.getWireValue()),
									new McpChunkedHttpClient.RequestHeader("X-Expect-Reporter", "true"));
					try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(port(server),
							body("tools/call", toolParams("quiet"), "\"quiet\"", token), headers)) {
						var head = client.readHead();
						assertEquals(200, head.status(), head.raw());
						assertEquals("application/json", head.singleHeader("Content-Type"));
						assertJsonEquals("{\"jsonrpc\":\"2.0\",\"id\":\"quiet\",\"result\":{"
								+ "\"content\":[{\"type\":\"text\",\"text\":\"quiet\"}]}}", client.readFixedBody(head));
					}
				}
				for (String token : List.of("null", "true", "1.5", "{}", "[]")) {
					try (McpChunkedHttpClient client = call(port(server), version, "tools/call",
							toolParams("quiet"), "\"invalid\"", token)) {
						var head = client.readHead();
						assertEquals("application/json", head.singleHeader("Content-Type"));
						assertTrue(client.readFixedBody(head).contains("\"code\":-32602"));
					}
				}
			}
			assertEquals(4, entries.get(), "Malformed tokens must fail before application execution.");
		}
	}

	@Test
	public void legacyJsonAndSseTerminalProjectionsAgreeForPromptsResourcesAndCompletion() throws Exception {
		McpPromptRegistration prompt = McpPromptRegistration.withName("prompt", VERSIONS)
				.handler((requestContext, promptGetContext, invocationFeatures) -> {
					reportIfAvailable(invocationFeatures);
					return McpCompleteResult.withPromptOutput(McpPromptOutput.fromMessages(
							McpPromptMessage.fromUserText("prompt result"))).metadata(metadata()).build();
				})
				.arguments(List.of(McpPromptArgumentDeclaration.withName("value").build()))
				.completionHandler((requestContext, completionContext, invocationFeatures) -> {
					reportIfAvailable(invocationFeatures);
					return McpArgumentCompletionResult.withValues(List.of("value"))
							.metadata(metadata()).build();
				}, VERSIONS).build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(URI.create("catalog://item"), "item", VERSIONS)
				.handler((requestContext, resourceReadContext, invocationFeatures) -> {
					reportIfAvailable(invocationFeatures);
					return McpCompleteResult.withResourceOutput(McpResourceOutput.fromContent(
							McpTextResourceContents.withUriAndText(resourceReadContext.getUri(), "resource result").build()))
							.metadata(metadata()).build();
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion("legacy-progress", "test").build(), VERSIONS)
				.promptRegistrations(List.of(prompt)).resourceRegistrations(List.of(resource)).build();
		McpServer server = configure(McpServer.withPort(0), endpoint).build();
		Map<String, String> calls = Map.of("prompts/get", "\"name\":\"prompt\",\"arguments\":{\"value\":\"v\"}",
				"resources/read", "\"uri\":\"catalog://item\"",
				"completion/complete", "\"ref\":{\"type\":\"ref/prompt\",\"name\":\"prompt\"},"
						+ "\"argument\":{\"name\":\"value\",\"value\":\"v\"}");
		try (Soklet soklet = managed(server, new MetricsCollector() { })) {
			soklet.start();
			for (McpProtocolVersion version : LEGACY)
				for (Map.Entry<String, String> operation : calls.entrySet()) {
					String json;
					try (McpChunkedHttpClient client = call(port(server), version, operation.getKey(),
							operation.getValue(), "\"projection\"", null)) {
						json = client.readFixedBody(client.readHead());
					}
					try (McpChunkedHttpClient client = call(port(server), version, operation.getKey(),
							operation.getValue(), "\"projection\"", "\"token\"")) {
						assertSseHead(client.readHead());
						assertEquals(progress("\"token\"", "1"), client.readChunkText());
						String terminal = client.readChunkText();
						assertJsonEquals(json, message(terminal));
						assertFalse(terminal.contains("resultType"), terminal);
						assertFalse(terminal.contains("io.modelcontextprotocol"), terminal);
						assertTrue(terminal.contains("example.test/receipt"), terminal);
						assertNull(client.readChunk());
					}
				}
		}
	}

	@Test
	public void intentionalAndUnexpectedErrorsUseJsonBeforeProgressAndSseAfterIt() throws Exception {
		McpEndpoint endpoint = endpoint(tool("intentional", (requestContext, arguments, invocationFeatures) -> {
			if (requestContext.getRequest().getHeader("X-Report").isPresent())
				reportIfAvailable(invocationFeatures);
			throw new McpJsonRpcException(McpJsonRpcError.fromApplication(-31903, "Operation unavailable"));
		}), tool("unexpected", (requestContext, arguments, invocationFeatures) -> {
			if (requestContext.getRequest().getHeader("X-Report").isPresent())
				reportIfAvailable(invocationFeatures);
			throw new IllegalStateException("exception-secret");
		}));
		McpServer server = configure(McpServer.withPort(0), endpoint).build();
		try (Soklet soklet = managed(server, new MetricsCollector() { })) {
			soklet.start();
			for (McpProtocolVersion version : LEGACY)
				for (String name : List.of("intentional", "unexpected"))
					for (boolean reports : List.of(false, true)) {
						List<McpChunkedHttpClient.RequestHeader> requestHeaders = reports
								? List.of(new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", version.getWireValue()),
										new McpChunkedHttpClient.RequestHeader("X-Report", "true")) : headers(version);
						try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(port(server),
								body("tools/call", toolParams(name), "\"error\"", "\"token\""), requestHeaders)) {
							var head = client.readHead();
							String terminal;
							if (reports) {
								assertSseHead(head);
								assertEquals(progress("\"token\"", "1"), client.readChunkText());
								terminal = message(client.readChunkText());
								assertNull(client.readChunk());
							} else {
								assertEquals("application/json", head.singleHeader("Content-Type"));
								terminal = client.readFixedBody(head);
							}
							String error = name.equals("intentional")
									? "{\"code\":-31903,\"message\":\"Operation unavailable\"}"
									: "{\"code\":-32603,\"message\":\"Internal error\"}";
							assertJsonEquals("{\"jsonrpc\":\"2.0\",\"id\":\"error\",\"error\":" + error + "}", terminal);
							assertFalse(terminal.contains("exception-secret"), terminal);
						}
					}
		}
	}

	@Test
	public void committedDisconnectDetachesTheWriterAndRetainsUncanceledPhysicalWork() throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Gate gate = new Gate();
			CountDownLatch streamClosed = new CountDownLatch(1);
			AtomicInteger progressEvents = new AtomicInteger();
			McpEndpoint endpoint = endpoint(tool("held", gate.handler(true)));
			McpServer server = configure(McpServer.withPort(0), endpoint).build();
			try (Soklet soklet = managed(server, new MetricsCollector() {
				@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
					if (event instanceof McpMetricsEvent.RequestStreamClosed)
						streamClosed.countDown();
					if (event instanceof McpMetricsEvent.ProgressEmitted)
						progressEvents.incrementAndGet();
				}
			})) {
				soklet.start();
				try (McpChunkedHttpClient client = call(port(server), version, "tools/call",
						toolParams("held"), "\"held\"", "\"token\"")) {
					assertSseHead(client.readHead());
					assertEquals(progress("\"token\"", "1"), client.readChunkText());
					client.closeWithReset();
					assertTrue(streamClosed.await(5, TimeUnit.SECONDS));
					assertFalse(gate.token.get().isCanceled());
					assertEquals(1, gate.canceled.getCount());
					assertEquals(1, server.getDiagnostics().getActiveHandlerExecutions(),
							"Writer loss must retain the running handler's physical execution slot.");
					assertDoesNotThrow(() -> gate.reporter.get().report(McpProgressUpdate.withProgress(0.0).build()));
					assertEquals(1, progressEvents.get(), "A detached reporter is inert.");
				} finally {
					gate.release.countDown();
				}
				assertTrue(gate.exited.await(5, TimeUnit.SECONDS));
				awaitCleanup(server);
				assertFalse(gate.token.get().isCanceled());
			}
		}
	}

	@Test
	public void finiteAndUncommittedDisconnectsStillCancelTheHandler() throws Exception {
		for (McpProtocolVersion version : LEGACY)
			for (String token : new String[] { null, "\"unused\"" }) {
				Gate gate = new Gate();
				McpEndpoint endpoint = endpoint(tool("held", gate.handler(false)));
				McpServer server = configure(McpServer.withPort(0), endpoint).build();
				try (Soklet soklet = managed(server, new MetricsCollector() { })) {
					soklet.start();
					try (McpChunkedHttpClient client = call(port(server), version, "tools/call",
							toolParams("held"), "\"finite\"", token)) {
						assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
						client.closeWithReset();
						assertTrue(gate.canceled.await(5, TimeUnit.SECONDS));
						assertTrue(gate.token.get().isCanceled());
						assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
								gate.token.get().getCancelationReason().orElseThrow());
					} finally {
						gate.release.countDown();
					}
					assertTrue(gate.exited.await(5, TimeUnit.SECONDS));
					awaitCleanup(server);
				}
			}
	}

	@Test
	public void detachedProgressWorkStillObservesItsDeadlineAndRetainsCapacityUntilPhysicalExit()
			throws Exception {
		for (McpProtocolVersion version : LEGACY) {
			Gate gate = new Gate();
			CountDownLatch streamClosed = new CountDownLatch(1);
			McpEndpoint endpoint = endpoint(tool("held", gate.handler(true, true)));
			McpServer server = configure(McpServer.withPort(0), endpoint)
					.requestTimeout(Duration.ofSeconds(2)).build();
			try (Soklet soklet = managed(server, new MetricsCollector() {
				@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
					if (event instanceof McpMetricsEvent.RequestStreamClosed)
						streamClosed.countDown();
				}
			})) {
				soklet.start();
				try (McpChunkedHttpClient client = call(port(server), version, "tools/call",
						toolParams("held"), "\"deadline\"", "\"token\"")) {
					assertSseHead(client.readHead());
					assertEquals(progress("\"token\"", "1"), client.readChunkText());
					client.closeWithReset();
					assertTrue(streamClosed.await(5, TimeUnit.SECONDS));
					assertFalse(gate.token.get().isCanceled());
					assertTrue(gate.canceled.await(5, TimeUnit.SECONDS),
							"Writer detachment must retain the application's request deadline.");
					assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,
							gate.token.get().getCancelationReason().orElseThrow());
					assertEquals(1, server.getDiagnostics().getActiveHandlerExecutions(),
							"Cooperative cancelation must not refund an occupied execution slot.");
				} finally {
					gate.release.countDown();
				}
				assertTrue(gate.exited.await(5, TimeUnit.SECONDS));
				awaitCleanup(server);
			}
		}
	}

	@Test
	public void simulatorDisconnectUsesTheSameCommittedVersusFiniteRule() throws Exception {
		for (McpProtocolVersion version : LEGACY)
			for (boolean reports : List.of(false, true)) {
				Gate gate = new Gate();
				McpEndpoint endpoint = endpoint(tool("held", gate.handler(reports)));
				SokletSimulator.run(simulatorConfig(endpoint), simulator -> {
					try (McpSimulation simulation = simulator.startMcpRequest(simulationRequest(version,
							"tools/call", toolParams("held"), "\"held\"", "\"token\""))) {
						assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
						if (reports) {
							assertEquals(McpSimulationBodyType.SSE,
									simulation.awaitResponse(WAIT).orElseThrow().getBodyType());
							assertEquals(progress("\"token\"", "1"),
									text(simulation.awaitStreamItem(WAIT).orElseThrow()));
						}
						simulation.close();
						assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
								simulation.awaitCompletion(WAIT).orElseThrow().getReason());
						assertEquals(!reports, gate.token.get().isCanceled());
						if (reports) {
							// Server diagnostics describe the stopped listener, not the
							// simulator's separate execution generation. Observe this
							// handler directly; real-listener tests verify slot accounting.
							assertEquals(1, gate.exited.getCount(), "Detachment must leave work running.");
							assertEquals(1, gate.canceled.getCount());
							assertDoesNotThrow(() -> gate.reporter.get().report(
									McpProgressUpdate.withProgress(0.0).build()));
							assertTrue(simulation.awaitStreamItem(Duration.ZERO).isEmpty());
						} else
							assertTrue(gate.canceled.await(5, TimeUnit.SECONDS));
					} finally {
						gate.release.countDown();
					}
					assertTrue(gate.exited.await(5, TimeUnit.SECONDS));
				});
			}
	}

	private static final class Gate {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch canceled = new CountDownLatch(1);
		final CountDownLatch exited = new CountDownLatch(1);
		final AtomicReference<CancelationToken> token = new AtomicReference<>();
		final AtomicReference<McpProgressReporter> reporter = new AtomicReference<>();

		McpToolHandler<McpJsonObject> handler(boolean reports) {
			return handler(reports, false);
		}

		McpToolHandler<McpJsonObject> handler(boolean reports, boolean ignoresInterrupts) {
			return (requestContext, arguments, invocationFeatures) -> {
				token.set(invocationFeatures.getCancelationToken());
				token.get().onCancel(canceled::countDown);
				invocationFeatures.getProgressReporter().ifPresent(reporter::set);
				try {
					if (reports)
						reporter.get().report(McpProgressUpdate.withProgress(1.0).build());
					entered.countDown();
					long deadline = System.nanoTime() + WAIT.toNanos();
					boolean interrupted = false;
					try {
						while (release.getCount() != 0) {
							long remaining = deadline - System.nanoTime();
							assertTrue(remaining > 0, "The held handler exceeded its test wait bound.");
							try {
								assertTrue(release.await(remaining, TimeUnit.NANOSECONDS));
							} catch (InterruptedException exception) {
								if (!ignoresInterrupts)
									throw exception;
								interrupted = true;
							}
						}
					} finally {
						if (interrupted)
							Thread.currentThread().interrupt();
					}
					return McpCompleteResult.fromToolText("late result");
				} finally {
					exited.countDown();
				}
			};
		}
	}

	private static McpToolRegistration<McpJsonObject> tool(String name, McpToolHandler<McpJsonObject> handler) {
		return McpToolRegistration.withName(name, VERSIONS).jsonObjectArguments().handler(handler).build();
	}

	private static McpEndpoint endpoint(McpToolRegistration<?>... tools) {
		return McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion("legacy-progress", "test").build(), VERSIONS)
				.toolRegistrations(List.of(tools)).build();
	}

	private static McpServer.Builder configure(McpServer.Builder builder, McpEndpoint endpoint) {
		return builder.host(HOST).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.requestRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed())
				.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance()).allowedHosts(Set.of(HOST));
	}

	private static SimulatorConfig simulatorConfig(McpEndpoint endpoint) {
		return SimulatorConfig.builder().configureMcpServer(builder -> configure(builder.port(0), endpoint))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).lifecyclePolicy(LIFECYCLE).build();
	}

	private static Soklet managed(McpServer server, MetricsCollector metricsCollector) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector).lifecyclePolicy(LIFECYCLE).build());
	}

	private static McpJsonObject metadata() {
		return McpJsonObject.builder().put("example.test/receipt", "opaque").build();
	}

	private static void reportIfAvailable(McpInvocationFeatures invocationFeatures) {
		invocationFeatures.getProgressReporter().ifPresent(reporter ->
				reporter.report(McpProgressUpdate.withProgress(1.0).build()));
	}

	private static int port(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static McpChunkedHttpClient call(int port, McpProtocolVersion version, String method,
			String params, String idJson, String tokenJson) throws Exception {
		return McpChunkedHttpClient.postMcpMessage(port, body(method, params, idJson, tokenJson), headers(version));
	}

	private static List<McpChunkedHttpClient.RequestHeader> headers(McpProtocolVersion version) {
		return List.of(new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", version.getWireValue()));
	}

	private static Request simulationRequest(McpProtocolVersion version, String method,
			String params, String idJson, String tokenJson) {
		return Request.withPath(HttpMethod.POST, PATH).headers(Map.of("Host", Set.of(HOST + ":0"),
				"Content-Type", Set.of("application/json"), "Accept", Set.of("application/json, text/event-stream"),
				"MCP-Protocol-Version", Set.of(version.getWireValue())))
				.body(body(method, params, idJson, tokenJson).getBytes(StandardCharsets.UTF_8)).build();
	}

	private static String toolParams(String name) {
		return "\"name\":\"" + name + "\",\"arguments\":{}";
	}

	private static String body(String method, String params, String idJson, String tokenJson) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"method\":\"" + method + "\",\"params\":{"
				+ params + (tokenJson == null ? "" : ",\"_meta\":{\"progressToken\":" + tokenJson + "}") + "}}";
	}

	private static String progress(String tokenJson, String fields) {
		return "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{"
				+ "\"progressToken\":" + tokenJson + ",\"progress\":" + fields + "}}\n\n";
	}

	private static String text(McpSimulationStreamItem item) {
		return new String(item.getEncodedBytes(), StandardCharsets.UTF_8);
	}

	private static String message(String sse) {
		assertTrue(sse.startsWith("data: "), sse);
		assertTrue(sse.endsWith("\n\n"), sse);
		return sse.substring(6, sse.length() - 2);
	}

	private static void assertJsonEquals(String expected, String actual) {
		assertEquals(JSON.parse(expected), JSON.parse(actual), actual);
	}

	private static void assertSseHead(McpChunkedHttpClient.HttpResponseHead head) {
		assertEquals(200, head.status(), head.raw());
		assertEquals("text/event-stream", head.singleHeader("Content-Type"));
		assertEquals("no-store", head.singleHeader("Cache-Control"));
		assertEquals("chunked", head.singleHeader("Transfer-Encoding"));
		assertFalse(head.hasHeader("Content-Length"));
	}

	private static void awaitCleanup(McpServer server) throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while ((server.getDiagnostics().getActiveHandlerExecutions() != 0
				|| server.getDiagnostics().getActiveRequestStreams() != 0
				|| server.getDiagnostics().getRequestHandlerQueueDepth() != 0)
				&& System.nanoTime() - deadline < 0L)
			Thread.sleep(5);
		assertEquals(0, server.getDiagnostics().getActiveHandlerExecutions());
		assertEquals(0, server.getDiagnostics().getActiveRequestStreams());
		assertEquals(0, server.getDiagnostics().getRequestHandlerQueueDepth());
	}
}
