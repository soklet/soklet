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
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.annotation.ServerSentEventSources;
import com.soklet.core.HttpMethod;
import com.soklet.core.Request;
import com.soklet.core.ResourceMethod;
import com.soklet.core.ResourceMethodResolver;
import com.soklet.core.ResourcePath;
import com.soklet.core.ResourcePathDeclaration;
import com.soklet.internal.classindex.ClassIndex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultResourceMethodResolver implements ResourceMethodResolver {
	@Nonnull
	private static final DefaultResourceMethodResolver SHARED_INSTANCE;

	static {
		SHARED_INSTANCE = new DefaultResourceMethodResolver();
	}

	@Nonnull
	private final Set<Method> methods;
	@Nonnull
	private final Map<HttpMethod, Set<Method>> methodsByHttpMethod;
	@Nonnull
	private final Map<Method, Set<HttpMethodResourcePathDeclaration>> httpMethodResourcePathDeclarationsByMethod;
	@Nonnull
	private final Set<ResourceMethod> resourceMethods;

	@Nonnull
	public static DefaultResourceMethodResolver sharedInstance() {
		return SHARED_INSTANCE;
	}

	public DefaultResourceMethodResolver() {
		this(ClassIndex.getAnnotated(Resource.class).parallelStream().collect(Collectors.toSet()), null);
	}

	public DefaultResourceMethodResolver(@Nullable Set<Class<?>> resourceClasses) {
		this(resourceClasses, null);
	}

	public DefaultResourceMethodResolver(@Nullable Set<Class<?>> resourceClasses,
																			 @Nullable Set<Method> methods) {
		Set<Method> allMethods = new HashSet<>();

		if (resourceClasses != null)
			allMethods.addAll(extractMethods(resourceClasses));

		if (methods != null)
			allMethods.addAll(methods);

		this.methods = Collections.unmodifiableSet(allMethods);
		this.methodsByHttpMethod = Collections.unmodifiableMap(createMethodsByHttpMethod(getMethods()));
		this.httpMethodResourcePathDeclarationsByMethod = Collections.unmodifiableMap(createHttpMethodResourcePathDeclarationsByMethod(getMethods()));

		// Collect up all resource methods into a single set for easy access
		Set<ResourceMethod> resourceMethods = new HashSet<>();

		for (Entry<HttpMethod, Set<Method>> entry : this.methodsByHttpMethod.entrySet()) {
			HttpMethod httpMethod = entry.getKey();
			Set<Method> currentMethods = entry.getValue();

			if (currentMethods == null)
				continue;

			for (Method method : currentMethods) {
				Set<HttpMethodResourcePathDeclaration> httpMethodResourcePathDeclarations = this.httpMethodResourcePathDeclarationsByMethod.get(method);

				if (httpMethodResourcePathDeclarations == null)
					continue;

				for (HttpMethodResourcePathDeclaration httpMethodResourcePathDeclaration : httpMethodResourcePathDeclarations) {
					ResourcePathDeclaration resourcePathDeclaration = httpMethodResourcePathDeclaration.getResourcePathDeclaration();
					Boolean serverSentEventSource = httpMethodResourcePathDeclaration.isServerSentEventSource();
					ResourceMethod resourceMethod = ResourceMethod.withComponents(httpMethod, resourcePathDeclaration, method, serverSentEventSource);
					resourceMethods.add(resourceMethod);
				}
			}
		}

		this.resourceMethods = Collections.unmodifiableSet(resourceMethods);
	}

	@Nonnull
	@Override
	public Optional<ResourceMethod> resourceMethodForRequest(@Nonnull Request request) {
		requireNonNull(request);

		Set<Method> methods = getMethodsByHttpMethod().get(request.getHttpMethod());

		if (methods == null)
			return Optional.empty();

		ResourcePath resourcePath = request.getResourcePath();
		Set<ResourceMethod> matchingResourceMethods = new HashSet<>(4); // Normally there are few (if any) potential matches

		// TODO: faster matching via path component tree structure instead of linear scan
		for (Entry<Method, Set<HttpMethodResourcePathDeclaration>> entry : getHttpMethodResourcePathDeclarationsByMethod().entrySet()) {
			Method method = entry.getKey();
			Set<HttpMethodResourcePathDeclaration> httpMethodResourcePathDeclarations = entry.getValue();

			for (HttpMethodResourcePathDeclaration httpMethodResourcePathDeclaration : httpMethodResourcePathDeclarations)
				if (httpMethodResourcePathDeclaration.getHttpMethod().equals(request.getHttpMethod())
						&& resourcePath.matches(httpMethodResourcePathDeclaration.getResourcePathDeclaration()))
					matchingResourceMethods.add(ResourceMethod.withComponents(request.getHttpMethod(), httpMethodResourcePathDeclaration.getResourcePathDeclaration(), method, httpMethodResourcePathDeclaration.isServerSentEventSource()));
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

			throw new RuntimeException(format("Multiple routes match '%s %s'. Ambiguous matches were:\n%s", request.getHttpMethod().name(), request.getResourcePath().getPath(),
					matchingResourceMethods.stream()
							.map(matchingResourceMethod -> matchingResourceMethod.getMethod().toString())
							.collect(Collectors.joining("\n"))));
		}

		return Optional.empty();
	}

	@Nonnull
	protected Map<Method, Set<HttpMethodResourcePathDeclaration>> createHttpMethodResourcePathDeclarationsByMethod(@Nonnull Set<Method> methods) {
		requireNonNull(methods);

		Map<Method, Set<HttpMethodResourcePathDeclaration>> httpMethodResourcePathDeclarationsByMethod = new HashMap<>();

		for (Method method : methods) {
			Set<HttpMethodResourcePathDeclaration> matchedHttpMethodResourcePathDeclarations = new HashSet<>();

			for (Annotation annotation : method.getAnnotations()) {
				if (annotation instanceof GET) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.of
							(((GET) annotation).value())));
				} else if (annotation instanceof POST) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.POST, ResourcePathDeclaration.of
							(((POST) annotation).value())));
				} else if (annotation instanceof PUT) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PUT, ResourcePathDeclaration.of
							(((PUT) annotation).value())));
				} else if (annotation instanceof PATCH) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PATCH, ResourcePathDeclaration.of
							(((PATCH) annotation).value())));
				} else if (annotation instanceof DELETE) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.DELETE, ResourcePathDeclaration.of
							(((DELETE) annotation).value())));
				} else if (annotation instanceof OPTIONS) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.OPTIONS, ResourcePathDeclaration.of
							(((OPTIONS) annotation).value())));
				} else if (annotation instanceof HEAD) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.HEAD, ResourcePathDeclaration.of
							(((HEAD) annotation).value())));
				} else if (annotation instanceof ServerSentEventSource) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.of
							(((ServerSentEventSource) annotation).value()), true));
				} else if (annotation instanceof GETs) {
					for (GET get : ((GETs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.of
								(get.value())));
				} else if (annotation instanceof POSTs) {
					for (POST post : ((POSTs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.POST, ResourcePathDeclaration.of
								(post.value())));
				} else if (annotation instanceof PUTs) {
					for (PUT put : ((PUTs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PUT, ResourcePathDeclaration.of
								(put.value())));
				} else if (annotation instanceof PATCHes) {
					for (PATCH patch : ((PATCHes) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PATCH, ResourcePathDeclaration.of
								(patch.value())));
				} else if (annotation instanceof DELETEs) {
					for (DELETE delete : ((DELETEs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.DELETE, ResourcePathDeclaration.of
								(delete.value())));
				} else if (annotation instanceof OPTIONSes) {
					for (OPTIONS options : ((OPTIONSes) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.OPTIONS, ResourcePathDeclaration.of
								(options.value())));
				} else if (annotation instanceof HEADs) {
					for (HEAD head : ((HEADs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.HEAD, ResourcePathDeclaration.of
								(head.value())));
				} else if (annotation instanceof ServerSentEventSources) {
					for (ServerSentEventSource serverSentEventSource : ((ServerSentEventSources) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.of
								(serverSentEventSource.value()), true));
				}

				Set<HttpMethodResourcePathDeclaration> httpMethodResourcePathDeclarations =
						httpMethodResourcePathDeclarationsByMethod.computeIfAbsent(method, k -> new HashSet<>());
				httpMethodResourcePathDeclarations.addAll(matchedHttpMethodResourcePathDeclarations);
			}
		}

		return httpMethodResourcePathDeclarationsByMethod;
	}

	@Nonnull
	protected Set<ResourceMethod> mostSpecificResourceMethods(@Nonnull Request request,
																														@Nonnull Set<ResourceMethod> resourceMethods) {
		requireNonNull(request);
		requireNonNull(resourceMethods);

		SortedMap<Long, Set<ResourceMethod>> resourceMethodsByPlaceholderComponentCount = new TreeMap<>();

		for (ResourceMethod resourceMethod : resourceMethods) {
			Set<HttpMethodResourcePathDeclaration> httpMethodResourcePathDeclarations = getHttpMethodResourcePathDeclarationsByMethod().get(resourceMethod.getMethod());

			if (httpMethodResourcePathDeclarations == null || httpMethodResourcePathDeclarations.size() == 0)
				continue;

			for (HttpMethodResourcePathDeclaration httpMethodResourcePathDeclaration : httpMethodResourcePathDeclarations) {
				if (httpMethodResourcePathDeclaration.getHttpMethod() != request.getHttpMethod())
					continue;

				long literalComponentCount = httpMethodResourcePathDeclaration.getResourcePathDeclaration().getComponents().stream().filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.PLACEHOLDER).count();
				Set<ResourceMethod> resourceMethodsWithEquivalentComponentCount = resourceMethodsByPlaceholderComponentCount.computeIfAbsent(literalComponentCount, k -> new HashSet<>());

				resourceMethodsWithEquivalentComponentCount.add(resourceMethod);
			}
		}

		return resourceMethodsByPlaceholderComponentCount.size() == 0 ? Collections.emptySet() :
				resourceMethodsByPlaceholderComponentCount.get(resourceMethodsByPlaceholderComponentCount.keySet().stream().findFirst().get());
	}

	@Nonnull
	protected Map<HttpMethod, Set<Method>> createMethodsByHttpMethod(@Nonnull Set<Method> methods) {
		requireNonNull(methods);

		Map<HttpMethod, Set<Method>> methodsByMethod = new HashMap<>();

		for (Method method : methods) {
			for (Annotation annotation : method.getAnnotations()) {
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
				else if (annotation instanceof ServerSentEventSource || annotation instanceof ServerSentEventSources)
					httpMethod = HttpMethod.GET;

				if (httpMethod == null)
					continue;

				Set<Method> httpMethodMethods = methodsByMethod.computeIfAbsent(httpMethod, k -> new HashSet<>());
				httpMethodMethods.add(method);
			}
		}

		return methodsByMethod;
	}

	@Nonnull
	protected Set<Method> extractMethods(@Nonnull Set<Class<?>> resourceClasses) {
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
							|| annotation instanceof ServerSentEventSource
							|| annotation instanceof GETs
							|| annotation instanceof POSTs
							|| annotation instanceof PUTs
							|| annotation instanceof PATCHes
							|| annotation instanceof DELETEs
							|| annotation instanceof OPTIONSes
							|| annotation instanceof HEADs
							|| annotation instanceof ServerSentEventSources)
						methods.add(method);

		return methods;
	}

	@Nonnull
	@Override
	public Set<ResourceMethod> getResourceMethods() {
		return this.resourceMethods;
	}

	@Nonnull
	public Set<Method> getMethods() {
		return this.methods;
	}

	@Nonnull
	public Map<Method, Set<HttpMethodResourcePathDeclaration>> getHttpMethodResourcePathDeclarationsByMethod() {
		return this.httpMethodResourcePathDeclarationsByMethod;
	}

	@Nonnull
	protected Map<HttpMethod, Set<Method>> getMethodsByHttpMethod() {
		return this.methodsByHttpMethod;
	}

	@ThreadSafe
	protected static class HttpMethodResourcePathDeclaration {
		@Nonnull
		private final HttpMethod httpMethod;
		@Nonnull
		private final ResourcePathDeclaration resourcePathDeclaration;
		@Nonnull
		private final Boolean serverSentEventSource;

		public HttpMethodResourcePathDeclaration(@Nonnull HttpMethod httpMethod,
																						 @Nonnull ResourcePathDeclaration resourcePathDeclaration) {
			this(httpMethod, resourcePathDeclaration, false);
		}

		public HttpMethodResourcePathDeclaration(@Nonnull HttpMethod httpMethod,
																						 @Nonnull ResourcePathDeclaration resourcePathDeclaration,
																						 @Nonnull Boolean serverSentEventSource) {
			requireNonNull(httpMethod);
			requireNonNull(resourcePathDeclaration);
			requireNonNull(serverSentEventSource);

			this.httpMethod = httpMethod;
			this.resourcePathDeclaration = resourcePathDeclaration;
			this.serverSentEventSource = serverSentEventSource;
		}

		@Override
		public String toString() {
			return format("%s{httpMethod=%s, resourcePathDeclaration=%s, serverSentEventSource=%s}", getClass().getSimpleName(),
					getHttpMethod(), getResourcePathDeclaration(), isServerSentEventSource());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof HttpMethodResourcePathDeclaration httpMethodResourcePathDeclaration))
				return false;

			return Objects.equals(getHttpMethod(), httpMethodResourcePathDeclaration.getHttpMethod())
					&& Objects.equals(getResourcePathDeclaration(), httpMethodResourcePathDeclaration.getResourcePathDeclaration())
					&& Objects.equals(isServerSentEventSource(), httpMethodResourcePathDeclaration.isServerSentEventSource());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getHttpMethod(), getResourcePathDeclaration(), isServerSentEventSource());
		}

		@Nonnull
		public HttpMethod getHttpMethod() {
			return this.httpMethod;
		}

		@Nonnull
		public ResourcePathDeclaration getResourcePathDeclaration() {
			return this.resourcePathDeclaration;
		}

		@Nonnull
		public Boolean isServerSentEventSource() {
			return this.serverSentEventSource;
		}
	}
}
