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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ProgressEmitter;
import com.soklet.internal.mcp.protocol.McpApplicationExecutionObserver;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Small barrier tests for metric delivery without sockets or slow readers. */
@Timeout(60)
public class McpMetricBackpressureTests {

	@Test
	public void everyRecognizedOperationHasItsOwnBoundedMetricLabel() throws Exception {
		List<String> methods = List.of("initialize", "notifications/initialized", "ping", "server/discover",
				"tools/list", "tools/call", "prompts/list", "prompts/get", "resources/list",
				"resources/templates/list", "resources/read", "resources/subscribe", "resources/unsubscribe",
				"skills/list", "skills/get", "completion/complete", "subscriptions/listen", "tasks/get",
				"tasks/update", "tasks/cancel", "notifications/cancelled");
		Method metricMethod = DefaultMcpServer.class.getDeclaredMethod("metricMethod", String.class);
		metricMethod.setAccessible(true);
		Set<McpOperationType> operations = new java.util.HashSet<>();
		for (String method : methods) {
			operations.add(McpOperationType.fromJsonRpcMethod(method));
			Assertions.assertEquals(method, metricMethod.invoke(null, method));
		}
		Assertions.assertEquals(Set.of(McpOperationType.values()).size() - 1, operations.size());
		Assertions.assertEquals(McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD, metricMethod.invoke(null, "attacker/unique"));
	}

	@Test
	public void interruptedProgressWaiterLeavesWhileEarlierReportIsBlocked() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		DefaultMcpServer server = server(new RecordingCollector());
		McpProgressReporter reporter = reporter(server, (progress, total, message) -> {
			entered.countDown(); release.await(); return true;
		});
		Thread first = new Thread(() -> reporter.report(McpProgressUpdate.withProgress(1.0).build()));
		Thread waiting = new Thread(() -> reporter.report(McpProgressUpdate.withProgress(2.0).build()));
		try {
			first.start(); Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
			waiting.start(); waiting.interrupt(); waiting.join(1000);
			Assertions.assertFalse(waiting.isAlive(), "A waiting report must honor cancellation without waiting for the writer.");
		} finally {
			release.countDown(); first.interrupt(); waiting.interrupt();
			first.join(2000); waiting.join(2000);
			Assertions.assertFalse(first.isAlive()); Assertions.assertFalse(waiting.isAlive());
		}
	}

	@Test
	public void blockedProgressReportersLeaveVirtualThreadCarriersAvailable() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(Runtime.version().feature() >= 21);
		Path output = Files.createTempFile("soklet-mcp-progress-carriers-", ".log");
		try {
			Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-Xmx128m", "-XX:ActiveProcessorCount=2", "-Djdk.virtualThreadScheduler.parallelism=2",
					"-Djdk.virtualThreadScheduler.maxPoolSize=2", "-cp",
					System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
					McpMetricBackpressureTests.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
			try {
				Assertions.assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Progress carrier probe timed out.");
				Assertions.assertTrue(Files.size(output) <= 65_536);
				Assertions.assertEquals(0, process.exitValue(), Files.readString(output));
			} finally {
				process.destroyForcibly(); Assertions.assertTrue(process.waitFor(2, TimeUnit.SECONDS));
			}
		} finally { Files.deleteIfExists(output); }
	}

	public static void main(String[] args) throws Exception {
		ExecutorService executor = (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
		List<McpOutboundChannel> channels = new ArrayList<>();
		CountDownLatch blocked = new CountDownLatch(2);
		DefaultMcpServer server = server(new RecordingCollector());
		try {
			for (int index = 0; index < 2; index++) {
				McpOutboundChannel channel = new McpOutboundChannel(1, 1, 1, System::nanoTime, new McpOutboundChannel.Listener() {
					@Override public void didWrite(long bytes, long timestamp) {}
					@Override public void didApplyBackpressure() { blocked.countDown(); }
					@Override public void didTerminate(StreamTerminationReason reason, Throwable cause) {}
				});
				channels.add(channel); Assertions.assertTrue(channel.enqueue(new byte[]{1}));
				McpProgressReporter reporter = reporter(server, (progress, total, message) -> channel.enqueue(new byte[]{2}));
				executor.submit(() -> reporter.report(McpProgressUpdate.withProgress(1.0).build()));
			}
			Assertions.assertTrue(blocked.await(2, TimeUnit.SECONDS));
			Thread.sleep(100);
			Assertions.assertEquals("available", executor.submit(() -> "available").get(2, TimeUnit.SECONDS));
		} finally {
			for (McpOutboundChannel channel : channels) channel.close(StreamTerminationReason.APPLICATION_CANCELED, null);
			executor.shutdownNow(); Assertions.assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	@Test
	public void heldTransitionDoesNotDeferAnotherOperationsMetrics() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (McpApplicationExecutionObserver.MetricDeferral scope = observer.beginRequestTransitionDeferral()) {
			observer.recordRequestAccepted();
			executor.submit(() -> invokeUnchecked(deliveryUnchecked(server), "recordAndDrain",
					McpMetricsEvent.class, McpMetricsEvent.connectionRejected())).get(1, TimeUnit.SECONDS);
			Assertions.assertEquals(List.of(McpMetricsEvent.connectionRejected()), collector.events,
					"Only this transition's provisional record may be withheld.");
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		awaitEvents(collector, List.of(McpMetricsEvent.connectionRejected(), McpMetricsEvent.requestAccepted()));
	}

	@Test
	public void closingTransitionDoesNotRunABlockedReentrantCollectorInline() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<DefaultMcpServer> serverReference = new AtomicReference<>();
		DefaultMcpServer server = server(new MetricsCollector() {
			@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				serverReference.get().getDiagnostics();
				entered.countDown();
				await(release);
			}
		});
		serverReference.set(server);
		McpApplicationExecutionObserver observer = observer(server);
		McpApplicationExecutionObserver.MetricDeferral scope = observer.beginRequestTransitionDeferral();
		observer.recordRequestAccepted();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> closing = executor.submit(scope::close);
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
			closing.get(1, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void nestedTransitionRecordsWaitForTheOutermostScopeAndKeepTheirOrder() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (McpApplicationExecutionObserver.MetricDeferral outer = observer.beginRequestTransitionDeferral()) {
			observer.recordRequestAccepted();
			try (McpApplicationExecutionObserver.MetricDeferral inner = observer.beginRequestTransitionDeferral()) {
				observer.recordHandlerQueued();
			}
			Assertions.assertTrue(collector.events.isEmpty());
			executor.submit(() -> invokeUnchecked(deliveryUnchecked(server), "recordAndDrain",
					McpMetricsEvent.class, McpMetricsEvent.connectionRejected())).get(1, TimeUnit.SECONDS);
			Assertions.assertEquals(List.of(McpMetricsEvent.connectionRejected()), collector.events);
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		awaitEvents(collector, List.of(McpMetricsEvent.connectionRejected(),
				McpMetricsEvent.requestAccepted(), McpMetricsEvent.handlerQueued()));
	}

	@Test
	public void asynchronousDrainCannotPublishAProvisionalFailureBeforeItsDiscard() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (McpApplicationExecutionObserver.MetricDeferral scope = observer.beginRequestTransitionDeferral()) {
			McpApplicationExecutionObserver.PendingMetricRecord record = observer.recordTransportFailure(
					MetricsCollector.TransportFailureReason.RESPONSE_READY_ERROR);
			executor.submit(observer::drainAsynchronously).get(1, TimeUnit.SECONDS);
			Assertions.assertTrue(collector.events.isEmpty());
			observer.discardPendingMetric(record);
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		invoke(delivery(server), "recordAndDrain", McpMetricsEvent.class, McpMetricsEvent.connectionRejected());
		awaitEvents(collector, List.of(McpMetricsEvent.connectionRejected()));
	}

	@Test
	public void closingAnotherThreadsScopeDoesNotReleaseAnIndependentOrReusedScope() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (McpApplicationExecutionObserver.MetricDeferral outer = observer.beginRequestTransitionDeferral()) {
			observer.recordRequestAccepted();
			McpApplicationExecutionObserver.MetricDeferral first = executor.submit(() -> {
				McpApplicationExecutionObserver.MetricDeferral scope = observer.beginRequestTransitionDeferral();
				observer.recordHandlerQueued();
				return scope;
			}).get(1, TimeUnit.SECONDS);
			first.close();
			awaitEvents(collector, List.of(McpMetricsEvent.handlerQueued()));
			try (McpApplicationExecutionObserver.MetricDeferral second = executor.submit(() -> {
				McpApplicationExecutionObserver.MetricDeferral scope = observer.beginRequestTransitionDeferral();
				observer.recordHandlerDequeued();
				return scope;
			}).get(1, TimeUnit.SECONDS)) {
				first.close();
				observer.drainAsynchronously();
				Assertions.assertEquals(List.of(McpMetricsEvent.handlerQueued()), collector.events,
						"Closing the old scope again must not release either open scope.");
			}
			awaitEvents(collector, List.of(McpMetricsEvent.handlerQueued(), McpMetricsEvent.handlerDequeued()));
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		awaitEvents(collector, List.of(McpMetricsEvent.handlerQueued(),
				McpMetricsEvent.handlerDequeued(), McpMetricsEvent.requestAccepted()));
	}

	@Test
	public void closingATransitionDoesNotReleaseTheLifecycleGate() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		server.beginNonwaitingMcpMetricsDeferral();
		try {
			try (McpApplicationExecutionObserver.MetricDeferral scope = observer.beginRequestTransitionDeferral()) {
				observer.recordRequestAccepted();
			}
			executor.submit(() -> invokeUnchecked(deliveryUnchecked(server), "recordAndDrain",
					McpMetricsEvent.class, McpMetricsEvent.connectionRejected())).get(1, TimeUnit.SECONDS);
			Assertions.assertTrue(collector.events.isEmpty());
		} finally {
			server.endMcpMetricsDeferral();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		awaitEvents(collector, List.of(McpMetricsEvent.requestAccepted(), McpMetricsEvent.connectionRejected()));
	}

	@Test
	public void withheldRecordsShareTheQueueBoundAndAllowLifecycleReclamation() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		McpApplicationExecutionObserver observer = observer(server);
		Object delivery = delivery(server);
		Field capacityField = DefaultMcpServer.class.getDeclaredField("MAXIMUM_PENDING_MCP_METRIC_EVENTS");
		capacityField.setAccessible(true);
		int capacity = capacityField.getInt(null);
		Field queueField = delivery.getClass().getDeclaredField("pendingEvents");
		queueField.setAccessible(true);
		Field deferredField = delivery.getClass().getDeclaredField("deferredEvents");
		deferredField.setAccessible(true);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		McpMetricsEvent started = McpMetricsEvent.serverStarted();
		McpMetricsEvent stopped = McpMetricsEvent.serverStopped(ShutdownComponentDisposition.GRACEFUL_TERMINATION);
		try (McpApplicationExecutionObserver.MetricDeferral scope = observer.beginRequestTransitionDeferral()) {
			McpApplicationExecutionObserver.PendingMetricRecord first = observer.recordRequestAccepted();
			McpApplicationExecutionObserver.PendingMetricRecord omitted = first;
			for (int index = 1; index < capacity + 32; index++)
				omitted = observer.recordRequestAccepted();
			Assertions.assertEquals(capacity, ((Queue<?>) queueField.get(delivery)).size());
			Assertions.assertEquals(capacity, ((Map<?, ?>) deferredField.get(delivery)).size());
			observer.discardPendingMetric(omitted);
			executor.submit(() -> {
				invokeUnchecked(delivery, "record", McpMetricsEvent.class, started);
				invokeUnchecked(delivery, "record", McpMetricsEvent.class, stopped);
				invokeUnchecked(delivery, "drain");
			}).get(1, TimeUnit.SECONDS);
			awaitEvents(collector, List.of(started, stopped));
			observer.discardPendingMetric(first);
			Assertions.assertEquals(capacity - 2, ((Queue<?>) queueField.get(delivery)).size());
			Assertions.assertEquals(capacity - 2, ((Map<?, ?>) deferredField.get(delivery)).size());
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (collector.events.size() < capacity && System.nanoTime() - deadline < 0L)
			Thread.sleep(5L);
		Assertions.assertEquals(capacity, collector.events.size());
		Assertions.assertEquals(List.of(started, stopped), collector.events.subList(0, 2));
		Assertions.assertEquals(java.util.Collections.nCopies(capacity - 2, McpMetricsEvent.requestAccepted()),
				collector.events.subList(2, capacity));
		Assertions.assertTrue(((Queue<?>) queueField.get(delivery)).isEmpty());
		Assertions.assertTrue(((Map<?, ?>) deferredField.get(delivery)).isEmpty());
	}

	private static McpApplicationExecutionObserver observer(DefaultMcpServer server) throws Exception {
		return (McpApplicationExecutionObserver) invoke(server, "applicationExecutionObserver");
	}

	private static Object deliveryUnchecked(DefaultMcpServer server) {
		try { return delivery(server); }
		catch (Exception exception) { throw new AssertionError(exception); }
	}

	private static void awaitEvents(RecordingCollector collector, List<McpMetricsEvent> expected)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (collector.events.size() < expected.size() && System.nanoTime() - deadline < 0L)
			Thread.sleep(5L);
		Assertions.assertEquals(expected, collector.events);
	}

	@Test
	public void asynchronousDrainDoesNotWaitForLifecycleDeferral() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		Object delivery = delivery(server);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		server.beginNonwaitingMcpMetricsDeferral();
		try {
			invoke(delivery, "record", McpMetricsEvent.class,
					McpMetricsEvent.connectionAccepted());
			Future<?> drain = executor.submit(() -> invokeUnchecked(delivery,
					"drainAsynchronously"));
			drain.get(1, TimeUnit.SECONDS);
			Assertions.assertTrue(collector.events.isEmpty());
		} finally {
			server.endMcpMetricsDeferral();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		Assertions.assertEquals(List.of(McpMetricsEvent.connectionAccepted()),
				collector.events);
	}

	@Test
	public void lifecycleDeferralDoesNotWaitForAnInFlightCollector() throws Exception {
		CountDownLatch collectorEntered = new CountDownLatch(1);
		CountDownLatch collectorRelease = new CountDownLatch(1);
		DefaultMcpServer server = server(new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				collectorEntered.countDown();
				await(collectorRelease);
			}
		});
		Object delivery = delivery(server);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<?> deferred = null;
		try {
			invoke(delivery, "record", McpMetricsEvent.class,
					McpMetricsEvent.connectionAccepted());
			Future<?> drain = executor.submit(() -> invokeUnchecked(delivery,
					"drainAsynchronously"));
			Assertions.assertTrue(collectorEntered.await(5, TimeUnit.SECONDS));
			deferred = executor.submit(server::beginMcpMetricsDeferral);
			deferred.get(1, TimeUnit.SECONDS);
			collectorRelease.countDown();
			drain.get(5, TimeUnit.SECONDS);
		} finally {
			collectorRelease.countDown();
			if (deferred != null) {
				deferred.get(5, TimeUnit.SECONDS);
				server.endMcpMetricsDeferral();
			}
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void blockedProgressWriteDoesNotDeferUnrelatedMetrics() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		CountDownLatch writeEntered = new CountDownLatch(1);
		CountDownLatch writeRelease = new CountDownLatch(1);
		McpProgressReporter reporter = reporter(server, (progress, total, message) -> {
			writeEntered.countDown();
			if (!writeRelease.await(5, TimeUnit.SECONDS))
				throw new AssertionError("The progress-write barrier was not released.");
			return true;
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> progress = executor.submit(() -> reporter.report(
					McpProgressUpdate.withProgress(1.0d).build()));
			Assertions.assertTrue(writeEntered.await(5, TimeUnit.SECONDS));
			invoke(delivery(server), "recordAndDrain", McpMetricsEvent.class,
					McpMetricsEvent.connectionAccepted());
			Assertions.assertEquals(List.of(McpMetricsEvent.connectionAccepted()),
					collector.events,
					"A pending write must not defer another operation's metrics.");
			writeRelease.countDown();
			progress.get(5, TimeUnit.SECONDS);
			reporter.report(McpProgressUpdate.withProgress(1.0d).build());
			Assertions.assertThrows(IllegalArgumentException.class, () ->
					reporter.report(McpProgressUpdate.withProgress(0.0d).build()));
			Assertions.assertEquals(List.of(McpMetricsEvent.connectionAccepted(),
					McpMetricsEvent.progressEmitted("/mcp/metrics", "tools/call")),
					collector.events);
		} finally {
			writeRelease.countDown();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void pendingMetricQueueIsBoundedAndRetainsLifecycleEvents() throws Exception {
		RecordingCollector collector = new RecordingCollector();
		DefaultMcpServer server = server(collector);
		Object delivery = delivery(server);
		Field capacityField = DefaultMcpServer.class.getDeclaredField(
				"MAXIMUM_PENDING_MCP_METRIC_EVENTS");
		capacityField.setAccessible(true);
		int capacity = capacityField.getInt(null);
		Assertions.assertTrue(capacity > 0 && capacity <= 16_384);
		Field queueField = delivery.getClass().getDeclaredField("pendingEvents");
		queueField.setAccessible(true);
		server.beginNonwaitingMcpMetricsDeferral();
		try {
			Object omitted = null;
			for (int index = 0; index < capacity + 32; index++)
				omitted = invoke(delivery, "record", McpMetricsEvent.class,
						McpMetricsEvent.connectionAccepted());
			invoke(delivery, "discard", omitted.getClass(), omitted);
			invoke(delivery, "record", McpMetricsEvent.class,
					McpMetricsEvent.serverStarted());
			invoke(delivery, "record", McpMetricsEvent.class,
					McpMetricsEvent.serverStopped(
							ShutdownComponentDisposition.GRACEFUL_TERMINATION));
			Assertions.assertEquals(capacity, ((Queue<?>) queueField.get(delivery)).size());
		} finally {
			server.endMcpMetricsDeferral();
		}
		Assertions.assertEquals(capacity, collector.events.size());
		Assertions.assertEquals(List.of(McpMetricsEvent.serverStarted(),
				McpMetricsEvent.serverStopped(
						ShutdownComponentDisposition.GRACEFUL_TERMINATION)),
				collector.events.subList(capacity - 2, capacity));
	}

	@Test
	public void asynchronousDrainRacingACollectorDoesNotLoseItsWakeup() throws Exception {
		CountDownLatch collectorEntered = new CountDownLatch(1);
		CountDownLatch collectorRelease = new CountDownLatch(1);
		CountDownLatch laterDelivered = new CountDownLatch(1);
		DefaultMcpServer server = server(new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				if (event instanceof McpMetricsEvent.ConnectionAccepted) {
					collectorEntered.countDown();
					await(collectorRelease);
				} else
					laterDelivered.countDown();
			}
		});
		Object delivery = delivery(server);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(() -> invokeUnchecked(delivery,
					"recordAndDrain", McpMetricsEvent.class,
					McpMetricsEvent.connectionAccepted()));
			Assertions.assertTrue(collectorEntered.await(5, TimeUnit.SECONDS));
			invoke(delivery, "record", McpMetricsEvent.class,
					McpMetricsEvent.handlerExecutionStarted());
			Future<?> drain = executor.submit(() -> invokeUnchecked(delivery,
					"drainAsynchronously"));
			drain.get(1, TimeUnit.SECONDS);
			collectorRelease.countDown();
			first.get(5, TimeUnit.SECONDS);
			Assertions.assertTrue(laterDelivered.await(5, TimeUnit.SECONDS),
					"The pending event needs delivery without another metric or signal.");
		} finally {
			collectorRelease.countDown();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void releasingAnOrdinaryDeferralResignalsAnEarlierAsynchronousDrain()
			throws Exception {
		CountDownLatch delivered = new CountDownLatch(1);
		DefaultMcpServer server = server(new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				delivered.countDown();
			}
		});
		Object delivery = delivery(server);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		invoke(delivery, "beginNonwaitingDeferral");
		try {
			invoke(delivery, "record", McpMetricsEvent.class,
					McpMetricsEvent.connectionAccepted());
			executor.submit(() -> invokeUnchecked(delivery,
					"drainAsynchronously")).get(1, TimeUnit.SECONDS);
		} finally {
			// Release before joining: the pre-fix drain ignores interruption.
			invoke(delivery, "endDeferral");
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		Assertions.assertTrue(delivered.await(5, TimeUnit.SECONDS));
	}

	private static DefaultMcpServer server(MetricsCollector collector) {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp/metrics",
				McpImplementation.withNameAndVersion("metrics-test", "4.0.0").build(),
				Set.of(McpProtocolVersion.V2026_07_28)).build();
		DefaultMcpServer server = (DefaultMcpServer) McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).build();
		server.initialize(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector).build());
		return server;
	}

	private static Object delivery(DefaultMcpServer server) throws Exception {
		Field field = DefaultMcpServer.class.getDeclaredField("mcpMetricEventDelivery");
		field.setAccessible(true);
		return field.get(server);
	}

	private static McpProgressReporter reporter(DefaultMcpServer server,
			ProgressEmitter emitter) throws Exception {
		Class<?> reporterType = java.util.Arrays.stream(DefaultMcpServer.class.getDeclaredClasses())
				.filter(type -> type.getSimpleName().equals("DefaultMcpProgressReporter"))
				.findFirst().orElseThrow();
		Constructor<?> constructor = reporterType.getDeclaredConstructor(
				DefaultMcpServer.class, CancelationToken.class, ProgressEmitter.class,
				String.class, String.class);
		constructor.setAccessible(true);
		CancelationToken token = new CancelationToken() {
			@Override
			public Boolean isCanceled() { return false; }

			@Override
			public Optional<StreamTerminationReason> getCancelationReason() {
				return Optional.empty();
			}

			@Override
			public Optional<Throwable> getCancelationCause() {
				return Optional.empty();
			}

			@Override
			public CallbackRegistration onCancel(Runnable callback) { return () -> {}; }
		};
		return (McpProgressReporter) constructor.newInstance(server,
				token, emitter, "/mcp/metrics", "tools/call");
	}

	private static Object invoke(Object target, String method) throws Exception {
		Method reflected = target.getClass().getDeclaredMethod(method);
		reflected.setAccessible(true);
		return reflected.invoke(target);
	}

	private static Object invoke(Object target, String method, Class<?> parameterType,
			Object argument) throws Exception {
		Method reflected = target.getClass().getDeclaredMethod(method, parameterType);
		reflected.setAccessible(true);
		return reflected.invoke(target, argument);
	}

	private static void invokeUnchecked(Object target, String method) {
		try {
			invoke(target, method);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private static void invokeUnchecked(Object target, String method,
			Class<?> parameterType, Object argument) {
		try {
			invoke(target, method, parameterType, argument);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS))
				throw new AssertionError("Metric barrier was not released.");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static final class RecordingCollector implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
			this.events.add(event);
		}
	}
}
