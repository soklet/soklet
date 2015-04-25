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

package com.soklet.web.response;

import java.io.IOException;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.soklet.web.request.RequestHandler;
import com.soklet.web.routing.Route;

/**
 * Contract for writing an HTTP response.
 * <p>
 * Implementors should use
 * {@link #handleResponse(HttpServletRequest, HttpServletResponse, Optional, Optional, Optional)} to write an
 * appropriate HTTP response body, content type, and any relevant headers.
 *
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public interface ResponseHandler {
  /**
   * Determines and writes an appropriate response for the client based on {@link RequestHandler} output.
   * <p>
   * Optionals are as follows:
   * <ul>
   * <li>{@code route}: The route matching the request. Empty if no matching method was found for the request URL
   * (normally you'll want to return a 404 in this case)</li>
   * <li>{@code response}: The return value of the method invoked to handle this request. Empty if the method is
   * declared as {@code void} or returned {@code null}</li>
   * <li>{@code exception}: The exception that occurred during request processing. Empty if no exception occurred</li>
   * </ul>
   * 
   * @param httpServletRequest
   *          Servlet request
   * @param httpServletResponse
   *          Servlet response
   * @param route
   *          The route that matched this request (if any)
   * @param response
   *          The result of the {@code resourceMethod} invocation (if any)
   * @param exception
   *          The exception that occurred when attempting to handle the request (if any)
   * @throws IOException
   *           If an error occurs while writing the response
   */
  void handleResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<Route> route, Optional<Object> response, Optional<Exception> exception) throws IOException;
}