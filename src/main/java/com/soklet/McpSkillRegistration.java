/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import com.soklet.internal.mcp.skills.McpSkillRuntimeBridge;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable association between a Skills snapshot and its root resource URI.
 *
 * <p>Construction validates the generated manifest but does not publish endpoint
 * resources. The URI identifies the root {@code SKILL.md}, not a directory.
 * Locale and cache settings do not grant authorization to read the bundle.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSkillRegistration {
	@NonNull private final URI uri;
	@NonNull private final McpSkillBundle skillBundle;
	@Nullable private final Locale locale;
	@NonNull private final McpCachePolicy cachePolicy;
	@NonNull private final List<@NonNull Resource> resources;
	private final McpSkillRuntimeBridge.@NonNull Registration runtimeRegistration;

	private McpSkillRegistration(@NonNull Builder builder) {
		this.uri = builder.uri;
		this.skillBundle = builder.skillBundle;
		this.locale = builder.locale;
		this.cachePolicy = builder.cachePolicy;
		this.runtimeRegistration = McpSkillRuntimeBridge.register(this.uri,
				this.skillBundle.runtimeBundle(), this.cachePolicy);
		this.resources = this.runtimeRegistration.resources().stream().map(Resource::new).toList();
	}

	/**
	 * Starts registration of a bundle at its root document URI.
	 *
	 * @param uri absolute normalized hierarchical ASCII URI ending in literal
	 * {@code /SKILL.md}, without query, fragment, dot segments, or encoded separators
	 * @param skillBundle validated immutable snapshot whose name matches the URI's
	 * final skill-directory segment
	 * @return mutable builder with no locale and private zero-TTL cache policy
	 * @throws NullPointerException if either argument is null
	 */
	@NonNull
	public static Builder withUriAndSkillBundle(@NonNull URI uri, @NonNull McpSkillBundle skillBundle) {
		return new Builder(uri, skillBundle);
	}

	/** @return original root {@code SKILL.md} URI */
	@NonNull
	public URI getUri() { return this.uri; }

	/** @return the supplied immutable bundle */
	@NonNull
	public McpSkillBundle getSkillBundle() { return this.skillBundle; }

	/** @return configured locale, or empty when omitted */
	@NonNull
	public Optional<@NonNull Locale> getLocale() { return Optional.ofNullable(this.locale); }

	/** @return configured cache policy, private zero-TTL when omitted */
	@NonNull
	public McpCachePolicy getCachePolicy() { return this.cachePolicy; }

	/**
	 * Returns framework-derived file manifest entries without copying file bytes.
	 *
	 * @return cached immutable list in canonical bundle path order, root document first
	 */
	@NonNull
	public List<McpSkillRegistration.@NonNull Resource> getResources() { return this.resources; }

	McpSkillRuntimeBridge.@NonNull Registration runtimeRegistration() { return this.runtimeRegistration; }

	/** @return whether URI identity, exact bundle content, locale, and cache policy match */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || other instanceof McpSkillRegistration registration
				&& this.uri.equals(registration.uri) && this.skillBundle.equals(registration.skillBundle)
				&& Objects.equals(this.locale, registration.locale) && this.cachePolicy.equals(registration.cachePolicy);
	}

	/** @return structural registration hash code */
	@Override
	public int hashCode() { return Objects.hash(this.uri, this.skillBundle, this.locale, this.cachePolicy); }

	/** @return diagnostic rendering without registration or bundle content */
	@Override
	@NonNull
	public String toString() { return "McpSkillRegistration[redacted]"; }

	/**
	 * Immutable, framework-generated resource identity and content fingerprint.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public static final class Resource {
		@NonNull private final URI uri;
		@NonNull private final String digest;
		@NonNull private final Long sizeInBytes;

		private Resource(McpSkillRuntimeBridge.@NonNull Resource resource) {
			this.uri = resource.uri();
			this.digest = resource.digest();
			this.sizeInBytes = resource.sizeInBytes();
		}

		/** @return canonical absolute file URI */
		@NonNull
		public URI getUri() { return this.uri; }

		/** @return {@code sha256:} followed by lowercase hexadecimal SHA-256 of served bytes */
		@NonNull
		public String getDigest() { return this.digest; }

		/** @return exact raw file size in bytes */
		@NonNull
		public Long getSizeInBytes() { return this.sizeInBytes; }

		/** @return whether URI identity, digest, and byte size match */
		@Override
		public boolean equals(@Nullable Object other) {
			return this == other || other instanceof Resource resource
					&& this.uri.equals(resource.uri) && this.digest.equals(resource.digest)
					&& this.sizeInBytes.equals(resource.sizeInBytes);
		}

		/** @return structural resource hash code */
		@Override
		public int hashCode() { return Objects.hash(this.uri, this.digest, this.sizeInBytes); }

		/** @return diagnostic rendering without URI, digest, or byte size */
		@Override
		@NonNull
		public String toString() { return "McpSkillRegistration.Resource[redacted]"; }
	}

	/**
	 * Mutable builder for an immutable Skills registration.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull private final URI uri;
		@NonNull private final McpSkillBundle skillBundle;
		@Nullable private Locale locale;
		@NonNull private McpCachePolicy cachePolicy = McpCachePolicy.privateNoCacheInstance();

		private Builder(@NonNull URI uri, @NonNull McpSkillBundle skillBundle) {
			this.uri = requireNonNull(uri, "A Skills root URI is required.");
			this.skillBundle = requireNonNull(skillBundle, "A Skills bundle is required.");
		}

		/**
		 * Selects the locale associated with this registration.
		 *
		 * @param locale non-null registration locale
		 * @return this builder
		 * @throws NullPointerException if the locale is null
		 */
		@NonNull
		public Builder locale(@NonNull Locale locale) {
			this.locale = requireNonNull(locale, "A Skills locale is required.");
			return this;
		}

		/**
		 * Replaces the registration's cache policy.
		 *
		 * @param cachePolicy non-null policy; public scope is appropriate only for
		 * content that does not vary by private identity or authorization
		 * @return this builder
		 * @throws NullPointerException if the policy is null
		 */
		@NonNull
		public Builder cachePolicy(@NonNull McpCachePolicy cachePolicy) {
			this.cachePolicy = requireNonNull(cachePolicy, "A Skills cache policy is required.");
			return this;
		}

		/**
		 * Validates URI/name agreement and bounded manifest/resource representations.
		 *
		 * <p>Preflight uses representative response wrappers. It cannot account for
		 * arbitrary request IDs, server metadata, or multi-entry endpoint pages.
		 * Endpoint-wide checks and final request serialization remain separate;
		 * construction is not a guarantee that every later response envelope fits.
		 *
		 * @return immutable registration snapshot
		 * @throws IllegalArgumentException if the URI or generated response representation is invalid
		 */
		@NonNull
		public McpSkillRegistration build() { return new McpSkillRegistration(this); }
	}
}
