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
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Immutable details describing why and when a stream terminated.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class StreamTermination {
	@NonNull
	private final StreamTerminationReason reason;
	@NonNull
	private final Duration duration;
	@Nullable
	private final Throwable cause;

	/**
	 * Acquires a builder for {@link StreamTermination} instances.
	 *
	 * @param reason   why the stream terminated
	 * @param duration how long the stream existed
	 * @return the builder
	 */
	@NonNull
	public static Builder with(@NonNull StreamTerminationReason reason,
														 @NonNull Duration duration) {
		requireNonNull(reason);
		requireNonNull(duration);

		return new Builder(reason, duration);
	}

	private StreamTermination(@NonNull Builder builder) {
		requireNonNull(builder);

		this.reason = builder.reason;
		this.duration = builder.duration;
		this.cause = builder.cause;
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{reason=%s, duration=%s, cause=%s}", getClass().getSimpleName(),
				getReason(), getDuration(), getCause().orElse(null));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof StreamTermination streamTermination))
			return false;

		return Objects.equals(getReason(), streamTermination.getReason())
				&& Objects.equals(getDuration(), streamTermination.getDuration())
				&& Objects.equals(getCause(), streamTermination.getCause());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getReason(), getDuration(), getCause());
	}

	/**
	 * Why the stream terminated.
	 *
	 * @return the termination reason
	 */
	@NonNull
	public StreamTerminationReason getReason() {
		return this.reason;
	}

	/**
	 * How long the stream existed.
	 *
	 * @return the stream duration
	 */
	@NonNull
	public Duration getDuration() {
		return this.duration;
	}

	/**
	 * The underlying termination cause, if available.
	 *
	 * @return the underlying cause, or {@link Optional#empty()} if unavailable
	 */
	@NonNull
	public Optional<Throwable> getCause() {
		return Optional.ofNullable(this.cause);
	}

	/**
	 * Vends a mutable copier seeded with this instance's data, suitable for building new instances.
	 *
	 * @return a copier for this instance
	 */
	@NonNull
	public Copier copy() {
		return new Copier(this);
	}

	/**
	 * Builder used to construct instances of {@link StreamTermination}.
	 */
	@NotThreadSafe
	public static class Builder {
		@NonNull
		private StreamTerminationReason reason;
		@NonNull
		private Duration duration;
		@Nullable
		private Throwable cause;

		protected Builder(@NonNull StreamTerminationReason reason,
											@NonNull Duration duration) {
			this.reason = requireNonNull(reason);
			this.duration = requireNonNull(duration);
		}

		/**
		 * Specifies why the stream terminated.
		 *
		 * @param reason the termination reason
		 * @return this builder
		 */
		@NonNull
		public Builder reason(@NonNull StreamTerminationReason reason) {
			this.reason = requireNonNull(reason);
			return this;
		}

		/**
		 * Specifies how long the stream existed.
		 *
		 * @param duration the stream duration
		 * @return this builder
		 */
		@NonNull
		public Builder duration(@NonNull Duration duration) {
			this.duration = requireNonNull(duration);
			return this;
		}

		/**
		 * Specifies the underlying termination cause.
		 *
		 * @param cause the cause, or {@code null} if unavailable
		 * @return this builder
		 */
		@NonNull
		public Builder cause(@Nullable Throwable cause) {
			this.cause = cause;
			return this;
		}

		/**
		 * Builds a {@link StreamTermination} instance.
		 *
		 * @return the termination details
		 */
		@NonNull
		public StreamTermination build() {
			return new StreamTermination(this);
		}
	}

	/**
	 * Mutable copier seeded with an existing {@link StreamTermination}.
	 */
	@NotThreadSafe
	public static final class Copier extends Builder {
		Copier(@NonNull StreamTermination streamTermination) {
			super(streamTermination.getReason(), streamTermination.getDuration());
			cause(streamTermination.getCause().orElse(null));
		}

		@Override
		@NonNull
		public Copier reason(@NonNull StreamTerminationReason reason) {
			super.reason(reason);
			return this;
		}

		@Override
		@NonNull
		public Copier duration(@NonNull Duration duration) {
			super.duration(duration);
			return this;
		}

		@Override
		@NonNull
		public Copier cause(@Nullable Throwable cause) {
			super.cause(cause);
			return this;
		}
	}
}
