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
 * Resolves the stable application owner of a freshly admitted MCP session use.
 * Applies only to endpoints explicitly enabling sessions for MCP
 * {@code 2025-06-18} or {@code 2025-11-25}; Soklet's {@code 2026-07-28}
 * implementation does not use this callback.
 * <p>
 * Implementations must be fast, local, nonblocking, and safe for concurrent
 * invocation. Soklet invokes the resolver inside the operation's bounded
 * application execution after fresh admission. Remote authorization belongs
 * in the admission controller, not this mapping.
 * <p>
 * The key must distinguish subjects, including issuer and tenant where
 * relevant. A principal's equality, an authorization partition, or a rate-limit
 * partition need not identify one subject. Keys are never emitted in Soklet's
 * responses, logs, or metric labels. Anonymous owners are resolved only when
 * explicitly enabled in {@link McpSessionConfig}, under a separate internal
 * namespace. A constant anonymous key deliberately shares one owner quota;
 * generating a new key per initialization must not bypass that quota.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpSessionOwnerKeyResolver {
	/**
	 * Resolves one opaque, stable owner key from the current admitted identity.
	 * The key must be nonblank and at most 256 UTF-8 bytes. Null, invalid values,
	 * failure, or timeout fail closed.
	 *
	 * @param admissionIdentity freshly admitted identity
	 * @return stable, nonblank opaque owner key
	 * @throws Exception if an owner cannot be established
	 */
	@NonNull
	String resolve(@NonNull McpAdmissionIdentity admissionIdentity) throws Exception;
}
