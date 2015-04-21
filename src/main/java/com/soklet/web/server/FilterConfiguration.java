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
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class FilterConfiguration {
  private static final Set<DispatcherType> DEFAULT_DISPATCHER_TYPES = unmodifiableSet(EnumSet
    .of(DispatcherType.REQUEST));

  private final Class<? extends Filter> filterClass;
  private final String urlPattern;
  private final Set<DispatcherType> dispatcherTypes;
  private final Map<String, String> initParameters;

  public FilterConfiguration(Class<? extends Filter> filterClass, String urlPattern) {
    this.filterClass = requireNonNull(filterClass);
    this.urlPattern = requireNonNull(urlPattern);
    this.initParameters = emptyMap();
    this.dispatcherTypes = DEFAULT_DISPATCHER_TYPES;
  }

  public FilterConfiguration(Class<? extends Filter> filterClass, String urlPattern, Map<String, String> initParameters) {
    this.filterClass = requireNonNull(filterClass);
    this.urlPattern = requireNonNull(urlPattern);
    this.initParameters = unmodifiableMap(new HashMap<>(requireNonNull(initParameters)));
    this.dispatcherTypes = DEFAULT_DISPATCHER_TYPES;
  }

  public FilterConfiguration(Class<? extends Filter> filterClass, String urlPattern,
      Set<DispatcherType> dispatcherTypes, Map<String, String> initParameters) {
    this.filterClass = requireNonNull(filterClass);
    this.urlPattern = requireNonNull(urlPattern);
    this.dispatcherTypes = unmodifiableSet(EnumSet.copyOf(dispatcherTypes));
    this.initParameters = unmodifiableMap(new HashMap<>(requireNonNull(initParameters)));
  }

  @Override
  public String toString() {
    return format("%s{filterClass=%s, urlPattern=%s, dispatcherTypes=%s, initParameters=%s}", getClass()
      .getSimpleName(), filterClass().getName(), urlPattern(), dispatcherTypes(), initParameters());
  }

  @Override
  public boolean equals(Object object) {
    if (this == object)
      return true;

    if (!(object instanceof FilterConfiguration))
      return false;

    FilterConfiguration filterConfiguration = (FilterConfiguration) object;

    return Objects.equals(filterClass(), filterConfiguration.filterClass())
        && Objects.equals(urlPattern(), filterConfiguration.urlPattern())
        && Objects.equals(dispatcherTypes(), filterConfiguration.dispatcherTypes())
        && Objects.equals(initParameters(), filterConfiguration.initParameters());
  }

  @Override
  public int hashCode() {
    return Objects.hash(filterClass(), urlPattern(), dispatcherTypes(), initParameters());
  }

  public Class<? extends Filter> filterClass() {
    return filterClass;
  }

  public String urlPattern() {
    return urlPattern;
  }

  public Map<String, String> initParameters() {
    return initParameters;
  }

  public Set<DispatcherType> dispatcherTypes() {
    return dispatcherTypes;
  }
}