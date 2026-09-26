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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * An immutable RFC 6750 Bearer challenge with an RFC 9728 protected-resource
 * metadata URL. {@link #getHeaderValue()} renders one {@code WWW-Authenticate}
 * field value, without the field name. Constructing a challenge validates its
 * syntax; the application remains responsible for publishing the metadata and
 * choosing scopes its authorization server can grant.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class BearerAuthenticationChallenge {
	@NonNull
	private final URI resourceMetadataUri;
	@Nullable
	private final BearerAuthenticationError error;
	@NonNull
	private final List<@NonNull String> requiredScopes;
	@Nullable
	private final String realm;
	@Nullable
	private final String errorDescription;
	@Nullable
	private final URI errorUri;
	@NonNull
	private final String headerValue;

	/** @return a builder with the configured protected-resource metadata URL */
	@NonNull
	public static Builder withResourceMetadataUri(
			@NonNull URI resourceMetadataUri) {
		return new Builder(resourceMetadataUri);
	}

	private BearerAuthenticationChallenge(@NonNull Builder builder) {
		requireNonNull(builder);
		this.resourceMetadataUri = requireMetadataUri(builder.resourceMetadataUri);
		this.error = builder.error;
		this.requiredScopes = validatedScopes(builder.requiredScopes);
		this.realm = builder.realm == null ? null
				: requireQuotedRealm(builder.realm);
		this.errorDescription = builder.errorDescription == null ? null
				: requireBearerText(builder.errorDescription, "errorDescription", true);
		this.errorUri = builder.errorUri == null ? null
				: requireErrorUri(builder.errorUri);
		if (this.error == BearerAuthenticationError.INSUFFICIENT_SCOPE
				&& this.requiredScopes.isEmpty())
			throw new IllegalArgumentException(
					"An insufficient-scope challenge requires at least one scope.");
		if (this.error == null
				&& (this.errorDescription != null || this.errorUri != null))
			throw new IllegalArgumentException(
					"Error details require a Bearer authentication error.");
		this.headerValue = render();
	}

	/** @return the configured absolute protected-resource metadata URI */
	@NonNull
	public URI getResourceMetadataUri() {
		return this.resourceMetadataUri;
	}

	/** @return the RFC 6750 Bearer error, when supplied */
	@NonNull
	public Optional<@NonNull BearerAuthenticationError> getError() {
		return Optional.ofNullable(this.error);
	}

	/** @return immutable scope tokens in first-encounter order */
	@NonNull
	public List<@NonNull String> getRequiredScopes() {
		return this.requiredScopes;
	}

	/** @return the protection realm, when supplied */
	@NonNull
	public Optional<@NonNull String> getRealm() {
		return Optional.ofNullable(this.realm);
	}

	/** @return the developer-facing error description, when supplied */
	@NonNull
	public Optional<@NonNull String> getErrorDescription() {
		return Optional.ofNullable(this.errorDescription);
	}

	/** @return the error explanation URI, when supplied */
	@NonNull
	public Optional<@NonNull URI> getErrorUri() {
		return Optional.ofNullable(this.errorUri);
	}

	/**
	 * Returns the RFC-recommended HTTP status for this challenge: 400 for an
	 * invalid request, 403 for insufficient scope, and 401 otherwise.
	 *
	 * @return recommended HTTP status
	 */
	@NonNull
	public Integer getRecommendedStatusCode() {
		if (this.error == BearerAuthenticationError.INVALID_REQUEST)
			return 400;
		if (this.error == BearerAuthenticationError.INSUFFICIENT_SCOPE)
			return 403;
		return 401;
	}

	/** @return the complete {@code WWW-Authenticate} field value */
	@NonNull
	public String getHeaderValue() {
		return this.headerValue;
	}

	private String render() {
		List<String> parameters = new ArrayList<>();
		if (this.realm != null)
			parameters.add("realm=" + quoted(this.realm));
		if (this.error != null)
			parameters.add("error=" + quoted(switch (this.error) {
				case INVALID_REQUEST -> "invalid_request";
				case INVALID_TOKEN -> "invalid_token";
				case INSUFFICIENT_SCOPE -> "insufficient_scope";
			}));
		if (!this.requiredScopes.isEmpty())
			parameters.add("scope=" + quoted(String.join(" ", this.requiredScopes)));
		parameters.add("resource_metadata="
				+ quoted(this.resourceMetadataUri.toASCIIString()));
		if (this.errorDescription != null)
			parameters.add("error_description=" + quoted(this.errorDescription));
		if (this.errorUri != null)
			parameters.add("error_uri=" + quoted(this.errorUri.toASCIIString()));
		return "Bearer " + String.join(", ", parameters);
	}

	private static String quoted(@NonNull String value) {
		return "\"" + value.replace("\\", "\\\\")
				.replace("\"", "\\\"") + "\"";
	}

	private static URI requireMetadataUri(@NonNull URI uri) {
		requireNonNull(uri);
		if (!uri.isAbsolute() || uri.getHost() == null
				|| !("https".equalsIgnoreCase(uri.getScheme())
						|| "http".equalsIgnoreCase(uri.getScheme()))
				|| uri.getRawFragment() != null || uri.getRawUserInfo() != null)
			throw new IllegalArgumentException(
					"resourceMetadataUri must be an absolute HTTP(S) URL without a fragment or user information.");
		return uri;
	}

	private static URI requireErrorUri(@NonNull URI uri) {
		requireNonNull(uri);
		if (!uri.isAbsolute())
			throw new IllegalArgumentException(
					"errorUri must be an absolute URI.");
		requireBearerText(uri.toASCIIString(), "errorUri", false);
		return uri;
	}

	private static String requireQuotedRealm(@NonNull String realm) {
		requireNonNull(realm);
		for (int index = 0; index < realm.length(); index++) {
			char character = realm.charAt(index);
			if (character < 0x20 || character > 0x7e)
				throw new IllegalArgumentException(
						"realm must contain printable ASCII characters.");
		}
		return realm;
	}

	private static String requireBearerText(@NonNull String value,
			@NonNull String field, boolean allowSpace) {
		requireNonNull(value);
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if ((character == 0x20 && allowSpace)
					|| character == 0x21
					|| (character >= 0x23 && character <= 0x5b)
					|| (character >= 0x5d && character <= 0x7e))
				continue;
			throw new IllegalArgumentException(
					field + " contains a character forbidden by RFC 6750.");
		}
		return value;
	}

	private static List<String> validatedScopes(
			@NonNull List<@NonNull String> requiredScopes) {
		requireNonNull(requiredScopes);
		LinkedHashSet<String> distinct = new LinkedHashSet<>();
		for (String requiredScope : requiredScopes) {
			String scope = requireNonNull(requiredScope);
			if (scope.isEmpty())
				throw new IllegalArgumentException("A required scope must not be empty.");
			requireBearerText(scope, "requiredScope", false);
			distinct.add(scope);
		}
		return List.copyOf(distinct);
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof BearerAuthenticationChallenge challenge))
			return false;
		return this.resourceMetadataUri.equals(challenge.resourceMetadataUri)
				&& Objects.equals(this.error, challenge.error)
				&& this.requiredScopes.equals(challenge.requiredScopes)
				&& Objects.equals(this.realm, challenge.realm)
				&& Objects.equals(this.errorDescription, challenge.errorDescription)
				&& Objects.equals(this.errorUri, challenge.errorUri);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.resourceMetadataUri, this.error,
				this.requiredScopes, this.realm, this.errorDescription, this.errorUri);
	}

	@Override
	@NonNull
	public String toString() {
		return "BearerAuthenticationChallenge[error=" + this.error
				+ ", requiredScopeCount=" + this.requiredScopes.size() + "]";
	}

	/** Mutable builder for one immutable Bearer challenge. */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final URI resourceMetadataUri;
		@Nullable
		private BearerAuthenticationError error;
		@NonNull
		private List<@NonNull String> requiredScopes = List.of();
		@Nullable
		private String realm;
		@Nullable
		private String errorDescription;
		@Nullable
		private URI errorUri;

		private Builder(@NonNull URI resourceMetadataUri) {
			this.resourceMetadataUri = requireMetadataUri(resourceMetadataUri);
		}

		/** @return this builder */
		@NonNull
		public Builder error(@NonNull BearerAuthenticationError error) {
			this.error = requireNonNull(error);
			return this;
		}

		/** @return this builder */
		@NonNull
		public Builder requiredScopes(
				@NonNull List<@NonNull String> requiredScopes) {
			this.requiredScopes = validatedScopes(requiredScopes);
			return this;
		}

		/** @return this builder */
		@NonNull
		public Builder realm(@NonNull String realm) {
			this.realm = requireQuotedRealm(realm);
			return this;
		}

		/** @return this builder */
		@NonNull
		public Builder errorDescription(@NonNull String errorDescription) {
			this.errorDescription = requireBearerText(errorDescription,
					"errorDescription", true);
			return this;
		}

		/** @return this builder */
		@NonNull
		public Builder errorUri(@NonNull URI errorUri) {
			this.errorUri = requireErrorUri(errorUri);
			return this;
		}

		/** @return one immutable, validated challenge */
		@NonNull
		public BearerAuthenticationChallenge build() {
			return new BearerAuthenticationChallenge(this);
		}
	}
}
