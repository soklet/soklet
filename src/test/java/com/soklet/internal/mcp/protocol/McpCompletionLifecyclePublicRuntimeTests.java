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
import com.soklet.LifecyclePolicy;
import com.soklet.McpArgumentCompletionResult;
import com.soklet.McpCompleteResult;
import com.soklet.McpCompletionHandler;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpMetricsEvent;
import com.soklet.McpOperationType;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpPromptArgumentDeclaration;
import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.McpPromptRegistration;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpTextContent;
import com.soklet.McpTextResourceContents;
import com.soklet.MetricsCollector;
import com.soklet.ResourceMethodResolver;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded real-listener lifecycle coverage for both Completion reference types.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpCompletionLifecyclePublicRuntimeTests {
	private static final String HOST = "127.0.0.1";
	private static final String PATH = "/mcp";
	private static final String REVISION = "2026-07-28";
	private static final String TEMPLATE = "catalog://items/{sku}";
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancelationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownTimeout(Duration.ofSeconds(2))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build();

	@Test
	public void bothReferencesUseProgressAndBoundedLifecycleClassification()
			throws Exception {
		List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();
		CountDownLatch finished = new CountDownLatch(3);
		AtomicInteger intercepted = new AtomicInteger();
		AtomicInteger completed = new AtomicInteger();
		AtomicInteger noToken = new AtomicInteger();
		McpCompletionHandler handler = (request, context, features) -> {
			assertEquals(McpOperationType.COMPLETION_COMPLETE,
					request.getOperationType());
			assertEquals("completion/complete", request.getJsonRpcMethod());
			assertTrue(features.find(com.soklet.McpTaskControl.class).isEmpty());
			if (features.find(McpProgressReporter.class).isPresent()) {
				features.require(McpProgressReporter.class).report(
						McpProgressUpdate.withProgress(1.0).build());
			} else {
				noToken.incrementAndGet();
			}
			completed.incrementAndGet();
			return McpArgumentCompletionResult.fromValues(List.of("suggestion"));
		};
		McpServer server = server(handler, null,
				(context, features, continuation) -> {
					assertEquals(McpOperationType.COMPLETION_COMPLETE,
							context.getOperationType());
					intercepted.incrementAndGet();
					return continuation.proceed();
				});
		Soklet soklet = managed(server, new MetricsCollector() {
			@Override
			public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				events.add(event);
				if (event instanceof McpMetricsEvent.RequestFinished)
					finished.countDown();
			}
		});
		try {
			soklet.start();
			int port = port(server);
			for (boolean prompt : List.of(true, false)) {
				String id = prompt ? "prompt-progress" : "resource-progress";
				String token = prompt ? "prompt-token" : "resource-token";
				try (McpChunkedHttpClient client = complete(port, id, prompt,
						"\"" + token + "\"")) {
					McpChunkedHttpClient.HttpResponseHead head = client.readHead();
					assertEquals(200, head.status(), head.raw());
					assertEquals("text/event-stream",
							head.singleHeader("Content-Type"));
					assertEquals(sse("{\"jsonrpc\":\"2.0\","
							+ "\"method\":\"notifications/progress\","
							+ "\"params\":{\"progressToken\":\"" + token
							+ "\",\"progress\":1}}"), client.readChunkText());
					String terminal = client.readChunkText();
					assertTrue(terminal.contains("\"resultType\":\"complete\""),
							terminal);
					assertTrue(terminal.contains("\"values\":[\"suggestion\"]"),
							terminal);
					assertNull(client.readChunk());
				}
			}
			try (McpChunkedHttpClient client = complete(port, "no-token", true,
						null)) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				assertEquals(200, head.status(), head.raw());
				assertEquals("application/json",
						head.singleHeader("Content-Type"));
				assertTrue(client.readFixedBody(head).contains(
						"\"values\":[\"suggestion\"]"));
			}
			assertTrue(finished.await(5, TimeUnit.SECONDS));
			assertEquals(3, intercepted.get());
			assertEquals(3, completed.get());
			assertEquals(1, noToken.get());
			assertEquals(3, events.stream()
					.filter(event -> event.equals(McpMetricsEvent.requestStarted(
							PATH, "completion/complete"))).count());
			assertEquals(2, events.stream()
					.filter(event -> event.equals(McpMetricsEvent.progressEmitted(
							PATH, "completion/complete"))).count());
			assertEquals(3, events.stream()
					.filter(McpMetricsEvent.RequestFinished.class::isInstance)
					.map(McpMetricsEvent.RequestFinished.class::cast)
					.filter(event -> "completion/complete".equals(
							event.getJsonRpcMethod())
							&& event.getOutcome()
							== com.soklet.McpRequestOutcome.COMPLETE).count());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void deadlineBlocksLateCompletionHandlerEntryForBothReferences()
			throws Exception {
		AtomicInteger interceptorEntries = new AtomicInteger();
		AtomicInteger handlerEntries = new AtomicInteger();
		CountDownLatch lateContinuations = new CountDownLatch(2);
		McpCompletionHandler handler = (request, context, features) -> {
			handlerEntries.incrementAndGet();
			return McpArgumentCompletionResult.fromValues(List.of("late"));
		};
		McpServer server = server(handler, Duration.ofMillis(250),
				(context, features, continuation) -> {
					interceptorEntries.incrementAndGet();
					long finish = System.nanoTime()
							+ TimeUnit.MILLISECONDS.toNanos(600);
					while (System.nanoTime() - finish < 0L) {
						try {
							Thread.sleep(10L);
						} catch (InterruptedException ignored) {
							// Deliberately noncooperative to test the late-entry fence.
						}
					}
					try {
						return continuation.proceed();
					} finally {
						lateContinuations.countDown();
					}
				});
		Soklet soklet = managed(server, new MetricsCollector() { });
		try {
			soklet.start();
			for (boolean prompt : List.of(true, false)) {
				try (McpChunkedHttpClient client = complete(port(server),
						prompt ? "prompt-deadline" : "resource-deadline",
						prompt, null)) {
					McpChunkedHttpClient.HttpResponseHead head = client.readHead();
					assertEquals(504, head.status(), head.raw());
					assertTrue(client.readFixedBody(head).contains(
							"\"message\":\"Internal error\""));
				}
			}
			assertTrue(lateContinuations.await(5, TimeUnit.SECONDS));
			assertEquals(2, interceptorEntries.get());
			assertEquals(0, handlerEntries.get(),
					"Expired Completion requests must not enter handlers.");
		} finally {
			lateContinuations.await(5, TimeUnit.SECONDS);
			soklet.close();
		}
	}

	@Test
	public void disconnectCancelsBothCompletionReferenceTypes() throws Exception {
		AtomicReference<CountDownLatch> released =
				new AtomicReference<>(new CountDownLatch(1));
		CountDownLatch canceled = new CountDownLatch(2);
		CountDownLatch exited = new CountDownLatch(2);
		AtomicInteger correctlyCanceled = new AtomicInteger();
		McpCompletionHandler handler = (request, context, features) -> {
			CancelationToken token = features.require(CancelationToken.class);
			token.onCancel(() -> {
				if (token.isCanceled() && token.getCancelationReason()
						.orElse(null) == StreamTerminationReason.CLIENT_DISCONNECTED)
					correctlyCanceled.incrementAndGet();
				canceled.countDown();
			});
			features.require(McpProgressReporter.class).report(
					McpProgressUpdate.withProgress(1.0).build());
			try {
				released.get().await(10, TimeUnit.SECONDS);
				return McpArgumentCompletionResult.fromValues(List.of("never"));
			} finally {
				exited.countDown();
			}
		};
		McpServer server = server(handler, null, null);
		Soklet soklet = managed(server, new MetricsCollector() { });
		McpChunkedHttpClient client = null;
		try {
			soklet.start();
			for (boolean prompt : List.of(true, false)) {
				client = complete(port(server),
						prompt ? "prompt-disconnect" : "resource-disconnect",
						prompt, "\"disconnect-token\"");
				assertEquals(200, client.readHead().status());
				assertTrue(client.readChunkText().contains(
						"\"method\":\"notifications/progress\""));
				client.closeWithReset();
				client = null;
			}
			assertTrue(canceled.await(5, TimeUnit.SECONDS));
			assertTrue(exited.await(5, TimeUnit.SECONDS));
			assertEquals(2, correctlyCanceled.get());
			awaitHandlerCleanup(server);
			assertEquals(0, server.getDiagnostics()
					.getActiveHandlerExecutions());
			assertEquals(0, server.getDiagnostics()
					.getActiveRequestStreams());
		} finally {
			released.get().countDown();
			if (client != null)
				client.close();
			soklet.close();
		}
	}

	@Test
	public void malformedProgressTokenCannotEnterEitherCompletionHandler()
			throws Exception {
		AtomicInteger handlerEntries = new AtomicInteger();
		McpServer server = server((request, context, features) -> {
			handlerEntries.incrementAndGet();
			return McpArgumentCompletionResult.fromValues(List.of("never"));
		}, null, null);
		Soklet soklet = managed(server, new MetricsCollector() { });
		try {
			soklet.start();
			for (boolean prompt : List.of(true, false)) {
				try (McpChunkedHttpClient client = complete(port(server),
						prompt ? "bad-prompt-token" : "bad-resource-token",
						prompt, "true")) {
					McpChunkedHttpClient.HttpResponseHead head = client.readHead();
					assertEquals(400, head.status(), head.raw());
					assertTrue(client.readFixedBody(head).contains(
							"\"code\":-32602"));
				}
			}
			assertEquals(0, handlerEntries.get());
		} finally {
			soklet.close();
		}
	}

	@Test
	public void shutdownCancelsBothActiveCompletionReferenceTypes()
			throws Exception {
		CountDownLatch canceled = new CountDownLatch(2);
		CountDownLatch exited = new CountDownLatch(2);
		CountDownLatch emergencyRelease = new CountDownLatch(1);
		AtomicInteger shutdownReasons = new AtomicInteger();
		McpCompletionHandler handler = (request, context, features) -> {
			CancelationToken token = features.require(CancelationToken.class);
			token.onCancel(() -> {
				if (token.isCanceled() && token.getCancelationReason()
						.orElse(null) == StreamTerminationReason.SERVER_STOPPING)
					shutdownReasons.incrementAndGet();
				canceled.countDown();
				if (canceled.getCount() == 0L)
					emergencyRelease.countDown();
			});
			features.require(McpProgressReporter.class).report(
					McpProgressUpdate.withProgress(1.0).build());
			try {
				emergencyRelease.await(10, TimeUnit.SECONDS);
				return McpArgumentCompletionResult.fromValues(List.of("never"));
			} finally {
				exited.countDown();
			}
		};
		McpServer server = server(handler, null, null);
		Soklet soklet = managed(server, new MetricsCollector() { });
		ExecutorService stopExecutor = Executors.newSingleThreadExecutor();
		McpChunkedHttpClient promptClient = null;
		McpChunkedHttpClient resourceClient = null;
		Future<?> stop = null;
		try {
			soklet.start();
			promptClient = complete(port(server), "prompt-stop", true,
					"\"prompt-stop-token\"");
			assertEquals(200, promptClient.readHead().status());
			assertTrue(promptClient.readChunkText().contains(
					"\"progressToken\":\"prompt-stop-token\""));
			resourceClient = complete(port(server), "resource-stop", false,
					"\"resource-stop-token\"");
			assertEquals(200, resourceClient.readHead().status());
			assertTrue(resourceClient.readChunkText().contains(
					"\"progressToken\":\"resource-stop-token\""));
			assertEquals(2, server.getDiagnostics().getActiveHandlerExecutions(),
					"Both Completion handlers must be active before shutdown.");

			stop = stopExecutor.submit(soklet::close);
			assertTrue(canceled.await(5, TimeUnit.SECONDS),
					"Shutdown did not cancel both active Completion requests.");
			assertEquals(2, shutdownReasons.get());
			assertTrue(exited.await(5, TimeUnit.SECONDS),
					"Canceled Completion handlers did not release their slots.");
			stop.get(5, TimeUnit.SECONDS);
			assertEquals(0, server.getDiagnostics().getActiveHandlerExecutions());
			assertEquals(0, server.getDiagnostics().getActiveRequestStreams());
		} finally {
			emergencyRelease.countDown();
			if (promptClient != null)
				promptClient.close();
			if (resourceClient != null)
				resourceClient.close();
			try {
				if (stop == null)
					soklet.close();
				else
					stop.get(5, TimeUnit.SECONDS);
			} finally {
				stopExecutor.shutdownNow();
				assertTrue(stopExecutor.awaitTermination(5, TimeUnit.SECONDS));
			}
		}
	}

	private static McpServer server(McpCompletionHandler completionHandler,
			Duration requestTimeout,
			com.soklet.McpHandlerInterceptor interceptor) {
		McpPromptRegistration prompt = McpPromptRegistration.withName("suggest")
				.handler((request, arguments, features) ->
						McpCompleteResult.fromPromptOutput(McpPromptOutput.builder()
								.addMessage(McpPromptMessage.fromUserContent(
										McpTextContent.fromText("unused"))).build()))
				.addArgument(McpPromptArgumentDeclaration.withName("term").build())
				.completionHandler(completionHandler).build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriTemplateAndName(TEMPLATE, "Items")
				.handler((request, target, features) ->
						McpCompleteResult.fromResourceOutput(
								McpResourceOutput.withContent(
										McpTextResourceContents.withUriAndText(
												target.getUri(), "unused").build())
										.build()))
				.completionHandler(completionHandler).build();
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion("completion-lifecycle", "1.0")
						.build())
				.serverInfoIncluded(false)
				.addPrompt(prompt).addResource(resource).build();
		McpServer.Builder builder = McpServer.withPort(0).host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST));
		if (requestTimeout != null)
			builder.requestTimeout(requestTimeout);
		if (interceptor != null)
			builder.handlerInterceptor(interceptor);
		return builder.build();
	}

	private static Soklet managed(McpServer server, MetricsCollector collector) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector)
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY).build());
	}

	private static int port(McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	private static McpChunkedHttpClient complete(int port, String id,
			boolean prompt, String progressTokenJson) throws Exception {
		String reference = prompt ? "\"type\":\"ref/prompt\",\"name\":\"suggest\""
				: "\"type\":\"ref/resource\",\"uri\":\"" + TEMPLATE + "\"";
		String argument = prompt ? "term" : "sku";
		String token = progressTokenJson == null ? ""
				: ",\"progressToken\":" + progressTokenJson;
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"completion/complete\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + REVISION
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}" + token
				+ "},\"ref\":{" + reference + "},\"argument\":{\"name\":\""
				+ argument + "\",\"value\":\"s\"}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", REVISION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "completion/complete")));
	}

	private static String sse(String message) {
		return "data: " + message + "\n\n";
	}

	private static void awaitHandlerCleanup(McpServer server)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while ((server.getDiagnostics().getActiveHandlerExecutions() != 0
				|| server.getDiagnostics().getActiveRequestStreams() != 0)
				&& System.nanoTime() - deadline < 0L)
			Thread.sleep(5L);
	}
}
