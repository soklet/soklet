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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Provisional normalized endpoint snapshot shared by discovery and later dispatch.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpNormalizedEndpoint {
	@NonNull
	private final McpImplementationMetadata serverInformation;
	@NonNull
	private final Optional<@NonNull String> instructions;
	@NonNull
	private final McpDiscoveryCachePolicy discoveryCachePolicy;
	private final boolean includeServerInformation;
	@NonNull
	private final McpJsonObject discoveryMetadata;
	@NonNull
	private final List<@NonNull McpNormalizedOperation> tools;
	@NonNull
	private final List<@NonNull McpNormalizedOperation> prompts;
	@NonNull
	private final List<@NonNull McpNormalizedOperation> exactResources;
	@NonNull
	private final List<@NonNull McpNormalizedOperation> resourceTemplates;
	private final boolean customResourceListHandler;
	@NonNull
	private final Optional<@NonNull McpNormalizedSubscriptionConfiguration> subscriptions;

	@NonNull
	static Builder withServerInformation(@NonNull McpImplementationMetadata serverInformation) {
		return new Builder(serverInformation);
	}

	private McpNormalizedEndpoint(@NonNull Builder builder) {
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
		validateToolDescriptors(this.prompts, this.exactResources,
				this.resourceTemplates);
		validateToolOnlyMirroredHeaders(this.prompts, this.exactResources,
				this.resourceTemplates);
		validateDistinctResources(this.exactResources, this.resourceTemplates);
		this.customResourceListHandler = builder.customResourceListHandler;
		this.subscriptions = builder.subscriptions;

		if (this.subscriptions.isPresent() && !hasResourceSurface())
			throw new IllegalStateException(
					"Resource subscriptions require an exact resource, template, or custom list handler.");
	}

	@NonNull
	McpImplementationMetadata serverInformation() {
		return serverInformation;
	}

	@NonNull
	Optional<@NonNull String> instructions() {
		return instructions;
	}

	@NonNull
	McpDiscoveryCachePolicy discoveryCachePolicy() {
		return discoveryCachePolicy;
	}

	boolean includeServerInformation() {
		return includeServerInformation;
	}

	@NonNull
	McpJsonObject discoveryMetadata() {
		return discoveryMetadata;
	}

	@NonNull
	List<@NonNull McpNormalizedOperation> tools() {
		return tools;
	}

	@NonNull
	List<@NonNull McpNormalizedOperation> prompts() {
		return prompts;
	}

	@NonNull
	List<@NonNull McpNormalizedOperation> exactResources() {
		return exactResources;
	}

	@NonNull
	List<@NonNull McpNormalizedOperation> resourceTemplates() {
		return resourceTemplates;
	}

	boolean customResourceListHandler() {
		return customResourceListHandler;
	}

	@NonNull
	Optional<@NonNull McpNormalizedSubscriptionConfiguration> subscriptions() {
		return subscriptions;
	}

	boolean hasResourceSurface() {
		return customResourceListHandler || !exactResources.isEmpty() || !resourceTemplates.isEmpty();
	}

	@NonNull
	private static List<@NonNull McpNormalizedOperation> immutableOperations(
			@NonNull List<@NonNull McpNormalizedOperation> operations,
			@NonNull String description) {
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

	private static void validateDistinctResources(
			@NonNull List<@NonNull McpNormalizedOperation> exactResources,
			@NonNull List<@NonNull McpNormalizedOperation> resourceTemplates) {
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
	private static void validateToolDescriptors(
			@NonNull List<@NonNull McpNormalizedOperation>... operationGroups) {
		for (List<McpNormalizedOperation> operations : operationGroups) {
			for (McpNormalizedOperation operation : operations) {
				if (operation.toolDescriptor().isPresent())
					throw new IllegalArgumentException(
							"Tool descriptors are supported only for tools.");
			}
		}
	}

	@SafeVarargs
	private static void validateToolOnlyMirroredHeaders(
			@NonNull List<@NonNull McpNormalizedOperation>... operationGroups) {
		for (List<McpNormalizedOperation> operations : operationGroups) {
			for (McpNormalizedOperation operation : operations) {
				if (!operation.mirroredHeaderPlan().declarations().isEmpty())
					throw new IllegalArgumentException(
							"Custom mirrored headers are supported only for tools.");
			}
		}
	}

	@NotThreadSafe
	static final class Builder {
		@NonNull
		private final McpImplementationMetadata serverInformation;
		@NonNull
		private Optional<@NonNull String> instructions;
		@NonNull
		private McpDiscoveryCachePolicy discoveryCachePolicy;
		private boolean includeServerInformation;
		@NonNull
		private McpJsonObject discoveryMetadata;
		@NonNull
		private final List<@NonNull McpNormalizedOperation> tools;
		@NonNull
		private final List<@NonNull McpNormalizedOperation> prompts;
		@NonNull
		private final List<@NonNull McpNormalizedOperation> exactResources;
		@NonNull
		private final List<@NonNull McpNormalizedOperation> resourceTemplates;
		private boolean customResourceListHandler;
		@NonNull
		private Optional<@NonNull McpNormalizedSubscriptionConfiguration> subscriptions;

		private Builder(@NonNull McpImplementationMetadata serverInformation) {
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

		@NonNull
		Builder instructions(@NonNull String instructions) {
			this.instructions = Optional.of(
					McpProtocolSupport.requireNonBlank(instructions, "Endpoint instructions"));
			return this;
		}

		@NonNull
		Builder discoveryCachePolicy(@NonNull McpDiscoveryCachePolicy discoveryCachePolicy) {
			this.discoveryCachePolicy = requireNonNull(discoveryCachePolicy);
			return this;
		}

		@NonNull
		Builder includeServerInformation(boolean includeServerInformation) {
			this.includeServerInformation = includeServerInformation;
			return this;
		}

		@NonNull
		Builder discoveryMetadata(@NonNull McpJsonObject discoveryMetadata) {
			this.discoveryMetadata = McpProtocolSupport.requireApplicationMetadataFields(
					discoveryMetadata, Set.of(McpResultMetadata.SERVER_INFORMATION_KEY));
			return this;
		}

		@NonNull
		Builder tool(@NonNull McpNormalizedOperation tool) {
			tools.add(requireNonNull(tool));
			return this;
		}

		@NonNull
		Builder prompt(@NonNull McpNormalizedOperation prompt) {
			prompts.add(requireNonNull(prompt));
			return this;
		}

		@NonNull
		Builder exactResource(@NonNull String uri) {
			return exactResource(McpNormalizedOperation.named(uri));
		}

		@NonNull
		Builder exactResource(@NonNull McpNormalizedOperation resource) {
			exactResources.add(requireNonNull(resource));
			return this;
		}

		@NonNull
		Builder resourceTemplate(@NonNull String uriTemplate) {
			return resourceTemplate(McpNormalizedOperation.named(uriTemplate));
		}

		@NonNull
		Builder resourceTemplate(@NonNull McpNormalizedOperation resourceTemplate) {
			resourceTemplates.add(requireNonNull(resourceTemplate));
			return this;
		}

		@NonNull
		Builder customResourceListHandler() {
			customResourceListHandler = true;
			return this;
		}

		@NonNull
		Builder subscriptions(
				@NonNull McpNormalizedSubscriptionConfiguration subscriptions) {
			this.subscriptions = Optional.of(requireNonNull(subscriptions));
			return this;
		}

		@NonNull
		McpNormalizedEndpoint build() {
			return new McpNormalizedEndpoint(this);
		}
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpNormalizedOperation(@NonNull String name,
		@NonNull McpInputRequestPlan inputRequestPlan,
		@NonNull McpMirroredHeaderPlan mirroredHeaderPlan,
		@NonNull Optional<@NonNull McpNormalizedToolDescriptor> toolDescriptor) {
	McpNormalizedOperation(@NonNull String name,
			@NonNull McpInputRequestPlan inputRequestPlan) {
		this(name, inputRequestPlan, McpMirroredHeaderPlan.empty(), Optional.empty());
	}

	McpNormalizedOperation(@NonNull String name,
			@NonNull McpInputRequestPlan inputRequestPlan,
			@NonNull McpMirroredHeaderPlan mirroredHeaderPlan) {
		this(name, inputRequestPlan, mirroredHeaderPlan, Optional.empty());
	}

	McpNormalizedOperation {
		name = McpProtocolSupport.requireNonBlank(name, "Operation name");
		requireNonNull(inputRequestPlan);
		requireNonNull(mirroredHeaderPlan);
		requireNonNull(toolDescriptor);
		if (toolDescriptor.isPresent()
				&& !name.equals(toolDescriptor.orElseThrow().name()))
			throw new IllegalArgumentException(
					"Tool operation and descriptor names must match.");
	}

	@NonNull
	static McpNormalizedOperation tool(
			@NonNull McpNormalizedToolDescriptor descriptor,
			@NonNull McpMirroredHeaderPlan mirroredHeaderPlan) {
		requireNonNull(descriptor);
		return new McpNormalizedOperation(descriptor.name(),
				McpInputRequestPlan.empty(), requireNonNull(mirroredHeaderPlan),
				Optional.of(descriptor));
	}

	@NonNull
	static McpNormalizedOperation named(@NonNull String name) {
		return new McpNormalizedOperation(name, McpInputRequestPlan.empty(),
				McpMirroredHeaderPlan.empty(), Optional.empty());
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpDiscoveryCachePolicy(long timeToLiveMilliseconds,
		@NonNull McpCacheScope scope) {
	McpDiscoveryCachePolicy {
		if (timeToLiveMilliseconds < 0L)
			throw new IllegalArgumentException("Discovery cache TTL must be >= 0.");

		requireNonNull(scope);
	}

	@NonNull
	static McpDiscoveryCachePolicy privateNoCache() {
		return new McpDiscoveryCachePolicy(0L, McpCacheScope.PRIVATE);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpCacheScope {
	PRIVATE("private"),
	PUBLIC("public");

	@NonNull
	private final String wireValue;

	McpCacheScope(@NonNull String wireValue) {
		this.wireValue = wireValue;
	}

	@NonNull
	String wireValue() {
		return wireValue;
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpResourceNotificationType {
	RESOURCES_LIST_CHANGED,
	RESOURCE_UPDATED
}

/**
 * Post-validation subscription snapshot. Its presence proves that an endpoint
 * has an attached publisher-backed subscription configuration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpNormalizedSubscriptionConfiguration(
		@NonNull Set<@NonNull McpResourceNotificationType> notificationTypes) {
	McpNormalizedSubscriptionConfiguration {
		requireNonNull(notificationTypes);

		if (notificationTypes.isEmpty())
			throw new IllegalArgumentException(
					"At least one resource notification type is required.");

		notificationTypes = Collections.unmodifiableSet(
				EnumSet.copyOf(notificationTypes));
	}

	@NonNull
	static McpNormalizedSubscriptionConfiguration supporting(
			@NonNull McpResourceNotificationType first,
			@NonNull McpResourceNotificationType... remaining) {
		requireNonNull(first);
		requireNonNull(remaining);
		Set<McpResourceNotificationType> notificationTypes = EnumSet.of(first);

		for (McpResourceNotificationType notificationType : remaining)
			notificationTypes.add(requireNonNull(notificationType));

		return new McpNormalizedSubscriptionConfiguration(notificationTypes);
	}
}
