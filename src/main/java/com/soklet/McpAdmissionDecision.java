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
 * Immutable accepted or rejected MCP admission decision. Decision
 * carriers are safe for concurrent access; application-owned values reachable
 * through an accepted identity retain their own thread-safety contracts.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpAdmissionDecision permits McpAdmissionDecision.Accepted,
		McpAdmissionDecision.Rejected {
	/**
	 * Creates an accepted decision carrying an application identity.
	 *
	 * @param identity admitted identity
	 * @return accepted decision
	 */
	@NonNull
	static Accepted accepted(@NonNull McpAdmissionIdentity identity) {
		return new Accepted(identity);
	}

	/**
	 * Creates an accepted decision carrying the canonical anonymous identity.
	 *
	 * @return anonymous accepted decision
	 */
	@NonNull
	static Accepted accepted() {
		return accepted(McpAdmissionIdentity.anonymousInstance());
	}

	/**
	 * Creates a rejected decision.
	 *
	 * @param rejection safe client-visible rejection
	 * @return rejected decision
	 */
	@NonNull
	static Rejected rejected(@NonNull McpAdmissionRejection rejection) {
		return new Rejected(rejection);
	}

	/**
	 * Accepted admission decision.
	 *
	 * @param identity admitted identity
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record Accepted(@NonNull McpAdmissionIdentity identity) implements McpAdmissionDecision {
		/**
		 * Creates an accepted decision.
		 *
		 * @param identity admitted identity
		 */
		public Accepted {
			requireNonNull(identity);
		}
	}

	/**
	 * Rejected admission decision.
	 *
	 * @param rejection safe client-visible rejection
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	record Rejected(@NonNull McpAdmissionRejection rejection) implements McpAdmissionDecision {
		/**
		 * Creates a rejected decision.
		 *
		 * @param rejection safe client-visible rejection
		 */
		public Rejected {
			requireNonNull(rejection);
		}
	}
}
