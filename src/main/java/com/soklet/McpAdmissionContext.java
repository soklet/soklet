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
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, bounded context supplied to an MCP admission controller.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpAdmissionContext {
	/** @return the original immutable Soklet HTTP request */
	@NonNull Request getRequest();

	/** @return the selected MCP endpoint */
	@NonNull McpEndpoint getEndpoint();

	/** @return immutable endpoint-path parameters, empty for a fixed path */
	@NonNull Map<@NonNull String, @NonNull String> getEndpointPathParameters();

	/** @return the validated JSON-RPC method */
	@NonNull String getJsonRpcMethod();

	/** @return whether the message is a JSON-RPC notification */
	@NonNull Boolean isNotification();

	/** @return the request ID, empty for a notification */
	@NonNull Optional<@NonNull McpRequestId> getRequestId();

	/** @return the validated MCP protocol version */
	@NonNull String getProtocolVersion();

	/** @return the selected tool, prompt, or resource name, when applicable */
	@NonNull Optional<@NonNull String> getOperationName();

	/** @return informational client implementation metadata, when supplied */
	@NonNull Optional<@NonNull McpImplementation> getClientInfo();

	/** @return validated client capabilities, when the message carries them */
	@NonNull Optional<@NonNull McpClientCapabilities> getClientCapabilities();

	/**
	 * Returns the validated, deduplicated resource URIs requested by a
	 * {@code subscriptions/listen} message, in first-encounter order. The list
	 * is empty for every other method and when the subscription request does not
	 * include resource subscriptions.
	 *
	 * @return immutable requested resource-subscription URI list
	 */
	@NonNull
	List<@NonNull URI> getRequestedResourceSubscriptionUris();

	/**
	 * Returns the validated distributed trace context supplied through MCP
	 * request metadata. This value does not fall back to HTTP trace headers;
	 * those remain independently available from {@link #getRequest()}.
	 *
	 * @return validated MCP distributed trace context, when supplied
	 */
	@NonNull Optional<@NonNull TraceContext> getTraceContext();
}
