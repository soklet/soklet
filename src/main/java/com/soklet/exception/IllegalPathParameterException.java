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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Exception thrown when a URL path parameter value does not match the expected Java type.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public final class IllegalPathParameterException extends BadRequestException {
	@NonNull
	private final String pathParameterName;
	@Nullable
	private final String pathParameterValue;

	public IllegalPathParameterException(@Nullable String message,
																			 @NonNull String pathParameterName,
																			 @Nullable String pathParameterValue) {
		super(message);
		this.pathParameterName = requireNonNull(pathParameterName);
		this.pathParameterValue = pathParameterValue;
	}

	public IllegalPathParameterException(@Nullable String message,
																			 @Nullable Throwable cause,
																			 @NonNull String pathParameterName,
																			 @Nullable String pathParameterValue) {
		super(message, cause);
		this.pathParameterName = requireNonNull(pathParameterName);
		this.pathParameterValue = pathParameterValue;
	}

	@NonNull
	public String getPathParameterName() {
		return this.pathParameterName;
	}

	@NonNull
	public Optional<String> getPathParameterValue() {
		return Optional.ofNullable(this.pathParameterValue);
	}
}