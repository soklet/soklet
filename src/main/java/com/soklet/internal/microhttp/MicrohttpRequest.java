package com.soklet.internal.microhttp;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Request objects represent discrete HTTP requests with request line, headers, and body.
 * Request objects have no hidden references to the network protocol layer that produced them.
 * The parser also snapshots incomplete requests for rejection diagnostics; fields not yet
 * parsed remain null. Successful request dispatch fills those fields before handing off.
 */
public record MicrohttpRequest(
        @Nullable String method,
        @Nullable String uri,
        @Nullable String version,
        @Nullable List<@NonNull Header> headers,
        byte @Nullable [] body,
        boolean contentTooLarge,
        @Nullable InetSocketAddress remoteAddress,
        long observedWireByteCount) {

    public MicrohttpRequest(@Nullable String method, @Nullable String uri, @Nullable String version, @Nullable List<@NonNull Header> headers,
                            byte @Nullable [] body, boolean contentTooLarge,
                            @Nullable InetSocketAddress remoteAddress) {
        this(method, uri, version, headers, body, contentTooLarge, remoteAddress, 0L);
    }

    /**
     * Returns the parsed method.
     * @throws IllegalStateException if this diagnostic snapshot does not have the method yet
     */
    public @NonNull String parsedMethod() {
        if (method == null)
            throw new IllegalStateException("The HTTP request method has not been parsed");
        return method;
    }

    /**
     * Returns the parsed request target.
     * @throws IllegalStateException if this diagnostic snapshot does not have the request target yet
     */
    public @NonNull String parsedUri() {
        if (uri == null)
            throw new IllegalStateException("The HTTP request target has not been parsed");
        return uri;
    }

    /**
     * Returns the parsed version.
     * @throws IllegalStateException if this diagnostic snapshot does not have the version yet
     */
    public @NonNull String parsedVersion() {
        if (version == null)
            throw new IllegalStateException("The HTTP request version has not been parsed");
        return version;
    }

    /**
     * Returns the parsed headers.
     * @throws IllegalStateException if this diagnostic snapshot does not have the headers yet
     */
    public @NonNull List<@NonNull Header> parsedHeaders() {
        if (headers == null)
            throw new IllegalStateException("The HTTP request headers have not been parsed");
        return headers;
    }

    /**
     * Returns the parsed body.
     * @throws IllegalStateException if this diagnostic snapshot does not have the body yet
     */
    public byte @NonNull [] parsedBody() {
        if (body == null)
            throw new IllegalStateException("The HTTP request body has not been parsed");
        return body;
    }

    @Nullable
    public String header(@Nullable String name) {
        for (Header header : parsedHeaders()) {
            if (equalsIgnoreCase(header.name(), name)) {
                return header.value();
            }
        }
        return null;
    }

    public boolean hasHeader(@Nullable String name, @Nullable String value) {
        for (Header header : parsedHeaders()) {
            if (equalsIgnoreCase(header.name(), name) && equalsIgnoreCase(header.value(), value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCase(@Nullable String left, @Nullable String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

}
