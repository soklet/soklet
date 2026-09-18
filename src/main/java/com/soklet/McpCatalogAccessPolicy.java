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
 * Immutable policy for caller-aware tool and prompt availability.
 * <p>
 * Availability applies to discovery and direct access, not argument-level or
 * object-level authorization. Evaluators receive canonical, untranslated
 * registrations and must support concurrent invocation. The immutable policy
 * does not make application state captured by an evaluator thread-safe.
 * <p>
 * Policies retain reference identity: application-owned evaluator capabilities
 * do not participate in structural equality or diagnostic rendering.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpCatalogAccessPolicy {
	@NonNull
	private static final McpCatalogAccessPolicy ALLOW_ALL =
			new McpCatalogAccessPolicy((requestContext, toolRegistration,
					invocationFeatures) -> true,
					(requestContext, promptRegistration, invocationFeatures) -> true);
	@NonNull
	private final ToolAccessEvaluator toolAccessEvaluator;
	@NonNull
	private final PromptAccessEvaluator promptAccessEvaluator;

	private McpCatalogAccessPolicy(@NonNull ToolAccessEvaluator toolAccessEvaluator,
			@NonNull PromptAccessEvaluator promptAccessEvaluator) {
		this.toolAccessEvaluator = requireNonNull(toolAccessEvaluator);
		this.promptAccessEvaluator = requireNonNull(promptAccessEvaluator);
	}

	/**
	 * Creates a policy from two required application-owned evaluators.
	 *
	 * @param toolAccessEvaluator tool availability evaluator
	 * @param promptAccessEvaluator prompt availability evaluator
	 * @return immutable policy
	 * @throws NullPointerException if either evaluator is null
	 */
	@NonNull
	public static McpCatalogAccessPolicy fromEvaluators(
			@NonNull ToolAccessEvaluator toolAccessEvaluator,
			@NonNull PromptAccessEvaluator promptAccessEvaluator) {
		return new McpCatalogAccessPolicy(toolAccessEvaluator, promptAccessEvaluator);
	}

	/** @return shared policy that permits every registered tool and prompt */
	@NonNull
	public static McpCatalogAccessPolicy allowAllInstance() {
		return ALLOW_ALL;
	}

	boolean isToolAccessible(@NonNull McpRequestContext requestContext,
			@NonNull McpToolRegistration<?> toolRegistration,
			@NonNull McpInvocationFeatures invocationFeatures) throws Exception {
		return requireNonNull(this.toolAccessEvaluator.isToolAccessible(
				requireNonNull(requestContext), requireNonNull(toolRegistration),
				requireNonNull(invocationFeatures)),
				"The MCP tool access evaluator returned null.");
	}

	boolean isPromptAccessible(@NonNull McpRequestContext requestContext,
			@NonNull McpPromptRegistration promptRegistration,
			@NonNull McpInvocationFeatures invocationFeatures) throws Exception {
		return requireNonNull(this.promptAccessEvaluator.isPromptAccessible(
				requireNonNull(requestContext), requireNonNull(promptRegistration),
				requireNonNull(invocationFeatures)),
				"The MCP prompt access evaluator returned null.");
	}

	/** @return diagnostic rendering without application-owned evaluator data */
	@Override
	@NonNull
	public String toString() {
		return "McpCatalogAccessPolicy{evaluators=<redacted>}";
	}

	/**
	 * Concurrently callable tool availability evaluator.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface ToolAccessEvaluator {
		/**
		 * Determines whether a caller may discover or invoke a tool.
		 *
		 * @param requestContext admitted request context
		 * @param toolRegistration canonical, untranslated registration
		 * @param invocationFeatures features scoped to this authorization check
		 * @return true if accessible; never null
		 * @throws Exception if availability cannot be established
		 */
		@NonNull
		Boolean isToolAccessible(@NonNull McpRequestContext requestContext,
				@NonNull McpToolRegistration<?> toolRegistration,
				@NonNull McpInvocationFeatures invocationFeatures) throws Exception;
	}

	/**
	 * Concurrently callable prompt availability evaluator.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface PromptAccessEvaluator {
		/**
		 * Determines whether a caller may discover or access a prompt.
		 *
		 * @param requestContext admitted request context
		 * @param promptRegistration canonical, untranslated registration
		 * @param invocationFeatures features scoped to this authorization check
		 * @return true if accessible; never null
		 * @throws Exception if availability cannot be established
		 */
		@NonNull
		Boolean isPromptAccessible(@NonNull McpRequestContext requestContext,
				@NonNull McpPromptRegistration promptRegistration,
				@NonNull McpInvocationFeatures invocationFeatures) throws Exception;
	}
}
