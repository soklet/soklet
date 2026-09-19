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
import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable MCP Apps tool association and audience metadata.
 *
 * <p>An app-visible helper need not declare a resource URI. Neither visibility
 * nor the resource association is an authorization grant or proof that a call
 * originated from an authorized UI; applications authorize every invocation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpAppToolMetadata {

	@Nullable
	private final URI resourceUri;
	@NonNull
	private final Set<@NonNull Visibility> visibility;

	/** @return mutable builder with both audiences and no resource association */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	private McpAppToolMetadata(@NonNull Builder builder) {
		this.resourceUri = builder.resourceUri;
		this.visibility = builder.visibility;
	}

	/** @return concrete normalized ASCII {@code ui://} resource URI, if supplied */
	@NonNull
	public Optional<@NonNull URI> getResourceUri() {
		return Optional.ofNullable(this.resourceUri);
	}

	/** @return immutable audiences in enum declaration order; explicit empty stays empty */
	@NonNull
	public Set<@NonNull Visibility> getVisibility() {
		return this.visibility;
	}

	/** @return whether the resource association and effective audiences match */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpAppToolMetadata metadata))
			return false;
		return Objects.equals(this.resourceUri, metadata.resourceUri)
				&& this.visibility.equals(metadata.visibility);
	}

	/** @return structural hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.resourceUri, this.visibility);
	}

	/** @return diagnostic rendering without application configuration */
	@Override
	@NonNull
	public String toString() {
		return "McpAppToolMetadata{resourceUri=<redacted>, visibility=<redacted>}";
	}

	/**
	 * Closed Apps audience domain for Soklet 4.0. A new audience requires an
	 * explicit domain/API-evolution amendment.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public enum Visibility {
		/** Tool is visible to the model. */
		MODEL,
		/** Tool is visible to Apps hosts. */
		APP
	}

	/**
	 * Mutable builder for immutable Apps tool metadata.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private URI resourceUri;
		@NonNull
		private Set<@NonNull Visibility> visibility = Collections.unmodifiableSet(
				new LinkedHashSet<>(EnumSet.allOf(Visibility.class)));

		private Builder() {}

		/**
		 * Associates a concrete UI resource without fetching or dereferencing it.
		 *
		 * @param resourceUri normalized absolute ASCII {@code ui://} URI
		 * @return this builder
		 */
		@NonNull
		public Builder resourceUri(@NonNull URI resourceUri) {
			this.resourceUri = McpAppMetadataValidation.requireResourceUri(resourceUri);
			return this;
		}

		/**
		 * Replaces the effective audiences. Empty means neither audience.
		 *
		 * @param visibility non-null audiences, defensively copied in declaration order
		 * @return this builder
		 */
		@NonNull
		public Builder visibility(@NonNull Set<@NonNull Visibility> visibility) {
			requireNonNull(visibility);
			EnumSet<Visibility> snapshot = EnumSet.noneOf(Visibility.class);
			for (Visibility value : visibility)
				snapshot.add(requireNonNull(value));
			this.visibility = Collections.unmodifiableSet(new LinkedHashSet<>(snapshot));
			return this;
		}

		/** @return immutable metadata snapshot */
		@NonNull
		public McpAppToolMetadata build() {
			return new McpAppToolMetadata(this);
		}
	}
}
