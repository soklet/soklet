package com.soklet.internal.microhttp;

import org.jspecify.annotations.NonNull;
/**
 * A logger that is disabled and does not log anything.
 */
public final class NoopLogger implements Logger {

    private static final @NonNull NoopLogger INSTANCE = new NoopLogger();

    private NoopLogger() {
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void log(@NonNull LogEntry @NonNull ... entries) {

    }

    @Override
    public void log(@NonNull Exception e, @NonNull LogEntry @NonNull ... entries) {

    }

    public static @NonNull Logger instance() {
        return INSTANCE;
    }

}
