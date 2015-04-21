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

package com.soklet.web.exception;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class IllegalQueryParameterException extends BadRequestException {
  private final String queryParameterName;
  private final Optional<String> queryParameterValue;

  public IllegalQueryParameterException(String message, String queryParameterName, Optional<String> queryParameterValue) {
    super(requireNonNull(message));
    this.queryParameterName = requireNonNull(queryParameterName);
    this.queryParameterValue = requireNonNull(queryParameterValue);
  }

  public IllegalQueryParameterException(String message, Optional<Throwable> cause, String queryParameterName,
      Optional<String> queryParameterValue) {
    super(requireNonNull(message), requireNonNull(cause));
    this.queryParameterName = requireNonNull(queryParameterName);
    this.queryParameterValue = requireNonNull(queryParameterValue);
  }

  public String queryParameterName() {
    return queryParameterName;
  }

  public Optional<String> queryParameterValue() {
    return queryParameterValue;
  }
}