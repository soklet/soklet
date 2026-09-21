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

import com.soklet.internal.mcp.protocol.McpAppMimeType;
import com.soklet.internal.mcp.protocol.McpEndpointPathLimit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
	private final boolean serverInformationIncluded;
	@Nullable
	private final String instructions;
	@NonNull
	private final List<@NonNull McpToolRegistration<?>> toolRegistrations;
	@NonNull
	private final List<@NonNull McpPromptRegistration> promptRegistrations;
	@NonNull
	private final List<@NonNull McpResourceRegistration> resourceRegistrations;
	@NonNull
	private final List<@NonNull McpSkillRegistration> skillRegistrations;
	@NonNull
	private final List<@NonNull McpSkillGroup> skillGroups;
	@NonNull
	private final McpSkillEndpointIndex skillIndex;
	@Nullable
	private final McpSkillListHandler skillListHandler;
	@NonNull
	private final McpCachePolicy skillListCachePolicy;
	@Nullable
	private final McpResourceListHandler resourceListHandler;
	@NonNull
	private final McpCachePolicy resourceListCachePolicy;
	@NonNull
	private final McpCachePolicy resourceTemplateListCachePolicy;
	@Nullable
	private final String toolRateLimiterName;
	@Nullable
	private final McpRateLimiter toolRateLimiter;
	@Nullable
	private final McpSubscriptionConfig subscriptionConfig;

	/**
	 * Vends a builder primed with its required construction values.
	 *
	 * @param path the absolute endpoint path in ASCII raw URI form; percent-encode
	 *             non-ASCII characters
	 * @param implementation implementation information advertised by the endpoint
	 * @return a builder for endpoint registrations
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the path is not a non-root absolute
	 *                                  path, is not valid ASCII raw URI form,
	 *                                  contains a query or fragment, or exceeds
	 *                                  8192 bytes after normalization
	 */
	@NonNull
	public static Builder withPath(@NonNull String path,
			@NonNull McpImplementation implementation) {
		return new Builder(normalizePath(path), implementation);
	}

	private McpEndpoint(@NonNull Builder builder) {
		requireNonNull(builder);

		this.path = builder.path;
		this.serverInformation = builder.serverInformation;
		this.serverInformationIncluded = builder.serverInformationIncluded;
		this.instructions = builder.instructions;
		this.toolRegistrations = List.copyOf(builder.toolRegistrations);
		this.promptRegistrations = List.copyOf(builder.promptRegistrations);
		this.resourceRegistrations = List.copyOf(builder.resourceRegistrations);
		this.skillRegistrations = List.copyOf(builder.skillRegistrations);
		this.skillGroups = List.copyOf(builder.skillGroups);
		this.skillIndex = McpSkillEndpointIndex.from(this.skillRegistrations, this.skillGroups,
				this.resourceRegistrations);
		this.skillListHandler = builder.skillListHandler;
		this.skillListCachePolicy = builder.skillListCachePolicy;
		this.resourceListHandler = builder.resourceListHandler;
		this.resourceListCachePolicy = builder.resourceListCachePolicy;
		this.resourceTemplateListCachePolicy =
				builder.resourceTemplateListCachePolicy;
		this.toolRateLimiterName = builder.toolRateLimiterName;
		this.toolRateLimiter = builder.toolRateLimiter;
		this.subscriptionConfig = builder.subscriptionConfig;

		Set<String> toolNames = new LinkedHashSet<>();
		for (McpToolRegistration<?> tool : this.toolRegistrations) {
			if (!toolNames.add(tool.getName()))
				throw new IllegalStateException(
						"Duplicate MCP tool name: " + tool.getName());
		}
		Set<String> promptNames = new LinkedHashSet<>();
		for (McpPromptRegistration prompt : this.promptRegistrations) {
			if (!promptNames.add(prompt.getName()))
				throw new IllegalStateException(
						"Duplicate MCP prompt name: " + prompt.getName());
		}
		Map<URI, McpResourceRegistration> exactResources = new LinkedHashMap<>();
		Set<String> resourceUriTemplates = new LinkedHashSet<>();
		for (McpResourceRegistration resource : this.resourceRegistrations) {
			if (resource.getAddressType() == McpResourceAddressType.URI) {
				URI uri = resource.getUri().orElseThrow();
				if (exactResources.putIfAbsent(uri, resource) != null)
					throw new IllegalStateException(
							"Duplicate MCP exact resource URI: " + uri);
			} else {
				String uriTemplate = resource.getUriTemplate().orElseThrow();
				if (!resourceUriTemplates.add(uriTemplate))
					throw new IllegalStateException(
							"Duplicate MCP resource URI template: " + uriTemplate);
			}
		}
		for (McpToolRegistration<?> tool : this.toolRegistrations) {
			Optional<URI> resourceUri = McpAppMetadataSupport
					.effectiveToolMetadata(tool.getMetadata(),
							tool.getAppToolMetadata().orElse(null))
					.flatMap(McpAppToolMetadata::getResourceUri);
			if (resourceUri.isPresent()) {
				McpResourceRegistration resource = exactResources.get(resourceUri.get());
				if (resource == null || !hasAppsMimeType(resource))
					throw new IllegalStateException(
							"MCP Apps tool associations require an exact UI resource "
									+ "registration with the Apps MIME profile on the same endpoint.");
			}
		}
		McpSkillEndpointIndex.preflight(this);
	}

	private static boolean hasAppsMimeType(@NonNull McpResourceRegistration resource) {
		String mimeType = resource.getMimeType().orElse(null);
		if (mimeType == null)
			return false;
		try {
			return McpAppMimeType.isAppsProfile(mimeType);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private McpEndpoint(@NonNull McpEndpoint endpoint,
			@NonNull List<@NonNull McpSkillRegistration> skillRegistrations,
			@NonNull List<@NonNull McpSkillGroup> skillGroups,
			@NonNull McpSkillEndpointIndex skillIndex,
			@Nullable McpSubscriptionConfig subscriptionConfig) {
		requireNonNull(endpoint);
		this.path = endpoint.path;
		this.serverInformation = endpoint.serverInformation;
		this.serverInformationIncluded = endpoint.serverInformationIncluded;
		this.instructions = endpoint.instructions;
		this.toolRegistrations = endpoint.toolRegistrations;
		this.promptRegistrations = endpoint.promptRegistrations;
		this.resourceRegistrations = endpoint.resourceRegistrations;
		this.skillRegistrations = skillRegistrations;
		this.skillGroups = skillGroups;
		this.skillIndex = skillIndex;
		this.skillListHandler = endpoint.skillListHandler;
		this.skillListCachePolicy = endpoint.skillListCachePolicy;
		this.resourceListHandler = endpoint.resourceListHandler;
		this.resourceListCachePolicy = endpoint.resourceListCachePolicy;
		this.resourceTemplateListCachePolicy =
				endpoint.resourceTemplateListCachePolicy;
		this.toolRateLimiterName = endpoint.toolRateLimiterName;
		this.toolRateLimiter = endpoint.toolRateLimiter;
		this.subscriptionConfig = subscriptionConfig;
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
	public McpImplementation getServerInfo() {
		return this.serverInformation;
	}

	/**
	 * Indicates whether Soklet includes the configured server implementation at
	 * {@code _meta["io.modelcontextprotocol/serverInfo"]} in MCP results.
	 *
	 * @return {@code true} when MCP result metadata includes server information
	 */
	@NonNull
	public Boolean isServerInfoIncluded() {
		return this.serverInformationIncluded;
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
	public List<@NonNull McpToolRegistration<?>> getToolRegistrations() {
		return this.toolRegistrations;
	}

	/**
	 * Returns the prompts exposed by this endpoint in registration order.
	 *
	 * @return immutable prompt registrations
	 */
	@NonNull
	public List<@NonNull McpPromptRegistration> getPromptRegistrations() {
		return this.promptRegistrations;
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
	public List<@NonNull McpResourceRegistration> getResourceRegistrations() {
		return this.resourceRegistrations;
	}

	/**
	 * Returns only standalone Skills registrations in supplied order. Group members
	 * remain in {@link #getSkillGroups()}; this getter does not flatten them.
	 * Skills configuration is currently construction-only, pending runtime routing.
	 *
	 * @return immutable standalone Skills registrations
	 */
	@NonNull
	public List<@NonNull McpSkillRegistration> getSkillRegistrations() {
		return this.skillRegistrations;
	}

	/**
	 * Returns explicit Skills locale groups in supplied order, without selecting
	 * a locale or granting access to any member.
	 *
	 * @return immutable Skills groups
	 */
	@NonNull
	public List<@NonNull McpSkillGroup> getSkillGroups() {
		return this.skillGroups;
	}

	@NonNull
	McpSkillEndpointIndex skillIndex() { return this.skillIndex; }

	/**
	 * Returns the optional application-owned {@code skills/list} page handler.
	 * When absent, Soklet produces one bounded automatic page. A custom handler
	 * owns pagination and retained snapshots, not canonical skill-file reads.
	 *
	 * @return custom Skills-list handler, or empty for automatic listing
	 */
	@NonNull
	public Optional<@NonNull McpSkillListHandler> getSkillListHandler() {
		return Optional.ofNullable(this.skillListHandler);
	}

	/** @return fixed Skills-list cache scope and default time to live */
	@NonNull
	public McpCachePolicy getSkillListCachePolicy() { return this.skillListCachePolicy; }

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
	public McpCachePolicy getResourceListCachePolicy() {
		return this.resourceListCachePolicy;
	}

	/**
	 * Returns the fixed cache policy for {@code resources/templates/list}.
	 *
	 * @return resource-template-list cache policy
	 */
	@NonNull
	public McpCachePolicy getResourceTemplateListCachePolicy() {
		return this.resourceTemplateListCachePolicy;
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
	 * Returns this endpoint's subscription-change configuration.
	 *
	 * @return subscription configuration, or the empty optional if none was
	 * configured
	 */
	@NonNull
	public Optional<@NonNull McpSubscriptionConfig> getSubscriptionConfig() {
		return Optional.ofNullable(this.subscriptionConfig);
	}

	@NonNull
	McpEndpoint withSubscriptionConfig(
			@NonNull McpSubscriptionConfig subscriptionConfig) {
		return new McpEndpoint(this, this.skillRegistrations, this.skillGroups, this.skillIndex,
				requireNonNull(subscriptionConfig));
	}

	@NonNull
	McpEndpoint withSkillRegistrations(
			@NonNull List<@NonNull McpSkillRegistration> skillRegistrations) {
		List<McpSkillRegistration> snapshot = List.copyOf(skillRegistrations);
		McpSkillEndpointIndex skillIndex = McpSkillEndpointIndex.from(snapshot,
				this.skillGroups, this.resourceRegistrations);
		McpEndpoint endpoint = new McpEndpoint(this, snapshot, this.skillGroups, skillIndex,
				this.subscriptionConfig);
		McpSkillEndpointIndex.preflight(endpoint);
		return endpoint;
	}

	@NonNull
	McpEndpoint withSkillGroups(
			@NonNull List<@NonNull McpSkillGroup> skillGroups) {
		List<McpSkillGroup> snapshot = List.copyOf(skillGroups);
		McpSkillEndpointIndex skillIndex = McpSkillEndpointIndex.from(this.skillRegistrations,
				snapshot, this.resourceRegistrations);
		McpEndpoint endpoint = new McpEndpoint(this, this.skillRegistrations, snapshot, skillIndex,
				this.subscriptionConfig);
		McpSkillEndpointIndex.preflight(endpoint);
		return endpoint;
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

		return McpEndpointPathLimit.requireValidWirePath(normalizedPath);
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
		@NonNull
		private McpImplementation serverInformation;
		private boolean serverInformationIncluded;
		@Nullable
		private String instructions;
		@NonNull
		private List<@NonNull McpToolRegistration<?>> toolRegistrations;
		@NonNull
		private List<@NonNull McpPromptRegistration> promptRegistrations;
		@NonNull
		private List<@NonNull McpResourceRegistration> resourceRegistrations;
		@NonNull
		private List<@NonNull McpSkillRegistration> skillRegistrations;
		@NonNull
		private List<@NonNull McpSkillGroup> skillGroups;
		@Nullable
		private McpSkillListHandler skillListHandler;
		@NonNull
		private McpCachePolicy skillListCachePolicy;
		@Nullable
		private McpResourceListHandler resourceListHandler;
		@NonNull
		private McpCachePolicy resourceListCachePolicy;
		@NonNull
		private McpCachePolicy resourceTemplateListCachePolicy;
		@Nullable
		private String toolRateLimiterName;
		@Nullable
		private McpRateLimiter toolRateLimiter;
		@Nullable
		private McpSubscriptionConfig subscriptionConfig;

		private Builder(@NonNull String path,
				@NonNull McpImplementation implementation) {
			this.path = requireNonNull(path);
			this.serverInformation = requireNonNull(implementation);
			this.serverInformationIncluded = true;
			this.toolRegistrations = List.of();
			this.promptRegistrations = List.of();
			this.resourceRegistrations = List.of();
			this.skillRegistrations = List.of();
			this.skillGroups = List.of();
			this.skillListCachePolicy = McpCachePolicy.privateNoCacheInstance();
			this.resourceListCachePolicy =
					McpCachePolicy.privateNoCacheInstance();
			this.resourceTemplateListCachePolicy =
					McpCachePolicy.privateNoCacheInstance();
		}

		/**
		 * Sets the required implementation information advertised by this endpoint.
		 *
		 * @param implementation the server implementation information
		 * @return this builder
		 */
		@NonNull
		public Builder serverInfo(
				@NonNull McpImplementation implementation) {
			this.serverInformation = requireNonNull(implementation);
			return this;
		}

		/**
		 * Controls whether Soklet includes the configured server implementation at
		 * {@code _meta["io.modelcontextprotocol/serverInfo"]} in MCP results. The
		 * default is {@code true}.
		 *
		 * @param serverInfoIncluded whether MCP result metadata includes server
		 *                           information, or null to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder serverInfoIncluded(
				@Nullable Boolean serverInfoIncluded) {
			this.serverInformationIncluded = serverInfoIncluded == null
					? true : serverInfoIncluded;
			return this;
		}

		/**
		 * Sets nonblank human-readable instructions for clients using this endpoint.
		 *
		 * @param instructions the endpoint instructions, or null to clear them
		 * @return this builder
		 * @throws IllegalArgumentException if the instructions are blank
		 */
		@NonNull
		public Builder instructions(@Nullable String instructions) {
			if (instructions != null && instructions.isBlank())
				throw new IllegalArgumentException(
						"MCP endpoint instructions must not be blank.");

			this.instructions = instructions;
			return this;
		}

		/**
		 * Replaces tool registrations in supplied order.
		 * Null or empty clears the property. The complete list is validated and
		 * snapshotted before replacing the prior value.
		 *
		 * @param toolRegistrations tool registrations, or null to clear
		 * @return this builder
		 * @throws NullPointerException if a list element is null
		 */
		@NonNull
		public Builder toolRegistrations(
				@Nullable List<@NonNull McpToolRegistration<?>> toolRegistrations) {
			this.toolRegistrations = toolRegistrations == null ? List.of()
					: List.copyOf(toolRegistrations);
			return this;
		}

		/**
		 * Replaces prompt registrations in supplied order.
		 * Null or empty clears the property. The complete list is validated and
		 * snapshotted before replacing the prior value.
		 *
		 * @param promptRegistrations prompt registrations, or null to clear
		 * @return this builder
		 * @throws NullPointerException if a list element is null
		 */
		@NonNull
		public Builder promptRegistrations(
				@Nullable List<@NonNull McpPromptRegistration> promptRegistrations) {
			this.promptRegistrations = promptRegistrations == null ? List.of()
					: List.copyOf(promptRegistrations);
			return this;
		}

		/**
		 * Replaces resource registrations in supplied order.
		 * Null or empty clears the property. The complete list is validated and
		 * snapshotted before replacing the prior value.
		 *
		 * @param resourceRegistrations resource registrations, or null to clear
		 * @return this builder
		 * @throws NullPointerException if a list element is null
		 */
		@NonNull
		public Builder resourceRegistrations(
				@Nullable List<@NonNull McpResourceRegistration> resourceRegistrations) {
			this.resourceRegistrations = resourceRegistrations == null ? List.of()
					: List.copyOf(resourceRegistrations);
			return this;
		}

		/**
		 * Replaces standalone Skills registrations in supplied order. Null or empty
		 * clears this property without changing groups. The complete list is
		 * snapshotted before replacing the prior value. Endpoint construction checks
		 * duplicate identities, listing names, shared files and resource collisions
		 * across both sources, independent of setter order.
		 *
		 * @param skillRegistrations standalone Skills registrations, or null to clear
		 * @return this builder
		 * @throws NullPointerException if a list element is null
		 */
		@NonNull
		public Builder skillRegistrations(
				@Nullable List<@NonNull McpSkillRegistration> skillRegistrations) {
			this.skillRegistrations = skillRegistrations == null ? List.of() : List.copyOf(skillRegistrations);
			return this;
		}

		/**
		 * Replaces explicit Skills locale groups in supplied order. Null or empty
		 * clears this property without changing standalone registrations. The
		 * complete list is snapshotted before replacing the prior value. Empty
		 * groups are permitted but contribute no listing name or file owner.
		 *
		 * @param skillGroups Skills groups, or null to clear
		 * @return this builder
		 * @throws NullPointerException if a list element is null
		 */
		@NonNull
		public Builder skillGroups(@Nullable List<@NonNull McpSkillGroup> skillGroups) {
			this.skillGroups = skillGroups == null ? List.of() : List.copyOf(skillGroups);
			return this;
		}

		/**
		 * Installs the application-owned {@code skills/list} page handler.
		 * Each invocation replaces the prior handler. Null restores bounded
		 * automatic single-page listing. The handler owns authenticated cursors and
		 * snapshot restoration, while Soklet validates page membership and current
		 * access without changing canonical file-read handling.
		 *
		 * @param skillListHandler custom Skills-list handler, or null for automatic listing
		 * @return this builder
		 */
		@NonNull
		public Builder skillListHandler(@Nullable McpSkillListHandler skillListHandler) {
			this.skillListHandler = skillListHandler;
			return this;
		}

		/**
		 * Sets fixed cache scope and default freshness for {@code skills/list}.
		 * The default is private scope with zero time to live. Page overrides affect
		 * only freshness and remain subject to localization and security clamps.
		 *
		 * @param skillListCachePolicy Skills-list cache policy, or null to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder skillListCachePolicy(@Nullable McpCachePolicy skillListCachePolicy) {
			this.skillListCachePolicy = skillListCachePolicy == null
					? McpCachePolicy.privateNoCacheInstance() : skillListCachePolicy;
			return this;
		}

		/**
		 * Installs the custom {@code resources/list} handler.
		 * <p>
		 * A custom handler is authoritative for every returned page; exact resource
		 * registrations are not merged automatically. Every descriptor with an
		 * exact {@code uri} must identify either an exact-URI resource registration
		 * or a matching URI-template registration on this endpoint. Soklet validates
		 * the complete page after the handler returns; a violation rejects the request
		 * with JSON-RPC error {@code -32603} and HTTP
		 * status {@code 500}. Null selects the static single-page fallback.
		 * Sequential calls are last-call-wins.
		 *
		 * @param resourceListHandler custom list handler, or null for the static
		 *                            fallback
		 * @return this builder
		 */
		@NonNull
		public Builder resourceListHandler(
				@Nullable McpResourceListHandler resourceListHandler) {
			this.resourceListHandler = resourceListHandler;
			return this;
		}

		/**
		 * Sets the fixed scope and default time to live for every
		 * {@code resources/list} page. The default is private scope with a zero
		 * time to live.
		 *
		 * @param resourceListCachePolicy resources-list cache policy, or null to
		 *                                restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder resourceListCachePolicy(
				@Nullable McpCachePolicy resourceListCachePolicy) {
			this.resourceListCachePolicy = resourceListCachePolicy == null
					? McpCachePolicy.privateNoCacheInstance()
					: resourceListCachePolicy;
			return this;
		}

		/**
		 * Sets the fixed scope and default time to live for
		 * {@code resources/templates/list}. The default is private scope with a
		 * zero time to live.
		 *
		 * @param resourceTemplateListCachePolicy resource-template-list cache
		 *                                        policy, or null to restore the
		 *                                        default
		 * @return this builder
		 */
		@NonNull
		public Builder resourceTemplateListCachePolicy(
				@Nullable McpCachePolicy resourceTemplateListCachePolicy) {
			this.resourceTemplateListCachePolicy =
					resourceTemplateListCachePolicy == null
							? McpCachePolicy.privateNoCacheInstance()
							: resourceTemplateListCachePolicy;
			return this;
		}

		/**
		 * Sets a named tool-limiter override.
		 * <p>
		 * Sequential named and direct setter calls are last-call-wins. This call
		 * clears any direct limiter previously configured on this builder.
		 *
		 * @param toolRateLimiterName nonblank name in the server limiter registry,
		 *                            or null to clear the endpoint override
		 * @return this builder
		 */
		@NonNull
		public Builder toolRateLimiterName(@Nullable String toolRateLimiterName) {
			if (toolRateLimiterName == null) {
				this.toolRateLimiterName = null;
				this.toolRateLimiter = null;
				return this;
			}
			if (toolRateLimiterName.isBlank())
				throw new IllegalArgumentException(
						"MCP rate-limiter name must not be blank.");
			this.toolRateLimiterName = toolRateLimiterName;
			this.toolRateLimiter = null;
			return this;
		}

		/**
		 * Sets a direct tool-limiter override.
		 * <p>
		 * Sequential named and direct setter calls are last-call-wins. This call
		 * clears any limiter name previously configured on this builder.
		 *
		 * @param toolRateLimiter direct tool limiter, or null to clear the endpoint
		 *                        override
		 * @return this builder
		 */
		@NonNull
		public Builder toolRateLimiter(@Nullable McpRateLimiter toolRateLimiter) {
			this.toolRateLimiter = toolRateLimiter;
			this.toolRateLimiterName = null;
			return this;
		}

		/**
		 * Sets the endpoint's subscription-change configuration.
		 * <p>
		 * Sequential calls are last-call-wins. The immutable configuration and its
		 * application-owned publisher are retained by reference.
		 *
		 * @param subscriptionConfig subscription-change configuration, or null to
		 *                           disable subscriptions
		 * @return this builder
		 */
		@NonNull
		public Builder subscriptionConfig(
				@Nullable McpSubscriptionConfig subscriptionConfig) {
			this.subscriptionConfig = subscriptionConfig;
			return this;
		}

		/**
		 * Builds an immutable endpoint.
		 * <p>
		 * No tool, prompt, or resource operation is required. Tool and prompt
		 * names must each be unique within the endpoint, as must exact resource
		 * URIs and resource URI templates.
		 * <p>
		 * Every Apps tool resource association must identify an exact registration
		 * on this endpoint whose declared MIME type is the Apps HTML profile.
		 * Templates and custom resource-list descriptors do not establish this
		 * eligibility. Validation does not invoke application handlers or fetch
		 * resource contents.
		 * <p>Skills names occupy distinct standalone/group listing slots. Registered
		 * descendants must be completely present in every enclosing bundle. Shared
		 * files must have identical bytes and representation, have at most 16 owners,
		 * and cannot overlap ordinary exact-resource or URI-template routes.
		 * Individual Skills responses include endpoint metadata in their output
		 * preflight. Without a custom Skills-list handler, the complete unfiltered
		 * automatic page must fit the output profile and contain at most 32 slots.
		 *
		 * @return the endpoint
		 * @throws IllegalStateException if a tool name, prompt name, exact resource URI,
		 *                               or resource URI template is duplicated,
		 *                               an Apps association has no eligible resource,
		 *                               or Skills configuration conflicts or is incomplete
		 * @throws IllegalArgumentException if a Skills response cannot fit the output
		 *                                  profile, or the automatic page requires a
		 *                                  custom {@code skillListHandler(...)}
		 */
		@NonNull
		public McpEndpoint build() {
			return new McpEndpoint(this);
		}
	}
}
