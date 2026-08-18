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
import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * Immutable result of one MCP rate-limit acquisition attempt.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface McpRateLimitDecision
		permits McpRateLimitDecision.Allowed, McpRateLimitDecision.Denied {
	/**
	 * Returns an allowed decision.
	 *
	 * @return an allowed decision
	 */
	@NonNull
	static Allowed allowed() {
		return Allowed.INSTANCE;
	}

	/**
	 * Returns a denied decision with the minimum suggested retry delay.
	 *
	 * @param retryAfter nonnegative retry delay
	 * @return a denied decision
	 */
	@NonNull
	static Denied denied(@NonNull Duration retryAfter) {
		return new Denied(retryAfter);
	}

	/**
	 * An allowed acquisition.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class Allowed implements McpRateLimitDecision {
		@NonNull
		private static final Allowed INSTANCE = new Allowed();

		private Allowed() {
		}

		/** @return whether the other value is also an allowed decision */
		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof Allowed;
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
			return "Allowed{}";
		}
	}

	/**
	 * A denied acquisition carrying a nonnegative minimum suggested retry
	 * delay.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public final class Denied implements McpRateLimitDecision {
		@NonNull
		private final Duration retryAfter;

		/**
		 * Validates this denied decision.
		 */
		private Denied(@NonNull Duration retryAfter) {
			this.retryAfter = requireNonNull(retryAfter);
			if (retryAfter.isNegative())
				throw new IllegalArgumentException("retryAfter must not be negative");
		}

		/** @return nonnegative minimum suggested retry delay */
		@NonNull
		public Duration getRetryAfter() {
			return this.retryAfter;
		}

		/** @return whether this decision has the same suggested retry delay */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof Denied denied))
				return false;
			return this.retryAfter.equals(denied.retryAfter);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return this.retryAfter.hashCode();
		}

		/** @return safe diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "Denied{retryAfter=" + this.retryAfter + "}";
		}
	}
}
