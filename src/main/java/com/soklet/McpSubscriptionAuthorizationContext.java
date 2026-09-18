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
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable context for one bounded MCP subscription authorization check.
 * <p>
 * The initial request context is historical admission evidence and never
 * becomes current authority. The application context reflects initial
 * admission for the first check and the latest successful authorization result
 * thereafter. Candidate notification families and targets describe the initial
 * request before acknowledgement; renewal checks describe the acknowledged
 * subscription and cannot expand it.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSubscriptionAuthorizationContext {
	/**
	 * Returns the immutable request context from original subscription admission.
	 *
	 * @return original admission request context
	 */
	@NonNull
	McpRequestContext getInitialRequestContext();

	/**
	 * Returns the current application authorization context. Absence is a valid
	 * replacement and does not retain an older context implicitly.
	 *
	 * @return current application context, when present
	 */
	@NonNull
	Optional<@NonNull Object> getApplicationContext();

	/**
	 * Returns the previous effective, server-capped authorization expiration.
	 * This is empty for the initial check and is historical information during
	 * reconciliation, not proof of a currently active grant.
	 *
	 * @return previous effective expiration, when one exists
	 */
	@NonNull
	Optional<@NonNull Instant> getPreviousValidUntil();

	/**
	 * Returns the fixed wall-clock representation of this check's queue-inclusive
	 * deadline. This is neither credential expiry nor the maximum validity an
	 * authorizer may request.
	 *
	 * @return this authorization check's deadline
	 */
	@NonNull
	Instant getDeadline();

	/** @return whether tool-list-change delivery is included */
	@NonNull
	Boolean isToolsListChangedIncluded();

	/** @return whether prompt-list-change delivery is included */
	@NonNull
	Boolean isPromptsListChangedIncluded();

	/** @return whether resource-list-change delivery is included */
	@NonNull
	Boolean isResourcesListChangedIncluded();

	/**
	 * Returns an immutable set of unique resource-subscription URIs. Membership,
	 * not iteration order, is semantically significant.
	 *
	 * @return immutable resource-subscription URI set, possibly empty
	 */
	@NonNull
	Set<@NonNull URI> getResourceSubscriptionUris();

	/**
	 * Returns an immutable set of unique task IDs. IDs identify tasks rather than
	 * subscription handles; membership, not iteration order, is semantically
	 * significant.
	 *
	 * @return immutable task-ID set, possibly empty
	 */
	@NonNull
	Set<@NonNull String> getTaskIds();
}
