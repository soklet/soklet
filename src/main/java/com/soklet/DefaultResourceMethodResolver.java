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
import com.soklet.annotation.SseEventSource;
import com.soklet.annotation.SseEventSources;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultResourceMethodResolver implements ResourceMethodResolver {
	@NonNull
	private static final Map<@NonNull String, @NonNull Class<?>> PRIMITIVE_TYPES_BY_NAME;
	@NonNull
	private static final DefaultResourceMethodResolver DEFAULT_INSTANCE;

	static {
		PRIMITIVE_TYPES_BY_NAME = Map.of(
				"boolean", boolean.class,
				"byte", byte.class,
				"short", short.class,
				"char", char.class,
				"int", int.class,
				"long", long.class,
				"float", float.class,
				"double", double.class,
				"void", void.class
		);

		DEFAULT_INSTANCE = new DefaultResourceMethodResolver();
	}

	@NonNull
	public static DefaultResourceMethodResolver fromClasspathIntrospection() {
		return DEFAULT_INSTANCE;
	}

	@NonNull
	public static DefaultResourceMethodResolver fromClasses(@Nullable Set<@NonNull Class<?>> resourceClasses) {
		requireNonNull(resourceClasses);
		return new DefaultResourceMethodResolver(resourceClasses, null);
	}

	@NonNull
	public static DefaultResourceMethodResolver fromMethods(@NonNull Set<@NonNull Method> methods) {
		requireNonNull(methods);
		return new DefaultResourceMethodResolver(null, methods);
	}

	@NonNull
	static DefaultResourceMethodResolver fromResourceMethods(@NonNull Set<@NonNull ResourceMethod> resourceMethods) {
		requireNonNull(resourceMethods);
		return new DefaultResourceMethodResolver(resourceMethods);
	}

	@NonNull
	private final Set<@NonNull Method> methods;
	@NonNull
	private final Map<@NonNull HttpMethod, @NonNull Set<@NonNull Method>> methodsByHttpMethod;
	@NonNull
	private final Map<@NonNull Method, @NonNull Set<@NonNull HttpMethodResourcePathDeclaration>> httpMethodResourcePathDeclarationsByMethod;
	@NonNull
	private final Set<@NonNull ResourceMethod> resourceMethods;
	@NonNull
	private final RouteIndex routeIndex;

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
			var rpd = ResourcePathDeclaration.fromPath(resourceMethodDeclaration.path());
			boolean sse = resourceMethodDeclaration.sseEventSource();
			decls.add(new HttpMethodResourcePathDeclaration(resourceMethodDeclaration.httpMethod(), rpd, sse));

			// Index by http method
			byHttp.computeIfAbsent(resourceMethodDeclaration.httpMethod(), __ -> new HashSet<>()).add(method);
			resourceMethods.add(ResourceMethod.fromComponents(resourceMethodDeclaration.httpMethod(), rpd, method, sse));
		}

		this.methods = Collections.unmodifiableSet(allMethods);
		this.methodsByHttpMethod = Collections.unmodifiableMap(byHttp);
		this.httpMethodResourcePathDeclarationsByMethod = Collections.unmodifiableMap(byMethod);
		this.resourceMethods = Collections.unmodifiableSet(resourceMethods);
		validateNoAmbiguousResourceMethods(this.resourceMethods);
		this.routeIndex = RouteIndex.fromResourceMethods(this.resourceMethods);
	}

	private DefaultResourceMethodResolver(@NonNull Set<@NonNull ResourceMethod> resourceMethods) {
		requireNonNull(resourceMethods);

		Set<Method> methods = new HashSet<>();
		Map<HttpMethod, Set<Method>> methodsByHttpMethod = new HashMap<>();
		Map<Method, Set<HttpMethodResourcePathDeclaration>> httpMethodResourcePathDeclarationsByMethod = new HashMap<>();
		Set<ResourceMethod> resourceMethodsCopy = new HashSet<>();

		for (ResourceMethod resourceMethod : resourceMethods) {
			requireNonNull(resourceMethod);

			HttpMethod httpMethod = resourceMethod.getHttpMethod();
			Method method = resourceMethod.getMethod();
			ResourcePathDeclaration resourcePathDeclaration = resourceMethod.getResourcePathDeclaration();
			Boolean sseEventSource = resourceMethod.isSseEventSource();

			if (sseEventSource && !SseHandshakeResult.class.isAssignableFrom(method.getReturnType()))
				throw new IllegalStateException(format("Resource Methods annotated with @%s must be declared to return an instance of %s (e.g. %s.accept()). Incorrect Resource Method was %s",
						SseEventSource.class.getSimpleName(), SseHandshakeResult.class.getSimpleName(), SseHandshakeResult.class.getSimpleName(), method));

			methods.add(method);
			methodsByHttpMethod.computeIfAbsent(httpMethod, ignored -> new HashSet<>()).add(method);
			httpMethodResourcePathDeclarationsByMethod.computeIfAbsent(method, ignored -> new HashSet<>())
					.add(new HttpMethodResourcePathDeclaration(httpMethod, resourcePathDeclaration, sseEventSource));
			resourceMethodsCopy.add(resourceMethod);
		}

		this.methods = Collections.unmodifiableSet(methods);
		this.methodsByHttpMethod = Collections.unmodifiableMap(methodsByHttpMethod);
		this.httpMethodResourcePathDeclarationsByMethod = Collections.unmodifiableMap(httpMethodResourcePathDeclarationsByMethod);
		this.resourceMethods = Collections.unmodifiableSet(resourceMethodsCopy);
		validateNoAmbiguousResourceMethods(this.resourceMethods);
		this.routeIndex = RouteIndex.fromResourceMethods(this.resourceMethods);
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
		@NonNull
		private static ResourceMethodDeclaration parseLine(@NonNull String line) {
			requireNonNull(line);

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

		@NonNull
		private static List<ResourceMethodDeclaration> dedupeAndOrder(@NonNull List<ResourceMethodDeclaration> in) {
			requireNonNull(in);

			Map<String, ResourceMethodDeclaration> byKey = new LinkedHashMap<>();

			for (ResourceMethodDeclaration r : in) {
				String key = r.httpMethod().name() + "|" + r.path() + "|" + r.className() + "|" +
						r.methodName() + "|" + String.join(";", r.parameterTypes()) + "|" +
						r.sseEventSource();
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

	@NonNull
	private Method resolveMethod(@NonNull String className,
															 @NonNull String methodName,
															 @NonNull String[] paramTypeNames) {
		requireNonNull(className);
		requireNonNull(methodName);
		requireNonNull(paramTypeNames);

		try {
			Class<?> owner = Class.forName(className);
			Class<?>[] paramTypes = new Class<?>[paramTypeNames.length];
			for (int i = 0; i < paramTypeNames.length; i++)
				paramTypes[i] = resolveParamType(paramTypeNames[i]);
			Method m = owner.getMethod(methodName, paramTypes);
			m.setAccessible(true);
			return m;
		} catch (Exception e) {
			throw new IllegalStateException(format("Unable to resolve Soklet Resource Method %s#%s(%s).", className, methodName, String.join(",", paramTypeNames)), e);
		}
	}

	@NonNull
	private static Class<?> resolveParamType(@NonNull String paramTypeName) throws ClassNotFoundException {
		requireNonNull(paramTypeName);

		Class<?> primitiveType = PRIMITIVE_TYPES_BY_NAME.get(paramTypeName);

		if (primitiveType != null)
			return primitiveType;

		return Class.forName(paramTypeName);
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
					Boolean sseEventSource = httpMethodResourcePathDeclaration.isSseEventSource();

					// Enforce that @SseEventSource methods have a return type of SseHandshakeResult
					if (sseEventSource && !SseHandshakeResult.class.isAssignableFrom(method.getReturnType()))
						throw new IllegalStateException(format("Resource Methods annotated with @%s must be declared to return an instance of %s (e.g. %s.accept()). Incorrect Resource Method was %s",
								SseEventSource.class.getSimpleName(), SseHandshakeResult.class.getSimpleName(), SseHandshakeResult.class.getSimpleName(), method));

					ResourceMethod resourceMethod = ResourceMethod.fromComponents(httpMethod, resourcePathDeclaration, method, sseEventSource);
					resourceMethods.add(resourceMethod);
				}
			}
		}

		this.resourceMethods = Collections.unmodifiableSet(resourceMethods);
		validateNoAmbiguousResourceMethods(this.resourceMethods);
		this.routeIndex = RouteIndex.fromResourceMethods(this.resourceMethods);
	}

	@NonNull
	@Override
	public Optional<ResourceMethod> resourceMethodForRequest(@NonNull Request request,
																													 @NonNull ServerType serverType) {
		requireNonNull(request);
		requireNonNull(serverType);

		ResourcePath resourcePath = request.getResourcePath();
		Set<ResourceMethod> matchingResourceMethods = this.routeIndex.matchingResourceMethods(request.getHttpMethod(), serverType, resourcePath);

		// Simple case - if exactly one resource method remains, use it.
		if (matchingResourceMethods.size() == 1)
			return Optional.of(matchingResourceMethods.iterator().next());

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

	@NonNull
	protected Map<@NonNull Method, @NonNull Set<@NonNull HttpMethodResourcePathDeclaration>> createHttpMethodResourcePathDeclarationsByMethod(@NonNull Set<@NonNull Method> methods) {
		requireNonNull(methods);

		Map<Method, Set<HttpMethodResourcePathDeclaration>> httpMethodResourcePathDeclarationsByMethod = new HashMap<>();

		for (Method method : methods) {
			Set<HttpMethodResourcePathDeclaration> matchedHttpMethodResourcePathDeclarations = new HashSet<>();

			for (Annotation annotation : method.getAnnotations()) {
				if (annotation instanceof GET) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.fromPath
							(((GET) annotation).value())));
				} else if (annotation instanceof POST) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.POST, ResourcePathDeclaration.fromPath
							(((POST) annotation).value())));
				} else if (annotation instanceof PUT) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PUT, ResourcePathDeclaration.fromPath
							(((PUT) annotation).value())));
				} else if (annotation instanceof PATCH) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PATCH, ResourcePathDeclaration.fromPath
							(((PATCH) annotation).value())));
				} else if (annotation instanceof DELETE) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.DELETE, ResourcePathDeclaration.fromPath
							(((DELETE) annotation).value())));
				} else if (annotation instanceof OPTIONS) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.OPTIONS, ResourcePathDeclaration.fromPath
							(((OPTIONS) annotation).value())));
				} else if (annotation instanceof HEAD) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.HEAD, ResourcePathDeclaration.fromPath
							(((HEAD) annotation).value())));
				} else if (annotation instanceof SseEventSource) {
					matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.fromPath
							(((SseEventSource) annotation).value()), true));
				} else if (annotation instanceof GETs) {
					for (GET get : ((GETs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.fromPath
								(get.value())));
				} else if (annotation instanceof POSTs) {
					for (POST post : ((POSTs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.POST, ResourcePathDeclaration.fromPath
								(post.value())));
				} else if (annotation instanceof PUTs) {
					for (PUT put : ((PUTs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PUT, ResourcePathDeclaration.fromPath
								(put.value())));
				} else if (annotation instanceof PATCHes) {
					for (PATCH patch : ((PATCHes) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.PATCH, ResourcePathDeclaration.fromPath
								(patch.value())));
				} else if (annotation instanceof DELETEs) {
					for (DELETE delete : ((DELETEs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.DELETE, ResourcePathDeclaration.fromPath
								(delete.value())));
				} else if (annotation instanceof OPTIONSes) {
					for (OPTIONS options : ((OPTIONSes) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.OPTIONS, ResourcePathDeclaration.fromPath
								(options.value())));
				} else if (annotation instanceof HEADs) {
					for (HEAD head : ((HEADs) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.HEAD, ResourcePathDeclaration.fromPath
								(head.value())));
				} else if (annotation instanceof SseEventSources) {
					for (SseEventSource sseEventSource : ((SseEventSources) annotation).value())
						matchedHttpMethodResourcePathDeclarations.add(new HttpMethodResourcePathDeclaration(HttpMethod.GET, ResourcePathDeclaration.fromPath
								(sseEventSource.value()), true));
				}

				Set<HttpMethodResourcePathDeclaration> httpMethodResourcePathDeclarations =
						httpMethodResourcePathDeclarationsByMethod.computeIfAbsent(method, k -> new HashSet<>());
				httpMethodResourcePathDeclarations.addAll(matchedHttpMethodResourcePathDeclarations);
			}
		}

		return httpMethodResourcePathDeclarationsByMethod;
	}

	@NonNull
	protected Set<@NonNull ResourceMethod> mostSpecificResourceMethods(@NonNull Request request,
																																		 @NonNull Set<@NonNull ResourceMethod> resourceMethods) {
		requireNonNull(request);
		requireNonNull(resourceMethods);

		// Choose the most specific routes: prefer non-varargs, then fewer placeholders, then more literals.
		Comparator<ResourceMethod> specificityComparator = Comparator
				.comparing((ResourceMethod resourceMethod) -> resourceMethod.getResourcePathDeclaration().getVarargsComponent().isPresent())
				.thenComparingLong(DefaultResourceMethodResolver::placeholderCount)
				.thenComparingLong(resourceMethod -> -literalCount(resourceMethod));

		ResourceMethod mostSpecific = resourceMethods.stream()
				.min(specificityComparator)
				.orElse(null);

		if (mostSpecific == null)
			return Collections.emptySet();

		return resourceMethods.stream()
				.filter(resourceMethod -> specificityComparator.compare(resourceMethod, mostSpecific) == 0)
				.collect(Collectors.toSet());
	}

	private static long placeholderCount(@NonNull ResourceMethod resourceMethod) {
		requireNonNull(resourceMethod);

		return resourceMethod.getResourcePathDeclaration().getComponents().stream()
				.filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.PLACEHOLDER)
				.count();
	}

	private static long literalCount(@NonNull ResourceMethod resourceMethod) {
		requireNonNull(resourceMethod);

		return resourceMethod.getResourcePathDeclaration().getComponents().stream()
				.filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.LITERAL)
				.count();
	}

	private static void validateNoAmbiguousResourceMethods(@NonNull Set<@NonNull ResourceMethod> resourceMethods) {
		requireNonNull(resourceMethods);

		if (resourceMethods.size() < 2)
			return;

		Map<SpecificityKey, List<ResourceMethod>> groups = new HashMap<>();

		for (ResourceMethod resourceMethod : resourceMethods) {
			ResourcePathDeclaration declaration = resourceMethod.getResourcePathDeclaration();
			boolean hasVarargs = declaration.getVarargsComponent().isPresent();

			SpecificityKey key = new SpecificityKey(
					resourceMethod.getHttpMethod(),
					resourceMethod.isSseEventSource(),
					hasVarargs,
					placeholderCount(resourceMethod),
					literalCount(resourceMethod));

			groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(resourceMethod);
		}

		List<String> ambiguousPairs = new ArrayList<>();

		for (List<ResourceMethod> group : groups.values()) {
			if (group.size() < 2)
				continue;

			for (int i = 0; i < group.size(); i++) {
				ResourceMethod first = group.get(i);
				for (int j = i + 1; j < group.size(); j++) {
					ResourceMethod second = group.get(j);
					if (resourcePathDeclarationsOverlap(first.getResourcePathDeclaration(),
							second.getResourcePathDeclaration())) {
						ambiguousPairs.add(format("%s vs %s",
								describeResourceMethod(first),
								describeResourceMethod(second)));
					}
				}
			}
		}

		if (!ambiguousPairs.isEmpty()) {
			throw new IllegalStateException(format(
					"Ambiguous resource method declarations detected. These routes are equally specific and overlap:\n%s",
					String.join("\n", ambiguousPairs)));
		}
	}

	private static String describeResourceMethod(@NonNull ResourceMethod resourceMethod) {
		requireNonNull(resourceMethod);

		String serverType = resourceMethod.isSseEventSource() ? "SSE" : "HTTP";
		return format("%s %s %s -> %s",
				serverType,
				resourceMethod.getHttpMethod().name(),
				resourceMethod.getResourcePathDeclaration().getPath(),
				resourceMethod.getMethod().toString());
	}

	private static boolean resourcePathDeclarationsOverlap(@NonNull ResourcePathDeclaration first,
																												 @NonNull ResourcePathDeclaration second) {
		requireNonNull(first);
		requireNonNull(second);

		List<ResourcePathDeclaration.Component> firstComponents = first.getComponents();
		List<ResourcePathDeclaration.Component> secondComponents = second.getComponents();

		boolean firstHasVarargs = first.getVarargsComponent().isPresent();
		boolean secondHasVarargs = second.getVarargsComponent().isPresent();

		int firstPrefixLength = firstComponents.size() - (firstHasVarargs ? 1 : 0);
		int secondPrefixLength = secondComponents.size() - (secondHasVarargs ? 1 : 0);

		if (!firstHasVarargs && !secondHasVarargs) {
			if (firstComponents.size() != secondComponents.size())
				return false;

			for (int i = 0; i < firstComponents.size(); i++)
				if (!componentsCompatible(firstComponents.get(i), secondComponents.get(i)))
					return false;

			return true;
		}

		if (firstHasVarargs && !secondHasVarargs) {
			if (secondComponents.size() < firstPrefixLength)
				return false;

			for (int i = 0; i < firstPrefixLength; i++)
				if (!componentsCompatible(firstComponents.get(i), secondComponents.get(i)))
					return false;

			return true;
		}

		if (!firstHasVarargs) {
			if (firstComponents.size() < secondPrefixLength)
				return false;

			for (int i = 0; i < secondPrefixLength; i++)
				if (!componentsCompatible(firstComponents.get(i), secondComponents.get(i)))
					return false;

			return true;
		}

		int minPrefixLength = Math.min(firstPrefixLength, secondPrefixLength);

		for (int i = 0; i < minPrefixLength; i++)
			if (!componentsCompatible(firstComponents.get(i), secondComponents.get(i)))
				return false;

		return true;
	}

	private static boolean componentsCompatible(ResourcePathDeclaration.@NonNull Component first,
																							ResourcePathDeclaration.@NonNull Component second) {
		requireNonNull(first);
		requireNonNull(second);

		if (first.getType() == ResourcePathDeclaration.ComponentType.LITERAL
				&& second.getType() == ResourcePathDeclaration.ComponentType.LITERAL)
			return first.getValue().equals(second.getValue());

		return true;
	}

	private static final class RouteIndex {
		@NonNull
		private final Map<@NonNull RouteIndexKey, @NonNull RouteNode> roots;

		private RouteIndex(@NonNull Map<@NonNull RouteIndexKey, @NonNull RouteNode> roots) {
			requireNonNull(roots);
			this.roots = roots;
		}

		@NonNull
		private static RouteIndex fromResourceMethods(@NonNull Set<@NonNull ResourceMethod> resourceMethods) {
			requireNonNull(resourceMethods);

			Map<RouteIndexKey, RouteNode> roots = new HashMap<>();

			for (ResourceMethod resourceMethod : resourceMethods) {
				RouteIndexKey key = new RouteIndexKey(resourceMethod.getHttpMethod(), resourceMethod.isSseEventSource());
				RouteNode root = roots.computeIfAbsent(key, ignored -> new RouteNode());
				root.add(resourceMethod);
			}

			return new RouteIndex(Collections.unmodifiableMap(roots));
		}

		@NonNull
		private Set<@NonNull ResourceMethod> matchingResourceMethods(@NonNull HttpMethod httpMethod,
																																 @NonNull ServerType serverType,
																																 @NonNull ResourcePath resourcePath) {
			requireNonNull(httpMethod);
			requireNonNull(serverType);
			requireNonNull(resourcePath);

			if (resourcePath == ResourcePath.OPTIONS_SPLAT_RESOURCE_PATH)
				return Collections.emptySet();

			RouteNode root = this.roots.get(new RouteIndexKey(httpMethod, serverType == ServerType.SSE));

			if (root == null)
				return Collections.emptySet();

			Set<ResourceMethod> matches = new HashSet<>(4);
			List<RouteNode> activeNodes = List.of(root);
			root.addVarargsMatches(matches);

			for (String component : resourcePath.getComponents()) {
				List<RouteNode> nextNodes = new ArrayList<>(activeNodes.size() * 2);

				for (RouteNode activeNode : activeNodes) {
					RouteNode literalChild = activeNode.literalChildren.get(component);

					if (literalChild != null) {
						nextNodes.add(literalChild);
						literalChild.addVarargsMatches(matches);
					}

					RouteNode placeholderChild = activeNode.placeholderChild;

					if (placeholderChild != null) {
						nextNodes.add(placeholderChild);
						placeholderChild.addVarargsMatches(matches);
					}
				}

				if (nextNodes.isEmpty()) {
					removeFalsePositives(matches, resourcePath);
					return matches.isEmpty() ? Collections.emptySet() : matches;
				}

				activeNodes = nextNodes;
			}

			for (RouteNode activeNode : activeNodes)
				activeNode.addTerminalMatches(matches);

			removeFalsePositives(matches, resourcePath);
			return matches.isEmpty() ? Collections.emptySet() : matches;
		}

		private void removeFalsePositives(@NonNull Set<@NonNull ResourceMethod> matches,
																			@NonNull ResourcePath resourcePath) {
			requireNonNull(matches);
			requireNonNull(resourcePath);
			matches.removeIf(resourceMethod -> !resourcePath.matches(resourceMethod.getResourcePathDeclaration()));
		}
	}

	private static final class RouteNode {
		@NonNull
		private final Map<@NonNull String, @NonNull RouteNode> literalChildren;
		@Nullable
		private RouteNode placeholderChild;
		@NonNull
		private final List<@NonNull ResourceMethod> terminalResourceMethods;
		@NonNull
		private final List<@NonNull ResourceMethod> varargsResourceMethods;

		private RouteNode() {
			this.literalChildren = new HashMap<>();
			this.terminalResourceMethods = new ArrayList<>(1);
			this.varargsResourceMethods = new ArrayList<>(1);
		}

		private void add(@NonNull ResourceMethod resourceMethod) {
			requireNonNull(resourceMethod);

			List<ResourcePathDeclaration.Component> components = resourceMethod.getResourcePathDeclaration().getComponents();
			boolean varargs = resourceMethod.getResourcePathDeclaration().getVarargsComponent().isPresent();
			int fixedComponentCount = components.size() - (varargs ? 1 : 0);
			RouteNode node = this;

			for (int i = 0; i < fixedComponentCount; i++) {
				ResourcePathDeclaration.Component component = components.get(i);

				if (component.getType() == ResourcePathDeclaration.ComponentType.LITERAL) {
					node = node.literalChildren.computeIfAbsent(component.getValue(), ignored -> new RouteNode());
				} else {
					if (node.placeholderChild == null)
						node.placeholderChild = new RouteNode();

					node = node.placeholderChild;
				}
			}

			if (varargs) {
				node.varargsResourceMethods.add(resourceMethod);
			} else {
				node.terminalResourceMethods.add(resourceMethod);
			}
		}

		private void addTerminalMatches(@NonNull Set<@NonNull ResourceMethod> matches) {
			requireNonNull(matches);
			matches.addAll(this.terminalResourceMethods);
		}

		private void addVarargsMatches(@NonNull Set<@NonNull ResourceMethod> matches) {
			requireNonNull(matches);
			matches.addAll(this.varargsResourceMethods);
		}
	}

	private record RouteIndexKey(@NonNull HttpMethod httpMethod,
															 @NonNull Boolean sseEventSource) {
		private RouteIndexKey {
			requireNonNull(httpMethod);
			requireNonNull(sseEventSource);
		}
	}

	private record SpecificityKey(@NonNull HttpMethod httpMethod,
															 @NonNull Boolean sseEventSource,
															 @NonNull Boolean hasVarargs,
															 @NonNull Long placeholderCount,
															 @NonNull Long literalCount) {
		private SpecificityKey {
			requireNonNull(httpMethod);
			requireNonNull(sseEventSource);
			requireNonNull(hasVarargs);
			requireNonNull(placeholderCount);
			requireNonNull(literalCount);
		}
	}

	@NonNull
	protected Map<@NonNull HttpMethod, @NonNull Set<@NonNull Method>> createMethodsByHttpMethod(@NonNull Set<@NonNull Method> methods) {
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
				else if (annotation instanceof SseEventSource || annotation instanceof SseEventSources)
					httpMethod = HttpMethod.GET;

				if (httpMethod == null)
					continue;

				Set<Method> httpMethodMethods = methodsByMethod.computeIfAbsent(httpMethod, k -> new HashSet<>());
				httpMethodMethods.add(method);
			}
		}

		return methodsByMethod;
	}

	@NonNull
	protected Set<@NonNull Method> extractMethods(@NonNull Set<@NonNull Class<?>> resourceClasses) {
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
							|| annotation instanceof SseEventSource
							|| annotation instanceof GETs
							|| annotation instanceof POSTs
							|| annotation instanceof PUTs
							|| annotation instanceof PATCHes
							|| annotation instanceof DELETEs
							|| annotation instanceof OPTIONSes
							|| annotation instanceof HEADs
							|| annotation instanceof SseEventSources)
						methods.add(method);

		return methods;
	}

	@NonNull
	@Override
	public Set<@NonNull ResourceMethod> getResourceMethods() {
		return this.resourceMethods;
	}

	@NonNull
	public Set<@NonNull Method> getMethods() {
		return this.methods;
	}

	@NonNull
	public Map<@NonNull Method, @NonNull Set<@NonNull HttpMethodResourcePathDeclaration>> getHttpMethodResourcePathDeclarationsByMethod() {
		return this.httpMethodResourcePathDeclarationsByMethod;
	}

	@NonNull
	protected Map<@NonNull HttpMethod, @NonNull Set<@NonNull Method>> getMethodsByHttpMethod() {
		return this.methodsByHttpMethod;
	}

	@ThreadSafe
	protected static class HttpMethodResourcePathDeclaration {
		@NonNull
		private final HttpMethod httpMethod;
		@NonNull
		private final ResourcePathDeclaration resourcePathDeclaration;
		@NonNull
		private final Boolean sseEventSource;

		public HttpMethodResourcePathDeclaration(@NonNull HttpMethod httpMethod,
																						 @NonNull ResourcePathDeclaration resourcePathDeclaration) {
			this(httpMethod, resourcePathDeclaration, false);
		}

		public HttpMethodResourcePathDeclaration(@NonNull HttpMethod httpMethod,
																						 @NonNull ResourcePathDeclaration resourcePathDeclaration,
																						 @NonNull Boolean sseEventSource) {
			requireNonNull(httpMethod);
			requireNonNull(resourcePathDeclaration);
			requireNonNull(sseEventSource);

			this.httpMethod = httpMethod;
			this.resourcePathDeclaration = resourcePathDeclaration;
			this.sseEventSource = sseEventSource;
		}

		@Override
		public String toString() {
			return format("%s{httpMethod=%s, resourcePathDeclaration=%s, sseEventSource=%s}", getClass().getSimpleName(),
					getHttpMethod(), getResourcePathDeclaration(), isSseEventSource());
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;

			if (!(object instanceof HttpMethodResourcePathDeclaration httpMethodResourcePathDeclaration))
				return false;

			return Objects.equals(getHttpMethod(), httpMethodResourcePathDeclaration.getHttpMethod())
					&& Objects.equals(getResourcePathDeclaration(), httpMethodResourcePathDeclaration.getResourcePathDeclaration())
					&& Objects.equals(isSseEventSource(), httpMethodResourcePathDeclaration.isSseEventSource());
		}

		@Override
		public int hashCode() {
			return Objects.hash(getHttpMethod(), getResourcePathDeclaration(), isSseEventSource());
		}

		@NonNull
		public HttpMethod getHttpMethod() {
			return this.httpMethod;
		}

		@NonNull
		public ResourcePathDeclaration getResourcePathDeclaration() {
			return this.resourcePathDeclaration;
		}

		@NonNull
		public Boolean isSseEventSource() {
			return this.sseEventSource;
		}
	}
}
