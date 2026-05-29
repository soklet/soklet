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
     * Indicates whether failure logging is active.
     */
    default boolean failureEnabled() {
        return enabled();
    }

    /**
     * Creates a new log event consisting of multiple log entries.
     */
    void log(LogEntry... entries);

    /**
     * Creates a new log event consisting of an exception and multiple log entries.
     */
    void log(Exception e, LogEntry... entries);

    /**
     * Creates a new failure log event consisting of multiple log entries.
     */
    default void logFailure(LogEntry... entries) {
        log(entries);
    }

    /**
     * Creates a new failure log event consisting of an exception and multiple log entries.
     */
    default void logFailure(Exception e, LogEntry... entries) {
        log(e, entries);
    }

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

    /**
     * Creates a new failure log event consisting of a throwable and multiple log entries.
     */
    default void logFailure(Throwable throwable, LogEntry... entries) {
        if (throwable instanceof Exception e) {
            logFailure(e, entries);
            return;
        }

        logFailure(new RuntimeException(throwable), entries);
    }

}
