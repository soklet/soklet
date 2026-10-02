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
 * Fresh HTTP admission and authorization for GET delivery and DELETE cleanup
 * at session-enabled {@code 2025-06-18} and {@code 2025-11-25} endpoints.
 * Soklet's {@code 2026-07-28} implementation does not use this controller.
 * <p>
 * Implementations must support concurrent invocation and evaluate current
 * authority on every call, including reauthorization using the original GET
 * request. The accepted identity is freshly owner-verified. GET permission
 * alone does not grant permission to receive updates for any resource URI.
 * <p>
 * The features provide cooperative cancellation for deadlines, disconnect,
 * reconciliation and shutdown. Progress reporting and task creation are
 * unavailable. Keep callbacks bounded and respond promptly to cancellation;
 * physical execution remains reserved until an uncooperative callback exits.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpSessionTransportAdmissionController {
	/**
	 * Evaluates current HTTP authority and returns a bounded authorization
	 * expiry or a safe HTTP rejection.
	 *
	 * @param sessionTransportAdmissionContext immutable HTTP operation context
	 * @param invocationFeatures features for this bounded callback
	 * @return accepted identity, expiry and selected families, or a rejection
	 * @throws Exception if application admission fails
	 */
	@NonNull
	McpSessionTransportAdmissionDecision admit(
			@NonNull McpSessionTransportAdmissionContext sessionTransportAdmissionContext,
			@NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
