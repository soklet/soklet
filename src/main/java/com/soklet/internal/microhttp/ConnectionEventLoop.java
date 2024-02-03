package com.soklet.internal.microhttp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final Options options;
    private final Logger logger;
    private final Handler handler;
    private final AtomicLong connectionCounter;
    private final AtomicBoolean stop;

    private final Scheduler timeoutQueue;
    private final Queue<Runnable> taskQueue;
    private final ByteBuffer buffer;
    private final Selector selector;
    private final Thread thread;

    ConnectionEventLoop(
            Options options,
            Logger logger,
            Handler handler,
            AtomicLong connectionCounter,
            AtomicBoolean stop) throws IOException {
        this.options = options;
        this.logger = logger;
        this.handler = handler;
        this.connectionCounter = connectionCounter;
        this.stop = stop;

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

        static final String KEEP_ALIVE = "Keep-Alive";

        final SocketChannel socketChannel;
        final SelectionKey selectionKey;
        final ByteTokenizer byteTokenizer;
        final String id;
        RequestParser requestParser;
        ByteBuffer writeBuffer;
        Cancellable requestTimeoutTask;
        boolean httpOneDotZero;
        boolean keepAlive;

        private Connection(SocketChannel socketChannel, SelectionKey selectionKey) throws IOException {
            this.socketChannel = socketChannel;
            this.selectionKey = selectionKey;
            byteTokenizer = new ByteTokenizer();
            id = Long.toString(connectionCounter.getAndIncrement());
            requestParser = new RequestParser(byteTokenizer);
            requestTimeoutTask = timeoutQueue.schedule(this::onRequestTimeout, options.requestTimeout());
        }

        private void onRequestTimeout() {
            if (logger.enabled()) {
                logger.log(
                        new LogEntry("event", "request_timeout"),
                        new LogEntry("id", id));
            }
            failSafeClose();
        }

        private void onReadable() {
            try {
                doOnReadable();
            } catch (IOException | RuntimeException e) {
                if (logger.enabled()) {
                    logger.log(e,
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
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "read_request"),
                            new LogEntry("id", id),
                            new LogEntry("request_bytes", Integer.toString(byteTokenizer.remaining())));
                }
                onParseRequest();
            } else {
                if (byteTokenizer.size() > options.maxRequestSize()) {
                    if (logger.enabled()) {
                        logger.log(
                                new LogEntry("event", "exceed_request_max_close"),
                                new LogEntry("id", id),
                                new LogEntry("request_size", Integer.toString(byteTokenizer.size())));
                    }

                    // *** START SOKLET CHANGE ***

                    // Previous behavior was to immediately discard the request and close the connection.
                    //
                    // However, this is not semantically aligned with Soklet's error handling: we want client application
                    // code to be able to detect "request too large" and write whatever response it likes.
                    //
                    // Updated behavior is to truncate the request body and permit response processing.

                    // Previously:
                    // failSafeClose();

                    // Updated:
                    // Perform the actions of onParseRequest() but inject a "poison pill" header.
                    // This lets Soklet code know the request was stopped for being too large.
                    //
                    // Alternative would be to, for example, modify MicrohttpRequest to include an additional field.
                    // Current approach was chosen because it keeps surface area of Microhttp modifications small.

                    if (selectionKey.interestOps() != 0) {
                        selectionKey.interestOps(0);
                    }

                    if (requestTimeoutTask != null) {
                        requestTimeoutTask.cancel();
                        requestTimeoutTask = null;
                    }

                    MicrohttpRequest request = requestParser.request();

                    if(request.method() == null || request.uri() == null || request.version() == null) {
                        // We don't even have enough data to construct a meaningful Request; nothing we can do.
                        // Fall back to stock Microhttp behavior.
                        failSafeClose();
                    } else {
                        // OK, we at least have a method, URI, and HTTP version.
                        // We make our own request with its own copy of headers - including our poison pill - and an empty body.
                        List<Header> headers = request.headers() == null ? new ArrayList<>(1) : new ArrayList<>(request.headers());
                        headers.add(new Header("com.soklet.CONTENT_TOO_LARGE", String.valueOf(options.maxRequestSize())));

                        MicrohttpRequest tooLargeRequest = new MicrohttpRequest(request.method(), request.uri(), request.version(), headers, new byte[0]);

                        httpOneDotZero = tooLargeRequest.version().equalsIgnoreCase(HTTP_1_0);
                        keepAlive = tooLargeRequest.hasHeader(HEADER_CONNECTION, KEEP_ALIVE);
                        byteTokenizer.compact();
                        requestParser = new RequestParser(byteTokenizer);
                        handler.handle(tooLargeRequest, this::onResponse);
                    }

                    // *** END SOKLET CHANGE ***
                }
            }
        }

        private void onParseRequest() {
            if (selectionKey.interestOps() != 0) {
                selectionKey.interestOps(0);
            }
            if (requestTimeoutTask != null) {
                requestTimeoutTask.cancel();
                requestTimeoutTask = null;
            }
            MicrohttpRequest request = requestParser.request();
            httpOneDotZero = request.version().equalsIgnoreCase(HTTP_1_0);
            keepAlive = request.hasHeader(HEADER_CONNECTION, KEEP_ALIVE);
            byteTokenizer.compact();
            requestParser = new RequestParser(byteTokenizer);
            handler.handle(request, this::onResponse);
        }

        private void onResponse(MicrohttpResponse microhttpResponse) {
            // enqueuing the callback invocation and waking the selector
            // ensures that the microhttpResponse callback works properly when
            // invoked inline from the event loop thread or a separate background thread
            taskQueue.add(() -> {
                try {
                    prepareToWriteResponse(microhttpResponse);
                } catch (IOException e) {
                    if (logger.enabled()) {
                        logger.log(e,
                                new LogEntry("event", "response_ready_error"),
                                new LogEntry("id", id));
                    }
                    failSafeClose();
                }
            });
            // selector wakeup is not necessary if callback was invoked within event loop thread
            // since scheduler tasks are processed at the end of every event loop iteration
            if (Thread.currentThread() != thread) {
                selector.wakeup();
            }
        }

        private void prepareToWriteResponse(MicrohttpResponse microhttpResponse) throws IOException {
            String version = httpOneDotZero ? HTTP_1_0 : HTTP_1_1;
            List<Header> headers = new ArrayList<>();
            if (httpOneDotZero && keepAlive) {
                headers.add(new Header(HEADER_CONNECTION, KEEP_ALIVE));
            }
            if (!microhttpResponse.hasHeader(HEADER_CONTENT_LENGTH)) {
                headers.add(new Header(HEADER_CONTENT_LENGTH, Integer.toString(microhttpResponse.body().length)));
            }
            writeBuffer = ByteBuffer.wrap(microhttpResponse.serialize(version, headers));
            if (logger.enabled()) {
                logger.log(
                        new LogEntry("event", "response_ready"),
                        new LogEntry("id", id),
                        new LogEntry("num_bytes", Integer.toString(writeBuffer.remaining())));
            }
            doOnWritable();
        }

        private void onWritable() {
            try {
                doOnWritable();
            } catch (IOException | RuntimeException e) {
                if (logger.enabled()) {
                    logger.log(e,
                            new LogEntry("event", "write_error"),
                            new LogEntry("id", id));
                }
                failSafeClose();
            }
        }

        private int doWrite() throws IOException {
            buffer.clear(); // pos = 0, limit = capacity
            int amount = Math.min(buffer.remaining(), writeBuffer.remaining()); // determine transfer quantity
            buffer.put(writeBuffer.array(), writeBuffer.position(), amount); // do transfer
            buffer.flip();
            int written = socketChannel.write(buffer);
            writeBuffer.position(writeBuffer.position() + written); // advance write buffer
            return written;
        }

        private void doOnWritable() throws IOException {
            int numBytes = doWrite();
            if (!writeBuffer.hasRemaining()) { // response fully written
                writeBuffer = null; // done with current write buffer, remove reference
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "write_response"),
                            new LogEntry("id", id),
                            new LogEntry("num_bytes", Integer.toString(numBytes)));
                }
                if (httpOneDotZero && !keepAlive) { // non-persistent connection, close now
                    if (logger.enabled()) {
                        logger.log(
                                new LogEntry("event", "close_after_response"),
                                new LogEntry("id", id));
                    }
                    failSafeClose();
                } else { // persistent connection
                    if (requestParser.parse()) { // subsequent request in buffer
                        if (logger.enabled()) {
                            logger.log(
                                    new LogEntry("event", "pipeline_request"),
                                    new LogEntry("id", id),
                                    new LogEntry("request_bytes", Integer.toString(byteTokenizer.remaining())));
                        }
                        onParseRequest();
                    } else { // switch back to read mode
                        requestTimeoutTask = timeoutQueue.schedule(this::onRequestTimeout, options.requestTimeout());
                        selectionKey.interestOps(SelectionKey.OP_READ);
                    }
                }
            } else { // response not fully written, switch to or remain in write mode
                if ((selectionKey.interestOps() & SelectionKey.OP_WRITE) == 0) {
                    selectionKey.interestOps(SelectionKey.OP_WRITE);
                }
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "write"),
                            new LogEntry("id", id),
                            new LogEntry("num_bytes", Integer.toString(numBytes)));
                }
            }
        }

        private void failSafeClose() {
            if (requestTimeoutTask != null) {
                requestTimeoutTask.cancel();
            }
            selectionKey.cancel();
            CloseUtils.closeQuietly(socketChannel);
        }
    }

    int numConnections() {
        return selector.keys().size();
    }

    void start() {
        thread.start();
    }

    void join() throws InterruptedException {
        thread.join();
    }

    private void run() {
        try {
            doStart();
        } catch (IOException e) {
            if (logger.enabled()) {
                logger.log(e, new LogEntry("event", "sub_event_loop_terminate"));
            }
            stop.set(true); // stop the world on critical error
        } finally {
            for (SelectionKey selKey : selector.keys()) {
                Object attachment = selKey.attachment();
                if (attachment instanceof Connection connection) {
                    connection.failSafeClose();
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
                if (selKey.isReadable()) {
                    ((Connection) selKey.attachment()).onReadable();
                } else if (selKey.isWritable()) {
                    ((Connection) selKey.attachment()).onWritable();
                }
                it.remove();
            }
            timeoutQueue.expired().forEach(Runnable::run);
            Runnable task;
            while ((task = taskQueue.poll()) != null) {
                task.run();
            }
        }
    }

    void register(SocketChannel socketChannel) {
        taskQueue.add(() -> {
            try {
                doRegister(socketChannel);
            } catch (IOException e) {
                logger.log(e, new LogEntry("event", "register_error"));
                CloseUtils.closeQuietly(socketChannel);
            }
        });
        selector.wakeup(); // wakeup event loop thread to process task immediately
    }

    private void doRegister(SocketChannel socketChannel) throws IOException {
        socketChannel.configureBlocking(false);
        SelectionKey selectionKey = socketChannel.register(selector, SelectionKey.OP_READ);
        Connection connection = new Connection(socketChannel, selectionKey);
        selectionKey.attach(connection);
        if (logger.enabled()) {
            logger.log(
                    new LogEntry("event", "accept"),
                    new LogEntry("remote_address", socketChannel.getRemoteAddress().toString()),
                    new LogEntry("id", connection.id));
        }
    }
}