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

package com.soklet.web.exception;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.soklet.web.routing.Route;

/**
 * Indicates that an error occurred when executing the resource {@link java.lang.reflect.Method} of a
 * {@link com.soklet.web.routing.Route}.
 * 
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class ResourceMethodExecutionException extends RuntimeException {
  public ResourceMethodExecutionException(Route route, Throwable cause) {
    super(format("An error occurred while executing %s when attempting to handle %s %s", requireNonNull(route)
      .resourceMethod(), requireNonNull(route).httpMethod(), requireNonNull(route).resourcePath().path()), cause);
  }

  public ResourceMethodExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
