package com.soklet.microhttp;

import java.util.function.Consumer;

/**
 * HTTP request handler.
 */
public interface Handler {

    /**
     * Handle HTTP request.
     * This method is called on the event loop thread. It must be non-blocking!
     * The callee must invoke the callback once and only once.
     * The callback may either be invoked synchronously before handle terminates or
     * asynchronously in a background thread.
     * The provided callback object has a reference to internal connection state.
     * Avoid retaining the callback for an extended period.
     */
    void handle(Request request, Consumer<Response> callback);

}
