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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * An immutable registry of MCP endpoints and their handlers.
 * <p>
 * Resolver methods preserve endpoint registration order. Every resolver
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
public interface McpHandlerResolver {
	/**
	 * The endpoints in deterministic registration order.
	 *
	 * @return an immutable endpoint list
	 */
	@NonNull
	List<@NonNull McpEndpoint> getEndpoints();

	/**
	 * Returns a resolver containing the current endpoints followed by the given
	 * endpoint.
	 *
	 * @param endpoint the endpoint to append
	 * @return a new immutable resolver
	 * @throws IllegalArgumentException if the endpoint path is already registered
	 */
	@NonNull
	McpHandlerResolver withEndpoint(@NonNull McpEndpoint endpoint);

	/**
	 * Resolves every generated MCP endpoint descriptor visible through the
	 * current thread's context class loader.
	 *
	 * <p>Endpoint classes are not initialized and endpoint instances are not
	 * acquired during descriptor discovery. Generated handlers use
	 * {@link InstanceProvider#defaultInstance()} when they are invoked. Soklet
	 * does not retain or close the returned endpoint instance.
	 *
	 * @return an immutable resolver ordered by endpoint binary name
	 * @throws IllegalStateException if no generated descriptor is found or an
	 *                               index or provider is malformed, conflicting,
	 *                               or cannot be loaded
	 */
	@NonNull
	static McpHandlerResolver fromClasspathIntrospection() {
		return fromClasspathIntrospection(InstanceProvider.defaultInstance());
	}

	/**
	 * Resolves every generated MCP endpoint descriptor visible through the
	 * current thread's context class loader.
	 *
	 * <p>The supplied provider may be called concurrently and is consulted once
	 * per annotated operation invocation. It is not called during descriptor
	 * discovery or list operations. The provider is application-owned and alone
	 * determines whether returned instances are new, scoped, or shared; Soklet
	 * neither caches nor closes them.
	 *
	 * @param instanceProvider application endpoint-instance provider
	 * @return an immutable resolver ordered by endpoint binary name
	 * @throws IllegalStateException if no generated descriptor is found or an
	 *                               index or provider is malformed, conflicting,
	 *                               or cannot be loaded
	 * @throws NullPointerException if the provider is null
	 */
	@NonNull
	static McpHandlerResolver fromClasspathIntrospection(
			@NonNull InstanceProvider instanceProvider) {
		requireNonNull(instanceProvider);
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		if (classLoader == null)
			classLoader = McpHandlerResolver.class.getClassLoader();
		try {
			return new DefaultMcpHandlerResolver(
					McpGeneratedEndpointProviderLoader.loadAll(classLoader,
							instanceProvider));
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException(
					"Generated MCP endpoint descriptors conflict.", exception);
		}
	}

	/**
	 * Resolves generated descriptors for endpoint classes in the supplied order.
	 *
	 * <p>Endpoint instances are acquired through
	 * {@link InstanceProvider#defaultInstance()} only when an annotated
	 * operation is invoked. Soklet does not retain or close the returned
	 * instance.
	 *
	 * @param endpointClasses one or more annotated endpoint classes
	 * @return an immutable resolver in the supplied order
	 * @throws IllegalArgumentException if no classes are supplied, a class is
	 *                                  duplicated, or a class has no generated
	 *                                  descriptor, or two selected endpoints
	 *                                  have the same normalized path
	 * @throws IllegalStateException if an index or provider is malformed,
	 *                               conflicting, or cannot be loaded
	 * @throws NullPointerException if the array or one of its classes is null
	 */
	@NonNull
	static McpHandlerResolver fromClasses(
			@NonNull Class<?> @NonNull ... endpointClasses) {
		return fromClasses(InstanceProvider.defaultInstance(), endpointClasses);
	}

	/**
	 * Resolves generated descriptors for endpoint classes in the supplied order.
	 *
	 * <p>The supplied provider may be called concurrently and is consulted once
	 * per annotated operation invocation. It is not called during descriptor
	 * discovery or list operations. The provider is application-owned and alone
	 * determines whether returned instances are new, scoped, or shared; Soklet
	 * neither caches nor closes them.
	 *
	 * @param instanceProvider application endpoint-instance provider
	 * @param endpointClasses one or more annotated endpoint classes
	 * @return an immutable resolver in the supplied order
	 * @throws IllegalArgumentException if no classes are supplied, a class is
	 *                                  duplicated, or a class has no generated
	 *                                  descriptor, or two selected endpoints
	 *                                  have the same normalized path
	 * @throws IllegalStateException if an index or provider is malformed,
	 *                               conflicting, or cannot be loaded
	 * @throws NullPointerException if the provider, array, or one of its classes
	 *                              is null
	 */
	@NonNull
	static McpHandlerResolver fromClasses(
			@NonNull InstanceProvider instanceProvider,
			@NonNull Class<?> @NonNull ... endpointClasses) {
		requireNonNull(instanceProvider);
		requireNonNull(endpointClasses);
		return new DefaultMcpHandlerResolver(
				McpGeneratedEndpointProviderLoader.loadClasses(
						Arrays.asList(endpointClasses.clone()), instanceProvider));
	}

	/**
	 * Creates an immutable resolver from endpoints in registration order.
	 *
	 * @param endpoints one or more endpoints
	 * @return an immutable handler resolver
	 * @throws IllegalArgumentException if no endpoints are supplied or two
	 *                                  endpoints have the same normalized path
	 */
	@NonNull
	static McpHandlerResolver fromEndpoints(
			@NonNull Collection<@NonNull McpEndpoint> endpoints) {
		return new DefaultMcpHandlerResolver(endpoints);
	}
}

/**
 * Package-private immutable {@link McpHandlerResolver} implementation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpHandlerResolver implements McpHandlerResolver {
	@NonNull
	private final List<@NonNull McpEndpoint> endpoints;

	DefaultMcpHandlerResolver(
			@NonNull Collection<@NonNull McpEndpoint> endpoints) {
		requireNonNull(endpoints);
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

		this.endpoints = copiedEndpoints;
	}

	@Override
	@NonNull
	public List<@NonNull McpEndpoint> getEndpoints() {
		return this.endpoints;
	}

	@Override
	@NonNull
	public McpHandlerResolver withEndpoint(@NonNull McpEndpoint endpoint) {
		requireNonNull(endpoint);
		List<@NonNull McpEndpoint> endpoints = new ArrayList<>(getEndpoints());
		endpoints.add(endpoint);
		return new DefaultMcpHandlerResolver(endpoints);
	}
}
