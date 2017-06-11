/*
 * Copyright (c) 2017 Transmogrify LLC.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.soklet.util;

import com.soklet.web.exception.ResourceMethodExecutionException;
import com.soklet.web.routing.Route;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;
import java.util.logging.Logger;

import static com.soklet.util.FormatUtils.httpServletRequestDescription;
import static com.soklet.util.FormatUtils.stackTraceForThrowable;
import static com.soklet.util.IoUtils.copyStreamCloseAfterwards;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.FINE;

/**
 * @author <a href="https://revetkn.com">Mark Allen</a>
 * @since 1.2.0
 */
public final class ResponseUtils {
  private static final Logger logger = Logger.getLogger(ResponseUtils.class.getName());

  private ResponseUtils() {
  }

  public static void logException(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
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

  public static void writeFailsafeErrorResponse(HttpServletRequest httpServletRequest,
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