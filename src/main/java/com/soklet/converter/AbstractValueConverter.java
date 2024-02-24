/*
 * Copyright 2022-2024 Revetware LLC.
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

import com.soklet.core.Utilities;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public abstract class AbstractValueConverter<F, T> implements ValueConverter<F, T> {
	@Nonnull
	private final Type fromType;
	@Nonnull
	private final Type toType;

	public AbstractValueConverter() {
		List<Type> genericTypes = genericTypesForClass(getClass());

		Type fromType = null;
		Type toType = null;

		if (genericTypes.size() == 2) {
			fromType = genericTypes.get(0);
			toType = genericTypes.get(1);
		}

		if (fromType == null || toType == null)
			throw new IllegalStateException(format("Unable to extract generic %s type information from %s",
					ValueConverter.class.getSimpleName(), this));

		this.fromType = fromType;
		this.toType = toType;
	}

	public AbstractValueConverter(@Nonnull Type fromType) {
		requireNonNull(fromType);

		List<Type> genericTypes = genericTypesForClass(getClass());

		Type toType = null;

		if (genericTypes.size() == 1)
			toType = genericTypes.get(0);

		if (toType == null)
			throw new IllegalStateException(format("Unable to extract generic %s type information from %s",
					ValueConverter.class.getSimpleName(), this));

		this.fromType = fromType;
		this.toType = toType;
	}

	@Nullable
	@Override
	@SuppressWarnings("unchecked")
	public final T convert(@Nullable F from) throws ValueConversionException {
		if (from == null)
			return null;

		// Special handling for String types
		if (from instanceof String) {
			from = (F) Utilities.trimAggressivelyToNull((String) from);

			if (from == null)
				return null;
		}

		try {
			return performConversion(from);
		} catch (ValueConversionException e) {
			throw e;
		} catch (Exception e) {
			throw new ValueConversionException(format("Unable to convert value '%s' of type %s to an instance of %s", from,
					getFromType(), getToType()), e, getFromType(), getToType());
		}
	}

	@Nullable
	public abstract T performConversion(@Nonnull F from) throws Exception;

	@Override
	@Nonnull
	public Type getFromType() {
		return this.fromType;
	}

	@Override
	@Nonnull
	public Type getToType() {
		return this.toType;
	}

	@Override
	@Nonnull
	public String toString() {
		return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), getFromType(), getToType());
	}

	@Nonnull
	static List<Type> genericTypesForClass(@Nullable Class<?> valueConverterClass) {
		if (valueConverterClass == null)
			return List.of();

		// TODO: this only works for simple cases (direct subclass or direct use of ValueConverter interface) and doesn't do full error handling.
		List<Type> genericInterfaces = Arrays.asList(valueConverterClass.getGenericInterfaces());

		// If not direct use of interface, try superclass (no error handling done yet)
		if (genericInterfaces.size() == 0)
			genericInterfaces = Collections.singletonList(valueConverterClass.getGenericSuperclass());

		// Figure out what the two type arguments are for ValueConverter
		for (Type genericInterface : genericInterfaces) {
			if (genericInterface instanceof ParameterizedType) {
				Object rawType = ((ParameterizedType) genericInterface).getRawType();

				if (!ValueConverter.class.isAssignableFrom((Class<?>) rawType))
					continue;

				Type[] genericTypes = ((ParameterizedType) genericInterface).getActualTypeArguments();
				return Arrays.asList(genericTypes);
			}
		}

		return List.of();
	}
}