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
import java.net.URI;
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
	private final McpResourceCachePolicy resourceListCachePolicy;
	@NonNull
	private final McpResourceCachePolicy resourceTemplateListCachePolicy;
	private final int maximumCursorSizeInBytes;
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
		McpLevelOneUriTemplate.requireResourceTemplateCount(
				builder.resourceTemplates.size());
		this.resourceTemplates = immutableOperations(
				builder.resourceTemplates, "resource URI template");
		validateToolDescriptors(this.prompts, this.exactResources,
				this.resourceTemplates);
		validatePromptDescriptors(this.tools, this.exactResources,
				this.resourceTemplates);
		validateResourceDescriptors(this.tools, this.prompts);
		validateToolOnlyMirroredHeaders(this.prompts, this.exactResources,
				this.resourceTemplates);
		validateDistinctResources(this.exactResources, this.resourceTemplates);
		this.customResourceListHandler = builder.customResourceListHandler;
		this.resourceListCachePolicy = builder.resourceListCachePolicy;
		this.resourceTemplateListCachePolicy = builder.resourceTemplateListCachePolicy;
		this.maximumCursorSizeInBytes = builder.maximumCursorSizeInBytes;
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
	McpResourceCachePolicy resourceListCachePolicy() {
		return resourceListCachePolicy;
	}

	@NonNull
	McpResourceCachePolicy resourceTemplateListCachePolicy() {
		return resourceTemplateListCachePolicy;
	}

	int maximumCursorSizeInBytes() {
		return maximumCursorSizeInBytes;
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
		Set<URI> exactResourceIdentities = new java.util.LinkedHashSet<>();
		Set<String> templateIdentities = new java.util.LinkedHashSet<>();

		for (McpNormalizedOperation resource : exactResources) {
			String uri = resource.resourceDescriptor().orElseThrow().uri();
			if (!exactResourceIdentities.add(URI.create(uri)))
				throw new IllegalArgumentException(
						"Duplicate exact resource URI '" + uri + "'.");
		}

		for (McpNormalizedOperation resourceTemplate : resourceTemplates) {
			String uriTemplate = resourceTemplate.resourceTemplateDescriptor()
					.orElseThrow().uriTemplate();
			if (!templateIdentities.add(uriTemplate))
				throw new IllegalArgumentException(
						"Duplicate resource identity '" + uriTemplate + "'.");
		}

		McpLevelOneUriTemplate.OverlapComparisonBudget overlapBudget =
				McpLevelOneUriTemplate.endpointOverlapComparisonBudget();
		for (int left = 0; left < resourceTemplates.size(); ++left) {
			McpNormalizedResourceTemplateDescriptor leftDescriptor = resourceTemplates
					.get(left).resourceTemplateDescriptor().orElseThrow();
			for (int right = left + 1; right < resourceTemplates.size(); ++right) {
				McpNormalizedResourceTemplateDescriptor rightDescriptor = resourceTemplates
						.get(right).resourceTemplateDescriptor().orElseThrow();
				if (leftDescriptor.parsedTemplate().potentiallyOverlaps(
						rightDescriptor.parsedTemplate(), overlapBudget))
					throw new IllegalArgumentException(
							"Potentially overlapping resource URI templates '"
									+ leftDescriptor.uriTemplate() + "' and '"
									+ rightDescriptor.uriTemplate() + "'.");
			}
		}
	}

	@SafeVarargs
	private static void validateResourceDescriptors(
			@NonNull List<@NonNull McpNormalizedOperation>... operationGroups) {
		for (List<McpNormalizedOperation> operations : operationGroups) {
			for (McpNormalizedOperation operation : operations) {
				if (operation.resourceDescriptor().isPresent()
						|| operation.resourceTemplateDescriptor().isPresent())
					throw new IllegalArgumentException(
							"Resource descriptors are supported only for resources.");
			}
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
	private static void validatePromptDescriptors(
			@NonNull List<@NonNull McpNormalizedOperation>... operationGroups) {
		for (List<McpNormalizedOperation> operations : operationGroups) {
			for (McpNormalizedOperation operation : operations) {
				if (operation.promptDescriptor().isPresent())
					throw new IllegalArgumentException(
							"Prompt descriptors are supported only for prompts.");
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
		private McpResourceCachePolicy resourceListCachePolicy;
		@NonNull
		private McpResourceCachePolicy resourceTemplateListCachePolicy;
		private int maximumCursorSizeInBytes;
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
			this.resourceListCachePolicy = McpResourceCachePolicy.privateNoCache();
			this.resourceTemplateListCachePolicy =
					McpResourceCachePolicy.privateNoCache();
			this.maximumCursorSizeInBytes = 4_096;
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
		Builder prompt(@NonNull McpNormalizedPromptDescriptor prompt) {
			return prompt(McpNormalizedOperation.prompt(prompt));
		}

		@NonNull
		Builder exactResource(@NonNull String uri) {
			return exactResource(McpNormalizedResourceDescriptor.minimal(uri));
		}

		@NonNull
		Builder exactResource(@NonNull McpNormalizedOperation resource) {
			requireResourceOperation(resource);
			exactResources.add(McpNormalizedOperation.resource(
					McpNormalizedResourceDescriptor.minimal(resource.name()),
					resource.inputRequestPlan()));
			return this;
		}

		@NonNull
		Builder exactResource(@NonNull McpNormalizedResourceDescriptor descriptor) {
			return exactResource(descriptor, McpInputRequestPlan.empty());
		}

		@NonNull
		Builder exactResource(@NonNull McpNormalizedResourceDescriptor descriptor,
				@NonNull McpInputRequestPlan inputRequestPlan) {
			exactResources.add(McpNormalizedOperation.resource(
					requireNonNull(descriptor), requireNonNull(inputRequestPlan)));
			return this;
		}

		@NonNull
		Builder resourceTemplate(@NonNull String uriTemplate) {
			return resourceTemplate(
					McpNormalizedResourceTemplateDescriptor.minimal(uriTemplate));
		}

		@NonNull
		Builder resourceTemplate(@NonNull McpNormalizedOperation resourceTemplate) {
			requireResourceOperation(resourceTemplate);
			resourceTemplates.add(McpNormalizedOperation.resourceTemplate(
					McpNormalizedResourceTemplateDescriptor.minimal(
							resourceTemplate.name()),
					resourceTemplate.inputRequestPlan()));
			return this;
		}

		@NonNull
		Builder resourceTemplate(
				@NonNull McpNormalizedResourceTemplateDescriptor descriptor) {
			return resourceTemplate(descriptor, McpInputRequestPlan.empty());
		}

		@NonNull
		Builder resourceTemplate(
				@NonNull McpNormalizedResourceTemplateDescriptor descriptor,
				@NonNull McpInputRequestPlan inputRequestPlan) {
			resourceTemplates.add(McpNormalizedOperation.resourceTemplate(
					requireNonNull(descriptor), requireNonNull(inputRequestPlan)));
			return this;
		}

		@NonNull
		Builder customResourceListHandler() {
			customResourceListHandler = true;
			return this;
		}

		@NonNull
		Builder resourceListCachePolicy(
				@NonNull McpResourceCachePolicy resourceListCachePolicy) {
			this.resourceListCachePolicy = requireNonNull(resourceListCachePolicy);
			return this;
		}

		@NonNull
		Builder resourceTemplateListCachePolicy(
				@NonNull McpResourceCachePolicy resourceTemplateListCachePolicy) {
			this.resourceTemplateListCachePolicy =
					requireNonNull(resourceTemplateListCachePolicy);
			return this;
		}

		@NonNull
		Builder maximumCursorSizeInBytes(int maximumCursorSizeInBytes) {
			if (maximumCursorSizeInBytes < 1)
				throw new IllegalArgumentException(
						"Maximum cursor size must be positive.");
			this.maximumCursorSizeInBytes = maximumCursorSizeInBytes;
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

		private static void requireResourceOperation(
				@NonNull McpNormalizedOperation operation) {
			requireNonNull(operation);
			if (operation.toolDescriptor().isPresent()
					|| operation.promptDescriptor().isPresent()
					|| operation.resourceDescriptor().isPresent()
					|| operation.resourceTemplateDescriptor().isPresent())
				throw new IllegalArgumentException(
						"A legacy resource operation must not carry a descriptor.");
			if (!operation.mirroredHeaderPlan().declarations().isEmpty())
				throw new IllegalArgumentException(
						"Custom mirrored headers are supported only for tools.");
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
		@NonNull Optional<@NonNull McpNormalizedToolDescriptor> toolDescriptor,
		@NonNull Optional<@NonNull McpNormalizedPromptDescriptor> promptDescriptor,
		@NonNull Optional<@NonNull McpNormalizedResourceDescriptor> resourceDescriptor,
		@NonNull Optional<@NonNull McpNormalizedResourceTemplateDescriptor>
				resourceTemplateDescriptor) {
	McpNormalizedOperation(@NonNull String name,
			@NonNull McpInputRequestPlan inputRequestPlan) {
		this(name, inputRequestPlan, McpMirroredHeaderPlan.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
	}

	McpNormalizedOperation(@NonNull String name,
			@NonNull McpInputRequestPlan inputRequestPlan,
			@NonNull McpMirroredHeaderPlan mirroredHeaderPlan) {
		this(name, inputRequestPlan, mirroredHeaderPlan,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
	}

	McpNormalizedOperation(@NonNull String name,
			@NonNull McpInputRequestPlan inputRequestPlan,
			@NonNull McpMirroredHeaderPlan mirroredHeaderPlan,
			@NonNull Optional<@NonNull McpNormalizedToolDescriptor> toolDescriptor) {
		this(name, inputRequestPlan, mirroredHeaderPlan, toolDescriptor,
				Optional.empty(), Optional.empty(), Optional.empty());
	}

	McpNormalizedOperation {
		name = McpProtocolSupport.requireNonBlank(name, "Operation name");
		requireNonNull(inputRequestPlan);
		requireNonNull(mirroredHeaderPlan);
		requireNonNull(toolDescriptor);
		requireNonNull(promptDescriptor);
		requireNonNull(resourceDescriptor);
		requireNonNull(resourceTemplateDescriptor);
		int descriptorCount = (toolDescriptor.isPresent() ? 1 : 0)
				+ (promptDescriptor.isPresent() ? 1 : 0)
				+ (resourceDescriptor.isPresent() ? 1 : 0)
				+ (resourceTemplateDescriptor.isPresent() ? 1 : 0);
		if (descriptorCount > 1)
			throw new IllegalArgumentException(
					"An operation cannot have more than one descriptor.");
		if (toolDescriptor.isPresent()
				&& !name.equals(toolDescriptor.orElseThrow().name()))
			throw new IllegalArgumentException(
					"Tool operation and descriptor names must match.");
		if (promptDescriptor.isPresent()
				&& !name.equals(promptDescriptor.orElseThrow().name()))
			throw new IllegalArgumentException(
					"Prompt operation and descriptor names must match.");
		if (resourceDescriptor.isPresent()
				&& !name.equals(resourceDescriptor.orElseThrow().uri()))
			throw new IllegalArgumentException(
					"Resource operation and descriptor URIs must match.");
		if (resourceTemplateDescriptor.isPresent()
				&& !name.equals(resourceTemplateDescriptor.orElseThrow().uriTemplate()))
			throw new IllegalArgumentException(
					"Resource-template operation and descriptor templates must match.");
	}

	@NonNull
	static McpNormalizedOperation tool(
			@NonNull McpNormalizedToolDescriptor descriptor,
			@NonNull McpMirroredHeaderPlan mirroredHeaderPlan) {
		return tool(descriptor, McpInputRequestPlan.empty(), mirroredHeaderPlan);
	}

	@NonNull
	static McpNormalizedOperation tool(
			@NonNull McpNormalizedToolDescriptor descriptor,
			@NonNull McpInputRequestPlan inputRequestPlan,
			@NonNull McpMirroredHeaderPlan mirroredHeaderPlan) {
		requireNonNull(descriptor);
		return new McpNormalizedOperation(descriptor.name(),
				requireNonNull(inputRequestPlan), requireNonNull(mirroredHeaderPlan),
				Optional.of(descriptor), Optional.empty(), Optional.empty(),
				Optional.empty());
	}

	@NonNull
	static McpNormalizedOperation prompt(
			@NonNull McpNormalizedPromptDescriptor descriptor) {
		return prompt(descriptor, McpInputRequestPlan.empty());
	}

	@NonNull
	static McpNormalizedOperation prompt(
			@NonNull McpNormalizedPromptDescriptor descriptor,
			@NonNull McpInputRequestPlan inputRequestPlan) {
		requireNonNull(descriptor);
		return new McpNormalizedOperation(descriptor.name(),
				requireNonNull(inputRequestPlan), McpMirroredHeaderPlan.empty(),
				Optional.empty(), Optional.of(descriptor), Optional.empty(),
				Optional.empty());
	}

	@NonNull
	static McpNormalizedOperation resource(
			@NonNull McpNormalizedResourceDescriptor descriptor,
			@NonNull McpInputRequestPlan inputRequestPlan) {
		requireNonNull(descriptor);
		return new McpNormalizedOperation(descriptor.uri(), inputRequestPlan,
				McpMirroredHeaderPlan.empty(), Optional.empty(), Optional.empty(),
				Optional.of(descriptor), Optional.empty());
	}

	@NonNull
	static McpNormalizedOperation resourceTemplate(
			@NonNull McpNormalizedResourceTemplateDescriptor descriptor,
			@NonNull McpInputRequestPlan inputRequestPlan) {
		requireNonNull(descriptor);
		return new McpNormalizedOperation(descriptor.uriTemplate(), inputRequestPlan,
				McpMirroredHeaderPlan.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.of(descriptor));
	}

	@NonNull
	static McpNormalizedOperation named(@NonNull String name) {
		return new McpNormalizedOperation(name, McpInputRequestPlan.empty(),
				McpMirroredHeaderPlan.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty());
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
