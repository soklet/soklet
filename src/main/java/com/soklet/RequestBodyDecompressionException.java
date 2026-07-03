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

import static java.util.Objects.requireNonNull;

/**
 * Internal signal that a request body could not be transparently decompressed; carries the HTTP status
 * the standard HTTP server should respond with.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class RequestBodyDecompressionException extends RuntimeException {
	@NonNull
	private final Reason reason;

	RequestBodyDecompressionException(@NonNull Reason reason,
																		@NonNull String message) {
		super(requireNonNull(message));
		this.reason = requireNonNull(reason);
	}

	RequestBodyDecompressionException(@NonNull Reason reason,
																		@NonNull String message,
																		@NonNull Throwable cause) {
		super(requireNonNull(message), requireNonNull(cause));
		this.reason = requireNonNull(reason);
	}

	@NonNull
	Reason getReason() {
		return this.reason;
	}

	enum Reason {
		/**
		 * The request declared a {@code Content-Encoding} the server does not support ({@code 415}).
		 */
		UNSUPPORTED_CONTENT_ENCODING(415),
		/**
		 * The request body could not be decoded per its declared {@code Content-Encoding} ({@code 400}).
		 */
		MALFORMED_CONTENT(400),
		/**
		 * The decompressed request body exceeded a configured size or ratio limit ({@code 413}).
		 */
		DECOMPRESSED_CONTENT_TOO_LARGE(413);

		private final int statusCode;

		Reason(int statusCode) {
			this.statusCode = statusCode;
		}

		int getStatusCode() {
			return this.statusCode;
		}
	}
}
