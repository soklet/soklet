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

package com.soklet.web;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class Route {
  private final HttpMethod httpMethod;
  private final ResourcePath resourcePath;
  private final Method resourceMethod;

  public Route(HttpMethod httpMethod, ResourcePath resourcePath, Method resourceMethod) {
    this.httpMethod = requireNonNull(httpMethod);
    this.resourcePath = requireNonNull(resourcePath);
    this.resourceMethod = requireNonNull(resourceMethod);
  }

  @Override
  public String toString() {
    return format("%s{httpMethod=%s, resourcePath=%s, resourceMethod=%s}", getClass().getSimpleName(), httpMethod(),
      resourcePath(), resourceMethod());
  }

  @Override
  public boolean equals(Object object) {
    if (this == object)
      return true;

    if (!(object instanceof Route))
      return false;

    Route route = (Route) object;

    return Objects.equals(httpMethod(), route.httpMethod()) && Objects.equals(resourcePath(), route.resourcePath())
        && Objects.equals(resourceMethod(), route.resourceMethod());
  }

  @Override
  public int hashCode() {
    return Objects.hash(httpMethod(), resourcePath(), resourceMethod());
  }

  public HttpMethod httpMethod() {
    return httpMethod;
  }

  public ResourcePath resourcePath() {
    return resourcePath;
  }

  public Method resourceMethod() {
    return resourceMethod;
  }
}