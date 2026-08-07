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
 * Thread-safe, server-owned MCP trace-correlation control plane.
 * <p>
 * Correlation is disabled unless a key is supplied when the server is built.
 * Rotation atomically replaces both active key ID and key material. Soklet
 * retains no public history or historical-token derivation facility.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpTraceCorrelation {
	/** @return whether trace correlation was enabled at server construction */
	boolean isEnabled();

	/** @return active non-secret key ID, or empty when disabled */
	@NonNull
	Optional<@NonNull String> getActiveKeyId();

	/** @return active configuration fingerprint, or empty when disabled */
	@NonNull
	Optional<@NonNull McpTraceCorrelationConfigurationFingerprint>
			getConfigurationFingerprint();

	/**
	 * Atomically rotates both active key ID and material.
	 * <p>
	 * Re-supplying the byte-identical active entry is an idempotent no-op. Reuse
	 * of the active ID with different material is rejected so structured-log key
	 * IDs continue to identify one correlation-key version unambiguously.
	 *
	 * @param activeKey new active correlation key
	 * @throws IllegalArgumentException if its ID ambiguously reuses the active
	 *                                  ID or its material equals any protection
	 *                                  ring key material
	 * @throws IllegalStateException if correlation was not enabled when the
	 *                               server was built
	 */
	void rotateActiveKey(@NonNull McpTraceCorrelationKey activeKey);
}
