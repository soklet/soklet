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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * Focused public and live-runtime coverage for MCP handler-capacity metrics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpHandlerMetricsObservabilityTests {
	private static final String HOST = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final String ACTIVE_HANDLER_METRIC_NAME =
			"soklet_mcp_handler_executions_active";
	private static final String HANDLER_QUEUE_METRIC_NAME =
			"soklet_mcp_handler_queue_depth";
	private static final String HANDLER_REJECTION_METRIC_NAME =
			"soklet_mcp_handler_capacity_rejections_total";
	private static final Set<String> HANDLER_METRIC_NAMES = Set.of(
			ACTIVE_HANDLER_METRIC_NAME,
			HANDLER_QUEUE_METRIC_NAME,
			HANDLER_REJECTION_METRIC_NAME);

	@Test
	public void snapshotContractUsesBoxedNonnegativeHandlerValues()
			throws Exception {
		assertBoxedSnapshotMethod("getActiveHandlerExecutions",
				"activeHandlerExecutions");
		assertBoxedSnapshotMethod("getHandlerQueueDepth",
				"handlerQueueDepth");
		assertBoxedSnapshotMethod("getHandlerCapacityRejections",
				"handlerCapacityRejections");

		McpMetricsSnapshot empty = McpMetricsSnapshot.emptyInstance();
		Assertions.assertSame(empty, McpMetricsSnapshot.emptyInstance());
		Assertions.assertEquals(0L, empty.getActiveHandlerExecutions());
		Assertions.assertEquals(0L, empty.getHandlerQueueDepth());
		Assertions.assertEquals(0L, empty.getHandlerCapacityRejections());

		McpMetricsSnapshot snapshot = McpMetricsSnapshot.builder()
				.activeHandlerExecutions(2L)
				.handlerQueueDepth(3L)
				.handlerCapacityRejections(5L)
				.shutdowns(Map.of(ParticipantShutdownDisposition.GRACEFUL_TERMINATION, 7L))
				.build();
		Assertions.assertEquals(2L, snapshot.getActiveHandlerExecutions());
		Assertions.assertEquals(3L, snapshot.getHandlerQueueDepth());
		Assertions.assertEquals(5L, snapshot.getHandlerCapacityRejections());
		Assertions.assertEquals(Map.of(ParticipantShutdownDisposition.GRACEFUL_TERMINATION, 7L),
				snapshot.getShutdowns());

		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder()
						.activeHandlerExecutions(null).build());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder()
						.handlerQueueDepth(null).build());
		Assertions.assertThrows(NullPointerException.class,
				() -> McpMetricsSnapshot.builder()
						.handlerCapacityRejections(null).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder()
						.activeHandlerExecutions(-1L).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder()
						.handlerQueueDepth(-1L).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpMetricsSnapshot.builder()
						.handlerCapacityRejections(-1L).build());
	}

	@Test
	public void defaultCollectorAggregatesConfiguredZerosRendersFiltersAndResets() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		McpServer server = serverFor(List.of(emptyEndpoint("/mcp/collector")),
				1, 1, Duration.ofSeconds(5));
		Soklet soklet = newSoklet(server, collector,
				new SilentLifecycleObserver());

		try {
			soklet.start();
			McpMetricsSnapshot empty = collector.snapshot().orElseThrow()
					.getMcpMetrics();
			assertHandlerSnapshot(empty, 0L, 0L, 0L);
			String configuredZeroText = prometheus(collector);
			assertSample(configuredZeroText, ACTIVE_HANDLER_METRIC_NAME, 0L);
			assertSample(configuredZeroText, HANDLER_QUEUE_METRIC_NAME, 0L);
			assertSample(configuredZeroText, HANDLER_REJECTION_METRIC_NAME, 0L);

			collector.didRecordMcpMetricsEvent(
					McpMetricsEvent.handlerExecutionStarted());
			collector.didRecordMcpMetricsEvent(
					McpMetricsEvent.handlerExecutionStarted());
			collector.didRecordMcpMetricsEvent(
					McpMetricsEvent.handlerExecutionFinished());
			collector.didRecordMcpMetricsEvent(
					McpMetricsEvent.handlerQueued());
			collector.didRecordMcpMetricsEvent(
					McpMetricsEvent.handlerQueued());
			collector.didRecordMcpMetricsEvent(
					McpMetricsEvent.handlerDequeued());
			for (int index = 0; index < 3; index++)
				collector.didRecordMcpMetricsEvent(
						McpMetricsEvent.handlerCapacityRejected());

			McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
					.getMcpMetrics();
			assertHandlerSnapshot(retained, 1L, 1L, 3L);

			Set<Map<String, String>> labels =
					java.util.concurrent.ConcurrentHashMap.newKeySet();
			MetricsCollector.SnapshotTextOptions prometheusOptions =
					MetricsCollector.SnapshotTextOptions.withMetricsFormat(
							MetricsCollector.MetricsFormat.PROMETHEUS)
							.metricFilter(sample -> {
								if (HANDLER_METRIC_NAMES.contains(sample.getName()))
									labels.add(sample.getLabels());
								return true;
							})
							.build();
			String text = collector.snapshotText(prometheusOptions).orElseThrow();
			assertMetricType(text, ACTIVE_HANDLER_METRIC_NAME,
					"Currently occupied MCP handler-execution slots", "gauge");
			assertMetricType(text, HANDLER_QUEUE_METRIC_NAME,
					"MCP application requests waiting for a handler slot", "gauge");
			assertMetricType(text, HANDLER_REJECTION_METRIC_NAME,
					"Total MCP requests rejected because the handler queue was full",
					"counter");
			assertSample(text, ACTIVE_HANDLER_METRIC_NAME, 1L);
			assertSample(text, HANDLER_QUEUE_METRIC_NAME, 1L);
			assertSample(text, HANDLER_REJECTION_METRIC_NAME, 3L);
			Assertions.assertEquals(Set.of(Map.of()), labels,
					"Handler metric families must not expose labels.");
			for (String metricName : HANDLER_METRIC_NAMES)
				Assertions.assertFalse(text.contains(metricName + "{"), text);
			Assertions.assertFalse(text.contains("# EOF"));

			MetricsCollector.SnapshotTextOptions queueOnly =
					MetricsCollector.SnapshotTextOptions.withMetricsFormat(
							MetricsCollector.MetricsFormat.PROMETHEUS)
							.metricFilter(sample -> sample.getName()
									.equals(HANDLER_QUEUE_METRIC_NAME))
							.build();
			String filtered = collector.snapshotText(queueOnly).orElseThrow();
			assertSample(filtered, HANDLER_QUEUE_METRIC_NAME, 1L);
			Assertions.assertFalse(filtered.contains(ACTIVE_HANDLER_METRIC_NAME));
			Assertions.assertFalse(filtered.contains(HANDLER_REJECTION_METRIC_NAME));
			Assertions.assertFalse(filtered.contains("soklet_http_"));

			String openMetrics = collector.snapshotText(
					MetricsCollector.SnapshotTextOptions.withMetricsFormat(
							MetricsCollector.MetricsFormat.OPEN_METRICS_1_0)
							.build()).orElseThrow();
			assertSample(openMetrics, ACTIVE_HANDLER_METRIC_NAME, 1L);
			assertSample(openMetrics, HANDLER_QUEUE_METRIC_NAME, 1L);
			assertSample(openMetrics, HANDLER_REJECTION_METRIC_NAME, 3L);
			Assertions.assertTrue(openMetrics.endsWith("# EOF\n"));
			Assertions.assertEquals(1, occurrences(openMetrics, "# EOF\n"));

			collector.reset();
			assertHandlerSnapshot(collector.snapshot().orElseThrow()
					.getMcpMetrics(), 1L, 1L, 0L);
			String liveResetText = prometheus(collector);
			assertSample(liveResetText, ACTIVE_HANDLER_METRIC_NAME, 1L);
			assertSample(liveResetText, HANDLER_QUEUE_METRIC_NAME, 1L);
			assertSample(liveResetText, HANDLER_REJECTION_METRIC_NAME, 0L);
			assertHandlerSnapshot(retained, 1L, 1L, 3L);

			collector.didRecordMcpMetricsEvent(
					McpMetricsEvent.handlerExecutionFinished());
			collector.didRecordMcpMetricsEvent(
					McpMetricsEvent.handlerDequeued());
			assertHandlerSnapshot(collector.snapshot().orElseThrow()
					.getMcpMetrics(), 0L, 0L, 0L);

			collector.reset();
			assertHandlerSnapshot(collector.snapshot().orElseThrow()
					.getMcpMetrics(), 0L, 0L, 0L);
			String resetText = prometheus(collector);
			assertSample(resetText, ACTIVE_HANDLER_METRIC_NAME, 0L);
			assertSample(resetText, HANDLER_QUEUE_METRIC_NAME, 0L);
			assertSample(resetText, HANDLER_REJECTION_METRIC_NAME, 0L);
			assertHandlerSnapshot(retained, 1L, 1L, 3L);
		} finally {
			soklet.close();
		}
	}

	@Test
	@Timeout(120)
	public void sokletOwnedSaturatedListenerEmitsExactServerWideTransitions()
			throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpEndpoint firstEndpoint = endpoint("/mcp/capacity-first",
				"metrics.first", (request, arguments, features) -> {
					int invocation = invocations.incrementAndGet();
					if (invocation == 1) {
						firstEntered.countDown();
						Assertions.assertTrue(releaseFirst.await(10,
								TimeUnit.SECONDS),
								"Timed out waiting to release the first handler");
					}
					return McpCompleteResult.fromToolText("first-" + invocation);
				});
		McpEndpoint secondEndpoint = endpoint("/mcp/capacity-second",
				"metrics.second", (request, arguments, features) -> {
					invocations.incrementAndGet();
					return McpCompleteResult.fromToolText("second");
				});
		McpServer server = serverFor(List.of(firstEndpoint, secondEndpoint),
				1, 1, Duration.ofSeconds(5));
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		Soklet soklet = newSoklet(server, collector,
				new SilentLifecycleObserver());
		CompletableFuture<HttpResponse<String>> first = null;
		CompletableFuture<HttpResponse<String>> second = null;

		try {
			soklet.start();
			int port = port(server);
			first = callTool(port, "/mcp/capacity-first", "capacity-first",
					"metrics.first");
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS),
					"The first capacity handler did not enter.");

			second = callTool(port, "/mcp/capacity-first", "capacity-second",
					"metrics.first");
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 1L
							&& metrics.getHandlerQueueDepth() == 1L);
			McpServerDiagnostics queuedDiagnostics = server.getDiagnostics();
			assertHandlerDiagnostics(queuedDiagnostics, 1, 1);
			HttpResponse<String> rejected = callTool(port,
					"/mcp/capacity-second", "capacity-third", "metrics.second")
					.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(503, rejected.statusCode(), rejected.body());
			assertContains(rejected.body(), "\"code\":-32603");
			assertContains(rejected.body(), "\"message\":\"Internal error\"");
			Assertions.assertEquals(1, invocations.get(),
					"A capacity-rejected request must not enter a handler.");

			releaseFirst.countDown();
			Assertions.assertEquals(200,
					requireNonNull(first).get(5, TimeUnit.SECONDS).statusCode());
			Assertions.assertEquals(200,
					requireNonNull(second).get(5, TimeUnit.SECONDS).statusCode());
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 0L
							&& metrics.getHandlerQueueDepth() == 0L
							&& metrics.getHandlerCapacityRejections() == 1L);
			assertHandlerDiagnostics(server.getDiagnostics(), 0, 0);
			assertHandlerDiagnostics(queuedDiagnostics, 1, 1);
			Assertions.assertEquals(2, invocations.get());
			Assertions.assertEquals(List.of(
					"HandlerExecutionStarted",
					"HandlerQueued",
					"HandlerCapacityRejected",
					"HandlerExecutionFinished",
					"HandlerDequeued",
					"HandlerExecutionStarted",
					"HandlerExecutionFinished"),
					collector.handlerTransitionNames());
		} finally {
			releaseFirst.countDown();
			if (first != null)
				first.cancel(true);
			if (second != null)
				second.cancel(true);
			soklet.close();
			soklet.close();
		}
	}

	@Test
	@Timeout(120)
	public void queuedDeadlineDequeuesWithoutExecutionAndRetainsActiveGauge()
			throws Exception {
		ExecutorService probeExecutor = Executors.newFixedThreadPool(2);
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch activeInterrupted = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		CountDownLatch activeExited = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpEndpoint endpoint = endpoint("/mcp/deadline", "metrics.deadline",
				(request, arguments, features) -> {
					invocations.incrementAndGet();
					activeEntered.countDown();
					try {
						awaitIgnoringInterrupts(releaseActive, activeInterrupted);
						return McpCompleteResult.fromToolText("released");
					} finally {
						activeExited.countDown();
					}
				});
		McpServer server = serverFor(List.of(endpoint), 1, 1,
				Duration.ofSeconds(1));
		AtomicReference<Soklet> sokletReference = new AtomicReference<>();
		LockProbingMetricsCollector collector = new LockProbingMetricsCollector(
				probeExecutor, server, sokletReference);
		Soklet soklet = newSoklet(server, collector,
				new SilentLifecycleObserver());
		sokletReference.set(soklet);
		CompletableFuture<HttpResponse<String>> active = null;
		CompletableFuture<HttpResponse<String>> queued = null;

		try {
			soklet.start();
			int port = port(server);
			active = callTool(port, "/mcp/deadline", "deadline-active",
					"metrics.deadline");
			Assertions.assertTrue(activeEntered.await(5, TimeUnit.SECONDS),
					"The active deadline handler did not enter.");
			queued = callTool(port, "/mcp/deadline", "deadline-queued",
					"metrics.deadline");
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 1L
							&& metrics.getHandlerQueueDepth() == 1L);
			McpServerDiagnostics queuedDiagnostics = server.getDiagnostics();
			assertHandlerDiagnostics(queuedDiagnostics, 1, 1);

			HttpResponse<String> activeResponse = requireNonNull(active)
					.get(5, TimeUnit.SECONDS);
			HttpResponse<String> queuedResponse = requireNonNull(queued)
					.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(504, activeResponse.statusCode(),
					activeResponse.body());
			Assertions.assertEquals(503, queuedResponse.statusCode(),
					queuedResponse.body());
			assertContains(queuedResponse.body(), "\"code\":-32603");
			Assertions.assertTrue(activeInterrupted.await(5, TimeUnit.SECONDS),
					"Deadline did not interrupt the active handler.");
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 1L
							&& metrics.getHandlerQueueDepth() == 0L);
			assertHandlerDiagnostics(server.getDiagnostics(), 1, 0);
			collector.awaitProbeCount(1);
			Assertions.assertNull(collector.probeFailure(),
					"Queued deadline metrics blocked lifecycle-state probes.");
			Assertions.assertEquals(List.of("HandlerDequeued"),
					collector.probedTransitionNames());
			Assertions.assertEquals(List.of(McpServerStatus.RUNNING),
					collector.observedStatuses());
			Assertions.assertEquals(List.of(true),
					collector.observedSokletStarted());
			Assertions.assertEquals(1, invocations.get(),
					"The expired queued request must never enter a handler.");
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerExecutionStarted.class));
			Assertions.assertEquals(0,
					collector.count(McpMetricsEvent.HandlerExecutionFinished.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerQueued.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerDequeued.class));
			Assertions.assertEquals(0,
					collector.count(McpMetricsEvent.HandlerCapacityRejected.class));

			releaseActive.countDown();
			Assertions.assertTrue(activeExited.await(5, TimeUnit.SECONDS),
					"The released deadline handler did not exit.");
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 0L);
			assertHandlerDiagnostics(server.getDiagnostics(), 0, 0);
			assertHandlerDiagnostics(queuedDiagnostics, 1, 1);
			collector.awaitProbeCount(2);
			Assertions.assertNull(collector.probeFailure());
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerExecutionFinished.class));
		} finally {
			releaseActive.countDown();
			if (active != null)
				active.cancel(true);
			if (queued != null)
				queued.cancel(true);
			soklet.close();
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	@Timeout(120)
	public void queuedDisconnectDequeuesWithoutStartingHandler()
			throws Exception {
		ExecutorService probeExecutor = Executors.newFixedThreadPool(2);
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpEndpoint endpoint = endpoint("/mcp/disconnect", "metrics.disconnect",
				(request, arguments, features) -> {
					invocations.incrementAndGet();
					activeEntered.countDown();
					Assertions.assertTrue(releaseActive.await(10,
							TimeUnit.SECONDS),
							"Timed out waiting to release the active handler");
					return McpCompleteResult.fromToolText("released");
				});
		McpServer server = serverFor(List.of(endpoint), 1, 1,
				Duration.ofSeconds(5));
		AtomicReference<Soklet> sokletReference = new AtomicReference<>();
		LockProbingMetricsCollector collector = new LockProbingMetricsCollector(
				probeExecutor, server, sokletReference);
		Soklet soklet = newSoklet(server, collector,
				new SilentLifecycleObserver());
		sokletReference.set(soklet);
		CompletableFuture<HttpResponse<String>> active = null;
		Socket queued = null;

		try {
			soklet.start();
			int port = port(server);
			active = callTool(port, "/mcp/disconnect", "disconnect-active",
					"metrics.disconnect");
			Assertions.assertTrue(activeEntered.await(5, TimeUnit.SECONDS),
					"The active disconnect handler did not enter.");
			queued = openRawToolCall(port, "/mcp/disconnect",
					"disconnect-queued", "metrics.disconnect");
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 1L
							&& metrics.getHandlerQueueDepth() == 1L);
			McpServerDiagnostics queuedDiagnostics = server.getDiagnostics();
			assertHandlerDiagnostics(queuedDiagnostics, 1, 1);
			queued.setSoLinger(true, 0);
			queued.close();
			queued = null;

			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 1L
							&& metrics.getHandlerQueueDepth() == 0L);
			assertHandlerDiagnostics(server.getDiagnostics(), 1, 0);
			collector.awaitProbeCount(1);
			Assertions.assertNull(collector.probeFailure(),
					"Disconnect metrics blocked lifecycle-state probes.");
			Assertions.assertEquals(List.of("HandlerDequeued"),
					collector.probedTransitionNames());
			Assertions.assertEquals(List.of(McpServerStatus.RUNNING),
					collector.observedStatuses());
			Assertions.assertEquals(List.of(true),
					collector.observedSokletStarted());
			Assertions.assertEquals(1, invocations.get());
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerExecutionStarted.class));
			Assertions.assertEquals(0,
					collector.count(McpMetricsEvent.HandlerExecutionFinished.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerQueued.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerDequeued.class));
			Assertions.assertEquals(0,
					collector.count(McpMetricsEvent.HandlerCapacityRejected.class));

			releaseActive.countDown();
			Assertions.assertEquals(200,
					requireNonNull(active).get(5, TimeUnit.SECONDS).statusCode());
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 0L);
			assertHandlerDiagnostics(server.getDiagnostics(), 0, 0);
			assertHandlerDiagnostics(queuedDiagnostics, 1, 1);
			collector.awaitProbeCount(2);
			Assertions.assertNull(collector.probeFailure());
		} finally {
			releaseActive.countDown();
			if (queued != null)
				queued.close();
			if (active != null)
				active.cancel(true);
			soklet.close();
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void managedResidualShutdownDequeuesAndFreezesGaugeAcrossLateExit()
			throws Exception {
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch activeInterrupted = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		CountDownLatch activeExited = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpEndpoint endpoint = endpoint("/mcp/residual-handler",
				"metrics.residual", (request, arguments, features) -> {
					invocations.incrementAndGet();
					activeEntered.countDown();
					try {
						awaitIgnoringInterrupts(releaseActive, activeInterrupted);
						return McpCompleteResult.fromToolText("released");
					} finally {
						activeExited.countDown();
					}
				});
		McpServer server = serverFor(List.of(endpoint), 1, 1,
				Duration.ofSeconds(10));
		RecordingMetricsCollector collector = new RecordingMetricsCollector();
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		Soklet soklet = newSoklet(server, collector, observer,
				shortShutdownPolicy());
		CompletableFuture<HttpResponse<String>> active = null;
		CompletableFuture<HttpResponse<String>> queued = null;

		try {
			soklet.start();
			int port = port(server);
			active = callTool(port, "/mcp/residual-handler", "residual-active",
					"metrics.residual");
			Assertions.assertTrue(activeEntered.await(5, TimeUnit.SECONDS),
					"The residual handler did not enter.");
			queued = callTool(port, "/mcp/residual-handler", "residual-queued",
					"metrics.residual");
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 1L
							&& metrics.getHandlerQueueDepth() == 1L);
			assertHandlerDiagnostics(server.getDiagnostics(), 1, 1);

			ShutdownIncompleteException stopFailure = Assertions.assertThrows(
					ShutdownIncompleteException.class, soklet::close);
			InternalShutdownResult shutdownResult =
					stopFailure.getInternalShutdownResult();
			Assertions.assertSame(shutdownResult,
					lifecycleAdapter(server).result().orElseThrow());
			Assertions.assertSame(shutdownResult,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertTrue(activeInterrupted.await(5, TimeUnit.SECONDS),
					"Shutdown did not interrupt the residual handler.");
			observer.awaitTerminal();
			McpServerDiagnostics residualDiagnostics = server.getDiagnostics();
			Assertions.assertEquals(
					McpServerStatus.RESIDUAL_ACTIVITY,
					residualDiagnostics.getStatus());
			assertHandlerDiagnostics(residualDiagnostics, 1, 0);
			awaitMetrics(collector, metrics -> metrics.getShutdowns().equals(Map.of(
					ParticipantShutdownDisposition.RESIDUAL_ACTIVITY, 1L)));
			McpMetricsSnapshot retained = collector.snapshot().orElseThrow()
					.getMcpMetrics();
			assertHandlerSnapshot(retained, 1L, 0L, 0L);
			Assertions.assertEquals(Map.of(
					ParticipantShutdownDisposition.RESIDUAL_ACTIVITY, 1L),
					retained.getShutdowns());
			Assertions.assertEquals(List.of(
					ParticipantShutdownDisposition.RESIDUAL_ACTIVITY),
					observer.stopOutcomes());
			Assertions.assertEquals(0, observer.mcpStopFailures());
			Assertions.assertNull(observer.globalStopFailure());
			Assertions.assertEquals(1, invocations.get(),
					"Shutdown must not promote queued handler work.");
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerExecutionStarted.class));
			Assertions.assertEquals(0,
					collector.count(McpMetricsEvent.HandlerExecutionFinished.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerQueued.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerDequeued.class));

			ShutdownIncompleteException repeatedStop = Assertions.assertThrows(
					ShutdownIncompleteException.class, soklet::close);
			Assertions.assertSame(shutdownResult,
					repeatedStop.getInternalShutdownResult());
			ShutdownIncompleteException secondRepeatedStop = Assertions.assertThrows(
					ShutdownIncompleteException.class, soklet::close);
			Assertions.assertSame(shutdownResult,
					secondRepeatedStop.getInternalShutdownResult());
			Assertions.assertEquals(List.of(ParticipantShutdownDisposition.RESIDUAL_ACTIVITY),
					collector.serverStopOutcomes());
			Assertions.assertEquals(0, observer.mcpStopFailures());
			Assertions.assertEquals(List.of(
					ParticipantShutdownDisposition.RESIDUAL_ACTIVITY),
					observer.stopOutcomes());
			Assertions.assertNull(observer.globalStopFailure());

			releaseActive.countDown();
			Assertions.assertTrue(activeExited.await(5, TimeUnit.SECONDS),
					"The released residual handler did not exit.");
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 0L);
			awaitStatus(server, McpServerStatus.RESIDUAL_ACTIVITY);
			assertHandlerDiagnostics(server.getDiagnostics(), 0, 0);
			Assertions.assertEquals(
					McpServerStatus.RESIDUAL_ACTIVITY,
					residualDiagnostics.getStatus());
			assertHandlerDiagnostics(residualDiagnostics, 1, 0);
			McpMetricsSnapshot afterPhysicalCleanup = collector.snapshot()
					.orElseThrow()
					.getMcpMetrics();
			assertHandlerSnapshot(afterPhysicalCleanup, 0L, 0L, 0L);
			Assertions.assertEquals(Map.of(
					ParticipantShutdownDisposition.RESIDUAL_ACTIVITY, 1L),
					afterPhysicalCleanup.getShutdowns());
			assertHandlerSnapshot(retained, 1L, 0L, 0L);
			Assertions.assertSame(shutdownResult,
					lifecycleAdapter(server).result().orElseThrow());
			Assertions.assertSame(shutdownResult,
					soklet.getDirectLifecycle().result().orElseThrow());
			ShutdownIncompleteException lateRepeatedStop = Assertions.assertThrows(
					ShutdownIncompleteException.class, soklet::close);
			Assertions.assertSame(shutdownResult,
					lateRepeatedStop.getInternalShutdownResult());
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerExecutionFinished.class));
			Assertions.assertEquals(List.of(ParticipantShutdownDisposition.RESIDUAL_ACTIVITY),
					collector.serverStopOutcomes());
			Assertions.assertEquals(0, observer.mcpStopFailures());
			Assertions.assertEquals(List.of(
					ParticipantShutdownDisposition.RESIDUAL_ACTIVITY),
					observer.stopOutcomes());
			Assertions.assertNull(observer.globalStopFailure());
		} finally {
			releaseActive.countDown();
			if (active != null)
				active.cancel(true);
			if (queued != null)
				queued.cancel(true);
			stopAfterTerminalFailure(soklet);
		}
	}

	@Test
	public void managedStopDefersQueueAndExecutionCallbacksBeyondLifecycleLocks()
			throws Exception {
		ExecutorService probeExecutor = Executors.newFixedThreadPool(2);
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch activeInterrupted = new CountDownLatch(1);
		CountDownLatch activeExited = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		McpEndpoint endpoint = endpoint("/mcp/managed-lock-probe",
				"metrics.managed-lock-probe", (request, arguments, features) -> {
					activeEntered.countDown();
					try {
						Assertions.assertTrue(emergencyRelease.await(10,
								TimeUnit.SECONDS),
								"Timed out waiting for the emergency handler release");
						return McpCompleteResult.fromToolText("released");
					} catch (InterruptedException exception) {
						activeInterrupted.countDown();
						throw exception;
					} finally {
						activeExited.countDown();
					}
				});
		McpServer server = serverFor(List.of(endpoint), 1, 1,
				Duration.ofSeconds(10));
		AtomicReference<Soklet> sokletReference = new AtomicReference<>();
		LockProbingMetricsCollector collector = new LockProbingMetricsCollector(
				probeExecutor, server, sokletReference);
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		Soklet soklet = newSoklet(server, collector, observer,
				managedLockProbeShutdownPolicy());
		sokletReference.set(soklet);
		CompletableFuture<HttpResponse<String>> active = null;
		CompletableFuture<HttpResponse<String>> queued = null;

		try {
			soklet.start();
			int port = port(server);
			active = callTool(port, "/mcp/managed-lock-probe",
					"managed-probe-active", "metrics.managed-lock-probe");
			Assertions.assertTrue(activeEntered.await(5, TimeUnit.SECONDS),
					"The managed lock-probe handler did not enter.");
			queued = callTool(port, "/mcp/managed-lock-probe",
					"managed-probe-queued", "metrics.managed-lock-probe");
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 1L
							&& metrics.getHandlerQueueDepth() == 1L);

			soklet.close();
			Assertions.assertTrue(activeInterrupted.await(5, TimeUnit.SECONDS),
					"Managed stop did not interrupt the active handler.");
			Assertions.assertTrue(activeExited.await(5, TimeUnit.SECONDS),
					"The cooperative managed-stop handler did not exit.");
			awaitMetrics(collector, metrics -> metrics.getShutdowns().equals(Map.of(
					ParticipantShutdownDisposition.FORCED_TERMINATION, 1L)));
			collector.awaitProbeCount(2);
			Assertions.assertNull(collector.probeFailure(),
					"A handler metric callback ran while a lifecycle lock was held.");
			Assertions.assertEquals(List.of(
					"HandlerDequeued", "HandlerExecutionFinished"),
					collector.probedTransitionNames());
			Assertions.assertEquals(List.of(
					McpServerStatus.TERMINATED, McpServerStatus.TERMINATED),
					collector.observedStatuses());
			Assertions.assertEquals(List.of(false, false),
					collector.observedSokletStarted());
			assertHandlerSnapshot(collector.snapshot().orElseThrow()
					.getMcpMetrics(), 0L, 0L, 0L);
			observer.awaitTerminal();
			Assertions.assertEquals(List.of(ParticipantShutdownDisposition.FORCED_TERMINATION),
					observer.stopOutcomes());
		} finally {
			try {
				emergencyRelease.countDown();
				if (active != null)
					active.cancel(true);
				if (queued != null)
					queued.cancel(true);
				soklet.close();
			} finally {
				probeExecutor.shutdownNow();
				Assertions.assertTrue(probeExecutor.awaitTermination(
						5, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	@Timeout(120)
	public void unexpectedTerminationDefersQueueCallbackAndFreezesTerminalGauge()
			throws Exception {
		ExecutorService probeExecutor = Executors.newFixedThreadPool(2);
		ExecutorService terminationExecutor = Executors.newSingleThreadExecutor();
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch activeInterrupted = new CountDownLatch(1);
		CountDownLatch activeExited = new CountDownLatch(1);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		McpEndpoint endpoint = endpoint("/mcp/unexpected-lock-probe",
				"metrics.unexpected-lock-probe", (request, arguments, features) -> {
					activeEntered.countDown();
					try {
						awaitIgnoringInterrupts(emergencyRelease,
								activeInterrupted);
						return McpCompleteResult.fromToolText("released");
					} finally {
						activeExited.countDown();
					}
				});
		McpServer server = serverFor(List.of(endpoint), 1, 1,
				Duration.ofSeconds(10));
		AtomicReference<Soklet> sokletReference = new AtomicReference<>();
		LockProbingMetricsCollector collector = new LockProbingMetricsCollector(
				probeExecutor, server, sokletReference);
		Soklet soklet = newSoklet(server, collector,
				new SilentLifecycleObserver(), shortShutdownPolicy());
		sokletReference.set(soklet);
		CompletableFuture<HttpResponse<String>> active = null;
		CompletableFuture<HttpResponse<String>> queued = null;
		Future<Throwable> termination = null;

		try {
			soklet.start();
			McpTransportLifecycleAdapter lifecycleAdapter =
					lifecycleAdapter(server);
			McpTransportLifecycleAdapter.Generation terminatedGeneration =
					(McpTransportLifecycleAdapter.Generation)
							lifecycleAdapter.currentGeneration();
			int port = port(server);
			active = callTool(port, "/mcp/unexpected-lock-probe",
					"unexpected-probe-active",
					"metrics.unexpected-lock-probe");
			Assertions.assertTrue(activeEntered.await(5, TimeUnit.SECONDS),
					"The unexpected-termination handler did not enter.");
			queued = callTool(port, "/mcp/unexpected-lock-probe",
					"unexpected-probe-queued",
					"metrics.unexpected-lock-probe");
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 1L
							&& metrics.getHandlerQueueDepth() == 1L);
			McpServerDiagnostics saturatedDiagnostics = server.getDiagnostics();
			assertHandlerDiagnostics(saturatedDiagnostics, 1, 1);
			Object runtime = runtime(runtimeBridge(server));
			Object subscriptionLock = subscriptionLock(runtime);
			EventLoop eventLoop = eventLoop(runtime);
			McpServerDiagnostics failedBeforeDrain;
			synchronized (subscriptionLock) {
				termination = terminationExecutor.submit(() ->
						handleUnexpectedTermination(runtime, eventLoop,
								terminatedGeneration));
				failedBeforeDrain = awaitDiagnostics(server, diagnostics ->
						diagnostics.getStatus()
								== McpServerStatus.RESIDUAL_ACTIVITY
							&& diagnostics.getActiveHandlerExecutions() == 1
							&& diagnostics.getQueuedRequests() == 1);
			}
			Assertions.assertTrue(activeInterrupted.await(5, TimeUnit.SECONDS),
					"Unexpected termination did not interrupt the active handler.");
			Throwable expectedTerminationFailure = requireNonNull(termination)
					.get(5, TimeUnit.SECONDS);
			McpServerDiagnostics failedAfterDrain = awaitDiagnostics(server,
					diagnostics -> diagnostics.getStatus()
							== McpServerStatus.RESIDUAL_ACTIVITY
							&& diagnostics.getActiveHandlerExecutions() == 1
							&& diagnostics.getQueuedRequests() == 0);
			collector.awaitProbeCount(1);
			Assertions.assertNull(collector.probeFailure(),
					"A handler callback ran while the runtime lifecycle lock was held.");
			Assertions.assertEquals(List.of("HandlerDequeued"),
					collector.probedTransitionNames());
			Assertions.assertEquals(List.of(
					McpServerStatus.RESIDUAL_ACTIVITY),
					collector.observedStatuses());
			SokletTerminatedUnexpectedlyException stopFailure =
					Assertions.assertThrows(
							SokletTerminatedUnexpectedlyException.class,
							soklet::close);
			InternalShutdownResult shutdownResult =
					stopFailure.getInternalShutdownResult();
			Assertions.assertSame(expectedTerminationFailure,
					stopFailure.getCause());
			Assertions.assertFalse(shutdownResult.isComplete(),
					"The still-running exact generation must freeze with retained evidence.");
			Assertions.assertSame(shutdownResult,
					lifecycleAdapter.result(terminatedGeneration).orElseThrow());
			Assertions.assertSame(shutdownResult,
					soklet.getDirectLifecycle().result().orElseThrow());
			awaitMetrics(collector, metrics -> metrics.getShutdowns().equals(Map.of(
					ParticipantShutdownDisposition.RESIDUAL_ACTIVITY, 1L)));
			McpMetricsSnapshot terminalMetrics = collector.snapshot().orElseThrow()
					.getMcpMetrics();
			assertHandlerSnapshot(terminalMetrics, 1L, 0L, 0L);
			Assertions.assertEquals(Map.of(
					ParticipantShutdownDisposition.RESIDUAL_ACTIVITY, 1L),
					terminalMetrics.getShutdowns());

			emergencyRelease.countDown();
			Assertions.assertTrue(activeExited.await(5, TimeUnit.SECONDS),
					"The released unexpected-termination handler did not exit.");
			McpServerDiagnostics stoppedDiagnostics = awaitDiagnostics(server,
					diagnostics -> diagnostics.getStatus() == McpServerStatus.RESIDUAL_ACTIVITY
							&& diagnostics.getActiveHandlerExecutions() == 0
							&& diagnostics.getQueuedRequests() == 0);
			collector.awaitProbeCount(2);
			Assertions.assertNull(collector.probeFailure());
			Assertions.assertEquals(List.of(
					"HandlerDequeued", "HandlerExecutionFinished"),
					collector.probedTransitionNames());
			Assertions.assertEquals(List.of(
					McpServerStatus.RESIDUAL_ACTIVITY,
					McpServerStatus.RESIDUAL_ACTIVITY),
					collector.observedStatuses());
			assertHandlerDiagnostics(saturatedDiagnostics, 1, 1);
			assertHandlerDiagnostics(failedBeforeDrain, 1, 1);
			assertHandlerDiagnostics(failedAfterDrain, 1, 0);
			assertHandlerDiagnostics(stoppedDiagnostics, 0, 0);
			McpMetricsSnapshot afterPhysicalCleanup = collector.snapshot().orElseThrow()
					.getMcpMetrics();
			assertHandlerSnapshot(afterPhysicalCleanup, 0L, 0L, 0L);
			Assertions.assertEquals(Map.of(
					ParticipantShutdownDisposition.RESIDUAL_ACTIVITY, 1L),
					afterPhysicalCleanup.getShutdowns());
			assertHandlerSnapshot(terminalMetrics, 1L, 0L, 0L);
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerExecutionStarted.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerQueued.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerDequeued.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerExecutionFinished.class));
			Assertions.assertSame(shutdownResult,
					lifecycleAdapter.result(terminatedGeneration).orElseThrow());
			Assertions.assertSame(shutdownResult,
					soklet.getDirectLifecycle().result().orElseThrow());
			SokletTerminatedUnexpectedlyException repeatedStop =
					Assertions.assertThrows(
							SokletTerminatedUnexpectedlyException.class,
							soklet::close);
			Assertions.assertSame(shutdownResult,
					repeatedStop.getInternalShutdownResult());
			Assertions.assertSame(expectedTerminationFailure,
					repeatedStop.getCause());
		} finally {
			emergencyRelease.countDown();
			if (active != null)
				active.cancel(true);
			if (queued != null)
				queued.cancel(true);
			stopAfterTerminalFailure(soklet);
			terminationExecutor.shutdownNow();
			Assertions.assertTrue(terminationExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
			probeExecutor.shutdownNow();
			Assertions.assertTrue(probeExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	@Timeout(120)
	public void handlerMetricsCollectorFailuresAreContainedAndLogged()
			throws Exception {
		RuntimeException expectedFailure = new RuntimeException(
				"expected handler metrics failure");
		RecordingMetricsCollector collector =
				new RecordingMetricsCollector(expectedFailure);
		RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
		McpEndpoint endpoint = endpoint("/mcp/failing-handler-metrics",
				"metrics.failure", (request, arguments, features) ->
						McpCompleteResult.fromToolText("complete"));
		McpServer server = serverFor(List.of(endpoint), 1, 1,
				Duration.ofSeconds(5));
		Soklet soklet = newSoklet(server, collector, observer);

		try {
			soklet.start();
			HttpResponse<String> response = callTool(port(server),
					"/mcp/failing-handler-metrics", "failing-metrics",
					"metrics.failure").get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(200, response.statusCode(), response.body());
			awaitMetrics(collector, metrics ->
					metrics.getActiveHandlerExecutions() == 0L);
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerExecutionStarted.class));
			Assertions.assertEquals(1,
					collector.count(McpMetricsEvent.HandlerExecutionFinished.class));
			awaitLogEventCount(observer,
					LogEventType.METRICS_COLLECTOR_FAILED, 2);

			List<LogEvent> failures = observer.logEvents().stream()
					.filter(event -> event.getLogEventType()
							== LogEventType.METRICS_COLLECTOR_FAILED)
					.toList();
			Assertions.assertEquals(2, failures.size());
			for (LogEvent failure : failures) {
				Assertions.assertTrue(failure.getThrowable().isEmpty(),
						failure.toString());
				Assertions.assertTrue(failure.getRequest().isEmpty(),
						"Server-wide handler metrics have no request context.");
				Assertions.assertTrue(failure.getResourceMethod().isEmpty());
				Assertions.assertTrue(failure.getMarshaledResponse().isEmpty());
			}
			Assertions.assertFalse(failures.toString().contains(
					expectedFailure.getMessage()), failures.toString());
		} finally {
			soklet.close();
		}
	}

	private static void assertBoxedSnapshotMethod(@NonNull String getterName,
			@NonNull String builderMethodName) throws Exception {
		Method getter = McpMetricsSnapshot.class.getMethod(
				requireNonNull(getterName));
		Assertions.assertEquals(Long.class, getter.getReturnType());
		Assertions.assertTrue(getter.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
		Method builderMethod = McpMetricsSnapshot.Builder.class.getMethod(
				requireNonNull(builderMethodName), Long.class);
		Assertions.assertEquals(McpMetricsSnapshot.Builder.class,
				builderMethod.getReturnType());
		Assertions.assertEquals(Long.class,
				builderMethod.getParameterTypes()[0]);
		Assertions.assertTrue(builderMethod.getAnnotatedParameterTypes()[0]
				.isAnnotationPresent(NonNull.class));
	}

	private static void assertHandlerSnapshot(@NonNull McpMetricsSnapshot snapshot,
			long activeHandlers, long queueDepth, long capacityRejections) {
		Assertions.assertEquals(activeHandlers,
				requireNonNull(snapshot).getActiveHandlerExecutions());
		Assertions.assertEquals(queueDepth, snapshot.getHandlerQueueDepth());
		Assertions.assertEquals(capacityRejections,
				snapshot.getHandlerCapacityRejections());
	}

	private static void assertHandlerDiagnostics(
			@NonNull McpServerDiagnostics diagnostics,
			int activeHandlerExecutions, int queuedRequests) {
		Assertions.assertEquals(Integer.valueOf(1),
				requireNonNull(diagnostics).getRequestHandlerConcurrency());
		Assertions.assertEquals(Integer.valueOf(1),
				diagnostics.getRequestHandlerQueueCapacity());
		Assertions.assertEquals(Integer.valueOf(activeHandlerExecutions),
				diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(Integer.valueOf(queuedRequests),
				diagnostics.getQueuedRequests());
	}

	private static void assertMetricType(@NonNull String text,
			@NonNull String metricName, @NonNull String help,
			@NonNull String type) {
		Assertions.assertTrue(requireNonNull(text).contains(
				"# HELP " + requireNonNull(metricName) + " "
						+ requireNonNull(help) + "\n"), text);
		Assertions.assertTrue(text.contains(
				"# TYPE " + metricName + " " + requireNonNull(type) + "\n"),
				text);
	}

	private static void assertSample(@NonNull String text,
			@NonNull String metricName, long value) {
		Assertions.assertTrue(requireNonNull(text).contains(
				requireNonNull(metricName) + " " + value + "\n"), text);
	}

	@NonNull
	private static String prometheus(@NonNull MetricsCollector collector) {
		return requireNonNull(collector).snapshotText(
				MetricsCollector.SnapshotTextOptions.withMetricsFormat(
						MetricsCollector.MetricsFormat.PROMETHEUS)
						.build()).orElseThrow();
	}

	private static int occurrences(@NonNull String value,
			@NonNull String substring) {
		int count = 0;
		int index = 0;
		while ((index = requireNonNull(value).indexOf(
				requireNonNull(substring), index)) >= 0) {
			count++;
			index += substring.length();
		}
		return count;
	}

	private static void assertContains(@NonNull String actual,
			@NonNull String expected) {
		Assertions.assertTrue(requireNonNull(actual).contains(
				requireNonNull(expected)), () ->
				"Expected <" + actual + "> to contain <" + expected + ">.");
	}

	private static void awaitIgnoringInterrupts(@NonNull CountDownLatch release,
			@NonNull CountDownLatch interrupted) {
		boolean released = false;
		while (!released) {
			try {
				released = requireNonNull(release).await(
						25, TimeUnit.MILLISECONDS);
			} catch (InterruptedException exception) {
				requireNonNull(interrupted).countDown();
				// Deliberately model noncooperative application code.
			}
		}
	}

	private static void awaitMetrics(@NonNull MetricsCollector collector,
			@NonNull Predicate<@NonNull McpMetricsSnapshot> predicate)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			McpMetricsSnapshot metrics = requireNonNull(collector).snapshot()
					.orElseThrow().getMcpMetrics();
			if (requireNonNull(predicate).test(metrics))
				return;
			Thread.sleep(10L);
		}
		Assertions.assertTrue(predicate.test(collector.snapshot().orElseThrow()
				.getMcpMetrics()), "MCP handler metrics did not reach the expected state.");
	}

	private static void awaitStatus(@NonNull McpServer server,
			@NonNull McpServerStatus status) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			if (requireNonNull(server).getDiagnostics().getStatus()
					== requireNonNull(status))
				return;
			Thread.sleep(10L);
		}
		Assertions.assertEquals(status, server.getDiagnostics().getStatus());
	}

	@NonNull
	private static McpServerDiagnostics awaitDiagnostics(
			@NonNull McpServer server,
			@NonNull Predicate<@NonNull McpServerDiagnostics> predicate)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpServerDiagnostics latest = requireNonNull(server).getDiagnostics();
		while (System.nanoTime() - deadline < 0L) {
			latest = server.getDiagnostics();
			if (requireNonNull(predicate).test(latest))
				return latest;
			Thread.sleep(10L);
		}
		Assertions.fail("MCP diagnostics did not reach the expected state; latest="
				+ latest);
		throw new AssertionError();
	}

	private static void awaitLogEventCount(
			@NonNull RecordingLifecycleObserver observer,
			@NonNull LogEventType eventType, int expectedCount)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			long count = requireNonNull(observer).logEvents().stream()
					.filter(event -> event.getLogEventType()
							== requireNonNull(eventType))
					.count();
			if (count == expectedCount)
				return;
			Thread.sleep(10L);
		}
		Assertions.assertEquals(expectedCount,
				observer.logEvents().stream()
						.filter(event -> event.getLogEventType() == eventType)
						.count());
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
	private static Object runtime(@NonNull McpServerRuntimeBridge bridge)
			throws Exception {
		Field runtimeField = McpServerRuntimeBridge.class.getDeclaredField(
				"runtime");
		runtimeField.setAccessible(true);
		return runtimeField.get(requireNonNull(bridge));
	}

	@NonNull
	private static Object subscriptionLock(@NonNull Object runtime)
			throws Exception {
		Field subscriptionLockField = requireNonNull(runtime).getClass()
				.getDeclaredField("subscriptionLock");
		subscriptionLockField.setAccessible(true);
		return subscriptionLockField.get(runtime);
	}

	@NonNull
	private static EventLoop eventLoop(@NonNull Object runtime)
			throws Exception {
		Field eventLoopField = runtime.getClass().getDeclaredField("eventLoop");
		eventLoopField.setAccessible(true);
		return (EventLoop) eventLoopField.get(runtime);
	}

	@NonNull
	private static Throwable handleUnexpectedTermination(@NonNull Object runtime,
			@NonNull EventLoop eventLoop,
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation
					terminatedGeneration) {
		try {
			Method method = requireNonNull(runtime).getClass().getDeclaredMethod(
					"handleUnexpectedTermination", EventLoop.class, Throwable.class,
					McpServerRuntimeBridge.LifecycleAdapter.Generation.class);
			method.setAccessible(true);
			Throwable expectedFailure = new IllegalStateException(
					"expected test termination");
			method.invoke(runtime, requireNonNull(eventLoop),
					expectedFailure,
					requireNonNull(terminatedGeneration));
			return expectedFailure;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Unable to invoke unexpected termination.",
					exception.getCause() == null ? exception : exception.getCause());
		}
	}

	private static void stopAfterTerminalFailure(@NonNull Soklet soklet) {
		try {
			requireNonNull(soklet).close();
		} catch (ShutdownIncompleteException
				| SokletTerminatedUnexpectedlyException ignored) {
			// Terminal cleanup replays the immutable owner result by contract.
		}
	}

	@NonNull
	private static Soklet newSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector collector,
			@NonNull LifecycleObserver observer) {
		return newSoklet(server, collector, observer,
				LifecyclePolicy.fromDefaults());
	}

	@NonNull
	private static Soklet newSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector collector,
			@NonNull LifecycleObserver observer,
			@NonNull LifecyclePolicy lifecyclePolicy) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(requireNonNull(server))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(requireNonNull(collector))
				.lifecycleObserver(requireNonNull(observer))
				.lifecyclePolicy(requireNonNull(lifecyclePolicy))
				.build());
	}

	@NonNull
	private static LifecyclePolicy shortShutdownPolicy() {
		return LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(5))
				.startupCancellationTimeout(Duration.ofMillis(100))
				.gracefulShutdownDuration(Duration.ofMillis(100))
				.forcedShutdownDuration(Duration.ofMillis(100))
				.build();
	}

	@NonNull
	private static LifecyclePolicy managedLockProbeShutdownPolicy() {
		return LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(5))
				.startupCancellationTimeout(Duration.ofMillis(100))
				.gracefulShutdownDuration(Duration.ofMillis(100))
				.forcedShutdownDuration(Duration.ofSeconds(3))
				.build();
	}

	@NonNull
	private static McpEndpoint emptyEndpoint(@NonNull String path) {
		return McpEndpoint.withPath(requireNonNull(path))
				.serverInformation(McpImplementation.withNameAndVersion(
						"handler-metrics-test", "4.0.0").build())
				.build();
	}

	@NonNull
	private static McpEndpoint endpoint(@NonNull String path,
			@NonNull String toolName,
			@NonNull McpToolHandler<McpJsonObject> handler) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(requireNonNull(toolName))
				.jsonArguments()
				.handler(requireNonNull(handler))
				.build();
		return McpEndpoint.withPath(requireNonNull(path))
				.serverInformation(McpImplementation.withNameAndVersion(
						"handler-metrics-test", "4.0.0").build())
				.tool(tool)
				.build();
	}

	@NonNull
	private static McpServer serverFor(
			@NonNull List<@NonNull McpEndpoint> endpoints,
			int handlerConcurrency, int handlerQueueCapacity,
			@NonNull Duration requestTimeout) {
		return McpServer.withPort(0)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.copyOf(requireNonNull(endpoints))))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST))
				.requestHandlerConcurrency(handlerConcurrency)
				.requestHandlerQueueCapacity(handlerQueueCapacity)
				.requestTimeout(requireNonNull(requestTimeout))
				.build();
	}

	private static int port(@NonNull McpServer server) {
		return requireNonNull(server).getDiagnostics().getBoundAddress()
				.orElseThrow().getPort();
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> callTool(int port,
			@NonNull String path, @NonNull String id,
			@NonNull String toolName) {
		String body = toolCallBody(id, toolName);
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + HOST + ":" + port
						+ requireNonNull(path)))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", requireNonNull(toolName))
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

	@NonNull
	private static Socket openRawToolCall(int port, @NonNull String path,
			@NonNull String id, @NonNull String toolName) throws Exception {
		byte[] body = toolCallBody(id, toolName).getBytes(StandardCharsets.UTF_8);
		Socket socket = new Socket();
		try {
			socket.connect(new InetSocketAddress(HOST, port), 3_000);
			String requestHead = "POST " + requireNonNull(path) + " HTTP/1.1\r\n"
					+ "Host: " + HOST + ":" + port + "\r\n"
					+ "Content-Type: " + JSON_MEDIA_TYPE
					+ "; charset=UTF-8\r\n"
					+ "Accept: " + JSON_MEDIA_TYPE
					+ ", text/event-stream\r\n"
					+ "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
					+ "Mcp-Method: tools/call\r\n"
					+ "Mcp-Name: " + requireNonNull(toolName) + "\r\n"
					+ "Content-Length: " + body.length + "\r\n"
					+ "Connection: close\r\n\r\n";
			socket.getOutputStream().write(
					requestHead.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().write(body);
			socket.getOutputStream().flush();
			return socket;
		} catch (Throwable throwable) {
			try {
				socket.close();
			} catch (Throwable suppressed) {
				throwable.addSuppressed(suppressed);
			}
			throw throwable;
		}
	}

	@NonNull
	private static String toolCallBody(@NonNull String id,
			@NonNull String toolName) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + requireNonNull(id)
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + requireNonNull(toolName)
				+ "\",\"arguments\":{}}}";
	}

	private static boolean handlerEvent(@NonNull McpMetricsEvent event) {
		return requireNonNull(event)
				instanceof McpMetricsEvent.HandlerExecutionStarted
				|| event instanceof McpMetricsEvent.HandlerExecutionFinished
				|| event instanceof McpMetricsEvent.HandlerQueued
				|| event instanceof McpMetricsEvent.HandlerDequeued
				|| event instanceof McpMetricsEvent.HandlerCapacityRejected;
	}

	@ThreadSafe
	private static final class RecordingMetricsCollector
			implements MetricsCollector {
		@NonNull
		private final DefaultMetricsCollector delegate;
		@NonNull
		private final List<@NonNull McpMetricsEvent> events;
		@Nullable
		private final RuntimeException handlerFailure;

		private RecordingMetricsCollector() {
			this(null);
		}

		private RecordingMetricsCollector(
				@Nullable RuntimeException handlerFailure) {
			this.delegate = DefaultMetricsCollector.defaultInstance();
			this.events = new CopyOnWriteArrayList<>();
			this.handlerFailure = handlerFailure;
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			McpMetricsEvent requiredEvent = requireNonNull(event);
			this.events.add(requiredEvent);
			this.delegate.didRecordMcpMetricsEvent(requiredEvent);
			if (handlerEvent(requiredEvent) && this.handlerFailure != null)
				throw this.handlerFailure;
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
			this.events.clear();
		}

		private int count(@NonNull Class<? extends McpMetricsEvent> eventType) {
			return (int) this.events.stream()
					.filter(requireNonNull(eventType)::isInstance)
					.count();
		}

		@NonNull
		private List<@NonNull String> handlerTransitionNames() {
			return this.events.stream()
					.filter(McpHandlerMetricsObservabilityTests::handlerEvent)
					.map(event -> event.getClass().getSimpleName())
					.toList();
		}

		@NonNull
		private List<@NonNull ParticipantShutdownDisposition> serverStopOutcomes() {
			return this.events.stream()
					.filter(McpMetricsEvent.ServerStopped.class::isInstance)
					.map(McpMetricsEvent.ServerStopped.class::cast)
					.map(McpMetricsEvent.ServerStopped::getOutcome)
					.toList();
		}
	}

	@ThreadSafe
	private static final class LockProbingMetricsCollector
			implements MetricsCollector {
		@NonNull
		private final DefaultMetricsCollector delegate;
		@NonNull
		private final ExecutorService probeExecutor;
		@NonNull
		private final McpServer server;
		@NonNull
		private final AtomicReference<@Nullable Soklet> sokletReference;
		@NonNull
		private final List<@NonNull McpMetricsEvent> events;
		@NonNull
		private final List<@NonNull String> probedTransitionNames;
		@NonNull
		private final List<@NonNull McpServerStatus> observedStatuses;
		@NonNull
		private final List<@NonNull Boolean> observedSokletStarted;
		@NonNull
		private final AtomicReference<@Nullable Throwable> probeFailure;

		private LockProbingMetricsCollector(
				@NonNull ExecutorService probeExecutor,
				@NonNull McpServer server,
				@NonNull AtomicReference<@Nullable Soklet> sokletReference) {
			this.delegate = DefaultMetricsCollector.defaultInstance();
			this.probeExecutor = requireNonNull(probeExecutor);
			this.server = requireNonNull(server);
			this.sokletReference = requireNonNull(sokletReference);
			this.events = new CopyOnWriteArrayList<>();
			this.probedTransitionNames = new CopyOnWriteArrayList<>();
			this.observedStatuses = new CopyOnWriteArrayList<>();
			this.observedSokletStarted = new CopyOnWriteArrayList<>();
			this.probeFailure = new AtomicReference<>();
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			McpMetricsEvent requiredEvent = requireNonNull(event);
			this.events.add(requiredEvent);
			this.delegate.didRecordMcpMetricsEvent(requiredEvent);
			if (!(requiredEvent instanceof McpMetricsEvent.HandlerDequeued)
					&& !(requiredEvent
					instanceof McpMetricsEvent.HandlerExecutionFinished))
				return;

			this.probedTransitionNames.add(
					requiredEvent.getClass().getSimpleName());
			Future<McpServerStatus> statusProbe = this.probeExecutor.submit(
					() -> this.server.getDiagnostics().getStatus());
			Future<Boolean> sokletProbe = this.probeExecutor.submit(() ->
					requireNonNull(this.sokletReference.get()).getStatus()
							== SokletStatus.RUNNING);
			long probeDeadlineNanos = System.nanoTime()
					+ TimeUnit.SECONDS.toNanos(2);
			boolean interrupted = false;
			try {
				McpServerStatus observedStatus;
				for (;;) {
					try {
						observedStatus = statusProbe.get(Math.max(0L,
								probeDeadlineNanos - System.nanoTime()),
								TimeUnit.NANOSECONDS);
						break;
					} catch (InterruptedException exception) {
						interrupted = true;
					}
				}
				boolean observedStarted;
				for (;;) {
					try {
						observedStarted = sokletProbe.get(Math.max(0L,
								probeDeadlineNanos - System.nanoTime()),
								TimeUnit.NANOSECONDS);
						break;
					} catch (InterruptedException exception) {
						interrupted = true;
					}
				}
				this.observedStatuses.add(observedStatus);
				this.observedSokletStarted.add(observedStarted);
			} catch (Throwable throwable) {
				statusProbe.cancel(true);
				sokletProbe.cancel(true);
				this.probeFailure.compareAndSet(null, throwable);
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}

		@Override
		@NonNull
		public Optional<Snapshot> snapshot() {
			return this.delegate.snapshot();
		}

		private int count(@NonNull Class<? extends McpMetricsEvent> eventType) {
			return (int) this.events.stream()
					.filter(requireNonNull(eventType)::isInstance)
					.count();
		}

		private void awaitProbeCount(int expectedCount)
				throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() - deadline < 0L) {
				if (this.probeFailure.get() != null
						|| probeCountComplete(expectedCount))
					return;
				Thread.sleep(10L);
			}
			Assertions.assertTrue(this.probeFailure.get() != null
					|| probeCountComplete(expectedCount),
					"Lifecycle lock probes did not complete in time: transitions="
							+ this.probedTransitionNames + ", statuses="
							+ this.observedStatuses + ", sokletStarted="
							+ this.observedSokletStarted + ", probeFailure="
							+ this.probeFailure.get());
		}

		private boolean probeCountComplete(int expectedCount) {
			return this.observedStatuses.size() == expectedCount
					&& this.observedSokletStarted.size() == expectedCount;
		}

		@Nullable
		private Throwable probeFailure() {
			return this.probeFailure.get();
		}

		@NonNull
		private List<@NonNull String> probedTransitionNames() {
			return List.copyOf(this.probedTransitionNames);
		}

		@NonNull
		private List<@NonNull McpServerStatus> observedStatuses() {
			return List.copyOf(this.observedStatuses);
		}

		@NonNull
		private List<@NonNull Boolean> observedSokletStarted() {
			return List.copyOf(this.observedSokletStarted);
		}
	}

	@ThreadSafe
	private static class SilentLifecycleObserver implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Keep expected configuration diagnostics out of test output.
		}
	}

	@ThreadSafe
	private static final class RecordingLifecycleObserver
			extends SilentLifecycleObserver {
		@NonNull
		private final List<@NonNull ParticipantShutdownDisposition> stopOutcomes;
		@NonNull
		private final List<@NonNull LogEvent> logEvents;
		@NonNull
		private final AtomicInteger mcpStopFailures;
		@NonNull
		private final AtomicReference<@Nullable Throwable> globalStopFailure;
		@NonNull
		private final CountDownLatch terminal;

		private RecordingLifecycleObserver() {
			this.stopOutcomes = new CopyOnWriteArrayList<>();
			this.logEvents = new CopyOnWriteArrayList<>();
			this.mcpStopFailures = new AtomicInteger();
			this.globalStopFailure = new AtomicReference<>();
			this.terminal = new CountDownLatch(1);
		}

		private void awaitTerminal() throws InterruptedException {
			Assertions.assertTrue(this.terminal.await(5, TimeUnit.SECONDS),
					"The global lifecycle terminal callback was not observed.");
		}

		@Override
		public void didStopMcpServer(@NonNull McpServer server,
				@NonNull ParticipantShutdownResult result) {
			requireNonNull(server);
			ParticipantShutdownResult exactResult = requireNonNull(result);
			this.stopOutcomes.add(exactResult.getDisposition());
			if (!exactResult.getFailures().isEmpty()) {
				this.mcpStopFailures.incrementAndGet();
				this.globalStopFailure.compareAndSet(null,
						exactResult.getFailures().get(0));
			}
		}

		@Override
		public void didStopSoklet(@NonNull Soklet soklet,
				@NonNull ShutdownResult result) {
			requireNonNull(soklet);
			requireNonNull(result);
			this.terminal.countDown();
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			this.logEvents.add(requireNonNull(logEvent));
		}

		@NonNull
		private List<@NonNull ParticipantShutdownDisposition> stopOutcomes() {
			return List.copyOf(this.stopOutcomes);
		}

		private int mcpStopFailures() {
			return this.mcpStopFailures.get();
		}

		@Nullable
		private Throwable globalStopFailure() {
			return this.globalStopFailure.get();
		}

		@NonNull
		private List<@NonNull LogEvent> logEvents() {
			return List.copyOf(this.logEvents);
		}
	}
}
