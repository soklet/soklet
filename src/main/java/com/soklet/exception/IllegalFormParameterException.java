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
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class IllegalFormParameterException extends BadRequestException {
	@Nonnull
	private final String formParameterName;
	@Nullable
	private final String formParameterValue;

	public IllegalFormParameterException(@Nullable String message,
																			 @Nonnull String formParameterName,
																			 @Nullable String formParameterValue) {
		super(message);
		this.formParameterName = requireNonNull(formParameterName);
		this.formParameterValue = formParameterValue;
	}

	public IllegalFormParameterException(@Nullable String message,
																			 @Nullable Throwable cause,
																			 @Nonnull String formParameterName,
																			 @Nullable String formParameterValue) {
		super(message, cause);
		this.formParameterName = requireNonNull(formParameterName);
		this.formParameterValue = formParameterValue;
	}

	@Nonnull
	public String getFormParameterName() {
		return this.formParameterName;
	}

	@Nonnull
	public Optional<String> getFormParameterValue() {
		return Optional.ofNullable(this.formParameterValue);
	}
}