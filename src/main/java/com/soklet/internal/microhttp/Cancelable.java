package com.soklet.internal.microhttp;

/**
 * Task handle returned by {@link Scheduler} that facilitates task cancelation.
 */
interface Cancelable {

    /**
     * Cancel scheduled task.
     */
    void cancel();

}
