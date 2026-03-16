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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.Immutable;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable metadata describing a resolved MCP endpoint registration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public record McpEndpointRegistration(
		@NonNull Class<? extends McpEndpoint> endpointClass,
		@NonNull ResourcePathDeclaration endpointPathDeclaration,
		@NonNull String name,
		@NonNull String version,
		@Nullable String instructions,
		@Nullable String title,
		@Nullable String description,
		@Nullable String websiteUrl,
		@NonNull Set<@NonNull String> toolNames,
		@NonNull Set<@NonNull String> promptNames,
		@NonNull Set<@NonNull String> resourceUris,
		@NonNull Boolean hasResourceListHandler
) {
	public McpEndpointRegistration {
		requireNonNull(endpointClass);
		requireNonNull(endpointPathDeclaration);
		requireNonNull(name);
		requireNonNull(version);
		requireNonNull(toolNames);
		requireNonNull(promptNames);
		requireNonNull(resourceUris);
		requireNonNull(hasResourceListHandler);

		toolNames = Set.copyOf(toolNames);
		promptNames = Set.copyOf(promptNames);
		resourceUris = Set.copyOf(resourceUris);
	}
}
