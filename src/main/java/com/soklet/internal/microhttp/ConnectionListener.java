package com.soklet.internal.microhttp;

import org.jspecify.annotations.Nullable;

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
    void willAcceptConnection(@Nullable InetSocketAddress remoteAddress);

    /**
     * Called when a connection is accepted.
     *
     * @param remoteAddress best-effort remote address, or {@code null} if unavailable
     */
    void didAcceptConnection(@Nullable InetSocketAddress remoteAddress);

    /**
     * Called when a connection fails to be accepted.
     *
     * @param remoteAddress best-effort remote address, or {@code null} if unavailable
     */
    void didFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress);

    /**
     * Called when a connection fails to be accepted.
     *
     * @param remoteAddress best-effort remote address, or {@code null} if unavailable
     * @param throwable     the failure, or {@code null} if unavailable
     */
    default void didFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress,
                                           @Nullable Throwable throwable) {
        didFailToAcceptConnection(remoteAddress);
    }
}
