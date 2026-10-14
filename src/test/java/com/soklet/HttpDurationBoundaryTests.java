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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class HttpDurationBoundaryTests {
    @Test
    void submillisecondSelectorTimeoutStillExpiresAStalledRequest() throws Exception {
        DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1")
                .concurrency(1).socketSelectTimeout(Duration.ofNanos(1))
                .requestHeaderTimeout(Duration.ofMillis(100)).build();
        try (Soklet owner = owner(server)) {
            owner.start();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.getEventLoop().orElseThrow().getPort()), 3_000);
                socket.setSoTimeout(3_000);
                socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 408 "), response);
            }
        }
    }

    @Test
    void acceptedHugeReadAndWriteTimeoutsDoNotBreakOrdinaryRequests() throws Exception {
        Duration huge = Duration.ofSeconds(Long.MAX_VALUE);
        DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1")
                .concurrency(1).requestHeaderTimeout(huge).requestBodyTimeout(huge)
                .responseWriteIdleTimeout(huge).streamingResponseIdleTimeout(Duration.ofSeconds(1)).build();
        try (Soklet owner = owner(server)) {
            owner.start();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.getEventLoop().orElseThrow().getPort()), 3_000);
                socket.setSoTimeout(3_000);
                socket.getOutputStream().write(("GET /health HTTP/1.1\r\nHost: localhost\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 200 "), response);
                assertTrue(response.endsWith("healthy"), response);
            }
        }
    }

    private static Soklet owner(HttpServer server) {
        return Soklet.fromConfig(SokletConfig.withHttpServer(server)
                .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(HealthResource.class)))
                .lifecycleObserver(new LifecycleObserver() {})
                .lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
                        .startupCancelationTimeout(Duration.ofSeconds(1))
                        .gracefulShutdownTimeout(Duration.ofSeconds(1))
                        .forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
    }

    public static final class HealthResource {
        @GET("/health") public String health() { return "healthy"; }
    }
}
