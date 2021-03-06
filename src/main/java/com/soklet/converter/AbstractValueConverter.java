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

import static java.lang.String.format;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public abstract class AbstractValueConverter<F, T> implements ValueConverter<F, T> {
  private final Type fromType;
  private final Type toType;

  public AbstractValueConverter() {
    Type fromType = null;
    Type toType = null;

    // TODO: this only works for simple cases (direct subclass or direct use of ValueConverter interface) and doesn't do
    // full error handling. Probably also want to pull this into a ReflectionUtils class
    List<Type> genericInterfaces = Arrays.asList(getClass().getGenericInterfaces());

    // If not direct use of interface, try superclass (no error handling done yet)
    if (genericInterfaces.size() == 0)
      genericInterfaces = Arrays.asList(getClass().getGenericSuperclass());

    // Figure out what the two type arguments are for ValueConverter
    for (Type genericInterface : genericInterfaces) {
      if (genericInterface instanceof ParameterizedType) {
        Object rawType = ((ParameterizedType) genericInterface).getRawType();

        if (!ValueConverter.class.isAssignableFrom((Class<?>) rawType))
          continue;

        Type[] genericTypes = ((ParameterizedType) genericInterface).getActualTypeArguments();
        fromType = genericTypes[0];
        toType = genericTypes[1];
      }
    }

    if (fromType == null || toType == null)
      throw new IllegalStateException(format("Unable to extract generic %s type information from %s",
        ValueConverter.class.getSimpleName(), this));

    this.fromType = fromType;
    this.toType = toType;
  }

  @Override
  public Type fromType() {
    return fromType;
  }

  @Override
  public Type toType() {
    return toType;
  }
}