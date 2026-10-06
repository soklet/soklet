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
 * <p>
 * For an established {@code 2026-07-28} listen, denial, expiry or a failed
 * required check fences delivery and ends the subscription. A usable stream
 * receives its tagged completion result before closing; diagnostics retain
 * the authorization reason. A broken writer or partially written revoked
 * frame may close abruptly. Initial denial still rejects before acknowledgment.
 *
 * <p>For exact 2025-era delivery, the authorizer establishes and renews one
 * session-owned resource URI grant through a real {@code resources/subscribe}
 * context. That grant is independent of an individual GET connection and
 * survives its disconnect within configured bounds. GET notification-family
 * admission is controlled separately by {@link McpSessionTransportAdmissionController}.
 * Unsubscribe does not invoke this authorizer.
 * A transient renewal exception or timeout fences delivery and permits at most
 * three consecutive failed attempts within the existing lease. Successful
 * authorization resets this retry count. Explicit renewal denial, exhausted
 * retries, or an expired established grant retires its session: the client must
 * reinitialize and resubscribe. Legacy protocols have no subscription-ended
 * notification. Initial subscribe rejection and explicit unsubscribe do not
 * themselves retire a session.
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
