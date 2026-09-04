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
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Shallowly immutable application-supplied identity and stable, opaque
 * partition keys. The carrier itself is safe for concurrent access;
 * applications retain responsibility for the thread-safety of principal and
 * application-context objects they place inside it.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpAdmissionIdentity {
	/** Maximum UTF-8 encoding length of either partition key. */
	public static final int MAXIMUM_PARTITION_KEY_SIZE_IN_UTF_8_BYTES = 256;
	@NonNull
	private static final McpAdmissionIdentity ANONYMOUS =
			new Builder("anonymous").build();
	@NonNull
	private final String rateLimitPartitionKey;
	@Nullable
	private final String authorizationPartitionKey;
	@Nullable
	private final Object principal;
	@Nullable
	private final Object applicationContext;

	/**
	 * Returns the canonical anonymous identity.
	 *
	 * @return shared anonymous identity
	 */
	@NonNull
	public static McpAdmissionIdentity anonymousInstance() {
		return ANONYMOUS;
	}

	/**
	 * Vends an identity builder primed with a stable rate-limit partition key.
	 *
	 * @param rateLimitPartitionKey opaque, fleet-stable partition key
	 * @return identity builder
	 */
	@NonNull
	public static Builder withRateLimitPartitionKey(@NonNull String rateLimitPartitionKey) {
		return new Builder(rateLimitPartitionKey);
	}

	private McpAdmissionIdentity(@NonNull Builder builder) {
		this.rateLimitPartitionKey = requirePartitionKey(
				builder.rateLimitPartitionKey, "rateLimitPartitionKey");
		this.authorizationPartitionKey = builder.authorizationPartitionKey == null
				? null : requirePartitionKey(builder.authorizationPartitionKey,
				"authorizationPartitionKey");
		this.principal = builder.principal;
		this.applicationContext = builder.applicationContext;
		if (this.principal != null && this.authorizationPartitionKey == null)
			throw new IllegalStateException(
					"authorizationPartitionKey is required when principal is present");
	}

	/** @return whether a principal is present */
	@NonNull
	public Boolean isAuthenticated() {
		return this.principal != null;
	}

	/** @return application principal, when authenticated */
	@NonNull
	public Optional<@NonNull Object> getPrincipal() {
		return Optional.ofNullable(this.principal);
	}

	/** @return optional application context propagated to later MCP hooks */
	@NonNull
	public Optional<@NonNull Object> getApplicationContext() {
		return Optional.ofNullable(this.applicationContext);
	}

	/** @return opaque rate-limit partition key */
	@NonNull
	public String getRateLimitPartitionKey() {
		return this.rateLimitPartitionKey;
	}

	/**
	 * Returns the opaque authorization partition key. Absence selects Soklet's
	 * endpoint-scoped anonymous authorization partition.
	 *
	 * @return authorization partition key, when supplied
	 */
	@NonNull
	public Optional<@NonNull String> getAuthorizationPartitionKey() {
		return Optional.ofNullable(this.authorizationPartitionKey);
	}

	@NonNull
	private static String requirePartitionKey(@NonNull String value,
			@NonNull String name) {
		requireNonNull(value, name);
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		try {
			int length = StandardCharsets.UTF_8.newEncoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.encode(CharBuffer.wrap(value)).remaining();
			if (length > MAXIMUM_PARTITION_KEY_SIZE_IN_UTF_8_BYTES)
				throw new IllegalArgumentException(name + " must contain at most "
						+ MAXIMUM_PARTITION_KEY_SIZE_IN_UTF_8_BYTES + " UTF-8 bytes");
		} catch (CharacterCodingException exception) {
			throw new IllegalArgumentException(name + " must contain valid Unicode text", exception);
		}
		return value;
	}

	/**
	 * Mutable builder for an immutable {@link McpAdmissionIdentity}.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final String rateLimitPartitionKey;
		@Nullable
		private String authorizationPartitionKey;
		@Nullable
		private Object principal;
		@Nullable
		private Object applicationContext;

		private Builder(@NonNull String rateLimitPartitionKey) {
			this.rateLimitPartitionKey = requireNonNull(rateLimitPartitionKey);
		}

		/**
		 * Sets the stable authorization partition key.
		 *
		 * @param authorizationPartitionKey opaque authorization partition
		 * @return this builder
		 */
		@NonNull
		public Builder authorizationPartitionKey(@NonNull String authorizationPartitionKey) {
			this.authorizationPartitionKey = requireNonNull(authorizationPartitionKey);
			return this;
		}

		/**
		 * Sets the authenticated application principal. An authorization partition
		 * key must also be supplied.
		 *
		 * @param principal application principal
		 * @return this builder
		 */
		@NonNull
		public Builder principal(@NonNull Object principal) {
			this.principal = requireNonNull(principal);
			return this;
		}

		/**
		 * Sets optional application context propagated to later MCP hooks.
		 *
		 * @param applicationContext application context
		 * @return this builder
		 */
		@NonNull
		public Builder applicationContext(@NonNull Object applicationContext) {
			this.applicationContext = requireNonNull(applicationContext);
			return this;
		}

		/** @return immutable admission identity */
		@NonNull
		public McpAdmissionIdentity build() {
			return new McpAdmissionIdentity(this);
		}
	}
}
