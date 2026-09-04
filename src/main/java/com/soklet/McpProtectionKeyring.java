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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable initial MCP request-state protection keyring.
 * <p>
 * Exactly one key is initially active for sealing. Other entries are initially
 * verification-only. Building a server copies the complete ring, including
 * key material, into independent server-owned live state. Runtime rotation is
 * available only through that server's {@link McpProtectionControl}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpProtectionKeyring {
	@NonNull
	private final McpProtectionKey activeKey;
	@NonNull
	private final Map<@NonNull String, @NonNull McpProtectionKey> verificationKeys;

	/**
	 * Vends a builder primed with the initial active key.
	 *
	 * @param activeKey initial active key
	 * @return keyring builder
	 */
	@NonNull
	public static Builder withActiveKey(@NonNull McpProtectionKey activeKey) {
		return new Builder(activeKey);
	}

	private McpProtectionKeyring(@NonNull Builder builder) {
		this.activeKey = copyOf(builder.activeKey);
		LinkedHashMap<@NonNull String, @NonNull McpProtectionKey> copies =
				new LinkedHashMap<>();
		builder.verificationKeys.forEach(
				(keyId, key) -> copies.put(keyId, copyOf(key)));
		this.verificationKeys = Map.copyOf(copies);
	}

	/** @return non-secret active key ID */
	@NonNull
	public String getActiveKeyId() {
		return this.activeKey.getKeyId();
	}

	/** @return immutable verification-only key ID set */
	@NonNull
	public Set<@NonNull String> getVerificationKeyIds() {
		return this.verificationKeys.keySet();
	}

	@NonNull
	McpProtectionKey initialActiveKey() {
		return this.activeKey;
	}

	@NonNull
	Collection<@NonNull McpProtectionKey> initialVerificationKeys() {
		return this.verificationKeys.values();
	}

	@NonNull
	private static McpProtectionKey copyOf(@NonNull McpProtectionKey key) {
		byte[] keyMaterial = key.copyKeyMaterial();
		try {
			return McpProtectionKey.fromIdAndBytes(key.getKeyId(), keyMaterial);
		} finally {
			Arrays.fill(keyMaterial, (byte) 0);
		}
	}

	/**
	 * Single-threaded builder for an immutable initial keyring.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final McpProtectionKey activeKey;
		@NonNull
		private final Map<@NonNull String, @NonNull McpProtectionKey>
				verificationKeys;

		private Builder(@NonNull McpProtectionKey activeKey) {
			this.activeKey = requireNonNull(activeKey);
			this.verificationKeys = new LinkedHashMap<>();
		}

		/**
		 * Adds an initial verification-only key.
		 *
		 * @param verificationKey key to add
		 * @return this builder
		 * @throws IllegalArgumentException if its ID or material duplicates an
		 *                                  initial key
		 */
		@NonNull
		public Builder addVerificationKey(
				@NonNull McpProtectionKey verificationKey) {
			addVerificationKey(this.verificationKeys,
					requireNonNull(verificationKey));
			return this;
		}

		/**
		 * Adds initial verification-only keys.
		 *
		 * @param verificationKeys keys to add
		 * @return this builder
		 * @throws IllegalArgumentException if an ID or material duplicates an
		 *                                  initial key
		 */
		@NonNull
		public Builder addVerificationKeys(
				@NonNull Collection<@NonNull McpProtectionKey> verificationKeys) {
			requireNonNull(verificationKeys);
			LinkedHashMap<@NonNull String, @NonNull McpProtectionKey> updated =
					new LinkedHashMap<>(this.verificationKeys);
			verificationKeys.forEach(key -> addVerificationKey(updated, key));
			this.verificationKeys.clear();
			this.verificationKeys.putAll(updated);
			return this;
		}

		/** @return immutable initial keyring */
		@NonNull
		public McpProtectionKeyring build() {
			return new McpProtectionKeyring(this);
		}

		private void addVerificationKey(
				@NonNull Map<@NonNull String, @NonNull McpProtectionKey>
						verificationKeys,
				@NonNull McpProtectionKey candidate) {
			requireNonNull(verificationKeys);
			requireNonNull(candidate);
			if (this.activeKey.getKeyId().equals(candidate.getKeyId())
					|| verificationKeys.containsKey(candidate.getKeyId()))
				throw new IllegalArgumentException(
						"Duplicate MCP protection key ID.");
			if (this.activeKey.hasSameMaterial(candidate)
					|| verificationKeys.values().stream()
					.anyMatch(existing -> existing.hasSameMaterial(candidate)))
				throw new IllegalArgumentException(
						"Duplicate MCP protection key material.");
			verificationKeys.put(candidate.getKeyId(), candidate);
		}
	}
}
