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

import com.soklet.internal.mcp.protocol.McpApplicationExecutionObserver;
import com.soklet.internal.mcp.protocol.McpApplicationExecutionObserver.MetricDeferral;
import com.soklet.internal.mcp.protocol.McpApplicationExecutionObserver.MetricScope;
import com.soklet.internal.mcp.protocol.McpApplicationExecutionObserver.MetricSequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic request-ordering coverage across independent producer threads. */
@Timeout(60)
public class McpRequestMetricOrderingTests {
	private static final String WORKER = "worker";
	private static final String TIMEOUT = "timeout";
	private static final String CANCELLATION = "cancel";

	@Test
	public void ownerLifecycleEventsRemainIndependentOfTheCallingRequestsSequence() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		MetricSequence sequence = observer.newRequestMetricSequence();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			try (MetricScope scope = observer.useMetricSequence(sequence);
					MetricDeferral transition = observer.beginRequestTransitionDeferral()) {
				observer.recordRequestAccepted();
				executor.submit(() -> {
					try (MetricScope sameRequest = observer.useMetricSequence(sequence)) {
						record(server, McpMetricsEvent.serverStarted());
						record(server, McpMetricsEvent.serverStopped(ShutdownComponentDisposition.GRACEFUL_TERMINATION));
						observer.drainAsynchronously();
					}
				}).get(5, TimeUnit.SECONDS);
				assertEquals(List.of(McpMetricsEvent.serverStarted(),
						McpMetricsEvent.serverStopped(ShutdownComponentDisposition.GRACEFUL_TERMINATION)), collector.events);
			}
			awaitEvents(collector, 3);
			assertEquals(McpMetricsEvent.requestAccepted(), collector.events.get(2));
		} finally {
			executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void independentRequestProgressesWhileWorkerTimeoutAndCancellationRecordsWaitForAcceptance() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		MetricSequence first = observer.newRequestMetricSequence();
		MetricSequence other = observer.newRequestMetricSequence();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			try (MetricScope sequence = observer.useMetricSequence(first);
					MetricDeferral submission = observer.beginRequestTransitionDeferral()) {
				observer.recordRequestAccepted();
				for (McpMetricsEvent event : List.of(McpMetricsEvent.protocolError(-32700),
						McpMetricsEvent.requestRejected(),
						McpMetricsEvent.cancelationSignaled("/mcp", "tools/call"))) {
					executor.submit(() -> {
						try (MetricScope scope = observer.useMetricSequence(first)) {
							record(server, event);
							observer.drainAsynchronously();
						}
					}).get(5, TimeUnit.SECONDS);
				}
				assertTrue(collector.events.isEmpty());
				executor.submit(() -> {
					try (MetricScope scope = observer.useMetricSequence(other)) {
						observer.recordHandlerQueued();
						observer.drainAsynchronously();
					}
				}).get(5, TimeUnit.SECONDS);
				assertEquals(List.of(McpMetricsEvent.handlerQueued()), collector.events);
			}
			awaitEvents(collector, 5);
			assertEquals(List.of(McpMetricsEvent.handlerQueued(), McpMetricsEvent.requestAccepted(),
					McpMetricsEvent.protocolError(-32700), McpMetricsEvent.requestRejected(),
					McpMetricsEvent.cancelationSignaled("/mcp", "tools/call")), collector.events);
		} finally {
			executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void deferredProtocolErrorKeepsAStreamTerminalBehindIt() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		MetricSequence sequence = observer.newRequestMetricSequence();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			try (MetricScope scope = observer.useMetricSequence(sequence);
					MetricDeferral transition = observer.beginRequestTransitionDeferral()) {
				observer.recordProtocolError(-32603, null);
				executor.submit(() -> {
					try (MetricScope otherThread = observer.useMetricSequence(sequence)) {
						record(server, McpMetricsEvent.requestFinished("/mcp", "tools/call",
								McpRequestOutcome.INTERNAL_ERROR, Duration.ZERO));
						observer.drainAsynchronously();
					}
				}).get(5, TimeUnit.SECONDS);
				assertTrue(collector.events.isEmpty());
			}
			awaitEvents(collector, 2);
			assertEquals(McpMetricsEvent.protocolError(-32603), collector.events.get(0));
			assertInstanceOf(McpMetricsEvent.RequestFinished.class, collector.events.get(1));
		} finally {
			executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void discardEvictionAndOmissionCannotStrandLaterRecords() throws Exception {
		for (String removal : List.of("discard", "evict", "omit")) {
			RecordingCollector collector = new RecordingCollector();
			DefaultMcpServer server = server(collector);
			McpApplicationExecutionObserver observer = observer(server);
			MetricSequence sequence = observer.newRequestMetricSequence();
			int capacity = (int) field(DefaultMcpServer.class, "MAXIMUM_PENDING_MCP_METRIC_EVENTS").get(null);
			ExecutorService executor = Executors.newSingleThreadExecutor();
			try (MetricDeferral submission = observer.beginRequestTransitionDeferral()) {
				if (removal.equals("omit"))
					for (int index = 0; index < capacity; index++) observer.recordConnectionAccepted();
				McpApplicationExecutionObserver.PendingMetricRecord accepted;
				try (MetricScope scope = observer.useMetricSequence(sequence)) {
					accepted = observer.recordRequestAccepted();
				}
				if (removal.equals("discard")) observer.discardPendingMetric(accepted);
				if (removal.equals("evict")) {
					for (int index = 1; index < capacity; index++) observer.recordConnectionAccepted();
					record(server, McpMetricsEvent.serverStarted());
				}
				if (!removal.equals("discard")) {
					// Drop every retained filler while leaving the submission scope open.
					Object delivery = delivery(server);
					Object lock = field(delivery.getClass(), "lock").get(delivery);
					List<?> entries;
					synchronized (lock) {
						entries = new ArrayList<>((java.util.Collection<?>) field(delivery.getClass(), "pendingEvents").get(delivery));
					}
					for (Object entry : entries)
						observer.discardPendingMetric((McpApplicationExecutionObserver.PendingMetricRecord) entry);
				}
				executor.submit(() -> {
					try (MetricScope scope = observer.useMetricSequence(sequence)) {
						observer.recordRequestRejected(); observer.drainAsynchronously();
					}
				}).get(5, TimeUnit.SECONDS);
				assertEquals(List.of(McpMetricsEvent.requestRejected()), collector.events, removal);
			} finally {
				executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	public void nestedBindingsRestoreAndCollectorReentryHasNoInheritedRequestIdentity() throws Exception {
		AtomicReference<McpApplicationExecutionObserver> observerReference = new AtomicReference<>();
		AtomicReference<MetricSequence> seenByCollector = new AtomicReference<>();
		DefaultMcpServer server = server(new MetricsCollector() {
			@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				seenByCollector.set(observerReference.get().currentMetricSequence());
			}
		});
		McpApplicationExecutionObserver observer = observer(server);
		observerReference.set(observer);
		MetricSequence first = observer.newRequestMetricSequence(), second = observer.newRequestMetricSequence();
		assertNull(observer.currentMetricSequence());
		try (MetricScope outer = observer.useMetricSequence(first)) {
			try (MetricScope inner = observer.useMetricSequence(second)) {
				assertSame(second, observer.currentMetricSequence());
			}
			assertSame(first, observer.currentMetricSequence());
			observer.recordRequestAccepted(); observer.drainAsynchronously();
			assertNull(seenByCollector.get());
			assertSame(first, observer.currentMetricSequence());
		}
		assertNull(observer.currentMetricSequence());
		assertEquals(0, first.getClass().getDeclaredFields().length,
				"A sequence must be an identity marker without request data or predecessor links.");
	}

	@Test
	public void physicalHandlerExitResignalsAnEmptyAsynchronousDrainHandoff() throws Exception {
		AtomicReference<McpApplicationExecutionObserver> observerReference = new AtomicReference<>();
		RecordingCollector collector = new RecordingCollector() {
			@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				super.didRecordMcpMetricsEvent(event);
				if (event instanceof McpMetricsEvent.HandlerExecutionStarted)
					// An asynchronous signal may arrive while the synchronous drainer
					// still owns delivery, after its final pending event was removed.
					observerReference.get().drainAsynchronously();
			}
		};
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		observerReference.set(observer);
		observer.recordHandlerExecutionStarted();
		observer.drain();
		assertEquals(List.of(McpMetricsEvent.handlerExecutionStarted()), collector.events);

		// No additional HTTP request, timeout, or lifecycle event should be needed
		// to release the physical handler gauge after this handoff.
		observer.recordHandlerExecutionFinished();
		observer.drain();
		awaitEvents(collector, 2);
		assertEquals(List.of(McpMetricsEvent.handlerExecutionStarted(),
				McpMetricsEvent.handlerExecutionFinished()), collector.events);
	}

	@Test public void realWorkerCannotOvertakePausedSubmission() throws Exception { runtimeCase(WORKER); }
	@Test public void realDeadlineCannotOvertakePausedSubmission() throws Exception { runtimeCase(TIMEOUT); }
	@Test public void realCancellationCannotOvertakePausedSubmission() throws Exception { runtimeCase(CANCELLATION); }

	private static void runtimeCase(String producer) throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		Soklet owner = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector).lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(2))
						.gracefulShutdownTimeout(Duration.ofSeconds(2)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
		Object bridge = field(DefaultMcpServer.class, "runtimeBridge").get(server);
		Object runtime = field(bridge.getClass(), "runtime").get(bridge);
		Field observerField = field(runtime.getClass(), "applicationExecutionObserver");
		McpApplicationExecutionObserver original = (McpApplicationExecutionObserver) observerField.get(runtime);
		CompletableFuture<Void> submissionPaused = new CompletableFuture<>();
		CountDownLatch releaseSubmission = new CountDownLatch(1);
		CountDownLatch workerPaused = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
		CountDownLatch rejected = new CountDownLatch(1);
		AtomicReference<Thread> submitter = new AtomicReference<>();
		AtomicReference<String> lastObserverCall = new AtomicReference<>("none");
		AtomicBoolean pauseSubmission = new AtomicBoolean(true), pauseWorker = new AtomicBoolean(!producer.equals("worker"));
		McpApplicationExecutionObserver intercepted = (McpApplicationExecutionObserver) Proxy.newProxyInstance(
				McpApplicationExecutionObserver.class.getClassLoader(), new Class<?>[]{McpApplicationExecutionObserver.class},
				(proxy, method, arguments) -> {
					lastObserverCall.set(method.getName() + " on " + Thread.currentThread().getName());
					if (method.getName().equals("recordRequestAccepted")) submitter.set(Thread.currentThread());
					if (method.getName().equals("useMetricSequence")
							&& Thread.currentThread().getName().startsWith("soklet-mcp-request-")
							&& pauseWorker.compareAndSet(true, false)) {
						workerPaused.countDown(); await(releaseWorker);
					}
					Object result;
					try { result = method.invoke(original, arguments); }
					catch (InvocationTargetException failure) { throw failure.getCause(); }
					if (method.getName().equals("recordRequestRejected")) rejected.countDown();
					if (method.getName().equals("beginRequestTransitionDeferral")) {
						MetricDeferral delegate = (MetricDeferral) result;
						return (MetricDeferral) () -> {
							if (Thread.currentThread() == submitter.get() && pauseSubmission.compareAndSet(true, false)) {
								submissionPaused.complete(null); await(releaseSubmission);
							}
							delegate.close();
						};
					}
					return result;
				});
		observerField.set(runtime, intercepted);
		try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
			try {
				owner.start();
				int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
				HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
						.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
						.header("Accept", "application/json, text/event-stream")
						.header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", "server/discover")
						.POST(HttpRequest.BodyPublishers.ofString("{")).build();
				var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
				awaitSubmission(submissionPaused, response, () ->
						"producer=" + producer + ", port=" + port + ", acceptedBy=" + submitter.get()
								+ ", observerInstalled=" + observerInstallation(observerField, runtime, intercepted)
								+ ", lastObserverCall=" + lastObserverCall.get() + ", workerPaused=" + (workerPaused.getCount() == 0)
								+ ", events=" + collector.events + submissionThreadSnapshot());
				if (!producer.equals("worker")) {
					assertTrue(workerPaused.await(5, TimeUnit.SECONDS), () ->
							"Worker was not intercepted: producer=" + producer + ", lastObserverCall=" + lastObserverCall.get());
					Map<?, ?> controls = (Map<?, ?>) field(runtime.getClass(), "requestControls").get(runtime);
					Object control;
					synchronized (controls) { control = controls.values().iterator().next(); }
					if (producer.equals("timeout"))
						invoke(control, "onTimer", new Class<?>[]{long.class}, field(control.getClass(), "deadlineNanos").getLong(control));
					else
						invoke(control, "cancelFromSimulation", new Class<?>[]{StreamTerminationReason.class}, StreamTerminationReason.CLIENT_DISCONNECTED);
				}
				assertTrue(rejected.await(5, TimeUnit.SECONDS));
				original.drainAsynchronously();
				assertTrue(collector.requestEvents().isEmpty(), collector.events.toString());
				original.recordConnectionRejected(); original.drainAsynchronously();
				// A concurrent drainer may already own delivery when drainAsynchronously returns.
				assertTrue(collector.independentDelivered.await(5, TimeUnit.SECONDS),
						"Independent transport metrics must still make progress.");
				assertTrue(collector.events.contains(McpMetricsEvent.connectionRejected()),
						"Independent transport metrics must still make progress.");
				releaseWorker.countDown(); releaseSubmission.countDown();
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (collector.requestEvents().size() < (producer.equals("worker") ? 3 : 2)
						&& System.nanoTime() - deadline < 0) Thread.sleep(5);
				assertEquals(producer.equals("worker")
						? List.of(McpMetricsEvent.requestAccepted(), McpMetricsEvent.protocolError(-32700), McpMetricsEvent.requestRejected())
						: List.of(McpMetricsEvent.requestAccepted(), McpMetricsEvent.requestRejected()), collector.requestEvents());
				if (!producer.equals("cancel")) assertTrue(response.get(5, TimeUnit.SECONDS).statusCode() >= 400);
				else response.cancel(true);
			} finally {
				releaseWorker.countDown(); releaseSubmission.countDown(); owner.close();
			}
		}
	}

	/** A completed HTTP exchange before the gate is a prerequisite failure, not a metric-ordering timeout. */
	private static void awaitSubmission(CompletableFuture<Void> submission,
			CompletableFuture<HttpResponse<String>> response, Supplier<String> diagnostic) throws InterruptedException {
		try {
			CompletableFuture.anyOf(submission, response).get(5, TimeUnit.SECONDS);
		} catch (ExecutionException failure) {
			fail("HTTP exchange failed before submission interception: " + diagnostic.get(), failure.getCause());
		} catch (TimeoutException failure) {
			fail("Submission interception stalled: HTTP=" + completedResponseDescription(response)
					+ "; " + diagnostic.get(), failure);
		}
		assertTrue(submission.isDone(), () -> "HTTP exchange completed before submission interception: "
				+ completedResponseDescription(response) + "; " + diagnostic.get());
	}

	private static String completedResponseDescription(CompletableFuture<HttpResponse<String>> response) {
		return switch (response.state()) {
			case RUNNING -> "pending";
			case CANCELLED -> "cancelled";
			case FAILED -> response.exceptionNow().toString();
			case SUCCESS -> "status=" + response.resultNow().statusCode() + ", body=" + response.resultNow().body();
		};
	}

	private static String observerInstallation(Field observerField, Object runtime, Object expected) {
		try {
			return Boolean.toString(observerField.get(runtime) == expected);
		} catch (IllegalAccessException failure) {
			return "unreadable: " + failure;
		}
	}

	private static String submissionThreadSnapshot() {
		StringBuilder snapshot = new StringBuilder("\nMCP transport threads at failure:");
		// Run only when an assertion fails, before the fixture's finally block releases its gates.
		for (java.lang.management.ThreadInfo thread : java.lang.management.ManagementFactory
				.getThreadMXBean().dumpAllThreads(true, true)) {
			String name = thread.getThreadName();
			if (name.startsWith("soklet-") || name.equals("connection-event-loop") || name.equals("event-loop")
					|| name.startsWith("HttpClient-") || name.startsWith("ForkJoinPool.commonPool-worker-")) {
				snapshot.append('\n').append(thread);
				for (StackTraceElement frame : thread.getStackTrace()) snapshot.append("\n  ").append(frame);
			}
		}
		return snapshot.toString();
	}

	@Test
	public void failedHttpExchangeDoesNotMasqueradeAsSubmissionTimeout() {
		java.io.IOException failure = new java.io.IOException("Client connection failed");
		AssertionError assertion = assertTimeout(Duration.ofSeconds(2), () ->
				assertThrows(AssertionError.class, () -> awaitSubmission(new CompletableFuture<>(),
						CompletableFuture.failedFuture(failure), () -> "fixture context")));
		assertSame(failure, assertion.getCause());
		assertTrue(assertion.getMessage().contains("HTTP exchange failed before submission interception"));
		assertTrue(assertion.getMessage().contains("fixture context"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void earlyHttpResponseRetainsStatusInsteadOfMasqueradingAsSubmissionTimeout() {
		HttpResponse<String> response = (HttpResponse<String>) Proxy.newProxyInstance(HttpResponse.class.getClassLoader(),
				new Class<?>[]{HttpResponse.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "statusCode" -> 503;
					case "body" -> "Not admitted";
					default -> throw new UnsupportedOperationException(method.getName());
				});
		AssertionError assertion = assertTimeout(Duration.ofSeconds(2), () ->
				assertThrows(AssertionError.class, () -> awaitSubmission(new CompletableFuture<>(),
						CompletableFuture.completedFuture(response), () -> "fixture context")));
		assertTrue(assertion.getMessage().contains("status=503, body=Not admitted"));
		assertTrue(assertion.getMessage().contains("fixture context"));
	}

	private static DefaultMcpServer server(MetricsCollector collector) {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("metric-order", "4.0.0").build(),
				Set.of(McpProtocolVersion.V2026_07_28)).build();
		DefaultMcpServer server = (DefaultMcpServer) McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).requestTimeout(Duration.ofSeconds(30)).build();
		server.initialize(SokletConfig.withMcpServer(server).resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector).build());
		return server;
	}
	private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
	private static Object invoke(Object target, String name, Class<?>[] types, Object... values) throws Exception {
		Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, values);
	}
	private static McpApplicationExecutionObserver observer(DefaultMcpServer server) throws Exception {
		return (McpApplicationExecutionObserver) invoke(server, "applicationExecutionObserver", new Class<?>[0]);
	}
	private static Object delivery(DefaultMcpServer server) throws Exception { return field(DefaultMcpServer.class, "mcpMetricEventDelivery").get(server); }
	private static void record(DefaultMcpServer server, McpMetricsEvent event) {
		try { invoke(delivery(server), "record", new Class<?>[]{McpMetricsEvent.class}, event); }
		catch (Exception failure) { throw new AssertionError(failure); }
	}
	private static void awaitEvents(RecordingCollector collector, int count) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (collector.events.size() < count && System.nanoTime() - deadline < 0) Thread.sleep(5);
		assertEquals(count, collector.events.size(), collector.events.toString());
	}
	private static void await(CountDownLatch latch) {
		boolean interrupted = false;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		try {
			while (true) try { assertTrue(latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)); return; }
			catch (InterruptedException ignored) { interrupted = true; }
		} finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
	private static class RecordingCollector implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		private final CountDownLatch independentDelivered = new CountDownLatch(1);
		@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
			events.add(event);
			if (event instanceof McpMetricsEvent.ConnectionRejected) independentDelivered.countDown();
		}
		private List<McpMetricsEvent> requestEvents() { return events.stream().filter(event -> event instanceof McpMetricsEvent.RequestAccepted
				|| event instanceof McpMetricsEvent.ProtocolError || event instanceof McpMetricsEvent.RequestRejected).toList(); }
	}
}
