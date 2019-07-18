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

import com.soklet.web.HttpMethod;
import com.soklet.web.exception.MethodNotAllowedException;
import com.soklet.web.exception.NotFoundException;
import com.soklet.web.request.RequestContext;
import com.soklet.web.request.RequestHandler;
import com.soklet.web.response.ResponseHandler;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static com.soklet.util.FormatUtils.httpServletRequestDescription;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.FINER;
import static java.util.stream.Collectors.joining;

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

    HttpMethod httpMethod = HttpMethod.valueOf(httpServletRequest.getMethod().toUpperCase(ENGLISH));
    String requestPath = httpServletRequest.getPathInfo();
    boolean executeResponseHandler = true;

    Optional<Route> route = RequestContext.get().route();
    Optional<Object> response = Optional.ofNullable(null);

    try {
      if (route.isPresent()) {
        if (logger.isLoggable(FINER))
          logger.finer(format("Found a matching handler: %s", route.get().resourceMethod()));

        response = requestHandler.handleRequest(httpServletRequest, httpServletResponse, route.get());
      } else {
        if (logger.isLoggable(FINER))
          logger.finer(format("No matching handler found for %s", httpServletRequestDescription(httpServletRequest)));

        executeResponseHandler = handleUnmatchedRoute(httpServletRequest, httpServletResponse, httpMethod, requestPath);
      }

      if (executeResponseHandler)
        responseHandler.handleResponse(httpServletRequest, httpServletResponse, route, response, Optional.empty());
    } catch (RuntimeException | ServletException | IOException e) {
      // Exceptions are handled further up by SokletFilter
      throw e;
    } catch (Exception e) {
      throw new ServletException(e);
    }
  }

  /**
   * Performs custom processing when a route was not matched.
   * <p>
   * Detects 404s, also useful for handling special cases like 405 errors if we detect the route would match for a
   * different HTTP method.
   * 
   * @return {@code true} if the response handler should be invoked, {@code false} otherwise
   */
  protected boolean handleUnmatchedRoute(HttpServletRequest httpServletRequest,
      HttpServletResponse httpServletResponse, HttpMethod httpMethod, String requestPath) {
    // If this resource matches a different method[s], error out specially
    List<HttpMethod> otherHttpMethods = new ArrayList<>(HttpMethod.values().length);

    for (HttpMethod otherHttpMethod : HttpMethod.values())
      if (httpMethod != otherHttpMethod && routeMatcher.match(otherHttpMethod, requestPath).isPresent())
        otherHttpMethods.add(otherHttpMethod);

    // Handle OPTIONS specially by indicating we don't want to invoke the response handler
    // Otherwise, throw an exception indicating a 405
    if (otherHttpMethods.size() > 0) {
      // Always write the Allow header
      httpServletResponse.setHeader("Allow",
        otherHttpMethods.stream().map(method -> method.name()).collect(joining(", ")));

      if (httpMethod == HttpMethod.OPTIONS)
        return false;

      throw new MethodNotAllowedException(format("%s is not supported for this resource. Supported method%s %s",
        httpMethod, (otherHttpMethods.size() == 1 ? " is" : "s are"),
        otherHttpMethods.stream().map(method -> method.name()).collect(joining(", "))));
    }

    // No matching route, and no possible alternatives? It's a 404
    throw new NotFoundException(format("No route was found for %s %s", httpMethod.name(), requestPath));
  }
}