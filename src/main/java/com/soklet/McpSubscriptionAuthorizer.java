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
 * Thread-safe application authorization policy for establishing and renewing
 * MCP subscriptions. Soklet may invoke one authorizer concurrently for
 * independent subscriptions, but runs at most one authorization callback at a
 * time for each subscription.
 * <p>
 * Authorization is distinct from initial request admission and catalog-item
 * access. Implementations establish a bounded whole-subscription grant from
 * current application state. A thrown exception or {@code null} result fails
 * closed. Soklet invokes callbacks through bounded application execution,
 * never on an event loop. Invocation features contain the check's cooperative
 * cancellation token; they do not expose task creation, progress reporting, or
 * a localization context computed before authorization completes.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
public interface McpSubscriptionAuthorizer {
	/**
	 * Authorizes one initial, renewal, or reconciliation check.
	 *
	 * @param subscriptionAuthorizationContext immutable authorization context
	 * @param invocationFeatures features scoped to this authorization check;
	 *                           includes its cancellation token
	 * @return a non-null allowed or denied authorization result
	 * @throws Exception if authorization cannot be established; Soklet fails
	 *                   the check closed
	 */
	@NonNull
	McpSubscriptionAuthorization authorize(
			@NonNull McpSubscriptionAuthorizationContext
					subscriptionAuthorizationContext,
			@NonNull McpInvocationFeatures invocationFeatures) throws Exception;

	/**
	 * Returns the shared authorizer that denies every authorization check.
	 *
	 * @return shared deny-all authorizer
	 */
	@NonNull
	static McpSubscriptionAuthorizer denyAllInstance() {
		return DenyAllMcpSubscriptionAuthorizer.INSTANCE;
	}
}

/**
 * Thread-safe deny-all MCP subscription authorizer.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DenyAllMcpSubscriptionAuthorizer
		implements McpSubscriptionAuthorizer {
	@NonNull
	static final DenyAllMcpSubscriptionAuthorizer INSTANCE =
			new DenyAllMcpSubscriptionAuthorizer();

	private DenyAllMcpSubscriptionAuthorizer() {
	}

	@Override
	@NonNull
	public McpSubscriptionAuthorization authorize(
			@NonNull McpSubscriptionAuthorizationContext
					subscriptionAuthorizationContext,
			@NonNull McpInvocationFeatures invocationFeatures) {
		requireNonNull(subscriptionAuthorizationContext);
		requireNonNull(invocationFeatures);
		return McpSubscriptionAuthorization.deniedInstance();
	}
}
