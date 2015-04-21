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

package com.soklet.web.server;

import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.servlet.http.HttpServlet;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class ServletConfiguration {
  private final Class<? extends HttpServlet> servletClass;
  private final Map<String, String> initParameters;
  private final String urlPattern;

  public ServletConfiguration(Class<? extends HttpServlet> servletClass, String urlPattern) {
    this.servletClass = requireNonNull(servletClass);
    this.urlPattern = requireNonNull(urlPattern);
    this.initParameters = emptyMap();
  }

  public ServletConfiguration(Class<? extends HttpServlet> servletClass, String urlPattern,
      Map<String, String> initParameters) {
    this.servletClass = requireNonNull(servletClass);
    this.urlPattern = requireNonNull(urlPattern);
    this.initParameters = unmodifiableMap(new HashMap<>(requireNonNull(initParameters)));
  }

  @Override
  public String toString() {
    return format("%s{servletClass=%s, urlPattern=%s, initParameters=%s}", getClass().getSimpleName(), servletClass()
      .getName(), urlPattern(), initParameters());
  }

  @Override
  public boolean equals(Object object) {
    if (this == object)
      return true;

    if (!(object instanceof FilterConfiguration))
      return false;

    ServletConfiguration servletConfiguration = (ServletConfiguration) object;

    return Objects.equals(servletClass(), servletConfiguration.servletClass())
        && Objects.equals(urlPattern(), servletConfiguration.urlPattern())
        && Objects.equals(initParameters(), servletConfiguration.initParameters());
  }

  @Override
  public int hashCode() {
    return Objects.hash(servletClass(), urlPattern(), initParameters());
  }

  public Class<? extends HttpServlet> servletClass() {
    return servletClass;
  }

  public String urlPattern() {
    return urlPattern;
  }

  public Map<String, String> initParameters() {
    return initParameters;
  }
}