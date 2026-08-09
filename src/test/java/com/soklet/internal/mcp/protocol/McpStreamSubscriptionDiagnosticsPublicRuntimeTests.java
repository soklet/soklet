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

import com.soklet.CorsAuthorizer;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpHandlerResolver;
import com.soklet.McpImplementation;
import com.soklet.McpJsonObject;
import com.soklet.McpProgressReporter;
import com.soklet.McpProgressUpdate;
import com.soklet.McpRateLimitDecision;
import com.soklet.McpRequestAdmissionPolicy;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpServerDiagnostics;
import com.soklet.McpServerStatus;
import com.soklet.McpSubscriptionConfig;
import com.soklet.McpSubscriptionEvent;
import com.soklet.McpSubscriptionEventListener;
import com.soklet.McpSubscriptionEventPublisher;
import com.soklet.McpSubscriptionEventSubscription;
import com.soklet.McpSubscriptionNotificationType;
import com.soklet.McpTextResourceContents;
import com.soklet.McpToolHandler;
import com.soklet.McpToolRegistration;
import com.soklet.internal.microhttp.EventLoop;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Black-box listener coverage for public MCP stream and subscription diagnostics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(40)
public class McpStreamSubscriptionDiagnosticsPublicRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String TOOL_PATH = "/mcp";
	private static final String SUBSCRIPTION_PATH = "/mcp/subscriptions";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String TOOL_NAME = "diagnostics.stream";
	private static final URI RESOURCE_URI =
			URI.create("test://diagnostics/subscription");

	@Test
	public void ordinaryAndSubscriptionStreamsAggregateAcrossEndpointsAndCleanOnDisconnect()
			throws Exception {
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		McpEndpoint toolEndpoint = toolEndpoint((request, call, features) -> {
			features.require(McpProgressReporter.class).report(
					McpProgressUpdate.withProgress(1.0d).build());
			try {
				releaseHandler.await();
			} catch (InterruptedException exception) {
				handlerInterrupted.countDown();
				throw exception;
			}
			return McpCompleteResult.fromToolText("released");
		});
		McpServer server = server(List.of(toolEndpoint, subscriptionEndpoint()),
				Duration.ofSeconds(1));
		ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
		AtomicBoolean readSnapshots = new AtomicBoolean(true);
		AtomicInteger snapshotReads = new AtomicInteger();
		McpChunkedHttpClient ordinary = null;
		McpChunkedHttpClient subscription = null;
		Future<?> reader = null;

		try {
			server.start();
			reader = readerExecutor.submit(() -> {
				while (readSnapshots.get()) {
					assertStreamInvariant(server.getDiagnostics());
					snapshotReads.incrementAndGet();
				}
			});
			awaitCondition(() -> snapshotReads.get() > 0);

			int port = boundPort(server);
			ordinary = callTool(port, TOOL_PATH, "ordinary");
			assertSseHead(ordinary.readHead());
			Assertions.assertTrue(ordinary.readChunkText().contains(
					"\"progressToken\":\"ordinary-progress\""));
			McpServerDiagnostics ordinaryOpen = awaitDiagnostics(server,
					diagnostics -> streamPair(diagnostics, 1, 0));

			subscription = listen(port, SUBSCRIPTION_PATH, "subscription");
			assertSseHead(subscription.readHead());
			String acknowledgment = subscription.readChunkText();
			Assertions.assertTrue(acknowledgment.contains(
					"\"method\":\"notifications/subscriptions/acknowledged\""),
					acknowledgment);
			Assertions.assertTrue(acknowledgment.contains(
					"\"io.modelcontextprotocol/subscriptionId\":\"subscription\""),
					acknowledgment);
			McpServerDiagnostics aggregate = awaitDiagnostics(server,
					diagnostics -> streamPair(diagnostics, 2, 1));

			subscription.closeWithReset();
			subscription = null;
			awaitDiagnostics(server, diagnostics -> streamPair(diagnostics, 1, 0));
			ordinary.closeWithReset();
			ordinary = null;
			Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS));
			awaitDiagnostics(server, diagnostics -> streamPair(diagnostics, 0, 0));

			assertStreamPair(ordinaryOpen, 1, 0);
			assertStreamPair(aggregate, 2, 1);
			Assertions.assertEquals(McpServerStatus.STARTED, aggregate.getStatus());
			readSnapshots.set(false);
			requireFuture(reader).get(5, TimeUnit.SECONDS);
			Assertions.assertTrue(snapshotReads.get() > 0);
		} finally {
			readSnapshots.set(false);
			releaseHandler.countDown();
			if (ordinary != null)
				ordinary.close();
			if (subscription != null)
				subscription.close();
			server.stop();
			readerExecutor.shutdownNow();
			Assertions.assertTrue(readerExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void residualHandlerStopPublishesZeroStreamsBeforeLateHandlerExit()
			throws Exception {
		CountDownLatch releaseHandler = new CountDownLatch(1);
		CountDownLatch handlerInterrupted = new CountDownLatch(1);
		CountDownLatch handlerExited = new CountDownLatch(1);
		McpEndpoint endpoint = toolEndpoint((request, call, features) -> {
			features.require(McpProgressReporter.class).report(
					McpProgressUpdate.withProgress(1.0d).build());
			try {
				awaitIgnoringInterrupts(releaseHandler, handlerInterrupted);
				return McpCompleteResult.fromToolText("released");
			} finally {
				handlerExited.countDown();
			}
		});
		McpServer server = server(List.of(endpoint), Duration.ofMillis(150));
		McpChunkedHttpClient client = null;

		try {
			server.start();
			client = callTool(boundPort(server), TOOL_PATH, "residual");
			assertSseHead(client.readHead());
			client.readChunkText();
			McpServerDiagnostics open = awaitDiagnostics(server,
					diagnostics -> streamPair(diagnostics, 1, 0));

			server.stop();
			Assertions.assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS));
			McpServerDiagnostics residual = server.getDiagnostics();
			Assertions.assertEquals(McpServerStatus.STOPPED_WITH_RESIDUAL_HANDLERS,
					residual.getStatus());
			Assertions.assertEquals(Integer.valueOf(1),
					residual.getActiveHandlerExecutions());
			assertStreamPair(residual, 0, 0);
			Assertions.assertTrue(client.awaitTransportClosure());

			releaseHandler.countDown();
			Assertions.assertTrue(handlerExited.await(5, TimeUnit.SECONDS));
			McpServerDiagnostics stopped = awaitDiagnostics(server,
					diagnostics -> diagnostics.getStatus() == McpServerStatus.STOPPED
							&& streamPair(diagnostics, 0, 0));
			Assertions.assertEquals(Integer.valueOf(0),
					stopped.getActiveHandlerExecutions());
			assertStreamPair(open, 1, 0);
			assertStreamPair(residual, 0, 0);
		} finally {
			releaseHandler.countDown();
			if (client != null)
				client.close();
			server.stop();
		}
	}

	@Test
	public void unexpectedFailureRetainsOneSubscriptionUntilCleanupWithConcurrentInvariantReads()
			throws Exception {
		McpServer server = server(List.of(subscriptionEndpoint()),
				Duration.ofSeconds(1));
		ExecutorService terminationExecutor = Executors.newSingleThreadExecutor();
		ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
		AtomicBoolean readSnapshots = new AtomicBoolean(true);
		AtomicInteger snapshotReads = new AtomicInteger();
		McpChunkedHttpClient subscription = null;
		Future<?> termination = null;
		Future<?> reader = null;

		try {
			server.start();
			subscription = listen(boundPort(server), SUBSCRIPTION_PATH, "failure");
			assertSseHead(subscription.readHead());
			subscription.readChunkText();
			awaitDiagnostics(server, diagnostics -> streamPair(diagnostics, 1, 1));

			Object runtime = runtime(server);
			Object subscriptionLock = field(runtime, "subscriptionLock");
			EventLoop eventLoop = (EventLoop) field(runtime, "eventLoop");
			reader = readerExecutor.submit(() -> {
				while (readSnapshots.get()) {
					assertStreamInvariant(server.getDiagnostics());
					snapshotReads.incrementAndGet();
				}
			});
			awaitCondition(() -> snapshotReads.get() > 0);

			McpServerDiagnostics failedBeforeCleanup;
			synchronized (subscriptionLock) {
				termination = terminationExecutor.submit(() ->
						handleUnexpectedTermination(runtime, eventLoop));
				failedBeforeCleanup = awaitDiagnostics(server,
						diagnostics -> diagnostics.getStatus()
								== McpServerStatus.STOPPED_WITH_RESIDUAL_HANDLERS
							&& streamPair(diagnostics, 1, 1));
			}
			requireFuture(termination).get(5, TimeUnit.SECONDS);
			McpServerDiagnostics failedAfterCleanup = awaitDiagnostics(server,
					diagnostics -> diagnostics.getStatus() == McpServerStatus.STOPPED
							&& streamPair(diagnostics, 0, 0));
			// Transport termination is the callback precondition; this seam owns
			// diagnostics cleanup only, and the still-live test EventLoop owns writes.
			assertStreamPair(failedBeforeCleanup, 1, 1);
			assertStreamPair(failedAfterCleanup, 0, 0);

			readSnapshots.set(false);
			requireFuture(reader).get(5, TimeUnit.SECONDS);
			Assertions.assertTrue(snapshotReads.get() > 0);
		} finally {
			readSnapshots.set(false);
			if (subscription != null)
				subscription.close();
			server.stop();
			terminationExecutor.shutdownNow();
			readerExecutor.shutdownNow();
			Assertions.assertTrue(terminationExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
			Assertions.assertTrue(readerExecutor.awaitTermination(
					5, TimeUnit.SECONDS));
		}
	}

	@NonNull
	private static McpEndpoint toolEndpoint(
			@NonNull McpToolHandler<McpJsonObject> handler) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(TOOL_NAME)
				.jsonArguments()
				.handler(handler)
				.build();
		return McpEndpoint.withPath(TOOL_PATH)
				.serverInformation(serverInformation())
				.tool(tool)
				.build();
	}

	@NonNull
	private static McpEndpoint subscriptionEndpoint() {
		McpSubscriptionEventPublisher publisher = new McpSubscriptionEventPublisher() {
			@Override
			@NonNull
			public McpSubscriptionEventSubscription subscribe(
					@NonNull McpSubscriptionEventListener listener) {
				return () -> {};
			}

			@Override
			public void publish(@NonNull McpSubscriptionEvent event) {
			}
		};
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher)
				.notificationType(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)
				.build();
		McpResourceRegistration resource = McpResourceRegistration
				.withUriAndName(RESOURCE_URI, "diagnostics-subscription")
				.handler((request, read, features) ->
						McpCompleteResult.fromResourceOutput(
								McpResourceOutput.builder()
										.content(McpTextResourceContents
												.withUriAndText(read.getUri(), "test")
												.build())
										.build()))
				.build();
		return McpEndpoint.withPath(SUBSCRIPTION_PATH)
				.serverInformation(serverInformation())
				.resource(resource)
				.subscriptions(subscriptions)
				.build();
	}

	@NonNull
	private static McpImplementation serverInformation() {
		return McpImplementation.withNameAndVersion(
				"stream-diagnostics-test", "3.6.0-SNAPSHOT").build();
	}

	@NonNull
	private static McpServer server(@NonNull List<@NonNull McpEndpoint> endpoints,
			@NonNull Duration shutdownTimeout) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.handlerResolver(McpHandlerResolver.fromEndpoints(endpoints))
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance())
				.requestRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.toolRateLimiter(context -> McpRateLimitDecision.fromAllowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.requestHandlerConcurrency(2)
				.requestHandlerQueueCapacity(2)
				.requestTimeout(Duration.ofSeconds(10))
				.shutdownTimeout(shutdownTimeout)
				.build();
	}

	private static int boundPort(@NonNull McpServer server) {
		return server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
	}

	@NonNull
	private static McpChunkedHttpClient callTool(int port, @NonNull String path,
			@NonNull String id) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{},"
				+ "\"progressToken\":\"ordinary-progress\"},"
				+ "\"name\":\"" + TOOL_NAME + "\",\"arguments\":{}}}";
		return post(port, path, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", "tools/call"),
				new McpChunkedHttpClient.RequestHeader("Mcp-Name", TOOL_NAME)));
	}

	@NonNull
	private static McpChunkedHttpClient listen(int port, @NonNull String path,
			@NonNull String id) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"subscriptions/listen\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"notifications\":{\"resourcesListChanged\":true}}}";
		return post(port, path, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader(
						"Mcp-Method", "subscriptions/listen")));
	}

	@NonNull
	private static McpChunkedHttpClient post(int port, @NonNull String path,
			@NonNull String body,
			@NonNull List<McpChunkedHttpClient.RequestHeader> headers)
			throws Exception {
		Constructor<McpChunkedHttpClient> constructor =
				McpChunkedHttpClient.class.getDeclaredConstructor(int.class, int.class);
		constructor.setAccessible(true);
		McpChunkedHttpClient client = constructor.newInstance(port, 0);
		try {
			client.writeRequest("POST", path, LOOPBACK + ':' + port, body, headers);
			return client;
		} catch (Exception | Error throwable) {
			client.close();
			throw throwable;
		}
	}

	@NonNull
	private static McpServerDiagnostics awaitDiagnostics(@NonNull McpServer server,
			@NonNull Predicate<@NonNull McpServerDiagnostics> predicate)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpServerDiagnostics latest = server.getDiagnostics();
		while (System.nanoTime() - deadline < 0L) {
			latest = server.getDiagnostics();
			if (predicate.test(latest))
				return latest;
			Thread.sleep(10L);
		}
		Assertions.fail("Timed out waiting for MCP diagnostics; latest=" + latest);
		throw new AssertionError();
	}

	private static boolean streamPair(@NonNull McpServerDiagnostics diagnostics,
			int streams, int subscriptions) {
		return diagnostics.getActiveRequestStreams() == streams
				&& diagnostics.getActiveSubscriptions() == subscriptions;
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(10L);
		}
		Assertions.fail("Timed out waiting for concurrent diagnostics reads.");
	}

	private static void assertStreamPair(
			@NonNull McpServerDiagnostics diagnostics,
			int streams, int subscriptions) {
		Assertions.assertEquals(Integer.valueOf(streams),
				diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(Integer.valueOf(subscriptions),
				diagnostics.getActiveSubscriptions());
	}

	private static void assertStreamInvariant(
			@NonNull McpServerDiagnostics diagnostics) {
		int streams = diagnostics.getActiveRequestStreams();
		int subscriptions = diagnostics.getActiveSubscriptions();
		Assertions.assertTrue(streams >= 0);
		Assertions.assertTrue(subscriptions >= 0 && subscriptions <= streams);
		if (diagnostics.getStatus() == McpServerStatus.STOPPED) {
			Assertions.assertEquals(0, streams);
			Assertions.assertEquals(0, subscriptions);
		}
	}

	private static void assertSseHead(
			McpChunkedHttpClient.@NonNull HttpResponseHead head) {
		Assertions.assertEquals(200, head.status(), head.raw());
		Assertions.assertEquals("text/event-stream",
				head.singleHeader("Content-Type"));
		Assertions.assertEquals("chunked",
				head.singleHeader("Transfer-Encoding"));
	}

	private static void awaitIgnoringInterrupts(@NonNull CountDownLatch release,
			@NonNull CountDownLatch interrupted) {
		boolean released = false;
		while (!released) {
			try {
				released = release.await(25, TimeUnit.MILLISECONDS);
			} catch (InterruptedException exception) {
				interrupted.countDown();
			}
		}
	}

	@NonNull
	private static Object runtime(@NonNull McpServer server) throws Exception {
		Object bridge = field(server, "runtimeBridge");
		return field(bridge, "runtime");
	}

	@NonNull
	private static Object field(@NonNull Object target, @NonNull String name)
			throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void handleUnexpectedTermination(@NonNull Object runtime,
			@NonNull EventLoop eventLoop) {
		try {
			Method method = runtime.getClass().getDeclaredMethod(
					"handleUnexpectedTermination", EventLoop.class, Throwable.class);
			method.setAccessible(true);
			method.invoke(runtime, eventLoop,
					new IllegalStateException("expected diagnostics test failure"));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Unable to invoke unexpected termination.",
					exception.getCause() == null ? exception : exception.getCause());
		}
	}

	@SuppressWarnings("unchecked")
	@NonNull
	private static <T> Future<T> requireFuture(Future<?> future) {
		Assertions.assertNotNull(future);
		return (Future<T>) future;
	}
}
