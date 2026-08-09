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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import com.soklet.internal.microhttp.EventLoop;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Focused real-listener coverage for shared MCP metric-event delivery.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpMetricsEventDeliveryPublicRuntimeTests {
	private static final String HOST = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";

	@Test
	public void directLifecycleEmitsExactStartedStoppedGenerationsAndNoOps() {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpServer server = server(0, "/mcp/direct-generations");
		Soklet owner = soklet(server, collector,
				LifecycleObserver.defaultInstance());

		try {
			server.stop();
			Assertions.assertTrue(collector.serverLifecycleEvents().isEmpty());

			server.start();
			server.start();
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.ServerStarted()),
					collector.serverLifecycleEvents());

			server.stop();
			server.stop();
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.ServerStarted(),
					new McpMetricsEvent.ServerStopped(
							McpShutdownOutcome.CLEAN)),
					collector.serverLifecycleEvents());

			server.start();
			server.stop();
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.ServerStarted(),
					new McpMetricsEvent.ServerStopped(
							McpShutdownOutcome.CLEAN),
					new McpMetricsEvent.ServerStarted(),
					new McpMetricsEvent.ServerStopped(
							McpShutdownOutcome.CLEAN)),
					collector.serverLifecycleEvents());
		} finally {
			server.stop();
			owner.stop();
		}
	}

	@Test
	public void consumingStopGenerationQueuesStoppedBeforeDirectRestart() {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpServer server = server(0, "/mcp/consume-stop-generation");
		Soklet owner = soklet(server, collector,
				LifecycleObserver.defaultInstance());
		DefaultMcpServer defaultServer = (DefaultMcpServer) server;

		try {
			server.start();
			McpServerStopResult stopResult = defaultServer.stopForSoklet();
			Assertions.assertTrue(stopResult.listenerGenerationStopped());
			Assertions.assertEquals(McpShutdownOutcome.CLEAN,
					stopResult.shutdownOutcome());
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.ServerStarted(),
					new McpMetricsEvent.ServerStopped(
							McpShutdownOutcome.CLEAN)),
					collector.serverLifecycleEvents(),
					"Consuming a generation must queue its stop before the lifecycle lock is released.");

			server.start();
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.ServerStarted(),
					new McpMetricsEvent.ServerStopped(
							McpShutdownOutcome.CLEAN),
					new McpMetricsEvent.ServerStarted()),
					collector.serverLifecycleEvents(),
					"A direct restart must never overtake the consumed generation's stop.");
		} finally {
			server.stop();
			owner.stop();
		}
	}

	@Test
	public void failedListenerStartRemovesStagedStartedWithoutPhantoms() {
		RecordingMetricsCollector occupyingCollector =
				new RecordingMetricsCollector();
		McpServer occupyingServer = server(0, "/mcp/occupying-listener");
		Soklet occupyingOwner = soklet(occupyingServer, occupyingCollector,
				LifecycleObserver.defaultInstance());
		McpServer failedServer = null;
		Soklet failedOwner = null;

		try {
			occupyingServer.start();
			int occupiedPort = occupyingServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			RecordingMetricsCollector failedCollector =
					new RecordingMetricsCollector();
			failedServer = server(occupiedPort, "/mcp/failed-listener");
			failedOwner = soklet(failedServer, failedCollector,
					LifecycleObserver.defaultInstance());

			McpServer finalFailedServer = failedServer;
			Assertions.assertThrows(UncheckedIOException.class,
					finalFailedServer::start);
			Assertions.assertFalse(failedServer.isStarted());
			Assertions.assertTrue(failedCollector.serverLifecycleEvents().isEmpty(),
					"A failed listener start must remove its staged ServerStarted event.");

			failedServer.stop();
			Assertions.assertTrue(failedCollector.serverLifecycleEvents().isEmpty(),
					"Failed-start cleanup must not invent a ServerStopped event.");
		} finally {
			if (failedServer != null)
				failedServer.stop();
			if (failedOwner != null)
				failedOwner.stop();
			occupyingServer.stop();
			occupyingOwner.stop();
		}
	}

	@Test
	public void managedStartRollbackEmitsStartedThenStoppedAfterLifecycleUnlock()
			throws Exception {
		RuntimeException expectedFailure = new RuntimeException(
				"expected managed start rollback");
		ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
		AtomicReference<McpServer> serverReference = new AtomicReference<>();
		AtomicReference<Soklet> sokletReference = new AtomicReference<>();
		LifecycleLockProbingCollector collector =
				new LifecycleLockProbingCollector(probeExecutor,
						serverReference, sokletReference);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didStartMcpServer(@NonNull McpServer server) {
				throw expectedFailure;
			}
		};
		McpServer server = server(0, "/mcp/managed-rollback");
		Soklet soklet = soklet(server, collector, observer);
		serverReference.set(server);
		sokletReference.set(soklet);

		try {
			RuntimeException actualFailure = Assertions.assertThrows(
					RuntimeException.class, soklet::start);
			Assertions.assertSame(expectedFailure, actualFailure);
			Assertions.assertFalse(server.isStarted());
			Assertions.assertFalse(soklet.isStarted());
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.ServerStarted(),
					new McpMetricsEvent.ServerStopped(
							McpShutdownOutcome.CLEAN)),
					collector.serverLifecycleEvents());
			Assertions.assertNull(collector.probeFailure(),
					"Server lifecycle metrics ran under an MCP-server or Soklet lifecycle lock.");
			Assertions.assertEquals(List.of(
					List.of(false, false), List.of(false, false)),
					collector.observedStartedStates());
		} finally {
			soklet.stop();
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void unexpectedRestartOrdersNormalizedStopBeforeNextStarted()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpServer server = server(0, "/mcp/unexpected-restart");
		Soklet owner = soklet(server, collector,
				LifecycleObserver.defaultInstance());
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			server.start();
			terminateUnexpectedly(eventLoop(bridge));
			Assertions.assertFalse(server.isStarted());
			Assertions.assertTrue(bridge.getRuntimeState().stopRequired());

			server.start();
			Assertions.assertTrue(server.isStarted());
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.ServerStarted(),
					new McpMetricsEvent.ServerStopped(
							McpShutdownOutcome.CLEAN),
					new McpMetricsEvent.ServerStarted()),
					collector.serverLifecycleEvents(),
					"The old generation's normalized stop must precede the new start.");

			server.stop();
			Assertions.assertEquals(List.of(
					new McpMetricsEvent.ServerStarted(),
					new McpMetricsEvent.ServerStopped(
							McpShutdownOutcome.CLEAN),
					new McpMetricsEvent.ServerStarted(),
					new McpMetricsEvent.ServerStopped(
							McpShutdownOutcome.CLEAN)),
					collector.serverLifecycleEvents());
		} finally {
			server.stop();
			owner.stop();
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void serverStartedPrecedesImmediatelyAdmittedReentrantRequestInSerializedFifo()
			throws Exception {
		ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
		AtomicReference<McpServer> serverReference = new AtomicReference<>();
		AtomicReference<Soklet> sokletReference = new AtomicReference<>();
		ReentrantStartCollector collector = new ReentrantStartCollector(
				probeExecutor, serverReference, sokletReference,
				"/mcp/reentrant-start");
		McpServer server = server(0, "/mcp/reentrant-start");
		Soklet soklet = soklet(server, collector,
				LifecycleObserver.defaultInstance());
		serverReference.set(server);
		sokletReference.set(soklet);

		try {
			soklet.start();
			collector.awaitRequestFinished();
			Assertions.assertNull(collector.failure(),
					"The reentrant request or lifecycle-lock probe failed.");
			HttpResponse<String> response = collector.response();
			Assertions.assertNotNull(response);
			assertSuccessfulDiscovery(response, "reentrant-start");
			Assertions.assertEquals(1, collector.maximumConcurrentCallbacks(),
					"One shared FIFO must serialize nested metric callbacks.");
			Assertions.assertEquals(List.of(
					McpMetricsEvent.ServerStarted.class,
					McpMetricsEvent.RequestStarted.class,
					McpMetricsEvent.RequestFinished.class),
					collector.events().stream().map(Object::getClass).toList(),
					"The successful listener generation must be visible before an immediately admitted request.");
		} finally {
			soklet.stop();
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void collectorFailuresRetainRequestContextAndDoNotStallFifo()
			throws Exception {
		RuntimeException serverFailure = new RuntimeException(
				"expected server metric failure");
		RuntimeException requestFailure = new RuntimeException(
				"expected request metric failure");
		FailingMetricsCollector collector = new FailingMetricsCollector(
				serverFailure, requestFailure);
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = server(0, "/mcp/failure-context");
		Soklet soklet = soklet(server, collector, observer);

		try {
			soklet.start();
			Assertions.assertTrue(server.isStarted(),
					"A ServerStarted collector failure must not roll back the listener.");
			HttpResponse<String> response = sendDiscovery(
					server.getDiagnostics().getBoundAddress().orElseThrow().getPort(),
					"/mcp/failure-context", "failure-context");
			observer.awaitRequestFinished();
			collector.awaitRequestFinished();
			assertSuccessfulDiscovery(response, "failure-context");
			Assertions.assertEquals(List.of(
					McpMetricsEvent.ServerStarted.class,
					McpMetricsEvent.RequestStarted.class,
					McpMetricsEvent.RequestFinished.class),
					collector.events().stream().map(Object::getClass).toList(),
					"A failed delivery must not stall or discard later FIFO entries.");

			List<LogEvent> failures = observer.logEvents().stream()
					.filter(event -> event.getLogEventType()
							== LogEventType.METRICS_COLLECTOR_FAILED)
					.toList();
			Assertions.assertEquals(2, failures.size(), failures.toString());
			LogEvent serverLog = failures.stream()
					.filter(event -> event.getThrowable().orElseThrow()
							== serverFailure)
					.findFirst().orElseThrow();
			LogEvent requestLog = failures.stream()
					.filter(event -> event.getThrowable().orElseThrow()
							== requestFailure)
					.findFirst().orElseThrow();
			Assertions.assertTrue(serverLog.getRequest().isEmpty());
			Assertions.assertSame(observer.requestContext().getRequest(),
					requestLog.getRequest().orElseThrow(),
					"A queued admitted event must retain its exact originating request.");
			for (LogEvent failure : failures) {
				Assertions.assertTrue(failure.getResourceMethod().isEmpty());
				Assertions.assertTrue(failure.getMarshaledResponse().isEmpty());
			}
		} finally {
			soklet.stop();
		}
	}

	@NonNull
	private static McpServer server(int port, @NonNull String... paths) {
		List<McpEndpoint> endpoints = java.util.Arrays.stream(paths)
				.map(path -> McpEndpoint.withPath(path)
						.serverInformation(McpImplementation.withNameAndVersion(
								"metric-delivery-test", "3.6.0-SNAPSHOT")
								.build())
						.build())
				.toList();
		return McpServer.withPort(port)
				.host(HOST)
				.handlerResolver(McpHandlerResolver.fromEndpoints(endpoints))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST))
				.build();
	}

	@NonNull
	private static Soklet soklet(@NonNull McpServer server,
			@NonNull MetricsCollector collector,
			@NonNull LifecycleObserver observer) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector)
				.lifecycleObserver(observer)
				.build());
	}

	@NonNull
	private static HttpResponse<String> sendDiscovery(int port,
			@NonNull String path, @NonNull String id) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + HOST + ":" + port + path))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "server/discover")
				.POST(HttpRequest.BodyPublishers.ofString(
						body, StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.send(request, HttpResponse.BodyHandlers.ofString(
						StandardCharsets.UTF_8));
	}

	private static void assertSuccessfulDiscovery(
			@NonNull HttpResponse<String> response, @NonNull String id) {
		Assertions.assertEquals(200, response.statusCode(), response.body());
		Assertions.assertEquals(JSON_MEDIA_TYPE,
				response.headers().firstValue("Content-Type").orElseThrow());
		Assertions.assertTrue(response.body().contains(
				"\"id\":\"" + id + "\""), response.body());
	}

	@NonNull
	private static McpServerRuntimeBridge runtimeBridge(
			@NonNull McpServer server) throws Exception {
		Field bridgeField = DefaultMcpServer.class.getDeclaredField(
				"runtimeBridge");
		bridgeField.setAccessible(true);
		return (McpServerRuntimeBridge) bridgeField.get(requireNonNull(server));
	}

	@NonNull
	private static EventLoop eventLoop(@NonNull McpServerRuntimeBridge bridge)
			throws Exception {
		Field runtimeField = McpServerRuntimeBridge.class.getDeclaredField(
				"runtime");
		runtimeField.setAccessible(true);
		Object runtime = runtimeField.get(requireNonNull(bridge));
		Field eventLoopField = runtime.getClass().getDeclaredField("eventLoop");
		eventLoopField.setAccessible(true);
		return (EventLoop) eventLoopField.get(runtime);
	}

	private static void terminateUnexpectedly(@NonNull EventLoop eventLoop)
			throws Exception {
		Field selectorField = EventLoop.class.getDeclaredField("selector");
		selectorField.setAccessible(true);
		((Selector) selectorField.get(requireNonNull(eventLoop))).close();
		Assertions.assertTrue(eventLoop.join(Duration.ofSeconds(2)),
				"The unexpectedly terminated MCP event loop did not exit.");
	}

	private static class RecordingMetricsCollector implements MetricsCollector {
		@NonNull
		private final List<@NonNull McpMetricsEvent> events =
				new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(requireNonNull(event));
		}

		@NonNull
		protected List<@NonNull McpMetricsEvent> events() {
			return List.copyOf(this.events);
		}

		@NonNull
		protected List<@NonNull McpMetricsEvent> serverLifecycleEvents() {
			return this.events.stream()
					.filter(event -> event instanceof McpMetricsEvent.ServerStarted
							|| event instanceof McpMetricsEvent.ServerStopped)
					.toList();
		}
	}

	private static final class LifecycleLockProbingCollector
			extends RecordingMetricsCollector {
		@NonNull
		private final ExecutorService probeExecutor;
		@NonNull
		private final AtomicReference<McpServer> serverReference;
		@NonNull
		private final AtomicReference<Soklet> sokletReference;
		@NonNull
		private final List<@NonNull List<@NonNull Boolean>> observedStartedStates =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final AtomicReference<Throwable> probeFailure =
				new AtomicReference<>();

		private LifecycleLockProbingCollector(@NonNull ExecutorService probeExecutor,
				@NonNull AtomicReference<McpServer> serverReference,
				@NonNull AtomicReference<Soklet> sokletReference) {
			this.probeExecutor = requireNonNull(probeExecutor);
			this.serverReference = requireNonNull(serverReference);
			this.sokletReference = requireNonNull(sokletReference);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			super.didRecordMcpMetricsEvent(event);
			if (!(event instanceof McpMetricsEvent.ServerStarted)
					&& !(event instanceof McpMetricsEvent.ServerStopped))
				return;
			try {
				Future<List<Boolean>> probe = this.probeExecutor.submit(() ->
						List.of(this.serverReference.get().isStarted(),
								this.sokletReference.get().isStarted()));
				this.observedStartedStates.add(probe.get(2, TimeUnit.SECONDS));
			} catch (Throwable throwable) {
				this.probeFailure.compareAndSet(null, throwable);
			}
		}

		private Throwable probeFailure() {
			return this.probeFailure.get();
		}

		@NonNull
		private List<@NonNull List<@NonNull Boolean>> observedStartedStates() {
			return List.copyOf(this.observedStartedStates);
		}
	}

	private static final class ReentrantStartCollector
			extends RecordingMetricsCollector {
		@NonNull
		private final ExecutorService probeExecutor;
		@NonNull
		private final AtomicReference<McpServer> serverReference;
		@NonNull
		private final AtomicReference<Soklet> sokletReference;
		@NonNull
		private final String endpointPath;
		@NonNull
		private final AtomicBoolean invoked = new AtomicBoolean();
		@NonNull
		private final AtomicInteger activeCallbacks = new AtomicInteger();
		@NonNull
		private final AtomicInteger maximumConcurrentCallbacks =
				new AtomicInteger();
		@NonNull
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		@NonNull
		private final AtomicReference<HttpResponse<String>> response =
				new AtomicReference<>();
		@NonNull
		private final CountDownLatch requestFinished = new CountDownLatch(1);

		private ReentrantStartCollector(@NonNull ExecutorService probeExecutor,
				@NonNull AtomicReference<McpServer> serverReference,
				@NonNull AtomicReference<Soklet> sokletReference,
				@NonNull String endpointPath) {
			this.probeExecutor = requireNonNull(probeExecutor);
			this.serverReference = requireNonNull(serverReference);
			this.sokletReference = requireNonNull(sokletReference);
			this.endpointPath = requireNonNull(endpointPath);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			int active = this.activeCallbacks.incrementAndGet();
			this.maximumConcurrentCallbacks.accumulateAndGet(active, Math::max);
			try {
				super.didRecordMcpMetricsEvent(event);
				if (event instanceof McpMetricsEvent.ServerStarted
						&& this.invoked.compareAndSet(false, true)) {
					try {
						Future<List<Boolean>> probe = this.probeExecutor.submit(() ->
								List.of(this.serverReference.get().isStarted(),
										this.sokletReference.get().isStarted()));
						Assertions.assertEquals(List.of(true, true),
								probe.get(2, TimeUnit.SECONDS));
						int port = this.serverReference.get().getDiagnostics()
								.getBoundAddress().orElseThrow().getPort();
						this.response.set(sendDiscovery(port, this.endpointPath,
								"reentrant-start"));
					} catch (Throwable throwable) {
						this.failure.compareAndSet(null, throwable);
					}
				}
				if (event instanceof McpMetricsEvent.RequestFinished)
					this.requestFinished.countDown();
			} finally {
				this.activeCallbacks.decrementAndGet();
			}
		}

		private void awaitRequestFinished() throws InterruptedException {
			Assertions.assertTrue(this.requestFinished.await(5, TimeUnit.SECONDS),
					"The reentrant request's terminal metric did not arrive.");
		}

		private Throwable failure() {
			return this.failure.get();
		}

		private HttpResponse<String> response() {
			return this.response.get();
		}

		private int maximumConcurrentCallbacks() {
			return this.maximumConcurrentCallbacks.get();
		}
	}

	private static final class FailingMetricsCollector
			extends RecordingMetricsCollector {
		@NonNull
		private final RuntimeException serverFailure;
		@NonNull
		private final RuntimeException requestFailure;
		@NonNull
		private final CountDownLatch requestFinished = new CountDownLatch(1);

		private FailingMetricsCollector(@NonNull RuntimeException serverFailure,
				@NonNull RuntimeException requestFailure) {
			this.serverFailure = requireNonNull(serverFailure);
			this.requestFailure = requireNonNull(requestFailure);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			super.didRecordMcpMetricsEvent(event);
			if (event instanceof McpMetricsEvent.RequestFinished)
				this.requestFinished.countDown();
			if (event instanceof McpMetricsEvent.ServerStarted)
				throw this.serverFailure;
			if (event instanceof McpMetricsEvent.RequestStarted)
				throw this.requestFailure;
		}

		private void awaitRequestFinished() throws InterruptedException {
			Assertions.assertTrue(this.requestFinished.await(5, TimeUnit.SECONDS),
					"A failed request-start delivery stalled the terminal FIFO entry.");
		}
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		@NonNull
		private final AtomicReference<McpRequestContext> requestContext =
				new AtomicReference<>();
		@NonNull
		private final CountDownLatch requestFinished = new CountDownLatch(1);
		@NonNull
		private final List<@NonNull LogEvent> logEvents =
				new CopyOnWriteArrayList<>();

		@Override
		public void didStartMcpRequestHandling(
				@NonNull McpRequestContext context) {
			this.requestContext.set(requireNonNull(context));
		}

		@Override
		public void didFinishMcpRequestHandling(
				@NonNull McpRequestContext context,
				@NonNull McpRequestOutcome outcome,
				McpJsonRpcError error, @NonNull Duration duration,
				@NonNull List<@NonNull Throwable> throwables) {
			Assertions.assertSame(this.requestContext.get(), context);
			this.requestFinished.countDown();
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			this.logEvents.add(requireNonNull(logEvent));
		}

		private void awaitRequestFinished() throws InterruptedException {
			Assertions.assertTrue(this.requestFinished.await(5, TimeUnit.SECONDS),
					"The admitted request lifecycle did not finish.");
		}

		@NonNull
		private McpRequestContext requestContext() {
			return requireNonNull(this.requestContext.get());
		}

		@NonNull
		private List<@NonNull LogEvent> logEvents() {
			return List.copyOf(this.logEvents);
		}
	}
}
