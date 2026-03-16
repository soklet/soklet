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

import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Contract for discovering MCP endpoints and composing programmatic handlers onto them.
 * <p>
 * Standard threadsafe implementations can be acquired via:
 * <ul>
 *   <li>{@link #fromClasspathIntrospection()} for production discovery via {@link SokletProcessor}-generated metadata</li>
 *   <li>{@link #fromClasses(Set)} for runtime reflection against a known set of endpoint classes</li>
 * </ul>
 * <p>
 * Programmatic handlers may be layered onto either base resolver via the {@code withX(...)} methods.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpHandlerResolver {
	/**
	 * Resolves MCP endpoint metadata for the given endpoint class.
	 *
	 * @param endpointClass the endpoint class
	 * @return resolved endpoint registration metadata, if available
	 */
	@NonNull
	Optional<McpEndpointRegistration> endpointRegistrationForClass(@NonNull Class<? extends McpEndpoint> endpointClass);

	/**
	 * Vends the set of all registered MCP endpoint metadata in the system.
	 *
	 * @return all resolved endpoint registrations
	 */
	@NonNull
	Set<@NonNull McpEndpointRegistration> getEndpointRegistrations();

	/**
	 * Returns a new resolver with the given tool handler layered onto the specified endpoint class.
	 *
	 * @param toolHandler   the tool handler to add
	 * @param endpointClass the endpoint class to which the handler belongs
	 * @return a new resolver instance containing the overlay
	 */
	@NonNull
	McpHandlerResolver withTool(@NonNull McpToolHandler toolHandler,
															@NonNull Class<? extends McpEndpoint> endpointClass);

	/**
	 * Returns a new resolver with the given prompt handler layered onto the specified endpoint class.
	 *
	 * @param promptHandler the prompt handler to add
	 * @param endpointClass the endpoint class to which the handler belongs
	 * @return a new resolver instance containing the overlay
	 */
	@NonNull
	McpHandlerResolver withPrompt(@NonNull McpPromptHandler promptHandler,
																@NonNull Class<? extends McpEndpoint> endpointClass);

	/**
	 * Returns a new resolver with the given resource handler layered onto the specified endpoint class.
	 *
	 * @param resourceHandler the resource handler to add
	 * @param endpointClass   the endpoint class to which the handler belongs
	 * @return a new resolver instance containing the overlay
	 */
	@NonNull
	McpHandlerResolver withResource(@NonNull McpResourceHandler resourceHandler,
																	@NonNull Class<? extends McpEndpoint> endpointClass);

	/**
	 * Returns a new resolver with the given resource-list handler layered onto the specified endpoint class.
	 *
	 * @param resourceListHandler the resource-list handler to add
	 * @param endpointClass       the endpoint class to which the handler belongs
	 * @return a new resolver instance containing the overlay
	 */
	@NonNull
	McpHandlerResolver withResourceList(@NonNull McpResourceListHandler resourceListHandler,
																			@NonNull Class<? extends McpEndpoint> endpointClass);

	/**
	 * Acquires a threadsafe {@link McpHandlerResolver} implementation which discovers MCP endpoints by examining a processor-generated lookup table.
	 * <p>
	 * This implementation requires that your application be compiled with the {@link SokletProcessor} annotation processor.
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 *
	 * @return a resolver backed by classpath introspection
	 */
	@NonNull
	static McpHandlerResolver fromClasspathIntrospection() {
		return DefaultMcpHandlerResolver.fromClasspathIntrospection();
	}

	/**
	 * Acquires a threadsafe {@link McpHandlerResolver} implementation which discovers MCP endpoints by reflecting over the given endpoint classes.
	 * <p>
	 * This method is guaranteed to return a new instance.
	 *
	 * @param endpointClasses the endpoint classes to inspect
	 * @return a resolver backed by the provided endpoint classes
	 */
	@NonNull
	static McpHandlerResolver fromClasses(@NonNull Set<@NonNull Class<?>> endpointClasses) {
		requireNonNull(endpointClasses);
		return DefaultMcpHandlerResolver.fromClasses(endpointClasses);
	}
}
