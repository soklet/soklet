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
import java.util.Objects;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class RequestContext {
	@Nonnull
	private static final ThreadLocal<RequestContext> REQUEST_CONTEXT_HOLDER = new ThreadLocal<>();

	@Nonnull
	private final Request request;
	@Nullable
	private final ResourceMethod resourceMethod;

	public RequestContext(@Nonnull Request request) {
		this(request, null);
	}

	public RequestContext(@Nonnull Request request,
												@Nullable ResourceMethod resourceMethod) {
		requireNonNull(request);

		this.request = request;
		this.resourceMethod = resourceMethod;
	}

	@Override
	public String toString() {
		return format("%s{request=%s, resourceMethod=%s}", getClass().getSimpleName(),
				getRequest(), (getResourceMethod().isPresent() ? getResourceMethod().get() : "[none]"));
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof RequestContext))
			return false;

		RequestContext requestContext = (RequestContext) object;

		return Objects.equals(getRequest(), requestContext.getRequest())
				&& Objects.equals(getResourceMethod(), requestContext.getResourceMethod());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getRequest(), getResourceMethod());
	}

	@Nonnull
	public static Optional<RequestContext> getCurrent() {
		return Optional.ofNullable(REQUEST_CONTEXT_HOLDER.get());
	}

	public static void setCurrent(@Nullable RequestContext requestContext) {
		if (requestContext == null)
			REQUEST_CONTEXT_HOLDER.remove();
		else
			REQUEST_CONTEXT_HOLDER.set(requestContext);
	}

	@Nonnull
	public Request getRequest() {
		return this.request;
	}

	@Nonnull
	public Optional<ResourceMethod> getResourceMethod() {
		return Optional.ofNullable(this.resourceMethod);
	}
}
