package com.soklet.internal.microhttp;

import com.soklet.internal.util.AcceptLoopBackoff;
import org.jspecify.annotations.Nullable;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventLoop is an HTTP server implementation. It provides connection management, network I/O,
 * request parsing, and request dispatching.
 */
public class EventLoop {
    private final Options options;
    private final Logger logger;
    private final ConnectionListener connectionListener;

    private final Selector selector;
    private final AtomicBoolean stopAccepting;
    private final AtomicBoolean stopConnections;
    private final AtomicBoolean draining;
    private final AtomicBoolean unexpectedTerminationNotified;
    private final AtomicInteger admittedConnections;
    private final ServerSocketChannel serverSocketChannel;
    private final List<ConnectionEventLoop> connectionEventLoops;
    private final Thread thread;
    private final Object lifecycleLock;
    private boolean started;
    private boolean closedBeforeStart;

    // Tracks a run of back-to-back accept() failures so the loop can escalate its backoff and
    // coalesce its logging instead of storming. Touched on the accept-loop thread; AtomicLong for safe publication.
    private final AtomicLong consecutiveAcceptFailures = new AtomicLong();

    @FunctionalInterface
    interface SocketAcceptor {
        SocketChannel accept() throws IOException;
    }

    /**
     * A single ownership ticket for one accepted socket. Ownership moves from the accept loop to a
     * pending registration and finally to a registered connection. Every terminal path may safely
     * call {@link #release()}; only the first call changes the global admission count.
     */
    static final class ConnectionAdmission {
        private final AtomicInteger admittedConnections;
        private final AtomicBoolean released;

        private ConnectionAdmission(AtomicInteger admittedConnections) {
            this.admittedConnections = admittedConnections;
            this.released = new AtomicBoolean();
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                admittedConnections.decrementAndGet();
            }
        }
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
        unexpectedTerminationNotified = new AtomicBoolean();
        admittedConnections = new AtomicInteger();
        lifecycleLock = new Object();

        AtomicLong connectionCounter = new AtomicLong();
        connectionEventLoops = new ArrayList<>();
        try {
            for (int i = 0; i < options.concurrency(); i++) {
                connectionEventLoops.add(new ConnectionEventLoop(
                        options, logger, handler, connectionCounter, stopConnections, draining,
                        this::handleUnexpectedTermination));
            }
        } catch (IOException | RuntimeException | Error throwable) {
            connectionEventLoops.forEach(ConnectionEventLoop::closeBeforeStart);
            CloseUtils.closeQuietly(selector);
            throw throwable;
        }

        Thread acceptLoopThread;
        try {
            acceptLoopThread = new Thread(this::run, "event-loop");
        } catch (RuntimeException | Error throwable) {
            connectionEventLoops.forEach(ConnectionEventLoop::closeBeforeStart);
            CloseUtils.closeQuietly(selector);
            throw throwable;
        }
        thread = acceptLoopThread;

        InetSocketAddress address = options.host() == null
                ? new InetSocketAddress(options.port()) // wildcard address
                : new InetSocketAddress(options.host(), options.port());

        try {
            serverSocketChannel = openServerSocketChannel(address);
        } catch (IOException | RuntimeException | Error throwable) {
            connectionEventLoops.forEach(ConnectionEventLoop::closeBeforeStart);
            CloseUtils.closeQuietly(selector);
            throw throwable;
        }
    }

    private ServerSocketChannel openServerSocketChannel(InetSocketAddress address) throws IOException {
        ServerSocketChannel openedServerSocketChannel = ServerSocketChannel.open();

        try {
            if (options.reuseAddr()) {
                openedServerSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, options.reuseAddr());
            }
            if (options.reusePort()) {
                openedServerSocketChannel.setOption(StandardSocketOptions.SO_REUSEPORT, options.reusePort());
            }
            openedServerSocketChannel.configureBlocking(false);
            openedServerSocketChannel.bind(address, options.acceptLength());
            openedServerSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            return openedServerSocketChannel;
        } catch (IOException | RuntimeException | Error throwable) {
            CloseUtils.closeQuietly(openedServerSocketChannel);
            throw throwable;
        }
    }

    public int getPort() throws IOException {
        return getLocalAddress().getPort();
    }

    /**
     * Returns the effective local address of the bound listener. This is authoritative when
     * port zero was requested and avoids reconstructing diagnostics from configured values.
     */
    public InetSocketAddress getLocalAddress() throws IOException {
        SocketAddress localAddress = serverSocketChannel.getLocalAddress();

        if (localAddress instanceof InetSocketAddress inetSocketAddress) {
            return inetSocketAddress;
        }

        throw new IOException("The event loop is not bound to an internet socket address.");
    }

    public void start() {
        synchronized (lifecycleLock) {
            // Preserve stop-before-start as an idempotent terminal operation. Historically tests
            // sometimes called start() after stop() solely to drive cleanup; cleanup is now
            // synchronous, so there is nothing left to start.
            if (closedBeforeStart) {
                return;
            }
            if (started) {
                throw new IllegalStateException("Event loop has already been started.");
            }

            started = true;
            try {
                connectionEventLoops.forEach(ConnectionEventLoop::start);
                thread.start();
            } catch (RuntimeException | Error throwable) {
                stopAccepting.set(true);
                stopConnections.set(true);
                connectionEventLoops.forEach(ConnectionEventLoop::wakeup);
                connectionEventLoops.forEach(ConnectionEventLoop::closeBeforeStart);
                CloseUtils.closeQuietly(serverSocketChannel);
                CloseUtils.closeQuietly(selector);
                throw throwable;
            }
        }
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
            handleUnexpectedTermination(throwable);
        } finally {
            CloseUtils.closeQuietly(selector);
            CloseUtils.closeQuietly(serverSocketChannel);
        }
    }

    private void handleUnexpectedTermination(Throwable throwable) {
        stopAccepting.set(true);
        stopConnections.set(true);
        selector.wakeup();
        connectionEventLoops.forEach(ConnectionEventLoop::wakeup);
        if (!unexpectedTerminationNotified.compareAndSet(false, true)) {
            return;
        }

        try {
            connectionListener.didTerminateEventLoop(this, throwable);
        } catch (Throwable ignored) {
            // No safe fallback sink is available from an event-loop thread.
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

        if (socketChannel == null) {
            // accept() returned without throwing (no pending connection), so the accept path is healthy again
            noteAcceptRecovery();
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
            ConnectionAdmission admission = tryReserveConnection();
            if (admission == null) {
                if (logger.enabled()) {
                    logger.log(
                            new LogEntry("event", "accept_reject_max_connections"),
                            new LogEntry("max_connections", Integer.toString(options.maxConnections())));
                }
                connectionListener.didFailToAcceptConnection(remoteAddress);
                CloseUtils.closeQuietly(socketChannel);
                // An intentional admission decision, not accept-path ill health
                noteAcceptRecovery();
                return false;
            }

            boolean registrationTransferred = false;
            try {
                connectionListener.didAcceptConnection(remoteAddress);
                ConnectionEventLoop connectionEventLoop = leastConnections();
                connectionEventLoop.register(socketChannel, admission);
                // register() owns both the socket and admission ticket once it returns.
                registrationTransferred = true;
                // The full accept iteration succeeded, so the accept path is healthy again
                noteAcceptRecovery();
                return true;
            } finally {
                // Listener/setup failures happen after the reservation but before ownership is
                // transferred to a connection loop.
                if (!registrationTransferred) {
                    admission.release();
                }
            }
        } catch (RuntimeException e) {
            CloseUtils.closeQuietly(socketChannel);
            connectionListener.didFailToAcceptConnection(remoteAddress, e);
            handleConnectionSetupFailure(e);
            return false;
        }
    }

    private ConnectionEventLoop leastConnections() {
        return connectionEventLoops.stream()
                .min(Comparator.comparing(ConnectionEventLoop::connectionLoad))
                .get();
    }

    private @Nullable ConnectionAdmission tryReserveConnection() {
        int maximumConnections = options.maxConnections();
        ConnectionAdmission admission = new ConnectionAdmission(admittedConnections);

        while (true) {
            int currentConnections = admittedConnections.get();
            if (maximumConnections > 0 && currentConnections >= maximumConnections) {
                return null;
            }
            if (admittedConnections.compareAndSet(currentConnections, currentConnections + 1)) {
                return admission;
            }
        }
    }

    public void stop() {
        stopAccepting.set(true);
        stopConnections.set(true);
        selector.wakeup();
        connectionEventLoops.forEach(ConnectionEventLoop::wakeup);
        closeBeforeStartIfNeeded();
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
        closeBeforeStartIfNeeded();
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

    public boolean join(Duration timeout) throws InterruptedException {
        long timeoutNanos = Math.max(0L, timeout.toNanos());
        long deadlineNanos = System.nanoTime() + timeoutNanos;
        if (!joinThread(thread, deadlineNanos)) {
            return false;
        }
        for (ConnectionEventLoop connectionEventLoop : connectionEventLoops) {
            if (!connectionEventLoop.joinUntil(deadlineNanos)) {
                return false;
            }
        }
        return true;
    }

    public void joinAcceptLoop() throws InterruptedException {
        thread.join();
    }

    public void joinConnectionLoops() throws InterruptedException {
        for (ConnectionEventLoop connectionEventLoop : connectionEventLoops) {
            connectionEventLoop.join();
        }
    }

    public boolean isTerminated() {
        return !thread.isAlive()
                && connectionEventLoops.stream().allMatch(ConnectionEventLoop::isTerminated);
    }

    private boolean joinThread(Thread threadToJoin, long deadlineNanos)
            throws InterruptedException {
        while (threadToJoin.isAlive()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return false;
            }
            long millis = remainingNanos / 1_000_000L;
            int nanos = (int) (remainingNanos % 1_000_000L);
            threadToJoin.join(millis, nanos);
        }
        return true;
    }

    public boolean awaitConnectionsDrained(Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Math.max(0L, timeout.toNanos());

        while (admittedConnections.get() > 0) {
            long remainingNanos = deadlineNanos - System.nanoTime();

            if (remainingNanos <= 0L)
                return false;

            Thread.sleep(Math.min(10L, Math.max(1L, remainingNanos / 1_000_000L)));
        }

        return true;
    }

    int numAdmittedConnections() {
        return admittedConnections.get();
    }

    int numPendingConnections() {
        int total = 0;
        for (ConnectionEventLoop loop : connectionEventLoops) {
            total += loop.numPendingRegistrations();
        }
        return total;
    }

    boolean resourcesClosed() {
        if (selector.isOpen() || serverSocketChannel.isOpen()) {
            return false;
        }
        return connectionEventLoops.stream().allMatch(ConnectionEventLoop::resourcesClosed);
    }

    private void closeBeforeStartIfNeeded() {
        synchronized (lifecycleLock) {
            if (started || closedBeforeStart) {
                return;
            }

            closedBeforeStart = true;
            connectionEventLoops.forEach(ConnectionEventLoop::closeBeforeStart);
            CloseUtils.closeQuietly(serverSocketChannel);
            CloseUtils.closeQuietly(selector);
        }
    }

    private void handleAcceptFailure(IOException e) {
        long failures = consecutiveAcceptFailures.incrementAndGet();
        connectionListener.didFailToAcceptConnection(null, e);

        // Coalesce log volume during a sustained failure (e.g. file-descriptor exhaustion):
        // log the first failure and then only at exponentially-spaced milestones.
        if (logger.failureEnabled() && AcceptLoopBackoff.shouldLogFailure(failures)) {
            logger.logFailure(e,
                    new LogEntry("event", "accept_loop_error"),
                    new LogEntry("consecutive_failures", Long.toString(failures)));
        }

        backoffAfterAcceptFailure(failures);
    }

    private void handleConnectionSetupFailure(RuntimeException e) {
        long failures = consecutiveAcceptFailures.incrementAndGet();

        // Coalesce log volume during a sustained failure (e.g. a connection listener that throws
        // on every accept): log the first failure and then only at exponentially-spaced milestones.
        if (logger.failureEnabled() && AcceptLoopBackoff.shouldLogFailure(failures)) {
            logger.logFailure(e,
                    new LogEntry("event", "connection_setup_error"),
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

    // Escalating backoff: a persistent accept() failure (e.g. EMFILE) would otherwise spin the
    // accept loop ~20x/sec. Double the delay per consecutive failure up to a 1s ceiling.
    private void backoffAfterAcceptFailure(long consecutiveFailures) {
        sleepBeforeRetry(AcceptLoopBackoff.backoffMillis(consecutiveFailures));
    }

    // Visible for testing.
    void sleepBeforeRetry(long millis) {
        boolean interrupted = AcceptLoopBackoff.sleepBeforeRetry(millis,
                () -> stopAccepting.get() || stopConnections.get());

        if (interrupted) {
            stopAccepting.set(true);
            stopConnections.set(true);
            connectionEventLoops.forEach(ConnectionEventLoop::wakeup);
        }
    }
}
