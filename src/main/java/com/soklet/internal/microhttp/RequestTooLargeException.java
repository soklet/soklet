package com.soklet.internal.microhttp;

import org.jspecify.annotations.NonNull;
class RequestTooLargeException extends RuntimeException {
    enum Reason {
        CONTENT,
        HEADERS,
        URI_TOO_LONG
    }

    private final @NonNull Reason reason;

    RequestTooLargeException() {
        this(Reason.CONTENT);
    }

    RequestTooLargeException(@NonNull Reason reason) {
        this.reason = reason;
    }

    @NonNull Reason reason() {
        return reason;
    }
}
