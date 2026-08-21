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
import com.soklet.McpEndpointRegistry;
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
import com.soklet.McpAdmissionController;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
				(request, arguments, features) -> {
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
					reporter.report(McpProgressUpdate.withProgress(0.0d)
							.total(100.0d).build());
					reporter.report(McpProgressUpdate.withProgress(50.0d)
							.total(100.0d).message("Halfway 世界").build());
					// Equal values are deliberately coalesced.
					reporter.report(McpProgressUpdate.withProgress(50.0d)
							.total(100.0d).message("not emitted").build());
					Assertions.assertThrows(IllegalArgumentException.class,
							() -> reporter.report(
									McpProgressUpdate.withProgress(49.0d).build()));
					reporter.report(McpProgressUpdate.withProgress(100.0d)
							.total(100.0d).build());
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
					McpProgressUpdate.withProgress(101.0d).total(101.0d).build()));
			Assertions.assertDoesNotThrow(() -> terminalReporter.get().report(
					McpProgressUpdate.withProgress(99.0d).build()));
			Assertions.assertDoesNotThrow(() -> terminalReporter.get().report(
					McpProgressUpdate.withProgress(100.0d).build()));
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
				(request, arguments, features) -> {
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
				(request, arguments, features) -> {
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
						.handler((request, arguments, features) -> {
							if (request.getClientCapabilities().supports(
									com.soklet.McpClientCapability.ROOTS)) {
								features.require(McpProgressReporter.class).report(
										McpProgressUpdate.withProgress(1.0d).build());
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
						.handler((request, arguments, features) -> {
							inputReporterSuppressed.set(features
									.find(McpProgressReporter.class).isEmpty());
							return McpInputRequiredResult.builder()
									.inputRequest("roots", McpInputRequest.fromDeclaration(
											roots,
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
		List<McpMetricsEvent> metrics = new CopyOnWriteArrayList<>();
		ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
		AtomicReference<McpProgressReporter> observedReporter =
				new AtomicReference<>();
		AtomicBoolean monitorProbed = new AtomicBoolean();
		AtomicReference<Throwable> monitorProbeFailure = new AtomicReference<>();
		AtomicInteger activeMetricCallbacks = new AtomicInteger();
		AtomicInteger maximumConcurrentMetricCallbacks = new AtomicInteger();
		CountDownLatch requestFinishedMetric = new CountDownLatch(1);
		CountDownLatch handlerFinishedMetric = new CountDownLatch(1);
		MetricsCollector metricsCollector = new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
				int active = activeMetricCallbacks.incrementAndGet();
				maximumConcurrentMetricCallbacks.accumulateAndGet(active, Math::max);
				try {
					metrics.add(event);
					if (event instanceof McpMetricsEvent.ProgressEmitted
							&& monitorProbed.compareAndSet(false, true)) {
						try {
							Future<?> probe = probeExecutor.submit(() -> {
								McpProgressReporter reporter = observedReporter.get();
								if (reporter == null)
									throw new AssertionError(
											"The progress reporter was not published.");
								synchronized (reporter) {
									// Acquiring the implementation monitor is the probe.
								}
							});
							probe.get(2, TimeUnit.SECONDS);
						} catch (Throwable throwable) {
							monitorProbeFailure.compareAndSet(null, throwable);
						}
						McpProgressReporter reporter = observedReporter.get();
						if (reporter == null)
							throw new AssertionError(
									"The progress reporter was not published.");
						reporter.report(McpProgressUpdate.withProgress(2.0d).build());
					}
					if (event instanceof McpMetricsEvent.RequestFinished)
						requestFinishedMetric.countDown();
					if (event instanceof McpMetricsEvent.HandlerExecutionFinished)
						handlerFinishedMetric.countDown();
				} finally {
					activeMetricCallbacks.decrementAndGet();
				}
			}
		};
		CountDownLatch callbackInvoked = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		AtomicReference<CancelationToken> observedToken = new AtomicReference<>();
		AtomicReference<StreamTerminationReason> observedReason =
				new AtomicReference<>();
		AtomicBoolean callbackSawCanceled = new AtomicBoolean();
		McpToolRegistration<McpJsonObject> tool = tool("progress.cancel",
				(request, arguments, features) -> {
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
					reporter.report(McpProgressUpdate.withProgress(1.0d).build());
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
			Assertions.assertTrue(client.readChunkText().contains(
					"\"progress\":2"));
			client.closeWithReset();

			Assertions.assertTrue(callbackInvoked.await(5, TimeUnit.SECONDS),
					"Disconnect did not run the public cancelation callback.");
			Assertions.assertTrue(handlerExited.await(5, TimeUnit.SECONDS),
					"Disconnect did not interrupt the application handler.");
			Assertions.assertTrue(requestFinishedMetric.await(5, TimeUnit.SECONDS),
					"Disconnect did not deliver the request-finished metric.");
			Assertions.assertTrue(handlerFinishedMetric.await(5, TimeUnit.SECONDS),
					"The interrupted handler did not release its metric slot.");
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
					McpMetricsEvent.progressEmitted(MCP_PATH, "tools/call"),
					McpMetricsEvent.progressEmitted(MCP_PATH, "tools/call")),
					progressEvents);
			Assertions.assertEquals(List.of(
					McpMetricsEvent.cancelationSignaled(MCP_PATH, "tools/call")),
					cancelationEvents);
			List<McpMetricsEvent> eventSnapshot = List.copyOf(metrics);
			List<Class<?>> deterministicPrefixTypes = eventSnapshot.stream()
					.filter(event -> event instanceof McpMetricsEvent.ServerStarted
							|| event instanceof McpMetricsEvent.RequestStarted
							|| event instanceof McpMetricsEvent.HandlerExecutionStarted
							|| event instanceof McpMetricsEvent.RequestStreamOpened
							|| event instanceof McpMetricsEvent.ProgressEmitted)
					.map(Object::getClass)
					.toList();
			Assertions.assertEquals(List.of(
					McpMetricsEvent.ServerStarted.class,
					McpMetricsEvent.RequestStarted.class,
					McpMetricsEvent.HandlerExecutionStarted.class,
					McpMetricsEvent.RequestStreamOpened.class,
					McpMetricsEvent.ProgressEmitted.class,
					McpMetricsEvent.ProgressEmitted.class),
					deterministicPrefixTypes,
					"Startup, stream-open, and accepted progress events must retain their deterministic record order.");
			List<McpMetricsEvent.RequestStreamClosed> streamClosedEvents =
					eventSnapshot.stream()
					.filter(McpMetricsEvent.RequestStreamClosed.class::isInstance)
					.map(McpMetricsEvent.RequestStreamClosed.class::cast)
					.toList();
			List<McpMetricsEvent.RequestFinished> requestFinishedEvents =
					eventSnapshot.stream()
					.filter(McpMetricsEvent.RequestFinished.class::isInstance)
					.map(McpMetricsEvent.RequestFinished.class::cast)
					.toList();
			Assertions.assertEquals(1, streamClosedEvents.size(),
					"Disconnect must close the request stream exactly once.");
			Assertions.assertEquals(1, requestFinishedEvents.size(),
					"Disconnect must finish request observation exactly once.");
			McpMetricsEvent.RequestStreamClosed streamClosed =
					streamClosedEvents.get(0);
			McpMetricsEvent.RequestFinished requestFinished =
					requestFinishedEvents.get(0);
			Assertions.assertEquals(
					com.soklet.McpStreamTerminationReason.CLIENT_DISCONNECTED,
					streamClosed.getReason());
			Assertions.assertEquals(com.soklet.McpRequestOutcome.CLIENT_DISCONNECTED,
					requestFinished.getOutcome());
			int streamClosedIndex = eventSnapshot.indexOf(streamClosed);
			int requestFinishedIndex = eventSnapshot.indexOf(requestFinished);
			Assertions.assertTrue(streamClosedIndex < requestFinishedIndex,
					"Request-stream closure must precede terminal request observation.");
			var diagnostics = server.getDiagnostics();
			Assertions.assertEquals(0,
					diagnostics.getActiveHandlerExecutions());
			Assertions.assertEquals(0, diagnostics.getQueuedRequests());
			Assertions.assertEquals(0, diagnostics.getActiveRequestStreams());
			Assertions.assertEquals(0, diagnostics.getActiveSubscriptions());
			Assertions.assertEquals(1,
					maximumConcurrentMetricCallbacks.get(),
					"A reentrant progress report must queue instead of recursively invoking the collector.");
			Assertions.assertNull(monitorProbeFailure.get(),
					"ProgressEmitted was delivered while the progress reporter monitor was held.");

			// Once canceled, monotonicity and delivery are both inert. In
			// particular, a retained reporter cannot emit another notification or
			// its corresponding accepted-delivery metric.
			Assertions.assertDoesNotThrow(() -> observedReporter.get().report(
					McpProgressUpdate.withProgress(0.0d).build()));
			Assertions.assertDoesNotThrow(() -> observedReporter.get().report(
					McpProgressUpdate.withProgress(1.0d).build()));
			Assertions.assertDoesNotThrow(() -> observedReporter.get().report(
					McpProgressUpdate.withProgress(2.0d).build()));
			Assertions.assertEquals(2, metrics.stream()
					.filter(McpMetricsEvent.ProgressEmitted.class::isInstance)
					.count(), "Canceled reports must not emit or record progress.");
		} finally {
			emergencyRelease.countDown();
			if (client != null)
				client.close();
			soklet.stop();
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void progressEnqueueWinsBeforeMappedErrorTerminal() throws Exception {
		assertProgressErrorWinner(true);
	}

	@Test
	public void mappedErrorTerminalWinsAfterProgressEligibility() throws Exception {
		assertProgressErrorWinner(false);
	}

	private static void assertProgressErrorWinner(boolean progressWins)
			throws Exception {
		String id = progressWins ? "progress-wins" : "error-wins";
		String secret = "application-secret-" + id;
		List<McpMetricsEvent> metrics = new CopyOnWriteArrayList<>();
		CountDownLatch requestFinished = new CountDownLatch(1);
		CountDownLatch lateEnqueueEntered = new CountDownLatch(1);
		CountDownLatch releaseLateEnqueue = new CountDownLatch(1);
		CountDownLatch lateReportFinished = new CountDownLatch(1);
		AtomicInteger enqueueAttempts = new AtomicInteger();
		AtomicBoolean lateReporterInterrupted = new AtomicBoolean();
		AtomicReference<Throwable> lateReportFailure = new AtomicReference<>();
		AtomicReference<Future<?>> lateReport = new AtomicReference<>();
		ExecutorService lateReporter = Executors.newSingleThreadExecutor();
		MetricsCollector metricsCollector = new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
				metrics.add(event);
				if (event instanceof McpMetricsEvent.RequestFinished)
					requestFinished.countDown();
			}
		};
		McpRequestSseStream.TestHooks hooks = new McpRequestSseStream.TestHooks() {
			@Override
			public void beforeTerminalReservation() {
			}

			@Override
			public void beforeMessageEnqueue() {
				if (enqueueAttempts.incrementAndGet() != 1)
					return;
				lateEnqueueEntered.countDown();
				awaitUninterruptibly(releaseLateEnqueue);
			}
		};
		McpToolRegistration<McpJsonObject> tool = tool("progress." + id,
				(request, arguments, features) -> {
					McpProgressReporter reporter =
							features.require(McpProgressReporter.class);
					reporter.report(McpProgressUpdate.withProgress(1.0d).build());
					lateReport.set(lateReporter.submit(() -> {
						try {
							reporter.report(McpProgressUpdate.withProgress(2.0d)
									.build());
						} catch (Throwable throwable) {
							lateReportFailure.set(throwable);
						} finally {
							lateReporterInterrupted.set(
									Thread.currentThread().isInterrupted());
							lateReportFinished.countDown();
						}
					}));
					if (!lateEnqueueEntered.await(5, TimeUnit.SECONDS))
						throw new AssertionError(
								"Late progress did not reach the enqueue boundary.");
					if (progressWins) {
						releaseLateEnqueue.countDown();
						if (!lateReportFinished.await(5, TimeUnit.SECONDS))
							throw new AssertionError(
									"Progress did not win the enqueue boundary.");
					}
					throw new IllegalStateException(secret);
				});
		McpServer server = server(List.of(tool));
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector)
				.build());
		McpChunkedHttpClient client = null;

		try {
			McpRequestSseStream.setTestHooks(hooks);
			soklet.start();
			String idJson = "\"" + id + "\"";
			String tokenJson = "\"" + id + "-token\"";
			client = callTool(boundPort(server), idJson,
					"progress." + id, "{}", tokenJson);
			assertSseHead(client.readHead());
			Assertions.assertEquals(progressEvent(tokenJson, "1"),
					client.readChunkText());
			if (progressWins) {
				Assertions.assertEquals(progressEvent(tokenJson, "2"),
						client.readChunkText());
			}
			String terminal = sse("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
					+ "\",\"error\":{\"code\":-32603,"
					+ "\"message\":\"Internal error\"}}");
			String terminalFrame = client.readChunkText();
			Assertions.assertEquals(terminal, terminalFrame);
			Assertions.assertFalse(terminalFrame.contains(secret), terminalFrame);
			if (!progressWins)
				releaseLateEnqueue.countDown();
			Assertions.assertTrue(lateReportFinished.await(5, TimeUnit.SECONDS),
					"The late progress report remained blocked.");
			Future<?> report = lateReport.get();
			Assertions.assertNotNull(report);
			report.get(5, TimeUnit.SECONDS);
			Assertions.assertNull(lateReportFailure.get());
			Assertions.assertEquals(1, enqueueAttempts.get(),
					"Only the late report traverses the existing-stream enqueue path.");
			Assertions.assertEquals(!progressWins,
					lateReporterInterrupted.get(),
					"Only a terminal-owned late enqueue restores interruption.");
			Assertions.assertNull(client.readChunk(),
					"No progress frame may follow the mapped terminal error.");

			Assertions.assertTrue(requestFinished.await(5, TimeUnit.SECONDS),
					"The mapped-error request did not finish observation.");
			awaitPublicCleanup(server);
			Assertions.assertEquals(progressWins ? 2L : 1L, metrics.stream()
					.filter(McpMetricsEvent.ProgressEmitted.class::isInstance)
					.count());
			List<McpMetricsEvent.ProtocolError> protocolErrors = metrics.stream()
					.filter(McpMetricsEvent.ProtocolError.class::isInstance)
					.map(McpMetricsEvent.ProtocolError.class::cast)
					.toList();
			Assertions.assertEquals(1, protocolErrors.size());
			Assertions.assertEquals(McpJsonRpcError.INTERNAL_ERROR,
					protocolErrors.get(0).getCode());
			List<McpMetricsEvent.RequestFinished> finishes = metrics.stream()
					.filter(McpMetricsEvent.RequestFinished.class::isInstance)
					.map(McpMetricsEvent.RequestFinished.class::cast)
					.toList();
			Assertions.assertEquals(1, finishes.size());
			Assertions.assertEquals(com.soklet.McpRequestOutcome.INTERNAL_ERROR,
					finishes.get(0).getOutcome());
			List<McpMetricsEvent.RequestStreamClosed> streamCloses = metrics.stream()
					.filter(McpMetricsEvent.RequestStreamClosed.class::isInstance)
					.map(McpMetricsEvent.RequestStreamClosed.class::cast)
					.toList();
			Assertions.assertEquals(1, streamCloses.size());
			Assertions.assertEquals(
					com.soklet.McpStreamTerminationReason.COMPLETED,
					streamCloses.get(0).getReason());
			Assertions.assertTrue(metrics.indexOf(streamCloses.get(0))
					< metrics.indexOf(finishes.get(0)));
			Assertions.assertTrue(metrics.indexOf(protocolErrors.get(0))
					< metrics.indexOf(finishes.get(0)));
			List<Class<?>> requestTerminalOrder = metrics.stream()
					.filter(event -> event instanceof McpMetricsEvent.ProgressEmitted
							|| event instanceof McpMetricsEvent.ProtocolError
							|| event instanceof McpMetricsEvent.RequestStreamClosed
							|| event instanceof McpMetricsEvent.RequestFinished)
					.map(Object::getClass)
					.toList();
			List<Class<?>> expectedOrder = progressWins ? List.of(
					McpMetricsEvent.ProgressEmitted.class,
					McpMetricsEvent.ProgressEmitted.class,
					McpMetricsEvent.ProtocolError.class,
					McpMetricsEvent.RequestStreamClosed.class,
					McpMetricsEvent.RequestFinished.class) : List.of(
					McpMetricsEvent.ProgressEmitted.class,
					McpMetricsEvent.ProtocolError.class,
					McpMetricsEvent.RequestStreamClosed.class,
					McpMetricsEvent.RequestFinished.class);
			Assertions.assertEquals(expectedOrder, requestTerminalOrder,
					"Accepted progress and terminal events must retain request FIFO.");
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveHandlerExecutions());
			Assertions.assertEquals(0, server.getDiagnostics().getQueuedRequests());
			Assertions.assertEquals(0,
					server.getDiagnostics().getActiveRequestStreams());
		} finally {
			releaseLateEnqueue.countDown();
			McpRequestSseStream.setTestHooks(null);
			if (client != null)
				client.close();
			soklet.stop();
			lateReporter.shutdownNow();
			Assertions.assertTrue(lateReporter.awaitTermination(
					5, TimeUnit.SECONDS));
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

	private static void awaitPublicCleanup(McpServer server)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while ((server.getDiagnostics().getActiveHandlerExecutions() != 0
				|| server.getDiagnostics().getQueuedRequests() != 0
				|| server.getDiagnostics().getActiveRequestStreams() != 0)
				&& System.nanoTime() - deadline < 0L)
			Thread.sleep(5L);
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try {
				latch.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
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
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context ->
						McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
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
