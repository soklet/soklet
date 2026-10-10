package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Linux delayed ACKs must not hold small response bodies behind their separately written heads. */
@Timeout(60)
@EnabledOnOs(OS.LINUX)
class KeepAliveResponseLatencyRuntimeTests {
    private static final int WARMUP_REQUESTS = 10;
    private static final int MEASURED_REQUESTS = 50;
    private static final long MEDIAN_LIMIT_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    @Test
    void smallHttpResponsesAvoidDelayedAckStallsOnAReusedConnection() throws Exception {
        HttpServer server = HttpServer.withPort(0).host("127.0.0.1")
                .requestHeaderTimeout(Duration.ofSeconds(5)).requestBodyTimeout(Duration.ofSeconds(5))
                .responseWriteIdleTimeout(Duration.ofSeconds(5)).build();
        SokletConfig config = SokletConfig.withHttpServer(server)
                .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SmallResource.class)))
                .lifecyclePolicy(lifecyclePolicy()).build();
        try (Soklet soklet = Soklet.fromConfig(config)) {
            soklet.start();
            int port = ((DefaultHttpServer) server).getEventLoop().orElseThrow().getPort();
            try (PersistentClient client = new PersistentClient(port)) {
                byte[] request = "GET /small HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII);
                assertLowMedianLatency(client, request, SmallResource.BODY, "HTTP");
            }
        }
    }

    @Test
    void smallMcpCatalogResponsesAvoidDelayedAckStallsOnAReusedConnection() throws Exception {
        Set<McpProtocolVersion> versions = Set.of(McpProtocolVersion.V2026_07_28);
        McpToolRegistration<McpJsonObject> tool = McpToolRegistration.withName("probe", versions)
                .jsonObjectArguments().handler((requestContext, arguments, invocationFeatures) ->
                        McpCompleteResult.fromToolText("ok")).build();
        McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
                McpImplementation.withNameAndVersion("keep-alive-latency", "1").build(), versions)
                .toolRegistrations(List.of(tool)).build();
        McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
                .endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
                .admissionController(admissionContext -> McpAdmissionDecision.accepted())
                .toolRateLimiter(rateLimitContext -> McpRateLimitDecision.allowed()).build();
        SokletConfig config = SokletConfig.withMcpServer(server)
                .resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
                .lifecyclePolicy(lifecyclePolicy()).build();
        try (Soklet soklet = Soklet.fromConfig(config)) {
            soklet.start();
            int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
            try (PersistentClient client = new PersistentClient(port)) {
                String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{"
                        + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                        + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
                byte[] request = ("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                        + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                        + "MCP-Protocol-Version: 2026-07-28\r\nMcp-Method: tools/list\r\n"
                        + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body)
                        .getBytes(StandardCharsets.UTF_8);
                assertLowMedianLatency(client, request, "\"name\":\"probe\"", "MCP tools/list");
            }
        }
    }

    private static LifecyclePolicy lifecyclePolicy() {
        return LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(10))
                .startupCancelationTimeout(Duration.ofSeconds(1))
                .gracefulShutdownTimeout(Duration.ofSeconds(1))
                .forcedShutdownTimeout(Duration.ofSeconds(1)).build();
    }

    private static void assertLowMedianLatency(PersistentClient client, byte[] request,
                                              String expectedContent, String operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long[] samples = new long[MEASURED_REQUESTS];
        for (int index = 0; index < WARMUP_REQUESTS + MEASURED_REQUESTS; index++) {
            long started = System.nanoTime();
            String body = client.exchange(request, deadline);
            long elapsed = System.nanoTime() - started;
            assertTrue(body.contains(expectedContent), operation + " returned an unexpected response body");
            if (index >= WARMUP_REQUESTS) samples[index - WARMUP_REQUESTS] = elapsed;
        }
        Arrays.sort(samples);
        long median = samples[samples.length / 2];
        assertTrue(median < MEDIAN_LIMIT_NANOS, () -> operation + " median keep-alive latency was "
                + median / 1_000_000.0 + " ms for " + MEASURED_REQUESTS + " warmed sequential requests; "
                + "small bodies must not repeatedly wait for Linux's delayed ACK");
    }

    public static final class SmallResource {
        private static final String BODY = "x".repeat(1024);
        @GET("/small") public MarshaledResponse small() {
            return MarshaledResponse.withStatusCode(200).body(BODY.getBytes(StandardCharsets.US_ASCII)).build();
        }
    }

    private static final class PersistentClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream input;

        private PersistentClient(int port) throws IOException {
            socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
                socket.setTcpNoDelay(true);
                input = new BufferedInputStream(socket.getInputStream());
            } catch (IOException | RuntimeException | Error failure) {
                try { socket.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }

        private String exchange(byte[] request, long deadline) throws Exception {
            setReadDeadline(deadline);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
            int suffix = 0;
            while (suffix != 0x0d0a0d0a) {
                assertTrue(headBytes.size() < 16_384, "Response head exceeded the fixture byte bound");
                setReadDeadline(deadline);
                int value = input.read();
                assertTrue(value >= 0, "Keep-alive connection closed before its response head completed");
                headBytes.write(value);
                suffix = (suffix << 8) | value;
            }
            String head = headBytes.toString(StandardCharsets.ISO_8859_1);
            assertTrue(head.startsWith("HTTP/1.1 200"), head);
            assertFalse(head.toLowerCase(Locale.ROOT).contains("connection: close"), head);
            int length = -1;
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                    length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
            assertTrue(length > 0 && length <= 16_384, "Expected a bounded small finite response: " + head);
            byte[] body = new byte[length];
            int offset = 0;
            while (offset < body.length) {
                setReadDeadline(deadline);
                int count = input.read(body, offset, body.length - offset);
                assertTrue(count > 0, "Keep-alive connection closed before its response body completed");
                offset += count;
            }
            return new String(body, StandardCharsets.UTF_8);
        }

        private void setReadDeadline(long deadline) throws IOException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Keep-alive sample deadline elapsed");
            socket.setSoTimeout((int) Math.max(1, Math.min(3_000, (remaining + 999_999) / 1_000_000)));
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
