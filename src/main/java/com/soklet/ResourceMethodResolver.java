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
 * A <em>Resource Method</em> is a Java method with an HTTP method annotation applied, e.g. {@link com.soklet.annotation.GET}, {@link com.soklet.annotation.POST}, ...
 * <p>
 * Standard threadsafe implementations can be acquired via these factory methods:
 * <ul>
 *   <li>{@link #fromClasspathIntrospection()} (examines methods within classes on which the {@link com.soklet.annotation.Resource} annotation is applied)</li>
 *   <li>{@link #withResourceClasses(Set)} (examines methods within the hardcoded set of classes)</li>
 *   <li>{@link #withMethods(Set)} (examines the hardcoded set of methods)</li>
 * </ul>
 * <p>
 * It is likely that one or more of the above implementations is sufficient for your application and test suite.
 * <p>
 * However, should a custom implementation be necessary, documentation is available at <a href="https://www.soklet.com/docs/request-handling#resource-method-resolution">https://www.soklet.com/docs/request-handling#resource-method-resolution</a>.
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

	/**
	 * Acquires a threadsafe {@link ResourceMethodResolver} implementation which locates <em>Resource Methods</em> by examining methods of all classes annotated with {@link com.soklet.annotation.Resource}.
	 * <p>
	 * This implementation requires that your application be compiled with the {@link com.soklet.annotation.SokletProcessor} annotation processor, as shown below:
	 * <p>
	 * <pre>javac -processor com.soklet.annotation.SokletProcessor ...[rest of javac parameters elided]</pre>
	 * <p>
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 *
	 * @return a {@code ResourceMethodResolver} which performs classpath introspection against the annotation processor's lookup table
	 */
	@Nonnull
	static ResourceMethodResolver fromClasspathIntrospection() {
		return DefaultResourceMethodResolver.fromClasspathIntrospection();
	}

	/**
	 * Acquires a threadsafe {@link ResourceMethodResolver} implementation which locates <em>Resource Methods</em> by examining the provided {@code resourceClasses}.
	 * <p>
	 * This method is guaranteed to return a new instance.
	 *
	 * @param resourceClasses the classes to inspect for <em>Resource Methods</em>
	 * @return a {@code ResourceMethodResolver} backed by the given {@code resourceClasses}
	 */
	@Nonnull
	static ResourceMethodResolver withResourceClasses(@Nonnull Set<Class<?>> resourceClasses) {
		requireNonNull(resourceClasses);
		return DefaultResourceMethodResolver.withResourceClasses(resourceClasses);
	}

	/**
	 * Acquires a threadsafe {@link ResourceMethodResolver} implementation which locates <em>Resource Methods</em> by examining the provided {@code methods}.
	 * <p>
	 * This method is guaranteed to return a new instance.
	 *
	 * @param methods the methods to inspect for <em>Resource Method</em> annotations
	 * @return a {@code ResourceMethodResolver} backed by the given {@code methods}
	 */
	@Nonnull
	static ResourceMethodResolver withMethods(@Nonnull Set<Method> methods) {
		requireNonNull(methods);
		return DefaultResourceMethodResolver.withMethods(methods);
	}
}
