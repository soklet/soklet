/*
 * Copyright 2015 Transmogrify LLC.
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

package com.soklet.web.request;

import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import com.soklet.util.InstanceProvider;
import com.soklet.web.ResourcePath;
import com.soklet.web.annotation.*;
import com.soklet.web.exception.*;
import com.soklet.web.request.FirstFilter.SokletHttpServletRequest;
import com.soklet.web.routing.Route;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

import static com.soklet.util.IoUtils.stringFromStreamCloseAfterwards;
import static com.soklet.util.StringUtils.trimToNull;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class DefaultRequestHandler implements RequestHandler {
	private final InstanceProvider instanceProvider;
	private final ValueConverterRegistry valueConverterRegistry;

	public DefaultRequestHandler(InstanceProvider instanceProvider) {
		this.instanceProvider = requireNonNull(instanceProvider);
		this.valueConverterRegistry = new ValueConverterRegistry();
	}

	public DefaultRequestHandler(InstanceProvider instanceProvider, ValueConverterRegistry valueConverterRegistry) {
		this.instanceProvider = requireNonNull(instanceProvider);
		this.valueConverterRegistry = requireNonNull(valueConverterRegistry);
	}

	@Override
	public Optional<Object> handleRequest(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
																				Route route) throws Exception {
		requireNonNull(httpServletRequest);
		requireNonNull(httpServletResponse);
		requireNonNull(route);

		// Load up values to pass as the method's parameters (if any)
		Parameter[] parameters = route.resourceMethod().getParameters();
		List<Object> parametersToPass = new ArrayList<>(parameters.length);

		for (Parameter parameter : parameters)
			parametersToPass.add(extractParameterValueToPassToResourceMethod(httpServletRequest, httpServletResponse, route,
					parameter));

		// Ask our injector for an instance of the class associated with the method
		Object resourceMethodDeclaringInstance = instanceProvider.provide(route.resourceMethod().getDeclaringClass());

		// Call the method via reflection
		try {
			return Optional.ofNullable(route.resourceMethod().invoke(resourceMethodDeclaringInstance,
					parametersToPass.toArray()));
		} catch (InvocationTargetException e) {
			throw new ResourceMethodExecutionException(route, e.getCause());
		} catch (Exception e) {
			throw new ResourceMethodExecutionException(route, e);
		}
	}

	protected Object extractParameterValueToPassToResourceMethod(HttpServletRequest httpServletRequest,
																															 HttpServletResponse httpServletResponse, Route route, Parameter parameter) throws Exception {
		requireNonNull(httpServletRequest);
		requireNonNull(httpServletResponse);
		requireNonNull(route);
		requireNonNull(parameter);

		if (parameter.getType().isAssignableFrom(HttpServletRequest.class)) return httpServletRequest;
		if (parameter.getType().isAssignableFrom(HttpServletResponse.class)) return httpServletResponse;

		ParameterType parameterType = new ParameterType(parameter);

		PathParameter pathParameter = parameter.getAnnotation(PathParameter.class);

		if (pathParameter != null) {
			if (parameterType.isOptional())
				throw new IllegalStateException(format("@%s-annotated parameters cannot be marked %s",
						PathParameter.class.getSimpleName(), Optional.class.getSimpleName()));

			String pathParameterName =
					extractParameterName(route.resourceMethod(), parameter, pathParameter, pathParameter.value());
			ResourcePath requestResourcePath = ResourcePath.fromPathInstance(httpServletRequest.getPathInfo());

			Map<String, String> valuesByPathParameter = route.resourcePath().placeholders(requestResourcePath);
			String pathParameterValue = valuesByPathParameter.get(pathParameterName);

			if (pathParameterValue == null)
				throw new IllegalStateException(format("Missing value for path parameter '%s' for resource method %s",
						pathParameterName, route.resourceMethod()));

			Optional<ValueConverter<Object, Object>> valueConverter =
					valueConverterRegistry.get(String.class, parameter.getType());

			if (!valueConverter.isPresent())
				throwValueConverterMissingException(valueConverter, parameter, String.class, parameter.getType(), route);

			Object result = null;

			try {
				result = valueConverter.get().convert(pathParameterValue);
			} catch (ValueConversionException e) {
				throw new IllegalPathParameterException(format(
						"Illegal value '%s' was specified for path parameter '%s' (was expecting a value convertible to %s)",
						pathParameterValue, pathParameterName, valueConverter.get().toType()), e, pathParameterName,
						Optional.ofNullable(pathParameterValue));
			}

			return result;
		}

		QueryParameter queryParameter = parameter.getAnnotation(QueryParameter.class);
		if (queryParameter != null)
			return extractQueryParameterValue(httpServletRequest, route, parameter, queryParameter, parameterType);

		FormParameter formParameter = parameter.getAnnotation(FormParameter.class);
		if (formParameter != null)
			return extractFormParameterValue(httpServletRequest, route, parameter, formParameter, parameterType);

		RequestHeader requestHeader = parameter.getAnnotation(RequestHeader.class);
		if (requestHeader != null)
			return extractRequestHeaderValue(httpServletRequest, route, parameter, requestHeader, parameterType);

		RequestCookie requestCookie = parameter.getAnnotation(RequestCookie.class);
		if (requestCookie != null)
			return extractRequestCookieValue(httpServletRequest, route, parameter, requestCookie, parameterType);

		RequestBody requestBody = parameter.getAnnotation(RequestBody.class);

		if (requestBody != null) {
			if (!String.class.equals(parameterType.normalizedType()))
				throw new IllegalStateException(format("@%s-annotated parameters must be of type %s or %s<%s>",
						RequestBody.class.getSimpleName(), String.class.getSimpleName(), Optional.class.getSimpleName(),
						String.class.getSimpleName()));

			String requestBodyValue = trimToNull(stringFromStreamCloseAfterwards(httpServletRequest.getInputStream()));

			if (parameterType.isOptional()) return Optional.ofNullable(requestBodyValue);

			if (requestBodyValue == null)
				throw new MissingRequestBodyException(format("A request body is required for this resource."));

			return requestBodyValue;
		}

		// Don't recognize what's being asked for? Have the InstanceProvider try to vend something
		return this.instanceProvider.provide(parameter.getType());
	}

	protected Object extractQueryParameterValue(HttpServletRequest httpServletRequest, Route route, Parameter parameter,
																							QueryParameter queryParameter, ParameterType parameterType) {
		requireNonNull(httpServletRequest);
		requireNonNull(route);
		requireNonNull(parameter);
		requireNonNull(queryParameter);
		requireNonNull(parameterType);

		String name = extractParameterName(route.resourceMethod(), parameter, queryParameter, queryParameter.value());

		String[] rawValues = (httpServletRequest instanceof SokletHttpServletRequest) ? ((SokletHttpServletRequest) httpServletRequest).getQueryParameterValues(name) : httpServletRequest.getParameterValues(name);
		List<String> values = rawValues == null ? emptyList() : Arrays.asList(rawValues);

		return extractRequestValue(httpServletRequest, route, parameter, parameterType, name, values, "query parameter", (
				message, ignored) -> {
			return new MissingQueryParameterException(message, name);
		}, (message, cause, ignored, value, valueMetadatum) -> {
			return new IllegalQueryParameterException(message, cause, name, value);
		});
	}

	protected Object extractFormParameterValue(HttpServletRequest httpServletRequest, Route route, Parameter parameter,
																						 FormParameter formParameter, ParameterType parameterType) {
		requireNonNull(httpServletRequest);
		requireNonNull(route);
		requireNonNull(parameter);
		requireNonNull(formParameter);
		requireNonNull(parameterType);

		String name = extractParameterName(route.resourceMethod(), parameter, formParameter, formParameter.value());

		String[] rawValues = (httpServletRequest instanceof SokletHttpServletRequest) ? ((SokletHttpServletRequest) httpServletRequest).getFormParameterValues(name) : httpServletRequest.getParameterValues(name);
		List<String> values = rawValues == null ? emptyList() : Arrays.asList(rawValues);

		return extractRequestValue(httpServletRequest, route, parameter, parameterType, name, values, "form parameter", (
				message, ignored) -> {
			return new MissingFormParameterException(message, name);
		}, (message, cause, ignored, value, valueMetadatum) -> {
			return new IllegalFormParameterException(message, cause, name, value);
		});
	}

	protected Object extractRequestHeaderValue(HttpServletRequest httpServletRequest, Route route, Parameter parameter,
																						 RequestHeader requestHeader, ParameterType parameterType) {
		requireNonNull(httpServletRequest);
		requireNonNull(route);
		requireNonNull(parameter);
		requireNonNull(requestHeader);
		requireNonNull(parameterType);

		String name = extractParameterName(route.resourceMethod(), parameter, requestHeader, requestHeader.value());
		List<String> values = new ArrayList<>();

		for (Enumeration<String> specificValues = httpServletRequest.getHeaders(name); specificValues.hasMoreElements(); )
			values.add(specificValues.nextElement());

		return extractRequestValue(httpServletRequest, route, parameter, parameterType, name, values, "request header", (
				message, ignored) -> {
			return new MissingRequestHeaderException(message, name);
		}, (message, cause, ignored, value, valueMetadatum) -> {
			return new IllegalRequestHeaderException(message, cause, name, value);
		});
	}

	protected Object extractRequestCookieValue(HttpServletRequest httpServletRequest, Route route, Parameter parameter,
																						 RequestCookie requestCookie, ParameterType parameterType) {
		requireNonNull(httpServletRequest);
		requireNonNull(route);
		requireNonNull(parameter);
		requireNonNull(requestCookie);
		requireNonNull(parameterType);

		String name = extractParameterName(route.resourceMethod(), parameter, requestCookie, requestCookie.value());
		Cookie[] cookies = httpServletRequest.getCookies();
		List<String> values = new ArrayList<>();
		List<Cookie> valuesMetadata = new ArrayList<>();

		if (cookies != null) {
			for (Cookie cookie : cookies) {
				if (name.equals(cookie.getName())) {
					values.add(cookie.getValue());
					valuesMetadata.add(cookie);
				}
			}
		}

		// Special hack to return Cookie instances directly if the parameter wants a Cookie
		String cookieTypeName = Cookie.class.getTypeName();
		boolean isCookieScalarType = cookieTypeName.equals(parameterType.normalizedType().getTypeName());
		boolean isCookieListType =
				parameterType.listElementType().isPresent()
						&& cookieTypeName.equals(parameterType.listElementType().get().getTypeName());

		boolean returnWholeCookies = isCookieScalarType || isCookieListType;

		return extractRequestValue(httpServletRequest, route, parameter, parameterType, name, values, valuesMetadata,
				returnWholeCookies, "request cookie", (message, ignored) -> {
					return new MissingRequestCookieException(message, name);
				}, (message, cause, ignored, value, valueMetadatum) -> {
					return new IllegalRequestCookieException(message, cause, (Cookie) valueMetadatum.get());
				});
	}

	@FunctionalInterface
	protected static interface MissingExceptionProvider {
		RuntimeException provide(String message, String name);
	}

	@FunctionalInterface
	protected static interface IllegalExceptionProvider {
		RuntimeException provide(String message, Exception cause, String name, Optional<String> value,
														 Optional<Object> valueMetadatum);
	}

	protected Object extractRequestValue(HttpServletRequest httpServletRequest, Route route, Parameter parameter,
																			 ParameterType parameterType, String name, List<String> values, String description,
																			 MissingExceptionProvider missingExceptionProvider, IllegalExceptionProvider illegalExceptionProvider) {
		return extractRequestValue(httpServletRequest, route, parameter, parameterType, name, values, emptyList(), false,
				description, missingExceptionProvider, illegalExceptionProvider);
	}

	protected Object extractRequestValue(HttpServletRequest httpServletRequest, Route route, Parameter parameter,
																			 ParameterType parameterType, String name, List<String> values, List<?> valuesMetadata,
																			 boolean returnMetadataInsteadOfValues, String description, MissingExceptionProvider missingExceptionProvider,
																			 IllegalExceptionProvider illegalExceptionProvider) {
		requireNonNull(httpServletRequest);
		requireNonNull(route);
		requireNonNull(parameter);
		requireNonNull(parameterType);
		requireNonNull(name);
		requireNonNull(values);
		requireNonNull(valuesMetadata);
		requireNonNull(description);
		requireNonNull(missingExceptionProvider);
		requireNonNull(illegalExceptionProvider);

		Type toType = parameterType.isList() ? parameterType.listElementType().get() : parameterType.normalizedType();
		Optional<ValueConverter<Object, Object>> valueConverter = valueConverterRegistry.get(String.class, toType);

		if (!valueConverter.isPresent() && !returnMetadataInsteadOfValues)
			throwValueConverterMissingException(valueConverter, parameter, String.class, toType, route);

		// Special handling for Lists (support for multiple query parameters/headers/cookies with the same name)
		if (parameterType.isList()) {
			List<Object> results = new ArrayList<>(values.size());

			if (returnMetadataInsteadOfValues) {
				for (Object valuesMetadatum : valuesMetadata)
					results.add(valuesMetadatum);
			} else {
				for (int i = 0; i < values.size(); ++i) {
					String value = values.get(i);

					if (value != null && value.trim().length() > 0)
						try {
							results.add(valueConverter.get().convert(value));
						} catch (ValueConversionException e) {
							throw illegalExceptionProvider.provide(
									format("Illegal value '%s' was specified for %s '%s' (was expecting a value convertible to %s)", value,
											description, name, valueConverter.get().toType()), e, name, Optional.ofNullable(value), Optional
											.ofNullable(valuesMetadata.size() > i ? valuesMetadata.get(i) : null));
						}
				}
			}

			if (!parameterType.isOptional() && results.size() == 0)
				throw missingExceptionProvider.provide(format("Required %s '%s' was not specified.", description, name), name);

			return parameterType.isOptional() ? (results.size() == 0 ? Optional.empty() : Optional.of(results)) : results;
		}

		// Non-list support
		Object result = null;

		if (returnMetadataInsteadOfValues) {
			result = valuesMetadata.size() > 0 ? valuesMetadata.get(0) : null;

			if (!parameterType.isOptional() && result == null)
				throw missingExceptionProvider.provide(format("Required %s '%s' was not specified.", description, name), name);
		} else {
			String value = values.size() > 0 ? values.get(0) : null;

			if (value != null && value.trim().length() == 0) value = null;

			if (!parameterType.isOptional() && value == null)
				throw missingExceptionProvider.provide(format("Required %s '%s' was not specified.", description, name), name);

			try {
				result = valueConverter.get().convert(value);
			} catch (ValueConversionException e) {
				throw illegalExceptionProvider.provide(
						format("Illegal value '%s' was specified for %s '%s' (was expecting a value convertible to %s)", value,
								description, name, valueConverter.get().toType()), e, name, Optional.ofNullable(value), Optional
								.ofNullable(valuesMetadata.size() > 0 ? valuesMetadata.get(0) : null));
			}
		}

		return parameterType.isOptional() ? Optional.ofNullable(result) : result;
	}

	protected String extractParameterName(Method method, Parameter parameter, Annotation annotation,
																				String annotationValue) {
		requireNonNull(method);
		requireNonNull(parameter);
		requireNonNull(annotation);
		requireNonNull(annotationValue);

		String parameterName = trimToNull(annotationValue);

		if (parameterName == null && parameter.isNamePresent()) parameterName = parameter.getName();

		if (parameterName == null)
			throw new IllegalArgumentException(
					format(
							"Unable to automatically detect resource method parameter name. "
									+ "You must either explicitly specify a @%s value for parameter %s - for example, @%s(\"name-goes-here\") - "
									+ "or compile with javac flag \"-parameters\" to preserve parameter names for reflection. Offending resource method was %s",
							annotation.annotationType().getSimpleName(), parameter, annotation.annotationType().getSimpleName(), method));

		return parameterName;
	}

	protected void throwValueConverterMissingException(Optional<ValueConverter<Object, Object>> valueConverter,
																										 Parameter parameter, Type fromType, Type toType, Route route) {
		requireNonNull(valueConverter);
		requireNonNull(parameter);
		requireNonNull(fromType);
		requireNonNull(toType);
		requireNonNull(route);

		throw new IllegalArgumentException(format(
				"No %s is registered for converting %s to %s for parameter '%s' in resource method %s ",
				ValueConverter.class.getSimpleName(), fromType, toType, parameter, route.resourceMethod()));
	}

	protected static class ParameterType {
		private final Type normalizedType;
		private final Optional<Type> listElementType;
		private final boolean optional;

		public ParameterType(Parameter parameter) {
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
			this.listElementType = Optional.ofNullable(listElementType);
			this.optional = optional;
		}

		public Type normalizedType() {
			return normalizedType;
		}

		public Optional<Type> listElementType() {
			return listElementType;
		}

		public boolean isList() {
			return listElementType().isPresent();
		}

		public boolean isOptional() {
			return optional;
		}
	}
}