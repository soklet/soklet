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

package com.soklet.exception;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Exception thrown when a required request header is missing from the HTTP request.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public final class MissingRequestHeaderException extends BadRequestException {
	@NonNull
	private final String requestHeaderName;

	public MissingRequestHeaderException(@Nullable String message,
																			 @NonNull String requestHeaderName) {
		super(message);
		this.requestHeaderName = requireNonNull(requestHeaderName);
	}

	@NonNull
	public String getRequestHeaderName() {
		return this.requestHeaderName;
	}
}