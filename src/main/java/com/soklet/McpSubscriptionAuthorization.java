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
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable result of one MCP subscription authorization check. The carrier is
 * safe for concurrent access; an application remains responsible for the
 * thread safety of an opaque application-context object stored in an allowed
 * result. {@link Allowed} and {@link Denied} are the exhaustive result domain.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpSubscriptionAuthorization
		permits McpSubscriptionAuthorization.Allowed,
		McpSubscriptionAuthorization.Denied {
	/**
	 * Returns the shared denied authorization result.
	 *
	 * @return shared denied result
	 */
	@NonNull
	static Denied deniedInstance() {
		return Denied.INSTANCE;
	}

	/**
	 * An allowed subscription authorization with an application-requested
	 * expiration and optional replacement application context.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class Allowed implements McpSubscriptionAuthorization {
		@NonNull
		private final Instant validUntil;
		@Nullable
		private final Object applicationContext;

		/**
		 * Creates an allowed authorization without an application context.
		 *
		 * @param validUntil application-requested authorization expiration
		 * @return allowed authorization
		 * @throws NullPointerException if {@code validUntil} is null
		 */
		@NonNull
		public static Allowed fromValidUntil(@NonNull Instant validUntil) {
			return new Allowed(validUntil);
		}

		/**
		 * Vends a builder primed with an application-requested expiration.
		 *
		 * @param validUntil application-requested authorization expiration
		 * @return allowed-authorization builder
		 * @throws NullPointerException if {@code validUntil} is null
		 */
		@NonNull
		public static Builder withValidUntil(@NonNull Instant validUntil) {
			return new Builder(validUntil);
		}

		private Allowed(@NonNull Instant validUntil) {
			this.validUntil = requireNonNull(validUntil);
			this.applicationContext = null;
		}

		private Allowed(@NonNull Builder builder) {
			this.validUntil = requireNonNull(builder).validUntil;
			this.applicationContext = builder.applicationContext;
		}

		/** @return application-requested authorization expiration */
		@NonNull
		public Instant getValidUntil() {
			return this.validUntil;
		}

		/**
		 * Returns replacement application context for subsequent authorization and
		 * projection work. Absence clears any previous application context.
		 *
		 * @return replacement application context, when present
		 */
		@NonNull
		public Optional<@NonNull Object> getApplicationContext() {
			return Optional.ofNullable(this.applicationContext);
		}

		/** @return whether this result contains the same values */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof Allowed allowed))
				return false;
			return this.validUntil.equals(allowed.validUntil)
					&& Objects.equals(this.applicationContext,
					allowed.applicationContext);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 31 * this.validUntil.hashCode()
					+ Objects.hashCode(this.applicationContext);
		}

		/** @return diagnostic rendering without application-context data */
		@Override
		@NonNull
		public String toString() {
			return "Allowed{validUntil=" + this.validUntil
					+ ", applicationContext=<redacted>}";
		}

		/**
		 * Builder for immutable allowed subscription authorizations.
		 * <p>
		 * This class is intended for use by a single thread.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@NotThreadSafe
		public static final class Builder {
			@NonNull
			private final Instant validUntil;
			@Nullable
			private Object applicationContext;

			private Builder(@NonNull Instant validUntil) {
				this.validUntil = requireNonNull(validUntil);
			}

			/**
			 * Replaces the application context. Passing null clears it rather than
			 * retaining a context from an earlier authorization.
			 *
			 * @param applicationContext replacement application context, or null to
			 *                           clear it
			 * @return this builder
			 */
			@NonNull
			public Builder applicationContext(
					@Nullable Object applicationContext) {
				this.applicationContext = applicationContext;
				return this;
			}

			/** @return immutable allowed subscription authorization */
			@NonNull
			public Allowed build() {
				return new Allowed(this);
			}
		}
	}

	/**
	 * A data-free denied subscription authorization.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class Denied implements McpSubscriptionAuthorization {
		@NonNull
		private static final Denied INSTANCE = new Denied();

		private Denied() {
		}

		/** @return whether the other value is also a denied authorization */
		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof Denied;
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return safe diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "Denied{}";
		}
	}
}
