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

package com.soklet;

import com.soklet.annotation.FormParameter;
import com.soklet.annotation.Multipart;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.RequestCookie;
import com.soklet.annotation.RequestHeader;
import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import com.soklet.exception.BadRequestException;
import com.soklet.exception.IllegalFormParameterException;
import com.soklet.exception.IllegalMultipartFieldException;
import com.soklet.exception.IllegalPathParameterException;
import com.soklet.exception.IllegalQueryParameterException;
import com.soklet.exception.IllegalRequestBodyException;
import com.soklet.exception.IllegalRequestCookieException;
import com.soklet.exception.IllegalRequestHeaderException;
import com.soklet.exception.MissingFormParameterException;
import com.soklet.exception.MissingMultipartFieldException;
import com.soklet.exception.MissingQueryParameterException;
import com.soklet.exception.MissingRequestBodyException;
import com.soklet.exception.MissingRequestCookieException;
import com.soklet.exception.MissingRequestHeaderException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.soklet.Utilities.trimAggressively;
import static com.soklet.Utilities.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultResourceMethodParameterProvider implements ResourceMethodParameterProvider {
	@NonNull
	private static final Map<Type, Object> DEFAULT_VALUES_BY_PRIMITIVE_TYPE;

	static {
		// See https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
		DEFAULT_VALUES_BY_PRIMITIVE_TYPE = Map.of(
				byte.class, (byte) 0,
				short.class, (short) 0,
				int.class, 0,
				long.class, (long) 0,
				float.class, (float) 0,
				double.class, (double) 0,
				char.class, '\u0000',
				boolean.class, false
		);
	}

	@NonNull
	private final SokletConfig sokletConfig;

	public DefaultResourceMethodParameterProvider(@NonNull SokletConfig sokletConfig) {
		requireNonNull(sokletConfig);
		this.sokletConfig = sokletConfig;
	}

	@NonNull
	@Override
	public List<Object> parameterValuesForResourceMethod(@NonNull Request request,
																											 @NonNull ResourceMethod resourceMethod) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(sokletConfig);

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
	protected Object extractParameterValueToPassToResourceMethod(@NonNull Request request,
																															 @NonNull ResourceMethod resourceMethod,
																															 @NonNull Parameter parameter) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);

		SokletConfig sokletConfig = getSokletConfig();

		// First, support a few special injections based on type.
		Class<?> basicParameterType = parameter.getType();

		if (basicParameterType.isAssignableFrom(Request.class))
			return request;

		if (basicParameterType.isAssignableFrom(ResourceMethod.class))
			return resourceMethod;

		if (basicParameterType.isAssignableFrom(InstanceProvider.class))
			return sokletConfig.getInstanceProvider();

		if (basicParameterType.isAssignableFrom(RequestBodyMarshaler.class))
			return sokletConfig.getRequestBodyMarshaler();

		if (basicParameterType.isAssignableFrom(ResponseMarshaler.class))
			return sokletConfig.getResponseMarshaler();

		if (basicParameterType.isAssignableFrom(ValueConverterRegistry.class))
			return sokletConfig.getValueConverterRegistry();

		if (basicParameterType.isAssignableFrom(ResourceMethodResolver.class))
			return sokletConfig.getResourceMethodResolver();

		if (basicParameterType.isAssignableFrom(ResourceMethodParameterProvider.class))
			return sokletConfig.getResourceMethodParameterProvider();

		if (basicParameterType.isAssignableFrom(LifecycleObserver.class))
			return sokletConfig.getLifecycleObserver();

		if (basicParameterType.isAssignableFrom(RequestInterceptor.class))
			return sokletConfig.getRequestInterceptor();

		if (basicParameterType.isAssignableFrom(CorsAuthorizer.class))
			return sokletConfig.getCorsAuthorizer();

		if (basicParameterType.isAssignableFrom(Server.class))
			return sokletConfig.getServer();

		if (basicParameterType.isAssignableFrom(ServerSentEventServer.class)) {
			ServerSentEventServer serverSentEventServer = sokletConfig.getServerSentEventServer().orElse(null);

			if (serverSentEventServer == null)
				throw new IllegalStateException(format("You cannot inject a parameter of type %s because your %s instance is not configured with one. Offending resource method: %s",
						ServerSentEventServer.class.getSimpleName(), Soklet.class.getSimpleName(), resourceMethod));

			return serverSentEventServer;
		}

		// Ok, we're done with the basic by-type injections.
		// Now, examine annotation data to determine special injections (e.g. query parameter value, request body, ...)

		ParameterType parameterType = new ParameterType(parameter);
		PathParameter pathParameter = parameter.getAnnotation(PathParameter.class);

		if (pathParameter != null) {
			if (parameterType.isWrappedInOptional())
				throw new IllegalStateException(format("@%s-annotated parameters cannot be marked %s. Offending resource method: %s",
						PathParameter.class.getSimpleName(), Optional.class.getSimpleName(), resourceMethod));

			String pathParameterName = extractParameterName(resourceMethod, parameter, pathParameter, pathParameter.name());
			ResourcePath resourcePath = request.getResourcePath();

			Map<String, String> valuesByPathParameter = resourceMethod.getResourcePathDeclaration().extractPlaceholders(resourcePath);
			String pathParameterValue = valuesByPathParameter.get(pathParameterName);

			if (pathParameterValue == null)
				throw new IllegalStateException(format("Missing value for path parameter '%s' for resource method %s",
						pathParameterName, resourceMethod));

			// Special check for varargs: it must be of type String
			ResourcePathDeclaration.Component varargsComponent = resourceMethod.getResourcePathDeclaration().getVarargsComponent().orElse(null);

			if (varargsComponent != null
					&& Objects.equals(varargsComponent.getValue(), pathParameterName)
					&& !parameter.getType().equals(String.class))
				throw new IllegalStateException(format("Path parameter '%s' for resource method %s is defined as supporting varargs. Its type was declared as %s, but varargs path parameters must be of type %s.",
						pathParameterName, resourceMethod, parameter.getType(), String.class));

			ValueConverter<Object, Object> valueConverter = getSokletConfig().getValueConverterRegistry().get(String.class, parameter.getType()).orElse(null);

			if (valueConverter == null)
				throwValueConverterMissingException(parameter, String.class, parameter.getType(), resourceMethod);

			Object result;

			try {
				Optional<Object> valueConverterResult = valueConverter.convert(pathParameterValue);
				result = valueConverterResult == null ? null : valueConverterResult.orElse(null);
			} catch (Exception e) {
				throw new IllegalPathParameterException(format("Illegal value '%s' was specified for path parameter '%s' (was expecting a value convertible to %s)",
						pathParameterValue, pathParameterName, valueConverter.getToType()), e, pathParameterName, pathParameterValue);
			}

			if (result == null)
				throw new IllegalPathParameterException(format("No value was specified for path parameter '%s' (was expecting a value convertible to %s)",
						pathParameterName, valueConverter.getToType()), pathParameterName, pathParameterValue);

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

		Multipart multipart = parameter.getAnnotation(Multipart.class);

		String multipartFieldTypeName = MultipartField.class.getTypeName();
		boolean isMultipartScalarType = multipartFieldTypeName.equals(parameterType.getNormalizedType().getTypeName());
		boolean isMultipartListType = parameterType.getListElementType().isPresent()
				&& multipartFieldTypeName.equals(parameterType.getListElementType().get().getTypeName());

		// Multipart is either indicated by @Multipart annotation or the parameter is of type MultipartField
		if (multipart != null || (isMultipartScalarType || isMultipartListType))
			return extractRequestMultipartValue(request, resourceMethod, parameter, multipart, parameterType);

		RequestBody requestBody = parameter.getAnnotation(RequestBody.class);

		if (requestBody != null) {
			boolean requestBodyOptional = requestBody.optional() || parameterType.isWrappedInOptional();
			boolean requestBodyExpectsString = String.class.equals(parameterType.getNormalizedType());
			boolean requestBodyExpectsByteArray = byte[].class.equals(parameterType.getNormalizedType());

			if (requestBodyExpectsString) {
				String requestBodyAsString = request.getBodyAsString().orElse(null);

				if (parameterType.isWrappedInOptional())
					return Optional.ofNullable(requestBodyAsString);

				if (!requestBodyOptional && requestBodyAsString == null)
					throw new MissingRequestBodyException("A request body is required for this resource.");

				return requestBodyAsString;
			} else if (requestBodyExpectsByteArray) {
				byte[] requestBodyAsByteArray = request.getBody().orElse(null);

				if (parameterType.isWrappedInOptional())
					return Optional.ofNullable(requestBodyAsByteArray);

				if (!requestBodyOptional && requestBodyAsByteArray == null)
					throw new MissingRequestBodyException("A request body is required for this resource.");

				return requestBodyAsByteArray;
			} else {
				// Short circuit: optional type and no request body
				if (parameterType.isWrappedInOptional() && request.getBody().isEmpty())
					return Optional.empty();

				// Short circuit: marked optional and no request body
				if (requestBodyOptional && request.getBody().isEmpty())
					return defaultValueForType(parameterType.getNormalizedType()).orElse(null);

				// Short circuit: not optional and no request body
				if (!requestBodyOptional && request.getBody().isEmpty())
					throw new MissingRequestBodyException("A request body is required for this resource.");

				// Let the request body marshaler try to handle it
				Object requestBodyObject;
				Type requestBodyType = parameterType.getNormalizedType();

				try {
					Optional<Object> marshaledRequestBody = getSokletConfig().getRequestBodyMarshaler().marshalRequestBody(request, resourceMethod, parameter, requestBodyType);
					requestBodyObject = marshaledRequestBody == null ? null : marshaledRequestBody.orElse(null);
				} catch (IllegalRequestBodyException e) {
					throw e;
				} catch (Exception e) {
					throw new IllegalRequestBodyException(format("Unable to marshal request body to %s", requestBodyType), e);
				}

				if (parameterType.isWrappedInOptional())
					return Optional.ofNullable(requestBodyObject);

				if (!requestBodyOptional && requestBodyObject == null)
					throw new MissingRequestBodyException("Request body is required for this resource, but it was marshaled to null");

				return requestBodyObject;
			}
		}

		// Don't recognize what's being asked for? Have the InstanceProvider try to vend something
		if (parameterType.isWrappedInOptional())
			return Optional.ofNullable(getSokletConfig().getInstanceProvider().provide(parameter));
		else
			return getSokletConfig().getInstanceProvider().provide(parameter);
	}

	/**
	 * What "default" value does the JDK use for an unassigned field of the given type?
	 * <p>
	 * For example, a primitive type like int defaults to 0 but a java.util.List would be null.
	 * <p>
	 * See https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
	 *
	 * @param type the type whose default value we'd like to know
	 * @return the default value for the type, or an empty optional if no default exists (i.e. is null)
	 */
	@NonNull
	protected Optional<Object> defaultValueForType(@Nullable Type type) {
		if (type == null)
			return Optional.empty();

		return Optional.ofNullable(DEFAULT_VALUES_BY_PRIMITIVE_TYPE.get(type));
	}

	@NonNull
	protected String extractParameterName(@NonNull ResourceMethod resourceMethod,
																				@NonNull Parameter parameter,
																				@Nullable Annotation annotation,
																				@Nullable String annotationValue) {
		requireNonNull(resourceMethod);
		requireNonNull(parameter);

		String parameterName = trimAggressivelyToNull(annotationValue);

		if (parameterName == null && parameter.isNamePresent())
			parameterName = parameter.getName();

		if (parameterName == null) {
			String message;

			if (annotation == null)
				message = format(
						"Unable to automatically detect resource method parameter name. "
								+ "You must compile with javac flag \"-parameters\" to preserve parameter names for reflection. Offending resource method was %s",
						resourceMethod);
			else
				message = format(
						"Unable to automatically detect resource method parameter name. "
								+ "You must either explicitly specify a @%s value for parameter %s - for example, @%s(\"name-goes-here\") - "
								+ "or compile with javac flag \"-parameters\" to preserve parameter names for reflection. Offending resource method was %s",
						annotation.annotationType().getSimpleName(), parameter, annotation.annotationType().getSimpleName(), resourceMethod);

			throw new IllegalArgumentException(message);
		}

		return parameterName;
	}

	@Nullable
	@SuppressWarnings("unchecked")
	protected Object extractQueryParameterValue(@NonNull Request request,
																							@NonNull ResourceMethod resourceMethod,
																							@NonNull Parameter parameter,
																							@NonNull QueryParameter queryParameter,
																							@NonNull ParameterType parameterType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(queryParameter);
		requireNonNull(parameterType);

		String parameterDescription = "query parameter";
		String parameterName = extractParameterName(resourceMethod, parameter, queryParameter, queryParameter.name());
		Set<String> values = request.getQueryParameters().get(parameterName);

		if (values == null)
			values = Set.of();

		RequestValueExtractionConfig<String> requestValueExtractionConfig = new RequestValueExtractionConfig.Builder<>(resourceMethod, parameter, parameterType, parameterName, parameterDescription)
				.optional(queryParameter.optional())
				.values(new ArrayList<>(values))
				.missingExceptionProvider((message, name) -> new MissingQueryParameterException(message, parameterName))
				.illegalExceptionProvider((message, cause, name, value, valueMetadatum) -> new IllegalQueryParameterException(message, cause, parameterName, value))
				.build();

		return extractRequestValue(requestValueExtractionConfig);
	}

	@Nullable
	@SuppressWarnings("unchecked")
	protected Object extractFormParameterValue(@NonNull Request request,
																						 @NonNull ResourceMethod resourceMethod,
																						 @NonNull Parameter parameter,
																						 @NonNull FormParameter formParameter,
																						 @NonNull ParameterType parameterType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(formParameter);
		requireNonNull(parameterType);

		String parameterDescription = "form parameter";
		String parameterName = extractParameterName(resourceMethod, parameter, formParameter, formParameter.name());
		Set<String> values = request.getFormParameters().get(parameterName);

		if (values == null)
			values = Set.of();

		RequestValueExtractionConfig<String> requestValueExtractionConfig = new RequestValueExtractionConfig.Builder<>(resourceMethod, parameter, parameterType, parameterName, parameterDescription)
				.optional(formParameter.optional())
				.values(new ArrayList<>(values))
				.missingExceptionProvider((message, name) -> new MissingFormParameterException(message, parameterName))
				.illegalExceptionProvider((message, cause, name, value, valueMetadatum) -> new IllegalFormParameterException(message, cause, parameterName, value))
				.build();

		return extractRequestValue(requestValueExtractionConfig);
	}

	@Nullable
	@SuppressWarnings("unchecked")
	protected Object extractRequestHeaderValue(@NonNull Request request,
																						 @NonNull ResourceMethod resourceMethod,
																						 @NonNull Parameter parameter,
																						 @NonNull RequestHeader requestHeader,
																						 @NonNull ParameterType parameterType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(requestHeader);
		requireNonNull(parameterType);

		String parameterDescription = "request header";
		String parameterName = extractParameterName(resourceMethod, parameter, requestHeader, requestHeader.name());
		Set<String> values = request.getHeaders().get(parameterName);

		if (values == null)
			values = Set.of();

		RequestValueExtractionConfig<String> requestValueExtractionConfig = new RequestValueExtractionConfig.Builder<>(resourceMethod, parameter, parameterType, parameterName, parameterDescription)
				.optional(requestHeader.optional())
				.values(new ArrayList<>(values))
				.missingExceptionProvider((message, name) -> new MissingRequestHeaderException(message, parameterName))
				.illegalExceptionProvider((message, cause, name, value, valueMetadatum) -> new IllegalRequestHeaderException(message, cause, parameterName, value))
				.build();

		return extractRequestValue(requestValueExtractionConfig);
	}

	@Nullable
	@SuppressWarnings("unchecked")
	protected Object extractRequestCookieValue(@NonNull Request request,
																						 @NonNull ResourceMethod resourceMethod,
																						 @NonNull Parameter parameter,
																						 @NonNull RequestCookie requestCookie,
																						 @NonNull ParameterType parameterType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(requestCookie);
		requireNonNull(parameterType);

		String parameterDescription = "request cookie";
		String parameterName = extractParameterName(resourceMethod, parameter, requestCookie, requestCookie.name());
		Set<String> values = request.getCookies().get(parameterName);

		if (values == null)
			values = Set.of();

		RequestValueExtractionConfig<String> requestValueExtractionConfig = new RequestValueExtractionConfig.Builder<>(resourceMethod, parameter, parameterType, parameterName, parameterDescription)
				.optional(requestCookie.optional())
				.values(new ArrayList<>(values))
				.missingExceptionProvider((message, name) -> new MissingRequestCookieException(message, parameterName))
				.illegalExceptionProvider((message, cause, name, value, valueMetadatum) -> new IllegalRequestCookieException(message, cause, parameterName, value))
				.build();

		return extractRequestValue(requestValueExtractionConfig);
	}

	@NonNull
	@SuppressWarnings("unchecked")
	protected Object extractRequestMultipartValue(@NonNull Request request,
																								@NonNull ResourceMethod resourceMethod,
																								@NonNull Parameter parameter,
																								@Nullable Multipart multipart,
																								@NonNull ParameterType parameterType) {
		requireNonNull(request);
		requireNonNull(resourceMethod);
		requireNonNull(parameter);
		requireNonNull(parameterType);

		String parameterDescription = "multipart field";
		String parameterName = extractParameterName(resourceMethod, parameter, multipart, multipart == null ? null : multipart.name());

		List<String> values = new ArrayList<>();
		List<MultipartField> valuesMetadata = new ArrayList<>();

		for (Map.Entry<String, Set<MultipartField>> entry : request.getMultipartFields().entrySet()) {
			String multipartName = entry.getKey();

			if (parameterName.equals(multipartName)) {
				Set<MultipartField> multipartFields = entry.getValue();

				for (MultipartField matchingMultipartField : multipartFields) {
					values.add(matchingMultipartField.getDataAsString().orElse(null));
					valuesMetadata.add(matchingMultipartField);
				}
			}
		}

		ValueMetadatumConverter<MultipartField> valueMetadatumConverter = (MultipartField multipartField, Type toType, ValueConverter<Object, Object> valueConverter) -> {
			if (toType.equals(MultipartField.class))
				return multipartField;

			if (toType.equals(String.class))
				return multipartField.getDataAsString().orElse(null);

			if (toType.equals(byte[].class))
				return multipartField.getData().orElse(null);

			Optional<Object> valueConverterResult = valueConverter.convert(multipartField.getDataAsString().orElse(null));
			return valueConverterResult == null ? null : valueConverterResult.orElse(null);
		};

		RequestValueExtractionConfig<MultipartField> requestValueExtractionConfig = new RequestValueExtractionConfig.Builder<>(resourceMethod, parameter, parameterType, parameterName, parameterDescription)
				.optional(multipart == null ? false : multipart.optional())
				.values(new ArrayList<>(values))
				.valuesMetadata(valuesMetadata)
				.valueMetadatumConverter(valueMetadatumConverter)
				.missingExceptionProvider((message, name) -> new MissingMultipartFieldException(message, parameterName))
				.illegalExceptionProvider((message, cause, name, value, valueMetadatum) -> new IllegalMultipartFieldException(message, cause, ((Optional<MultipartField>) valueMetadatum).orElse(null)))
				.build();

		return extractRequestValue(requestValueExtractionConfig);
	}

	@NonNull
	@SuppressWarnings("unchecked")
	protected <T> Object extractRequestValue(@NonNull RequestValueExtractionConfig<T> requestValueExtractionConfig) {
		requireNonNull(requestValueExtractionConfig);

		ResourceMethod resourceMethod = requestValueExtractionConfig.getResourceMethod();
		Parameter parameter = requestValueExtractionConfig.getParameter();
		ParameterType parameterType = requestValueExtractionConfig.getParameterType();
		String parameterName = requestValueExtractionConfig.getParameterName();
		String parameterDescription = requestValueExtractionConfig.getParameterDescription();
		boolean optional = requestValueExtractionConfig.getOptional();
		List<String> values = requestValueExtractionConfig.getValues();
		List<T> valuesMetadata = requestValueExtractionConfig.getValuesMetadata();
		ValueMetadatumConverter<T> valueMetadatumConverter = requestValueExtractionConfig.getValueMetadatumConverter().orElse(null);
		MissingExceptionProvider missingExceptionProvider = requestValueExtractionConfig.getMissingExceptionProvider();
		IllegalExceptionProvider illegalExceptionProvider = requestValueExtractionConfig.getIllegalExceptionProvider();

		boolean returnMetadataInsteadOfValues = valueMetadatumConverter != null;
		Type toType = parameterType.isList() ? parameterType.getListElementType().get() : parameterType.getNormalizedType();

		ValueConverter<Object, Object> valueConverter = getSokletConfig().getValueConverterRegistry().get(String.class, toType).orElse(null);

		if (valueConverter == null && !returnMetadataInsteadOfValues)
			throwValueConverterMissingException(parameter, String.class, toType, resourceMethod);

		// Special handling for Lists (support for multiple query parameters/headers/cookies with the same name)
		if (parameterType.isList()) {
			List<Object> results = new ArrayList<>(values.size());

			if (returnMetadataInsteadOfValues) {
				for (int i = 0; i < valuesMetadata.size(); ++i) {
					Object valueMetadatum = valuesMetadata.get(i);

					if (valueMetadatum != null)
						try {
							valueMetadatum = valueMetadatumConverter.convert((T) valueMetadatum, toType, valueConverter);
							results.add(valueMetadatum);
						} catch (ValueConversionException e) {
							throw illegalExceptionProvider.provide(
									format("Illegal value '%s' was specified for %s '%s' (was expecting a value convertible to %s)", valueMetadatum,
											parameterDescription, parameterName, valueConverter.getToType()), e, parameterName, null, Optional
											.ofNullable(valuesMetadata.size() > i ? valuesMetadata.get(i) : null));
						}
				}
			} else {
				for (int i = 0; i < values.size(); ++i) {
					String value = values.get(i);

					if (value != null && trimAggressively(value).length() > 0)
						try {
							Optional<Object> valueConverterResult = valueConverter.convert(value);
							results.add(valueConverterResult == null ? null : valueConverterResult.orElse(null));
						} catch (ValueConversionException e) {
							throw illegalExceptionProvider.provide(
									format("Illegal value '%s' was specified for %s '%s' (was expecting a value convertible to %s)", value,
											parameterDescription, parameterName, valueConverter.getToType()), e, parameterName, value, Optional
											.ofNullable(valuesMetadata.size() > i ? valuesMetadata.get(i) : null));
						}
				}
			}

			boolean required = !parameterType.isWrappedInOptional() && !optional;

			if (required && results.size() == 0)
				throw missingExceptionProvider.provide(format("Required %s '%s' was not specified.", parameterDescription, parameterName), parameterName);

			return parameterType.isWrappedInOptional() ? (results.size() == 0 ? Optional.empty() : Optional.of(results)) : results;
		}

		// Non-list support
		Object result;

		if (returnMetadataInsteadOfValues) {
			// Check for multiple values when parameter is not a List and fail-fast instead of just picking the first value
			if (valuesMetadata.size() > 1) {
				String valuesAsString = format("[%s]", valuesMetadata.stream()
						.map(Objects::toString)
						.collect(Collectors.joining(", ")));

				throw illegalExceptionProvider.provide(
						format("Multiple values specified for %s '%s' (but expected single value): %s",
								parameterDescription, parameterName, valuesAsString),
						new IllegalArgumentException("Multiple values provided for single-value parameter"),
						parameterName, valuesAsString, Optional.empty());
			}

			result = valuesMetadata.size() > 0 ? valuesMetadata.get(0) : null;

			if (result != null) {
				try {
					result = valueMetadatumConverter.convert((T) result, toType, valueConverter);
				} catch (ValueConversionException e) {
					throw illegalExceptionProvider.provide(
							format("Illegal value '%s' was specified for %s '%s' (was expecting a value convertible to %s)", result,
									parameterDescription, parameterName, valueConverter.getToType()), e, parameterName, null, Optional
									.ofNullable(valuesMetadata.size() > 0 ? valuesMetadata.get(0) : null));
				}
			}

			boolean required = !parameterType.isWrappedInOptional() && !optional;

			if (required && result == null)
				throw missingExceptionProvider.provide(format("Required %s '%s' was not specified.", parameterDescription, parameterName), parameterName);
		} else {
			// Check for multiple values when parameter is not a List and fail-fast instead of just picking the first value
			if (values.size() > 1) {
				String valuesAsString = format("[%s]", String.join(", ", values));

				throw illegalExceptionProvider.provide(
						format("Multiple values specified for %s '%s' (but expected single value): %s",
								parameterDescription, parameterName, valuesAsString),
						new IllegalArgumentException("Multiple values provided for single-value parameter"),
						parameterName, valuesAsString, Optional.empty());
			}

			String value = values.size() > 0 ? values.get(0) : null;

			if (value != null && trimAggressively(value).length() == 0) value = null;

			boolean required = !parameterType.isWrappedInOptional() && !optional;

			if (required && value == null)
				throw missingExceptionProvider.provide(format("Required %s '%s' was not specified.", parameterDescription, parameterName), parameterName);

			try {
				Optional<Object> valueConverterResult = valueConverter.convert(value);
				result = valueConverterResult == null ? null : valueConverterResult.orElse(null);
			} catch (ValueConversionException e) {
				throw illegalExceptionProvider.provide(
						format("Illegal value '%s' was specified for %s '%s' (was expecting a value convertible to %s)", value,
								parameterDescription, parameterName, valueConverter.getToType()), e, parameterName, value, Optional
								.ofNullable(valuesMetadata.size() > 0 ? valuesMetadata.get(0) : null));
			}
		}

		return parameterType.isWrappedInOptional() ? Optional.ofNullable(result) : result;
	}

	protected void throwValueConverterMissingException(@NonNull Parameter parameter,
																										 @NonNull Type fromType,
																										 @NonNull Type toType,
																										 @NonNull ResourceMethod resourceMethod) {
		requireNonNull(parameter);
		requireNonNull(fromType);
		requireNonNull(toType);
		requireNonNull(resourceMethod);

		throw new IllegalArgumentException(format(
				"No %s is registered for converting %s to %s for parameter '%s' in resource method %s ",
				ValueConverter.class.getSimpleName(), fromType, toType, parameter, resourceMethod));
	}

	@NonNull
	protected SokletConfig getSokletConfig() {
		return this.sokletConfig;
	}

	@FunctionalInterface
	protected interface MissingExceptionProvider {
		@NonNull
		RuntimeException provide(@NonNull String message,
														 @NonNull String name);
	}

	@FunctionalInterface
	protected interface IllegalExceptionProvider<T> {
		@NonNull
		RuntimeException provide(@NonNull String message,
														 @NonNull Exception cause,
														 @NonNull String name,
														 @Nullable String value,
														 @Nullable T valueMetadatum);
	}

	@FunctionalInterface
	protected interface ValueMetadatumConverter<T> {
		@NonNull
		Object convert(@NonNull T valueMetadatum,
									 @NonNull Type toType,
									 @NonNull ValueConverter<Object, Object> valueConverter) throws ValueConversionException;
	}

	@NotThreadSafe
	protected static class RequestValueExtractionConfig<T> {
		@NonNull
		private final ResourceMethod resourceMethod;
		@NonNull
		private final Parameter parameter;
		@NonNull
		private final ParameterType parameterType;
		@NonNull
		private final String parameterName;
		@NonNull
		private final String parameterDescription;
		@NonNull
		private final Boolean optional;
		@NonNull
		private final List<String> values;
		@NonNull
		private final List<T> valuesMetadata;
		@Nullable
		private ValueMetadatumConverter<T> valueMetadatumConverter;
		@NonNull
		private final MissingExceptionProvider missingExceptionProvider;
		@NonNull
		private final IllegalExceptionProvider illegalExceptionProvider;

		@SuppressWarnings("unchecked")
		protected RequestValueExtractionConfig(@NonNull Builder builder) {
			requireNonNull(builder);

			this.resourceMethod = requireNonNull(builder.resourceMethod);
			this.parameter = requireNonNull(builder.parameter);
			this.parameterType = requireNonNull(builder.parameterType);
			this.parameterName = requireNonNull(builder.parameterName);
			this.parameterDescription = requireNonNull(builder.parameterDescription);
			this.optional = builder.optional == null ? false : builder.optional;
			this.values = builder.values == null ? List.of() : new ArrayList<>(builder.values);
			this.valuesMetadata = builder.valuesMetadata == null ? List.of() : new ArrayList<>(builder.valuesMetadata);
			this.valueMetadatumConverter = builder.valueMetadatumConverter;
			this.missingExceptionProvider = requireNonNull(builder.missingExceptionProvider);
			this.illegalExceptionProvider = requireNonNull(builder.illegalExceptionProvider);
		}

		@NotThreadSafe
		protected static final class Builder<T> {
			@NonNull
			private final ResourceMethod resourceMethod;
			@NonNull
			private final Parameter parameter;
			@NonNull
			private final ParameterType parameterType;
			@NonNull
			private final String parameterName;
			@NonNull
			private String parameterDescription;
			@Nullable
			private Boolean optional;
			@Nullable
			private List<String> values;
			@Nullable
			private List<T> valuesMetadata;
			@Nullable
			private ValueMetadatumConverter<T> valueMetadatumConverter;
			@Nullable
			private MissingExceptionProvider missingExceptionProvider;
			@Nullable
			private IllegalExceptionProvider illegalExceptionProvider;

			public Builder(@NonNull ResourceMethod resourceMethod,
										 @NonNull Parameter parameter,
										 @NonNull ParameterType parameterType,
										 @NonNull String parameterName,
										 @NonNull String parameterDescription) {
				requireNonNull(resourceMethod);
				requireNonNull(parameter);
				requireNonNull(parameterType);
				requireNonNull(parameterName);
				requireNonNull(parameterDescription);

				this.resourceMethod = resourceMethod;
				this.parameter = parameter;
				this.parameterType = parameterType;
				this.parameterName = parameterName;
				this.parameterDescription = parameterDescription;
			}

			@NonNull
			public Builder optional(@Nullable Boolean optional) {
				this.optional = optional;
				return this;
			}

			@NonNull
			public Builder values(@Nullable List<String> values) {
				this.values = values;
				return this;
			}

			@NonNull
			public Builder valuesMetadata(@Nullable List<T> valuesMetadata) {
				this.valuesMetadata = valuesMetadata;
				return this;
			}

			@NonNull
			public Builder valueMetadatumConverter(@Nullable ValueMetadatumConverter<T> valueMetadatumConverter) {
				this.valueMetadatumConverter = valueMetadatumConverter;
				return this;
			}

			@NonNull
			public Builder missingExceptionProvider(@Nullable MissingExceptionProvider missingExceptionProvider) {
				this.missingExceptionProvider = missingExceptionProvider;
				return this;
			}

			@NonNull
			public Builder illegalExceptionProvider(@Nullable IllegalExceptionProvider illegalExceptionProvider) {
				this.illegalExceptionProvider = illegalExceptionProvider;
				return this;
			}

			@NonNull
			public RequestValueExtractionConfig build() {
				return new RequestValueExtractionConfig(this);
			}
		}

		@NonNull
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@NonNull
		public Parameter getParameter() {
			return this.parameter;
		}

		@NonNull
		public ParameterType getParameterType() {
			return this.parameterType;
		}

		@NonNull
		public String getParameterName() {
			return this.parameterName;
		}

		@NonNull
		public String getParameterDescription() {
			return this.parameterDescription;
		}

		@NonNull
		public Boolean getOptional() {
			return this.optional;
		}

		@NonNull
		public List<String> getValues() {
			return this.values;
		}

		@NonNull
		public List<T> getValuesMetadata() {
			return this.valuesMetadata;
		}

		@NonNull
		public Optional<ValueMetadatumConverter<T>> getValueMetadatumConverter() {
			return Optional.ofNullable(this.valueMetadatumConverter);
		}

		@NonNull
		public MissingExceptionProvider getMissingExceptionProvider() {
			return this.missingExceptionProvider;
		}

		@NonNull
		public IllegalExceptionProvider getIllegalExceptionProvider() {
			return this.illegalExceptionProvider;
		}
	}

	/**
	 * Given a parameter, make its "real" type a little more accessible.
	 * That means:
	 * <p>
	 * 1. If wrapped in an optional, the real type is the wrapped value
	 * 2. If it's a List type, the real type is the list element's type
	 * 3. If neither 1 nor 2 then no transformations performed
	 */
	@ThreadSafe
	protected static class ParameterType {
		@NonNull
		private final Type normalizedType;
		@Nullable
		private final Type listElementType;
		@NonNull
		private final Boolean wrappedInOptional;

		public ParameterType(@NonNull Parameter parameter) {
			requireNonNull(parameter);

			Type normalizedType = parameter.getParameterizedType();
			Type listElementType = null;
			boolean wrappedInOptional = false;

			if (parameter.getType().isAssignableFrom(Optional.class)) {
				normalizedType = ((ParameterizedType) parameter.getParameterizedType()).getActualTypeArguments()[0];
				wrappedInOptional = true;
			}

			// Special handling: determine if this property is a generic List
			if (ParameterizedType.class.isAssignableFrom(normalizedType.getClass())) {
				ParameterizedType parameterizedNormalizedType = (ParameterizedType) normalizedType;

				if (parameterizedNormalizedType.getRawType().equals(List.class))
					listElementType = parameterizedNormalizedType.getActualTypeArguments()[0];
			}

			this.normalizedType = normalizedType;
			this.listElementType = listElementType;
			this.wrappedInOptional = wrappedInOptional;
		}

		@NonNull
		public Type getNormalizedType() {
			return this.normalizedType;
		}

		@NonNull
		public Optional<Type> getListElementType() {
			return Optional.ofNullable(this.listElementType);
		}

		@NonNull
		public Boolean isList() {
			return getListElementType().isPresent();
		}

		@NonNull
		public Boolean isWrappedInOptional() {
			return this.wrappedInOptional;
		}
	}
}
