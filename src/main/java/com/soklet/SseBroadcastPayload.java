/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** One key's generation outcome, retained only for the duration of a single broadcast. */
@NotThreadSafe
final class SseBroadcastPayload<T> {
	@Nullable
	private final T payload;
	@Nullable
	private final Throwable failure;
	private int failedClientCount;

	private SseBroadcastPayload(@Nullable T payload, @Nullable Throwable failure) {
		this.payload = payload;
		this.failure = failure;
	}

	@NonNull
	static <T> SseBroadcastPayload<T> fromSupplier(@NonNull Supplier<@NonNull T> payloadSupplier) {
		requireNonNull(payloadSupplier);
		try {
			return new SseBroadcastPayload<>(requireNonNull(payloadSupplier.get()), null);
		} catch (Throwable failure) {
			// A non-null outcome also caches provider/serialization failures. Returning
			// null or throwing from computeIfAbsent would cause another client to retry.
			return new SseBroadcastPayload<>(null, failure);
		}
	}

	@Nullable
	T getPayload() { return this.payload; }

	@NonNull
	Optional<@NonNull Throwable> getFailure() { return Optional.ofNullable(this.failure); }

	void recordFailedClient() { this.failedClientCount++; }

	int getFailedClientCount() { return this.failedClientCount; }
}
