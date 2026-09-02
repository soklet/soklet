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
import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression coverage for core Soklet ownership of the independent MCP transport.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class SokletMcpLifecycleTests {
	@NonNull
	private static final String NO_RESOURCE_METHODS =
			"No Soklet Resource Methods were found. First, try to rebuild and see if that solves the problem. If not, please ensure your "
					+ ResourceMethodResolver.class.getSimpleName()
					+ " is configured correctly. See https://www.soklet.com/docs/request-handling#resource-method-resolution for details.";

	@Test
	@Timeout(60)
	public void noncooperativeMcpHandlerFreezesOneResidualOutcomeAcrossLaterCalls()
			throws Exception {
		String host = "127.0.0.1";
		String path = "/mcp/residual-lifecycle";
		String toolName = "lifecycle.blocking";
		Duration shutdownTimeout = Duration.ofMillis(150);
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		AtomicInteger willStopMcpCallbacks = new AtomicInteger();
		List<ShutdownComponentResult> stopResults = new CopyOnWriteArrayList<>();
		AtomicReference<ShutdownResult> globalResult = new AtomicReference<>();
		CountDownLatch terminalObserved = new CountDownLatch(1);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(toolName)
				.jsonArguments()
				.handler((request, arguments, features) -> {
					handlerEntered.countDown();
					try {
						while (releaseHandler.getCount() != 0L) {
							try {
								releaseHandler.await(25, TimeUnit.MILLISECONDS);
							} catch (InterruptedException exception) {
								handlerInterrupted.countDown();
								// Model application code that ignores cooperative shutdown.
							}
						}
						return McpCompleteResult.fromToolText("released");
					} finally {
						handlerExited.countDown();
					}
				})
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(path)
				.serverInformation(McpImplementation.withNameAndVersion(
						"residual-lifecycle-test", "4.0.0").build())
				.tool(tool)
				.build();
		McpServer mcpServer = McpServer.withPort(0)
				.host(host)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(host))
				.requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(1)
				.build();
		LifecycleObserver lifecycleObserver = new LifecycleObserver() {
			@Override
			public void willStopMcpServer(@NonNull McpServer server) {
				willStopMcpCallbacks.incrementAndGet();
			}

			@Override
			public void didStopMcpServer(@NonNull McpServer server,
					@NonNull ShutdownComponentResult result) {
				stopResults.add(result);
			}

			@Override
			public void didStopSoklet(@NonNull Soklet soklet,
					@NonNull ShutdownResult result) {
				globalResult.set(result);
				terminalObserved.countDown();
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				// Keep expected MCP configuration diagnostics out of test output.
			}
		};
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(lifecycleObserver)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownDuration(shutdownTimeout)
						.forcedShutdownDuration(Duration.ofSeconds(3))
						.build())
				.build());
		CompletableFuture<HttpResponse<String>> request = null;

		try {
			soklet.start();
			int port = mcpServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			request = callTool(host, port, path, toolName);
			Assertions.assertTrue(handlerEntered.await(5, TimeUnit.SECONDS),
					"The public MCP handler did not enter.");

			long stopStartedAt = System.nanoTime();
			ShutdownIncompleteException stopFailure = Assertions.assertThrows(
					ShutdownIncompleteException.class, soklet::close);
			Duration stopDuration = Duration.ofNanos(
					System.nanoTime() - stopStartedAt);
			InternalShutdownResult result = stopFailure.getInternalShutdownResult();
			InternalLifecycleComponentShutdownResult mcpResult = result.participantResult(
					InternalLifecycleComponentType.MCP).orElseThrow();

			Assertions.assertTrue(stopDuration.compareTo(
					shutdownTimeout.plusSeconds(4)) < 0,
					() -> "MCP shutdown exceeded its grace, fixed three-second "
							+ "forced-observation window, and scheduling tolerance: "
							+ stopDuration);
			Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS),
					"Shutdown did not interrupt the noncooperative handler.");
			Assertions.assertEquals(
					McpServerStatus.RESIDUAL_ACTIVITY,
					mcpServer.getDiagnostics().getStatus());
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.READY,
					result.startupDisposition());
			Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
					result.disposition());
			Assertions.assertEquals(
					InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY,
					mcpResult.disposition());
			Assertions.assertFalse(mcpResult.residualActivity().isEmpty());
			Assertions.assertTrue(terminalObserved.await(5, TimeUnit.SECONDS),
					"The asynchronous terminal transition was not observed.");
			ShutdownResult observedAggregate = globalResult.get();
			Assertions.assertNotNull(observedAggregate);
			ShutdownComponentResult observedMcp = observedAggregate
					.getShutdownComponentResult(ShutdownComponentType.MCP).orElseThrow();
			Assertions.assertEquals(List.of(observedMcp), stopResults,
					"An incomplete MCP participant must publish one exact terminal callback.");
			Assertions.assertEquals(
					ShutdownComponentDisposition.RESIDUAL_ACTIVITY,
					observedMcp.getShutdownComponentDisposition());
			Assertions.assertEquals(1, willStopMcpCallbacks.get());
			Assertions.assertSame(result,
					observedAggregate.internalResult());

			ShutdownIncompleteException repeatedStopFailure = Assertions.assertThrows(
					ShutdownIncompleteException.class, soklet::close);
			Assertions.assertSame(result,
					repeatedStopFailure.getInternalShutdownResult());
			Assertions.assertEquals(1, willStopMcpCallbacks.get(),
					"Repeated stop while only a residual handler remains must be a no-op.");
			Assertions.assertEquals(List.of(observedMcp), stopResults);

			Assertions.assertThrows(
					IllegalStateException.class, soklet::start);
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow(),
					"The one-shot start rejection must not replace the frozen result.");

			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(5, TimeUnit.SECONDS),
					"The released MCP handler did not exit.");
			Assertions.assertEquals(McpServerStatus.RESIDUAL_ACTIVITY,
					mcpServer.getDiagnostics().getStatus(),
					"Late cleanup must not rewrite the frozen residual result.");
			ShutdownIncompleteException lateStopFailure = Assertions.assertThrows(
					ShutdownIncompleteException.class, soklet::close);
			Assertions.assertSame(result, lateStopFailure.getInternalShutdownResult(),
					"Late physical cleanup cannot rewrite an immutable residual result.");
			Assertions.assertEquals(1, stopResults.size(),
					"Late cleanup must not emit another terminal transition.");
		} finally {
			releaseHandler.countDown();
			if (request != null)
				request.cancel(true);
			try {
				soklet.close();
			} catch (ShutdownIncompleteException ignored) {
				// The immutable residual result remains the expected terminal result.
			}
		}

		Assertions.assertEquals(1, stopResults.size());
	}

	@Test
	public void mcpOnlySokletDoesNotRequireHttpResourceMethods() throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		McpServer mcpServer = newMcpServer();
		RecordingLifecycleObserver observer =
				new RecordingLifecycleObserver(events, null);
		SokletConfig config = SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(observer)
				.build();
		Soklet soklet = Soklet.fromConfig(config);

		try {
			soklet.start();
			Assertions.assertEquals(SokletStatus.RUNNING, soklet.getStatus());
			soklet.close();
			observer.awaitTerminal();
			Assertions.assertEquals(SokletStatus.CLOSED, soklet.getStatus());

			List<String> eventsAfterFirstStop = List.copyOf(events);
			soklet.close();

			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-mcp",
					"did-start-mcp",
					"did-start-soklet",
					"will-stop-soklet",
					"will-stop-mcp",
					"did-stop-mcp-GRACEFUL_TERMINATION",
					"did-stop-soklet"), eventsAfterFirstStop);
			Assertions.assertEquals(eventsAfterFirstStop, events,
					"Stopping an already-stopped MCP-only Soklet must be a no-op");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void mixedTransportLifecycleUsesConfiguredStartAndStopOrder()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		RecordingLifecycleObserver observer =
				new RecordingLifecycleObserver(events, null);
		SokletConfig config = mixedTransportConfig(observer);
		Soklet soklet = Soklet.fromConfig(config);

		try {
			soklet.start();
			soklet.close();
			observer.awaitTerminal();

			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-http",
					"did-start-http",
					"will-start-sse",
					"did-start-sse",
					"will-start-mcp",
					"did-start-mcp",
					"did-start-soklet",
					"will-stop-soklet",
					"will-stop-http",
					"will-stop-sse",
					"will-stop-mcp",
					"did-stop-http",
					"did-stop-sse",
					"did-stop-mcp-GRACEFUL_TERMINATION",
					"did-stop-soklet"), events);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void startedMcpOwnerRejectsMixedSecondOwnerAndClaimDoesNotRetry() {
		McpServer mcpServer = newMcpServer();
		Soklet firstOwner = Soklet.fromConfig(SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());
		SokletConfig mixedConfig = SokletConfig
				.withHttpServer(HttpServer.withPort(0).build())
				.sseServer(new LifecycleSseServer())
				.mcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(MixedTransportResource.class)))
				.build();

		try {
			firstOwner.start();
			TransportOwnershipException runningConflict = Assertions.assertThrows(
					TransportOwnershipException.class,
					() -> Soklet.fromConfig(mixedConfig));
			Assertions.assertEquals(ShutdownComponentType.MCP,
					runningConflict.getShutdownComponentType());
			Assertions.assertSame(mcpServer.getClass(),
					runningConflict.getTransportClass());

			firstOwner.close();
			TransportOwnershipException terminalConflict = Assertions.assertThrows(
					TransportOwnershipException.class,
					() -> Soklet.fromConfig(mixedConfig));
			Assertions.assertEquals(ShutdownComponentType.MCP,
					terminalConflict.getShutdownComponentType());
			Assertions.assertSame(mcpServer.getClass(),
					terminalConflict.getTransportClass(),
					"A terminal first owner must not make its transport claim retryable.");
		} finally {
			firstOwner.close();
		}
	}

	@Test
	public void failedMixedStartIsOneShotAndRetainsItsExactResult()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FailingSseServer sseServer = new FailingSseServer();
		RecordingLifecycleObserver observer =
				new RecordingLifecycleObserver(events, null);
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build())
				.sseServer(sseServer)
				.mcpServer(newMcpServer())
				.resourceMethodResolver(
						ResourceMethodResolver.fromClasses(Set.of(MixedTransportResource.class)))
				.lifecycleObserver(observer)
				.build();
		Soklet soklet = Soklet.fromConfig(config);
		McpServer mcpServer = config.getMcpServer().orElseThrow();

		try {
			SokletStartupException startupFailure = Assertions.assertThrows(
					SokletStartupException.class, soklet::start);
			observer.awaitTerminal();
			InternalShutdownResult result = startupFailure.getInternalShutdownResult();

			Assertions.assertSame(sseServer.failure(), startupFailure.getCause());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					startupFailure.getInternalStartupDisposition());
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertEquals(McpServerStatus.TERMINATED,
					mcpServer.getDiagnostics().getStatus());
			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-http",
					"did-start-http",
					"will-start-sse",
					"did-fail-start-sse",
					"did-fail-start-soklet",
					"will-stop-soklet",
					"will-stop-http",
					"will-stop-sse",
					"will-stop-mcp",
					"did-stop-http",
					"did-stop-sse",
					"did-stop-mcp-NOT_STARTED",
					"did-stop-soklet"), events);

			Assertions.assertThrows(IllegalStateException.class, soklet::start);
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertEquals(1, sseServer.startAttempts(),
					"A failed one-shot owner must not retry participant startup.");
		} finally {
			soklet.close();
		}
	}

	@Test
	public void didStartMcpObserverFailureIsObservationalAndDoesNotVetoLifecycle()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		RuntimeException expectedFailure = new RuntimeException("expected MCP did-start failure");
		RecordingLifecycleObserver observer =
				new RecordingLifecycleObserver(events, expectedFailure);
		SokletConfig config = mixedTransportConfig(observer);
		Soklet soklet = Soklet.fromConfig(config);

		try {
			soklet.start();
			observer.awaitReady();
			Assertions.assertEquals(SokletStatus.RUNNING, soklet.getStatus(),
					"An observer exception must not veto published readiness.");
			soklet.close();
			observer.awaitTerminal();
			Assertions.assertTrue(soklet.getDirectLifecycle().result()
					.orElseThrow().isComplete());
			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-http",
					"did-start-http",
					"will-start-sse",
					"did-start-sse",
					"will-start-mcp",
					"did-start-mcp",
					"did-start-soklet",
					"will-stop-soklet",
					"will-stop-http",
					"will-stop-sse",
					"will-stop-mcp",
					"did-stop-http",
					"did-stop-sse",
					"did-stop-mcp-GRACEFUL_TERMINATION",
					"did-stop-soklet"), events);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void stopObserverFailureIsObservationalAndPreservesPhaseOrder()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		RuntimeException expectedFailure = new RuntimeException("expected HTTP will-stop failure");
		AtomicReference<Throwable> globalFailure = new AtomicReference<>();
		CountDownLatch terminalObserved = new CountDownLatch(1);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void willStopSoklet(@NonNull Soklet soklet) {
				events.add("will-stop-soklet");
			}

			@Override
			public void willStopHttpServer(@NonNull HttpServer httpServer) {
				events.add("will-stop-http");
				throw expectedFailure;
			}

			@Override
			public void didStopHttpServer(@NonNull HttpServer httpServer,
					@NonNull ShutdownComponentResult result) {
				events.add("did-stop-http");
			}

			@Override
			public void willStopSseServer(@NonNull SseServer sseServer) {
				events.add("will-stop-sse");
			}

			@Override
			public void didStopSseServer(@NonNull SseServer sseServer,
					@NonNull ShutdownComponentResult result) {
				events.add("did-stop-sse");
			}

			@Override
			public void willStopMcpServer(@NonNull McpServer mcpServer) {
				events.add("will-stop-mcp");
			}

			@Override
			public void didStopMcpServer(@NonNull McpServer mcpServer,
					@NonNull ShutdownComponentResult result) {
				events.add("did-stop-mcp-" + result.getShutdownComponentDisposition().name());
			}

			@Override
			public void didStopSoklet(@NonNull Soklet soklet,
					@NonNull ShutdownResult result) {
				events.add("did-stop-soklet");
				terminalObserved.countDown();
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				// Keep expected MCP configuration diagnostics out of test output.
			}
		};
		Soklet soklet = Soklet.fromConfig(mixedTransportConfig(observer));

		try {
			soklet.start();
			soklet.close();
			Assertions.assertTrue(terminalObserved.await(5, TimeUnit.SECONDS),
					"The asynchronous stop observer did not reach its terminal callback.");

			Assertions.assertEquals(SokletStatus.CLOSED, soklet.getStatus());
			Assertions.assertNull(globalFailure.get(),
					"An observer callback failure must not change lifecycle outcome.");
			Assertions.assertTrue(soklet.getDirectLifecycle().result()
					.orElseThrow().isComplete());
			Assertions.assertEquals(List.of(
					"will-stop-soklet",
					"will-stop-http",
					"will-stop-sse",
					"will-stop-mcp",
					"did-stop-http",
					"did-stop-sse",
					"did-stop-mcp-GRACEFUL_TERMINATION",
					"did-stop-soklet"), events);
		} finally {
			soklet.close();
		}
	}

	@Test
	public void httpValidationRunsAtStartAndRetainsItsExactCause() {
		SokletConfig config = SokletConfig.withMcpServer(newMcpServer())
				.httpServer(HttpServer.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();

		Soklet soklet = Soklet.fromConfig(config);
		SokletStartupException startupFailure = Assertions.assertThrows(
				SokletStartupException.class, soklet::start);
		InternalShutdownResult result = startupFailure.getInternalShutdownResult();

		Assertions.assertInstanceOf(IllegalStateException.class,
				startupFailure.getCause());
		Assertions.assertEquals(NO_RESOURCE_METHODS,
				startupFailure.getCause().getMessage());
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				startupFailure.getInternalStartupDisposition());
		Assertions.assertEquals(InternalShutdownDisposition.NOT_STARTED,
				result.disposition());
		Assertions.assertSame(result,
				soklet.getDirectLifecycle().result().orElseThrow());
		for (InternalLifecycleComponentShutdownResult participant :
				result.participantResults()) {
			Assertions.assertEquals(
					InternalLifecycleComponentShutdownDisposition.NOT_STARTED,
					participant.disposition());
			Assertions.assertEquals(List.of(startupFailure.getCause()),
					participant.failures());
		}
		Assertions.assertDoesNotThrow(soklet::close);
	}

	@NonNull
	private static SokletConfig mixedTransportConfig(
			@NonNull LifecycleObserver lifecycleObserver) {
		return SokletConfig.withHttpServer(HttpServer.withPort(0).build())
				.sseServer(new LifecycleSseServer())
				.mcpServer(newMcpServer())
				.resourceMethodResolver(
						ResourceMethodResolver.fromClasses(Set.of(MixedTransportResource.class)))
				.lifecycleObserver(lifecycleObserver)
				.build();
	}

	@NonNull
	private static McpServer newMcpServer() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(
						McpImplementation.withNameAndVersion("test-server", "1.0").build())
				.build();

		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.build();
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> callTool(
			@NonNull String host, int port, @NonNull String path,
			@NonNull String toolName) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"residual-lifecycle\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":{}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + host + ":" + port + path))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", "2026-07-28")
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", toolName)
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.sendAsync(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void awaitMcpStatus(@NonNull McpServer mcpServer,
			@NonNull McpServerStatus expectedStatus) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			if (mcpServer.getDiagnostics().getStatus() == expectedStatus)
				return;
			Thread.sleep(10L);
		}
		Assertions.assertEquals(expectedStatus,
				mcpServer.getDiagnostics().getStatus());
	}

	public static final class MixedTransportResource {
		@GET("/http")
		public void http() {
			// No-op.
		}

		@SseEventSource("/events")
		@NonNull
		public SseHandshakeResult events() {
			return SseHandshakeResult.accept();
		}
	}

	private static final class FailingSseServer implements SseServer {
		@NonNull
		private final TransportIdentity identity = TransportIdentity.create();
		@NonNull
		private final RuntimeException failure = new IllegalStateException(
				"expected first SSE start failure");
		@NonNull
		private final AtomicInteger startAttempts = new AtomicInteger();

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull SseTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			TransportTerminationSignal terminationSignal =
					context.getTerminationSignal();
			AtomicBoolean terminationSignalled = new AtomicBoolean();
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					startAttempts.incrementAndGet();
					throw failure;
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					signalTermination();
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					signalTermination();
				}

				private void signalTermination() {
					if (terminationSignalled.compareAndSet(false, true))
						terminationSignal.signalTerminated();
				}
			};
		}

		@NonNull
		private RuntimeException failure() {
			return this.failure;
		}

		private int startAttempts() {
			return this.startAttempts.get();
		}

		@Override
		@NonNull
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			return Optional.empty();
		}
	}

	private static final class LifecycleSseServer implements SseServer {
		@NonNull
		private final TransportIdentity identity = TransportIdentity.create();
		@NonNull
		private final AtomicBoolean started = new AtomicBoolean();

		@Override
		@NonNull
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		@NonNull
		public TransportRuntime attach(
				@NonNull SseTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			TransportTerminationSignal terminationSignal =
					context.getTerminationSignal();
			AtomicBoolean terminationSignalled = new AtomicBoolean();
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					started.set(true);
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					stopAndSignal();
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					stopAndSignal();
				}

				private void stopAndSignal() {
					started.set(false);
					if (terminationSignalled.compareAndSet(false, true))
						terminationSignal.signalTerminated();
				}
			};
		}

		@Override
		@NonNull
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			return Optional.empty();
		}
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		@NonNull
		private final List<String> events;
		@Nullable
		private final RuntimeException didStartMcpFailure;
		@NonNull
		private final CountDownLatch readyObserved;
		@NonNull
		private final CountDownLatch terminalObserved;

		private RecordingLifecycleObserver(@NonNull List<String> events,
				@Nullable RuntimeException didStartMcpFailure) {
			this.events = events;
			this.didStartMcpFailure = didStartMcpFailure;
			this.readyObserved = new CountDownLatch(1);
			this.terminalObserved = new CountDownLatch(1);
		}

		private void awaitReady() throws InterruptedException {
			Assertions.assertTrue(this.readyObserved.await(5, TimeUnit.SECONDS),
					"The asynchronous ready transition was not observed.");
		}

		private void awaitTerminal() throws InterruptedException {
			Assertions.assertTrue(this.terminalObserved.await(5, TimeUnit.SECONDS),
					"The asynchronous terminal transition was not observed.");
		}

		@Override
		public void willStartSoklet(@NonNull Soklet soklet) {
			this.events.add("will-start-soklet");
		}

		@Override
		public void didStartSoklet(@NonNull Soklet soklet) {
			this.events.add("did-start-soklet");
			this.readyObserved.countDown();
		}

		@Override
		public void didFailToStartSoklet(@NonNull Soklet soklet,
				@NonNull Throwable throwable) {
			this.events.add("did-fail-start-soklet");
		}

		@Override
		public void willStopSoklet(@NonNull Soklet soklet) {
			this.events.add("will-stop-soklet");
		}

		@Override
		public void didStopSoklet(@NonNull Soklet soklet,
				@NonNull ShutdownResult result) {
			this.events.add("did-stop-soklet");
			this.terminalObserved.countDown();
		}

		@Override
		public void willStartHttpServer(@NonNull HttpServer httpServer) {
			this.events.add("will-start-http");
		}

		@Override
		public void didStartHttpServer(@NonNull HttpServer httpServer) {
			this.events.add("did-start-http");
		}

		@Override
		public void willStopHttpServer(@NonNull HttpServer httpServer) {
			this.events.add("will-stop-http");
		}

		@Override
		public void didStopHttpServer(@NonNull HttpServer httpServer,
				@NonNull ShutdownComponentResult result) {
			this.events.add("did-stop-http");
		}

		@Override
		public void willStartSseServer(@NonNull SseServer sseServer) {
			this.events.add("will-start-sse");
		}

		@Override
		public void didStartSseServer(@NonNull SseServer sseServer) {
			this.events.add("did-start-sse");
		}

		@Override
		public void didFailToStartSseServer(@NonNull SseServer sseServer,
				@NonNull Throwable throwable) {
			this.events.add("did-fail-start-sse");
		}

		@Override
		public void willStopSseServer(@NonNull SseServer sseServer) {
			this.events.add("will-stop-sse");
		}

		@Override
		public void didStopSseServer(@NonNull SseServer sseServer,
				@NonNull ShutdownComponentResult result) {
			this.events.add("did-stop-sse");
		}

		@Override
		public void willStartMcpServer(@NonNull McpServer mcpServer) {
			this.events.add("will-start-mcp");
		}

		@Override
		public void didStartMcpServer(@NonNull McpServer mcpServer) {
			this.events.add("did-start-mcp");

			if (this.didStartMcpFailure != null)
				throw this.didStartMcpFailure;
		}

		@Override
		public void didFailToStartMcpServer(@NonNull McpServer mcpServer,
				@NonNull Throwable throwable) {
			this.events.add("did-fail-start-mcp");
		}

		@Override
		public void willStopMcpServer(@NonNull McpServer mcpServer) {
			this.events.add("will-stop-mcp");
		}

		@Override
		public void didStopMcpServer(@NonNull McpServer mcpServer,
				@NonNull ShutdownComponentResult result) {
			this.events.add("did-stop-mcp-" + result.getShutdownComponentDisposition().name());
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Keep expected MCP configuration diagnostics out of test output.
		}
	}
}
