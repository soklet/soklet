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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Convenience superclass which provides default implementations of {@link ValueConverter} methods.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public abstract class AbstractValueConverter<F, T> implements ValueConverter<F, T> {
	@NonNull
	private final Type fromType;
	@NonNull
	private final Type toType;

	/**
	 * Supports subclasses that have both 'from' and 'to' generic types.
	 */
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

	/**
	 * Supports subclasses that have only a 'to' generic type, like {@link FromStringValueConverter}.
	 *
	 * @param fromType an explicitly-provided 'from' type
	 */
	public AbstractValueConverter(@NonNull Type fromType) {
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

	@NonNull
	@Override
	@SuppressWarnings("unchecked")
	public final Optional<T> convert(@Nullable F from) throws ValueConversionException {
		// Special handling for String types
		if (from instanceof String && shouldTrimFromValues())
			from = (F) trimAggressivelyToNull((String) from);

		try {
			return performConversion(from);
		} catch (ValueConversionException e) {
			throw e;
		} catch (Exception e) {
			throw new ValueConversionException(format("Unable to convert value '%s' of type %s to an instance of %s", from,
					getFromType(), getToType()), e, getFromType(), from, getToType());
		}
	}

	@NonNull
	protected Boolean shouldTrimFromValues() {
		return true;
	}

	/**
	 * Subclasses must implement this method to convert a 'from' instance to a 'to' instance.
	 *
	 * @param from the instance we are converting from
	 * @return an instance that was converted to
	 * @throws Exception if an error occured during conversion
	 */
	@NonNull
	public abstract Optional<T> performConversion(@Nullable F from) throws Exception;

	@Override
	@NonNull
	public Type getFromType() {
		return this.fromType;
	}

	@Override
	@NonNull
	public Type getToType() {
		return this.toType;
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{fromType=%s, toType=%s}", getClass().getSimpleName(), getFromType(), getToType());
	}

	@NonNull
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