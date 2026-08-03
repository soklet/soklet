package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class EventLoopConnectionAdmissionTests {
    @Test
    public void maxConnectionsIncludesAcceptedSocketsPendingRegistration() throws Exception {
        int maximumConnections = 3;
        int attemptedConnections = 32;
        AtomicInteger acceptedConnections = new AtomicInteger();
        AtomicInteger rejectedConnections = new AtomicInteger();
        EventLoop eventLoop = new EventLoop(
                testOptions(maximumConnections, 2),
                NoopLogger.instance(),
                (request, callback) -> {},
                recordingConnectionListener(acceptedConnections, rejectedConnections));
        List<SocketChannel> clientChannels = new ArrayList<>();
        List<SocketChannel> serverChannels = new ArrayList<>();

        try (ServerSocketChannel peerListener = ServerSocketChannel.open()) {
            peerListener.bind(new InetSocketAddress("127.0.0.1", 0));
            InetSocketAddress peerAddress = (InetSocketAddress) peerListener.getLocalAddress();

            // The connection loops have deliberately not been started, so every successful
            // admission remains pending. This makes the old registered-only accounting bug
            // deterministic: all attempts used to pass while the registered count stayed zero.
            for (int i = 0; i < attemptedConnections; i++) {
                SocketChannel clientChannel = SocketChannel.open(peerAddress);
                SocketChannel serverChannel = peerListener.accept();
                clientChannels.add(clientChannel);
                serverChannels.add(serverChannel);

                boolean accepted = eventLoop.acceptReadyConnection(() -> serverChannel);
                Assertions.assertEquals(i < maximumConnections, accepted);
                Assertions.assertTrue(eventLoop.numAdmittedConnections() <= maximumConnections,
                        "Accepted plus pending connections exceeded the configured maximum");
                Assertions.assertEquals(Math.min(i + 1, maximumConnections),
                        eventLoop.numAdmittedConnections());
                Assertions.assertEquals(eventLoop.numAdmittedConnections(),
                        eventLoop.numPendingConnections());
            }

            Assertions.assertEquals(maximumConnections, acceptedConnections.get());
            Assertions.assertEquals(attemptedConnections - maximumConnections, rejectedConnections.get());
            Assertions.assertEquals(maximumConnections,
                    serverChannels.stream().filter(SocketChannel::isOpen).count());

            eventLoop.stop();
            eventLoop.join();

            Assertions.assertEquals(0, eventLoop.numAdmittedConnections());
            Assertions.assertEquals(0, eventLoop.numPendingConnections());
            Assertions.assertTrue(serverChannels.stream().noneMatch(SocketChannel::isOpen));
            Assertions.assertTrue(eventLoop.resourcesClosed());
        } finally {
            eventLoop.stop();
            eventLoop.join();
            closeAll(clientChannels);
            closeAll(serverChannels);
        }
    }

    @Test
    public void listenerFailureAfterReservationReleasesConnectionSlot() throws Exception {
        AtomicBoolean rejectFirstConnection = new AtomicBoolean(true);
        AtomicInteger acceptedConnections = new AtomicInteger();
        AtomicInteger failedConnections = new AtomicInteger();
        ConnectionListener listener = new ConnectionListener() {
            @Override
            public void willAcceptConnection(InetSocketAddress remoteAddress) {
                // No-op
            }

            @Override
            public void didAcceptConnection(InetSocketAddress remoteAddress) {
                if (rejectFirstConnection.compareAndSet(true, false)) {
                    throw new IllegalStateException("synthetic listener failure");
                }
                acceptedConnections.incrementAndGet();
            }

            @Override
            public void didFailToAcceptConnection(InetSocketAddress remoteAddress) {
                failedConnections.incrementAndGet();
            }
        };
        EventLoop eventLoop = new EventLoop(
                testOptions(1, 1), NoopLogger.instance(), (request, callback) -> {}, listener);
        List<SocketChannel> clientChannels = new ArrayList<>();
        List<SocketChannel> serverChannels = new ArrayList<>();

        try (ServerSocketChannel peerListener = ServerSocketChannel.open()) {
            peerListener.bind(new InetSocketAddress("127.0.0.1", 0));
            InetSocketAddress peerAddress = (InetSocketAddress) peerListener.getLocalAddress();

            SocketChannel first = openServerSideChannel(peerListener, peerAddress, clientChannels);
            serverChannels.add(first);
            Assertions.assertFalse(eventLoop.acceptReadyConnection(() -> first));
            Assertions.assertFalse(first.isOpen());
            Assertions.assertEquals(0, eventLoop.numAdmittedConnections());

            SocketChannel second = openServerSideChannel(peerListener, peerAddress, clientChannels);
            serverChannels.add(second);
            Assertions.assertTrue(eventLoop.acceptReadyConnection(() -> second));
            Assertions.assertEquals(1, eventLoop.numAdmittedConnections());
            Assertions.assertEquals(1, eventLoop.numPendingConnections());
            Assertions.assertEquals(1, acceptedConnections.get());
            Assertions.assertEquals(1, failedConnections.get());
        } finally {
            eventLoop.stop();
            eventLoop.join();
            closeAll(clientChannels);
            closeAll(serverChannels);
        }

        Assertions.assertEquals(0, eventLoop.numAdmittedConnections());
        Assertions.assertTrue(eventLoop.resourcesClosed());
    }

    @Test
    public void stopBeforeStartClosesBoundSocketAndEverySelectorIdempotently() throws Exception {
        EventLoop eventLoop = new EventLoop(testOptions(1, 2), (request, callback) -> {});
        int port = eventLoop.getPort();

        eventLoop.stop();
        eventLoop.join();
        eventLoop.stop();
        eventLoop.join();

        Assertions.assertTrue(eventLoop.resourcesClosed());
        Assertions.assertThrows(IOException.class, eventLoop::getPort);

        // The constructor-bound listener must be released synchronously, not only when a never-
        // started thread's finally block happens to run.
        try (ServerSocketChannel rebound = ServerSocketChannel.open()) {
            rebound.bind(new InetSocketAddress("127.0.0.1", port));
        }

        // Preserve the historical cleanup idiom used by existing tests: start after a pre-start
        // stop is a harmless no-op, and join remains safe.
        eventLoop.start();
        eventLoop.join();
        Assertions.assertTrue(eventLoop.resourcesClosed());
    }

    private static Options testOptions(int maximumConnections, int concurrency) {
        return Options.builder()
                .withHost("127.0.0.1")
                .withPort(0)
                .withResolution(Duration.ofMillis(10))
                .withMaxConnections(maximumConnections)
                .withConcurrency(concurrency)
                .build();
    }

    private static ConnectionListener recordingConnectionListener(AtomicInteger accepted,
                                                                   AtomicInteger rejected) {
        return new ConnectionListener() {
            @Override
            public void willAcceptConnection(InetSocketAddress remoteAddress) {
                // No-op
            }

            @Override
            public void didAcceptConnection(InetSocketAddress remoteAddress) {
                accepted.incrementAndGet();
            }

            @Override
            public void didFailToAcceptConnection(InetSocketAddress remoteAddress) {
                rejected.incrementAndGet();
            }
        };
    }

    private static SocketChannel openServerSideChannel(ServerSocketChannel peerListener,
                                                       InetSocketAddress peerAddress,
                                                       List<SocketChannel> clientChannels)
            throws IOException {
        clientChannels.add(SocketChannel.open(peerAddress));
        return peerListener.accept();
    }

    private static void closeAll(List<SocketChannel> channels) {
        for (SocketChannel channel : channels) {
            CloseUtils.closeQuietly(channel);
        }
    }
}
