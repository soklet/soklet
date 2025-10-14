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
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.annotation.ServerSentEventSources;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
final class DefaultResourceMethodResolver implements ResourceMethodResolver {
	@Nonnull
	private static final DefaultResourceMethodResolver DEFAULT_INSTANCE;

	static {
		DEFAULT_INSTANCE = new DefaultResourceMethodResolver();
	}

	@Nonnull
	public static DefaultResourceMethodResolver fromClasspathIntrospection() {
		return DEFAULT_INSTANCE;
	}

	@Nonnull
	public static DefaultResourceMethodResolver withResourceClasses(@Nullable Set<Class<?>> resourceClasses) {
		requireNonNull(resourceClasses);
		return new DefaultResourceMethodResolver(resourceClasses, null);
	}

	@Nonnull
	public static DefaultResourceMethodResolver withMethods(@Nonnull Set<Method> methods) {
		requireNonNull(methods);
		return new DefaultResourceMethodResolver(null, methods);
	}

	@Nonnull
	private final Set<Method> methods;
	@Nonnull
	private final Map<HttpMethod, Set<Method>> methodsByHttpMethod;
	@Nonnull
	private final Map<Method, Set<HttpMethodResourcePathDeclaration>> httpMethodResourcePathDeclarationsByMethod;
	@Nonnull
	private final Set<ResourceMethod> resourceMethods;

	private DefaultResourceMethodResolver() {
		// Read declarations from SokletProcessor's compile-time lookup table
		List<ResourceMethodDeclaration> resourceMethodDeclarations = ResourceMethodDeclarationLoader.loadAll(Thread.currentThread().getContextClassLoader());

		// Resolve Methods once
		Set<Method> allMethods = new HashSet<>();
		Map<Method, Set<HttpMethodResourcePathDeclaration>> byMethod = new HashMap<>();
		Map<HttpMethod, Set<Method>> byHttp = new HashMap<>();
		Set<ResourceMethod> resourceMethods = new HashSet<>();

		for (ResourceMethodDeclaration resourceMethodDeclaration : resourceMethodDeclarations) {
			Method method = resolveMethod(resourceMethodDeclaration.className(), resourceMethodDeclaration.methodName(), resourceMethodDeclaration.parameterTypes());
			allMethods.add(method);

			// Declarations for this method
			var decls = byMethod.computeIfAbsent(method, __ -> new HashSet<>());
			var rpd = ResourcePathDeclaration.withPath(resourceMethodDeclaration.path());
			boolean sse = resourceMethodDeclaration.serverSentEventSource();
			decls.add(new HttpMethodResourcePathDeclaration(resourceMethodDeclaration.httpMethod(), rpd, sse));

			// Index by http method
			byHttp.computeIfAbsent(resourceMethodDeclaration.httpMethod(), __ -> new HashSet<>()).add(method);
			resourceMethods.add(ResourceMethod.withComponents(resourceMethodDeclaration.httpMethod(), rpd, method, sse));
		}

		this.methods = Collections.unmodifiableSet(allMethods);
		this.methodsByHttpMethod = Collections.unmodifiableMap(byHttp);
		this.httpMethodResourcePathDeclarationsByMethod = Collections.unmodifiableMap(byMethod);
		this.resourceMethods = Collections.unmodifiableSet(resourceMethods);
	}

	@ThreadSafe
	static final class ResourceMethodDeclarationLoader {
		private ResourceMethodDeclarationLoader() {}

		@SuppressWarnings("unchecked")
		static List<ResourceMethodDeclaration> loadAll(ClassLoader cl) {
			List<ResourceMethodDeclaration> out = new ArrayList<>();
			Enumeration<URL> resources = null;

			try {
				// Read in Resource Method cache generated by SokletProcessor
				resources = cl.getResources(SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH);
			} catch (Exception e) {
				throw new RuntimeException(format("Unable to access Soklet's Resource Method lookup table. Is '%s' on the classpath and well-formed? Did you supply javac with the following options? '-parameters -processor %s'?", SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH, SokletProcessor.class.getName()), e);
			}

			int resourcesCount = 0;

			try {
				while (resources.hasMoreElements()) {
					++resourcesCount;
					URL url = resources.nextElement();
					try (var in = url.openStream();
							 var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
						for (String line; (line = br.readLine()) != null; ) {
							line = line.trim();
							if (line.isBlank() || line.startsWith("#")) continue;
							ResourceMethodDeclaration r = parseLine(line);
							if (r != null) out.add(r);
						}
					}
				}
			} catch (Exception e) {
				throw new RuntimeException(format("Unable to process Soklet's Resource Method lookup table. Is '%s' on the classpath and well-formed? Did you supply javac with the following options? '-parameters -processor %s'?", SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH, SokletProcessor.class.getName()), e);
			}

			// In the future, might enable a "strict" mode that won't start up without resource methods
			// if (resourcesCount == 0)
			//	throw new RuntimeException(format("Unable to access Soklet's Resource Method lookup table. Did you supply javac with the following options? '-parameters -processor %s'?", SokletProcessor.class.getName()));

			return dedupeAndOrder(out);
		}

		// Line format written by SokletProcessor:
		// METHOD|b64(path)|b64(class)|b64(method)|b64(param1;param2;...)|true|false
		private static ResourceMethodDeclaration parseLine(String line) {
			String[] parts = line.split("\\|", -1);
			if (parts.length != 6) return null;

			HttpMethod http = HttpMethod.valueOf(parts[0]);

			String path = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
			String className = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
			String methodName = new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8);
			String params = new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8);
			String[] paramTypes = params.isBlank() ? new String[0] : params.split(";");

			boolean sse = Boolean.parseBoolean(parts[5]);

			return new ResourceMethodDeclaration(http, path, className, methodName, paramTypes, sse);
		}

		private static List<ResourceMethodDeclaration> dedupeAndOrder(List<ResourceMethodDeclaration> in) {
			Map<String, ResourceMethodDeclaration> byKey = new LinkedHashMap<>();

			for (ResourceMethodDeclaration r : in) {
				String key = r.httpMethod().name() + "|" + r.path() + "|" + r.className() + "|" +
						r.methodName() + "|" + String.join(";", r.parameterTypes()) + "|" +
						r.serverSentEventSource();
				byKey.putIfAbsent(key, r);
			}

			List<ResourceMethodDeclaration> out = new ArrayList<>(byKey.values());
			out.sort(Comparator
					.comparing((ResourceMethodDeclaration r) -> r.httpMethod().name())
					.thenComparing(ResourceMethodDeclaration::path)
					.thenComparing(ResourceMethodDeclaration::className)
					.thenComparing(ResourceMethodDeclaration::methodName));
			return out;
		}
	}

	@Nonnull
	private Method resolveMethod(@Nonnull String className,
															 @Nonnull String methodName,
															 @Nonnull String[] paramTypeNames) {
		requireNonNull(className);
		requireNonNull(methodName);
		requireNonNull(paramTypeNames);

		try {
			Class<?> owner = Class.forName(className);
			Class<?>[] paramTypes = new Class<?>[paramTypeNames.length];
			for (int i = 0; i < paramTypeNames.length; i++)
				paramTypes[i] = Class.forName(paramTypeNames[i]);
			Method m = owner.getMethod(methodName, paramTypes);
			m.setAccessible(true);
			return m;
		} catch (Exception e) {
			throw new IllegalStateException(format("Unable to resolve Soklet Resource Method %s#%s(%s).", className, methodName, String.join(",", paramTypeNames)), e);
		}
	}

	private DefaultResourceMethodResolver(@Nullable Set<Class<?>> resourceClasses,
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

					// Enforce that @ServerSentEventSource methods have a return type of HandshakeResult
					if (serverSentEventSource && !HandshakeResult.class.isAssignableFrom(method.getReturnType()))
						throw new IllegalStateException(format("Resource Methods annotated with @%s must be declared to return an instance of %s (e.g. %s.accept()). Incorrect Resource Method was %s",
								ServerSentEventSource.class.getSimpleName(), HandshakeResult.class.getSimpleName(), HandshakeResult.class.getSimpleName(), method));

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
		Set<ResourceMethod> matchingResourceMethods = new HashSet<>(4);

		// Collect all resource methods matching the HTTP method and whose resource path declaration matches the request.
		for (Entry<Method, Set<HttpMethodResourcePathDeclaration>> entry : getHttpMethodResourcePathDeclarationsByMethod().entrySet()) {
			Method method = entry.getKey();
			Set<HttpMethodResourcePathDeclaration> httpMethodResourcePathDeclarations = entry.getValue();
			for (HttpMethodResourcePathDeclaration httpMethodResourcePathDeclaration : httpMethodResourcePathDeclarations)
				if (httpMethodResourcePathDeclaration.getHttpMethod().equals(request.getHttpMethod())
						&& resourcePath.matches(httpMethodResourcePathDeclaration.getResourcePathDeclaration()))
					matchingResourceMethods.add(ResourceMethod.withComponents(
							request.getHttpMethod(),
							httpMethodResourcePathDeclaration.getResourcePathDeclaration(),
							method,
							httpMethodResourcePathDeclaration.isServerSentEventSource()));
		}

		// Varargs precedence: if any matching resource method is defined with a varargs placeholder, only consider those.
		Set<ResourceMethod> varargsMatches = matchingResourceMethods.stream()
				.filter(resourceMethod -> resourceMethod.getResourcePathDeclaration().getVarargsComponent().isPresent())
				.collect(Collectors.toSet());

		if (!varargsMatches.isEmpty())
			matchingResourceMethods = varargsMatches;

		// Simple case - if exactly one resource method remains, use it.
		if (matchingResourceMethods.size() == 1)
			return matchingResourceMethods.stream().findFirst();

		// Multiple matches: narrow by specificity.
		if (matchingResourceMethods.size() > 1) {
			Set<ResourceMethod> mostSpecific = mostSpecificResourceMethods(request, matchingResourceMethods);

			if (mostSpecific.size() == 1)
				return mostSpecific.stream().findFirst();

			throw new RuntimeException(format("Multiple routes match '%s %s'. Ambiguous matches were:\n%s",
					request.getHttpMethod().name(),
					request.getResourcePath().getPath(),
					matchingResourceMethods.stream()
							.map(m -> m.getMethod().toString())
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
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.withPath
							(((GET) annotation).value())));
				} else if (annotation instanceof POST) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.POST, ResourcePathDeclaration.withPath
							(((POST) annotation).value())));
				} else if (annotation instanceof PUT) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PUT, ResourcePathDeclaration.withPath
							(((PUT) annotation).value())));
				} else if (annotation instanceof PATCH) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PATCH, ResourcePathDeclaration.withPath
							(((PATCH) annotation).value())));
				} else if (annotation instanceof DELETE) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.DELETE, ResourcePathDeclaration.withPath
							(((DELETE) annotation).value())));
				} else if (annotation instanceof OPTIONS) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.OPTIONS, ResourcePathDeclaration.withPath
							(((OPTIONS) annotation).value())));
				} else if (annotation instanceof HEAD) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.HEAD, ResourcePathDeclaration.withPath
							(((HEAD) annotation).value())));
				} else if (annotation instanceof ServerSentEventSource) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.withPath
							(((ServerSentEventSource) annotation).value()), true));
				} else if (annotation instanceof GETs) {
					for (GET get : ((GETs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.withPath
								(get.value())));
				} else if (annotation instanceof POSTs) {
					for (POST post : ((POSTs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.POST, ResourcePathDeclaration.withPath
								(post.value())));
				} else if (annotation instanceof PUTs) {
					for (PUT put : ((PUTs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PUT, ResourcePathDeclaration.withPath
								(put.value())));
				} else if (annotation instanceof PATCHes) {
					for (PATCH patch : ((PATCHes) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PATCH, ResourcePathDeclaration.withPath
								(patch.value())));
				} else if (annotation instanceof DELETEs) {
					for (DELETE delete : ((DELETEs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.DELETE, ResourcePathDeclaration.withPath
								(delete.value())));
				} else if (annotation instanceof OPTIONSes) {
					for (OPTIONS options : ((OPTIONSes) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.OPTIONS, ResourcePathDeclaration.withPath
								(options.value())));
				} else if (annotation instanceof HEADs) {
					for (HEAD head : ((HEADs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.HEAD, ResourcePathDeclaration.withPath
								(head.value())));
				} else if (annotation instanceof ServerSentEventSources) {
					for (ServerSentEventSource serverSentEventSource : ((ServerSentEventSources) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.withPath
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

		// If any of the matching resource methods use a varargs placeholder, restrict to just those.
		Set<ResourceMethod> varargsResourceMethods = resourceMethods.stream()
				.filter(resourceMethod -> resourceMethod.getResourcePathDeclaration().getVarargsComponent().isPresent())
				.collect(Collectors.toSet());

		if (!varargsResourceMethods.isEmpty())
			resourceMethods = varargsResourceMethods;

		// Group by the count of placeholder components (non-literal) in the declaration.
		// Fewer placeholders means the route is more specific.
		SortedMap<Long, Set<ResourceMethod>> resourceMethodsByPlaceholderCount = new TreeMap<>();

		for (ResourceMethod resourceMethod : resourceMethods) {
			Set<HttpMethodResourcePathDeclaration> declarations = getHttpMethodResourcePathDeclarationsByMethod().get(resourceMethod.getMethod());

			if (declarations == null || declarations.isEmpty())
				continue;

			for (HttpMethodResourcePathDeclaration declaration : declarations) {
				if (declaration.getHttpMethod() != request.getHttpMethod())
					continue;

				long placeholderCount = declaration.getResourcePathDeclaration().getComponents().stream()
						.filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.PLACEHOLDER)
						.count();

				resourceMethodsByPlaceholderCount
						.computeIfAbsent(placeholderCount, k -> new HashSet<>())
						.add(resourceMethod);
			}
		}

		return resourceMethodsByPlaceholderCount.isEmpty()
				? Collections.emptySet()
				: resourceMethodsByPlaceholderCount.get(resourceMethodsByPlaceholderCount.firstKey());
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
