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

import com.soklet.CorsAuthorizer;
import com.soklet.HttpMethod;
import com.soklet.McpEndpoint;
import com.soklet.McpImplementation;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationOptions;
import com.soklet.McpStreamTerminationReason;
import com.soklet.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@NotThreadSafe
@Timeout(60)
public class McpSimulationLifecyclePhaseTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";

	@Test
	public void bridge_quiesce_is_idempotent_fences_starts_and_releases_proof()
			throws Exception {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"simulation-phase-test", "4.0.0").build())
				.build();
		McpServerRuntimeBridge bridge = new McpServerRuntimeBridge(
				LOOPBACK, 0, endpoint, Set.of(LOOPBACK), false,
				CorsAuthorizer.rejectAllInstance(), true,
				ignored -> com.soklet.McpAdmissionDecision.accepted(),
				ignored -> {});

		try (McpServerRuntimeBridge.SimulationSession session =
					bridge.openSimulationSession()) {
			Assertions.assertTrue(session.lifecycleEvidence().executorTask());
			session.quiesce();
			session.quiesce();
			Assertions.assertThrows(IllegalStateException.class,
					() -> session.start(discoveryRequest(),
							McpSimulationOptions.defaultInstance()));
			Assertions.assertTrue(session.awaitTermination(
					System.nanoTime() + Duration.ofSeconds(5).toNanos(),
					System::nanoTime));
			assertEmpty(session.lifecycleEvidence());
			session.releaseLifecycleEvidence();
			session.releaseLifecycleEvidence();
			session.force();
			assertEmpty(session.lifecycleEvidence());
		}
	}

	@Test
	@Timeout(120)
	public void graceful_simulation_drain_does_not_interrupt_admitted_handler()
			throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger interrupts = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(invocation -> {
			entered.countDown();
			try {
				release.await();
			} catch (InterruptedException exception) {
				interrupts.incrementAndGet();
				Thread.currentThread().interrupt();
			}
			return McpWireResult.complete(McpJsonObject.empty());
		});

		try (runtime;
				McpHttpServerRuntime.SimulationSession session =
						runtime.openSimulationSession();
				McpSimulation simulation = session.start(toolRequest(),
						McpSimulationOptions.defaultInstance())) {
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
			session.quiesce();
			Assertions.assertEquals(0, interrupts.get());
			McpLifecycleEvidence drainingEvidence = session.lifecycleEvidence();
			Assertions.assertTrue(drainingEvidence.executorTask(),
					drainingEvidence.toString());
			Assertions.assertTrue(drainingEvidence.callback(),
					drainingEvidence.toString());
			Assertions.assertThrows(IllegalStateException.class,
					session::releaseLifecycleEvidence);
			release.countDown();
			Assertions.assertTrue(session.awaitTermination(
					System.nanoTime() + Duration.ofSeconds(5).toNanos(),
					System::nanoTime));
			Assertions.assertEquals(200, simulation.awaitResponse(
					Duration.ofSeconds(5)).orElseThrow().getStatusCode());
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					simulation.awaitCompletion(Duration.ofSeconds(5))
							.orElseThrow().getReason());
			Assertions.assertEquals(0, interrupts.get());
			assertEmpty(session.lifecycleEvidence());
			session.releaseLifecycleEvidence();
		} finally {
			release.countDown();
		}
	}

	@Test
	@Timeout(120)
	public void force_interrupts_admitted_handler_and_reaches_complete_barrier()
			throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		McpHttpServerRuntime runtime = runtime(invocation -> {
			entered.countDown();
			try {
				new CountDownLatch(1).await();
			} catch (InterruptedException exception) {
				interrupted.countDown();
				Thread.currentThread().interrupt();
			}
			return McpWireResult.complete(McpJsonObject.empty());
		});

		try (runtime;
				McpHttpServerRuntime.SimulationSession session =
						runtime.openSimulationSession();
				McpSimulation ignored = session.start(toolRequest(),
						McpSimulationOptions.defaultInstance())) {
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
			session.force();
			session.force();
			Assertions.assertTrue(interrupted.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(session.awaitTermination(
					System.nanoTime() + Duration.ofSeconds(5).toNanos(),
					System::nanoTime));
			assertEmpty(session.lifecycleEvidence());
			session.releaseLifecycleEvidence();
		}
	}

	private static McpHttpServerRuntime runtime(
			McpApplicationRequestHandler handler) {
		McpNormalizedToolDescriptor descriptor = new McpNormalizedToolDescriptor(
				"phase", objectSchema(), Optional.empty(), McpJsonObject.empty(),
				McpJsonObject.empty());
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"simulation-phase-test", "4.0.0"))
				.tool(McpNormalizedOperation.tool(descriptor,
						McpMirroredHeaderPlan.empty()))
				.build();
		McpApplicationRequestRouter router =
				McpApplicationRequestRouter.fromToolRoutes(Map.of("phase",
						new McpApplicationToolRoute(handler,
								ignored -> McpRateLimitDecision.allowed())));
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				McpHttpEndpointPolicy.forDiscovery(
						CorsAuthorizer.rejectAllInstance(), ignored ->
								McpAdmissionDecision.acceptedAnonymous()),
				endpoint, router,
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM);
	}

	private static McpJsonObject objectSchema() {
		return new McpJsonObject(Map.of("type", new McpJsonString("object")));
	}

	private static Request discoveryRequest() {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"discover\","
				+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		return request(body, "server/discover", null);
	}

	private static Request toolRequest() {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"phase\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"phase\",\"arguments\":{}}}";
		return request(body, "tools/call", "phase");
	}

	private static Request request(String body, String method, String name) {
		Map<String, Set<String>> headers = new java.util.LinkedHashMap<>();
		headers.put("Host", Set.of(LOOPBACK + ":0"));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept", Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));
		if (name != null)
			headers.put("Mcp-Name", Set.of(name));
		return Request.withPath(HttpMethod.POST, "/mcp")
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static void assertEmpty(
			McpServerRuntimeBridge.LifecycleEvidence evidence) {
		Assertions.assertFalse(evidence.eventLoop());
		Assertions.assertFalse(evidence.connection());
		Assertions.assertFalse(evidence.executorTask());
		Assertions.assertFalse(evidence.stream());
		Assertions.assertFalse(evidence.callback());
		Assertions.assertFalse(evidence.subscriptionRegistration());
	}

	private static void assertEmpty(McpLifecycleEvidence evidence) {
		Assertions.assertTrue(evidence.empty(), evidence.toString());
	}
}
