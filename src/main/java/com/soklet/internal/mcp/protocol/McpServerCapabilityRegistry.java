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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable endpoint-specific registry derived exclusively from normalized registration.
 */
final class McpServerCapabilityRegistry {
	private final McpServerCapabilities capabilities;
	private final List<String> tools;
	private final List<String> prompts;
	private final List<String> exactResourceUris;
	private final List<String> resourceTemplates;
	private final Map<McpOperationKey, McpInputRequestPlan> inputRequestPlans;
	private final McpDiscoverResult discoverResult;

	static McpServerCapabilityRegistry fromEndpoint(McpNormalizedEndpoint endpoint) {
		return new McpServerCapabilityRegistry(endpoint);
	}

	private McpServerCapabilityRegistry(McpNormalizedEndpoint endpoint) {
		requireNonNull(endpoint);
		this.tools = namesOf(endpoint.tools());
		this.prompts = namesOf(endpoint.prompts());
		this.exactResourceUris = namesOf(endpoint.exactResources());
		this.resourceTemplates = namesOf(endpoint.resourceTemplates());
		this.inputRequestPlans = inputRequestPlans(endpoint);

		Optional<McpImmutableCatalogCapability> toolsCapability = tools.isEmpty()
				? Optional.empty()
				: Optional.of(McpImmutableCatalogCapability.INSTANCE);
		Optional<McpImmutableCatalogCapability> promptsCapability = prompts.isEmpty()
				? Optional.empty()
				: Optional.of(McpImmutableCatalogCapability.INSTANCE);
		Optional<McpResourceCapability> resourcesCapability;

		if (endpoint.hasResourceSurface()) {
			Set<McpResourceNotificationType> notificationTypes = endpoint.subscriptions()
					.map(McpNormalizedSubscriptionConfiguration::notificationTypes)
					.orElseGet(Set::of);
			resourcesCapability = Optional.of(new McpResourceCapability(
					notificationTypes.contains(
							McpResourceNotificationType.RESOURCES_LIST_CHANGED),
					notificationTypes.contains(
							McpResourceNotificationType.RESOURCE_UPDATED)));
		} else {
			resourcesCapability = Optional.empty();
		}

		this.capabilities = new McpServerCapabilities(
				toolsCapability, promptsCapability, resourcesCapability);

		Optional<McpImplementationMetadata> serverInformation =
				endpoint.includeServerInformation()
						? Optional.of(endpoint.serverInformation())
						: Optional.empty();
		McpResultMetadata resultMetadata =
				new McpResultMetadata(serverInformation, endpoint.discoveryMetadata());
		Optional<McpResultMetadata> optionalResultMetadata =
				resultMetadata.isEmpty() ? Optional.empty() : Optional.of(resultMetadata);
		this.discoverResult = new McpDiscoverResult(McpProtocolVersion.SUPPORTED,
				capabilities, endpoint.instructions(),
				endpoint.discoveryCachePolicy().timeToLiveMilliseconds(),
				endpoint.discoveryCachePolicy().scope(), optionalResultMetadata);
	}

	McpServerCapabilities capabilities() {
		return capabilities;
	}

	List<String> tools() {
		return tools;
	}

	List<String> prompts() {
		return prompts;
	}

	List<String> exactResourceUris() {
		return exactResourceUris;
	}

	List<String> resourceTemplates() {
		return resourceTemplates;
	}

	Optional<McpInputRequestPlan> inputRequestPlan(
			McpOperationKind operationKind, String operationName) {
		return Optional.ofNullable(inputRequestPlans.get(
				new McpOperationKey(operationKind, operationName)));
	}

	McpDiscoverResult discoverResult() {
		return discoverResult;
	}

	boolean permitsResultType(McpResultType resultType) {
		return requireNonNull(resultType).isCore();
	}

	private static List<String> namesOf(List<McpNormalizedOperation> operations) {
		return operations.stream().map(McpNormalizedOperation::name).toList();
	}

	private static Map<McpOperationKey, McpInputRequestPlan> inputRequestPlans(
			McpNormalizedEndpoint endpoint) {
		Map<McpOperationKey, McpInputRequestPlan> plans = new LinkedHashMap<>();

		for (McpNormalizedOperation tool : endpoint.tools())
			plans.put(new McpOperationKey(McpOperationKind.TOOL, tool.name()), tool.inputRequestPlan());

		for (McpNormalizedOperation prompt : endpoint.prompts())
			plans.put(new McpOperationKey(McpOperationKind.PROMPT, prompt.name()), prompt.inputRequestPlan());

		for (McpNormalizedOperation resource : endpoint.exactResources())
			plans.put(new McpOperationKey(McpOperationKind.RESOURCE, resource.name()),
					resource.inputRequestPlan());

		for (McpNormalizedOperation resourceTemplate : endpoint.resourceTemplates())
			plans.put(new McpOperationKey(McpOperationKind.RESOURCE, resourceTemplate.name()),
					resourceTemplate.inputRequestPlan());

		return Collections.unmodifiableMap(plans);
	}
}

record McpOperationKey(McpOperationKind kind, String name) {
	McpOperationKey {
		requireNonNull(kind);
		name = McpProtocolSupport.requireNonBlank(name, "Operation name");
	}
}

enum McpOperationKind {
	TOOL,
	PROMPT,
	RESOURCE
}

record McpServerCapabilities(Optional<McpImmutableCatalogCapability> tools,
		Optional<McpImmutableCatalogCapability> prompts,
		Optional<McpResourceCapability> resources) {
	McpServerCapabilities {
		requireNonNull(tools);
		requireNonNull(prompts);
		requireNonNull(resources);
	}

	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();
		tools.ifPresent(value -> values.put("tools", value.toJsonObject()));
		prompts.ifPresent(value -> values.put("prompts", value.toJsonObject()));
		resources.ifPresent(value -> values.put("resources", value.toJsonObject()));
		return new McpJsonObject(values);
	}
}

enum McpImmutableCatalogCapability {
	INSTANCE;

	McpJsonObject toJsonObject() {
		return McpJsonObject.empty();
	}
}

record McpResourceCapability(boolean listChanged, boolean subscribe) {
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();

		if (listChanged)
			values.put("listChanged", McpJsonBoolean.TRUE);

		if (subscribe)
			values.put("subscribe", McpJsonBoolean.TRUE);

		return new McpJsonObject(values);
	}
}

record McpDiscoverResult(List<String> supportedVersions,
		McpServerCapabilities capabilities, Optional<String> instructions,
		long timeToLiveMilliseconds, McpCacheScope cacheScope,
		Optional<McpResultMetadata> metadata) {
	McpDiscoverResult {
		supportedVersions = McpProtocolSupport.immutableUniqueNames(
				supportedVersions, "supported protocol version");
		requireNonNull(capabilities);
		requireNonNull(instructions);

		if (timeToLiveMilliseconds < 0L)
			throw new IllegalArgumentException("Discovery cache TTL must be >= 0.");

		requireNonNull(cacheScope);
		requireNonNull(metadata);
	}

	McpWireResult toWireResult() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();
		List<McpJsonValue> versionValues = supportedVersions.stream()
				.map(McpJsonString::new)
				.map(McpJsonValue.class::cast)
				.toList();
		values.put("supportedVersions", new McpJsonArray(versionValues));
		values.put("capabilities", capabilities.toJsonObject());
		instructions.ifPresent(value -> values.put("instructions", new McpJsonString(value)));
		values.put("ttlMs", new McpJsonNumber(timeToLiveMilliseconds));
		values.put("cacheScope", new McpJsonString(cacheScope.wireValue()));
		return McpWireResult.complete(new McpJsonObject(values), metadata);
	}
}
