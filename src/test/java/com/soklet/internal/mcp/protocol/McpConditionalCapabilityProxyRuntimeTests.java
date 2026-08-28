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
import com.soklet.McpRequestOutcome;
import com.soklet.McpRequestStateMode;
import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Live two-leg proxy evidence for the conditional-capability response hold.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
@Timeout(60)
public class McpConditionalCapabilityProxyRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String METHOD = "tools/call";
	private static final String TOOL = "conditional.proxy";
	private static final String PROGRESS_TOKEN = "proxy-progress";
	private static final Duration PROXY_IDLE_TIMEOUT = Duration.ofSeconds(37);

	@Test
	@Timeout(120)
	public void proxyIdleExpiryCancelsSilentHoldAndSupportedControlForwardsSse()
			throws Exception {
		McpInputRequestDeclaration roots = McpInputRequestDeclaration.roots(
				McpInputRequirement.CONDITIONAL);
		McpInputRequestPlan inputPlan = new McpInputRequestPlan(List.of(roots));
		ManualMonotonicClock proxyClock = new ManualMonotonicClock();
		CountDownLatch blockedHandlerEntered = new CountDownLatch(1);
		CountDownLatch releaseBlockedHandler = new CountDownLatch(1);
		CountDownLatch blockedHandlerExited = new CountDownLatch(1);
		CountDownLatch cancelationDelivered = new CountDownLatch(1);
		CountDownLatch supportedHandlerEntered = new CountDownLatch(1);
		AtomicBoolean blockedReporterSuppressed = new AtomicBoolean();
		AtomicInteger cancelations = new AtomicInteger();
		AtomicReference<StreamTerminationReason> cancelationReason =
				new AtomicReference<>();
		McpRuntimeObservationRecorder observations =
				new McpRuntimeObservationRecorder();
		McpHttpServerRuntime runtime = runtime(inputPlan, observations,
				invocation -> {
			invocation.requireHandlerEntry();
			if (invocation.request().params().metadata().clientCapabilities()
					.supports(McpCoreClientCapability.ROOTS)) {
				supportedHandlerEntered.countDown();
				McpServerRuntimeBridge.ProgressEmitter emitter =
						McpServerRuntimeBridge.progressEmitterFor(invocation,
								inputPlan).orElseThrow();
				Assertions.assertTrue(emitter.emit(1.0d, Optional.of(2.0d),
						Optional.of("forwarded")));
				return complete("supported complete");
			}

			blockedReporterSuppressed.set(McpServerRuntimeBridge
					.progressEmitterFor(invocation, inputPlan).isEmpty());
			invocation.cancelationToken().onCancel(() -> {
				cancelationReason.compareAndSet(null,
						invocation.cancellationReason().orElseThrow());
				cancelations.incrementAndGet();
				cancelationDelivered.countDown();
			});
			blockedHandlerEntered.countDown();
			try {
				awaitUninterruptibly(releaseBlockedHandler);
			} finally {
				blockedHandlerExited.countDown();
			}
			return complete("late result must be discarded");
		});
		ForwardingIdleProxy proxy = null;
		McpChunkedHttpClient missingClient = null;
		McpChunkedHttpClient supportedClient = null;

		try {
			int serverPort = runtime.start().getPort();
			proxy = new ForwardingIdleProxy(serverPort, PROXY_IDLE_TIMEOUT,
					proxyClock);

			missingClient = call(proxy.port(), "proxy-missing", false);
			ProxyConnection missingConnection = proxy.awaitNextConnection();
			await(blockedHandlerEntered,
					"The missing-capability handler did not enter.");
			Assertions.assertTrue(blockedReporterSuppressed.get());
			Assertions.assertTrue(missingConnection.forwardedRequestBytes() > 0L,
					"The proxy did not forward the request to the live listener.");

			McpApplicationExecutionSnapshot heldApplication = awaitApplication(
					runtime, snapshot -> snapshot.activeHandlerSlots() == 1
							&& snapshot.activeIdentifiedRequestExchanges() == 1
							&& snapshot.retainedExchanges() == 1
							&& snapshot.retainedTransportLeases() == 1);
			Assertions.assertEquals(0, heldApplication.terminalResponses());
			Assertions.assertEquals(0, heldApplication.abandonedResponses());
			McpRequestExecutionSnapshot heldRequest = runtime
					.requestExecutionSnapshot();
			Assertions.assertEquals(1, heldRequest.retainedRequestControls());
			Assertions.assertEquals(0, heldRequest.queuedProtocolRequests());
			Assertions.assertEquals(1,
					heldRequest.activeIdentifiedRequestExchanges());
			assertNoResponseState(heldRequest);
			Assertions.assertEquals(0L,
					missingConnection.backendResponseBytes());
			Assertions.assertEquals(0L,
					missingConnection.forwardedResponseBytes());

			proxyClock.advance(PROXY_IDLE_TIMEOUT.minusNanos(1L));
			Assertions.assertFalse(proxy.runIdleCycle(missingConnection),
					"The proxy expired one nanosecond before its idle boundary.");
			Assertions.assertFalse(missingConnection.isClosed());
			Assertions.assertEquals(0, proxy.expirations());
			Assertions.assertEquals(0, cancelations.get());
			Assertions.assertEquals(0L,
					missingConnection.backendResponseBytes());
			Assertions.assertEquals(0L,
					missingConnection.forwardedResponseBytes());

			proxyClock.advanceNanos(1L);
			Assertions.assertTrue(proxy.runIdleCycle(missingConnection),
					"The proxy did not expire at the exact idle boundary.");
			Assertions.assertFalse(proxy.runIdleCycle(missingConnection),
					"A closed proxy connection must not expire twice.");
			await(missingConnection.closed(),
					"The proxy did not close both connection legs.");
			await(missingConnection.pumpsStopped(),
					"The proxy forwarding pumps did not stop.");
			await(cancelationDelivered,
					"The proxy disconnect did not signal cancelation.");
			Assertions.assertEquals(1, proxy.expirations());
			Assertions.assertEquals(1, cancelations.get());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
					cancelationReason.get());

			McpRuntimeObservationRecorder.Observation missingObservation =
					observations.observation("proxy-missing");
			McpRuntimeObservationRecorder.Finish missingFinish =
					missingObservation.awaitFinish();
			Assertions.assertEquals(McpRequestOutcome.CLIENT_DISCONNECTED,
					missingFinish.outcome());
			Assertions.assertNull(missingFinish.error());
			Assertions.assertThrows(IOException.class, missingClient::readHead,
					"The expired proxy must not synthesize an HTTP response.");

			McpApplicationExecutionSnapshot disconnected = awaitApplication(
					runtime, snapshot -> snapshot.activeHandlerSlots() == 1
							&& snapshot.activeIdentifiedRequestExchanges() == 0
							&& snapshot.retainedExchanges() == 1
							&& snapshot.retainedTransportLeases() == 0
							&& snapshot.abandonedResponses() == 1
							&& snapshot.responseCleanups() == 1);
			Assertions.assertEquals(0, disconnected.terminalResponses());
			Assertions.assertEquals(0, disconnected.deadlineExpirations());
			Assertions.assertEquals(0,
					disconnected.protocolDeadlineExpirations());
			Assertions.assertEquals(1, disconnected.abandonedResponses());
			Assertions.assertEquals(1, disconnected.responseCleanups());
			assertRequestClean(runtime.requestExecutionSnapshot());

			releaseBlockedHandler.countDown();
			await(blockedHandlerExited,
					"The released missing-capability handler did not exit.");
			McpApplicationExecutionSnapshot missingReleased = awaitApplication(
					runtime, snapshot -> snapshot.activeHandlerSlots() == 0
							&& snapshot.activeIdentifiedRequestExchanges() == 0
							&& snapshot.retainedExchanges() == 0
							&& snapshot.retainedTransportLeases() == 0
							&& snapshot.abandonedResponses() == 1
							&& snapshot.responseCleanups() == 1);
			Assertions.assertEquals(0, missingReleased.terminalResponses());
			Assertions.assertEquals(0, missingReleased.deadlineExpirations());
			Assertions.assertEquals(1, missingReleased.abandonedResponses());
			Assertions.assertEquals(1, missingReleased.responseCleanups());
			Assertions.assertEquals(1, missingObservation.finishCount());
			Assertions.assertEquals(1, cancelations.get());
			Assertions.assertEquals(0L,
					missingConnection.backendResponseBytes(),
					"The released handler produced late backend bytes.");
			Assertions.assertEquals(0L,
					missingConnection.forwardedResponseBytes(),
					"The released handler produced a late client-visible result.");
			assertRequestClean(runtime.requestExecutionSnapshot());
			assertRuntimeGaugesClean(runtime);

			supportedClient = call(proxy.port(), "proxy-supported", true);
			ProxyConnection supportedConnection = proxy.awaitNextConnection();
			await(supportedHandlerEntered,
					"The capability-present control handler did not enter.");
			McpChunkedHttpClient.HttpResponseHead supportedHead =
					supportedClient.readHead();
			Assertions.assertEquals(200, supportedHead.status(),
					supportedHead.raw());
			Assertions.assertEquals("text/event-stream",
					supportedHead.singleHeader("Content-Type"));
			String progress = supportedClient.readChunkText();
			Assertions.assertTrue(progress.contains(
					"\"method\":\"notifications/progress\""), progress);
			Assertions.assertTrue(progress.contains(
					"\"progressToken\":\"" + PROGRESS_TOKEN + "\""),
					progress);
			Assertions.assertTrue(progress.contains("\"progress\":1"),
					progress);
			String terminal = supportedClient.readChunkText();
			Assertions.assertTrue(terminal.contains("\"id\":\"proxy-supported\""),
					terminal);
			Assertions.assertTrue(terminal.contains("supported complete"), terminal);
			Assertions.assertNull(supportedClient.readChunk());
			supportedClient.close();
			supportedClient = null;
			await(supportedConnection.pumpsStopped(),
					"The control proxy connection did not release its pumps.");
			Assertions.assertTrue(supportedConnection.backendResponseBytes() > 0L);
			Assertions.assertEquals(supportedConnection.backendResponseBytes(),
					supportedConnection.forwardedResponseBytes(),
					"The working proxy must forward every backend response byte.");
			Assertions.assertEquals(1, proxy.expirations(),
					"The completed control must not trigger another idle expiry.");

			McpRuntimeObservationRecorder.Observation supportedObservation =
					observations.observation("proxy-supported");
			McpRuntimeObservationRecorder.Finish supportedFinish =
					supportedObservation.awaitFinish();
			Assertions.assertEquals(McpRequestOutcome.COMPLETE,
					supportedFinish.outcome());
			Assertions.assertEquals(1, supportedObservation.finishCount());
			McpApplicationExecutionSnapshot completed = awaitApplication(runtime,
					snapshot -> snapshot.activeHandlerSlots() == 0
							&& snapshot.activeIdentifiedRequestExchanges() == 0
							&& snapshot.retainedExchanges() == 0
							&& snapshot.retainedTransportLeases() == 0
							&& snapshot.terminalResponses() == 1
							&& snapshot.abandonedResponses() == 1
							&& snapshot.responseCleanups() == 2);
			Assertions.assertEquals(2, completed.admittedRequests());
			Assertions.assertEquals(0, completed.deadlineExpirations());
			Assertions.assertEquals(0, completed.protocolDeadlineExpirations());
			Assertions.assertEquals(1, cancelations.get());
			assertRequestClean(runtime.requestExecutionSnapshot());
			assertRuntimeGaugesClean(runtime);
		} finally {
			releaseBlockedHandler.countDown();
			if (missingClient != null)
				missingClient.close();
			if (supportedClient != null)
				supportedClient.close();
			if (proxy != null)
				proxy.close();
			runtime.close();
		}
	}

	private static McpHttpServerRuntime runtime(McpInputRequestPlan inputPlan,
			McpRuntimeObservationSink observations,
			McpApplicationRequestHandler handler) {
		McpNormalizedToolDescriptor descriptor = new McpNormalizedToolDescriptor(
				TOOL, objectSchema(), Optional.empty(), McpJsonObject.empty(),
				McpJsonObject.empty());
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"conditional-proxy-runtime-test", "4.0.0-SNAPSHOT"))
				.tool(McpNormalizedOperation.tool(descriptor, inputPlan,
						McpMirroredHeaderPlan.empty()))
				.build();
		McpApplicationRequestRouter router =
				McpApplicationRequestRouter.fromToolRoutes(Map.of(TOOL,
						new McpApplicationToolRoute(handler,
								ignored -> McpRateLimitDecision.allowed(), inputPlan,
								McpRequestStateMode.NONE)));
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				McpProtocolAdmissionController.acceptAllInstance());
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy,
				endpoint, router, observations);
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				List.of(binding), McpJsonLimits.productionDefaults(),
				new McpApplicationExecutionConfiguration(1, 2,
						Duration.ofMinutes(1), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {}, Optional.empty(),
				McpFrameworkRequestStateRuntime.disabledInstance());
	}

	private static McpChunkedHttpClient call(int port, String requestId,
			boolean rootsCapability) throws IOException {
		String capabilities = rootsCapability ? "{\"roots\":{}}" : "{}";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
				+ "\",\"method\":\"" + METHOD + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":"
				+ capabilities + ",\"progressToken\":\"" + PROGRESS_TOKEN
				+ "\"},\"name\":\"" + TOOL + "\",\"arguments\":{}}}";
		return McpChunkedHttpClient.postMcpMessage(port, body, List.of(
				new McpChunkedHttpClient.RequestHeader(
						"MCP-Protocol-Version", PROTOCOL_VERSION),
				new McpChunkedHttpClient.RequestHeader("Mcp-Method", METHOD),
				new McpChunkedHttpClient.RequestHeader("Mcp-Name", TOOL)));
	}

	private static McpWireResult complete(String value) {
		return McpWireResult.complete(new McpJsonObject(
				Map.of("value", new McpJsonString(value))));
	}

	private static McpJsonObject objectSchema() {
		return new McpJsonObject(Map.of("type", new McpJsonString("object")));
	}

	private static void assertNoResponseState(McpRequestExecutionSnapshot snapshot) {
		Assertions.assertEquals(0, snapshot.activeResponseStreams());
		Assertions.assertEquals(0, snapshot.bufferedStreamFrames());
		Assertions.assertEquals(0, snapshot.bufferedStreamBytes());
		Assertions.assertEquals(0, snapshot.terminalStreamBytes());
	}

	private static void assertRequestClean(McpRequestExecutionSnapshot snapshot) {
		Assertions.assertEquals(0, snapshot.retainedRequestControls());
		Assertions.assertEquals(0, snapshot.queuedProtocolRequests());
		Assertions.assertEquals(0, snapshot.activeIdentifiedRequestExchanges());
		assertNoResponseState(snapshot);
	}

	private static void assertRuntimeGaugesClean(McpHttpServerRuntime runtime) {
		McpHttpServerDiagnosticsSnapshot diagnostics = runtime
				.diagnosticsSnapshot();
		Assertions.assertEquals(0, diagnostics.activeHandlerExecutions());
		Assertions.assertEquals(0, diagnostics.queuedRequests());
		Assertions.assertEquals(0, diagnostics.activeRequestStreams());
		Assertions.assertEquals(0, diagnostics.activeSubscriptions());
	}

	private static McpApplicationExecutionSnapshot awaitApplication(
			McpHttpServerRuntime runtime,
			Predicate<McpApplicationExecutionSnapshot> condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpApplicationExecutionSnapshot latest;
		do {
			latest = runtime.applicationExecutionSnapshot().orElseThrow();
			if (condition.test(latest))
				return latest;
			Thread.yield();
		} while (System.nanoTime() - deadline < 0L);
		throw new AssertionError(
				"Timed out waiting for application cleanup: " + latest);
	}

	private static void await(CountDownLatch latch, String failure)
			throws InterruptedException {
		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), failure);
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try {
				latch.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	@ThreadSafe
	private static final class ManualMonotonicClock {
		private final AtomicLong nanoseconds = new AtomicLong();

		private long nanoTime() {
			return this.nanoseconds.get();
		}

		private void advance(Duration duration) {
			this.nanoseconds.addAndGet(duration.toNanos());
		}

		private void advanceNanos(long nanoseconds) {
			this.nanoseconds.addAndGet(nanoseconds);
		}
	}

	@ThreadSafe
	private static final class ForwardingIdleProxy implements AutoCloseable {
		private final int backendPort;
		private final long idleTimeoutNanos;
		private final ManualMonotonicClock clock;
		private final ServerSocket listener;
		private final ExecutorService executor;
		private final BlockingQueue<ProxyConnection> acceptedConnections;
		private final List<ProxyConnection> allConnections;
		private final AtomicReference<Throwable> failure;
		private final AtomicInteger expirations;
		private final AtomicBoolean closing;

		private ForwardingIdleProxy(int backendPort, Duration idleTimeout,
				ManualMonotonicClock clock) throws IOException {
			this.backendPort = backendPort;
			this.idleTimeoutNanos = idleTimeout.toNanos();
			this.clock = clock;
			this.listener = new ServerSocket();
			this.listener.bind(new InetSocketAddress(LOOPBACK, 0));
			this.executor = Executors.newFixedThreadPool(5);
			this.acceptedConnections = new LinkedBlockingQueue<>();
			this.allConnections = new java.util.concurrent.CopyOnWriteArrayList<>();
			this.failure = new AtomicReference<>();
			this.expirations = new AtomicInteger();
			this.closing = new AtomicBoolean();
			this.executor.execute(this::acceptConnections);
		}

		private int port() {
			return this.listener.getLocalPort();
		}

		private ProxyConnection awaitNextConnection() throws Exception {
			ProxyConnection connection = this.acceptedConnections.poll(
					5, TimeUnit.SECONDS);
			if (connection == null)
				throw new AssertionError(
						"The proxy did not accept the next connection.");
			assertHealthy();
			return connection;
		}

		private boolean runIdleCycle(ProxyConnection connection) {
			if (connection.isClosed())
				return false;
			long elapsed = this.clock.nanoTime()
					- connection.lastBackendResponseNanos();
			if (elapsed < this.idleTimeoutNanos)
				return false;
			if (!connection.markExpired())
				return false;
			this.expirations.incrementAndGet();
			connection.close(true);
			return true;
		}

		private int expirations() {
			return this.expirations.get();
		}

		private void acceptConnections() {
			while (!this.closing.get()) {
				Socket client = null;
				Socket backend = null;
				try {
					client = this.listener.accept();
					client.setTcpNoDelay(true);
					backend = new Socket();
					backend.setTcpNoDelay(true);
					backend.connect(new InetSocketAddress(LOOPBACK,
							this.backendPort),
							(int) TimeUnit.SECONDS.toMillis(5));
					ProxyConnection connection = new ProxyConnection(client,
							backend, this.clock.nanoTime());
					this.allConnections.add(connection);
					this.acceptedConnections.add(connection);
					this.executor.execute(() -> pumpRequest(connection));
					this.executor.execute(() -> pumpResponse(connection));
					client = null;
					backend = null;
				} catch (SocketException exception) {
					if (!this.closing.get())
						this.failure.compareAndSet(null, exception);
				} catch (Throwable throwable) {
					this.failure.compareAndSet(null, throwable);
				} finally {
					closeSocket(client, false);
					closeSocket(backend, false);
				}
			}
		}

		private void pumpRequest(ProxyConnection connection) {
			try {
				InputStream input = connection.client().getInputStream();
				OutputStream output = connection.backend().getOutputStream();
				byte[] head = backendAuthorityHead(readHttpHead(input));
				output.write(head);
				output.flush();
				connection.didForwardRequest(head.length);
				byte[] buffer = new byte[8_192];
				int count;
				while ((count = input.read(buffer)) >= 0) {
					if (count == 0)
						continue;
					output.write(buffer, 0, count);
					output.flush();
					connection.didForwardRequest(count);
				}
			} catch (IOException ignored) {
				// Peer closure and the explicit idle close terminate the pump.
			} catch (Throwable throwable) {
				this.failure.compareAndSet(null, throwable);
			} finally {
				connection.pumpStopped();
				connection.close(false);
			}
		}

		private void pumpResponse(ProxyConnection connection) {
			pumpResponse(connection, connection.backend(), connection.client());
		}

		private void pumpResponse(ProxyConnection connection, Socket source,
				Socket destination) {
			byte[] buffer = new byte[8_192];
			try {
				InputStream input = source.getInputStream();
				OutputStream output = destination.getOutputStream();
				int count;
				while ((count = input.read(buffer)) >= 0) {
					if (count == 0)
						continue;
					connection.didReadBackendResponse(count,
							this.clock.nanoTime());
					output.write(buffer, 0, count);
					output.flush();
					connection.didForwardResponse(count);
				}
			} catch (IOException ignored) {
				// Peer closure and the explicit idle close terminate the pumps.
			} catch (Throwable throwable) {
				this.failure.compareAndSet(null, throwable);
			} finally {
				connection.pumpStopped();
				connection.close(false);
			}
		}

		private byte[] backendAuthorityHead(byte[] headBytes) {
			String head = new String(headBytes, StandardCharsets.ISO_8859_1);
			int hostStart = head.indexOf("\r\nHost: ");
			if (hostStart < 0)
				throw new IllegalArgumentException(
						"The proxied request did not contain a Host header.");
			hostStart += 2;
			int hostEnd = head.indexOf("\r\n", hostStart);
			String rewritten = head.substring(0, hostStart)
					+ "Host: " + LOOPBACK + ':' + this.backendPort
					+ head.substring(hostEnd);
			return rewritten.getBytes(StandardCharsets.ISO_8859_1);
		}

		private static byte[] readHttpHead(InputStream input) throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			int matched = 0;
			while (bytes.size() < 64 * 1_024) {
				int value = input.read();
				if (value < 0)
					throw new IOException(
							"The client closed before its HTTP head completed.");
				bytes.write(value);
				matched = switch (matched) {
					case 0 -> value == '\r' ? 1 : 0;
					case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
					case 2 -> value == '\r' ? 3 : 0;
					case 3 -> value == '\n' ? 4 : 0;
					default -> matched;
				};
				if (matched == 4)
					return bytes.toByteArray();
			}
			throw new IOException("The proxied HTTP head exceeded 64 KiB.");
		}

		private void assertHealthy() {
			Throwable throwable = this.failure.get();
			if (throwable != null)
				throw new AssertionError("The forwarding proxy failed.", throwable);
		}

		@Override
		public void close() throws InterruptedException {
			this.closing.set(true);
			try {
				this.listener.close();
			} catch (IOException ignored) {
			}
			for (ProxyConnection connection : this.allConnections)
				connection.close(false);
			this.executor.shutdownNow();
			Assertions.assertTrue(this.executor.awaitTermination(
					5, TimeUnit.SECONDS),
					"The forwarding proxy executor did not stop.");
			assertHealthy();
		}
	}

	@ThreadSafe
	private static final class ProxyConnection {
		private final Socket client;
		private final Socket backend;
		private final AtomicLong lastBackendResponseNanos;
		private final AtomicLong forwardedRequestBytes;
		private final AtomicLong backendResponseBytes;
		private final AtomicLong forwardedResponseBytes;
		private final AtomicBoolean expired;
		private final AtomicBoolean closedState;
		private final CountDownLatch closed;
		private final CountDownLatch pumpsStopped;

		private ProxyConnection(Socket client, Socket backend,
				long acceptedNanos) {
			this.client = client;
			this.backend = backend;
			this.lastBackendResponseNanos = new AtomicLong(acceptedNanos);
			this.forwardedRequestBytes = new AtomicLong();
			this.backendResponseBytes = new AtomicLong();
			this.forwardedResponseBytes = new AtomicLong();
			this.expired = new AtomicBoolean();
			this.closedState = new AtomicBoolean();
			this.closed = new CountDownLatch(1);
			this.pumpsStopped = new CountDownLatch(2);
		}

		private Socket client() {
			return this.client;
		}

		private Socket backend() {
			return this.backend;
		}

		private long lastBackendResponseNanos() {
			return this.lastBackendResponseNanos.get();
		}

		private void didForwardRequest(int count) {
			this.forwardedRequestBytes.addAndGet(count);
		}

		private void didReadBackendResponse(int count, long nowNanos) {
			this.backendResponseBytes.addAndGet(count);
			this.lastBackendResponseNanos.set(nowNanos);
		}

		private void didForwardResponse(int count) {
			this.forwardedResponseBytes.addAndGet(count);
		}

		private long forwardedRequestBytes() {
			return this.forwardedRequestBytes.get();
		}

		private long backendResponseBytes() {
			return this.backendResponseBytes.get();
		}

		private long forwardedResponseBytes() {
			return this.forwardedResponseBytes.get();
		}

		private boolean markExpired() {
			return this.expired.compareAndSet(false, true);
		}

		private boolean isClosed() {
			return this.closedState.get();
		}

		private CountDownLatch closed() {
			return this.closed;
		}

		private CountDownLatch pumpsStopped() {
			return this.pumpsStopped;
		}

		private void pumpStopped() {
			this.pumpsStopped.countDown();
		}

		private void close(boolean reset) {
			if (!this.closedState.compareAndSet(false, true))
				return;
			closeSocket(this.client, reset);
			closeSocket(this.backend, reset);
			this.closed.countDown();
		}
	}

	private static void closeSocket(Socket socket, boolean reset) {
		if (socket == null)
			return;
		try {
			if (reset && !socket.isClosed())
				socket.setSoLinger(true, 0);
		} catch (SocketException ignored) {
		}
		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}
}
