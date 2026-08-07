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

/**
 * Thread-safe application-supplied alternative to Soklet's built-in
 * request-state protection.
 * <p>
 * The protector owns its algorithm, envelope format, keys, rotation, fleet
 * compatibility, randomness, and per-key invocation limits. Soklet continues
 * to own canonical state serialization, size, round, and lifetime checks, plus
 * context binding. Implementations must authenticate the exact associated data
 * supplied in the context and may be invoked concurrently.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpRequestStateProtector {
	/**
	 * Protects canonical framework request-state bytes.
	 *
	 * @param context immutable binding context
	 * @param plaintext call-confined canonical framework bytes; implementations
	 *                  must not retain or mutate this array
	 * @return opaque string suitable for the MCP wire value
	 * @throws McpRequestStateProtectionException on a sanitized protection
	 *                                            failure
	 */
	@NonNull
	String seal(@NonNull McpRequestStateProtectionContext context,
			byte @NonNull [] plaintext)
			throws McpRequestStateProtectionException;

	/**
	 * Opens a framework request-state wire value previously produced by this
	 * protector.
	 *
	 * @param context immutable expected binding context
	 * @param protectedState opaque value received from the client
	 * @return newly allocated canonical framework bytes whose ownership transfers
	 *         to Soklet
	 * @throws McpRequestStateProtectionException with
	 * {@link McpRequestStateProtectionException.Reason#INVALID_STATE} for every
	 * malformation, authentication failure, expiry-equivalent failure, or
	 * context mismatch detected by the protector
	 */
	byte @NonNull [] open(@NonNull McpRequestStateProtectionContext context,
			@NonNull String protectedState)
			throws McpRequestStateProtectionException;
}
