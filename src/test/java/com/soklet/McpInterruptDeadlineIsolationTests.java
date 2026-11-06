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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpInterruptDeadlineIsolationTests {
	@Test
	void slowChannelCloseCannotHoldTimeoutRepliesAndRetainsShutdownOwnership() throws Exception {
		Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2026_07_28);
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicBoolean marked = new AtomicBoolean();
		AtomicReference<Throwable> selfWaitFailure = new AtomicReference<>();
		AtomicReference<ShutdownResult> frozenResult = new AtomicReference<>();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch firstReturned = new CountDownLatch(1);
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch releaseClose = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		CountDownLatch releaseRead = new CountDownLatch(1);
		CountDownLatch releaseSecond = new CountDownLatch(1);
		ReadableByteChannel channel = new AbstractInterruptibleReadableChannel() {
			@Override public int read(ByteBuffer destination) throws IOException {
				begin();
				firstEntered.countDown();
				try {
					try { releaseRead.await(); }
					catch (InterruptedException ignored) { /* end reports the channel interrupt. */ }
					return -1;
				} finally { end(false); }
			}
			@Override protected void implCloseChannel() {
				Soklet owner = ownerReference.get();
				marked.set(LifecycleExecutionContext.isMarked(owner.getDirectLifecycle().executionOwnerToken()));
				try { owner.awaitShutdown(); }
				catch (Throwable failure) { selfWaitFailure.set(failure); }
				closeEntered.countDown();
				awaitUninterruptibly(releaseClose);
			}
		};
		McpToolRegistration<McpJsonObject> first = McpToolRegistration.withName("first", versions)
				.jsonObjectArguments().handler((request, arguments, features) -> {
					try { channel.read(ByteBuffer.allocate(1)); }
					catch (IOException ignored) { /* Expected interruptible-channel close. */ }
					firstReturned.countDown();
					return McpCompleteResult.fromToolText("first finished");
				}).build();
		McpToolRegistration<McpJsonObject> second = McpToolRegistration.withName("second", versions)
				.jsonObjectArguments().handler((request, arguments, features) -> {
					secondEntered.countDown();
					try { releaseSecond.await(); }
					catch (InterruptedException ignored) { /* The second request reaches its own deadline. */ }
					return McpCompleteResult.fromToolText("second finished");
				}).build();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("interrupt-isolation", "1").build(), versions)
				.toolRegistrations(List.of(first, second)).build();
		McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.requestHandlerConcurrency(2).requestTimeout(Duration.ofMillis(200))
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed()).build();
		// This test must observe an immutable incomplete result while the close
		// callback is retained, then release all physical work before leaving.
		try (SokletTestScope scope = SokletTestScope.of(Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
						.startupCancelationTimeout(Duration.ofMillis(100))
						.gracefulShutdownTimeout(Duration.ofMillis(100))
						.forcedShutdownTimeout(Duration.ofMillis(100)).build()).build()));
				HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
			Soklet owner = scope.soklet();
			ownerReference.set(owner);
			owner.start();
			int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
			try {
				var one = client.sendAsync(request(port, "first"), HttpResponse.BodyHandlers.ofString());
				assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
				assertTrue(closeEntered.await(3, TimeUnit.SECONDS));
				assertTrue(marked.get(), "Channel close must carry the lifecycle owner scope.");
				assertInstanceOf(IllegalStateException.class, selfWaitFailure.get());
				assertTimeoutResponse(one.get(2, TimeUnit.SECONDS), "first");
				var two = client.sendAsync(request(port, "second"), HttpResponse.BodyHandlers.ofString());
				assertTrue(secondEntered.await(3, TimeUnit.SECONDS));
				assertTimeoutResponse(two.get(2, TimeUnit.SECONDS), "second");
				// Channel interruption can finish waking a virtual reader only
				// after its close returns. Release the read independently while
				// keeping the close callback, delivery and dispatcher slot held.
				releaseRead.countDown();
				awaitActiveSlots(server, 1);
				assertEquals(1L, releaseClose.getCount(), "The close callback is still physically blocked.");
				owner.requestShutdown();
				ExecutionException asynchronousFailure = assertThrows(ExecutionException.class,
						() -> owner.awaitShutdownAsync().toCompletableFuture().get(3, TimeUnit.SECONDS));
				ShutdownResult incomplete = assertInstanceOf(SokletShutdownIncompleteException.class,
						asynchronousFailure.getCause()).getShutdownResult();
				frozenResult.set(incomplete);
				assertFalse(incomplete.isComplete(), "A blocked interrupt delivery must retain shutdown evidence.");
				releaseClose.countDown();
				releaseRead.countDown();
				releaseSecond.countDown();
				assertTrue(firstReturned.await(3, TimeUnit.SECONDS));
				awaitActiveSlots(server, 0);
				assertSame(incomplete, assertThrows(SokletShutdownIncompleteException.class,
						owner::awaitShutdown).getShutdownResult());
				try (ServerSocket rebound = new ServerSocket()) {
					rebound.setReuseAddress(true);
					rebound.bind(new InetSocketAddress("127.0.0.1", port));
				}
			} finally {
				releaseClose.countDown();
				releaseRead.countDown();
				releaseSecond.countDown();
			}
		} catch (SokletShutdownIncompleteException expectedAtScopeClose) {
			assertSame(frozenResult.get(), expectedAtScopeClose.getShutdownResult());
		}
	}

	private static HttpRequest request(int port, String name) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + name + "\",\"method\":\"tools/call\",\"params\":{"
				+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},\"name\":\"" + name + "\",\"arguments\":{}}}";
		return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
				.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2026-07-28")
				.header("Mcp-Method", "tools/call").header("Mcp-Name", name)
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
	}

	private static void assertTimeoutResponse(HttpResponse<String> response, String id) {
		assertEquals(200, response.statusCode(), response.body());
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}", response.body());
	}

	private static void awaitActiveSlots(McpServer server, int expected) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (server.getDiagnostics().getActiveHandlerExecutions() != expected && System.nanoTime() - deadline < 0)
			Thread.sleep(5L);
		assertEquals(expected, server.getDiagnostics().getActiveHandlerExecutions());
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try { assertTrue(latch.await(5, TimeUnit.SECONDS)); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private abstract static class AbstractInterruptibleReadableChannel extends AbstractInterruptibleChannel
			implements ReadableByteChannel {}
}
