package com.soklet.internal.microhttp;

class MalformedRequestException extends RuntimeException {
    MalformedRequestException(String message) {
        super(message);
    }

    MalformedRequestException(String message, Throwable cause) {
        super(message, cause);
    }

    MalformedRequestException(Throwable cause) {
        super(cause);
    }
}
