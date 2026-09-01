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

import com.soklet.annotation.GET;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RuntimeState;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Black-box coverage for the public MCP server runtime projection.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpServerPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String DISCOVER_METHOD = "server/discover";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String OMITTED_CORS_AUTHORIZER_DIAGNOSTIC =
			"No CorsAuthorizer is configured for the MCP server; requests carrying an "
					+ "Origin header will be rejected.";

	@Test
	public void executionConfigurationValidatesAndOwnsOneExecutorPerGeneration()
			throws Exception {
		McpServer.Builder validationBuilder = McpServer.withPort(0);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpServer.withPort(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> validationBuilder.port(null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> validationBuilder.requestHandlerConcurrency(0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> validationBuilder.requestHandlerQueueCapacity(-1));
		Assertions.assertThrows(NullPointerException.class,
				() -> validationBuilder.requestHandlerConcurrency(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> validationBuilder.requestHandlerQueueCapacity(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> validationBuilder
						.unknownMirroredHeaderNameDiagnostics(null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> validationBuilder.requestTimeout(Duration.ZERO));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> validationBuilder.requestTimeout(Duration.ofNanos(-1)));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> validationBuilder.requestTimeout(
						Duration.ofSeconds(Long.MAX_VALUE)));
		Assertions.assertThrows(NullPointerException.class,
				() -> validationBuilder.requestTimeout(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> validationBuilder
						.requestHandlerExecutorServiceSupplier(null));

		List<ExecutorService> suppliedExecutors = new ArrayList<>();
		McpServer firstServer = newExecutorConfiguredMcpServer(suppliedExecutors);
		Soklet firstOwner = mcpOnlySoklet(firstServer,
				quietLifecycleObserver());

		Assertions.assertTrue(suppliedExecutors.isEmpty(),
				"Building a server and owner must not allocate the executor.");
		try {
			firstOwner.start();
			Assertions.assertEquals(1, suppliedExecutors.size());
			Assertions.assertFalse(suppliedExecutors.get(0).isShutdown());
		} finally {
			firstOwner.close();
			firstOwner.close();
		}
		Assertions.assertTrue(suppliedExecutors.get(0).isShutdown());

		McpServer secondServer = newExecutorConfiguredMcpServer(suppliedExecutors);
		Soklet secondOwner = mcpOnlySoklet(secondServer,
				quietLifecycleObserver());
		Assertions.assertEquals(1, suppliedExecutors.size(),
				"A fresh server and owner remain lazy until startup.");
		boolean secondExecutorShutdownByOwner = false;
		try {
			secondOwner.start();
			Assertions.assertEquals(2, suppliedExecutors.size());
			Assertions.assertNotSame(suppliedExecutors.get(0),
					suppliedExecutors.get(1));
			Assertions.assertFalse(suppliedExecutors.get(1).isShutdown());
		} finally {
			try {
				secondOwner.close();
				secondOwner.close();
			} finally {
				secondExecutorShutdownByOwner = suppliedExecutors.size() == 2
						&& suppliedExecutors.get(1).isShutdown();
				for (ExecutorService executor : suppliedExecutors)
					executor.shutdownNow();
			}
		}
		Assertions.assertTrue(secondExecutorShutdownByOwner,
				"The fresh owner must terminate its own generation executor.");
		Assertions.assertTrue(suppliedExecutors.stream()
				.allMatch(ExecutorService::isShutdown));

		ExecutorService shutDownExecutor = Executors.newSingleThreadExecutor();
		shutDownExecutor.shutdown();
		McpServer invalidServer = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(newEndpoint())))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.requestHandlerExecutorServiceSupplier(() -> shutDownExecutor)
				.build();
		Soklet invalidOwner = mcpOnlySoklet(invalidServer,
				quietLifecycleObserver());
		try {
			SokletStartupException exception = Assertions.assertThrows(
					SokletStartupException.class, invalidOwner::start);
			IllegalStateException cause = Assertions.assertInstanceOf(
					IllegalStateException.class, exception.getCause());
			Assertions.assertTrue(cause.getMessage().contains("shut-down"),
					cause.getMessage());
			Assertions.assertEquals(McpServerStatus.TERMINATED,
					invalidServer.getDiagnostics().getStatus());
		} finally {
			invalidOwner.close();
			invalidOwner.close();
		}
	}

	@Test
	public void sokletOwnedPortZeroGenerationsPublishImmutableDiagnosticSnapshots()
			throws Exception {
		McpServer neverStartedServer = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet neverStartedOwner = mcpOnlySoklet(neverStartedServer,
				quietLifecycleObserver());
		McpServerDiagnostics neverStartedSnapshot =
				neverStartedServer.getDiagnostics();

		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				neverStartedSnapshot.getStatus());
		Assertions.assertTrue(neverStartedSnapshot.getBoundAddress().isEmpty());
		assertHandlerDiagnostics(neverStartedSnapshot, 32, 128, 0, 0);
		neverStartedOwner.close();
		neverStartedOwner.close();
		Assertions.assertEquals(McpServerStatus.TERMINATED,
				neverStartedServer.getDiagnostics().getStatus());

		McpServer firstServer = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet firstOwner = mcpOnlySoklet(firstServer,
				quietLifecycleObserver());
		McpServerDiagnostics firstInitial = firstServer.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.NOT_STARTED,
				firstInitial.getStatus());
		Assertions.assertTrue(firstInitial.getBoundAddress().isEmpty());
		assertHandlerDiagnostics(firstInitial, 32, 128, 0, 0);
		McpServerDiagnostics firstStarted;
		InetSocketAddress firstAddress;
		try {
			firstOwner.start();
			firstStarted = firstServer.getDiagnostics();
			firstAddress = firstStarted.getBoundAddress().orElseThrow();

			Assertions.assertEquals(SokletStatus.RUNNING, firstOwner.getStatus());
			Assertions.assertEquals(McpServerStatus.RUNNING, firstStarted.getStatus());
			assertHandlerDiagnostics(firstStarted, 32, 128, 0, 0);
			Assertions.assertEquals(LOOPBACK,
					firstAddress.getAddress().getHostAddress());
			Assertions.assertTrue(firstAddress.getPort() > 0);
			Assertions.assertEquals(McpServerStatus.NOT_STARTED,
					firstInitial.getStatus(),
					"A retained pre-start snapshot must not change.");
			Assertions.assertTrue(firstInitial.getBoundAddress().isEmpty());
			assertHandlerDiagnostics(firstInitial, 32, 128, 0, 0);

			Assertions.assertEquals(firstAddress,
					firstServer.getDiagnostics().getBoundAddress().orElseThrow());
			assertSuccessfulDiscovery(sendDiscovery(firstAddress.getPort(),
					"first-generation", "{}"), "first-generation");
		} finally {
			firstOwner.close();
			firstOwner.close();
			firstOwner.close();
			firstOwner.close();
		}
		McpServerDiagnostics firstStopped = firstServer.getDiagnostics();
		Assertions.assertEquals(SokletStatus.CLOSED, firstOwner.getStatus());
		Assertions.assertEquals(McpServerStatus.TERMINATED, firstStopped.getStatus());
		Assertions.assertEquals(firstAddress,
				firstStopped.getBoundAddress().orElseThrow(),
				"A once-bound address remains historical generation evidence.");
		assertHandlerDiagnostics(firstStopped, 32, 128, 0, 0);
		Assertions.assertEquals(McpServerStatus.RUNNING, firstStarted.getStatus(),
				"A retained started snapshot must not change after stop.");
		Assertions.assertEquals(firstAddress,
				firstStarted.getBoundAddress().orElseThrow());
		assertHandlerDiagnostics(firstStarted, 32, 128, 0, 0);

		McpServer secondServer = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet secondOwner = mcpOnlySoklet(secondServer,
				quietLifecycleObserver());
		try {
			secondOwner.start();
			McpServerDiagnostics secondStarted = secondServer.getDiagnostics();
			int secondPort = secondStarted.getBoundAddress().orElseThrow().getPort();
			Assertions.assertEquals(McpServerStatus.RUNNING, secondStarted.getStatus());
			assertHandlerDiagnostics(secondStarted, 32, 128, 0, 0);
			Assertions.assertEquals(McpServerStatus.TERMINATED, firstStopped.getStatus(),
					"A retained stopped snapshot must not change for a fresh generation.");
			Assertions.assertEquals(firstAddress,
					firstStopped.getBoundAddress().orElseThrow());
			assertHandlerDiagnostics(firstStopped, 32, 128, 0, 0);
			assertSuccessfulDiscovery(sendDiscovery(secondPort,
					"second-generation", "{}"), "second-generation");
		} finally {
			secondOwner.close();
			secondOwner.close();
			secondOwner.close();
			secondOwner.close();
		}

		Assertions.assertEquals(SokletStatus.CLOSED, secondOwner.getStatus());
		McpServerDiagnostics finalSnapshot = secondServer.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.TERMINATED,
				finalSnapshot.getStatus());
		assertHandlerDiagnostics(finalSnapshot, 32, 128, 0, 0);
	}

	@Test
	public void diagnosticSnapshotValidatesLifecycleHandlerStreamAndSecurityTuples() {
		InetSocketAddress address = new InetSocketAddress(LOOPBACK, 12_345);
		McpProtectionKeyRingFingerprint protectionFingerprint =
				new McpProtectionKeyRingFingerprint("A".repeat(43));
		McpTraceCorrelationConfigurationFingerprint traceFingerprint =
				McpTraceCorrelationConfigurationFingerprint.fromValue(
						"E".repeat(43));
		McpServerDiagnostics started = diagnosticSnapshot(
				McpServerStatus.RUNNING, Optional.of(address), 2, 3, 1, 2, 4, 3,
				McpProtectionMode.PRODUCTION_KEY_RING, false,
				Optional.of(protectionFingerprint), Optional.of(traceFingerprint));

		Assertions.assertEquals(McpServerStatus.RUNNING, started.getStatus());
		Assertions.assertEquals(address, started.getBoundAddress().orElseThrow());
		assertDiagnostics(started, 2, 3, 1, 2, 4, 3);
		Assertions.assertEquals(McpProtectionMode.PRODUCTION_KEY_RING,
				started.getProtectionMode());
		Assertions.assertEquals(Boolean.FALSE,
				started.isApplicationRequestStateProtectorConfigured());
		Assertions.assertEquals(Optional.of(protectionFingerprint),
				started.getProtectionKeyRingFingerprint());
		Assertions.assertEquals(Optional.of(traceFingerprint),
				started.getTraceCorrelationConfigurationFingerprint());

		McpServerDiagnostics residualCleanup = diagnosticSnapshot(
				McpServerStatus.RESIDUAL_ACTIVITY, Optional.empty(),
				2, 3, 1, 2, 1, 1, McpProtectionMode.CUSTOM_PROTECTOR, true,
				Optional.empty(), Optional.empty());
		Assertions.assertEquals(McpServerStatus.RESIDUAL_ACTIVITY,
				residualCleanup.getStatus());
		assertDiagnostics(residualCleanup, 2, 3, 1, 2, 1, 1);
		Assertions.assertEquals(McpProtectionMode.CUSTOM_PROTECTOR,
				residualCleanup.getProtectionMode());
		Assertions.assertEquals(Boolean.TRUE,
				residualCleanup.isApplicationRequestStateProtectorConfigured());

		McpServerDiagnostics offNetworkRunning = defaultSecurityDiagnosticSnapshot(
				McpServerStatus.RUNNING, Optional.empty(), 2, 3, 0, 0, 0, 0);
		Assertions.assertEquals(McpServerStatus.RUNNING,
				offNetworkRunning.getStatus());
		Assertions.assertTrue(offNetworkRunning.getBoundAddress().isEmpty(),
				"An off-network simulator can be running without a bound address.");
		McpServerDiagnostics retainedStopped = defaultSecurityDiagnosticSnapshot(
				McpServerStatus.TERMINATED, Optional.of(address), 2, 3, 0, 0, 0, 0);
		Assertions.assertEquals(address,
				retainedStopped.getBoundAddress().orElseThrow());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.RUNNING, Optional.of(address), 0, 3, 0, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.RUNNING, Optional.of(address), 2, 0, 0, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.RUNNING, Optional.of(address), 2, 3, -1, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.RUNNING, Optional.of(address), 2, 3, 3, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.RUNNING, Optional.of(address), 2, 3, 0, -1, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.RUNNING, Optional.of(address), 2, 3, 0, 4, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.TERMINATED, Optional.empty(), 2, 3, 1, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.TERMINATED, Optional.empty(), 2, 3, 0, 1, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.RUNNING, Optional.of(address), 2, 3, 0, 0,
						-1, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.RUNNING, Optional.of(address), 2, 3, 0, 0,
						1, -1));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.RUNNING, Optional.of(address), 2, 3, 0, 0,
						1, 2));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.TERMINATED, Optional.empty(), 2, 3, 0, 0,
						1, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> defaultSecurityDiagnosticSnapshot(
						McpServerStatus.TERMINATED, Optional.empty(), 2, 3, 0, 0,
						1, 1));

		Assertions.assertThrows(NullPointerException.class,
				() -> diagnosticSnapshot(McpServerStatus.TERMINATED, Optional.empty(),
						2, 3, 0, 0, 0, 0, null, false,
						Optional.empty(), Optional.empty()));
		Assertions.assertThrows(NullPointerException.class,
				() -> diagnosticSnapshot(McpServerStatus.TERMINATED, Optional.empty(),
						2, 3, 0, 0, 0, 0,
						McpProtectionMode.NO_FRAMEWORK_KEYS, false,
						null, Optional.empty()));
		Assertions.assertThrows(NullPointerException.class,
				() -> diagnosticSnapshot(McpServerStatus.TERMINATED, Optional.empty(),
						2, 3, 0, 0, 0, 0,
						McpProtectionMode.NO_FRAMEWORK_KEYS, false,
						Optional.empty(), null));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> diagnosticSnapshot(McpServerStatus.TERMINATED, Optional.empty(),
						2, 3, 0, 0, 0, 0,
						McpProtectionMode.NO_FRAMEWORK_KEYS, true,
						Optional.empty(), Optional.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> diagnosticSnapshot(McpServerStatus.TERMINATED, Optional.empty(),
						2, 3, 0, 0, 0, 0,
						McpProtectionMode.CUSTOM_PROTECTOR, false,
						Optional.empty(), Optional.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> diagnosticSnapshot(McpServerStatus.TERMINATED, Optional.empty(),
						2, 3, 0, 0, 0, 0,
						McpProtectionMode.CUSTOM_PROTECTOR, true,
						Optional.of(protectionFingerprint), Optional.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> diagnosticSnapshot(McpServerStatus.TERMINATED, Optional.empty(),
						2, 3, 0, 0, 0, 0,
						McpProtectionMode.PRODUCTION_KEY_RING, false,
						Optional.empty(), Optional.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> diagnosticSnapshot(McpServerStatus.TERMINATED, Optional.empty(),
						2, 3, 0, 0, 0, 0,
						McpProtectionMode.PRODUCTION_KEY_RING, true,
						Optional.of(protectionFingerprint), Optional.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> diagnosticSnapshot(McpServerStatus.TERMINATED, Optional.empty(),
						2, 3, 0, 0, 0, 0,
						McpProtectionMode.DEVELOPMENT_EPHEMERAL, false,
						Optional.of(protectionFingerprint), Optional.empty()));
	}

	@Test
	public void runtimeBridgeStateKeepsStartedAndBoundAddressAtomicAcrossLifecycle()
			throws Exception {
		McpServerRuntimeBridge bridge = new McpServerRuntimeBridge(
				LOOPBACK, 0, newEndpoint(), Set.of(LOOPBACK), false,
				CorsAuthorizer.rejectAllInstance(), true,
				ignored -> McpAdmissionDecision.accepted(), ignored -> {});

		try {
			RuntimeState initiallyStopped = bridge.getRuntimeState();
			Assertions.assertFalse(initiallyStopped.started());
			Assertions.assertFalse(initiallyStopped.stopRequired());
			Assertions.assertTrue(initiallyStopped.boundAddress().isEmpty());
			Assertions.assertFalse(initiallyStopped.residualHandlers());

			InetSocketAddress address = bridge.start();
			RuntimeState started = bridge.getRuntimeState();
			Assertions.assertTrue(started.started());
			Assertions.assertTrue(started.stopRequired());
			Assertions.assertEquals(address,
					started.boundAddress().orElseThrow());
			Assertions.assertFalse(started.residualHandlers());

			bridge.stop();
			RuntimeState stoppedAgain = bridge.getRuntimeState();
			Assertions.assertFalse(stoppedAgain.started());
			Assertions.assertFalse(stoppedAgain.stopRequired());
			Assertions.assertEquals(address,
					stoppedAgain.boundAddress().orElseThrow());
			Assertions.assertFalse(stoppedAgain.residualHandlers());
		} finally {
			bridge.stop();
		}
	}

	@Test
	public void sokletStopCleansUnexpectedMcpListenerTermination()
			throws Exception {
		List<LogEvent> events = new ArrayList<>();
		McpServer server = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet owner = mcpOnlySoklet(server, new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				events.add(logEvent);
			}
		});
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			owner.start();
			InetSocketAddress failedAddress = bridge.getRuntimeState()
					.boundAddress().orElseThrow();
			terminateUnexpectedly(eventLoop(bridge));
			assertTransportFailureEvent(events);

			RuntimeState failed = bridge.getRuntimeState();
			Assertions.assertFalse(failed.started());
			Assertions.assertEquals(failedAddress,
					failed.boundAddress().orElseThrow());
			Assertions.assertNotEquals(McpServerStatus.RUNNING,
					server.getDiagnostics().getStatus());

			Assertions.assertThrows(SokletTerminatedUnexpectedlyException.class,
					owner::close);
			RuntimeState stopped = bridge.getRuntimeState();
			Assertions.assertFalse(stopped.started());
			Assertions.assertFalse(stopped.stopRequired());
			Assertions.assertEquals(failedAddress,
					stopped.boundAddress().orElseThrow());
		} finally {
			stopOwnerAllowingUnexpectedTermination(owner);
			stopOwnerAllowingUnexpectedTermination(owner);
		}
	}

	@Test
	public void freshOwnerStartsAfterUnexpectedMcpListenerTermination()
			throws Exception {
		int port = findFreePort();
		McpServer failedServer = newMcpServer(port,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet failedOwner = mcpOnlySoklet(failedServer,
				quietLifecycleObserver());
		McpServerRuntimeBridge failedBridge = runtimeBridge(failedServer);
		EventLoop failedEventLoop;

		try {
			failedOwner.start();
			InetSocketAddress failedAddress = failedBridge.getRuntimeState()
					.boundAddress().orElseThrow();
			Assertions.assertEquals(port, failedAddress.getPort());
			failedEventLoop = eventLoop(failedBridge);
			terminateUnexpectedly(failedEventLoop);

			RuntimeState failed = failedBridge.getRuntimeState();
			Assertions.assertFalse(failed.started());
			Assertions.assertEquals(failedAddress,
					failed.boundAddress().orElseThrow());

			Assertions.assertThrows(SokletTerminatedUnexpectedlyException.class,
					failedOwner::close);
			RuntimeState normalized = failedBridge.getRuntimeState();
			Assertions.assertFalse(normalized.started());
			Assertions.assertFalse(normalized.stopRequired());
			Assertions.assertEquals(failedAddress,
					normalized.boundAddress().orElseThrow());
		} finally {
			stopOwnerAllowingUnexpectedTermination(failedOwner);
		}

		McpServer freshServer = newMcpServer(port,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet freshOwner = mcpOnlySoklet(freshServer,
				quietLifecycleObserver());
		McpServerRuntimeBridge freshBridge = runtimeBridge(freshServer);
		try {
			freshOwner.start();
			RuntimeState fresh = freshBridge.getRuntimeState();
			Assertions.assertTrue(fresh.started());
			Assertions.assertTrue(fresh.stopRequired());
			int freshPort = fresh.boundAddress().orElseThrow().getPort();
			Assertions.assertEquals(port, freshPort,
					"The completed generation must release the exact fixed port.");
			Assertions.assertNotSame(failedEventLoop, eventLoop(freshBridge));
			assertSuccessfulDiscovery(sendDiscovery(freshPort,
					"fresh-after-termination", "{}"),
					"fresh-after-termination");
		} finally {
			freshOwner.close();
			freshOwner.close();
		}
	}

	@Test
	public void discoveryAdvertisesConfiguredServerInformationWithoutOperationCapabilities()
			throws Exception {
		McpImplementation implementation = McpImplementation
				.withNameAndVersion("public-runtime", "4.0.0")
				.title("Public Runtime")
				.description("Operation-free public projection")
				.websiteUrl(URI.create("https://example.test/soklet-mcp"))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(implementation)
				.instructions("Use the public discovery endpoint.")
				.build();
		McpServer server = newMcpServer(0, endpoint,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet owner = mcpOnlySoklet(server, quietLifecycleObserver());

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			HttpResponse<String> response = sendDiscovery(port, "discover-info", "{}");
			String body = response.body();

			assertSuccessfulDiscovery(response, "discover-info");
			Assertions.assertTrue(body.contains("\"capabilities\":{}"), body);
			Assertions.assertFalse(body.contains("\"tools\""), body);
			Assertions.assertFalse(body.contains("\"prompts\""), body);
			Assertions.assertFalse(body.contains("\"resources\""), body);
			Assertions.assertTrue(body.contains(
					"\"io.modelcontextprotocol/serverInfo\""), body);
			Assertions.assertTrue(body.contains("\"name\":\"public-runtime\""), body);
			Assertions.assertTrue(body.contains(
					"\"version\":\"4.0.0\""), body);
			Assertions.assertTrue(body.contains("\"title\":\"Public Runtime\""), body);
			Assertions.assertTrue(body.contains(
					"\"description\":\"Operation-free public projection\""), body);
			Assertions.assertTrue(body.contains(
					"\"websiteUrl\":\"https://example.test/soklet-mcp\""), body);
			Assertions.assertTrue(body.contains(
					"\"instructions\":\"Use the public discovery endpoint.\""), body);
		} finally {
			owner.close();
			owner.close();
		}
	}

	@Test
	public void attachedSubscriptionConfigAdvertisesAndActivatesResourceSubscriptions()
			throws Exception {
		AtomicInteger listenerRegistrations = new AtomicInteger();
		AtomicInteger listenerRegistrationCloses = new AtomicInteger();
		AtomicInteger publishedEvents = new AtomicInteger();
		McpSubscriptionEventPublisher publisher =
				new McpSubscriptionEventPublisher() {
					@Override
					public McpSubscriptionEventRegistration subscribe(
							@NonNull McpSubscriptionEventListener listener) {
						listenerRegistrations.incrementAndGet();
						AtomicInteger closed = new AtomicInteger();
						return () -> {
							if (closed.compareAndSet(0, 1))
								listenerRegistrationCloses.incrementAndGet();
						};
					}

					@Override
					public void publish(@NonNull McpSubscriptionEvent event) {
						publishedEvents.incrementAndGet();
					}
				};
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationType(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)
				.notificationType(
						McpSubscriptionNotificationType.RESOURCE_UPDATED)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(URI.create("test://phase-five-subscriptions"),
						"phase-five-subscriptions")
				.handler((request, read, features) -> {
					throw new AssertionError(
							"Discovery must not invoke the resource handler.");
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("phase-five-subscriptions", "1.0")
						.build())
				.resource(resource)
				.subscriptions(subscriptions)
				.build();
		McpServer server = newMcpServer(0, endpoint,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet owner = mcpOnlySoklet(server, quietLifecycleObserver());

		Assertions.assertEquals(0, listenerRegistrations.get());
		Assertions.assertEquals(0, listenerRegistrationCloses.get());
		Assertions.assertEquals(0, publishedEvents.get());
		try {
			owner.start();
			Assertions.assertEquals(1, listenerRegistrations.get());
			Assertions.assertEquals(0, listenerRegistrationCloses.get());
			int port = server.getDiagnostics().getBoundAddress().orElseThrow()
					.getPort();
			HttpResponse<String> response = sendDiscovery(port,
					"phase-five-subscriptions", "{}");

			assertSuccessfulDiscovery(response, "phase-five-subscriptions");
			Assertions.assertEquals("{\"jsonrpc\":\"2.0\","
					+ "\"id\":\"phase-five-subscriptions\",\"result\":{"
					+ "\"supportedVersions\":[\"2026-07-28\"],"
					+ "\"capabilities\":{\"resources\":{\"listChanged\":true,"
					+ "\"subscribe\":true}},\"ttlMs\":0,\"cacheScope\":\"private\","
					+ "\"resultType\":\"complete\",\"_meta\":{"
					+ "\"io.modelcontextprotocol/serverInfo\":{"
					+ "\"name\":\"phase-five-subscriptions\","
					+ "\"version\":\"1.0\"}}}}", response.body());
			Assertions.assertEquals(0, publishedEvents.get());
		} finally {
			owner.close();
			owner.close();
		}
		Assertions.assertEquals(1, listenerRegistrations.get());
		Assertions.assertEquals(1, listenerRegistrationCloses.get());
		Assertions.assertEquals(0, publishedEvents.get());
	}

	@Test
	public void discoveryOmitsServerInformationMetadataWhenDisabled()
			throws Exception {
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("omitted-server-info", "4.0.0")
						.build())
				.includeServerInformation(false)
				.build();
		McpServer server = newMcpServer(0, endpoint,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet owner = mcpOnlySoklet(server, quietLifecycleObserver());

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow()
					.getPort();
			HttpResponse<String> response = sendDiscovery(port,
					"discover-without-server-info", "{}");

			assertSuccessfulDiscovery(response, "discover-without-server-info");
			Assertions.assertFalse(response.body().contains(
					"\"io.modelcontextprotocol/serverInfo\""), response.body());
		} finally {
			owner.close();
			owner.close();
		}
	}

	@Test
	public void customAdmissionReceivesPublicMetadataAndMapsTypedRejectionToWire()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicReference<McpAdmissionContext> observedContext = new AtomicReference<>();
		McpAdmissionRejection rejection = McpAdmissionRejection
				.withStatusCodeAndError(401, McpJsonRpcError.fromApplication(1_001,
						"Temporarily unavailable",
						McpJsonObject.builder().put("reason", "maintenance").build()))
				.header("WWW-Authenticate", "Bearer realm=soklet-mcp")
				.build();
		McpEndpoint endpoint = newEndpoint();
		McpServer server = newMcpServer(0, endpoint, context -> {
			admissions.incrementAndGet();
			observedContext.set(context);
			return McpAdmissionDecision.rejected(rejection);
		}, true);
		Soklet owner = mcpOnlySoklet(server, quietLifecycleObserver());

		try {
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			HttpResponse<String> response = sendDiscovery(port, "admission-1",
					"{\"roots\":{\"listChanged\":true}}");

			Assertions.assertEquals(401, response.statusCode(), response.body());
			Assertions.assertEquals("Bearer realm=soklet-mcp",
					response.headers().firstValue("WWW-Authenticate").orElseThrow());
			Assertions.assertTrue(response.body().contains("\"id\":\"admission-1\""),
					response.body());
			Assertions.assertTrue(response.body().contains("\"code\":1001"),
					response.body());
			Assertions.assertTrue(response.body().contains(
					"\"message\":\"Temporarily unavailable\""), response.body());
			Assertions.assertTrue(response.body().contains(
					"\"data\":{\"reason\":\"maintenance\"}"), response.body());

			Assertions.assertEquals(1, admissions.get());
			McpAdmissionContext context = observedContext.get();
			Assertions.assertNotNull(context);
			Assertions.assertSame(endpoint, context.getEndpoint());
			Assertions.assertEquals(DISCOVER_METHOD, context.getJsonRpcMethod());
			Assertions.assertFalse(context.isNotification());
			Assertions.assertEquals(McpRequestId.fromString("admission-1"),
					context.getRequestId().orElseThrow());
			Assertions.assertEquals(PROTOCOL_VERSION, context.getProtocolVersion());
			McpClientCapabilities capabilities =
					context.getClientCapabilities().orElseThrow();
			Assertions.assertTrue(capabilities.supports(McpClientCapability.ROOTS));
			Assertions.assertFalse(capabilities.supports(McpClientCapability.SAMPLING));
			Assertions.assertTrue(capabilities.toJson().find("roots").isPresent());
			Assertions.assertTrue(
					context.getRequestedResourceSubscriptionUris().isEmpty(),
					"Non-subscription admission must expose no requested resource URIs.");
		} finally {
			owner.close();
			owner.close();
		}
	}

	@Test
	public void failedFixedPortBindLeavesResourceAvailableToFreshOwnerAfterRelease()
			throws Exception {
		int port;
		try (ServerSocket occupied = new ServerSocket()) {
			occupied.setReuseAddress(false);
			occupied.bind(new InetSocketAddress(LOOPBACK, 0));
			port = occupied.getLocalPort();
			McpServer failedServer = newMcpServer(port,
					McpAdmissionController.acceptAllInstance(), true);
			Soklet failedOwner = mcpOnlySoklet(failedServer,
					quietLifecycleObserver());

			SokletStartupException failure = Assertions.assertThrows(
					SokletStartupException.class, failedOwner::start);
			Assertions.assertInstanceOf(BindException.class,
					failure.getCause());
			Assertions.assertEquals(McpServerStatus.TERMINATED,
					failedServer.getDiagnostics().getStatus());
			Assertions.assertTrue(failedServer.getDiagnostics()
					.getBoundAddress().isEmpty());
			failedOwner.close();
			failedOwner.close();
		}

		McpServer server = newMcpServer(port,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet owner = mcpOnlySoklet(server, quietLifecycleObserver());
		try {
			owner.start();
			Assertions.assertEquals(SokletStatus.RUNNING, owner.getStatus());
			Assertions.assertEquals(port,
					server.getDiagnostics().getBoundAddress().orElseThrow().getPort());
			assertSuccessfulDiscovery(sendDiscovery(port, "after-bind-release", "{}"),
					"after-bind-release");
		} finally {
			owner.close();
			owner.close();
		}
	}

	@Test
	public void omittedCorsDiagnosticIsExactAndOncePerSuccessfulSokletGeneration()
			throws Exception {
		List<LogEvent> events = new ArrayList<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				events.add(logEvent);
			}
		};
		McpServer firstServer = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), false);
		Soklet firstOwner = mcpOnlySoklet(firstServer, observer);

		try {
			Assertions.assertTrue(events.isEmpty());
			firstOwner.start();
			assertOmittedCorsEvents(events, 1);
		} finally {
			firstOwner.close();
			firstOwner.close();
		}
		assertOmittedCorsEvents(events, 1);

		McpServer secondServer = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), false);
		Soklet secondOwner = mcpOnlySoklet(secondServer, observer);
		try {
			secondOwner.start();
			assertOmittedCorsEvents(events, 2);
		} finally {
			secondOwner.close();
			secondOwner.close();
		}
	}

	@Test
	public void freshGenerationPublishesNeverBoundAddressBeforeStartupCallbacks()
			throws Exception {
		List<Optional<InetSocketAddress>> startupAddresses = new ArrayList<>();
		McpServer firstServer = newDevelopmentEphemeralMcpServer();
		Soklet firstOwner = mcpOnlySoklet(firstServer, new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				if (DefaultMcpServer.DEVELOPMENT_EPHEMERAL_PROTECTION_DIAGNOSTIC
						.equals(logEvent.getMessage()))
					startupAddresses.add(firstServer.getDiagnostics()
							.getBoundAddress());
			}
		});

		try {
			firstOwner.start();
		} finally {
			firstOwner.close();
			firstOwner.close();
		}
		Assertions.assertTrue(firstServer.getDiagnostics().getBoundAddress().isPresent(),
				"A stopped generation retains its successfully bound address.");

		McpServer secondServer = newDevelopmentEphemeralMcpServer();
		Soklet secondOwner = mcpOnlySoklet(secondServer, new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				if (DefaultMcpServer.DEVELOPMENT_EPHEMERAL_PROTECTION_DIAGNOSTIC
						.equals(logEvent.getMessage()))
					startupAddresses.add(secondServer.getDiagnostics()
							.getBoundAddress());
			}
		});
		try {
			secondOwner.start();
			Assertions.assertEquals(List.of(Optional.empty(), Optional.empty()),
					startupAddresses,
					"Every fresh startup callback begins with no bound-address history.");
		} finally {
			secondOwner.close();
			secondOwner.close();
		}
	}

	@Test
	public void startupDoesNotPublishReadyBeforeConfigurationDiagnosticsReturn()
			throws Exception {
		int port = findFreePort();
		CountDownLatch diagnosticEntered = new CountDownLatch(1);
		CountDownLatch releaseDiagnostic = new CountDownLatch(1);
		AtomicReference<Throwable> startFailure = new AtomicReference<>();
		McpServer server = newMcpServer(port,
				McpAdmissionController.acceptAllInstance(), false);
		Soklet soklet = mcpOnlySoklet(server, new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				if (logEvent.getLogEventType()
						!= LogEventType.MCP_SERVER_CONFIGURATION)
					return;
				diagnosticEntered.countDown();
				try {
					if (!releaseDiagnostic.await(10, TimeUnit.SECONDS))
						throw new AssertionError(
								"Timed out waiting to release the startup diagnostic.");
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(
							"Interrupted while holding the startup diagnostic.", exception);
				}
			}
		});
		Thread startThread = new Thread(() -> {
			try {
				soklet.start();
			} catch (Throwable throwable) {
				startFailure.set(throwable);
			}
		}, "mcp-blocked-start-test");
		startThread.setDaemon(true);

		try {
			startThread.start();
			Assertions.assertTrue(diagnosticEntered.await(5, TimeUnit.SECONDS),
					"MCP startup did not reach the omitted-CORS diagnostic.");

			HttpResponse<String> startingResponse = sendDiscovery(port,
					"while-starting", "{}");
			Assertions.assertEquals(503, startingResponse.statusCode(),
					"The bound listener must remain non-ready until startup diagnostics return.");

			releaseDiagnostic.countDown();
			startThread.join(TimeUnit.SECONDS.toMillis(5));
			Assertions.assertFalse(startThread.isAlive(),
					"MCP startup did not finish after the diagnostic returned.");
			if (startFailure.get() != null)
				Assertions.fail("MCP startup failed after the diagnostic returned.",
						startFailure.get());
			Assertions.assertEquals(SokletStatus.RUNNING, soklet.getStatus());
			Assertions.assertEquals(McpServerStatus.RUNNING,
					server.getDiagnostics().getStatus());
			assertSuccessfulDiscovery(sendDiscovery(port, "after-startup", "{}"),
					"after-startup");
		} finally {
			releaseDiagnostic.countDown();
			startThread.join(TimeUnit.SECONDS.toMillis(5));
			if (startThread.isAlive()) {
				startThread.interrupt();
				startThread.join(TimeUnit.SECONDS.toMillis(5));
			}
			if (!startThread.isAlive())
				soklet.close();
		}
	}

	@Test
	public void explicitRejectAllCorsSuppressesTheOmittedConfigurationDiagnostic()
			throws Exception {
		List<LogEvent> events = new ArrayList<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				events.add(logEvent);
			}
		};
		McpServer firstServer = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet firstOwner = mcpOnlySoklet(firstServer, observer);

		try {
			firstOwner.start();
		} finally {
			firstOwner.close();
			firstOwner.close();
		}

		McpServer secondServer = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), true);
		Soklet secondOwner = mcpOnlySoklet(secondServer, observer);
		try {
			secondOwner.start();
			Assertions.assertTrue(events.stream().noneMatch(event ->
					event.getLogEventType() == LogEventType.MCP_SERVER_CONFIGURATION),
					events.toString());
		} finally {
			secondOwner.close();
			secondOwner.close();
		}
	}

	@Test
	public void omittedCorsObserverFailureDoesNotChangeServerAvailability()
			throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		McpServer server = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), false);
		Soklet soklet = mcpOnlySoklet(server, new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				if (logEvent.getLogEventType()
						== LogEventType.MCP_SERVER_CONFIGURATION) {
					attempts.incrementAndGet();
					throw new IllegalStateException("expected observer failure");
				}
			}
		});

		try {
			Assertions.assertDoesNotThrow(soklet::start);
			Assertions.assertEquals(SokletStatus.RUNNING, soklet.getStatus());
			Assertions.assertEquals(McpServerStatus.RUNNING,
					server.getDiagnostics().getStatus());
			Assertions.assertEquals(1, attempts.get());
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			assertSuccessfulDiscovery(sendDiscovery(port, "observer-failure", "{}"),
					"observer-failure");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void mcpAndOrdinaryHttpUseSeparateListenersAndPorts() throws Exception {
		int httpPort = findFreePort();
		HttpServer httpServer = HttpServer.withPort(httpPort).host(LOOPBACK).build();
		McpServer mcpServer = newMcpServer(0,
				McpAdmissionController.acceptAllInstance(), true);
		SokletConfig config = SokletConfig.withHttpServer(httpServer)
				.mcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver
						.fromClasses(Set.of(HealthResource.class)))
				.lifecycleObserver(quietLifecycleObserver())
				.build();
		Soklet soklet = Soklet.fromConfig(config);

		try {
			soklet.start();
			int mcpPort = mcpServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();

			Assertions.assertNotEquals(httpPort, mcpPort,
					"MCP must bind independently from Soklet's ordinary HTTP server.");
			HttpResponse<String> httpResponse = sendGet(httpPort, "/health");
			Assertions.assertEquals(200, httpResponse.statusCode());
			Assertions.assertEquals("http-ok", httpResponse.body());
			assertSuccessfulDiscovery(sendDiscovery(mcpPort,
					"separate-listener", "{}"), "separate-listener");
		} finally {
			soklet.close();
		}
	}

	@NonNull
	private static McpServer newExecutorConfiguredMcpServer(
			@NonNull List<@NonNull ExecutorService> suppliedExecutors) {
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(newEndpoint())))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.requestHandlerConcurrency(3)
				.requestHandlerQueueCapacity(7)
				.requestTimeout(Duration.ofSeconds(2))
				.requestHandlerExecutorServiceSupplier(() -> {
					ExecutorService executor = Executors.newFixedThreadPool(4);
					suppliedExecutors.add(executor);
					return executor;
				})
				.build();
	}

	@NonNull
	private static McpServer newDevelopmentEphemeralMcpServer() {
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.of(newEndpoint())))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.protectionConfig(McpProtectionConfig
						.withDevelopmentEphemeralProtection().build())
				.build();
	}

	@NonNull
	private static McpServer newMcpServer(int port,
			@NonNull McpAdmissionController admissionController,
			boolean configureCorsAuthorizer) {
		return newMcpServer(port, newEndpoint(), admissionController,
				configureCorsAuthorizer);
	}

	@NonNull
	private static McpServer newMcpServer(int port, @NonNull McpEndpoint endpoint,
			@NonNull McpAdmissionController admissionController,
			boolean configureCorsAuthorizer) {
		McpServer.Builder builder = McpServer.withPort(port)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(admissionController);
		if (configureCorsAuthorizer)
			builder.corsAuthorizer(CorsAuthorizer.rejectAllInstance());
		return builder.build();
	}

	@NonNull
	private static McpEndpoint newEndpoint() {
		return McpEndpoint.withPath(MCP_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("public-runtime-test", "1.0")
						.build())
				.build();
	}

	@NonNull
	private static Soklet mcpOnlySoklet(@NonNull McpServer server,
			@NonNull LifecycleObserver lifecycleObserver) {
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancellationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownDuration(Duration.ofSeconds(2))
						.forcedShutdownDuration(Duration.ofSeconds(1))
						.build())
				.build();
		return Soklet.fromConfig(config);
	}

	@NonNull
	private static McpServerRuntimeBridge runtimeBridge(@NonNull McpServer server)
			throws Exception {
		Field bridgeField = DefaultMcpServer.class.getDeclaredField("runtimeBridge");
		bridgeField.setAccessible(true);
		return (McpServerRuntimeBridge) bridgeField.get(server);
	}

	@NonNull
	private static EventLoop eventLoop(@NonNull McpServerRuntimeBridge bridge)
			throws Exception {
		Field runtimeField = McpServerRuntimeBridge.class.getDeclaredField("runtime");
		runtimeField.setAccessible(true);
		Object runtime = runtimeField.get(bridge);
		Field eventLoopField = runtime.getClass().getDeclaredField("eventLoop");
		eventLoopField.setAccessible(true);
		return (EventLoop) eventLoopField.get(runtime);
	}

	private static void terminateUnexpectedly(@NonNull EventLoop eventLoop)
			throws Exception {
		Field selectorField = EventLoop.class.getDeclaredField("selector");
		selectorField.setAccessible(true);
		((Selector) selectorField.get(eventLoop)).close();
		Assertions.assertTrue(eventLoop.join(Duration.ofSeconds(2)),
				"The unexpectedly terminated MCP event loop did not exit.");
	}

	private static void assertTransportFailureEvent(
			@NonNull List<@NonNull LogEvent> events) {
		List<LogEvent> transportFailures = events.stream()
				.filter(event -> event.getLogEventType()
						== LogEventType.SERVER_TRANSPORT_FAILURE)
				.toList();
		Assertions.assertEquals(1, transportFailures.size(), events.toString());
		LogEvent transportFailure = transportFailures.get(0);
		Assertions.assertEquals("MCP transport failure: event_loop_terminate",
				transportFailure.getMessage());
		Assertions.assertTrue(transportFailure.getThrowable().isEmpty(),
				transportFailure.toString());
		Assertions.assertTrue(transportFailure.getRequest().isEmpty(),
				transportFailure.toString());
		Assertions.assertTrue(transportFailure.getResourceMethod().isEmpty(),
				transportFailure.toString());
		Assertions.assertTrue(transportFailure.getMarshaledResponse().isEmpty(),
				transportFailure.toString());
	}

	private static void stopOwnerAllowingUnexpectedTermination(
			@NonNull Soklet owner) {
		try {
			owner.close();
		} catch (SokletTerminatedUnexpectedlyException expected) {
			// Repeated owner shutdown replays the already-asserted terminal evidence.
		}
	}

	@NonNull
	private static LifecycleObserver quietLifecycleObserver() {
		return new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				// Quiet test lifecycle.
			}
		};
	}

	@NonNull
	private static DefaultMcpServerDiagnostics defaultSecurityDiagnosticSnapshot(
			McpServerStatus status, Optional<InetSocketAddress> boundAddress,
			int requestHandlerConcurrency, int requestHandlerQueueCapacity,
			int activeHandlerExecutions, int queuedRequests,
			int activeRequestStreams, int activeSubscriptions) {
		return diagnosticSnapshot(status, boundAddress,
				requestHandlerConcurrency, requestHandlerQueueCapacity,
				activeHandlerExecutions, queuedRequests,
				activeRequestStreams, activeSubscriptions,
				McpProtectionMode.NO_FRAMEWORK_KEYS, false,
				Optional.empty(), Optional.empty());
	}

	@NonNull
	private static DefaultMcpServerDiagnostics diagnosticSnapshot(
			McpServerStatus status, Optional<InetSocketAddress> boundAddress,
			int requestHandlerConcurrency, int requestHandlerQueueCapacity,
			int activeHandlerExecutions, int queuedRequests,
			int activeRequestStreams, int activeSubscriptions,
			McpProtectionMode protectionMode,
			boolean applicationRequestStateProtectorConfigured,
			Optional<McpProtectionKeyRingFingerprint> protectionKeyRingFingerprint,
			Optional<McpTraceCorrelationConfigurationFingerprint>
					traceCorrelationConfigurationFingerprint) {
		return new DefaultMcpServerDiagnostics(status, boundAddress,
				requestHandlerConcurrency, requestHandlerQueueCapacity,
				activeHandlerExecutions, queuedRequests,
				activeRequestStreams, activeSubscriptions, protectionMode,
				applicationRequestStateProtectorConfigured,
				protectionKeyRingFingerprint,
				traceCorrelationConfigurationFingerprint);
	}

	private static void assertOmittedCorsEvents(@NonNull List<LogEvent> events,
			int expectedCount) {
		List<LogEvent> corsEvents = events.stream()
				.filter(event -> event.getLogEventType()
						== LogEventType.MCP_SERVER_CONFIGURATION)
				.toList();
		Assertions.assertEquals(expectedCount, corsEvents.size(), events.toString());
		for (LogEvent event : corsEvents) {
			Assertions.assertEquals(OMITTED_CORS_AUTHORIZER_DIAGNOSTIC,
					event.getMessage());
			Assertions.assertTrue(event.getThrowable().isEmpty());
			Assertions.assertTrue(event.getRequest().isEmpty());
			Assertions.assertTrue(event.getResourceMethod().isEmpty());
			Assertions.assertTrue(event.getMarshaledResponse().isEmpty());
		}
	}

	private static void assertHandlerDiagnostics(
			@NonNull McpServerDiagnostics diagnostics,
			int requestHandlerConcurrency, int requestHandlerQueueCapacity,
			int activeHandlerExecutions, int queuedRequests) {
		assertDiagnostics(diagnostics, requestHandlerConcurrency,
				requestHandlerQueueCapacity, activeHandlerExecutions,
				queuedRequests, 0, 0);
	}

	private static void assertDiagnostics(
			@NonNull McpServerDiagnostics diagnostics,
			int requestHandlerConcurrency, int requestHandlerQueueCapacity,
			int activeHandlerExecutions, int queuedRequests,
			int activeRequestStreams, int activeSubscriptions) {
		Assertions.assertEquals(Integer.valueOf(requestHandlerConcurrency),
				diagnostics.getRequestHandlerConcurrency());
		Assertions.assertEquals(Integer.valueOf(requestHandlerQueueCapacity),
				diagnostics.getRequestHandlerQueueCapacity());
		Assertions.assertEquals(Integer.valueOf(activeHandlerExecutions),
				diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(Integer.valueOf(queuedRequests),
				diagnostics.getQueuedRequests());
		Assertions.assertEquals(Integer.valueOf(activeRequestStreams),
				diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(Integer.valueOf(activeSubscriptions),
				diagnostics.getActiveSubscriptions());
	}

	private static void assertSuccessfulDiscovery(
			@NonNull HttpResponse<String> response, @NonNull String expectedId) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertEquals("no-store",
				response.headers().firstValue("Cache-Control").orElseThrow());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + expectedId + "\""), response.body());
		Assertions.assertTrue(response.body().contains(
				"\"supportedVersions\":[\"" + PROTOCOL_VERSION + "\"]"),
				response.body());
	}

	@NonNull
	private static HttpResponse<String> sendDiscovery(int port, @NonNull String id,
			@NonNull String clientCapabilitiesJson) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + DISCOVER_METHOD + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + PROTOCOL_VERSION
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":"
				+ clientCapabilitiesJson + "}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + MCP_PATH))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", DISCOVER_METHOD)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		return httpClient().send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@NonNull
	private static HttpResponse<String> sendGet(int port, @NonNull String path)
			throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + LOOPBACK + ":" + port + path))
				.timeout(Duration.ofSeconds(5))
				.header("Accept", "text/plain")
				.GET()
				.build();
		return httpClient().send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@NonNull
	private static HttpClient httpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build();
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(false);
			socket.bind(new InetSocketAddress(LOOPBACK, 0));
			return socket.getLocalPort();
		}
	}

	public static final class HealthResource {
		@GET("/health")
		@NonNull
		public String health() {
			return "http-ok";
		}
	}
}
