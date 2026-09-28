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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable transport binding for one exact MCP endpoint path.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpHttpEndpointBinding(@NonNull McpHttpEndpointPolicy endpointPolicy,
		@NonNull McpNormalizedEndpoint endpoint,
		@NonNull McpApplicationRequestRouter applicationRouter,
		@NonNull McpRuntimeObservationSink observationSink,
		@NonNull List<@NonNull McpSubscriptionEventSource>
				subscriptionEventSources,
		@NonNull Optional<McpServerRuntimeBridge.@NonNull TaskManagerAdapter>
				taskManagerAdapter,
		@NonNull Map<@NonNull String, @NonNull McpNormalizedEndpoint>
				revisionEndpoints) {
	private static final String MODERN_REVISION = "2026-07-28";

	McpHttpEndpointBinding(@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpApplicationRequestRouter applicationRouter,
			@NonNull McpRuntimeObservationSink observationSink,
			@NonNull List<@NonNull McpSubscriptionEventSource>
					subscriptionEventSources,
			@NonNull Optional<McpServerRuntimeBridge.@NonNull TaskManagerAdapter>
					taskManagerAdapter) {
		this(endpointPolicy, endpoint, applicationRouter, observationSink,
				subscriptionEventSources, taskManagerAdapter,
				Map.of(MODERN_REVISION, endpoint));
	}
	McpHttpEndpointBinding(@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpApplicationRequestRouter applicationRouter) {
		this(endpointPolicy, endpoint, applicationRouter,
				McpRuntimeObservationSink.disabledInstance(), List.of(),
				Optional.empty());
	}

	McpHttpEndpointBinding(@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpApplicationRequestRouter applicationRouter,
			@NonNull McpRuntimeObservationSink observationSink) {
		this(endpointPolicy, endpoint, applicationRouter, observationSink,
				List.of(), Optional.empty());
	}

	McpHttpEndpointBinding(@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpApplicationRequestRouter applicationRouter,
			@NonNull McpRuntimeObservationSink observationSink,
			@NonNull Optional<@NonNull McpSubscriptionEventSource>
					subscriptionEventSource) {
		this(endpointPolicy, endpoint, applicationRouter, observationSink,
				requireNonNull(subscriptionEventSource).stream().toList(),
				Optional.empty());
	}

	McpHttpEndpointBinding {
		requireNonNull(endpointPolicy);
		requireNonNull(endpoint);
		requireNonNull(applicationRouter);
		requireNonNull(observationSink);
		subscriptionEventSources = List.copyOf(
				requireNonNull(subscriptionEventSources));
		requireNonNull(taskManagerAdapter);
		revisionEndpoints = Collections.unmodifiableMap(new LinkedHashMap<>(
				requireNonNull(revisionEndpoints)));
		if (revisionEndpoints.isEmpty())
			throw new IllegalArgumentException(
					"An MCP endpoint must serve at least one exact protocol revision.");
		if (revisionEndpoints.values().stream().anyMatch(value -> value == null))
			throw new IllegalArgumentException("An MCP revision endpoint must not be null.");
		if (!subscriptionEventSources.isEmpty()
				!= endpoint.subscriptionConfig().isPresent())
			throw new IllegalArgumentException(
					"MCP subscription sources and normalized configuration must be present together.");
		long taskSourceCount = subscriptionEventSources.stream()
				.filter(source -> source.sourceType()
						== McpSubscriptionEventSource.SourceType.TASK)
				.count();
		boolean taskNotifications = endpoint.subscriptionConfig()
				.map(McpNormalizedSubscriptionConfiguration::taskNotifications)
				.orElse(false);
		if (taskSourceCount > 1 || taskNotifications != (taskSourceCount == 1))
			throw new IllegalArgumentException(
					"MCP task-notification configuration requires exactly one task event source.");
		if (taskNotifications && taskManagerAdapter.isEmpty())
			throw new IllegalArgumentException(
					"MCP task notifications require a task-manager adapter.");
	}

	Optional<McpNormalizedEndpoint> revisionEndpoint(String revision) {
		return Optional.ofNullable(revisionEndpoints.get(requireNonNull(revision)));
	}

	Set<String> supportedRevisions() {
		return revisionEndpoints.keySet();
	}
}
