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

/** Releases owned file channels abandoned before a response reaches the transport. */
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
			discard(previous, replacement, null);
		} catch (RuntimeException | Error failure) {
			// The replacement cannot be returned if releasing its predecessor failed.
			discard(replacement, null, failure);
			throw failure;
		}
		return replacement;
	}

	@NonNull
	static MarshaledResponse track(@NonNull List<MarshaledResponse> pending,
			@NonNull MarshaledResponse response) {
		if (response.getBody().orElse(null) instanceof MarshaledResponseBody.FileChannel body
				&& body.getCloseOnComplete())
			pending.add(response);
		return response;
	}

	@SuppressWarnings("ReferenceEquality")
	static void discard(@NonNull MarshaledResponse previous, @Nullable MarshaledResponse replacement,
			@Nullable Throwable primaryFailure) {
		if (!(previous.getBody().orElse(null) instanceof MarshaledResponseBody.FileChannel body)
				|| !body.getCloseOnComplete())
			return;
		// A copied response or a new range descriptor can retain the same channel.
		if (replacement != null
				&& replacement.getBody().orElse(null) instanceof MarshaledResponseBody.FileChannel nextBody
				&& body.getChannel() == nextBody.getChannel())
			return;
		try {
			previous.copy().withoutBody();
		} catch (RuntimeException | Error cleanupFailure) {
			if (primaryFailure == null)
				throw cleanupFailure;
			if (primaryFailure != cleanupFailure)
				primaryFailure.addSuppressed(cleanupFailure);
		}
	}
}
