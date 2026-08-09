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
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedSelectorException;
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
		McpServer server = McpServer.withPort(0)
				.handlerResolver(McpHandlerResolver.fromEndpoints(
						List.of(newEndpoint())))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
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

		Assertions.assertTrue(suppliedExecutors.isEmpty(),
				"Building a server must not allocate its generation executor.");
		try {
			server.start();
			Assertions.assertEquals(1, suppliedExecutors.size());
			Assertions.assertFalse(suppliedExecutors.get(0).isShutdown());
			server.stop();
			Assertions.assertTrue(suppliedExecutors.get(0).isShutdown());

			server.start();
			Assertions.assertEquals(2, suppliedExecutors.size());
			Assertions.assertNotSame(suppliedExecutors.get(0),
					suppliedExecutors.get(1));
			Assertions.assertFalse(suppliedExecutors.get(1).isShutdown());
		} finally {
			server.stop();
			for (ExecutorService executor : suppliedExecutors)
				executor.shutdownNow();
		}
		Assertions.assertTrue(suppliedExecutors.stream()
				.allMatch(ExecutorService::isShutdown));

		ExecutorService shutDownExecutor = Executors.newSingleThreadExecutor();
		shutDownExecutor.shutdown();
		McpServer invalidServer = McpServer.withPort(0)
				.handlerResolver(McpHandlerResolver.fromEndpoints(
						List.of(newEndpoint())))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.requestHandlerExecutorServiceSupplier(() -> shutDownExecutor)
				.build();
		try {
			IllegalStateException exception = Assertions.assertThrows(
					IllegalStateException.class, invalidServer::start);
			Assertions.assertTrue(exception.getMessage().contains("shut-down"),
					exception.getMessage());
			Assertions.assertEquals(McpServerStatus.STOPPED,
					invalidServer.getDiagnostics().getStatus());
		} finally {
			invalidServer.stop();
		}
	}

	@Test
	public void directPortZeroLifecyclePublishesImmutableDiagnosticSnapshots()
			throws Exception {
		McpServer server = newMcpServer(0,
				McpRequestAdmissionPolicy.acceptAllInstance(), true);
		McpServerDiagnostics initial = server.getDiagnostics();

		Assertions.assertFalse(server.isStarted());
		Assertions.assertEquals(McpServerStatus.STOPPED, initial.getStatus());
		Assertions.assertTrue(initial.getBoundAddress().isEmpty());
		assertHandlerDiagnostics(initial, 32, 128, 0, 0);

		server.stop();
		server.stop();
		try {
			server.start();
			McpServerDiagnostics firstStarted = server.getDiagnostics();
			InetSocketAddress firstAddress = firstStarted.getBoundAddress().orElseThrow();

			Assertions.assertTrue(server.isStarted());
			Assertions.assertEquals(McpServerStatus.STARTED, firstStarted.getStatus());
			assertHandlerDiagnostics(firstStarted, 32, 128, 0, 0);
			Assertions.assertEquals(LOOPBACK,
					firstAddress.getAddress().getHostAddress());
			Assertions.assertTrue(firstAddress.getPort() > 0);
			Assertions.assertEquals(McpServerStatus.STOPPED, initial.getStatus(),
					"A retained pre-start snapshot must not change.");
			Assertions.assertTrue(initial.getBoundAddress().isEmpty());
			assertHandlerDiagnostics(initial, 32, 128, 0, 0);

			server.start();
			Assertions.assertEquals(firstAddress,
					server.getDiagnostics().getBoundAddress().orElseThrow(),
					"A redundant start must retain the current listener generation.");
			assertSuccessfulDiscovery(sendDiscovery(firstAddress.getPort(),
					"first-generation", "{}"), "first-generation");

			server.stop();
			McpServerDiagnostics firstStopped = server.getDiagnostics();
			Assertions.assertFalse(server.isStarted());
			Assertions.assertEquals(McpServerStatus.STOPPED, firstStopped.getStatus());
			Assertions.assertTrue(firstStopped.getBoundAddress().isEmpty());
			assertHandlerDiagnostics(firstStopped, 32, 128, 0, 0);
			Assertions.assertEquals(McpServerStatus.STARTED, firstStarted.getStatus(),
					"A retained started snapshot must not change after stop.");
			Assertions.assertEquals(firstAddress,
					firstStarted.getBoundAddress().orElseThrow());
			assertHandlerDiagnostics(firstStarted, 32, 128, 0, 0);

			server.stop();
			server.start();
			server.start();
			McpServerDiagnostics secondStarted = server.getDiagnostics();
			int secondPort = secondStarted.getBoundAddress().orElseThrow().getPort();
			Assertions.assertEquals(McpServerStatus.STARTED, secondStarted.getStatus());
			assertHandlerDiagnostics(secondStarted, 32, 128, 0, 0);
			Assertions.assertEquals(McpServerStatus.STOPPED, firstStopped.getStatus(),
					"A retained stopped snapshot must not change after restart.");
			Assertions.assertTrue(firstStopped.getBoundAddress().isEmpty());
			assertHandlerDiagnostics(firstStopped, 32, 128, 0, 0);
			assertSuccessfulDiscovery(sendDiscovery(secondPort,
					"second-generation", "{}"), "second-generation");
		} finally {
			server.stop();
			server.stop();
			server.close();
			server.close();
		}

		Assertions.assertFalse(server.isStarted());
		McpServerDiagnostics finalSnapshot = server.getDiagnostics();
		Assertions.assertEquals(McpServerStatus.STOPPED,
				finalSnapshot.getStatus());
		assertHandlerDiagnostics(finalSnapshot, 32, 128, 0, 0);
	}

	@Test
	public void diagnosticSnapshotValidatesLifecycleHandlerAndStreamTuples() {
		InetSocketAddress address = new InetSocketAddress(LOOPBACK, 12_345);
		McpServerDiagnostics started = new DefaultMcpServerDiagnostics(
				McpServerStatus.STARTED, Optional.of(address), 2, 3, 1, 2, 4, 3);

		Assertions.assertEquals(McpServerStatus.STARTED, started.getStatus());
		Assertions.assertEquals(address, started.getBoundAddress().orElseThrow());
		assertDiagnostics(started, 2, 3, 1, 2, 4, 3);

		McpServerDiagnostics residualCleanup = new DefaultMcpServerDiagnostics(
				McpServerStatus.STOPPED_WITH_RESIDUAL_HANDLERS, Optional.empty(),
				2, 3, 1, 2, 1, 1);
		Assertions.assertEquals(McpServerStatus.STOPPED_WITH_RESIDUAL_HANDLERS,
				residualCleanup.getStatus());
		assertDiagnostics(residualCleanup, 2, 3, 1, 2, 1, 1);

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.empty(), 2, 3, 0, 0, 0, 0),
				"A STARTED snapshot without a bound address violates the public contract.");
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STOPPED, Optional.of(address), 2, 3, 0, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.of(address), 0, 3, 0, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.of(address), 2, 0, 0, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.of(address), 2, 3, -1, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.of(address), 2, 3, 3, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.of(address), 2, 3, 0, -1, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.of(address), 2, 3, 0, 4, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STOPPED, Optional.empty(), 2, 3, 1, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STOPPED, Optional.empty(), 2, 3, 0, 1, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.of(address), 2, 3, 0, 0,
						-1, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.of(address), 2, 3, 0, 0,
						1, -1));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STARTED, Optional.of(address), 2, 3, 0, 0,
						1, 2));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STOPPED, Optional.empty(), 2, 3, 0, 0,
						1, 0));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new DefaultMcpServerDiagnostics(
						McpServerStatus.STOPPED, Optional.empty(), 2, 3, 0, 0,
						1, 1));
	}

	@Test
	public void runtimeBridgeStateKeepsStartedAndBoundAddressAtomicAcrossLifecycle()
			throws Exception {
		McpServerRuntimeBridge bridge = new McpServerRuntimeBridge(
				LOOPBACK, 0, newEndpoint(), Set.of(LOOPBACK), false,
				CorsAuthorizer.rejectAllInstance(), true,
				ignored -> McpAdmissionDecision.fromAnonymousIdentity(), ignored -> {});

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
			Assertions.assertTrue(stoppedAgain.boundAddress().isEmpty());
			Assertions.assertFalse(stoppedAgain.residualHandlers());
		} finally {
			bridge.stop();
		}
	}

	@Test
	public void sokletStopCleansUnexpectedMcpListenerTerminationForRestart()
			throws Exception {
		List<LogEvent> events = new ArrayList<>();
		McpServer server = newMcpServer(0,
				McpRequestAdmissionPolicy.acceptAllInstance(), true);
		Soklet soklet = mcpOnlySoklet(server, new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				events.add(logEvent);
			}
		});
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			soklet.start();
			terminateUnexpectedly(eventLoop(bridge));
			assertTransportFailureEvent(events);

			RuntimeState failed = bridge.getRuntimeState();
			Assertions.assertFalse(failed.started());
			Assertions.assertTrue(failed.stopRequired(),
					"A failed listener generation must still require cleanup.");
			Assertions.assertTrue(failed.boundAddress().isEmpty());
			Assertions.assertFalse(server.isStarted());

			soklet.stop();
			RuntimeState stopped = bridge.getRuntimeState();
			Assertions.assertFalse(stopped.started());
			Assertions.assertFalse(stopped.stopRequired());
			Assertions.assertTrue(stopped.boundAddress().isEmpty());

			soklet.start();
			int restartedPort = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			assertSuccessfulDiscovery(sendDiscovery(restartedPort,
					"after-unexpected-termination", "{}"),
					"after-unexpected-termination");
		} finally {
			soklet.stop();
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void directStartNormalizesUnexpectedMcpListenerTerminationForRestart()
			throws Exception {
		McpServer server = newMcpServer(0,
				McpRequestAdmissionPolicy.acceptAllInstance(), true);
		Soklet soklet = mcpOnlySoklet(server, quietLifecycleObserver());
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			server.start();
			EventLoop failedEventLoop = eventLoop(bridge);
			terminateUnexpectedly(failedEventLoop);

			RuntimeState failed = bridge.getRuntimeState();
			Assertions.assertFalse(failed.started());
			Assertions.assertTrue(failed.stopRequired());
			Assertions.assertTrue(failed.boundAddress().isEmpty());

			server.start();
			RuntimeState restarted = bridge.getRuntimeState();
			Assertions.assertTrue(restarted.started());
			Assertions.assertTrue(restarted.stopRequired());
			int restartedPort = restarted.boundAddress().orElseThrow().getPort();
			Assertions.assertNotSame(failedEventLoop, eventLoop(bridge));
			assertSuccessfulDiscovery(sendDiscovery(restartedPort,
					"after-direct-restart", "{}"), "after-direct-restart");
		} finally {
			soklet.stop();
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void discoveryAdvertisesConfiguredServerInformationWithoutOperationCapabilities()
			throws Exception {
		McpImplementation implementation = McpImplementation
				.withNameAndVersion("public-runtime", "3.6.0-SNAPSHOT")
				.title("Public Runtime")
				.description("Operation-free public projection")
				.websiteUrl(URI.create("https://example.test/soklet-mcp"))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH)
				.serverInformation(implementation)
				.instructions("Use the public discovery endpoint.")
				.build();
		McpServer server = newMcpServer(0, endpoint,
				McpRequestAdmissionPolicy.acceptAllInstance(), true);

		try {
			server.start();
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
					"\"version\":\"3.6.0-SNAPSHOT\""), body);
			Assertions.assertTrue(body.contains("\"title\":\"Public Runtime\""), body);
			Assertions.assertTrue(body.contains(
					"\"description\":\"Operation-free public projection\""), body);
			Assertions.assertTrue(body.contains(
					"\"websiteUrl\":\"https://example.test/soklet-mcp\""), body);
			Assertions.assertTrue(body.contains(
					"\"instructions\":\"Use the public discovery endpoint.\""), body);
		} finally {
			server.stop();
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
					public McpSubscriptionEventSubscription subscribe(
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
				McpRequestAdmissionPolicy.acceptAllInstance(), true);

		Assertions.assertEquals(0, listenerRegistrations.get());
		Assertions.assertEquals(0, listenerRegistrationCloses.get());
		Assertions.assertEquals(0, publishedEvents.get());
		try {
			server.start();
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
			server.stop();
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
						.withNameAndVersion("omitted-server-info", "3.6.0-SNAPSHOT")
						.build())
				.includeServerInformation(false)
				.build();
		McpServer server = newMcpServer(0, endpoint,
				McpRequestAdmissionPolicy.acceptAllInstance(), true);

		try {
			server.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow()
					.getPort();
			HttpResponse<String> response = sendDiscovery(port,
					"discover-without-server-info", "{}");

			assertSuccessfulDiscovery(response, "discover-without-server-info");
			Assertions.assertFalse(response.body().contains(
					"\"io.modelcontextprotocol/serverInfo\""), response.body());
		} finally {
			server.stop();
		}
	}

	@Test
	public void customAdmissionReceivesPublicMetadataAndMapsTypedRejectionToWire()
			throws Exception {
		AtomicInteger admissions = new AtomicInteger();
		AtomicReference<McpAdmissionContext> observedContext = new AtomicReference<>();
		McpRequestRejection rejection = McpRequestRejection
				.withStatusCodeAndError(401, McpJsonRpcError.fromApplication(1_001,
						"Temporarily unavailable",
						McpJsonObject.builder().put("reason", "maintenance").build()))
				.header("WWW-Authenticate", "Bearer realm=soklet-mcp")
				.build();
		McpEndpoint endpoint = newEndpoint();
		McpServer server = newMcpServer(0, endpoint, context -> {
			admissions.incrementAndGet();
			observedContext.set(context);
			return McpAdmissionDecision.fromRejection(rejection);
		}, true);

		try {
			server.start();
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
		} finally {
			server.stop();
		}
	}

	@Test
	public void failedFixedPortBindLeavesTheSameServerRetryableAfterRelease()
			throws Exception {
		McpServer server;
		int port;
		try (ServerSocket occupied = new ServerSocket()) {
			occupied.setReuseAddress(false);
			occupied.bind(new InetSocketAddress(LOOPBACK, 0));
			port = occupied.getLocalPort();
			server = newMcpServer(port,
					McpRequestAdmissionPolicy.acceptAllInstance(), true);

			Assertions.assertThrows(UncheckedIOException.class, server::start);
			Assertions.assertFalse(server.isStarted());
			Assertions.assertEquals(McpServerStatus.STOPPED,
					server.getDiagnostics().getStatus());
			Assertions.assertTrue(server.getDiagnostics().getBoundAddress().isEmpty());
		}

		try {
			server.start();
			Assertions.assertTrue(server.isStarted());
			Assertions.assertEquals(port,
					server.getDiagnostics().getBoundAddress().orElseThrow().getPort());
			assertSuccessfulDiscovery(sendDiscovery(port, "after-bind-release", "{}"),
					"after-bind-release");
		} finally {
			server.stop();
		}
	}

	@Test
	public void omittedCorsDiagnosticIsExactAndOncePerSuccessfulSokletGeneration()
			throws Exception {
		List<LogEvent> events = new ArrayList<>();
		McpServer server = newMcpServer(0,
				McpRequestAdmissionPolicy.acceptAllInstance(), false);
		Soklet soklet = mcpOnlySoklet(server, new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				events.add(logEvent);
			}
		});

		try {
			Assertions.assertTrue(events.isEmpty());
			soklet.start();
			assertOmittedCorsEvents(events, 1);

			soklet.start();
			assertOmittedCorsEvents(events, 1);
			soklet.stop();
			soklet.stop();
			assertOmittedCorsEvents(events, 1);

			soklet.start();
			assertOmittedCorsEvents(events, 2);
		} finally {
			soklet.stop();
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
				McpRequestAdmissionPolicy.acceptAllInstance(), false);
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
			Assertions.assertTrue(soklet.isStarted());
			Assertions.assertTrue(server.isStarted());
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
				soklet.stop();
		}
	}

	@Test
	public void explicitRejectAllCorsSuppressesTheOmittedConfigurationDiagnostic()
			throws Exception {
		List<LogEvent> events = new ArrayList<>();
		McpServer server = newMcpServer(0,
				McpRequestAdmissionPolicy.acceptAllInstance(), true);
		Soklet soklet = mcpOnlySoklet(server, new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				events.add(logEvent);
			}
		});

		try {
			soklet.start();
			soklet.stop();
			soklet.start();
			Assertions.assertTrue(events.stream().noneMatch(event ->
					event.getLogEventType() == LogEventType.MCP_SERVER_CONFIGURATION),
					events.toString());
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void omittedCorsObserverFailureDoesNotChangeServerAvailability()
			throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		McpServer server = newMcpServer(0,
				McpRequestAdmissionPolicy.acceptAllInstance(), false);
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
			Assertions.assertTrue(soklet.isStarted());
			Assertions.assertTrue(server.isStarted());
			Assertions.assertEquals(1, attempts.get());
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			assertSuccessfulDiscovery(sendDiscovery(port, "observer-failure", "{}"),
					"observer-failure");
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void mcpAndOrdinaryHttpUseSeparateListenersAndPorts() throws Exception {
		int httpPort = findFreePort();
		HttpServer httpServer = HttpServer.withPort(httpPort).host(LOOPBACK).build();
		McpServer mcpServer = newMcpServer(0,
				McpRequestAdmissionPolicy.acceptAllInstance(), true);
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
			soklet.stop();
		}
	}

	@NonNull
	private static McpServer newMcpServer(int port,
			@NonNull McpRequestAdmissionPolicy admissionPolicy,
			boolean configureCorsAuthorizer) {
		return newMcpServer(port, newEndpoint(), admissionPolicy,
				configureCorsAuthorizer);
	}

	@NonNull
	private static McpServer newMcpServer(int port, @NonNull McpEndpoint endpoint,
			@NonNull McpRequestAdmissionPolicy admissionPolicy,
			boolean configureCorsAuthorizer) {
		McpServer.Builder builder = McpServer.withPort(port)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(admissionPolicy);
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
		Assertions.assertInstanceOf(ClosedSelectorException.class,
				transportFailure.getThrowable().orElseThrow());
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
