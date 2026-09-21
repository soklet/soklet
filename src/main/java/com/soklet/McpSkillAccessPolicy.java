/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
 * Immutable policy separating Skills access from discovery.
 *
 * <p>Discovery checks access first and invokes the discovery evaluator only for
 * accessible registrations. An accessible but undiscoverable registration is
 * omitted from listing but remains eligible for direct access. Direct skill
 * lookup and file reads freshly evaluate access; an earlier list is not a grant.
 * Denial supplies no grant through that registration, rather than vetoing a
 * shared file independently granted through another accessible owner.
 *
 * <p>Evaluators receive canonical registrations and must support concurrent
 * invocation. The immutable policy does not make captured application state
 * thread-safe. Callback failures and null results fail closed; no partially
 * authorized result is published. Policies retain reference identity and do not
 * inspect evaluator capabilities for equality or diagnostic rendering.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSkillAccessPolicy {
	@NonNull
	private static final McpSkillAccessPolicy ALLOW_ALL = new McpSkillAccessPolicy(
			(requestContext, skillRegistration, invocationFeatures) -> true,
			(requestContext, skillRegistration, invocationFeatures) -> true);
	@NonNull
	private final AccessEvaluator accessEvaluator;
	@NonNull
	private final DiscoveryEvaluator discoveryEvaluator;

	private McpSkillAccessPolicy(@NonNull AccessEvaluator accessEvaluator,
			@NonNull DiscoveryEvaluator discoveryEvaluator) {
		this.accessEvaluator = requireNonNull(accessEvaluator);
		this.discoveryEvaluator = requireNonNull(discoveryEvaluator);
	}

	/**
	 * Creates a policy from two required application-owned evaluators.
	 *
	 * @param accessEvaluator registration access evaluator
	 * @param discoveryEvaluator listing eligibility evaluator
	 * @return immutable policy
	 * @throws NullPointerException if either evaluator is null
	 */
	@NonNull
	public static McpSkillAccessPolicy fromEvaluators(@NonNull AccessEvaluator accessEvaluator,
			@NonNull DiscoveryEvaluator discoveryEvaluator) {
		return new McpSkillAccessPolicy(accessEvaluator, discoveryEvaluator);
	}

	/** @return shared policy permitting access to and discovery of every registered skill */
	@NonNull
	public static McpSkillAccessPolicy allowAllInstance() {
		return ALLOW_ALL;
	}

	boolean isSkillAccessible(@NonNull McpRequestContext requestContext,
			@NonNull McpSkillRegistration skillRegistration,
			@NonNull McpInvocationFeatures invocationFeatures) throws Exception {
		return requireNonNull(this.accessEvaluator.isSkillAccessible(
				requireNonNull(requestContext), requireNonNull(skillRegistration),
				requireNonNull(invocationFeatures)),
				"The MCP skill access evaluator returned null.");
	}

	boolean isSkillDiscoverable(@NonNull McpRequestContext requestContext,
			@NonNull McpSkillRegistration skillRegistration,
			@NonNull McpInvocationFeatures invocationFeatures) throws Exception {
		return requireNonNull(this.discoveryEvaluator.isSkillDiscoverable(
				requireNonNull(requestContext), requireNonNull(skillRegistration),
				requireNonNull(invocationFeatures)),
				"The MCP skill discovery evaluator returned null.");
	}

	/** @return diagnostic rendering without application-owned evaluator data */
	@Override
	@NonNull
	public String toString() {
		return "McpSkillAccessPolicy{evaluators=<redacted>}";
	}

	/**
	 * Concurrently callable registration access evaluator.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface AccessEvaluator {
		/**
		 * Determines whether this registration may grant the caller access.
		 *
		 * @param requestContext admitted request context
		 * @param skillRegistration canonical registration being checked
		 * @param invocationFeatures features scoped to this authorization check
		 * @return true if accessible; never null
		 * @throws Exception if access cannot be established
		 */
		@NonNull
		Boolean isSkillAccessible(@NonNull McpRequestContext requestContext,
				@NonNull McpSkillRegistration skillRegistration,
				@NonNull McpInvocationFeatures invocationFeatures) throws Exception;
	}

	/**
	 * Concurrently callable discovery evaluator for accessible registrations.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface DiscoveryEvaluator {
		/**
		 * Determines whether an accessible registration is a listing candidate.
		 * Discovery does not independently grant access to a skill or its files.
		 *
		 * @param requestContext admitted request context
		 * @param skillRegistration canonical accessible registration
		 * @param invocationFeatures features scoped to this discovery check
		 * @return true if discoverable; never null
		 * @throws Exception if discovery eligibility cannot be established
		 */
		@NonNull
		Boolean isSkillDiscoverable(@NonNull McpRequestContext requestContext,
				@NonNull McpSkillRegistration skillRegistration,
				@NonNull McpInvocationFeatures invocationFeatures) throws Exception;
	}
}
