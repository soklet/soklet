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

package com.soklet.converter;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Type;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Thrown if an error occurs during value conversion.
 * <p>
 * For example, a {@link ValueConverter} for 'from' type {@link String} and 'to' type {@link Integer}
 * might throw this exception if the 'from' value is {@code "abc"}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class ValueConversionException extends Exception {
	@NonNull
	private final Type fromType;
	@Nullable
	private final Object fromValue;
	@NonNull
	private final Type toType;

	/**
	 * Creates an exception that describes the value conversion error.
	 *
	 * @param message   a message describing the error
	 * @param fromType  the 'from' type
	 * @param fromValue the value supplied for the 'from' type
	 * @param toType    the 'to' type
	 */
	public ValueConversionException(@Nullable String message,
																	@NonNull Type fromType,
																	@Nullable Object fromValue,
																	@NonNull Type toType) {
		super(message);

		requireNonNull(fromType);
		requireNonNull(toType);

		this.fromType = fromType;
		this.fromValue = fromValue;
		this.toType = toType;
	}

	/**
	 * Creates an exception that describes the value conversion error.
	 *
	 * @param cause     the underlying exception that caused this one to be thrown
	 * @param fromType  the 'from' type
	 * @param fromValue the value supplied for the 'from' type
	 * @param toType    the 'to' type
	 */
	public ValueConversionException(@Nullable Throwable cause,
																	@NonNull Type fromType,
																	@Nullable Object fromValue,
																	@NonNull Type toType) {
		super(cause);

		requireNonNull(fromType);
		requireNonNull(toType);

		this.fromType = fromType;
		this.fromValue = fromValue;
		this.toType = toType;
	}

	/**
	 * Creates an exception that describes the value conversion error.
	 *
	 * @param message   a message describing the error
	 * @param cause     the underlying exception that caused this one to be thrown
	 * @param fromType  the 'from' type
	 * @param fromValue the value supplied for the 'from' type
	 * @param toType    the 'to' type
	 */
	public ValueConversionException(@Nullable String message,
																	@Nullable Throwable cause,
																	@NonNull Type fromType,
																	@Nullable Object fromValue,
																	@NonNull Type toType) {
		super(message, cause);

		requireNonNull(fromType);
		requireNonNull(toType);

		this.fromType = fromType;
		this.fromValue = fromValue;
		this.toType = toType;
	}

	/**
	 * The 'from' type of the failed {@link ValueConverter}.
	 *
	 * @return the 'from' type
	 */
	@NonNull
	public Type getFromType() {
		return this.fromType;
	}

	/**
	 * The 'from' value of the failed {@link ValueConverter}.
	 *
	 * @return the 'from' value that could not be converted to the 'to' type
	 */
	@NonNull
	public Optional<Object> getFromValue() {
		return Optional.ofNullable(this.fromValue);
	}

	/**
	 * The 'to' type of the failed {@link ValueConverter}.
	 *
	 * @return the 'to' type
	 */
	@NonNull
	public Type getToType() {
		return this.toType;
	}
}