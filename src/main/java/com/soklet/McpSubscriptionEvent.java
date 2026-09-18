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
 * Immutable application event that identifies a coarse MCP subscription-visible
 * change.
 * <p>
 * Events do not identify an endpoint, authorization partition, or connected
 * client. Soklet applies endpoint configuration and accepted subscription
 * filters before wire emission. The authorization partition stored when a
 * subscription is admitted scopes registration and quota accounting; it is not
 * an event target and does not authorize catalog or resource access.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpSubscriptionEvent
		permits McpSubscriptionEvent.ResourcesListChanged,
		McpSubscriptionEvent.ResourceUpdated,
		McpSubscriptionEvent.ToolsListChanged,
		McpSubscriptionEvent.PromptsListChanged {
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
	 * Creates a tool-list-changed event.
	 *
	 * @return tool-list-changed event
	 */
	@NonNull
	static ToolsListChanged toolsListChanged() {
		return ToolsListChanged.INSTANCE;
	}

	/**
	 * Creates a prompt-list-changed event.
	 *
	 * @return prompt-list-changed event
	 */
	@NonNull
	static PromptsListChanged promptsListChanged() {
		return PromptsListChanged.INSTANCE;
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

	/**
	 * Requests reevaluation of the caller-visible {@code tools/list} catalog for
	 * interested subscriptions. Publication alone does not assert that the
	 * visible catalog changed.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class ToolsListChanged implements McpSubscriptionEvent {
		@NonNull
		private static final ToolsListChanged INSTANCE = new ToolsListChanged();

		private ToolsListChanged() {
		}

		/** @return whether the other value is also a tool-list-changed event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof ToolsListChanged;
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
			return "ToolsListChanged{}";
		}
	}

	/**
	 * Requests reevaluation of the caller-visible {@code prompts/list} catalog for
	 * interested subscriptions. Publication alone does not assert that the
	 * visible catalog changed.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class PromptsListChanged implements McpSubscriptionEvent {
		@NonNull
		private static final PromptsListChanged INSTANCE =
				new PromptsListChanged();

		private PromptsListChanged() {
		}

		/** @return whether the other value is also a prompt-list-changed event */
		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof PromptsListChanged;
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
			return "PromptsListChanged{}";
		}
	}
}
