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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(25)
public class TransportProcessOwnershipTests {
	@Test
	void httpKeepsProcessAliveAfterMainReturnsAndReleasesItOnClose(@TempDir Path directory) throws Exception {
		assertDirectLiveness("HTTP", directory, List.of("event-loop", "connection-event-loop"));
	}

	@Test
	void mcpKeepsProcessAliveAfterMainReturnsAndReleasesItOnClose(@TempDir Path directory) throws Exception {
		assertDirectLiveness("MCP", directory, List.of("event-loop", "connection-event-loop"));
	}

	@Test
	void sseKeepsProcessAliveAfterMainReturnsAndReleasesItOnClose(@TempDir Path directory) throws Exception {
		assumeTrue(Runtime.version().feature() >= 21, "SSE requires virtual threads");
		assertDirectLiveness("SSE", directory, List.of("sse-event-loop"));
	}

	@Test
	void aggregateHttpAndSseReleasesEveryListenerOnClose(@TempDir Path directory) throws Exception {
		assumeTrue(Runtime.version().feature() >= 21, "SSE requires virtual threads");
		assertDirectLiveness("BOTH", directory, List.of("event-loop", "connection-event-loop", "sse-event-loop"));
	}

	@Test
	void standaloneHookJoinsCoreBeforeRunningCleanupOnSigterm(@TempDir Path directory) throws Exception {
		assumeUnixSignal();
		try (Child child = start("RUNNER", directory)) {
			awaitMarker(child, "ready");
			child.process.destroy();
			assertTrue(child.process.waitFor(5, TimeUnit.SECONDS), child.log());
			assertEquals("true", Files.readString(directory.resolve("cleanup")), child.log());
		}
	}

	@Test
	void manuallyOwnedBlockingCloseHookCompletesCoreOnSigterm(@TempDir Path directory) throws Exception {
		assumeUnixSignal();
		try (Child child = start("MANUAL_HOOK", directory)) {
			awaitMarker(child, "ready");
			child.process.destroy();
			assertTrue(child.process.waitFor(5, TimeUnit.SECONDS), child.log());
			assertEquals("true", Files.readString(directory.resolve("closed")), child.log());
		}
	}

	@Test
	void enterKeyEofDoesNotStopTheRunnerOrDisableItsSignalHook(@TempDir Path directory) throws Exception {
		assumeUnixSignal();
		try (Child child = start("RUNNER_EOF", directory)) {
			awaitMarker(child, "ready");
			child.process.getOutputStream().close();
			awaitMarker(child, "unsupported");
			assertFalse(child.process.waitFor(200, TimeUnit.MILLISECONDS), child.log());
			child.process.destroy();
			assertTrue(child.process.waitFor(5, TimeUnit.SECONDS), child.log());
			assertEquals("true", Files.readString(directory.resolve("cleanup")), child.log());
		}
	}

	@Test
	void enterKeyRunLeavesTheFollowingLineAvailable(@TempDir Path directory) throws Exception {
		assertInputHandoff("RUNNER_INPUT_LINE", directory);
	}

	@Test
	void interruptedRunRetiresThePendingByteWithoutDrainingTheFollowingLine(@TempDir Path directory) throws Exception {
		assertInputHandoff("RUNNER_INPUT_INTERRUPT", directory);
	}

	private static void assertInputHandoff(String mode, Path directory) throws Exception {
		try (Child child = start(mode, directory)) {
			awaitMarker(child, "ready");
			awaitMarker(child, "reading");
			if (mode.endsWith("INTERRUPT")) {
				Files.writeString(directory.resolve("interrupt"), "interrupt");
				awaitMarker(child, "run-ended");
			}
			child.process.getOutputStream().write(((mode.endsWith("INTERRUPT") ? "X" : "\n") + "follow-up\n")
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			child.process.getOutputStream().close();
			assertTrue(child.process.waitFor(5, TimeUnit.SECONDS), child.log());
			assertEquals(0, child.process.exitValue(), child.log());
			assertEquals("follow-up", Files.readString(directory.resolve("following-line")), child.log());
			assertEquals("true", Files.readString(directory.resolve("cleanup")), child.log());
		}
	}

	@Test
	void httpAuxiliaryThreadsRemainDaemonRegardlessOfTheirCaller() {
		Thread thread = new DefaultHttpServer.NonvirtualThreadFactory("test-http-worker").newThread(() -> {});
		assertTrue(thread.isDaemon(), "Application and timeout workers must not inherit listener liveness");
	}

	private static void assertDirectLiveness(String mode, Path directory, List<String> listeners) throws Exception {
		try (Child child = start(mode, directory)) {
			awaitMarker(child, "ready");
			assertFalse(child.process.waitFor(300, TimeUnit.MILLISECONDS), "Main return must leave a running listener alive: " + child.log());
			String snapshot = Files.readString(directory.resolve("ready"));
			for (String listener : listeners)
				assertTrue(snapshot.contains(listener + "=false\n"), snapshot);
			Files.writeString(directory.resolve("close"), "close");
			assertTrue(child.process.waitFor(5, TimeUnit.SECONDS), "Complete close must release process liveness: " + child.log());
			assertEquals(0, child.process.exitValue(), child.log());
			assertEquals("true", Files.readString(directory.resolve("closed")), child.log());
		}
	}

	private static Child start(String mode, Path directory) throws Exception {
		Path log = directory.resolve("process.log");
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp", System.getProperty("java.class.path"),
				Fixture.class.getName(), mode, directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
		return new Child(process, directory, log);
	}

	private static void awaitMarker(Child child, String name) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!Files.exists(child.directory.resolve(name)) && child.process.isAlive() && System.nanoTime() < deadline)
			Thread.sleep(10);
		assertTrue(Files.exists(child.directory.resolve(name)), "Missing " + name + ": " + child.log());
	}

	private static void assumeUnixSignal() {
		assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Process.destroy must deliver SIGTERM for this fixture");
	}

	private record Child(Process process, Path directory, Path output) implements AutoCloseable {
		String log() throws Exception { return Files.readString(output); }
		@Override public void close() throws Exception {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Fixture process must be reaped");
			}
		}
	}

	public static final class Fixture {
		public static void main(String[] args) throws Exception {
			String mode = args[0];
			Path directory = Path.of(args[1]);
			if (mode.startsWith("RUNNER_INPUT")) {
				System.setIn(new java.io.FilterInputStream(System.in) {
					private final java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
					private void recordRead() { if (this.first.compareAndSet(true, false)) write(directory, "reading", "true"); }
					@Override public int read() throws java.io.IOException { recordRead(); return super.read(); }
					@Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
						recordRead(); return super.read(bytes, offset, length);
					}
				});
			}
			LifecycleObserver observer = new LifecycleObserver() {
				@Override public void didStartSoklet(Soklet soklet) {
					if (mode.startsWith("RUNNER")) write(directory, "ready", snapshot());
				}
				@Override public void didReceiveLogEvent(LogEvent event) {
					if (event.getLogEventType() == LogEventType.CONFIGURATION_UNSUPPORTED)
						write(directory, "unsupported", "true");
				}
			};
			SokletConfig.Builder builder = mode.equals("SSE")
					? SokletConfig.withSseServer(SseServer.withPort(0).host("127.0.0.1").build())
					: SokletConfig.withHttpServer(HttpServer.withPort(0).host("127.0.0.1").concurrency(1).build());
			if (mode.equals("BOTH")) builder.sseServer(SseServer.withPort(0).host("127.0.0.1").build());
			if (mode.equals("MCP")) {
				McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("liveness-fixture", "1").build(),
						java.util.Set.of(McpProtocolVersion.V2026_07_28)).build();
				builder = SokletConfig.withMcpServer(McpServer.withPort(0).host("127.0.0.1")
						.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).build());
			}
			var methods = new java.util.HashSet<java.lang.reflect.Method>();
			if (!mode.equals("SSE") && !mode.equals("MCP")) methods.add(Routes.class.getMethod("ready"));
			if (mode.equals("SSE") || mode.equals("BOTH")) methods.add(Routes.class.getMethod("events"));
			SokletConfig config = builder.resourceMethodResolver(ResourceMethodResolver.fromMethods(methods))
					.lifecycleObserver(observer).lifecyclePolicy(LifecyclePolicy.builder()
							.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build();
			if (mode.startsWith("RUNNER")) {
				ShutdownCleanup cleanup = ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(1),
						result -> write(directory, "cleanup", result.isComplete().toString()));
				if (mode.startsWith("RUNNER_INPUT")) {
					Thread runner = Thread.currentThread();
					if (mode.endsWith("INTERRUPT")) {
						Thread command = new Thread(() -> {
							try {
								while (!Files.exists(directory.resolve("interrupt"))) Thread.sleep(10);
								runner.interrupt();
							} catch (InterruptedException failure) { throw new RuntimeException(failure); }
						}, "input-handoff-interrupt-command");
						command.setDaemon(true); command.start();
					}
					SokletApplication.fromConfig(config).run(cleanup, ShutdownTrigger.ENTER_KEY);
					Thread.interrupted(); // The following application code owns its own wait.
					write(directory, "run-ended", "true");
					while (SokletApplication.SYSTEM_INPUT.isListenerStarted()) Thread.sleep(10);
					String line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in,
							java.nio.charset.StandardCharsets.UTF_8)).readLine();
					write(directory, "following-line", String.valueOf(line));
				} else if (mode.equals("RUNNER_EOF")) SokletApplication.fromConfig(config).run(cleanup, ShutdownTrigger.ENTER_KEY);
				else SokletApplication.fromConfig(config).run(cleanup);
				return;
			}
			Soklet soklet = Soklet.fromConfig(config);
			if (mode.equals("MANUAL_HOOK")) {
				Runtime.getRuntime().addShutdownHook(new Thread(() -> {
					soklet.close();
					write(directory, "closed", "true");
				}, "test-blocking-close-hook"));
				soklet.start();
				write(directory, "ready", snapshot());
				soklet.awaitShutdown();
				return;
			}
			soklet.start();
			Thread closer = new Thread(() -> {
				try {
					while (!Files.exists(directory.resolve("close"))) Thread.sleep(10);
					Thread closeOwner = new Thread(() -> {
						soklet.close();
						write(directory, "closed", "true");
					}, "test-close-owner");
					closeOwner.setDaemon(false);
					closeOwner.start();
				} catch (Exception failure) { throw new RuntimeException(failure); }
			}, "test-daemon-close-command");
			closer.setDaemon(true);
			closer.start();
			write(directory, "ready", snapshot());
			// Return main: only the configured listener may retain process liveness.
		}

		private static String snapshot() {
			StringBuilder snapshot = new StringBuilder();
			for (Thread thread : Thread.getAllStackTraces().keySet())
				if (List.of("event-loop", "connection-event-loop", "sse-event-loop").contains(thread.getName()))
					snapshot.append(thread.getName()).append('=').append(thread.isDaemon()).append('\n');
			return snapshot.toString();
		}

		private static void write(Path directory, String name, String value) {
			try { Files.writeString(directory.resolve(name), value); }
			catch (Exception failure) { throw new RuntimeException(failure); }
		}
	}

	public static final class Routes {
		@GET("/ready") public String ready() { return "ready"; }
		@SseEventSource("/ready") public SseHandshakeResult events() { return SseHandshakeResult.accept(); }
	}
}
