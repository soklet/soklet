package com.soklet.internal.microhttp;

import java.time.Duration;

public class OptionsBuilder {
    private String host;
    private int port;
    private boolean reuseAddr;
    private boolean reusePort;
    private Duration resolution;
    private Duration requestHeaderTimeout;
    private Duration requestBodyTimeout;
    private int readBufferSize;
    private int acceptLength;
    private int maxRequestSize;
    private int maxHeaderCount;
    private int maxRequestTargetLength;
    private int maxConnections;
    private int concurrency;

    private OptionsBuilder() {
        this.host = "localhost";
        this.port = 8080;
        this.reuseAddr = false;
        this.reusePort = false;
        this.resolution = Duration.ofMillis(100);
        this.requestHeaderTimeout = null;
        this.requestBodyTimeout = null;
        this.readBufferSize = 1_024 * 64;
        this.acceptLength = 0;
        this.maxRequestSize = 1_024 * 1_024;
        this.maxHeaderCount = 100;
        this.maxRequestTargetLength = 8_192;
        this.maxConnections = 0;
        this.concurrency = Runtime.getRuntime().availableProcessors();
    }

    public static OptionsBuilder newBuilder() {
        return new OptionsBuilder();
    }

    public Options build() {
        return new Options(this.host,
            this.port,
            this.reuseAddr,
            this.reusePort,
            this.resolution,
            this.requestHeaderTimeout == null ? Duration.ofSeconds(60) : this.requestHeaderTimeout,
            this.requestBodyTimeout == null ? Duration.ofSeconds(60) : this.requestBodyTimeout,
            this.readBufferSize,
            this.acceptLength,
            this.maxRequestSize,
            this.maxHeaderCount,
            this.maxRequestTargetLength,
            this.maxConnections,
            this.concurrency);
    }

    public OptionsBuilder withHost(String host) {
        this.host = host;
        return this;
    }

    public OptionsBuilder withPort(int port) {
        this.port = port;
        return this;
    }

    public OptionsBuilder withReuseAddr(boolean reuseAddr) {
        this.reuseAddr = reuseAddr;
        return this;
    }

    public OptionsBuilder withReusePort(boolean reusePort) {
        this.reusePort = reusePort;
        return this;
    }

    public OptionsBuilder withResolution(Duration resolution) {
        this.resolution = resolution;
        return this;
    }

    public OptionsBuilder withRequestHeaderTimeout(Duration requestHeaderTimeout) {
        this.requestHeaderTimeout = requestHeaderTimeout;
        return this;
    }

    public OptionsBuilder withRequestBodyTimeout(Duration requestBodyTimeout) {
        this.requestBodyTimeout = requestBodyTimeout;
        return this;
    }

    public OptionsBuilder withReadBufferSize(int readBufferSize) {
        this.readBufferSize = readBufferSize;
        return this;
    }

    public OptionsBuilder withAcceptLength(int acceptLength) {
        this.acceptLength = acceptLength;
        return this;
    }

    public OptionsBuilder withMaxRequestSize(int maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
        return this;
    }

    public OptionsBuilder withMaxHeaderCount(int maxHeaderCount) {
        this.maxHeaderCount = maxHeaderCount;
        return this;
    }

    public OptionsBuilder withMaxRequestTargetLength(int maxRequestTargetLength) {
        this.maxRequestTargetLength = maxRequestTargetLength;
        return this;
    }

    public OptionsBuilder withMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        return this;
    }

    public OptionsBuilder withConcurrency(int concurrency) {
        this.concurrency = concurrency;
        return this;
    }
}
