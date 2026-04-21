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

import static java.util.Objects.requireNonNull;

/**
 * Options for non-network {@link Simulator} execution.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class SimulatorOptions {
	@NonNull
	private static final Integer DEFAULT_STREAMING_RESPONSE_BODY_LIMIT_IN_BYTES;
	@NonNull
	private static final SimulatorOptions DEFAULT_INSTANCE;

	static {
		DEFAULT_STREAMING_RESPONSE_BODY_LIMIT_IN_BYTES = 1_024 * 1_024 * 10;
		DEFAULT_INSTANCE = builder().build();
	}

	@NonNull
	private final Integer streamingResponseBodyLimitInBytes;

	/**
	 * Acquires a builder for {@link SimulatorOptions}.
	 *
	 * @return the builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Acquires the default simulator options.
	 *
	 * @return the default simulator options
	 */
	@NonNull
	public static SimulatorOptions defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	protected SimulatorOptions(@NonNull Builder builder) {
		requireNonNull(builder);

		this.streamingResponseBodyLimitInBytes = builder.streamingResponseBodyLimitInBytes == null
				? DEFAULT_STREAMING_RESPONSE_BODY_LIMIT_IN_BYTES
				: builder.streamingResponseBodyLimitInBytes;

		if (this.streamingResponseBodyLimitInBytes < 0)
			throw new IllegalArgumentException("Streaming response body limit must be >= 0");
	}

	/**
	 * The maximum number of streaming response bytes the simulator may materialize.
	 *
	 * @return the maximum materialized streaming response bytes
	 */
	@NonNull
	public Integer getStreamingResponseBodyLimitInBytes() {
		return this.streamingResponseBodyLimitInBytes;
	}

	/**
	 * Builder for {@link SimulatorOptions}.
	 * <p>
	 * This class is intended for use by a single thread.
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private Integer streamingResponseBodyLimitInBytes;

		private Builder() {
			// No-op
		}

		/**
		 * Sets the maximum number of streaming response bytes the simulator may materialize.
		 *
		 * @param streamingResponseBodyLimitInBytes the maximum number of bytes, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder streamingResponseBodyLimitInBytes(@Nullable Integer streamingResponseBodyLimitInBytes) {
			this.streamingResponseBodyLimitInBytes = streamingResponseBodyLimitInBytes;
			return this;
		}

		/**
		 * Builds a {@link SimulatorOptions} instance.
		 *
		 * @return simulator options
		 */
		@NonNull
		public SimulatorOptions build() {
			return new SimulatorOptions(this);
		}
	}
}
