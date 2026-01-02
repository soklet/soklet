package com.soklet.internal.microhttp;

import java.net.InetSocketAddress;

/**
 * A connection listener that performs no work.
 */
public final class NoopConnectionListener implements ConnectionListener {
    private static final NoopConnectionListener INSTANCE = new NoopConnectionListener();

    private NoopConnectionListener() {
    }

    @Override
    public void willAcceptConnection(InetSocketAddress remoteAddress) {
        // No-op
    }

    @Override
    public void didAcceptConnection(InetSocketAddress remoteAddress) {
        // No-op
    }

    @Override
    public void didFailToAcceptConnection(InetSocketAddress remoteAddress) {
        // No-op
    }

    public static ConnectionListener instance() {
        return INSTANCE;
    }
}
