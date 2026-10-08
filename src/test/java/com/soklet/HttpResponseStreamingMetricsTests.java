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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@org.junit.jupiter.api.Timeout(20)
public class HttpResponseStreamingMetricsTests {
    static final CountDownLatch producerEntered = new CountDownLatch(1);
    static final CountDownLatch releaseProducer = new CountDownLatch(1);
    static final CountDownLatch handoff = new CountDownLatch(1);
    static final CountDownLatch terminated = new CountDownLatch(1);
    static final AtomicReference<StreamTermination> terminal = new AtomicReference<>();
    public static final class Resource {
        @GET("/stream-metrics-probe")
        public MarshaledResponse stream() {
            return MarshaledResponse.withStatusCode(200).stream(responseStream -> {
                responseStream.write("first".getBytes(StandardCharsets.US_ASCII));
                producerEntered.countDown();
                if (!releaseProducer.await(5, TimeUnit.SECONDS)) throw new AssertionError("Producer release timed out");
                responseStream.write("second".getBytes(StandardCharsets.US_ASCII));
            }).build();
        }
    }
    @org.junit.jupiter.api.Test
    public void liveStreamRetainsActiveRequestAndRecordsTransportPayloadAtTermination() throws Exception {
        int port;
        try (ServerSocket reserve = new ServerSocket(0)) { port = reserve.getLocalPort(); }
        DefaultMetricsCollector defaults = DefaultMetricsCollector.defaultInstance();
        MetricsCollector collector = new MetricsCollector() {
            @Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) {
                defaults.didStartRequestHandling(type, request, method);
            }
            @Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) {
                defaults.willWriteResponse(type, request, method, response);
            }
            @Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
                    MarshaledResponse response, Duration duration, List<Throwable> throwables) {
                defaults.didFinishRequestHandling(type, request, method, response, duration, throwables);
                handoff.countDown();
            }
            @Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination,
                    Duration requestDuration, Long bodyBytes) {
                org.junit.jupiter.api.Assertions.assertEquals(0L, handoff.getCount());
                defaults.didTerminateResponseStream(handle, termination, requestDuration, bodyBytes);
            }
        };
        LifecycleObserver observer = new LifecycleObserver() {
            @Override public void didReceiveLogEvent(LogEvent event) {}
            @Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
                terminal.set(termination); terminated.countDown();
            }
        };
        SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).build())
            .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
            .metricsCollector(collector).lifecycleObserver(observer).build();
        ExecutorService client = Executors.newSingleThreadExecutor();
        try (Soklet soklet = Soklet.fromConfig(config)) {
            soklet.start();
            Future<String> wire = client.submit(() -> {
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write(("GET /stream-metrics-probe HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                    return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                }
            });
            if (!producerEntered.await(5, TimeUnit.SECONDS) || !handoff.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Probe did not reach the live streamed response");
            MetricsCollector.Snapshot mid = defaults.snapshot().orElseThrow();
            org.junit.jupiter.api.Assertions.assertNull(terminal.get());
            org.junit.jupiter.api.Assertions.assertEquals(1L, mid.getActiveRequests());
            long bytes = mid.getHttpResponseBodyBytes().values().stream().mapToLong(h -> h.getSum().longValue()).sum();
            org.junit.jupiter.api.Assertions.assertTrue(mid.getHttpResponseBodyBytes().isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(mid.getHttpRequestDurations().isEmpty());
            releaseProducer.countDown();
            String response = wire.get(5, TimeUnit.SECONDS);
            if (!terminated.await(5, TimeUnit.SECONDS) || !response.startsWith("HTTP/1.1 200")
                    || !response.contains("first") || !response.contains("second") || terminal.get().getReason() != StreamTerminationReason.COMPLETED)
                throw new AssertionError("Control stream did not complete normally");
            MetricsCollector.Snapshot after = defaults.snapshot().orElseThrow();
            double beforeDuration = mid.getHttpRequestDurations().values().stream().mapToDouble(h -> h.getSum()).sum();
            double afterDuration = after.getHttpRequestDurations().values().stream().mapToDouble(h -> h.getSum()).sum();
            long afterBytes = after.getHttpResponseBodyBytes().values().stream().mapToLong(h -> h.getSum().longValue()).sum();
            org.junit.jupiter.api.Assertions.assertTrue(afterDuration > beforeDuration);
            org.junit.jupiter.api.Assertions.assertEquals(11L, afterBytes);
            org.junit.jupiter.api.Assertions.assertEquals(0L, after.getActiveRequests());

        } finally {
            releaseProducer.countDown(); client.shutdownNow();
            if (!client.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("Client did not stop");
        }
    }
}
