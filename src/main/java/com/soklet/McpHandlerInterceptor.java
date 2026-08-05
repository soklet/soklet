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

import static java.util.Objects.requireNonNull;

/**
 * Thread-safe server-level interceptor for every application-owned MCP
 * handler invocation.
 * <p>
 * Soklet invokes the configured interceptor for tool calls, prompt gets,
 * resource reads, and custom resource-list handling, including handlers
 * discovered from annotations. Framework-owned discovery and static catalog
 * generation do not invoke application handlers and therefore do not traverse
 * this hook.
 * <p>
 * Interception occurs after admission, rate limiting, handler-queue admission,
 * and handler-slot acquisition, but before complete application-input
 * validation and handler invocation. Applications that need an interceptor
 * chain compose it behind this one hook and own its ordering.
 * <p>
 * An interceptor may invoke the continuation and transform its result, or may
 * short-circuit by returning a method-compatible result without invoking it.
 * Either result still traverses Soklet's applicable result validation,
 * tool-output sanitization, output-schema validation, and response generation.
 * A {@code null} return or thrown exception fails closed without exposing
 * exception-derived data.
 * <p>
 * Implementations must be safe for concurrent invocation. Each supplied
 * continuation remains synchronous, one-shot, and bound to the invoking
 * thread as documented by {@link McpHandlerInvocation}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpHandlerInterceptor {
	/**
	 * Intercepts one application-owned MCP handler invocation.
	 *
	 * @param context immutable request context
	 * @param invocation synchronous one-shot downstream continuation
	 * @return recognized, non-null, method-compatible handler result
	 * @throws Exception if application interception fails
	 */
	@NonNull
	McpOperationResult interceptHandler(@NonNull McpRequestContext context,
			@NonNull McpHandlerInvocation invocation) throws Exception;

	/**
	 * Returns the shared interceptor that invokes the downstream continuation
	 * without transforming its result.
	 *
	 * @return shared pass-through interceptor
	 */
	@NonNull
	static McpHandlerInterceptor defaultInstance() {
		return DefaultMcpHandlerInterceptor.INSTANCE;
	}
}

/**
 * Thread-safe pass-through MCP handler interceptor.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpHandlerInterceptor implements McpHandlerInterceptor {
	@NonNull
	static final DefaultMcpHandlerInterceptor INSTANCE =
			new DefaultMcpHandlerInterceptor();

	private DefaultMcpHandlerInterceptor() {
	}

	@Override
	@NonNull
	public McpOperationResult interceptHandler(
			@NonNull McpRequestContext context,
			@NonNull McpHandlerInvocation invocation) throws Exception {
		requireNonNull(context);
		return requireNonNull(requireNonNull(invocation).invoke(),
				"The MCP handler invocation returned null.");
	}
}
