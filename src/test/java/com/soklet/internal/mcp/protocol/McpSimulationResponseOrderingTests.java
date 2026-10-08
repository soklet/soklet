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

import com.soklet.CorsAuthorizer;
import com.soklet.HttpMethod;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyType;
import com.soklet.McpSimulationCompletion;
import com.soklet.McpSimulationOptions;
import com.soklet.McpSimulationStreamItem;
import com.soklet.McpStreamTerminationReason;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Admitted tool completion racing the first progress response head. */
@Timeout(30)
class McpSimulationResponseOrderingTests {
	@TestFactory
	Stream<DynamicTest> terminalResultWaitsForTheProgressResponseHead() {
		return List.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28)
				.stream().map(version -> DynamicTest.dynamicTest(version.getWireValue(),
						() -> verifyOrdering(version.getWireValue())));
	}

	private static void verifyOrdering(String revision) throws Exception {
		CountDownLatch openedBeforeHead = new CountDownLatch(1);
		CountDownLatch releaseHead = new CountDownLatch(1);
		CountDownLatch physicalHandlerExit = new CountDownLatch(1);
		CountDownLatch progressFinished = new CountDownLatch(1);
		AtomicReference<Thread> progressThread = new AtomicReference<>();
		AtomicReference<Throwable> progressFailure = new AtomicReference<>();
		AtomicReference<McpRequestOutcome> outcome = new AtomicReference<>();
		AtomicInteger streamCloses = new AtomicInteger();
		AtomicInteger requestFinishes = new AtomicInteger();
		McpRuntimeObservationSink observation = input -> new McpRuntimeRequestObservation() {
			@Override
			public Optional<McpRequestContext> publicContext() {
				return Optional.empty();
			}

			@Override
			public void didOpenRequestStream() {
				openedBeforeHead.countDown();
				await(releaseHead);
			}

			@Override
			public void didCloseRequestStream(StreamTerminationReason reason, Duration duration) {
				assertEquals(StreamTerminationReason.COMPLETED, reason);
				streamCloses.incrementAndGet();
			}

			@Override
			public void didFinish(McpRequestOutcome result, @Nullable McpJsonRpcError error,
					Duration duration, List<Throwable> throwables) {
				outcome.set(result);
				requestFinishes.incrementAndGet();
			}
		};
		McpNormalizedToolDescriptor descriptor = new McpNormalizedToolDescriptor(
				"race", new McpJsonObject(Map.of("type", new McpJsonString("object"))),
				Optional.empty(), McpJsonObject.empty(), McpJsonObject.empty());
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("simulation-ordering", "4.0.0"))
				.tool(McpNormalizedOperation.tool(descriptor, McpMirroredHeaderPlan.empty())).build();
		McpApplicationRequestHandler handler = invocation -> {
			Thread helper = new Thread(() -> {
				try {
					assertTrue(invocation.sendNotification(progress()));
				} catch (Throwable failure) {
					progressFailure.set(failure);
				} finally {
					progressFinished.countDown();
				}
			}, "simulation-ordering-progress");
			progressThread.set(helper);
			helper.start();
			assertTrue(openedBeforeHead.await(5, TimeUnit.SECONDS));
			return McpWireResult.complete(new McpJsonObject(Map.of("content",
					new McpJsonArray(List.of(new McpJsonObject(Map.of(
							"type", new McpJsonString("text"), "text", new McpJsonString("done"))))))));
		};
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromToolRoutes(Map.of(
				"race", new McpApplicationToolRoute(handler, ignored -> McpRateLimitDecision.allowed())));
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
						ignored -> McpRequestAdmissionDecision.ACCEPT), endpoint, router,
				observation, List.of(), Optional.empty(), Map.of(revision, endpoint));
		McpApplicationExecutionObserver executionObserver = new McpApplicationExecutionObserver() {
			@Override public void beginDeferral() {}
			@Override public void endDeferral() {}
			@Override public void drain() {}
			@Override public void recordHandlerExecutionStarted() {}
			@Override public void recordHandlerExecutionFinished() { physicalHandlerExit.countDown(); }
			@Override public void recordHandlerQueued() {}
			@Override public void recordHandlerDequeued() {}
			@Override public void recordHandlerCapacityRejected() {}
		};
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), List.of(binding),
				McpJsonLimits.productionDefaults(), McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM, McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {}, Optional.empty(),
				McpFrameworkRequestStateRuntime.disabledInstance(),
				McpSubscriptionRuntimeConfiguration.productionDefaults(), executionObserver);
		try (runtime;
				McpHttpServerRuntime.SimulationSession session = runtime.openSimulationSession();
				McpSimulation simulation = session.start(request(revision), McpSimulationOptions.defaultInstance())) {
			try {
				assertTrue(openedBeforeHead.await(5, TimeUnit.SECONDS));
				assertTrue(physicalHandlerExit.await(5, TimeUnit.SECONDS),
						"A reserved terminal result must release the handler before the head is published.");
				assertTrue(simulation.awaitResponse(Duration.ZERO).isEmpty());
				assertTrue(simulation.awaitStreamItem(Duration.ZERO).isEmpty());
				assertTrue(simulation.awaitCompletion(Duration.ZERO).isEmpty());
				assertEquals(0, streamCloses.get());
				assertEquals(0, requestFinishes.get());
				releaseHead.countDown();
				assertTrue(progressFinished.await(5, TimeUnit.SECONDS));
				assertNull(progressFailure.get());
				assertEquals(McpSimulationBodyType.SSE,
						simulation.awaitResponse(Duration.ofSeconds(5)).orElseThrow().getBodyType());
				McpSimulationStreamItem progress = simulation.awaitStreamItem(Duration.ofSeconds(5)).orElseThrow();
				McpSimulationStreamItem terminal = simulation.awaitStreamItem(Duration.ofSeconds(5)).orElseThrow();
				assertTrue(new String(progress.getEncodedBytes(), StandardCharsets.UTF_8)
						.contains("\"method\":\"notifications/progress\""));
				assertTrue(new String(terminal.getEncodedBytes(), StandardCharsets.UTF_8)
						.contains("\"text\":\"done\""));
				McpSimulationCompletion completion = simulation.awaitCompletion(Duration.ofSeconds(5)).orElseThrow();
				assertEquals(McpStreamTerminationReason.COMPLETED, completion.getReason());
				assertEquals(terminal.getMessage().orElseThrow(), completion.getTerminalMessage().orElseThrow());
				assertTrue(completion.getThrowables().isEmpty());
				assertEquals(McpRequestOutcome.COMPLETE, outcome.get());
				assertEquals(1, streamCloses.get());
				assertEquals(1, requestFinishes.get());
				assertTrue(simulation.awaitStreamItem(Duration.ZERO).isEmpty());
			} finally {
				releaseHead.countDown();
				Thread helper = progressThread.get();
				if (helper != null) {
					helper.join(5_000);
					assertFalse(helper.isAlive());
				}
			}
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(10, TimeUnit.SECONDS));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static McpJsonRpcMessage.Notification progress() {
		return new McpJsonRpcMessage.Notification("notifications/progress", Optional.of(
				new McpJsonObject(Map.of("progressToken", new McpJsonString("token"),
						"progress", new McpJsonNumber(BigDecimal.ONE)))), McpJsonObject.empty());
	}

	private static Request request(String revision) {
		String metadata = "\"progressToken\":\"token\"";
		if (revision.equals("2026-07-28"))
			metadata += ",\"io.modelcontextprotocol/protocolVersion\":\"" + revision
					+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"race\",\"arguments\":{},\"_meta\":{" + metadata + "}}}";
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Host", List.of("127.0.0.1:0"));
		headers.put("Content-Type", List.of("application/json"));
		headers.put("Accept", List.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", List.of(revision));
		headers.put("Mcp-Method", List.of("tools/call"));
		headers.put("Mcp-Name", List.of("race"));
		return Request.withPath(HttpMethod.POST, "/mcp").headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}
}
