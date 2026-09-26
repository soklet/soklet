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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Typed MCP admission rejection. Soklet owns envelope serialization and
 * suppresses the body for notifications. Application response headers are
 * transported only after Soklet's response-header safety validation. A
 * {@link BearerAuthenticationChallenge} supplies a validated
 * {@code WWW-Authenticate} value; the application owns token verification,
 * protected-resource metadata, and scope policy. Unsafe,
 * framework-owned, hop-by-hop, CORS, and obsolete transport-state headers
 * fail closed.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpAdmissionRejection {
	private final int statusCode;
	@NonNull
	private final McpJsonRpcError jsonRpcError;
	@NonNull
	private final Map<@NonNull String, @NonNull List<@NonNull String>> headers;

	/**
	 * Vends a rejection builder primed with an HTTP status and JSON-RPC error.
	 *
	 * @param statusCode HTTP status from 400 through 599
	 * @param jsonRpcError client-visible JSON-RPC error
	 * @return rejection builder
	 * @throws NullPointerException if {@code statusCode} is null
	 */
	@NonNull
	public static Builder withStatusCodeAndError(@NonNull Integer statusCode,
			@NonNull McpJsonRpcError jsonRpcError) {
		return new Builder().statusCode(statusCode).jsonRpcError(jsonRpcError);
	}

	/**
	 * Vends a rejection builder using the challenge's recommended HTTP status
	 * and adding its rendered {@code WWW-Authenticate} value.
	 *
	 * @param bearerAuthenticationChallenge validated Bearer challenge
	 * @param jsonRpcError client-visible JSON-RPC error
	 * @return rejection builder
	 */
	@NonNull
	public static Builder withBearerAuthenticationChallengeAndError(
			@NonNull BearerAuthenticationChallenge bearerAuthenticationChallenge,
			@NonNull McpJsonRpcError jsonRpcError) {
		BearerAuthenticationChallenge challenge =
				requireNonNull(bearerAuthenticationChallenge);
		return withStatusCodeAndError(challenge.getRecommendedStatusCode(),
				jsonRpcError).addHeader("WWW-Authenticate",
				challenge.getHeaderValue());
	}

	private McpAdmissionRejection(@NonNull Builder builder) {
		this.statusCode = builder.statusCode;
		if (this.statusCode < 400 || this.statusCode > 599)
			throw new IllegalArgumentException(
					"Admission rejection statusCode must be between 400 and 599");
		this.jsonRpcError = requireNonNull(builder.jsonRpcError, "jsonRpcError");
		Map<String, List<String>> copied = new LinkedHashMap<>();
		builder.headers.forEach((name, values) -> copied.put(
				requireNonNull(name), List.copyOf(requireNonNull(values))));
		this.headers = Collections.unmodifiableMap(copied);
	}

	/** @return rejection HTTP status */
	@NonNull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	/** @return client-visible JSON-RPC error */
	@NonNull
	public McpJsonRpcError getJsonRpcError() {
		return this.jsonRpcError;
	}

	/**
	 * Returns immutable application response headers. Values remain subject to
	 * Soklet's fail-closed response-header safety validation when transported.
	 *
	 * @return immutable application response headers
	 */
	@NonNull
	public Map<@NonNull String, @NonNull List<@NonNull String>> getHeaders() {
		return this.headers;
	}

	/** @return whether every admission-rejection property is structurally equal */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other)
			return true;
		if (!(other instanceof McpAdmissionRejection rejection))
			return false;
		return this.statusCode == rejection.statusCode
				&& this.jsonRpcError.equals(rejection.jsonRpcError)
				&& this.headers.equals(rejection.headers);
	}

	/** @return structural admission-rejection hash code */
	@Override
	public int hashCode() {
		return Objects.hash(this.statusCode, this.jsonRpcError, this.headers);
	}

	/**
	 * Mutable builder for an immutable {@link McpAdmissionRejection}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		private int statusCode;
		@Nullable
		private McpJsonRpcError jsonRpcError;
		@NonNull
		private Map<@NonNull String, @NonNull List<@NonNull String>> headers =
				new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * @param statusCode HTTP status from 400 through 599
		 * @return this builder
		 * @throws NullPointerException if {@code statusCode} is null
		 */
		@NonNull
		public Builder statusCode(@NonNull Integer statusCode) {
			this.statusCode = requireNonNull(statusCode);
			return this;
		}

		/** @param jsonRpcError client-visible error @return this builder */
		@NonNull
		public Builder jsonRpcError(@NonNull McpJsonRpcError jsonRpcError) {
			this.jsonRpcError = requireNonNull(jsonRpcError);
			return this;
		}

		/**
		 * Replaces the application response headers. Headers remain subject to
		 * Soklet's fail-closed response-header safety validation when transported.
		 * Passing an empty map clears all application response headers.
		 *
		 * @param headers application response headers
		 * @return this builder
		 * @throws NullPointerException if the map, a name, a value list, or a value is null
		 */
		@NonNull
		public Builder headers(
				@NonNull Map<@NonNull String, ? extends @NonNull List<@NonNull String>> headers) {
			requireNonNull(headers);
			Map<String, List<String>> copied = new LinkedHashMap<>();
			headers.forEach((name, values) -> {
				String checkedName = requireNonNull(name);
				List<String> copiedValues = new ArrayList<>();
				requireNonNull(values).forEach(
						value -> copiedValues.add(requireNonNull(value)));
				copied.put(checkedName, copiedValues);
			});
			this.headers = copied;
			return this;
		}

		/**
		 * Adds an application response header. The header remains subject to
		 * Soklet's fail-closed response-header safety validation when transported.
		 *
		 * @param name header name
		 * @param value header value
		 * @return this builder
		 * @throws NullPointerException if the name or value is null
		 */
		@NonNull
		public Builder addHeader(@NonNull String name, @NonNull String value) {
			requireNonNull(name);
			requireNonNull(value);
			this.headers.computeIfAbsent(name, ignored -> new ArrayList<>())
					.add(value);
			return this;
		}

		/** @return immutable rejection */
		@NonNull
		public McpAdmissionRejection build() {
			return new McpAdmissionRejection(this);
		}
	}
}
