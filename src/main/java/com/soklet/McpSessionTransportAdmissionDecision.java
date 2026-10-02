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
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable HTTP admission decision for session-enabled {@code 2025-06-18}
 * and {@code 2025-11-25} GET delivery or DELETE cleanup. Soklet's
 * {@code 2026-07-28} implementation does not use this decision.
 * <p>
 * Every acceptance supplies an explicit expiry. Soklet caps GET leases using
 * its configured authorization, subscription and session lifetimes, and
 * verifies expiry again before delivery or DELETE retirement. Application
 * values reachable through the identity retain their own thread-safety and
 * lifecycle contracts.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpSessionTransportAdmissionDecision
		permits McpSessionTransportAdmissionDecision.Accepted,
		McpSessionTransportAdmissionDecision.Rejected {
	/**
	 * Creates an accepted decision. The selected notification families must be
	 * a subset of the context's offered families; DELETE requires an empty set.
	 * The expiry must still be in the future when Soklet applies the decision.
	 * These operation-dependent conditions are checked by the runtime.
	 *
	 * @param admissionIdentity freshly admitted identity
	 * @param validUntil application authorization expiry
	 * @param notificationTypes selected notification families, possibly empty
	 * @return immutable accepted decision
	 * @throws NullPointerException if an argument or notification type is null
	 */
	@NonNull
	static Accepted accepted(@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull Instant validUntil,
			@NonNull Set<@NonNull McpSubscriptionNotificationType> notificationTypes) {
		return new Accepted(admissionIdentity, validUntil, notificationTypes);
	}

	/**
	 * Creates an HTTP rejection using the existing rejection's safe status and
	 * response headers. GET and DELETE have no JSON-RPC request ID, so the
	 * rejection's JSON-RPC error is not rendered on these paths. Header safety
	 * and framework-owned status rules remain checked before transport.
	 *
	 * @param admissionRejection HTTP status and application response headers
	 * @return immutable rejected decision
	 * @throws NullPointerException if the rejection is null
	 */
	@NonNull
	static Rejected rejected(@NonNull McpAdmissionRejection admissionRejection) {
		return new Rejected(admissionRejection);
	}

	/**
	 * Accepted HTTP identity, explicit expiry and immutable family selection.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class Accepted implements McpSessionTransportAdmissionDecision {
		@NonNull private final McpAdmissionIdentity identity;
		@NonNull private final Instant validUntil;
		@NonNull private final Set<@NonNull McpSubscriptionNotificationType> notificationTypes;

		private Accepted(@NonNull McpAdmissionIdentity admissionIdentity,
				@NonNull Instant validUntil,
				@NonNull Set<@NonNull McpSubscriptionNotificationType> notificationTypes) {
			this.identity = requireNonNull(admissionIdentity);
			this.validUntil = requireNonNull(validUntil);
			EnumSet<McpSubscriptionNotificationType> copiedTypes = EnumSet.noneOf(McpSubscriptionNotificationType.class);
			for (McpSubscriptionNotificationType notificationType : requireNonNull(notificationTypes))
				copiedTypes.add(requireNonNull(notificationType));
			this.notificationTypes = Collections.unmodifiableSet(copiedTypes);
		}

		/** @return freshly admitted identity */
		@NonNull public McpAdmissionIdentity getIdentity() { return this.identity; }

		/** @return application authorization expiry */
		@NonNull public Instant getValidUntil() { return this.validUntil; }

		/** @return immutable selected families in enum declaration order */
		@NonNull public Set<@NonNull McpSubscriptionNotificationType> getNotificationTypes() {
			return this.notificationTypes;
		}

		/** @return whether every admission property is structurally equal */
		@Override
		public boolean equals(@Nullable Object other) {
			return this == other || other instanceof Accepted accepted
					&& this.identity.equals(accepted.identity)
					&& this.validUntil.equals(accepted.validUntil)
					&& this.notificationTypes.equals(accepted.notificationTypes);
		}

		/** @return value-based hash code */
		@Override public int hashCode() { return Objects.hash(this.identity, this.validUntil, this.notificationTypes); }

		/** @return redacted diagnostic rendering */
		@Override @NonNull public String toString() {
			return "Accepted{identity=<redacted>, validUntil=<redacted>, notificationTypeCount=" + this.notificationTypes.size() + "}";
		}
	}

	/**
	 * Rejected HTTP decision retaining the rejection carrier. Soklet validates
	 * its HTTP headers before applying it to the transport.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class Rejected implements McpSessionTransportAdmissionDecision {
		@NonNull private final McpAdmissionRejection rejection;

		private Rejected(@NonNull McpAdmissionRejection admissionRejection) {
			this.rejection = requireNonNull(admissionRejection);
		}

		/** @return HTTP status and response-header rejection carrier */
		@NonNull public McpAdmissionRejection getRejection() { return this.rejection; }

		/** @return whether this decision carries the same rejection */
		@Override
		public boolean equals(@Nullable Object other) {
			return this == other || other instanceof Rejected rejected && this.rejection.equals(rejected.rejection);
		}

		/** @return value-based hash code */
		@Override public int hashCode() { return this.rejection.hashCode(); }

		/** @return redacted diagnostic rendering */
		@Override @NonNull public String toString() { return "Rejected{rejection=<redacted>}"; }
	}
}
