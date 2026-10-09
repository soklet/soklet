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

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static org.junit.jupiter.api.Assertions.*;

/** Real concurrent work in all three built-in transports under one owner. */
@Timeout(60)
@EnabledForJreRange(min = JRE.JAVA_21)
class CombinedTransportShutdownTests {

	@Test
	void gracefulShutdownClosesSseAndDrainsFiniteHttpAndMcpWithOneDeadline() throws Exception {
		try (Fixture fixture = new Fixture(false, Duration.ofSeconds(3))) {
			fixture.openWork();
			fixture.feedClient = get(fixture.httpPort, "/feed");
			assertTrue(head(fixture.feedClient).startsWith("HTTP/1.1 200 OK"));
			assertTrue(fixture.resource.feedEntered.await(2, TimeUnit.SECONDS));
			CompletableFuture<ShutdownResult> shutdown = fixture.soklet.shutdown().toCompletableFuture();
			fixture.assertSharedDeadline(ShutdownPhase.GRACEFUL);
			assertEquals("4\r\nlast\r\n0\r\n\r\n", remainder(fixture.feedClient));
			assertTrue(fixture.sseWork.exited.await(2, TimeUnit.SECONDS));
			assertTrue(fixture.sseTerminated.await(2, TimeUnit.SECONDS));
			assertEquals(StreamTerminationReason.SERVER_STOPPING, fixture.sseTermination.get().getReason());
			assertEquals(-1, fixture.sseClient.getInputStream().read(), "SSE delivery must end during graceful shutdown");
			assertFalse(shutdown.isDone(), "Finite admitted HTTP and MCP work must still be retained");
			assertEquals(1L, fixture.httpWork.interrupted.getCount());
			assertEquals(1L, fixture.mcpWork.interrupted.getCount());
			fixture.httpWork.release.countDown();
			fixture.mcpWork.release.countDown();
			assertEquals("5\r\nfirst\r\n6\r\nsecond\r\n0\r\n\r\n", remainder(fixture.httpClient),
					"The default drain policy must preserve the entire finite HTTP stream");
			String mcpResponse = remainder(fixture.mcpClient);
			assertTrue(mcpResponse.startsWith("HTTP/1.1 200 OK"), mcpResponse);
			assertTrue(mcpResponse.contains("released"), mcpResponse);
			ShutdownResult result = shutdown.get(4, TimeUnit.SECONDS);
			assertTrue(result.isComplete());
			assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
			fixture.assertComponents(result, ShutdownComponentDisposition.GRACEFUL_TERMINATION, false);
			assertEquals(StreamTerminationReason.COMPLETED, fixture.httpTermination.get().getReason());
			assertNull(fixture.httpPhases.forced.get());
			assertNull(fixture.ssePhases.forced.get());
			assertSame(result, fixture.soklet.awaitShutdown());
			fixture.assertPhysicalTermination();
		}
	}

	@Test
	void forcedShutdownRetainsAllBlockedWorkersAndLateExitCannotRewriteFrozenResult() throws Exception {
		try (Fixture fixture = new Fixture(true, Duration.ofMillis(300))) {
			fixture.openWork();
			CompletableFuture<ShutdownResult> shutdown = fixture.soklet.shutdown().toCompletableFuture();
			fixture.assertSharedDeadline(ShutdownPhase.GRACEFUL);
			assertEquals(-1, fixture.sseClient.getInputStream().read());
			assertEquals(1L, fixture.sseWork.exited.getCount(),
					"A terminated SSE socket is not proof that its initializer returned");
			ShutdownResult result = shutdown.get(3, TimeUnit.SECONDS);
			fixture.assertSharedDeadline(ShutdownPhase.FORCED);
			assertFalse(result.isComplete());
			assertEquals(ShutdownDisposition.INCOMPLETE, result.getShutdownDisposition());
			fixture.assertComponents(result, ShutdownComponentDisposition.RESIDUAL_ACTIVITY, true);
			for (WorkGate work : fixture.work()) {
				assertEquals(0L, work.interrupted.getCount(), "Each retained worker must receive cooperative interruption");
				assertEquals(1L, work.exited.getCount(), "Every worker is still physically running at the result boundary");
			}
			assertTrue(fixture.httpTerminated.await(2, TimeUnit.SECONDS));
			assertEquals(StreamTerminationReason.SERVER_STOPPING, fixture.httpTermination.get().getReason());
			fixture.releaseWork();
			fixture.assertPhysicalTermination();
			assertTrue(fixture.sseTerminated.await(2, TimeUnit.SECONDS));
			assertEquals(StreamTerminationReason.SERVER_STOPPING, fixture.sseTermination.get().getReason());
			assertSame(result, fixture.soklet.getShutdownResult().orElseThrow());
			assertSame(result, fixture.soklet.shutdown().toCompletableFuture().get(1, TimeUnit.SECONDS));
			fixture.assertComponents(result, ShutdownComponentDisposition.RESIDUAL_ACTIVITY, true);
			SokletShutdownIncompleteException failure = assertThrows(SokletShutdownIncompleteException.class, fixture.soklet::close);
			assertSame(result, failure.getShutdownResult(), "Late physical cleanup cannot rewrite the frozen shutdown result");
		}
	}

	private static final class Fixture implements AutoCloseable {
		final WorkGate httpWork;
		final WorkGate sseWork;
		final WorkGate mcpWork;
		final int httpPort = findFreePort();
		final int ssePort = findFreePort();
		final DefaultHttpServer httpServer;
		final DefaultSseServer sseServer;
		final McpServer mcpServer;
		final PhaseRecorder httpPhases = new PhaseRecorder();
		final PhaseRecorder ssePhases = new PhaseRecorder();
		final AtomicReference<StreamTermination> httpTermination = new AtomicReference<>();
		final AtomicReference<StreamTermination> sseTermination = new AtomicReference<>();
		final CountDownLatch httpTerminated = new CountDownLatch(1);
		final CountDownLatch sseTerminated = new CountDownLatch(1);
		final Soklet soklet;
		final Resource resource;
		final McpServerRuntimeBridge mcpBridge;
		final Object mcpRuntime;
		ExecutorService httpProducerExecutor;
		ExecutorService sseConnectionExecutor;
		Socket httpClient;
		Socket sseClient;
		Socket mcpClient;
		Socket feedClient;

		Fixture(boolean ignoreInterrupts, Duration gracefulTimeout) throws Exception {
			this.httpWork = new WorkGate("HTTP producer", ignoreInterrupts);
			this.sseWork = new WorkGate("SSE initializer", ignoreInterrupts);
			this.mcpWork = new WorkGate("MCP handler", ignoreInterrupts);
			this.httpServer = (DefaultHttpServer) HttpServer.withPort(this.httpPort).host("127.0.0.1")
					.streamingResponseTimeout(Duration.ofSeconds(10)).build();
			this.sseServer = (DefaultSseServer) SseServer.withPort(this.ssePort).host("127.0.0.1")
					.verifyConnectionOnceEstablished(false).heartbeatInterval(Duration.ofSeconds(30)).build();
			this.httpPhases.observe(this.httpServer.getLifecycleAdapter());
			this.ssePhases.observe(this.sseServer.getLifecycleAdapter());
			McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("bounded_work", Set.of(McpProtocolVersion.V2026_07_28))
					.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
						this.mcpWork.run();
						return McpCompleteResult.fromToolText("released");
					}).build();
			McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("combined-shutdown", "4.0.0").build(),
					Set.of(McpProtocolVersion.V2026_07_28)).toolRegistrations(List.of(tool)).build();
			this.mcpServer = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
					.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
					.toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed())
					.admissionController(McpAdmissionController.acceptAllInstance()).build();
			this.resource = new Resource(this.httpWork, this.sseWork);
			Resource resource = this.resource;
			this.soklet = Soklet.fromConfig(SokletConfig.withHttpServer(this.httpServer).sseServer(this.sseServer).mcpServer(this.mcpServer)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					}).lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent logEvent) {}
						@Override public void didTerminateResponseStream(StreamingResponseHandle streamingResponseHandle, StreamTermination streamTermination) {
							httpTermination.set(streamTermination); httpTerminated.countDown();
						}
						@Override public void didTerminateSseConnection(SseConnection sseConnection, StreamTermination streamTermination) {
							sseTermination.set(streamTermination); sseTerminated.countDown();
						}
					}).lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(gracefulTimeout)
							.forcedShutdownTimeout(Duration.ofMillis(300)).build()).build());
			this.mcpBridge = (McpServerRuntimeBridge) field(this.mcpServer, "runtimeBridge");
			this.mcpRuntime = field(this.mcpBridge, "runtime");
		}

		void openWork() throws Exception {
			this.soklet.start();
			this.httpProducerExecutor = this.httpServer.getStreamingExecutorService().orElseThrow();
			this.sseConnectionExecutor = this.sseServer.getConnectionExecutorService().orElseThrow();
			this.httpClient = get(this.httpPort, "/finite-stream");
			assertTrue(head(this.httpClient).startsWith("HTTP/1.1 200 OK"));
			this.sseClient = get(this.ssePort, "/events");
			assertTrue(head(this.sseClient).startsWith("HTTP/1.1 200 OK"));
			int mcpPort = this.mcpServer.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			this.mcpClient = connectWithRetry("127.0.0.1", mcpPort, 2000);
			this.mcpClient.setSoTimeout(4000);
			String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"bounded_work\",\"arguments\":{},"
					+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
			this.mcpClient.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n"
					+ "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
					+ "MCP-Protocol-Version: 2026-07-28\r\nMcp-Method: tools/call\r\nMcp-Name: bounded_work\r\nContent-Length: "
					+ body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
			for (WorkGate work : work()) assertTrue(work.entered.await(2, TimeUnit.SECONDS), work.name + " must have active application work");
		}

		List<WorkGate> work() { return List.of(this.httpWork, this.sseWork, this.mcpWork); }
		void releaseWork() { for (WorkGate work : work()) work.release.countDown(); }

		void assertSharedDeadline(ShutdownPhase phase) throws Exception {
			CountDownLatch delivered = phase == ShutdownPhase.GRACEFUL ? this.httpPhases.gracefulDelivered : this.httpPhases.forcedDelivered;
			assertTrue(delivered.await(2, TimeUnit.SECONDS));
			CountDownLatch sseDelivered = phase == ShutdownPhase.GRACEFUL ? this.ssePhases.gracefulDelivered : this.ssePhases.forcedDelivered;
			assertTrue(sseDelivered.await(2, TimeUnit.SECONDS));
			ShutdownContext http = phase == ShutdownPhase.GRACEFUL ? this.httpPhases.graceful.get() : this.httpPhases.forced.get();
			ShutdownContext sse = phase == ShutdownPhase.GRACEFUL ? this.ssePhases.graceful.get() : this.ssePhases.forced.get();
			assertEquals(phase, http.getShutdownPhase());
			assertEquals(phase, sse.getShutdownPhase());
			assertEquals(http.absoluteDeadlineNanos(), sse.absoluteDeadlineNanos(), "HTTP and SSE must receive the same absolute phase boundary");
			String prefix = phase == ShutdownPhase.GRACEFUL ? "lifecycleGraceful" : "lifecycleForced";
			// MCP is sealed, so inspect its existing internal boundary rather than inventing a public decorator API.
			long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (true) {
				synchronized (field(this.mcpRuntime, "lifecycleLock")) {
					if ((boolean) field(this.mcpRuntime, prefix + "DeadlinePresent")) {
						assertEquals(http.absoluteDeadlineNanos(), (long) field(this.mcpRuntime, prefix + "DeadlineNanos"),
								"MCP must share that exact boundary, rather than receiving a fresh per-transport budget");
						break;
					}
				}
				assertTrue(System.nanoTime() - waitDeadline < 0, "MCP did not receive the shutdown phase");
				Thread.sleep(5);
			}
		}

		void assertComponents(ShutdownResult result, ShutdownComponentDisposition expected, boolean residual) {
			assertEquals(Set.of(ShutdownComponentType.HTTP, ShutdownComponentType.SSE, ShutdownComponentType.MCP),
					result.getShutdownComponentResults().stream().map(ShutdownComponentResult::getShutdownComponentType).collect(java.util.stream.Collectors.toSet()));
			for (ShutdownComponentResult component : result.getShutdownComponentResults()) {
				assertEquals(expected, component.getShutdownComponentDisposition(), component.getShutdownComponentType().name());
				assertEquals(residual, component.getResidualActivityEvidence().isPresent(), component.getShutdownComponentType().name());
			}
		}

		void assertPhysicalTermination() throws Exception {
			for (WorkGate work : work()) assertTrue(work.exited.await(3, TimeUnit.SECONDS));
			assertTrue(this.httpProducerExecutor.awaitTermination(3, TimeUnit.SECONDS));
			assertTrue(this.sseConnectionExecutor.awaitTermination(3, TimeUnit.SECONDS));
			awaitMcpPhysicalTermination();
		}

		private void awaitMcpPhysicalTermination() throws Exception {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			// The runtime clamps waits to the owner's original phase deadline. Once
			// that deadline has expired, observe late convergence without extending it.
			while (!this.mcpBridge.awaitLifecycleTermination(deadline)) {
				assertTrue(System.nanoTime() - deadline < 0,
						"MCP's real workers and transport resources must converge after release");
				Thread.sleep(5);
			}
		}

		@Override public void close() throws Exception {
			this.resource.stopFeed.countDown();
			releaseWork();
			for (Socket socket : new Socket[]{this.httpClient, this.sseClient, this.mcpClient, this.feedClient}) if (socket != null) socket.close();
			try { this.soklet.close(); }
			catch (SokletShutdownIncompleteException failure) {
				assertEquals(ShutdownDisposition.INCOMPLETE, failure.getShutdownResult().getShutdownDisposition());
			}
			if (this.httpProducerExecutor != null) assertTrue(this.httpProducerExecutor.awaitTermination(3, TimeUnit.SECONDS));
			if (this.sseConnectionExecutor != null) assertTrue(this.sseConnectionExecutor.awaitTermination(3, TimeUnit.SECONDS));
			awaitMcpPhysicalTermination();
		}
	}

	public static final class Resource {
		private final WorkGate httpWork;
		private final WorkGate sseWork;
		final CountDownLatch feedEntered = new CountDownLatch(1);
		final CountDownLatch stopFeed = new CountDownLatch(1);
		Resource(WorkGate httpWork, WorkGate sseWork) { this.httpWork = httpWork; this.sseWork = sseWork; }
		@GET("/finite-stream") public MarshaledResponse stream() {
			return MarshaledResponse.withStatusCode(200).stream(responseStream -> {
				responseStream.write("first".getBytes(StandardCharsets.US_ASCII));
				responseStream.flush();
				this.httpWork.run();
				responseStream.write("second".getBytes(StandardCharsets.US_ASCII));
			}).build();
		}
		@SseEventSource("/events") public SseHandshakeResult events() {
			return SseHandshakeResult.Accepted.builder().clientInitializer(sseUnicaster -> this.sseWork.run()).build();
		}
		@GET("/feed") public MarshaledResponse feed() {
			return MarshaledResponse.withStatusCode(200).stream(responseStream -> {
				this.feedEntered.countDown();
				while (!responseStream.isGracefulShutdownRequested() && this.stopFeed.getCount() != 0) {
					responseStream.getCancelationToken().throwIfCanceled();
					this.stopFeed.await(10, TimeUnit.MILLISECONDS);
				}
				responseStream.getCancelationToken().throwIfCanceled();
				assertFalse(Thread.currentThread().isInterrupted());
				responseStream.write("last".getBytes(StandardCharsets.US_ASCII));
			}).build();
		}
	}

	private static final class WorkGate {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch interrupted = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch exited = new CountDownLatch(1);
		final String name;
		final boolean ignoreInterrupts;
		WorkGate(String name, boolean ignoreInterrupts) { this.name = name; this.ignoreInterrupts = ignoreInterrupts; }
		void run() throws InterruptedException {
			this.entered.countDown();
			boolean wasInterrupted = false;
			try {
				while (true) {
					try { this.release.await(); return; }
					catch (InterruptedException failure) {
						this.interrupted.countDown();
						if (!this.ignoreInterrupts) throw failure;
						wasInterrupted = true;
					}
				}
			} finally {
				if (wasInterrupted) Thread.currentThread().interrupt();
				this.exited.countDown();
			}
		}
	}

	private static final class PhaseRecorder {
		final AtomicReference<ShutdownContext> graceful = new AtomicReference<>();
		final AtomicReference<ShutdownContext> forced = new AtomicReference<>();
		final CountDownLatch gracefulDelivered = new CountDownLatch(1);
		final CountDownLatch forcedDelivered = new CountDownLatch(1);
		void observe(BuiltInTransportLifecycleAdapter adapter) throws Exception {
			// Preserve the configured built-in identities and their residual-evidence paths.
			// This test-only forwarding probe observes the actual phase delivered to transport operations.
			Field operations = BuiltInTransportLifecycleAdapter.class.getDeclaredField("operations");
			operations.setAccessible(true);
			BuiltInTransportLifecycleAdapter.Operations delegate = (BuiltInTransportLifecycleAdapter.Operations) operations.get(adapter);
			operations.set(adapter, new BuiltInTransportLifecycleAdapter.Operations() {
				@Override public void quiesce() { delegate.quiesce(); }
				@Override public void force() { delegate.force(); }
				@Override public void shutdownGracefully(ShutdownContext shutdownContext) {
					graceful.compareAndSet(null, shutdownContext);
					delegate.shutdownGracefully(shutdownContext); gracefulDelivered.countDown();
				}
				@Override public void shutdownForcibly(ShutdownContext shutdownContext) {
					forced.compareAndSet(null, shutdownContext);
					delegate.shutdownForcibly(shutdownContext); forcedDelivered.countDown();
				}
				@Override public boolean awaitTermination(long absoluteDeadlineNanos) throws InterruptedException {
					return delegate.awaitTermination(absoluteDeadlineNanos);
				}
				@Override public Set<InternalResidualActivityType> residualActivity() { return delegate.residualActivity(); }
				@Override public void releaseTerminatedEvidence() { delegate.releaseTerminatedEvidence(); }
			});
		}
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
	}
	private static Socket get(int port, String path) throws Exception {
		Socket socket = connectWithRetry("127.0.0.1", port, 2000); socket.setSoTimeout(4000);
		socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		return socket;
	}
	private static String head(Socket socket) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while (bytes.size() < 16384) {
			int value = socket.getInputStream().read(); assertNotEquals(-1, value, "Response ended before its headers"); bytes.write(value);
			String text = bytes.toString(StandardCharsets.US_ASCII); if (text.endsWith("\r\n\r\n")) return text;
		}
		throw new AssertionError("Response headers exceeded the fixture bound");
	}
	private static String remainder(Socket socket) throws Exception {
		return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
	}
}
