/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.mcp.protocol;

import com.soklet.LifecycleObserver;
import com.soklet.LifecyclePolicy;
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
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
public class McpStreamDisconnectPublicRuntimeTests {
	private static final Duration WAIT = Duration.ofSeconds(5);
	private static final String HOST = "127.0.0.1";
	private static final LifecyclePolicy LIFECYCLE = LifecyclePolicy.builder()
			.startupTimeout(WAIT).startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();

	@Test
	public void june2025ResetDuringProgressWritesIsAClientDisconnect() throws Exception {
		assertStreamingReset(McpProtocolVersion.V2025_06_18);
	}

	@Test
	public void november2025ResetDuringProgressWritesIsAClientDisconnect() throws Exception {
		assertStreamingReset(McpProtocolVersion.V2025_11_25);
	}

	@Test
	public void july2026ResetDuringProgressWritesIsAClientDisconnect() throws Exception {
		assertStreamingReset(McpProtocolVersion.V2026_07_28);
	}

	private static void assertStreamingReset(McpProtocolVersion version) throws Exception {
		CountDownLatch continueProducing = new CountDownLatch(1);
		CountDownLatch producerWriting = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		CountDownLatch streamClosed = new CountDownLatch(1);
		CountDownLatch requestFinished = new CountDownLatch(1);
		AtomicBoolean stopProducing = new AtomicBoolean();
		AtomicReference<McpRequestOutcome> outcome = new AtomicReference<>();
		List<McpMetricsEvent.RequestStreamClosed> closures = new CopyOnWriteArrayList<>();
		List<McpMetricsEvent.TransportFailure> failures = new CopyOnWriteArrayList<>();
		String message = "streaming-reset-" + "x".repeat(8 * 1_024);
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("stream", Set.of(version))
				.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
					try {
						McpProgressReporter reporter = invocationFeatures.getProgressReporter().orElseThrow();
						reporter.report(McpProgressUpdate.withProgress(1.0).build());
						assertTrue(continueProducing.await(5, TimeUnit.SECONDS));
						long deadline = System.nanoTime() + WAIT.toNanos();
						for (int progress = 2; !stopProducing.get()
								&& !invocationFeatures.getCancelationToken().isCanceled(); progress++) {
							assertTrue(System.nanoTime() - deadline < 0L,
									"The client reset did not stop the producer within its bound.");
							reporter.report(McpProgressUpdate.withProgress((double) progress).message(message).build());
							producerWriting.countDown();
						}
						return McpCompleteResult.fromToolText("late result");
					} finally {
						handlerExited.countDown();
					}
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("stream-disconnect", "test").build(), Set.of(version))
				.toolRegistrations(List.of(tool)).build();
		McpServer server = McpServer.withPort(0).host(HOST).allowedHosts(Set.of(HOST))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.requestRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed())
				.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
		MetricsCollector collector = new MetricsCollector() {
			@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				if (event instanceof McpMetricsEvent.RequestStreamClosed closed) {
					closures.add(closed);
					stopProducing.set(true);
					streamClosed.countDown();
				}
				if (event instanceof McpMetricsEvent.TransportFailure failure)
					failures.add(failure);
			}
		};
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didFinishMcpRequestHandling(McpRequestContext requestContext,
					McpRequestOutcome requestOutcome, @Nullable McpJsonRpcError jsonRpcError,
					Duration requestDuration, List<Throwable> throwables) {
				outcome.set(requestOutcome);
				requestFinished.countDown();
			}
		};
		Soklet.run(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector).lifecycleObserver(observer).lifecyclePolicy(LIFECYCLE).build(), () -> {
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			String modernMetadata = version == McpProtocolVersion.V2026_07_28
					? "\"io.modelcontextprotocol/protocolVersion\":\"" + version.getWireValue() + "\","
							+ "\"io.modelcontextprotocol/clientCapabilities\":{}," : "";
			String body = "{\"jsonrpc\":\"2.0\",\"id\":\"stream\",\"method\":\"tools/call\",\"params\":{"
					+ "\"name\":\"stream\",\"arguments\":{},\"_meta\":{" + modernMetadata
					+ "\"progressToken\":\"token\"}}}";
			List<McpChunkedHttpClient.RequestHeader> headers = version == McpProtocolVersion.V2026_07_28
					? List.of(new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", version.getWireValue()),
							new McpChunkedHttpClient.RequestHeader("Mcp-Method", "tools/call"),
							new McpChunkedHttpClient.RequestHeader("Mcp-Name", "stream"))
					: List.of(new McpChunkedHttpClient.RequestHeader("MCP-Protocol-Version", version.getWireValue()));
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(port, body,
					headers, 1_024)) {
				var head = client.readHead();
				assertEquals(200, head.status(), version + ": " + head.raw()
						+ (head.status() == 200 ? "" : client.readFixedBody(head)));
				assertEquals("text/event-stream", head.singleHeader("Content-Type"));
				assertTrue(client.readChunkText().contains("\"progress\":1"));
				continueProducing.countDown();
				assertTrue(producerWriting.await(5, TimeUnit.SECONDS));
				client.closeWithReset();
				assertTrue(streamClosed.await(5, TimeUnit.SECONDS), version.toString());
				assertTrue(requestFinished.await(5, TimeUnit.SECONDS), version.toString());
				assertTrue(handlerExited.await(5, TimeUnit.SECONDS), version.toString());
				assertEquals(1, closures.size(), closures.toString());
				assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED, closures.get(0).getReason(),
						version.toString());
				assertEquals(McpRequestOutcome.CLIENT_DISCONNECTED, outcome.get(), version.toString());
				assertTrue(failures.isEmpty(), failures.toString());
			} finally {
				stopProducing.set(true);
				continueProducing.countDown();
			}
		});
	}
}
