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
import java.util.Map;
import java.util.Set;

/** Signature-only excerpt; existing methods not needed by these fixtures are omitted, not removed. */
public final class MarshaledResponse {
	private MarshaledResponse() {}
	@NonNull public static Builder withStatusCode(@NonNull Integer statusCode) { throw signatureOnly(); }
	@NonNull public Copier copy() { throw signatureOnly(); }
	public static final class Builder {
		private Builder() {}
		@NonNull public Builder headers(@Nullable Map<@NonNull String, @NonNull Set<@NonNull String>> headers) { throw signatureOnly(); }
		/**
		 * Uses the existing streaming-body selection rules. A writer is required;
		 * use withoutStreamingResponseBody to clear it. Does not silently remove
		 * an existing known-length body; build rejects conflicting bodies.
		 */
		@NonNull public Builder stream(@NonNull StreamingResponseWriter streamingResponseWriter) { throw signatureOnly(); }
		@NonNull public Builder streamingResponseBody(@Nullable StreamingResponseBody streamingResponseBody) { throw signatureOnly(); }
		@NonNull public Builder withoutBody() { throw signatureOnly(); }
		@NonNull public Builder withoutStreamingResponseBody() { throw signatureOnly(); }
		@NonNull public MarshaledResponse build() { throw signatureOnly(); }
	}
	public static final class Copier {
		private Copier() {}
		@NonNull public Copier stream(@NonNull StreamingResponseWriter streamingResponseWriter) { throw signatureOnly(); }
		@NonNull public Copier streamingResponseBody(@Nullable StreamingResponseBody streamingResponseBody) { throw signatureOnly(); }
		@NonNull public Copier withoutBody() { throw signatureOnly(); }
		@NonNull public Copier withoutStreamingResponseBody() { throw signatureOnly(); }
		@NonNull public MarshaledResponse finish() { throw signatureOnly(); }
	}
	private static UnsupportedOperationException signatureOnly() { return new UnsupportedOperationException("Compile-only API fixture"); }
}
