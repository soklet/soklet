package com.soklet.internal.microhttp;

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
     * Creates a new log event consisting of multiple log entries.
     */
    void log(LogEntry... entries);

    /**
     * Creates a new log event consisting of an exception and multiple log entries.
     */
    void log(Exception e, LogEntry... entries);

    /**
     * Creates a new log event consisting of a throwable and multiple log entries.
     */
    default void log(Throwable throwable, LogEntry... entries) {
        if (throwable instanceof Exception e) {
            log(e, entries);
            return;
        }

        log(new RuntimeException(throwable), entries);
    }

}
