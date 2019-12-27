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

import com.soklet.web.response.PageResponse;
import com.soklet.web.routing.Route;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class DefaultPageResponseWriter implements PageResponseWriter {
  @Override
  public void writeResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<PageResponse> response, Optional<Route> route, Optional<Exception> exception) throws IOException {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);
    requireNonNull(response);
    requireNonNull(exception);

    httpServletResponse.setContentType("text/html;charset=UTF-8");

    String bodyStyle =
        "margin: 24px; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; font-size: 12pt; background-color: #f6f6f6;";
    String preStyle = "padding: 12px; border: 1px solid #bbb; background-color: #fff;";

    copyStreamCloseAfterwards(
      new ByteArrayInputStream(
        format(
          "<html><head></head><body style=\"%s\">"
              + "<p>To render HTML pages, you must provide Soklet with your own <code>%s</code> implementation.</p><p>Using Guice, this might look like the following:</p><pre style=\"%s\">"
              + "@Provides\n"
              + "@Singleton\n"
              + "protected PageResponseWriter providePageResponseWriter() {\n"
              + "  return new PageResponseWriter() {\n"
              + "    @Override\n"
              + "    public void writeResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,\n"
              + "      Optional&lt;PageResponse&gt; response, Optional&lt;Route&gt; route, Optional&lt;Exception&gt; exception) throws IOException {\n"
              + "      // Your code here\n"
              + "    }\n"
              + "  };\n"
              + "}</pre><p>See <a href=\"https://www.soklet.com/response-writers\">https://www.soklet.com/response-writers</a> for more information.</p>",
          bodyStyle, PageResponseWriter.class.getSimpleName(), preStyle).getBytes(UTF_8)),
      httpServletResponse.getOutputStream());
  }
}