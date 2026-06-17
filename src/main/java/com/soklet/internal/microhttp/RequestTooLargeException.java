package com.soklet.internal.microhttp;

class RequestTooLargeException extends RuntimeException {
    enum Reason {
        CONTENT,
        HEADERS
    }

    private final Reason reason;

    RequestTooLargeException() {
        this(Reason.CONTENT);
    }

    RequestTooLargeException(Reason reason) {
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
