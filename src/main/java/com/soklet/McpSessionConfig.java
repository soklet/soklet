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
 * Immutable server-wide ownership and bounds for explicitly enabled MCP
 * {@code 2025-06-18} and {@code 2025-11-25} sessions. Soklet's
 * {@code 2026-07-28} implementation does not use this configuration.
 * <p>
 * Configure this value on {@link McpServer.Builder#sessionConfig(McpSessionConfig)}
 * and select exact session revisions on each applicable endpoint through
 * {@link McpEndpoint.Builder#sessionProtocolVersions(java.util.Set)}.
 * Configuration alone does not enable sessions on every endpoint. Sessions
 * are bounded, node-local state owned by the MCP server's lifecycle; session
 * IDs are correlation handles, not credentials. Every use requires fresh
 * admission and owner verification.
 * <p>
 * Instances retain reference identity because the application-owned resolver
 * is a live callback. Configuration does not make its state immutable or
 * transfer its lifecycle to Soklet. Defaults are initial operational limits,
 * not a recovery or capacity guarantee for a particular deployment.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSessionConfig {
	@NonNull
	private final McpSessionOwnerKeyResolver ownerKeyResolver;
	private final int maximumSessions;
	private final int maximumSessionsPerOwner;
	@NonNull
	private final Duration maximumSessionIdleDuration;
	@NonNull
	private final Duration maximumSessionDuration;
	private final int maximumClientMetadataSizeInBytes;
	private final boolean anonymousSessionsAllowed;
	@Nullable
	private final McpSessionTransportAdmissionController transportAdmissionController;

	/**
	 * Vends a builder primed with its required application owner resolver.
	 *
	 * @param sessionOwnerKeyResolver application-owned stable owner resolver
	 * @return a session-configuration builder
	 * @throws NullPointerException if the resolver is null
	 */
	@NonNull
	public static Builder withOwnerKeyResolver(
			@NonNull McpSessionOwnerKeyResolver sessionOwnerKeyResolver) {
		return new Builder(sessionOwnerKeyResolver);
	}

	private McpSessionConfig(@NonNull Builder builder) {
		requireNonNull(builder);
		this.ownerKeyResolver = builder.ownerKeyResolver;
		this.maximumSessions = builder.maximumSessions;
		this.maximumSessionsPerOwner = builder.maximumSessionsPerOwner;
		this.maximumSessionIdleDuration = builder.maximumSessionIdleDuration;
		this.maximumSessionDuration = builder.maximumSessionDuration;
		this.maximumClientMetadataSizeInBytes = builder.maximumClientMetadataSizeInBytes;
		this.anonymousSessionsAllowed = builder.anonymousSessionsAllowed;
		this.transportAdmissionController = builder.transportAdmissionController;
	}

	/** @return application-owned stable owner resolver */
	@NonNull
	public McpSessionOwnerKeyResolver getOwnerKeyResolver() {
		return this.ownerKeyResolver;
	}

	/** @return maximum live and provisional sessions across this server; default 256 */
	@NonNull
	public Integer getMaximumSessions() {
		return this.maximumSessions;
	}

	/** @return maximum sessions for one resolved owner; default 16 */
	@NonNull
	public Integer getMaximumSessionsPerOwner() {
		return this.maximumSessionsPerOwner;
	}

	/**
	 * Returns the lifetime of a quiescent session without accepted owner-bound
	 * activity. In-flight admitted work prevents idle expiry. Invalid requests
	 * and transport keep-alives do not refresh this lifetime.
	 *
	 * @return positive idle lifetime; default 24 hours
	 */
	@NonNull
	public Duration getMaximumSessionIdleDuration() {
		return this.maximumSessionIdleDuration;
	}

	/**
	 * Returns the absolute session lifetime, independent of activity.
	 *
	 * @return positive hard lifetime; default 7 days
	 */
	@NonNull
	public Duration getMaximumSessionDuration() {
		return this.maximumSessionDuration;
	}

	/**
	 * Returns the maximum retained initialization client-info and capability
	 * projection size. Existing JSON byte and node limits also apply; an
	 * oversized snapshot is rejected rather than partially retained.
	 *
	 * @return maximum encoded projection size in bytes; default 65536
	 */
	@NonNull
	public Integer getMaximumClientMetadataSizeInBytes() {
		return this.maximumClientMetadataSizeInBytes;
	}

	/**
	 * Indicates whether identities for which
	 * {@link McpAdmissionIdentity#isAuthenticated()} is false may allocate sessions.
	 * Anonymous owners use separate internal namespaces and a bounded shared
	 * sub-budget in addition to the configured owner and server limits.
	 *
	 * @return whether anonymous sessions are allowed; default false
	 */
	@NonNull
	public Boolean isAnonymousSessionsAllowed() {
		return this.anonymousSessionsAllowed;
	}

	/**
	 * Returns the HTTP controller for GET delivery and DELETE cleanup on the
	 * explicitly session-enabled 2025 revisions. Absence disables both methods.
	 * A controller enables DELETE cleanup; GET additionally requires an explicit
	 * endpoint subscription revision and effective notification families.
	 *
	 * @return application-owned HTTP transport admission controller, when configured
	 */
	@NonNull
	public Optional<@NonNull McpSessionTransportAdmissionController> getTransportAdmissionController() {
		return Optional.ofNullable(this.transportAdmissionController);
	}

	/**
	 * Builder for immutable {@link McpSessionConfig} values.
	 * This class is intended for use by a single thread. Null tuning arguments
	 * restore their defaults; failed replacements leave prior values intact.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		private static final int DEFAULT_MAXIMUM_SESSIONS = 256;
		private static final int DEFAULT_MAXIMUM_SESSIONS_PER_OWNER = 16;
		@NonNull
		private static final Duration DEFAULT_MAXIMUM_SESSION_IDLE_DURATION = Duration.ofHours(24);
		@NonNull
		private static final Duration DEFAULT_MAXIMUM_SESSION_DURATION = Duration.ofDays(7);
		private static final int DEFAULT_MAXIMUM_CLIENT_METADATA_SIZE_IN_BYTES = 64 * 1024;

		@NonNull
		private final McpSessionOwnerKeyResolver ownerKeyResolver;
		private int maximumSessions = DEFAULT_MAXIMUM_SESSIONS;
		private int maximumSessionsPerOwner = DEFAULT_MAXIMUM_SESSIONS_PER_OWNER;
		@NonNull
		private Duration maximumSessionIdleDuration = DEFAULT_MAXIMUM_SESSION_IDLE_DURATION;
		@NonNull
		private Duration maximumSessionDuration = DEFAULT_MAXIMUM_SESSION_DURATION;
		private int maximumClientMetadataSizeInBytes = DEFAULT_MAXIMUM_CLIENT_METADATA_SIZE_IN_BYTES;
		private boolean anonymousSessionsAllowed;
		@Nullable
		private McpSessionTransportAdmissionController transportAdmissionController;

		private Builder(@NonNull McpSessionOwnerKeyResolver sessionOwnerKeyResolver) {
			this.ownerKeyResolver = requireNonNull(sessionOwnerKeyResolver);
		}

		/**
		 * Sets the server-wide live and provisional session limit.
		 *
		 * @param maximumSessions positive limit, or null to restore 256
		 * @return this builder
		 * @throws IllegalArgumentException if the limit is not positive
		 */
		@NonNull
		public Builder maximumSessions(@Nullable Integer maximumSessions) {
			this.maximumSessions = maximumSessions == null ? DEFAULT_MAXIMUM_SESSIONS
					: requirePositiveInteger(maximumSessions);
			return this;
		}

		/**
		 * Sets the limit for one resolved owner; it must not exceed the server limit.
		 *
		 * @param maximumSessionsPerOwner positive limit, or null to restore 16
		 * @return this builder
		 * @throws IllegalArgumentException if the limit is not positive
		 */
		@NonNull
		public Builder maximumSessionsPerOwner(@Nullable Integer maximumSessionsPerOwner) {
			this.maximumSessionsPerOwner = maximumSessionsPerOwner == null
					? DEFAULT_MAXIMUM_SESSIONS_PER_OWNER : requirePositiveInteger(maximumSessionsPerOwner);
			return this;
		}

		/**
		 * Sets the positive quiescent session lifetime, no longer than the hard lifetime.
		 *
		 * @param maximumSessionIdleDuration positive finite duration, or null to restore 24 hours
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive or does not fit in signed nanoseconds
		 */
		@NonNull
		public Builder maximumSessionIdleDuration(@Nullable Duration maximumSessionIdleDuration) {
			this.maximumSessionIdleDuration = maximumSessionIdleDuration == null
					? DEFAULT_MAXIMUM_SESSION_IDLE_DURATION : requirePositiveDuration(maximumSessionIdleDuration);
			return this;
		}

		/**
		 * Sets the positive absolute session lifetime.
		 *
		 * @param maximumSessionDuration positive finite duration, or null to restore 7 days
		 * @return this builder
		 * @throws IllegalArgumentException if the duration is not positive or does not fit in signed nanoseconds
		 */
		@NonNull
		public Builder maximumSessionDuration(@Nullable Duration maximumSessionDuration) {
			this.maximumSessionDuration = maximumSessionDuration == null
					? DEFAULT_MAXIMUM_SESSION_DURATION : requirePositiveDuration(maximumSessionDuration);
			return this;
		}

		/**
		 * Sets the encoded initialization metadata projection limit.
		 *
		 * @param maximumClientMetadataSizeInBytes positive byte limit, or null to restore 65536
		 * @return this builder
		 * @throws IllegalArgumentException if the limit is not positive
		 */
		@NonNull
		public Builder maximumClientMetadataSizeInBytes(@Nullable Integer maximumClientMetadataSizeInBytes) {
			this.maximumClientMetadataSizeInBytes = maximumClientMetadataSizeInBytes == null
					? DEFAULT_MAXIMUM_CLIENT_METADATA_SIZE_IN_BYTES : requirePositiveInteger(maximumClientMetadataSizeInBytes);
			return this;
		}

		/**
		 * Explicitly permits session allocation for unauthenticated admitted identities.
		 *
		 * @param anonymousSessionsAllowed whether anonymous allocation is allowed, or null to restore false
		 * @return this builder
		 */
		@NonNull
		public Builder anonymousSessionsAllowed(@Nullable Boolean anonymousSessionsAllowed) {
			this.anonymousSessionsAllowed = anonymousSessionsAllowed != null && anonymousSessionsAllowed;
			return this;
		}

		/**
		 * Sets fresh HTTP admission for session GET delivery and DELETE cleanup.
		 * The callback is application-owned and must support concurrent calls.
		 * Configuration alone does not enable GET notification delivery on every
		 * session endpoint. Null clears the controller and disables both methods.
		 *
		 * @param sessionTransportAdmissionController HTTP admission callback, or null to clear
		 * @return this builder
		 */
		@NonNull
		public Builder transportAdmissionController(
				@Nullable McpSessionTransportAdmissionController sessionTransportAdmissionController) {
			this.transportAdmissionController = sessionTransportAdmissionController;
			return this;
		}

		/**
		 * Builds an immutable configuration without invoking its owner resolver.
		 *
		 * @return session configuration
		 * @throws IllegalStateException if the owner limit exceeds the server limit or the idle lifetime exceeds the hard lifetime
		 */
		@NonNull
		public McpSessionConfig build() {
			if (this.maximumSessionsPerOwner > this.maximumSessions)
				throw new IllegalStateException("The MCP session owner limit must not exceed the server session limit.");
			if (this.maximumSessionIdleDuration.compareTo(this.maximumSessionDuration) > 0)
				throw new IllegalStateException("The MCP session idle lifetime must not exceed its absolute lifetime.");
			return new McpSessionConfig(this);
		}

		private static int requirePositiveInteger(int value) {
			if (value < 1)
				throw new IllegalArgumentException("MCP session limits must be positive.");
			return value;
		}

		@NonNull
		private static Duration requirePositiveDuration(@NonNull Duration duration) {
			requireNonNull(duration);
			try {
				if (duration.isZero() || duration.isNegative() || duration.toNanos() <= 0L)
					throw new IllegalArgumentException("MCP session lifetimes must be positive.");
			} catch (ArithmeticException exception) {
				throw new IllegalArgumentException("MCP session lifetimes must fit in a signed nanosecond duration.", exception);
			}
			return duration;
		}
	}
}
