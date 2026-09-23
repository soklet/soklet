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
import java.util.Optional;

/** Signature-only excerpt; unrelated accepted/rejected handshake API is omitted, not removed. */
public interface SseHandshakeResult {
	final class Accepted implements SseHandshakeResult {
		private Accepted() {}
		@NonNull public static Builder builder() { throw signatureOnly(); }
		@NonNull public Optional<@NonNull SseClientInitializer> getClientInitializer() { throw signatureOnly(); }
		public static final class Builder {
			private Builder() {}
			/** Null removes the initializer; no competing Consumer overload remains. */
			@NonNull public Builder clientInitializer(@Nullable SseClientInitializer clientInitializer) { throw signatureOnly(); }
			@NonNull public Accepted build() { throw signatureOnly(); }
		}
	}
	private static UnsupportedOperationException signatureOnly() { return new UnsupportedOperationException("Compile-only API fixture"); }
}
