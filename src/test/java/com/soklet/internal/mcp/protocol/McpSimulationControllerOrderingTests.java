/*
 * Copyright 2026 Revetware LLC.
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

import com.soklet.HttpMethod;
import com.soklet.McpAdmissionController;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpJsonObject;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyType;
import com.soklet.McpSimulationCompletion;
import com.soklet.McpSimulationOptions;
import com.soklet.McpSimulationStreamItem;
import com.soklet.McpSimulationStreamItemType;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpToolRegistration;
import com.soklet.Request;
import com.soklet.ResourceMethodResolver;
import com.soklet.SimulatorConfig;
import com.soklet.SokletSimulator;
import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real request controller, rather than a capture-only cancel stub. */
@Timeout(60)
public class McpSimulationControllerOrderingTests {

	@Test
	@Timeout(150)
	public void capturedTerminalWinsWhenCloseIsAlreadyWaitingToReachItsController() {
		assertControllerOrdering(McpProtocolVersion.V2026_07_28, true);
	}

	@Test
	@Timeout(150)
	public void disconnectWinsWhenItsControllerRunsBeforeTerminalCapture() {
		assertControllerOrdering(McpProtocolVersion.V2026_07_28, false);
	}

	@Test
	@Timeout(150)
	public void june2025CapturedTerminalWinsBeforeLegacyWriterDetach() {
		assertControllerOrdering(McpProtocolVersion.V2025_06_18, true);
	}

	@Test
	@Timeout(150)
	public void june2025DisconnectDetachesBeforeTerminalCapture() {
		assertControllerOrdering(McpProtocolVersion.V2025_06_18, false);
	}

	@Test
	@Timeout(150)
	public void november2025CapturedTerminalWinsBeforeLegacyWriterDetach() {
		assertControllerOrdering(McpProtocolVersion.V2025_11_25, true);
	}

	@Test
	@Timeout(150)
	public void november2025DisconnectDetachesBeforeTerminalCapture() {
		assertControllerOrdering(McpProtocolVersion.V2025_11_25, false);
	}

	@Test
	public void closeFallbackPreservesAnAlreadyReservedSessionRetirementCause() throws Exception {
		McpSimulationRuntime capture = new McpSimulationRuntime(
				McpSimulationOptions.defaultInstance(), () -> {});
		capture.openChannel((reason, exactReason, cause) -> {});
		capture.acceptResponse(capture.response(List.of()));
		capture.bindController(reason -> {
			// Legacy GET retirement assigns the exact owner cause after clean
			// no-message capture termination, before publishing completion.
			Assertions.assertTrue(capture.completeWithoutMessage());
			capture.reserveRuntimeReason(McpStreamTerminationReason.SESSION_CLOSED);
			return true;
		});
		capture.close();
		capture.didFinishRequest(McpRequestOutcome.COMPLETE, List.of());
		Assertions.assertEquals(McpStreamTerminationReason.SESSION_CLOSED,
				capture.awaitCompletion(Duration.ZERO).orElseThrow().getReason());
		Assertions.assertTrue(capture.awaitStreamItem(Duration.ZERO).isEmpty());
	}

	private static void assertControllerOrdering(McpProtocolVersion version, boolean terminalFirst) {
		CountDownLatch releaseHandler = new CountDownLatch(1);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("ordering.probe", Set.of(version))
				.jsonObjectArguments().handler((request, arguments, features) -> {
					features.require(McpProgressReporter.class).report(
							McpProgressUpdate.withProgress(1.0d).build());
					try {
						releaseHandler.await();
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
					}
					return McpCompleteResult.fromToolText("fixture terminal message");
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("ordering-probe", "4.0.0").build(),
				Set.of(version))
				.toolRegistrations(List.of(tool)).build();
		SimulatorConfig configuration = SimulatorConfig.builder()
				.configureMcpServer(builder -> builder.host("127.0.0.1").port(0)
						.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
						.admissionController(McpAdmissionController.acceptAllInstance())
						.requestTimeout(Duration.ofSeconds(30))
						.writeTimeout(Duration.ofHours(2)).keepAliveInterval(Duration.ofHours(1)))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).build();
		SokletSimulator.run(configuration, simulator -> {
			CountDownLatch closeEntered = new CountDownLatch(1);
			CountDownLatch allowClose = new CountDownLatch(1);
			CountDownLatch terminalCaptured = new CountDownLatch(1);
			CountDownLatch allowTerminalCallback = new CountDownLatch(1);
			AtomicReference<Throwable> closeFailure = new AtomicReference<>();
			Thread closeThread = null;
			try {
				boolean modern = version == McpProtocolVersion.V2026_07_28;
				String metadata = modern
						? "\"io.modelcontextprotocol/protocolVersion\":\"" + version.getWireValue()
								+ "\",\"io.modelcontextprotocol/clientCapabilities\":{},"
						: "";
				String body = "{\"jsonrpc\":\"2.0\",\"id\":\"fixture-race\",\"method\":\"tools/call\",\"params\":{"
						+ "\"name\":\"ordering.probe\",\"arguments\":{},\"_meta\":{" + metadata
						+ "\"progressToken\":\"fixture-progress\"}}}";
				Map<String, List<String>> headers = new LinkedHashMap<>(Map.of(
						"Host", List.of("127.0.0.1:0"), "Content-Type", List.of("application/json"),
						"Accept", List.of("application/json, text/event-stream"),
						"MCP-Protocol-Version", List.of(version.getWireValue())));
				if (modern) {
					headers.put("Mcp-Method", List.of("tools/call"));
					headers.put("Mcp-Name", List.of("ordering.probe"));
				}
				Request request = Request.withPath(HttpMethod.POST, "/mcp")
						.headers(headers)
						.body(body.getBytes(StandardCharsets.UTF_8)).build();
				McpSimulation simulation = simulator.startMcpRequest(request);
				var response = simulation.awaitResponse(Duration.ofSeconds(10)).orElseThrow();
				Assertions.assertEquals(200, response.getStatusCode());
				Assertions.assertEquals(McpSimulationBodyType.SSE, response.getBodyType());
				McpSimulationStreamItem progress = simulation.awaitStreamItem(Duration.ofSeconds(10)).orElseThrow();
				Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE, progress.getType());
				Assertions.assertTrue(new String(progress.getEncodedBytes(), StandardCharsets.UTF_8)
						.contains("notifications/progress"));

				// Pause only callback delivery. The actual production controller and
				// terminal listener still perform all transitions once released.
				Field controllerField = simulationField(simulation, "controller");
				McpSimulationRuntime.Controller controller =
						(McpSimulationRuntime.Controller) controllerField.get(simulation);
				controllerField.set(simulation, (McpSimulationRuntime.Controller) reason -> {
					closeEntered.countDown();
					await(allowClose);
					return controller.cancel(reason);
				});
				Field listenerField = simulationField(simulation, "streamListener");
				McpRequestSseStream.Listener listener = (McpRequestSseStream.Listener) listenerField.get(simulation);
				listenerField.set(simulation, (McpRequestSseStream.Listener) (reason, exactReason, cause) -> {
					if (reason == StreamTerminationReason.COMPLETED) {
						terminalCaptured.countDown();
						await(allowTerminalCallback);
					}
					listener.didTerminate(reason, exactReason, cause);
				});
				closeThread = new Thread(() -> {
					try {
						simulation.close();
					} catch (Throwable failure) {
						closeFailure.set(failure);
					}
				}, "simulation-controller-ordering-close");
				closeThread.start();
				await(closeEntered);
				if (terminalFirst) {
					releaseHandler.countDown();
					await(terminalCaptured);
					Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
							simulationField(simulation, "pendingReason").get(simulation));
				}
				allowClose.countDown();
				closeThread.join(10_000);
				Assertions.assertFalse(closeThread.isAlive());
				Assertions.assertNull(closeFailure.get());
				releaseHandler.countDown();
				allowTerminalCallback.countDown();
				McpSimulationCompletion completion = simulation.awaitCompletion(Duration.ofSeconds(10)).orElseThrow();
				Assertions.assertEquals(terminalFirst ? McpStreamTerminationReason.COMPLETED
						: McpStreamTerminationReason.CLIENT_DISCONNECTED, completion.getReason());
				Assertions.assertEquals(terminalFirst, completion.getTerminalMessage().isPresent());
				if (terminalFirst) {
					McpSimulationStreamItem terminal = simulation.awaitStreamItem(Duration.ZERO).orElseThrow();
					Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE, terminal.getType());
					Assertions.assertTrue(new String(terminal.getEncodedBytes(), StandardCharsets.UTF_8)
							.contains("fixture terminal message"));
				}
				Assertions.assertTrue(simulation.awaitStreamItem(Duration.ZERO).isEmpty());
				simulation.close();
				Assertions.assertSame(completion, simulation.awaitCompletion(Duration.ZERO).orElseThrow());
			} catch (Exception failure) {
				throw new AssertionError(failure);
			} finally {
				releaseHandler.countDown();
				allowClose.countDown();
				allowTerminalCallback.countDown();
				if (closeThread != null) {
					try {
						closeThread.join(10_000);
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
					}
				}
			}
		});
	}

	private static Field simulationField(McpSimulation simulation, String name) throws Exception {
		Field field = simulation.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static void await(CountDownLatch latch) {
		try {
			Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}
}
