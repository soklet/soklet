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

import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;

import static java.lang.String.format;

/**
 * Policy used by the standard HTTP server to decide whether and how gzip-compressed request bodies
 * ({@code Content-Encoding: gzip} or {@code x-gzip}, per
 * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.4">RFC 9110, Section 8.4</a>) are
 * transparently decompressed before request handling. Only a single {@code gzip}/{@code x-gzip} coding is
 * supported; coding chains (e.g. {@code identity, gzip}) are rejected with {@code 415} as sanctioned by
 * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.16">RFC 9110, Section 15.5.16</a>.
 * <p>
 * Decompression is <strong>opt-in</strong> and disabled by default; see
 * {@code HttpServer.Builder#requestDecompressionPolicy(RequestDecompressionPolicy)}. When disabled, request
 * bodies are passed to handlers exactly as received, compressed or not.
 * <p>
 * When enabled:
 * <ul>
 * <li>A request with {@code Content-Encoding: gzip} or {@code x-gzip} has its body decompressed; the
 * {@code Content-Encoding} header is removed and {@code Content-Length} is updated to the decompressed size,
 * so handlers observe a self-consistent uncompressed request.</li>
 * <li>A request with an unsupported {@code Content-Encoding} (or multiple codings) is rejected with
 * {@code 415 Unsupported Media Type}.</li>
 * <li>A request whose body cannot be decompressed is rejected with {@code 400 Bad Request}.</li>
 * <li>A request whose decompressed body exceeds {@link #getMaximumDecompressedBodySizeInBytes()} (or, when
 * unset, the server's {@code maximumRequestSizeInBytes}) or expands beyond
 * {@link #getMaximumCompressionRatio()} is rejected with {@code 413 Content Too Large}. These limits guard
 * against decompression bombs; decompression aborts as soon as a limit is exceeded.</li>
 * <li>{@code Content-Encoding: identity} and requests without a body are passed through unchanged.</li>
 * </ul>
 * <p>
 * This applies to the standard HTTP server only. The SSE and MCP servers do not decompress request bodies,
 * and the {@code Simulator} exercises handlers directly without transport-level decompression.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class RequestDecompressionPolicy {
	@NonNull
	private static final Integer DEFAULT_MAXIMUM_COMPRESSION_RATIO;
	@NonNull
	private static final RequestDecompressionPolicy DISABLED_INSTANCE;
	@NonNull
	private static final RequestDecompressionPolicy DEFAULTS_INSTANCE;

	static {
		DEFAULT_MAXIMUM_COMPRESSION_RATIO = 100;
		DISABLED_INSTANCE = new RequestDecompressionPolicy(false, null, DEFAULT_MAXIMUM_COMPRESSION_RATIO);
		DEFAULTS_INSTANCE = new RequestDecompressionPolicy(true, null, DEFAULT_MAXIMUM_COMPRESSION_RATIO);
	}

	@NonNull
	private final Boolean enabled;
	@Nullable
	private final Integer maximumDecompressedBodySizeInBytes;
	@NonNull
	private final Integer maximumCompressionRatio;

	private RequestDecompressionPolicy(@NonNull Boolean enabled,
																		 @Nullable Integer maximumDecompressedBodySizeInBytes,
																		 @NonNull Integer maximumCompressionRatio) {
		this.enabled = enabled;
		this.maximumDecompressedBodySizeInBytes = maximumDecompressedBodySizeInBytes;
		this.maximumCompressionRatio = maximumCompressionRatio;
	}

	/**
	 * Acquires a policy that disables request decompression (the default): request bodies are passed to
	 * handlers exactly as received.
	 *
	 * @return a disabled request decompression policy
	 */
	@NonNull
	public static RequestDecompressionPolicy disabledInstance() {
		return DISABLED_INSTANCE;
	}

	/**
	 * Acquires a policy that enables gzip request decompression with default limits: the decompressed body is
	 * capped by the server's {@code maximumRequestSizeInBytes} and a {@code 100:1} compression ratio.
	 *
	 * @return a default request decompression policy
	 */
	@NonNull
	public static RequestDecompressionPolicy fromDefaults() {
		return DEFAULTS_INSTANCE;
	}

	/**
	 * Acquires a builder for an enabled request decompression policy with custom limits.
	 *
	 * @return a request decompression policy builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Is request decompression enabled?
	 *
	 * @return {@code true} if gzip request bodies should be decompressed, {@code false} otherwise
	 */
	@NonNull
	public Boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * The maximum permitted decompressed body size in bytes, if customized.
	 * <p>
	 * When empty, the server's {@code maximumRequestSizeInBytes} applies to the decompressed body.
	 *
	 * @return the maximum decompressed body size, or {@link Optional#empty()} to use the server's request size limit
	 */
	@NonNull
	public Optional<Integer> getMaximumDecompressedBodySizeInBytes() {
		return Optional.ofNullable(this.maximumDecompressedBodySizeInBytes);
	}

	/**
	 * The maximum permitted expansion ratio between decompressed and compressed body sizes.
	 * <p>
	 * A request is rejected with {@code 413 Content Too Large} once its decompressed size exceeds
	 * {@code (compressed size × ratio) + 8 KB}; the additive allowance keeps legitimately small compressed
	 * bodies from tripping the ratio check.
	 *
	 * @return the maximum compression ratio (default {@code 100})
	 */
	@NonNull
	public Integer getMaximumCompressionRatio() {
		return this.maximumCompressionRatio;
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{enabled=%s, maximumDecompressedBodySizeInBytes=%s, maximumCompressionRatio=%s}",
				getClass().getSimpleName(), isEnabled(), getMaximumDecompressedBodySizeInBytes().orElse(null),
				getMaximumCompressionRatio());
	}

	/**
	 * Builder for enabled {@link RequestDecompressionPolicy} instances.
	 */
	@ThreadSafe
	public static final class Builder {
		@Nullable
		private Integer maximumDecompressedBodySizeInBytes;
		@Nullable
		private Integer maximumCompressionRatio;

		private Builder() {}

		/**
		 * Sets the maximum permitted decompressed body size in bytes, or {@code null} to use the server's
		 * {@code maximumRequestSizeInBytes}.
		 *
		 * @param maximumDecompressedBodySizeInBytes the maximum decompressed body size, or {@code null} for the server default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumDecompressedBodySizeInBytes(@Nullable Integer maximumDecompressedBodySizeInBytes) {
			this.maximumDecompressedBodySizeInBytes = maximumDecompressedBodySizeInBytes;
			return this;
		}

		/**
		 * Sets the maximum permitted expansion ratio between decompressed and compressed body sizes, or
		 * {@code null} for the default of {@code 100}. Must be between {@code 1} and {@code 10_000};
		 * the upper bound keeps ratio arithmetic safely within {@code long} range for any legal body size
		 * (gzip's theoretical maximum expansion is roughly {@code 1032:1}).
		 *
		 * @param maximumCompressionRatio the maximum compression ratio, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumCompressionRatio(@Nullable Integer maximumCompressionRatio) {
			this.maximumCompressionRatio = maximumCompressionRatio;
			return this;
		}

		/**
		 * Builds an enabled {@link RequestDecompressionPolicy}.
		 *
		 * @return the request decompression policy
		 * @throws IllegalArgumentException if a limit is zero or negative
		 */
		@NonNull
		public RequestDecompressionPolicy build() {
			if (this.maximumDecompressedBodySizeInBytes != null && this.maximumDecompressedBodySizeInBytes <= 0)
				throw new IllegalArgumentException(format("maximumDecompressedBodySizeInBytes must be positive, was %d",
						this.maximumDecompressedBodySizeInBytes));

			if (this.maximumCompressionRatio != null
					&& (this.maximumCompressionRatio <= 0 || this.maximumCompressionRatio > 10_000))
				throw new IllegalArgumentException(format("maximumCompressionRatio must be between 1 and 10000, was %d",
						this.maximumCompressionRatio));

			return new RequestDecompressionPolicy(true, this.maximumDecompressedBodySizeInBytes,
					this.maximumCompressionRatio != null ? this.maximumCompressionRatio : DEFAULT_MAXIMUM_COMPRESSION_RATIO);
		}
	}
}
