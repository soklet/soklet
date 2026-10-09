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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static com.soklet.McpLegacySessionTransportPublicRuntimeTests.*;

/** Public configuration, exact revision responses and physical accounting for custom executors. */
@Timeout(60)
class McpHandlerExecutorPublicRuntimeTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28);

	@TestFactory
	java.util.stream.Stream<DynamicTest> executorBoundariesForEachRevision() {
		return java.util.stream.Stream.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28).flatMap(version -> java.util.stream.Stream.of(
				DynamicTest.dynamicTest("direct handoff / " + version, () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> directHandoffExecutesAllAcceptedRequestsWithoutCapacityFailures(version))),
				DynamicTest.dynamicTest("submission rejection / " + version, () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> executorSubmissionRejectionUsesTheFixedCapacityResponseAndRecovers(version))),
				DynamicTest.dynamicTest("policy submission rejection / " + version, () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> policySubmissionRejectionUsesTheFixedCapacityResponseAndRecovers(version))),
				DynamicTest.dynamicTest("caller runs / " + version, () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> callerRunsCannotMoveAnApplicationHandlerOntoTheProtocolThread(version))),
				DynamicTest.dynamicTest("handler exception / " + version, () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> aHandlerThrownRejectionRemainsAnApplicationFailure(version)))));
	}

	void directHandoffExecutesAllAcceptedRequestsWithoutCapacityFailures(McpProtocolVersion version) throws Exception {
		try (Fixture fixture = new Fixture(new Executor(false, false), true, false)) {
			List<RawClient> queued = new ArrayList<>();
			try (RawClient active = fixture.open(version, "active")) {
				assertTrue(fixture.firstEntered.await(5, TimeUnit.SECONDS));
				for (int index = 0; index < 3; index++) {
					queued.add(fixture.open(version, "queued-" + index));
					int expected = index + 1;
					awaitCondition(() -> fixture.server.getDiagnostics().getRequestHandlerQueueDepth() == expected);
				}
				fixture.releaseFirst.countDown();
				assertSuccess(active);
				for (RawClient client : queued) assertSuccess(client);
				awaitCondition(() -> fixture.outcomes.size() == 4 && fixture.metricCount(McpMetricsEvent.HandlerExecutionFinished.class) == 4);
				assertEquals(4, fixture.handlerCalls.get());
				assertEquals(List.of(McpRequestOutcome.COMPLETE, McpRequestOutcome.COMPLETE,
						McpRequestOutcome.COMPLETE, McpRequestOutcome.COMPLETE), fixture.outcomes);
				assertEquals(0, fixture.metricCount(McpMetricsEvent.HandlerCapacityRejected.class));
				assertEquals(3, fixture.metricCount(McpMetricsEvent.HandlerQueued.class));
				assertEquals(3, fixture.metricCount(McpMetricsEvent.HandlerDequeued.class));
				assertEquals(0, fixture.server.getDiagnostics().getActiveHandlerExecutions());
				assertEquals(0, fixture.server.getDiagnostics().getRequestHandlerQueueDepth());
			} finally {
				fixture.releaseFirst.countDown();
				for (RawClient client : queued) client.close();
			}
		}
	}

	void executorSubmissionRejectionUsesTheFixedCapacityResponseAndRecovers(McpProtocolVersion version) throws Exception {
		Executor executor = new Executor(true, false);
		try (Fixture fixture = new Fixture(executor, false, false)) {
			try (RawClient client = fixture.open(version, "rejected")) { assertCapacity(client, "rejected"); }
			awaitCondition(() -> fixture.outcomes.size() == 1 && fixture.metricCount(McpMetricsEvent.HandlerCapacityRejected.class) == 1);
			assertEquals(0, fixture.handlerCalls.get());
			assertEquals(List.of(McpRequestOutcome.REJECTED), fixture.outcomes);
			assertEquals(List.of(List.of(executor.rejection)), fixture.failures);
			assertEquals(0, fixture.server.getDiagnostics().getActiveHandlerExecutions());
			try (RawClient client = fixture.open(version, "recovered")) { assertSuccess(client); }
			awaitCondition(() -> fixture.outcomes.size() == 2);
			assertEquals(1, fixture.handlerCalls.get());
			assertEquals(List.of(McpRequestOutcome.REJECTED, McpRequestOutcome.COMPLETE), fixture.outcomes);
		}
	}

	void callerRunsCannotMoveAnApplicationHandlerOntoTheProtocolThread(McpProtocolVersion version) throws Exception {
		Executor executor = new Executor(false, true);
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		executor.execute(() -> {
			occupied.countDown();
			try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
		});
		assertTrue(occupied.await(5, TimeUnit.SECONDS));
		try (Fixture fixture = new Fixture(executor, false, false)) {
			try (RawClient client = fixture.open(version, "inline")) { assertCapacity(client, "inline"); }
			awaitCondition(() -> fixture.outcomes.size() == 1);
			assertEquals(0, fixture.handlerCalls.get());
			assertEquals(List.of(McpRequestOutcome.REJECTED), fixture.outcomes);
			release.countDown();
			awaitCondition(() -> executor.getActiveCount() == 0);
			try (RawClient client = fixture.open(version, "recovered")) { assertSuccess(client); }
			assertEquals(1, fixture.handlerCalls.get());
		} finally {
			release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	void policySubmissionRejectionUsesTheFixedCapacityResponseAndRecovers(McpProtocolVersion version) throws Exception {
		AtomicInteger policyCalls = new AtomicInteger();
		McpCatalogAccessPolicy policy = McpCatalogAccessPolicy.fromEvaluators(
				(requestContext, toolRegistration, invocationFeatures) -> { policyCalls.incrementAndGet(); return true; },
				(requestContext, promptRegistration, invocationFeatures) -> true);
		try (Fixture fixture = new Fixture(new Executor(true, false), false, false, policy)) {
			try (RawClient client = fixture.openCatalog(version, "policy-rejected")) { assertCapacity(client, "policy-rejected"); }
			awaitCondition(() -> fixture.outcomes.size() == 1);
			assertEquals(0, policyCalls.get());
			assertEquals(0, fixture.handlerCalls.get());
			assertEquals(List.of(McpRequestOutcome.REJECTED), fixture.outcomes);
			assertEquals(0, fixture.server.getDiagnostics().getActiveHandlerExecutions());
			try (RawClient client = fixture.openCatalog(version, "policy-recovered")) {
				Head head = client.readHead();
				String body = client.readBody(head);
				assertEquals(200, head.status(), body);
				assertTrue(body.contains("\"name\":\"work\""), body);
			}
			assertEquals(1, policyCalls.get());
		}
	}

	void aHandlerThrownRejectionRemainsAnApplicationFailure(McpProtocolVersion version) throws Exception {
		try (Fixture fixture = new Fixture(new Executor(false, false), false, true)) {
			try (RawClient client = fixture.open(version, "handler-failure")) {
				Head head = client.readHead();
				String body = client.readBody(head);
				assertEquals(version == McpProtocolVersion.V2026_07_28 ? 500 : 200, head.status(), body);
				assertTrue(body.contains("\"code\":-32603"), body);
				assertFalse(body.contains("handler-private-detail"), body);
			}
			awaitCondition(() -> fixture.outcomes.size() == 1);
			assertEquals(1, fixture.handlerCalls.get());
			assertEquals(List.of(McpRequestOutcome.INTERNAL_ERROR), fixture.outcomes);
			assertEquals(List.of(List.of(fixture.handlerFailure)), fixture.failures);
			assertEquals(0, fixture.metricCount(McpMetricsEvent.HandlerCapacityRejected.class));
		}
	}

	private static void assertSuccess(RawClient client) throws Exception {
		Head head = client.readHead();
		String body = client.readBody(head);
		assertEquals(200, head.status(), body);
		assertTrue(body.contains("\"text\":\"ran\""), body);
	}

	private static void assertCapacity(RawClient client, String id) throws Exception {
		Head head = client.readHead();
		String body = client.readBody(head);
		assertEquals(503, head.status(), body);
		assertEquals("application/json", head.header("Content-Type"));
		assertEquals("no-store", head.header("Cache-Control"));
		assertNull(head.header("Retry-After"));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}", body);
	}

	private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L) Thread.sleep(5L);
		assertTrue(condition.getAsBoolean());
	}

	private static final class Fixture implements AutoCloseable {
		private final CountDownLatch firstEntered = new CountDownLatch(1);
		private final CountDownLatch releaseFirst = new CountDownLatch(1);
		private final AtomicInteger handlerCalls = new AtomicInteger();
		private final RejectedExecutionException handlerFailure = new RejectedExecutionException("handler-private-detail");
		private final List<McpRequestOutcome> outcomes = new CopyOnWriteArrayList<>();
		private final List<List<Throwable>> failures = new CopyOnWriteArrayList<>();
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		private final McpServer server;
		private final Soklet soklet;

		private Fixture(Executor executor, boolean blockFirst, boolean failHandler) {
			this(executor, blockFirst, failHandler, null);
		}

		private Fixture(Executor executor, boolean blockFirst, boolean failHandler, McpCatalogAccessPolicy catalogAccessPolicy) {
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("work", VERSIONS)
					.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
						int call = handlerCalls.incrementAndGet();
						if (blockFirst && call == 1) { firstEntered.countDown(); releaseFirst.await(); }
						if (failHandler) throw handlerFailure;
						return McpCompleteResult.fromToolOutput(McpToolOutput.fromText("ran"));
					}).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("executor-test", "1").build(), VERSIONS)
					.toolRegistrations(List.of(tool)).build();
			server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.catalogAccessPolicy(catalogAccessPolicy)
					.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed())
					.requestHandlerConcurrency(1).requestHandlerQueueCapacity(3)
					.requestTimeout(Duration.ofSeconds(10))
					.requestHandlerExecutorServiceSupplier(() -> executor).build();
			soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
					.metricsCollector(new MetricsCollector() {
						@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) { events.add(event); }
					}).lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) {}
						@Override public void didFinishMcpRequestHandling(McpRequestContext requestContext,
								McpRequestOutcome requestOutcome, McpJsonRpcError jsonRpcError,
								Duration requestDuration, List<Throwable> throwables) {
							failures.add(List.copyOf(throwables));
							outcomes.add(requestOutcome);
						}
					}).lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
			soklet.start();
		}

		private int metricCount(Class<? extends McpMetricsEvent> type) {
			return (int) events.stream().filter(type::isInstance).count();
		}

		private RawClient open(McpProtocolVersion version, String id) throws Exception {
			return open(version, id, false);
		}

		private RawClient openCatalog(McpProtocolVersion version, String id) throws Exception {
			return open(version, id, true);
		}

		private RawClient open(McpProtocolVersion version, String id, boolean catalog) throws Exception {
			String method = catalog ? "tools/list" : "tools/call";
			String metadata = version == McpProtocolVersion.V2026_07_28
					? "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}" : "";
			String params = catalog ? metadata : "\"name\":\"work\",\"arguments\":{}" + (metadata.isEmpty() ? "" : "," + metadata);
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
					+ "\",\"method\":\"" + method + "\",\"params\":{" + params + "}}";
			List<HeaderValue> headers = new ArrayList<>();
			headers.add(new HeaderValue("MCP-Protocol-Version", version.getWireValue()));
			if (version == McpProtocolVersion.V2026_07_28) {
				headers.add(new HeaderValue("Mcp-Method", method));
				if (!catalog) headers.add(new HeaderValue("Mcp-Name", "work"));
			}
			return new RawClient(server.getDiagnostics().getBoundAddress().orElseThrow().getPort(), "POST", "/mcp", body, headers);
		}

		@Override public void close() { releaseFirst.countDown(); soklet.close(); }
	}

	private static final class Executor extends ThreadPoolExecutor {
		private final AtomicInteger submissions = new AtomicInteger();
		private final boolean rejectFirst;
		private final RejectedExecutionException rejection = new RejectedExecutionException("executor-private-detail");
		private Executor(boolean rejectFirst, boolean callerRuns) {
			super(1, 1, 0L, TimeUnit.SECONDS, new SynchronousQueue<>(),
					runnable -> new Thread(runnable, "mcp-public-executor-test"), callerRuns ? new CallerRunsPolicy() : new AbortPolicy());
			this.rejectFirst = rejectFirst;
		}
		@Override public void execute(Runnable command) {
			if (submissions.incrementAndGet() == 1 && rejectFirst) throw rejection;
			super.execute(command);
		}
	}
}
