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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Black-box public coverage for asynchronous, off-network MCP simulation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpSimulatorPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	public void startMcpRequestRejectsMissingServerConfiguration() {
		Request request = request("missing-server", "complete", null,
				LOOPBACK + ":0", Optional.empty());
		AtomicReference<Simulator> escaped = new AtomicReference<>();

		SokletSimulator.run(transports -> SokletConfig
				.withHttpServer(transports.getHttpServer()).build(), simulator -> {
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
		ServerFixture server = server(() -> List.of(tool("blocking",
				(request, arguments, features) -> {
					handlerEntered.countDown();
					awaitLatch(releaseHandler);
					return McpCompleteResult.fromToolText("released");
				})), context -> {
			admittedRequest.set(context.getRequest());
			return McpAdmissionDecision.accepted();
		});
		SimulatorConfigFactory configFactory =
				server.configFactory(metrics, lifecycle);
		String origin = "https://simulator.example";
		Request request = request("configured-server", "blocking", null,
				LOOPBACK + ":0", Optional.of(origin));

		try {
			SokletSimulator.run(configFactory, simulator -> {
				assertRunningOffNetworkDiagnostics(server.server());
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
				assertRunningOffNetworkDiagnostics(server.server());
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
		assertStoppedDiagnostics(server.server());
	}

	@Test
	public void defaultLoopbackHostPolicyRequiresLiteralConfiguredPortZero() {
		AtomicInteger handlerCalls = new AtomicInteger();
		ServerFixture server = new ServerFixture(Duration.ofMillis(250),
				transports -> {
			McpToolRegistration<McpJsonObject> tool = tool("default-loopback",
					(request, arguments, features) -> {
						handlerCalls.incrementAndGet();
						return McpCompleteResult.fromToolText(
								"default host accepted");
					});
			McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
					.serverInformation(McpImplementation.withNameAndVersion(
							"simulator-default-host-test",
							"4.0.0").build())
					.tool(tool)
					.build();
			return transports.newMcpServerBuilder(0)
					.host(LOOPBACK)
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(
							List.of(endpoint)))
					.admissionController(
							McpAdmissionController.acceptAllInstance())
					.requestRateLimiter(
							context -> McpRateLimitDecision.allowed())
					.toolRateLimiter(context -> McpRateLimitDecision.allowed())
					.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
					.build();
		});

		SokletSimulator.run(server.configFactory(
				MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance()), simulator -> {
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
		assertStoppedDiagnostics(server.server());
	}

	@Test
	public void synchronousJsonSimulationUsesRealProtocolLifecycleMetricsAndBodyMode() {
		RecordingMetrics metrics = new RecordingMetrics();
		ServerFixture server = server(() -> List.of(tool("complete",
				(request, arguments, features) ->
						McpCompleteResult.fromToolText("json complete"))));
		Request request = request("json-response", "complete", null,
				LOOPBACK + ":0", Optional.empty());
		AtomicInteger exactBodySize = new AtomicInteger();

		SokletSimulator.run(server.configFactory(metrics,
				LifecycleObserver.defaultInstance()), simulator -> {
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
		ServerFixture server = server(() -> List.of(tool("complete",
				(request, arguments, features) ->
						McpCompleteResult.fromToolText("must not run"))), context -> {
			admissionCalls.incrementAndGet();
			return McpAdmissionDecision.rejected(rejection);
		});

		SokletSimulator.run(server.configFactory(metrics,
				LifecycleObserver.defaultInstance()), simulator -> {
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
		ServerFixture server = server(() -> List.of(McpToolRegistration
				.withName("multi-round-trip")
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerContexts.add(request);
					if (handlerCalls.incrementAndGet() == 1) {
						Assertions.assertEquals(McpRequestId.fromString("mrtr-initial"),
								request.getRequestId().orElseThrow());
						Assertions.assertTrue(
								request.getApplicationRequestState().isEmpty());
						Assertions.assertTrue(
								request.getFrameworkRequestState().isEmpty());
						return McpInputRequiredResult.builder()
								.applicationRequestState(requestState)
								.build();
					}
					Assertions.assertEquals(McpRequestId.fromString("mrtr-continued"),
							request.getRequestId().orElseThrow());
					String continuedState =
							request.getApplicationRequestState().orElseThrow();
					Assertions.assertEquals(requestState, continuedState);
					Assertions.assertTrue(
							request.getFrameworkRequestState().isEmpty());
					return McpCompleteResult.fromToolText("continued complete");
				})
				.requestStateMode(McpRequestStateMode.APPLICATION_PROTECTED)
				.build()));

		SokletSimulator.run(server.configFactory(metrics,
				LifecycleObserver.defaultInstance()), simulator -> {
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
		ServerFixture server = server(() -> List.of(tool("progress",
				(request, arguments, features) -> {
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					return McpCompleteResult.fromToolText("stream complete");
				})));
		Request request = request("stream-response", "progress",
				"\"sim-token\"", LOOPBACK + ":0", Optional.empty());

		SokletSimulator.run(server.configFactory(
				MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance()), simulator -> {
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
		ServerFixture server = server(() -> List.of(tool("open-stream",
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
		Request request = request("open-stream", "open-stream",
				"\"open-token\"", LOOPBACK + ":0", Optional.empty());

		SokletSimulator.run(server.configFactory(
				MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance()), simulator -> {
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
		ServerFixture server = new ServerFixture(Duration.ofMillis(250),
				transports -> {
			McpSubscriptionConfig subscriptions = McpSubscriptionConfig
					.withEventPublisher(publisher)
					.notificationTypes(EnumSet.of(
							McpSubscriptionNotificationType
									.RESOURCES_LIST_CHANGED))
					.build();
			McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
					.serverInformation(McpImplementation.withNameAndVersion(
							"simulator-subscription-test",
							"4.0.0").build())
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
			return baseServerBuilder(transports, List.of(endpoint),
					McpAdmissionController.acceptAllInstance()).build();
		});
		Request request = subscriptionRequest("subscription-sim");

		SokletSimulator.run(server.configFactory(metrics,
				LifecycleObserver.defaultInstance()), simulator -> {
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
		ServerFixture server = server(() -> {
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
			return List.of(limited, failing);
		}, McpAdmissionController.acceptAllInstance());
		Request request = request("item-limit", "limited", "\"item-token\"",
				LOOPBACK + ":0", Optional.empty());

		try {
			SokletSimulator.run(server.configFactory(metrics,
					LifecycleObserver.defaultInstance()), simulator -> {
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
	@Timeout(120)
	public void nonDrainingCaptureLimitDoesNotBlockUnrelatedSimulationOrCreateTransportFailure() {
		CountDownLatch firstSlowProgress = new CountDownLatch(1);
		CountDownLatch allowSlowOverflow = new CountDownLatch(1);
		CountDownLatch slowHeldAfterLimit = new CountDownLatch(1);
		CountDownLatch releaseSlowHandler = new CountDownLatch(1);
		CountDownLatch slowHandlerExited = new CountDownLatch(1);
		CountDownLatch slowCanceled = new CountDownLatch(1);
		CountDownLatch fastHandlerEntered = new CountDownLatch(1);
		AtomicReference<CancelationToken> slowToken = new AtomicReference<>();
		List<StreamTerminationReason> slowTokenReasons =
				new CopyOnWriteArrayList<>();
		AtomicInteger slowCancelCallbacks = new AtomicInteger();
		RecordingMetrics metrics = new RecordingMetrics(0, 1, 2, 2);
		ServerFixture server = new ServerFixture(Duration.ofMillis(250),
				transports -> {
			McpToolRegistration<McpJsonObject> slow = tool("capture-slow",
				(request, arguments, features) -> {
					CancelationToken token = features.require(CancelationToken.class);
					slowToken.set(token);
					token.onCancel(() -> {
						slowCancelCallbacks.incrementAndGet();
						slowTokenReasons.add(token.getCancelationReason().orElseThrow());
						slowCanceled.countDown();
					});
					McpProgressReporter reporter =
							features.require(McpProgressReporter.class);
					try {
						reporter.report(McpProgressUpdate.withProgress(1.0d).build());
						firstSlowProgress.countDown();
						awaitLatch(allowSlowOverflow);
						reporter.report(McpProgressUpdate.withProgress(2.0d).build());
						slowHeldAfterLimit.countDown();
						awaitLatchIgnoringInterrupt(releaseSlowHandler);
						return McpCompleteResult.fromToolText("late slow result");
					} finally {
						slowHandlerExited.countDown();
					}
				});
			McpToolRegistration<McpJsonObject> fast = tool("capture-fast",
				(request, arguments, features) -> {
					fastHandlerEntered.countDown();
					return McpCompleteResult.fromToolText("unrelated complete");
				});
			McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"simulator-capture-isolation-test",
						"4.0.0").build())
				.tools(List.of(slow, fast))
				.build();
			return baseServerBuilder(transports, List.of(endpoint),
					McpAdmissionController.acceptAllInstance())
					.requestHandlerConcurrency(2)
					.requestHandlerQueueCapacity(1)
					.keepAliveInterval(Duration.ofHours(1))
					.writeTimeout(Duration.ofHours(2))
					.requestTimeout(Duration.ofHours(3))
					.build();
		});
		try {
			SokletSimulator.run(server.configFactory(metrics,
					LifecycleObserver.defaultInstance()), simulator -> {
				assertRunningOffNetworkDiagnostics(server.server());
				McpSimulation slowSimulation = simulator.startMcpRequest(request(
						"capture-slow", "capture-slow", "\"slow-token\"",
						LOOPBACK + ":0", Optional.empty()),
						McpSimulationOptions.builder()
								.streamItemQueueCapacity(1).build());
				try {
					Assertions.assertTrue(awaitLatch(firstSlowProgress));
					Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
							awaitResponse(slowSimulation).getBodyMode());
					Assertions.assertTrue(pollCompletion(slowSimulation,
							Duration.ZERO).isEmpty());
					allowSlowOverflow.countDown();
					Assertions.assertTrue(awaitLatch(slowCanceled));
					Assertions.assertTrue(awaitLatch(slowHeldAfterLimit));

					McpSimulationCompletion slowCompletion =
							awaitCompletion(slowSimulation);
					Assertions.assertEquals(McpStreamTerminationReason
							.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED,
							slowCompletion.getReason());
					Assertions.assertTrue(slowCompletion.getTerminalMessage().isEmpty());
					Assertions.assertTrue(slowCompletion.getThrowables().isEmpty());
					Assertions.assertTrue(slowSimulation.isComplete());
					Assertions.assertTrue(slowToken.get().isCanceled());
					Assertions.assertEquals(List.of(
							StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED),
							slowTokenReasons);
					Assertions.assertEquals(1, slowCancelCallbacks.get());
					slowSimulation.cancel();
					slowSimulation.close();
					Assertions.assertSame(slowCompletion,
							awaitCompletion(slowSimulation));
					Assertions.assertEquals(1, slowCancelCallbacks.get());
					assertRunningOffNetworkDiagnostics(server.server());

					McpSimulation fastSimulation = simulator.startMcpRequest(request(
							"capture-fast", "capture-fast", null,
							LOOPBACK + ":0", Optional.empty()));
					Assertions.assertTrue(awaitLatch(fastHandlerEntered));
					McpSimulationResponse fastResponse = awaitResponse(fastSimulation);
					Assertions.assertEquals(McpSimulationBodyMode.JSON,
							fastResponse.getBodyMode());
					Assertions.assertTrue(new String(
							fastResponse.getBody().orElseThrow(),
							StandardCharsets.UTF_8).contains("unrelated complete"));
					Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
							awaitCompletion(fastSimulation).getReason());
					Assertions.assertTrue(fastSimulation.isComplete());
					Assertions.assertEquals(1L, slowHandlerExited.getCount(),
							"The unrelated simulation must finish while the slow handler still owns its slot.");
					Assertions.assertSame(slowCompletion,
							awaitCompletion(slowSimulation));

					McpSimulationStreamItem retained = nextItem(slowSimulation);
					String retainedFrame = new String(retained.getEncodedBytes(),
							StandardCharsets.UTF_8);
					Assertions.assertTrue(retainedFrame.contains("\"progress\":1"));
					Assertions.assertFalse(retainedFrame.contains("\"progress\":2"));
					Assertions.assertTrue(pollNextItem(slowSimulation,
							Duration.ZERO).isEmpty(),
							"The frame that exceeded the non-drained item limit must be omitted.");

					releaseSlowHandler.countDown();
					Assertions.assertTrue(awaitLatch(slowHandlerExited));
					assertRunningOffNetworkDiagnostics(server.server());
				} finally {
					allowSlowOverflow.countDown();
					releaseSlowHandler.countDown();
				}
			});
		} finally {
			allowSlowOverflow.countDown();
			releaseSlowHandler.countDown();
		}

		Assertions.assertTrue(metrics.awaitRequestFinished());
		Assertions.assertTrue(metrics.awaitSimulatorLimitRequestFinishes());
		Assertions.assertTrue(metrics.awaitHandlerExecutionsFinished());
		Assertions.assertTrue(awaitLatch(slowHandlerExited));
		Assertions.assertEquals(1, slowCancelCallbacks.get());
		Assertions.assertEquals(List.of(
				StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED),
				slowTokenReasons);
		List<McpMetricsEvent> events = metrics.events();
		Assertions.assertEquals(2, countEvents(events,
				McpMetricsEvent.RequestAccepted.class), events.toString());
		Assertions.assertEquals(2, countEvents(events,
				McpMetricsEvent.RequestStarted.class), events.toString());
		Assertions.assertEquals(2, countEvents(events,
				McpMetricsEvent.HandlerExecutionStarted.class), events.toString());
		Assertions.assertEquals(2, countEvents(events,
				McpMetricsEvent.HandlerExecutionFinished.class), events.toString());
		Assertions.assertEquals(1, countEvents(events,
				McpMetricsEvent.RequestStreamOpened.class), events.toString());
		Assertions.assertEquals(2, countEvents(events,
				McpMetricsEvent.ProgressEmitted.class), events.toString());
		Assertions.assertEquals(1, countEvents(events,
				McpMetricsEvent.CancelationSignaled.class), events.toString());
		Assertions.assertEquals(1, events.stream()
				.filter(McpMetricsEvent.RequestStreamClosed.class::isInstance)
				.map(McpMetricsEvent.RequestStreamClosed.class::cast)
				.filter(closed -> closed.getReason() == McpStreamTerminationReason
						.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED)
				.count(), events.toString());
		Assertions.assertEquals(2, countEvents(events,
				McpMetricsEvent.RequestFinished.class), events.toString());
		Assertions.assertEquals(1, events.stream()
				.filter(McpMetricsEvent.RequestFinished.class::isInstance)
				.map(McpMetricsEvent.RequestFinished.class::cast)
				.filter(finished -> finished.getOutcome()
						== McpRequestOutcome.CANCELED)
				.count(), events.toString());
		Assertions.assertEquals(1, events.stream()
				.filter(McpMetricsEvent.RequestFinished.class::isInstance)
				.map(McpMetricsEvent.RequestFinished.class::cast)
				.filter(finished -> finished.getOutcome()
						== McpRequestOutcome.COMPLETE)
				.count(), events.toString());
		Assertions.assertEquals(15, events.size(), events.toString());
		Assertions.assertTrue(events.stream().noneMatch(event ->
				event instanceof McpMetricsEvent.RequestRejected
						|| event instanceof McpMetricsEvent.HandlerQueued
						|| event instanceof McpMetricsEvent.HandlerDequeued
						|| event instanceof McpMetricsEvent.HandlerCapacityRejected
						|| event instanceof McpMetricsEvent.KeepAliveEmitted
						|| event instanceof McpMetricsEvent.ProtocolError
						|| event instanceof McpMetricsEvent.TransportFailure
						|| event instanceof McpMetricsEvent.ConnectionAccepted
						|| event instanceof McpMetricsEvent.ConnectionRejected),
				events.toString());
		assertStoppedDiagnostics(server.server());
	}

	@Test
	public void simulatorScopeExitCancelsOutstandingRequestsAndRestoresOffNetworkState() {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch canceled = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> tokenReason = new AtomicReference<>();
		AtomicReference<McpSimulation> escapedSimulation = new AtomicReference<>();
		AtomicReference<Simulator> escapedSimulator = new AtomicReference<>();
		ServerFixture server = server(() -> List.of(tool("scope-cancel",
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
		Request request = request("scope-cancel", "scope-cancel", null,
				LOOPBACK + ":0", Optional.empty());

		SokletSimulator.run(server.configFactory(
				MetricsCollector.defaultInstance(),
				LifecycleObserver.defaultInstance()), simulator -> {
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
		assertStoppedDiagnostics(server.server());
	}

	@Test
	public void noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression() {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		ServerFixture server = server(() -> List.of(tool("noncooperative",
				(request, arguments, features) -> {
					handlerEntered.countDown();
					boolean interrupted = false;
					try {
						while (releaseHandler.getCount() != 0) {
							try {
								Assertions.assertTrue(releaseHandler.await(10,
										TimeUnit.SECONDS),
										"Timed out waiting to release the simulation handler");
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
				})),
				McpAdmissionController.acceptAllInstance(),
				Duration.ofMillis(50));
		Request request = request("noncooperative", "noncooperative", null,
				LOOPBACK + ":0", Optional.empty());
		RuntimeException consumerFailure = new RuntimeException(
				"consumer-failure-canary");
		AtomicReference<Simulator> escapedSimulator = new AtomicReference<>();
		LifecycleWorkers lifecycleWorkers = new LifecycleWorkers(
				(name, task) -> task.run());

		try {
			RuntimeException thrown = Assertions.assertThrows(RuntimeException.class,
					() -> SokletSimulator.run(server.configFactory(
							MetricsCollector.defaultInstance(),
							LifecycleObserver.defaultInstance()),
							SimulatorOptions.defaultInstance(), simulator -> {
						escapedSimulator.set(simulator);
						simulator.startMcpRequest(request);
						Assertions.assertTrue(awaitLatch(handlerEntered));
						throw consumerFailure;
					}, NanoClock.system(), lifecycleWorkers));
			Assertions.assertSame(consumerFailure, thrown);
			Assertions.assertEquals(1, thrown.getSuppressed().length);
			ShutdownIncompleteException shutdownFailure = Assertions.assertInstanceOf(
					ShutdownIncompleteException.class,
					thrown.getSuppressed()[0]);
			InternalShutdownResult shutdownResult =
					shutdownFailure.getInternalShutdownResult();
			Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
					shutdownResult.disposition());
			Assertions.assertEquals(InternalStartupDisposition.READY,
					shutdownResult.startupDisposition());
			Assertions.assertEquals(1, shutdownResult.participantResults().size());
			InternalParticipantShutdownResult participant = shutdownResult
					.participantResult(InternalParticipantKind.MCP).orElseThrow();
			Assertions.assertEquals(
					InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY,
					participant.disposition());
			Assertions.assertEquals(Set.of(
						InternalResidualActivityKind.EXECUTOR_TASK,
						InternalResidualActivityKind.CALLBACK),
					participant.residualActivity());
			Assertions.assertTrue(participant.failures().isEmpty());
			Assertions.assertFalse(shutdownFailure.toString()
					.contains("consumer-failure-canary"));
			Assertions.assertThrows(IllegalStateException.class,
					() -> escapedSimulator.get().startMcpRequest(request),
					"A simulator with residual work must reject new requests.");
			TransportOwnershipException conflict = Assertions.assertThrows(
					TransportOwnershipException.class,
					() -> Soklet.fromConfig(SokletConfig
							.withMcpServer(server.server())
							.resourceMethodResolver(ResourceMethodResolver
									.fromMethods(Set.of()))
							.build()),
					"Live start must not overlap residual simulator work.");
			Assertions.assertEquals(ParticipantKind.MCP,
					conflict.getParticipantKind());
			Assertions.assertSame(server.server().getClass(),
					conflict.getTransportClass());
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
		ServerFixture server = server(() -> List.of(tool("waits",
				(request, arguments, features) -> {
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					itemEmitted.countDown();
					awaitLatch(releaseHandler);
					return McpCompleteResult.fromToolText("wait complete");
				})));
		Request request = request("waits", "waits", "1",
				LOOPBACK + ":0", Optional.empty());

		try {
			SokletSimulator.run(server.configFactory(
					MetricsCollector.defaultInstance(),
					LifecycleObserver.defaultInstance()), simulator -> {
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
	@Timeout(120)
	public void concurrentSimulationsRemainRequestIsolatedAndDrainExactlyOnce()
			throws Exception {
		int requestCount = 16;
		AtomicInteger handlerCalls = new AtomicInteger();
		ServerFixture server = server(() -> List.of(tool("concurrent",
				(request, arguments, features) -> McpCompleteResult.fromToolText(
						"result-" + handlerCalls.incrementAndGet()))));
		ExecutorService executor = Executors.newFixedThreadPool(4);

		try {
			SokletSimulator.run(server.configFactory(
					MetricsCollector.defaultInstance(),
					LifecycleObserver.defaultInstance()), simulator -> {
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
				try {
					CompletableFuture.allOf(futures.toArray(
							CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
				Set<String> actualIds = futures.stream()
						.map(CompletableFuture::join)
						.map(body -> expectedIds.stream().filter(body::contains)
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

	private static ServerFixture server(
			@NonNull Supplier<? extends List<? extends McpToolRegistration<?>>>
					toolsFactory) {
		return server(toolsFactory,
				McpAdmissionController.acceptAllInstance());
	}

	private static ServerFixture server(
			@NonNull Supplier<? extends List<? extends McpToolRegistration<?>>>
					toolsFactory,
			@NonNull McpAdmissionController admissionController) {
		return server(toolsFactory, admissionController, Duration.ofMillis(250));
	}

	private static ServerFixture server(
			@NonNull Supplier<? extends List<? extends McpToolRegistration<?>>>
					toolsFactory,
			@NonNull McpAdmissionController admissionController,
			@NonNull Duration shutdownTimeout) {
		return new ServerFixture(shutdownTimeout, transports -> {
			List<McpToolRegistration<?>> tools = new ArrayList<>();
			tools.addAll(toolsFactory.get());
			McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
					.serverInformation(McpImplementation.withNameAndVersion(
							"simulator-public-runtime-test",
							"4.0.0").build())
					.tools(tools)
					.build();
			return baseServerBuilder(transports, List.of(endpoint),
					admissionController).build();
		});
	}

	private static McpServer.Builder baseServerBuilder(
			@NonNull SimulatorTransports transports,
			@NonNull List<@NonNull McpEndpoint> endpoints,
			@NonNull McpAdmissionController admissionController) {
		return transports.newMcpServerBuilder(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.admissionController(admissionController)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.acceptAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}


	private static final class ServerFixture {
		@NonNull
		private final Duration forcedShutdownDuration;
		@NonNull
		private final Function<SimulatorTransports, McpServer> serverFactory;
		@NonNull
		private final AtomicReference<McpServer> server = new AtomicReference<>();

		private ServerFixture(@NonNull Duration shutdownTimeout,
				@NonNull Function<SimulatorTransports, McpServer> serverFactory) {
			this.forcedShutdownDuration = shutdownTimeout;
			this.serverFactory = serverFactory;
		}

		@NonNull
		private SimulatorConfigFactory configFactory(
				@NonNull MetricsCollector metrics,
				@NonNull LifecycleObserver lifecycle) {
			return transports -> {
				McpServer server = this.serverFactory.apply(transports);
				this.server.set(server);
				return SokletConfig.withMcpServer(server)
						.resourceMethodResolver(
								ResourceMethodResolver.fromMethods(Set.of()))
						.metricsCollector(metrics)
						.lifecycleObservers(List.of(lifecycle))
						.lifecyclePolicy(LifecyclePolicy.builder()
								.startupTimeout(Duration.ofSeconds(30))
								.startupCancellationTimeout(Duration.ofSeconds(2))
								.gracefulShutdownDuration(Duration.ZERO)
								.forcedShutdownDuration(
										this.forcedShutdownDuration)
								.build())
						.build();
			};
		}

		@NonNull
		private McpServer server() {
			return Optional.ofNullable(this.server.get()).orElseThrow(() ->
					new IllegalStateException(
							"The simulator server has not been created."));
		}
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
		Assertions.assertEquals(McpServerStatus.TERMINATED, diagnostics.getStatus());
		assertOffNetworkDiagnostics(diagnostics);
	}

	private static void assertRunningOffNetworkDiagnostics(
			@NonNull McpServer server) {
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.RUNNING, diagnostics.getStatus());
		assertOffNetworkDiagnostics(diagnostics);
	}

	private static void assertOffNetworkDiagnostics(
			@NonNull McpServerDiagnostics diagnostics) {
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

	private static void awaitLatchIgnoringInterrupt(
			@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		while (latch.getCount() != 0L) {
			try {
				latch.await();
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static long countEvents(
			@NonNull List<@NonNull McpMetricsEvent> events,
			@NonNull Class<?> eventType) {
		return events.stream().filter(eventType::isInstance).count();
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
		private final CountDownLatch handlerExecutionsFinished;

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
			this(expectedRequestRejections,
					expectedSimulatorLimitRequestFinishes,
					expectedRequestFinishes, 0);
		}

		private RecordingMetrics(int expectedRequestRejections,
				int expectedSimulatorLimitRequestFinishes,
				int expectedRequestFinishes,
				int expectedHandlerExecutionFinishes) {
			this.requestFinished = new CountDownLatch(expectedRequestFinishes);
			this.requestRejections = new CountDownLatch(expectedRequestRejections);
			this.simulatorLimitRequestFinishes =
					new CountDownLatch(expectedSimulatorLimitRequestFinishes);
			this.handlerExecutionsFinished =
					new CountDownLatch(expectedHandlerExecutionFinishes);
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
			if (event instanceof McpMetricsEvent.HandlerExecutionFinished)
				this.handlerExecutionsFinished.countDown();
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

		private boolean awaitHandlerExecutionsFinished() {
			return awaitLatch(this.handlerExecutionsFinished);
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
				@NonNull ParticipantShutdownResult result) {
			this.callbacks.incrementAndGet();
		}

		private int serverCallbacks() {
			return this.callbacks.get();
		}
	}
}
