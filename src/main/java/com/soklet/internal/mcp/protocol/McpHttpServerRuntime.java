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
import com.soklet.Cors;
import com.soklet.CorsPreflight;
import com.soklet.CorsPreflightResponse;
import com.soklet.CorsResponse;
import com.soklet.HttpMethod;
import com.soklet.MediaRange;
import com.soklet.MetricsCollector.TransportFailureReason;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpRequestStateMode;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationOptions;
import com.soklet.McpStreamTerminationReason;
import com.soklet.Request;
import com.soklet.StatusCode;
import com.soklet.StreamTerminationReason;
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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RejectedExecutionException;
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
	@NonNull
	private static final String MCP_METHOD = "Mcp-Method";
	@NonNull
	private static final String MCP_NAME = "Mcp-Name";
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
	private boolean subscriptionsAccepting;
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
				McpProductionProtocolProfiles.REGISTRY,
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
				McpProductionProtocolProfiles.REGISTRY, lifecycleAdapter);
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
		this.mirroredHeaderCodec = new McpMirroredHeaderCodec(Math.min(
				McpMirroredHeaderCodec.DEFAULT_MAXIMUM_DECODED_BYTES,
				transportConfiguration.maximumHeaderBytes()));
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
		for (String endpointPath : this.endpointsByPath.keySet())
			this.localizationInvalidationTokens.put(endpointPath, new Object());
		this.subscriptionsAccepting = false;
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
			McpServerCapabilityRegistry capabilityRegistry =
					McpServerCapabilityRegistry.fromEndpoint(binding.endpoint(),
							endpointPolicy.catalogLocalizer()
									.map(McpRuntimeCatalogLocalizer
											::localizedResponseKinds)
									.orElseGet(Set::of), this.protocolProfiles);
			EndpointRuntime endpointRuntime = new EndpointRuntime(binding,
					capabilityRegistry,
					profileFrameworkResponses(capabilityRegistry));
			if (endpointsByPath.putIfAbsent(endpointRuntime.path(), endpointRuntime)
					!= null)
				throw new IllegalArgumentException("Duplicate MCP HTTP endpoint path '"
						+ endpointRuntime.path() + "'.");
		}
		return Collections.unmodifiableMap(endpointsByPath);
	}

	@NonNull
	private Map<@NonNull String, @NonNull ProfileFrameworkResponses>
	profileFrameworkResponses(
			@NonNull McpServerCapabilityRegistry capabilityRegistry) {
		Map<String, ProfileFrameworkResponses> responses = new LinkedHashMap<>();
		for (McpProtocolProfile profile : this.protocolProfiles.profiles()) {
			ProfileFrameworkResponses rendered = new ProfileFrameworkResponses(
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.DISCOVERY,
							capabilityRegistry.discoverResult().toWireResult()),
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.TOOLS_LIST,
							capabilityRegistry.toolsListResult()),
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.PROMPTS_LIST,
							capabilityRegistry.promptsListResult()),
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.RESOURCES_LIST,
							capabilityRegistry.resourcesListResult()),
					profile.renderFrameworkResult(
							McpProfileFrameworkResultKind.RESOURCE_TEMPLATES_LIST,
							capabilityRegistry.resourceTemplatesListResult()));
			if (responses.putIfAbsent(profile.revision(), rendered) != null)
				throw new IllegalStateException(
						"Duplicate precomputed MCP profile revision.");
		}
		return Collections.unmodifiableMap(responses);
	}

	@NonNull
	private List<@NonNull SubscriptionSourceGroup> subscriptionSourceGroups() {
		Map<Object, MutableSubscriptionSourceGroup> groupsByIdentity =
				new IdentityHashMap<>();
		List<MutableSubscriptionSourceGroup> groupsInEndpointOrder =
				new ArrayList<>();
		for (EndpointRuntime endpointRuntime : this.endpointsByPath.values()) {
			endpointRuntime.binding().subscriptionEventSource().ifPresent(source -> {
				MutableSubscriptionSourceGroup group =
						groupsByIdentity.get(source.identity());
				if (group == null) {
					group = new MutableSubscriptionSourceGroup(source);
					groupsByIdentity.put(source.identity(), group);
					groupsInEndpointOrder.add(group);
				}
				group.endpointPaths().add(endpointRuntime.path());
				if (source.endpointSubscriber().isPresent())
					group.endpointSources().add(new EndpointSubscriptionSource(
							endpointRuntime.path(),
							source.endpointSubscriber().orElseThrow()));
			});
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
			McpNormalizedEndpoint endpoint = endpointRuntime.binding().endpoint();
			McpServerCapabilityRegistry capabilityRegistry =
					endpointRuntime.capabilityRegistry();
			for (McpProtocolProfile profile : this.protocolProfiles.profiles()) {
				ProfileFrameworkResponses responses =
						endpointRuntime.frameworkResponses(profile);
				preflightFrameworkOwnedResponse(endpointRuntime.path(),
						profile.revision(), "server/discover", responses.discovery());
				if (!capabilityRegistry.tools().isEmpty())
					preflightFrameworkOwnedResponse(endpointRuntime.path(),
							profile.revision(), "tools/list", responses.toolsList());
				if (!capabilityRegistry.prompts().isEmpty())
					preflightFrameworkOwnedResponse(endpointRuntime.path(),
							profile.revision(), "prompts/list", responses.promptsList());
				if (capabilityRegistry.capabilities().resources().isPresent()) {
					if (!endpoint.customResourceListHandler())
						preflightFrameworkOwnedResponse(endpointRuntime.path(),
								profile.revision(), "resources/list",
								responses.resourcesList());
					preflightFrameworkOwnedResponse(endpointRuntime.path(),
							profile.revision(), "resources/templates/list",
							responses.resourceTemplatesList());
				}
				if (endpoint.subscriptions().isPresent()
						&& endpointRuntime.binding().subscriptionEventSource().isPresent())
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
			envelopeCodec.encode(new McpJsonRpcMessage.ResultResponse(
					new McpJsonRpcId.IntegerId(BigInteger.ZERO),
					requireNonNull(result), McpJsonObject.empty()));
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
				if (cleanupFailure != null && cleanupFailure != failure)
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
		if (first != requiredNext)
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
		synchronized (lifecycleLock) {
			if (requiredGeneration.evidenceReleased())
				return;
			if (simulationGeneration == requiredGeneration)
				simulationGeneration = null;
			if (residualSimulationRequestProcessor
					== requiredGeneration.processor())
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
			if (lifecycleAdapter.currentGeneration() != expectedGeneration)
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
					|| lifecycleStartupGeneration != expectedGeneration
					|| lifecycleAdapter.currentGeneration() != expectedGeneration)
				throw new IllegalStateException(
						"The MCP HTTP server has no matching prepared startup to claim.");
			lifecycleStartupClaimed = true;
			return lifecycleStartupGeneration;
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
				if (catchUpFailure != null && catchUpFailure != primary)
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
					if (cleanupFailure != primary)
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
		if (exactSecondary == null || exactSecondary == primary)
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
			if (closeFailure != null && closeFailure != failure)
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
			if (failure != first)
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
			if (first == null && failure != requireNonNull(startupPrimary))
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
	private ThreadPoolExecutor newRequestProcessor() {
		int concurrency = transportConfiguration.requestProcessorConcurrency();
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
				threadFactory,
				new ThreadPoolExecutor.AbortPolicy());
	}

	/** Runs the registered graceful handoff exactly once after protocol drain. */
	private static final class LifecycleRequestProcessor extends ThreadPoolExecutor {
		@NonNull
		private final AtomicReference<@Nullable Runnable> afterTermination;
		@NonNull
		private final AtomicBoolean terminationObserved;

		private LifecycleRequestProcessor(int corePoolSize, int maximumPoolSize,
				long keepAliveTime, @NonNull TimeUnit unit,
				@NonNull ArrayBlockingQueue<@NonNull Runnable> workQueue,
				@NonNull ThreadFactory threadFactory,
				@NonNull RejectedExecutionHandler handler) {
			super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
					threadFactory, handler);
			this.afterTermination = new AtomicReference<>();
			this.terminationObserved = new AtomicBoolean();
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

		private void runAfterTermination() {
			Runnable action = this.afterTermination.get();
			if (action != null && this.terminationObserved.compareAndSet(false, true))
				action.run();
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
								&& lifecycleStartupGeneration == lifecycleGeneration;
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
			if (eventLoop != terminatedEventLoop)
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
			if (eventLoop != terminatedEventLoop
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
			@Nullable InetSocketAddress effectiveAddress,
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
			MicrohttpResponse response = processRequest(requiredAddress, request,
					requestControl, application);
			requestControl.completeProtocol(response);
			return null;
		}) {
			@Override
			protected void done() {
				requestControl.protocolLifecycleWorkTerminated();
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
					.maximumSubscriptionsPerPrincipal())
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
			@Nullable Object expectedLocalizationInvalidationToken) {
		requireNonNull(control);
		requireNonNull(registration);
		synchronized (subscriptionLock) {
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
			for (String endpointPath : endpointPaths) {
				Set<RequestControl> endpointSubscriptions =
						activeSubscriptionsByEndpointPath.get(endpointPath);
				if (endpointSubscriptions != null)
					subscriptions.addAll(endpointSubscriptions);
			}
		}
		for (RequestControl subscription : subscriptions) {
			try {
				subscription.offerSubscriptionEvent(event);
			} catch (Throwable ignored) {
				// One subscriber can never alter publisher or peer delivery.
			}
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
		List<RequestControl> controls;
		synchronized (requestControls) {
			controls = List.copyOf(requestControls.values());
		}
		for (RequestControl control : controls) {
			try {
				control.onTimer(nowNanos);
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

		EndpointRuntime endpointRuntime =
				this.endpointsByPath.get(requestPath(request.uri()));
		if (endpointRuntime == null)
			return emptyResponse(404, "Not Found", List.of());
		McpHttpEndpointBinding endpointBinding = endpointRuntime.binding();
		McpHttpEndpointPolicy endpointPolicy = endpointBinding.endpointPolicy();
		McpNormalizedEndpoint endpoint = endpointBinding.endpoint();
		McpServerCapabilityRegistry capabilityRegistry =
				endpointRuntime.capabilityRegistry();
		McpApplicationRequestRouter applicationRouter =
				endpointBinding.applicationRouter();

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

		if (httpMethod.orElseThrow() != HttpMethod.POST)
			return methodNotAllowed(corsHeaders);

		MicrohttpResponse contentNegotiationFailure = contentNegotiationFailure(request,
				corsHeaders);
		if (contentNegotiationFailure != null)
			return contentNegotiationFailure;

		McpJsonRpcEnvelope envelope;
		try {
			envelope = envelopeCodec.decode(request.body());
		} catch (McpWireDecodingException exception) {
			return wireDecodingFailure(exception,
					exception.readableMethod().orElse(null), corsHeaders);
		}

		if (envelope instanceof McpJsonRpcEnvelope.Notification notification)
			return processNotification(request, sokletRequest, notification,
					corsHeaders, requestControl, endpointRuntime);

		if (!(envelope instanceof McpJsonRpcEnvelope.Request wireRequest))
			return jsonRpcError(400, "Bad Request", Optional.empty(),
					new McpJsonRpcError(McpJsonRpcError.INVALID_REQUEST,
							"Invalid Request", Optional.empty()), corsHeaders);

		boolean validatedUnsupportedSelector =
				validatedUnsupportedSelector(request);
		MicrohttpResponse mirroredHeaderFailure = validateRequiredMirroredHeaders(
				request, wireRequest, validatedUnsupportedSelector, corsHeaders);
		if (mirroredHeaderFailure != null)
			return mirroredHeaderFailure;

		McpCustomMirroredHeaderValidation customHeaderValidation =
				customMirroredHeaderValidator.validate(request.headers(), wireRequest,
						capabilityRegistry,
						endpointPolicy.unknownMirroredHeaderPolicy(),
						this.unknownMirroredHeaderNameDiagnostics.enabled());
		recordUnknownMirroredHeaders(endpointRuntime.path(), wireRequest.method(),
				customHeaderValidation.unknownHeaderCount());
		for (String unknownHeaderName : customHeaderValidation.unknownHeaderNames())
			this.unknownMirroredHeaderNameDiagnostics.observe(endpointRuntime.path(),
					unknownHeaderName);
		if (customHeaderValidation.outcome()
				== McpCustomMirroredHeaderOutcome.HEADER_MISMATCH)
			return headerMismatch(wireRequest.id(), wireRequest.method(),
					validatedUnsupportedSelector, corsHeaders);
		if (customHeaderValidation.outcome()
				== McpCustomMirroredHeaderOutcome.STRICT_UNKNOWN)
			return strictUnknownMirroredHeader(wireRequest.id(), wireRequest.method(),
					validatedUnsupportedSelector, corsHeaders);

		String headerProtocolVersion = singleHeader(request, MCP_PROTOCOL_VERSION)
				.orElseThrow();
		Optional<McpProtocolProfile> selectedProfile =
				this.protocolProfiles.resolve(headerProtocolVersion);
		if (selectedProfile.isEmpty()) {
			Optional<String> readableBodyProtocolVersion =
					readableBodyProtocolVersion(wireRequest);
			if (readableBodyProtocolVersion.isPresent()
					&& !headerProtocolVersion.equals(
							readableBodyProtocolVersion.orElseThrow()))
				return headerMismatch(wireRequest.id(), wireRequest.method(), true,
						corsHeaders);
			return jsonRpcError(400, "Bad Request", Optional.of(wireRequest.id()),
					McpJsonRpcError.unsupportedProtocolVersion(headerProtocolVersion,
							this.protocolProfiles.revisions()), corsHeaders);
		}
		McpProtocolProfile protocolProfile = selectedProfile.orElseThrow();
		requestControl.bindProtocolProfile(protocolProfile);

		McpJsonRpcMessage.Request mappedRequest;
		try {
			mappedRequest = protocolProfile
					.mapRequest(this.requestWireMapper, wireRequest);
		} catch (McpWireDecodingException exception) {
			return wireDecodingFailure(protocolProfile, exception,
					wireRequest.method(), corsHeaders);
		}

		if (!headerProtocolVersion.equals(mappedRequest.params().metadata().protocolVersion()))
			return headerMismatch(mappedRequest.id(), mappedRequest.method(), corsHeaders);

		String requestedProtocolVersion = protocolProfile.revision();

		if (!requestControl.identifyRequestExchange())
			return null;

		boolean discoveryRequest = "server/discover".equals(mappedRequest.method());
		boolean toolsListRequest = "tools/list".equals(mappedRequest.method());
		boolean promptsListRequest = "prompts/list".equals(mappedRequest.method());
		boolean resourcesListRequest = "resources/list".equals(mappedRequest.method());
		boolean resourceTemplatesListRequest =
				"resources/templates/list".equals(mappedRequest.method());
		boolean subscriptionListenRequest =
				"subscriptions/listen".equals(mappedRequest.method());
		Optional<String> operationName = Optional.empty();
		Optional<McpApplicationToolRoute> toolRoute = Optional.empty();
		Optional<McpApplicationPromptRoute> promptRoute = Optional.empty();
		Optional<McpApplicationRequestHandler> applicationHandler = Optional.empty();
		McpInputRequestPlan inputRequestPlan = McpInputRequestPlan.empty();
		McpRequestStateMode requestStateMode = McpRequestStateMode.NONE;
		McpJsonObject inputResponses = McpJsonObject.empty();
		boolean inputResponsesSupplied = false;
		Optional<String> suppliedRequestState = Optional.empty();
		Optional<AcceptedSubscriptionFilter> acceptedSubscriptionFilter =
				Optional.empty();

		if (discoveryRequest) {
			if (!mappedRequest.params().fields().members().isEmpty())
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if (toolsListRequest) {
			if (capabilityRegistry.tools().isEmpty())
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
			// The immutable catalog is one static page. Any parameter, including a
			// present empty cursor, is therefore invalid rather than interpreted.
			if (!mappedRequest.params().fields().members().isEmpty())
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if (promptsListRequest) {
			if (capabilityRegistry.prompts().isEmpty())
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
			// The immutable catalog is one static page. Any parameter, including a
			// present empty cursor, is therefore invalid rather than interpreted.
			if (!mappedRequest.params().fields().members().isEmpty())
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if (resourcesListRequest) {
			if (capabilityRegistry.capabilities().resources().isEmpty())
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
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
					return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
				Optional<String> resolvedCursor = cursor;
				McpApplicationResourceListRoute resolvedRoute = listRoute.orElseThrow();
				applicationHandler = Optional.of(invocation -> resourceResultWithCachePolicy(
							resolvedRoute.handler().handle(
									new McpApplicationResourceListInvocation(invocation,
											resolvedCursor,
											capabilityRegistry.exactResourceDescriptors(),
											endpoint.resourceListCachePolicy())),
							endpoint.resourceListCachePolicy(), true, false,
							endpoint.maximumCursorSizeInBytes(), applicationRouter));
			} else {
				// The framework-owned fallback is exactly one static page. Every
				// present cursor, including the empty string, is invalid.
				if (!mappedRequest.params().fields().members().isEmpty())
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
		} else if (resourceTemplatesListRequest) {
			if (capabilityRegistry.capabilities().resources().isEmpty())
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
			// Templates are always one framework-owned static page, including
			// the valid empty category of an exact-resource-only endpoint.
			if (!mappedRequest.params().fields().members().isEmpty())
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
		} else if (subscriptionListenRequest) {
			if (endpoint.subscriptions().isEmpty()
					|| endpointBinding.subscriptionEventSource().isEmpty())
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
			try {
				acceptedSubscriptionFilter = Optional.of(
						parseAcceptedSubscriptionFilter(mappedRequest,
								endpoint.subscriptions().orElseThrow(),
								endpointPolicy.catalogLocalizer()
										.map(McpRuntimeCatalogLocalizer
												::localizedResponseKinds)
										.orElseGet(Set::of)));
			} catch (IllegalArgumentException exception) {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
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
			McpJsonValue argumentsValue = fields.get("arguments");
			if (argumentsValue != null && !(argumentsValue instanceof McpJsonObject))
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);

			operationName = Optional.of(name.value());
			toolRoute = applicationRouter.resolveTool(name.value());
			if (applicationRouter.hasToolRoutes()) {
				if (toolRoute.isEmpty())
					return invalidParams(protocolProfile, mappedRequest, corsHeaders);
				McpApplicationToolRoute resolvedRoute = toolRoute.orElseThrow();
				applicationHandler = Optional.of(resolvedRoute.handler());
				inputRequestPlan = resolvedRoute.inputRequestPlan();
				requestStateMode = resolvedRoute.requestStateMode();
			} else {
				// Retain the package-private generic method route for existing runtime
				// tests while production registrations use exact immutable tool routes.
				applicationHandler = applicationRouter.resolve(mappedRequest.method());
			}
			if (applicationHandler.isEmpty())
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
		} else if ("prompts/get".equals(mappedRequest.method())) {
			Optional<McpApplicationRequestHandler> genericPromptHandler =
					applicationRouter.resolve(mappedRequest.method());
			if (capabilityRegistry.prompts().isEmpty()
					&& genericPromptHandler.isEmpty())
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);

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

			McpJsonValue argumentsValue = fields.get("arguments");
			if (capabilityRegistry.prompts().isEmpty()) {
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

			operationName = Optional.of(name.value());
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
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
		} else if ("resources/read".equals(mappedRequest.method())) {
			Optional<McpApplicationRequestHandler> genericResourceHandler =
					applicationRouter.resolve(mappedRequest.method());
			if (capabilityRegistry.capabilities().resources().isEmpty()
					&& genericResourceHandler.isEmpty()
					&& !applicationRouter.hasResourceReadRoutes())
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);

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
					if (templateMatch.isEmpty())
						return invalidResourceUriParams(protocolProfile, mappedRequest, uri, corsHeaders);
					McpApplicationResourceTemplateMatch match =
							templateMatch.orElseThrow();
					resolvedRoute = match.readRoute();
					templateVariables = match.templateVariables();
				}
				McpApplicationResourceReadRoute route = resolvedRoute;
				inputRequestPlan = route.inputRequestPlan();
				requestStateMode = route.requestStateMode();
				Map<String, String> variables = templateVariables;
				applicationHandler = Optional.of(invocation -> resourceResultWithCachePolicy(
						route.handler().handle(new McpApplicationResourceReadInvocation(
								invocation, uri, variables, route.cachePolicy())),
						route.cachePolicy(), false,
						resourceRetry,
						endpoint.maximumCursorSizeInBytes(), applicationRouter));
			} else {
				// Preserve the generic package-private seam used by transport tests.
				applicationHandler = genericResourceHandler;
			}
			if (applicationHandler.isEmpty()) {
				if (capabilityRegistry.capabilities().resources().isPresent())
					return invalidResourceUriParams(protocolProfile, mappedRequest, uri, corsHeaders);
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
			}
		} else {
			applicationHandler = applicationRouter.resolve(mappedRequest.method());
			if (applicationHandler.isEmpty())
				return methodNotFound(protocolProfile, mappedRequest, corsHeaders);
		}

		if (McpWireResult.supportsInputRequired(mappedRequest.method())) {
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
				inputRequestPlan.missingAtAdmission(
						mappedRequest.params().metadata().clientCapabilities());
		if (!missingCapabilities.isEmpty())
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
				Optional.of(mappedRequest.params().metadata().clientCapabilities()),
				acceptedSubscriptionFilter
						.map(AcceptedSubscriptionFilter
								::requestedResourceSubscriptionUris)
						.orElseGet(List::of),
				Optional.of(mappedRequest.params().metadata().toJsonObject()));
		McpAdmissionDecision admissionDecision;
		try {
			admissionDecision = endpointPolicy.protocolAdmissionController().admit(admissionContext);
		} catch (Throwable throwable) {
			return policyHookInternalError(protocolProfile, mappedRequest.id(), corsHeaders);
		}
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (admissionDecision == null)
			return policyHookInternalError(protocolProfile, mappedRequest.id(), corsHeaders);

		if (admissionDecision instanceof McpAdmissionDecision.Rejected rejected) {
			try {
				return admissionRejection(mappedRequest.id(), rejected.rejection(), corsHeaders);
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
		if (suppliedRequestState.isPresent()) {
			String protectedState = suppliedRequestState.orElseThrow();
			if (requestStateMode == McpRequestStateMode.APPLICATION_PROTECTED) {
				requestState = Optional.of(
						new McpRuntimeApplicationRequestState(protectedState));
			} else if (requestStateMode
					== McpRequestStateMode.FRAMEWORK_PROTECTED) {
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
			} else {
				return invalidParams(protocolProfile, mappedRequest, corsHeaders);
			}
		}
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (!requestControl.startObservation(endpointBinding.observationSink(),
				new McpRuntimeRequestInput(sokletRequest, Map.of(),
						mappedRequest.method(), Optional.of(mappedRequest.id()),
						requestedProtocolVersion, operationName,
						mappedRequest.params().metadata().clientInformation(),
					mappedRequest.params().metadata().clientCapabilities()
							.toJsonObject(),
					mappedRequest.params().metadata().toJsonObject(),
					inputResponses,
					requestState,
					requestControl.acceptLanguageValues(),
					effectiveIdentity.admittedIdentity())))
			return null;

		if (endpointPolicy.requestRateLimiter().isPresent()) {
			McpRateLimitDecision rateLimitDecision;
			try {
				rateLimitDecision = endpointPolicy.requestRateLimiter().orElseThrow().acquire(
						new McpRateLimitContext(sokletRequest, endpoint, effectiveIdentity,
								McpRateLimitTarget.REQUEST, mappedRequest.method(),
								operationName));
			} catch (Throwable throwable) {
				return observedPolicyHookInternalError(requestControl,
						mappedRequest.id(), corsHeaders, throwable);
			}
			if (!requestControl.protocolProcessingAllowed())
				return null;
			if (rateLimitDecision == null)
				return observedPolicyHookInternalError(requestControl,
						mappedRequest.id(), corsHeaders, null);
			if (rateLimitDecision instanceof McpRateLimitDecision.Denied denied)
				return observedRateLimited(requestControl, mappedRequest.id(),
						denied.retryAfter(), corsHeaders);
		}

		if (toolRoute.isPresent()) {
			McpRateLimitDecision rateLimitDecision;
			try {
				rateLimitDecision = toolRoute.orElseThrow().rateLimiter().acquire(
						new McpRateLimitContext(sokletRequest, endpoint, effectiveIdentity,
								McpRateLimitTarget.TOOL, mappedRequest.method(),
								operationName));
			} catch (Throwable throwable) {
				return observedPolicyHookInternalError(requestControl,
						mappedRequest.id(), corsHeaders, throwable);
			}
			if (!requestControl.protocolProcessingAllowed())
				return null;
			if (rateLimitDecision == null)
				return observedPolicyHookInternalError(requestControl,
						mappedRequest.id(), corsHeaders, null);
			if (rateLimitDecision instanceof McpRateLimitDecision.Denied denied)
				return observedRateLimited(requestControl, mappedRequest.id(),
						denied.retryAfter(), corsHeaders);
		}

		if (subscriptionListenRequest) {
			SubscriptionOpenResult openResult = requestControl.openSubscription(
					endpointRuntime.path(), endpoint,
					effectiveIdentity.authorizationPartition(), mappedRequest.id(),
					acceptedSubscriptionFilter.orElseThrow(), corsHeaders,
					endpointPolicy, protocolProfile);
			if (openResult == SubscriptionOpenResult.CAPACITY_REJECTED)
				return observedSubscriptionCapacityRejected(requestControl,
						mappedRequest.id(), corsHeaders);
			if (openResult == SubscriptionOpenResult.LOCALIZATION_FAILED
					|| openResult
						== SubscriptionOpenResult.TERMINAL_PREFLIGHT_FAILED)
				return observedPolicyHookInternalError(requestControl,
						mappedRequest.id(), corsHeaders, null);
			if (openResult == SubscriptionOpenResult.SERVER_STOPPING)
				requestControl.cancel(StreamTerminationReason.SERVER_STOPPING, null);
			return null;
		}

		if (discoveryRequest) {
			return catalogResponse(endpointRuntime.frameworkResponses(protocolProfile)
						.discovery(), protocolProfile,
					McpRuntimeCatalogLocalizer.ResponseKind.DISCOVERY, mappedRequest.id(),
					endpointPolicy, requestControl, corsHeaders);
		}

		if (toolsListRequest) {
			return catalogResponse(endpointRuntime.frameworkResponses(protocolProfile)
						.toolsList(), protocolProfile,
					McpRuntimeCatalogLocalizer.ResponseKind.TOOLS_LIST, mappedRequest.id(),
					endpointPolicy, requestControl, corsHeaders);
		}

		if (promptsListRequest) {
			return catalogResponse(endpointRuntime.frameworkResponses(protocolProfile)
						.promptsList(), protocolProfile,
					McpRuntimeCatalogLocalizer.ResponseKind.PROMPTS_LIST, mappedRequest.id(),
					endpointPolicy, requestControl, corsHeaders);
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

		McpApplicationRequestHandler resolvedApplicationHandler =
				applicationHandler.orElseThrow();
		Optional<McpFrameworkRequestStateContinuation> resolvedContinuation =
				frameworkRequestStateContinuation;
		requestControl.handoff(application, () -> {
			McpApplicationResponseWriter responseWriter =
					new McpApplicationResponseWriter() {
					@Override
					public boolean write(@NonNull McpApplicationResponse response) {
						return requestControl.writeApplicationResponse(response,
								mappedRequest.id(), corsHeaders);
					}

					@Override
					public boolean writeNotification(
							McpJsonRpcMessage.@NonNull Notification notification)
							throws InterruptedException {
						return requestControl.writeApplicationNotification(
								notification, corsHeaders);
					}
				};
			Optional<McpRequestContext> publicContext =
					requestControl.publicRequestContext();
			if (publicContext.isPresent()) {
				application.dispatchWithSokletRequest(request, sokletRequest,
						publicContext.orElseThrow(), mappedRequest, protocolProfile,
						effectiveIdentity,
						resolvedContinuation,
						resolvedApplicationHandler,
						endpointPolicy.requestInterceptor(),
						requestControl::applicationEntryAllowed,
						requestControl.deadlineNanos(), responseWriter,
						requestControl::applicationTerminated);
			} else {
				application.dispatchWithSokletRequest(request, sokletRequest,
						mappedRequest, protocolProfile, effectiveIdentity,
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
					localizedResponseKinds) {
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
		List<SubscriptionResource> requestedResources = new ArrayList<>();
		if (resourceSubscriptionsRequested) {
			McpJsonValue resourceSubscriptions = fields.get("resourceSubscriptions");
			if (!(resourceSubscriptions instanceof McpJsonArray resources))
				throw new IllegalArgumentException(
						"Resource subscriptions must be an array.");
			Map<URI, SubscriptionResource> distinctResources = new LinkedHashMap<>();
			for (McpJsonValue value : resources.values()) {
				if (!(value instanceof McpJsonString string))
					throw new IllegalArgumentException(
							"Resource subscription URIs must be strings.");
				String wireUri = McpLevelOneUriTemplate.requireValidAbsoluteUri(
						string.value(), "Resource subscription URI");
				URI uri = URI.create(wireUri);
				distinctResources.putIfAbsent(uri,
						new SubscriptionResource(uri, wireUri));
			}
			requestedResources.addAll(distinctResources.values());
		}

		Set<McpResourceNotificationType> supported =
				requireNonNull(configuration).notificationTypes();
		boolean acceptToolsListChanged = toolsListChangedRequested
				&& localizedResponseKinds.contains(
						McpRuntimeCatalogLocalizer.ResponseKind.TOOLS_LIST);
		boolean acceptPromptsListChanged = promptsListChangedRequested
				&& localizedResponseKinds.contains(
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
				acceptResourceSubscriptions, requestedResources);
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
			List<McpJsonValue> resourceUris = filter.resourceSubscriptions().stream()
					.map(resource -> (McpJsonValue) new McpJsonString(resource.wireUri()))
					.toList();
			accepted.put("resourceSubscriptions", new McpJsonArray(resourceUris));
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
				endpoint.includeServerInformation()
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

	@NonNull
	private static Optional<@NonNull McpJsonObject> parseInputResponses(
			@NonNull Map<@NonNull String, @NonNull McpJsonValue> fields) {
		McpJsonValue value = requireNonNull(fields).get("inputResponses");
		if (value == null)
			return Optional.empty();
		if (!(value instanceof McpJsonObject responses))
			throw new IllegalArgumentException("MCP input responses must be an object.");
		for (McpJsonValue response : responses.members().values())
			McpInputResponseValidator.validate(response);
		return Optional.of(responses);
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
			int maximumCursorSizeInBytes,
			@NonNull McpApplicationRequestRouter applicationRouter) {
		requireNonNull(result);
		requireNonNull(cachePolicy);
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
			validateResourceListResult(fields, applicationRouter);
		else
			validateResourceReadResult(fields);
		if (resourceRetry) {
			fields.put("cacheScope", new McpJsonString(
					McpCacheScope.PRIVATE.wireValue()));
			fields.put("ttlMs", new McpJsonNumber(BigDecimal.ZERO));
			return McpWireResult.complete(new McpJsonObject(fields), result.metadata());
		}
		McpJsonValue configuredScope = fields.get("cacheScope");
		if (configuredScope != null
				&& (!(configuredScope instanceof McpJsonString string)
				|| !cachePolicy.scope().wireValue().equals(string.value())))
			throw new IllegalArgumentException(
					"A resource result cannot override its cache scope.");
		fields.put("cacheScope", new McpJsonString(cachePolicy.scope().wireValue()));

		McpJsonValue configuredTtl = fields.get("ttlMs");
		if (configuredTtl == null) {
			fields.put("ttlMs", new McpJsonNumber(
					cachePolicy.timeToLiveMilliseconds()));
		} else if (!(configuredTtl instanceof McpJsonNumber number)
				|| number.value().stripTrailingZeros().scale() > 0
				|| number.value().signum() < 0) {
			throw new IllegalArgumentException(
					"A resource result cache TTL must be a nonnegative integer.");
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
			@NonNull McpApplicationRequestRouter applicationRouter) {
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
			if (!observedUris.add(URI.create(normalized.uri())))
				throw new IllegalArgumentException(
						"A resource-list page contains a duplicate URI.");
			if (!hasReadableResourceRoute(normalized.uri(), applicationRouter))
				throw new IllegalArgumentException(
						"A resource-list page contains an unreadable URI.");
		}
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

	private @Nullable MicrohttpResponse processNotification(
			@NonNull MicrohttpRequest request,
			@NonNull Request sokletRequest,
			McpJsonRpcEnvelope.@NonNull Notification notification,
			@NonNull List<@NonNull Header> corsHeaders,
			@NonNull RequestControl requestControl,
			@NonNull EndpointRuntime endpointRuntime) {
		McpHttpEndpointBinding endpointBinding = endpointRuntime.binding();
		McpHttpEndpointPolicy endpointPolicy = endpointBinding.endpointPolicy();
		McpNormalizedEndpoint endpoint = endpointBinding.endpoint();
		boolean cancellationNotification =
				"notifications/cancelled".equals(notification.method());

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
		if (selectedProfile.isEmpty())
			return emptyResponse(400, "Bad Request", corsHeaders);
		McpProtocolProfile protocolProfile = selectedProfile.orElseThrow();
		requestControl.bindProtocolProfile(protocolProfile);
		McpNotificationMetadataValidation metadataValidation = protocolProfile
				.validateNotificationMetadata(notification);

		if (!cancellationNotification && !metadataValidation.valid())
			return emptyResponse(400, "Bad Request", corsHeaders);

		if (!requestControl.protocolProcessingAllowed())
			return null;
		McpAdmissionContext admissionContext = new McpAdmissionContext(
				sokletRequest, endpoint, Map.of(), notification.method(), true,
				Optional.empty(), protocolVersion, Optional.empty(), Optional.empty(),
				Optional.empty(), List.of(), metadataValidation.metadata());
		McpAdmissionDecision admissionDecision;
		try {
			admissionDecision = endpointPolicy.protocolAdmissionController().admit(admissionContext);
		} catch (Throwable throwable) {
			return emptyResponse(500, "Internal Server Error", corsHeaders);
		}
		if (!requestControl.protocolProcessingAllowed())
			return null;
		if (admissionDecision == null)
			return emptyResponse(500, "Internal Server Error", corsHeaders);

		if (admissionDecision instanceof McpAdmissionDecision.Rejected rejected) {
			try {
				return notificationAdmissionRejection(rejected.rejection(), corsHeaders);
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
		if (!requestControl.startObservation(endpointBinding.observationSink(),
				new McpRuntimeRequestInput(sokletRequest, Map.of(),
						notification.method(), Optional.empty(), protocolVersion,
						Optional.empty(), Optional.empty(), McpJsonObject.empty(),
					metadataValidation.metadata()
								.orElseGet(McpJsonObject::empty),
						McpJsonObject.empty(), Optional.empty(),
						requestControl.acceptLanguageValues(),
						effectiveIdentity.admittedIdentity())))
			return null;

		if (endpointPolicy.requestRateLimiter().isPresent()) {
			McpRateLimitDecision rateLimitDecision;
			try {
				rateLimitDecision = endpointPolicy.requestRateLimiter().orElseThrow().acquire(
						new McpRateLimitContext(sokletRequest, endpoint, effectiveIdentity,
								McpRateLimitTarget.REQUEST, notification.method(),
								Optional.empty()));
			} catch (Throwable throwable) {
				requestControl.planRequestObservation(new RequestObservationResult(
						McpRequestOutcome.INTERNAL_ERROR, null, List.of(throwable)));
				return emptyResponse(500, "Internal Server Error", corsHeaders);
			}
			if (!requestControl.protocolProcessingAllowed())
				return null;
			if (rateLimitDecision == null) {
				requestControl.planRequestObservation(new RequestObservationResult(
						McpRequestOutcome.INTERNAL_ERROR, null, List.of()));
				return emptyResponse(500, "Internal Server Error", corsHeaders);
			}
			if (rateLimitDecision instanceof McpRateLimitDecision.Denied denied) {
				requestControl.planRequestObservation(new RequestObservationResult(
						McpRequestOutcome.REJECTED, null, List.of()));
				return notificationRateLimited(denied.retryAfter(), corsHeaders);
			}
		}

		return cancellationNotification
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
			case PRODUCER_FAILED, INTERNAL_ERROR, UNKNOWN ->
					McpRequestOutcome.INTERNAL_ERROR;
			case SERVER_STOPPING, PROTOCOL_UNSUPPORTED, APPLICATION_CANCELED,
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
				byte[] encodedMessage = envelopeCodec.encode(message);
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
	@NonNull
	private MicrohttpResponse catalogResponse(@NonNull McpWireResult canonicalResult,
			@NonNull McpProtocolProfile protocolProfile,
			McpRuntimeCatalogLocalizer.@NonNull ResponseKind responseKind,
			@NonNull McpJsonRpcId requestId,
			@NonNull McpHttpEndpointPolicy endpointPolicy,
			@NonNull RequestControl requestControl,
			@NonNull List<@NonNull Header> corsHeaders) {
		byte[] canonicalEncoded = envelopeCodec.encode(
				new McpJsonRpcMessage.ResultResponse(requestId, canonicalResult,
						McpJsonObject.empty()));
		Optional<McpRuntimeCatalogLocalizer> catalogLocalizer =
				endpointPolicy.catalogLocalizer();
		McpRequestContext requestContext =
				requestControl.publicRequestContext().orElse(null);

		if (catalogLocalizer.isEmpty() || requestContext == null)
			return jsonResponse(200, "OK", canonicalEncoded, corsHeaders);

		McpJsonObject canonicalDocument = canonicalResult.toJsonObject();
		// Canonical documents are stable per binding, so their encoded length is
		// measured once per catalog rather than once per request.
		long canonicalDocumentBytes = canonicalCatalogDocumentBytes.computeIfAbsent(
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
							requestControl::isTerminalCanceledOrPastDeadline)),
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
			case LOCALIZED -> jsonResponse(200, "OK", envelopeCodec.encode(
					new McpJsonRpcMessage.ResultResponse(requestId,
							McpWireResult.withPrecomputedJsonObject(canonicalResult,
									outcome.document()), McpJsonObject.empty())),
					responseHeaders);
			case FAIL_REQUEST -> observedPolicyHookInternalError(requestControl,
					requestId, corsHeaders, null);
		};
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
		McpRequestContext requestContext =
				requestControl.publicRequestContext().orElseThrow();

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
		if (!applicationErrorCodeAllowed(rejection.jsonRpcError().code()))
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
		if (!applicationErrorCodeAllowed(rejection.jsonRpcError().code()))
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

	private boolean applicationErrorCodeAllowed(int code) {
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
				if (headers.size() >= transportConfiguration.maximumHeaderCount()
						|| encodedBytes > transportConfiguration.maximumHeaderBytes())
					throw new IllegalArgumentException(
							"Admission rejection response headers exceed the configured bounds.");
				headers.add(new Header(name, value));
			}
		}
		return List.copyOf(headers);
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

		if (requestedMethods.size() != 1 || !"POST".equals(requestedMethods.get(0)))
			return emptyResponse(403, "Forbidden", List.of());

		Optional<Set<String>> requestedHeaders = requestedPreflightHeaders(request);
		if (requestedHeaders.isEmpty()
				|| !containsOnlyIgnoreCase(requestedHeaders.orElseThrow(),
						mcpPreflightRequestHeaders(endpointRuntime)))
			return emptyResponse(403, "Forbidden", List.of());

		CorsPreflight preflight = CorsPreflight.with(origins.get(0), HttpMethod.POST,
				requestedHeaders.orElseThrow());
		CorsPreflightResponse authorization;
		try {
			Optional<CorsPreflightResponse> optionalAuthorization =
					endpointPolicy.corsAuthorizer().authorizePreflight(
							sokletRequest, preflight, MCP_HTTP_METHODS);
			if (optionalAuthorization == null)
				return emptyResponse(500, "Internal Server Error", List.of());
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
		if (!MCP_HTTP_METHODS.containsAll(allowedMethods)
				|| !validCorsAllowedHeaders(allowedHeaders, endpointRuntime))
			return emptyResponse(500, "Internal Server Error", List.of());

		List<Header> headers = new ArrayList<>();
		headers.add(new Header("Access-Control-Allow-Origin", allowedOrigin.orElseThrow()));
		if (Boolean.TRUE.equals(
				authorization.getAccessControlAllowCredentials().orElse(null)))
			headers.add(new Header("Access-Control-Allow-Credentials", "true"));
		List<String> allowedMethodNames = new ArrayList<>();
		for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.OPTIONS)) {
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
		headers.addAll(endpointRuntime.capabilityRegistry()
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
			Optional<CorsResponse> optionalResponse = endpointPolicy.corsAuthorizer()
					.authorize(sokletRequest, Cors.fromOrigin(httpMethod, origins.get(0)));
			if (optionalResponse == null)
				return CorsAuthorization.rejected(
						emptyResponse(500, "Internal Server Error", List.of()));
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
		if (!MCP_EXPOSED_RESPONSE_HEADERS.isEmpty())
			headers.add(new Header("Access-Control-Expose-Headers",
					String.join(", ", MCP_EXPOSED_RESPONSE_HEADERS)));
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
		List<String> protocolVersions = headerValues(request, MCP_PROTOCOL_VERSION);
		List<String> methods = headerValues(request, MCP_METHOD);
		List<String> names = headerValues(request, MCP_NAME);

		if (protocolVersions.size() != 1 || methods.size() != 1)
			return headerMismatch(wireRequest.id(), wireRequest.method(),
					validatedUnsupportedSelector, corsHeaders);
		try {
			mirroredHeaderCodec.requirePlainString(protocolVersions.get(0));
			mirroredHeaderCodec.requirePlainString(methods.get(0));
		} catch (IllegalArgumentException exception) {
			return headerMismatch(wireRequest.id(), wireRequest.method(),
					validatedUnsupportedSelector, corsHeaders);
		}
		if (!methods.get(0).equals(wireRequest.method()))
			return headerMismatch(wireRequest.id(), wireRequest.method(),
					validatedUnsupportedSelector, corsHeaders);

		Optional<String> expectedName = standardMirroredName(wireRequest);
		if (requiresMcpName(wireRequest.method())) {
			if (names.size() != 1 || expectedName.isEmpty())
				return headerMismatch(wireRequest.id(), wireRequest.method(),
						validatedUnsupportedSelector, corsHeaders);

			String decodedName;
			try {
				decodedName = mirroredHeaderCodec.decodeString(names.get(0));
			} catch (IllegalArgumentException exception) {
				return headerMismatch(wireRequest.id(), wireRequest.method(),
						validatedUnsupportedSelector, corsHeaders);
			}
			if (!decodedName.equals(expectedName.orElseThrow()))
				return headerMismatch(wireRequest.id(), wireRequest.method(),
						validatedUnsupportedSelector, corsHeaders);
		} else if (!names.isEmpty()) {
			return headerMismatch(wireRequest.id(), wireRequest.method(),
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
				|| "resources/read".equals(method);
	}

	@NonNull
	private Optional<@NonNull String> standardMirroredName(
			McpJsonRpcEnvelope.@NonNull Request wireRequest) {
		if (!requiresMcpName(wireRequest.method())
				|| wireRequest.params().isEmpty()
				|| !(wireRequest.params().orElseThrow() instanceof McpJsonObject params))
			return Optional.empty();

		String fieldName = "resources/read".equals(wireRequest.method()) ? "uri" : "name";
		McpJsonValue value = params.members().get(fieldName);
		return value instanceof McpJsonString string
				? Optional.of(string.value())
				: Optional.empty();
	}

	@NonNull
	private MicrohttpResponse headerMismatch(@NonNull McpJsonRpcId id,
			@NonNull String readableMethod,
			@NonNull List<@NonNull Header> corsHeaders) {
		return headerMismatch(id, readableMethod, false, corsHeaders);
	}

	@NonNull
	private MicrohttpResponse headerMismatch(@NonNull McpJsonRpcId id,
			@NonNull String readableMethod, boolean validatedUnsupportedSelector,
			@NonNull List<@NonNull Header> corsHeaders) {
		return jsonRpcError(400, "Bad Request", Optional.of(id),
				new McpJsonRpcError(McpJsonRpcError.HEADER_MISMATCH,
						"Header mismatch", supportedVersionDiagnostic(
								readableMethod, validatedUnsupportedSelector)),
				corsHeaders);
	}

	@NonNull
	private MicrohttpResponse strictUnknownMirroredHeader(@NonNull McpJsonRpcId id,
			@NonNull String readableMethod, boolean validatedUnsupportedSelector,
			@NonNull List<@NonNull Header> corsHeaders) {
		return jsonRpcError(400, "Bad Request", Optional.of(id),
				new McpJsonRpcError(SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER,
						"Unknown mirrored header",
						supportedVersionDiagnostic(readableMethod,
								validatedUnsupportedSelector)), corsHeaders);
	}

	@NonNull
	private MicrohttpResponse methodNotFound(
			@NonNull McpProtocolProfile protocolProfile,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull List<@NonNull Header> corsHeaders) {
		Optional<McpJsonValue> data = supportedVersionDiagnostic(request.method());
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
	private MicrohttpResponse invalidResourceUriParams(
			@NonNull McpProtocolProfile protocolProfile,
			McpJsonRpcMessage.@NonNull Request request, @NonNull String uri,
			@NonNull List<@NonNull Header> corsHeaders) {
		McpJsonObject data = new McpJsonObject(
				Map.of("uri", new McpJsonString(requireNonNull(uri))));
		return profiledJsonRpcError(protocolProfile, McpProfileErrorKind.OPERATION,
				400, "Bad Request", Optional.of(request.id()),
				new McpJsonRpcError(McpJsonRpcError.INVALID_PARAMS,
						"Invalid params", Optional.of(data)), corsHeaders);
	}

	@NonNull
	private MicrohttpResponse wireDecodingFailure(
			@NonNull McpWireDecodingException exception,
			@Nullable String readableMethod,
			@NonNull List<@NonNull Header> corsHeaders) {
		McpJsonRpcError error = wireDecodingError(exception, readableMethod);
		return jsonRpcError(400, "Bad Request", exception.readableRequestId(),
				error, corsHeaders);
	}

	@NonNull
	private MicrohttpResponse wireDecodingFailure(
			@NonNull McpProtocolProfile protocolProfile,
			@NonNull McpWireDecodingException exception,
			@Nullable String readableMethod,
			@NonNull List<@NonNull Header> corsHeaders) {
		McpJsonRpcError error = wireDecodingError(exception, readableMethod);
		return profiledJsonRpcError(protocolProfile,
				McpProfileErrorKind.REQUEST_MAPPER, 400, "Bad Request",
				exception.readableRequestId(), error, corsHeaders);
	}

	@NonNull
	private McpJsonRpcError wireDecodingError(
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
		Optional<McpJsonValue> data = supportedVersionDiagnostic(readableMethod);
		return new McpJsonRpcError(code, message, data);
	}

	@NonNull
	private McpJsonObject supportedVersionDiagnostic() {
		List<McpJsonValue> versions = this.protocolProfiles.revisions().stream()
				.map(McpJsonString::new)
				.map(McpJsonValue.class::cast)
				.toList();
		return new McpJsonObject(Map.of("supportedVersions", new McpJsonArray(versions)));
	}

	@NonNull
	private Optional<@NonNull McpJsonValue> supportedVersionDiagnostic(
			@Nullable String readableMethod) {
		return "initialize".equals(readableMethod)
				? Optional.of(supportedVersionDiagnostic())
				: Optional.empty();
	}

	@NonNull
	private Optional<@NonNull McpJsonValue> supportedVersionDiagnostic(
			@Nullable String readableMethod,
			boolean validatedUnsupportedSelector) {
		return validatedUnsupportedSelector
				? Optional.of(supportedVersionDiagnostic())
				: supportedVersionDiagnostic(readableMethod);
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
		List<Header> headers = new ArrayList<>(additionalHeaders);
		headers.add(new Header("Allow", "POST, OPTIONS"));
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

		Optional<HostAuthority> authority = parseHostAuthority(values.get(0));
		if (authority.isEmpty())
			return false;

		HostAuthority hostAuthority = authority.orElseThrow();
		if (hostAuthority.port().isPresent()) {
			if (hostAuthority.port().orElseThrow() != effectiveAddress.getPort())
				return false;
		} else if (effectiveAddress.getPort() != 80) {
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

	@NonNull
	private Set<@NonNull String> normalizedAllowedHosts(
			@NonNull InetSocketAddress effectiveAddress,
			@NonNull McpHttpEndpointPolicy endpointPolicy) {
		Set<String> allowedHosts = new LinkedHashSet<>();

		InetAddress address = effectiveAddress.getAddress();
		boolean loopback = address != null && address.isLoopbackAddress();
		if (address == null && effectiveAddress.isUnresolved()) {
			String host = effectiveAddress.getHostString();
			loopback = "localhost".equalsIgnoreCase(host)
					|| "::1".equals(host) || "[::1]".equals(host)
					|| host.startsWith("127.");
		}
		if (loopback) {
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
		try {
			InetAddress address = InetAddress.getByName(value);
			return address instanceof Inet6Address
					? Optional.of(address.getHostAddress().toLowerCase(Locale.ROOT))
					: Optional.empty();
		} catch (Exception exception) {
			return Optional.empty();
		}
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
		try {
			URI uri = new URI(requestTarget);
			String path = uri.getRawPath();
			return path == null || path.isEmpty() ? "/" : path;
		} catch (URISyntaxException exception) {
			return "";
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
		return endpointRuntime != null && endpointRuntime.binding()
				.endpointPolicy().localizationEnabled();
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
		private final ThreadPoolExecutor processor;
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
		private @Nullable FutureTask<@Nullable Void> protocolTask;
		private @Nullable Consumer<@NonNull MicrohttpResponse> responseCallback;
		private @Nullable McpProtocolProfile protocolProfile;
		private boolean identifiedRequestExchange;
		private @Nullable McpRequestSseStream responseStream;
		private @Nullable StreamTerminationReason cancellationReason;
		private @Nullable Throwable cancellationCause;
		private @Nullable McpRuntimeRequestObservation requestObservation;
		private @Nullable SubscriptionRegistration subscriptionRegistration;
		private @Nullable StreamTerminationReason plannedSubscriptionCloseReason;
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
		private long nextKeepAliveNanos;
		private long streamOpenedAtNanos;
		private long subscriptionOpenedAtNanos;
		private boolean applicationOwned;
		private boolean subscriptionOwned;
		private boolean streamObservationOpened;
		private boolean streamObservationClosed;
		private boolean subscriptionObservationOpened;
		private boolean subscriptionObservationClosed;
		private boolean streamTerminalResponseOwned;
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
		private boolean gracefulTransportClosed;

		private RequestControl(@NonNull MicrohttpRequest request,
				long deadlineNanos, @NonNull ThreadPoolExecutor processor,
				@NonNull McpApplicationExecution application,
				@Nullable Request publicRequest,
				@Nullable McpSimulationRuntime simulation,
				@Nullable Runnable lifecycleAdmission,
				@NonNull Consumer<@NonNull MicrohttpResponse> responseCallback) {
			this.request = requireNonNull(request);
			this.deadlineNanos = deadlineNanos;
			this.processor = requireNonNull(processor);
			this.application = requireNonNull(application);
			this.publicRequest = publicRequest;
			this.simulation = simulation;
			this.lifecycleAdmission = lifecycleAdmission;
			this.acceptLanguageValues = physicalHeaderValues(request,
					"Accept-Language");
			this.acceptLanguageVaryRequired = localizationVaryRequired(request);
			this.lock = new Object();
			this.streamObservationTransitionLock = new Object();
			this.responseCallback = requireNonNull(responseCallback);
			this.publicRequestContext = Optional.empty();
			this.deadlineResponseHeaders = decorateResponseHeaders(List.of());
			this.lifecycleWorkOwners = lifecycleAdmission == null ? 0 : 1;
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
			if (remove)
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
			if (lifecycleAdmission == null) {
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
			MicrohttpResponse requiredResponse = requireNonNull(response);
			return this.acceptLanguageVaryRequired
					? requiredResponse.withHeaders(withAcceptLanguageVary(
							requiredResponse.headers()))
					: requiredResponse;
		}

		private long deadlineNanos() {
			synchronized (lock) {
				return deadlineNanos;
			}
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
				@NonNull McpProtocolProfile protocolProfile) {
			requireNonNull(additionalHeaders);
			requireNonNull(protocolProfile);
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
			// Two-phase when a localizer is configured: reserve only the cap,
			// release every lock, retain the original request deadline, create
			// the context and pre-render the localized terminal metadata, and
			// only then materialize and commit the stream. The rendered stream
			// deadline extension therefore follows successful pre-render.
			boolean localizeTerminal = endpointPolicy.catalogLocalizer().isPresent()
					&& publicRequestContext().isPresent();
			SubscriptionRegistration capReservation = null;
			Object localizationInvalidationToken = null;
			Optional<String> contentLanguage = Optional.empty();
			boolean terminalPreflightComplete = false;

			if (localizeTerminal) {
				SubscriptionCapReservation cap = reserveSubscriptionCap(
						endpointPath, endpoint, authorizationPartition,
						subscriptionId, filter, protocolProfile);

				if (cap.result() != SubscriptionOpenResult.OPENED)
					return cap.result();

				capReservation = requireNonNull(cap.registration());
				localizationInvalidationToken = requireNonNull(
						cap.localizationInvalidationToken());
				McpRuntimeCatalogLocalizer.Outcome outcome;

				try {
					outcome = localizeSubscriptionTerminal(endpointPolicy,
							preRenderedTerminal, this);
				} catch (RuntimeException exception) {
					outcome = null;
				}

				if (outcome == null || outcome.disposition()
						== McpRuntimeCatalogLocalizer.Disposition.FAIL_REQUEST) {
					// This method still solely owns the unmaterialized cap, so
					// the rollback happens exactly once.
					removeSubscription(this, capReservation);
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
					if (capReservation != null)
						removeSubscription(this, capReservation);
					return SubscriptionOpenResult.TERMINAL_PREFLIGHT_FAILED;
				}
			}
			SubscriptionOpenReservation reservation;
			try {
				synchronized (streamObservationTransitionLock) {
					reservation = reserveSubscriptionOpen(endpointPath, endpoint,
							authorizationPartition, subscriptionId, filter,
							protocolProfile, capReservation);
					if (reservation.result() != SubscriptionOpenResult.OPENED)
						return reservation.result();
					markStreamOpenedInOrder(true);
					synchronized (lock) {
						if (terminal || canceled)
							return SubscriptionOpenResult.TERMINATED;
					}
				}
			} finally {
				drainApplicationExecutionObservation();
			}
			McpRequestSseStream stream = requireNonNull(reservation.stream());
			Consumer<MicrohttpResponse> callback = requireNonNull(
					reservation.responseCallback());
			SubscriptionRegistration registration = requireNonNull(
					reservation.registration());
			synchronized (lock) {
				if (!terminal && !canceled && !streamAbortOwned
						&& responseStream == stream
						&& subscriptionRegistration == registration) {
					// The acknowledgment was queued while the subscription was still
					// pending. Activate before handing the response to the transport so a
					// client that publishes immediately after reading that acknowledgment
					// cannot race the pending-to-active transition and lose its event.
					SubscriptionActivationResult activation = activateSubscription(
							this, registration, localizationInvalidationToken);
					if (activation != SubscriptionActivationResult.NOT_ACTIVATED)
						preRenderedSubscriptionTerminal = activation
								== SubscriptionActivationResult
								.ACTIVATED_CURRENT_LOCALIZATION
								? preRenderedTerminal : null;
				}
			}
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
		 * capacity, so
		 * localized terminal pre-render can run between cap reservation and
		 * stream materialization without any framework lock held.
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
				if (canceled || terminal || applicationOwned || subscriptionOwned)
					return new SubscriptionCapReservation(
							SubscriptionOpenResult.TERMINATED, null, null);
				SubscriptionRegistrationAttempt registrationAttempt = registerSubscription(
						this, endpointPath, endpoint, authorizationPartition,
						subscriptionId, filter, protocolProfile);
				if (registrationAttempt.result()
						== SubscriptionRegistrationResult.NOT_ACCEPTING)
					return new SubscriptionCapReservation(
							SubscriptionOpenResult.SERVER_STOPPING, null, null);
				if (registrationAttempt.result()
						== SubscriptionRegistrationResult.CAPACITY_REJECTED)
					return new SubscriptionCapReservation(
							SubscriptionOpenResult.CAPACITY_REJECTED, null, null);
				return new SubscriptionCapReservation(
						SubscriptionOpenResult.OPENED,
						requireNonNull(registrationAttempt.registration()),
						localizationInvalidationToken(endpointPath));
			}
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
				@Nullable SubscriptionRegistration preReservedRegistration) {
			SubscriptionRegistration registration;
			synchronized (lock) {
				if (canceled || terminal || applicationOwned || subscriptionOwned) {
					if (preReservedRegistration != null)
						removeSubscription(this, preReservedRegistration);

					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.TERMINATED,
							null, null, null);
				}
				if (preReservedRegistration != null) {
					registration = preReservedRegistration;
					if (registration.protocolProfile() != protocolProfile)
						throw new IllegalStateException(
								"An MCP subscription reservation cannot change protocol profiles.");
				} else {
					SubscriptionRegistrationAttempt registrationAttempt = registerSubscription(
							this, endpointPath, endpoint, authorizationPartition,
							subscriptionId, filter, protocolProfile);
					if (registrationAttempt.result()
							== SubscriptionRegistrationResult.NOT_ACCEPTING)
						return new SubscriptionOpenReservation(
								SubscriptionOpenResult.SERVER_STOPPING,
								null, null, null);
					if (registrationAttempt.result()
							== SubscriptionRegistrationResult.CAPACITY_REJECTED)
						return new SubscriptionOpenReservation(
								SubscriptionOpenResult.CAPACITY_REJECTED,
								null, null, null);
					registration = requireNonNull(registrationAttempt.registration());
				}
				// This is the last shutdown-admission check before materialization.
				// The request-control lock remains held while stream state is installed,
				// so a shutdown that races after this point snapshots the still-pending
				// control and cannot complete it before that state is coherent.
				if (!subscriptionMayCommit(this)) {
					removeSubscription(this, registration);
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.SERVER_STOPPING,
							null, null, null);
				}
				try {
					McpRequestSseStream stream = newResponseStream();
					McpOutboundChannel.OfferResult result = stream.offerMessage(
							subscriptionAcknowledgement(
									registration.protocolProfile(),
									subscriptionId, filter));
					if (result != McpOutboundChannel.OfferResult.ACCEPTED)
						throw new IllegalStateException(
								"A new MCP subscription stream could not accept its acknowledgment.");
					responseStream = stream;
					subscriptionRegistration = registration;
					subscriptionOwned = true;
					long nowNanos = applicationClock.nanoTime();
					deadlineNanos = saturatingAdd(nowNanos,
							subscriptionRuntimeConfiguration
									.maximumSubscriptionDuration().toNanos());
					nextKeepAliveNanos = saturatingAdd(nowNanos,
							transportConfiguration.keepAliveInterval().toNanos());
					return new SubscriptionOpenReservation(
							SubscriptionOpenResult.OPENED, stream,
							takeResponseCallback(), registration);
				} catch (RuntimeException | Error failure) {
					removeSubscription(this, registration);
					throw failure;
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
						|| responseStream == null)
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
					if (invalidation.tools()
							&& registration.filter().toolsListChanged())
						coalescingKeys.add(SubscriptionEventKey.TOOLS_LIST_CHANGED);
					if (invalidation.prompts()
							&& registration.filter().promptsListChanged())
						coalescingKeys.add(
								SubscriptionEventKey.PROMPTS_LIST_CHANGED);
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
				if (coalescingKey == SubscriptionEventKey.TOOLS_LIST_CHANGED)
					notification = listChangedNotification(
							registration.protocolProfile(),
							registration.subscriptionId(),
							"notifications/tools/list_changed");
				else if (coalescingKey == SubscriptionEventKey.PROMPTS_LIST_CHANGED)
					notification = listChangedNotification(
							registration.protocolProfile(),
							registration.subscriptionId(),
							"notifications/prompts/list_changed");
				else if (coalescingKey == SubscriptionEventKey.RESOURCES_LIST_CHANGED)
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
					result = stream.offerCoalescingMessage(notification,
							coalescingKey);
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
			requireNonNull(stream);
			requireNonNull(reason);
			boolean scheduled = false;
			synchronized (lock) {
				if (responseStream != stream || terminal || canceled
						|| streamAbortOwned || streamTerminalResponseOwned)
					return;
				streamAbortOwned = true;
				subscriptionOwned = false;
				pendingSubscriptionStreamFailure = new SubscriptionStreamFailure(
						stream, reason, cause);
				scheduled = true;
			}
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
			synchronized (lock) {
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
					plannedSubscriptionCloseReason = closeReason;
					streamTerminalResponseOwned = true;
					stream = responseStream;
					registration = subscriptionRegistration;
					preRendered = preRenderedSubscriptionTerminal;
					preRenderedSubscriptionTerminal = null;
				}
			}
			if (pendingFailure != null) {
				stream.fail(pendingFailure.reason(), pendingFailure.cause());
				return;
			}
			planRequestObservation(new RequestObservationResult(
					McpRequestOutcome.COMPLETE, null, List.of()));
			SubscriptionRegistration resolvedRegistration = requireNonNull(registration);
			try {
				if (!stream.completeMessage(preRendered != null ? preRendered
						: subscriptionTerminalResponse(
								resolvedRegistration.protocolProfile(),
								resolvedRegistration.subscriptionId(),
								resolvedRegistration.endpoint())))
					stream.fail(StreamTerminationReason.INTERNAL_ERROR, null);
			} catch (Throwable throwable) {
				stream.fail(StreamTerminationReason.INTERNAL_ERROR, throwable);
			}
		}

		private boolean hasSubscriptionRegistration() {
			synchronized (lock) {
				return subscriptionRegistration != null;
			}
		}

		/** Closes public transport state without canceling admitted handler work. */
		private void quiesceTransport() {
			if (hasSubscriptionRegistration()) {
				completeSubscription(StreamTerminationReason.SERVER_STOPPING);
				return;
			}
			McpRequestSseStream stream;
			Consumer<MicrohttpResponse> callback = null;
			boolean preserveApplication;
			synchronized (lock) {
				if (terminal || canceled)
					return;
				stream = responseStream;
				preserveApplication = applicationOwned;
				if (preserveApplication)
					gracefulTransportClosed = true;
				if (stream == null && preserveApplication) {
					canceled = true;
					cancellationReason = StreamTerminationReason.SERVER_STOPPING;
					callback = takeResponseCallback();
					markTerminalWhileLocked();
					releaseIdentifiedRequestExchange();
				}
			}
			if (stream != null) {
				stream.close(StreamTerminationReason.SERVER_STOPPING, null);
				return;
			}
			if (callback != null) {
				MicrohttpResponse response = withLifecycleResponseTermination(
						decorateResponse(emptyResponse(
								503, "Service Unavailable", List.of())));
				Throwable deliveryFailure = deliverResponse(callback, response);
				if (deliveryFailure != null)
					finishTransportLifecycle();
				finishRequestObservation(McpRequestOutcome.CANCELED, null, List.of());
				// The common admission joins this response body's termination with the
				// admitted application handler's terminal cleanup.
			}
		}

		/**
		 * Gracefully drains one off-network request. Bounded application work keeps
		 * its response channel through the grace phase; only already-open streams
		 * and subscriptions receive the immediate server-stopping terminal.
		 */
		private void quiesceSimulationTransport() {
			if (hasSubscriptionRegistration()) {
				completeSubscription(StreamTerminationReason.SERVER_STOPPING);
				return;
			}
			McpRequestSseStream stream;
			synchronized (lock) {
				if (terminal || canceled)
					return;
				stream = responseStream;
			}
			if (stream != null)
				stream.close(StreamTerminationReason.SERVER_STOPPING, null);
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
			if (!requestObservationReserved && !requestRejectionRecorded) {
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
			if (reservation.deadlineExpiration() != null)
				finishProtocolDeadline(reservation.deadlineExpiration());
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

			if (submission != null && submission.rejectedCallback() != null) {
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
						submission.rejectedCallback(), response);
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
			if (reservation.deadlineExpiration() != null) {
				finishProtocolDeadline(reservation.deadlineExpiration());
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
				if (pendingCancellationReason != null)
					application.cancel(request, pendingCancellationReason,
							pendingCancellationCause);
			}
			drainRequestObservation();
			return true;
		}

		private void completeProtocol(@Nullable MicrohttpResponse response) {
			ProtocolResponseReservation reservation;
			McpHttpServerRuntime.this.applicationExecutionObserver
					.beginRequestTransitionDeferral();
			try {
				synchronized (lock) {
					if (applicationOwned || terminal)
						return;
					if (subscriptionRegistration != null
							|| streamTerminalResponseOwned) {
						// An inline application handler may reserve its stream terminal
						// before the protocol handoff returns. Its transport callback still
						// owns terminal cleanup and must not be preempted here.
						protocolTask = null;
						return;
					}

					reservation = application.reserveProtocolOperationIfRunning(() -> {
						if (!canceled && response != null
								&& applicationClock.nanoTime() - deadlineNanos >= 0L)
							return new ProtocolResponseReservation(
									null, null, detachProtocolDeadline(false));

						protocolTask = null;
						markTerminalWhileLocked();
						Consumer<MicrohttpResponse> callback = !canceled
								&& response != null ? takeResponseCallback() : null;
						responseCallback = null;
						releaseIdentifiedRequestExchange();
						return new ProtocolResponseReservation(callback, response, null);
					}).orElse(null);
					if (reservation == null) {
						protocolTask = null;
						canceled = true;
						markTerminalWhileLocked();
						responseCallback = null;
						releaseIdentifiedRequestExchange();
					}
				}
			} finally {
				McpHttpServerRuntime.this.applicationExecutionObserver.endDeferral();
			}

			if (reservation == null) {
				finishTransportLifecycle();
				finishRequestObservation(McpRequestOutcome.CANCELED, null, List.of());
				return;
			}
			if (reservation.deadlineExpiration() != null) {
				finishProtocolDeadline(reservation.deadlineExpiration());
				return;
			}
			if (reservation.responseCallback() != null) {
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
						reservation.responseCallback(), observedResponse);
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
				if (!applicationOwned || streamAbortOwned || canceled || terminal)
					return false;
				stream = responseStream;
			}

			if (stream != null) {
				stream.enqueueMessage(notification);
				return true;
			}

			StreamObservationOpenTransition openTransition = null;
			try {
				synchronized (streamObservationTransitionLock) {
					synchronized (lock) {
						if (!applicationOwned || streamAbortOwned || canceled
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
							nextKeepAliveNanos = saturatingAdd(
									applicationClock.nanoTime(),
									transportConfiguration.keepAliveInterval().toNanos());
							callback = takeResponseCallback();
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

			stream.enqueueMessage(notification);
			return true;
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

				applicationOwned = false;
				protocolTask = null;
				stream = responseStream;
				if (stream == null) {
					markTerminalWhileLocked();
					callback = takeResponseCallback();
					releaseIdentifiedRequestExchange();
				} else
					streamTerminalResponseOwned = true;
			}

			if (stream == null) {
				ApplicationResponseRendering rendering = renderApplicationResponse(
						protocolProfile(), response, requestId, additionalHeaders,
						publicRequestContext().orElse(null));
				planRequestObservation(rendering.observationResult());
				boolean trackBody = tracksLifecycleResponseBody();
				if (!trackBody)
					finishTransportLifecycle();
				MicrohttpResponse observedResponse = withRequestObservationTermination(
						rendering.response(), rendering.observationResult());
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
			planRequestObservation(requestObservationResult(response));

			if (response.message().isEmpty())
				return stream.fail(StreamTerminationReason.RESPONSE_TIMEOUT, null);

			McpJsonRpcMessage message = response.message().orElseThrow();
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
						envelopeCodec.encode(message);
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
		private McpRequestSseStream newResponseStream() {
			if (simulation != null) {
				McpRequestSseStream.Listener listener = this::streamTerminated;
				return new McpRequestSseStream(envelopeCodec,
						simulation.openChannel(listener));
			}
			return new McpRequestSseStream(
					transportConfiguration.streamQueueCapacity(),
					jsonLimits,
					envelopeCodec,
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
			FutureTask<Void> task;
			McpRequestSseStream stream;
			SubscriptionRegistration subscription;
			boolean completedStream;
			boolean remove;
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
				pendingSubscriptionStreamFailure = null;
				subscriptionOwned = false;
				preRenderedSubscriptionTerminal = null;
				completedStream = stream != null && stream.isTerminalWritten();
				remove = !applicationOwned;
				if (applicationOwned) {
					// Application cancellation runs under the ownership lock. Its
					// terminal cleanup is reentrant, and this closes the gap in which a
					// queued deadline or handler response could otherwise win after the
					// transport had already marked this control canceled.
					application.cancel(request, requireNonNull(reason), cause);
					if (!terminal) {
						applicationOwned = false;
						markTerminalWhileLocked();
						responseCallback = null;
						releaseIdentifiedRequestExchange();
						remove = true;
					}
				} else {
					markTerminalWhileLocked();
					responseCallback = null;
					releaseIdentifiedRequestExchange();
				}
			}

			if (task != null) {
				task.cancel(true);
				processor.remove(task);
			}
			if (stream != null)
				stream.close(reason, cause);
			if (subscription != null)
				removeSubscription(this, subscription);
			if (stream != null)
				markStreamClosed(completedStream
						? StreamTerminationReason.COMPLETED : reason);
			if (remove)
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
			McpRequestSseStream stream;
			boolean subscriptionStream;
			boolean completeExpiredSubscription;
			SubscriptionStreamFailure pendingFailure;
			synchronized (lock) {
				if (terminal || canceled)
					return;
				pendingFailure = pendingSubscriptionStreamFailure;
				pendingSubscriptionStreamFailure = null;
				stream = responseStream;
				subscriptionStream = subscriptionRegistration != null;
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
				if (!subscriptionStream
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

				boolean terminateForBackpressure = false;
				boolean keepAliveEmitted = false;
				try {
					synchronized (streamObservationTransitionLock) {
						synchronized (lock) {
							if (terminal || canceled || responseStream != stream
									|| streamTerminalResponseOwned || streamAbortOwned
									|| nowNanos - deadlineNanos >= 0L)
								return;
							if (nowNanos - nextKeepAliveNanos >= 0L) {
								McpOutboundChannel.OfferResult result =
										stream.offerKeepAlive();
								if (result == McpOutboundChannel.OfferResult.ACCEPTED) {
									nextKeepAliveNanos = saturatingAdd(nowNanos,
											transportConfiguration.keepAliveInterval()
													.toNanos());
									keepAliveEmitted = true;
								} else if (result
										== McpOutboundChannel.OfferResult.FULL
										|| result == McpOutboundChannel.OfferResult.TOO_LARGE) {
									streamAbortOwned = true;
									terminateForBackpressure = true;
								}
							}
						}
						if (keepAliveEmitted)
							markKeepAliveEmitted();
					}
				} finally {
					drainApplicationExecutionObservation();
				}
				if (terminateForBackpressure)
					stream.fail(StreamTerminationReason.BACKPRESSURE, null);
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

		private void streamTerminated(@NonNull StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason exactReason,
				@Nullable Throwable cause) {
			requireNonNull(reason);
			boolean cancelApplication;
			boolean preserveApplication;
			SubscriptionRegistration subscription;
			StreamTerminationReason observedStreamReason;
			synchronized (lock) {
				if (terminal)
					return;

				preserveApplication = gracefulTransportClosed && applicationOwned
						&& reason == StreamTerminationReason.SERVER_STOPPING;
				cancelApplication = applicationOwned
						&& reason != StreamTerminationReason.COMPLETED
						&& !preserveApplication;
				if (reason != StreamTerminationReason.COMPLETED)
					canceled = true;
				if (!preserveApplication)
					applicationOwned = false;
				subscriptionOwned = false;
				subscription = subscriptionRegistration;
				subscriptionRegistration = null;
				pendingSubscriptionStreamFailure = null;
				preRenderedSubscriptionTerminal = null;
				observedStreamReason = reason == StreamTerminationReason.COMPLETED
						&& plannedSubscriptionCloseReason != null
						? plannedSubscriptionCloseReason : reason;
				markTerminalWhileLocked();
				protocolTask = null;
				responseCallback = null;
				releaseIdentifiedRequestExchange();
			}

			if (subscription != null)
				removeSubscription(this, subscription);
			markStreamClosed(observedStreamReason, exactReason);
			finishTransportLifecycle();
			if (reason == StreamTerminationReason.COMPLETED)
				finishPlannedRequestObservation(requestObservationResult(reason, cause));
			else if (exactReason == McpStreamTerminationReason
					.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED
					|| exactReason == McpStreamTerminationReason
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

		@NonNull
		private ProtocolDeadlineExpiration detachProtocolDeadline(boolean cancelTask) {
			canceled = true;
			markTerminalWhileLocked();
			FutureTask<Void> task = protocolTask;
			protocolTask = null;
			Consumer<MicrohttpResponse> callback = takeResponseCallback();
			releaseIdentifiedRequestExchange();
			return new ProtocolDeadlineExpiration(
					cancelTask ? task : null, callback, deadlineResponseHeaders);
		}

		private void finishProtocolDeadline(
				@NonNull ProtocolDeadlineExpiration expiration) {
			requireNonNull(expiration);
			if (expiration.task() != null) {
				expiration.task().cancel(true);
				processor.remove(expiration.task());
			}
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
			synchronized (lock) {
				observeRequest = requestObservation != null;
			}
			if (!observeRequest && !trackBody)
				return response;
			MicrohttpResponse observedResponse = response.withBodyTerminationListener(
					(reason, cause) -> {
				try {
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

		@NonNull
		private MicrohttpResponse withLifecycleResponseTermination(
				@NonNull MicrohttpResponse response) {
			requireNonNull(response);
			if (!tracksLifecycleResponseBody())
				return response;
			MicrohttpResponse trackedResponse = response.withBodyTerminationListener(
					(reason, cause) -> finishTransportLifecycle());
			markLifecycleTransportStarted();
			return trackedResponse;
		}

		private boolean tracksLifecycleResponseBody() {
			return lifecycleAdmission != null;
		}

		private @Nullable Throwable deliverResponse(
				@NonNull Consumer<@NonNull MicrohttpResponse> callback,
				@NonNull MicrohttpResponse response) {
			try {
				callback.accept(response);
				return null;
			} catch (Throwable throwable) {
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

	private enum SubscriptionOpenResult {
		OPENED,
		CAPACITY_REJECTED,
		SERVER_STOPPING,
		TERMINATED,
		LOCALIZATION_FAILED,
		TERMINAL_PREFLIGHT_FAILED
	}

	private enum SubscriptionActivationResult {
		ACTIVATED_CURRENT_LOCALIZATION,
		ACTIVATED_STALE_LOCALIZATION,
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
			@Nullable SubscriptionRegistration registration,
			@Nullable Object localizationInvalidationToken) {
		private SubscriptionCapReservation {
			requireNonNull(result);
			boolean opened = result == SubscriptionOpenResult.OPENED;
			if (opened != (registration != null)
					|| opened != (localizationInvalidationToken != null))
				throw new IllegalArgumentException(
						"Exactly an opened MCP subscription cap retains registration and invalidation state.");
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
			@NonNull List<@NonNull SubscriptionResource> resourceSubscriptions) {
		private AcceptedSubscriptionFilter {
			resourceSubscriptions = List.copyOf(
					requireNonNull(resourceSubscriptions));
		}

		@NonNull
		private List<@NonNull URI> requestedResourceSubscriptionUris() {
			return resourceSubscriptions.stream()
					.map(SubscriptionResource::uri)
					.toList();
		}

		private boolean contains(@NonNull URI resourceUri) {
			requireNonNull(resourceUri);
			return resourceSubscriptionsIncluded && resourceSubscriptions.stream()
					.anyMatch(resource -> resource.uri().equals(resourceUri));
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
			@NonNull McpWireResult toolsList,
			@NonNull McpWireResult promptsList,
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

	private record EndpointRuntime(@NonNull McpHttpEndpointBinding binding,
			@NonNull McpServerCapabilityRegistry capabilityRegistry,
			@NonNull Map<@NonNull String, @NonNull ProfileFrameworkResponses>
					frameworkResponsesByRevision) {
		private EndpointRuntime {
			requireNonNull(binding);
			requireNonNull(capabilityRegistry);
			frameworkResponsesByRevision = Map.copyOf(
					requireNonNull(frameworkResponsesByRevision));
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
			@NonNull List<@NonNull Header> responseHeaders) {
		private ProtocolDeadlineExpiration {
			requireNonNull(responseCallback);
			responseHeaders = List.copyOf(responseHeaders);
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
