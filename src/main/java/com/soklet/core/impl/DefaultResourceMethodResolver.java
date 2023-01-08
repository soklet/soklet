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

package com.soklet.core.impl;

import com.soklet.annotation.DELETE;
import com.soklet.annotation.DELETEs;
import com.soklet.annotation.GET;
import com.soklet.annotation.GETs;
import com.soklet.annotation.HEAD;
import com.soklet.annotation.HEADs;
import com.soklet.annotation.OPTIONS;
import com.soklet.annotation.OPTIONSes;
import com.soklet.annotation.PATCH;
import com.soklet.annotation.PATCHes;
import com.soklet.annotation.POST;
import com.soklet.annotation.POSTs;
import com.soklet.annotation.PUT;
import com.soklet.annotation.PUTs;
import com.soklet.annotation.Resource;
import com.soklet.classindex.ClassIndex;
import com.soklet.core.HttpMethod;
import com.soklet.core.Request;
import com.soklet.core.ResourceMethod;
import com.soklet.core.ResourceMethodResolver;
import com.soklet.core.ResourcePath;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultResourceMethodResolver implements ResourceMethodResolver {
	@Nonnull
	private final Set<Method> resourceMethods;
	@Nonnull
	private final Map<HttpMethod, Set<Method>> resourceMethodsByHttpMethod;
	@Nonnull
	private final Map<Method, Set<HttpMethodResourcePath>> httpMethodResourcePathsByMethod;

	public DefaultResourceMethodResolver() {
		this(StreamSupport.stream(ClassIndex.getAnnotated(Resource.class).spliterator(), true)
				.collect(Collectors.toSet()), null);
	}

	public DefaultResourceMethodResolver(@Nullable Set<Class<?>> resourceClasses,
																			 @Nullable Set<Method> resourceMethods) {
		Set<Method> allResouceMethods = new HashSet<>();

		if (resourceClasses != null)
			allResouceMethods.addAll(extractResourceMethods(resourceClasses));

		if (resourceMethods != null)
			allResouceMethods.addAll(resourceMethods);

		this.resourceMethods = Collections.unmodifiableSet(allResouceMethods);
		this.resourceMethodsByHttpMethod =
				Collections.unmodifiableMap(createResourceMethodsByHttpMethod(getResourceMethods()));
		this.httpMethodResourcePathsByMethod =
				Collections.unmodifiableMap(createHttpMethodResourcePathsByMethod(getResourceMethods()));
	}

	@Nonnull
	@Override
	public Optional<ResourceMethod> resourceMethodForRequest(@Nonnull Request request) {
		requireNonNull(request);

		Set<Method> resourceMethods = getResourceMethodsByHttpMethod().get(request.getHttpMethod());

		if (resourceMethods == null)
			return Optional.empty();

		ResourcePath resourcePath = ResourcePath.fromPathInstance(request.getPath());
		Set<ResourceMethod> matchingResourceMethods = new HashSet<>(4); // Normally there are few (if any) potential matches

		// TODO: faster matching via path component tree structure instead of linear scan
		for (Map.Entry<Method, Set<HttpMethodResourcePath>> entry : getHttpMethodResourcePathsByMethod().entrySet()) {
			Method method = entry.getKey();
			Set<HttpMethodResourcePath> httpMethodResourcePaths = entry.getValue();

			for (HttpMethodResourcePath httpMethodResourcePath : httpMethodResourcePaths)
				if (httpMethodResourcePath.getHttpMethod().equals(request.getHttpMethod())
						&& resourcePath.matches(httpMethodResourcePath.getResourcePath()))
					matchingResourceMethods.add(new ResourceMethod(request.getHttpMethod(), httpMethodResourcePath.getResourcePath(), method));
		}

		// Simple case - exact route match
		if (matchingResourceMethods.size() == 1)
			return matchingResourceMethods.stream().findFirst();

		// Multiple matches are OK so long as one is more specific than any others.
		// If none are a match, we have a problem
		if (matchingResourceMethods.size() > 1) {
			Set<ResourceMethod> mostSpecificResourceMethods = mostSpecificResourceMethods(request, matchingResourceMethods);

			if (mostSpecificResourceMethods.size() == 1)
				return mostSpecificResourceMethods.stream().findFirst();

			throw new RuntimeException(format("Multiple routes match '%s %s'. Ambiguous matches were:\n%s", request.getHttpMethod().name(), request.getPath(),
					matchingResourceMethods.stream()
							.map(matchingResourceMethod -> matchingResourceMethod.getMethod().toString())
							.collect(Collectors.joining("\n"))));
		}

		return Optional.empty();
	}

	@Nonnull
	protected Map<Method, Set<HttpMethodResourcePath>> createHttpMethodResourcePathsByMethod(@Nonnull Set<Method> resourceMethods) {
		requireNonNull(resourceMethods);

		Map<Method, Set<HttpMethodResourcePath>> httpMethodResourcePathsByResourceMethod = new HashMap<>();

		for (Method resourceMethod : resourceMethods) {
			Set<HttpMethodResourcePath> matchedHttpMethodResourcePaths = new HashSet<>();

			for (Annotation annotation : resourceMethod.getAnnotations()) {
				if (annotation instanceof GET) {
					matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.GET, ResourcePath
							.fromPathDeclaration(((GET) annotation).value())));
				} else if (annotation instanceof POST) {
					matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.POST, ResourcePath
							.fromPathDeclaration(((POST) annotation).value())));
				} else if (annotation instanceof PUT) {
					matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.PUT, ResourcePath
							.fromPathDeclaration(((PUT) annotation).value())));
				} else if (annotation instanceof PATCH) {
					matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.PATCH, ResourcePath
							.fromPathDeclaration(((PATCH) annotation).value())));
				} else if (annotation instanceof DELETE) {
					matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.DELETE, ResourcePath
							.fromPathDeclaration(((DELETE) annotation).value())));
				} else if (annotation instanceof OPTIONS) {
					matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.OPTIONS, ResourcePath
							.fromPathDeclaration(((OPTIONS) annotation).value())));
				} else if (annotation instanceof HEAD) {
					matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.HEAD, ResourcePath
							.fromPathDeclaration(((HEAD) annotation).value())));
				} else if (annotation instanceof GETs) {
					for (GET get : ((GETs) annotation).value())
						matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.GET, ResourcePath
								.fromPathDeclaration(get.value())));
				} else if (annotation instanceof POSTs) {
					for (POST post : ((POSTs) annotation).value())
						matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.POST, ResourcePath
								.fromPathDeclaration(post.value())));
				} else if (annotation instanceof PUTs) {
					for (PUT put : ((PUTs) annotation).value())
						matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.PUT, ResourcePath
								.fromPathDeclaration(put.value())));
				} else if (annotation instanceof PATCHes) {
					for (PATCH patch : ((PATCHes) annotation).value())
						matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.PATCH, ResourcePath
								.fromPathDeclaration(patch.value())));
				} else if (annotation instanceof DELETEs) {
					for (DELETE delete : ((DELETEs) annotation).value())
						matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.DELETE, ResourcePath
								.fromPathDeclaration(delete.value())));
				} else if (annotation instanceof OPTIONSes) {
					for (OPTIONS options : ((OPTIONSes) annotation).value())
						matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.OPTIONS, ResourcePath
								.fromPathDeclaration(options.value())));
				} else if (annotation instanceof HEADs) {
					for (HEAD head : ((HEADs) annotation).value())
						matchedHttpMethodResourcePaths.add(new HttpMethodResourcePath(HttpMethod.HEAD, ResourcePath
								.fromPathDeclaration(head.value())));
				}

				Set<HttpMethodResourcePath> httpMethodResourcePaths =
						httpMethodResourcePathsByResourceMethod.get(resourceMethod);

				if (httpMethodResourcePaths == null) {
					httpMethodResourcePaths = new HashSet<>();
					httpMethodResourcePathsByResourceMethod.put(resourceMethod, httpMethodResourcePaths);
				}

				httpMethodResourcePaths.addAll(matchedHttpMethodResourcePaths);
			}
		}

		return httpMethodResourcePathsByResourceMethod;
	}

	@Nonnull
	protected Set<ResourceMethod> mostSpecificResourceMethods(@Nonnull Request request,
																														@Nonnull Set<ResourceMethod> resourceMethods) {
		requireNonNull(request);
		requireNonNull(resourceMethods);

		SortedMap<Long, Set<ResourceMethod>> resourceMethodsByPlaceholderComponentCount = new TreeMap<>();

		for (ResourceMethod resourceMethod : resourceMethods) {
			Set<HttpMethodResourcePath> httpMethodResourcePaths = getHttpMethodResourcePathsByMethod().get(resourceMethod);

			if (httpMethodResourcePaths == null || httpMethodResourcePaths.size() == 0)
				continue;

			for (HttpMethodResourcePath httpMethodResourcePath : httpMethodResourcePaths) {
				if (httpMethodResourcePath.getHttpMethod() != request.getHttpMethod())
					continue;

				long literalComponentCount = httpMethodResourcePath.getResourcePath().getComponents().stream().filter(component -> component.getType() == ResourcePath.ComponentType.PLACEHOLDER).count();
				Set<ResourceMethod> resourceMethodsWithEquivalentComponentCount = resourceMethodsByPlaceholderComponentCount.get(literalComponentCount);

				if (resourceMethodsWithEquivalentComponentCount == null) {
					resourceMethodsWithEquivalentComponentCount = new HashSet<>();
					resourceMethodsByPlaceholderComponentCount.put(literalComponentCount, resourceMethodsWithEquivalentComponentCount);
				}

				resourceMethodsWithEquivalentComponentCount.add(resourceMethod);
			}
		}

		return resourceMethodsByPlaceholderComponentCount.size() == 0 ? Collections.emptySet() :
				resourceMethodsByPlaceholderComponentCount.get(resourceMethodsByPlaceholderComponentCount.keySet().stream().findFirst().get());
	}

	@Nonnull
	protected Map<HttpMethod, Set<Method>> createResourceMethodsByHttpMethod(@Nonnull Set<Method> resourceMethods) {
		requireNonNull(resourceMethods);

		Map<HttpMethod, Set<Method>> resourceMethodsByHttpMethod = new HashMap<>();

		for (Method resourceMethod : resourceMethods) {
			for (Annotation annotation : resourceMethod.getAnnotations()) {
				HttpMethod httpMethod = null;

				if (annotation instanceof GET || annotation instanceof GETs)
					httpMethod = HttpMethod.GET;
				else if (annotation instanceof POST || annotation instanceof POSTs)
					httpMethod = HttpMethod.POST;
				else if (annotation instanceof PUT || annotation instanceof PUTs)
					httpMethod = HttpMethod.PUT;
				else if (annotation instanceof PATCH || annotation instanceof PATCHes)
					httpMethod = HttpMethod.PATCH;
				else if (annotation instanceof DELETE || annotation instanceof DELETEs)
					httpMethod = HttpMethod.DELETE;
				else if (annotation instanceof OPTIONS || annotation instanceof OPTIONSes)
					httpMethod = HttpMethod.OPTIONS;
				else if (annotation instanceof HEAD || annotation instanceof HEADs)
					httpMethod = HttpMethod.HEAD;

				if (httpMethod == null)
					continue;

				Set<Method> httpMethodResourceMethods = resourceMethodsByHttpMethod.get(httpMethod);

				if (httpMethodResourceMethods == null) {
					httpMethodResourceMethods = new HashSet<>();
					resourceMethodsByHttpMethod.put(httpMethod, httpMethodResourceMethods);
				}

				httpMethodResourceMethods.add(resourceMethod);
			}
		}

		return resourceMethodsByHttpMethod;
	}

	@Nonnull
	protected Set<Method> extractResourceMethods(@Nonnull Set<Class<?>> resourceClasses) {
		requireNonNull(resourceClasses);

		Set<Method> methods = new HashSet<>();

		for (Class<?> resourceClass : resourceClasses)
			for (Method method : resourceClass.getMethods())
				for (Annotation annotation : method.getAnnotations())
					if (annotation instanceof GET
							|| annotation instanceof POST
							|| annotation instanceof PUT
							|| annotation instanceof PATCH
							|| annotation instanceof DELETE
							|| annotation instanceof OPTIONS
							|| annotation instanceof HEAD
							|| annotation instanceof GETs
							|| annotation instanceof POSTs
							|| annotation instanceof PUTs
							|| annotation instanceof PATCHes
							|| annotation instanceof DELETEs
							|| annotation instanceof OPTIONSes
							|| annotation instanceof HEADs)
						methods.add(method);

		return methods;
	}

	@Nonnull
	protected Set<Method> getResourceMethods() {
		return resourceMethods;
	}

	@Nonnull
	protected Map<HttpMethod, Set<Method>> getResourceMethodsByHttpMethod() {
		return resourceMethodsByHttpMethod;
	}

	@Nonnull
	protected Map<Method, Set<HttpMethodResourcePath>> getHttpMethodResourcePathsByMethod() {
		return httpMethodResourcePathsByMethod;
	}

	@ThreadSafe
	protected static class HttpMethodResourcePath {
		@Nonnull
		private final HttpMethod httpMethod;
		@Nonnull
		private final ResourcePath resourcePath;

		public HttpMethodResourcePath(@Nonnull HttpMethod httpMethod,
																	@Nonnull ResourcePath resourcePath) {
			requireNonNull(httpMethod);
			requireNonNull(resourcePath);

			this.httpMethod = httpMethod;
			this.resourcePath = resourcePath;
		}

		@Override
		public String toString() {
			return format("%s{httpMethod=%s, resourcePath=%s}", getClass().getSimpleName(),
					getHttpMethod(), getResourcePath());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof HttpMethodResourcePath httpMethodResourcePath))
				return false;

			return Objects.equals(getHttpMethod(), httpMethodResourcePath.getHttpMethod())
					&& Objects.equals(getResourcePath(), httpMethodResourcePath.getResourcePath());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getHttpMethod(), getResourcePath());
		}

		@Nonnull
		public HttpMethod getHttpMethod() {
			return this.httpMethod;
		}

		@Nonnull
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}
	}
}
