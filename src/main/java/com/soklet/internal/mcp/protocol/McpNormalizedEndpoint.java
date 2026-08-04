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

package com.soklet.internal.mcp.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Provisional normalized endpoint snapshot shared by discovery and later dispatch.
 */
final class McpNormalizedEndpoint {
	private final McpImplementationMetadata serverInformation;
	private final Optional<String> instructions;
	private final McpDiscoveryCachePolicy discoveryCachePolicy;
	private final boolean includeServerInformation;
	private final McpJsonObject discoveryMetadata;
	private final List<McpNormalizedOperation> tools;
	private final List<McpNormalizedOperation> prompts;
	private final List<McpNormalizedOperation> exactResources;
	private final List<McpNormalizedOperation> resourceTemplates;
	private final boolean customResourceListHandler;
	private final Optional<McpNormalizedSubscriptionConfiguration> subscriptions;

	static Builder withServerInformation(McpImplementationMetadata serverInformation) {
		return new Builder(serverInformation);
	}

	private McpNormalizedEndpoint(Builder builder) {
		this.serverInformation = builder.serverInformation;
		this.instructions = builder.instructions;
		this.discoveryCachePolicy = builder.discoveryCachePolicy;
		this.includeServerInformation = builder.includeServerInformation;
		this.discoveryMetadata = builder.discoveryMetadata;
		this.tools = immutableOperations(builder.tools, "tool");
		this.prompts = immutableOperations(builder.prompts, "prompt");
		this.exactResources = immutableOperations(builder.exactResources, "exact resource URI");
		this.resourceTemplates = immutableOperations(
				builder.resourceTemplates, "resource URI template");
		validateToolOnlyMirroredHeaders(this.prompts, this.exactResources,
				this.resourceTemplates);
		validateDistinctResources(this.exactResources, this.resourceTemplates);
		this.customResourceListHandler = builder.customResourceListHandler;
		this.subscriptions = builder.subscriptions;

		if (this.subscriptions.isPresent() && !hasResourceSurface())
			throw new IllegalStateException(
					"Resource subscriptions require an exact resource, template, or custom list handler.");
	}

	McpImplementationMetadata serverInformation() {
		return serverInformation;
	}

	Optional<String> instructions() {
		return instructions;
	}

	McpDiscoveryCachePolicy discoveryCachePolicy() {
		return discoveryCachePolicy;
	}

	boolean includeServerInformation() {
		return includeServerInformation;
	}

	McpJsonObject discoveryMetadata() {
		return discoveryMetadata;
	}

	List<McpNormalizedOperation> tools() {
		return tools;
	}

	List<McpNormalizedOperation> prompts() {
		return prompts;
	}

	List<McpNormalizedOperation> exactResources() {
		return exactResources;
	}

	List<McpNormalizedOperation> resourceTemplates() {
		return resourceTemplates;
	}

	boolean customResourceListHandler() {
		return customResourceListHandler;
	}

	Optional<McpNormalizedSubscriptionConfiguration> subscriptions() {
		return subscriptions;
	}

	boolean hasResourceSurface() {
		return customResourceListHandler || !exactResources.isEmpty() || !resourceTemplates.isEmpty();
	}

	private static List<McpNormalizedOperation> immutableOperations(
			List<McpNormalizedOperation> operations, String description) {
		requireNonNull(operations);
		List<McpNormalizedOperation> copiedOperations = List.copyOf(operations);
		Set<String> names = new java.util.LinkedHashSet<>();

		for (McpNormalizedOperation operation : copiedOperations) {
			requireNonNull(operation);

			if (!names.add(operation.name()))
				throw new IllegalArgumentException(
						"Duplicate " + description + " '" + operation.name() + "'.");
		}

		return copiedOperations;
	}

	private static void validateDistinctResources(List<McpNormalizedOperation> exactResources,
			List<McpNormalizedOperation> resourceTemplates) {
		Set<String> resourceIdentities = new java.util.LinkedHashSet<>();

		for (McpNormalizedOperation resource : exactResources)
			resourceIdentities.add(resource.name());

		for (McpNormalizedOperation resourceTemplate : resourceTemplates) {
			if (!resourceIdentities.add(resourceTemplate.name()))
				throw new IllegalArgumentException(
						"Duplicate resource identity '" + resourceTemplate.name() + "'.");
		}
	}

	@SafeVarargs
	private static void validateToolOnlyMirroredHeaders(
			List<McpNormalizedOperation>... operationGroups) {
		for (List<McpNormalizedOperation> operations : operationGroups) {
			for (McpNormalizedOperation operation : operations) {
				if (!operation.mirroredHeaderPlan().declarations().isEmpty())
					throw new IllegalArgumentException(
							"Custom mirrored headers are supported only for tools.");
			}
		}
	}

	static final class Builder {
		private final McpImplementationMetadata serverInformation;
		private Optional<String> instructions;
		private McpDiscoveryCachePolicy discoveryCachePolicy;
		private boolean includeServerInformation;
		private McpJsonObject discoveryMetadata;
		private final List<McpNormalizedOperation> tools;
		private final List<McpNormalizedOperation> prompts;
		private final List<McpNormalizedOperation> exactResources;
		private final List<McpNormalizedOperation> resourceTemplates;
		private boolean customResourceListHandler;
		private Optional<McpNormalizedSubscriptionConfiguration> subscriptions;

		private Builder(McpImplementationMetadata serverInformation) {
			this.serverInformation = requireNonNull(serverInformation);
			this.instructions = Optional.empty();
			this.discoveryCachePolicy = McpDiscoveryCachePolicy.privateNoCache();
			this.includeServerInformation = true;
			this.discoveryMetadata = McpJsonObject.empty();
			this.tools = new ArrayList<>();
			this.prompts = new ArrayList<>();
			this.exactResources = new ArrayList<>();
			this.resourceTemplates = new ArrayList<>();
			this.subscriptions = Optional.empty();
		}

		Builder instructions(String instructions) {
			this.instructions = Optional.of(
					McpProtocolSupport.requireNonBlank(instructions, "Endpoint instructions"));
			return this;
		}

		Builder discoveryCachePolicy(McpDiscoveryCachePolicy discoveryCachePolicy) {
			this.discoveryCachePolicy = requireNonNull(discoveryCachePolicy);
			return this;
		}

		Builder includeServerInformation(boolean includeServerInformation) {
			this.includeServerInformation = includeServerInformation;
			return this;
		}

		Builder discoveryMetadata(McpJsonObject discoveryMetadata) {
			this.discoveryMetadata = McpProtocolSupport.requireApplicationMetadataFields(
					discoveryMetadata, Set.of(McpResultMetadata.SERVER_INFORMATION_KEY));
			return this;
		}

		Builder tool(McpNormalizedOperation tool) {
			tools.add(requireNonNull(tool));
			return this;
		}

		Builder prompt(McpNormalizedOperation prompt) {
			prompts.add(requireNonNull(prompt));
			return this;
		}

		Builder exactResource(String uri) {
			return exactResource(McpNormalizedOperation.named(uri));
		}

		Builder exactResource(McpNormalizedOperation resource) {
			exactResources.add(requireNonNull(resource));
			return this;
		}

		Builder resourceTemplate(String uriTemplate) {
			return resourceTemplate(McpNormalizedOperation.named(uriTemplate));
		}

		Builder resourceTemplate(McpNormalizedOperation resourceTemplate) {
			resourceTemplates.add(requireNonNull(resourceTemplate));
			return this;
		}

		Builder customResourceListHandler() {
			customResourceListHandler = true;
			return this;
		}

		Builder subscriptions(McpNormalizedSubscriptionConfiguration subscriptions) {
			this.subscriptions = Optional.of(requireNonNull(subscriptions));
			return this;
		}

		McpNormalizedEndpoint build() {
			return new McpNormalizedEndpoint(this);
		}
	}
}

record McpNormalizedOperation(String name, McpInputRequestPlan inputRequestPlan,
		McpMirroredHeaderPlan mirroredHeaderPlan) {
	McpNormalizedOperation(String name, McpInputRequestPlan inputRequestPlan) {
		this(name, inputRequestPlan, McpMirroredHeaderPlan.empty());
	}

	McpNormalizedOperation {
		name = McpProtocolSupport.requireNonBlank(name, "Operation name");
		requireNonNull(inputRequestPlan);
		requireNonNull(mirroredHeaderPlan);
	}

	static McpNormalizedOperation named(String name) {
		return new McpNormalizedOperation(name, McpInputRequestPlan.empty(),
				McpMirroredHeaderPlan.empty());
	}
}

record McpDiscoveryCachePolicy(long timeToLiveMilliseconds, McpCacheScope scope) {
	McpDiscoveryCachePolicy {
		if (timeToLiveMilliseconds < 0L)
			throw new IllegalArgumentException("Discovery cache TTL must be >= 0.");

		requireNonNull(scope);
	}

	static McpDiscoveryCachePolicy privateNoCache() {
		return new McpDiscoveryCachePolicy(0L, McpCacheScope.PRIVATE);
	}
}

enum McpCacheScope {
	PRIVATE("private"),
	PUBLIC("public");

	private final String wireValue;

	McpCacheScope(String wireValue) {
		this.wireValue = wireValue;
	}

	String wireValue() {
		return wireValue;
	}
}

enum McpResourceNotificationType {
	RESOURCES_LIST_CHANGED,
	RESOURCE_UPDATED
}

/**
 * Post-validation subscription snapshot. Its presence proves that an endpoint
 * has an attached publisher-backed subscription configuration.
 */
record McpNormalizedSubscriptionConfiguration(
		Set<McpResourceNotificationType> notificationTypes) {
	McpNormalizedSubscriptionConfiguration {
		requireNonNull(notificationTypes);

		if (notificationTypes.isEmpty())
			throw new IllegalArgumentException(
					"At least one resource notification type is required.");

		notificationTypes = Collections.unmodifiableSet(
				EnumSet.copyOf(notificationTypes));
	}

	static McpNormalizedSubscriptionConfiguration supporting(
			McpResourceNotificationType first,
			McpResourceNotificationType... remaining) {
		requireNonNull(first);
		requireNonNull(remaining);
		Set<McpResourceNotificationType> notificationTypes = EnumSet.of(first);

		for (McpResourceNotificationType notificationType : remaining)
			notificationTypes.add(requireNonNull(notificationType));

		return new McpNormalizedSubscriptionConfiguration(notificationTypes);
	}
}
