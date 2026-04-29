package com.soklet.internal.microhttp;

import java.time.Duration;

public record Options(String host, int port, boolean reuseAddr, boolean reusePort, Duration resolution,
                      Duration requestHeaderTimeout, Duration requestBodyTimeout, int readBufferSize,
                      int acceptLength, int maxRequestSize, int maxHeaderCount,
                      int maxRequestTargetLength, int maxConnections, int concurrency) {

    public static OptionsBuilder builder() {
        return OptionsBuilder.newBuilder();
    }
}
