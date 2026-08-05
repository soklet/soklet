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

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable endpoint-specific registry derived exclusively from normalized registration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpServerCapabilityRegistry {
	@NonNull
	private final McpServerCapabilities capabilities;
	@NonNull
	private final List<@NonNull String> tools;
	@NonNull
	private final List<@NonNull String> prompts;
	@NonNull
	private final List<@NonNull String> exactResourceUris;
	@NonNull
	private final List<@NonNull String> resourceTemplates;
	@NonNull
	private final Map<@NonNull McpOperationKey, @NonNull McpInputRequestPlan> inputRequestPlans;
	@NonNull
	private final Map<@NonNull String, @NonNull McpMirroredHeaderPlan> toolMirroredHeaderPlans;
	@NonNull
	private final Set<@NonNull String> customMirroredHeaderNames;
	@NonNull
	private final McpDiscoverResult discoverResult;
	@NonNull
	private final McpWireResult toolsListResult;

	@NonNull
	static McpServerCapabilityRegistry fromEndpoint(
			@NonNull McpNormalizedEndpoint endpoint) {
		return new McpServerCapabilityRegistry(endpoint);
	}

	private McpServerCapabilityRegistry(@NonNull McpNormalizedEndpoint endpoint) {
		requireNonNull(endpoint);
		this.tools = namesOf(endpoint.tools());
		this.prompts = namesOf(endpoint.prompts());
		this.exactResourceUris = namesOf(endpoint.exactResources());
		this.resourceTemplates = namesOf(endpoint.resourceTemplates());
		this.inputRequestPlans = inputRequestPlans(endpoint);
		this.toolMirroredHeaderPlans = toolMirroredHeaderPlans(endpoint);
		this.customMirroredHeaderNames = customMirroredHeaderNames(endpoint);
		this.toolsListResult = toolsListResult(endpoint.tools());

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

	@NonNull
	McpServerCapabilities capabilities() {
		return capabilities;
	}

	@NonNull
	List<@NonNull String> tools() {
		return tools;
	}

	@NonNull
	List<@NonNull String> prompts() {
		return prompts;
	}

	@NonNull
	List<@NonNull String> exactResourceUris() {
		return exactResourceUris;
	}

	@NonNull
	List<@NonNull String> resourceTemplates() {
		return resourceTemplates;
	}

	@NonNull
	Optional<@NonNull McpInputRequestPlan> inputRequestPlan(
			@NonNull McpOperationKind operationKind,
			@NonNull String operationName) {
		return Optional.ofNullable(inputRequestPlans.get(
				new McpOperationKey(operationKind, operationName)));
	}

	@NonNull
	Optional<@NonNull McpMirroredHeaderPlan> toolMirroredHeaderPlan(
			@NonNull String toolName) {
		return Optional.ofNullable(toolMirroredHeaderPlans.get(requireNonNull(toolName)));
	}

	@NonNull
	Set<@NonNull String> customMirroredHeaderNames() {
		return customMirroredHeaderNames;
	}

	@NonNull
	McpDiscoverResult discoverResult() {
		return discoverResult;
	}

	@NonNull
	McpWireResult toolsListResult() {
		return toolsListResult;
	}

	boolean permitsResultType(@NonNull McpResultType resultType) {
		return requireNonNull(resultType).isCore();
	}

	@NonNull
	private static List<@NonNull String> namesOf(
			@NonNull List<@NonNull McpNormalizedOperation> operations) {
		return operations.stream().map(McpNormalizedOperation::name).toList();
	}

	@NonNull
	private static McpWireResult toolsListResult(
			@NonNull List<@NonNull McpNormalizedOperation> tools) {
		List<McpJsonValue> descriptors = tools.stream()
				.map(tool -> tool.toolDescriptor()
						.orElseGet(() -> McpNormalizedToolDescriptor.minimal(tool.name())))
				.map(McpNormalizedToolDescriptor::toJsonObject)
				.map(McpJsonValue.class::cast)
				.toList();
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("tools", new McpJsonArray(descriptors));
		fields.put("ttlMs", new McpJsonNumber(0L));
		fields.put("cacheScope", new McpJsonString(McpCacheScope.PRIVATE.wireValue()));
		return McpWireResult.complete(new McpJsonObject(fields));
	}

	@NonNull
	private static Map<@NonNull McpOperationKey, @NonNull McpInputRequestPlan> inputRequestPlans(
			@NonNull McpNormalizedEndpoint endpoint) {
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

	@NonNull
	private static Map<@NonNull String, @NonNull McpMirroredHeaderPlan> toolMirroredHeaderPlans(
			@NonNull McpNormalizedEndpoint endpoint) {
		Map<String, McpMirroredHeaderPlan> plans = new LinkedHashMap<>();
		for (McpNormalizedOperation tool : endpoint.tools())
			plans.put(tool.name(), tool.mirroredHeaderPlan());
		return Collections.unmodifiableMap(plans);
	}

	@NonNull
	private static Set<@NonNull String> customMirroredHeaderNames(
			@NonNull McpNormalizedEndpoint endpoint) {
		Map<String, String> namesByLowercase = new LinkedHashMap<>();
		for (McpNormalizedOperation tool : endpoint.tools()) {
			for (McpMirroredHeaderDeclaration declaration
					: tool.mirroredHeaderPlan().declarations())
				namesByLowercase.putIfAbsent(
						declaration.headerName().toLowerCase(Locale.ROOT),
						declaration.headerName());
		}
		return Collections.unmodifiableSet(
				new java.util.LinkedHashSet<>(namesByLowercase.values()));
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpOperationKey(@NonNull McpOperationKind kind, @NonNull String name) {
	McpOperationKey {
		requireNonNull(kind);
		name = McpProtocolSupport.requireNonBlank(name, "Operation name");
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpOperationKind {
	TOOL,
	PROMPT,
	RESOURCE
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpServerCapabilities(
		@NonNull Optional<@NonNull McpImmutableCatalogCapability> tools,
		@NonNull Optional<@NonNull McpImmutableCatalogCapability> prompts,
		@NonNull Optional<@NonNull McpResourceCapability> resources) {
	McpServerCapabilities {
		requireNonNull(tools);
		requireNonNull(prompts);
		requireNonNull(resources);
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();
		tools.ifPresent(value -> values.put("tools", value.toJsonObject()));
		prompts.ifPresent(value -> values.put("prompts", value.toJsonObject()));
		resources.ifPresent(value -> values.put("resources", value.toJsonObject()));
		return new McpJsonObject(values);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
enum McpImmutableCatalogCapability {
	INSTANCE;

	@NonNull
	McpJsonObject toJsonObject() {
		return McpJsonObject.empty();
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpResourceCapability(boolean listChanged, boolean subscribe) {
	@NonNull
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();

		if (listChanged)
			values.put("listChanged", McpJsonBoolean.TRUE);

		if (subscribe)
			values.put("subscribe", McpJsonBoolean.TRUE);

		return new McpJsonObject(values);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpDiscoverResult(@NonNull List<@NonNull String> supportedVersions,
		@NonNull McpServerCapabilities capabilities,
		@NonNull Optional<@NonNull String> instructions,
		long timeToLiveMilliseconds, @NonNull McpCacheScope cacheScope,
		@NonNull Optional<@NonNull McpResultMetadata> metadata) {
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

	@NonNull
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
