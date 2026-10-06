package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.POST;
import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FramingTransportTests {
    private static final AtomicInteger INVOCATIONS = new AtomicInteger();

    @Test
    void faultyHttpFramingClosesBeforeAnyHandlerOrFollowingPipelineRequestRuns() throws Exception {
        int port = TestSupport.findFreePort();
        INVOCATIONS.set(0);
        try (Soklet soklet = Soklet.fromConfig(config(port, null))) {
            soklet.start();
            List<String> malformed = List.of(
                    "POST /framing HTTP/1.0\r\nHost: a\r\nConnection: keep-alive\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                    chunked("1;name=a\rb\r\nx\r\n0\r\n\r\n"),
                    chunked("1;name=a\nb\r\nx\r\n0\r\n\r\n"),
                    chunked("1;name=\u0000\r\nx\r\n0\r\n\r\n"),
                    chunked("1;name=" + "a".repeat(8192) + "\r\nx\r\n0\r\n\r\n"),
                    "A".repeat(65) + " /framing HTTP/1.1\r\nHost: a\r\n\r\n",
                    "GET /following HTTP/1.1\r\nHost: a\r\nX-Note: a\rX-Authenticated-User: admin\r\n\r\n");
            for (String request : malformed) {
                String wire = exchange(port, request + "GET /following HTTP/1.1\r\nHost: secret\r\n\r\n");
                assertTrue(wire.startsWith("HTTP/1.1 400"), wire);
                assertEquals(List.of("close"), headers(wire).get("Connection"));
                assertEquals(1, wire.split("HTTP/1.1", -1).length - 1, wire);
                assertEquals(0, INVOCATIONS.get());
            }
        }
    }

    @Test
    void validChunkExtensionsAndHttp10ContentLengthReachTheHandler() throws Exception {
        int port = TestSupport.findFreePort();
        INVOCATIONS.set(0);
        try (Soklet soklet = Soklet.fromConfig(config(port, null))) {
            soklet.start();
            for (String request : List.of(chunked("1;name=\"quoted; value\\\"\"\r\nx\r\n0;done\r\n\r\n"),
                    "POST /framing HTTP/1.0\r\nHost: a\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx")) {
                String wire = exchange(port, request);
                assertTrue(wire.startsWith("HTTP/1.1 200") || wire.startsWith("HTTP/1.0 200"), wire);
                assertEquals("x", wire.substring(wire.indexOf("\r\n\r\n") + 4));
            }
        }
        assertEquals(2, INVOCATIONS.get());
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void sseRejectsBareCarriageReturnsAndReframesApplicationRejections() throws Exception {
        int httpPort = TestSupport.findFreePort();
        int ssePort = TestSupport.findFreePort();
        INVOCATIONS.set(0);
        try (Soklet soklet = Soklet.fromConfig(config(httpPort, ssePort))) {
            soklet.start();
            String malformed = exchange(ssePort, "GET /events HTTP/1.1\r\nHost: a\r\n"
                    + "X-Note: a\rX-Authenticated-User: admin\r\n\r\n");
            assertTrue(malformed.startsWith("HTTP/1.1 400"), malformed);
            assertEquals(List.of("close"), headers(malformed).get("Connection"));
            assertEquals(0, INVOCATIONS.get());

            String wire = exchange(ssePort, "GET /events HTTP/1.1\r\nHost: a\r\n\r\n");
            assertTrue(wire.startsWith("HTTP/1.1 403"), wire);
            Map<String, List<String>> headers = headers(wire);
            assertEquals(List.of("close"), headers.get("Connection"), wire);
            assertEquals(List.of("6"), headers.get("Content-Length"), wire);
            for (String name : List.of("Transfer-Encoding", "Keep-Alive", "Trailer", "TE", "Upgrade", "Proxy-Connection", "X-Hop"))
                assertFalse(headers.containsKey(name), wire);
            assertEquals(List.of("Bearer realm=\"first\"", "Bearer realm=\"second\""), headers.get("WWW-Authenticate"));
            assertEquals("denied", wire.substring(wire.indexOf("\r\n\r\n") + 4));
            assertEquals(1, INVOCATIONS.get());
        }
    }

    private static SokletConfig config(int httpPort, Integer ssePort) {
        SokletConfig.Builder builder = SokletConfig.withHttpServer(HttpServer.withPort(httpPort).concurrency(1).build())
                .resourceMethodResolver(ResourceMethodResolver.fromClasses(ssePort == null
                        ? Set.of(FramingResource.class) : Set.of(FramingResource.class, SseFramingResource.class)))
                .lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
                        .startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(1))
                        .forcedShutdownTimeout(Duration.ofSeconds(1)).build())
                .lifecycleObserver(new LifecycleObserver() {
                    @Override public void didReceiveLogEvent(@NonNull LogEvent logEvent) {}
                });
        if (ssePort != null)
            builder.sseServer(SseServer.withPort(ssePort).host("127.0.0.1").build());
        return builder.build();
    }

    @Test
    void absoluteAuthorityAndEmptyNamesReachLiveHttpHandlers() throws Exception {
        int httpPort = TestSupport.findFreePort();
        try (Soklet soklet = Soklet.fromConfig(config(httpPort, null))) {
            soklet.start();
            String wire = exchange(httpPort, "GET https://target.example:8443/authority?=value HTTP/1.1\r\nHost: other.example\r\nConnection: close\r\n\r\n");
            assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
            assertEquals("https://target.example:8443|value", wire.substring(wire.indexOf("\r\n\r\n") + 4));
            for (String host : List.of("", "Host: a\r\nHost: b\r\n", "Host: bad host\r\n")) {
                String rejected = exchange(httpPort, "GET https://target.example/authority HTTP/1.1\r\n" + host + "Connection: close\r\n\r\n");
                assertTrue(rejected.startsWith("HTTP/1.1 400"), rejected);
            }
        }
    }

    private static String chunked(String body) {
        return "POST /framing HTTP/1.1\r\nHost: a\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n" + body;
    }

    private static String exchange(int port, String request) throws Exception {
        try (Socket socket = TestSupport.connectWithRetry("127.0.0.1", port, 2000)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readNBytes(32 * 1024), StandardCharsets.ISO_8859_1);
        }
    }

    private static Map<String, List<String>> headers(String wire) {
        return Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(wire.substring(0, wire.indexOf("\r\n\r\n")).split("\r\n")));
    }

    public static class FramingResource {
        @GET("/authority")
        public String authority(Request request) {
            return EffectiveOriginResolver.withRequest(request, EffectiveOriginResolver.TrustPolicy.TRUST_NONE).resolve().orElseThrow()
                    + "|" + request.getQueryParameter("").orElse("");
        }
        @POST("/framing")
        public String body(Request request) {
            INVOCATIONS.incrementAndGet();
            return request.getBodyAsString().orElse("");
        }

        @GET("/following")
        public String following() {
            INVOCATIONS.incrementAndGet();
            return "following";
        }
    }

    public static class SseFramingResource {
        @SseEventSource("/events")
        public SseHandshakeResult events() {
            INVOCATIONS.incrementAndGet();
            return SseHandshakeResult.rejectWithResponse(Response.withStatusCode(403).body("denied")
                    .headers(Map.of("Connection", List.of("keep-alive, X-Hop"), "Content-Length", List.of("999"),
                            "Transfer-Encoding", List.of("chunked"), "Keep-Alive", List.of("timeout=60"),
                            "TE", List.of("trailers"), "Trailer", List.of("X-End"), "Upgrade", List.of("websocket"),
                            "Proxy-Connection", List.of("keep-alive"), "X-Hop", List.of("remove"),
                            "WWW-Authenticate", List.of("Bearer realm=\"first\"", "Bearer realm=\"second\""))).build());
        }
    }
}
