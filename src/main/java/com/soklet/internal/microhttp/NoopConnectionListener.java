package com.soklet.internal.microhttp;

import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;

/**
 * A connection listener that performs no work.
 */
public final class NoopConnectionListener implements ConnectionListener {
    private static final NoopConnectionListener INSTANCE = new NoopConnectionListener();

    private NoopConnectionListener() {
    }

    @Override
    public void willAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
        // No-op
    }

    @Override
    public void didAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
        // No-op
    }

    @Override
    public void didFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
        // No-op
    }

    public static ConnectionListener instance() {
        return INSTANCE;
    }
}
