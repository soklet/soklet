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
import com.soklet.McpRequestContext;
import com.soklet.McpSimulationOptions;
import com.soklet.McpStreamTerminationReason;
import com.soklet.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Context creation must not admit policy work after this request's terminal winner.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
class McpContextCreationTerminalFenceTests {
	@Test
	void protocolDeadlineDuringOmittedContextCreationPreventsNotificationLimiterEntry() throws Exception {
		verifyTerminalFence(false);
	}

	@Test
	void clientDisconnectDuringOmittedContextCreationPreventsNotificationLimiterEntry() throws Exception {
		verifyTerminalFence(true);
	}

	private static void verifyTerminalFence(boolean clientDisconnect) throws Exception {
		CountDownLatch contextEntered = new CountDownLatch(1);
		CountDownLatch releaseContext = new CountDownLatch(1);
		AtomicInteger limiterEntries = new AtomicInteger();
		McpRuntimeObservationSink sink = new McpRuntimeObservationSink() {
			@Override public McpRuntimeRequestObservation didStartRequest(McpRuntimeRequestInput input) {
				throw new AssertionError("The observation budget must be exhausted before this request.");
			}

			@Override public Optional<McpRequestContext> contextForOmittedRequest(McpRuntimeRequestInput input) {
				contextEntered.countDown();
				boolean interrupted = false;
				while (true) {
					try { releaseContext.await(); break; }
					catch (InterruptedException failure) { interrupted = true; }
				}
				if (interrupted) Thread.currentThread().interrupt();
				return Optional.empty();
			}
		};
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("context-terminal-fence", "1")).build();
		McpHttpEndpointPolicy policy = new McpHttpEndpointPolicy("/mcp", Set.of(),
				McpAbsentOriginPolicy.ALLOW, CorsAuthorizer.rejectAllInstance(),
				ignored -> McpRequestAdmissionDecision.ACCEPT,
				Optional.of(ignored -> { limiterEntries.incrementAndGet(); return McpRateLimitDecision.allowed(); }),
				McpApplicationRequestInterceptor.passThroughInstance());
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy, endpoint,
				McpApplicationRequestRouter.fromToolRoutes(Map.of()), sink);
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0), List.of(binding),
				McpJsonLimits.productionDefaults(),
				new McpApplicationExecutionConfiguration(1, 1,
						clientDisconnect ? Duration.ofSeconds(30) : Duration.ofSeconds(1), Duration.ofMillis(10)),
				McpApplicationClock.SYSTEM, McpApplicationHandlerExecutorFactory.production(), ignored -> {}, ignored -> {});
		List<McpLegacyHttpObservationDispatcher.Reservation> reservations = new ArrayList<>();
		try (runtime; var session = runtime.openSimulationSession()) {
			Object generation = ((AtomicReference<?>) field(session, "generation")).get();
			McpApplicationExecution application = (McpApplicationExecution) field(generation, "application");
			ThreadPoolExecutor protocolExecutor = (ThreadPoolExecutor) field(generation, "processor");
			try {
				for (int index = 0; index < 128; index++) {
					var reservation = application.reserveSemanticRequestObservation(false);
					if (reservation == null) break;
					reservations.add(reservation);
				}
				assertTrue(!reservations.isEmpty() && reservations.size() < 128,
						"The explicit small fixture must exhaust a bounded observation budget.");
				try (var simulation = session.start(notification(), McpSimulationOptions.defaultInstance())) {
					assertTrue(contextEntered.await(5, TimeUnit.SECONDS));
					assertEquals(0, limiterEntries.get());
					if (clientDisconnect) simulation.close();
					var completion = simulation.awaitCompletion(Duration.ofSeconds(5)).orElseThrow();
					if (clientDisconnect) {
						assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED, completion.getReason());
						assertTrue(simulation.awaitResponse(Duration.ZERO).isEmpty());
					} else {
						assertEquals(504, simulation.awaitResponse(Duration.ZERO).orElseThrow().getStatusCode());
					}
					releaseContext.countDown();
					awaitCondition(() -> protocolExecutor.getActiveCount() == 0,
							"The protocol worker must physically leave context creation before this assertion.");
					assertEquals(0, limiterEntries.get(),
							"A terminal request must not enter a notification rate limiter after context creation.");
				}
			} finally {
				releaseContext.countDown();
				for (var reservation : reservations) reservation.finish(() -> {}, () -> {});
				awaitCondition(() -> !application.legacyHttpObservationOutstanding(),
						"All fixture observation reservations must finish before closing the runtime.");
			}
		} finally {
			releaseContext.countDown();
		}
	}

	private static Request notification() {
		String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1,"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		return Request.withPath(HttpMethod.POST, "/mcp").headers(Map.of(
				"Host", List.of("127.0.0.1:0"), "Content-Type", List.of("application/json"),
				"Accept", List.of("application/json, text/event-stream"),
				"MCP-Protocol-Version", List.of("2026-07-28"), "Mcp-Method", List.of("notifications/cancelled")))
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
		assertTrue(condition.getAsBoolean(), message);
	}
}
