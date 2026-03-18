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
 * Transport/admission context for MCP request rejection or acceptance.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpAdmissionContext {
	/**
	 * Provides the transport request being evaluated for admission.
	 *
	 * @return the current request
	 */
	@NonNull
	Request getRequest();

	/**
	 * Provides the HTTP method for the MCP transport request.
	 *
	 * @return the transport HTTP method
	 */
	@NonNull
	HttpMethod getHttpMethod();

	/**
	 * Provides the resolved MCP endpoint class.
	 *
	 * @return the target endpoint class
	 */
	@NonNull
	Class<? extends McpEndpoint> getEndpointClass();

	/**
	 * Provides the JSON-RPC method name when the request is a parsed MCP {@code POST}.
	 *
	 * @return the JSON-RPC method, if available
	 */
	@NonNull
	Optional<String> getJsonRpcMethod();

	/**
	 * Provides the high-level operation type when the request maps to a known MCP operation.
	 *
	 * @return the MCP operation type, if available
	 */
	@NonNull
	Optional<McpOperationType> getOperationType();

	/**
	 * Provides the parsed JSON-RPC request ID for request-style MCP operations.
	 *
	 * @return the request ID, if available
	 */
	@NonNull
	Optional<McpJsonRpcRequestId> getJsonRpcRequestId();

	/**
	 * Provides the requested MCP session ID when one is present on the transport request.
	 *
	 * @return the session ID, if available
	 */
	@NonNull
	Optional<String> getSessionId();
}
