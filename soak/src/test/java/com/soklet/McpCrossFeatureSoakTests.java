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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Longer-running live-listener soak coverage for MCP Phase 5 cross-feature
 * churn, cooperative cancelation, and restart cleanup.
 * <p>
 * {@code SOKLET_SOAK_PROFILE} selects the checked-in smoke or nightly workload
 * profile. Live-listener coverage speaks MCP over raw loopback sockets, while
 * simulator coverage exercises the same public application APIs off-network.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpCrossFeatureSoakTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String PROGRESS_TOOL = "soak.progress";
	private static final String PROTECTED_TOOL = "soak.protected-state";
	private static final String BLOCKING_TOOL = "soak.blocking";
	private static final String SIMULATOR_JSON_TOOL = "soak.simulator-json";
	private static final String SIMULATOR_CAPTURE_TOOL =
			"soak.simulator-capture";
	private static final String SIMULATOR_RESIDUAL_TOOL =
			"soak.simulator-residual";
	private static final int SIMULATOR_CASE_COUNT = 8;
	private static final int SIMULATOR_STREAM_ITEM_CAPACITY = 4;
	private static final int SIMULATOR_MAXIMUM_CAPTURED_BYTES = 4_096;
	private static final Duration ZERO_TIMEOUT = Duration.ZERO;
	private static final URI RESOURCE_URI = URI.create("soak://resource/current");
	private static final URI IGNORED_RESOURCE_URI =
			URI.create("soak://resource/ignored");
	private static final McpSoakProfile PROFILE =
			McpSoakProfile.fromSelectedProfile();

	@Test
	public void mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown()
			throws Exception {
		long startedAt = System.nanoTime();
		SoakState state = new SoakState();
		CountingSubscriptionPublisher publisher =
				new CountingSubscriptionPublisher();
		CountingMcpMetricsCollector metricsCollector =
				new CountingMcpMetricsCollector();
		CountingLifecycle lifecycle = new CountingLifecycle();
		McpServer mcpServer = mcpServer(state, publisher);
		SokletConfig config = SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector)
				.lifecycleObserver(lifecycle)
				.build();
		SoakResourceSnapshot baseline;
		SoakResourceSnapshot finalSnapshot;
		int workloadFeatureCycles = PROFILE.concurrentClients()
				* PROFILE.cyclesPerClient();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			// Warm every feature and lifecycle path before taking a stopped-state
			// resource baseline. The same Soklet and MCP server are then restarted.
			soklet.start();
			performFeatureCycle(boundPort(mcpServer), "warmup", state,
					publisher, true);
			awaitRuntimeIdle("warmup", metricsCollector, state,
					PROFILE.settleTimeout());
			soklet.stop();
			assertStopped(mcpServer);
			Assertions.assertEquals(0, state.openClientSockets.get());
			Assertions.assertEquals(0, publisher.activeRegistrationCount());
			baseline = SoakResourceSnapshot.captureAfterGc();

			RunResult mixedRun = null;
			for (int shutdownCycle = 0;
					shutdownCycle < PROFILE.shutdownCycles(); shutdownCycle++) {
				soklet.start();
				int port = boundPort(mcpServer);

				if (shutdownCycle == 0) {
					RunResult run = runConcurrent(PROFILE.concurrentClients(),
							PROFILE.cyclesPerClient(),
							(clientIndex, iteration) -> performFeatureCycle(port,
									"mixed-%d-%d".formatted(clientIndex, iteration),
									state, publisher, false));
					mixedRun = run;
					Assertions.assertTrue(run.failures().isEmpty(),
							() -> "Unexpected MCP mixed-churn failures: "
									+ run.failures());
					Assertions.assertEquals(workloadFeatureCycles,
							run.completed());
					awaitRuntimeIdle("mixed MCP feature churn", metricsCollector,
							state, PROFILE.settleTimeout());
				}

				performShutdownBoundary(soklet, mcpServer, port,
						"shutdown-" + shutdownCycle, state, publisher,
						metricsCollector);
				assertStopped(mcpServer);
			}

			Assertions.assertNotNull(mixedRun);
			awaitRuntimeIdle("final stopped MCP state", metricsCollector,
					state, PROFILE.settleTimeout());
			assertExactCounters(state, publisher, metricsCollector, lifecycle,
					workloadFeatureCycles);
			finalSnapshot = SoakResourceSnapshot.assertReturnsNear(
					"MCP Phase 5 cross-feature churn",
					baseline,
					PROFILE.settleTimeout(),
					PROFILE.resourceTolerance());
			SoakReport.recordPassedScenario(
					"MCP Phase 5 cross-feature churn",
					"clients=%d, cyclesPerClient=%d, shutdownCycles=%d, requestHandlerConcurrency=%d, requestHandlerQueueCapacity=%d, streamQueueCapacity=%d"
							.formatted(PROFILE.concurrentClients(),
									PROFILE.cyclesPerClient(),
									PROFILE.shutdownCycles(),
									PROFILE.requestHandlerConcurrency(),
									PROFILE.requestHandlerQueueCapacity(),
									PROFILE.streamQueueCapacity()),
					Duration.ofNanos(System.nanoTime() - startedAt),
					baseline,
					finalSnapshot,
					PROFILE.resourceTolerance(),
					SoakReport.observations(
							"Completed mixed feature cycles",
							Integer.toString(workloadFeatureCycles),
							"Progress terminal exchanges",
							Integer.toString(state.progressTerminals.get() - 1),
							"Framework-protected MRTR round trips",
							Integer.toString(state.protectedRoundTrips.get() - 1),
							"Filtered subscription disconnects",
							Integer.toString(
									state.filteredSubscriptionDisconnects.get() - 1),
							"Cooperative client-disconnect cancelations",
							Integer.toString(state.clientDisconnectCancelations.get() - 1),
							"Cooperative server-stop cancelations",
							Integer.toString(state.serverStoppingCancelations.get()),
							"Publisher registrations opened/closed",
							publisher.subscribeCount() + "/" + publisher.closeCount(),
							"MCP requests started/finished",
							metricsCollector.requestsStarted() + "/"
									+ metricsCollector.requestsFinished(),
							"MCP streams opened/closed",
							metricsCollector.streamsOpened() + "/"
									+ metricsCollector.streamsClosed(),
							"MCP subscriptions opened/closed",
							metricsCollector.subscriptionsOpened() + "/"
									+ metricsCollector.subscriptionsClosed(),
							"MCP server generations started/stopped",
							lifecycle.serversStarted() + "/"
									+ lifecycle.serversStopped(),
							"Final MCP status",
							mcpServer.getDiagnostics().getStatus().name(),
							"Active publisher registrations",
							Integer.toString(publisher.activeRegistrationCount()),
							"Open client sockets",
							Integer.toString(state.openClientSockets.get()),
							"Settle timeout", PROFILE.settleTimeout().toString()));
		} finally {
			state.releaseAllBlockingHandlers();
			mcpServer.stop();
		}
	}

	@Test
	public void mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup()
			throws Exception {
		long startedAt = System.nanoTime();
		SoakState state = new SoakState();
		CountingSubscriptionPublisher publisher =
				new CountingSubscriptionPublisher();
		CountingMcpMetricsCollector metricsCollector =
				new CountingMcpMetricsCollector();
		CountingLifecycle lifecycle = new CountingLifecycle();
		McpServer mcpServer = mcpServer(state, publisher);
		SokletConfig config = SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector)
				.lifecycleObserver(lifecycle)
				.build();
		AtomicIntegerArray caseCounts =
				new AtomicIntegerArray(SIMULATOR_CASE_COUNT);
		int workloadCycles = PROFILE.concurrentClients()
				* PROFILE.cyclesPerClient();
		int expectedPerCase = workloadCycles / SIMULATOR_CASE_COUNT;
		SoakResourceSnapshot baseline;
		SoakResourceSnapshot finalSnapshot;

		Assertions.assertEquals(0, workloadCycles % SIMULATOR_CASE_COUNT,
				"The checked-in soak profiles must exercise every simulator case equally.");
		try {
			// Warm all deterministic paths and the executor before measuring the
			// stopped, off-network resource baseline.
			Soklet.runSimulator(config, simulator -> {
				try {
					for (int caseIndex = 0; caseIndex < SIMULATOR_CASE_COUNT;
							caseIndex++)
						performSimulatorCase(simulator, caseIndex,
								"warmup-" + caseIndex, state, publisher);
				} catch (Exception e) {
					throw new AssertionError("Unable to warm simulator soak paths.", e);
				}
			});
			awaitSimulatorIdle("simulator warmup", metricsCollector, state,
					PROFILE.settleTimeout());
			assertSimulatorStoppedAndDrained(mcpServer, state, publisher,
					metricsCollector, lifecycle);
			baseline = SoakResourceSnapshot.captureAfterGc();

			AtomicReference<RunResult> runResult = new AtomicReference<>();
			Soklet.runSimulator(config, simulator -> {
				try {
					runResult.set(runConcurrent(PROFILE.concurrentClients(),
							PROFILE.cyclesPerClient(), (clientIndex, iteration) -> {
						int ordinal = clientIndex * PROFILE.cyclesPerClient()
								+ iteration;
						int caseIndex = ordinal % SIMULATOR_CASE_COUNT;
						performSimulatorCase(simulator, caseIndex,
								"measured-%d-%d".formatted(clientIndex,
										iteration), state, publisher);
						caseCounts.incrementAndGet(caseIndex);
					}));
				} catch (Exception e) {
					throw new AssertionError("Unable to run simulator soak workload.", e);
				}
			});
			RunResult completedRun = requireNonNull(runResult.get(),
					"The simulator workload did not publish a result.");
			Assertions.assertTrue(completedRun.failures().isEmpty(),
					() -> "Unexpected MCP simulator churn failures: "
							+ completedRun.failures());
			Assertions.assertEquals(workloadCycles, completedRun.completed());
			for (int caseIndex = 0; caseIndex < SIMULATOR_CASE_COUNT;
					caseIndex++)
				Assertions.assertEquals(expectedPerCase, caseCounts.get(caseIndex),
						"Unexpected execution count for simulator case " + caseIndex);

			awaitSimulatorIdle("measured simulator churn", metricsCollector,
					state, PROFILE.settleTimeout());
			performSimulatorResidualWave(config, mcpServer, state,
					metricsCollector);
			awaitSimulatorIdle("post-residual simulator recovery",
					metricsCollector, state, PROFILE.settleTimeout());
			assertSimulatorStoppedAndDrained(mcpServer, state, publisher,
					metricsCollector, lifecycle);
			finalSnapshot = SoakResourceSnapshot.assertReturnsNear(
					"MCP off-network simulator churn", baseline,
					PROFILE.settleTimeout(), PROFILE.resourceTolerance());
			SoakReport.recordPassedScenario(
					"MCP off-network simulator churn",
					"clients=%d, cyclesPerClient=%d, cases=%d, streamItemQueueCapacity=%d, maximumCapturedBytes=%d, residualWaves=1"
							.formatted(PROFILE.concurrentClients(),
									PROFILE.cyclesPerClient(), SIMULATOR_CASE_COUNT,
									SIMULATOR_STREAM_ITEM_CAPACITY,
									SIMULATOR_MAXIMUM_CAPTURED_BYTES),
					Duration.ofNanos(System.nanoTime() - startedAt),
					baseline,
					finalSnapshot,
					PROFILE.resourceTolerance(),
					SoakReport.observations(
							"Completed simulator feature cycles",
							Integer.toString(workloadCycles),
							"Executions per deterministic case",
							Integer.toString(expectedPerCase),
							"MCP requests started/finished",
							metricsCollector.requestsStarted() + "/"
									+ metricsCollector.requestsFinished(),
							"MCP streams opened/closed",
							metricsCollector.streamsOpened() + "/"
									+ metricsCollector.streamsClosed(),
							"MCP subscriptions opened/closed",
							metricsCollector.subscriptionsOpened() + "/"
									+ metricsCollector.subscriptionsClosed(),
							"MCP handler executions started/finished",
							metricsCollector.handlerExecutionsStarted() + "/"
									+ metricsCollector.handlerExecutionsFinished(),
							"Residual cleanup waves", "1",
							"Server/connection/transport metric events",
							Integer.toString(
									metricsCollector.transportBoundaryEvents()),
							"MCP server lifecycle callbacks",
							lifecycle.serversStarted() + "/"
									+ lifecycle.serversStopped(),
							"Final MCP status",
							mcpServer.getDiagnostics().getStatus().name(),
							"Active publisher registrations",
							Integer.toString(publisher.activeRegistrationCount()),
							"Open client sockets",
							Integer.toString(state.openClientSockets.get()),
							"Settle timeout", PROFILE.settleTimeout().toString()));
		} finally {
			state.releaseAllBlockingHandlers();
			state.releaseResidualHandler();
			mcpServer.stop();
		}
	}

	@NonNull
	private static McpServer mcpServer(@NonNull SoakState state,
			@NonNull CountingSubscriptionPublisher publisher) {
		requireNonNull(state);
		requireNonNull(publisher);
		McpInputRequestDeclaration form = McpInputRequestDeclaration
				.fromElicitationForm(McpInputRequirement.CONDITIONAL);
		McpJsonObject requestedSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put("answer", McpJsonObject.builder()
								.put("type", "string")
								.put("description", "Soak approval answer")
								.build())
						.build())
				.put("required", McpJsonArray.builder().add("answer").build())
				.build();
		McpToolRegistration<McpJsonObject> progressTool = McpToolRegistration
				.withName(PROGRESS_TOOL)
				.jsonArguments()
				.handler((request, call, features) -> {
					state.progressInvocations.incrementAndGet();
					McpProgressReporter reporter =
							features.require(McpProgressReporter.class);
					reporter.report(McpProgressUpdate.withProgress(0.0d)
							.total(100.0d).build());
					state.progressReports.incrementAndGet();
					reporter.report(McpProgressUpdate.withProgress(50.0d)
							.total(100.0d).build());
					state.progressReports.incrementAndGet();
					reporter.report(McpProgressUpdate.withProgress(100.0d)
							.total(100.0d).build());
					state.progressReports.incrementAndGet();
					return McpCompleteResult.fromToolText("progress complete");
				})
				.build();
		McpJsonObject frameworkState = McpJsonObject.builder()
				.put("phase", "awaiting-approval")
				.put("fixture", "mcp-cross-feature-soak")
				.build();
		McpToolRegistration<McpJsonObject> protectedTool = McpToolRegistration
				.withName(PROTECTED_TOOL)
				.jsonArguments()
				.handler((request, call, features) -> {
					if (request.getRequestState().isEmpty()) {
						if (!request.getInputResponses().asMap().isEmpty())
							throw new IllegalStateException(
									"Initial protected request carried input responses.");
						state.protectedInitialInvocations.incrementAndGet();
						return McpInputRequiredResult.builder()
								.inputRequest("approval", McpInputRequest
										.fromDeclaration(form, McpJsonObject.builder()
												.put("message",
														"Approve the soak protected-state exchange")
												.put("requestedSchema", requestedSchema)
												.build()))
								.frameworkRequestState(frameworkState)
								.build();
					}

					McpFrameworkRequestState requestState = requireType(
							request.getRequestState().orElseThrow(),
							McpFrameworkRequestState.class,
							"framework request state");
					McpJsonObject stateValue = requireType(requestState.value(),
							McpJsonObject.class, "framework request-state value");
					requireJsonString(stateValue, "phase", "awaiting-approval");
					requireJsonString(stateValue, "fixture",
							"mcp-cross-feature-soak");
					McpJsonObject approval = requireType(request.getInputResponses()
							.find("approval").orElseThrow(), McpJsonObject.class,
							"approval input response");
					requireJsonString(approval, "action", "accept");
					McpJsonObject content = requireType(
							approval.find("content").orElseThrow(),
							McpJsonObject.class, "approval content");
					requireJsonString(content, "answer", "approved");
					state.protectedRetryInvocations.incrementAndGet();
					return McpCompleteResult.fromToolText(
							"protected request state accepted");
				})
				.mayRequestInput(form)
				.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
				.build();
		McpToolRegistration<McpJsonObject> blockingTool = McpToolRegistration
				.withName(BLOCKING_TOOL)
				.jsonArguments()
				.handler((request, call, features) -> {
					String invocation = requireJsonString(call.getArguments(),
							"invocation");
					CancelationToken token = features.require(CancelationToken.class);
					BlockingObservation observation =
							state.beginBlocking(invocation);
					token.onCancel(() -> {
						StreamTerminationReason reason = token
								.getCancelationReason().orElse(null);
						observation.reason.set(reason);
						state.blockingCallbacks.incrementAndGet();
						if (reason == StreamTerminationReason.CLIENT_DISCONNECTED)
							state.clientDisconnectCancelations.incrementAndGet();
						else if (reason == StreamTerminationReason.SERVER_STOPPING)
							state.serverStoppingCancelations.incrementAndGet();
						observation.callbackInvoked.countDown();
						observation.release.countDown();
					});
					McpProgressReporter reporter =
							features.require(McpProgressReporter.class);
					reporter.report(McpProgressUpdate.withProgress(1.0d).build());
					state.blockingProgressReports.incrementAndGet();
					try {
						observation.release.await();
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					} finally {
						state.endBlocking(observation);
					}
					return McpCompleteResult.fromToolText("blocking request released");
				})
				.build();
		McpToolRegistration<McpJsonObject> simulatorJsonTool =
				McpToolRegistration.withName(SIMULATOR_JSON_TOOL)
						.jsonArguments()
						.handler((request, call, features) ->
								McpCompleteResult.fromToolText(
										"off-network simulator JSON complete"))
						.build();
		McpToolRegistration<McpJsonObject> simulatorCaptureTool =
				McpToolRegistration.withName(SIMULATOR_CAPTURE_TOOL)
						.jsonArguments()
						.handler((request, call, features) -> {
							String mode = requireJsonString(call.getArguments(), "mode");
							McpProgressReporter reporter =
									features.require(McpProgressReporter.class);
							if ("item".equals(mode)) {
								for (int index = 1; index <= 5; index++)
									reporter.report(McpProgressUpdate
											.withProgress((double) index).build());
							} else if ("byte".equals(mode)) {
								String message = "x".repeat(3_000);
								reporter.report(McpProgressUpdate.withProgress(1.0d)
										.message(message).build());
								reporter.report(McpProgressUpdate.withProgress(2.0d)
										.message(message).build());
							} else {
								throw new IllegalArgumentException(
										"Unknown simulator capture mode.");
							}
							return McpCompleteResult.fromToolText(
									"capture producer completed");
						})
						.build();
		McpToolRegistration<McpJsonObject> simulatorResidualTool =
				McpToolRegistration.withName(SIMULATOR_RESIDUAL_TOOL)
						.jsonArguments()
						.handler((request, call, features) -> {
							state.runResidualHandler();
							return McpCompleteResult.fromToolText(
									"residual handler released");
						})
						.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "MCP soak resource")
				.handler((request, read, features) ->
						McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
								.content(McpTextResourceContents.withUriAndText(
										read.getUri(), "MCP soak resource contents")
										.build())
								.build()))
				.build();
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationTypes(Set.of(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
						McpSubscriptionNotificationType.RESOURCE_UPDATED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation.withNameAndVersion(
						"soklet-mcp-soak", "3.6.0-SNAPSHOT").build())
				.tool(progressTool)
				.tool(protectedTool)
				.tool(blockingTool)
				.tool(simulatorJsonTool)
				.tool(simulatorCaptureTool)
				.tool(simulatorResidualTool)
				.resource(resource)
				.subscriptions(subscriptions)
				.build();

		return McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.protectionConfig(McpProtectionConfig.withKeyRing(
						McpProtectionKeyRing.withActiveKey(
								McpProtectionKey.fromIdAndBytes("soak-v1",
										"0123456789abcdef0123456789abcdef"
												.getBytes(StandardCharsets.US_ASCII)))
								.build()).build())
				.requestHandlerConcurrency(PROFILE.requestHandlerConcurrency())
				.requestHandlerQueueCapacity(
						PROFILE.requestHandlerQueueCapacity())
				.streamQueueCapacity(PROFILE.streamQueueCapacity())
				.requestTimeout(PROFILE.requestTimeout())
				.writeTimeout(PROFILE.writeTimeout())
				.keepAliveInterval(PROFILE.keepAliveInterval())
				.shutdownTimeout(PROFILE.shutdownTimeout())
				.maximumSubscriptionsPerPrincipal(
						PROFILE.maximumSubscriptionsPerPrincipal())
				.maximumSubscriptionDuration(
						PROFILE.maximumSubscriptionDuration())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.build();
	}

	private static void performSimulatorCase(@NonNull Simulator simulator,
			int caseIndex, @NonNull String cycleId, @NonNull SoakState state,
			@NonNull CountingSubscriptionPublisher publisher) throws Exception {
		requireNonNull(simulator);
		requireNonNull(cycleId);
		requireNonNull(state);
		requireNonNull(publisher);
		switch (caseIndex) {
			case 0 -> performSimulatorJson(simulator, cycleId + "-json");
			case 1 -> performSimulatorProgress(simulator, cycleId + "-progress");
			case 2 -> performSimulatorSubscription(simulator,
					cycleId + "-subscription", publisher);
			case 3 -> performSimulatorProtectedRoundTrip(simulator,
					cycleId + "-protected");
			case 4 -> performSimulatorCancel(simulator,
					cycleId + "-cancel", state);
			case 5 -> performSimulatorItemLimit(simulator,
					cycleId + "-item-limit");
			case 6 -> performSimulatorByteLimit(simulator,
					cycleId + "-byte-limit");
			case 7 -> performSimulatorCancelTerminalRace(simulator,
					cycleId + "-race", state);
			default -> throw new IllegalArgumentException(
					"Unknown simulator soak case " + caseIndex);
		}
	}

	private static void performSimulatorJson(@NonNull Simulator simulator,
			@NonNull String id) throws Exception {
		try (McpSimulation simulation = simulator.startMcpRequest(
				simulatorToolRequest(id, SIMULATOR_JSON_TOOL, "{}", null,
						null, null), simulatorOptions())) {
			McpSimulationResponse response = awaitSimulatorResponse(simulation);
			Assertions.assertEquals(200, response.getStatusCode());
			Assertions.assertEquals(McpSimulationBodyMode.JSON,
					response.getBodyMode());
			String body = new String(response.getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			assertContains(body, "\"id\":" + jsonString(id),
					"simulator JSON request ID");
			assertContains(body, "off-network simulator JSON complete",
					"simulator JSON result");
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitSimulatorCompletion(simulation).getReason());
			Assertions.assertTrue(simulation.nextStreamItem(ZERO_TIMEOUT).isEmpty());
		}
	}

	private static void performSimulatorProgress(@NonNull Simulator simulator,
			@NonNull String id) throws Exception {
		try (McpSimulation simulation = simulator.startMcpRequest(
				simulatorToolRequest(id, PROGRESS_TOOL, "{}", id + "-token",
						null, null), simulatorOptions())) {
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					awaitSimulatorResponse(simulation).getBodyMode());
			List<McpSimulationStreamItem> items = new ArrayList<>();
			for (int index = 0; index < 4; index++)
				items.add(awaitSimulatorItem(simulation));
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitSimulatorCompletion(simulation).getReason());
			Assertions.assertTrue(simulation.nextStreamItem(ZERO_TIMEOUT).isEmpty());
			Assertions.assertTrue(items.get(0).getMessage().isPresent());
			Assertions.assertTrue(items.get(3).getMessage().isPresent());
		}
	}

	private static void performSimulatorSubscription(
			@NonNull Simulator simulator, @NonNull String id,
			@NonNull CountingSubscriptionPublisher publisher) throws Exception {
		URI resourceUri = URI.create("soak://simulator/" + id);
		try (McpSimulation simulation = simulator.startMcpRequest(
				simulatorSubscriptionRequest(id, resourceUri), simulatorOptions())) {
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					awaitSimulatorResponse(simulation).getBodyMode());
			McpSimulationStreamItem acknowledgment =
					awaitSimulatorItem(simulation);
			assertContains(new String(acknowledgment.getEncodedBytes(),
					StandardCharsets.UTF_8),
					"notifications/subscriptions/acknowledged",
					"simulator subscription acknowledgement");
			publisher.publishResourceUpdated(resourceUri);
			McpSimulationStreamItem event = awaitSimulatorItem(simulation);
			String eventBytes = new String(event.getEncodedBytes(),
					StandardCharsets.UTF_8);
			assertContains(eventBytes, "notifications/resources/updated",
					"simulator subscription event");
			assertContains(eventBytes, resourceUri.toString(),
					"simulator subscribed resource URI");
			simulation.cancel();
			Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
					awaitSimulatorCompletion(simulation).getReason());
			Assertions.assertTrue(simulation.nextStreamItem(ZERO_TIMEOUT).isEmpty());
		}
	}

	private static void performSimulatorProtectedRoundTrip(
			@NonNull Simulator simulator, @NonNull String idPrefix) throws Exception {
		String capabilities = "{\"elicitation\":{\"form\":{}}}";
		String initialId = idPrefix + "-initial";
		String initialBody;
		try (McpSimulation initial = simulator.startMcpRequest(
				simulatorToolRequest(initialId, PROTECTED_TOOL, "{}", null,
						null, null, capabilities), simulatorOptions())) {
			McpSimulationResponse response = awaitSimulatorResponse(initial);
			Assertions.assertEquals(McpSimulationBodyMode.JSON,
					response.getBodyMode());
			initialBody = new String(response.getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			assertContains(initialBody, "\"resultType\":\"input_required\"",
					"simulator input-required result");
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitSimulatorCompletion(initial).getReason());
		}

		String requestState = extractJsonStringMember(initialBody,
				"requestState");
		String inputResponses = "{\"approval\":{\"action\":\"accept\","
				+ "\"content\":{\"answer\":\"approved\"}}}";
		String retryId = idPrefix + "-retry";
		try (McpSimulation retry = simulator.startMcpRequest(
				simulatorToolRequest(retryId, PROTECTED_TOOL, "{}", null,
						inputResponses, requestState, capabilities),
				simulatorOptions())) {
			String retryBody = new String(
					awaitSimulatorResponse(retry).getBody().orElseThrow(),
					StandardCharsets.UTF_8);
			assertContains(retryBody, "\"id\":" + jsonString(retryId),
					"simulator protected retry request ID");
			assertContains(retryBody, "protected request state accepted",
					"simulator protected retry result");
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitSimulatorCompletion(retry).getReason());
		}
	}

	private static void performSimulatorCancel(@NonNull Simulator simulator,
			@NonNull String id, @NonNull SoakState state) throws Exception {
		String invocation = id + "-invocation";
		BlockingObservation observation = state.prepareBlocking(invocation);
		try (McpSimulation simulation = simulator.startMcpRequest(
				simulatorToolRequest(id, BLOCKING_TOOL,
						"{\"invocation\":" + jsonString(invocation) + "}",
						id + "-token", null, null), simulatorOptions())) {
			Assertions.assertTrue(observation.handlerStarted.await(
					PROFILE.settleTimeout().toMillis(), TimeUnit.MILLISECONDS));
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					awaitSimulatorResponse(simulation).getBodyMode());
			awaitSimulatorItem(simulation);
			simulation.cancel();
			observation.awaitCanceledAndExited(PROFILE.settleTimeout(),
					StreamTerminationReason.CLIENT_DISCONNECTED);
			Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
					awaitSimulatorCompletion(simulation).getReason());
		} finally {
			observation.release.countDown();
			state.removeBlocking(invocation, observation);
		}
	}

	private static void performSimulatorItemLimit(@NonNull Simulator simulator,
			@NonNull String id) throws Exception {
		try (McpSimulation simulation = simulator.startMcpRequest(
				simulatorToolRequest(id, SIMULATOR_CAPTURE_TOOL,
						"{\"mode\":\"item\"}", id + "-token", null, null),
				simulatorOptions())) {
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					awaitSimulatorResponse(simulation).getBodyMode());
			Assertions.assertEquals(McpStreamTerminationReason
						.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED,
					awaitSimulatorCompletion(simulation).getReason());
			for (int index = 0; index < SIMULATOR_STREAM_ITEM_CAPACITY; index++)
				awaitSimulatorItem(simulation);
			Assertions.assertTrue(simulation.nextStreamItem(ZERO_TIMEOUT).isEmpty());
		}
	}

	private static void performSimulatorByteLimit(@NonNull Simulator simulator,
			@NonNull String id) throws Exception {
		try (McpSimulation simulation = simulator.startMcpRequest(
				simulatorToolRequest(id, SIMULATOR_CAPTURE_TOOL,
						"{\"mode\":\"byte\"}", id + "-token", null, null),
				simulatorOptions())) {
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					awaitSimulatorResponse(simulation).getBodyMode());
			Assertions.assertEquals(McpStreamTerminationReason
						.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
					awaitSimulatorCompletion(simulation).getReason());
			McpSimulationStreamItem retained = awaitSimulatorItem(simulation);
			Assertions.assertTrue(retained.getEncodedBytes().length
					<= SIMULATOR_MAXIMUM_CAPTURED_BYTES);
			Assertions.assertTrue(simulation.nextStreamItem(ZERO_TIMEOUT).isEmpty());
		}
	}

	private static void performSimulatorCancelTerminalRace(
			@NonNull Simulator simulator, @NonNull String id,
			@NonNull SoakState state) throws Exception {
		String invocation = id + "-invocation";
		BlockingObservation observation = state.prepareBlocking(invocation);
		try (McpSimulation simulation = simulator.startMcpRequest(
				simulatorToolRequest(id, BLOCKING_TOOL,
						"{\"invocation\":" + jsonString(invocation) + "}",
						id + "-token", null, null), simulatorOptions())) {
			Assertions.assertTrue(observation.handlerStarted.await(
					PROFILE.settleTimeout().toMillis(), TimeUnit.MILLISECONDS));
			awaitSimulatorResponse(simulation);
			awaitSimulatorItem(simulation);
			CyclicBarrier barrier = new CyclicBarrier(2);
			AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
			AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
			Thread cancelThread = new Thread(() -> {
				try {
					barrier.await();
					simulation.cancel();
				} catch (Throwable throwable) {
					cancelFailure.set(throwable);
				}
			}, "mcp-simulator-soak-cancel");
			Thread terminalThread = new Thread(() -> {
				try {
					barrier.await();
					observation.release.countDown();
				} catch (Throwable throwable) {
					terminalFailure.set(throwable);
				}
			}, "mcp-simulator-soak-terminal");
			cancelThread.start();
			terminalThread.start();
			joinSimulatorRaceThread(cancelThread);
			joinSimulatorRaceThread(terminalThread);
			Assertions.assertNull(cancelFailure.get());
			Assertions.assertNull(terminalFailure.get());
			Assertions.assertTrue(observation.handlerExited.await(
					PROFILE.settleTimeout().toMillis(), TimeUnit.MILLISECONDS));
			McpStreamTerminationReason reason =
					awaitSimulatorCompletion(simulation).getReason();
			Assertions.assertTrue(reason == McpStreamTerminationReason.COMPLETED
					|| reason == McpStreamTerminationReason.CLIENT_DISCONNECTED,
					"The cancel/terminal race must publish one coherent winner.");
			int remainingItems = 0;
			while (simulation.nextStreamItem(ZERO_TIMEOUT).isPresent())
				remainingItems++;
			Assertions.assertEquals(
					reason == McpStreamTerminationReason.COMPLETED ? 1 : 0,
					remainingItems);
		} finally {
			observation.release.countDown();
			state.removeBlocking(invocation, observation);
		}
	}

	private static void performSimulatorResidualWave(@NonNull SokletConfig config,
			@NonNull McpServer mcpServer, @NonNull SoakState state,
			@NonNull CountingMcpMetricsCollector metricsCollector)
			throws Exception {
		AtomicReference<Simulator> escapedSimulator = new AtomicReference<>();
		AtomicReference<McpSimulation> escapedSimulation = new AtomicReference<>();
		IllegalStateException cleanupFailure = Assertions.assertThrows(
				IllegalStateException.class, () -> Soklet.runSimulator(config,
						simulator -> {
							escapedSimulator.set(simulator);
							escapedSimulation.set(simulator.startMcpRequest(
									simulatorToolRequest("residual", SIMULATOR_RESIDUAL_TOOL,
											"{}", null, null, null),
									simulatorOptions()));
							try {
								Assertions.assertTrue(state.residualHandlerStarted.await(
										PROFILE.settleTimeout().toMillis(),
										TimeUnit.MILLISECONDS));
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								throw new AssertionError(e);
							}
						}));
		Assertions.assertFalse(cleanupFailure.getMessage() == null
				|| cleanupFailure.getMessage().isBlank(),
				"Residual simulator cleanup must remain diagnosable.");
		Simulator retainedSimulator = escapedSimulator.get();
		McpSimulation retainedSimulation = escapedSimulation.get();
		Assertions.assertNotNull(retainedSimulator);
		Assertions.assertNotNull(retainedSimulation);
		Assertions.assertThrows(IllegalStateException.class,
				() -> retainedSimulator.startMcpRequest(simulatorToolRequest(
						"residual-rejected", SIMULATOR_JSON_TOOL, "{}", null,
						null, null)));
		Assertions.assertThrows(IllegalStateException.class, mcpServer::start,
				"Live start must reject residual simulator work before binding.");
		assertStopped(mcpServer);
		Assertions.assertEquals(0,
				mcpServer.getDiagnostics().getActiveHandlerExecutions());
		Assertions.assertTrue(metricsCollector.handlerExecutionsStarted()
				> metricsCollector.handlerExecutionsFinished(),
				"Residual simulator work must remain accounted internally.");
		state.releaseResidualHandler();
		Assertions.assertTrue(state.residualHandlerExited.await(
				PROFILE.settleTimeout().toMillis(), TimeUnit.MILLISECONDS));
		awaitSimulatorIdle("released residual simulator handler",
				metricsCollector, state, PROFILE.settleTimeout());
		Assertions.assertTrue(retainedSimulation.isComplete());
		Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
				awaitSimulatorCompletion(retainedSimulation).getReason());

		// A new private generation must be available after the retained handler
		// actually exits; this remains off-network and leaves diagnostics stopped.
		Soklet.runSimulator(config, simulator -> {
			try {
				performSimulatorJson(simulator, "post-residual-recovery");
			} catch (Exception e) {
				throw new AssertionError("Simulator did not recover after residual work.",
						e);
			}
		});
		assertStopped(mcpServer);
	}

	@NonNull
	private static McpSimulationOptions simulatorOptions() {
		return McpSimulationOptions.builder()
				.streamItemQueueCapacity(SIMULATOR_STREAM_ITEM_CAPACITY)
				.maximumCapturedBytes(SIMULATOR_MAXIMUM_CAPTURED_BYTES)
				.build();
	}

	@NonNull
	private static Request simulatorToolRequest(@NonNull String id,
			@NonNull String toolName, @NonNull String argumentsJson,
			@Nullable String progressToken, @Nullable String inputResponsesJson,
			@Nullable String requestState) {
		return simulatorToolRequest(id, toolName, argumentsJson, progressToken,
				inputResponsesJson, requestState, "{}");
	}

	@NonNull
	private static Request simulatorToolRequest(@NonNull String id,
			@NonNull String toolName, @NonNull String argumentsJson,
			@Nullable String progressToken, @Nullable String inputResponsesJson,
			@Nullable String requestState, @NonNull String capabilitiesJson) {
		String body = toolCallBody(id, toolName, argumentsJson, capabilitiesJson,
				progressToken, inputResponsesJson, requestState);
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type",
						Set.of("application/json; charset=UTF-8"),
						"Accept",
						Set.of("application/json, text/event-stream"),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of("tools/call"),
						"Mcp-Name", Set.of(toolName)))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	private static Request simulatorSubscriptionRequest(@NonNull String id,
			@NonNull URI resourceUri) {
		String notifications = "{\"resourcesListChanged\":false,"
				+ "\"resourceSubscriptions\":["
				+ jsonString(resourceUri.toString()) + "]}";
		return Request.withPath(HttpMethod.POST, MCP_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type",
						Set.of("application/json; charset=UTF-8"),
						"Accept",
						Set.of("application/json, text/event-stream"),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of("subscriptions/listen")))
				.body(subscriptionBody(id, notifications)
						.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	private static McpSimulationResponse awaitSimulatorResponse(
			@NonNull McpSimulation simulation) throws InterruptedException {
		return simulation.awaitResponse(PROFILE.settleTimeout())
				.orElseThrow(() -> new AssertionError(
						"Timed out awaiting an MCP simulator response."));
	}

	@NonNull
	private static McpSimulationStreamItem awaitSimulatorItem(
			@NonNull McpSimulation simulation) throws InterruptedException {
		return simulation.nextStreamItem(PROFILE.settleTimeout())
				.orElseThrow(() -> new AssertionError(
						"Timed out awaiting an MCP simulator stream item."));
	}

	@NonNull
	private static McpSimulationCompletion awaitSimulatorCompletion(
			@NonNull McpSimulation simulation) throws InterruptedException {
		return simulation.awaitCompletion(PROFILE.settleTimeout())
				.orElseThrow(() -> new AssertionError(
						"Timed out awaiting MCP simulator completion."));
	}

	private static void joinSimulatorRaceThread(@NonNull Thread thread)
			throws InterruptedException {
		thread.join(PROFILE.settleTimeout().toMillis());
		Assertions.assertFalse(thread.isAlive(),
				"Simulator race participant did not terminate.");
	}

	private static void awaitSimulatorIdle(@NonNull String scenario,
			@NonNull CountingMcpMetricsCollector metrics,
			@NonNull SoakState state, @NonNull Duration timeout)
			throws InterruptedException {
		Assertions.assertTrue(metrics.awaitBalanced(timeout),
				() -> scenario + " did not balance MCP metric transitions: "
						+ metrics.describe(state));
		Assertions.assertEquals(0, state.activeBlockingHandlers.get());
		Assertions.assertEquals(0, state.activeResidualHandlers.get());
	}

	private static void assertSimulatorStoppedAndDrained(
			@NonNull McpServer mcpServer, @NonNull SoakState state,
			@NonNull CountingSubscriptionPublisher publisher,
			@NonNull CountingMcpMetricsCollector metrics,
			@NonNull CountingLifecycle lifecycle) {
		assertStopped(mcpServer);
		McpServerDiagnostics diagnostics = mcpServer.getDiagnostics();
		Assertions.assertEquals(0, diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(0, diagnostics.getQueuedRequests());
		Assertions.assertEquals(0, diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(0, diagnostics.getActiveSubscriptions());
		Assertions.assertEquals(0, state.activeBlockingHandlers.get());
		Assertions.assertEquals(0, state.activeResidualHandlers.get());
		Assertions.assertEquals(0, state.openClientSockets.get());
		Assertions.assertEquals(0, publisher.activeRegistrationCount());
		Assertions.assertEquals(metrics.requestsStarted(),
				metrics.requestsFinished());
		Assertions.assertEquals(metrics.streamsOpened(), metrics.streamsClosed());
		Assertions.assertEquals(metrics.subscriptionsOpened(),
				metrics.subscriptionsClosed());
		Assertions.assertEquals(metrics.handlerExecutionsStarted(),
				metrics.handlerExecutionsFinished());
		Assertions.assertEquals(0, metrics.transportBoundaryEvents());
		Assertions.assertEquals(0, lifecycle.serversStarted());
		Assertions.assertEquals(0, lifecycle.serversStopped());
	}

	private static void performFeatureCycle(int port, @NonNull String cycleId,
			@NonNull SoakState state,
			@NonNull CountingSubscriptionPublisher publisher,
			boolean verifyQuietFilter) throws Exception {
		requireNonNull(cycleId);
		requireNonNull(state);
		requireNonNull(publisher);
		performProgressTerminal(port, cycleId + "-progress", state);
		performProtectedRoundTrip(port, cycleId + "-protected", state);
		performFilteredSubscription(port, cycleId + "-subscription", state,
				publisher, verifyQuietFilter);
		performBlockingDisconnect(port, cycleId + "-blocking", state);
		state.featureCyclesCompleted.incrementAndGet();
	}

	private static void performProgressTerminal(int port, @NonNull String id,
			@NonNull SoakState state) throws Exception {
		String progressToken = id + "-token";
		String body = toolCallBody(id, PROGRESS_TOOL, "{}", "{}",
				progressToken, null, null);

		try (RawMcpClient client = RawMcpClient.post(port, body,
				"tools/call", PROGRESS_TOOL, state)) {
			assertSseHead(client.readHead());
			assertProgress(client.readDataChunk(), progressToken, "0");
			assertProgress(client.readDataChunk(), progressToken, "50");
			assertProgress(client.readDataChunk(), progressToken, "100");
			String terminal = client.readDataChunk();
			assertContains(terminal, "\"id\":" + jsonString(id),
					"progress terminal request ID");
			assertContains(terminal, "\"text\":\"progress complete\"",
					"progress terminal result");
			Assertions.assertNull(client.readChunk(),
					"Progress response did not terminate its HTTP chunk stream.");
		}

		state.progressTerminals.incrementAndGet();
	}

	private static void performProtectedRoundTrip(int port,
			@NonNull String idPrefix, @NonNull SoakState state) throws Exception {
		String initialId = idPrefix + "-initial";
		String retryId = idPrefix + "-retry";
		Assertions.assertNotEquals(initialId, retryId,
				"An MRTR retry must use a fresh JSON-RPC request ID.");
		String capabilities = "{\"elicitation\":{\"form\":{}}}";
		String initialBody = toolCallBody(initialId, PROTECTED_TOOL, "{}",
				capabilities, null, null, null);
		String initialResponse;

		try (RawMcpClient client = RawMcpClient.post(port, initialBody,
				"tools/call", PROTECTED_TOOL, state)) {
			RawMcpClient.HttpResponseHead head = client.readHead();
			assertJsonHead(head);
			initialResponse = client.readFixedBody(head);
		}

		assertContains(initialResponse, "\"id\":" + jsonString(initialId),
				"protected initial request ID");
		assertContains(initialResponse, "\"resultType\":\"input_required\"",
				"protected initial result type");
		assertContains(initialResponse, "\"method\":\"elicitation/create\"",
				"protected elicitation request");
		Assertions.assertFalse(initialResponse.contains("awaiting-approval"),
				"Framework-managed state leaked its plaintext marker.");
		String protectedState = extractJsonStringMember(initialResponse,
				"requestState");

		String inputResponses = "{\"approval\":{\"action\":\"accept\","
				+ "\"content\":{\"answer\":\"approved\"}}}";
		String retryBody = toolCallBody(retryId, PROTECTED_TOOL, "{}",
				capabilities, null, inputResponses, protectedState);
		String retryResponse;

		try (RawMcpClient client = RawMcpClient.post(port, retryBody,
				"tools/call", PROTECTED_TOOL, state)) {
			RawMcpClient.HttpResponseHead head = client.readHead();
			assertJsonHead(head);
			retryResponse = client.readFixedBody(head);
		}

		assertContains(retryResponse, "\"id\":" + jsonString(retryId),
				"protected retry request ID");
		assertContains(retryResponse, "\"resultType\":\"complete\"",
				"protected retry result type");
		assertContains(retryResponse,
				"\"text\":\"protected request state accepted\"",
				"protected retry result");
		state.protectedRoundTrips.incrementAndGet();
	}

	private static void performFilteredSubscription(int port,
			@NonNull String id, @NonNull SoakState state,
			@NonNull CountingSubscriptionPublisher publisher,
			boolean verifyQuietFilter) throws Exception {
		URI subscribedResourceUri = URI.create("soak://resource/" + id);
		String notifications = "{\"resourcesListChanged\":false,"
				+ "\"resourceSubscriptions\":["
				+ jsonString(subscribedResourceUri.toString())
				+ "]}";
		String body = subscriptionBody(id, notifications);

		try (RawMcpClient client = RawMcpClient.post(port, body,
				"subscriptions/listen", null, state)) {
			assertSseHead(client.readHead());
			String acknowledged = client.readDataChunk();
			assertContains(acknowledged,
					"\"method\":\"notifications/subscriptions/acknowledged\"",
					"subscription acknowledgement");
			Assertions.assertFalse(
					acknowledged.contains("\"resourcesListChanged\":true"),
					"Filtered subscription unexpectedly acknowledged list changes.");
			assertContains(acknowledged,
					jsonString(subscribedResourceUri.toString()),
					"subscription resource filter");
			state.filteredSubscriptionAcknowledgements.incrementAndGet();

			publisher.publishResourceUpdated(IGNORED_RESOURCE_URI);
			if (verifyQuietFilter)
				client.assertNoInputWithin(Duration.ofMillis(40));
			publisher.publishResourceUpdated(subscribedResourceUri);
			String update = client.readDataChunk();
			assertContains(update,
					"\"method\":\"notifications/resources/updated\"",
					"resource-updated notification");
			assertContains(update, "\"uri\":"
					+ jsonString(subscribedResourceUri.toString()),
					"subscribed resource URI");
			Assertions.assertFalse(update.contains(IGNORED_RESOURCE_URI.toString()),
					"An update outside the subscription filter reached the client.");
			state.filteredResourceUpdates.incrementAndGet();
			client.closeWithReset();
		}

		state.filteredSubscriptionDisconnects.incrementAndGet();
	}

	private static void performBlockingDisconnect(int port,
			@NonNull String invocation, @NonNull SoakState state) throws Exception {
		RawMcpClient client = openBlockingRequest(port, invocation, state);
		BlockingObservation observation = null;

		try {
			observation = state.awaitBlocking(invocation, PROFILE.settleTimeout());
			client.closeWithReset();
			observation.awaitCanceledAndExited(PROFILE.settleTimeout(),
					StreamTerminationReason.CLIENT_DISCONNECTED);
		} finally {
			client.close();
			if (observation != null) {
				observation.release.countDown();
				state.blockingByInvocation.remove(invocation, observation);
			}
		}
	}

	private static void performShutdownBoundary(@NonNull Soklet soklet,
			@NonNull McpServer mcpServer, int port, @NonNull String cycleId,
			@NonNull SoakState state,
			@NonNull CountingSubscriptionPublisher publisher,
			@NonNull CountingMcpMetricsCollector metricsCollector) throws Exception {
		requireNonNull(soklet);
		requireNonNull(mcpServer);
		requireNonNull(cycleId);
		requireNonNull(state);
		requireNonNull(publisher);
		requireNonNull(metricsCollector);
		String subscriptionId = cycleId + "-subscription";
		String blockingInvocation = cycleId + "-blocking";
		String notifications = "{\"resourcesListChanged\":true,"
				+ "\"resourceSubscriptions\":[]}";
		RawMcpClient subscription = RawMcpClient.post(port,
				subscriptionBody(subscriptionId, notifications),
				"subscriptions/listen", null, state);
		RawMcpClient blocking = null;
		BlockingObservation observation = null;
		Thread stopThread = null;
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();

		try {
			assertSseHead(subscription.readHead());
			String acknowledged = subscription.readDataChunk();
			assertContains(acknowledged,
					"\"method\":\"notifications/subscriptions/acknowledged\"",
					"shutdown subscription acknowledgement");
			state.shutdownSubscriptionAcknowledgements.incrementAndGet();

			blocking = openBlockingRequest(port, blockingInvocation, state);
			observation = state.awaitBlocking(blockingInvocation,
					PROFILE.settleTimeout());

			publisher.publishResourcesListChanged();
			String listChanged = subscription.readDataChunk();
			assertContains(listChanged,
					"\"method\":\"notifications/resources/list_changed\"",
					"resource-list-changed notification");
			state.shutdownListChangedNotifications.incrementAndGet();

			stopThread = new Thread(() -> {
				try {
					soklet.stop();
				} catch (Throwable throwable) {
					stopFailure.set(throwable);
				}
			}, "mcp-soak-stop-" + cycleId);
			stopThread.start();

			String terminal = subscription.readDataChunk();
			assertContains(terminal, "\"id\":" + jsonString(subscriptionId),
					"shutdown subscription terminal request ID");
			assertContains(terminal, "\"resultType\":\"complete\"",
					"shutdown subscription terminal result");
			Assertions.assertNull(subscription.readChunk(),
					"Shutdown subscription did not terminate its chunk stream.");
			blocking.awaitTransportClosure();
			observation.awaitCanceledAndExited(PROFILE.settleTimeout(),
					StreamTerminationReason.SERVER_STOPPING);
			joinStopThread(stopThread, stopFailure);
			assertStopped(mcpServer);
			Assertions.assertEquals(0, publisher.activeRegistrationCount());
			blocking.close();
			blocking = null;
			subscription.close();
			state.removeBlocking(blockingInvocation, observation);
			observation = null;
			awaitRuntimeIdle("MCP shutdown boundary " + cycleId,
					metricsCollector, state, PROFILE.settleTimeout());
			state.shutdownBoundariesCompleted.incrementAndGet();
		} finally {
			if (observation != null) {
				observation.release.countDown();
				state.blockingByInvocation.remove(blockingInvocation, observation);
			}
			if (blocking != null)
				blocking.close();
			subscription.close();
			if (stopThread != null && stopThread.isAlive()) {
				state.releaseAllBlockingHandlers();
				stopThread.join(PROFILE.settleTimeout().toMillis());
			}
		}
	}

	@NonNull
	private static RawMcpClient openBlockingRequest(int port,
			@NonNull String invocation, @NonNull SoakState state) throws Exception {
		String body = toolCallBody(invocation, BLOCKING_TOOL,
				"{\"invocation\":" + jsonString(invocation) + "}", "{}",
				invocation + "-progress", null, null);
		RawMcpClient client = RawMcpClient.post(port, body,
				"tools/call", BLOCKING_TOOL, state);
		try {
			assertSseHead(client.readHead());
			assertProgress(client.readDataChunk(), invocation + "-progress", "1");
			return client;
		} catch (Throwable throwable) {
			try {
				client.close();
			} catch (Throwable suppressed) {
				throwable.addSuppressed(suppressed);
			}
			throw throwable;
		}
	}

	private static void joinStopThread(@NonNull Thread stopThread,
			@NonNull AtomicReference<Throwable> stopFailure) throws Exception {
		stopThread.join(PROFILE.shutdownTimeout().plus(PROFILE.settleTimeout())
				.toMillis());
		Assertions.assertFalse(stopThread.isAlive(),
				"MCP server stop thread did not terminate within its bound.");
		Throwable failure = stopFailure.get();
		if (failure != null)
			throw new AssertionError("MCP server stop failed.", failure);
	}

	private static void assertExactCounters(@NonNull SoakState state,
			@NonNull CountingSubscriptionPublisher publisher,
			@NonNull CountingMcpMetricsCollector metricsCollector,
			@NonNull CountingLifecycle lifecycle,
			int workloadFeatureCycles) {
		int fullFeatureCycles = 1 + workloadFeatureCycles;
		int generations = 1 + PROFILE.shutdownCycles();
		int blockingInvocations = fullFeatureCycles + PROFILE.shutdownCycles();
		int subscriptions = fullFeatureCycles + PROFILE.shutdownCycles();
		int requests = 5 * fullFeatureCycles + 2 * PROFILE.shutdownCycles();
		int streams = 3 * fullFeatureCycles + 2 * PROFILE.shutdownCycles();
		int progressEvents = 4 * fullFeatureCycles + PROFILE.shutdownCycles();

		Assertions.assertEquals(fullFeatureCycles,
				state.featureCyclesCompleted.get());
		Assertions.assertEquals(fullFeatureCycles,
				state.progressInvocations.get());
		Assertions.assertEquals(3 * fullFeatureCycles,
				state.progressReports.get());
		Assertions.assertEquals(fullFeatureCycles, state.progressTerminals.get());
		Assertions.assertEquals(fullFeatureCycles,
				state.protectedInitialInvocations.get());
		Assertions.assertEquals(fullFeatureCycles,
				state.protectedRetryInvocations.get());
		Assertions.assertEquals(fullFeatureCycles,
				state.protectedRoundTrips.get());
		Assertions.assertEquals(fullFeatureCycles,
				state.filteredSubscriptionAcknowledgements.get());
		Assertions.assertEquals(fullFeatureCycles,
				state.filteredResourceUpdates.get());
		Assertions.assertEquals(fullFeatureCycles,
				state.filteredSubscriptionDisconnects.get());
		Assertions.assertEquals(PROFILE.shutdownCycles(),
				state.shutdownSubscriptionAcknowledgements.get());
		Assertions.assertEquals(PROFILE.shutdownCycles(),
				state.shutdownListChangedNotifications.get());
		Assertions.assertEquals(PROFILE.shutdownCycles(),
				state.shutdownBoundariesCompleted.get());
		Assertions.assertEquals(blockingInvocations,
				state.blockingInvocations.get());
		Assertions.assertEquals(blockingInvocations,
				state.blockingProgressReports.get());
		Assertions.assertEquals(blockingInvocations,
				state.blockingCallbacks.get());
		Assertions.assertEquals(blockingInvocations,
				state.blockingExits.get());
		Assertions.assertEquals(fullFeatureCycles,
				state.clientDisconnectCancelations.get());
		Assertions.assertEquals(PROFILE.shutdownCycles(),
				state.serverStoppingCancelations.get());
		Assertions.assertEquals(0, state.activeBlockingHandlers.get());
		Assertions.assertEquals(0, state.openClientSockets.get());
		Assertions.assertTrue(state.blockingByInvocation.isEmpty());

		Assertions.assertEquals(generations, publisher.subscribeCount());
		Assertions.assertEquals(generations, publisher.closeCount());
		Assertions.assertEquals(0, publisher.activeRegistrationCount());
		Assertions.assertEquals(2 * fullFeatureCycles,
				publisher.resourceUpdatedCount());
		Assertions.assertEquals(PROFILE.shutdownCycles(),
				publisher.resourcesListChangedCount());

		Assertions.assertEquals(generations, lifecycle.serversStarted());
		Assertions.assertEquals(generations, lifecycle.serversStopped());
		Assertions.assertEquals(Collections.nCopies(generations,
				McpShutdownOutcome.CLEAN), lifecycle.shutdownOutcomes());
		Assertions.assertEquals(requests, metricsCollector.requestsStarted());
		Assertions.assertEquals(requests, metricsCollector.requestsFinished());
		Assertions.assertEquals(streams, metricsCollector.streamsOpened());
		Assertions.assertEquals(streams, metricsCollector.streamsClosed());
		Assertions.assertEquals(subscriptions,
				metricsCollector.subscriptionsOpened());
		Assertions.assertEquals(subscriptions,
				metricsCollector.subscriptionsClosed());
		Assertions.assertEquals(progressEvents,
				metricsCollector.progressEvents());
		Assertions.assertTrue(metricsCollector.cancelationSignals()
				>= blockingInvocations,
				"Every blocking invocation must signal cooperative cancelation.");
		Assertions.assertTrue(metricsCollector.subscriptionCloseReasons().stream()
				.filter(reason -> reason == McpStreamTerminationReason.SERVER_STOPPED)
				.count() >= PROFILE.shutdownCycles(),
				"Every shutdown boundary must close its subscription as server-stopped.");
	}

	private static void awaitRuntimeIdle(@NonNull String scenario,
			@NonNull CountingMcpMetricsCollector metrics,
			@NonNull SoakState state, @NonNull Duration timeout)
			throws InterruptedException {
		requireNonNull(scenario);
		requireNonNull(metrics);
		requireNonNull(state);
		requireNonNull(timeout);
		long deadline = System.nanoTime() + timeout.toNanos();

		while (System.nanoTime() < deadline) {
			if (metrics.requestsStarted() == metrics.requestsFinished()
					&& metrics.streamsOpened() == metrics.streamsClosed()
					&& metrics.subscriptionsOpened() == metrics.subscriptionsClosed()
					&& state.activeBlockingHandlers.get() == 0
					&& state.openClientSockets.get() == 0)
				return;
			Thread.sleep(50L);
		}

		Assertions.fail("%s did not return to runtime idle within %s: %s"
				.formatted(scenario, timeout, metrics.describe(state)));
	}

	private static void assertStopped(@NonNull McpServer mcpServer) {
		McpServerDiagnostics diagnostics = mcpServer.getDiagnostics();
		Assertions.assertFalse(mcpServer.isStarted());
		Assertions.assertEquals(McpServerStatus.STOPPED,
				diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isEmpty());
	}

	private static int boundPort(@NonNull McpServer mcpServer) {
		McpServerDiagnostics diagnostics = mcpServer.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.STARTED,
				diagnostics.getStatus());
		InetSocketAddress address = diagnostics.getBoundAddress().orElseThrow();
		Assertions.assertTrue(address.getAddress().isLoopbackAddress());
		Assertions.assertTrue(address.getPort() > 0);
		return address.getPort();
	}

	@NonNull
	private static RunResult runConcurrent(int clients, int cyclesPerClient,
			@NonNull SoakOperation operation) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(clients);
		CountDownLatch ready = new CountDownLatch(clients);
		CountDownLatch start = new CountDownLatch(1);
		Queue<String> failures = new ConcurrentLinkedQueue<>();
		AtomicInteger completed = new AtomicInteger();

		try {
			for (int client = 0; client < clients; client++) {
				int clientIndex = client;
				executorService.submit(() -> {
					ready.countDown();
					try {
						if (!start.await(10, TimeUnit.SECONDS))
							throw new AssertionError(
									"Timed out waiting for the MCP soak start signal.");
						for (int cycle = 0; cycle < cyclesPerClient; cycle++) {
							try {
								operation.run(clientIndex, cycle);
								completed.incrementAndGet();
							} catch (Throwable throwable) {
								failures.add("client=%d cycle=%d %s: %s".formatted(
										clientIndex, cycle,
										throwable.getClass().getSimpleName(),
										throwable.getMessage()));
							}
						}
					} catch (Throwable throwable) {
						failures.add("client=%d setup %s: %s".formatted(
								clientIndex, throwable.getClass().getSimpleName(),
								throwable.getMessage()));
					}
				});
			}

			Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS),
					"MCP soak clients did not become ready.");
			start.countDown();
			executorService.shutdown();
			Assertions.assertTrue(executorService.awaitTermination(
					PROFILE.runTimeout().toMillis(), TimeUnit.MILLISECONDS),
					"Timed out waiting for MCP soak clients to finish.");
		} finally {
			executorService.shutdownNow();
		}

		return new RunResult(completed.get(), failures);
	}

	@NonNull
	private static String toolCallBody(@NonNull String id,
			@NonNull String toolName, @NonNull String argumentsJson,
			@NonNull String capabilitiesJson, @Nullable String progressToken,
			@Nullable String inputResponsesJson, @Nullable String requestState) {
		StringBuilder body = new StringBuilder(512)
				.append("{\"jsonrpc\":\"2.0\",\"id\":")
				.append(jsonString(id))
				.append(",\"method\":\"tools/call\",\"params\":{\"_meta\":{")
				.append("\"io.modelcontextprotocol/protocolVersion\":")
				.append(jsonString(PROTOCOL_VERSION))
				.append(",\"io.modelcontextprotocol/clientCapabilities\":")
				.append(capabilitiesJson);
		if (progressToken != null)
			body.append(",\"progressToken\":").append(jsonString(progressToken));
		body.append("},\"name\":").append(jsonString(toolName))
				.append(",\"arguments\":").append(argumentsJson);
		if (inputResponsesJson != null)
			body.append(",\"inputResponses\":").append(inputResponsesJson);
		if (requestState != null)
			body.append(",\"requestState\":").append(jsonString(requestState));
		return body.append("}}").toString();
	}

	@NonNull
	private static String subscriptionBody(@NonNull String id,
			@NonNull String notificationsJson) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + jsonString(id)
				+ ",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":"
				+ jsonString(PROTOCOL_VERSION) + ","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":" + notificationsJson + "}}";
	}

	private static void assertProgress(@NonNull String event,
			@NonNull String progressToken, @NonNull String progress) {
		assertContains(event, "\"method\":\"notifications/progress\"",
				"progress notification method");
		assertContains(event, "\"progressToken\":" + jsonString(progressToken),
				"progress token");
		assertContains(event, "\"progress\":" + progress,
				"progress value");
	}

	private static void assertSseHead(
			RawMcpClient.@NonNull HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
		Assertions.assertFalse(head.hasHeader("Content-Length"));
	}

	private static void assertJsonHead(
			RawMcpClient.@NonNull HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("application/json",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", head.singleHeader("Cache-Control"));
		Assertions.assertTrue(head.hasHeader("Content-Length"));
		Assertions.assertFalse(head.hasHeader("Transfer-Encoding"));
	}

	private static void assertContains(@NonNull String value,
			@NonNull String expectedFragment, @NonNull String description) {
		Assertions.assertTrue(value.contains(expectedFragment),
				() -> "Missing %s fragment %s in %s".formatted(
						description, expectedFragment, value));
	}

	@NonNull
	private static String requireJsonString(@NonNull McpJsonObject object,
			@NonNull String member) {
		return requireType(object.find(member).orElseThrow(), McpJsonString.class,
				"JSON member " + member).value();
	}

	private static void requireJsonString(@NonNull McpJsonObject object,
			@NonNull String member, @NonNull String expected) {
		String actual = requireJsonString(object, member);
		if (!expected.equals(actual))
			throw new IllegalStateException("Expected JSON member %s=%s, found %s"
					.formatted(member, expected, actual));
	}

	@NonNull
	private static <T> T requireType(@NonNull Object value,
			@NonNull Class<T> type, @NonNull String description) {
		if (!type.isInstance(value))
			throw new IllegalStateException("Expected %s to be %s, found %s"
					.formatted(description, type.getSimpleName(),
							value.getClass().getSimpleName()));
		return type.cast(value);
	}

	@NonNull
	private static String extractJsonStringMember(@NonNull String json,
			@NonNull String member) {
		String prefix = jsonString(member) + ":";
		int memberIndex = json.indexOf(prefix);
		if (memberIndex < 0)
			throw new AssertionError("Missing JSON member " + member + ": " + json);
		int start = memberIndex + prefix.length();
		if (start >= json.length() || json.charAt(start) != '"')
			throw new AssertionError("JSON member is not a string: " + member);
		StringBuilder decoded = new StringBuilder();
		for (int index = start + 1; index < json.length(); index++) {
			char character = json.charAt(index);
			if (character == '"')
				return decoded.toString();
			if (character != '\\') {
				decoded.append(character);
				continue;
			}
			if (++index >= json.length())
				break;
			char escaped = json.charAt(index);
			switch (escaped) {
				case '"', '\\', '/' -> decoded.append(escaped);
				case 'b' -> decoded.append('\b');
				case 'f' -> decoded.append('\f');
				case 'n' -> decoded.append('\n');
				case 'r' -> decoded.append('\r');
				case 't' -> decoded.append('\t');
				case 'u' -> {
					if (index + 4 >= json.length())
						throw new AssertionError("Truncated JSON Unicode escape.");
					decoded.append((char) Integer.parseInt(
							json.substring(index + 1, index + 5), 16));
					index += 4;
				}
				default -> throw new AssertionError(
						"Unsupported JSON string escape: \\" + escaped);
			}
		}
		throw new AssertionError("Unterminated JSON string member " + member);
	}

	@NonNull
	private static String jsonString(@NonNull String value) {
		StringBuilder encoded = new StringBuilder(value.length() + 2).append('"');
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '"' -> encoded.append("\\\"");
				case '\\' -> encoded.append("\\\\");
				case '\b' -> encoded.append("\\b");
				case '\f' -> encoded.append("\\f");
				case '\n' -> encoded.append("\\n");
				case '\r' -> encoded.append("\\r");
				case '\t' -> encoded.append("\\t");
				default -> {
					if (character < 0x20)
						encoded.append("\\u%04x".formatted((int) character));
					else
						encoded.append(character);
				}
			}
		}
		return encoded.append('"').toString();
	}

	@ThreadSafe
	private static final class SoakState {
		private final AtomicInteger featureCyclesCompleted = new AtomicInteger();
		private final AtomicInteger progressInvocations = new AtomicInteger();
		private final AtomicInteger progressReports = new AtomicInteger();
		private final AtomicInteger progressTerminals = new AtomicInteger();
		private final AtomicInteger protectedInitialInvocations = new AtomicInteger();
		private final AtomicInteger protectedRetryInvocations = new AtomicInteger();
		private final AtomicInteger protectedRoundTrips = new AtomicInteger();
		private final AtomicInteger filteredSubscriptionAcknowledgements =
				new AtomicInteger();
		private final AtomicInteger filteredResourceUpdates = new AtomicInteger();
		private final AtomicInteger filteredSubscriptionDisconnects =
				new AtomicInteger();
		private final AtomicInteger shutdownSubscriptionAcknowledgements =
				new AtomicInteger();
		private final AtomicInteger shutdownListChangedNotifications =
				new AtomicInteger();
		private final AtomicInteger shutdownBoundariesCompleted =
				new AtomicInteger();
		private final AtomicInteger blockingInvocations = new AtomicInteger();
		private final AtomicInteger blockingProgressReports = new AtomicInteger();
		private final AtomicInteger blockingCallbacks = new AtomicInteger();
		private final AtomicInteger blockingExits = new AtomicInteger();
		private final AtomicInteger activeBlockingHandlers = new AtomicInteger();
		private final AtomicInteger activeResidualHandlers = new AtomicInteger();
		private final AtomicInteger openClientSockets = new AtomicInteger();
		private final AtomicInteger clientDisconnectCancelations =
				new AtomicInteger();
		private final AtomicInteger serverStoppingCancelations =
				new AtomicInteger();
		private final ConcurrentHashMap<String, BlockingObservation>
				blockingByInvocation = new ConcurrentHashMap<>();
		private final CountDownLatch residualHandlerStarted = new CountDownLatch(1);
		private final CountDownLatch residualHandlerRelease = new CountDownLatch(1);
		private final CountDownLatch residualHandlerExited = new CountDownLatch(1);

		@NonNull
		private BlockingObservation prepareBlocking(@NonNull String invocation) {
			BlockingObservation observation = new BlockingObservation(invocation);
			if (this.blockingByInvocation.putIfAbsent(invocation, observation) != null)
				throw new IllegalStateException(
						"Duplicate prepared blocking invocation " + invocation);
			return observation;
		}

		@NonNull
		private BlockingObservation beginBlocking(@NonNull String invocation) {
			BlockingObservation candidate = new BlockingObservation(invocation);
			candidate.claimed.set(true);
			BlockingObservation prepared =
					this.blockingByInvocation.putIfAbsent(invocation, candidate);
			BlockingObservation observation = prepared == null ? candidate : prepared;
			if (prepared != null
					&& !observation.claimed.compareAndSet(false, true))
				throw new IllegalStateException(
						"Duplicate blocking invocation " + invocation);
			this.blockingInvocations.incrementAndGet();
			this.activeBlockingHandlers.incrementAndGet();
			observation.handlerStarted.countDown();
			return observation;
		}

		private void endBlocking(@NonNull BlockingObservation observation) {
			this.activeBlockingHandlers.decrementAndGet();
			this.blockingExits.incrementAndGet();
			observation.handlerExited.countDown();
		}

		@NonNull
		private BlockingObservation awaitBlocking(@NonNull String invocation,
				@NonNull Duration timeout) throws InterruptedException {
			long deadline = System.nanoTime() + timeout.toNanos();
			BlockingObservation observation;
			do {
				observation = this.blockingByInvocation.get(invocation);
				if (observation != null) {
					Assertions.assertTrue(observation.handlerStarted.await(
							timeout.toMillis(), TimeUnit.MILLISECONDS));
					return observation;
				}
				Thread.sleep(10L);
			} while (System.nanoTime() < deadline);
			throw new AssertionError(
					"Blocking handler did not start for " + invocation);
		}

		private void removeBlocking(@NonNull String invocation,
				@NonNull BlockingObservation observation) {
			Assertions.assertTrue(this.blockingByInvocation.remove(invocation,
					observation), "Blocking observation was not registered.");
		}

		private void releaseAllBlockingHandlers() {
			this.blockingByInvocation.values().forEach(
					observation -> observation.release.countDown());
		}

		private void runResidualHandler() {
			this.activeResidualHandlers.incrementAndGet();
			this.residualHandlerStarted.countDown();
			boolean interrupted = false;
			try {
				while (this.residualHandlerRelease.getCount() != 0) {
					try {
						this.residualHandlerRelease.await();
					} catch (InterruptedException e) {
						interrupted = true;
					}
				}
			} finally {
				this.activeResidualHandlers.decrementAndGet();
				this.residualHandlerExited.countDown();
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}

		private void releaseResidualHandler() {
			this.residualHandlerRelease.countDown();
		}
	}

	@ThreadSafe
	private static final class BlockingObservation {
		@NonNull
		private final String invocation;
		@NonNull
		private final CountDownLatch handlerStarted = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch callbackInvoked = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch release = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch handlerExited = new CountDownLatch(1);
		@NonNull
		private final AtomicReference<StreamTerminationReason> reason =
				new AtomicReference<>();
		private final AtomicBoolean claimed = new AtomicBoolean();

		private BlockingObservation(@NonNull String invocation) {
			this.invocation = requireNonNull(invocation);
		}

		private void awaitCanceledAndExited(@NonNull Duration timeout,
				@NonNull StreamTerminationReason expectedReason)
				throws InterruptedException {
			Assertions.assertTrue(this.callbackInvoked.await(timeout.toMillis(),
					TimeUnit.MILLISECONDS),
					"Cancelation callback did not run for " + this.invocation);
			Assertions.assertTrue(this.handlerExited.await(timeout.toMillis(),
					TimeUnit.MILLISECONDS),
					"Canceled handler did not exit for " + this.invocation);
			Assertions.assertEquals(expectedReason, this.reason.get(),
					"Unexpected cancelation reason for " + this.invocation);
		}
	}

	@ThreadSafe
	private static final class CountingSubscriptionPublisher
			implements McpSubscriptionEventPublisher {
		@NonNull
		private final McpLocalSubscriptionEventPublisher delegate =
				McpLocalSubscriptionEventPublisher.fromDefaults();
		@NonNull
		private final AtomicInteger subscribes = new AtomicInteger();
		@NonNull
		private final AtomicInteger closes = new AtomicInteger();
		@NonNull
		private final AtomicInteger resourcesListChanged = new AtomicInteger();
		@NonNull
		private final AtomicInteger resourceUpdated = new AtomicInteger();

		@Override
		@NonNull
		public McpSubscriptionEventSubscription subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			McpSubscriptionEventSubscription subscription =
					this.delegate.subscribe(listener);
			this.subscribes.incrementAndGet();
			AtomicBoolean open = new AtomicBoolean(true);
			return () -> {
				if (open.compareAndSet(true, false)) {
					subscription.close();
					this.closes.incrementAndGet();
				}
			};
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
			if (event instanceof McpSubscriptionEvent.ResourcesListChanged)
				this.resourcesListChanged.incrementAndGet();
			else if (event instanceof McpSubscriptionEvent.ResourceUpdated)
				this.resourceUpdated.incrementAndGet();
			this.delegate.publish(event);
		}

		private int subscribeCount() {
			return this.subscribes.get();
		}

		private int closeCount() {
			return this.closes.get();
		}

		private int activeRegistrationCount() {
			return subscribeCount() - closeCount();
		}

		private int resourcesListChangedCount() {
			return this.resourcesListChanged.get();
		}

		private int resourceUpdatedCount() {
			return this.resourceUpdated.get();
		}
	}

	@ThreadSafe
	private static final class CountingMcpMetricsCollector
			implements MetricsCollector {
		@NonNull
		private final Object balanceLock = new Object();
		@NonNull
		private final AtomicInteger requestsStarted = new AtomicInteger();
		@NonNull
		private final AtomicInteger requestsFinished = new AtomicInteger();
		@NonNull
		private final AtomicInteger streamsOpened = new AtomicInteger();
		@NonNull
		private final AtomicInteger streamsClosed = new AtomicInteger();
		@NonNull
		private final AtomicInteger subscriptionsOpened = new AtomicInteger();
		@NonNull
		private final AtomicInteger subscriptionsClosed = new AtomicInteger();
		@NonNull
		private final AtomicInteger cancelationSignals = new AtomicInteger();
		@NonNull
		private final AtomicInteger progressEvents = new AtomicInteger();
		@NonNull
		private final AtomicInteger handlerExecutionsStarted = new AtomicInteger();
		@NonNull
		private final AtomicInteger handlerExecutionsFinished = new AtomicInteger();
		@NonNull
		private final AtomicInteger transportBoundaryEvents = new AtomicInteger();
		@NonNull
		private final Queue<McpStreamTerminationReason> subscriptionCloseReasons =
				new ConcurrentLinkedQueue<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			try {
				if (event instanceof McpMetricsEvent.RequestStarted)
					this.requestsStarted.incrementAndGet();
				else if (event instanceof McpMetricsEvent.RequestFinished)
					this.requestsFinished.incrementAndGet();
				else if (event instanceof McpMetricsEvent.RequestStreamOpened)
					this.streamsOpened.incrementAndGet();
				else if (event instanceof McpMetricsEvent.RequestStreamClosed)
					this.streamsClosed.incrementAndGet();
				else if (event instanceof McpMetricsEvent.SubscriptionOpened)
					this.subscriptionsOpened.incrementAndGet();
				else if (event instanceof McpMetricsEvent.SubscriptionClosed closed) {
					this.subscriptionsClosed.incrementAndGet();
					this.subscriptionCloseReasons.add(closed.reason());
				} else if (event instanceof McpMetricsEvent.CancelationSignaled)
					this.cancelationSignals.incrementAndGet();
				else if (event instanceof McpMetricsEvent.ProgressEmitted)
					this.progressEvents.incrementAndGet();
				else if (event instanceof McpMetricsEvent.HandlerExecutionStarted)
					this.handlerExecutionsStarted.incrementAndGet();
				else if (event instanceof McpMetricsEvent.HandlerExecutionFinished)
					this.handlerExecutionsFinished.incrementAndGet();
				else if (event instanceof McpMetricsEvent.ServerStarted
						|| event instanceof McpMetricsEvent.ServerStopped
						|| event instanceof McpMetricsEvent.ConnectionAccepted
						|| event instanceof McpMetricsEvent.ConnectionRejected
						|| event instanceof McpMetricsEvent.TransportFailure)
					this.transportBoundaryEvents.incrementAndGet();
			} finally {
				synchronized (this.balanceLock) {
					this.balanceLock.notifyAll();
				}
			}
		}

		private int requestsStarted() {
			return this.requestsStarted.get();
		}

		private int requestsFinished() {
			return this.requestsFinished.get();
		}

		private int streamsOpened() {
			return this.streamsOpened.get();
		}

		private int streamsClosed() {
			return this.streamsClosed.get();
		}

		private int subscriptionsOpened() {
			return this.subscriptionsOpened.get();
		}

		private int subscriptionsClosed() {
			return this.subscriptionsClosed.get();
		}

		private int cancelationSignals() {
			return this.cancelationSignals.get();
		}

		private int progressEvents() {
			return this.progressEvents.get();
		}

		private int handlerExecutionsStarted() {
			return this.handlerExecutionsStarted.get();
		}

		private int handlerExecutionsFinished() {
			return this.handlerExecutionsFinished.get();
		}

		private int transportBoundaryEvents() {
			return this.transportBoundaryEvents.get();
		}

		private boolean awaitBalanced(@NonNull Duration timeout)
				throws InterruptedException {
			long timeoutNanos;
			try {
				timeoutNanos = requireNonNull(timeout).toNanos();
			} catch (ArithmeticException ignored) {
				timeoutNanos = Long.MAX_VALUE;
			}
			long startedAt = System.nanoTime();
			synchronized (this.balanceLock) {
				long remaining = timeoutNanos;
				while (!isBalanced() && remaining > 0L) {
					TimeUnit.NANOSECONDS.timedWait(this.balanceLock, remaining);
					long elapsed = System.nanoTime() - startedAt;
					remaining = elapsed >= timeoutNanos ? 0L
							: timeoutNanos - elapsed;
				}
				return isBalanced();
			}
		}

		private boolean isBalanced() {
			return requestsStarted() == requestsFinished()
					&& streamsOpened() == streamsClosed()
					&& subscriptionsOpened() == subscriptionsClosed()
					&& handlerExecutionsStarted() == handlerExecutionsFinished();
		}

		@NonNull
		private List<McpStreamTerminationReason> subscriptionCloseReasons() {
			return List.copyOf(this.subscriptionCloseReasons);
		}

		@NonNull
		private String describe(@NonNull SoakState state) {
			return ("requests=%d/%d streams=%d/%d subscriptions=%d/%d "
					+ "handlers=%d/%d activeBlocking=%d activeResidual=%d "
					+ "openClientSockets=%d")
					.formatted(requestsStarted(), requestsFinished(),
							streamsOpened(), streamsClosed(), subscriptionsOpened(),
							subscriptionsClosed(),
							handlerExecutionsStarted(), handlerExecutionsFinished(),
							state.activeBlockingHandlers.get(),
							state.activeResidualHandlers.get(),
							state.openClientSockets.get());
		}
	}

	@ThreadSafe
	private static final class CountingLifecycle implements LifecycleObserver {
		@NonNull
		private final AtomicInteger serversStarted = new AtomicInteger();
		@NonNull
		private final AtomicInteger serversStopped = new AtomicInteger();
		@NonNull
		private final Queue<McpShutdownOutcome> shutdownOutcomes =
				new ConcurrentLinkedQueue<>();

		@Override
		public void didStartMcpServer(@NonNull McpServer mcpServer) {
			this.serversStarted.incrementAndGet();
		}

		@Override
		public void didStopMcpServer(@NonNull McpServer mcpServer,
				@NonNull McpShutdownOutcome shutdownOutcome) {
			this.serversStopped.incrementAndGet();
			this.shutdownOutcomes.add(shutdownOutcome);
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Keep soak-test output focused on assertion failures.
		}

		private int serversStarted() {
			return this.serversStarted.get();
		}

		private int serversStopped() {
			return this.serversStopped.get();
		}

		@NonNull
		private List<McpShutdownOutcome> shutdownOutcomes() {
			return List.copyOf(this.shutdownOutcomes);
		}
	}

	@NotThreadSafe
	private static final class RawMcpClient implements AutoCloseable {
		private static final int MAXIMUM_HEAD_BYTES = 64 * 1_024;
		private static final int MAXIMUM_CHUNK_BYTES = 20 * 1_024 * 1_024;
		@NonNull
		private final Socket socket;
		@NonNull
		private final InputStream inputStream;
		@NonNull
		private final SoakState state;
		@NonNull
		private final AtomicBoolean open;
		private boolean terminalChunkRead;

		@NonNull
		private static RawMcpClient post(int port, @NonNull String body,
				@NonNull String method, @Nullable String name,
				@NonNull SoakState state) throws IOException {
			RawMcpClient client = new RawMcpClient(port, state);
			try {
				client.write(body, method, name, port);
				return client;
			} catch (IOException | RuntimeException | Error throwable) {
				try {
					client.close();
				} catch (Throwable suppressed) {
					throwable.addSuppressed(suppressed);
				}
				throw throwable;
			}
		}

		private RawMcpClient(int port, @NonNull SoakState state) throws IOException {
			this.socket = new Socket();
			this.state = requireNonNull(state);
			this.open = new AtomicBoolean();
			try {
				this.socket.setTcpNoDelay(true);
				this.socket.setSoTimeout(Math.toIntExact(
						PROFILE.clientSocketTimeout().toMillis()));
				this.socket.connect(new InetSocketAddress(LOOPBACK, port),
						Math.toIntExact(PROFILE.clientSocketTimeout().toMillis()));
				this.inputStream = this.socket.getInputStream();
				this.state.openClientSockets.incrementAndGet();
				this.open.set(true);
			} catch (IOException | RuntimeException | Error throwable) {
				try {
					this.socket.close();
				} catch (Throwable suppressed) {
					throwable.addSuppressed(suppressed);
				}
				throw throwable;
			}
		}

		private void write(@NonNull String body, @NonNull String method,
				@Nullable String name, int port) throws IOException {
			byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
			StringBuilder head = new StringBuilder(512)
					.append("POST ").append(MCP_PATH).append(" HTTP/1.1\r\n")
					.append("Host: ").append(LOOPBACK).append(':').append(port)
					.append("\r\n")
					.append("Content-Type: application/json; charset=UTF-8\r\n")
					.append("Accept: application/json, text/event-stream\r\n")
					.append("MCP-Protocol-Version: ").append(PROTOCOL_VERSION)
					.append("\r\n")
					.append("Mcp-Method: ").append(method).append("\r\n");
			if (name != null)
				head.append("Mcp-Name: ").append(name).append("\r\n");
			head.append("Content-Length: ").append(bodyBytes.length)
					.append("\r\n\r\n");
			this.socket.getOutputStream().write(
					head.toString().getBytes(StandardCharsets.ISO_8859_1));
			this.socket.getOutputStream().write(bodyBytes);
			this.socket.getOutputStream().flush();
		}

		@NonNull
		private HttpResponseHead readHead() throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			int matched = 0;
			while (bytes.size() < MAXIMUM_HEAD_BYTES) {
				int value = this.inputStream.read();
				if (value < 0)
					throw new EOFException(
							"Socket closed before the HTTP response head completed.");
				bytes.write(value);
				matched = switch (matched) {
					case 0 -> value == '\r' ? 1 : 0;
					case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
					case 2 -> value == '\r' ? 3 : 0;
					case 3 -> value == '\n' ? 4 : 0;
					default -> matched;
				};
				if (matched == 4)
					break;
			}
			if (matched != 4)
				throw new IOException("HTTP response head exceeded the test bound.");

			String raw = bytes.toString(StandardCharsets.ISO_8859_1);
			String[] lines = raw.substring(0, raw.length() - 4).split("\\r\\n");
			String[] statusParts = lines[0].split(" ", 3);
			if (statusParts.length < 2)
				throw new IOException("Malformed HTTP status line: " + lines[0]);
			Map<String, List<String>> headers = new LinkedHashMap<>();
			for (int index = 1; index < lines.length; index++) {
				int colon = lines[index].indexOf(':');
				if (colon < 1)
					throw new IOException("Malformed response header: " + lines[index]);
				String headerName = lines[index].substring(0, colon).trim()
						.toLowerCase(Locale.ROOT);
				String headerValue = lines[index].substring(colon + 1).trim();
				headers.computeIfAbsent(headerName, ignored -> new ArrayList<>())
						.add(headerValue);
			}
			Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
			headers.forEach((headerName, values) ->
					copiedHeaders.put(headerName, List.copyOf(values)));
			return new HttpResponseHead(raw,
					Integer.parseInt(statusParts[1]), Map.copyOf(copiedHeaders));
		}

		@NonNull
		private String readFixedBody(@NonNull HttpResponseHead head)
				throws IOException {
			int length = Integer.parseInt(head.singleHeader("Content-Length"));
			return new String(readExactly(length), StandardCharsets.UTF_8);
		}

		@NonNull
		private String readDataChunk() throws IOException {
			while (true) {
				byte[] chunk = readChunk();
				if (chunk == null)
					throw new EOFException("Expected another SSE data chunk.");
				String text = new String(chunk, StandardCharsets.UTF_8);
				if (text.startsWith("data: "))
					return text;
				if (!text.startsWith(":"))
					throw new IOException("Unexpected non-data SSE chunk: " + text);
			}
		}

		private byte @Nullable [] readChunk() throws IOException {
			if (this.terminalChunkRead)
				return null;
			String sizeLine = readCrlfLine();
			int extension = sizeLine.indexOf(';');
			String hexadecimal = (extension < 0 ? sizeLine
					: sizeLine.substring(0, extension)).trim();
			long size;
			try {
				size = Long.parseLong(hexadecimal, 16);
			} catch (NumberFormatException exception) {
				throw new IOException("Malformed HTTP chunk size: " + sizeLine,
						exception);
			}
			if (size == 0L) {
				String trailer;
				do {
					trailer = readCrlfLine();
				} while (!trailer.isEmpty());
				this.terminalChunkRead = true;
				return null;
			}
			if (size < 0L || size > MAXIMUM_CHUNK_BYTES)
				throw new IOException("HTTP chunk exceeds the test bound: " + size);
			byte[] payload = readExactly((int) size);
			if (this.inputStream.read() != '\r' || this.inputStream.read() != '\n')
				throw new IOException("HTTP chunk payload was not followed by CRLF.");
			return payload;
		}

		private void assertNoInputWithin(@NonNull Duration duration)
				throws IOException {
			int originalTimeout = this.socket.getSoTimeout();
			try {
				this.socket.setSoTimeout(Math.toIntExact(duration.toMillis()));
				int value = this.inputStream.read();
				Assertions.fail("Filtered subscription unexpectedly produced byte "
						+ value + ".");
			} catch (SocketTimeoutException expected) {
				// The ignored resource update correctly produced no wire output.
			} finally {
				this.socket.setSoTimeout(originalTimeout);
			}
		}

		private void closeWithReset() throws IOException {
			if (this.open.get()) {
				this.socket.setSoLinger(true, 0);
				close();
			}
		}

		private void awaitTransportClosure() throws IOException {
			try {
				while (this.inputStream.read() >= 0) {
					// Discard until shutdown closes the transport.
				}
			} catch (SocketException expected) {
				// A reset is also a closed shutdown transport.
			}
		}

		@NonNull
		private String readCrlfLine() throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			boolean carriageReturn = false;
			while (bytes.size() < MAXIMUM_HEAD_BYTES) {
				int value = this.inputStream.read();
				if (value < 0)
					throw new EOFException(
							"Socket closed while reading an HTTP chunk line.");
				if (carriageReturn && value == '\n') {
					byte[] line = bytes.toByteArray();
					return new String(line, 0, line.length - 1,
							StandardCharsets.US_ASCII);
				}
				bytes.write(value);
				carriageReturn = value == '\r';
			}
			throw new IOException("HTTP chunk line exceeded the test bound.");
		}

		private byte @NonNull [] readExactly(int length) throws IOException {
			byte[] bytes = new byte[length];
			int offset = 0;
			while (offset < bytes.length) {
				int read = this.inputStream.read(bytes, offset, bytes.length - offset);
				if (read < 0)
					throw new EOFException("Socket closed with "
							+ (bytes.length - offset) + " bytes remaining.");
				offset += read;
			}
			return bytes;
		}

		@Override
		public void close() throws IOException {
			if (this.open.compareAndSet(true, false)) {
				try {
					this.socket.close();
				} finally {
					this.state.openClientSockets.decrementAndGet();
				}
			}
		}

		private record HttpResponseHead(@NonNull String raw, int status,
				@NonNull Map<@NonNull String, @NonNull List<@NonNull String>> headers) {
			@NonNull
			private String singleHeader(@NonNull String name) {
				List<String> values = this.headers.get(
						name.toLowerCase(Locale.ROOT));
				if (values == null || values.size() != 1)
					throw new AssertionError("Expected exactly one " + name
							+ " header, found " + values + "; response=" + this.raw);
				return values.get(0);
			}

			private boolean hasHeader(@NonNull String name) {
				return this.headers.containsKey(name.toLowerCase(Locale.ROOT));
			}
		}
	}

	@ThreadSafe
	private record RunResult(int completed,
			@NonNull Queue<@NonNull String> failures) {
	}

	@ThreadSafe
	@FunctionalInterface
	private interface SoakOperation {
		void run(int clientIndex, int iteration) throws Exception;
	}

	@ThreadSafe
	private record McpSoakProfile(
			@NonNull Duration clientSocketTimeout,
			int concurrentClients,
			int cyclesPerClient,
			@NonNull Duration keepAliveInterval,
			@NonNull Duration maximumSubscriptionDuration,
			int maximumSubscriptionsPerPrincipal,
			int requestHandlerConcurrency,
			int requestHandlerQueueCapacity,
			@NonNull Duration requestTimeout,
			SoakResourceSnapshot.@NonNull ResourceTolerance resourceTolerance,
			@NonNull Duration runTimeout,
			@NonNull Duration settleTimeout,
			int shutdownCycles,
			@NonNull Duration shutdownTimeout,
			int streamQueueCapacity,
			@NonNull Duration writeTimeout) {
		@NonNull
		private static McpSoakProfile fromSelectedProfile() {
			SoakProfiles.SelectedProfile profile = SoakProfiles.selected();
			return new McpSoakProfile(
					profile.durationMillis("mcp.clientSocketTimeoutMillis"),
					profile.integer("mcp.concurrentClients"),
					profile.integer("mcp.cyclesPerClient"),
					profile.durationMillis("mcp.keepAliveIntervalMillis"),
					profile.durationMillis(
							"mcp.maximumSubscriptionDurationMillis"),
					profile.integer("mcp.maximumSubscriptionsPerPrincipal"),
					profile.integer("mcp.requestHandlerConcurrency"),
					profile.integer("mcp.requestHandlerQueueCapacity"),
					profile.durationMillis("mcp.requestTimeoutMillis"),
					new SoakResourceSnapshot.ResourceTolerance(
							profile.number(
									"mcp.resourceTolerance.maxOpenFileDescriptorGrowth"),
							profile.number(
									"mcp.resourceTolerance.maxHeapGrowthBytes"),
							profile.integer(
									"mcp.resourceTolerance.maxLiveThreadGrowth")),
					profile.durationMillis("mcp.runTimeoutMillis"),
					profile.durationMillis("mcp.settleTimeoutMillis"),
					profile.integer("mcp.shutdownCycles"),
					profile.durationMillis("mcp.shutdownTimeoutMillis"),
					profile.integer("mcp.streamQueueCapacity"),
					profile.durationMillis("mcp.writeTimeoutMillis"));
		}
	}
}
