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
import java.util.Optional;

/**
 * Thread-safe, server-owned control plane for the live MCP protection key
 * ring.
 * <p>
 * Each server owns independent live state copied from its immutable
 * {@link McpProtectionConfig}. Every successful mutation has one linearization
 * point and validates against the live ring at that same point. Rejected
 * mutations leave the ring unchanged.
 * <p>
 * A fleet rotation stages the identical key on every instance, compares the
 * secret-free snapshots, activates the staged key everywhere, waits for the
 * former key's maximum request-state lifetime and outstanding sealing
 * reservations, and finally removes the former key from every instance.
 * <p>
 * A sealing reservation is Soklet's internal acquisition of one exact active
 * key immediately before sealing. Activation may linearize while an earlier
 * reservation finishes under the former key. Removal rejects a key until all
 * of its reservations have drained.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpProtectionControl {
	/** @return this server's effective protection mode */
	@NonNull
	McpProtectionMode getProtectionMode();

	/**
	 * Captures active and verification IDs plus their configuration fingerprint
	 * at one linearization point.
	 *
	 * @return secret-free snapshot, or empty when no production ring is active
	 */
	@NonNull
	Optional<@NonNull McpProtectionKeyRingSnapshot> getKeyRingSnapshot();

	/**
	 * Adds a verification-only key to the live production ring.
	 * <p>
	 * Re-staging a byte-identical key under the same ID is an idempotent no-op,
	 * whether it is currently active or verification-only. The mutation rejects
	 * a duplicate ID with different material, material already present under a
	 * different ID, or material equal to the active trace-correlation key.
	 *
	 * @param verificationKey key to stage
	 * @throws IllegalArgumentException if the mutation is invalid
	 * @throws IllegalStateException if this server has no production key ring
	 */
	void stageVerificationKey(@NonNull McpProtectionKey verificationKey);

	/**
	 * Activates a staged key and atomically demotes the former active key.
	 * Activating the already-active ID is an idempotent no-op.
	 *
	 * @param keyId staged verification-key ID
	 * @throws IllegalArgumentException if the ID is unknown
	 * @throws IllegalStateException if this server has no production key ring
	 */
	void activateStagedKey(@NonNull String keyId);

	/**
	 * Atomically stages and activates a key.
	 * <p>
	 * This convenience is retry-safe for the byte-identical already-active key.
	 * Fleet deployments normally stage on every instance before activation.
	 *
	 * @param activeKey new active key
	 * @throws IllegalArgumentException if the mutation is invalid
	 * @throws IllegalStateException if this server has no production key ring
	 */
	void rotateTo(@NonNull McpProtectionKey activeKey);

	/**
	 * Removes a verification-only key.
	 * <p>
	 * An absent ID returns {@code false}; removing the active ID is rejected. A
	 * key with an outstanding sealing reservation fails transiently with
	 * {@link McpKeyInUseException}.
	 *
	 * @param keyId verification-only key ID
	 * @return whether a key was removed
	 * @throws IllegalArgumentException if the ID names the active key
	 * @throws IllegalStateException if this server has no production key ring
	 * @throws McpKeyInUseException if the key is still used for sealing
	 */
	boolean removeVerificationKey(@NonNull String keyId);
}
