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

/**
 * Immutable application event that identifies a coarse MCP resource change.
 * <p>
 * Events do not identify an endpoint, authorization partition, or connected
 * client. Soklet retains responsibility for applying endpoint configuration,
 * client-requested filters, and authorization isolation before wire emission.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpSubscriptionEvent
		permits McpSubscriptionEvent.ResourcesListChanged,
		McpSubscriptionEvent.ResourceUpdated {
	/**
	 * Creates a resource-list-changed event.
	 *
	 * @return resource-list-changed event
	 */
	@NonNull
	static ResourcesListChanged resourcesListChanged() {
		return new ResourcesListChanged();
	}

	/**
	 * Creates a resource-updated event.
	 *
	 * @param resourceUri changed resource URI
	 * @return resource-updated event
	 * @throws NullPointerException if {@code resourceUri} is null
	 * @throws IllegalArgumentException if the URI is relative, not normalized,
	 *                                  or not in ASCII wire form
	 */
	@NonNull
	static ResourceUpdated resourceUpdated(@NonNull URI resourceUri) {
		return new ResourceUpdated(resourceUri);
	}

	/**
	 * Signals that clients should request {@code resources/list} again.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ResourcesListChanged() implements McpSubscriptionEvent {
	}

	/**
	 * Signals that the representation at one resource URI changed.
	 *
	 * @param resourceUri absolute normalized changed-resource URI in ASCII wire
	 *                    form
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record ResourceUpdated(@NonNull URI resourceUri)
			implements McpSubscriptionEvent {
		/**
		 * Validates this resource-updated event.
		 *
		 * @throws NullPointerException if {@code resourceUri} is null
		 * @throws IllegalArgumentException if the URI is relative, not normalized,
		 *                                  or not in ASCII wire form
		 */
		public ResourceUpdated {
			resourceUri = McpResourceValueSupport.requireAbsoluteNormalizedUri(
					resourceUri);
		}
	}
}
