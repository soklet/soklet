/*
 * Copyright (c) 2015 Transmogrify LLC.
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

package com.soklet.web.response;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.soklet.web.exception.ExceptionStatusMapper;
import com.soklet.web.exception.ResourceMethodExecutionException;
import com.soklet.web.response.writer.ApiResponseWriter;
import com.soklet.web.response.writer.BinaryResponseWriter;
import com.soklet.web.response.writer.PageResponseWriter;
import com.soklet.web.response.writer.RedirectResponseWriter;
import com.soklet.web.response.writer.ResponseWriter;
import com.soklet.web.routing.Route;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
@Singleton
public class DefaultResponseHandler implements ResponseHandler {
  private final PageResponseWriter pageResponseWriter;
  private final ApiResponseWriter apiResponseWriter;
  private final BinaryResponseWriter binaryResponseWriter;
  private final RedirectResponseWriter redirectResponseWriter;
  private final ExceptionStatusMapper exceptionStatusMapper;

  @Inject
  public DefaultResponseHandler(PageResponseWriter pageResponseWriter, ApiResponseWriter apiResponseWriter,
      BinaryResponseWriter binaryResponseWriter, RedirectResponseWriter redirectResponseWriter,
      ExceptionStatusMapper exceptionStatusMapper) {
    this.pageResponseWriter = requireNonNull(pageResponseWriter);
    this.apiResponseWriter = requireNonNull(apiResponseWriter);
    this.binaryResponseWriter = requireNonNull(binaryResponseWriter);
    this.redirectResponseWriter = requireNonNull(redirectResponseWriter);
    this.exceptionStatusMapper = requireNonNull(exceptionStatusMapper);
  }

  @Override
  public void handleResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<Route> route, Optional<Object> response, Optional<Exception> exception) throws IOException {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);
    requireNonNull(response);
    requireNonNull(exception);

    // Unwrap ResourceMethodExecutionExceptions to get at the real cause.
    // TODO: maybe the signature for this method should take a Throwable instead of Exception
    if (exception.isPresent() && exception.get() instanceof ResourceMethodExecutionException)
      exception =
          Optional.of(exception.get().getCause() instanceof Exception ? (Exception) exception.get().getCause()
              : exception.get());

    // Do nothing at all if this was an async response
    if (response.isPresent() && response.get() instanceof AsyncResponse)
      return;

    // Figure out status code
    int status = statusForResponse(httpServletRequest, httpServletResponse, route, response, exception);

    httpServletResponse.setStatus(status);

    // Tack on any extra headers (no-cache, for example)
    writeAdditionalHeaders(httpServletRequest, httpServletResponse, route, response, exception);

    // No route? It's a 404 page
    if (!route.isPresent()) {
      ResponseWriter<?> responseWriter = responseWriterForMissingRoute(httpServletRequest, httpServletResponse);
      responseWriter.writeResponse(httpServletRequest, httpServletResponse, Optional.empty(), Optional.empty(),
        Optional.empty());
      return;
    }

    // Don't write anything if it's a 204 or a successful request where no response is available
    if (status == 204 || (!exception.isPresent() && !response.isPresent()))
      return;

    // Exception?
    if (exception.isPresent()) {
      ResponseWriter<?> responseWriter =
          responseWriterForException(httpServletRequest, httpServletResponse, route.get(), exception.get());
      responseWriter.writeResponse(httpServletRequest, httpServletResponse, Optional.empty(), route, exception);
      return;
    }

    // Normal response
    if (response.get() instanceof PageResponse)
      this.pageResponseWriter.writeResponse(httpServletRequest, httpServletResponse,
        Optional.of((PageResponse) response.get()), route, exception);
    else if (response.get() instanceof ApiResponse)
      this.apiResponseWriter.writeResponse(httpServletRequest, httpServletResponse,
        Optional.of((ApiResponse) response.get()), route, exception);
    else if (response.get() instanceof BinaryResponse)
      this.binaryResponseWriter.writeResponse(httpServletRequest, httpServletResponse,
        Optional.of((BinaryResponse) response.get()), route, exception);
    else if (response.get() instanceof RedirectResponse)
      this.redirectResponseWriter.writeResponse(httpServletRequest, httpServletResponse,
        Optional.of((RedirectResponse) response.get()), route, exception);
    else
      throw new IllegalArgumentException(format(
        "Not sure what to do with resource method return value of type %s. Resource method was %s", response.get()
          .getClass(), route.get().resourceMethod()));
  }

  protected void writeAdditionalHeaders(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<Route> route, Optional<Object> response, Optional<Exception> exception) {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);
    requireNonNull(response);
    requireNonNull(exception);

    // Never cache
    httpServletResponse.setHeader("Pragma", "no-cache");
    httpServletResponse.setHeader("Cache-Control", "no-cache, no-store");
    httpServletResponse.setHeader("Expires", "0");
  }

  protected int statusForResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<Route> route, Optional<Object> response, Optional<Exception> exception) {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);
    requireNonNull(response);
    requireNonNull(exception);

    if (!route.isPresent())
      return 404;

    if (exception.isPresent())
      return exceptionStatusMapper.statusForException(exception.get());

    if (!response.isPresent())
      return 204;

    if (response.get() instanceof Response)
      return ((Response) response.get()).status();

    return 200;
  }

  protected ResponseWriter<?> responseWriterForException(HttpServletRequest httpServletRequest,
      HttpServletResponse httpServletResponse, Route route, Exception exception) {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);
    requireNonNull(exception);

    // Note: if your API resource method is declared to return something other than ApiResponse (e.g. Object), this will
    // not work. You should subclass and override this method to compensate, possibly using a condition like
    // route.resourcePath().path().startsWith("/api/")
    if (ApiResponse.class.isAssignableFrom(route.resourceMethod().getReturnType()))
      return apiResponseWriter;

    return pageResponseWriter;
  }

  protected ResponseWriter<?> responseWriterForMissingRoute(HttpServletRequest httpServletRequest,
      HttpServletResponse httpServletResponse) {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);

    return pageResponseWriter;
  }
}