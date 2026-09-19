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
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable security policy and presentation hints for an MCP Apps resource.
 *
 * <p>Permissions request capabilities rather than grant them. Hosts enforce
 * content security policy and may further restrict it; this value does not
 * construct a browser sandbox or establish host acceptance of a domain hint.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpAppResourceMetadata {
	@Nullable
	private final ContentSecurityPolicy contentSecurityPolicy;
	@NonNull
	private final Set<@NonNull Permission> permissions;
	@Nullable
	private final String domain;
	@Nullable
	private final Boolean prefersBorder;

	/** @return mutable builder with empty permissions and omitted hints */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	private McpAppResourceMetadata(@NonNull Builder builder) {
		this.contentSecurityPolicy = builder.contentSecurityPolicy;
		this.permissions = builder.permissions;
		this.domain = builder.domain;
		this.prefersBorder = builder.prefersBorder;
	}

	/** @return content security policy, or empty when omitted */
	@NonNull
	public Optional<@NonNull ContentSecurityPolicy> getContentSecurityPolicy() {
		return Optional.ofNullable(this.contentSecurityPolicy);
	}

	/** @return immutable requested permissions in enum declaration order */
	@NonNull
	public Set<@NonNull Permission> getPermissions() {
		return this.permissions;
	}

	/** @return optional host-specific sandbox-origin hint */
	@NonNull
	public Optional<@NonNull String> getDomain() {
		return Optional.ofNullable(this.domain);
	}

	/** @return border preference, preserving omission separately from false */
	@NonNull
	public Optional<@NonNull Boolean> getPrefersBorder() {
		return Optional.ofNullable(this.prefersBorder);
	}

	/** @return whether policy, permissions, and optional hints match */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpAppResourceMetadata metadata))
			return false;
		return Objects.equals(this.contentSecurityPolicy, metadata.contentSecurityPolicy)
				&& this.permissions.equals(metadata.permissions)
				&& Objects.equals(this.domain, metadata.domain)
				&& Objects.equals(this.prefersBorder, metadata.prefersBorder);
	}

	/** @return structural hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.contentSecurityPolicy, this.permissions,
				this.domain, this.prefersBorder);
	}

	/** @return diagnostic rendering without policy or hint values */
	@Override
	@NonNull
	public String toString() {
		return "McpAppResourceMetadata{contentSecurityPolicy=<redacted>, "
				+ "permissions=<redacted>, domain=<redacted>, prefersBorder=<redacted>}";
	}

	/**
	 * Browser capabilities that an Apps resource may request from its host.
	 *
	 * <p>This domain is closed for 4.0. Adding a permission requires an explicit
	 * domain and API-evolution amendment; raw metadata cannot bypass this domain.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public enum Permission {
		/** Camera access. */
		CAMERA,
		/** Microphone access. */
		MICROPHONE,
		/** Geolocation access. */
		GEOLOCATION,
		/** Clipboard write access. */
		CLIPBOARD_WRITE
	}

	/**
	 * Mutable builder for immutable Apps resource metadata.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private ContentSecurityPolicy contentSecurityPolicy;
		@NonNull
		private Set<@NonNull Permission> permissions = Collections.emptySet();
		@Nullable
		private String domain;
		@Nullable
		private Boolean prefersBorder;

		private Builder() {}

		/**
		 * Sets a host-enforced content security policy.
		 *
		 * @param contentSecurityPolicy immutable policy
		 * @return this builder
		 */
		@NonNull
		public Builder contentSecurityPolicy(
				@NonNull ContentSecurityPolicy contentSecurityPolicy) {
			this.contentSecurityPolicy = requireNonNull(contentSecurityPolicy);
			return this;
		}

		/**
		 * Replaces requested permissions with an immutable ordered snapshot.
		 *
		 * @param permissions non-null permissions, possibly empty
		 * @return this builder
		 */
		@NonNull
		public Builder permissions(@NonNull Set<@NonNull Permission> permissions) {
			requireNonNull(permissions);
			EnumSet<Permission> ordered = EnumSet.noneOf(Permission.class);
			for (Permission permission : permissions)
				ordered.add(requireNonNull(permission));
			this.permissions = Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
			return this;
		}

		/**
		 * Sets a host-specific sandbox-origin hint without provisioning DNS.
		 *
		 * @param domain lowercase ASCII DNS name with LDH labels and no trailing dot
		 * @return this builder
		 * @throws IllegalArgumentException if the baseline DNS-name syntax is invalid
		 */
		@NonNull
		public Builder domain(@NonNull String domain) {
			this.domain = McpAppMetadataValidation.requireDomain(requireNonNull(domain));
			return this;
		}

		/**
		 * Sets a presentation preference, retaining explicit false.
		 *
		 * @param prefersBorder whether a border is preferred
		 * @return this builder
		 */
		@NonNull
		public Builder prefersBorder(@NonNull Boolean prefersBorder) {
			this.prefersBorder = requireNonNull(prefersBorder);
			return this;
		}

		/** @return immutable resource metadata */
		@NonNull
		public McpAppResourceMetadata build() {
			return new McpAppResourceMetadata(this);
		}
	}

	/**
	 * Immutable allowlists of canonical ASCII origins, not raw CSP header sources.
	 *
	 * <p>Empty collections allow no additional external connection, resource, or
	 * frame origins and retain same-origin-only document base URIs. Host enforcement
	 * may impose further restrictions. A wildcard is permitted only as one leftmost
	 * DNS label, such as {@code https://*.example.com}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public static final class ContentSecurityPolicy {
		@NonNull
		private final Set<@NonNull String> connectDomains;
		@NonNull
		private final Set<@NonNull String> resourceDomains;
		@NonNull
		private final Set<@NonNull String> frameDomains;
		@NonNull
		private final Set<@NonNull String> baseUriDomains;

		/** @return mutable builder with empty origin allowlists */
		@NonNull
		public static Builder builder() {
			return new Builder();
		}

		private ContentSecurityPolicy(@NonNull Builder builder) {
			this.connectDomains = builder.connectDomains;
			this.resourceDomains = builder.resourceDomains;
			this.frameDomains = builder.frameDomains;
			this.baseUriDomains = builder.baseUriDomains;
		}

		/** @return immutable connection origins in canonical ASCII order */
		@NonNull
		public Set<@NonNull String> getConnectDomains() {
			return this.connectDomains;
		}

		/** @return immutable asset origins in canonical ASCII order */
		@NonNull
		public Set<@NonNull String> getResourceDomains() {
			return this.resourceDomains;
		}

		/** @return immutable iframe origins in canonical ASCII order */
		@NonNull
		public Set<@NonNull String> getFrameDomains() {
			return this.frameDomains;
		}

		/** @return immutable document-base origins in canonical ASCII order */
		@NonNull
		public Set<@NonNull String> getBaseUriDomains() {
			return this.baseUriDomains;
		}

		/** @return whether all four origin allowlists match */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof ContentSecurityPolicy policy))
				return false;
			return this.connectDomains.equals(policy.connectDomains)
					&& this.resourceDomains.equals(policy.resourceDomains)
					&& this.frameDomains.equals(policy.frameDomains)
					&& this.baseUriDomains.equals(policy.baseUriDomains);
		}

		/** @return structural hash code */
		@Override
		public int hashCode() {
			return Objects.hash(this.connectDomains, this.resourceDomains,
					this.frameDomains, this.baseUriDomains);
		}

		/** @return diagnostic rendering without origin values */
		@Override
		@NonNull
		public String toString() {
			return "ContentSecurityPolicy{connectDomains=<redacted>, "
					+ "resourceDomains=<redacted>, frameDomains=<redacted>, "
					+ "baseUriDomains=<redacted>}";
		}

		/**
		 * Mutable builder for an immutable content security policy.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@NotThreadSafe
		public static final class Builder {
			@NonNull
			private Set<@NonNull String> connectDomains = Collections.emptySet();
			@NonNull
			private Set<@NonNull String> resourceDomains = Collections.emptySet();
			@NonNull
			private Set<@NonNull String> frameDomains = Collections.emptySet();
			@NonNull
			private Set<@NonNull String> baseUriDomains = Collections.emptySet();

			private Builder() {}

			/**
			 * Replaces connection origins with a validated, sorted snapshot.
			 *
			 * @param connectDomains canonical HTTPS/WSS origins, or HTTP/WS loopback origins
			 * @return this builder
			 * @throws IllegalArgumentException if any origin is invalid
			 */
			@NonNull
			public Builder connectDomains(@NonNull Set<@NonNull String> connectDomains) {
				this.connectDomains = McpAppMetadataValidation.immutableOrigins(
						requireNonNull(connectDomains), true);
				return this;
			}

			/**
			 * Replaces asset origins with a validated, sorted snapshot.
			 *
			 * @param resourceDomains canonical HTTPS origins, or HTTP loopback origins
			 * @return this builder
			 * @throws IllegalArgumentException if any origin is invalid
			 */
			@NonNull
			public Builder resourceDomains(@NonNull Set<@NonNull String> resourceDomains) {
				this.resourceDomains = McpAppMetadataValidation.immutableOrigins(
						requireNonNull(resourceDomains), false);
				return this;
			}

			/**
			 * Replaces iframe origins with a validated, sorted snapshot.
			 *
			 * @param frameDomains canonical HTTPS origins, or HTTP loopback origins
			 * @return this builder
			 * @throws IllegalArgumentException if any origin is invalid
			 */
			@NonNull
			public Builder frameDomains(@NonNull Set<@NonNull String> frameDomains) {
				this.frameDomains = McpAppMetadataValidation.immutableOrigins(
						requireNonNull(frameDomains), false);
				return this;
			}

			/**
			 * Replaces document-base origins with a validated, sorted snapshot.
			 *
			 * @param baseUriDomains canonical HTTPS origins, or HTTP loopback origins
			 * @return this builder
			 * @throws IllegalArgumentException if any origin is invalid
			 */
			@NonNull
			public Builder baseUriDomains(@NonNull Set<@NonNull String> baseUriDomains) {
				this.baseUriDomains = McpAppMetadataValidation.immutableOrigins(
						requireNonNull(baseUriDomains), false);
				return this;
			}

			/** @return immutable content security policy */
			@NonNull
			public ContentSecurityPolicy build() {
				return new ContentSecurityPolicy(this);
			}
		}
	}
}
