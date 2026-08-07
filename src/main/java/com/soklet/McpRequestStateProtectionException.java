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

import static java.util.Objects.requireNonNull;

/**
 * Sanitized checked failure from an application request-state protector.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public final class McpRequestStateProtectionException extends Exception {
	private static final long serialVersionUID = 1L;
	@NonNull
	private final Reason reason;

	/**
	 * Sanitized protection-failure category.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public enum Reason {
		/** Protected state is invalid, unauthenticated, or context-inappropriate. */
		INVALID_STATE,

		/** The application protection service is temporarily unavailable. */
		PROTECTOR_UNAVAILABLE
	}

	/** @return invalid-state failure without sensitive details */
	@NonNull
	public static McpRequestStateProtectionException fromInvalidState() {
		return new McpRequestStateProtectionException(Reason.INVALID_STATE);
	}

	/** @return protector-unavailable failure without sensitive details */
	@NonNull
	public static McpRequestStateProtectionException fromProtectorUnavailable() {
		return new McpRequestStateProtectionException(
				Reason.PROTECTOR_UNAVAILABLE);
	}

	private McpRequestStateProtectionException(@NonNull Reason reason) {
		super(reason == Reason.INVALID_STATE ? "Request state is invalid."
				: "Request-state protection is unavailable.");
		this.reason = requireNonNull(reason);
	}

	/** @return sanitized failure category */
	@NonNull
	public Reason getReason() {
		return this.reason;
	}
}
