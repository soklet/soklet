package com.soklet.internal.microhttp;

import org.jspecify.annotations.Nullable;
class ExpectationFailedException extends RuntimeException {
    ExpectationFailedException(@Nullable String message) {
        super(message);
    }
}
