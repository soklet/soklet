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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box public coverage for asynchronous, off-network MCP simulation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpSimulatorPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	public void startMcpRequestRejectsMissingServerConfiguration() {
		SokletConfig noMcp = SokletConfig.withHttpServer(
				HttpServer.withPort(0).build()).build();
		Request request = request("missing-server", "complete", null,
				LOOPBACK + ":0", Optional.empty());
		AtomicReference<Simulator> escaped = new AtomicReference<>();

		Soklet.runSimulator(noMcp, simulator -> {
			escaped.set(simulator);
			Assertions.assertThrows(IllegalStateException.class,
					() -> simulator.startMcpRequest(request));
		});
		Assertions.assertThrows(IllegalStateException.class,
				() -> escaped.get().startMcpRequest(request));
	}

	@Test
	public void simulatorStartsRequestAgainstConfiguredMcpServer() {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		AtomicReference<Request> admittedRequest = new AtomicReference<>();
		RecordingMetrics metrics = new RecordingMetrics();
		ServerLifecycleProbe lifecycle = new ServerLifecycleProbe();
		McpToolRegistration<McpJsonObject> tool = tool("blocking",
				(request, arguments, features) -> {
					handlerEntered.countDown();
					awaitLatch(releaseHandler);
					return McpCompleteResult.fromToolText("released");
				});
		McpServer server = server(List.of(tool), context -> {
			admittedRequest.set(context.getRequest());
			return McpAdmissionDecision.accepted();
		});
		SokletConfig config = config(server, metrics, lifecycle);
		String origin = "https://simulator.example";
		Request request = request("configured-server", "blocking", null,
				LOOPBACK + ":0", Optional.of(origin));

		try {
			Soklet.runSimulator(config, simulator -> {
				assertStoppedDiagnostics(server);
				Assertions.assertFalse(server.isStarted());
				McpSimulation missingHost = simulator.startMcpRequest(request(
						"missing-host", "blocking", null, null, Optional.empty()));
				Assertions.assertEquals(421,
						awaitResponse(missingHost).getStatusCode());
				awaitCompletion(missingHost);
				McpSimulation missingLiteralPort = simulator.startMcpRequest(request(
						"missing-literal-port", "blocking", null, LOOPBACK,
						Optional.empty()));
				Assertions.assertEquals(421,
						awaitResponse(missingLiteralPort).getStatusCode());
				awaitCompletion(missingLiteralPort);
				Assertions.assertNull(admittedRequest.get(),
						"Simulator mode must not inject or repair the Host header.");
				McpSimulation simulation = simulator.startMcpRequest(request);
				Assertions.assertTrue(awaitLatch(handlerEntered));
				assertStoppedDiagnostics(server);
				Assertions.assertFalse(server.isStarted());
				releaseHandler.countDown();
				McpSimulationResponse response = awaitResponse(simulation);
				Assertions.assertEquals(200, response.getStatusCode());
				Assertions.assertEquals(McpSimulationBodyMode.JSON,
						response.getBodyMode());
				Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
						awaitCompletion(simulation).getReason());
			});
		} finally {
			releaseHandler.countDown();
		}

		Request observed = admittedRequest.get();
		Assertions.assertNotNull(observed);
		Assertions.assertEquals(Set.of(LOOPBACK + ":0"),
				observed.getHeaders().get("Host"));
		Assertions.assertEquals(Set.of(origin),
				observed.getHeaders().get("Origin"));
		Assertions.assertEquals(Set.of("canary-value"),
				observed.getHeaders().get("X-Simulator-Canary"));
		Assertions.assertArrayEquals(request.getBody().orElseThrow(),
				observed.getBody().orElseThrow());
		Assertions.assertTrue(metrics.awaitRequestFinished());
		Assertions.assertTrue(metrics.events().stream().noneMatch(event ->
				event instanceof McpMetricsEvent.ServerStarted
						|| event instanceof McpMetricsEvent.ServerStopped
						|| event instanceof McpMetricsEvent.ConnectionAccepted
						|| event instanceof McpMetricsEvent.ConnectionRejected
						|| event instanceof McpMetricsEvent.TransportFailure),
				metrics.events().toString());
		Assertions.assertEquals(0, lifecycle.serverCallbacks());
		assertStoppedDiagnostics(server);
	}

	@Test
	public void defaultLoopbackHostPolicyRequiresLiteralConfiguredPortZero() {
		AtomicInteger handlerCalls = new AtomicInteger();
		McpToolRegistration<McpJsonObject> tool = tool("default-loopback",
				(request, arguments, features) -> {
					handlerCalls.incrementAndGet();
					return McpCompleteResult.fromToolText("default host accepted");
				});
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"simulator-default-host-test", "3.6.0-SNAPSHOT").build())
				.tool(tool)
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.build();
		SokletConfig config = config(server, MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance());

		Soklet.runSimulator(config, simulator -> {
			McpSimulation missingConfiguredPort = simulator.startMcpRequest(request(
					"default-host-no-port", "default-loopback", null, LOOPBACK,
					Optional.empty()));
			Assertions.assertEquals(421,
					awaitResponse(missingConfiguredPort).getStatusCode());
			awaitCompletion(missingConfiguredPort);

			McpSimulation accepted = simulator.startMcpRequest(request(
					"default-host-literal-port", "default-loopback", null,
					LOOPBACK + ":0", Optional.empty()));
			Assertions.assertEquals(200, awaitResponse(accepted).getStatusCode());
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitCompletion(accepted).getReason());
		});

		Assertions.assertEquals(1, handlerCalls.get());
		Assertions.assertFalse(server.isStarted());
		assertStoppedDiagnostics(server);
	}

	@Test
	public void synchronousJsonSimulationUsesRealProtocolLifecycleMetricsAndBodyMode() {
		RecordingMetrics metrics = new RecordingMetrics();
		McpServer server = server(List.of(tool("complete",
				(request, arguments, features) ->
						McpCompleteResult.fromToolText("json complete"))));
		SokletConfig config = config(server, metrics,
				LifecycleObserver.defaultInstance());
		Request request = request("json-response", "complete", null,
				LOOPBACK + ":0", Optional.empty());
		AtomicInteger exactBodySize = new AtomicInteger();

		Soklet.runSimulator(config, simulator -> {
			McpSimulation baseline = simulator.startMcpRequest(request);
			McpSimulationResponse response = awaitResponse(baseline);
			Assertions.assertEquals(200, response.getStatusCode());
			Assertions.assertEquals(McpSimulationBodyMode.JSON,
					response.getBodyMode());
			byte[] body = response.getBody().orElseThrow();
			exactBodySize.set(body.length);
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\","
					+ "\"id\":\"json-response\",\"result\":{"
					+ "\"content\":[{\"type\":\"text\","
					+ "\"text\":\"json complete\"}],"
					+ "\"resultType\":\"complete\"}}",
					new String(body, StandardCharsets.UTF_8));
			byte original = body[0];
			body[0] ^= 1;
			Assertions.assertEquals(original,
					response.getBody().orElseThrow()[0],
					"Response bodies must be defensively copied.");
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitCompletion(baseline).getReason());

			McpSimulation exact = simulator.startMcpRequest(request,
					McpSimulationOptions.builder()
							.maximumCapturedBytes(exactBodySize.get()).build());
			Assertions.assertEquals(exactBodySize.get(),
					awaitResponse(exact).getBody().orElseThrow().length,
					"The exact JSON-body byte bound must be inclusive.");
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitCompletion(exact).getReason());

			McpSimulation overflow = simulator.startMcpRequest(request,
					McpSimulationOptions.builder()
							.maximumCapturedBytes(exactBodySize.get() - 1).build());
			McpSimulationResponse overflowHead = awaitResponse(overflow);
			Assertions.assertEquals(200, overflowHead.getStatusCode());
			Assertions.assertEquals(McpSimulationBodyMode.JSON,
					overflowHead.getBodyMode());
			Assertions.assertTrue(overflowHead.getBody().isEmpty());
			McpSimulationCompletion overflowCompletion =
					awaitCompletion(overflow);
			Assertions.assertEquals(
					McpStreamTerminationReason.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
					overflowCompletion.getReason());
			Assertions.assertTrue(overflowCompletion.getTerminalMessage().isEmpty());
		});
		Assertions.assertTrue(metrics.awaitRequestFinished());
	}

	@Test
	public void malformedAndRejectedSimulationsPreserveProtocolPrecedenceWithoutAdmission() {
		AtomicInteger admissionCalls = new AtomicInteger();
		RecordingMetrics metrics = new RecordingMetrics(2);
		McpAdmissionRejection rejection = McpAdmissionRejection
				.withStatusCodeAndError(401, McpJsonRpcError.fromApplication(1_001,
						"Simulator admission rejected"))
				.header("WWW-Authenticate", "Bearer realm=soklet-mcp-simulator")
				.build();
		McpServer server = server(List.of(tool("complete",
				(request, arguments, features) ->
						McpCompleteResult.fromToolText("must not run"))), context -> {
			admissionCalls.incrementAndGet();
			return McpAdmissionDecision.rejected(rejection);
		});
		SokletConfig config = config(server, metrics,
				LifecycleObserver.defaultInstance());

		Soklet.runSimulator(config, simulator -> {
			McpSimulation malformed = simulator.startMcpRequest(
					malformedRequest("{"));
			McpSimulationResponse malformedResponse = awaitResponse(malformed);
			Assertions.assertEquals(400, malformedResponse.getStatusCode());
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\",\"error\":{"
					+ "\"code\":-32700,\"message\":\"Parse error\"}}",
					new String(malformedResponse.getBody().orElseThrow(),
							StandardCharsets.UTF_8));
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitCompletion(malformed).getReason());
			Assertions.assertEquals(0, admissionCalls.get(),
					"Malformed input must fail before admission controller evaluation.");

			McpSimulation rejected = simulator.startMcpRequest(request(
					"admission-rejected", "complete", null, LOOPBACK + ":0",
					Optional.empty()));
			McpSimulationResponse rejectedResponse = awaitResponse(rejected);
			Assertions.assertEquals(401, rejectedResponse.getStatusCode());
			Assertions.assertEquals(Set.of("Bearer realm=soklet-mcp-simulator"),
					rejectedResponse.getHeaders().get("WWW-Authenticate"));
			String rejectedBody = new String(
					rejectedResponse.getBody().orElseThrow(), StandardCharsets.UTF_8);
			Assertions.assertTrue(rejectedBody.contains(
					"\"id\":\"admission-rejected\""), rejectedBody);
			Assertions.assertTrue(rejectedBody.contains("\"code\":1001"),
					rejectedBody);
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitCompletion(rejected).getReason());
		});

		Assertions.assertTrue(metrics.awaitRequestRejections());
		Assertions.assertEquals(1, admissionCalls.get());
		Assertions.assertEquals(List.of(
				McpMetricsEvent.requestAccepted(),
				McpMetricsEvent.protocolError(-32_700),
				McpMetricsEvent.requestRejected(),
				McpMetricsEvent.requestAccepted(),
				McpMetricsEvent.requestRejected()),
				metrics.events().stream()
						.filter(event -> event instanceof McpMetricsEvent.RequestAccepted
								|| event instanceof McpMetricsEvent.ProtocolError
								|| event instanceof McpMetricsEvent.RequestRejected)
						.toList());
		Assertions.assertTrue(metrics.events().stream().noneMatch(event ->
				event instanceof McpMetricsEvent.RequestStarted
						|| event instanceof McpMetricsEvent.RequestFinished));
	}

	@Test
	public void multiRoundTripSimulationContinuesInputRequiredStateToDistinctCompletedRequest() {
		String requestState = "simulator-mrtr-state-v1";
		List<McpRequestContext> handlerContexts = new CopyOnWriteArrayList<>();
		AtomicInteger handlerCalls = new AtomicInteger();
		RecordingMetrics metrics = new RecordingMetrics(0, 0, 2);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("multi-round-trip")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerContexts.add(request);
					if (handlerCalls.incrementAndGet() == 1) {
						Assertions.assertEquals(McpRequestId.fromString("mrtr-initial"),
								request.getRequestId().orElseThrow());
						Assertions.assertTrue(request.getRequestState().isEmpty());
						return McpInputRequiredResult.builder()
								.applicationRequestState(requestState)
								.build();
					}
					Assertions.assertEquals(McpRequestId.fromString("mrtr-continued"),
							request.getRequestId().orElseThrow());
					McpApplicationRequestState continuedState =
							Assertions.assertInstanceOf(
									McpApplicationRequestState.class,
									request.getRequestState().orElseThrow());
					Assertions.assertEquals(requestState, continuedState.getValue());
					return McpCompleteResult.fromToolText("continued complete");
				})
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED)
				.build();
		McpServer server = server(List.of(tool));
		SokletConfig config = config(server, metrics,
				LifecycleObserver.defaultInstance());

		Soklet.runSimulator(config, simulator -> {
			McpSimulation initial = simulator.startMcpRequest(request(
					"mrtr-initial", "multi-round-trip", null, LOOPBACK + ":0",
					Optional.empty()));
			McpSimulationResponse initialResponse = awaitResponse(initial);
			Assertions.assertEquals(McpSimulationBodyMode.JSON,
					initialResponse.getBodyMode());
			String initialBody = new String(initialResponse.getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			Assertions.assertTrue(initialBody.contains("\"id\":\"mrtr-initial\""),
					initialBody);
			Assertions.assertTrue(initialBody.contains(
					"\"resultType\":\"input_required\""), initialBody);
			Assertions.assertTrue(initialBody.contains(
					"\"requestState\":\"" + requestState + "\""), initialBody);
			McpSimulationCompletion initialCompletion = awaitCompletion(initial);
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					initialCompletion.getReason());
			Assertions.assertTrue(initialCompletion.getTerminalMessage().isEmpty());
			Assertions.assertTrue(pollNextItem(initial, Duration.ZERO).isEmpty());

			McpSimulation continued = simulator.startMcpRequest(requestWithState(
					"mrtr-continued", "multi-round-trip", requestState));
			McpSimulationResponse continuedResponse = awaitResponse(continued);
			Assertions.assertEquals(McpSimulationBodyMode.JSON,
					continuedResponse.getBodyMode());
			String continuedBody = new String(
					continuedResponse.getBody().orElseThrow(), StandardCharsets.UTF_8);
			Assertions.assertTrue(continuedBody.contains(
					"\"id\":\"mrtr-continued\""), continuedBody);
			Assertions.assertFalse(continuedBody.contains("\"id\":\"mrtr-initial\""),
					continuedBody);
			Assertions.assertTrue(continuedBody.contains(
					"\"resultType\":\"complete\""), continuedBody);
			Assertions.assertTrue(continuedBody.contains("continued complete"),
					continuedBody);
			McpSimulationCompletion continuedCompletion = awaitCompletion(continued);
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					continuedCompletion.getReason());
			Assertions.assertTrue(continuedCompletion.getTerminalMessage().isEmpty());
			Assertions.assertTrue(pollNextItem(continued, Duration.ZERO).isEmpty());
		});

		Assertions.assertTrue(metrics.awaitRequestFinished());
		Assertions.assertEquals(2, handlerCalls.get());
		Assertions.assertEquals(2, handlerContexts.size());
		Assertions.assertEquals(List.of(
				McpRequestOutcome.INPUT_REQUIRED, McpRequestOutcome.COMPLETE),
				metrics.events().stream()
						.filter(McpMetricsEvent.RequestFinished.class::isInstance)
						.map(McpMetricsEvent.RequestFinished.class::cast)
						.map(McpMetricsEvent.RequestFinished::getOutcome)
						.toList());
	}

	@Test
	public void mcpSimulationBuffersStreamItemsAndClosesExplicitly() {
		McpServer server = server(List.of(tool("progress",
				(request, arguments, features) -> {
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					return McpCompleteResult.fromToolText("stream complete");
				})));
		SokletConfig config = config(server, MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance());
		Request request = request("stream-response", "progress",
				"\"sim-token\"", LOOPBACK + ":0", Optional.empty());

		Soklet.runSimulator(config, simulator -> {
			try (McpSimulation simulation = simulator.startMcpRequest(request)) {
				McpSimulationResponse response = awaitResponse(simulation);
				Assertions.assertEquals(200, response.getStatusCode());
				Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
						response.getBodyMode());
				Assertions.assertTrue(response.getBody().isEmpty());

				McpSimulationStreamItem progress = nextItem(simulation);
				Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE,
						progress.getType());
				Assertions.assertEquals("data: {\"jsonrpc\":\"2.0\","
						+ "\"method\":\"notifications/progress\","
						+ "\"params\":{\"progressToken\":\"sim-token\","
						+ "\"progress\":1}}\n\n",
						new String(progress.getEncodedBytes(), StandardCharsets.UTF_8));

				McpSimulationStreamItem terminal = nextItem(simulation);
				Assertions.assertEquals("data: {\"jsonrpc\":\"2.0\","
						+ "\"id\":\"stream-response\",\"result\":{"
						+ "\"content\":[{\"type\":\"text\","
						+ "\"text\":\"stream complete\"}],"
						+ "\"resultType\":\"complete\"}}\n\n",
						new String(terminal.getEncodedBytes(), StandardCharsets.UTF_8));
				McpSimulationCompletion completion = awaitCompletion(simulation);
				Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
						completion.getReason());
				Assertions.assertTrue(completion.getTerminalMessage().isPresent());
				assertSameJsonRpcId(terminal.getMessage().orElseThrow(),
						completion.getTerminalMessage().orElseThrow());
				Assertions.assertTrue(pollNextItem(simulation,
						Duration.ZERO).isEmpty());
				Assertions.assertTrue(simulation.isComplete());
				simulation.cancel();
				simulation.close();
				Assertions.assertEquals(completion.getReason(),
						awaitCompletion(simulation).getReason());
			}
		});
	}

	@Test
	public void simulatorRepresentsEventStreamAsOpenMcpSimulation() {
		CountDownLatch progressEmitted = new CountDownLatch(1);
		CountDownLatch canceled = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> cancelationReason =
				new AtomicReference<>();
		McpServer server = server(List.of(tool("open-stream",
				(request, arguments, features) -> {
					CancelationToken token = features.require(CancelationToken.class);
					token.onCancel(() -> {
						cancelationReason.set(token.getCancelationReason().orElse(null));
						canceled.countDown();
					});
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					progressEmitted.countDown();
					awaitLatch(canceled);
					return McpCompleteResult.fromToolText("late terminal");
				})));
		SokletConfig config = config(server, MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance());
		Request request = request("open-stream", "open-stream",
				"\"open-token\"", LOOPBACK + ":0", Optional.empty());

		Soklet.runSimulator(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(request);
			Assertions.assertTrue(awaitLatch(progressEmitted));
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					awaitResponse(simulation).getBodyMode());
			Assertions.assertTrue(nextItem(simulation).getMessage().isPresent());
			Assertions.assertFalse(simulation.isComplete());
			simulation.close();
			Assertions.assertTrue(awaitLatch(canceled));
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
					cancelationReason.get());
			Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
					awaitCompletion(simulation).getReason());
			simulation.close();
			simulation.cancel();
		});
	}

	@Test
	public void subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder() {
		McpLocalSubscriptionEventPublisher publisher =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		RecordingMetrics metrics = new RecordingMetrics(0, 1);
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationTypes(EnumSet.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"simulator-subscription-test", "3.6.0-SNAPSHOT").build())
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("https://example.com/simulator-resource"),
						"Simulator resource")
						.handler((resourceRequest, read, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(read.getUri(),
																"simulated")
														.build())
												.build()))
						.build())
				.subscriptions(subscriptions)
				.build();
		McpServer server = baseServerBuilder(List.of(endpoint),
				McpAdmissionController.acceptAllInstance(),
				Duration.ofMillis(250)).build();
		SokletConfig config = config(server, metrics,
				LifecycleObserver.defaultInstance());
		Request request = subscriptionRequest("subscription-sim");

		Soklet.runSimulator(config, simulator -> {
			McpSimulation simulation = simulator.startMcpRequest(request);
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					awaitResponse(simulation).getBodyMode());
			McpSimulationStreamItem acknowledgment = nextItem(simulation);
			Assertions.assertEquals("data: {\"jsonrpc\":\"2.0\","
					+ "\"method\":\"notifications/subscriptions/acknowledged\","
					+ "\"params\":{\"_meta\":{"
					+ "\"io.modelcontextprotocol/subscriptionId\":"
					+ "\"subscription-sim\"},\"notifications\":{"
					+ "\"resourcesListChanged\":true}}}\n\n",
					new String(acknowledgment.getEncodedBytes(),
							StandardCharsets.UTF_8));
			publisher.publishResourcesListChanged();
			McpSimulationStreamItem event = nextItem(simulation);
			Assertions.assertEquals("data: {\"jsonrpc\":\"2.0\","
					+ "\"method\":\"notifications/resources/list_changed\","
					+ "\"params\":{\"_meta\":{"
					+ "\"io.modelcontextprotocol/subscriptionId\":"
					+ "\"subscription-sim\"}}}\n\n",
					new String(event.getEncodedBytes(), StandardCharsets.UTF_8));
			simulation.cancel();
			Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
					awaitCompletion(simulation).getReason());
			Assertions.assertTrue(pollNextItem(simulation,
					Duration.ZERO).isEmpty());

			McpSimulation byteLimited = simulator.startMcpRequest(request,
					McpSimulationOptions.builder()
							.streamItemQueueCapacity(2)
							.maximumCapturedBytes(1)
							.build());
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					awaitResponse(byteLimited).getBodyMode());
			Assertions.assertEquals(
					McpStreamTerminationReason.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
					awaitCompletion(byteLimited).getReason());
			Assertions.assertTrue(pollNextItem(byteLimited,
					Duration.ZERO).isEmpty());
			Assertions.assertTrue(metrics.awaitSubscriptionByteLimit());
			Assertions.assertTrue(metrics.awaitSimulatorLimitRequestFinishes());
			Assertions.assertEquals(1, metrics.events().stream()
					.filter(McpMetricsEvent.RequestFinished.class::isInstance)
					.map(McpMetricsEvent.RequestFinished.class::cast)
					.filter(finished -> finished.getOutcome()
							== McpRequestOutcome.CANCELED)
					.count());
			Assertions.assertTrue(metrics.events().stream().noneMatch(eventMetric ->
					eventMetric instanceof McpMetricsEvent.ProtocolError
							|| eventMetric instanceof McpMetricsEvent.TransportFailure),
					metrics.events().toString());
		});
		Assertions.assertTrue(metrics.awaitRequestFinished());
		Assertions.assertTrue(metrics.events().stream().anyMatch(event ->
				event instanceof McpMetricsEvent.SubscriptionClosed closed
						&& closed.getReason()
						== McpStreamTerminationReason.CLIENT_DISCONNECTED));
		Assertions.assertTrue(metrics.events().stream().anyMatch(event ->
				event instanceof McpMetricsEvent.SubscriptionClosed closed
						&& closed.getReason()
						== McpStreamTerminationReason
								.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED));
	}

	@Test
	public void mcpSimulationCompletionRetainsStreamCaptureFailures() {
		CountDownLatch firstProgress = new CountDownLatch(1);
		CountDownLatch releaseSecond = new CountDownLatch(1);
		AtomicReference<CancelationToken> token = new AtomicReference<>();
		List<StreamTerminationReason> tokenReasons = new CopyOnWriteArrayList<>();
		RecordingMetrics metrics = new RecordingMetrics(0, 2);
		RuntimeException applicationFailure = new RuntimeException(
				"simulator-throwable-secret-canary");
		McpToolRegistration<McpJsonObject> limited = tool("limited",
				(request, arguments, features) -> {
					CancelationToken observed = features.require(CancelationToken.class);
					token.set(observed);
				observed.onCancel(() -> tokenReasons.add(observed
						.getCancelationReason().orElseThrow()));
					McpProgressReporter reporter =
							features.require(McpProgressReporter.class);
					reporter.report(McpProgressUpdate.withProgress(1.0d).build());
					firstProgress.countDown();
					awaitLatch(releaseSecond);
					reporter.report(McpProgressUpdate.withProgress(2.0d).build());
					return McpCompleteResult.fromToolText("not captured");
				});
		McpToolRegistration<McpJsonObject> failing = tool("failing",
				(request, arguments, features) -> {
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					throw applicationFailure;
				});
		McpServer server = server(List.of(limited, failing),
				McpAdmissionController.acceptAllInstance());
		SokletConfig config = config(server, metrics,
				LifecycleObserver.defaultInstance());
		Request request = request("item-limit", "limited", "\"item-token\"",
				LOOPBACK + ":0", Optional.empty());

		try {
			Soklet.runSimulator(config, simulator -> {
				McpSimulation simulation = simulator.startMcpRequest(request,
						McpSimulationOptions.builder()
								.streamItemQueueCapacity(1).build());
				Assertions.assertTrue(awaitLatch(firstProgress));
				releaseSecond.countDown();
				McpSimulationCompletion completion = awaitCompletion(simulation);
				Assertions.assertEquals(
						McpStreamTerminationReason.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED,
						completion.getReason());
				Assertions.assertTrue(completion.getTerminalMessage().isEmpty());
				McpSimulationStreamItem retained = nextItem(simulation);
				Assertions.assertTrue(new String(retained.getEncodedBytes(),
						StandardCharsets.UTF_8).contains("\"progress\":1"));
				Assertions.assertTrue(pollNextItem(simulation,
						Duration.ZERO).isEmpty());

				McpSimulation byteLimited = simulator.startMcpRequest(request(
						"byte-limit", "limited", "\"item-token\"",
						LOOPBACK + ":0", Optional.empty()),
						McpSimulationOptions.builder()
								.streamItemQueueCapacity(2)
								.maximumCapturedBytes(
										retained.getEncodedBytes().length)
								.build());
				Assertions.assertEquals(
						McpStreamTerminationReason
								.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
						awaitCompletion(byteLimited).getReason());
				McpSimulationStreamItem retainedAtByteLimit = nextItem(byteLimited);
				Assertions.assertTrue(new String(
						retainedAtByteLimit.getEncodedBytes(), StandardCharsets.UTF_8)
						.contains("\"progress\":1"));
				Assertions.assertTrue(pollNextItem(byteLimited,
						Duration.ZERO).isEmpty());
				Assertions.assertTrue(metrics.awaitRequestStreamByteLimit());
				Assertions.assertTrue(metrics.awaitSimulatorLimitRequestFinishes());
				Assertions.assertEquals(2, metrics.events().stream()
						.filter(McpMetricsEvent.RequestFinished.class::isInstance)
						.map(McpMetricsEvent.RequestFinished.class::cast)
						.filter(finished -> finished.getOutcome()
								== McpRequestOutcome.CANCELED)
						.count());
				Assertions.assertTrue(metrics.events().stream().noneMatch(event ->
						event instanceof McpMetricsEvent.ProtocolError
								|| event instanceof McpMetricsEvent.TransportFailure),
						metrics.events().toString());

				McpSimulation failureSimulation = simulator.startMcpRequest(request(
						"stream-failure", "failing", "\"failure-token\"",
						LOOPBACK + ":0", Optional.empty()));
				McpSimulationStreamItem failureProgress =
						nextItem(failureSimulation);
				Assertions.assertTrue(new String(failureProgress.getEncodedBytes(),
						StandardCharsets.UTF_8).contains("\"progress\":1"));
				McpSimulationStreamItem failureTerminal =
						nextItem(failureSimulation);
				Assertions.assertTrue(new String(failureTerminal.getEncodedBytes(),
						StandardCharsets.UTF_8).contains("\"code\":-32603"));
				McpSimulationCompletion failed =
						awaitCompletion(failureSimulation);
				Assertions.assertEquals(1, failed.getThrowables().size());
				Assertions.assertSame(applicationFailure,
						failed.getThrowables().get(0));
				Assertions.assertThrows(UnsupportedOperationException.class,
						() -> failed.getThrowables().add(
								new RuntimeException("mutation")));
				Assertions.assertFalse(failed.toString().contains(
						"simulator-throwable-secret-canary"));
				Assertions.assertFalse(failureTerminal.toString().contains(
						"simulator-throwable-secret-canary"));
			});
		} finally {
			releaseSecond.countDown();
		}
		Assertions.assertTrue(metrics.awaitRequestFinished());
		Assertions.assertTrue(token.get().isCanceled());
		Assertions.assertEquals(List.of(
				StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED,
				StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED), tokenReasons);
		Assertions.assertTrue(metrics.events().stream().anyMatch(event ->
				event instanceof McpMetricsEvent.RequestFinished finished
						&& finished.getOutcome() == McpRequestOutcome.CANCELED));
		Assertions.assertTrue(metrics.events().stream().anyMatch(event ->
				event instanceof McpMetricsEvent.RequestStreamClosed closed
						&& closed.getReason()
						== McpStreamTerminationReason.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED));
	}

	@Test
	public void simulatorScopeExitCancelsOutstandingRequestsAndRestoresOffNetworkState() {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch canceled = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> tokenReason = new AtomicReference<>();
		AtomicReference<McpSimulation> escapedSimulation = new AtomicReference<>();
		AtomicReference<Simulator> escapedSimulator = new AtomicReference<>();
		McpServer server = server(List.of(tool("scope-cancel",
				(request, arguments, features) -> {
					CancelationToken token = features.require(CancelationToken.class);
					token.onCancel(() -> {
						tokenReason.set(token.getCancelationReason().orElse(null));
						canceled.countDown();
					});
					handlerEntered.countDown();
					awaitLatch(canceled);
					return McpCompleteResult.fromToolText("late");
				})));
		SokletConfig config = config(server, MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance());
		Request request = request("scope-cancel", "scope-cancel", null,
				LOOPBACK + ":0", Optional.empty());

		Soklet.runSimulator(config, simulator -> {
			escapedSimulator.set(simulator);
			escapedSimulation.set(simulator.startMcpRequest(request));
			Assertions.assertTrue(awaitLatch(handlerEntered));
		});
		Assertions.assertTrue(awaitLatch(canceled));
		Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
				tokenReason.get());
		Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
				awaitCompletion(escapedSimulation.get()).getReason());
		Assertions.assertThrows(IllegalStateException.class,
				() -> escapedSimulator.get().startMcpRequest(request));
		assertStoppedDiagnostics(server);
	}

	@Test
	public void noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression() {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		McpToolRegistration<McpJsonObject> tool = tool("noncooperative",
				(request, arguments, features) -> {
					handlerEntered.countDown();
					boolean interrupted = false;
					try {
						while (releaseHandler.getCount() != 0) {
							try {
								releaseHandler.await();
							} catch (InterruptedException e) {
								interrupted = true;
							}
						}
						return McpCompleteResult.fromToolText("released late");
					} finally {
						if (interrupted)
							Thread.currentThread().interrupt();
						handlerExited.countDown();
					}
				});
		McpServer server = server(List.of(tool),
				McpAdmissionController.acceptAllInstance(),
				Duration.ofMillis(50));
		SokletConfig config = config(server, MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance());
		Request request = request("noncooperative", "noncooperative", null,
				LOOPBACK + ":0", Optional.empty());
		RuntimeException consumerFailure = new RuntimeException(
				"consumer-failure-canary");
		AtomicReference<Simulator> escapedSimulator = new AtomicReference<>();

		try {
			RuntimeException thrown = Assertions.assertThrows(RuntimeException.class,
					() -> Soklet.runSimulator(config, simulator -> {
						escapedSimulator.set(simulator);
						simulator.startMcpRequest(request);
						Assertions.assertTrue(awaitLatch(handlerEntered));
						throw consumerFailure;
					}));
			Assertions.assertSame(consumerFailure, thrown);
			Assertions.assertEquals(1, thrown.getSuppressed().length);
			Assertions.assertInstanceOf(IllegalStateException.class,
					thrown.getSuppressed()[0]);
			Assertions.assertFalse(thrown.getSuppressed()[0].toString()
					.contains("consumer-failure-canary"));
			Assertions.assertThrows(IllegalStateException.class,
					() -> escapedSimulator.get().startMcpRequest(request),
					"A simulator with residual work must reject new requests.");
			Assertions.assertThrows(IllegalStateException.class, server::start,
					"Live start must not overlap residual simulator work.");
		} finally {
			releaseHandler.countDown();
		}
		Assertions.assertTrue(awaitLatch(handlerExited));
	}

	@Test
	public void waitOperationsHandleZeroTimeoutInterruptionAndCompletionIdempotently()
			throws Exception {
		CountDownLatch itemEmitted = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		McpServer server = server(List.of(tool("waits",
				(request, arguments, features) -> {
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					itemEmitted.countDown();
					awaitLatch(releaseHandler);
					return McpCompleteResult.fromToolText("wait complete");
				})));
		SokletConfig config = config(server, MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance());
		Request request = request("waits", "waits", "1",
				LOOPBACK + ":0", Optional.empty());

		try {
			Soklet.runSimulator(config, simulator -> {
				McpSimulation simulation = simulator.startMcpRequest(request);
				Assertions.assertThrows(NullPointerException.class,
						() -> simulation.awaitResponse(null));
				Assertions.assertThrows(NullPointerException.class,
						() -> simulation.nextStreamItem(null));
				Assertions.assertThrows(NullPointerException.class,
						() -> simulation.awaitCompletion(null));
				for (Duration negative : List.of(Duration.ofNanos(-1),
						Duration.ofSeconds(-1))) {
					Assertions.assertThrows(IllegalArgumentException.class,
							() -> simulation.awaitResponse(negative));
					Assertions.assertThrows(IllegalArgumentException.class,
							() -> simulation.nextStreamItem(negative));
					Assertions.assertThrows(IllegalArgumentException.class,
							() -> simulation.awaitCompletion(negative));
				}
				Assertions.assertTrue(awaitLatch(itemEmitted));
				Assertions.assertTrue(pollCompletion(simulation,
						Duration.ZERO).isEmpty());
				Assertions.assertTrue(pollNextItem(simulation,
						Duration.ofSeconds(Long.MAX_VALUE)).isPresent());

				AtomicReference<Throwable> interruption = new AtomicReference<>();
				CountDownLatch waiterStarted = new CountDownLatch(1);
				Thread waiter = new Thread(() -> {
					waiterStarted.countDown();
					try {
						simulation.nextStreamItem(Duration.ofSeconds(Long.MAX_VALUE));
					} catch (Throwable throwable) {
						interruption.set(throwable);
					}
				}, "mcp-simulator-interrupted-wait");
				waiter.start();
				Assertions.assertTrue(awaitLatch(waiterStarted));
				waiter.interrupt();
				try {
					waiter.join(TimeUnit.SECONDS.toMillis(5));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError(e);
				}
				Assertions.assertFalse(waiter.isAlive());
				Assertions.assertInstanceOf(InterruptedException.class,
						interruption.get());
				Assertions.assertFalse(simulation.isComplete(),
						"An interrupted waiter must not cancel its simulation.");
				releaseHandler.countDown();
				McpSimulationCompletion completion = awaitCompletion(simulation);
				Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
						completion.getReason());
				Assertions.assertEquals(completion.getReason(),
						awaitCompletion(simulation).getReason());
			});
		} finally {
			releaseHandler.countDown();
		}
	}

	@Test
	public void concurrentSimulationsRemainRequestIsolatedAndDrainExactlyOnce()
			throws Exception {
		int requestCount = 16;
		AtomicInteger handlerCalls = new AtomicInteger();
		McpServer server = server(List.of(tool("concurrent",
				(request, arguments, features) -> McpCompleteResult.fromToolText(
						"result-" + handlerCalls.incrementAndGet()))));
		SokletConfig config = config(server, MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance());
		ExecutorService executor = Executors.newFixedThreadPool(4);

		try {
			Soklet.runSimulator(config, simulator -> {
				List<CompletableFuture<String>> futures = new ArrayList<>();
				for (int index = 0; index < requestCount; index++) {
					int requestIndex = index;
					futures.add(CompletableFuture.supplyAsync(() -> {
						McpSimulation simulation = simulator.startMcpRequest(request(
								"concurrent-" + requestIndex, "concurrent", null,
								LOOPBACK + ":0", Optional.empty()));
						String body = new String(awaitResponse(simulation).getBody()
								.orElseThrow(), StandardCharsets.UTF_8);
						Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
								awaitCompletion(simulation).getReason());
						return body;
					}, executor));
				}
				Set<String> expectedIds = java.util.stream.IntStream.range(0,
						requestCount).mapToObj(index -> "\"id\":\"concurrent-"
						+ index + "\"").collect(java.util.stream.Collectors.toSet());
				Set<String> actualIds = futures.stream().map(future -> {
					try {
						return future.get(5, TimeUnit.SECONDS);
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				}).map(body -> expectedIds.stream().filter(body::contains)
						.findFirst().orElseThrow()).collect(
						java.util.stream.Collectors.toSet());
				Assertions.assertEquals(expectedIds, actualIds);
			});
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		Assertions.assertEquals(requestCount, handlerCalls.get());
	}

	private static McpToolRegistration<McpJsonObject> tool(
			@NonNull String name, @NonNull McpToolHandler<McpJsonObject> handler) {
		return McpToolRegistration.withName(name).jsonArguments()
				.handler(handler).build();
	}

	private static McpServer server(
			@NonNull List<@NonNull McpToolRegistration<?>> tools) {
		return server(tools, McpAdmissionController.acceptAllInstance());
	}

	private static McpServer server(
			@NonNull List<@NonNull McpToolRegistration<?>> tools,
			@NonNull McpAdmissionController admissionController) {
		return server(tools, admissionController, Duration.ofMillis(250));
	}

	private static McpServer server(
			@NonNull List<@NonNull McpToolRegistration<?>> tools,
			@NonNull McpAdmissionController admissionController,
			@NonNull Duration shutdownTimeout) {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"simulator-public-runtime-test", "3.6.0-SNAPSHOT").build())
				.tools(tools)
				.build();
		return baseServerBuilder(List.of(endpoint), admissionController,
				shutdownTimeout).build();
	}

	private static McpServer.Builder baseServerBuilder(
			@NonNull List<@NonNull McpEndpoint> endpoints,
			@NonNull McpAdmissionController admissionController,
			@NonNull Duration shutdownTimeout) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.admissionController(admissionController)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.shutdownTimeout(shutdownTimeout);
	}

	private static SokletConfig config(@NonNull McpServer server,
			@NonNull MetricsCollector metrics,
			@NonNull LifecycleObserver lifecycle) {
		return SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metrics)
				.lifecycleObservers(List.of(lifecycle))
				.build();
	}

	private static Request request(@NonNull String id,
			@NonNull String toolName, String progressToken,
			String host, @NonNull Optional<@NonNull String> origin) {
		String progress = progressToken == null ? ""
				: ",\"progressToken\":" + progressToken;
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}"
				+ progress + "},\"name\":\"" + toolName
				+ "\",\"arguments\":{}}}";
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		if (host != null)
			headers.put("Host", Set.of(host));
		headers.put("Content-Type", Set.of(JSON_MEDIA_TYPE + "; charset=UTF-8"));
		headers.put("Accept", Set.of(JSON_MEDIA_TYPE + ", text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of("tools/call"));
		headers.put("Mcp-Name", Set.of(toolName));
		headers.put("X-Simulator-Canary", Set.of("canary-value"));
		origin.ifPresent(value -> headers.put("Origin", Set.of(value)));
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static Request subscriptionRequest(@NonNull String id) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type", Set.of(JSON_MEDIA_TYPE + "; charset=UTF-8"),
						"Accept", Set.of(JSON_MEDIA_TYPE + ", text/event-stream"),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of("subscriptions/listen")))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static Request requestWithState(@NonNull String id,
			@NonNull String toolName, @NonNull String requestState) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":{},"
				+ "\"requestState\":\"" + requestState + "\"}}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type", Set.of(JSON_MEDIA_TYPE + "; charset=UTF-8"),
						"Accept", Set.of(JSON_MEDIA_TYPE + ", text/event-stream"),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of("tools/call"),
						"Mcp-Name", Set.of(toolName)))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static Request malformedRequest(@NonNull String body) {
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type", Set.of(JSON_MEDIA_TYPE + "; charset=UTF-8"),
						"Accept", Set.of(JSON_MEDIA_TYPE + ", text/event-stream"),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of("server/discover")))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static void assertStoppedDiagnostics(@NonNull McpServer server) {
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.STOPPED, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isEmpty());
		Assertions.assertEquals(0, diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(0, diagnostics.getQueuedRequests());
		Assertions.assertEquals(0, diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(0, diagnostics.getActiveSubscriptions());
	}

	private static McpSimulationResponse awaitResponse(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.awaitResponse(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator response."));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static McpSimulationStreamItem nextItem(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.nextStreamItem(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator stream item."));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static McpSimulationCompletion awaitCompletion(
			@NonNull McpSimulation simulation) {
		try {
			return simulation.awaitCompletion(WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator completion."));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static Optional<@NonNull McpSimulationStreamItem> pollNextItem(
			@NonNull McpSimulation simulation, @NonNull Duration timeout) {
		try {
			return simulation.nextStreamItem(timeout);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static Optional<@NonNull McpSimulationCompletion> pollCompletion(
			@NonNull McpSimulation simulation, @NonNull Duration timeout) {
		try {
			return simulation.awaitCompletion(timeout);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static boolean awaitLatch(@NonNull CountDownLatch latch) {
		try {
			return latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static void assertSameJsonRpcId(@NonNull McpJsonValue first,
			@NonNull McpJsonValue second) {
		McpJsonObject firstObject = Assertions.assertInstanceOf(
				McpJsonObject.class, first);
		McpJsonObject secondObject = Assertions.assertInstanceOf(
				McpJsonObject.class, second);
		Assertions.assertEquals(firstObject.find("id").orElseThrow(),
				secondObject.find("id").orElseThrow());
	}

	private static final class RecordingMetrics implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		private final CountDownLatch requestFinished;
		private final CountDownLatch requestRejections;
		private final CountDownLatch requestStreamByteLimit =
				new CountDownLatch(1);
		private final CountDownLatch subscriptionByteLimit =
				new CountDownLatch(1);
		private final CountDownLatch simulatorLimitRequestFinishes;

		private RecordingMetrics() {
			this(0, 0, 1);
		}

		private RecordingMetrics(int expectedRequestRejections) {
			this(expectedRequestRejections, 0, 1);
		}

		private RecordingMetrics(int expectedRequestRejections,
				int expectedSimulatorLimitRequestFinishes) {
			this(expectedRequestRejections,
					expectedSimulatorLimitRequestFinishes, 1);
		}

		private RecordingMetrics(int expectedRequestRejections,
				int expectedSimulatorLimitRequestFinishes,
				int expectedRequestFinishes) {
			this.requestFinished = new CountDownLatch(expectedRequestFinishes);
			this.requestRejections = new CountDownLatch(expectedRequestRejections);
			this.simulatorLimitRequestFinishes =
					new CountDownLatch(expectedSimulatorLimitRequestFinishes);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(event);
			if (event instanceof McpMetricsEvent.RequestFinished)
				this.requestFinished.countDown();
			if (event instanceof McpMetricsEvent.RequestRejected)
				this.requestRejections.countDown();
			if (event instanceof McpMetricsEvent.RequestStreamClosed closed
					&& closed.getReason() == McpStreamTerminationReason
							.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED)
				this.requestStreamByteLimit.countDown();
			if (event instanceof McpMetricsEvent.SubscriptionClosed closed
					&& closed.getReason() == McpStreamTerminationReason
							.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED)
				this.subscriptionByteLimit.countDown();
			if (event instanceof McpMetricsEvent.RequestFinished finished
					&& finished.getOutcome() == McpRequestOutcome.CANCELED)
				this.simulatorLimitRequestFinishes.countDown();
		}

		private boolean awaitRequestFinished() {
			return awaitLatch(this.requestFinished);
		}

		private boolean awaitRequestRejections() {
			return awaitLatch(this.requestRejections);
		}

		private boolean awaitRequestStreamByteLimit() {
			return awaitLatch(this.requestStreamByteLimit);
		}

		private boolean awaitSubscriptionByteLimit() {
			return awaitLatch(this.subscriptionByteLimit);
		}

		private boolean awaitSimulatorLimitRequestFinishes() {
			return awaitLatch(this.simulatorLimitRequestFinishes);
		}

		private List<McpMetricsEvent> events() {
			return List.copyOf(this.events);
		}
	}

	private static final class ServerLifecycleProbe implements LifecycleObserver {
		private final AtomicInteger callbacks = new AtomicInteger();

		@Override
		public void willStartMcpServer(@NonNull McpServer server) {
			this.callbacks.incrementAndGet();
		}

		@Override
		public void didStartMcpServer(@NonNull McpServer server) {
			this.callbacks.incrementAndGet();
		}

		@Override
		public void didFailToStartMcpServer(@NonNull McpServer server,
				@NonNull Throwable throwable) {
			this.callbacks.incrementAndGet();
		}

		@Override
		public void willStopMcpServer(@NonNull McpServer server) {
			this.callbacks.incrementAndGet();
		}

		@Override
		public void didStopMcpServer(@NonNull McpServer server,
				@NonNull McpShutdownOutcome outcome) {
			this.callbacks.incrementAndGet();
		}

		@Override
		public void didFailToStopMcpServer(@NonNull McpServer server,
				@NonNull Throwable throwable) {
			this.callbacks.incrementAndGet();
		}

		private int serverCallbacks() {
			return this.callbacks.get();
		}
	}
}
