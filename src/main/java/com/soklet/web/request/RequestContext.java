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

package com.soklet.web.request;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.soklet.web.routing.Route;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.0
 */
public class RequestContext {
  private static final ThreadLocal<RequestContext> REQUEST_CONTEXT_HOLDER = new ThreadLocal<>();

  private final HttpServletRequest httpServletRequest;
  private final HttpServletResponse httpServletResponse;
  private final Optional<Route> route;

  public RequestContext(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<Route> route) {
    this.httpServletRequest = requireNonNull(httpServletRequest);
    this.httpServletResponse = requireNonNull(httpServletResponse);
    this.route = requireNonNull(route);
  }

  public static void set(RequestContext requestContext) {
    requireNonNull(requestContext);
    REQUEST_CONTEXT_HOLDER.set(requestContext);
  }

  /**
   * @throws IllegalStateException
   *           if no {@code RequestContext} has been set
   */
  public static RequestContext get() {
    RequestContext requestContext = REQUEST_CONTEXT_HOLDER.get();

    if (requestContext == null)
      throw new IllegalStateException(format("No %s was set for this request.", RequestContext.class.getSimpleName()));

    return requestContext;
  }

  public static void clear() {
    REQUEST_CONTEXT_HOLDER.remove();
  }

  public HttpServletRequest httpServletRequest() {
    return this.httpServletRequest;
  }

  public HttpServletResponse httpServletResponse() {
    return this.httpServletResponse;
  }

  public Optional<Route> route() {
    return this.route;
  }
}