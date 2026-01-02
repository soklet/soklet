package com.soklet.internal.microhttp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
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

    private final Options options;
    private final Logger logger;
    private final ConnectionListener connectionListener;

    private final Selector selector;
    private final AtomicBoolean stop;
    private final ServerSocketChannel serverSocketChannel;
    private final List<ConnectionEventLoop> connectionEventLoops;
    private final Thread thread;

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
        stop = new AtomicBoolean();

        AtomicLong connectionCounter = new AtomicLong();
        connectionEventLoops = new ArrayList<>();
        for (int i = 0; i < options.concurrency(); i++) {
            connectionEventLoops.add(new ConnectionEventLoop(options, logger, handler, connectionCounter, stop));
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
        } catch (IOException e) {
            if (logger.enabled()) {
                logger.log(e, new LogEntry("event", "event_loop_terminate"));
            }
            stop.set(true); // stop the world on critical error
        } finally {
            CloseUtils.closeQuietly(selector);
            CloseUtils.closeQuietly(serverSocketChannel);
        }
    }

    private void doRun() throws IOException {
        while (!stop.get()) {
            selector.select(options.resolution().toMillis());
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectedKeys.iterator();
            while (it.hasNext()) {
                SelectionKey selKey = it.next();
                if (selKey.isAcceptable()) {
                    SocketChannel socketChannel = serverSocketChannel.accept();
                    if (socketChannel == null) {
                        it.remove();
                        continue;
                    }
                    InetSocketAddress remoteAddress = null;
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
                        it.remove();
                        continue;
                    }
                    connectionListener.didAcceptConnection(remoteAddress);
                    ConnectionEventLoop connectionEventLoop = leastConnections();
                    connectionEventLoop.register(socketChannel);
                }
                it.remove();
            }
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
        stop.set(true);
    }

    public void join() throws InterruptedException {
        thread.join();
        for (ConnectionEventLoop connectionEventLoop : connectionEventLoops) {
            connectionEventLoop.join();
        }
    }
}
