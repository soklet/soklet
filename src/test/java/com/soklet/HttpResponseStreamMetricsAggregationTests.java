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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class HttpResponseStreamMetricsAggregationTests {
    private static final String METRIC = "soklet_http_response_stream_terminations_total";
    private static MetricsCollector.HttpServerRouteStatusKey status(String route) {
        return new MetricsCollector.HttpServerRouteStatusKey(HttpMethod.GET, MetricsCollector.RouteType.MATCHED,
            ResourcePathDeclaration.fromPath(route), "2xx");
    }
    private static MetricsCollector.HttpResponseStreamTerminationKey key(String route, StreamTerminationReason reason) {
        return MetricsCollector.HttpResponseStreamTerminationKey.fromDimensions(status(route), reason);
    }
    private static MarshaledResponse response() {
        return MarshaledResponse.withStatusCode(200).stream(stream -> {}).build();
    }
    private static StreamingResponseHandle start(DefaultMetricsCollector collector, Request request) {
        MarshaledResponse response = response();
        collector.didStartRequestHandling(ServerType.HTTP, request, null);
        collector.willWriteResponse(ServerType.HTTP, request, null, response);
        StreamingResponseHandle handle = new DefaultStreamingResponseHandle(ServerType.HTTP, request, null, response, Instant.now());
        collector.willWriteResponseStream(handle);
        collector.didFinishRequestHandling(ServerType.HTTP, request, null, response, Duration.ofNanos(1), List.of());
        return handle;
    }
    private static void end(DefaultMetricsCollector collector, StreamingResponseHandle handle, StreamTerminationReason reason, long bytes) {
        collector.didTerminateResponseStream(handle, StreamTermination.with(reason, Duration.ofNanos(2)).build(), Duration.ofNanos(7), bytes);
    }
    @Test public void dimensionsAreImmutableNullableContractIsExplicitAndDiagnosticsAreRedacted() throws Exception {
        var key = key("/route-secret-canary", StreamTerminationReason.PRODUCER_FAILED);
        assertEquals(key, key("/route-secret-canary", StreamTerminationReason.PRODUCER_FAILED));
        assertEquals(key.hashCode(), key("/route-secret-canary", StreamTerminationReason.PRODUCER_FAILED).hashCode());
        assertNotEquals(key, key("/other", StreamTerminationReason.PRODUCER_FAILED));
        assertNotEquals(key, key("/route-secret-canary", StreamTerminationReason.COMPLETED));
        assertFalse(key.equals(null));
        assertSame(StreamTerminationReason.PRODUCER_FAILED, key.getReason());
        assertEquals(status("/route-secret-canary"), key.getHttpServerRouteStatusKey());
        assertFalse(key.toString().contains("route-secret-canary"));
        assertFalse(key.toString().contains("2xx"));
        assertTrue(key.toString().contains("PRODUCER_FAILED"));
        assertEquals(0, key.getClass().getConstructors().length);
        assertThrows(NullPointerException.class, () -> MetricsCollector.HttpResponseStreamTerminationKey.fromDimensions(null, StreamTerminationReason.COMPLETED));
        assertThrows(NullPointerException.class, () -> MetricsCollector.HttpResponseStreamTerminationKey.fromDimensions(status("/a"), null));
        var callback = MetricsCollector.class.getMethod("didTerminateResponseStream", StreamingResponseHandle.class, StreamTermination.class, Duration.class, Long.class);
        assertTrue(callback.isDefault());
        assertEquals(List.of("streamingResponseHandle", "streamTermination", "requestDuration", "responseBodySizeInBytes"),
            Arrays.stream(callback.getParameters()).map(p -> p.getName()).toList());
    }
    @Test public void builderCopiesAndValidatesCountsAndRetainsZero() {
        var key = key("/map-secret-canary", StreamTerminationReason.COMPLETED);
        Map<MetricsCollector.HttpResponseStreamTerminationKey, Long> map = new HashMap<>(); map.put(key, 0L);
        var builder = MetricsCollector.Snapshot.builder().httpResponseStreamTerminations(map);
        map.put(key, 9L);
        var snapshot = builder.build();
        assertEquals(Map.of(key, 0L), snapshot.getHttpResponseStreamTerminations());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getHttpResponseStreamTerminations().clear());
        builder.httpResponseStreamTerminations(null); assertTrue(builder.build().getHttpResponseStreamTerminations().isEmpty());
        assertEquals(Map.of(key, 0L), snapshot.getHttpResponseStreamTerminations());
        builder.httpResponseStreamTerminations(Map.of()); assertTrue(builder.build().getHttpResponseStreamTerminations().isEmpty());
        var failure = assertThrows(IllegalArgumentException.class, () -> builder.httpResponseStreamTerminations(Map.of(key, -1L)));
        assertFalse(failure.getMessage().contains("secret-canary"));
        map.clear(); map.put(null, 1L); assertThrows(NullPointerException.class, () -> builder.httpResponseStreamTerminations(map));
        map.clear(); map.put(key, null); assertThrows(NullPointerException.class, () -> builder.httpResponseStreamTerminations(map));
    }
    @Test public void liveResetDuplicateTerminalsAndReusedIdsCannotFinishAnotherDispatch() {
        var collector = DefaultMetricsCollector.defaultInstance();
        Object id = new Object();
        Request a = Request.withPath(HttpMethod.GET, "/a").idGenerator(request -> id).build();
        Request b = Request.withPath(HttpMethod.GET, "/b").idGenerator(request -> id).build();
        var first = start(collector, a); var second = start(collector, b);
        assertEquals(2L, collector.snapshot().orElseThrow().getActiveRequests());
        collector.reset(); assertEquals(2L, collector.snapshot().orElseThrow().getActiveRequests());
        end(collector, first, StreamTerminationReason.COMPLETED, 5);
        var snapshot = collector.snapshot().orElseThrow();
        end(collector, first, StreamTerminationReason.COMPLETED, 100);
        assertEquals(1L, collector.snapshot().orElseThrow().getActiveRequests());
        end(collector, second, StreamTerminationReason.PRODUCER_FAILED, 6);
        var finished = collector.snapshot().orElseThrow();
        assertEquals(0L, finished.getActiveRequests());
        assertEquals(2L, finished.getHttpResponseBodyBytes().values().stream().mapToLong(h -> h.getCount()).sum());
        assertEquals(11D, finished.getHttpResponseBodyBytes().values().stream().mapToDouble(h -> h.getSum()).sum());
        assertEquals(14D, finished.getHttpRequestDurations().values().stream().mapToDouble(h -> h.getSum()).sum());
        assertEquals(2L, finished.getHttpResponseStreamTerminations().values().stream().mapToLong(Long::longValue).sum());
        collector.reset(); assertTrue(collector.snapshot().orElseThrow().getHttpResponseStreamTerminations().isEmpty());
        assertEquals(1L, snapshot.getHttpResponseStreamTerminations().values().stream().mapToLong(Long::longValue).sum());
    }
    @Test public void exportsBothFormatsWithBoundedReasonsAndFilters() {
        var collector = DefaultMetricsCollector.defaultInstance();
        for (var reason : StreamTerminationReason.values()) {
            end(collector, start(collector, Request.withPath(HttpMethod.GET, "/raw-query-secret-canary").build()), reason, 3);
        }
        for (var format : MetricsCollector.MetricsFormat.values()) {
            String text = collector.snapshotText(MetricsCollector.SnapshotTextOptions.withMetricsFormat(format).metricFilter(metric -> {
                if (!METRIC.equals(metric.getName())) return false;
                assertEquals(Set.of("method", "route", "status_class", "reason"), metric.getLabels().keySet());
                assertEquals("2xx", metric.getLabels().get("status_class"));
                return "PRODUCER_FAILED".equals(metric.getLabels().get("reason"));
            }).build()).orElseThrow();
            assertTrue(text.contains(" counter\n"), text);
            assertTrue(text.contains("reason=\"PRODUCER_FAILED\""));
            assertFalse(text.contains("raw-query-secret-canary"));
            String filtered = collector.snapshotText(MetricsCollector.SnapshotTextOptions.withMetricsFormat(format)
                .metricFilter(metric -> !METRIC.equals(metric.getName())).build()).orElseThrow();
            assertFalse(filtered.contains(METRIC));
        }
    }
    @Test public void concurrentTerminationsDoNotLoseCounts() throws Exception {
        var collector = DefaultMetricsCollector.defaultInstance();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 200; i++) work.add(pool.submit(() -> end(collector,
                start(collector, Request.withPath(HttpMethod.GET, "/concurrent").build()), StreamTerminationReason.COMPLETED, 1)));
            for (Future<?> future : work) future.get(5, TimeUnit.SECONDS);
            var snapshot = collector.snapshot().orElseThrow();
            assertEquals(0L, snapshot.getActiveRequests());
            assertEquals(200L, snapshot.getHttpResponseStreamTerminations().values().stream().mapToLong(Long::longValue).sum());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test public void terminationCounterUsesTheExisting8192KeyCapacity() {
        var collector=DefaultMetricsCollector.defaultInstance();
        for(int i=0;i<8193;i++) {
            Request request=Request.withPath(HttpMethod.GET,"/capacity/"+i).build();
            collector.didStartMcpHttpRequestHandling(request,"/capacity/"+i);
            MarshaledResponse response=response();
            StreamingResponseHandle handle=new DefaultStreamingResponseHandle(ServerType.HTTP,request,null,response,Instant.now());
            collector.willWriteResponseStream(handle);
            collector.didFinishRequestHandling(ServerType.HTTP,request,null,response,Duration.ZERO,List.of());
            end(collector,handle,StreamTerminationReason.COMPLETED,0);
        }
        var keys=collector.snapshot().orElseThrow().getHttpResponseStreamTerminations();
        assertEquals(8192,keys.size());assertFalse(keys.containsKey(key("/capacity/0",StreamTerminationReason.COMPLETED)));
        assertTrue(keys.containsKey(key("/capacity/8192",StreamTerminationReason.COMPLETED)));
        assertEquals(0L,collector.snapshot().orElseThrow().getActiveRequests());
    }

    @Test public void privateObservationPreservesCapturedDurationAndRejectsFiniteDisposition() throws Exception {
        var handle=new DefaultStreamingResponseHandle(ServerType.HTTP,Request.fromPath(HttpMethod.GET,"/timing"),null,response(),Instant.now());
        var termination=StreamTermination.with(StreamTerminationReason.COMPLETED,Duration.ZERO).build();
        List<Duration> durations=new ArrayList<>();
        MetricsCollector custom=new MetricsCollector(){
            @Override public void didTerminateResponseStream(StreamingResponseHandle h,StreamTermination t,Duration duration,Long bytes){
                assertSame(handle,h);assertSame(termination,t);assertEquals(7L,bytes);durations.add(duration);
            }
        };
        var observation=new HttpResponseStreamObservation(handle.getRequest(),100L);observation.completeHandling(true);
        observation.deliver(handle,termination,150L,7L,custom,log->fail("Unexpected metric failure"));
        observation.deliver(handle,termination,999L,7L,custom,log->fail("Unexpected metric failure"));
        assertEquals(List.of(Duration.ofNanos(50)),durations);
        var finite=new HttpResponseStreamObservation(handle.getRequest(),100L);finite.completeHandling(false);
        finite.deliver(handle,termination,150L,7L,custom,log->fail("Unexpected metric failure"));
        assertEquals(1,durations.size());
        assertTrue(Arrays.stream(HttpRequestResult.class.getMethods()).noneMatch(m->m.getName().contains("StreamObservation")));
    }

}
