/*
 * Copyright 2022-2025 Revetware LLC.
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

import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Abstract superclass for exceptions that would normally result in an HTTP 400 (Bad Request) status code.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public abstract class BadRequestException extends RuntimeException {
	public BadRequestException(@Nullable String message) {
		super(message);
	}

	public BadRequestException(@Nullable String message,
														 @Nullable Throwable cause) {
		super(message, cause);
	}
}