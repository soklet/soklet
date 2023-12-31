package com.soklet.internal.microhttp;

import java.time.Duration;

public record Options(String host, int port, boolean reuseAddr, boolean reusePort, Duration resolution,
                      Duration requestTimeout, int readBufferSize, int acceptLength, int maxRequestSize,
                      int concurrency) {

    public static OptionsBuilder builder() {
        return OptionsBuilder.newBuilder();
    }
}
