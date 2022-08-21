/**
 * MIT License
 *
 * Copyright (c) 2022 Elliot Barlas
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.soklet.microhttp;

import java.time.Duration;

public class Options {

    private String host = "localhost";
    private int port = 8080;
    private boolean reuseAddr = false;
    private boolean reusePort = false;
    private Duration resolution = Duration.ofMillis(100);
    private Duration requestTimeout = Duration.ofSeconds(60);
    private int readBufferSize = 1_024 * 64;
    private int acceptLength = 0;
    private int maxRequestSize = 1_024 * 1_024;

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean reuseAddr() {
        return reuseAddr;
    }

    public boolean reusePort() {
        return reusePort;
    }

    public Duration resolution() {
        return resolution;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int readBufferSize() {
        return readBufferSize;
    }

    public int acceptLength() {
        return acceptLength;
    }

    public int maxRequestSize() {
        return maxRequestSize;
    }

    public Options withHost(String host) {
        this.host = host;
        return this;
    }

    public Options withPort(int port) {
        this.port = port;
        return this;
    }

    public Options withReuseAddr(boolean reuseAddr) {
        this.reuseAddr = reuseAddr;
        return this;
    }

    public Options withReusePort(boolean reusePort) {
        this.reusePort = reusePort;
        return this;
    }

    public Options withResolution(Duration resolution) {
        this.resolution = resolution;
        return this;
    }

    public Options withRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        return this;
    }

    public Options withReadBufferSize(int readBufferSize) {
        this.readBufferSize = readBufferSize;
        return this;
    }

    public Options withAcceptLength(int acceptLength) {
        this.acceptLength = acceptLength;
        return this;
    }

    public Options withMaxRequestSize(int maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
        return this;
    }
}
