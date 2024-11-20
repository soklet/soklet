/*
 * Copyright 2022-2024 Revetware LLC.
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
 * Represents a <em>Resource Method</em>, which is a Java {@link Method} invoked by Soklet to handle an HTTP request.
 * <p>
 * See <a href="https://www.soklet.com/docs/request-handling">https://www.soklet.com/docs/request-handling</a> for details.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResourceMethod {
	@Nonnull
	private final HttpMethod httpMethod;
	@Nonnull
	private final ResourcePath resourcePath;
	@Nonnull
	private final Method method;
	@Nonnull
	private final Boolean serverSentEventSource;

	/**
	 * Vends a <em>Resource Method</em> given its unique components.
	 *
	 * @param httpMethod            an HTTP method
	 * @param resourcePath          an HTTP path which might contain placeholders, e.g. {@code /example/{exampleId}}
	 * @param method                a Java method to invoke for the combination of HTTP method and resource path
	 * @param serverSentEventSource is this <em>Resource Method</em> configured as a server-sent event source?
	 * @return a <em>Resource Method</em> for the supplied components
	 */
	@Nonnull
	public static ResourceMethod withComponents(@Nonnull HttpMethod httpMethod,
																							@Nonnull ResourcePath resourcePath,
																							@Nonnull Method method,
																							@Nonnull Boolean serverSentEventSource) {
		requireNonNull(httpMethod);
		requireNonNull(resourcePath);
		requireNonNull(method);
		requireNonNull(serverSentEventSource);

		return new ResourceMethod(httpMethod, resourcePath, method, serverSentEventSource);
	}

	protected ResourceMethod(@Nonnull HttpMethod httpMethod,
													 @Nonnull ResourcePath resourcePath,
													 @Nonnull Method method,
													 @Nonnull Boolean serverSentEventSource) {
		requireNonNull(httpMethod);
		requireNonNull(resourcePath);
		requireNonNull(method);
		requireNonNull(serverSentEventSource);

		this.httpMethod = httpMethod;
		this.resourcePath = resourcePath;
		this.method = method;
		this.serverSentEventSource = serverSentEventSource;
	}

	@Override
	public String toString() {
		return format("%s{httpMethod=%s, resourcePath=%s, method=%s, serverSentEventSource=%s}", getClass().getSimpleName(),
				getHttpMethod(), getResourcePath(), getMethod(), isServerSentEventSource());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof ResourceMethod resourceMethod))
			return false;

		return Objects.equals(getHttpMethod(), resourceMethod.getHttpMethod())
				&& Objects.equals(getResourcePath(), resourceMethod.getResourcePath())
				&& Objects.equals(getMethod(), resourceMethod.getMethod())
				&& Objects.equals(isServerSentEventSource(), resourceMethod.isServerSentEventSource());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getHttpMethod(), getResourcePath(), getMethod(), isServerSentEventSource());
	}

	/**
	 * Returns the HTTP method for this <em>Resource Method</em>.
	 *
	 * @return the HTTP method
	 */
	@Nonnull
	public HttpMethod getHttpMethod() {
		return this.httpMethod;
	}

	/**
	 * Returns the HTTP path for this <em>Resource Method</em>, which might contain placeholders - for example, {@code /example/{exampleId}}.
	 *
	 * @return the HTTP path
	 */
	@Nonnull
	public ResourcePath getResourcePath() {
		return this.resourcePath;
	}

	/**
	 * Returns the Java method to invoke for the combination of HTTP method and resource path.
	 *
	 * @return the Java method to invoke
	 */
	@Nonnull
	public Method getMethod() {
		return this.method;
	}

	/**
	 * Returns whether or not this <em>Resource Method</em> functions as a Server-Sent Event Source.
	 *
	 * @return {@code true} if this <em>Resource Method</em> functions as a Server-Sent Event Source, {@code false} otherwise
	 */
	@Nonnull
	public Boolean isServerSentEventSource() {
		return this.serverSentEventSource;
	}
}
