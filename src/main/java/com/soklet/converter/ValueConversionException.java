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

package com.soklet.converter;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Type;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class ValueConversionException extends Exception {
  private Type fromType;
  private Type toType;

  public ValueConversionException(String message, Type fromType, Type toType) {
    super(message);
    this.fromType = requireNonNull(fromType);
    this.toType = requireNonNull(toType);
  }

  public ValueConversionException(Throwable cause, Type fromType, Type toType) {
    super(cause);
    this.fromType = requireNonNull(fromType);
    this.toType = requireNonNull(toType);
  }

  public ValueConversionException(String message, Throwable cause, Type fromType, Type toType) {
    super(message, cause);
    this.fromType = requireNonNull(fromType);
    this.toType = requireNonNull(toType);
  }

  public Type getFromType() {
    return fromType;
  }

  public Type getToType() {
    return toType;
  }
}