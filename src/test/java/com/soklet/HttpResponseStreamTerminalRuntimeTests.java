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
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.soklet.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
public class HttpResponseStreamTerminalRuntimeTests {
    private static volatile StreamingResponseWriter writer;
    public static class Resource {
        @GET("/terminal") public MarshaledResponse stream() { return MarshaledResponse.withStatusCode(200).stream(writer).build(); }
    }
    private static class Observation implements MetricsCollector {
        final DefaultMetricsCollector defaults = DefaultMetricsCollector.defaultInstance();
        final CountDownLatch handling = new CountDownLatch(1), terminal = new CountDownLatch(1), lifecycle = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger(), logs = new AtomicInteger();
        volatile Duration duration; volatile Long bytes; volatile StreamTermination outcome;
        volatile CountDownLatch blockHandling, blockLifecycle;
        volatile boolean handlingReturned, throwMetrics;
        volatile Request original;
        final Set<Request> originals=Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        final AtomicInteger handlingCalls=new AtomicInteger();
        final CountDownLatch secondHandled=new CountDownLatch(1);
        @Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) {
            original = request; originals.add(request); defaults.didStartRequestHandling(type, request, method);
        }
        @Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) {
            defaults.willWriteResponse(type, request, method, response);
        }
        @Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method, MarshaledResponse response,
                Duration duration, List<Throwable> failures) {
            defaults.didFinishRequestHandling(type, request, method, response, duration, failures);
            handling.countDown(); if (blockHandling != null) await(blockHandling);
            handlingReturned = true; if(handlingCalls.incrementAndGet()==2)secondHandled.countDown();
        }
        @Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination outcome, Duration duration, Long bytes) {
            assertTrue(originals.contains(handle.getRequest())); assertTrue(handlingReturned);
            this.duration = duration; this.bytes = bytes; this.outcome = outcome;
            defaults.didTerminateResponseStream(handle, outcome, duration, bytes);
            calls.incrementAndGet(); terminal.countDown();
            if (throwMetrics) throw new IllegalStateException("metric-failure-canary");
        }
        public void willTerminateResponseStream(StreamingResponseHandle handle, StreamTermination outcome) {
            lifecycle.countDown(); if (blockLifecycle != null) await(blockLifecycle);
        }
        public void didReceiveLogEvent(LogEvent event) { if (event.getLogEventType() == LogEventType.METRICS_COLLECTOR_FAILED) logs.incrementAndGet(); }
    }
    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            for (;;) {
                try { assertTrue(latch.await(5, TimeUnit.SECONDS)); return; }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static SokletConfig config(HttpServer server, Observation observation) {
        return config(server,observation,null);
    }
    private static SokletConfig config(HttpServer server, Observation observation, LifecyclePolicy policy) {
        return SokletConfig.withHttpServer(server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
            .metricsCollector(observation).lifecyclePolicy(policy).lifecycleObserver(new LifecycleObserver() {
                @Override public void willTerminateResponseStream(StreamingResponseHandle handle, StreamTermination outcome) { observation.willTerminateResponseStream(handle,outcome); }
                @Override public void didReceiveLogEvent(LogEvent event) { observation.didReceiveLogEvent(event); }
            }).build();
    }
    private static Socket request(int port, String method, String version) throws Exception {
        Socket socket = connectWithRetry("127.0.0.1", port, 2000); socket.setSoTimeout(5000);
        socket.getOutputStream().write((method + " /terminal " + version + "\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        return socket;
    }
    private static String head(InputStream in) throws IOException {
        StringBuilder s = new StringBuilder(); int b;
        while ((b = in.read()) != -1) { s.append((char)b); if (s.toString().endsWith("\r\n\r\n")) break; }
        return s.toString();
    }
    @Test public void earlyCompletionWaitsForMetricsHandoffAndExcludesObserverDelay() throws Exception {
        Observation o = new Observation(); o.blockHandling = new CountDownLatch(1);
        writer = stream -> stream.write(new byte[]{1,2,3});
        int port = findFreePort();
        try (Soklet soklet = Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1").build(), o))) {
            soklet.start();
            try (Socket socket = request(port, "GET", "HTTP/1.1")) {
                assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200"));
                await(o.handling); assertEquals(0, o.calls.get()); assertEquals(1L, o.defaults.snapshot().orElseThrow().getActiveRequests());
                // A real gate, not a timing assumption: the transport has finished, handling is still held.
                o.blockHandling.countDown(); await(o.terminal);
                assertEquals(3L, o.bytes); assertEquals(1, o.calls.get()); assertTrue(o.duration.toNanos() > 0);
            }
        } finally { o.blockHandling.countDown(); }
    }
    @Test public void metricsCompleteBeforeBlockedLifecycleObserver() throws Exception {
        Observation o = new Observation(); o.blockLifecycle = new CountDownLatch(1);
        writer = stream -> stream.write(new byte[]{1,2,3}); int port = findFreePort();
        try (Soklet soklet = Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1").build(), o))) {
            soklet.start();
            try (Socket socket = request(port,"GET","HTTP/1.1")) {
                socket.getInputStream().readAllBytes(); await(o.lifecycle); await(o.terminal);
                assertEquals(0L, o.defaults.snapshot().orElseThrow().getActiveRequests()); assertEquals(3L, o.bytes);
                o.blockLifecycle.countDown();
            }
        } finally { o.blockLifecycle.countDown(); }
    }
    @Test public void producerFailureCountsOnlyPayloadAlreadyWrittenAndKeepsCommittedStatus() throws Exception {
        Observation o = new Observation(); CountDownLatch fail = new CountDownLatch(1);
        writer = stream -> { stream.write("first".getBytes(StandardCharsets.US_ASCII)); stream.flush(); await(fail); throw new IOException("producer-failure-canary"); };
        int port = findFreePort();
        try (Soklet soklet = Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1").build(), o))) {
            soklet.start(); try (Socket socket = request(port,"GET","HTTP/1.1")) {
                assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 200"));
                assertEquals("5\r\nfirst\r\n", new String(socket.getInputStream().readNBytes(10),StandardCharsets.US_ASCII));
                fail.countDown(); socket.getInputStream().readAllBytes(); await(o.terminal);
                assertEquals(5L, o.bytes); assertEquals(StreamTerminationReason.PRODUCER_FAILED,o.outcome.getReason());
                assertEquals("2xx", o.defaults.snapshot().orElseThrow().getHttpResponseStreamTerminations().keySet().iterator().next().getHttpServerRouteStatusKey().getStatusClass());
            }
        } finally { fail.countDown(); }
    }
    @Test public void timeoutTerminatesAnAdmittedStreamAndRecordsZeroUnwrittenBytes() throws Exception {
        Observation o = new Observation(); CountDownLatch entered = new CountDownLatch(1);
        writer = stream -> { entered.countDown(); new CountDownLatch(1).await(5,TimeUnit.SECONDS); };
        int port=findFreePort();
        try (Soklet soklet = Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1")
            .streamingResponseTimeout(Duration.ofMillis(300)).build(),o))) {
            soklet.start(); try (Socket socket=request(port,"GET","HTTP/1.1")) {
                await(entered); socket.getInputStream().readAllBytes(); await(o.terminal);
                assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,o.outcome.getReason()); assertEquals(0L,o.bytes);
                assertEquals(0L,o.defaults.snapshot().orElseThrow().getActiveRequests());
            }
        }
    }
    @Test public void disconnectTerminatesTheAdmittedStream() throws Exception {
        Observation o=new Observation(); CountDownLatch entered=new CountDownLatch(1);
        writer=stream->{stream.write(new byte[]{1});stream.flush();entered.countDown();new CountDownLatch(1).await(5,TimeUnit.SECONDS);};
        int port=findFreePort();
        try (Soklet soklet=Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1").build(),o))) {
            soklet.start(); try (Socket socket=request(port,"GET","HTTP/1.1")) {
                head(socket.getInputStream()); socket.getInputStream().readNBytes(6); await(entered); socket.setSoLinger(true,0);
            }
            await(o.terminal); assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,o.outcome.getReason());
            assertEquals(1L,o.bytes); assertEquals(0L,o.defaults.snapshot().orElseThrow().getActiveRequests());
        }
    }
    @Test public void http10AndHeadRemainFiniteAndDoNotCountOriginalStreams() throws Exception {
        for (String method : List.of("GET","HEAD")) {
            Observation o=new Observation(); AtomicInteger entered=new AtomicInteger(); writer=stream->entered.incrementAndGet(); int port=findFreePort();
            try (Soklet soklet=Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1").build(),o))) {
                soklet.start(); try(Socket socket=request(port,method,method.equals("GET")?"HTTP/1.0":"HTTP/1.1")) {
                    String wire=new String(socket.getInputStream().readAllBytes(),StandardCharsets.US_ASCII); await(o.handling);
                    assertTrue(wire.contains(" "+(method.equals("GET")?505:200)+" "), wire);
                    assertEquals(0,entered.get()); assertEquals(0,o.calls.get());
                    var snap=o.defaults.snapshot().orElseThrow(); assertEquals(0L,snap.getActiveRequests());
                    assertTrue(snap.getHttpResponseStreamTerminations().isEmpty());
                    assertEquals(1L,snap.getHttpRequestDurations().values().stream().mapToLong(h->h.getCount()).sum());
                }
            }
        }
    }
    @Test public void throwingMetricsCallbackIsLoggedAndLifecycleStillRunsOnce() throws Exception {
        Observation o=new Observation(); o.throwMetrics=true; writer=stream->{}; int port=findFreePort();
        try(Soklet soklet=Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1").build(),o))) {
            soklet.start(); try(Socket socket=request(port,"GET","HTTP/1.1")) {
                socket.getInputStream().readAllBytes(); await(o.lifecycle); assertEquals(1,o.calls.get()); assertEquals(1,o.logs.get());
            }
        }
    }
    @Test public void simulatorCountsSuccessAndAcceptedPrefixBeforeFailure() {
        for(boolean failed:List.of(false,true)) {
            Observation o=new Observation(); writer=stream->{ stream.write("first".getBytes(StandardCharsets.US_ASCII));stream.flush();if(failed)throw new IOException("sim-failure-canary");};
            SokletSimulator.run(SimulatorConfig.fromSokletConfig(config(HttpServer.withPort(0).build(),o)),simulator->{
                Request request=Request.withPath(HttpMethod.GET,"/terminal").build();
                if(failed)assertThrows(IllegalStateException.class,()->simulator.performHttpRequest(request));
                else assertEquals(5L,simulator.performHttpRequest(request).getMarshaledResponse().getBodyLength());
                assertEquals(1,o.calls.get()); assertEquals(5L,o.bytes);
                assertEquals(failed?StreamTerminationReason.PRODUCER_FAILED:StreamTerminationReason.COMPLETED,o.outcome.getReason());
                assertEquals(0L,o.defaults.snapshot().orElseThrow().getActiveRequests());
            });
        }
    }
    @Test public void simulatorLimitKeepsTheAcceptedPrefixInMetrics() {
        Observation o=new Observation();writer=stream->{stream.write(new byte[]{1,2,3});stream.flush();stream.write(new byte[]{4,5,6});stream.flush();};
        var simulatorConfig=SimulatorConfig.withSokletConfig(config(HttpServer.withPort(0).build(),o))
            .simulatorOptions(SimulatorOptions.builder().streamingResponseBodyLimitInBytes(5).build()).build();
        SokletSimulator.run(simulatorConfig,simulator->{
            assertThrows(IllegalStateException.class,()->simulator.performHttpRequest(Request.withPath(HttpMethod.GET,"/terminal").build()));
            assertEquals(3L,o.bytes);assertEquals(StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED,o.outcome.getReason());
            assertEquals(1,o.calls.get());assertEquals(0L,o.defaults.snapshot().orElseThrow().getActiveRequests());
        });
    }

    @Test public void capacityRejectionRecordsFinite503WithoutAStreamTerminalMetric() throws Exception {
        Observation o=new Observation();CountDownLatch release=new CountDownLatch(1);
        writer=stream->{stream.write(new byte[]{1});stream.flush();await(release);};int port=findFreePort();
        try(Soklet soklet=Soklet.fromConfig(config(HttpServer.withPort(port).host("127.0.0.1")
                .streamingLifecycleCapacity(1).streamingCallbackConcurrency(1).build(),o))) {
            soklet.start();try(Socket first=request(port,"GET","HTTP/1.1")) {
                head(first.getInputStream());first.getInputStream().readNBytes(6);await(o.handling);
                try(Socket second=request(port,"GET","HTTP/1.1")) {
                    String wire=new String(second.getInputStream().readAllBytes(),StandardCharsets.US_ASCII);
                    assertTrue(wire.contains(" 503 "),wire);await(o.secondHandled);
                    assertEquals(0,o.calls.get());assertEquals(1L,o.defaults.snapshot().orElseThrow().getActiveRequests());
                }
                release.countDown();first.getInputStream().readAllBytes();await(o.terminal);
                var snapshot=o.defaults.snapshot().orElseThrow();assertEquals(0L,snapshot.getActiveRequests());
                assertEquals(1L,snapshot.getHttpResponseStreamTerminations().values().stream().mapToLong(Long::longValue).sum());
                assertTrue(snapshot.getHttpRequestDurations().keySet().stream().anyMatch(k->"5xx".equals(k.getStatusClass())));
                assertEquals(2L,snapshot.getHttpRequestDurations().values().stream().mapToLong(h->h.getCount()).sum());
            }
        }finally{release.countDown();}
    }
    @Test public void forcedServerStopTerminatesAdmittedStreamMetrics() throws Exception {
        Observation o=new Observation();CountDownLatch entered=new CountDownLatch(1);
        writer=stream->{entered.countDown();new CountDownLatch(1).await(5,TimeUnit.SECONDS);};int port=findFreePort();
        SokletConfig config=config(HttpServer.withPort(port).host("127.0.0.1").build(),o,
            LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ZERO)
                .forcedShutdownTimeout(Duration.ofSeconds(2)).build());
        try(Soklet soklet=Soklet.fromConfig(config)) {
            soklet.start();try(Socket socket=request(port,"GET","HTTP/1.1")) {
                await(entered);await(o.handling);soklet.shutdown().toCompletableFuture().get(3,TimeUnit.SECONDS);await(o.terminal);
                assertEquals(StreamTerminationReason.SERVER_STOPPING,o.outcome.getReason());assertEquals(0L,o.bytes);
                assertEquals(0L,o.defaults.snapshot().orElseThrow().getActiveRequests());
            }
        }
    }

}
