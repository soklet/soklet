/*
 * Copyright 2022-2026 Revetware LLC.
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

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Method;
import java.util.Objects;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Represents a <em>Resource Method</em>, which is a Java {@link Method} invoked by Soklet to handle an HTTP request.
 * <p>
 * Instances can be acquired via the {@link #withComponents(HttpMethod, ResourcePathDeclaration, Method, Boolean)} factory method.
 * <p>
 * Detailed documentation available at <a href="https://www.soklet.com/docs/request-handling">https://www.soklet.com/docs/request-handling</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ResourceMethod {
	@NonNull
	private final HttpMethod httpMethod;
	@NonNull
	private final ResourcePathDeclaration resourcePathDeclaration;
	@NonNull
	private final Method method;
	@NonNull
	private final Boolean serverSentEventSource;

	/**
	 * Vends a <em>Resource Method</em> given its unique components.
	 *
	 * @param httpMethod              an HTTP method
	 * @param resourcePathDeclaration an HTTP path which might contain placeholders, e.g. {@code /example/{exampleId}}
	 * @param method                  a Java method to invoke for the combination of HTTP method and resource path
	 * @param serverSentEventSource   is this <em>Resource Method</em> configured as a Server-Sent Event source?
	 * @return a <em>Resource Method</em> for the supplied components
	 */
	@NonNull
	public static ResourceMethod withComponents(@NonNull HttpMethod httpMethod,
																							@NonNull ResourcePathDeclaration resourcePathDeclaration,
																							@NonNull Method method,
																							@NonNull Boolean serverSentEventSource) {
		requireNonNull(httpMethod);
		requireNonNull(resourcePathDeclaration);
		requireNonNull(method);
		requireNonNull(serverSentEventSource);

		return new ResourceMethod(httpMethod, resourcePathDeclaration, method, serverSentEventSource);
	}

	private ResourceMethod(@NonNull HttpMethod httpMethod,
												 @NonNull ResourcePathDeclaration resourcePathDeclaration,
												 @NonNull Method method,
												 @NonNull Boolean serverSentEventSource) {
		requireNonNull(httpMethod);
		requireNonNull(resourcePathDeclaration);
		requireNonNull(method);
		requireNonNull(serverSentEventSource);

		this.httpMethod = httpMethod;
		this.resourcePathDeclaration = resourcePathDeclaration;
		this.method = method;
		this.serverSentEventSource = serverSentEventSource;
	}

	@Override
	public String toString() {
		return format("%s{httpMethod=%s, resourcePathDeclaration=%s, method=%s, serverSentEventSource=%s}", getClass().getSimpleName(),
				getHttpMethod(), getResourcePathDeclaration(), getMethod(), isServerSentEventSource());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof ResourceMethod resourceMethod))
			return false;

		return Objects.equals(getHttpMethod(), resourceMethod.getHttpMethod())
				&& Objects.equals(getResourcePathDeclaration(), resourceMethod.getResourcePathDeclaration())
				&& Objects.equals(getMethod(), resourceMethod.getMethod())
				&& Objects.equals(isServerSentEventSource(), resourceMethod.isServerSentEventSource());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getHttpMethod(), getResourcePathDeclaration(), getMethod(), isServerSentEventSource());
	}

	/**
	 * Returns the HTTP method for this <em>Resource Method</em>.
	 *
	 * @return the HTTP method
	 */
	@NonNull
	public HttpMethod getHttpMethod() {
		return this.httpMethod;
	}

	/**
	 * Returns the HTTP path for this <em>Resource Method</em>, which might contain placeholders - for example, {@code /example/{exampleId}}.
	 *
	 * @return the HTTP path
	 */
	@NonNull
	public ResourcePathDeclaration getResourcePathDeclaration() {
		return this.resourcePathDeclaration;
	}

	/**
	 * Returns the Java method to invoke for the combination of HTTP method and resource path.
	 *
	 * @return the Java method to invoke
	 */
	@NonNull
	public Method getMethod() {
		return this.method;
	}

	/**
	 * Returns whether this <em>Resource Method</em> functions as a Server-Sent Event Source.
	 *
	 * @return {@code true} if this <em>Resource Method</em> functions as a Server-Sent Event Source, {@code false} otherwise
	 */
	@NonNull
	public Boolean isServerSentEventSource() {
		return this.serverSentEventSource;
	}
}
