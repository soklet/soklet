package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Optional TCP tuning must not obscure the accepted socket's normal I/O failure path. */
@Timeout(30)
class AcceptedSocketConfigurationTests {
    @Test
    void tcpNoDelayIsSetAfterNonBlockingConfiguration() throws Exception {
        try (OptionSocketChannel socketChannel = new OptionSocketChannel()) {
            ConnectionEventLoop.configureAcceptedSocket(socketChannel);
            assertFalse(socketChannel.isBlocking());
            assertEquals(1, socketChannel.optionAttempts);
            assertTrue(socketChannel.tcpNoDelay);
        }
    }

    @Test
    void optionalTcpTuningFailureLeavesTheSocketAvailableForRegistration() throws Exception {
        try (OptionSocketChannel socketChannel = new OptionSocketChannel()) {
            socketChannel.optionFailure = new IOException("peer reset during optional tuning");
            assertDoesNotThrow(() -> ConnectionEventLoop.configureAcceptedSocket(socketChannel));
            assertFalse(socketChannel.isBlocking());
            assertTrue(socketChannel.isOpen());
            assertEquals(1, socketChannel.optionAttempts);
        }
    }

    @Test
    void mandatoryConfigurationFailureStillPropagatesWithoutAttemptingTheOption() throws Exception {
        try (OptionSocketChannel socketChannel = new OptionSocketChannel()) {
            IOException failure = new IOException("non-blocking setup failed");
            socketChannel.configurationFailure = failure;
            assertSame(failure, assertThrows(IOException.class,
                    () -> ConnectionEventLoop.configureAcceptedSocket(socketChannel)));
            assertEquals(0, socketChannel.optionAttempts);
        }
    }

    @Test
    void unexpectedOptionFailureIsNotSuppressed() throws Exception {
        try (OptionSocketChannel socketChannel = new OptionSocketChannel()) {
            IllegalStateException failure = new IllegalStateException("unexpected option implementation failure");
            socketChannel.unexpectedOptionFailure = failure;
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> ConnectionEventLoop.configureAcceptedSocket(socketChannel)));
        }
    }

    @Test
    void acceptedConnectionEnablesTcpNoDelayBeforeDispatch() throws Exception {
        AtomicReference<SocketChannel> registeredSocket = new AtomicReference<>();
        AtomicReference<Boolean> observedOption = new AtomicReference<>();
        AtomicReference<IOException> observationFailure = new AtomicReference<>();
        EventLoop eventLoop = new EventLoop(Options.builder().withHost("127.0.0.1").withPort(0)
                .withConcurrency(1).withResolution(Duration.ofMillis(5))
                .withRequestHeaderTimeout(Duration.ofSeconds(3))
                .withRequestBodyTimeout(Duration.ofSeconds(3))
                .withResponseWriteIdleTimeout(Duration.ofSeconds(3)).build(), (request, callback) -> {
            try {
                observedOption.set(registeredSocket.get().getOption(StandardSocketOptions.TCP_NODELAY));
            } catch (IOException failure) {
                observationFailure.set(failure);
            }
            callback.accept(new MicrohttpResponse(200, "OK", List.of(), "ok".getBytes(StandardCharsets.US_ASCII)));
        });
        try (ServerSocketChannel listener = ServerSocketChannel.open(); Socket client = new Socket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            client.connect(listener.getLocalAddress(), 3_000);
            client.setSoTimeout(3_000);
            try (SocketChannel acceptedSocket = listener.accept()) {
                // Supply the accepted channel explicitly so the assertion observes the exact
                // channel that the event loop registers, rather than the client-side option.
                registeredSocket.set(acceptedSocket);
                assertTrue(eventLoop.acceptReadyConnection(() -> acceptedSocket));
                eventLoop.start();
                client.getOutputStream().write(("GET / HTTP/1.1\r\nHost: localhost\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 200"), response);
                assertTrue(response.endsWith("ok"), response);
                assertNull(observationFailure.get());
                assertEquals(Boolean.TRUE, observedOption.get());
            }
        } finally {
            eventLoop.stop();
            assertTrue(eventLoop.join(Duration.ofSeconds(3)), "The event loop must terminate within the fixture budget");
        }
    }

    private static final class OptionSocketChannel extends SocketChannel {
        private int optionAttempts;
        private boolean tcpNoDelay;
        private IOException optionFailure;
        private IOException configurationFailure;
        private RuntimeException unexpectedOptionFailure;

        private OptionSocketChannel() { super(SelectorProvider.provider()); }

        @Override protected void implConfigureBlocking(boolean blocking) throws IOException {
            if (configurationFailure != null) throw configurationFailure;
        }
        @Override protected void implCloseSelectableChannel() {}
        @Override public <T> SocketChannel setOption(SocketOption<T> option, T value) throws IOException {
            assertFalse(isBlocking(), "TCP tuning must follow non-blocking setup");
            assertEquals(StandardSocketOptions.TCP_NODELAY, option);
            assertEquals(Boolean.TRUE, value);
            optionAttempts++;
            if (optionFailure != null) throw optionFailure;
            if (unexpectedOptionFailure != null) throw unexpectedOptionFailure;
            tcpNoDelay = true;
            return this;
        }
        @Override public <T> T getOption(SocketOption<T> option) { throw new UnsupportedOperationException(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(StandardSocketOptions.TCP_NODELAY); }
        @Override public SocketChannel bind(SocketAddress local) { throw new UnsupportedOperationException(); }
        @Override public SocketChannel shutdownInput() { throw new UnsupportedOperationException(); }
        @Override public SocketChannel shutdownOutput() { throw new UnsupportedOperationException(); }
        @Override public Socket socket() { throw new UnsupportedOperationException(); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress remote) { throw new UnsupportedOperationException(); }
        @Override public boolean finishConnect() { throw new UnsupportedOperationException(); }
        @Override public SocketAddress getRemoteAddress() { throw new UnsupportedOperationException(); }
        @Override public SocketAddress getLocalAddress() { throw new UnsupportedOperationException(); }
        @Override public int read(ByteBuffer destination) { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] destinations, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer source) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] sources, int offset, int length) { throw new UnsupportedOperationException(); }
    }
}
