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
 * Context supplied to {@link McpResponseMarshaler} during structured-content conversion.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpStructuredContentContext {
	/**
	 * Provides the endpoint class that produced the structured content.
	 *
	 * @return the endpoint class
	 */
	@NonNull
	Class<? extends McpEndpoint> getEndpointClass();

	/**
	 * Provides the tool name whose structured content is being marshaled.
	 *
	 * @return the tool name
	 */
	@NonNull
	String getToolName();

	/**
	 * Provides the tool-call context for the current structured content marshaling operation.
	 *
	 * @return the tool-call context
	 */
	@NonNull
	McpToolCallContext getToolCallContext();

	/**
	 * Provides the session context active for the tool call.
	 *
	 * @return the session context
	 */
	@NonNull
	McpSessionContext getSessionContext();
}
