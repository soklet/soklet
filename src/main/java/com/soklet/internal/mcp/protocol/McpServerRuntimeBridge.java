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

import com.soklet.CorsAuthorizer;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpEndpoint;
import com.soklet.McpImplementation;
import com.soklet.McpJsonArray;
import com.soklet.McpJsonBoolean;
import com.soklet.McpJsonNull;
import com.soklet.McpJsonNumber;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import com.soklet.McpRequestId;
import com.soklet.McpRequestRejection;
import com.soklet.Request;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Internal bridge between Soklet's public MCP server and the package-private
 * protocol runtime. This is not an application extension point.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpServerRuntimeBridge {
	@NonNull
	private static final McpJsonCodec CANONICAL_JSON_CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());
	@NonNull
	private final McpHttpServerRuntime runtime;

	/**
	 * Creates a discovery-only listener projection.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull McpEndpoint publicEndpoint,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer) {
		this(host, port, publicEndpoint, allowedHosts, requireOrigin,
				corsAuthorizer, corsAuthorizerExplicitlyConfigured, admissionAdapter,
				startupDiagnosticConsumer, throwable -> {});
	}

	/**
	 * Creates a discovery-only listener projection with runtime-failure reporting.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull McpEndpoint publicEndpoint,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		this(host, port, publicEndpoint, allowedHosts, requireOrigin,
				corsAuthorizer, corsAuthorizerExplicitlyConfigured, admissionAdapter,
				Optional.empty(), List.of(), startupDiagnosticConsumer,
				unexpectedTerminationConsumer);
	}

	/**
	 * Creates a listener projection from immutable executable tool plans.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull McpEndpoint publicEndpoint,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Optional<@NonNull RateLimitAdapter> requestRateLimitAdapter,
			@NonNull List<@NonNull ToolPlan> toolPlans,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		this(host, port, publicEndpoint, allowedHosts, requireOrigin,
				corsAuthorizer, corsAuthorizerExplicitlyConfigured,
				admissionAdapter, requestRateLimitAdapter, toolPlans, List.of(),
				startupDiagnosticConsumer, unexpectedTerminationConsumer);
	}

	/**
	 * Creates a listener projection from immutable executable tool and prompt
	 * plans.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull McpEndpoint publicEndpoint,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Optional<@NonNull RateLimitAdapter> requestRateLimitAdapter,
			@NonNull List<@NonNull ToolPlan> toolPlans,
			@NonNull List<@NonNull PromptPlan> promptPlans,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		this(host, port, publicEndpoint, allowedHosts, requireOrigin,
				corsAuthorizer, corsAuthorizerExplicitlyConfigured, admissionAdapter,
				requestRateLimitAdapter, toolPlans, promptPlans, List.of(),
				Optional.empty(), startupDiagnosticConsumer,
				unexpectedTerminationConsumer);
	}

	/**
	 * Creates a listener projection from all immutable Phase 4 operation plans.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull McpEndpoint publicEndpoint,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Optional<@NonNull RateLimitAdapter> requestRateLimitAdapter,
			@NonNull List<@NonNull ToolPlan> toolPlans,
			@NonNull List<@NonNull PromptPlan> promptPlans,
			@NonNull List<@NonNull ResourcePlan> resourcePlans,
			@NonNull Optional<@NonNull ResourceListPlan> resourceListPlan,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		this(host, port, publicEndpoint, allowedHosts, requireOrigin,
				corsAuthorizer, corsAuthorizerExplicitlyConfigured, admissionAdapter,
				requestRateLimitAdapter, toolPlans, promptPlans, resourcePlans,
				resourceListPlan,
				McpApplicationExecutionConfiguration.productionDefaults()
						.handlerConcurrency(),
				McpApplicationExecutionConfiguration.productionDefaults()
						.handlerQueueCapacity(),
				McpApplicationExecutionConfiguration.productionDefaults()
						.requestDeadline(),
				Optional.empty(), startupDiagnosticConsumer,
				unexpectedTerminationConsumer);
	}

	/**
	 * Creates a listener projection with explicit application-execution bounds.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull McpEndpoint publicEndpoint,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Optional<@NonNull RateLimitAdapter> requestRateLimitAdapter,
			@NonNull List<@NonNull ToolPlan> toolPlans,
			@NonNull List<@NonNull PromptPlan> promptPlans,
			@NonNull List<@NonNull ResourcePlan> resourcePlans,
			@NonNull Optional<@NonNull ResourceListPlan> resourceListPlan,
			int requestHandlerConcurrency, int requestHandlerQueueCapacity,
			@NonNull Duration requestTimeout,
			@NonNull Optional<@NonNull Supplier<@NonNull ExecutorService>>
					requestHandlerExecutorServiceSupplier,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		this(host, port,
				List.of(singletonEndpointPlan(publicEndpoint, toolPlans, promptPlans,
						resourcePlans, resourceListPlan)),
				allowedHosts, requireOrigin, corsAuthorizer,
				corsAuthorizerExplicitlyConfigured, admissionAdapter,
				requestRateLimitAdapter, requestHandlerConcurrency,
				requestHandlerQueueCapacity, requestTimeout,
				requestHandlerExecutorServiceSupplier, startupDiagnosticConsumer,
				unexpectedTerminationConsumer);
	}

	@NonNull
	private static EndpointPlan singletonEndpointPlan(
			@NonNull McpEndpoint publicEndpoint,
			@NonNull List<@NonNull ToolPlan> toolPlans,
			@NonNull List<@NonNull PromptPlan> promptPlans,
			@NonNull List<@NonNull ResourcePlan> resourcePlans,
			@NonNull Optional<@NonNull ResourceListPlan> resourceListPlan) {
		requireNonNull(resourceListPlan);
		return new EndpointPlan(publicEndpoint, toolPlans, promptPlans,
				resourcePlans,
				resourceListPlan.orElseGet(ResourceListPlan::staticDefaults));
	}

	/**
	 * Creates one listener projection from multiple immutable endpoint plans.
	 * Handler execution bounds and the optional executor remain server-wide.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull List<@NonNull EndpointPlan> endpointPlans,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Optional<@NonNull RateLimitAdapter> requestRateLimitAdapter,
			int requestHandlerConcurrency, int requestHandlerQueueCapacity,
			@NonNull Duration requestTimeout,
			@NonNull Optional<@NonNull Supplier<@NonNull ExecutorService>>
					requestHandlerExecutorServiceSupplier,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		requireNonNull(host);
		List<EndpointPlan> immutableEndpointPlans =
				List.copyOf(requireNonNull(endpointPlans));
		requireNonNull(allowedHosts);
		requireNonNull(corsAuthorizer);
		requireNonNull(admissionAdapter);
		requireNonNull(requestRateLimitAdapter);
		requireNonNull(requestTimeout);
		requireNonNull(requestHandlerExecutorServiceSupplier);
		requireNonNull(startupDiagnosticConsumer);
		requireNonNull(unexpectedTerminationConsumer);

		List<McpHttpEndpointBinding> endpointBindings = immutableEndpointPlans.stream()
				.map(endpointPlan -> toEndpointBinding(endpointPlan, allowedHosts,
						requireOrigin, corsAuthorizer,
						corsAuthorizerExplicitlyConfigured, admissionAdapter,
						requestRateLimitAdapter))
				.toList();
		McpHttpTransportConfiguration defaults =
				McpHttpTransportConfiguration.productionDefaults(port);
		McpHttpTransportConfiguration transport = new McpHttpTransportConfiguration(
				host, port, defaults.selectorResolution(), defaults.requestHeaderTimeout(),
				defaults.requestBodyTimeout(), defaults.responseWriteIdleTimeout(),
				defaults.keepAliveInterval(), defaults.shutdownTimeout(),
				defaults.readBufferSize(), defaults.acceptBacklog(),
				defaults.maximumAggregateRequestBytes(),
				defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				defaults.maximumConnections(), defaults.connectionWriterConcurrency(),
				defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity(), defaults.streamQueueCapacity());
		McpApplicationExecutionConfiguration applicationConfiguration =
				new McpApplicationExecutionConfiguration(requestHandlerConcurrency,
						requestHandlerQueueCapacity, requestTimeout,
						McpApplicationExecutionConfiguration.productionDefaults()
								.timerResolution());
		McpApplicationHandlerExecutorFactory applicationExecutorFactory =
				requestHandlerExecutorServiceSupplier
						.<McpApplicationHandlerExecutorFactory>map(supplier ->
								ignoredConcurrency -> requireUsableExecutor(
										supplier.get()))
						.orElseGet(McpApplicationHandlerExecutorFactory::production);
		this.runtime = new McpHttpServerRuntime(transport, endpointBindings,
				McpJsonLimits.productionDefaults(), applicationConfiguration,
				McpApplicationClock.SYSTEM, applicationExecutorFactory,
				startupDiagnosticConsumer, unexpectedTerminationConsumer);
	}

	@NonNull
	private static McpHttpEndpointBinding toEndpointBinding(
			@NonNull EndpointPlan endpointPlan,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Optional<@NonNull RateLimitAdapter> requestRateLimitAdapter) {
		requireNonNull(endpointPlan);
		McpEndpoint publicEndpoint = endpointPlan.endpoint();
		McpImplementation publicInformation = publicEndpoint.getServerInformation();
		McpImplementationMetadata implementation = new McpImplementationMetadata(
				publicInformation.getName(), publicInformation.getVersion(),
				publicInformation.getTitle(), publicInformation.getDescription(),
				publicInformation.getWebsiteUrl(), List.of(),
				com.soklet.internal.mcp.protocol.McpJsonObject.empty());
		McpNormalizedEndpoint.Builder endpointBuilder =
				McpNormalizedEndpoint.withServerInformation(implementation);
		publicEndpoint.getInstructions().ifPresent(endpointBuilder::instructions);

		Map<String, McpApplicationToolRoute> toolRoutes = new LinkedHashMap<>();
		for (ToolPlan toolPlan : endpointPlan.toolPlans()) {
			McpNormalizedToolDescriptor descriptor = new McpNormalizedToolDescriptor(
					toolPlan.name(),
					(com.soklet.internal.mcp.protocol.McpJsonObject)
							toInternal(toolPlan.inputSchemaDocument()),
					toolPlan.outputSchemaDocument().map(value ->
							(com.soklet.internal.mcp.protocol.McpJsonObject)
									toInternal(value)),
					(com.soklet.internal.mcp.protocol.McpJsonObject)
							toInternal(toolPlan.descriptorFields()),
					(com.soklet.internal.mcp.protocol.McpJsonObject)
							toInternal(toolPlan.metadata()));
			endpointBuilder.tool(McpNormalizedOperation.tool(
					descriptor, McpMirroredHeaderPlan.empty()));
			McpRateLimiter internalToolRateLimiter = context ->
					toInternalRateLimitDecision(requireNonNull(
							toolPlan.toolRateLimitAdapter().acquire(
									toRateLimitInput(context, publicEndpoint)),
							"The MCP tool rate limiter returned null."));
			McpApplicationToolRoute route = new McpApplicationToolRoute(
					invocation -> invokeTool(toolPlan, invocation, publicEndpoint),
					internalToolRateLimiter);
			if (toolRoutes.putIfAbsent(toolPlan.name(), route) != null)
				throw new IllegalArgumentException(
						"Duplicate tool plan '" + toolPlan.name() + "'.");
		}

		Map<String, McpApplicationPromptRoute> promptRoutes = new LinkedHashMap<>();
		for (PromptPlan promptPlan : endpointPlan.promptPlans()) {
			List<McpNormalizedPromptArgumentDescriptor> arguments = promptPlan
					.arguments().stream()
					.map(argument -> new McpNormalizedPromptArgumentDescriptor(
							argument.name(), argument.required(),
							(com.soklet.internal.mcp.protocol.McpJsonObject)
									toInternal(argument.descriptorFields())))
					.toList();
			McpNormalizedPromptDescriptor descriptor =
					new McpNormalizedPromptDescriptor(promptPlan.name(), arguments,
							(com.soklet.internal.mcp.protocol.McpJsonObject)
									toInternal(promptPlan.descriptorFields()),
							(com.soklet.internal.mcp.protocol.McpJsonObject)
									toInternal(promptPlan.metadata()));
			endpointBuilder.prompt(McpNormalizedOperation.prompt(descriptor));
			McpApplicationPromptRoute route = new McpApplicationPromptRoute(
					invocation -> invokePrompt(promptPlan, invocation, publicEndpoint));
			if (promptRoutes.putIfAbsent(promptPlan.name(), route) != null)
				throw new IllegalArgumentException(
						"Duplicate prompt plan '" + promptPlan.name() + "'.");
		}

		ResourceListPlan resourceListPlan = endpointPlan.resourceListPlan();
		endpointBuilder.resourcesListCachePolicy(toInternal(
				resourceListPlan.resourcesListCachePolicy()));
		endpointBuilder.resourceTemplatesListCachePolicy(toInternal(
				resourceListPlan.resourceTemplatesListCachePolicy()));
		endpointBuilder.maximumCursorSizeInBytes(
				resourceListPlan.maximumCursorSizeInBytes());
		Map<String, McpApplicationResourceReadRoute> exactResourceRoutes =
				new LinkedHashMap<>();
		List<McpApplicationResourceTemplateRoute> resourceTemplateRoutes =
				new ArrayList<>();
		for (ResourcePlan resourcePlan : endpointPlan.resourcePlans()) {
			McpResourceCachePolicy internalCachePolicy =
					toInternal(resourcePlan.readCachePolicy());
			McpApplicationResourceReadRoute readRoute =
					new McpApplicationResourceReadRoute(
							invocation -> invokeResource(resourcePlan, invocation,
									publicEndpoint), internalCachePolicy);
			if (resourcePlan.addressKind() == ResourceAddressKind.URI) {
				McpNormalizedResourceDescriptor descriptor =
						new McpNormalizedResourceDescriptor(resourcePlan.address(),
								resourcePlan.name(),
								(com.soklet.internal.mcp.protocol.McpJsonObject)
										toInternal(resourcePlan.descriptorFields()),
								(com.soklet.internal.mcp.protocol.McpJsonObject)
										toInternal(resourcePlan.metadata()),
								internalCachePolicy);
				endpointBuilder.exactResource(descriptor);
				if (exactResourceRoutes.putIfAbsent(resourcePlan.address(), readRoute)
						!= null)
					throw new IllegalArgumentException(
							"Duplicate exact resource plan '"
									+ resourcePlan.address() + "'.");
			} else {
				McpNormalizedResourceTemplateDescriptor descriptor =
						new McpNormalizedResourceTemplateDescriptor(
								resourcePlan.address(), resourcePlan.name(),
								(com.soklet.internal.mcp.protocol.McpJsonObject)
										toInternal(resourcePlan.descriptorFields()),
								(com.soklet.internal.mcp.protocol.McpJsonObject)
										toInternal(resourcePlan.metadata()),
								internalCachePolicy);
				endpointBuilder.resourceTemplate(descriptor);
				resourceTemplateRoutes.add(
						new McpApplicationResourceTemplateRoute(
								resourcePlan.address(), readRoute));
			}
		}
		Optional<McpApplicationResourceListRoute> internalResourceListRoute =
				resourceListPlan.invoker().map(invoker -> {
					endpointBuilder.customResourceListHandler();
					return new McpApplicationResourceListRoute(invocation ->
							invokeResourceList(resourceListPlan, invocation,
									publicEndpoint));
				});
		McpNormalizedEndpoint endpoint = endpointBuilder.build();

		McpRequestAdmissionPolicy internalAdmissionPolicy = context -> {
			AdmissionInput input = new AdmissionInput(context.request(), publicEndpoint,
					context.endpointPathParameters(), context.jsonRpcMethod(),
					context.notification(),
					context.requestId().map(McpServerRuntimeBridge::toPublic),
					context.protocolVersion(), context.operationName(),
					context.clientInformation().map(McpServerRuntimeBridge::toPublic),
					context.clientCapabilities().map(value ->
							(McpJsonObject) toPublic(value.toJsonObject())),
					context.requestMetadata().map(value ->
							(McpJsonObject) toPublic(value)));
			McpAdmissionDecision decision = requireNonNull(
					admissionAdapter.admit(input),
					"The MCP request-admission policy returned null.");
			return toInternal(decision);
		};

		McpHttpEndpointPolicy endpointPolicy = new McpHttpEndpointPolicy(
				publicEndpoint.getPath(), allowedHosts,
				requireOrigin ? McpAbsentOriginPolicy.REQUIRE_ORIGIN
						: McpAbsentOriginPolicy.ALLOW,
				corsAuthorizer, internalAdmissionPolicy, Optional.empty(),
				McpApplicationRequestInterceptor.passThroughInstance(),
				McpUnknownMirroredHeaderPolicy.IGNORE,
				corsAuthorizerExplicitlyConfigured);
		if (requestRateLimitAdapter.isPresent()) {
			RateLimitAdapter adapter = requestRateLimitAdapter.orElseThrow();
			endpointPolicy = endpointPolicy.withRequestRateLimiter(context ->
					toInternalRateLimitDecision(requireNonNull(
							adapter.acquire(toRateLimitInput(context, publicEndpoint)),
							"The MCP request rate limiter returned null.")));
		}

		McpApplicationRequestRouter applicationRouter =
				McpApplicationRequestRouter.fromHandlersAndOperationRoutes(
						Map.of(), toolRoutes, promptRoutes, exactResourceRoutes,
						resourceTemplateRoutes, internalResourceListRoute);
		return new McpHttpEndpointBinding(endpointPolicy, endpoint,
				applicationRouter);
	}

	@NonNull
	private static ExecutorService requireUsableExecutor(
			@NonNull ExecutorService executorService) {
		requireNonNull(executorService,
				"The MCP request-handler executor supplier returned null.");
		if (executorService.isShutdown() || executorService.isTerminated())
			throw new IllegalStateException(
					"The MCP request-handler executor supplier returned a shut-down executor.");
		return executorService;
	}

	@NonNull
	public InetSocketAddress start() throws IOException {
		return this.runtime.start();
	}

	public void stop() {
		this.runtime.stop();
	}

	public boolean stopAndReportResidualHandlers() {
		return this.runtime.stopAndReportResidualApplicationExecutions();
	}

	public boolean isStarted() {
		return getRuntimeState().started();
	}

	@NonNull
	public Optional<@NonNull InetSocketAddress> getBoundAddress() {
		return getRuntimeState().boundAddress();
	}

	public boolean hasResidualHandlers() {
		return getRuntimeState().residualHandlers();
	}

	/**
	 * Captures the listener state through one runtime lifecycle lock acquisition.
	 *
	 * @return atomic runtime state
	 */
	@NonNull
	public RuntimeState getRuntimeState() {
		McpHttpServerLifecycleSnapshot snapshot = this.runtime.lifecycleSnapshot();
		return new RuntimeState(snapshot.started(), snapshot.stopRequired(),
				snapshot.boundAddress(), snapshot.residualApplicationExecutions());
	}

	/**
	 * Atomic internal lifecycle projection used by the public server adapter.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record RuntimeState(boolean started, boolean stopRequired,
			@NonNull Optional<@NonNull InetSocketAddress> boundAddress,
			boolean residualHandlers) {
		public RuntimeState {
			requireNonNull(boundAddress);
			if (started != boundAddress.isPresent())
				throw new IllegalArgumentException(
						"A started MCP listener must have exactly one bound address.");
			if (started && !stopRequired)
				throw new IllegalArgumentException(
						"A started MCP listener must require a stop transition.");
		}
	}

	/**
	 * Immutable public-to-internal operation plan for one fixed MCP endpoint.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record EndpointPlan(@NonNull McpEndpoint endpoint,
			@NonNull List<@NonNull ToolPlan> toolPlans,
			@NonNull List<@NonNull PromptPlan> promptPlans,
			@NonNull List<@NonNull ResourcePlan> resourcePlans,
			@NonNull ResourceListPlan resourceListPlan) {
		/** Validates and snapshots one endpoint plan. */
		public EndpointPlan {
			requireNonNull(endpoint);
			toolPlans = List.copyOf(requireNonNull(toolPlans));
			promptPlans = List.copyOf(requireNonNull(promptPlans));
			resourcePlans = List.copyOf(requireNonNull(resourcePlans));
			requireNonNull(resourceListPlan);
		}
	}

	/**
	 * Immutable catalog-and-execution source for one tool.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record ToolPlan(@NonNull String name,
			@NonNull McpJsonObject inputSchemaDocument,
			@NonNull Optional<@NonNull McpJsonObject> outputSchemaDocument,
			@NonNull McpJsonObject descriptorFields,
			@NonNull McpJsonObject metadata,
			boolean mirrorStructuredContentAsText,
			@NonNull RateLimitAdapter toolRateLimitAdapter,
			@NonNull ToolInvoker invoker) {
		public ToolPlan {
			name = McpProtocolSupport.requireNonBlank(name, "Tool name");
			requireNonNull(inputSchemaDocument);
			requireNonNull(outputSchemaDocument);
			requireNonNull(descriptorFields);
			requireNonNull(metadata);
			requireNonNull(toolRateLimitAdapter);
			requireNonNull(invoker);
		}

		@Override
		@NonNull
		public String toString() {
			return "ToolPlan[outputSchemaPresent=" + outputSchemaDocument.isPresent()
					+ ", descriptorFieldCount=" + descriptorFields.getMembers().size()
					+ ", metadataFieldCount=" + metadata.getMembers().size()
					+ ", mirrorStructuredContentAsText="
					+ mirrorStructuredContentAsText + "]";
		}
	}

	/**
	 * Immutable catalog-and-execution source for one prompt.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record PromptPlan(@NonNull String name,
			@NonNull List<@NonNull PromptArgumentPlan> arguments,
			@NonNull McpJsonObject descriptorFields,
			@NonNull McpJsonObject metadata,
			@NonNull PromptInvoker invoker) {
		public PromptPlan {
			name = McpProtocolSupport.requireNonBlank(name, "Prompt name");
			arguments = List.copyOf(requireNonNull(arguments));
			requireNonNull(descriptorFields);
			requireNonNull(metadata);
			requireNonNull(invoker);
		}

		@Override
		@NonNull
		public String toString() {
			return "PromptPlan[descriptorFieldCount="
					+ descriptorFields.getMembers().size()
					+ ", argumentCount=" + arguments.size()
					+ ", metadataFieldCount=" + metadata.getMembers().size()
					+ "]";
		}
	}

	/**
	 * Immutable catalog declaration for one prompt argument.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record PromptArgumentPlan(@NonNull String name, boolean required,
			@NonNull McpJsonObject descriptorFields) {
		/** Validates the prompt argument declaration. */
		public PromptArgumentPlan {
			name = McpProtocolSupport.requireNonBlank(
					name, "Prompt argument name");
			requireNonNull(descriptorFields);
		}
	}

	/**
	 * Resource registration address form.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public enum ResourceAddressKind {
		/** Exact absolute URI. */
		URI,
		/** RFC 6570 Level-1 URI template. */
		URI_TEMPLATE
	}

	/**
	 * Cache policy erased to bridge-owned scalar values.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record CachePlan(long timeToLiveMilliseconds,
			@NonNull CacheScope scope) {
		/** Validates a nonnegative whole-millisecond cache policy. */
		public CachePlan {
			if (timeToLiveMilliseconds < 0L)
				throw new IllegalArgumentException("Cache TTL must be nonnegative.");
			requireNonNull(scope);
		}

		/** @return shared-shape private zero-TTL plan */
		@NonNull
		public static CachePlan privateNoCache() {
			return new CachePlan(0L, CacheScope.PRIVATE);
		}
	}

	/**
	 * Bridge-owned cache visibility.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public enum CacheScope {
		/** Authorization-partition-private cache entry. */
		PRIVATE,
		/** Cache entry that may be shared across callers. */
		PUBLIC
	}

	/**
	 * Immutable catalog-and-execution source for one resource-read route.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record ResourcePlan(@NonNull ResourceAddressKind addressKind,
			@NonNull String address, @NonNull String name,
			@NonNull McpJsonObject descriptorFields,
			@NonNull McpJsonObject metadata,
			@NonNull CachePlan readCachePolicy,
			@NonNull ResourceInvoker invoker) {
		/** Validates the erased resource plan. */
		public ResourcePlan {
			requireNonNull(addressKind);
			address = McpProtocolSupport.requireNonBlank(address,
					"Resource address");
			name = McpProtocolSupport.requireNonBlank(name, "Resource name");
			requireNonNull(descriptorFields);
			requireNonNull(metadata);
			requireNonNull(readCachePolicy);
			requireNonNull(invoker);
		}

		@Override
		@NonNull
		public String toString() {
			return "ResourcePlan[addressKind=" + addressKind
					+ ", descriptorFieldCount=" + descriptorFields.getMembers().size()
					+ ", metadataFieldCount=" + metadata.getMembers().size() + "]";
		}
	}

	/**
	 * Endpoint-wide resources/list and templates/list plan.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record ResourceListPlan(
			@NonNull CachePlan resourcesListCachePolicy,
			@NonNull CachePlan resourceTemplatesListCachePolicy,
			int maximumCursorSizeInBytes,
			@NonNull Optional<@NonNull ResourceListInvoker> invoker) {
		/** Validates the endpoint resource-list plan. */
		public ResourceListPlan {
			requireNonNull(resourcesListCachePolicy);
			requireNonNull(resourceTemplatesListCachePolicy);
			if (maximumCursorSizeInBytes < 1)
				throw new IllegalArgumentException(
						"Maximum cursor size must be positive.");
			requireNonNull(invoker);
		}

		/** @return framework-owned static-list defaults */
		@NonNull
		public static ResourceListPlan staticDefaults() {
			return new ResourceListPlan(CachePlan.privateNoCache(),
					CachePlan.privateNoCache(), 4_096, Optional.empty());
		}
	}

	/**
	 * Internal-only guard that commits entry into a public application handler.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface HandlerEntryGuard {
		/** Commits handler entry or fails when the request is no longer active. */
		void requireEntry() throws InterruptedException;
	}

	/**
	 * Erased invocation input for one resource read.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record ResourceInvocation(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod, @NonNull McpRequestId requestId,
			@NonNull String protocolVersion, @NonNull String operationName,
			@NonNull Optional<@NonNull McpImplementation> clientInformation,
			@NonNull McpJsonObject clientCapabilitiesJson,
			@NonNull McpJsonObject requestMetadata,
			@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull String uri,
			@NonNull Map<@NonNull String, @NonNull String> templateVariables,
			@NonNull HandlerEntryGuard handlerEntryGuard) {
		/** Validates and snapshots the erased invocation. */
		public ResourceInvocation {
			requireNonNull(request);
			requireNonNull(endpoint);
			endpointPathParameters = Map.copyOf(
					requireNonNull(endpointPathParameters));
			jsonRpcMethod = McpProtocolSupport.requireNonBlank(
					jsonRpcMethod, "JSON-RPC method");
			requireNonNull(requestId);
			protocolVersion = McpProtocolSupport.requireNonBlank(
					protocolVersion, "Protocol version");
			operationName = McpProtocolSupport.requireNonBlank(
					operationName, "Operation name");
			requireNonNull(clientInformation);
			requireNonNull(clientCapabilitiesJson);
			requireNonNull(requestMetadata);
			requireNonNull(admissionIdentity);
			uri = McpProtocolSupport.requireNonBlank(uri, "Resource URI");
			templateVariables = Map.copyOf(requireNonNull(templateVariables));
			requireNonNull(handlerEntryGuard);
		}
	}

	/**
	 * Erased invocation input for one dynamic resources/list page.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record ResourceListInvocation(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod, @NonNull McpRequestId requestId,
			@NonNull String protocolVersion,
			@NonNull Optional<@NonNull McpImplementation> clientInformation,
			@NonNull McpJsonObject clientCapabilitiesJson,
			@NonNull McpJsonObject requestMetadata,
			@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull Optional<@NonNull String> cursor,
			@NonNull List<@NonNull McpJsonObject> registeredResourceDescriptors,
			@NonNull HandlerEntryGuard handlerEntryGuard) {
		/** Validates and snapshots the erased invocation. */
		public ResourceListInvocation {
			requireNonNull(request);
			requireNonNull(endpoint);
			endpointPathParameters = Map.copyOf(
					requireNonNull(endpointPathParameters));
			jsonRpcMethod = McpProtocolSupport.requireNonBlank(
					jsonRpcMethod, "JSON-RPC method");
			requireNonNull(requestId);
			protocolVersion = McpProtocolSupport.requireNonBlank(
					protocolVersion, "Protocol version");
			requireNonNull(clientInformation);
			requireNonNull(clientCapabilitiesJson);
			requireNonNull(requestMetadata);
			requireNonNull(admissionIdentity);
			requireNonNull(cursor);
			registeredResourceDescriptors = List.copyOf(
					requireNonNull(registeredResourceDescriptors));
			requireNonNull(handlerEntryGuard);
		}
	}

	/**
	 * Invokes one erased resource-read plan.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface ResourceInvoker {
		/** @return erased resource result */
		@NonNull
		ResourceInvocationResult invoke(@NonNull ResourceInvocation invocation)
				throws Exception;
	}

	/**
	 * Invokes the optional sole erased dynamic resource-list plan.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface ResourceListInvoker {
		/** @return erased resource-list result */
		@NonNull
		ResourceListInvocationResult invoke(
				@NonNull ResourceListInvocation invocation) throws Exception;
	}

	/**
	 * Bridge-owned erased resource-read result.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public sealed interface ResourceInvocationResult
			permits ResourceInvocationResult.Complete,
			ResourceInvocationResult.InvalidInput,
			ResourceInvocationResult.JsonRpcError {
		/** @return completed resource result */
		@NonNull
		static Complete complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata) {
			return new Complete(resultFields, metadata);
		}

		/** @return pre-handler input-binding failure */
		@NonNull
		static InvalidInput invalidInput() {
			return InvalidInput.INSTANCE;
		}

		/** @return intentional client-visible JSON-RPC error */
		@NonNull
		static JsonRpcError jsonRpcError(int code, @NonNull String message,
				@NonNull Optional<@NonNull McpJsonValue> data) {
			return new JsonRpcError(code, message, data);
		}

		/**
		 * Completed resource-read fields and metadata.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata) implements ResourceInvocationResult {
			/** Validates result fields and metadata. */
			public Complete {
				requireNonNull(resultFields);
				requireNonNull(metadata);
			}
		}

		/**
		 * Input validation or binding failed before the typed handler ran.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		enum InvalidInput implements ResourceInvocationResult {
			/** Shared invalid-input marker. */
			INSTANCE
		}

		/**
		 * Intentional client-visible JSON-RPC error.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record JsonRpcError(int code, @NonNull String message,
				@NonNull Optional<@NonNull McpJsonValue> data)
				implements ResourceInvocationResult {
			/** Validates the error projection. */
			public JsonRpcError {
				message = McpProtocolSupport.requireNonBlank(message,
						"JSON-RPC error message");
				requireNonNull(data);
			}
		}
	}

	/**
	 * Bridge-owned erased dynamic resource-list result.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public sealed interface ResourceListInvocationResult
			permits ResourceListInvocationResult.Complete,
			ResourceListInvocationResult.JsonRpcError {
		/** @return completed list-page result */
		@NonNull
		static Complete complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata) {
			return new Complete(resultFields, metadata);
		}

		/** @return intentional client-visible JSON-RPC error */
		@NonNull
		static JsonRpcError jsonRpcError(int code, @NonNull String message,
				@NonNull Optional<@NonNull McpJsonValue> data) {
			return new JsonRpcError(code, message, data);
		}

		/**
		 * Completed list-page fields and metadata.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata)
				implements ResourceListInvocationResult {
			/** Validates result fields and metadata. */
			public Complete {
				requireNonNull(resultFields);
				requireNonNull(metadata);
			}
		}

		/**
		 * Intentional client-visible JSON-RPC error.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record JsonRpcError(int code, @NonNull String message,
				@NonNull Optional<@NonNull McpJsonValue> data)
				implements ResourceListInvocationResult {
			/** Validates the error projection. */
			public JsonRpcError {
				message = McpProtocolSupport.requireNonBlank(message,
						"JSON-RPC error message");
				requireNonNull(data);
			}
		}
	}

	/**
	 * Erased invocation input whose public values require no package-private
	 * conversion in the public server adapter.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record ToolInvocation(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod,
			@NonNull McpRequestId requestId,
			@NonNull String protocolVersion,
			@NonNull String operationName,
			@NonNull Optional<@NonNull McpImplementation> clientInformation,
			@NonNull McpJsonObject clientCapabilitiesJson,
			@NonNull McpJsonObject requestMetadata,
			@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull McpJsonObject rawArguments,
			@NonNull HandlerEntryGuard handlerEntryGuard) {
		public ToolInvocation {
			requireNonNull(request);
			requireNonNull(endpoint);
			endpointPathParameters = Map.copyOf(
					requireNonNull(endpointPathParameters));
			jsonRpcMethod = McpProtocolSupport.requireNonBlank(
					jsonRpcMethod, "JSON-RPC method");
			requireNonNull(requestId);
			protocolVersion = McpProtocolSupport.requireNonBlank(
					protocolVersion, "Protocol version");
			operationName = McpProtocolSupport.requireNonBlank(
					operationName, "Operation name");
			requireNonNull(clientInformation);
			requireNonNull(clientCapabilitiesJson);
			requireNonNull(requestMetadata);
			requireNonNull(admissionIdentity);
			requireNonNull(rawArguments);
			requireNonNull(handlerEntryGuard);
		}

		@Override
		@NonNull
		public String toString() {
			return "ToolInvocation[endpointPathParameterCount="
					+ endpointPathParameters.size()
					+ ", clientInformationPresent=" + clientInformation.isPresent()
					+ ", rawArgumentMemberCount=" + rawArguments.getMembers().size()
					+ ", authenticated=" + admissionIdentity.isAuthenticated() + "]";
		}
	}

	/**
	 * Erased invocation input for one prompt request.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record PromptInvocation(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod,
			@NonNull McpRequestId requestId,
			@NonNull String protocolVersion,
			@NonNull String operationName,
			@NonNull Optional<@NonNull McpImplementation> clientInformation,
			@NonNull McpJsonObject clientCapabilitiesJson,
			@NonNull McpJsonObject requestMetadata,
			@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull McpJsonObject rawArguments,
			@NonNull HandlerEntryGuard handlerEntryGuard) {
		public PromptInvocation {
			requireNonNull(request);
			requireNonNull(endpoint);
			endpointPathParameters = Map.copyOf(
					requireNonNull(endpointPathParameters));
			jsonRpcMethod = McpProtocolSupport.requireNonBlank(
					jsonRpcMethod, "JSON-RPC method");
			requireNonNull(requestId);
			protocolVersion = McpProtocolSupport.requireNonBlank(
					protocolVersion, "Protocol version");
			operationName = McpProtocolSupport.requireNonBlank(
					operationName, "Operation name");
			requireNonNull(clientInformation);
			requireNonNull(clientCapabilitiesJson);
			requireNonNull(requestMetadata);
			requireNonNull(admissionIdentity);
			requireNonNull(rawArguments);
			requireNonNull(handlerEntryGuard);
		}

		@Override
		@NonNull
		public String toString() {
			return "PromptInvocation[endpointPathParameterCount="
					+ endpointPathParameters.size()
					+ ", clientInformationPresent=" + clientInformation.isPresent()
					+ ", rawArgumentMemberCount=" + rawArguments.getMembers().size()
					+ ", authenticated=" + admissionIdentity.isAuthenticated() + "]";
		}
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface ToolInvoker {
		@NonNull
		ToolInvocationResult invoke(@NonNull ToolInvocation invocation)
				throws Exception;
	}

	/**
	 * Invokes one erased prompt plan.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface PromptInvoker {
		/**
		 * @param invocation prompt invocation
		 * @return erased prompt result
		 * @throws Exception if application handling fails
		 */
		@NonNull
		PromptInvocationResult invoke(@NonNull PromptInvocation invocation)
				throws Exception;
	}

	/**
	 * Bridge-owned erased tool result.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public sealed interface ToolInvocationResult permits ToolInvocationResult.Complete,
			ToolInvocationResult.Structured, ToolInvocationResult.InvalidInput {
		@NonNull
		static Complete complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata) {
			return new Complete(resultFields, metadata);
		}

		@NonNull
		static Structured structured(@NonNull McpJsonValue structuredContent,
				@NonNull McpJsonObject metadata) {
			return new Structured(structuredContent, metadata);
		}

		@NonNull
		static InvalidInput invalidInput() {
			return InvalidInput.INSTANCE;
		}

		/**
		 * Complete tool-result fields. The bridge may append the configured
		 * structured-content compatibility mirror before wire serialization.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata) implements ToolInvocationResult {
			public Complete {
				requireNonNull(resultFields);
				requireNonNull(metadata);
			}
		}

		/**
		 * Typed structured result whose compatibility mirror remains framework-owned.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Structured(@NonNull McpJsonValue structuredContent,
				@NonNull McpJsonObject metadata) implements ToolInvocationResult {
			public Structured {
				requireNonNull(structuredContent);
				requireNonNull(metadata);
			}
		}

		/**
		 * Input validation or binding failed before the typed handler ran.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		enum InvalidInput implements ToolInvocationResult {
			INSTANCE
		}
	}

	/**
	 * Bridge-owned erased prompt result.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public sealed interface PromptInvocationResult
			permits PromptInvocationResult.Complete,
			PromptInvocationResult.InvalidInput {
		/**
		 * Creates a completed prompt result.
		 *
		 * @param resultFields operation-specific result fields
		 * @param metadata protocol result metadata
		 * @return completed result
		 */
		@NonNull
		static Complete complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata) {
			return new Complete(resultFields, metadata);
		}

		/** @return invalid-input marker */
		@NonNull
		static InvalidInput invalidInput() {
			return InvalidInput.INSTANCE;
		}

		/**
		 * Completed prompt result fields.
		 *
		 * @param resultFields operation-specific result fields
		 * @param metadata protocol result metadata
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata)
				implements PromptInvocationResult {
			/** Validates completed prompt fields and metadata. */
			public Complete {
				requireNonNull(resultFields);
				requireNonNull(metadata);
			}
		}

		/**
		 * Prompt argument validation failed before the handler ran.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		enum InvalidInput implements PromptInvocationResult {
			/** Shared invalid-input marker. */
			INSTANCE
		}
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface RateLimitAdapter {
		@NonNull
		RateLimitResult acquire(@NonNull RateLimitInput input) throws Exception;
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record RateLimitInput(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull RateLimitTarget target,
			@NonNull String jsonRpcMethod,
			@NonNull Optional<@NonNull String> operationName) {
		public RateLimitInput {
			requireNonNull(request);
			requireNonNull(endpoint);
			requireNonNull(admissionIdentity);
			requireNonNull(target);
			jsonRpcMethod = McpProtocolSupport.requireNonBlank(
					jsonRpcMethod, "JSON-RPC method");
			requireNonNull(operationName);
		}

		@Override
		@NonNull
		public String toString() {
			return "RateLimitInput[target=" + target
					+ ", operationNamePresent=" + operationName.isPresent()
					+ ", authenticated=" + admissionIdentity.isAuthenticated() + "]";
		}
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public enum RateLimitTarget {
		REQUEST,
		TOOL
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public sealed interface RateLimitResult permits RateLimitResult.Allowed,
			RateLimitResult.Denied {
		@NonNull
		static Allowed allowed() {
			return new Allowed();
		}

		@NonNull
		static Denied denied(@NonNull Duration retryAfter) {
			return new Denied(retryAfter);
		}

		/**
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Allowed() implements RateLimitResult {
		}

		/**
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Denied(@NonNull Duration retryAfter) implements RateLimitResult {
			public Denied {
				requireNonNull(retryAfter);
				if (retryAfter.isNegative())
					throw new IllegalArgumentException(
							"Retry-After must not be negative.");
			}
		}
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface AdmissionAdapter {
		@NonNull
		McpAdmissionDecision admit(@NonNull AdmissionInput input) throws Exception;
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record AdmissionInput(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod, boolean notification,
			@NonNull Optional<@NonNull McpRequestId> requestId,
			@NonNull String protocolVersion,
			@NonNull Optional<@NonNull String> operationName,
			@NonNull Optional<@NonNull McpImplementation> clientInformation,
			@NonNull Optional<@NonNull McpJsonObject> clientCapabilitiesJson,
			@NonNull Optional<@NonNull McpJsonObject> requestMetadata) {
		public AdmissionInput {
			requireNonNull(request);
			requireNonNull(endpoint);
			endpointPathParameters = Map.copyOf(requireNonNull(endpointPathParameters));
			requireNonNull(jsonRpcMethod);
			requireNonNull(requestId);
			requireNonNull(protocolVersion);
			requireNonNull(operationName);
			requireNonNull(clientInformation);
			requireNonNull(clientCapabilitiesJson);
			requireNonNull(requestMetadata);
		}
	}

	@NonNull
	private static McpWireResult invokeTool(@NonNull ToolPlan toolPlan,
			@NonNull McpApplicationInvocation invocation,
			@NonNull McpEndpoint publicEndpoint) throws Exception {
		McpJsonRpcMessage.Request request = invocation.request();
		com.soklet.internal.mcp.protocol.McpJsonValue argumentsValue =
				request.params().fields().members().get("arguments");
		com.soklet.internal.mcp.protocol.McpJsonObject arguments =
				argumentsValue instanceof com.soklet.internal.mcp.protocol.McpJsonObject object
						? object : com.soklet.internal.mcp.protocol.McpJsonObject.empty();
		McpRequestMetadata requestMetadata = request.params().metadata();
		ToolInvocation toolInvocation = new ToolInvocation(
				invocation.sokletRequest().orElseThrow(() ->
						new IllegalStateException(
								"A production MCP tool invocation requires its Soklet request.")),
				publicEndpoint, Map.of(), request.method(), toPublic(request.id()),
				requestMetadata.protocolVersion(), toolPlan.name(),
				requestMetadata.clientInformation().map(McpServerRuntimeBridge::toPublic),
				(McpJsonObject) toPublic(
						requestMetadata.clientCapabilities().toJsonObject()),
				(McpJsonObject) toPublic(requestMetadata.toJsonObject()),
				toPublic(invocation.admissionIdentity().admittedIdentity()),
				(McpJsonObject) toPublic(arguments),
				invocation::requireHandlerEntry);
		ToolInvocationResult result = requireNonNull(
				toolPlan.invoker().invoke(toolInvocation),
				"The MCP tool invoker returned null.");

		if (result instanceof ToolInvocationResult.InvalidInput)
			throw new McpInvalidApplicationInputException();

		com.soklet.internal.mcp.protocol.McpJsonObject resultFields;
		com.soklet.internal.mcp.protocol.McpJsonObject resultMetadata;
		if (result instanceof ToolInvocationResult.Complete complete) {
			resultFields = (com.soklet.internal.mcp.protocol.McpJsonObject)
					toInternal(complete.resultFields());
			if (toolPlan.mirrorStructuredContentAsText())
				resultFields = withStructuredContentTextMirror(resultFields);
			resultMetadata = (com.soklet.internal.mcp.protocol.McpJsonObject)
					toInternal(complete.metadata());
		} else if (result instanceof ToolInvocationResult.Structured structured) {
			com.soklet.internal.mcp.protocol.McpJsonValue structuredContent =
					toInternal(structured.structuredContent());
			List<com.soklet.internal.mcp.protocol.McpJsonValue> content =
					new ArrayList<>();
			if (toolPlan.mirrorStructuredContentAsText()) {
				Map<String, com.soklet.internal.mcp.protocol.McpJsonValue> textBlock =
						new LinkedHashMap<>();
				textBlock.put("type",
						new com.soklet.internal.mcp.protocol.McpJsonString("text"));
				textBlock.put("text", new com.soklet.internal.mcp.protocol.McpJsonString(
						CANONICAL_JSON_CODEC.toJson(structuredContent)));
				content.add(new com.soklet.internal.mcp.protocol.McpJsonObject(textBlock));
			}
			Map<String, com.soklet.internal.mcp.protocol.McpJsonValue> fields =
					new LinkedHashMap<>();
			fields.put("content",
					new com.soklet.internal.mcp.protocol.McpJsonArray(content));
			fields.put("structuredContent", structuredContent);
			resultFields = new com.soklet.internal.mcp.protocol.McpJsonObject(fields);
			resultMetadata = (com.soklet.internal.mcp.protocol.McpJsonObject)
					toInternal(structured.metadata());
		} else {
			throw new IllegalArgumentException("Unsupported MCP tool invocation result.");
		}

		McpResultMetadata metadata = new McpResultMetadata(
				Optional.empty(), resultMetadata);
		return McpWireResult.complete(resultFields,
				metadata.isEmpty() ? Optional.empty() : Optional.of(metadata));
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpJsonObject
	withStructuredContentTextMirror(
			com.soklet.internal.mcp.protocol.@NonNull McpJsonObject resultFields) {
		com.soklet.internal.mcp.protocol.McpJsonValue structuredContent =
				resultFields.members().get("structuredContent");
		if (structuredContent == null)
			return resultFields;

		com.soklet.internal.mcp.protocol.McpJsonValue contentValue =
				resultFields.members().get("content");
		if (!(contentValue instanceof com.soklet.internal.mcp.protocol.McpJsonArray
				contentArray))
			throw new IllegalArgumentException(
					"MCP tool output content must be an array.");

		List<com.soklet.internal.mcp.protocol.McpJsonValue> content =
				new ArrayList<>(contentArray.values());
		Map<String, com.soklet.internal.mcp.protocol.McpJsonValue> textBlock =
				new LinkedHashMap<>();
		textBlock.put("type",
				new com.soklet.internal.mcp.protocol.McpJsonString("text"));
		textBlock.put("text", new com.soklet.internal.mcp.protocol.McpJsonString(
				CANONICAL_JSON_CODEC.toJson(structuredContent)));
		content.add(new com.soklet.internal.mcp.protocol.McpJsonObject(textBlock));

		Map<String, com.soklet.internal.mcp.protocol.McpJsonValue> fields =
				new LinkedHashMap<>(resultFields.members());
		fields.put("content",
				new com.soklet.internal.mcp.protocol.McpJsonArray(content));
		return new com.soklet.internal.mcp.protocol.McpJsonObject(fields);
	}

	@NonNull
	private static McpWireResult invokePrompt(@NonNull PromptPlan promptPlan,
			@NonNull McpApplicationInvocation invocation,
			@NonNull McpEndpoint publicEndpoint) throws Exception {
		McpJsonRpcMessage.Request request = invocation.request();
		com.soklet.internal.mcp.protocol.McpJsonValue argumentsValue =
				request.params().fields().members().get("arguments");
		com.soklet.internal.mcp.protocol.McpJsonObject arguments =
				argumentsValue instanceof com.soklet.internal.mcp.protocol.McpJsonObject object
						? object : com.soklet.internal.mcp.protocol.McpJsonObject.empty();
		McpRequestMetadata requestMetadata = request.params().metadata();
		PromptInvocation promptInvocation = new PromptInvocation(
				invocation.sokletRequest().orElseThrow(() ->
						new IllegalStateException(
								"A production MCP prompt invocation requires its Soklet request.")),
				publicEndpoint, Map.of(), request.method(), toPublic(request.id()),
				requestMetadata.protocolVersion(), promptPlan.name(),
				requestMetadata.clientInformation().map(McpServerRuntimeBridge::toPublic),
				(McpJsonObject) toPublic(
						requestMetadata.clientCapabilities().toJsonObject()),
				(McpJsonObject) toPublic(requestMetadata.toJsonObject()),
				toPublic(invocation.admissionIdentity().admittedIdentity()),
				(McpJsonObject) toPublic(arguments),
				invocation::requireHandlerEntry);
		PromptInvocationResult result = requireNonNull(
				promptPlan.invoker().invoke(promptInvocation),
				"The MCP prompt invoker returned null.");

		if (result instanceof PromptInvocationResult.InvalidInput)
			throw new McpInvalidApplicationInputException();
		if (!(result instanceof PromptInvocationResult.Complete complete))
			throw new IllegalArgumentException(
					"Unsupported MCP prompt invocation result.");

		com.soklet.internal.mcp.protocol.McpJsonObject resultFields =
				(com.soklet.internal.mcp.protocol.McpJsonObject)
						toInternal(complete.resultFields());
		com.soklet.internal.mcp.protocol.McpJsonObject resultMetadata =
				(com.soklet.internal.mcp.protocol.McpJsonObject)
						toInternal(complete.metadata());
		McpResultMetadata metadata = new McpResultMetadata(
				Optional.empty(), resultMetadata);
		return McpWireResult.complete(resultFields,
				metadata.isEmpty() ? Optional.empty() : Optional.of(metadata));
	}

	@NonNull
	private static McpWireResult invokeResource(@NonNull ResourcePlan resourcePlan,
			@NonNull McpApplicationResourceReadInvocation internalInvocation,
			@NonNull McpEndpoint publicEndpoint) throws Exception {
		McpApplicationInvocation invocation = internalInvocation.invocation();
		McpJsonRpcMessage.Request request = invocation.request();
		McpRequestMetadata requestMetadata = request.params().metadata();
		ResourceInvocation resourceInvocation = new ResourceInvocation(
				invocation.sokletRequest().orElseThrow(() ->
						new IllegalStateException(
								"A production MCP resource invocation requires its Soklet request.")),
				publicEndpoint, Map.of(), request.method(), toPublic(request.id()),
				requestMetadata.protocolVersion(), internalInvocation.uri(),
				requestMetadata.clientInformation().map(McpServerRuntimeBridge::toPublic),
				(McpJsonObject) toPublic(
						requestMetadata.clientCapabilities().toJsonObject()),
				(McpJsonObject) toPublic(requestMetadata.toJsonObject()),
				toPublic(invocation.admissionIdentity().admittedIdentity()),
				internalInvocation.uri(), internalInvocation.templateVariables(),
				invocation::requireHandlerEntry);
		ResourceInvocationResult result = requireNonNull(
				resourcePlan.invoker().invoke(resourceInvocation),
				"The MCP resource invoker returned null.");

		if (result instanceof ResourceInvocationResult.InvalidInput)
			throw new McpInvalidApplicationInputException();
		if (result instanceof ResourceInvocationResult.JsonRpcError error)
			throw new McpApplicationJsonRpcException(toInternal(error));
		if (!(result instanceof ResourceInvocationResult.Complete complete))
			throw new IllegalArgumentException(
					"Unsupported MCP resource invocation result.");

		return completeResult(complete.resultFields(), complete.metadata());
	}

	@NonNull
	private static McpWireResult invokeResourceList(
			@NonNull ResourceListPlan resourceListPlan,
			@NonNull McpApplicationResourceListInvocation internalInvocation,
			@NonNull McpEndpoint publicEndpoint) throws Exception {
		McpApplicationInvocation invocation = internalInvocation.invocation();
		McpJsonRpcMessage.Request request = invocation.request();
		McpRequestMetadata requestMetadata = request.params().metadata();
		List<McpJsonObject> registeredDescriptors = internalInvocation
				.registeredResourceDescriptors().stream()
				.map(McpNormalizedResourceDescriptor::toJsonObject)
				.map(McpServerRuntimeBridge::toPublic)
				.map(McpJsonObject.class::cast)
				.toList();
		ResourceListInvocation listInvocation = new ResourceListInvocation(
				invocation.sokletRequest().orElseThrow(() ->
						new IllegalStateException(
								"A production MCP resource-list invocation requires its Soklet request.")),
				publicEndpoint, Map.of(), request.method(), toPublic(request.id()),
				requestMetadata.protocolVersion(),
				requestMetadata.clientInformation().map(McpServerRuntimeBridge::toPublic),
				(McpJsonObject) toPublic(
						requestMetadata.clientCapabilities().toJsonObject()),
				(McpJsonObject) toPublic(requestMetadata.toJsonObject()),
				toPublic(invocation.admissionIdentity().admittedIdentity()),
				internalInvocation.cursor(), registeredDescriptors,
				invocation::requireHandlerEntry);
		ResourceListInvocationResult result = requireNonNull(
				resourceListPlan.invoker().orElseThrow().invoke(listInvocation),
				"The MCP resource-list invoker returned null.");

		if (result instanceof ResourceListInvocationResult.JsonRpcError error)
			throw new McpApplicationJsonRpcException(toInternal(error));
		if (!(result instanceof ResourceListInvocationResult.Complete complete))
			throw new IllegalArgumentException(
					"Unsupported MCP resource-list invocation result.");

		return completeResult(complete.resultFields(), complete.metadata());
	}

	@NonNull
	private static McpWireResult completeResult(@NonNull McpJsonObject resultFields,
			@NonNull McpJsonObject metadataFields) {
		com.soklet.internal.mcp.protocol.McpJsonObject internalResultFields =
				(com.soklet.internal.mcp.protocol.McpJsonObject)
						toInternal(requireNonNull(resultFields));
		com.soklet.internal.mcp.protocol.McpJsonObject internalMetadata =
				(com.soklet.internal.mcp.protocol.McpJsonObject)
						toInternal(requireNonNull(metadataFields));
		McpResultMetadata metadata = new McpResultMetadata(
				Optional.empty(), internalMetadata);
		return McpWireResult.complete(internalResultFields,
				metadata.isEmpty() ? Optional.empty() : Optional.of(metadata));
	}

	@NonNull
	private static McpResourceCachePolicy toInternal(@NonNull CachePlan cachePlan) {
		return new McpResourceCachePolicy(cachePlan.timeToLiveMilliseconds(),
				cachePlan.scope() == CacheScope.PUBLIC
						? McpCacheScope.PUBLIC : McpCacheScope.PRIVATE);
	}

	@NonNull
	private static McpJsonRpcError toInternal(
			ResourceInvocationResult.@NonNull JsonRpcError error) {
		return new McpJsonRpcError(
				error.code(), error.message(), error.data().map(
						McpServerRuntimeBridge::toInternal));
	}

	@NonNull
	private static McpJsonRpcError toInternal(
			ResourceListInvocationResult.@NonNull JsonRpcError error) {
		return new McpJsonRpcError(
				error.code(), error.message(), error.data().map(
						McpServerRuntimeBridge::toInternal));
	}

	@NonNull
	private static RateLimitInput toRateLimitInput(
			@NonNull McpRateLimitContext context,
			@NonNull McpEndpoint publicEndpoint) {
		RateLimitTarget target = switch (context.target()) {
			case REQUEST -> RateLimitTarget.REQUEST;
			case TOOL -> RateLimitTarget.TOOL;
		};
		return new RateLimitInput(context.request(), publicEndpoint,
				toPublic(context.admissionIdentity().admittedIdentity()), target,
				context.jsonRpcMethod(), context.operationName());
	}

	@NonNull
	private static McpRateLimitDecision toInternalRateLimitDecision(
			@NonNull RateLimitResult result) {
		if (result instanceof RateLimitResult.Allowed)
			return McpRateLimitDecision.allowed();
		if (result instanceof RateLimitResult.Denied denied)
			return McpRateLimitDecision.denied(denied.retryAfter());
		throw new IllegalArgumentException("Unsupported MCP rate-limit result.");
	}

	@NonNull
	private static McpRequestId toPublic(@NonNull McpJsonRpcId requestId) {
		if (requestId instanceof McpJsonRpcId.StringId stringId)
			return McpRequestId.fromString(stringId.value());
		if (requestId instanceof McpJsonRpcId.IntegerId integerId)
			return McpRequestId.fromInteger(integerId.value());
		throw new IllegalArgumentException("Unsupported MCP request ID.");
	}

	@NonNull
	private static McpAdmissionIdentity toPublic(
			com.soklet.internal.mcp.protocol.@NonNull McpAdmissionIdentity identity) {
		com.soklet.McpAdmissionIdentity.Builder builder =
				com.soklet.McpAdmissionIdentity.withRateLimitPartitionKey(
						identity.rateLimitPartitionKey().orElseThrow());
		identity.authorizationPartitionKey().ifPresent(
				builder::authorizationPartitionKey);
		identity.principal().ifPresent(builder::principal);
		identity.applicationContext().ifPresent(builder::applicationContext);
		return builder.build();
	}

	@NonNull
	private static McpImplementation toPublic(
			@NonNull McpImplementationMetadata metadata) {
		McpImplementation.Builder builder = McpImplementation.withNameAndVersion(
				metadata.name(), metadata.version());
		metadata.title().ifPresent(builder::title);
		metadata.description().ifPresent(builder::description);
		metadata.websiteUrl().ifPresent(builder::websiteUrl);
		return builder.build();
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpAdmissionDecision toInternal(
			@NonNull McpAdmissionDecision decision) {
		if (decision instanceof McpAdmissionDecision.Accepted accepted)
			return com.soklet.internal.mcp.protocol.McpAdmissionDecision.accepted(
					toInternal(accepted.identity()));
		if (decision instanceof McpAdmissionDecision.Rejected rejected)
			return com.soklet.internal.mcp.protocol.McpAdmissionDecision.rejected(
					toInternal(rejected.rejection()));
		throw new IllegalArgumentException("Unsupported MCP admission decision.");
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpAdmissionIdentity toInternal(
			@NonNull McpAdmissionIdentity identity) {
		com.soklet.internal.mcp.protocol.McpAdmissionIdentity.Builder builder =
				com.soklet.internal.mcp.protocol.McpAdmissionIdentity
						.withRateLimitPartitionKey(identity.getRateLimitPartitionKey());
		identity.getAuthorizationPartitionKey().ifPresent(builder::authorizationPartitionKey);
		identity.getPrincipal().ifPresent(builder::principal);
		identity.getApplicationContext().ifPresent(builder::applicationContext);
		return builder.build();
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpRequestRejection toInternal(
			@NonNull McpRequestRejection rejection) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		rejection.getHeaders().forEach((name, values) ->
				headers.put(name, List.copyOf(values)));
		com.soklet.McpJsonRpcError error = rejection.getJsonRpcError();
		return new com.soklet.internal.mcp.protocol.McpRequestRejection(
				rejection.getStatusCode(),
				new com.soklet.internal.mcp.protocol.McpJsonRpcError(
						error.getCode(), error.getMessage(), error.getData().map(
								McpServerRuntimeBridge::toInternal)),
				headers);
	}

	@NonNull
	private static McpJsonValue toPublic(
			com.soklet.internal.mcp.protocol.@NonNull McpJsonValue value) {
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonString string)
			return new McpJsonString(string.value());
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonNumber number)
			return new McpJsonNumber(number.value());
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonBoolean bool)
			return new McpJsonBoolean(
					bool == com.soklet.internal.mcp.protocol.McpJsonBoolean.TRUE);
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonNull)
			return McpJsonNull.INSTANCE;
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonArray array) {
			List<McpJsonValue> elements = new ArrayList<>();
			array.values().forEach(element -> elements.add(toPublic(element)));
			return McpJsonArray.fromElements(elements);
		}
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonObject object) {
			Map<String, McpJsonValue> members = new LinkedHashMap<>();
			object.members().forEach((name, member) -> members.put(name, toPublic(member)));
			return McpJsonObject.fromMembers(members);
		}
		throw new IllegalArgumentException("Unsupported internal MCP JSON value.");
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpJsonValue toInternal(
			@NonNull McpJsonValue value) {
		if (value instanceof McpJsonString string)
			return new com.soklet.internal.mcp.protocol.McpJsonString(string.value());
		if (value instanceof McpJsonNumber number)
			return new com.soklet.internal.mcp.protocol.McpJsonNumber(number.value());
		if (value instanceof McpJsonBoolean bool)
			return com.soklet.internal.mcp.protocol.McpJsonBoolean
					.fromBoolean(bool.value());
		if (value instanceof McpJsonNull)
			return com.soklet.internal.mcp.protocol.McpJsonNull.INSTANCE;
		if (value instanceof McpJsonArray array) {
			List<com.soklet.internal.mcp.protocol.McpJsonValue> elements = new ArrayList<>();
			array.getElements().forEach(element -> elements.add(toInternal(element)));
			return new com.soklet.internal.mcp.protocol.McpJsonArray(elements);
		}
		if (value instanceof McpJsonObject object) {
			Map<String, com.soklet.internal.mcp.protocol.McpJsonValue> members =
					new LinkedHashMap<>();
			object.getMembers().forEach((name, member) ->
					members.put(name, toInternal(member)));
			return new com.soklet.internal.mcp.protocol.McpJsonObject(members);
		}
		throw new IllegalArgumentException("Unsupported public MCP JSON value.");
	}
}
