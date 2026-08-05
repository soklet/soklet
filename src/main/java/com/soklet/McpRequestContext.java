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
import java.util.Map;
import java.util.Optional;

/**
 * Immutable request metadata supplied to MCP application handlers.
 *
 * <p>Invocation-specific optional behavior is exposed through
 * {@link McpInvocationFeatures}, not by adding optional members to this
 * context.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpRequestContext {
	/** @return originating immutable Soklet request */
	@NonNull
	Request getRequest();

	/** @return endpoint selected for this request */
	@NonNull
	McpEndpoint getEndpoint();

	/** @return immutable endpoint path parameters */
	@NonNull
	Map<@NonNull String, @NonNull String> getEndpointPathParameters();

	/** @return JSON-RPC method name */
	@NonNull
	String getJsonRpcMethod();

	/** @return request identifier, or empty for a notification */
	@NonNull
	Optional<@NonNull McpRequestId> getRequestId();

	/** @return negotiated MCP protocol version */
	@NonNull
	String getProtocolVersion();

	/**
	 * Returns client implementation metadata supplied during initialization.
	 * This value is informational and is not an authenticated identity.
	 *
	 * @return client implementation metadata, if supplied
	 */
	@NonNull
	Optional<@NonNull McpImplementation> getClientInfo();

	/** @return immutable negotiated client capabilities */
	@NonNull
	McpClientCapabilities getClientCapabilities();

	/** @return immutable request {@code _meta} object */
	@NonNull
	McpJsonObject getRequestMetadata();

	/**
	 * Returns the protocol's deprecated per-request log-level metadata.
	 *
	 * <p>The accessor itself is not deprecated: the wire value type carries
	 * the deprecation, and exposing it does not mean that Soklet advertises
	 * MCP Logging.
	 *
	 * @return deprecated log level, if supplied
	 */
	@SuppressWarnings("deprecation")
	@NonNull
	Optional<@NonNull McpLogLevel> getDeprecatedLogLevel();

	/**
	 * Returns the validated distributed trace context supplied through MCP
	 * request metadata. This value does not fall back to HTTP trace headers;
	 * those remain independently available from {@link #getRequest()}.
	 *
	 * @return validated MCP distributed trace context, if supplied
	 */
	@NonNull
	Optional<@NonNull TraceContext> getTraceContext();

	/**
	 * Returns validated W3C baggage entries supplied through MCP request
	 * metadata, in first-valid-occurrence order. Values are percent-decoded,
	 * the first valid duplicate key wins, and validated baggage properties are
	 * not exposed by this map. The original metadata remains available through
	 * {@link #getRequestMetadata()}.
	 *
	 * @return immutable validated baggage entries
	 */
	@NonNull
	Map<@NonNull String, @NonNull String> getBaggage();

	/** @return identity accepted by the request-admission policy */
	@NonNull
	McpAdmissionIdentity getAdmissionIdentity();
}
