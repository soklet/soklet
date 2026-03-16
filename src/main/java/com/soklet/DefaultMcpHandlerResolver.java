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

import com.soklet.annotation.McpListResources;
import com.soklet.annotation.McpPrompt;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpHandlerResolver implements McpHandlerResolver {
	@NonNull
	private static final DefaultMcpHandlerResolver DEFAULT_INSTANCE;

	static {
		DEFAULT_INSTANCE = new DefaultMcpHandlerResolver(McpEndpointDeclarationLoader.loadAll(Thread.currentThread().getContextClassLoader()));
	}

	@NonNull
	static DefaultMcpHandlerResolver fromClasspathIntrospection() {
		return DEFAULT_INSTANCE;
	}

	@NonNull
	static DefaultMcpHandlerResolver fromClasses(@NonNull Set<@NonNull Class<?>> endpointClasses) {
		requireNonNull(endpointClasses);
		return new DefaultMcpHandlerResolver(endpointClasses);
	}

	@NonNull
	private final Map<@NonNull Class<? extends McpEndpoint>, @NonNull ResolvedEndpoint> resolvedEndpointsByClass;
	@NonNull
	private final Set<@NonNull McpEndpointRegistration> endpointRegistrations;

	private DefaultMcpHandlerResolver(@NonNull Set<@NonNull Class<?>> endpointClasses) {
		requireNonNull(endpointClasses);
		this.resolvedEndpointsByClass = Collections.unmodifiableMap(buildResolvedEndpoints(endpointClasses));
		Set<McpEndpointRegistration> endpointRegistrations = this.resolvedEndpointsByClass.values().stream()
				.map(ResolvedEndpoint::toRegistration)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		this.endpointRegistrations = Collections.unmodifiableSet(endpointRegistrations);
		validateNoAmbiguousEndpointPaths(this.resolvedEndpointsByClass.values());
	}

	private DefaultMcpHandlerResolver(@NonNull Map<@NonNull Class<? extends McpEndpoint>, @NonNull ResolvedEndpoint> resolvedEndpointsByClass) {
		requireNonNull(resolvedEndpointsByClass);
		this.resolvedEndpointsByClass = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedEndpointsByClass));
		Set<McpEndpointRegistration> endpointRegistrations = this.resolvedEndpointsByClass.values().stream()
				.map(ResolvedEndpoint::toRegistration)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		this.endpointRegistrations = Collections.unmodifiableSet(endpointRegistrations);
		validateNoAmbiguousEndpointPaths(this.resolvedEndpointsByClass.values());
	}

	@NonNull
	@Override
	public Optional<McpEndpointRegistration> endpointRegistrationForClass(@NonNull Class<? extends McpEndpoint> endpointClass) {
		requireNonNull(endpointClass);
		return resolvedEndpointForClass(endpointClass).map(ResolvedEndpoint::toRegistration);
	}

	@NonNull
	@Override
	public Set<@NonNull McpEndpointRegistration> getEndpointRegistrations() {
		return this.endpointRegistrations;
	}

	@NonNull
	@Override
	public McpHandlerResolver withTool(@NonNull McpToolHandler toolHandler,
																		 @NonNull Class<? extends McpEndpoint> endpointClass) {
		requireNonNull(toolHandler);
		requireNonNull(endpointClass);

		return withEndpointOverlay(endpointClass, resolvedEndpoint ->
				resolvedEndpoint.withTool(new ProgrammaticToolBinding(toolHandler)));
	}

	@NonNull
	@Override
	public McpHandlerResolver withPrompt(@NonNull McpPromptHandler promptHandler,
																			 @NonNull Class<? extends McpEndpoint> endpointClass) {
		requireNonNull(promptHandler);
		requireNonNull(endpointClass);

		return withEndpointOverlay(endpointClass, resolvedEndpoint ->
				resolvedEndpoint.withPrompt(new ProgrammaticPromptBinding(promptHandler)));
	}

	@NonNull
	@Override
	public McpHandlerResolver withResource(@NonNull McpResourceHandler resourceHandler,
																				 @NonNull Class<? extends McpEndpoint> endpointClass) {
		requireNonNull(resourceHandler);
		requireNonNull(endpointClass);

		return withEndpointOverlay(endpointClass, resolvedEndpoint ->
				resolvedEndpoint.withResource(new ProgrammaticResourceBinding(resourceHandler)));
	}

	@NonNull
	@Override
	public McpHandlerResolver withResourceList(@NonNull McpResourceListHandler resourceListHandler,
																						 @NonNull Class<? extends McpEndpoint> endpointClass) {
		requireNonNull(resourceListHandler);
		requireNonNull(endpointClass);

		return withEndpointOverlay(endpointClass, resolvedEndpoint ->
				resolvedEndpoint.withResourceList(new ProgrammaticResourceListBinding(resourceListHandler)));
	}

	@NonNull
	Set<@NonNull ResolvedEndpoint> getResolvedEndpoints() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(this.resolvedEndpointsByClass.values()));
	}

	@NonNull
	Optional<ResolvedEndpoint> resolvedEndpointForClass(@NonNull Class<? extends McpEndpoint> endpointClass) {
		requireNonNull(endpointClass);
		return Optional.ofNullable(this.resolvedEndpointsByClass.get(endpointClass));
	}

	@NonNull
	Optional<ResolvedEndpoint> resolvedEndpointForRequest(@NonNull Request request) {
		requireNonNull(request);

		List<ResolvedEndpoint> matchingEndpoints = this.resolvedEndpointsByClass.values().stream()
				.filter(resolvedEndpoint -> resolvedEndpoint.endpointPathDeclaration().matches(request.getResourcePath()))
				.collect(Collectors.toCollection(ArrayList::new));

		if (matchingEndpoints.isEmpty())
			return Optional.empty();

		if (matchingEndpoints.size() == 1)
			return Optional.of(matchingEndpoints.get(0));

		Comparator<ResolvedEndpoint> specificityComparator = Comparator
				.comparing((ResolvedEndpoint resolvedEndpoint) -> resolvedEndpoint.endpointPathDeclaration().getVarargsComponent().isPresent())
				.thenComparingLong(DefaultMcpHandlerResolver::placeholderCount)
				.thenComparingLong(resolvedEndpoint -> -literalCount(resolvedEndpoint));

		ResolvedEndpoint mostSpecific = matchingEndpoints.stream()
				.min(specificityComparator)
				.orElse(null);

		if (mostSpecific == null)
			return Optional.empty();

		List<ResolvedEndpoint> equallySpecificEndpoints = matchingEndpoints.stream()
				.filter(resolvedEndpoint -> specificityComparator.compare(resolvedEndpoint, mostSpecific) == 0)
				.collect(Collectors.toCollection(ArrayList::new));

		if (equallySpecificEndpoints.size() > 1) {
			throw new IllegalStateException(format("Multiple MCP endpoints match '%s'. Ambiguous matches were:\n%s",
					request.getResourcePath().getPath(),
					equallySpecificEndpoints.stream()
							.map(resolvedEndpoint -> format("%s -> %s",
									resolvedEndpoint.endpointPathDeclaration().getPath(),
									resolvedEndpoint.endpointClass().getName()))
							.collect(Collectors.joining("\n"))));
		}

		return Optional.of(mostSpecific);
	}

	@NonNull
	private McpHandlerResolver withEndpointOverlay(@NonNull Class<? extends McpEndpoint> endpointClass,
																								 @NonNull Function<@NonNull ResolvedEndpoint, @NonNull ResolvedEndpoint> overlayFunction) {
		requireNonNull(endpointClass);
		requireNonNull(overlayFunction);

		ResolvedEndpoint baseResolvedEndpoint = resolvedEndpointForClass(endpointClass)
				.orElseGet(() -> resolveEndpointClass(endpointClass));
		ResolvedEndpoint overlaidResolvedEndpoint = overlayFunction.apply(baseResolvedEndpoint);
		Map<Class<? extends McpEndpoint>, ResolvedEndpoint> resolvedEndpointsByClass = new LinkedHashMap<>(this.resolvedEndpointsByClass);
		resolvedEndpointsByClass.put(endpointClass, overlaidResolvedEndpoint);
		return new DefaultMcpHandlerResolver(resolvedEndpointsByClass);
	}

	@NonNull
	private static Map<@NonNull Class<? extends McpEndpoint>, @NonNull ResolvedEndpoint> buildResolvedEndpoints(@NonNull Set<@NonNull Class<?>> endpointClasses) {
		requireNonNull(endpointClasses);

		Map<Class<? extends McpEndpoint>, ResolvedEndpoint> resolvedEndpointsByClass = new LinkedHashMap<>();

		for (Class<?> endpointClass : endpointClasses) {
			requireNonNull(endpointClass);

			if (!endpointClass.isAnnotationPresent(McpServerEndpoint.class))
				continue;

			ResolvedEndpoint resolvedEndpoint = resolveEndpointClass(endpointClass);
			resolvedEndpointsByClass.put(resolvedEndpoint.endpointClass(), resolvedEndpoint);
		}

		return resolvedEndpointsByClass;
	}

	@NonNull
	@SuppressWarnings("unchecked")
	private static ResolvedEndpoint resolveEndpointClass(@NonNull Class<?> endpointClass) {
		requireNonNull(endpointClass);

		if (!McpEndpoint.class.isAssignableFrom(endpointClass))
			throw new IllegalStateException(format("Classes annotated with @%s must implement %s. Incorrect class was %s",
					McpServerEndpoint.class.getSimpleName(), McpEndpoint.class.getSimpleName(), endpointClass.getName()));

		McpServerEndpoint serverEndpoint = endpointClass.getAnnotation(McpServerEndpoint.class);

		if (serverEndpoint == null)
			throw new IllegalStateException(format("Cannot register MCP endpoint class %s because it is not annotated with @%s",
					endpointClass.getName(), McpServerEndpoint.class.getSimpleName()));

		Class<? extends McpEndpoint> typedEndpointClass = (Class<? extends McpEndpoint>) endpointClass;
		Map<String, ToolBinding> toolsByName = new LinkedHashMap<>();
		Map<String, PromptBinding> promptsByName = new LinkedHashMap<>();
		Map<String, ResourceBinding> resourcesByUri = new LinkedHashMap<>();
		Map<String, String> resourceNamesByName = new LinkedHashMap<>();
		ResourceListBinding resourceListBinding = null;

		for (Method method : endpointClass.getMethods()) {
			McpTool tool = method.getAnnotation(McpTool.class);

			if (tool != null) {
				ToolBinding binding = new AnnotatedToolBinding(method, tool.name(), tool.description());

				if (toolsByName.putIfAbsent(binding.name(), binding) != null)
					throw new IllegalStateException(format("Duplicate MCP tool name '%s' for endpoint %s",
							binding.name(), endpointClass.getName()));
			}

			McpPrompt prompt = method.getAnnotation(McpPrompt.class);

			if (prompt != null) {
				PromptBinding binding = new AnnotatedPromptBinding(method, prompt.name(), prompt.description(), normalizeBlankToNull(prompt.title()));

				if (promptsByName.putIfAbsent(binding.name(), binding) != null)
					throw new IllegalStateException(format("Duplicate MCP prompt name '%s' for endpoint %s",
							binding.name(), endpointClass.getName()));
			}

			McpResource resource = method.getAnnotation(McpResource.class);

			if (resource != null) {
				ResourceBinding binding = new AnnotatedResourceBinding(method, resource.uri(), resource.name(), resource.mimeType(), normalizeBlankToNull(resource.description()));

				if (resourcesByUri.putIfAbsent(binding.uri(), binding) != null)
					throw new IllegalStateException(format("Duplicate MCP resource URI '%s' for endpoint %s",
							binding.uri(), endpointClass.getName()));

				if (resourceNamesByName.putIfAbsent(binding.name(), binding.uri()) != null)
					throw new IllegalStateException(format("Duplicate MCP resource name '%s' for endpoint %s",
							binding.name(), endpointClass.getName()));
			}

			if (method.isAnnotationPresent(McpListResources.class)) {
				if (resourceListBinding != null)
					throw new IllegalStateException(format("At most one @%s method may be declared on endpoint %s",
							McpListResources.class.getSimpleName(), endpointClass.getName()));

				resourceListBinding = new AnnotatedResourceListBinding(method);
			}
		}

		return new ResolvedEndpoint(
				typedEndpointClass,
				ResourcePathDeclaration.fromPath(serverEndpoint.path()),
				serverEndpoint.name(),
				serverEndpoint.version(),
				normalizeBlankToNull(serverEndpoint.instructions()),
				normalizeBlankToNull(serverEndpoint.title()),
				normalizeBlankToNull(serverEndpoint.description()),
				normalizeBlankToNull(serverEndpoint.websiteUrl()),
				toolsByName,
				promptsByName,
				resourcesByUri,
				resourceListBinding
		);
	}

	private static void validateNoAmbiguousEndpointPaths(@NonNull Iterable<@NonNull ResolvedEndpoint> resolvedEndpoints) {
		requireNonNull(resolvedEndpoints);

		List<ResolvedEndpoint> asList = new ArrayList<>();
		resolvedEndpoints.forEach(asList::add);

		if (asList.size() < 2)
			return;

		Map<SpecificityKey, List<ResolvedEndpoint>> groups = new LinkedHashMap<>();

		for (ResolvedEndpoint resolvedEndpoint : asList) {
			ResourcePathDeclaration declaration = resolvedEndpoint.endpointPathDeclaration();
			SpecificityKey key = new SpecificityKey(
					declaration.getVarargsComponent().isPresent(),
					placeholderCount(resolvedEndpoint),
					literalCount(resolvedEndpoint));

			groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(resolvedEndpoint);
		}

		List<String> ambiguousPairs = new ArrayList<>();

		for (List<ResolvedEndpoint> group : groups.values()) {
			if (group.size() < 2)
				continue;

			for (int i = 0; i < group.size(); i++) {
				ResolvedEndpoint first = group.get(i);

				for (int j = i + 1; j < group.size(); j++) {
					ResolvedEndpoint second = group.get(j);

					if (resourcePathDeclarationsOverlap(first.endpointPathDeclaration(), second.endpointPathDeclaration()))
						ambiguousPairs.add(format("%s -> %s vs %s -> %s",
								first.endpointPathDeclaration().getPath(),
								first.endpointClass().getName(),
								second.endpointPathDeclaration().getPath(),
								second.endpointClass().getName()));
				}
			}
		}

		if (!ambiguousPairs.isEmpty()) {
			throw new IllegalStateException(format(
					"Ambiguous MCP endpoint path declarations detected. These paths are equally specific and overlap:\n%s",
					String.join("\n", ambiguousPairs)));
		}
	}

	private static long placeholderCount(@NonNull ResolvedEndpoint resolvedEndpoint) {
		requireNonNull(resolvedEndpoint);
		return resolvedEndpoint.endpointPathDeclaration().getComponents().stream()
				.filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.PLACEHOLDER)
				.count();
	}

	private static long literalCount(@NonNull ResolvedEndpoint resolvedEndpoint) {
		requireNonNull(resolvedEndpoint);
		return resolvedEndpoint.endpointPathDeclaration().getComponents().stream()
				.filter(component -> component.getType() == ResourcePathDeclaration.ComponentType.LITERAL)
				.count();
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

	@Nullable
	private static String normalizeBlankToNull(@Nullable String value) {
		if (value == null)
			return null;

		String trimmedValue = value.trim();
		return trimmedValue.isEmpty() ? null : trimmedValue;
	}

	@ThreadSafe
	static final class McpEndpointDeclarationLoader {
		private McpEndpointDeclarationLoader() {}

		@NonNull
		static Set<@NonNull Class<?>> loadAll(@NonNull ClassLoader classLoader) {
			requireNonNull(classLoader);

			Set<Class<?>> endpointClasses = new LinkedHashSet<>();
			Enumeration<URL> resources;

			try {
				resources = classLoader.getResources(SokletProcessor.MCP_ENDPOINT_LOOKUP_TABLE_PATH);
			} catch (Exception e) {
				throw new RuntimeException(format("Unable to access Soklet's MCP endpoint lookup table. Is '%s' on the classpath and well-formed? Did you supply javac with the following options? '-parameters -processor %s'?",
						SokletProcessor.MCP_ENDPOINT_LOOKUP_TABLE_PATH, SokletProcessor.class.getName()), e);
			}

			try {
				while (resources.hasMoreElements()) {
					URL url = resources.nextElement();

					try (var inputStream = url.openStream();
							 var bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
						for (String line; (line = bufferedReader.readLine()) != null; ) {
							String trimmedLine = line.trim();

							if (trimmedLine.isBlank() || trimmedLine.startsWith("#"))
								continue;

							String[] parts = trimmedLine.split("\\|", -1);

							if (parts.length == 0)
								continue;

							String className = new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8);
							Class<?> endpointClass = Class.forName(className);
							endpointClasses.add(endpointClass);
						}
					}
				}
			} catch (Exception e) {
				throw new RuntimeException(format("Unable to process Soklet's MCP endpoint lookup table. Is '%s' on the classpath and well-formed? Did you supply javac with the following options? '-parameters -processor %s'?",
						SokletProcessor.MCP_ENDPOINT_LOOKUP_TABLE_PATH, SokletProcessor.class.getName()), e);
			}

			return endpointClasses;
		}
	}

	@Immutable
	record SpecificityKey(
			@NonNull Boolean hasVarargs,
			@NonNull Long placeholderCount,
			@NonNull Long literalCount
	) {
		SpecificityKey {
			requireNonNull(hasVarargs);
			requireNonNull(placeholderCount);
			requireNonNull(literalCount);
		}
	}

	@Immutable
	record ResolvedEndpoint(
			@NonNull Class<? extends McpEndpoint> endpointClass,
			@NonNull ResourcePathDeclaration endpointPathDeclaration,
			@NonNull String name,
			@NonNull String version,
			@Nullable String instructions,
			@Nullable String title,
			@Nullable String description,
			@Nullable String websiteUrl,
			@NonNull Map<@NonNull String, @NonNull ToolBinding> toolsByName,
			@NonNull Map<@NonNull String, @NonNull PromptBinding> promptsByName,
			@NonNull Map<@NonNull String, @NonNull ResourceBinding> resourcesByUri,
			@Nullable ResourceListBinding resourceListBinding
	) {
		ResolvedEndpoint {
			requireNonNull(endpointClass);
			requireNonNull(endpointPathDeclaration);
			requireNonNull(name);
			requireNonNull(version);
			requireNonNull(toolsByName);
			requireNonNull(promptsByName);
			requireNonNull(resourcesByUri);

			toolsByName = Collections.unmodifiableMap(new LinkedHashMap<>(toolsByName));
			promptsByName = Collections.unmodifiableMap(new LinkedHashMap<>(promptsByName));
			resourcesByUri = Collections.unmodifiableMap(new LinkedHashMap<>(resourcesByUri));
		}

		@NonNull
		ResolvedEndpoint withTool(@NonNull ToolBinding toolBinding) {
			requireNonNull(toolBinding);

			Map<String, ToolBinding> toolsByName = new LinkedHashMap<>(this.toolsByName);

			if (toolsByName.putIfAbsent(toolBinding.name(), toolBinding) != null)
				throw new IllegalStateException(format("Duplicate MCP tool name '%s' for endpoint %s",
						toolBinding.name(), endpointClass().getName()));

			return new ResolvedEndpoint(endpointClass(), endpointPathDeclaration(), name(), version(), instructions(), title(), description(), websiteUrl(),
					toolsByName, promptsByName(), resourcesByUri(), resourceListBinding());
		}

		@NonNull
		ResolvedEndpoint withPrompt(@NonNull PromptBinding promptBinding) {
			requireNonNull(promptBinding);

			Map<String, PromptBinding> promptsByName = new LinkedHashMap<>(this.promptsByName);

			if (promptsByName.putIfAbsent(promptBinding.name(), promptBinding) != null)
				throw new IllegalStateException(format("Duplicate MCP prompt name '%s' for endpoint %s",
						promptBinding.name(), endpointClass().getName()));

			return new ResolvedEndpoint(endpointClass(), endpointPathDeclaration(), name(), version(), instructions(), title(), description(), websiteUrl(),
					toolsByName(), promptsByName, resourcesByUri(), resourceListBinding());
		}

		@NonNull
		ResolvedEndpoint withResource(@NonNull ResourceBinding resourceBinding) {
			requireNonNull(resourceBinding);

			Map<String, ResourceBinding> resourcesByUri = new LinkedHashMap<>(this.resourcesByUri);

			if (resourcesByUri.putIfAbsent(resourceBinding.uri(), resourceBinding) != null)
				throw new IllegalStateException(format("Duplicate MCP resource URI '%s' for endpoint %s",
						resourceBinding.uri(), endpointClass().getName()));

			if (this.resourcesByUri.values().stream().anyMatch(existingBinding -> existingBinding.name().equals(resourceBinding.name())))
				throw new IllegalStateException(format("Duplicate MCP resource name '%s' for endpoint %s",
						resourceBinding.name(), endpointClass().getName()));

			return new ResolvedEndpoint(endpointClass(), endpointPathDeclaration(), name(), version(), instructions(), title(), description(), websiteUrl(),
					toolsByName(), promptsByName(), resourcesByUri, resourceListBinding());
		}

		@NonNull
		ResolvedEndpoint withResourceList(@NonNull ResourceListBinding resourceListBinding) {
			requireNonNull(resourceListBinding);

			if (this.resourceListBinding != null)
				throw new IllegalStateException(format("At most one MCP resource-list handler may be declared for endpoint %s",
						endpointClass().getName()));

			return new ResolvedEndpoint(endpointClass(), endpointPathDeclaration(), name(), version(), instructions(), title(), description(), websiteUrl(),
					toolsByName(), promptsByName(), resourcesByUri(), resourceListBinding);
		}

		@NonNull
		McpEndpointRegistration toRegistration() {
			return new McpEndpointRegistration(
					endpointClass(),
					endpointPathDeclaration(),
					name(),
					version(),
					instructions(),
					title(),
					description(),
					websiteUrl(),
					toolsByName().keySet(),
					promptsByName().keySet(),
					resourcesByUri().keySet(),
					resourceListBinding() != null
			);
		}
	}

	sealed interface ToolBinding permits AnnotatedToolBinding, ProgrammaticToolBinding {
		@NonNull
		String name();

		@NonNull
		String description();
	}

	sealed interface PromptBinding permits AnnotatedPromptBinding, ProgrammaticPromptBinding {
		@NonNull
		String name();

		@NonNull
		String description();
	}

	sealed interface ResourceBinding permits AnnotatedResourceBinding, ProgrammaticResourceBinding {
		@NonNull
		String uri();

		@NonNull
		String name();

		@NonNull
		String mimeType();

		@NonNull
		Optional<String> optionalDescription();
	}

	sealed interface ResourceListBinding permits AnnotatedResourceListBinding, ProgrammaticResourceListBinding {}

	@Immutable
	record AnnotatedToolBinding(
			@NonNull Method method,
			@NonNull String name,
			@NonNull String description
	) implements ToolBinding {
		AnnotatedToolBinding {
			requireNonNull(method);
			requireNonNull(name);
			requireNonNull(description);
		}
	}

	@Immutable
	record ProgrammaticToolBinding(
			@NonNull McpToolHandler toolHandler
	) implements ToolBinding {
		ProgrammaticToolBinding {
			requireNonNull(toolHandler);
		}

		@NonNull
		@Override
		public String name() {
			return this.toolHandler.getName();
		}

		@NonNull
		@Override
		public String description() {
			return this.toolHandler.getDescription();
		}
	}

	@Immutable
	record AnnotatedPromptBinding(
			@NonNull Method method,
			@NonNull String name,
			@NonNull String description,
			@Nullable String title
	) implements PromptBinding {
		AnnotatedPromptBinding {
			requireNonNull(method);
			requireNonNull(name);
			requireNonNull(description);
		}
	}

	@Immutable
	record ProgrammaticPromptBinding(
			@NonNull McpPromptHandler promptHandler
	) implements PromptBinding {
		ProgrammaticPromptBinding {
			requireNonNull(promptHandler);
		}

		@NonNull
		@Override
		public String name() {
			return this.promptHandler.getName();
		}

		@NonNull
		@Override
		public String description() {
			return this.promptHandler.getDescription();
		}
	}

	@Immutable
	record AnnotatedResourceBinding(
			@NonNull Method method,
			@NonNull String uri,
			@NonNull String name,
			@NonNull String mimeType,
			@Nullable String description
	) implements ResourceBinding {
		AnnotatedResourceBinding {
			requireNonNull(method);
			requireNonNull(uri);
			requireNonNull(name);
			requireNonNull(mimeType);
		}

		@NonNull
		@Override
		public Optional<String> optionalDescription() {
			return Optional.ofNullable(this.description);
		}
	}

	@Immutable
	record ProgrammaticResourceBinding(
			@NonNull McpResourceHandler resourceHandler
	) implements ResourceBinding {
		ProgrammaticResourceBinding {
			requireNonNull(resourceHandler);
		}

		@NonNull
		@Override
		public String uri() {
			return this.resourceHandler.getUri();
		}

		@NonNull
		@Override
		public String name() {
			return this.resourceHandler.getName();
		}

		@NonNull
		@Override
		public String mimeType() {
			return this.resourceHandler.getMimeType();
		}

		@NonNull
		@Override
		public Optional<String> optionalDescription() {
			return this.resourceHandler.getDescription();
		}
	}

	@Immutable
	record AnnotatedResourceListBinding(
			@NonNull Method method
	) implements ResourceListBinding {
		AnnotatedResourceListBinding {
			requireNonNull(method);
		}
	}

	@Immutable
	record ProgrammaticResourceListBinding(
			@NonNull McpResourceListHandler resourceListHandler
	) implements ResourceListBinding {
		ProgrammaticResourceListBinding {
			requireNonNull(resourceListHandler);
		}
	}
}
