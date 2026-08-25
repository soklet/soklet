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
				McpShutdownOutcome.class, Long.class
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
				McpShutdownOutcome.class, Long.class
		}, shutdownsParameterType.getActualTypeArguments());

		Assertions.assertSame(McpMetricsSnapshot.emptyInstance(),
				McpMetricsSnapshot.emptyInstance());
		Assertions.assertTrue(McpMetricsSnapshot.emptyInstance()
				.getShutdowns().isEmpty());

		Map<McpShutdownOutcome, Long> source = new HashMap<>();
		source.put(McpShutdownOutcome.CLEAN, 2L);
		source.put(McpShutdownOutcome.RESIDUAL_HANDLERS, 0L);
		McpMetricsSnapshot snapshot = McpMetricsSnapshot.builder()
				.shutdowns(source)
				.build();
		source.put(McpShutdownOutcome.CLEAN, 99L);

		Assertions.assertEquals(Map.of(
				McpShutdownOutcome.CLEAN, 2L,
				McpShutdownOutcome.RESIDUAL_HANDLERS, 0L),
				snapshot.getShutdowns());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> snapshot.getShutdowns().put(
						McpShutdownOutcome.CLEAN, 3L));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().shutdowns(null).build());

		Map<McpShutdownOutcome, Long> nullKey = new HashMap<>();
		nullKey.put(null, 1L);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().shutdowns(nullKey).build());

		Map<McpShutdownOutcome, Long> nullValue = new HashMap<>();
		nullValue.put(McpShutdownOutcome.CLEAN, null);
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder().shutdowns(nullValue).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder().shutdowns(Map.of(
						McpShutdownOutcome.RESIDUAL_HANDLERS, -1L)).build());
	}

	@Test
	public void defaultCollectorAggregatesRendersFiltersAndResetsShutdowns() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		Assertions.assertTrue(collector.snapshot().orElseThrow()
				.getMcpMetrics().getShutdowns().isEmpty());
		Assertions.assertThrows(NullPointerException.class,
				() -> collector.didRecordMcpMetricsEvent(null));

		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN));
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN));
		collector.didRecordMcpMetricsEvent(McpMetricsEvent.serverStopped(
				McpShutdownOutcome.RESIDUAL_HANDLERS));
		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.handlerExecutionStarted());

		McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
				.getMcpMetrics();
		Assertions.assertEquals(Map.of(
				McpShutdownOutcome.CLEAN, 2L,
				McpShutdownOutcome.RESIDUAL_HANDLERS, 1L),
				retained.getShutdowns());

		collector.didRecordMcpMetricsEvent(
				McpMetricsEvent.serverStopped(McpShutdownOutcome.CLEAN));
		Assertions.assertEquals(2L,
				retained.getShutdowns().get(McpShutdownOutcome.CLEAN));
		Assertions.assertEquals(3L, collector.snapshot().orElseThrow()
				.getMcpMetrics().getShutdowns().get(McpShutdownOutcome.CLEAN));

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
				SHUTDOWN_METRIC_NAME + "{outcome=\"clean\"} 3\n"));
		Assertions.assertTrue(prometheus.contains(
				SHUTDOWN_METRIC_NAME
						+ "{outcome=\"residual_handlers\"} 1\n"));
		Assertions.assertEquals(Set.of(
				Map.of("outcome", "clean"),
				Map.of("outcome", "residual_handlers")), shutdownLabels,
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
										"outcome", "residual_handlers")))
						.build();
		String filtered = collector.snapshotText(residualOnly).orElseThrow();
		Assertions.assertTrue(filtered.contains(
				SHUTDOWN_METRIC_NAME
						+ "{outcome=\"residual_handlers\"} 1\n"));
		Assertions.assertFalse(filtered.contains("outcome=\"clean\""));
		Assertions.assertFalse(filtered.contains("soklet_http_"));

		String openMetrics = collector.snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.OPEN_METRICS_1_0)
						.build()).orElseThrow();
		Assertions.assertTrue(openMetrics.contains(
				SHUTDOWN_METRIC_NAME + "{outcome=\"clean\"} 3\n"));
		Assertions.assertTrue(openMetrics.contains(
				SHUTDOWN_METRIC_NAME
						+ "{outcome=\"residual_handlers\"} 1\n"));
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
				retained.getShutdowns().get(McpShutdownOutcome.CLEAN),
				"Reset must not mutate a retained point-in-time snapshot.");
	}

	@Test
	public void managedCleanStopEmitsOneMatchingLifecycleAndMetricsOutcome() {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/managed-clean");
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			soklet.start();
			soklet.stop();
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));

			soklet.stop();
			server.stop();
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void directCleanStopRecordsMetricsWithoutInventingLifecycleCallbacks() {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/direct-clean");
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			server.start();
			server.stop();
			server.stop();

			Assertions.assertTrue(observer.getStopOutcomes().isEmpty(),
					"Direct server lifecycle does not synthesize Soklet callbacks.");
			Assertions.assertEquals(List.of(McpShutdownOutcome.CLEAN),
					collector.getServerStopOutcomes());
			Assertions.assertEquals(Map.of(McpShutdownOutcome.CLEAN, 1L),
					collector.snapshot().orElseThrow().getMcpMetrics()
							.getShutdowns());
		} finally {
			server.stop();
			soklet.stop();
		}
	}

	@Test
	public void startupRollbackRecordsOneCleanLifecycleAndMetricsOutcome() {
		RuntimeException expectedFailure = new RuntimeException(
				"expected did-start failure");
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer =
				new RecordingLifecycleObserver(expectedFailure);
		McpServer server = newServer("/mcp/startup-rollback");
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			RuntimeException actualFailure = Assertions.assertThrows(
					RuntimeException.class, soklet::start);
			Assertions.assertSame(expectedFailure, actualFailure);
			Assertions.assertFalse(server.isStarted());
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));

			soklet.stop();
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void failedSubscriptionRegistrationCloseEmitsOnlyAfterSuccessfulRetry()
			throws Exception {
		FailingClosePublisher publisher = new FailingClosePublisher();
		McpEndpoint endpoint = subscriptionEndpoint(
				"/mcp/subscription-close-retry", publisher);
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = serverFor(endpoint);
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			soklet.start();
			Assertions.assertEquals(1, publisher.getSubscribeAttempts());

			Assertions.assertDoesNotThrow(soklet::stop);
			Assertions.assertEquals(1, publisher.getCloseAttempts());
			Assertions.assertEquals(1, observer.getStopFailures().size());
			Assertions.assertTrue(observer.getStopOutcomes().isEmpty());
			Assertions.assertTrue(collector.getServerStopOutcomes().isEmpty());
			Assertions.assertTrue(collector.snapshot().orElseThrow()
					.getMcpMetrics().getShutdowns().isEmpty());
			Assertions.assertTrue(runtimeBridge(server).getRuntimeState()
					.stopRequired());

			Assertions.assertDoesNotThrow(soklet::stop);
			Assertions.assertEquals(2, publisher.getCloseAttempts());
			Assertions.assertEquals(1, observer.getStopFailures().size());
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));
			Assertions.assertFalse(runtimeBridge(server).getRuntimeState()
					.stopRequired());

			soklet.stop();
			server.stop();
			Assertions.assertEquals(2, publisher.getCloseAttempts());
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));
		} finally {
			soklet.stop();
		}
	}

	@Test
	@Timeout(30)
	public void blockingSubscriptionRegistrationClosePublishesAfterLateCleanup()
			throws Exception {
		Duration shutdownTimeout = Duration.ofMillis(150);
		BlockingClosePublisher publisher = new BlockingClosePublisher();
		McpEndpoint endpoint = subscriptionEndpoint(
				"/mcp/subscription-close-blocking", publisher);
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = serverFor(List.of(endpoint), shutdownTimeout);
		Soklet soklet = newSoklet(server, collector, observer);
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			soklet.start();
			long stopStartedAt = System.nanoTime();
			Assertions.assertDoesNotThrow(soklet::stop);
			Duration stopDuration = Duration.ofNanos(
					System.nanoTime() - stopStartedAt);
			Assertions.assertTrue(stopDuration.compareTo(
					shutdownTimeout.plusSeconds(1)) < 0,
					() -> "Blocking registration cleanup exceeded its bounded "
							+ "shutdown budget: " + stopDuration);
			publisher.awaitCloseEntered();
			Assertions.assertEquals(1, publisher.getCloseAttempts());
			Assertions.assertEquals(1, observer.getWillStopMcpCallbacks());
			Assertions.assertEquals(1, observer.getStopFailures().size());
			Assertions.assertTrue(observer.getStopOutcomes().isEmpty());
			Assertions.assertTrue(collector.getServerStopOutcomes().isEmpty());
			Assertions.assertTrue(collector.snapshot().orElseThrow()
					.getMcpMetrics().getShutdowns().isEmpty());
			Assertions.assertTrue(bridge.getRuntimeState().stopRequired());

			publisher.releaseClose();
			publisher.awaitClosed();
			awaitStopRequired(bridge, false);
			Assertions.assertTrue(collector.getServerStopOutcomes().isEmpty(),
					"Asynchronous cleanup alone must not duplicate delivery.");

			soklet.stop();
			Assertions.assertEquals(1, publisher.getCloseAttempts(),
					"A completed registration close must not be invoked again.");
			Assertions.assertEquals(2, observer.getWillStopMcpCallbacks());
			Assertions.assertEquals(1, observer.getStopFailures().size());
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));

			soklet.stop();
			server.stop();
			Assertions.assertEquals(2, observer.getWillStopMcpCallbacks());
			Assertions.assertEquals(1, publisher.getCloseAttempts());
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));
		} finally {
			publisher.releaseClose();
			soklet.stop();
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void unexpectedListenerTerminationCleanupAndRestartHaveExactParity()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/unexpected-termination");
		Soklet soklet = newSoklet(server, collector, observer);
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			soklet.start();
			terminateUnexpectedly(eventLoop(bridge));
			Assertions.assertFalse(bridge.getRuntimeState().started());
			Assertions.assertTrue(bridge.getRuntimeState().stopRequired());

			soklet.stop();
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));
			Assertions.assertFalse(bridge.getRuntimeState().stopRequired());
			soklet.stop();
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));

			soklet.start();
			soklet.stop();
			assertShutdownParity(observer, collector, List.of(
					McpShutdownOutcome.CLEAN,
					McpShutdownOutcome.CLEAN));
		} finally {
			soklet.stop();
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void directRestartNormalizesUnexpectedGenerationExactlyOnce()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/direct-unexpected-restart");
		Soklet soklet = newSoklet(server, collector, observer);
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			server.start();
			terminateUnexpectedly(eventLoop(bridge));
			Assertions.assertTrue(bridge.getRuntimeState().stopRequired());

			server.start();
			Assertions.assertTrue(server.isStarted());
			Assertions.assertTrue(observer.getStopOutcomes().isEmpty());
			Assertions.assertEquals(List.of(McpShutdownOutcome.CLEAN),
					collector.getServerStopOutcomes());
			Assertions.assertEquals(Map.of(McpShutdownOutcome.CLEAN, 1L),
					collector.snapshot().orElseThrow().getMcpMetrics()
							.getShutdowns());

			server.stop();
			server.stop();
			Assertions.assertTrue(observer.getStopOutcomes().isEmpty(),
					"Direct restart and stop must not synthesize Soklet callbacks.");
			Assertions.assertEquals(List.of(
					McpShutdownOutcome.CLEAN,
					McpShutdownOutcome.CLEAN),
					collector.getServerStopOutcomes());
			Assertions.assertEquals(Map.of(McpShutdownOutcome.CLEAN, 2L),
					collector.snapshot().orElseThrow().getMcpMetrics()
							.getShutdowns());
		} finally {
			server.stop();
			soklet.stop();
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void managedRestartNormalizesUnexpectedGenerationExactlyOnce()
			throws Exception {
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpServer server = newServer("/mcp/managed-unexpected-restart");
		Soklet soklet = newSoklet(server, collector, observer);
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			soklet.start();
			terminateUnexpectedly(eventLoop(bridge));
			Assertions.assertFalse(server.isStarted());
			Assertions.assertTrue(bridge.getRuntimeState().stopRequired());

			soklet.start();
			Assertions.assertTrue(server.isStarted());
			Assertions.assertEquals(0, observer.getWillStopMcpCallbacks(),
					"Restart normalization must not synthesize stop intent.");
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));

			soklet.start();
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));

			soklet.stop();
			Assertions.assertEquals(1, observer.getWillStopMcpCallbacks());
			Assertions.assertTrue(observer.getStopFailures().isEmpty());
			assertShutdownParity(observer, collector, List.of(
					McpShutdownOutcome.CLEAN,
					McpShutdownOutcome.CLEAN));

			soklet.stop();
			assertShutdownParity(observer, collector, List.of(
					McpShutdownOutcome.CLEAN,
					McpShutdownOutcome.CLEAN));
		} finally {
			soklet.stop();
			if (bridge.getRuntimeState().stopRequired())
				bridge.stop();
		}
	}

	@Test
	public void failedStartCleanupDoesNotEmitPhantomServerStoppedEvent()
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
		McpServer server = serverFor(List.of(firstEndpoint, secondEndpoint));
		Soklet soklet = newSoklet(server, collector, observer);
		McpServerRuntimeBridge bridge = runtimeBridge(server);

		try {
			IllegalStateException startupFailure = Assertions.assertThrows(
					IllegalStateException.class, soklet::start);
			Assertions.assertSame(secondPublisher.getFirstSubscribeFailure(),
					startupFailure);
			Assertions.assertFalse(server.isStarted());
			Assertions.assertTrue(bridge.getRuntimeState().stopRequired());
			Assertions.assertEquals(1, firstPublisher.getCloseAttempts());
			assertNoMcpStopCallbacks(observer);
			Assertions.assertTrue(collector.getServerStopOutcomes().isEmpty());
			Assertions.assertTrue(collector.snapshot().orElseThrow()
					.getMcpMetrics().getShutdowns().isEmpty());

			Assertions.assertDoesNotThrow(soklet::stop);
			Assertions.assertFalse(bridge.getRuntimeState().stopRequired());
			Assertions.assertEquals(2, firstPublisher.getCloseAttempts(),
					"Managed cleanup must retry the failed registration close.");
			assertNoMcpStopCallbacks(observer);
			Assertions.assertTrue(collector.getServerStopOutcomes().isEmpty(),
					"Cleanup of a never-started generation must not emit ServerStopped.");
			Assertions.assertTrue(collector.snapshot().orElseThrow()
					.getMcpMetrics().getShutdowns().isEmpty());

			soklet.start();
			Assertions.assertTrue(server.isStarted());
			Assertions.assertEquals(2, firstPublisher.getSubscribeAttempts());
			Assertions.assertEquals(2, secondPublisher.getSubscribeAttempts());
			Assertions.assertTrue(collector.getServerStopOutcomes().isEmpty(),
					"A never-started generation must not emit ServerStopped.");
			Assertions.assertTrue(observer.getStopOutcomes().isEmpty());

			soklet.stop();
			Assertions.assertEquals(3, firstPublisher.getCloseAttempts());
			Assertions.assertEquals(1, observer.getWillStopMcpCallbacks());
			Assertions.assertTrue(observer.getStopFailures().isEmpty());
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));

			soklet.stop();
			Assertions.assertEquals(1, observer.getWillStopMcpCallbacks());
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.CLEAN));
		} finally {
			soklet.stop();
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
							sokletReference.get().isStarted())
							.get(2, TimeUnit.SECONDS));
				} catch (Throwable throwable) {
					callbackFailure.set(throwable);
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
			soklet.stop();
			Assertions.assertNull(callbackFailure.get(),
					"The shutdown callback blocked a concurrent diagnostics read.");
			Assertions.assertEquals(McpServerStatus.STOPPED,
					observedStatus.get());
			Assertions.assertEquals(false, observedSokletStarted.get());
			Assertions.assertEquals(List.of(McpShutdownOutcome.CLEAN),
					observer.getStopOutcomes());
		} finally {
			soklet.stop();
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void shutdownMetricsCollectorFailureIsContainedAndLoggedOnce() {
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
			Assertions.assertDoesNotThrow(soklet::stop);
			Assertions.assertEquals(List.of(McpShutdownOutcome.CLEAN),
					observer.getStopOutcomes());
			Assertions.assertEquals(1, attempts.get());
			List<LogEvent> failures = observer.getLogEvents().stream()
					.filter(event -> event.getLogEventType()
							== LogEventType.METRICS_COLLECTOR_FAILED)
					.toList();
			Assertions.assertEquals(1, failures.size());
			Assertions.assertSame(expectedFailure,
					failures.get(0).getThrowable().orElseThrow());
			Assertions.assertTrue(failures.get(0).getRequest().isEmpty());
			Assertions.assertTrue(failures.get(0).getResourceMethod().isEmpty());
			Assertions.assertTrue(failures.get(0).getMarshaledResponse().isEmpty());

			soklet.stop();
			Assertions.assertEquals(1, attempts.get());
			Assertions.assertEquals(1, observer.getLogEvents().stream()
					.filter(event -> event.getLogEventType()
							== LogEventType.METRICS_COLLECTOR_FAILED)
					.count());
		} finally {
			soklet.stop();
		}
	}

	@Test
	@Timeout(30)
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
						"shutdown-observability-test", "4.0.0-SNAPSHOT").build())
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
				.shutdownTimeout(Duration.ofMillis(150))
				.build();
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		Soklet soklet = newSoklet(server, collector, observer);
		CompletableFuture<HttpResponse<String>> request = null;

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow()
					.getPort();
			request = callTool(port, path, toolName);
			Assertions.assertTrue(handlerEntered.await(5, TimeUnit.SECONDS),
					"The residual fixture handler did not enter.");

			soklet.stop();
			Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS),
					"Shutdown did not interrupt the held handler.");
			Assertions.assertEquals(
					McpServerStatus.STOPPED_WITH_RESIDUAL_HANDLERS,
					server.getDiagnostics().getStatus());
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.RESIDUAL_HANDLERS));
			McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
					.getMcpMetrics();

			soklet.stop();
			server.stop();
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.RESIDUAL_HANDLERS));

			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(5, TimeUnit.SECONDS),
					"The released residual fixture handler did not exit.");
			awaitStatus(server, McpServerStatus.STOPPED);
			assertShutdownParity(observer, collector,
					List.of(McpShutdownOutcome.RESIDUAL_HANDLERS));
			Assertions.assertEquals(Map.of(
					McpShutdownOutcome.RESIDUAL_HANDLERS, 1L),
					retained.getShutdowns(),
					"A retained residual snapshot must remain unchanged.");
		} finally {
			releaseHandler.countDown();
			if (request != null)
				request.cancel(true);
			soklet.stop();
		}
	}

	private static void assertShutdownParity(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull RecordingMetricsCollector collector,
			@NonNull List<@NonNull McpShutdownOutcome> expectedOutcomes) {
		requireNonNull(observer);
		requireNonNull(collector);
		requireNonNull(expectedOutcomes);
		Assertions.assertEquals(expectedOutcomes, observer.getStopOutcomes());
		Assertions.assertEquals(expectedOutcomes,
				collector.getServerStopOutcomes());

		Map<McpShutdownOutcome, Long> expectedCounts = new HashMap<>();
		for (McpShutdownOutcome outcome : expectedOutcomes)
			expectedCounts.merge(outcome, 1L, Long::sum);
		Assertions.assertEquals(expectedCounts,
				collector.snapshot().orElseThrow().getMcpMetrics()
						.getShutdowns());
	}

	private static void assertNoMcpStopCallbacks(
			@NonNull RecordingLifecycleObserver observer) {
		requireNonNull(observer);
		Assertions.assertEquals(0, observer.getWillStopMcpCallbacks());
		Assertions.assertTrue(observer.getStopOutcomes().isEmpty());
		Assertions.assertTrue(observer.getStopFailures().isEmpty());
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
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector)
				.lifecycleObserver(observer)
				.build());
	}

	@NonNull
	private static McpServer newServer(@NonNull String path) {
		McpEndpoint endpoint = McpEndpoint.withPath(requireNonNull(path))
				.serverInformation(McpImplementation.withNameAndVersion(
						"shutdown-observability-test", "4.0.0-SNAPSHOT").build())
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
		McpServer.Builder builder = McpServer.withPort(0)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.copyOf(requireNonNull(endpoints))))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST));
		if (shutdownTimeout != null)
			builder.shutdownTimeout(shutdownTimeout);
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
						"shutdown-observability-test", "4.0.0-SNAPSHOT").build())
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

	private static final class RecordingMetricsCollector
			implements MetricsCollector {
		@NonNull
		private final DefaultMetricsCollector delegate;
		@NonNull
		private final List<@NonNull McpMetricsEvent> events;

		private RecordingMetricsCollector() {
			this.delegate = DefaultMetricsCollector.defaultInstance();
			this.events = new CopyOnWriteArrayList<>();
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			McpMetricsEvent requiredEvent = requireNonNull(event);
			this.events.add(requiredEvent);
			this.delegate.didRecordMcpMetricsEvent(requiredEvent);
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
		private List<@NonNull McpShutdownOutcome> getServerStopOutcomes() {
			return this.events.stream()
					.filter(McpMetricsEvent.ServerStopped.class::isInstance)
					.map(McpMetricsEvent.ServerStopped.class::cast)
					.map(McpMetricsEvent.ServerStopped::getOutcome)
					.toList();
		}
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		@NonNull
		private final List<@NonNull McpShutdownOutcome> stopOutcomes;
		@NonNull
		private final List<@NonNull LogEvent> logEvents;
		@NonNull
		private final List<@NonNull Throwable> stopFailures;
		@NonNull
		private final AtomicInteger willStopMcpCallbacks;
		@Nullable
		private final RuntimeException didStartFailure;

		private RecordingLifecycleObserver() {
			this(null);
		}

		private RecordingLifecycleObserver(
				@Nullable RuntimeException didStartFailure) {
			this.stopOutcomes = new CopyOnWriteArrayList<>();
			this.logEvents = new CopyOnWriteArrayList<>();
			this.stopFailures = new CopyOnWriteArrayList<>();
			this.willStopMcpCallbacks = new AtomicInteger();
			this.didStartFailure = didStartFailure;
		}

		@Override
		public void didStartMcpServer(@NonNull McpServer mcpServer) {
			requireNonNull(mcpServer);
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
				@NonNull McpShutdownOutcome shutdownOutcome) {
			requireNonNull(mcpServer);
			this.stopOutcomes.add(requireNonNull(shutdownOutcome));
		}

		@Override
		public void didFailToStopMcpServer(@NonNull McpServer mcpServer,
				@NonNull Throwable throwable) {
			requireNonNull(mcpServer);
			this.stopFailures.add(requireNonNull(throwable));
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			this.logEvents.add(requireNonNull(logEvent));
		}

		@NonNull
		private List<@NonNull McpShutdownOutcome> getStopOutcomes() {
			return List.copyOf(this.stopOutcomes);
		}

		@NonNull
		private List<@NonNull LogEvent> getLogEvents() {
			return List.copyOf(this.logEvents);
		}

		@NonNull
		private List<@NonNull Throwable> getStopFailures() {
			return List.copyOf(this.stopFailures);
		}

		private int getWillStopMcpCallbacks() {
			return this.willStopMcpCallbacks.get();
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
