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
import com.soklet.internal.mcp.protocol.McpApplicationExecutionObserver;
import com.soklet.internal.mcp.protocol.McpApplicationExecutionObserver.PendingMetricRecord;
import com.soklet.internal.mcp.protocol.McpLocalizationContextUnavailableException;
import com.soklet.internal.mcp.protocol.McpRuntimeCatalogLocalizer;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.AdmissionInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.CachePlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.CacheScope;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.DiagnosticsState;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.EndpointPlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.HandlerEntryGuard;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.PromptArgumentPlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.PromptInvocation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.PromptInvocationResult;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.PromptPlan;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ProgressEmitter;
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
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.SimulationSession;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestError;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservationInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionAdapter;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionPlan;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
	static final String DEVELOPMENT_EPHEMERAL_PROTECTION_DIAGNOSTIC =
			"MCP development-ephemeral request-state protection is enabled; "
					+ "protected state is process-local and will not survive restarts "
					+ "or work across server instances.";
	@NonNull
	private static final Set<@NonNull String> BOUNDED_METRIC_METHODS = Set.of(
			"server/discover", "tools/list", "tools/call", "prompts/list",
			"prompts/get", "resources/list", "resources/templates/list",
			"resources/read", "subscriptions/listen", "notifications/cancelled");
	@NonNull
	private final Object lifecycleLock;
	private final int maximumCursorSizeInBytes;
	private final int maximumSubscriptionsPerPrincipal;
	private final int streamQueueCapacity;
	@NonNull
	private final Duration keepAliveInterval;
	@NonNull
	private final Duration maximumSubscriptionDuration;
	@NonNull
	private final Duration writeTimeout;
	private final boolean logRawValidatedTraceIds;
	@NonNull
	private final McpEndpointRegistry endpointRegistry;
	@NonNull
	private final McpAdmissionController admissionController;
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
	@Nullable
	private final McpProtectionConfig protectionConfig;
	@Nullable
	private final McpLocalizer localizer;
	@Nullable
	private final McpCanonicalLocalizationPlan localizationPlan;
	@NonNull
	private final DefaultMcpSecurityControls securityControls;
	@NonNull
	private final McpLocalizationControl localizationControl;
	@NonNull
	private final McpMetricEventDelivery mcpMetricEventDelivery;
	@NonNull
	private final McpServerRuntimeBridge runtimeBridge;
	@NonNull
	private final McpTransportLifecycleAdapter lifecycleAdapter;
	@NonNull
	private volatile LifecycleObserver lifecycleObserver;
	@NonNull
	private volatile MetricsCollector metricsCollector;
	@NonNull
	private volatile LifecyclePolicy lifecyclePolicy;
	@Nullable
	private volatile Object lifecycleExecutionOwner;
	private McpTransportLifecycleAdapter.@Nullable Generation
			pendingListenerGeneration;
	@Nullable
	private InternalParticipantShutdownResult ownerTerminalResult;
	private boolean attachmentStarted;
	private boolean terminalMetricEmitted;
	private boolean simulatorOwned;

	DefaultMcpServer(int port, @NonNull String host,
			int maximumCursorSizeInBytes,
			int requestHandlerConcurrency, int requestHandlerQueueCapacity,
			@NonNull Duration requestTimeout,
			@Nullable Supplier<@NonNull ExecutorService>
					requestHandlerExecutorServiceSupplier,
			int streamQueueCapacity, @NonNull Duration writeTimeout,
			@NonNull Duration keepAliveInterval,
			int maximumSubscriptionsPerPrincipal,
			@NonNull Duration maximumSubscriptionDuration,
			@NonNull McpEndpointRegistry endpointRegistry,
			@NonNull McpAdmissionController admissionController,
			@NonNull McpHandlerInterceptor handlerInterceptor,
			@NonNull McpToolOutputSanitizer toolOutputSanitizer,
			@Nullable CorsAuthorizer configuredCorsAuthorizer,
			@NonNull McpAbsentOriginPolicy absentOriginPolicy,
			@NonNull McpUnknownMirroredHeaderPolicy unknownMirroredHeaderPolicy,
			boolean unknownMirroredHeaderNameDiagnostics,
			boolean logRawValidatedTraceIds,
			@NonNull Set<@NonNull String> allowedHosts,
			@Nullable McpRateLimiter requestRateLimiter,
			@Nullable McpRateLimiter toolRateLimiter,
			@NonNull McpRateLimiterRegistry rateLimiterRegistry,
			@Nullable McpProtectionConfig protectionConfig,
			@Nullable McpTraceCorrelationKey traceCorrelationKey,
			@Nullable McpLocalizer localizer) {
		this.lifecycleLock = new Object();
		this.maximumCursorSizeInBytes = maximumCursorSizeInBytes;
		this.maximumSubscriptionsPerPrincipal =
				maximumSubscriptionsPerPrincipal;
		this.streamQueueCapacity = streamQueueCapacity;
		this.keepAliveInterval = requireNonNull(keepAliveInterval);
		this.maximumSubscriptionDuration = requireNonNull(
				maximumSubscriptionDuration);
		this.writeTimeout = requireNonNull(writeTimeout);
		this.logRawValidatedTraceIds = logRawValidatedTraceIds;
		this.endpointRegistry = requireNonNull(endpointRegistry);
		this.admissionController = requireNonNull(admissionController);
		this.handlerInterceptor = requireNonNull(handlerInterceptor);
		this.toolOutputSanitizer = requireNonNull(toolOutputSanitizer);
		this.requestRateLimiter = requestRateLimiter;
		this.toolRateLimiter = toolRateLimiter;
		this.rateLimiterRegistry = requireNonNull(rateLimiterRegistry);
		this.protectionConfig = protectionConfig;
		this.localizer = localizer;
		this.localizationPlan = localizer == null ? null
				: DefaultMcpLocalizationCatalogExtractor.plan(
						endpointRegistry,
						localizer.getMaximumLocalizableTextCountPerResponse());
		this.securityControls = new DefaultMcpSecurityControls(protectionConfig,
				traceCorrelationKey);
		this.localizationControl = new DefaultMcpLocalizationControl(
				localizer != null, this::publishLocalizationCatalogInvalidation);
		this.mcpMetricEventDelivery = new McpMetricEventDelivery();
		requireNonNull(unknownMirroredHeaderPolicy);
		boolean corsAuthorizerExplicitlyConfigured = configuredCorsAuthorizer != null;
		this.corsAuthorizer = configuredCorsAuthorizer == null
				? CorsAuthorizer.rejectAllInstance() : configuredCorsAuthorizer;
		this.lifecycleObserver = LifecycleObserver.defaultInstance();
		this.metricsCollector = MetricsCollector.disabledInstance();
		this.lifecyclePolicy = LifecyclePolicy.fromDefaults();
		this.pendingListenerGeneration = null;
		this.ownerTerminalResult = null;
		this.attachmentStarted = false;
		this.terminalMetricEmitted = false;
		this.simulatorOwned = false;
		List<EndpointPlan> endpointPlans = endpointRegistry.getEndpoints().stream()
				.map(this::toEndpointPlan)
				.toList();
		validateRequestStateProtection(endpointPlans, protectionConfig);
		Optional<RequestStateProtectionPlan> requestStateProtectionPlan =
				Optional.ofNullable(protectionConfig)
						.map(this::toRequestStateProtectionPlan);
		this.lifecycleAdapter = new McpTransportLifecycleAdapter(
				this::getGracefulShutdownDuration,
				this::getForcedShutdownDuration);
		this.runtimeBridge = new McpServerRuntimeBridge(host, port, endpointPlans,
				allowedHosts, absentOriginPolicy == McpAbsentOriginPolicy.REQUIRE_ORIGIN,
				this.corsAuthorizer, corsAuthorizerExplicitlyConfigured,
				input -> this.admissionController.admit(
						new DefaultMcpAdmissionContext(input)),
				Optional.ofNullable(this.requestRateLimiter)
						.map(DefaultMcpServer::toRateLimitAdapter),
				unknownMirroredHeaderPolicy,
				unknownMirroredHeaderNameDiagnostics,
				this::safelyLogUnknownMirroredHeaderName,
				requestHandlerConcurrency, requestHandlerQueueCapacity,
				requestTimeout,
				Optional.ofNullable(requestHandlerExecutorServiceSupplier),
				this::safelyLogStartupDiagnostic,
				this::safelyLogUnexpectedTermination,
				this::didStartRequestObservation, requestStateProtectionPlan,
				this.streamQueueCapacity, this.writeTimeout,
				this.keepAliveInterval,
				this.maximumSubscriptionsPerPrincipal,
				this.maximumSubscriptionDuration,
				applicationExecutionObserver(), this.lifecycleAdapter);
		this.lifecycleAdapter.bindRuntime(this.runtimeBridge);
	}

	@NonNull
	private McpApplicationExecutionObserver applicationExecutionObserver() {
		return new McpApplicationExecutionObserver() {
			@Override
			public void beginDeferral() {
				mcpMetricEventDelivery.beginDeferral();
			}

			@Override
			public void beginRequestTransitionDeferral() {
				mcpMetricEventDelivery.beginNonwaitingDeferral();
			}

			@Override
			@NonNull
			public PendingMetricRecord recordRequestAccepted() {
				return mcpMetricEventDelivery.record(
						McpMetricsEvent.requestAccepted());
			}

			@Override
			public void discardPendingMetric(
					@NonNull PendingMetricRecord pendingMetricRecord) {
				if (!(requireNonNull(pendingMetricRecord)
						instanceof McpMetricEventDeliveryEntry entry))
					throw new IllegalArgumentException(
							"The pending metric record belongs to another observer.");
				mcpMetricEventDelivery.discard(entry);
			}

			@Override
			public void recordRequestRejected() {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.requestRejected());
			}

			@Override
			public void recordConnectionAccepted() {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.connectionAccepted());
			}

			@Override
			public void recordConnectionRejected() {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.connectionRejected());
			}

			@Override
			@NonNull
			public PendingMetricRecord recordTransportFailure(
					MetricsCollector.@NonNull TransportFailureReason reason) {
				return mcpMetricEventDelivery.record(
						McpMetricsEvent.transportFailure(requireNonNull(reason)));
			}

			@Override
			@NonNull
			public PendingMetricRecord recordProtocolError(int code,
					@Nullable McpRequestContext requestContext) {
				return mcpMetricEventDelivery.record(
						McpMetricsEvent.protocolError(code), requestContext);
			}

			@Override
			public void recordUnknownMirroredHeader(
					@NonNull String endpointPath,
					@NonNull String jsonRpcMethod) {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.unknownMirroredHeader(
								requireNonNull(endpointPath),
								metricMethod(jsonRpcMethod)));
			}

			@Override
			public void recordHandlerExecutionStarted() {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.handlerExecutionStarted());
			}

			@Override
			public void recordHandlerExecutionFinished() {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.handlerExecutionFinished());
			}

			@Override
			public void recordHandlerQueued() {
				mcpMetricEventDelivery.record(McpMetricsEvent.handlerQueued());
			}

			@Override
			public void recordHandlerDequeued() {
				mcpMetricEventDelivery.record(McpMetricsEvent.handlerDequeued());
			}

			@Override
			public void recordHandlerCapacityRejected() {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.handlerCapacityRejected());
			}

			@Override
			public void drain() {
				mcpMetricEventDelivery.drain();
			}

			@Override
			public void endDeferral() {
				mcpMetricEventDelivery.endDeferral();
			}

			@Override
			public void endDeferralForAsynchronousDrain() {
				mcpMetricEventDelivery.endDeferralForAsynchronousDrain();
			}

			@Override
			public void drainAsynchronously() {
				mcpMetricEventDelivery.drainAsynchronously();
			}
		};
	}

	@NonNull
	private RequestStateProtectionPlan toRequestStateProtectionPlan(
			@NonNull McpProtectionConfig configuration) {
		requireNonNull(configuration);
		RequestStateProtectionAdapter adapter =
				new RequestStateProtectionAdapter() {
					@Override
					public void validateStructure(@NonNull String protectedState)
							throws McpRequestStateProtectionException {
						securityControls.validateRequestStateStructure(
								protectedState);
					}

					@Override
					@NonNull
					public String seal(@NonNull RequestStateProtectionInput input,
							byte @NonNull [] canonicalPlaintext)
							throws McpRequestStateProtectionException {
						return securityControls.sealRequestState(
								protectionContext(input), canonicalPlaintext);
					}

					@Override
					public byte @NonNull [] open(
							@NonNull RequestStateProtectionInput input,
							@NonNull String protectedState)
							throws McpRequestStateProtectionException {
						return securityControls.openRequestState(
								protectionContext(input), protectedState);
					}
				};
		return new RequestStateProtectionPlan(
				configuration.getMaximumEncodedRequestStateBytes(),
				configuration.getMaximumDecodedRequestStateBytes(),
				configuration.getMaximumRequestStateLifetime(),
				configuration.getMaximumRequestStateRounds(), adapter);
	}

	@NonNull
	private static McpRequestStateProtectionContext protectionContext(
			@NonNull RequestStateProtectionInput input) {
		requireNonNull(input);
		return new McpRequestStateProtectionContext(input.endpointPath(),
				input.protocolVersion(), input.method(), input.associatedData());
	}

	private static void validateRequestStateProtection(
			@NonNull List<@NonNull EndpointPlan> endpointPlans,
			@Nullable McpProtectionConfig protectionConfig) {
		requireNonNull(endpointPlans);
		boolean frameworkProtectionRequired = endpointPlans.stream()
				.anyMatch(endpointPlan -> endpointPlan.toolPlans().stream()
						.anyMatch(toolPlan -> toolPlan.requestStateMode()
								== McpRequestStateMode.FRAMEWORK_PROTECTED)
						|| endpointPlan.promptPlans().stream()
						.anyMatch(promptPlan -> promptPlan.requestStateMode()
								== McpRequestStateMode.FRAMEWORK_PROTECTED)
						|| endpointPlan.resourcePlans().stream()
						.anyMatch(resourcePlan -> resourcePlan.requestStateMode()
								== McpRequestStateMode.FRAMEWORK_PROTECTED));
		if (frameworkProtectionRequired && protectionConfig == null)
			throw new IllegalStateException(
					"Framework-protected MCP request state requires protection configuration.");
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
				effectiveCachePlan(endpoint.getResourceListCachePolicy()),
				effectiveCachePlan(endpoint.getResourceTemplateListCachePolicy()),
				this.maximumCursorSizeInBytes,
				endpoint.getResourceListHandler().map(handler -> invocation ->
						invokeResourceList(handler, registeredResourceDescriptors,
								invocation)));
		return new EndpointPlan(endpoint, toolPlans, promptPlans, resourcePlans,
				resourceListPlan, catalogLocalizer(endpoint),
				this.localizer != null);
	}

	/**
	 * Builds this endpoint's catalog localizer, or empty when no localizer is
	 * configured or this endpoint publishes no localizable text.
	 */
	@NonNull
	private Optional<@NonNull McpRuntimeCatalogLocalizer> catalogLocalizer(
			@NonNull McpEndpoint endpoint) {
		McpLocalizer configuredLocalizer = this.localizer;
		McpCanonicalLocalizationPlan plan = this.localizationPlan;

		if (configuredLocalizer == null || plan == null)
			return Optional.empty();

		Optional<McpCanonicalLocalizationPlan.EndpointPlan> endpointPlan =
				plan.endpoints().stream()
						.filter(candidate -> candidate.endpointPath()
								.equals(endpoint.getPath()))
						.findFirst();

		return endpointPlan.map(resolved -> new McpRuntimeCatalogLocalizer() {
			@Override
			public McpRuntimeCatalogLocalizer.@NonNull Outcome localizeCatalog(
					McpRuntimeCatalogLocalizer.@NonNull Input input) {
				return DefaultMcpServer.this.localizeCatalog(configuredLocalizer,
						resolved, input);
			}

			@Override
			@NonNull
			public Set<McpRuntimeCatalogLocalizer.@NonNull ResponseKind>
					localizedResponseKinds() {
				return resolved.responses().stream()
						.map(response -> toRuntimeResponseKind(response.kind()))
						.collect(java.util.stream.Collectors.toUnmodifiableSet());
			}
		});
	}

	/**
	 * Routes {@code catalogsChanged()} to the runtime bridge. The method body
	 * reads the bridge at call time, which is what lets the control be
	 * constructed before the bridge in the constructor.
	 */
	private void publishLocalizationCatalogInvalidation() {
		this.runtimeBridge.publishLocalizationCatalogInvalidation();
	}

	private static McpRuntimeCatalogLocalizer.@NonNull ResponseKind
			toRuntimeResponseKind(
					McpCanonicalLocalizationPlan.@NonNull ResponseKind kind) {
		return switch (kind) {
			case DISCOVERY -> McpRuntimeCatalogLocalizer.ResponseKind.DISCOVERY;
			case TOOLS_LIST -> McpRuntimeCatalogLocalizer.ResponseKind.TOOLS_LIST;
			case PROMPTS_LIST ->
					McpRuntimeCatalogLocalizer.ResponseKind.PROMPTS_LIST;
			case RESOURCES_LIST ->
					McpRuntimeCatalogLocalizer.ResponseKind.RESOURCES_LIST;
			case RESOURCE_TEMPLATES_LIST ->
					McpRuntimeCatalogLocalizer.ResponseKind.RESOURCE_TEMPLATES_LIST;
			case SUBSCRIPTION_TERMINAL ->
					McpRuntimeCatalogLocalizer.ResponseKind.SUBSCRIPTION_TERMINAL;
		};
	}

	/**
	 * Creates exactly one localization context for this response and renders it.
	 * <p>
	 * The absolute request boundary is checked immediately before and after
	 * provider work, so a request that has already become terminal never reaches
	 * application code. A response kind with no planned slots needs no context at
	 * all and publishes canonically.
	 */
	private McpRuntimeCatalogLocalizer.@NonNull Outcome localizeCatalog(
			@NonNull McpLocalizer configuredLocalizer,
			McpCanonicalLocalizationPlan.@NonNull EndpointPlan endpointPlan,
			McpRuntimeCatalogLocalizer.@NonNull Input input) {
		Optional<McpCanonicalLocalizationPlan.ResponsePlan> responsePlan =
				endpointPlan.response(toPlanResponseKind(input.responseKind()));

		if (responsePlan.isEmpty())
			return McpRuntimeCatalogLocalizer.Outcome.canonical(
					input.canonicalDocument());

		// Terminal work before any provider call publishes canonically: no
		// provider ran, so there is no localization failure to classify.
		if (input.terminalBoundary().getAsBoolean())
			return McpRuntimeCatalogLocalizer.Outcome.canonical(
					input.canonicalDocument());

		McpLocalizationRequest localizationRequest =
				new DefaultMcpLocalizationRequest(input.requestContext(),
						McpLocaleSupport.boundedLanguageRanges(
								input.acceptLanguageValues()),
						null,
						input.resourceListCursor().isEmpty() ? null
								: input.resourceListCursor().get(0),
						configuredLocalizer.getFallbackLocale());
		McpLocalizationContext context;

		try {
			context = requireNonNull(configuredLocalizer.getContextProvider()
					.createContext(localizationRequest),
					"The MCP localization context provider returned null.");
		} catch (Throwable exception) {
			// The whole throwable - Errors and sneaky-thrown checked exceptions
			// included - is untrusted localization data and is never forwarded to
			// any framework-owned surface.
			if (exception instanceof InterruptedException)
				Thread.currentThread().interrupt();

			return localizationFailure(configuredLocalizer, input);
		}

		if (input.terminalBoundary().getAsBoolean())
			return localizationFailure(configuredLocalizer, input);

		McpLocalizationRenderer.Outcome outcome = McpLocalizationRenderer.render(
				input.canonicalDocument(), input.canonicalEncodedBytes(),
				input.envelopeBytes(), input.maximumResponseBytes(),
				input.maximumReplacementCharacters(),
				responsePlan.orElseThrow().slots(), context,
				configuredLocalizer.getFailurePolicy(),
				() -> input.terminalBoundary().getAsBoolean(),
				document -> input.encodedLength().applyAsLong(document));

		return switch (outcome.disposition()) {
			case LOCALIZED -> new McpRuntimeCatalogLocalizer.Outcome(
					McpRuntimeCatalogLocalizer.Disposition.LOCALIZED,
					outcome.document(),
					contentLanguageTag(outcome.selectedLocale()));
			// A successful no-op resolution or intentional per-field default-text
			// choice still renders the representation for the selected locale.
			case CANONICAL -> new McpRuntimeCatalogLocalizer.Outcome(
					McpRuntimeCatalogLocalizer.Disposition.CANONICAL,
					input.canonicalDocument(),
					contentLanguageTag(outcome.selectedLocale()));
			// Whole-response canonical fallback is the configured fallback
			// locale's representation.
			case DEFAULT_TEXT -> new McpRuntimeCatalogLocalizer.Outcome(
					McpRuntimeCatalogLocalizer.Disposition.CANONICAL,
					input.canonicalDocument(),
					contentLanguageTag(configuredLocalizer.getFallbackLocale()));
			case FAIL_REQUEST -> new McpRuntimeCatalogLocalizer.Outcome(
					McpRuntimeCatalogLocalizer.Disposition.FAIL_REQUEST,
					input.canonicalDocument(), Optional.empty());
		};
	}

	@NonNull
	private static Optional<@NonNull String> contentLanguageTag(
			@Nullable Locale locale) {
		return locale == null ? Optional.empty()
				: Optional.of(locale.toLanguageTag());
	}

	private static McpRuntimeCatalogLocalizer.@NonNull Outcome localizationFailure(
			@NonNull McpLocalizer configuredLocalizer,
			McpRuntimeCatalogLocalizer.@NonNull Input input) {
		return configuredLocalizer.getFailurePolicy()
				== McpLocalizationFailurePolicy.USE_DEFAULT_TEXT
				? new McpRuntimeCatalogLocalizer.Outcome(
						McpRuntimeCatalogLocalizer.Disposition.CANONICAL,
						input.canonicalDocument(), contentLanguageTag(
								configuredLocalizer.getFallbackLocale()))
				: new McpRuntimeCatalogLocalizer.Outcome(
						McpRuntimeCatalogLocalizer.Disposition.FAIL_REQUEST,
						input.canonicalDocument(), Optional.empty());
	}

	private static McpCanonicalLocalizationPlan.@NonNull ResponseKind toPlanResponseKind(
			McpRuntimeCatalogLocalizer.@NonNull ResponseKind responseKind) {
		return switch (responseKind) {
			case DISCOVERY -> McpCanonicalLocalizationPlan.ResponseKind.DISCOVERY;
			case TOOLS_LIST -> McpCanonicalLocalizationPlan.ResponseKind.TOOLS_LIST;
			case PROMPTS_LIST -> McpCanonicalLocalizationPlan.ResponseKind.PROMPTS_LIST;
			case RESOURCES_LIST ->
					McpCanonicalLocalizationPlan.ResponseKind.RESOURCES_LIST;
			case RESOURCE_TEMPLATES_LIST ->
					McpCanonicalLocalizationPlan.ResponseKind.RESOURCE_TEMPLATES_LIST;
			case SUBSCRIPTION_TERMINAL ->
					McpCanonicalLocalizationPlan.ResponseKind.SUBSCRIPTION_TERMINAL;
		};
	}

	void initialize(@NonNull SokletConfig sokletConfig) {
		SokletConfig configuration = requireNonNull(sokletConfig);
		synchronized (this.lifecycleLock) {
			this.attachmentStarted = true;
			this.lifecycleObserver = configuration.getAggregateLifecycleObserver();
			this.metricsCollector = configuration.getMetricsCollector();
			this.lifecyclePolicy = configuration.getLifecyclePolicy();
		}
	}

	@NonNull
	private Duration getGracefulShutdownDuration() {
		return this.lifecyclePolicy.getGracefulShutdownDuration();
	}

	@NonNull
	private Duration getForcedShutdownDuration() {
		return this.lifecyclePolicy.getForcedShutdownDuration();
	}

	void installLifecycleExecutionOwner(@NonNull Object ownerToken) {
		Object exactOwner = requireNonNull(ownerToken);
		synchronized (this.lifecycleLock) {
			Object existing = this.lifecycleExecutionOwner;
			if (existing != null && existing != exactOwner)
				throw new IllegalStateException(
						"The MCP application-execution lifecycle owner was already installed");
			this.lifecycleExecutionOwner = exactOwner;
		}
	}

	@NonNull
	McpTransportLifecycleAdapter getLifecycleAdapter() {
		return this.lifecycleAdapter;
	}

	void recordExternallyCoordinatedTerminalResultWhileMetricsDeferred(
			@NonNull InternalParticipantShutdownResult participantResult,
			@NonNull SokletConfig sokletConfig) {
		InternalParticipantShutdownResult exactResult =
				requireNonNull(participantResult);
		if (exactResult.kind() != InternalParticipantKind.MCP)
			throw new IllegalArgumentException(
					"The terminal metric result must belong to the MCP participant");
		SokletConfig configuration = requireNonNull(sokletConfig);
		synchronized (this.lifecycleLock) {
			if (this.terminalMetricEmitted)
				return;
			// A configured participant can freeze as NOT_STARTED before attachment
			// creates an MCP generation. Install only the observation dependencies
			// needed to deliver that exact owner result; no listener/runtime state is
			// initialized or mutated here.
			this.lifecycleObserver = configuration.getAggregateLifecycleObserver();
			this.metricsCollector = configuration.getMetricsCollector();
			this.ownerTerminalResult = exactResult;
			this.terminalMetricEmitted = true;
			this.mcpMetricEventDelivery.record(McpMetricsEvent.serverStopped(
					ParticipantShutdownDisposition.valueOf(
							exactResult.disposition().name())));
		}
	}

	@NonNull
	SimulationSession openSimulationSession() {
		synchronized (this.lifecycleLock) {
			return this.runtimeBridge.openSimulationSession();
		}
	}

	void openSimulationSession(
			@NonNull Consumer<@NonNull SimulationSession> sessionOwner) {
		synchronized (this.lifecycleLock) {
			this.runtimeBridge.openSimulationSession(requireNonNull(sessionOwner));
		}
	}

	void claimSimulatorScope(@NonNull Object simulatorScope) {
		requireNonNull(simulatorScope);
		synchronized (this.lifecycleLock) {
			if (this.simulatorOwned)
				throw new IllegalStateException(
						"The MCP server already belongs to a simulator scope");
			this.simulatorOwned = true;
		}
	}

	void startForSoklet() {
		beginMcpMetricsDeferral();
		try {
			startForSokletWhileMetricsDeferred();
		} finally {
			endMcpMetricsDeferral();
		}
	}

	private void startForSokletWhileMetricsDeferred() {
		McpMetricEventDeliveryEntry provisionalServerStarted = null;
		McpTransportLifecycleAdapter.@Nullable Generation lifecycleGeneration = null;

		try {
			synchronized (this.lifecycleLock) {
				if (this.simulatorOwned)
					throw new IllegalStateException(
							"A simulator-owned MCP server cannot bind a listener");
				if (this.lifecycleAdapter.shutdownInProgress())
					throw new IllegalStateException(
							"Cannot start MCP server while shutdown is in progress");
				if (this.pendingListenerGeneration != null
						|| this.lifecycleAdapter.result().isPresent())
					throw new IllegalStateException(
							"An MCP listener lifecycle was already attempted");
				lifecycleGeneration = this.lifecycleAdapter.beginStart();
				this.pendingListenerGeneration = lifecycleGeneration;
				this.runtimeBridge.prepareLifecycleStart(lifecycleGeneration);
			}

			if (this.securityControls.getProtectionMode()
					== McpProtectionMode.DEVELOPMENT_EPHEMERAL)
				safelyLogStartupDiagnostic(
						DEVELOPMENT_EPHEMERAL_PROTECTION_DIAGNOSTIC);
			provisionalServerStarted = this.mcpMetricEventDelivery.record(
					McpMetricsEvent.serverStarted());
			this.runtimeBridge.start(requireNonNull(lifecycleGeneration));
			synchronized (this.lifecycleLock) {
				// Readiness and the exact pending-listener identity are one server-level
				// transition. A concurrent stop cannot consume the ready generation in
				// between these two publications.
				this.lifecycleAdapter.markReady(
						requireNonNull(lifecycleGeneration));
			}
		} catch (IOException | RuntimeException | Error throwable) {
			Throwable primary = exactStartupFailure(throwable);
			if (provisionalServerStarted != null) {
				try {
					this.mcpMetricEventDelivery.discard(provisionalServerStarted);
				} catch (Throwable cleanupFailure) {
					if (cleanupFailure != primary)
						primary.addSuppressed(cleanupFailure);
				}
			}
			if (lifecycleGeneration != null) {
				try {
					this.lifecycleAdapter.failedStart(lifecycleGeneration, primary,
							false);
				} catch (Throwable cleanupFailure) {
					if (cleanupFailure != primary)
						primary.addSuppressed(cleanupFailure);
				}
			}
			if (primary instanceof IOException exception)
				throw new UncheckedIOException("Unable to start the MCP server.", exception);
			if (primary instanceof RuntimeException exception)
				throw exception;
			throw (Error) primary;
		}
	}

	@NonNull
	private Throwable exactStartupFailure(@NonNull Throwable failure) {
		Throwable requiredFailure = requireNonNull(failure);
		if (requiredFailure instanceof BuiltInTransportLifecycleAdapter
				.PrematureTerminationException
				&& requiredFailure.getCause() != null)
			return requiredFailure.getCause();
		return requiredFailure;
	}

	void beginMcpMetricsDeferral() {
		this.mcpMetricEventDelivery.beginDeferral();
	}

	void beginNonwaitingMcpMetricsDeferral() {
		this.mcpMetricEventDelivery.beginNonwaitingDeferral();
	}

	void endMcpMetricsDeferral() {
		this.mcpMetricEventDelivery.endLifecycleDeferral();
	}

	@Override
	@NonNull
	public McpEndpointRegistry getEndpointRegistry() {
		return this.endpointRegistry;
	}

	@Override
	@NonNull
	public McpAdmissionController getAdmissionController() {
		return this.admissionController;
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
	@NonNull
	public Integer getMaximumCursorSizeInBytes() {
		return this.maximumCursorSizeInBytes;
	}

	@Override
	@NonNull
	public McpProtectionControl getProtectionControl() {
		return this.securityControls;
	}

	@Override
	@NonNull
	public McpTraceCorrelationControl getTraceCorrelationControl() {
		return this.securityControls;
	}

	@Override
	@NonNull
	public McpLocalizationControl getLocalizationControl() {
		return this.localizationControl;
	}

	@NonNull
	Optional<@NonNull McpProtectionConfig> protectionConfig() {
		return Optional.ofNullable(this.protectionConfig);
	}

	@NonNull
	Optional<@NonNull McpLocalizer> localizer() {
		return Optional.ofNullable(this.localizer);
	}

	@NonNull
	Optional<@NonNull McpCanonicalLocalizationPlan> localizationPlan() {
		return Optional.ofNullable(this.localizationPlan);
	}

	int streamQueueCapacity() {
		return this.streamQueueCapacity;
	}

	@NonNull
	Duration writeTimeout() {
		return this.writeTimeout;
	}

	@NonNull
	Duration keepAliveInterval() {
		return this.keepAliveInterval;
	}

	int maximumSubscriptionsPerPrincipal() {
		return this.maximumSubscriptionsPerPrincipal;
	}

	@NonNull
	Duration maximumSubscriptionDuration() {
		return this.maximumSubscriptionDuration;
	}

	boolean logRawValidatedTraceIds() {
		return this.logRawValidatedTraceIds;
	}

	@Override
	@NonNull
	public McpServerDiagnostics getDiagnostics() {
		synchronized (this.lifecycleLock) {
			DiagnosticsState runtimeState = this.runtimeBridge
					.getDiagnosticsState();
			DefaultMcpSecurityControls.SecurityDiagnosticsState securityState =
					this.securityControls.getDiagnosticsState();
			McpServerStatus status = mcpServerStatus(runtimeState);
			return new DefaultMcpServerDiagnostics(status,
					runtimeState.boundAddress(),
					runtimeState.requestHandlerConcurrency(),
					runtimeState.requestHandlerQueueCapacity(),
					runtimeState.activeHandlerExecutions(),
					runtimeState.queuedRequests(),
					runtimeState.activeRequestStreams(),
					runtimeState.activeSubscriptions(),
					securityState.protectionMode(),
					securityState.applicationRequestStateProtectorConfigured(),
					securityState.protectionKeyRingFingerprint(),
					securityState.traceCorrelationConfigurationFingerprint());
		}
	}

	@NonNull
	private McpServerStatus mcpServerStatus(
			@NonNull DiagnosticsState runtimeState) {
		InternalParticipantShutdownResult terminalResult = this.lifecycleAdapter
				.result()
				.flatMap(result -> result.participantResult(
						InternalParticipantKind.MCP))
				.orElse(this.ownerTerminalResult);
		if (terminalResult != null)
			return switch (terminalResult.disposition()) {
				case RESIDUAL_ACTIVITY -> McpServerStatus.RESIDUAL_ACTIVITY;
				case TERMINATION_UNKNOWN -> McpServerStatus.TERMINATION_UNKNOWN;
				case NOT_STARTED, GRACEFUL_TERMINATION, FORCED_TERMINATION,
						UNEXPECTED_TERMINATION -> McpServerStatus.TERMINATED;
			};
		if (this.lifecycleAdapter.shutdownInProgress())
			return McpServerStatus.SHUTTING_DOWN;
		if (requireNonNull(runtimeState).started()
				|| this.lifecycleAdapter.admissionOpen())
			return McpServerStatus.RUNNING;
		if (this.lifecycleAdapter.hasActiveGeneration())
			return McpServerStatus.STARTING;
		return this.attachmentStarted
				? McpServerStatus.STARTING : McpServerStatus.NOT_STARTED;
	}

	@NonNull
	private <A> ToolPlan toToolPlan(@NonNull McpEndpoint endpoint,
			@NonNull McpToolRegistration<A> tool) {
		McpRateLimiter resolvedRateLimiter = resolveToolRateLimiter(endpoint, tool);
		return new ToolPlan(tool.getName(), tool.getInputSchema().getDocument(),
				tool.getMirroredHeaderPlan(),
				tool.getOutputSchema().map(McpToolSchema::getDocument),
				toolDescriptorFields(tool), tool.getMetadata(),
				tool.isStructuredContentTextMirroringEnabled(),
				toRateLimitAdapter(resolvedRateLimiter),
				tool.getInputRequestDeclarations(), tool.getRequestStateMode(),
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
				prompt.getInputRequestDeclarations(), prompt.getRequestStateMode(),
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
				effectiveCachePlan(resource.getCachePolicy()),
				resource.getInputRequestDeclarations(),
				resource.getRequestStateMode(),
				invocation -> invokeResource(resource, invocation));
	}

	/**
	 * Resolves one configured cache policy to its effective plan.
	 * <p>
	 * With a configured localizer there is no in-protocol locale cache
	 * dimension, so every cacheable localized-capable result is conservatively
	 * private with a zero TTL. The downgrade is monotonic: an application
	 * result cannot widen it, because the clamped plan is what every later
	 * validation compares against.
	 */
	@NonNull
	private CachePlan effectiveCachePlan(@NonNull McpCachePolicy cachePolicy) {
		if (this.localizer != null)
			return new CachePlan(0L, CacheScope.PRIVATE);

		return toCachePlan(cachePolicy);
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
			return RateLimitResult.denied(denied.getRetryAfter());
		throw new IllegalArgumentException("Unsupported MCP rate-limit decision.");
	}

	@NonNull
	private <A> ToolInvocationResult invokeTool(
			@NonNull McpToolRegistration<A> tool,
			@NonNull ToolInvocation invocation) throws Exception {
		McpRequestContext requestContext = invocation.requestContext();
		McpInvocationFeatures invocationFeatures = invocationFeatures(
				requestContext, invocation.endpoint(), invocation.jsonRpcMethod(),
				invocation.cancelationToken(), invocation.progressEmitter(),
				invocation.pastDeadline(), invocation.continuationLocale(),
				invocation.selectedLocaleSlot());
		McpOperationResult result;
		try {
			result = interceptHandler(requestContext, invocation.handlerEntryGuard(),
					invocationFeatures,
					() -> tool.invoke(
							requestContext, invocation.rawArguments(),
							invocationFeatures));
		} catch (McpInvalidToolArgumentsException exception) {
			return ToolInvocationResult.invalidInput();
		}

		if (result instanceof McpInputRequiredResult inputRequiredResult)
			return ToolInvocationResult.inputRequired(inputRequiredResult);
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
		McpInvocationFeatures invocationFeatures = invocationFeatures(
				requestContext, invocation.endpoint(), invocation.jsonRpcMethod(),
				invocation.cancelationToken(), invocation.progressEmitter(),
				invocation.pastDeadline(), invocation.continuationLocale(),
				invocation.selectedLocaleSlot());
		McpOperationResult result;
		try {
			result = interceptHandler(requestContext, invocation.handlerEntryGuard(),
					invocationFeatures,
					() -> prompt.invoke(
							requestContext, invocation.rawArguments(),
							invocationFeatures));
		} catch (McpInvalidPromptArgumentsException exception) {
			return PromptInvocationResult.invalidInput();
		}

		if (result instanceof McpInputRequiredResult inputRequiredResult)
			return PromptInvocationResult.inputRequired(inputRequiredResult);
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
		McpInvocationFeatures invocationFeatures = invocationFeatures(
				requestContext, invocation.endpoint(), invocation.jsonRpcMethod(),
				invocation.cancelationToken(), invocation.progressEmitter(),
				invocation.pastDeadline(), invocation.continuationLocale(),
				invocation.selectedLocaleSlot());
		McpOperationResult result;
		try {
			result = interceptHandler(requestContext, invocation.handlerEntryGuard(),
					invocationFeatures,
					() -> {
						try {
							return requireNonNull(
									resource.getHandler().handle(requestContext,
											new DefaultMcpResourceReadContext(invocation),
											invocationFeatures),
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

		if (result instanceof McpInputRequiredResult inputRequiredResult)
			return ResourceInvocationResult.inputRequired(inputRequiredResult);
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
			@NonNull McpInvocationFeatures invocationFeatures,
			@NonNull McpHandlerContinuation continuation) throws Exception {
		Object ownerToken = this.lifecycleExecutionOwner;
		if (ownerToken == null)
			return interceptHandlerWhileMarked(requestContext, handlerEntryGuard,
					invocationFeatures, continuation);
		try (LifecycleExecutionContext.Scope ignored =
					 LifecycleExecutionContext.enter(ownerToken)) {
			return interceptHandlerWhileMarked(requestContext, handlerEntryGuard,
					invocationFeatures, continuation);
		}
	}

	@NonNull
	private McpOperationResult interceptHandlerWhileMarked(
			@NonNull McpRequestContext requestContext,
			@NonNull HandlerEntryGuard handlerEntryGuard,
			@NonNull McpInvocationFeatures invocationFeatures,
			@NonNull McpHandlerContinuation continuation) throws Exception {
		requireNonNull(requestContext);
		requireNonNull(handlerEntryGuard);
		requireNonNull(invocationFeatures);
		requireNonNull(continuation);
		AtomicBoolean active = new AtomicBoolean(true);
		AtomicBoolean invoked = new AtomicBoolean();
		Thread interceptorThread = Thread.currentThread();
		McpOperationResult result;
		try {
			result = this.handlerInterceptor.interceptHandler(requestContext,
					invocationFeatures,
					new McpHandlerContinuation() {
						private void requireActiveThread() {
							if (!active.get())
								throw new IllegalStateException(
										"An MCP interceptor continuation cannot be used after interception returns.");
							if (Thread.currentThread() != interceptorThread)
								throw new IllegalStateException(
										"An MCP interceptor continuation must be used on the interceptor thread.");
						}

						@Override
						@NonNull
						public McpOperationResult proceed() throws Exception {
							requireActiveThread();
							if (!invoked.compareAndSet(false, true))
								throw new IllegalStateException(
										"An MCP interceptor continuation may be invoked only once.");
							handlerEntryGuard.requireEntry();
							return requireNonNull(continuation.proceed(),
									"The MCP downstream handler returned null.");
						}
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
		McpInvocationFeatures invocationFeatures = invocationFeatures(
				requestContext, invocation.endpoint(), invocation.jsonRpcMethod(),
				invocation.cancelationToken(), invocation.progressEmitter(),
				invocation.pastDeadline(), invocation.continuationLocale(),
				invocation.selectedLocaleSlot(), invocation.cursor());
		McpOperationResult result;
		try {
			result = interceptHandler(requestContext, invocation.handlerEntryGuard(),
					invocationFeatures,
					() -> {
						try {
							return requireNonNull(
									handler.handle(requestContext,
											new DefaultMcpResourceListContext(
													invocation.cursor(),
													registeredDescriptors),
											invocationFeatures),
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

	@NonNull
	private McpInvocationFeatures invocationFeatures(
			@NonNull McpRequestContext requestContext,
			@NonNull McpEndpoint endpoint, @NonNull String jsonRpcMethod,
			@NonNull CancelationToken cancelationToken,
			@NonNull Optional<@NonNull ProgressEmitter> progressEmitter,
			@NonNull BooleanSupplier pastDeadline,
			@NonNull Optional<@NonNull String> continuationLocale,
			@NonNull AtomicReference<@Nullable String> selectedLocaleSlot) {
		return invocationFeatures(requestContext, endpoint, jsonRpcMethod,
				cancelationToken, progressEmitter, pastDeadline,
				continuationLocale, selectedLocaleSlot, Optional.empty());
	}

	private McpInvocationFeatures invocationFeatures(
			@NonNull McpRequestContext requestContext,
			@NonNull McpEndpoint endpoint, @NonNull String jsonRpcMethod,
			@NonNull CancelationToken cancelationToken,
			@NonNull Optional<@NonNull ProgressEmitter> progressEmitter,
			@NonNull BooleanSupplier pastDeadline,
			@NonNull Optional<@NonNull String> continuationLocale,
			@NonNull AtomicReference<@Nullable String> selectedLocaleSlot,
			@NonNull Optional<@NonNull String> resourceListCursor) {
		requireNonNull(requestContext);
		String endpointPath = requireNonNull(endpoint).getPath();
		String boundedMethod = metricMethod(jsonRpcMethod);
		CancelationToken token = requireNonNull(cancelationToken);
		Optional<ProgressEmitter> emitter = requireNonNull(progressEmitter);
		token.onCancel(() -> this.mcpMetricEventDelivery.recordAndDrain(
				McpMetricsEvent.cancelationSignaled(endpointPath, boundedMethod),
				requestContext));

		Map<Class<?>, Object> features = new LinkedHashMap<>();
		features.put(CancelationToken.class, token);
		emitter.ifPresent(value -> features.put(McpProgressReporter.class,
				new DefaultMcpProgressReporter(token, value,
						requestContext, endpointPath, boundedMethod)));
		// Created after queue admission and the handler slot, immediately before
		// the interceptor, so rejected/dequeued work never calls the provider.
		applicationLocalizationContext(requestContext, token, pastDeadline,
				continuationLocale, selectedLocaleSlot, resourceListCursor)
				.ifPresent(context ->
						features.put(McpLocalizationContext.class, context));
		return McpInvocationFeatures.fromFeatures(features);
	}

	/**
	 * Creates the request-scoped localization context for one handler-family
	 * invocation, or empty when no localizer is configured.
	 * <p>
	 * A creation failure - including a canceled boundary - always fails the
	 * invocation before interceptor or handler entry: Soklet cannot fabricate
	 * the application's selected locale or translation snapshot, so neither
	 * failure policy applies.
	 *
	 * @throws McpLocalizationContextUnavailableException on any creation failure;
	 *         the provider throwable is discarded as untrusted localization data
	 */
	@NonNull
	private Optional<@NonNull McpLocalizationContext>
			applicationLocalizationContext(
					@NonNull McpRequestContext requestContext,
					@NonNull CancelationToken token,
					@NonNull BooleanSupplier pastDeadline,
					@NonNull Optional<@NonNull String> continuationLocale,
					@NonNull AtomicReference<@Nullable String> selectedLocaleSlot,
					@NonNull Optional<@NonNull String> resourceListCursor) {
		McpLocalizer configuredLocalizer = this.localizer;

		if (configuredLocalizer == null) {
			// A verified version-2 continuation requires the exact original
			// locale, which a localization-disabled node cannot construct. It
			// fails through the sanitized path and never re-emits version 1.
			if (continuationLocale.isPresent())
				throw new McpLocalizationContextUnavailableException();

			return Optional.empty();
		}

		// The absolute deadline is read directly, so an expired request stops
		// here rather than at the next asynchronous deadline sweep.
		if (token.isCanceled() || pastDeadline.getAsBoolean())
			throw new McpLocalizationContextUnavailableException();

		McpLocalizationRequest localizationRequest =
				new DefaultMcpLocalizationRequest(requestContext,
						McpLocaleSupport.boundedLanguageRanges(
								requestContext instanceof DefaultMcpRequestContext context
										? context.acceptLanguageValues()
										: DefaultMcpRequestContext.acceptLanguageValues(
												requestContext.getRequest())),
						continuationLocale.map(Locale::forLanguageTag)
								.orElse(null),
						resourceListCursor.orElse(null),
						configuredLocalizer.getFallbackLocale());
		McpLocalizationContext context;
		String selectedLocaleTag;

		try {
			context = requireNonNull(configuredLocalizer.getContextProvider()
					.createContext(localizationRequest),
					"The MCP localization context provider returned null.");
			// The selected locale is provider data: validate it canonical and
			// non-root here, once, for both feature exposure and state minting.
			selectedLocaleTag = McpLocaleSupport.requireCanonicalCatalogLocale(
					requireNonNull(context.getLocale()), "selectedLocale")
					.toLanguageTag();
		} catch (Throwable exception) {
			// The whole throwable - Errors and sneaky-thrown checked exceptions
			// included - is untrusted localization data and is never forwarded to
			// any framework-owned surface.
			if (exception instanceof InterruptedException)
				Thread.currentThread().interrupt();

			throw new McpLocalizationContextUnavailableException();
		}

		// A verified continuation locale is a required selection, not a hint: a
		// context reporting any other language fails before interceptor/handler
		// entry. Field-level UseDefaultText remains available to an exact-locale
		// context; it never authorizes renegotiation.
		if (continuationLocale.isPresent()
				&& !continuationLocale.orElseThrow().equals(selectedLocaleTag))
			throw new McpLocalizationContextUnavailableException();

		if (token.isCanceled() || pastDeadline.getAsBoolean())
			throw new McpLocalizationContextUnavailableException();

		selectedLocaleSlot.set(selectedLocaleTag);
		return Optional.of(context);
	}

	/**
	 * Serializes application progress reports for one active MCP invocation.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	private final class DefaultMcpProgressReporter
			implements McpProgressReporter {
		@NonNull
		private final CancelationToken cancelationToken;
		@NonNull
		private final ProgressEmitter progressEmitter;
		@NonNull
		private final McpRequestContext requestContext;
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;
		@Nullable
		private Double lastAcceptedProgress;

		private DefaultMcpProgressReporter(
				@NonNull CancelationToken cancelationToken,
				@NonNull ProgressEmitter progressEmitter,
				@NonNull McpRequestContext requestContext,
				@NonNull String endpointPath, @NonNull String jsonRpcMethod) {
			this.cancelationToken = requireNonNull(cancelationToken);
			this.progressEmitter = requireNonNull(progressEmitter);
			this.requestContext = requireNonNull(requestContext);
			this.endpointPath = requireNonNull(endpointPath);
			this.jsonRpcMethod = requireNonNull(jsonRpcMethod);
		}

		@Override
		public void report(@NonNull McpProgressUpdate update) {
			McpProgressUpdate requiredUpdate = requireNonNull(update);
			DefaultMcpServer.this.mcpMetricEventDelivery.beginDeferral();
			try {
				synchronized (this) {
					if (this.cancelationToken.isCanceled()
							|| !this.progressEmitter.isActive())
						return;

					double progress = requiredUpdate.getProgress();
					if (this.lastAcceptedProgress != null) {
						int comparison = Double.compare(progress,
								this.lastAcceptedProgress);
						if (comparison < 0)
							throw new IllegalArgumentException(
									"MCP progress must not decrease.");
						if (comparison == 0)
							return;
					}

					try {
						if (!this.progressEmitter.emit(progress,
								requiredUpdate.getTotal(),
								requiredUpdate.getMessage()))
							return;
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						return;
					}

					this.lastAcceptedProgress = progress;
					mcpMetricEventDelivery.record(
							McpMetricsEvent.progressEmitted(
									this.endpointPath, this.jsonRpcMethod),
							this.requestContext);
				}
			} finally {
				DefaultMcpServer.this.mcpMetricEventDelivery.endDeferral();
			}
		}
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
				fields.put("title", McpJsonString.fromValue(value)));
		tool.getDescription().ifPresent(value ->
				fields.put("description", McpJsonString.fromValue(value)));
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
				fields.put("title", McpJsonString.fromValue(value)));
		prompt.getDescription().ifPresent(value ->
				fields.put("description", McpJsonString.fromValue(value)));
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
				fields.put("title", McpJsonString.fromValue(value)));
		resource.getDescription().ifPresent(value ->
				fields.put("description", McpJsonString.fromValue(value)));
		resource.getMimeType().ifPresent(value ->
				fields.put("mimeType", McpJsonString.fromValue(value)));
		if (!resource.getIcons().isEmpty())
			fields.put("icons", McpJsonArray.fromElements(resource.getIcons().stream()
					.map(DefaultMcpServer::iconToJson)
					.toList()));
		resource.getAnnotations().ifPresent(value ->
				fields.put("annotations", contentAnnotationsToJson(value)));
		resource.getSize().ifPresent(value ->
				fields.put("size", McpJsonNumber.fromValue(
						java.math.BigDecimal.valueOf(value))));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject resourceDescriptorFields(
			@NonNull McpResourceDescriptor resource) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		resource.getTitle().ifPresent(value ->
				fields.put("title", McpJsonString.fromValue(value)));
		resource.getDescription().ifPresent(value ->
				fields.put("description", McpJsonString.fromValue(value)));
		resource.getMimeType().ifPresent(value ->
				fields.put("mimeType", McpJsonString.fromValue(value)));
		if (!resource.getIcons().isEmpty())
			fields.put("icons", McpJsonArray.fromElements(resource.getIcons().stream()
					.map(DefaultMcpServer::iconToJson)
					.toList()));
		resource.getAnnotations().ifPresent(value ->
				fields.put("annotations", contentAnnotationsToJson(value)));
		resource.getSize().ifPresent(value ->
				fields.put("size", McpJsonNumber.fromValue(
						java.math.BigDecimal.valueOf(value))));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject promptArgumentDescriptorFields(
			@NonNull McpPromptArgumentDefinition argument) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		argument.getTitle().ifPresent(value ->
				fields.put("title", McpJsonString.fromValue(value)));
		argument.getDescription().ifPresent(value ->
				fields.put("description", McpJsonString.fromValue(value)));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject iconToJson(@NonNull McpIcon icon) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("src", McpJsonString.fromValue(icon.getSource().toString()));
		icon.getMimeType().ifPresent(value ->
				fields.put("mimeType", McpJsonString.fromValue(value)));
		if (!icon.getSizes().isEmpty())
			fields.put("sizes", McpJsonArray.fromElements(icon.getSizes().stream()
					.map(McpJsonString::fromValue)
					.toList()));
		icon.getTheme().ifPresent(value -> fields.put("theme",
				McpJsonString.fromValue(value.name().toLowerCase(Locale.ROOT))));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject toolAnnotationsToJson(
			@NonNull McpToolAnnotations annotations) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		annotations.getTitle().ifPresent(value ->
				fields.put("title", McpJsonString.fromValue(value)));
		annotations.getReadOnlyHint().ifPresent(value ->
				fields.put("readOnlyHint", McpJsonBoolean.fromValue(value)));
		annotations.getDestructiveHint().ifPresent(value ->
				fields.put("destructiveHint", McpJsonBoolean.fromValue(value)));
		annotations.getIdempotentHint().ifPresent(value ->
				fields.put("idempotentHint", McpJsonBoolean.fromValue(value)));
		annotations.getOpenWorldHint().ifPresent(value ->
				fields.put("openWorldHint", McpJsonBoolean.fromValue(value)));
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
			fields.put("isError", McpJsonBoolean.fromValue(true));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject promptOutputFields(
			@NonNull McpPromptOutput output) {
		requireAggregatePromptBinaryDataFitsOutput(output.getMessages());
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		output.getDescription().ifPresent(value ->
				fields.put("description", McpJsonString.fromValue(value)));
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
				fields.put("ttlMs", McpJsonNumber.fromValue(
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
				fields.put("nextCursor", McpJsonString.fromValue(value)));
		page.getCacheTimeToLiveOverride().ifPresent(value ->
				fields.put("ttlMs", McpJsonNumber.fromValue(
						java.math.BigDecimal.valueOf(value.toMillis()))));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject resourceDescriptorToJson(
			@NonNull McpResourceDescriptor resource) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("uri", McpJsonString.fromValue(resource.getUri().toString()));
		fields.put("name", McpJsonString.fromValue(resource.getName()));
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
		fields.put("role", McpJsonString.fromValue(
				message.getRole().name().toLowerCase(Locale.ROOT)));
		fields.put("content", contentBlockToJson(message.getContent()));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject contentBlockToJson(
			@NonNull McpContentBlock content) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		if (content instanceof McpTextContent text) {
			fields.put("type", McpJsonString.fromValue("text"));
			fields.put("text", McpJsonString.fromValue(text.getText()));
			addContentAnnotationsAndMetadata(fields, text.getAnnotations(),
					text.getMetadata());
		} else if (content instanceof McpImageContent image) {
			fields.put("type", McpJsonString.fromValue("image"));
			requireBinaryDataFitsOutput(image.dataLength());
			fields.put("data", McpJsonString.fromValue(encodeBinaryData(image.getData())));
			fields.put("mimeType", McpJsonString.fromValue(image.getMimeType()));
			addContentAnnotationsAndMetadata(fields, image.getAnnotations(),
					image.getMetadata());
		} else if (content instanceof McpAudioContent audio) {
			fields.put("type", McpJsonString.fromValue("audio"));
			requireBinaryDataFitsOutput(audio.dataLength());
			fields.put("data", McpJsonString.fromValue(encodeBinaryData(audio.getData())));
			fields.put("mimeType", McpJsonString.fromValue(audio.getMimeType()));
			addContentAnnotationsAndMetadata(fields, audio.getAnnotations(),
					audio.getMetadata());
		} else if (content instanceof McpResourceLink link) {
			fields.put("type", McpJsonString.fromValue("resource_link"));
			fields.put("uri", McpJsonString.fromValue(link.getUri().toString()));
			fields.put("name", McpJsonString.fromValue(link.getName()));
			link.getTitle().ifPresent(value ->
					fields.put("title", McpJsonString.fromValue(value)));
			link.getDescription().ifPresent(value ->
					fields.put("description", McpJsonString.fromValue(value)));
			link.getMimeType().ifPresent(value ->
					fields.put("mimeType", McpJsonString.fromValue(value)));
			if (!link.getIcons().isEmpty())
				fields.put("icons", McpJsonArray.fromElements(link.getIcons().stream()
						.map(DefaultMcpServer::iconToJson)
						.toList()));
			link.getSize().ifPresent(value ->
					fields.put("size", McpJsonNumber.fromValue(
							java.math.BigDecimal.valueOf(value))));
			addContentAnnotationsAndMetadata(fields, link.getAnnotations(),
					link.getMetadata());
		} else if (content instanceof McpEmbeddedResource embedded) {
			fields.put("type", McpJsonString.fromValue("resource"));
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
							.map(role -> McpJsonString.fromValue(
									role.name().toLowerCase(Locale.ROOT)))
							.toList()));
		annotations.getPriority().ifPresent(value -> fields.put("priority",
				McpJsonNumber.fromValue(java.math.BigDecimal.valueOf(value))));
		annotations.getLastModified().ifPresent(value -> fields.put(
				"lastModified", McpJsonString.fromValue(value.toString())));
		return McpJsonObject.fromMembers(fields);
	}

	@NonNull
	private static McpJsonObject resourceContentsToJson(
			@NonNull McpResourceContents contents) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("uri", McpJsonString.fromValue(contents.getUri().toString()));
		contents.getMimeType().ifPresent(value ->
				fields.put("mimeType", McpJsonString.fromValue(value)));
		if (contents instanceof McpTextResourceContents text)
			fields.put("text", McpJsonString.fromValue(text.getText()));
		else if (contents instanceof McpBlobResourceContents blob) {
			requireBinaryDataFitsOutput(blob.dataLength());
			fields.put("blob", McpJsonString.fromValue(encodeBinaryData(blob.getData())));
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
			McpContentBlock content = message.getContent();
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
				requireNonNull(input), this.securityControls);
		Optional<McpTraceLogRecord> traceLogRecord = McpTraceLogRecord.capture(
				context.traceCorrelationToken(),
				this.logRawValidatedTraceIds
						? context.getTraceContext().map(TraceContext::getTraceId)
						: Optional.empty());
		LifecycleObserver observer = this.lifecycleObserver;
		List<Throwable> startThrowables = new ArrayList<>();

		try {
			observer.didStartMcpRequestHandling(context);
		} catch (Throwable throwable) {
			startThrowables.add(throwable);
			safelyLogRequestObservation(observer, LogEvent.with(
					LogEventType.LIFECYCLE_OBSERVER_DID_START_MCP_REQUEST_HANDLING_FAILED,
					"An exception occurred while invoking LifecycleObserver::didStartMcpRequestHandling")
					.build(), startThrowables);
		}
		this.mcpMetricEventDelivery.recordAndDrain(
				McpMetricsEvent.requestStarted(input.endpoint().getPath(),
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

				mcpMetricEventDelivery.recordAndDrain(
						McpMetricsEvent.requestFinished(
								input.endpoint().getPath(),
								metricMethod(input.jsonRpcMethod()),
								outcome, duration), context);
				traceLogRecord.ifPresent(record -> safelyLogRequestObservation(
						observer, LogEvent.with(
								LogEventType.MCP_TRACE_CORRELATION,
								record.toLogMessage()).build(), null));
				try {
					observer.didFinishMcpRequestHandling(context, outcome,
							publicError, duration, immutableThrowables);
				} catch (Throwable throwable) {
					safelyLogRequestObservation(observer, LogEvent.with(
							LogEventType.LIFECYCLE_OBSERVER_DID_FINISH_MCP_REQUEST_HANDLING_FAILED,
							"An exception occurred while invoking LifecycleObserver::didFinishMcpRequestHandling")
							.build(), null);
				}
			}

			@Override
			public void didOpenRequestStream() {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.requestStreamOpened(
								input.endpoint().getPath(),
								metricMethod(input.jsonRpcMethod())), context);
			}

			@Override
			public void didCloseRequestStream(
					@NonNull McpStreamTerminationReason reason,
					@NonNull Duration duration) {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.requestStreamClosed(
								input.endpoint().getPath(),
								metricMethod(input.jsonRpcMethod()), reason,
								duration), context);
			}

			@Override
			public void didOpenSubscription() {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.subscriptionOpened(
								input.endpoint().getPath()), context);
			}

			@Override
			public void didCloseSubscription(
					@NonNull McpStreamTerminationReason reason,
					@NonNull Duration duration) {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.subscriptionClosed(
								input.endpoint().getPath(), reason, duration),
						context);
			}

			@Override
			public void didEmitKeepAlive() {
				mcpMetricEventDelivery.record(
						McpMetricsEvent.keepAliveEmitted(), context);
			}
		};
	}

	@NonNull
	private static String metricMethod(@NonNull String jsonRpcMethod) {
		return BOUNDED_METRIC_METHODS.contains(requireNonNull(jsonRpcMethod))
				? jsonRpcMethod : McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD;
	}

	private void safelyRecordMcpMetrics(@NonNull McpMetricsEvent event,
			@NonNull McpRequestContext context) {
		MetricsCollector collector = this.metricsCollector;
		LifecycleObserver observer = this.lifecycleObserver;
		try {
			collector.didRecordMcpMetricsEvent(requireNonNull(event));
		} catch (Throwable throwable) {
			safelyLogRequestObservation(observer, LogEvent.with(
					LogEventType.METRICS_COLLECTOR_FAILED,
					"An exception occurred while invoking MetricsCollector::didRecordMcpMetricsEvent")
					.build(), null);
		}
	}

	private void safelyRecordMcpServerMetrics(
			@NonNull McpMetricsEvent event) {
		MetricsCollector collector = this.metricsCollector;
		LifecycleObserver observer = this.lifecycleObserver;
		try {
			collector.didRecordMcpMetricsEvent(requireNonNull(event));
		} catch (Throwable throwable) {
			safelyLogRequestObservation(observer, LogEvent.with(
					LogEventType.METRICS_COLLECTOR_FAILED,
					"An exception occurred while invoking MetricsCollector::didRecordMcpMetricsEvent")
					.build(), null);
		}
	}

	private void safelyLogRequestObservation(@NonNull LifecycleObserver observer,
			@NonNull LogEvent event,
			@Nullable List<@NonNull Throwable> requestThrowables) {
		try {
			observer.didReceiveLogEvent(requireNonNull(event));
		} catch (Throwable throwable) {
			LifecycleObserverLogFallback.report(throwable);
			if (requestThrowables != null)
				requestThrowables.add(throwable);
		}
	}

	private void safelyLogStartupDiagnostic(@NonNull String message) {
		try {
			this.lifecycleObserver.didReceiveLogEvent(LogEvent.with(
					LogEventType.MCP_SERVER_CONFIGURATION, message).build());
		} catch (Throwable observerFailure) {
			LifecycleObserverLogFallback.report(observerFailure);
		}
	}

	private void safelyLogUnknownMirroredHeaderName(@NonNull String endpointPath,
			@NonNull String headerName) {
		try {
			this.lifecycleObserver.didReceiveLogEvent(LogEvent.with(
					LogEventType.MCP_UNKNOWN_MIRRORED_HEADER,
					"Unknown MCP mirrored header: endpointPath="
							+ requireNonNull(endpointPath) + ", headerName="
							+ requireNonNull(headerName))
					.build());
		} catch (Throwable observerFailure) {
			LifecycleObserverLogFallback.report(observerFailure);
		}
	}

	private void safelyLogUnexpectedTermination(@NonNull Throwable throwable) {
		requireNonNull(throwable);
		try {
			this.lifecycleObserver.didReceiveLogEvent(LogEvent.with(
					LogEventType.SERVER_TRANSPORT_FAILURE,
					"MCP transport failure: event_loop_terminate")
					.build());
		} catch (Throwable observerFailure) {
			LifecycleObserverLogFallback.report(observerFailure);
		}
	}

	/**
	 * Serializes semantic metric delivery while permitting nested runtime,
	 * server, and Soklet lifecycle deferral.
	 */
	@ThreadSafe
	private final class McpMetricEventDelivery {
		@NonNull
		private final Object lock;
		@NonNull
		private final Queue<@NonNull McpMetricEventDeliveryEntry> pendingEvents;
		private int deferralDepth;
		private boolean delivering;
		private boolean asynchronousDrainRequired;
		private @Nullable Thread deliveryThread;

		private McpMetricEventDelivery() {
			this.lock = new Object();
			this.pendingEvents = new ArrayDeque<>();
		}

		private void beginDeferral() {
			boolean interrupted = false;
			Thread currentThread = Thread.currentThread();
			synchronized (this.lock) {
				this.deferralDepth++;
				while (this.delivering && this.deliveryThread != currentThread) {
					try {
						this.lock.wait();
					} catch (InterruptedException exception) {
						interrupted = true;
					}
				}
			}
			if (interrupted)
				currentThread.interrupt();
		}

		private void beginNonwaitingDeferral() {
			synchronized (this.lock) {
				this.deferralDepth++;
			}
		}

		@NonNull
		private McpMetricEventDeliveryEntry record(
				@NonNull McpMetricsEvent event) {
			return record(event, null);
		}

		@NonNull
		private McpMetricEventDeliveryEntry record(
				@NonNull McpMetricsEvent event,
				@Nullable McpRequestContext requestContext) {
			McpMetricEventDeliveryEntry entry =
					new McpMetricEventDeliveryEntry(event, requestContext);
			synchronized (this.lock) {
				this.pendingEvents.add(entry);
			}
			return entry;
		}

		private void recordAndDrain(@NonNull McpMetricsEvent event,
				@NonNull McpRequestContext requestContext) {
			record(event, requireNonNull(requestContext));
			drain();
		}

		@SuppressWarnings("ReferenceEquality")
		private void discard(
				@NonNull McpMetricEventDeliveryEntry discardedEntry) {
			McpMetricEventDeliveryEntry requiredEntry =
					requireNonNull(discardedEntry);
			synchronized (this.lock) {
				Iterator<McpMetricEventDeliveryEntry> iterator =
						this.pendingEvents.iterator();
				while (iterator.hasNext()) {
					if (iterator.next() == requiredEntry) {
						iterator.remove();
						return;
					}
				}
			}
			throw new IllegalStateException(
					"The provisional MCP metric event is no longer pending.");
		}

		private void drain() {
			drain(false);
		}

		private void drainAsynchronously() {
			drain(true);
		}

		private void drain(boolean asynchronous) {
			boolean interrupted = false;
			boolean deliveryClaimed = false;
			Thread currentThread = Thread.currentThread();
			try {
				synchronized (this.lock) {
					if (asynchronous) {
						while ((this.deferralDepth != 0 || this.delivering)
								&& this.deliveryThread != currentThread) {
							try {
								this.lock.wait();
							} catch (InterruptedException exception) {
								interrupted = true;
							}
						}
						if (this.deferralDepth != 0 || this.delivering)
							return;
						this.asynchronousDrainRequired = false;
					} else if (this.asynchronousDrainRequired
							|| this.deferralDepth != 0 || this.delivering)
						return;
					if (this.pendingEvents.isEmpty())
						return;
					this.delivering = true;
					this.deliveryThread = currentThread;
					deliveryClaimed = true;
				}

				while (true) {
					McpMetricEventDeliveryEntry entry;
					synchronized (this.lock) {
						if (this.deferralDepth != 0
								|| (!asynchronous
								&& this.asynchronousDrainRequired)
								|| this.pendingEvents.isEmpty()) {
							finishDeliveryLocked();
							return;
						}
						if (asynchronous)
							this.asynchronousDrainRequired = false;
						entry = this.pendingEvents.remove();
					}
					@Nullable McpRequestContext requestContext =
							entry.requestContext();
					if (requestContext == null)
						safelyRecordMcpServerMetrics(entry.event());
					else
						safelyRecordMcpMetrics(entry.event(),
								requestContext);
				}
			} finally {
				if (deliveryClaimed) {
					synchronized (this.lock) {
						if (this.delivering
								&& this.deliveryThread == currentThread)
							finishDeliveryLocked();
					}
				}
				if (interrupted)
					currentThread.interrupt();
			}
		}

		private void endDeferral() {
			if (releaseDeferral(false))
				drain();
		}

		private void endLifecycleDeferral() {
			if (releaseDeferral(false))
				drain(true);
		}

		private void endDeferralForAsynchronousDrain() {
			releaseDeferral(true);
		}

		private boolean releaseDeferral(boolean requireAsynchronousDrain) {
			synchronized (this.lock) {
				if (this.deferralDepth == 0)
					throw new IllegalStateException(
							"MCP metric delivery deferral is not active.");
				if (requireAsynchronousDrain)
					this.asynchronousDrainRequired = true;
				this.deferralDepth--;
				this.lock.notifyAll();
				return this.deferralDepth == 0;
			}
		}

		private void finishDeliveryLocked() {
			this.delivering = false;
			this.deliveryThread = null;
			this.lock.notifyAll();
		}
	}

	/** Immutable event plus optional request-scoped failure-log context. */
	@ThreadSafe
	private record McpMetricEventDeliveryEntry(
			@NonNull McpMetricsEvent event,
			@Nullable McpRequestContext requestContext)
			implements PendingMetricRecord {
		private McpMetricEventDeliveryEntry {
			requireNonNull(event);
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
		@NonNull Optional<@NonNull InetSocketAddress> boundAddress,
		int requestHandlerConcurrency, int requestHandlerQueueCapacity,
		int activeHandlerExecutions, int queuedRequests,
		int activeRequestStreams, int activeSubscriptions,
		@NonNull McpProtectionMode protectionMode,
		boolean applicationRequestStateProtectorConfigured,
		@NonNull Optional<@NonNull McpProtectionKeyRingFingerprint>
				protectionKeyRingFingerprint,
		@NonNull Optional<@NonNull McpTraceCorrelationConfigurationFingerprint>
				traceCorrelationConfigurationFingerprint)
		implements McpServerDiagnostics {
	DefaultMcpServerDiagnostics {
		requireNonNull(status);
		boundAddress = requireNonNull(boundAddress).map(address ->
				new InetSocketAddress(address.getAddress(), address.getPort()));
		if (requestHandlerConcurrency < 1)
			throw new IllegalArgumentException(
					"Request-handler concurrency must be positive.");
		if (requestHandlerQueueCapacity < 1)
			throw new IllegalArgumentException(
					"Request-handler queue capacity must be positive.");
		if (activeHandlerExecutions < 0
				|| activeHandlerExecutions > requestHandlerConcurrency)
			throw new IllegalArgumentException(
					"Active handler executions must be between zero and the configured concurrency.");
		if (queuedRequests < 0 || queuedRequests > requestHandlerQueueCapacity)
			throw new IllegalArgumentException(
					"Queued requests must be between zero and the configured queue capacity.");
		boolean terminationProven = status == McpServerStatus.NOT_STARTED
				|| status == McpServerStatus.TERMINATED;
		if (terminationProven && activeHandlerExecutions != 0)
			throw new IllegalArgumentException(
					"A proof-complete MCP server snapshot cannot have active handler executions.");
		if (terminationProven && queuedRequests != 0)
			throw new IllegalArgumentException(
					"A proof-complete MCP server snapshot cannot have queued requests.");
		if (activeRequestStreams < 0)
			throw new IllegalArgumentException(
					"Active request streams must be nonnegative.");
		if (activeSubscriptions < 0
				|| activeSubscriptions > activeRequestStreams)
			throw new IllegalArgumentException(
					"Active subscriptions must be between zero and the active request-stream count.");
		if (terminationProven && activeRequestStreams != 0)
			throw new IllegalArgumentException(
					"A proof-complete MCP server snapshot cannot have active request streams.");
		if (terminationProven && activeSubscriptions != 0)
			throw new IllegalArgumentException(
					"A proof-complete MCP server snapshot cannot have active subscriptions.");
		requireNonNull(protectionMode);
		requireNonNull(protectionKeyRingFingerprint);
		requireNonNull(traceCorrelationConfigurationFingerprint);
		if (applicationRequestStateProtectorConfigured
				!= (protectionMode == McpProtectionMode.CUSTOM_PROTECTOR))
			throw new IllegalArgumentException(
					"Application request-state protector presence must match custom-protector mode.");
		if (protectionKeyRingFingerprint.isPresent()
				!= (protectionMode == McpProtectionMode.PRODUCTION_KEY_RING))
			throw new IllegalArgumentException(
					"Production protection mode must have exactly one key-ring fingerprint.");
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

	@Override
	@NonNull
	public Integer getRequestHandlerConcurrency() {
		return this.requestHandlerConcurrency;
	}

	@Override
	@NonNull
	public Integer getRequestHandlerQueueCapacity() {
		return this.requestHandlerQueueCapacity;
	}

	@Override
	@NonNull
	public Integer getActiveHandlerExecutions() {
		return this.activeHandlerExecutions;
	}

	@Override
	@NonNull
	public Integer getQueuedRequests() {
		return this.queuedRequests;
	}

	@Override
	@NonNull
	public Integer getActiveRequestStreams() {
		return this.activeRequestStreams;
	}

	@Override
	@NonNull
	public Integer getActiveSubscriptions() {
		return this.activeSubscriptions;
	}

	@Override
	@NonNull
	public McpProtectionMode getProtectionMode() {
		return this.protectionMode;
	}

	@Override
	@NonNull
	public Boolean isApplicationRequestStateProtectorConfigured() {
		return this.applicationRequestStateProtectorConfigured;
	}

	@Override
	@NonNull
	public Optional<@NonNull McpProtectionKeyRingFingerprint>
			getProtectionKeyRingFingerprint() {
		return this.protectionKeyRingFingerprint;
	}

	@Override
	@NonNull
	public Optional<@NonNull McpTraceCorrelationConfigurationFingerprint>
			getTraceCorrelationConfigurationFingerprint() {
		return this.traceCorrelationConfigurationFingerprint;
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
	@Override public @NonNull Boolean isNotification() {
		return this.input.notification();
	}
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
	@Override public @NonNull List<@NonNull URI>
	getRequestedResourceSubscriptionUris() {
		return this.input.requestedResourceSubscriptionUris();
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
	private final McpInputResponses inputResponses;
	@NonNull
	private final Optional<@NonNull McpJsonValue> frameworkRequestState;
	@NonNull
	private final Optional<@NonNull String> applicationRequestState;
	@NonNull
	private final McpAdmissionIdentity admissionIdentity;
	@NonNull
	private final McpClientCapabilities clientCapabilities;
	@NonNull
	private final Optional<@NonNull McpLogLevel> deprecatedLogLevel;
	@NonNull
	private final McpRequestPropagation requestPropagation;
	@NonNull
	private final List<@NonNull String> acceptLanguageValues;
	@NonNull
	private final Optional<
			DefaultMcpSecurityControls.@NonNull TraceCorrelationToken>
			traceCorrelationToken;

	DefaultMcpRequestContext(@NonNull ToolInvocation invocation) {
		this(requireNonNull(invocation).request(), invocation.endpoint(),
				invocation.endpointPathParameters(), invocation.jsonRpcMethod(),
				Optional.of(invocation.requestId()), invocation.protocolVersion(),
				Optional.of(invocation.operationName()),
				invocation.clientInformation(), invocation.clientCapabilitiesJson(),
				invocation.requestMetadata(),
				invocation.requestContext().getInputResponses(),
				invocation.requestContext().getFrameworkRequestState(),
				invocation.requestContext().getApplicationRequestState(),
				invocation.admissionIdentity(), Optional.empty());
	}

	DefaultMcpRequestContext(@NonNull PromptInvocation invocation) {
		this(requireNonNull(invocation).request(), invocation.endpoint(),
				invocation.endpointPathParameters(), invocation.jsonRpcMethod(),
				Optional.of(invocation.requestId()), invocation.protocolVersion(),
				Optional.of(invocation.operationName()),
				invocation.clientInformation(), invocation.clientCapabilitiesJson(),
				invocation.requestMetadata(),
				invocation.requestContext().getInputResponses(),
				invocation.requestContext().getFrameworkRequestState(),
				invocation.requestContext().getApplicationRequestState(),
				invocation.admissionIdentity(), Optional.empty());
	}

	DefaultMcpRequestContext(@NonNull ResourceInvocation invocation) {
		this(requireNonNull(invocation).request(), invocation.endpoint(),
				invocation.endpointPathParameters(), invocation.jsonRpcMethod(),
				Optional.of(invocation.requestId()), invocation.protocolVersion(),
				Optional.of(invocation.operationName()),
				invocation.clientInformation(), invocation.clientCapabilitiesJson(),
				invocation.requestMetadata(),
				invocation.requestContext().getInputResponses(),
				invocation.requestContext().getFrameworkRequestState(),
				invocation.requestContext().getApplicationRequestState(),
				invocation.admissionIdentity(), Optional.empty());
	}

	DefaultMcpRequestContext(@NonNull ResourceListInvocation invocation) {
		this(requireNonNull(invocation).request(), invocation.endpoint(),
				invocation.endpointPathParameters(), invocation.jsonRpcMethod(),
				Optional.of(invocation.requestId()), invocation.protocolVersion(),
				Optional.empty(),
				invocation.clientInformation(), invocation.clientCapabilitiesJson(),
				invocation.requestMetadata(),
				invocation.requestContext().getInputResponses(),
				invocation.requestContext().getFrameworkRequestState(),
				invocation.requestContext().getApplicationRequestState(),
				invocation.admissionIdentity(), Optional.empty());
	}

	DefaultMcpRequestContext(@NonNull RequestObservationInput input) {
		this(input, Optional.empty());
	}

	DefaultMcpRequestContext(@NonNull RequestObservationInput input,
			@NonNull DefaultMcpSecurityControls securityControls) {
		this(input, Optional.of(requireNonNull(securityControls)));
	}

	private DefaultMcpRequestContext(@NonNull RequestObservationInput input,
			@NonNull Optional<@NonNull DefaultMcpSecurityControls>
					securityControls) {
		this(requireNonNull(input).request(), input.endpoint(),
				input.endpointPathParameters(), input.jsonRpcMethod(),
				input.requestId(), input.protocolVersion(), input.operationName(),
				input.clientInformation(), input.clientCapabilities(),
				input.requestMetadata(), input.inputResponses(),
				input.frameworkRequestState(), input.applicationRequestState(),
				input.admissionIdentity(), input.acceptLanguageValues(),
				requireNonNull(securityControls));
	}

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
			@NonNull McpInputResponses inputResponses,
			@NonNull Optional<@NonNull McpJsonValue> frameworkRequestState,
			@NonNull Optional<@NonNull String> applicationRequestState,
			@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull Optional<@NonNull DefaultMcpSecurityControls>
					securityControls) {
		this(request, endpoint, endpointPathParameters, jsonRpcMethod, requestId,
				protocolVersion, operationName, clientInformation,
				clientCapabilitiesJson, requestMetadata, inputResponses,
				frameworkRequestState, applicationRequestState,
				admissionIdentity, acceptLanguageValues(request), securityControls);
	}

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
			@NonNull McpInputResponses inputResponses,
			@NonNull Optional<@NonNull McpJsonValue> frameworkRequestState,
			@NonNull Optional<@NonNull String> applicationRequestState,
			@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull List<@NonNull String> acceptLanguageValues,
			@NonNull Optional<@NonNull DefaultMcpSecurityControls>
					securityControls) {
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
		this.inputResponses = requireNonNull(inputResponses);
		this.frameworkRequestState = requireNonNull(frameworkRequestState);
		this.applicationRequestState = requireNonNull(applicationRequestState);
		if (frameworkRequestState.isPresent()
				&& applicationRequestState.isPresent())
			throw new IllegalArgumentException(
					"At most one MCP request-state value may be present.");
		this.admissionIdentity = requireNonNull(admissionIdentity);
		this.acceptLanguageValues = List.copyOf(
				requireNonNull(acceptLanguageValues));
		this.clientCapabilities = McpClientCapabilities.fromJson(
				clientCapabilitiesJson);
		this.deprecatedLogLevel = requestMetadata
				.find(DEPRECATED_LOG_LEVEL_KEY)
				.filter(McpJsonString.class::isInstance)
				.map(McpJsonString.class::cast)
				.map(McpJsonString::getValue)
				.map(value -> McpLogLevel.valueOf(
						value.toUpperCase(Locale.ROOT)));
		this.requestPropagation = McpRequestPropagation.fromMetadata(
				requestMetadata);
		this.traceCorrelationToken = requireNonNull(securityControls)
				.flatMap(controls -> this.requestPropagation.traceContext()
						.flatMap(controls::deriveTraceCorrelationToken));
	}

	@NonNull
	static List<@NonNull String> acceptLanguageValues(
			@NonNull Request request) {
		Set<String> values = requireNonNull(request)
				.getHeaders().get("Accept-Language");
		return values == null ? List.of() : List.copyOf(values);
	}

	@NonNull
	List<@NonNull String> acceptLanguageValues() {
		return this.acceptLanguageValues;
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
	@Override public @NonNull McpInputResponses getInputResponses() {
		return this.inputResponses;
	}
	@Override public @NonNull Optional<@NonNull McpJsonValue>
	getFrameworkRequestState() {
		return this.frameworkRequestState;
	}
	@Override public @NonNull Optional<@NonNull String>
	getApplicationRequestState() {
		return this.applicationRequestState;
	}
	@Override
	public @NonNull Optional<@NonNull McpLogLevel> getDeprecatedLogLevel() {
		return this.deprecatedLogLevel;
	}
	@Override public @NonNull Optional<@NonNull TraceContext> getTraceContext() {
		return this.requestPropagation.traceContext();
	}
	@NonNull
	Optional<DefaultMcpSecurityControls.@NonNull TraceCorrelationToken>
	traceCorrelationToken() {
		return this.traceCorrelationToken;
	}
	@Override public @NonNull Map<@NonNull String, @NonNull String> getBaggage() {
		return this.requestPropagation.baggage();
	}
	@Override public @NonNull McpAdmissionIdentity getAdmissionIdentity() {
		return this.admissionIdentity;
	}
}
