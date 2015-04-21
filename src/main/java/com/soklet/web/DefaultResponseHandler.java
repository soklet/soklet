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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.soklet.web.exception.BadRequestException;
import com.soklet.web.exception.MethodNotAllowedException;

/**
 * Bare-bones placeholder {@link ResponseHandler} implementation.
 * <p>
 * Soklet applications should override this with their own implementation for enhanced functionality (rendering HTML
 * templates, JSON, etc.)
 * 
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class DefaultResponseHandler implements ResponseHandler {
  @Override
  public void handleResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<Route> route, Optional<Object> response, Optional<Exception> exception) throws IOException {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);
    requireNonNull(response);
    requireNonNull(exception);

    httpServletResponse.setContentType("text/html;charset=UTF-8");

    if (exception.isPresent()) {
      httpServletResponse.setStatus(statusCodeForException(exception.get()));
      writeResponseBody(httpServletResponse, exception.get().toString());
    } else {
      if (route.isPresent()) {
        try (OutputStream outputStream = httpServletResponse.getOutputStream()) {
          if (response.isPresent())
            writeResponseBody(httpServletResponse, response.get().toString());
          else
            httpServletResponse.setStatus(204);
        }
      } else {
        // This must be a 404
        httpServletResponse.setStatus(404);
        writeResponseBody(httpServletResponse, "404");
      }
    }
  }

  protected int statusCodeForException(Exception exception) {
    requireNonNull(exception);

    if (exception instanceof BadRequestException)
      return 400;
    if (exception instanceof MethodNotAllowedException)
      return 405;

    return 500;
  }

  protected void writeResponseBody(HttpServletResponse httpServletResponse, String responseBody) throws IOException {
    requireNonNull(httpServletResponse);
    requireNonNull(responseBody);

    try (OutputStream outputStream = httpServletResponse.getOutputStream()) {
      copyStreamCloseAfterwards(new ByteArrayInputStream((responseBody).getBytes(UTF_8)), outputStream);
    }
  }
}