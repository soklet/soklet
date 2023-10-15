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

package com.soklet.core.impl;

import com.soklet.annotation.FormParameter;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.RequestCookie;
import com.soklet.annotation.RequestHeader;
import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import com.soklet.core.InstanceProvider;
import com.soklet.core.Request;
import com.soklet.core.RequestBodyMarshaler;
import com.soklet.core.ResourceMethod;
import com.soklet.core.ResourceMethodParameterProvider;
import com.soklet.core.ResourcePath;
import com.soklet.core.Utilities;
import com.soklet.exception.BadRequestException;
import com.soklet.exception.IllegalFormParameterException;
import com.soklet.exception.IllegalPathParameterException;
import com.soklet.exception.IllegalQueryParameterException;
import com.soklet.exception.IllegalRequestBodyException;
import com.soklet.exception.IllegalRequestCookieException;
import com.soklet.exception.IllegalRequestHeaderException;
import com.soklet.exception.MissingFormParameterException;
import com.soklet.exception.MissingQueryParameterException;
import com.soklet.exception.MissingRequestBodyException;
import com.soklet.exception.MissingRequestCookieException;
import com.soklet.exception.MissingRequestHeaderException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.soklet.core.Utilities.trimAggressively;
import static com.soklet.core.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultResourceMethodParameterProvider implements ResourceMethodParameterProvider {
	@Nonnull
	private final InstanceProvider instanceProvider;
	@Nonnull
	private final ValueConverterRegistry valueConverterRegistry;
	@Nonnull
	private final RequestBodyMarshaler requestBodyMarshaler;

	public DefaultResourceMethodParameterProvider(@Nonnull InstanceProvider instanceProvider,
																								@Nonnull ValueConverterRegistry valueConverterRegistry,
																								@Nonnull RequestBodyMarshaler requestBodyMarshaler) {
		requireNonNull(instanceProvider);
		requireNonNull(valueConverterRegistry);
		requireNonNull(requestBodyMarshaler);

		this.instanceProvider = instanceProvider;
		this.valueConverterRegistry = valueConverterRegistry;
		this.requestBodyMarshaler = requestBodyMarshaler;
	}

	@Nonnull
	@Override
	public List<Object> parameterValuesForResourceMethod(@Nonnull Request request,
																											 @Nonnull ResourceMethod resourceMethod) {
		requireNonNull(request);
		requireNonNull(resourceMethod);

		Parameter[] parameters = resourceMethod.getMethod().getParameters();
		List<Object> parametersToPass = new ArrayList<>(parameters.length);

		for (int i = 0; i < parameters.length; ++i) {
			Parameter parameter = parameters[i];

			try {
				parametersToPass.add(extractParameterValueToPassToResourceMethod(request, resourceMethod, parameter));
			} catch (BadRequestException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalArgumentException(format("Unable to inject parameter at index %d (%s) for resource method %s.",
						i, parameter, resourceMethod.getMethod()), e);
			}
		}

		return parametersToPass;
	}

	@Nullable
	protected Object extractParameterValueToPassToResourceMethod(@Nonnull Request request,
																															 @Nonnull ResourceMethod resourceMethod,
																															 @Nonnull Parameter parameter) throws Exception {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);

		if (parameter.getType().isAssignableFrom(Request.class))
			return request;

		ParameterType parameterType = new ParameterType(parameter);
		PathParameter pathParameter = parameter.getAnnotation(PathParameter.class);

		if (pathParameter != null) {
			if (parameterType.isOptional())
				throw new IllegalStateException(format("@%s-annotated parameters cannot be marked %s",
						PathParameter.class.getSimpleName(), Optional.class.getSimpleName()));

			String pathParameterName = extractParameterName(resourceMethod, parameter, pathParameter, pathParameter.value());
			ResourcePath requestResourcePath = ResourcePath.fromPathInstance(request.getPath());

			Map<String, String> valuesByPathParameter = resourceMethod.getResourcePath().placeholders(requestResourcePath);
			String pathParameterValue = valuesByPathParameter.get(pathParameterName);

			if (pathParameterValue == null)
				throw new IllegalStateException(format("Missing value for path parameter '%s' for resource method %s",
						pathParameterName, resourceMethod));

			ValueConverter<Object, Object> valueConverter = getValueConverterRegistry().get(String.class, parameter.getType()).orElse(null);

			if (valueConverter == null)
				throwValueConverterMissingException(parameter, String.class, parameter.getType(), resourceMethod);

			Object result;

			try {
				result = valueConverter.convert(pathParameterValue);
			} catch (ValueConversionException e) {
				throw new IllegalPathParameterException(format("Illegal value '%s' was specified for path parameter '%s' (was expecting a value convertible to %s)",
						pathParameterValue, pathParameterName, valueConverter.getToType()), e, pathParameterName, pathParameterValue);
			}

			return result;
		}

		QueryParameter queryParameter = parameter.getAnnotation(QueryParameter.class);

		if (queryParameter != null)
			return extractQueryParameterValue(request, resourceMethod, parameter, queryParameter, parameterType);

		FormParameter formParameter = parameter.getAnnotation(FormParameter.class);

		if (formParameter != null)
			return extractFormParameterValue(request, resourceMethod, parameter, formParameter, parameterType);

		RequestHeader requestHeader = parameter.getAnnotation(RequestHeader.class);

		if (requestHeader != null)
			return extractRequestHeaderValue(request, resourceMethod, parameter, requestHeader, parameterType);

		RequestCookie requestCookie = parameter.getAnnotation(RequestCookie.class);

		if (requestCookie != null)
			return extractRequestCookieValue(request, resourceMethod, parameter, requestCookie, parameterType);

		RequestBody requestBody = parameter.getAnnotation(RequestBody.class);

		if (requestBody != null) {
			boolean requestBodyExpectsString = String.class.equals(parameterType.getNormalizedType());
			boolean requestBodyExpectsByteArray = byte[].class.equals(parameterType.getNormalizedType());

			if (requestBodyExpectsString) {
				String requestBodyAsString = request.getBodyAsString().orElse(null);

				if (parameterType.isOptional())
					return Optional.ofNullable(requestBodyAsString);

				if (requestBodyAsString == null)
					throw new MissingRequestBodyException("A request body is required for this resource.");

				return requestBodyAsString;
			} else if (requestBodyExpectsByteArray) {
				byte[] requestBodyAsByteArray = request.getBody().orElse(null);

				if (parameterType.isOptional())
					return Optional.ofNullable(requestBodyAsByteArray);

				if (requestBodyAsByteArray == null)
					throw new MissingRequestBodyException("A request body is required for this resource.");

				return requestBodyAsByteArray;
			} else {
				// Let the request body marshaler try to handle it
				Object requestBodyObject;
				Type requestBodyType = parameterType.getNormalizedType();

				try {
					requestBodyObject = getRequestBodyMarshaler().marshalRequestBody(request, requestBodyType);
				} catch (IllegalRequestBodyException e) {
					throw e;
				} catch (Exception e) {
					throw new IllegalRequestBodyException(format("Unable to marshal request body to %s", requestBodyType), e);
				}

				if (parameterType.isOptional())
					return Optional.ofNullable(requestBodyObject);

				if (requestBodyObject == null)
					throw new MissingRequestBodyException("A request body is required for this resource.");

				return requestBodyObject;
			}
		}

		// Don't recognize what's being asked for? Have the InstanceProvider try to vend something
		if (parameterType.isOptional())
			return Optional.ofNullable(getInstanceProvider().provide(parameter.getType()));
		else
			return getInstanceProvider().provide(parameter.getType());
	}

	@Nonnull
	protected String extractParameterName(@Nonnull ResourceMethod resourceMethod,
																				@Nonnull Parameter parameter,
																				@Nonnull Annotation annotation,
																				@Nonnull String annotationValue) {
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(annotation);
		requireNonNull(annotationValue);

		String parameterName = trimAggressivelyToNull(annotationValue);

		if (parameterName == null && parameter.isNamePresent())
			parameterName = parameter.getName();

		if (parameterName == null)
			throw new IllegalArgumentException(
					format(
							"Unable to automatically detect resource method parameter name. "
									+ "You must either explicitly specify a @%s value for parameter %s - for example, @%s(\"name-goes-here\") - "
									+ "or compile with javac flag \"-parameters\" to preserve parameter names for reflection. Offending resource method was %s",
							annotation.annotationType().getSimpleName(), parameter, annotation.annotationType().getSimpleName(), resourceMethod));

		return parameterName;
	}

	@Nonnull
	protected Object extractQueryParameterValue(@Nonnull Request request,
																							@Nonnull ResourceMethod resourceMethod,
																							@Nonnull Parameter parameter,
																							@Nonnull QueryParameter queryParameter,
																							@Nonnull ParameterType parameterType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(queryParameter);
		requireNonNull(parameterType);

		String name = extractParameterName(resourceMethod, parameter, queryParameter, queryParameter.value());
		Set<String> values = request.getQueryParameters().get(name);

		if (values == null)
			values = Set.of();

		return extractRequestValue(request, resourceMethod, parameter, parameterType, name, new ArrayList<>(values), "query parameter", (
				message, ignored) -> {
			return new MissingQueryParameterException(message, name);
		}, (message, cause, ignored, value, valueMetadatum) -> {
			return new IllegalQueryParameterException(message, cause, name, value);
		});
	}

	@Nonnull
	protected Object extractFormParameterValue(@Nonnull Request request,
																						 @Nonnull ResourceMethod resourceMethod,
																						 @Nonnull Parameter parameter,
																						 @Nonnull FormParameter formParameter,
																						 @Nonnull ParameterType parameterType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(formParameter);
		requireNonNull(parameterType);

		String name = extractParameterName(resourceMethod, parameter, formParameter, formParameter.value());
		Set<String> values = null;
		String requestBodyAsString = request.getBodyAsString().orElse(null);

		if (requestBodyAsString != null) {
			Map<String, Set<String>> formParameters = Utilities.extractQueryParametersFromQuery(requestBodyAsString);
			values = formParameters.get(name);
		}

		if (values == null)
			values = Set.of();

		return extractRequestValue(request, resourceMethod, parameter, parameterType, name, new ArrayList<>(values), "form parameter", (
				message, ignored) -> {
			return new MissingFormParameterException(message, name);
		}, (message, cause, ignored, value, valueMetadatum) -> {
			return new IllegalFormParameterException(message, cause, name, value);
		});
	}

	@Nonnull
	protected Object extractRequestHeaderValue(@Nonnull Request request,
																						 @Nonnull ResourceMethod resourceMethod,
																						 @Nonnull Parameter parameter,
																						 @Nonnull RequestHeader requestHeader,
																						 @Nonnull ParameterType parameterType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(requestHeader);
		requireNonNull(parameterType);

		String name = extractParameterName(resourceMethod, parameter, requestHeader, requestHeader.value());
		Set<String> values = request.getHeaders().get(name);

		if (values == null)
			values = Set.of();

		return extractRequestValue(request, resourceMethod, parameter, parameterType, name, new ArrayList<>(values), "request header", (
				message, ignored) -> {
			return new MissingRequestHeaderException(message, name);
		}, (message, cause, ignored, value, valueMetadatum) -> {
			return new IllegalRequestHeaderException(message, cause, name, value);
		});
	}

	@Nonnull
	protected Object extractRequestCookieValue(@Nonnull Request request,
																						 @Nonnull ResourceMethod resourceMethod,
																						 @Nonnull Parameter parameter,
																						 @Nonnull RequestCookie requestCookie,
																						 @Nonnull ParameterType parameterType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(requestCookie);
		requireNonNull(parameterType);

		String name = extractParameterName(resourceMethod, parameter, requestCookie, requestCookie.value());
		Set<String> values = request.getCookies().get(name);

		if (values == null)
			values = Set.of();

		return extractRequestValue(request, resourceMethod, parameter, parameterType, name, new ArrayList<>(values), "request cookie", (
				message, ignored) -> {
			return new MissingRequestCookieException(message, name);
		}, (message, cause, ignored, value, valueMetadatum) -> {
			return new IllegalRequestCookieException(message, cause, name, value);
		});
	}

	@Nonnull
	protected Object extractRequestValue(@Nonnull Request request,
																			 @Nonnull ResourceMethod resourceMethod,
																			 @Nonnull Parameter parameter,
																			 @Nonnull ParameterType parameterType,
																			 @Nonnull String name,
																			 @Nonnull List<String> values,
																			 @Nonnull String description,
																			 @Nonnull MissingExceptionProvider missingExceptionProvider,
																			 @Nonnull IllegalExceptionProvider illegalExceptionProvider) {
		return extractRequestValue(request, resourceMethod, parameter, parameterType, name, values, List.of(), false,
				description, missingExceptionProvider, illegalExceptionProvider);
	}

	@Nonnull
	protected Object extractRequestValue(@Nonnull Request request,
																			 @Nonnull ResourceMethod resourceMethod,
																			 @Nonnull Parameter parameter,
																			 @Nonnull ParameterType parameterType,
																			 @Nonnull String name,
																			 @Nonnull List<String> values,
																			 @Nonnull List<?> valuesMetadata,
																			 @Nonnull Boolean returnMetadataInsteadOfValues,
																			 @Nonnull String description,
																			 @Nonnull MissingExceptionProvider missingExceptionProvider,
																			 @Nonnull IllegalExceptionProvider illegalExceptionProvider) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(parameterType);
		requireNonNull(name);
		requireNonNull(values);
		requireNonNull(valuesMetadata);
		requireNonNull(description);
		requireNonNull(missingExceptionProvider);
		requireNonNull(illegalExceptionProvider);

		Type toType = parameterType.isList() ? parameterType.getListElementType().get() : parameterType.getNormalizedType();
		ValueConverter<Object, Object> valueConverter = getValueConverterRegistry().get(String.class, toType).orElse(null);

		if (valueConverter == null && !returnMetadataInsteadOfValues)
			throwValueConverterMissingException(parameter, String.class, toType, resourceMethod);

		// Special handling for Lists (support for multiple query parameters/headers/cookies with the same name)
		if (parameterType.isList()) {
			List<Object> results = new ArrayList<>(values.size());

			if (returnMetadataInsteadOfValues) {
				results.addAll(valuesMetadata);
			} else {
				for (int i = 0; i < values.size(); ++i) {
					String value = values.get(i);

					if (value != null && trimAggressively(value).length() > 0)
						try {
							results.add(valueConverter.convert(value));
						} catch (ValueConversionException e) {
							throw illegalExceptionProvider.provide(
									format("Illegal value '%s' was specified for %s '%s' (was expecting a value convertible to %s)", value,
											description, name, valueConverter.getToType()), e, name, value, Optional
											.ofNullable(valuesMetadata.size() > i ? valuesMetadata.get(i) : null));
						}
				}
			}

			if (!parameterType.isOptional() && results.size() == 0)
				throw missingExceptionProvider.provide(format("Required %s '%s' was not specified.", description, name), name);

			return parameterType.isOptional() ? (results.size() == 0 ? Optional.empty() : Optional.of(results)) : results;
		}

		// Non-list support
		Object result;

		if (returnMetadataInsteadOfValues) {
			result = valuesMetadata.size() > 0 ? valuesMetadata.get(0) : null;

			if (!parameterType.isOptional() && result == null)
				throw missingExceptionProvider.provide(format("Required %s '%s' was not specified.", description, name), name);
		} else {
			String value = values.size() > 0 ? values.get(0) : null;

			if (value != null && trimAggressively(value).length() == 0) value = null;

			if (!parameterType.isOptional() && value == null)
				throw missingExceptionProvider.provide(format("Required %s '%s' was not specified.", description, name), name);

			try {
				result = valueConverter.convert(value);
			} catch (ValueConversionException e) {
				throw illegalExceptionProvider.provide(
						format("Illegal value '%s' was specified for %s '%s' (was expecting a value convertible to %s)", value,
								description, name, valueConverter.getToType()), e, name, value, Optional
								.ofNullable(valuesMetadata.size() > 0 ? valuesMetadata.get(0) : null));
			}
		}

		return parameterType.isOptional() ? Optional.ofNullable(result) : result;
	}

	protected void throwValueConverterMissingException(@Nonnull Parameter parameter,
																										 @Nonnull Type fromType,
																										 @Nonnull Type toType,
																										 @Nonnull ResourceMethod resourceMethod) {
		requireNonNull(parameter);
		requireNonNull(fromType);
		requireNonNull(toType);
		requireNonNull(resourceMethod);

		throw new IllegalArgumentException(format(
				"No %s is registered for converting %s to %s for parameter '%s' in resource method %s ",
				ValueConverter.class.getSimpleName(), fromType, toType, parameter, resourceMethod));
	}

	@Nonnull
	protected InstanceProvider getInstanceProvider() {
		return this.instanceProvider;
	}

	@Nonnull
	protected ValueConverterRegistry getValueConverterRegistry() {
		return this.valueConverterRegistry;
	}

	@Nonnull
	protected RequestBodyMarshaler getRequestBodyMarshaler() {
		return this.requestBodyMarshaler;
	}

	@FunctionalInterface
	protected interface MissingExceptionProvider {
		@Nonnull
		RuntimeException provide(@Nonnull String message,
														 @Nonnull String name);
	}

	@FunctionalInterface
	protected interface IllegalExceptionProvider {
		@Nonnull
		RuntimeException provide(@Nonnull String message,
														 @Nonnull Exception cause,
														 @Nonnull String name,
														 @Nullable String value,
														 @Nullable Object valueMetadatum);
	}

	@ThreadSafe
	protected static class ParameterType {
		@Nonnull
		private final Type normalizedType;
		@Nullable
		private final Type listElementType;
		@Nonnull
		private final Boolean optional;

		public ParameterType(@Nonnull Parameter parameter) {
			requireNonNull(parameter);

			Type normalizedType = parameter.getParameterizedType();
			Type listElementType = null;
			boolean optional = false;

			if (parameter.getType().isAssignableFrom(Optional.class)) {
				normalizedType = ((ParameterizedType) parameter.getParameterizedType()).getActualTypeArguments()[0];
				optional = true;
			}

			// Gross hack to determine if this property is a generic List
			if (ParameterizedType.class.isAssignableFrom(normalizedType.getClass())
					&& normalizedType.getTypeName().startsWith(List.class.getName() + "<"))
				listElementType = ((ParameterizedType) normalizedType).getActualTypeArguments()[0];

			this.normalizedType = normalizedType;
			this.listElementType = listElementType;
			this.optional = optional;
		}

		@Nonnull
		public Type getNormalizedType() {
			return this.normalizedType;
		}

		@Nonnull
		public Optional<Type> getListElementType() {
			return Optional.ofNullable(this.listElementType);
		}

		@Nonnull
		public Boolean isList() {
			return getListElementType().isPresent();
		}

		@Nonnull
		public Boolean isOptional() {
			return this.optional;
		}
	}
}
