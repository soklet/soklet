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

import javax.annotation.concurrent.ThreadSafe;

/**
 * Thread-safe server-local control for reconciling MCP subscription
 * authorization after application-owned invalidation or recovery.
 * <p>
 * Reconciliation includes active and establishing subscriptions, independently
 * of their notification families or localization support. It fences delivery
 * based on prior authorization and schedules fresh checks, or safely closes a
 * stream when scheduling fails. It does not provide a distributed subscription
 * registry, migrate streams, or recall bytes already handed to a transport.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSubscriptionReconciler {
	/**
	 * Invalidates local authorization generations and schedules fresh checks for
	 * establishing and active subscriptions. With no subscriptions this is a
	 * harmless no-op.
	 * <p>
	 * Returning confirms local delivery fencing and scheduling acceptance, not
	 * completion of authorization callbacks, replica catch-up, client delivery,
	 * or recall of bytes already written.
	 */
	void reconcileSubscriptions();
}
