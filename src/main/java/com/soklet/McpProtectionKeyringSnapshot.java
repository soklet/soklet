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
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, secret-free atomic snapshot of a live MCP protection keyring.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpProtectionKeyringSnapshot {
	@NonNull
	private final String activeKeyId;
	@NonNull
	private final Set<@NonNull String> verificationKeyIds;
	@NonNull
	private final McpProtectionKeyringFingerprint fingerprint;

	McpProtectionKeyringSnapshot(@NonNull String activeKeyId,
			@NonNull Set<@NonNull String> verificationKeyIds,
			@NonNull McpProtectionKeyringFingerprint fingerprint) {
		this.activeKeyId = requireNonNull(activeKeyId);
		this.verificationKeyIds = Set.copyOf(verificationKeyIds);
		this.fingerprint = requireNonNull(fingerprint);
	}

	/** @return non-secret active key ID */
	@NonNull
	public String getActiveKeyId() {
		return this.activeKeyId;
	}

	/** @return immutable verification-only key ID set */
	@NonNull
	public Set<@NonNull String> getVerificationKeyIds() {
		return this.verificationKeyIds;
	}

	/** @return fingerprint for the complete captured ring */
	@NonNull
	public McpProtectionKeyringFingerprint getFingerprint() {
		return this.fingerprint;
	}
}
