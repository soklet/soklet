/*
 * Copyright 2022 Revetware LLC.
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

package com.soklet.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Method;
import java.util.Objects;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class ResourceMethod {
	@Nonnull
	private final HttpMethod httpMethod;
	@Nonnull
	private final ResourcePath resourcePath;
	@Nonnull
	private final Method method;

	public ResourceMethod(@Nonnull HttpMethod httpMethod,
												@Nonnull ResourcePath resourcePath,
												@Nonnull Method method) {
		requireNonNull(httpMethod);
		requireNonNull(resourcePath);
		requireNonNull(method);

		this.httpMethod = httpMethod;
		this.resourcePath = resourcePath;
		this.method = method;
	}

	@Override
	public String toString() {
		return format("%s{httpMethod=%s, resourcePath=%s, method=%s}", getClass().getSimpleName(),
				getHttpMethod(), getResourcePath(), getMethod());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof ResourceMethod))
			return false;

		ResourceMethod resourceMethod = (ResourceMethod) object;

		return Objects.equals(getHttpMethod(), resourceMethod.getHttpMethod())
				&& Objects.equals(getResourcePath(), resourceMethod.getResourcePath())
				&& Objects.equals(getMethod(), resourceMethod.getMethod());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getHttpMethod(), getResourcePath(), getMethod());
	}

	@Nonnull
	public HttpMethod getHttpMethod() {
		return this.httpMethod;
	}

	@Nonnull
	public ResourcePath getResourcePath() {
		return this.resourcePath;
	}

	@Nonnull
	public Method getMethod() {
		return this.method;
	}
}
