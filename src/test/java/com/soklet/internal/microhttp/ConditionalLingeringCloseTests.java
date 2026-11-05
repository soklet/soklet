/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet.internal.microhttp;

import com.soklet.MetricsCollector.TransportFailureReason;
import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real parser/response/readiness turns without starting listeners or event-loop threads. */
@Timeout(30)
class ConditionalLingeringCloseTests {
    private static final String CLOSE = "GET /close HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    private static final String KEEP = "GET /keep HTTP/1.1\r\nHost: localhost\r\n\r\n";

    @Test
    void completeRequestsClosePromptlyRegardlessOfResponseStatusOrRequestFraming() throws Exception {
        for (String request : List.of(CLOSE, "GET /old HTTP/1.0\r\n\r\n",
                "POST /fixed HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 3\r\n\r\nabc",
                "POST /chunked HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "3;ext=valid\r\nabc\r\n0\r\nX-Trailer: complete\r\n\r\n",
                "POST /empty HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")) {
            for (int status : new int[]{200, 413, 500}) {
                try (Fixture fixture = new Fixture(1_024)) {
                    fixture.read(request);
                    assertEquals(1, fixture.requests.size());
                    fixture.respond(status);
                    fixture.assertPromptClose();
                    assertTrue(fixture.socket.wire().startsWith("HTTP/1."), fixture.socket.wire());
                    assertTrue(fixture.socket.wire().endsWith("done"), fixture.socket.wire());
                }
            }
        }
    }

    @Test
    void aggregateLimitAfterCompleteParseClosesPromptlyButEarly413StillDrains() throws Exception {
        String headers = "POST /limit HTTP/1.1\r\nHost: localhost\r\nContent-Length: 90\r\n\r\n";
        for (boolean pipelined : new boolean[]{false, true}) {
            for (boolean complete : new boolean[]{true, false}) {
                try (Fixture fixture = new Fixture(100)) {
                    fixture.read((pipelined ? KEEP : "") + headers + (complete ? "x".repeat(90) : ""));
                    if (pipelined)
                        fixture.reply(new MicrohttpResponse(200, "OK", List.of(), ascii("first")));
                    assertEquals(pipelined ? 2 : 1, fixture.requests.size());
                    assertTrue(fixture.requests.getLast().contentTooLarge());
                    fixture.respond(413);
                    if (complete) fixture.assertPromptClose();
                    else fixture.assertLingeringClose();
                }
            }
        }
    }

    @Test
    void rawRejectionsAndPartialBodyTimeoutsKeepProtectionAfterCaptureClearsTheirBuffers() throws Exception {
        for (String request : List.of("INVALID\r\n\r\n",
                "POST /bad HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n",
                "POST /expect HTTP/1.1\r\nHost: localhost\r\nExpect: other\r\nContent-Length: 3\r\n\r\n",
                "POST /partial HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nx",
                "POST /trailer HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Trailer: incomplete")) {
            try (Fixture fixture = new Fixture(1_024)) {
                fixture.read(request);
                if (fixture.socket.output.size() == 0) fixture.invoke("onRequestReadTimeout");
                fixture.runQueuedTasks();
                assertEquals(0, fixture.requests.size());
                assertEquals(0, ((ByteTokenizer) field(fixture.connection, "byteTokenizer")).remaining());
                fixture.assertLingeringClose();
                fixture.invoke("runReadableTask"); // zero read is not evidence that the upload ended
                fixture.assertLingeringClose();
            }
        }
    }

    @Test
    void surplusInputObservedWithRequestOrWhileHandlerPendingKeepsDrainAndNeverDispatches() throws Exception {
        for (boolean late : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(1_024)) {
                fixture.read(CLOSE + (late ? "" : KEEP));
                if (late) fixture.read(KEEP);
                fixture.respond(200);
                fixture.assertLingeringClose();
                fixture.read(KEEP);
                assertEquals(1, fixture.requests.size(), "Terminal input must never become another request");
                fixture.socket.endOfInput = true;
                fixture.invoke("runReadableTask");
                fixture.assertReleased();
            }
        }
    }

    @Test
    void streamingDiscardObservationIsCapturedBeforeItsCounterIsReset() throws Exception {
        for (boolean surplus : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(1_024)) {
                fixture.read(CLOSE);
                PendingSource source = new PendingSource();
                fixture.reply(MicrohttpResponse.withStreamingBody(200, "OK", List.of(), () -> source));
                assertNotNull(field(fixture.connection, "writableSource"));
                if (surplus) fixture.read("discarded input");
                source.finish = true;
                fixture.invoke("onWritable");
                assertEquals(0, field(fixture.connection, "streamingResponseBytesDiscarded"));
                assertEquals(1, source.closes);
                if (surplus) fixture.assertLingeringClose();
                else fixture.assertPromptClose();
            }
        }
    }

    @Test
    void nextPersistentRequestHasItsOwnCompletionProofAndDoesNotInheritPriorTail() throws Exception {
        for (String tail : List.of(CLOSE,
                "POST /next HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 3\r\n\r\nx",
                "POST /large HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2000\r\n\r\n",
                "INVALID\r\n\r\n")) {
            try (Fixture fixture = new Fixture(1_024)) {
                fixture.read(KEEP + tail);
                fixture.reply(new MicrohttpResponse(200, "OK", List.of(), ascii("first")));
                if (tail.equals(CLOSE)) {
                    assertEquals(2, fixture.requests.size());
                    fixture.respond(200);
                    fixture.assertPromptClose();
                } else if (tail.contains("/next")) {
                    assertEquals(1, fixture.requests.size());
                    fixture.read("yz");
                    assertEquals(2, fixture.requests.size());
                    fixture.respond(200);
                    fixture.assertPromptClose();
                } else if (tail.contains("/large")) {
                    assertEquals(2, fixture.requests.size());
                    assertTrue(fixture.requests.getLast().contentTooLarge());
                    fixture.respond(413);
                    fixture.assertLingeringClose();
                } else {
                    assertEquals(1, fixture.requests.size());
                    fixture.assertLingeringClose();
                    assertTrue(fixture.socket.wire().contains("HTTP/1.1 400"));
                }
            }
        }
    }

    @Test
    void queuedSocketInputIsProbedOnceAndCompletedBodyOwnershipIsUnchanged() throws Exception {
        for (boolean graceful : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(1_024)) {
                fixture.read(graceful ? KEEP : CLOSE);
                fixture.socket.input.add(ByteBuffer.wrap(ascii(KEEP)));
                int reads = fixture.socket.reads;
                fixture.draining.set(graceful);
                byte[] body = ascii("x".repeat(256 * 1_024));
                AtomicInteger closes = new AtomicInteger();
                AtomicInteger terminations = new AtomicInteger();
                AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
                fixture.reply(MicrohttpResponse.withWritableSourceBody(200, "OK", List.of(), body.length,
                        () -> new ByteBufferWritableSource(ByteBuffer.wrap(body)) {
                            @Override public void close() { closes.incrementAndGet(); }
                        }).withBodyTerminationListener((value, cause) -> {
                            reason.set(value);
                            terminations.incrementAndGet();
                        }));
                fixture.assertLingeringClose();
                assertEquals(reads + 1, fixture.socket.reads);
                assertEquals(1, fixture.socket.lastReadLimit);
                assertEquals(1L, field(fixture.connection, "lingeringCloseBytesDiscarded"));
                assertEquals(KEEP.length() - 1, fixture.socket.input.element().remaining());
                assertEquals(0, ((ByteTokenizer) field(fixture.connection, "byteTokenizer")).remaining());
                assertTrue(fixture.socket.wire().endsWith(new String(body, StandardCharsets.US_ASCII)));
                assertEquals(1, closes.get());
                assertEquals(1, terminations.get());
                assertEquals(StreamTerminationReason.COMPLETED, reason.get());
                fixture.invoke("runReadableTask");
                assertEquals(1, fixture.requests.size(), "Probed and drained bytes must never dispatch");
                fixture.invoke("failSafeClose");
                fixture.assertReleased();
                assertEquals(1, closes.get());
                assertEquals(1, terminations.get());
            }
        }
    }

    @Test
    void terminalProbeZeroEofAndReadFailuresCloseQuietlyAfterExactlyOneRead() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            try (Fixture fixture = new Fixture(1_024)) {
                fixture.read(CLOSE);
                if (mode == 1) fixture.socket.endOfInput = true;
                if (mode == 2) fixture.socket.ioFailure = new IOException("Expected terminal reset");
                if (mode == 3) fixture.socket.runtimeFailure = new IllegalStateException("Expected terminal read failure");
                int reads = fixture.socket.reads;
                int observationsBefore = fixture.observations.size();
                fixture.respond(200);
                fixture.assertPromptClose();
                assertEquals(reads + 1, fixture.socket.reads);
                assertEquals(1, fixture.socket.lastReadLimit);
                assertEquals(mode == 1, field(fixture.connection, "inputHalfClosed"));
                List<FailureObservation> terminalObservations = fixture.observations.subList(
                        observationsBefore, fixture.observations.size());
                assertTrue(terminalObservations.stream().allMatch(observation -> observation.discarded),
                        "A terminal probe failure must not become a request/read/write failure");
                assertEquals(0, terminalObservations.stream()
                        .filter(observation -> observation.reason == TransportFailureReason.READ_ERROR).count());
                assertEquals(0, terminalObservations.stream()
                        .filter(observation -> observation.reason == TransportFailureReason.WRITE_ERROR).count(),
                        "Probe failures must not escape to response-write classification");
            }
        }
    }

    @Test
    void persistentKnownRiskAndTerminalFastPathsDoNotProbe() throws Exception {
        for (String mode : List.of("persistent", "buffered", "incomplete", "forced", "eof", "invalid", "closed")) {
            try (Fixture fixture = new Fixture(1_024)) {
                fixture.read(mode.equals("persistent") ? KEEP : mode.equals("buffered") ? CLOSE + KEEP
                        : mode.equals("incomplete")
                        ? "POST /early HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2000\r\n\r\n" : CLOSE);
                int reads = fixture.socket.reads;
                if (mode.equals("forced") || mode.equals("invalid") || mode.equals("closed")) {
                    if (mode.equals("forced")) fixture.stop.set(true);
                    if (mode.equals("invalid")) fixture.key.valid = false;
                    if (mode.equals("closed")) fixture.invoke("failSafeClose");
                    fixture.socket.input.add(ByteBuffer.wrap(ascii(KEEP)));
                    fixture.respond(200);
                    fixture.assertPromptClose();
                } else if (mode.equals("eof")) {
                    fixture.socket.endOfInput = true;
                    fixture.invoke("runReadableTask");
                    reads = fixture.socket.reads;
                    assertEquals(true, field(fixture.connection, "inputHalfClosed"));
                    fixture.respond(200);
                    fixture.assertPromptClose();
                } else if (mode.equals("persistent")) {
                    fixture.socket.input.add(ByteBuffer.wrap(ascii(KEEP)));
                    fixture.reply(new MicrohttpResponse(200, "OK", List.of(), ascii("done")));
                    assertTrue(fixture.socket.isOpen());
                    assertFalse(fixture.socket.outputShutdown);
                    assertEquals(1, fixture.requests.size());
                    assertEquals(KEEP.length(), fixture.socket.input.element().remaining());
                } else {
                    fixture.socket.input.add(ByteBuffer.wrap(ascii(KEEP)));
                    fixture.respond(413);
                    fixture.assertLingeringClose();
                    assertEquals(0L, field(fixture.connection, "lingeringCloseBytesDiscarded"));
                }
                assertEquals(reads, fixture.socket.reads, mode);
            }
        }
    }

    @Test
    void probeByteCountsTowardTheExactTerminalDiscardCeiling() throws Exception {
        try (Fixture fixture = new Fixture(1_024)) {
            fixture.read(CLOSE);
            fixture.socket.input.add(ByteBuffer.allocate(20 * 1_024 * 1_024));
            long before = fixture.socket.bytesRead;
            fixture.respond(200);
            fixture.assertLingeringClose();
            assertEquals(1, fixture.socket.lastReadLimit);
            assertEquals(1, fixture.socket.bytesRead - before);
            assertEquals(1L, field(fixture.connection, "lingeringCloseBytesDiscarded"));
            // No loop or timer is started/pumped: only the byte ceiling can close this socket.
            for (int turn = 0; turn < 200 && fixture.socket.isOpen(); turn++)
                fixture.invoke("runReadableTask");
            fixture.assertReleased();
            assertEquals(16L * 1_024 * 1_024, fixture.socket.bytesRead - before);
            assertEquals(4 * 1_024 * 1_024, fixture.socket.input.element().remaining());
            assertEquals(76_714, fixture.socket.lastReadLimit, "The probe consumes one byte of the final read budget");
            assertEquals(0, ((Scheduler) field(fixture.loop, "timeoutQueue")).size());
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test
    void positiveProbeKeepsOneAbsoluteDeadlineDespiteFurtherInputProgress() throws Exception {
        try (Fixture fixture = new Fixture(1_024)) {
            fixture.read(CLOSE);
            fixture.socket.input.add(ByteBuffer.wrap(ascii(KEEP)));
            fixture.respond(200);
            Scheduler scheduler = (Scheduler) field(fixture.loop, "timeoutQueue");
            Object deadline = field(fixture.connection, "lingeringCloseTimeoutTask");
            assertEquals(1, scheduler.size());
            fixture.clock.now = 500_000_000L;
            fixture.invoke("runReadableTask");
            assertSame(deadline, field(fixture.connection, "lingeringCloseTimeoutTask"));
            fixture.clock.now = 999_999_999L;
            assertTrue(scheduler.expired().isEmpty());
            fixture.clock.now = 1_000_000_000L;
            List<Runnable> expired = scheduler.expired();
            assertEquals(1, expired.size());
            expired.forEach(Runnable::run);
            fixture.assertReleased();
            assertNull(field(fixture.connection, "lingeringCloseTimeoutTask"));
        }
    }

    @Test
    void anotherConnectionCanReadNormallyAfterTheSharedBufferWasLimitedToOneByte() throws Exception {
        try (Fixture fixture = new Fixture(1_024)) {
            ScriptedSocket siblingSocket = new ScriptedSocket();
            RecordingKey siblingKey = new RecordingKey(siblingSocket, fixture.key.selector);
            AtomicInteger siblingAdmitted = new AtomicInteger(1);
            AtomicInteger siblingReleases = new AtomicInteger();
            Object sibling = fixture.newConnection(siblingSocket, siblingKey, siblingAdmitted, siblingReleases);
            try {
                fixture.read(CLOSE);
                fixture.respond(200);
                assertFalse(fixture.socket.isOpen());
                assertEquals(1, ((ByteBuffer) field(fixture.loop, "buffer")).limit());
                siblingSocket.input.add(ByteBuffer.wrap(ascii("POST /next HTTP/1.1\r\nHost: localhost\r\n"
                        + "Connection: close\r\nContent-Length: 128\r\n\r\n" + "x".repeat(128))));
                fixture.invoke(sibling, "runReadableTask");
                fixture.runQueuedTasks();
                assertEquals(2, fixture.requests.size());
                assertEquals(128, fixture.requests.getLast().body().length);
                assertTrue(siblingSocket.lastReadLimit > 1);
                fixture.respond(200);
                assertFalse(siblingSocket.isOpen());
                assertEquals(1, siblingReleases.get());
                assertEquals(0, siblingAdmitted.get());
                fixture.assertReleased();
            } finally {
                fixture.invoke(sibling, "failSafeClose");
            }
        }
    }

    @Test
    void drainPublishedBeforeOrDuringFinalWriteUsesTheCapturedInputRisk() throws Exception {
        for (DrainBoundary boundary : List.of(DrainBoundary.BEFORE_PREPARATION,
                DrainBoundary.FINAL_WRITE, DrainBoundary.SOURCE_CLOSE)) {
            for (String surplus : List.of("socket", "tokenizer", "empty"))
                verifyDrainAtBoundary(boundary, surplus);
            if (boundary != DrainBoundary.BEFORE_PREPARATION)
                verifyDiscardedStreamAtBoundary(boundary);
        }
    }

    @Test
    void drainPublishedByTheCompletedBodyCallbackUsesTheSameTerminalClosePath() throws Exception {
        for (String surplus : List.of("socket", "tokenizer", "empty"))
            verifyDrainAtBoundary(DrainBoundary.COMPLETION_CALLBACK, surplus);
    }

    private static void verifyDiscardedStreamAtBoundary(DrainBoundary boundary) throws Exception {
        try (Fixture fixture = new Fixture(1_024)) {
            fixture.read(KEEP);
            PendingSource source = new PendingSource() {
                @Override public long writeTo(SocketChannel socket, long maximum) throws IOException {
                    long written = super.writeTo(socket, maximum);
                    if (boundary == DrainBoundary.FINAL_WRITE && !hasRemaining())
                        fixture.draining.set(true);
                    return written;
                }
                @Override public void close() {
                    super.close();
                    if (boundary == DrainBoundary.SOURCE_CLOSE)
                        fixture.draining.set(true);
                }
            };
            fixture.reply(MicrohttpResponse.withStreamingBody(200, "OK", List.of(), () -> source));
            fixture.read("discarded input");
            assertTrue((int) field(fixture.connection, "streamingResponseBytesDiscarded") > 0);
            assertEquals(0, ((ByteTokenizer) field(fixture.connection, "byteTokenizer")).remaining());
            // DISCARD makes this response terminal before drain publication. Its input
            // evidence must still survive cleanup through the shared close selector.
            assertEquals(true, field(fixture.connection, "closeAfterResponse"));
            int readsBefore = fixture.socket.reads;
            source.finish = true;
            fixture.invoke("onWritable");
            assertEquals(0, field(fixture.connection, "streamingResponseBytesDiscarded"));
            fixture.assertLingeringClose();
            assertEquals(0L, field(fixture.connection, "lingeringCloseBytesDiscarded"));
            assertEquals(readsBefore, fixture.socket.reads, "Known discarded input requires no speculative read");
            assertEquals(1, fixture.requests.size());
            assertEquals(1, source.closes);
            fixture.invoke("failSafeClose");
            assertEquals(1, source.closes);
        }
    }

    private static void verifyDrainAtBoundary(DrainBoundary boundary, String surplus) throws Exception {
        try (Fixture fixture = new Fixture(1_024)) {
            fixture.read(KEEP + (surplus.equals("tokenizer") ? KEEP : ""));
            if (surplus.equals("socket"))
                fixture.socket.input.add(ByteBuffer.wrap(ascii(KEEP)));
            assertEquals(false, field(fixture.connection, "closeAfterResponse"));
            int readsBefore = fixture.socket.reads;
            AtomicInteger closes = new AtomicInteger();
            AtomicInteger terminations = new AtomicInteger();
            AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
            ByteBuffer body = ByteBuffer.wrap(ascii("complete"));
            if (boundary == DrainBoundary.BEFORE_PREPARATION)
                fixture.draining.set(true);
            fixture.reply(MicrohttpResponse.withWritableSourceBody(200, "OK", List.of(), body.remaining(),
                    () -> new ByteBufferWritableSource(body) {
                        @Override public long writeTo(SocketChannel socket, long maximum) throws IOException {
                            long written = super.writeTo(socket, maximum);
                            if (boundary == DrainBoundary.FINAL_WRITE && !hasRemaining())
                                fixture.draining.set(true);
                            return written;
                        }
                        @Override public void close() {
                            closes.incrementAndGet();
                            if (boundary == DrainBoundary.SOURCE_CLOSE)
                                fixture.draining.set(true);
                        }
                    }).withBodyTerminationListener((value, cause) -> {
                        reason.set(value);
                        terminations.incrementAndGet();
                        if (boundary == DrainBoundary.COMPLETION_CALLBACK)
                            fixture.draining.set(true);
                    }));
            if (surplus.equals("empty")) {
                fixture.assertPromptClose();
                assertEquals(readsBefore + 1, fixture.socket.reads);
            } else {
                fixture.assertLingeringClose();
                assertEquals(readsBefore + (surplus.equals("socket") ? 1 : 0), fixture.socket.reads);
                assertEquals(surplus.equals("socket") ? 1L : 0L,
                        field(fixture.connection, "lingeringCloseBytesDiscarded"));
                assertEquals(0, ((ByteTokenizer) field(fixture.connection, "byteTokenizer")).remaining());
            }
            assertEquals(true, field(fixture.connection, "closeAfterResponse"));
            assertEquals(true, field(fixture.connection, "requestFullyParsed"),
                    "A drain must not fall through into the next parser generation");
            assertTrue(fixture.socket.wire().endsWith("complete"));
            assertEquals(1, fixture.requests.size());
            assertEquals(1, closes.get());
            assertEquals(1, terminations.get());
            assertEquals(StreamTerminationReason.COMPLETED, reason.get());
            assertTrue(fixture.observations.stream().allMatch(observation -> observation.discarded));
            fixture.invoke("runReadableTask");
            assertEquals(1, fixture.requests.size(), "Terminal input must never dispatch another request");
            fixture.invoke("failSafeClose");
            fixture.assertReleased();
            assertEquals(1, closes.get());
            assertEquals(1, terminations.get());
        }
    }

    @Test
    void lateDrainHandoffPreservesForcedEofInvalidAndClosedFastPaths() throws Exception {
        for (String mode : List.of("forced", "eof", "invalid", "closed")) {
            try (Fixture fixture = new Fixture(1_024)) {
                fixture.read(KEEP + (mode.equals("eof") ? KEEP : ""));
                if (mode.equals("eof")) {
                    fixture.socket.endOfInput = true;
                    fixture.invoke("runReadableTask");
                    assertEquals(true, field(fixture.connection, "inputHalfClosed"));
                }
                assertEquals(false, field(fixture.connection, "closeAfterResponse"));
                int readsBefore = fixture.socket.reads;
                AtomicInteger terminations = new AtomicInteger();
                AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
                AtomicReference<Exception> callbackFailure = new AtomicReference<>();
                fixture.reply(new MicrohttpResponse(200, "OK", List.of(), ascii("complete"))
                        .withBodyTerminationListener((value, cause) -> {
                            terminations.incrementAndGet();
                            reason.set(value);
                            fixture.draining.set(true);
                            if (mode.equals("forced")) fixture.stop.set(true);
                            if (mode.equals("invalid")) fixture.key.valid = false;
                            if (mode.equals("closed")) {
                                try { fixture.invoke("failSafeClose"); }
                                catch (Exception failure) { callbackFailure.set(failure); }
                            }
                        }));
                assertNull(callbackFailure.get());
                fixture.assertPromptClose();
                assertEquals(readsBefore, fixture.socket.reads, mode);
                assertEquals(1, fixture.requests.size());
                assertEquals(1, terminations.get());
                assertEquals(StreamTerminationReason.COMPLETED, reason.get());
                assertTrue(fixture.observations.stream().allMatch(observation -> observation.discarded));
            }
        }
    }

    private enum DrainBoundary {
        BEFORE_PREPARATION,
        FINAL_WRITE,
        SOURCE_CLOSE,
        COMPLETION_CALLBACK
    }

    private static final class Fixture implements AutoCloseable {
        final ScriptedSocket socket = new ScriptedSocket();
        final AtomicInteger admitted = new AtomicInteger(1);
        final AtomicInteger releases = new AtomicInteger();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicBoolean draining = new AtomicBoolean();
        final ManualClock clock = new ManualClock();
        final List<FailureObservation> observations = new ArrayList<>();
        final List<MicrohttpRequest> requests = new ArrayList<>();
        final Queue<Consumer<MicrohttpResponse>> replies = new ArrayDeque<>();
        final ConnectionEventLoop loop;
        final RecordingKey key;
        final Object connection;

        Fixture(int maxRequestSize) throws Exception {
            Handler handler = new Handler() {
                @Override public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
                    requests.add(request);
                    replies.add(callback);
                }
                @Override public boolean monitorClientDisconnectsBeforeResponse(MicrohttpRequest request) { return true; }
                @Override public StreamingResponseInputPolicy streamingResponseInputPolicy(MicrohttpRequest request) {
                    return StreamingResponseInputPolicy.DISCARD;
                }
            };
            Options options = OptionsBuilder.newBuilder().withPort(0).withMaxRequestSize(maxRequestSize)
                    .withMaxRequestBodySize(maxRequestSize).withReadBufferSize(100_003).build();
            loop = new ConnectionEventLoop(options, NoopLogger.instance(), handler, new AtomicLong(),
                    stop, draining, failure -> fail(failure), () -> {}, reason -> {
                        FailureObservation observation = new FailureObservation(reason);
                        observations.add(observation);
                        return observation;
                    });
            Field schedulerField = loop.getClass().getDeclaredField("timeoutQueue");
            schedulerField.setAccessible(true);
            schedulerField.set(loop, new Scheduler(clock));
            key = new RecordingKey(socket, (Selector) field(loop, "selector"));
            connection = newConnection(socket, key, admitted, releases);
        }

        Object newConnection(ScriptedSocket socket, RecordingKey key, AtomicInteger admitted,
                             AtomicInteger releases) throws Exception {
            Constructor<EventLoop.ConnectionAdmission> admissionConstructor =
                    EventLoop.ConnectionAdmission.class.getDeclaredConstructor(AtomicInteger.class, Runnable.class);
            admissionConstructor.setAccessible(true);
            EventLoop.ConnectionAdmission admission = admissionConstructor.newInstance(admitted,
                    (Runnable) releases::incrementAndGet);
            Class<?> type = Class.forName(ConnectionEventLoop.class.getName() + "$Connection");
            Constructor<?> constructor = type.getDeclaredConstructor(ConnectionEventLoop.class,
                    SocketChannel.class, SelectionKey.class, InetSocketAddress.class,
                    EventLoop.ConnectionAdmission.class);
            constructor.setAccessible(true);
            Object connection = constructor.newInstance(loop, socket, key, null, admission);
            ((AtomicInteger) field(loop, "connectionCount")).incrementAndGet();
            return connection;
        }

        void read(String input) throws Exception {
            socket.input.add(ByteBuffer.wrap(ascii(input)));
            invoke("runReadableTask");
            runQueuedTasks();
        }

        void respond(int status) throws Exception {
            reply(new MicrohttpResponse(status, "Response", List.of(new Header("Connection", "close")), ascii("done")));
        }

        void reply(MicrohttpResponse response) throws Exception {
            assertFalse(replies.isEmpty());
            replies.remove().accept(response);
            runQueuedTasks();
        }

        @SuppressWarnings("unchecked")
        void runQueuedTasks() throws Exception {
            Queue<Runnable> tasks = (Queue<Runnable>) field(loop, "taskQueue");
            for (int turn = 0; turn < 16; turn++) {
                Runnable task = tasks.poll();
                if (task == null) return;
                task.run();
            }
            fail("Unexpectedly unbounded connection-task chain");
        }

        void invoke(String name) throws Exception {
            invoke(connection, name);
        }

        void invoke(Object connection, String name) throws Exception {
            Method method = connection.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            try { method.invoke(connection); }
            catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof Exception exception) throw exception;
                if (wrapped.getCause() instanceof Error error) throw error;
                throw new AssertionError(wrapped.getCause());
            }
        }

        void assertPromptClose() throws Exception {
            assertReleased();
            assertFalse(socket.outputShutdown, "A completely consumed request needs no retained terminal phase");
            assertNull(field(connection, "lingeringCloseTimeoutTask"));
        }

        void assertLingeringClose() throws Exception {
            assertTrue(socket.isOpen());
            assertTrue(socket.outputShutdown);
            assertTrue((boolean) field(connection, "lingeringClose"));
            assertNotNull(field(connection, "lingeringCloseTimeoutTask"));
            assertEquals(SelectionKey.OP_READ, key.interestOps());
            assertEquals(1, admitted.get());
            assertEquals(0, releases.get());
        }

        void assertReleased() {
            assertFalse(socket.isOpen());
            assertFalse(key.isValid());
            assertEquals(1, socket.closes);
            assertEquals(1, key.cancels);
            assertEquals(0, admitted.get());
            assertEquals(1, releases.get());
            assertEquals(0, loop.numConnections());
        }

        @Override public void close() throws Exception {
            try { invoke("failSafeClose"); }
            finally { loop.closeBeforeStart(); }
            assertReleased();
            assertTrue(loop.resourcesClosed());
        }
    }

    private static final class ManualClock implements Clock {
        long now;
        @Override public long nanoTime() { return now; }
    }

    private static final class FailureObservation implements TransportFailureObserver.Observation {
        final TransportFailureReason reason;
        boolean discarded;
        FailureObservation(TransportFailureReason reason) { this.reason = reason; }
        @Override public void discard() { discarded = true; }
        @Override public void close() {}
    }

    private static class PendingSource implements WritableSource {
        final ByteBuffer ending = ByteBuffer.wrap(ascii("0\r\n\r\n"));
        boolean finish;
        int closes;
        @Override public long writeTo(SocketChannel channel, long maximum) throws IOException {
            return finish ? channel.write(ending) : 0;
        }
        @Override public boolean hasRemaining() { return !finish || ending.hasRemaining(); }
        @Override public void close() { closes++; }
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static final class RecordingKey extends SelectionKey {
        final SocketChannel socket;
        final Selector selector;
        boolean valid = true;
        int interests = SelectionKey.OP_READ;
        int cancels;
        RecordingKey(SocketChannel socket, Selector selector) { this.socket = socket; this.selector = selector; }
        @Override public SelectableChannel channel() { return socket; }
        @Override public Selector selector() { return selector; }
        @Override public boolean isValid() { return valid; }
        @Override public void cancel() { valid = false; cancels++; }
        @Override public int interestOps() { return interests; }
        @Override public SelectionKey interestOps(int value) { interests = value; return this; }
        @Override public int readyOps() { return interests; }
    }

    private static final class ScriptedSocket extends SocketChannel {
        final Queue<ByteBuffer> input = new ArrayDeque<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean endOfInput;
        boolean outputShutdown;
        int closes;
        int reads;
        int lastReadLimit;
        long bytesRead;
        IOException ioFailure;
        RuntimeException runtimeFailure;
        ScriptedSocket() { super(SelectorProvider.provider()); }
        @Override public int read(ByteBuffer bytes) throws IOException {
            reads++;
            lastReadLimit = bytes.remaining();
            if (ioFailure != null) throw ioFailure;
            if (runtimeFailure != null) throw runtimeFailure;
            ByteBuffer next = input.peek();
            if (next == null) return endOfInput ? -1 : 0;
            int count = Math.min(bytes.remaining(), next.remaining());
            int limit = next.limit();
            next.limit(next.position() + count);
            bytes.put(next);
            next.limit(limit);
            if (!next.hasRemaining()) input.remove();
            bytesRead += count;
            return count;
        }
        @Override public long read(ByteBuffer[] bytes, int offset, int length) { throw new AssertionError(); }
        @Override public int write(ByteBuffer bytes) {
            int count = bytes.remaining();
            byte[] written = new byte[count];
            bytes.get(written);
            output.writeBytes(written);
            return count;
        }
        @Override public long write(ByteBuffer[] bytes, int offset, int length) {
            long count = 0;
            for (int index = offset; index < offset + length; index++) count += write(bytes[index]);
            return count;
        }
        String wire() { return output.toString(StandardCharsets.US_ASCII); }
        @Override public SocketChannel bind(SocketAddress address) { return this; }
        @Override public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
        @Override public <T> T getOption(SocketOption<T> option) { throw new UnsupportedOperationException(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public SocketChannel shutdownInput() { return this; }
        @Override public SocketChannel shutdownOutput() { outputShutdown = true; return this; }
        @Override public Socket socket() { return new Socket(); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress address) { return true; }
        @Override public boolean finishConnect() { return true; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override protected void implCloseSelectableChannel() { closes++; }
        @Override protected void implConfigureBlocking(boolean blocking) {}
    }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
}
