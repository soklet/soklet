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
 * Request-scoped MCP metadata.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpRequestContext {
	/**
	 * Provides the transport request being handled.
	 *
	 * @return the current request
	 */
	@NonNull
	Request getRequest();

	/**
	 * Provides the resolved endpoint class for the current request.
	 *
	 * @return the endpoint class
	 */
	@NonNull
	Class<? extends McpEndpoint> getEndpointClass();

	/**
	 * Provides the JSON-RPC method name.
	 *
	 * @return the JSON-RPC method
	 */
	@NonNull
	String getJsonRpcMethod();

	/**
	 * Provides the high-level MCP operation kind.
	 *
	 * @return the operation kind
	 */
	@NonNull
	McpOperationKind getOperationKind();

	/**
	 * Provides the JSON-RPC request ID for request-style operations.
	 *
	 * @return the request ID, if available
	 */
	@NonNull
	Optional<McpJsonRpcRequestId> getJsonRpcRequestId();

	/**
	 * Provides the MCP session ID associated with the request.
	 *
	 * @return the session ID, if available
	 */
	@NonNull
	Optional<String> getSessionId();

	/**
	 * Provides the negotiated protocol version for the request's session.
	 *
	 * @return the negotiated protocol version, if available
	 */
	@NonNull
	Optional<String> getProtocolVersion();

	/**
	 * Provides the negotiated server capabilities for the request's session.
	 *
	 * @return the negotiated capabilities, if available
	 */
	@NonNull
	Optional<McpNegotiatedCapabilities> getNegotiatedCapabilities();

	/**
	 * Provides the current session context for the request's session.
	 *
	 * @return the session context, if available
	 */
	@NonNull
	Optional<McpSessionContext> getSessionContext();
}
