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

package com.soklet.web;

import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toSet;

import java.util.Set;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public enum HttpMethod {
  GET, POST, PUT, PATCH, OPTIONS, HEAD, DELETE;

  private static final Set<HttpMethod> VALUES_AS_SET = unmodifiableSet(stream(HttpMethod.values()).map(
    httpMethod -> httpMethod).collect(toSet()));

  public static Set<HttpMethod> valuesAsSet() {
    return VALUES_AS_SET;
  }
}