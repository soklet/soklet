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
 * Indicates that an HTTP request was made with an incorrect method.
 * <p>
 * Example: the client specified {@code POST} instead of {@code PUT}.
 * <p>
 * This normally corresponds to an HTTP {@code 405} response.
 * 
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class MethodNotAllowedException extends RuntimeException {
  public MethodNotAllowedException(String message) {
    super(requireNonNull(message));
  }

  public MethodNotAllowedException(String message, Optional<Throwable> cause) {
    super(requireNonNull(message), requireNonNull(cause).orElse(null));
  }

  public MethodNotAllowedException(Throwable cause) {
    super(requireNonNull(cause));
  }
}