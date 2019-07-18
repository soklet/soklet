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

import com.soklet.util.RequestUtils;
import com.soklet.util.RequestUtils.QueryStringParseStrategy;
import com.soklet.util.ResponseUtils;
import com.soklet.web.HttpMethod;
import com.soklet.web.response.ResponseHandler;
import com.soklet.web.routing.Route;
import com.soklet.web.routing.RouteMatcher;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.soklet.util.FormatUtils.httpServletRequestDescription;
import static com.soklet.util.FormatUtils.stackTraceForThrowable;
import static com.soklet.util.IoUtils.copyStreamToBytesCloseAfterwards;
import static java.lang.String.format;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.FINE;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.2
 */
@Singleton
public class SokletFilter implements Filter {
	public static String STATIC_FILES_URL_PATTERN_PARAM = "STATIC_FILES_URL_PATTERN";

	private final RouteMatcher routeMatcher;
	private final ResponseHandler responseHandler;
	private Optional<String> staticFilesUrlPattern = Optional.empty();
	private final Logger logger = Logger.getLogger(SokletFilter.class.getName());

	@Inject
	public SokletFilter(RouteMatcher routeMatcher, ResponseHandler responseHandler) {
		this.routeMatcher = Objects.requireNonNull(routeMatcher);
		this.responseHandler = Objects.requireNonNull(responseHandler);
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		this.staticFilesUrlPattern = Optional.ofNullable(filterConfig.getInitParameter(STATIC_FILES_URL_PATTERN_PARAM));
	}

	@Override
	public void destroy() {
	}

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

		logRequestStart(httpServletRequest);

		HttpMethod httpMethod = HttpMethod.valueOf(httpServletRequest.getMethod().toUpperCase(ENGLISH));
		String requestPath = httpServletRequest.getPathInfo();
		Optional<Route> route = routeMatcher.match(httpMethod, requestPath);

		if (shouldAllowRequestBodyRepeatableReads(httpServletRequest, httpServletResponse, route))
			httpServletRequest = new SokletHttpServletRequest(httpServletRequest);

		try {
			RequestContext.perform(new RequestContext(httpServletRequest, httpServletResponse, route), (requestContext) -> {
				filterChain.doFilter(requestContext.httpServletRequest(), requestContext.httpServletResponse());
			});
		} catch(Exception e) {
			logException(httpServletRequest, httpServletResponse, route, Optional.empty(), e);

			try {
				responseHandler.handleResponse(httpServletRequest, httpServletResponse, route, Optional.empty(), Optional.of(e));
			} catch (Exception e2) {
				logger.warning(format(
						"Exception occurred while trying to handle an error response, falling back to a failsafe response...\n%s",
						stackTraceForThrowable(e2)));

				writeFailsafeErrorResponse(httpServletRequest, httpServletResponse);
			}
		} finally {
			logRequestEnd(httpServletRequest, nanoTime() - time);
		}
	}

	protected void logRequestStart(HttpServletRequest httpServletRequest) {
		if (logger.isLoggable(FINE))
			logger.fine(format("Received %s", httpServletRequestDescription(httpServletRequest)));
	}

	protected void logRequestEnd(HttpServletRequest httpServletRequest, long elapsedNanoTime) {
		if (logger.isLoggable(FINE))
			logger.fine(format("Took %.2fms to handle %s", elapsedNanoTime / 1_000_000f,
					httpServletRequestDescription(httpServletRequest)));
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
	 * @param httpServletRequest  Servlet request
	 * @param httpServletResponse Servlet request
	 * @param route               The route for this request
	 * @return {@code true} if the filter should be applied, {@code false} otherwise
	 */
	public boolean shouldAllowRequestBodyRepeatableReads(HttpServletRequest httpServletRequest,
																											 HttpServletResponse httpServletResponse, Optional<Route> route) {
		requireNonNull(httpServletRequest);
		requireNonNull(httpServletResponse);
		requireNonNull(route);

		return true;
	}

	protected void logException(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
															Optional<Route> route, Optional<Object> response, Exception exception) {
		requireNonNull(httpServletRequest);
		requireNonNull(httpServletResponse);
		requireNonNull(route);
		requireNonNull(response);
		requireNonNull(exception);

		ResponseUtils.logException(httpServletRequest, httpServletResponse, route, response, exception);
	}

	protected void writeFailsafeErrorResponse(HttpServletRequest httpServletRequest,
																						HttpServletResponse httpServletResponse) throws ServletException, IOException {
		requireNonNull(httpServletRequest);
		requireNonNull(httpServletResponse);

		ResponseUtils.writeFailsafeErrorResponse(httpServletRequest, httpServletResponse);
	}

	/**
	 * This class is not threadsafe!
	 */
	protected static class SokletHttpServletRequest extends HttpServletRequestWrapper {
		private final byte[] requestBody;
		private Map<String, String[]> cachedParameterMap;
		private Map<String, String[]> cachedQueryParameterMap = Collections.emptyMap();
		private Map<String, String[]> cachedFormParameterMap = Collections.emptyMap();
		private final Logger logger = Logger.getLogger(SokletHttpServletRequest.class.getName());

		public SokletHttpServletRequest(HttpServletRequest httpServletRequest) {
			super(requireNonNull(httpServletRequest));
			try {
				this.requestBody =
						httpServletRequest.getInputStream() == null ? new byte[]{}
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

		@Override
		public Map<String, String[]> getParameterMap() {
			ensureParameterMapsArePopulated();
			return cachedParameterMap;
		}

		@Override
		public String getParameter(String name) {
			return getParameter(name, getParameterMap());
		}

		@Override
		public Enumeration<String> getParameterNames() {
			return getParameterNames(getParameterMap());
		}

		@Override
		public String[] getParameterValues(String name) {
			return getParameterMap().get(name);
		}

		public Map<String, String[]> getQueryParameterMap() {
			ensureParameterMapsArePopulated();
			return cachedQueryParameterMap;
		}

		public String getQueryParameter(String name) {
			return getParameter(name, getQueryParameterMap());
		}

		public Enumeration<String> getQueryParameterNames() {
			return getParameterNames(getQueryParameterMap());
		}

		public String[] getQueryParameterValues(String name) {
			return getQueryParameterMap().get(name);
		}

		public Map<String, String[]> getFormParameterMap() {
			ensureParameterMapsArePopulated();
			return cachedFormParameterMap;
		}

		public String getFormParameter(String name) {
			return getParameter(name, getFormParameterMap());
		}

		public Enumeration<String> getFormParameterNames() {
			return getParameterNames(getFormParameterMap());
		}

		public String[] getFormParameterValues(String name) {
			return getFormParameterMap().get(name);
		}

		public boolean isMultipart() {
			return getContentType() != null && getContentType().toLowerCase().indexOf("multipart/form-data") != -1;
		}

		protected String getParameter(String name, Map<String, String[]> parameterMap) {
			if (parameterMap == null)
				return null;

			String[] values = parameterMap.get(name);
			return values != null && values.length > 0 ? values[0] : null;
		}

		protected Enumeration<String> getParameterNames(Map<String, String[]> parameterMap) {
			return new Vector(parameterMap.keySet()).elements();
		}

		protected void ensureParameterMapsArePopulated() {
			if (cachedParameterMap != null)
				return;

			cachedParameterMap = new HashMap<>();

			Map<String, List<String>> queryParameterMap = Collections.emptyMap();

			try {
				queryParameterMap = RequestUtils.parseQueryString(getQueryString(), QueryStringParseStrategy.EXCLUDE_NULL_VALUES);
			} catch (Exception e) {
				logger.log(Level.WARNING, format("Unable to parse query parameters. Query string was %s", getQueryString()), e);
			}

			Map<String, List<String>> formParameterMap = Collections.emptyMap();

			// Eventually we should support multipart as well
			if (requestBody != null && !isMultipart()) {
				try {
					formParameterMap =
							RequestUtils.parseQueryString(new String(requestBody, UTF_8), QueryStringParseStrategy.EXCLUDE_NULL_VALUES);
				} catch (Exception e) {
					logger.log(Level.WARNING, "Unable to parse request body", e);
				}
			}

			// Combine query and form parameters
			Map<String, List<String>> parameterMap = new HashMap<>(queryParameterMap.size() + formParameterMap.size());

			// First, add all query params
			parameterMap.putAll(queryParameterMap);

			// Then, merge in form params
			for (Entry<String, List<String>> entry : formParameterMap.entrySet()) {
				String name = entry.getKey();
				List<String> values = entry.getValue();

				List<String> parameterValues = parameterMap.get(name);

				if (parameterValues == null) {
					parameterValues = new ArrayList<>();
					parameterMap.put(name, parameterValues);
				}

				parameterValues.addAll(values);
			}

			cachedQueryParameterMap = Collections.unmodifiableMap(convertParameterMap(queryParameterMap));
			cachedFormParameterMap = Collections.unmodifiableMap(convertParameterMap(formParameterMap));
			cachedParameterMap = Collections.unmodifiableMap(convertParameterMap(parameterMap));
		}

		protected Map<String, String[]> convertParameterMap(Map<String, List<String>> parameterMap) {
			if (parameterMap == null)
				return Collections.emptyMap();

			Map<String, String[]> convertedParameterMap = new HashMap<>(parameterMap.size());

			for (Entry<String, List<String>> entry : parameterMap.entrySet())
				convertedParameterMap.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().toArray(new String[0]));

			return convertedParameterMap;
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