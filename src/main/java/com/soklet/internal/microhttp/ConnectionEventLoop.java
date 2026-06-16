package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
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

    private final Options options;
    private final Logger logger;
    private final Handler handler;
    private final AtomicLong connectionCounter;
    private final AtomicBoolean stop;
    private final AtomicBoolean draining;

    private final Scheduler timeoutQueue;
    private final Queue<Runnable> taskQueue;
    private final ByteBuffer buffer;
    private final Selector selector;
    private final Thread thread;
    private final AtomicInteger connectionCount;

    ConnectionEventLoop(
            Options options,
            Logger logger,
            Handler handler,
            AtomicLong connectionCounter,
            AtomicBoolean stop,
            AtomicBoolean draining) throws IOException {
        this.options = options;
        this.logger = logger;
        this.handler = handler;
        this.connectionCounter = connectionCounter;
        this.stop = stop;
        this.draining = draining;

        connectionCount = new AtomicInteger();
        timeoutQueue = new Scheduler();
        taskQueue = new ConcurrentLinkedQueue<>();
        buffer = ByteBuffer.allocateDirect(options.readBufferSize());
        selector = Selector.open();
        thread = new Thread(this::run, "connection-event-loop");
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

        static final byte[] BAD_REQUEST_RESPONSE =
                "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII);
        static final byte[] EXPECTATION_FAILED_RESPONSE =
                "HTTP/1.1 417 Expectation Failed\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII);
        static final byte[] CONTINUE_RESPONSE =
                "HTTP/1.1 100 Continue\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII);

        final SocketChannel socketChannel;
        final SelectionKey selectionKey;
        final ByteTokenizer byteTokenizer;
        final String id;
        final @Nullable InetSocketAddress remoteAddress;
        RequestParser requestParser;
        @Nullable
        WritableSource writableSource;
        @Nullable
        ByteBuffer continueResponseBuffer;
        @Nullable
        Cancelable requestReadTimeoutTask;
        @Nullable
        Cancelable responseWriteIdleTimeoutTask;
        boolean requestReadTimeoutBodyPhase;
        long requestReadTimeoutTokenizerMark;
        boolean responseWriteIdleTimeoutEnabled;
        boolean httpOneDotZero;
        boolean headRequest;
        boolean keepAlive;
        boolean closeAfterResponse;
        boolean requestInFlight;
        final AtomicBoolean closed;

        private Connection(SocketChannel socketChannel, SelectionKey selectionKey, @Nullable InetSocketAddress remoteAddress) throws IOException {
            this.socketChannel = socketChannel;
            this.selectionKey = selectionKey;
            byteTokenizer = new ByteTokenizer();
            id = Long.toString(connectionCounter.getAndIncrement());
            this.remoteAddress = remoteAddress;
            requestParser = new RequestParser(byteTokenizer, remoteAddress, options.maxRequestSize(),
                    options.maxHeaderCount(), options.maxHeadersSize(), options.maxRequestTargetLength());
            scheduleRequestReadTimeoutForCurrentParserState();
            closed = new AtomicBoolean(false);
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
                failSafeClose(StreamTerminationReason.SERVER_STOPPING, null);
                return;
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
                respondToRequestTooLarge();
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
                failSafeClose();
            }
        }

        private void doOnReadable() throws IOException {
            buffer.clear();
            int numBytes = socketChannel.read(buffer);
            if (numBytes < 0) {
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "read_close"),
                            new LogEntry("id", id));
                }
                failSafeClose();
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
                    respondToRequestTooLarge();
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

                    respondToRequestTooLarge();
                } else {
                    onPartialRequestParsed();
                }
            }
        }

        private void respondToRequestTooLarge() {
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
            requestInFlight = true;
            handler.handle(tooLargeRequest, this::onResponse);
        }

        private void respondToMalformedRequest() {
            respondWithRawError(BAD_REQUEST_RESPONSE);
        }

        private void respondToExpectationFailed() {
            respondWithRawError(EXPECTATION_FAILED_RESPONSE);
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
            byteTokenizer.compact();
            requestParser.reset();
            requestInFlight = true;
            handler.handle(request, this::onResponse);
        }

        private void onResponse(MicrohttpResponse microhttpResponse) {
            // enqueuing the callback invocation and waking the selector
            // ensures that the microhttpResponse callback works properly when
            // invoked inline from the event loop thread or a separate background thread
            queueConnectionTask("response_ready_error", () -> prepareToWriteResponse(microhttpResponse));
            // selector wakeup is not necessary if callback was invoked within event loop thread
            // since scheduler tasks are processed at the end of every event loop iteration
            if (Thread.currentThread() != thread) {
                selector.wakeup();
            }
        }

        private void prepareToWriteResponse(MicrohttpResponse microhttpResponse) throws IOException {
            if (microhttpResponse.streaming() && httpOneDotZero) {
                microhttpResponse.closeStreamingBody(StreamTerminationReason.PROTOCOL_UNSUPPORTED, null);
                microhttpResponse = new MicrohttpResponse(505, "HTTP Version Not Supported",
                        List.of(new Header(HEADER_CONNECTION, CLOSE)), new byte[0]);
                closeAfterResponse = true;
            }
            if (closed.get()) {
                microhttpResponse.closeStreamingBody(StreamTerminationReason.SERVER_STOPPING, null);
                return;
            }
            requestInFlight = false;
            if (hasHeaderToken(microhttpResponse.headers(), HEADER_CONNECTION, CLOSE)) {
                closeAfterResponse = true;
            }
            if (draining.get()) {
                closeAfterResponse = true;
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
            writableSource = microhttpResponse.writableSource(serializedHead);
            writableSource.writeReadyCallback(this::onWritableSourceReady);
            writableSource.start();
            resetResponseWriteIdleTimeoutIfNeeded();
            if (logger.enabled()) {
                logger.log(
                        new LogEntry("event", "response_ready"),
                        new LogEntry("id", id),
                        new LogEntry("num_bytes", Long.toString((long) serializedHead.length + microhttpResponse.bodyLength())));
            }
            doOnWritable();
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
                failSafeClose();
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
                doOnWritable();
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
                cancelResponseWriteIdleTimeout();
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
                } else { // persistent connection
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
                        respondToRequestTooLarge();
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
                respondToRequestTooLarge();
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

        private void failSafeClose() {
            failSafeClose(null, null);
        }

        private void failSafeClose(@Nullable StreamTerminationReason cancelationReason, @Nullable Throwable cause) {
            if (!closed.compareAndSet(false, true))
                return;
            requestInFlight = false;
            if (requestReadTimeoutTask != null) {
                requestReadTimeoutTask.cancel();
            }
            cancelResponseWriteIdleTimeout();
            if (writableSource != null) {
                if (cancelationReason == null) {
                    CloseUtils.closeQuietly(writableSource);
                } else {
                    try {
                        writableSource.close(cancelationReason, cause);
                    } catch (IOException ignore) {
                        // suppress
                    }
                }
                writableSource = null;
            }
            continueResponseBuffer = null;
            selectionKey.cancel();
            CloseUtils.closeQuietly(socketChannel);
            connectionCount.decrementAndGet();
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
                failSafeClose();
            }
        }

        private void beginDrain() {
            if (closed.get())
                return;

            if (requestInFlight || writableSource != null) {
                closeAfterResponse = true;
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

            if (mustNotSendContentLength(microhttpResponse.status())) {
                return false;
            }

            return !headRequest || microhttpResponse.bodyLength() > 0L;
        }

        private boolean mustNotSendContentLength(int status) {
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
        thread.start();
    }

    void wakeup() {
        selector.wakeup();
    }

    void join() throws InterruptedException {
        thread.join();
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
        } finally {
            for (SelectionKey selKey : selector.keys()) {
                Object attachment = selKey.attachment();
                if (attachment instanceof Connection connection) {
                    connection.failSafeClose(StreamTerminationReason.SERVER_STOPPING, null);
                }
            }
            CloseUtils.closeQuietly(selector);
        }
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

    void register(SocketChannel socketChannel) {
        taskQueue.add(() -> {
            try {
                doRegister(socketChannel);
            } catch (Throwable e) {
                logThrowable(e, new LogEntry("event", "register_error"));
                CloseUtils.closeQuietly(socketChannel);
            }
        });
        selector.wakeup(); // wakeup event loop thread to process task immediately
    }

    private void doRegister(SocketChannel socketChannel) throws IOException {
        if (stop.get() || draining.get()) {
            CloseUtils.closeQuietly(socketChannel);
            return;
        }

        socketChannel.configureBlocking(false);
        SelectionKey selectionKey = socketChannel.register(selector, SelectionKey.OP_READ);
        SocketAddress socketAddress = socketChannel.getRemoteAddress();
        InetSocketAddress remoteAddress = socketAddress instanceof InetSocketAddress
                ? (InetSocketAddress) socketAddress
                : null;
        Connection connection = new Connection(socketChannel, selectionKey, remoteAddress);
        connectionCount.incrementAndGet();
        selectionKey.attach(connection);
        if (logger.enabled()) {
            String remoteAddressString = remoteAddress != null
                    ? remoteAddress.toString()
                    : (socketAddress != null ? socketAddress.toString() : "unknown");
            logger.log(
                    new LogEntry("event", "accept"),
                    new LogEntry("remote_address", remoteAddressString),
                    new LogEntry("id", connection.id));
        }
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
