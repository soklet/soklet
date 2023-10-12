/*
 * Copyright 2022-2023 Revetware LLC.
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

package com.soklet.converter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Type;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class ValueConversionException extends Exception {
	@Nonnull
	private final Type fromType;
	@Nonnull
	private final Type toType;

	public ValueConversionException(@Nullable String message,
																	@Nonnull Type fromType,
																	@Nonnull Type toType) {
		super(message);

		requireNonNull(fromType);
		requireNonNull(toType);

		this.fromType = fromType;
		this.toType = toType;
	}

	public ValueConversionException(@Nullable Throwable cause,
																	@Nonnull Type fromType,
																	@Nonnull Type toType) {
		super(cause);

		requireNonNull(fromType);
		requireNonNull(toType);

		this.fromType = fromType;
		this.toType = toType;
	}

	public ValueConversionException(@Nullable String message,
																	@Nullable Throwable cause,
																	@Nonnull Type fromType,
																	@Nonnull Type toType) {
		super(message, cause);

		requireNonNull(fromType);
		requireNonNull(toType);

		this.fromType = fromType;
		this.toType = toType;
	}

	@Nonnull
	public Type getFromType() {
		return this.fromType;
	}

	@Nonnull
	public Type getToType() {
		return this.toType;
	}
}