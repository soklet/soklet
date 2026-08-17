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

import static java.util.Objects.requireNonNull;

/**
 * Thread-safe broadcast publisher for application-owned MCP resource-change
 * events.
 * <p>
 * Every event must be made available to every current listener; a
 * competing-consumer queue is not a valid implementation. Implementations may
 * perform process-local delivery or coordinate through a distributed service.
 * The application owns the publisher. Soklet closes only registrations
 * returned by {@link #subscribe(McpSubscriptionEventListener)} and never closes
 * the publisher itself.
 * <p>
 * This SPI distributes application change events only. Soklet owns all MCP
 * stream-delivery mechanics: per-stream queues, duplicate-event coalescing,
 * backpressure, subscription filtering and authorization, and MCP wire
 * serialization. Publisher implementations must not attempt to perform those
 * functions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSubscriptionEventPublisher {
	/**
	 * Registers a listener for broadcast events.
	 *
	 * @param listener thread-safe listener
	 * @return an idempotently closable listener registration
	 */
	@NonNull
	McpSubscriptionEventRegistration subscribe(
			@NonNull McpSubscriptionEventListener listener);

	/**
	 * Broadcasts a coarse resource-change event.
	 *
	 * @param event resource-change event
	 */
	void publish(@NonNull McpSubscriptionEvent event);

	/** Broadcasts a resource-list-changed event. */
	default void publishResourcesListChanged() {
		publish(McpSubscriptionEvent.resourcesListChanged());
	}

	/**
	 * Broadcasts a resource-updated event.
	 *
	 * @param resourceUri absolute normalized changed-resource URI in ASCII wire
	 *                    form
	 * @throws NullPointerException if {@code resourceUri} is null
	 * @throws IllegalArgumentException if the URI is relative, not normalized,
	 *                                  or not in ASCII wire form
	 */
	default void publishResourceUpdated(@NonNull URI resourceUri) {
		publish(McpSubscriptionEvent.resourceUpdated(
				requireNonNull(resourceUri)));
	}
}
