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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable progress update for one active MCP invocation.
 *
 * <p>Progress and total values are finite JSON numbers. Soklet associates the
 * update with the initiating request's opaque progress token; applications do
 * not copy or manage that token themselves.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpProgressUpdate {
	private final double progress;
	private final @Nullable Double total;
	private final @Nullable String message;

	/**
	 * Vends a builder primed with the current progress value.
	 *
	 * @param progress finite progress value
	 * @return progress-update builder
	 * @throws IllegalArgumentException if {@code progress} is not finite
	 */
	@NonNull
	public static Builder withProgress(double progress) {
		return new Builder(progress);
	}

	private McpProgressUpdate(@NonNull Builder builder) {
		this.progress = requireFinite(builder.progress, "Progress");
		this.total = builder.total == null ? null
				: requireFinite(builder.total, "Progress total");
		this.message = builder.message;
	}

	/** @return finite progress value */
	public double getProgress() {
		return this.progress;
	}

	/** @return finite total value, if supplied */
	@NonNull
	public Optional<@NonNull Double> getTotal() {
		return Optional.ofNullable(this.total);
	}

	/** @return human-readable progress message, if supplied */
	@NonNull
	public Optional<@NonNull String> getMessage() {
		return Optional.ofNullable(this.message);
	}

	private static double requireFinite(double value, @NonNull String description) {
		if (!Double.isFinite(value))
			throw new IllegalArgumentException(description + " must be finite.");
		// JSON has one zero value. Normalize the Java-only negative-zero spelling.
		return value == 0.0d ? 0.0d : value;
	}

	/**
	 * Mutable builder for immutable {@link McpProgressUpdate} values.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		private final double progress;
		private @Nullable Double total;
		private @Nullable String message;

		private Builder(double progress) {
			this.progress = requireFinite(progress, "Progress");
		}

		/**
		 * Supplies the total amount of work, when known.
		 *
		 * @param total finite total value
		 * @return this builder
		 * @throws IllegalArgumentException if {@code total} is not finite
		 */
		@NonNull
		public Builder total(double total) {
			this.total = requireFinite(total, "Progress total");
			return this;
		}

		/**
		 * Supplies an optional human-readable progress message.
		 *
		 * @param message progress message
		 * @return this builder
		 * @throws NullPointerException if {@code message} is null
		 */
		@NonNull
		public Builder message(@NonNull String message) {
			this.message = requireNonNull(message);
			return this;
		}

		/** @return immutable progress update */
		@NonNull
		public McpProgressUpdate build() {
			return new McpProgressUpdate(this);
		}
	}
}
