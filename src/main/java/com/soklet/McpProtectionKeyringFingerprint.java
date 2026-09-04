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

import javax.annotation.concurrent.ThreadSafe;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Secret-free deployment-comparison fingerprint for one complete live MCP
 * protection keyring configuration.
 * <p>
 * This value is operational metadata only, not an authentication input. It
 * exposes neither raw key material nor per-key fingerprint tags.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpProtectionKeyringFingerprint {
	/** Fingerprint encoding version. */
	@NonNull
	public static final String VERSION = "v1";

	/** Built-in protection profile included in the fingerprint. */
	@NonNull
	public static final String PROFILE = "soklet-mcp-protection-v1";

	@NonNull
	private final String value;

	McpProtectionKeyringFingerprint(@NonNull String value) {
		this.value = requireNonNull(value);
	}

	/** @return fingerprint encoding version */
	@NonNull
	public String getVersion() {
		return VERSION;
	}

	/** @return protection profile represented by this fingerprint */
	@NonNull
	public String getProfile() {
		return PROFILE;
	}

	/** @return unpadded Base64URL fingerprint value */
	@NonNull
	public String getValue() {
		return this.value;
	}

	/** @return the unpadded Base64URL fingerprint value */
	@Override
	@NonNull
	public String toString() {
		return this.value;
	}

	/**
	 * Compares fingerprint values.
	 *
	 * @param object value to compare
	 * @return whether the values are equal
	 */
	@Override
	public boolean equals(@Nullable Object object) {
		return object instanceof McpProtectionKeyringFingerprint other
				&& this.value.equals(other.value);
	}

	/** @return hash code for the fingerprint value */
	@Override
	public int hashCode() {
		return Objects.hash(this.value);
	}
}
