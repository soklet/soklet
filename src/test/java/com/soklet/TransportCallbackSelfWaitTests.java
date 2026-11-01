package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class TransportCallbackSelfWaitTests {
	public static class HttpRoutes {
		@com.soklet.annotation.GET("/ok") public String get() { return "ok"; }
	}
	public static class SseRoutes {
		@com.soklet.annotation.SseEventSource("/events")
		public SseHandshakeResult events() { return SseHandshakeResult.Accepted.builder().build(); }
	}

	@Test void httpObserverAwaitDoesNotJoinItsOwnAcceptLoop() throws Exception { selfWait(ServerType.HTTP, false, false); }
	@Test void httpCollectorAwaitDoesNotJoinItsOwnAcceptLoop() throws Exception { selfWait(ServerType.HTTP, true, false); }
	@Test void sseObserverAwaitDoesNotJoinItsOwnAcceptLoop() throws Exception { selfWait(ServerType.SSE, false, false); }
	@Test void sseCollectorAwaitDoesNotJoinItsOwnAcceptLoop() throws Exception { selfWait(ServerType.SSE, true, false); }
	@Test void httpObserverCloseDoesNotJoinItsOwnAcceptLoop() throws Exception { selfWait(ServerType.HTTP, false, true); }
	@Test void httpCollectorCloseDoesNotJoinItsOwnAcceptLoop() throws Exception { selfWait(ServerType.HTTP, true, true); }
	@Test void sseObserverCloseDoesNotJoinItsOwnAcceptLoop() throws Exception { selfWait(ServerType.SSE, false, true); }
	@Test void sseCollectorCloseDoesNotJoinItsOwnAcceptLoop() throws Exception { selfWait(ServerType.SSE, true, true); }
	@Test void mcpAdmissionAwaitDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("admission", false); }
	@Test void mcpAdmissionCloseDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("admission", true); }
	@Test void mcpLimiterAwaitDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("limiter", false); }
	@Test void mcpLimiterCloseDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("limiter", true); }
	@Test void mcpCorsAwaitDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("cors", false); }
	@Test void mcpCorsCloseDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("cors", true); }
	@Test void mcpCollectorAwaitDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("metrics", false); }
	@Test void mcpCollectorCloseDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("metrics", true); }
	@Test void mcpCatalogPolicyAwaitDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("catalog", false); }
	@Test void mcpCatalogPolicyCloseDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("catalog", true); }
	@Test void mcpPolicyCancelationAwaitDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("policy-cancelation", false); }
	@Test void mcpHandlerCancelationAwaitDoesNotJoinItsOwnWork() throws Exception { mcpSelfWait("handler-cancelation", false); }

	@Test
	void callbackGuardIsOwnerSpecificAndRestoresCallerContextAfterFailure() throws Exception {
		AtomicReference<Soklet> owner = new AtomicReference<>();
		AtomicReference<Throwable> observed = new AtomicReference<>();
		IllegalArgumentException expected = new IllegalArgumentException("observer failure");
		Soklet other = Soklet.fromConfig(SokletConfig.withHttpServer(HttpServer.fromPort(0))
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HttpRoutes.class)))
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofMillis(200))
						.gracefulShutdownTimeout(Duration.ofMillis(200)).forcedShutdownTimeout(Duration.ofMillis(200)).build()).build());
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HttpRoutes.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) { }
					@Override public void willAcceptConnection(ServerType type, InetSocketAddress address) {
						try {
							assertThrows(IllegalStateException.class, () -> owner.get().awaitCoreShutdown());
							assertDoesNotThrow(other::close, "A callback for owner A must be able to close independent owner B");
							observed.set(expected);
							throw expected;
						} catch (Throwable failure) {
							observed.set(failure);
							throw failure;
						}
					}
				}).lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofMillis(200))
						.gracefulShutdownTimeout(Duration.ofMillis(200)).forcedShutdownTimeout(Duration.ofMillis(200)).build()).build());
		owner.set(soklet);
		try {
			soklet.start();
			var callback = DefaultHttpServer.class.getDeclaredMethod("notifyWillAcceptConnection", InetSocketAddress.class);
			callback.setAccessible(true);
			callback.invoke(server, new InetSocketAddress("127.0.0.1", 1));
			assertSame(expected, observed.get());
			assertFalse(LifecycleExecutionContext.isMarked(soklet.getDirectLifecycle().executionOwnerToken()),
					"A throwing callback must not leak the owner marker into its caller");
		} finally {
			soklet.close();
			other.close();
		}
	}

	private static void mcpSelfWait(String hook, boolean close) throws Exception {
		CompletableFuture<Void> ready = new CompletableFuture<>();
		CompletableFuture<Void> release = new CompletableFuture<>();
		AtomicReference<Thread> policyThread = new AtomicReference<>();
		AtomicReference<CancelationToken> policyToken = new AtomicReference<>();
		AtomicReference<Throwable> policyExit = new AtomicReference<>();
		CompletableFuture<ShutdownResult> shutdown = null;
		String waitStage = "startup";
		AtomicReference<Soklet> owner = new AtomicReference<>();
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		AtomicBoolean attempted = new AtomicBoolean();
		CompletableFuture<Throwable> outcome = new CompletableFuture<>();
		Runnable callback = () -> {
			if (!attempted.compareAndSet(false, true)) return;
			callbackThread.set(Thread.currentThread());
			try {
				if (close) owner.get().close(); else owner.get().awaitCoreShutdown();
				outcome.complete(null);
			} catch (Throwable failure) { outcome.complete(failure); }
		};
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("self-wait", "1").build(),
				Set.of(McpProtocolVersion.V2026_07_28))
				.toolRegistrations(List.of(McpToolRegistration.withName("probe", Set.of(McpProtocolVersion.V2026_07_28))
						.jsonObjectArguments().handler((request, arguments, features) -> {
							if (hook.equals("handler-cancelation")) {
								features.getCancelationToken().onCancel(callback);
								ready.complete(null);
								release.get(4, TimeUnit.SECONDS);
							}
							return McpCompleteResult.fromToolText("ok");
						}).build())).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators((request, tool, features) -> {
					if (hook.equals("catalog")) callback.run();
					if (hook.equals("policy-cancelation")) {
						policyThread.set(Thread.currentThread());
						policyToken.set(features.getCancelationToken());
						features.getCancelationToken().onCancel(callback);
						ready.complete(null);
						try {
							release.get(4, TimeUnit.SECONDS);
						} catch (Exception failure) {
							policyExit.set(failure);
							throw failure;
						}
					}
					return true;
				}, (request, prompt, features) -> true))
				.admissionController(context -> {
					if (hook.equals("admission")) callback.run();
					return McpAdmissionDecision.accepted();
				}).requestRateLimiter(context -> {
					if (hook.equals("limiter")) callback.run();
					return McpRateLimitDecision.allowed();
				}).toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.fromWhitelistAuthorizer(origin -> {
					if (hook.equals("cors")) callback.run();
					return true;
				})).build();
		MetricsCollector collector = new MetricsCollector() {
			@Override public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
				if (hook.equals("metrics") && event instanceof McpMetricsEvent.ConnectionAccepted) callback.run();
			}
		};
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(collector).lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofMillis(200))
						.gracefulShutdownTimeout(Duration.ofMillis(200)).forcedShutdownTimeout(Duration.ofMillis(200)).build()).build());
		owner.set(soklet);
		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			try (Socket socket = new Socket()) {
				socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1_000);
				if (!hook.equals("metrics")) {
					String method = hook.equals("handler-cancelation") ? "tools/call"
							: hook.equals("catalog") || hook.equals("policy-cancelation") ? "tools/list" : "server/discover";
					String parameters = hook.equals("handler-cancelation") ? "\"name\":\"probe\",\"arguments\":{}," : "";
					String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{" + parameters + "\"_meta\":{"
							+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
							+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
					socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + port
							+ "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream"
							+ "\r\nMCP-Protocol-Version: 2026-07-28\r\nMcp-Method: " + method
							+ (hook.equals("handler-cancelation") ? "\r\nMcp-Name: probe" : "")
							+ "\r\nOrigin: https://example.com\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
							.getBytes(StandardCharsets.US_ASCII));
				}
				if (hook.endsWith("cancelation")) {
					waitStage = "cancelation registration";
					ready.get(2, TimeUnit.SECONDS);
					shutdown = soklet.shutdown().toCompletableFuture();
				}
				waitStage = "callback outcome";
				Throwable failure = outcome.get(2, TimeUnit.SECONDS);
				assertInstanceOf(IllegalStateException.class, failure);
				assertEquals("Lifecycle wait cannot run from tracked lifecycle execution", failure.getMessage());
			}
		} catch (java.util.concurrent.TimeoutException failure) {
			CancelationToken token = policyToken.get();
			String shutdownState = shutdown == null ? "not requested" : shutdown.isDone()
					? shutdown.handle((result, error) -> error == null ? String.valueOf(result) : String.valueOf(error)).getNow("pending")
					: "pending";
			throw new AssertionError("MCP self-wait hook=" + hook + ", waitingFor=" + waitStage
					+ ", registrationReady=" + ready.isDone() + ", callbackAttempted=" + attempted.get()
					+ ", callbackThread=" + threadDiagnostic(callbackThread.get())
					+ ", policyThread=" + threadDiagnostic(policyThread.get())
					+ ", policyExit=" + policyExit.get()
					+ ", policyCancelationReason=" + (token == null ? "unavailable" : token.getCancelationReason())
					+ ", shutdown=" + shutdownState, failure);
		} finally {
			release.complete(null);
			if (!outcome.isDone()) {
				Thread blocked = callbackThread.get();
				if (blocked != null) blocked.interrupt();
			}
			soklet.shutdown().toCompletableFuture().get(4, TimeUnit.SECONDS);
		}
	}

	private static String threadDiagnostic(Thread thread) {
		return thread == null ? "not entered" : thread.getState() + " " + java.util.Arrays.toString(thread.getStackTrace());
	}

	private static void selfWait(ServerType type, boolean metrics, boolean close) throws Exception {
		AtomicReference<Soklet> owner = new AtomicReference<>();
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		AtomicBoolean attempted = new AtomicBoolean();
		CompletableFuture<Throwable> outcome = new CompletableFuture<>();
		Runnable callback = () -> {
			if (!attempted.compareAndSet(false, true)) return;
			callbackThread.set(Thread.currentThread());
			try {
				if (close) owner.get().close();
				else owner.get().awaitCoreShutdown();
				outcome.complete(null);
			} catch (Throwable failure) {
				outcome.complete(failure);
			}
		};
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { }
			@Override public void willAcceptConnection(ServerType serverType, InetSocketAddress address) {
				if (!metrics) callback.run();
			}
		};
		MetricsCollector collector = new MetricsCollector() {
			@Override public void willAcceptConnection(ServerType serverType, InetSocketAddress address) {
				if (metrics) callback.run();
			}
		};
		DefaultHttpServer http = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").build();
		DefaultSseServer sse = (DefaultSseServer) SseServer.withPort(0).host("127.0.0.1").build();
		SokletConfig.Builder builder = type == ServerType.HTTP ? SokletConfig.withHttpServer(http) : SokletConfig.withSseServer(sse);
		Soklet soklet = Soklet.fromConfig(builder.resourceMethodResolver(ResourceMethodResolver.fromClasses(
				Set.of(type == ServerType.HTTP ? HttpRoutes.class : SseRoutes.class)))
				.lifecycleObserver(observer).metricsCollector(collector).lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofMillis(200))
						.gracefulShutdownTimeout(Duration.ofMillis(200)).forcedShutdownTimeout(Duration.ofMillis(200)).build()).build());
		owner.set(soklet);
		try {
			soklet.start();
			int port;
			if (type == ServerType.HTTP) port = http.getEventLoop().orElseThrow().getPort();
			else {
				var field = DefaultSseServer.class.getDeclaredField("serverSocketChannel");
				field.setAccessible(true);
				port = ((InetSocketAddress) ((ServerSocketChannel) field.get(sse)).getLocalAddress()).getPort();
			}
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
				Throwable failure = outcome.get(2, TimeUnit.SECONDS);
				assertInstanceOf(IllegalStateException.class, failure);
				assertEquals("Lifecycle wait cannot run from tracked lifecycle execution", failure.getMessage());
			}
		} finally {
			// A broken guard must leave a bounded, releasable regression rather than a hung suite.
			if (!outcome.isDone()) {
				Thread blocked = callbackThread.get();
				if (blocked != null) blocked.interrupt();
			}
			soklet.shutdown().toCompletableFuture().get(4, TimeUnit.SECONDS);
		}
	}
}
