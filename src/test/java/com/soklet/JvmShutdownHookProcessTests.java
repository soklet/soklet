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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real SIGTERM ownership and cleanup across all three built-in listeners. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class JvmShutdownHookProcessTests {
    @TempDir
    Path directory;

    @Test
    void defaultJvmHookDrainsEveryListenerAndRunsCleanup() throws Exception {
        verifySignalShutdown("default");
    }

    @Test
    void explicitlyEnabledJvmHookRetainsDefaultBehavior() throws Exception {
        verifySignalShutdown("enabled");
    }

    @Test
    void hostOwnedHookKeepsEveryListenerAvailableUntilHostRequestsShutdown() throws Exception {
        verifySignalShutdown("host");
    }

    private void verifySignalShutdown(String mode) throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
                "Process.destroy delivers SIGTERM on the supported Unix test hosts");
        int httpPort;
        int ssePort;
        int mcpPort;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket http = new ServerSocket(0, 0, loopback);
             ServerSocket sse = new ServerSocket(0, 0, loopback);
             ServerSocket mcp = new ServerSocket(0, 0, loopback)) {
            httpPort = http.getLocalPort();
            ssePort = sse.getLocalPort();
            mcpPort = mcp.getLocalPort();
        }
        Path ready = this.directory.resolve(mode + "-ready");
        Path entered = this.directory.resolve(mode + "-host-entered");
        Path release = this.directory.resolve(mode + "-host-release");
        Path cleaned = this.directory.resolve(mode + "-cleaned");
        Path finished = this.directory.resolve(mode + "-host-finished");
        Path output = this.directory.resolve(mode + ".log");
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Child.class.getName(), mode, Integer.toString(httpPort), Integer.toString(ssePort),
                Integer.toString(mcpPort), ready.toString(), entered.toString(), release.toString(),
                cleaned.toString(), finished.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            awaitMarker(child, ready, output);
            assertHttpReady(httpPort);
            assertMcpReady(mcpPort);
            try (Socket stream = openSse(ssePort)) {
                child.destroy();
                if (mode.equals("host")) {
                    awaitMarker(child, entered, output);
                    assertTrue(child.isAlive(), () -> childOutput(output));
                    assertFalse(Files.exists(cleaned), "Cleanup must wait for the host's shutdown request");
                    assertHttpReady(httpPort);
                    assertMcpReady(mcpPort);
                    // New SSE connections must also remain admissible during the host's delay.
                    try (Socket additionalStream = openSse(ssePort)) {
                        assertTrue(additionalStream.isConnected());
                    }
                    Files.writeString(release, "continue");
                }
                assertTrue(child.waitFor(15, TimeUnit.SECONDS), () -> childOutput(output));
                assertEquals(143, child.exitValue(), () -> childOutput(output));
                assertTrue(stream.getInputStream().readAllBytes().length < 65536);
            }
            assertEquals("complete:HTTP,SSE,MCP", Files.readString(cleaned));
            assertEquals(mode.equals("host"), Files.exists(finished));
            for (int port : new int[]{httpPort, ssePort, mcpPort})
                assertListenerClosed(port);
            assertFalse(childOutput(output).contains("soklet-terminal-report"), () -> childOutput(output));
        } finally {
            Files.writeString(release, "continue");
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void assertHttpReady(int port) throws Exception {
        String response = exchange(port, "GET /ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 "), response);
        assertTrue(response.endsWith("ready"), response);
    }

    private static void assertMcpReady(int port) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ready\",\"arguments\":{},\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
        String response = exchange(port, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n"
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "MCP-Protocol-Version: 2026-07-28\r\nMcp-Method: tools/call\r\nMcp-Name: ready\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body);
        assertTrue(response.startsWith("HTTP/1.1 200 "), response);
        assertTrue(response.contains("\"text\":\"ready\""), response);
    }

    private static Socket openSse(int port) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /events HTTP/1.1\r\nHost: localhost\r\n"
                    + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            while (!headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                int value = socket.getInputStream().read();
                assertTrue(value >= 0, headers::toString);
                headers.write(value);
                assertTrue(headers.size() < 16384);
            }
            assertTrue(headers.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200 "), headers::toString);
            return socket;
        } catch (Exception | AssertionError failure) {
            socket.close();
            throw failure;
        }
    }

    private static String exchange(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertListenerClosed(int port) {
        assertThrows(java.io.IOException.class, () -> {
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                fail("Listener still accepts connections on " + port);
            }
        });
    }

    private static void awaitMarker(Process child, Path marker, Path output) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(marker) && child.isAlive() && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertTrue(Files.exists(marker), () -> childOutput(output));
    }

    private static String childOutput(Path output) {
        try {
            return Files.readString(output);
        } catch (java.io.IOException failure) {
            return failure.toString();
        }
    }

    public static final class Resource {
        @GET("/ready")
        public String ready() { return "ready"; }

        @SseEventSource("/events")
        public SseHandshakeResult events() {
            return SseHandshakeResult.Accepted.builder().clientInitializer(client ->
                    client.unicastEvent(SseEvent.withEvent("ready").data("ready").build())).build();
        }
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            Path ready = Path.of(args[4]);
            Path entered = Path.of(args[5]);
            Path release = Path.of(args[6]);
            Path cleaned = Path.of(args[7]);
            Path finished = Path.of(args[8]);
            AtomicReference<Soklet> lifecycle = new AtomicReference<>();
            if (mode.equals("host"))
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Files.writeString(entered, "host owns shutdown");
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        while (!Files.exists(release) && System.nanoTime() < deadline)
                            Thread.sleep(10);
                        if (!Files.exists(release)) throw new IllegalStateException("Host release was not received");
                        Soklet soklet = lifecycle.get();
                        soklet.requestShutdown();
                        if (!soklet.awaitShutdown().isComplete()) throw new IllegalStateException("Incomplete host shutdown");
                        Files.writeString(finished, "complete");
                    } catch (Exception failure) {
                        throw new IllegalStateException("Host shutdown failed", failure);
                    }
                }, "test-host-shutdown"));
            McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
                    McpImplementation.withNameAndVersion("hook-test", "1").build(),
                    Set.of(McpProtocolVersion.V2026_07_28))
                    .toolRegistrations(java.util.List.of(McpToolRegistration.withName("ready", Set.of(McpProtocolVersion.V2026_07_28))
                            .jsonObjectArguments().handler((request, arguments, features) -> McpCompleteResult.fromToolText("ready")).build()))
                    .build();
            SokletConfig.Builder builder = SokletConfig.withHttpServer(
                    HttpServer.withPort(Integer.valueOf(args[1])).host("127.0.0.1").build())
                    .sseServer(SseServer.withPort(Integer.valueOf(args[2])).host("127.0.0.1").build())
                    .mcpServer(McpServer.withPort(Integer.valueOf(args[3])).host("127.0.0.1")
                            .endpointRegistry(McpEndpointRegistry.fromEndpoints(Set.of(endpoint))).build())
                    .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
                    .lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
                            .startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(2))
                            .forcedShutdownTimeout(Duration.ofSeconds(1)).build());
            if (mode.equals("host")) builder.jvmShutdownHookEnabled(Boolean.FALSE);
            if (mode.equals("enabled")) builder.jvmShutdownHookEnabled(Boolean.TRUE);
            Soklet soklet = Soklet.run(builder.build(), ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(2), result -> {
                Set<ShutdownComponentType> components = result.getShutdownComponentResults().stream()
                        .map(ShutdownComponentResult::getShutdownComponentType).collect(java.util.stream.Collectors.toSet());
                if (!result.isComplete() || !components.equals(Set.of(ShutdownComponentType.HTTP, ShutdownComponentType.SSE, ShutdownComponentType.MCP)))
                    throw new IllegalStateException("Cleanup requires complete shutdown of every listener");
                Files.writeString(cleaned, "complete:HTTP,SSE,MCP");
            }));
            lifecycle.set(soklet);
            Files.writeString(ready, "ready");
            soklet.awaitShutdown();
        }
    }
}
