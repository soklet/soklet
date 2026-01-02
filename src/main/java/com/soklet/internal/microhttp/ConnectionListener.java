package com.soklet.internal.microhttp;

import java.net.InetSocketAddress;

/**
 * Listener for low-level connection acceptance and rejection events.
 */
public interface ConnectionListener {

    /**
     * Called before a connection is accepted.
     *
     * @param remoteAddress best-effort remote address, or {@code null} if unavailable
     */
    void willAcceptConnection(InetSocketAddress remoteAddress);

    /**
     * Called when a connection is accepted.
     *
     * @param remoteAddress best-effort remote address, or {@code null} if unavailable
     */
    void didAcceptConnection(InetSocketAddress remoteAddress);

    /**
     * Called when a connection fails to be accepted.
     *
     * @param remoteAddress best-effort remote address, or {@code null} if unavailable
     */
    void didFailToAcceptConnection(InetSocketAddress remoteAddress);
}
