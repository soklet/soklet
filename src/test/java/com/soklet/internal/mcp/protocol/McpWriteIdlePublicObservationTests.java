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

package com.soklet.internal.mcp.protocol;

import com.soklet.CancelationToken;
import com.soklet.CorsAuthorizer;
import com.soklet.LifecycleObserver;
import com.soklet.LifecyclePolicy;
import com.soklet.McpAdmissionController;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonRpcError;
import com.soklet.McpMetricsEvent;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpProtocolVersion;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpServer;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpToolRegistration;
import com.soklet.MetricsCollector;
import com.soklet.Request;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.WritableSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real admitted requests with deliberately unwritten SSE bodies. */
@Timeout(30)
class McpWriteIdlePublicObservationTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final Duration WRITE_TIMEOUT = Duration.ofHours(1);
	private static final Duration REQUEST_TIMEOUT = Duration.ofDays(1);
	private static final List<McpProtocolVersion> VERSIONS = List.of(
			McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
			McpProtocolVersion.V2026_07_28);

	@TestFactory
	Stream<DynamicTest> stalledWriterIsWriteFailedBeforeTheRequestDeadline() {
		return VERSIONS.stream().map(version ->
				DynamicTest.dynamicTest(version.getWireValue(),
						() -> observeUnwrittenStream(version, false)));
	}

	@TestFactory
	Stream<DynamicTest> expiredRequestDeadlineRemainsDistinctFromWriteFailure() {
		return VERSIONS.stream().map(version ->
				DynamicTest.dynamicTest(version.getWireValue(),
						() -> observeUnwrittenStream(version, true)));
	}

	private static void observeUnwrittenStream(McpProtocolVersion version,
			boolean expireRequestDeadline) throws Exception {
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		CountDownLatch responseOffered = new CountDownLatch(1);
		CountDownLatch requestFinished = new CountDownLatch(1);
		CountDownLatch metricsFinished = new CountDownLatch(1);
		CountDownLatch transportFailureRecorded = new CountDownLatch(1);
		AtomicReference<CancelationToken> token = new AtomicReference<>();
		AtomicReference<MicrohttpResponse> response = new AtomicReference<>();
		List<McpRequestOutcome> outcomes = new CopyOnWriteArrayList<>();
		List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("stalled.writer", Set.of(version)).jsonObjectArguments()
				.handler((requestContext, arguments, invocationFeatures) -> {
					token.set(invocationFeatures.require(CancelationToken.class));
					try {
						invocationFeatures.require(McpProgressReporter.class).report(
								McpProgressUpdate.withProgress(1.0d).build());
						if (!expireRequestDeadline)
							assertTrue(releaseHandler.await(5, TimeUnit.SECONDS));
						return McpCompleteResult.fromToolText("finished");
					} finally {
						handlerExited.countDown();
					}
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("write-idle", "4.0.0").build(),
				Set.of(version)).toolRegistrations(List.of(tool)).build();
		McpServer server = McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.host(LOOPBACK).allowedHosts(Set.of(LOOPBACK))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.requestTimeout(REQUEST_TIMEOUT).writeTimeout(WRITE_TIMEOUT)
				.keepAliveInterval(Duration.ofMinutes(30)).build();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didFinishMcpRequestHandling(McpRequestContext requestContext,
					McpRequestOutcome requestOutcome, @Nullable McpJsonRpcError error,
					Duration duration, List<Throwable> throwables) {
				outcomes.add(requestOutcome);
				requestFinished.countDown();
			}
		};
		MetricsCollector collector = new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				events.add(event);
				if (event instanceof McpMetricsEvent.RequestFinished)
					metricsFinished.countDown();
				if (event instanceof McpMetricsEvent.TransportFailure)
					transportFailureRecorded.countDown();
			}
		};
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObservers(List.of(observer)).metricsCollector(collector)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.gracefulShutdownTimeout(Duration.ofSeconds(2))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
		WritableSource source = null;
		try {
			soklet.start();
			InetSocketAddress address = server.getDiagnostics().getBoundAddress().orElseThrow();
			McpHttpServerRuntime runtime = (McpHttpServerRuntime)
					field(field(server, "runtimeBridge"), "runtime");
			MicrohttpRequest request = request(address, version);
			Method submit = McpHttpServerRuntime.class.getDeclaredMethod("submitRequest",
					ThreadPoolExecutor.class, McpApplicationExecution.class,
					InetSocketAddress.class, MicrohttpRequest.class, Request.class,
					McpSimulationRuntime.class, Runnable.class, Consumer.class);
			submit.setAccessible(true);
			submit.invoke(runtime, field(runtime, "requestProcessor"),
					field(runtime, "applicationExecution"), address, request, null, null,
					null, (Consumer<MicrohttpResponse>) offered -> {
						response.set(offered);
						responseOffered.countDown();
					});
			assertTrue(responseOffered.await(5, TimeUnit.SECONDS));
			assertTrue(response.get().streaming());
			Method newBodySource = MicrohttpResponse.class.getDeclaredMethod("newBodySource");
			newBodySource.setAccessible(true);
			source = (WritableSource) newBodySource.invoke(response.get());
			source.writeReadyCallback(() -> {});
			source.start();
			assertEquals(1, runtime.diagnosticsSnapshot().activeRequestStreams());
			assertFalse(token.get().isCanceled());
			Object control = ((Map<?, ?>) field(runtime, "requestControls")).get(request);
			assertNotNull(control);
			long deadline = (Long) field(control, "deadlineNanos");
			long now;
			if (expireRequestDeadline) {
				assertTrue(handlerExited.await(5, TimeUnit.SECONDS));
				// The terminal result must be reserved before the protocol timer owns
				// the request deadline; the unwritten body still holds the stream open.
				awaitTerminalReservation(control);
				now = deadline;
			} else {
				now = System.nanoTime() + WRITE_TIMEOUT.toNanos();
				assertTrue(now - deadline < 0L, "The request deadline must remain open");
			}
			// Start the writer but never write a byte. Drive the real control's timer forward
			// rather than allocating enough output to fill an OS socket buffer.
			Method timer = control.getClass().getDeclaredMethod("onTimer", long.class);
			timer.setAccessible(true);
			timer.invoke(control, now);
			assertTrue(requestFinished.await(5, TimeUnit.SECONDS));
			assertTrue(metricsFinished.await(5, TimeUnit.SECONDS));
			McpStreamTerminationReason expectedReason = expireRequestDeadline
					? McpStreamTerminationReason.DEADLINE_EXCEEDED
					: McpStreamTerminationReason.WRITE_FAILED;
			McpRequestOutcome expectedOutcome = expireRequestDeadline
					? McpRequestOutcome.DEADLINE_EXCEEDED : McpRequestOutcome.WRITE_FAILED;
			assertEquals(List.of(expectedOutcome), outcomes);
			List<McpMetricsEvent.RequestStreamClosed> closes = events.stream()
					.filter(McpMetricsEvent.RequestStreamClosed.class::isInstance)
					.map(McpMetricsEvent.RequestStreamClosed.class::cast).toList();
			assertEquals(1, closes.size());
			assertEquals(expectedReason, closes.get(0).getReason());
			assertEquals(List.of(expectedOutcome), events.stream()
					.filter(McpMetricsEvent.RequestFinished.class::isInstance)
					.map(McpMetricsEvent.RequestFinished.class::cast)
					.map(McpMetricsEvent.RequestFinished::getOutcome).toList());
			assertEquals(0, runtime.diagnosticsSnapshot().activeRequestStreams());
			if (!expireRequestDeadline) {
				assertTrue(handlerExited.await(5, TimeUnit.SECONDS));
				assertTrue(transportFailureRecorded.await(5, TimeUnit.SECONDS));
				assertEquals(Optional.of(StreamTerminationReason.RESPONSE_IDLE_TIMEOUT),
						token.get().getCancelationReason());
				assertEquals(List.of(MetricsCollector.TransportFailureReason.WRITE_TIMEOUT),
						events.stream().filter(McpMetricsEvent.TransportFailure.class::isInstance)
								.map(McpMetricsEvent.TransportFailure.class::cast)
								.map(McpMetricsEvent.TransportFailure::getReason).toList());
			} else {
				assertTrue(events.stream().noneMatch(
						McpMetricsEvent.TransportFailure.class::isInstance));
			}
		} finally {
			releaseHandler.countDown();
			if (source != null)
				source.close(StreamTerminationReason.SERVER_STOPPING, null);
			soklet.close();
		}
	}

	private static MicrohttpRequest request(InetSocketAddress address,
			McpProtocolVersion version) {
		String metadata = "\"progressToken\":\"progress\"";
		if (version == McpProtocolVersion.V2026_07_28)
			metadata += ",\"io.modelcontextprotocol/protocolVersion\":\""
					+ version.getWireValue() + "\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{}";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"stalled.writer\",\"arguments\":{},\"_meta\":{"
				+ metadata + "}}}";
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(
				new Header("Host", LOOPBACK + ':' + address.getPort()),
				new Header("Content-Type", "application/json"),
				new Header("Accept", "application/json, text/event-stream"),
				new Header("MCP-Protocol-Version", version.getWireValue()),
				new Header("Mcp-Method", "tools/call"),
				new Header("Mcp-Name", "stalled.writer")),
				body.getBytes(StandardCharsets.UTF_8), false,
				new InetSocketAddress(LOOPBACK, 12_345));
	}

	private static void awaitTerminalReservation(Object control) throws Exception {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		boolean owned;
		do {
			synchronized (field(control, "lock")) {
				owned = (Boolean) field(control, "applicationOwned");
				if (!owned)
					assertTrue((Boolean) field(control, "streamTerminalResponseOwned"));
			}
			if (owned)
				Thread.sleep(1);
		} while (owned && System.nanoTime() - until < 0L);
		assertFalse(owned, "The application must reserve its terminal result");
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}
}
