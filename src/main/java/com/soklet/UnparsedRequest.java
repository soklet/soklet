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

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable snapshot of incoming bytes rejected by a server before a valid
 * {@link Request} could be constructed.
 * <p>
 * The built-in standard HTTP transport retains at most 64 KiB of wire input
 * attributed to the rejected request through a parser-proven failure boundary.
 * {@link #getObservedByteCount()} reports the number of attributed bytes through
 * that boundary, while {@link #isCaptureTruncated()} indicates whether the
 * retained prefix omits any of those bytes. Bytes already read from the socket
 * beyond the boundary are not counted because they might belong to a pipelined
 * request. Captured bytes are untrusted and may contain credentials or other
 * sensitive values; applications should apply their own redaction and retention
 * policies before logging or storing them.
 * <p>
 * Diagnostic rendering intentionally omits both captured bytes and the remote
 * address.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class UnparsedRequest {
	@NonNull
	private final ServerType serverType;
	@NonNull
	private final UnparsedRequestReason reason;
	@Nullable
	private final InetSocketAddress remoteAddress;
	private final byte @NonNull [] capturedBytes;
	@NonNull
	private final Long observedByteCount;
	@NonNull
	private final Boolean captureTruncated;

	/**
	 * Acquires a builder for an unparsed request.
	 *
	 * @param serverType the type of server that rejected the incoming bytes
	 * @param reason the typed reason no valid request could be constructed
	 * @return an unparsed-request builder
	 * @throws NullPointerException if an argument is {@code null}
	 */
	@NonNull
	public static Builder withServerTypeAndReason(
			@NonNull ServerType serverType,
			@NonNull UnparsedRequestReason reason) {
		return new Builder(serverType, reason);
	}

	private UnparsedRequest(@NonNull Builder builder) {
		Builder exactBuilder = requireNonNull(builder);
		this.serverType = exactBuilder.serverType;
		this.reason = exactBuilder.reason;
		this.remoteAddress = exactBuilder.remoteAddress;
		this.capturedBytes = exactBuilder.capturedBytes.clone();
		this.observedByteCount = exactBuilder.observedByteCount;
		this.captureTruncated = exactBuilder.captureTruncated;

		if (this.observedByteCount < this.capturedBytes.length)
			throw new IllegalArgumentException(
					"Observed byte count must not be smaller than the captured byte-prefix length.");
		if (this.captureTruncated
				&& this.observedByteCount <= this.capturedBytes.length)
			throw new IllegalArgumentException(
					"A truncated capture must omit at least one observed byte.");
		if (!this.captureTruncated
				&& this.observedByteCount != this.capturedBytes.length)
			throw new IllegalArgumentException(
					"A complete capture must contain every observed byte.");
	}

	/** @return the type of server that rejected the incoming bytes */
	@NonNull
	public ServerType getServerType() {
		return this.serverType;
	}

	/** @return the typed reason no valid request could be constructed */
	@NonNull
	public UnparsedRequestReason getReason() {
		return this.reason;
	}

	/**
	 * Returns the best-effort remote network address for the rejected request.
	 *
	 * @return the remote address, or {@link Optional#empty()} if unavailable
	 */
	@NonNull
	public Optional<@NonNull InetSocketAddress> getRemoteAddress() {
		return Optional.ofNullable(this.remoteAddress);
	}

	/**
	 * Returns the retained prefix of exact wire bytes attributed to the rejected
	 * request through the parser-proven failure boundary.
	 * <p>
	 * Each invocation returns a fresh read-only view with position {@code 0} and
	 * no accessible backing array.
	 *
	 * @return a fresh read-only view of the captured byte prefix
	 */
	@NonNull
	public ByteBuffer getCapturedBytes() {
		return ByteBuffer.wrap(this.capturedBytes).asReadOnlyBuffer();
	}

	/**
	 * Returns the number of wire bytes attributed to this rejected request through
	 * the parser-proven failure boundary. This can be larger than the number
	 * returned by
	 * {@link ByteBuffer#remaining()} on {@link #getCapturedBytes()}.
	 * <p>
	 * The value does not include bytes already read from the socket beyond that
	 * boundary because they might belong to a pipelined request.
	 *
	 * @return the parser-attributed byte count through the failure boundary
	 */
	@NonNull
	public Long getObservedByteCount() {
		return this.observedByteCount;
	}

	/**
	 * Indicates whether {@link #getCapturedBytes()} is an incomplete prefix of
	 * the wire input attributed to the rejected request through the parser-proven
	 * failure boundary.
	 *
	 * @return {@code true} if captured bytes were truncated
	 */
	@NonNull
	public Boolean isCaptureTruncated() {
		return this.captureTruncated;
	}

	/** @return whether all captured request components are equal */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other
				|| other instanceof UnparsedRequest request
				&& this.serverType == request.serverType
				&& this.reason == request.reason
				&& Objects.equals(this.remoteAddress, request.remoteAddress)
				&& Arrays.equals(this.capturedBytes, request.capturedBytes)
				&& Objects.equals(this.observedByteCount,
						request.observedByteCount)
				&& Objects.equals(this.captureTruncated,
						request.captureTruncated);
	}

	/** @return value-based hash code */
	@Override
	public int hashCode() {
		int result = Objects.hash(this.serverType, this.reason,
				this.remoteAddress, this.observedByteCount,
				this.captureTruncated);
		return 31 * result + Arrays.hashCode(this.capturedBytes);
	}

	/** @return diagnostic rendering that omits captured bytes and address */
	@Override
	@NonNull
	public String toString() {
		return "%s{serverType=%s, reason=%s, observedByteCount=%s, captureTruncated=%s}"
				.formatted(getClass().getSimpleName(), getServerType(), getReason(),
						getObservedByteCount(), isCaptureTruncated());
	}

	/**
	 * Builder used to construct an immutable {@link UnparsedRequest}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final ServerType serverType;
		@NonNull
		private final UnparsedRequestReason reason;
		@Nullable
		private InetSocketAddress remoteAddress;
		private byte @NonNull [] capturedBytes;
		@NonNull
		private Long observedByteCount;
		@NonNull
		private Boolean captureTruncated;

		private Builder(@NonNull ServerType serverType,
				@NonNull UnparsedRequestReason reason) {
			this.serverType = requireNonNull(serverType);
			this.reason = requireNonNull(reason);
			this.capturedBytes = new byte[0];
			this.observedByteCount = 0L;
			this.captureTruncated = false;
		}

		/**
		 * Sets the best-effort remote network address. Passing {@code null}
		 * clears any previously configured address.
		 *
		 * @param remoteAddress the remote address, or {@code null} if unavailable
		 * @return this builder
		 */
		@NonNull
		public Builder remoteAddress(
				@Nullable InetSocketAddress remoteAddress) {
			this.remoteAddress = remoteAddress;
			return this;
		}

		/**
		 * Sets the retained prefix of exact wire bytes attributed to the rejected
		 * request through its failure boundary. The bytes are defensively copied when
		 * {@link #build()} is invoked.
		 *
		 * @param capturedBytes captured wire-byte prefix
		 * @return this builder
		 * @throws NullPointerException if {@code capturedBytes} is {@code null}
		 */
		@NonNull
		public Builder capturedBytes(byte @NonNull [] capturedBytes) {
			this.capturedBytes = requireNonNull(capturedBytes);
			return this;
		}

		/**
		 * Sets the number of wire bytes attributed to this rejected request through
		 * its failure boundary.
		 *
		 * @param observedByteCount parser-attributed byte count through the failure
		 * boundary
		 * @return this builder
		 * @throws NullPointerException if {@code observedByteCount} is {@code null}
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder observedByteCount(@NonNull Long observedByteCount) {
			requireNonNull(observedByteCount);
			if (observedByteCount < 0)
				throw new IllegalArgumentException(
						"Observed byte count must be >= 0.");
			this.observedByteCount = observedByteCount;
			return this;
		}

		/**
		 * Specifies whether the captured bytes are an incomplete prefix of the
		 * wire input attributed to the rejected request through its failure boundary.
		 *
		 * @param captureTruncated {@code true} if the capture is incomplete
		 * @return this builder
		 * @throws NullPointerException if {@code captureTruncated} is {@code null}
		 */
		@NonNull
		public Builder captureTruncated(@NonNull Boolean captureTruncated) {
			this.captureTruncated = requireNonNull(captureTruncated);
			return this;
		}

		/**
		 * Builds an immutable unparsed-request snapshot.
		 *
		 * @return an immutable unparsed request
		 * @throws IllegalArgumentException if captured-byte metadata is
		 * inconsistent
		 */
		@NonNull
		public UnparsedRequest build() {
			return new UnparsedRequest(this);
		}
	}
}
