package com.soklet.internal.microhttp;

class ExpectationFailedException extends RuntimeException {
    ExpectationFailedException(String message) {
        super(message);
    }
}
