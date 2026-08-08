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

package com.soklet.internal.mcp.protocol;

import com.soklet.CancelationToken;
import com.soklet.CorsAuthorizer;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpHandlerResolver;
import com.soklet.McpImplementation;
import com.soklet.McpInputRequest;
import com.soklet.McpInputRequestDeclaration;
import com.soklet.McpInputRequiredResult;
import com.soklet.McpInputRequirement;
import com.soklet.McpJsonObject;
import com.soklet.McpMetricsEvent;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestAdmissionPolicy;
import com.soklet.McpServer;
import com.soklet.McpToolHandler;
import com.soklet.McpToolRegistration;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box real-listener coverage for public MCP progress and cooperative
 * cancelation features.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpProgressPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String ROOTS_CAPABILITY = "{\"roots\":{}}";

	@Test
	public void stringAndIntegerTokensProduceExactIsolatedMonotonicStreams()
			throws Exception {
		List<McpProgressReporter> reporters = Collections.synchronizedList(
				new ArrayList<>());
		AtomicReference<McpProgressReporter> terminalReporter =
				new AtomicReference<>();
		AtomicReference<CancelationToken> terminalToken = new AtomicReference<>();
		McpToolRegistration<McpJsonObject> progressTool = tool("progress.exact",
				(request, call, features) -> {
					CancelationToken cancelation =
							features.require(CancelationToken.class);
					Assertions.assertSame(cancelation,
							features.require(CancelationToken.class));
					Assertions.assertFalse(cancelation.isCanceled());
					terminalToken.set(cancelation);
					McpProgressReporter reporter =
							features.require(McpProgressReporter.class);
					Assertions.assertSame(reporter,
							features.find(McpProgressReporter.class).orElseThrow());
					reporters.add(reporter);
					terminalReporter.set(reporter);
					reporter.report(McpProgressUpdate.withProgress(0)
							.total(100).build());
					reporter.report(McpProgressUpdate.withProgress(50)
							.total(100).message("Halfway 世界").build());
					// Equal values are deliberately coalesced.
					reporter.report(McpProgressUpdate.withProgress(50)
							.total(100).message("not emitted").build());
					Assertions.assertThrows(IllegalArgumentException.class,
							() -> reporter.report(
									McpProgressUpdate.withProgress(49).build()));
					reporter.report(McpProgressUpdate.withProgress(100)
							.total(100).build());
					return McpCompleteResult.fromToolText("progress complete");
				});
		McpServer server = server(List.of(progressTool));

		try {
			server.start();
			int port = boundPort(server);
			assertExactProgressExchange(port, "\"string-request\"",
					"\"string-token\"", "\"string-token\"");
			assertExactProgressExchange(port, "27", "9007199254740991",
					"9007199254740991");

			Assertions.assertEquals(2, reporters.size());
			Assertions.assertNotSame(reporters.get(0), reporters.get(1),
					"Each request must own a distinct progress reporter.");

			// A retained invocation feature becomes inert after its terminal event.
			Assertions.assertDoesNotThrow(() -> terminalReporter.get().report(
					McpProgressUpdate.withProgress(101).total(101).build()));
			Assertions.assertDoesNotThrow(() -> terminalReporter.get().report(
					McpProgressUpdate.withProgress(99).build()));
			Assertions.assertDoesNotThrow(() -> terminalReporter.get().report(
					McpProgressUpdate.withProgress(100).build()));
			AtomicBoolean lateCallback = new AtomicBoolean();
			AutoCloseable lateRegistration = terminalToken.get().onCancel(
					() -> lateCallback.set(true));
			lateRegistration.close();
			Assertions.assertFalse(lateCallback.get());
			Assertions.assertFalse(terminalToken.get().isCanceled());
		} finally {
			server.stop();
		}
	}

	@Test
	public void floatingPointProgressTotalAndMessagePreserveExactWireValues()
			throws Exception {
		McpToolRegistration<McpJsonObject> progressTool = tool("progress.float",
				(request, call, features) -> {
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(12.5)
									.total(100.25)
									.message("Indexing 1/8")
									.build());
					return McpCompleteResult.fromToolText("floating progress complete");
				});
		McpServer server = server(List.of(progressTool));

		try {
			server.start();
			try (McpChunkedHttpClient client = callTool(boundPort(server),
					"\"float-request\"", "progress.float", "{}",
					"\"float-token\"")) {
				assertSseHead(client.readHead());
				Assertions.assertEquals(sse("{\"jsonrpc\":\"2.0\","
						+ "\"method\":\"notifications/progress\","
						+ "\"params\":{\"progressToken\":\"float-token\","
						+ "\"progress\":12.5,\"total\":100.25,"
						+ "\"message\":\"Indexing 1/8\"}}"),
						client.readChunkText());
				Assertions.assertEquals(sse("{\"jsonrpc\":\"2.0\","
						+ "\"id\":\"float-request\",\"result\":{"
						+ "\"content\":[{\"type\":\"text\","
						+ "\"text\":\"floating progress complete\"}],"
						+ "\"resultType\":\"complete\"}}"),
						client.readChunkText());
				Assertions.assertNull(client.readChunk());
			}
		} finally {
			server.stop();
		}
	}

	@Test
	public void noTokenKeepsReporterAbsentAndReturnsOneJsonResponse()
			throws Exception {
		AtomicReference<CancelationToken> observedToken = new AtomicReference<>();
		McpToolRegistration<McpJsonObject> tool = tool("progress.no-token",
				(request, call, features) -> {
					CancelationToken token = features.require(CancelationToken.class);
					Assertions.assertSame(token,
							features.find(CancelationToken.class).orElseThrow());
					Assertions.assertTrue(
							features.find(McpProgressReporter.class).isEmpty());
					observedToken.set(token);
					return McpCompleteResult.fromToolText("no token complete");
				});
		McpServer server = server(List.of(tool));

		try {
			server.start();
			try (McpChunkedHttpClient client = callTool(boundPort(server),
					"\"no-token\"", "progress.no-token", "{}", null)) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				assertJsonHead(head, 200);
				Assertions.assertEquals("{\"jsonrpc\":\"2.0\","
						+ "\"id\":\"no-token\",\"result\":{"
						+ "\"content\":[{\"type\":\"text\","
						+ "\"text\":\"no token complete\"}],"
						+ "\"resultType\":\"complete\"}}",
						client.readFixedBody(head));
			}
			Assertions.assertFalse(observedToken.get().isCanceled());
			Assertions.assertTrue(
					observedToken.get().getCancelationReason().isEmpty());
		} finally {
			server.stop();
		}
	}

	@Test
	public void conditionalCapabilityHoldSuppressesProgressAndPreservesTerminalChoice()
			throws Exception {
		McpInputRequestDeclaration roots = McpInputRequestDeclaration
				.fromRoots(McpInputRequirement.CONDITIONAL);
		AtomicBoolean completeReporterSuppressed = new AtomicBoolean();
		AtomicBoolean inputReporterSuppressed = new AtomicBoolean();
		McpToolRegistration<McpJsonObject> complete =
				McpToolRegistration.withName("progress.conditional-complete")
						.jsonArguments()
						.handler((request, call, features) -> {
							if (request.getClientCapabilities().supports(
									com.soklet.McpClientCapability.ROOTS)) {
								features.require(McpProgressReporter.class).report(
										McpProgressUpdate.withProgress(1).build());
							} else {
								completeReporterSuppressed.set(features
										.find(McpProgressReporter.class).isEmpty());
							}
							return McpCompleteResult.fromToolText(
									"conditional complete");
						})
						.mayRequestInput(roots)
						.build();
		McpToolRegistration<McpJsonObject> input =
				McpToolRegistration.withName("progress.conditional-input")
						.jsonArguments()
						.handler((request, call, features) -> {
							inputReporterSuppressed.set(features
									.find(McpProgressReporter.class).isEmpty());
							return McpInputRequiredResult.builder()
									.inputRequest("roots", McpInputRequest
											.fromDeclaration(roots,
													McpJsonObject.emptyInstance()))
									.build();
						})
						.mayRequestInput(roots)
						.build();
		McpServer server = server(List.of(complete, input));

		try {
			server.start();
			int port = boundPort(server);
			try (McpChunkedHttpClient client = callTool(port,
					"\"conditional-complete\"",
					"progress.conditional-complete", "{}", "\"held\"")) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				assertJsonHead(head, 200);
				Assertions.assertTrue(client.readFixedBody(head).contains(
						"\"text\":\"conditional complete\""));
			}
			Assertions.assertTrue(completeReporterSuppressed.get());

			try (McpChunkedHttpClient client = callTool(port,
					"\"conditional-input\"", "progress.conditional-input",
					"{}", "\"held-input\"")) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				assertJsonHead(head, 400);
				Assertions.assertEquals("{\"jsonrpc\":\"2.0\","
						+ "\"id\":\"conditional-input\",\"error\":{"
						+ "\"code\":-32021,"
						+ "\"message\":\"Missing required client capability\","
						+ "\"data\":{\"requiredCapabilities\":{"
						+ "\"roots\":{}}}}}", client.readFixedBody(head));
			}
			Assertions.assertTrue(inputReporterSuppressed.get());

			try (McpChunkedHttpClient client = callTool(port,
					"\"conditional-supported\"",
					"progress.conditional-complete", ROOTS_CAPABILITY,
					"\"live\"")) {
				assertSseHead(client.readHead());
				Assertions.assertEquals(sse("{\"jsonrpc\":\"2.0\","
						+ "\"method\":\"notifications/progress\","
						+ "\"params\":{\"progressToken\":\"live\","
						+ "\"progress\":1}}"), client.readChunkText());
				Assertions.assertTrue(client.readChunkText().contains(
						"\"id\":\"conditional-supported\""));
				Assertions.assertNull(client.readChunk());
			}
		} finally {
			server.stop();
		}
	}

	@Test
	public void disconnectCancelsSameFeatureInstanceAndRunsCallback()
			throws Exception {
		List<McpMetricsEvent> metrics = Collections.synchronizedList(
				new ArrayList<>());
		MetricsCollector metricsCollector = new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
				metrics.add(event);
			}
		};
		CountDownLatch callbackInvoked = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		AtomicReference<CancelationToken> observedToken = new AtomicReference<>();
		AtomicReference<McpProgressReporter> observedReporter =
				new AtomicReference<>();
		AtomicReference<StreamTerminationReason> observedReason =
				new AtomicReference<>();
		AtomicBoolean callbackSawCanceled = new AtomicBoolean();
		McpToolRegistration<McpJsonObject> tool = tool("progress.cancel",
				(request, call, features) -> {
					CancelationToken token = features.require(CancelationToken.class);
					Assertions.assertSame(token,
							features.require(CancelationToken.class));
					observedToken.set(token);
					token.onCancel(() -> {
						callbackSawCanceled.set(token.isCanceled());
						observedReason.set(token.getCancelationReason().orElse(null));
						callbackInvoked.countDown();
					});
					McpProgressReporter reporter =
							features.require(McpProgressReporter.class);
					observedReporter.set(reporter);
					reporter.report(McpProgressUpdate.withProgress(1).build());
					try {
						emergencyRelease.await();
						return McpCompleteResult.fromToolText("must not be written");
					} finally {
						handlerExited.countDown();
					}
				});
		McpServer server = server(List.of(tool));
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector)
				.build());
		McpChunkedHttpClient client = null;

		try {
			soklet.start();
			client = callTool(boundPort(server), "\"disconnect\"",
					"progress.cancel", "{}", "\"disconnect-token\"");
			assertSseHead(client.readHead());
			Assertions.assertTrue(client.readChunkText().contains(
					"\"progressToken\":\"disconnect-token\""));
			client.closeWithReset();

			Assertions.assertTrue(callbackInvoked.await(5, TimeUnit.SECONDS),
					"Disconnect did not run the public cancelation callback.");
			Assertions.assertTrue(handlerExited.await(5, TimeUnit.SECONDS),
					"Disconnect did not interrupt the application handler.");
			Assertions.assertTrue(callbackSawCanceled.get());
			Assertions.assertTrue(observedToken.get().isCanceled());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
					observedReason.get());
			Assertions.assertEquals(
					java.util.Optional.of(StreamTerminationReason.CLIENT_DISCONNECTED),
					observedToken.get().getCancelationReason());
			List<McpMetricsEvent.ProgressEmitted> progressEvents = metrics.stream()
					.filter(McpMetricsEvent.ProgressEmitted.class::isInstance)
					.map(McpMetricsEvent.ProgressEmitted.class::cast)
					.toList();
			List<McpMetricsEvent.CancelationSignaled> cancelationEvents =
					metrics.stream()
							.filter(McpMetricsEvent.CancelationSignaled.class::isInstance)
							.map(McpMetricsEvent.CancelationSignaled.class::cast)
							.toList();
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.ProgressEmitted(MCP_PATH, "tools/call")),
					progressEvents);
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.CancelationSignaled(MCP_PATH, "tools/call")),
					cancelationEvents);

			// Once canceled, monotonicity and delivery are both inert. In
			// particular, a retained reporter cannot emit another notification or
			// its corresponding accepted-delivery metric.
			Assertions.assertDoesNotThrow(() -> observedReporter.get().report(
					McpProgressUpdate.withProgress(0).build()));
			Assertions.assertDoesNotThrow(() -> observedReporter.get().report(
					McpProgressUpdate.withProgress(1).build()));
			Assertions.assertDoesNotThrow(() -> observedReporter.get().report(
					McpProgressUpdate.withProgress(2).build()));
			Assertions.assertEquals(1, metrics.stream()
					.filter(McpMetricsEvent.ProgressEmitted.class::isInstance)
					.count(), "Canceled reports must not emit or record progress.");
		} finally {
			emergencyRelease.countDown();
			if (client != null)
				client.close();
			soklet.stop();
		}
	}

	private static void assertExactProgressExchange(int port, String idJson,
			String tokenJson, String expectedTokenJson) throws Exception {
		try (McpChunkedHttpClient client = callTool(port, idJson,
				"progress.exact", "{}", tokenJson)) {
			assertSseHead(client.readHead());
			Assertions.assertEquals(progressEvent(expectedTokenJson,
					"0,\"total\":100"), client.readChunkText());
			Assertions.assertEquals(progressEvent(expectedTokenJson,
					"50,\"total\":100,\"message\":\"Halfway 世界\""),
					client.readChunkText());
			Assertions.assertEquals(progressEvent(expectedTokenJson,
					"100,\"total\":100"), client.readChunkText());
			Assertions.assertTrue(client.readChunkText().contains(
					"\"text\":\"progress complete\""));
			Assertions.assertNull(client.readChunk());
		}
	}

	private static String progressEvent(String tokenJson, String progressFields) {
		return sse("{\"jsonrpc\":\"2.0\","
				+ "\"method\":\"notifications/progress\","
				+ "\"params\":{\"progressToken\":" + tokenJson + ","
				+ "\"progress\":" + progressFields + "}}");
	}

	private static String sse(String json) {
		return "data: " + json + "\n\n";
	}

	private static McpToolRegistration<McpJsonObject> tool(String name,
			McpToolHandler<McpJsonObject> handler) {
		return McpToolRegistration.withName(name)
				.jsonArguments()
				.handler(handler)
				.build();
	}

	private static McpServer server(
			List<McpToolRegistration<?>> tools) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"progress-public-runtime-test", "3.6.0-SNAPSHOT").build())
				.tools(tools)
				.build();
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
				.requestRateLimiter(context ->
						McpRateLimitDecision.fromAllowed())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static int boundPort(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static McpChunkedHttpClient callTool(int port, String idJson,
			String toolName, String clientCapabilitiesJson, String progressTokenJson)
			throws Exception {
		String progressToken = progressTokenJson == null ? ""
				: ",\"progressToken\":" + progressTokenJson;
		String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ clientCapabilitiesJson + progressToken + "},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":{}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", "tools/call"),
				new McpChunkedHttpClient.RequestHeader("Mcp-Name", toolName)));
	}

	private static void assertSseHead(
			McpChunkedHttpClient.HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
		Assertions.assertFalse(head.hasHeader("Content-Length"));
	}

	private static void assertJsonHead(
			McpChunkedHttpClient.HttpResponseHead head, int expectedStatus) {
		Assertions.assertEquals(expectedStatus, head.status(), head.raw());
		Assertions.assertEquals("application/json",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
		Assertions.assertTrue(head.hasHeader("Content-Length"));
		Assertions.assertFalse(head.hasHeader("Transfer-Encoding"));
	}
}
