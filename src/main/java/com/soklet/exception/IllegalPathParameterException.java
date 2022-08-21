/*
 * Copyright 2022 Revetware LLC.
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
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@NotThreadSafe
public class IllegalPathParameterException extends BadRequestException {
	@Nonnull
	private final String pathParameterName;
	@Nullable
	private final String pathParameterValue;

	public IllegalPathParameterException(@Nullable String message,
																			 @Nonnull String pathParameterName,
																			 @Nullable String pathParameterValue) {
		super(message);
		this.pathParameterName = requireNonNull(pathParameterName);
		this.pathParameterValue = pathParameterValue;
	}

	public IllegalPathParameterException(@Nullable String message,
																			 @Nullable Throwable cause,
																			 @Nonnull String pathParameterName,
																			 @Nullable String pathParameterValue) {
		super(message, cause);
		this.pathParameterName = requireNonNull(pathParameterName);
		this.pathParameterValue = pathParameterValue;
	}

	@Nonnull
	public String getPathParameterName() {
		return this.pathParameterName;
	}

	@Nonnull
	public Optional<String> getPathParameterValue() {
		return Optional.ofNullable(this.pathParameterValue);
	}
}