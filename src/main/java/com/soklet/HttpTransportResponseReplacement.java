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

import static java.util.Objects.requireNonNull;

/** Internal, synchronous feedback from the built-in HTTP transport to its request lifecycle. */
final class HttpTransportResponseReplacement extends RuntimeException {
	private static final long serialVersionUID = 1L;
	private final MarshaledResponse marshaledResponse;
	private final @Nullable Throwable writeFailure;

	HttpTransportResponseReplacement(@NonNull MarshaledResponse marshaledResponse,
			@Nullable Throwable preparationFailure, @Nullable Throwable writeFailure) {
		super("HTTP transport replaced the response.", preparationFailure, false, false);
		this.marshaledResponse = requireNonNull(marshaledResponse);
		this.writeFailure = writeFailure;
	}

	MarshaledResponse getMarshaledResponse() { return this.marshaledResponse; }
	@Nullable Throwable getWriteFailure() { return this.writeFailure; }
}
