package com.soklet.internal.microhttp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventLoop is an HTTP server implementation. It provides connection management, network I/O,
 * request parsing, and request dispatching.
 */
public class EventLoop {
    private static final Duration ACCEPT_FAILURE_BACKOFF = Duration.ofMillis(50);
    private static final Duration ACCEPT_FAILURE_BACKOFF_MAX = Duration.ofSeconds(1);

    private final Options options;
    private final Logger logger;
    private final ConnectionListener connectionListener;

    private final Selector selector;
    private final AtomicBoolean stopAccepting;
    private final AtomicBoolean stopConnections;
    private final AtomicBoolean draining;
    private final ServerSocketChannel serverSocketChannel;
    private final List<ConnectionEventLoop> connectionEventLoops;
    private final Thread thread;

    // Tracks a run of back-to-back accept() failures so the loop can escalate its backoff and
    // coalesce its logging instead of storming. Touched on the accept-loop thread; AtomicLong for safe publication.
    private final AtomicLong consecutiveAcceptFailures = new AtomicLong();

    @FunctionalInterface
    interface SocketAcceptor {
        SocketChannel accept() throws IOException;
    }

    public EventLoop(Handler handler) throws IOException {
        this(Options.builder().build(), NoopLogger.instance(), handler, NoopConnectionListener.instance());
    }

    public EventLoop(Options options, Handler handler) throws IOException {
        this(options, NoopLogger.instance(), handler, NoopConnectionListener.instance());
    }

    public EventLoop(Options options, Logger logger, Handler handler) throws IOException {
        this(options, logger, handler, NoopConnectionListener.instance());
    }

    public EventLoop(Options options, Logger logger, Handler handler, ConnectionListener connectionListener) throws IOException {
        this.options = options;
        this.logger = logger;
        this.connectionListener = connectionListener == null ? NoopConnectionListener.instance() : connectionListener;

        selector = Selector.open();
        stopAccepting = new AtomicBoolean();
        stopConnections = new AtomicBoolean();
        draining = new AtomicBoolean();

        AtomicLong connectionCounter = new AtomicLong();
        connectionEventLoops = new ArrayList<>();
        for (int i = 0; i < options.concurrency(); i++) {
            connectionEventLoops.add(new ConnectionEventLoop(options, logger, handler, connectionCounter, stopConnections, draining));
        }

        thread = new Thread(this::run, "event-loop");

        InetSocketAddress address = options.host() == null
                ? new InetSocketAddress(options.port()) // wildcard address
                : new InetSocketAddress(options.host(), options.port());

        serverSocketChannel = ServerSocketChannel.open();
        if (options.reuseAddr()) {
            serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, options.reuseAddr());
        }
        if (options.reusePort()) {
            serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEPORT, options.reusePort());
        }
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.bind(address, options.acceptLength());
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    public int getPort() throws IOException {
        return serverSocketChannel.getLocalAddress() instanceof InetSocketAddress a ? a.getPort() : -1;
    }

    public void start() {
        thread.start();
        connectionEventLoops.forEach(ConnectionEventLoop::start);
    }

    private void run() {
        try {
            doRun();
        } catch (Throwable throwable) {
            try {
                if (logger.failureEnabled()) {
                    logger.logFailure(throwable, new LogEntry("event", "event_loop_terminate"));
                }
            } catch (Throwable ignored) {
                // No safe fallback sink is available from the accept-loop thread.
            }
            stopAccepting.set(true);
            stopConnections.set(true); // stop the world on critical error
            connectionEventLoops.forEach(ConnectionEventLoop::wakeup);
            try {
                connectionListener.didTerminateEventLoop(this, throwable);
            } catch (Throwable ignored) {
                // No safe fallback sink is available from the accept-loop thread.
            }
        } finally {
            CloseUtils.closeQuietly(selector);
            CloseUtils.closeQuietly(serverSocketChannel);
        }
    }

    private void doRun() throws IOException {
        while (!stopAccepting.get() && !stopConnections.get()) {
            selector.select(options.resolution().toMillis());
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectedKeys.iterator();
            while (it.hasNext()) {
                SelectionKey selKey = it.next();
                if (stopAccepting.get() || stopConnections.get()) {
                    it.remove();
                    break;
                }
                if (selKey.isAcceptable()) {
                    acceptReadyConnection();
                }
                it.remove();
            }
        }
    }

    boolean acceptReadyConnection() throws IOException {
        return acceptReadyConnection(serverSocketChannel::accept);
    }

    boolean acceptReadyConnection(SocketAcceptor socketAcceptor) throws IOException {
        if (stopAccepting.get() || stopConnections.get()) {
            return false;
        }

        InetSocketAddress remoteAddress = null;
        SocketChannel socketChannel;

        try {
            socketChannel = socketAcceptor.accept();
        } catch (IOException e) {
            if (stopAccepting.get() || stopConnections.get() || !serverSocketChannel.isOpen()) {
                throw e;
            }

            handleAcceptFailure(e);
            return false;
        }

        // accept() returned without throwing, so the accept path is healthy again
        noteAcceptRecovery();

        if (socketChannel == null) {
            return false;
        }

        try {
            try {
                SocketAddress socketAddress = socketChannel.getRemoteAddress();
                if (socketAddress instanceof InetSocketAddress) {
                    remoteAddress = (InetSocketAddress) socketAddress;
                }
            } catch (IOException ignored) {
                // Best effort
            }

            connectionListener.willAcceptConnection(remoteAddress);
            if (options.maxConnections() > 0 && totalConnections() >= options.maxConnections()) {
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "accept_reject_max_connections"),
                            new LogEntry("max_connections", Integer.toString(options.maxConnections())));
                }
                connectionListener.didFailToAcceptConnection(remoteAddress);
                CloseUtils.closeQuietly(socketChannel);
                return false;
            }

            connectionListener.didAcceptConnection(remoteAddress);
            ConnectionEventLoop connectionEventLoop = leastConnections();
            connectionEventLoop.register(socketChannel);
            return true;
        } catch (RuntimeException e) {
            connectionListener.didFailToAcceptConnection(remoteAddress, e);
            CloseUtils.closeQuietly(socketChannel);
            logConnectionSetupFailure(e);
            backoffAfterAcceptFailure();
            return false;
        }
    }

    private ConnectionEventLoop leastConnections() {
        return connectionEventLoops.stream()
                .min(Comparator.comparing(ConnectionEventLoop::numConnections))
                .get();
    }

    private int totalConnections() {
        int total = 0;
        for (ConnectionEventLoop loop : connectionEventLoops) {
            total += loop.numConnections();
        }
        return total;
    }

    public void stop() {
        stopAccepting.set(true);
        stopConnections.set(true);
        selector.wakeup();
        connectionEventLoops.forEach(ConnectionEventLoop::wakeup);
    }

    public void stopAccepting() {
        stopAccepting.set(true);
        selector.wakeup();
    }

    public void beginDrain() {
        draining.set(true);
        connectionEventLoops.forEach(ConnectionEventLoop::beginDrain);
    }

    public void stopConnections() {
        stopConnections.set(true);
        connectionEventLoops.forEach(ConnectionEventLoop::wakeup);
    }

    public boolean isRunning() {
        return thread.isAlive() && !stopAccepting.get() && !stopConnections.get();
    }

    public boolean isAccepting() {
        return thread.isAlive() && !stopAccepting.get() && !stopConnections.get();
    }

    boolean isStopped() {
        return stopAccepting.get() || stopConnections.get();
    }

    public void join() throws InterruptedException {
        joinAcceptLoop();
        joinConnectionLoops();
    }

    public void joinAcceptLoop() throws InterruptedException {
        thread.join();
    }

    public void joinConnectionLoops() throws InterruptedException {
        for (ConnectionEventLoop connectionEventLoop : connectionEventLoops) {
            connectionEventLoop.join();
        }
    }

    public boolean awaitConnectionsDrained(Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Math.max(0L, timeout.toNanos());

        while (totalConnections() > 0) {
            long remainingNanos = deadlineNanos - System.nanoTime();

            if (remainingNanos <= 0L)
                return false;

            Thread.sleep(Math.min(10L, Math.max(1L, remainingNanos / 1_000_000L)));
        }

        return true;
    }

    private void handleAcceptFailure(IOException e) {
        long failures = consecutiveAcceptFailures.incrementAndGet();
        connectionListener.didFailToAcceptConnection(null, e);

        // Coalesce log volume during a sustained failure (e.g. file-descriptor exhaustion):
        // log the first failure and then only at exponentially-spaced milestones.
        if (logger.failureEnabled() && isPowerOfTwo(failures)) {
            logger.logFailure(e,
                    new LogEntry("event", "accept_loop_error"),
                    new LogEntry("consecutive_failures", Long.toString(failures)));
        }

        backoffAfterAcceptFailure(failures);
    }

    private void noteAcceptRecovery() {
        if (consecutiveAcceptFailures.get() == 0) {
            return;
        }

        long recoveredAfter = consecutiveAcceptFailures.getAndSet(0);

        if (logger.enabled()) {
            logger.log(
                    new LogEntry("event", "accept_loop_recovered"),
                    new LogEntry("failures", Long.toString(recoveredAfter)));
        }
    }

    // Visible for testing.
    static boolean isPowerOfTwo(long value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    private void logConnectionSetupFailure(RuntimeException e) {
        if (logger.failureEnabled()) {
            logger.logFailure(e, new LogEntry("event", "connection_setup_error"));
        }
    }

    // Escalating backoff: a persistent accept() failure (e.g. EMFILE) would otherwise spin the
    // accept loop ~20x/sec. Double the delay per consecutive failure up to a 1s ceiling.
    private void backoffAfterAcceptFailure(long consecutiveFailures) {
        sleepBeforeRetry(acceptBackoffMillis(consecutiveFailures));
    }

    // Visible for testing.
    static long acceptBackoffMillis(long consecutiveFailures) {
        long base = ACCEPT_FAILURE_BACKOFF.toMillis();
        long max = ACCEPT_FAILURE_BACKOFF_MAX.toMillis();
        int shift = (int) Math.min(Math.max(0L, consecutiveFailures - 1L), 20L);
        return Math.min(max, base << shift);
    }

    private void backoffAfterAcceptFailure() {
        sleepBeforeRetry(ACCEPT_FAILURE_BACKOFF.toMillis());
    }

    private void sleepBeforeRetry(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopAccepting.set(true);
            stopConnections.set(true);
            connectionEventLoops.forEach(ConnectionEventLoop::wakeup);
        }
    }
}
