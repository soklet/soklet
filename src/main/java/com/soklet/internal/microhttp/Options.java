package com.soklet.internal.microhttp;

import java.time.Duration;
import java.util.List;

public record Options(String host, int port, boolean reuseAddr, boolean reusePort, Duration resolution,
                      Duration requestHeaderTimeout, Duration requestBodyTimeout, Duration responseWriteIdleTimeout,
                      int readBufferSize, int acceptLength, int maxRequestSize, int maxRequestBodySize, int maxHeaderCount,
                      int maxHeadersSize, int maxRequestTargetLength, int maxConnections, int concurrency,
                      List<Header> earlyErrorResponseHeaders) {

    public Options {
        earlyErrorResponseHeaders = List.copyOf(earlyErrorResponseHeaders);
    }

    public Options(String host, int port, boolean reuseAddr, boolean reusePort, Duration resolution,
                   Duration requestHeaderTimeout, Duration requestBodyTimeout, Duration responseWriteIdleTimeout,
                   int readBufferSize, int acceptLength, int maxRequestSize, int maxHeaderCount,
                   int maxHeadersSize, int maxRequestTargetLength, int maxConnections, int concurrency) {
        this(host, port, reuseAddr, reusePort, resolution, requestHeaderTimeout, requestBodyTimeout,
                responseWriteIdleTimeout, readBufferSize, acceptLength, maxRequestSize, maxRequestSize,
                maxHeaderCount, maxHeadersSize, maxRequestTargetLength, maxConnections, concurrency, List.of());
    }

    public static OptionsBuilder builder() {
        return OptionsBuilder.newBuilder();
    }
}
