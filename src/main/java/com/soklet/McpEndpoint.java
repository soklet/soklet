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
	private final Set<@NonNull McpProtocolVersion> protocolVersions;
	@NonNull
	private final Set<@NonNull McpProtocolVersion> taskProtocolVersions;
	@NonNull
	private final Set<@NonNull McpProtocolVersion> sessionProtocolVersions;
	@NonNull
	private final Set<@NonNull McpProtocolVersion> subscriptionProtocolVersions;
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
	private final Set<@NonNull McpProtocolVersion> skillListHandlerProtocolVersions;
	@NonNull
	private final McpCachePolicy skillListCachePolicy;
	@Nullable
	private final McpResourceListHandler resourceListHandler;
	@NonNull
	private final Set<@NonNull McpProtocolVersion> resourceListHandlerProtocolVersions;
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
	 * @param path the normalized absolute endpoint path in ASCII raw URI form;
	 *             percent-encode non-ASCII characters. The path is not rewritten.
	 * @param implementation implementation information advertised by the endpoint
	 * @param protocolVersions nonempty exact revisions served at this URL
	 * @return a builder for endpoint registrations
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the path is not a non-root absolute
	 *                                  path, is not valid ASCII raw URI form,
	 *                                  contains a query or fragment, has a trailing
	 *                                  slash, repeated slashes, whitespace or dot
	 *                                  segments, or exceeds 8192 bytes, or the
	 *                                  server name or version is blank
	 */
	@NonNull
	public static Builder withPath(@NonNull String path,
			@NonNull McpImplementation implementation,
			@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
		return new Builder(normalizePath(path), implementation,
				McpProtocolVersion.requiredSet(protocolVersions));
	}

	private McpEndpoint(@NonNull Builder builder) {
		requireNonNull(builder);

		this.path = builder.path;
		this.protocolVersions = builder.protocolVersions;
		this.taskProtocolVersions = builder.taskProtocolVersions;
		this.sessionProtocolVersions = builder.sessionProtocolVersions;
		this.subscriptionProtocolVersions = builder.subscriptionProtocolVersions;
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
		this.skillListHandlerProtocolVersions = builder.skillListHandlerProtocolVersions;
		this.skillListCachePolicy = builder.skillListCachePolicy;
		this.resourceListHandler = builder.resourceListHandler;
		this.resourceListHandlerProtocolVersions = builder.resourceListHandlerProtocolVersions;
		this.resourceListCachePolicy = builder.resourceListCachePolicy;
		this.resourceTemplateListCachePolicy =
				builder.resourceTemplateListCachePolicy;
		this.toolRateLimiterName = builder.toolRateLimiterName;
		this.toolRateLimiter = builder.toolRateLimiter;
		this.subscriptionConfig = builder.subscriptionConfig;
		if (this.protocolVersions.contains(McpProtocolVersion.V2025_03_26))
			throw new IllegalStateException(
					"MCP 2025-03-26 is not implemented by this adapter" + versionContext(Set.of(McpProtocolVersion.V2025_03_26)));

		requireSubset(this.taskProtocolVersions, this.protocolVersions, "Tasks");
		requireSubset(this.sessionProtocolVersions, this.protocolVersions, "sessions");
		if (!Set.of(McpProtocolVersion.V2025_06_18,
				McpProtocolVersion.V2025_11_25).containsAll(this.sessionProtocolVersions))
			throw new IllegalStateException(
					"Sessions currently require MCP 2025-06-18 or 2025-11-25" + versionContext(this.sessionProtocolVersions));
		requireSubset(this.subscriptionProtocolVersions, this.protocolVersions,
				"subscriptions");
		requireSubset(this.skillListHandlerProtocolVersions, this.protocolVersions,
				"Skills-list handler");
		requireSubset(this.resourceListHandlerProtocolVersions, this.protocolVersions,
				"resource-list handler");
		if (!Set.of(McpProtocolVersion.V2026_07_28).containsAll(
				this.taskProtocolVersions))
			throw new IllegalStateException(
					"Tasks currently require MCP 2026-07-28" + versionContext(this.taskProtocolVersions));
		if (!Set.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25,
				McpProtocolVersion.V2026_07_28).containsAll(
				this.subscriptionProtocolVersions))
			throw new IllegalStateException(
					"Subscriptions currently require MCP 2025-06-18, 2025-11-25, or 2026-07-28" + versionContext(this.subscriptionProtocolVersions));
		for (McpProtocolVersion subscriptionProtocolVersion : this.subscriptionProtocolVersions)
			if (subscriptionProtocolVersion != McpProtocolVersion.V2026_07_28
					&& !this.sessionProtocolVersions.contains(subscriptionProtocolVersion))
				throw new IllegalStateException(
						"Legacy subscription revisions must also enable sessions" + versionContext(Set.of(subscriptionProtocolVersion)));
		if (this.subscriptionConfig != null && this.subscriptionProtocolVersions.isEmpty())
			throw new IllegalStateException(
					"MCP subscription configuration requires an enabled protocol revision" + versionContext(this.protocolVersions));

		Set<String> toolNames = new LinkedHashSet<>();
		for (McpToolRegistration<?> tool : this.toolRegistrations) {
			requireSubset(tool.getProtocolVersions(), this.protocolVersions,
					"tool " + tool.getName());
			if (containsLegacyVersion(tool.getProtocolVersions())
					&& tool.getOutputSchema().isPresent()
					&& !McpJsonString.fromValue("object").equals(tool.getOutputSchema().orElseThrow()
							.getDocument().getMembers().get("type")))
				throw new IllegalStateException("MCP tool '" + tool.getName()
						+ "' output schema must have object type for the 2025 adapter"
						+ versionContext(tool.getProtocolVersions()));
			if (containsLegacyVersion(tool.getProtocolVersions())
					&& (!tool.getInputRequestDeclarations().isEmpty()
						|| tool.getRequestStateMode() != McpRequestStateMode.NONE
						|| !tool.getMirroredHeaderPlan().declarations().isEmpty()))
				throw new IllegalStateException(
						"The 2025 tools adapter cannot serve input requests, request state, or mirrored headers for tool "
								+ tool.getName() + versionContext(tool.getProtocolVersions()));
			tool.getAppToolMetadata().ifPresent(metadata -> {
				if (containsLegacyVersion(metadata.getProtocolVersions()))
					throw new IllegalStateException(
							"MCP Apps metadata for tool '" + tool.getName() + "' is not implemented by the 2025 adapter"
									+ versionContext(metadata.getProtocolVersions()));
				if (!metadata.getVisibility().contains(McpAppToolMetadata.Visibility.MODEL)
						&& containsLegacyVersion(tool.getProtocolVersions()))
					throw new IllegalStateException(
							"App-only tools cannot be exposed by the 2025 tools-only adapter: " + tool.getName()
									+ versionContext(tool.getProtocolVersions()));
			});
			if (tool.isTaskRequired())
				requireSubset(tool.getProtocolVersions(), this.taskProtocolVersions,
						"task-required tool " + tool.getName());
			if (!toolNames.add(tool.getName()))
				throw new IllegalStateException(
						"Duplicate MCP tool name: " + tool.getName());
		}
		Set<String> promptNames = new LinkedHashSet<>();
		for (McpPromptRegistration prompt : this.promptRegistrations) {
			requireSubset(prompt.getProtocolVersions(), this.protocolVersions,
					"prompt " + prompt.getName());
			if (containsLegacyVersion(prompt.getProtocolVersions())
					&& (!prompt.getInputRequestDeclarations().isEmpty()
						|| prompt.getRequestStateMode() != McpRequestStateMode.NONE))
				throw new IllegalStateException(
						"The 2025 prompt adapter cannot serve input requests or request state for prompt "
								+ prompt.getName() + versionContext(prompt.getProtocolVersions()));
			if (!promptNames.add(prompt.getName()))
				throw new IllegalStateException(
						"Duplicate MCP prompt name: " + prompt.getName());
		}
		Map<URI, McpResourceRegistration> exactResources = new LinkedHashMap<>();
		Set<String> resourceUriTemplates = new LinkedHashSet<>();
		for (McpResourceRegistration resource : this.resourceRegistrations) {
			requireSubset(resource.getProtocolVersions(), this.protocolVersions,
					"resource " + resource.getName());
			if (containsLegacyVersion(resource.getProtocolVersions())) {
				if (!resource.getInputRequestDeclarations().isEmpty()
						|| resource.getRequestStateMode() != McpRequestStateMode.NONE)
					throw new IllegalStateException(
							"The 2025 resource adapter cannot serve input requests or request state for resource "
									+ resource.getName() + versionContext(resource.getProtocolVersions()));
				if (hasAppsMimeType(resource)
						|| resource.getMetadata().getMembers().containsKey("ui"))
					throw new IllegalStateException(
							"MCP Apps resources are not implemented by the 2025 adapter: " + resource.getName()
									+ versionContext(resource.getProtocolVersions()));
			}
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
		for (McpSkillRegistration skill : this.skillRegistrations)
			requireSkillVersions(skill);
		for (McpSkillGroup group : this.skillGroups)
			for (McpSkillRegistration skill : group.getSkillRegistrations())
				requireSkillVersions(skill);
		requireModernOnly(this.skillListHandlerProtocolVersions,
				"Skills-list handlers");
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
				tool.getAppToolMetadata().ifPresent(metadata -> requireSubset(
						metadata.getProtocolVersions(), resource.getProtocolVersions(),
						"Apps UI resource for tool " + tool.getName()));
			}
		}
		McpSkillEndpointIndex.preflight(this);
	}

	private void requireSkillVersions(@NonNull McpSkillRegistration skill) {
		requireSubset(skill.getProtocolVersions(), this.protocolVersions,
				"Skill " + skill.getSkillBundle().getName());
		if (!Set.of(McpProtocolVersion.V2026_07_28).containsAll(
				skill.getProtocolVersions()))
			throw new IllegalStateException(
					"Skills currently require MCP 2026-07-28: " + skill.getSkillBundle().getName()
							+ versionContext(skill.getProtocolVersions()));
	}

	private void requireModernOnly(
			@NonNull Set<@NonNull McpProtocolVersion> protocolVersions,
			@NonNull String feature) {
		if (containsLegacyVersion(protocolVersions))
			throw new IllegalStateException("The 2025 adapter does not implement MCP "
					+ feature + versionContext(protocolVersions));
	}

	private static boolean containsLegacyVersion(
			@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
		return protocolVersions.stream().anyMatch(version ->
				version != McpProtocolVersion.V2026_07_28);
	}

	private void requireSubset(
			@NonNull Set<@NonNull McpProtocolVersion> selected,
			@NonNull Set<@NonNull McpProtocolVersion> owner,
			@NonNull String feature) {
		if (!owner.containsAll(selected))
			throw new IllegalStateException("MCP " + feature
					+ " revisions must be a subset of their owner revisions"
					+ versionContext(selected.stream().filter(version -> !owner.contains(version))
							.collect(java.util.stream.Collectors.toSet())));
	}

	private String versionContext(Set<McpProtocolVersion> versions) {
		return " (endpoint " + this.path + "; revisions " + versions.stream()
				.map(McpProtocolVersion::getWireValue).sorted().collect(java.util.stream.Collectors.joining(", ")) + ").";
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
		this.protocolVersions = endpoint.protocolVersions;
		this.taskProtocolVersions = endpoint.taskProtocolVersions;
		this.sessionProtocolVersions = endpoint.sessionProtocolVersions;
		this.subscriptionProtocolVersions = endpoint.subscriptionProtocolVersions;
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
		this.skillListHandlerProtocolVersions = endpoint.skillListHandlerProtocolVersions;
		this.skillListCachePolicy = endpoint.skillListCachePolicy;
		this.resourceListHandler = endpoint.resourceListHandler;
		this.resourceListHandlerProtocolVersions = endpoint.resourceListHandlerProtocolVersions;
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

	/** @return exact protocol revisions served at this endpoint */
	@NonNull
	public Set<@NonNull McpProtocolVersion> getProtocolVersions() {
		return this.protocolVersions;
	}

	/** @return exact revisions that enable Tasks on this endpoint */
	@NonNull
	public Set<@NonNull McpProtocolVersion> getTaskProtocolVersions() {
		return this.taskProtocolVersions;
	}

	/**
	 * Returns the exact session-enabled subset of this endpoint's revisions.
	 * Only MCP {@code 2025-06-18} and {@code 2025-11-25} are eligible;
	 * Soklet's {@code 2026-07-28} implementation does not use sessions.
	 *
	 * @return immutable session revisions, or an empty set when disabled
	 */
	@NonNull
	public Set<@NonNull McpProtocolVersion> getSessionProtocolVersions() {
		return this.sessionProtocolVersions;
	}

	/**
	 * Returns exact subscription-enabled revisions. Modern {@code 2026-07-28}
	 * uses {@code subscriptions/listen}; {@code 2025-06-18} and
	 * {@code 2025-11-25} use session GET delivery and must also enable sessions.
	 * Legacy delivery additionally requires the server's HTTP admission
	 * controller and an effective event-source family set.
	 *
	 * @return immutable exact subscription-enabled revisions
	 */
	@NonNull
	public Set<@NonNull McpProtocolVersion> getSubscriptionProtocolVersions() {
		return this.subscriptionProtocolVersions;
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
	 * Skills manifests and files are served only on their declared protocol revisions.
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

	/** @return revisions on which the custom Skills-list handler applies */
	@NonNull
	public Set<@NonNull McpProtocolVersion> getSkillListHandlerProtocolVersions() {
		return this.skillListHandlerProtocolVersions;
	}

	/** @return fixed Skills-list cache scope and default time to live */
	@NonNull
	public McpCachePolicy getSkillListCachePolicy() { return this.skillListCachePolicy; }

	/**
	 * Returns the optional sole custom {@code resources/list} handler.
	 * <p>
	 * When enabled for the selected revision, the returned handler is
	 * authoritative; Soklet does not merge exact registrations into its pages.
	 * Otherwise the endpoint uses the static fallback: a single page on
	 * {@code 2026-07-28}, or bounded framework pages on {@code 2025-06-18} and
	 * {@code 2025-11-25}.
	 *
	 * @return custom resource-list handler, or empty for the static fallback
	 */
	@NonNull
	public Optional<@NonNull McpResourceListHandler> getResourceListHandler() {
		return Optional.ofNullable(this.resourceListHandler);
	}

	/** @return revisions on which the custom resource-list handler applies */
	@NonNull
	public Set<@NonNull McpProtocolVersion> getResourceListHandlerProtocolVersions() {
		return this.resourceListHandlerProtocolVersions;
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
		if (this.subscriptionProtocolVersions.isEmpty())
			throw new IllegalStateException(
					"MCP subscription configuration requires an enabled protocol revision.");
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
		for (McpSkillRegistration skill : snapshot)
			endpoint.requireSkillVersions(skill);
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
		for (McpSkillGroup group : snapshot)
			for (McpSkillRegistration skill : group.getSkillRegistrations())
				endpoint.requireSkillVersions(skill);
		McpSkillEndpointIndex.preflight(endpoint);
		return endpoint;
	}

	@NonNull
	static String normalizePath(@NonNull String path) {
		return McpEndpointPathLimit.requireValidWirePath(path);
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
		private final Set<@NonNull McpProtocolVersion> protocolVersions;
		@NonNull
		private Set<@NonNull McpProtocolVersion> taskProtocolVersions = Set.of();
		@NonNull
		private Set<@NonNull McpProtocolVersion> sessionProtocolVersions = Set.of();
		@NonNull
		private Set<@NonNull McpProtocolVersion> subscriptionProtocolVersions = Set.of();
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
		private Set<@NonNull McpProtocolVersion> skillListHandlerProtocolVersions = Set.of();
		@NonNull
		private McpCachePolicy skillListCachePolicy;
		@Nullable
		private McpResourceListHandler resourceListHandler;
		@NonNull
		private Set<@NonNull McpProtocolVersion> resourceListHandlerProtocolVersions = Set.of();
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
				@NonNull McpImplementation implementation,
				@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
			this.path = requireNonNull(path);
			this.protocolVersions = requireNonNull(protocolVersions);
			this.serverInformation = requireServerInformation(implementation);
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
		 * Enables Tasks for an explicit subset of endpoint revisions. An empty set
		 * disables Tasks. The task manager remains a server dependency.
		 *
		 * @param protocolVersions exact task-enabled revisions
		 * @return this builder
		 */
		@NonNull
		public Builder taskProtocolVersions(
				@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
			this.taskProtocolVersions = McpProtocolVersion.optionalSet(protocolVersions);
			return this;
		}

		/**
		 * Enables sessions for an explicit subset of served endpoint revisions.
		 * Only {@code 2025-06-18} and {@code 2025-11-25} are eligible; an empty
		 * set disables sessions. {@code 2026-07-28} does not use sessions.
		 * The server must configure {@link McpSessionConfig}. A session-enabled
		 * revision requires a session ID after initialization; it never silently
		 * falls back to stateless operation. The supplied set is copied.
		 *
		 * @param protocolVersions exact session-enabled revisions
		 * @return this builder
		 * @throws NullPointerException if the set or an item is null
		 */
		@NonNull
		public Builder sessionProtocolVersions(
				@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
			this.sessionProtocolVersions = McpProtocolVersion.optionalSet(protocolVersions);
			return this;
		}

		/**
		 * Enables subscriptions for an explicit subset of endpoint revisions.
		 * Modern {@code 2026-07-28} uses {@code subscriptions/listen}; the two
		 * supported 2025 revisions require matching session revisions, server HTTP
		 * admission and effective event sources for GET delivery. An empty set
		 * disables subscriptions. URI updates require an explicit URI authorizer;
		 * legacy catalog-only delivery does not.
		 *
		 * @param protocolVersions exact subscription-enabled revisions
		 * @return this builder
		 */
		@NonNull
		public Builder subscriptionProtocolVersions(
				@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
			this.subscriptionProtocolVersions = McpProtocolVersion.optionalSet(protocolVersions);
			return this;
		}

		/**
		 * Sets the required implementation information advertised by this endpoint.
		 *
		 * @param implementation the server implementation information
		 * @return this builder
		 * @throws IllegalArgumentException if the server name or version is blank
		 */
		@NonNull
		public Builder serverInfo(
				@NonNull McpImplementation implementation) {
			this.serverInformation = requireServerInformation(implementation);
			return this;
		}

		@NonNull
		private static McpImplementation requireServerInformation(
				@NonNull McpImplementation implementation) {
			requireNonNull(implementation);
			if (implementation.getName().isBlank() || implementation.getVersion().isBlank())
				throw new IllegalArgumentException(
						"MCP server implementation name and version must not be blank.");
			return implementation;
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
		 * @param protocolVersions nonempty handler revisions, or empty when clearing
		 * @return this builder
		 */
		@NonNull
		public Builder skillListHandler(@Nullable McpSkillListHandler skillListHandler,
				@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
			Set<McpProtocolVersion> versions = skillListHandler == null
					? McpProtocolVersion.optionalSet(protocolVersions)
					: McpProtocolVersion.requiredSet(protocolVersions);
			if (skillListHandler == null && !versions.isEmpty())
				throw new IllegalArgumentException(
						"A null MCP Skills-list handler requires an empty version set.");
			this.skillListHandler = skillListHandler;
			this.skillListHandlerProtocolVersions = versions;
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
		 * status {@code 500}. Null selects the static fallback: a single page on
		 * {@code 2026-07-28}, or bounded framework pages on {@code 2025-06-18} and
		 * {@code 2025-11-25}.
		 * Sequential calls are last-call-wins.
		 *
		 * @param resourceListHandler custom list handler, or null for the static
		 *                            fallback
		 * @param protocolVersions nonempty handler revisions, or empty when clearing
		 * @return this builder
		 */
		@NonNull
		public Builder resourceListHandler(
				@Nullable McpResourceListHandler resourceListHandler,
				@NonNull Set<@NonNull McpProtocolVersion> protocolVersions) {
			Set<McpProtocolVersion> versions = resourceListHandler == null
					? McpProtocolVersion.optionalSet(protocolVersions)
					: McpProtocolVersion.requiredSet(protocolVersions);
			if (resourceListHandler == null && !versions.isEmpty())
				throw new IllegalArgumentException(
						"A null MCP resource-list handler requires an empty version set.");
			this.resourceListHandler = resourceListHandler;
			this.resourceListHandlerProtocolVersions = versions;
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
