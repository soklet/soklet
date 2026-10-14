package com.soklet.internal.microhttp;

import java.time.Duration;
import java.util.List;

public record Options(String host, int port, boolean reuseAddr, boolean reusePort, Duration resolution,
                      Duration requestHeaderTimeout, Duration requestBodyTimeout, Duration responseWriteIdleTimeout,
                      int readBufferSize, int acceptLength, int maxRequestSize, int maxRequestBodySize, int maxHeaderCount,
                      int maxHeadersSize, int maxRequestTargetLength, int maxConnections, int concurrency,
                      List<Header> earlyErrorResponseHeaders,
                      int unparsedRequestCaptureLimitInBytes,
                      int unparsedResponseSizeLimitInBytes) {

    public Options {
        earlyErrorResponseHeaders = List.copyOf(earlyErrorResponseHeaders);
    }

    public Options(String host, int port, boolean reuseAddr, boolean reusePort, Duration resolution,
                   Duration requestHeaderTimeout, Duration requestBodyTimeout, Duration responseWriteIdleTimeout,
                   int readBufferSize, int acceptLength, int maxRequestSize, int maxHeaderCount,
                   int maxHeadersSize, int maxRequestTargetLength, int maxConnections, int concurrency) {
        this(host, port, reuseAddr, reusePort, resolution, requestHeaderTimeout, requestBodyTimeout,
                responseWriteIdleTimeout, readBufferSize, acceptLength, maxRequestSize, maxRequestSize,
                maxHeaderCount, maxHeadersSize, maxRequestTargetLength, maxConnections, concurrency,
                List.of(), 0, 64 * 1024);
    }

    long selectTimeoutMillis() {
        // Selector.select(0) waits indefinitely; a positive submillisecond
        // duration must still wake the loop to process request/write deadlines.
        if (resolution.isZero()) return 0L;
        try {
            long millis = resolution.toMillis();
            return resolution.toNanosPart() % 1_000_000 != 0 && millis < Long.MAX_VALUE
                    ? millis + 1L : millis;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public static OptionsBuilder builder() {
        return OptionsBuilder.newBuilder();
    }
}
