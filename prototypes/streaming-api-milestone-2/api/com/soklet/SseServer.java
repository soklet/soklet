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
import java.time.Duration;

/** Signature-only builder excerpt; no runtime or removal of unrelated server methods. */
public interface SseServer {
	@NonNull static Builder withPort(@NonNull Integer port) { throw signatureOnly(); }
	final class Builder {
		private Builder() {}
		/**
		 * Maximum admitted lifetimes, including residual work. Null restores this
		 * server's default. Positive, at most Integer.MAX_VALUE / 2. Resolved values
		 * are validated at build time; connection/producer admission is independent.
		 * @param streamingLifecycleCapacity maximum admitted lifetimes, or null for the default
		 * @return this builder
		 */
		@NonNull public Builder streamingLifecycleCapacity(@Nullable Integer streamingLifecycleCapacity) { throw signatureOnly(); }
		/**
		 * Bounds managed callback workers; positive and no greater than resolved
		 * lifecycle capacity. Null restores this server's default. No worker is
		 * replaced merely because an application callback is stuck.
		 * @param streamingCallbackConcurrency callback workers, or null for the default
		 * @return this builder
		 */
		@NonNull public Builder streamingCallbackConcurrency(@Nullable Integer streamingCallbackConcurrency) { throw signatureOnly(); }
		/**
		 * Positive, nanosecond-representable cleanup grace; null restores default.
		 * Zero does not disable supervision. It bounds supervisory waiting, not
		 * arbitrary cleanup execution; response and shutdown budgets are separate.
		 * @param streamingCleanupTimeout cleanup grace, or null for the default
		 * @return this builder
		 */
		@NonNull public Builder streamingCleanupTimeout(@Nullable Duration streamingCleanupTimeout) { throw signatureOnly(); }
		@NonNull public SseServer build() { throw signatureOnly(); }
	}
	private static UnsupportedOperationException signatureOnly() { return new UnsupportedOperationException("Compile-only API fixture"); }
}
