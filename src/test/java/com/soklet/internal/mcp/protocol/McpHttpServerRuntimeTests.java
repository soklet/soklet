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
import com.soklet.Cors;
import com.soklet.CorsPreflight;
import com.soklet.CorsPreflightResponse;
import com.soklet.CorsResponse;
import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.ResourceMethod;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.Options;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

@NotThreadSafe
public class McpHttpServerRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String DISCOVER_METHOD = "server/discover";

	@Test
	@Timeout(120)
	public void construction_does_not_bind_and_failed_start_is_restartable() throws Exception {
		try (ServerSocket occupied = new ServerSocket()) {
			occupied.setReuseAddress(false);
			occupied.bind(new InetSocketAddress(LOOPBACK, 0));
			int port = occupied.getLocalPort();
			McpHttpServerRuntime runtime = runtime(configuration(port), defaultPolicy());

			Assertions.assertFalse(runtime.isStarted());
			Assertions.assertTrue(runtime.boundAddress().isEmpty());
			Assertions.assertThrows(IOException.class, runtime::start);
			Assertions.assertFalse(runtime.isStarted());
			Assertions.assertTrue(runtime.boundAddress().isEmpty());
			runtime.stop();
			Assertions.assertFalse(runtime.lifecycleSnapshot().stopRequired());
			Assertions.assertTrue(runtime.boundAddress().isEmpty(),
					"A generation that never bound retains no address evidence.");

			occupied.close();
			try {
				InetSocketAddress address = runtime.start();
				Assertions.assertEquals(port, address.getPort());
				Assertions.assertEquals(200, discover(port, "\"after-bind-failure\"").status());
			} finally {
				runtime.close();
			}
		}
	}

	@Test
	public void discovery_works_as_the_first_request_and_preserves_id_types() throws Exception {
		McpHttpServerRuntime runtime = runtime(configuration(0), defaultPolicy());

		try {
			InetSocketAddress address = runtime.start();
			Assertions.assertTrue(address.getPort() > 0);
			Assertions.assertEquals(address, runtime.boundAddress().orElseThrow());

			RawResponse stringResponse = discover(address.getPort(), "\"discover-string\"");
			RawResponse integerResponse = discover(address.getPort(), "42");

			assertDiscoveryResponse(stringResponse, "\"discover-string\"");
			assertDiscoveryResponse(integerResponse, "42");
		} finally {
			runtime.close();
		}
	}

	@Test
	public void multi_endpoint_construction_rejects_empty_and_duplicate_paths() {
		McpHttpTransportConfiguration configuration = configuration(0);
		IllegalArgumentException empty = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> new McpHttpServerRuntime(configuration, List.of()));
		Assertions.assertEquals(
				"At least one MCP HTTP endpoint binding must be configured.",
				empty.getMessage());

		McpHttpEndpointBinding first = endpointBinding(
				"/same", "first", ignored -> completeResult("first"));
		McpHttpEndpointBinding second = endpointBinding(
				"/same", "second", ignored -> completeResult("second"));
		IllegalArgumentException duplicate = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> new McpHttpServerRuntime(configuration,
						List.of(first, second)));
		Assertions.assertEquals("Duplicate MCP HTTP endpoint path '/same'.",
				duplicate.getMessage());
	}

	@Test
	public void exact_path_routing_selects_each_endpoint_policy_catalog_and_router()
			throws Exception {
		AtomicInteger alphaAdmissions = new AtomicInteger();
		AtomicInteger betaAdmissions = new AtomicInteger();
		AtomicInteger alphaInterceptions = new AtomicInteger();
		AtomicInteger betaInterceptions = new AtomicInteger();
		McpHttpEndpointBinding alpha = endpointBinding("/alpha", "alpha",
				alphaAdmissions, alphaInterceptions,
				ignored -> completeResult("alpha"));
		McpHttpEndpointBinding beta = endpointBinding("/beta", "beta",
				betaAdmissions, betaInterceptions,
				ignored -> completeResult("beta"));
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				configuration(0), List.of(alpha, beta));

		try {
			int port = runtime.start().getPort();
			RawResponse alphaDiscovery = request(port, "/alpha", "alpha-discovery",
					DISCOVER_METHOD);
			RawResponse betaDiscovery = request(port, "/beta", "beta-discovery",
					DISCOVER_METHOD);
			Assertions.assertEquals(200, alphaDiscovery.status(),
					alphaDiscovery.bodyText());
			Assertions.assertTrue(alphaDiscovery.bodyText().contains(
					"\"name\":\"alpha\""), alphaDiscovery.bodyText());
			Assertions.assertEquals(200, betaDiscovery.status(),
					betaDiscovery.bodyText());
			Assertions.assertTrue(betaDiscovery.bodyText().contains(
					"\"name\":\"beta\""), betaDiscovery.bodyText());

			RawResponse alphaResult = request(port, "/alpha", "alpha-call",
					"example/echo");
			RawResponse betaResult = request(port, "/beta", "beta-call",
					"example/echo");
			Assertions.assertEquals(200, alphaResult.status(), alphaResult.bodyText());
			Assertions.assertTrue(alphaResult.bodyText().contains(
					"\"value\":\"alpha\""), alphaResult.bodyText());
			Assertions.assertEquals(200, betaResult.status(), betaResult.bodyText());
			Assertions.assertTrue(betaResult.bodyText().contains(
					"\"value\":\"beta\""), betaResult.bodyText());
			Assertions.assertEquals(2, alphaAdmissions.get());
			Assertions.assertEquals(2, betaAdmissions.get());
			Assertions.assertEquals(1, alphaInterceptions.get());
			Assertions.assertEquals(1, betaInterceptions.get());

			RawResponse missing = send(port, "POST", "/missing",
					replaceHeader(standardHeaders(port, DISCOVER_METHOD),
							"Host", "untrusted.example:" + port),
					discoverBody("\"missing\"", DISCOVER_METHOD, PROTOCOL_VERSION));
			Assertions.assertEquals(404, missing.status(), missing.bodyText());
			Assertions.assertEquals(0, missing.body().length);
			Assertions.assertEquals(2, alphaAdmissions.get(),
					"Unknown paths must not reach endpoint policy.");
			Assertions.assertEquals(2, betaAdmissions.get(),
					"Unknown paths must not reach endpoint policy.");
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	public void handler_capacity_is_server_wide_across_endpoint_paths() throws Exception {
		CountDownLatch alphaEntered = new CountDownLatch(1);
		CountDownLatch releaseAlpha = new CountDownLatch(1);
		McpHttpEndpointBinding alpha = endpointBinding("/alpha", "alpha", invocation -> {
			alphaEntered.countDown();
			Assertions.assertTrue(releaseAlpha.await(10,
					TimeUnit.SECONDS),
					"Timed out waiting to release the alpha endpoint");
			return completeResult("alpha");
		});
		McpHttpEndpointBinding beta = endpointBinding(
				"/beta", "beta", ignored -> completeResult("beta"));
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				configuration(0), List.of(alpha, beta),
				McpJsonLimits.productionDefaults(),
				new McpApplicationExecutionConfiguration(
						1, 1, Duration.ofSeconds(15), Duration.ofMillis(10)),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {});
		ExecutorService clients = Executors.newFixedThreadPool(2);

		try {
			int port = runtime.start().getPort();
			Future<RawResponse> active = clients.submit(
					() -> request(port, "/alpha", "active", "example/echo"));
			Assertions.assertTrue(alphaEntered.await(5, TimeUnit.SECONDS),
					"The alpha handler did not enter.");
			Future<RawResponse> queued = clients.submit(
					() -> request(port, "/beta", "queued", "example/echo"));
			awaitApplicationSnapshot(runtime, snapshot ->
					snapshot.activeHandlerSlots() == 1
							&& snapshot.queuedRequests() == 1);

			RawResponse rejected = request(port, "/beta", "rejected", "example/echo");
			assertCapacityResponse(rejected, "rejected");

			releaseAlpha.countDown();
			Assertions.assertTrue(active.get(5, TimeUnit.SECONDS).bodyText()
					.contains("\"value\":\"alpha\""));
			Assertions.assertTrue(queued.get(5, TimeUnit.SECONDS).bodyText()
					.contains("\"value\":\"beta\""));
			McpApplicationExecutionSnapshot snapshot = awaitApplicationSnapshot(runtime,
					value -> value.activeHandlerSlots() == 0
							&& value.queuedRequests() == 0
							&& value.activeIdentifiedRequestExchanges() == 0);
			Assertions.assertEquals(1, snapshot.maximumObservedActiveHandlerSlots());
			Assertions.assertEquals(1, snapshot.maximumObservedQueuedRequests());
			Assertions.assertEquals(1, snapshot.capacityRejections());
		} finally {
			releaseAlpha.countDown();
			runtime.close();
			clients.shutdownNow();
		}
	}

	@Test
	@Timeout(120)
	public void concurrent_same_string_id_completes_independently_across_anonymous_endpoint_paths()
			throws Exception {
		CountDownLatch alphaEntered = new CountDownLatch(1);
		CountDownLatch betaEntered = new CountDownLatch(1);
		CountDownLatch releaseAlpha = new CountDownLatch(1);
		CountDownLatch releaseBeta = new CountDownLatch(1);
		McpHttpEndpointBinding alpha = endpointBinding("/alpha", "alpha", invocation -> {
			alphaEntered.countDown();
			Assertions.assertTrue(releaseAlpha.await(10,
					TimeUnit.SECONDS),
					"Timed out waiting to release the alpha endpoint");
			return completeResult("alpha");
		});
		McpHttpEndpointBinding beta = endpointBinding("/beta", "beta", ignored -> {
			betaEntered.countDown();
			Assertions.assertTrue(releaseBeta.await(10,
					TimeUnit.SECONDS),
					"Timed out waiting to release the beta endpoint");
			return completeResult("beta");
		});
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				configuration(0), List.of(alpha, beta),
				McpJsonLimits.productionDefaults(),
				new McpApplicationExecutionConfiguration(
						2, 2, Duration.ofSeconds(15), Duration.ofMillis(10)),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {});
		ExecutorService clients = Executors.newFixedThreadPool(2);

		try {
			int port = runtime.start().getPort();
			Future<RawResponse> alphaResponse = clients.submit(
					() -> request(port, "/alpha", "shared-id", "example/echo"));
			Future<RawResponse> betaResponse = clients.submit(
					() -> request(port, "/beta", "shared-id", "example/echo"));
			Assertions.assertTrue(alphaEntered.await(5, TimeUnit.SECONDS),
					"The alpha handler did not enter.");
			Assertions.assertTrue(betaEntered.await(5, TimeUnit.SECONDS),
					"The beta handler did not enter.");
			awaitApplicationSnapshot(runtime,
					snapshot -> snapshot.activeIdentifiedRequestExchanges() == 2);

			releaseBeta.countDown();
			RawResponse completedBeta = betaResponse.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(200, completedBeta.status(),
					completedBeta.bodyText());
			Assertions.assertTrue(completedBeta.bodyText().contains(
					"\"id\":\"shared-id\""), completedBeta.bodyText());
			Assertions.assertTrue(completedBeta.bodyText().contains(
					"\"value\":\"beta\""), completedBeta.bodyText());
			awaitApplicationSnapshot(runtime,
					snapshot -> snapshot.activeIdentifiedRequestExchanges() == 1);

			releaseAlpha.countDown();
			RawResponse completedAlpha = alphaResponse.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(200, completedAlpha.status(),
					completedAlpha.bodyText());
			Assertions.assertTrue(completedAlpha.bodyText().contains(
					"\"id\":\"shared-id\""), completedAlpha.bodyText());
			Assertions.assertTrue(completedAlpha.bodyText().contains(
					"\"value\":\"alpha\""), completedAlpha.bodyText());
			awaitApplicationSnapshot(runtime, snapshot ->
					snapshot.activeIdentifiedRequestExchanges() == 0);
		} finally {
			releaseAlpha.countDown();
			releaseBeta.countDown();
			runtime.close();
			clients.shutdownNow();
		}
	}

	@Test
	public void mcp_and_ordinary_http_listeners_are_independent() throws Exception {
		EventLoop ordinary = new EventLoop(Options.builder()
				.withHost(LOOPBACK)
				.withPort(0)
				.withConcurrency(1)
				.build(), (request, callback) -> callback.accept(new MicrohttpResponse(
				200, "OK", List.of(), "ordinary".getBytes(StandardCharsets.UTF_8))));
		McpHttpServerRuntime mcp = runtime(configuration(0), defaultPolicy());

		try {
			ordinary.start();
			int ordinaryPort = ordinary.getPort();
			int mcpPort = mcp.start().getPort();
			Assertions.assertNotEquals(ordinaryPort, mcpPort);
			Assertions.assertEquals("ordinary", send(ordinaryPort, "GET", "/",
					List.of(new HeaderLine("Host", LOOPBACK + ":" + ordinaryPort)),
					new byte[0]).bodyText());
			Assertions.assertEquals(200, discover(mcpPort, "1").status());

			ordinary.stop();
			ordinary.join();
			Assertions.assertEquals(200, discover(mcpPort, "2").status());
		} finally {
			ordinary.stop();
			ordinary.join();
			mcp.close();
		}
	}

	@Test
	@Timeout(120)
	public void alternating_mcp_instances_keep_discovery_state_independent()
			throws Exception {
		McpHttpServerRuntime first = runtime(configuration(0), defaultPolicy(), "first");
		McpHttpServerRuntime second = runtime(configuration(0), defaultPolicy(), "second");

		try {
			int firstPort = first.start().getPort();
			int secondPort = second.start().getPort();
			for (int index = 0; index < 10; index++) {
				RawResponse firstResponse = discover(firstPort, Integer.toString(index * 2));
				RawResponse secondResponse = discover(secondPort,
						Integer.toString(index * 2 + 1));
				Assertions.assertTrue(firstResponse.bodyText().contains("\"name\":\"first\""),
						firstResponse.bodyText());
				Assertions.assertTrue(secondResponse.bodyText().contains("\"name\":\"second\""),
						secondResponse.bodyText());
			}
		} finally {
			first.close();
			second.close();
		}
	}

	@Test
	@Timeout(120)
	public void lifecycle_is_idempotent_and_restartable_with_a_fresh_listener() throws Exception {
		McpHttpServerRuntime runtime = runtime(configuration(0), defaultPolicy());
		try {
			runtime.stop();
			InetSocketAddress firstAddress = runtime.start();
			int firstPort = firstAddress.getPort();
			Assertions.assertThrows(IllegalStateException.class, runtime::start);
			Assertions.assertEquals(200, discover(firstPort, "1").status());
			runtime.stop();
			runtime.stop();
			Assertions.assertFalse(runtime.isStarted());
			Assertions.assertEquals(firstAddress,
					runtime.boundAddress().orElseThrow());

			int secondPort = runtime.start().getPort();
			Assertions.assertEquals(200, discover(secondPort, "2").status());
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	public void disabledLifecycleUnexpectedEventLoopRetainsFailureUntilLegacyStopCleanup()
			throws Exception {
		AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();
		CountDownLatch failureObserved = new CountDownLatch(1);
		McpNormalizedEndpoint endpoint = endpoint("direct-unexpected");
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				configuration(0), defaultPolicy(), endpoint,
				McpJsonLimits.productionDefaults(), McpApplicationRequestRouter.empty(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(), ignored -> {}, failure -> {
					unexpectedFailure.set(failure);
					failureObserved.countDown();
				});

		try {
			InetSocketAddress address = runtime.start();
			terminateUnexpectedly(eventLoop(runtime));
			Assertions.assertTrue(failureObserved.await(5, TimeUnit.SECONDS));
			Assertions.assertInstanceOf(ClosedSelectorException.class,
					unexpectedFailure.get());
			McpHttpServerLifecycleSnapshot failed = runtime.lifecycleSnapshot();
			Assertions.assertFalse(failed.started());
			Assertions.assertTrue(failed.stopRequired());
			Assertions.assertEquals(address, failed.boundAddress().orElseThrow());

			runtime.stop();

			McpHttpServerLifecycleSnapshot stopped = runtime.lifecycleSnapshot();
			Assertions.assertFalse(stopped.started());
			Assertions.assertFalse(stopped.stopRequired());
			Assertions.assertEquals(address, stopped.boundAddress().orElseThrow());
			Assertions.assertTrue(runtime.lifecycleEvidence().empty());
			assertListenerReturned(address);

			InetSocketAddress restarted = runtime.start();
			Assertions.assertEquals(200,
					discover(restarted.getPort(), "\"restart\"").status());
			runtime.stop();
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(180)
	public void omitted_cors_authorizer_emits_fixed_diagnostic_once_per_successful_generation()
			throws Exception {
		List<String> diagnostics = new ArrayList<>();
		McpHttpEndpointPolicy policy =
				McpHttpEndpointPolicy.forDiscoveryWithDefaultCorsAuthorizer(
						request -> McpRequestAdmissionDecision.ACCEPT);

		try (ServerSocket occupied = new ServerSocket()) {
			occupied.setReuseAddress(false);
			occupied.bind(new InetSocketAddress(LOOPBACK, 0));
			int port = occupied.getLocalPort();
			McpHttpServerRuntime runtime = runtime(
					configuration(port), policy, diagnostics::add);

			try {
				Assertions.assertTrue(diagnostics.isEmpty());
				Assertions.assertThrows(IOException.class, runtime::start);
				Assertions.assertTrue(diagnostics.isEmpty(),
						"A failed listener generation must not emit a startup diagnostic.");
				runtime.stop();
				Assertions.assertFalse(runtime.lifecycleSnapshot().stopRequired());
				Assertions.assertTrue(runtime.boundAddress().isEmpty(),
						"A generation that never bound retains no address evidence.");

				occupied.close();
				Assertions.assertEquals(port, runtime.start().getPort());
				assertOmittedCorsDiagnostics(diagnostics, 1);

				RawResponse rejectedOrigin = send(port, "POST", "/mcp",
						append(standardHeaders(port, DISCOVER_METHOD),
								new HeaderLine("Origin", "https://attacker-one.example")),
						discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION));
				Assertions.assertEquals(403, rejectedOrigin.status());
				RawResponse rejectedHost = send(port, "POST", "/mcp",
						replaceHeader(append(standardHeaders(port, DISCOVER_METHOD),
								new HeaderLine("Origin", "https://attacker-two.example")),
								"Host", "attacker-two.example:" + port),
						discoverBody("2", DISCOVER_METHOD, PROTOCOL_VERSION));
				Assertions.assertEquals(421, rejectedHost.status());
				assertOmittedCorsDiagnostics(diagnostics, 1);

				Assertions.assertThrows(IllegalStateException.class, runtime::start);
				assertOmittedCorsDiagnostics(diagnostics, 1);
				runtime.stop();
				runtime.stop();
				assertOmittedCorsDiagnostics(diagnostics, 1);

				Assertions.assertEquals(port, runtime.start().getPort());
				assertOmittedCorsDiagnostics(diagnostics, 2);
			} finally {
				runtime.close();
			}
		}
	}

	@Test
	@Timeout(240)
	public void explicit_cors_authorizer_suppresses_omitted_authorizer_diagnostic()
			throws Exception {
		for (CorsAuthorizer authorizer : List.of(
				CorsAuthorizer.rejectAllInstance(), CorsAuthorizer.acceptAllInstance())) {
			List<String> diagnostics = new ArrayList<>();
			McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
					authorizer, request -> McpRequestAdmissionDecision.ACCEPT);
			McpHttpServerRuntime runtime = runtime(
					configuration(0), policy, diagnostics::add);

			try {
				runtime.start();
				runtime.stop();
				runtime.start();
				Assertions.assertTrue(diagnostics.isEmpty(),
						"An explicitly configured authorizer must suppress the omitted-only "
								+ "diagnostic.");
			} finally {
				runtime.close();
			}
		}
	}

	@Test
	public void omitted_cors_authorizer_uses_the_default_diagnostic_delivery()
			throws Exception {
		McpHttpEndpointPolicy policy =
				McpHttpEndpointPolicy.forDiscoveryWithDefaultCorsAuthorizer(
						request -> McpRequestAdmissionDecision.ACCEPT);
		McpHttpServerRuntime runtime = runtime(configuration(0), policy);
		ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
		PrintStream originalStandardError = System.err;

		try (PrintStream capturedStandardError = new PrintStream(
				diagnosticBytes, true, StandardCharsets.UTF_8)) {
			System.setErr(capturedStandardError);
			try {
				runtime.start();
			} finally {
				try {
					runtime.close();
				} finally {
					System.setErr(originalStandardError);
				}
			}
		}

		Assertions.assertEquals(
				McpHttpServerRuntime.OMITTED_CORS_AUTHORIZER_DIAGNOSTIC
						+ System.lineSeparator(),
				diagnosticBytes.toString(StandardCharsets.UTF_8));
	}

	@Test
	@Timeout(120)
	public void diagnostic_sink_failure_does_not_fail_listener_start() throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		McpHttpEndpointPolicy policy =
				McpHttpEndpointPolicy.forDiscoveryWithDefaultCorsAuthorizer(
						request -> McpRequestAdmissionDecision.ACCEPT);
		McpHttpServerRuntime runtime = runtime(configuration(0), policy, message -> {
			attempts.incrementAndGet();
			throw new AssertionError("diagnostic sink failure");
		});

		try {
			int port = runtime.start().getPort();
			Assertions.assertEquals(1, attempts.get());
			Assertions.assertEquals(200, discover(port, "1").status());
			runtime.stop();
			runtime.start();
			Assertions.assertEquals(2, attempts.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	public void submit_after_stop_boundary_returns_unavailable_and_releases_lifecycle_admission()
			throws Exception {
		McpHttpServerRuntime runtime = runtime(configuration(0), defaultPolicy());
		AtomicInteger callbacks = new AtomicInteger();
		AtomicInteger releases = new AtomicInteger();
		AtomicReference<MicrohttpResponse> terminalResponse = new AtomicReference<>();

		try {
			InetSocketAddress address = runtime.start();
			Field processorField = McpHttpServerRuntime.class.getDeclaredField(
					"requestProcessor");
			processorField.setAccessible(true);
			ThreadPoolExecutor stoppedProcessor = (ThreadPoolExecutor) processorField.get(runtime);
			Field applicationField = McpHttpServerRuntime.class.getDeclaredField(
					"applicationExecution");
			applicationField.setAccessible(true);
			McpApplicationExecution stoppedApplication =
					(McpApplicationExecution) applicationField.get(runtime);

			runtime.stop();
			Assertions.assertTrue(stoppedProcessor.isTerminated());
			Assertions.assertTrue(stoppedApplication.isTerminated());

			Method submitRequest = McpHttpServerRuntime.class.getDeclaredMethod(
					"submitRequest", ThreadPoolExecutor.class, McpApplicationExecution.class,
					InetSocketAddress.class, MicrohttpRequest.class, Request.class,
					McpSimulationRuntime.class, Runnable.class,
					java.util.function.Consumer.class);
			submitRequest.setAccessible(true);
			MicrohttpRequest lateRequest = new MicrohttpRequest(
					"POST", "/mcp", "HTTP/1.1", List.of(), new byte[0], false,
					new InetSocketAddress(LOOPBACK, 12_345));
			submitRequest.invoke(runtime, stoppedProcessor, stoppedApplication, address,
					lateRequest, null, null, (Runnable) releases::incrementAndGet,
					(java.util.function.Consumer<MicrohttpResponse>) response -> {
						callbacks.incrementAndGet();
						Assertions.assertEquals(503, response.status());
						terminalResponse.set(response);
					});

			Assertions.assertEquals(1, callbacks.get(),
					"A submit that loses the stop boundary must terminate promptly.");
			Assertions.assertEquals(0, releases.get(),
					"The full-body lifecycle lease must span terminal response delivery.");
			completeBody(terminalResponse.get());
			Assertions.assertEquals(1, releases.get(),
					"Terminal body delivery must release the lifecycle lease exactly once.");
			McpRequestExecutionSnapshot snapshot = runtime.requestExecutionSnapshot();
			Assertions.assertEquals(0, snapshot.retainedRequestControls());
			Assertions.assertEquals(0, snapshot.queuedProtocolRequests());
			Assertions.assertEquals(0,
					snapshot.activeIdentifiedRequestExchanges());

			int restartedPort = runtime.start().getPort();
			Assertions.assertEquals(200, discover(restartedPort, "\"after-late-submit\"").status());
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	public void residual_admission_work_blocks_restart_until_it_really_exits()
			throws Exception {
		CountDownLatch admissionStarted = new CountDownLatch(1);
		AtomicBoolean releaseAdmission = new AtomicBoolean();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(), request -> {
					admissionStarted.countDown();
					while (!releaseAdmission.get()) {
						try {
							Thread.sleep(10L);
						} catch (InterruptedException ignored) {
							// Deliberately model uncooperative application code.
						}
					}
					return McpRequestAdmissionDecision.ACCEPT;
				});
		McpHttpServerRuntime runtime = runtime(
				configurationWithShutdownTimeout(0, Duration.ofMillis(50)), policy);
		Thread client = null;

		try {
			int port = runtime.start().getPort();
			client = new Thread(() -> {
				try {
					discover(port, "1");
				} catch (Throwable ignored) {
					// Shutdown is expected to close this client-visible exchange.
				}
			}, "mcp-residual-admission-client");
			client.start();
			Assertions.assertTrue(admissionStarted.await(2, TimeUnit.SECONDS));

			AtomicReference<Throwable> firstStopFailure = new AtomicReference<>();
			AtomicReference<Throwable> secondStopFailure = new AtomicReference<>();
			CountDownLatch stopReady = new CountDownLatch(2);
			CountDownLatch stopTogether = new CountDownLatch(1);
			Thread firstStop = stoppingThread(runtime, stopReady, stopTogether,
					firstStopFailure, "mcp-first-stop");
			Thread secondStop = stoppingThread(runtime, stopReady, stopTogether,
					secondStopFailure, "mcp-second-stop");
			firstStop.start();
			secondStop.start();
			Assertions.assertTrue(stopReady.await(2, TimeUnit.SECONDS));
			stopTogether.countDown();
			firstStop.join(2_000L);
			secondStop.join(2_000L);
			Assertions.assertFalse(firstStop.isAlive());
			Assertions.assertFalse(secondStop.isAlive());
			Assertions.assertNull(firstStopFailure.get());
			Assertions.assertNull(secondStopFailure.get());
			IllegalStateException exception = Assertions.assertThrows(
					IllegalStateException.class, runtime::start);
			Assertions.assertEquals(
					"Cannot start MCP server while residual handler executions remain",
					exception.getMessage());

			releaseAdmission.set(true);
			boolean restarted = false;
			for (int attempt = 0; attempt < 200 && !restarted; attempt++) {
				try {
					runtime.start();
					restarted = true;
				} catch (IllegalStateException residual) {
					Thread.sleep(10L);
				}
			}
			Assertions.assertTrue(restarted, "Residual admission work did not drain.");
		} finally {
			releaseAdmission.set(true);
			runtime.close();
			if (client != null)
				client.join(2_000L);
		}
	}

	@Test
	@Timeout(120)
	public void residual_transport_is_a_stop_failure_and_blocks_restart_until_exit()
			throws Exception {
		McpHttpServerRuntime runtime = runtime(
				configurationWithShutdownTimeout(0, Duration.ofMillis(25)),
				defaultPolicy());
		HeldTerminationEventLoop heldEventLoop = new HeldTerminationEventLoop();

		try {
			InetSocketAddress retainedAddress = runtime.start();
			replaceEventLoop(runtime, heldEventLoop);

			long stopStartedAt = System.nanoTime();
			IllegalStateException stopFailure = Assertions.assertThrows(
					IllegalStateException.class, runtime::stop);
			Duration stopDuration = Duration.ofNanos(
					System.nanoTime() - stopStartedAt);
			Assertions.assertEquals(
					McpHttpServerRuntime.RESIDUAL_TRANSPORT_DIAGNOSTIC,
					stopFailure.getMessage());
			Assertions.assertTrue(stopDuration.compareTo(Duration.ofSeconds(1)) < 0,
					"Transport stop failure exceeded its bounded deadline: "
							+ stopDuration);

			McpHttpServerLifecycleSnapshot stopped = runtime.lifecycleSnapshot();
			Assertions.assertFalse(stopped.started());
			Assertions.assertTrue(stopped.stopRequired());
			Assertions.assertEquals(retainedAddress,
					stopped.boundAddress().orElseThrow());
			Assertions.assertFalse(stopped.residualApplicationExecutions());
			IllegalStateException restartFailure = Assertions.assertThrows(
					IllegalStateException.class, runtime::start);
			Assertions.assertEquals(
					"Cannot start MCP server while residual transport threads remain",
					restartFailure.getMessage());

			heldEventLoop.releaseTermination();
			int restartedPort = runtime.start().getPort();
			Assertions.assertEquals(200,
					discover(restartedPort, "\"after-residual-transport\"").status());
		} finally {
			heldEventLoop.releaseTermination();
			runtime.close();
		}
	}

	@Test
	public void routing_host_method_and_content_negotiation_fail_before_protocol_dispatch()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicInteger corsOriginChecks = new AtomicInteger();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.fromWhitelistAuthorizer(origin -> {
					corsOriginChecks.incrementAndGet();
					return true;
				}), request -> {
					admissions.incrementAndGet();
					return McpRequestAdmissionDecision.ACCEPT;
				});
		McpHttpServerRuntime runtime = runtime(configuration(0), policy);

		try {
			int port = runtime.start().getPort();
			byte[] malformedJson = "not-json".getBytes(StandardCharsets.UTF_8);
			Assertions.assertEquals(404, send(port, "POST", "/not-mcp",
					standardHeaders(port, DISCOVER_METHOD), malformedJson).status());
			Assertions.assertEquals(421, send(port, "POST", "/mcp",
					replaceHeader(standardHeaders(port, DISCOVER_METHOD), "Host",
							"evil.example:" + port), malformedJson).status());
			Assertions.assertEquals(405, send(port, "GET", "/mcp",
					List.of(new HeaderLine("Host", LOOPBACK + ":" + port)),
					new byte[0]).status());
			Assertions.assertEquals(421, send(port, "BREW", "/mcp",
					replaceHeader(standardHeaders(port, DISCOVER_METHOD), "Host",
							"evil.example:" + port), new byte[0]).status());
			Assertions.assertEquals(403, send(port, "BREW", "/mcp",
					append(standardHeaders(port, DISCOVER_METHOD),
							new HeaderLine("Origin", "null")), new byte[0]).status());
			Assertions.assertEquals(403, send(port, "BREW", "/mcp",
					append(standardHeaders(port, DISCOVER_METHOD),
							new HeaderLine("Origin", "https://evil.example")),
					new byte[0]).status());
			Assertions.assertEquals(405, send(port, "post", "/mcp",
					standardHeaders(port, DISCOVER_METHOD), new byte[0]).status());
			Assertions.assertEquals(405, send(port, "OPTIONS", "/mcp",
					List.of(new HeaderLine("Host", LOOPBACK + ":" + port)),
					new byte[0]).status());

			List<HeaderLine> wrongContentType = replaceHeader(
					standardHeaders(port, DISCOVER_METHOD), "Content-Type", "text/plain");
			Assertions.assertEquals(415, send(port, "POST", "/mcp",
					wrongContentType, discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION)).status());

			List<HeaderLine> jsonOnlyAccept = replaceHeader(
					standardHeaders(port, DISCOVER_METHOD), "Accept", JSON_MEDIA_TYPE);
			Assertions.assertEquals(406, send(port, "POST", "/mcp", jsonOnlyAccept,
					discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION)).status());

			List<HeaderLine> explicitJsonRejection = replaceHeader(
					standardHeaders(port, DISCOVER_METHOD), "Accept",
					"*/*;q=1, application/json;q=0");
			Assertions.assertEquals(406, send(port, "POST", "/mcp",
					explicitJsonRejection,
					discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION)).status());
			Assertions.assertEquals(0, admissions.get());
			Assertions.assertEquals(0, corsOriginChecks.get(),
					"Unknown wire methods must not be presented to CORS as fabricated POSTs.");
		} finally {
			runtime.close();
		}
	}

	@Test
	public void mcp_listener_accepts_only_http_1_1() throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpHttpServerRuntime runtime = runtime(configuration(0),
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
						ignored -> {
							admissions.incrementAndGet();
							return McpAdmissionDecision.acceptedAnonymous();
						}));

		try {
			int port = runtime.start().getPort();
			RawResponse response = sendVersion(port, "POST", "/mcp", "HTTP/1.0",
					standardHeaders(port, DISCOVER_METHOD),
					discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION));
			Assertions.assertEquals(505, response.status());
			Assertions.assertEquals(0, response.body().length);
			Assertions.assertEquals("no-store", response.singleHeader("Cache-Control"));
			Assertions.assertEquals(0, admissions.get());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void accept_negotiation_is_strict_and_uses_most_specific_quality()
			throws Exception {
		McpHttpServerRuntime runtime = runtime(configuration(0), defaultPolicy());

		try {
			int port = runtime.start().getPort();
			byte[] body = discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION);
			for (String accepted : List.of(
					"application/json, text/event-stream",
					"*/*",
					"application/*, text/*",
					"application/json;q=1, text/event-stream;q=0.5")) {
				RawResponse response = send(port, "POST", "/mcp",
						replaceHeader(standardHeaders(port, DISCOVER_METHOD),
								"Accept", accepted), body);
				Assertions.assertEquals(200, response.status(), accepted);
			}

			for (String rejected : List.of(
					"application/json",
					"text/event-stream",
					"*/*;q=1, application/json;q=0",
					"*/*;q=2",
					"*/*;q=0.1234",
					"application/json;q=1, text/event-stream;q=.5",
					"*/*;q=1;q=0",
					"*/*;q",
					"*/*;q=\"1\"",
					"application/json;charset=utf-8, text/event-stream")) {
				RawResponse response = send(port, "POST", "/mcp",
						replaceHeader(standardHeaders(port, DISCOVER_METHOD),
								"Accept", rejected), body);
				Assertions.assertEquals(406, response.status(), rejected);
			}
		} finally {
			runtime.close();
		}
	}

	@Test
	public void parser_owned_errors_also_disable_caching() throws Exception {
		McpHttpServerRuntime runtime = runtime(configuration(0), defaultPolicy());

		try {
			int port = runtime.start().getPort();
			RawResponse missingHost = send(port, "POST", "/mcp",
					removeHeader(standardHeaders(port, DISCOVER_METHOD), "Host"),
					new byte[0]);
			Assertions.assertEquals(400, missingHost.status());
			Assertions.assertEquals("no-store", missingHost.singleHeader("Cache-Control"));

			RawResponse duplicateHost = send(port, "POST", "/mcp",
					append(standardHeaders(port, DISCOVER_METHOD),
							new HeaderLine("Host", LOOPBACK + ":" + port)), new byte[0]);
			Assertions.assertEquals(400, duplicateHost.status());
			Assertions.assertEquals("no-store", duplicateHost.singleHeader("Cache-Control"));
		} finally {
			runtime.close();
		}
	}

	@Test
	public void ipv6_loopback_bind_authorizes_its_effective_authority()
			throws Exception {
		Assumptions.assumeTrue(ipv6LoopbackAvailable(), "IPv6 loopback is unavailable.");
		McpHttpServerRuntime runtime = runtime(configurationWithHost(0, "::1"),
				defaultPolicy());

		try {
			int port = runtime.start().getPort();
			for (String authority : List.of(
					"[::1]:" + port, "[0:0:0:0:0:0:0:1]:" + port)) {
				RawResponse response = send("::1", port, "POST", "/mcp",
						replaceHeader(standardHeaders(port, DISCOVER_METHOD),
								"Host", authority),
						discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION));
				Assertions.assertEquals(200, response.status(), authority);
			}
		} finally {
			runtime.close();
		}
	}

	@Test
	public void invalid_configured_hosts_fail_before_the_listener_is_created() {
		for (String invalidHost : List.of(
				"example.com:443", "https://example.com", " example.com", "exa_mple")) {
			McpHttpEndpointPolicy policy = new McpHttpEndpointPolicy("/mcp",
					Set.of(invalidHost), McpAbsentOriginPolicy.ALLOW,
					CorsAuthorizer.rejectAllInstance(),
					request -> McpRequestAdmissionDecision.ACCEPT);
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> runtime(configuration(0), policy), invalidHost);
		}
	}

	@Test
	public void transport_durations_must_fit_monotonic_nanosecond_accounting() {
		McpHttpTransportConfiguration defaults = configuration(0);
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpHttpTransportConfiguration(
						defaults.host(), defaults.port(), defaults.selectorResolution(),
						defaults.requestHeaderTimeout(), defaults.requestBodyTimeout(),
						defaults.responseWriteIdleTimeout(),
						defaults.keepAliveInterval(), Duration.ofSeconds(Long.MAX_VALUE),
						defaults.readBufferSize(),
						defaults.acceptBacklog(), defaults.maximumAggregateRequestBytes(),
						defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
						defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
						defaults.maximumConnections(),
						defaults.connectionWriterConcurrency(),
						defaults.requestProcessorConcurrency(),
						defaults.requestProcessorQueueCapacity(),
						defaults.streamQueueCapacity()));
	}

	@Test
	public void streaming_configuration_requires_finite_capacity_and_early_keep_alive() {
		McpHttpTransportConfiguration defaults = configuration(0);
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> configurationWithStreaming(
						defaults.responseWriteIdleTimeout(),
						defaults.responseWriteIdleTimeout(),
						defaults.streamQueueCapacity()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> configurationWithStreaming(
						defaults.keepAliveInterval(),
						defaults.responseWriteIdleTimeout(), 0));
	}

	@Test
	@Timeout(120)
	public void cors_rejects_present_origins_by_default_and_reuses_shared_authorizer()
			throws Exception {
		McpHttpServerRuntime rejecting = runtime(configuration(0), defaultPolicy());
		try {
			int port = rejecting.start().getPort();
			List<HeaderLine> headers = append(standardHeaders(port, DISCOVER_METHOD),
					new HeaderLine("Origin", "https://allowed.example"));
			Assertions.assertEquals(403, send(port, "POST", "/mcp", headers,
					discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION)).status());
		} finally {
			rejecting.close();
		}

		AtomicInteger admissions = new AtomicInteger();
		McpHttpEndpointPolicy allowingPolicy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.fromWhitelistedOrigins(Set.of("https://allowed.example")),
				request -> {
					admissions.incrementAndGet();
					return McpRequestAdmissionDecision.ACCEPT;
				});
		McpHttpServerRuntime allowing = runtime(configuration(0), allowingPolicy);
		try {
			int port = allowing.start().getPort();
			List<HeaderLine> headers = append(standardHeaders(port, DISCOVER_METHOD),
					new HeaderLine("Origin", "https://allowed.example"));
			RawResponse accepted = send(port, "POST", "/mcp", headers,
					discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION));
			Assertions.assertEquals(200, accepted.status());
			Assertions.assertEquals("https://allowed.example",
					accepted.singleHeader("Access-Control-Allow-Origin"));
			Assertions.assertEquals(1, admissions.get());

			RawResponse preflight = send(port, "OPTIONS", "/mcp", List.of(
					new HeaderLine("Host", LOOPBACK + ":" + port),
					new HeaderLine("Origin", "https://allowed.example"),
					new HeaderLine("Access-Control-Request-Method", "POST"),
					new HeaderLine("Access-Control-Request-Headers",
							"Content-Type, MCP-Protocol-Version, Mcp-Method")), new byte[0]);
			Assertions.assertEquals(204, preflight.status());
			Assertions.assertEquals("POST, OPTIONS",
					preflight.singleHeader("Access-Control-Allow-Methods"));
			Assertions.assertEquals("no-store", preflight.singleHeader("Cache-Control"));
			Assertions.assertEquals(1, admissions.get());

			RawResponse invalidPreflight = send(port, "OPTIONS", "/mcp", List.of(
					new HeaderLine("Host", LOOPBACK + ":" + port),
					new HeaderLine("Origin", "https://allowed.example"),
					new HeaderLine("Access-Control-Request-Method", "POST"),
					new HeaderLine("Access-Control-Request-Headers", "X-Not-Mcp")),
					new byte[0]);
			Assertions.assertEquals(403, invalidPreflight.status());
			Assertions.assertEquals(1, admissions.get());
		} finally {
			allowing.close();
		}
	}

	@Test
	public void cors_preflight_allows_only_registered_custom_mirrored_headers()
			throws Exception {
		McpMirroredHeaderPlan mirroredHeaders = new McpMirroredHeaderPlan(List.of(
				new McpMirroredHeaderDeclaration("Tenant", List.of("tenant"),
						McpMirroredHeaderValueType.STRING)));
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"cors-header-test", "4.0.0-SNAPSHOT"))
				.tool(new McpNormalizedOperation("lookup", McpInputRequestPlan.empty(),
						mirroredHeaders))
				.build();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.fromWhitelistedOrigins(Set.of("https://allowed.example")),
				ignored -> McpAdmissionDecision.acceptedAnonymous());
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				configuration(0), policy, endpoint);

		try {
			int port = runtime.start().getPort();
			RawResponse accepted = send(port, "OPTIONS", "/mcp", List.of(
					new HeaderLine("Host", LOOPBACK + ":" + port),
					new HeaderLine("Origin", "https://allowed.example"),
					new HeaderLine("Access-Control-Request-Method", "POST"),
					new HeaderLine("Access-Control-Request-Headers",
							"Content-Type, MCP-Protocol-Version, Mcp-Method, "
									+ "Mcp-Name, mcp-param-tenant")), new byte[0]);
			Assertions.assertEquals(204, accepted.status());

			RawResponse unregistered = send(port, "OPTIONS", "/mcp", List.of(
					new HeaderLine("Host", LOOPBACK + ":" + port),
					new HeaderLine("Origin", "https://allowed.example"),
					new HeaderLine("Access-Control-Request-Method", "POST"),
					new HeaderLine("Access-Control-Request-Headers",
							"Mcp-Param-Unregistered")), new byte[0]);
			Assertions.assertEquals(403, unregistered.status());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void cors_preflight_honors_authorizer_narrowed_methods_and_headers()
			throws Exception {
		CorsAuthorizer authorizer = preflightAuthorizer(
				Set.of(HttpMethod.POST), Set.of("Content-Type"));
		McpHttpServerRuntime runtime = runtime(configuration(0),
				McpHttpEndpointPolicy.forDiscovery(authorizer,
						ignored -> McpRequestAdmissionDecision.ACCEPT));

		try {
			int port = runtime.start().getPort();
			RawResponse response = send(port, "OPTIONS", "/mcp", List.of(
					new HeaderLine("Host", LOOPBACK + ":" + port),
					new HeaderLine("Origin", "https://allowed.example"),
					new HeaderLine("Access-Control-Request-Method", "POST"),
					new HeaderLine("Access-Control-Request-Headers",
							"Content-Type, Authorization, MCP-Protocol-Version")),
					new byte[0]);

			Assertions.assertEquals(204, response.status());
			Assertions.assertEquals("POST",
					response.singleHeader("Access-Control-Allow-Methods"));
			Assertions.assertEquals("Content-Type",
					response.singleHeader("Access-Control-Allow-Headers"));
			Assertions.assertFalse(response.singleHeader("Access-Control-Allow-Headers")
					.contains("Authorization"));
		} finally {
			runtime.close();
		}
	}

	@Test
	@Timeout(120)
	public void cors_preflight_fails_closed_for_authorizer_values_outside_mcp_surface()
			throws Exception {
		for (CorsAuthorizer authorizer : List.of(
				preflightAuthorizer(Set.of(HttpMethod.DELETE), Set.of()),
				preflightAuthorizer(Set.of(HttpMethod.POST), Set.of("X-Not-Mcp")))) {
			McpHttpServerRuntime runtime = runtime(configuration(0),
					McpHttpEndpointPolicy.forDiscovery(authorizer,
							ignored -> McpRequestAdmissionDecision.ACCEPT));
			try {
				int port = runtime.start().getPort();
				RawResponse response = send(port, "OPTIONS", "/mcp", List.of(
						new HeaderLine("Host", LOOPBACK + ":" + port),
						new HeaderLine("Origin", "https://allowed.example"),
						new HeaderLine("Access-Control-Request-Method", "POST"),
						new HeaderLine("Access-Control-Request-Headers", "Content-Type")),
						new byte[0]);

				Assertions.assertEquals(500, response.status());
				Assertions.assertEquals(0, response.body().length);
				Assertions.assertFalse(response.headers()
						.containsKey("access-control-allow-origin"));
			} finally {
				runtime.close();
			}
		}
	}

	@Test
	@Timeout(180)
	public void absent_origin_policy_and_cors_hook_failures_fail_closed()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		McpHttpEndpointPolicy requireOrigin = new McpHttpEndpointPolicy("/mcp", Set.of(),
				McpAbsentOriginPolicy.REQUIRE_ORIGIN, CorsAuthorizer.acceptAllInstance(),
				request -> {
					admissions.incrementAndGet();
					return McpRequestAdmissionDecision.ACCEPT;
				});
		McpHttpServerRuntime requiring = runtime(configuration(0), requireOrigin);
		try {
			int port = requiring.start().getPort();
			Assertions.assertEquals(403, discover(port, "1").status());
			Assertions.assertEquals(0, admissions.get());
		} finally {
			requiring.close();
		}

		for (CorsAuthorizer authorizer : List.of(
				faultingCorsAuthorizer(false), faultingCorsAuthorizer(true))) {
			McpHttpServerRuntime runtime = runtime(configuration(0),
					McpHttpEndpointPolicy.forDiscovery(authorizer,
							request -> McpRequestAdmissionDecision.ACCEPT));
			try {
				int port = runtime.start().getPort();
				RawResponse response = send(port, "POST", "/mcp",
						append(standardHeaders(port, DISCOVER_METHOD),
								new HeaderLine("Origin", "https://allowed.example")),
						discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION));
				Assertions.assertEquals(500, response.status());
				Assertions.assertEquals(0, response.body().length);
				Assertions.assertFalse(
						response.headers().containsKey("access-control-allow-origin"));

				RawResponse preflightResponse = send(port, "OPTIONS", "/mcp", List.of(
						new HeaderLine("Host", LOOPBACK + ":" + port),
						new HeaderLine("Origin", "https://allowed.example"),
						new HeaderLine("Access-Control-Request-Method", "POST"),
						new HeaderLine("Access-Control-Request-Headers",
								"Content-Type, MCP-Protocol-Version, Mcp-Method")),
						new byte[0]);
				Assertions.assertEquals(500, preflightResponse.status());
				Assertions.assertEquals(0, preflightResponse.body().length);
				Assertions.assertFalse(preflightResponse.headers()
						.containsKey("access-control-allow-origin"));
			} finally {
				runtime.close();
			}
		}
	}

	@Test
	public void modern_header_metadata_and_method_failures_have_deterministic_errors()
			throws Exception {
		McpHttpServerRuntime runtime = runtime(configuration(0), defaultPolicy());

		try {
			int port = runtime.start().getPort();
			RawResponse missingProtocolVersionHeader = send(port, "POST", "/mcp",
					removeHeader(standardHeaders(port, DISCOVER_METHOD),
							"MCP-Protocol-Version"),
					discoverBody("\"version-header\"", DISCOVER_METHOD,
							PROTOCOL_VERSION));
			assertJsonRpcError(missingProtocolVersionHeader, 400, -32020,
					"\"version-header\"");

			RawResponse missingMethodHeader = send(port, "POST", "/mcp",
					removeHeader(standardHeaders(port, DISCOVER_METHOD), "Mcp-Method"),
					discoverBody("\"header\"", DISCOVER_METHOD, PROTOCOL_VERSION));
			assertJsonRpcError(missingMethodHeader, 400, -32020, "\"header\"");

			RawResponse headerMismatch = send(port, "POST", "/mcp",
					standardHeaders(port, DISCOVER_METHOD),
					discoverBody("1", DISCOVER_METHOD, "2025-11-25"));
			assertJsonRpcError(headerMismatch, 400, -32020, "1");

			List<HeaderLine> unsupportedHeaders = replaceHeader(
					standardHeaders(port, DISCOVER_METHOD), "MCP-Protocol-Version",
					"2025-11-25");
			RawResponse unsupported = send(port, "POST", "/mcp", unsupportedHeaders,
					discoverBody("2", DISCOVER_METHOD, "2025-11-25"));
			assertJsonRpcError(unsupported, 400, -32022, "2");
			Assertions.assertTrue(unsupported.bodyText().contains("\"supported\":[\"2026-07-28\"]"));

			String unknownMethod = "example/unknown";
			RawResponse unknown = send(port, "POST", "/mcp",
					replaceHeader(standardHeaders(port, DISCOVER_METHOD),
							"Mcp-Method", unknownMethod),
					discoverBody("3", unknownMethod, PROTOCOL_VERSION));
			assertJsonRpcError(unknown, 404, -32601, "3");

			RawResponse legacy = send(port, "POST", "/mcp",
					replaceHeader(standardHeaders(port, DISCOVER_METHOD),
							"Mcp-Method", "initialize"),
					discoverBody("4", "initialize", PROTOCOL_VERSION));
			assertJsonRpcError(legacy, 404, -32601, "4");
			Assertions.assertTrue(legacy.bodyText().contains(PROTOCOL_VERSION));
		} finally {
			runtime.close();
		}
	}

	@Test
	public void request_body_limit_has_an_exact_body_only_boundary() throws Exception {
		int maximumBodyBytes = 512;
		McpHttpServerRuntime runtime = runtime(configuration(0, maximumBodyBytes),
				defaultPolicy());

		try {
			int port = runtime.start().getPort();
			byte[] valid = paddedDiscoverBody(maximumBodyBytes);
			Assertions.assertEquals(maximumBodyBytes, valid.length);
			Assertions.assertEquals(200, send(port, "POST", "/mcp",
					standardHeaders(port, DISCOVER_METHOD), valid).status());

			byte[] oneOver = Arrays.copyOf(valid, valid.length + 1);
			oneOver[oneOver.length - 1] = ' ';
			Assertions.assertEquals(413, send(port, "POST", "/mcp",
					standardHeaders(port, DISCOVER_METHOD), oneOver).status());
		} finally {
			runtime.close();
		}
	}

	private static McpHttpServerRuntime runtime(McpHttpTransportConfiguration configuration,
			McpHttpEndpointPolicy policy) {
		return runtime(configuration, policy, "test-server");
	}

	private static McpHttpServerRuntime runtime(McpHttpTransportConfiguration configuration,
			McpHttpEndpointPolicy policy, String serverName) {
		return new McpHttpServerRuntime(configuration, policy, endpoint(serverName));
	}

	private static McpNormalizedEndpoint endpoint(String serverName) {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(serverName, "4.0.0-SNAPSHOT"))
				.build();
	}

	private static McpHttpServerRuntime runtime(McpHttpTransportConfiguration configuration,
			McpHttpEndpointPolicy policy, Consumer<String> startupDiagnosticConsumer) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"test-server", "4.0.0-SNAPSHOT"))
				.build();
		return new McpHttpServerRuntime(
				configuration, policy, endpoint, startupDiagnosticConsumer);
	}

	private static McpHttpEndpointBinding endpointBinding(String path,
			String serverName, McpApplicationRequestHandler handler) {
		return endpointBinding(path, serverName, new AtomicInteger(),
				new AtomicInteger(), handler);
	}

	private static McpHttpEndpointBinding endpointBinding(String path,
			String serverName, AtomicInteger admissions, AtomicInteger interceptions,
			McpApplicationRequestHandler handler) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						serverName, "4.0.0-SNAPSHOT"))
				.build();
		McpHttpEndpointPolicy policy = new McpHttpEndpointPolicy(
				path, Set.of(), McpAbsentOriginPolicy.ALLOW,
				CorsAuthorizer.rejectAllInstance(), context -> {
					Assertions.assertSame(endpoint, context.endpoint());
					admissions.incrementAndGet();
					return McpRequestAdmissionDecision.ACCEPT;
				}).withRequestInterceptor((invocation, handlerInvocation) -> {
					interceptions.incrementAndGet();
					return handlerInvocation.invoke();
				});
		return new McpHttpEndpointBinding(policy, endpoint,
				McpApplicationRequestRouter.fromHandlers(
						Map.of("example/echo", handler)));
	}

	private static McpWireResult completeResult(String value) {
		return McpWireResult.complete(new McpJsonObject(
				Map.of("value", new McpJsonString(value))));
	}

	private static RawResponse request(int port, String path, String id, String method)
			throws Exception {
		return send(port, "POST", path, standardHeaders(port, method),
				discoverBody("\"" + id + "\"", method, PROTOCOL_VERSION));
	}

	private static McpApplicationExecutionSnapshot awaitApplicationSnapshot(
			McpHttpServerRuntime runtime,
			Predicate<McpApplicationExecutionSnapshot> condition) throws Exception {
		McpApplicationExecutionSnapshot latest = null;
		for (int attempt = 0; attempt < 500; attempt++) {
			latest = runtime.applicationExecutionSnapshot().orElseThrow();
			if (condition.test(latest))
				return latest;
			Thread.sleep(10L);
		}
		throw new AssertionError("Application snapshot condition was not met: " + latest);
	}

	private static void assertCapacityResponse(RawResponse response, String id) {
		Assertions.assertEquals(503, response.status(), response.bodyText());
		Assertions.assertTrue(response.bodyText().contains("\"id\":\"" + id + "\""),
				response.bodyText());
		Assertions.assertTrue(response.bodyText().contains("\"code\":-32603"),
				response.bodyText());
		Assertions.assertTrue(response.bodyText().contains(
				"\"message\":\"Internal error\""), response.bodyText());
		Assertions.assertFalse(response.bodyText().contains("\"data\""),
				response.bodyText());
		Assertions.assertFalse(response.headers().containsKey("retry-after"));
	}

	private static void assertOmittedCorsDiagnostics(List<String> diagnostics,
			int expectedCount) {
		Assertions.assertEquals(expectedCount, diagnostics.size());
		Assertions.assertTrue(diagnostics.stream().allMatch(
				McpHttpServerRuntime.OMITTED_CORS_AUTHORIZER_DIAGNOSTIC::equals));
	}

	private static McpHttpEndpointPolicy defaultPolicy() {
		return McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
				request -> McpRequestAdmissionDecision.ACCEPT);
	}

	private static McpHttpTransportConfiguration configuration(int port) {
		return McpHttpTransportConfiguration.productionDefaults(port);
	}

	private static McpHttpTransportConfiguration configuration(int port,
			int maximumBodyBytes) {
		McpHttpTransportConfiguration defaults = configuration(port);
		return new McpHttpTransportConfiguration(
				defaults.host(), defaults.port(), defaults.selectorResolution(),
				defaults.requestHeaderTimeout(), defaults.requestBodyTimeout(),
				defaults.responseWriteIdleTimeout(), defaults.keepAliveInterval(),
				defaults.shutdownTimeout(),
				defaults.readBufferSize(), defaults.acceptBacklog(),
				maximumBodyBytes + defaults.maximumHeaderBytes()
						+ defaults.maximumRequestTargetBytes() + 1_024,
				maximumBodyBytes, defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				defaults.maximumConnections(), defaults.connectionWriterConcurrency(),
				defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity(), defaults.streamQueueCapacity());
	}

	private static McpHttpTransportConfiguration configurationWithHost(int port,
			String host) {
		McpHttpTransportConfiguration defaults = configuration(port);
		return new McpHttpTransportConfiguration(
				host, defaults.port(), defaults.selectorResolution(),
				defaults.requestHeaderTimeout(), defaults.requestBodyTimeout(),
				defaults.responseWriteIdleTimeout(), defaults.keepAliveInterval(),
				defaults.shutdownTimeout(),
				defaults.readBufferSize(), defaults.acceptBacklog(),
				defaults.maximumAggregateRequestBytes(),
				defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				defaults.maximumConnections(), defaults.connectionWriterConcurrency(),
				defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity(), defaults.streamQueueCapacity());
	}

	private static McpHttpTransportConfiguration configurationWithShutdownTimeout(
			int port, Duration shutdownTimeout) {
		McpHttpTransportConfiguration defaults = configuration(port);
		return new McpHttpTransportConfiguration(
				defaults.host(), defaults.port(), defaults.selectorResolution(),
				defaults.requestHeaderTimeout(), defaults.requestBodyTimeout(),
				defaults.responseWriteIdleTimeout(), defaults.keepAliveInterval(),
				shutdownTimeout,
				defaults.readBufferSize(), defaults.acceptBacklog(),
				defaults.maximumAggregateRequestBytes(),
				defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				defaults.maximumConnections(), defaults.connectionWriterConcurrency(),
				defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity(), defaults.streamQueueCapacity());
	}

	private static McpHttpTransportConfiguration configurationWithStreaming(
			Duration keepAliveInterval, Duration responseWriteIdleTimeout,
			int streamQueueCapacity) {
		McpHttpTransportConfiguration defaults = configuration(0);
		return new McpHttpTransportConfiguration(
				defaults.host(), defaults.port(), defaults.selectorResolution(),
				defaults.requestHeaderTimeout(), defaults.requestBodyTimeout(),
				responseWriteIdleTimeout, keepAliveInterval, defaults.shutdownTimeout(),
				defaults.readBufferSize(), defaults.acceptBacklog(),
				defaults.maximumAggregateRequestBytes(),
				defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				defaults.maximumConnections(), defaults.connectionWriterConcurrency(),
				defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity(), streamQueueCapacity);
	}

	private static RawResponse discover(int port, String idJson) throws Exception {
		return send(port, "POST", "/mcp", standardHeaders(port, DISCOVER_METHOD),
				discoverBody(idJson, DISCOVER_METHOD, PROTOCOL_VERSION));
	}

	private static byte[] discoverBody(String idJson, String method, String protocolVersion) {
		return ("{\"jsonrpc\":\"2.0\",\"id\":" + idJson
				+ ",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + protocolVersion + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}")
				.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] paddedDiscoverBody(int size) {
		byte[] body = discoverBody("1", DISCOVER_METHOD, PROTOCOL_VERSION);
		if (body.length > size)
			throw new IllegalArgumentException("Requested test body is too small.");
		byte[] paddedBody = Arrays.copyOf(body, size);
		Arrays.fill(paddedBody, body.length, paddedBody.length, (byte) ' ');
		return paddedBody;
	}

	private static List<HeaderLine> standardHeaders(int port, String method) {
		return List.of(
				new HeaderLine("Host", LOOPBACK + ":" + port),
				new HeaderLine("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8"),
				new HeaderLine("Accept", JSON_MEDIA_TYPE + ", text/event-stream"),
				new HeaderLine("MCP-Protocol-Version", PROTOCOL_VERSION),
				new HeaderLine("Mcp-Method", method));
	}

	private static List<HeaderLine> replaceHeader(List<HeaderLine> headers,
			String name, String value) {
		List<HeaderLine> replaced = new ArrayList<>();
		boolean found = false;
		for (HeaderLine header : headers) {
			if (header.name().equalsIgnoreCase(name)) {
				if (!found)
					replaced.add(new HeaderLine(header.name(), value));
				found = true;
			} else {
				replaced.add(header);
			}
		}
		if (!found)
			replaced.add(new HeaderLine(name, value));
		return List.copyOf(replaced);
	}

	private static List<HeaderLine> removeHeader(List<HeaderLine> headers, String name) {
		return headers.stream().filter(header -> !header.name().equalsIgnoreCase(name)).toList();
	}

	private static List<HeaderLine> append(List<HeaderLine> headers, HeaderLine header) {
		List<HeaderLine> appended = new ArrayList<>(headers);
		appended.add(header);
		return List.copyOf(appended);
	}

	private static RawResponse send(int port, String method, String path,
			List<HeaderLine> headers, byte[] body) throws Exception {
		return send(LOOPBACK, port, method, path, "HTTP/1.1", headers, body);
	}

	private static RawResponse sendVersion(int port, String method, String path,
			String version, List<HeaderLine> headers, byte[] body) throws Exception {
		return send(LOOPBACK, port, method, path, version, headers, body);
	}

	private static RawResponse send(String host, int port, String method, String path,
			List<HeaderLine> headers, byte[] body) throws Exception {
		return send(host, port, method, path, "HTTP/1.1", headers, body);
	}

	private static RawResponse send(String host, int port, String method, String path,
			String version, List<HeaderLine> headers, byte[] body) throws Exception {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), 3_000);
			socket.setSoTimeout(5_000);
			StringBuilder requestHead = new StringBuilder()
					.append(method).append(' ').append(path).append(' ')
					.append(version).append("\r\n");
			for (HeaderLine header : headers)
				requestHead.append(header.name()).append(": ")
						.append(header.value()).append("\r\n");
			requestHead.append("Content-Length: ").append(body.length).append("\r\n")
					.append("Connection: close\r\n\r\n");
			socket.getOutputStream().write(
					requestHead.toString().getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().write(body);
			socket.getOutputStream().flush();

			ByteArrayOutputStream response = new ByteArrayOutputStream();
			InputStream input = socket.getInputStream();
			byte[] buffer = new byte[4_096];
			int read;
			while ((read = input.read(buffer)) >= 0)
				response.write(buffer, 0, read);

			return RawResponse.parse(response.toByteArray());
		}
	}

	private static boolean ipv6LoopbackAvailable() {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("::1", 0));
			return true;
		} catch (IOException exception) {
			return false;
		}
	}

	private static CorsAuthorizer faultingCorsAuthorizer(boolean throwsException) {
		return new CorsAuthorizer() {
			@Override
			public Optional<CorsResponse> authorize(Request request, Cors cors) {
				if (throwsException)
					throw new IllegalStateException("deliberate CORS test failure");
				return null;
			}

			@Override
			public Optional<CorsPreflightResponse> authorizePreflight(Request request,
					CorsPreflight corsPreflight,
					Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod) {
				return Optional.empty();
			}

			@Override
			public Optional<CorsPreflightResponse> authorizePreflight(Request request,
					CorsPreflight corsPreflight, Set<HttpMethod> availableHttpMethods) {
				if (throwsException)
					throw new IllegalStateException("deliberate CORS test failure");
				return null;
			}
		};
	}

	private static CorsAuthorizer preflightAuthorizer(Set<HttpMethod> allowedMethods,
			Set<String> allowedHeaders) {
		return new CorsAuthorizer() {
			@Override
			public Optional<CorsResponse> authorize(Request request, Cors cors) {
				return Optional.empty();
			}

			@Override
			public Optional<CorsPreflightResponse> authorizePreflight(Request request,
					CorsPreflight corsPreflight,
					Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod) {
				return Optional.empty();
			}

			@Override
			public Optional<CorsPreflightResponse> authorizePreflight(Request request,
					CorsPreflight corsPreflight, Set<HttpMethod> availableHttpMethods) {
				return Optional.of(CorsPreflightResponse
						.withAccessControlAllowOrigin(corsPreflight.getOrigin())
						.accessControlAllowMethods(allowedMethods)
						.accessControlAllowHeaders(allowedHeaders)
						.build());
			}
		};
	}

	private static Thread stoppingThread(McpHttpServerRuntime runtime,
			CountDownLatch ready, CountDownLatch start, AtomicReference<Throwable> failure,
			String name) {
		return new Thread(() -> {
			ready.countDown();
			try {
				start.await();
				runtime.stop();
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, name);
	}

	private static void replaceEventLoop(McpHttpServerRuntime runtime,
			EventLoop replacement) throws Exception {
		Field eventLoopField = McpHttpServerRuntime.class.getDeclaredField("eventLoop");
		eventLoopField.setAccessible(true);
		EventLoop original = (EventLoop) eventLoopField.get(runtime);
		original.stop();
		Assertions.assertTrue(original.join(Duration.ofSeconds(2)),
				"The original MCP event loop did not terminate before replacement.");
		eventLoopField.set(runtime, replacement);
	}

	private static EventLoop eventLoop(McpHttpServerRuntime runtime) throws Exception {
		Field eventLoopField = McpHttpServerRuntime.class.getDeclaredField("eventLoop");
		eventLoopField.setAccessible(true);
		return (EventLoop) eventLoopField.get(runtime);
	}

	private static void terminateUnexpectedly(EventLoop eventLoop) throws Exception {
		Field selectorField = EventLoop.class.getDeclaredField("selector");
		selectorField.setAccessible(true);
		((Selector) selectorField.get(eventLoop)).close();
		Assertions.assertTrue(eventLoop.join(Duration.ofSeconds(2)),
				"The disabled-lifecycle MCP event loop did not terminate.");
	}

	private static void assertListenerReturned(InetSocketAddress address)
			throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(true);
			socket.bind(address);
		}
	}

	private static void completeBody(MicrohttpResponse response) throws Exception {
		Method reserveBodyTermination = MicrohttpResponse.class.getDeclaredMethod(
				"reserveBodyTermination", StreamTerminationReason.class, Throwable.class);
		reserveBodyTermination.setAccessible(true);
		reserveBodyTermination.invoke(response, StreamTerminationReason.COMPLETED, null);

		Method deliverBodyTermination = MicrohttpResponse.class.getDeclaredMethod(
				"deliverBodyTermination");
		deliverBodyTermination.setAccessible(true);
		deliverBodyTermination.invoke(response);
	}

	private static void assertDiscoveryResponse(RawResponse response, String idJson) {
		Assertions.assertEquals(200, response.status(), response.bodyText());
		Assertions.assertEquals(JSON_MEDIA_TYPE, response.singleHeader("Content-Type"));
		Assertions.assertEquals("no-store", response.singleHeader("Cache-Control"));
		String body = response.bodyText();
		Assertions.assertTrue(body.contains("\"id\":" + idJson), body);
		Assertions.assertTrue(body.contains("\"resultType\":\"complete\""), body);
		Assertions.assertTrue(body.contains("\"supportedVersions\":[\"2026-07-28\"]"), body);
		Assertions.assertTrue(body.contains("\"capabilities\":{}"), body);
		Assertions.assertTrue(body.contains("\"ttlMs\":0"), body);
		Assertions.assertTrue(body.contains("\"cacheScope\":\"private\""), body);
		Assertions.assertTrue(body.contains("\"io.modelcontextprotocol/serverInfo\""), body);
	}

	private static void assertJsonRpcError(RawResponse response, int status, int code,
			String idJson) {
		Assertions.assertEquals(status, response.status(), response.bodyText());
		Assertions.assertTrue(response.bodyText().contains("\"id\":" + idJson),
				response.bodyText());
		Assertions.assertTrue(response.bodyText().contains("\"code\":" + code),
				response.bodyText());
		Assertions.assertEquals("no-store", response.singleHeader("Cache-Control"));
	}

	private static final String JSON_MEDIA_TYPE = "application/json";

	private static final class HeldTerminationEventLoop extends EventLoop {
		private final CountDownLatch releaseTermination;

		private HeldTerminationEventLoop() throws IOException {
			super(Options.builder()
					.withHost(LOOPBACK)
					.withPort(0)
					.withConcurrency(1)
					.build(), (request, callback) -> {});
			this.releaseTermination = new CountDownLatch(1);
		}

		@Override
		public boolean join(Duration timeout) throws InterruptedException {
			if (!this.releaseTermination.await(
					timeout.toNanos(), TimeUnit.NANOSECONDS))
				return false;
			return super.join(Duration.ZERO);
		}

		@Override
		public boolean isTerminated() {
			return this.releaseTermination.getCount() == 0 && super.isTerminated();
		}

		private void releaseTermination() {
			this.releaseTermination.countDown();
		}
	}

	private record HeaderLine(String name, String value) {
	}

	private record RawResponse(int status, Map<String, List<String>> headers, byte[] body) {
		private static RawResponse parse(byte[] bytes) {
			byte[] delimiter = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
			int boundary = indexOf(bytes, delimiter);
			if (boundary < 0)
				throw new AssertionError("Response did not contain a complete HTTP head.");

			String head = new String(bytes, 0, boundary, StandardCharsets.ISO_8859_1);
			String[] lines = head.split("\r\n");
			String[] statusParts = lines[0].split(" ", 3);
			Map<String, List<String>> headers = new LinkedHashMap<>();
			for (int index = 1; index < lines.length; index++) {
				int colon = lines[index].indexOf(':');
				String name = lines[index].substring(0, colon).toLowerCase(Locale.ROOT);
				String value = lines[index].substring(colon + 1).trim();
				headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
			}
			return new RawResponse(Integer.parseInt(statusParts[1]),
					Map.copyOf(headers), Arrays.copyOfRange(bytes,
							boundary + delimiter.length, bytes.length));
		}

		private String bodyText() {
			return new String(body, StandardCharsets.UTF_8);
		}

		private String singleHeader(String name) {
			List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
			if (values == null || values.size() != 1)
				throw new AssertionError("Expected exactly one " + name + " header, found " + values);
			return values.get(0);
		}

		private static int indexOf(byte[] bytes, byte[] target) {
			outer:
			for (int offset = 0; offset <= bytes.length - target.length; offset++) {
				for (int index = 0; index < target.length; index++) {
					if (bytes[offset + index] != target[index])
						continue outer;
				}
				return offset;
			}
			return -1;
		}
	}
}
