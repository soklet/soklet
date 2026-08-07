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

import javax.annotation.concurrent.ThreadSafe;
import java.security.MessageDigest;
import java.util.Arrays;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, purpose-specific MCP request-state protection master key.
 * <p>
 * Supplied bytes are defensively copied and are never exposed through the
 * public API, diagnostic rendering, logs, metrics, fingerprints, or exception
 * messages. Applications remain responsible for generating key material with
 * a cryptographically secure random source and at least 256 bits of entropy.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpProtectionKey {
	private static final int MINIMUM_KEY_BYTES = 32;
	@NonNull
	private final String keyId;
	private final byte @NonNull [] keyMaterial;

	/**
	 * Creates a protection master key from exact bytes.
	 *
	 * @param keyId 1-64-byte ASCII HTTP token
	 * @param keyMaterial at least 32 bytes of operator-generated key material
	 * @return immutable key value
	 * @throws IllegalArgumentException if the ID or material is invalid
	 */
	@NonNull
	public static McpProtectionKey fromIdAndBytes(@NonNull String keyId,
			byte @NonNull [] keyMaterial) {
		return new McpProtectionKey(keyId, keyMaterial);
	}

	private McpProtectionKey(@NonNull String keyId,
			byte @NonNull [] keyMaterial) {
		this.keyId = McpKeyIdValidator.validate(keyId,
				"MCP protection key ID");
		requireNonNull(keyMaterial);
		if (keyMaterial.length < MINIMUM_KEY_BYTES)
			throw new IllegalArgumentException(
					"MCP protection keys must contain at least 32 bytes.");
		this.keyMaterial = keyMaterial.clone();
	}

	/** @return non-secret key ID */
	@NonNull
	public String getKeyId() {
		return this.keyId;
	}

	byte @NonNull [] copyKeyMaterial() {
		return this.keyMaterial.clone();
	}

	boolean hasSameMaterial(@NonNull McpProtectionKey other) {
		return MessageDigest.isEqual(this.keyMaterial,
				requireNonNull(other).keyMaterial);
	}

	boolean hasSameMaterial(@NonNull McpTraceCorrelationKey other) {
		byte[] otherKeyMaterial = requireNonNull(other).copyKeyMaterial();
		try {
			return MessageDigest.isEqual(this.keyMaterial, otherKeyMaterial);
		} finally {
			Arrays.fill(otherKeyMaterial, (byte) 0);
		}
	}

	/** @return redacted rendering containing only the non-secret key ID */
	@Override
	@NonNull
	public String toString() {
		return "%s{keyId='%s', keyMaterial=<redacted>}"
				.formatted(getClass().getSimpleName(), this.keyId);
	}
}
