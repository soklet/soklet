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
 * The configured notification types are the subscription-change families the
 * endpoint may support. The publisher is application-owned and may provide
 * either process-local or distributed broadcast delivery. This value does not
 * transfer publisher lifecycle ownership to Soklet.
 *
 * <p>Instances intentionally retain reference identity because the publisher
 * is a live application-owned capability, not immutable value data. Code that
 * compares configurations should compare notification types and publisher
 * identity according to its own lifecycle model.
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
	 * Vends a builder primed with its required construction values.
	 *
	 * @param subscriptionEventPublisher application-owned broadcast publisher
	 * @param subscriptionNotificationTypes nonempty supported notification families
	 * @return a subscription-configuration builder
	 * @throws NullPointerException if an argument or notification type is null
	 * @throws IllegalArgumentException if {@code subscriptionNotificationTypes} is empty
	 */
	@NonNull
	public static Builder withEventPublisherAndNotificationTypes(
			@NonNull McpSubscriptionEventPublisher subscriptionEventPublisher,
			@NonNull Set<@NonNull McpSubscriptionNotificationType>
					subscriptionNotificationTypes) {
		return new Builder(subscriptionEventPublisher, subscriptionNotificationTypes);
	}

	private McpSubscriptionConfig(@NonNull Builder builder) {
		requireNonNull(builder);
		this.eventPublisher = builder.eventPublisher;
		this.notificationTypes = requireNotificationTypes(builder.notificationTypes);
	}

	@NonNull
	private static Set<@NonNull McpSubscriptionNotificationType> requireNotificationTypes(
			@NonNull Set<@NonNull McpSubscriptionNotificationType>
					subscriptionNotificationTypes) {
		EnumSet<McpSubscriptionNotificationType> copiedTypes = EnumSet.noneOf(
				McpSubscriptionNotificationType.class);
		for (McpSubscriptionNotificationType subscriptionNotificationType
				: requireNonNull(subscriptionNotificationTypes))
			copiedTypes.add(requireNonNull(subscriptionNotificationType));
		if (copiedTypes.isEmpty())
			throw new IllegalArgumentException(
					"At least one MCP subscription notification type must be configured.");
		return Collections.unmodifiableSet(copiedTypes);
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
	 * Returns the endpoint's supported subscription-notification families.
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
		private Set<@NonNull McpSubscriptionNotificationType>
				notificationTypes;

		private Builder(
				@NonNull McpSubscriptionEventPublisher subscriptionEventPublisher,
				@NonNull Set<@NonNull McpSubscriptionNotificationType>
						subscriptionNotificationTypes) {
			this.eventPublisher = requireNonNull(subscriptionEventPublisher);
			this.notificationTypes = requireNotificationTypes(subscriptionNotificationTypes);
		}

		/**
		 * Replaces the supported subscription-notification families.
		 * A failed replacement leaves the previous families unchanged.
		 *
		 * @param subscriptionNotificationTypes nonempty notification families
		 * @return this builder
		 * @throws NullPointerException if the set or any item is null
		 * @throws IllegalArgumentException if the set is empty
		 */
		@NonNull
		public Builder notificationTypes(
				@NonNull Set<@NonNull McpSubscriptionNotificationType>
						subscriptionNotificationTypes) {
			this.notificationTypes = requireNotificationTypes(subscriptionNotificationTypes);
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
