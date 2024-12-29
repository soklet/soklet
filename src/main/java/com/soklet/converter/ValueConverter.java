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

package com.soklet.converter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Contract for converting objects from one type to another.
 * <p>
 * For example, you might have a {@code ValueConverter<String, List<Integer>>} which converts
 * text like {@code "1,2,3"} to a list of numbers.
 * <p>
 * Value conversion is documented in detail at <a href="https://www.soklet.com/docs/value-conversions">https://www.soklet.com/docs/value-conversions</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface ValueConverter<F, T> {
	/**
	 * Converts {@code from} to an instance of {@code T}.
	 *
	 * @param from the value from which to convert. May be {@code null}
	 * @return the {@code T} representation of {@code from}
	 * @throws ValueConversionException if an error occurs during conversion
	 */
	@Nonnull
	Optional<T> convert(@Nullable F from) throws ValueConversionException;

	/**
	 * The 'converting from' type.
	 *
	 * @return the type represented by {@code F}
	 */
	@Nonnull
	Type getFromType();

	/**
	 * The 'converting to' type.
	 *
	 * @return the type represented by {@code T}
	 */
	@Nonnull
	Type getToType();
}