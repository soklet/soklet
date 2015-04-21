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
 * Indicates that an illegally-formatted HTTP request was made.
 * <p>
 * Examples:
 * <ul>
 * <li>Missing query parameter</li>
 * <li>incorrectly-formatted path parameter</li>
 * </ul>
 * <p>
 * This normally corresponds to an HTTP {@code 400} response.
 * 
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class BadRequestException extends RuntimeException {
  /**
   * 
   * @param message
   */
  public BadRequestException(String message) {
    super(requireNonNull(message));
  }

  public BadRequestException(String message, Optional<Throwable> cause) {
    super(requireNonNull(message), requireNonNull(cause).orElse(null));
  }

  public BadRequestException(Throwable cause) {
    super(requireNonNull(cause));
  }
}