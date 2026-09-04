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

import com.soklet.internal.mcp.generated.McpGeneratedEndpointProviderLoader;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * An immutable registry of MCP endpoints and their handlers.
 * <p>
 * Registry methods preserve endpoint registration order. Every registry
 * contains at least one endpoint, and no two endpoints may have the same
 * normalized path.
 * <p>
 * Generated endpoints in a named application module require their endpoint
 * package to be open or exported to Soklet. Packages containing non-public
 * typed record arguments or results must be open to Soklet for runtime
 * conversion.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpEndpointRegistry {
	@NonNull
	private final List<@NonNull McpEndpoint> endpoints;
	@NonNull
	private final Map<@NonNull Class<?>, @NonNull McpEndpoint>
			generatedEndpoints;

	McpEndpointRegistry(
			@NonNull Collection<@NonNull McpEndpoint> endpoints) {
		this(endpoints, Map.of());
	}

	McpEndpointRegistry(
			@NonNull Map<@NonNull Class<?>, @NonNull McpEndpoint>
					generatedEndpoints) {
		this(generatedEndpoints.values(), generatedEndpoints);
	}

	private McpEndpointRegistry(
			@NonNull Collection<@NonNull McpEndpoint> endpoints,
			@NonNull Map<@NonNull Class<?>, @NonNull McpEndpoint>
					generatedEndpoints) {
		requireNonNull(endpoints);
		requireNonNull(generatedEndpoints);
		List<@NonNull McpEndpoint> copiedEndpoints = List.copyOf(endpoints);

		if (copiedEndpoints.isEmpty())
			throw new IllegalArgumentException(
					"At least one MCP endpoint must be configured.");

		Set<@NonNull String> paths = new LinkedHashSet<>();

		for (McpEndpoint endpoint : copiedEndpoints) {
			if (!paths.add(endpoint.getPath()))
				throw new IllegalArgumentException(
						"Duplicate MCP endpoint path '" + endpoint.getPath() + "'.");
		}

		Map<Class<?>, McpEndpoint> copiedGeneratedEndpoints =
				new IdentityHashMap<>();
		Set<McpEndpoint> endpointIdentities = Collections.newSetFromMap(
				new IdentityHashMap<>());
		endpointIdentities.addAll(copiedEndpoints);
		for (Map.Entry<Class<?>, McpEndpoint> generatedEndpoint
				: generatedEndpoints.entrySet()) {
			Class<?> endpointClass = requireNonNull(generatedEndpoint.getKey());
			McpEndpoint endpoint = requireNonNull(generatedEndpoint.getValue());
			if (!endpointIdentities.contains(endpoint))
				throw new IllegalArgumentException(
						"Every generated MCP endpoint must identify a configured endpoint instance.");
			copiedGeneratedEndpoints.put(endpointClass, endpoint);
		}

		this.endpoints = copiedEndpoints;
		this.generatedEndpoints = Collections.unmodifiableMap(
				copiedGeneratedEndpoints);
	}

	/**
	 * The endpoints in deterministic registration order.
	 *
	 * @return an immutable endpoint list
	 */
	@NonNull
	public List<@NonNull McpEndpoint> getEndpoints() {
		return this.endpoints;
	}

	/**
	 * Returns a registry containing the current endpoints followed by the given
	 * endpoint.
	 *
	 * @param endpoint the endpoint to append
	 * @return a new immutable registry
	 * @throws IllegalArgumentException if the endpoint path is already registered
	 */
	@NonNull
	public McpEndpointRegistry withEndpoint(@NonNull McpEndpoint endpoint) {
		requireNonNull(endpoint);
		List<@NonNull McpEndpoint> endpoints = new ArrayList<>(getEndpoints());
		endpoints.add(endpoint);
		return new McpEndpointRegistry(endpoints, this.generatedEndpoints);
	}

	/**
	 * Returns a registry whose generated endpoint for the supplied annotated
	 * class carries the given resource-subscription configuration.
	 * <p>
	 * Soklet selects the already-generated endpoint by the exact loaded
	 * {@link Class} identity retained during generated-descriptor discovery. It
	 * does not initialize the endpoint class, acquire an endpoint instance, or
	 * rediscover handler metadata. Endpoint order and every other endpoint value
	 * are preserved.
	 *
	 * @param annotatedEndpointClass annotated endpoint class whose generated
	 *                               endpoint is selected
	 * @param subscriptionConfig resource-subscription configuration
	 * @return a new immutable registry
	 * @throws IllegalArgumentException if this registry did not load a generated
	 *                                  endpoint for the exact supplied class
	 * @throws NullPointerException if either argument is null
	 */
	@NonNull
	public McpEndpointRegistry withSubscriptionConfig(
			@NonNull Class<?> annotatedEndpointClass,
			@NonNull McpSubscriptionConfig subscriptionConfig) {
		requireNonNull(annotatedEndpointClass);
		requireNonNull(subscriptionConfig);
		McpEndpoint generatedEndpoint = this.generatedEndpoints.get(
				annotatedEndpointClass);
		if (generatedEndpoint == null)
			throw new IllegalArgumentException(
					"No generated MCP endpoint is registered for annotated class '"
							+ annotatedEndpointClass.getName() + "'.");

		List<@NonNull McpEndpoint> endpoints = new ArrayList<>(getEndpoints());
		for (int index = 0; index < endpoints.size(); ++index) {
			McpEndpoint endpoint = endpoints.get(index);
			if (endpoint == generatedEndpoint) {
				McpEndpoint replacedEndpoint = endpoint.withSubscriptionConfig(
						subscriptionConfig);
				endpoints.set(index, replacedEndpoint);
				Map<Class<?>, McpEndpoint> generatedEndpoints =
						new IdentityHashMap<>(this.generatedEndpoints);
				generatedEndpoints.put(annotatedEndpointClass, replacedEndpoint);
				return new McpEndpointRegistry(endpoints, generatedEndpoints);
			}
		}

		throw new IllegalStateException(
				"Generated MCP endpoint provenance is inconsistent.");
	}

	/**
	 * Resolves every generated MCP endpoint descriptor visible through the
	 * current thread's context class loader.
	 *
	 * <p>Endpoint classes are not initialized and endpoint instances are not
	 * acquired during descriptor discovery. When a generated handler is invoked,
	 * it acquires its endpoint instance through the
	 * {@link SokletConfig#getInstanceProvider() instance provider} configured on
	 * the {@link SokletConfig} that owns the MCP server. Soklet does not retain or
	 * close the returned endpoint instance. Direct invocation of a generated
	 * registration handler outside a Soklet-managed request uses
	 * {@link InstanceProvider#defaultInstance()}.
	 *
	 * @return an immutable registry ordered by endpoint binary name
	 * @throws IllegalStateException if no generated descriptor is found or an
	 *                               index or provider is malformed, conflicting,
	 *                               or cannot be loaded
	 */
	@NonNull
	public static McpEndpointRegistry fromClasspathIntrospection() {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		if (classLoader == null)
			classLoader = McpEndpointRegistry.class.getClassLoader();
		try {
			return new McpEndpointRegistry(
					McpGeneratedEndpointProviderLoader.loadAllWithProvenance(
							classLoader));
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException(
					"Generated MCP endpoint descriptors conflict.", exception);
		}
	}

	/**
	 * Resolves generated descriptors for endpoint classes in the supplied order.
	 *
	 * <p>Endpoint classes are not initialized and endpoint instances are not
	 * acquired during descriptor discovery. When a generated handler is invoked,
	 * it acquires its endpoint instance through the
	 * {@link SokletConfig#getInstanceProvider() instance provider} configured on
	 * the {@link SokletConfig} that owns the MCP server. Soklet does not retain or
	 * close the returned endpoint instance. Direct invocation of a generated
	 * registration handler outside a Soklet-managed request uses
	 * {@link InstanceProvider#defaultInstance()}.
	 *
	 * @param endpointClasses one or more annotated endpoint classes
	 * @return an immutable registry in the supplied order
	 * @throws IllegalArgumentException if no classes are supplied, a class is
	 *                                  duplicated, or a class has no generated
	 *                                  descriptor, or two selected endpoints
	 *                                  have the same normalized path
	 * @throws IllegalStateException if an index or provider is malformed,
	 *                               conflicting, or cannot be loaded
	 * @throws NullPointerException if the array or one of its classes is null
	 */
	@NonNull
	public static McpEndpointRegistry fromClasses(
			@NonNull Class<?> @NonNull ... endpointClasses) {
		requireNonNull(endpointClasses);
		return new McpEndpointRegistry(
				McpGeneratedEndpointProviderLoader.loadClassesWithProvenance(
						Arrays.asList(endpointClasses.clone())));
	}

	/**
	 * Creates an immutable registry from endpoints in registration order.
	 *
	 * @param endpoints one or more endpoints
	 * @return an immutable endpoint registry
	 * @throws IllegalArgumentException if no endpoints are supplied or two
	 *                                  endpoints have the same normalized path
	 */
	@NonNull
	public static McpEndpointRegistry fromEndpoints(
			@NonNull Collection<@NonNull McpEndpoint> endpoints) {
		return new McpEndpointRegistry(endpoints);
	}
}
