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

package com.soklet.util;

import static java.lang.String.format;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Construct for creating type tokens that represent generic types.
 * <p>
 * In Java, you may express a type token for a non-generic type like {@code String.class}. But you cannot say
 * <code>List&lt;String&gt;.class</code>. Using {@code TypeReference}, you can express the latter as follows:
 * <p>
 * <code>new TypeReference&lt;List&lt;String&gt;&gt;() &#123;&#125;</code>
 * <p>
 * See <a
 * href="http://gafter.blogspot.com/2006/12/super-type-tokens.html">http://gafter.blogspot.com/2006/12/super-type-
 * tokens.html</a> for more details.
 * 
 * @author Neal Gafter
 * @author Bob Lee
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public abstract class TypeReference<T> {
  private final Type type;

  protected TypeReference() {
    Type superclass = getClass().getGenericSuperclass();

    if (superclass instanceof Class)
      throw new IllegalStateException("Missing type parameter.");

    this.type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
  }

  @Override
  public String toString() {
    return format("%s{type=%s}", getClass().getSimpleName(), type());
  }

  @Override
  public boolean equals(Object object) {
    if (this == object)
      return true;

    if (!(object instanceof TypeReference))
      return false;

    TypeReference<?> typeReference = (TypeReference<?>) object;

    return Objects.equals(type(), typeReference.type());
  }

  @Override
  public int hashCode() {
    return Objects.hash(type());
  }

  public Type type() {
    return this.type;
  }
}