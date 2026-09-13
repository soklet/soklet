/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.microhttp;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;

import static java.util.Objects.requireNonNull;

/**
 * A transport-owned snapshot of one parser rejection. The captured bytes end
 * at a parser-proven boundary and therefore never include a later pipelined
 * request merely because it arrived in the same socket read.
 */
public final class UnparsedRequestRejection {
    public enum Reason {
        MALFORMED_REQUEST,
        REQUEST_TARGET_TOO_LONG,
        EXPECTATION_FAILED,
        REQUEST_HEADERS_TOO_LARGE
    }

    private final Reason reason;
    private final @Nullable InetSocketAddress remoteAddress;
    private final byte[] capturedBytes;
    private final long observedByteCount;
    private final boolean captureTruncated;

    UnparsedRequestRejection(@NonNull Reason reason,
                             @Nullable InetSocketAddress remoteAddress,
                             byte @NonNull [] capturedBytes,
                             long observedByteCount,
                             boolean captureTruncated) {
        this.reason = requireNonNull(reason);
        this.remoteAddress = remoteAddress;
        this.capturedBytes = requireNonNull(capturedBytes);
        this.observedByteCount = observedByteCount;
        this.captureTruncated = captureTruncated;
    }

    @NonNull
    public Reason reason() {
        return this.reason;
    }

    public @Nullable InetSocketAddress remoteAddress() {
        return this.remoteAddress;
    }

    /**
     * Transfers the transport-owned capture to the framework adapter. Callers
     * must not mutate or retain this array after constructing the public value.
     */
    public byte @NonNull [] capturedBytesForTransfer() {
        return this.capturedBytes;
    }

    public long observedByteCount() {
        return this.observedByteCount;
    }

    public boolean captureTruncated() {
        return this.captureTruncated;
    }
}
