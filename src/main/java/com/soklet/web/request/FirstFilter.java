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

import static com.soklet.util.FormatUtils.httpServletRequestDescription;
import static com.soklet.util.IoUtils.copyStreamToBytesCloseAfterwards;
import static java.lang.String.format;
import static java.lang.System.nanoTime;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.FINE;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import com.soklet.web.HttpMethod;
import com.soklet.web.routing.Route;
import com.soklet.web.routing.RouteMatcher;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.2
 */
@Singleton
public class FirstFilter implements Filter {
  public static String STATIC_FILES_URL_PATTERN_PARAM = "STATIC_FILES_URL_PATTERN";

  private final RouteMatcher routeMatcher;
  private Optional<String> staticFilesUrlPattern = Optional.empty();
  private final Logger logger = Logger.getLogger(FirstFilter.class.getName());

  @Inject
  public FirstFilter(RouteMatcher routeMatcher) {
    this.routeMatcher = Objects.requireNonNull(routeMatcher);
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    this.staticFilesUrlPattern = Optional.ofNullable(filterConfig.getInitParameter(STATIC_FILES_URL_PATTERN_PARAM));
  }

  @Override
  public void destroy() {}

  @Override
  public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
      throws IOException, ServletException {
    HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
    HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;

    if (shouldSkipFilter(httpServletRequest, httpServletResponse)) {
      HttpServletRequest pinnedHttpServletRequest = httpServletRequest;

      // We still bind request/response to the current thread, but skip route matching and any other work
      RequestContext.perform(new RequestContext(httpServletRequest, httpServletResponse, Optional.empty()), (
          requestContext) -> {
        filterChain.doFilter(pinnedHttpServletRequest, httpServletResponse);
      });
      
      return;
    }

    long time = nanoTime();

    HttpMethod httpMethod = HttpMethod.valueOf(httpServletRequest.getMethod().toUpperCase(ENGLISH));
    String requestPath = httpServletRequest.getPathInfo();

    if (logger.isLoggable(FINE)) logger.fine(format("Received %s", httpServletRequestDescription(httpServletRequest)));

    Optional<Route> route = routeMatcher.match(httpMethod, requestPath);

    if (shouldAllowRequestBodyRepeatableReads(httpServletRequest, httpServletResponse, route))
      httpServletRequest = new RequestBodyRepeatableReadWrapper(httpServletRequest);

    try {
      RequestContext.perform(new RequestContext(httpServletRequest, httpServletResponse, route), (requestContext) -> {
        filterChain.doFilter(requestContext.httpServletRequest(), requestContext.httpServletResponse());
      });
    } finally {
      time = nanoTime() - time;

      if (logger.isLoggable(FINE))
        logger.fine(format("Took %.2fms to handle %s", time / 1_000_000f,
          httpServletRequestDescription(httpServletRequest)));
    }
  }

  /**
   * Default implementation does not apply this filter to any static files
   */
  protected boolean shouldSkipFilter(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);

    if (!this.staticFilesUrlPattern.isPresent()) return false;

    String pathInfo = httpServletRequest.getRequestURI();
    String normalizedStaticFilesUrlPattern = this.staticFilesUrlPattern.get();

    if (normalizedStaticFilesUrlPattern.endsWith("*"))
      normalizedStaticFilesUrlPattern =
          normalizedStaticFilesUrlPattern.substring(0, normalizedStaticFilesUrlPattern.length() - 1);

    return pathInfo.startsWith(normalizedStaticFilesUrlPattern);
  }

  /**
   * Should this request body be cached in-memory to support repeated reads?
   * <p>
   * This is helpful for common cases (request body logging and parsing) but if you have special requirements (need
   * non-blocking I/O or have to handle large request bodies like file uploads) then you should override this method to
   * return {@code false}.
   * <p>
   * By default this method returns {@code true}.
   * 
   * @param httpServletRequest
   *          Servlet request
   * @param httpServletResponse
   *          Servlet request
   * @param route
   *          The route for this request
   * @return {@code true} if the filter should be applied, {@code false} otherwise
   */
  public boolean shouldAllowRequestBodyRepeatableReads(HttpServletRequest httpServletRequest,
      HttpServletResponse httpServletResponse, Optional<Route> route) {
    requireNonNull(httpServletRequest);
    requireNonNull(httpServletResponse);
    requireNonNull(route);

    return true;
  }

  protected static class RequestBodyRepeatableReadWrapper extends HttpServletRequestWrapper {
    private final byte[] requestBody;

    public RequestBodyRepeatableReadWrapper(HttpServletRequest httpServletRequest) {
      super(requireNonNull(httpServletRequest));
      try {
        this.requestBody =
            httpServletRequest.getInputStream() == null ? new byte[] {}
                : copyStreamToBytesCloseAfterwards(httpServletRequest.getInputStream());
      } catch (IOException e) {
        throw new IllegalStateException("Unable to read request body", e);
      }
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      return new RepeatableReadServletInputStream(new ByteArrayInputStream(requestBody));
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String characterEncoding = getCharacterEncoding();
      return new BufferedReader(new InputStreamReader(getInputStream(), characterEncoding == null ? "UTF-8"
          : characterEncoding));
    }

    protected static class RepeatableReadServletInputStream extends ServletInputStream {
      private final InputStream inputStream;

      public RepeatableReadServletInputStream(InputStream inputStream) {
        this.inputStream = requireNonNull(inputStream);
      }

      @Override
      public int read() throws IOException {
        return inputStream.read();
      }

      @Override
      public boolean markSupported() {
        return false;
      }

      @Override
      public boolean isFinished() {
        return true;
      }

      @Override
      public boolean isReady() {
        return false;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        requireNonNull(readListener);

        // Since we've already read the request body, we're immediately done...fire callback right away
        try {
          readListener.onAllDataRead();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }
}