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
import java.util.Collection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Immutable initial MCP request-state protection key ring.
 * <p>
 * Exactly one key is initially active for sealing. Other entries are initially
 * verification-only. Building a server copies the complete ring, including
 * key material, into independent server-owned live state. Runtime rotation is
 * available only through that server's {@link McpProtectionControl}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpProtectionKeyRing {
	@NonNull
	private final McpProtectionKey activeKey;
	@NonNull
	private final Map<@NonNull String, @NonNull McpProtectionKey> verificationKeys;

	/**
	 * Vends a builder primed with the initial active key.
	 *
	 * @param activeKey initial active key
	 * @return key-ring builder
	 */
	@NonNull
	public static Builder withActiveKey(@NonNull McpProtectionKey activeKey) {
		return new Builder(activeKey);
	}

	private McpProtectionKeyRing(@NonNull Builder builder) {
		this.activeKey = copyOf(builder.activeKey);
		LinkedHashMap<@NonNull String, @NonNull McpProtectionKey> copies =
				new LinkedHashMap<>();
		builder.verificationKeys.forEach(
				(keyId, key) -> copies.put(keyId, copyOf(key)));
		this.verificationKeys = Map.copyOf(copies);
	}

	@NonNull
	McpProtectionKey copyInitialActiveKey() {
		return copyOf(this.activeKey);
	}

	@NonNull
	Map<@NonNull String, @NonNull McpProtectionKey>
			copyInitialVerificationKeys() {
		LinkedHashMap<@NonNull String, @NonNull McpProtectionKey> copies =
				new LinkedHashMap<>();
		this.verificationKeys.forEach(
				(keyId, key) -> copies.put(keyId, copyOf(key)));
		return Map.copyOf(copies);
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
	 * Single-threaded builder for an immutable initial key ring.
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
		public Builder verificationKey(
				@NonNull McpProtectionKey verificationKey) {
			addVerificationKey(requireNonNull(verificationKey));
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
		public Builder verificationKeys(
				@NonNull Collection<@NonNull McpProtectionKey> verificationKeys) {
			requireNonNull(verificationKeys);
			LinkedHashMap<@NonNull String, @NonNull McpProtectionKey> updated =
					new LinkedHashMap<>(this.verificationKeys);
			verificationKeys.forEach(key -> addVerificationKey(updated, key));
			this.verificationKeys.clear();
			this.verificationKeys.putAll(updated);
			return this;
		}

		/** @return immutable initial key ring */
		@NonNull
		public McpProtectionKeyRing build() {
			return new McpProtectionKeyRing(this);
		}

		private void addVerificationKey(
				@NonNull McpProtectionKey candidate) {
			addVerificationKey(this.verificationKeys, candidate);
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
