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
 * Programmatic MCP prompt handler.
 *
 * <p>Structural validation does not make prompt arguments semantically safe.
 * The handler owns deployment-specific business allowlists, input and output
 * classification, prompt-injection defenses, and authorization under the
 * current admitted identity. It must authorize before reading a referenced
 * resource and should collapse rejected input, authorization failure, and
 * unavailable resources to a neutral client-visible failure that does not
 * disclose protected values. Soklet supplies no universal injection detector
 * or application resource authorizer.
 *
 * <p>Handlers may complete with
 * {@link McpCompleteResult#fromPromptOutput(McpPromptOutput)} or return a
 * declared multi-round-trip result. Implementations must be safe for
 * concurrent invocation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpPromptHandler {
	/**
	 * Handles one structurally validated prompt invocation.
	 *
	 * @param request request metadata
	 * @param prompt supplied prompt arguments
	 * @param features invocation-scoped optional features
	 * @return recognized non-null operation result
	 * @throws Exception if application handling fails
	 */
	@NonNull
	McpOperationResult handle(@NonNull McpRequestContext request,
			@NonNull McpPromptGetContext prompt,
			@NonNull McpInvocationFeatures features) throws Exception;
}
