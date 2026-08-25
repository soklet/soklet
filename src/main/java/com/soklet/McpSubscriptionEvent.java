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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;

/**
 * Immutable application event that identifies a coarse MCP resource change.
 * <p>
 * Events do not identify an endpoint, authorization partition, or connected
 * client. Soklet applies endpoint configuration and each accepted URI filter
 * before wire emission. The authorization partition stored when a subscription
 * is admitted scopes registration and quota accounting; it is not an event
 * target and does not authorize a URI semantically.
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
		return ResourcesListChanged.INSTANCE;
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
	public final class ResourcesListChanged implements McpSubscriptionEvent {
		@NonNull
		private static final ResourcesListChanged INSTANCE =
				new ResourcesListChanged();

		private ResourcesListChanged() {
		}

		/** @return whether the other value is also a list-changed event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof ResourcesListChanged;
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return 0;
		}

		/** @return safe diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "ResourcesListChanged{}";
		}
	}

	/**
	 * Signals that the representation at one absolute normalized resource URI
	 * in ASCII wire form changed.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class ResourceUpdated
			implements McpSubscriptionEvent {
		@NonNull
		private final URI resourceUri;

		/**
		 * Validates this resource-updated event.
		 *
		 * @throws NullPointerException if {@code resourceUri} is null
		 * @throws IllegalArgumentException if the URI is relative, not normalized,
		 *                                  or not in ASCII wire form
		 */
		private ResourceUpdated(@NonNull URI resourceUri) {
			this.resourceUri = McpResourceValueSupport.requireAbsoluteNormalizedUri(
					resourceUri);
		}

		/** @return absolute normalized changed-resource URI in ASCII wire form */
		@NonNull
		public URI getResourceUri() {
			return this.resourceUri;
		}

		/** @return whether this event identifies the same changed resource URI */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof ResourceUpdated updated))
				return false;
			return this.resourceUri.equals(updated.resourceUri);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return this.resourceUri.hashCode();
		}

		/** @return rendering that does not expose the resource URI */
		@Override
		@NonNull
		public final String toString() {
			return "ResourceUpdated{resourceUri=<redacted>}";
		}
	}
}
