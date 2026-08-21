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
import java.util.Map;

/**
 * Immutable routed input for one MCP resource-read invocation.
 *
 * <p>The URI and template variables remain application input. Routing does not
 * authorize the current principal, establish filesystem containment, or say
 * that the URI is safe for direct client loading. A handler must apply its own
 * delivery-intent URI allowlist and, for filesystem-backed resources,
 * canonicalize and contain the target before authorization and opening.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpResourceReadContext {
	/**
	 * Returns the exact routed resource URI supplied by the client.
	 *
	 * @return exact resource URI; not an authorization or dereference decision
	 */
	@NonNull
	URI getUri();

	/**
	 * Returns URI-template variables captured while routing this resource.
	 *
	 * @return immutable variable map, empty for an exact-URI registration
	 */
	@NonNull
	Map<@NonNull String, @NonNull String> getUriTemplateVariables();
}
