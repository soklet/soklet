package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * This class represents an independent, threaded event loop for managing a group of connections.
 * It has its own selector, direct off-heap byte buffer, timeout queue, task queue, and state-per-connection.
 * <p>
 * ConnectionEventLoop instances are managed by a parent EventLoop.
 *
 * <p>
 * The diagram below outlines the various connection states.
 *
 * <pre>
 *                                                   Write Complete Non-Persistent
 *                                   Write     +--------------------------------------------+
 *                                   Complete  |                                            |
 *              Read                 Request   |                Write                       |
 *              Partial              Pipelined |                Partial                     |
 *              +-----+                +-----+ |                +-----+                     |
 *              |     |                |     | |                |     |    Write            |
 *              |     v                |     v |                |     v    Complete         v
 *            +-+--------+  Read-     ++-------+-+  Write-    +-+--------+ Non-       +----------+
 *    Accept  |          |  Complete  |          |  Partial   |          | Persist.   |          |
 * ---------->| READABLE +----------->| DISPATCH +----------->| WRITABLE +----------->|  CLOSED  |
 *            |          |            |          |            |          |            |          |
 *            +----+-----+            +----------+ Write      +-+---+----+            +----------+
 *                 |                        ^      Complete     |   |
 *                 |                        |      Request      |   |
 *                 |                        |      Pipelined    |   |
 *                 |                        +-------------------+   |
 *                 |                                                |
 *                 +------------------------------------------------+
 *                               Write Complete Persistent
 * </pre>
 */
class ConnectionEventLoop {
    private static final long MAX_RESPONSE_BYTES_PER_WRITE_TURN = 1024L * 1024L;

    @FunctionalInterface
    private interface ThrowingTask {
        void run() throws Exception;
    }

    private static final class PendingRegistration {
        final SocketChannel socketChannel;
        final EventLoop.ConnectionAdmission admission;
        final AtomicBoolean claimed;

        private PendingRegistration(SocketChannel socketChannel, EventLoop.ConnectionAdmission admission) {
            this.socketChannel = socketChannel;
            this.admission = admission;
            this.claimed = new AtomicBoolean();
        }

        private boolean claim() {
            return claimed.compareAndSet(false, true);
        }

        private void closeAndRelease() {
            CloseUtils.closeQuietly(socketChannel);
            admission.release();
        }
    }

    private enum DispatchState {
        HANDLING,
        RESPONSE_PENDING,
        INVALID_RESPONSE_PENDING,
        PREPARING,
        COMMITTED,
        CANCELED
    }

    private enum ResponseDisposition {
        ACCEPTED,
        CANCELED,
        DUPLICATE
    }

    private static final class ResponseOffer {
        final ResponseDisposition disposition;
        final StreamTerminationReason discardReason;
        final @Nullable Throwable discardCause;

        private ResponseOffer(ResponseDisposition disposition, StreamTerminationReason discardReason,
                              @Nullable Throwable discardCause) {
            this.disposition = disposition;
            this.discardReason = discardReason;
            this.discardCause = discardCause;
        }
    }

    private static final class DispatchCancellation {
        final @Nullable MicrohttpResponse pendingResponse;

        private DispatchCancellation(@Nullable MicrohttpResponse pendingResponse) {
            this.pendingResponse = pendingResponse;
        }
    }

    /**
     * The ownership ticket for one handler dispatch. Handler callbacks may arrive from any thread, so all
     * state transitions are synchronized on this object. Connection-level references are still changed only
     * by the event-loop thread.
     */
    private static final class InFlightDispatch {
        final MicrohttpRequest request;
        final boolean monitorClientDisconnects;
        private DispatchState state;
        private @Nullable MicrohttpResponse pendingResponse;
        private @Nullable StreamTerminationReason cancelationReason;
        private @Nullable Throwable cancelationCause;

        private InFlightDispatch(MicrohttpRequest request, boolean monitorClientDisconnects) {
            this.request = request;
            this.monitorClientDisconnects = monitorClientDisconnects;
            this.state = DispatchState.HANDLING;
        }

        private synchronized ResponseOffer offerResponse(MicrohttpResponse response) {
            if (state == DispatchState.HANDLING) {
                pendingResponse = response;
                state = DispatchState.RESPONSE_PENDING;
                return new ResponseOffer(ResponseDisposition.ACCEPTED, StreamTerminationReason.INTERNAL_ERROR, null);
            }

            if (state == DispatchState.CANCELED) {
                StreamTerminationReason reason = cancelationReason == null
                        ? StreamTerminationReason.UNKNOWN
                        : cancelationReason;
                return new ResponseOffer(ResponseDisposition.CANCELED, reason, cancelationCause);
            }

            return new ResponseOffer(ResponseDisposition.DUPLICATE, StreamTerminationReason.INTERNAL_ERROR, null);
        }

        private synchronized ResponseOffer offerNullResponse() {
            if (state == DispatchState.HANDLING) {
                state = DispatchState.INVALID_RESPONSE_PENDING;
                return new ResponseOffer(ResponseDisposition.ACCEPTED, StreamTerminationReason.INTERNAL_ERROR, null);
            }

            if (state == DispatchState.CANCELED) {
                StreamTerminationReason reason = cancelationReason == null
                        ? StreamTerminationReason.UNKNOWN
                        : cancelationReason;
                return new ResponseOffer(ResponseDisposition.CANCELED, reason, cancelationCause);
            }

            return new ResponseOffer(ResponseDisposition.DUPLICATE, StreamTerminationReason.INTERNAL_ERROR, null);
        }

        private synchronized @Nullable MicrohttpResponse beginPreparation() {
            if (state != DispatchState.RESPONSE_PENDING) {
                return null;
            }

            MicrohttpResponse response = pendingResponse;
            pendingResponse = null;
            state = DispatchState.PREPARING;
            return response;
        }

        private synchronized void commit() {
            if (state != DispatchState.PREPARING) {
                throw new IllegalStateException("Cannot commit response from dispatch state " + state);
            }

            state = DispatchState.COMMITTED;
        }

        private synchronized @Nullable DispatchCancellation cancel(StreamTerminationReason reason,
                                                                    @Nullable Throwable cause) {
            if (state == DispatchState.COMMITTED || state == DispatchState.CANCELED) {
                return null;
            }

            MicrohttpResponse response = pendingResponse;
            pendingResponse = null;
            cancelationReason = reason;
            cancelationCause = cause;
            state = DispatchState.CANCELED;
            return new DispatchCancellation(response);
        }

        private synchronized boolean shouldMonitorClientDisconnects() {
            return monitorClientDisconnects
                    && (state == DispatchState.HANDLING
                    || state == DispatchState.RESPONSE_PENDING
                    || state == DispatchState.INVALID_RESPONSE_PENDING);
        }
    }

    private final Options options;
    private final Logger logger;
    private final Handler handler;
    private final AtomicLong connectionCounter;
    private final AtomicBoolean stop;
    private final AtomicBoolean draining;
    private final Consumer<Throwable> unexpectedTerminationHandler;
    private final byte[] badRequestResponse;
    private final byte[] expectationFailedResponse;
    private final byte[] requestHeaderFieldsTooLargeResponse;
    private final byte[] requestUriTooLongResponse;

    private final Scheduler timeoutQueue;
    private final Queue<Runnable> taskQueue;
    private final Queue<PendingRegistration> pendingRegistrations;
    private final ByteBuffer buffer;
    private final Selector selector;
    private final Thread thread;
    private final AtomicInteger connectionCount;
    private final AtomicInteger pendingRegistrationCount;
    private final AtomicBoolean registrationsClosed;
    private final Object lifecycleLock;
    private boolean started;
    private boolean closedBeforeStart;

    ConnectionEventLoop(
            Options options,
            Logger logger,
            Handler handler,
            AtomicLong connectionCounter,
            AtomicBoolean stop,
            AtomicBoolean draining,
            Consumer<Throwable> unexpectedTerminationHandler) throws IOException {
        this.options = options;
        this.logger = logger;
        this.handler = handler;
        this.connectionCounter = connectionCounter;
        this.stop = stop;
        this.draining = draining;
        this.unexpectedTerminationHandler = unexpectedTerminationHandler;
        this.badRequestResponse = rawErrorResponse(400, "Bad Request");
        this.expectationFailedResponse = rawErrorResponse(417, "Expectation Failed");
        this.requestHeaderFieldsTooLargeResponse = rawErrorResponse(
                431, "Request Header Fields Too Large");
        this.requestUriTooLongResponse = rawErrorResponse(414, "URI Too Long");

        connectionCount = new AtomicInteger();
        pendingRegistrationCount = new AtomicInteger();
        registrationsClosed = new AtomicBoolean();
        timeoutQueue = new Scheduler();
        taskQueue = new ConcurrentLinkedQueue<>();
        pendingRegistrations = new ConcurrentLinkedQueue<>();
        buffer = ByteBuffer.allocateDirect(options.readBufferSize());
        Selector openedSelector = Selector.open();
        Thread eventLoopThread;
        try {
            eventLoopThread = new Thread(this::run, "connection-event-loop");
        } catch (RuntimeException | Error throwable) {
            CloseUtils.closeQuietly(openedSelector);
            throw throwable;
        }
        selector = openedSelector;
        thread = eventLoopThread;
        lifecycleLock = new Object();
    }

    private class Connection {
        static final String HTTP_1_0 = "HTTP/1.0";
        static final String HTTP_1_1 = "HTTP/1.1";

        static final String HEADER_CONNECTION = "Connection";
        static final String HEADER_CONTENT_LENGTH = "Content-Length";
        static final String HEADER_TRANSFER_ENCODING = "Transfer-Encoding";

        static final String KEEP_ALIVE = "Keep-Alive";
        static final String CLOSE = "close";
        static final String CHUNKED = "chunked";

        static final byte[] CONTINUE_RESPONSE =
                "HTTP/1.1 100 Continue\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII);

        final SocketChannel socketChannel;
        final SelectionKey selectionKey;
        final ByteTokenizer byteTokenizer;
        final String id;
        final @Nullable InetSocketAddress remoteAddress;
        final EventLoop.ConnectionAdmission admission;
        RequestParser requestParser;
        @Nullable
        WritableSource writableSource;
        @Nullable
        MicrohttpResponse responseInDelivery;
        @Nullable
        ByteBuffer continueResponseBuffer;
        @Nullable
        Cancelable requestReadTimeoutTask;
        @Nullable
        Cancelable responseWriteIdleTimeoutTask;
        @Nullable
        InFlightDispatch inFlightDispatch;
        boolean requestReadTimeoutBodyPhase;
        long requestReadTimeoutTokenizerMark;
        boolean responseWriteIdleTimeoutEnabled;
        boolean httpOneDotZero;
        boolean headRequest;
        boolean keepAlive;
        boolean closeAfterResponse;
        boolean inputHalfClosed;
        boolean monitorClientDisconnectsDuringStreamingResponse;
        int streamingResponseBytesDiscarded;
        final AtomicBoolean closed;

        private Connection(SocketChannel socketChannel, SelectionKey selectionKey,
                           @Nullable InetSocketAddress remoteAddress,
                           EventLoop.ConnectionAdmission admission) throws IOException {
            this.socketChannel = socketChannel;
            this.selectionKey = selectionKey;
            byteTokenizer = new ByteTokenizer();
            id = Long.toString(connectionCounter.getAndIncrement());
            this.remoteAddress = remoteAddress;
            this.admission = admission;
            closed = new AtomicBoolean(false);
            requestParser = new RequestParser(byteTokenizer, remoteAddress, options.maxRequestBodySize(),
                    options.maxHeaderCount(), options.maxHeadersSize(), options.maxRequestTargetLength());
            scheduleRequestReadTimeoutForCurrentParserState();
        }

        private void onRequestReadTimeout() {
            // Policy: close quietly ONLY when no request data is in flight - nothing buffered (e.g. a
            // browser/LB preconnect or a clean idle keep-alive reap) AND no bytes arrived since this wait
            // began (the tokenizer mark was captured when the timeout was scheduled). Anything else - body
            // phase, buffered partial request bytes (including a stalled pipelined request), or bytes that
            // arrived during the wait - is a partial-request timeout and must be recorded; otherwise a
            // slow client could hold connection slots without ever appearing in transport-failure signals.
            if (requestDataInFlight() && logger.failureEnabled()) {
                logger.logFailure(
                        new LogEntry("event", "request_timeout"),
                        new LogEntry("id", id));
            }
            failSafeClose();
        }

        private void onReadable() {
            if (draining.get()) {
                InFlightDispatch dispatch = inFlightDispatch;

                if (dispatch != null) {
                    closeAfterResponse = true;
                    if (!dispatch.shouldMonitorClientDisconnects()) {
                        disableReadInterest();
                        return;
                    }
                    // Keep the explicitly opted-in bounded monitor active until response commitment. This
                    // detects an abortive disconnect and consumes bounded pipelined bytes, avoiding an RST
                    // when graceful drain closes the socket after the active response.
                } else if (writableSource != null) {
                    closeAfterResponse = true;
                    if (!monitorClientDisconnectsDuringStreamingResponse) {
                        disableReadInterest();
                        return;
                    }
                } else {
                    failSafeClose(StreamTerminationReason.SERVER_STOPPING, null);
                    return;
                }
            }

            try {
                doOnReadable();
            } catch (RequestTooLargeException e) {
                if (logger.failureEnabled()) {
                    logger.logFailure(
                            new LogEntry("event", "exceed_request_max_close"),
                            new LogEntry("id", id),
                            new LogEntry("request_size", Integer.toString(byteTokenizer.size())));
                }
                respondToRequestTooLarge(e);
            } catch (ExpectationFailedException e) {
                if (logger.failureEnabled()) {
                    logger.logFailure(e,
                            new LogEntry("event", "expectation_failed"),
                            new LogEntry("id", id));
                }
                respondToExpectationFailed();
            } catch (MalformedRequestException e) {
                if (logger.failureEnabled()) {
                    logger.logFailure(e,
                            new LogEntry("event", "malformed_request"),
                            new LogEntry("id", id));
                }
                respondToMalformedRequest();
            } catch (IOException | RuntimeException e) {
                if (shouldRecordReadFailure(e) && logger.failureEnabled()) {
                    logger.logFailure(e,
                            new LogEntry("event", "read_error"),
                            new LogEntry("id", id));
                }
                InFlightDispatch dispatch = inFlightDispatch;
                if (dispatch != null && dispatch.shouldMonitorClientDisconnects()) {
                    failSafeClose(StreamTerminationReason.CLIENT_DISCONNECTED, e);
                } else if (monitorClientDisconnectsDuringStreamingResponse && writableSource != null) {
                    failSafeClose(StreamTerminationReason.CLIENT_DISCONNECTED, e);
                } else {
                    failSafeClose();
                }
            }
        }

        private void doOnReadable() throws IOException {
            InFlightDispatch dispatch = inFlightDispatch;
            if (dispatch != null && dispatch.shouldMonitorClientDisconnects()) {
                doOnReadableWhileAwaitingResponse(dispatch);
                return;
            }
            if (monitorClientDisconnectsDuringStreamingResponse && writableSource != null) {
                doOnReadableDuringStreamingResponse();
                return;
            }

            buffer.clear();
            int numBytes = socketChannel.read(buffer);
            if (numBytes < 0) {
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "read_close"),
                            new LogEntry("id", id));
                }
                failSafeClose(StreamTerminationReason.CLIENT_DISCONNECTED, null);
                return;
            }
            buffer.flip();
            byteTokenizer.add(buffer);
            if (logger.enabled()) {
                logger.log(
                        new LogEntry("event", "read_bytes"),
                        new LogEntry("id", id),
                        new LogEntry("read_bytes", Integer.toString(numBytes)),
                        new LogEntry("request_bytes", Integer.toString(byteTokenizer.remaining())));
            }
            if (requestParser.parse()) {
                if (byteTokenizer.position() > options.maxRequestSize()) {
                    if (logger.failureEnabled()) {
                        logger.logFailure(
                                new LogEntry("event", "exceed_request_max_close"),
                                new LogEntry("id", id),
                                new LogEntry("request_size", Integer.toString(byteTokenizer.position())));
                    }
                    respondToRequestTooLarge(RequestTooLargeException.Reason.CONTENT);
                    return;
                }
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "read_request"),
                            new LogEntry("id", id),
                            new LogEntry("request_bytes", Integer.toString(byteTokenizer.remaining())));
                }
                onParseRequest();
            } else {
                if (byteTokenizer.size() > options.maxRequestSize()) {
                    if (logger.failureEnabled()) {
                        logger.logFailure(
                                new LogEntry("event", "exceed_request_max_close"),
                                new LogEntry("id", id),
                                new LogEntry("request_size", Integer.toString(byteTokenizer.size())));
                    }

                    respondToRequestTooLarge(RequestTooLargeException.Reason.CONTENT);
                } else {
                    onPartialRequestParsed();
                }
            }
        }

        private void doOnReadableWhileAwaitingResponse(InFlightDispatch dispatch) throws IOException {
            if (dispatch != inFlightDispatch || !dispatch.shouldMonitorClientDisconnects()) {
                disableReadInterest();
                return;
            }

            buffer.clear();
            int remainingCapacity = options.maxRequestSize() - byteTokenizer.size();
            boolean overflowProbe = remainingCapacity <= 0;
            buffer.limit(overflowProbe ? 1 : Math.min(buffer.capacity(), remainingCapacity));
            int numBytes = socketChannel.read(buffer);

            if (numBytes < 0) {
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "read_half_close_while_response_pending"),
                            new LogEntry("id", id));
                }
                // EOF is only a half-close of the client's sending side. The client may still be waiting
                // to read responses. Retain and drain complete pipelined requests already in memory, but
                // never return to socket reads once those bytes are exhausted.
                inputHalfClosed = true;
                if (byteTokenizer.size() == 0) {
                    closeAfterResponse = true;
                }
                disableReadInterest();
                return;
            }

            if (numBytes == 0) {
                return;
            }

            if (overflowProbe) {
                if (logger.failureEnabled()) {
                    logger.logFailure(
                            new LogEntry("event", "exceed_request_max_close"),
                            new LogEntry("id", id),
                            new LogEntry("request_size", Long.toString((long) options.maxRequestSize() + 1L)));
                }
                failSafeClose(StreamTerminationReason.BACKPRESSURE, null);
                return;
            }

            buffer.flip();
            byteTokenizer.add(buffer);

            if (logger.enabled()) {
                logger.log(
                        new LogEntry("event", "read_bytes_while_response_pending"),
                        new LogEntry("id", id),
                        new LogEntry("read_bytes", Integer.toString(numBytes)),
                        new LogEntry("buffered_request_bytes", Integer.toString(byteTokenizer.size())));
            }
        }

        private void doOnReadableDuringStreamingResponse() throws IOException {
            if (!monitorClientDisconnectsDuringStreamingResponse || writableSource == null) {
                disableReadInterest();
                return;
            }

            buffer.clear();
            int remainingCapacity = options.maxRequestSize() - streamingResponseBytesDiscarded;
            boolean overflowProbe = remainingCapacity <= 0;
            buffer.limit(overflowProbe ? 1 : Math.min(buffer.capacity(), remainingCapacity));
            int numBytes = socketChannel.read(buffer);

            if (numBytes < 0) {
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "read_half_close_during_streaming_response"),
                            new LogEntry("id", id));
                }
                // EOF closes only the client's sending side. The committed response may continue
                // writing indefinitely; no future request can arrive on this connection.
                inputHalfClosed = true;
                monitorClientDisconnectsDuringStreamingResponse = false;
                disableReadInterest();
                return;
            }

            if (numBytes == 0) {
                return;
            }

            if (overflowProbe) {
                if (logger.failureEnabled()) {
                    logger.logFailure(
                            new LogEntry("event", "streaming_response_read_limit_close"),
                            new LogEntry("id", id),
                            new LogEntry("discarded_bytes",
                                    Long.toString((long) options.maxRequestSize() + 1L)));
                }
                failSafeClose(StreamTerminationReason.BACKPRESSURE, null);
                return;
            }

            streamingResponseBytesDiscarded += numBytes;
            // These bytes intentionally bypass ByteTokenizer. If this finite stream completes,
            // close instead of ever treating discarded input as a subsequent HTTP request.
            closeAfterResponse = true;

            if (logger.enabled()) {
                logger.log(
                        new LogEntry("event", "read_bytes_during_streaming_response"),
                        new LogEntry("id", id),
                        new LogEntry("read_bytes", Integer.toString(numBytes)),
                        new LogEntry("discarded_bytes", Integer.toString(streamingResponseBytesDiscarded)));
            }
        }

        private void respondToRequestTooLarge(RequestTooLargeException exception) {
            respondToRequestTooLarge(exception.reason());
        }

        private void respondToRequestTooLarge(RequestTooLargeException.Reason reason) {
            if (reason == RequestTooLargeException.Reason.HEADERS) {
                respondWithRawError(requestHeaderFieldsTooLargeResponse);
                return;
            }

            if (reason == RequestTooLargeException.Reason.URI_TOO_LONG) {
                respondWithRawError(requestUriTooLongResponse);
                return;
            }

            if (selectionKey.isValid() && selectionKey.interestOps() != 0) {
                selectionKey.interestOps(0);
            }

            if (requestReadTimeoutTask != null) {
                requestReadTimeoutTask.cancel();
                requestReadTimeoutTask = null;
            }

            MicrohttpRequest request = requestParser.request();

            if (request.method() == null || request.uri() == null || request.version() == null) {
                failSafeClose();
                return;
            }

            List<Header> headers = request.headers() == null ? new ArrayList<>(0) : new ArrayList<>(request.headers());
            MicrohttpRequest tooLargeRequest = new MicrohttpRequest(request.method(), request.uri(), request.version(), headers, new byte[0], true, remoteAddress);

            applyConnectionPolicy(tooLargeRequest);
            closeAfterResponse = true;
            byteTokenizer.compact();
            requestParser.reset();
            dispatchRequest(tooLargeRequest);
        }

        private void respondToMalformedRequest() {
            respondWithRawError(badRequestResponse);
        }

        private void respondToExpectationFailed() {
            respondWithRawError(expectationFailedResponse);
        }

        private void respondWithRawError(byte[] response) {
            if (selectionKey.isValid() && selectionKey.interestOps() != 0) {
                selectionKey.interestOps(0);
            }
            if (requestReadTimeoutTask != null) {
                requestReadTimeoutTask.cancel();
                requestReadTimeoutTask = null;
            }
            cancelResponseWriteIdleTimeout();
            closeAfterResponse = true;
            writableSource = new ByteBufferWritableSource(ByteBuffer.wrap(response));
            try {
                doOnWritable();
            } catch (IOException e) {
                failSafeClose();
            }
        }

        private void onParseRequest() {
            if (selectionKey.isValid() && selectionKey.interestOps() != 0) {
                selectionKey.interestOps(0);
            }
            if (requestReadTimeoutTask != null) {
                requestReadTimeoutTask.cancel();
                requestReadTimeoutTask = null;
            }
            MicrohttpRequest request = requestParser.request();
            applyConnectionPolicy(request);
            if (inputHalfClosed && byteTokenizer.remaining() == 0) {
                closeAfterResponse = true;
            }
            byteTokenizer.compact();
            requestParser.reset();
            dispatchRequest(request);
        }

        private void dispatchRequest(MicrohttpRequest request) {
            boolean monitorClientDisconnects;

            try {
                monitorClientDisconnects = handler.monitorClientDisconnectsBeforeResponse(request);
            } catch (Throwable throwable) {
                logThrowable(throwable,
                        new LogEntry("event", "request_disconnect_monitor_error"),
                        new LogEntry("id", id));
                failSafeClose(StreamTerminationReason.INTERNAL_ERROR, throwable);
                return;
            }

            InFlightDispatch dispatch = new InFlightDispatch(request, monitorClientDisconnects);
            inFlightDispatch = dispatch;

            if (monitorClientDisconnects) {
                enableReadInterestForDisconnectMonitoring();
            }

            try {
                handler.handle(request, response -> onResponse(dispatch, response));
            } catch (Throwable throwable) {
                logThrowable(throwable,
                        new LogEntry("event", "request_handler_error"),
                        new LogEntry("id", id));
                failSafeClose(StreamTerminationReason.INTERNAL_ERROR, throwable);
            }
        }

        private void onResponse(InFlightDispatch dispatch, @Nullable MicrohttpResponse microhttpResponse) {
            if (microhttpResponse == null) {
                ResponseOffer offer = dispatch.offerNullResponse();

                if (offer.disposition == ResponseDisposition.ACCEPTED) {
                    queueConnectionTask("response_ready_error", () -> {
                        throw new NullPointerException("Handler response callback received null");
                    });
                    wakeupSelectorForCallback();
                } else if (offer.disposition == ResponseDisposition.DUPLICATE) {
                    logDuplicateResponse();
                }
                return;
            }

            ResponseOffer offer = dispatch.offerResponse(microhttpResponse);

            if (offer.disposition != ResponseDisposition.ACCEPTED) {
                if (offer.disposition == ResponseDisposition.DUPLICATE) {
                    logDuplicateResponse();
                }
                discardResponse(microhttpResponse, offer.discardReason, offer.discardCause);
                return;
            }

            // enqueuing the callback invocation and waking the selector
            // ensures that the microhttpResponse callback works properly when
            // invoked inline from the event loop thread or a separate background thread
            queueConnectionTask("response_ready_error", () -> prepareToWriteResponse(dispatch));
            wakeupSelectorForCallback();
        }

        private void logDuplicateResponse() {
            try {
                if (logger.failureEnabled()) {
                    logger.logFailure(
                            new LogEntry("event", "duplicate_response"),
                            new LogEntry("id", id));
                }
            } catch (Throwable ignored) {
                // Logging must not affect callback ownership.
            }
        }

        private void wakeupSelectorForCallback() {
            // selector wakeup is not necessary if callback was invoked within event loop thread
            // since scheduler tasks are processed at the end of every event loop iteration
            if (Thread.currentThread() != thread) {
                selector.wakeup();
            }
        }

        private void prepareToWriteResponse(InFlightDispatch dispatch) throws Exception {
            MicrohttpResponse microhttpResponse = dispatch.beginPreparation();

            if (microhttpResponse == null) {
                return;
            }

            if (closed.get()) {
                cancelDispatch(dispatch, StreamTerminationReason.SERVER_STOPPING, null);
                discardResponse(microhttpResponse, StreamTerminationReason.SERVER_STOPPING, null);
                return;
            }

            responseInDelivery = microhttpResponse;
            boolean committed = false;
            boolean bodyOwnershipAttempted = false;
            boolean monitorStreamingResponse = false;

            try {
                if (microhttpResponse.streaming() && httpOneDotZero) {
                    bodyOwnershipAttempted = true;
                    microhttpResponse.closeStreamingBody(StreamTerminationReason.PROTOCOL_UNSUPPORTED, null);
                    microhttpResponse = new MicrohttpResponse(505, "HTTP Version Not Supported",
                            List.of(new Header(HEADER_CONNECTION, CLOSE)), new byte[0]);
                    closeAfterResponse = true;
                }
                if (mustNotSendBody(microhttpResponse.status())) {
                    bodyOwnershipAttempted = true;
                    microhttpResponse.closeBody(StreamTerminationReason.PROTOCOL_UNSUPPORTED, null);
                    microhttpResponse = microhttpResponse.withoutBodyOrFramingHeaders();
                }
                // RFC 9110 §9.3.2: HEAD responses carry no content. The normal marshaling path strips
                // HEAD bodies before reaching this layer; this guards responses that bypass it (e.g.
                // canned failsafe responses and error-path marshaling), preserving the hypothetical
                // Content-Length while omitting the bytes so a keep-alive client cannot desync. Raw
                // parse-error responses are exempt: they always close the connection and may predate
                // method parsing. headRequest is set by applyConnectionPolicy at every dispatch site.
                if (headRequest) {
                    if (microhttpResponse.streaming()) {
                        bodyOwnershipAttempted = true;
                        microhttpResponse.closeStreamingBody(StreamTerminationReason.PROTOCOL_UNSUPPORTED, null);
                        microhttpResponse = microhttpResponse.withoutBodyOrFramingHeaders();
                    } else if (microhttpResponse.bodyLength() > 0) {
                        bodyOwnershipAttempted = true;
                        microhttpResponse = microhttpResponse.withBodyOmittedForHead();
                    }
                }
                if (hasHeaderToken(microhttpResponse.headers(), HEADER_CONNECTION, CLOSE)) {
                    closeAfterResponse = true;
                }
                if (draining.get()) {
                    closeAfterResponse = true;
                }
                if (microhttpResponse.streaming()) {
                    monitorStreamingResponse =
                            handler.monitorClientDisconnectsDuringStreamingResponse(dispatch.request);
                    if (monitorStreamingResponse && byteTokenizer.size() > 0) {
                        // Bytes already coalesced with the request predate committed monitoring but
                        // must obey the same no-pipelining contract.
                        closeAfterResponse = true;
                    }
                }
                responseWriteIdleTimeoutEnabled = !microhttpResponse.streaming()
                        && !options.responseWriteIdleTimeout().isZero();
                String version = httpOneDotZero ? HTTP_1_0 : HTTP_1_1;
                List<Header> headers = new ArrayList<>();
                if (httpOneDotZero && keepAlive && !closeAfterResponse) {
                    headers.add(new Header(HEADER_CONNECTION, KEEP_ALIVE));
                }
                if (closeAfterResponse && !hasHeaderToken(microhttpResponse.headers(), HEADER_CONNECTION, CLOSE)) {
                    headers.add(new Header(HEADER_CONNECTION, CLOSE));
                }
                if (microhttpResponse.streaming()) {
                    if (!microhttpResponse.hasHeader(HEADER_TRANSFER_ENCODING)) {
                        headers.add(new Header(HEADER_TRANSFER_ENCODING, CHUNKED));
                    }
                } else if (shouldAddContentLength(microhttpResponse)) {
                    headers.add(new Header(HEADER_CONTENT_LENGTH, Long.toString(microhttpResponse.bodyLength())));
                }
                byte[] serializedHead = microhttpResponse.serializeHead(version, headers);
                disableReadInterest();
                bodyOwnershipAttempted = true;
                WritableSource preparedSource = microhttpResponse.writableSource(serializedHead);

                // Creating the complete source is the last fallible ownership step before commitment.
                // Once installed, all later failures belong to the source and must not call Handler.cancel.
                writableSource = preparedSource;
                dispatch.commit();
                committed = true;
                if (inFlightDispatch == dispatch) {
                    inFlightDispatch = null;
                }
                monitorClientDisconnectsDuringStreamingResponse = monitorStreamingResponse;
                streamingResponseBytesDiscarded = 0;
                if (monitorStreamingResponse) {
                    enableReadInterestForDisconnectMonitoring();
                }

                preparedSource.writeReadyCallback(this::onWritableSourceReady);
                preparedSource.start();
                resetResponseWriteIdleTimeoutIfNeeded();
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "response_ready"),
                            new LogEntry("id", id),
                            new LogEntry("num_bytes", Long.toString((long) serializedHead.length + microhttpResponse.bodyLength())));
                }
                // Ownership is committed before the first socket write. Route that write through
                // the same failure classifier as later selector- and callback-driven writes so a
                // scheduling detail cannot change the source's termination reason.
                onWritable();
            } catch (Throwable throwable) {
                if (!committed) {
                    cancelDispatch(dispatch, StreamTerminationReason.INTERNAL_ERROR, throwable);
                    if (!bodyOwnershipAttempted) {
                        responseInDelivery = null;
                        discardResponse(microhttpResponse, StreamTerminationReason.INTERNAL_ERROR, throwable);
                    }
                }

                if (throwable instanceof Exception exception) {
                    throw exception;
                }
                if (throwable instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(throwable);
            }
        }

        private void onWritable() {
            try {
                if (continueResponseBuffer == null) {
                    doOnWritable();
                } else {
                    doOnWritableContinueResponse();
                }
            } catch (IOException | RuntimeException e) {
                if (logger.failureEnabled()) {
                    logger.logFailure(e,
                            new LogEntry("event", "write_error"),
                            new LogEntry("id", id));
                }
                failSafeClose(StreamTerminationReason.WRITE_FAILED, e);
            }
        }

        private void onWritableSourceReady() {
            queueConnectionTask("write_error", () -> {
                if (closed.get() || writableSource == null || !selectionKey.isValid()) {
                    return;
                }
                if ((selectionKey.interestOps() & SelectionKey.OP_WRITE) == 0) {
                    selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                }
                onWritable();
            });
            if (Thread.currentThread() != thread) {
                selector.wakeup();
            }
        }

        private void doOnWritable() throws IOException {
            WritableSource activeWritableSource = writableSource;
            if (activeWritableSource == null) {
                failSafeClose();
                return;
            }

            long numBytes = activeWritableSource.writeTo(socketChannel, MAX_RESPONSE_BYTES_PER_WRITE_TURN);
            if (numBytes > 0) {
                resetResponseWriteIdleTimeoutIfNeeded();
            }
            if (!activeWritableSource.hasRemaining()) { // response fully written
                activeWritableSource.close();
                writableSource = null; // done with current write source, remove reference
                MicrohttpResponse deliveredResponse = responseInDelivery;
                responseInDelivery = null;
                monitorClientDisconnectsDuringStreamingResponse = false;
                streamingResponseBytesDiscarded = 0;
                cancelResponseWriteIdleTimeout();
                if (deliveredResponse != null)
                    deliveredResponse.reserveBodyTermination(StreamTerminationReason.COMPLETED, null);

                try {
                    if (logger.enabled()) {
                        logger.log(
                                new LogEntry("event", "write_response"),
                                new LogEntry("id", id),
                                new LogEntry("num_bytes", Long.toString(numBytes)));
                    }
                    if (closeAfterResponse) { // non-persistent connection, close now
                        if (logger.enabled()) {
                            logger.log(
                                new LogEntry("event", "close_after_response"),
                                new LogEntry("id", id));
                        }
                        failSafeClose();
                    }
                } finally {
                    if (deliveredResponse != null)
                        deliverBodyTermination(deliveredResponse);
                }

                if (!closeAfterResponse) { // persistent connection
                    parseBufferedRequestAfterResponse();
                }
            } else { // response not fully written, switch to or remain in write mode
                if (!selectionKey.isValid()) {
                    failSafeClose();
                    return;
                }
                if (activeWritableSource.isReadyToWrite()) {
                    if ((selectionKey.interestOps() & SelectionKey.OP_WRITE) == 0) {
                        selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                    }
                } else if ((selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0) {
                    selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
                }
                if (logger.enabled()) {
                    logger.log(
                        new LogEntry("event", "write"),
                        new LogEntry("id", id),
                        new LogEntry("num_bytes", Long.toString(numBytes)));
                }
            }
        }

        private void onPartialRequestParsed() {
            if (inputHalfClosed) {
                failSafeClose();
                return;
            }

            scheduleRequestReadTimeoutForCurrentParserState();

            if (requestParser.consumeContinueExpectation()) {
                continueResponseBuffer = ByteBuffer.wrap(CONTINUE_RESPONSE);
                try {
                    doOnWritableContinueResponse();
                } catch (IOException e) {
                    if (logger.failureEnabled()) {
                        logger.logFailure(e,
                                new LogEntry("event", "write_error"),
                                new LogEntry("id", id));
                    }
                    failSafeClose();
                }
                return;
            }

            if (!selectionKey.isValid()) {
                failSafeClose();
                return;
            }
            selectionKey.interestOps(SelectionKey.OP_READ);
        }

        private void doOnWritableContinueResponse() throws IOException {
            ByteBuffer activeContinueResponseBuffer = continueResponseBuffer;
            if (activeContinueResponseBuffer == null) {
                return;
            }

            socketChannel.write(activeContinueResponseBuffer);
            if (activeContinueResponseBuffer.hasRemaining()) {
                if (!selectionKey.isValid()) {
                    failSafeClose();
                    return;
                }
                selectionKey.interestOps(SelectionKey.OP_WRITE);
                return;
            }

            continueResponseBuffer = null;
            if (!selectionKey.isValid()) {
                failSafeClose();
                return;
            }
            selectionKey.interestOps(SelectionKey.OP_READ);
        }

        private void parseBufferedRequestAfterResponse() {
            if (draining.get()) {
                failSafeClose(StreamTerminationReason.SERVER_STOPPING, null);
                return;
            }

            try {
                if (requestParser.parse()) { // subsequent request in buffer
                    if (byteTokenizer.position() > options.maxRequestSize()) {
                        if (logger.failureEnabled()) {
                            logger.logFailure(
                                    new LogEntry("event", "exceed_request_max_close"),
                                    new LogEntry("id", id),
                                    new LogEntry("request_size", Integer.toString(byteTokenizer.position())));
                        }
                        respondToRequestTooLarge(RequestTooLargeException.Reason.CONTENT);
                        return;
                    }
                    if (logger.enabled()) {
                        logger.log(
                                new LogEntry("event", "pipeline_request"),
                                new LogEntry("id", id),
                                new LogEntry("request_bytes", Integer.toString(byteTokenizer.remaining())));
                    }
                    onParseRequest();
                } else { // switch back to read mode
                    onPartialRequestParsed();
                }
            } catch (RequestTooLargeException e) {
                if (logger.failureEnabled()) {
                    logger.logFailure(
                            new LogEntry("event", "exceed_request_max_close"),
                            new LogEntry("id", id),
                            new LogEntry("request_size", Integer.toString(byteTokenizer.size())));
                }
                respondToRequestTooLarge(e);
            } catch (ExpectationFailedException e) {
                if (logger.failureEnabled()) {
                    logger.logFailure(e,
                            new LogEntry("event", "expectation_failed"),
                            new LogEntry("id", id));
                }
                respondToExpectationFailed();
            } catch (MalformedRequestException e) {
                if (logger.failureEnabled()) {
                    logger.logFailure(e,
                            new LogEntry("event", "malformed_request"),
                            new LogEntry("id", id));
                }
                respondToMalformedRequest();
            }
        }

        private void enableReadInterestForDisconnectMonitoring() {
            if (!selectionKey.isValid() || inputHalfClosed) {
                return;
            }

            if ((selectionKey.interestOps() & SelectionKey.OP_READ) == 0) {
                selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
            }
        }

        private void disableReadInterest() {
            if (!selectionKey.isValid()) {
                return;
            }

            if ((selectionKey.interestOps() & SelectionKey.OP_READ) != 0) {
                selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ);
            }
        }

        private void cancelDispatch(InFlightDispatch dispatch, StreamTerminationReason reason,
                                    @Nullable Throwable cause) {
            DispatchCancellation cancellation = dispatch.cancel(reason, cause);

            if (cancellation == null) {
                return;
            }

            if (inFlightDispatch == dispatch) {
                inFlightDispatch = null;
            }

            try {
                handler.cancel(dispatch.request, reason, cause);
            } catch (Throwable throwable) {
                logThrowable(throwable,
                        new LogEntry("event", "request_cancel_error"),
                        new LogEntry("id", id));
            }

            if (cancellation.pendingResponse != null) {
                discardResponse(cancellation.pendingResponse, reason, cause);
            }
        }

        private void discardResponse(MicrohttpResponse response, StreamTerminationReason reason,
                                     @Nullable Throwable cause) {
            Throwable closeFailure = null;

            try {
                response.closeBody(reason, cause);
            } catch (Throwable throwable) {
                closeFailure = throwable;
                logThrowable(throwable,
                        new LogEntry("event", "response_discard_error"),
                        new LogEntry("id", id));
            } finally {
                response.reserveBodyTermination(reason, cause == null ? closeFailure : cause);
                deliverBodyTermination(response);
            }
        }

        private void deliverBodyTermination(MicrohttpResponse response) {
            try {
                response.deliverBodyTermination();
            } catch (Throwable throwable) {
                logThrowable(throwable,
                        new LogEntry("event", "response_termination_listener_error"),
                        new LogEntry("id", id));
            }
        }

        private void failSafeClose() {
            failSafeClose(null, null);
        }

        private void failSafeClose(@Nullable StreamTerminationReason cancelationReason, @Nullable Throwable cause) {
            if (!closed.compareAndSet(false, true))
                return;

            StreamTerminationReason effectiveReason = cancelationReason == null
                    ? StreamTerminationReason.CLIENT_DISCONNECTED
                    : cancelationReason;
            InFlightDispatch dispatch = inFlightDispatch;
            inFlightDispatch = null;
            MicrohttpResponse response = responseInDelivery;
            responseInDelivery = null;
            Throwable responseCloseFailure = null;

            try {
                if (dispatch != null) {
                    cancelDispatch(dispatch, effectiveReason, cause);
                }
                if (requestReadTimeoutTask != null) {
                    requestReadTimeoutTask.cancel();
                    requestReadTimeoutTask = null;
                }
                cancelResponseWriteIdleTimeout();
                if (writableSource != null) {
                    WritableSource source = writableSource;
                    writableSource = null;
                    try {
                        source.close(effectiveReason, cause);
                    } catch (Throwable throwable) {
                        responseCloseFailure = throwable;
                        logThrowable(throwable,
                                new LogEntry("event", "response_close_error"),
                                new LogEntry("id", id));
                    }
                }
                continueResponseBuffer = null;
                monitorClientDisconnectsDuringStreamingResponse = false;
                streamingResponseBytesDiscarded = 0;
            } finally {
                try {
                    selectionKey.cancel();
                } finally {
                    CloseUtils.closeQuietly(socketChannel);
                    connectionCount.decrementAndGet();
                    admission.release();
                }
            }

            if (response != null) {
                response.reserveBodyTermination(effectiveReason,
                        cause == null ? responseCloseFailure : cause);
                deliverBodyTermination(response);
            }
        }

        private void scheduleRequestReadTimeoutForCurrentParserState() {
            boolean bodyPhase = requestParser.readingBody();

            if (requestReadTimeoutTask != null && requestReadTimeoutBodyPhase == bodyPhase) {
                return;
            }

            Duration timeout = bodyPhase
                    ? options.requestBodyTimeout()
                    : options.requestHeaderTimeout();

            if (requestReadTimeoutTask != null) {
                requestReadTimeoutTask.cancel();
            }

            requestReadTimeoutBodyPhase = bodyPhase;
            requestReadTimeoutTokenizerMark = byteTokenizer.totalBytesAdded();
            requestReadTimeoutTask = timeoutQueue.schedule(() -> runConnectionTask("request_timeout_error", this::onRequestReadTimeout), timeout);
        }

        private boolean shouldRecordReadFailure(Throwable throwable) {
            return requestDataInFlight() || !isRemoteClose(throwable);
        }

        private boolean requestDataInFlight() {
            return requestReadTimeoutBodyPhase
                    || byteTokenizer.size() > 0
                    || byteTokenizer.totalBytesAdded() > requestReadTimeoutTokenizerMark;
        }

        private void onResponseWriteIdleTimeout() {
            if (logger.failureEnabled()) {
                logger.logFailure(
                        new LogEntry("event", "response_write_idle_timeout"),
                        new LogEntry("id", id));
            }
            failSafeClose();
        }

        private void resetResponseWriteIdleTimeoutIfNeeded() {
            if (!responseWriteIdleTimeoutEnabled) {
                return;
            }

            cancelResponseWriteIdleTimeout();
            responseWriteIdleTimeoutTask = timeoutQueue.schedule(
                    () -> runConnectionTask("response_write_idle_timeout_error", this::onResponseWriteIdleTimeout),
                    options.responseWriteIdleTimeout());
        }

        private void cancelResponseWriteIdleTimeout() {
            if (responseWriteIdleTimeoutTask != null) {
                responseWriteIdleTimeoutTask.cancel();
                responseWriteIdleTimeoutTask = null;
            }
        }

        private void queueConnectionTask(String failureEvent, ThrowingTask task) {
            taskQueue.add(() -> runConnectionTask(failureEvent, task));
        }

        private void runConnectionTask(String failureEvent, ThrowingTask task) {
            try {
                task.run();
            } catch (Throwable throwable) {
                logThrowable(throwable,
                        new LogEntry("event", failureEvent),
                        new LogEntry("id", id));
                failSafeClose(StreamTerminationReason.INTERNAL_ERROR, throwable);
            }
        }

        private void beginDrain() {
            if (closed.get())
                return;

            InFlightDispatch dispatch = inFlightDispatch;

            if (dispatch != null) {
                closeAfterResponse = true;
                if (!dispatch.shouldMonitorClientDisconnects())
                    disableReadInterest();
                return;
            }

            if (writableSource != null) {
                closeAfterResponse = true;
                disableReadInterest();
                return;
            }

            failSafeClose(StreamTerminationReason.SERVER_STOPPING, null);
        }

        private void applyConnectionPolicy(MicrohttpRequest request) {
            closeAfterResponse = false;
            httpOneDotZero = request.version().equalsIgnoreCase(HTTP_1_0);
            headRequest = "HEAD".equalsIgnoreCase(request.method());

            boolean hasClose = hasHeaderToken(request.headers(), HEADER_CONNECTION, CLOSE);
            boolean hasKeepAlive = hasHeaderToken(request.headers(), HEADER_CONNECTION, KEEP_ALIVE);

            if (hasClose) {
                keepAlive = false;
                closeAfterResponse = true;
            } else if (httpOneDotZero) {
                keepAlive = hasKeepAlive;
                closeAfterResponse = !keepAlive;
            } else {
                keepAlive = true;
            }
        }

        private boolean shouldAddContentLength(MicrohttpResponse microhttpResponse) {
            if (microhttpResponse.hasHeader(HEADER_CONTENT_LENGTH)) {
                return false;
            }

            if (mustNotSendBody(microhttpResponse.status())) {
                return false;
            }

            return !headRequest || microhttpResponse.bodyLength() > 0L;
        }

        private boolean mustNotSendBody(int status) {
            return (status >= 100 && status < 200) || status == 204 || status == 304;
        }

        private boolean hasHeaderToken(@Nullable List<Header> headers, String headerName, String token) {
            if (headers == null) {
                return false;
            }
            for (Header header : headers) {
                if (!header.name().equalsIgnoreCase(headerName)) {
                    continue;
                }
                String value = header.value();
                if (value == null) {
                    continue;
                }
                for (String part : value.split(",", -1)) {
                    if (token.equalsIgnoreCase(part.trim())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    int numConnections() {
        return connectionCount.get();
    }

    int numPendingRegistrations() {
        return pendingRegistrationCount.get();
    }

    int connectionLoad() {
        return numConnections() + numPendingRegistrations();
    }

    private static boolean isRemoteClose(@Nullable Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof ClosedChannelException)
                return true;
            if (current instanceof EOFException)
                return true;
            if (current instanceof IOException) {
                String message = current.getMessage();

                if (message != null) {
                    String normalized = message.toLowerCase(Locale.ROOT);

                    if (normalized.contains("broken pipe")
                            || normalized.contains("connection reset")
                            || normalized.contains("connection aborted")
                            || normalized.contains("connection reset by peer")
                            || normalized.contains("software caused connection abort")
                            || normalized.contains("socket closed"))
                        return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    void start() {
        synchronized (lifecycleLock) {
            if (closedBeforeStart) {
                return;
            }
            if (started) {
                throw new IllegalStateException("Connection event loop has already been started.");
            }

            started = true;
            try {
                thread.start();
            } catch (RuntimeException | Error throwable) {
                started = false;
                closedBeforeStart = true;
                registrationsClosed.set(true);
                closePendingRegistrations();
                CloseUtils.closeQuietly(selector);
                throw throwable;
            }
        }
    }

    void wakeup() {
        selector.wakeup();
    }

    void join() throws InterruptedException {
        thread.join();
    }

    boolean joinUntil(long deadlineNanos) throws InterruptedException {
        while (thread.isAlive()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L)
                return false;
            long millis = remainingNanos / 1_000_000L;
            int nanos = (int) (remainingNanos % 1_000_000L);
            thread.join(millis, nanos);
        }
        return true;
    }

    boolean isTerminated() {
        return !thread.isAlive();
    }

    void beginDrain() {
        taskQueue.add(() -> {
            for (SelectionKey selKey : selector.keys()) {
                Object attachment = selKey.attachment();
                if (attachment instanceof Connection connection) {
                    connection.beginDrain();
                }
            }
        });
        selector.wakeup();
    }

    private void run() {
        try {
            doStart();
        } catch (Throwable throwable) {
            try {
                if (logger.failureEnabled()) {
                    logger.logFailure(throwable, new LogEntry("event", "sub_event_loop_terminate"));
                }
            } catch (Throwable ignored) {
                // No safe fallback sink is available from the connection-event-loop thread.
            }
            stop.set(true); // stop the world on critical error
            try {
                unexpectedTerminationHandler.accept(throwable);
            } catch (Throwable ignored) {
                // The parent event loop owns reporting; no secondary failure may escape here.
            }
        } finally {
            registrationsClosed.set(true);
            closePendingRegistrations();
            try {
                for (SelectionKey selKey : selector.keys()) {
                    Object attachment = selKey.attachment();
                    if (attachment instanceof Connection connection) {
                        connection.failSafeClose(StreamTerminationReason.SERVER_STOPPING, null);
                    }
                }
            } catch (ClosedSelectorException ignored) {
                // A fatal selector failure may already have closed it.
            }
            CloseUtils.closeQuietly(selector);
        }
    }

    private byte[] rawErrorResponse(int status, String reason) {
        StringBuilder response = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Connection: close\r\n")
                .append("Content-Length: 0\r\n");
        for (Header header : options.earlyErrorResponseHeaders()) {
            if (!validEarlyErrorHeader(header))
                throw new IllegalArgumentException("Invalid early-error response header.");
            response.append(header.name()).append(": ").append(header.value()).append("\r\n");
        }
        response.append("\r\n");
        return response.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private boolean validEarlyErrorHeader(Header header) {
        if (header == null || header.name() == null || header.value() == null
                || header.name().isEmpty())
            return false;

        String name = header.name();
        if ("connection".equalsIgnoreCase(name)
                || "content-length".equalsIgnoreCase(name)
                || "transfer-encoding".equalsIgnoreCase(name))
            return false;

        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && "!#$%&'*+-.^_`|~".indexOf(character) < 0)
                return false;
        }

        for (int index = 0; index < header.value().length(); index++) {
            char character = header.value().charAt(index);
            if (character < 0x20 || character > 0x7E)
                return false;
        }
        return true;
    }

    private void doStart() throws IOException {
        while (!stop.get()) {
            selector.select(options.resolution().toMillis());
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectedKeys.iterator();
            while (it.hasNext()) {
                SelectionKey selKey = it.next();
                try {
                    if (!selKey.isValid()) {
                        continue;
                    }
                    Object attachment = selKey.attachment();
                    if (attachment instanceof Connection connection) {
                        if (selKey.isReadable()) {
                            connection.runConnectionTask("read_error", connection::onReadable);
                        } else if (selKey.isWritable()) {
                            connection.runConnectionTask("write_error", connection::onWritable);
                        }
                    }
                } catch (Throwable throwable) {
                    logThrowable(throwable, new LogEntry("event", "selection_key_error"));
                    Object attachment = selKey.attachment();
                    if (attachment instanceof Connection connection) {
                        connection.failSafeClose();
                    } else {
                        selKey.cancel();
                    }
                } finally {
                    it.remove();
                }
            }
            timeoutQueue.expired().forEach(task -> runLoopTask(task, "timeout_task_error"));
            Runnable task;
            while ((task = taskQueue.poll()) != null) {
                runLoopTask(task, "task_error");
            }
        }
    }

    void register(SocketChannel socketChannel, EventLoop.ConnectionAdmission admission) {
        PendingRegistration pendingRegistration = new PendingRegistration(socketChannel, admission);
        pendingRegistrationCount.incrementAndGet();
        pendingRegistrations.add(pendingRegistration);

        if (registrationsClosed.get()) {
            closePendingRegistration(pendingRegistration);
            return;
        }

        taskQueue.add(() -> processPendingRegistration(pendingRegistration));

        // If shutdown raced with adding the task after its final queue drain, the registering
        // thread performs the cleanup. The pending ticket makes this idempotent with every other
        // race outcome.
        if (registrationsClosed.get()) {
            closePendingRegistration(pendingRegistration);
        }
        selector.wakeup(); // wakeup event loop thread to process task immediately
    }

    private void processPendingRegistration(PendingRegistration pendingRegistration) {
        if (!claimPendingRegistration(pendingRegistration)) {
            return;
        }

        try {
            if (stop.get() || draining.get() || registrationsClosed.get()) {
                pendingRegistration.closeAndRelease();
                return;
            }

            doRegister(pendingRegistration);
        } catch (Throwable throwable) {
            logThrowable(throwable, new LogEntry("event", "register_error"));
            pendingRegistration.closeAndRelease();
        }
    }

    private void doRegister(PendingRegistration pendingRegistration) throws IOException {
        SocketChannel socketChannel = pendingRegistration.socketChannel;
        @Nullable SelectionKey selectionKey = null;
        @Nullable Connection connection = null;

        try {
            socketChannel.configureBlocking(false);
            selectionKey = socketChannel.register(selector, SelectionKey.OP_READ);
            SocketAddress socketAddress = socketChannel.getRemoteAddress();
            InetSocketAddress remoteAddress = socketAddress instanceof InetSocketAddress
                    ? (InetSocketAddress) socketAddress
                    : null;
            connection = new Connection(socketChannel, selectionKey, remoteAddress, pendingRegistration.admission);
            connectionCount.incrementAndGet();
            selectionKey.attach(connection);

            try {
                if (logger.enabled()) {
                    String remoteAddressString = remoteAddress != null
                            ? remoteAddress.toString()
                            : (socketAddress != null ? socketAddress.toString() : "unknown");
                    logger.log(
                            new LogEntry("event", "accept"),
                            new LogEntry("remote_address", remoteAddressString),
                            new LogEntry("id", connection.id));
                }
            } catch (Throwable throwable) {
                logThrowable(throwable,
                        new LogEntry("event", "accept_log_error"),
                        new LogEntry("id", connection.id));
            }
        } catch (Throwable throwable) {
            if (connection != null) {
                connection.failSafeClose(StreamTerminationReason.INTERNAL_ERROR, throwable);
            } else {
                if (selectionKey != null) {
                    selectionKey.cancel();
                }
                pendingRegistration.closeAndRelease();
            }

            if (throwable instanceof IOException ioException) {
                throw ioException;
            }
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new IOException("Unexpected connection registration failure", throwable);
        }
    }

    private boolean claimPendingRegistration(PendingRegistration pendingRegistration) {
        if (!pendingRegistration.claim()) {
            return false;
        }

        pendingRegistrations.remove(pendingRegistration);
        pendingRegistrationCount.decrementAndGet();
        return true;
    }

    private void closePendingRegistration(PendingRegistration pendingRegistration) {
        if (claimPendingRegistration(pendingRegistration)) {
            pendingRegistration.closeAndRelease();
        }
    }

    private void closePendingRegistrations() {
        PendingRegistration pendingRegistration;
        while ((pendingRegistration = pendingRegistrations.poll()) != null) {
            if (pendingRegistration.claim()) {
                pendingRegistrationCount.decrementAndGet();
                pendingRegistration.closeAndRelease();
            }
        }
    }

    void closeBeforeStart() {
        synchronized (lifecycleLock) {
            if (started || closedBeforeStart) {
                return;
            }

            closedBeforeStart = true;
            registrationsClosed.set(true);
            closePendingRegistrations();
            CloseUtils.closeQuietly(selector);
        }
    }

    boolean resourcesClosed() {
        return !selector.isOpen();
    }

    private void runLoopTask(Runnable task, String failureEvent) {
        try {
            task.run();
        } catch (Throwable throwable) {
            logThrowable(throwable, new LogEntry("event", failureEvent));
        }
    }

    private void logThrowable(Throwable throwable, LogEntry... entries) {
        try {
            if (logger.failureEnabled()) {
                logger.logFailure(throwable, entries);
            }
        } catch (Throwable ignored) {
            // Logging must not terminate the event loop.
        }
    }
}
