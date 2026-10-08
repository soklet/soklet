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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
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
 * validated by the builder and revalidated before transport. A
 * {@link BearerAuthenticationChallenge} supplies a validated
 * {@code WWW-Authenticate} value; the application owns token verification,
 * protected-resource metadata, and scope policy. Unsafe,
 * framework-owned, hop-by-hop, CORS, and obsolete transport-state headers
 * fail during header construction without exposing custom names or values.
 * <p>
 * Header names must be ASCII HTTP tokens and unique ignoring case. Value lists
 * must be nonempty; each value may be empty but must contain only visible ASCII
 * or horizontal tabs. The fixed limits are 100 header fields and 65,536 bytes,
 * counting each field as its name length plus value length plus four bytes.
 * <p>
 * Reserved names, ignoring case, are {@code Cache-Control}, {@code Connection},
 * {@code Content-Encoding}, {@code Content-Length}, {@code Content-Type},
 * {@code Keep-Alive}, {@code Proxy-Authenticate}, {@code Proxy-Authorization},
 * {@code Proxy-Connection}, {@code TE}, {@code Trailer}, {@code Transfer-Encoding},
 * {@code Upgrade}, {@code Retry-After}, {@code Mcp-Session-Id}, {@code Last-Event-ID},
 * and every name beginning with {@code Access-Control-}.
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
	 * @throws NullPointerException if {@code statusCode} or {@code jsonRpcError} is null
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
		McpServerRuntimeBridge.validateAdmissionRejectionHeaders(copied);
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
	 * Returns the immutable snapshot of validated application response headers.
	 * Original name spelling, insertion order and value order are preserved.
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
		 * Replaces the application response headers with a validated snapshot.
		 * Passing an empty map clears all application response headers. Invalid
		 * replacement headers leave this builder's previous headers intact.
		 *
		 * @see McpAdmissionRejection for header safety rules and fixed bounds
		 *
		 * @param headers application response headers
		 * @return this builder
		 * @throws NullPointerException if the map, a name, a value list, or a value is null
		 * @throws IllegalArgumentException if the headers violate the safety rules or fixed bounds
		 */
		@NonNull
		public Builder headers(
				@NonNull Map<@NonNull String, ? extends @NonNull List<@NonNull String>> headers) {
			requireNonNull(headers);
			McpServerRuntimeBridge.validateAdmissionRejectionHeaders(headers);
			Map<String, List<String>> copied = new LinkedHashMap<>();
			headers.forEach((name, values) -> {
				String checkedName = requireNonNull(name);
				List<String> copiedValues = new ArrayList<>();
				requireNonNull(values).forEach(
						value -> copiedValues.add(requireNonNull(value)));
				copied.put(checkedName, copiedValues);
			});
			McpServerRuntimeBridge.validateAdmissionRejectionHeaders(copied);
			this.headers = copied;
			return this;
		}

		/**
		 * Adds a validated application response header. Reusing the exact name
		 * appends a value; a different case spelling of an existing name is rejected.
		 * Invalid additions leave this builder's previous headers intact.
		 *
		 * @see McpAdmissionRejection for header safety rules and fixed bounds
		 *
		 * @param name header name
		 * @param value header value
		 * @return this builder
		 * @throws NullPointerException if the name or value is null
		 * @throws IllegalArgumentException if the addition violates the safety rules or fixed bounds
		 */
		@NonNull
		public Builder addHeader(@NonNull String name, @NonNull String value) {
			requireNonNull(name);
			requireNonNull(value);
			Map<String, List<String>> updated = new LinkedHashMap<>(this.headers);
			List<String> values = new ArrayList<>(updated.getOrDefault(name, List.of()));
			values.add(value);
			updated.put(name, values);
			McpServerRuntimeBridge.validateAdmissionRejectionHeaders(updated);
			this.headers = updated;
			return this;
		}

		/** @return immutable rejection */
		@NonNull
		public McpAdmissionRejection build() {
			return new McpAdmissionRejection(this);
		}
	}
}
