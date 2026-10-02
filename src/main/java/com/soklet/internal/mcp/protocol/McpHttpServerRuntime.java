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

import com.soklet.internal.mcp.protocol.McpSubscriptionEventSource.Event;
import com.soklet.internal.mcp.protocol.McpSubscriptionEventSource.Registration;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.TaskManagerAdapter;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.TaskSnapshot;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.CatalogAccessAdapter;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.CatalogAccessInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.CatalogAccessSession;
import com.soklet.CallbackRegistration;
import com.soklet.CancelationToken;
import com.soklet.Cors;
import com.soklet.CorsPreflight;
import com.soklet.CorsPreflightResponse;
import com.soklet.CorsResponse;
import com.soklet.HttpMethod;
import com.soklet.MediaRange;
import com.soklet.MetricsCollector.TransportFailureReason;
import com.soklet.McpEndpoint;
import com.soklet.McpImplementation;
import com.soklet.McpInputResponses;
import com.soklet.McpInvocationFeatures;
import com.soklet.McpMetricsEvent;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestId;
import com.soklet.McpRequestOutcome;
import com.soklet.McpRequestStateMode;
import com.soklet.McpInputRequest;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationOptions;
import com.soklet.McpStreamTerminationReason;
import com.soklet.McpSubscriptionAuthorization;
import com.soklet.McpSubscriptionAuthorizationContext;
import com.soklet.McpSubscriptionAuthorizer;
import com.soklet.McpTaskStatus;
import com.soklet.Request;
import com.soklet.StatusCode;
import com.soklet.StreamTerminationReason;
import com.soklet.TraceContext;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import com.soklet.internal.microhttp.ConnectionListener;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.Handler;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import com.soklet.internal.microhttp.NoopLogger;
import com.soklet.internal.microhttp.Options;
import com.soklet.internal.microhttp.TransportFailureObserver;
import com.soklet.internal.util.HostHeaderValidator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static com.soklet.internal.ObjectIdentity.sameInstance;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpRequestExecutionSnapshot(int retainedRequestControls,
		int queuedProtocolRequests, int activeIdentifiedRequestExchanges,
		int activeResponseStreams, long bufferedStreamFrames,
		long bufferedStreamBytes, long terminalStreamBytes,
		int maximumObservedBufferedFramesPerStream,
		int maximumObservedBufferedBytesPerStream,
		long unknownMirroredHeaderOccurrences) {
}

/** Positive retained work observed for one MCP lifecycle generation. */
@ThreadSafe
record McpLifecycleEvidence(boolean eventLoop, boolean connection,
		boolean executorTask, boolean stream, boolean callback,
		boolean subscriptionRegistration) {
	boolean empty() {
		return !eventLoop && !connection && !executorTask && !stream
				&& !callback && !subscriptionRegistration;
	}
}

/**
 * Atomic point-in-time view of the MCP listener lifecycle state.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpHttpServerLifecycleSnapshot(boolean started, boolean stopRequired,
		@NonNull Optional<@NonNull InetSocketAddress> boundAddress,
		boolean residualApplicationExecutions) {
	McpHttpServerLifecycleSnapshot {
		requireNonNull(boundAddress);
		if (started && boundAddress.isEmpty())
			throw new IllegalArgumentException(
					"A started MCP listener must have a retained bound address.");
		if (started && !stopRequired)
			throw new IllegalArgumentException(
					"A started MCP listener must require a stop transition.");
	}
}

/**
 * Atomic point-in-time view of MCP listener lifecycle, application
 * handler-capacity, and live-stream diagnostics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpHttpServerDiagnosticsSnapshot(boolean started, boolean stopRequired,
		@NonNull Optional<@NonNull InetSocketAddress> boundAddress,
		boolean residualApplicationExecutions, int requestHandlerConcurrency,
		int requestHandlerQueueCapacity, int activeHandlerExecutions,
		int queuedRequests, int activeRequestStreams, int activeSubscriptions) {
	McpHttpServerDiagnosticsSnapshot {
		requireNonNull(boundAddress);
		if (started && boundAddress.isEmpty())
			throw new IllegalArgumentException(
					"A started MCP listener must have a retained bound address.");
		if (started && !stopRequired)
			throw new IllegalArgumentException(
					"A started MCP listener must require a stop transition.");
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
		if (!started && !residualApplicationExecutions
				&& activeHandlerExecutions != 0)
			throw new IllegalArgumentException(
					"A non-residual stopped MCP listener snapshot cannot have active handler executions.");
		if (!started && !residualApplicationExecutions && queuedRequests != 0)
			throw new IllegalArgumentException(
					"A non-residual stopped MCP diagnostics snapshot cannot have queued requests.");
		if (activeRequestStreams < 0)
			throw new IllegalArgumentException(
					"Active request streams must be nonnegative.");
		if (activeSubscriptions < 0
				|| activeSubscriptions > activeRequestStreams)
			throw new IllegalArgumentException(
					"Active subscriptions must be between zero and the active request-stream count.");
		if (!started && !residualApplicationExecutions
				&& activeRequestStreams != 0)
			throw new IllegalArgumentException(
					"A non-residual stopped MCP diagnostics snapshot cannot have active request streams.");
		if (!started && !residualApplicationExecutions
				&& activeSubscriptions != 0)
			throw new IllegalArgumentException(
					"A non-residual stopped MCP diagnostics snapshot cannot have active subscriptions.");
	}
}

/**
 * Package-private production runtime for MCP Streamable HTTP. It owns a
 * listener that is independent from Soklet's application HTTP server, routes
 * one or more fixed endpoint paths, handles framework-owned discovery, and
 * hands registered operations to the server-wide bounded application execution
 * runtime without retaining a protocol request-processing thread.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpHttpServerRuntime implements AutoCloseable {
	interface SubscriptionAuthorizationSchedulingTestHooks {
		default void beforeRenewalScheduling() {
			// No-op outside deterministic race tests.
		}

		default void beforeReconciliationScheduling() {
			// No-op outside deterministic race tests.
		}
	}

	@NonNull
	private static final SubscriptionAuthorizationSchedulingTestHooks
			NO_OP_SUBSCRIPTION_AUTHORIZATION_SCHEDULING_TEST_HOOKS =
			new SubscriptionAuthorizationSchedulingTestHooks() {};
	@NonNull
	private static volatile SubscriptionAuthorizationSchedulingTestHooks
			subscriptionAuthorizationSchedulingTestHooks =
			NO_OP_SUBSCRIPTION_AUTHORIZATION_SCHEDULING_TEST_HOOKS;

	static void setSubscriptionAuthorizationSchedulingTestHooks(
			@Nullable SubscriptionAuthorizationSchedulingTestHooks testHooks) {
		subscriptionAuthorizationSchedulingTestHooks = testHooks == null
				? NO_OP_SUBSCRIPTION_AUTHORIZATION_SCHEDULING_TEST_HOOKS
				: testHooks;
	}

	@NonNull
	static final String OMITTED_CORS_AUTHORIZER_DIAGNOSTIC =
			"No CorsAuthorizer is configured for the MCP server; requests carrying an "
					+ "Origin header will be rejected.";
	@NonNull
	static final String RESIDUAL_TRANSPORT_DIAGNOSTIC =
			"The MCP transport did not terminate within the shutdown deadline.";
	@NonNull
	static final String RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_DIAGNOSTIC =
			"The MCP subscription event-source registrations did not close "
					+ "successfully within the shutdown deadline.";
	@NonNull
	static final String RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_RESTART_DIAGNOSTIC =
			"Cannot start MCP server while residual subscription event-source "
					+ "registrations remain";
	@NonNull
	static final String SIMULATION_REQUIRES_STOPPED_SERVER =
			"MCP simulation requires a stopped server with no residual handler executions.";
	@NonNull
	static final String SIMULATION_SHUTDOWN_TIMED_OUT =
			"MCP simulator shutdown timed out with residual handler executions.";
	@NonNull
	private static final Consumer<@NonNull String> DEFAULT_STARTUP_DIAGNOSTIC_CONSUMER =
			diagnostic -> System.err.printf("%s%n", diagnostic);
	@NonNull
	private static final Consumer<@NonNull Throwable>
			DEFAULT_UNEXPECTED_TERMINATION_CONSUMER = throwable -> {};
	@NonNull
	private static final String CONTENT_TYPE = "Content-Type";
	@NonNull
	private static final String ACCEPT = "Accept";
	@NonNull
	private static final String HOST = "Host";
	@NonNull
	private static final String ORIGIN = "Origin";
	@NonNull
	private static final String MCP_PROTOCOL_VERSION = "MCP-Protocol-Version";
	private static final String MCP_SESSION_ID = "Mcp-Session-Id";
	@NonNull
	private static final String MCP_METHOD = "Mcp-Method";
	@NonNull
	private static final String MCP_NAME = "Mcp-Name";
	@NonNull
	private static final String TASKS_EXTENSION_IDENTIFIER =
			"io.modelcontextprotocol/tasks";
	@NonNull
	private static final Set<@NonNull String> TASK_REQUEST_METHODS =
			Set.of("tasks/get", "tasks/update", "tasks/cancel");
	@NonNull
	private static final String CACHE_CONTROL = "Cache-Control";
	@NonNull
	private static final String CACHE_CONTROL_NO_STORE = "no-store";
	@NonNull
	private static final String RETRY_AFTER = "Retry-After";
	@NonNull
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final int SOKLET_RATE_LIMITED = -31999;
	private static final int SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER = -31998;
	private static final int MAXIMUM_ADMISSION_REJECTION_HEADER_COUNT = 100;
	private static final int MAXIMUM_ADMISSION_REJECTION_HEADER_BYTES = 64 * 1_024;
	private static final int MAXIMUM_RESOURCE_LIST_DIAGNOSTIC_VALUE_CHARACTERS =
			256;
	private static final int MAXIMUM_RESOURCE_SUBSCRIPTION_URIS = 256;
	private static final int MAXIMUM_TASK_SUBSCRIPTION_IDS = 256;
	private static final int MAXIMUM_TASK_NOTIFICATION_PROJECTION_CONCURRENCY = 4;
	private static final int MAXIMUM_TASK_NOTIFICATION_PROJECTION_QUEUE_CAPACITY =
			128;
	private static final long MAXIMUM_SUBSCRIPTION_RENEWAL_STAGGER_NANOS =
			Duration.ofSeconds(1).toNanos();
	@NonNull
	static final Set<@NonNull Integer> PRODUCED_PROTOCOL_ERROR_CODES = Set.of(
			McpJsonRpcError.PARSE_ERROR,
			McpJsonRpcError.INVALID_REQUEST,
			McpJsonRpcError.METHOD_NOT_FOUND,
			McpJsonRpcError.INVALID_PARAMS,
			McpJsonRpcError.INTERNAL_ERROR,
			McpJsonRpcError.HEADER_MISMATCH,
			McpJsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY,
			McpJsonRpcError.UNSUPPORTED_PROTOCOL_VERSION,
			SOKLET_RATE_LIMITED,
			SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER);
	private static final int APPLICATION_REQUEST_STATE_MAXIMUM_BYTES = 65_536;
	@NonNull
	private static final Set<@NonNull String> FRAMEWORK_OWNED_POLICY_HEADERS = Set.of(
			"cache-control", "connection", "content-encoding", "content-length",
			"content-type", "keep-alive", "proxy-authenticate",
			"proxy-authorization", "proxy-connection", "te", "trailer",
			"transfer-encoding", "upgrade", "retry-after");
	@NonNull
	private static final Set<@NonNull String> FORBIDDEN_LEGACY_MCP_POLICY_HEADERS = Set.of(
			"mcp-session-id", "last-event-id");
	@NonNull
	private static final Set<@NonNull HttpMethod> MCP_HTTP_METHODS =
			Set.of(HttpMethod.POST, HttpMethod.OPTIONS);
	@NonNull
	private static final Set<@NonNull String> MCP_PREFLIGHT_REQUEST_HEADERS = Set.of(
			"Accept", "Authorization", "Content-Type", "MCP-Protocol-Version",
			"Mcp-Method", "Mcp-Name");
	@NonNull
	private static final Set<@NonNull String> MCP_EXPOSED_RESPONSE_HEADERS =
			Set.of("WWW-Authenticate");
	private static final byte @NonNull [] EMPTY_BODY = new byte[0];

	@NonNull
	private final McpHttpTransportConfiguration transportConfiguration;
	@NonNull
	private final ThreadLocal<AtomicInteger> lifecycleProofExecutionDepth;
	@NonNull
	private final McpJsonLimits jsonLimits;
	@NonNull
	private final McpJsonCodec jsonCodec;
	@NonNull
	private final McpJsonRpcEnvelopeCodec envelopeCodec;
	@NonNull
	private final ConcurrentHashMap<@NonNull CanonicalCatalogKey, @NonNull Long>
			canonicalCatalogDocumentBytes = new ConcurrentHashMap<>();
	@NonNull
	private final McpRequestWireMapper requestWireMapper;
	@NonNull
	private final McpProtocolProfileRegistry protocolProfiles;
	@NonNull
	private final McpMirroredHeaderCodec mirroredHeaderCodec;
	@NonNull
	private final McpCustomMirroredHeaderValidator customMirroredHeaderValidator;
	@NonNull
	private final Map<@NonNull String, @NonNull EndpointRuntime> endpointsByPath;
	@NonNull
	private final McpApplicationExecutionConfiguration applicationConfiguration;
	@NonNull
	private final McpApplicationClock applicationClock;
	@NonNull
	private final McpApplicationHandlerExecutorFactory applicationExecutorFactory;
	@NonNull
	private final McpApplicationExecutionObserver applicationExecutionObserver;
	@NonNull
	private final TransportMetricDrainScheduler transportMetricDrainScheduler;
	@NonNull
	private final TransportFailureObserver transportFailureObserver;
	@NonNull
	private final McpFrameworkRequestStateRuntime requestStateRuntime;
	@NonNull
	private final McpSubscriptionRuntimeConfiguration
			subscriptionRuntimeConfiguration;
	@NonNull
	private final Consumer<@NonNull String> startupDiagnosticConsumer;
	@NonNull
	private final Consumer<@NonNull Throwable> unexpectedTerminationConsumer;
	private final McpServerRuntimeBridge.@NonNull LifecycleAdapter lifecycleAdapter;
	@NonNull
	private final Object lifecycleLock;
	@NonNull
	private final Map<@NonNull MicrohttpRequest, @NonNull RequestControl> requestControls;
	@NonNull
	private final AtomicInteger activeIdentifiedRequestExchangeCount;
	@NonNull
	private final Object streamDiagnosticsLock;
	private int activeRequestStreams;
	private int activeSubscriptions;
	@NonNull
	private final Object subscriptionLock;
	@NonNull
	private final Map<@NonNull String, @NonNull Set<@NonNull RequestControl>>
			activeSubscriptionsByEndpointPath;
	@NonNull
	private final Map<@NonNull McpEffectivePartition, @NonNull Integer>
			activeSubscriptionCountsByPartition;
	@NonNull
	private final Set<@NonNull RequestControl> pendingSubscriptions;
	@NonNull
	private final Map<@NonNull String, @NonNull Object>
			localizationInvalidationTokens;
	@NonNull
	private final Map<@NonNull String, @NonNull Object>
			catalogInvalidationTokens;
	private boolean subscriptionsAccepting;
	private long subscriptionReconciliationGeneration;
	@NonNull
	private final List<@NonNull SubscriptionSourceGroup> subscriptionSourceGroups;
	@NonNull
	private List<@NonNull SubscriptionSourceRegistrationControl>
			subscriptionSourceRegistrations;
	@NonNull
	private List<@NonNull SubscriptionSourceRegistrationControl>
			residualSubscriptionSourceRegistrations;
	@NonNull
	private final AtomicLong processorThreadSequence;
	@NonNull
	private final AtomicLong subscriptionCloseThreadSequence;
	@NonNull
	private final AtomicLong unknownMirroredHeaderOccurrences;
	@NonNull
	private final McpUnknownMirroredHeaderNameDiagnostics
			unknownMirroredHeaderNameDiagnostics;
	@NonNull
	private LifecycleState lifecycleState;
	private @Nullable EventLoop eventLoop;
	private @Nullable EventLoop residualEventLoop;
	private @Nullable ThreadPoolExecutor requestProcessor;
	private @Nullable ThreadPoolExecutor residualRequestProcessor;
	private @Nullable McpApplicationExecution applicationExecution;
	private @Nullable McpApplicationExecution residualApplicationExecution;
	private @Nullable SimulationGeneration simulationGeneration;
	private Map<String, Set<String>> legacySessionRevisions = Map.of();
	private @Nullable McpLegacySessionStore legacySessionStore;
	private @Nullable McpLegacySessionTransportAdmission legacyTransportAdmission;
	private Map<String, Map<String, Set<McpResourceNotificationType>>> legacyTransportFamilies = Map.of();
	private final Map<RequestControl, LegacyGetControl> legacyGetControls = new ConcurrentHashMap<>();
	private final Set<LegacyUriGrantControl> legacyUriGrantControls = ConcurrentHashMap.newKeySet();
	private final Object legacyMaintenanceLock = new Object();
	private final McpLegacyHttpControlBudget legacyHttpControlBudget;
	static final int MAXIMUM_LEGACY_MAINTENANCE_JOBS = 4;
	static final int MAXIMUM_LEGACY_MAINTENANCE_DISPATCHES_PER_SECOND = 64;
	static final long LEGACY_MAINTENANCE_RETRY_NANOS = 50_000_000L;
	private final ArrayDeque<Long> legacyMaintenanceDispatchTimes = new ArrayDeque<>();
	private long legacyTransportReconciliationGeneration;
	private int legacyMaintenanceActive;
	private @Nullable LegacySessionOwnerResolver legacySessionOwnerResolver;
	private boolean legacySessionsConfigured;
	private boolean legacyAnonymousSessionsAllowed;

	@FunctionalInterface
	interface LegacySessionOwnerResolver {
		String resolve(McpAdmissionIdentity admissionIdentity) throws Exception;
	}
	private @Nullable ThreadPoolExecutor residualSimulationRequestProcessor;
	private @Nullable McpApplicationExecution residualSimulationApplicationExecution;
	@NonNull
	private List<@NonNull SubscriptionSourceRegistrationControl>
			residualSimulationSubscriptionSourceRegistrations;
	private @Nullable InetSocketAddress boundAddress;
	private @Nullable AtomicReference<@NonNull ListenerState> currentReadiness;
	private @Nullable SubscriptionRegistrationCloseBatch lifecycleCloseBatch;
	private boolean lifecycleQuiesceRequested;
	private boolean lifecycleForceRequested;
	private boolean lifecycleQuiesced;
	private boolean lifecycleForced;
	private boolean lifecycleGracefulDeadlinePresent;
	private long lifecycleGracefulDeadlineNanos;
	private boolean lifecycleForcedDeadlinePresent;
	private long lifecycleForcedDeadlineNanos;
	private boolean lifecycleStartupInProgress;
	private boolean lifecycleStartupClaimed;
	private McpServerRuntimeBridge.LifecycleAdapter.@Nullable Generation
			lifecycleStartupGeneration;

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint) {
		this(transportConfiguration, endpointPolicy, endpoint,
				McpJsonLimits.productionDefaults(), McpApplicationRequestRouter.empty(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production());
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings) {
		this(transportConfiguration, endpointBindings,
				McpJsonLimits.productionDefaults(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(),
				DEFAULT_STARTUP_DIAGNOSTIC_CONSUMER,
				DEFAULT_UNEXPECTED_TERMINATION_CONSUMER);
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpJsonLimits jsonLimits) {
		this(transportConfiguration, endpointPolicy, endpoint, jsonLimits,
				McpApplicationRequestRouter.empty(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production());
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer) {
		this(transportConfiguration, endpointPolicy, endpoint,
				McpJsonLimits.productionDefaults(), McpApplicationRequestRouter.empty(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(),
				startupDiagnosticConsumer);
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpApplicationRequestRouter applicationRouter,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock) {
		this(transportConfiguration, endpointPolicy, endpoint,
				McpJsonLimits.productionDefaults(), applicationRouter,
				applicationConfiguration, applicationClock,
				McpApplicationHandlerExecutorFactory.production());
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationRequestRouter applicationRouter,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory) {
		this(transportConfiguration, endpointPolicy, endpoint, jsonLimits,
				applicationRouter, applicationConfiguration, applicationClock,
				applicationExecutorFactory, DEFAULT_STARTUP_DIAGNOSTIC_CONSUMER);
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationRequestRouter applicationRouter,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer) {
		this(transportConfiguration, endpointPolicy, endpoint, jsonLimits,
				applicationRouter, applicationConfiguration, applicationClock,
				applicationExecutorFactory, startupDiagnosticConsumer,
				DEFAULT_UNEXPECTED_TERMINATION_CONSUMER);
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationRequestRouter applicationRouter,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		this(transportConfiguration,
				List.of(new McpHttpEndpointBinding(endpointPolicy, endpoint,
						applicationRouter)),
				jsonLimits, applicationConfiguration, applicationClock,
				applicationExecutorFactory, startupDiagnosticConsumer,
				unexpectedTerminationConsumer);
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		this(transportConfiguration, endpointBindings, jsonLimits,
				applicationConfiguration, applicationClock, applicationExecutorFactory,
				startupDiagnosticConsumer, unexpectedTerminationConsumer,
				Optional.empty());
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer,
			@NonNull Optional<@NonNull BiConsumer<@NonNull String, @NonNull String>>
					unknownMirroredHeaderNameDiagnosticConsumer) {
		this(transportConfiguration, endpointBindings, jsonLimits,
				applicationConfiguration, applicationClock,
				applicationExecutorFactory, startupDiagnosticConsumer,
				unexpectedTerminationConsumer,
				unknownMirroredHeaderNameDiagnosticConsumer,
				McpFrameworkRequestStateRuntime.disabledInstance());
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer,
			@NonNull Optional<@NonNull BiConsumer<@NonNull String, @NonNull String>>
					unknownMirroredHeaderNameDiagnosticConsumer,
			@NonNull McpFrameworkRequestStateRuntime requestStateRuntime) {
		this(transportConfiguration, endpointBindings, jsonLimits,
				applicationConfiguration, applicationClock,
				applicationExecutorFactory, startupDiagnosticConsumer,
				unexpectedTerminationConsumer,
				unknownMirroredHeaderNameDiagnosticConsumer, requestStateRuntime,
				McpSubscriptionRuntimeConfiguration.productionDefaults());
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer,
			@NonNull Optional<@NonNull BiConsumer<@NonNull String, @NonNull String>>
					unknownMirroredHeaderNameDiagnosticConsumer,
			@NonNull McpFrameworkRequestStateRuntime requestStateRuntime,
			@NonNull McpSubscriptionRuntimeConfiguration
					subscriptionRuntimeConfiguration) {
		this(transportConfiguration, endpointBindings, jsonLimits,
				applicationConfiguration, applicationClock,
				applicationExecutorFactory, startupDiagnosticConsumer,
				unexpectedTerminationConsumer,
				unknownMirroredHeaderNameDiagnosticConsumer, requestStateRuntime,
				subscriptionRuntimeConfiguration,
				McpApplicationExecutionObserver.disabledInstance());
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer,
			@NonNull Optional<@NonNull BiConsumer<@NonNull String, @NonNull String>>
					unknownMirroredHeaderNameDiagnosticConsumer,
			@NonNull McpFrameworkRequestStateRuntime requestStateRuntime,
			@NonNull McpSubscriptionRuntimeConfiguration
					subscriptionRuntimeConfiguration,
			@NonNull McpApplicationExecutionObserver applicationExecutionObserver) {
		this(transportConfiguration, endpointBindings, jsonLimits,
				applicationConfiguration, applicationClock,
				applicationExecutorFactory, startupDiagnosticConsumer,
				unexpectedTerminationConsumer,
				unknownMirroredHeaderNameDiagnosticConsumer, requestStateRuntime,
				subscriptionRuntimeConfiguration, applicationExecutionObserver,
				installedProfilesForBindings(endpointBindings),
				McpServerRuntimeBridge.LifecycleAdapter.disabledInstance());
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer,
			@NonNull Optional<@NonNull BiConsumer<@NonNull String, @NonNull String>>
					unknownMirroredHeaderNameDiagnosticConsumer,
			@NonNull McpFrameworkRequestStateRuntime requestStateRuntime,
			@NonNull McpSubscriptionRuntimeConfiguration
					subscriptionRuntimeConfiguration,
			@NonNull McpApplicationExecutionObserver applicationExecutionObserver,
			McpServerRuntimeBridge.@NonNull LifecycleAdapter lifecycleAdapter) {
		this(transportConfiguration, endpointBindings, jsonLimits,
				applicationConfiguration, applicationClock,
				applicationExecutorFactory, startupDiagnosticConsumer,
				unexpectedTerminationConsumer,
				unknownMirroredHeaderNameDiagnosticConsumer, requestStateRuntime,
				subscriptionRuntimeConfiguration, applicationExecutionObserver,
				installedProfilesForBindings(endpointBindings), lifecycleAdapter);
	}

	@NonNull
	private static McpProtocolProfileRegistry installedProfilesForBindings(
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings) {
		return requireNonNull(endpointBindings).stream()
				.anyMatch(binding -> binding.supportedRevisions().stream()
						.anyMatch(McpLegacyHttpWire::isLegacyRevision))
				? McpCompatibilityProtocolProfiles.REGISTRY
				: McpProductionProtocolProfiles.REGISTRY;
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer,
			@NonNull Optional<@NonNull BiConsumer<@NonNull String, @NonNull String>>
					unknownMirroredHeaderNameDiagnosticConsumer,
			@NonNull McpFrameworkRequestStateRuntime requestStateRuntime,
			@NonNull McpSubscriptionRuntimeConfiguration
					subscriptionRuntimeConfiguration,
			@NonNull McpApplicationExecutionObserver applicationExecutionObserver,
			@NonNull McpProtocolProfileRegistry protocolProfiles) {
		this(transportConfiguration, endpointBindings, jsonLimits,
				applicationConfiguration, applicationClock,
				applicationExecutorFactory, startupDiagnosticConsumer,
				unexpectedTerminationConsumer,
				unknownMirroredHeaderNameDiagnosticConsumer, requestStateRuntime,
				subscriptionRuntimeConfiguration, applicationExecutionObserver,
				protocolProfiles,
				McpServerRuntimeBridge.LifecycleAdapter.disabledInstance());
	}

	McpHttpServerRuntime(
			@NonNull McpHttpTransportConfiguration transportConfiguration,
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings,
			@NonNull McpJsonLimits jsonLimits,
			@NonNull McpApplicationExecutionConfiguration applicationConfiguration,
			@NonNull McpApplicationClock applicationClock,
			@NonNull McpApplicationHandlerExecutorFactory applicationExecutorFactory,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer,
			@NonNull Optional<@NonNull BiConsumer<@NonNull String, @NonNull String>>
					unknownMirroredHeaderNameDiagnosticConsumer,
			@NonNull McpFrameworkRequestStateRuntime requestStateRuntime,
			@NonNull McpSubscriptionRuntimeConfiguration
					subscriptionRuntimeConfiguration,
			@NonNull McpApplicationExecutionObserver applicationExecutionObserver,
			@NonNull McpProtocolProfileRegistry protocolProfiles,
			McpServerRuntimeBridge.@NonNull LifecycleAdapter lifecycleAdapter) {
		this.transportConfiguration = requireNonNull(transportConfiguration);
		this.lifecycleProofExecutionDepth = ThreadLocal.withInitial(AtomicInteger::new);
		this.jsonLimits = requireNonNull(jsonLimits);
		this.applicationConfiguration = requireNonNull(applicationConfiguration);
		this.applicationClock = requireNonNull(applicationClock);
		this.legacyHttpControlBudget = new McpLegacyHttpControlBudget(this.applicationClock);
		this.applicationExecutorFactory = requireNonNull(applicationExecutorFactory);
		this.applicationExecutionObserver = requireNonNull(
				applicationExecutionObserver);
		this.transportMetricDrainScheduler =
				new TransportMetricDrainScheduler(this.applicationExecutionObserver);
		this.transportFailureObserver = this::beginTransportFailure;
		this.requestStateRuntime = requireNonNull(requestStateRuntime);
		this.subscriptionRuntimeConfiguration = requireNonNull(
				subscriptionRuntimeConfiguration);
		this.startupDiagnosticConsumer = requireNonNull(startupDiagnosticConsumer);
		this.unexpectedTerminationConsumer = requireNonNull(
				unexpectedTerminationConsumer);
		this.lifecycleAdapter = requireNonNull(lifecycleAdapter);
		if (transportConfiguration.maximumRequestBodyBytes()
				> jsonLimits.maximumInputBytes())
			throw new IllegalArgumentException("The HTTP request-body limit must not exceed "
					+ "the strict JSON input limit.");

		McpJsonCodec jsonCodec = new McpJsonCodec(jsonLimits);
		this.jsonCodec = jsonCodec;
		this.envelopeCodec = new McpJsonRpcEnvelopeCodec(jsonCodec);
		this.requestWireMapper = new McpRequestWireMapper(jsonLimits);
		this.protocolProfiles = requireNonNull(protocolProfiles);
		this.mirroredHeaderCodec = new McpMirroredHeaderCodec(
				McpMirroredHeaderCodec.DEFAULT_MAXIMUM_DECODED_BYTES);
		this.customMirroredHeaderValidator =
				new McpCustomMirroredHeaderValidator(mirroredHeaderCodec);
		this.endpointsByPath = endpointRuntimes(endpointBindings);
		this.subscriptionSourceGroups = subscriptionSourceGroups();
		preflightFrameworkOwnedResponses();
		this.lifecycleLock = new Object();
		this.requestControls = Collections.synchronizedMap(new IdentityHashMap<>());
		this.activeIdentifiedRequestExchangeCount = new AtomicInteger();
		this.streamDiagnosticsLock = new Object();
		this.subscriptionLock = new Object();
		this.activeSubscriptionsByEndpointPath = new LinkedHashMap<>();
		this.activeSubscriptionCountsByPartition = new LinkedHashMap<>();
		this.pendingSubscriptions = new LinkedHashSet<>();
		this.localizationInvalidationTokens = new LinkedHashMap<>();
		this.catalogInvalidationTokens = new LinkedHashMap<>();
		for (String endpointPath : this.endpointsByPath.keySet())
			this.localizationInvalidationTokens.put(endpointPath, new Object());
		for (String endpointPath : this.endpointsByPath.keySet())
			this.catalogInvalidationTokens.put(endpointPath, new Object());
		this.subscriptionsAccepting = false;
		this.subscriptionReconciliationGeneration = 0L;
		this.subscriptionSourceRegistrations = List.of();
		this.residualSubscriptionSourceRegistrations = List.of();
		this.residualSimulationSubscriptionSourceRegistrations = List.of();
		this.processorThreadSequence = new AtomicLong();
		this.subscriptionCloseThreadSequence = new AtomicLong();
		this.unknownMirroredHeaderOccurrences = new AtomicLong();
		this.unknownMirroredHeaderNameDiagnostics =
				new McpUnknownMirroredHeaderNameDiagnostics(applicationClock,
						requireNonNull(unknownMirroredHeaderNameDiagnosticConsumer));
		this.lifecycleState = LifecycleState.STOPPED;
		this.lifecycleQuiesceRequested = false;
		this.lifecycleForceRequested = false;
		this.lifecycleQuiesced = false;
		this.lifecycleForced = false;
		this.lifecycleGracefulDeadlinePresent = false;
		this.lifecycleForcedDeadlinePresent = false;
		this.lifecycleStartupInProgress = false;
		this.lifecycleStartupClaimed = false;
		this.lifecycleStartupGeneration = null;
	}

	void configureLegacySessions(Optional<com.soklet.McpSessionConfig> sessionConfig,
			Map<String, Set<String>> revisionsByPath, LegacySessionOwnerResolver ownerResolver) {
		requireNonNull(sessionConfig);
		requireNonNull(revisionsByPath);
		requireNonNull(ownerResolver);
		synchronized (lifecycleLock) {
			if (legacySessionsConfigured || lifecycleState != LifecycleState.STOPPED)
				throw new IllegalStateException("Session configuration must precede listener startup.");
			if (sessionConfig.isPresent() == revisionsByPath.isEmpty())
				throw new IllegalArgumentException("Session configuration requires explicitly enabled 2025 endpoints.");
			Map<String, Set<String>> selected = new LinkedHashMap<>();
			for (Map.Entry<String, Set<String>> entry : revisionsByPath.entrySet()) {
				EndpointRuntime endpoint = endpointsByPath.get(entry.getKey());
				if (endpoint == null || entry.getValue().isEmpty()
						|| !endpoint.binding().supportedRevisions().containsAll(entry.getValue())
						|| !entry.getValue().stream().allMatch(McpLegacyHttpWire::isLegacyRevision))
					throw new IllegalArgumentException("Sessions require explicitly served 2025 revisions.");
				selected.put(entry.getKey(), Set.copyOf(entry.getValue()));
			}
			legacySessionRevisions = Map.copyOf(selected);
			if (sessionConfig.isPresent()) {
				com.soklet.McpSessionConfig config = sessionConfig.orElseThrow();
				long requestEvidence = Math.addExact((long) transportConfiguration.maximumHeaderBytes(), 16_384L);
				long sessionEvidence = Math.max(1_048_576L, Math.multiplyExact(2L, requestEvidence));
				long ownerEvidence = Math.max(2_097_152L, Math.multiplyExact(2L, sessionEvidence));
				long globalEvidence = Math.max(16_777_216L, Math.multiplyExact(8L, ownerEvidence));
				legacySessionStore = new McpLegacySessionStore(new McpLegacySessionStore.Config(
						config.getMaximumSessions(), config.getMaximumSessionsPerOwner(),
						config.getMaximumSessionIdleDuration().toNanos(), config.getMaximumSessionDuration().toNanos(),
						config.getMaximumClientMetadataSizeInBytes(), config.isAnonymousSessionsAllowed(),
						sessionEvidence, ownerEvidence, globalEvidence), jsonLimits, applicationClock);
				legacySessionOwnerResolver = ownerResolver;
				legacyAnonymousSessionsAllowed = config.isAnonymousSessionsAllowed();
			}
			legacySessionsConfigured = true;
		}
	}

	private boolean sessionsEnabled(String path, String revision) {
		return legacySessionRevisions.getOrDefault(path, Set.of()).contains(revision);
	}

	/** The HTTP-only controller cannot enable a session or a notification family implicitly. */
	void configureLegacySessionTransport(Optional<McpLegacySessionTransportAdmission> admission,
			Map<String, Map<String, Set<McpResourceNotificationType>>> families) {
		requireNonNull(admission); requireNonNull(families);
		synchronized (lifecycleLock) {
			if (!legacySessionsConfigured || lifecycleState != LifecycleState.STOPPED)
				throw new IllegalStateException("HTTP session admission must be configured before startup.");
			Map<String, Map<String, Set<McpResourceNotificationType>>> copy = new LinkedHashMap<>();
			for (var entry : families.entrySet()) {
				Map<String, Set<McpResourceNotificationType>> revisions = new LinkedHashMap<>();
				for (var revision : entry.getValue().entrySet()) {
					if (!sessionsEnabled(entry.getKey(), revision.getKey()) || revision.getValue().isEmpty())
						throw new IllegalArgumentException("GET requires an explicit session revision and offered families.");
					revisions.put(revision.getKey(), Set.copyOf(revision.getValue()));
				}
				copy.put(entry.getKey(), Map.copyOf(revisions));
			}
			if (!copy.isEmpty() && admission.isEmpty())
				throw new IllegalArgumentException("Legacy GET requires an HTTP admission controller.");
			legacyTransportAdmission = admission.orElse(null);
			legacyTransportFamilies = Map.copyOf(copy);
			if (legacySessionStore != null) legacySessionStore.configureGetQuota(new McpLegacySessionStore.GetQuota() {
				@Override public boolean reserve(McpEffectivePartition partition) {
					synchronized (subscriptionLock) {
						int current = activeSubscriptionCountsByPartition.getOrDefault(partition, 0);
						if (current >= subscriptionRuntimeConfiguration.maximumSubscriptionsPerPartition()) return false;
						activeSubscriptionCountsByPartition.put(partition, current + 1);
						return true;
					}
				}
				@Override public void release(McpEffectivePartition partition) {
					synchronized (subscriptionLock) {
						int current = activeSubscriptionCountsByPartition.getOrDefault(partition, 0);
						if (current <= 1) activeSubscriptionCountsByPartition.remove(partition);
						else activeSubscriptionCountsByPartition.put(partition, current - 1);
					}
				}
			});
		}
	}

	private Set<HttpMethod> legacyHttpMethods(EndpointRuntime endpoint, @Nullable String revision) {
		Set<HttpMethod> methods = EnumSet.of(HttpMethod.POST, HttpMethod.OPTIONS);
		if (legacyTransportAdmission == null) return Set.copyOf(methods);
		Set<String> revisions = revision == null ? legacySessionRevisions.getOrDefault(endpoint.path(), Set.of())
				: sessionsEnabled(endpoint.path(), revision) ? Set.of(revision) : Set.of();
		if (!revisions.isEmpty()) methods.add(HttpMethod.DELETE);
		if (revisions.stream().anyMatch(value -> !legacyTransportFamilies
				.getOrDefault(endpoint.path(), Map.of()).getOrDefault(value, Set.of()).isEmpty()))
			methods.add(HttpMethod.GET);
		return Set.copyOf(methods);
	}

	@NonNull
	private Map<@NonNull String, @NonNull EndpointRuntime> endpointRuntimes(
			@NonNull List<@NonNull McpHttpEndpointBinding> endpointBindings) {
		List<McpHttpEndpointBinding> bindings = List.copyOf(
				requireNonNull(endpointBindings));
		if (bindings.isEmpty())
			throw new IllegalArgumentException(
					"At least one MCP HTTP endpoint binding must be configured.");

		Map<String, EndpointRuntime> endpointsByPath = new LinkedHashMap<>();
		for (McpHttpEndpointBinding binding : bindings) {
			McpHttpEndpointPolicy endpointPolicy = binding.endpointPolicy();
			validateConfiguredAllowedHosts(endpointPolicy);
			validateHostAuthorizationConfiguration(endpointPolicy);
			List<String> advertisedRevisions = this.protocolProfiles.revisions()
					.stream().filter(binding.supportedRevisions()::contains).toList();
			if (advertisedRevisions.size() != binding.supportedRevisions().size())
				throw new IllegalArgumentException(
						"An MCP endpoint declares an unimplemented protocol revision.");
			Map<String, McpServerCapabilityRegistry> registries = new LinkedHashMap<>();
			Map<String, ProfileFrameworkResponses> responses = new LinkedHashMap<>();
			Map<String, McpApplicationRequestRouter> resourceRouters = new LinkedHashMap<>();
			Map<String, Map<McpLegacyCatalogPager.Kind, McpLegacyCatalogPager>> pagers = new LinkedHashMap<>();
			for (String revision : advertisedRevisions) {
				McpNormalizedEndpoint revisionEndpoint = binding.revisionEndpoint(revision)
						.orElseThrow();
				McpProtocolProfile profile = this.protocolProfiles.resolve(revision)
						.orElseThrow();
				McpServerCapabilityRegistry registry =
						McpServerCapabilityRegistry.fromEndpoint(revisionEndpoint,
								McpLegacyHttpWire.isLegacyRevision(revision) ? Set.of() : endpointPolicy.catalogLocalizer()
										.map(McpRuntimeCatalogLocalizer
												::localizedResponseKinds)
										.orElseGet(Set::of), advertisedRevisions);
				// Caller-aware catalogs are rendered later, but every declared
				// legacy descriptor must still satisfy that revision's schema.
				if (profile instanceof Mcp2025ProtocolProfile
						&& (revisionEndpoint.catalogAccessAdapter().isPresent()
								|| registry.hasAppTools())) {
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.TOOLS_LIST,
							registry.toolsListResult());
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.PROMPTS_LIST,
							registry.promptsListResult());
				}
				resourceRouters.put(revision, revisionEndpoint == binding.endpoint()
						? binding.applicationRouter().completionView(revisionEndpoint, revision)
						: binding.applicationRouter().resourceView(revisionEndpoint, revision));
				registries.put(revision, registry);
				if (McpLegacyHttpWire.isLegacyRevision(revision)) {
					Map<McpLegacyCatalogPager.Kind, McpLegacyCatalogPager> catalogs =
							new EnumMap<>(McpLegacyCatalogPager.Kind.class);
					catalogs.put(McpLegacyCatalogPager.Kind.TOOLS, new McpLegacyCatalogPager(
							endpointPolicy.path(), profile, McpLegacyCatalogPager.Kind.TOOLS,
							registry.toolsListResult(), jsonCodec, owner -> endpointPolicy.catalogLocalizer()
									.map(value -> value.ownerSlotCount(McpRuntimeCatalogLocalizer.ResponseKind.TOOLS_LIST, owner)).orElse(0)));
					catalogs.put(McpLegacyCatalogPager.Kind.PROMPTS, new McpLegacyCatalogPager(
							endpointPolicy.path(), profile, McpLegacyCatalogPager.Kind.PROMPTS,
							registry.promptsListResult(), jsonCodec, owner -> endpointPolicy.catalogLocalizer()
									.map(value -> value.ownerSlotCount(McpRuntimeCatalogLocalizer.ResponseKind.PROMPTS_LIST, owner)).orElse(0)));
					if (!revisionEndpoint.customResourceListHandler())
						catalogs.put(McpLegacyCatalogPager.Kind.RESOURCES, new McpLegacyCatalogPager(
								endpointPolicy.path(), profile, McpLegacyCatalogPager.Kind.RESOURCES,
								registry.resourcesListResult(), jsonCodec, owner -> endpointPolicy.catalogLocalizer()
										.map(value -> value.ownerSlotCount(McpRuntimeCatalogLocalizer.ResponseKind.RESOURCES_LIST, owner)).orElse(0)));
					catalogs.put(McpLegacyCatalogPager.Kind.TEMPLATES, new McpLegacyCatalogPager(
							endpointPolicy.path(), profile, McpLegacyCatalogPager.Kind.TEMPLATES,
							registry.resourceTemplatesListResult(), jsonCodec, owner -> endpointPolicy.catalogLocalizer()
									.map(value -> value.ownerSlotCount(McpRuntimeCatalogLocalizer.ResponseKind.RESOURCE_TEMPLATES_LIST, owner)).orElse(0)));
					pagers.put(revision, Map.copyOf(catalogs));
				}
				responses.put(revision, profileFrameworkResponses(registry,
							revisionEndpoint.catalogAccessAdapter().isPresent(), profile));
			}
			EndpointRuntime endpointRuntime = new EndpointRuntime(binding,
					registries, responses, resourceRouters, pagers);
			if (endpointsByPath.putIfAbsent(endpointRuntime.path(), endpointRuntime)
					!= null)
				throw new IllegalArgumentException("Duplicate MCP HTTP endpoint path '"
						+ endpointRuntime.path() + "'.");
		}
		return Collections.unmodifiableMap(endpointsByPath);
	}

	@NonNull
	private ProfileFrameworkResponses
	profileFrameworkResponses(
			@NonNull McpServerCapabilityRegistry capabilityRegistry,
			boolean callerAwareCatalog,
			@NonNull McpProtocolProfile profile) {
			return new ProfileFrameworkResponses(
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.DISCOVERY,
							capabilityRegistry.discoverResult().toWireResult()),
					callerAwareCatalog || capabilityRegistry.hasAppTools()
							? Optional.empty() : Optional.of(
							profile.renderFrameworkResult(
									McpProfileFrameworkResultKind.TOOLS_LIST,
									capabilityRegistry.toolsListResult())),
					callerAwareCatalog ? Optional.empty() : Optional.of(
							profile.renderFrameworkResult(
									McpProfileFrameworkResultKind.PROMPTS_LIST,
									capabilityRegistry.promptsListResult())),
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.RESOURCES_LIST,
							capabilityRegistry.resourcesListResult()),
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.RESOURCE_TEMPLATES_LIST,
							capabilityRegistry.resourceTemplatesListResult()));
	}

	@NonNull
	private List<@NonNull SubscriptionSourceGroup> subscriptionSourceGroups() {
		IdentityHashMap<Object, Map<McpSubscriptionEventSource.SourceType,
				MutableSubscriptionSourceGroup>> groupsByIdentity =
				new IdentityHashMap<>();
		List<MutableSubscriptionSourceGroup> groupsInEndpointOrder =
				new ArrayList<>();
		for (EndpointRuntime endpointRuntime : this.endpointsByPath.values()) {
			for (McpSubscriptionEventSource source
					: endpointRuntime.binding().subscriptionEventSources()) {
				Map<McpSubscriptionEventSource.SourceType,
						MutableSubscriptionSourceGroup> groupsByType =
						groupsByIdentity.computeIfAbsent(source.identity(),
								ignored -> new EnumMap<>(
										McpSubscriptionEventSource.SourceType.class));
				MutableSubscriptionSourceGroup group =
						groupsByType.get(source.sourceType());
				if (group == null) {
					group = new MutableSubscriptionSourceGroup(source);
					groupsByType.put(source.sourceType(), group);
					groupsInEndpointOrder.add(group);
				}
				group.endpointPaths().add(endpointRuntime.path());
				if (source.endpointSubscriber().isPresent())
					group.endpointSources().add(new EndpointSubscriptionSource(
							endpointRuntime.path(),
							source.endpointSubscriber().orElseThrow()));
			}
		}
		List<SubscriptionSourceGroup> groups = new ArrayList<>(
				groupsInEndpointOrder.size());
		for (MutableSubscriptionSourceGroup group : groupsInEndpointOrder)
			groups.add(new SubscriptionSourceGroup(group.source(),
					Set.copyOf(group.endpointPaths()),
					List.copyOf(group.endpointSources())));
		return List.copyOf(groups);
	}

	private void preflightFrameworkOwnedResponses() {
		for (EndpointRuntime endpointRuntime : this.endpointsByPath.values()) {
			for (McpProtocolProfile profile : this.protocolProfiles.profiles()) {
				if (endpointRuntime.binding().revisionEndpoint(profile.revision())
						.isEmpty())
					continue;
				McpNormalizedEndpoint endpoint = endpointRuntime.binding()
						.revisionEndpoint(profile.revision()).orElseThrow();
				McpServerCapabilityRegistry capabilityRegistry =
						endpointRuntime.capabilityRegistry(profile.revision());
				ProfileFrameworkResponses responses =
						endpointRuntime.frameworkResponses(profile);
				preflightFrameworkOwnedResponse(endpointRuntime.path(),
						profile.revision(), "server/discover", responses.discovery());
				// Legacy catalog descriptors are preflighted individually by their
				// pager; the complete aggregate is deliberately not a wire response.
				if (McpLegacyHttpWire.isLegacyRevision(profile.revision()))
					continue;
				if (endpoint.catalogAccessAdapter().isEmpty()
						&& !capabilityRegistry.hasAppTools()
						&& !capabilityRegistry.tools().isEmpty())
					preflightFrameworkOwnedResponse(endpointRuntime.path(),
							profile.revision(), "tools/list",
							responses.toolsList().orElseThrow());
				if (endpoint.catalogAccessAdapter().isEmpty()
						&& !capabilityRegistry.prompts().isEmpty())
					preflightFrameworkOwnedResponse(endpointRuntime.path(),
							profile.revision(), "prompts/list",
							responses.promptsList().orElseThrow());
				if (capabilityRegistry.capabilities().resources().isPresent()) {
					if (!endpoint.customResourceListHandler())
						preflightFrameworkOwnedResponse(endpointRuntime.path(),
								profile.revision(), "resources/list",
								responses.resourcesList());
					preflightFrameworkOwnedResponse(endpointRuntime.path(),
							profile.revision(), "resources/templates/list",
							responses.resourceTemplatesList());
				}
				if (endpoint.subscriptionConfig().isPresent()
						&& !endpointRuntime.binding().subscriptionEventSources().isEmpty())
					preflightFrameworkOwnedResponse(endpointRuntime.path(),
							profile.revision(), "subscriptions/listen terminal",
							subscriptionTerminalResponse(profile,
									new McpJsonRpcId.IntegerId(BigInteger.ZERO),
									endpoint).result());
			}
		}
	}

	private void preflightFrameworkOwnedResponse(@NonNull String endpointPath,
			@NonNull String profileRevision,
			@NonNull String method,
			@NonNull McpWireResult result) {
		try {
			McpJsonRpcMessage.ResultResponse response =
					new McpJsonRpcMessage.ResultResponse(
							new McpJsonRpcId.IntegerId(BigInteger.ZERO),
							requireNonNull(result), McpJsonObject.empty());
			if (McpLegacyHttpWire.isLegacyRevision(profileRevision))
				McpLegacyResponseWire.encode(jsonCodec, response);
			else
				envelopeCodec.encode(response);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("The framework-owned MCP response for '"
					+ requireNonNull(method) + "' at endpoint '"
					+ requireNonNull(endpointPath) + "' under profile '"
					+ requireNonNull(profileRevision)
					+ "' cannot fit within the configured JSON "
					+ "output bounds (maximum UTF-8 bytes: "
					+ jsonLimits.maximumOutputBytes() + ").", exception);
		}
	}

	@NonNull
	SimulationSession openSimulationSession() {
		return openSimulationSession(ignored -> {}, false);
	}

	@NonNull
	SimulationSession openSimulationSession(
			@NonNull Consumer<@NonNull SimulationSession> sessionOwner) {
		return openSimulationSession(requireNonNull(sessionOwner), true);
	}

	@NonNull
	private SimulationSession openSimulationSession(
			@NonNull Consumer<@NonNull SimulationSession> sessionOwner,
			boolean retainFailedGenerationForOwner) {
		SimulationGeneration generation;
		SimulationSession session;
		synchronized (lifecycleLock) {
			reapSimulationResidualsWhileLocked();
			reapLiveResidualsForSimulationWhileLocked();
			if (lifecycleState != LifecycleState.STOPPED
					|| simulationGeneration != null
					|| residualRequestProcessor != null
					|| residualApplicationExecution != null
					|| residualEventLoop != null
					|| !residualSubscriptionSourceRegistrations.isEmpty()
					|| residualSimulationRequestProcessor != null
					|| residualSimulationApplicationExecution != null
					|| !residualSimulationSubscriptionSourceRegistrations.isEmpty())
				throw new IllegalStateException(SIMULATION_REQUIRES_STOPPED_SERVER);

			ThreadPoolExecutor processor = newRequestProcessor();
			McpApplicationExecution application = new McpApplicationExecution(
					applicationConfiguration, applicationClock,
					applicationExecutorFactory, this::runProtocolDeadlineCycle,
					this.applicationExecutionObserver);
			List<SubscriptionSourceRegistrationControl> registrations =
					new ArrayList<>();
			generation = new SimulationGeneration(processor, application,
					InetSocketAddress.createUnresolved(
							transportConfiguration.host(),
							transportConfiguration.port()), registrations);
			session = new SimulationSession(generation);
			simulationGeneration = generation;
			boolean sessionClaimed = false;
			try {
				sessionOwner.accept(session);
				sessionClaimed = true;
				application.start();
				subscribeToSubscriptionEventSources(registrations);
				startAcceptingSubscriptions();
			} catch (RuntimeException | Error failure) {
				generation.beginClosing();
				Throwable cleanupFailure = null;
				cleanupFailure = runLifecycleStep(cleanupFailure,
						() -> stopAcceptingSubscriptions());
				cleanupFailure = runLifecycleStep(cleanupFailure, () -> application
						.stop(StreamTerminationReason.CLIENT_DISCONNECTED));
				cleanupFailure = runLifecycleStep(cleanupFailure,
						processor::shutdownNow);
				try {
					SubscriptionRegistrationCloseBatch closeBatch =
							beginClosingSubscriptionEventSourceRegistrations(
									registrations);
					generation.mergeCloseBatch(closeBatch);
				} catch (Throwable closeFailure) {
					cleanupFailure = retainLifecycleFailure(cleanupFailure,
							closeFailure);
				}
				if (!retainFailedGenerationForOwner || !sessionClaimed) {
					try {
						if (simulationGenerationBarrierComplete(generation))
							releaseSimulationGenerationEvidence(generation);
						else
							retainSimulationGenerationEvidence(generation);
					} catch (Throwable retentionFailure) {
						cleanupFailure = retainLifecycleFailure(cleanupFailure,
								retentionFailure);
					}
				}
				if (cleanupFailure != null && !sameInstance(cleanupFailure, failure))
					failure.addSuppressed(cleanupFailure);
				throw failure;
			}
		}
		return session;
	}

	private @Nullable Throwable retainLifecycleFailure(
			@Nullable Throwable first, @NonNull Throwable next) {
		Throwable requiredNext = requireNonNull(next);
		if (first == null)
			return requiredNext;
		if (!sameInstance(first, requiredNext))
			first.addSuppressed(requiredNext);
		return first;
	}

	private void reapLiveResidualsForSimulationWhileLocked() {
		if (!Thread.holdsLock(lifecycleLock))
			throw new IllegalStateException("The MCP lifecycle lock is required.");
		if (residualRequestProcessor != null
				&& residualRequestProcessor.isTerminated())
			residualRequestProcessor = null;
		if (residualApplicationExecution != null
				&& residualApplicationExecution.isTerminated())
			residualApplicationExecution = null;
		if (residualEventLoop != null && residualEventLoop.isTerminated())
			residualEventLoop = null;
		residualSubscriptionSourceRegistrations =
				unclosedSubscriptionSourceRegistrations(
						residualSubscriptionSourceRegistrations);
	}

	private void reapSimulationResidualsWhileLocked() {
		if (!Thread.holdsLock(lifecycleLock))
			throw new IllegalStateException("The MCP lifecycle lock is required.");
		if (residualSimulationRequestProcessor != null
				&& residualSimulationRequestProcessor.isTerminated())
			residualSimulationRequestProcessor = null;
		if (residualSimulationApplicationExecution != null
				&& residualSimulationApplicationExecution.isTerminated())
			residualSimulationApplicationExecution = null;
		residualSimulationSubscriptionSourceRegistrations =
				unclosedSubscriptionSourceRegistrations(
						residualSimulationSubscriptionSourceRegistrations);
	}

	private void closeSimulationGeneration(
			@NonNull SimulationGeneration generation) {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		boolean interrupted = Thread.interrupted();
		Throwable forceFailure = null;
		long deadline = saturatingAdd(System.nanoTime(),
				transportConfiguration.shutdownTimeout().toNanos());
		try {
			try {
				forceSimulationGenerationForCompatibility(requiredGeneration);
			} catch (RuntimeException | Error failure) {
				forceFailure = failure;
			}

			boolean terminated = false;
			while (!terminated && remainingUntil(deadline) > 0L) {
				try {
					terminated = awaitSimulationGenerationTermination(
							requiredGeneration, deadline);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
			if (!terminated)
				terminated = simulationGenerationBarrierComplete(
						requiredGeneration);

			if (terminated)
				releaseSimulationGenerationEvidence(requiredGeneration);
			else
				retainSimulationGenerationEvidence(requiredGeneration);

			if (forceFailure != null)
				rethrowLifecycleFailure(forceFailure);
			if (!terminated)
				throw new IllegalStateException(SIMULATION_SHUTDOWN_TIMED_OUT);
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/** Preserves the legacy simulator-close cancellation order and time budget. */
	private void forceSimulationGenerationForCompatibility(
			@NonNull SimulationGeneration generation) {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		synchronized (lifecycleLock) {
			if (requiredGeneration.evidenceReleased())
				return;
			if (simulationGeneration != requiredGeneration)
				throw new IllegalStateException(SIMULATION_REQUIRES_STOPPED_SERVER);
			if (!requiredGeneration.claimForce())
				return;
			requiredGeneration.claimQuiesce();
			requiredGeneration.beginClosing();
		}

		Throwable failure = null;
		failure = runLifecycleStep(failure, this::closeLegacySessions);
		for (McpSimulationRuntime simulation
				: requiredGeneration.activeSimulations())
			failure = runLifecycleStep(failure, simulation::close);
		stopAcceptingSubscriptions();
		SubscriptionRegistrationCloseBatch closeBatch =
				beginClosingSubscriptionEventSourceRegistrations(
						requiredGeneration.registrations());
		synchronized (lifecycleLock) {
			requiredGeneration.mergeCloseBatch(closeBatch);
		}
		failure = runLifecycleStep(failure, () -> requiredGeneration.application()
				.stop(StreamTerminationReason.CLIENT_DISCONNECTED));
		failure = runLifecycleStep(failure, () -> cancelAllRequests(
				StreamTerminationReason.CLIENT_DISCONNECTED, null));
		failure = runLifecycleStep(failure,
				requiredGeneration.processor()::shutdownNow);
		failure = runLifecycleStep(failure, () -> cancelAllRequests(
				StreamTerminationReason.CLIENT_DISCONNECTED, null));
		failure = runLifecycleStep(failure, this::closeLegacySessions);
		rethrowLifecycleFailure(failure);
	}

	/** Fences one simulation generation and begins cooperative, noninterrupting drain. */
	private void quiesceSimulationGeneration(
			@NonNull SimulationGeneration generation) {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		synchronized (lifecycleLock) {
			if (requiredGeneration.evidenceReleased())
				return;
			if (simulationGeneration != requiredGeneration)
				throw new IllegalStateException(SIMULATION_REQUIRES_STOPPED_SERVER);
			if (!requiredGeneration.claimQuiesce())
				return;
			requiredGeneration.beginClosing();
		}

		Set<RequestControl> subscriptions = stopAcceptingSubscriptions();
		completeSubscriptions(subscriptions);
		for (RequestControl control : requestControlsSnapshot())
			control.quiesceSimulationTransport();
		beginGracefulSimulationSubscriptionCloses(requiredGeneration);
		ThreadPoolExecutor processor = requiredGeneration.processor();
		McpApplicationExecution application = requiredGeneration.application();
		if (processor instanceof LifecycleRequestProcessor lifecycleProcessor)
			lifecycleProcessor.afterTermination(application::beginGracefulDrain);
		processor.shutdown();
		if (!(processor instanceof LifecycleRequestProcessor))
			application.beginGracefulDrain();
	}

	/** Idempotent simulation force phase; force-first subsumes quiesce. */
	private void forceSimulationGeneration(
			@NonNull SimulationGeneration generation) {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		synchronized (lifecycleLock) {
			if (requiredGeneration.evidenceReleased())
				return;
			if (simulationGeneration != requiredGeneration)
				throw new IllegalStateException(SIMULATION_REQUIRES_STOPPED_SERVER);
			if (!requiredGeneration.claimForce())
				return;
		}

		Throwable failure = null;
		failure = runLifecycleStep(failure, this::closeLegacySessions);
		failure = runLifecycleStep(failure,
				() -> quiesceSimulationGeneration(requiredGeneration));
		for (McpSimulationRuntime simulation
				: requiredGeneration.activeSimulations())
			failure = runLifecycleStep(failure, simulation::close);
		Set<RequestControl> subscriptions = stopAcceptingSubscriptions();
		failure = runLifecycleStep(failure,
				() -> completeSubscriptions(subscriptions));
		for (RequestControl control : requestControlsSnapshot())
			failure = runLifecycleStep(failure, control::quiesceTransport);
		SubscriptionRegistrationCloseBatch closeBatch =
				beginClosingSubscriptionEventSourceRegistrations(
						requiredGeneration.registrations());
		synchronized (lifecycleLock) {
			requiredGeneration.mergeCloseBatch(closeBatch);
			closeBatch = requiredGeneration.closeBatch();
		}
		if (closeBatch != null) {
			for (SubscriptionRegistrationCloseAttempt closeAttempt
					: closeBatch.closeAttempts())
				closeAttempt.cancel();
		}
		failure = runLifecycleStep(failure, () -> requiredGeneration.application()
				.stop(StreamTerminationReason.CLIENT_DISCONNECTED));
		failure = runLifecycleStep(failure, () -> cancelAllRequests(
				StreamTerminationReason.CLIENT_DISCONNECTED, null));
		failure = runLifecycleStep(failure,
				requiredGeneration.processor()::shutdownNow);
		failure = runLifecycleStep(failure, () -> cancelAllRequests(
				StreamTerminationReason.CLIENT_DISCONNECTED, null));
		failure = runLifecycleStep(failure, this::closeLegacySessions);
		rethrowLifecycleFailure(failure);
	}

	private void beginGracefulSimulationSubscriptionCloses(
			@NonNull SimulationGeneration generation) {
		synchronized (lifecycleLock) {
			if (generation.forced())
				return;
			SubscriptionRegistrationCloseBatch closeBatch =
					beginClosingSubscriptionEventSourceRegistrations(
							generation.registrations());
			generation.mergeCloseBatch(closeBatch);
		}
	}

	private boolean awaitSimulationGenerationTermination(
			@NonNull SimulationGeneration generation,
			long absoluteDeadlineNanos) throws InterruptedException {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		if (requiredGeneration.evidenceReleased())
			return true;
		boolean processorTerminated = requiredGeneration.processor().isTerminated();
		if (!processorTerminated) {
			long remaining = remainingUntil(absoluteDeadlineNanos);
			if (remaining > 0L)
				processorTerminated = requiredGeneration.processor().awaitTermination(
						remaining, TimeUnit.NANOSECONDS);
		}
		boolean applicationTerminated = requiredGeneration.application()
				.isTerminated();
		if (!applicationTerminated) {
			long remaining = remainingUntil(absoluteDeadlineNanos);
			if (remaining > 0L)
				applicationTerminated = requiredGeneration.application()
						.awaitTermination(Duration.ofNanos(remaining));
		}
		SubscriptionRegistrationCloseBatch closeBatch;
		synchronized (lifecycleLock) {
			closeBatch = requiredGeneration.closeBatch();
		}
		boolean registrationsClosed = awaitSimulationRegistrations(
				requiredGeneration, closeBatch, absoluteDeadlineNanos);
		return processorTerminated && applicationTerminated
				&& registrationsClosed
				&& simulationGenerationBarrierComplete(requiredGeneration);
	}

	private boolean awaitSimulationGenerationTermination(
			@NonNull SimulationGeneration generation, long absoluteDeadlineNanos,
			@NonNull LongSupplier nanoTime) throws InterruptedException {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		LongSupplier requiredNanoTime = requireNonNull(nanoTime);
		for (;;) {
			if (simulationGenerationBarrierComplete(requiredGeneration))
				return true;
			long remaining = remainingUntil(absoluteDeadlineNanos,
					requiredNanoTime.getAsLong());
			if (remaining == 0L)
				return false;
			long observationSlice = Math.min(remaining,
					TimeUnit.MILLISECONDS.toNanos(1L));
			synchronized (lifecycleLock) {
				if (simulationGenerationBarrierComplete(requiredGeneration))
					return true;
				TimeUnit.NANOSECONDS.timedWait(lifecycleLock, observationSlice);
			}
		}
	}

	private boolean awaitSimulationRegistrations(
			@NonNull SimulationGeneration generation,
			@Nullable SubscriptionRegistrationCloseBatch closeBatch,
			long absoluteDeadlineNanos) throws InterruptedException {
		boolean closeAttemptsCompleted = true;
		if (closeBatch == null)
			return unclosedSubscriptionSourceRegistrations(
					generation.registrations()).isEmpty();
		for (SubscriptionRegistrationCloseAttempt attempt
				: closeBatch.closeAttempts()) {
			if (!attempt.completed()) {
				long remaining = remainingUntil(absoluteDeadlineNanos);
				if (remaining > 0L)
					attempt.await(remaining);
			}
			closeAttemptsCompleted &= attempt.completed();
		}
		return closeAttemptsCompleted
				&& unclosedSubscriptionSourceRegistrations(
						generation.registrations()).isEmpty();
	}

	private boolean simulationGenerationBarrierComplete(
			@NonNull SimulationGeneration generation) {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		if (requiredGeneration.evidenceReleased())
			return true;
		SubscriptionRegistrationCloseBatch closeBatch;
		synchronized (lifecycleLock) {
			closeBatch = requiredGeneration.closeBatch();
		}
		boolean closeAttemptsComplete = closeBatch == null
				|| closeBatch.closeAttempts().stream()
						.allMatch(SubscriptionRegistrationCloseAttempt::completed);
		requiredGeneration.removeCompletedSimulations();
		return requiredGeneration.processor().isTerminated()
				&& requiredGeneration.application().isTerminated()
				&& closeAttemptsComplete
				&& unclosedSubscriptionSourceRegistrations(
						requiredGeneration.registrations()).isEmpty()
				&& requestControlsSnapshot().isEmpty()
				&& activeStreamsAndSubscriptionsEnded()
				&& requiredGeneration.activeSimulations().isEmpty();
	}

	@NonNull
	private McpLifecycleEvidence simulationLifecycleEvidence(
			@NonNull SimulationGeneration generation) {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		if (requiredGeneration.evidenceReleased())
			return new McpLifecycleEvidence(false, false, false, false,
					false, false);
		McpApplicationExecutionSnapshot applicationSnapshot =
				requiredGeneration.application().snapshot(
						activeIdentifiedRequestExchangeCount.get());
		boolean streams;
		synchronized (streamDiagnosticsLock) {
			streams = activeRequestStreams != 0 || activeSubscriptions != 0;
		}
		requiredGeneration.removeCompletedSimulations();
		SubscriptionRegistrationCloseBatch closeBatch;
		synchronized (lifecycleLock) {
			closeBatch = requiredGeneration.closeBatch();
		}
		boolean registrationCloseInProgress = closeBatch != null
				&& closeBatch.closeAttempts().stream()
						.anyMatch(attempt -> !attempt.completed());
		return new McpLifecycleEvidence(false, false,
				!requiredGeneration.processor().isTerminated()
						|| !requiredGeneration.application().isTerminated(),
				streams || !requestControlsSnapshot().isEmpty()
						|| !requiredGeneration.activeSimulations().isEmpty(),
				applicationSnapshot.activeHandlerSlots() > 0,
				registrationCloseInProgress
						|| !unclosedSubscriptionSourceRegistrations(
								requiredGeneration.registrations()).isEmpty());
	}

	private void releaseSimulationGenerationEvidence(
			@NonNull SimulationGeneration generation) {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		if (requiredGeneration.evidenceReleased())
			return;
		if (!simulationGenerationBarrierComplete(requiredGeneration))
			throw new IllegalStateException(
					"MCP simulation lifecycle evidence cannot be released before termination is proven.");
		closeLegacySessions();
		synchronized (lifecycleLock) {
			if (requiredGeneration.evidenceReleased())
				return;
			if (simulationGeneration == requiredGeneration)
				simulationGeneration = null;
			if (sameInstance(residualSimulationRequestProcessor,
					requiredGeneration.processor()))
				residualSimulationRequestProcessor = null;
			if (residualSimulationApplicationExecution
					== requiredGeneration.application())
				residualSimulationApplicationExecution = null;
			residualSimulationSubscriptionSourceRegistrations =
					unclosedSubscriptionSourceRegistrations(
							residualSimulationSubscriptionSourceRegistrations);
			requiredGeneration.markEvidenceReleased();
			lifecycleLock.notifyAll();
		}
	}

	private void retainSimulationGenerationEvidence(
			@NonNull SimulationGeneration generation) {
		SimulationGeneration requiredGeneration = requireNonNull(generation);
		synchronized (lifecycleLock) {
			if (simulationGeneration == requiredGeneration)
				simulationGeneration = null;
			residualSimulationRequestProcessor =
					requiredGeneration.processor().isTerminated()
							? null : requiredGeneration.processor();
			residualSimulationApplicationExecution =
					requiredGeneration.application().isTerminated()
							? null : requiredGeneration.application();
			residualSimulationSubscriptionSourceRegistrations =
					unclosedSubscriptionSourceRegistrations(
							requiredGeneration.registrations());
			lifecycleLock.notifyAll();
		}
	}

	@NonNull
	InetSocketAddress start() throws IOException {
		return start(requireNonNull(lifecycleAdapter.currentGeneration()));
	}

	@NonNull
	InetSocketAddress start(
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation
					expectedGeneration) throws IOException {
		requireNonNull(expectedGeneration);
		boolean preparationRequired;
		synchronized (lifecycleLock) {
			preparationRequired = lifecycleState == LifecycleState.STOPPED;
		}
		if (preparationRequired) {
			if (expectedGeneration.shutdownRequested())
				throw new IOException(
						"MCP shutdown began before listener startup.");
			prepareLifecycleStart(expectedGeneration);
		}
		McpServerRuntimeBridge.LifecycleAdapter.Generation lifecycleGeneration =
				claimLifecycleStart(expectedGeneration);
		this.applicationExecutionObserver.beginDeferral();
		enterStartupExecution();
		try {
			return startWhileMetricsDeferred(lifecycleGeneration);
		} finally {
			try {
				exitStartupExecution();
			} finally {
				this.applicationExecutionObserver.endDeferral();
			}
		}
	}

	/** Publishes the never-bound state for one exact generation before callbacks. */
	void prepareLifecycleStart(
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation
					expectedGeneration) {
		requireNonNull(expectedGeneration);
		synchronized (lifecycleLock) {
			if (!sameInstance(lifecycleAdapter.currentGeneration(), expectedGeneration))
				throw new IllegalStateException(
						"The MCP lifecycle generation is no longer current.");
			if (lifecycleState != LifecycleState.STOPPED)
				throw new IllegalStateException("The MCP HTTP server is not stopped.");
			lifecycleState = LifecycleState.STARTING;
			lifecycleStartupInProgress = true;
			lifecycleStartupClaimed = false;
			lifecycleStartupGeneration = expectedGeneration;
			boundAddress = null;
			lifecycleCloseBatch = null;
			lifecycleQuiesceRequested = false;
			lifecycleForceRequested = false;
			lifecycleQuiesced = false;
			lifecycleForced = false;
			lifecycleGracefulDeadlinePresent = false;
			lifecycleForcedDeadlinePresent = false;
			currentReadiness = null;
			subscriptionSourceRegistrations = List.of();
		}
	}

	private McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation
			claimLifecycleStart(
					McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation
							expectedGeneration) {
		requireNonNull(expectedGeneration);
		synchronized (lifecycleLock) {
			if (lifecycleState != LifecycleState.STARTING
					|| !lifecycleStartupInProgress
					|| lifecycleStartupClaimed
					|| !sameInstance(lifecycleStartupGeneration, expectedGeneration)
					|| !sameInstance(lifecycleAdapter.currentGeneration(), expectedGeneration))
				throw new IllegalStateException(
						"The MCP HTTP server has no matching prepared startup to claim.");
			lifecycleStartupClaimed = true;
			// The identity checks above establish this is the claimed generation.
			return expectedGeneration;
		}
	}

	boolean lifecycleWaitWouldSelfJoin() {
		AtomicInteger depth = lifecycleProofExecutionDepth.get();
		boolean selfJoin = depth.get() != 0;
		if (!selfJoin)
			lifecycleProofExecutionDepth.remove();
		return selfJoin;
	}

	private void enterStartupExecution() {
		enterLifecycleProofExecution();
	}

	private void exitStartupExecution() {
		exitLifecycleProofExecution();
	}

	private void enterLifecycleProofExecution() {
		lifecycleProofExecutionDepth.get().incrementAndGet();
	}

	private void exitLifecycleProofExecution() {
		AtomicInteger depth = lifecycleProofExecutionDepth.get();
		if (depth.decrementAndGet() == 0)
			lifecycleProofExecutionDepth.remove();
	}

	@NonNull
	private InetSocketAddress startWhileMetricsDeferred(
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation
					lifecycleGeneration) throws IOException {
		ThreadPoolExecutor candidateProcessor = null;
		McpApplicationExecution candidateApplicationExecution = null;
		AtomicReference<ListenerState> candidateReadiness =
				new AtomicReference<>(ListenerState.STARTING);
		AtomicReference<InetSocketAddress> candidateAddress = new AtomicReference<>();
		AtomicReference<Throwable> startupFailure = new AtomicReference<>();
		AtomicBoolean startupFailureSignaled = new AtomicBoolean();
		AtomicBoolean startupFailureDiagnosticRetained = new AtomicBoolean();
		Object startupFailureSignalLock = new Object();
		EventLoop candidateEventLoop = null;
		InetSocketAddress effectiveAddress = null;
		List<SubscriptionSourceRegistrationControl>
				candidateSubscriptionRegistrations = new ArrayList<>();

		try {
			synchronized (lifecycleLock) {
				reapSimulationResidualsWhileLocked();
				if (simulationGeneration != null
						|| residualSimulationRequestProcessor != null
						|| residualSimulationApplicationExecution != null
						|| !residualSimulationSubscriptionSourceRegistrations.isEmpty())
					throw new IllegalStateException(SIMULATION_REQUIRES_STOPPED_SERVER);
				if (residualRequestProcessor != null) {
					if (!residualRequestProcessor.isTerminated())
						throw new IllegalStateException(
								"Cannot start MCP server while residual handler executions remain");
					residualRequestProcessor = null;
				}
				if (residualApplicationExecution != null) {
					if (!residualApplicationExecution.isTerminated())
						throw new IllegalStateException(
								"Cannot start MCP server while residual handler executions remain");
					residualApplicationExecution = null;
				}
				if (residualEventLoop != null) {
					if (!residualEventLoop.isTerminated())
						throw new IllegalStateException(
								"Cannot start MCP server while residual transport threads remain");
					residualEventLoop = null;
				}
				residualSubscriptionSourceRegistrations =
						unclosedSubscriptionSourceRegistrations(
								residualSubscriptionSourceRegistrations);
				if (!residualSubscriptionSourceRegistrations.isEmpty())
					throw new IllegalStateException(
							RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_RESTART_DIAGNOSTIC);
			}
			ensureStartupMayContinue(lifecycleGeneration, startupFailure);

			candidateProcessor = newRequestProcessor();
			synchronized (lifecycleLock) {
				requestProcessor = candidateProcessor;
			}
			catchUpStartupProcessor(candidateProcessor);
			ensureStartupMayContinue(lifecycleGeneration, startupFailure);

			candidateApplicationExecution = new McpApplicationExecution(
					applicationConfiguration, applicationClock,
					applicationExecutorFactory, this::runProtocolDeadlineCycle,
					this.applicationExecutionObserver);
			synchronized (lifecycleLock) {
				applicationExecution = candidateApplicationExecution;
			}
			catchUpStartupApplication(candidateApplicationExecution);
			ensureStartupMayContinue(lifecycleGeneration, startupFailure);

			ThreadPoolExecutor readyProcessor = candidateProcessor;
			McpApplicationExecution readyApplicationExecution =
					candidateApplicationExecution;
			Handler handler = new Handler() {
				@Override
				public void handle(@NonNull MicrohttpRequest request,
						@NonNull Consumer<@NonNull MicrohttpResponse> callback) {
					if (candidateReadiness.get() != ListenerState.READY) {
						applicationExecutionObserver.recordRequestRejected();
						applicationExecutionObserver.drain();
						List<Header> headers = localizationVaryRequired(request)
								? withAcceptLanguageVary(List.of()) : List.of();
						callback.accept(emptyResponse(
								503, "Service Unavailable", headers));
						return;
					}
					Runnable lifecycleAdmission = lifecycleGeneration.tryAdmit()
							.orElse(null);
					if (lifecycleAdmission == null) {
						applicationExecutionObserver.recordRequestRejected();
						applicationExecutionObserver.drain();
						List<Header> headers = localizationVaryRequired(request)
								? withAcceptLanguageVary(List.of()) : List.of();
						callback.accept(emptyResponse(
								503, "Service Unavailable", headers));
						return;
					}

					try {
						Runnable trackedLifecycleAdmission = lifecycleGeneration
								.tracksAdmissionLifetime() ? lifecycleAdmission : null;
						submitRequest(readyProcessor, readyApplicationExecution,
								candidateAddress.get(), request,
								trackedLifecycleAdmission, callback);
					} catch (RuntimeException | Error failure) {
						lifecycleAdmission.run();
						throw failure;
					}
				}

				@Override
				public boolean monitorClientDisconnectsBeforeResponse(
						@NonNull MicrohttpRequest request) {
					return true;
				}

				@Override
				public boolean monitorClientDisconnectsDuringStreamingResponse(
						@NonNull MicrohttpRequest request) {
					return true;
				}

				@Override
				public void cancel(@NonNull MicrohttpRequest request,
						@NonNull StreamTerminationReason reason,
						@Nullable Throwable cause) {
					cancelRequest(request, reason, cause);
				}
			};

			Options options = microhttpOptions();
			candidateEventLoop = new EventLoop(options, NoopLogger.instance(), handler,
					connectionListener(candidateReadiness, lifecycleGeneration,
							startupFailure, startupFailureSignaled,
							startupFailureDiagnosticRetained,
							startupFailureSignalLock),
					this.transportFailureObserver);
			if (lifecycleGeneration.coordinatorOwnsUnexpectedTermination())
				candidateEventLoop.useCoordinatorOwnedUnexpectedTermination();
			effectiveAddress = candidateEventLoop.getLocalAddress();
			candidateAddress.set(effectiveAddress);
			synchronized (lifecycleLock) {
				eventLoop = candidateEventLoop;
				currentReadiness = candidateReadiness;
				// Binding is the address-retention linearization point.  Every later
				// snapshot for this generation retains this value.
				boundAddress = effectiveAddress;
			}
			catchUpStartupEventLoop(candidateEventLoop);
			ensureStartupMayContinue(lifecycleGeneration, startupFailure);

			candidateEventLoop.start();
			ensureStartupMayContinue(lifecycleGeneration, startupFailure);
			candidateApplicationExecution.start();
			ensureStartupMayContinue(lifecycleGeneration, startupFailure);

			subscribeToSubscriptionEventSources(
					candidateSubscriptionRegistrations,
					this::publishStartupSubscriptionRegistration);
			ensureStartupMayContinue(lifecycleGeneration, startupFailure);
			emitOmittedCorsAuthorizerDiagnostic();
			ensureStartupMayContinue(lifecycleGeneration, startupFailure);

			boolean ready;
			synchronized (lifecycleLock) {
				ready = lifecycleState == LifecycleState.STARTING
						&& !lifecycleQuiesced && !lifecycleForced
						&& !lifecycleGeneration.shutdownRequested()
						&& startupFailure.get() == null;
				if (ready) {
					startAcceptingSubscriptions();
					ready = candidateReadiness.compareAndSet(
							ListenerState.STARTING, ListenerState.READY);
				}
				if (ready) {
					lifecycleState = LifecycleState.STARTED;
					lifecycleStartupInProgress = false;
					lifecycleStartupClaimed = false;
					lifecycleStartupGeneration = null;
					lifecycleLock.notifyAll();
				}
			}
			if (!ready) {
				Throwable exactFailure = startupFailure.get();
				if (exactFailure != null)
					rethrowStartupFailure(exactFailure);
				throw new IOException(
						"MCP shutdown began before listener readiness.");
			}
			return requireNonNull(effectiveAddress);
		} catch (IOException | RuntimeException | Error throwable) {
			Throwable primary;
			synchronized (lifecycleLock) {
				// Startup and EventLoop termination elect one exact primary while
				// serialized with the final STARTING -> READY transition.  Publishing
				// TERMINATED before the cause would allow startup to synthesize a
				// different failure and win the common coordinator signal.
				startupFailure.compareAndSet(null, throwable);
				primary = requireNonNull(startupFailure.get());
				candidateReadiness.set(ListenerState.TERMINATED);
			}
			retainCompetingStartupFailure(lifecycleGeneration, primary, throwable,
					startupFailureSignaled,
					startupFailureDiagnosticRetained,
					startupFailureSignalLock);

			boolean candidatesPublished = candidateProcessor != null
					|| candidateApplicationExecution != null
					|| candidateEventLoop != null
					|| !candidateSubscriptionRegistrations.isEmpty();
			synchronized (lifecycleLock) {
				if (candidateProcessor != null)
					requestProcessor = candidateProcessor;
				if (candidateApplicationExecution != null)
					applicationExecution = candidateApplicationExecution;
				if (candidateEventLoop != null) {
					eventLoop = candidateEventLoop;
					if (effectiveAddress != null)
						boundAddress = effectiveAddress;
				}
				publishStartupSubscriptionRegistrationsWhileLocked(
						candidateSubscriptionRegistrations);
				currentReadiness = null;
				if (candidatesPublished) {
					if (lifecycleState == LifecycleState.STARTING)
						lifecycleState = LifecycleState.FAILED;
				} else {
					lifecycleState = LifecycleState.STOPPED;
				}
			}

			signalStartupFailure(
					lifecycleGeneration, primary, startupFailureSignaled,
					startupFailureSignalLock);
			// A signal-path failure is contained locally and cannot replace or
			// decorate the elected startup cause.

			// A coordinator may have frozen an incomplete result while an
			// application subscription callback ignored startup interruption.  Once
			// that callback returns, unwind the failed startup on this same worker;
			// never enter quiesce/force concurrently with the live start call.
			boolean coordinatorOwnsUnexpectedTermination = lifecycleGeneration
					.coordinatorOwnsUnexpectedTermination();
			if (coordinatorOwnsUnexpectedTermination) {
				Throwable unwindFailure = unwindCancelledStartup(primary,
						candidateProcessor, candidateApplicationExecution,
						candidateEventLoop, candidateSubscriptionRegistrations);
				retainCompetingStartupFailure(lifecycleGeneration, primary,
						unwindFailure, startupFailureSignaled,
						startupFailureDiagnosticRetained,
						startupFailureSignalLock);
			}

			if (!coordinatorOwnsUnexpectedTermination) {
				Throwable catchUpFailure = null;
				if (candidateProcessor != null) {
					ThreadPoolExecutor processorToCatchUp = candidateProcessor;
					catchUpFailure = runLifecycleStep(catchUpFailure,
							() -> catchUpStartupProcessor(processorToCatchUp));
				}
				if (candidateApplicationExecution != null) {
					McpApplicationExecution applicationToCatchUp =
							candidateApplicationExecution;
					catchUpFailure = runLifecycleStep(catchUpFailure,
							() -> catchUpStartupApplication(
									applicationToCatchUp));
				}
				if (candidateEventLoop != null) {
					EventLoop eventLoopToCatchUp = candidateEventLoop;
					catchUpFailure = runLifecycleStep(catchUpFailure,
							() -> catchUpStartupEventLoop(eventLoopToCatchUp));
				}
				for (SubscriptionSourceRegistrationControl registration
						: candidateSubscriptionRegistrations)
					catchUpFailure = runLifecycleStep(catchUpFailure,
							() -> catchUpStartupSubscriptionRegistration(
									registration));
				if (catchUpFailure != null && !sameInstance(catchUpFailure, primary))
					primary.addSuppressed(catchUpFailure);

				synchronized (lifecycleLock) {
					lifecycleStartupInProgress = false;
					lifecycleStartupClaimed = false;
					lifecycleStartupGeneration = null;
					lifecycleLock.notifyAll();
				}
			}

			if (!coordinatorOwnsUnexpectedTermination
					&& candidatesPublished) {
				try {
					stopAndReportResidualApplicationExecutionsWhileMetricsDeferred();
				} catch (Throwable cleanupFailure) {
					if (!sameInstance(cleanupFailure, primary))
						primary.addSuppressed(cleanupFailure);
				}
			}
			rethrowStartupFailure(primary);
			throw new AssertionError("Unreachable startup failure");
		}
	}

	@Nullable
	private Throwable unwindCancelledStartup(
			@NonNull Throwable startupPrimary,
			@Nullable ThreadPoolExecutor processor,
			@Nullable McpApplicationExecution application,
			@Nullable EventLoop loop,
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					registrations) {
		synchronized (lifecycleLock) {
			// The startup worker owns physical cleanup until it reaches the final
			// handoff below.  The outer coordinator records phase intent without
			// entering this runtime while its tracked start call remains live.
			lifecycleQuiesceRequested = true;
			if (!lifecycleQuiesced) {
				lifecycleQuiesced = true;
				publishLifecycleQuiesceIntentWhileLocked();
			}
		}

		Throwable failure = null;
		if (loop != null) {
			failure = runStartupUnwindStep(
					failure, startupPrimary, loop::stopAccepting);
			failure = runStartupUnwindStep(
					failure, startupPrimary, loop::beginDrain);
		}
		Set<RequestControl> subscriptions = stopAcceptingSubscriptions();
		failure = runStartupUnwindStep(failure, startupPrimary,
				() -> completeSubscriptions(subscriptions));
		for (RequestControl control : requestControlsSnapshot())
			failure = runStartupUnwindStep(
					failure, startupPrimary, control::quiesceTransport);
		AtomicReference<SubscriptionRegistrationCloseBatch> closeBatch =
				new AtomicReference<>();
		failure = runStartupUnwindStep(failure, startupPrimary,
				() -> closeBatch.set(
						beginClosingSubscriptionEventSourceRegistrations(
								registrations)));
		SubscriptionRegistrationCloseBatch exactCloseBatch = closeBatch.get();
		if (exactCloseBatch != null)
			failure = runStartupUnwindStep(failure, startupPrimary,
					() -> publishLifecycleCloseBatch(exactCloseBatch));
		if (processor != null)
			failure = runStartupUnwindStep(
					failure, startupPrimary, processor::shutdown);
		if (application != null) {
			// A failed startup never reached readiness, so the request processor
			// could not admit application work.  Stop synchronously on this tracked
			// startup worker: exposing graceful-drain state here would let the
			// deadline thread claim cleanup and lose its failure from startup evidence.
			failure = runStartupUnwindStep(
					failure, startupPrimary, application::stop);
		}

		boolean forceApplied = false;
		for (;;) {
			boolean forceRequired;
			synchronized (lifecycleLock) {
				forceRequired = lifecycleForceRequested && !forceApplied;
				if (!forceRequired) {
					// This lock handoff linearizes the end of startup-owned
					// resource access.  A later phase call owns physical cleanup;
					// an earlier force request is observed by the loop instead.
					lifecycleStartupInProgress = false;
					lifecycleStartupClaimed = false;
					lifecycleStartupGeneration = null;
					lifecycleLock.notifyAll();
					return failure;
				}
				lifecycleForced = true;
			}

			if (loop != null) {
				failure = runStartupUnwindStep(
						failure, startupPrimary, loop::stopAccepting);
				failure = runStartupUnwindStep(
						failure, startupPrimary, loop::beginDrain);
				failure = runStartupUnwindStep(
						failure, startupPrimary, loop::stopConnections);
			}
			Set<RequestControl> forcedSubscriptions = stopAcceptingSubscriptions();
			failure = runStartupUnwindStep(failure, startupPrimary,
					() -> completeSubscriptions(forcedSubscriptions));
			for (RequestControl control : requestControlsSnapshot())
				failure = runStartupUnwindStep(
						failure, startupPrimary, control::quiesceTransport);
			AtomicReference<SubscriptionRegistrationCloseBatch> forcedCloseBatch =
					new AtomicReference<>();
			failure = runStartupUnwindStep(failure, startupPrimary,
					() -> forcedCloseBatch.set(
							beginClosingSubscriptionEventSourceRegistrations(
									registrations)));
			SubscriptionRegistrationCloseBatch exactForcedCloseBatch =
					forcedCloseBatch.get();
			if (exactForcedCloseBatch != null) {
				for (SubscriptionRegistrationCloseAttempt closeAttempt
						: exactForcedCloseBatch.closeAttempts())
					failure = runStartupUnwindStep(failure, startupPrimary,
							closeAttempt::cancel);
				failure = runStartupUnwindStep(failure, startupPrimary,
						() -> publishLifecycleCloseBatch(exactForcedCloseBatch));
			}
			if (application != null)
				failure = runStartupUnwindStep(failure, startupPrimary,
						() -> application.stop(
								StreamTerminationReason.SERVER_STOPPING));
			failure = runStartupUnwindStep(failure, startupPrimary,
					() -> cancelAllRequests(
							StreamTerminationReason.SERVER_STOPPING, null));
			if (processor != null)
				failure = runStartupUnwindStep(
						failure, startupPrimary, processor::shutdownNow);
			failure = runStartupUnwindStep(failure, startupPrimary,
					() -> cancelAllRequests(
							StreamTerminationReason.SERVER_STOPPING, null));
			forceApplied = true;
		}
	}

	private void ensureStartupMayContinue(
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation generation,
			@NonNull AtomicReference<Throwable> startupFailure) throws IOException {
		Throwable exactFailure = requireNonNull(startupFailure).get();
		if (exactFailure != null)
			rethrowStartupFailure(exactFailure);
		boolean stopping;
		synchronized (lifecycleLock) {
			stopping = lifecycleState != LifecycleState.STARTING
					|| lifecycleQuiesced || lifecycleForced;
		}
		if (stopping || requireNonNull(generation).shutdownRequested())
			throw new IOException("MCP shutdown began during listener startup.");
	}

	private void rethrowStartupFailure(@NonNull Throwable failure)
			throws IOException {
		if (requireNonNull(failure) instanceof IOException exception)
			throw exception;
		if (failure instanceof RuntimeException exception)
			throw exception;
		if (failure instanceof Error error)
			throw error;
		throw new IOException("The MCP HTTP listener terminated during startup.",
				failure);
	}

	private void catchUpStartupProcessor(@NonNull ThreadPoolExecutor processor) {
		boolean quiesced;
		boolean forced;
		synchronized (lifecycleLock) {
			quiesced = lifecycleQuiesced;
			forced = lifecycleForced;
		}
		if (forced)
			requireNonNull(processor).shutdownNow();
		else if (quiesced)
			processor.shutdown();
	}

	private void catchUpStartupApplication(
			@NonNull McpApplicationExecution application) {
		boolean quiesced;
		boolean forced;
		synchronized (lifecycleLock) {
			quiesced = lifecycleQuiesced;
			forced = lifecycleForced;
		}
		if (forced)
			requireNonNull(application).stop(
					StreamTerminationReason.SERVER_STOPPING);
		else if (quiesced)
			application.beginGracefulDrain();
	}

	private void catchUpStartupEventLoop(@NonNull EventLoop loop) {
		boolean quiesced;
		boolean forced;
		synchronized (lifecycleLock) {
			quiesced = lifecycleQuiesced;
			forced = lifecycleForced;
		}
		if (quiesced || forced) {
			requireNonNull(loop).stopAccepting();
			loop.beginDrain();
		}
		if (forced)
			loop.stopConnections();
	}

	private void publishStartupSubscriptionRegistration(
			@NonNull SubscriptionSourceRegistrationControl registration) {
		synchronized (lifecycleLock) {
			publishStartupSubscriptionRegistrationsWhileLocked(
					List.of(requireNonNull(registration)));
		}
		catchUpStartupSubscriptionRegistration(registration);
	}

	private void publishStartupSubscriptionRegistrationsWhileLocked(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					registrations) {
		if (!Thread.holdsLock(lifecycleLock))
			throw new IllegalStateException("The MCP lifecycle lock is required.");
		if (lifecycleQuiesced || lifecycleForced)
			residualSubscriptionSourceRegistrations =
					mergeSubscriptionSourceRegistrations(
							residualSubscriptionSourceRegistrations,
							requireNonNull(registrations));
		else
			subscriptionSourceRegistrations = mergeSubscriptionSourceRegistrations(
					subscriptionSourceRegistrations, requireNonNull(registrations));
	}

	private void catchUpStartupSubscriptionRegistration(
			@NonNull SubscriptionSourceRegistrationControl registration) {
		boolean quiesced;
		boolean forced;
		synchronized (lifecycleLock) {
			quiesced = lifecycleQuiesced;
			forced = lifecycleForced;
		}
		if (!quiesced && !forced)
			return;
		SubscriptionSourceRegistrationControl requiredRegistration =
				requireNonNull(registration);
		requiredRegistration.deactivateListener();
		SubscriptionRegistrationCloseAttempt closeAttempt =
				requiredRegistration.beginClose(
						subscriptionCloseThreadSequence.incrementAndGet());
		synchronized (lifecycleLock) {
			forced = lifecycleForced;
		}
		if (forced)
			closeAttempt.cancel();
		publishLifecycleCloseBatch(new SubscriptionRegistrationCloseBatch(
				List.of(requiredRegistration), List.of(closeAttempt)));
	}

	private void publishLifecycleCloseBatch(
			@NonNull SubscriptionRegistrationCloseBatch closeBatch) {
		synchronized (lifecycleLock) {
			lifecycleCloseBatch = mergeSubscriptionRegistrationCloseBatches(
					lifecycleCloseBatch, requireNonNull(closeBatch));
		}
	}

	@NonNull
	private SubscriptionRegistrationCloseBatch mergeSubscriptionRegistrationCloseBatches(
			@Nullable SubscriptionRegistrationCloseBatch first,
			@NonNull SubscriptionRegistrationCloseBatch second) {
		if (first == null)
			return requireNonNull(second);
		SubscriptionRegistrationCloseBatch requiredSecond = requireNonNull(second);
		List<SubscriptionSourceRegistrationControl> registrations =
				mergeSubscriptionSourceRegistrations(first.registrations(),
						requiredSecond.registrations());
		Set<SubscriptionRegistrationCloseAttempt> mergedAttempts =
				Collections.newSetFromMap(new IdentityHashMap<>());
		List<SubscriptionRegistrationCloseAttempt> attempts = new ArrayList<>();
		for (SubscriptionRegistrationCloseAttempt attempt : first.closeAttempts()) {
			if (mergedAttempts.add(attempt))
				attempts.add(attempt);
		}
		for (SubscriptionRegistrationCloseAttempt attempt
				: requiredSecond.closeAttempts()) {
			if (mergedAttempts.add(attempt))
				attempts.add(attempt);
		}
		return new SubscriptionRegistrationCloseBatch(registrations, attempts);
	}

	private void signalStartupFailure(
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation generation,
			@NonNull Throwable failure,
			@NonNull AtomicBoolean failureSignaled,
			@NonNull Object failureSignalLock) {
		AtomicBoolean exactFailureSignaled = requireNonNull(failureSignaled);
		synchronized (requireNonNull(failureSignalLock)) {
			if (!exactFailureSignaled.compareAndSet(false, true))
				return;
			try {
				requireNonNull(generation).signalTerminationFailure(
						requireNonNull(failure));
			} catch (Throwable ignored) {
				// Signal-path failure cannot replace or decorate the elected cause.
			}
		}
	}

	private void retainCompetingStartupFailure(
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation generation,
			@NonNull Throwable primary, @Nullable Throwable secondary,
			@NonNull AtomicBoolean failureSignaled,
			@NonNull AtomicBoolean diagnosticRetained,
			@NonNull Object failureSignalLock) {
		Throwable exactSecondary = secondary;
		if (exactSecondary == null || sameInstance(exactSecondary, primary))
			return;
		AtomicBoolean exactDiagnosticRetained = requireNonNull(
				diagnosticRetained);
		if (!exactDiagnosticRetained.compareAndSet(false, true))
			return;
		if (!requireNonNull(generation).coordinatorOwnsUnexpectedTermination()) {
			primary.addSuppressed(exactSecondary);
			return;
		}
		AtomicBoolean exactFailureSignaled = requireNonNull(failureSignaled);
		synchronized (requireNonNull(failureSignalLock)) {
			if (!exactFailureSignaled.get()) {
				// No result can expose this primary before its first signal.  Claim the
				// per-start diagnostic slot first so later group-routed competitors
				// cannot append a second cause across the signal boundary.
				primary.addSuppressed(exactSecondary);
				return;
			}
			// The lifecycle group owns both the one-diagnostic cap and freeze boundary.
			try {
				generation.signalTerminationFailure(exactSecondary);
			} catch (Throwable ignored) {
				// Diagnostic retention cannot replace the elected startup primary.
			}
		}
	}

	private void emitOmittedCorsAuthorizerDiagnostic() {
		boolean anyAuthorizerOmitted = this.endpointsByPath.values().stream()
				.map(EndpointRuntime::binding)
				.map(McpHttpEndpointBinding::endpointPolicy)
				.anyMatch(policy -> !policy.corsAuthorizerExplicitlyConfigured());
		if (!anyAuthorizerOmitted)
			return;

		try {
			startupDiagnosticConsumer.accept(OMITTED_CORS_AUTHORIZER_DIAGNOSTIC);
		} catch (Throwable ignored) {
			// Diagnostics must not change listener startup or availability.
		}
	}

	private void subscribeToSubscriptionEventSources(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					registrations) {
		subscribeToSubscriptionEventSources(registrations, ignored -> {});
	}

	private void subscribeToSubscriptionEventSources(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					registrations,
			@NonNull Consumer<@NonNull SubscriptionSourceRegistrationControl>
					registrationConsumer) {
		requireNonNull(registrations);
		requireNonNull(registrationConsumer);
		for (SubscriptionSourceGroup group : subscriptionSourceGroups) {
			subscribeToSubscriptionEventSource(registrations,
					group.endpointPaths(), group.source().subscriber(),
					registrationConsumer);
			for (EndpointSubscriptionSource endpointSource : group.endpointSources())
				subscribeToSubscriptionEventSource(registrations,
					Set.of(endpointSource.endpointPath()),
					endpointSource.subscriber(), registrationConsumer);
		}
	}

	private void subscribeToSubscriptionEventSource(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					registrations,
			@NonNull Set<@NonNull String> endpointPaths,
			McpSubscriptionEventSource.@NonNull Subscriber subscriber,
			@NonNull Consumer<@NonNull SubscriptionSourceRegistrationControl>
					registrationConsumer) {
		SubscriptionEventSourceGeneration generation =
				new SubscriptionEventSourceGeneration();
		SubscriptionEventListenerFence listenerFence =
				new SubscriptionEventListenerFence(generation, event -> {
					try {
						publishSubscriptionEvent(endpointPaths, event,
								generation);
					} catch (Throwable ignored) {
						// Runtime fan-out must never escape into an application publisher.
					}
		});
		SubscriptionSourceRegistrationControl control = null;
		try {
			Registration registration = requireNonNull(subscriber).subscribe(
					listenerFence::onEvent);
			if (registration == null)
				throw new NullPointerException(
						"An MCP subscription event source returned a null registration.");
			control = new SubscriptionSourceRegistrationControl(
					registration, listenerFence);
			registrations.add(control);
			requireNonNull(registrationConsumer).accept(control);
		} catch (RuntimeException | Error failure) {
			listenerFence.deactivate();
			// Once subscribe() returns, the framework owns the registration even if
			// later bookkeeping fails.  Start a retained close attempt before
			// propagating so startup cleanup cannot lose that application resource.
			if (control != null) {
				SubscriptionSourceRegistrationControl retainedControl = control;
				retainedControl.beginClose(
						subscriptionCloseThreadSequence.incrementAndGet());
				synchronized (lifecycleLock) {
					residualSubscriptionSourceRegistrations =
							mergeSubscriptionSourceRegistrations(
									residualSubscriptionSourceRegistrations,
									List.of(retainedControl));
				}
			}
			throw failure;
		}
	}

	@NonNull
	private SubscriptionRegistrationCloseBatch
			beginClosingSubscriptionEventSourceRegistrations(
					@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
							registrations) {
		List<SubscriptionSourceRegistrationControl> copiedRegistrations =
				List.copyOf(requireNonNull(registrations));
		for (SubscriptionSourceRegistrationControl registration
				: copiedRegistrations)
			registration.deactivateListener();

		List<SubscriptionRegistrationCloseAttempt> closeAttempts =
				new ArrayList<>(copiedRegistrations.size());
		for (SubscriptionSourceRegistrationControl registration
				: copiedRegistrations) {
			closeAttempts.add(registration.beginClose(
					subscriptionCloseThreadSequence.incrementAndGet()));
		}
		return new SubscriptionRegistrationCloseBatch(copiedRegistrations,
				closeAttempts);
	}

	@NonNull
	private SubscriptionRegistrationCloseOutcome
			awaitSubscriptionEventSourceRegistrations(
					@NonNull SubscriptionRegistrationCloseBatch closeBatch,
					long shutdownStartedAt, long shutdownTimeoutNanos) {
		requireNonNull(closeBatch);
		boolean interrupted = Thread.interrupted();
		for (SubscriptionRegistrationCloseAttempt closeAttempt
				: closeBatch.closeAttempts()) {
			while (!closeAttempt.completed()) {
				long remainingNanos = remainingShutdownNanos(
						shutdownStartedAt, shutdownTimeoutNanos);
				if (remainingNanos <= 0L)
					break;
				try {
					if (!closeAttempt.await(remainingNanos))
						break;
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
		}
		List<SubscriptionSourceRegistrationControl> residualRegistrations =
				unclosedSubscriptionSourceRegistrations(closeBatch.registrations());
		if (interrupted)
			Thread.currentThread().interrupt();
		return new SubscriptionRegistrationCloseOutcome(residualRegistrations);
	}

	@NonNull
	private List<@NonNull SubscriptionSourceRegistrationControl>
			unclosedSubscriptionSourceRegistrations(
					@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
							registrations) {
		return List.copyOf(requireNonNull(registrations).stream()
				.filter(registration -> !registration.closed())
				.toList());
	}

	@NonNull
	private List<@NonNull SubscriptionSourceRegistrationControl>
			mergeSubscriptionSourceRegistrations(
					@NonNull List<@NonNull SubscriptionSourceRegistrationControl> first,
					@NonNull List<@NonNull SubscriptionSourceRegistrationControl> second) {
		Set<SubscriptionSourceRegistrationControl> merged =
				Collections.newSetFromMap(new IdentityHashMap<>());
		List<SubscriptionSourceRegistrationControl> ordered = new ArrayList<>();
		for (SubscriptionSourceRegistrationControl registration
				: List.copyOf(requireNonNull(first))) {
			if (merged.add(registration))
				ordered.add(registration);
		}
		for (SubscriptionSourceRegistrationControl registration
				: List.copyOf(requireNonNull(second))) {
			if (merged.add(registration))
				ordered.add(registration);
		}
		return List.copyOf(ordered);
	}

	@NonNull
	private IllegalStateException subscriptionRegistrationCloseFailure(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					residualRegistrations) {
		IllegalStateException failure = new IllegalStateException(
				RESIDUAL_SUBSCRIPTION_EVENT_SOURCE_DIAGNOSTIC);
		for (SubscriptionSourceRegistrationControl registration
				: List.copyOf(requireNonNull(residualRegistrations))) {
			Throwable closeFailure = registration.latestCloseFailure();
			if (closeFailure != null && !sameInstance(closeFailure, failure))
				failure.addSuppressed(closeFailure);
		}
		return failure;
	}

	@NonNull
	private IllegalStateException residualTransportStopFailure(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					residualSubscriptionRegistrations) {
		IllegalStateException failure = new IllegalStateException(
				RESIDUAL_TRANSPORT_DIAGNOSTIC);
		if (!residualSubscriptionRegistrations.isEmpty())
			failure.addSuppressed(subscriptionRegistrationCloseFailure(
					residualSubscriptionRegistrations));
		return failure;
	}

	private boolean retryResidualSubscriptionEventSourceRegistrations(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					registrations) {
		long shutdownStartedAt = System.nanoTime();
		long shutdownTimeoutNanos = transportConfiguration.shutdownTimeout().toNanos();
		SubscriptionRegistrationCloseOutcome closeOutcome =
				new SubscriptionRegistrationCloseOutcome(registrations);
		List<SubscriptionSourceRegistrationControl> residualRegistrations =
				List.of();
		boolean residualExecutions = false;
		try {
			SubscriptionRegistrationCloseBatch closeBatch =
					beginClosingSubscriptionEventSourceRegistrations(registrations);
			closeOutcome = awaitSubscriptionEventSourceRegistrations(
					closeBatch, shutdownStartedAt, shutdownTimeoutNanos);
		} finally {
			synchronized (lifecycleLock) {
				residualRegistrations =
						unclosedSubscriptionSourceRegistrations(
								closeOutcome.residualRegistrations());
				residualSubscriptionSourceRegistrations = residualRegistrations;
				residualExecutions = (residualApplicationExecution != null
						&& !residualApplicationExecution.isTerminated())
						|| (residualRequestProcessor != null
						&& !residualRequestProcessor.isTerminated());
				lifecycleState = LifecycleState.STOPPED;
				lifecycleLock.notifyAll();
			}
		}

		if (!residualRegistrations.isEmpty())
			throw subscriptionRegistrationCloseFailure(
					residualRegistrations);
		return residualExecutions;
	}

	/** Prompt, idempotent graceful MCP wind-up for compatibility callers. */
	void quiesceLifecycle() {
		quiesceLifecycle(false, 0L);
	}

	/**
	 * Prompt, idempotent graceful MCP wind-up against the common coordinator's
	 * already-fixed absolute boundary.
	 */
	void quiesceLifecycle(long absoluteDeadlineNanos) {
		quiesceLifecycle(true, absoluteDeadlineNanos);
	}

	private void quiesceLifecycle(boolean deadlinePresent,
			long absoluteDeadlineNanos) {
		EventLoop loop;
		ThreadPoolExecutor processor;
		McpApplicationExecution application;
		List<SubscriptionSourceRegistrationControl> registrations;
		synchronized (lifecycleLock) {
			if (deadlinePresent && !lifecycleGracefulDeadlinePresent) {
				lifecycleGracefulDeadlineNanos = absoluteDeadlineNanos;
				lifecycleGracefulDeadlinePresent = true;
			}
			if (lifecycleQuiesced || lifecycleQuiesceRequested)
				return;
			lifecycleQuiesceRequested = true;
			publishLifecycleQuiesceIntentWhileLocked();
			if (lifecycleStartupClaimed)
				return;
			lifecycleQuiesced = true;
			loop = eventLoop != null ? eventLoop : residualEventLoop;
			processor = requestProcessor != null
					? requestProcessor : residualRequestProcessor;
			application = applicationExecution != null
					? applicationExecution : residualApplicationExecution;
			registrations = residualSubscriptionSourceRegistrations;
		}

		if (loop != null) {
			loop.stopAccepting();
			loop.beginDrain();
		}
		Set<RequestControl> subscriptions = stopAcceptingSubscriptions();
		completeSubscriptions(subscriptions);
		for (RequestControl control : requestControlsSnapshot())
			control.quiesceTransport();
		beginGracefulSubscriptionEventSourceRegistrationCloses(registrations);
		if (processor != null) {
			if (application != null && processor instanceof LifecycleRequestProcessor
					lifecycleProcessor)
				lifecycleProcessor.afterTermination(
						application::beginGracefulDrain);
			processor.shutdown();
		} else if (application != null) {
			application.beginGracefulDrain();
		}
	}

	private void publishLifecycleQuiesceIntentWhileLocked() {
		if (!Thread.holdsLock(lifecycleLock))
			throw new IllegalStateException("The MCP lifecycle lock is required.");
		if (currentReadiness != null)
			currentReadiness.set(ListenerState.TERMINATED);
		List<SubscriptionSourceRegistrationControl> registrations =
				mergeSubscriptionSourceRegistrations(
						residualSubscriptionSourceRegistrations,
						subscriptionSourceRegistrations);
		subscriptionSourceRegistrations = List.of();
		residualSubscriptionSourceRegistrations = registrations;
		if (lifecycleState == LifecycleState.STARTING
				&& !lifecycleStartupClaimed) {
			lifecycleStartupInProgress = false;
			lifecycleStartupGeneration = null;
			lifecycleLock.notifyAll();
		}
		if (lifecycleState == LifecycleState.STARTING
				|| lifecycleState == LifecycleState.STARTED
				|| lifecycleState == LifecycleState.FAILED)
			lifecycleState = LifecycleState.STOPPING;
	}

	/** Prompt, idempotent force phase for compatibility callers. */
	void forceLifecycle() {
		forceLifecycle(false, 0L);
	}

	/**
	 * Prompt, idempotent force phase against the common coordinator's
	 * already-fixed absolute boundary; force-first always subsumes quiesce.
	 */
	void forceLifecycle(long absoluteDeadlineNanos) {
		forceLifecycle(true, absoluteDeadlineNanos);
	}

	private void forceLifecycle(boolean deadlinePresent,
			long absoluteDeadlineNanos) {
		EventLoop loop;
		ThreadPoolExecutor processor;
		McpApplicationExecution application;
		List<SubscriptionSourceRegistrationControl> registrations;
		synchronized (lifecycleLock) {
			if (deadlinePresent && !lifecycleForcedDeadlinePresent) {
				lifecycleForcedDeadlineNanos = absoluteDeadlineNanos;
				lifecycleForcedDeadlinePresent = true;
			}
			if (lifecycleForced || lifecycleForceRequested)
				return;
			lifecycleForceRequested = true;
			if (lifecycleStartupClaimed) {
				if (!lifecycleQuiesceRequested) {
					lifecycleQuiesceRequested = true;
					publishLifecycleQuiesceIntentWhileLocked();
				}
				return;
			}
			lifecycleForced = true;
			loop = eventLoop != null ? eventLoop : residualEventLoop;
			processor = requestProcessor != null
					? requestProcessor : residualRequestProcessor;
			application = applicationExecution != null
					? applicationExecution : residualApplicationExecution;
			registrations = mergeSubscriptionSourceRegistrations(
					residualSubscriptionSourceRegistrations,
					subscriptionSourceRegistrations);
			subscriptionSourceRegistrations = List.of();
			residualSubscriptionSourceRegistrations = registrations;
		}

		Throwable failure = null;
		failure = runLifecycleStep(failure, this::closeLegacySessions);
		failure = runLifecycleStep(failure, () -> {
			if (deadlinePresent)
				quiesceLifecycle(absoluteDeadlineNanos);
			else
				quiesceLifecycle();
		});
		if (loop != null) {
			failure = runLifecycleStep(failure, loop::stopAccepting);
			failure = runLifecycleStep(failure, loop::beginDrain);
		}
		Set<RequestControl> subscriptions = stopAcceptingSubscriptions();
		failure = runLifecycleStep(failure,
				() -> completeSubscriptions(subscriptions));
		for (RequestControl control : requestControlsSnapshot())
			failure = runLifecycleStep(failure, control::quiesceTransport);
		SubscriptionRegistrationCloseBatch closeBatch =
				beginClosingSubscriptionEventSourceRegistrations(registrations);
		for (SubscriptionRegistrationCloseAttempt closeAttempt
				: closeBatch.closeAttempts())
			closeAttempt.cancel();
		publishLifecycleCloseBatch(closeBatch);
		if (loop != null)
			failure = runLifecycleStep(failure, loop::stopConnections);
		if (application != null)
			failure = runLifecycleStep(failure, () -> application.stop(
					StreamTerminationReason.SERVER_STOPPING));
		failure = runLifecycleStep(failure, () -> cancelAllRequests(
				StreamTerminationReason.SERVER_STOPPING, null));
		if (processor != null)
			failure = runLifecycleStep(failure, processor::shutdownNow);
		failure = runLifecycleStep(failure, () -> cancelAllRequests(
				StreamTerminationReason.SERVER_STOPPING, null));
		failure = runLifecycleStep(failure, this::closeLegacySessions);
		rethrowLifecycleFailure(failure);
	}

	private void beginGracefulSubscriptionEventSourceRegistrationCloses(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					registrations) {
		synchronized (lifecycleLock) {
			// Force may run concurrently with a canceled graceful call.  Either
			// publish the graceful attempts before force claims the phase, or let
			// force create and cancel every attempt; never create uncanceled work
			// after force has returned.
			if (lifecycleForced)
				return;
			SubscriptionRegistrationCloseBatch closeBatch =
					beginClosingSubscriptionEventSourceRegistrations(registrations);
			lifecycleCloseBatch = mergeSubscriptionRegistrationCloseBatches(
					lifecycleCloseBatch, closeBatch);
		}
	}

	private @Nullable Throwable runLifecycleStep(@Nullable Throwable first,
			@NonNull Runnable step) {
		try {
			requireNonNull(step).run();
		} catch (Throwable failure) {
			if (first == null)
				return failure;
			if (!sameInstance(failure, first))
				first.addSuppressed(failure);
		}
		return first;
	}

	private @Nullable Throwable runStartupUnwindStep(
			@Nullable Throwable first, @NonNull Throwable startupPrimary,
			@NonNull Runnable step) {
		try {
			requireNonNull(step).run();
		} catch (Throwable failure) {
			// The startup cause may already be visible through frozen evidence.
			// Never decorate it (or another cleanup failure) here; the lifecycle
			// group owns the single freeze-aware secondary slot.
			if (first == null && !sameInstance(failure, requireNonNull(startupPrimary)))
				return failure;
		}
		return first;
	}

	private void rethrowLifecycleFailure(@Nullable Throwable failure) {
		if (failure instanceof RuntimeException runtimeException)
			throw runtimeException;
		if (failure instanceof Error error)
			throw error;
	}

	/** Observes the complete runtime barrier against the supplied absolute deadline. */
	boolean awaitLifecycleTermination(long absoluteDeadlineNanos)
			throws InterruptedException {
		long phaseDeadlineNanos;
		EventLoop loop;
		ThreadPoolExecutor processor;
		McpApplicationExecution application;
		SubscriptionRegistrationCloseBatch closeBatch;
		List<SubscriptionSourceRegistrationControl> registrations;
		synchronized (lifecycleLock) {
			phaseDeadlineNanos = activeLifecyclePhaseDeadline(
					absoluteDeadlineNanos);
			while (lifecycleStartupInProgress) {
				long remaining = remainingUntil(phaseDeadlineNanos);
				if (remaining <= 0L)
					return false;
				TimeUnit.NANOSECONDS.timedWait(lifecycleLock, remaining);
			}
			loop = eventLoop != null ? eventLoop : residualEventLoop;
			processor = requestProcessor != null
					? requestProcessor : residualRequestProcessor;
			application = applicationExecution != null
					? applicationExecution : residualApplicationExecution;
			closeBatch = lifecycleCloseBatch;
			registrations = mergeSubscriptionSourceRegistrations(
					residualSubscriptionSourceRegistrations,
					subscriptionSourceRegistrations);
		}

		boolean loopTerminated = loop == null || loop.joinUntil(phaseDeadlineNanos);
		boolean processorTerminated = processor == null || processor.isTerminated();
		if (!processorTerminated) {
			long remaining = remainingUntil(phaseDeadlineNanos);
			if (remaining > 0L)
				processorTerminated = processor.awaitTermination(
						remaining, TimeUnit.NANOSECONDS);
		}
		boolean applicationTerminated = application == null
				|| application.isTerminated();
		if (!applicationTerminated) {
			long remaining = remainingUntil(phaseDeadlineNanos);
			if (remaining > 0L)
				applicationTerminated = application.awaitTermination(
						Duration.ofNanos(remaining));
		}
		boolean closeAttemptsCompleted = true;
		if (closeBatch != null) {
			for (SubscriptionRegistrationCloseAttempt attempt
					: closeBatch.closeAttempts()) {
				if (!attempt.completed()) {
					long remaining = remainingUntil(phaseDeadlineNanos);
					if (remaining > 0L)
						attempt.await(remaining);
				}
				closeAttemptsCompleted &= attempt.completed();
			}
		}
		boolean registrationsClosed = unclosedSubscriptionSourceRegistrations(
				registrations).isEmpty();
		return loopTerminated && processorTerminated && applicationTerminated
				&& closeAttemptsCompleted && registrationsClosed
				&& requestControlsSnapshot().isEmpty()
				&& activeStreamsAndSubscriptionsEnded();
	}

	private long activeLifecyclePhaseDeadline(long observerDeadlineNanos) {
		long activeDeadlineNanos = lifecycleForcedDeadlinePresent
				? lifecycleForcedDeadlineNanos
				: lifecycleGracefulDeadlinePresent
						? lifecycleGracefulDeadlineNanos : observerDeadlineNanos;
		return earlierDeadline(observerDeadlineNanos, activeDeadlineNanos);
	}

	private long earlierDeadline(long first, long second) {
		return remainingUntil(first) <= remainingUntil(second) ? first : second;
	}

	@NonNull
	McpLifecycleEvidence lifecycleEvidence() {
		EventLoop loop;
		ThreadPoolExecutor processor;
		McpApplicationExecution application;
		List<SubscriptionSourceRegistrationControl> registrations;
		SubscriptionRegistrationCloseBatch closeBatch;
		boolean startupInProgress;
		synchronized (lifecycleLock) {
			loop = eventLoop != null ? eventLoop : residualEventLoop;
			processor = requestProcessor != null
					? requestProcessor : residualRequestProcessor;
			application = applicationExecution != null
					? applicationExecution : residualApplicationExecution;
			registrations = mergeSubscriptionSourceRegistrations(
					residualSubscriptionSourceRegistrations,
					subscriptionSourceRegistrations);
			closeBatch = lifecycleCloseBatch;
			startupInProgress = lifecycleStartupInProgress;
		}
		McpApplicationExecutionSnapshot applicationSnapshot = application == null
				? null : application.snapshot(activeIdentifiedRequestExchangeCount.get());
		boolean streams;
		synchronized (streamDiagnosticsLock) {
			streams = activeRequestStreams != 0 || activeSubscriptions != 0;
		}
		boolean registrationCloseInProgress = closeBatch != null
				&& closeBatch.closeAttempts().stream()
						.anyMatch(attempt -> !attempt.completed());
		return new McpLifecycleEvidence(
				loop != null && !loop.isTerminated(),
				loop != null && loop.numAdmittedConnections() > 0,
				(processor != null && !processor.isTerminated())
						|| (application != null && !application.isTerminated()),
				streams || !requestControlsSnapshot().isEmpty(),
				startupInProgress || applicationSnapshot != null
						&& applicationSnapshot.activeHandlerSlots() > 0,
				registrationCloseInProgress
						|| !unclosedSubscriptionSourceRegistrations(
								registrations).isEmpty());
	}

	/** Releases proof-bearing resources only after the common barrier completed. */
	void releaseLifecycleEvidence() {
		closeLegacySessions();
		synchronized (lifecycleLock) {
			eventLoop = null;
			residualEventLoop = null;
			requestProcessor = null;
			residualRequestProcessor = null;
			applicationExecution = null;
			residualApplicationExecution = null;
			subscriptionSourceRegistrations = List.of();
			residualSubscriptionSourceRegistrations = List.of();
			lifecycleCloseBatch = null;
			currentReadiness = null;
			lifecycleQuiesceRequested = false;
			lifecycleForceRequested = false;
			lifecycleQuiesced = false;
			lifecycleForced = false;
			lifecycleGracefulDeadlinePresent = false;
			lifecycleForcedDeadlinePresent = false;
			lifecycleStartupInProgress = false;
			lifecycleStartupClaimed = false;
			lifecycleStartupGeneration = null;
			lifecycleState = LifecycleState.STOPPED;
			lifecycleLock.notifyAll();
		}
	}

	@NonNull
	private List<@NonNull RequestControl> requestControlsSnapshot() {
		synchronized (requestControls) {
			return List.copyOf(requestControls.values());
		}
	}

	private boolean activeStreamsAndSubscriptionsEnded() {
		synchronized (streamDiagnosticsLock) {
			return activeRequestStreams == 0 && activeSubscriptions == 0;
		}
	}

	private long remainingUntil(long absoluteDeadlineNanos) {
		return remainingUntil(absoluteDeadlineNanos, System.nanoTime());
	}

	private long remainingUntil(long absoluteDeadlineNanos, long now) {
		long remaining = absoluteDeadlineNanos - now;
		if (((absoluteDeadlineNanos ^ now) & (absoluteDeadlineNanos ^ remaining)) < 0)
			return absoluteDeadlineNanos >= now ? Long.MAX_VALUE : 0L;
		return Math.max(0L, remaining);
	}

	void stop() {
		stopAndReportResidualApplicationExecutions();
	}

	boolean stopAndReportResidualApplicationExecutions() {
		this.applicationExecutionObserver.beginDeferral();
		try {
			return stopAndReportResidualApplicationExecutionsWhileMetricsDeferred();
		} finally {
			this.applicationExecutionObserver.endDeferral();
		}
	}

	private boolean stopAndReportResidualApplicationExecutionsWhileMetricsDeferred() {
		@Nullable EventLoop eventLoopToStop = null;
		@Nullable ThreadPoolExecutor processorToStop = null;
		@Nullable McpApplicationExecution applicationToStop = null;
		List<SubscriptionSourceRegistrationControl>
				subscriptionRegistrationsToClose;
		boolean interrupted = false;
		boolean residualSubscriptionRegistrationsOnly = false;
		boolean residualApplicationExecutions = false;
		boolean residualTransport = false;

		synchronized (lifecycleLock) {
			while (lifecycleState == LifecycleState.STOPPING) {
				try {
					lifecycleLock.wait();
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}

			if (lifecycleState == LifecycleState.STOPPED) {
				if (residualEventLoop != null && residualEventLoop.isTerminated())
					residualEventLoop = null;
				residualSubscriptionSourceRegistrations =
						unclosedSubscriptionSourceRegistrations(
								residualSubscriptionSourceRegistrations);
				if (residualEventLoop != null) {
					if (interrupted)
						Thread.currentThread().interrupt();
					throw new IllegalStateException(RESIDUAL_TRANSPORT_DIAGNOSTIC);
				}
				if (residualSubscriptionSourceRegistrations.isEmpty()) {
					if (interrupted)
						Thread.currentThread().interrupt();
					return (residualApplicationExecution != null
							&& !residualApplicationExecution.isTerminated())
							|| (residualRequestProcessor != null
							&& !residualRequestProcessor.isTerminated());
				}
				lifecycleState = LifecycleState.STOPPING;
				subscriptionRegistrationsToClose =
						residualSubscriptionSourceRegistrations;
				residualSubscriptionRegistrationsOnly = true;
			} else {
				if (lifecycleState != LifecycleState.STARTED
						&& lifecycleState != LifecycleState.FAILED)
					throw new IllegalStateException(
							"The MCP HTTP server cannot stop from state "
									+ lifecycleState + ".");

				lifecycleState = LifecycleState.STOPPING;
				if (currentReadiness != null)
					currentReadiness.set(ListenerState.TERMINATED);
				eventLoopToStop = eventLoop;
				processorToStop = requestProcessor;
				applicationToStop = applicationExecution;
				subscriptionRegistrationsToClose =
						mergeSubscriptionSourceRegistrations(
								residualSubscriptionSourceRegistrations,
								subscriptionSourceRegistrations);
				subscriptionSourceRegistrations = List.of();
				residualSubscriptionSourceRegistrations =
						subscriptionRegistrationsToClose;
			}
		}

		if (interrupted)
			Thread.currentThread().interrupt();
		if (residualSubscriptionRegistrationsOnly)
			return retryResidualSubscriptionEventSourceRegistrations(
					subscriptionRegistrationsToClose);

		EventLoop requiredEventLoopToStop = eventLoopToStop;
		ThreadPoolExecutor requiredProcessorToStop = processorToStop;
		McpApplicationExecution requiredApplicationToStop = applicationToStop;

		boolean eventLoopTerminated = requiredEventLoopToStop == null;
		boolean applicationTerminated = requiredApplicationToStop == null;
		List<SubscriptionSourceRegistrationControl>
				residualSubscriptionRegistrations = List.of();
		SubscriptionRegistrationCloseOutcome subscriptionCloseOutcome =
				new SubscriptionRegistrationCloseOutcome(
						subscriptionRegistrationsToClose);
		try {
			long shutdownStartedAt = System.nanoTime();
			long shutdownTimeoutNanos = transportConfiguration.shutdownTimeout().toNanos();
			closeLegacySessions();
			SubscriptionRegistrationCloseBatch subscriptionCloseBatch =
					beginClosingSubscriptionEventSourceRegistrations(
							subscriptionRegistrationsToClose);
			if (requiredEventLoopToStop != null)
				requiredEventLoopToStop.stopAccepting();
			Set<RequestControl> subscriptionsToComplete =
					stopAcceptingSubscriptions();
			// Close application admission and atomically drain its queue before
			// interrupting active work. Otherwise an interrupted active handler can
			// promote queued application code during shutdown.
			if (requiredApplicationToStop != null)
				requiredApplicationToStop.stop();
			completeSubscriptions(subscriptionsToComplete);
			cancelAllNonSubscriptionRequests(
					StreamTerminationReason.SERVER_STOPPING, null);
			if (requiredEventLoopToStop != null)
				requiredEventLoopToStop.beginDrain();
			long remainingBeforeSubscriptionDrain = remainingShutdownNanos(
					shutdownStartedAt, shutdownTimeoutNanos);
			long forcedTransportReserveNanos = Math.min(
					TimeUnit.SECONDS.toNanos(1L),
					Math.max(1L, remainingBeforeSubscriptionDrain / 2L));
			long subscriptionDrainNanos = Math.max(0L,
					remainingBeforeSubscriptionDrain
							- forcedTransportReserveNanos);
			if (subscriptionDrainNanos > 0L) {
				try {
					awaitSubscriptionsClosed(subscriptionDrainNanos);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
			if (requiredEventLoopToStop != null)
				requiredEventLoopToStop.stopConnections();
			if (requiredProcessorToStop != null)
				requiredProcessorToStop.shutdownNow();
			cancelAllRequests(StreamTerminationReason.SERVER_STOPPING, null);
			closeLegacySessions();

			while (!eventLoopTerminated && requiredEventLoopToStop != null) {
				long remainingNanos = remainingShutdownNanos(
						shutdownStartedAt, shutdownTimeoutNanos);
				if (remainingNanos <= 0L)
					break;
				try {
					eventLoopTerminated = requiredEventLoopToStop.join(
							Duration.ofNanos(remainingNanos));
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}

			while (requiredProcessorToStop != null
					&& !requiredProcessorToStop.isTerminated()) {
				long remainingNanos = remainingShutdownNanos(
						shutdownStartedAt, shutdownTimeoutNanos);
				if (remainingNanos <= 0L)
					break;

				try {
					requiredProcessorToStop.awaitTermination(
							remainingNanos, TimeUnit.NANOSECONDS);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}

			while (!applicationTerminated && requiredApplicationToStop != null) {
				long remainingNanos = remainingShutdownNanos(
						shutdownStartedAt, shutdownTimeoutNanos);
				if (remainingNanos <= 0L)
					break;
				try {
					applicationTerminated = requiredApplicationToStop.awaitTermination(
							Duration.ofNanos(remainingNanos));
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
			subscriptionCloseOutcome =
					awaitSubscriptionEventSourceRegistrations(
							subscriptionCloseBatch, shutdownStartedAt,
							shutdownTimeoutNanos);
		} finally {
			synchronized (lifecycleLock) {
				eventLoop = null;
				residualEventLoop = requiredEventLoopToStop == null
							|| eventLoopTerminated
							|| requiredEventLoopToStop.isTerminated()
						? null : requiredEventLoopToStop;
					residualTransport = residualEventLoop != null;
				requestProcessor = null;
				residualRequestProcessor = requiredProcessorToStop == null
						|| requiredProcessorToStop.isTerminated()
						? null : requiredProcessorToStop;
				applicationExecution = null;
				residualApplicationExecution = requiredApplicationToStop == null
						|| applicationTerminated
							|| requiredApplicationToStop.isTerminated()
						? null : requiredApplicationToStop;
				residualApplicationExecutions = residualApplicationExecution != null
						|| residualRequestProcessor != null;
				residualSubscriptionRegistrations =
						unclosedSubscriptionSourceRegistrations(
								subscriptionCloseOutcome.residualRegistrations());
				residualSubscriptionSourceRegistrations =
						residualSubscriptionRegistrations;
				currentReadiness = null;
				lifecycleState = LifecycleState.STOPPED;
				lifecycleLock.notifyAll();
			}

			if (interrupted)
				Thread.currentThread().interrupt();
		}

		if (residualTransport)
			throw residualTransportStopFailure(
					residualSubscriptionRegistrations);
		if (!residualSubscriptionRegistrations.isEmpty())
			throw subscriptionRegistrationCloseFailure(
					residualSubscriptionRegistrations);
		return residualApplicationExecutions;
	}

	private long remainingShutdownNanos(long shutdownStartedAt,
			long shutdownTimeoutNanos) {
		long elapsedNanos = System.nanoTime() - shutdownStartedAt;
		return elapsedNanos >= shutdownTimeoutNanos
				? 0L : shutdownTimeoutNanos - elapsedNanos;
	}

	private void awaitSubscriptionsClosed(long timeoutNanos)
			throws InterruptedException {
		long waitStartedAt = System.nanoTime();
		synchronized (subscriptionLock) {
			while (!pendingSubscriptions.isEmpty()
					|| !activeSubscriptionsByEndpointPath.isEmpty()) {
				long remainingNanos = remainingShutdownNanos(
						waitStartedAt, timeoutNanos);
				if (remainingNanos <= 0L)
					return;
				TimeUnit.NANOSECONDS.timedWait(subscriptionLock, remainingNanos);
			}
		}
	}

	private long saturatingAdd(long left, long right) {
		long result = left + right;
		return ((left ^ result) & (right ^ result)) < 0
				? Long.MAX_VALUE : result;
	}

	boolean isStarted() {
		return lifecycleSnapshot().started();
	}

	@NonNull
	Optional<@NonNull InetSocketAddress> boundAddress() {
		return lifecycleSnapshot().boundAddress();
	}

	boolean hasResidualApplicationExecutions() {
		return lifecycleSnapshot().residualApplicationExecutions();
	}

	@NonNull
	McpHttpServerLifecycleSnapshot lifecycleSnapshot() {
		synchronized (lifecycleLock) {
			residualSubscriptionSourceRegistrations =
					unclosedSubscriptionSourceRegistrations(
							residualSubscriptionSourceRegistrations);
			boolean started = lifecycleState == LifecycleState.STARTED;
			boolean stopRequired = lifecycleState == LifecycleState.STARTING
					|| lifecycleState == LifecycleState.STARTED
					|| lifecycleState == LifecycleState.STOPPING
					|| lifecycleState == LifecycleState.FAILED
					|| (residualEventLoop != null && !residualEventLoop.isTerminated())
					|| !residualSubscriptionSourceRegistrations.isEmpty();
			Optional<@NonNull InetSocketAddress> effectiveAddress =
					Optional.ofNullable(boundAddress);
			McpApplicationExecution residualExecution = lifecycleState
					== LifecycleState.FAILED
					? applicationExecution : residualApplicationExecution;
			ThreadPoolExecutor residualProcessor = lifecycleState
					== LifecycleState.FAILED
					? requestProcessor : residualRequestProcessor;
			boolean residualExecutions = (residualExecution != null
					&& !residualExecution.isTerminated())
					|| (residualProcessor != null && !residualProcessor.isTerminated());
			return new McpHttpServerLifecycleSnapshot(started, stopRequired,
					effectiveAddress, residualExecutions);
		}
	}

	@NonNull
	McpHttpServerDiagnosticsSnapshot diagnosticsSnapshot() {
		synchronized (lifecycleLock) {
			reapSimulationResidualsWhileLocked();
			if (simulationGeneration != null
					|| residualSimulationRequestProcessor != null
					|| residualSimulationApplicationExecution != null
					|| !residualSimulationSubscriptionSourceRegistrations.isEmpty()) {
				return new McpHttpServerDiagnosticsSnapshot(false, false,
						Optional.ofNullable(boundAddress), false,
						applicationConfiguration.handlerConcurrency(),
						applicationConfiguration.handlerQueueCapacity(),
						0, 0, 0, 0);
			}
			residualSubscriptionSourceRegistrations =
					unclosedSubscriptionSourceRegistrations(
							residualSubscriptionSourceRegistrations);
			boolean started = lifecycleState == LifecycleState.STARTED;
			boolean stopRequired = lifecycleState == LifecycleState.STARTING
					|| lifecycleState == LifecycleState.STARTED
					|| lifecycleState == LifecycleState.STOPPING
					|| lifecycleState == LifecycleState.FAILED
					|| (residualEventLoop != null && !residualEventLoop.isTerminated())
					|| !residualSubscriptionSourceRegistrations.isEmpty();
			Optional<@NonNull InetSocketAddress> effectiveAddress =
					Optional.ofNullable(boundAddress);
			boolean currentGenerationResidual = lifecycleState
					== LifecycleState.STOPPING
					|| lifecycleState == LifecycleState.FAILED;
			McpApplicationExecution residualExecution = currentGenerationResidual
					? applicationExecution : residualApplicationExecution;
			ThreadPoolExecutor residualProcessor = currentGenerationResidual
					? requestProcessor : residualRequestProcessor;
			boolean residualExecutions = (residualExecution != null
					&& !residualExecution.isTerminated())
					|| (residualProcessor != null && !residualProcessor.isTerminated());
			synchronized (streamDiagnosticsLock) {
				McpApplicationExecution diagnosticsExecution = lifecycleState
						== LifecycleState.STOPPED
								? residualApplicationExecution : applicationExecution;
				McpApplicationExecutionSnapshot applicationSnapshot =
						diagnosticsExecution == null ? null
								: diagnosticsExecution.snapshot(
										activeIdentifiedRequestExchangeCount.get());
				int requestHandlerConcurrency = applicationSnapshot == null
						? applicationConfiguration.handlerConcurrency()
						: applicationSnapshot.configuredHandlerConcurrency();
				int requestHandlerQueueCapacity = applicationSnapshot == null
						? applicationConfiguration.handlerQueueCapacity()
						: applicationSnapshot.configuredHandlerQueueCapacity();
				int activeHandlerExecutions = applicationSnapshot == null
						? 0 : applicationSnapshot.activeHandlerSlots();
				int queuedRequests = applicationSnapshot == null
						? 0 : applicationSnapshot.queuedRequests();
				boolean residualDiagnostics = residualExecutions || !started
						&& (activeHandlerExecutions != 0 || queuedRequests != 0
						|| activeRequestStreams != 0 || activeSubscriptions != 0);
				return new McpHttpServerDiagnosticsSnapshot(started, stopRequired,
						effectiveAddress, residualDiagnostics,
						requestHandlerConcurrency, requestHandlerQueueCapacity,
						activeHandlerExecutions, queuedRequests,
						activeRequestStreams, activeSubscriptions);
			}
		}
	}

	private void recordStreamDiagnosticsTransition(int requestStreamDelta,
			int subscriptionDelta) {
		if (requestStreamDelta == 0 && subscriptionDelta == 0)
			return;
		synchronized (streamDiagnosticsLock) {
			int updatedRequestStreams = Math.addExact(activeRequestStreams,
					requestStreamDelta);
			int updatedSubscriptions = Math.addExact(activeSubscriptions,
					subscriptionDelta);
			if (updatedRequestStreams < 0 || updatedSubscriptions < 0
					|| updatedSubscriptions > updatedRequestStreams)
				throw new IllegalStateException(
						"MCP active stream diagnostics became inconsistent.");
			activeRequestStreams = updatedRequestStreams;
			activeSubscriptions = updatedSubscriptions;
		}
	}

	@NonNull
	Optional<@NonNull McpApplicationExecutionSnapshot> applicationExecutionSnapshot() {
		synchronized (lifecycleLock) {
			McpApplicationExecution execution = applicationExecution != null
					? applicationExecution : residualApplicationExecution;
			return execution == null ? Optional.empty()
					: Optional.of(execution.snapshot(
							activeIdentifiedRequestExchangeCount.get()));
		}
	}

	@NonNull
	McpRequestExecutionSnapshot requestExecutionSnapshot() {
		synchronized (lifecycleLock) {
			ThreadPoolExecutor processor = requestProcessor != null
					? requestProcessor : residualRequestProcessor;
			List<RequestControl> controls;
			synchronized (requestControls) {
				controls = List.copyOf(requestControls.values());
			}
			int activeStreams = 0;
			long bufferedFrames = 0L;
			long bufferedBytes = 0L;
			long terminalBytes = 0L;
			int maximumObservedFrames = 0;
			int maximumObservedBytes = 0;
			for (RequestControl control : controls) {
				Optional<McpOutboundChannel.Snapshot> streamSnapshot =
						control.streamSnapshot();
				if (streamSnapshot.isEmpty())
					continue;
				McpOutboundChannel.Snapshot stream = streamSnapshot.orElseThrow();
				if (!stream.closed())
					activeStreams++;
				bufferedFrames += stream.bufferedFrames();
				bufferedBytes += stream.bufferedBytes();
				terminalBytes += stream.terminalBytes();
				maximumObservedFrames = Math.max(maximumObservedFrames,
						stream.maximumObservedBufferedFrames());
				maximumObservedBytes = Math.max(maximumObservedBytes,
						stream.maximumObservedBufferedBytes());
			}
			return new McpRequestExecutionSnapshot(controls.size(),
					processor == null ? 0 : processor.getQueue().size(),
					activeIdentifiedRequestExchangeCount.get(), activeStreams,
					bufferedFrames,
					bufferedBytes, terminalBytes, maximumObservedFrames,
					maximumObservedBytes,
					unknownMirroredHeaderOccurrences.get());
		}
	}

	void runApplicationTimerCycle() {
		McpApplicationExecution execution;
		synchronized (lifecycleLock) {
			execution = requireNonNull(applicationExecution,
					"The MCP application execution runtime is not started.");
		}
		execution.runTimerCycle();
	}

	@Override
	public void close() {
		stop();
	}

	@NonNull
	private Options microhttpOptions() {
		return Options.builder()
				.withHost(transportConfiguration.host())
				.withPort(transportConfiguration.port())
				.withReuseAddr(true)
				.withResolution(transportConfiguration.selectorResolution())
				.withRequestHeaderTimeout(transportConfiguration.requestHeaderTimeout())
				.withRequestBodyTimeout(transportConfiguration.requestBodyTimeout())
				.withResponseWriteIdleTimeout(
						transportConfiguration.responseWriteIdleTimeout())
				.withReadBufferSize(transportConfiguration.readBufferSize())
				.withAcceptLength(transportConfiguration.acceptBacklog())
				.withMaxRequestSize(
						transportConfiguration.maximumAggregateRequestBytes())
				.withMaxRequestBodySize(
						transportConfiguration.maximumRequestBodyBytes())
				.withMaxHeaderCount(transportConfiguration.maximumHeaderCount())
				.withMaxHeadersSize(transportConfiguration.maximumHeaderBytes())
				.withMaxRequestTargetLength(
						transportConfiguration.maximumRequestTargetBytes())
				.withMaxConnections(transportConfiguration.maximumConnections())
				.withConcurrency(transportConfiguration.connectionWriterConcurrency())
				.withEarlyErrorResponseHeaders(
						List.of(new Header(CACHE_CONTROL, CACHE_CONTROL_NO_STORE)))
				.build();
	}

	@NonNull
	private LifecycleRequestProcessor newRequestProcessor() {
		int concurrency = transportConfiguration.requestProcessorConcurrency();
		boolean taskNotifications = this.subscriptionSourceGroups.stream()
				.anyMatch(group -> group.source().sourceType()
						== McpSubscriptionEventSource.SourceType.TASK);
		boolean catalogNotifications = this.endpointsByPath.values().stream()
				.flatMap(endpointRuntime -> endpointRuntime.binding()
						.supportedRevisions().stream()
						.map(endpointRuntime::capabilityRegistry))
				.map(McpServerCapabilityRegistry::capabilities)
				.anyMatch(capabilities -> capabilities.tools()
						.map(McpCatalogCapability::listChanged).orElse(false)
						|| capabilities.prompts()
								.map(McpCatalogCapability::listChanged).orElse(false));
		if ((taskNotifications || catalogNotifications) && concurrency < 2)
			throw new IllegalStateException(
					"MCP subscription notification projections require request-processor concurrency of at least two.");
		int taskProjectionConcurrency = concurrency == 1 ? 1
				: Math.min(MAXIMUM_TASK_NOTIFICATION_PROJECTION_CONCURRENCY,
						concurrency - 1);
		int taskProjectionQueueCapacity = Math.min(
				MAXIMUM_TASK_NOTIFICATION_PROJECTION_QUEUE_CAPACITY,
				transportConfiguration.requestProcessorQueueCapacity());
		ThreadFactory threadFactory = runnable -> {
			Thread thread = new Thread(runnable, "soklet-mcp-request-"
					+ processorThreadSequence.incrementAndGet());
			thread.setDaemon(false);
			return thread;
		};
		return new LifecycleRequestProcessor(
				concurrency,
				concurrency,
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(
						transportConfiguration.requestProcessorQueueCapacity()),
				threadFactory, taskProjectionConcurrency,
				taskProjectionQueueCapacity,
				new ThreadPoolExecutor.AbortPolicy());
	}

	/** Runs the registered graceful handoff exactly once after protocol drain. */
	private static final class LifecycleRequestProcessor extends ThreadPoolExecutor {
		@NonNull
		private final AtomicReference<@Nullable Runnable> afterTermination;
		@NonNull
		private final AtomicBoolean terminationObserved;
		@NonNull
		private final TaskNotificationProjectionScheduler
				taskNotificationProjectionScheduler;

		private LifecycleRequestProcessor(int corePoolSize, int maximumPoolSize,
				long keepAliveTime, @NonNull TimeUnit unit,
				@NonNull ArrayBlockingQueue<@NonNull Runnable> workQueue,
				@NonNull ThreadFactory threadFactory,
				int taskProjectionConcurrency,
				int taskProjectionQueueCapacity,
				@NonNull RejectedExecutionHandler handler) {
			super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
					threadFactory, handler);
			this.afterTermination = new AtomicReference<>();
			this.terminationObserved = new AtomicBoolean();
			this.taskNotificationProjectionScheduler =
					new TaskNotificationProjectionScheduler(this,
							taskProjectionConcurrency,
							taskProjectionQueueCapacity);
		}

		private void executeTaskNotificationProjection(
				@NonNull TaskNotificationProjectionJob job) {
			this.taskNotificationProjectionScheduler.execute(requireNonNull(job));
		}

		@Override
		public void shutdown() {
			this.taskNotificationProjectionScheduler.shutdown();
			super.shutdown();
		}

		@Override
		@NonNull
		public List<@NonNull Runnable> shutdownNow() {
			this.taskNotificationProjectionScheduler.shutdown();
			return super.shutdownNow();
		}

		private void afterTermination(@NonNull Runnable action) {
			if (!this.afterTermination.compareAndSet(null, requireNonNull(action)))
				return;
			if (isTerminated())
				runAfterTermination();
		}

		@Override
		protected void terminated() {
			try {
				runAfterTermination();
			} finally {
				super.terminated();
			}
		}

		@Override
		protected void afterExecute(@NonNull Runnable runnable,
				@Nullable Throwable failure) {
			try {
				this.taskNotificationProjectionScheduler.executorMayAcceptWorker();
			} finally {
				super.afterExecute(requireNonNull(runnable), failure);
			}
		}

		private void runAfterTermination() {
			Runnable action = this.afterTermination.get();
			if (action != null && this.terminationObserved.compareAndSet(false, true))
				action.run();
		}
	}

	/**
	 * Bounded fair scheduler shared by task, catalog, and subscription-
	 * authorization maintenance so asynchronous fan-out cannot fill the protocol
	 * request queue. Each worker performs one job and returns to the tail of that
	 * queue before it may perform another.
	 */
	@ThreadSafe
	static final class TaskNotificationProjectionScheduler {
		@NonNull
		private final Executor executor;
		private final int maximumWorkers;
		@NonNull
		private final ArrayBlockingQueue<@NonNull TaskNotificationProjectionJob>
				jobs;
		@NonNull
		private final Object lock;
		private int workersScheduled;
		private boolean shutdown;

		TaskNotificationProjectionScheduler(@NonNull Executor executor,
				int maximumWorkers, int queueCapacity) {
			this.executor = requireNonNull(executor);
			if (maximumWorkers < 1)
				throw new IllegalArgumentException(
						"Task-notification projection concurrency must be positive.");
			if (queueCapacity < 1)
				throw new IllegalArgumentException(
						"Task-notification projection queue capacity must be positive.");
			this.maximumWorkers = maximumWorkers;
			this.jobs = new ArrayBlockingQueue<>(queueCapacity);
			this.lock = new Object();
		}

		void execute(@NonNull TaskNotificationProjectionJob job) {
			TaskNotificationProjectionJob requiredJob = requireNonNull(job);
			boolean scheduleWorker = false;
			List<TaskNotificationProjectionJob> rejected = new ArrayList<>();
			synchronized (this.lock) {
				if (this.shutdown)
					rejected.add(requiredJob);
				else if (!this.jobs.offer(requiredJob)) {
					// Each logical owner normally contributes at most one queued job.
					// Keep owner-aware eviction as a fail-safe if a future caller violates
					// that invariant, rather than letting its duplicates displace an
					// unrelated stream.
					int currentOwnerCount = queuedOwnerCountWhileLocked(
							requiredJob.owner());
					Object mostRepresentedOwner = mostRepresentedOwnerWhileLocked();
					int mostRepresentedOwnerCount = mostRepresentedOwner == null ? 0
							: queuedOwnerCountWhileLocked(mostRepresentedOwner);
					Object victim = currentOwnerCount == 0
							? (mostRepresentedOwnerCount > 1
									? mostRepresentedOwner : null)
							: (mostRepresentedOwnerCount > currentOwnerCount
									? mostRepresentedOwner : requiredJob.owner());
					if (victim == null) {
						rejected.add(requiredJob);
					} else {
						rejected.addAll(removeOwnerJobsWhileLocked(victim));
						if (sameInstance(victim, requiredJob.owner()))
							rejected.add(requiredJob);
						else if (!this.jobs.offer(requiredJob))
							throw new IllegalStateException(
									"A task-notification projection slot was not reclaimed.");
					}
				}
				if (!this.jobs.isEmpty()
						&& this.workersScheduled < this.maximumWorkers) {
					this.workersScheduled++;
					scheduleWorker = true;
				}
			}
			reject(rejected);
			if (scheduleWorker)
				submitWorker();
		}

		void executorMayAcceptWorker() {
			boolean scheduleWorker = false;
			synchronized (this.lock) {
				if (!this.shutdown && !this.jobs.isEmpty()
						&& this.workersScheduled < this.maximumWorkers) {
					this.workersScheduled++;
					scheduleWorker = true;
				}
			}
			if (scheduleWorker)
				submitWorker();
		}

		int queuedJobCount() {
			synchronized (this.lock) {
				return this.jobs.size();
			}
		}

		private void submitWorker() {
			try {
				this.executor.execute(this::runOne);
			} catch (RuntimeException | Error failure) {
				List<TaskNotificationProjectionJob> rejected = List.of();
				synchronized (this.lock) {
					this.workersScheduled--;
					if (!(failure instanceof RejectedExecutionException)
							&& this.workersScheduled == 0)
						rejected = drainJobsWhileLocked();
				}
				reject(rejected);
				if (!(failure instanceof RejectedExecutionException))
					throw failure;
			}
		}

		@Nullable
		private Object mostRepresentedOwnerWhileLocked() {
			if (!Thread.holdsLock(this.lock))
				throw new IllegalStateException(
						"The task-notification scheduler lock is required.");
			Map<Object, Integer> counts = new IdentityHashMap<>();
			Object mostRepresented = null;
			int greatestCount = 0;
			for (TaskNotificationProjectionJob job : this.jobs) {
				int count = counts.merge(job.owner(), 1, Integer::sum);
				if (count > greatestCount) {
					greatestCount = count;
					mostRepresented = job.owner();
				}
			}
			return mostRepresented;
		}

		private int queuedOwnerCountWhileLocked(@NonNull Object owner) {
			if (!Thread.holdsLock(this.lock))
				throw new IllegalStateException(
						"The task-notification scheduler lock is required.");
			int count = 0;
			for (TaskNotificationProjectionJob job : this.jobs)
				if (sameInstance(job.owner(), requireNonNull(owner)))
					count++;
			return count;
		}

		@NonNull
		private List<@NonNull TaskNotificationProjectionJob>
		removeOwnerJobsWhileLocked(@NonNull Object owner) {
			if (!Thread.holdsLock(this.lock))
				throw new IllegalStateException(
						"The task-notification scheduler lock is required.");
			Object requiredOwner = requireNonNull(owner);
			List<TaskNotificationProjectionJob> queued = drainJobsWhileLocked();
			List<TaskNotificationProjectionJob> removed = new ArrayList<>();
			for (TaskNotificationProjectionJob job : queued) {
				if (sameInstance(job.owner(), requiredOwner))
					removed.add(job);
				else if (!this.jobs.offer(job))
					throw new IllegalStateException(
							"A retained task-notification projection could not be restored.");
			}
			return List.copyOf(removed);
		}

		private void runOne() {
			TaskNotificationProjectionJob job;
			synchronized (this.lock) {
				job = this.shutdown ? null : this.jobs.poll();
				if (job == null)
					this.workersScheduled--;
			}
			if (job == null)
				return;

			try {
				job.run();
			} finally {
				boolean scheduleWorker;
				synchronized (this.lock) {
					scheduleWorker = !this.shutdown && !this.jobs.isEmpty();
					if (!scheduleWorker)
						this.workersScheduled--;
				}
				if (scheduleWorker)
					submitWorker();
			}
		}

		void shutdown() {
			List<TaskNotificationProjectionJob> rejected;
			synchronized (this.lock) {
				if (this.shutdown)
					return;
				this.shutdown = true;
				rejected = drainJobsWhileLocked();
			}
			reject(rejected);
		}

		@NonNull
		private List<@NonNull TaskNotificationProjectionJob>
		drainJobsWhileLocked() {
			if (!Thread.holdsLock(this.lock))
				throw new IllegalStateException(
						"The task-notification scheduler lock is required.");
			List<TaskNotificationProjectionJob> drained = new ArrayList<>();
			this.jobs.drainTo(drained);
			return drained;
		}

		private void reject(
				@NonNull List<@NonNull TaskNotificationProjectionJob> rejected) {
			for (TaskNotificationProjectionJob job : requireNonNull(rejected))
				try {
					job.reject();
				} catch (Throwable ignored) {
					// One rejected stream cannot strand scheduler cleanup for peers.
				}
		}
	}

	record TaskNotificationProjectionJob(@NonNull Object owner,
			@NonNull Runnable task,
			@NonNull Runnable rejection) {
		TaskNotificationProjectionJob {
			requireNonNull(owner);
			requireNonNull(task);
			requireNonNull(rejection);
		}

		private void run() {
			this.task.run();
		}

		private void reject() {
			this.rejection.run();
		}
	}

	@NonNull
	private ConnectionListener connectionListener(
			@NonNull AtomicReference<@NonNull ListenerState> readiness,
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation
					lifecycleGeneration,
			@NonNull AtomicReference<Throwable> startupFailure,
			@NonNull AtomicBoolean startupFailureSignaled,
			@NonNull AtomicBoolean startupFailureDiagnosticRetained,
			@NonNull Object startupFailureSignalLock) {
		return new ConnectionListener() {
			@Override
			public void willAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
				recordConnectionMetric(true);
			}

			@Override
			public void didFailToAcceptConnection(@Nullable InetSocketAddress remoteAddress) {
				recordConnectionMetric(false);
			}

			@Override
			public void didFailToAcceptConnection(
					@Nullable InetSocketAddress remoteAddress,
					@Nullable Throwable throwable) {
				// Accept/setup faults are typed transport failures, not capacity rejection.
			}

			@Override
			public void didTerminateEventLoop(@NonNull EventLoop terminatedEventLoop,
					@NonNull Throwable throwable) {
				enterLifecycleProofExecution();
				try {
					ListenerState previous;
					Throwable exactStartupFailure = null;
					boolean coordinatorOwned = lifecycleGeneration
							.coordinatorOwnsUnexpectedTermination();
					boolean startupTermination;
					synchronized (lifecycleLock) {
						previous = readiness.get();
						startupTermination = previous == ListenerState.STARTING
								|| coordinatorOwned
								&& previous == ListenerState.TERMINATED
								&& lifecycleStartupInProgress
								&& sameInstance(lifecycleStartupGeneration, lifecycleGeneration);
						if (startupTermination) {
							requireNonNull(startupFailure).compareAndSet(
									null, throwable);
							exactStartupFailure = requireNonNull(
									startupFailure.get());
						}
						if (previous != ListenerState.TERMINATED)
							readiness.set(ListenerState.TERMINATED);
					}
					if (startupTermination) {
						// Preserve and publish the EventLoop's exact pre-readiness cause
						// before reporting it or allowing startup cleanup to proceed.
						Throwable primary = requireNonNull(exactStartupFailure);
						retainCompetingStartupFailure(lifecycleGeneration, primary,
								throwable, startupFailureSignaled,
								startupFailureDiagnosticRetained,
								startupFailureSignalLock);
						signalStartupFailure(lifecycleGeneration, primary,
								startupFailureSignaled,
								startupFailureSignalLock);
						try {
							unexpectedTerminationConsumer.accept(primary);
						} catch (Throwable ignored) {
							// Failure reporting cannot replace the listener's primary cause.
						}
					} else if (previous == ListenerState.READY || coordinatorOwned) {
						handleUnexpectedTermination(terminatedEventLoop, throwable,
								lifecycleGeneration);
					}
				} finally {
					exitLifecycleProofExecution();
				}
			}
		};
	}

	private void recordConnectionMetric(boolean accepted) {
		boolean deferred = false;
		try {
			this.applicationExecutionObserver.beginRequestTransitionDeferral();
			deferred = true;
			if (accepted)
				this.applicationExecutionObserver.recordConnectionAccepted();
			else
				this.applicationExecutionObserver.recordConnectionRejected();
		} catch (Throwable ignored) {
			// Metrics observation must not alter connection admission.
		} finally {
			if (deferred) {
				try {
					this.applicationExecutionObserver
							.endDeferralForAsynchronousDrain();
				} catch (Throwable ignored) {
					// A failed internal observer must not alter connection admission.
				} finally {
					this.transportMetricDrainScheduler.schedule();
				}
			}
		}
	}

	private TransportFailureObserver.@NonNull Observation beginTransportFailure(
			@NonNull TransportFailureReason reason) {
		this.applicationExecutionObserver.beginRequestTransitionDeferral();
		try {
			McpApplicationExecutionObserver.PendingMetricRecord pendingRecord =
					this.applicationExecutionObserver.recordTransportFailure(
							requireNonNull(reason));
			return new RuntimeTransportFailureObservation(pendingRecord);
		} catch (RuntimeException | Error failure) {
			try {
				this.applicationExecutionObserver.endDeferralForAsynchronousDrain();
			} finally {
				this.transportMetricDrainScheduler.schedule();
			}
			throw failure;
		}
	}

	private void observeTransportFailure(
			@NonNull TransportFailureReason reason,
			@NonNull Runnable terminalConsequences) {
		TransportFailureObserver.@Nullable Observation observation = null;
		try {
			observation = this.transportFailureObserver.beginFailure(
					requireNonNull(reason));
		} catch (Throwable ignored) {
			// Metrics observation must not alter a terminal transport transition.
		}
		try {
			requireNonNull(terminalConsequences).run();
		} finally {
			if (observation != null) {
				try {
					observation.close();
				} catch (Throwable ignored) {
					// Metrics observation must not alter the transport transition.
				}
			}
		}
	}

	private void handleUnexpectedTermination(@NonNull EventLoop terminatedEventLoop,
			@NonNull Throwable throwable,
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation
					lifecycleGeneration) {
		// EventLoop's typed transport-failure scope already defers this complete
		// terminal transition without waiting on an active collector callback.
		handleUnexpectedTerminationWhileMetricsDeferred(terminatedEventLoop,
				throwable, lifecycleGeneration);
	}

	private void handleUnexpectedTerminationWhileMetricsDeferred(
			@NonNull EventLoop terminatedEventLoop,
			@NonNull Throwable throwable,
			McpServerRuntimeBridge.LifecycleAdapter.@NonNull Generation
					lifecycleGeneration) {
		if (!lifecycleGeneration.coordinatorOwnsUnexpectedTermination()) {
			handleLegacyUnexpectedTerminationWhileMetricsDeferred(
					terminatedEventLoop, throwable);
			return;
		}
		synchronized (lifecycleLock) {
			if (!sameInstance(eventLoop, terminatedEventLoop))
				return;
			if (lifecycleState == LifecycleState.STARTED)
				lifecycleState = LifecycleState.FAILED;
		}

		// The common termination signal must be the first framework-visible
		// consequence.  Its coordinator exclusively owns quiesce/force; this
		// failed event-loop callback performs no transport-wide cancellation,
		// executor shutdown, registration close, or evidence clearing.
		lifecycleGeneration.signalTerminationFailure(throwable);
		try {
			unexpectedTerminationConsumer.accept(throwable);
		} catch (Throwable ignored) {
			// Failure reporting cannot alter coordinator-owned shutdown.
		}
	}

	/** Compatibility cleanup for package-private/direct runtimes without a coordinator. */
	private void handleLegacyUnexpectedTerminationWhileMetricsDeferred(
			@NonNull EventLoop terminatedEventLoop,
			@NonNull Throwable throwable) {
		ThreadPoolExecutor processorToStop;
		McpApplicationExecution applicationToStop;
		List<SubscriptionSourceRegistrationControl> registrationsToClose;
		synchronized (lifecycleLock) {
			if (!sameInstance(eventLoop, terminatedEventLoop)
					|| lifecycleState != LifecycleState.STARTED)
				return;
			lifecycleState = LifecycleState.FAILED;
			processorToStop = requestProcessor;
			applicationToStop = applicationExecution;
			registrationsToClose = mergeSubscriptionSourceRegistrations(
					residualSubscriptionSourceRegistrations,
					subscriptionSourceRegistrations);
			subscriptionSourceRegistrations = List.of();
			residualSubscriptionSourceRegistrations = registrationsToClose;
		}

		closeLegacySessions();
		stopAcceptingSubscriptions();
		SubscriptionRegistrationCloseBatch closeBatch =
				beginClosingSubscriptionEventSourceRegistrations(
						registrationsToClose);
		publishLifecycleCloseBatch(closeBatch);
		if (applicationToStop != null)
			applicationToStop.stop(StreamTerminationReason.INTERNAL_ERROR);
		cancelAllRequests(StreamTerminationReason.INTERNAL_ERROR, null);
		if (processorToStop != null)
			processorToStop.shutdownNow();
		cancelAllRequests(StreamTerminationReason.INTERNAL_ERROR, null);
		closeLegacySessions();
		try {
			unexpectedTerminationConsumer.accept(throwable);
		} catch (Throwable ignored) {
			// Failure reporting must not strand legacy direct-runtime cleanup.
		}
	}

	private void submitRequest(@NonNull ThreadPoolExecutor processor,
			@NonNull McpApplicationExecution application,
			@Nullable InetSocketAddress effectiveAddress,
			@NonNull MicrohttpRequest request,
			@Nullable Runnable lifecycleAdmission,
			@NonNull Consumer<@NonNull MicrohttpResponse> callback) {
		if (effectiveAddress == null) {
			this.applicationExecutionObserver.recordRequestRejected();
			this.applicationExecutionObserver.drain();
			List<Header> headers = localizationVaryRequired(request)
					? withAcceptLanguageVary(List.of()) : List.of();
			MicrohttpResponse response = emptyResponse(
					503, "Service Unavailable", headers);
			if (lifecycleAdmission != null) {
				Runnable requiredAdmission = lifecycleAdmission;
				response = response.withBodyTerminationListener((reason, cause) ->
						requiredAdmission.run());
			}
			try {
				requireNonNull(callback).accept(response);
			} catch (RuntimeException | Error failure) {
				if (lifecycleAdmission != null)
					lifecycleAdmission.run();
				throw failure;
			}
			return;
		}
		submitRequest(processor, application, effectiveAddress, request, null,
				null, lifecycleAdmission, callback);
	}

	@NonNull
	private RequestControl submitSimulationRequest(
			@NonNull SimulationGeneration generation,
			@NonNull Request publicRequest,
			@NonNull McpSimulationRuntime simulation) {
		Request requiredRequest = requireNonNull(publicRequest);
		List<Header> headers = new ArrayList<>();
		requiredRequest.getHeaders().forEach((name, values) ->
				values.forEach(value -> headers.add(new Header(name, value))));
		MicrohttpRequest request = new MicrohttpRequest(
				requiredRequest.getHttpMethod().name(),
				requiredRequest.getRawPathAndQuery(), "HTTP/1.1",
				List.copyOf(headers),
				requiredRequest.getBody().orElseGet(() -> EMPTY_BODY),
				requiredRequest.isContentTooLarge(),
				requiredRequest.getRemoteAddress().orElse(null));
		return submitRequest(generation.processor(), generation.application(),
				generation.effectiveAddress(), request, requiredRequest, simulation,
				null, simulation::acceptResponse);
	}

	@NonNull
	private RequestControl submitRequest(@NonNull ThreadPoolExecutor processor,
			@NonNull McpApplicationExecution application,
			@NonNull InetSocketAddress effectiveAddress,
			@NonNull MicrohttpRequest request,
			@Nullable Request publicRequest,
			@Nullable McpSimulationRuntime simulation,
			@Nullable Runnable lifecycleAdmission,
			@NonNull Consumer<@NonNull MicrohttpResponse> callback) {
		requireNonNull(request);
		requireNonNull(callback);

		InetSocketAddress requiredAddress = requireNonNull(effectiveAddress,
				"An MCP request generation requires an effective address.");

		// nanoTime may wrap; every comparison uses subtraction and the configured
		// positive duration is constrained to the signed nanosecond range.
		long deadlineNanos = applicationClock.nanoTime()
				+ applicationConfiguration.requestDeadline().toNanos();
		RequestControl requestControl = new RequestControl(request, deadlineNanos,
				processor, application, publicRequest, simulation,
				lifecycleAdmission, callback);
		FutureTask<Void> task = new FutureTask<>(() -> {
			try {
				MicrohttpResponse response = processRequest(requiredAddress, request,
						requestControl, application);
				requestControl.completeProtocol(response);
				return null;
			} finally {
				requestControl.legacyProtocolPhysicalFinished();
			}
		}) {
			@Override public void run() {
				synchronized (requestControl.lock) { requestControl.legacyProtocolPhysicalStarted = true; }
				super.run();
			}
			@Override
			protected void done() {
				synchronized (requestControl.lock) {
					if (!requestControl.legacyProtocolPhysicalStarted)
						requestControl.legacyProtocolPhysicalFinished = true;
				}
				requestControl.protocolLifecycleWorkTerminated();
				requestControl.releaseLegacyPhysicalIfComplete();
			}
		};
		requestControl.submit(task);
		return requestControl;
	}

	private void cancelRequest(@NonNull MicrohttpRequest request,
			@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
		RequestControl requestControl = requestControls.get(request);
		if (requestControl != null)
			requestControl.cancel(reason, cause);
	}

	private void cancelAllRequests(@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		List<RequestControl> controls;
		synchronized (requestControls) {
			controls = List.copyOf(requestControls.values());
		}
		for (RequestControl control : controls)
			control.cancel(reason, cause);
	}

	private void closeLegacySessions() {
		McpLegacySessionStore store = legacySessionStore;
		if (store != null) store.close();
	}

	private void startAcceptingSubscriptions() {
		synchronized (subscriptionLock) {
			if (!pendingSubscriptions.isEmpty()
					|| !activeSubscriptionsByEndpointPath.isEmpty()
					|| !activeSubscriptionCountsByPartition.isEmpty())
				throw new IllegalStateException(
						"A new MCP server generation cannot inherit active subscriptions.");
			subscriptionsAccepting = true;
		}
	}

	@NonNull
	private Set<@NonNull RequestControl> stopAcceptingSubscriptions() {
		synchronized (subscriptionLock) {
			subscriptionsAccepting = false;
			Set<RequestControl> subscriptions =
					new LinkedHashSet<>(pendingSubscriptions);
			for (Set<RequestControl> endpointSubscriptions
					: activeSubscriptionsByEndpointPath.values())
				subscriptions.addAll(endpointSubscriptions);
			return Set.copyOf(subscriptions);
		}
	}

	@NonNull
	private SubscriptionRegistrationAttempt registerSubscription(
			@NonNull RequestControl control, @NonNull String endpointPath,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpEffectivePartition authorizationPartition,
			@NonNull McpJsonRpcId subscriptionId,
			@NonNull AcceptedSubscriptionFilter filter,
			@NonNull McpProtocolProfile protocolProfile) {
		requireNonNull(control);
		requireNonNull(endpointPath);
		requireNonNull(endpoint);
		requireNonNull(authorizationPartition);
		requireNonNull(subscriptionId);
		requireNonNull(filter);
		requireNonNull(protocolProfile);
		synchronized (subscriptionLock) {
			if (!subscriptionsAccepting)
				return new SubscriptionRegistrationAttempt(
						SubscriptionRegistrationResult.NOT_ACCEPTING, null);
			int active = activeSubscriptionCountsByPartition.getOrDefault(
					authorizationPartition, 0);
			if (active >= subscriptionRuntimeConfiguration
					.maximumSubscriptionsPerPartition())
				return new SubscriptionRegistrationAttempt(
						SubscriptionRegistrationResult.CAPACITY_REJECTED, null);
			SubscriptionRegistration registration = new SubscriptionRegistration(
					endpointPath, endpoint, authorizationPartition, subscriptionId,
					filter, protocolProfile, applicationClock.nanoTime());
			pendingSubscriptions.add(control);
			activeSubscriptionCountsByPartition.put(authorizationPartition,
					active + 1);
			return new SubscriptionRegistrationAttempt(
					SubscriptionRegistrationResult.REGISTERED, registration);
		}
	}

	private boolean subscriptionMayCommit(@NonNull RequestControl control) {
		requireNonNull(control);
		synchronized (subscriptionLock) {
			return subscriptionsAccepting
					&& (control.lifecycleAdmission == null
					|| !lifecycleAdapter.currentGeneration().shutdownRequested())
					&& pendingSubscriptions.contains(control);
		}
	}

	@NonNull
	@SuppressWarnings("ReferenceEquality")
	private SubscriptionActivationResult activateSubscription(
			@NonNull RequestControl control,
			@NonNull SubscriptionRegistration registration,
			@Nullable Object expectedLocalizationInvalidationToken,
			@NonNull Object expectedCatalogInvalidationToken) {
		requireNonNull(control);
		requireNonNull(registration);
		requireNonNull(expectedCatalogInvalidationToken);
		synchronized (subscriptionLock) {
			if (!control.subscriptionAuthorizationAllowsActivationAtGenerationWhileLocked(
					subscriptionReconciliationGeneration))
				return SubscriptionActivationResult.AUTHORIZATION_STALE;
			if (catalogInvalidationTokens.get(registration.endpointPath())
					!= expectedCatalogInvalidationToken)
				return SubscriptionActivationResult.CATALOG_STALE;
			if (!pendingSubscriptions.remove(control))
				return SubscriptionActivationResult.NOT_ACTIVATED;
			boolean activated = activeSubscriptionsByEndpointPath.computeIfAbsent(
					registration.endpointPath(), ignored -> new LinkedHashSet<>())
					.add(control);
			if (!activated)
				throw new IllegalStateException(
						"An MCP subscription cannot activate twice.");
			return expectedLocalizationInvalidationToken == null
					|| localizationInvalidationTokens.get(registration.endpointPath())
					== expectedLocalizationInvalidationToken
					? SubscriptionActivationResult.ACTIVATED_CURRENT_LOCALIZATION
					: SubscriptionActivationResult.ACTIVATED_STALE_LOCALIZATION;
		}
	}

	@NonNull
	private Object localizationInvalidationToken(
			@NonNull String endpointPath) {
		synchronized (subscriptionLock) {
			return requireNonNull(localizationInvalidationTokens.get(
					requireNonNull(endpointPath)));
		}
	}

	@NonNull
	private Object catalogInvalidationToken(@NonNull String endpointPath) {
		synchronized (subscriptionLock) {
			return requireNonNull(catalogInvalidationTokens.get(
					requireNonNull(endpointPath)));
		}
	}

	private void removeSubscription(@NonNull RequestControl control,
			@NonNull SubscriptionRegistration registration) {
		requireNonNull(control);
		requireNonNull(registration);
		synchronized (subscriptionLock) {
			boolean removed = pendingSubscriptions.remove(control);
			Set<RequestControl> subscriptions =
					activeSubscriptionsByEndpointPath.get(registration.endpointPath());
			if (subscriptions != null && subscriptions.remove(control)) {
				removed = true;
				if (subscriptions.isEmpty())
					activeSubscriptionsByEndpointPath.remove(
							registration.endpointPath());
			}
			if (!removed)
				return;
			int active = activeSubscriptionCountsByPartition.getOrDefault(
					registration.authorizationPartition(), 0);
			if (active <= 1)
				activeSubscriptionCountsByPartition.remove(
						registration.authorizationPartition());
			else
				activeSubscriptionCountsByPartition.put(
						registration.authorizationPartition(), active - 1);
			subscriptionLock.notifyAll();
		}
	}

	private void publishSubscriptionEvent(
			@NonNull Set<@NonNull String> endpointPaths,
			@NonNull Event event,
			@NonNull SubscriptionEventSourceGeneration generation) {
		requireNonNull(endpointPaths);
		requireNonNull(event);
		requireNonNull(generation);
		Set<RequestControl> subscriptions = new LinkedHashSet<>();
		synchronized (subscriptionLock) {
			if (!generation.active())
				return;
			if (event instanceof McpSubscriptionEventSource.Event
					.LocalizationCatalogsChanged)
				for (String endpointPath : endpointPaths)
					if (localizationInvalidationTokens.containsKey(endpointPath))
						localizationInvalidationTokens.put(endpointPath, new Object());
			boolean catalogInvalidation = event instanceof McpSubscriptionEventSource
					.Event.ToolsListChanged
					|| event instanceof McpSubscriptionEventSource.Event
						.PromptsListChanged
					|| event instanceof McpSubscriptionEventSource.Event
						.LocalizationCatalogsChanged invalidation
						&& (invalidation.tools() || invalidation.prompts());
			if (catalogInvalidation)
				for (String endpointPath : endpointPaths)
					if (catalogInvalidationTokens.containsKey(endpointPath))
						catalogInvalidationTokens.put(endpointPath, new Object());
			for (String endpointPath : endpointPaths) {
				Set<RequestControl> endpointSubscriptions =
						activeSubscriptionsByEndpointPath.get(endpointPath);
				if (endpointSubscriptions != null)
					subscriptions.addAll(endpointSubscriptions);
			}
		}
		for (RequestControl subscription : subscriptions) {
			try {
				if (event instanceof McpSubscriptionEventSource.Event.TaskChanged task)
					subscription.scheduleTaskSubscriptionEvent(task);
				else if (event instanceof McpSubscriptionEventSource.Event
						.ToolsListChanged
						|| event instanceof McpSubscriptionEventSource.Event
							.PromptsListChanged)
					subscription.scheduleCatalogSubscriptionEvent(event);
				else if (event instanceof McpSubscriptionEventSource.Event
						.LocalizationCatalogsChanged invalidation) {
					subscription.scheduleCatalogSubscriptionEvent(event);
					if (invalidation.resources())
						subscription.offerSubscriptionEvent(event);
				} else
					subscription.offerSubscriptionEvent(event);
			} catch (Throwable ignored) {
				// One subscriber can never alter publisher or peer delivery.
			}
		}
		if (generation.active()) publishLegacySubscriptionEvent(endpointPaths, event, generation);
	}

	/**
	 * Fences delivery for every establishing or active local subscription before
	 * asking each owner to establish a fresh authorization generation.
	 */
	void reconcileSubscriptions() {
		List<LegacyGetControl> legacyGets;
		synchronized (legacyMaintenanceLock) {
			legacyTransportReconciliationGeneration++;
			legacyGets = List.copyOf(legacyGetControls.values());
		}
		for (LegacyGetControl get : legacyGets) get.reconcile();
		McpLegacySessionStore legacyStore = legacySessionStore;
		if (legacyStore != null) legacyStore.fenceGrants();
		if (subscriptionRuntimeConfiguration.authorizer().isEmpty())
			return;
		Set<RequestControl> subscriptions = new LinkedHashSet<>();
		synchronized (subscriptionLock) {
			subscriptionReconciliationGeneration++;
			subscriptions.addAll(pendingSubscriptions);
			for (Set<RequestControl> endpointSubscriptions
					: activeSubscriptionsByEndpointPath.values())
				subscriptions.addAll(endpointSubscriptions);
		}
		for (RequestControl subscription : subscriptions)
			try {
				subscription.reconcileSubscriptionAuthorization();
			} catch (Throwable throwable) {
				subscription.failSubscriptionAuthorization(
						McpStreamTerminationReason.SUBSCRIPTION_RECONCILIATION_FAILED,
						throwable);
			}
	}

	private long currentSubscriptionReconciliationGeneration() {
		synchronized (subscriptionLock) {
			return subscriptionReconciliationGeneration;
		}
	}

	private void recordSubscriptionMaintenance(@NonNull String endpointPath,
			McpMetricsEvent.SubscriptionMaintenance.@NonNull Work work,
			McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome) {
		try {
			applicationExecutionObserver.recordSubscriptionMaintenance(
					requireNonNull(endpointPath), requireNonNull(work),
					requireNonNull(outcome));
			applicationExecutionObserver.drainAsynchronously();
		} catch (Throwable ignored) {
			// Metrics observation must never alter subscription behavior.
		}
	}

	private void completeSubscriptions(
			@NonNull Set<@NonNull RequestControl> subscriptions) {
		requireNonNull(subscriptions);
		for (RequestControl subscription : subscriptions)
			subscription.completeSubscription(
					StreamTerminationReason.SERVER_STOPPING);
	}

	private void cancelAllNonSubscriptionRequests(
			@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
		List<RequestControl> controls;
		synchronized (requestControls) {
			controls = List.copyOf(requestControls.values());
		}
		for (RequestControl control : controls) {
			if (!control.hasSubscriptionRegistration())
				control.cancel(reason, cause);
		}
	}

	private void runProtocolDeadlineCycle(long nowNanos) {
		legacyHttpControlBudget.maintain();
		McpLegacySessionStore store = legacySessionStore;
		if (store != null)
			store.maintain();
		for (LegacyUriGrantControl grant : legacyUriGrantControls) {
			try { grant.onTimer(nowNanos); }
			catch (Throwable ignored) { /* The current lease still bounds a failed maintenance dispatch. */ }
		}
		for (Map.Entry<String, Map<String, Set<McpResourceNotificationType>>> endpoint : legacyTransportFamilies.entrySet())
			for (String revision : endpoint.getValue().keySet()) flushLegacyNotifications(endpoint.getKey(), revision);
		List<RequestControl> controls;
		synchronized (requestControls) {
			controls = List.copyOf(requestControls.values());
		}
		for (RequestControl control : controls) {
			try {
				control.onTimer(nowNanos);
				control.releaseLegacyPhysicalIfComplete();
			} catch (Throwable throwable) {
				control.cancel(StreamTerminationReason.INTERNAL_ERROR, throwable);
			}
		}
	}

	private @Nullable MicrohttpResponse processRequest(
			@NonNull InetSocketAddress effectiveAddress,
			@NonNull MicrohttpRequest request,
			@NonNull RequestControl requestControl,
			@NonNull McpApplicationExecution application) {
		try {
			MicrohttpResponse response = processRequestSafely(effectiveAddress, request,
					requestControl, application);
			return response == null ? null : requestControl.decorateResponse(response);
		} catch (Throwable throwable) {
			requestControl.planRequestObservation(new RequestObservationResult(
					McpRequestOutcome.INTERNAL_ERROR, null,
					List.of(throwable)));
			return requestControl.decorateResponse(
					emptyResponse(500, "Internal Server Error", List.of()));
		}
	}

	private @Nullable MicrohttpResponse processRequestSafely(
			@NonNull InetSocketAddress effectiveAddress,
			@NonNull MicrohttpRequest request,
			@NonNull RequestControl requestControl,
			@NonNull McpApplicationExecution application) {
		if (!requestControl.protocolProcessingAllowed())
			return null;

		if (request.contentTooLarge()
				|| request.body().length > transportConfiguration.maximumRequestBodyBytes())
			return emptyResponse(413, "Content Too Large", List.of());

		if (!"HTTP/1.1".equals(request.version()))
			return emptyResponse(505, "HTTP Version Not Supported", List.of());

		String path = requestPath(request.uri());
		if (path.isEmpty())
			return emptyResponse(400, "Bad Request", List.of());
		EndpointRuntime endpointRuntime = this.endpointsByPath.get(path);
		if (endpointRuntime == null)
			return emptyResponse(404, "Not Found", List.of());
		McpHttpEndpointBinding endpointBinding = endpointRuntime.binding();
		McpHttpEndpointPolicy endpointPolicy = endpointBinding.endpointPolicy();

		if (!authorizedHost(effectiveAddress, request, endpointPolicy))
			return emptyResponse(421, "Misdirected Request", List.of());

		MicrohttpResponse originPolicyFailure =
				prevalidateOriginPolicy(request, endpointPolicy);
		if (originPolicyFailure != null)
			return originPolicyFailure;

		Optional<HttpMethod> httpMethod = httpMethod(request.method());
		if (httpMethod.isEmpty()) {
			// The shared CORS API requires a recognized HttpMethod. Never fabricate
			// one for an unknown wire token: a present Origin fails closed, while an
			// absent Origin can proceed to the ordinary 405 response.
			return headerValues(request, ORIGIN).isEmpty()
					? methodNotAllowed(List.of())
					: emptyResponse(403, "Forbidden", List.of());
		}

		Request sokletRequest = requestControl.publicRequest == null
				? toSokletRequest(request, httpMethod.orElseThrow())
				: requestControl.publicRequest;
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (httpMethod.orElseThrow() == HttpMethod.OPTIONS)
			return processPreflight(request, sokletRequest, endpointRuntime);

		CorsAuthorization corsAuthorization = authorizeCors(request, sokletRequest,
				httpMethod.orElseThrow(), endpointPolicy);
		if (corsAuthorization.rejection().isPresent())
			return corsAuthorization.rejection().orElseThrow();

		List<Header> authorizedCorsHeaders = corsAuthorization.response()
				.map(response -> corsHeaders(request, response))
				.orElseGet(List::of);
		// Provider selection policy is application-owned and opaque, so every
		// non-preflight response from a localization-enabled endpoint varies by
		// Accept-Language - success, sanitized error, rejection, deadline, and
		// subscription stream-opening alike.
		List<Header> corsHeaders = requestControl.decorateResponseHeaders(
				authorizedCorsHeaders);
		if (!requestControl.updateDeadlineResponseHeaders(corsHeaders))
			return null;
		if (!requestControl.protocolProcessingAllowed())
			return null;

		if (httpMethod.orElseThrow() == HttpMethod.GET || httpMethod.orElseThrow() == HttpMethod.DELETE)
			return processLegacySessionHttp(request, sokletRequest, httpMethod.orElseThrow(), endpointRuntime,
					requestControl, application, corsHeaders);
		if (httpMethod.orElseThrow() != HttpMethod.POST)
			return methodNotAllowed(corsHeaders, legacyHttpMethods(endpointRuntime,
					singleHeader(request, MCP_PROTOCOL_VERSION).orElse(null)));

		MicrohttpResponse contentNegotiationFailure = contentNegotiationFailure(request,
				corsHeaders);
		if (contentNegotiationFailure != null)
			return contentNegotiationFailure;

		McpJsonRpcEnvelope envelope;
		try {
			envelope = envelopeCodec.decode(request.body());
		} catch (McpWireDecodingException exception) {
			return wireDecodingFailure(endpointBinding, exception,
					exception.readableMethod().orElse(null), corsHeaders);
		}
		McpLegacyHttpWire.Era wireEra = McpLegacyHttpWire.classify(envelope,
				headerValues(request, MCP_PROTOCOL_VERSION),
				headerValues(request, MCP_METHOD), headerValues(request, MCP_NAME),
				request.headers().stream().anyMatch(header -> header.name()
						.regionMatches(true, 0, "Mcp-Param-", 0, 10)),
				this.mirroredHeaderCodec);

		if (envelope instanceof McpJsonRpcEnvelope.Notification notification)
			return processNotification(request, sokletRequest, notification,
					corsHeaders, requestControl, endpointRuntime, wireEra);

		if (!(envelope instanceof McpJsonRpcEnvelope.Request wireRequest))
			return jsonRpcError(400, "Bad Request", Optional.empty(),
					new McpJsonRpcError(McpJsonRpcError.INVALID_REQUEST,
							"Invalid Request", Optional.empty()), corsHeaders);

		boolean legacy = wireEra == McpLegacyHttpWire.Era.LEGACY;
		String headerProtocolVersion = singleHeader(request, MCP_PROTOCOL_VERSION)
				.orElse(null);
		boolean validatedUnsupportedSelector = !legacy
				&& (validatedUnsupportedSelector(request)
					|| headerProtocolVersion != null
					&& McpLegacyHttpWire.isLegacyRevision(headerProtocolVersion));
		boolean deferredToolMirroredHeaderValidation =
				!legacy && endpointBinding.endpoint().catalogAccessAdapter().isPresent()
						&& "tools/call".equals(wireRequest.method());
		if (!legacy) {
			MicrohttpResponse initialMirroredHeaderFailure =
					validateRequiredMirroredHeaders(request, wireRequest,
							validatedUnsupportedSelector, corsHeaders,
							!deferredToolMirroredHeaderValidation);
			if (initialMirroredHeaderFailure != null)
				return initialMirroredHeaderFailure;
		}
		if (!legacy && !deferredToolMirroredHeaderValidation) {
			// The modern header checks and custom-header policy precede version
			// selection. Use the exact modern view when supported, otherwise the
			// modern endpoint view for deterministic unsupported-selector errors.
			String validationRevision = headerProtocolVersion != null
					&& !McpLegacyHttpWire.isLegacyRevision(headerProtocolVersion)
					&& endpointBinding.revisionEndpoint(headerProtocolVersion).isPresent()
					? headerProtocolVersion
					: endpointBinding.revisionEndpoint(McpProtocolVersion.CURRENT).isPresent()
						? McpProtocolVersion.CURRENT
						: endpointBinding.supportedRevisions().iterator().next();
			McpCustomMirroredHeaderValidation customHeaderValidation =
					customMirroredHeaderValidator.validate(request.headers(), wireRequest,
							endpointRuntime.capabilityRegistry(validationRevision),
							endpointPolicy.unknownMirroredHeaderPolicy(),
							this.unknownMirroredHeaderNameDiagnostics.enabled());
			recordUnknownMirroredHeaders(endpointRuntime.path(), wireRequest.method(),
					customHeaderValidation.unknownHeaderCount());
			for (String unknownHeaderName : customHeaderValidation.unknownHeaderNames())
				this.unknownMirroredHeaderNameDiagnostics.observe(
						endpointRuntime.path(), unknownHeaderName);
			if (customHeaderValidation.outcome()
					== McpCustomMirroredHeaderOutcome.HEADER_MISMATCH)
				return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
						validatedUnsupportedSelector, corsHeaders);
			if (customHeaderValidation.outcome()
					== McpCustomMirroredHeaderOutcome.STRICT_UNKNOWN)
				return strictUnknownMirroredHeader(endpointBinding, wireRequest.id(), wireRequest.method(),
						validatedUnsupportedSelector, corsHeaders);
		}

		String selectedRevision;
		if (legacy && "initialize".equals(wireRequest.method())) {
			if (!(wireRequest.params().orElse(null) instanceof McpJsonObject params))
				return wireDecodingFailure(endpointBinding, McpWireDecodingException.invalidParams(
						"Initialize params must be an object.", wireRequest.id()),
						"initialize", corsHeaders);
			McpLegacyRequestWireMapper.Initialization initialization;
			try {
				initialization = McpLegacyRequestWireMapper.parseInitialization(
						params, wireRequest);
			} catch (McpWireDecodingException exception) {
				return wireDecodingFailure(endpointBinding, exception, "initialize", corsHeaders);
			}
			if (headerProtocolVersion != null
					&& !headerProtocolVersion.equals(initialization.requestedRevision()))
				return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
						corsHeaders);
			selectedRevision = selectLegacyInitializationRevision(
						endpointBinding, initialization.requestedRevision());
			if (selectedRevision == null)
				return jsonRpcError(400, "Bad Request",
						Optional.of(wireRequest.id()),
						McpJsonRpcError.unsupportedProtocolVersion(
								initialization.requestedRevision(),
								this.protocolProfiles.revisions().stream()
										.filter(endpointBinding.supportedRevisions()::contains)
										.toList()),
						corsHeaders);
		} else if (legacy) {
			if (headerProtocolVersion == null
					|| !McpLegacyHttpWire.isLegacyRevision(headerProtocolVersion))
				return emptyResponse(400, "Bad Request", corsHeaders);
			selectedRevision = headerProtocolVersion;
		} else {
			if (headerProtocolVersion == null)
				return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
						corsHeaders);
			selectedRevision = headerProtocolVersion;
		}
		Optional<McpProtocolProfile> selectedProfile =
				this.protocolProfiles.resolve(selectedRevision);
		if (selectedProfile.isEmpty()
				|| endpointBinding.revisionEndpoint(selectedRevision).isEmpty()
				|| !legacy && McpLegacyHttpWire.isLegacyRevision(selectedRevision)) {
			Optional<String> readableBodyProtocolVersion =
					readableBodyProtocolVersion(wireRequest);
			if (!legacy && readableBodyProtocolVersion.isPresent()
					&& !selectedRevision.equals(readableBodyProtocolVersion.orElseThrow()))
				return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(), true,
						corsHeaders);
			return jsonRpcError(400, "Bad Request", Optional.of(wireRequest.id()),
					McpJsonRpcError.unsupportedProtocolVersion(selectedRevision,
							this.protocolProfiles.revisions().stream()
								.filter(endpointBinding.supportedRevisions()::contains)
								.toList()), corsHeaders);
		}
		McpProtocolProfile protocolProfile = selectedProfile.orElseThrow();
		McpNormalizedEndpoint endpoint = endpointBinding.revisionEndpoint(
				selectedRevision).orElseThrow();
		McpServerCapabilityRegistry capabilityRegistry =
				endpointRuntime.capabilityRegistry(selectedRevision);
		McpApplicationRequestRouter applicationRouter =
				endpointRuntime.resourceRoutersByRevision().get(selectedRevision);

		requestControl.bindProtocolProfile(protocolProfile);
		boolean sessionEnabled = legacy && sessionsEnabled(endpointRuntime.path(), selectedRevision);
		requestControl.legacySessionSelected = sessionEnabled;
		if (sessionEnabled && !validLegacySessionFraming(request,
				"initialize".equals(wireRequest.method())))
			return emptyResponse(400, "Bad Request", corsHeaders);

		McpJsonRpcMessage.Request mappedRequest;
		try {
			mappedRequest = protocolProfile
					.mapRequest(this.requestWireMapper, wireRequest);
		} catch (McpWireDecodingException exception) {
			return wireDecodingFailure(endpointBinding, protocolProfile, exception,
					wireRequest.method(), corsHeaders);
		}

		if (!selectedRevision.equals(mappedRequest.params().metadata().protocolVersion()))
			return headerMismatch(endpointBinding, mappedRequest.id(), mappedRequest.method(), corsHeaders);
		if (legacy && !McpLegacyHttpWire.supportsRequestMethod(mappedRequest.method()))
			return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);

		String requestedProtocolVersion = protocolProfile.revision();
		McpJsonObject requestMessageMetadata = legacy
				? wireRequest.params().filter(McpJsonObject.class::isInstance).map(McpJsonObject.class::cast)
						.map(params -> params.members().get("_meta")).filter(McpJsonObject.class::isInstance)
						.map(McpJsonObject.class::cast).orElseGet(McpJsonObject::empty)
				: mappedRequest.params().metadata().toJsonObject();

		if (!requestControl.identifyRequestExchange())
			return null;

		boolean initializeRequest = legacy && "initialize".equals(mappedRequest.method());
		boolean pingRequest = legacy && "ping".equals(mappedRequest.method());
		boolean discoveryRequest = "server/discover".equals(mappedRequest.method());
		boolean toolsListRequest = "tools/list".equals(mappedRequest.method());
		boolean promptsListRequest = "prompts/list".equals(mappedRequest.method());
		boolean resourcesListRequest = "resources/list".equals(mappedRequest.method());
		boolean resourceTemplatesListRequest =
				"resources/templates/list".equals(mappedRequest.method());
		boolean subscriptionListenRequest =
				"subscriptions/listen".equals(mappedRequest.method());
		boolean legacyResourceSubscribe = legacy && "resources/subscribe".equals(mappedRequest.method());
		boolean legacyResourceUnsubscribe = legacy && "resources/unsubscribe".equals(mappedRequest.method());
		boolean completionRequestMethod =
				"completion/complete".equals(mappedRequest.method());
		boolean taskRequest = isTaskRequestMethod(mappedRequest.method());
		boolean callerAwareCatalog = endpoint.catalogAccessAdapter().isPresent();
		boolean pagedLegacyCatalog = legacy
				&& McpLegacyCatalogPager.Kind.forMethod(mappedRequest.method()).isPresent()
				&& !(resourcesListRequest && endpoint.customResourceListHandler());
		Optional<String> operationName = Optional.empty();
		Optional<CompletionRequest> completionRequest = Optional.empty();
		Optional<McpApplicationToolRoute> toolRoute = Optional.empty();
		Optional<McpApplicationPromptRoute> promptRoute = Optional.empty();
		Optional<McpApplicationRequestHandler> applicationHandler = Optional.empty();
		McpInputRequestPlan inputRequestPlan = McpInputRequestPlan.empty();
		McpRequestStateMode requestStateMode = McpRequestStateMode.NONE;
		boolean taskRequired = false;
		McpJsonObject inputResponses = McpJsonObject.empty();
		boolean inputResponsesSupplied = false;
		Optional<String> suppliedRequestState = Optional.empty();
		Optional<AcceptedSubscriptionFilter> acceptedSubscriptionFilter =
				Optional.empty();
		Optional<CatalogAccessSession> catalogAccessSession = Optional.empty();
		Set<String> accessibleToolNames = Set.of();
		Set<String> accessiblePromptNames = Set.of();
		AtomicReference<@Nullable String> catalogSelectedLocaleSlot =
				new AtomicReference<>();

		if (initializeRequest) {
			// The 2025 mapper has already validated required initialization fields.
			if (!mappedRequest.params().fields().members().keySet().containsAll(
					Set.of("protocolVersion", "capabilities", "clientInfo")))
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if (pingRequest) {
			if (!mappedRequest.params().fields().members().isEmpty())
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if (discoveryRequest) {
			if (!mappedRequest.params().fields().members().isEmpty())
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if (toolsListRequest) {
			if (capabilityRegistry.tools().isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			if (!validFrameworkCatalogParams(mappedRequest.params().fields(), legacy))
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if (promptsListRequest) {
			if (capabilityRegistry.prompts().isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			if (!validFrameworkCatalogParams(mappedRequest.params().fields(), legacy))
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if ("skills/list".equals(mappedRequest.method())
				|| "skills/get".equals(mappedRequest.method())) {
			if (endpoint.skillsPlan().isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			McpServerRuntimeBridge.SkillsPlan skillsPlan = endpoint.skillsPlan().orElseThrow();
			Map<String, McpJsonValue> fields = mappedRequest.params().fields().members();
			if ("skills/list".equals(mappedRequest.method())) {
				if (!Set.of("cursor").containsAll(fields.keySet())
						|| !skillsPlan.customListHandler() && fields.containsKey("cursor"))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				if (fields.containsKey("cursor")
						&& (!(fields.get("cursor") instanceof McpJsonString cursor)
						|| !McpCursorValidator.fitsWithinUtf8ByteLimit(cursor.value(), skillsPlan.maximumCursorSizeInBytes())))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			} else {
				if (!fields.keySet().equals(Set.of("uri"))
						|| !(fields.get("uri") instanceof McpJsonString uri))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				try {
					operationName = Optional.of(McpLevelOneUriTemplate.requireValidAbsoluteUri(uri.value(), "Skills URI"));
				} catch (IllegalArgumentException ignored) {
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				}
			}
			applicationHandler = applicationRouter.resolve(mappedRequest.method());
			if (applicationHandler.isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
		} else if (resourcesListRequest) {
			if (capabilityRegistry.capabilities().resources().isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			if (endpoint.customResourceListHandler()) {
				Map<String, McpJsonValue> fields =
						mappedRequest.params().fields().members();
				if (!Set.of("cursor").containsAll(fields.keySet()))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				Optional<String> cursor = Optional.empty();
				if (fields.containsKey("cursor")) {
					McpJsonValue cursorValue = fields.get("cursor");
					if (!(cursorValue instanceof McpJsonString string)
							|| !McpCursorValidator.fitsWithinUtf8ByteLimit(
									string.value(), endpoint.maximumCursorSizeInBytes()))
						return invalidParams(protocolProfile, mappedRequest, corsHeaders);
					cursor = Optional.of(string.value());
				}
				Optional<McpApplicationResourceListRoute> listRoute =
						applicationRouter.resourceListRoute();
				if (listRoute.isEmpty())
					return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
				Optional<String> resolvedCursor = cursor;
				McpApplicationResourceListRoute resolvedRoute = listRoute.orElseThrow();
				applicationHandler = Optional.of(invocation -> resourceResultWithCachePolicy(
							resolvedRoute.handler().handle(
									new McpApplicationResourceListInvocation(invocation,
											resolvedCursor,
											capabilityRegistry.exactResourceDescriptors(),
											endpoint.resourceListCachePolicy())),
							endpoint.resourceListCachePolicy(), true, false,
							endpointPolicy.localizationEnabled(),
							endpoint.maximumCursorSizeInBytes(), endpointPolicy.path(),
							applicationRouter));
			} else {
				if (!validFrameworkCatalogParams(mappedRequest.params().fields(), legacy))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
		} else if (resourceTemplatesListRequest) {
			if (capabilityRegistry.capabilities().resources().isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			if (!validFrameworkCatalogParams(mappedRequest.params().fields(), legacy))
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if (legacyResourceSubscribe || legacyResourceUnsubscribe) {
			if (!sessionEnabled || !legacyTransportFamilies.getOrDefault(endpointRuntime.path(), Map.of())
					.getOrDefault(selectedRevision, Set.of()).contains(McpResourceNotificationType.RESOURCE_UPDATED))
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			Map<String, McpJsonValue> fields = mappedRequest.params().fields().members();
			if (!fields.keySet().equals(Set.of("uri")) || !(fields.get("uri") instanceof McpJsonString uri))
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			try {
				String validatedUri = McpLevelOneUriTemplate.requireValidAbsoluteUri(uri.value(), "Resource subscription URI");
				operationName = Optional.of(validatedUri);
				if (legacyResourceSubscribe)
					acceptedSubscriptionFilter = Optional.of(new AcceptedSubscriptionFilter(false, false, false,
							true, Map.of(URI.create(validatedUri), new SubscriptionResource(URI.create(validatedUri), validatedUri)),
							false, List.of(), Set.of(), mappedRequest.params().metadata().clientCapabilities()));
			} catch (IllegalArgumentException exception) {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
		} else if (subscriptionListenRequest) {
			if (endpoint.subscriptionConfig().isEmpty()
					|| endpointBinding.subscriptionEventSources().isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			try {
				acceptedSubscriptionFilter = Optional.of(
						parseAcceptedSubscriptionFilter(mappedRequest,
								endpoint.subscriptionConfig().orElseThrow(),
								endpointPolicy.catalogLocalizer()
										.map(McpRuntimeCatalogLocalizer
												::localizedResponseKinds)
										.orElseGet(Set::of),
								!capabilityRegistry.tools().isEmpty(),
								!capabilityRegistry.prompts().isEmpty()));
			} catch (IllegalArgumentException exception) {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
			if (acceptedSubscriptionFilter.orElseThrow().taskIdsRequested()
					&& !mappedRequest.params().metadata().clientCapabilities()
							.extensions().containsKey(TASKS_EXTENSION_IDENTIFIER))
				return missingTasksCapability(protocolProfile, mappedRequest.id(),
						corsHeaders);
		} else if (completionRequestMethod) {
			if (!capabilityRegistry.capabilities().completions())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			completionRequest = parseCompletionRequest(
					mappedRequest.params().fields().members());
			if (completionRequest.isEmpty())
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			operationName = Optional.of(completionRequest.orElseThrow().reference());
		} else if (taskRequest) {
			Optional<McpApplicationRequestHandler> taskHandler =
					applicationRouter.resolve(mappedRequest.method());
			boolean tasksSupported = capabilityRegistry.capabilities().extensions()
					.containsKey(TASKS_EXTENSION_IDENTIFIER)
					&& taskHandler.isPresent();
			if (!tasksSupported)
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			if (!mappedRequest.params().metadata().clientCapabilities().extensions()
					.containsKey(TASKS_EXTENSION_IDENTIFIER))
				return missingTasksCapability(protocolProfile, mappedRequest.id(),
						corsHeaders);

			Map<String, McpJsonValue> fields =
					mappedRequest.params().fields().members();
			McpJsonValue taskIdValue = fields.get("taskId");
			if (!(taskIdValue instanceof McpJsonString taskId)
					|| !validTaskId(taskId.value()))
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			operationName = Optional.of(taskId.value());

			if ("tasks/update".equals(mappedRequest.method())) {
				try {
					Optional<McpJsonObject> parsedInputResponses =
							parseTaskInputResponses(fields);
					if (parsedInputResponses.isEmpty())
						return invalidParams(protocolProfile, mappedRequest,
								corsHeaders);
					inputResponses = parsedInputResponses.orElseThrow();
				} catch (IllegalArgumentException exception) {
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				}
			}
			applicationHandler = taskHandler;
		} else if ("tools/call".equals(mappedRequest.method())) {
			Map<String, McpJsonValue> fields =
					mappedRequest.params().fields().members();
			try {
				Optional<McpJsonObject> parsedInputResponses =
						parseInputResponses(fields);
				inputResponses = parsedInputResponses.orElseGet(McpJsonObject::empty);
				inputResponsesSupplied = parsedInputResponses.isPresent();
			} catch (IllegalArgumentException exception) {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
			McpJsonValue nameValue = fields.get("name");
			if (!(nameValue instanceof McpJsonString name) || name.value().isBlank())
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			operationName = Optional.of(name.value());
			if (!callerAwareCatalog) {
				McpJsonValue argumentsValue = fields.get("arguments");
				if (argumentsValue != null
						&& !(argumentsValue instanceof McpJsonObject))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				if ((applicationRouter.hasToolRoutes()
						|| !capabilityRegistry.tools().isEmpty())
						&& !capabilityRegistry.tools().contains(name.value()))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);

				toolRoute = applicationRouter.resolveTool(name.value());
				if (applicationRouter.hasToolRoutes()) {
					if (toolRoute.isEmpty())
						return invalidParams(protocolProfile, mappedRequest, corsHeaders);
					McpApplicationToolRoute resolvedRoute = toolRoute.orElseThrow();
					applicationHandler = Optional.of(resolvedRoute.handler());
					inputRequestPlan = resolvedRoute.inputRequestPlan();
					requestStateMode = resolvedRoute.requestStateMode();
					taskRequired = resolvedRoute.taskRequired();
				} else {
					// Retain the package-private generic method route for existing runtime
					// tests while production registrations use exact immutable tool routes.
					applicationHandler = applicationRouter.resolve(mappedRequest.method());
				}
				if (applicationHandler.isEmpty())
					return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			}
		} else if ("prompts/get".equals(mappedRequest.method())) {
			Optional<McpApplicationRequestHandler> genericPromptHandler =
					applicationRouter.resolve(mappedRequest.method());
			if (capabilityRegistry.prompts().isEmpty()
					&& genericPromptHandler.isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);

			Map<String, McpJsonValue> fields =
					mappedRequest.params().fields().members();
			try {
				Optional<McpJsonObject> parsedInputResponses =
						parseInputResponses(fields);
				inputResponses = parsedInputResponses.orElseGet(McpJsonObject::empty);
				inputResponsesSupplied = parsedInputResponses.isPresent();
			} catch (IllegalArgumentException exception) {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
			McpJsonValue nameValue = fields.get("name");
			if (!(nameValue instanceof McpJsonString name) || name.value().isBlank())
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			operationName = Optional.of(name.value());

			if (!callerAwareCatalog) {
				McpJsonValue argumentsValue = fields.get("arguments");
			if (capabilityRegistry.prompts().isEmpty()
					&& !applicationRouter.hasPromptRoutes()) {
				// Preserve the package-private generic method seam used by transport
				// tests while still enforcing the final wire's string-value shape.
				if (!validPromptArgumentValues(argumentsValue))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			} else {
				Optional<McpNormalizedPromptDescriptor> promptDescriptor =
						capabilityRegistry.promptDescriptor(name.value());
				if (promptDescriptor.isEmpty()
						|| !validPromptArguments(promptDescriptor.orElseThrow(),
								argumentsValue))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}

			promptRoute = applicationRouter.resolvePrompt(name.value());
			if (applicationRouter.hasPromptRoutes()) {
				if (promptRoute.isEmpty())
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				McpApplicationPromptRoute resolvedRoute = promptRoute.orElseThrow();
				applicationHandler = Optional.of(resolvedRoute.handler());
				inputRequestPlan = resolvedRoute.inputRequestPlan();
				requestStateMode = resolvedRoute.requestStateMode();
			} else {
				// Retain the package-private generic method route for existing runtime
				// tests while production registrations use exact immutable prompt routes.
				applicationHandler = genericPromptHandler;
			}
			if (applicationHandler.isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			}
		} else if ("resources/read".equals(mappedRequest.method())) {
			Optional<McpApplicationRequestHandler> genericResourceHandler =
					applicationRouter.resolve(mappedRequest.method());
			if (legacy && capabilityRegistry.capabilities().resources().isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			if (capabilityRegistry.capabilities().resources().isEmpty()
					&& genericResourceHandler.isEmpty()
					&& !applicationRouter.hasResourceReadRoutes())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);

			Map<String, McpJsonValue> fields = mappedRequest.params().fields().members();
			try {
				Optional<McpJsonObject> parsedInputResponses =
						parseInputResponses(fields);
				inputResponses = parsedInputResponses.orElseGet(McpJsonObject::empty);
				inputResponsesSupplied = parsedInputResponses.isPresent();
			} catch (IllegalArgumentException exception) {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
			boolean resourceRetry = inputResponsesSupplied
					|| fields.containsKey("requestState");
			McpJsonValue uriValue = fields.get("uri");
			if (!(uriValue instanceof McpJsonString uriString))
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			String uri;
			try {
				uri = McpLevelOneUriTemplate.requireValidAbsoluteUri(
						uriString.value(), "Resource URI");
			} catch (IllegalArgumentException exception) {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
			operationName = Optional.of(uri);

			if (applicationRouter.hasResourceReadRoutes()) {
				Optional<McpApplicationResourceReadRoute> exactRoute =
						applicationRouter.resolveExactResource(uri);
				McpApplicationResourceReadRoute resolvedRoute;
				Map<String, String> templateVariables;
				boolean skillRead = endpoint.isSkillFile(URI.create(uri));
				if (exactRoute.isPresent()) {
					// Exact registration deliberately wins over a matching template.
					resolvedRoute = exactRoute.orElseThrow();
					templateVariables = Map.of();
				} else {
					Optional<McpApplicationResourceTemplateMatch> templateMatch;
					try {
						templateMatch = applicationRouter.resolveResourceTemplate(uri);
					} catch (IllegalArgumentException | IllegalStateException exception) {
						return invalidParams(protocolProfile, mappedRequest, corsHeaders);
					}
					if (templateMatch.isEmpty()) {
						if (endpoint.skillsPlan().isEmpty() || genericResourceHandler.isEmpty())
							return invalidResourceUriParams(protocolProfile, mappedRequest, uri, corsHeaders);
						McpApplicationRequestHandler unavailable = genericResourceHandler.orElseThrow();
						resolvedRoute = new McpApplicationResourceReadRoute(
								invocation -> unavailable.handle(invocation.invocation()),
								McpResourceCachePolicy.privateNoCache(), McpInputRequestPlan.empty(), McpRequestStateMode.NONE);
						templateVariables = Map.of();
						skillRead = true;
					} else {
						McpApplicationResourceTemplateMatch match = templateMatch.orElseThrow();
						resolvedRoute = match.readRoute();
						templateVariables = match.templateVariables();
					}
				}
				if (skillRead && !fields.keySet().equals(Set.of("uri")))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				McpApplicationResourceReadRoute route = resolvedRoute;
				inputRequestPlan = route.inputRequestPlan();
				requestStateMode = route.requestStateMode();
				Map<String, String> variables = templateVariables;
				applicationHandler = Optional.of(invocation -> resourceResultWithCachePolicy(
						route.handler().handle(new McpApplicationResourceReadInvocation(
								invocation, uri, variables, route.cachePolicy())),
						route.cachePolicy(), false,
						resourceRetry,
						endpointPolicy.localizationEnabled(),
						endpoint.maximumCursorSizeInBytes(), endpointPolicy.path(),
						applicationRouter));
			} else {
				// Preserve the generic package-private seam used by transport tests.
				if (endpoint.skillsPlan().isPresent() && !fields.keySet().equals(Set.of("uri")))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				applicationHandler = genericResourceHandler;
			}
			if (applicationHandler.isEmpty()) {
				if (capabilityRegistry.capabilities().resources().isPresent())
					return invalidResourceUriParams(protocolProfile, mappedRequest, uri, corsHeaders);
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
			}
		} else if (mappedRequest.method().startsWith("tasks/")
				|| mappedRequest.method().startsWith("skills/")) {
			// These framework extensions own their complete method namespaces.
			// Unknown methods never fall through to an application route.
			return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
		} else {
			applicationHandler = applicationRouter.resolve(mappedRequest.method());
			if (applicationHandler.isEmpty())
				return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
		}

		boolean completionPromptRequest = completionRequest
				.map(value -> value.promptReference()).orElse(false);
		boolean appToolCall = "tools/call".equals(mappedRequest.method())
				&& operationName.map(capabilityRegistry::hasAppTool).orElse(false);
		boolean deferredCatalogDirectRequest = callerAwareCatalog
				&& ("tools/call".equals(mappedRequest.method())
						|| "prompts/get".equals(mappedRequest.method()));
		if (McpWireResult.supportsInputRequired(mappedRequest.method())
				&& !deferredCatalogDirectRequest) {
			try {
				suppliedRequestState = parseRequestState(
						mappedRequest.params().fields().members(),
						requestStateMode);
			} catch (McpInvalidRequestStateException
					| IllegalArgumentException exception) {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			} catch (McpRequestStateUnavailableException exception) {
				return requestStateUnavailable(protocolProfile, mappedRequest.id(), corsHeaders);
			} catch (Throwable throwable) {
				return policyHookInternalError(protocolProfile, mappedRequest.id(), corsHeaders);
			}
		}

		Set<McpClientCapabilityRequirement> missingCapabilities =
				new LinkedHashSet<>(inputRequestPlan.missingAtAdmission(
						mappedRequest.params().metadata().clientCapabilities()));
		if (taskRequired && !mappedRequest.params().metadata().clientCapabilities()
				.extensions().containsKey(TASKS_EXTENSION_IDENTIFIER))
			missingCapabilities.add(new McpExtensionClientCapability(
					TASKS_EXTENSION_IDENTIFIER));
		if (!missingCapabilities.isEmpty() && !appToolCall)
			return profiledJsonRpcError(protocolProfile,
					McpProfileErrorKind.OPERATION, 400, "Bad Request",
					Optional.of(mappedRequest.id()),
					McpJsonRpcError.missingRequiredClientCapabilities(
							missingCapabilities), corsHeaders);

		if (!requestControl.protocolProcessingAllowed())
			return null;
		McpAdmissionContext admissionContext = new McpAdmissionContext(
				sokletRequest, endpoint, Map.of(), mappedRequest.method(), false,
				Optional.of(mappedRequest.id()), requestedProtocolVersion,
				operationName, mappedRequest.params().metadata().clientInformation(),
				legacy && !initializeRequest ? Optional.empty()
						: Optional.of(mappedRequest.params().metadata().clientCapabilities()),
				acceptedSubscriptionFilter.map(AcceptedSubscriptionFilter
						::toolsListChanged).orElse(false),
				acceptedSubscriptionFilter.map(AcceptedSubscriptionFilter
						::promptsListChanged).orElse(false),
				acceptedSubscriptionFilter.map(AcceptedSubscriptionFilter
						::resourcesListChanged).orElse(false),
				acceptedSubscriptionFilter.map(AcceptedSubscriptionFilter
						::resourceSubscriptionsIncluded).orElse(false),
				acceptedSubscriptionFilter
						.map(AcceptedSubscriptionFilter
								::requestedResourceSubscriptionUris)
						.orElseGet(List::of),
				acceptedSubscriptionFilter.map(AcceptedSubscriptionFilter
						::taskIdsRequested).orElse(false),
				acceptedSubscriptionFilter.map(AcceptedSubscriptionFilter
						::requestedTaskIds).orElseGet(List::of),
				Optional.of(requestMessageMetadata));
		Optional<McpAdmissionDecision> admissionResult;
		try {
			admissionResult = Optional.ofNullable(
					endpointPolicy.protocolAdmissionController().admit(admissionContext));
		} catch (Throwable throwable) {
			return policyHookInternalError(protocolProfile, mappedRequest.id(), corsHeaders);
		}
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (admissionResult.isEmpty())
			return policyHookInternalError(protocolProfile, mappedRequest.id(), corsHeaders);
		McpAdmissionDecision admissionDecision = admissionResult.orElseThrow();

		if (admissionDecision instanceof McpAdmissionDecision.Rejected rejected) {
			try {
				requestControl.legacyAdmissionRejected = sessionEnabled;
				return remapSessionAdmissionRejection(admissionRejection(mappedRequest.id(),
						rejected.rejection(), corsHeaders), sessionEnabled);
			} catch (IllegalArgumentException exception) {
				return policyHookInternalError(protocolProfile, mappedRequest.id(), corsHeaders);
			}
		}
		McpAdmissionIdentity admittedIdentity =
				((McpAdmissionDecision.Accepted) admissionDecision).identity();
		McpEffectiveAdmissionIdentity effectiveIdentity =
				McpEffectiveAdmissionIdentity.resolve(endpoint, endpointPolicy.path(),
						admittedIdentity);
		Optional<McpRuntimeRequestState> requestState = Optional.empty();
		Optional<McpFrameworkRequestStateContinuation>
				frameworkRequestStateContinuation = Optional.empty();
		if (suppliedRequestState.isPresent()
				&& requestStateMode == McpRequestStateMode.APPLICATION_PROTECTED)
			requestState = Optional.of(new McpRuntimeApplicationRequestState(
					suppliedRequestState.orElseThrow()));

		boolean requestLimiterConfigured =
				endpointPolicy.requestRateLimiter().isPresent();
		McpRateLimitDecision requestRateLimitDecision = null;
		Throwable requestRateLimitFailure = null;
		if (requestLimiterConfigured) {
			try {
				requestRateLimitDecision = endpointPolicy.requestRateLimiter()
						.orElseThrow().acquire(new McpRateLimitContext(
								sokletRequest, endpoint, requestedProtocolVersion,
								effectiveIdentity,
								McpRateLimitTarget.REQUEST, mappedRequest.method(),
								operationName));
			} catch (Throwable throwable) {
				requestRateLimitFailure = throwable;
			}
			if (!requestControl.protocolProcessingAllowed())
				return null;
		}

		boolean requestRateLimitAllowed = !requestLimiterConfigured
				|| requestRateLimitDecision instanceof McpRateLimitDecision.Allowed;
		boolean deferredCatalogDirectInvalidRoute = false;
		boolean deferredCatalogDirectMissingHandler = false;
		Throwable deferredCatalogRequestStateFailure = null;
		if (requestRateLimitAllowed && deferredCatalogDirectRequest) {
			// Resolve only the neutral route identity after the request limiter.  We
			// need its state mode to recover a verified continuation locale before the
			// one request/localization context is created, but all failures remain
			// latent until caller-aware access and (for tools) the tool limiter have
			// run.  Consequently an inaccessible known name cannot expose any
			// registration-specific request-state behavior.
			if ("tools/call".equals(mappedRequest.method())) {
				String name = operationName.orElseThrow();
				toolRoute = capabilityRegistry.tools().contains(name)
						? applicationRouter.resolveTool(name) : Optional.empty();
				if ((!capabilityRegistry.tools().isEmpty()
						&& !capabilityRegistry.tools().contains(name))
						|| applicationRouter.hasToolRoutes() && toolRoute.isEmpty()) {
					deferredCatalogDirectInvalidRoute = true;
				} else if (toolRoute.isPresent()) {
					McpApplicationToolRoute resolvedRoute = toolRoute.orElseThrow();
					applicationHandler = Optional.of(resolvedRoute.handler());
					inputRequestPlan = resolvedRoute.inputRequestPlan();
					requestStateMode = resolvedRoute.requestStateMode();
					taskRequired = resolvedRoute.taskRequired();
				} else {
					applicationHandler = applicationRouter.resolve(
							mappedRequest.method());
				}
			} else {
				String name = operationName.orElseThrow();
				promptRoute = applicationRouter.resolvePrompt(name);
				if (applicationRouter.hasPromptRoutes() && promptRoute.isEmpty()) {
					deferredCatalogDirectInvalidRoute = true;
				} else if (promptRoute.isPresent()) {
					McpApplicationPromptRoute resolvedRoute = promptRoute.orElseThrow();
					applicationHandler = Optional.of(resolvedRoute.handler());
					inputRequestPlan = resolvedRoute.inputRequestPlan();
					requestStateMode = resolvedRoute.requestStateMode();
				} else {
					applicationHandler = applicationRouter.resolve(
							mappedRequest.method());
				}
			}
			deferredCatalogDirectMissingHandler =
					!deferredCatalogDirectInvalidRoute && applicationHandler.isEmpty();

			if (!deferredCatalogDirectInvalidRoute
					&& !deferredCatalogDirectMissingHandler) {
				try {
					suppliedRequestState = parseRequestState(
							mappedRequest.params().fields().members(),
							requestStateMode);
					if (suppliedRequestState.isPresent()
							&& requestStateMode
									== McpRequestStateMode.APPLICATION_PROTECTED)
						requestState = Optional.of(
								new McpRuntimeApplicationRequestState(
										suppliedRequestState.orElseThrow()));
					if (suppliedRequestState.isPresent()
							&& requestStateMode
									== McpRequestStateMode.FRAMEWORK_PROTECTED) {
						McpFrameworkRequestStateRuntime.OpenedState openedState =
								requestStateRuntime.open(endpointPolicy.path(),
										requestedProtocolVersion,
										mappedRequest.method(),
										effectiveIdentity.authorizationPartition()
												.applicationKey(),
										mappedRequest.params().toJsonObject(),
										mappedRequest.id(),
										suppliedRequestState.orElseThrow());
						requestState = Optional.of(
								new McpRuntimeFrameworkRequestState(
										openedState.state()));
						frameworkRequestStateContinuation = Optional.of(
								openedState.continuation());
					}
				} catch (Throwable throwable) {
					deferredCatalogRequestStateFailure = throwable;
					suppliedRequestState = Optional.empty();
					requestState = Optional.empty();
					frameworkRequestStateContinuation = Optional.empty();
				}
			}
		}
		boolean toolLimiterConfigured = requestRateLimitAllowed
				&& toolRoute.isPresent() && !deferredCatalogDirectRequest;
		McpRateLimitDecision toolRateLimitDecision = null;
		Throwable toolRateLimitFailure = null;
		if (toolLimiterConfigured) {
			try {
				toolRateLimitDecision = toolRoute.orElseThrow().rateLimiter().acquire(
						new McpRateLimitContext(sokletRequest, endpoint,
								requestedProtocolVersion,
								effectiveIdentity, McpRateLimitTarget.TOOL,
								mappedRequest.method(), operationName));
			} catch (Throwable throwable) {
				toolRateLimitFailure = throwable;
			}
			if (!requestControl.protocolProcessingAllowed())
				return null;
		}

		boolean toolRateLimitAllowed = !toolLimiterConfigured
				|| toolRateLimitDecision instanceof McpRateLimitDecision.Allowed;
		if (requestRateLimitAllowed && toolRateLimitAllowed
				&& !deferredCatalogDirectRequest
				&& suppliedRequestState.isPresent()
				&& requestStateMode == McpRequestStateMode.FRAMEWORK_PROTECTED) {
			String protectedState = suppliedRequestState.orElseThrow();
			McpFrameworkRequestStateRuntime.OpenedState openedState;
			try {
				openedState = requestStateRuntime.open(endpointPolicy.path(),
						requestedProtocolVersion, mappedRequest.method(),
						effectiveIdentity.authorizationPartition().applicationKey(),
						mappedRequest.params().toJsonObject(), mappedRequest.id(),
						protectedState);
			} catch (McpInvalidRequestStateException exception) {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			} catch (McpRequestStateUnavailableException exception) {
				return requestStateUnavailable(protocolProfile, mappedRequest.id(), corsHeaders);
			} catch (Throwable throwable) {
				return policyHookInternalError(protocolProfile, mappedRequest.id(), corsHeaders);
			}
			requestState = Optional.of(
					new McpRuntimeFrameworkRequestState(openedState.state()));
			frameworkRequestStateContinuation = Optional.of(
					openedState.continuation());
		}
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (sessionEnabled && requestRateLimitAllowed && toolRateLimitAllowed) {
			MicrohttpResponse sessionFailure = bindLegacySession(request, endpointRuntime.path(),
					selectedRevision, mappedRequest, admittedIdentity, initializeRequest,
					requestControl, application, corsHeaders);
			if (sessionFailure != null || !requestControl.protocolProcessingAllowed())
				return sessionFailure;
		}
		McpLegacySessionStore.Snapshot executionSnapshot = requestControl.legacySnapshot;
		McpJsonRpcMessage.Request executionRequest = requestControl.withLegacyExecutionMetadata(mappedRequest);
		if (executionSnapshot != null) {
			missingCapabilities.clear();
			missingCapabilities.addAll(inputRequestPlan.missingAtAdmission(executionSnapshot.clientCapabilities()));
		}
		if (!requestControl.startObservation(endpointBinding.observationSink(),
				new McpRuntimeRequestInput(sokletRequest, Map.of(),
						mappedRequest.method(), Optional.of(mappedRequest.id()),
						requestedProtocolVersion, operationName,
						executionSnapshot == null ? mappedRequest.params().metadata().clientInformation()
								: executionSnapshot.clientInformation(),
					(executionSnapshot == null ? mappedRequest.params().metadata().clientCapabilities()
							: executionSnapshot.clientCapabilities())
							.toJsonObject(),
					requestMessageMetadata,
					inputResponses,
					requestState,
					requestControl.acceptLanguageValues(),
					effectiveIdentity.admittedIdentity())))
			return null;

		if (requestRateLimitFailure != null)
			return observedPolicyHookInternalError(requestControl,
					mappedRequest.id(), corsHeaders, requestRateLimitFailure);
		if (requestLimiterConfigured && requestRateLimitDecision == null)
			return observedPolicyHookInternalError(requestControl,
					mappedRequest.id(), corsHeaders, null);
		if (requestRateLimitDecision instanceof McpRateLimitDecision.Denied denied)
			return observedRateLimited(requestControl, mappedRequest.id(),
					denied.retryAfter(), corsHeaders);

		if (toolRateLimitFailure != null)
			return observedPolicyHookInternalError(requestControl,
					mappedRequest.id(), corsHeaders, toolRateLimitFailure);
		if (toolLimiterConfigured && toolRateLimitDecision == null)
			return observedPolicyHookInternalError(requestControl,
					mappedRequest.id(), corsHeaders, null);
		if (toolRateLimitDecision instanceof McpRateLimitDecision.Denied denied)
			return observedRateLimited(requestControl, mappedRequest.id(),
					denied.retryAfter(), corsHeaders);
		if (completionPromptRequest && capabilityRegistry.promptDescriptor(
				operationName.orElseThrow()).isEmpty())
			return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		if (deferredCatalogDirectInvalidRoute)
			return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		if (deferredCatalogDirectMissingHandler)
			return methodNotFound(endpointBinding, protocolProfile, mappedRequest, corsHeaders);
		if (appToolCall && !callerAwareCatalog) {
			MicrohttpResponse appFailure = appToolCallFailure(capabilityRegistry,
					protocolProfile, executionRequest, operationName.orElseThrow(),
					corsHeaders);
			if (appFailure != null)
				return appFailure;
			if (!missingCapabilities.isEmpty())
				return profiledJsonRpcError(protocolProfile,
						McpProfileErrorKind.OPERATION, 400, "Bad Request",
						Optional.of(mappedRequest.id()),
						McpJsonRpcError.missingRequiredClientCapabilities(
								missingCapabilities), corsHeaders);
		}

		if (legacyResourceSubscribe || legacyResourceUnsubscribe)
			return processLegacyResourceSubscription(request, requestControl, endpointRuntime, applicationRouter,
					mappedRequest, operationName.orElseThrow(), effectiveIdentity.authorizationPartition(),
					legacyResourceSubscribe, corsHeaders);
		if (legacy && requestControl.legacyCall != null) {
			McpResourceNotificationType catalogFamily = legacyCatalogFamily(mappedRequest.method());
			if (catalogFamily != null) requireNonNull(legacySessionStore).rearmCatalog(requestControl.legacyCall, catalogFamily);
		}
		if (pagedLegacyCatalog)
			return pagedCatalogResponse(endpointRuntime, protocolProfile, mappedRequest,
					endpointPolicy, requestControl, corsHeaders, application);

		if (callerAwareCatalog) {
			boolean policyRequest = toolsListRequest || promptsListRequest
					|| deferredCatalogDirectRequest || completionPromptRequest;
			if (policyRequest) {
				McpRequestContext policyContext = requestControl.publicRequestContext()
						.orElse(null);
				if (policyContext == null)
					return observedPolicyHookInternalError(requestControl,
							mappedRequest.id(), corsHeaders, null);
				CatalogAccessAdapter accessAdapter = endpoint.catalogAccessAdapter()
						.orElseThrow();
				Optional<String> continuationLocale =
						frameworkRequestStateContinuation.flatMap(continuation ->
								Optional.ofNullable(continuation.selectedLocale()));
				Optional<String> policyOperationName = operationName;
				if (!requestControl.beginCatalogPolicyEvaluation())
					return null;
				try {
					CatalogAccessProjection projection = application.invokeBoundedPolicy(
							() -> {
								CatalogAccessSession session = requireNonNull(
										accessAdapter.open(new CatalogAccessInput(
										policyContext,
										requestControl.catalogAccessCancelationToken(),
										requestControl::isTerminalCanceledOrPastDeadline,
										requestControl.acceptLanguageValues(),
										continuationLocale,
												catalogSelectedLocaleSlot)),
										"The MCP catalog access adapter returned null.");
								Set<String> visibleTools = Set.of();
								Set<String> visiblePrompts = Set.of();
								boolean directAccessible = true;
								if (toolsListRequest) {
									LinkedHashSet<String> names = new LinkedHashSet<>();
									for (String candidate : capabilityRegistry.tools())
										if (session.isToolAccessible(candidate))
											names.add(candidate);
									visibleTools = Collections.unmodifiableSet(names);
								} else if (promptsListRequest) {
									LinkedHashSet<String> names = new LinkedHashSet<>();
									for (String candidate : capabilityRegistry.prompts())
										if (session.isPromptAccessible(candidate))
											names.add(candidate);
									visiblePrompts = Collections.unmodifiableSet(names);
								} else if ("tools/call".equals(mappedRequest.method())) {
									directAccessible = session.isToolAccessible(
											policyOperationName.orElseThrow());
								} else if ("prompts/get".equals(mappedRequest.method())
										|| completionPromptRequest) {
									directAccessible = session.isPromptAccessible(
											policyOperationName.orElseThrow());
								}
								return new CatalogAccessProjection(session, visibleTools,
										visiblePrompts, directAccessible);
							}, requestControl.deadlineNanos(),
							requestControl.catalogAccessCancellation,
							requestControl::releaseLegacyPhysicalIfComplete);
					requestControl.finishCatalogPolicyEvaluation();
					catalogAccessSession = Optional.of(projection.session());
					accessibleToolNames = projection.accessibleToolNames();
					accessiblePromptNames = projection.accessiblePromptNames();
					if (!projection.directAccessible())
						return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				} catch (McpApplicationPolicyCapacityException exception) {
					requestControl.finishCatalogPolicyEvaluation();
					if (!requestControl.protocolProcessingAllowed())
						return null;
					return observedPolicyCapacityRejected(requestControl,
							mappedRequest.id(), corsHeaders);
				} catch (McpApplicationPolicyDeadlineException exception) {
					if (!requestControl.reserveCatalogPolicyDeadlineResponse())
						return null;
					return observedPolicyDeadline(requestControl,
							mappedRequest.id(), corsHeaders, exception.queued());
				} catch (Throwable throwable) {
					requestControl.finishCatalogPolicyEvaluation();
					if (!requestControl.protocolProcessingAllowed())
						return null;
					return observedPolicyHookInternalError(requestControl,
							mappedRequest.id(), corsHeaders, throwable);
				}
				if (!requestControl.protocolProcessingAllowed())
					return null;
			}

			if ("tools/call".equals(mappedRequest.method())) {
				MicrohttpResponse appFailure = appToolCallFailure(capabilityRegistry,
						protocolProfile, executionRequest, operationName.orElseThrow(),
						corsHeaders);
				if (appFailure != null)
					return appFailure;
				McpApplicationToolRoute resolvedRoute = toolRoute.orElse(null);
				if (resolvedRoute != null) {
					Optional<McpRateLimitDecision> decision;
					try {
						decision = Optional.ofNullable(resolvedRoute.rateLimiter().acquire(
								new McpRateLimitContext(sokletRequest, endpoint,
										requestedProtocolVersion,
										effectiveIdentity, McpRateLimitTarget.TOOL,
										mappedRequest.method(), operationName)));
					} catch (Throwable throwable) {
						return observedPolicyHookInternalError(requestControl,
								mappedRequest.id(), corsHeaders, throwable);
					}
					if (!requestControl.protocolProcessingAllowed())
						return null;
					if (decision.isEmpty())
						return observedPolicyHookInternalError(requestControl,
								mappedRequest.id(), corsHeaders, null);
					if (decision.orElseThrow()
							instanceof McpRateLimitDecision.Denied denied)
						return observedRateLimited(requestControl, mappedRequest.id(),
								denied.retryAfter(), corsHeaders);
				}

				if (!legacy) {
					MicrohttpResponse mirroredHeaderFailure =
							validateRequiredMirroredHeaders(request, wireRequest,
									validatedUnsupportedSelector, corsHeaders);
					if (mirroredHeaderFailure != null)
						return mirroredHeaderFailure;
					McpCustomMirroredHeaderValidation customHeaderValidation =
							customMirroredHeaderValidator.validate(request.headers(),
									wireRequest, capabilityRegistry,
									endpointPolicy.unknownMirroredHeaderPolicy(),
									this.unknownMirroredHeaderNameDiagnostics.enabled());
					recordUnknownMirroredHeaders(endpointRuntime.path(), wireRequest.method(),
							customHeaderValidation.unknownHeaderCount());
					for (String unknownHeaderName
							: customHeaderValidation.unknownHeaderNames())
						this.unknownMirroredHeaderNameDiagnostics.observe(
								endpointRuntime.path(), unknownHeaderName);
					if (customHeaderValidation.outcome()
							== McpCustomMirroredHeaderOutcome.HEADER_MISMATCH)
						return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
								validatedUnsupportedSelector, corsHeaders);
					if (customHeaderValidation.outcome()
							== McpCustomMirroredHeaderOutcome.STRICT_UNKNOWN)
						return strictUnknownMirroredHeader(endpointBinding, wireRequest.id(),
								wireRequest.method(), validatedUnsupportedSelector,
								corsHeaders);
				}
				McpJsonValue argumentsValue = mappedRequest.params().fields()
						.members().get("arguments");
				if (argumentsValue != null
						&& !(argumentsValue instanceof McpJsonObject))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			} else if ("prompts/get".equals(mappedRequest.method())) {
				McpJsonValue argumentsValue = mappedRequest.params().fields()
						.members().get("arguments");
				Optional<McpNormalizedPromptDescriptor> promptDescriptor =
						capabilityRegistry.promptDescriptor(operationName.orElseThrow());
				if (promptDescriptor.isEmpty()
						|| !validPromptArguments(promptDescriptor.orElseThrow(),
								argumentsValue))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}

			if (deferredCatalogDirectRequest) {
				if (deferredCatalogRequestStateFailure
						instanceof McpInvalidRequestStateException
						|| deferredCatalogRequestStateFailure
								instanceof IllegalArgumentException)
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				if (deferredCatalogRequestStateFailure
						instanceof McpRequestStateUnavailableException)
					return requestStateUnavailable(protocolProfile,
							mappedRequest.id(), corsHeaders);
				if (deferredCatalogRequestStateFailure != null)
					return observedPolicyHookInternalError(requestControl,
							mappedRequest.id(), corsHeaders,
							deferredCatalogRequestStateFailure);
				Set<McpClientCapabilityRequirement> deferredMissingCapabilities =
						new LinkedHashSet<>(inputRequestPlan.missingAtAdmission(
								executionRequest.params().metadata().clientCapabilities()));
				if (taskRequired && !mappedRequest.params().metadata()
						.clientCapabilities().extensions()
						.containsKey(TASKS_EXTENSION_IDENTIFIER))
					deferredMissingCapabilities.add(new McpExtensionClientCapability(
							TASKS_EXTENSION_IDENTIFIER));
				if (!deferredMissingCapabilities.isEmpty())
					return profiledJsonRpcError(protocolProfile,
							McpProfileErrorKind.OPERATION, 400, "Bad Request",
							Optional.of(mappedRequest.id()),
							McpJsonRpcError.missingRequiredClientCapabilities(
									deferredMissingCapabilities), corsHeaders);
			}
		}

		if (subscriptionListenRequest) {
			SubscriptionCapReservation cap = requestControl.reserveSubscriptionCap(
					endpointRuntime.path(), endpoint,
					effectiveIdentity.authorizationPartition(), mappedRequest.id(),
					acceptedSubscriptionFilter.orElseThrow(), protocolProfile);
			if (cap.result() == SubscriptionOpenResult.CAPACITY_REJECTED)
				return observedSubscriptionCapacityRejected(requestControl,
						mappedRequest.id(), corsHeaders);
			if (cap.result() == SubscriptionOpenResult.SERVER_STOPPING) {
				requestControl.cancel(StreamTerminationReason.SERVER_STOPPING, null);
				return null;
			}
			if (cap.result() != SubscriptionOpenResult.OPENED)
				return null;

			SubscriptionRegistration capRegistration = requireNonNull(
					cap.registration());
			try {
				while (true) {
					SubscriptionAuthorizationResult authorization = requestControl
							.authorizeSubscriptionInitially(
									acceptedSubscriptionFilter.orElseThrow());
					if (authorization.disposition()
							!= SubscriptionAuthorizationDisposition.ALLOWED)
						return observedSubscriptionAuthorizationFailure(requestControl,
								mappedRequest.id(), corsHeaders, authorization);
					if (subscriptionRuntimeConfiguration.authorizer().isPresent()) {
						acceptedSubscriptionFilter = Optional.of(
								acceptedSubscriptionFilter.orElseThrow()
										.withAcceptedTaskIds(new ArrayList<>(
												authorization.acceptedTaskIds())));
					} else {
						try {
							acceptedSubscriptionFilter = Optional.of(
									authorizeTaskSubscriptions(endpointBinding,
											requestControl,
											acceptedSubscriptionFilter.orElseThrow()));
						} catch (Throwable throwable) {
							return observedPolicyHookInternalError(requestControl,
									mappedRequest.id(), corsHeaders, throwable);
						}
					}
					if (!requestControl.protocolProcessingAllowed())
						return null;
					SubscriptionRegistration updatedRegistration = requestControl
							.updateSubscriptionCapFilter(
									capRegistration,
									acceptedSubscriptionFilter.orElseThrow());
					if (updatedRegistration == null)
						return null;
					capRegistration = updatedRegistration;
					SubscriptionOpenResult openResult = requestControl.openSubscription(
							endpointRuntime.path(), endpoint,
							effectiveIdentity.authorizationPartition(), mappedRequest.id(),
							acceptedSubscriptionFilter.orElseThrow(), corsHeaders,
							endpointPolicy, protocolProfile, capRegistration);
					if (openResult == SubscriptionOpenResult.AUTHORIZATION_STALE
							|| openResult == SubscriptionOpenResult.CATALOG_STALE)
						continue;
					if (openResult == SubscriptionOpenResult.LOCALIZATION_FAILED
							|| openResult
								== SubscriptionOpenResult.CATALOG_PROJECTION_FAILED
							|| openResult
								== SubscriptionOpenResult.TERMINAL_PREFLIGHT_FAILED)
						return observedPolicyHookInternalError(requestControl,
								mappedRequest.id(), corsHeaders, null);
					if (openResult == SubscriptionOpenResult.SERVER_STOPPING)
						requestControl.cancel(StreamTerminationReason.SERVER_STOPPING,
								null);
					return null;
				}
			} finally {
				requestControl.releaseSubscriptionCapReservation(capRegistration);
			}
		}

		if (initializeRequest) {
			Map<String, McpJsonValue> fields = new LinkedHashMap<>();
			fields.put("protocolVersion", new McpJsonString(selectedRevision));
			Map<String, McpJsonValue> capabilities = new LinkedHashMap<>();
			capabilityRegistry.capabilities().tools().ifPresent(value ->
					capabilities.put("tools", value.toJsonObject()));
			capabilityRegistry.capabilities().prompts().ifPresent(value ->
					capabilities.put("prompts", value.toJsonObject()));
			capabilityRegistry.capabilities().resources().ifPresent(value ->
					capabilities.put("resources", value.toJsonObject()));
			if (capabilityRegistry.capabilities().completions())
				capabilities.put("completions", McpJsonObject.empty());
			fields.put("capabilities", new McpJsonObject(capabilities));
			fields.put("serverInfo", McpLegacyResponseWire
					.projectServerInformation(selectedRevision,
							endpoint.serverInformation()));
			endpoint.instructions().ifPresent(value ->
					fields.put("instructions", new McpJsonString(value)));
			McpWireResult result = McpWireResult.complete(new McpJsonObject(fields));
			MicrohttpResponse response = jsonResponse(200, "OK", McpLegacyResponseWire.encode(jsonCodec,
					new McpJsonRpcMessage.ResultResponse(mappedRequest.id(), result,
							McpJsonObject.empty())), corsHeaders);
			return requestControl.withInitializationResponse(response);
		}

		if (pingRequest) {
			return jsonResponse(200, "OK", McpLegacyResponseWire.encode(jsonCodec,
					new McpJsonRpcMessage.ResultResponse(mappedRequest.id(),
							McpWireResult.complete(McpJsonObject.empty()),
							McpJsonObject.empty())), corsHeaders);
		}

		if (discoveryRequest) {
			return catalogResponse(endpointRuntime.frameworkResponses(protocolProfile)
						.discovery(), protocolProfile,
					McpRuntimeCatalogLocalizer.ResponseKind.DISCOVERY, mappedRequest.id(),
					endpointPolicy, requestControl, corsHeaders);
		}

		if (toolsListRequest) {
			boolean requestSpecificProjection = callerAwareCatalog
					|| capabilityRegistry.hasAppTools();
			McpWireResult toolsResult = requestSpecificProjection
					? protocolProfile.renderFrameworkResult(
							McpProfileFrameworkResultKind.TOOLS_LIST,
							capabilityRegistry.toolsListResult(callerAwareCatalog
									? accessibleToolNames
									: new LinkedHashSet<>(capabilityRegistry.tools()),
									executionRequest.params().metadata().clientCapabilities()
											.supports(McpServerCapabilityRegistry.APPS_CAPABILITY)))
					: endpointRuntime.frameworkResponses(protocolProfile).toolsList()
							.orElseThrow();
			return catalogResponse(toolsResult, protocolProfile,
					McpRuntimeCatalogLocalizer.ResponseKind.TOOLS_LIST, mappedRequest.id(),
					endpointPolicy, requestControl, corsHeaders, catalogAccessSession,
					requestSpecificProjection);
		}

		if (promptsListRequest) {
			McpWireResult promptsResult = callerAwareCatalog
					? protocolProfile.renderFrameworkResult(
							McpProfileFrameworkResultKind.PROMPTS_LIST,
							capabilityRegistry.promptsListResult(accessiblePromptNames))
					: endpointRuntime.frameworkResponses(protocolProfile).promptsList()
							.orElseThrow();
			return catalogResponse(promptsResult, protocolProfile,
					McpRuntimeCatalogLocalizer.ResponseKind.PROMPTS_LIST, mappedRequest.id(),
					endpointPolicy, requestControl, corsHeaders, catalogAccessSession,
					callerAwareCatalog);
		}

		if (resourcesListRequest && !endpoint.customResourceListHandler()) {
			return catalogResponse(endpointRuntime.frameworkResponses(protocolProfile)
						.resourcesList(), protocolProfile,
					McpRuntimeCatalogLocalizer.ResponseKind.RESOURCES_LIST, mappedRequest.id(),
					endpointPolicy, requestControl, corsHeaders);
		}

		if (resourceTemplatesListRequest) {
			return catalogResponse(endpointRuntime.frameworkResponses(protocolProfile)
						.resourceTemplatesList(), protocolProfile,
					McpRuntimeCatalogLocalizer.ResponseKind.RESOURCE_TEMPLATES_LIST, mappedRequest.id(),
					endpointPolicy, requestControl, corsHeaders);
		}

		if (completionRequest.isPresent()) {
			CompletionRequest completion = completionRequest.orElseThrow();
			McpApplicationCompletionRoute route;
			if (completion.promptReference()) {
				Optional<McpNormalizedPromptDescriptor> descriptor =
						capabilityRegistry.promptDescriptor(completion.reference());
				if (descriptor.isEmpty())
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				Set<String> argumentNames = descriptor.orElseThrow().arguments().stream()
						.map(McpNormalizedPromptArgumentDescriptor::name)
						.collect(java.util.stream.Collectors.toSet());
				if (!argumentNames.contains(completion.argumentName())
						|| !argumentNames.containsAll(completion.contextArguments().keySet()))
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				route = applicationRouter.resolvePromptCompletion(
						completion.reference()).orElse(null);
				if (route == null) {
					applicationHandler = Optional.of(invocation ->
							emptyCompletionResult());
				}
			} else {
				route = applicationRouter.resolveResourceCompletion(
						completion.reference()).orElse(null);
				if (route == null) {
					if (!legacy)
						return invalidParams(protocolProfile, mappedRequest, corsHeaders);
					Optional<McpNormalizedResourceTemplateDescriptor> descriptor =
							capabilityRegistry.resourceTemplateDescriptor(completion.reference());
					if (descriptor.isEmpty())
						return invalidParams(protocolProfile, mappedRequest, corsHeaders);
					Set<String> argumentNames = descriptor.orElseThrow()
							.parsedTemplate().variableNames();
					if (!argumentNames.contains(completion.argumentName())
							|| !argumentNames.containsAll(completion.contextArguments().keySet()))
						return invalidParams(protocolProfile, mappedRequest, corsHeaders);
					applicationHandler = Optional.of(invocation -> emptyCompletionResult());
				} else if (!route.argumentNames().contains(completion.argumentName())
						|| !route.argumentNames().containsAll(
							completion.contextArguments().keySet())) {
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				}
			}
			if (route != null) {
				McpApplicationCompletionRoute selectedRoute = route;
				applicationHandler = Optional.of(invocation ->
						selectedRoute.handler().handle(invocation,
								completion.argumentName(), completion.argumentValue(),
								completion.contextArguments()));
			}
		}

		McpApplicationRequestHandler resolvedApplicationHandler =
				applicationHandler.orElseThrow();
		Optional<McpFrameworkRequestStateContinuation> resolvedContinuation =
				frameworkRequestStateContinuation;
		Optional<CatalogAccessSession> resolvedCatalogAccessSession =
				catalogAccessSession;
		AtomicReference<@Nullable String> resolvedCatalogSelectedLocaleSlot =
				catalogSelectedLocaleSlot;
		McpJsonRpcMessage.Request dispatchRequest = requestControl.withReservedLegacyProgress(executionRequest);
		requestControl.handoff(application, () -> {
			McpApplicationResponseWriter responseWriter =
					new McpApplicationResponseWriter() {
					@Override
					public void didFinishPhysicalWork() {
						requestControl.legacyApplicationPhysicalFinished();
					}

					@Override
					public boolean write(@NonNull McpApplicationResponse response) {
						Optional<McpImplementationMetadata> serverInformation =
								endpoint.serverInformationIncluded()
										? Optional.of(endpoint.serverInformation())
										: Optional.empty();
						return requestControl.writeApplicationResponse(
								response.withServerInformation(serverInformation),
								mappedRequest.id(), corsHeaders);
					}

					@Override
					public boolean writeNotification(
							McpJsonRpcMessage.@NonNull Notification notification)
							throws InterruptedException {
						return requestControl.writeApplicationNotification(
								notification, corsHeaders);
					}

					@Override
					public boolean isNotificationDeliveryActive() {
						return requestControl.isNotificationDeliveryActive();
					}
				};
			Optional<McpRequestContext> publicContext =
					requestControl.publicRequestContext();
			if (publicContext.isPresent()) {
				application.dispatchWithSokletRequest(request, sokletRequest,
						publicContext.orElseThrow(), dispatchRequest, protocolProfile,
							effectiveIdentity,
							resolvedContinuation,
							resolvedCatalogAccessSession,
							resolvedCatalogSelectedLocaleSlot,
						resolvedApplicationHandler,
						endpointPolicy.requestInterceptor(),
						requestControl::applicationEntryAllowed,
						requestControl.deadlineNanos(), responseWriter,
						requestControl::applicationTerminated);
			} else {
				application.dispatchWithSokletRequest(request, sokletRequest,
						dispatchRequest, protocolProfile, effectiveIdentity,
						resolvedContinuation,
						resolvedApplicationHandler,
						endpointPolicy.requestInterceptor(),
						requestControl::applicationEntryAllowed,
						requestControl.deadlineNanos(), responseWriter,
						requestControl::applicationTerminated);
			}
		});
		return null;
	}

	@NonNull
	private AcceptedSubscriptionFilter parseAcceptedSubscriptionFilter(
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpNormalizedSubscriptionConfiguration configuration,
			@NonNull Set<McpRuntimeCatalogLocalizer.@NonNull ResponseKind>
					localizedResponseKinds,
			boolean toolsPresent, boolean promptsPresent) {
		Map<String, McpJsonValue> requestFields =
				requireNonNull(request).params().fields().members();
		McpJsonValue notificationsValue = requestFields.get("notifications");
		if (!(notificationsValue instanceof McpJsonObject notifications))
			throw new IllegalArgumentException(
					"Subscription notifications must be an object.");

		Map<String, McpJsonValue> fields = notifications.members();
		// Canonical registrations are immutable, but localized presentation is
		// not: tool and prompt list-change filters are accepted exactly when the
		// corresponding localized catalog exists.
		boolean toolsListChangedRequested = optionalSubscriptionBoolean(
				fields, "toolsListChanged");
		boolean promptsListChangedRequested = optionalSubscriptionBoolean(
				fields, "promptsListChanged");
		boolean resourcesListChangedRequested = optionalSubscriptionBoolean(
				fields, "resourcesListChanged");
		boolean resourceSubscriptionsRequested =
				fields.containsKey("resourceSubscriptions");
		Map<URI, SubscriptionResource> requestedResources = new LinkedHashMap<>();
		if (resourceSubscriptionsRequested) {
			McpJsonValue resourceSubscriptions = fields.get("resourceSubscriptions");
			if (!(resourceSubscriptions instanceof McpJsonArray resources))
				throw new IllegalArgumentException(
						"Resource subscriptions must be an array.");
			for (McpJsonValue value : resources.values()) {
				if (!(value instanceof McpJsonString string))
					throw new IllegalArgumentException(
							"Resource subscription URIs must be strings.");
				String wireUri = McpLevelOneUriTemplate.requireValidAbsoluteUri(
						string.value(), "Resource subscription URI");
				URI uri = URI.create(wireUri);
				requestedResources.putIfAbsent(uri,
						new SubscriptionResource(uri, wireUri));
				if (requestedResources.size()
						> MAXIMUM_RESOURCE_SUBSCRIPTION_URIS)
					throw new IllegalArgumentException(
							"Too many resource subscription URIs were requested.");
			}
		}
		boolean taskIdsRequested = fields.containsKey("taskIds");
		List<String> requestedTaskIds = new ArrayList<>();
		if (taskIdsRequested) {
			McpJsonValue taskIdsValue = fields.get("taskIds");
			if (!(taskIdsValue instanceof McpJsonArray taskIds))
				throw new IllegalArgumentException(
						"Task subscription IDs must be an array.");
			Set<String> distinctTaskIds = new LinkedHashSet<>();
			for (McpJsonValue value : taskIds.values()) {
				if (!(value instanceof McpJsonString string)
						|| !validTaskId(string.value()))
					throw new IllegalArgumentException(
							"Task subscription IDs must be valid task-ID strings.");
				distinctTaskIds.add(string.value());
				if (distinctTaskIds.size() > MAXIMUM_TASK_SUBSCRIPTION_IDS)
					throw new IllegalArgumentException(
							"Too many task subscription IDs were requested.");
			}
			requestedTaskIds.addAll(distinctTaskIds);
		}

		Set<McpResourceNotificationType> supported =
				requireNonNull(configuration).notificationTypes();
		boolean acceptToolsListChanged = toolsListChangedRequested
				&& McpServerCapabilityRegistry.catalogListChangedSupported(true,
						toolsPresent, supported,
						McpResourceNotificationType.TOOLS_LIST_CHANGED,
						localizedResponseKinds,
						McpRuntimeCatalogLocalizer.ResponseKind.TOOLS_LIST);
		boolean acceptPromptsListChanged = promptsListChangedRequested
				&& McpServerCapabilityRegistry.catalogListChangedSupported(true,
						promptsPresent, supported,
						McpResourceNotificationType.PROMPTS_LIST_CHANGED,
						localizedResponseKinds,
						McpRuntimeCatalogLocalizer.ResponseKind.PROMPTS_LIST);
		// The application publisher and the framework localization source
		// compose: either one truthfully supports the resources family.
		boolean acceptResourcesListChanged = resourcesListChangedRequested
				&& (supported.contains(
						McpResourceNotificationType.RESOURCES_LIST_CHANGED)
						|| localizedResponseKinds.contains(
								McpRuntimeCatalogLocalizer.ResponseKind
										.RESOURCES_LIST)
						|| localizedResponseKinds.contains(
								McpRuntimeCatalogLocalizer.ResponseKind
										.RESOURCE_TEMPLATES_LIST));
		boolean acceptResourceSubscriptions = resourceSubscriptionsRequested
				&& supported.contains(McpResourceNotificationType.RESOURCE_UPDATED);
		return new AcceptedSubscriptionFilter(acceptToolsListChanged,
				acceptPromptsListChanged, acceptResourcesListChanged,
				acceptResourceSubscriptions, requestedResources,
				taskIdsRequested, requestedTaskIds, Set.of(),
				request.params().metadata().clientCapabilities());
	}

	private boolean optionalSubscriptionBoolean(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String name) {
		McpJsonValue value = requireNonNull(fields).get(requireNonNull(name));
		if (value == null)
			return false;
		if (!(value instanceof McpJsonBoolean booleanValue))
			throw new IllegalArgumentException(
					"Subscription filter booleans must be boolean values.");
		return booleanValue == McpJsonBoolean.TRUE;
	}

	@NonNull
	private AcceptedSubscriptionFilter authorizeTaskSubscriptions(
			@NonNull McpHttpEndpointBinding endpointBinding,
			@NonNull RequestControl requestControl,
			@NonNull AcceptedSubscriptionFilter filter) throws Exception {
		requireNonNull(endpointBinding);
		requireNonNull(requestControl);
		AcceptedSubscriptionFilter requiredFilter = requireNonNull(filter);
		if (!requiredFilter.taskIdsRequested()
				|| !endpointBinding.endpoint().subscriptionConfig().orElseThrow()
						.taskNotifications())
			return requiredFilter.withAcceptedTaskIds(List.of());

		TaskManagerAdapter taskManagerAdapter = endpointBinding.taskManagerAdapter()
				.orElseThrow(() -> new IllegalStateException(
						"MCP task notifications require a task manager."));
		McpRequestContext requestContext = requestControl.publicRequestContext()
				.orElseThrow(() -> new IllegalStateException(
						"MCP task notification authorization requires an admitted request context."));
		List<String> acceptedTaskIds = new ArrayList<>();
		for (String taskId : requiredFilter.requestedTaskIds()) {
			if (!requestControl.protocolProcessingAllowed())
				return requiredFilter.withAcceptedTaskIds(List.of());
			try {
				Optional<TaskSnapshot> taskSnapshot = requireNonNull(
						taskManagerAdapter
								.findTaskForSubscriptionAuthorization(
										requestContext, taskId),
						"The MCP task manager adapter returned null.");
				if (taskSnapshot.isEmpty())
					continue;
				TaskSnapshot snapshot = taskSnapshot.orElseThrow();
				if (!taskId.equals(snapshot.task().getTaskId()))
					throw new IllegalStateException(
							"The MCP task manager returned a mismatched task ID.");
				McpServerRuntimeBridge.requireTaskInputCapabilities(snapshot,
						requiredFilter.clientCapabilities());
			} catch (Throwable throwable) {
				// One unavailable, malformed, or capability-incompatible task must not
				// destroy unrelated advisory subscriptions in the same request. Polling
				// remains the explicit, authoritative task-error path.
				if (throwable instanceof InterruptedException)
					Thread.currentThread().interrupt();
				continue;
			}
			acceptedTaskIds.add(taskId);
		}
		return requiredFilter.withAcceptedTaskIds(acceptedTaskIds);
	}

	private McpJsonRpcMessage.@NonNull Notification subscriptionAcknowledgement(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcId subscriptionId,
			@NonNull AcceptedSubscriptionFilter filter) {
		Map<String, McpJsonValue> accepted = new LinkedHashMap<>();
		if (filter.toolsListChanged())
			accepted.put("toolsListChanged", McpJsonBoolean.TRUE);
		if (filter.promptsListChanged())
			accepted.put("promptsListChanged", McpJsonBoolean.TRUE);
		if (filter.resourcesListChanged())
			accepted.put("resourcesListChanged", McpJsonBoolean.TRUE);
		if (filter.resourceSubscriptionsIncluded()) {
			List<McpJsonValue> resourceUris = filter.resourceSubscriptions().values()
					.stream()
					.map(resource -> (McpJsonValue) new McpJsonString(resource.wireUri()))
					.toList();
			accepted.put("resourceSubscriptions", new McpJsonArray(resourceUris));
		}
		if (!filter.acceptedTaskIds().isEmpty()) {
			List<McpJsonValue> taskIds = filter.acceptedTaskIds().stream()
					.map(taskId -> (McpJsonValue) new McpJsonString(taskId))
					.toList();
			accepted.put("taskIds", new McpJsonArray(taskIds));
		}
		Map<String, McpJsonValue> params = new LinkedHashMap<>();
		params.put("_meta", subscriptionMetadata(subscriptionId));
		params.put("notifications", new McpJsonObject(accepted));
		McpJsonRpcMessage.Notification canonicalNotification =
				new McpJsonRpcMessage.Notification(
				"notifications/subscriptions/acknowledged",
				Optional.of(new McpJsonObject(params)), McpJsonObject.empty());
		return requireNonNull(protocolProfile.renderFrameworkNotification(
				McpProfileFrameworkNotificationKind
						.SUBSCRIPTION_ACKNOWLEDGEMENT,
				canonicalNotification));
	}

	private McpJsonRpcMessage.@NonNull Notification listChangedNotification(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcId subscriptionId, @NonNull String method) {
		Map<String, McpJsonValue> params = new LinkedHashMap<>();
		params.put("_meta", subscriptionMetadata(subscriptionId));
		McpJsonRpcMessage.Notification canonicalNotification =
				new McpJsonRpcMessage.Notification(method,
				Optional.of(new McpJsonObject(params)), McpJsonObject.empty());
		return requireNonNull(protocolProfile.renderFrameworkNotification(
				McpProfileFrameworkNotificationKind.SUBSCRIPTION_EVENT,
				canonicalNotification));
	}

	private McpJsonRpcMessage.@NonNull Notification subscriptionNotification(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcId subscriptionId,
			@NonNull Event event) {
		Map<String, McpJsonValue> params = new LinkedHashMap<>();
		params.put("_meta", subscriptionMetadata(subscriptionId));
		String method;
		if (event instanceof McpSubscriptionEventSource.Event.ResourcesListChanged) {
			method = "notifications/resources/list_changed";
		} else if (event instanceof McpSubscriptionEventSource.Event.ResourceUpdated updated) {
			method = "notifications/resources/updated";
			params.put("uri", new McpJsonString(updated.wireResourceUri()));
		} else {
			throw new IllegalArgumentException(
					"Unsupported MCP subscription event: " + event.getClass().getName());
		}
		McpJsonRpcMessage.Notification canonicalNotification =
				new McpJsonRpcMessage.Notification(method,
				Optional.of(new McpJsonObject(params)), McpJsonObject.empty());
		return requireNonNull(protocolProfile.renderFrameworkNotification(
				McpProfileFrameworkNotificationKind.SUBSCRIPTION_EVENT,
				canonicalNotification));
	}

	private McpJsonRpcMessage.@NonNull Notification taskNotification(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcId subscriptionId,
			@NonNull TaskSnapshot taskSnapshot) {
		McpJsonObject params = McpServerRuntimeBridge.taskNotificationParams(
				requireNonNull(taskSnapshot), subscriptionMetadata(subscriptionId));
		McpJsonRpcMessage.Notification canonicalNotification =
				new McpJsonRpcMessage.Notification("notifications/tasks",
						Optional.of(params), McpJsonObject.empty());
		if (taskSnapshot.task().getTaskStatus()
					== com.soklet.McpTaskStatus.COMPLETED
				&& taskSnapshot.structuredContentMirroredAsText()) {
			try {
				envelopeCodec.encode(canonicalNotification);
			} catch (IllegalArgumentException exception) {
				params = McpServerRuntimeBridge.taskNotificationParams(taskSnapshot,
						subscriptionMetadata(subscriptionId), false);
				canonicalNotification = new McpJsonRpcMessage.Notification(
						"notifications/tasks", Optional.of(params),
						McpJsonObject.empty());
			}
		}
		return requireNonNull(protocolProfile.renderFrameworkNotification(
				McpProfileFrameworkNotificationKind.SUBSCRIPTION_EVENT,
				canonicalNotification));
	}

	@NonNull
	private McpJsonObject subscriptionMetadata(
			@NonNull McpJsonRpcId subscriptionId) {
		return new McpJsonObject(Map.of(McpResultMetadata.SUBSCRIPTION_ID_KEY,
				requireNonNull(subscriptionId).toJsonValue()));
	}

	private McpJsonRpcMessage.@NonNull ResultResponse subscriptionTerminalResponse(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcId subscriptionId,
			@NonNull McpNormalizedEndpoint endpoint) {
		Optional<McpImplementationMetadata> serverInformation =
				endpoint.serverInformationIncluded()
						? Optional.of(endpoint.serverInformation()) : Optional.empty();
		McpResultMetadata metadata = McpResultMetadata.withSubscriptionId(
				subscriptionId, serverInformation);
		McpWireResult canonicalResult = McpWireResult.complete(
				McpJsonObject.empty(), Optional.of(metadata));
		McpWireResult renderedResult = requireNonNull(protocolProfile
				.renderFrameworkResult(
						McpProfileFrameworkResultKind.SUBSCRIPTION_TERMINAL,
						canonicalResult));
		return new McpJsonRpcMessage.ResultResponse(subscriptionId, renderedResult,
				McpJsonObject.empty());
	}

	private boolean validPromptArguments(
			@NonNull McpNormalizedPromptDescriptor descriptor,
			@Nullable McpJsonValue argumentsValue) {
		if (!validPromptArgumentValues(argumentsValue))
			return false;
		Map<String, McpJsonValue> suppliedArguments = argumentsValue == null
				? Map.of() : ((McpJsonObject) argumentsValue).members();

		Map<String, McpNormalizedPromptArgumentDescriptor> declarations =
				new LinkedHashMap<>();
		for (McpNormalizedPromptArgumentDescriptor argument : descriptor.arguments())
			declarations.put(argument.name(), argument);

		for (Map.Entry<String, McpJsonValue> supplied : suppliedArguments.entrySet()) {
			if (!declarations.containsKey(supplied.getKey())
					|| !(supplied.getValue() instanceof McpJsonString))
				return false;
		}

		for (McpNormalizedPromptArgumentDescriptor declaration : declarations.values()) {
			if (declaration.required()
					&& !suppliedArguments.containsKey(declaration.name()))
				return false;
		}

		return true;
	}

	private record CompletionRequest(boolean promptReference,
			@NonNull String reference, @NonNull String argumentName,
			@NonNull String argumentValue,
			@NonNull Map<String, String> contextArguments) {
		private CompletionRequest {
			requireNonNull(reference);
			requireNonNull(argumentName);
			requireNonNull(argumentValue);
			contextArguments = Map.copyOf(requireNonNull(contextArguments));
		}

		@Override
		@NonNull
		public String toString() {
			return "CompletionRequest[promptReference=" + promptReference
					+ ", reference=<redacted>, argumentName=<redacted>, "
					+ "argumentValue=<redacted>, contextArguments=<redacted>]";
		}
	}

	@NonNull
	private static McpWireResult emptyCompletionResult() {
		return McpWireResult.complete(new McpJsonObject(Map.of(
				"completion", new McpJsonObject(Map.of(
						"values", new McpJsonArray(List.of()))))));
	}

	@NonNull
	private static Optional<CompletionRequest> parseCompletionRequest(
			@NonNull Map<String, McpJsonValue> fields) {
		if (!(fields.get("ref") instanceof McpJsonObject reference)
				|| !(fields.get("argument") instanceof McpJsonObject argument))
			return Optional.empty();
		McpJsonValue typeValue = reference.members().get("type");
		if (!(typeValue instanceof McpJsonString type))
			return Optional.empty();
		boolean promptReference;
		String referenceKey;
		if ("ref/prompt".equals(type.value())) {
			promptReference = true;
			referenceKey = "name";
		} else if ("ref/resource".equals(type.value())) {
			promptReference = false;
			referenceKey = "uri";
		} else {
			return Optional.empty();
		}
		if (!(reference.members().get(referenceKey)
						instanceof McpJsonString referenceString)
				|| referenceString.value().isBlank()
				|| !(argument.members().get("name")
						instanceof McpJsonString argumentName)
				|| argumentName.value().isBlank()
				|| !(argument.members().get("value")
						instanceof McpJsonString argumentValue))
			return Optional.empty();
		Map<String, String> contextArguments = new LinkedHashMap<>();
		McpJsonValue contextValue = fields.get("context");
		if (contextValue != null) {
			if (!(contextValue instanceof McpJsonObject context))
				return Optional.empty();
			McpJsonValue argumentsValue = context.members().get("arguments");
			if (argumentsValue != null) {
				if (!(argumentsValue instanceof McpJsonObject arguments))
					return Optional.empty();
				for (Map.Entry<String, McpJsonValue> entry
						: arguments.members().entrySet()) {
					if (!(entry.getValue() instanceof McpJsonString value))
						return Optional.empty();
					contextArguments.put(entry.getKey(), value.value());
				}
			}
		}
		return Optional.of(new CompletionRequest(promptReference,
				referenceString.value(), argumentName.value(), argumentValue.value(),
				contextArguments));
	}

	@NonNull
	private static Optional<@NonNull McpJsonObject> parseInputResponses(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		Optional<McpJsonObject> responses = parseInputResponsesObject(fields);
		if (responses.isPresent())
			for (McpJsonValue response
					: responses.orElseThrow().members().values())
				McpInputResponseValidator.validate(response);
		return responses;
	}

	@NonNull
	private static Optional<@NonNull McpJsonObject> parseTaskInputResponses(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		return parseInputResponsesObject(fields);
	}

	@NonNull
	private static Optional<@NonNull McpJsonObject> parseInputResponsesObject(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		McpJsonValue value = requireNonNull(fields).get("inputResponses");
		if (value == null)
			return Optional.empty();
		if (!(value instanceof McpJsonObject responses))
			throw new IllegalArgumentException("MCP input responses must be an object.");
		return Optional.of(responses);
	}

	private static boolean validTaskId(@NonNull String taskId) {
		requireNonNull(taskId);
		return !taskId.isBlank()
				&& taskId.indexOf('\r') < 0
				&& taskId.indexOf('\n') < 0;
	}

	private static boolean terminalTaskStatus(@NonNull McpTaskStatus taskStatus) {
		return switch (requireNonNull(taskStatus)) {
			case COMPLETED, FAILED, CANCELED -> true;
			case WORKING, INPUT_REQUIRED -> false;
		};
	}

	@NonNull
	private Optional<@NonNull String> parseRequestState(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull McpRequestStateMode requestStateMode)
			throws McpInvalidRequestStateException,
			McpRequestStateUnavailableException {
		McpJsonValue value = requireNonNull(fields).get("requestState");
		if (value == null)
			return Optional.empty();
		if (requireNonNull(requestStateMode) == McpRequestStateMode.NONE
				|| !(value instanceof McpJsonString string)
				|| string.value().isEmpty())
			throw new McpInvalidRequestStateException();

		if (requestStateMode == McpRequestStateMode.APPLICATION_PROTECTED) {
			if (utf8Size(string.value())
					> APPLICATION_REQUEST_STATE_MAXIMUM_BYTES)
				throw new McpInvalidRequestStateException();
		} else if (requestStateMode == McpRequestStateMode.FRAMEWORK_PROTECTED) {
			requestStateRuntime.validateStructure(string.value());
		} else {
			throw new McpInvalidRequestStateException();
		}
		return Optional.of(string.value());
	}

	@NonNull
	private McpWireResult resourceResultWithCachePolicy(
			@NonNull McpWireResult result,
			@NonNull McpResourceCachePolicy cachePolicy,
			boolean resourceListResult, boolean resourceRetry,
			boolean localizationEnabled,
			int maximumCursorSizeInBytes,
			@NonNull String endpointPath,
			@NonNull McpApplicationRequestRouter applicationRouter) {
		requireNonNull(result);
		requireNonNull(cachePolicy);
		requireNonNull(endpointPath);
		requireNonNull(applicationRouter);
		if (!McpResultType.COMPLETE.equals(result.resultType())) {
			if (resourceListResult)
				throw new IllegalArgumentException(
						"resources/list must return a complete result.");
			return result;
		}

		Map<String, McpJsonValue> fields =
				new LinkedHashMap<>(result.fields().members());
		if (resourceListResult)
			validateResourceListResult(fields, endpointPath, applicationRouter);
		else
			validateResourceReadResult(fields);

		McpJsonValue configuredScope = fields.get("cacheScope");
		if (configuredScope != null
				&& (!(configuredScope instanceof McpJsonString string)
				|| !cachePolicy.scope().wireValue().equals(string.value())))
			throw new IllegalArgumentException(
					"A resource result cannot override its cache scope.");

		McpJsonValue configuredTtl = fields.get("ttlMs");
		if (configuredTtl != null
				&& (!(configuredTtl instanceof McpJsonNumber number)
				|| number.value().stripTrailingZeros().scale() > 0
				|| number.value().signum() < 0))
			throw new IllegalArgumentException(
					"A resource result cache TTL must be a nonnegative integer.");

		if (resourceRetry || localizationEnabled) {
			fields.put("cacheScope", new McpJsonString(
					McpCacheScope.PRIVATE.wireValue()));
			fields.put("ttlMs", new McpJsonNumber(BigDecimal.ZERO));
		} else {
			fields.put("cacheScope", new McpJsonString(
					cachePolicy.scope().wireValue()));

			if (configuredTtl == null)
				fields.put("ttlMs", new McpJsonNumber(
						cachePolicy.timeToLiveMilliseconds()));
		}

		if (resourceListResult && fields.containsKey("nextCursor")) {
			McpJsonValue cursorValue = fields.get("nextCursor");
			if (!(cursorValue instanceof McpJsonString string)
					|| !McpCursorValidator.fitsWithinUtf8ByteLimit(
							string.value(), maximumCursorSizeInBytes))
				throw new IllegalArgumentException(
						"A resource-list next cursor exceeds its wire bound.");
		}

		return McpWireResult.complete(new McpJsonObject(fields), result.metadata());
	}

	private void validateResourceListResult(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields,
			@NonNull String endpointPath,
			@NonNull McpApplicationRequestRouter applicationRouter) {
		requireNonNull(endpointPath);
		requireNonNull(applicationRouter);
		McpJsonValue resourcesValue = fields.get("resources");
		if (!(resourcesValue instanceof McpJsonArray resources))
			throw new IllegalArgumentException(
					"A resource-list result must contain a resources array.");

		Set<URI> observedUris = new LinkedHashSet<>();
		for (McpJsonValue value : resources.values()) {
			if (!(value instanceof McpJsonObject descriptor))
				throw new IllegalArgumentException(
						"Every resource-list member must be an object.");
			McpJsonValue uriValue = descriptor.members().get("uri");
			McpJsonValue nameValue = descriptor.members().get("name");
			if (!(uriValue instanceof McpJsonString uriString)
					|| !(nameValue instanceof McpJsonString nameString))
				throw new IllegalArgumentException(
						"Every resource-list member requires string uri and name fields.");

			Map<String, McpJsonValue> descriptorFields =
					new LinkedHashMap<>(descriptor.members());
			descriptorFields.remove("uri");
			descriptorFields.remove("name");
			McpJsonValue metadataValue = descriptorFields.remove("_meta");
			McpJsonObject metadata;
			if (metadataValue == null)
				metadata = McpJsonObject.empty();
			else if (metadataValue instanceof McpJsonObject object)
				metadata = object;
			else
				throw new IllegalArgumentException(
						"Resource descriptor metadata must be an object.");

			validateResourceDescriptorFields(descriptorFields);
			McpNormalizedResourceDescriptor normalized =
					new McpNormalizedResourceDescriptor(uriString.value(),
							nameString.value(), new McpJsonObject(descriptorFields),
							metadata, McpResourceCachePolicy.privateNoCache());
			EndpointRuntime ownerEndpoint = this.endpointsByPath.get(endpointPath);
			if (ownerEndpoint != null && ownerEndpoint.binding().endpoint().isSkillFile(URI.create(normalized.uri())))
				throw new IllegalArgumentException(
						"Ordinary resource lists must not expose Skills-owned files.");
			if (!observedUris.add(URI.create(normalized.uri())))
				throw new IllegalArgumentException(
						resourceListRouteDiagnostic(true, normalized.uri(),
								endpointPath));
			if (!hasReadableResourceRoute(normalized.uri(), applicationRouter))
				throw new IllegalArgumentException(
						resourceListRouteDiagnostic(false, normalized.uri(),
								endpointPath));
		}
	}

	@NonNull
	static String resourceListRouteDiagnostic(boolean duplicateUri,
			@NonNull String uri, @NonNull String endpointPath) {
		return "A resource-list page for endpoint '"
				+ boundedResourceListDiagnosticValue(endpointPath)
				+ "' contains " + (duplicateUri ? "a duplicate" : "an unreadable")
				+ " URI '" + boundedResourceListDiagnosticValue(uri) + "'.";
	}

	@NonNull
	private static String boundedResourceListDiagnosticValue(
			@NonNull String value) {
		requireNonNull(value);
		if (value.length()
				<= MAXIMUM_RESOURCE_LIST_DIAGNOSTIC_VALUE_CHARACTERS)
			return value;

		int end = MAXIMUM_RESOURCE_LIST_DIAGNOSTIC_VALUE_CHARACTERS - 3;
		if (Character.isHighSurrogate(value.charAt(end - 1)))
			--end;
		return value.substring(0, end) + "...";
	}

	private static void validateResourceReadResult(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		McpJsonValue contentsValue = fields.get("contents");
		if (!(contentsValue instanceof McpJsonArray contents)
				|| contents.values().isEmpty())
			throw new IllegalArgumentException(
					"A complete resource-read result requires nonempty contents.");

		for (McpJsonValue value : contents.values()) {
			if (!(value instanceof McpJsonObject content))
				throw new IllegalArgumentException(
						"Every resource-read content value must be an object.");
			McpJsonValue uriValue = content.members().get("uri");
			if (!(uriValue instanceof McpJsonString uriString))
				throw new IllegalArgumentException(
						"Every resource-read content value requires a string URI.");
			McpLevelOneUriTemplate.requireValidAbsoluteUri(
					uriString.value(), "Resource content URI");
			McpJsonValue mimeType = content.members().get("mimeType");
			if (mimeType != null && (!(mimeType instanceof McpJsonString string)
					|| string.value().isBlank()))
				throw new IllegalArgumentException(
						"Resource content MIME type must be a nonblank string.");

			McpJsonValue text = content.members().get("text");
			McpJsonValue blob = content.members().get("blob");
			if (text instanceof McpJsonString == blob instanceof McpJsonString)
				throw new IllegalArgumentException(
						"Resource content requires exactly one text or blob string.");
			McpJsonValue metadataValue = content.members().get("_meta");
			if (metadataValue != null) {
				if (!(metadataValue instanceof McpJsonObject metadata))
					throw new IllegalArgumentException(
							"Resource content metadata must be an object.");
				McpProtocolSupport.requireApplicationMetadataFields(
						metadata, Set.of());
			}
		}
	}

	private static void validateResourceDescriptorFields(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		for (String name : List.of("title", "description", "mimeType")) {
			McpJsonValue value = fields.get(name);
			if (value != null && !(value instanceof McpJsonString))
				throw new IllegalArgumentException(
						"Resource descriptor '" + name + "' must be a string.");
		}
		McpJsonValue mimeType = fields.get("mimeType");
		if (mimeType instanceof McpJsonString string && string.value().isBlank())
			throw new IllegalArgumentException(
					"Resource descriptor MIME type must not be blank.");
		McpJsonValue icons = fields.get("icons");
		if (icons != null && !(icons instanceof McpJsonArray))
			throw new IllegalArgumentException(
					"Resource descriptor icons must be an array.");
		McpJsonValue annotations = fields.get("annotations");
		if (annotations != null && !(annotations instanceof McpJsonObject))
			throw new IllegalArgumentException(
					"Resource descriptor annotations must be an object.");
		McpJsonValue size = fields.get("size");
		if (size != null && (!(size instanceof McpJsonNumber number)
				|| number.value().stripTrailingZeros().scale() > 0
				|| number.value().signum() < 0))
			throw new IllegalArgumentException(
					"Resource descriptor size must be a nonnegative integer.");
	}

	private boolean hasReadableResourceRoute(@NonNull String uri,
			@NonNull McpApplicationRequestRouter applicationRouter) {
		requireNonNull(applicationRouter);
		if (applicationRouter.resolveExactResource(uri).isPresent())
			return true;
		return applicationRouter.resolveResourceTemplate(uri).isPresent();
	}

	private int utf8Size(@NonNull String value) {
		try {
			return StandardCharsets.UTF_8.newEncoder()
					.encode(CharBuffer.wrap(requireNonNull(value))).remaining();
		} catch (CharacterCodingException exception) {
			return Integer.MAX_VALUE;
		}
	}

	private boolean validPromptArgumentValues(@Nullable McpJsonValue argumentsValue) {
		if (argumentsValue == null)
			return true;
		if (!(argumentsValue instanceof McpJsonObject arguments))
			return false;
		for (McpJsonValue value : arguments.members().values()) {
			if (!(value instanceof McpJsonString))
				return false;
		}
		return true;
	}

	private void recordUnknownMirroredHeaders(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod, int occurrences) {
		requireNonNull(endpointPath);
		requireNonNull(jsonRpcMethod);
		if (occurrences == 0)
			return;
		unknownMirroredHeaderOccurrences.getAndUpdate(current ->
				current > Long.MAX_VALUE - occurrences
						? Long.MAX_VALUE : current + occurrences);
		for (int occurrence = 0; occurrence < occurrences; occurrence++)
			this.applicationExecutionObserver.recordUnknownMirroredHeader(
					endpointPath, jsonRpcMethod);
		this.applicationExecutionObserver.drain();
	}

	private static long minimumDeadline(long now, long first, long second) {
		return first - now <= second - now ? first : second;
	}

	/** HTTP-only admission deliberately has no JSON-RPC context or method label. */
	private @Nullable MicrohttpResponse processLegacySessionHttp(MicrohttpRequest request,
			Request publicRequest, HttpMethod method, EndpointRuntime endpoint,
			RequestControl control, McpApplicationExecution application, List<Header> headers) {
		control.startLegacyHttpObservation(publicRequest);
		Set<HttpMethod> configuredMethods = legacyHttpMethods(endpoint, null);
		if (!configuredMethods.contains(method))
			return methodNotAllowed(headers, configuredMethods);
		List<String> versions = headerValues(request, MCP_PROTOCOL_VERSION);
		if (versions.size() != 1 || versions.get(0).isBlank())
			return emptyResponse(400, "Bad Request", headers);
		String revision = versions.get(0);
		if (protocolProfiles.resolve(revision).isEmpty()
				|| endpoint.binding().revisionEndpoint(revision).isEmpty())
			return emptyResponse(400, "Bad Request", headers);
		if (!legacyHttpMethods(endpoint, revision).contains(method))
			return methodNotAllowed(headers, legacyHttpMethods(endpoint, revision));
		if (request.body().length != 0 || !validLegacySessionFraming(request, false))
			return emptyResponse(400, "Bad Request", headers);
		if (sessionRequestEvidenceBytes(request) > (long) transportConfiguration.maximumHeaderBytes() + 64L)
			return emptyResponse(431, "Request Header Fields Too Large", headers);
		if (method == HttpMethod.GET && !acceptsSse(headerValues(request, ACCEPT)))
			return emptyResponse(406, "Not Acceptable", headers);
		control.bindProtocolProfile(protocolProfiles.resolve(revision).orElseThrow());
		if (!control.beginCatalogPolicyEvaluation()) return null;
		Set<McpResourceNotificationType> offered = method == HttpMethod.GET
				? legacyTransportFamilies.get(endpoint.path()).get(revision) : Set.of();
		LegacyGetControl get = method == HttpMethod.GET
				? new LegacyGetControl(control, publicRequest, endpoint, revision, offered, headers) : null;
		if (get != null) {
			synchronized (legacyMaintenanceLock) {
				get.reconciliationGeneration = legacyTransportReconciliationGeneration;
				synchronized (control.lock) { control.legacyGet = get; }
				legacyGetControls.put(control, get);
			}
		}
		long expectedGeneration = get == null ? 0L : get.establishmentGeneration();
		long now = applicationClock.nanoTime();
		long operationDeadline = minimumDeadline(now, control.deadlineNanos(), now
				+ subscriptionRuntimeConfiguration.authorizationTimeout().toNanos());
		McpApplicationExecution.BoundedPolicyCancellation cancellation = application.newBoundedPolicyCancellation();
		synchronized (control.lock) { control.legacyHttpCancellation = cancellation; }
		if (get != null) get.bindCancellation(cancellation);
		LegacyHttpPolicyWork work = new LegacyHttpPolicyWork(() -> {});
		try {
			LegacyHttpAuthorization authorization = authorizeLegacyHttp(control, publicRequest,
					endpoint, revision, offered, false, operationDeadline, cancellation, work);
			if (authorization.rejection() != null) return legacyHttpRejection(authorization.rejection(), headers);
			McpLegacySessionTransportAdmission.Accepted accepted = requireNonNull(authorization.accepted());
			long lease = legacyLeaseDeadline(accepted, Long.MAX_VALUE);
			if (lease - applicationClock.nanoTime() <= 0L)
				return emptyResponse(403, "Forbidden", headers);
			McpEffectivePartition partition = requireNonNull(authorization.partition());
			try {
				McpLegacySessionStore store = requireNonNull(legacySessionStore);
				String sessionId = singleHeader(request, MCP_SESSION_ID).orElseThrow();
				if (method == HttpMethod.DELETE) {
					McpLegacySessionStore.Acquisition acquisition = store.acquire(sessionId,
							requireNonNull(authorization.owner()), endpoint.path(), revision, application,
							null, null, new McpLegacySessionStore.Target() {
								@Override public boolean cancel(McpLegacySessionStore.Cause cause) { return false; }
								@Override public void retire(McpLegacySessionStore.Cause cause) { }
							}, sessionRequestEvidenceBytes(request));
					if (acquisition.status() != McpLegacySessionStore.Status.ACCEPTED)
						return legacyHttpStatusResponse(acquisition.status(), headers);
					McpLegacySessionStore.Call call = acquisition.call().orElseThrow();
					try {
						return call.acceptedUse() && store.retireIfCurrent(call, McpLegacySessionStore.Cause.SESSION_CLOSED, lease)
								? emptyResponse(204, "No Content", headers) : emptyResponse(403, "Forbidden", headers);
					} finally { call.logicalComplete(); call.physicalComplete(); }
				}
				LegacyGetControl delivery = requireNonNull(get);
				if (!delivery.canEstablish(expectedGeneration)) return emptyResponse(403, "Forbidden", headers);
				// Allocate the transport before replacing an older logical GET.
				delivery.prepareStream();
				long total = applicationClock.nanoTime() + subscriptionRuntimeConfiguration.maximumSubscriptionDuration().toNanos();
				McpLegacySessionStore.GetAllocation allocation = store.reserveGet(sessionId,
						requireNonNull(authorization.owner()), endpoint.path(), revision, application, partition,
						delivery, sessionRequestEvidenceBytes(request), lease, total, accepted.notificationTypes());
				if (allocation.status() != McpLegacySessionStore.Status.ACCEPTED)
					return legacyHttpStatusResponse(allocation.status(), headers);
				if (!delivery.open(allocation.get().orElseThrow(), expectedGeneration, accepted.notificationTypes(), partition))
					return emptyResponse(403, "Forbidden", headers);
				return null;
			} finally { work.complete(); }
		} catch (McpApplicationPolicyCapacityException | McpApplicationPolicyDeadlineException exception) {
			control.reserveCatalogPolicyDeadlineResponse();
			return sessionCapacityResponse(503, headers);
		} catch (Throwable throwable) {
			return emptyResponse(500, "Internal Server Error", headers);
		} finally {
			work.complete();
			control.finishCatalogPolicyEvaluation();
			if (get != null) get.establishmentFinished();
		}
	}

	private boolean acceptsSse(List<String> values) {
		if (values.isEmpty()) return false;
		List<MediaRange> ranges = new ArrayList<>();
		for (String value : splitCommaAware(String.join(",", values))) {
			if (!validAcceptFragment(value)) return false;
			Optional<MediaRange> range = MediaRange.fromHeaderRepresentation(value);
			if (range.isEmpty()) return false;
			ranges.add(range.orElseThrow());
		}
		return effectiveQuality(ranges, "text", "event-stream").signum() > 0;
	}

	/** A logical timeout never releases a physical control or maintenance reservation. */
	private final class LegacyHttpPolicyWork {
		private int callbacks;
		private boolean logicalComplete;
		private boolean released;
		private @Nullable McpEffectivePartition partition;
		private final Runnable onRelease;
		LegacyHttpPolicyWork(Runnable onRelease) { this.onRelease = onRelease; }
		synchronized boolean reservePartition(McpEffectivePartition candidate) {
			if (partition != null) return true;
			if (!legacyHttpControlBudget.reserve(candidate)) return false;
			partition = candidate; return true;
		}
		synchronized void callbackEntered() { callbacks++; }
		void callbackExited() { synchronized (this) { callbacks--; } releaseIfComplete(); }
		void complete() { synchronized (this) { logicalComplete = true; } releaseIfComplete(); }
		private void releaseIfComplete() {
			McpEffectivePartition release;
			synchronized (this) {
				if (released || !logicalComplete || callbacks != 0) return;
				released = true; release = partition; partition = null;
			}
			if (release != null) legacyHttpControlBudget.release(release);
			onRelease.run();
		}
	}

	private record LegacyHttpAuthorization(McpLegacySessionTransportAdmission.@Nullable Accepted accepted,
			McpLegacySessionTransportAdmission.@Nullable Rejected rejection,
			McpLegacySessionStore.@Nullable Owner owner, @Nullable McpEffectivePartition partition) { }

	private LegacyHttpAuthorization authorizeLegacyHttp(RequestControl control, Request request,
			EndpointRuntime endpoint, String revision, Set<McpResourceNotificationType> offered,
			boolean reauthorization, long deadline,
			McpApplicationExecution.BoundedPolicyCancellation cancellation, LegacyHttpPolicyWork work) throws Exception {
		long remaining = deadline - applicationClock.nanoTime();
		if (remaining <= 0L) throw new McpApplicationPolicyDeadlineException(true);
		Instant publicDeadline = applicationClock.instant().plusNanos(remaining);
		control.reserveLegacyHttpPolicyWork(); work.callbackEntered();
		McpLegacySessionTransportAdmission.Decision decision = control.application.invokeBoundedPolicy(
				() -> requireNonNull(legacyTransportAdmission).admit(request, endpoint.path(), revision,
						offered, reauthorization, publicDeadline, cancellation), deadline, cancellation,
				() -> { control.legacyHttpPolicyPhysicallyFinished(); work.callbackExited(); });
		if (decision instanceof McpLegacySessionTransportAdmission.Rejected rejected)
			return new LegacyHttpAuthorization(null, rejected, null, null);
		McpLegacySessionTransportAdmission.Accepted accepted = (McpLegacySessionTransportAdmission.Accepted) decision;
		if (!offered.containsAll(accepted.notificationTypes()))
			throw new IllegalArgumentException("HTTP admission widened the offered notification selection.");
		if (!accepted.identity().authenticated() && !legacyAnonymousSessionsAllowed)
			return new LegacyHttpAuthorization(null,
					new McpLegacySessionTransportAdmission.Rejected(403, List.of()), null, null);
		if (legacyLeaseDeadline(accepted, Long.MAX_VALUE) - applicationClock.nanoTime() <= 0L)
			return new LegacyHttpAuthorization(null,
					new McpLegacySessionTransportAdmission.Rejected(403, List.of()), null, null);
		McpEffectivePartition partition = McpEffectiveAdmissionIdentity.resolve(
				endpoint.binding().revisionEndpoint(revision).orElseThrow(), endpoint.path(), accepted.identity())
				.authorizationPartition();
		if (!work.reservePartition(partition)) throw new McpApplicationPolicyCapacityException();
		control.reserveLegacyHttpPolicyWork(); work.callbackEntered();
		McpLegacySessionStore.Owner owner = control.application.invokeBoundedSessionOwnerPolicy(
				() -> new McpLegacySessionStore.Owner(requireNonNull(legacySessionOwnerResolver)
						.resolve(accepted.identity()), !accepted.identity().authenticated()), deadline,
				() -> { control.legacyHttpPolicyPhysicallyFinished(); work.callbackExited(); });
		return new LegacyHttpAuthorization(accepted, null, owner, partition);
	}

	/** Clip before converting to nanos so even Instant.MAX remains bounded. */
	private long legacyLeaseDeadline(McpLegacySessionTransportAdmission.Accepted accepted, long totalDeadline) {
		return legacyAuthorizationDeadline(accepted.validUntil(), totalDeadline);
	}

	private long legacyAuthorizationDeadline(Instant validUntil, long totalDeadline) {
		long nowNanos = applicationClock.nanoTime();
		Instant now = applicationClock.instant();
		if (!validUntil.isAfter(now)) return nowNanos;
		Duration maximum = subscriptionRuntimeConfiguration.maximumAuthorizationDuration();
		Duration duration = Duration.between(now, validUntil);
		long nanos = duration.compareTo(maximum) >= 0 ? maximum.toNanos() : duration.toNanos();
		return totalDeadline == Long.MAX_VALUE ? nowNanos + nanos
				: minimumDeadline(nowNanos, nowNanos + nanos, totalDeadline);
	}

	private MicrohttpResponse legacyHttpRejection(McpLegacySessionTransportAdmission.Rejected rejected,
			List<Header> headers) {
		int status = Set.of(400, 404, 405).contains(rejected.statusCode()) ? 403 : rejected.statusCode();
		List<Header> combined = new ArrayList<>(headers);
		combined.addAll(rejected.headers());
		return emptyResponse(status, StatusCode.fromStatusCode(status).map(StatusCode::getReasonPhrase).orElse("Rejected"), List.copyOf(combined));
	}

	private MicrohttpResponse legacyHttpStatusResponse(McpLegacySessionStore.Status status, List<Header> headers) {
		return switch (status) {
			case NOT_FOUND -> emptyResponse(404, "Not Found", headers);
			case REVISION_MISMATCH, INVALID_ID -> emptyResponse(400, "Bad Request", headers);
			case PARTITION_MISMATCH, AUTHORIZATION_EXPIRED, ANONYMOUS_DENIED -> emptyResponse(403, "Forbidden", headers);
			case OWNER_CAPACITY, CALL_CAPACITY -> sessionCapacityResponse(503, headers);
			case GLOBAL_CAPACITY, STOPPED -> sessionCapacityResponse(503, headers);
			default -> emptyResponse(500, "Internal Server Error", headers);
		};
	}

	private @Nullable MicrohttpResponse processLegacyResourceSubscription(MicrohttpRequest request,
			RequestControl control, EndpointRuntime endpoint, McpApplicationRequestRouter router,
			McpJsonRpcMessage.Request rpcRequest, String uri, McpEffectivePartition partition,
			boolean subscribe, List<Header> headers) {
		McpLegacySessionStore store = requireNonNull(legacySessionStore);
		McpLegacySessionStore.Call call = control.legacyCall;
		if (call == null || !control.protocolProcessingAllowed()) return null;
		if (!subscribe) {
			store.unsubscribe(call, uri);
			return legacyEmptyResult(rpcRequest.id(), headers);
		}
		if (request.body().length > 16 * 1024)
			return sessionCapacityResponse(429, headers);
		if (!router.resolvesResourceSubscription(uri)) {
			store.unsubscribe(call, uri);
			return legacyResourcePermissionDenied(control.protocolProfile(), rpcRequest.id(), headers);
		}
		McpRequestContext initialContext = control.publicRequestContext().orElse(null);
		if (initialContext == null || subscriptionRuntimeConfiguration.authorizer().isEmpty())
			return observedPolicyHookInternalError(control, rpcRequest.id(), headers, null);
		LegacyUriGrantControl target = new LegacyUriGrantControl(endpoint.path(), control.protocolProfile().revision(),
				uri, partition, initialContext, control.application, control.processor);
		long now = applicationClock.nanoTime();
		McpLegacySessionStore.GrantAllocation allocation = store.beginGrant(call, uri, partition, target,
				now + subscriptionRuntimeConfiguration.maximumSubscriptionDuration().toNanos());
		if (allocation.status() != McpLegacySessionStore.Status.ACCEPTED)
			return sessionStatusResponse(allocation.status(), control.protocolProfile(), rpcRequest.id(), headers);
		McpLegacySessionStore.Grant grant = allocation.grant().orElseThrow();
		target.bind(grant);
		legacyUriGrantControls.add(target);
		if (!control.beginCatalogPolicyEvaluation()) {
			grant.retire(McpLegacySessionStore.GrantCause.AUTHORIZATION_FAILED);
			target.initialFinished(); return null;
		}
		long deadline = minimumDeadline(now, control.deadlineNanos(), now
				+ subscriptionRuntimeConfiguration.authorizationTimeout().toNanos());
		try {
			// Reconciliation may overtake an establishing grant. Each retry uses a
			// fresh cancellation lease but shares this POST's fixed deadline and
			// partition evidence; obsolete callbacks retain their physical holds.
			for (int attempt = 0; attempt < 8; attempt++) {
				long generation = grant.generation();
				McpApplicationExecution.BoundedPolicyCancellation cancellation = control.application.newBoundedPolicyCancellation();
				synchronized (control.lock) { control.legacyHttpCancellation = cancellation; }
				target.bindCancellation(cancellation);
				if (!control.protocolProcessingAllowed()) {
					grant.retireIfCurrent(generation, McpLegacySessionStore.GrantCause.AUTHORIZATION_FAILED);
					return null;
				}
				control.reserveLegacyHttpPolicyWork(); target.callbackEntered();
				try {
					McpSubscriptionAuthorization decision = target.authorize(deadline, cancellation,
							() -> { control.legacyHttpPolicyPhysicallyFinished(); target.callbackExited(); });
					if (!control.protocolProcessingAllowed()) {
						grant.retireIfCurrent(generation, McpLegacySessionStore.GrantCause.AUTHORIZATION_FAILED);
						return null;
					}
					if (grant.current() && grant.generation() != generation) continue;
					if (decision instanceof McpSubscriptionAuthorization.Denied) {
						if (!grant.retireIfCurrent(generation, McpLegacySessionStore.GrantCause.AUTHORIZATION_DENIED) && grant.current()) continue;
						return legacyResourcePermissionDenied(control.protocolProfile(), rpcRequest.id(), headers);
					}
					McpSubscriptionAuthorization.Allowed allowed = (McpSubscriptionAuthorization.Allowed) decision;
					long lease = legacyAuthorizationDeadline(allowed.getValidUntil(), grant.totalDeadlineNanos());
					McpLegacySessionStore.Status committed = grant.commitStatus(generation, lease);
					if (committed != McpLegacySessionStore.Status.ACCEPTED) {
						if (grant.current() && grant.generation() != generation) continue;
						if (committed == McpLegacySessionStore.Status.GLOBAL_CAPACITY || committed == McpLegacySessionStore.Status.OWNER_CAPACITY) grant.abort();
						else grant.retireIfCurrent(generation, McpLegacySessionStore.GrantCause.AUTHORIZATION_FAILED);
						return committed == McpLegacySessionStore.Status.GLOBAL_CAPACITY || committed == McpLegacySessionStore.Status.OWNER_CAPACITY
								? sessionStatusResponse(committed, control.protocolProfile(), rpcRequest.id(), headers)
								: legacyResourcePermissionDenied(control.protocolProfile(), rpcRequest.id(), headers);
					}
					target.accept(allowed, lease, generation + 1L);
					flushLegacyNotifications(endpoint.path(), control.protocolProfile().revision());
					return legacyEmptyResult(rpcRequest.id(), headers);
				} catch (McpApplicationPolicyCapacityException | McpApplicationPolicyDeadlineException exception) {
					grant.abort();
					return control.reserveCatalogPolicyDeadlineResponse() ? sessionCapacityResponse(503, headers) : null;
				} catch (Throwable throwable) {
					if (control.protocolProcessingAllowed() && grant.current() && grant.generation() != generation) continue;
					if (!grant.retireIfCurrent(generation, McpLegacySessionStore.GrantCause.AUTHORIZATION_FAILED) && grant.current()) continue;
					if (!control.protocolProcessingAllowed()) return null;
					return observedPolicyHookInternalError(control, rpcRequest.id(), headers, throwable);
				}
			}
			grant.abort();
			return control.reserveCatalogPolicyDeadlineResponse() ? sessionCapacityResponse(503, headers) : null;
		} finally {
			control.finishCatalogPolicyEvaluation(); target.initialFinished();
		}
	}

	private MicrohttpResponse legacyEmptyResult(McpJsonRpcId id, List<Header> headers) {
		return jsonResponse(200, "OK", McpLegacyResponseWire.encode(jsonCodec,
				new McpJsonRpcMessage.ResultResponse(id, McpWireResult.complete(McpJsonObject.empty()), McpJsonObject.empty())), headers);
	}

	private MicrohttpResponse legacyResourcePermissionDenied(McpProtocolProfile profile, McpJsonRpcId id, List<Header> headers) {
		return profiledJsonRpcError(profile, McpProfileErrorKind.OPERATION, 200, "OK", Optional.of(id),
				new McpJsonRpcError(-32002, "Resource subscription is unavailable.", Optional.empty()), headers);
	}

	private static @Nullable McpResourceNotificationType legacyCatalogFamily(String method) {
		return switch (method) {
			case "tools/list" -> McpResourceNotificationType.TOOLS_LIST_CHANGED;
			case "prompts/list" -> McpResourceNotificationType.PROMPTS_LIST_CHANGED;
			case "resources/list", "resources/templates/list" -> McpResourceNotificationType.RESOURCES_LIST_CHANGED;
			default -> null;
		};
	}

	private void publishLegacySubscriptionEvent(Set<String> endpointPaths, Event event, SubscriptionEventSourceGeneration generation) {
		McpLegacySessionStore store = legacySessionStore;
		if (store == null || event instanceof McpSubscriptionEventSource.Event.TaskChanged) return;
		for (String path : endpointPaths) {
			for (Map.Entry<String, Set<McpResourceNotificationType>> view : legacyTransportFamilies.getOrDefault(path, Map.of()).entrySet()) {
				try {
				String revision = view.getKey();
				if (event instanceof McpSubscriptionEventSource.Event.ResourceUpdated updated) {
					if (view.getValue().contains(McpResourceNotificationType.RESOURCE_UPDATED))
						store.markResourceDirty(path, revision, updated.wireResourceUri(), generation::active);
				} else {
					Set<McpResourceNotificationType> changed = EnumSet.noneOf(McpResourceNotificationType.class);
					if (event instanceof McpSubscriptionEventSource.Event.ToolsListChanged) changed.add(McpResourceNotificationType.TOOLS_LIST_CHANGED);
					if (event instanceof McpSubscriptionEventSource.Event.PromptsListChanged) changed.add(McpResourceNotificationType.PROMPTS_LIST_CHANGED);
					if (event instanceof McpSubscriptionEventSource.Event.ResourcesListChanged) changed.add(McpResourceNotificationType.RESOURCES_LIST_CHANGED);
					if (event instanceof McpSubscriptionEventSource.Event.LocalizationCatalogsChanged catalogs) {
						if (catalogs.tools()) changed.add(McpResourceNotificationType.TOOLS_LIST_CHANGED);
						if (catalogs.prompts()) changed.add(McpResourceNotificationType.PROMPTS_LIST_CHANGED);
						if (catalogs.resources()) changed.add(McpResourceNotificationType.RESOURCES_LIST_CHANGED);
					}
					for (McpResourceNotificationType family : changed)
						if (view.getValue().contains(family) && legacyCatalogCanChange(path, revision, family))
							store.markCatalogDirty(path, revision, family, generation::active);
				}
				flushLegacyNotifications(path, revision);
				} catch (Throwable ignored) { /* One view cannot alter publisher or peer delivery. */ }
			}
		}
	}

	private boolean legacyCatalogCanChange(String path, String revision, McpResourceNotificationType family) {
		EndpointRuntime runtime = endpointsByPath.get(path);
		if (runtime == null) return false;
		McpNormalizedEndpoint endpoint = runtime.binding().revisionEndpoint(revision).orElseThrow();
		if (family == McpResourceNotificationType.RESOURCES_LIST_CHANGED && endpoint.customResourceListHandler()) return true;
		if (family != McpResourceNotificationType.RESOURCES_LIST_CHANGED && endpoint.catalogAccessAdapter().isPresent()) return true;
		Map<McpLegacyCatalogPager.Kind, McpLegacyCatalogPager> pagers = runtime.pagersByRevision().getOrDefault(revision, Map.of());
		McpLegacyCatalogPager.Kind selected = switch (family) {
			case TOOLS_LIST_CHANGED -> McpLegacyCatalogPager.Kind.TOOLS;
			case PROMPTS_LIST_CHANGED -> McpLegacyCatalogPager.Kind.PROMPTS;
			case RESOURCES_LIST_CHANGED -> McpLegacyCatalogPager.Kind.RESOURCES;
			default -> null;
		};
		return selected != null && (Optional.ofNullable(pagers.get(selected)).map(McpLegacyCatalogPager::hasLocalizableOwners).orElse(false)
				|| family == McpResourceNotificationType.RESOURCES_LIST_CHANGED
				&& Optional.ofNullable(pagers.get(McpLegacyCatalogPager.Kind.TEMPLATES)).map(McpLegacyCatalogPager::hasLocalizableOwners).orElse(false));
	}

	/** Snapshot offers select one newest eligible writer and retain only coalesced invalidation state. */
	private void flushLegacyNotifications(String path, String revision) {
		McpLegacySessionStore store = legacySessionStore;
		if (store == null) return;
		for (McpLegacySessionStore.Delivery delivery : store.pendingDeliveries(path, revision)) {
			try { offerLegacyDelivery(store, delivery, revision); }
			catch (Throwable ignored) { /* A failed offer preserves its dirty bit for a later bounded retry. */ }
		}
	}

	private void offerLegacyDelivery(McpLegacySessionStore store, McpLegacySessionStore.Delivery delivery, String revision) {
		for (McpLegacySessionStore.Get get : delivery.eligibleGets()) {
			if (!(get.target().orElse(null) instanceof LegacyGetControl target)) continue;
			Optional<McpLegacySessionStore.DeliveryAttempt> selected = delivery.forGet(get);
			if (selected.isEmpty()) continue;
			McpLegacySessionStore.DeliveryAttempt attempt = selected.orElseThrow();
			McpJsonRpcMessage.Notification message = legacyNotification(delivery.notificationType(), delivery.uri());
			long encodedBytes = (long) McpRequestSseStream.encodeMessage(envelopeCodec, jsonCodec,
					protocolProfiles.resolve(revision).orElseThrow(), message).length + 8L;
			Optional<McpLegacySessionStore.NotificationReservation> reservation = store.reserveNotificationBytes(attempt, encodedBytes);
			if (reservation.isEmpty()) {
				if (attempt.valid()) shedLegacyNotificationPressure(store, get, encodedBytes);
				continue;
			}
			McpLegacySessionStore.NotificationReservation retained = reservation.orElseThrow();
			Optional<McpOutboundChannel.OfferResult> offered;
			try { offered = target.offerNotification(message, List.of(delivery.notificationType(), delivery.uri()), retained); }
			catch (Throwable failure) { retained.release(); throw failure; }
			if (offered.isPresent() && offered.orElseThrow() == McpOutboundChannel.OfferResult.ACCEPTED) {
				store.acknowledgeOffered(attempt); break;
			}
			// The existing keyed frame may precede a newer URI invalidation. Keep that newer bit pending.
			if (offered.isPresent() && offered.orElseThrow() == McpOutboundChannel.OfferResult.COALESCED) break;
			if (offered.isPresent() && (offered.orElseThrow() == McpOutboundChannel.OfferResult.FULL
					|| offered.orElseThrow() == McpOutboundChannel.OfferResult.TOO_LARGE)) target.shedNotificationPressure();
		}
	}

	private void shedLegacyNotificationPressure(McpLegacySessionStore store, McpLegacySessionStore.Get attempted, long bytes) {
		boolean ownerPressure = store.notificationOwnerCapacityExceeded(attempted, bytes);
		LegacyGetControl largest = null;
		long largestBytes = 0L;
		for (LegacyGetControl candidate : legacyGetControls.values()) {
			McpLegacySessionStore.Get registration;
			McpRequestSseStream stream;
			synchronized (candidate.control.lock) {
				registration = candidate.closing ? null : candidate.registration; stream = candidate.stream;
			}
			if (registration == null || stream == null || ownerPressure && !registration.owner().equals(attempted.owner())
					|| store.queuedNotificationBytes(registration) == 0L) continue;
			long pendingBytes = stream.snapshot().map(snapshot -> (long) snapshot.bufferedBytes()).orElse(0L);
			if (pendingBytes > largestBytes) { largest = candidate; largestBytes = pendingBytes; }
		}
		if (largest != null) largest.shedNotificationPressure();
	}

	private McpJsonRpcMessage.Notification legacyNotification(McpResourceNotificationType type, Optional<String> uri) {
		String method = switch (type) {
			case RESOURCE_UPDATED -> "notifications/resources/updated";
			case RESOURCES_LIST_CHANGED -> "notifications/resources/list_changed";
			case TOOLS_LIST_CHANGED -> "notifications/tools/list_changed";
			case PROMPTS_LIST_CHANGED -> "notifications/prompts/list_changed";
		};
		return new McpJsonRpcMessage.Notification(method,
				uri.map(value -> new McpJsonObject(Map.of("uri", new McpJsonString(value)))), McpJsonObject.empty());
	}

	private void recheckLegacyWriters(String path, String revision) {
		for (LegacyGetControl get : legacyGetControls.values())
			if (get.endpoint.path().equals(path) && get.revision.equals(revision)) get.recheckNotifications();
	}

	private boolean validLegacySessionFraming(MicrohttpRequest request, boolean initialize) {
		List<String> values = headerValues(request, MCP_SESSION_ID);
		return values.isEmpty() ? initialize
				: values.size() == 1 && McpLegacySessionStore.validSessionId(values.get(0));
	}

	private MicrohttpResponse remapSessionAdmissionRejection(MicrohttpResponse response,
			boolean sessionEnabled) {
		return sessionEnabled && Set.of(400, 404, 405).contains(response.status())
				? new MicrohttpResponse(403, "Forbidden", response.headers(), response.body()) : response;
	}

	private long sessionRequestEvidenceBytes(MicrohttpRequest request) {
		long bytes = request.body().length;
		for (Header header : request.headers())
			bytes = Math.addExact(bytes, (long) utf8Size(header.name()) + utf8Size(header.value()) + 4L);
		return Math.addExact(bytes, 64L);
	}

	private McpLegacySessionStore.Owner resolveSessionOwner(RequestControl control,
			McpAdmissionIdentity identity, McpApplicationExecution application) throws Exception {
		return application.invokeBoundedSessionOwnerPolicy(() -> new McpLegacySessionStore.Owner(
				requireNonNull(legacySessionOwnerResolver).resolve(identity), !identity.authenticated()),
				control.deadlineNanos());
	}

	private @Nullable MicrohttpResponse bindLegacySession(MicrohttpRequest request, String path,
			String revision, McpJsonRpcMessage.Request mappedRequest, McpAdmissionIdentity identity,
			boolean initialize, RequestControl control, McpApplicationExecution application,
			List<Header> headers) {
		if (!identity.authenticated() && !legacyAnonymousSessionsAllowed)
			return emptyResponse(403, "Forbidden", headers);
		if (!control.beginCatalogPolicyEvaluation())
			return null;
		McpLegacySessionStore.Owner owner;
		try {
			owner = resolveSessionOwner(control, identity, application);
			control.finishCatalogPolicyEvaluation();
		} catch (McpApplicationPolicyCapacityException | McpApplicationPolicyDeadlineException exception) {
			control.reserveCatalogPolicyDeadlineResponse();
			return sessionCapacityResponse(503, headers);
		} catch (Throwable throwable) {
			control.finishCatalogPolicyEvaluation();
			return policyHookInternalError(control.protocolProfile(), mappedRequest.id(), headers);
		}
		if (!control.protocolProcessingAllowed())
			return null;
		McpLegacySessionStore store = requireNonNull(legacySessionStore);
		control.bindLegacyRequestId(mappedRequest.id());
		McpLegacySessionStore.Target target = control.legacySessionTarget(initialize);
		if (initialize) {
			McpRequestMetadata metadata = mappedRequest.params().metadata();
			McpLegacySessionStore.Allocation allocation = store.publish(owner, path, revision,
					application, new McpLegacySessionStore.Snapshot(metadata.clientCapabilities(),
							metadata.clientInformation()), target, sessionRequestEvidenceBytes(request));
			if (allocation.status() != McpLegacySessionStore.Status.ACCEPTED)
				return sessionStatusResponse(allocation.status(), control.protocolProfile(),
						mappedRequest.id(), headers);
			McpLegacySessionStore.Initialization initialization = allocation.initialization().orElseThrow();
			if (!control.attachLegacyInitialization(initialization)) {
				initialization.deliveryFailed();
				initialization.physicalComplete();
			}
		} else {
			McpLegacySessionStore.Acquisition acquisition = store.acquire(
					singleHeader(request, MCP_SESSION_ID).orElseThrow(), owner, path, revision,
					application, mappedRequest.id(), mappedRequest.params().metadata().progressToken().orElse(null),
					target, sessionRequestEvidenceBytes(request));
			if (acquisition.status() != McpLegacySessionStore.Status.ACCEPTED)
				return sessionStatusResponse(acquisition.status(), control.protocolProfile(),
						mappedRequest.id(), headers);
			McpLegacySessionStore.Call call = acquisition.call().orElseThrow();
			if (!control.attachLegacyCall(call)) {
				call.logicalComplete();
				call.physicalComplete();
			} else if (!call.acceptedUse()) {
				return emptyResponse(404, "Not Found", headers);
			}
		}
		return null;
	}

	private MicrohttpResponse sessionCapacityResponse(int status, List<Header> headers) {
		List<Header> responseHeaders = new ArrayList<>(headers);
		responseHeaders.add(new Header(RETRY_AFTER, "1"));
		return emptyResponse(status, status == 429 ? "Too Many Requests" : "Service Unavailable",
				List.copyOf(responseHeaders));
	}

	private MicrohttpResponse sessionStatusResponse(McpLegacySessionStore.Status status,
			McpProtocolProfile profile, McpJsonRpcId id, List<Header> headers) {
		return switch (status) {
			case NOT_FOUND -> emptyResponse(404, "Not Found", headers);
			case REVISION_MISMATCH, INVALID_ID -> emptyResponse(400, "Bad Request", headers);
			case ANONYMOUS_DENIED, PARTITION_MISMATCH, AUTHORIZATION_EXPIRED -> emptyResponse(403, "Forbidden", headers);
			case ACTIVE_ID_COLLISION -> profiledJsonRpcError(profile, McpProfileErrorKind.CONTROL,
					200, "OK", Optional.of(id), new McpJsonRpcError(McpJsonRpcError.INVALID_REQUEST,
							"Invalid Request", Optional.empty()), headers);
			case METADATA_TOO_LARGE -> profiledJsonRpcError(profile, McpProfileErrorKind.CONTROL,
					200, "OK", Optional.of(id), new McpJsonRpcError(McpJsonRpcError.INVALID_PARAMS,
							"Invalid params", Optional.empty()), headers);
			case CALL_CAPACITY, OWNER_CAPACITY -> sessionCapacityResponse(429, headers);
			case GLOBAL_CAPACITY, STOPPED -> sessionCapacityResponse(503, headers);
			case INTERNAL_FAILURE -> policyHookInternalError(profile, id, headers);
			case ACCEPTED -> throw new IllegalArgumentException("Accepted sessions have no failure response.");
		};
	}

	private boolean observeNotification(RequestControl control, McpHttpEndpointBinding binding,
			Request request, McpJsonRpcEnvelope.Notification notification, String revision,
			McpNotificationMetadataValidation metadata, McpAdmissionIdentity identity) {
		McpLegacySessionStore.Snapshot snapshot = control.legacySnapshot;
		return control.startObservation(binding.observationSink(), new McpRuntimeRequestInput(
				request, Map.of(), notification.method(), Optional.empty(), revision, Optional.empty(),
				snapshot == null ? Optional.empty() : snapshot.clientInformation(),
				snapshot == null ? McpJsonObject.empty() : snapshot.clientCapabilities().toJsonObject(),
				metadata.metadata().orElseGet(McpJsonObject::empty), McpJsonObject.empty(), Optional.empty(),
				control.acceptLanguageValues(), identity));
	}

	private @Nullable McpJsonRpcId legacyCancellationTarget(McpJsonRpcEnvelope.Notification notification) {
		if (!(notification.params().orElse(null) instanceof McpJsonObject params)) return null;
		McpJsonValue value = params.members().get("requestId");
		if (value instanceof McpJsonString string) return new McpJsonRpcId.StringId(string.value());
		if (value instanceof McpJsonNumber number) {
			try { return new McpJsonRpcId.IntegerId(number.value().toBigIntegerExact()); }
			catch (ArithmeticException ignored) { return null; }
		}
		return null;
	}

	private @Nullable MicrohttpResponse processNotification(
			@NonNull MicrohttpRequest request,
			@NonNull Request sokletRequest,
			McpJsonRpcEnvelope.@NonNull Notification notification,
			@NonNull List<@NonNull Header> corsHeaders,
			@NonNull RequestControl requestControl,
			@NonNull EndpointRuntime endpointRuntime,
			McpLegacyHttpWire.@NonNull Era wireEra) {
		McpHttpEndpointBinding endpointBinding = endpointRuntime.binding();
		McpHttpEndpointPolicy endpointPolicy = endpointBinding.endpointPolicy();
		boolean cancellationNotification =
				"notifications/cancelled".equals(notification.method());
		boolean initializedNotification = wireEra == McpLegacyHttpWire.Era.LEGACY
				&& "notifications/initialized".equals(notification.method());

		List<String> protocolVersions = headerValues(request, MCP_PROTOCOL_VERSION);
		if (protocolVersions.size() != 1)
			return emptyResponse(400, "Bad Request", corsHeaders);
		String protocolVersion = protocolVersions.get(0);
		try {
			mirroredHeaderCodec.requirePlainString(protocolVersion);
		} catch (IllegalArgumentException exception) {
			return emptyResponse(400, "Bad Request", corsHeaders);
		}
		Optional<McpProtocolProfile> selectedProfile =
				this.protocolProfiles.resolve(protocolVersion);
		if (selectedProfile.isEmpty()
				|| endpointBinding.revisionEndpoint(protocolVersion).isEmpty()
				|| (wireEra == McpLegacyHttpWire.Era.LEGACY)
						!= McpLegacyHttpWire.isLegacyRevision(protocolVersion))
			return emptyResponse(400, "Bad Request", corsHeaders);
		McpProtocolProfile protocolProfile = selectedProfile.orElseThrow();
		McpNormalizedEndpoint endpoint = endpointBinding.revisionEndpoint(
				protocolVersion).orElseThrow();
		requestControl.bindProtocolProfile(protocolProfile);
		boolean sessionEnabled = wireEra == McpLegacyHttpWire.Era.LEGACY
				&& sessionsEnabled(endpointRuntime.path(), protocolVersion);
		requestControl.legacySessionSelected = sessionEnabled;
		if (sessionEnabled && !validLegacySessionFraming(request, false))
			return emptyResponse(400, "Bad Request", corsHeaders);
		McpNotificationMetadataValidation metadataValidation = protocolProfile
				.validateNotificationMetadata(notification);

		if (!cancellationNotification && !metadataValidation.valid())
			return emptyResponse(400, "Bad Request", corsHeaders);
		if (initializedNotification && notification.params().isPresent()
				&& (!(notification.params().orElseThrow() instanceof McpJsonObject params)
				|| !Set.of("_meta").containsAll(params.members().keySet())))
			return emptyResponse(400, "Bad Request", corsHeaders);

		if (!requestControl.protocolProcessingAllowed())
			return null;
		McpAdmissionContext admissionContext = new McpAdmissionContext(
				sokletRequest, endpoint, Map.of(), notification.method(), true,
				Optional.empty(), protocolVersion, Optional.empty(), Optional.empty(),
				Optional.empty(), false, false, false, false, List.of(),
				false, List.of(), metadataValidation.metadata());
		Optional<McpAdmissionDecision> admissionResult;
		try {
			admissionResult = Optional.ofNullable(
					endpointPolicy.protocolAdmissionController().admit(admissionContext));
		} catch (Throwable throwable) {
			return emptyResponse(500, "Internal Server Error", corsHeaders);
		}
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (admissionResult.isEmpty())
			return emptyResponse(500, "Internal Server Error", corsHeaders);
		McpAdmissionDecision admissionDecision = admissionResult.orElseThrow();

		if (admissionDecision instanceof McpAdmissionDecision.Rejected rejected) {
			try {
				requestControl.legacyAdmissionRejected = sessionEnabled;
				return remapSessionAdmissionRejection(notificationAdmissionRejection(
						rejected.rejection(), corsHeaders), sessionEnabled);
			} catch (IllegalArgumentException exception) {
				return emptyResponse(500, "Internal Server Error", corsHeaders);
			}
		}
		McpAdmissionIdentity admittedIdentity =
				((McpAdmissionDecision.Accepted) admissionDecision).identity();
		McpEffectiveAdmissionIdentity effectiveIdentity =
				McpEffectiveAdmissionIdentity.resolve(endpoint, endpointPolicy.path(),
						admittedIdentity);
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (!sessionEnabled && !observeNotification(requestControl, endpointBinding, sokletRequest,
				notification, protocolVersion, metadataValidation, effectiveIdentity.admittedIdentity()))
			return null;

		if (endpointPolicy.requestRateLimiter().isPresent()) {
			Optional<McpRateLimitDecision> rateLimitResult;
			try {
				rateLimitResult = Optional.ofNullable(endpointPolicy.requestRateLimiter().orElseThrow().acquire(
						new McpRateLimitContext(sokletRequest, endpoint, protocolVersion,
								effectiveIdentity,
								McpRateLimitTarget.REQUEST, notification.method(),
								Optional.empty())));
			} catch (Throwable throwable) {
				if (sessionEnabled && !observeNotification(requestControl, endpointBinding, sokletRequest,
						notification, protocolVersion, metadataValidation, effectiveIdentity.admittedIdentity()))
					return null;
				requestControl.planRequestObservation(new RequestObservationResult(
						McpRequestOutcome.INTERNAL_ERROR, null, List.of(throwable)));
				return emptyResponse(500, "Internal Server Error", corsHeaders);
			}
			if (!requestControl.protocolProcessingAllowed())
				return null;
			if (rateLimitResult.isEmpty()) {
				if (sessionEnabled && !observeNotification(requestControl, endpointBinding, sokletRequest,
						notification, protocolVersion, metadataValidation, effectiveIdentity.admittedIdentity()))
					return null;
				requestControl.planRequestObservation(new RequestObservationResult(
						McpRequestOutcome.INTERNAL_ERROR, null, List.of()));
				return emptyResponse(500, "Internal Server Error", corsHeaders);
			}
			McpRateLimitDecision rateLimitDecision = rateLimitResult.orElseThrow();
			if (rateLimitDecision instanceof McpRateLimitDecision.Denied denied) {
				if (sessionEnabled && !observeNotification(requestControl, endpointBinding, sokletRequest,
						notification, protocolVersion, metadataValidation, effectiveIdentity.admittedIdentity()))
					return null;
				requestControl.planRequestObservation(new RequestObservationResult(
						McpRequestOutcome.REJECTED, null, List.of()));
				return notificationRateLimited(denied.retryAfter(), corsHeaders);
			}
		}

		if (sessionEnabled && (cancellationNotification || initializedNotification)) {
			if (!admittedIdentity.authenticated() && !legacyAnonymousSessionsAllowed)
				return emptyResponse(403, "Forbidden", corsHeaders);
			McpLegacySessionStore.Owner owner;
			if (!requestControl.beginCatalogPolicyEvaluation()) return null;
			try {
				owner = resolveSessionOwner(requestControl, admittedIdentity, requestControl.application);
				requestControl.finishCatalogPolicyEvaluation();
			} catch (McpApplicationPolicyCapacityException | McpApplicationPolicyDeadlineException exception) {
				requestControl.reserveCatalogPolicyDeadlineResponse();
				return sessionCapacityResponse(503, corsHeaders);
			} catch (Throwable throwable) {
				requestControl.finishCatalogPolicyEvaluation();
				return emptyResponse(500, "Internal Server Error", corsHeaders);
			}
			if (!requestControl.protocolProcessingAllowed()) return null;
			McpLegacySessionStore store = requireNonNull(legacySessionStore);
			McpLegacySessionStore.Acquisition acquisition = store.acquire(
					singleHeader(request, MCP_SESSION_ID).orElseThrow(), owner, endpointRuntime.path(),
					protocolVersion, requestControl.application, null, null,
					requestControl.legacySessionTarget(false), sessionRequestEvidenceBytes(request));
			if (acquisition.status() != McpLegacySessionStore.Status.ACCEPTED) {
				return switch (acquisition.status()) {
					case NOT_FOUND -> emptyResponse(404, "Not Found", corsHeaders);
					case REVISION_MISMATCH, INVALID_ID -> emptyResponse(400, "Bad Request", corsHeaders);
					case OWNER_CAPACITY, CALL_CAPACITY -> sessionCapacityResponse(429, corsHeaders);
					case GLOBAL_CAPACITY, STOPPED -> sessionCapacityResponse(503, corsHeaders);
					default -> emptyResponse(500, "Internal Server Error", corsHeaders);
				};
			}
			McpLegacySessionStore.Call call = acquisition.call().orElseThrow();
			if (!requestControl.attachLegacyCall(call)) {
				call.logicalComplete(); call.physicalComplete(); return null;
			}
			if (!call.acceptedUse()) return emptyResponse(404, "Not Found", corsHeaders);
			if (!observeNotification(requestControl, endpointBinding, sokletRequest, notification,
					protocolVersion, metadataValidation, effectiveIdentity.admittedIdentity())) return null;
			if (initializedNotification) store.acknowledge(call);
			else {
				McpJsonRpcId target = legacyCancellationTarget(notification);
				if (target != null) store.cancel(call, target);
			}
		}
		return cancellationNotification || initializedNotification
				? emptyResponse(202, "Accepted", corsHeaders)
				: emptyResponse(400, "Bad Request", corsHeaders);
	}

	@NonNull
	private static RequestObservationResult requestObservationResult(
			@NonNull MicrohttpResponse response) {
		requireNonNull(response);
		McpRequestOutcome outcome;
		if (response.status() >= 200 && response.status() < 300)
			outcome = McpRequestOutcome.COMPLETE;
		else if (response.status() == 429 || response.status() == 503)
			outcome = McpRequestOutcome.REJECTED;
		else if (response.status() == 504)
			outcome = McpRequestOutcome.DEADLINE_EXCEEDED;
		else if (response.status() >= 500)
			outcome = McpRequestOutcome.INTERNAL_ERROR;
		else
			outcome = McpRequestOutcome.PROTOCOL_ERROR;
		return new RequestObservationResult(outcome, null, List.of());
	}

	@NonNull
	private static RequestObservationResult requestObservationResult(
			@NonNull McpApplicationResponse response) {
		requireNonNull(response);
		if (response.message().orElse(null)
				instanceof McpJsonRpcMessage.ErrorResponse errorResponse) {
			return new RequestObservationResult(response.outcome(),
					errorResponse.error(),
					response.throwables());
		}
		return new RequestObservationResult(response.outcome(), null,
				response.throwables());
	}

	@NonNull
	private static RequestObservationResult requestObservationResult(
			@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
		McpRequestOutcome outcome = switch (requireNonNull(reason)) {
			case COMPLETED -> McpRequestOutcome.COMPLETE;
			case CLIENT_DISCONNECTED -> McpRequestOutcome.CLIENT_DISCONNECTED;
			case RESPONSE_TIMEOUT -> McpRequestOutcome.DEADLINE_EXCEEDED;
			case RESPONSE_IDLE_TIMEOUT, WRITE_FAILED -> McpRequestOutcome.WRITE_FAILED;
			case CLEANUP_TIMEOUT, PRODUCER_FAILED, INTERNAL_ERROR, UNKNOWN ->
					McpRequestOutcome.INTERNAL_ERROR;
			case SERVER_STOPPING, PROTOCOL_UNSUPPORTED, APPLICATION_CANCELED, CLIENT_CANCELED,
					BACKPRESSURE, SIMULATOR_LIMIT_EXCEEDED ->
					McpRequestOutcome.CANCELED;
		};
		return new RequestObservationResult(outcome, null,
				cause == null ? List.of() : List.of(cause));
	}

	@NonNull
	private ApplicationResponseRendering renderApplicationResponse(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpApplicationResponse response,
			@NonNull McpJsonRpcId requestId,
			@NonNull List<@NonNull Header> additionalHeaders,
			@Nullable McpRequestContext requestContext) {
		requireNonNull(response);
		requireNonNull(requestId);
		requireNonNull(additionalHeaders);

		MicrohttpResponse httpResponse;
		RequestObservationResult observationResult;
		try {
			if (response.message().isPresent()) {
				McpJsonRpcMessage message = response.message().orElseThrow();
				byte[] encodedMessage = message instanceof McpJsonRpcMessage.ResultResponse result
						&& McpLegacyHttpWire.isLegacyRevision(protocolProfile.revision())
						? McpLegacyResponseWire.encode(jsonCodec, result)
						: envelopeCodec.encode(message);
				if (message instanceof McpJsonRpcMessage.ErrorResponse errorResponse)
					recordProtocolErrorAfterSuccessfulEncoding(
							errorResponse.error().code(), requestContext);
				httpResponse = jsonResponse(response.status(), response.reason(),
						encodedMessage, additionalHeaders);
			} else {
				httpResponse = emptyResponse(response.status(), response.reason(),
						additionalHeaders);
			}
			observationResult = requestObservationResult(response);
		} catch (Throwable throwable) {
			McpJsonRpcError canonicalError = new McpJsonRpcError(
					McpJsonRpcError.INTERNAL_ERROR, "Internal error", Optional.empty());
			McpJsonRpcError renderedError = requireNonNull(protocolProfile
					.renderFrameworkError(McpProfileErrorKind.CONTROL,
							canonicalError));
			httpResponse = jsonRpcError(500, "Internal Server Error",
					Optional.of(requestId), renderedError, additionalHeaders,
					requestContext);
			List<Throwable> throwables = new ArrayList<>(response.throwables());
			throwables.add(throwable);
			observationResult = new RequestObservationResult(
					McpRequestOutcome.INTERNAL_ERROR, renderedError, throwables);
		}

		return new ApplicationResponseRendering(httpResponse, observationResult);
	}

	/**
	 * Publishes one framework-owned catalog response, localizing it first when a
	 * localizer is configured.
	 * <p>
	 * Without a localizer this is exactly the original canonical encode, so the
	 * wire bytes and the work done to produce them are both unchanged.
	 */
	private static boolean validFrameworkCatalogParams(@NonNull McpJsonObject fields,
			boolean legacy) {
		if (!legacy)
			return fields.members().isEmpty();
		if (!Set.of("cursor").containsAll(fields.members().keySet()))
			return false;
		McpJsonValue cursor = fields.members().get("cursor");
		return cursor == null || cursor instanceof McpJsonString string
				&& McpLegacyCatalogPager.decode(string.value()).isPresent();
	}

	@Nullable
	private MicrohttpResponse pagedCatalogResponse(@NonNull EndpointRuntime endpointRuntime,
			@NonNull McpProtocolProfile profile,
			McpJsonRpcMessage.@NonNull Request mappedRequest,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull RequestControl control, @NonNull List<@NonNull Header> headers,
			@NonNull McpApplicationExecution application) {
		McpRequestContext requestContext = control.publicRequestContext().orElse(null);
		McpLegacyCatalogPager.Kind kind = McpLegacyCatalogPager.Kind
				.forMethod(mappedRequest.method()).orElseThrow();
		if (requestContext == null
				&& (kind == McpLegacyCatalogPager.Kind.TOOLS || kind == McpLegacyCatalogPager.Kind.PROMPTS)
				&& endpointRuntime.binding().endpoint().catalogAccessAdapter().isPresent())
			return observedPolicyHookInternalError(control, mappedRequest.id(), headers, null);
		McpLegacyCatalogPager pager = endpointRuntime.pagersByRevision()
				.get(profile.revision()).get(kind);
		McpJsonValue cursorValue = mappedRequest.params().fields().members().get("cursor");
		McpLegacyCatalogPager.Cursor cursor = cursorValue instanceof McpJsonString string
				? McpLegacyCatalogPager.decode(string.value()).orElseThrow() : null;
		if (!control.beginCatalogPolicyEvaluation())
			return null;
		LegacyPageOutcome outcome;
		try {
			outcome = application.invokeBoundedPolicy(() -> renderLegacyPage(pager,
					endpointRuntime, mappedRequest.id(), cursor, endpointPolicy,
					control, requestContext), control.deadlineNanos(), control.catalogAccessCancellation,
					control::releaseLegacyPhysicalIfComplete);
			control.finishCatalogPolicyEvaluation();
		} catch (McpApplicationPolicyCapacityException exception) {
			control.finishCatalogPolicyEvaluation();
			return control.protocolProcessingAllowed()
					? observedPolicyCapacityRejected(control, mappedRequest.id(), headers) : null;
		} catch (McpApplicationPolicyDeadlineException exception) {
			return control.reserveCatalogPolicyDeadlineResponse()
					? observedPolicyDeadline(control, mappedRequest.id(), headers, exception.queued()) : null;
		} catch (Throwable throwable) {
			control.finishCatalogPolicyEvaluation();
			return control.protocolProcessingAllowed()
					? observedPolicyHookInternalError(control, mappedRequest.id(), headers, throwable) : null;
		}
		if (!control.protocolProcessingAllowed())
			return null;
		if (outcome.invalidCursor())
			return invalidParams(profile, mappedRequest, headers);
		if (outcome.failed())
			return observedPolicyHookInternalError(control, mappedRequest.id(), headers, null);
		return jsonResponse(200, "OK", jsonCodec.toUtf8Bytes(
				McpLegacyCatalogPager.envelope(mappedRequest.id(), outcome.document())),
				withContentLanguage(headers, outcome.contentLanguage()));
	}

	private LegacyPageOutcome renderLegacyPage(McpLegacyCatalogPager pager,
			EndpointRuntime endpointRuntime, McpJsonRpcId requestId,
			McpLegacyCatalogPager.@Nullable Cursor cursor, McpHttpEndpointPolicy endpointPolicy,
			RequestControl control, @Nullable McpRequestContext requestContext) throws Exception {
		McpLegacyCatalogPager.Kind kind = pager.kind();
		Optional<CatalogAccessSession> accessSession = Optional.empty();
		Optional<CatalogAccessAdapter> accessAdapter = endpointRuntime.binding().endpoint()
				.catalogAccessAdapter();
		if ((kind == McpLegacyCatalogPager.Kind.TOOLS || kind == McpLegacyCatalogPager.Kind.PROMPTS)
				&& accessAdapter.isPresent())
			accessSession = Optional.of(requireNonNull(accessAdapter.orElseThrow().open(
					new CatalogAccessInput(requireNonNull(requestContext), control.catalogAccessCancelationToken(),
							control::isTerminalCanceledOrPastDeadline, control.acceptLanguageValues(),
							Optional.empty(), new AtomicReference<>()))));
		Optional<CatalogAccessSession> policy = accessSession;
		Optional<McpRuntimeCatalogLocalizer> localizer = endpointPolicy.catalogLocalizer()
				.filter(value -> requestContext != null && pager.hasLocalizableOwners());
		McpJsonObject empty = pager.document(List.of(), false, "");
		long emptyDocumentBytes = jsonCodec.toUtf8Bytes(empty).length;
		McpJsonObject emptyEnvelope = McpLegacyCatalogPager.envelope(requestId, empty);
		long emptyEnvelopeBytes = jsonCodec.toUtf8Bytes(emptyEnvelope).length;
		Optional<McpRuntimeCatalogLocalizer.PageSession> localization = localizer.map(value ->
				requireNonNull(value.openPage(pageLocalizationInput(kind, requireNonNull(requestContext), empty,
						emptyDocumentBytes, emptyEnvelopeBytes - emptyDocumentBytes,
						endpointPolicy, control, policy))));
		// A provider-initialization failure obeys localization failure policy
		// before a continuation is compared against an unavailable locale.
		if (localization.isPresent() && localization.orElseThrow().localize(
				pageLocalizationInput(kind, requireNonNull(requestContext), empty, emptyDocumentBytes,
						emptyEnvelopeBytes - emptyDocumentBytes, endpointPolicy, control, policy))
				.disposition() == McpRuntimeCatalogLocalizer.Disposition.FAIL_REQUEST)
			return new LegacyPageOutcome(empty, Optional.empty(), false, true);
		String locale = localization.flatMap(McpRuntimeCatalogLocalizer.PageSession::negotiatedLocale)
				.orElse("");
		McpLegacyCatalogPager.Page page = pager.select(cursor, locale, emptyEnvelopeBytes,
				McpLegacyCatalogPager.nodes(emptyEnvelope), jsonLimits.maximumOutputBytes(),
				jsonLimits.maximumNodeCount(), localizer.map(McpRuntimeCatalogLocalizer::maximumPageSlotCount)
						.orElse(Integer.MAX_VALUE),
				owner -> localizer.map(value -> value.ownerSlotCount(kind.responseKind, owner)).orElse(0),
				owner -> {
					if (control.isTerminalCanceledOrPastDeadline())
						throw new InterruptedException("Catalog page request ended.");
					return policy.isEmpty() || (kind == McpLegacyCatalogPager.Kind.TOOLS
							? policy.orElseThrow().isToolAccessible(owner)
							: policy.orElseThrow().isPromptAccessible(owner));
				});
		if (page.invalidCursor())
			return new LegacyPageOutcome(empty, Optional.empty(), true, false);
		if (page.oversizedEntry())
			return new LegacyPageOutcome(empty, Optional.empty(), false, true);
		List<McpLegacyCatalogPager.Entry> prefix = page.entries();
		for (;;) {
			boolean more = page.more() || prefix.size() < page.entries().size();
			McpJsonObject document = pager.document(prefix, more, locale);
			byte[] canonical;
			try {
				canonical = jsonCodec.toUtf8Bytes(McpLegacyCatalogPager.envelope(requestId, document));
			} catch (IllegalArgumentException exception) {
				if (prefix.size() <= 1)
					return new LegacyPageOutcome(empty, Optional.empty(), false, true);
				prefix = prefix.subList(0, prefix.size() / 2);
				continue;
			}
			if (localization.isEmpty())
				return new LegacyPageOutcome(document, Optional.empty(), false, false);
			long documentBytes = jsonCodec.toUtf8Bytes(document).length;
			McpRuntimeCatalogLocalizer.Outcome localized = requireNonNull(localization.orElseThrow()
					.localize(pageLocalizationInput(kind, requireNonNull(requestContext), document, documentBytes,
							canonical.length - documentBytes, endpointPolicy, control, policy)));
			if (localized.disposition() == McpRuntimeCatalogLocalizer.Disposition.RESIZE_PAGE) {
				if (prefix.size() <= 1)
					return new LegacyPageOutcome(empty, Optional.empty(), false, true);
				prefix = prefix.subList(0, prefix.size() / 2);
				continue;
			}
			if (localized.disposition() == McpRuntimeCatalogLocalizer.Disposition.FAIL_REQUEST)
				return new LegacyPageOutcome(empty, Optional.empty(), false, true);
			return new LegacyPageOutcome(localized.document(), localized.contentLanguage(), false, false);
		}
	}

	private McpRuntimeCatalogLocalizer.Input pageLocalizationInput(McpLegacyCatalogPager.Kind kind,
			McpRequestContext requestContext, McpJsonObject document, long documentBytes,
			long envelopeBytes, McpHttpEndpointPolicy endpointPolicy, RequestControl control,
			Optional<CatalogAccessSession> policy) {
		return new McpRuntimeCatalogLocalizer.Input(endpointPolicy.path(), kind.responseKind,
				requestContext, document, documentBytes, envelopeBytes, jsonLimits.maximumOutputBytes(),
				maximumLocalizedReplacementCharacters(), value -> {
					try { return jsonCodec.toUtf8Bytes(value).length; }
					catch (IllegalArgumentException exception) { return Long.MAX_VALUE; }
				}, control.acceptLanguageValues(), List.of(), control::isTerminalCanceledOrPastDeadline,
				policy.flatMap(CatalogAccessSession::localizationContext));
	}

	private record LegacyPageOutcome(McpJsonObject document, Optional<String> contentLanguage,
			boolean invalidCursor, boolean failed) {
		@Override public String toString() { return "LegacyPageOutcome{<redacted>}"; }
	}

	@NonNull
	private MicrohttpResponse catalogResponse(@NonNull McpWireResult canonicalResult,
			@NonNull McpProtocolProfile protocolProfile,
			McpRuntimeCatalogLocalizer.@NonNull ResponseKind responseKind,
			@NonNull McpJsonRpcId requestId,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull RequestControl requestControl,
			@NonNull List<@NonNull Header> corsHeaders) {
		return catalogResponse(canonicalResult, protocolProfile, responseKind,
				requestId, endpointPolicy, requestControl, corsHeaders,
				Optional.empty(), false);
	}

	@NonNull
	private MicrohttpResponse catalogResponse(@NonNull McpWireResult canonicalResult,
			@NonNull McpProtocolProfile protocolProfile,
			McpRuntimeCatalogLocalizer.@NonNull ResponseKind responseKind,
			@NonNull McpJsonRpcId requestId,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull RequestControl requestControl,
			@NonNull List<@NonNull Header> corsHeaders,
			@NonNull Optional<@NonNull CatalogAccessSession> catalogAccessSession,
			boolean requestSpecificProjection) {
		byte[] canonicalEncoded = encodeResultResponse(protocolProfile, requestId,
				canonicalResult);
		Optional<McpRuntimeCatalogLocalizer> catalogLocalizer =
				endpointPolicy.catalogLocalizer();
		McpRequestContext requestContext =
				requestControl.publicRequestContext().orElse(null);

		if (catalogLocalizer.isEmpty() || requestContext == null)
			return jsonResponse(200, "OK", canonicalEncoded, corsHeaders);

		McpJsonObject canonicalDocument = McpLegacyHttpWire.isLegacyRevision(
				protocolProfile.revision())
				? McpLegacyResponseWire.projectResult(canonicalResult)
				: canonicalResult.toJsonObject();
		// Canonical documents are stable per binding, so their encoded length is
		// measured once per catalog rather than once per request.
		long canonicalDocumentBytes = requestSpecificProjection
				? jsonCodec.toUtf8Bytes(canonicalDocument).length
				: canonicalCatalogDocumentBytes.computeIfAbsent(
						new CanonicalCatalogKey(endpointPolicy.path(),
								requireNonNull(protocolProfile).revision(), responseKind),
						key -> (long) jsonCodec.toUtf8Bytes(canonicalDocument).length);
		McpRuntimeCatalogLocalizer.Outcome outcome;

		try {
			outcome = requireNonNull(catalogLocalizer.orElseThrow().localizeCatalog(
					new McpRuntimeCatalogLocalizer.Input(endpointPolicy.path(),
							responseKind, requestContext, canonicalDocument,
							canonicalDocumentBytes,
							canonicalEncoded.length - canonicalDocumentBytes,
							jsonLimits.maximumOutputBytes(),
							maximumLocalizedReplacementCharacters(),
							document -> jsonCodec.toUtf8Bytes(document).length,
							requestControl.acceptLanguageValues(), List.of(),
							requestControl::isTerminalCanceledOrPastDeadline,
							catalogAccessSession.flatMap(
									CatalogAccessSession::localizationContext))),
					"The MCP catalog localizer returned null.");
		} catch (RuntimeException exception) {
			return observedPolicyHookInternalError(requestControl, requestId,
					corsHeaders, exception);
		}

		List<Header> responseHeaders = withContentLanguage(corsHeaders,
				outcome.contentLanguage());

		return switch (outcome.disposition()) {
			case CANONICAL -> jsonResponse(200, "OK", canonicalEncoded,
					responseHeaders);
			case LOCALIZED -> jsonResponse(200, "OK", encodeResultResponse(
					protocolProfile, requestId,
					McpWireResult.withPrecomputedJsonObject(canonicalResult,
							outcome.document())),
					responseHeaders);
			case FAIL_REQUEST, RESIZE_PAGE -> observedPolicyHookInternalError(requestControl,
					requestId, corsHeaders, null);
		};
	}

	private byte @NonNull [] encodeResultResponse(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcId requestId,
			@NonNull McpWireResult result) {
		McpJsonRpcMessage.ResultResponse response =
				new McpJsonRpcMessage.ResultResponse(requestId, result,
						McpJsonObject.empty());
		return McpLegacyHttpWire.isLegacyRevision(protocolProfile.revision())
				? McpLegacyResponseWire.encode(jsonCodec, response)
				: envelopeCodec.encode(response);
	}

	@Nullable
	private MicrohttpResponse appToolCallFailure(
			@NonNull McpServerCapabilityRegistry capabilityRegistry,
			@NonNull McpProtocolProfile protocolProfile,
			McpJsonRpcMessage.@NonNull Request mappedRequest,
			@NonNull String toolName, @NonNull List<@NonNull Header> corsHeaders) {
		if (!capabilityRegistry.isToolAppAvailable(toolName))
			return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		if (capabilityRegistry.toolRequiresAppCapability(toolName)
				&& !mappedRequest.params().metadata().clientCapabilities()
						.supports(McpServerCapabilityRegistry.APPS_CAPABILITY))
			return profiledJsonRpcError(protocolProfile,
					McpProfileErrorKind.OPERATION, 400, "Bad Request",
					Optional.of(mappedRequest.id()),
					McpJsonRpcError.missingRequiredClientCapabilities(
							Set.of(McpServerCapabilityRegistry.APPS_CAPABILITY)), corsHeaders);
		return null;
	}

	private McpCatalogProjectionQueue.Digest projectCatalogDigest(
			@NonNull EndpointRuntime endpointRuntime,
			McpCatalogProjectionQueue.@NonNull Family family,
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpRequestContext requestContext,
			@NonNull CancelationToken cancelationToken,
			@NonNull List<@NonNull String> acceptLanguageValues,
			long deadlineNanos) throws Exception {
		requireCatalogProjectionActive(cancelationToken, deadlineNanos);
		McpServerCapabilityRegistry capabilityRegistry = requireNonNull(
				endpointRuntime).capabilityRegistry(protocolProfile.revision());
		McpRuntimeCatalogLocalizer.ResponseKind responseKind;
		McpProfileFrameworkResultKind resultKind;
		Set<String> visibleNames;
		Optional<CatalogAccessSession> catalogAccessSession = Optional.empty();
		Optional<CatalogAccessAdapter> accessAdapter = endpointRuntime.binding()
				.endpoint().catalogAccessAdapter();

		if (accessAdapter.isPresent()) {
			CatalogAccessSession session = requireNonNull(
					accessAdapter.orElseThrow().open(new CatalogAccessInput(
							requestContext, cancelationToken,
							() -> catalogProjectionPastDeadline(cancelationToken,
									deadlineNanos), acceptLanguageValues,
							Optional.empty(), new AtomicReference<>())),
					"The MCP catalog access adapter returned null.");
			catalogAccessSession = Optional.of(session);
			LinkedHashSet<String> accessible = new LinkedHashSet<>();
			if (family == McpCatalogProjectionQueue.Family.TOOLS) {
				for (String toolName : capabilityRegistry.tools()) {
					requireCatalogProjectionActive(cancelationToken, deadlineNanos);
					if (session.isToolAccessible(toolName))
						accessible.add(toolName);
				}
			} else {
				for (String promptName : capabilityRegistry.prompts()) {
					requireCatalogProjectionActive(cancelationToken, deadlineNanos);
					if (session.isPromptAccessible(promptName))
						accessible.add(promptName);
				}
			}
			visibleNames = Collections.unmodifiableSet(accessible);
		} else {
			visibleNames = family == McpCatalogProjectionQueue.Family.TOOLS
					? Collections.unmodifiableSet(new LinkedHashSet<>(
							capabilityRegistry.tools()))
					: Collections.unmodifiableSet(new LinkedHashSet<>(
							capabilityRegistry.prompts()));
		}

		McpWireResult projectedResult;
		if (family == McpCatalogProjectionQueue.Family.TOOLS) {
			responseKind = McpRuntimeCatalogLocalizer.ResponseKind.TOOLS_LIST;
			resultKind = McpProfileFrameworkResultKind.TOOLS_LIST;
			projectedResult = capabilityRegistry.hasAppTools()
					? capabilityRegistry.toolsListResult(visibleNames,
							requestContext.getClientCapabilities().supportsAppMimeType(
									McpServerCapabilityRegistry.APPS_MIME_TYPE))
					: capabilityRegistry.toolsListResult(visibleNames);
		} else {
			responseKind = McpRuntimeCatalogLocalizer.ResponseKind.PROMPTS_LIST;
			resultKind = McpProfileFrameworkResultKind.PROMPTS_LIST;
			projectedResult = capabilityRegistry.promptsListResult(visibleNames);
		}
		McpWireResult renderedResult = requireNonNull(protocolProfile)
				.renderFrameworkResult(resultKind, projectedResult);
		McpJsonObject finalDocument = renderedResult.toJsonObject();
		Optional<McpRuntimeCatalogLocalizer> catalogLocalizer = endpointRuntime
				.binding().endpointPolicy().catalogLocalizer();

		if (catalogLocalizer.isPresent()) {
			long canonicalDocumentBytes = jsonCodec.toUtf8Bytes(finalDocument).length;
			McpJsonRpcMessage.ResultResponse representativeResponse =
					new McpJsonRpcMessage.ResultResponse(
							new McpJsonRpcId.IntegerId(BigInteger.ZERO), renderedResult,
							McpJsonObject.empty());
			long envelopeBytes = envelopeCodec.encode(representativeResponse).length
					- canonicalDocumentBytes;
			McpRuntimeCatalogLocalizer.Outcome outcome = requireNonNull(
					catalogLocalizer.orElseThrow().localizeCatalog(
							new McpRuntimeCatalogLocalizer.Input(
									endpointRuntime.path(), responseKind,
									requestContext, finalDocument,
									canonicalDocumentBytes, envelopeBytes,
									jsonLimits.maximumOutputBytes(),
									maximumLocalizedReplacementCharacters(),
									document -> jsonCodec.toUtf8Bytes(document).length,
									acceptLanguageValues, List.of(),
									() -> catalogProjectionPastDeadline(
											cancelationToken, deadlineNanos),
									catalogAccessSession.flatMap(
											CatalogAccessSession::localizationContext))),
					"The MCP catalog localizer returned null.");
			if (outcome.localizationFailure()
					|| outcome.disposition()
							== McpRuntimeCatalogLocalizer.Disposition.FAIL_REQUEST)
				throw new IllegalStateException(
						"MCP subscription catalog localization failed.");
			if (outcome.disposition()
					== McpRuntimeCatalogLocalizer.Disposition.LOCALIZED)
				finalDocument = outcome.document();
		}

		requireCatalogProjectionActive(cancelationToken, deadlineNanos);
		return sha256CatalogDigest(jsonCodec.toUtf8Bytes(finalDocument));
	}

	private boolean catalogProjectionPastDeadline(
			@NonNull CancelationToken cancelationToken, long deadlineNanos) {
		return requireNonNull(cancelationToken).isCanceled()
				|| applicationClock.nanoTime() - deadlineNanos >= 0L;
	}

	private void requireCatalogProjectionActive(
			@NonNull CancelationToken cancelationToken, long deadlineNanos)
			throws InterruptedException {
		if (Thread.currentThread().isInterrupted()
				|| catalogProjectionPastDeadline(cancelationToken, deadlineNanos))
			throw new InterruptedException(
					"MCP subscription catalog projection was canceled.");
	}

	private static McpCatalogProjectionQueue.Digest sha256CatalogDigest(
			byte @NonNull [] documentBytes) {
		try {
			return new McpCatalogProjectionQueue.Digest(
					MessageDigest.getInstance("SHA-256")
							.digest(requireNonNull(documentBytes)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	@NonNull
	private static List<@NonNull Header> withContentLanguage(
			@NonNull List<@NonNull Header> headers,
			@NonNull Optional<@NonNull String> contentLanguage) {
		if (contentLanguage.isEmpty())
			return headers;

		List<Header> merged = new ArrayList<>(headers.size() + 1);
		merged.addAll(headers);
		merged.add(new Header("Content-Language", contentLanguage.orElseThrow()));
		return List.copyOf(merged);
	}

	/**
	 * Localizes one subscription's terminal metadata before response commitment.
	 * The subscription identifier is part of the document, so the canonical
	 * length is measured per pre-render rather than cached per catalog.
	 */
	private McpRuntimeCatalogLocalizer.@NonNull Outcome localizeSubscriptionTerminal(
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			McpJsonRpcMessage.@NonNull ResultResponse canonicalResponse,
			@NonNull RequestControl requestControl) {
		requireNonNull(canonicalResponse);
		byte[] canonicalEncoded = envelopeCodec.encode(canonicalResponse);
		McpJsonObject canonicalDocument = canonicalResponse.result().toJsonObject();
		long canonicalDocumentBytes =
				jsonCodec.toUtf8Bytes(canonicalDocument).length;
		McpRequestContext requestContext = requestControl
				.currentSubscriptionRequestContext("subscriptions/listen");

		return requireNonNull(endpointPolicy.catalogLocalizer().orElseThrow()
				.localizeCatalog(new McpRuntimeCatalogLocalizer.Input(
						endpointPolicy.path(),
						McpRuntimeCatalogLocalizer.ResponseKind.SUBSCRIPTION_TERMINAL,
						requestContext, canonicalDocument, canonicalDocumentBytes,
						canonicalEncoded.length - canonicalDocumentBytes,
						jsonLimits.maximumOutputBytes(),
						maximumLocalizedReplacementCharacters(),
						document -> jsonCodec.toUtf8Bytes(document).length,
						requestControl.acceptLanguageValues(), List.of(),
						requestControl::isTerminalCanceledOrPastDeadline)),
				"The MCP catalog localizer returned null.");
	}

	/**
	 * Normalizes every response {@code Vary} field into one duplicate-free token
	 * list and merges {@code Accept-Language} into it. A wildcard overrides every
	 * named token and remains exactly {@code *}.
	 */
	@NonNull
	private static List<@NonNull Header> withAcceptLanguageVary(
			@NonNull List<@NonNull Header> headers) {
		LinkedHashMap<String, String> tokens = new LinkedHashMap<>();
		String varyName = "Vary";
		boolean foundVary = false;
		boolean wildcard = false;

		for (Header header : headers) {
			if (!"Vary".equalsIgnoreCase(header.name()))
				continue;
			if (!foundVary) {
				foundVary = true;
				varyName = header.name();
			}
			for (String value : header.value().split(",", -1)) {
				String token = value.trim();
				if (token.isEmpty())
					continue;
				if ("*".equals(token)) {
					wildcard = true;
					continue;
				}
				tokens.putIfAbsent(token.toLowerCase(Locale.ROOT), token);
			}
		}

		if (!wildcard)
			tokens.putIfAbsent("accept-language", "Accept-Language");

		String normalizedValue = wildcard ? "*"
				: String.join(", ", tokens.values());
		List<Header> normalized = new ArrayList<>(headers.size() + 1);
		boolean emittedVary = false;
		for (Header header : headers) {
			if ("Vary".equalsIgnoreCase(header.name())) {
				if (!emittedVary) {
					normalized.add(new Header(varyName, normalizedValue));
					emittedVary = true;
				}
			} else {
				normalized.add(header);
			}
		}
		if (!emittedVary)
			normalized.add(new Header(varyName, normalizedValue));

		return List.copyOf(normalized);
	}

	private long maximumLocalizedReplacementCharacters() {
		return Math.min(jsonLimits.maximumStringLengthInCharacters(),
				jsonLimits.maximumTokenLengthInCharacters());
	}

	@NonNull
	private MicrohttpResponse policyHookInternalError(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcId requestId,
			@NonNull List<@NonNull Header> corsHeaders) {
		return profiledJsonRpcError(protocolProfile, McpProfileErrorKind.CONTROL,
				500, "Internal Server Error", Optional.of(requestId),
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()), corsHeaders);
	}

	@NonNull
	private MicrohttpResponse requestStateUnavailable(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcId requestId,
			@NonNull List<@NonNull Header> corsHeaders) {
		return profiledJsonRpcError(protocolProfile, McpProfileErrorKind.CONTROL,
				503, "Service Unavailable", Optional.of(requestId),
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()), corsHeaders);
	}

	@NonNull
	private MicrohttpResponse observedSubscriptionCapacityRejected(
			@NonNull RequestControl requestControl,
			@NonNull McpJsonRpcId requestId,
			@NonNull List<@NonNull Header> corsHeaders) {
		McpJsonRpcError error = renderFrameworkError(
				requestControl.protocolProfile(), McpProfileErrorKind.CONTROL,
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()));
		requestControl.planRequestObservation(new RequestObservationResult(
				McpRequestOutcome.REJECTED, error, List.of()));
		return jsonRpcError(503, "Service Unavailable",
				Optional.of(requestId), error, corsHeaders,
				requestControl.publicRequestContext().orElse(null));
	}

	private @Nullable MicrohttpResponse observedSubscriptionAuthorizationFailure(
			@NonNull RequestControl requestControl,
			@NonNull McpJsonRpcId requestId,
			@NonNull List<@NonNull Header> corsHeaders,
			@NonNull SubscriptionAuthorizationResult result) {
		return switch (requireNonNull(result).disposition()) {
			case TERMINATED, STALE_RESULT -> null;
			case DENIED -> {
				McpJsonRpcError error = renderFrameworkError(
						requestControl.protocolProfile(), McpProfileErrorKind.CONTROL,
						new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
								"Internal error", Optional.empty()));
				requestControl.planRequestObservation(new RequestObservationResult(
						McpRequestOutcome.REJECTED, error, List.of()));
				yield jsonRpcError(403, "Forbidden", Optional.of(requestId), error,
						corsHeaders, requestControl.publicRequestContext().orElse(null));
			}
			case CAPACITY_REJECTED -> observedPolicyCapacityRejected(
					requestControl, requestId, corsHeaders);
			case TIMED_OUT -> observedPolicyDeadline(requestControl, requestId,
					corsHeaders, result.queuedTimeout());
			case FAILED -> observedPolicyHookInternalError(requestControl,
					requestId, corsHeaders, result.failure());
			case ALLOWED -> throw new IllegalArgumentException(
					"A successful subscription authorization is not a failure.");
		};
	}

	@NonNull
	private MicrohttpResponse observedPolicyCapacityRejected(
			@NonNull RequestControl requestControl,
			@NonNull McpJsonRpcId requestId,
			@NonNull List<@NonNull Header> corsHeaders) {
		McpJsonRpcError error = renderFrameworkError(
				requestControl.protocolProfile(), McpProfileErrorKind.CONTROL,
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()));
		requestControl.planRequestObservation(new RequestObservationResult(
				McpRequestOutcome.REJECTED, error, List.of()));
		return jsonRpcError(503, "Service Unavailable",
				Optional.of(requestId), error, corsHeaders,
				requestControl.publicRequestContext().orElse(null));
	}

	@NonNull
	private MicrohttpResponse observedPolicyDeadline(
			@NonNull RequestControl requestControl,
			@NonNull McpJsonRpcId requestId,
			@NonNull List<@NonNull Header> corsHeaders,
			boolean queued) {
		McpJsonRpcError error = renderFrameworkError(
				requestControl.protocolProfile(), McpProfileErrorKind.CONTROL,
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()));
		requestControl.planRequestObservation(new RequestObservationResult(
				McpRequestOutcome.DEADLINE_EXCEEDED, error, List.of()));
		return jsonRpcError(queued ? 503 : 504,
				queued ? "Service Unavailable" : "Gateway Timeout",
				Optional.of(requestId), error, corsHeaders,
				requestControl.publicRequestContext().orElse(null));
	}

	@NonNull
	private MicrohttpResponse observedPolicyHookInternalError(
			@NonNull RequestControl requestControl,
			@NonNull McpJsonRpcId requestId,
			@NonNull List<@NonNull Header> corsHeaders,
			@Nullable Throwable throwable) {
		McpJsonRpcError error = renderFrameworkError(
				requestControl.protocolProfile(), McpProfileErrorKind.CONTROL,
				new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR,
						"Internal error", Optional.empty()));
		requestControl.planRequestObservation(new RequestObservationResult(
				McpRequestOutcome.INTERNAL_ERROR, error,
				throwable == null ? List.of() : List.of(throwable)));
		return jsonRpcError(500, "Internal Server Error",
				Optional.of(requestId), error, corsHeaders,
				requestControl.publicRequestContext().orElse(null));
	}

	@NonNull
	private MicrohttpResponse admissionRejection(@NonNull McpJsonRpcId requestId,
			@NonNull McpAdmissionRejection rejection,
			@NonNull List<@NonNull Header> corsHeaders) {
		requireNonNull(requestId);
		requireNonNull(rejection);
		requireNonNull(corsHeaders);
		if (!admissionErrorCodeAllowed(rejection.jsonRpcError().code()))
			throw new IllegalArgumentException(
					"Admission rejection used a reserved error code.");

		List<Header> headers = new ArrayList<>(corsHeaders);
		headers.addAll(validatedPolicyHeaders(rejection.headers()));
		String reason = StatusCode.fromStatusCode(rejection.statusCode())
				.map(StatusCode::getReasonPhrase)
				.orElse("Admission Rejected");
		return jsonRpcError(rejection.statusCode(), reason, Optional.of(requestId),
				rejection.jsonRpcError(), List.copyOf(headers));
	}

	@NonNull
	private MicrohttpResponse notificationAdmissionRejection(
			@NonNull McpAdmissionRejection rejection,
			@NonNull List<@NonNull Header> corsHeaders) {
		requireNonNull(rejection);
		requireNonNull(corsHeaders);
		if (!admissionErrorCodeAllowed(rejection.jsonRpcError().code()))
			throw new IllegalArgumentException(
					"Admission rejection used a reserved error code.");

		List<Header> headers = new ArrayList<>(corsHeaders);
		headers.addAll(validatedPolicyHeaders(rejection.headers()));
		String reason = StatusCode.fromStatusCode(rejection.statusCode())
				.map(StatusCode::getReasonPhrase)
				.orElse("Admission Rejected");
		return emptyResponse(rejection.statusCode(), reason, List.copyOf(headers));
	}

	@NonNull
	private MicrohttpResponse rateLimited(
			@NonNull McpJsonRpcId requestId,
			@NonNull Duration retryAfter,
			@NonNull List<@NonNull Header> corsHeaders,
			@Nullable McpRequestContext requestContext,
			@NonNull McpJsonRpcError error) {
		requireNonNull(retryAfter);
		if (retryAfter.isNegative())
			throw new IllegalArgumentException("Retry-After must not be negative.");
		List<Header> headers = new ArrayList<>(corsHeaders.size() + 1);
		headers.addAll(corsHeaders);
		headers.add(new Header(RETRY_AFTER, retryAfterSeconds(retryAfter)));
		return jsonRpcError(429, "Too Many Requests", Optional.of(requestId),
				requireNonNull(error), List.copyOf(headers), requestContext);
	}

	@NonNull
	private MicrohttpResponse observedRateLimited(
			@NonNull RequestControl requestControl,
			@NonNull McpJsonRpcId requestId, @NonNull Duration retryAfter,
			@NonNull List<@NonNull Header> corsHeaders) {
		McpJsonRpcError error = renderFrameworkError(
				requestControl.protocolProfile(), McpProfileErrorKind.CONTROL,
				new McpJsonRpcError(SOKLET_RATE_LIMITED,
						"Rate limited", Optional.empty()));
		requestControl.planRequestObservation(new RequestObservationResult(
				McpRequestOutcome.REJECTED, error, List.of()));
		return rateLimited(requestId, retryAfter, corsHeaders,
				requestControl.publicRequestContext().orElse(null), error);
	}

	@NonNull
	private MicrohttpResponse notificationRateLimited(
			@NonNull Duration retryAfter,
			@NonNull List<@NonNull Header> corsHeaders) {
		requireNonNull(retryAfter);
		if (retryAfter.isNegative())
			throw new IllegalArgumentException("Retry-After must not be negative.");
		List<Header> headers = new ArrayList<>(corsHeaders.size() + 1);
		headers.addAll(corsHeaders);
		headers.add(new Header(RETRY_AFTER, retryAfterSeconds(retryAfter)));
		return emptyResponse(429, "Too Many Requests", List.copyOf(headers));
	}

	@NonNull
	private String retryAfterSeconds(@NonNull Duration retryAfter) {
		long seconds = retryAfter.getSeconds();
		if (retryAfter.getNano() > 0 && seconds < Long.MAX_VALUE)
			seconds++;
		return Long.toString(seconds);
	}

	private boolean admissionErrorCodeAllowed(int code) {
		if (code == McpJsonRpcError.INVALID_PARAMS)
			return true;
		return (code < -32_768 || code > -32_000)
				&& code != SOKLET_RATE_LIMITED
				&& code != SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER;
	}

	@NonNull
	private List<@NonNull Header> validatedPolicyHeaders(
			@NonNull Map<@NonNull String, @NonNull List<@NonNull String>> policyHeaders) {
		List<Header> headers = new ArrayList<>();
		Set<String> normalizedNames = new LinkedHashSet<>();
		long encodedBytes = 0L;
		for (Map.Entry<String, List<String>> entry : policyHeaders.entrySet()) {
			String name = requireNonNull(entry.getKey());
			String lowerName = name.toLowerCase(Locale.ROOT);
			if (!validHeaderName(name)
					|| !normalizedNames.add(lowerName)
					|| FRAMEWORK_OWNED_POLICY_HEADERS.contains(lowerName)
					|| FORBIDDEN_LEGACY_MCP_POLICY_HEADERS.contains(lowerName)
					|| lowerName.startsWith("access-control-"))
				throw new IllegalArgumentException(
						"Admission rejection contains an unsafe response header.");

			List<String> values = requireNonNull(entry.getValue());
			if (values.isEmpty())
				throw new IllegalArgumentException(
						"Admission rejection header values must not be empty.");
			for (String value : values) {
				requireNonNull(value);
				if (!validHeaderValue(value))
					throw new IllegalArgumentException(
							"Admission rejection contains an unsafe response header value.");
				encodedBytes += name.length() + value.length() + 4L;
				if (headers.size() >= MAXIMUM_ADMISSION_REJECTION_HEADER_COUNT
						|| encodedBytes > MAXIMUM_ADMISSION_REJECTION_HEADER_BYTES)
					throw new IllegalArgumentException(
							"Admission rejection response headers exceed the fixed bounds.");
				headers.add(new Header(name, value));
			}
		}
		return List.copyOf(headers);
	}

	McpLegacySessionTransportAdmission.Rejected validatedLegacySessionTransportRejection(
			int statusCode, Map<String, List<String>> headers) {
		if (statusCode < 400 || statusCode > 599)
			throw new IllegalArgumentException("HTTP admission rejection must use an error status.");
		return new McpLegacySessionTransportAdmission.Rejected(statusCode, validatedPolicyHeaders(headers));
	}

	private boolean validHeaderName(@NonNull String name) {
		if (name.isEmpty())
			return false;
		for (int index = 0; index < name.length(); index++) {
			char character = name.charAt(index);
			if (!(character >= '0' && character <= '9')
					&& !(character >= 'A' && character <= 'Z')
					&& !(character >= 'a' && character <= 'z')
					&& "!#$%&'*+-.^_`|~".indexOf(character) < 0)
				return false;
		}
		return true;
	}

	private boolean validHeaderValue(@NonNull String value) {
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character != '\t' && (character < 0x20 || character > 0x7E))
				return false;
		}
		return true;
	}

	@NonNull
	private MicrohttpResponse processPreflight(@NonNull MicrohttpRequest request,
			@NonNull Request sokletRequest,
			@NonNull EndpointRuntime endpointRuntime) {
		McpHttpEndpointPolicy endpointPolicy =
				endpointRuntime.binding().endpointPolicy();
		List<String> origins = headerValues(request, ORIGIN);
		List<String> requestedMethods = headerValues(request,
				"Access-Control-Request-Method");

		if (origins.isEmpty() && requestedMethods.isEmpty())
			return methodNotAllowed(List.of());

		if (origins.size() != 1 || !validOrigin(origins.get(0)))
			return emptyResponse(403, "Forbidden", List.of());

		Set<HttpMethod> configuredMethods = legacyHttpMethods(endpointRuntime, null);
		Optional<HttpMethod> requestedMethod = requestedMethods.size() == 1
				? httpMethod(requestedMethods.get(0)) : Optional.empty();
		if (requestedMethod.isEmpty() || requestedMethod.orElseThrow() == HttpMethod.OPTIONS
				|| !configuredMethods.contains(requestedMethod.orElseThrow()))
			return emptyResponse(403, "Forbidden", List.of());

		Optional<Set<String>> requestedHeaders = requestedPreflightHeaders(request);
		if (requestedHeaders.isEmpty()
				|| !containsOnlyIgnoreCase(requestedHeaders.orElseThrow(),
						mcpPreflightRequestHeaders(endpointRuntime)))
			return emptyResponse(403, "Forbidden", List.of());

		CorsPreflight preflight = CorsPreflight.fromOrigin(origins.get(0), requestedMethod.orElseThrow(),
				requestedHeaders.orElseThrow());
		CorsPreflightResponse authorization;
		try {
			Optional<CorsPreflightResponse> optionalAuthorization = requireNonNull(
					endpointPolicy.corsAuthorizer().authorizePreflight(
							sokletRequest, preflight, configuredMethods));
			authorization = optionalAuthorization.orElse(null);
		} catch (Throwable throwable) {
			return emptyResponse(500, "Internal Server Error", List.of());
		}

		if (authorization == null)
			return emptyResponse(403, "Forbidden", List.of());

		Optional<String> allowedOrigin = safeAllowedOrigin(
				origins.get(0), authorization.getAccessControlAllowOrigin(),
				authorization.getAccessControlAllowCredentials().orElse(null));
		if (allowedOrigin.isEmpty())
			return emptyResponse(500, "Internal Server Error", List.of());

		Set<HttpMethod> allowedMethods = authorization.getAccessControlAllowMethods();
		Set<String> allowedHeaders = authorization.getAccessControlAllowHeaders();
		if (!configuredMethods.containsAll(allowedMethods)
				|| !validCorsAllowedHeaders(allowedHeaders, endpointRuntime))
			return emptyResponse(500, "Internal Server Error", List.of());

		List<Header> headers = new ArrayList<>();
		headers.add(new Header("Access-Control-Allow-Origin", allowedOrigin.orElseThrow()));
		if (Boolean.TRUE.equals(
				authorization.getAccessControlAllowCredentials().orElse(null)))
			headers.add(new Header("Access-Control-Allow-Credentials", "true"));
		List<String> allowedMethodNames = new ArrayList<>();
		for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.GET, HttpMethod.DELETE, HttpMethod.OPTIONS)) {
			if (allowedMethods.contains(method))
				allowedMethodNames.add(method.name());
		}
		if (!allowedMethodNames.isEmpty())
			headers.add(new Header("Access-Control-Allow-Methods",
					String.join(", ", allowedMethodNames)));
		if (!allowedHeaders.isEmpty()) {
			List<String> sortedAllowedHeaders = new ArrayList<>(allowedHeaders);
			sortedAllowedHeaders.sort(String.CASE_INSENSITIVE_ORDER);
			headers.add(new Header("Access-Control-Allow-Headers",
					String.join(", ", sortedAllowedHeaders)));
		}
		authorization.getAccessControlMaxAge().ifPresent(maximumAge -> {
			if (!maximumAge.isNegative() && !maximumAge.isZero())
				headers.add(new Header("Access-Control-Max-Age",
						Long.toString(maximumAge.toSeconds())));
		});
		if (!"*".equals(allowedOrigin.orElseThrow()))
			headers.add(new Header("Vary",
					"Origin, Access-Control-Request-Method, Access-Control-Request-Headers"));
		return emptyResponse(204, "No Content", headers);
	}

	private boolean validCorsAllowedHeaders(
			@NonNull Set<@NonNull String> allowedHeaders,
			@NonNull EndpointRuntime endpointRuntime) {
		Set<String> normalizedNames = new LinkedHashSet<>();
		for (String name : allowedHeaders) {
			if (!validHeaderName(name)
					|| !containsOnlyIgnoreCase(Set.of(name),
							mcpPreflightRequestHeaders(endpointRuntime))
					|| !normalizedNames.add(name.toLowerCase(Locale.ROOT)))
				return false;
		}
		return true;
	}

	@NonNull
	private Set<@NonNull String> mcpPreflightRequestHeaders(
			@NonNull EndpointRuntime endpointRuntime) {
		Set<String> headers = new LinkedHashSet<>(MCP_PREFLIGHT_REQUEST_HEADERS);
		if (legacySessionRevisions.containsKey(endpointRuntime.path()))
			headers.add(MCP_SESSION_ID);
		if (legacyHttpMethods(endpointRuntime, null).contains(HttpMethod.GET))
			headers.add("Last-Event-ID");
		for (String revision : endpointRuntime.binding().supportedRevisions())
			headers.addAll(endpointRuntime.capabilityRegistry(revision)
					.customMirroredHeaderNames());
		return Set.copyOf(headers);
	}

	@NonNull
	private CorsAuthorization authorizeCors(@NonNull MicrohttpRequest request,
			@NonNull Request sokletRequest, @NonNull HttpMethod httpMethod,
			@NonNull McpHttpEndpointPolicy endpointPolicy) {
		List<String> origins = headerValues(request, ORIGIN);
		if (origins.isEmpty()) {
			if (endpointPolicy.absentOriginPolicy() == McpAbsentOriginPolicy.REQUIRE_ORIGIN)
				return CorsAuthorization.rejected(
						emptyResponse(403, "Forbidden", List.of()));

			return CorsAuthorization.withoutOrigin();
		}

		if (origins.size() != 1 || !validOrigin(origins.get(0)))
			return CorsAuthorization.rejected(
					emptyResponse(403, "Forbidden", List.of()));

		CorsResponse response;
		try {
			Optional<CorsResponse> optionalResponse = requireNonNull(endpointPolicy.corsAuthorizer()
					.authorize(sokletRequest, Cors.fromOrigin(httpMethod, origins.get(0))));
			response = optionalResponse.orElse(null);
		} catch (Throwable throwable) {
			return CorsAuthorization.rejected(
					emptyResponse(500, "Internal Server Error", List.of()));
		}

		if (response == null)
			return CorsAuthorization.rejected(
					emptyResponse(403, "Forbidden", List.of()));

		if (safeAllowedOrigin(origins.get(0), response.getAccessControlAllowOrigin(),
				response.getAccessControlAllowCredentials().orElse(null)).isEmpty())
			return CorsAuthorization.rejected(
					emptyResponse(500, "Internal Server Error", List.of()));

		return CorsAuthorization.accepted(response);
	}

	private @Nullable MicrohttpResponse prevalidateOriginPolicy(
			@NonNull MicrohttpRequest request,
			@NonNull McpHttpEndpointPolicy endpointPolicy) {
		List<String> origins = headerValues(request, ORIGIN);
		if (origins.isEmpty())
			return endpointPolicy.absentOriginPolicy() == McpAbsentOriginPolicy.REQUIRE_ORIGIN
					? emptyResponse(403, "Forbidden", List.of()) : null;

		return origins.size() == 1 && validOrigin(origins.get(0))
				? null : emptyResponse(403, "Forbidden", List.of());
	}

	@NonNull
	private List<@NonNull Header> corsHeaders(@NonNull MicrohttpRequest request,
			@NonNull CorsResponse response) {
		String origin = singleHeader(request, ORIGIN).orElseThrow();
		String allowedOrigin = safeAllowedOrigin(origin,
				response.getAccessControlAllowOrigin(),
				response.getAccessControlAllowCredentials().orElse(null)).orElseThrow();
		List<Header> headers = new ArrayList<>();
		headers.add(new Header("Access-Control-Allow-Origin", allowedOrigin));
		if (Boolean.TRUE.equals(response.getAccessControlAllowCredentials().orElse(null)))
			headers.add(new Header("Access-Control-Allow-Credentials", "true"));
		List<String> exposedHeaders = new ArrayList<>(MCP_EXPOSED_RESPONSE_HEADERS);
		if (legacySessionRevisions.containsKey(requestPath(request.uri())))
			exposedHeaders.add(MCP_SESSION_ID);
		if (!exposedHeaders.isEmpty())
			headers.add(new Header("Access-Control-Expose-Headers",
					String.join(", ", exposedHeaders)));
		if (!"*".equals(allowedOrigin))
			headers.add(new Header("Vary", "Origin"));
		return List.copyOf(headers);
	}

	@NonNull
	private Optional<@NonNull String> safeAllowedOrigin(
			@NonNull String requestOrigin,
			@Nullable String configuredAllowedOrigin,
			@Nullable Boolean allowCredentials) {
		if (configuredAllowedOrigin == null)
			return Optional.empty();

		String value = configuredAllowedOrigin.trim();
		if (Boolean.TRUE.equals(allowCredentials) && "*".equals(value))
			value = requestOrigin;

		if (!"*".equals(value) && !value.equals(requestOrigin))
			return Optional.empty();

		if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
			return Optional.empty();

		return Optional.of(value);
	}

	private @Nullable MicrohttpResponse contentNegotiationFailure(
			@NonNull MicrohttpRequest request,
			@NonNull List<@NonNull Header> corsHeaders) {
		List<String> contentTypes = headerValues(request, CONTENT_TYPE);
		if (contentTypes.size() > 1)
			return emptyResponse(400, "Bad Request", corsHeaders);

		if (contentTypes.size() != 1 || !isJsonContentType(contentTypes.get(0)))
			return emptyResponse(415, "Unsupported Media Type", corsHeaders);

		if (!acceptsBothResponseTypes(headerValues(request, ACCEPT)))
			return emptyResponse(406, "Not Acceptable", corsHeaders);

		return null;
	}

	private boolean isJsonContentType(@NonNull String contentType) {
		List<String> segments = splitSemicolonAware(contentType);
		if (segments.isEmpty() || !JSON_MEDIA_TYPE.equalsIgnoreCase(segments.get(0).trim()))
			return false;

		Set<String> names = new LinkedHashSet<>();
		for (int index = 1; index < segments.size(); index++) {
			String segment = segments.get(index).trim();
			int equals = segment.indexOf('=');
			if (equals <= 0 || equals == segment.length() - 1)
				return false;

			String name = segment.substring(0, equals).trim().toLowerCase(Locale.ROOT);
			String rawValue = segment.substring(equals + 1).trim();
			if (!validParameterValue(rawValue))
				return false;

			String value = unquote(rawValue);
			if (!httpToken(name) || !names.add(name))
				return false;

			if ("charset".equals(name) && !"utf-8".equalsIgnoreCase(value))
				return false;
		}

		return true;
	}

	private boolean acceptsBothResponseTypes(
			@NonNull List<@NonNull String> acceptHeaders) {
		if (acceptHeaders.isEmpty())
			return false;

		List<String> fragments = splitCommaAware(String.join(",", acceptHeaders));
		List<MediaRange> ranges = new ArrayList<>(fragments.size());
		for (String fragment : fragments) {
			if (!validAcceptFragment(fragment))
				return false;

			Optional<MediaRange> range = MediaRange.fromHeaderRepresentation(fragment);
			if (range.isEmpty())
				return false;
			ranges.add(range.orElseThrow());
		}

		if (ranges.isEmpty())
			return false;

		return effectiveQuality(ranges, "application", "json").compareTo(BigDecimal.ZERO) > 0
				&& effectiveQuality(ranges, "text", "event-stream")
						.compareTo(BigDecimal.ZERO) > 0;
	}

	private boolean validAcceptFragment(@NonNull String fragment) {
		List<String> segments = splitSemicolonAware(fragment);
		if (segments.isEmpty())
			return false;

		String representation = segments.get(0).trim();
		int slash = representation.indexOf('/');
		if (slash <= 0 || slash != representation.lastIndexOf('/')
				|| slash == representation.length() - 1)
			return false;

		String type = representation.substring(0, slash);
		String subtype = representation.substring(slash + 1);
		if (!httpToken(type) || !httpToken(subtype)
				|| ("*".equals(type) && !"*".equals(subtype)))
			return false;

		Set<String> parameterNames = new LinkedHashSet<>();
		for (int index = 1; index < segments.size(); index++) {
			String segment = segments.get(index).trim();
			int equals = segment.indexOf('=');
			if (equals <= 0 || equals == segment.length() - 1)
				return false;

			String name = segment.substring(0, equals).trim().toLowerCase(Locale.ROOT);
			String rawValue = segment.substring(equals + 1).trim();
			if (!httpToken(name) || !parameterNames.add(name)
					|| !validParameterValue(rawValue))
				return false;

			if ("q".equals(name) && !validQualityValue(rawValue))
				return false;
		}

		return true;
	}

	private boolean validQualityValue(@NonNull String value) {
		if ("0".equals(value) || "1".equals(value))
			return true;
		if (value.length() < 2 || value.length() > 5 || value.charAt(1) != '.')
			return false;

		char whole = value.charAt(0);
		if (whole != '0' && whole != '1')
			return false;

		for (int index = 2; index < value.length(); index++) {
			char digit = value.charAt(index);
			if (digit < '0' || digit > '9' || (whole == '1' && digit != '0'))
				return false;
		}

		return true;
	}

	@NonNull
	private BigDecimal effectiveQuality(@NonNull List<@NonNull MediaRange> ranges,
			@NonNull String type, @NonNull String subtype) {
		return ranges.stream()
				.filter(range -> range.getParameters().isEmpty())
				.filter(range -> mediaRangeMatches(range, type, subtype))
				.max(Comparator.comparingInt(this::mediaRangeSpecificity)
						.thenComparing(MediaRange::getQuality))
				.map(MediaRange::getQuality)
				.orElse(BigDecimal.ZERO);
	}

	private boolean mediaRangeMatches(@NonNull MediaRange range,
			@NonNull String type, @NonNull String subtype) {
		return ("*".equals(range.getType()) || type.equals(range.getType()))
				&& ("*".equals(range.getSubtype()) || subtype.equals(range.getSubtype()));
	}

	private int mediaRangeSpecificity(@NonNull MediaRange range) {
		if ("*".equals(range.getType()))
			return 0;
		if ("*".equals(range.getSubtype()))
			return 1;
		return 2;
	}

	private @Nullable MicrohttpResponse validateRequiredMirroredHeaders(
			@NonNull MicrohttpRequest request,
			McpJsonRpcEnvelope.@NonNull Request wireRequest,
			boolean validatedUnsupportedSelector,
			@NonNull List<@NonNull Header> corsHeaders) {
		return validateRequiredMirroredHeaders(request, wireRequest,
				validatedUnsupportedSelector, corsHeaders, true);
	}

	private @Nullable MicrohttpResponse validateRequiredMirroredHeaders(
			@NonNull MicrohttpRequest request,
			McpJsonRpcEnvelope.@NonNull Request wireRequest,
			boolean validatedUnsupportedSelector,
			@NonNull List<@NonNull Header> corsHeaders,
			boolean validateName) {
		McpHttpEndpointBinding endpointBinding = diagnosticBinding(request);
		List<String> protocolVersions = headerValues(request, MCP_PROTOCOL_VERSION);
		List<String> methods = headerValues(request, MCP_METHOD);
		List<String> names = headerValues(request, MCP_NAME);

		if (protocolVersions.size() != 1 || methods.size() != 1)
			return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
					validatedUnsupportedSelector, corsHeaders);
		try {
			mirroredHeaderCodec.requirePlainString(protocolVersions.get(0));
			mirroredHeaderCodec.requirePlainString(methods.get(0));
		} catch (IllegalArgumentException exception) {
			return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
					validatedUnsupportedSelector, corsHeaders);
		}
		if (!methods.get(0).equals(wireRequest.method()))
			return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
					validatedUnsupportedSelector, corsHeaders);

		Optional<String> expectedName = standardMirroredName(wireRequest);
		if (validateName && requiresMcpName(wireRequest.method())) {
			if (names.size() != 1 || expectedName.isEmpty())
				return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
						validatedUnsupportedSelector, corsHeaders);

			String decodedName;
			try {
				decodedName = mirroredHeaderCodec.decodeString(names.get(0));
			} catch (IllegalArgumentException exception) {
				return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
						validatedUnsupportedSelector, corsHeaders);
			}
			if (!decodedName.equals(expectedName.orElseThrow()))
				return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
						validatedUnsupportedSelector, corsHeaders);
		} else if (validateName && !names.isEmpty()) {
			return headerMismatch(endpointBinding, wireRequest.id(), wireRequest.method(),
					validatedUnsupportedSelector, corsHeaders);
		}

		return null;
	}

	private boolean validatedUnsupportedSelector(
			@NonNull MicrohttpRequest request) {
		List<String> selectors = headerValues(request, MCP_PROTOCOL_VERSION);
		if (selectors.size() != 1)
			return false;
		String selector = selectors.get(0);
		try {
			this.mirroredHeaderCodec.requirePlainString(selector);
		} catch (IllegalArgumentException exception) {
			return false;
		}
		return !this.protocolProfiles.supports(selector);
	}

	private @Nullable String selectLegacyInitializationRevision(
			@NonNull McpHttpEndpointBinding binding,
			@NonNull String requestedRevision) {
		requireNonNull(binding);
		requireNonNull(requestedRevision);
		if (McpLegacyHttpWire.isLegacyRevision(requestedRevision)
				&& binding.revisionEndpoint(requestedRevision).isPresent())
			return requestedRevision;
		if (binding.revisionEndpoint("2025-11-25").isPresent())
			return "2025-11-25";
		if (binding.revisionEndpoint("2025-06-18").isPresent())
			return "2025-06-18";
		return null;
	}

	@NonNull
	private Optional<@NonNull String> readableBodyProtocolVersion(
			McpJsonRpcEnvelope.@NonNull Request request) {
		if (request.params().isEmpty()
				|| !(request.params().orElseThrow() instanceof McpJsonObject params)
				|| !(params.members().get("_meta") instanceof McpJsonObject metadata)
				|| !(metadata.members().get(
						"io.modelcontextprotocol/protocolVersion")
						instanceof McpJsonString protocolVersion))
			return Optional.empty();
		return Optional.of(protocolVersion.value());
	}

	private boolean requiresMcpName(@NonNull String method) {
		return "tools/call".equals(method)
				|| "prompts/get".equals(method)
				|| "resources/read".equals(method)
				|| isTaskRequestMethod(method);
	}

	private static boolean isTaskRequestMethod(@NonNull String method) {
		return TASK_REQUEST_METHODS.contains(requireNonNull(method));
	}

	@NonNull
	private Optional<@NonNull String> standardMirroredName(
			McpJsonRpcEnvelope.@NonNull Request wireRequest) {
		if (!requiresMcpName(wireRequest.method())
				|| wireRequest.params().isEmpty()
				|| !(wireRequest.params().orElseThrow() instanceof McpJsonObject params))
			return Optional.empty();

		String fieldName;
		if ("resources/read".equals(wireRequest.method()))
			fieldName = "uri";
		else if (isTaskRequestMethod(wireRequest.method()))
			fieldName = "taskId";
		else
			fieldName = "name";
		McpJsonValue value = params.members().get(fieldName);
		return value instanceof McpJsonString string
				? Optional.of(string.value())
				: Optional.empty();
	}

	@NonNull
	private MicrohttpResponse headerMismatch(
			@Nullable McpHttpEndpointBinding endpointBinding,
			@NonNull McpJsonRpcId id,
			@NonNull String readableMethod,
			@NonNull List<@NonNull Header> corsHeaders) {
		return headerMismatch(endpointBinding, id, readableMethod, false,
				corsHeaders);
	}

	@NonNull
	private MicrohttpResponse headerMismatch(
			@Nullable McpHttpEndpointBinding endpointBinding,
			@NonNull McpJsonRpcId id,
			@NonNull String readableMethod, boolean validatedUnsupportedSelector,
			@NonNull List<@NonNull Header> corsHeaders) {
		return jsonRpcError(400, "Bad Request", Optional.of(id),
				new McpJsonRpcError(McpJsonRpcError.HEADER_MISMATCH,
						"Header mismatch", supportedVersionDiagnostic(
								endpointBinding, readableMethod,
								validatedUnsupportedSelector)),
				corsHeaders);
	}

	@NonNull
	private MicrohttpResponse strictUnknownMirroredHeader(
			@Nullable McpHttpEndpointBinding endpointBinding,
			@NonNull McpJsonRpcId id,
			@NonNull String readableMethod, boolean validatedUnsupportedSelector,
			@NonNull List<@NonNull Header> corsHeaders) {
		return jsonRpcError(400, "Bad Request", Optional.of(id),
				new McpJsonRpcError(SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER,
						"Unknown mirrored header",
						supportedVersionDiagnostic(endpointBinding, readableMethod,
								validatedUnsupportedSelector)), corsHeaders);
	}

	@NonNull
	private MicrohttpResponse methodNotFound(
			@NonNull McpHttpEndpointBinding endpointBinding,
			@NonNull McpProtocolProfile protocolProfile,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull List<@NonNull Header> corsHeaders) {
		Optional<McpJsonValue> data = supportedVersionDiagnostic(endpointBinding,
				request.method());
		return profiledJsonRpcError(protocolProfile, McpProfileErrorKind.OPERATION,
				404, "Not Found", Optional.of(request.id()),
				new McpJsonRpcError(McpJsonRpcError.METHOD_NOT_FOUND,
						"Method not found", data), corsHeaders);
	}

	@NonNull
	private MicrohttpResponse invalidParams(
			@NonNull McpProtocolProfile protocolProfile,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull List<@NonNull Header> corsHeaders) {
		return profiledJsonRpcError(protocolProfile, McpProfileErrorKind.OPERATION,
				400, "Bad Request", Optional.of(request.id()),
				new McpJsonRpcError(McpJsonRpcError.INVALID_PARAMS,
						"Invalid params", Optional.empty()), corsHeaders);
	}

	@NonNull
	private MicrohttpResponse missingTasksCapability(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpJsonRpcId requestId,
			@NonNull List<@NonNull Header> corsHeaders) {
		return profiledJsonRpcError(protocolProfile,
				McpProfileErrorKind.OPERATION, 400, "Bad Request",
				Optional.of(requireNonNull(requestId)),
				McpJsonRpcError.missingRequiredClientExtension(
						TASKS_EXTENSION_IDENTIFIER),
				corsHeaders);
	}

	@NonNull
	private MicrohttpResponse invalidResourceUriParams(
			@NonNull McpProtocolProfile protocolProfile,
			McpJsonRpcMessage.@NonNull Request request, @NonNull String uri,
			@NonNull List<@NonNull Header> corsHeaders) {
		McpJsonObject data = new McpJsonObject(
				Map.of("uri", new McpJsonString(requireNonNull(uri))));
		return profiledJsonRpcError(protocolProfile, McpProfileErrorKind.RESOURCE_NOT_FOUND,
				400, "Bad Request", Optional.of(request.id()),
				new McpJsonRpcError(McpJsonRpcError.INVALID_PARAMS,
						"Invalid params", Optional.of(data)), corsHeaders);
	}

	@NonNull
	private MicrohttpResponse wireDecodingFailure(
			@Nullable McpHttpEndpointBinding endpointBinding,
			@NonNull McpWireDecodingException exception,
			@Nullable String readableMethod,
			@NonNull List<@NonNull Header> corsHeaders) {
		McpJsonRpcError error = wireDecodingError(endpointBinding, exception,
				readableMethod);
		return jsonRpcError(400, "Bad Request", exception.readableRequestId(),
				error, corsHeaders);
	}

	@NonNull
	private MicrohttpResponse wireDecodingFailure(
			@Nullable McpHttpEndpointBinding endpointBinding,
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpWireDecodingException exception,
			@Nullable String readableMethod,
			@NonNull List<@NonNull Header> corsHeaders) {
		McpJsonRpcError error = wireDecodingError(endpointBinding, exception,
				readableMethod);
		return profiledJsonRpcError(protocolProfile,
				McpProfileErrorKind.REQUEST_MAPPER, 400, "Bad Request",
				exception.readableRequestId(), error, corsHeaders);
	}

	@NonNull
	private McpJsonRpcError wireDecodingError(
			@Nullable McpHttpEndpointBinding endpointBinding,
			@NonNull McpWireDecodingException exception,
			@Nullable String readableMethod) {
		int code = switch (exception.kind()) {
			case PARSE_ERROR -> McpJsonRpcError.PARSE_ERROR;
			case INVALID_REQUEST -> McpJsonRpcError.INVALID_REQUEST;
			case INVALID_PARAMS -> McpJsonRpcError.INVALID_PARAMS;
		};
		String message = switch (exception.kind()) {
			case PARSE_ERROR -> "Parse error";
			case INVALID_REQUEST -> "Invalid Request";
			case INVALID_PARAMS -> "Invalid params";
		};
		Optional<McpJsonValue> data = supportedVersionDiagnostic(endpointBinding,
				readableMethod);
		return new McpJsonRpcError(code, message, data);
	}

	@NonNull
	private Optional<@NonNull McpJsonObject> supportedVersionDiagnostic(
			@Nullable McpHttpEndpointBinding endpointBinding) {
		if (endpointBinding == null)
			return Optional.empty();
		List<McpJsonValue> versions = this.protocolProfiles.revisions().stream()
				.filter(endpointBinding.supportedRevisions()::contains)
				.map(McpJsonString::new)
				.map(McpJsonValue.class::cast)
				.toList();
		return Optional.of(new McpJsonObject(Map.of(
				"supportedVersions", new McpJsonArray(versions))));
	}

	@NonNull
	private Optional<@NonNull McpJsonValue> supportedVersionDiagnostic(
			@Nullable McpHttpEndpointBinding endpointBinding,
			@Nullable String readableMethod) {
		return "initialize".equals(readableMethod)
				? supportedVersionDiagnostic(endpointBinding)
						.map(McpJsonValue.class::cast)
				: Optional.empty();
	}

	@NonNull
	private Optional<@NonNull McpJsonValue> supportedVersionDiagnostic(
			@Nullable McpHttpEndpointBinding endpointBinding,
			@Nullable String readableMethod,
			boolean validatedUnsupportedSelector) {
		return validatedUnsupportedSelector
				? supportedVersionDiagnostic(endpointBinding)
						.map(McpJsonValue.class::cast)
				: supportedVersionDiagnostic(endpointBinding, readableMethod);
	}

	private @Nullable McpHttpEndpointBinding diagnosticBinding(
			@NonNull MicrohttpRequest request) {
		EndpointRuntime endpointRuntime = this.endpointsByPath.get(
				requestPath(requireNonNull(request).uri()));
		return endpointRuntime == null ? null : endpointRuntime.binding();
	}

	@NonNull
	private MicrohttpResponse jsonRpcError(int status, @NonNull String reason,
			@NonNull Optional<@NonNull McpJsonRpcId> id,
			@NonNull McpJsonRpcError error,
			@NonNull List<@NonNull Header> additionalHeaders) {
		return jsonRpcError(status, reason, id, error, additionalHeaders, null);
	}

	@NonNull
	private MicrohttpResponse jsonRpcError(int status, @NonNull String reason,
			@NonNull Optional<@NonNull McpJsonRpcId> id,
			@NonNull McpJsonRpcError error,
			@NonNull List<@NonNull Header> additionalHeaders,
			@Nullable McpRequestContext requestContext) {
		McpJsonRpcMessage.ErrorResponse response = new McpJsonRpcMessage.ErrorResponse(
				id, error, McpJsonObject.empty());
		byte[] encodedResponse = envelopeCodec.encode(response);
		recordProtocolErrorAfterSuccessfulEncoding(error.code(), requestContext);
		return jsonResponse(status, reason, encodedResponse, additionalHeaders);
	}

	@NonNull
	private MicrohttpResponse profiledJsonRpcError(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpProfileErrorKind errorKind,
			int status, @NonNull String reason,
			@NonNull Optional<@NonNull McpJsonRpcId> id,
			@NonNull McpJsonRpcError canonicalError,
			@NonNull List<@NonNull Header> additionalHeaders) {
		return profiledJsonRpcError(protocolProfile, errorKind, status, reason,
				id, canonicalError, additionalHeaders, null);
	}

	@NonNull
	private MicrohttpResponse profiledJsonRpcError(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpProfileErrorKind errorKind,
			int status, @NonNull String reason,
			@NonNull Optional<@NonNull McpJsonRpcId> id,
			@NonNull McpJsonRpcError canonicalError,
			@NonNull List<@NonNull Header> additionalHeaders,
			@Nullable McpRequestContext requestContext) {
		McpJsonRpcError renderedError = renderFrameworkError(protocolProfile,
				errorKind, canonicalError);
		return jsonRpcError(status, reason, id, renderedError,
				additionalHeaders, requestContext);
	}

	@NonNull
	private McpJsonRpcError renderFrameworkError(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpProfileErrorKind errorKind,
			@NonNull McpJsonRpcError canonicalError) {
		return requireNonNull(requireNonNull(protocolProfile).renderFrameworkError(
				requireNonNull(errorKind), requireNonNull(canonicalError)));
	}

	private void recordProtocolErrorAfterSuccessfulEncoding(int code,
			@Nullable McpRequestContext requestContext) {
		recordProducedProtocolError(code, requestContext);
		this.applicationExecutionObserver.drain();
	}

	private McpApplicationExecutionObserver.@Nullable PendingMetricRecord
			recordProducedProtocolError(int code,
					@Nullable McpRequestContext requestContext) {
		if (!producedProtocolErrorCode(code))
			return null;
		return this.applicationExecutionObserver.recordProtocolError(
				code, requestContext);
	}

	private static boolean producedProtocolErrorCode(int code) {
		return PRODUCED_PROTOCOL_ERROR_CODES.contains(code);
	}

	@NonNull
	private MicrohttpResponse jsonResponse(int status, @NonNull String reason,
			byte @NonNull [] body,
			@NonNull List<@NonNull Header> additionalHeaders) {
		List<Header> headers = new ArrayList<>(additionalHeaders.size() + 2);
		headers.add(new Header(CONTENT_TYPE, JSON_MEDIA_TYPE));
		headers.addAll(additionalHeaders);
		return response(status, reason, headers, body);
	}

	@NonNull
	private MicrohttpResponse methodNotAllowed(
			@NonNull List<@NonNull Header> additionalHeaders) {
		return methodNotAllowed(additionalHeaders, MCP_HTTP_METHODS);
	}

	private MicrohttpResponse methodNotAllowed(List<Header> additionalHeaders, Set<HttpMethod> methods) {
		List<Header> headers = new ArrayList<>(additionalHeaders);
		headers.add(new Header("Allow", List.of(HttpMethod.POST, HttpMethod.GET, HttpMethod.DELETE, HttpMethod.OPTIONS)
				.stream().filter(methods::contains).map(Enum::name).collect(java.util.stream.Collectors.joining(", "))));
		return emptyResponse(405, "Method Not Allowed", headers);
	}

	@NonNull
	private MicrohttpResponse emptyResponse(int status, @NonNull String reason,
			@NonNull List<@NonNull Header> additionalHeaders) {
		return response(status, reason, additionalHeaders, EMPTY_BODY);
	}

	@NonNull
	private MicrohttpResponse response(int status, @NonNull String reason,
			@NonNull List<@NonNull Header> additionalHeaders,
			byte @NonNull [] body) {
		List<Header> headers = new ArrayList<>(additionalHeaders.size() + 1);
		headers.add(new Header(CACHE_CONTROL, CACHE_CONTROL_NO_STORE));
		headers.addAll(additionalHeaders);
		return new MicrohttpResponse(status, reason, List.copyOf(headers), body);
	}

	private boolean authorizedHost(@NonNull InetSocketAddress effectiveAddress,
			@NonNull MicrohttpRequest request,
			@NonNull McpHttpEndpointPolicy endpointPolicy) {
		List<String> values = headerValues(request, HOST);
		if (values.size() != 1)
			return false;

		Optional<URI> target = requestTargetUri(request.uri());
		if (target.isEmpty())
			return false;
		URI targetUri = target.orElseThrow();
		// RFC 9112 absolute-form requests use their target authority, not Host.
		String authorityValue = request.uri().startsWith("/")
				? values.get(0) : targetUri.getRawAuthority();
		Optional<HostAuthority> authority = parseHostAuthority(authorityValue);
		if (authority.isEmpty())
			return false;

		HostAuthority hostAuthority = authority.orElseThrow();
		if (hostAuthority.port().isPresent()) {
			if (hostAuthority.port().orElseThrow() != effectiveAddress.getPort())
				return false;
		} else if (effectiveAddress.getPort() !=
				("https".equalsIgnoreCase(targetUri.getScheme()) ? 443 : 80)) {
			return false;
		}

		Set<String> allowedHosts = normalizedAllowedHosts(effectiveAddress,
				endpointPolicy);
		return allowedHosts.contains(hostAuthority.host());
	}

	private void validateConfiguredAllowedHosts(
			@NonNull McpHttpEndpointPolicy endpointPolicy) {
		for (String allowedHost : endpointPolicy.allowedHosts()) {
			Optional<HostAuthority> authority = parseConfiguredHost(allowedHost);
			if (!allowedHost.equals(trimOptionalWhitespace(allowedHost))
					|| authority.isEmpty() || authority.orElseThrow().port().isPresent())
				throw new IllegalArgumentException("Allowed hosts must contain only valid "
						+ "ASCII hostnames or IP literals without a port.");
		}
	}

	private void validateHostAuthorizationConfiguration(
			@NonNull McpHttpEndpointPolicy endpointPolicy) {
		if (requireNonNull(endpointPolicy).allowedHosts().isEmpty()
				&& !safeLoopbackBindHost(transportConfiguration.host()))
			throw new IllegalArgumentException(
					"A non-loopback MCP bind host requires at least one explicitly allowed host.");
	}

	private boolean safeLoopbackBindHost(@NonNull String configuredHost) {
		String host = requireNonNull(configuredHost);
		if ("localhost".equalsIgnoreCase(host))
			return true;

		String literal = host;
		if (literal.startsWith("[") && literal.endsWith("]")
				&& literal.length() > 2) {
			literal = literal.substring(1, literal.length() - 1);
			if (literal.indexOf(':') < 0)
				return false;
		} else if (literal.indexOf('[') >= 0 || literal.indexOf(']') >= 0) {
			return false;
		}

		long ipv4Address = parseIpv4Literal(literal);
		if (ipv4Address >= 0L)
			return (ipv4Address >>> 24) == 127L;

		return HostHeaderValidator.parseIpv6AddressLiteral(literal)
				.map(InetAddress::isLoopbackAddress).orElse(false);
	}

	private long parseIpv4Literal(@NonNull String literal) {
		String[] parts = requireNonNull(literal).split("\\.", -1);
		if (parts.length < 1 || parts.length > 4)
			return -1L;

		long[] values = new long[parts.length];
		for (int partIndex = 0; partIndex < parts.length; partIndex++) {
			String part = parts[partIndex];
			if (part.isEmpty() || part.length() > 10)
				return -1L;
			long value = 0L;
			for (int index = 0; index < part.length(); index++) {
				char character = part.charAt(index);
				if (character < '0' || character > '9')
					return -1L;
				value = value * 10L + character - '0';
				if (value > 0xFFFF_FFFFL)
					return -1L;
			}
			values[partIndex] = value;
		}

		return switch (values.length) {
			case 1 -> values[0];
			case 2 -> values[0] <= 0xFFL && values[1] <= 0xFF_FFFFL
					? values[0] << 24 | values[1] : -1L;
			case 3 -> values[0] <= 0xFFL && values[1] <= 0xFFL
					&& values[2] <= 0xFFFFL
					? values[0] << 24 | values[1] << 16 | values[2] : -1L;
			case 4 -> values[0] <= 0xFFL && values[1] <= 0xFFL
					&& values[2] <= 0xFFL && values[3] <= 0xFFL
					? values[0] << 24 | values[1] << 16
							| values[2] << 8 | values[3] : -1L;
			default -> -1L;
		};
	}

	@NonNull
	private Set<@NonNull String> normalizedAllowedHosts(
			@NonNull InetSocketAddress effectiveAddress,
			@NonNull McpHttpEndpointPolicy endpointPolicy) {
		Set<String> allowedHosts = new LinkedHashSet<>();

		InetAddress address = effectiveAddress.getAddress();
		boolean loopback = address != null && address.isLoopbackAddress();
		if (address == null && effectiveAddress.isUnresolved()) {
			loopback = safeLoopbackBindHost(effectiveAddress.getHostString());
		}
		if (loopback) {
			// RFC 6761 reserves localhost for loopback use. The literal loopback
			// authorities are equally safe aliases regardless of which address
			// family accepted this particular connection.
			addNormalizedHost(allowedHosts, "localhost");
			addNormalizedHost(allowedHosts, "127.0.0.1");
			addNormalizedHost(allowedHosts, "::1");
			addNormalizedHost(allowedHosts, effectiveAddress.getHostString());
			if (address != null)
				addNormalizedHost(allowedHosts, address.getHostAddress());
			addNormalizedHost(allowedHosts, transportConfiguration.host());
		}

		for (String allowedHost : endpointPolicy.allowedHosts())
			addNormalizedHost(allowedHosts, allowedHost);

		return Set.copyOf(allowedHosts);
	}

	private void addNormalizedHost(@NonNull Set<@NonNull String> hosts,
			@NonNull String host) {
		parseConfiguredHost(host).filter(authority -> authority.port().isEmpty())
				.map(HostAuthority::host).ifPresent(hosts::add);
	}

	@NonNull
	private Optional<@NonNull HostAuthority> parseConfiguredHost(
			@Nullable String value) {
		if (value == null)
			return Optional.empty();
		String authority = value.indexOf(':') >= 0 && !value.startsWith("[")
				? "[" + value + "]" : value;
		return parseHostAuthority(authority);
	}

	@NonNull
	private Optional<@NonNull HostAuthority> parseHostAuthority(
			@Nullable String value) {
		if (value == null)
			return Optional.empty();

		String authority = value.trim();
		if (authority.isEmpty() || !ascii(authority) || authority.indexOf('@') >= 0
				|| authority.indexOf('%') >= 0)
			return Optional.empty();

		String host;
		Optional<Integer> port = Optional.empty();
		if (authority.startsWith("[")) {
			int close = authority.indexOf(']');
			if (close <= 1)
				return Optional.empty();

			Optional<String> normalizedIpv6 = normalizeIpv6(
					authority.substring(1, close));
			if (normalizedIpv6.isEmpty())
				return Optional.empty();
			host = normalizedIpv6.orElseThrow();

			String remainder = authority.substring(close + 1);
			if (!remainder.isEmpty()) {
				if (!remainder.startsWith(":"))
					return Optional.empty();
				port = parsePort(remainder.substring(1));
				if (port.isEmpty())
					return Optional.empty();
			}
		} else {
			int colon = authority.lastIndexOf(':');
			if (colon >= 0) {
				if (authority.indexOf(':') != colon)
					return Optional.empty();
				host = authority.substring(0, colon);
				port = parsePort(authority.substring(colon + 1));
				if (port.isEmpty())
					return Optional.empty();
			} else {
				host = authority;
			}

			host = normalizeRegName(host);
			if (host.isEmpty())
				return Optional.empty();
		}

		return Optional.of(new HostAuthority(host, port));
	}

	@NonNull
	private Optional<@NonNull String> normalizeIpv6(@NonNull String value) {
		return HostHeaderValidator.parseIpv6AddressLiteral(value)
				.map(address -> address.getHostAddress().toLowerCase(Locale.ROOT));
	}

	@NonNull
	private String normalizeRegName(@NonNull String value) {
		String host = value.toLowerCase(Locale.ROOT);
		if (host.endsWith("."))
			host = host.substring(0, host.length() - 1);
		if (host.isEmpty())
			return "";

		for (String label : host.split("\\.", -1)) {
			if (label.isEmpty() || label.length() > 63 || label.startsWith("-")
					|| label.endsWith("-"))
				return "";

			for (int index = 0; index < label.length(); index++) {
				char character = label.charAt(index);
				if (!(character >= 'a' && character <= 'z')
						&& !(character >= '0' && character <= '9') && character != '-')
					return "";
			}
		}

		return host;
	}

	@NonNull
	private Optional<@NonNull Integer> parsePort(@NonNull String value) {
		if (value.isEmpty() || value.length() > 5)
			return Optional.empty();

		int port = 0;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character < '0' || character > '9')
				return Optional.empty();
			port = port * 10 + character - '0';
			if (port > 65_535)
				return Optional.empty();
		}

		return Optional.of(port);
	}

	private boolean validOrigin(@Nullable String origin) {
		if (origin == null || !ascii(origin) || "null".equalsIgnoreCase(origin))
			return false;

		try {
			URI uri = new URI(origin);
			String scheme = uri.getScheme();
			if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)))
				return false;

			return uri.getHost() != null && uri.getUserInfo() == null
					&& (uri.getRawPath() == null || uri.getRawPath().isEmpty())
					&& uri.getRawQuery() == null && uri.getRawFragment() == null
					&& uri.getPort() >= -1 && uri.getPort() <= 65_535;
		} catch (URISyntaxException exception) {
			return false;
		}
	}

	@NonNull
	private Optional<@NonNull Set<@NonNull String>> requestedPreflightHeaders(
			@NonNull MicrohttpRequest request) {
		Set<String> headers = new LinkedHashSet<>();
		for (String value : headerValues(request, "Access-Control-Request-Headers")) {
			for (String name : value.split(",", -1)) {
				String normalized = name.trim();
				if (normalized.isEmpty() || !httpToken(normalized))
					return Optional.empty();
				headers.add(normalized);
			}
		}
		return Optional.of(Collections.unmodifiableSet(headers));
	}

	private boolean containsOnlyIgnoreCase(@NonNull Set<@NonNull String> values,
			@NonNull Set<@NonNull String> allowedValues) {
		for (String value : values) {
			boolean allowed = allowedValues.stream()
					.anyMatch(allowedValue -> allowedValue.equalsIgnoreCase(value));
			if (!allowed)
				return false;
		}
		return true;
	}

	@NonNull
	private Request toSokletRequest(@NonNull MicrohttpRequest request,
			@NonNull HttpMethod httpMethod) {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		for (Header header : request.headers()) {
			String matchingName = headers.keySet().stream()
					.filter(name -> name.equalsIgnoreCase(header.name()))
					.findFirst().orElse(header.name());
			Set<String> values = new LinkedHashSet<>(
					headers.getOrDefault(matchingName, Set.of()));
			values.add(header.value());
			headers.put(matchingName, Collections.unmodifiableSet(values));
		}

		return Request.withRawUrl(httpMethod, request.uri())
				.headers(headers)
				.remoteAddress(request.remoteAddress())
				.body(request.body())
				.contentTooLarge(request.contentTooLarge())
				.build();
	}

	@NonNull
	private String requestPath(@NonNull String requestTarget) {
		return requestTargetUri(requestTarget).map(uri -> {
			String path = uri.getRawPath();
			return path == null || path.isEmpty() ? "/" : path;
		}).orElse("");
	}

	@NonNull
	private Optional<URI> requestTargetUri(@NonNull String requestTarget) {
		try {
			// An origin-form path beginning with // is still a path, not a URI authority.
			boolean originForm = requestTarget.startsWith("/");
			URI uri = new URI(originForm ? "http://soklet.invalid" + requestTarget : requestTarget);
			if (uri.getRawFragment() != null || uri.getRawUserInfo() != null
					|| uri.getRawAuthority() == null
					|| !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
					|| (!originForm && parseHostAuthority(uri.getRawAuthority()).isEmpty()))
				return Optional.empty();
			return Optional.of(uri);
		} catch (URISyntaxException exception) {
			return Optional.empty();
		}
	}

	@NonNull
	private Optional<@NonNull HttpMethod> httpMethod(@NonNull String method) {
		try {
			return Optional.of(HttpMethod.valueOf(method));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	@NonNull
	private List<@NonNull String> headerValues(@NonNull MicrohttpRequest request,
			@NonNull String name) {
		List<String> values = new ArrayList<>();
		for (Header header : request.headers()) {
			if (name.equalsIgnoreCase(header.name()))
				values.add(trimOptionalWhitespace(header.value()));
		}
		return List.copyOf(values);
	}

	/** Preserves physical field-value order and duplicates without parsing. */
	@NonNull
	private List<@NonNull String> physicalHeaderValues(
			@NonNull MicrohttpRequest request, @NonNull String name) {
		List<String> values = new ArrayList<>();
		for (Header header : requireNonNull(request).headers())
			if (requireNonNull(name).equalsIgnoreCase(header.name()))
				values.add(header.value());
		return List.copyOf(values);
	}

	private boolean localizationVaryRequired(@NonNull MicrohttpRequest request) {
		if ("OPTIONS".equals(requireNonNull(request).method()))
			return false;
		EndpointRuntime endpointRuntime = this.endpointsByPath.get(
				requestPath(request.uri()));
		return endpointRuntime != null && (endpointRuntime.binding()
				.endpointPolicy().localizationEnabled()
				|| endpointRuntime.binding().endpoint().skillsPlan()
						.map(McpServerRuntimeBridge.SkillsPlan::acceptLanguageVaryRequired).orElse(false));
	}

	@NonNull
	private Optional<@NonNull String> singleHeader(@NonNull MicrohttpRequest request,
			@NonNull String name) {
		List<String> values = headerValues(request, name);
		return values.size() == 1 ? Optional.of(values.get(0)) : Optional.empty();
	}

	@NonNull
	private String trimOptionalWhitespace(@NonNull String value) {
		int start = 0;
		int end = value.length();
		while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t'))
			start++;
		while (end > start && (value.charAt(end - 1) == ' '
				|| value.charAt(end - 1) == '\t'))
			end--;
		return value.substring(start, end);
	}

	@NonNull
	private List<@NonNull String> splitSemicolonAware(@NonNull String value) {
		List<String> segments = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		boolean escaped = false;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (escaped) {
				current.append(character);
				escaped = false;
			} else if (quoted && character == '\\') {
				current.append(character);
				escaped = true;
			} else if (character == '"') {
				current.append(character);
				quoted = !quoted;
			} else if (character == ';' && !quoted) {
				segments.add(current.toString());
				current.setLength(0);
			} else {
				current.append(character);
			}
		}
		if (quoted || escaped)
			return List.of();
		segments.add(current.toString());
		return List.copyOf(segments);
	}

	@NonNull
	private String unquote(@NonNull String value) {
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
			return value.substring(1, value.length() - 1);
		return value;
	}

	private boolean validParameterValue(@NonNull String value) {
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			boolean escaped = false;
			for (int index = 1; index < value.length() - 1; index++) {
				char character = value.charAt(index);
				if (escaped) {
					escaped = false;
				} else if (character == '\\') {
					escaped = true;
				} else if (character < 0x20 || character == 0x7F || character == '"') {
					return false;
				}
			}
			return !escaped;
		}

		return httpToken(value);
	}

	@NonNull
	private List<@NonNull String> splitCommaAware(@NonNull String value) {
		List<String> fragments = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		boolean escaped = false;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (escaped) {
				current.append(character);
				escaped = false;
			} else if (quoted && character == '\\') {
				current.append(character);
				escaped = true;
			} else if (character == '"') {
				current.append(character);
				quoted = !quoted;
			} else if (character == ',' && !quoted) {
				fragments.add(current.toString().trim());
				current.setLength(0);
			} else {
				current.append(character);
			}
		}
		if (quoted || escaped)
			return List.of();
		fragments.add(current.toString().trim());
		return List.copyOf(fragments);
	}

	private boolean httpToken(@NonNull String value) {
		if (value.isEmpty())
			return false;

		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (!(character >= '0' && character <= '9')
					&& !(character >= 'A' && character <= 'Z')
					&& !(character >= 'a' && character <= 'z')
					&& "!#$%&'*+-.^_`|~".indexOf(character) < 0)
				return false;
		}
		return true;
	}

	private boolean ascii(@NonNull String value) {
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character < 0x21 || character > 0x7E)
				return false;
		}
		return true;
	}

	/** Session-owned historical URI evidence; callback exit and logical retirement are separate. */
	@ThreadSafe
	private final class LegacyUriGrantControl implements McpLegacySessionStore.GrantTarget {
		private final Object lock = new Object();
		private final String path;
		private final String revision;
		private final String uri;
		private final McpEffectivePartition partition;
		private final McpRequestContext initialContext;
		private final McpApplicationExecution application;
		private final LifecycleRequestProcessor processor;
		private @Nullable McpLegacySessionStore.Grant grant;
		private @Nullable McpApplicationExecution.BoundedPolicyCancellation cancellation;
		private Optional<Object> applicationContext;
		private Optional<Instant> previousValidUntil = Optional.empty();
		private long acceptedContextGeneration;
		private long renewalNanos;
		private int callbacks;
		private boolean initialFinished;
		private boolean initialReleased;
		private boolean initialized;
		private boolean retired;
		private boolean pending;

		LegacyUriGrantControl(String path, String revision, String uri, McpEffectivePartition partition,
				McpRequestContext initialContext, McpApplicationExecution application, LifecycleRequestProcessor processor) {
			this.path = path; this.revision = revision; this.uri = uri; this.partition = partition;
			this.initialContext = initialContext; this.application = application; this.processor = processor;
			this.applicationContext = initialContext.getAdmissionIdentity().getApplicationContext();
		}
		void bind(McpLegacySessionStore.Grant grant) { synchronized (lock) { this.grant = grant; } }
		void bindCancellation(McpApplicationExecution.BoundedPolicyCancellation next) {
			boolean canceled;
			synchronized (lock) { cancellation = next; canceled = retired; }
			if (canceled) next.cancel(StreamTerminationReason.APPLICATION_CANCELED);
		}
		void callbackEntered() { synchronized (lock) { callbacks++; } }
		void callbackExited() { synchronized (lock) { callbacks--; } releaseInitialIfComplete(); }
		void initialFinished() { synchronized (lock) { initialFinished = true; } releaseInitialIfComplete(); }
		void releaseInitialIfComplete() {
			McpLegacySessionStore.Grant release = null;
			boolean remove;
			synchronized (lock) {
				if (initialFinished && callbacks == 0 && !initialReleased) { initialReleased = true; release = grant; }
				remove = retired && callbacks == 0 && !pending && initialFinished;
			}
			if (release != null) release.physicalComplete();
			if (remove) legacyUriGrantControls.remove(this);
		}
		McpSubscriptionAuthorization authorize(long deadline,
				McpApplicationExecution.BoundedPolicyCancellation next, Runnable physicalExit) throws Exception {
			Optional<Object> context; Optional<Instant> previous;
			McpLegacySessionStore.Grant authorizationGrant;
			synchronized (lock) { context = applicationContext; previous = previousValidUntil; authorizationGrant = requireNonNull(grant); }
			long remaining = deadline - applicationClock.nanoTime();
			Instant callbackDeadline = applicationClock.instant().plusNanos(Math.max(0L, remaining));
			McpSubscriptionAuthorizationContext snapshot = new SubscriptionAuthorizationContextSnapshot(initialContext,
					context, previous, callbackDeadline, false, false, false, Set.of(URI.create(uri)), Set.of());
			McpInvocationFeatures features = McpInvocationFeatures.fromFeatures(Map.of(CancelationToken.class, next));
			return application.invokeBoundedPolicy(() -> {
				if (!authorizationGrant.tryBeginAuthorization()) throw new McpApplicationPolicyCapacityException();
				try {
					return requireNonNull(subscriptionRuntimeConfiguration.authorizer().orElseThrow().authorize(snapshot, features),
							"The MCP resource authorizer returned null.");
				} finally { authorizationGrant.finishAuthorization(); }
			},
					deadline, next, physicalExit);
		}
		void accept(McpSubscriptionAuthorization.Allowed decision, long lease, long acceptedGeneration) {
			synchronized (lock) {
				if (retired || acceptedGeneration <= acceptedContextGeneration) return;
				long now = applicationClock.nanoTime();
				initialized = true;
				acceptedContextGeneration = acceptedGeneration;
				applicationContext = decision.getApplicationContext();
				previousValidUntil = Optional.of(applicationClock.instant().plusNanos(Math.max(0L, lease - now)));
				if (grant == null || grant.generation() != acceptedGeneration || grant.isFenced()) {
					renewalNanos = now;
					return;
				}
				renewalNanos = now + Math.max(1L, (lease - now) / 2L);
			}
		}
		@Override public void fence(long generation) {
			McpApplicationExecution.BoundedPolicyCancellation old;
			synchronized (lock) { renewalNanos = applicationClock.nanoTime(); old = cancellation; }
			recheckLegacyWriters(path, revision);
			if (old != null && old.isActive()) old.cancel(StreamTerminationReason.APPLICATION_CANCELED);
		}
		@Override public void retire(McpLegacySessionStore.GrantCause cause) {
			McpApplicationExecution.BoundedPolicyCancellation old;
			synchronized (lock) { retired = true; old = cancellation; }
			recheckLegacyWriters(path, revision);
			if (old != null && old.isActive()) old.cancel(cause == McpLegacySessionStore.GrantCause.SERVER_STOPPING
					? StreamTerminationReason.SERVER_STOPPING : StreamTerminationReason.APPLICATION_CANCELED);
			releaseInitialIfComplete();
		}
		void onTimer(long now) {
			synchronized (lock) { if (retired || !initialized || pending || now - renewalNanos < 0L) return; }
			boolean capacityRejected;
			synchronized (legacyMaintenanceLock) {
				while (!legacyMaintenanceDispatchTimes.isEmpty()
						&& now - legacyMaintenanceDispatchTimes.peekFirst() >= TimeUnit.SECONDS.toNanos(1))
					legacyMaintenanceDispatchTimes.removeFirst();
				capacityRejected = legacyMaintenanceActive >= MAXIMUM_LEGACY_MAINTENANCE_JOBS
						|| legacyMaintenanceDispatchTimes.size() >= MAXIMUM_LEGACY_MAINTENANCE_DISPATCHES_PER_SECOND;
				if (!capacityRejected) {
					synchronized (lock) { if (retired || pending) return; pending = true; }
					legacyMaintenanceActive++; legacyMaintenanceDispatchTimes.addLast(now);
				}
			}
			if (capacityRejected) { retryCapacity(); return; }
			LegacyHttpPolicyWork work = new LegacyHttpPolicyWork(() -> {
				synchronized (lock) { pending = false; }
				synchronized (legacyMaintenanceLock) { legacyMaintenanceActive--; }
				releaseInitialIfComplete();
			});
			processor.executeTaskNotificationProjection(new TaskNotificationProjectionJob(this,
					() -> { try { renew(work); } finally { work.complete(); } },
					() -> { work.complete(); retryCapacity(); }));
		}
		void renew(LegacyHttpPolicyWork work) {
			McpLegacySessionStore.Grant current;
			McpApplicationExecution.BoundedPolicyCancellation next = application.newBoundedPolicyCancellation();
			synchronized (lock) { if (retired || grant == null) return; current = grant; cancellation = next; }
			long generation = current.generation();
			Optional<McpLegacySessionStore.GrantWork> held = current.acquireWork(generation);
			if (held.isEmpty()) return;
			McpLegacySessionStore.GrantWork physical = held.orElseThrow();
			boolean callbackOwned = false;
			try {
				if (!work.reservePartition(partition)) { retryCapacity(current, generation); return; }
				long now = applicationClock.nanoTime();
				long deadline = minimumDeadline(now, current.deadlineNanos(), now
						+ subscriptionRuntimeConfiguration.authorizationTimeout().toNanos());
				work.callbackEntered(); callbackEntered(); callbackOwned = true;
				McpSubscriptionAuthorization result = authorize(deadline, next,
						() -> { physical.close(); callbackExited(); work.callbackExited(); });
				if (result instanceof McpSubscriptionAuthorization.Denied) {
					boolean retired = current.retireIfCurrent(generation, McpLegacySessionStore.GrantCause.AUTHORIZATION_DENIED);
					maintenanceOutcome(retired ? McpMetricsEvent.SubscriptionMaintenance.Outcome.DENIED
							: McpMetricsEvent.SubscriptionMaintenance.Outcome.STALE_RESULT_DISCARDED); return;
				}
				McpSubscriptionAuthorization.Allowed allowed = (McpSubscriptionAuthorization.Allowed) result;
				long lease = legacyAuthorizationDeadline(allowed.getValidUntil(), current.totalDeadlineNanos());
				McpLegacySessionStore.Status status = current.renewStatus(generation, lease);
				if (status == McpLegacySessionStore.Status.GLOBAL_CAPACITY) { retryCapacity(current, generation); return; }
				if (status == McpLegacySessionStore.Status.ACCEPTED) {
					synchronized (lock) {
						if (retired || current.generation() != generation + 1L) {
							maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.STALE_RESULT_DISCARDED); return;
						}
						accept(allowed, lease, generation + 1L);
					}
					recheckLegacyWriters(path, revision); flushLegacyNotifications(path, revision);
					maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
				} else maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.STALE_RESULT_DISCARDED);
			} catch (McpApplicationPolicyCapacityException exception) {
				retryCapacity(current, generation);
			} catch (Throwable throwable) {
				boolean stale = !current.retireIfCurrent(generation, McpLegacySessionStore.GrantCause.AUTHORIZATION_FAILED);
				maintenanceOutcome(stale ? McpMetricsEvent.SubscriptionMaintenance.Outcome.STALE_RESULT_DISCARDED
						: throwable instanceof McpApplicationPolicyDeadlineException
						? McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT : McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED);
			} finally { if (!callbackOwned) physical.close(); }
		}
		void retryCapacity() {
			McpLegacySessionStore.Grant current;
			synchronized (lock) { current = retired ? null : grant; }
			if (current == null) return;
			retryCapacity(current, current.generation());
		}
		void retryCapacity(McpLegacySessionStore.Grant current, long generation) {
			if (!current.fenceIfCurrent(generation)) {
				maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.STALE_RESULT_DISCARDED); return;
			}
			maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.CAPACITY_REJECTED);
			synchronized (lock) { renewalNanos = applicationClock.nanoTime() + LEGACY_MAINTENANCE_RETRY_NANOS; }
		}
		void maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome outcome) {
			try {
				applicationExecutionObserver.recordSubscriptionMaintenance(path,
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION, outcome);
				transportMetricDrainScheduler.schedule();
			} catch (Throwable ignored) { }
		}
	}

	/** One nonresumable HTTP GET, with a bounded, generation-fenced authorization lease. */
	@ThreadSafe
	private final class LegacyGetControl implements McpLegacySessionStore.GetTarget {
		private final RequestControl control;
		private final Request originalRequest;
		private final EndpointRuntime endpoint;
		private final String revision;
		private final Set<McpResourceNotificationType> offered;
		private final List<Header> headers;
		private @Nullable McpLegacySessionStore.Get registration;
		private @Nullable McpRequestSseStream stream;
		private @Nullable McpApplicationExecution.BoundedPolicyCancellation cancellation;
		private Set<McpResourceNotificationType> authorizedTypes = Set.of();
		private @Nullable McpEffectivePartition fixedPartition;
		private long reconciliationGeneration;
		private long establishmentGeneration = 1L;
		private long renewalNanos;
		private long openedNanos;
		private long closeDeadlineNanos;
		private boolean headOwned;
		private boolean closing;
		private boolean writerTerminated;
		private boolean pending;
		private boolean reconciliation;
		private McpStreamTerminationReason closeReason = McpStreamTerminationReason.COMPLETED;

		LegacyGetControl(RequestControl control, Request originalRequest, EndpointRuntime endpoint,
				String revision, Set<McpResourceNotificationType> offered, List<Header> headers) {
			this.control = control; this.originalRequest = originalRequest; this.endpoint = endpoint;
			this.revision = revision; this.offered = Set.copyOf(offered); this.headers = List.copyOf(headers);
		}
		long establishmentGeneration() { synchronized (control.lock) { return establishmentGeneration; } }
		boolean canEstablish(long generation) {
			synchronized (control.lock) {
				return !closing && !control.terminal && !control.canceled
						&& establishmentGeneration == generation;
			}
		}
		void bindCancellation(McpApplicationExecution.BoundedPolicyCancellation cancellation) {
			boolean cancel;
			synchronized (control.lock) { this.cancellation = cancellation; cancel = closing; }
			if (cancel) cancellation.cancel(StreamTerminationReason.APPLICATION_CANCELED);
		}
		void prepareStream() {
			McpRequestSseStream prepared = control.simulation == null
					? new McpRequestSseStream(transportConfiguration.streamQueueCapacity(), jsonLimits,
							envelopeCodec, jsonCodec, control.protocolProfile(), applicationClock,
							new McpOutboundChannel.Listener() {
								@Override public void didWrite(long byteCount, long timestampNanos) { }
								@Override public void didApplyBackpressure() { }
								@Override public void didTerminate(StreamTerminationReason reason, @Nullable Throwable cause) {
									terminated(reason, null, cause);
								}
							})
					: new McpRequestSseStream(envelopeCodec, jsonCodec, control.protocolProfile(),
							control.simulation.openChannel(this::terminated));
			synchronized (control.lock) { stream = prepared; }
		}
		boolean open(McpLegacySessionStore.Get registration, long expectedGeneration,
				Set<McpResourceNotificationType> selected, McpEffectivePartition partition) {
			Consumer<MicrohttpResponse> callback;
			MicrohttpResponse response;
			synchronized (legacyMaintenanceLock) {
			synchronized (control.streamObservationTransitionLock) {
				synchronized (control.lock) {
					this.registration = registration;
					if (!canEstablish(expectedGeneration) || !registration.active()
							|| reconciliationGeneration != legacyTransportReconciliationGeneration) {
						registration.logicalComplete(); return false;
					}
					authorizedTypes = Set.copyOf(selected); fixedPartition = partition;
					headOwned = true;
					openedNanos = applicationClock.nanoTime();
					renewalNanos = openedNanos + Math.max(1L, (registration.deadlineNanos() - openedNanos) / 2L);
					control.responseStream = requireNonNull(stream);
					control.deadlineNanos = registration.totalDeadlineNanos();
					response = stream.response(headers);
					control.noteLegacyHttpResponse(response);
					callback = control.takeResponseCallback();
				}
				control.markStreamOpenedInOrder(true);
				applicationExecutionObserver.recordSubscriptionOpened(endpoint.path());
			}
			}
			transportMetricDrainScheduler.schedule();
			if (callback == null || control.deliverResponse(callback, response) != null)
				close(StreamTerminationReason.CLIENT_DISCONNECTED, McpStreamTerminationReason.CLIENT_DISCONNECTED, null);
			flushLegacyNotifications(endpoint.path(), revision);
			return true;
		}
		void establishmentFinished() {
			McpRequestSseStream abandoned;
			synchronized (control.lock) { abandoned = headOwned ? null : stream; }
			if (abandoned != null) abandoned.close(StreamTerminationReason.APPLICATION_CANCELED, null);
			control.releaseLegacyPhysicalIfComplete();
		}
		@Override public void retire(McpLegacySessionStore.GetCause cause) {
			McpStreamTerminationReason exact = switch (cause) {
				case SESSION_EXPIRED -> McpStreamTerminationReason.SESSION_EXPIRED;
				case SESSION_CLOSED -> McpStreamTerminationReason.SESSION_CLOSED;
				case SERVER_STOPPING -> McpStreamTerminationReason.SERVER_STOPPING;
				case LEASE_EXPIRED -> McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_EXPIRED;
				case TOTAL_LIFETIME_EXPIRED -> McpStreamTerminationReason.DEADLINE_EXCEEDED;
				case AUTHORIZATION_DENIED -> McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_DENIED;
				case AUTHORIZATION_FAILED -> McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_CHECK_FAILED;
				case REPLACED -> McpStreamTerminationReason.COMPLETED;
			};
			close(cause == McpLegacySessionStore.GetCause.SERVER_STOPPING
					? StreamTerminationReason.SERVER_STOPPING : StreamTerminationReason.APPLICATION_CANCELED, exact, null);
		}
		/** Invalid generations are purged at the guarded writer boundary before renewal. */
		@Override public void fence(long generation) {
			McpApplicationExecution.BoundedPolicyCancellation old;
			synchronized (control.lock) {
				establishmentGeneration++;
				reconciliation = true;
				renewalNanos = applicationClock.nanoTime();
				old = cancellation;
			}
			if (old != null && old.isActive()) old.cancel(StreamTerminationReason.APPLICATION_CANCELED);
			recheckNotifications();
		}
		Optional<McpOutboundChannel.OfferResult> offerNotification(McpJsonRpcMessage.Notification message, Object key,
				McpLegacySessionStore.NotificationReservation reservation) {
			McpRequestSseStream current;
			synchronized (control.lock) { current = headOwned && !closing && !writerTerminated ? stream : null; }
			if (current == null) { reservation.release(); return Optional.empty(); }
			return current.offerGuardedCoalescingMessage(message, key, reservation::valid, reservation::release);
		}
		void recheckNotifications() {
			McpRequestSseStream current;
			synchronized (control.lock) { current = stream; }
			if (current != null) current.recheckGuardedFrames();
		}
		void shedNotificationPressure() {
			close(StreamTerminationReason.BACKPRESSURE, McpStreamTerminationReason.BACKPRESSURE, null);
		}
		void reconcile() {
			McpLegacySessionStore.Get current;
			synchronized (control.lock) { current = registration; }
			if (current == null) fence(0L);
			else current.fence();
		}
		void close(StreamTerminationReason reason, McpStreamTerminationReason exact, @Nullable Throwable cause) {
			McpRequestSseStream current;
			McpLegacySessionStore.Get currentRegistration;
			McpApplicationExecution.BoundedPolicyCancellation policy;
			boolean offered;
			synchronized (control.lock) {
				if (closing) return;
				closing = true; establishmentGeneration++; closeReason = exact;
				closeDeadlineNanos = applicationClock.nanoTime() + subscriptionRuntimeConfiguration.shutdownTimeout().toNanos();
				current = stream; currentRegistration = registration; policy = cancellation; offered = headOwned;
			}
			if (currentRegistration != null) currentRegistration.logicalComplete();
			if (policy != null && policy.isActive()) policy.cancel(reason);
			if (current != null) {
				if (reason != StreamTerminationReason.BACKPRESSURE && offered && current.completeWithoutMessage()) return;
				current.close(reason, cause);
			} else if (offered) terminated(reason, null, cause);
		}
		void terminated(StreamTerminationReason reason, @Nullable McpStreamTerminationReason exact,
				@Nullable Throwable cause) {
			boolean observed;
			McpLegacySessionStore.Get current;
			McpApplicationExecution.BoundedPolicyCancellation policy;
			synchronized (control.lock) {
				if (writerTerminated) return;
				writerTerminated = true; observed = headOwned;
				if (!closing) closeReason = exact == null ? McpServerRuntimeBridge.toPublicTerminationReason(reason) : exact;
				closing = true; current = registration; policy = cancellation;
				if (observed) {
					control.markTerminalWhileLocked(); control.responseCallback = null;
				}
			}
			if (current != null) current.logicalComplete();
			if (policy != null && policy.isActive()) policy.cancel(reason);
			if (observed) {
				if (control.simulation != null) {
					control.simulation.reserveRuntimeReason(closeReason);
					control.simulation.didFinishRequest(McpRequestOutcome.COMPLETE, cause == null ? List.of() : List.of(cause));
				}
				control.markStreamClosed(reason, closeReason);
				applicationExecutionObserver.recordSubscriptionClosed(endpoint.path(), closeReason,
						Duration.ofNanos(Math.max(0L, applicationClock.nanoTime() - openedNanos)));
				transportMetricDrainScheduler.schedule();
				control.finishTransportLifecycle();
			}
			control.releaseLegacyPhysicalIfComplete();
		}
		void onTimer(long now) {
			McpRequestSseStream current;
			boolean finishClose;
			boolean due;
			synchronized (control.lock) {
				if (!headOwned || writerTerminated) return;
				current = stream;
				finishClose = closing && now - closeDeadlineNanos >= 0L;
				due = !closing && !pending && now - renewalNanos >= 0L;
			}
			if (finishClose) { requireNonNull(current).close(StreamTerminationReason.RESPONSE_TIMEOUT, null); return; }
			if (current != null && !closing) {
				if (current.failIfWriteIdleExpired(now, transportConfiguration.responseWriteIdleTimeout().toNanos(),
						StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null)) return;
				current.offerKeepAliveIfWriteIdleExpired(now, transportConfiguration.keepAliveInterval().toNanos());
			}
			if (due) scheduleRenewal(now);
		}
		void scheduleRenewal(long now) {
			boolean capacityRejected;
			synchronized (legacyMaintenanceLock) {
				while (!legacyMaintenanceDispatchTimes.isEmpty()
						&& now - legacyMaintenanceDispatchTimes.peekFirst() >= TimeUnit.SECONDS.toNanos(1))
					legacyMaintenanceDispatchTimes.removeFirst();
				capacityRejected = legacyMaintenanceActive >= MAXIMUM_LEGACY_MAINTENANCE_JOBS
						|| legacyMaintenanceDispatchTimes.size() >= MAXIMUM_LEGACY_MAINTENANCE_DISPATCHES_PER_SECOND;
				if (!capacityRejected) {
					synchronized (control.lock) {
						if (closing || pending || !headOwned) return;
						pending = true;
					}
					legacyMaintenanceActive++; legacyMaintenanceDispatchTimes.addLast(now);
				}
			}
			if (capacityRejected) { retryCapacity(); return; }
			LegacyHttpPolicyWork work = new LegacyHttpPolicyWork(() -> {
				synchronized (control.lock) { pending = false; }
				synchronized (legacyMaintenanceLock) { legacyMaintenanceActive--; }
			});
			control.processor.executeTaskNotificationProjection(new TaskNotificationProjectionJob(this,
					() -> { try { renew(work); } finally { work.complete(); } },
					() -> { work.complete(); retryCapacity(); }));
		}
		void renew(LegacyHttpPolicyWork work) {
			McpLegacySessionStore.Get current;
			long generation;
			long deadline;
			McpApplicationExecution.BoundedPolicyCancellation next = control.application.newBoundedPolicyCancellation();
			synchronized (control.lock) {
				if (closing || registration == null) return;
				current = registration; generation = current.generation(); cancellation = next;
				long now = applicationClock.nanoTime();
				deadline = minimumDeadline(now, current.deadlineNanos(), now
						+ subscriptionRuntimeConfiguration.authorizationTimeout().toNanos());
			}
			try {
				if (!work.reservePartition(requireNonNull(fixedPartition))) { retryCapacity(current, generation); return; }
				LegacyHttpAuthorization result = authorizeLegacyHttp(control, originalRequest, endpoint,
						revision, offered, true, deadline, next, work);
				if (result.rejection() != null) {
					boolean retired = current.retireIfCurrent(generation, McpLegacySessionStore.GetCause.AUTHORIZATION_DENIED);
					maintenanceOutcome(retired ? McpMetricsEvent.SubscriptionMaintenance.Outcome.DENIED
							: McpMetricsEvent.SubscriptionMaintenance.Outcome.STALE_RESULT_DISCARDED); return;
				}
				McpEffectivePartition partition = requireNonNull(result.partition());
				try {
					McpLegacySessionTransportAdmission.Accepted accepted = requireNonNull(result.accepted());
					long lease = legacyLeaseDeadline(accepted, current.totalDeadlineNanos());
					McpLegacySessionStore.Status renewalStatus = current.renewStatus(generation, requireNonNull(result.owner()), partition, lease, accepted.notificationTypes());
					if (renewalStatus == McpLegacySessionStore.Status.GLOBAL_CAPACITY) { retryCapacity(current, generation); return; }
					if (renewalStatus == McpLegacySessionStore.Status.ACCEPTED) {
						synchronized (control.lock) {
							if (current.generation() != generation + 1L) { maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.STALE_RESULT_DISCARDED); return; }
							authorizedTypes = accepted.notificationTypes(); reconciliation = false;
							long now = applicationClock.nanoTime();
							renewalNanos = now + Math.max(1L, (current.deadlineNanos() - now) / 2L);
						}
						maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
						recheckNotifications(); flushLegacyNotifications(endpoint.path(), revision);
					}
				} finally { work.complete(); }
			} catch (McpApplicationPolicyCapacityException exception) {
				retryCapacity(current, generation);
			} catch (Throwable throwable) {
				boolean stale = !current.retireIfCurrent(generation, McpLegacySessionStore.GetCause.AUTHORIZATION_FAILED);
				maintenanceOutcome(stale ? McpMetricsEvent.SubscriptionMaintenance.Outcome.STALE_RESULT_DISCARDED
						: throwable instanceof McpApplicationPolicyDeadlineException
						? McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT : McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED);
			}
		}
		void maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome outcome) {
			try {
				applicationExecutionObserver.recordSubscriptionMaintenance(endpoint.path(),
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION, outcome);
				transportMetricDrainScheduler.schedule();
			} catch (Throwable ignored) { }
		}
		void retryCapacity() {
			McpLegacySessionStore.Get current;
			synchronized (control.lock) { current = closing ? null : registration; }
			if (current == null) return;
			retryCapacity(current, current.generation());
		}
		void retryCapacity(McpLegacySessionStore.Get current, long generation) {
			if (!current.fenceIfCurrent(generation)) {
				maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.STALE_RESULT_DISCARDED); return;
			}
			maintenanceOutcome(McpMetricsEvent.SubscriptionMaintenance.Outcome.CAPACITY_REJECTED);
			synchronized (control.lock) {
				if (!closing) renewalNanos = applicationClock.nanoTime() + LEGACY_MAINTENANCE_RETRY_NANOS;
			}
		}
		void physicalComplete() {
			McpLegacySessionStore.Get current;
			synchronized (control.lock) { current = registration; registration = null; authorizedTypes = Set.of(); }
			if (current != null) current.physicalComplete();
			legacyGetControls.remove(control, this);
		}
	}

	/**
	 * Arbitrates protocol-task ownership against asynchronous application
	 * ownership for one transport request. Handoff reserves application ownership
	 * at the generation boundary, then invokes registration after releasing this
	 * control's lock. The application-entry gate closes the cancellation gap
	 * without running an application-supplied executor under the monitor.
	 */
	@ThreadSafe
	private final class RequestControl {
		@NonNull
		private final MicrohttpRequest request;
		private long deadlineNanos;
		@NonNull
		private final LifecycleRequestProcessor processor;
		@NonNull
		private final McpApplicationExecution application;
		private final @Nullable Request publicRequest;
		private final @Nullable McpSimulationRuntime simulation;
		private final @Nullable Runnable lifecycleAdmission;
		@NonNull
		private final List<@NonNull String> acceptLanguageValues;
		private final boolean acceptLanguageVaryRequired;
		@NonNull
		private final Object lock;
		@NonNull
		private final Object streamObservationTransitionLock;
		private final McpApplicationExecution.@NonNull BoundedPolicyCancellation
				catalogAccessCancellation;
		private @Nullable FutureTask<@Nullable Void> protocolTask;
		private @Nullable Consumer<@NonNull MicrohttpResponse> responseCallback;
		private @Nullable McpProtocolProfile protocolProfile;
		private boolean identifiedRequestExchange;
		private @Nullable McpRequestSseStream responseStream;
		private boolean legacyResponseDeliveryDetached;
		private boolean responseStreamCommitted;
		private @Nullable StreamTerminationReason cancellationReason;
		private @Nullable Throwable cancellationCause;
		private @Nullable McpRuntimeRequestObservation requestObservation;
		private @Nullable SubscriptionRegistration subscriptionCapReservation;
		private @Nullable SubscriptionRegistration subscriptionRegistration;
		private @Nullable StreamTerminationReason plannedSubscriptionCloseReason;
		private @Nullable McpStreamTerminationReason
				plannedSubscriptionCloseExactReason;
		private @Nullable SubscriptionStreamFailure pendingSubscriptionStreamFailure;
		@NonNull
		private Optional<@NonNull McpRequestContext> publicRequestContext;
		private McpJsonRpcMessage.@Nullable ResultResponse preRenderedSubscriptionTerminal;
		private @Nullable RequestObservationResult plannedRequestObservationResult;
		private @Nullable RequestObservationTerminal requestObservationTerminal;
		private long requestObservationStartedAtNanos;
		private boolean requestObservationDelivered;
		@NonNull
		private List<@NonNull Header> deadlineResponseHeaders;
		@NonNull
		private final TaskNotificationProjectionQueue
				taskNotificationProjectionQueue;
		@NonNull
		private final McpCatalogProjectionQueue catalogProjectionQueue;
		@NonNull
		private final Object taskProjectionSchedulerOwner;
		@NonNull
		private final Object catalogProjectionSchedulerOwner;
		@NonNull
		private final Object catalogOfferLock;
		@NonNull
		private final Object authorizationSchedulerOwner;
		@NonNull
		private Optional<@NonNull Object>
				subscriptionAuthorizationApplicationContext;
		private @Nullable Instant subscriptionAuthorizationValidUntil;
		private @Nullable SubscriptionAuthorizationCheck
				subscriptionAuthorizationCheck;
		private @Nullable CatalogProjectionCheck catalogProjectionCheck;
		private long subscriptionAuthorizationGeneration;
		private long catalogAuthorizationRevision;
		private long subscriptionAuthorizationReconciliationGeneration;
		private long subscriptionAuthorizationExpiryNanos;
		private long subscriptionAuthorizationRenewalNanos;
		private boolean subscriptionAuthorizationRenewalScheduled;
		private boolean subscriptionAuthorizationEstablished;
		private boolean subscriptionAuthorizationFenced;
		private boolean subscriptionAuthorizationReconciliationPending;
		private boolean subscriptionAuthorizationAcknowledged;
		private boolean subscriptionAuthorizationCallbackCompletionDeferred;
		private boolean catalogProjectionCallbackCompletionDeferred;
		private long nextKeepAliveNanos;
		private long streamOpenedAtNanos;
		private long subscriptionOpenedAtNanos;
		private boolean applicationOwned;
		private boolean catalogPolicyEvaluationOwned;
		private boolean catalogPolicyDeadlineResponseOwned;
		private boolean subscriptionOwned;
		private boolean streamObservationOpened;
		private boolean streamObservationClosed;
		private boolean subscriptionObservationOpened;
		private boolean subscriptionObservationClosed;
		private boolean streamTerminalResponseOwned;
		private boolean streamTerminalDeadlineResponseOwned;
		private boolean streamAbortOwned;
		private boolean canceled;
		private boolean terminal;
		private boolean requestObservationReserved;
		private boolean requestRejectionRecorded;
		private boolean lifecycleAdmissionReleased;
		private boolean protocolLifecycleWorkReleased;
		private boolean applicationLifecycleWorkOwned;
		private int lifecycleWorkOwners;
		private boolean lifecycleTransportStarted;
		private boolean lifecycleTransportTerminated;
		private @Nullable LegacyGetControl legacyGet;
		private int legacyHttpPolicyWork;
		private boolean legacyHttpControl;
		private @Nullable McpApplicationExecution.BoundedPolicyCancellation legacyHttpCancellation;
		private McpApplicationExecutionObserver.@Nullable HttpRequestObservation legacyHttpObservation;
		private long legacyHttpStartedNanos;
		private @Nullable MicrohttpResponse legacyHttpResponse;
		private boolean legacyHttpObservationFinished;
		private boolean legacySessionSelected;
		private boolean legacyAdmissionRejected;
		private @Nullable McpLegacySessionStore.Call legacyCall;
		private @Nullable McpLegacySessionStore.Initialization legacyInitialization;
		private McpLegacySessionStore.@Nullable Snapshot legacySnapshot;
		private @Nullable McpJsonRpcId legacyRequestId;
		private McpLegacySessionStore.@Nullable Cause legacySessionTerminationCause;
		private boolean legacySessionStreamTerminationObserved;
		private long legacySessionTransportCompletionDeadlineNanos;
		private boolean legacyInitializationResponseOffered;
		private boolean legacyProtocolPhysicalStarted;
		private boolean legacyProtocolPhysicalFinished;
		private boolean legacyApplicationPhysicalOutstanding;
		private boolean legacyPhysicalComplete;

		private RequestControl(@NonNull MicrohttpRequest request,
				long deadlineNanos, @NonNull ThreadPoolExecutor processor,
				@NonNull McpApplicationExecution application,
				@Nullable Request publicRequest,
				@Nullable McpSimulationRuntime simulation,
				@Nullable Runnable lifecycleAdmission,
				@NonNull Consumer<@NonNull MicrohttpResponse> responseCallback) {
			this.request = requireNonNull(request);
			this.deadlineNanos = deadlineNanos;
			if (!(requireNonNull(processor)
					instanceof LifecycleRequestProcessor lifecycleProcessor))
				throw new IllegalArgumentException(
						"MCP request controls require a lifecycle request processor.");
			this.processor = lifecycleProcessor;
			this.application = requireNonNull(application);
			this.publicRequest = publicRequest;
			this.simulation = simulation;
			this.lifecycleAdmission = lifecycleAdmission;
			this.acceptLanguageValues = physicalHeaderValues(request,
					"Accept-Language");
			this.acceptLanguageVaryRequired = localizationVaryRequired(request);
			this.lock = new Object();
			this.streamObservationTransitionLock = new Object();
			this.catalogAccessCancellation =
					application.newBoundedPolicyCancellation();
			this.responseCallback = requireNonNull(responseCallback);
			this.publicRequestContext = Optional.empty();
			this.deadlineResponseHeaders = decorateResponseHeaders(List.of());
			this.taskNotificationProjectionQueue =
					new TaskNotificationProjectionQueue();
			this.catalogProjectionQueue = new McpCatalogProjectionQueue();
			this.taskProjectionSchedulerOwner = new Object();
			this.catalogProjectionSchedulerOwner = new Object();
			this.catalogOfferLock = new Object();
			this.authorizationSchedulerOwner = new Object();
			this.subscriptionAuthorizationApplicationContext = Optional.empty();
			this.subscriptionAuthorizationGeneration = 0L;
			this.catalogAuthorizationRevision = 0L;
			this.subscriptionAuthorizationReconciliationGeneration = -1L;
			this.subscriptionAuthorizationExpiryNanos = Long.MIN_VALUE;
			this.subscriptionAuthorizationRenewalNanos = 0L;
			this.subscriptionAuthorizationRenewalScheduled = false;
			this.lifecycleWorkOwners = lifecycleAdmission == null ? 0 : 1;
		}

		private void startLegacyHttpObservation(Request request) {
			synchronized (lock) { legacyHttpControl = true; legacyHttpStartedNanos = applicationClock.nanoTime(); }
			McpApplicationExecutionObserver.HttpRequestObservation observation;
			try { observation = applicationExecutionObserver.didStartHttpRequest(request); }
			catch (Throwable ignored) { observation = (status, headers, duration, throwables) -> {}; }
			synchronized (lock) { legacyHttpObservation = observation; }
		}
		private void noteLegacyHttpResponse(MicrohttpResponse response) {
			synchronized (lock) { if (legacyHttpControl) legacyHttpResponse = response; }
		}
		private void finishLegacyHttpObservation() {
			McpApplicationExecutionObserver.HttpRequestObservation observation;
			MicrohttpResponse response;
			long start;
			synchronized (lock) {
				if (legacyHttpObservationFinished || legacyHttpObservation == null) return;
				legacyHttpObservationFinished = true; observation = legacyHttpObservation;
				response = legacyHttpResponse; start = legacyHttpStartedNanos;
			}
			Map<String, List<String>> headers = new LinkedHashMap<>();
			if (response != null) for (Header header : response.headers())
				headers.computeIfAbsent(header.name(), ignored -> new ArrayList<>()).add(header.value());
			try { observation.didFinish(response == null ? 503 : response.status(), Map.copyOf(headers),
					Duration.ofNanos(Math.max(0L, applicationClock.nanoTime() - start)), List.of()); }
			catch (Throwable ignored) { }
		}
		private void reserveLegacyHttpPolicyWork() {
			synchronized (lock) { legacyHttpPolicyWork++; if (lifecycleAdmission != null) lifecycleWorkOwners++; }
		}
		private void legacyHttpPolicyPhysicallyFinished() {
			synchronized (lock) { legacyHttpPolicyWork--; if (lifecycleAdmission != null) lifecycleWorkOwners--; }
			releaseLegacyPhysicalIfComplete(); releaseTrackedLifecycleIfComplete();
		}

		private void releaseTrackedLifecycleIfComplete() {
			Runnable release;
			boolean remove;
			synchronized (lock) {
				if (lifecycleAdmissionReleased || lifecycleAdmission == null
						|| lifecycleWorkOwners != 0
						|| !lifecycleTransportTerminated)
					return;
				lifecycleAdmissionReleased = true;
				release = lifecycleAdmission;
				remove = true;
			}
			if (remove && legacyCall == null && legacyInitialization == null && legacyGet == null)
				requestControls.remove(request, this);
			requireNonNull(release).run();
		}

		private void protocolLifecycleWorkTerminated() {
			if (lifecycleAdmission == null)
				return;
			synchronized (lock) {
				if (protocolLifecycleWorkReleased)
					return;
				protocolLifecycleWorkReleased = true;
				lifecycleWorkOwners--;
			}
			releaseTrackedLifecycleIfComplete();
		}

		private void reserveApplicationLifecycleWorkWhileLocked() {
			legacyApplicationPhysicalOutstanding = true;
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required to reserve application lifecycle work.");
			if (lifecycleAdmission == null)
				return;
			if (applicationLifecycleWorkOwned)
				throw new IllegalStateException(
						"Application lifecycle work is already reserved.");
			applicationLifecycleWorkOwned = true;
			lifecycleWorkOwners++;
		}

		private void applicationLifecycleWorkTerminated() {
			if (lifecycleAdmission == null)
				return;
			synchronized (lock) {
				if (!applicationLifecycleWorkOwned)
					return;
				applicationLifecycleWorkOwned = false;
				lifecycleWorkOwners--;
			}
			releaseTrackedLifecycleIfComplete();
		}

		private void markLifecycleTransportStarted() {
			if (lifecycleAdmission == null)
				return;
			synchronized (lock) {
				lifecycleTransportStarted = true;
			}
		}

		private void finishTransportLifecycle() {
			finishLegacyHttpObservation();
			synchronized (lock) { lifecycleTransportTerminated = true; }
			releaseLegacyPhysicalIfComplete();
			if (lifecycleAdmission == null) {
				if (legacyCall == null && legacyInitialization == null && legacyGet == null)
					requestControls.remove(request, this);
				return;
			}
			synchronized (lock) {
				lifecycleTransportTerminated = true;
			}
			releaseTrackedLifecycleIfComplete();
		}

		@NonNull
		private List<@NonNull String> acceptLanguageValues() {
			return this.acceptLanguageValues;
		}

		@SuppressWarnings("ReferenceEquality")
		private void bindProtocolProfile(
				@NonNull McpProtocolProfile selectedProfile) {
			requireNonNull(selectedProfile);
			synchronized (lock) {
				if (this.protocolProfile != null
						&& this.protocolProfile != selectedProfile)
					throw new IllegalStateException(
							"An MCP request cannot change its selected protocol profile.");
				this.protocolProfile = selectedProfile;
			}
		}

		@NonNull
		private McpProtocolProfile protocolProfile() {
			synchronized (lock) {
				if (this.protocolProfile == null)
					throw new IllegalStateException(
							"No MCP protocol profile has been selected for this request.");
				return this.protocolProfile;
			}
		}

		@NonNull
		private List<@NonNull Header> decorateResponseHeaders(
				@NonNull List<@NonNull Header> headers) {
			return this.acceptLanguageVaryRequired
					? withAcceptLanguageVary(requireNonNull(headers))
					: requireNonNull(headers);
		}

		@NonNull
		private MicrohttpResponse decorateResponse(
				@NonNull MicrohttpResponse response) {
			MicrohttpResponse requiredResponse = normalizeLegacySessionRpcResponse(requireNonNull(response));
			McpLegacySessionStore.Initialization initialization;
			synchronized (lock) {
				initialization = legacyInitializationResponseOffered ? null : legacyInitialization;
			}
			if (initialization != null)
				initialization.deliveryFailed();
			return this.acceptLanguageVaryRequired
					? requiredResponse.withHeaders(withAcceptLanguageVary(
							requiredResponse.headers()))
					: requiredResponse;
		}

		private MicrohttpResponse normalizeLegacySessionRpcResponse(MicrohttpResponse response) {
			if (!legacySessionSelected || legacyAdmissionRejected
					|| !Set.of(400, 404, 405, 500).contains(response.status())
					|| response.bodyLength() == 0 || response.streaming())
				return response;
			return new MicrohttpResponse(200, "OK", response.headers(), response.body());
		}

		private boolean attachLegacyCall(McpLegacySessionStore.Call call) {
			synchronized (lock) {
				if (canceled || terminal) return false;
				legacyCall = call;
				legacySnapshot = call.snapshot();
				return true;
			}
		}

		private boolean attachLegacyInitialization(McpLegacySessionStore.Initialization initialization) {
			synchronized (lock) {
				if (canceled || terminal) return false;
				legacyInitialization = initialization;
				legacySnapshot = initialization.snapshot();
				return true;
			}
		}

		/** Keeps remembered execution features separate from the original request metadata. */
		private McpJsonRpcMessage.Request withLegacyExecutionMetadata(McpJsonRpcMessage.Request mappedRequest) {
			synchronized (lock) {
				if (legacySnapshot == null) return mappedRequest;
				McpRequestMetadata metadata = mappedRequest.params().metadata();
				return new McpJsonRpcMessage.Request(mappedRequest.id(), mappedRequest.method(),
						new McpRequestParameters(new McpRequestMetadata(metadata.protocolVersion(),
								legacySnapshot.clientCapabilities(), legacySnapshot.clientInformation(), metadata.deprecatedLogLevel(),
								metadata.progressToken(), metadata.extensionFields()), mappedRequest.params().fields()),
						mappedRequest.extensionFields());
			}
		}

		/** Suppresses an unreserved progress token without altering public message metadata. */
		private McpJsonRpcMessage.Request withReservedLegacyProgress(McpJsonRpcMessage.Request mappedRequest) {
			synchronized (lock) {
				if (legacyCall == null || legacyCall.progressAllowed()
						|| mappedRequest.params().metadata().progressToken().isEmpty())
					return mappedRequest;
				McpRequestMetadata metadata = mappedRequest.params().metadata();
				return new McpJsonRpcMessage.Request(mappedRequest.id(), mappedRequest.method(),
						new McpRequestParameters(new McpRequestMetadata(metadata.protocolVersion(),
								metadata.clientCapabilities(), metadata.clientInformation(), metadata.deprecatedLogLevel(),
								Optional.empty(), metadata.extensionFields()), mappedRequest.params().fields()),
						mappedRequest.extensionFields());
			}
		}

		private MicrohttpResponse withInitializationResponse(MicrohttpResponse response) {
			McpLegacySessionStore.Initialization initialization;
			synchronized (lock) {
				initialization = legacyInitialization;
				if (initialization == null) return response;
				legacyInitializationResponseOffered = true;
			}
			List<Header> headers = new ArrayList<>(response.headers());
			headers.add(new Header(MCP_SESSION_ID, initialization.sessionId()));
			return response.withHeaders(List.copyOf(headers));
		}

		private McpLegacySessionStore.Target legacySessionTarget(boolean initialize) {
			return new McpLegacySessionStore.Target() {
				@Override public boolean cancel(McpLegacySessionStore.Cause cause) {
					return !initialize && cancelLegacySessionRequest(cause);
				}
				@Override public void retire(McpLegacySessionStore.Cause cause) {
					cancelLegacySessionRequest(cause);
				}
			};
		}

		private void bindLegacyRequestId(McpJsonRpcId requestId) {
			synchronized (lock) { legacyRequestId = requireNonNull(requestId); }
		}

		/**
		 * The router's terminal reservation is authoritative even before its writer
		 * reaches this control. An unregistered exchange is fenced here and the
		 * handoff's finally block cancels any subsequently registered exchange.
		 */
		private boolean cancelLegacySessionRequest(McpLegacySessionStore.Cause cause) {
			requireNonNull(cause);
			if (cause == McpLegacySessionStore.Cause.SERVER_STOPPING)
				return cancelFromSimulation(StreamTerminationReason.SERVER_STOPPING);
			McpHttpServerRuntime.this.applicationExecutionObserver.beginRequestTransitionDeferral();
			try {
				return cancelLegacySessionRequestWhileDeferred(cause);
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}
		}

		private boolean cancelLegacySessionRequestWhileDeferred(McpLegacySessionStore.Cause cause) {
			StreamTerminationReason reason = cause == McpLegacySessionStore.Cause.SESSION_EXPIRED
					? StreamTerminationReason.RESPONSE_TIMEOUT : StreamTerminationReason.CLIENT_CANCELED;
			McpApplicationCancellationReservation cancellation;
			FutureTask<Void> task;
			McpRequestSseStream stream;
			Consumer<MicrohttpResponse> callback;
			McpJsonRpcId requestId;
			boolean committed;
			boolean detached;
			StreamObservationOpenTransition opened = null;
			RequestObservationResult observation;
			McpJsonRpcError error = cause == McpLegacySessionStore.Cause.CLIENT_CANCEL ? null
					: new McpJsonRpcError(McpJsonRpcError.INTERNAL_ERROR, "Internal error", Optional.empty());
			synchronized (lock) {
				if (terminal || canceled || streamTerminalResponseOwned || streamAbortOwned
						|| protocolProfile == null || !McpLegacyHttpWire.isLegacyRevision(protocolProfile.revision()))
					return false;
				cancellation = application.tryReserveCancellation(request, reason);
				if (cancellation.registered() && !cancellation.won())
					return false;
				requestId = legacyRequestId;
				stream = responseStream;
				committed = responseStreamCommitted;
				detached = legacyResponseDeliveryDetached;
				callback = !committed && !detached && responseCallback != null ? takeResponseCallback() : null;
				if (cause == McpLegacySessionStore.Cause.CLIENT_CANCEL && stream == null && callback != null) {
					stream = newResponseStream();
					responseStream = stream;
					opened = reserveStreamObservationOpenWhileLocked(false);
				}
				legacySessionTerminationCause = cause;
				legacySessionTransportCompletionDeadlineNanos = applicationClock.nanoTime()
						+ transportConfiguration.responseWriteIdleTimeout().toNanos();
				canceled = true;
				cancellationReason = reason;
				cancellationCause = null;
				applicationOwned = false;
				task = protocolTask;
				protocolTask = null;
				// A cancellation/error response still owns a usable transport even
				// when application cleanup runs synchronously before HTTP offer.
				if (callback != null || committed) lifecycleTransportStarted = true;
				if (stream != null && !detached) {
					streamTerminalResponseOwned = true;
					responseStreamCommitted = true;
				}
				observation = new RequestObservationResult(
						cause == McpLegacySessionStore.Cause.SESSION_EXPIRED
								? McpRequestOutcome.DEADLINE_EXCEEDED : McpRequestOutcome.CANCELED,
						cause == McpLegacySessionStore.Cause.CLIENT_CANCEL ? null : error, List.of());
				replacePlannedRequestObservation(observation);
				markTerminalWhileLocked();
				responseCallback = null;
				releaseIdentifiedRequestExchange();
			}
			if (opened != null) {
				synchronized (streamObservationTransitionLock) { recordStreamObservationOpenInOrder(opened); }
			}
			if (simulation != null) {
				if (cause == McpLegacySessionStore.Cause.CLIENT_CANCEL) simulation.reserveClientCancellation();
				else simulation.reserveLegacySessionReason(legacySessionExactReason(cause));
			}
			catalogAccessCancellation.cancel(reason);
			if (task != null) { task.cancel(true); processor.remove(task); }
			try {
			if (detached) {
				finishTransportLifecycle();
				finishPlannedRequestObservation(observation);
				return true;
			}
			if (stream != null) {
				boolean finished;
				try {
					if (cause == McpLegacySessionStore.Cause.CLIENT_CANCEL)
						finished = stream.completeWithoutMessage(!committed);
					else if (requestId != null) {
						McpJsonRpcMessage.ErrorResponse terminalError = new McpJsonRpcMessage.ErrorResponse(
								Optional.of(requestId), renderFrameworkError(protocolProfile(), McpProfileErrorKind.CONTROL,
										requireNonNull(error)), McpJsonObject.empty());
						McpRequestSseStream.encodeMessage(envelopeCodec, jsonCodec, protocolProfile(), terminalError);
						recordProducedProtocolError(requireNonNull(error).code(), publicRequestContext().orElse(null));
						finished = stream.completeMessage(terminalError);
					} else finished = stream.completeWithoutMessage(!committed);
					if (!finished) stream.fail(StreamTerminationReason.INTERNAL_ERROR, null);
					if (callback != null) {
						Throwable failure = deliverResponse(callback, stream.response(deadlineResponseHeaders));
						if (failure != null) stream.fail(StreamTerminationReason.WRITE_FAILED, failure);
					}
				} catch (Throwable failure) { stream.fail(StreamTerminationReason.INTERNAL_ERROR, failure); }
				return true;
			}
			if (callback != null) {
				MicrohttpResponse response = requestId == null ? emptyResponse(202, "Accepted", deadlineResponseHeaders)
						: profiledJsonRpcError(protocolProfile(), McpProfileErrorKind.CONTROL, 200, "OK",
								Optional.of(requestId), requireNonNull(error), deadlineResponseHeaders, publicRequestContext().orElse(null));
				MicrohttpResponse observed = withRequestObservationTermination(response, observation);
				Throwable failure = deliverResponse(callback, observed);
				if (failure != null) {
					finishTransportLifecycle();
					finishRequestObservation(McpRequestOutcome.WRITE_FAILED, null, List.of(failure));
				} else if (simulation != null) {
					finishDeliveredSimulationResponse(observation, null);
					finishTransportLifecycle();
				}
			} else {
				finishTransportLifecycle();
				finishPlannedRequestObservation(observation);
			}
			return true;
			} finally {
				// Offer the required terminal transport before releasing cancellation
				// effects, which may synchronously reenter application cleanup.
				cancellation.complete().run();
			}
		}

		private McpStreamTerminationReason legacySessionExactReason(McpLegacySessionStore.Cause cause) {
			return switch (cause) {
				case CLIENT_CANCEL -> McpStreamTerminationReason.REQUEST_CANCELED;
				case SESSION_EXPIRED -> McpStreamTerminationReason.SESSION_EXPIRED;
				case SESSION_CLOSED -> McpStreamTerminationReason.SESSION_CLOSED;
				case SERVER_STOPPING -> McpStreamTerminationReason.SERVER_STOPPING;
			};
		}

		/** A reserved session terminal still owns its body's eventual completion. */
		private boolean finishLegacySessionStreamIfOwned(StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason exactReason, @Nullable Throwable cause) {
			StreamTerminationReason observed;
			McpStreamTerminationReason exact;
			synchronized (lock) {
				if (legacySessionTerminationCause == null || responseStream == null) return false;
				if (legacySessionStreamTerminationObserved) return true;
				legacySessionStreamTerminationObserved = true;
				observed = reason == StreamTerminationReason.COMPLETED ? requireNonNull(cancellationReason) : reason;
				exact = reason == StreamTerminationReason.COMPLETED ? legacySessionExactReason(legacySessionTerminationCause)
						: exactReason;
				markTerminalWhileLocked();
			}
			markStreamClosed(observed, exact);
			finishTransportLifecycle();
			if (reason == StreamTerminationReason.COMPLETED) finishPlannedRequestObservation(requestObservationResult(observed, cause));
			else {
				RequestObservationResult result = requestObservationResult(reason, cause);
				finishRequestObservation(result.outcome(), result.error(), result.throwables());
			}
			return true;
		}

		private void releaseLegacyPhysicalIfComplete() {
			McpLegacySessionStore.Call call;
			McpLegacySessionStore.Initialization initialization;
			LegacyGetControl get;
			synchronized (lock) {
				if (legacyPhysicalComplete || !legacyProtocolPhysicalFinished
						|| legacyApplicationPhysicalOutstanding || legacyHttpPolicyWork != 0 || !lifecycleTransportTerminated
						|| catalogAccessCancellation.canceledPhysicalWorkOutstanding()) return;
				legacyPhysicalComplete = true;
				call = legacyCall;
				initialization = legacyInitialization;
				get = legacyGet; legacyGet = null;
				legacyCall = null;
				legacyInitialization = null;
				legacySnapshot = null;
			}
			if (call != null) call.physicalComplete();
			if (initialization != null) initialization.physicalComplete();
			if (get != null) get.physicalComplete();
			if (lifecycleAdmission == null || lifecycleAdmissionReleased)
				requestControls.remove(request, this);
		}

		private void legacyApplicationPhysicalFinished() {
			synchronized (lock) { legacyApplicationPhysicalOutstanding = false; }
			releaseLegacyPhysicalIfComplete();
		}

		private void legacyProtocolPhysicalFinished() {
			synchronized (lock) { legacyProtocolPhysicalFinished = true; }
			releaseLegacyPhysicalIfComplete();
		}

		private long deadlineNanos() {
			synchronized (lock) {
				return deadlineNanos;
			}
		}

		@NonNull
		private SubscriptionAuthorizationResult authorizeSubscriptionInitially(
				@NonNull AcceptedSubscriptionFilter filter) {
			requireNonNull(filter);
			if (subscriptionRuntimeConfiguration.authorizer().isEmpty())
				return SubscriptionAuthorizationResult.allowed();

			while (true) {
				SubscriptionAuthorizationCheck check;
				try {
					synchronized (lock) {
						if (terminal || canceled || subscriptionCapReservation == null)
							return SubscriptionAuthorizationResult.terminated();
						if (subscriptionAuthorizationCheck != null)
							throw new IllegalStateException(
									"An initial MCP subscription authorization check is already outstanding.");
						check = reserveSubscriptionAuthorizationCheckWhileLocked(
								SubscriptionAuthorizationCheckKind.INITIAL, filter);
					}
					if (check == null)
						return recordInitialAuthorizationReservationFailure(
								filter, SubscriptionAuthorizationResult.timedOut(false));
				} catch (Throwable throwable) {
					SubscriptionAuthorizationResult failed =
							SubscriptionAuthorizationResult.failed(throwable);
					recordSubscriptionAuthorizationResult(
							subscriptionEndpointPath(),
							SubscriptionAuthorizationCheckKind.INITIAL, failed);
					return failed;
				}

				SubscriptionAuthorizationExecution execution =
						executeSubscriptionAuthorizationCheck(check);
				if (execution.disposition()
						!= SubscriptionAuthorizationDisposition.TIMED_OUT
						&& check.cancellation()
								.canceledPhysicalWorkOutstanding()) {
					boolean retryMayRemainPossible;
					synchronized (lock) {
						retryMayRemainPossible = sameInstance(subscriptionAuthorizationCheck, check)
								&& !terminal && !canceled
								&& subscriptionCapReservation != null;
					}
					if (retryMayRemainPossible
							&& !check.physicalExit().awaitUntil(
									applicationClock, check.deadlineNanos()))
						execution = SubscriptionAuthorizationExecution.timedOut(
								new McpApplicationPolicyDeadlineException(false), false);
				}
				SubscriptionAuthorizationResult result =
						finishSubscriptionAuthorizationCheck(check, execution, false);
				if (result.disposition()
						!= SubscriptionAuthorizationDisposition.STALE_RESULT)
					return result;
			}
		}

		@NonNull
		private SubscriptionAuthorizationResult
				recordInitialAuthorizationReservationFailure(
						@NonNull AcceptedSubscriptionFilter filter,
						@NonNull SubscriptionAuthorizationResult result) {
			requireNonNull(filter);
			recordSubscriptionAuthorizationResult(subscriptionEndpointPath(),
					SubscriptionAuthorizationCheckKind.INITIAL,
					requireNonNull(result));
			return result;
		}

		@NonNull
		private String subscriptionEndpointPath() {
			synchronized (lock) {
				SubscriptionRegistration registration = subscriptionRegistration != null
						? subscriptionRegistration : subscriptionCapReservation;
				return registration == null ? "<unknown>"
						: registration.endpointPath();
			}
		}

		private @Nullable SubscriptionAuthorizationCheck
				reserveSubscriptionAuthorizationCheckWhileLocked(
						@NonNull SubscriptionAuthorizationCheckKind kind,
						@NonNull AcceptedSubscriptionFilter filter) {
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required to reserve subscription authorization.");
			if (subscriptionAuthorizationCheck != null)
				return null;
			SubscriptionRegistration registration = subscriptionRegistration != null
					? subscriptionRegistration : subscriptionCapReservation;
			if (registration == null)
				return null;
			McpRequestContext initialContext = publicRequestContext.orElse(null);
			if (initialContext == null)
				throw new IllegalStateException(
						"MCP subscription authorization requires an admitted request context.");

			long nowNanos = applicationClock.nanoTime();
			Instant now = applicationClock.instant();
			long lifetimeDeadlineNanos = subscriptionRegistration == null
					? registration.openedAtNanos()
							+ subscriptionRuntimeConfiguration
									.maximumSubscriptionDuration().toNanos()
					: deadlineNanos;
			long checkDeadlineNanos = minimumDeadline(nowNanos,
					nowNanos + subscriptionRuntimeConfiguration
							.authorizationTimeout().toNanos(),
					lifetimeDeadlineNanos);
			if (kind == SubscriptionAuthorizationCheckKind.INITIAL)
				checkDeadlineNanos = minimumDeadline(nowNanos, checkDeadlineNanos,
						deadlineNanos);
			else if (kind == SubscriptionAuthorizationCheckKind.RENEWAL)
				checkDeadlineNanos = minimumDeadline(nowNanos, checkDeadlineNanos,
						subscriptionAuthorizationExpiryNanos);
			long remainingNanos = checkDeadlineNanos - nowNanos;
			if (remainingNanos <= 0L)
				return null;

			Optional<Object> applicationContext = subscriptionAuthorizationEstablished
					? subscriptionAuthorizationApplicationContext
					: initialContext.getAdmissionIdentity().getApplicationContext();
			Set<URI> resourceUris = Collections.unmodifiableSet(
					new LinkedHashSet<>(filter.resourceSubscriptions().keySet()));
			Set<String> taskIds = Collections.unmodifiableSet(new LinkedHashSet<>(
					subscriptionAuthorizationAcknowledged
							? filter.acceptedTaskIds() : filter.requestedTaskIds()));
			Instant deadline = now.plusNanos(remainingNanos);
			SubscriptionAuthorizationContextSnapshot context =
					new SubscriptionAuthorizationContextSnapshot(initialContext,
							applicationContext,
							Optional.ofNullable(subscriptionAuthorizationValidUntil),
							deadline, filter.toolsListChanged(),
							filter.promptsListChanged(),
							filter.resourcesListChanged(), resourceUris, taskIds);
			McpApplicationExecution.BoundedPolicyCancellation cancellation =
					application.newBoundedPolicyCancellation();
			SubscriptionAuthorizationCheck check =
					new SubscriptionAuthorizationCheck(kind,
							subscriptionAuthorizationGeneration,
							currentSubscriptionReconciliationGeneration(),
							registration.endpointPath(), registration.subscriptionId(),
							filter, context, cancellation,
							new BoundedPolicyPhysicalExit(), checkDeadlineNanos,
							lifetimeDeadlineNanos);
			subscriptionAuthorizationCallbackCompletionDeferred = false;
			subscriptionAuthorizationCheck = check;
			return check;
		}

		@NonNull
		private SubscriptionAuthorizationExecution
				executeSubscriptionAuthorizationCheck(
						@NonNull SubscriptionAuthorizationCheck check) {
			McpSubscriptionAuthorizer authorizer = subscriptionRuntimeConfiguration
					.authorizer().orElseThrow();
			McpInvocationFeatures features = McpInvocationFeatures.fromFeatures(
					Map.of(CancelationToken.class, check.cancellation()));
			try {
				SubscriptionAuthorizationCallbackResult callbackResult =
						application.invokeBoundedPolicy(
								() -> {
									McpSubscriptionAuthorization authorization =
											authorizer.authorize(check.context(), features);
									Set<String> acceptedTaskIds = authorization
											instanceof McpSubscriptionAuthorization.Allowed allowed
											? authorizeSubscriptionTasks(check, allowed)
											: Set.of();
									return new SubscriptionAuthorizationCallbackResult(
											authorization, acceptedTaskIds);
								},
								check.deadlineNanos(),
								check.cancellation(),
								() -> subscriptionAuthorizationPhysicallyExited(check));
				McpSubscriptionAuthorization authorization =
						callbackResult.authorization();
				if (authorization instanceof McpSubscriptionAuthorization.Allowed allowed)
					return SubscriptionAuthorizationExecution.allowed(allowed,
							callbackResult.acceptedTaskIds());
				if (authorization instanceof McpSubscriptionAuthorization.Denied)
					return SubscriptionAuthorizationExecution.denied();
				return SubscriptionAuthorizationExecution.failed(
						new IllegalStateException(
								"Unsupported MCP subscription authorization result."));
			} catch (McpApplicationPolicyCapacityException exception) {
				return SubscriptionAuthorizationExecution.capacityRejected(exception);
			} catch (McpApplicationPolicyDeadlineException exception) {
				return SubscriptionAuthorizationExecution.timedOut(
						exception, exception.queued());
			} catch (Throwable throwable) {
				if (throwable instanceof InterruptedException) {
					boolean cancellationAlreadyRequested =
							check.cancellation().isCancellationRequested();
					check.cancellation().cancel(
							StreamTerminationReason.RESPONSE_TIMEOUT);
					if (!cancellationAlreadyRequested)
						Thread.currentThread().interrupt();
				}
				return SubscriptionAuthorizationExecution.failed(throwable);
			} finally {
				if (check.cancellation().isActive())
					try {
						check.cancellation().complete();
					} catch (IllegalStateException ignored) {
						// A concurrent fence fixed and released cancellation first.
					}
			}
		}

		@NonNull
		private Set<@NonNull String> authorizeSubscriptionTasks(
				@NonNull SubscriptionAuthorizationCheck check,
				McpSubscriptionAuthorization.@NonNull Allowed allowed)
				throws Exception {
			AcceptedSubscriptionFilter filter = check.filter();
			if (!filter.taskIdsRequested())
				return Set.of();
			EndpointRuntime endpointRuntime = endpointsByPath.get(check.endpointPath());
			if (endpointRuntime == null
					|| endpointRuntime.binding().endpoint().subscriptionConfig().isEmpty()
					|| !endpointRuntime.binding().endpoint().subscriptionConfig()
							.orElseThrow().taskNotifications())
				return Set.of();
			TaskManagerAdapter taskManager = endpointRuntime.binding()
					.taskManagerAdapter().orElseThrow(() ->
							new IllegalStateException(
									"MCP task notifications require a task manager."));
			McpRequestContext requestContext = new DerivedSubscriptionRequestContext(
					check.context().getInitialRequestContext(),
					"subscriptions/listen", allowed.getApplicationContext());
			Set<String> accepted = new LinkedHashSet<>();
			for (String taskId : check.context().getTaskIds()) {
				if (check.cancellation().isCancellationRequested()
						|| applicationClock.nanoTime() - check.deadlineNanos() >= 0L)
					throw new InterruptedException(
							"MCP subscription task authorization was canceled.");
				try {
					Optional<TaskSnapshot> taskSnapshot = requireNonNull(
							taskManager.findTaskForSubscriptionAuthorization(
									requestContext, taskId),
							"The MCP task manager adapter returned null.");
					if (taskSnapshot.isEmpty())
						continue;
					TaskSnapshot snapshot = taskSnapshot.orElseThrow();
					if (!taskId.equals(snapshot.task().getTaskId()))
						throw new IllegalStateException(
								"The MCP task manager returned a mismatched task ID.");
					McpServerRuntimeBridge.requireTaskInputCapabilities(snapshot,
							filter.clientCapabilities());
					accepted.add(taskId);
				} catch (Throwable throwable) {
					if (throwable instanceof InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw interrupted;
					}
					// One unavailable or malformed task does not destroy unrelated
					// advisory subscriptions in the same stream.
				}
			}
			return Collections.unmodifiableSet(accepted);
		}

		@NonNull
		private SubscriptionAuthorizationResult
				finishSubscriptionAuthorizationCheck(
						@NonNull SubscriptionAuthorizationCheck check,
						@NonNull SubscriptionAuthorizationExecution execution,
						boolean asynchronous) {
			requireNonNull(check);
			requireNonNull(execution);
			EffectiveSubscriptionAuthorizationGrant grant = null;
			SubscriptionAuthorizationResult executionResult;
			if (execution.disposition()
					== SubscriptionAuthorizationDisposition.ALLOWED) {
				grant = effectiveSubscriptionAuthorizationGrant(check,
						execution.allowed());
				executionResult = grant == null
						? SubscriptionAuthorizationResult.failed(
								invalidSubscriptionAuthorizationGrant())
						: SubscriptionAuthorizationResult.allowed(
								execution.acceptedTaskIds());
			} else {
				executionResult = new SubscriptionAuthorizationResult(
						execution.disposition(), Set.of(), execution.failure(),
						execution.queuedTimeout());
			}

			boolean stale = false;
			boolean scheduleFresh = false;
			boolean signalTotalLifetime = false;
			boolean submitCatalogRefresh = false;
			boolean submitTaskRefresh = false;
			boolean completionDeferred = false;
			CatalogProjectionCheck catalogProjectionToCancel = null;
			SubscriptionAuthorizationFailure authorizationFailure = null;
			SubscriptionAuthorizationResult result = executionResult;
			synchronized (catalogOfferLock) {
				synchronized (lock) {
					if (!sameInstance(subscriptionAuthorizationCheck, check))
						return SubscriptionAuthorizationResult.stale();
					boolean generationStale = check.generation()
							!= subscriptionAuthorizationGeneration
							|| check.reconciliationGeneration()
									!= currentSubscriptionReconciliationGeneration();
					boolean callbackMayRemainActive = execution.disposition()
							== SubscriptionAuthorizationDisposition.TIMED_OUT
							&& !execution.queuedTimeout();
					long nowNanos = applicationClock.nanoTime();
					if (grant != null && nowNanos - grant.expiryNanos() >= 0L) {
						// Acceptance, not callback return, fixes the effective lease. A
						// short result that expires while waiting to reacquire this owner
						// lock is therefore rejected rather than briefly installed.
						grant = null;
						result = SubscriptionAuthorizationResult.failed(
								invalidSubscriptionAuthorizationGrant());
					}
					boolean establishedAuthorizationExpired =
							subscriptionAuthorizationEstablished
									&& nowNanos - subscriptionAuthorizationExpiryNanos >= 0L;
					boolean totalLifetimeWins = establishedAuthorizationExpired
							&& subscriptionOwned && nowNanos - deadlineNanos >= 0L
							&& deadlineNanos - nowNanos
									<= subscriptionAuthorizationExpiryNanos - nowNanos;
					if ((terminal || canceled) && !callbackMayRemainActive) {
						stale = true;
					} else if (totalLifetimeWins) {
						// A result completing on or after the shared lease/lifetime
						// boundary cannot revive the stream or replace its ordinary
						// maximum-duration terminal.
						subscriptionAuthorizationFenced = true;
						stale = true;
						signalTotalLifetime = true;
					} else if (establishedAuthorizationExpired) {
						stale = true;
						authorizationFailure =
								reserveSubscriptionAuthorizationFailureWhileLocked(
										McpStreamTerminationReason
												.SUBSCRIPTION_AUTHORIZATION_EXPIRED,
										null);
					} else if (generationStale && asynchronous
							&& check.cancellation()
									.canceledPhysicalWorkOutstanding()
							&& execution.disposition()
									!= SubscriptionAuthorizationDisposition
											.CAPACITY_REJECTED) {
						stale = true;
						completionDeferred = true;
						subscriptionAuthorizationCallbackCompletionDeferred = true;
					} else if (generationStale && !callbackMayRemainActive
							&& execution.disposition()
									!= SubscriptionAuthorizationDisposition
											.CAPACITY_REJECTED) {
						stale = true;
						scheduleFresh = asynchronous && subscriptionOwned
								&& subscriptionAuthorizationReconciliationPending;
					} else if (result.disposition()
							== SubscriptionAuthorizationDisposition.ALLOWED) {
						EffectiveSubscriptionAuthorizationGrant accepted =
								requireNonNull(grant);
						boolean applicationContextChanged = !sameInstance(
								subscriptionAuthorizationApplicationContext.orElse(null),
								accepted.applicationContext().orElse(null));
						Instant previousValidUntil =
								subscriptionAuthorizationValidUntil;
						long previousExpiryNanos =
								subscriptionAuthorizationExpiryNanos;
						boolean expiryExtended = !subscriptionAuthorizationEstablished
								|| previousValidUntil == null
								|| (accepted.validUntil().isAfter(previousValidUntil)
										&& accepted.expiryNanos() - nowNanos
												> previousExpiryNanos - nowNanos);
						long installedExpiryNanos = accepted.expiryNanos();
						if (subscriptionAuthorizationEstablished
								&& previousValidUntil != null
								&& !accepted.validUntil().isAfter(previousValidUntil))
							installedExpiryNanos = minimumDeadline(nowNanos,
									previousExpiryNanos, accepted.expiryNanos());
						subscriptionAuthorizationEstablished = true;
						subscriptionAuthorizationFenced = false;
						subscriptionAuthorizationReconciliationPending = false;
						subscriptionAuthorizationApplicationContext =
								accepted.applicationContext();
						if (catalogAuthorizationRevision == Long.MAX_VALUE)
							throw new IllegalStateException(
									"The MCP catalog authorization revision cannot overflow.");
						catalogAuthorizationRevision++;
						subscriptionAuthorizationReconciliationGeneration =
								check.reconciliationGeneration();
						subscriptionAuthorizationValidUntil = accepted.validUntil();
						subscriptionAuthorizationExpiryNanos = installedExpiryNanos;
						subscriptionAuthorizationRenewalNanos = expiryExtended
								? accepted.renewalNanos() : 0L;
						subscriptionAuthorizationRenewalScheduled = expiryExtended;
						if (asynchronous && subscriptionRegistration != null) {
							SubscriptionRegistration current =
									subscriptionRegistration;
							if (check.kind()
									== SubscriptionAuthorizationCheckKind.RECONCILIATION
									|| applicationContextChanged)
								preRenderedSubscriptionTerminal = null;
							subscriptionRegistration = new SubscriptionRegistration(
									current.endpointPath(), current.endpoint(),
									current.authorizationPartition(),
									current.subscriptionId(),
									current.filter().withAcceptedTaskIds(
											new ArrayList<>(result.acceptedTaskIds())),
									current.protocolProfile(), current.openedAtNanos());
							if (subscriptionOwned && !streamAbortOwned
									&& !streamTerminalResponseOwned)
								for (String taskId : result.acceptedTaskIds())
									if (taskNotificationProjectionQueue.request(taskId))
										submitTaskRefresh = true;
							catalogProjectionToCancel = catalogProjectionCheck;
							if (subscriptionOwned && !streamAbortOwned
									&& !streamTerminalResponseOwned
									&& (current.filter().toolsListChanged()
											|| current.filter().promptsListChanged())) {
								catalogProjectionQueue.markAuthorizationChanged();
								submitCatalogRefresh = catalogProjectionQueue.retryAll(
										catalogProjectionDeadlineWhileLocked())
										== McpCatalogProjectionQueue.RequestResult.SUBMIT;
							}
						}
					} else {
						if (asynchronous && subscriptionOwned)
							authorizationFailure =
									reserveSubscriptionAuthorizationFailureWhileLocked(
											terminationReason(check.kind(),
													result.disposition()),
											result.failure());
						else
							subscriptionAuthorizationFenced = true;
					}
					if (!completionDeferred) {
						subscriptionAuthorizationCallbackCompletionDeferred = false;
						subscriptionAuthorizationCheck = null;
					}
				}
			}

			if (stale) {
				result = SubscriptionAuthorizationResult.stale();
				if (authorizationFailure != null)
					finishSubscriptionAuthorizationFailure(authorizationFailure);
				if (signalTotalLifetime)
					application.signalDeadlineTimer();
				recordSubscriptionMaintenance(check.endpointPath(),
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome
								.STALE_RESULT_DISCARDED);
				if (scheduleFresh)
					scheduleSubscriptionAuthorizationCheck(
							SubscriptionAuthorizationCheckKind.RECONCILIATION);
				return result;
			}

			recordSubscriptionAuthorizationResult(check.endpointPath(), check.kind(),
					result);
			if (catalogProjectionToCancel != null)
				catalogProjectionToCancel.cancellation().cancel(
						StreamTerminationReason.APPLICATION_CANCELED);
			if (submitCatalogRefresh)
				submitCatalogProjection();
			if (submitTaskRefresh)
				submitTaskNotificationProjection();
			if (check.kind() == SubscriptionAuthorizationCheckKind.RECONCILIATION
					&& result.disposition()
							== SubscriptionAuthorizationDisposition.ALLOWED)
				recordSubscriptionMaintenance(check.endpointPath(),
						McpMetricsEvent.SubscriptionMaintenance.Work.RECONCILIATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
			if (authorizationFailure != null)
				finishSubscriptionAuthorizationFailure(authorizationFailure);
			return result;
		}

		private void recordSubscriptionAuthorizationResult(
				@NonNull String endpointPath,
				@NonNull SubscriptionAuthorizationCheckKind kind,
				@NonNull SubscriptionAuthorizationResult result) {
			McpMetricsEvent.SubscriptionMaintenance.Outcome outcome = switch (
					requireNonNull(result).disposition()) {
				case ALLOWED -> McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED;
				case DENIED -> McpMetricsEvent.SubscriptionMaintenance.Outcome.DENIED;
				case TIMED_OUT -> McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT;
				case CAPACITY_REJECTED -> McpMetricsEvent.SubscriptionMaintenance.Outcome
						.CAPACITY_REJECTED;
				case STALE_RESULT -> McpMetricsEvent.SubscriptionMaintenance.Outcome
						.STALE_RESULT_DISCARDED;
				case FAILED -> McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED;
				case TERMINATED -> null;
			};
			if (outcome != null)
				recordSubscriptionMaintenance(endpointPath,
						McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
						outcome);
			if (kind == SubscriptionAuthorizationCheckKind.RECONCILIATION
					&& result.disposition() != SubscriptionAuthorizationDisposition.ALLOWED
					&& result.disposition()
							!= SubscriptionAuthorizationDisposition.STALE_RESULT
					&& result.disposition()
							!= SubscriptionAuthorizationDisposition.TERMINATED)
					recordSubscriptionMaintenance(endpointPath,
							McpMetricsEvent.SubscriptionMaintenance.Work.RECONCILIATION,
							McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED);
		}

		private void subscriptionAuthorizationPhysicallyExited(
				@NonNull SubscriptionAuthorizationCheck check) {
			SubscriptionAuthorizationCheck requiredCheck = requireNonNull(check);
			requiredCheck.physicalExit().markExited();
			boolean scheduleFresh = false;
			boolean signalTotalLifetime = false;
			SubscriptionAuthorizationFailure authorizationFailure = null;
			synchronized (lock) {
				if (!sameInstance(subscriptionAuthorizationCheck, requiredCheck)
						|| !subscriptionAuthorizationCallbackCompletionDeferred)
					return;
				subscriptionAuthorizationCallbackCompletionDeferred = false;
				subscriptionAuthorizationCheck = null;
				if (!subscriptionOwned || terminal || canceled
						|| !subscriptionAuthorizationReconciliationPending)
					return;
				long nowNanos = applicationClock.nanoTime();
				if (subscriptionAuthorizationEstablished
						&& nowNanos - subscriptionAuthorizationExpiryNanos >= 0L) {
					if (nowNanos - deadlineNanos >= 0L
							&& deadlineNanos - nowNanos
									<= subscriptionAuthorizationExpiryNanos - nowNanos) {
						subscriptionAuthorizationFenced = true;
						signalTotalLifetime = true;
					} else {
						authorizationFailure =
								reserveSubscriptionAuthorizationFailureWhileLocked(
										McpStreamTerminationReason
												.SUBSCRIPTION_AUTHORIZATION_EXPIRED,
										null);
					}
				} else {
					scheduleFresh = true;
				}
			}
			if (authorizationFailure != null)
				finishSubscriptionAuthorizationFailure(authorizationFailure);
			else if (signalTotalLifetime)
				application.signalDeadlineTimer();
			else if (scheduleFresh)
				scheduleSubscriptionAuthorizationCheck(
						SubscriptionAuthorizationCheckKind.RECONCILIATION);
		}

		private @Nullable EffectiveSubscriptionAuthorizationGrant
				effectiveSubscriptionAuthorizationGrant(
						@NonNull SubscriptionAuthorizationCheck check,
						McpSubscriptionAuthorization.@Nullable Allowed allowed) {
			if (allowed == null)
				return null;
			long nowNanos = applicationClock.nanoTime();
			Instant now = applicationClock.instant();
			Instant maximumValidUntil;
			try {
				maximumValidUntil = now.plus(
						subscriptionRuntimeConfiguration.maximumAuthorizationDuration());
			} catch (RuntimeException exception) {
				return null;
			}
			Instant validUntil = allowed.getValidUntil().isBefore(maximumValidUntil)
					? allowed.getValidUntil() : maximumValidUntil;
			long wallDurationNanos;
			try {
				wallDurationNanos = Duration.between(now, validUntil).toNanos();
			} catch (ArithmeticException exception) {
				return null;
			}
			long lifetimeRemainingNanos = check.lifetimeDeadlineNanos() - nowNanos;
			long durationNanos = Math.min(wallDurationNanos,
					lifetimeRemainingNanos);
			if (durationNanos <= 0L)
				return null;
			long expiryNanos = nowNanos + durationNanos;
			Instant effectiveValidUntil = now.plusNanos(durationNanos);
			long halfDuration = Math.max(1L, durationNanos / 2L);
			long maximumStagger = Math.min(MAXIMUM_SUBSCRIPTION_RENEWAL_STAGGER_NANOS,
					Math.max(0L, durationNanos / 20L));
			long stagger = maximumStagger == 0L ? 0L
					: Integer.toUnsignedLong(check.subscriptionId().hashCode())
							% (maximumStagger + 1L);
			long renewalOffsetNanos = Math.min(durationNanos,
					halfDuration + stagger);
			long renewalNanos = renewalOffsetNanos >= durationNanos
					? expiryNanos : nowNanos + renewalOffsetNanos;
			return new EffectiveSubscriptionAuthorizationGrant(effectiveValidUntil,
					expiryNanos, renewalNanos, allowed.getApplicationContext());
		}

		@NonNull
		private IllegalStateException invalidSubscriptionAuthorizationGrant() {
			return new IllegalStateException(
					"The MCP subscription authorizer returned an invalid or expired authorization grant.");
		}

		private void scheduleSubscriptionAuthorizationCheck(
				@NonNull SubscriptionAuthorizationCheckKind kind) {
			SubscriptionAuthorizationCheck check;
			SubscriptionAuthorizationCheckKind effectiveKind = requireNonNull(kind);
			SubscriptionAuthorizationResult immediateFailure = null;
			SubscriptionAuthorizationFailure reservedFailure = null;
			synchronized (lock) {
				if (!subscriptionOwned || terminal || canceled
						|| subscriptionRegistration == null
						|| subscriptionRuntimeConfiguration.authorizer().isEmpty()
						|| applicationClock.nanoTime() - deadlineNanos >= 0L)
					return;
				if (subscriptionAuthorizationCheck != null) {
					if (kind == SubscriptionAuthorizationCheckKind.RECONCILIATION)
						subscriptionAuthorizationReconciliationPending = true;
					return;
				}
				if (subscriptionAuthorizationReconciliationPending)
					effectiveKind = SubscriptionAuthorizationCheckKind.RECONCILIATION;
				check = reserveSubscriptionAuthorizationCheckWhileLocked(effectiveKind,
						subscriptionRegistration.filter());
				if (check == null) {
					immediateFailure = SubscriptionAuthorizationResult.timedOut(false);
					reservedFailure = reserveSubscriptionAuthorizationFailureWhileLocked(
							terminationReason(effectiveKind,
									immediateFailure.disposition()), null);
				}
			}
			if (immediateFailure != null) {
				recordSubscriptionAuthorizationResult(subscriptionEndpointPath(),
						effectiveKind,
						immediateFailure);
				finishSubscriptionAuthorizationFailure(
						requireNonNull(reservedFailure));
				return;
			}
			SubscriptionAuthorizationCheck reserved = requireNonNull(check);
			processor.executeTaskNotificationProjection(
					new TaskNotificationProjectionJob(authorizationSchedulerOwner,
							() -> runSubscriptionAuthorizationCheck(reserved),
							() -> rejectSubscriptionAuthorizationCheck(reserved)));
		}

		private void runSubscriptionAuthorizationCheck(
				@NonNull SubscriptionAuthorizationCheck check) {
			SubscriptionAuthorizationExecution execution =
					executeSubscriptionAuthorizationCheck(requireNonNull(check));
			finishSubscriptionAuthorizationCheck(check, execution, true);
		}

		private void rejectSubscriptionAuthorizationCheck(
				@NonNull SubscriptionAuthorizationCheck check) {
			boolean owned;
			SubscriptionAuthorizationFailure failure = null;
			synchronized (lock) {
				owned = sameInstance(subscriptionAuthorizationCheck, requireNonNull(check));
				if (owned) {
					subscriptionAuthorizationCheck = null;
					SubscriptionAuthorizationResult rejected =
							SubscriptionAuthorizationResult.capacityRejected(null);
					failure = reserveSubscriptionAuthorizationFailureWhileLocked(
							terminationReason(check.kind(), rejected.disposition()), null);
				}
			}
			if (!owned)
				return;
			SubscriptionAuthorizationResult rejected =
					SubscriptionAuthorizationResult.capacityRejected(null);
			recordSubscriptionAuthorizationResult(check.endpointPath(), check.kind(),
					rejected);
			finishSubscriptionAuthorizationFailure(requireNonNull(failure));
		}

		private void reconcileSubscriptionAuthorization() {
			SubscriptionAuthorizationCheck checkToCancel;
			CatalogProjectionCheck catalogCheckToCancel;
			boolean schedule = false;
			boolean coalesced = false;
			String endpointPath;
			synchronized (catalogOfferLock) {
				synchronized (lock) {
					if (terminal || canceled
							|| (subscriptionRegistration == null
									&& subscriptionCapReservation == null)
							|| subscriptionRuntimeConfiguration.authorizer().isEmpty())
						return;
					SubscriptionRegistration registration =
							subscriptionRegistration != null
									? subscriptionRegistration
									: subscriptionCapReservation;
					endpointPath = requireNonNull(registration).endpointPath();
					subscriptionAuthorizationGeneration++;
					if (catalogAuthorizationRevision == Long.MAX_VALUE)
						throw new IllegalStateException(
								"The MCP catalog authorization revision cannot overflow.");
					catalogAuthorizationRevision++;
					subscriptionAuthorizationFenced = true;
					subscriptionAuthorizationReconciliationPending = true;
					checkToCancel = subscriptionAuthorizationCheck;
					catalogCheckToCancel = catalogProjectionCheck;
					if (checkToCancel != null) {
						coalesced = true;
					} else if (subscriptionOwned) {
						schedule = true;
					}
				}
			}
			if (checkToCancel != null)
				checkToCancel.cancellation().cancel(
						StreamTerminationReason.APPLICATION_CANCELED);
			if (catalogCheckToCancel != null)
				catalogCheckToCancel.cancellation().cancel(
						StreamTerminationReason.APPLICATION_CANCELED);
			if (coalesced)
				recordSubscriptionMaintenance(endpointPath,
						McpMetricsEvent.SubscriptionMaintenance.Work.RECONCILIATION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED);
			if (schedule) {
				subscriptionAuthorizationSchedulingTestHooks
						.beforeReconciliationScheduling();
				scheduleSubscriptionAuthorizationCheck(
						SubscriptionAuthorizationCheckKind.RECONCILIATION);
			}
		}

		private void maintainSubscriptionAuthorization(long nowNanos) {
			SubscriptionAuthorizationFailure failure = null;
			boolean renew = false;
			synchronized (lock) {
				if (!subscriptionOwned || terminal || canceled
						|| subscriptionRuntimeConfiguration.authorizer().isEmpty()
						|| !subscriptionAuthorizationEstablished)
					return;
				if (nowNanos - subscriptionAuthorizationExpiryNanos >= 0L) {
					if (nowNanos - deadlineNanos >= 0L
							&& deadlineNanos - nowNanos
									<= subscriptionAuthorizationExpiryNanos - nowNanos) {
						// The ordinary maximum-duration terminal owns a coincident
						// boundary so its pre-rendered terminal is not replaced by an
						// authorization failure.
						subscriptionAuthorizationFenced = true;
					} else {
						failure = reserveSubscriptionAuthorizationFailureWhileLocked(
								McpStreamTerminationReason
										.SUBSCRIPTION_AUTHORIZATION_EXPIRED,
								null);
					}
				} else if (!subscriptionAuthorizationFenced
						&& subscriptionAuthorizationCheck == null
						&& subscriptionAuthorizationRenewalScheduled
						&& nowNanos - subscriptionAuthorizationRenewalNanos >= 0L) {
					subscriptionAuthorizationRenewalScheduled = false;
					renew = true;
				}
			}
			if (failure != null) {
				finishSubscriptionAuthorizationFailure(failure);
			} else if (renew) {
				subscriptionAuthorizationSchedulingTestHooks
						.beforeRenewalScheduling();
				scheduleSubscriptionAuthorizationCheck(
						SubscriptionAuthorizationCheckKind.RENEWAL);
			}
		}

		private boolean subscriptionAuthorizationAllowsDeliveryWhileLocked() {
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required for subscription authorization state.");
			if (subscriptionRuntimeConfiguration.authorizer().isEmpty())
				return true;
			return subscriptionAuthorizationEstablished
					&& !subscriptionAuthorizationFenced
					&& applicationClock.nanoTime()
							- subscriptionAuthorizationExpiryNanos < 0L;
		}

		private boolean subscriptionAuthorizationAllowsActivationAtGenerationWhileLocked(
				long reconciliationGeneration) {
			if (!Thread.holdsLock(lock) || !Thread.holdsLock(subscriptionLock))
				throw new IllegalStateException(
						"The request-control and subscription locks are required for subscription activation.");
			if (subscriptionRuntimeConfiguration.authorizer().isEmpty())
				return true;
			return subscriptionAuthorizationAllowsDeliveryWhileLocked()
					&& subscriptionAuthorizationReconciliationGeneration
							== reconciliationGeneration;
		}

		private boolean subscriptionAuthorizationAllowsActivationWhileLocked() {
			if (subscriptionRuntimeConfiguration.authorizer().isEmpty())
				return true;
			return subscriptionAuthorizationAllowsDeliveryWhileLocked()
					&& subscriptionAuthorizationReconciliationGeneration
							== currentSubscriptionReconciliationGeneration();
		}

		@NonNull
		private McpRequestContext currentSubscriptionRequestContext(
				@NonNull String jsonRpcMethod) {
			McpRequestContext initial;
			Optional<Object> applicationContext;
			synchronized (lock) {
				initial = publicRequestContext.orElseThrow(() ->
						new IllegalStateException(
								"An admitted MCP subscription context is unavailable."));
				applicationContext = subscriptionAuthorizationEstablished
						? subscriptionAuthorizationApplicationContext
						: initial.getAdmissionIdentity().getApplicationContext();
			}
			return new DerivedSubscriptionRequestContext(initial,
					requireNonNull(jsonRpcMethod), applicationContext);
		}

		@NonNull
		private InitialCatalogProjectionResult establishInitialCatalogBaselines(
				@NonNull String endpointPath,
				@NonNull AcceptedSubscriptionFilter filter,
				@NonNull McpProtocolProfile protocolProfile) {
			requireNonNull(endpointPath);
			requireNonNull(filter);
			requireNonNull(protocolProfile);
			McpCatalogProjectionQueue.Digest tools = null;
			McpCatalogProjectionQueue.Digest prompts = null;

			for (McpCatalogProjectionQueue.Family family
					: McpCatalogProjectionQueue.Family.values()) {
				if (!catalogFamilyAccepted(filter, family))
					continue;
				CatalogProjectionCheck check;
				CatalogProjectionDisposition immediateDisposition = null;
				synchronized (lock) {
					if (terminal || canceled || subscriptionCapReservation == null) {
						immediateDisposition = CatalogProjectionDisposition.TERMINATED;
						check = null;
					} else if (catalogProjectionCheck != null) {
						throw new IllegalStateException(
								"An MCP catalog projection is already outstanding.");
					} else {
						long projectionDeadlineNanos =
								catalogProjectionDeadlineWhileLocked();
						if (applicationClock.nanoTime() - projectionDeadlineNanos >= 0L) {
							immediateDisposition =
									CatalogProjectionDisposition.TIMED_OUT;
							check = null;
						} else {
							McpApplicationExecution.BoundedPolicyCancellation cancellation =
									application.newBoundedPolicyCancellation();
							check = new CatalogProjectionCheck(family, null,
									catalogAuthorizationRevision,
									subscriptionAuthorizationGeneration,
									endpointPath, protocolProfile,
									currentSubscriptionRequestContext(
											catalogJsonRpcMethod(family)),
									cancellation,
								new BoundedPolicyPhysicalExit(),
									projectionDeadlineNanos);
							catalogProjectionCallbackCompletionDeferred = false;
							catalogProjectionCheck = check;
						}
					}
				}

				if (immediateDisposition != null) {
					if (immediateDisposition != CatalogProjectionDisposition.TERMINATED)
						recordCatalogProjectionResult(endpointPath,
								immediateDisposition);
					return new InitialCatalogProjectionResult(
							immediateDisposition, null);
				}

				CatalogProjectionCheck reserved = requireNonNull(check);
				CatalogProjectionExecution execution =
						executeCatalogProjectionCheck(reserved);
				if (execution.disposition()
						!= CatalogProjectionDisposition.TIMED_OUT
						&& reserved.cancellation()
								.canceledPhysicalWorkOutstanding()) {
					boolean retryMayRemainPossible;
					synchronized (lock) {
						retryMayRemainPossible = sameInstance(catalogProjectionCheck, reserved)
								&& !terminal && !canceled
								&& subscriptionCapReservation != null;
					}
					if (retryMayRemainPossible
							&& !reserved.physicalExit().awaitUntil(
									applicationClock, reserved.deadlineNanos()))
						execution = CatalogProjectionExecution.timedOut(
								new McpApplicationPolicyDeadlineException(false), false);
				}
				CatalogProjectionDisposition disposition;
				synchronized (lock) {
				if (!sameInstance(catalogProjectionCheck, reserved)) {
						disposition = CatalogProjectionDisposition.STALE_RESULT;
					} else {
						catalogProjectionCheck = null;
						if (terminal || canceled || subscriptionCapReservation == null)
							disposition = CatalogProjectionDisposition.TERMINATED;
						else if (execution.disposition()
								== CatalogProjectionDisposition.TIMED_OUT)
							disposition = CatalogProjectionDisposition.TIMED_OUT;
						else if (reserved.authorizationRevision()
								!= catalogAuthorizationRevision
								|| reserved.authorizationGeneration()
										!= subscriptionAuthorizationGeneration
								|| !subscriptionAuthorizationAllowsDeliveryWhileLocked())
							disposition = CatalogProjectionDisposition.STALE_RESULT;
						else if (applicationClock.nanoTime()
								- reserved.deadlineNanos() >= 0L)
							disposition = CatalogProjectionDisposition.TIMED_OUT;
						else
							disposition = execution.disposition();
					}
				}
				if (disposition != CatalogProjectionDisposition.TERMINATED)
					recordCatalogProjectionResult(endpointPath, disposition);
				if (disposition != CatalogProjectionDisposition.SUCCEEDED)
					return new InitialCatalogProjectionResult(disposition, null);
				if (family == McpCatalogProjectionQueue.Family.TOOLS)
					tools = requireNonNull(execution.digest());
				else
					prompts = requireNonNull(execution.digest());
			}

			return new InitialCatalogProjectionResult(
					CatalogProjectionDisposition.SUCCEEDED,
					new InitialCatalogBaselines(tools, prompts));
		}

		@NonNull
		private CatalogProjectionExecution executeCatalogProjectionCheck(
				@NonNull CatalogProjectionCheck check) {
			CatalogProjectionCheck requiredCheck = requireNonNull(check);
			EndpointRuntime endpointRuntime = endpointsByPath.get(
					requiredCheck.endpointPath());
			if (endpointRuntime == null)
				return CatalogProjectionExecution.failed(new IllegalStateException(
						"The MCP subscription endpoint is unavailable."));
			try {
				McpCatalogProjectionQueue.Digest digest = application.invokeBoundedPolicy(
						() -> projectCatalogDigest(endpointRuntime,
								requiredCheck.family(), requiredCheck.protocolProfile(),
								requiredCheck.requestContext(),
								requiredCheck.cancellation(), acceptLanguageValues,
								requiredCheck.deadlineNanos()),
						requiredCheck.deadlineNanos(),
						requiredCheck.cancellation(),
						() -> catalogProjectionPhysicallyExited(requiredCheck));
				return CatalogProjectionExecution.succeeded(digest);
			} catch (McpApplicationPolicyCapacityException exception) {
				return CatalogProjectionExecution.capacityRejected(exception);
			} catch (McpApplicationPolicyDeadlineException exception) {
				return CatalogProjectionExecution.timedOut(
						exception, exception.queued());
			} catch (Throwable throwable) {
				if (throwable instanceof InterruptedException
						&& !requiredCheck.cancellation().isCancellationRequested())
					Thread.currentThread().interrupt();
				if (applicationClock.nanoTime()
						- requiredCheck.deadlineNanos() >= 0L)
					return CatalogProjectionExecution.timedOut(throwable, false);
				return CatalogProjectionExecution.failed(throwable);
			} finally {
				if (requiredCheck.cancellation().isActive())
					try {
						requiredCheck.cancellation().complete();
					} catch (IllegalStateException ignored) {
						// A concurrent fence fixed and released cancellation first.
					}
			}
		}

		private void catalogProjectionPhysicallyExited(
				@NonNull CatalogProjectionCheck check) {
			CatalogProjectionCheck requiredCheck = requireNonNull(check);
			requiredCheck.physicalExit().markExited();
			boolean submitAgain = false;
			synchronized (lock) {
				if (!sameInstance(catalogProjectionCheck, requiredCheck)
						|| !catalogProjectionCallbackCompletionDeferred)
					return;
				catalogProjectionCallbackCompletionDeferred = false;
				catalogProjectionCheck = null;
				McpCatalogProjectionQueue.Projection projection =
						requiredCheck.projection();
				if (projection != null && catalogProjectionQueue.owns(projection))
					submitAgain = catalogProjectionQueue.finish(projection,
							catalogProjectionOwnerActiveWhileLocked(), false);
			}
			if (submitAgain)
				submitCatalogProjection();
		}

		private long catalogProjectionDeadlineWhileLocked() {
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required for catalog projection state.");
			long nowNanos = applicationClock.nanoTime();
			long projectionDeadlineNanos = nowNanos
					+ subscriptionRuntimeConfiguration.catalogProjectionTimeout().toNanos();
			projectionDeadlineNanos = minimumDeadline(nowNanos,
					projectionDeadlineNanos,
					deadlineNanos);
			SubscriptionRegistration registration = subscriptionRegistration != null
					? subscriptionRegistration : subscriptionCapReservation;
			if (registration != null)
				projectionDeadlineNanos = minimumDeadline(nowNanos,
						projectionDeadlineNanos,
						registration.openedAtNanos()
								+ subscriptionRuntimeConfiguration
										.maximumSubscriptionDuration().toNanos());
			if (subscriptionRuntimeConfiguration.authorizer().isPresent()
					&& subscriptionAuthorizationEstablished)
				projectionDeadlineNanos = minimumDeadline(nowNanos,
						projectionDeadlineNanos,
						subscriptionAuthorizationExpiryNanos);
			return projectionDeadlineNanos;
		}

		private boolean catalogFamilyAccepted(
				@NonNull AcceptedSubscriptionFilter filter,
				McpCatalogProjectionQueue.@NonNull Family family) {
			return requireNonNull(family) == McpCatalogProjectionQueue.Family.TOOLS
					? requireNonNull(filter).toolsListChanged()
					: requireNonNull(filter).promptsListChanged();
		}

		@NonNull
		private String catalogJsonRpcMethod(
				McpCatalogProjectionQueue.@NonNull Family family) {
			return requireNonNull(family) == McpCatalogProjectionQueue.Family.TOOLS
					? "tools/list" : "prompts/list";
		}

		private void recordCatalogProjectionResult(@NonNull String endpointPath,
				@NonNull CatalogProjectionDisposition disposition) {
			McpMetricsEvent.SubscriptionMaintenance.Outcome outcome = switch (
					requireNonNull(disposition)) {
				case SUCCEEDED -> McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED;
				case TIMED_OUT -> McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT;
				case CAPACITY_REJECTED -> McpMetricsEvent.SubscriptionMaintenance.Outcome
						.CAPACITY_REJECTED;
				case FAILED -> McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED;
				case STALE_RESULT -> McpMetricsEvent.SubscriptionMaintenance.Outcome
						.STALE_RESULT_DISCARDED;
				case TERMINATED -> null;
			};
			if (outcome != null)
				recordSubscriptionMaintenance(endpointPath,
						McpMetricsEvent.SubscriptionMaintenance.Work.CATALOG_PROJECTION,
						outcome);
		}

		private void failSubscriptionAuthorization(
				@NonNull McpStreamTerminationReason exactReason,
				@Nullable Throwable cause) {
			SubscriptionAuthorizationFailure failure;
			synchronized (lock) {
				failure = reserveSubscriptionAuthorizationFailureWhileLocked(
						requireNonNull(exactReason), cause);
			}
			finishSubscriptionAuthorizationFailure(failure);
		}

		@NonNull
		private SubscriptionAuthorizationFailure
				reserveSubscriptionAuthorizationFailureWhileLocked(
						@NonNull McpStreamTerminationReason exactReason,
						@Nullable Throwable cause) {
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required to fail subscription authorization.");
			requireNonNull(exactReason);
			subscriptionAuthorizationFenced = true;
			SubscriptionAuthorizationCheck check = subscriptionAuthorizationCheck;
			CatalogProjectionCheck catalogCheck = catalogProjectionCheck;
			catalogProjectionCheck = null;
			catalogProjectionQueue.reset();
			boolean signalTimer = false;
			if (subscriptionOwned && responseStream != null && !terminal && !canceled
					&& !streamAbortOwned && !streamTerminalResponseOwned) {
				StreamTerminationReason reason = exactReason
						== McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_EXPIRED
						? StreamTerminationReason.RESPONSE_TIMEOUT
						: exactReason == McpStreamTerminationReason
								.SUBSCRIPTION_AUTHORIZATION_DENIED
							? StreamTerminationReason.APPLICATION_CANCELED
							: StreamTerminationReason.INTERNAL_ERROR;
				streamAbortOwned = true;
				subscriptionOwned = false;
				taskNotificationProjectionQueue.reset();
				plannedSubscriptionCloseExactReason = exactReason;
				pendingSubscriptionStreamFailure = new SubscriptionStreamFailure(
						responseStream, reason, cause);
				signalTimer = true;
			}
			return new SubscriptionAuthorizationFailure(check, catalogCheck,
					exactReason, signalTimer);
		}

		private void finishSubscriptionAuthorizationFailure(
				@NonNull SubscriptionAuthorizationFailure failure) {
			SubscriptionAuthorizationFailure requiredFailure = requireNonNull(failure);
			SubscriptionAuthorizationCheck check = requiredFailure.check();
			if (check != null)
				check.cancellation().cancel(
						requiredFailure.exactReason() == McpStreamTerminationReason
								.SUBSCRIPTION_AUTHORIZATION_EXPIRED
							? StreamTerminationReason.RESPONSE_TIMEOUT
							: StreamTerminationReason.APPLICATION_CANCELED);
			CatalogProjectionCheck catalogCheck = requiredFailure.catalogCheck();
			if (catalogCheck != null)
				catalogCheck.cancellation().cancel(
						StreamTerminationReason.APPLICATION_CANCELED);
			if (requiredFailure.signalTimer())
				application.signalDeadlineTimer();
		}

		@NonNull
		private McpStreamTerminationReason terminationReason(
				@NonNull SubscriptionAuthorizationCheckKind kind,
				@NonNull SubscriptionAuthorizationDisposition disposition) {
			if (kind == SubscriptionAuthorizationCheckKind.RECONCILIATION)
				return McpStreamTerminationReason.SUBSCRIPTION_RECONCILIATION_FAILED;
			if (disposition == SubscriptionAuthorizationDisposition.DENIED)
				return McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_DENIED;
			return McpStreamTerminationReason.SUBSCRIPTION_AUTHORIZATION_CHECK_FAILED;
		}

		private long minimumDeadline(long nowNanos, long first, long second) {
			return first - nowNanos <= second - nowNanos ? first : second;
		}

		@NonNull
		@SuppressWarnings("ReferenceEquality")
		private SubscriptionOpenResult openSubscription(
				@NonNull String endpointPath,
				@NonNull McpNormalizedEndpoint endpoint,
				@NonNull McpEffectivePartition authorizationPartition,
				@NonNull McpJsonRpcId subscriptionId,
				@NonNull AcceptedSubscriptionFilter filter,
				@NonNull List<@NonNull Header> additionalHeaders,
				@NonNull McpHttpEndpointPolicy endpointPolicy,
				@NonNull McpProtocolProfile protocolProfile,
				@NonNull SubscriptionRegistration capReservation) {
			requireNonNull(additionalHeaders);
			requireNonNull(protocolProfile);
			requireNonNull(capReservation);
			// The terminal carries the request ID both as its JSON-RPC ID and as
			// subscription metadata. Pre-render that actual request-specific shape so
			// an acknowledgment can never commit a stream whose terminal cannot fit.
			McpJsonRpcMessage.ResultResponse preRenderedTerminal;
			try {
				preRenderedTerminal = subscriptionTerminalResponse(protocolProfile,
						subscriptionId, endpoint);
			} catch (RuntimeException exception) {
				return SubscriptionOpenResult.TERMINAL_PREFLIGHT_FAILED;
			}
			// The subscription cap was reserved before any external task lookup.
			// Continue the two-phase open without a framework lock held: retain the
			// original request deadline, pre-render localized terminal metadata,
			// and only then materialize and commit the stream. The rendered stream
			// deadline extension therefore follows successful pre-render.
			boolean localizeTerminal = endpointPolicy.catalogLocalizer().isPresent()
					&& publicRequestContext().isPresent();
			Object localizationInvalidationToken = null;
			Object expectedCatalogInvalidationToken =
					catalogInvalidationToken(endpointPath);
			Optional<String> contentLanguage = Optional.empty();
			boolean terminalPreflightComplete = false;

			if (localizeTerminal) {
				localizationInvalidationToken = localizationInvalidationToken(
						endpointPath);
				McpRuntimeCatalogLocalizer.Outcome outcome;

				try {
					outcome = localizeSubscriptionTerminal(endpointPolicy,
							preRenderedTerminal, this);
				} catch (RuntimeException exception) {
					outcome = null;
				}

				if (outcome == null || outcome.disposition()
						== McpRuntimeCatalogLocalizer.Disposition.FAIL_REQUEST) {
					return SubscriptionOpenResult.LOCALIZATION_FAILED;
				}
				contentLanguage = outcome.contentLanguage();
				terminalPreflightComplete = outcome.disposition()
						== McpRuntimeCatalogLocalizer.Disposition.CANONICAL;

				if (outcome.disposition()
						== McpRuntimeCatalogLocalizer.Disposition.LOCALIZED)
					preRenderedTerminal = new McpJsonRpcMessage.ResultResponse(
							subscriptionId, McpWireResult.withPrecomputedJsonObject(
									preRenderedTerminal.result(),
									outcome.document()),
							McpJsonObject.empty());
			}

			if (!terminalPreflightComplete) {
				try {
					envelopeCodec.encode(preRenderedTerminal);
				} catch (RuntimeException exception) {
					return SubscriptionOpenResult.TERMINAL_PREFLIGHT_FAILED;
				}
			}
			InitialCatalogProjectionResult initialCatalogProjection =
					establishInitialCatalogBaselines(endpointPath, filter,
							protocolProfile);
			if (initialCatalogProjection.disposition()
					== CatalogProjectionDisposition.STALE_RESULT)
				return SubscriptionOpenResult.CATALOG_STALE;
			if (initialCatalogProjection.disposition()
					== CatalogProjectionDisposition.TERMINATED)
				return SubscriptionOpenResult.TERMINATED;
			if (initialCatalogProjection.disposition()
					!= CatalogProjectionDisposition.SUCCEEDED)
				return SubscriptionOpenResult.CATALOG_PROJECTION_FAILED;
			InitialCatalogBaselines initialCatalogBaselines = requireNonNull(
					initialCatalogProjection.baselines());
			SubscriptionOpenReservation reservation;
			try {
				synchronized (streamObservationTransitionLock) {
					reservation = reserveSubscriptionOpen(endpointPath, endpoint,
							authorizationPartition, subscriptionId, filter,
							protocolProfile, capReservation,
							localizationInvalidationToken,
							expectedCatalogInvalidationToken,
							initialCatalogBaselines, preRenderedTerminal);
					if (reservation.result() != SubscriptionOpenResult.OPENED)
						return reservation.result();
					synchronized (lock) {
						if (terminal || canceled)
							return SubscriptionOpenResult.TERMINATED;
					}
					// Activation already committed under the reconciliation epoch. The
					// transition lock keeps a concurrently-triggered close from being
					// observed before this application-facing open observation.
					markStreamOpenedInOrder(true);
				}
			} finally {
				drainApplicationExecutionObservation();
			}
			McpRequestSseStream stream = requireNonNull(reservation.stream());
			Consumer<MicrohttpResponse> callback = requireNonNull(
					reservation.responseCallback());
			markLifecycleTransportStarted();
			try {
				callback.accept(stream.response(withContentLanguage(
						additionalHeaders, contentLanguage)));
			} catch (Throwable throwable) {
				stream.fail(StreamTerminationReason.WRITE_FAILED, throwable);
			}
			return SubscriptionOpenResult.OPENED;
		}

		/**
		 * Reserves only the per-authorization-partition and server subscription
		 * capacity before external task authorization and localized terminal
		 * pre-render. Neither operation runs with a framework lock held.
		 */
		@NonNull
		private SubscriptionCapReservation reserveSubscriptionCap(
				@NonNull String endpointPath,
				@NonNull McpNormalizedEndpoint endpoint,
				@NonNull McpEffectivePartition authorizationPartition,
				@NonNull McpJsonRpcId subscriptionId,
				@NonNull AcceptedSubscriptionFilter filter,
				@NonNull McpProtocolProfile protocolProfile) {
			synchronized (lock) {
				if (canceled || terminal || applicationOwned || subscriptionOwned
						|| subscriptionCapReservation != null)
					return new SubscriptionCapReservation(
							SubscriptionOpenResult.TERMINATED, null);
				SubscriptionRegistrationAttempt registrationAttempt = registerSubscription(
						this, endpointPath, endpoint, authorizationPartition,
						subscriptionId, filter, protocolProfile);
				if (registrationAttempt.result()
						== SubscriptionRegistrationResult.NOT_ACCEPTING)
					return new SubscriptionCapReservation(
							SubscriptionOpenResult.SERVER_STOPPING, null);
				if (registrationAttempt.result()
						== SubscriptionRegistrationResult.CAPACITY_REJECTED)
					return new SubscriptionCapReservation(
							SubscriptionOpenResult.CAPACITY_REJECTED, null);
				SubscriptionRegistration registration = requireNonNull(
						registrationAttempt.registration());
				subscriptionCapReservation = registration;
				return new SubscriptionCapReservation(
						SubscriptionOpenResult.OPENED, registration);
			}
		}

		@SuppressWarnings("ReferenceEquality")
		private @Nullable SubscriptionRegistration updateSubscriptionCapFilter(
				@NonNull SubscriptionRegistration expectedRegistration,
				@NonNull AcceptedSubscriptionFilter filter) {
			requireNonNull(expectedRegistration);
			requireNonNull(filter);
			synchronized (lock) {
				if (canceled || terminal || subscriptionCapReservation
						!= expectedRegistration)
					return null;
				SubscriptionRegistration updatedRegistration =
						new SubscriptionRegistration(
								expectedRegistration.endpointPath(),
								expectedRegistration.endpoint(),
								expectedRegistration.authorizationPartition(),
								expectedRegistration.subscriptionId(), filter,
								expectedRegistration.protocolProfile(),
								expectedRegistration.openedAtNanos());
				subscriptionCapReservation = updatedRegistration;
				return updatedRegistration;
			}
		}

		@SuppressWarnings("ReferenceEquality")
		private void releaseSubscriptionCapReservation(
				@NonNull SubscriptionRegistration expectedRegistration) {
			SubscriptionRegistration registration = null;
			synchronized (lock) {
				if (subscriptionCapReservation == requireNonNull(
						expectedRegistration)) {
					registration = subscriptionCapReservation;
					subscriptionCapReservation = null;
				}
			}
			if (registration != null)
				removeSubscription(this, registration);
		}

		@NonNull
		@SuppressWarnings("ReferenceEquality")
		private SubscriptionOpenReservation reserveSubscriptionOpen(
				@NonNull String endpointPath,
				@NonNull McpNormalizedEndpoint endpoint,
				@NonNull McpEffectivePartition authorizationPartition,
				@NonNull McpJsonRpcId subscriptionId,
				@NonNull AcceptedSubscriptionFilter filter,
				@NonNull McpProtocolProfile protocolProfile,
				@NonNull SubscriptionRegistration preReservedRegistration,
				@Nullable Object expectedLocalizationInvalidationToken,
				@NonNull Object expectedCatalogInvalidationToken,
				@NonNull InitialCatalogBaselines initialCatalogBaselines,
				McpJsonRpcMessage.@NonNull ResultResponse preRenderedTerminal) {
			requireNonNull(preReservedRegistration);
			requireNonNull(expectedCatalogInvalidationToken);
			requireNonNull(initialCatalogBaselines);
			requireNonNull(preRenderedTerminal);
			SubscriptionRegistration registration;
			synchronized (lock) {
				if (canceled || terminal || applicationOwned || subscriptionOwned)
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.TERMINATED,
							null, null, null);
				if (subscriptionCapReservation != preReservedRegistration)
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.TERMINATED,
							null, null, null);
				registration = preReservedRegistration;
				if (!registration.endpointPath().equals(endpointPath)
						|| registration.endpoint() != endpoint
						|| !registration.authorizationPartition().equals(
								authorizationPartition)
						|| !registration.subscriptionId().equals(subscriptionId)
						|| !registration.filter().equals(filter)
						|| registration.protocolProfile() != protocolProfile)
					throw new IllegalStateException(
							"An MCP subscription reservation cannot change before activation.");
				// This is the last shutdown-admission check before materialization.
				// The request-control lock remains held while stream state is installed,
				// so a shutdown that races after this point snapshots the still-pending
				// control and cannot complete it before that state is coherent.
				if (!subscriptionMayCommit(this)) {
					subscriptionCapReservation = null;
					removeSubscription(this, registration);
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.SERVER_STOPPING,
							null, null, null);
				}
				if (!subscriptionAuthorizationAllowsActivationWhileLocked())
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.AUTHORIZATION_STALE,
							null, null, null);
				// The epoch validation and pending-to-active registry transition are
				// one subscriptionLock operation. Once this succeeds, a later
				// reconciliation sees an active subscription and must fence it before
				// returning; an earlier reconciliation forces the initial check loop
				// to run again without materializing a response channel.
				SubscriptionActivationResult activation = activateSubscription(this,
						registration, expectedLocalizationInvalidationToken,
						expectedCatalogInvalidationToken);
				if (activation == SubscriptionActivationResult.AUTHORIZATION_STALE)
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.AUTHORIZATION_STALE,
							null, null, null);
				if (activation == SubscriptionActivationResult.CATALOG_STALE)
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.CATALOG_STALE,
							null, null, null);
				if (activation == SubscriptionActivationResult.NOT_ACTIVATED)
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.TERMINATED,
							null, null, null);
				try {
					initialCatalogBaselines.install(catalogProjectionQueue);
					McpRequestSseStream stream = newResponseStream();
					McpOutboundChannel.OfferResult result = stream.offerMessage(
							subscriptionAcknowledgement(
									registration.protocolProfile(),
									subscriptionId, filter));
					if (result != McpOutboundChannel.OfferResult.ACCEPTED)
						throw new IllegalStateException(
								"A new MCP subscription stream could not accept its acknowledgment.");
					responseStream = stream;
					subscriptionCapReservation = null;
					subscriptionRegistration = registration;
					subscriptionOwned = true;
					subscriptionAuthorizationAcknowledged = true;
					long nowNanos = applicationClock.nanoTime();
					deadlineNanos = registration.openedAtNanos()
							+ subscriptionRuntimeConfiguration
									.maximumSubscriptionDuration().toNanos();
					nextKeepAliveNanos = nowNanos
							+ transportConfiguration.keepAliveInterval().toNanos();
					preRenderedSubscriptionTerminal = activation
							== SubscriptionActivationResult
									.ACTIVATED_CURRENT_LOCALIZATION
							? preRenderedTerminal : null;
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.OPENED, stream,
							takeResponseCallback(), registration);
				} catch (RuntimeException | Error failure) {
					catalogProjectionQueue.reset();
					subscriptionCapReservation = null;
					removeSubscription(this, registration);
					throw failure;
				}
			}
		}

		private void scheduleTaskSubscriptionEvent(
				McpSubscriptionEventSource.Event.@NonNull TaskChanged event) {
			String taskId = requireNonNull(event).taskId();
			boolean submit = false;
			synchronized (lock) {
				if (!subscriptionOwned || canceled || terminal
						|| streamAbortOwned || streamTerminalResponseOwned
						|| subscriptionRegistration == null
						|| responseStream == null
						|| !subscriptionAuthorizationAllowsDeliveryWhileLocked()
						|| !subscriptionRegistration.filter().containsTask(taskId))
					return;
				submit = taskNotificationProjectionQueue.request(taskId);
			}
			if (submit)
				submitTaskNotificationProjection();
		}

		private void scheduleCatalogSubscriptionEvent(@NonNull Event event) {
			Event requiredEvent = requireNonNull(event);
			boolean submit = false;
			int coalesced = 0;
			String endpointPath = null;
			synchronized (lock) {
				if (requiredEvent instanceof McpSubscriptionEventSource.Event
						.LocalizationCatalogsChanged)
					preRenderedSubscriptionTerminal = null;
				if (!catalogProjectionOwnerActiveWhileLocked()
						|| !subscriptionAuthorizationAllowsDeliveryWhileLocked())
					return;
				SubscriptionRegistration registration = requireNonNull(
						subscriptionRegistration);
				endpointPath = registration.endpointPath();
				long projectionDeadlineNanos = catalogProjectionDeadlineWhileLocked();
				for (McpCatalogProjectionQueue.Family family
						: catalogFamilies(requiredEvent)) {
					if (!catalogFamilyAccepted(registration.filter(), family))
						continue;
					McpCatalogProjectionQueue.RequestResult result =
							catalogProjectionQueue.request(family,
									projectionDeadlineNanos);
					if (result == McpCatalogProjectionQueue.RequestResult.SUBMIT)
						submit = true;
					else
						coalesced++;
				}
			}
			for (int index = 0; index < coalesced; index++)
				recordSubscriptionMaintenance(requireNonNull(endpointPath),
						McpMetricsEvent.SubscriptionMaintenance.Work.CATALOG_PROJECTION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED);
			if (submit)
				submitCatalogProjection();
		}

		@NonNull
		private List<McpCatalogProjectionQueue.@NonNull Family> catalogFamilies(
				@NonNull Event event) {
			if (requireNonNull(event) instanceof McpSubscriptionEventSource.Event
					.ToolsListChanged)
				return List.of(McpCatalogProjectionQueue.Family.TOOLS);
			if (event instanceof McpSubscriptionEventSource.Event.PromptsListChanged)
				return List.of(McpCatalogProjectionQueue.Family.PROMPTS);
			if (event instanceof McpSubscriptionEventSource.Event
					.LocalizationCatalogsChanged invalidation) {
				List<McpCatalogProjectionQueue.Family> families = new ArrayList<>(2);
				if (invalidation.tools())
					families.add(McpCatalogProjectionQueue.Family.TOOLS);
				if (invalidation.prompts())
					families.add(McpCatalogProjectionQueue.Family.PROMPTS);
				return List.copyOf(families);
			}
			return List.of();
		}

		private void submitCatalogProjection() {
			processor.executeTaskNotificationProjection(
					new TaskNotificationProjectionJob(
							catalogProjectionSchedulerOwner,
							this::projectCatalogNotification,
							this::rejectCatalogProjection));
		}

		private void projectCatalogNotification() {
			McpCatalogProjectionQueue.Projection projection;
			CatalogProjectionCheck check;
			SubscriptionRegistration registration;
			McpRequestSseStream stream;
			synchronized (lock) {
				if (!catalogProjectionOwnerActiveWhileLocked()) {
					catalogProjectionQueue.reset();
					return;
				}
				if (!subscriptionAuthorizationAllowsDeliveryWhileLocked()) {
					catalogProjectionQueue.deferOutstandingJob();
					return;
				}
				projection = catalogProjectionQueue.poll();
				if (projection == null)
					return;
				if (catalogProjectionCheck != null)
					throw new IllegalStateException(
							"An MCP catalog projection is already outstanding.");
				registration = requireNonNull(subscriptionRegistration);
				stream = requireNonNull(responseStream);
				McpApplicationExecution.BoundedPolicyCancellation cancellation =
						application.newBoundedPolicyCancellation();
				check = new CatalogProjectionCheck(projection.family(), projection,
						catalogAuthorizationRevision,
						subscriptionAuthorizationGeneration,
						registration.endpointPath(), registration.protocolProfile(),
						currentSubscriptionRequestContext(
								catalogJsonRpcMethod(projection.family())),
							cancellation, new BoundedPolicyPhysicalExit(),
						projection.deadlineNanos());
				catalogProjectionCallbackCompletionDeferred = false;
				catalogProjectionCheck = check;
			}

			CatalogProjectionExecution execution =
					executeCatalogProjectionCheck(check);
			McpJsonRpcMessage.Notification notification = null;
			Throwable notificationFailure = null;
			if (execution.disposition() == CatalogProjectionDisposition.SUCCEEDED
					&& !projection.baseline().equals(execution.digest())) {
				try {
					notification = listChangedNotification(
							registration.protocolProfile(),
							registration.subscriptionId(),
							projection.family()
									== McpCatalogProjectionQueue.Family.TOOLS
										? "notifications/tools/list_changed"
										: "notifications/prompts/list_changed");
				} catch (Throwable throwable) {
					notificationFailure = throwable;
				}
			}

			boolean submitAgain = false;
			boolean recordCoalesced = false;
			boolean offerReserved = false;
			boolean completionDeferred = false;
			StreamTerminationReason failureReason = null;
			Throwable failureCause = execution.failure();
			CatalogProjectionDisposition disposition = execution.disposition();
			synchronized (catalogOfferLock) {
				synchronized (lock) {
				boolean ownsCheck = sameInstance(catalogProjectionCheck, check);
					boolean ownerActive = catalogProjectionOwnerActiveWhileLocked();
					if (!ownsCheck || !catalogProjectionQueue.owns(projection)) {
						disposition = ownerActive
								? CatalogProjectionDisposition.STALE_RESULT
								: CatalogProjectionDisposition.TERMINATED;
					} else if (!ownerActive) {
						catalogProjectionQueue.finish(projection, false, false);
						disposition = CatalogProjectionDisposition.TERMINATED;
					} else if (check.cancellation()
							.canceledPhysicalWorkOutstanding()) {
						catalogProjectionQueue.markActiveCompletionDeferred(projection);
						catalogProjectionCallbackCompletionDeferred = true;
						completionDeferred = true;
						if (check.authorizationRevision()
								!= catalogAuthorizationRevision
								|| check.authorizationGeneration()
										!= subscriptionAuthorizationGeneration
								|| !subscriptionAuthorizationAllowsDeliveryWhileLocked()
								|| !catalogFamilyAccepted(requireNonNull(
										subscriptionRegistration).filter(),
										projection.family()))
							disposition = CatalogProjectionDisposition.STALE_RESULT;
						else if (applicationClock.nanoTime()
								- projection.deadlineNanos() >= 0L
								|| execution.disposition()
										== CatalogProjectionDisposition.TIMED_OUT)
							disposition = CatalogProjectionDisposition.TIMED_OUT;
					} else if (check.authorizationRevision()
							!= catalogAuthorizationRevision
							|| check.authorizationGeneration()
									!= subscriptionAuthorizationGeneration
							|| !subscriptionAuthorizationAllowsDeliveryWhileLocked()
							|| !catalogFamilyAccepted(
									requireNonNull(subscriptionRegistration).filter(),
									projection.family())) {
						submitAgain = catalogProjectionQueue.finish(
								projection, true, false);
						disposition = CatalogProjectionDisposition.STALE_RESULT;
					} else if (applicationClock.nanoTime()
							- projection.deadlineNanos() >= 0L) {
						submitAgain = catalogProjectionQueue.finish(
								projection, true, false);
						disposition = CatalogProjectionDisposition.TIMED_OUT;
					} else if (execution.disposition()
							!= CatalogProjectionDisposition.SUCCEEDED) {
						submitAgain = catalogProjectionQueue.finish(
								projection, true, false);
					} else if (notificationFailure != null) {
						catalogProjectionQueue.reset();
						disposition = CatalogProjectionDisposition.FAILED;
						failureReason = StreamTerminationReason.INTERNAL_ERROR;
						failureCause = notificationFailure;
					} else if (notification == null) {
						submitAgain = catalogProjectionQueue.finish(
								projection, true, true);
					} else {
						// Authorization transitions share this lock, so the offer either
						// reaches transport before their fence or is suppressed by it.
						offerReserved = true;
					}
					if (!offerReserved && !completionDeferred && ownsCheck) {
						catalogProjectionCallbackCompletionDeferred = false;
						catalogProjectionCheck = null;
					}
				}

				if (offerReserved) {
					McpOutboundChannel.OfferResult offer = null;
					Throwable offerFailure = null;
					boolean offerDeadlineExpired = false;
					try {
						Optional<McpOutboundChannel.OfferResult> attemptedOffer =
								stream.offerCoalescingMessageIf(
								requireNonNull(notification),
								catalogSubscriptionEventKey(projection.family()),
								() -> applicationClock.nanoTime()
										- projection.deadlineNanos() < 0L);
						offer = attemptedOffer.orElse(null);
						offerDeadlineExpired = attemptedOffer.isEmpty();
					} catch (Throwable throwable) {
						offerFailure = throwable;
					}

					synchronized (lock) {
				boolean ownsCheck = sameInstance(catalogProjectionCheck, check);
						if (ownsCheck) {
							catalogProjectionCallbackCompletionDeferred = false;
							catalogProjectionCheck = null;
						}
						boolean ownerActive = catalogProjectionOwnerActiveWhileLocked();
						if (!ownsCheck || !catalogProjectionQueue.owns(projection)) {
							disposition = ownerActive
									? CatalogProjectionDisposition.STALE_RESULT
									: CatalogProjectionDisposition.TERMINATED;
						} else if (!ownerActive) {
							catalogProjectionQueue.finish(projection, false, false);
							disposition = CatalogProjectionDisposition.TERMINATED;
						} else if (offerDeadlineExpired) {
							submitAgain = catalogProjectionQueue.finish(
									projection, true, false);
							disposition = CatalogProjectionDisposition.TIMED_OUT;
						} else if (offerFailure != null) {
							catalogProjectionQueue.reset();
							disposition = CatalogProjectionDisposition.FAILED;
							failureReason = StreamTerminationReason.INTERNAL_ERROR;
							failureCause = offerFailure;
						} else {
							switch (requireNonNull(offer)) {
								case ACCEPTED -> {
									catalogProjectionQueue.advanceBaseline(projection,
											requireNonNull(execution.digest()));
									submitAgain = catalogProjectionQueue.finish(
											projection, true, true);
								}
								case COALESCED -> {
									recordCoalesced = true;
									submitAgain = catalogProjectionQueue.finish(
											projection, true, false);
								}
								case CLOSED -> {
									catalogProjectionQueue.finish(
											projection, false, false);
									disposition = CatalogProjectionDisposition.TERMINATED;
								}
								case FULL -> {
									catalogProjectionQueue.reset();
									disposition = CatalogProjectionDisposition
											.CAPACITY_REJECTED;
									failureReason = StreamTerminationReason.BACKPRESSURE;
								}
								case TOO_LARGE, NOT_IDLE -> {
									catalogProjectionQueue.reset();
									disposition = CatalogProjectionDisposition.FAILED;
									failureReason = StreamTerminationReason.INTERNAL_ERROR;
								}
							}
						}
					}
				}
			}

			if (disposition != CatalogProjectionDisposition.TERMINATED)
				recordCatalogProjectionResult(registration.endpointPath(), disposition);
			if (recordCoalesced)
				recordSubscriptionMaintenance(registration.endpointPath(),
						McpMetricsEvent.SubscriptionMaintenance.Work.CATALOG_PROJECTION,
						McpMetricsEvent.SubscriptionMaintenance.Outcome.COALESCED);
			if (failureReason != null) {
				scheduleSubscriptionStreamFailure(stream, failureReason, failureCause);
				return;
			}
			if (submitAgain)
				submitCatalogProjection();
		}

		@NonNull
		private Object catalogSubscriptionEventKey(
				McpCatalogProjectionQueue.@NonNull Family family) {
			return requireNonNull(family) == McpCatalogProjectionQueue.Family.TOOLS
					? SubscriptionEventKey.TOOLS_LIST_CHANGED
					: SubscriptionEventKey.PROMPTS_LIST_CHANGED;
		}

		private boolean catalogProjectionOwnerActiveWhileLocked() {
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required for catalog projection state.");
			return subscriptionOwned && !canceled && !terminal
					&& !streamAbortOwned && !streamTerminalResponseOwned
					&& subscriptionRegistration != null && responseStream != null;
		}

		private void rejectCatalogProjection() {
			McpRequestSseStream stream = null;
			String endpointPath = null;
			synchronized (lock) {
				if (catalogProjectionOwnerActiveWhileLocked()
						&& catalogProjectionQueue.jobOutstanding()) {
					stream = responseStream;
					endpointPath = requireNonNull(subscriptionRegistration)
							.endpointPath();
					catalogProjectionQueue.reset();
				}
			}
			if (stream == null)
				return;
			recordCatalogProjectionResult(requireNonNull(endpointPath),
					CatalogProjectionDisposition.CAPACITY_REJECTED);
			scheduleSubscriptionStreamFailure(stream,
					StreamTerminationReason.BACKPRESSURE, null);
		}

		private void submitTaskNotificationProjection() {
			processor.executeTaskNotificationProjection(
					new TaskNotificationProjectionJob(
							taskProjectionSchedulerOwner,
							this::projectTaskNotification,
							() -> failTaskNotificationProjection(
									StreamTerminationReason.BACKPRESSURE, null)));
		}

		private void projectTaskNotification() {
			TaskNotificationProjection projection;
			SubscriptionRegistration registration;
			McpRequestSseStream stream;
			McpRequestContext requestContext;
			TaskManagerAdapter taskManagerAdapter;
			long authorizationRevision;
			synchronized (lock) {
				if (!taskNotificationProjectionOwnerActiveWhileLocked()) {
					taskNotificationProjectionQueue.reset();
					return;
				}
				if (!subscriptionAuthorizationAllowsDeliveryWhileLocked()) {
					taskNotificationProjectionQueue.deferOutstandingJob();
					return;
				}
				projection = taskNotificationProjectionQueue.poll();
				if (projection == null)
					return;
				registration = requireNonNull(subscriptionRegistration);
				stream = requireNonNull(responseStream);
				requestContext = subscriptionAuthorizationAllowsDeliveryWhileLocked()
						? currentSubscriptionRequestContext("subscriptions/listen") : null;
				authorizationRevision = catalogAuthorizationRevision;
			}
			if (!registration.filter().containsTask(projection.taskId())) {
				finishTaskNotificationProjection(projection);
				return;
			}
			if (requestContext == null) {
				failTaskNotificationProjection(
						StreamTerminationReason.INTERNAL_ERROR, null);
				return;
			}
			EndpointRuntime endpointRuntime = endpointsByPath.get(
					registration.endpointPath());
			if (endpointRuntime == null
					|| endpointRuntime.binding().taskManagerAdapter().isEmpty()) {
				failTaskNotificationProjection(
						StreamTerminationReason.INTERNAL_ERROR, null);
				return;
			}
			taskManagerAdapter = endpointRuntime.binding().taskManagerAdapter()
					.orElseThrow();

			Optional<TaskSnapshot> taskSnapshot;
			try {
				taskSnapshot = requireNonNull(
						taskManagerAdapter.findTask(requestContext,
								projection.taskId()),
						"The MCP task manager adapter returned null.");
			} catch (Throwable throwable) {
				// Projection is advisory. A transient application-owned lookup failure
				// skips this generation while preserving the subscription and any newer
				// coalesced generation for retry.
				if (throwable instanceof InterruptedException)
					Thread.currentThread().interrupt();
				finishTaskNotificationProjection(projection);
				return;
			}

			try {
				if (taskSnapshot.isPresent()) {
					TaskSnapshot snapshot = taskSnapshot.orElseThrow();
					if (!projection.taskId().equals(snapshot.task().getTaskId()))
						throw new IllegalStateException(
								"The MCP task manager returned a mismatched task ID.");
					try {
						McpServerRuntimeBridge.requireTaskInputCapabilities(snapshot,
								registration.filter().clientCapabilities());
					} catch (McpProtocolJsonRpcException exception) {
						finishTaskNotificationProjection(projection);
						return;
					}
					boolean terminalSnapshot = terminalTaskStatus(
							snapshot.task().getTaskStatus());
					TaskNotificationDeliveryState deliveryState = terminalSnapshot
							? null : TaskNotificationDeliveryState.from(snapshot);
					McpOutboundChannel.OfferResult result = null;
					try {
						McpJsonRpcMessage.Notification notification = taskNotification(
								registration.protocolProfile(),
								registration.subscriptionId(), snapshot);
						synchronized (lock) {
							boolean deliver = taskNotificationProjectionActiveWhileLocked(
									projection)
									&& subscriptionAuthorizationAllowsDeliveryWhileLocked()
									&& authorizationRevision
											== catalogAuthorizationRevision
									&& requireNonNull(subscriptionRegistration)
											.filter().containsTask(projection.taskId())
									&& projection.state().requestedGeneration
									== projection.generation()
									&& shouldDeliverTaskSnapshot(
											projection.state(), snapshot, deliveryState);
							if (deliver) {
								result = stream.offerMessage(notification);
								if (result == McpOutboundChannel.OfferResult.ACCEPTED) {
									projection.state().terminalDelivered = terminalSnapshot;
									projection.state().lastDelivery = deliveryState;
								}
							}
						}
					} catch (IllegalArgumentException exception) {
						failTaskNotificationProjection(
								StreamTerminationReason.BACKPRESSURE, exception);
						return;
					}
					if (result == McpOutboundChannel.OfferResult.FULL
							|| result == McpOutboundChannel.OfferResult.TOO_LARGE) {
						failTaskNotificationProjection(
								StreamTerminationReason.BACKPRESSURE, null);
						return;
					}
				}
				finishTaskNotificationProjection(projection);
			} catch (Throwable throwable) {
				failTaskNotificationProjection(
						StreamTerminationReason.INTERNAL_ERROR, throwable);
			}
		}

		private boolean taskNotificationProjectionOwnerActiveWhileLocked() {
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required for task notification state.");
			return subscriptionOwned && !canceled && !terminal
					&& !streamAbortOwned && !streamTerminalResponseOwned
					&& subscriptionRegistration != null && responseStream != null;
		}

		private boolean taskNotificationProjectionActiveWhileLocked(
				@NonNull TaskNotificationProjection projection) {
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required for task notification state.");
			return taskNotificationProjectionOwnerActiveWhileLocked()
					&& taskNotificationProjectionQueue.owns(projection);
		}

		private boolean shouldDeliverTaskSnapshot(
				@NonNull TaskNotificationProjectionState state,
				@NonNull TaskSnapshot snapshot,
				@Nullable TaskNotificationDeliveryState deliveryState) {
			if (state.terminalDelivered)
				return false;
			TaskNotificationDeliveryState lastDelivery = state.lastDelivery;
			if (lastDelivery == null)
				return true;
			if (lastDelivery.equals(deliveryState))
				return false;
			if (snapshot.task().getLastUpdatedAt().isBefore(
					lastDelivery.lastUpdatedAt()))
				return false;
			return true;
		}

		private void finishTaskNotificationProjection(
				@NonNull TaskNotificationProjection projection) {
			boolean submitAgain;
			synchronized (lock) {
				submitAgain = taskNotificationProjectionQueue.finish(
						projection,
						taskNotificationProjectionActiveWhileLocked(projection));
			}
			if (submitAgain)
				submitTaskNotificationProjection();
		}

		private void failTaskNotificationProjection(
				@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
			McpRequestSseStream stream;
			synchronized (lock) {
				stream = subscriptionOwned && responseStream != null
						? responseStream : null;
				if (stream == null)
					taskNotificationProjectionQueue.reset();
			}
			if (stream != null) {
				// Fence new events before clearing the owner job. An event racing this
				// failure then coalesces into the still-outstanding job and is discarded
				// by reset instead of submitting work after stream failure owns cleanup.
				try {
					scheduleSubscriptionStreamFailure(stream, reason, cause);
				} finally {
					synchronized (lock) {
						taskNotificationProjectionQueue.reset();
					}
				}
			}
		}

		private void offerSubscriptionEvent(
				@NonNull Event event) {
			requireNonNull(event);
			McpRequestSseStream stream;
			SubscriptionRegistration registration;
			List<Object> coalescingKeys = new ArrayList<>(3);
			synchronized (lock) {
				if (!subscriptionOwned || canceled || terminal
						|| streamAbortOwned || streamTerminalResponseOwned
						|| subscriptionRegistration == null
						|| responseStream == null
						|| !subscriptionAuthorizationAllowsDeliveryWhileLocked())
					return;
				registration = subscriptionRegistration;
				if (event instanceof McpSubscriptionEventSource.Event.ResourcesListChanged) {
					if (!registration.filter().resourcesListChanged())
						return;
					coalescingKeys.add(SubscriptionEventKey.RESOURCES_LIST_CHANGED);
				} else if (event instanceof McpSubscriptionEventSource.Event.ResourceUpdated updated) {
					if (!registration.filter().contains(updated.resourceUri()))
						return;
					coalescingKeys.add(new SubscriptionEventKey(updated.resourceUri()));
				} else if (event instanceof McpSubscriptionEventSource.Event
						.LocalizationCatalogsChanged invalidation) {
					// The invalidation always releases derived localized render
					// state, so a long stream never retains an obsolete
					// translation graph even when its filters accept nothing.
					preRenderedSubscriptionTerminal = null;
					// Tool and prompt catalogs take the caller-visible digest path.
					// This direct path remains only for resource catalogs.
					if (invalidation.resources()
							&& registration.filter().resourcesListChanged())
						coalescingKeys.add(
								SubscriptionEventKey.RESOURCES_LIST_CHANGED);
					if (coalescingKeys.isEmpty())
						return;
				} else {
					return;
				}
				stream = responseStream;
			}

			for (Object coalescingKey : coalescingKeys) {
				McpJsonRpcMessage.Notification notification;
				if (SubscriptionEventKey.TOOLS_LIST_CHANGED.equals(coalescingKey))
					notification = listChangedNotification(
							registration.protocolProfile(),
							registration.subscriptionId(),
							"notifications/tools/list_changed");
				else if (SubscriptionEventKey.PROMPTS_LIST_CHANGED.equals(coalescingKey))
					notification = listChangedNotification(
							registration.protocolProfile(),
							registration.subscriptionId(),
							"notifications/prompts/list_changed");
				else if (SubscriptionEventKey.RESOURCES_LIST_CHANGED.equals(coalescingKey))
					notification = listChangedNotification(
							registration.protocolProfile(),
							registration.subscriptionId(),
							"notifications/resources/list_changed");
				else
					notification = subscriptionNotification(
							registration.protocolProfile(),
							registration.subscriptionId(), event);

				McpOutboundChannel.OfferResult result;
				try {
					synchronized (lock) {
						if (!subscriptionOwned || terminal || canceled
								|| streamAbortOwned || streamTerminalResponseOwned
								|| responseStream != stream
								|| !sameInstance(subscriptionRegistration, registration)
								|| !subscriptionAuthorizationAllowsDeliveryWhileLocked())
							return;
						result = stream.offerCoalescingMessage(notification,
								coalescingKey);
					}
				} catch (IllegalArgumentException exception) {
					scheduleSubscriptionStreamFailure(stream,
							StreamTerminationReason.BACKPRESSURE, exception);
					return;
				} catch (Throwable throwable) {
					scheduleSubscriptionStreamFailure(stream,
							StreamTerminationReason.INTERNAL_ERROR, throwable);
					return;
				}
				if (result == McpOutboundChannel.OfferResult.FULL
						|| result == McpOutboundChannel.OfferResult.TOO_LARGE) {
					scheduleSubscriptionStreamFailure(stream,
							StreamTerminationReason.BACKPRESSURE, null);
					return;
				}
			}
		}

		private void scheduleSubscriptionStreamFailure(
				@NonNull McpRequestSseStream stream,
				@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
			scheduleSubscriptionStreamFailure(stream, reason, null, cause);
		}

		private void scheduleSubscriptionStreamFailure(
				@NonNull McpRequestSseStream stream,
				@NonNull StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason exactReason,
				@Nullable Throwable cause) {
			requireNonNull(stream);
			requireNonNull(reason);
			boolean scheduled = false;
			SubscriptionAuthorizationCheck authorizationCheck = null;
			CatalogProjectionCheck catalogCheck = null;
			synchronized (lock) {
				if (responseStream != stream || terminal || canceled
						|| streamAbortOwned || streamTerminalResponseOwned)
					return;
				streamAbortOwned = true;
				subscriptionOwned = false;
				taskNotificationProjectionQueue.reset();
				authorizationCheck = subscriptionAuthorizationCheck;
				subscriptionAuthorizationCheck = null;
				catalogCheck = catalogProjectionCheck;
				catalogProjectionCheck = null;
				catalogProjectionQueue.reset();
				plannedSubscriptionCloseExactReason = exactReason;
				pendingSubscriptionStreamFailure = new SubscriptionStreamFailure(
						stream, reason, cause);
				scheduled = true;
			}
			if (authorizationCheck != null)
				authorizationCheck.cancellation().cancel(reason);
			if (catalogCheck != null)
				catalogCheck.cancellation().cancel(reason);
			if (scheduled)
				application.signalDeadlineTimer();
		}

		private void completeSubscription(
				@NonNull StreamTerminationReason closeReason) {
			requireNonNull(closeReason);
			McpRequestSseStream stream;
			SubscriptionRegistration registration = null;
			McpJsonRpcMessage.ResultResponse preRendered = null;
			SubscriptionStreamFailure pendingFailure;
			SubscriptionAuthorizationCheck authorizationCheck = null;
			CatalogProjectionCheck catalogCheck = null;
			boolean cancelPendingReservation;
			synchronized (lock) {
				cancelPendingReservation = subscriptionCapReservation != null
						&& subscriptionRegistration == null;
				if (cancelPendingReservation) {
					stream = null;
					pendingFailure = null;
				} else {
					pendingFailure = pendingSubscriptionStreamFailure;
					if (pendingFailure != null) {
						pendingSubscriptionStreamFailure = null;
						stream = pendingFailure.stream();
					} else {
						if (!subscriptionOwned || canceled || terminal
								|| streamAbortOwned || streamTerminalResponseOwned
								|| subscriptionRegistration == null
								|| responseStream == null)
							return;
						subscriptionOwned = false;
						taskNotificationProjectionQueue.reset();
						authorizationCheck = subscriptionAuthorizationCheck;
						subscriptionAuthorizationCheck = null;
						catalogCheck = catalogProjectionCheck;
						catalogProjectionCheck = null;
						catalogProjectionQueue.reset();
						plannedSubscriptionCloseReason = closeReason;
						plannedSubscriptionCloseExactReason = null;
						streamTerminalResponseOwned = true;
						stream = responseStream;
						registration = subscriptionRegistration;
						preRendered = preRenderedSubscriptionTerminal;
						preRenderedSubscriptionTerminal = null;
					}
				}
			}
			if (authorizationCheck != null)
				authorizationCheck.cancellation().cancel(closeReason);
			if (catalogCheck != null)
				catalogCheck.cancellation().cancel(closeReason);
			if (cancelPendingReservation) {
				cancel(closeReason, null);
				return;
			}
			McpRequestSseStream resolvedStream = requireNonNull(stream);
			if (pendingFailure != null) {
				resolvedStream.fail(pendingFailure.reason(), pendingFailure.cause());
				return;
			}
			planRequestObservation(new RequestObservationResult(
					McpRequestOutcome.COMPLETE, null, List.of()));
			SubscriptionRegistration resolvedRegistration = requireNonNull(registration);
			try {
				if (!resolvedStream.completeMessage(preRendered != null ? preRendered
						: subscriptionTerminalResponse(
								resolvedRegistration.protocolProfile(),
								resolvedRegistration.subscriptionId(),
								resolvedRegistration.endpoint())))
					resolvedStream.fail(StreamTerminationReason.INTERNAL_ERROR, null);
			} catch (Throwable throwable) {
				resolvedStream.fail(StreamTerminationReason.INTERNAL_ERROR, throwable);
			}
		}

		private boolean hasSubscriptionRegistration() {
			synchronized (lock) {
				return subscriptionRegistration != null
						|| subscriptionCapReservation != null || (legacyGet != null && legacyGet.headOwned);
			}
		}

		/**
		 * Begins graceful transport drain for this request. Indefinite
		 * subscriptions receive their terminal result promptly; already-admitted
		 * finite requests retain their response path until normal completion or the
		 * force boundary.
		 */
		private void quiesceTransport() {
			LegacyGetControl get; synchronized (lock) { get = legacyGet; }
			if (get != null) { get.close(StreamTerminationReason.SERVER_STOPPING, McpStreamTerminationReason.SERVER_STOPPING, null); return; }
			if (hasSubscriptionRegistration())
				completeSubscription(StreamTerminationReason.SERVER_STOPPING);
		}

		/**
		 * Gracefully drains one off-network request with the same finite-request
		 * semantics as the network runtime.
		 */
		private void quiesceSimulationTransport() {
			LegacyGetControl get; synchronized (lock) { get = legacyGet; }
			if (get != null) { get.close(StreamTerminationReason.SERVER_STOPPING, McpStreamTerminationReason.SERVER_STOPPING, null); return; }
			if (hasSubscriptionRegistration())
				completeSubscription(StreamTerminationReason.SERVER_STOPPING);
		}

		private boolean applicationEntryAllowed() {
			synchronized (lock) {
				return applicationOwned && !canceled && !terminal;
			}
		}

		private boolean startObservation(@NonNull McpRuntimeObservationSink sink,
				@NonNull McpRuntimeRequestInput input) {
			requireNonNull(sink);
			requireNonNull(input);
			synchronized (lock) {
				if (terminal || canceled)
					return false;
				if (requestObservationReserved)
					throw new IllegalStateException(
							"MCP request observation cannot start twice.");
				requestObservationReserved = true;
			}
			long startedAtNanos = applicationClock.nanoTime();
			McpRuntimeRequestObservation observation;
			Optional<@NonNull McpRequestContext> publicRequestContext;
			try {
				observation = requireNonNull(sink.didStartRequest(input),
						"The MCP runtime observation sink returned null.");
				publicRequestContext = requireNonNull(observation.publicContext(),
						"The MCP runtime request observation returned a null public context.");
			} catch (Throwable ignored) {
				observation = McpRuntimeRequestObservation.disabledInstance();
				publicRequestContext = Optional.empty();
			}

			synchronized (lock) {
				requestObservation = observation;
				this.publicRequestContext = publicRequestContext;
				requestObservationStartedAtNanos = startedAtNanos;
			}
			drainRequestObservation();
			return true;
		}

		private void markTerminalWhileLocked() {
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required to mark a terminal request.");
			terminal = true;
			if (legacyCall != null) legacyCall.logicalComplete();
			catalogPolicyEvaluationOwned = false;
			catalogPolicyDeadlineResponseOwned = false;
			if (!canceled && catalogAccessCancellation.isActive())
				catalogAccessCancellation.complete();
			taskNotificationProjectionQueue.reset();
			catalogProjectionQueue.reset();
			if (!legacyHttpControl && !requestObservationReserved && !requestRejectionRecorded) {
				requestRejectionRecorded = true;
				McpHttpServerRuntime.this.applicationExecutionObserver
						.recordRequestRejected();
			}
		}

		@NonNull
		private Optional<@NonNull McpRequestContext> publicRequestContext() {
			synchronized (lock) {
				return publicRequestContext;
			}
		}

		/**
		 * Whether this request is terminal, canceled, or past its absolute
		 * deadline. The deadline is read directly so provider callbacks stop at
		 * actual expiry rather than at the next asynchronous deadline sweep.
		 */
		private boolean isTerminalCanceledOrPastDeadline() {
			synchronized (lock) {
				return terminal || canceled
						|| applicationClock.nanoTime() - deadlineNanos >= 0;
			}
		}

		/**
		 * Cancellation-only invocation feature used while the caller-aware catalog
		 * session is opened on the bounded application executor.  It deliberately
		 * shares the request's terminal signal without acquiring application-handler
		 * ownership first.
		 */
		@NonNull
		private CancelationToken catalogAccessCancelationToken() {
			return new CancelationToken() {
				@Override
				@NonNull
				public Boolean isCanceled() {
					return catalogAccessCancellation
							.isCancellationRequested();
				}

				@Override
				@NonNull
				public Optional<@NonNull StreamTerminationReason>
						getCancelationReason() {
					return catalogAccessCancellation.reason();
				}

				@Override
				@NonNull
				public Optional<@NonNull Throwable> getCancelationCause() {
					return Optional.empty();
				}

				@Override
				@NonNull
				public CallbackRegistration onCancel(@NonNull Runnable callback) {
					return catalogAccessCancellation.onCancel(
							requireNonNull(callback));
				}
			};
		}

		/**
		 * Prevents the protocol deadline timer from replacing a bounded catalog
		 * policy timeout with the transport's bodyless fallback while the dispatcher
		 * still owns the policy outcome.
		 */
		private boolean beginCatalogPolicyEvaluation() {
			synchronized (lock) {
				if (canceled || terminal || applicationOwned
						|| catalogPolicyEvaluationOwned
						|| catalogPolicyDeadlineResponseOwned)
					return false;
				catalogPolicyEvaluationOwned = true;
				return true;
			}
		}

		private void finishCatalogPolicyEvaluation() {
			synchronized (lock) {
				catalogPolicyEvaluationOwned = false;
			}
		}

		/**
		 * Transfers the winning policy deadline into protocol-response ownership.
		 * A disconnect or shutdown may still make the request unwritable before the
		 * response is handed off, but the ordinary protocol timer may no longer
		 * replace the correlated policy response.
		 */
		private boolean reserveCatalogPolicyDeadlineResponse() {
			boolean reserved;
			McpHttpServerRuntime.this.applicationExecutionObserver
					.beginRequestTransitionDeferral();
			try {
				synchronized (lock) {
					if (canceled || terminal || applicationOwned
							|| !catalogPolicyEvaluationOwned)
						return false;
					reserved = application.reserveProtocolOperationIfRunning(() -> {
						catalogPolicyEvaluationOwned = false;
						catalogPolicyDeadlineResponseOwned = true;
						return true;
					}).orElse(false);
				}
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}
			if (reserved) {
				application.recordProtocolDeadlineExpiration();
				catalogAccessCancellation.cancel(
						StreamTerminationReason.RESPONSE_TIMEOUT);
			}
			return reserved;
		}

		private void finishRequestObservation(@NonNull McpRequestOutcome outcome,
				@Nullable McpJsonRpcError error,
				@NonNull List<@NonNull Throwable> throwables) {
			requireNonNull(outcome);
			requireNonNull(throwables);
			boolean firstTerminal = false;
			synchronized (lock) {
				if (requestObservationTerminal == null) {
					requestObservationTerminal = new RequestObservationTerminal(
							outcome, error, applicationClock.nanoTime(), throwables);
					firstTerminal = true;
				}
			}
			if (firstTerminal && simulation != null)
				simulation.didFinishRequest(outcome, throwables);
			drainRequestObservation();
		}

		private void planRequestObservation(
				@NonNull RequestObservationResult result) {
			requireNonNull(result);
			synchronized (lock) {
				if (plannedRequestObservationResult == null)
					plannedRequestObservationResult = result;
			}
		}

		private void replacePlannedRequestObservation(
				@NonNull RequestObservationResult result) {
			requireNonNull(result);
			synchronized (lock) {
				if (requestObservationTerminal == null)
					plannedRequestObservationResult = result;
			}
		}

		@NonNull
		private RequestObservationResult plannedRequestObservationOr(
				@NonNull RequestObservationResult fallback) {
			requireNonNull(fallback);
			synchronized (lock) {
				return plannedRequestObservationResult == null ? fallback
						: plannedRequestObservationResult;
			}
		}

		private void finishPlannedRequestObservation(
				@NonNull RequestObservationResult fallback) {
			RequestObservationResult result = plannedRequestObservationOr(fallback);
			finishRequestObservation(result.outcome(), result.error(),
					result.throwables());
		}

		private void drainRequestObservation() {
			if (Thread.holdsLock(lock))
				return;

			McpRuntimeRequestObservation observation;
			RequestObservationTerminal terminal;
			long startedAtNanos;
			synchronized (lock) {
				if (requestObservationDelivered || requestObservation == null
						|| requestObservationTerminal == null)
					return;
				requestObservationDelivered = true;
				observation = requestObservation;
				terminal = requestObservationTerminal;
				startedAtNanos = requestObservationStartedAtNanos;
			}

			long elapsedNanos = terminal.finishedAtNanos() - startedAtNanos;
			Duration duration = Duration.ofNanos(Math.max(0L, elapsedNanos));
			try {
				observation.didFinish(terminal.outcome(), terminal.error(), duration,
						terminal.throwables());
			} catch (Throwable ignored) {
				// Observation must never alter protocol or transport behavior.
			}
		}

		private void markStreamOpenedInOrder(boolean subscription) {
			StreamObservationOpenTransition transition;
			synchronized (lock) {
				transition = reserveStreamObservationOpenWhileLocked(subscription);
			}
			recordStreamObservationOpenInOrder(transition);
		}

		@NonNull
		private StreamObservationOpenTransition
				reserveStreamObservationOpenWhileLocked(boolean subscription) {
			if (!Thread.holdsLock(lock))
				throw new IllegalStateException(
						"The request-control lock is required to reserve a stream-open observation.");
			McpRuntimeRequestObservation observation = null;
			boolean openRequestStream = false;
			boolean openSubscription = false;
			long nowNanos = applicationClock.nanoTime();
			if (!terminal && !canceled) {
				observation = requestObservation;
				if (!streamObservationOpened) {
					streamObservationOpened = true;
					streamOpenedAtNanos = nowNanos;
					openRequestStream = true;
				}
				if (subscription && !subscriptionObservationOpened) {
					subscriptionObservationOpened = true;
					subscriptionOpenedAtNanos = nowNanos;
					openSubscription = true;
				}
			}
			return new StreamObservationOpenTransition(observation,
					openRequestStream, openSubscription);
		}

		private void recordStreamObservationOpenInOrder(
				@NonNull StreamObservationOpenTransition transition) {
			StreamObservationOpenTransition requiredTransition =
					requireNonNull(transition);
			recordStreamDiagnosticsTransition(
					requiredTransition.requestStreamOpened() ? 1 : 0,
					requiredTransition.subscriptionOpened() ? 1 : 0);
			@Nullable McpRuntimeRequestObservation observation =
					requiredTransition.observation();
			if (observation == null)
				return;
			if (requiredTransition.requestStreamOpened()) {
				try {
					observation.didOpenRequestStream();
				} catch (Throwable ignored) {
					// Observation failures must not alter stream lifecycle behavior.
				}
			}
			if (requiredTransition.subscriptionOpened()) {
				try {
					observation.didOpenSubscription();
				} catch (Throwable ignored) {
					// Observation failures must not alter subscription lifecycle behavior.
				}
			}
		}

		private void markStreamClosed(@NonNull StreamTerminationReason reason) {
			markStreamClosed(reason, null);
		}

		private void markStreamClosed(@NonNull StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason exactReason) {
			requireNonNull(reason);
			try {
				synchronized (streamObservationTransitionLock) {
					markStreamClosedInOrder(reason, exactReason);
				}
			} finally {
				drainApplicationExecutionObservation();
			}
		}

		private void markStreamClosedInOrder(
				@NonNull StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason exactReason) {
			McpRuntimeRequestObservation observation;
			Duration streamDuration = null;
			Duration subscriptionDuration = null;
			long nowNanos = applicationClock.nanoTime();
			synchronized (lock) {
				observation = requestObservation;
				if (streamObservationOpened && !streamObservationClosed) {
					streamObservationClosed = true;
					streamDuration = Duration.ofNanos(Math.max(0L,
							nowNanos - streamOpenedAtNanos));
				}
				if (subscriptionObservationOpened
						&& !subscriptionObservationClosed) {
					subscriptionObservationClosed = true;
					subscriptionDuration = Duration.ofNanos(Math.max(0L,
							nowNanos - subscriptionOpenedAtNanos));
				}
			}
			recordStreamDiagnosticsTransition(streamDuration == null ? 0 : -1,
					subscriptionDuration == null ? 0 : -1);
			if (observation == null)
				return;
			if (streamDuration != null) {
				try {
					observation.didCloseRequestStream(reason, exactReason,
							streamDuration);
				} catch (Throwable ignored) {
					// Observation failures must not alter stream cleanup behavior.
				}
			}
			if (subscriptionDuration != null) {
				try {
					observation.didCloseSubscription(reason, exactReason,
							subscriptionDuration);
				} catch (Throwable ignored) {
					// Observation failures must not alter subscription cleanup behavior.
				}
			}
		}

		private void markKeepAliveEmitted() {
			McpRuntimeRequestObservation observation;
			synchronized (lock) {
				observation = requestObservation;
			}
			if (observation == null)
				return;
			try {
				observation.didEmitKeepAlive();
			} catch (Throwable ignored) {
				// Observation failures must not alter keep-alive delivery behavior.
			}
		}

		private void drainApplicationExecutionObservation() {
			try {
				McpHttpServerRuntime.this.applicationExecutionObserver.drain();
			} catch (Throwable ignored) {
				// Observation must never alter stream lifecycle behavior.
			}
		}

		private boolean protocolProcessingAllowed() {
			ProtocolProcessingReservation reservation;
			McpHttpServerRuntime.this.applicationExecutionObserver
					.beginRequestTransitionDeferral();
			try {
				synchronized (lock) {
					if (canceled || terminal || applicationOwned)
						return false;
					reservation = application.reserveProtocolOperationIfRunning(() -> {
						if (applicationClock.nanoTime() - deadlineNanos >= 0L)
							return new ProtocolProcessingReservation(
									false, detachProtocolDeadline(true));
						return new ProtocolProcessingReservation(true, null);
					}).orElse(null);
				}
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}

			if (reservation == null)
				return false;
			ProtocolDeadlineExpiration deadlineExpiration = reservation.deadlineExpiration();
			if (deadlineExpiration != null)
				finishProtocolDeadline(deadlineExpiration);
			return reservation.allowed();
		}

		private boolean identifyRequestExchange() {
			synchronized (lock) {
				if (canceled || terminal)
					return false;
				if (identifiedRequestExchange)
					throw new IllegalStateException(
							"The request exchange is already identified.");
				identifiedRequestExchange = true;
				activeIdentifiedRequestExchangeCount.incrementAndGet();
				return true;
			}
		}

		private void submit(@NonNull FutureTask<@Nullable Void> task) {
			requireNonNull(task);
			ProtocolSubmission submission;
			McpHttpServerRuntime.this.applicationExecutionObserver
					.beginRequestTransitionDeferral();
			try {
				synchronized (lock) {
					if (protocolTask != null)
						throw new IllegalStateException(
								"The protocol task is already bound.");
					submission = application.reserveProtocolOperationIfRunning(() -> {
						protocolTask = task;
						requestControls.put(request, this);
						application.signalDeadlineTimer();
						McpApplicationExecutionObserver.PendingMetricRecord
								acceptedRecord = McpHttpServerRuntime.this
										.applicationExecutionObserver.recordRequestAccepted();
						try {
							processor.execute(task);
							return new ProtocolSubmission(null);
						} catch (RejectedExecutionException exception) {
							McpHttpServerRuntime.this.applicationExecutionObserver
									.discardPendingMetric(acceptedRecord);
							protocolTask = null;
							markTerminalWhileLocked();
							return new ProtocolSubmission(takeResponseCallback());
						} catch (RuntimeException | Error failure) {
							McpHttpServerRuntime.this.applicationExecutionObserver
									.discardPendingMetric(acceptedRecord);
							protocolTask = null;
							canceled = true;
							markTerminalWhileLocked();
							responseCallback = null;
							task.cancel(true);
							processor.remove(task);
							throw failure;
						}
					}).orElse(null);
					if (submission == null) {
						canceled = true;
						markTerminalWhileLocked();
						submission = new ProtocolSubmission(takeResponseCallback());
					}
				}
			} catch (RuntimeException | Error failure) {
				task.cancel(false);
				finishTransportLifecycle();
				throw failure;
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}

			Consumer<MicrohttpResponse> rejectedCallback =
					submission.rejectedCallback();
			if (rejectedCallback != null) {
				// No processor owns this task. Cancellation completes the protocol-work
				// half of the common lifecycle lease before response delivery begins.
				task.cancel(false);
				boolean trackBody = tracksLifecycleResponseBody();
				if (!trackBody)
					finishTransportLifecycle();
				MicrohttpResponse terminalResponse = decorateResponse(emptyResponse(
						503, "Service Unavailable", List.of()));
				RequestObservationResult fallback =
						requestObservationResult(terminalResponse);
				MicrohttpResponse response = withRequestObservationTermination(
						terminalResponse, fallback);
				Throwable deliveryFailure = deliverResponse(
						rejectedCallback, response);
				if (deliveryFailure != null && trackBody)
					finishTransportLifecycle();
				if (simulation != null)
					finishDeliveredSimulationResponse(
							fallback, deliveryFailure);
				else if (deliveryFailure != null)
					observeTransportFailure(TransportFailureReason.RESPONSE_READY_ERROR,
							() -> {});
			}
		}

		private boolean updateDeadlineResponseHeaders(
				@NonNull List<@NonNull Header> headers) {
			requireNonNull(headers);
			synchronized (lock) {
				if (canceled || terminal)
					return false;
				deadlineResponseHeaders = List.copyOf(headers);
				return true;
			}
		}

		private boolean handoff(@NonNull McpApplicationExecution application,
				@NonNull Runnable registration) {
			requireNonNull(application);
			requireNonNull(registration);
			if (this.application != application)
				throw new IllegalArgumentException(
						"Request control belongs to another application generation.");

			ProtocolProcessingReservation reservation;
			synchronized (lock) {
				if (canceled || terminal)
					return false;

				reservation = application.reserveProtocolOperationIfRunning(() -> {
					if (applicationClock.nanoTime() - deadlineNanos >= 0L)
						return new ProtocolProcessingReservation(
								false, detachProtocolDeadline(true));

					reserveApplicationLifecycleWorkWhileLocked();
					applicationOwned = true;
					return new ProtocolProcessingReservation(true, null);
				}).orElse(null);
			}

			if (reservation == null)
				return false;
			ProtocolDeadlineExpiration deadlineExpiration = reservation.deadlineExpiration();
			if (deadlineExpiration != null) {
				finishProtocolDeadline(deadlineExpiration);
				return false;
			}

			try {
				registration.run();
			} catch (RuntimeException | Error failure) {
				synchronized (lock) {
					if (!terminal)
						applicationOwned = false;
				}
				applicationLifecycleWorkTerminated();
				legacyApplicationPhysicalFinished();
				throw failure;
			} finally {
				StreamTerminationReason pendingCancellationReason;
				Throwable pendingCancellationCause;
				synchronized (lock) {
					if (applicationOwned)
						protocolTask = null;
					pendingCancellationReason = canceled
							? cancellationReason : null;
					pendingCancellationCause = cancellationCause;
				}
				if (pendingCancellationReason != null) {
					if (legacySessionTerminationCause != null)
						application.tryReserveCancellation(request, pendingCancellationReason).complete().run();
					else application.cancel(request, pendingCancellationReason, pendingCancellationCause);
				}
			}
			drainRequestObservation();
			return true;
		}

		private void completeProtocol(@Nullable MicrohttpResponse response) {
			if (response != null) noteLegacyHttpResponse(response);
			ProtocolResponseReservation reservation;
			StreamTerminationReason applicationStopReason = null;
			McpHttpServerRuntime.this.applicationExecutionObserver
					.beginRequestTransitionDeferral();
			try {
				synchronized (lock) {
					if (applicationOwned || terminal)
						return;
					if (subscriptionRegistration != null
							|| (legacyGet != null && legacyGet.headOwned) || streamTerminalResponseOwned) {
						// An inline application handler may reserve its stream terminal
						// before the protocol handoff returns. Its transport callback still
						// owns terminal cleanup and must not be preempted here.
						protocolTask = null;
						return;
					}

					reservation = application.reserveProtocolOperationIfRunning(() -> {
						boolean policyDeadlineResponse =
								catalogPolicyDeadlineResponseOwned;
						if (!policyDeadlineResponse && !canceled && response != null
								&& applicationClock.nanoTime() - deadlineNanos >= 0L)
							return new ProtocolResponseReservation(
									null, null, detachProtocolDeadline(false));

						protocolTask = null;
						if (policyDeadlineResponse) {
							canceled = true;
							cancellationReason =
									StreamTerminationReason.RESPONSE_TIMEOUT;
							cancellationCause = null;
						}
						markTerminalWhileLocked();
						Consumer<MicrohttpResponse> callback = response != null
								&& (!canceled || policyDeadlineResponse)
										? takeResponseCallback() : null;
						responseCallback = null;
						releaseIdentifiedRequestExchange();
						return new ProtocolResponseReservation(callback, response, null);
					}).orElse(null);
					if (reservation == null) {
						protocolTask = null;
						canceled = true;
						applicationStopReason = application.stoppingReason();
						cancellationReason = applicationStopReason;
						cancellationCause = null;
						markTerminalWhileLocked();
						responseCallback = null;
						releaseIdentifiedRequestExchange();
					}
				}
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}

			if (reservation == null) {
				catalogAccessCancellation.cancel(
						requireNonNull(applicationStopReason));
				finishTransportLifecycle();
				finishRequestObservation(McpRequestOutcome.CANCELED, null, List.of());
				return;
			}
			ProtocolDeadlineExpiration deadlineExpiration = reservation.deadlineExpiration();
			if (deadlineExpiration != null) {
				finishProtocolDeadline(deadlineExpiration);
				return;
			}
			Consumer<MicrohttpResponse> callback = reservation.responseCallback();
			if (callback != null) {
				boolean trackBody = tracksLifecycleResponseBody();
				if (!trackBody)
					finishTransportLifecycle();
				MicrohttpResponse terminalResponse =
						requireNonNull(reservation.response());
				RequestObservationResult fallback =
						requestObservationResult(terminalResponse);
				MicrohttpResponse observedResponse =
						withRequestObservationTermination(terminalResponse, fallback);
				Throwable deliveryFailure = deliverResponse(
						callback, observedResponse);
				if (deliveryFailure != null && trackBody)
					finishTransportLifecycle();
				if (simulation != null) {
					finishDeliveredSimulationResponse(fallback, deliveryFailure);
				} else if (deliveryFailure != null) {
					Throwable requiredFailure = deliveryFailure;
					observeTransportFailure(
							TransportFailureReason.RESPONSE_READY_ERROR,
							() -> finishRequestObservation(
									McpRequestOutcome.WRITE_FAILED, null,
									List.of(requiredFailure)));
				}
			} else
				finishTransportLifecycle();
		}

		private boolean isNotificationDeliveryActive() {
			synchronized (lock) {
				return applicationOwned && !legacyResponseDeliveryDetached
						&& !streamAbortOwned && !canceled && !terminal;
			}
		}

		private boolean writeApplicationNotification(
				McpJsonRpcMessage.@NonNull Notification notification,
				@NonNull List<@NonNull Header> additionalHeaders)
				throws InterruptedException {
			requireNonNull(notification);
			requireNonNull(additionalHeaders);
			McpRequestSseStream stream;
			Consumer<MicrohttpResponse> callback = null;
			boolean firstMessage = false;

			synchronized (lock) {
				if (!applicationOwned || legacyResponseDeliveryDetached
						|| streamAbortOwned || canceled || terminal)
					return false;
				stream = responseStream;
			}

			if (stream != null) {
				return stream.enqueueMessage(notification);
			}

			StreamObservationOpenTransition openTransition = null;
			try {
				synchronized (streamObservationTransitionLock) {
					synchronized (lock) {
						if (!applicationOwned || legacyResponseDeliveryDetached
								|| streamAbortOwned || canceled
								|| terminal)
							return false;
						stream = responseStream;
						if (stream == null) {
							stream = newResponseStream();
							McpOutboundChannel.OfferResult result =
									stream.offerMessage(notification);
							if (result != McpOutboundChannel.OfferResult.ACCEPTED)
								throw new IllegalStateException(
										"A new MCP response stream could not accept its first message.");

							responseStream = stream;
							firstMessage = true;
							nextKeepAliveNanos = applicationClock.nanoTime()
									+ transportConfiguration.keepAliveInterval().toNanos();
							openTransition =
									reserveStreamObservationOpenWhileLocked(false);
						}
					}
					if (firstMessage)
						recordStreamObservationOpenInOrder(
								requireNonNull(openTransition));
				}
			} finally {
				if (firstMessage)
					drainApplicationExecutionObservation();
			}

			if (firstMessage) {
				synchronized (lock) {
					if (canceled || terminal)
						return false;
					// Reserve response delivery immediately before offering HTTP SSE.
					callback = takeResponseCallback();
					responseStreamCommitted = true;
				}
				markLifecycleTransportStarted();
				try {
					requireNonNull(callback).accept(stream.response(additionalHeaders));
				} catch (Throwable throwable) {
					McpRequestSseStream failedStream = stream;
					observeTransportFailure(
							TransportFailureReason.RESPONSE_READY_ERROR,
							() -> failedStream.fail(
									StreamTerminationReason.WRITE_FAILED, throwable));
					return false;
				}
				return true;
			}

			return stream.enqueueMessage(notification);
		}

		private boolean writeApplicationResponse(
				@NonNull McpApplicationResponse response,
				@NonNull McpJsonRpcId requestId,
				@NonNull List<@NonNull Header> additionalHeaders) {
			requireNonNull(response);
			requireNonNull(requestId);
			requireNonNull(additionalHeaders);
			Consumer<MicrohttpResponse> callback = null;
			McpRequestSseStream stream;

			synchronized (lock) {
				if (!applicationOwned || streamAbortOwned || canceled || terminal)
					return false;
				if (legacyResponseDeliveryDetached) {
					applicationOwned = false;
					protocolTask = null;
					markTerminalWhileLocked();
					releaseIdentifiedRequestExchange();
					return false;
				}

				applicationOwned = false;
				protocolTask = null;
				if (legacyCall != null) legacyCall.logicalComplete();
				stream = responseStream;
				if (stream == null) {
					markTerminalWhileLocked();
					callback = takeResponseCallback();
					releaseIdentifiedRequestExchange();
				} else {
					streamTerminalResponseOwned = true;
					streamTerminalDeadlineResponseOwned = response.outcome()
							== McpRequestOutcome.DEADLINE_EXCEEDED;
				}
			}
			McpApplicationResponse effectiveResponse =
					omitCompatibilityMirrorIfNecessary(response);

			if (stream == null) {
				ApplicationResponseRendering rendering = renderApplicationResponse(
						protocolProfile(), effectiveResponse, requestId, additionalHeaders,
						publicRequestContext().orElse(null));
				planRequestObservation(rendering.observationResult());
				boolean trackBody = tracksLifecycleResponseBody();
				if (!trackBody)
					finishTransportLifecycle();
				MicrohttpResponse observedResponse = withRequestObservationTermination(
						normalizeLegacySessionRpcResponse(rendering.response()), rendering.observationResult());
				Throwable deliveryFailure = deliverResponse(requireNonNull(callback),
						observedResponse);
				if (deliveryFailure != null && trackBody)
					finishTransportLifecycle();
				if (simulation != null) {
					finishDeliveredSimulationResponse(
							rendering.observationResult(), deliveryFailure);
				} else if (deliveryFailure != null) {
					Throwable requiredFailure = deliveryFailure;
					observeTransportFailure(
							TransportFailureReason.RESPONSE_READY_ERROR,
							() -> finishRequestObservation(
									McpRequestOutcome.WRITE_FAILED, null,
									List.of(requiredFailure)));
				}
				return true;
			}
			planRequestObservation(requestObservationResult(effectiveResponse));

			if (effectiveResponse.message().isEmpty())
				return stream.fail(StreamTerminationReason.RESPONSE_TIMEOUT, null);

			McpJsonRpcMessage message = effectiveResponse.message().orElseThrow();
			McpApplicationExecutionObserver.@Nullable PendingMetricRecord
					pendingError = null;
			McpHttpServerRuntime.this.applicationExecutionObserver
					.beginRequestTransitionDeferral();
			try {
				try {
					if (message
							instanceof McpJsonRpcMessage.ErrorResponse errorResponse) {
						// Validate the exact immutable message before staging its event so
						// synchronous stream termination cannot overtake ProtocolError.
						McpRequestSseStream.encodeMessage(envelopeCodec, jsonCodec,
								protocolProfile(), message);
						pendingError = recordProducedProtocolError(
								errorResponse.error().code(),
								publicRequestContext().orElse(null));
					}
					boolean completed = stream.completeMessage(message);
					if (!completed && pendingError != null) {
						McpHttpServerRuntime.this.applicationExecutionObserver
								.discardPendingMetric(pendingError);
					}
					return completed;
				} catch (Throwable throwable) {
					if (pendingError != null) {
						McpHttpServerRuntime.this.applicationExecutionObserver
								.discardPendingMetric(pendingError);
					}
					return stream.fail(StreamTerminationReason.INTERNAL_ERROR, throwable);
				}
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}
		}

		@NonNull
		private McpApplicationResponse omitCompatibilityMirrorIfNecessary(
				@NonNull McpApplicationResponse response) {
			Optional<McpApplicationResponse> fallback = requireNonNull(response)
					.withoutCompatibilityMirror();
			if (fallback.isEmpty() || response.message().isEmpty())
				return response;
			try {
				McpRequestSseStream.encodeMessage(envelopeCodec, jsonCodec,
						protocolProfile(), response.message().orElseThrow());
				return response;
			} catch (IllegalArgumentException exception) {
				return fallback.orElseThrow();
			}
		}

		@NonNull
		private McpRequestSseStream newResponseStream() {
			if (simulation != null) {
				McpRequestSseStream.Listener listener = this::streamTerminated;
				return new McpRequestSseStream(envelopeCodec, jsonCodec, protocolProfile(),
						simulation.openChannel(listener));
			}
			return new McpRequestSseStream(
					transportConfiguration.streamQueueCapacity(),
					jsonLimits,
					envelopeCodec,
					jsonCodec,
					protocolProfile(),
					applicationClock,
					new McpOutboundChannel.Listener() {
						@Override
						public void didWrite(long byteCount, long timestampNanos) {
							// The channel owns its write-idle timestamp.
						}

						@Override
						public void didApplyBackpressure() {
							// Phase 3 records the bound through deterministic tests;
							// public metrics arrive with the observability slice.
						}

						@Override
						public void didTerminate(@NonNull StreamTerminationReason reason,
								@Nullable Throwable cause) {
							streamTerminated(reason, null, cause);
						}
					});
		}

		@NonNull
		private Optional<McpOutboundChannel.@NonNull Snapshot> streamSnapshot() {
			synchronized (lock) {
				return responseStream == null ? Optional.empty()
						: responseStream.snapshot();
			}
		}

		private void cancel(@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
			McpHttpServerRuntime.this.applicationExecutionObserver
					.beginRequestTransitionDeferral();
			try {
				cancelWhileMetricsDeferred(reason, cause);
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}
		}

		private boolean cancelFromSimulation(
				@NonNull StreamTerminationReason reason) {
			McpHttpServerRuntime.this.applicationExecutionObserver
					.beginRequestTransitionDeferral();
			try {
				return cancelWhileMetricsDeferred(reason, null);
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}
		}

		private boolean cancelWhileMetricsDeferred(
				@NonNull StreamTerminationReason reason,
				@Nullable Throwable cause) {
			LegacyGetControl get; McpApplicationExecution.BoundedPolicyCancellation policy;
			synchronized (lock) { get = legacyGet; policy = legacyHttpCancellation; }
			if (policy != null && policy.isActive()) policy.cancel(reason);
			if (get != null) {
				get.close(reason, McpServerRuntimeBridge.toPublicTerminationReason(reason), cause);
				if (get.headOwned) return true;
			}
			if (detachLegacyResponseDelivery(reason, cause))
				return true;
			FutureTask<Void> task;
			McpRequestSseStream stream;
			SubscriptionRegistration subscription;
			SubscriptionRegistration capReservation;
			SubscriptionAuthorizationCheck authorizationCheck;
			CatalogProjectionCheck catalogCheck;
			Runnable applicationCancellation = null;
			boolean completedStream;
			synchronized (lock) {
				if (terminal)
					return false;

				canceled = true;
				cancellationReason = reason;
				cancellationCause = cause;
				if (simulation != null
						&& reason == StreamTerminationReason.CLIENT_DISCONNECTED)
					simulation.reserveRuntimeReason(
							McpStreamTerminationReason.CLIENT_DISCONNECTED);
				task = protocolTask;
				protocolTask = null;
				stream = responseStream;
				subscription = subscriptionRegistration;
				subscriptionRegistration = null;
				capReservation = subscriptionCapReservation;
				subscriptionCapReservation = null;
				authorizationCheck = subscriptionAuthorizationCheck;
				subscriptionAuthorizationCheck = null;
				catalogCheck = catalogProjectionCheck;
				catalogProjectionCheck = null;
				pendingSubscriptionStreamFailure = null;
				plannedSubscriptionCloseExactReason = null;
				subscriptionOwned = false;
				preRenderedSubscriptionTerminal = null;
				completedStream = stream != null && stream.isTerminalWritten();
				if (applicationOwned) {
					// Reserve the application terminal while this ownership lock still
					// excludes a handler response. Application onCancel callbacks and the
					// reentrant terminal cleanup run only after this lock is released.
					applicationCancellation = application.reserveCancellation(
							request, requireNonNull(reason), cause);
					applicationOwned = false;
					markTerminalWhileLocked();
					responseCallback = null;
					releaseIdentifiedRequestExchange();
				} else {
					markTerminalWhileLocked();
					responseCallback = null;
					releaseIdentifiedRequestExchange();
				}
			}

			catalogAccessCancellation.cancel(reason);
			if (authorizationCheck != null)
				authorizationCheck.cancellation().cancel(reason);
			if (catalogCheck != null)
				catalogCheck.cancellation().cancel(reason);
			if (applicationCancellation != null)
				applicationCancellation.run();
			if (task != null) {
				task.cancel(true);
				processor.remove(task);
			}
			if (stream != null)
				stream.close(reason, cause);
			if (subscription != null)
				removeSubscription(this, subscription);
			if (capReservation != null)
				removeSubscription(this, capReservation);
			if (stream != null)
				markStreamClosed(completedStream
						? StreamTerminationReason.COMPLETED : reason);
			// Cancellation is itself the transport terminal transition. Application
			// cleanup may reenter applicationTerminated() and mark this control terminal
			// before stream.close() can report termination, so neither callback can be
			// relied upon to release the transport half of the lifecycle lease.
			finishTransportLifecycle();
			if (completedStream)
				finishPlannedRequestObservation(requestObservationResult(
						StreamTerminationReason.COMPLETED, null));
			else {
				RequestObservationResult result = requestObservationResult(reason, cause);
				finishRequestObservation(result.outcome(), result.error(),
						result.throwables());
			}
			return true;
		}

		private void onTimer(long nowNanos) {
			LegacyGetControl get; synchronized (lock) { get = legacyGet; }
			if (get != null && get.headOwned) { get.onTimer(nowNanos); return; }
			maintainLegacySessionTerminalTransport(nowNanos);
			maintainSubscriptionAuthorization(nowNanos);
			McpRequestSseStream stream;
			boolean subscriptionStream;
			boolean applicationStreamOwned;
			boolean streamTerminationOwned;
			boolean completeExpiredSubscription;
			SubscriptionStreamFailure pendingFailure;
			synchronized (lock) {
				if (terminal || canceled || legacyResponseDeliveryDetached
						|| catalogPolicyEvaluationOwned
						|| catalogPolicyDeadlineResponseOwned)
					return;
				pendingFailure = pendingSubscriptionStreamFailure;
				pendingSubscriptionStreamFailure = null;
				stream = responseStream;
				subscriptionStream = subscriptionRegistration != null;
				applicationStreamOwned = applicationOwned;
				streamTerminationOwned = streamAbortOwned
						|| (streamTerminalResponseOwned
								&& streamTerminalDeadlineResponseOwned);
				completeExpiredSubscription = subscriptionOwned
						&& subscriptionStream && nowNanos - deadlineNanos >= 0L;
			}
			if (pendingFailure != null) {
				pendingFailure.stream().fail(
						pendingFailure.reason(), pendingFailure.cause());
				return;
			}

			if (stream != null) {
				if (completeExpiredSubscription) {
					completeSubscription(StreamTerminationReason.RESPONSE_TIMEOUT);
					return;
				}
				// The application execution owns its active deadline and can encode a
				// correlated terminal error. The protocol timer retains responsibility
				// once the application has already offered its terminal response.
				if (!subscriptionStream && !applicationStreamOwned
						&& !streamTerminationOwned
						&& stream.failIfDeadlineExpired(nowNanos, deadlineNanos,
						StreamTerminationReason.RESPONSE_TIMEOUT, null)) {
					application.recordStreamDeadlineExpiration();
					return;
				}
				TransportFailureObserver.@Nullable Observation
						writeTimeoutObservation = null;
				boolean writeTimeoutWon = false;
				try {
					try {
						writeTimeoutObservation = transportFailureObserver.beginFailure(
								TransportFailureReason.WRITE_TIMEOUT);
					} catch (Throwable ignored) {
						// Metrics observation must not alter the timer transition.
					}
					writeTimeoutWon = stream.failIfWriteIdleExpired(nowNanos,
							transportConfiguration.responseWriteIdleTimeout().toNanos(),
							StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null);
				} finally {
					if (writeTimeoutObservation != null) {
						try {
							if (!writeTimeoutWon)
								writeTimeoutObservation.discard();
						} catch (Throwable ignored) {
							// Metrics observation must not alter the timer transition.
						} finally {
							try {
								writeTimeoutObservation.close();
							} catch (Throwable ignored) {
								// Metrics observation must not alter the timer transition.
							}
						}
					}
				}
				if (writeTimeoutWon)
					return;

				boolean terminateForInvariantFailure = false;
				boolean keepAliveEmitted = false;
				IllegalStateException keepAliveFailure = null;
				try {
					synchronized (streamObservationTransitionLock) {
						synchronized (lock) {
							if (terminal || canceled || responseStream != stream
									|| streamTerminalResponseOwned || streamAbortOwned
									|| nowNanos - deadlineNanos >= 0L)
								return;
							if (nowNanos - nextKeepAliveNanos >= 0L) {
								long keepAliveIntervalNanos =
										transportConfiguration.keepAliveInterval()
												.toNanos();
								McpOutboundChannel.OfferResult result =
										stream.offerKeepAliveIfWriteIdleExpired(
												nowNanos, keepAliveIntervalNanos);
								if (result == McpOutboundChannel.OfferResult.ACCEPTED) {
									keepAliveEmitted = true;
								} else if (result
										== McpOutboundChannel.OfferResult.TOO_LARGE) {
									streamAbortOwned = true;
									terminateForInvariantFailure = true;
									keepAliveFailure = new IllegalStateException(
											"The MCP keep-alive frame exceeds the outbound byte capacity.");
								}
								// Keep-alives are optional. A recent write or a full queue
								// skips this interval without turning a healthy stream into a
								// backpressure failure.
								if (result != McpOutboundChannel.OfferResult.CLOSED) {
									long next = result
											== McpOutboundChannel.OfferResult.NOT_IDLE
											? stream.responseWriteIdleDeadlineNanos(
													keepAliveIntervalNanos)
											: Long.MAX_VALUE;
									nextKeepAliveNanos = next != Long.MAX_VALUE
												&& next - nowNanos > 0L ? next
												: nowNanos + keepAliveIntervalNanos;
								}
							}
						}
						if (keepAliveEmitted)
							markKeepAliveEmitted();
					}
				} finally {
					drainApplicationExecutionObservation();
				}
				if (terminateForInvariantFailure)
					stream.fail(StreamTerminationReason.INTERNAL_ERROR,
							requireNonNull(keepAliveFailure));
				return;
			}

			if (nowNanos - deadlineNanos < 0L)
				return;

			ProtocolDeadlineExpiration expiration;
			McpHttpServerRuntime.this.applicationExecutionObserver
					.beginRequestTransitionDeferral();
			try {
				synchronized (lock) {
					if (terminal || canceled || applicationOwned)
						return;

					expiration = application.reserveProtocolOperationIfRunning(
							() -> detachProtocolDeadline(true)).orElse(null);
					if (expiration == null)
						return;
				}
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}

			finishProtocolDeadline(expiration);
		}

		/** Bounds physical delivery even after its winning semantic cancellation. */
		private void maintainLegacySessionTerminalTransport(long nowNanos) {
			McpRequestSseStream stream;
			long completionDeadline;
			synchronized (lock) {
				if (legacySessionTerminationCause == null || lifecycleTransportTerminated || responseStream == null)
					return;
				stream = responseStream;
				completionDeadline = legacySessionTransportCompletionDeadlineNanos;
			}
			TransportFailureObserver.Observation observation = null;
			boolean failed = false;
			try {
				try { observation = transportFailureObserver.beginFailure(TransportFailureReason.WRITE_TIMEOUT); }
				catch (Throwable ignored) { /* Observation cannot delay physical cleanup. */ }
				failed = stream.failIfDeadlineExpired(nowNanos, completionDeadline,
						StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null)
						|| stream.failIfWriteIdleExpired(nowNanos, transportConfiguration.responseWriteIdleTimeout().toNanos(),
								StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null);
			} finally {
				if (observation != null) {
					try { if (!failed) observation.discard(); }
					catch (Throwable ignored) { /* Observation cannot alter cleanup. */ }
					finally { try { observation.close(); } catch (Throwable ignored) { /* Already fenced. */ } }
				}
			}
		}

		private void streamTerminated(@NonNull StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason exactReason,
				@Nullable Throwable cause) {
			requireNonNull(reason);
			if (finishLegacySessionStreamIfOwned(reason, exactReason, cause))
				return;
			if (detachLegacyResponseDelivery(reason, cause))
				return;
			boolean cancelApplication;
			SubscriptionRegistration subscription;
			StreamTerminationReason observedStreamReason;
			McpStreamTerminationReason observedExactReason;
			SubscriptionAuthorizationCheck authorizationCheck;
			CatalogProjectionCheck catalogCheck;
			synchronized (lock) {
				if (terminal)
					return;

				cancelApplication = applicationOwned
						&& reason != StreamTerminationReason.COMPLETED;
				if (reason != StreamTerminationReason.COMPLETED) {
					canceled = true;
					cancellationReason = reason;
					cancellationCause = cause;
				}
				applicationOwned = false;
				subscriptionOwned = false;
				subscription = subscriptionRegistration;
				authorizationCheck = subscriptionAuthorizationCheck;
				subscriptionAuthorizationCheck = null;
				catalogCheck = catalogProjectionCheck;
				catalogProjectionCheck = null;
				subscriptionRegistration = null;
				pendingSubscriptionStreamFailure = null;
				preRenderedSubscriptionTerminal = null;
				observedStreamReason = reason == StreamTerminationReason.COMPLETED
						&& plannedSubscriptionCloseReason != null
						? plannedSubscriptionCloseReason : reason;
				observedExactReason = exactReason != null ? exactReason
						: plannedSubscriptionCloseExactReason;
				plannedSubscriptionCloseExactReason = null;
				markTerminalWhileLocked();
				protocolTask = null;
				responseCallback = null;
				releaseIdentifiedRequestExchange();
			}

			if (reason != StreamTerminationReason.COMPLETED)
				catalogAccessCancellation.cancel(reason);
			if (authorizationCheck != null)
				authorizationCheck.cancellation().cancel(
						reason == StreamTerminationReason.COMPLETED
								? StreamTerminationReason.APPLICATION_CANCELED : reason);
			if (catalogCheck != null)
				catalogCheck.cancellation().cancel(
						reason == StreamTerminationReason.COMPLETED
								? StreamTerminationReason.APPLICATION_CANCELED : reason);
			if (subscription != null)
				removeSubscription(this, subscription);
			markStreamClosed(observedStreamReason, observedExactReason);
			finishTransportLifecycle();
			if (reason == StreamTerminationReason.COMPLETED)
				finishPlannedRequestObservation(requestObservationResult(reason, cause));
			else if (observedExactReason == McpStreamTerminationReason
					.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED
					|| observedExactReason == McpStreamTerminationReason
					.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED)
				finishRequestObservation(McpRequestOutcome.CANCELED, null, List.of());
			else {
				RequestObservationResult result = requestObservationResult(reason, cause);
				finishRequestObservation(result.outcome(), result.error(),
						result.throwables());
			}
			if (cancelApplication)
				application.cancel(request, reason, cause);
		}

		/** Closes a lost legacy SSE writer without abandoning bounded application work. */
		private boolean detachLegacyResponseDelivery(
				@NonNull StreamTerminationReason reason, @Nullable Throwable cause) {
			if (reason != StreamTerminationReason.CLIENT_DISCONNECTED
					&& reason != StreamTerminationReason.WRITE_FAILED)
				return false;
			McpRequestSseStream stream;
			boolean completedStream;
			synchronized (lock) {
				if (legacyResponseDeliveryDetached)
					return true;
				if (terminal || canceled || !responseStreamCommitted
						|| responseStream == null || protocolProfile == null
						|| !McpLegacyHttpWire.isLegacyRevision(protocolProfile.revision()))
					return false;
				legacyResponseDeliveryDetached = true;
				if (legacyCall != null) legacyCall.detachProgress();
				stream = responseStream;
				completedStream = stream.isTerminalWritten();
				if (simulation != null)
					simulation.reserveRuntimeReason(McpStreamTerminationReason.CLIENT_DISCONNECTED);
				if (!applicationOwned) {
					markTerminalWhileLocked();
					protocolTask = null;
					releaseIdentifiedRequestExchange();
				}
			}
			// Closing wakes blocked reporters. The execution retains its own deadline,
			// cancellation state and physical worker reservation until it terminates.
			stream.close(reason, cause);
			markStreamClosed(completedStream ? StreamTerminationReason.COMPLETED : reason);
			finishTransportLifecycle();
			RequestObservationResult result = requestObservationResult(
					completedStream ? StreamTerminationReason.COMPLETED : reason, cause);
			finishRequestObservation(result.outcome(), result.error(), result.throwables());
			return true;
		}

		@NonNull
		private ProtocolDeadlineExpiration detachProtocolDeadline(boolean cancelTask) {
			canceled = true;
			cancellationReason = StreamTerminationReason.RESPONSE_TIMEOUT;
			SubscriptionAuthorizationCheck authorizationCheck =
					subscriptionAuthorizationCheck;
			subscriptionAuthorizationCheck = null;
			CatalogProjectionCheck catalogCheck = catalogProjectionCheck;
			catalogProjectionCheck = null;
			markTerminalWhileLocked();
			FutureTask<Void> task = protocolTask;
			protocolTask = null;
			Consumer<MicrohttpResponse> callback = takeResponseCallback();
			SubscriptionRegistration capReservation = subscriptionCapReservation;
			subscriptionCapReservation = null;
			releaseIdentifiedRequestExchange();
			return new ProtocolDeadlineExpiration(
					cancelTask ? task : null, callback, deadlineResponseHeaders,
					capReservation, authorizationCheck, catalogCheck);
		}

		private void finishProtocolDeadline(
				@NonNull ProtocolDeadlineExpiration expiration) {
			requireNonNull(expiration);
			catalogAccessCancellation.cancel(
					StreamTerminationReason.RESPONSE_TIMEOUT);
			SubscriptionAuthorizationCheck authorizationCheck =
					expiration.authorizationCheck();
			if (authorizationCheck != null)
				authorizationCheck.cancellation().cancel(
						StreamTerminationReason.RESPONSE_TIMEOUT);
			CatalogProjectionCheck catalogCheck = expiration.catalogCheck();
			if (catalogCheck != null)
				catalogCheck.cancellation().cancel(
						StreamTerminationReason.RESPONSE_TIMEOUT);
			FutureTask<Void> task = expiration.task();
			if (task != null) {
				task.cancel(true);
				processor.remove(task);
			}
			SubscriptionRegistration capReservation =
					expiration.subscriptionCapReservation();
			if (capReservation != null)
				removeSubscription(this, capReservation);
			application.recordProtocolDeadlineExpiration();
			RequestObservationResult fallback = new RequestObservationResult(
					McpRequestOutcome.DEADLINE_EXCEEDED, null, List.of());
			replacePlannedRequestObservation(fallback);
			MicrohttpResponse response = withRequestObservationTermination(
					emptyResponse(504, "Gateway Timeout", expiration.responseHeaders()),
					fallback);
			boolean trackBody = tracksLifecycleResponseBody();
			if (!trackBody)
				finishTransportLifecycle();
			Throwable deliveryFailure = deliverResponse(
					expiration.responseCallback(), response);
			if (deliveryFailure != null && trackBody)
				finishTransportLifecycle();
			if (simulation != null) {
				finishDeliveredSimulationResponse(fallback, deliveryFailure);
			} else if (deliveryFailure != null) {
				Throwable requiredFailure = deliveryFailure;
				observeTransportFailure(TransportFailureReason.RESPONSE_READY_ERROR,
						() -> finishRequestObservation(
								McpRequestOutcome.WRITE_FAILED, null,
								List.of(requiredFailure)));
			}
		}

		private void applicationTerminated() {
			boolean remove;
			boolean finishCanceled;
			boolean noTransportResponse;
			synchronized (lock) {
				applicationOwned = false;
				protocolTask = null;
				remove = responseStream == null || canceled || terminal;
				// An outer cancel transition owns its exact reason and cause.
				finishCanceled = remove
						&& cancellationReason == null
						&& plannedRequestObservationResult == null
						&& requestObservationTerminal == null;
				if (remove) {
					markTerminalWhileLocked();
					responseCallback = null;
					releaseIdentifiedRequestExchange();
				}
				noTransportResponse = !lifecycleTransportStarted;
			}
			applicationLifecycleWorkTerminated();
			if (remove && noTransportResponse)
				finishTransportLifecycle();
			if (finishCanceled)
				finishRequestObservation(McpRequestOutcome.CANCELED, null, List.of());
		}

		@NonNull
		private Consumer<@NonNull MicrohttpResponse> takeResponseCallback() {
			Consumer<@NonNull MicrohttpResponse> callback = requireNonNull(responseCallback,
					"An open request must retain its response callback.");
			responseCallback = null;
			return callback;
		}

		@NonNull
		private MicrohttpResponse withRequestObservationTermination(
				@NonNull MicrohttpResponse response,
				@NonNull RequestObservationResult fallback) {
			requireNonNull(response);
			requireNonNull(fallback);
			boolean observeRequest;
			boolean trackBody = tracksLifecycleResponseBody();
			McpLegacySessionStore.Initialization initialization;
			synchronized (lock) {
				observeRequest = requestObservation != null;
				initialization = legacyInitialization;
			}
			if (!observeRequest && !trackBody)
				return response;
			MicrohttpResponse observedResponse = response.withBodyTerminationListener(
					(reason, cause) -> {
				try {
					if (initialization != null && reason != StreamTerminationReason.COMPLETED)
						initialization.deliveryFailed();
					if (observeRequest) {
						if (reason == StreamTerminationReason.COMPLETED)
							finishPlannedRequestObservation(fallback);
						else {
							RequestObservationResult result =
									requestObservationResult(reason, cause);
							finishRequestObservation(result.outcome(), result.error(),
									result.throwables());
						}
					}
				} finally {
					if (trackBody)
						finishTransportLifecycle();
				}
			});
			if (trackBody)
				markLifecycleTransportStarted();
			return observedResponse;
		}

		private boolean tracksLifecycleResponseBody() {
			return lifecycleAdmission != null || legacyCall != null || legacyInitialization != null;
		}

		private @Nullable Throwable deliverResponse(
				@NonNull Consumer<@NonNull MicrohttpResponse> callback,
				@NonNull MicrohttpResponse response) {
			try {
				callback.accept(response);
				return null;
			} catch (Throwable throwable) {
				McpLegacySessionStore.Initialization initialization = legacyInitialization;
				if (initialization != null) initialization.deliveryFailed();
				// A reserved terminal outcome remains authoritative on delivery failure.
				return throwable;
			}
		}

		private void finishDeliveredSimulationResponse(
				@NonNull RequestObservationResult fallback,
				@Nullable Throwable deliveryFailure) {
			McpSimulationRuntime activeSimulation = simulation;
			if (activeSimulation == null)
				return;
			// The simulator materializes a finite body at publication; it has no
			// socket writer to deliver the network body-termination listener.
			finishTransportLifecycle();
			if (deliveryFailure != null) {
				finishRequestObservation(McpRequestOutcome.WRITE_FAILED, null,
						List.of(deliveryFailure));
				return;
			}
			McpStreamTerminationReason reason = activeSimulation
					.nonStreamingReason().orElse(McpStreamTerminationReason.COMPLETED);
			if (reason == McpStreamTerminationReason
					.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED) {
				finishRequestObservation(McpRequestOutcome.CANCELED, null, List.of());
				return;
			}
			finishPlannedRequestObservation(requireNonNull(fallback));
		}

		private void releaseIdentifiedRequestExchange() {
			if (identifiedRequestExchange) {
				identifiedRequestExchange = false;
				activeIdentifiedRequestExchangeCount.decrementAndGet();
			}
		}
	}

	@ThreadSafe
	private final class RuntimeTransportFailureObservation
			implements TransportFailureObserver.Observation {
		private final McpApplicationExecutionObserver.@NonNull PendingMetricRecord
				pendingRecord;
		private boolean discarded;
		private boolean closed;

		private RuntimeTransportFailureObservation(
				McpApplicationExecutionObserver.@NonNull PendingMetricRecord
						pendingRecord) {
			this.pendingRecord = requireNonNull(pendingRecord);
		}

		@Override
		public synchronized void discard() {
			if (this.closed)
				throw new IllegalStateException(
						"A closed transport-failure observation cannot be discarded.");
			if (!this.discarded) {
				applicationExecutionObserver.discardPendingMetric(this.pendingRecord);
				this.discarded = true;
			}
		}

		@Override
		public void close() {
			synchronized (this) {
				if (this.closed)
					return;
				this.closed = true;
			}
			try {
				applicationExecutionObserver.endDeferralForAsynchronousDrain();
			} finally {
				transportMetricDrainScheduler.schedule();
			}
		}
	}

	@ThreadSafe
	static final class TransportMetricDrainScheduler {
		private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();
		@NonNull
		private final McpApplicationExecutionObserver observer;
		@NonNull
		private final Executor executor;
		@NonNull
		private final Object lock;
		private long requestedGeneration;
		private long completedGeneration;
		private boolean workerScheduled;

		TransportMetricDrainScheduler(
				@NonNull McpApplicationExecutionObserver observer) {
			this(observer, newTransportMetricExecutor());
		}

		TransportMetricDrainScheduler(
				@NonNull McpApplicationExecutionObserver observer,
				@NonNull Executor executor) {
			this.observer = requireNonNull(observer);
			this.executor = requireNonNull(executor);
			this.lock = new Object();
		}

		@NonNull
		private static Executor newTransportMetricExecutor() {
			ThreadFactory threadFactory = runnable -> {
				Thread thread = new Thread(runnable, "soklet-mcp-metrics-"
						+ THREAD_SEQUENCE.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			};
			ThreadPoolExecutor executor = new ThreadPoolExecutor(
					0, 1, 1L, TimeUnit.SECONDS,
					new LinkedBlockingQueue<>(), threadFactory,
					new ThreadPoolExecutor.AbortPolicy());
			executor.allowCoreThreadTimeOut(true);
			return executor;
		}

		void schedule() {
			long attemptedGeneration;
			synchronized (this.lock) {
				if (this.requestedGeneration != Long.MAX_VALUE)
					this.requestedGeneration++;
				if (this.workerScheduled)
					return;
				this.workerScheduled = true;
				attemptedGeneration = this.requestedGeneration;
			}

			while (true) {
				try {
					this.executor.execute(this::drain);
					return;
				} catch (RuntimeException | Error ignored) {
					long nextAttemptGeneration;
					synchronized (this.lock) {
						if (Long.compare(this.requestedGeneration,
								attemptedGeneration) == 0) {
							this.workerScheduled = false;
							return;
						}
						nextAttemptGeneration = this.requestedGeneration;
					}
					// A signal raced with the rejected submission. Retry that
					// generation once without delivering on the signaling thread.
					attemptedGeneration = nextAttemptGeneration;
				}
			}
		}

		private void drain() {
			while (true) {
				long targetGeneration;
				synchronized (this.lock) {
					targetGeneration = this.requestedGeneration;
				}
				try {
					this.observer.drainAsynchronously();
				} catch (Throwable ignored) {
					// An internal observer failure must not strand later drain signals.
				}
				synchronized (this.lock) {
					this.completedGeneration = targetGeneration;
					if (this.completedGeneration == this.requestedGeneration) {
						this.workerScheduled = false;
						return;
					}
				}
			}
		}
	}

	private enum SubscriptionAuthorizationCheckKind {
		INITIAL,
		RENEWAL,
		RECONCILIATION
	}

	private enum SubscriptionAuthorizationDisposition {
		ALLOWED,
		DENIED,
		TIMED_OUT,
		CAPACITY_REJECTED,
		FAILED,
		STALE_RESULT,
		TERMINATED
	}

	private enum CatalogProjectionDisposition {
		SUCCEEDED,
		TIMED_OUT,
		CAPACITY_REJECTED,
		FAILED,
		STALE_RESULT,
		TERMINATED
	}

	@ThreadSafe
	private static final class BoundedPolicyPhysicalExit {
		@NonNull
		private final AtomicBoolean exited;
		@NonNull
		private final CountDownLatch exitLatch;

		private BoundedPolicyPhysicalExit() {
			this.exited = new AtomicBoolean();
			this.exitLatch = new CountDownLatch(1);
		}

		private void markExited() {
			this.exited.set(true);
			this.exitLatch.countDown();
		}

		private boolean exited() {
			return this.exited.get();
		}

		private boolean awaitUntil(@NonNull McpApplicationClock clock,
				long deadlineNanos) {
			requireNonNull(clock);
			boolean interrupted = false;
			try {
				while (!exited()) {
					long remainingNanos = deadlineNanos - clock.nanoTime();
					if (remainingNanos <= 0L)
						return false;
					try {
						if (this.exitLatch.await(remainingNanos,
								TimeUnit.NANOSECONDS))
							return true;
					} catch (InterruptedException ignored) {
						interrupted = true;
						return false;
					}
				}
				return true;
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}
	}

	private record CatalogProjectionCheck(
			McpCatalogProjectionQueue.@NonNull Family family,
			McpCatalogProjectionQueue.@Nullable Projection projection,
			long authorizationRevision, long authorizationGeneration,
			@NonNull String endpointPath,
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpRequestContext requestContext,
			McpApplicationExecution.@NonNull BoundedPolicyCancellation cancellation,
			@NonNull BoundedPolicyPhysicalExit physicalExit,
			long deadlineNanos) {
		private CatalogProjectionCheck {
			requireNonNull(family);
			requireNonNull(endpointPath);
			requireNonNull(protocolProfile);
			requireNonNull(requestContext);
			requireNonNull(cancellation);
			requireNonNull(physicalExit);
		}

		/** @return bounded rendering that excludes request and transport data */
		@Override
		@NonNull
		public String toString() {
			return "CatalogProjectionCheck{family=" + family
					+ ", projectionPresent=" + (projection != null) + "}";
		}
	}

	private record CatalogProjectionExecution(
			@NonNull CatalogProjectionDisposition disposition,
			McpCatalogProjectionQueue.@Nullable Digest digest,
			@Nullable Throwable failure, boolean queuedTimeout) {
		private CatalogProjectionExecution {
			requireNonNull(disposition);
			if ((disposition == CatalogProjectionDisposition.SUCCEEDED)
					!= (digest != null))
				throw new IllegalArgumentException(
						"Exactly a successful catalog projection retains a digest.");
		}

		private static CatalogProjectionExecution succeeded(
				McpCatalogProjectionQueue.@NonNull Digest digest) {
			return new CatalogProjectionExecution(
					CatalogProjectionDisposition.SUCCEEDED,
					requireNonNull(digest), null, false);
		}

		private static CatalogProjectionExecution timedOut(
				@NonNull Throwable failure, boolean queued) {
			return new CatalogProjectionExecution(
					CatalogProjectionDisposition.TIMED_OUT, null,
					requireNonNull(failure), queued);
		}

		private static CatalogProjectionExecution capacityRejected(
				@Nullable Throwable failure) {
			return new CatalogProjectionExecution(
					CatalogProjectionDisposition.CAPACITY_REJECTED,
					null, failure, false);
		}

		private static CatalogProjectionExecution failed(
				@NonNull Throwable failure) {
			return new CatalogProjectionExecution(
					CatalogProjectionDisposition.FAILED, null,
					requireNonNull(failure), false);
		}

		/** @return bounded rendering that excludes digests and failure details */
		@Override
		@NonNull
		public String toString() {
			return "CatalogProjectionExecution{disposition=" + disposition
					+ ", digestPresent=" + (digest != null)
					+ ", failurePresent=" + (failure != null)
					+ ", queuedTimeout=" + queuedTimeout + "}";
		}
	}

	private record InitialCatalogBaselines(
			McpCatalogProjectionQueue.@Nullable Digest tools,
			McpCatalogProjectionQueue.@Nullable Digest prompts) {
		private void install(@NonNull McpCatalogProjectionQueue queue) {
			McpCatalogProjectionQueue requiredQueue = requireNonNull(queue);
			if (tools != null)
				requiredQueue.establishBaseline(
						McpCatalogProjectionQueue.Family.TOOLS, tools);
			if (prompts != null)
				requiredQueue.establishBaseline(
						McpCatalogProjectionQueue.Family.PROMPTS, prompts);
		}

		/** @return bounded rendering that excludes catalog digests */
		@Override
		@NonNull
		public String toString() {
			return "InitialCatalogBaselines{toolsPresent=" + (tools != null)
					+ ", promptsPresent=" + (prompts != null) + "}";
		}
	}

	private record InitialCatalogProjectionResult(
			@NonNull CatalogProjectionDisposition disposition,
			@Nullable InitialCatalogBaselines baselines) {
		private InitialCatalogProjectionResult {
			requireNonNull(disposition);
			if ((disposition == CatalogProjectionDisposition.SUCCEEDED)
					!= (baselines != null))
				throw new IllegalArgumentException(
						"Exactly successful initial catalog projection retains baselines.");
		}

		/** @return bounded rendering that excludes catalog digests */
		@Override
		@NonNull
		public String toString() {
			return "InitialCatalogProjectionResult{disposition=" + disposition
					+ ", baselinesPresent=" + (baselines != null) + "}";
		}
	}

	private record SubscriptionAuthorizationCheck(
			@NonNull SubscriptionAuthorizationCheckKind kind,
			long generation, long reconciliationGeneration,
			@NonNull String endpointPath,
			@NonNull McpJsonRpcId subscriptionId,
			@NonNull AcceptedSubscriptionFilter filter,
			@NonNull SubscriptionAuthorizationContextSnapshot context,
			McpApplicationExecution.@NonNull BoundedPolicyCancellation cancellation,
			@NonNull BoundedPolicyPhysicalExit physicalExit,
			long deadlineNanos, long lifetimeDeadlineNanos) {
		private SubscriptionAuthorizationCheck {
			requireNonNull(kind);
			requireNonNull(endpointPath);
			requireNonNull(subscriptionId);
			requireNonNull(filter);
			requireNonNull(context);
			requireNonNull(cancellation);
			requireNonNull(physicalExit);
		}

		/** @return bounded rendering that excludes request and authorization data */
		@Override
		@NonNull
		public String toString() {
			return "SubscriptionAuthorizationCheck{kind=" + kind + "}";
		}
	}

	private record SubscriptionAuthorizationCallbackResult(
			@NonNull McpSubscriptionAuthorization authorization,
			@NonNull Set<@NonNull String> acceptedTaskIds) {
		private SubscriptionAuthorizationCallbackResult {
			requireNonNull(authorization);
			acceptedTaskIds = Collections.unmodifiableSet(
					new LinkedHashSet<>(requireNonNull(acceptedTaskIds)));
		}

		/** @return bounded rendering that excludes grants and task identifiers */
		@Override
		@NonNull
		public String toString() {
			return "SubscriptionAuthorizationCallbackResult{acceptedTaskIdCount="
					+ acceptedTaskIds.size() + "}";
		}
	}

	private record SubscriptionAuthorizationExecution(
			@NonNull SubscriptionAuthorizationDisposition disposition,
			McpSubscriptionAuthorization.@Nullable Allowed allowed,
			@NonNull Set<@NonNull String> acceptedTaskIds,
			@Nullable Throwable failure, boolean queuedTimeout) {
		private SubscriptionAuthorizationExecution {
			requireNonNull(disposition);
			acceptedTaskIds = Collections.unmodifiableSet(
					new LinkedHashSet<>(requireNonNull(acceptedTaskIds)));
			if ((disposition == SubscriptionAuthorizationDisposition.ALLOWED)
					!= (allowed != null))
				throw new IllegalArgumentException(
						"Exactly an allowed authorization execution retains a grant.");
			if (disposition != SubscriptionAuthorizationDisposition.ALLOWED
					&& !acceptedTaskIds.isEmpty())
				throw new IllegalArgumentException(
						"Only an allowed authorization execution retains task IDs.");
		}

		@NonNull
		private static SubscriptionAuthorizationExecution allowed(
				McpSubscriptionAuthorization.@NonNull Allowed allowed,
				@NonNull Set<@NonNull String> acceptedTaskIds) {
			return new SubscriptionAuthorizationExecution(
					SubscriptionAuthorizationDisposition.ALLOWED,
					requireNonNull(allowed), requireNonNull(acceptedTaskIds),
					null, false);
		}

		@NonNull
		private static SubscriptionAuthorizationExecution denied() {
			return new SubscriptionAuthorizationExecution(
					SubscriptionAuthorizationDisposition.DENIED, null, Set.of(),
					null, false);
		}

		@NonNull
		private static SubscriptionAuthorizationExecution timedOut(
				@NonNull Throwable failure, boolean queued) {
			return new SubscriptionAuthorizationExecution(
					SubscriptionAuthorizationDisposition.TIMED_OUT, null, Set.of(),
					requireNonNull(failure), queued);
		}

		@NonNull
		private static SubscriptionAuthorizationExecution capacityRejected(
				@Nullable Throwable failure) {
			return new SubscriptionAuthorizationExecution(
					SubscriptionAuthorizationDisposition.CAPACITY_REJECTED,
					null, Set.of(), failure, false);
		}

		@NonNull
		private static SubscriptionAuthorizationExecution failed(
				@NonNull Throwable failure) {
			return new SubscriptionAuthorizationExecution(
					SubscriptionAuthorizationDisposition.FAILED, null, Set.of(),
					requireNonNull(failure), false);
		}

		/** @return bounded rendering that excludes grants, task IDs, and failures */
		@Override
		@NonNull
		public String toString() {
			return "SubscriptionAuthorizationExecution{disposition=" + disposition
					+ ", acceptedTaskIdCount=" + acceptedTaskIds.size()
					+ ", failurePresent=" + (failure != null)
					+ ", queuedTimeout=" + queuedTimeout + "}";
		}
	}

	private record SubscriptionAuthorizationResult(
			@NonNull SubscriptionAuthorizationDisposition disposition,
			@NonNull Set<@NonNull String> acceptedTaskIds,
			@Nullable Throwable failure, boolean queuedTimeout) {
		private SubscriptionAuthorizationResult {
			requireNonNull(disposition);
			acceptedTaskIds = Collections.unmodifiableSet(
					new LinkedHashSet<>(requireNonNull(acceptedTaskIds)));
			if (disposition != SubscriptionAuthorizationDisposition.ALLOWED
					&& !acceptedTaskIds.isEmpty())
				throw new IllegalArgumentException(
						"Only an allowed authorization result retains task IDs.");
		}

		@NonNull
		private static SubscriptionAuthorizationResult allowed() {
			return allowed(Set.of());
		}

		@NonNull
		private static SubscriptionAuthorizationResult allowed(
				@NonNull Set<@NonNull String> acceptedTaskIds) {
			return new SubscriptionAuthorizationResult(
					SubscriptionAuthorizationDisposition.ALLOWED,
					requireNonNull(acceptedTaskIds), null, false);
		}

		@NonNull
		private static SubscriptionAuthorizationResult timedOut(boolean queued) {
			return new SubscriptionAuthorizationResult(
					SubscriptionAuthorizationDisposition.TIMED_OUT, Set.of(),
					null, queued);
		}

		@NonNull
		private static SubscriptionAuthorizationResult capacityRejected(
				@Nullable Throwable failure) {
			return new SubscriptionAuthorizationResult(
					SubscriptionAuthorizationDisposition.CAPACITY_REJECTED,
					Set.of(), failure, false);
		}

		@NonNull
		private static SubscriptionAuthorizationResult failed(
				@NonNull Throwable failure) {
			return new SubscriptionAuthorizationResult(
					SubscriptionAuthorizationDisposition.FAILED, Set.of(),
					requireNonNull(failure), false);
		}

		@NonNull
		private static SubscriptionAuthorizationResult stale() {
			return new SubscriptionAuthorizationResult(
					SubscriptionAuthorizationDisposition.STALE_RESULT, Set.of(),
					null, false);
		}

		@NonNull
		private static SubscriptionAuthorizationResult terminated() {
			return new SubscriptionAuthorizationResult(
					SubscriptionAuthorizationDisposition.TERMINATED, Set.of(),
					null, false);
		}

		/** @return bounded rendering that excludes task IDs and failure details */
		@Override
		@NonNull
		public String toString() {
			return "SubscriptionAuthorizationResult{disposition=" + disposition
					+ ", acceptedTaskIdCount=" + acceptedTaskIds.size()
					+ ", failurePresent=" + (failure != null)
					+ ", queuedTimeout=" + queuedTimeout + "}";
		}
	}

	private record EffectiveSubscriptionAuthorizationGrant(
			@NonNull Instant validUntil, long expiryNanos, long renewalNanos,
			@NonNull Optional<@NonNull Object> applicationContext) {
		private EffectiveSubscriptionAuthorizationGrant {
			requireNonNull(validUntil);
			requireNonNull(applicationContext);
		}

		/** @return bounded rendering that excludes lease and application data */
		@Override
		@NonNull
		public String toString() {
			return "EffectiveSubscriptionAuthorizationGrant{applicationContextPresent="
					+ applicationContext.isPresent() + "}";
		}
	}

	private record SubscriptionAuthorizationFailure(
			@Nullable SubscriptionAuthorizationCheck check,
			@Nullable CatalogProjectionCheck catalogCheck,
			@NonNull McpStreamTerminationReason exactReason,
			boolean signalTimer) {
		private SubscriptionAuthorizationFailure {
			requireNonNull(exactReason);
		}

		/** @return bounded rendering that excludes nested request state */
		@Override
		@NonNull
		public String toString() {
			return "SubscriptionAuthorizationFailure{authorizationCheckPresent="
					+ (check != null) + ", catalogCheckPresent="
					+ (catalogCheck != null) + ", exactReason=" + exactReason
					+ ", signalTimer=" + signalTimer + "}";
		}
	}

	@ThreadSafe
	private record SubscriptionAuthorizationContextSnapshot(
			@NonNull McpRequestContext initialRequestContext,
			@NonNull Optional<@NonNull Object> applicationContext,
			@NonNull Optional<@NonNull Instant> previousValidUntil,
			@NonNull Instant deadline, boolean toolsListChangedIncluded,
			boolean promptsListChangedIncluded,
			boolean resourcesListChangedIncluded,
			@NonNull Set<@NonNull URI> resourceSubscriptionUris,
			@NonNull Set<@NonNull String> taskIds)
			implements McpSubscriptionAuthorizationContext {
		private SubscriptionAuthorizationContextSnapshot {
			requireNonNull(initialRequestContext);
			requireNonNull(applicationContext);
			requireNonNull(previousValidUntil);
			requireNonNull(deadline);
			resourceSubscriptionUris = Collections.unmodifiableSet(
					new LinkedHashSet<>(requireNonNull(resourceSubscriptionUris)));
			taskIds = Collections.unmodifiableSet(
					new LinkedHashSet<>(requireNonNull(taskIds)));
		}

		@Override public @NonNull McpRequestContext getInitialRequestContext() {
			return initialRequestContext;
		}
		@Override public @NonNull Optional<@NonNull Object> getApplicationContext() {
			return applicationContext;
		}
		@Override public @NonNull Optional<@NonNull Instant> getPreviousValidUntil() {
			return previousValidUntil;
		}
		@Override public @NonNull Instant getDeadline() { return deadline; }
		@Override public @NonNull Boolean isToolsListChangedIncluded() {
			return toolsListChangedIncluded;
		}
		@Override public @NonNull Boolean isPromptsListChangedIncluded() {
			return promptsListChangedIncluded;
		}
		@Override public @NonNull Boolean isResourcesListChangedIncluded() {
			return resourcesListChangedIncluded;
		}
		@Override public @NonNull Set<@NonNull URI> getResourceSubscriptionUris() {
			return resourceSubscriptionUris;
		}
		@Override public @NonNull Set<@NonNull String> getTaskIds() {
			return taskIds;
		}

		/** @return bounded rendering that excludes request and application data */
		@Override
		@NonNull
		public String toString() {
			return "SubscriptionAuthorizationContextSnapshot{applicationContextPresent="
					+ applicationContext.isPresent() + ", previousValidUntilPresent="
					+ previousValidUntil.isPresent()
					+ ", toolsListChangedIncluded=" + toolsListChangedIncluded
					+ ", promptsListChangedIncluded=" + promptsListChangedIncluded
					+ ", resourcesListChangedIncluded=" + resourcesListChangedIncluded
					+ ", resourceSubscriptionUriCount="
					+ resourceSubscriptionUris.size() + ", taskIdCount="
					+ taskIds.size() + "}";
		}
	}

	@ThreadSafe
	private static final class DerivedSubscriptionRequestContext
			implements McpRequestContext {
		@NonNull private final McpRequestContext initial;
		@NonNull private final String jsonRpcMethod;
		@NonNull private final Request request;
		private final com.soklet.@NonNull McpAdmissionIdentity admissionIdentity;

		private DerivedSubscriptionRequestContext(
				@NonNull McpRequestContext initial,
				@NonNull String jsonRpcMethod,
				@NonNull Optional<@NonNull Object> applicationContext) {
			this.initial = requireNonNull(initial);
			this.jsonRpcMethod = requireNonNull(jsonRpcMethod);
			this.request = Request.fromPath(HttpMethod.POST,
					initial.getEndpoint().getPath());
			com.soklet.McpAdmissionIdentity original = initial.getAdmissionIdentity();
			com.soklet.McpAdmissionIdentity.Builder builder =
					com.soklet.McpAdmissionIdentity
					.withRateLimitPartitionKey(original.getRateLimitPartitionKey());
			original.getAuthorizationPartitionKey().ifPresent(
					builder::authorizationPartitionKey);
			original.getPrincipal().ifPresent(builder::principal);
			requireNonNull(applicationContext).ifPresent(builder::applicationContext);
			this.admissionIdentity = builder.build();
		}

		@Override public @NonNull Request getRequest() { return request; }
		@Override public @NonNull McpEndpoint getEndpoint() {
			return initial.getEndpoint();
		}
		@Override public @NonNull Map<@NonNull String, @NonNull String>
		getEndpointPathParameters() {
			return initial.getEndpointPathParameters();
		}
		@Override public @NonNull String getJsonRpcMethod() {
			return jsonRpcMethod;
		}
		@Override public @NonNull Optional<@NonNull McpRequestId> getRequestId() {
			return Optional.empty();
		}
		@Override public com.soklet.@NonNull McpProtocolVersion getProtocolVersion() {
			return initial.getProtocolVersion();
		}
		@Override public @NonNull Optional<@NonNull String> getOperationName() {
			return Optional.empty();
		}
		@Override public @NonNull Optional<@NonNull McpImplementation> getClientInfo() {
			return initial.getClientInfo();
		}
		@Override public com.soklet.@NonNull McpClientCapabilities
		getClientCapabilities() {
			return initial.getClientCapabilities();
		}
		@Override public com.soklet.@NonNull McpJsonObject getRequestMetadata() {
			return com.soklet.McpJsonObject.emptyInstance();
		}
		@Override public @NonNull McpInputResponses getInputResponses() {
			return McpInputResponses.emptyInstance();
		}
		@Override public @NonNull Optional<com.soklet.@NonNull McpJsonValue>
		getFrameworkRequestState() {
			return Optional.empty();
		}
		@Override public @NonNull Optional<@NonNull String>
		getApplicationRequestState() {
			return Optional.empty();
		}
		@Override public @NonNull Optional<@NonNull TraceContext> getTraceContext() {
			return Optional.empty();
		}
		@Override public @NonNull Map<@NonNull String, @NonNull String> getBaggage() {
			return Map.of();
		}
		@Override public com.soklet.@NonNull McpAdmissionIdentity
		getAdmissionIdentity() {
			return admissionIdentity;
		}
	}

	private enum SubscriptionOpenResult {
		OPENED,
		CAPACITY_REJECTED,
		SERVER_STOPPING,
		TERMINATED,
		AUTHORIZATION_STALE,
		CATALOG_STALE,
		CATALOG_PROJECTION_FAILED,
		LOCALIZATION_FAILED,
		TERMINAL_PREFLIGHT_FAILED
	}

	private enum SubscriptionActivationResult {
		ACTIVATED_CURRENT_LOCALIZATION,
		ACTIVATED_STALE_LOCALIZATION,
		AUTHORIZATION_STALE,
		CATALOG_STALE,
		NOT_ACTIVATED
	}

	private enum SubscriptionRegistrationResult {
		REGISTERED,
		CAPACITY_REJECTED,
		NOT_ACCEPTING
	}

	private record SubscriptionRegistrationAttempt(
			@NonNull SubscriptionRegistrationResult result,
			@Nullable SubscriptionRegistration registration) {
		private SubscriptionRegistrationAttempt {
			requireNonNull(result);
			if ((result == SubscriptionRegistrationResult.REGISTERED)
					!= (registration != null))
				throw new IllegalArgumentException(
						"Only a registered MCP subscription may retain registration state.");
		}
	}

	private record SubscriptionCapReservation(
			@NonNull SubscriptionOpenResult result,
			@Nullable SubscriptionRegistration registration) {
		private SubscriptionCapReservation {
			requireNonNull(result);
			boolean opened = result == SubscriptionOpenResult.OPENED;
			if (opened != (registration != null))
				throw new IllegalArgumentException(
						"Exactly an opened MCP subscription cap retains registration state.");
		}
	}

	private record SubscriptionOpenReservation(
			@NonNull SubscriptionOpenResult result,
			@Nullable McpRequestSseStream stream,
			@Nullable Consumer<@NonNull MicrohttpResponse> responseCallback,
			@Nullable SubscriptionRegistration registration) {
		private SubscriptionOpenReservation {
			requireNonNull(result);
			boolean opened = result == SubscriptionOpenResult.OPENED;
			boolean retainsOpenState = stream != null || responseCallback != null
					|| registration != null;
			if ((opened && (stream == null || responseCallback == null
					|| registration == null)) || (!opened && retainsOpenState))
				throw new IllegalArgumentException(
						"Only an opened MCP subscription may retain its response handoff.");
		}
	}

	private record SubscriptionStreamFailure(
			@NonNull McpRequestSseStream stream,
			@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		private SubscriptionStreamFailure {
			requireNonNull(stream);
			requireNonNull(reason);
		}
	}

	private record SubscriptionResource(@NonNull URI uri,
			@NonNull String wireUri) {
		private SubscriptionResource {
			requireNonNull(uri);
			requireNonNull(wireUri);
		}
	}

	private record AcceptedSubscriptionFilter(boolean toolsListChanged,
			boolean promptsListChanged, boolean resourcesListChanged,
			boolean resourceSubscriptionsIncluded,
			@NonNull Map<@NonNull URI, @NonNull SubscriptionResource>
					resourceSubscriptions,
			boolean taskIdsRequested,
			@NonNull List<@NonNull String> requestedTaskIds,
			@NonNull Set<@NonNull String> acceptedTaskIds,
			@NonNull McpClientCapabilities clientCapabilities) {
		private AcceptedSubscriptionFilter {
			resourceSubscriptions = Collections.unmodifiableMap(
					new LinkedHashMap<>(requireNonNull(resourceSubscriptions)));
			requestedTaskIds = List.copyOf(requireNonNull(requestedTaskIds));
			acceptedTaskIds = Collections.unmodifiableSet(
					new LinkedHashSet<>(requireNonNull(acceptedTaskIds)));
			requireNonNull(clientCapabilities);
			if (!requestedTaskIds.containsAll(acceptedTaskIds))
				throw new IllegalArgumentException(
						"Accepted task subscription IDs must have been requested.");
		}

		@NonNull
		private AcceptedSubscriptionFilter withAcceptedTaskIds(
				@NonNull List<@NonNull String> acceptedTaskIds) {
			return new AcceptedSubscriptionFilter(toolsListChanged,
					promptsListChanged, resourcesListChanged,
					resourceSubscriptionsIncluded, resourceSubscriptions,
					taskIdsRequested, requestedTaskIds,
					new LinkedHashSet<>(requireNonNull(acceptedTaskIds)),
					clientCapabilities);
		}

		@NonNull
		private List<@NonNull URI> requestedResourceSubscriptionUris() {
			return List.copyOf(resourceSubscriptions.keySet());
		}

		private boolean contains(@NonNull URI resourceUri) {
			requireNonNull(resourceUri);
			return resourceSubscriptionsIncluded
					&& resourceSubscriptions.containsKey(resourceUri);
		}

		private boolean containsTask(@NonNull String taskId) {
			return acceptedTaskIds.contains(requireNonNull(taskId));
		}
	}

	private record SubscriptionRegistration(@NonNull String endpointPath,
			@NonNull McpNormalizedEndpoint endpoint,
			@NonNull McpEffectivePartition authorizationPartition,
			@NonNull McpJsonRpcId subscriptionId,
			@NonNull AcceptedSubscriptionFilter filter,
			@NonNull McpProtocolProfile protocolProfile,
			long openedAtNanos) {
		private SubscriptionRegistration {
			requireNonNull(endpointPath);
			requireNonNull(endpoint);
			requireNonNull(authorizationPartition);
			requireNonNull(subscriptionId);
			requireNonNull(filter);
			requireNonNull(protocolProfile);
		}
	}

	private record SubscriptionEventKey(@NonNull URI resourceUri) {
		@NonNull
		private static final Object RESOURCES_LIST_CHANGED = new Object();
		@NonNull
		private static final Object TOOLS_LIST_CHANGED = new Object();
		@NonNull
		private static final Object PROMPTS_LIST_CHANGED = new Object();

		private SubscriptionEventKey {
			requireNonNull(resourceUri);
		}
	}

	/**
	 * Per-subscription task IDs awaiting authoritative projection. Access is
	 * serialized by the owning request-control lock.
	 */
	static final class TaskNotificationProjectionQueue {
		private final int maximumTaskIds;
		@NonNull
		private final Map<@NonNull String, @NonNull TaskNotificationProjectionState>
				states;
		@NonNull
		private final ArrayDeque<@NonNull String> pendingTaskIds;
		private boolean jobOutstanding;
		private @Nullable TaskNotificationProjection active;

		TaskNotificationProjectionQueue() {
			this(MAXIMUM_TASK_SUBSCRIPTION_IDS);
		}

		TaskNotificationProjectionQueue(int maximumTaskIds) {
			if (maximumTaskIds < 1)
				throw new IllegalArgumentException(
						"Task-notification projection capacity must be positive.");
			this.maximumTaskIds = maximumTaskIds;
			this.states = new LinkedHashMap<>();
			this.pendingTaskIds = new ArrayDeque<>(maximumTaskIds);
		}

		/**
		 * Records a generation and reports whether the owner must submit its sole
		 * scheduler job.
		 */
		boolean request(@NonNull String taskId) {
			String requiredTaskId = requireNonNull(taskId);
			TaskNotificationProjectionState state = this.states.get(requiredTaskId);
			if (state == null) {
				if (this.states.size() >= this.maximumTaskIds)
					throw new IllegalStateException(
							"A task-notification projection exceeded its subscription filter bound.");
				state = new TaskNotificationProjectionState();
				this.states.put(requiredTaskId, state);
			}
			state.requestedGeneration++;
			if (!state.pending) {
				state.pending = true;
				this.pendingTaskIds.addLast(requiredTaskId);
			}
			if (this.jobOutstanding)
				return false;
			this.jobOutstanding = true;
			return true;
		}

		@Nullable
		TaskNotificationProjection poll() {
			if (!this.jobOutstanding)
				throw new IllegalStateException(
						"A task-notification projection job is not outstanding.");
			if (this.active != null)
				throw new IllegalStateException(
						"A task-notification projection is already active.");
			String taskId = this.pendingTaskIds.pollFirst();
			if (taskId == null) {
				this.jobOutstanding = false;
				return null;
			}
			TaskNotificationProjectionState state = requireNonNull(
					this.states.get(taskId));
			state.pending = false;
			this.active = new TaskNotificationProjection(taskId, state,
					state.requestedGeneration);
			return this.active;
		}

		boolean owns(@NonNull TaskNotificationProjection projection) {
			TaskNotificationProjection requiredProjection =
					requireNonNull(projection);
			return this.active == requiredProjection
					&& this.states.get(requiredProjection.taskId())
					== requiredProjection.state();
		}

		/**
		 * Finishes one task and reports whether the owner must re-enter the shared
		 * scheduler at its tail.
		 */
		boolean finish(@NonNull TaskNotificationProjection projection,
				boolean ownerActive) {
			TaskNotificationProjection requiredProjection =
					requireNonNull(projection);
			// An old worker can finish after reset. It must not clear newer state.
			if (!owns(requiredProjection))
				return false;
			if (!ownerActive) {
				reset();
				return false;
			}
			this.active = null;
			TaskNotificationProjectionState state = requiredProjection.state();
			if (state.requestedGeneration != requiredProjection.generation()
					&& !state.pending) {
				state.pending = true;
				this.pendingTaskIds.addLast(requiredProjection.taskId());
			}
			if (this.pendingTaskIds.isEmpty()) {
				this.jobOutstanding = false;
				return false;
			}
			return true;
		}

		void reset() {
			for (TaskNotificationProjectionState state : this.states.values()) {
				state.pending = false;
				state.lastDelivery = null;
				state.terminalDelivered = false;
			}
			this.states.clear();
			this.pendingTaskIds.clear();
			this.active = null;
			this.jobOutstanding = false;
		}

		int pendingTaskIdCount() {
			return this.pendingTaskIds.size();
		}

		boolean jobOutstanding() {
			return this.jobOutstanding;
		}

		void deferOutstandingJob() {
			if (!this.jobOutstanding)
				throw new IllegalStateException(
						"A task-notification projection job is not outstanding.");
			if (this.active != null)
				throw new IllegalStateException(
						"An active task-notification projection cannot be deferred.");
			this.jobOutstanding = false;
		}
	}

	static final class TaskNotificationProjection {
		@NonNull
		private final String taskId;
		@NonNull
		private final TaskNotificationProjectionState state;
		private final long generation;

		TaskNotificationProjection(@NonNull String taskId,
				@NonNull TaskNotificationProjectionState state,
				long generation) {
			this.taskId = requireNonNull(taskId);
			this.state = requireNonNull(state);
			if (generation < 1L)
				throw new IllegalArgumentException(
						"A task-notification projection generation must be positive.");
			this.generation = generation;
		}

		@NonNull
		String taskId() {
			return this.taskId;
		}

		@NonNull
		TaskNotificationProjectionState state() {
			return this.state;
		}

		long generation() {
			return this.generation;
		}
	}

	private static final class TaskNotificationProjectionState {
		private long requestedGeneration;
		private boolean pending;
		private boolean terminalDelivered;
		private @Nullable TaskNotificationDeliveryState lastDelivery;
	}

	/**
	 * Structural comparison of a delivered nonterminal status notification. The
	 * task ID is already the queue key; its persisted, non-wire origin is not a
	 * status update and must never be retained here. Completed/failed/canceled
	 * tasks instead keep only the absorbing terminal marker, not their output.
	 *
	 * <p>This is deliberately not a timestamp or hash comparison: same-timestamp
	 * changes and structurally equal maps must keep their delivery semantics.
	 * Input requests, metadata, and status messages remain variable-size. State
	 * is installed only after the stream accepts its output-bounded notification,
	 * with at most one such value per accepted task ID (at most 256); it does not
	 * keep the private origin or another copy of the encoded notification.
	 */
	private record TaskNotificationDeliveryState(@NonNull McpTaskStatus status,
			@NonNull Optional<@NonNull String> statusMessage,
			@NonNull Instant createdAt, @NonNull Instant lastUpdatedAt,
			@NonNull Optional<@NonNull Duration> timeToLive,
			@NonNull Optional<@NonNull Duration> pollInterval,
			@NonNull Map<@NonNull String, @NonNull McpInputRequest> inputRequests,
			com.soklet.@NonNull McpJsonObject metadata) {
		@NonNull
		private static TaskNotificationDeliveryState from(
				@NonNull TaskSnapshot snapshot) {
			com.soklet.McpTask task = requireNonNull(snapshot).task();
			return new TaskNotificationDeliveryState(task.getTaskStatus(),
					task.getTaskStatusMessage(), task.getCreatedAt(),
					task.getLastUpdatedAt(), task.getTimeToLive(),
					task.getPollInterval(), task.getInputRequests(), task.getMetadata());
		}
	}

	private record SubscriptionSourceGroup(
			@NonNull McpSubscriptionEventSource source,
			@NonNull Set<@NonNull String> endpointPaths,
			@NonNull List<@NonNull EndpointSubscriptionSource> endpointSources) {
		private SubscriptionSourceGroup {
			requireNonNull(source);
			endpointPaths = Set.copyOf(requireNonNull(endpointPaths));
			endpointSources = List.copyOf(requireNonNull(endpointSources));
		}
	}

	private record EndpointSubscriptionSource(@NonNull String endpointPath,
			McpSubscriptionEventSource.@NonNull Subscriber subscriber) {
		private EndpointSubscriptionSource {
			requireNonNull(endpointPath);
			requireNonNull(subscriber);
		}
	}

	private static final class MutableSubscriptionSourceGroup {
		@NonNull
		private final McpSubscriptionEventSource source;
		@NonNull
		private final Set<@NonNull String> endpointPaths;
		@NonNull
		private final List<@NonNull EndpointSubscriptionSource> endpointSources;

		private MutableSubscriptionSourceGroup(
				@NonNull McpSubscriptionEventSource source) {
			this.source = requireNonNull(source);
			this.endpointPaths = new LinkedHashSet<>();
			this.endpointSources = new ArrayList<>();
		}

		@NonNull
		private McpSubscriptionEventSource source() {
			return source;
		}

		@NonNull
		private Set<@NonNull String> endpointPaths() {
			return endpointPaths;
		}

		@NonNull
		private List<@NonNull EndpointSubscriptionSource> endpointSources() {
			return endpointSources;
		}
	}

	/** One nonblocking source-generation gate. */
	@ThreadSafe
	private static final class SubscriptionEventSourceGeneration {
		@NonNull
		private final AtomicBoolean active;

		private SubscriptionEventSourceGeneration() {
			this.active = new AtomicBoolean(true);
		}

		private boolean active() {
			return active.get();
		}

		private void deactivate() {
			active.set(false);
		}
	}

	/**
	 * Fences one source-generation listener without waiting for application
	 * publisher threads. Fan-out checks the same generation again while taking
	 * its internal subscription snapshot, so a callback that raced deactivation
	 * can touch only old request controls and never a restarted generation.
	 */
	@ThreadSafe
	private static final class SubscriptionEventListenerFence {
		@NonNull
		private final SubscriptionEventSourceGeneration generation;
		@NonNull
		private final Consumer<@NonNull Event> listener;

		private SubscriptionEventListenerFence(
				@NonNull SubscriptionEventSourceGeneration generation,
				@NonNull Consumer<@NonNull Event> listener) {
			this.generation = requireNonNull(generation);
			this.listener = requireNonNull(listener);
		}

		private void onEvent(@NonNull Event event) {
			requireNonNull(event);
			if (generation.active())
				listener.accept(event);
		}

		private void deactivate() {
			generation.deactivate();
		}
	}

	/** One fenced, independently closable application registration. */
	@ThreadSafe
	private final class SubscriptionSourceRegistrationControl {
		@NonNull
		private final Object lock;
		@NonNull
		private final Registration registration;
		@NonNull
		private final SubscriptionEventListenerFence listenerFence;
		private boolean closed;
		private @Nullable SubscriptionRegistrationCloseAttempt closeAttempt;
		private @Nullable Throwable lastCloseFailure;

		private SubscriptionSourceRegistrationControl(
				@NonNull Registration registration,
				@NonNull SubscriptionEventListenerFence listenerFence) {
			this.lock = new Object();
			this.registration = requireNonNull(registration);
			this.listenerFence = requireNonNull(listenerFence);
		}

		private void deactivateListener() {
			listenerFence.deactivate();
		}

		@NonNull
		private SubscriptionRegistrationCloseAttempt beginClose(long sequence) {
			synchronized (lock) {
				if (closed)
					return SubscriptionRegistrationCloseAttempt.completedSuccessfully();
				if (closeAttempt != null && !closeAttempt.completed())
					return closeAttempt;

				SubscriptionRegistrationCloseAttempt attempt =
						new SubscriptionRegistrationCloseAttempt();
				closeAttempt = attempt;
				try {
					Thread closeThread = new Thread(
							() -> runClose(attempt),
							"soklet-mcp-subscription-source-close-" + sequence);
					closeThread.setDaemon(true);
					attempt.bindWorker(closeThread);
					closeThread.start();
				} catch (Throwable failure) {
					lastCloseFailure = failure;
					attempt.complete();
				}
				return attempt;
			}
		}

		private void runClose(
				@NonNull SubscriptionRegistrationCloseAttempt attempt) {
			Throwable failure = null;
			enterLifecycleProofExecution();
			try {
				registration.close();
			} catch (Throwable throwable) {
				failure = throwable;
			} finally {
				try {
					synchronized (lock) {
						if (failure == null) {
							closed = true;
							lastCloseFailure = null;
						} else {
							lastCloseFailure = failure;
						}
					}
				} finally {
					try {
						exitLifecycleProofExecution();
					} finally {
						attempt.complete();
					}
				}
			}
		}

		private boolean closed() {
			synchronized (lock) {
				return closed;
			}
		}

		private @Nullable Throwable latestCloseFailure() {
			synchronized (lock) {
				return lastCloseFailure;
			}
		}
	}

	@ThreadSafe
	private static final class SubscriptionRegistrationCloseAttempt {
		@NonNull
		private final CountDownLatch completion;
		@NonNull
		private final AtomicReference<@Nullable Thread> worker;
		@NonNull
		private final AtomicBoolean cancellationRequested;

		private SubscriptionRegistrationCloseAttempt() {
			this.completion = new CountDownLatch(1);
			this.worker = new AtomicReference<>();
			this.cancellationRequested = new AtomicBoolean();
		}

		@NonNull
		private static SubscriptionRegistrationCloseAttempt
				completedSuccessfully() {
			SubscriptionRegistrationCloseAttempt attempt =
					new SubscriptionRegistrationCloseAttempt();
			attempt.complete();
			return attempt;
		}

		private void complete() {
			completion.countDown();
		}

		private void bindWorker(@NonNull Thread worker) {
			Thread requiredWorker = requireNonNull(worker);
			if (!this.worker.compareAndSet(null, requiredWorker))
				throw new IllegalStateException(
						"A subscription close attempt already has a worker.");
			if (cancellationRequested.get())
				requiredWorker.interrupt();
		}

		private void cancel() {
			cancellationRequested.set(true);
			Thread activeWorker = worker.get();
			if (activeWorker != null)
				activeWorker.interrupt();
		}

		private boolean await(long timeoutNanos) throws InterruptedException {
			return completion.await(timeoutNanos, TimeUnit.NANOSECONDS);
		}

		private boolean completed() {
			return completion.getCount() == 0L;
		}
	}

	private record SubscriptionRegistrationCloseBatch(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					registrations,
			@NonNull List<@NonNull SubscriptionRegistrationCloseAttempt>
					closeAttempts) {
		private SubscriptionRegistrationCloseBatch {
			registrations = List.copyOf(requireNonNull(registrations));
			closeAttempts = List.copyOf(requireNonNull(closeAttempts));
		}
	}

	private record SubscriptionRegistrationCloseOutcome(
			@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
					residualRegistrations) {
		private SubscriptionRegistrationCloseOutcome {
			residualRegistrations = List.copyOf(
					requireNonNull(residualRegistrations));
		}
	}

	private record ProfileFrameworkResponses(@NonNull McpWireResult discovery,
			@NonNull Optional<@NonNull McpWireResult> toolsList,
			@NonNull Optional<@NonNull McpWireResult> promptsList,
			@NonNull McpWireResult resourcesList,
			@NonNull McpWireResult resourceTemplatesList) {
		private ProfileFrameworkResponses {
			requireNonNull(discovery);
			requireNonNull(toolsList);
			requireNonNull(promptsList);
			requireNonNull(resourcesList);
			requireNonNull(resourceTemplatesList);
		}
	}

	private record CanonicalCatalogKey(@NonNull String endpointPath,
			@NonNull String profileRevision,
			McpRuntimeCatalogLocalizer.@NonNull ResponseKind responseKind) {
		private CanonicalCatalogKey {
			requireNonNull(endpointPath);
			requireNonNull(profileRevision);
			requireNonNull(responseKind);
		}
	}

	private record CatalogAccessProjection(
			@NonNull CatalogAccessSession session,
			@NonNull Set<@NonNull String> accessibleToolNames,
			@NonNull Set<@NonNull String> accessiblePromptNames,
			boolean directAccessible) {
		private CatalogAccessProjection {
			requireNonNull(session);
			accessibleToolNames = Set.copyOf(
					requireNonNull(accessibleToolNames));
			accessiblePromptNames = Set.copyOf(
					requireNonNull(accessiblePromptNames));
		}
	}

	private record EndpointRuntime(@NonNull McpHttpEndpointBinding binding,
			@NonNull Map<@NonNull String, @NonNull McpServerCapabilityRegistry>
					capabilityRegistriesByRevision,
			@NonNull Map<@NonNull String, @NonNull ProfileFrameworkResponses>
					frameworkResponsesByRevision,
			@NonNull Map<@NonNull String, @NonNull McpApplicationRequestRouter>
					resourceRoutersByRevision,
			@NonNull Map<String, Map<McpLegacyCatalogPager.Kind, McpLegacyCatalogPager>>
					pagersByRevision) {
		private EndpointRuntime {
			requireNonNull(binding);
			capabilityRegistriesByRevision = Map.copyOf(
					requireNonNull(capabilityRegistriesByRevision));
			frameworkResponsesByRevision = Map.copyOf(
					requireNonNull(frameworkResponsesByRevision));
			resourceRoutersByRevision = Map.copyOf(requireNonNull(resourceRoutersByRevision));
			pagersByRevision = Map.copyOf(requireNonNull(pagersByRevision));
		}

		@NonNull
		private McpServerCapabilityRegistry capabilityRegistry(
				@NonNull String revision) {
			McpServerCapabilityRegistry registry = this.capabilityRegistriesByRevision
					.get(requireNonNull(revision));
			if (registry == null)
				throw new IllegalStateException(
						"No MCP capability view exists for the selected revision.");
			return registry;
		}

		@NonNull
		private String path() {
			return this.binding.endpointPolicy().path();
		}

		@NonNull
		private ProfileFrameworkResponses frameworkResponses(
				@NonNull McpProtocolProfile profile) {
			ProfileFrameworkResponses responses = this.frameworkResponsesByRevision
					.get(requireNonNull(profile).revision());
			if (responses == null)
				throw new IllegalStateException(
						"No precomputed framework responses exist for the selected MCP profile.");
			return responses;
		}
	}

	@ThreadSafe
	final class SimulationSession implements AutoCloseable {
		@NonNull
		private final AtomicReference<@Nullable SimulationGeneration> generation;
		@NonNull
		private final AtomicBoolean closed;
		@NonNull
		private final AtomicBoolean compatibilityCloseClaimed;

		private SimulationSession(@NonNull SimulationGeneration generation) {
			this.generation = new AtomicReference<>(requireNonNull(generation));
			this.closed = new AtomicBoolean();
			this.compatibilityCloseClaimed = new AtomicBoolean();
		}

		@NonNull
		McpSimulation start(@NonNull Request request,
				@NonNull McpSimulationOptions options) {
			SimulationGeneration activeGeneration = this.generation.get();
			if (closed.get() || activeGeneration == null)
				throw new IllegalStateException(SIMULATION_REQUIRES_STOPPED_SERVER);
			McpSimulationRuntime simulation = new McpSimulationRuntime(
					requireNonNull(options),
					activeGeneration::removeCompletedSimulations);
			synchronized (activeGeneration.simulations) {
				if (closed.get() || this.generation.get() != activeGeneration
						|| activeGeneration.closing())
					throw new IllegalStateException(SIMULATION_REQUIRES_STOPPED_SERVER);
				activeGeneration.simulations.add(simulation);
				try {
					RequestControl control = submitSimulationRequest(activeGeneration,
							requireNonNull(request), simulation);
					simulation.bindController(control::cancelFromSimulation);
					return simulation;
				} catch (RuntimeException | Error failure) {
					activeGeneration.simulations.remove(simulation);
					throw failure;
				}
			}
		}

		void quiesce() {
			this.closed.set(true);
			SimulationGeneration activeGeneration = this.generation.get();
			if (activeGeneration != null)
				quiesceSimulationGeneration(activeGeneration);
		}

		void force() {
			this.closed.set(true);
			SimulationGeneration activeGeneration = this.generation.get();
			if (activeGeneration != null)
				forceSimulationGeneration(activeGeneration);
		}

		boolean awaitTermination(long absoluteDeadlineNanos,
				@NonNull LongSupplier nanoTime) throws InterruptedException {
			SimulationGeneration activeGeneration = this.generation.get();
			return activeGeneration == null
					|| awaitSimulationGenerationTermination(activeGeneration,
							absoluteDeadlineNanos, requireNonNull(nanoTime));
		}

		boolean terminationProven() {
			SimulationGeneration activeGeneration = this.generation.get();
			return activeGeneration == null
					|| simulationGenerationBarrierComplete(activeGeneration);
		}

		@NonNull
		McpLifecycleEvidence lifecycleEvidence() {
			SimulationGeneration activeGeneration = this.generation.get();
			return activeGeneration == null
					? new McpLifecycleEvidence(false, false, false, false,
							false, false)
					: simulationLifecycleEvidence(activeGeneration);
		}

		void releaseLifecycleEvidence() {
			SimulationGeneration activeGeneration = this.generation.get();
			if (activeGeneration == null)
				return;
			releaseSimulationGenerationEvidence(activeGeneration);
			this.generation.compareAndSet(activeGeneration, null);
		}

		@Override
		public void close() {
			this.closed.set(true);
			if (!this.compatibilityCloseClaimed.compareAndSet(false, true))
				return;
			SimulationGeneration activeGeneration = this.generation.get();
			if (activeGeneration == null)
				return;
			try {
				closeSimulationGeneration(activeGeneration);
			} finally {
				this.generation.compareAndSet(activeGeneration, null);
			}
		}
	}

	@ThreadSafe
	private static final class SimulationGeneration {
		@NonNull
		private final ThreadPoolExecutor processor;
		@NonNull
		private final McpApplicationExecution application;
		@NonNull
		private final InetSocketAddress effectiveAddress;
		@NonNull
		private final List<@NonNull SubscriptionSourceRegistrationControl>
				registrations;
		@NonNull
		private final Set<@NonNull McpSimulationRuntime> simulations;
		@NonNull
		private final AtomicBoolean closing;
		@NonNull
		private final AtomicBoolean quiesced;
		@NonNull
		private final AtomicBoolean forced;
		@NonNull
		private final AtomicBoolean evidenceReleased;
		private @Nullable SubscriptionRegistrationCloseBatch closeBatch;

		private SimulationGeneration(@NonNull ThreadPoolExecutor processor,
				@NonNull McpApplicationExecution application,
				@NonNull InetSocketAddress effectiveAddress,
				@NonNull List<@NonNull SubscriptionSourceRegistrationControl>
						registrations) {
			this.processor = requireNonNull(processor);
			this.application = requireNonNull(application);
			this.effectiveAddress = requireNonNull(effectiveAddress);
			this.registrations = requireNonNull(registrations);
			this.simulations = Collections.synchronizedSet(
					Collections.newSetFromMap(new IdentityHashMap<>()));
			this.closing = new AtomicBoolean();
			this.quiesced = new AtomicBoolean();
			this.forced = new AtomicBoolean();
			this.evidenceReleased = new AtomicBoolean();
		}

		@NonNull
		private ThreadPoolExecutor processor() {
			return this.processor;
		}

		@NonNull
		private McpApplicationExecution application() {
			return this.application;
		}

		@NonNull
		private InetSocketAddress effectiveAddress() {
			return this.effectiveAddress;
		}

		@NonNull
		private List<@NonNull SubscriptionSourceRegistrationControl>
		registrations() {
			return List.copyOf(this.registrations);
		}

		private void removeCompletedSimulations() {
			synchronized (this.simulations) {
				this.simulations.removeIf(simulation -> simulation.isComplete());
			}
		}

		@NonNull
		private List<@NonNull McpSimulationRuntime> activeSimulations() {
			synchronized (this.simulations) {
				return List.copyOf(this.simulations);
			}
		}

		private void beginClosing() {
			synchronized (this.simulations) {
				this.closing.set(true);
			}
		}

		private boolean closing() {
			return this.closing.get();
		}

		private boolean claimQuiesce() {
			return this.quiesced.compareAndSet(false, true);
		}

		private boolean claimForce() {
			return this.forced.compareAndSet(false, true);
		}

		private boolean forced() {
			return this.forced.get();
		}

		private @Nullable SubscriptionRegistrationCloseBatch closeBatch() {
			return this.closeBatch;
		}

		private void mergeCloseBatch(
				@NonNull SubscriptionRegistrationCloseBatch next) {
			SubscriptionRegistrationCloseBatch requiredNext = requireNonNull(next);
			if (this.closeBatch == null) {
				this.closeBatch = requiredNext;
				return;
			}
			Set<SubscriptionSourceRegistrationControl> seenRegistrations =
					Collections.newSetFromMap(new IdentityHashMap<>());
			List<SubscriptionSourceRegistrationControl> registrations =
					new ArrayList<>();
			for (SubscriptionSourceRegistrationControl registration
					: this.closeBatch.registrations()) {
				if (seenRegistrations.add(registration))
					registrations.add(registration);
			}
			for (SubscriptionSourceRegistrationControl registration
					: requiredNext.registrations()) {
				if (seenRegistrations.add(registration))
					registrations.add(registration);
			}
			Set<SubscriptionRegistrationCloseAttempt> seenAttempts =
					Collections.newSetFromMap(new IdentityHashMap<>());
			List<SubscriptionRegistrationCloseAttempt> attempts = new ArrayList<>();
			for (SubscriptionRegistrationCloseAttempt attempt
					: this.closeBatch.closeAttempts()) {
				if (seenAttempts.add(attempt))
					attempts.add(attempt);
			}
			for (SubscriptionRegistrationCloseAttempt attempt
					: requiredNext.closeAttempts()) {
				if (seenAttempts.add(attempt))
					attempts.add(attempt);
			}
			this.closeBatch = new SubscriptionRegistrationCloseBatch(
					registrations, attempts);
		}

		private boolean evidenceReleased() {
			return this.evidenceReleased.get();
		}

		private void markEvidenceReleased() {
			this.evidenceReleased.set(true);
		}
	}

	private enum LifecycleState {
		STOPPED,
		STARTING,
		STARTED,
		STOPPING,
		FAILED
	}

	private enum ListenerState {
		STARTING,
		READY,
		TERMINATED
	}

	private record ProtocolDeadlineExpiration(
			@Nullable FutureTask<@Nullable Void> task,
			@NonNull Consumer<@NonNull MicrohttpResponse> responseCallback,
			@NonNull List<@NonNull Header> responseHeaders,
			@Nullable SubscriptionRegistration subscriptionCapReservation,
			@Nullable SubscriptionAuthorizationCheck authorizationCheck,
			@Nullable CatalogProjectionCheck catalogCheck) {
		private ProtocolDeadlineExpiration {
			requireNonNull(responseCallback);
			responseHeaders = List.copyOf(responseHeaders);
		}

		/** @return bounded rendering that excludes callbacks and response data */
		@Override
		@NonNull
		public String toString() {
			return "ProtocolDeadlineExpiration{taskPresent=" + (task != null)
					+ ", responseHeaderCount=" + responseHeaders.size()
					+ ", subscriptionCapReservationPresent="
					+ (subscriptionCapReservation != null)
					+ ", authorizationCheckPresent="
					+ (authorizationCheck != null)
					+ ", catalogCheckPresent=" + (catalogCheck != null) + "}";
		}
	}

	private record ProtocolProcessingReservation(boolean allowed,
			@Nullable ProtocolDeadlineExpiration deadlineExpiration) {
		private ProtocolProcessingReservation {
			if (allowed == (deadlineExpiration != null))
				throw new IllegalArgumentException(
						"A processing reservation must allow work or own a deadline.");
		}
	}

	private record ProtocolResponseReservation(
			@Nullable Consumer<@NonNull MicrohttpResponse> responseCallback,
			@Nullable MicrohttpResponse response,
			@Nullable ProtocolDeadlineExpiration deadlineExpiration) {
		private ProtocolResponseReservation {
			if ((responseCallback == null) != (response == null))
				throw new IllegalArgumentException(
						"A protocol response and its callback must be reserved together.");
			if (deadlineExpiration != null && response != null)
				throw new IllegalArgumentException(
						"A protocol response and deadline cannot both be reserved.");
		}
	}

	private record ProtocolSubmission(
			@Nullable Consumer<@NonNull MicrohttpResponse> rejectedCallback) {
	}

	private record RequestObservationTerminal(@NonNull McpRequestOutcome outcome,
			@Nullable McpJsonRpcError error, long finishedAtNanos,
			@NonNull List<@NonNull Throwable> throwables) {
		private RequestObservationTerminal {
			requireNonNull(outcome);
			throwables = List.copyOf(requireNonNull(throwables));
		}
	}

	private record StreamObservationOpenTransition(
			@Nullable McpRuntimeRequestObservation observation,
			boolean requestStreamOpened, boolean subscriptionOpened) {
	}

	private record RequestObservationResult(@NonNull McpRequestOutcome outcome,
			@Nullable McpJsonRpcError error,
			@NonNull List<@NonNull Throwable> throwables) {
		private RequestObservationResult {
			requireNonNull(outcome);
			throwables = List.copyOf(requireNonNull(throwables));
		}
	}

	private record ApplicationResponseRendering(
			@NonNull MicrohttpResponse response,
			@NonNull RequestObservationResult observationResult) {
		private ApplicationResponseRendering {
			requireNonNull(response);
			requireNonNull(observationResult);
		}
	}

	private record HostAuthority(@NonNull String host,
			@NonNull Optional<@NonNull Integer> port) {
		private HostAuthority {
			requireNonNull(host);
			requireNonNull(port);
		}
	}

	private record CorsAuthorization(
			@NonNull Optional<@NonNull CorsResponse> response,
			@NonNull Optional<@NonNull MicrohttpResponse> rejection) {
		private CorsAuthorization {
			requireNonNull(response);
			requireNonNull(rejection);
			if (response.isPresent() && rejection.isPresent())
				throw new IllegalArgumentException(
						"CORS authorization cannot both accept and reject.");
		}

		@NonNull
		private static CorsAuthorization withoutOrigin() {
			return new CorsAuthorization(Optional.empty(), Optional.empty());
		}

		@NonNull
		private static CorsAuthorization accepted(@NonNull CorsResponse response) {
			return new CorsAuthorization(Optional.of(response), Optional.empty());
		}

		@NonNull
		private static CorsAuthorization rejected(@NonNull MicrohttpResponse response) {
			return new CorsAuthorization(Optional.empty(), Optional.of(response));
		}
	}
}
