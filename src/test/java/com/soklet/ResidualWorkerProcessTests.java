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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A real incomplete runner returns while blocked framework work remains alive. */
@Timeout(60)
class ResidualWorkerProcessTests {
	@Test void blockedStreamingTerminationObserverDoesNotKeepProcessAlive(@TempDir Path directory) throws Exception {
		assertResidualExit("STREAM_TERMINATION", directory);
	}
	@Test void blockedStreamingCancelCallbackDoesNotKeepProcessAlive(@TempDir Path directory) throws Exception {
		assertResidualExit("STREAM_CANCEL", directory);
	}
	@Test void blockedStreamingDiagnosticDoesNotKeepProcessAlive(@TempDir Path directory) throws Exception {
		assertResidualExit("STREAM_DIAGNOSTIC", directory);
	}
	@Test void blockedMcpHandlerDoesNotKeepProcessAlive(@TempDir Path directory) throws Exception {
		assertResidualExit("MCP_HANDLER", directory);
	}
	@Test void blockedMcpCancelCallbackDoesNotKeepProcessAlive(@TempDir Path directory) throws Exception {
		assertResidualExit("MCP_CANCEL", directory);
	}

	private static void assertResidualExit(String mode, Path directory) throws Exception {
		Path log = directory.resolve("process.log");
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp", System.getProperty("java.class.path"),
				Fixture.class.getName(), mode, directory.toString())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!Files.exists(directory.resolve("incomplete")) && process.isAlive() && System.nanoTime() < deadline)
				Thread.sleep(10);
			assertTrue(Files.exists(directory.resolve("incomplete")), () -> read(log));
			assertEquals("INCOMPLETE", Files.readString(directory.resolve("incomplete")), () -> read(log));
			assertFalse(Files.exists(directory.resolve("cleanup")), "Incomplete core must still skip cleanup");
			assertTrue(process.waitFor(3, TimeUnit.SECONDS), "Residual framework work kept process alive: " + read(log));
			assertEquals(0, process.exitValue(), () -> read(log));
			assertEquals("true", Files.readString(directory.resolve("blocked-daemon")));
			String workers = Files.readString(directory.resolve("workers"));
			assertFalse(workers.contains("=false"), workers);
			assertTrue(Files.readString(directory.resolve("residual")).length() > 0, "Daemon status must not erase residual evidence");
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Fixture process must be reaped");
			}
		}
	}

	private static String read(Path log) {
		try { return Files.readString(log); }
		catch (IOException exception) { return exception.toString(); }
	}

	public static final class Fixture implements LifecycleObserver {
		private final String mode;
		private final Path directory;
		private final CountDownLatch ready = new CountDownLatch(1), armed = new CountDownLatch(1);
		private final CountDownLatch neverRelease = new CountDownLatch(1);
		private final Thread runner = Thread.currentThread();
		private Fixture(String mode, Path directory) { this.mode = mode; this.directory = directory; }

		public static void main(String[] args) throws Exception {
			Fixture fixture = new Fixture(args[0], Path.of(args[1]));
			int port = TestSupport.findFreePort();
			SokletConfig.Builder builder;
			if (fixture.mode.startsWith("MCP")) {
				McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("block", Set.of(McpProtocolVersion.V2026_07_28))
						.jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) -> {
							if (fixture.mode.equals("MCP_CANCEL")) {
								CancelationToken token = invocationFeatures.getCancelationToken();
								token.onCancel(fixture::block);
								fixture.armed.countDown();
								while (!token.isCanceled()) Thread.sleep(1);
								return McpCompleteResult.fromToolText("canceled");
							}
							fixture.block();
							return McpCompleteResult.fromToolText("unreachable");
						}).build();
				McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("residual-fixture", "1").build(),
						Set.of(McpProtocolVersion.V2026_07_28)).toolRegistrations(List.of(tool)).build();
				builder = SokletConfig.withMcpServer(McpServer.withPort(port).host("127.0.0.1")
						.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
						.requestRateLimiter(context -> McpRateLimitDecision.allowed())
						.toolRateLimiter(context -> McpRateLimitDecision.allowed()).build())
						.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()));
			} else {
				builder = SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1").concurrency(1)
						.streamingLifecycleCapacity(2).streamingCallbackConcurrency(1).build())
						.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Fixture.class)))
						.instanceProvider(new InstanceProvider() {
							@Override public <T> T provide(@NonNull Class<T> type) { return type.cast(fixture); }
						});
			}
			SokletConfig config = builder.lifecycleObserver(fixture).lifecyclePolicy(LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofSeconds(1))
					.gracefulShutdownTimeout(Duration.ofMillis(100)).forcedShutdownTimeout(Duration.ofMillis(100)).build()).build();
			Thread client = new Thread(() -> fixture.drive(port), "residual-fixture-client");
			client.setDaemon(true); client.start();
			try {
				SokletApplication.fromConfig(config).run(ShutdownCleanup.fromTimeoutAndAction(Duration.ofMillis(100),
						result -> fixture.write("cleanup", "unexpected")));
				throw new AssertionError("Blocked runtime must not report complete shutdown");
			} catch (SokletShutdownIncompleteException failure) {
				ShutdownResult result = failure.getShutdownResult();
				if (result.isComplete()) throw new AssertionError("Missing physical residual proof");
				if (!Files.exists(fixture.directory.resolve("blocked-daemon"))) throw new AssertionError("The residual callback did not enter");
				ShutdownComponentType type = fixture.mode.startsWith("MCP") ? ShutdownComponentType.MCP : ShutdownComponentType.HTTP;
				var evidence = result.getShutdownComponentResult(type).orElseThrow().getResidualActivityEvidence().orElseThrow();
				fixture.write("residual", evidence.getResidualActivityTypes().toString());
				StringBuilder workers = new StringBuilder();
				for (Thread thread : Thread.getAllStackTraces().keySet()) {
					String name = thread.getName();
					if (name.startsWith("stream-") || name.startsWith("soklet-mcp-"))
						workers.append(name).append('=').append(thread.isDaemon()).append('\n');
				}
				fixture.write("workers", workers.toString());
				fixture.write("incomplete", result.getShutdownDisposition().name());
			}
			// Return main without System.exit/halt or releasing the blocked callback.
		}

		private void drive(int port) {
			try {
				if (!this.ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("Runner not ready");
				try (Socket socket = TestSupport.connectWithRetry("127.0.0.1", port, 3000)) {
					String wire = "GET /blocked HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n";
					if (this.mode.startsWith("MCP")) {
						String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"block\",\"arguments\":{},\"_meta\":{"
								+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
						wire = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + port
								+ "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nMCP-Protocol-Version: 2026-07-28"
								+ "\r\nMcp-Method: tools/call\r\nMcp-Name: block\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
								+ "\r\nConnection: close\r\n\r\n" + body;
					}
					socket.getOutputStream().write(wire.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
					if (!this.armed.await(5, TimeUnit.SECONDS)) throw new AssertionError("Residual work not entered");
					this.runner.interrupt();
					awaitIgnoringInterrupts(this.neverRelease); // Keep the client connected during shutdown.
				}
			} catch (Throwable failure) { failure.printStackTrace(); this.runner.interrupt(); }
		}

		private void block() {
			write("blocked-daemon", Boolean.toString(Thread.currentThread().isDaemon()));
			this.armed.countDown();
			awaitIgnoringInterrupts(this.neverRelease);
		}

		@GET("/blocked") public MarshaledResponse blocked() {
			return MarshaledResponse.withStatusCode(200).stream(stream -> {
				if (this.mode.equals("STREAM_CANCEL")) {
					stream.getCancelationToken().onCancel(this::block);
					throw new StreamingResponseCanceledException(StreamTerminationReason.APPLICATION_CANCELED);
				}
				if (this.mode.equals("STREAM_DIAGNOSTIC")) stream.own(() -> { throw new IOException("owned cleanup failed"); });
				stream.write("body".getBytes(StandardCharsets.UTF_8));
			}).build();
		}
		@Override public void didStartSoklet(@NonNull Soklet soklet) { this.ready.countDown(); }
		@Override public void didTerminateResponseStream(@NonNull StreamingResponseHandle handle, @NonNull StreamTermination termination) {
			if (this.mode.equals("STREAM_TERMINATION")) block();
		}
		@Override public void didReceiveLogEvent(@NonNull LogEvent event) {
			if (this.mode.equals("STREAM_DIAGNOSTIC") && event.getLogEventType() == LogEventType.RESPONSE_STREAM_CLOSE_FAILED) block();
		}
		private void write(String name, String value) {
			try { Files.writeString(this.directory.resolve(name), value); }
			catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
		}
		private static void awaitIgnoringInterrupts(CountDownLatch latch) {
			boolean interrupted = false;
			for (;;) {
				try { latch.await(); break; }
				catch (InterruptedException exception) { interrupted = true; }
			}
			if (interrupted) Thread.currentThread().interrupt();
		}
	}
}
