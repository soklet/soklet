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

import java.lang.reflect.Field;
import java.net.Socket;
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
	public void freshOwnersEmitExactStartedStoppedGenerationsAndShutdownNoOps()
			throws InterruptedException {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpServer neverStartedServer = server(0, "/mcp/never-started");
		Soklet neverStartedOwner = soklet(neverStartedServer, collector,
				LifecycleObserver.defaultInstance());
		neverStartedOwner.stop();
		neverStartedOwner.stop();
		Assertions.assertTrue(collector.serverLifecycleEvents().isEmpty());

		McpServer firstServer = server(0, "/mcp/first-generation");
		Soklet firstOwner = soklet(firstServer, collector,
				LifecycleObserver.defaultInstance());
		try {
			firstOwner.start();
			collector.awaitEventCount(1);
			Assertions.assertEquals(List.of(
					McpMetricsEvent.serverStarted()),
					collector.serverLifecycleEvents());
		} finally {
			firstOwner.stop();
			firstOwner.stop();
		}
		collector.awaitEventCount(2);
		Assertions.assertEquals(List.of(
				McpMetricsEvent.serverStarted(),
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN)),
				collector.serverLifecycleEvents());

		McpServer secondServer = server(0, "/mcp/second-generation");
		Soklet secondOwner = soklet(secondServer, collector,
				LifecycleObserver.defaultInstance());
		try {
			secondOwner.start();
			collector.awaitEventCount(3);
			Assertions.assertEquals(List.of(
					McpMetricsEvent.serverStarted(),
					McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN),
					McpMetricsEvent.serverStarted()),
					collector.serverLifecycleEvents());
		} finally {
			secondOwner.stop();
			secondOwner.stop();
		}
		collector.awaitEventCount(4);
		Assertions.assertEquals(List.of(
				McpMetricsEvent.serverStarted(),
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN),
				McpMetricsEvent.serverStarted(),
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN)),
				collector.serverLifecycleEvents());
	}

	@Test
	public void adapterStopRequestQueuesStoppedBeforeFreshOwnerStart()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpServer firstServer = server(0, "/mcp/consume-stop-generation");
		Soklet firstOwner = soklet(firstServer, collector,
				LifecycleObserver.defaultInstance());
		McpTransportLifecycleAdapter firstAdapter =
				lifecycleAdapter(firstServer);

		try {
			firstOwner.start();
			collector.awaitEventCount(1);
			McpTransportLifecycleAdapter.Generation generation =
					(McpTransportLifecycleAdapter.Generation)
							firstAdapter.currentGeneration();
			Assertions.assertSame(generation, firstAdapter.requestStop());
			firstOwner.stop();
			collector.awaitEventCount(2);
			Assertions.assertSame(firstOwner.getDirectLifecycle().result()
					.orElseThrow(), firstAdapter.result(generation).orElseThrow());
			Assertions.assertEquals(List.of(
					McpMetricsEvent.serverStarted(),
					McpMetricsEvent.serverStopped(
							McpShutdownOutcome.CLEAN)),
					collector.serverLifecycleEvents(),
					"The adapter stop request must publish one owner-normalized stop before a fresh owner starts.");
		} finally {
			firstOwner.stop();
			firstOwner.stop();
		}

		McpServer secondServer = server(0, "/mcp/after-consumed-generation");
		Soklet secondOwner = soklet(secondServer, collector,
				LifecycleObserver.defaultInstance());
		try {
			secondOwner.start();
			collector.awaitEventCount(3);
			Assertions.assertEquals(List.of(
					McpMetricsEvent.serverStarted(),
					McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN),
					McpMetricsEvent.serverStarted()),
					collector.serverLifecycleEvents(),
					"A fresh owner must never overtake the consumed generation's stop.");
		} finally {
			secondOwner.stop();
			secondOwner.stop();
		}
		collector.awaitEventCount(4);
		Assertions.assertEquals(List.of(
				McpMetricsEvent.serverStarted(),
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN),
				McpMetricsEvent.serverStarted(),
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN)),
				collector.serverLifecycleEvents(),
				"Repeated owner shutdown must not redeliver either generation's stop.");
	}

	@Test
	public void failedListenerStartRemovesStagedStartedWithoutPhantoms()
			throws InterruptedException {
		RecordingMetricsCollector occupyingCollector =
				new RecordingMetricsCollector();
		McpServer occupyingServer = server(0, "/mcp/occupying-listener");
		Soklet occupyingOwner = soklet(occupyingServer, occupyingCollector,
				LifecycleObserver.defaultInstance());

		try {
			occupyingOwner.start();
			int occupiedPort = occupyingServer.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			RecordingMetricsCollector failedCollector =
					new RecordingMetricsCollector();
			McpServer failedServer = server(occupiedPort, "/mcp/failed-listener");
			Soklet failedOwner = soklet(failedServer, failedCollector,
					LifecycleObserver.defaultInstance());

			try {
				SokletStartupException failure = Assertions.assertThrows(
						SokletStartupException.class, failedOwner::start);
				Assertions.assertInstanceOf(java.net.BindException.class,
						failure.getCause());
				Assertions.assertEquals(McpServerStatus.STOPPED,
						failedServer.getDiagnostics().getStatus());
				Assertions.assertFalse(failedOwner.isStarted());
				Assertions.assertSame(failure.getInternalShutdownResult(),
						failedOwner.getDirectLifecycle().result().orElseThrow());
				Assertions.assertEquals(InternalStartupDisposition.FAILED,
						failure.getInternalStartupDisposition());
				Assertions.assertTrue(
						failedCollector.serverLifecycleEvents().isEmpty(),
						"A failed listener start must remove its staged ServerStarted event.");

				failedOwner.stop();
				failedOwner.stop();
				Assertions.assertTrue(
						failedCollector.serverLifecycleEvents().isEmpty(),
						"Failed-start cleanup must not invent a ServerStopped event.");
			} finally {
				failedOwner.stop();
			}

			McpServer proofServer = server(0, "/mcp/after-failed-listener");
			Soklet proofOwner = soklet(proofServer, failedCollector,
					LifecycleObserver.defaultInstance());
			try {
				proofOwner.start();
				failedCollector.awaitEventCount(1);
			} finally {
				proofOwner.stop();
				proofOwner.stop();
			}
			failedCollector.awaitEventCount(2);
			Assertions.assertEquals(List.of(
					McpMetricsEvent.serverStarted(),
					McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN)),
					failedCollector.serverLifecycleEvents(),
					"A fresh delivery barrier must not reveal a phantom from the failed generation.");
		} finally {
			occupyingOwner.stop();
			occupyingOwner.stop();
		}
	}

	@Test
	public void lifecycleObserverFailureDoesNotControlStartedStoppedDelivery()
			throws Exception {
		RuntimeException expectedFailure = new RuntimeException(
				"expected isolated lifecycle-observer failure");
		CountDownLatch observerInvoked = new CountDownLatch(1);
		ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
		AtomicReference<McpServer> serverReference = new AtomicReference<>();
		AtomicReference<Soklet> sokletReference = new AtomicReference<>();
		LifecycleLockProbingCollector collector =
				new LifecycleLockProbingCollector(probeExecutor,
						serverReference, sokletReference);
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didStartMcpServer(@NonNull McpServer server) {
				observerInvoked.countDown();
				throw expectedFailure;
			}
		};
		McpServer server = server(0, "/mcp/observer-failure");
		Soklet soklet = soklet(server, collector, observer);
		serverReference.set(server);
		sokletReference.set(soklet);

		try {
			soklet.start();
			collector.awaitEventCount(1);
			collector.awaitServerStartedObserved();
			Assertions.assertTrue(observerInvoked.await(5, TimeUnit.SECONDS),
					"The isolated lifecycle observer was not invoked.");
			Assertions.assertEquals(McpServerStatus.STARTED,
					server.getDiagnostics().getStatus());
			Assertions.assertTrue(soklet.isStarted());
			Assertions.assertEquals(List.of(
					McpMetricsEvent.serverStarted()),
					collector.serverLifecycleEvents());
		} finally {
			try {
				soklet.stop();
				collector.awaitEventCount(2);
				collector.awaitLifecycleEventsObserved();
			} finally {
				probeExecutor.shutdownNow();
				Assertions.assertTrue(probeExecutor.awaitTermination(
						5, TimeUnit.SECONDS));
			}
		}
		Assertions.assertEquals(List.of(
				McpMetricsEvent.serverStarted(),
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN)),
				collector.serverLifecycleEvents());
		Assertions.assertNull(collector.probeFailure(),
				"Server lifecycle metrics ran under an MCP-server or Soklet lifecycle lock.");
		Assertions.assertEquals(List.of(
				List.of(true, true), List.of(false, false)),
				collector.observedStartedStates());
	}

	@Test
	public void unexpectedTerminationOrdersNormalizedStopBeforeFreshOwnerStart()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		McpServer firstServer = server(0, "/mcp/unexpected-first");
		Soklet firstOwner = soklet(firstServer, collector,
				LifecycleObserver.defaultInstance());
		McpServerRuntimeBridge firstBridge = runtimeBridge(firstServer);
		McpTransportLifecycleAdapter firstLifecycleAdapter =
				lifecycleAdapter(firstServer);

		try {
			firstOwner.start();
			collector.awaitEventCount(1);
			McpTransportLifecycleAdapter.Generation terminatedGeneration =
					(McpTransportLifecycleAdapter.Generation)
							firstLifecycleAdapter.currentGeneration();
			terminateUnexpectedly(eventLoop(firstBridge));
			Assertions.assertNotEquals(McpServerStatus.STARTED,
					firstServer.getDiagnostics().getStatus());
			Assertions.assertTrue(terminatedGeneration.shutdownRequested());

			SokletTerminatedUnexpectedlyException failure =
					Assertions.assertThrows(
							SokletTerminatedUnexpectedlyException.class,
							firstOwner::stop);
			firstLifecycleAdapter.awaitStop(terminatedGeneration);
			Assertions.assertSame(failure.getInternalShutdownResult(),
					firstLifecycleAdapter.result(terminatedGeneration)
							.orElseThrow());
			Assertions.assertTrue(firstLifecycleAdapter
					.result(terminatedGeneration)
					.orElseThrow().isComplete(),
					"The exact unexpectedly terminated generation must be proven before a fresh owner starts.");
			collector.awaitEventCount(3);

			McpServer secondServer = server(0, "/mcp/unexpected-second");
			Soklet secondOwner = soklet(secondServer, collector,
					LifecycleObserver.defaultInstance());
			try {
				secondOwner.start();
				collector.awaitEventCount(4);
				Assertions.assertEquals(McpServerStatus.STARTED,
						secondServer.getDiagnostics().getStatus());
				Assertions.assertEquals(List.of(
						McpMetricsEvent.serverStarted(),
						McpMetricsEvent.transportFailure(
								MetricsCollector.TransportFailureReason
										.EVENT_LOOP_TERMINATED),
						McpMetricsEvent.serverStopped(
								McpShutdownOutcome.CLEAN),
						McpMetricsEvent.serverStarted()),
						collector.events(),
						"A fresh owner must return only after the fatal transport event, old stop, and new start are delivered in generation order.");
			} finally {
				secondOwner.stop();
				secondOwner.stop();
			}
			collector.awaitEventCount(5);
			Assertions.assertEquals(List.of(
					McpMetricsEvent.serverStarted(),
					McpMetricsEvent.transportFailure(
							MetricsCollector.TransportFailureReason
									.EVENT_LOOP_TERMINATED),
					McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN),
					McpMetricsEvent.serverStarted(),
					McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN)),
					collector.events());
		} finally {
			stopOwnerAllowingUnexpectedTermination(firstOwner);
			if (firstBridge.getRuntimeState().stopRequired())
				firstBridge.stop();
		}
	}

	@Test
	public void connectionAcceptedDeliveryIsAsynchronousSerializedAndReentrant()
			throws Exception {
		AtomicReference<McpServer> serverReference = new AtomicReference<>();
		ReentrantConnectionCollector collector =
				new ReentrantConnectionCollector(serverReference,
						"/mcp/reentrant-connection");
		McpServer server = server(0, "/mcp/reentrant-connection");
		Soklet soklet = soklet(server, collector,
				LifecycleObserver.defaultInstance());
		serverReference.set(server);

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			try (Socket ignored = new Socket(HOST, port)) {
				collector.awaitRequestFinished();
			}

			Assertions.assertNull(collector.failure(),
					"The asynchronously reentrant connection callback failed.");
			assertSuccessfulDiscovery(collector.response(),
					"reentrant-connection");
			Assertions.assertTrue(collector.callbackThreadWasDaemon());
			Assertions.assertTrue(collector.callbackThreadName()
					.startsWith("soklet-mcp-metrics-"),
					collector.callbackThreadName());
			Assertions.assertEquals(1, collector.maximumConcurrentCallbacks(),
					"Connection and request events must share serialized callback delivery.");
			Assertions.assertEquals(List.of(
					McpMetricsEvent.ServerStarted.class,
					McpMetricsEvent.ConnectionAccepted.class,
					McpMetricsEvent.ConnectionAccepted.class,
					McpMetricsEvent.RequestAccepted.class,
					McpMetricsEvent.RequestStarted.class,
					McpMetricsEvent.RequestFinished.class),
					collector.events().stream().map(Object::getClass).toList());
		} finally {
			soklet.stop();
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
					McpMetricsEvent.ConnectionAccepted.class,
					McpMetricsEvent.RequestAccepted.class,
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
		RuntimeException connectionFailure = new RuntimeException(
				"expected connection metric failure");
		FailingMetricsCollector collector = new FailingMetricsCollector(
				serverFailure, connectionFailure, requestFailure);
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = server(0, "/mcp/failure-context");
		Soklet soklet = soklet(server, collector, observer);

		try {
			soklet.start();
			Assertions.assertEquals(McpServerStatus.STARTED,
					server.getDiagnostics().getStatus(),
					"A ServerStarted collector failure must not roll back the listener.");
			HttpResponse<String> response = sendDiscovery(
					server.getDiagnostics().getBoundAddress().orElseThrow().getPort(),
					"/mcp/failure-context", "failure-context");
			observer.awaitRequestFinished();
			collector.awaitRequestFinished();
			assertSuccessfulDiscovery(response, "failure-context");
			Assertions.assertEquals(List.of(
					McpMetricsEvent.ServerStarted.class,
					McpMetricsEvent.ConnectionAccepted.class,
					McpMetricsEvent.RequestAccepted.class,
					McpMetricsEvent.RequestStarted.class,
					McpMetricsEvent.RequestFinished.class),
					collector.events().stream().map(Object::getClass).toList(),
					"A failed delivery must not stall or discard later FIFO entries.");

			List<LogEvent> failures = observer.logEvents().stream()
					.filter(event -> event.getLogEventType()
							== LogEventType.METRICS_COLLECTOR_FAILED)
					.toList();
			Assertions.assertEquals(3, failures.size(), failures.toString());
			LogEvent serverLog = failures.stream()
					.filter(event -> event.getThrowable().orElseThrow()
							== serverFailure)
					.findFirst().orElseThrow();
			LogEvent requestLog = failures.stream()
					.filter(event -> event.getThrowable().orElseThrow()
							== requestFailure)
					.findFirst().orElseThrow();
			LogEvent connectionLog = failures.stream()
					.filter(event -> event.getThrowable().orElseThrow()
							== connectionFailure)
					.findFirst().orElseThrow();
			Assertions.assertTrue(serverLog.getRequest().isEmpty());
			Assertions.assertTrue(connectionLog.getRequest().isEmpty(),
					"A transport event failure must remain request-free.");
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
								"metric-delivery-test", "4.0.0-SNAPSHOT")
								.build())
						.build())
				.toList();
		return McpServer.withPort(port)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(endpoints))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
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
	private static McpTransportLifecycleAdapter lifecycleAdapter(
			@NonNull McpServer server) throws Exception {
		Field adapterField = DefaultMcpServer.class.getDeclaredField(
				"lifecycleAdapter");
		adapterField.setAccessible(true);
		return (McpTransportLifecycleAdapter) adapterField.get(
				requireNonNull(server));
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

	private static void stopOwnerAllowingUnexpectedTermination(
			@NonNull Soklet owner) {
		try {
			owner.stop();
		} catch (SokletTerminatedUnexpectedlyException expected) {
			// Repeated shutdown replays the already-asserted terminal evidence.
		}
	}

	private static class RecordingMetricsCollector implements MetricsCollector {
		@NonNull
		private final List<@NonNull McpMetricsEvent> events =
				new CopyOnWriteArrayList<>();
		@NonNull
		private final Object eventMonitor = new Object();

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			this.events.add(requireNonNull(event));
			synchronized (this.eventMonitor) {
				this.eventMonitor.notifyAll();
			}
		}

		protected void awaitEventCount(int expectedCount)
				throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			synchronized (this.eventMonitor) {
				while (this.events.size() < expectedCount) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0)
						break;
					TimeUnit.NANOSECONDS.timedWait(this.eventMonitor, remaining);
				}
			}
			Assertions.assertTrue(this.events.size() >= expectedCount,
					"Expected at least " + expectedCount
							+ " metric events but observed " + this.events);
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
		@NonNull
		private final CountDownLatch lifecycleEventsObserved =
				new CountDownLatch(2);
		@NonNull
		private final CountDownLatch serverStartedObserved =
				new CountDownLatch(1);

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
						List.of(this.serverReference.get().getDiagnostics()
								.getStatus() == McpServerStatus.STARTED,
								this.sokletReference.get().isStarted()));
				this.observedStartedStates.add(probe.get(2, TimeUnit.SECONDS));
			} catch (Throwable throwable) {
				this.probeFailure.compareAndSet(null, throwable);
			} finally {
				if (event instanceof McpMetricsEvent.ServerStarted)
					this.serverStartedObserved.countDown();
				this.lifecycleEventsObserved.countDown();
			}
		}

		private void awaitServerStartedObserved() throws InterruptedException {
			Assertions.assertTrue(this.serverStartedObserved.await(
					5, TimeUnit.SECONDS),
					"The ServerStarted metric callback did not complete.");
		}

		private void awaitLifecycleEventsObserved() throws InterruptedException {
			Assertions.assertTrue(this.lifecycleEventsObserved.await(
					5, TimeUnit.SECONDS),
					"The lifecycle metric callbacks did not complete.");
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
								List.of(this.serverReference.get().getDiagnostics()
										.getStatus() == McpServerStatus.STARTED,
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

	private static final class ReentrantConnectionCollector
			extends RecordingMetricsCollector {
		@NonNull
		private final AtomicReference<McpServer> serverReference;
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
		private final AtomicReference<String> callbackThreadName =
				new AtomicReference<>();
		@NonNull
		private final AtomicBoolean callbackThreadWasDaemon = new AtomicBoolean();
		@NonNull
		private final CountDownLatch requestFinished = new CountDownLatch(1);

		private ReentrantConnectionCollector(
				@NonNull AtomicReference<McpServer> serverReference,
				@NonNull String endpointPath) {
			this.serverReference = requireNonNull(serverReference);
			this.endpointPath = requireNonNull(endpointPath);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			int active = this.activeCallbacks.incrementAndGet();
			this.maximumConcurrentCallbacks.accumulateAndGet(active, Math::max);
			try {
				super.didRecordMcpMetricsEvent(event);
				if (event instanceof McpMetricsEvent.ConnectionAccepted
						&& this.invoked.compareAndSet(false, true)) {
					Thread callbackThread = Thread.currentThread();
					this.callbackThreadName.set(callbackThread.getName());
					this.callbackThreadWasDaemon.set(callbackThread.isDaemon());
					try {
						int port = this.serverReference.get().getDiagnostics()
								.getBoundAddress().orElseThrow().getPort();
						this.response.set(sendDiscovery(port, this.endpointPath,
								"reentrant-connection"));
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
					"The reentrant connection callback's request did not finish.");
		}

		private Throwable failure() {
			return this.failure.get();
		}

		@NonNull
		private HttpResponse<String> response() {
			return requireNonNull(this.response.get());
		}

		private int maximumConcurrentCallbacks() {
			return this.maximumConcurrentCallbacks.get();
		}

		@NonNull
		private String callbackThreadName() {
			return requireNonNull(this.callbackThreadName.get());
		}

		private boolean callbackThreadWasDaemon() {
			return this.callbackThreadWasDaemon.get();
		}
	}

	private static final class FailingMetricsCollector
			extends RecordingMetricsCollector {
		@NonNull
		private final RuntimeException serverFailure;
		@NonNull
		private final RuntimeException connectionFailure;
		@NonNull
		private final RuntimeException requestFailure;
		@NonNull
		private final CountDownLatch requestFinished = new CountDownLatch(1);

		private FailingMetricsCollector(@NonNull RuntimeException serverFailure,
				@NonNull RuntimeException connectionFailure,
				@NonNull RuntimeException requestFailure) {
			this.serverFailure = requireNonNull(serverFailure);
			this.connectionFailure = requireNonNull(connectionFailure);
			this.requestFailure = requireNonNull(requestFailure);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			super.didRecordMcpMetricsEvent(event);
			if (event instanceof McpMetricsEvent.RequestFinished)
				this.requestFinished.countDown();
			if (event instanceof McpMetricsEvent.ServerStarted)
				throw this.serverFailure;
			if (event instanceof McpMetricsEvent.ConnectionAccepted)
				throw this.connectionFailure;
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
