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

package com.soklet.web.request;

import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.soklet.web.routing.Route;

/**
 * Contract for executing a Java method to handle an HTTP request.
 *
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public interface RequestHandler {
  /**
   * Invokes the {@code resourceMethod} associated with the given {@code route}.
   * <p>
   * Implementors must ensure {@code resourceMethod} parameters are appropriately set. For example, a parameter
   * annotated with {@link com.soklet.web.annotation.RequestHeader} must have its value set according to the
   * corresponding header in {@code httpServletRequest}.
   * 
   * @param httpServletRequest
   *          Servlet request
   * @param httpServletResponse
   *          Servlet response
   * @param route
   *          The route to invoke
   * @return The result of the {@code route} invocation
   * @throws Exception
   *           An exception thrown during route invocation
   */
  Optional<Object> handleRequest(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Route route) throws Exception;
}