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
 * Thread-safe listener registration returned by an MCP subscription-event
 * publisher.
 * <p>
 * Closing a registration is idempotent. It unsubscribes only its listener and
 * does not close the application-owned publisher.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSubscriptionEventSubscription extends AutoCloseable {
	/** Idempotently unregisters the associated listener. */
	@Override
	void close();
}
