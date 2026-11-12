package com.soklet.internal.microhttp;

import org.jspecify.annotations.Nullable;
class MalformedRequestException extends RuntimeException {
    MalformedRequestException(@Nullable String message) {
        super(message);
    }

    MalformedRequestException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    MalformedRequestException(@Nullable Throwable cause) {
        super(cause);
    }
}
