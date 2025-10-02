/*
 * Copyright 2022-2025 Revetware LLC.
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

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Contract for matching incoming HTTP requests with appropriate <em>Resource Methods</em> (Java methods to invoke to handle requests).
 * <p>
 * Standard implementations can be acquired via these factory methods:
 * <ul>
 *   <li>{@link #withClasspathIntrospection()}</li>
 *   <li>{@link #withResourceClasses(Set)}</li>
 *   <li>{@link #withMethods(Set)}</li>
 * </ul>
 * <p>
 * However, should a custom implementation be necessary for your application, documentation is available at <a href="https://www.soklet.com/docs/request-handling#resource-method-resolution">https://www.soklet.com/docs/request-handling#resource-method-resolution</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface ResourceMethodResolver {
	/**
	 * Given an HTTP request, provide a matching <em>Resource Method</em> to invoke.
	 * <p>
	 * An unmatched <em>Resource Method</em> generally indicates an {@code HTTP 404}.
	 *
	 * @param request the HTTP request
	 * @return the matching <em>Resource Method</em>, or {@link Optional#empty()} if no match was found
	 */
	@Nonnull
	Optional<ResourceMethod> resourceMethodForRequest(@Nonnull Request request);

	/**
	 * Vends the set of all <em>Resource Methods</em> registered in the system.
	 *
	 * @return the set of all <em>Resource Methods</em> in the system
	 */
	@Nonnull
	Set<ResourceMethod> getResourceMethods();
	
	@Nonnull
	static ResourceMethodResolver withClasspathIntrospection() {
		return DefaultResourceMethodResolver.withClasspathIntrospection();
	}

	@Nonnull
	static ResourceMethodResolver withResourceClasses(@Nonnull Set<Class<?>> resourceClasses) {
		requireNonNull(resourceClasses);
		return DefaultResourceMethodResolver.withResourceClasses(resourceClasses);
	}

	@Nonnull
	static ResourceMethodResolver withMethods(@Nonnull Set<Method> methods) {
		requireNonNull(methods);
		return DefaultResourceMethodResolver.withMethods(methods);
	}
}
