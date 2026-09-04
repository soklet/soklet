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
 * Programmatic handler for a tool that always completes with a structured
 * Java result.
 *
 * <p>Soklet derives the output schema and intrinsic conversion plan from the
 * result type declared by {@link McpToolRegistration.ArgumentTypeStage#argumentAndOutputTypes(
 * Class, Class)}. A bare {@link String} result type is rejected; return prose
 * through an advanced {@link McpToolHandler} and
 * {@link McpCompleteResult#fromToolText(String)} instead.
 *
 * <p>Implementations must be safe for concurrent invocation.
 *
 * @param <A> bound argument type
 * @param <R> structured result type
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpCompleteToolHandler<A, R> {
	/**
	 * Handles one tool invocation.
	 *
	 * @param request request metadata
	 * @param arguments converted and raw tool arguments
	 * @param features invocation-scoped optional features
	 * @return non-null structured result
	 * @throws Exception if application handling fails
	 */
	@NonNull
	R handle(@NonNull McpRequestContext request,
			@NonNull McpToolArguments<A> arguments,
			@NonNull McpInvocationFeatures features) throws Exception;
}
