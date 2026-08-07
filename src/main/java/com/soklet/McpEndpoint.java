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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * An immutable MCP endpoint registration.
 * <p>
 * An endpoint may contain only its path and server information. Such an
 * operation-free endpoint remains valid and advertises no optional operation
 * capabilities.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpEndpoint {
	@NonNull
	private final String path;
	@NonNull
	private final McpImplementation serverInformation;
	private final boolean includeServerInformation;
	@Nullable
	private final String instructions;
	@NonNull
	private final List<@NonNull McpToolRegistration<?>> tools;
	@NonNull
	private final List<@NonNull McpPromptRegistration> prompts;
	@NonNull
	private final List<@NonNull McpResourceRegistration> resources;
	@Nullable
	private final McpResourceListHandler resourceListHandler;
	@NonNull
	private final McpCachePolicy resourcesListCachePolicy;
	@NonNull
	private final McpCachePolicy resourceTemplatesListCachePolicy;
	@Nullable
	private final String toolRateLimiterName;
	@Nullable
	private final McpRateLimiter toolRateLimiter;
	@Nullable
	private final McpSubscriptionConfig subscriptions;

	/**
	 * Vends a builder primed with an MCP endpoint path.
	 *
	 * @param path the absolute endpoint path
	 * @return a builder for endpoint registrations
	 * @throws IllegalArgumentException if the path is not a non-root absolute
	 *                                  path or contains a query or fragment
	 */
	@NonNull
	public static Builder withPath(@NonNull String path) {
		return new Builder(normalizePath(path));
	}

	private McpEndpoint(@NonNull Builder builder) {
		requireNonNull(builder);

		if (builder.serverInformation == null)
			throw new IllegalStateException(
					"MCP endpoint server information must be configured.");

		this.path = builder.path;
		this.serverInformation = builder.serverInformation;
		this.includeServerInformation = builder.includeServerInformation;
		this.instructions = builder.instructions;
		this.tools = List.copyOf(builder.tools);
		this.prompts = List.copyOf(builder.prompts);
		this.resources = List.copyOf(builder.resources);
		this.resourceListHandler = builder.resourceListHandler;
		this.resourcesListCachePolicy = builder.resourcesListCachePolicy;
		this.resourceTemplatesListCachePolicy =
				builder.resourceTemplatesListCachePolicy;
		this.toolRateLimiterName = builder.toolRateLimiterName;
		this.toolRateLimiter = builder.toolRateLimiter;
		this.subscriptions = builder.subscriptions;

		Set<String> toolNames = new LinkedHashSet<>();
		for (McpToolRegistration<?> tool : this.tools) {
			if (!toolNames.add(tool.getName()))
				throw new IllegalStateException(
						"Duplicate MCP tool name: " + tool.getName());
		}
		Set<String> promptNames = new LinkedHashSet<>();
		for (McpPromptRegistration prompt : this.prompts) {
			if (!promptNames.add(prompt.getName()))
				throw new IllegalStateException(
						"Duplicate MCP prompt name: " + prompt.getName());
		}
		Set<URI> exactResourceUris = new LinkedHashSet<>();
		Set<String> resourceUriTemplates = new LinkedHashSet<>();
		for (McpResourceRegistration resource : this.resources) {
			if (resource.getAddressType() == McpResourceAddressType.URI) {
				URI uri = resource.getUri().orElseThrow();
				if (!exactResourceUris.add(uri))
					throw new IllegalStateException(
							"Duplicate MCP exact resource URI: " + uri);
			} else {
				String uriTemplate = resource.getUriTemplate().orElseThrow();
				if (!resourceUriTemplates.add(uriTemplate))
					throw new IllegalStateException(
							"Duplicate MCP resource URI template: " + uriTemplate);
			}
		}
	}

	private McpEndpoint(@NonNull McpEndpoint endpoint,
			@NonNull McpSubscriptionConfig subscriptions) {
		requireNonNull(endpoint);
		this.path = endpoint.path;
		this.serverInformation = endpoint.serverInformation;
		this.includeServerInformation = endpoint.includeServerInformation;
		this.instructions = endpoint.instructions;
		this.tools = endpoint.tools;
		this.prompts = endpoint.prompts;
		this.resources = endpoint.resources;
		this.resourceListHandler = endpoint.resourceListHandler;
		this.resourcesListCachePolicy = endpoint.resourcesListCachePolicy;
		this.resourceTemplatesListCachePolicy =
				endpoint.resourceTemplatesListCachePolicy;
		this.toolRateLimiterName = endpoint.toolRateLimiterName;
		this.toolRateLimiter = endpoint.toolRateLimiter;
		this.subscriptions = requireNonNull(subscriptions);
	}

	/**
	 * The normalized absolute endpoint path.
	 *
	 * @return the endpoint path
	 */
	@NonNull
	public String getPath() {
		return this.path;
	}

	/**
	 * The required implementation information advertised by this endpoint.
	 *
	 * @return the server implementation information
	 */
	@NonNull
	public McpImplementation getServerInformation() {
		return this.serverInformation;
	}

	/**
	 * Indicates whether Soklet includes the configured server implementation at
	 * {@code _meta["io.modelcontextprotocol/serverInfo"]} in MCP results.
	 *
	 * @return {@code true} when MCP result metadata includes server information
	 */
	public boolean isServerInformationIncluded() {
		return this.includeServerInformation;
	}

	/**
	 * Optional human-readable instructions for clients using this endpoint.
	 *
	 * @return the instructions, or the empty optional if none were configured
	 */
	@NonNull
	public Optional<@NonNull String> getInstructions() {
		return Optional.ofNullable(this.instructions);
	}

	/**
	 * Returns the tools exposed by this endpoint in registration order.
	 *
	 * @return immutable tool registrations
	 */
	@NonNull
	public List<@NonNull McpToolRegistration<?>> getTools() {
		return this.tools;
	}

	/**
	 * Returns the prompts exposed by this endpoint in registration order.
	 *
	 * @return immutable prompt registrations
	 */
	@NonNull
	public List<@NonNull McpPromptRegistration> getPrompts() {
		return this.prompts;
	}

	/**
	 * Returns exact-URI and URI-template resource registrations in registration
	 * order.
	 * <p>
	 * When {@link #getResourceListHandler()} is empty, Soklet derives the static
	 * {@code resources/list} page from only the exact-URI registrations in this
	 * list. Template registrations are advertised separately by
	 * {@code resources/templates/list}.
	 *
	 * @return immutable resource registrations
	 */
	@NonNull
	public List<@NonNull McpResourceRegistration> getResources() {
		return this.resources;
	}

	/**
	 * Returns the optional sole custom {@code resources/list} handler.
	 * <p>
	 * When present, the returned handler is authoritative; Soklet does not merge
	 * exact registrations into its pages. When absent, the endpoint uses the
	 * single-page static fallback.
	 *
	 * @return custom resource-list handler, or empty for the static fallback
	 */
	@NonNull
	public Optional<@NonNull McpResourceListHandler> getResourceListHandler() {
		return Optional.ofNullable(this.resourceListHandler);
	}

	/**
	 * Returns the fixed cache policy for every {@code resources/list} page.
	 *
	 * @return resources-list cache policy
	 */
	@NonNull
	public McpCachePolicy getResourcesListCachePolicy() {
		return this.resourcesListCachePolicy;
	}

	/**
	 * Returns the fixed cache policy for {@code resources/templates/list}.
	 *
	 * @return resource-template-list cache policy
	 */
	@NonNull
	public McpCachePolicy getResourceTemplatesListCachePolicy() {
		return this.resourceTemplatesListCachePolicy;
	}

	/**
	 * Returns the named tool-limiter override.
	 * <p>
	 * At most one of this value and {@link #getToolRateLimiter()} is present.
	 *
	 * @return registry limiter name, or the empty optional for a direct or
	 * inherited limiter
	 */
	@NonNull
	public Optional<@NonNull String> getToolRateLimiterName() {
		return Optional.ofNullable(this.toolRateLimiterName);
	}

	/**
	 * Returns the direct tool-limiter override.
	 * <p>
	 * At most one of this value and {@link #getToolRateLimiterName()} is present.
	 *
	 * @return direct limiter, or the empty optional for a named or inherited
	 * limiter
	 */
	@NonNull
	public Optional<@NonNull McpRateLimiter> getToolRateLimiter() {
		return Optional.ofNullable(this.toolRateLimiter);
	}

	/**
	 * Returns this endpoint's resource-subscription configuration.
	 *
	 * @return subscription configuration, or the empty optional if none was
	 * configured
	 */
	@NonNull
	public Optional<@NonNull McpSubscriptionConfig> getSubscriptions() {
		return Optional.ofNullable(this.subscriptions);
	}

	@NonNull
	McpEndpoint withSubscriptions(
			@NonNull McpSubscriptionConfig subscriptions) {
		return new McpEndpoint(this, subscriptions);
	}

	@NonNull
	static String normalizePath(@NonNull String path) {
		requireNonNull(path);
		String strippedPath = path.strip();

		if (!strippedPath.startsWith("/") || strippedPath.length() == 1
				|| strippedPath.contains("?") || strippedPath.contains("#"))
			throw new IllegalArgumentException(
					"MCP endpoint path must be a non-root absolute path without a query or fragment.");

		String normalizedPath = ResourcePathDeclaration.normalizePath(strippedPath);

		if (normalizedPath.length() == 1)
			throw new IllegalArgumentException("MCP endpoint path must not be the root path.");

		return normalizedPath;
	}

	/**
	 * Builder for immutable {@link McpEndpoint} registrations.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final String path;
		@Nullable
		private McpImplementation serverInformation;
		private boolean includeServerInformation;
		@Nullable
		private String instructions;
		@NonNull
		private final List<@NonNull McpToolRegistration<?>> tools;
		@NonNull
		private final List<@NonNull McpPromptRegistration> prompts;
		@NonNull
		private final List<@NonNull McpResourceRegistration> resources;
		@Nullable
		private McpResourceListHandler resourceListHandler;
		@NonNull
		private McpCachePolicy resourcesListCachePolicy;
		@NonNull
		private McpCachePolicy resourceTemplatesListCachePolicy;
		@Nullable
		private String toolRateLimiterName;
		@Nullable
		private McpRateLimiter toolRateLimiter;
		@Nullable
		private McpSubscriptionConfig subscriptions;

		private Builder(@NonNull String path) {
			this.path = requireNonNull(path);
			this.includeServerInformation = true;
			this.tools = new ArrayList<>();
			this.prompts = new ArrayList<>();
			this.resources = new ArrayList<>();
			this.resourcesListCachePolicy =
					McpCachePolicy.privateNoCacheInstance();
			this.resourceTemplatesListCachePolicy =
					McpCachePolicy.privateNoCacheInstance();
		}

		/**
		 * Sets the required implementation information advertised by this endpoint.
		 *
		 * @param serverInformation the server implementation information
		 * @return this builder
		 */
		@NonNull
		public Builder serverInformation(
				@NonNull McpImplementation serverInformation) {
			this.serverInformation = requireNonNull(serverInformation);
			return this;
		}

		/**
		 * Controls whether Soklet includes the configured server implementation at
		 * {@code _meta["io.modelcontextprotocol/serverInfo"]} in MCP results. The
		 * default is {@code true}.
		 *
		 * @param includeServerInformation whether MCP result metadata includes server
		 *                                 information
		 * @return this builder
		 */
		@NonNull
		public Builder includeServerInformation(boolean includeServerInformation) {
			this.includeServerInformation = includeServerInformation;
			return this;
		}

		/**
		 * Sets nonblank human-readable instructions for clients using this endpoint.
		 *
		 * @param instructions the endpoint instructions
		 * @return this builder
		 * @throws IllegalArgumentException if the instructions are blank
		 */
		@NonNull
		public Builder instructions(@NonNull String instructions) {
			requireNonNull(instructions);

			if (instructions.isBlank())
				throw new IllegalArgumentException(
						"MCP endpoint instructions must not be blank.");

			this.instructions = instructions;
			return this;
		}

		/**
		 * Adds a tool registration.
		 *
		 * @param tool tool registration
		 * @return this builder
		 */
		@NonNull
		public Builder tool(@NonNull McpToolRegistration<?> tool) {
			this.tools.add(requireNonNull(tool));
			return this;
		}

		/**
		 * Adds tool registrations in iteration order.
		 *
		 * @param tools tool registrations
		 * @return this builder
		 */
		@NonNull
		public Builder tools(
				@NonNull Collection<? extends @NonNull McpToolRegistration<?>> tools) {
			requireNonNull(tools);
			for (McpToolRegistration<?> tool : tools)
				tool(tool);
			return this;
		}

		/**
		 * Adds a prompt registration.
		 *
		 * @param prompt prompt registration
		 * @return this builder
		 */
		@NonNull
		public Builder prompt(@NonNull McpPromptRegistration prompt) {
			this.prompts.add(requireNonNull(prompt));
			return this;
		}

		/**
		 * Adds prompt registrations in iteration order.
		 *
		 * @param prompts prompt registrations
		 * @return this builder
		 */
		@NonNull
		public Builder prompts(
				@NonNull Collection<? extends @NonNull McpPromptRegistration> prompts) {
			requireNonNull(prompts);
			for (McpPromptRegistration prompt : prompts)
				prompt(prompt);
			return this;
		}

		/**
		 * Adds an exact-URI or URI-template resource registration.
		 *
		 * @param resource resource registration
		 * @return this builder
		 */
		@NonNull
		public Builder resource(@NonNull McpResourceRegistration resource) {
			this.resources.add(requireNonNull(resource));
			return this;
		}

		/**
		 * Adds resource registrations in iteration order.
		 *
		 * @param resources resource registrations
		 * @return this builder
		 */
		@NonNull
		public Builder resources(
				@NonNull Collection<? extends @NonNull McpResourceRegistration> resources) {
			requireNonNull(resources);
			for (McpResourceRegistration resource : resources)
				resource(resource);
			return this;
		}

		/**
		 * Installs the sole custom {@code resources/list} handler.
		 * <p>
		 * A custom handler is authoritative for every returned page; exact resource
		 * registrations are not merged automatically. Omitting this setting selects
		 * the static single-page fallback.
		 *
		 * @param resourceListHandler custom list handler
		 * @return this builder
		 * @throws IllegalStateException if a handler was already installed
		 */
		@NonNull
		public Builder resourceListHandler(
				@NonNull McpResourceListHandler resourceListHandler) {
			if (this.resourceListHandler != null)
				throw new IllegalStateException(
						"An MCP resource-list handler is already configured.");
			this.resourceListHandler = requireNonNull(resourceListHandler);
			return this;
		}

		/**
		 * Sets the fixed scope and default time to live for every
		 * {@code resources/list} page. The default is private scope with a zero
		 * time to live.
		 *
		 * @param cachePolicy resources-list cache policy
		 * @return this builder
		 */
		@NonNull
		public Builder resourcesListCachePolicy(
				@NonNull McpCachePolicy cachePolicy) {
			this.resourcesListCachePolicy = requireNonNull(cachePolicy);
			return this;
		}

		/**
		 * Sets the fixed scope and default time to live for
		 * {@code resources/templates/list}. The default is private scope with a
		 * zero time to live.
		 *
		 * @param cachePolicy resource-template-list cache policy
		 * @return this builder
		 */
		@NonNull
		public Builder resourceTemplatesListCachePolicy(
				@NonNull McpCachePolicy cachePolicy) {
			this.resourceTemplatesListCachePolicy = requireNonNull(cachePolicy);
			return this;
		}

		/**
		 * Sets a named tool-limiter override.
		 * <p>
		 * Sequential named and direct setter calls are last-call-wins. This call
		 * clears any direct limiter previously configured on this builder.
		 *
		 * @param limiterName nonblank name in the server limiter registry
		 * @return this builder
		 */
		@NonNull
		public Builder toolRateLimiter(@NonNull String limiterName) {
			requireNonNull(limiterName);
			if (limiterName.isBlank())
				throw new IllegalArgumentException(
						"MCP rate-limiter name must not be blank.");
			this.toolRateLimiterName = limiterName;
			this.toolRateLimiter = null;
			return this;
		}

		/**
		 * Sets a direct tool-limiter override.
		 * <p>
		 * Sequential named and direct setter calls are last-call-wins. This call
		 * clears any limiter name previously configured on this builder.
		 *
		 * @param toolRateLimiter direct tool limiter
		 * @return this builder
		 */
		@NonNull
		public Builder toolRateLimiter(@NonNull McpRateLimiter toolRateLimiter) {
			this.toolRateLimiter = requireNonNull(toolRateLimiter);
			this.toolRateLimiterName = null;
			return this;
		}

		/**
		 * Sets the endpoint's resource-subscription configuration.
		 * <p>
		 * Sequential calls are last-call-wins. The immutable configuration and its
		 * application-owned publisher are retained by reference.
		 *
		 * @param subscriptions resource-subscription configuration
		 * @return this builder
		 */
		@NonNull
		public Builder subscriptions(
				@NonNull McpSubscriptionConfig subscriptions) {
			this.subscriptions = requireNonNull(subscriptions);
			return this;
		}

		/**
		 * Builds an immutable endpoint.
		 * <p>
		 * No tool, prompt, or resource operation is required. Tool and prompt
		 * names must each be unique within the endpoint, as must exact resource
		 * URIs and resource URI templates.
		 *
		 * @return the endpoint
		 * @throws IllegalStateException if server information was not configured or
		 *                               a tool name, prompt name, exact resource URI,
		 *                               or resource URI template is duplicated
		 */
		@NonNull
		public McpEndpoint build() {
			return new McpEndpoint(this);
		}
	}
}
