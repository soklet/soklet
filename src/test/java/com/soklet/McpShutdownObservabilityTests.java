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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Focused public and runtime coverage for MCP shutdown observability.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpShutdownObservabilityTests {
	private static final String HOST = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final String SHUTDOWN_METRIC_NAME =
			"soklet_mcp_shutdowns_total";

	@Test
	public void snapshotContractUsesReferenceCountsAndRejectsInvalidValues()
			throws Exception {
		Method getShutdowns = McpMetricsSnapshot.class.getMethod("getShutdowns");
		Assertions.assertInstanceOf(ParameterizedType.class,
				getShutdowns.getGenericReturnType());
		ParameterizedType shutdownsType = (ParameterizedType)
				getShutdowns.getGenericReturnType();
		Assertions.assertArrayEquals(new Object[]{
				ShutdownComponentDisposition.class, Long.class
		}, shutdownsType.getActualTypeArguments());
		Method setShutdowns = McpMetricsSnapshot.Builder.class.getMethod(
				"shutdowns", Map.class);
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				setShutdowns.getReturnType());
		Assertions.assertInstanceOf(ParameterizedType.class,
				setShutdowns.getGenericParameterTypes()[0]);
		ParameterizedType shutdownsParameterType = (ParameterizedType)
				setShutdowns.getGenericParameterTypes()[0];
		Assertions.assertArrayEquals(new Object[]{
				ShutdownComponentDisposition.class, Long.class
		}, shutdownsParameterType.getActualTypeArguments());

		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				McpMetricsSnapshot.emptyInstance());
		Assertions.assertTrue(McpMetricsSnapshot.emptyInstance()
				.getShutdowns().isEmpty());

		Map<ShutdownComponentDisposition, Long> source = new HashMap<>();
		source.put(ShutdownComponentDisposition.GRACEFUL_TERMINATION, 2L);
		source.put(ShutdownComponentDisposition.RESIDUAL_ACTIVITY, 0L);
		McpMetricsSnapshot snapshot = McpMetricsSnapshot.builder()
				.shutdowns(source)
				.build();
		source.put(ShutdownComponentDisposition.GRACEFUL_TERMINATION, 99L);

		Assertions.assertEquals(Map.of(
				ShutdownComponentDisposition.GRACEFUL_TERMINATION, 2L,
				ShutdownComponentDisposition.RESIDUAL_ACTIVITY, 0L),
				snapshot.getShutdowns());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getShutdowns().put(
						ShutdownComponentDisposition.GRACEFUL_TERMINATION, 3L));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().shutdowns(null).build());

		Map<ShutdownComponentDisposition, Long> nullKey = new HashMap<>();
		nullKey.put(null, 1L);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().shutdowns(nullKey).build());

		Map<ShutdownComponentDisposition, Long> nullValue = new HashMap<>();
		nullValue.put(ShutdownComponentDisposition.GRACEFUL_TERMINATION, null);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().shutdowns(nullValue).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().shutdowns(Map.of(
						ShutdownComponentDisposition.RESIDUAL_ACTIVITY, -1L)).build());
	}

	@Test
	public void defaultCollectorAggregatesRendersFiltersAndResetsShutdowns() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		Assertions.assertTrue(collector.snapshot().orElseThrow()
				.getMcpMetrics().getShutdowns().isEmpty());
		Assertions.assertThrows(NullPointerException.class,
				() -> collector.didRecordMcpMetricsEvent(null));

		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.serverStopped(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.serverStopped(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.serverStopped(
				ShutdownComponentDisposition.RESIDUAL_ACTIVITY));
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.handlerExecutionStarted());

		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(Map.of(
				ShutdownComponentDisposition.GRACEFUL_TERMINATION, 2L,
				ShutdownComponentDisposition.RESIDUAL_ACTIVITY, 1L),
				retained.getShutdowns());

		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.serverStopped(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		Assertions.assertEquals(2L,
				retained.getShutdowns().get(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		Assertions.assertEquals(3L, collector.snapshot().orElseThrow()
				.getMcpMetrics().getShutdowns().get(ShutdownComponentDisposition.GRACEFUL_TERMINATION));

		Set<Map<String, String>> shutdownLabels =
				java.util.concurrent.ConcurrentHashMap.newKeySet();
		MetricsCollector.SnapshotTextOptions prometheusOptions =
				MetricsCollector.SnapshotTextOptions
						.withMetricsFormat(
								MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> {
							if (sample.getName().equals(SHUTDOWN_METRIC_NAME))
								shutdownLabels.add(sample.getLabels());
							return true;
						})
						.build();
		String prometheus = collector.snapshotText(prometheusOptions)
				.orElseThrow();
		Assertions.assertTrue(prometheus.contains(
				"# HELP " + SHUTDOWN_METRIC_NAME + " "));
		Assertions.assertTrue(prometheus.contains(
				"# TYPE " + SHUTDOWN_METRIC_NAME + " counter\n"));
		Assertions.assertTrue(prometheus.contains(
				SHUTDOWN_METRIC_NAME + "{outcome=\"graceful_termination\"} 3\n"));
		Assertions.assertTrue(prometheus.contains(
				SHUTDOWN_METRIC_NAME
						+ "{outcome=\"residual_activity\"} 1\n"));
		Assertions.assertEquals(Set.of(
				Map.of("outcome", "graceful_termination"),
				Map.of("outcome", "residual_activity")), shutdownLabels,
				"Shutdown metrics must expose only their fixed outcome dimension.");
		Assertions.assertFalse(prometheus.contains(
				SHUTDOWN_METRIC_NAME + "{outcome=\"CLEAN\"}"));
		Assertions.assertFalse(prometheus.contains("# EOF"));

		MetricsCollector.SnapshotTextOptions residualOnly =
				MetricsCollector.SnapshotTextOptions
						.withMetricsFormat(
								MetricsCollector.MetricsFormat.PROMETHEUS)
						.metricFilter(sample -> sample.getName()
								.equals(SHUTDOWN_METRIC_NAME)
								&& sample.getLabels().equals(Map.of(
										"outcome", "residual_activity")))
						.build();
		String filtered = collector.snapshotText(residualOnly).orElseThrow();
		Assertions.assertTrue(filtered.contains(
				SHUTDOWN_METRIC_NAME
						+ "{outcome=\"residual_activity\"} 1\n"));
		Assertions.assertFalse(filtered.contains("outcome=\"graceful_termination\""));
		Assertions.assertFalse(filtered.contains("soklet_http_"));

		String openMetrics = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.OPEN_METRICS_1_0)
						.build()).orElseThrow();
		Assertions.assertTrue(openMetrics.contains(
				SHUTDOWN_METRIC_NAME + "{outcome=\"graceful_termination\"} 3\n"));
		Assertions.assertTrue(openMetrics.contains(
				SHUTDOWN_METRIC_NAME
						+ "{outcome=\"residual_activity\"} 1\n"));
		Assertions.assertTrue(openMetrics.endsWith("# EOF\n"));
		Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));

		collector.reset();
		Assertions.assertTrue(collector.snapshot().orElseThrow().getMcpMetrics()
				.getShutdowns().isEmpty());
		String resetText = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.build()).orElseThrow();
		Assertions.assertFalse(resetText.contains(SHUTDOWN_METRIC_NAME),
				"A reset collector must omit the empty shutdown metric family.");
		Assertions.assertEquals(2L,
				retained.getShutdowns().get(ShutdownComponentDisposition.GRACEFUL_TERMINATION),
				"Reset must not mutate a retained point-in-time snapshot.");
	}

	@Test
	public void managedCleanStopEmitsOneMatchingLifecycleAndMetricsOutcome()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/managed-clean");
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			soklet.start();
			soklet.close();
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION));

			soklet.close();
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		} finally {
			soklet.close();
		}
	}

	@Test
	public void lateShutdownFanoutCannotReopenTerminalMetricsDeferral()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/terminal-metrics-seal");
		SokletConfig ownerConfig = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector)
				.lifecycleObserver(observer)
				.build();
		Soklet callbackSoklet = newSoklet(
				newServer("/mcp/terminal-metrics-seal-callback"),
				MetricsCollector.disabledInstance(),
				LifecycleObserver.defaultInstance());
		DeferredTerminalMetricsLauncher launcher =
				new DeferredTerminalMetricsLauncher();
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig), NanoClock.system(),
				new LifecycleWorkers(launcher));
		boolean startAttempted = false;

		try {
			startAttempted = true;
			owner.start();
			collector.awaitServerStarted();
			CompletionStage<ShutdownResult> shutdown = owner.shutdown();
			ShutdownResult publicResult = shutdown.toCompletableFuture().get(
					WAIT.toNanos(), TimeUnit.NANOSECONDS);
			launcher.awaitDeferredHandoff();

			InternalShutdownResult result = owner.result().orElseThrow();
			Assertions.assertSame(result, publicResult.internalResult());
			CompletionStage<ShutdownResult> repeatedShutdown = owner.shutdown();
			Assertions.assertSame(shutdown, repeatedShutdown);
			Assertions.assertSame(result, repeatedShutdown.toCompletableFuture().get(
					WAIT.toNanos(), TimeUnit.NANOSECONDS).internalResult());
			Assertions.assertSame(result, owner.result().orElseThrow());
			launcher.runDeferredHandoff();

			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		} finally {
			if (startAttempted) {
				owner.shutdown();
				owner.awaitCompletion();
				launcher.awaitDeferredHandoff();
				launcher.runDeferredHandoffIfPresent();
			}
			callbackSoklet.close();
			McpServerRuntimeBridge bridge = runtimeBridge(server);
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void freshOwnerCleanStopRecordsOneLifecycleAndMetricsOutcome()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/fresh-owner-clean");
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			soklet.start();
			soklet.close();
			soklet.close();
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		} finally {
			soklet.close();
		}
	}

	@Test
	public void didStartObserverFailureDoesNotVetoOwnerOrCleanStop()
			throws Exception {
		RuntimeException expectedFailure = new RuntimeException(
				"expected did-start failure");
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer =
				new RecordingLifecycleObserver(expectedFailure);
		McpServer server = newServer("/mcp/startup-rollback");
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			soklet.start();
			observer.awaitDidStartMcp();
			Assertions.assertEquals(SokletStatus.RUNNING, soklet.getStatus(),
					"An observer failure must not veto owner readiness.");
			Assertions.assertEquals(1, observer.getDidStartMcpCallbacks());
			soklet.close();
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		} finally {
			soklet.close();
		}
	}

	@Test
	public void failedSubscriptionRegistrationCloseRetriesAtForceBeforeOneForcedOutcome()
			throws Exception {
		Duration shutdownTimeout = Duration.ofMillis(150);
		FailingClosePublisher publisher = new FailingClosePublisher();
		McpEndpoint endpoint = subscriptionEndpoint(
				"/mcp/subscription-close-retry", publisher);
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = serverFor(List.of(endpoint), shutdownTimeout);
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			soklet.start();
			Assertions.assertEquals(1, publisher.getSubscribeAttempts());

			Assertions.assertDoesNotThrow(soklet::close);
			Assertions.assertEquals(2, publisher.getCloseAttempts());
			Assertions.assertTrue(observer.getStopFailures().isEmpty());
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.FORCED_TERMINATION));
			Assertions.assertFalse(runtimeBridge(server).getRuntimeState()
					.stopRequired());

			Assertions.assertDoesNotThrow(soklet::close);
			Assertions.assertEquals(2, publisher.getCloseAttempts());
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.FORCED_TERMINATION));
		} finally {
			soklet.close();
		}
	}

	@Test
	@Timeout(60)
	public void blockingSubscriptionRegistrationCloseFreezesOneResidualOutcome()
			throws Exception {
		Duration shutdownTimeout = Duration.ofMillis(150);
		BlockingClosePublisher publisher = new BlockingClosePublisher();
		McpEndpoint endpoint = subscriptionEndpoint(
				"/mcp/subscription-close-blocking", publisher);
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = serverFor(List.of(endpoint), shutdownTimeout);
		Soklet soklet = newSoklet(server, collector, observer,
				shortShutdownPolicy());
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			soklet.start();
			long stopStartedAt = System.nanoTime();
			SokletShutdownIncompleteException stopFailure = Assertions.assertThrows(
					SokletShutdownIncompleteException.class, soklet::close);
			InternalShutdownResult result =
					stopFailure.getInternalShutdownResult();
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			Duration stopDuration = Duration.ofNanos(
					System.nanoTime() - stopStartedAt);
			Assertions.assertTrue(stopDuration.compareTo(
					shutdownTimeout.plusSeconds(4)) < 0,
					() -> "Blocking registration cleanup exceeded its bounded "
							+ "shared grace-plus-force budget: " + stopDuration);
			publisher.awaitCloseEntered();
			Assertions.assertEquals(1, publisher.getCloseAttempts());
			assertIncompleteShutdownParity(observer, collector, result);
			Assertions.assertTrue(bridge.getRuntimeState().stopRequired());

			publisher.releaseClose();
			publisher.awaitClosed();
			awaitSubscriptionRegistrationEvidence(bridge, false);
			Assertions.assertEquals(McpServerStatus.RESIDUAL_ACTIVITY,
					server.getDiagnostics().getStatus(),
					"Late cleanup must not rewrite the frozen residual result.");
			Assertions.assertTrue(bridge.getRuntimeState().stopRequired(),
					"Late cleanup must not rewrite the frozen incomplete generation.");
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			assertIncompleteShutdownParity(observer, collector, result);

			SokletShutdownIncompleteException repeatedStop = Assertions.assertThrows(
					SokletShutdownIncompleteException.class, soklet::close);
			Assertions.assertSame(result,
					repeatedStop.getInternalShutdownResult());
			Assertions.assertEquals(1, publisher.getCloseAttempts(),
					"A completed registration close must not be invoked again.");
			assertIncompleteShutdownParity(observer, collector, result);
			IllegalStateException restartRejection = Assertions.assertThrows(
					IllegalStateException.class, soklet::start,
					"Late physical cleanup cannot make an incomplete generation restartable.");
			Assertions.assertEquals(IllegalStateException.class,
					restartRejection.getClass());
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());

			SokletShutdownIncompleteException finalStop = Assertions.assertThrows(
					SokletShutdownIncompleteException.class, soklet::close);
			Assertions.assertSame(result, finalStop.getInternalShutdownResult());
			Assertions.assertEquals(1, publisher.getCloseAttempts());
			assertIncompleteShutdownParity(observer, collector, result);
		} finally {
			publisher.releaseClose();
			stopOwnerAllowingTerminalFailure(soklet);
		}
	}

	@Test
	public void unexpectedListenerTerminationAndFreshOwnerHaveExactParity()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/unexpected-termination");
		Soklet soklet = newSoklet(server, collector, observer);
		McpServerRuntimeBridge bridge = runtimeBridge(server);
		McpTransportLifecycleAdapter lifecycleAdapter = lifecycleAdapter(server);
		Soklet freshOwner = null;

		try {
			soklet.start();
			collector.awaitServerStarted();
			McpTransportLifecycleAdapter.Generation failedGeneration =
					(McpTransportLifecycleAdapter.Generation)
							lifecycleAdapter.currentGeneration();
			terminateUnexpectedly(eventLoop(bridge));
			lifecycleAdapter.awaitStop(failedGeneration);
			Assertions.assertFalse(bridge.getRuntimeState().started());

			SokletUnexpectedTerminationException stopFailure =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							soklet::close);
			InternalShutdownResult result =
					stopFailure.getInternalShutdownResult();
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertSame(result,
					lifecycleAdapter.result(failedGeneration).orElseThrow());
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.UNEXPECTED_TERMINATION));
			Assertions.assertFalse(bridge.getRuntimeState().stopRequired());
			Assertions.assertFalse(lifecycleAdapter.hasActiveGeneration());
			Assertions.assertEquals(McpServerStatus.TERMINATED,
					server.getDiagnostics().getStatus());

			SokletUnexpectedTerminationException repeatedStop =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							soklet::close);
			Assertions.assertSame(result,
					repeatedStop.getInternalShutdownResult());
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.UNEXPECTED_TERMINATION));
			IllegalStateException restartRejection = Assertions.assertThrows(
					IllegalStateException.class, soklet::start);
			Assertions.assertEquals(IllegalStateException.class,
					restartRejection.getClass());

			RecordingMetricsCollector freshCollector =
					new RecordingMetricsCollector();
			RecordingLifecycleObserver freshObserver =
					new RecordingLifecycleObserver();
			McpServer freshServer = newServer(
					"/mcp/unexpected-termination-fresh");
			freshOwner = newSoklet(freshServer, freshCollector, freshObserver);
			freshOwner.start();
			Assertions.assertEquals(SokletStatus.RUNNING, freshOwner.getStatus());
			freshOwner.close();
			assertShutdownParity(freshObserver, freshCollector,
					List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		} finally {
			if (freshOwner != null)
				freshOwner.close();
			stopOwnerAllowingTerminalFailure(soklet);
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void ownerNormalizesUnexpectedGenerationExactlyOnceAfterAdapterWait()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/direct-unexpected-restart");
		Soklet soklet = newSoklet(server, collector, observer);
		McpServerRuntimeBridge bridge = runtimeBridge(server);
		McpTransportLifecycleAdapter lifecycleAdapter = lifecycleAdapter(server);

		try {
			soklet.start();
			collector.awaitServerStarted();
			McpTransportLifecycleAdapter.Generation failedGeneration =
					(McpTransportLifecycleAdapter.Generation)
							lifecycleAdapter.currentGeneration();
			terminateUnexpectedly(eventLoop(bridge));
			lifecycleAdapter.awaitStop(failedGeneration);

			SokletUnexpectedTerminationException stopFailure =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							soklet::close);
			InternalShutdownResult result =
					stopFailure.getInternalShutdownResult();
			Assertions.assertSame(result,
					lifecycleAdapter.result(failedGeneration).orElseThrow());
			Assertions.assertEquals(SokletStatus.CLOSED, soklet.getStatus());
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.UNEXPECTED_TERMINATION));

			SokletUnexpectedTerminationException repeatedStop =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							soklet::close);
			Assertions.assertSame(result,
					repeatedStop.getInternalShutdownResult());
			IllegalStateException restartRejection = Assertions.assertThrows(
					IllegalStateException.class, soklet::start);
			Assertions.assertEquals(IllegalStateException.class,
					restartRejection.getClass());
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.UNEXPECTED_TERMINATION));
		} finally {
			stopOwnerAllowingTerminalFailure(soklet);
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/managed-unexpected-restart");
		Soklet soklet = newSoklet(server, collector, observer);
		McpServerRuntimeBridge bridge = runtimeBridge(server);
		McpTransportLifecycleAdapter lifecycleAdapter = lifecycleAdapter(server);
		Soklet freshOwner = null;

		try {
			soklet.start();
			collector.awaitServerStarted();
			McpTransportLifecycleAdapter.Generation failedGeneration =
					(McpTransportLifecycleAdapter.Generation)
							lifecycleAdapter.currentGeneration();
			terminateUnexpectedly(eventLoop(bridge));
			Assertions.assertFalse(bridge.getRuntimeState().started());
			lifecycleAdapter.awaitStop(failedGeneration);

			IllegalStateException restartRejection = Assertions.assertThrows(
					IllegalStateException.class, soklet::start);
			Assertions.assertEquals(IllegalStateException.class,
					restartRejection.getClass());
			SokletUnexpectedTerminationException stopFailure =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							soklet::close);
			InternalShutdownResult result =
					stopFailure.getInternalShutdownResult();
			Assertions.assertSame(result,
					lifecycleAdapter.result(failedGeneration).orElseThrow());
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.UNEXPECTED_TERMINATION));

			SokletUnexpectedTerminationException repeatedStop =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							soklet::close);
			Assertions.assertSame(result,
					repeatedStop.getInternalShutdownResult());
			assertShutdownParity(observer, collector,
					List.of(ShutdownComponentDisposition.UNEXPECTED_TERMINATION));

			RecordingMetricsCollector freshCollector =
					new RecordingMetricsCollector();
			RecordingLifecycleObserver freshObserver =
					new RecordingLifecycleObserver();
			McpServer freshServer = newServer(
					"/mcp/managed-unexpected-fresh");
			freshOwner = newSoklet(freshServer, freshCollector, freshObserver);
			freshOwner.start();
			Assertions.assertEquals(SokletStatus.RUNNING, freshOwner.getStatus());
			freshOwner.close();
			assertShutdownParity(freshObserver, freshCollector,
					List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		} finally {
			if (freshOwner != null)
				freshOwner.close();
			stopOwnerAllowingTerminalFailure(soklet);
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void failedStartCleanupEmitsOneExactForcedServerStoppedEvent()
			throws Exception {
		FailingClosePublisher firstPublisher = new FailingClosePublisher();
		FailingSubscribePublisher secondPublisher =
				new FailingSubscribePublisher();
		McpEndpoint firstEndpoint = subscriptionEndpoint(
				"/mcp/failed-start-first", firstPublisher);
		McpEndpoint secondEndpoint = subscriptionEndpoint(
				"/mcp/failed-start-second", secondPublisher);
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = serverFor(List.of(firstEndpoint, secondEndpoint),
				Duration.ofMillis(150));
		Soklet soklet = newSoklet(server, collector, observer,
				shortShutdownPolicy());
		McpServerRuntimeBridge bridge = runtimeBridge(server);
		Soklet freshOwner = null;

		try {
			SokletStartupException startupFailure = Assertions.assertThrows(
					SokletStartupException.class, soklet::start);
			Assertions.assertSame(secondPublisher.getFirstSubscribeFailure(),
					startupFailure.getCause());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					startupFailure.getInternalStartupDisposition());
			InternalShutdownResult failedResult =
					startupFailure.getInternalShutdownResult();
			Assertions.assertSame(failedResult,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertSame(failedResult,
					lifecycleAdapter(server).result().orElseThrow());
			Assertions.assertEquals(SokletStatus.CLOSED, soklet.getStatus());
			Assertions.assertFalse(bridge.getRuntimeState().stopRequired());
			Assertions.assertEquals(2, firstPublisher.getCloseAttempts(),
					"Failed-start cleanup must retry the failed close at force.");
			assertFailedStartLifecycle(observer, failedResult,
					ShutdownComponentDisposition.FORCED_TERMINATION);
			awaitServerStopOutcomeCount(collector, 1);
			Assertions.assertEquals(
					List.of(ShutdownComponentDisposition.FORCED_TERMINATION),
					collector.getServerStopOutcomes());
			Assertions.assertEquals(Map.of(
					ShutdownComponentDisposition.FORCED_TERMINATION, 1L),
					collector.snapshot().orElseThrow().getMcpMetrics().getShutdowns());

			Assertions.assertDoesNotThrow(soklet::close);
			Assertions.assertFalse(bridge.getRuntimeState().stopRequired());
			Assertions.assertEquals(2, firstPublisher.getCloseAttempts(),
					"A later stop must not repeat completed failed-start cleanup.");
			assertFailedStartLifecycle(observer, failedResult,
					ShutdownComponentDisposition.FORCED_TERMINATION);
			Assertions.assertEquals(
					List.of(ShutdownComponentDisposition.FORCED_TERMINATION),
					collector.getServerStopOutcomes(),
					"Repeated close must not redeliver the failed generation.");

			RecordingLifecycleObserver freshObserver =
					new RecordingLifecycleObserver();
			McpServer freshServer = serverFor(
					List.of(firstEndpoint, secondEndpoint), Duration.ofMillis(150));
			freshOwner = newSoklet(freshServer, collector, freshObserver,
					shortShutdownPolicy());
			freshOwner.start();
			Assertions.assertEquals(SokletStatus.RUNNING, freshOwner.getStatus());
			Assertions.assertEquals(2, firstPublisher.getSubscribeAttempts());
			Assertions.assertEquals(2, secondPublisher.getSubscribeAttempts());
			Assertions.assertEquals(
					List.of(ShutdownComponentDisposition.FORCED_TERMINATION),
					collector.getServerStopOutcomes());
			Assertions.assertEquals(List.of(ShutdownComponentDisposition.FORCED_TERMINATION),
					observer.getStopOutcomes(),
					"Owner callbacks retain the completed startup rollback outcome.");

			freshOwner.close();
			Assertions.assertEquals(3, firstPublisher.getCloseAttempts());
			assertShutdownParity(freshObserver, collector,
					List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION),
					List.of(ShutdownComponentDisposition.FORCED_TERMINATION,
							ShutdownComponentDisposition.GRACEFUL_TERMINATION));

			freshOwner.close();
			assertShutdownParity(freshObserver, collector,
					List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION),
					List.of(ShutdownComponentDisposition.FORCED_TERMINATION,
							ShutdownComponentDisposition.GRACEFUL_TERMINATION));
		} finally {
			if (freshOwner != null)
				freshOwner.close();
			soklet.close();
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void shutdownMetricsCallbackRunsOutsideServerLifecycleLock()
			throws Exception {
		ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
		AtomicReference<McpServer> serverReference = new AtomicReference<>();
		AtomicReference<Soklet> sokletReference = new AtomicReference<>();
		AtomicReference<McpServerStatus> observedStatus = new AtomicReference<>();
		AtomicReference<Boolean> observedSokletStarted = new AtomicReference<>();
		AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
		CountDownLatch callbackCompleted = new CountDownLatch(1);
		MetricsCollector collector = new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
				if (!(requireNonNull(event)
						instanceof McpMetricsEvent.ServerStopped))
					return;
				try {
					observedStatus.set(probeExecutor.submit(() ->
							serverReference.get().getDiagnostics().getStatus())
							.get(2, TimeUnit.SECONDS));
					observedSokletStarted.set(probeExecutor.submit(() ->
							sokletReference.get().getStatus()
									== SokletStatus.RUNNING)
							.get(2, TimeUnit.SECONDS));
				} catch (Throwable throwable) {
					callbackFailure.set(throwable);
				} finally {
					callbackCompleted.countDown();
				}
			}
		};
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/callback-lock-boundary");
		serverReference.set(server);
		Soklet soklet = newSoklet(server, collector, observer);
		sokletReference.set(soklet);

		try {
			soklet.start();
			soklet.close();
			observer.awaitTerminal();
			Assertions.assertTrue(callbackCompleted.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS),
					"The shutdown metrics callback did not complete.");
			Assertions.assertNull(callbackFailure.get(),
					"The shutdown callback blocked a concurrent diagnostics read.");
			Assertions.assertEquals(McpServerStatus.TERMINATED,
					observedStatus.get());
			Assertions.assertEquals(false, observedSokletStarted.get());
			Assertions.assertEquals(List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION),
					observer.getStopOutcomes());
		} finally {
			soklet.close();
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void shutdownMetricsCollectorFailureIsContainedAndLoggedOnce()
			throws Exception {
		RuntimeException expectedFailure = new RuntimeException(
				"expected shutdown metrics failure");
		AtomicInteger attempts = new AtomicInteger();
		MetricsCollector collector = new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
				if (!(requireNonNull(event)
						instanceof McpMetricsEvent.ServerStopped))
					return;
				attempts.incrementAndGet();
				throw expectedFailure;
			}
		};
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/failing-collector");
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			soklet.start();
			Assertions.assertDoesNotThrow(soklet::close);
			observer.awaitTerminal();
			awaitLogEventCount(observer,
					LogEventType.METRICS_COLLECTOR_FAILED, 1);
			Assertions.assertEquals(List.of(ShutdownComponentDisposition.GRACEFUL_TERMINATION),
					observer.getStopOutcomes());
			Assertions.assertEquals(1, attempts.get());
			List<LogEvent> failures = observer.getLogEvents().stream()
					.filter(event -> event.getLogEventType()
							== LogEventType.METRICS_COLLECTOR_FAILED)
					.toList();
			Assertions.assertEquals(1, failures.size());
			Assertions.assertTrue(failures.get(0).getThrowable().isEmpty(),
					failures.get(0).toString());
			Assertions.assertTrue(failures.get(0).getRequest().isEmpty());
			Assertions.assertTrue(failures.get(0).getResourceMethod().isEmpty());
			Assertions.assertTrue(failures.get(0).getMarshaledResponse().isEmpty());
			Assertions.assertFalse(failures.toString().contains(
					expectedFailure.getMessage()), failures.toString());

			soklet.close();
			Assertions.assertEquals(1, attempts.get());
			Assertions.assertEquals(1, observer.getLogEvents().stream()
					.filter(event -> event.getLogEventType()
							== LogEventType.METRICS_COLLECTOR_FAILED)
					.count());
		} finally {
			soklet.close();
		}
	}

	@Test
	@Timeout(60)
	public void residualStopAndLaterExitDoNotDuplicateLifecycleOrMetricsOutcome()
			throws Exception {
		String path = "/mcp/residual-observability";
		String toolName = "observability.blocking";
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
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
						"shutdown-observability-test", "4.0.0").build())
				.tool(tool)
				.build();
		McpServer server = McpServer.withPort(0)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST))
				.requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(1)
				.build();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		Soklet soklet = newSoklet(server, collector, observer,
				shortShutdownPolicy());
		CompletableFuture<HttpResponse<String>> request = null;

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow()
					.getPort();
			request = callTool(port, path, toolName);
			Assertions.assertTrue(handlerEntered.await(5, TimeUnit.SECONDS),
					"The residual fixture handler did not enter.");

			SokletShutdownIncompleteException stopFailure = Assertions.assertThrows(
					SokletShutdownIncompleteException.class, soklet::close);
			InternalShutdownResult result =
					stopFailure.getInternalShutdownResult();
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS),
					"Shutdown did not interrupt the held handler.");
			Assertions.assertEquals(
					McpServerStatus.RESIDUAL_ACTIVITY,
					server.getDiagnostics().getStatus());
			assertIncompleteShutdownParity(observer, collector, result);
			McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
					.getMcpMetrics();

			SokletShutdownIncompleteException repeatedStop = Assertions.assertThrows(
					SokletShutdownIncompleteException.class, soklet::close);
			Assertions.assertSame(result,
					repeatedStop.getInternalShutdownResult());
			assertIncompleteShutdownParity(observer, collector, result);

			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(5, TimeUnit.SECONDS),
					"The released residual fixture handler did not exit.");
			Assertions.assertEquals(McpServerStatus.RESIDUAL_ACTIVITY,
					server.getDiagnostics().getStatus(),
					"Late cleanup must not rewrite the frozen residual result.");
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			assertIncompleteShutdownParity(observer, collector, result);
			Assertions.assertEquals(Map.of(
					ShutdownComponentDisposition.RESIDUAL_ACTIVITY, 1L),
					retained.getShutdowns(),
					"A retained residual snapshot must remain unchanged.");
		} finally {
			releaseHandler.countDown();
			if (request != null)
				request.cancel(true);
			stopOwnerAllowingTerminalFailure(soklet);
		}
	}

	private static void assertShutdownParity(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull RecordingMetricsCollector collector,
			@NonNull List<@NonNull ShutdownComponentDisposition> expectedOutcomes)
			throws InterruptedException {
		assertShutdownParity(observer, collector, expectedOutcomes,
				expectedOutcomes);
	}

	private static void assertShutdownParity(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull RecordingMetricsCollector collector,
			@NonNull List<@NonNull ShutdownComponentDisposition>
					expectedObserverOutcomes,
			@NonNull List<@NonNull ShutdownComponentDisposition>
					expectedMetricOutcomes)
			throws InterruptedException {
		requireNonNull(observer);
		requireNonNull(collector);
		requireNonNull(expectedObserverOutcomes);
		requireNonNull(expectedMetricOutcomes);
		observer.awaitTerminal();
		awaitServerStopOutcomeCount(collector, expectedMetricOutcomes.size());
		Assertions.assertEquals(1, observer.getWillStopMcpCallbacks());
		Assertions.assertEquals(expectedObserverOutcomes,
				observer.getStopOutcomes());
		ShutdownResult aggregate = observer.getGlobalResult();
		Assertions.assertNotNull(aggregate);
		ShutdownComponentResult participant = aggregate.getShutdownComponentResult(
				ShutdownComponentType.MCP).orElseThrow();
		Assertions.assertEquals(List.of(participant), observer.getStopResults(),
				"The MCP callback must receive the aggregate's exact participant result.");
		Assertions.assertEquals(expectedMetricOutcomes,
				collector.getServerStopOutcomes());

		Map<ShutdownComponentDisposition, Long> expectedCounts = new HashMap<>();
		for (ShutdownComponentDisposition outcome : expectedMetricOutcomes)
			expectedCounts.merge(outcome, 1L, Long::sum);
		Assertions.assertEquals(expectedCounts,
				collector.snapshot().orElseThrow().getMcpMetrics()
						.getShutdowns());
	}

	private static void assertIncompleteShutdownParity(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull RecordingMetricsCollector collector,
			@NonNull InternalShutdownResult result) throws InterruptedException {
		requireNonNull(observer);
		requireNonNull(collector);
		requireNonNull(result);
		observer.awaitTerminal();
		awaitServerStopOutcomeCount(collector, 1);
		Assertions.assertEquals(1, observer.getWillStopMcpCallbacks());
		ShutdownResult aggregate = observer.getGlobalResult();
		Assertions.assertNotNull(aggregate);
		Assertions.assertSame(result, aggregate.internalResult());
		ShutdownComponentResult participant = aggregate.getShutdownComponentResult(
				ShutdownComponentType.MCP).orElseThrow();
		Assertions.assertEquals(List.of(participant), observer.getStopResults(),
				"An incomplete MCP participant still publishes one exact terminal callback.");
		Assertions.assertEquals(
				List.of(ShutdownComponentDisposition.RESIDUAL_ACTIVITY),
				observer.getStopOutcomes());
		Assertions.assertEquals(List.of(ShutdownComponentDisposition.RESIDUAL_ACTIVITY),
				collector.getServerStopOutcomes());
		Assertions.assertEquals(Map.of(
				ShutdownComponentDisposition.RESIDUAL_ACTIVITY, 1L),
				collector.snapshot().orElseThrow().getMcpMetrics().getShutdowns());
	}

	private static void assertFailedStartLifecycle(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull InternalShutdownResult result,
			@NonNull ShutdownComponentDisposition expectedDisposition)
			throws InterruptedException {
		requireNonNull(observer);
		requireNonNull(result);
		requireNonNull(expectedDisposition);
		observer.awaitTerminal();
		Assertions.assertEquals(1, observer.getWillStopMcpCallbacks());
		Assertions.assertEquals(List.of(expectedDisposition),
				observer.getStopOutcomes());
		ShutdownResult aggregate = observer.getGlobalResult();
		Assertions.assertNotNull(aggregate);
		Assertions.assertSame(result, aggregate.internalResult());
		Assertions.assertEquals(List.of(aggregate.getShutdownComponentResult(
				ShutdownComponentType.MCP).orElseThrow()), observer.getStopResults());
	}

	private static void awaitServerStopOutcomeCount(
			@NonNull RecordingMetricsCollector collector, int expectedCount)
			throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (System.nanoTime() - deadline < 0L) {
			if (requireNonNull(collector).getServerStopOutcomes().size()
					== expectedCount)
				return;
			Thread.sleep(10L);
		}
		Assertions.assertEquals(expectedCount,
				collector.getServerStopOutcomes().size(),
				"The terminal MCP metric was not published.");
	}

	private static void awaitLogEventCount(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull LogEventType eventType, int expectedCount)
			throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (System.nanoTime() - deadline < 0L) {
			long count = requireNonNull(observer).getLogEvents().stream()
					.filter(event -> event.getLogEventType()
							== requireNonNull(eventType))
					.count();
			if (count == expectedCount)
				return;
			Thread.sleep(10L);
		}
		Assertions.assertEquals(expectedCount,
				observer.getLogEvents().stream()
						.filter(event -> event.getLogEventType() == eventType)
						.count(),
				"The expected lifecycle log event was not delivered.");
	}

	private static void stopOwnerAllowingTerminalFailure(
			@NonNull Soklet owner) {
		try {
			requireNonNull(owner).close();
		} catch (SokletUnexpectedTerminationException
				| SokletShutdownIncompleteException ignored) {
			// Preserve the already-asserted immutable terminal result during cleanup.
		}
	}

	private static int occurrences(@NonNull String value,
			@NonNull String substring) {
		requireNonNull(value);
		requireNonNull(substring);
		int count = 0;
		int index = 0;
		while ((index = value.indexOf(substring, index)) >= 0) {
			count++;
			index += substring.length();
		}
		return count;
	}

	@NonNull
	private static Soklet newSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector collector,
			@NonNull LifecycleObserver observer) {
		return newSoklet(server, collector, observer,
				testLifecyclePolicy());
	}

	@NonNull
	private static Soklet newSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector collector,
			@NonNull LifecycleObserver observer,
			@NonNull LifecyclePolicy lifecyclePolicy) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector)
				.lifecycleObserver(observer)
				.lifecyclePolicy(lifecyclePolicy)
				.build());
	}

	@NonNull
	private static LifecyclePolicy testLifecyclePolicy() {
		return LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(5))
				.startupCancelationTimeout(Duration.ofSeconds(2))
				.gracefulShutdownTimeout(Duration.ofSeconds(2))
				.forcedShutdownTimeout(Duration.ofSeconds(1))
				.build();
	}

	@NonNull
	private static LifecyclePolicy shortShutdownPolicy() {
		return LifecyclePolicy.builder()
				.startupTimeout(WAIT)
				.startupCancelationTimeout(Duration.ofMillis(100))
				.gracefulShutdownTimeout(Duration.ofMillis(100))
				.forcedShutdownTimeout(Duration.ofMillis(100))
				.build();
	}

	@NonNull
	private static McpServer newServer(@NonNull String path) {
		McpEndpoint endpoint = McpEndpoint.withPath(requireNonNull(path))
				.serverInformation(McpImplementation.withNameAndVersion(
						"shutdown-observability-test", "4.0.0").build())
				.build();
		return serverFor(endpoint);
	}

	@NonNull
	private static McpServer serverFor(@NonNull McpEndpoint endpoint) {
		return serverFor(List.of(requireNonNull(endpoint)));
	}

	@NonNull
	private static McpServer serverFor(
			@NonNull List<@NonNull McpEndpoint> endpoints) {
		return serverFor(endpoints, null);
	}

	@NonNull
	private static McpServer serverFor(
			@NonNull List<@NonNull McpEndpoint> endpoints,
			@Nullable Duration shutdownTimeout) {
		if (shutdownTimeout != null)
			requireNonNull(shutdownTimeout);
		McpServer.Builder builder = McpServer.withPort(0)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.copyOf(requireNonNull(endpoints))))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST));
		return builder.build();
	}

	@NonNull
	private static McpEndpoint subscriptionEndpoint(@NonNull String path,
			@NonNull McpSubscriptionEventPublisher publisher) {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(requireNonNull(publisher))
				.notificationType(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)
				.build();
		return McpEndpoint.withPath(requireNonNull(path))
				.serverInformation(McpImplementation.withNameAndVersion(
						"shutdown-observability-test", "4.0.0").build())
				.resourceListHandler((request, list, features) ->
						McpResourcePage.builder().build())
				.subscriptions(subscriptions)
				.build();
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> callTool(
			int port, @NonNull String path, @NonNull String toolName) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"shutdown-observability\","
				+ "\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + toolName + "\",\"arguments\":{}}}";
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + HOST + ":" + port + path))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
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

	private static void awaitStatus(@NonNull McpServer server,
			@NonNull McpServerStatus expectedStatus) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			if (server.getDiagnostics().getStatus() == expectedStatus)
				return;
			Thread.sleep(10L);
		}
		Assertions.assertEquals(expectedStatus,
				server.getDiagnostics().getStatus());
	}

	private static void awaitStopRequired(
			@NonNull McpServerRuntimeBridge bridge, boolean expected)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			if (bridge.getRuntimeState().stopRequired() == expected)
				return;
			Thread.sleep(10L);
		}
		Assertions.assertEquals(expected,
				bridge.getRuntimeState().stopRequired());
	}

	private static void awaitSubscriptionRegistrationEvidence(
			@NonNull McpServerRuntimeBridge bridge, boolean expected)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			if (bridge.getLifecycleEvidence().subscriptionRegistration() == expected)
				return;
			Thread.sleep(10L);
		}
		Assertions.assertEquals(expected,
				bridge.getLifecycleEvidence().subscriptionRegistration());
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

	private static final class RecordingMetricsCollector
			implements MetricsCollector {
		@NonNull
		private final DefaultMetricsCollector delegate;
		@NonNull
		private final List<@NonNull McpMetricsEvent> events;
		@NonNull
		private final CountDownLatch serverStarted;

		private RecordingMetricsCollector() {
			this.delegate = DefaultMetricsCollector.defaultInstance();
			this.events = new CopyOnWriteArrayList<>();
			this.serverStarted = new CountDownLatch(1);
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			McpMetricsEvent requiredEvent = requireNonNull(event);
			this.events.add(requiredEvent);
			this.delegate.didRecordMcpMetricsEvent(requiredEvent);
			if (requiredEvent instanceof McpMetricsEvent.ServerStarted)
				this.serverStarted.countDown();
		}

		@Override
		@NonNull
		public Optional<Snapshot> snapshot() {
			return this.delegate.snapshot();
		}

		@Override
		@NonNull
		public Optional<String> snapshotText(@NonNull SnapshotTextOptions options) {
			return this.delegate.snapshotText(requireNonNull(options));
		}

		@Override
		public void reset() {
			this.delegate.reset();
		}

		@NonNull
		private List<@NonNull ShutdownComponentDisposition> getServerStopOutcomes() {
			return this.events.stream()
					.filter(McpMetricsEvent.ServerStopped.class::isInstance)
					.map(McpMetricsEvent.ServerStopped.class::cast)
					.map(McpMetricsEvent.ServerStopped::getOutcome)
					.toList();
		}

		private void awaitServerStarted() throws InterruptedException {
			Assertions.assertTrue(this.serverStarted.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS),
					"The MCP ServerStarted metric was not published before termination.");
		}
	}

	private static final class DeferredTerminalMetricsLauncher
			implements LifecycleWorkers.Launcher {
		private static final String HANDOFF_NAME =
				"soklet-mcp-terminal-metrics";
		@NonNull
		private final AtomicReference<@Nullable Runnable> deferredHandoff =
				new AtomicReference<>();
		@NonNull
		private final CountDownLatch handoffDeferred = new CountDownLatch(1);

		@Override
		public void launch(@NonNull String name, @NonNull Runnable runnable) {
			Runnable exactRunnable = requireNonNull(runnable);
			if (HANDOFF_NAME.equals(requireNonNull(name))) {
				if (!this.deferredHandoff.compareAndSet(null, exactRunnable))
					throw new IllegalStateException(
							"The terminal metrics handoff was already deferred.");
				this.handoffDeferred.countDown();
				return;
			}
			startDaemon(name, exactRunnable);
		}

		private void awaitDeferredHandoff() throws InterruptedException {
			Assertions.assertTrue(this.handoffDeferred.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS),
					"The terminal metrics handoff was not deferred.");
		}

		private void runDeferredHandoff() throws InterruptedException {
			Runnable handoff = this.deferredHandoff.getAndSet(null);
			Assertions.assertNotNull(handoff,
					"The terminal metrics handoff was already consumed.");
			runAndJoin(HANDOFF_NAME, requireNonNull(handoff));
		}

		private void runDeferredHandoffIfPresent() throws InterruptedException {
			Runnable handoff = this.deferredHandoff.getAndSet(null);
			if (handoff != null)
				runAndJoin(HANDOFF_NAME, handoff);
		}

		private static void startDaemon(@NonNull String name,
				@NonNull Runnable runnable) {
			Thread thread = new Thread(requireNonNull(runnable), requireNonNull(name));
			thread.setDaemon(true);
			thread.start();
		}

		private static void runAndJoin(@NonNull String name,
				@NonNull Runnable runnable) throws InterruptedException {
			AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
			Thread thread = new Thread(() -> {
				try {
					requireNonNull(runnable).run();
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}, requireNonNull(name));
			thread.setDaemon(true);
			thread.start();
			thread.join(WAIT.toMillis());
			Assertions.assertFalse(thread.isAlive(),
					"The deferred terminal metrics handoff did not complete.");
			Assertions.assertNull(failure.get(),
					"The deferred terminal metrics handoff failed.");
		}
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		@NonNull
		private final List<@NonNull ShutdownComponentResult> stopResults;
		@NonNull
		private final List<@NonNull LogEvent> logEvents;
		@NonNull
		private final AtomicInteger willStopMcpCallbacks;
		@NonNull
		private final AtomicInteger didStartMcpCallbacks;
		@NonNull
		private final CountDownLatch didStartMcp;
		@NonNull
		private final CountDownLatch terminal;
		@NonNull
		private final AtomicReference<@Nullable ShutdownResult> globalResult;
		@Nullable
		private final RuntimeException didStartFailure;

		private RecordingLifecycleObserver() {
			this(null);
		}

		private RecordingLifecycleObserver(
				@Nullable RuntimeException didStartFailure) {
			this.stopResults = new CopyOnWriteArrayList<>();
			this.logEvents = new CopyOnWriteArrayList<>();
			this.willStopMcpCallbacks = new AtomicInteger();
			this.didStartMcpCallbacks = new AtomicInteger();
			this.didStartMcp = new CountDownLatch(1);
			this.terminal = new CountDownLatch(1);
			this.globalResult = new AtomicReference<>();
			this.didStartFailure = didStartFailure;
		}

		@Override
		public void didStartMcpServer(@NonNull McpServer mcpServer) {
			requireNonNull(mcpServer);
			this.didStartMcpCallbacks.incrementAndGet();
			this.didStartMcp.countDown();
			if (this.didStartFailure != null)
				throw this.didStartFailure;
		}

		@Override
		public void willStopMcpServer(@NonNull McpServer mcpServer) {
			requireNonNull(mcpServer);
			this.willStopMcpCallbacks.incrementAndGet();
		}

		@Override
		public void didStopMcpServer(@NonNull McpServer mcpServer,
			@NonNull ShutdownComponentResult result) {
			requireNonNull(mcpServer);
			ShutdownComponentResult exactResult = requireNonNull(result);
			this.stopResults.add(exactResult);
		}

		@Override
		public void didStopSoklet(@NonNull Soklet soklet,
				@NonNull ShutdownResult result) {
			requireNonNull(soklet);
			this.globalResult.set(requireNonNull(result));
			this.terminal.countDown();
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			this.logEvents.add(requireNonNull(logEvent));
		}

		@NonNull
		private List<@NonNull ShutdownComponentDisposition> getStopOutcomes() {
			return this.stopResults.stream()
					.map(ShutdownComponentResult::
							getShutdownComponentDisposition).toList();
		}

		@NonNull
		private List<@NonNull ShutdownComponentResult> getStopResults() {
			return List.copyOf(this.stopResults);
		}

		@NonNull
		private List<@NonNull LogEvent> getLogEvents() {
			return List.copyOf(this.logEvents);
		}

		@NonNull
		private List<@NonNull Throwable> getStopFailures() {
			return this.stopResults.stream()
					.flatMap(result -> result.getThrowables().stream()).toList();
		}

		@Nullable
		private ShutdownResult getGlobalResult() {
			return this.globalResult.get();
		}

		private int getWillStopMcpCallbacks() {
			return this.willStopMcpCallbacks.get();
		}

		private int getDidStartMcpCallbacks() {
			return this.didStartMcpCallbacks.get();
		}

		private void awaitDidStartMcp() throws InterruptedException {
			Assertions.assertTrue(this.didStartMcp.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS),
					"The MCP did-start callback was not observed.");
		}

		private void awaitTerminal() throws InterruptedException {
			Assertions.assertTrue(this.terminal.await(
					WAIT.toNanos(), TimeUnit.NANOSECONDS),
					"The global lifecycle terminal callback was not observed.");
		}
	}

	@ThreadSafe
	private static final class FailingClosePublisher
			implements McpSubscriptionEventPublisher {
		@NonNull
		private final AtomicInteger subscribeAttempts = new AtomicInteger();
		@NonNull
		private final AtomicInteger closeAttempts = new AtomicInteger();

		@Override
		@NonNull
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			requireNonNull(listener);
			this.subscribeAttempts.incrementAndGet();
			return () -> {
				if (this.closeAttempts.incrementAndGet() == 1)
					throw new IllegalStateException(
							"expected first registration close failure");
			};
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
			requireNonNull(event);
		}

		private int getSubscribeAttempts() {
			return this.subscribeAttempts.get();
		}

		private int getCloseAttempts() {
			return this.closeAttempts.get();
		}
	}

	@ThreadSafe
	private static final class BlockingClosePublisher
			implements McpSubscriptionEventPublisher {
		@NonNull
		private final AtomicInteger closeAttempts = new AtomicInteger();
		@NonNull
		private final CountDownLatch closeEntered = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch closeRelease = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch closed = new CountDownLatch(1);

		@Override
		@NonNull
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			requireNonNull(listener);
			return () -> {
				this.closeAttempts.incrementAndGet();
				this.closeEntered.countDown();
				boolean interrupted = false;
				while (true) {
					try {
						this.closeRelease.await();
						break;
					} catch (InterruptedException exception) {
						interrupted = true;
					}
				}
				this.closed.countDown();
				if (interrupted)
					Thread.currentThread().interrupt();
			};
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
			requireNonNull(event);
		}

		private void awaitCloseEntered() throws InterruptedException {
			Assertions.assertTrue(this.closeEntered.await(5, TimeUnit.SECONDS),
					"The registration close did not enter its blocking boundary.");
		}

		private void releaseClose() {
			this.closeRelease.countDown();
		}

		private void awaitClosed() throws InterruptedException {
			Assertions.assertTrue(this.closed.await(5, TimeUnit.SECONDS),
					"The released registration close did not complete.");
		}

		private int getCloseAttempts() {
			return this.closeAttempts.get();
		}
	}

	@ThreadSafe
	private static final class FailingSubscribePublisher
			implements McpSubscriptionEventPublisher {
		@NonNull
		private final AtomicInteger subscribeAttempts = new AtomicInteger();
		@NonNull
		private final IllegalStateException firstSubscribeFailure =
				new IllegalStateException("expected first subscribe failure");

		@Override
		@NonNull
		public McpSubscriptionEventRegistration subscribe(
				@NonNull McpSubscriptionEventListener listener) {
			requireNonNull(listener);
			if (this.subscribeAttempts.incrementAndGet() == 1)
				throw this.firstSubscribeFailure;
			return () -> {};
		}

		@Override
		public void publish(@NonNull McpSubscriptionEvent event) {
			requireNonNull(event);
		}

		private int getSubscribeAttempts() {
			return this.subscribeAttempts.get();
		}

		@NonNull
		private IllegalStateException getFirstSubscribeFailure() {
			return this.firstSubscribeFailure;
		}
	}
}
