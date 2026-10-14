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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Internal result of offering a logical response to an HTTP or simulated transport. */
@ThreadSafe
abstract sealed class HttpTransportResponseOutcome permits HttpTransportResponseOutcome.Accepted,
		HttpTransportResponseOutcome.Replaced {
	private HttpTransportResponseOutcome() {}

	@NonNull
	static Accepted accepted() {
		return Accepted.DEFAULT_INSTANCE;
	}

	@NonNull
	static Replaced replaced(@NonNull MarshaledResponse marshaledResponse,
			@Nullable Throwable preparationFailure, @Nullable Throwable writeFailure) {
		return new Replaced(marshaledResponse, preparationFailure, writeFailure);
	}

	static final class Accepted extends HttpTransportResponseOutcome {
		private static final Accepted DEFAULT_INSTANCE = new Accepted();

		private Accepted() {}
	}

	static final class Replaced extends HttpTransportResponseOutcome {
		private final MarshaledResponse marshaledResponse;
		private final @Nullable Throwable preparationFailure;
		private final @Nullable Throwable writeFailure;

		private Replaced(@NonNull MarshaledResponse marshaledResponse,
				@Nullable Throwable preparationFailure, @Nullable Throwable writeFailure) {
			this.marshaledResponse = requireNonNull(marshaledResponse);
			this.preparationFailure = preparationFailure;
			this.writeFailure = writeFailure;
		}

		@NonNull
		MarshaledResponse getMarshaledResponse() { return this.marshaledResponse; }

		@NonNull
		Optional<Throwable> getPreparationFailure() { return Optional.ofNullable(this.preparationFailure); }

		@NonNull
		Optional<Throwable> getWriteFailure() { return Optional.ofNullable(this.writeFailure); }

		void rethrowWriteFailure() {
			Throwable failure = getWriteFailure().orElse(null);
			if (failure instanceof Error error) throw error;
			if (failure instanceof RuntimeException runtimeException) throw runtimeException;
			if (failure != null)
				throw new IllegalStateException("Unable to write replacement HTTP response.", failure);
		}
	}
}
