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

package com.soklet.web.routing;

import static com.soklet.util.IoUtils.copyStreamCloseAfterwards;
import static java.lang.String.format;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.stream.Collectors.joining;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.soklet.web.HttpMethod;
import com.soklet.web.exception.MethodNotAllowedException;
import com.soklet.web.exception.ResourceMethodExecutionException;
import com.soklet.web.request.RequestHandler;
import com.soklet.web.response.ResponseHandler;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class RoutingServlet extends HttpServlet {
  private final RouteMatcher routeMatcher;
  private final RequestHandler requestHandler;
  private final ResponseHandler responseHandler;
  private final Logger logger = Logger.getLogger(RoutingServlet.class.getName());

  @Inject
  public RoutingServlet(RouteMatcher routeMatcher, RequestHandler requestHandler, ResponseHandler responseHandler) {
    this.routeMatcher = requireNonNull(routeMatcher);
    this.requestHandler = requireNonNull(requestHandler);
    this.responseHandler = requireNonNull(responseHandler);
  }

  @Override
  protected void service(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
      throws ServletException, IOException {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);

    long time = nanoTime();

    HttpMethod httpMethod = HttpMethod.valueOf(httpServletRequest.getMethod().toUpperCase(ENGLISH));
    String requestPath = httpServletRequest.getPathInfo();

    if (logger.isLoggable(FINE))
      logger.fine(format("Received %s", httpServletRequestDescription(httpServletRequest)));

    Optional<Route> route = routeMatcher.match(httpMethod, requestPath);
    Optional<Object> response = Optional.ofNullable(null);
    boolean executeResponseHandler = true;

    try {
      if (route.isPresent()) {
        if (logger.isLoggable(FINER))
          logger.finer(format("Found a matching handler: %s", route.get().resourceMethod()));

        response = requestHandler.handleRequest(httpServletRequest, httpServletResponse, route.get());
      } else {
        if (logger.isLoggable(FINER))
          logger.finer(format("No matching handler found for %s", httpServletRequestDescription(httpServletRequest)));

        executeResponseHandler =
            handleMismatchedHttpMethod(httpServletRequest, httpServletResponse, httpMethod, requestPath);
      }

      if (executeResponseHandler)
        responseHandler.handleResponse(httpServletRequest, httpServletResponse, route, response, Optional.empty());
    } catch (Exception e) {
      logException(httpServletRequest, httpServletResponse, route, response, e);

      try {
        responseHandler.handleResponse(httpServletRequest, httpServletResponse, route, response, Optional.of(e));
      } catch (Exception e2) {
        logger.warning(format(
          "Exception occurred while trying to handle an error response, falling back to a failsafe response...\n%s",
          stackTraceForThrowable(e2)));

        writeFailsafeErrorResponse(httpServletRequest, httpServletResponse);
      }
    } finally {
      time = nanoTime() - time;

      if (logger.isLoggable(FINE))
        logger.fine(format("Took %.2fms to handle %s", time / 1_000_000f,
          httpServletRequestDescription(httpServletRequest)));
    }
  }

  /**
   * @return {@code true} if the response handler should be invoked, {@code false} otherwise
   */
  protected boolean handleMismatchedHttpMethod(HttpServletRequest httpServletRequest,
      HttpServletResponse httpServletResponse, HttpMethod httpMethod, String requestPath) {
    // If this resource matches a different method, error out specially
    List<HttpMethod> otherHttpMethods = new ArrayList<>(HttpMethod.values().length);

    for (HttpMethod otherHttpMethod : HttpMethod.values())
      if (httpMethod != otherHttpMethod && routeMatcher.match(otherHttpMethod, requestPath).isPresent())
        otherHttpMethods.add(otherHttpMethod);

    // Handle OPTIONS specially by writing the "Allow" response header.
    // Otherwise, throw an exception indicating a 405
    if (otherHttpMethods.size() > 0) {
      if (httpMethod == HttpMethod.OPTIONS) {
        httpServletResponse.setHeader("Allow",
          otherHttpMethods.stream().map(method -> method.name()).collect(joining(", ")));

        return false;
      } else {
        throw new MethodNotAllowedException(format("%s is not supported for this resource. Supported method%s %s",
          httpMethod, (otherHttpMethods.size() == 1 ? " is" : "s are"),
          otherHttpMethods.stream().map(method -> method.name()).collect(joining(", "))));
      }
    }

    return true;
  }

  protected void logException(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<Route> route, Optional<Object> response, Exception exception) {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);
    requireNonNull(response);
    requireNonNull(exception);

    if (!logger.isLoggable(FINE))
      return;

    Throwable throwable = exception;

    // Unwrap these for more compact stack traces
    if (exception instanceof ResourceMethodExecutionException)
      throwable = exception.getCause();

    logger.fine(format("Exception occurred while handling %s\n%s", httpServletRequestDescription(httpServletRequest),
      stackTraceForThrowable(throwable)));
  }

  protected String stackTraceForThrowable(Throwable throwable) {
    requireNonNull(throwable);

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    throwable.printStackTrace(printWriter);
    return stringWriter.toString().trim();
  }

  protected String httpServletRequestDescription(HttpServletRequest httpServletRequest) {
    requireNonNull(httpServletRequest);

    String path =
        httpServletRequest.getQueryString() == null ? httpServletRequest.getPathInfo() : format("%s?%s",
          httpServletRequest.getPathInfo(), httpServletRequest.getQueryString());

    return format("%s %s", httpServletRequest.getMethod(), path);
  }

  protected void writeFailsafeErrorResponse(HttpServletRequest httpServletRequest,
      HttpServletResponse httpServletResponse) throws ServletException, IOException {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);

    httpServletResponse.setContentType("text/html;charset=UTF-8");
    httpServletResponse.setStatus(500);

    try (OutputStream outputStream = httpServletResponse.getOutputStream()) {
      copyStreamCloseAfterwards(new ByteArrayInputStream("500 error".getBytes(UTF_8)), outputStream);
    }
  }
}