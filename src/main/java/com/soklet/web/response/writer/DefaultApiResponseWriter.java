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

package com.soklet.web.response.writer;

import static com.soklet.util.IoUtils.copyStreamCloseAfterwards;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.soklet.web.response.ApiResponse;
import com.soklet.web.routing.Route;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class DefaultApiResponseWriter implements ApiResponseWriter {
  @Override
  public void writeResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<ApiResponse> response, Optional<Route> route, Optional<Exception> exception) throws IOException {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);
    requireNonNull(response);
    requireNonNull(exception);

    httpServletResponse.setContentType("application/json;charset=UTF-8");

    copyStreamCloseAfterwards(
      new ByteArrayInputStream(
        format(
          "{\n  \"message\": \"In order to use %s, you must provide Soklet with your own %s. See http://soklet.com/response-writers for more information.\"\n}",
          ApiResponse.class.getSimpleName(), ApiResponseWriter.class.getSimpleName()).getBytes(UTF_8)),
      httpServletResponse.getOutputStream());
  }
}