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

package com.soklet;

import com.soklet.annotation.RequestBody;
import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import com.soklet.exception.IllegalRequestBodyException;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultRequestBodyMarshaler implements RequestBodyMarshaler {
	@NonNull
	private final ValueConverterRegistry valueConverterRegistry;

	public DefaultRequestBodyMarshaler(@NonNull ValueConverterRegistry valueConverterRegistry) {
		requireNonNull(valueConverterRegistry);
		this.valueConverterRegistry = valueConverterRegistry;
	}

	@NonNull
	@Override
	public Optional<Object> marshalRequestBody(@NonNull Request request,
																						 @NonNull ResourceMethod resourceMethod,
																						 @NonNull Parameter parameter,
																						 @NonNull Type requestBodyType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(requestBodyType);

		ValueConverter<Object, Object> valueConverter = getValueConverterRegistry().get(String.class, requestBodyType).orElse(null);

		if (valueConverter == null)
			throw new IllegalStateException(format("Soklet is not configured to marshal @%s fields of type %s. "
							+ "To do so, provide your own implementation of %s. See https://www.soklet.com/docs/request-handling#request-body for details.",
					RequestBody.class.getSimpleName(), requestBodyType, RequestBodyMarshaler.class.getSimpleName()));

		String requestBodyAsString = request.getBodyAsString().orElse(null);

		try {
			if (requestBodyAsString == null)
				return Optional.empty();

			Optional<Object> valueConverterResult = valueConverter.convert(requestBodyAsString);
			return valueConverterResult == null ? Optional.empty() : valueConverterResult;
		} catch (ValueConversionException e) {
			throw new IllegalRequestBodyException(format("Unable to marshal request body to %s", requestBodyType), e);
		}
	}

	@NonNull
	protected ValueConverterRegistry getValueConverterRegistry() {
		return this.valueConverterRegistry;
	}
}