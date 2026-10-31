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

import java.util.List;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;
import static com.soklet.internal.ObjectIdentity.sameInstance;

/** Carries owned channels through response transformations and releases abandoned resources. */
final class ResponseBodyCleanup {
	private ResponseBodyCleanup() {}

	@NonNull
	static MarshaledResponse transform(@NonNull MarshaledResponse previous,
			@NonNull Function<MarshaledResponse, MarshaledResponse> transformation) {
		MarshaledResponse replacement;
		try {
			replacement = requireNonNull(transformation.apply(previous));
		} catch (RuntimeException | Error failure) {
			discard(previous, null, failure);
			throw failure;
		}
		try {
			return retain(previous, replacement);
		} catch (RuntimeException | Error failure) {
			discard(replacement, null, failure);
			throw failure;
		}
	}

	@NonNull
	static MarshaledResponse track(@NonNull List<MarshaledResponse> pending,
			@NonNull MarshaledResponse response) {
		if (response.hasOwnedChannels())
			pending.add(response);
		return response;
	}

	@NonNull
	static MarshaledResponse retain(@NonNull MarshaledResponse previous, @NonNull MarshaledResponse replacement) {
		return replacement.retainingChannels(previous.ownedChannels());
	}

	static void discard(@NonNull MarshaledResponse previous, @Nullable MarshaledResponse replacement,
			@Nullable Throwable primaryFailure) {
		List<java.nio.channels.FileChannel> channels = previous.ownedChannels();
		if (replacement != null && replacement.getBody().orElse(null) instanceof MarshaledResponseBody.FileChannel next) {
			channels = new java.util.ArrayList<>(channels);
			channels.removeIf(channel -> sameInstance(channel, next.getChannel()));
		}
		MarshaledResponse.closeChannels(channels, primaryFailure);
	}
}
