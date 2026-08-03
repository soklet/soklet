package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * HTTP request handler.
 */
@FunctionalInterface
public interface Handler {

    /**
     * Handle HTTP request.
     * This method is called on the event loop thread. It must be non-blocking!
     * The callee must invoke the callback at most once. It must eventually invoke the callback unless
     * {@link #cancel(MicrohttpRequest, StreamTerminationReason, Throwable)} wins the pre-commit race first;
     * a callback supplied after cancellation is discarded by the transport.
     * The callback may either be invoked synchronously before handle terminates or
     * asynchronously in a background thread.
     * The provided callback object has a reference to internal connection state.
     * Avoid retaining the callback for an extended period.
     */
    void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback);

    /**
     * Notifies the handler that a dispatched request whose response has not been committed can no longer
     * produce a response. This method may race with the response callback, is invoked at most once for a
     * request, and must not block the connection event loop.
     *
     * @param request the canceled request
     * @param reason the cancellation reason
     * @param cause the cancellation cause, if available
     */
    default void cancel(MicrohttpRequest request, StreamTerminationReason reason,
                        @Nullable Throwable cause) {
        // No-op by default
    }

    /**
     * Whether the transport should continue bounded reads while this request is waiting for its response so
     * it can detect an abortive client disconnect before response commitment. Any pipelined bytes read in
     * this state are retained up to the configured request-size limit and parsed after the response
     * completes. A normal input half-close is not cancellation: the current response and responses for any
     * already-buffered pipelined requests are still written, after which the connection closes.
     *
     * <p>The default is {@code false}, preserving the normal microhttp behavior of suspending socket reads
     * until the response is ready. Long-lived protocols whose request work must be canceled promptly when a
     * client disappears can opt in per request.</p>
     *
     * @param request the request about to be dispatched
     * @return {@code true} to monitor the client connection until response commitment
     */
    default boolean monitorClientDisconnectsBeforeResponse(MicrohttpRequest request) {
        return false;
    }

    /**
     * Whether the transport should continue bounded reads after committing a streaming response so it can
     * detect an abortive client disconnect while the stream is otherwise idle. Positive client bytes are
     * discarded, never parsed or dispatched as pipelined requests, and limited to the configured request-size
     * bound; exceeding that bound terminates the connection. A normal input half-close is not cancellation and
     * does not prevent the committed stream from continuing to write.
     *
     * <p>This hook is consulted only when the response committed for {@code request} is streaming. The default
     * is {@code false}, preserving ordinary microhttp response behavior.</p>
     *
     * @param request the request whose streaming response is about to be committed
     * @return {@code true} to monitor the client connection for the lifetime of the streaming response
     */
    default boolean monitorClientDisconnectsDuringStreamingResponse(MicrohttpRequest request) {
        return false;
    }

}
