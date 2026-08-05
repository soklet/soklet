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
 * Immutable context supplied to an MCP rate limiter.
 * <p>
 * Implementations may combine the normalized endpoint path, the accepted
 * identity's stable partition key, and the target to select local or
 * distributed rate-limit state. Client-reported identity information is not
 * a trusted partition key.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpRateLimitContext {
	/**
	 * Returns the original Soklet request.
	 *
	 * @return request
	 */
	@NonNull
	Request getRequest();

	/**
	 * Returns the resolved MCP endpoint.
	 *
	 * @return endpoint
	 */
	@NonNull
	McpEndpoint getEndpoint();

	/**
	 * Returns the identity accepted by request admission.
	 *
	 * @return admitted identity
	 */
	@NonNull
	McpAdmissionIdentity getAdmissionIdentity();

	/**
	 * Returns the rate-limit stage requesting this acquisition.
	 *
	 * @return request or tool target
	 */
	@NonNull
	McpRateLimitTarget getTarget();

	/**
	 * Returns the JSON-RPC method.
	 *
	 * @return JSON-RPC method
	 */
	@NonNull
	String getJsonRpcMethod();

	/**
	 * Returns the resolved operation name, such as a tool name, when present.
	 *
	 * @return operation name, or the empty optional
	 */
	@NonNull
	Optional<@NonNull String> getOperationName();
}
