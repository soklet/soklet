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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Exception thrown when an HTTP query parameter value does not match the expected Java type.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public final class IllegalQueryParameterException extends BadRequestException {
	@Nonnull
	private final String queryParameterName;
	@Nullable
	private final String queryParameterValue;

	public IllegalQueryParameterException(@Nullable String message,
																				@Nonnull String queryParameterName,
																				@Nullable String queryParameterValue) {
		super(message);
		this.queryParameterName = requireNonNull(queryParameterName);
		this.queryParameterValue = queryParameterValue;
	}

	public IllegalQueryParameterException(@Nullable String message,
																				@Nullable Throwable cause,
																				@Nonnull String queryParameterName,
																				@Nullable String queryParameterValue) {
		super(message, cause);
		this.queryParameterName = requireNonNull(queryParameterName);
		this.queryParameterValue = queryParameterValue;
	}

	@Nonnull
	public String getQueryParameterName() {
		return this.queryParameterName;
	}

	@Nonnull
	public Optional<String> getQueryParameterValue() {
		return Optional.ofNullable(this.queryParameterValue);
	}
}