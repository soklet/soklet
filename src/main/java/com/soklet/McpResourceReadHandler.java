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
 * Programmatic MCP resource-read handler.
 *
 * <p>Routing proves neither authorization nor safe dereference. An application
 * that maps a resource URI to a filesystem must canonicalize its configured
 * root and requested target, reject targets outside that root after symlink
 * resolution, authorize the canonical target under the current admitted
 * identity, and address deployment-specific races between validation and
 * opening. Soklet provides no filesystem mapper or containment guarantee.
 *
 * <p>The application must also define URI policy by delivery intent. A URI
 * permitted for direct client loading may need a different scheme, authority,
 * credential, query, and fragment policy from an application-handler-only
 * URI. Soklet's URI syntax validation and routing do not establish that a URI
 * is safe or consumable for either purpose.
 *
 * <p>Handlers may complete with
 * {@link McpCompleteResult#fromResourceOutput(McpResourceOutput)} or return a
 * declared multi-round-trip result. Implementations must be safe for
 * concurrent invocation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpResourceReadHandler {
	/**
	 * Reads one routed resource under application authorization and policy.
	 *
	 * @param request request metadata
	 * @param resource resolved resource URI and template variables
	 * @param features invocation-scoped optional features
	 * @return recognized non-null operation result
	 * @throws Exception if application handling fails
	 */
	@NonNull
	McpOperationResult handle(@NonNull McpRequestContext request,
			@NonNull McpResourceReadContext resource,
			@NonNull McpInvocationFeatures features) throws Exception;
}
