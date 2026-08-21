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
 * Immutable metadata for one admitted semantic MCP request or notification.
 * <p>
 * Soklet creates this context after structural and semantic validation and
 * request admission. The same instance is supplied to MCP lifecycle callbacks
 * and, when application handling is required, to the selected handler. This
 * includes framework-owned operations such as discovery and static catalogs,
 * even though those operations do not invoke an application handler.
 *
 * <p>Each request is independently admitted. Connection reuse does not carry
 * application identity or continuation state into a later request; a durable
 * application continuation needs an explicit handle on every retry and an
 * application-owned repository that binds the handle to the current admitted
 * security context.
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

	/** @return validated MCP protocol version supplied on this request */
	@NonNull
	String getProtocolVersion();

	/**
	 * Returns the selected application operation: a tool name, prompt name, or
	 * requested resource URI. Custom resource-list handling has no narrower
	 * operation name.
	 *
	 * <p>This value is useful for application observation but is not safe as an
	 * unbounded built-in metric label.</p>
	 *
	 * @return selected operation name, or the empty optional
	 */
	@NonNull
	Optional<@NonNull String> getOperationName();

	/**
	 * Returns client implementation metadata supplied on this request.
	 * This value is informational and is not an authenticated identity.
	 *
	 * @return client implementation metadata, if supplied
	 */
	@NonNull
	Optional<@NonNull McpImplementation> getClientInfo();

	/** @return immutable client capabilities validated from this request */
	@NonNull
	McpClientCapabilities getClientCapabilities();

	/** @return immutable request {@code _meta} object */
	@NonNull
	McpJsonObject getRequestMetadata();

	/**
	 * Returns client responses supplied with a multi-round-trip retry.
	 *
	 * <p>The default preserves compatibility for request-context
	 * implementations that do not yet supply multi-round-trip data.
	 *
	 * @return immutable input responses, empty for an initial request
	 */
	@NonNull
	default McpInputResponses getInputResponses() {
		return McpInputResponses.emptyInstance();
	}

	/**
	 * Returns verified framework-protected JSON state supplied with a
	 * multi-round-trip retry.
	 *
	 * <p>The default preserves compatibility for request-context
	 * implementations that do not yet supply multi-round-trip data.
	 *
	 * @return verified framework-protected state, or empty when absent
	 */
	@NonNull
	default Optional<@NonNull McpJsonValue> getFrameworkRequestState() {
		return Optional.empty();
	}

	/**
	 * Returns application-protected opaque state supplied with a
	 * multi-round-trip retry.
	 *
	 * <p>Soklet preserves the exact validated string but does not resolve it.
	 * The application owns any durable repository, confidentiality, integrity,
	 * expiry, duplicate-use policy, atomic rotation, and binding to the current
	 * admission identity and authorization context. The same requirements
	 * apply when the retry arrives over a new connection or server instance.
	 *
	 * <p>The default preserves compatibility for request-context
	 * implementations that do not yet supply multi-round-trip data.
	 *
	 * @return application-protected state, or empty when absent
	 */
	@NonNull
	default Optional<@NonNull String> getApplicationRequestState() {
		return Optional.empty();
	}

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

	/** @return identity accepted by the admission controller */
	@NonNull
	McpAdmissionIdentity getAdmissionIdentity();
}
