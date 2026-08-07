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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable endpoint-scoped MCP subscription configuration.
 * <p>
 * The configured notification types are the resource-change families the
 * endpoint may support. The publisher is application-owned and may provide
 * either process-local or distributed broadcast delivery. This value does not
 * transfer publisher lifecycle ownership to Soklet.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSubscriptionConfig {
	@NonNull
	private final McpSubscriptionEventPublisher eventPublisher;
	@NonNull
	private final Set<@NonNull McpSubscriptionNotificationType> notificationTypes;

	/**
	 * Vends a builder primed with an application event publisher.
	 *
	 * @param eventPublisher application-owned broadcast publisher
	 * @return a subscription-configuration builder
	 */
	@NonNull
	public static Builder withEventPublisher(
			@NonNull McpSubscriptionEventPublisher eventPublisher) {
		return new Builder(eventPublisher);
	}

	private McpSubscriptionConfig(@NonNull Builder builder) {
		requireNonNull(builder);
		this.eventPublisher = builder.eventPublisher;
		this.notificationTypes = Collections.unmodifiableSet(
				EnumSet.copyOf(builder.notificationTypes));
	}

	/**
	 * Returns the application-owned broadcast publisher.
	 *
	 * @return event publisher
	 */
	@NonNull
	public McpSubscriptionEventPublisher getEventPublisher() {
		return this.eventPublisher;
	}

	/**
	 * Returns the endpoint's supported resource-notification families.
	 *
	 * @return immutable nonempty notification-type set in enum declaration order
	 */
	@NonNull
	public Set<@NonNull McpSubscriptionNotificationType> getNotificationTypes() {
		return this.notificationTypes;
	}

	/**
	 * Builder for immutable {@link McpSubscriptionConfig} values.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final McpSubscriptionEventPublisher eventPublisher;
		@NonNull
		private final EnumSet<@NonNull McpSubscriptionNotificationType>
				notificationTypes;

		private Builder(
				@NonNull McpSubscriptionEventPublisher eventPublisher) {
			this.eventPublisher = requireNonNull(eventPublisher);
			this.notificationTypes = EnumSet.noneOf(
					McpSubscriptionNotificationType.class);
		}

		/**
		 * Adds one supported resource-notification family.
		 *
		 * @param notificationType notification family
		 * @return this builder
		 */
		@NonNull
		public Builder notificationType(
				@NonNull McpSubscriptionNotificationType notificationType) {
			this.notificationTypes.add(requireNonNull(notificationType));
			return this;
		}

		/**
		 * Replaces the supported resource-notification families.
		 *
		 * @param notificationTypes notification families
		 * @return this builder
		 */
		@NonNull
		public Builder notificationTypes(
				@NonNull Set<@NonNull McpSubscriptionNotificationType>
						notificationTypes) {
			requireNonNull(notificationTypes);
			EnumSet<@NonNull McpSubscriptionNotificationType> copiedTypes =
					EnumSet.noneOf(McpSubscriptionNotificationType.class);
			copiedTypes.addAll(notificationTypes);
			this.notificationTypes.clear();
			this.notificationTypes.addAll(copiedTypes);
			return this;
		}

		/**
		 * Builds an immutable endpoint subscription configuration.
		 *
		 * @return subscription configuration
		 * @throws IllegalStateException if no notification type was configured
		 */
		@NonNull
		public McpSubscriptionConfig build() {
			if (this.notificationTypes.isEmpty())
				throw new IllegalStateException(
						"At least one MCP subscription notification type must be configured.");
			return new McpSubscriptionConfig(this);
		}
	}
}
