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
 * Application-owned argument completer for a prompt or resource URI template.
 *
 * <p>The handler must authorize every suggestion it returns for the admitted
 * request identity. Context arguments and partial values are untrusted input.
 * Implementations must be safe for concurrent invocation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpCompletionHandler {
	/**
	 * Supplies ordered suggestions for one partial argument value.
	 *
	 * @param requestContext admitted request metadata
	 * @param completionContext partial argument and canonical registration
	 * @param invocationFeatures invocation-scoped optional features
	 * @return non-null suggestions result
	 * @throws Exception if application handling fails
	 */
	@NonNull
	McpArgumentCompletionResult handle(
			@NonNull McpRequestContext requestContext,
			@NonNull McpCompletionContext completionContext,
			@NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
