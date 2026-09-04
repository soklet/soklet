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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable server-wide limits and provider selection for framework-protected
 * MCP request state.
 * <p>
 * No configuration is required until an operation declares framework-protected
 * request state. Development-ephemeral mode is explicit and is not portable
 * across restarts or server instances. A production keyring is an immutable
 * initial value; building a server copies it into independent live state and
 * exposes subsequent mutation only through {@link McpProtectionControl}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpProtectionConfig {
	private static final int DEFAULT_MAXIMUM_ENCODED_REQUEST_STATE_SIZE_IN_BYTES = 65_536;
	private static final int DEFAULT_MAXIMUM_DECODED_REQUEST_STATE_SIZE_IN_BYTES = 49_152;
	private static final int DEFAULT_MAXIMUM_REQUEST_STATE_ROUNDS = 10;
	@NonNull
	private static final Duration DEFAULT_MAXIMUM_REQUEST_STATE_LIFETIME =
			Duration.ofMinutes(15);

	@NonNull
	private final McpProtectionMode protectionMode;
	@Nullable
	private final McpProtectionKeyring initialKeyring;
	@Nullable
	private final McpRequestStateProtector requestStateProtector;
	private final int maximumEncodedRequestStateSizeInBytes;
	private final int maximumDecodedRequestStateSizeInBytes;
	@NonNull
	private final Duration maximumRequestStateLifetime;
	private final int maximumRequestStateRounds;

	/**
	 * Vends a production configuration builder.
	 *
	 * @param keyring immutable initial production ring
	 * @return protection configuration builder
	 */
	@NonNull
	public static Builder withKeyring(@NonNull McpProtectionKeyring keyring) {
		return new Builder(McpProtectionMode.PRODUCTION_KEYRING,
				requireNonNull(keyring), null);
	}

	/**
	 * Vends a custom-protector configuration builder.
	 *
	 * @param requestStateProtector application-owned protector
	 * @return protection configuration builder
	 */
	@NonNull
	public static Builder withRequestStateProtector(
			@NonNull McpRequestStateProtector requestStateProtector) {
		return new Builder(McpProtectionMode.CUSTOM_PROTECTOR, null,
				requireNonNull(requestStateProtector));
	}

	/**
	 * Vends an explicit development-ephemeral configuration builder.
	 *
	 * @return protection configuration builder
	 */
	@NonNull
	public static Builder withDevelopmentEphemeralProtection() {
		return new Builder(McpProtectionMode.DEVELOPMENT_EPHEMERAL, null, null);
	}

	private McpProtectionConfig(@NonNull Builder builder) {
		this.protectionMode = builder.protectionMode;
		this.initialKeyring = builder.initialKeyring;
		this.requestStateProtector = builder.requestStateProtector;
		this.maximumEncodedRequestStateSizeInBytes =
				builder.maximumEncodedRequestStateSizeInBytes;
		this.maximumDecodedRequestStateSizeInBytes =
				builder.maximumDecodedRequestStateSizeInBytes;
		this.maximumRequestStateLifetime = builder.maximumRequestStateLifetime;
		this.maximumRequestStateRounds = builder.maximumRequestStateRounds;
	}

	/** @return configured protection mode */
	@NonNull
	public McpProtectionMode getProtectionMode() {
		return this.protectionMode;
	}

	/**
	 * Returns the immutable initial ring configuration, not a server's live ring.
	 *
	 * @return initial keyring, if production protection is configured
	 */
	@NonNull
	public Optional<@NonNull McpProtectionKeyring> getInitialKeyring() {
		return Optional.ofNullable(this.initialKeyring);
	}

	/** @return custom application protector, when configured */
	@NonNull
	public Optional<@NonNull McpRequestStateProtector>
			getRequestStateProtector() {
		return Optional.ofNullable(this.requestStateProtector);
	}

	/** @return positive maximum encoded request-state size in bytes */
	@NonNull
	public Integer getMaximumEncodedRequestStateSizeInBytes() {
		return this.maximumEncodedRequestStateSizeInBytes;
	}

	/** @return positive maximum decoded request-state size in bytes */
	@NonNull
	public Integer getMaximumDecodedRequestStateSizeInBytes() {
		return this.maximumDecodedRequestStateSizeInBytes;
	}

	/** @return positive finite maximum request-state lifetime */
	@NonNull
	public Duration getMaximumRequestStateLifetime() {
		return this.maximumRequestStateLifetime;
	}

	/** @return positive maximum request-state round count */
	@NonNull
	public Integer getMaximumRequestStateRounds() {
		return this.maximumRequestStateRounds;
	}

	/**
	 * Single-threaded builder for immutable request-state protection settings.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final McpProtectionMode protectionMode;
		@Nullable
		private final McpProtectionKeyring initialKeyring;
		@Nullable
		private final McpRequestStateProtector requestStateProtector;
		private int maximumEncodedRequestStateSizeInBytes;
		private int maximumDecodedRequestStateSizeInBytes;
		@NonNull
		private Duration maximumRequestStateLifetime;
		private int maximumRequestStateRounds;

		private Builder(@NonNull McpProtectionMode protectionMode,
				@Nullable McpProtectionKeyring initialKeyring,
				@Nullable McpRequestStateProtector requestStateProtector) {
			this.protectionMode = requireNonNull(protectionMode);
			this.initialKeyring = initialKeyring;
			this.requestStateProtector = requestStateProtector;
			this.maximumEncodedRequestStateSizeInBytes =
					DEFAULT_MAXIMUM_ENCODED_REQUEST_STATE_SIZE_IN_BYTES;
			this.maximumDecodedRequestStateSizeInBytes =
					DEFAULT_MAXIMUM_DECODED_REQUEST_STATE_SIZE_IN_BYTES;
			this.maximumRequestStateLifetime =
					DEFAULT_MAXIMUM_REQUEST_STATE_LIFETIME;
			this.maximumRequestStateRounds =
					DEFAULT_MAXIMUM_REQUEST_STATE_ROUNDS;
		}

		/**
		 * Sets the maximum encoded request-state size. The default is 65,536 bytes.
		 *
		 * @param maximumEncodedRequestStateSizeInBytes positive byte limit, or
		 *                                             {@code null} to restore the
		 *                                             default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumEncodedRequestStateSizeInBytes(
				@Nullable Integer maximumEncodedRequestStateSizeInBytes) {
			this.maximumEncodedRequestStateSizeInBytes =
					maximumEncodedRequestStateSizeInBytes == null
							? DEFAULT_MAXIMUM_ENCODED_REQUEST_STATE_SIZE_IN_BYTES
							: requirePositive(maximumEncodedRequestStateSizeInBytes,
									"Maximum encoded request-state bytes");
			return this;
		}

		/**
		 * Sets the maximum decoded request-state size. The default is 49,152 bytes.
		 *
		 * @param maximumDecodedRequestStateSizeInBytes positive byte limit, or
		 *                                             {@code null} to restore the
		 *                                             default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumDecodedRequestStateSizeInBytes(
				@Nullable Integer maximumDecodedRequestStateSizeInBytes) {
			this.maximumDecodedRequestStateSizeInBytes =
					maximumDecodedRequestStateSizeInBytes == null
							? DEFAULT_MAXIMUM_DECODED_REQUEST_STATE_SIZE_IN_BYTES
							: requirePositive(maximumDecodedRequestStateSizeInBytes,
									"Maximum decoded request-state bytes");
			return this;
		}

		/**
		 * Sets the maximum request-state lifetime. The default is 15 minutes.
		 *
		 * @param maximumRequestStateLifetime positive finite lifetime, or
		 *                                    {@code null} to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumRequestStateLifetime(
				@Nullable Duration maximumRequestStateLifetime) {
			this.maximumRequestStateLifetime = maximumRequestStateLifetime == null
					? DEFAULT_MAXIMUM_REQUEST_STATE_LIFETIME
					: requirePositiveDuration(maximumRequestStateLifetime,
							"Maximum request-state lifetime");
			return this;
		}

		/**
		 * Sets the maximum request-state round count. The default is {@code 10}.
		 *
		 * @param maximumRequestStateRounds positive round limit, or {@code null}
		 *                                  to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumRequestStateRounds(
				@Nullable Integer maximumRequestStateRounds) {
			this.maximumRequestStateRounds = maximumRequestStateRounds == null
					? DEFAULT_MAXIMUM_REQUEST_STATE_ROUNDS
					: requirePositive(maximumRequestStateRounds,
							"Maximum request-state rounds");
			return this;
		}

		/**
		 * Builds the immutable protection configuration.
		 *
		 * @return protection configuration
		 * @throws IllegalStateException if the decoded-size limit exceeds the
		 *                               encoded-size limit
		 */
		@NonNull
		public McpProtectionConfig build() {
			if (this.maximumDecodedRequestStateSizeInBytes
					> this.maximumEncodedRequestStateSizeInBytes)
				throw new IllegalStateException(
						"Decoded request-state limit must not exceed encoded request-state limit.");
			return new McpProtectionConfig(this);
		}

		private static int requirePositive(int value,
				@NonNull String description) {
			if (value < 1)
				throw new IllegalArgumentException(
						description + " must be positive.");
			return value;
		}

		@NonNull
		private static Duration requirePositiveDuration(@NonNull Duration value,
				@NonNull String description) {
			requireNonNull(value);
			if (value.isZero() || value.isNegative())
				throw new IllegalArgumentException(
						description + " must be positive.");
			try {
				if (value.toNanos() < 1L)
					throw new IllegalArgumentException(
							description + " must be positive.");
			} catch (ArithmeticException exception) {
				throw new IllegalArgumentException(description
						+ " must fit in a signed nanosecond duration.", exception);
			}
			return value;
		}
	}
}
