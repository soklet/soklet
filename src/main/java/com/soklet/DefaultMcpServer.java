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

import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.AdmissionInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.CachePlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.CacheScope;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.EndpointPlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.HandlerEntryGuard;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.PromptArgumentPlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.PromptInvocation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.PromptInvocationResult;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.PromptPlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RateLimitAdapter;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RateLimitInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RateLimitResult;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourceAddressKind;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourceInvocation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourceInvocationResult;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourceListInvocation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourceListInvocationResult;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourceListPlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourcePlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RuntimeState;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestError;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservationInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ToolInvocation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ToolInvocationResult;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ToolPlan;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Package-private built-in {@link McpServer} implementation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpServer implements McpServer {
	private static final int MAXIMUM_BASE64_CHARACTERS =
			maximumBase64Characters();
	private static final int MAXIMUM_AGGREGATE_BASE64_CHARACTERS =
			McpJsonLimits.productionDefaults().maximumOutputBytes();
	@NonNull
	private static final Set<@NonNull String> BOUNDED_METRIC_METHODS = Set.of(
			"server/discover", "tools/list", "tools/call", "prompts/list",
			"prompts/get", "resources/list", "resources/templates/list",
			"resources/read", "notifications/cancelled");
	@NonNull
	private final Object lifecycleLock;
	private final int maximumCursorSizeInBytes;
	@NonNull
	private final McpHandlerResolver handlerResolver;
	@NonNull
	private final McpRequestAdmissionPolicy requestAdmissionPolicy;
	@NonNull
	private final McpHandlerInterceptor handlerInterceptor;
	@NonNull
	private final McpToolOutputSanitizer toolOutputSanitizer;
	@Nullable
	private final McpRateLimiter requestRateLimiter;
	@Nullable
	private final McpRateLimiter toolRateLimiter;
	@NonNull
	private final McpRateLimiterRegistry rateLimiterRegistry;
	@NonNull
	private final CorsAuthorizer corsAuthorizer;
	@NonNull
	private final McpServerRuntimeBridge runtimeBridge;
	@NonNull
	private volatile LifecycleObserver lifecycleObserver;
	@NonNull
	private volatile MetricsCollector metricsCollector;
	@NonNull
	private volatile McpShutdownOutcome lastShutdownOutcome;

	DefaultMcpServer(int port, @NonNull String host,
			int maximumCursorSizeInBytes,
			int requestHandlerConcurrency, int requestHandlerQueueCapacity,
			@NonNull Duration requestTimeout,
			@Nullable Supplier<@NonNull ExecutorService>
					requestHandlerExecutorServiceSupplier,
			@NonNull McpHandlerResolver handlerResolver,
			@NonNull McpRequestAdmissionPolicy requestAdmissionPolicy,
			@NonNull McpHandlerInterceptor handlerInterceptor,
			@NonNull McpToolOutputSanitizer toolOutputSanitizer,
			@Nullable CorsAuthorizer configuredCorsAuthorizer,
			@NonNull McpAbsentOriginPolicy absentOriginPolicy,
			@NonNull McpUnknownMirroredHeaderPolicy unknownMirroredHeaderPolicy,
			@NonNull Set<@NonNull String> allowedHosts,
			@Nullable McpRateLimiter requestRateLimiter,
			@Nullable McpRateLimiter toolRateLimiter,
			@NonNull McpRateLimiterRegistry rateLimiterRegistry) {
		this.lifecycleLock = new Object();
		this.maximumCursorSizeInBytes = maximumCursorSizeInBytes;
		this.handlerResolver = requireNonNull(handlerResolver);
		this.requestAdmissionPolicy = requireNonNull(requestAdmissionPolicy);
		this.handlerInterceptor = requireNonNull(handlerInterceptor);
		this.toolOutputSanitizer = requireNonNull(toolOutputSanitizer);
		this.requestRateLimiter = requestRateLimiter;
		this.toolRateLimiter = toolRateLimiter;
		this.rateLimiterRegistry = requireNonNull(rateLimiterRegistry);
		requireNonNull(unknownMirroredHeaderPolicy);
		boolean corsAuthorizerExplicitlyConfigured = configuredCorsAuthorizer != null;
		this.corsAuthorizer = configuredCorsAuthorizer == null
				? CorsAuthorizer.rejectAllInstance() : configuredCorsAuthorizer;
		this.lifecycleObserver = LifecycleObserver.defaultInstance();
		this.metricsCollector = MetricsCollector.disabledInstance();
		this.lastShutdownOutcome = McpShutdownOutcome.CLEAN;
		List<EndpointPlan> endpointPlans = handlerResolver.getEndpoints().stream()
				.map(this::toEndpointPlan)
				.toList();
		this.runtimeBridge = new McpServerRuntimeBridge(host, port, endpointPlans,
				allowedHosts, absentOriginPolicy == McpAbsentOriginPolicy.REQUIRE_ORIGIN,
				this.corsAuthorizer, corsAuthorizerExplicitlyConfigured,
				input -> this.requestAdmissionPolicy.admit(
						new DefaultMcpAdmissionContext(input)),
				Optional.ofNullable(this.requestRateLimiter)
						.map(DefaultMcpServer::toRateLimitAdapter),
				unknownMirroredHeaderPolicy,
				requestHandlerConcurrency, requestHandlerQueueCapacity,
				requestTimeout,
				Optional.ofNullable(requestHandlerExecutorServiceSupplier),
				this::safelyLogStartupDiagnostic,
				this::safelyLogUnexpectedTermination,
				this::didStartRequestObservation);
	}

	@NonNull
	private EndpointPlan toEndpointPlan(@NonNull McpEndpoint endpoint) {
		List<ToolPlan> toolPlans = endpoint.getTools().stream()
				.map(tool -> toToolPlan(endpoint, tool))
				.toList();
		List<PromptPlan> promptPlans = endpoint.getPrompts().stream()
				.map(this::toPromptPlan)
				.toList();
		List<ResourcePlan> resourcePlans = endpoint.getResources().stream()
				.map(this::toResourcePlan)
				.toList();
		List<McpResourceDescriptor> registeredResourceDescriptors = endpoint
				.getResources().stream()
				.filter(resource -> resource.getAddressType()
						== McpResourceAddressType.URI)
				.map(DefaultMcpServer::toResourceDescriptor)
				.toList();
		ResourceListPlan resourceListPlan = new ResourceListPlan(
				toCachePlan(endpoint.getResourcesListCachePolicy()),
				toCachePlan(endpoint.getResourceTemplatesListCachePolicy()),
				this.maximumCursorSizeInBytes,
				endpoint.getResourceListHandler().map(handler -> invocation ->
						invokeResourceList(handler, registeredResourceDescriptors,
								invocation)));
		return new EndpointPlan(endpoint, toolPlans, promptPlans, resourcePlans,
				resourceListPlan);
	}

	void initialize(@NonNull SokletConfig sokletConfig) {
		SokletConfig configuration = requireNonNull(sokletConfig);
		this.lifecycleObserver = configuration.getAggregateLifecycleObserver();
		this.metricsCollector = configuration.getMetricsCollector();
	}

	@Override
	public void start() {
		synchronized (this.lifecycleLock) {
			RuntimeState runtimeState = this.runtimeBridge.getRuntimeState();
			if (runtimeState.started())
				return;
			if (runtimeState.stopRequired()) {
				boolean residualHandlers = this.runtimeBridge
						.stopAndReportResidualHandlers();
				this.lastShutdownOutcome = residualHandlers
						? McpShutdownOutcome.RESIDUAL_HANDLERS
						: McpShutdownOutcome.CLEAN;
			}
			try {
				this.runtimeBridge.start();
				this.lastShutdownOutcome = McpShutdownOutcome.CLEAN;
			} catch (IOException exception) {
				throw new UncheckedIOException("Unable to start the MCP server.", exception);
			}
		}
	}

	@Override
	public void stop() {
		stopForSoklet();
	}

	@NonNull
	McpShutdownOutcome stopForSoklet() {
		synchronized (this.lifecycleLock) {
			if (!this.runtimeBridge.getRuntimeState().stopRequired())
				return this.lastShutdownOutcome;
			boolean residualHandlers = this.runtimeBridge
					.stopAndReportResidualHandlers();
			this.lastShutdownOutcome = residualHandlers
					? McpShutdownOutcome.RESIDUAL_HANDLERS : McpShutdownOutcome.CLEAN;
			return this.lastShutdownOutcome;
		}
	}

	boolean requiresStop() {
		synchronized (this.lifecycleLock) {
			return this.runtimeBridge.getRuntimeState().stopRequired();
		}
	}

	@Override
	public boolean isStarted() {
		synchronized (this.lifecycleLock) {
			return this.runtimeBridge.getRuntimeState().started();
		}
	}

	@Override
	@NonNull
	public McpHandlerResolver getHandlerResolver() {
		return this.handlerResolver;
	}

	@Override
	@NonNull
	public McpRequestAdmissionPolicy getRequestAdmissionPolicy() {
		return this.requestAdmissionPolicy;
	}

	@Override
	@NonNull
	public McpHandlerInterceptor getHandlerInterceptor() {
		return this.handlerInterceptor;
	}

	@Override
	@NonNull
	public McpToolOutputSanitizer getToolOutputSanitizer() {
		return this.toolOutputSanitizer;
	}

	@Override
	@NonNull
	public Optional<@NonNull McpRateLimiter> getRequestRateLimiter() {
		return Optional.ofNullable(this.requestRateLimiter);
	}

	@Override
	@NonNull
	public Optional<@NonNull McpRateLimiter> getToolRateLimiter() {
		return Optional.ofNullable(this.toolRateLimiter);
	}

	@Override
	@NonNull
	public McpRateLimiterRegistry getRateLimiterRegistry() {
		return this.rateLimiterRegistry;
	}

	@Override
	@NonNull
	public CorsAuthorizer getCorsAuthorizer() {
		return this.corsAuthorizer;
	}

	@Override
	public int getMaximumCursorSizeInBytes() {
		return this.maximumCursorSizeInBytes;
	}

	@Override
	@NonNull
	public McpServerDiagnostics getDiagnostics() {
		synchronized (this.lifecycleLock) {
			RuntimeState runtimeState = this.runtimeBridge.getRuntimeState();
			McpServerStatus status = runtimeState.started()
					? McpServerStatus.STARTED
					: runtimeState.residualHandlers()
							? McpServerStatus.STOPPED_WITH_RESIDUAL_HANDLERS
							: McpServerStatus.STOPPED;
			return new DefaultMcpServerDiagnostics(status,
					runtimeState.boundAddress());
		}
	}

	@NonNull
	private <A> ToolPlan toToolPlan(@NonNull McpEndpoint endpoint,
			@NonNull McpToolRegistration<A> tool) {
		McpRateLimiter resolvedRateLimiter = resolveToolRateLimiter(endpoint, tool);
		return new ToolPlan(tool.getName(), tool.getInputSchema().getDocument(),
				tool.getMirroredHeaderPlan(),
				tool.getOutputSchema().map(McpSchema::getDocument),
				toolDescriptorFields(tool), tool.getMetadata(),
				tool.isStructuredContentTextMirroringEnabled(),
				toRateLimitAdapter(resolvedRateLimiter),
				invocation -> invokeTool(tool, invocation));
	}

	@NonNull
	private PromptPlan toPromptPlan(@NonNull McpPromptRegistration prompt) {
		List<PromptArgumentPlan> arguments = prompt.getArguments().stream()
				.map(argument -> new PromptArgumentPlan(argument.getName(),
						argument.isRequired(),
						promptArgumentDescriptorFields(argument)))
				.toList();
		return new PromptPlan(prompt.getName(), arguments,
				promptDescriptorFields(prompt), prompt.getMetadata(),
				invocation -> invokePrompt(prompt, invocation));
	}

	@NonNull
	private ResourcePlan toResourcePlan(
			@NonNull McpResourceRegistration resource) {
		ResourceAddressKind addressKind;
		String address;
		if (resource.getAddressType() == McpResourceAddressType.URI) {
			addressKind = ResourceAddressKind.URI;
			address = resource.getUri().orElseThrow().toString();
		} else {
			addressKind = ResourceAddressKind.URI_TEMPLATE;
			address = resource.getUriTemplate().orElseThrow();
		}
		return new ResourcePlan(addressKind, address, resource.getName(),
				resourceDescriptorFields(resource), resource.getMetadata(),
				toCachePlan(resource.getCachePolicy()),
				invocation -> invokeResource(resource, invocation));
	}

	@NonNull
	private static CachePlan toCachePlan(@NonNull McpCachePolicy cachePolicy) {
		return new CachePlan(cachePolicy.getTimeToLive().toMillis(),
				cachePolicy.getScope() == McpCacheScope.PUBLIC
						? CacheScope.PUBLIC : CacheScope.PRIVATE);
	}

	@NonNull
	private McpRateLimiter resolveToolRateLimiter(@NonNull McpEndpoint endpoint,
			@NonNull McpToolRegistration<?> tool) {
		Optional<McpRateLimiter> resolved = tool.getRateLimiter()
				.or(() -> tool.getRateLimiterName()
						.flatMap(this.rateLimiterRegistry::find))
				.or(endpoint::getToolRateLimiter)
				.or(() -> endpoint.getToolRateLimiterName()
						.flatMap(this.rateLimiterRegistry::find))
				.or(() -> Optional.ofNullable(this.toolRateLimiter));
		return resolved.orElseThrow(() -> new IllegalStateException(
				"No MCP tool rate limiter resolved for tool '"
						+ tool.getName() + "'."));
	}

	@NonNull
	private static RateLimitAdapter toRateLimitAdapter(
			@NonNull McpRateLimiter rateLimiter) {
		requireNonNull(rateLimiter);
		return input -> toRateLimitResult(requireNonNull(
				rateLimiter.acquire(new DefaultMcpRateLimitContext(input)),
				"The MCP rate limiter returned null."));
	}

	@NonNull
	private static RateLimitResult toRateLimitResult(
			@NonNull McpRateLimitDecision decision) {
		if (decision instanceof McpRateLimitDecision.Allowed)
			return RateLimitResult.allowed();
		if (decision instanceof McpRateLimitDecision.Denied denied)
			return RateLimitResult.denied(denied.retryAfter());
		throw new IllegalArgumentException("Unsupported MCP rate-limit decision.");
	}

	@NonNull
	private <A> ToolInvocationResult invokeTool(
			@NonNull McpToolRegistration<A> tool,
			@NonNull ToolInvocation invocation) throws Exception {
		McpRequestContext requestContext = invocation.requestContext();
		McpOperationResult result;
		try {
			result = interceptHandler(requestContext, invocation.handlerEntryGuard(),
					() -> tool.invoke(
							requestContext, invocation.rawArguments(),
							McpInvocationFeatures.fromFeatures(Map.of())));
		} catch (McpInvalidToolArgumentsException exception) {
			return ToolInvocationResult.invalidInput();
		}

		if (!(result instanceof McpCompleteResult completeResult))
			throw new IllegalArgumentException(
					"Unsupported MCP tool result implementation: "
							+ result.getClass().getName());
		if (!(completeResult.getPayload() instanceof McpToolOutput output))
			throw new IllegalArgumentException(
					"An MCP tool handler must return tool output.");
		McpToolOutput sanitizedOutput = requireNonNull(
				this.toolOutputSanitizer.sanitize(requestContext, tool.getName(),
						invocation.rawArguments(), output),
				"The MCP tool-output sanitizer returned null.");

		Optional<McpJsonValue> structuredContent =
				sanitizedOutput.getStructuredContent();
		if (structuredContent.isPresent()
				&& !tool.isStructuredOutputValid(structuredContent.orElseThrow()))
			throw new IllegalArgumentException(
					"MCP structured tool output does not satisfy its output schema.");
		if (structuredContent.isPresent()
				&& sanitizedOutput.getContent().isEmpty()
				&& !sanitizedOutput.isError())
			return ToolInvocationResult.structured(structuredContent.orElseThrow(),
					completeResult.getMetadata());

		return ToolInvocationResult.complete(toolOutputFields(sanitizedOutput),
				completeResult.getMetadata());
	}

	@NonNull
	private PromptInvocationResult invokePrompt(
			@NonNull McpPromptRegistration prompt,
			@NonNull PromptInvocation invocation) throws Exception {
		McpRequestContext requestContext = invocation.requestContext();
		McpOperationResult result;
		try {
			result = interceptHandler(requestContext, invocation.handlerEntryGuard(),
					() -> prompt.invoke(
							requestContext, invocation.rawArguments(),
							McpInvocationFeatures.fromFeatures(Map.of())));
		} catch (McpInvalidPromptArgumentsException exception) {
			return PromptInvocationResult.invalidInput();
		}

		if (!(result instanceof McpCompleteResult completeResult))
			throw new IllegalArgumentException(
					"Unsupported MCP prompt result implementation: "
							+ result.getClass().getName());
		if (!(completeResult.getPayload() instanceof McpPromptOutput output))
			throw new IllegalArgumentException(
					"An MCP prompt handler must return prompt output.");

		return PromptInvocationResult.complete(promptOutputFields(output),
				completeResult.getMetadata());
	}

	@NonNull
	private ResourceInvocationResult invokeResource(
			@NonNull McpResourceRegistration resource,
			@NonNull ResourceInvocation invocation) throws Exception {
		McpRequestContext requestContext = invocation.requestContext();
		McpOperationResult result;
		try {
			result = interceptHandler(requestContext, invocation.handlerEntryGuard(),
					() -> {
						try {
							return requireNonNull(
									resource.getHandler().handle(requestContext,
											new DefaultMcpResourceReadContext(invocation),
											McpInvocationFeatures.fromFeatures(Map.of())),
									"The MCP resource handler returned null.");
						} catch (McpJsonRpcException exception) {
							throw new ApplicationHandlerJsonRpcException(
									exception.getError());
						}
					});
		} catch (ApplicationHandlerJsonRpcException exception) {
			McpJsonRpcError error = exception.getError();
			return ResourceInvocationResult.jsonRpcError(error.getCode(),
					error.getMessage(), error.getData());
		}

		if (!(result instanceof McpCompleteResult completeResult))
			throw new IllegalArgumentException(
					"Unsupported MCP resource result implementation: "
							+ result.getClass().getName());
		if (!(completeResult.getPayload() instanceof McpResourceOutput output))
			throw new IllegalArgumentException(
					"An MCP resource handler must return resource output.");

		return ResourceInvocationResult.complete(resourceOutputFields(output),
				completeResult.getMetadata());
	}

	@NonNull
	private McpOperationResult interceptHandler(
			@NonNull McpRequestContext requestContext,
			@NonNull HandlerEntryGuard handlerEntryGuard,
			@NonNull McpHandlerInvocation downstream) throws Exception {
		requireNonNull(requestContext);
		requireNonNull(handlerEntryGuard);
		requireNonNull(downstream);
		AtomicBoolean active = new AtomicBoolean(true);
		AtomicBoolean invoked = new AtomicBoolean();
		Thread interceptorThread = Thread.currentThread();
		McpOperationResult result;
		try {
			result = this.handlerInterceptor.interceptHandler(requestContext, () -> {
				if (!active.get())
					throw new IllegalStateException(
							"An MCP interceptor continuation cannot be invoked after interception returns.");
				if (Thread.currentThread() != interceptorThread)
					throw new IllegalStateException(
							"An MCP interceptor continuation must be invoked on the interceptor thread.");
				if (!invoked.compareAndSet(false, true))
					throw new IllegalStateException(
							"An MCP interceptor continuation may be invoked only once.");
				handlerEntryGuard.requireEntry();
				return requireNonNull(downstream.invoke(),
						"The MCP downstream handler returned null.");
			});
		} finally {
			active.set(false);
		}
		return requireNonNull(result,
				"The MCP handler interceptor returned null.");
	}

	@NonNull
	private ResourceListInvocationResult invokeResourceList(
			@NonNull McpResourceListHandler handler,
			@NonNull List<@NonNull McpResourceDescriptor> registeredDescriptors,
			@NonNull ResourceListInvocation invocation) throws Exception {
		McpRequestContext requestContext = invocation.requestContext();
		McpOperationResult result;
		try {
			result = interceptHandler(requestContext, invocation.handlerEntryGuard(),
					() -> {
						try {
							return requireNonNull(
									handler.handle(requestContext,
											new DefaultMcpResourceListContext(
													invocation.cursor(),
													registeredDescriptors),
											McpInvocationFeatures.fromFeatures(Map.of())),
									"The MCP resource-list handler returned null.");
						} catch (McpJsonRpcException exception) {
							throw new ApplicationHandlerJsonRpcException(
									exception.getError());
						}
					});
		} catch (ApplicationHandlerJsonRpcException exception) {
			McpJsonRpcError error = exception.getError();
			return ResourceListInvocationResult.jsonRpcError(error.getCode(),
					error.getMessage(), error.getData());
		}
		if (!(result instanceof McpResourcePage page))
			throw new IllegalArgumentException(
					"An MCP resource-list handler must return a resource page.");

		return ResourceListInvocationResult.complete(resourcePageFields(page),
				page.getMetadata());
	}

	/**
	 * Internal control signal that distinguishes an intentional JSON-RPC error
	 * thrown by an application handler from one thrown by its interceptor.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	private static final class ApplicationHandlerJsonRpcException
			extends Exception {
		private static final long serialVersionUID = 1L;
		@NonNull
		private final McpJsonRpcError error;

		private ApplicationHandlerJsonRpcException(
				@NonNull McpJsonRpcError error) {
			super(null, null, false, false);
			this.error = requireNonNull(error);
		}

		@NonNull
		private McpJsonRpcError getError() {
			return this.error;
		}
	}

	@NonNull
	private static McpJsonObject toolDescriptorFields(
			@NonNull McpToolRegistration<?> tool) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		tool.getTitle().ifPresent(value ->
				fields.put("title", new McpJsonString(value)));
		tool.getDescription().ifPresent(value ->
				fields.put("description", new McpJsonString(value)));
		if (!tool.getIcons().isEmpty())
			fields.put("icons", McpJsonArray.fromElements(tool.getIcons().stream()
					.map(DefaultMcpServer::iconToJson)
					.toList()));
		tool.getAnnotations().ifPresent(value ->
				fields.put("annotations", toolAnnotationsToJson(value)));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject promptDescriptorFields(
			@NonNull McpPromptRegistration prompt) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		prompt.getTitle().ifPresent(value ->
				fields.put("title", new McpJsonString(value)));
		prompt.getDescription().ifPresent(value ->
				fields.put("description", new McpJsonString(value)));
		if (!prompt.getIcons().isEmpty())
			fields.put("icons", McpJsonArray.fromElements(prompt.getIcons().stream()
					.map(DefaultMcpServer::iconToJson)
					.toList()));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject resourceDescriptorFields(
			@NonNull McpResourceRegistration resource) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		resource.getTitle().ifPresent(value ->
				fields.put("title", new McpJsonString(value)));
		resource.getDescription().ifPresent(value ->
				fields.put("description", new McpJsonString(value)));
		resource.getMimeType().ifPresent(value ->
				fields.put("mimeType", new McpJsonString(value)));
		if (!resource.getIcons().isEmpty())
			fields.put("icons", McpJsonArray.fromElements(resource.getIcons().stream()
					.map(DefaultMcpServer::iconToJson)
					.toList()));
		resource.getAnnotations().ifPresent(value ->
				fields.put("annotations", contentAnnotationsToJson(value)));
		resource.getSize().ifPresent(value ->
				fields.put("size", new McpJsonNumber(
						java.math.BigDecimal.valueOf(value))));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject resourceDescriptorFields(
			@NonNull McpResourceDescriptor resource) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		resource.getTitle().ifPresent(value ->
				fields.put("title", new McpJsonString(value)));
		resource.getDescription().ifPresent(value ->
				fields.put("description", new McpJsonString(value)));
		resource.getMimeType().ifPresent(value ->
				fields.put("mimeType", new McpJsonString(value)));
		if (!resource.getIcons().isEmpty())
			fields.put("icons", McpJsonArray.fromElements(resource.getIcons().stream()
					.map(DefaultMcpServer::iconToJson)
					.toList()));
		resource.getAnnotations().ifPresent(value ->
				fields.put("annotations", contentAnnotationsToJson(value)));
		resource.getSize().ifPresent(value ->
				fields.put("size", new McpJsonNumber(
						java.math.BigDecimal.valueOf(value))));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject promptArgumentDescriptorFields(
			@NonNull McpPromptArgumentDefinition argument) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		argument.getTitle().ifPresent(value ->
				fields.put("title", new McpJsonString(value)));
		argument.getDescription().ifPresent(value ->
				fields.put("description", new McpJsonString(value)));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject iconToJson(@NonNull McpIcon icon) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("src", new McpJsonString(icon.getSource().toString()));
		icon.getMimeType().ifPresent(value ->
				fields.put("mimeType", new McpJsonString(value)));
		if (!icon.getSizes().isEmpty())
			fields.put("sizes", McpJsonArray.fromElements(icon.getSizes().stream()
					.map(McpJsonString::new)
					.toList()));
		icon.getTheme().ifPresent(value -> fields.put("theme",
				new McpJsonString(value.name().toLowerCase(Locale.ROOT))));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject toolAnnotationsToJson(
			@NonNull McpToolAnnotations annotations) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		annotations.getTitle().ifPresent(value ->
				fields.put("title", new McpJsonString(value)));
		annotations.getReadOnlyHint().ifPresent(value ->
				fields.put("readOnlyHint", new McpJsonBoolean(value)));
		annotations.getDestructiveHint().ifPresent(value ->
				fields.put("destructiveHint", new McpJsonBoolean(value)));
		annotations.getIdempotentHint().ifPresent(value ->
				fields.put("idempotentHint", new McpJsonBoolean(value)));
		annotations.getOpenWorldHint().ifPresent(value ->
				fields.put("openWorldHint", new McpJsonBoolean(value)));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject toolOutputFields(@NonNull McpToolOutput output) {
		requireAggregateBinaryDataFitsOutput(output.getContent());
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("content", McpJsonArray.fromElements(output.getContent().stream()
				.map(DefaultMcpServer::contentBlockToJson)
				.toList()));
		output.getStructuredContent().ifPresent(value ->
				fields.put("structuredContent", value));
		if (output.isError())
			fields.put("isError", new McpJsonBoolean(true));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject promptOutputFields(
			@NonNull McpPromptOutput output) {
		requireAggregatePromptBinaryDataFitsOutput(output.getMessages());
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		output.getDescription().ifPresent(value ->
				fields.put("description", new McpJsonString(value)));
		fields.put("messages", McpJsonArray.fromElements(
				output.getMessages().stream()
						.map(DefaultMcpServer::promptMessageToJson)
						.toList()));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject resourceOutputFields(
			@NonNull McpResourceOutput output) {
		requireAggregateResourceBinaryDataFitsOutput(output.getContents());
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("contents", McpJsonArray.fromElements(output.getContents().stream()
				.map(DefaultMcpServer::resourceContentsToJson)
				.toList()));
		output.getCacheTimeToLiveOverride().ifPresent(value ->
				fields.put("ttlMs", new McpJsonNumber(
						java.math.BigDecimal.valueOf(value.toMillis()))));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject resourcePageFields(@NonNull McpResourcePage page) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("resources", McpJsonArray.fromElements(page.getResources().stream()
				.map(DefaultMcpServer::resourceDescriptorToJson)
				.toList()));
		page.getNextCursor().ifPresent(value ->
				fields.put("nextCursor", new McpJsonString(value)));
		page.getCacheTimeToLiveOverride().ifPresent(value ->
				fields.put("ttlMs", new McpJsonNumber(
						java.math.BigDecimal.valueOf(value.toMillis()))));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject resourceDescriptorToJson(
			@NonNull McpResourceDescriptor resource) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("uri", new McpJsonString(resource.getUri().toString()));
		fields.put("name", new McpJsonString(resource.getName()));
		fields.putAll(resourceDescriptorFields(resource).getMembers());
		if (!resource.getMetadata().getMembers().isEmpty())
			fields.put("_meta", resource.getMetadata());
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpResourceDescriptor toResourceDescriptor(
			@NonNull McpResourceRegistration resource) {
		McpResourceDescriptor.Builder builder = McpResourceDescriptor
				.withUriAndName(resource.getUri().orElseThrow(), resource.getName());
		resource.getTitle().ifPresent(builder::title);
		resource.getDescription().ifPresent(builder::description);
		resource.getMimeType().ifPresent(builder::mimeType);
		resource.getIcons().forEach(builder::icon);
		resource.getAnnotations().ifPresent(builder::annotations);
		resource.getSize().ifPresent(builder::size);
		builder.metadata(resource.getMetadata());
		return builder.build();
	}

	@NonNull
	private static McpJsonObject promptMessageToJson(
			@NonNull McpPromptMessage message) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("role", new McpJsonString(
				message.role().name().toLowerCase(Locale.ROOT)));
		fields.put("content", contentBlockToJson(message.content()));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject contentBlockToJson(
			@NonNull McpContentBlock content) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		if (content instanceof McpTextContent text) {
			fields.put("type", new McpJsonString("text"));
			fields.put("text", new McpJsonString(text.getText()));
			addContentAnnotationsAndMetadata(fields, text.getAnnotations(),
					text.getMetadata());
		} else if (content instanceof McpImageContent image) {
			fields.put("type", new McpJsonString("image"));
			requireBinaryDataFitsOutput(image.dataLength());
			fields.put("data", new McpJsonString(encodeBinaryData(image.getData())));
			fields.put("mimeType", new McpJsonString(image.getMimeType()));
			addContentAnnotationsAndMetadata(fields, image.getAnnotations(),
					image.getMetadata());
		} else if (content instanceof McpAudioContent audio) {
			fields.put("type", new McpJsonString("audio"));
			requireBinaryDataFitsOutput(audio.dataLength());
			fields.put("data", new McpJsonString(encodeBinaryData(audio.getData())));
			fields.put("mimeType", new McpJsonString(audio.getMimeType()));
			addContentAnnotationsAndMetadata(fields, audio.getAnnotations(),
					audio.getMetadata());
		} else if (content instanceof McpResourceLink link) {
			fields.put("type", new McpJsonString("resource_link"));
			fields.put("uri", new McpJsonString(link.getUri().toString()));
			fields.put("name", new McpJsonString(link.getName()));
			link.getTitle().ifPresent(value ->
					fields.put("title", new McpJsonString(value)));
			link.getDescription().ifPresent(value ->
					fields.put("description", new McpJsonString(value)));
			link.getMimeType().ifPresent(value ->
					fields.put("mimeType", new McpJsonString(value)));
			if (!link.getIcons().isEmpty())
				fields.put("icons", McpJsonArray.fromElements(link.getIcons().stream()
						.map(DefaultMcpServer::iconToJson)
						.toList()));
			link.getSize().ifPresent(value ->
					fields.put("size", new McpJsonNumber(
							java.math.BigDecimal.valueOf(value))));
			addContentAnnotationsAndMetadata(fields, link.getAnnotations(),
					link.getMetadata());
		} else if (content instanceof McpEmbeddedResource embedded) {
			fields.put("type", new McpJsonString("resource"));
			fields.put("resource", resourceContentsToJson(embedded.getResource()));
			addContentAnnotationsAndMetadata(fields, embedded.getAnnotations(),
					embedded.getMetadata());
		} else {
			throw new IllegalArgumentException(
					"Unsupported MCP content block: " + content.getClass().getName());
		}
		return McpJsonObject.fromMembers(fields);
	}

	private static void addContentAnnotationsAndMetadata(
			@NonNull Map<String, McpJsonValue> fields,
			@NonNull Optional<McpContentAnnotations> annotations,
			@NonNull McpJsonObject metadata) {
		annotations.ifPresent(value ->
				fields.put("annotations", contentAnnotationsToJson(value)));
		if (!metadata.getMembers().isEmpty())
			fields.put("_meta", metadata);
	}

	@NonNull
	private static McpJsonObject contentAnnotationsToJson(
			@NonNull McpContentAnnotations annotations) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		if (!annotations.getAudience().isEmpty())
			fields.put("audience", McpJsonArray.fromElements(
					annotations.getAudience().stream()
							.map(role -> new McpJsonString(
									role.name().toLowerCase(Locale.ROOT)))
							.toList()));
		annotations.getPriority().ifPresent(value -> fields.put("priority",
				new McpJsonNumber(java.math.BigDecimal.valueOf(value))));
		annotations.getLastModified().ifPresent(value -> fields.put(
				"lastModified", new McpJsonString(value.toString())));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject resourceContentsToJson(
			@NonNull McpResourceContents contents) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("uri", new McpJsonString(contents.getUri().toString()));
		contents.getMimeType().ifPresent(value ->
				fields.put("mimeType", new McpJsonString(value)));
		if (contents instanceof McpTextResourceContents text)
			fields.put("text", new McpJsonString(text.getText()));
		else if (contents instanceof McpBlobResourceContents blob) {
			requireBinaryDataFitsOutput(blob.dataLength());
			fields.put("blob", new McpJsonString(encodeBinaryData(blob.getData())));
		} else
			throw new IllegalArgumentException(
					"Unsupported MCP resource contents: "
							+ contents.getClass().getName());
		if (!contents.getMetadata().getMembers().isEmpty())
			fields.put("_meta", contents.getMetadata());
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static String encodeBinaryData(byte @NonNull [] data) {
		requireBinaryDataFitsOutput(data.length);
		return Base64.getEncoder().encodeToString(data);
	}

	private static void requireBinaryDataFitsOutput(int dataLength) {
		long encodedLength = base64EncodedLength(dataLength);
		if (encodedLength > MAXIMUM_BASE64_CHARACTERS)
			throw new IllegalArgumentException(
					"Base64-encoded MCP binary data cannot fit within the JSON output bound.");
	}

	private static void requireAggregateBinaryDataFitsOutput(
			@NonNull List<@NonNull McpContentBlock> contents) {
		long encodedCharacters = 0L;
		for (McpContentBlock content : contents) {
			if (content instanceof McpImageContent image)
				encodedCharacters += base64EncodedLength(image.dataLength());
			else if (content instanceof McpAudioContent audio)
				encodedCharacters += base64EncodedLength(audio.dataLength());
			else if (content instanceof McpEmbeddedResource embedded
					&& embedded.getResource() instanceof McpBlobResourceContents blob)
				encodedCharacters += base64EncodedLength(blob.dataLength());
			requireAggregateBinaryDataFitsOutput(encodedCharacters);
		}
	}

	private static void requireAggregatePromptBinaryDataFitsOutput(
			@NonNull List<@NonNull McpPromptMessage> messages) {
		long encodedCharacters = 0L;
		for (McpPromptMessage message : messages) {
			McpContentBlock content = message.content();
			if (content instanceof McpImageContent image)
				encodedCharacters += base64EncodedLength(image.dataLength());
			else if (content instanceof McpAudioContent audio)
				encodedCharacters += base64EncodedLength(audio.dataLength());
			else if (content instanceof McpEmbeddedResource embedded
					&& embedded.getResource() instanceof McpBlobResourceContents blob)
				encodedCharacters += base64EncodedLength(blob.dataLength());
			requireAggregateBinaryDataFitsOutput(encodedCharacters);
		}
	}

	private static void requireAggregateResourceBinaryDataFitsOutput(
			@NonNull List<@NonNull McpResourceContents> contents) {
		long encodedCharacters = 0L;
		for (McpResourceContents content : contents) {
			if (content instanceof McpBlobResourceContents blob)
				encodedCharacters += base64EncodedLength(blob.dataLength());
			requireAggregateBinaryDataFitsOutput(encodedCharacters);
		}
	}

	private static void requireAggregateBinaryDataFitsOutput(
			long encodedCharacters) {
		if (encodedCharacters > MAXIMUM_AGGREGATE_BASE64_CHARACTERS)
			throw new IllegalArgumentException(
					"Combined Base64-encoded MCP binary data cannot fit within the JSON output bound.");
	}

	private static long base64EncodedLength(int dataLength) {
		return 4L * ((dataLength + 2L) / 3L);
	}

	private static int maximumBase64Characters() {
		McpJsonLimits limits = McpJsonLimits.productionDefaults();
		return Math.min(limits.maximumStringLengthInCharacters(),
				Math.min(limits.maximumTokenLengthInCharacters(),
						limits.maximumOutputBytes() - 2));
	}

	@NonNull
	private RequestObservation didStartRequestObservation(
			@NonNull RequestObservationInput input) {
		DefaultMcpRequestContext context = new DefaultMcpRequestContext(
				requireNonNull(input));
		LifecycleObserver observer = this.lifecycleObserver;
		MetricsCollector collector = this.metricsCollector;
		List<Throwable> startThrowables = new ArrayList<>();

		try {
			observer.didStartMcpRequestHandling(context);
		} catch (Throwable throwable) {
			startThrowables.add(throwable);
			safelyLogRequestObservation(observer, LogEvent.with(
					LogEventType.LIFECYCLE_OBSERVER_DID_START_MCP_REQUEST_HANDLING_FAILED,
					"An exception occurred while invoking LifecycleObserver::didStartMcpRequestHandling")
					.throwable(throwable)
					.request(context.getRequest())
					.build(), startThrowables);
		}
		safelyRecordMcpMetrics(collector, observer,
				new McpMetricsEvent.RequestStarted(input.endpoint().getPath(),
						metricMethod(input.jsonRpcMethod())), context);

		List<Throwable> immutableStartThrowables = List.copyOf(startThrowables);
		return new RequestObservation() {
			@NonNull
			private final AtomicBoolean finished = new AtomicBoolean();

			@Override
			@NonNull
			public McpRequestContext context() {
				return context;
			}

			@Override
			public void didFinish(@NonNull McpRequestOutcome outcome,
					@Nullable RequestError error, @NonNull Duration duration,
					@NonNull List<@NonNull Throwable> throwables) {
				requireNonNull(outcome);
				requireNonNull(duration);
				requireNonNull(throwables);
				if (!this.finished.compareAndSet(false, true))
					return;

				List<Throwable> allThrowables = new ArrayList<>(
						immutableStartThrowables.size() + throwables.size());
				allThrowables.addAll(immutableStartThrowables);
				allThrowables.addAll(throwables);
				List<Throwable> immutableThrowables = List.copyOf(allThrowables);
				McpJsonRpcError publicError = error == null ? null
						: McpJsonRpcError.fromServer(error.code(), error.message(),
								error.data().orElse(null));

				safelyRecordMcpMetrics(collector, observer,
						new McpMetricsEvent.RequestFinished(
								input.endpoint().getPath(),
								metricMethod(input.jsonRpcMethod()),
								outcome, duration), context);
				try {
					observer.didFinishMcpRequestHandling(context, outcome,
							publicError, duration, immutableThrowables);
				} catch (Throwable throwable) {
					safelyLogRequestObservation(observer, LogEvent.with(
							LogEventType.LIFECYCLE_OBSERVER_DID_FINISH_MCP_REQUEST_HANDLING_FAILED,
							"An exception occurred while invoking LifecycleObserver::didFinishMcpRequestHandling")
							.throwable(throwable)
							.request(context.getRequest())
							.build(), null);
				}
			}
		};
	}

	@NonNull
	private static String metricMethod(@NonNull String jsonRpcMethod) {
		return BOUNDED_METRIC_METHODS.contains(requireNonNull(jsonRpcMethod))
				? jsonRpcMethod : McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD;
	}

	private void safelyRecordMcpMetrics(@NonNull MetricsCollector collector,
			@NonNull LifecycleObserver observer, @NonNull McpMetricsEvent event,
			@NonNull McpRequestContext context) {
		try {
			collector.didRecordMcpMetricsEvent(requireNonNull(event));
		} catch (Throwable throwable) {
			safelyLogRequestObservation(observer, LogEvent.with(
					LogEventType.METRICS_COLLECTOR_FAILED,
					"An exception occurred while invoking MetricsCollector::didRecordMcpMetricsEvent")
					.throwable(throwable)
					.request(context.getRequest())
					.build(), null);
		}
	}

	private void safelyLogRequestObservation(@NonNull LifecycleObserver observer,
			@NonNull LogEvent event,
			@Nullable List<@NonNull Throwable> requestThrowables) {
		try {
			observer.didReceiveLogEvent(requireNonNull(event));
		} catch (Throwable throwable) {
			if (requestThrowables != null)
				requestThrowables.add(throwable);
		}
	}

	private void safelyLogStartupDiagnostic(@NonNull String message) {
		try {
			this.lifecycleObserver.didReceiveLogEvent(LogEvent.with(
					LogEventType.MCP_SERVER_CONFIGURATION, message).build());
		} catch (Throwable ignored) {
			// Informational diagnostics must not change server availability.
		}
	}

	private void safelyLogUnexpectedTermination(@NonNull Throwable throwable) {
		try {
			this.lifecycleObserver.didReceiveLogEvent(LogEvent.with(
					LogEventType.SERVER_TRANSPORT_FAILURE,
					"MCP transport failure: event_loop_terminate")
					.throwable(throwable)
					.build());
		} catch (Throwable ignored) {
			// Failure reporting must not interfere with runtime cleanup.
		}
	}
}

/**
 * Immutable built-in MCP diagnostics snapshot.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record DefaultMcpServerDiagnostics(@NonNull McpServerStatus status,
		@NonNull Optional<@NonNull InetSocketAddress> boundAddress)
		implements McpServerDiagnostics {
	DefaultMcpServerDiagnostics {
		requireNonNull(status);
		boundAddress = requireNonNull(boundAddress).map(address ->
				new InetSocketAddress(address.getAddress(), address.getPort()));
		if ((status == McpServerStatus.STARTED) != boundAddress.isPresent())
			throw new IllegalArgumentException(
					"A STARTED MCP server snapshot must have exactly one bound address.");
	}

	@Override
	@NonNull
	public McpServerStatus getStatus() {
		return this.status;
	}

	@Override
	@NonNull
	public Optional<@NonNull InetSocketAddress> getBoundAddress() {
		return this.boundAddress;
	}
}

/**
 * Immutable public admission-context projection over one internal request.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpAdmissionContext implements McpAdmissionContext {
	@NonNull
	private final AdmissionInput input;
	@NonNull
	private final Optional<@NonNull McpClientCapabilities> clientCapabilities;
	@NonNull
	private final McpRequestPropagation requestPropagation;

	DefaultMcpAdmissionContext(@NonNull AdmissionInput input) {
		this.input = requireNonNull(input);
		this.clientCapabilities = input.clientCapabilitiesJson()
				.map(McpClientCapabilities::fromJson);
		this.requestPropagation = input.requestMetadata()
				.map(McpRequestPropagation::fromMetadata)
				.orElseGet(() -> McpRequestPropagation.fromMetadata(
						McpJsonObject.emptyInstance()));
	}

	@Override public @NonNull Request getRequest() { return this.input.request(); }
	@Override public @NonNull McpEndpoint getEndpoint() { return this.input.endpoint(); }
	@Override public @NonNull Map<@NonNull String, @NonNull String>
	getEndpointPathParameters() {
		return this.input.endpointPathParameters();
	}
	@Override public @NonNull String getJsonRpcMethod() { return this.input.jsonRpcMethod(); }
	@Override public boolean isNotification() { return this.input.notification(); }
	@Override public @NonNull Optional<@NonNull McpRequestId> getRequestId() {
		return this.input.requestId();
	}
	@Override public @NonNull String getProtocolVersion() { return this.input.protocolVersion(); }
	@Override public @NonNull Optional<@NonNull String> getOperationName() {
		return this.input.operationName();
	}
	@Override public @NonNull Optional<@NonNull McpImplementation> getClientInfo() {
		return this.input.clientInformation();
	}
	@Override public @NonNull Optional<@NonNull McpClientCapabilities>
	getClientCapabilities() {
		return this.clientCapabilities;
	}
	@Override public @NonNull Optional<@NonNull TraceContext> getTraceContext() {
		return this.requestPropagation.traceContext();
	}
}

/**
 * Immutable public rate-limit context projection.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpRateLimitContext implements McpRateLimitContext {
	@NonNull
	private final RateLimitInput input;

	DefaultMcpRateLimitContext(@NonNull RateLimitInput input) {
		this.input = requireNonNull(input);
	}

	@Override public @NonNull Request getRequest() { return this.input.request(); }
	@Override public @NonNull McpEndpoint getEndpoint() { return this.input.endpoint(); }
	@Override public @NonNull McpAdmissionIdentity getAdmissionIdentity() {
		return this.input.admissionIdentity();
	}
	@Override public @NonNull McpRateLimitTarget getTarget() {
		return switch (this.input.target()) {
			case REQUEST -> McpRateLimitTarget.REQUEST;
			case TOOL -> McpRateLimitTarget.TOOL;
		};
	}
	@Override public @NonNull String getJsonRpcMethod() {
		return this.input.jsonRpcMethod();
	}
	@Override public @NonNull Optional<@NonNull String> getOperationName() {
		return this.input.operationName();
	}
}

/**
 * Immutable public application-handler request context projection.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@SuppressWarnings("deprecation")
final class DefaultMcpRequestContext implements McpRequestContext {
	@NonNull
	private static final String DEPRECATED_LOG_LEVEL_KEY =
			"io.modelcontextprotocol/logLevel";
	@NonNull
	private final Request request;
	@NonNull
	private final McpEndpoint endpoint;
	@NonNull
	private final Map<@NonNull String, @NonNull String> endpointPathParameters;
	@NonNull
	private final String jsonRpcMethod;
	@NonNull
	private final Optional<@NonNull McpRequestId> requestId;
	@NonNull
	private final String protocolVersion;
	@NonNull
	private final Optional<@NonNull String> operationName;
	@NonNull
	private final Optional<@NonNull McpImplementation> clientInformation;
	@NonNull
	private final McpJsonObject requestMetadata;
	@NonNull
	private final McpAdmissionIdentity admissionIdentity;
	@NonNull
	private final McpClientCapabilities clientCapabilities;
	@NonNull
	private final Optional<@NonNull McpLogLevel> deprecatedLogLevel;
	@NonNull
	private final McpRequestPropagation requestPropagation;

	@SuppressWarnings("deprecation")
	DefaultMcpRequestContext(@NonNull ToolInvocation invocation) {
		this(requireNonNull(invocation).request(), invocation.endpoint(),
				invocation.endpointPathParameters(), invocation.jsonRpcMethod(),
				Optional.of(invocation.requestId()), invocation.protocolVersion(),
				Optional.of(invocation.operationName()),
				invocation.clientInformation(), invocation.clientCapabilitiesJson(),
				invocation.requestMetadata(), invocation.admissionIdentity());
	}

	@SuppressWarnings("deprecation")
	DefaultMcpRequestContext(@NonNull PromptInvocation invocation) {
		this(requireNonNull(invocation).request(), invocation.endpoint(),
				invocation.endpointPathParameters(), invocation.jsonRpcMethod(),
				Optional.of(invocation.requestId()), invocation.protocolVersion(),
				Optional.of(invocation.operationName()),
				invocation.clientInformation(), invocation.clientCapabilitiesJson(),
				invocation.requestMetadata(), invocation.admissionIdentity());
	}

	@SuppressWarnings("deprecation")
	DefaultMcpRequestContext(@NonNull ResourceInvocation invocation) {
		this(requireNonNull(invocation).request(), invocation.endpoint(),
				invocation.endpointPathParameters(), invocation.jsonRpcMethod(),
				Optional.of(invocation.requestId()), invocation.protocolVersion(),
				Optional.of(invocation.operationName()),
				invocation.clientInformation(), invocation.clientCapabilitiesJson(),
				invocation.requestMetadata(), invocation.admissionIdentity());
	}

	@SuppressWarnings("deprecation")
	DefaultMcpRequestContext(@NonNull ResourceListInvocation invocation) {
		this(requireNonNull(invocation).request(), invocation.endpoint(),
				invocation.endpointPathParameters(), invocation.jsonRpcMethod(),
				Optional.of(invocation.requestId()), invocation.protocolVersion(),
				Optional.empty(),
				invocation.clientInformation(), invocation.clientCapabilitiesJson(),
				invocation.requestMetadata(), invocation.admissionIdentity());
	}

	@SuppressWarnings("deprecation")
	DefaultMcpRequestContext(@NonNull RequestObservationInput input) {
		this(requireNonNull(input).request(), input.endpoint(),
				input.endpointPathParameters(), input.jsonRpcMethod(),
				input.requestId(), input.protocolVersion(), input.operationName(),
				input.clientInformation(), input.clientCapabilities(),
				input.requestMetadata(), input.admissionIdentity());
	}

	@SuppressWarnings("deprecation")
	private DefaultMcpRequestContext(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod,
			@NonNull Optional<@NonNull McpRequestId> requestId,
			@NonNull String protocolVersion,
			@NonNull Optional<@NonNull String> operationName,
			@NonNull Optional<@NonNull McpImplementation> clientInformation,
			@NonNull McpJsonObject clientCapabilitiesJson,
			@NonNull McpJsonObject requestMetadata,
			@NonNull McpAdmissionIdentity admissionIdentity) {
		this.request = requireNonNull(request);
		this.endpoint = requireNonNull(endpoint);
		this.endpointPathParameters = Map.copyOf(
				requireNonNull(endpointPathParameters));
		this.jsonRpcMethod = requireNonNull(jsonRpcMethod);
		this.requestId = requireNonNull(requestId);
		this.protocolVersion = requireNonNull(protocolVersion);
		this.operationName = requireNonNull(operationName);
		this.clientInformation = requireNonNull(clientInformation);
		this.requestMetadata = requireNonNull(requestMetadata);
		this.admissionIdentity = requireNonNull(admissionIdentity);
		this.clientCapabilities = McpClientCapabilities.fromJson(
				clientCapabilitiesJson);
		this.deprecatedLogLevel = requestMetadata
				.find(DEPRECATED_LOG_LEVEL_KEY)
				.filter(McpJsonString.class::isInstance)
				.map(McpJsonString.class::cast)
				.map(McpJsonString::value)
				.map(value -> McpLogLevel.valueOf(
						value.toUpperCase(Locale.ROOT)));
		this.requestPropagation = McpRequestPropagation.fromMetadata(
				requestMetadata);
	}

	@Override public @NonNull Request getRequest() {
		return this.request;
	}
	@Override public @NonNull McpEndpoint getEndpoint() {
		return this.endpoint;
	}
	@Override public @NonNull Map<@NonNull String, @NonNull String>
	getEndpointPathParameters() {
		return this.endpointPathParameters;
	}
	@Override public @NonNull String getJsonRpcMethod() {
		return this.jsonRpcMethod;
	}
	@Override public @NonNull Optional<@NonNull McpRequestId> getRequestId() {
		return this.requestId;
	}
	@Override public @NonNull String getProtocolVersion() {
		return this.protocolVersion;
	}
	@Override public @NonNull Optional<@NonNull String> getOperationName() {
		return this.operationName;
	}
	@Override public @NonNull Optional<@NonNull McpImplementation> getClientInfo() {
		return this.clientInformation;
	}
	@Override public @NonNull McpClientCapabilities getClientCapabilities() {
		return this.clientCapabilities;
	}
	@Override public @NonNull McpJsonObject getRequestMetadata() {
		return this.requestMetadata;
	}
	@Override
	@SuppressWarnings("deprecation")
	public @NonNull Optional<@NonNull McpLogLevel> getDeprecatedLogLevel() {
		return this.deprecatedLogLevel;
	}
	@Override public @NonNull Optional<@NonNull TraceContext> getTraceContext() {
		return this.requestPropagation.traceContext();
	}
	@Override public @NonNull Map<@NonNull String, @NonNull String> getBaggage() {
		return this.requestPropagation.baggage();
	}
	@Override public @NonNull McpAdmissionIdentity getAdmissionIdentity() {
		return this.admissionIdentity;
	}
}
