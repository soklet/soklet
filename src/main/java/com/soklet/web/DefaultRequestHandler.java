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

package com.soklet.web;

import static com.soklet.util.IoUtils.copyStreamToBytesCloseAfterwards;
import static com.soklet.util.IoUtils.stringFromStreamCloseAfterwards;
import static com.soklet.util.StringUtils.trimToNull;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import com.soklet.util.InstanceProvider;
import com.soklet.web.annotation.PathParameter;
import com.soklet.web.annotation.QueryParameter;
import com.soklet.web.annotation.RequestBody;
import com.soklet.web.exception.IllegalPathParameterException;
import com.soklet.web.exception.IllegalQueryParameterException;
import com.soklet.web.exception.MissingQueryParameterException;
import com.soklet.web.exception.MissingRequestBodyException;

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

    if (shouldAllowRequestBodyRepeatableReads(httpServletRequest, httpServletResponse, route))
      httpServletRequest = new RequestBodyRepeatableReadWrapper(httpServletRequest);

    // Load up values to pass as the method's parameters (if any)
    Parameter[] parameters = route.resourceMethod().getParameters();
    List<Object> parametersToPass = new ArrayList<>(parameters.length);

    for (Parameter parameter : parameters)
      parametersToPass.add(extractParameterValueToPassToResourceMethod(httpServletRequest, httpServletResponse, route,
        parameter));

    // Ask our injector for an instance of the class associated with the method
    Object resourceMethodDeclaringInstance = instanceProvider.provide(route.resourceMethod().getDeclaringClass());

    // Call the method
    return Optional.ofNullable(route.resourceMethod().invoke(resourceMethodDeclaringInstance,
      parametersToPass.toArray()));
  }

  protected Object extractParameterValueToPassToResourceMethod(HttpServletRequest httpServletRequest,
      HttpServletResponse httpServletResponse, Route route, Parameter parameter) throws Exception {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);
    requireNonNull(parameter);

    if (parameter.getType().isAssignableFrom(HttpServletRequest.class))
      return httpServletRequest;

    if (parameter.getType().isAssignableFrom(HttpServletResponse.class))
      return httpServletResponse;

    ParameterType parameterType = new ParameterType(parameter);

    QueryParameter queryParameter = parameter.getAnnotation(QueryParameter.class);

    if (queryParameter != null) {
      String queryParameterName =
          extractParameterName(route.resourceMethod(), parameter, queryParameter, queryParameter.value());

      String[] rawQueryParameterValues = httpServletRequest.getParameterValues(queryParameterName);
      List<String> queryParameterValues =
          rawQueryParameterValues == null ? emptyList() : Arrays.asList(rawQueryParameterValues);
      Type toType = parameterType.isList() ? parameterType.listElementType().get() : parameterType.normalizedType();

      Optional<ValueConverter<Object, Object>> valueConverter = valueConverterRegistry.get(String.class, toType);

      if (!valueConverter.isPresent())
        throwValueConverterMissingException(valueConverter, parameter, String.class, toType, route);

      // Special handling for Lists (support for multiple query parameters with the same name)
      if (parameterType.isList()) {
        List<Object> results = new ArrayList<>(queryParameterValues.size());

        for (String queryParameterValue : queryParameterValues) {
          if (queryParameterValue != null && queryParameterValue.trim().length() > 0)
            try {
              results.add(valueConverter.get().convert(queryParameterValue));
            } catch (ValueConversionException e) {
              throw new IllegalQueryParameterException(format(
                "Illegal value '%s' was specified for query parameter '%s' (was expecting a value convertible to %s)",
                queryParameterValue, queryParameterName, valueConverter.get().toType()), Optional.of(e),
                queryParameterName, Optional.ofNullable(queryParameterValue));
            }
        }

        if (!parameterType.isOptional() && results.size() == 0)
          throw new MissingQueryParameterException(format("Required query parameter '%s' was not specified.",
            queryParameterName), queryParameterName);

        return parameterType.isOptional() ? (results.size() == 0 ? Optional.empty() : Optional.of(results)) : results;
      }

      // Non-list support
      String queryParameterValue = queryParameterValues.size() > 0 ? queryParameterValues.get(0) : null;

      if (queryParameterValue != null && queryParameterValue.trim().length() == 0)
        queryParameterValue = null;

      if (!parameterType.isOptional() && queryParameterValue == null)
        throw new MissingQueryParameterException(format("Required query parameter '%s' was not specified.",
          queryParameterName), queryParameterName);

      Object result = null;

      try {
        result = valueConverter.get().convert(queryParameterValue);
      } catch (ValueConversionException e) {
        throw new IllegalQueryParameterException(format(
          "Illegal value '%s' was specified for query parameter '%s' (was expecting a value convertible to %s)",
          queryParameterValue, queryParameterName, valueConverter.get().toType()), Optional.of(e), queryParameterName,
          Optional.ofNullable(queryParameterValue));
      }

      return parameterType.isOptional() ? Optional.ofNullable(result) : result;
    }

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
          pathParameterValue, pathParameterName, valueConverter.get().toType()), Optional.of(e), pathParameterName,
          Optional.ofNullable(pathParameterValue));
      }

      return result;
    }

    // RequestHeader requestHeader = parameter.getAnnotation(RequestHeader.class);
    // RequestCookie requestCookie = parameter.getAnnotation(RequestCookie.class);

    RequestBody requestBody = parameter.getAnnotation(RequestBody.class);

    if (requestBody != null) {
      if (!String.class.equals(parameterType.normalizedType()))
        throw new IllegalStateException(format("@%s-annotated parameters must be of type %s or %s<%s>",
          RequestBody.class.getSimpleName(), String.class.getSimpleName(), Optional.class.getSimpleName(),
          String.class.getSimpleName()));

      String requestBodyValue = trimToNull(stringFromStreamCloseAfterwards(httpServletRequest.getInputStream()));

      if (parameterType.isOptional())
        return Optional.ofNullable(requestBodyValue);

      if (requestBodyValue == null)
        throw new MissingRequestBodyException(format("A request body is required for this resource."));

      return requestBodyValue;
    }

    // TODO: want to support async stuff as easily as possible
    // See http://www.jayway.com/2014/05/16/async-servlets/
    // We might want to inject Route and ResponseHandler parameters so async calls can write responses "normally".
    // Or introduce a new type that wraps AsyncContext stuff and inject that
    //
    // Further TODO: need to figure out how to signify a method is async so RoutingServlet does not try to write a
    // response. Either an annotation or magic return value from the method? Leaning toward latter since annotation
    // locks you into "always async"

    // TODO: throw IllegalStateException since we don't know what to do with the parameter?
    // Probably better to explicitly error out than keep the user guessing why things aren't working
    return null;
  }

  /**
   * Should this request body be cached in-memory to support repeated reads?
   * <p>
   * This is helpful for common cases (request body logging and parsing) but if you have special requirements (need
   * non-blocking I/O or have to handle large request bodies like file uploads) then you should override this method to
   * return {@code false}.
   * <p>
   * By default this method returns {@code true}.
   * 
   * @param httpServletRequest
   *          Servlet request
   * @param httpServletResponse
   *          Servlet request
   * @param route
   *          The route for this request
   * @return {@code true} if the filter should be applied, {@code false} otherwise
   */
  public boolean shouldAllowRequestBodyRepeatableReads(HttpServletRequest httpServletRequest,
      HttpServletResponse httpServletResponse, Route route) {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);

    return true;
  }

  protected String extractParameterName(Method method, Parameter parameter, Annotation annotation,
      String annotationValue) {
    requireNonNull(method);
    requireNonNull(parameter);
    requireNonNull(annotation);
    requireNonNull(annotationValue);

    String parameterName = trimToNull(annotationValue);

    if (parameterName == null && parameter.isNamePresent())
      parameterName = parameter.getName();

    if (parameterName == null)
      throw new IllegalArgumentException(format(
        "You must specify a @%s value for parameter '%s' for resource method %s "
            + "or compile with -g:vars to preserve variable names", annotation.getClass().getSimpleName(), parameter,
        method));

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

  protected static class RequestBodyRepeatableReadWrapper extends HttpServletRequestWrapper {
    private final byte[] requestBody;

    public RequestBodyRepeatableReadWrapper(HttpServletRequest httpServletRequest) {
      super(requireNonNull(httpServletRequest));
      try {
        this.requestBody =
            httpServletRequest.getInputStream() == null ? new byte[] {}
                : copyStreamToBytesCloseAfterwards(httpServletRequest.getInputStream());
      } catch (IOException e) {
        throw new IllegalStateException("Unable to read request body", e);
      }
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      return new RepeatableReadServletInputStream(new ByteArrayInputStream(requestBody));
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String characterEncoding = getCharacterEncoding();
      return new BufferedReader(new InputStreamReader(getInputStream(), characterEncoding == null ? "UTF-8"
          : characterEncoding));
    }

    protected static class RepeatableReadServletInputStream extends ServletInputStream {
      private final InputStream inputStream;

      public RepeatableReadServletInputStream(InputStream inputStream) {
        this.inputStream = requireNonNull(inputStream);
      }

      @Override
      public int read() throws IOException {
        return inputStream.read();
      }

      @Override
      public boolean markSupported() {
        return false;
      }

      @Override
      public boolean isFinished() {
        return true;
      }

      @Override
      public boolean isReady() {
        return false;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        requireNonNull(readListener);

        // Since we've already read the request body, we're immediately done...fire callback right away
        try {
          readListener.onAllDataRead();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }
}