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

import static com.soklet.util.IoUtils.copyStreamCloseAfterwards;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class RoutingServlet extends HttpServlet {
  private final RouteMatcher routeMatcher;
  private final RequestHandler requestHandler;
  private final ResponseHandler responseHandler;
  private final Logger logger = Logger.getLogger(getClass().getName());

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

    if (logger.isLoggable(FINE))
      logger.fine(format("Received %s", httpServletRequestDescription(httpServletRequest)));

    Optional<Route> route = routeMatcher.match(httpMethod, requestPath);
    Optional<Object> response = Optional.ofNullable(null);

    try {
      if (route.isPresent()) {
        if (logger.isLoggable(FINER))
          logger.finer(format("Found a matching handler: %s", route.get().resourceMethod()));

        response = requestHandler.handleRequest(httpServletRequest, httpServletResponse, route.get());
      } else {
        if (logger.isLoggable(FINER))
          logger.finer(format("No matching handler found for %s", httpServletRequestDescription(httpServletRequest)));
      }

      responseHandler.handleResponse(httpServletRequest, httpServletResponse, route, response, Optional.empty());
    } catch (Exception e) {
      if (logger.isLoggable(FINE))
        logger.fine(format("%s occurred while handling %s", e.getClass().getSimpleName(),
          httpServletRequestDescription(httpServletRequest)));

      try {
        responseHandler.handleResponse(httpServletRequest, httpServletResponse, route, response, Optional.of(e));
      } catch (Exception e2) {
        logger.warning(format(
          "%s occurred while trying to handle an error response, falling back to a failsafe response...", e2.getClass()
            .getSimpleName()));

        writeFailsafeErrorResponse(httpServletRequest, httpServletResponse);
      }
    }
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
      copyStreamCloseAfterwards(new ByteArrayInputStream("500".getBytes(UTF_8)), outputStream);
    }
  }
}