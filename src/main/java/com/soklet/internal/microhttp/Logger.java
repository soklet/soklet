package com.soklet.internal.microhttp;

import org.jspecify.annotations.NonNull;
/**
 * Simple logging abstraction that operates on {@link LogEntry} instances.
 *
 * @see NoopLogger for using a logger that is not enabled
 */
public interface Logger {

    /**
     * Indicates whether logger is active.
     */
    boolean enabled();

    /**
     * Indicates whether failure logging is active.
     */
    default boolean failureEnabled() {
        return enabled();
    }

    /**
     * Creates a new log event consisting of multiple log entries.
     */
    void log(@NonNull LogEntry @NonNull ... entries);

    /**
     * Creates a new log event consisting of an exception and multiple log entries.
     */
    void log(@NonNull Exception e, @NonNull LogEntry @NonNull ... entries);

    /**
     * Creates a new failure log event consisting of multiple log entries.
     */
    default void logFailure(@NonNull LogEntry @NonNull ... entries) {
        log(entries);
    }

    /**
     * Creates a new failure log event consisting of an exception and multiple log entries.
     */
    default void logFailure(@NonNull Exception e, @NonNull LogEntry @NonNull ... entries) {
        log(e, entries);
    }

    /**
     * Creates a new log event consisting of a throwable and multiple log entries.
     */
    default void log(@NonNull Throwable throwable, @NonNull LogEntry @NonNull ... entries) {
        if (throwable instanceof Exception e) {
            log(e, entries);
            return;
        }

        log(new RuntimeException(throwable), entries);
    }

    /**
     * Creates a new failure log event consisting of a throwable and multiple log entries.
     */
    default void logFailure(@NonNull Throwable throwable, @NonNull LogEntry @NonNull ... entries) {
        if (throwable instanceof Exception e) {
            logFailure(e, entries);
            return;
        }

        logFailure(new RuntimeException(throwable), entries);
    }

}
